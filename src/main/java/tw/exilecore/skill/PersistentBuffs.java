package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26：照 PoE2 只給自己的常駐增益（精魂技能），效果寫在程式裡：
 * <ul>
 *   <li><b>護時</b>（Time of Need）：每隔 10→8.1 秒（逐級；冷卻回復加快）一次祝福：回復固定生命（17→628→2763），解除身上的詛咒與元素異常（2026-11-01 照 PoE2 補詛咒）。</li>
 *   <li><b>幻步</b>（Ghost Dance）：每隔 12→10.1 秒（逐級；冷卻回復加快）獲得一層幽魂護罩（最多 3 層）；被擊中時失去一層，
 *       最近（4 秒內）失去過的話每秒再生閃避值 2% 的能量護盾。</li>
 *   <li><b>木甲</b>（Barkskin）：被敵人擊中、能量護盾被打掉時，獲得失去的能量護盾 43%→62%（逐級）的護甲 6 秒（可以疊，
 *       總共不超過身上原本的護甲）。</li>
 *   <li><b>不熄怒</b>（Eternal Rage）：每秒回復 0.25→5→10 點怒氣（屬性 {@link SkillResources#RAGE_REGEN}，寫在光環的 effects）。</li>
 *   <li><b>拾鱗</b>（Scavenged Plating，2026-11-01）：讓敵人完全破甲時照牠的稀有度獲得甲片（普通 1、魔法 2、稀有 5、傳奇 10 層，最多 10 層，
 *       每批 8 秒，品質每 1% +0.1 秒）；每層更多 4% 護甲、獲得一份荊棘物理傷害（逐級 2–2 → 43–64 → 124–186）。這次之前是常駐的護甲、破甲增加。</li>
 *   <li><b>風之舞步</b>（Wind Dancer，2026-11-01）：每 1.5→1.12→0.82 秒（逐級）一階，最多 3 階，每階更多 10% 閃避；
 *       帶著階數被敵人擊中時用掉全部階數觸發疾風（gale_force：身邊 1.6 格的攻擊並擊退；每階範圍更多 100%、傷害更多 50%、擊退更多 25%，冷卻 0.25 秒）。
 *       這次之前是常駐的閃避更多 30%、沒有疾風。</li>
 * </ul>
 * 逐級的間隔寫在 skills.yml 那幾個技能的 level_modifiers（blessing.interval、shroud.interval）；
 * 幅度寫在光環的 effects（blessing.life、shroud.es_regen、barkskin.armour，吃光環效果）。
 */
final class PersistentBuffs implements Listener {

    static final String TIME_OF_NEED = "guard_moment";
    static final String GHOST_DANCE = "spectral_step";
    static final String BARKSKIN = "wood_armor";
    static final String PLATING = "gathered_scales";
    static final String WIND_DANCER = "breeze_dance_step";
    static final String GALE_FORCE = "gale_force";

    /** 拾鱗：光環 effects 的每層數值（FLAT，吃光環效果）：更多幾 % 護甲、荊棘物理傷害下限、上限。 */
    static final String PLATING_ARMOUR = "plating.armour";
    static final String PLATING_THORNS_MIN = "plating.thorns_min";
    static final String PLATING_THORNS_MAX = "plating.thorns_max";
    /** 拾鱗：品質給的甲片持續時間（秒，gem_quality.yml）。 */
    static final String PLATING_DURATION = "plating.duration";
    /** 拾鱗：最多幾層、一批甲片幾 tick（8 秒）。 */
    static final int PLATING_MAX = 10;
    static final long PLATING_TICKS = 160;
    /** 風之舞步：每階間隔（秒，level_modifiers）、每階更多幾 % 閃避（光環 effects）。 */
    static final String WIND_INTERVAL = "wind_dancer.interval";
    static final String WIND_EVASION = "wind_dancer.evasion";
    /** 風之舞步：品質給的疾風爆炸半徑（公尺，gem_quality.yml）。 */
    static final String GALE_RADIUS = "gale.radius";
    static final int WIND_MAX = 3;

    static final String BLESSING_INTERVAL = "blessing.interval";
    static final String BLESSING_LIFE = "blessing.life";
    static final String SHROUD_INTERVAL = "shroud.interval";
    static final String SHROUD_ES_REGEN = "shroud.es_regen";
    static final String BARKSKIN_ARMOUR = "wood_armor.armour";

    /** 幽魂護罩最多幾層。 */
    static final int SHROUD_MAX = 3;
    /** 「最近」＝4 秒。 */
    static final long RECENTLY = 80;
    /** 木甲的護甲持續 6 秒。 */
    static final long BARKSKIN_TICKS = 120;
    /** 每幾 tick 跑一次（SkillService 每 10 tick 呼叫）。 */
    static final long PERIOD = 10;

    private record Stack(double armour, long until) {
    }

    /** 拾鱗的一批甲片：幾層、到哪個 tick 失效。 */
    record Plate(int count, long until) {
    }

    private static final class State {
        long nextBlessing = -1;
        long nextShroud = -1;
        int shrouds;
        long shroudLost = Long.MIN_VALUE / 2;
        double lastEs = -1;
        /** 2026-10-27：上次結算到現在被敵人擊中打掉的能量護盾（木甲照這個算；這次之前 10 tick 內被打過，任何能量護盾減少都算）。 */
        double hitEs;
        final List<Stack> bark = new ArrayList<>();
        double barkCap;
        /** 拾鱗：身上的甲片（每批各自計時）、每層的數值（每 10 tick 從屬性表讀）。 */
        final List<Plate> plates = new ArrayList<>();
        double plateArmour;
        double plateThornsMin;
        double plateThornsMax;
        int plateShown;
        /** 風之舞步：從哪個 tick 開始累積階數（-1 = 沒開）、每階幾 tick、現在算進屬性表的階數、每階更多幾 % 閃避。 */
        long windSince = -1;
        long windInterval = 30;
        int windShown;
        double windEvasion;
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, State> states = new HashMap<>();

    PersistentBuffs(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個常駐技能開著嗎；開著的話它的等級（沒開回傳 0）。 */
    private int level(final Player player, final String skillId) {
        if (!service.auras().isActive(player.getUniqueId(), skillId)) {
            return 0;
        }
        final tw.exilecore.character.SkillLoadout loadout = plugin.players().get(player).skills();
        final int position = loadout.find(skillId);
        return position < 0 ? 0 : Math.max(1, loadout.skill(position).level());
    }

    private double levelValue(final String skillId, final String stat, final int level, final double fallback) {
        for (final ScaledMod mod : service.config().levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return fallback;
    }

    /** 冷卻回復速度（祝福、護罩的間隔照這個縮短）。 */
    private static double recovery(final StatSheet sheet) {
        return Math.max(0.1, sheet.apply(StatKeys.COOLDOWN_RECOVERY, 1.0, Set.of()));
    }

    // ---- 每 10 tick ----

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                service.resources().regenerate(player, PERIOD); // 不熄怒
                service.supportMechanics().regenGlory(player, PERIOD); // 2026-11-05 照 PoE2 Fury of the King：消耗榮耀的技能每 2 秒獲得榮耀
                final int need = level(player, TIME_OF_NEED);
                final int ghost = level(player, GHOST_DANCE);
                final int bark = level(player, BARKSKIN);
                final int plating = level(player, PLATING);
                final int wind = level(player, WIND_DANCER);
                if (need <= 0 && ghost <= 0 && bark <= 0 && plating <= 0 && wind <= 0) {
                    final State old = states.remove(player.getUniqueId());
                    if (old != null && (!old.bark.isEmpty() || old.plateShown > 0 || old.windShown > 0)) {
                        plugin.players().markDirty(player);
                    }
                    continue;
                }
                final State s = states.computeIfAbsent(player.getUniqueId(), k -> new State());
                final PlayerState state = plugin.players().get(player);
                final StatSheet sheet = plugin.players().sheet(player);
                blessing(player, s, state, sheet, need, now);
                shrouds(player, s, state, sheet, ghost, now);
                barkskin(player, s, state, sheet, bark, now);
                plating(player, s, sheet, plating, now);
                windDancer(player, s, sheet, wind, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的常駐增益（護時、幻步、木甲、不熄怒、拾鱗、風之舞步）出錯", player.getName(), e);
            }
        }
    }

    private void blessing(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.nextBlessing = -1;
            return;
        }
        final long every = Math.max(20, Math.round(levelValue(TIME_OF_NEED, BLESSING_INTERVAL, level, 10) * 20 / recovery(sheet)));
        if (s.nextBlessing < 0) {
            s.nextBlessing = now + every; // 剛開：等一個間隔
            return;
        }
        if (now < s.nextBlessing || state.isDead()) {
            return;
        }
        s.nextBlessing = now + every;
        final double heal = sheet.flat(BLESSING_LIFE, Set.of());
        if (heal > 0) {
            state.setLife(state.life() + heal);
        }
        if (plugin.ailments() != null) {
            for (final AilmentType type : new AilmentType[] {AilmentType.IGNITE, AilmentType.CHILL, AilmentType.FREEZE,
                    AilmentType.SHOCK, AilmentType.ELECTROCUTE}) {
                plugin.ailments().cure(player, type);
            }
        }
        if (plugin.combat() != null && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().removeCurses(player.getUniqueId()); // 2026-11-01 照 PoE2 Time of Need：祝福也解除身上的詛咒；這次之前只解元素異常
        }
        Fx.spawn(player.getWorld(), Particle.END_ROD, player.getLocation().add(0, 1.2, 0), 12, 0.4, 0.6, 0.4, 0.02);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
    }

    private void shrouds(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.nextShroud = -1;
            s.shrouds = 0;
            return;
        }
        final long every = Math.max(20, Math.round(levelValue(GHOST_DANCE, SHROUD_INTERVAL, level, 12) * 20 / recovery(sheet)));
        // 2026-11-11 照站長「一般品質再算一次的照原作改（第二批）」：高階奇術的 ghost_dance_max_stacks（幽魂護罩最多 +(0—1) 層，無條件捨去）；這次之前一般的品質效果再算一次
        final int max = SHROUD_MAX + OriginalSkills.qualityInt(Math.max(0, service.qualityFlat(player, GHOST_DANCE, tw.exilecore.stats.GemQualityKeys.MAX_STACKS)));
        if (s.shrouds > max) {
            s.shrouds = max;
        }
        if (s.nextShroud < 0 || s.shrouds >= max) {
            s.nextShroud = now + every;
        } else if (now >= s.nextShroud) {
            s.shrouds++;
            s.nextShroud = now + every;
        }
        if (now - s.shroudLost <= RECENTLY && !state.isDead()) {
            final double percent = sheet.flat(SHROUD_ES_REGEN, Set.of());
            final double regen = Math.max(0, sheet.get(StatKeys.EVASION)) * percent / 100.0 * PERIOD / 20.0;
            if (regen > 0 && state.energyShield() < state.maxEnergyShield()) {
                state.setEnergyShield(state.energyShield() + regen);
            }
        }
    }

    private void barkskin(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        boolean changed = s.bark.removeIf(stack -> stack.until() <= now);
        final double es = state.energyShield();
        final double lost = barkskinLoss(s.hitEs, s.lastEs);
        s.hitEs = 0;
        if (level > 0 && lost > 0) {
            final double percent = sheet.flat(BARKSKIN_ARMOUR, Set.of());
            final double gain = lost * percent / 100.0;
            if (gain > 0) {
                // 上限：身上原本的護甲（扣掉木甲自己給的）
                s.barkCap = Math.max(0, sheet.get(StatKeys.ARMOUR) - bonus(s));
                s.bark.add(new Stack(gain, now + BARKSKIN_TICKS));
                changed = true;
            }
        }
        if (level <= 0 && !s.bark.isEmpty()) {
            s.bark.clear();
            changed = true;
        }
        s.lastEs = es;
        if (changed) {
            plugin.players().markDirty(player);
        }
    }

    /**
     * 2026-10-27：木甲算的「被擊中失去的能量護盾」：擊中打掉的能量護盾（hitEs），最多到上次結算時身上有的（lastEs；不知道時照擊中的）。
     * 這次之前是被打後 10 tick 內能量護盾的任何減少（花能量護盾、別的扣法也算）。
     */
    static double barkskinLoss(final double hitEs, final double lastEs) {
        if (hitEs <= 0) {
            return 0;
        }
        return lastEs >= 0 ? Math.min(hitEs, lastEs) : hitEs;
    }

    // ---- 拾鱗（Scavenged Plating） ----

    private void plating(final Player player, final State s, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.plates.clear();
        } else {
            s.plates.removeIf(plate -> plate.until() <= now);
            s.plateArmour = Math.max(0, sheet.flat(PLATING_ARMOUR, Set.of()));
            s.plateThornsMin = Math.max(0, sheet.flat(PLATING_THORNS_MIN, Set.of()));
            s.plateThornsMax = Math.max(0, sheet.flat(PLATING_THORNS_MAX, Set.of()));
        }
        final int total = plateCount(s.plates);
        if (total != s.plateShown) {
            s.plateShown = total;
            plugin.players().markDirty(player);
        }
    }

    /** 照 PoE2：完全破甲的敵人給幾層甲片（普通 1、魔法 2、稀有 5、傳奇 10；不是怪物 0）。 */
    static int plateStacks(final tw.exilecore.monster.MonsterRarity rarity) {
        if (rarity == null) {
            return 0;
        }
        return switch (rarity) {
            case NORMAL -> 1;
            case MAGIC -> 2;
            case RARE -> 5;
            case UNIQUE -> 10;
        };
    }

    /** 身上甲片的層數合計。 */
    static int plateCount(final List<Plate> plates) {
        int total = 0;
        for (final Plate plate : plates) {
            total += plate.count();
        }
        return total;
    }

    /** 加一批甲片：超過 {@value #PLATING_MAX} 層時從最舊的那批扣掉（純邏輯）。 */
    static void addPlates(final List<Plate> plates, final int count, final long until) {
        if (count <= 0) {
            return;
        }
        plates.add(new Plate(Math.min(PLATING_MAX, count), until));
        int over = plateCount(plates) - PLATING_MAX;
        while (over > 0 && !plates.isEmpty()) {
            final Plate oldest = plates.getFirst();
            if (oldest.count() <= over) {
                plates.removeFirst();
                over -= oldest.count();
            } else {
                plates.set(0, new Plate(oldest.count() - over, oldest.until()));
                over = 0;
            }
        }
    }

    /** 你讓敵人完全破甲時（CombatListener → SkillService#armourFullyBroken）：拾鱗開著就照牠的稀有度獲得甲片。 */
    void fullBreak(final Player player, final org.bukkit.entity.LivingEntity defender) {
        if (level(player, PLATING) <= 0 || defender == null || plugin.monsters() == null) {
            return;
        }
        final int count = plateStacks(plugin.monsters().data(defender).map(data -> data.rarity()).orElse(null));
        if (count <= 0) {
            return;
        }
        final long now = now();
        final State s = states.computeIfAbsent(player.getUniqueId(), k -> new State());
        final double seconds = PLATING_TICKS / 20.0 + Math.max(0, service.qualityFlat(player, PLATING, PLATING_DURATION));
        addPlates(s.plates, count, now + Math.round(seconds * 20));
        if (s.plateArmour <= 0) {
            final StatSheet sheet = plugin.players().sheet(player); // 剛開還沒跑過 tick：先讀一次每層的數值
            s.plateArmour = Math.max(0, sheet.flat(PLATING_ARMOUR, Set.of()));
            s.plateThornsMin = Math.max(0, sheet.flat(PLATING_THORNS_MIN, Set.of()));
            s.plateThornsMax = Math.max(0, sheet.flat(PLATING_THORNS_MAX, Set.of()));
        }
        s.plateShown = plateCount(s.plates);
        plugin.players().markDirty(player);
        Fx.spawn(player.getWorld(), Particle.CRIT, player.getLocation().add(0, 1.0, 0), 6, 0.3, 0.4, 0.3, 0.05);
    }

    // ---- 風之舞步（Wind Dancer） ----

    /** 照 PoE2：從 since 開始每 interval tick 一階，最多 {@value #WIND_MAX} 階（純邏輯）。 */
    static int windStages(final long since, final long now, final long interval) {
        if (since < 0 || now <= since) {
            return 0;
        }
        return (int) Math.min(WIND_MAX, (now - since) / Math.max(1, interval));
    }

    private void windDancer(final Player player, final State s, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.windSince = -1;
        } else {
            s.windInterval = Math.max(1, Math.round(levelValue(WIND_DANCER, WIND_INTERVAL, level, 1.5) * 20));
            s.windEvasion = Math.max(0, sheet.flat(WIND_EVASION, Set.of()));
            if (s.windSince < 0) {
                s.windSince = now; // 剛開：從 0 階開始累積
            }
        }
        final int stages = windStages(s.windSince, now, s.windInterval);
        if (stages != s.windShown) {
            s.windShown = stages;
            plugin.players().markDirty(player);
        }
    }

    /** 照 PoE2 Gale Force：每階範圍更多 100%（半徑乘這個）。 */
    static double galeRadiusScale(final int stages) {
        return Math.sqrt(1 + Math.max(0, stages));
    }

    /** 照 PoE2 Gale Force：每階傷害更多 50%。 */
    static double galeDamageScale(final int stages) {
        return 1 + 0.5 * Math.max(0, stages);
    }

    /** 照 PoE2 Gale Force：每階擊退距離更多 25%。 */
    static double galeKnockbackScale(final int stages) {
        return 1 + 0.25 * Math.max(0, stages);
    }

    /** 帶著階數被敵人擊中：用掉全部階數放疾風（冷卻中就不放、也不用掉）。 */
    private void galeForce(final Player player, final State s, final long now) {
        final int stages = windStages(s.windSince, now, s.windInterval);
        if (stages <= 0 || level(player, WIND_DANCER) <= 0) {
            return;
        }
        final double radiusMetres = Math.max(0, service.qualityFlat(player, WIND_DANCER, GALE_RADIUS));
        // 2026-11-11 照站長「一般品質再算一次的照原作改（第二批）」：高階奇術的 wind_dancer_damage_+%_final_per_stage（疾風每一階另外更多 (0—50)% 傷害）；這次之前一般的品質效果再算一次
        final double perStage = Math.max(0, service.qualityFlat(player, WIND_DANCER, tw.exilecore.stats.GemQualityKeys.GALE_MORE_PER_STAGE));
        final boolean fired = service.procs().castAt(player, GALE_FORCE, WIND_DANCER, player.getLocation(), build -> build
                .withBaseRadius(radiusMetres)
                .withAreaAndProjectiles(build.areaScale() * galeRadiusScale(stages), build.projectiles())
                .withKnockback(build.knockback() * galeKnockbackScale(stages))
                .withProfile(build.profile().scaled(galeDamageScale(stages) * (1 + perStage / 100.0 * stages), "風之舞步 " + stages + " 階")));
        if (fired) {
            s.windSince = now;
            s.windShown = 0;
            plugin.players().markDirty(player);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 1.1f);
        }
    }

    private static double bonus(final State s) {
        double total = 0;
        for (final Stack stack : s.bark) {
            total += stack.armour();
        }
        return Math.min(total, s.barkCap);
    }

    /** 木甲給的護甲、拾鱗的甲片（護甲、荊棘）、風之舞步的階數（閃避）（PlayerStateService 的增益來源）。 */
    List<Modifier> modifiers(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double armour = s.bark.isEmpty() ? 0 : bonus(s);
        if (armour > 0) {
            list.add(Modifier.flat(StatKeys.ARMOUR, armour, "木甲"));
        }
        final int plates = s.plateShown;
        if (plates > 0) {
            if (s.plateArmour > 0) {
                list.add(Modifier.more(StatKeys.ARMOUR, plates * s.plateArmour / 100.0, "拾鱗"));
            }
            if (s.plateThornsMax > 0) {
                list.add(Modifier.flat(StatKeys.THORNS_MIN, plates * s.plateThornsMin, "拾鱗"));
                list.add(Modifier.flat(StatKeys.THORNS_MAX, plates * s.plateThornsMax, "拾鱗"));
            }
        }
        if (s.windShown > 0 && s.windEvasion > 0) {
            list.add(Modifier.more(StatKeys.EVASION, s.windShown * s.windEvasion / 100.0, "風之舞步"));
        }
        return list;
    }

    /** 拾鱗現在幾層、風之舞步現在幾階（說明、測試用）。 */
    int plates(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.plateShown;
    }

    /** 幽魂護罩現在幾層（說明、測試用）。 */
    int shrouds(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.shrouds;
    }

    // ---- 被擊中：幻步失去一層護罩、木甲記下來 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitTaken(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit()) {
            return;
        }
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        final long now = now();
        s.hitEs += Math.max(0, event.result().esDamage()); // 2026-10-27：木甲照擊中打掉的能量護盾
        if (s.shrouds > 0) {
            s.shrouds--;
            s.shroudLost = now;
        }
        galeForce(player, s, now); // 2026-11-01 照 PoE2 Wind Dancer：被敵人擊中時用掉全部階數放疾風
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
