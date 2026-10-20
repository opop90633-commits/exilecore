package tw.exilecore.skill;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 放在地上的東西（圖騰、陷阱、鐘）：一個盔甲座當身體（頭上戴個東西、名字寫著是誰的），不會被怪物打、不會被玩家拿走。
 * 沒有血量：只照持續時間存在（圖騰、鐘的名字後面寫著還剩幾秒）。
 * <ul>
 *   <li>圖騰：站著，每隔一段時間對範圍內最近的敵人放一次技能（從圖騰的位置、朝那隻），時間到了消失；在的時候可以給施放者增益。</li>
 *   <li>陷阱：丟到準心指的地方，過一下子才會觸發；敵人靠近就從陷阱的位置朝那隻放一次技能，然後消失。</li>
 *   <li>鐘：放在面前；施放者在鐘旁邊打中敵人，鐘就響一次，打周圍一圈（用放鐘那個技能的傷害），響夠次數或時間到了消失。</li>
 * </ul>
 * 傷害都算施放者的。施放者下線、換世界就收掉；每個技能同時最多幾個照設定（多的收最舊的）。
 */
final class SkillDeployables implements Listener {

    /** 鐘：排隊等著響的（打中事件裡不直接打，下一 tick 再打，免得傷害事件套傷害事件）。 */
    private record Ring(Deployed bell, long atTick) {
    }

    /** 一個放在地上的東西。 */
    static final class Deployed {
        final UUID owner;
        final SkillBuild build;
        final DeploySpec spec;
        final ArmorStand stand;
        /** 頭上的名字與剩幾秒（陷阱沒有）：盔甲座沒有碰撞箱，它自己的名字會畫在腳邊，所以另外放一個文字顯示在頭上。 */
        TextDisplay tag;
        final Location feet;
        final long placedTick;
        final long untilTick;
        /** 名字（技能名稱與施放者），後面接剩幾秒。 */
        final String label;
        /** 名字上現在寫的秒數（變了才改名字）。 */
        int shownSeconds = -1;
        long nextTick;
        int ringsLeft;
        boolean armed;
        // 2026-10：圖騰的生命（替身圖騰引來的怪會打它）、特殊圖騰（替身、焚鏈、引爆）、怨燼
        double maxLife;
        double life;
        boolean taunt;
        boolean bond;
        boolean detonate;
        double vengeful;
        long vengefulReady;
        final Map<UUID, Long> attacks = new HashMap<>();

        Deployed(final UUID owner, final SkillBuild build, final DeploySpec spec, final ArmorStand stand, final Location feet,
                 final long now, final long untilTick, final String label) {
            this.owner = owner;
            this.build = build;
            this.spec = spec;
            this.stand = stand;
            this.feet = feet;
            this.placedTick = now;
            this.untilTick = untilTick;
            this.label = label;
            this.nextTick = now + (spec.kind() == DeploySpec.Kind.TOTEM ? 10 : 0);
            this.ringsLeft = spec.rings();
            this.armed = spec.kind() != DeploySpec.Kind.TRAP;
        }

        String skillId() {
            return build.definition().id();
        }

        /** 英靈圖騰（2026-10）：英靈用的錘子技能與幾 tick 叫一次；null = 照圖騰自己的技能放。 */
        SkillBuild linked;
        long linkedTicks;
    }

    /** 圖騰攻擊間隔的下限（tick）：攻速再高也不會比這個快。 */
    static final int TOTEM_MIN_TICKS = 4;

    /**
     * 圖騰下一次攻擊隔幾 tick（2026-10-20，照 PoE）：技能的 interval ÷ 放圖騰的人的速度倍率——攻擊技能看攻擊速度、
     * 法術看施法速度（增加 × 更多，含這個技能的輔助寶石）；不含武器本身的攻速（interval 已經是圖騰自己的節奏）。
     * 英靈圖騰另外算（{@link #ancestorTicks}，2026-10-21）。
     */
    static long totemTicks(final double intervalSeconds, final double speedMultiplier) {
        final double speed = Math.max(0.1, speedMultiplier);
        return Math.max(TOTEM_MIN_TICKS, Math.round(intervalSeconds * 20 / speed));
    }

    /** 英靈（2026-10-21）：叫一次英靈要等那個錘子技能攻擊時間的幾倍。 */
    static final double ANCESTOR_ATTACK_TIMES = 1.5;

    /**
     * 英靈圖騰叫英靈的間隔（2026-10-21）：那個錘子技能的攻擊時間（武器基礎攻速 × 技能自己的攻擊速度，例如千鈞砸 45%）× 1.5，
     * 再除以放圖騰的人的攻速（{@link #totemSpeed}：英靈圖騰的輔助算，錘子技能自己的輔助不算）。越慢越重的技能叫得越慢。
     */
    static long ancestorTicks(final double weaponAps, final double skillAttackSpeed, final double totemSpeed) {
        final double aps = (weaponAps <= 0 ? 1.0 : weaponAps) * Math.max(0.05, skillAttackSpeed);
        return totemTicks(ANCESTOR_ATTACK_TIMES / aps, totemSpeed);
    }

