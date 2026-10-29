package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * data/skills.yml：技能等級需求、法術傷害成長、所有技能（含四種普攻）；
 * data/supports.yml：輔助寶石與未切割輔助寶石的階級。純資料，不依賴 Bukkit。
 */
public final class SkillConfig {

    public static final String FILE = "skills.yml";
    public static final String SUPPORTS_FILE = "supports.yml";
    /** 2026-10-27 照 PoE2：血脈輔助寶石（跟 supports.yml 分開寫，讀完 supports.yml 再讀；代號不能重複）。 */
    public static final String LINEAGE_FILE = "supports_lineage.yml";

    /** 外觀代號（粒子與音效寫在 SkillVisuals）。 */
    public static final Set<String> VISUALS = Set.of("slash", "heavy_strike", "cleave", "slam", "leap",
            "arrow", "burning_arrow", "wand_bolt", "staff_bolt", "fireball", "frost_nova", "spark", "arc",
            "crossbow_bolt", "explosive_bolt", "permafrost_bolt", "staff_sweep", "tempest", "ice_cone",
            "spear_thrust", "lightning_spear", "whirl", "chaos_bolt", "blight_nova",
            // 第三部分：位移、延遲落下、地面效果、榴彈、印記…
            "dash", "whirling_blades", "flame_dash", "frostblink", "blink_arrow", "rain_of_arrows", "lightning_arrow",
            "tornado_shot", "ice_shot", "earthquake", "comet", "blizzard", "flame_wall", "explosive_grenade", "gas_grenade",
            "rally", "desecrate", "rapid_fire", "storm_wave", "killing_palm", "whirlwind_lance", "lunge", "hunters_mark", "void_rift",
            // 異常狀態的技能（2026-10）：血刺、蛇牙擊、碎脊擊、蝕毒箭
            "puncture", "viper_strike", "boneshatter", "caustic_arrow",
            // 第三部分（二）：圖騰、陷阱、鐘、骸爆
            "ancestral_totem", "flame_totem", "shockwave_totem", "fire_trap", "lightning_trap", "bear_trap", "tempest_bell", "detonate_dead",
            // 第三部分（三）：召喚物
            "raise_zombie", "summon_skeletons", "raging_spirits", "flame_golem",
            // 第三部分（四）：光環、捷、戰吼
            "aura_cold", "aura_fire", "aura_lightning", "aura_armour", "aura_evasion", "aura_es", "aura_haste", "aura_purity",
            "aura_life", "aura_mana", "aura_precision", "herald_ash", "herald_thunder", "herald_ice",
            "enduring_cry", "rallying_cry", "intimidating_cry",
            // 2026-10：瞬影擊、聚星戰吼
            "flicker", "star_cry");

    public static final String DEFAULT_MELEE = "default_melee";
    public static final String DEFAULT_BOW = "default_bow";
    public static final String DEFAULT_WAND = "default_wand";
    public static final String DEFAULT_STAFF = "default_staff";
    public static final String DEFAULT_CROSSBOW = "default_crossbow";
    public static final String DEFAULT_QUARTERSTAFF = "default_quarterstaff";
    public static final String DEFAULT_SPEAR = "default_spear";
    /** 一定要有的普攻。 */
    public static final List<String> DEFAULT_ATTACKS = List.of(DEFAULT_MELEE, DEFAULT_BOW, DEFAULT_WAND, DEFAULT_STAFF);
    /**
     * 新武器的普攻（弩、長棍、矛）：自己改過的 skills.yml（新版放在 .new）沒有也能啟動，
     * 弩改用射箭、長棍與矛改用一般近戰，啟用時提醒（{@link #missingDefaults()}）。
     */
    public static final List<String> OPTIONAL_DEFAULT_ATTACKS = List.of(DEFAULT_CROSSBOW, DEFAULT_QUARTERSTAFF, DEFAULT_SPEAR);
    /** skills.yml 沒寫 extra_level_damage 時：21 級以上每級多 13% 法術基礎傷害（2026-10：10 → 13，跟出貨的 skills.yml 一樣）。 */
    public static final double DEFAULT_EXTRA_LEVEL_DAMAGE = 13;

    private int[] requirements = new int[SkillLoadout.MAX_LEVEL];
    /** 2026-10-26：弩技能的彈匣大小（skills.yml 的 clip）。 */
    private Map<String, Scaled> clips = Map.of();
    /** 2026-10-26：逐級的技能修飾器（level_modifiers：凍結累積、穿透、破甲這些照 PoE2 每一級不同的）。 */
    private Map<String, java.util.List<ScaledMod>> levelModifiers = Map.of();
    /** 2026-10-26：落地後留在地上的彈（PoE2 的伏霆彈要被引爆、寒凝彈的冰晶地雷）。 */
    private Map<String, LodgeSpec> lodges = Map.of();
    /** 2026-10-26：一次把彈匣全部射出去、落在一片範圍裡（PoE2 的 Hailstorm Rounds）。 */
    private Map<String, RainSpec> rains = Map.of();
    /** 2026-10-28 照 PoE2 Lightning Rod：落地後插在地上、閃電雷束可以連鎖過去的箭（skills.yml 的 rod）。 */
    private Map<String, RodSpec> rods = Map.of();

    /**
     * 2026-10-28 照 PoE2 Lightning Rod（雷樁矢）：插在地上的箭（{@link LightningRods}）。
     *
     * @param duration 留幾秒（吃技能持續時間）
     * @param max      同時最多幾支（多的先拿掉最舊的）
     * @param chains   被連鎖幾次之後消失
     * @param charge   被連鎖之後充能幾秒才炸（雷鳴箭的雷束另外看 lightning_rod.burst_delay）
     */
    public record RodSpec(double duration, int max, int chains, double charge) {
    }

    public RodSpec rod(final String skillId) {
        return rods.get(skillId);
    }

    /** 2026-10-28 照 PoE2 Siege Ballista：弩彈落地打一小圈之後再炸開一圈（skills.yml 的 cascade）。 */
    private Map<String, CascadeSpec> cascades = Map.of();

    /**
     * 2026-10-28 照 PoE2 Siege Ballista（衝霄弩台）：每一發弩彈落地先打一小圈（技能本身的 impact 半徑、效益、轉換），
     * 過 after 秒再炸開一圈（這裡的半徑、效益、轉換；輔助、品質照樣吃）。
     *
     * @param radius        爆炸半徑（格，吃範圍加成）
     * @param after         落地後幾秒炸
     * @param effectiveness 爆炸的傷害效益（逐級）
     * @param conversion    爆炸的物理轉換（取代技能本身的 convert）
     */
    public record CascadeSpec(double radius, double after, Scaled effectiveness, Map<tw.exilecore.stats.DamageType, Double> conversion) {
    }

    public CascadeSpec cascade(final String skillId) {
        return cascades.get(skillId);
    }

    /**
     * 留在地上的彈（skills.yml 的 lodge）。
     *
     * @param skill     炸開時用的隱藏技能（nova）
     * @param count     一次落下幾顆（散在準心周圍 scatter 格內）
     * @param duration  留幾秒（沒被引爆就消失）
     * @param max       同時最多幾顆（多的先消失最舊的）
     * @param proximity true = 地雷：武裝之後敵人走進 trigger 格內就炸；false = 要被你別的技能打到（引爆）才炸
     * @param arm       放下後幾秒才武裝（地雷）
     * @param rampMore  武裝後越久越痛：最多更多 x%（rampSeconds 秒到滿）
     */
    public record LodgeSpec(String skill, int count, double scatter, double duration, int max, boolean proximity, double arm,
                            double trigger, double rampMore, double rampSeconds, boolean expire) {
        /** 2026-10-26：expire = 時間到自己引爆（PoE2 瘴苞雨的 15 秒引爆）；這次之前時間到就消失。 */
        public LodgeSpec(final String skill, final int count, final double scatter, final double duration, final int max, final boolean proximity,
                         final double arm, final double trigger, final double rampMore, final double rampSeconds) {
            this(skill, count, scatter, duration, max, proximity, arm, trigger, rampMore, rampSeconds, false);
        }
    }

    /**
     * 一次射出存著的全部彈（skills.yml 的 rain，PoE2 的 Hailstorm Rounds）：平常每 interval 秒存一發、最多 store（逐級）發；
     * 放的時候全部落在準心 area 格內，每一發半徑 radius，seconds 秒內陸續落下。
     */
    public record RainSpec(double area, double radius, double seconds, Scaled store, double interval) {
    }

    /** 2026-10-26：放技能要消耗的戰星（PoE2 的英靈圖騰：3 顆磐星）。 */
    public record ChargeCost(String charge, int count) {
    }

