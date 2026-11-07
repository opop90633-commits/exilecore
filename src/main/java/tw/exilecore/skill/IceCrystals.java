package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冰晶（2026-10，照 PoE2 的 Ice Crystals）：霜壁、凍壁矢這類技能（技能自己帶 crystal.count）留下幾顆冰晶。
 * 你擊中冰晶旁邊（{@value #HIT_RADIUS} 格內）的敵人、或技能直接打到冰晶（2026-10-25：揮到、炸到、投射物飛過）時，冰晶被打一下；打 {@value #BASE_HITS} 下（crystal.hits 可以改）就碎掉，
 * 炸出一圈冰（隱藏技能 proc_crystal_burst，用留下冰晶那個技能的寶石等級）。時間到沒打碎就自己融掉，不會炸。
 * 輔助：厚晶（要打更多下）、脆晶（更少下）、霜蝕晶（冰晶周圍一直冰）、晶刺（碎掉時往四周噴冰片）、冰鍍（打碎時你得到附加冰冷的增益）。
 * <p>
 * 2026-10-28 照 PoE2（RePoE frost_wall_maximum_life）：留下冰晶的技能寫了 level_modifiers 的 limit.crystal_life（霜壁、凍壁矢），
 * 冰晶就有那麼多生命，你的擊中（打中旁邊的敵人、或範圍直接蓋到冰晶）照那一下減免前的傷害扣生命，扣完才碎；厚晶、脆晶照「要打幾下」的比例放大縮小生命。
 * 沒寫的（寒晶雷這種）照舊打幾下就碎。這次之前一律打三下就碎。
 */
public final class IceCrystals implements Listener {

    static final int BASE_HITS = 3;
    static final double HIT_RADIUS = 2.4;
    static final double BASE_SECONDS = 8;
    static final int MAX_PER_PLAYER = 60; // 2026-11-01：霜壁整道都是冰晶（3 道 × 十幾顆）；這次之前 20
    /**
     * 2026-11-01 照 PoE2 Frost Wall（a wall of Ice Crystals which holds back enemies）：技能修飾器寫了這個（FLAT 1）的牆（line／環壁），
     * 整道牆是一顆一顆的冰晶（SkillExecutor.placeWall → {@link #spawnWall}）：擋住敵人、自己不打傷害，碎掉時照放它的技能
     * （輔助、加成照吃）炸出 2 公尺的冰（{@link ColdSpells#WALL_BURST}），用了電灌注再多一次 2.8 公尺的閃電爆炸（{@link ColdSpells#WALL_VOLT}）。
     * 被擋住的怪物照自己的攻擊節奏打冰晶（{@link #enemyStrikes}），扣完生命就碎。推不動的（Boss）撞上去直接把冰晶推碎（原作 explode if pushed hard enough）；放下 0.5 秒內被敵人推碎的爆炸更少 80%
     * （ice_crystals_damage_+%_final_for_first_500_ms_when_not_killed_by_self）。這次之前霜壁是一道打傷害的地面效果加 4 顆冰晶、不擋路。
     */
    static final String WALL = "crystal.wall";
    /** 冰晶牆擋路的半徑（格，量到敵人的中心再加牠的半寬）。 */
    static final double WALL_BLOCK_RADIUS = 0.7;
    /** 放下多久內被敵人推碎算「剛放下」（0.5 秒）、那時的爆炸更少幾成。 */
    static final int WALL_EARLY_TICKS = 10;
    static final double WALL_EARLY_LESS = 0.8;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, List<Crystal>> crystals = new HashMap<>();

    private static final class Crystal {
        final Location at;
        final BlockDisplay display;
        final long untilTick;
        final int level;
        final boolean shards;
        final boolean verglas;
        int hitsLeft;
        /** 2026-10-28 照 PoE2：冰晶剩下的生命（0 以下 = 照舊算打幾下）。 */
        double life;
        /** 2026-10-30：冰晶的最大生命（PoE2 的數值；冰淞照這個算）。 */
        double maxLife;
        /** 2026-10-28 照 PoE2：碎掉時炸的隱藏技能（凍壁矢的冰牆有自己的表）；這次之前一律 proc_crystal_burst。 */
        String burst = "proc_crystal_burst";
        /** 2026-11-01 照 PoE2 Frost Wall：冰晶牆的一顆（碎掉時照這個技能炸；null = 一般冰晶）、要不要再炸閃電、什麼時候放的。 */
        SkillBuild wall;
        boolean volt;
        long born;
        /** 2026-11-07：留下這顆冰晶的技能（午夜天頂照這個數自己的冰晶上限）。 */
        String skill = "";
        long lastHit;
        Object lastSource;

        Crystal(final Location at, final BlockDisplay display, final long untilTick, final int level, final int hits, final boolean shards,
                final boolean verglas) {
            this.at = at;
            this.display = display;
            this.untilTick = untilTick;
            this.level = level;
            this.hitsLeft = hits;
            this.shards = shards;
            this.verglas = verglas;
        }
    }

    IceCrystals(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        service.targets().addStrikeable(this::struck); // 2026-10-25：技能直接打到冰晶
    }

    /** 冰晶自己炸出來的、旁邊的霜蝕地面不打冰晶（避免連鎖一次全碎、站著就自己碎）。 */
    private static boolean ignored(final String skillId) {
        return skillId.startsWith("proc_crystal") || skillId.startsWith("proc_hoarfrost");
    }

    /** 2026-10-25：技能打到的範圍裡有自己的冰晶：直接打一下（不用旁邊有敵人）。 */
    private void struck(final Player owner, final tw.exilecore.stats.SkillProfile skill, final java.util.function.Predicate<Location> inside,
                        final Object source) {
        final List<Crystal> mine = crystals.get(owner.getUniqueId());
        if (mine == null || mine.isEmpty() || ignored(skill.id())) {
            return;
        }
        final long now = now();
        final List<Crystal> broken = new ArrayList<>();
        for (final Iterator<Crystal> it = mine.iterator(); it.hasNext(); ) {
            final Crystal crystal = it.next();
            if (crystal.lastHit == now || crystal.lastSource == source || !inside.test(crystal.at)) {
                continue;
            }
            crystal.lastSource = source; // 同一顆投射物飛過去好幾 tick 只算一下
            // 2026-10-28 照 PoE2：有生命的冰晶照這一下打出去的傷害扣（沒有敵人可以算，就對沒有防禦的目標估一次）
            final double damage = crystal.life > 0 ? service.leftovers().outgoing(owner, skill).total() : 0;
            if (chip(crystal, now, damage)) {
                it.remove();
                broken.add(crystal);
            }
        }
        for (final Crystal crystal : broken) {
            shatter(owner, crystal);
        }
    }

    /** 冰晶被打一下（damage：那一下減免前的傷害，有生命的冰晶照這個扣）；碎了回傳 true。 */
    private static boolean chip(final Crystal crystal, final long now, final double damage) {
        crystal.lastHit = now; // 同一下打中好幾個敵人只算一下
        tw.exilecore.skill.Fx.spawn(crystal.at.getWorld(), Particle.ITEM_SNOWBALL, crystal.at, 6, 0.2, 0.3, 0.2, 0.05);
        if (crystal.life > 0) {
            crystal.life -= Math.max(0, damage);
            return crystal.life <= 0;
        }
        crystal.hitsLeft--;
        return crystal.hitsLeft <= 0;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個技能（照它的屬性）要留下幾顆冰晶；0 = 不留。 */
    static int count(final SkillBuild build, final StatSheet sheet) {
        return Math.max(0, Math.min(8, (int) Math.round(build.profile().effective(sheet).flat(StatKeys.CRYSTAL_COUNT, build.profile().tags()))));
    }

    /** 在 center 留下 count 顆冰晶：一顆放正中間，多顆沿著 along（單位向量）排成一排、間隔 1.6 格。 */
    void spawn(final Player owner, final SkillBuild build, final Location center, final int count, final Vector along) {
        if (count <= 0 || center.getWorld() == null) {
            return;
        }
        final Vector step = along == null || along.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : along.clone().setY(0).normalize();
        final List<Location> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double offset = (i - (count - 1) / 2.0) * 1.6;
            final Location at = center.clone().add(step.clone().multiply(offset));
            at.setY(Math.floor(center.getY()));
            points.add(at);
        }
        place(owner, build, points, false);
        center.getWorld().playSound(center, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    /** 2026-11-01（凍壁矢的兩道冰牆，CrystalWalls）：在 center 加上每個 {dx, dz} 的地方各留一顆冰晶。 */
    void spawnAt(final Player owner, final SkillBuild build, final Location center, final List<double[]> offsets) {
        if (offsets.isEmpty() || center.getWorld() == null) {
            return;
        }
        final List<Location> points = new ArrayList<>();
        for (final double[] offset : offsets) {
            final Location at = center.clone().add(offset[0], 0, offset[1]);
            at.setY(Math.floor(center.getY()));
            points.add(at);
        }
        place(owner, build, points, false);
        center.getWorld().playSound(center, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    /** 2026-11-01 照 PoE2 Frost Wall：整道牆每一點一顆冰晶（{@link #WALL}）。 */
    void spawnWall(final Player owner, final SkillBuild build, final List<Location> points) {
        if (points.isEmpty() || points.getFirst().getWorld() == null) {
            return;
        }
        final List<Location> spots = new ArrayList<>();
        for (final Location point : points) {
            final Location at = point.clone();
            at.setY(Math.floor(point.getY() + 0.01));
            spots.add(at);
        }
        place(owner, build, spots, true);
        final Location middle = points.get(points.size() / 2);
        middle.getWorld().playSound(middle, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        ensureWallTask();
    }

    private void place(final Player owner, final SkillBuild build, final List<Location> points, final boolean wall) {
        place(owner, build, points, wall, BASE_SECONDS);
    }

    /**
     * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」（PoE2 Midnight Zenith）：在 center 周圍 radius 格的一圈平均放 count 顆冰晶，
     * 持續 seconds 秒（吃技能持續時間）。
     */
    void spawnRing(final Player owner, final SkillBuild build, final Location center, final int count, final double radius, final double seconds) {
        if (count <= 0 || center.getWorld() == null) {
            return;
        }
        final List<Location> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double angle = Math.PI * 2 * i / count;
            final Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            at.setY(Math.floor(center.getY() + 0.01));
            points.add(at);
        }
        place(owner, build, points, false, seconds);
        center.getWorld().playSound(center, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.8f, 1.6f);
    }

    /** 2026-11-07：這個玩家現在有幾顆某個技能留下的冰晶（午夜天頂的上限）。 */
    int count(final UUID owner, final String skillId) {
        final List<Crystal> mine = crystals.get(owner);
        if (mine == null) {
            return 0;
        }
        int count = 0;
        for (final Crystal crystal : mine) {
            if (skillId.equals(crystal.skill) && crystal.display.isValid()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」（PoE2 暮色瓣落「Ice Crystals have (-3—3)% reduced maximum Life per 5% Cold Resistance you have」，
     * 屬性 ice_crystal_maximum_life_+%_per_5%_cold_resistance）：冰晶最大生命的倍率。perFive 是每 5% 冰冷抗性增加幾 %（物品減少寫負的），
     * coldResist 是你現在的冰冷抗性（照上限；PoB2 PerStat ColdResist 是封頂後的、往下取整）。最低 0。純函式（測試用）。
     */
    static double coldLifeFactor(final double perFive, final double coldResist) {
        if (perFive == 0) {
            return 1;
        }
        return Math.max(0, 1 + perFive / 100.0 * Math.floor(coldResist / 5.0 + 1e-9));
    }

    private void place(final Player owner, final SkillBuild build, final List<Location> points, final boolean wall, final double seconds) {
        final int count = points.size();
        final StatSheet sheet = plugin.players().sheet(owner);
        final StatSheet effective = build.profile().effective(sheet);
        final java.util.Set<String> tags = build.profile().tags();
        final int hits = (int) Math.max(1, Math.round(effective.apply(StatKeys.CRYSTAL_HITS,
                BASE_HITS + effective.flat(StatKeys.CRYSTAL_HITS, tags), tags)));
        final boolean hoarfrost = StatKeys.flag(effective, StatKeys.FLAG_HOARFROST);
        final boolean shards = StatKeys.flag(effective, StatKeys.FLAG_CRYSTAL_SHARDS);
        final boolean verglas = StatKeys.flag(effective, StatKeys.FLAG_VERGLAS);
        // 2026-10-28 照 PoE2：冰晶的生命（技能逐級表；厚晶、脆晶照要打幾下的比例）
        // 2026-11-01 照 PoE2 Icicle（脆晶）：冰晶最大生命更少 50%（crystal.life）；照「打幾下」算的冰晶下數照比例無條件進位（3 下 → 2 下）
        // 2026-11-07（暮色瓣落）：每 5% 冰冷抗性增加／減少冰晶的最大生命（照你身上的封頂冰冷抗性）
        final double coldFactor = coldLifeFactor(sheet.flat(tw.exilecore.item.unique.UniqueStats.CRYSTAL_LIFE_PER_5_COLD, java.util.Set.of()),
                Math.min(sheet.get(tw.exilecore.stats.DamageType.COLD.mitigationStat()),
                        tw.exilecore.stats.DamageCalculator.resistCap(sheet, tw.exilecore.stats.DamageType.COLD)));
        final double lifeMore = Math.max(0, effective.more(tw.exilecore.stats.SupportParityKeys.CRYSTAL_LIFE, tags)) * coldFactor;
        final double life = StageLeftovers.scaledLife(StageAttacks.limit(build.profile(), "crystal_life", 0), hits, BASE_HITS) * lifeMore;
        final int hitsLeft = tw.exilecore.stats.SupportParityKeys.crystalHits(hits, lifeMore);
        final long until = now() + build.durationTicks(seconds);
        final List<Crystal> mine = crystals.computeIfAbsent(owner.getUniqueId(), key -> new ArrayList<>());
        final List<Location> spots = new ArrayList<>();
        final List<java.util.function.BooleanSupplier> alive = new ArrayList<>();
        final boolean volt = wall && SkillZones.localFlat(build.profile(), ColdSpells.WALL_VOLT_FLAG) > 0;
        for (int i = 0; i < count; i++) {
            final Location at = points.get(i).clone();
            final BlockDisplay display = at.getWorld().spawn(at.clone().add(-0.35, 0, -0.35), BlockDisplay.class, d -> {
                d.setBlock(Material.BLUE_ICE.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 1.1f, 0.7f), new AxisAngle4f()));
                d.setPersistent(false);
                d.setGlowing(true);
            });
            final Crystal crystal = new Crystal(at.clone().add(0, 0.5, 0), display, until, build.usableGemLevel(), hitsLeft, shards, verglas);
            crystal.burst = StageAttacks.CRYSTAL_BURST.getOrDefault(build.definition().id(), crystal.burst);
            crystal.life = life;
            crystal.maxLife = life;
            crystal.skill = build.definition().id();
            if (wall) {
                crystal.wall = build;
                crystal.volt = volt;
                crystal.born = now();
            }
            mine.add(crystal);
            spots.add(at.clone());
            alive.add(display::isValid);
            if (hoarfrost) {
                cast(owner, "proc_hoarfrost", crystal.level, crystal.at);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(owner.getUniqueId(), crystal), until - now() + 1);
        }
        service.chargeEffects().creepingChill(owner, build, spots, alive, until - now()); // 2026-10-24：蔓寒
        while (mine.size() > MAX_PER_PLAYER) {
            final Crystal oldest = mine.removeFirst();
            oldest.display.remove();
        }
    }

    // ---- 2026-11-01 照 PoE2 Frost Wall：冰晶牆擋路 ----

    private org.bukkit.scheduler.BukkitTask wallTask;

    private void ensureWallTask() {
        if (wallTask == null) {
            wallTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickWalls, 1, 1);
        }
    }

    /** 每 2 tick：走進冰晶牆的敵人放回牆邊（貼著牆滑、穿不過去）；推不動的（Boss）把那顆推碎。 */
    private void tickWalls() {
        final long now = now();
        if (now % 2 != 0) {
            return; // 每 2 tick 擋一次（怪物 2 tick 走不過 1 格多厚的冰晶）
        }
        boolean any = false;
        for (final Map.Entry<UUID, List<Crystal>> entry : crystals.entrySet()) {
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            final List<Crystal> pushed = new ArrayList<>();
            for (final Crystal crystal : entry.getValue()) {
                if (crystal.wall == null || !crystal.display.isValid()) {
                    continue;
                }
                any = true;
                for (final org.bukkit.entity.LivingEntity enemy : service.targets().around(crystal.at, WALL_BLOCK_RADIUS + 1.0, owner)) {
                    final double reach = WALL_BLOCK_RADIUS + enemy.getWidth() / 2;
                    if (!inside(crystal.at, enemy.getLocation(), reach)) {
                        continue;
                    }
                    if (tw.exilecore.combat.Knockbacks.immune(enemy) || service.isTotem(enemy)) {
                        if (!service.isTotem(enemy)) {
                            pushed.add(crystal); // 原作：推得夠用力就碎（Boss 推不動、直接推碎冰晶）
                        }
                        break;
                    }
                    holdBack(enemy, crystal.at, reach);
                    if (!pushed.contains(crystal) && enemyStrikes(enemy, crystal, now)) {
                        pushed.add(crystal); // 2026-11-01 照 PoE2：被擋住的敵人打冰晶，生命扣完就碎
                    }
                }
            }
            for (final Crystal crystal : pushed) {
                if (entry.getValue().remove(crystal)) {
                    shatter(owner, crystal, now - crystal.born < WALL_EARLY_TICKS);
                }
            }
        }
        if (!any && wallTask != null) {
            wallTask.cancel();
            wallTask = null;
        }
    }

    /** 被冰晶牆擋住的敵人：下一次能打冰晶的 tick。 */
    private final Map<UUID, Long> wallSwing = new HashMap<>();

    /**
     * 2026-11-01 照 PoE2 Frost Wall（The Crystals explode if sufficiently damaged：敵人也打得壞冰晶）：被這顆冰晶擋住的怪物照自己的攻擊節奏
     * 打冰晶（每下 1 秒 ÷ 攻擊速度，含詛咒的攻擊速度更少），每下扣「這隻怪一般攻擊的平均物理傷害」（怪物屬性表的武器傷害 × 傷害加成）；
     * 只有被擋住的那幾隻會打，不另外掃描。回傳這顆碎了沒。
     */
    private boolean enemyStrikes(final org.bukkit.entity.LivingEntity enemy, final Crystal crystal, final long now) {
        if (!(enemy instanceof org.bukkit.entity.Mob) || plugin.monsters() == null) {
            return false;
        }
        final Long ready = wallSwing.get(enemy.getUniqueId());
        if (ready != null && ready > now) {
            return false;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(enemy).orElse(null);
        if (data == null) {
            return false;
        }
        StatSheet sheet = plugin.monsters().sheet(enemy, data);
        if (plugin.combat() != null) {
            final List<tw.exilecore.stats.Modifier> cursed = plugin.combat().debuffs().modifiers(enemy.getUniqueId(), null, now);
            if (!cursed.isEmpty()) {
                sheet = sheet.overlay(cursed);
            }
        }
        final java.util.Set<String> tags = java.util.Set.of(tw.exilecore.stats.Tags.ATTACK, tw.exilecore.stats.Tags.MELEE, tw.exilecore.stats.Tags.PHYSICAL);
        final double speed = plugin.ailments() == null ? 1 : plugin.ailments().actionSpeed(enemy);
        if (speed <= 0) {
            return false; // 凍結、暈眩中不能打
        }
        wallSwing.put(enemy.getUniqueId(), now + monsterSwingTicks(sheet.apply(StatKeys.ATTACK_SPEED, 1.0, tags) * speed));
        if (wallSwing.size() > 512) {
            wallSwing.values().removeIf(tick -> tick < now);
        }
        final double physical = (sheet.flat(StatKeys.WEAPON_MIN, java.util.Set.of(tw.exilecore.stats.Tags.PHYSICAL))
                + sheet.flat(StatKeys.WEAPON_MAX, java.util.Set.of(tw.exilecore.stats.Tags.PHYSICAL))) / 2;
        final double damage = physical * Math.max(0, sheet.apply(StatKeys.DAMAGE, 1.0, tags));
        enemy.swingMainHand();
        return chip(crystal, now, damage);
    }

    /** 純函式：怪物打冰晶的間隔（tick）：1 秒 ÷ 攻擊速度（夾在 0.3～3 倍，跟怪物近戰一樣）。 */
    static long monsterSwingTicks(final double attackSpeed) {
        return Math.max(1, Math.round(20 / Math.max(0.3, Math.min(3.0, attackSpeed))));
    }

    /** 純函式：(x, z) 水平離 center 不到 reach（高度差 2 格內）。 */
    static boolean inside(final Location center, final Location at, final double reach) {
        if (!center.getWorld().equals(at.getWorld()) || Math.abs(at.getY() - (center.getY() - 0.5)) > 2) {
            return false;
        }
        final double dx = at.getX() - center.getX();
        final double dz = at.getZ() - center.getZ();
        return dx * dx + dz * dz < reach * reach;
    }

    /** 純函式：把在 (dx, dz)（離冰晶中心）的敵人放到 reach 外面的那一點（從中心往外；剛好在中心就往 +x）。 */
    static double[] pushOut(final double dx, final double dz, final double reach) {
        final double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-4) {
            return new double[] {reach + 0.02, 0};
        }
        final double scale = (reach + 0.02) / length;
        return new double[] {dx * scale, dz * scale};
    }

    /** 走進冰晶裡的敵人放回冰晶外緣（跟 Boss 的風暴牆一樣：不彈開，只是擋住），水平速度歸零。 */
    private static void holdBack(final org.bukkit.entity.LivingEntity enemy, final Location center, final double reach) {
        final org.bukkit.entity.Entity mover = enemy.getVehicle() != null ? enemy.getVehicle() : enemy;
        final Location at = mover.getLocation();
        final double[] out = pushOut(at.getX() - center.getX(), at.getZ() - center.getZ(), reach);
        final Location edge = at.clone();
        edge.setX(center.getX() + out[0]);
        edge.setZ(center.getZ() + out[1]);
        if (!edge.getBlock().isPassable() || !edge.clone().add(0, 1, 0).getBlock().isPassable()) {
            return; // 牆邊是方塊：不放進方塊裡
        }
        final Vector velocity = mover.getVelocity();
        mover.teleport(edge, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        mover.setVelocity(new Vector(0, Math.min(0, velocity.getY()), 0));
    }

    private void expire(final UUID owner, final Crystal crystal) {
        final List<Crystal> mine = crystals.get(owner);
        if (mine != null && mine.remove(crystal)) {
            crystal.display.remove();
            tw.exilecore.skill.Fx.spawn(crystal.at.getWorld(), Particle.SNOWFLAKE, crystal.at, 8, 0.3, 0.4, 0.3, 0.01);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.skill() == null) {
            return;
        }
        if (impaling(event.skill())) {
            // 2026-11-01 照 PoE2 Crystalline Shards：冰晶爆炸 100% 穿刺（ice_crystal_explosion_cold_damage_can_impale：冰冷傷害也算）
            final double impaled = event.result().outgoing().get(tw.exilecore.stats.DamageType.PHYSICAL)
                    + event.result().outgoing().get(tw.exilecore.stats.DamageType.COLD);
            if (impaled > 0) {
                final double magnitude = event.skill().effective(plugin.players().sheet(player))
                        .increased(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_MAGNITUDE, event.skill().tags());
                tw.exilecore.combat.Impales.shared().add(event.defender().getUniqueId(), player.getUniqueId(),
                        tw.exilecore.stats.KeystoneOriginKeys.impaleAmount(impaled, magnitude));
                tw.exilecore.skill.Fx.spawn(event.defender().getWorld(), Particle.CRIT, event.defender().getLocation().add(0, event.defender().getHeight() * 0.6, 0),
                        6, 0.2, 0.3, 0.2, 0.05);
            }
        }
        final List<Crystal> mine = crystals.get(player.getUniqueId());
        if (mine == null || mine.isEmpty() || ignored(event.skill().id())) { // 2026-10-17：冰晶旁邊的霜蝕地面也不打冰晶（以前站著就自己碎）
            return; // 冰晶自己炸出來的不再打冰晶（避免連鎖一次全碎）
        }
        final Location hit = event.defender().getLocation();
        final long now = now();
        final List<Crystal> broken = new ArrayList<>();
        for (final Iterator<Crystal> it = mine.iterator(); it.hasNext(); ) {
            final Crystal crystal = it.next();
            if (!crystal.at.getWorld().equals(hit.getWorld()) || crystal.at.distanceSquared(hit) > HIT_RADIUS * HIT_RADIUS
                    || crystal.lastHit == now) {
                continue;
            }
            if (chip(crystal, now, event.result().outgoing().total())) {
                it.remove();
                broken.add(crystal);
            }
        }
        for (final Crystal crystal : broken) {
            shatter(player, crystal);
        }
    }

    /** 2026-10-26（碎晶嘯，PoE2 Vruun's Aftermath）：戰吼讓範圍內你的冰晶全部炸掉；回傳炸掉幾顆。 */
    int explodeAround(final Player owner, final Location center, final double radius) {
        final List<Crystal> mine = crystals.get(owner.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return 0;
        }
        final List<Crystal> broken = new ArrayList<>();
        for (final Iterator<Crystal> it = mine.iterator(); it.hasNext(); ) {
            final Crystal crystal = it.next();
            if (crystal.at.getWorld().equals(center.getWorld()) && crystal.at.distanceSquared(center) <= radius * radius) {
                it.remove();
                broken.add(crystal);
            }
        }
        for (final Crystal crystal : broken) {
            shatter(owner, crystal);
        }
        return broken.size();
    }

    private void shatter(final Player owner, final Crystal crystal) {
        shatter(owner, crystal, false);
    }

    /** early：2026-11-01 照 PoE2 Frost Wall：放下 0.5 秒內被敵人推碎（爆炸更少 80%）。 */
    private void shatter(final Player owner, final Crystal crystal, final boolean early) {
        crystal.display.remove();
        crystal.at.getWorld().playSound(crystal.at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 0.9f);
        if (crystal.wall != null) {
            wallBurst(owner, crystal, early);
            verglas(owner, crystal.maxLife);
            return;
        }
        // 2026-11-01 照 PoE2 Crystalline Shards（晶刺）：被輔助的技能留下的冰晶爆炸 100% 穿刺打到的敵人（冰冷傷害也能穿刺、不抽出穿刺）；
        // 這次之前另外往四周噴 8 道冰片（proc_crystal_shards）
        cast(owner, crystal.burst, crystal.level, crystal.at, crystal.shards);
        verglas(owner, crystal.maxLife);
    }

    /** 2026-10-30 照 PoE2 Verglas：冰晶最大生命每 2000 點，被冰淞輔助的技能 6 秒內獲得 1% 傷害為額外冰冷傷害。 */
    static final double VERGLAS_LIFE_PER_PERCENT = 2000;
    static final int VERGLAS_TICKS = 120;
    /** 被冰淞輔助的技能身上的標籤（SkillBuild 加上；增益只給有這個標籤的技能）。 */
    public static final String VERGLAS_TAG = "frost_shell";
    /** 玩家 → {這一次冰淞的 %, 到期的 tick}。 */
    private final Map<UUID, double[]> verglas = new HashMap<>();

    /** 打碎一顆冰晶：照它的最大生命給冰淞的增益（同時只有一份：比現在的強才換，不然只刷新時間）。 */
    private void verglas(final Player owner, final double crystalLife) {
        final double percent = Math.floor(Math.max(0, crystalLife) / VERGLAS_LIFE_PER_PERCENT);
        if (percent <= 0) {
            return;
        }
        final long now = now();
        final double[] current = verglas.get(owner.getUniqueId());
        final double value = current != null && current[1] > now ? Math.max(current[0], percent) : percent;
        verglas.put(owner.getUniqueId(), new double[] {value, now + VERGLAS_TICKS});
        // 2026-11-07 修正（檢查發現）：「傷害的一部分為額外冰冷傷害」用 damage.extra.cold（所有傷害都吃）；這次之前用 damage.gain.cold，
        //   DamageCalculator 只對物理來源算 gain，冰冷的霜壁完全沒加到
        service.buff(owner, "frost_shell", "冰淞", VERGLAS_TICKS, List.of(new tw.exilecore.stats.Modifier(StatKeys.damageExtra(tw.exilecore.stats.DamageType.COLD),
                tw.exilecore.stats.ModType.FLAT, value, java.util.Set.of(VERGLAS_TAG), "冰淞")));
    }

    private void cast(final Player owner, final String skillId, final int level, final Location at) {
        cast(owner, skillId, level, at, false);
    }

    /**
     * 2026-11-01 照 PoE2 Frost Wall：冰晶牆的一顆碎掉——照放它的那一下（霜壁的傷害表、輔助、加成）炸出 2 公尺的冰；
     * 用了電灌注的再炸一次 2.8 公尺的閃電（自己的閃電表、暴擊 9%）。晶刺（穿刺）照一般冰晶。
     */
    private void wallBurst(final Player owner, final Crystal crystal, final boolean early) {
        final SkillBuild build = crystal.wall;
        final SkillConfig config = service.config();
        // 2026-11-07 修正（檢查發現）：引星觸發的霜壁，冰晶爆炸也要帶著「觸發的」記號（MetaGems.TRIGGERED_SUFFIX）——
        //   這次之前換成爆炸的代號時記號掉了，觸發出來的牆炸開又暴擊、又給暴擊引星充能，觸發的牆再觸發牆
        final String triggered = build.profile().id().contains(MetaGems.TRIGGERED_SUFFIX) ? MetaGems.TRIGGERED_SUFFIX : "";
        SkillProfile cold = build.profile().derive(ColdSpells.WALL_BURST + triggered, java.util.Set.of(), java.util.Set.of());
        if (crystal.shards) {
            cold = cold.withLocal(List.of(tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.SupportRemakeKeys.CRYSTAL_IMPALE_MARK, 1, "晶刺"),
                    tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.SupportExtraKeys.FLAG_NO_EXTRACT, 1, "晶刺")));
        }
        if (early) {
            cold = cold.scaled(1 - WALL_EARLY_LESS, "剛放下就被推碎");
        }
        final double radius = ColdSpells.tableRadius(config, ColdSpells.WALL_BURST, build.level(), 2.0) * build.areaScale();
        service.executor().burst(owner, cold, crystal.at, radius, 0, ColdSpells.ICE_NOVA);
        if (crystal.volt) {
            SkillProfile volt = ColdSpells.ownTable(config, build.profile(), ColdSpells.WALL_VOLT, build.level());
            if (!triggered.isEmpty()) {
                volt = volt.derive(ColdSpells.WALL_VOLT + triggered, java.util.Set.of(), java.util.Set.of()); // 2026-11-07 修正：同上
            }
            volt = volt.withLocal(List.of(tw.exilecore.stats.Modifier.flat(StatKeys.SPELL_BASE_CRIT, -2, "閃電爆炸")));
            if (early) {
                volt = volt.scaled(1 - WALL_EARLY_LESS, "剛放下就被推碎");
            }
            final double voltRadius = ColdSpells.tableRadius(config, ColdSpells.WALL_VOLT, build.level(), 2.8) * build.areaScale();
            service.executor().burst(owner, volt, crystal.at, voltRadius, 0, "chain_thunder");
        }
    }

    /** impale：2026-11-01 照 PoE2 Crystalline Shards：這一次爆炸打中時穿刺（技能描述帶記號，{@link #onHit} 存穿刺；這一下不抽出穿刺）。 */
    private void cast(final Player owner, final String skillId, final int level, final Location at, final boolean impale) {
        final SkillDefinition definition = service.config().skill(skillId);
        if (definition == null) {
            return;
        }
        final PlayerState state = plugin.players().get(owner);
        SkillBuild build = SkillBuild.of(service.config(), definition, Math.max(1, level), state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(owner));
        if (impale) {
            build = build.withProfile(build.profile().withLocal(List.of(
                    tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.SupportRemakeKeys.CRYSTAL_IMPALE_MARK, 1, "晶刺"),
                    tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.SupportExtraKeys.FLAG_NO_EXTRACT, 1, "晶刺"))));
        }
        service.procCast(Origin.at(owner, at.clone(), SkillTargets.facing(owner)), build, null);
    }

    /** 2026-11-01 照 PoE2 Crystalline Shards：這一下是不是帶穿刺記號的冰晶爆炸。 */
    private static boolean impaling(final tw.exilecore.stats.SkillProfile skill) {
        for (final tw.exilecore.stats.Modifier modifier : skill.local()) {
            if (tw.exilecore.stats.SupportRemakeKeys.CRYSTAL_IMPALE_MARK.equals(modifier.stat()) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    void clear(final UUID owner) {
        final List<Crystal> mine = crystals.remove(owner);
        if (mine != null) {
            mine.forEach(crystal -> crystal.display.remove());
        }
    }

    /** 外掛關掉時全部拿掉。 */
    void clearAll() {
        crystals.values().forEach(list -> list.forEach(crystal -> crystal.display.remove()));
        crystals.clear();
    }
}