    static long ancestorTicks(final SkillBuild totem, final SkillBuild linked, final tw.exilecore.stats.StatSheet sheet) {
        final double weaponAps = linked.profile().effective(sheet).flat(tw.exilecore.stats.StatKeys.WEAPON_APS, linked.profile().tags());
        return ancestorTicks(weaponAps, linked.definition().attackSpeed(), totemSpeed(totem, sheet, true));
    }

    private double totemSpeed(final Deployed item, final Player owner) {
        return totemSpeed(item.build, plugin.players().sheet(owner), item.linked != null);
    }

    /** 圖騰的速度倍率：攻擊（含英靈圖騰）看攻擊速度、法術看施法速度；用圖騰那個技能自己的輔助。 */
    static double totemSpeed(final SkillBuild totem, final tw.exilecore.stats.StatSheet sheet, final boolean linked) {
        final tw.exilecore.stats.StatSheet effective = totem.profile().effective(sheet);
        final Set<String> tags = totem.profile().tags();
        return effective.apply(linked || totem.isAttack() ? tw.exilecore.stats.StatKeys.ATTACK_SPEED : tw.exilecore.stats.StatKeys.CAST_SPEED,
                1.0, tags);
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillExecutor executor;
    private final SkillDamage damage;
    private final NamespacedKey key;
    private final List<Deployed> deployed = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    /** 被替身圖騰引過去的怪 → 那根圖騰（2026-10）。 */
    private final Map<UUID, Deployed> decoyed = new HashMap<>();
    /** Boss 被替身圖騰嘲諷（2026-10）：開始的 tick（嘲諷 BOSS_TAUNT_TICKS 後放掉）、免疫到哪個 tick。 */
    private final Map<UUID, Long> bossTauntStart = new HashMap<>();
    private final Map<UUID, Long> bossImmuneUntil = new HashMap<>();
    /** Boss 每次最多被嘲諷 4 秒，之後 8 秒不會再被嘲諷；Boss 打圖騰的傷害 ×3。 */
    static final long BOSS_TAUNT_TICKS = 80;
    static final long BOSS_IMMUNE_TICKS = 160;
    static final double BOSS_TOTEM_DAMAGE = 3.0;
    /** 圖騰的生命 = 施放者最大生命 × 這個（再乘 totem.life 的加成）。 */
    static final double TOTEM_LIFE_FACTOR = 0.5;

    SkillDeployables(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillExecutor executor,
                     final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.executor = executor;
        this.damage = damage;
        this.key = new NamespacedKey(plugin, "deployable");
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個玩家有沒有放著某個技能的東西（說明用）。 */
    int count(final UUID owner, final String skillId) {
        int count = 0;
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.skillId().equals(skillId)) {
                count++;
            }
        }
        return count;
    }

    /** 圖騰給施放者的增益（屬性表的來源）。 */
    List<Modifier> modifiers(final Player player) {
        List<Modifier> list = null;
        final java.util.Set<String> counted = new java.util.HashSet<>();
        for (final Deployed item : deployed) {
            if (item.owner.equals(player.getUniqueId()) && item.spec.kind() == DeploySpec.Kind.TOTEM && !item.spec.buff().isEmpty()
                    && counted.add(item.skillId())) { // 同一個技能放兩根也只算一次
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.addAll(item.spec.buff());
            }
        }
        return list == null ? List.of() : list;
    }

    /** 放下去（圖騰放面前 1.5 格、鐘放面前 2 格、陷阱丟到準心指的地方）。 */
    void place(final Player player, final SkillBuild build) {
        place(player, build, null, 0);
    }