    private Map<String, ChargeCost> chargeCosts = Map.of();

    public ChargeCost chargeCost(final String skillId) {
        return chargeCosts.get(skillId);
    }

    public java.util.List<ScaledMod> levelModifiers(final String skillId) {
        return levelModifiers.getOrDefault(skillId, java.util.List.of());
    }

    public LodgeSpec lodge(final String skillId) {
        return lodges.get(skillId);
    }

    public RainSpec rain(final String skillId) {
        return rains.get(skillId);
    }
    /** 2026-10-26：冷卻可以存幾次（skills.yml 的 cooldown_uses）。 */
    private Map<String, Integer> cooldownUses = Map.of();
    /** 2026-10-26：技能等級 → 怪物等級多少起會掉（也是沒寫逐級表的法術用的成長曲線）；跟需求等級分開。 */
    private int[] dropLevels = new int[SkillLoadout.MAX_LEVEL];
    private NavigableMap<Integer, Double> growth = new TreeMap<>(Map.of(1, 1.0));
    /** 超過 20 級（裝備的 +技能等級）每多一級，法術基礎傷害的倍率（skills.yml 的 extra_level_damage，預設 13%）。 */
    private double extraLevelGrowth = 1.0 + DEFAULT_EXTRA_LEVEL_DAMAGE / 100.0;
    /** 2026-10：召喚物超過 20 級每多一級，生命與傷害的倍率（skills.yml 的 minion_extra_level_damage，預設 5%）。 */
    public static final double DEFAULT_MINION_EXTRA_LEVEL_DAMAGE = 5;
    private double minionExtraLevelGrowth = 1.0 + DEFAULT_MINION_EXTRA_LEVEL_DAMAGE / 100.0;
    private Map<String, SkillDefinition> skills = Map.of();
    /** 技能的連段、榮耀、元素灌注（2026-10 PoE2）。 */
    private Map<String, ResourceSpec> resources = Map.of();
    /** 指令型召喚物技能（2026-10）：技能代號 → 聽命的召喚物技能（"any" = 全部）。 */
    private Map<String, java.util.List<String>> commands = Map.of();
    /** 2026-10：照觸發它的技能出傷害的隱藏技能（parent_damage：餘震這類＝原技能一下的幾成）。 */
    private Map<String, Double> parentDamage = Map.of();
    /** 2026-10-26 照 PoE2：傷害照敵人最大生命的隱藏技能（life_damage：焚亡爆 10%、寒殞片 10%、碎骨爆 25%）。 */
    private Map<String, Double> lifeDamage = Map.of();
    /** 坐騎技能（2026-10）：技能代號 → 坐騎；要騎著才能放的技能。 */
    private Map<String, Mounts.Spec> mountSpecs = Map.of();
    /** 連珠（2026-10）：技能代號 → 「下一次弓／矛的投射物攻擊重複」的設定（skills.yml 的 barrage 區塊）。 */
    private Map<String, BarrageSpec> barrages = Map.of();
    /** 蓄力放開（2026-10，PoE2 的 Snipe、Perfect Strike、Flameblast）：技能代號 → skills.yml 的 charge 區塊。 */
    private Map<String, ChargeSpec> charges = Map.of();
    /** 專屬機制（2026-10，PoE2）：技能代號 → whirl（旋矛擊的旋風）、lance（霆落矛插地放電）、ember（星火連珠懸浮）。 */
    private Map<String, String> signatures = Map.of();
    /** 2026-10-28 照 PoE2 melee_conditional_step_distance：技能代號 → 近戰條件位移（skills.yml 的 step；{@link MeleeStep}）。 */
    private Map<String, MeleeStep.Spec> steps = Map.of();
    /** signature 可以寫的值。 */
    static final java.util.Set<String> SIGNATURES = union(java.util.Set.of("whirl", "lance", "ember", "bolt_spear", "hexblast", "sunder", // 2026-10-15：bolt_spear（擲雷槍）；2026-10-26：hexblast（蝕咒裂）、sunder（稜光裂）
                    Twisters.SIGNATURE), // 2026-10-28 照 PoE2：旋風矛的旋風
            union(OriginalSkills.SIGNATURES, SpearMechanics.SIGNATURES)); // 2026-10-26：照 PoE2 原作機制的（OriginalSkills）；2026-10-27：擲矛、炎爆矛（SpearMechanics）

    private static java.util.Set<String> union(final java.util.Set<String> a, final java.util.Set<String> b) {
        final java.util.Set<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return java.util.Set.copyOf(all);
    }
    /** 主要寶石（2026-10，照 PoE2 的 Meta Gem）：技能代號 → skills.yml 的 meta 區塊（{@link MetaSpec}）。 */
    private Map<String, MetaSpec> metas = Map.of();
    /** 龍捲風（2026-10，照 PoE2 的龍捲射擊）：技能代號 → skills.yml 的 tornado 區塊。 */
    private Map<String, TornadoSpec> tornados = Map.of();
    /** 異質技能（variant_of）→ 原本的技能（2026-10：同一系列的技能面板上只能放一個）。 */
    private Map<String, String> variants = Map.of();
    /** skills.yml 裡沒有的新武器普攻（自己改過的檔案）。 */
    private List<String> missingDefaults = List.of();
    private Map<String, SupportDefinition> supports = Map.of();
    /** 2026-10-27：血脈輔助的掉落設定（supports_lineage.yml 的 drops）。 */
    private LineageRules.Drops lineageDrops = LineageRules.Drops.DEFAULT;
    private Map<String, Integer> retiredSupports = Map.of();
    /** 2026-10-26：併入同系列最高階的輔助（supports.yml 的 merged：舊代號 → 併入的輔助代號；舊寶石、面板上掛著的直接換過去）。 */
    private Map<String, String> mergedSupports = Map.of();
    /** 2026-10-26：併入的輔助的舊名稱（通知用）。 */
    private Map<String, String> mergedSupportNames = Map.of();
    /** 2026-10-26：拿掉的技能（skills.yml 的 retired：代號 → 名稱；舊寶石換成未切割技能寶石時的通知用）。 */
    private Map<String, String> retiredSkills = Map.of();
    /** 2026-10-26：併入別的技能的技能（skills.yml 的 merged：舊代號 → 併入的技能代號；舊寶石直接換成那個技能，等級、品質、輔助保留）。 */
    private Map<String, String> mergedSkills = Map.of();
    /** 各階級的未切割輔助寶石從幾級的怪物開始掉（索引 0 = 階級 1）。 */
    private int[] supportTierLevels = {1, 15, 35};
    /**
     * 掉落的未切割技能寶石等級的權重：索引 0 = 最高等級、1 = 低 1 級、2 = 低 2 級…（skills.yml 的 uncut_level_weights）。
     * 2026-10 從 60/30/10 改成 20/40/40：T14 以上一張圖掉好幾顆 20 級，太多了。
     */
    public static final double[] DEFAULT_UNCUT_LEVEL_WEIGHTS = {20, 40, 40};
    private double[] uncutLevelWeights = DEFAULT_UNCUT_LEVEL_WEIGHTS.clone();
    /**
     * 掉落的未切割技能寶石某些等級只留幾成（2026-10，skills.yml 的 uncut_level_scale：等級 → 0～1）。擲到這個等級時照這個機率留下，
     * 沒留下就整顆不掉（不會變成低一級的）——所以那個等級真的變少，其他等級的掉率不受影響。沒寫的等級是 1。
     */
    private java.util.Map<Integer, Double> uncutLevelScale = java.util.Map.of();
    /** 同一種輔助一個角色只能用一次（PoE2 的規則；supports.yml 的 once_per_character，預設關：不同技能可以各掛一顆）。 */
    private boolean supportOncePerCharacter;
    /** data/boss_skills.yml：Boss 招式（檔案不存在時是空的）。 */
    private final BossSkillConfig bossSkills = new BossSkillConfig();

    /** 2026-10-27：所有技能的魔力消耗再乘這個（skills.yml 的 mana_cost_scale；SkillBuild 算消耗時乘上）。 */
    private static volatile double manaCostScale = 1.0;

    public static double manaCostScale() {
        return manaCostScale;
    }

    /** 2026-10-29：玩家攻擊、施法速度的基礎倍率（skills.yml 的 player_speed_scale）。 */
    private static volatile double playerSpeedScale = 2.0;
    /** 2026-10-29：召喚物移動速度的基礎倍率（skills.yml 的 minion_move_scale）。 */
    private static volatile double minionMoveScale = 2.0;

    public static double playerSpeedScale() {
        return playerSpeedScale;
    }

    public static double minionMoveScale() {
        return minionMoveScale;
    }

