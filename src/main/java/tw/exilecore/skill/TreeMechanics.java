package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeNewMechanicKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-10-26：照 PoE2 0.5 做出天賦樹「新機制」類天賦要的系統，讓那些天賦真的生效（屬性鍵在 {@link TreeNewMechanicKeys}）。
 * <ul>
 *   <li>統治者增益（Archon）：被觸發的強化狀態，預設 10 秒；失去後 20 秒恢復期內不能再獲得任何統治者。
 *   元素統治者（累計消耗 100% 最大魔力）：法術元素傷害更多 25%、法術不能造成非元素傷害、凍結累積／點燃機率／感電機率更多 100%，
 *   使用攻擊就失去；不死統治者（使用指令技能時擲機率）：召喚物傷害更多 25%、指令技能冷卻回復速度更多 200%。</li>
 *   <li>易爆（Volatility）：層數，最後一次獲得後 4 秒爆炸，每層對你造成 100 物理傷害，之後 10 秒易爆之力（每層 1% 傷害額外獲得為混沌）。</li>
 *   <li>切割（Incision）：敵人身上的層數（最多 10），每層讓牠被你擊中時多 10% 機率流血，流血時全部拿掉。</li>
 *   <li>傀儡大師（Puppet Master）：使用指令技能時獲得的層數，每層 8 秒、最多 5 層。</li>
 *   <li>秘術增強（Arcane Surge）：增加 15% 施法速度、魔力回復更多 20%。</li>
 *   <li>先祖強化（Ancestral Boost）：猛擊更多 30% 傷害、範圍增加 25%；打擊多打 2 個目標；算強化攻擊。</li>
 *   <li>衝刺：翻滾之後一直按著翻滾鍵移動。</li>
 * </ul>
 * 還有重暈、釘住、招架、圖騰、護符、詛咒、標記、存在範圍內敵人的一些天賦效果。
 */
public final class TreeMechanics implements Listener {

    /** 每幾 tick 檢查一次（統治者到期、易爆爆炸、傀儡大師到期、衝刺、存在範圍內的敵人…）。 */
    public static final long PERIOD = 5;

    // ---- 統治者 ----
    public static final long ARCHON_TICKS = 200;
    public static final long ARCHON_RECOVERY_TICKS = 400;
    public static final String ARCHON_BUFF = "archon";

    /** 統治者的種類。 */
    public enum Archon {
        ELEMENTAL("元素統治者"), UNDEATH("不死統治者");

        private final String displayName;

