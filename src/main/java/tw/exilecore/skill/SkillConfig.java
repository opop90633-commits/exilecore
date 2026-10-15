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
    /** 坐騎技能（2026-10）：技能代號 → 坐騎；要騎著才能放的技能。 */
    private Map<String, Mounts.Spec> mountSpecs = Map.of();
    /** 連珠（2026-10）：技能代號 → 「下一次弓／矛的投射物攻擊重複」的設定（skills.yml 的 barrage 區塊）。 */
    private Map<String, BarrageSpec> barrages = Map.of();
    /** 主要寶石（2026-10，照 PoE2 的 Meta Gem）：技能代號 → skills.yml 的 meta 區塊（{@link MetaSpec}）。 */
    private Map<String, MetaSpec> metas = Map.of();
    /** 龍捲風（2026-10，照 PoE2 的龍捲射擊）：技能代號 → skills.yml 的 tornado 區塊。 */
    private Map<String, TornadoSpec> tornados = Map.of();
    private Set<String> mountOnly = Set.of();
    /** 異質技能（variant_of）→ 原本的技能（2026-10：同一系列的技能面板上只能放一個）。 */
    private Map<String, String> variants = Map.of();
    /** skills.yml 裡沒有的新武器普攻（自己改過的檔案）。 */
    private List<String> missingDefaults = List.of();
    private Map<String, SupportDefinition> supports = Map.of();
    private Map<String, Integer> retiredSupports = Map.of();
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

    public void load(final Path dataFolder) throws IOException {
        final YamlNode root = YamlNode.load(dataFolder.resolve(FILE));
        final int[] parsedRequirements = parseRequirements(root);
        final NavigableMap<Integer, Double> parsedGrowth = parseGrowth(root.node("spell_damage_growth"));
        final double[] parsedUncutWeights = parseUncutWeights(root);
        final java.util.Map<Integer, Double> parsedUncutScale = parseUncutScale(root);
        final double extraLevelDamage = root.decimal("extra_level_damage", DEFAULT_EXTRA_LEVEL_DAMAGE);
        if (extraLevelDamage < 0 || extraLevelDamage > 100) {
            throw new IllegalArgumentException(FILE + ".extra_level_damage：要在 0 到 100 之間（百分比）");
        }
        final double minionExtraLevelDamage = root.decimal("minion_extra_level_damage", DEFAULT_MINION_EXTRA_LEVEL_DAMAGE);
        if (minionExtraLevelDamage < 0 || minionExtraLevelDamage > 100) {
            throw new IllegalArgumentException(FILE + ".minion_extra_level_damage：要在 0 到 100 之間（百分比）");
        }
        final Map<String, SkillDefinition> parsed = new LinkedHashMap<>();
        final Map<String, ResourceSpec> parsedResources = new HashMap<>();
        final Map<String, java.util.List<String>> parsedCommands = new HashMap<>(); // 2026-10：指令型召喚物技能
        final Map<String, Double> parsedParent = new HashMap<>();
        final Map<String, Mounts.Spec> parsedMounts = new HashMap<>();
        final Map<String, BarrageSpec> parsedBarrages = new HashMap<>();
        final Map<String, MetaSpec> parsedMetas = new HashMap<>();
        final Map<String, TornadoSpec> parsedTornados = new HashMap<>();
        final Set<String> parsedMountOnly = new java.util.HashSet<>();
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
            if (node.bool("requires_mount", false)) {
                parsedMountOnly.add(id);
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
            } else if (definition.mechanic() == Mechanic.META) {
                throw new IllegalArgumentException(node.path() + "：mechanic: meta 要寫 meta 區塊");
            }
            if (node.has("parent_damage")) {
                final double share = node.decimal("parent_damage", 0);
                if (share <= 0 || share > 3) {
                    throw new IllegalArgumentException(node.path() + ".parent_damage：要在 0 到 3 之間（原技能一下的倍數）");
                }
                parsedParent.put(id, share);
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
            if (!sub.hidden() || sub.isAttack() || !Set.of(Mechanic.STRIKE, Mechanic.PROJECTILE, Mechanic.NOVA, Mechanic.CONE,
                    Mechanic.CHAIN, Mechanic.IMPACT, Mechanic.ZONE).contains(sub.mechanic())) {
                throw new IllegalArgumentException(FILE + "：" + definition.id() + " 的 aura.on_kill 技能 " + sub.id()
                        + " 要是 hidden: true 的法術，運作方式是 strike、projectile、nova、cone、chain、impact 或 zone");
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
        }
        bossSkills.load(dataFolder);
        this.requirements = parsedRequirements;
        this.uncutLevelWeights = parsedUncutWeights;
        this.uncutLevelScale = parsedUncutScale;
        this.extraLevelGrowth = 1.0 + extraLevelDamage / 100.0;
        this.minionExtraLevelGrowth = 1.0 + minionExtraLevelDamage / 100.0;
        this.growth = parsedGrowth;
        this.skills = parsed;
        this.resources = parsedResources;
        for (final Map.Entry<String, java.util.List<String>> entry : parsedCommands.entrySet()) {
            for (final String who : entry.getValue()) {
                if (!who.equals("any") && (parsed.get(who) == null || parsed.get(who).summon() == null)) {
                    throw new IllegalArgumentException(FILE + "：" + entry.getKey() + ".command 的 " + who + " 不是召喚物技能");
                }
            }
        }
        this.commands = Map.copyOf(parsedCommands);
        this.parentDamage = Map.copyOf(parsedParent);
        this.mountSpecs = Map.copyOf(parsedMounts);
        this.barrages = Map.copyOf(parsedBarrages);
        this.metas = Map.copyOf(parsedMetas);
        this.tornados = Map.copyOf(parsedTornados);
        this.mountOnly = Set.copyOf(parsedMountOnly);
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
        this.retiredSupports = Map.copyOf(parsedRetired);
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

    private static int[] parseRequirements(final YamlNode root) {
        final Object raw = root.raw("level_requirements");
        if (!(raw instanceof List<?> list) || list.size() != SkillLoadout.MAX_LEVEL) {
            throw new IllegalArgumentException(FILE + ".level_requirements：要寫 " + SkillLoadout.MAX_LEVEL + " 個角色等級（技能等級 1 到 20）");
        }
        final int[] result = new int[SkillLoadout.MAX_LEVEL];
        for (int i = 0; i < result.length; i++) {
            if (!(list.get(i) instanceof Number number)) {
                throw new IllegalArgumentException(FILE + ".level_requirements[" + i + "]：應該是整數");
            }
            result[i] = number.intValue();
            if (result[i] < 1 || (i > 0 && result[i] < result[i - 1])) {
                throw new IllegalArgumentException(FILE + ".level_requirements：要從 1 以上遞增");
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
            final tw.exilecore.character.SkillLoadout.Entry other = loadout.skill(i);
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

    public BarrageSpec barrage(final String id) {
        return id == null ? null : barrages.get(id);
    }

    /** 要騎在坐騎上才能放的技能。 */
    public boolean requiresMount(final String id) {
        return id != null && mountOnly.contains(id);
    }

    /** 隱藏技能照觸發它的技能出傷害的倍數（0 = 不是，照自己的傷害效益）。 */
    public double parentDamage(final String id) {
        return id == null ? 0 : parentDamage.getOrDefault(id, 0.0);
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
        final double base = spellGrowth(requiredLevel(capped));
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

    public SupportDefinition support(final String id) {
        return id == null ? null : supports.get(id);
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
        return maxLevelFor(monsterLevel);
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