    /** 2026-10-29：召喚物攻擊、施法速度的基礎倍率（skills.yml 的 minion_speed_scale）。 */
    private static volatile double minionSpeedScale = 2.0;

    public static double minionSpeedScale() {
        return minionSpeedScale;
    }

    public void load(final Path dataFolder) throws IOException {
        final YamlNode root = YamlNode.load(dataFolder.resolve(FILE));
        final int[] parsedRequirements = parseRequirements(root, "level_requirements");
        // 2026-10-26：需求等級照 PoE2（20 級要 90 級）；掉落與法術成長照原本的曲線（uncut_drop_levels，沒寫就跟需求一樣）
        final int[] parsedDropLevels = root.has("uncut_drop_levels") ? parseRequirements(root, "uncut_drop_levels") : parsedRequirements;
        final NavigableMap<Integer, Double> parsedGrowth = parseGrowth(root.node("spell_damage_growth"));
        final double[] parsedUncutWeights = parseUncutWeights(root);
        final java.util.Map<Integer, Double> parsedUncutScale = parseUncutScale(root);
        final double extraLevelDamage = root.decimal("extra_level_damage", DEFAULT_EXTRA_LEVEL_DAMAGE);
        // 2026-10-27 照站長「所有技能的魔力消耗砍半」：每個技能的魔力消耗再乘這個（mana_cost_scale，0.1–10）
        manaCostScale = Math.max(0.1, Math.min(10, root.decimal("mana_cost_scale", 1.0)));
        playerSpeedScale = Math.max(0.1, Math.min(10, root.decimal("player_speed_scale", 2.0))); // 2026-10-29 照站長
        minionMoveScale = Math.max(0.1, Math.min(10, root.decimal("minion_move_scale", 2.0))); // 2026-10-29 照站長
        minionSpeedScale = Math.max(0.1, Math.min(10, root.decimal("minion_speed_scale", 2.0))); // 2026-10-29 照站長
        if (extraLevelDamage < 0 || extraLevelDamage > 100) {
            throw new IllegalArgumentException(FILE + ".extra_level_damage：要在 0 到 100 之間（百分比）");
        }
        final double minionExtraLevelDamage = root.decimal("minion_extra_level_damage", DEFAULT_MINION_EXTRA_LEVEL_DAMAGE);
        if (minionExtraLevelDamage < 0 || minionExtraLevelDamage > 100) {
            throw new IllegalArgumentException(FILE + ".minion_extra_level_damage：要在 0 到 100 之間（百分比）");
        }
        final Map<String, SkillDefinition> parsed = new LinkedHashMap<>();
        final Map<String, Scaled> parsedClips = new HashMap<>();
        final Map<String, java.util.List<ScaledMod>> parsedLevelMods = new HashMap<>();
        final Map<String, LodgeSpec> parsedLodges = new HashMap<>();
        final Map<String, RainSpec> parsedRains = new HashMap<>();
        final Map<String, RodSpec> parsedRods = new HashMap<>();
        final Map<String, CascadeSpec> parsedCascades = new HashMap<>();
        final Map<String, Map<String, SkillStages.Stage>> parsedStages = new HashMap<>(); // 2026-10-28 照 PoE2：分段傷害（stages）
        final Map<String, ChargeCost> parsedChargeCosts = new HashMap<>();
        final Map<String, Integer> parsedUses = new HashMap<>();
        final Map<String, ResourceSpec> parsedResources = new HashMap<>();
        final Map<String, java.util.List<String>> parsedCommands = new HashMap<>(); // 2026-10：指令型召喚物技能
        final Map<String, Double> parsedParent = new HashMap<>();
        final Map<String, Double> parsedLife = new HashMap<>();
        final Map<String, Mounts.Spec> parsedMounts = new HashMap<>();
        final Map<String, BarrageSpec> parsedBarrages = new HashMap<>();
        final Map<String, ChargeSpec> parsedCharges = new HashMap<>();
        final Map<String, String> parsedSignatures = new HashMap<>();
        final Map<String, MeleeStep.Spec> parsedSteps = new HashMap<>();
        final Map<String, MetaSpec> parsedMetas = new HashMap<>();
        final Map<String, TornadoSpec> parsedTornados = new HashMap<>();
        final YamlNode section = root.node("skills");
        final Map<String, String> starters = new HashMap<>();
        final Map<String, String> variantMap = new HashMap<>();
        for (final String id : section.keys()) {
            YamlNode node = section.node(id);
            final String variantOf = node.string("variant_of", null);
            if (variantOf != null) {
                // 變體（劫盜的異質寶石）：照原本的技能，只改寫到的地方；起始技能、專屬來源不帶過來
                if (!section.has(variantOf.trim()) || section.node(variantOf.trim()).has("variant_of")) {
                    throw new IllegalArgumentException(node.path() + ".variant_of：沒有 " + variantOf + " 這個技能（或它本身也是變體）");
                }
                node = section.node(variantOf.trim()).mergedWith(node, Set.of("starter", "source", "variant_of"));
                variantMap.put(id, variantOf.trim());
            }
            final SkillDefinition definition = SkillDefinition.parse(id, node, VISUALS);
            if (definition.starter() != null) {
                final String previous = starters.put(definition.starter(), id);
                if (previous != null) {
                    throw new IllegalArgumentException(FILE + "：職業 " + definition.starter() + " 有兩個起始技能（" + previous + "、" + id + "）");
                }
            }
            parsed.put(id, definition);
            if (node.has("cooldown_uses")) {
                // 2026-10-26（PoE2）：冷卻可以存幾次（榴彈「5 秒（3 次）」）
                final int uses = node.integer("cooldown_uses", 1);
                if (uses < 1 || uses > 10) {
                    throw new IllegalArgumentException(node.path() + ".cooldown_uses：要在 1 到 10 之間");
                }
                parsedUses.put(id, uses);
            }
            if (node.has("level_modifiers")) {
                parsedLevelMods.put(id, ScaledMod.parseList(node, "level_modifiers"));
            }
            if (node.has("lodge")) {
                final YamlNode l = node.node("lodge");
                final String sub = l.requireString("skill").trim();
                // 2026-10-29 照站長：1 公尺 = 2 格（Units）——scatter（散在準心周圍多遠）跟著落點的範圍換成格；trigger（敵人靠多近引爆）照 Minecraft，不換
                parsedLodges.put(id, new LodgeSpec(sub, Math.max(1, l.integer("count", 1)),
                        tw.exilecore.core.Units.metres(Math.max(0, l.decimal("scatter", 0))),
                        Math.max(0.5, l.decimal("duration", 10)), Math.max(1, l.integer("max", 30)), l.bool("proximity", false),
                        Math.max(0, l.decimal("arm", 0)), Math.max(0.3, l.decimal("trigger", 1.0)), Math.max(0, l.decimal("ramp_more", 0)),
                        Math.max(0.05, l.decimal("ramp_seconds", 1)), l.bool("expire", false)));
            }
            if (node.has("rod")) { // 2026-10-28 照 PoE2 Lightning Rod
                final YamlNode r = node.node("rod");
                parsedRods.put(id, new RodSpec(Math.max(0.5, r.decimal("duration", 20)), Math.max(1, r.integer("max", 10)),
                        Math.max(1, r.integer("chains", 8)), Math.max(0.05, r.decimal("charge", 1.0))));
            }
            final Map<String, SkillStages.Stage> stageMap = SkillStages.parse(node); // 2026-10-28 照 PoE2：每一段自己的逐級傷害
            if (!stageMap.isEmpty()) {
                parsedStages.put(id, stageMap);
            }
            if (node.has("cascade")) { // 2026-10-28 照 PoE2 Siege Ballista
                final YamlNode c = node.node("cascade");
                // 2026-10-29 照站長：1 公尺 = 2 格（Units）——爆炸半徑照 PoE2 寫公尺，讀進來換成格
                parsedCascades.put(id, new CascadeSpec(tw.exilecore.core.Units.metres(Math.max(0.3, c.decimal("radius", 1.4))),
                        Math.max(0, c.decimal("after", 0.3)),
                        Scaled.parse(c.path() + ".effectiveness", c.raw("effectiveness"), 1),
                        c.has("convert") ? SkillDefinition.weights(c.path() + ".convert", c.node("convert"), false) : Map.of()));
            }
            if (node.has("charge_cost")) {
                final YamlNode c = node.node("charge_cost");
                parsedChargeCosts.put(id, new ChargeCost(c.requireString("charge").trim().toLowerCase(java.util.Locale.ROOT),
                        Math.max(1, c.integer("count", 1))));
            }
            if (node.has("rain")) {
                final YamlNode r = node.node("rain");
                // 2026-10-29 照站長：1 公尺 = 2 格（Units）——落點範圍、每一發的半徑照 PoE2 寫公尺，讀進來換成格
                parsedRains.put(id, new RainSpec(tw.exilecore.core.Units.metres(Math.max(0.5, r.decimal("area", 4))),
                        tw.exilecore.core.Units.metres(Math.max(0.3, r.decimal("radius", 1))),
                        Math.max(0, r.decimal("seconds", 1)), Scaled.parse(r.path() + ".store", r.raw("store"), 1),
                        Math.max(0.05, r.decimal("interval", 0.4))));
            }
            if (node.has("clip")) {
                // 2026-10-26（PoE2）：弩技能自己的彈匣大小（可以逐級），沒寫是 1 發
                final Scaled clip = Scaled.parse(node.path() + ".clip", node.raw("clip"), 1);
                if (clip.min() < 1) {
                    throw new IllegalArgumentException(node.path() + ".clip：至少 1 發");
                }
                parsedClips.put(id, clip);
            }
            if (node.has("mount")) {
                final YamlNode m = node.node("mount");
                final String kind = m.string("kind", "HORSE").trim().toUpperCase(java.util.Locale.ROOT);
                if (!Mounts.Spec.KINDS.contains(kind)) {
                    throw new IllegalArgumentException(m.path() + ".kind：要寫 " + Mounts.Spec.KINDS + " 其中一個");
                }
                final double speed = m.decimal("speed", 1.0);
                if (speed < 0.5 || speed > 2.5) {
                    throw new IllegalArgumentException(m.path() + ".speed：要在 0.5 到 2.5 之間");
                }
                parsedMounts.put(id, new Mounts.Spec(kind, speed));
            }
            if (node.has("requires_mount")) { // 2026-10-26：照站長拿掉鐵蹄踐踏，「要騎著才能放」跟著拿掉
                throw new IllegalArgumentException(node.path() + ".requires_mount：已經拿掉了");
            }
            final ChargeSpec charge = ChargeSpec.parse(node);
            if (charge != null) {
                parsedCharges.put(id, charge);
            }
            if (node.has("signature")) {
                final String signature = node.string("signature", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (!SIGNATURES.contains(signature)) {
                    throw new IllegalArgumentException(node.path() + ".signature：要寫 " + String.join("、", SIGNATURES) + "，卻是「" + signature + "」");
                }
                parsedSignatures.put(id, signature);
            }
            final MeleeStep.Spec step = MeleeStep.parse(node); // 2026-10-28 照 PoE2：近戰條件位移
            if (step != null) {
                if (!definition.isAttack() || !definition.tags().contains(tw.exilecore.stats.Tags.MELEE)
                        || !Set.of(Mechanic.STRIKE, Mechanic.NOVA, Mechanic.CONE, Mechanic.ZONE).contains(definition.mechanic())) {
                    throw new IllegalArgumentException(node.path() + ".step：只有近戰攻擊的 strike、nova、cone、zone 能寫");
                }
                // 2026-10-29 照站長：出手前踏步全部拿掉——伺服器上舊的 skills.yml 還寫著 step 也不會踏（要恢復把這行打開）
                // parsedSteps.put(id, step);
            }
            final BarrageSpec barrage = BarrageSpec.parse(node);
            if (barrage != null) {
                if (definition.mechanic() != Mechanic.GUARD) {
                    throw new IllegalArgumentException(node.path() + ".barrage：只有增益技能（mechanic: guard）能寫");
                }
                parsedBarrages.put(id, barrage);
            }
            if (node.has("tornado")) {
                if (definition.mechanic() != Mechanic.PROJECTILE) {
                    throw new IllegalArgumentException(node.path() + ".tornado：只有投射物技能能寫");
                }
                parsedTornados.put(id, TornadoSpec.parse(node.path() + ".tornado", node.node("tornado")));
            }
            if (node.has("meta")) {
                final MetaSpec meta = MetaSpec.parse(node.path() + ".meta", node.node("meta"));
                if (meta.totem() ? definition.mechanic() != Mechanic.META : definition.mechanic() != Mechanic.AURA) {
                    throw new IllegalArgumentException(node.path() + ".meta：法術圖騰（kind: totem）要寫 mechanic: meta，"
                            + "其他（暴擊時施放這類）是常駐的，要寫 mechanic: aura");
                }
                parsedMetas.put(id, meta);
            } else if (definition.mechanic() == Mechanic.META && !HollowForm.hosts(definition)) {
                // 2026-10-28 照 PoE2 Hollow Form：空無型態是按住引導的主要寶石（SkillService／HollowImages 處理），沒有 meta 區塊
                throw new IllegalArgumentException(node.path() + "：mechanic: meta 要寫 meta 區塊");
            }
            if (node.has("parent_damage") && "own".equals(String.valueOf(node.raw("parent_damage")).trim())) {
                // 2026-10-26 照 PoE2：觸發的那一下有自己的傷害表（effectiveness 逐級），照觸發它的那一下（含輔助）換算：
                // 倍數 = 自己這一級的 effectiveness ÷ 原技能這一級的 effectiveness
                parsedParent.put(id, PARENT_OWN_TABLE);
            } else if (node.has("parent_damage")) {
                final double share = node.decimal("parent_damage", 0);
                if (share <= 0 || share > 5) {
                    throw new IllegalArgumentException(node.path() + ".parent_damage：要在 0 到 5 之間（原技能一下的倍數）");
                }
                parsedParent.put(id, share);
            }
            if (node.has("life_damage")) {
                final double percent = node.decimal("life_damage", 0);
                if (percent <= 0 || percent > 100) {
                    throw new IllegalArgumentException(node.path() + ".life_damage：要在 0 到 100 之間（敵人最大生命的幾 %）");
                }
                parsedLife.put(id, percent);
            }
            if (node.has("command")) {
                final java.util.List<String> who = new java.util.ArrayList<>();
                for (final String one : node.strings("command")) {
                    who.add(one.trim().toLowerCase(java.util.Locale.ROOT));
                }
                if (who.isEmpty()) {
                    throw new IllegalArgumentException(node.path() + ".command：要寫聽命的召喚物技能（例如 [summon_skeletons]，或 [any]）");
                }
                parsedCommands.put(id, java.util.List.copyOf(who));
            }
            if (node.has("resource")) {
                final ResourceSpec spec = ResourceSpec.parse(id, node.node("resource"));
                if (!spec.isEmpty()) {
                    parsedResources.put(id, spec);
                }
            }
        }
        for (final SkillDefinition definition : parsed.values()) {
            // 捷：擊殺時放的隱藏技能要存在、要是隱藏的法術、要是能從別的地方放的運作方式
            if (definition.aura() == null || definition.aura().onKill() == null) {
                continue;
            }
            final SkillDefinition sub = parsed.get(definition.aura().onKill());
            if (sub == null) {
                throw new IllegalArgumentException(FILE + "：" + definition.id() + " 的 aura.on_kill 指到不存在的技能 " + definition.aura().onKill());
            }
            // 2026-10-26 照 PoE2：雷使、霜使的擊殺效果是攻擊（吃武器傷害），隱藏的攻擊也可以
            if (!sub.hidden() || !Set.of(Mechanic.STRIKE, Mechanic.PROJECTILE, Mechanic.NOVA, Mechanic.CONE,
                    Mechanic.CHAIN, Mechanic.IMPACT, Mechanic.ZONE).contains(sub.mechanic())) {
                throw new IllegalArgumentException(FILE + "：" + definition.id() + " 的 aura.on_kill 技能 " + sub.id()
                        + " 要是 hidden: true 的技能，運作方式是 strike、projectile、nova、cone、chain、impact 或 zone");
            }
        }
        final List<String> missing = new ArrayList<>();
        for (final String id : java.util.stream.Stream.concat(DEFAULT_ATTACKS.stream(), OPTIONAL_DEFAULT_ATTACKS.stream()).toList()) {
            final SkillDefinition definition = parsed.get(id);
            if (definition == null) {
                if (OPTIONAL_DEFAULT_ATTACKS.contains(id)) {
                    missing.add(id);
                    continue;
                }
                throw new IllegalArgumentException(FILE + "：缺少普攻 " + id);
            }
            if (!definition.hidden() || !definition.isAttack() || definition.mana().last() > 0) {
                throw new IllegalArgumentException(FILE + "：普攻 " + id + " 要是 hidden: true、type: attack、不耗魔力");
            }
        }
        final Map<String, SupportDefinition> parsedSupports = new LinkedHashMap<>();
        final Map<String, Integer> parsedRetired = new HashMap<>();
        final Map<String, String> parsedMergedSupports = new HashMap<>();
        final Map<String, String> parsedMergedSupportNames = new HashMap<>();
        int[] tierLevels = {1, 15, 35};
        boolean oncePerCharacter = false;
        final Path supportsFile = dataFolder.resolve(SUPPORTS_FILE);
        if (Files.exists(supportsFile)) {
            final YamlNode supportRoot = YamlNode.load(supportsFile);
            tierLevels = parseTierLevels(supportRoot.node("tier_levels"), tierLevels);
            oncePerCharacter = supportRoot.bool("once_per_character", false);
            final YamlNode retiredNode = supportRoot.node("retired");
            for (final String id : retiredNode.keys()) {
                parsedRetired.put(id, Math.max(1, Math.min(SupportDefinition.MAX_TIER, retiredNode.integer(id, 1))));
            }
            final YamlNode supportSection = supportRoot.node("supports");
            for (final String id : supportSection.keys()) {
                if (parsed.containsKey(id)) {
                    throw new IllegalArgumentException(SUPPORTS_FILE + "：輔助寶石的代號不能跟技能一樣（" + id + "）");
                }
                parsedSupports.put(id, SupportDefinition.parse(id, supportSection.node(id)));
            }
            // 2026-10-26：照站長「輔助寶石有分 I II III 的一律保留最高的」：較低階的併入同系列最高階
            //   （merged：舊代號: { into: 新代號, name: 舊名稱 }，或只寫新代號）
            final YamlNode mergedNode = supportRoot.node("merged");
            for (final String id : mergedNode.keys()) {
                final String into;
                if (mergedNode.raw(id) instanceof Map<?, ?>) {
                    final YamlNode one = mergedNode.node(id);
                    into = one.string("into", "").trim();
                    parsedMergedSupportNames.put(id, one.string("name", id).trim());
                } else {
                    into = mergedNode.string(id, "").trim();
                }
                if (parsedSupports.containsKey(id)) {
                    throw new IllegalArgumentException(SUPPORTS_FILE + " merged." + id + "：這個輔助還在 supports 裡（併入別的輔助要把定義刪掉）");
                }
                if (!parsedSupports.containsKey(into)) {
                    throw new IllegalArgumentException(SUPPORTS_FILE + " merged." + id + "：併入的輔助「" + into + "」不在 supports 裡");
                }
                if (parsedRetired.containsKey(id)) {
                    throw new IllegalArgumentException(SUPPORTS_FILE + " merged." + id + "：同時列在 retired（只能擇一）");
                }
                parsedMergedSupports.put(id, into);
            }
        }
        // 2026-10-27 照 PoE2：血脈輔助（supports_lineage.yml）：新的血脈輔助加進來，supports.yml 裡已有的標成血脈掉落
        LineageRules.Drops parsedLineageDrops = LineageRules.Drops.DEFAULT;
        final Path lineageFile = dataFolder.resolve(LINEAGE_FILE);
        if (Files.exists(lineageFile)) {
            parsedLineageDrops = LineageRules.load(YamlNode.load(lineageFile), parsedSupports, parsed.keySet());
        }
        // 2026-10-18：驗證移到任何欄位指定之前（以前先換掉一半才驗證，失敗時新舊混用）；Boss 招式也在換之前載入（它自己解析好才換）
        for (final Map.Entry<String, java.util.List<String>> entry : parsedCommands.entrySet()) {
            for (final String who : entry.getValue()) {
                if (!who.equals("any") && (parsed.get(who) == null || parsed.get(who).summon() == null)) {
                    throw new IllegalArgumentException(FILE + "：" + entry.getKey() + ".command 的 " + who + " 不是召喚物技能");
                }
            }
        }
        bossSkills.load(dataFolder);
        this.requirements = parsedRequirements;
        this.dropLevels = parsedDropLevels;
        this.uncutLevelWeights = parsedUncutWeights;
        this.uncutLevelScale = parsedUncutScale;
        this.extraLevelGrowth = 1.0 + extraLevelDamage / 100.0;
        this.minionExtraLevelGrowth = 1.0 + minionExtraLevelDamage / 100.0;
        this.growth = parsedGrowth;
        this.skills = parsed;
        SubSkills.bind(parsed); // 2026-10-29 照 PoE2：一顆寶石兩個技能（雷縛長棍的電震波）
        this.clips = parsedClips;
        this.levelModifiers = Map.copyOf(parsedLevelMods);
        this.lodges = Map.copyOf(parsedLodges);
        this.rains = Map.copyOf(parsedRains);
        this.rods = Map.copyOf(parsedRods);
        this.cascades = Map.copyOf(parsedCascades);
        SkillStages.install(parsedStages);
        this.chargeCosts = Map.copyOf(parsedChargeCosts);
        this.cooldownUses = parsedUses;
        this.resources = parsedResources;
        // 2026-10-27：輔助的防呆要看的（有使用條件、會留殘骸的技能）；要在 resources 換好之後（這次之前在前一行，第一次讀設定時看不到 resource.combo、resource.remnant）
        SupportGuards.index(this);
        this.commands = Map.copyOf(parsedCommands);
        this.parentDamage = Map.copyOf(parsedParent);
        this.lifeDamage = Map.copyOf(parsedLife);
        this.mountSpecs = Map.copyOf(parsedMounts);
        this.barrages = Map.copyOf(parsedBarrages);
        this.charges = Map.copyOf(parsedCharges);
        this.signatures = Map.copyOf(parsedSignatures);
        this.steps = Map.copyOf(parsedSteps);
        this.metas = Map.copyOf(parsedMetas);
        this.tornados = Map.copyOf(parsedTornados);
        this.variants = Map.copyOf(variantMap);
        this.missingDefaults = List.copyOf(missing);
        // 2026-10：輔助照階級排（第 1 階全部在前、再第 2 階…；同一階照 supports.yml 的順序），後來加的不會都擠在最後面
        final java.util.List<SupportDefinition> byTier = new java.util.ArrayList<>(parsedSupports.values());
        byTier.sort(java.util.Comparator.comparingInt(SupportDefinition::tier));
        final Map<String, SupportDefinition> ordered = new LinkedHashMap<>();
        for (final SupportDefinition support : byTier) {
            ordered.put(support.id(), support);
        }
        this.supports = ordered;
        this.lineageDrops = parsedLineageDrops;
        this.retiredSupports = Map.copyOf(parsedRetired);
        this.mergedSupports = Map.copyOf(parsedMergedSupports);
        this.mergedSupportNames = Map.copyOf(parsedMergedSupportNames);
        final Map<String, String> parsedRetiredSkills = new HashMap<>();
        final YamlNode retiredSkillNode = root.node("retired");
        for (final String id : retiredSkillNode.keys()) {
            if (parsed.containsKey(id)) {
                throw new IllegalArgumentException("skills.yml retired." + id + "：這個技能還在 skills 裡（拿掉的技能要把定義刪掉）");
            }
            parsedRetiredSkills.put(id, retiredSkillNode.string(id, id).trim());
        }
        // 2026-10-26：併入別的技能（merged：舊代號: { into: 新代號, name: 舊名稱 }，或只寫新代號）；舊名稱跟 retired 一樣放進通知用的名稱表
        final Map<String, String> parsedMerged = new HashMap<>();
        final YamlNode mergedNode = root.node("merged");
        for (final String id : mergedNode.keys()) {
            final String into;
            if (mergedNode.raw(id) instanceof Map<?, ?>) {
                final YamlNode one = mergedNode.node(id);
                into = one.string("into", "").trim();
                if (one.has("name")) {
                    parsedRetiredSkills.put(id, one.string("name", id).trim());
                }
            } else {
                into = mergedNode.string(id, "").trim();
            }
            if (parsed.containsKey(id)) {
                throw new IllegalArgumentException("skills.yml merged." + id + "：這個技能還在 skills 裡（併入別的技能要把定義刪掉）");
            }
            if (!parsed.containsKey(into)) {
                throw new IllegalArgumentException("skills.yml merged." + id + "：併入的技能「" + into + "」不在 skills 裡");
            }
            if (retiredSkillNode.has(id)) {
                throw new IllegalArgumentException("skills.yml merged." + id + "：同時列在 retired（只能擇一：換成未切割寶石，或併入別的技能）");
            }
            parsedMerged.put(id, into);
        }
        this.mergedSkills = Map.copyOf(parsedMerged);
        this.retiredSkills = Map.copyOf(parsedRetiredSkills);
        this.supportTierLevels = tierLevels;
        this.supportOncePerCharacter = oncePerCharacter;
        final Map<String, String> names = new java.util.HashMap<>();
        parsed.forEach((id, definition) -> names.put(id, definition.name()));
        skillNames = Map.copyOf(names);
        current = this;
    }

    /** 最近一次載入的設定（2026-10：寶石說明列觸發技能的詳細效果用，見 {@link ProcText}）。 */
    private static volatile SkillConfig current;

    static SkillConfig current() {
        return current;
    }

    /** 最近一次載入的技能代號 → 名稱（說明文字用，2026-10）。 */
    private static volatile Map<String, String> skillNames = Map.of();

    /** 技能代號的名稱（找不到回傳代號）。 */
    static String nameOf(final String id) {
        return skillNames.getOrDefault(id, id);
    }

    /** Boss 招式（data/boss_skills.yml）。 */
    public BossSkillConfig bossSkills() {
        return bossSkills;
    }

    /** skills.yml 裡沒有的新武器普攻（用替代的，啟用時提醒）。 */
    public List<String> missingDefaults() {
        return missingDefaults;
    }

    /** 同一種輔助一個角色只能用一次（PoE2）；false = 不同技能可以各掛一顆（同一個技能不能掛兩顆一樣的）。 */
    /** 這種輔助是不是一個角色只能掛一顆（設定全部開，或是要保留精魂的輔助，2026-10）。 */
    public boolean oncePerCharacter(final String supportId) {
        if (supportOncePerCharacter()) {
            return true;
        }
        final SupportDefinition support = support(supportId);
        return support != null && support.spiritSupport();
    }

    public boolean supportOncePerCharacter() {
        return supportOncePerCharacter;
    }

    private static int[] parseTierLevels(final YamlNode node, final int[] defaults) {
        final int[] levels = defaults.clone();
        for (final String key : node.keys()) {
            final int tier;
            try {
                tier = Integer.parseInt(key.trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + "：鍵要是階級（1 到 " + SupportDefinition.MAX_TIER + "），卻是「" + key + "」");
            }
            if (tier < 1 || tier > SupportDefinition.MAX_TIER) {
                throw new IllegalArgumentException(node.path() + "：階級要在 1 到 " + SupportDefinition.MAX_TIER + " 之間");
            }
            levels[tier - 1] = Math.max(1, node.integer(key, levels[tier - 1]));
        }
        for (int i = 1; i < levels.length; i++) {
            if (levels[i] < levels[i - 1]) {
                throw new IllegalArgumentException(node.path() + "：高階的掉落等級不能比低階的低");
            }
        }
        return levels;
    }

    /** uncut_level_scale：{ 等級: 0～1 }；沒寫是空的（每個等級都照 uncut_level_weights 掉）。 */
    static java.util.Map<Integer, Double> parseUncutScale(final YamlNode root) {
        if (!root.has("uncut_level_scale")) {
            return java.util.Map.of();
        }
        final YamlNode node = root.node("uncut_level_scale");
        final java.util.Map<Integer, Double> scale = new java.util.TreeMap<>();
        for (final String key : node.keys()) {
            final int level;
            try {
                level = Integer.parseInt(key.trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(FILE + ".uncut_level_scale：「" + key + "」要是寶石等級（1～20）");
            }
            final double value = node.decimal(key, 1.0);
            if (level < 1 || level > SkillLoadout.MAX_LEVEL + 1 || value < 0 || value > 1) {
                throw new IllegalArgumentException(FILE + ".uncut_level_scale." + key + "：等級 1～" + (SkillLoadout.MAX_LEVEL + 1)
                        + "、值 0～1（留下幾成）");
            }
            scale.put(level, value);
        }
        return java.util.Map.copyOf(scale);
    }

    /** 擲到這個等級的未切割技能寶石要不要真的掉（uncut_level_scale）。 */
    public boolean keepsUncut(final int level, final RandomGenerator random) {
        final Double keep = uncutLevelScale.get(level);
        return keep == null || keep >= 1 || random.nextDouble() < keep;
    }

    /** 這個等級的未切割技能寶石留下幾成（說明與測試用）。 */
    public double uncutLevelKeep(final int level) {
        return uncutLevelScale.getOrDefault(level, 1.0);
    }

    /** uncut_level_weights：1 到 5 個不是負數的權重（最高等級、低 1 級…），加起來要大於 0；沒寫用預設值。 */
    private static double[] parseUncutWeights(final YamlNode root) {
        final Object raw = root.raw("uncut_level_weights");
        if (raw == null) {
            return DEFAULT_UNCUT_LEVEL_WEIGHTS.clone();
        }
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 5) {
            throw new IllegalArgumentException(FILE + ".uncut_level_weights：要寫 1 到 5 個權重（最高等級、低 1 級、低 2 級…）");
        }
        final double[] weights = new double[list.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            if (!(list.get(i) instanceof Number number) || number.doubleValue() < 0) {
                throw new IllegalArgumentException(FILE + ".uncut_level_weights[" + i + "]：要是不小於 0 的數字");
            }
            weights[i] = number.doubleValue();
            total += weights[i];
        }
        if (total <= 0) {
            throw new IllegalArgumentException(FILE + ".uncut_level_weights：權重不能全部是 0");
        }
        return weights;
    }

    private static int[] parseRequirements(final YamlNode root, final String key) {
        final Object raw = root.raw(key);
        if (!(raw instanceof List<?> list) || list.size() != SkillLoadout.MAX_LEVEL) {
            throw new IllegalArgumentException(FILE + "." + key + "：要寫 " + SkillLoadout.MAX_LEVEL + " 個角色等級（技能等級 1 到 20）");
        }
        final int[] result = new int[SkillLoadout.MAX_LEVEL];
        for (int i = 0; i < result.length; i++) {
            if (!(list.get(i) instanceof Number number)) {
                throw new IllegalArgumentException(FILE + "." + key + "[" + i + "]：應該是整數");
            }
            result[i] = number.intValue();
            if (result[i] < 1 || (i > 0 && result[i] < result[i - 1])) {
                throw new IllegalArgumentException(FILE + "." + key + "：要從 1 以上遞增");
            }
        }
        return result;
    }

    private static NavigableMap<Integer, Double> parseGrowth(final YamlNode node) {
        final NavigableMap<Integer, Double> map = new TreeMap<>();
        for (final String key : node.keys()) {
            final int level;
            try {
                level = Integer.parseInt(key.trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + "：鍵要是角色等級，卻是「" + key + "」");
            }
            final double value = node.decimal(key, 1);
            if (value <= 0) {
                throw new IllegalArgumentException(node.path() + "." + key + "：倍率要大於 0");
            }
            map.put(level, value);
        }
        if (map.isEmpty()) {
            map.put(1, 1.0);
        }
        return map;
    }

    /** 這個技能是哪個系列（異質技能算原本的那個；一般技能是自己）。 */
    public String family(final String id) {
        return id == null ? null : variants.getOrDefault(id, id);
    }

    /**
     * 面板上有沒有同一個系列、但不是同一個的技能（異質寶石跟原本的、或兩個不同的異質不能同時放）；
     * 回傳那一格（ignore 那一格不算），沒有回傳 -1。
     */
    public int familyConflict(final tw.exilecore.character.SkillLoadout loadout, final String skillId, final int ignore) {
        final String family = family(skillId);
        for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry other = loadout.skill(i);
            if (i != ignore && other != null && !other.skillId().equals(skillId) && family.equals(family(other.skillId()))) {
                return i;
            }
        }
        return -1;
    }