        Archon(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    // ---- 易爆 ----
    public static final long VOLATILITY_DELAY_TICKS = 80;
    public static final int VOLATILITY_MAX = 200;
    public static final double VOLATILITY_DAMAGE = 100;
    public static final long VOLATILE_POWER_TICKS = 200;
    public static final long VOLATILITY_GAIN_GAP = 2;

    // ---- 切割 ----
    public static final int INCISION_MAX = 10;
    public static final double INCISION_BLEED_CHANCE = 0.10;
    public static final long INCISION_TICKS = 100;

    // ---- 傀儡大師 ----
    public static final long PUPPET_TICKS = 160;
    public static final int PUPPET_MAX = 5;

    // ---- 秘術增強 ----
    public static final double ARCANE_SURGE_CAST = 0.15;
    public static final double ARCANE_SURGE_REGEN = 0.20;
    public static final long ARCANE_SURGE_TICKS = 200;

    // ---- 先祖強化 ----
    public static final double ANCESTRAL_MORE = 0.30;
    public static final double ANCESTRAL_AREA = 0.25;
    public static final int ANCESTRAL_TARGETS = 2;

    /** 招架之後的增益只是提示（真正的傷害在下一次攻擊時加）。 */
    static final String PARRY_READY = "tree.parry_ready";

    /** 「最近」：4 秒。 */
    public static final long RECENT_TICKS = 80;

    /** 增益名稱（快捷欄字型要收進去）。 */
    public static final List<String> HUD_TEXTS = List.of("元素統治者", "不死統治者", "易爆之力", "傀儡大師", "秘術增強", "最近放過圖騰",
            "招架後的遠程攻擊", "招架後的近戰攻擊");

    /** 每個玩家的狀態。 */
    static final class State {
        Archon archon;
        long archonUntil;
        long archonRecoveryUntil;
        double manaSpent;
        double surgeSpent;
        int volatility;
        long volatilityExplodeAt;
        long volatilityLastGain = Long.MIN_VALUE / 2;
        final List<Long> puppet = new ArrayList<>();
        long ancestralUntil;
        boolean ancestralNext;
        int slams;
        double parryRanged;
        double parryMelee;
        int comboUsed;
        boolean sprinting;
        double sprintSpeed;
        double lastEs = -1;
        boolean recharging;
        SkillProfile lastSkill;
        /** 2026-10-27（塗油限定 岡姆之祝）：戰吼過了，下一個親自施放的火焰法術先祖強化。 */
        boolean fireSpellAncestral;
        /** 2026-10-27（塗油限定 危境增長）：增益現在照幾層易爆算的（層數變了才重給）。 */
        int perilStacks;
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, State> states = new HashMap<>();
    /** 切割：敵人 → 施加者的切割效果倍率（1 ＋ 增加）。 */
    private final Map<UUID, Double> incisionEffect = new HashMap<>();
    /** 流血：敵人 → 第一次看到牠身上有流血的 tick（「加劇存在超過 x 秒的流血」用）。 */
    private final Map<UUID, Long> bleedingSince = new HashMap<>();
    /** 釘住：敵人 → 釘住牠的玩家（解除釘住後癱瘓）。 */
    private final Map<UUID, UUID> pinnedBy = new HashMap<>();
    /** 敵人 → 最後打牠的召喚物（夥伴擊殺時疾速）。 */
    private final Map<UUID, UUID> lastMinionHit = new HashMap<>();
    /** 這一下擊中之前目標有沒有被釘住（LOWEST 記下、MONITOR 比較）。 */
    private final Set<UUID> pinnedBefore = new HashSet<>();
    /** 2026-10-27：上次清掉已經不在的敵人是哪個 tick。 */
    private long prunedAt;

    TreeMechanics(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        if (plugin.characters() != null) {
            // 2026-10-27：換角色時清掉上一個角色的統治者、易變、遠古之力…；這次之前只在下線時清
            plugin.characters().addCharacterResetHook(player -> states.remove(player.getUniqueId()));
        }
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private RandomGenerator random() {
        return plugin.random();
    }

    private boolean roll(final double percent) {
        return percent >= 100 || percent > 0 && random().nextDouble() * 100 < percent;
    }

    State state(final UUID player) {
        return states.computeIfAbsent(player, id -> new State());
    }

    private Debuffs debuffs() {
        return plugin.combat() == null ? null : plugin.combat().debuffs();
    }

    // =====================================================================
    // 純計算（測試用）
    // =====================================================================

    /** 超越機率（PoE2 Surpassing Chance）：每 100% 必定一次，剩下的照機率再一次。 */
    public static int surpassing(final double percent, final double roll01) {
        if (percent <= 0) {
            return 0;
        }
        final int sure = (int) Math.floor(percent / 100.0);
        return sure + (roll01 * 100 < percent - sure * 100 ? 1 : 0);
    }

    /** 統治者增益持續幾 tick（10 秒 × (1 ＋ 持續時間)）。 */
    public static long archonTicks(final StatSheet sheet) {
        return Math.max(20, Math.round(ARCHON_TICKS * Math.max(0.1, 1 + sheet.increased(TreeNewMechanicKeys.ARCHON_DURATION, Set.of()))));
    }

    /** 統治者恢復期幾 tick（20 秒 ÷ (1 ＋ 恢復期結束速度)；沒有恢復期的是 0）。 */
    public static long archonRecoveryTicks(final StatSheet sheet) {
        if (StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_ARCHON_NO_RECOVERY)) {
            return 0;
        }
        return Math.max(0, Math.round(ARCHON_RECOVERY_TICKS / Math.max(0.1, 1 + sheet.increased(TreeNewMechanicKeys.ARCHON_RECOVERY, Set.of()))));
    }

    /** 統治者增益給的修飾器（效果乘 (1 ＋ 你身上的統治者效果)；另外給的抗性、移動速度、暴擊也一樣乘）。 */
    public static List<Modifier> archonModifiers(final Archon archon, final StatSheet sheet) {
        final double effect = Math.max(0, 1 + sheet.increased(TreeNewMechanicKeys.ARCHON_EFFECT, Set.of()));
        final String source = archon.displayName();
        final List<Modifier> mods = new ArrayList<>();
        if (archon == Archon.ELEMENTAL) {
            mods.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.25 * effect, Set.of(Tags.SPELL, Tags.ELEMENTAL), source));
            // 法術不能造成非元素傷害
            mods.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, -1.0, Set.of(Tags.SPELL, Tags.PHYSICAL), source));
            mods.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, -1.0, Set.of(Tags.SPELL, Tags.CHAOS), source));
            mods.add(new Modifier(StatKeys.FREEZE_BUILDUP, ModType.MORE, 1.0 * effect, Set.of(Tags.SPELL), source));
            mods.add(new Modifier(StatKeys.IGNITE_CHANCE, ModType.MORE, 1.0 * effect, Set.of(Tags.SPELL), source));
            mods.add(new Modifier(StatKeys.SHOCK_CHANCE, ModType.MORE, 1.0 * effect, Set.of(Tags.SPELL), source));
        } else {
            mods.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.25 * effect, Set.of(Tags.MINION), source));
            mods.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.MORE, 2.0 * effect, Set.of("command"), source));
        }
        final double resist = sheet.flat(TreeNewMechanicKeys.ARCHON_GRANT_RESIST, Set.of()) * effect;
        if (resist != 0) {
            mods.add(Modifier.flat(StatKeys.RESIST_FIRE, resist, source));
            mods.add(Modifier.flat(StatKeys.RESIST_COLD, resist, source));
            mods.add(Modifier.flat(StatKeys.RESIST_LIGHTNING, resist, source));
        }
        final double movement = sheet.increased(TreeNewMechanicKeys.ARCHON_GRANT_MOVEMENT, Set.of()) * effect;
        if (movement != 0) {
            mods.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, movement, source));
        }
        final double crit = sheet.increased(TreeNewMechanicKeys.ARCHON_GRANT_CRIT_CHANCE, Set.of()) * effect;
        if (crit != 0) {
            mods.add(Modifier.increased(StatKeys.CRIT_CHANCE, crit, source));
        }
        final double critBonus = sheet.increased(TreeNewMechanicKeys.ARCHON_GRANT_CRIT_BONUS, Set.of()) * effect;
        if (critBonus != 0) {
            mods.add(Modifier.increased(StatKeys.CRIT_BONUS, critBonus, source));
        }
        return mods;
    }

    /** 易爆幾 tick 後爆炸（4 秒 × (1 ＋ 延遲)）。 */
    public static long volatilityDelay(final StatSheet sheet) {
        return Math.max(10, Math.round(VOLATILITY_DELAY_TICKS * Math.max(0.1, 1 + sheet.increased(TreeNewMechanicKeys.VOLATILITY_DELAY, Set.of()))));
    }

    /** 最大易爆（預設 200；「最大易爆為 30」）。 */
    public static int volatilityMax(final StatSheet sheet) {
        final double set = sheet.flat(TreeNewMechanicKeys.VOLATILITY_MAX, Set.of());
        return set > 0 ? (int) Math.round(set) : VOLATILITY_MAX;
    }

    /** 易爆之力：每層 1% 傷害額外獲得為混沌（加天賦的每層暴擊率）。 */
    public static List<Modifier> volatilePower(final int stacks, final StatSheet sheet) {
        final List<Modifier> mods = new ArrayList<>();
        mods.add(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), stacks, "易爆之力"));
        final double crit = sheet.flat(TreeNewMechanicKeys.VOLATILITY_POWER_CRIT, Set.of());
        if (crit > 0) {
            mods.add(Modifier.increased(StatKeys.CRIT_CHANCE, crit * stacks / 100.0, "易爆之力"));
        }
        return mods;
    }

    /** 易爆爆炸對你造成的物理傷害（每層 100，先吃護甲、物理減傷）。 */
    public static double volatilityDamage(final int stacks, final StatSheet sheet) {
        final double raw = VOLATILITY_DAMAGE * stacks;
        final double armour = Formulas.armourReduction(Math.max(0, sheet.get(StatKeys.ARMOUR)), raw);
        final double reduction = Math.max(0, Math.min(90, sheet.flat(StatKeys.PHYS_REDUCTION, Set.of()))) / 100.0;
        return raw * (1 - armour) * (1 - reduction);
    }

    /** 傀儡大師的修飾器（層數 × 效果）。 */
    public static List<Modifier> puppetModifiers(final int stacks, final StatSheet sheet) {
        final double each = stacks * Math.max(0, 1 + sheet.increased(TreeNewMechanicKeys.PUPPET_EFFECT, Set.of()));
        final Set<String> command = Set.of("command");
        final String source = "傀儡大師";
        return List.of(new Modifier(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.10 * each, command, source),
                new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.10 * each, command, source),
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.10 * each, command, source),
                new Modifier(StatKeys.MOVEMENT_SPEED, ModType.INCREASED, 0.02 * each, Set.of(Tags.MINION), source),
                new Modifier(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.03 * each, Set.of(Tags.MINION), source),
                new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.03 * each, Set.of(Tags.MINION), source));
    }

    /** 最多幾層傀儡大師。 */
    public static int puppetMax(final StatSheet sheet) {
        return Math.max(1, PUPPET_MAX + (int) Math.round(sheet.flat(TreeNewMechanicKeys.PUPPET_MAX, Set.of())));
    }

    /** 秘術增強的修飾器（效果乘 (1 ＋ 你身上的秘術增強效果)；有旗標的話「魔力回復更多」改成生命回復）。 */
    public static List<Modifier> arcaneSurge(final StatSheet sheet) {
        final double effect = Math.max(0, 1 + sheet.increased(TreeNewMechanicKeys.ARCANE_SURGE_EFFECT, Set.of()));
        final boolean life = StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_ARCANE_SURGE_LIFE);
        return List.of(Modifier.increased(StatKeys.CAST_SPEED, ARCANE_SURGE_CAST * effect, "秘術增強"),
                Modifier.more(life ? StatKeys.LIFE_REGEN : StatKeys.MANA_REGEN, ARCANE_SURGE_REGEN * effect, "秘術增強"));
    }

    // =====================================================================
    // 放技能（SkillService 呼叫）
    // =====================================================================

    /**
     * 技能真的放了（扣完消耗之後，所有技能都會到這裡）：累計魔力消耗（元素統治者、秘術增強）、指令技能（傀儡大師、不死統治者）、
     * 攻擊讓元素統治者消失、放圖騰（最近放過圖騰）、記下這次用掉幾點連擊。
     */
    void onUse(final Player player, final SkillBuild build, final int manaCost, final int comboUsed) {
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        final State state = state(player.getUniqueId());
        final long now = now();
        final PlayerState ps = plugin.players().get(player);
        state.comboUsed = build.definition().isAttack() ? comboUsed : 0; // 用掉連擊的傷害給這一下攻擊（beforeAttack 用掉）
        if (state.archon == Archon.ELEMENTAL && build.definition().isAttack() && now < state.archonUntil
                && !tw.exilecore.ascendancy.AscendancyEffects.archonHeld(player)) { // 2026-10-26：昇華・沸騰軀體（高煉獄之炎）例外
            loseArchon(player, state, sheet, now); // 使用攻擊：元素統治者消失
        }
        if (manaCost > 0) {
            if (StatKeys.flag(sheet, TreeNewMechanicKeys.ARCHON_ELEMENTAL_ON_MANA) && ps.maxMana() > 0) {
                state.manaSpent += manaCost;
                if (state.manaSpent >= ps.maxMana()) {
                    if (gainArchon(player, Archon.ELEMENTAL)) {
                        state.manaSpent = 0;
                    } else {
                        state.manaSpent = ps.maxMana(); // 恢復期中：存滿，恢復期一過下一次花魔力就給
                    }
                }
            }
            final double surge = effective.flat(TreeNewMechanicKeys.ARCANE_SURGE_ON_SPEND, tags);
            if (surge > 0 && ps.maxMana() > 0) {
                state.surgeSpent += manaCost;
                if (state.surgeSpent >= ps.maxMana() * surge / 100.0) {
                    state.surgeSpent = 0;
                    grantArcaneSurge(player, (int) ARCANE_SURGE_TICKS);
                }
            }
        }
        if (build.definition().mechanic() == Mechanic.WARCRY && StatKeys.flag(sheet, tw.exilecore.stats.AnointKeys.FLAG_WARCRY_FIRE_SPELL_ANCESTRAL)) {
            state.fireSpellAncestral = true; // 2026-10-27 照 PoE2（塗油限定 岡姆之祝）：下一個火焰法術先祖強化
        }
        if (tags.contains("command") || build.definition().tags().contains("command")) {
            if (roll(sheet.flat(TreeNewMechanicKeys.ARCHON_UNDEATH_ON_COMMAND, Set.of()))) {
                gainArchon(player, Archon.UNDEATH);
            }
            // 2026-10-27 照 PoE2（塗油限定 群集復甦）：x% 機率讓一種等著復活的常駐召喚物馬上復活
            if (service.auras() != null && roll(sheet.flat(tw.exilecore.stats.AnointKeys.COMMAND_REVIVE_CHANCE, Set.of()))) {
                service.auras().reviveRandom(player.getUniqueId(), now, random());
            }
            final int stacks = surpassing(sheet.flat(TreeNewMechanicKeys.PUPPET_CHANCE, Set.of()), random().nextDouble());
            if (stacks > 0) {
                addPuppet(player, state, sheet, stacks, now);
            }
        }
        if (build.deploy() != null && build.deploy().kind() == DeploySpec.Kind.TOTEM) {
            final double speed = sheet.flat(TreeNewMechanicKeys.TOTEM_RECENT_SPEED, Set.of());
            if (speed > 0) {
                service.buffs().apply(player, "tree.totem_recent", "最近放過圖騰", (int) RECENT_TICKS,
                        List.of(Modifier.increased(StatKeys.ATTACK_SPEED, speed / 100.0, "最近放過圖騰"),
                                Modifier.increased(StatKeys.CAST_SPEED, speed / 100.0, "最近放過圖騰")));
            }
        }
    }

    /**
     * 攻擊要出手了（戰吼的強化之後）：先祖強化（重暈稀有怪之後、每第二次猛擊）、招架之後的下一次遠程／近戰攻擊、
     * 用掉連擊的傷害。回傳改好的技能。
     */
    SkillBuild beforeAttack(final Player player, final SkillBuild build) {
        if (!build.definition().isAttack()) {
            // 2026-10-27 照 PoE2（塗油限定 岡姆之祝）：戰吼後親自施放的下一個火焰法術先祖強化（觸發的不經過這裡）
            final State state = states.get(player.getUniqueId());
            if (state != null && state.fireSpellAncestral && build.profile().tags().contains(Tags.SPELL)
                    && build.profile().tags().contains(Tags.FIRE)) {
                state.fireSpellAncestral = false;
                return ancestralBoost(build, plugin.players().sheet(player));
            }
            return build;
        }
        final State state = state(player.getUniqueId());
        final StatSheet sheet = plugin.players().sheet(player);
        final long now = now();
        final Set<String> tags = build.profile().tags();
        SkillBuild result = build;
        final List<Modifier> local = new ArrayList<>();
        // 招架之後
        if (state.parryRanged > 0 && !tags.contains(Tags.MELEE)) {
            local.add(Modifier.increased(StatKeys.DAMAGE, state.parryRanged / 100.0, "招架後的遠程攻擊"));
            state.parryRanged = 0;
            service.buffs().remove(player, "tree.parry_ranged");
        } else if (state.parryMelee > 0 && tags.contains(Tags.MELEE)) {
            local.add(Modifier.increased(StatKeys.DAMAGE, state.parryMelee / 100.0, "招架後的近戰攻擊"));
            state.parryMelee = 0;
            service.buffs().remove(player, "tree.parry_melee");
        }
        // 用掉連擊：每點增加 x%，最多 40%
        final double perCombo = sheet.flat(TreeNewMechanicKeys.COMBO_CONSUMED_DAMAGE, Set.of());
        if (perCombo > 0 && state.comboUsed > 0) {
            local.add(Modifier.increased(StatKeys.DAMAGE, Math.min(40, perCombo * state.comboUsed) / 100.0, "用掉連擊"));
        }
        state.comboUsed = 0;
        // 先祖強化
        boolean ancestral = false;
        if (state.ancestralNext && now <= state.ancestralUntil) {
            ancestral = true;
            state.ancestralNext = false;
        }
        if (tags.contains(Tags.SLAM) && StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_ANCESTRAL_EVERY_OTHER_SLAM)) {
            state.slams++;
            if (state.slams % 2 == 0) {
                ancestral = true;
            }
        }
        if (!local.isEmpty()) {
            result = result.withProfile(result.profile().withLocal(local));
        }
        if (ancestral) {
            result = ancestralBoost(result, sheet);
            plugin.players().get(player).markRecent(tw.exilecore.stats.TreeLastKeys.EVENT_EMPOWERED, now()); // 2026-10-27：先祖強化也算強化攻擊
        }
        return result;
    }

    /** 先祖強化：猛擊更多 30% 傷害、範圍增加 25%（加天賦）；打擊多打 2 個目標；帶 empowered 與 ancestral_boosted 標籤。 */
    static SkillBuild ancestralBoost(final SkillBuild build, final StatSheet sheet) {
        final Set<String> tags = build.profile().tags();
        SkillBuild result = build.withProfile(build.profile().derive(build.profile().id(),
                Set.of(tw.exilecore.stats.TreeMechanicKeys.EMPOWERED, TreeNewMechanicKeys.TAG_ANCESTRAL), Set.of()));
        final double area = sheet.increased(TreeNewMechanicKeys.ANCESTRAL_AREA, Set.of());
        if (tags.contains(Tags.SLAM) || !tags.contains(Tags.STRIKE)) {
            result = result.withProfile(result.profile().scaled(1 + ANCESTRAL_MORE, "先祖強化"));
            result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(Math.max(0.1, 1 + ANCESTRAL_AREA + area)), result.projectiles());
        } else {
            result = result.withTargets(result.targets() + ANCESTRAL_TARGETS);
            if (area != 0) {
                result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(Math.max(0.1, 1 + area)), result.projectiles());
            }
        }
        return result;
    }

    // =====================================================================
    // 統治者
    // =====================================================================

    /** 獲得統治者增益；已經有統治者或在恢復期中回傳 false。 */
    public boolean gainArchon(final Player player, final Archon archon) {
        final State state = state(player.getUniqueId());
        final long now = now();
        if (state.archon != null && now < state.archonUntil || now < state.archonRecoveryUntil) {
            return false;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final long ticks = archonTicks(sheet);
        state.archon = archon;
        state.archonUntil = now + ticks;
        service.buffs().apply(player, ARCHON_BUFF, archon.displayName(), (int) ticks, archonModifiers(archon, sheet));
        if (archon == Archon.UNDEATH && service.auras() != null) {
            service.auras().reviveUndead(player.getUniqueId(), now); // 不死統治者：倒下等復活的常駐不死召喚物立刻復活
        }
        final PlayerState ps = plugin.players().get(player);
        ps.notice("<#c7a6ff>✦ " + archon.displayName() + " <gray>" + Math.round(ticks / 20.0) + " 秒", now + 40);
        plugin.players().display(player, ps);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.4f);
        player.getWorld().spawnParticle(archon == Archon.ELEMENTAL ? Particle.ELECTRIC_SPARK : Particle.SOUL, player.getLocation().add(0, 1, 0),
                24, 0.5, 0.8, 0.5, 0.05);
        return true;
    }

    /** 現在身上的統治者（沒有是 null；給條件、說明用）。 */
    public Archon archon(final Player player) {
        final State state = states.get(player.getUniqueId());
        return state == null || state.archon == null || now() >= state.archonUntil ? null : state.archon;
    }

    /** 2026-10-26（昇華・沸騰軀體）：身上的統治者至少撐到 until（重給增益）；沒有統治者不做事。 */
    public void holdArchon(final Player player, final long until) {
        final State state = states.get(player.getUniqueId());
        final long now = now();
        if (state == null || state.archon == null || now >= state.archonUntil || state.archonUntil >= until) {
            return;
        }
        state.archonUntil = until;
        service.buffs().apply(player, ARCHON_BUFF, state.archon.displayName(), (int) (until - now),
                archonModifiers(state.archon, plugin.players().sheet(player)));
    }

    /** 2026-10-26（昇華・沸騰軀體：達到煉獄之炎上限）：失去統治者、進入恢復期。 */
    public void endArchon(final Player player) {
        final State state = states.get(player.getUniqueId());
        if (state != null && state.archon != null && now() < state.archonUntil) {
            loseArchon(player, state, plugin.players().sheet(player), now());
        }
    }

    private void loseArchon(final Player player, final State state, final StatSheet sheet, final long now) {
        state.archon = null;
        state.archonUntil = 0;
        state.archonRecoveryUntil = now + archonRecoveryTicks(sheet);
        service.buffs().remove(player, ARCHON_BUFF);
    }

    // =====================================================================
    // 易爆
    // =====================================================================

    /** 獲得 amount 層易爆（0.1 秒最多一次；爆炸計時重來）。 */
    public void gainVolatility(final Player player, final int amount) {
        if (amount <= 0) {
            return;
        }
        final State state = state(player.getUniqueId());
        final long now = now();
        if (now - state.volatilityLastGain < VOLATILITY_GAIN_GAP) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        state.volatilityLastGain = now;
        state.volatility = Math.min(volatilityMax(sheet), state.volatility + amount);
        state.volatilityExplodeAt = now + volatilityDelay(sheet);
        final PlayerState ps = plugin.players().get(player);
        ps.notice("<#b06cff>易爆 <white>" + state.volatility, now + 30);
        plugin.players().display(player, ps);
    }

    /**
     * 2026-10-27 照 PoE2 Catharsis（淨化輔助：「Using Supported Skills will explode a random amount of Volatility on you」）：
     * 現在就引爆 amount 層（剩下的照原本的計時）。
     */
    public void explodeVolatility(final Player player, final int amount) {
        final State state = states.get(player.getUniqueId());
        if (state == null || state.volatility <= 0 || amount <= 0) {
            return;
        }
        final int keep = Math.max(0, state.volatility - amount);
        final long explodeAt = state.volatilityExplodeAt;
        state.volatility = Math.min(state.volatility, amount);
        explodeVolatility(player, state, now());
        if (keep > 0) {
            state.volatility = Math.min(volatilityMax(plugin.players().sheet(player)), state.volatility + keep);
            state.volatilityExplodeAt = Math.max(state.volatilityExplodeAt, explodeAt);
        }
    }

    /** 身上有幾層易爆。 */
    public int volatility(final Player player) {
        final State state = states.get(player.getUniqueId());
        return state == null ? 0 : state.volatility;
    }

    private void explodeVolatility(final Player player, final State state, final long now) {
        final int stacks = state.volatility;
        state.volatility = 0;
        if (stacks <= 0) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final PlayerState ps = plugin.players().get(player);
        if (!StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_VOLATILITY_IMMUNE)) {
            double damage = volatilityDamage(stacks, sheet);
            final double es = Math.min(ps.energyShield(), damage);
            if (es > 0) {
                ps.setEnergyShield(ps.energyShield() - es);
                damage -= es;
            }
            if (damage > 0) {
                ps.setLife(Math.max(1, ps.life() - damage)); // 易爆不會讓你死（只會剩 1 點生命）
            }
        }
        service.buffs().apply(player, "tree.volatile_power", "易爆之力", (int) VOLATILE_POWER_TICKS, volatilePower(stacks, sheet));
        ps.notice("<#b06cff>✸ 易爆爆炸：" + stacks + " 層 <gray>（易爆之力 10 秒）", now + 40);
        plugin.players().display(player, ps);
        final Location at = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.WITCH, at, 30, 0.6, 0.6, 0.6, 0.1);
        player.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.4f, 1.6f);
        if (roll(sheet.flat(TreeNewMechanicKeys.VOLATILITY_REFRESH, Set.of()))) {
            state.volatility = Math.min(volatilityMax(sheet), stacks);
            state.volatilityExplodeAt = now + volatilityDelay(sheet);
        }
    }

    // =====================================================================
    // 傀儡大師、秘術增強
    // =====================================================================

    private void addPuppet(final Player player, final State state, final StatSheet sheet, final int stacks, final long now) {
        final long ticks = Math.max(1, Math.round(PUPPET_TICKS * Math.max(0.1, 1 + sheet.increased(TreeNewMechanicKeys.PUPPET_DURATION, Set.of()))));
        final int max = puppetMax(sheet);
        for (int i = 0; i < stacks; i++) {
            if (state.puppet.size() >= max) {
                state.puppet.sort(Long::compare);
                state.puppet.removeFirst(); // 滿了：換掉最快到期的那層
            }
            state.puppet.add(now + ticks);
        }
        refreshPuppet(player, state, sheet, now);
    }

    private void refreshPuppet(final Player player, final State state, final StatSheet sheet, final long now) {
        state.puppet.removeIf(until -> until <= now);
        if (state.puppet.isEmpty()) {
            service.buffs().remove(player, "tree.puppet");
            return;
        }
        long first = Long.MAX_VALUE;
        for (final long until : state.puppet) {
            first = Math.min(first, until);
        }
        // 增益到下一層到期為止（到期時 tick 會重算層數）
        service.buffs().apply(player, "tree.puppet", "傀儡大師", (int) Math.max(1, first - now + PERIOD),
                puppetModifiers(state.puppet.size(), sheet));
    }

    /** 給秘術增強 ticks 個 tick（湧詠輔助、天賦、傳奇）。 */
    public void grantArcaneSurge(final Player player, final int ticks) {
        service.buffs().apply(player, "tree.arcane_surge", "秘術增強", ticks, arcaneSurge(plugin.players().sheet(player)));
    }

    // =====================================================================
    // 別的系統呼叫的
    // =====================================================================

    /** 重暈了敵人（AilmentService 呼叫）：稀有、傳奇的回復生命、給磐星、下一次攻擊先祖強化；破甲。 */
    public void onHeavyStun(final Player player, final LivingEntity defender) {
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(defender).orElse(null);
        if (data == null) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final boolean rare = data.boss() || data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE;
        final long now = now();
        if (rare) {
            final double life = sheet.flat(TreeNewMechanicKeys.HEAVY_STUN_LIFE, Set.of());
            if (life > 0) {
                final PlayerState ps = plugin.players().get(player);
                if (!ps.ruined(now)) {
                    ps.setLife(Math.min(ps.maxLife(), ps.life() + ps.maxLife() * life / 100.0));
                    plugin.players().display(player, ps);
                }
            }
            final int endurance = (int) Math.round(sheet.flat(TreeNewMechanicKeys.HEAVY_STUN_ENDURANCE, Set.of()));
            if (endurance > 0 && plugin.combat() != null && plugin.combat().charges() != null) {
                plugin.combat().charges().grant(player, "endurance", endurance);
            }
            final double seconds = sheet.flat(TreeNewMechanicKeys.ANCESTRAL_AFTER_HEAVY_STUN, Set.of());
            if (seconds > 0) {
                final State state = state(player.getUniqueId());
                state.ancestralNext = true;
                state.ancestralUntil = now + Math.round(seconds * 20);
            }
        }
        final double breakPercent = sheet.flat(TreeNewMechanicKeys.HEAVY_STUN_ARMOUR_BREAK, Set.of());
        if (breakPercent > 0) {
            breakArmourPercent(defender, data, breakPercent);
        }
    }

    /** 破壞敵人 percent% 的護甲（不吃稀有度的破甲倍率）。 */
    private void breakArmourPercent(final LivingEntity defender, final MonsterData data, final double percent) {
        if (plugin.ailments() == null) {
            return;
        }
        final double armour = plugin.monsters().sheet(defender, data).get(StatKeys.ARMOUR);
        final double multiplier = Math.max(0.01, plugin.ailments().config().armourBreakMultiplier(data.rarity()));
        plugin.ailments().breakArmour(defender, data, armour / multiplier, Math.min(100, percent), armour);
    }

    /** 舉盾的眩暈持續幾 tick（PoE2：8 秒）。 */
    static final long DAZE_TICKS = 160;

    /**
     * 格擋了一擊（ChargeEffects 呼叫）：舉盾中格擋時 x% 機率眩暈攻擊者；招架的話近的（近戰）給下一次遠程攻擊、
     * 遠的（投射物）給下一次近戰攻擊。
     */
    void onBlock(final Player player, final LivingEntity attacker, final StatSheet sheet, final boolean shieldRaised, final boolean parried) {
        final Debuffs debuffs = debuffs();
        if (shieldRaised && attacker != null && !(attacker instanceof Player) && debuffs != null
                && roll(sheet.flat(TreeNewMechanicKeys.DAZE_ON_SHIELD_BLOCK, Set.of()))) {
            debuffs.apply(attacker.getUniqueId(), new Debuffs.Debuff("daze", player.getUniqueId(), now() + HitEffects.scaledTicks(DAZE_TICKS,
                    sheet.increased(tw.exilecore.stats.TreeMechanicKeys.DAZE_DURATION, Set.of())), 0, false, null,
                    List.of(Modifier.more(tw.exilecore.ailment.AilmentKeys.STUN_BUILDUP_TAKEN, HitEffects.DAZE_STUN_BUILDUP, "眩暈")), 0));
        }
        // 2026-10-27 照 PoE2（塗油限定 光明壁壘）：舉盾擋下的那一下讓攻擊者被招架 x 秒（被招架的效果照天賦）
        final double parriedSeconds = sheet.flat(tw.exilecore.stats.AnointKeys.RAISE_SHIELD_PARRIED, Set.of());
        if (shieldRaised && parriedSeconds > 0 && attacker != null && !(attacker instanceof Player) && debuffs != null) {
            final long ticks = Math.round(ChargeEffects.parriedTicks(sheet) * parriedSeconds * 20 / ChargeEffects.PARRIED_TICKS);
            debuffs.apply(attacker.getUniqueId(), new Debuffs.Debuff(ChargeEffects.PARRIED, player.getUniqueId(), now() + Math.max(1, ticks),
                    ChargeEffects.parriedTaken(sheet), false, Tags.ATTACK));
        }
        if (parried) {
            onParry(player, attacker, sheet);
        }
    }

    /** 2026-10-27 照 PoE2（塗油限定 危境增長）：每層易爆增加持續混沌傷害（中毒的強度）；層數變了才重給增益。 */
    private void growingPeril(final Player player, final State state, final StatSheet sheet) {
        final double per = sheet.flat(tw.exilecore.stats.AnointKeys.VOLATILITY_CHAOS_DOT, Set.of());
        final int stacks = per > 0 ? state.volatility : 0;
        if (stacks == state.perilStacks) {
            return;
        }
        state.perilStacks = stacks;
        if (stacks <= 0) {
            service.buffs().remove(player, "tree.growing_peril");
            return;
        }
        service.buffs().apply(player, "tree.growing_peril", "危境增長", (int) (VOLATILITY_DELAY_TICKS * 10),
                List.of(Modifier.increased(StatKeys.POISON_MAGNITUDE, tw.exilecore.stats.AnointKeys.perilPoison(per, stacks), "危境增長")));
    }

    /** 招架了一擊：近的（近戰）給下一次遠程攻擊、遠的（投射物）給下一次近戰攻擊。 */
    private void onParry(final Player player, final LivingEntity attacker, final StatSheet sheet) {
        final State state = state(player.getUniqueId());
        final boolean melee = attacker == null || attacker.getLocation().distanceSquared(player.getLocation()) <= 25;
        if (melee) {
            final double ranged = sheet.flat(TreeNewMechanicKeys.PARRY_NEXT_RANGED, Set.of());
            if (ranged > 0) {
                state.parryRanged = ranged;
                service.buffs().apply(player, "tree.parry_ranged", "招架後的遠程攻擊", 200,
                        List.of(Modifier.flat(PARRY_READY, 1, "招架後的遠程攻擊")));
            }
        } else {
            final double next = sheet.flat(TreeNewMechanicKeys.PARRY_NEXT_MELEE, Set.of());
            if (next > 0) {
                state.parryMelee = next;
                service.buffs().apply(player, "tree.parry_melee", "招架後的近戰攻擊", 200,
                        List.of(Modifier.flat(PARRY_READY, 1, "招架後的近戰攻擊")));
            }
        }
    }

    /** 召喚物死了（MinionService 呼叫）：會復活的常駐召喚物被擊殺時給易爆。 */
    void minionDied(final Player owner, final SkillDefinition definition) {
        if (!definition.tags().contains(Tags.PERSISTENT)) {
            return;
        }
        final int gain = (int) Math.round(plugin.players().sheet(owner).flat(TreeNewMechanicKeys.VOLATILITY_ON_MINION_DEATH, Set.of()));
        if (gain > 0) {
            gainVolatility(owner, gain);
        }
    }

    /** 喝了生命藥劑（FlaskService 呼叫）：獲得怒氣；夥伴也回復同樣多。 */
    public void lifeFlaskUsed(final Player player, final double recovery) {
        final StatSheet sheet = plugin.players().sheet(player);
        final double rage = sheet.flat(TreeNewMechanicKeys.RAGE_ON_LIFE_FLASK, Set.of());
        if (rage > 0 && service.resources() != null) {
            service.resources().gainRage(player, rage);
        }
        if (StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_COMPANION_FLASK) && service.minions() != null) {
            service.minions().healCompanions(player, recovery);
        }
    }

    /** 偷取了生命（CombatListener 呼叫）：有天賦的話夥伴也回復同樣多（原作是夥伴也偷取；我們直接回）。 */
    public void leechedLife(final Player player, final StatSheet effective, final double amount) {
        if (amount > 0 && StatKeys.flag(effective, TreeNewMechanicKeys.FLAG_COMPANION_LEECH) && service.minions() != null) {
            service.minions().healCompanions(player, amount);
        }
    }

    /** 擊中傷害的 x% 先由夥伴承受（CombatListener 呼叫）：回傳夥伴真的吃掉多少。 */
    public double companionAbsorb(final Player player, final double amount) {
        return service.minions() == null ? 0 : service.minions().absorbForOwner(player.getUniqueId(), amount);
    }

    /** 圖騰受傷（SkillDeployables 呼叫）：補償 x% 為你的生命（4 秒內）。 */
    void totemDamaged(final Player owner, final double amount) {
        final double recoup = plugin.players().sheet(owner).flat(TreeNewMechanicKeys.TOTEM_RECOUP, Set.of());
        if (recoup > 0 && amount > 0) {
            plugin.players().get(owner).addLeech(amount * recoup / 100.0);
        }
    }

    /** 造成了元素異常（點燃、冰緩、凍結、感電）：給易爆。 */
    public void onElementalAilment(final Player player) {
        final int gain = (int) Math.round(plugin.players().sheet(player).flat(TreeNewMechanicKeys.VOLATILITY_ON_ELEMENTAL_AILMENT, Set.of()));
        if (gain > 0) {
            gainVolatility(player, gain);
        }
    }

    // ---- 切割（AilmentService 算流血機率時呼叫）----

    /** 這隻敵人身上的切割讓牠被流血的機率多多少（0.3 = 30%）。 */
    public double incisionBleedChance(final LivingEntity defender) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null) {
            return 0;
        }
        final int stacks = HitEffects.stacks(debuffs, defender.getUniqueId(), "incision.", now());
        return stacks <= 0 ? 0 : stacks * INCISION_BLEED_CHANCE * incisionEffect.getOrDefault(defender.getUniqueId(), 1.0);
    }

    /** 被流血了：切割全部拿掉。 */
    public void clearIncision(final LivingEntity defender) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null) {
            return;
        }
        final long now = now();
        for (final Debuffs.Debuff debuff : debuffs.active(defender.getUniqueId(), now)) {
            if (debuff.id().startsWith("incision.")) {
                debuffs.remove(defender.getUniqueId(), debuff.id(), debuff.source(), now);
            }
        }
        incisionEffect.remove(defender.getUniqueId());
    }

    private void addIncision(final Player player, final LivingEntity target, final StatSheet sheet, final int stacks, final long now) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null) {
            return;
        }
        final double physicalTaken = sheet.flat(TreeNewMechanicKeys.INCISION_PHYSICAL_TAKEN, Set.of()) / 100.0;
        incisionEffect.put(target.getUniqueId(), Math.max(0, 1 + sheet.increased(TreeNewMechanicKeys.INCISION_EFFECT, Set.of())));
        int have = HitEffects.stacks(debuffs, target.getUniqueId(), "incision.", now);
        for (int i = 0; i < stacks; i++) {
            final int slot = have < INCISION_MAX ? have++ : (i % INCISION_MAX);
            debuffs.apply(target.getUniqueId(), new Debuffs.Debuff("incision." + slot, player.getUniqueId(), now + INCISION_TICKS,
                    physicalTaken, false, DamageType.PHYSICAL.tag()));
        }
        // 時間一起刷新
        for (final Debuffs.Debuff debuff : debuffs.active(target.getUniqueId(), now)) {
            if (debuff.id().startsWith("incision.") && debuff.untilTick() < now + INCISION_TICKS) {
                debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(debuff.id(), debuff.source(), now + INCISION_TICKS, debuff.taken(),
                        false, debuff.tag()));
            }
        }
    }

    // =====================================================================
    // 事件
    // =====================================================================

    /** 擊中之前：記下目標有沒有被釘住。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeHit(final HitEvent event) {
        final Debuffs debuffs = debuffs();
        if (debuffs != null && event.credit() != null && debuffs.has(event.defender().getUniqueId(), "pin", now())) {
            pinnedBefore.add(event.defender().getUniqueId());
        } else {
            pinnedBefore.remove(event.defender().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        final LivingEntity defender = event.defender();
        final Player player = event.credit();
        if (player == null || !result.hit() || result.blocked() || defender instanceof Player || event.skill() == null
                || plugin.monsters() == null) {
            return;
        }
        final MonsterData monster = plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return;
        }
        final long now = now();
        final StatSheet owner = plugin.players().sheet(player);
        final boolean byMinion = event.attacker() != player;
        final Debuffs debuffs = debuffs();
        if (byMinion) {
            if (owner.flat(TreeNewMechanicKeys.COMPANION_ONSLAUGHT, Set.of()) > 0) {
                lastMinionHit.put(defender.getUniqueId(), event.attacker().getUniqueId());
            }
            // 召喚物擊中：凋零
            if (debuffs != null && roll(owner.flat(TreeNewMechanicKeys.MINION_WITHER_CHANCE, Set.of()))) {
                HitEffects.addWithered(debuffs, defender.getUniqueId(), player.getUniqueId(), now, 1, HitEffects.WITHERED_TICKS);
            }
            witheredWeaken(player, defender, owner, now);
            return;
        }
        final SkillProfile skill = event.skill();
        state(player.getUniqueId()).lastSkill = skill;
        final StatSheet effective = skill.effective(owner);
        final Set<String> tags = skill.tags();
        // 切割
        if (skill.isAttack()) {
            int stacks = roll(effective.flat(TreeNewMechanicKeys.INCISION_CHANCE, tags)) ? 1 : 0;
            if (roll(effective.flat(TreeNewMechanicKeys.INCISION_CHANCE_TEN, tags))) {
                stacks += 10;
            }
            if (stacks > 0) {
                addIncision(player, defender, effective, Math.min(INCISION_MAX, stacks), now);
            }
        }
        // 加劇流血：暴擊時、存在超過 x 秒的
        if (plugin.ailments() != null && plugin.ailments().has(defender, tw.exilecore.ailment.AilmentType.BLEED)) {
            final long since = bleedingSince.computeIfAbsent(defender.getUniqueId(), id -> now);
            if (result.crit() && roll(effective.flat(TreeNewMechanicKeys.AGGRAVATE_ON_CRIT, tags))) {
                plugin.ailments().aggravateBleeds(defender, player.getUniqueId());
            }
            final double older = effective.flat(TreeNewMechanicKeys.AGGRAVATE_OLDER_THAN, tags);
            if (older > 0 && skill.isAttack() && (now - since) * 50 >= older) {
                plugin.ailments().aggravateBleeds(defender, player.getUniqueId());
            }
        } else {
            bleedingSince.remove(defender.getUniqueId());
        }
        // 物理法術的暴擊累積釘住
        if (result.crit() && skill.isSpell() && StatKeys.flag(effective, TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN) && plugin.ailments() != null
                && debuffs != null) {
            final double physical = result.outgoing().get(DamageType.PHYSICAL);
            if (physical > 0 && plugin.ailments().pinBuildup(defender, monster, plugin.monsters().sheet(defender, monster), physical,
                    Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeKeys.PIN_BUILDUP, tags)))) {
                final long ticks = Math.max(1, Math.round(plugin.ailments().config().pin().duration(false) * 20));
                debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("pin", player.getUniqueId(), now + ticks, 0, false, null, List.of(), 1.0));
            }
        }
        // 剛被釘住：破甲；記下來（解除後癱瘓）
        if (debuffs != null && !pinnedBefore.remove(defender.getUniqueId()) && debuffs.has(defender.getUniqueId(), "pin", now)) {
            final double breakPercent = owner.flat(TreeNewMechanicKeys.PIN_ARMOUR_BREAK, Set.of());
            if (breakPercent > 0) {
                breakArmourPercent(defender, monster, breakPercent);
            }
            if (owner.flat(TreeNewMechanicKeys.PIN_MAIM_AFTER, Set.of()) > 0) {
                pinnedBy.put(defender.getUniqueId(), player.getUniqueId());
            }
            if (service.treeLeftovers() != null) {
                service.treeLeftovers().immobilised(player, defender); // 2026-10-27 照 PoE2 天賦樹：禁錮（釘住）時恫嚇
            }
        }
        witheredWeaken(player, defender, owner, now);
    }

    /** 被凋零的敵人造成的傷害減少（每層 x%）：有凋零就刷新一條減益。 */
    private void witheredWeaken(final Player player, final LivingEntity defender, final StatSheet owner, final long now) {
        final double weaken = owner.flat(TreeNewMechanicKeys.WITHERED_WEAKEN, Set.of());
        final Debuffs debuffs = debuffs();
        if (weaken <= 0 || debuffs == null) {
            return;
        }
        final int stacks = HitEffects.stacks(debuffs, defender.getUniqueId(), "withered.", now);
        if (stacks > 0) {
            debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("tree.withered_weaken", player.getUniqueId(), now + HitEffects.WITHERED_TICKS,
                    0, false, null, List.of(Modifier.more(StatKeys.DAMAGE, -Math.min(90, weaken * stacks) / 100.0, "凋零")), 0));
        }
    }

    /** 擊殺：易爆、用帶標籤的技能（斧）擊殺時疾速。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        final UUID minionKiller = lastMinionHit.remove(victim.getUniqueId());
        if (minionKiller != null && service.minions() != null) {
            companionKill(minionKiller);
        }
        final Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player || plugin.monsters() == null || plugin.monsters().data(victim).isEmpty()) {
            return;
        }
        incisionEffect.remove(victim.getUniqueId());
        bleedingSince.remove(victim.getUniqueId());
        pinnedBy.remove(victim.getUniqueId());
        final StatSheet sheet = plugin.players().sheet(killer);
        if (roll(sheet.flat(TreeNewMechanicKeys.VOLATILITY_ON_KILL, Set.of()))) {
            gainVolatility(killer, 1);
        }
        final State state = states.get(killer.getUniqueId());
        final SkillProfile last = state == null ? null : state.lastSkill;
        if (last != null && plugin.combat() != null && plugin.combat().killEffects() != null) {
            final StatSheet effective = last.effective(sheet);
            if (roll(effective.flat(TreeNewMechanicKeys.ONSLAUGHT_ON_KILL_TAGGED, last.tags()))) {
                plugin.combat().killEffects().grantOnslaught(killer);
            }
            // 強化攻擊擊殺的敵人 x% 機率爆炸，造成牠最大生命十分之一的火焰傷害
            if (last.tags().contains(tw.exilecore.stats.TreeMechanicKeys.EMPOWERED)
                    && roll(effective.flat(TreeNewMechanicKeys.EMPOWERED_KILL_EXPLODE, last.tags()))) {
                final MonsterData data = plugin.monsters().data(victim).orElse(null);
                final double life = data == null ? 0 : plugin.monsters().sheet(victim, data).get(StatKeys.LIFE_MAX);
                if (life > 0) {
                    plugin.combat().killEffects().explodeAt(killer, victim.getLocation().add(0, 0.5, 0), life * 0.1, DamageType.FIRE);
                }
            }
        }
    }

    /** 夥伴的疾速：增加 20% 攻擊、施法速度與 10% 移動速度，4 秒（PoE2 Onslaught）。 */
    static final List<Modifier> COMPANION_ONSLAUGHT = List.of(Modifier.increased(StatKeys.ATTACK_SPEED, 0.2, "疾速"),
            Modifier.increased(StatKeys.CAST_SPEED, 0.2, "疾速"), Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.1, "疾速"));

    /** 召喚物打死了敵人：是夥伴的話照主人的天賦擲疾速。 */
    private void companionKill(final UUID minion) {
        final UUID ownerId = service.minions().companionOwner(minion);
        final Player owner = ownerId == null ? null : plugin.getServer().getPlayer(ownerId);
        if (owner != null && roll(plugin.players().sheet(owner).flat(TreeNewMechanicKeys.COMPANION_ONSLAUGHT, Set.of()))) {
            service.minions().buffMinion(minion, COMPANION_ONSLAUGHT, 80);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    // =====================================================================
    // 每 PERIOD tick
    // =====================================================================

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState ps = plugin.players().find(player.getUniqueId()).orElse(null);
            if (ps == null || !ps.hasCharacter() || ps.isDead()) {
                continue;
            }
            final State state = states.get(player.getUniqueId());
            final StatSheet sheet = plugin.players().sheet(player);
            if (state != null) {
                if (state.archon != null && now >= state.archonUntil) {
                    loseArchon(player, state, sheet, now); // 時間到：進入恢復期
                }
                if (state.volatility > 0 && now >= state.volatilityExplodeAt) {
                    explodeVolatility(player, state, now);
                }
                if (!state.puppet.isEmpty() && state.puppet.stream().anyMatch(until -> until <= now)) {
                    refreshPuppet(player, state, sheet, now);
                }
                if (state.ancestralNext && now > state.ancestralUntil) {
                    state.ancestralNext = false;
                }
                growingPeril(player, state, sheet);
            }
            sprint(player, sheet);
            esRecharge(player, ps, sheet, now);
            if (StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_ARCANE_SURGE_ALWAYS)) {
                // 2026-10-27 效能：每次都續（效果一樣只延長時間，不會重算屬性表）；這次之前等它過期才補，每秒「過期→重算→補上→重算」兩次
                grantArcaneSurge(player, (int) (PERIOD * 4));
            }
            enemyAuras(player, sheet, now);
            // 2026-10-27：夥伴傷害改在重算屬性表時直接算（companionDamageModifiers），不再每 10 tick 看一次
        }
        // 2026-10-27：沒死就消失（區塊卸載、被清掉）的敵人沒有死亡事件，每 10 秒清一次；這次之前只在死亡時清，會一直累積
        if (now - prunedAt >= 200) {
            prunedAt = now;
            final java.util.function.Predicate<UUID> gone = id -> {
                final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
                return entity == null || !entity.isValid();
            };
            incisionEffect.keySet().removeIf(gone);
            bleedingSince.keySet().removeIf(gone);
            lastMinionHit.keySet().removeIf(gone);
        }
        // 解除釘住後癱瘓
        if (!pinnedBy.isEmpty() && debuffs() != null) {
            final Debuffs debuffs = debuffs();
            pinnedBy.entrySet().removeIf(entry -> {
                if (debuffs.has(entry.getKey(), "pin", now)) {
                    return false;
                }
                final Player owner = plugin.getServer().getPlayer(entry.getValue());
                if (owner != null) {
                    final double seconds = plugin.players().sheet(owner).flat(TreeNewMechanicKeys.PIN_MAIM_AFTER, Set.of());
                    if (seconds > 0 && plugin.getServer().getEntity(entry.getKey()) instanceof LivingEntity living && living.isValid()) {
                        debuffs.apply(entry.getKey(), new Debuffs.Debuff("maim", owner.getUniqueId(), now + Math.round(seconds * 20), 0, false, null,
                                List.of(Modifier.increased(StatKeys.EVASION, -HitEffects.MAIM_EVASION, "癱瘓")), HitEffects.MAIM_SLOW));
                    }
                }
                return true;
            });
        }
    }

    /**
     * 能量護盾開始回充（受傷後過了延遲、護盾開始往上加）：有天賦的話獲得元素統治者。
     * 用取樣判斷：護盾沒滿、比上次多、最近 1 秒沒受傷 → 這一段回充的開始（直到再受傷或補滿）。
     */
    private void esRecharge(final Player player, final PlayerState ps, final StatSheet sheet, final long now) {
        final State state = state(player.getUniqueId());
        final double es = ps.energyShield();
        final boolean rising = state.lastEs >= 0 && es > state.lastEs + 1e-6 && es < ps.maxEnergyShield() && now - ps.lastDamageTick() >= 20;
        if (rising && !state.recharging && StatKeys.flag(sheet, TreeNewMechanicKeys.ARCHON_ELEMENTAL_ON_RECHARGE)) {
            gainArchon(player, Archon.ELEMENTAL);
        }
        if (rising && !state.recharging) {
            ps.markRecent(tw.exilecore.stats.TreeLastKeys.EVENT_ES_RECHARGE, now); // 2026-10-27 照 PoE2 天賦樹：「護盾在過去 2 秒內開始回充」
        }
        if (rising) {
            state.recharging = true;
        } else if (es >= ps.maxEnergyShield() || now - ps.lastDamageTick() < 20) {
            state.recharging = false;
        }
        state.lastEs = es;
    }

    /** 衝刺：翻滾之後一直按著翻滾鍵、在地上移動。狀態變了才重算屬性表。 */
    private void sprint(final Player player, final StatSheet sheet) {
        final boolean wants = sheet.increased(TreeNewMechanicKeys.SPRINT_MOVEMENT, Set.of()) != 0;
        final boolean sprinting = wants && plugin.combat() != null && plugin.combat().dodge() != null
                && plugin.combat().dodge().sprinting(player);
        final State state = state(player.getUniqueId());
        final double speed = sheet.increased(TreeNewMechanicKeys.SPRINT_MOVEMENT, Set.of());
        if (state.sprinting != sprinting || sprinting && Math.abs(state.sprintSpeed - speed) > 1e-9) {
            state.sprinting = sprinting;
            state.sprintSpeed = speed;
            plugin.players().markDirty(player);
        }
    }

    /**
     * 「夥伴傷害的增加與減少也套用在你身上」：加總帶夥伴標籤的增加傷害，給你同樣的增加傷害（PlayerStateService 重算屬性表的最後呼叫）。
     * 2026-10-27：這次之前是每 10 tick 看一次、當成暫時增益給的——角色面板把它列成增益、網頁計算器完全沒算；
     * 它只看屬性表（不用召喚出夥伴），所以改成屬性表的衍生值，面板、網頁、戰鬥都一樣。
     */
    public static List<Modifier> companionDamageModifiers(final StatSheet sheet) {
        if (!StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_COMPANION_DAMAGE_TO_YOU)) {
            return List.of();
        }
        double sum = 0;
        for (final Modifier modifier : sheet.modifiersOf(StatKeys.DAMAGE)) {
            if (modifier.type() == ModType.INCREASED && modifier.tags().contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION)) {
                sum += modifier.value();
            }
        }
        return Math.abs(sum) < 1e-9 ? List.of() : List.of(Modifier.increased(StatKeys.DAMAGE, sum, "夥伴傷害"));
    }

    /** 屬性表的來源：衝刺中的移動速度（夥伴傷害見 {@link #companionDamageModifiers}）。 */
    List<Modifier> modifiers(final Player player) {
        final State state = states.get(player.getUniqueId());
        if (state == null || !state.sprinting) {
            return List.of();
        }
        return List.of(Modifier.increased(StatKeys.MOVEMENT_SPEED, state.sprintSpeed, "衝刺"));
    }

    /** 存在範圍內的敵人（生命回復、冷卻回復）、圖騰附近的敵人受到的傷害、被詛咒的敵人阻礙、標記附近的敵人致盲。 */
    private void enemyAuras(final Player player, final StatSheet sheet, final long now) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null || plugin.monsters() == null) {
            return;
        }
        final double regen = sheet.increased(TreeNewMechanicKeys.PRESENCE_ENEMY_REGEN, Set.of());
        final double cooldown = sheet.increased(TreeNewMechanicKeys.PRESENCE_ENEMY_COOLDOWN, Set.of());
        final double hinder = sheet.flat(TreeNewMechanicKeys.CURSE_HINDER, Set.of());
        final boolean markBlind = StatKeys.flag(sheet, TreeNewMechanicKeys.FLAG_MARK_BLIND_NEARBY);
        final double totemTaken = sheet.flat(TreeNewMechanicKeys.TOTEM_PRESENCE_TAKEN, Set.of());
        if (regen == 0 && cooldown == 0 && hinder <= 0 && !markBlind && totemTaken <= 0) {
            return;
        }
        final double radius = tw.exilecore.combat.Presence.radius(sheet);
        final long linger = now + PERIOD * 3;
        final UUID me = player.getUniqueId();
        if (regen != 0 || cooldown != 0) {
            for (final LivingEntity target : monstersAround(player, player.getLocation(), radius)) {
                final List<Modifier> mods = new ArrayList<>();
                if (regen != 0) {
                    // 怪物的生命回復是詞綴給的「每秒回復 x% 最大生命」（FLAT）：減少的部分直接扣掉
                    final MonsterData data = plugin.monsters().data(target).orElse(null);
                    final double own = data == null ? 0 : plugin.monsters().sheet(target, data).flat(StatKeys.MONSTER_REGEN, Set.of());
                    if (own > 0) {
                        mods.add(Modifier.flat(StatKeys.MONSTER_REGEN, own * Math.max(-1, regen), "存在範圍"));
                    }
                }
                if (cooldown != 0) {
                    // 冷卻回復速度減少 x% = 冷卻時間變成 1 ÷ (1 − x) 倍
                    mods.add(Modifier.increased(StatKeys.SKILL_COOLDOWN, 1 / Math.max(0.1, 1 + cooldown) - 1, "存在範圍"));
                }
                if (!mods.isEmpty()) {
                    debuffs.apply(target.getUniqueId(), new Debuffs.Debuff("tree.presence." + me, me, linger, 0, false, null, mods, 0));
                }
            }
        }
        if (hinder > 0 || markBlind) {
            for (final LivingEntity target : monstersAround(player, player.getLocation(), 24)) {
                for (final Debuffs.Debuff debuff : debuffs.active(target.getUniqueId(), now)) {
                    if (!me.equals(debuff.source())) {
                        continue;
                    }
                    if (hinder > 0 && debuff.id().contains("_curse")) {
                        debuffs.apply(target.getUniqueId(), new Debuffs.Debuff("hinder", me, linger, 0, false, null, List.of(),
                                Math.min(0.9, hinder / 100.0)));
                    }
                    if (markBlind && debuffs.isMark(debuff.id())) {
                        for (final LivingEntity near : monstersAround(player, target.getLocation(), 4)) {
                            if (near != target) {
                                debuffs.apply(near.getUniqueId(), new Debuffs.Debuff("blind", me, linger, 0, false, null,
                                        HitEffects.blindModifiers(0), 0));
                            }
                        }
                    }
                }
            }
        }
        if (totemTaken > 0) {
            final List<Location> totems = service.deployables().totemSpots(me);
            for (final Location at : totems) {
                for (final LivingEntity target : monstersAround(player, at, radius)) {
                    final String id = "tree.totem." + me + "." + Math.floorMod(at.hashCode(), 64);
                    debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(id, me, linger, totemTaken / 100.0, false));
                }
            }
        }
    }

    private List<LivingEntity> monstersAround(final Player player, final Location at, final double radius) {
        final List<LivingEntity> list = new ArrayList<>();
        if (at.getWorld() == null || !at.getWorld().equals(player.getWorld())) {
            return list;
        }
        final double squared = radius * radius;
        for (final LivingEntity target : at.getWorld().getNearbyLivingEntities(at, radius)) {
            if (target instanceof Player || target.isDead() || !target.isValid() || plugin.monsters().data(target).isEmpty()
                    || target.getLocation().distanceSquared(at) > squared
                    || (plugin.training() != null && !plugin.training().canTarget(target, player))) {
                continue;
            }
            list.add(target);
        }
        return list;
    }

    // =====================================================================
    // 小工具（別的類別一行呼叫）
    // =====================================================================

    /** 戰旗另外給的增益（全元素抗性）。 */
    static List<Modifier> bannerExtras(final StatSheet effective, final Set<String> tags, final String name) {
        if (!tags.contains(tw.exilecore.stats.TreeMechanicKeys.BANNER)) {
            return List.of();
        }
        final double resist = effective.flat(TreeNewMechanicKeys.BANNER_RESIST, tags);
        if (resist == 0) {
            return List.of();
        }
        return List.of(Modifier.flat(StatKeys.RESIST_FIRE, resist, name), Modifier.flat(StatKeys.RESIST_COLD, resist, name),
                Modifier.flat(StatKeys.RESIST_LIGHTNING, resist, name));
    }

    /** 猛擊多幾次餘震（照機率；你親自使用的）。 */
    static int extraAftershocks(final StatSheet sheet, final Set<String> tags, final RandomGenerator random) {
        final double chance = sheet.flat(TreeNewMechanicKeys.AFTERSHOCK_CHANCE, tags);
        return chance <= 0 ? 0 : surpassing(chance, random.nextDouble());
    }

    /** 餘震的半徑倍率（餘震範圍開根號）。 */
    static double aftershockRadius(final StatSheet sheet, final Set<String> tags) {
        return Math.sqrt(Math.max(0.1, 1 + sheet.increased(TreeNewMechanicKeys.AFTERSHOCK_AREA, tags)));
    }
}