    /** 英靈圖騰（2026-10）：圖騰叫出的英靈用 linked 這個技能，每 linkedTicks 一次。 */
    void place(final Player player, final SkillBuild build, final SkillBuild linked, final long linkedTicks) {
        final DeploySpec spec = build.deploy();
        final Origin origin = Origin.of(player);
        final Location at;
        switch (spec.kind()) {
            case TRAP -> at = targets.aimPoint(origin, 12);
            case BELL -> at = ground(player.getLocation().add(origin.facing().multiply(2.0)));
            default -> at = ground(player.getLocation().add(origin.facing().multiply(1.5)));
        }
        if (at == null) {
            return;
        }
        final long now = now();
        // 同一個技能同時最多幾個：多的收最舊的
        final int limit = spec.kind() == DeploySpec.Kind.TOTEM ? Math.max(1, build.totems()) : Math.max(1, spec.max());
        final List<Deployed> mine = new ArrayList<>();
        for (final Deployed item : deployed) {
            if (item.owner.equals(player.getUniqueId()) && item.skillId().equals(build.definition().id())) {
                mine.add(item);
            }
        }
        while (mine.size() >= limit) {
            final Deployed oldest = mine.removeFirst();
            remove(oldest, false);
        }
        at.setDirection(origin.facing());
        final String ownerName = plugin.players().get(player).character() == null ? player.getName()
                : plugin.players().get(player).character().name();
        final String label = "<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name())
                + " <gray>（" + tw.exilecore.item.ItemRenderer.escape(ownerName) + "）";
        final ArmorStand stand = spawnStand(player, spec, at, label);
        if (stand == null) {
            return;
        }
        final boolean totem = spec.kind() == DeploySpec.Kind.TOTEM;
        final Deployed item = new Deployed(player.getUniqueId(), totem ? totemHits(build) : build, spec, stand, at, now,
                now + build.durationTicks(spec.duration()), label);
        item.linked = totem ? ancestorHits(linked) : linked;
        item.linkedTicks = Math.max(4, linkedTicks);
        if (spec.kind() == DeploySpec.Kind.TOTEM) {
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(player));
            final Set<String> tags = build.profile().tags();
            item.taunt = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_TOTEM_TAUNT);
            item.bond = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_SEARING_BOND);
            item.detonate = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_TOTEM_DETONATE);
            item.vengeful = Math.max(0, effective.flat(tw.exilecore.stats.StatKeys.TOTEM_VENGEFUL, tags));
            item.maxLife = Math.max(1, plugin.players().get(player).maxLife() * TOTEM_LIFE_FACTOR
                    * effective.apply(tw.exilecore.stats.StatKeys.TOTEM_LIFE, 1.0, tags));
            item.life = item.maxLife;
        }
        if (spec.kind() != DeploySpec.Kind.TRAP) {
            item.tag = spawnTag(player, at);
        }
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            visibility(viewer, item); // 2026-10：關掉別人技能特效的人看不到別人的圖騰、陷阱
        }
        showTimeLeft(item, now);
        deployed.add(item);
        visuals.deploy(build.definition().visual(), spec.kind(), at);
        if (spec.kind() == DeploySpec.Kind.TOTEM && !spec.buff().isEmpty()) {
            plugin.players().markDirty(player);
        }
        if (item.detonate) {
            // 異常・焚鏈圖騰：放到上限就全部引爆
            final List<Deployed> all = new ArrayList<>();
            for (final Deployed other : deployed) {
                if (other.owner.equals(player.getUniqueId()) && other.skillId().equals(build.definition().id())) {
                    all.add(other);
                }
            }
            if (all.size() >= limit) {
                detonate(all, player);
            }
        }
    }

    /**
     * 2026-10-18：圖騰（與英靈）放的技能標上 {@link tw.exilecore.stats.Tags#TOTEM_HIT}：照 PoE，圖騰打中的擊中回復生命、偷取不回給玩家。
     * 引爆、焚鏈都從 item.build 的技能描述衍生，一起帶著。
     */
    /**
     * 英靈（2026-10-21）：英靈用的錘子技能算「圖騰使用的技能」（照 PoE2）：加上圖騰標籤，吃得到全身的「圖騰傷害」；
     * 裝在英靈圖騰上的輔助照舊不套（只照那個技能與它自己的輔助）。也標上 {@link tw.exilecore.stats.Tags#TOTEM_HIT}。
     */
    static SkillBuild ancestorHits(final SkillBuild build) {
        if (build == null) {
            return null;
        }
        final Set<String> tags = build.profile().tags();
        if (tags.contains(tw.exilecore.stats.Tags.TOTEM) && tags.contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
            return build;
        }
        return build.withProfile(build.profile().derive(build.profile().id(),
                Set.of(tw.exilecore.stats.Tags.TOTEM, tw.exilecore.stats.Tags.TOTEM_HIT), Set.of()));
    }

    private static SkillBuild totemHits(final SkillBuild build) {
        if (build == null || build.profile().tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
            return build;
        }
        return build.withProfile(build.profile().derive(build.profile().id(), Set.of(tw.exilecore.stats.Tags.TOTEM_HIT), Set.of()));
    }

    /** 引爆（2026-10）：每根圖騰在原地炸一圈；炸幾根，每一下更多 30%（第一根不加）。 */
    private void detonate(final List<Deployed> totems, final Player owner) {
        final int count = totems.size();
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
        try {
            for (final Deployed totem : totems) {
                final double radius = 2.5 * totem.build.areaScale();
                final SkillProfile profile = totem.build.profile().derive(totem.skillId() + ".detonate", Set.of(tw.exilecore.stats.Tags.AREA), Set.of())
                        .scaled(DETONATE_MULTIPLIER * (1 + 0.3 * (count - 1)), "引爆");
                for (final LivingEntity target : targets.around(totem.feet, radius, owner)) {
                    damage.hit(owner, target, profile, null);
                }
                final World world = totem.feet.getWorld();
                Fx.spawn(world, org.bukkit.Particle.EXPLOSION, totem.feet.clone().add(0, 1, 0), 1);
                Fx.spawn(world, org.bukkit.Particle.FLAME, totem.feet.clone().add(0, 1, 0), 40, radius * 0.4, 0.6, radius * 0.4, 0.05);
                world.playSound(totem.feet, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                remove(totem, false);
            }
        } finally {
            Fx.restore(previousFx);
        }
    }

    /** 引爆的一下 = 火鏈一跳（0.5 秒）的幾倍。 */
    static final double DETONATE_MULTIPLIER = 6.0;

    /** 這個人看不看得到這個圖騰（關掉「別人的技能特效」的人看不到別人的）。 */
    private void visibility(final Player viewer, final Deployed item) {
        final boolean hide = !viewer.getUniqueId().equals(item.owner) && Fx.hides(viewer);
        for (final org.bukkit.entity.Entity entity : new org.bukkit.entity.Entity[] {item.stand, item.tag}) {
            if (entity == null) {
                continue;
            }
            if (hide) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    /** 設定改了：這個人看到的圖騰、陷阱跟上。 */
    void refresh(final Player viewer) {
        for (final Deployed item : deployed) {
            visibility(viewer, item);
        }
    }

    private ArmorStand spawnStand(final Player player, final DeploySpec spec, final Location at, final String label) {
        final World world = at.getWorld();
        final Material head = Material.matchMaterial(spec.head());
        final boolean small = spec.kind() == DeploySpec.Kind.TRAP;
        try {
            return world.spawn(at, ArmorStand.class, stand -> {
                stand.setInvulnerable(true);
                stand.setGravity(false);
                stand.setBasePlate(false);
                stand.setArms(!small);
                stand.setSmall(small);
                // 圖騰、鐘、陷阱都沒有碰撞箱（2026-10）：準心、箭矢、法術直接穿過去打到後面的怪，
                // 也點不到、打不壞（以前圖騰擋在面前，左鍵出招時會打到圖騰）
                stand.setMarker(true);
                stand.setCanPickupItems(false);
                stand.setPersistent(false);
                stand.setSilent(true);
                stand.setCollidable(false);
                stand.setInvisible(spec.kind() == DeploySpec.Kind.TRAP && head == null);
                for (final EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
                        EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
                    stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                    stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
                }
                if (head != null) {
                    stand.getEquipment().setHelmet(new ItemStack(head));
                }
                stand.customName(plugin.text().parse(label));
                stand.setCustomNameVisible(false); // 名字在頭上的文字顯示（{@link #spawnTag}）
                stand.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            });
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("放不出{}（{}）", spec.kind().displayName(), e.toString());
            return null;
        }
    }

    /** 頭上的名字（跟原版的名牌一樣：面向玩家、半透明的底）。 */
    private TextDisplay spawnTag(final Player player, final Location feet) {
        try {
            return feet.getWorld().spawn(feet.clone().add(0, TAG_HEIGHT, 0), TextDisplay.class, created -> {
                created.setPersistent(false);
                created.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                created.setDefaultBackground(true);
                created.setShadowed(false);
                created.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            });
        } catch (final RuntimeException e) {
            return null; // 沒有名字照樣能用
        }
    }

    /** 名字離地多高（盔甲座的頭上）。 */
    private static final double TAG_HEIGHT = 2.25;

    /** 這個點往下找地面（最多 4 格）。 */
    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5, point.getYaw(), 0);
            }
        }
        return null;
    }

    /** 每 tick：圖騰施放、陷阱觸發、鐘響、時間到了收掉。 */
    void tick() {
        final long now = now();
        if (!rings.isEmpty()) {
            final List<Ring> due = new ArrayList<>();
            rings.removeIf(ring -> {
                if (now >= ring.atTick()) {
                    due.add(ring);
                    return true;
                }
                return false;
            });
            for (final Ring ring : due) {
                ring(ring.bell(), now);
            }
        }
        if (deployed.isEmpty()) {
            return;
        }
        for (final Deployed item : new ArrayList<>(deployed)) { // 照快照跑：觸發、收掉會改名單
            if (!deployed.contains(item)) {
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(item.owner);
            if (now >= item.untilTick || !item.stand.isValid() || owner == null || !owner.getWorld().equals(item.feet.getWorld())
                    || (item.spec.kind() == DeploySpec.Kind.BELL && item.ringsLeft <= 0)) {
                remove(item, true);
                continue;
            }
            showTimeLeft(item, now);
            if (item.spec.kind() == DeploySpec.Kind.TOTEM && item.life <= 0) {
                remove(item, false); // 被打倒了
                continue;
            }
            switch (item.spec.kind()) {
                case TOTEM -> tickTotem(item, owner, now);
                case TRAP -> tickTrap(item, owner, now);
                case BELL -> {
                    if (now % 10 == 0) {
                        visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.range());
                    }
                }
            }
        }
        if (now % 5 == 0) {
            bonds(now);
        }
    }

    /** 焚鏈圖騰（2026-10）：你與你的焚鏈圖騰之間、圖騰與圖騰之間連起火鏈；每 0.5 秒燒碰到火鏈或站在兩端附近的敵人。 */
    private void bonds(final long now) {
        final Map<UUID, List<Deployed>> byOwner = new HashMap<>();
        for (final Deployed item : deployed) {
            if (item.bond && deployed.contains(item)) {
                byOwner.computeIfAbsent(item.owner, key -> new ArrayList<>()).add(item);
            }
        }
        for (final Map.Entry<UUID, List<Deployed>> entry : byOwner.entrySet()) {
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            final List<Deployed> totems = entry.getValue();
            final List<Location[]> segments = new ArrayList<>();
            final Location self = owner.getLocation().add(0, 1, 0);
            for (int i = 0; i < totems.size(); i++) {
                final Location at = totems.get(i).feet.clone().add(0, 1, 0);
                if (self.getWorld().equals(at.getWorld()) && self.distanceSquared(at) <= BOND_MAX * BOND_MAX) {
                    segments.add(new Location[] {self, at});
                }
                if (i + 1 < totems.size()) {
                    segments.add(new Location[] {at, totems.get(i + 1).feet.clone().add(0, 1, 0)});
                }
            }
            final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
            try {
                for (final Location[] segment : segments) {
                    final Vector step = segment[1].toVector().subtract(segment[0].toVector());
                    final double length = step.length();
                    final int points = (int) Math.max(2, length / 0.7);
                    for (int i = 0; i <= points; i++) {
                        final Location at = segment[0].clone().add(step.clone().multiply(i / (double) points));
                        Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at, 1, 0.05, 0.05, 0.05, 0.0);
                    }
                }
                if (now % 10 != 0) {
                    continue;
                }
                final Deployed first = totems.getFirst();
                final SkillProfile profile = first.build.profile().derive(first.skillId() + ".bond", Set.of(tw.exilecore.stats.Tags.AREA), Set.of());
                final Set<UUID> burned = new java.util.HashSet<>();
                for (final Location[] segment : segments) {
                    final Location mid = segment[0].clone().add(segment[1]).multiply(0.5);
                    final double half = segment[0].distance(segment[1]) / 2;
                    for (final LivingEntity target : targets.around(mid, half + BOND_END, owner)) {
                        final Location body = target.getLocation().add(0, target.getHeight() / 2, 0);
                        final boolean near = distanceToSegment(body, segment[0], segment[1]) <= BOND_WIDTH
                                || body.distance(segment[0]) <= BOND_END || body.distance(segment[1]) <= BOND_END;
                        if (near && burned.add(target.getUniqueId())) {
                            damage.hit(owner, target, profile, null);
                        }
                    }
                }
            } finally {
                Fx.restore(previousFx);
            }
        }
    }

    /** 火鏈：你離圖騰超過這麼遠就連不上。 */
    static final double BOND_MAX = 24;
    /** 火鏈：離鏈子多近算碰到。 */
    static final double BOND_WIDTH = 1.0;
    /** 火鏈：兩端（你、圖騰）多近也會燒。 */
    static final double BOND_END = 1.8;

    private static double distanceToSegment(final Location point, final Location a, final Location b) {
        final Vector ab = b.toVector().subtract(a.toVector());
        final Vector ap = point.toVector().subtract(a.toVector());
        final double lengthSquared = ab.lengthSquared();
        final double t = lengthSquared <= 1e-9 ? 0 : Math.max(0, Math.min(1, ap.dot(ab) / lengthSquared));
        return point.toVector().distance(a.toVector().add(ab.multiply(t)));
    }

    /** 花魔力之前：放不放得下去（圖騰、鐘要面前有地）。 */
    String precheck(final Player player, final SkillBuild build) {
        final DeploySpec spec = build.deploy();
        if (spec == null || spec.kind() == DeploySpec.Kind.TRAP) {
            return null;
        }
        final Vector facing = SkillTargets.facing(player);
        final Location at = ground(player.getLocation().add(facing.multiply(spec.kind() == DeploySpec.Kind.BELL ? 2.0 : 1.5)));
        return at == null ? "面前沒有地方放" + spec.kind().displayName() : null;
    }

    /** 圖騰、鐘的名字後面寫還剩幾秒（秒數變了才改，一秒最多改一次）；陷阱不顯示名字。 */
    private void showTimeLeft(final Deployed item, final long now) {
        if (item.spec.kind() == DeploySpec.Kind.TRAP) {
            return;
        }
        final int seconds = (int) Math.max(0, (item.untilTick - now + 19) / 20);
        if (seconds != item.shownSeconds) {
            item.shownSeconds = seconds;
            final String life = item.spec.kind() == DeploySpec.Kind.TOTEM && (item.taunt || item.vengeful > 0 || item.life < item.maxLife)
                    ? " <red>❤ " + Math.max(0, Math.round(item.life)) : "";
            final Component text = plugin.text().parse(item.label + " <white>" + seconds + "</white><gray> 秒" + life);
            item.stand.customName(text);
            if (item.tag != null && item.tag.isValid()) {
                item.tag.text(text);
            }
        }
    }

    private void tickTotem(final Deployed item, final Player owner, final long now) {
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            tickTotemInner(item, owner, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickTotemInner(final Deployed item, final Player owner, final long now) {
        if (now % 10 == 0) {
            visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.range());
        }
        if (item.taunt) {
            taunt(item, owner, now);
            return;
        }
        if (item.bond) {
            return; // 焚鏈圖騰不施法（火鏈在 bonds 裡）
        }
        if (now < item.nextTick) {
            return;
        }
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        final Origin look = Origin.at(owner, item.feet, item.feet.getDirection());
        for (final LivingEntity candidate : targets.around(item.feet, item.spec.range(), owner)) {
            final double distance = candidate.getLocation().distanceSquared(item.feet);
            if (distance < best && look.canSee(candidate)) {
                best = distance;
                target = candidate;
            }
        }
        if (target == null) {
            item.nextTick = now + 5; // 沒目標：等一下再看
            return;
        }
        if (item.linked != null) {
            // 英靈圖騰：在敵人旁邊（往圖騰那邊 1.5 格）叫出英靈，用連著的錘子技能打一下
            item.nextTick = now + ancestorTicks(item.build, item.linked, plugin.players().sheet(owner)); // 2026-10-21：錘子技能攻擊時間 ×1.5 ÷ 攻速
            final org.bukkit.util.Vector back = item.feet.toVector().subtract(target.getLocation().toVector()).setY(0);
            final Location spot = target.getLocation().clone();
            if (back.lengthSquared() > 1e-4) {
                spot.add(back.normalize().multiply(Math.min(1.5, Math.sqrt(best))));
            }
            visuals.deployCast(item.build.definition().visual(), spot);
            tw.exilecore.skill.Fx.spawn(spot.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, spot.clone().add(0, 1, 0), 12, 0.25, 0.5, 0.25, 0.02);
            executor.executeFrom(Origin.towards(owner, spot, target), item.linked, target);
            return;
        }
        item.nextTick = now + totemTicks(item.spec.interval(), totemSpeed(item, owner)); // 2026-10-20：吃攻速／施法速度
        final Origin origin = Origin.towards(owner, item.feet, target);
        visuals.deployCast(item.build.definition().visual(), item.feet);
        executor.executeFrom(origin, item.build, target);
    }

    /** 替身圖騰（2026-10）：範圍內的怪走過來打圖騰，不打玩家；Boss 只被引走走位與普攻，每次 4 秒、之後 8 秒免疫。 */
    private void taunt(final Deployed item, final Player owner, final long now) {
        if (now % 5 != 0) {
            return;
        }
        for (final LivingEntity candidate : targets.around(item.feet, item.spec.range(), owner)) {
            if (!(candidate instanceof org.bukkit.entity.Mob mob)) {
                continue;
            }
            final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(candidate);
            if (data.isEmpty()) {
                continue;
            }
            final boolean boss = data.get().boss();
            if (boss) {
                // Boss（2026-10）：只引走走位與普攻（招式照樣挑玩家放），每次最多 4 秒，之後 8 秒免疫
                if (now < bossImmuneUntil.getOrDefault(mob.getUniqueId(), 0L)) {
                    continue;
                }
                long start = bossTauntStart.computeIfAbsent(mob.getUniqueId(), key -> now);
                if (now - start > BOSS_TAUNT_TICKS + 20) {
                    // 2026-10-18：開始時間過舊（上次沒嘲諷滿就離開，殘留的紀錄）：重新計時，不要直接判成免疫
                    start = now;
                    bossTauntStart.put(mob.getUniqueId(), now);
                }
                if (now - start >= BOSS_TAUNT_TICKS) {
                    bossTauntStart.remove(mob.getUniqueId());
                    if (bossImmuneUntil.size() > 256) {
                        bossImmuneUntil.values().removeIf(until -> until < now); // 2026-10-17：過期的清掉（以前只增不減）
                    }
                    bossImmuneUntil.put(mob.getUniqueId(), now + BOSS_IMMUNE_TICKS);
                    decoyed.remove(mob.getUniqueId());
                    continue;
                }
            }
            decoyed.put(mob.getUniqueId(), item);
            if (mob.getTarget() instanceof Player) {
                mob.setTarget(null);
            }
            final double distance = mob.getLocation().distance(item.feet);
            if (distance > 1.9) {
                if (now % 10 == 0) {
                    mob.getPathfinder().moveTo(item.feet, 1.1);
                }
                continue;
            }
            final long last = item.attacks.getOrDefault(mob.getUniqueId(), 0L);
            if (now - last < 20) {
                continue;
            }
            item.attacks.put(mob.getUniqueId(), now);
            mob.lookAt(item.feet.clone().add(0, 1, 0));
            mob.swingMainHand();
            final double amount = tw.exilecore.stats.DamageCalculator.expectedOutgoing(plugin.monsters().sheet(candidate, data.get()),
                    SkillProfile.monsterAttack()).total() * (boss ? BOSS_TOTEM_DAMAGE : 1.0);
            hurtTotem(item, owner, mob, amount, now);
            if (!deployed.contains(item)) {
                return;
            }
        }
    }

    /** 圖騰被打：扣生命，有怨燼就反擊，沒血了收掉。 */
    private void hurtTotem(final Deployed item, final Player owner, final LivingEntity attacker, final double amount, final long now) {
        item.life -= Math.max(0, amount);
        item.shownSeconds = -1; // 名字上的生命跟著改
        Fx.spawn(item.feet.getWorld(), org.bukkit.Particle.DAMAGE_INDICATOR, item.feet.clone().add(0, 1.2, 0), 2, 0.2, 0.2, 0.2, 0.0);
        if (item.vengeful > 0) {
            vengeful(item, owner, attacker, now);
        }
        if (item.life <= 0) {
            item.feet.getWorld().playSound(item.feet, org.bukkit.Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f);
            remove(item, false);
        }
    }

    /** 怨燼（2026-10）：往天上射一團火，落在打它的敵人身上炸開（冷卻 1 秒）；傷害 = 圖騰最大生命的 x%（基礎值，吃你的加成與輔助）。 */
    private void vengeful(final Deployed item, final Player owner, final LivingEntity attacker, final long now) {
        if (now < item.vengefulReady || attacker == null || !attacker.isValid()) {
            return;
        }
        item.vengefulReady = now + 20;
        final double base = item.maxLife * item.vengeful / 100.0;
        final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket();
        final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket();
        min.set(tw.exilecore.stats.DamageType.FIRE, base * 0.85);
        max.set(tw.exilecore.stats.DamageType.FIRE, base * 1.15);
        final SkillProfile profile = new SkillProfile(item.skillId() + ".vengeful",
                Set.of(tw.exilecore.stats.Tags.SPELL, tw.exilecore.stats.Tags.FIRE, tw.exilecore.stats.Tags.AREA,
                        tw.exilecore.stats.Tags.TOTEM, tw.exilecore.stats.Tags.TOTEM_HIT, tw.exilecore.stats.Tags.PROJECTILE),
                0.0, min, max, java.util.Map.of(), item.build.profile().local());
        final double radius = 1.8 * item.build.areaScale();
        final Location from = item.feet.clone().add(0, 2, 0);
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
        try {
            Fx.spawn(from.getWorld(), org.bukkit.Particle.FLAME, from, 12, 0.15, 0.3, 0.15, 0.08);
        } finally {
            Fx.restore(previousFx);
        }
        from.getWorld().playSound(from, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.7f, 0.8f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final Player caster = plugin.getServer().getPlayer(item.owner);
            if (caster == null || !attacker.getWorld().equals(item.feet.getWorld())) {
                return;
            }
            final Location at = attacker.isValid() ? attacker.getLocation() : item.feet;
            final java.util.UUID previous = Fx.source(caster.getUniqueId());
            try {
                Fx.spawn(at.getWorld(), org.bukkit.Particle.LAVA, at.clone().add(0, 0.5, 0), 8, radius * 0.3, 0.2, radius * 0.3, 0.0);
                Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at.clone().add(0, 0.5, 0), 30, radius * 0.4, 0.3, radius * 0.4, 0.04);
            } finally {
                Fx.restore(previous);
            }
            at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
            for (final LivingEntity target : targets.around(at, radius, caster)) {
                damage.hit(caster, target, profile, null);
            }
        }, 10L);
    }

    /** 被替身圖騰引過去的怪不能改打玩家。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecoyTarget(final org.bukkit.event.entity.EntityTargetEvent event) {
        if (decoyed.isEmpty()) {
            return;
        }
        final Deployed decoy = decoyed.get(event.getEntity().getUniqueId());
        if (decoy == null) {
            return;
        }
        if (!deployed.contains(decoy) || !event.getEntity().getWorld().equals(decoy.feet.getWorld())
                || event.getEntity().getLocation().distanceSquared(decoy.feet) > (decoy.spec.range() + 6) * (decoy.spec.range() + 6)) {
            decoyed.remove(event.getEntity().getUniqueId());
            bossTauntStart.remove(event.getEntity().getUniqueId()); // 2026-10-18：離開範圍：Boss 的嘲諷計時一起清掉（不然下次直接判成免疫）
            return;
        }
        if (event.getTarget() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** 2026-10-18：怪死掉：替身圖騰的紀錄清掉（不然 map 一直累積）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDecoyDeath(final org.bukkit.event.entity.EntityDeathEvent event) {
        final UUID uuid = event.getEntity().getUniqueId();
        decoyed.remove(uuid);
        bossTauntStart.remove(uuid);
        bossImmuneUntil.remove(uuid);
    }

    /** 你在有怨燼的圖騰旁邊被怪打中：圖騰也反擊（2026-10；圖騰平常不會被打，這樣一般圖騰也用得到）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerHit(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit()
                || event.result().blocked()) {
            return;
        }
        final long now = now();
        for (final Deployed item : new ArrayList<>(deployed)) {
            if (item.vengeful <= 0 || !item.owner.equals(player.getUniqueId()) || !player.getWorld().equals(item.feet.getWorld())
                    || player.getLocation().distanceSquared(item.feet) > VENGEFUL_OWNER_RANGE * VENGEFUL_OWNER_RANGE) {
                continue;
            }
            vengeful(item, player, event.attacker(), now);
        }
    }

    /** 怨燼：你離圖騰多近被打，圖騰也會反擊。 */
    static final double VENGEFUL_OWNER_RANGE = 10;

    private void tickTrap(final Deployed item, final Player owner, final long now) {
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            tickTrapInner(item, owner, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickTrapInner(final Deployed item, final Player owner, final long now) {
        if (!item.armed) {
            if (now - item.placedTick >= Math.round(item.spec.arm() * 20)) {
                item.armed = true;
                visuals.deployArmed(item.feet);
            }
            return;
        }
        if (now % 2 != 0) {
            return;
        }
        if (now % 10 == 0) {
            visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.trigger());
        }
        final List<LivingEntity> near = targets.around(item.feet, item.spec.trigger(), owner);
        if (near.isEmpty()) {
            return;
        }
        final LivingEntity trigger = near.getFirst();
        visuals.deployTrigger(item.build.definition().visual(), item.feet);
        if (item.spec.root() > 0) {
            trigger.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(item.spec.root() * 20), 9, false, true, true));
            visuals.root(trigger);
        }
        executor.executeFrom(Origin.towards(owner, item.feet, trigger), item.build, trigger);
        remove(item, false);
    }

    /** 鐘響：打鐘周圍一圈（用放鐘那個技能的傷害；代號加 .ring，免得自己的響聲又觸發自己）。 */
    private void ring(final Deployed bell, final long now) {
        if (!deployed.contains(bell) || bell.ringsLeft <= 0) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(bell.owner);
        if (owner == null) {
            return;
        }
        bell.ringsLeft--;
        bell.nextTick = now + Math.max(1, Math.round(bell.spec.interval() * 20));
        final SkillProfile profile = bell.build.profile().derive(bell.skillId() + ".ring", java.util.Set.of(tw.exilecore.stats.Tags.AREA), java.util.Set.of());
        final double radius = bell.build.radius();
        for (final LivingEntity target : targets.around(bell.feet, radius, owner)) {
            damage.hit(owner, target, profile, null);
        }
        visuals.bellRing(bell.build.definition().visual(), bell.feet, radius, bell.ringsLeft);
        if (bell.ringsLeft <= 0) {
            remove(bell, true);
        }
    }

    /** 施放者在鐘旁邊打中敵人：鐘排隊響一次（下一 tick）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.result().blocked()
                || event.skill().id().endsWith(".ring") || event.skill().id().endsWith(".zone") || !event.skill().isAttack()
                || !event.skill().tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            return; // 只有近戰攻擊打中（不是地面效果一波一波的、不是遠程）
        }
        final long now = now();
        for (final Deployed bell : deployed) {
            if (bell.spec.kind() != DeploySpec.Kind.BELL || !bell.owner.equals(player.getUniqueId()) || bell.ringsLeft <= 0
                    || now < bell.nextTick || !event.defender().getWorld().equals(bell.feet.getWorld())
                    || event.defender().getLocation().distanceSquared(bell.feet) > bell.spec.range() * bell.spec.range()) {
                continue;
            }
            boolean queued = false;
            for (final Ring ring : rings) {
                if (ring.bell() == bell) {
                    queued = true;
                    break;
                }
            }
            if (!queued) {
                bell.nextTick = now + Math.max(1, Math.round(bell.spec.interval() * 20));
                rings.add(new Ring(bell, now + 1));
            }
        }
    }

    /** 玩家不能拿走圖騰頭上的東西。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onManipulate(final PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    /** 圖騰、鐘、陷阱不會受傷、不會被打掉（創造模式的玩家、爆炸、火都一樣）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof ArmorStand stand && stand.getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(final org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity() instanceof ArmorStand stand && stand.getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    private void remove(final Deployed item, final boolean expired) {
        deployed.remove(item);
        decoyed.entrySet().removeIf(entry -> {
            if (entry.getValue() != item) {
                return false;
            }
            bossTauntStart.remove(entry.getKey()); // 圖騰沒了：Boss 的嘲諷計時重來（免疫照算）
            return true;
        });
        rings.removeIf(ring -> ring.bell() == item);
        if (item.stand.isValid()) {
            item.stand.remove();
        }
        if (item.tag != null && item.tag.isValid()) {
            item.tag.remove();
        }
        visuals.deployGone(item.build.definition().visual(), item.spec.kind(), item.feet, expired);
        if (item.spec.kind() == DeploySpec.Kind.TOTEM && !item.spec.buff().isEmpty()) {
            final Player owner = plugin.getServer().getPlayer(item.owner);
            if (owner != null) {
                plugin.players().markDirty(owner);
            }
        }
    }

    /** 某個玩家的全部收掉（登出、換角色）。 */
    void forget(final UUID owner) {
        for (final Deployed item : new ArrayList<>(deployed)) {
            if (item.owner.equals(owner)) {
                remove(item, false);
            }
        }
    }

    /** 全部收掉（插件停用）；也把上次沒收乾淨的盔甲座（當機留下的）清掉。 */
    void clear() {
        for (final Deployed item : new ArrayList<>(deployed)) {
            remove(item, false);
        }
        sweep();
    }

    /** 清掉世界裡帶著我們標記、但不在名單上的盔甲座（上次當機留下的）。 */
    void sweep() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(key) && !tracked(stand)) {
                    stand.remove();
                }
            }
            for (final TextDisplay tag : world.getEntitiesByClass(TextDisplay.class)) {
                if (tag.getPersistentDataContainer().has(key) && !tracked(tag)) {
                    tag.remove();
                }
            }
        }
    }

    private boolean tracked(final Entity entity) {
        for (final Deployed item : deployed) {
            if (item.stand == entity || item.tag == entity) {
                return true;
            }
        }
        return false;
    }

    /** 這個盔甲座是不是我們放的東西（其他模組判斷用）。 */
    boolean isDeployable(final Entity entity) {
        return entity instanceof ArmorStand && entity.getPersistentDataContainer().has(key);
    }

    int size() {
        return deployed.size();
    }

    /** 給說明：面向的方向（放圖騰、鐘的位置）。 */
    static Vector facing(final Player player) {
        return SkillTargets.facing(player);
    }
}