    /** 技能的連段、榮耀、元素灌注；沒有回傳 {@link ResourceSpec#NONE}。 */
    /** 坐騎技能叫出的坐騎（不是回傳 null）。 */
    public Mounts.Spec mount(final String id) {
        return id == null ? null : mountSpecs.get(id);
    }

    /** 連珠（2026-10）：這個增益技能讓下一次弓／矛的投射物攻擊重複的設定（不是回傳 null）。 */
    /** 龍捲風的設定（2026-10）；沒有回傳 null。 */
    public TornadoSpec tornado(final String id) {
        return id == null ? null : tornados.get(id);
    }

    /** 主要寶石的設定（2026-10）；不是主要寶石回傳 null。 */
    public MetaSpec meta(final String id) {
        return id == null ? null : metas.get(id);
    }

    /** 蓄力放開的設定（沒有回傳 null）。 */
    public ChargeSpec charge(final String id) {
        return id == null ? null : charges.get(id);
    }

    /** 2026-10-28 照 PoE2：近戰條件位移（skills.yml 的 step；沒有回傳 null）。 */
    public MeleeStep.Spec step(final String id) {
        return id == null ? null : steps.get(id);
    }

    /** 專屬機制（whirl、lance、ember；沒有回傳 null）。 */
    public String signature(final String id) {
        return id == null ? null : signatures.get(id);
    }

    public BarrageSpec barrage(final String id) {
        return id == null ? null : barrages.get(id);
    }

    /** 隱藏技能照觸發它的技能出傷害的倍數（0 = 不是，照自己的傷害效益）。 */
    public double parentDamage(final String id) {
        return id == null ? 0 : parentDamage.getOrDefault(id, 0.0);
    }

    /** parent_damage: own（2026-10-26）：記成這個值。 */
    public static final double PARENT_OWN_TABLE = -1;

    /**
     * 2026-10-26：觸發的隱藏技能這一級實際是原技能一下的幾倍（0 = 不照原技能）。
     * parent_damage 寫數字就是那個數字；寫 own 就是「自己的 effectiveness ÷ 原技能的 effectiveness」（同一個等級）。
     */
    public double lifeDamage(final String id) {
        return lifeDamage.getOrDefault(id, 0.0);
    }

    /**
     * 2026-10-26：觸發的隱藏技能的傷害照敵人最大生命的幾 %（skills.yml 的 life_damage；0 = 照自己的傷害表）。
     * 上面那個：觸發的隱藏技能這一級實際是原技能一下的幾倍（見下面）。
     */
    public double parentShare(final String subId, final SkillDefinition parent, final int level) {
        final double raw = parentDamage(subId);
        if (raw >= 0) {
            return raw;
        }
        final SkillDefinition sub = skill(subId);
        if (sub == null || parent == null) {
            return 0;
        }
        final double base = parent.effectiveness().at(level);
        return base <= 0 ? 0 : sub.effectiveness().at(level) / base;
    }

    /** 指令型召喚物技能聽命的召喚物技能（不是指令技能回傳 null）。 */
    public java.util.List<String> command(final String id) {
        return id == null ? null : commands.get(id);
    }

    public ResourceSpec resource(final String id) {
        return id == null ? ResourceSpec.NONE : resources.getOrDefault(id, ResourceSpec.NONE);
    }

    public SkillDefinition skill(final String id) {
        return id == null ? null : skills.get(id);
    }

    public Collection<SkillDefinition> all() {
        return skills.values();
    }

    /** 玩家看得到、可以學的技能（不含普攻），依檔案順序。 */
    public List<SkillDefinition> visible() {
        final List<SkillDefinition> list = new ArrayList<>();
        for (final SkillDefinition definition : skills.values()) {
            if (!definition.hidden()) {
                list.add(definition);
            }
        }
        return list;
    }

    /**
     * 技能與輔助寶石寫到的戰星代號（cooldown_bypass、戰吼的 charges、charge.*.代號 的修飾器）在 charges.yml 裡找不到的：
     * 一條一句（啟用、/ec reload 時寫進伺服器紀錄；打錯字的話那個效果不會有作用）。
     */
    public List<String> chargeProblems(final java.util.function.Predicate<String> known) {
        final List<String> problems = new ArrayList<>();
        for (final SkillDefinition skill : skills.values()) {
            if (skill.cooldownBypass() != null && !known.test(skill.cooldownBypass())) {
                problems.add("skills.yml：" + skill.id() + " 的 cooldown_bypass 是 " + skill.cooldownBypass() + "，charges.yml 沒有這種戰星");
            }
            if (skill.warcry() != null) {
                for (final String charge : skill.warcry().charges().keySet()) {
                    if (!known.test(charge)) {
                        problems.add("skills.yml：" + skill.id() + " 的 warcry.charges 有 " + charge + "，charges.yml 沒有這種戰星");
                    }
                }
            }
        }
        for (final SupportDefinition support : supports()) {
            for (final tw.exilecore.stats.Modifier modifier : support.modifiers()) {
                final String charge = tw.exilecore.charge.ChargeText.chargeOf(modifier.stat());
                if (charge != null && !known.test(charge)) {
                    problems.add("supports.yml：" + support.id() + " 的 " + modifier.stat() + "，charges.yml 沒有這種戰星");
                }
            }
            for (final String charge : tw.exilecore.charge.ChargeText.placeholders(support.description())) {
                if (!known.test(charge)) {
                    problems.add("supports.yml：" + support.id() + " 的說明寫了 {charge:" + charge + "}，charges.yml 沒有這種戰星");
                }
            }
        }
        return problems;
    }

    /** 這個技能的冷卻可以存幾次（沒寫是 1）。 */
    public int cooldownUses(final String skillId) {
        return cooldownUses.getOrDefault(skillId, 1);
    }

    /** 弩技能這個等級的彈匣大小（沒寫是 1 發，照 PoE2）。 */
    public int clip(final String skillId, final int level) {
        final Scaled clip = clips.get(skillId);
        return clip == null ? 1 : Math.max(1, clip.intAt(level));
    }

    /** 技能等級需要的角色等級。 */
    public int requiredLevel(final int skillLevel) {
        return requirements[Math.max(1, Math.min(SkillLoadout.MAX_LEVEL, skillLevel)) - 1];
    }

    /** 這個角色等級能用的最高技能等級（至少 1）。 */
    public int maxLevelFor(final int characterLevel) {
        int best = 1;
        for (int level = 1; level <= SkillLoadout.MAX_LEVEL; level++) {
            if (requiredLevel(level) <= characterLevel) {
                best = level;
            }
        }
        return best;
    }

    /** 法術成長表的錨點（需求等級 → 倍率），唯讀。 */
    public NavigableMap<Integer, Double> spellGrowthAnchors() {
        return java.util.Collections.unmodifiableNavigableMap(growth);
    }

    /** 法術基礎傷害的成長倍率（依需求等級，錨點之間等比內插，兩端以外取端點）。 */
    public double spellGrowth(final int requiredLevel) {
        return LevelCurve.at(growth, requiredLevel, 1.0, LevelCurve.Mode.GEOMETRIC);
    }

    /**
     * 這個技能等級的法術成長倍率：20 級以內看需求等級（{@link #spellGrowth}），
     * 超過 20 級（裝備的 +技能等級）每多一級再乘 1 + extra_level_damage。
     */
    public double spellGrowthAt(final int skillLevel) {
        final int capped = Math.min(SkillLoadout.MAX_LEVEL, Math.max(1, skillLevel));
        final double base = spellGrowth(dropLevel(capped)); // 2026-10-26：照原本的曲線（需求等級改成 PoE2 之後不跟著變）
        return skillLevel > SkillLoadout.MAX_LEVEL ? base * Math.pow(extraLevelGrowth, skillLevel - SkillLoadout.MAX_LEVEL) : base;
    }

    /** 超過 20 級每多一級，法術基礎傷害的倍率（1.1 = 多 10%）。 */
    public double extraLevelGrowth() {
        return extraLevelGrowth;
    }

    /** 超過 20 級每多一級，召喚物生命與傷害的倍率（1.05 = 多 5%）。 */
    public double minionExtraLevelGrowth() {
        return minionExtraLevelGrowth;
    }

    /**
     * 沒綁技能的按鍵用哪種普攻：弓射箭、弩射弩箭、法杖與長杖射魔法彈；長棍橫掃（打兩個）、矛往前刺（比較遠），其他近戰。
     */
    public SkillDefinition defaultAttack(final Set<String> weaponTags) {
        if (weaponTags.contains(Tags.BOW)) {
            return skills.get(DEFAULT_BOW);
        }
        if (weaponTags.contains(Tags.CROSSBOW)) {
            return skills.getOrDefault(DEFAULT_CROSSBOW, skills.get(DEFAULT_BOW));
        }
        if (weaponTags.contains(Tags.STAFF) && weaponTags.contains(Tags.RANGED)) {
            return skills.get(DEFAULT_STAFF);
        }
        if (weaponTags.contains(Tags.RANGED)) {
            return skills.get(DEFAULT_WAND);
        }
        if (weaponTags.contains(Tags.QUARTERSTAFF)) {
            return skills.getOrDefault(DEFAULT_QUARTERSTAFF, skills.get(DEFAULT_MELEE));
        }
        if (weaponTags.contains(Tags.SPEAR)) {
            return skills.getOrDefault(DEFAULT_SPEAR, skills.get(DEFAULT_MELEE));
        }
        return skills.get(DEFAULT_MELEE);
    }

    // ---- 寶石 ----

    /** 2026-10 PoE2 改版拿掉的輔助寶石是第幾階（supports.yml 的 retired；換成同階的未切割輔助寶石）；沒寫是 1。 */
    public int retiredSupportTier(final String id) {
        return id == null ? 1 : retiredSupports.getOrDefault(id, 1);
    }

    /** 2026-10-26：輔助併入哪一顆（supports.yml 的 merged，同系列較低階的 I／II 併入最高階）；沒有併入回傳 null。 */
    public String supportMergedInto(final String id) {
        return id == null ? null : mergedSupports.get(id);
    }

    /** 2026-10-26：併入的輔助的通知文字（例如「割裂已經併入割裂 III」）；沒有併入回傳 null。 */
    public String supportMergeNotice(final String id) {
        final SupportDefinition target = support(supportMergedInto(id));
        return target == null ? null : mergedSupportNames.getOrDefault(id, id) + "已經併入" + target.name();
    }

    /**
     * 2026-10-26：技能寶石（含放在宿主孔裡的技能）上掛著併入別的輔助的舊輔助：原孔換成併入的那顆（共鳴保留）。
     * 同一個技能已經掛著那顆（同一個技能不能掛兩顆一樣的）就從孔上拿下來，做成輔助寶石放進 detached。
     * 不用換回傳原本的 entry（同一個物件）。
     */
    public SkillLoadout.Entry mergeSupports(final SkillLoadout.Entry entry, final List<GemData> detached) {
        if (entry == null) {
            return null;
        }
        SkillLoadout.Entry result = entry;
        for (int i = 0; i < result.sockets(); i++) {
            final String into = supportMergedInto(result.support(i));
            if (into == null) {
                continue;
            }
            final boolean resonantMark = result.resonant(i);
            if (result.supportIds().contains(into)) {
                detached.add(GemData.support(into, resonantMark));
                result = result.withSupport(i, null);
            } else {
                result = result.withSupport(i, into, resonantMark);
            }
        }
        final SkillLoadout.Entry inner = result.inner() == null ? null : mergeSupports(result.inner(), detached);
        return inner == result.inner() ? result : result.withInner(inner);
    }

    /** 2026-10-26：拿掉的技能（skills.yml 的 retired）。 */
    public boolean retiredSkill(final String id) {
        return id != null && retiredSkills.containsKey(id) && !mergedSkills.containsKey(id); // 2026-10-26：併入別的技能的名稱也放在這張表，不算拿掉
    }

    /** 2026-10-26：併入哪個技能（skills.yml 的 merged）；沒有併入回傳 null。 */
    public String mergedInto(final String id) {
        return id == null ? null : mergedSkills.get(id);
    }

    /** 2026-10-26：併入別的技能的通知文字（例如「矢幕已經併入連珠」）；沒有併入回傳 null。 */
    public String mergeNotice(final String id) {
        final String into = mergedInto(id);
        final SkillDefinition target = into == null ? null : skill(into);
        return target == null ? null : retiredSkillName(id) + "已經併入" + target.name();
    }

    /**
     * 2026-10-26：技能寶石（或放在宿主孔裡的技能）是併入別的技能的舊技能：換成併入的技能，等級、孔數、品質、腐化、輔助都保留。
     * 不用換回傳 null。
     */
    public SkillLoadout.Entry merge(final SkillLoadout.Entry entry) {
        if (entry == null) {
            return null;
        }
        final String into = mergedInto(entry.skillId());
        final SkillLoadout.Entry inner = entry.inner() == null ? null : merge(entry.inner());
        if (into == null && inner == null) {
            return null;
        }
        final SkillLoadout.Entry renamed = into == null ? entry
                : new SkillLoadout.Entry(into, entry.level(), entry.sockets(), entry.supports(), entry.resonant(),
                        entry.inner(), entry.corrupted(), entry.quality(), entry.granted());
        return inner == null ? renamed : renamed.withInner(inner);
    }

    /** 2026-10-26：拿掉的技能的名稱（通知用；併入別的技能的也算）；不在 skills.yml 的 retired、merged 裡就回傳代號。 */
    public String retiredSkillName(final String id) {
        return id == null ? "" : retiredSkills.getOrDefault(id, id);
    }

    public SupportDefinition support(final String id) {
        return id == null ? null : supports.get(id);
    }

    /** 2026-10-27：血脈輔助的掉落設定（supports_lineage.yml 的 drops）。 */
    public LineageRules.Drops lineageDrops() {
        return lineageDrops;
    }

    public Collection<SupportDefinition> supports() {
        return supports.values();
    }

    /** 階級 ≤ tier 的輔助寶石（未切割輔助寶石能切出的；專屬掉落的不算），依檔案順序。 */
    public List<SupportDefinition> supportsUpTo(final int tier) {
        final List<SupportDefinition> list = new ArrayList<>();
        for (final SupportDefinition definition : supports.values()) {
            if (definition.cuttable() && definition.tier() <= tier) {
                list.add(definition);
            }
        }
        return list;
    }

    /** 從某個來源掉的專屬技能寶石（skills.yml 的 source，例如劫盜的異質寶石），依檔案順序。 */
    public List<SkillDefinition> skillsFrom(final String source) {
        final List<SkillDefinition> list = new ArrayList<>();
        for (final SkillDefinition definition : skills.values()) {
            if (!definition.hidden() && definition.droppedBy(source)) {
                list.add(definition);
            }
        }
        return list;
    }

    /** 從某個來源掉的專屬輔助寶石（supports.yml 的 source），依檔案順序。 */
    public List<SupportDefinition> supportsFrom(final String source) {
        final List<SupportDefinition> list = new ArrayList<>();
        for (final SupportDefinition definition : supports.values()) {
            if (definition.droppedBy(source)) {
                list.add(definition);
            }
        }
        return list;
    }

    /** 等級 ≤ uncutLevel 能雕的技能（技能的階級 ≤ 未切割寶石的等級），依檔案順序。 */
    public List<SkillDefinition> engravable(final int uncutLevel) {
        final List<SkillDefinition> list = new ArrayList<>();
        for (final SkillDefinition definition : visible()) {
            if (definition.engravable() && definition.tier() <= uncutLevel) {
                list.add(definition);
            }
        }
        return list;
    }

    /** 這個等級的怪物掉的未切割技能寶石最高幾級（= 這個角色等級能用的最高技能等級）。 */
    public int uncutLevelFor(final int monsterLevel) {
        return highestAt(dropLevels, monsterLevel);
    }

    /** 掉落用的等級表：技能等級 → 怪物等級多少起會掉。 */
    public int dropLevel(final int skillLevel) {
        return dropLevels[Math.max(1, Math.min(SkillLoadout.MAX_LEVEL, skillLevel)) - 1];
    }

    private static int highestAt(final int[] table, final int level) {
        int best = 1;
        for (int i = 1; i <= table.length; i++) {
            if (table[i - 1] <= level) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 擲一顆掉落的未切割技能寶石等級：照 uncut_level_weights（預設 20/40/40：兩成是最高等級、四成低 1 級、四成低 2 級），
     * 最低 1 級。
     */
    public int rollUncutLevel(final int monsterLevel, final RandomGenerator random) {
        final int max = uncutLevelFor(monsterLevel);
        double total = 0;
        for (final double weight : uncutLevelWeights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        int drop = uncutLevelWeights.length - 1;
        for (int i = 0; i < uncutLevelWeights.length; i++) {
            roll -= uncutLevelWeights[i];
            if (roll < 0) {
                drop = i;
                break;
            }
        }
        return Math.max(1, max - drop);
    }

    /** 掉落的未切割技能寶石是最高等級、低 1 級…的機率（說明與測試用）。 */
    public double uncutLevelChance(final int below) {
        double total = 0;
        for (final double weight : uncutLevelWeights) {
            total += weight;
        }
        return below < 0 || below >= uncutLevelWeights.length || total <= 0 ? 0 : uncutLevelWeights[below] / total;
    }

    /** 擲一顆掉落的未切割輔助寶石階級：六成是最高階，其他平均分給比較低的階。 */
    public int rollSupportTier(final int monsterLevel, final RandomGenerator random) {
        final int max = supportTierFor(monsterLevel);
        if (max <= 1 || random.nextDouble() < 0.6) {
            return max;
        }
        return 1 + random.nextInt(max - 1);
    }

    /** 第 tier 階的未切割輔助寶石從幾級的怪物開始掉（沒有這一階回傳 1）。 */
    public int supportTierLevel(final int tier) {
        return tier >= 1 && tier <= supportTierLevels.length ? supportTierLevels[tier - 1] : 1;
    }

    /** 這個等級的怪物掉的未切割輔助寶石最高幾階。 */
    public int supportTierFor(final int monsterLevel) {
        int tier = 1;
        for (int i = 0; i < supportTierLevels.length; i++) {
            if (monsterLevel >= supportTierLevels[i]) {
                tier = i + 1;
            }
        }
        return tier;
    }

    /** 職業的起始技能；沒有回傳 null。 */
    public SkillDefinition starter(final String classId) {
        for (final SkillDefinition definition : skills.values()) {
            if (classId != null && classId.equals(definition.starter())) {
                return definition;
            }
        }
        return null;
    }
}
