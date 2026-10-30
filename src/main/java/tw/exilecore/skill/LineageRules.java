package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-27 照 PoE2 0.5：血脈輔助寶石（data/supports_lineage.yml）的資料與純規則（不依賴 Bukkit，測試直接用）：
 * 讀檔、能不能掛（各顆自己的限制）、建技能時的加成（孤星／雙兆／三兆的等級、願晶的品質、倍鏈的連鎖、推詠的投射物速度）、
 * 說明文字。執行時的機制在 {@link LineageMechanics}。
 */
public final class LineageRules {

    /** source 的前綴：lineage（一般）、lineage_rare（稀有）、lineage_pinnacle（巔峰）。 */
    public static final String SOURCE_PREFIX = "lineage";
    public static final String COMMON = "lineage";
    public static final String RARE = "lineage_rare";
    public static final String PINNACLE = "lineage_pinnacle";
    public static final List<String> SOURCES = List.of(COMMON, RARE, PINNACLE);

    /** 會留下殘骸的技能（拾息能掛的）：技能寫了 resource.remnant 的、幻身、骸餘饗、燼種、虹吸元素、魔力殘影。 */
    static final Set<String> REMNANT_SKILLS = Set.of("storm_orb", "frost_mine", "bone_cage", "snap", "ember_seed", "lingering_illusion",
            "grim_feast", "mana_remnants");
    /** 舉盾的技能（岳盾能掛的；照 PoE2 的 Active Block）。 */
    static final Set<String> RAISE_SHIELD = Set.of("raise_guard", "parry");

    /**
     * 血脈輔助的顏色（照 RePoE skill_gems.json 的 color；沒列的是白色）：r 力量、g 敏捷、b 智慧。
     * {@link SupportColours} 查不到時看這裡（分開寫，跟一般輔助的顏色表不打架）。
     */
    private static final Map<String, Character> COLOURS = colours(
            "r", "brutus_brain uruks_smelting cirels_cultivation daressos_passion ataluis_bloodletting einhars_beastrite kaoms_madness "
                    + "tawhoas_tending amanamus_tithe vruuns_inevitability uhtreds_exodus uhtreds_omen uhtreds_augury xophs_pyre uul_netols_embrace",
            "g", "eonyrs_thunder rathas_assault arjuns_medal dominus_grasp arakaalis_lust tacatis_ire garukhans_resolve tasalios_rhythm "
                    + "atziris_impatience tuls_stillness rakiatas_flow",
            "b", "oisins_oath vilentas_propulsion ahns_citadel doedres_undoing siones_temper romiras_requital diallas_desire kalisas_crescendo "
                    + "tecrods_revenge ixchels_torment atziris_allure zarokhs_refrain kurgals_leash kulemaks_dominion varashtas_blessing "
                    + "xibaquas_rending khatals_rejuvenation hayoxis_fulmination atziris_communion arbiters_ignition eshs_radiance zarokhs_revolt");

    private static Map<String, Character> colours(final String... pairs) {
        final Map<String, Character> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            for (final String id : pairs[i + 1].split(" ")) {
                if (!id.isBlank()) {
                    map.put(id, pairs[i].charAt(0));
                }
            }
        }
        return Map.copyOf(map);
    }

    /** 血脈輔助的顏色（r／g／b）；白色或不是血脈輔助回傳 0。 */
    public static char colour(final String supportId) {
        final Character colour = supportId == null ? null : COLOURS.get(supportId);
        return colour == null ? 0 : colour;
    }

    private LineageRules() {
    }

    /**
     * 掉落設定（supports_lineage.yml 的 drops）。
     *
     * @param minLevel       怪物等級至少多少才掉
     * @param mobChance      一般血脈：普通怪每隻的機率（魔法、稀有照 monsters.yml 的 quantity 放大）
     * @param mobRareChance  稀有血脈：同上
     * @param bossChance     一般血脈：地圖 Boss 每次擊殺
     * @param bossRareChance 稀有血脈：地圖 Boss 每次擊殺
     * @param pinnacleChance 巔峰血脈：終局、極境、巔峰 Boss 每次擊殺（中了從這一級抽一顆）
     * @param mobPinnacleChance  巔峰血脈：普通怪每隻（2026-10-27 站長：平均每隻 1/4,000,000）
     * @param bossPinnacleChance 巔峰血脈：地圖 Boss 每次擊殺
     */
    public record Drops(int minLevel, double mobChance, double mobRareChance, double bossChance, double bossRareChance, double pinnacleChance,
                        double mobPinnacleChance, double bossPinnacleChance) {
        /** 站長定的（2026-10-27）：一般 = 追逐級傳奇的 2 倍（平均每隻 1/500,000），稀有 1/2,000,000，巔峰 1/4,000,000（終局以上的 Boss 另外 0.5% 也拿掉了）。 */
        public static final Drops DEFAULT = new Drops(65, 9.643e-7, 2.411e-7, 0.00068, 0.00017, 0, 1.2055e-7, 0.000085);

        public Drops(final int minLevel, final double mobChance, final double mobRareChance, final double bossChance, final double bossRareChance,
                     final double pinnacleChance) {
            this(minLevel, mobChance, mobRareChance, bossChance, bossRareChance, pinnacleChance, 0, 0);
        }

        /** 這種稀有度的怪物的機率：普通怪的機率 ×（1 + monsters.yml 那種稀有度的 quantity）。 */
        public static double scaled(final double normalChance, final double rarityQuantity) {
            return Math.max(0, normalChance) * (1 + Math.max(0, rarityQuantity));
        }
    }

    /**
     * 讀 supports_lineage.yml：supports 加進 supports（代號不能重複，source 要是三種之一）、existing 把 supports.yml 已有的標成血脈掉落。
     *
     * @param skills 技能的代號（輔助的代號不能跟技能一樣）
     * @return 掉落設定
     */
    static Drops load(final YamlNode root, final Map<String, SupportDefinition> supports, final Set<String> skills) {
        final String file = SkillConfig.LINEAGE_FILE;
        final YamlNode section = root.node("supports");
        for (final String id : section.keys()) {
            if (supports.containsKey(id)) {
                throw new IllegalArgumentException(file + "：血脈輔助 " + id + " 的代號 supports.yml 已經有了");
            }
            if (skills.contains(id)) {
                throw new IllegalArgumentException(file + "：輔助寶石的代號不能跟技能一樣（" + id + "）");
            }
            final SupportDefinition definition = SupportDefinition.parse(id, section.node(id));
            if (!SOURCES.contains(definition.source())) {
                throw new IllegalArgumentException(file + "：" + id + " 的 source 要是 " + String.join("、", SOURCES));
            }
            supports.put(id, definition);
        }
        final YamlNode existing = root.node("existing");
        for (final String id : existing.keys()) {
            final SupportDefinition definition = supports.get(id);
            final String source = existing.string(id, "").trim().toLowerCase(Locale.ROOT);
            if (definition == null) {
                throw new IllegalArgumentException(file + " existing." + id + "：supports.yml 沒有這個輔助");
            }
            if (!SOURCES.contains(source)) {
                throw new IllegalArgumentException(file + " existing." + id + "：要寫 " + String.join("、", SOURCES));
            }
            supports.put(id, definition.withSource(source));
        }
        final YamlNode drops = root.node("drops");
        final Drops d = Drops.DEFAULT;
        return new Drops(Math.max(1, drops.integer("min_level", d.minLevel())),
                clamp01(drops.decimal("mob_chance", d.mobChance())), clamp01(drops.decimal("mob_rare_chance", d.mobRareChance())),
                clamp01(drops.decimal("boss_chance", d.bossChance())), clamp01(drops.decimal("boss_rare_chance", d.bossRareChance())),
                clamp01(drops.decimal("pinnacle_chance", d.pinnacleChance())),
                clamp01(drops.decimal("mob_pinnacle_chance", d.mobPinnacleChance())),
                clamp01(drops.decimal("boss_pinnacle_chance", d.bossPinnacleChance())));
    }

    private static double clamp01(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    // =====================================================================
    // 能不能掛
    // =====================================================================

    /** 血脈輔助各自的限制（requires、excludes 以外的）；可以掛回傳 null。 */
    static String refusal(final SupportDefinition support, final SkillDefinition skill) {
        if (!support.lineage()) {
            return null;
        }
        final Set<String> tags = skill.tags();
        return switch (support.id()) {
            case "ahns_citadel" -> skill.params() != null && skill.params().zone() != null && skill.params().zone().line()
                    ? null : "要立牆的技能（焰幕、霜壁）";
            case "amanamus_tithe", "tecrods_revenge" -> persistentMinion(skill) ? null : "要常駐的召喚物技能";
            case "brutus_brain" -> skill.summon() != null ? null : "要叫召喚物的技能";
            case "arbiters_ignition" -> tags.contains(Tags.FIRE) ? null : "要火焰法術";
            case "arbiters_reach", "her_declaration", "seraphs_heart" -> persistentBuff(skill) ? null : "要常駐的增益技能（光環、捷光環這類）";
            case "atziris_communion" -> persistentBuff(skill) && skill.aura().reserve() > 0 ? null : "要保留精魂的常駐技能（叫召喚物的不行）";
            case "rathas_assault", "arjuns_medal" -> SkillService.ammoSkill(skill) ? null : "要用彈匣的弩技能";
            case "ataluis_bloodletting" -> skill.mechanic() == Mechanic.AURA ? "保留精魂的技能不行" : skill.mana().last() <= 0 ? "不花魔力的技能不行" : null;
            case "atziris_allure", "kalisas_crescendo", "doedres_undoing", "hayoxis_fulmination" ->
                    skill.mechanic() == Mechanic.MARK && tags.contains("curse") ? null : "要詛咒技能";
            case "breachlords_amalgam" -> skill.mechanic() != Mechanic.PROJECTILE ? "要真的射出投射物的技能"
                    : !tags.contains(Tags.COLD) && !tags.contains(Tags.LIGHTNING) ? "要冰冷或閃電技能"
                    : skill.cooldown() > 0 ? "本來就有冷卻的技能不行" : null;
            case "zarokhs_refrain", "tuls_avalanche" -> !cascadable(skill) ? "要可以連瀑的範圍法術（以自己為中心、落在準心的，或詛咒）"
                    : skill.cooldown() > 0 ? "本來就有冷卻的技能不行" : sealed(skill) ? "本來就會蓄封印的技能不行" : null;
            case "zarokhs_revolt" -> skill.cooldown() > 0 ? "本來就有冷卻的技能不行" : sealed(skill) ? "本來就會蓄封印的技能不行"
                    : skill.castTime() <= 0 || skill.mechanic() == Mechanic.AURA || skill.summon() != null ? "要會施放的法術" : null;
            case "eshs_prowess", "prototype_seventeen", "voranas_siege", "morrigans_insight" ->
                    skill.dealsDamage() ? null : "不會造成傷害的技能不行";
            case "medveds_felling", "vruuns_inevitability" -> skill.isAttack() ? null : "要近戰攻擊";
            case "olroths_hubris" -> skill.mana().last() <= 0 ? "不花魔力的技能不行" : null;
            case "siones_temper", "vilentas_propulsion" -> !tags.contains(Tags.SPELL) ? "要投射物法術"
                    : skill.mechanic() != Mechanic.PROJECTILE ? "要真的射出投射物的技能" : null;
            case "styrns_ferocity" -> Warcries.SHIELD_ATTACKS.contains(skill.id()) ? null : "要盾牌技能（盾衝、共鳴之盾、盾牆）";
            case "styrns_mountain" -> RAISE_SHIELD.contains(skill.id()) ? null : "要舉盾的技能（架盾、招架）";
            case "tasalios_rhythm" -> perfectTiming(skill) ? null : "要有完美時機的技能";
            case "tricksters_shard" -> skill.castTime() <= 0 || skill.mechanic() == Mechanic.AURA || skill.summon() != null
                    || skill.mechanic() == Mechanic.META ? "要不是瞬間施放的法術" : null;
            case "uhtreds_constellation" -> skill.castTime() <= 0 && !skill.isAttack() ? "瞬間施放的技能不行" : null;
            case "xibaquas_rending" -> skill.mechanic() == Mechanic.LEAP ? null : "要跳躍的技能（躍擊這類）";
            case "khatals_rejuvenation" -> generatesRemnants(skill) ? null : "要會留下殘骸的技能";
            default -> null;
        };
    }

    /** 常駐的召喚物（沒有持續時間的召喚：殭屍、骷髏、夥伴…）。 */
    static boolean persistentMinion(final SkillDefinition skill) {
        return skill.summon() != null && skill.summon().permanent();
    }

    /** 常駐的增益：開著就一直有效的光環、捷光環（不是叫召喚物、不是主要寶石）。 */
    static boolean persistentBuff(final SkillDefinition skill) {
        return skill.mechanic() == Mechanic.AURA && skill.aura() != null && skill.summon() == null && skill.aura().perMinion() == null
                && !skill.tags().contains(Tags.META);
    }

    /**
     * PoE2 的 Cascadable。2026-10-28 照 PoE2：改看 SkillCascade（跟連瀑同一套：詛咒、焰幕、霜壁、骸爆可以；雷雲、烈陽珠、燼種、疫潮這些不行）；
     * 這次之前只看形狀（以自己為中心或落在準心的範圍法術，不是引導的）。
     */
    static boolean cascadable(final SkillDefinition skill) {
        return SkillCascade.cascadable(skill);
    }

    /** 本來就會蓄封印的技能（技能自己寫了 seal.max）。 */
    static boolean sealed(final SkillDefinition skill) {
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(StatKeys.SEAL_MAX) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 有完美時機的技能（蓄勁一擊、穿雲箭…：SupportDefinition.PERFECT_TIMING）。 */
    static boolean perfectTiming(final SkillDefinition skill) {
        return SupportDefinition.PERFECT_TIMING.contains(skill.id());
    }

    /** 會留下殘骸的技能。 */
    static boolean generatesRemnants(final SkillDefinition skill) {
        if (REMNANT_SKILLS.contains(skill.id())) {
            return true;
        }
        for (final Modifier modifier : skill.modifiers()) {
            if ((modifier.stat().equals(SkillTriggers.SIPHON_REMNANT) || modifier.stat().equals(ChargeEffects.REMNANT_EXTRA)) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // 建技能時
    // =====================================================================

    /**
     * 孤星、雙兆、三兆：照技能上「其他」能生效的輔助顆數給等級。supports 要是篩過名額、真的生效的那份（{@link SkillBuild#effectiveSupports}）；
     * 2026-10-27：同一個代號只算一次，宿主／引星合成的（meta.、host.、lineage. 開頭）不算——這次之前都算進去，少掛一顆就拿到等級。
     */
    static int conditionalLevels(final List<SupportDefinition> supports, final SkillDefinition definition) {
        final java.util.Set<String> counted = new java.util.HashSet<>();
        for (final SupportDefinition support : supports) {
            if (support != null && !synthetic(support) && support.supports(definition)) {
                counted.add(support.id());
            }
        }
        final int active = counted.size();
        int levels = 0;
        for (final SupportDefinition support : supports) {
            if (support == null || synthetic(support) || !support.lineage() || !support.supports(definition)) {
                continue;
            }
            final int others = active - 1;
            levels += (int) Math.round(total(support, others == 0 ? LineageKeys.LEVELS_IF_ALONE
                    : others == 1 ? LineageKeys.LEVELS_IF_ONE : others == 2 ? LineageKeys.LEVELS_IF_TWO : ""));
        }
        return levels;
    }

    /**
     * 2026-10-27（登入遷移）：一個技能只能掛一顆血脈輔助；舊存檔（這次把 8 顆改成血脈之前切得出來）同一個技能掛了好幾顆的，
     * 回傳第二顆以後那幾孔（照孔的順序，第一顆留著）。
     */
    static List<Integer> extraLineageSockets(final tw.exilecore.character.SkillLoadout.Entry entry,
                                             final java.util.function.Predicate<String> isLineage) {
        final List<Integer> extra = new java.util.ArrayList<>();
        boolean seen = false;
        for (int i = 0; i < entry.sockets(); i++) {
            final String support = entry.support(i);
            if (support.isEmpty() || entry.spellSocket(i) || !isLineage.test(support)) {
                continue;
            }
            if (seen) {
                extra.add(i);
            }
            seen = true;
        }
        return extra;
    }

    /** 程式合成的輔助（引星的 meta.、宿主的 host.、碎星的 lineage.），不是玩家掛的寶石。 */
    static boolean synthetic(final SupportDefinition support) {
        final String id = support.id();
        return id.startsWith("meta.") || id.startsWith("host.") || id.startsWith("lineage.");
    }

    /** 願晶：品質 +x%（照技能寶石自己的品質效果）。 */
    static List<Modifier> buildModifiers(final SupportDefinition support, final SkillDefinition definition) {
        if (!support.lineage()) {
            return List.of();
        }
        final int quality = (int) Math.round(total(support, LineageKeys.QUALITY));
        return quality > 0 ? SkillService.qualityModifiers(definition, quality) : List.of();
    }

    /** 推詠：施法速度的增加／減少有 x% 也套在投射物速度上（回傳要加進投射物速度倍率的部分）。 */
    static double castSpeedAsProjectileSpeed(final StatSheet effective, final Set<String> context) {
        final double share = effective.flat(LineageKeys.CAST_TO_PROJECTILE_SPEED, context);
        if (share <= 0) {
            return 0;
        }
        return share / 100.0 * effective.increased(StatKeys.CAST_SPEED, context) * effective.more(StatKeys.SKILL_PROJECTILE_SPEED, context);
    }

    /** 倍鏈：連鎖次數更多 x%。 */
    static int chains(final StatSheet effective, final Set<String> context, final int chains) {
        final double more = effective.more(LineageKeys.CHAINS_MORE, context);
        return more == 1 || chains <= 0 ? chains : (int) Math.round(chains * Math.max(0, more));
    }

    /** 血契：這個光環保留的最大生命 %（每 1 點基礎精魂 x/100 %）；沒掛回傳 0。 */
    static double communionLife(final SkillBuild build) {
        if (build == null || build.definition().aura() == null) {
            return 0;
        }
        double per = 0;
        for (final SupportDefinition support : build.supports()) {
            per += total(support, LineageKeys.LIFE_RESERVE);
        }
        return per <= 0 ? 0 : build.definition().aura().reserve() * per / 100.0;
    }

    /** 這顆輔助某個屬性的 FLAT 加總（MORE、INCREASED 也加，換回百分比）。 */
    static double total(final SupportDefinition support, final String stat) {
        double total = 0;
        if (support == null) {
            return 0;
        }
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.type() == ModType.FLAT ? modifier.value() : modifier.value() * 100;
            }
        }
        return total;
    }

    /** 一串輔助裡某個屬性的 FLAT 加總。 */
    static double total(final List<SupportDefinition> supports, final String stat) {
        double total = 0;
        for (final SupportDefinition support : supports) {
            total += total(support, stat);
        }
        return total;
    }

    // =====================================================================
    // 說明文字
    // =====================================================================

    /** 血脈輔助的屬性說明（{@link ModifierText#describe} 用）；不是這裡的回傳 null。 */
    static String text(final Modifier modifier) {
        final double value = modifier.value();
        final String n = ModifierText.number(Math.abs(modifier.type() == ModType.FLAT ? value : value * 100));
        return switch (modifier.stat()) {
            case LineageKeys.LEVELS_IF_ALONE -> "沒有掛其他輔助時 +" + n + " 等級";
            case LineageKeys.LEVELS_IF_ONE -> "剛好還掛著 1 顆其他輔助時 +" + n + " 等級";
            case LineageKeys.LEVELS_IF_TWO -> "剛好還掛著 2 顆其他輔助時 +" + n + " 等級";
            case LineageKeys.QUALITY -> "品質 +" + n + "%";
            case LineageKeys.CHAINS_MORE -> n + "% " + (value < 0 ? "更少" : "更多") + "連鎖次數";
            case LineageKeys.CAST_TO_PROJECTILE_SPEED -> "施法速度的增加與減少有 " + n + "% 也套用在投射物速度";
            case LineageKeys.COOLDOWN_USES -> "冷卻可以多存 " + n + " 次";
            case LineageKeys.WALL_FISSURE -> "牆改成沿著一道往前的裂縫立起來";
            case LineageKeys.WALL_LENGTH -> (value < 0 ? "減少 " : "增加 ") + n + "% 牆的長度";
            case LineageKeys.EXTRA_FISSURES -> "多裂出 " + n + " 道裂縫";
            case LineageKeys.LIFE_RESERVE -> "不保留精魂，改成每 1 點基礎精魂保留 " + ModifierText.number(value / 100.0) + "% 最大生命";
            case LineageKeys.GLORY_REQUIRED -> n + "% " + (value < 0 ? "更少" : "更多") + "需要的榮耀";
            case LineageKeys.SEAL_CAST_PERCENT -> "每過施法時間的 " + n + "% 蓄一個封印";
            case LineageKeys.REPEAT_LESS -> "解開封印多放的那幾次傷害更少 " + n + "%";
            case LineageKeys.PHYS_PER_LIFE_COST -> "每 20 點生命消耗獲得 " + n + "% 傷害為額外物理傷害（最多 40%）";
            case LineageKeys.LOSE_ON_USE -> "每次用都失去 " + n + "% 最大生命、魔力與能量護盾";
            case LineageKeys.LIFE_PER_5_RAGE -> "每次用時，你每 5 點怒氣失去 " + n + "% 最大生命";
            case LineageKeys.SPEED_PER_5_RAGE -> "你每 5 點怒氣增加 " + n + "% 攻擊速度";
            case LineageKeys.WILDSHARD -> "每放一次獲得一層碎星，每層 " + n + "% 機率多往四周射 8 發（射了就失去全部碎星）";
            case LineageKeys.COMBO_AREA -> "用其他近戰攻擊打中時累積連擊（最多 10），放的時候用掉，每點增加 " + n + "% 範圍";
            case LineageKeys.BLOCK_SEALS -> "每格擋 2 次蓄一個封印（最多 " + n + " 個），每個封印回響一次";
            case LineageKeys.ECHOES -> "回響 " + n + " 次";
            case LineageKeys.ECHO_AREA -> (value < 0 ? "減少 " : "增加 ") + n + "% 回響的範圍";
            case LineageKeys.MIMIC -> "每放一次留下一個幻身（最多 " + n + " 個，5 秒），到上限時全部一起放（傷害更少 50%）";
            case LineageKeys.OFFERING_CASCADE -> "你的範圍法術也在祭柱那裡放一次（" + n + "% " + (value < 0 ? "更少" : "更多") + "傷害）";
            case LineageKeys.RANDOM_AREA -> "觸發出來的技能範圍在更少 40% 到更多 50% 之間隨機";
            case LineageKeys.CHALICE -> "用的時候獲得滿溢聖杯（每 1 秒冷卻 " + n + " 秒：藥劑每秒 +2 次充能、回復增加 30%）";
            case LineageKeys.MANA_FLASK_EFFICIENCY -> "魔力藥劑生效時增加 " + n + "% 魔力消耗效率"; // 2026-10-27 照 PoE2 用語；這次之前「魔力消耗少 x%」
            case LineageKeys.HUBRIS -> "另外消耗 " + n + "% 最大魔力，附加這筆消耗 15% 的物理與冰冷傷害";
            case LineageKeys.UNHOLY_ON_COMMAND -> "指揮召喚物時，你和聽命的召喚物獲得不潔之力 " + n + " 秒（30% 額外混沌傷害）";
            case LineageKeys.RHYTHM -> "完美時機時獲得一層律勁（最多 5 層），每層更多 " + n + "% 傷害、完美時機的區間更短 30%";
            case LineageKeys.CURSE_REFLECT -> "詛咒不受詛咒上限限制，但也詛咒你自己";
            case LineageKeys.PACIFY -> "詛咒最後 " + n + "% 的時間讓敵人不能造成傷害";
            case LineageKeys.TOAD_GROUND -> "詛咒改成留下詛咒地面，裡面生出巫蟾（每塊最多 " + n + " 隻）撲向敵人炸開";
            case LineageKeys.ANNIHILATION_GROUND -> "詛咒改成留下詛咒地面，撐 " + n + " 秒後落下湮滅光柱";
            case LineageKeys.ALLY_GUARD -> "開著時每 6 秒讓身邊的隊友獲得你最大生命 " + n + "% 的守護（4 秒）";
            case LineageKeys.INTIMIDATE_PRESENCE -> "開著時，走進你身邊的敵人被恫嚇 " + n + " 秒";
            case LineageKeys.SERAPH -> "開著時，敵人擊中你有 " + n + "% 機率把你的抗性當成 90%";
            case LineageKeys.ABYSSAL_ON_MINION_DEATH -> "召喚物死掉時 " + n + "% 機率讓你獲得一個怪物詞綴 20 秒（最多 3 個）";
            case LineageKeys.MINION_UNDAMAGEABLE -> "召喚物不會受到傷害";
            case LineageKeys.COMPANION_BLAZE -> "夥伴致盲並點燃身邊 2 格的敵人（像造成牠最大生命 " + n + "% 的基礎火焰傷害）"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
            case LineageKeys.COMPANION_SHIELD -> "你受到的擊中有 " + n + "% 先由夥伴承受，你補償其中 200% 為生命";
            case LineageKeys.GIGANTIC -> "夥伴變巨大；你每 10 點閃避、偏斜給牠 " + ModifierText.number(value / 10.0) + " 點";
            case LineageKeys.UNDYING -> "召喚物生命降到 0 後再戰 " + n + " 秒（攻擊與施法速度增加 40%）";
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（林樁 4 公尺）
            case LineageKeys.TOTEM_GROVE -> "4 格內每有一根你的其他圖騰（最多 5 根），更多 " + n + "% 傷害、增加 "
                    + ModifierText.number(value / 2) + "% 範圍、每秒回復 " + ModifierText.number(value / 10) + "% 生命";
            case LineageKeys.TOTEM_EXPLODE -> "圖騰倒下時炸開，造成它最大生命 " + n + "% 的物理傷害（3 格）"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
            case LineageKeys.REMNANT_CDR -> "撿到殘骸時冷卻回復速度增加 " + n + "%（最多 8 層，各 10 秒）";
            case LineageKeys.SHIELD_BLOCK_MORE -> "舉盾時格擋過，接下來 4 秒其他近戰技能更多 " + n + "% 傷害";
            case LineageKeys.SHIELD_STUN_THRESHOLD -> "舉盾時暈眩門檻更多 " + n + "%";
            case LineageKeys.DODGE_LOAD -> "翻滾時裝 " + n + " 發";
            case LineageKeys.NO_RELOAD -> "不會自己裝填";
            case LineageKeys.LANDING_DETONATE -> "落地時引爆 3.5 格內的屍體（每具造成牠最大生命 " + n + "% 的火焰傷害）"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
            case LineageKeys.PER_POISON -> "敵人身上每層中毒，擊中更多 " + n + "%（最多 40%）";
            case LineageKeys.ARCHON_ON_IGNITE -> "點燃敵人時獲得元素執政";
            case LineageKeys.LOAD_ON_KILL -> "擊殺時 " + n + "% 機率替所有弩技能各裝一發";
            case LineageKeys.BREAK_ON_FREEZE -> "凍結或電擊敵人時破掉牠 " + n + "% 的護甲；完全破甲也讓冰冷、閃電擊中更痛";
            case LineageKeys.LOW_LIFE_ONLY -> "只能傷害低血（35% 以下）的敵人";
            case LineageKeys.STEAL_TWO -> "打死稀有怪物時獲得牠兩個詞綴 " + n + " 秒";
            case LineageKeys.LIGHTNING_ELECTROCUTES -> "閃電傷害也累積電擊；不能感電";
            case LineageKeys.VOLTAIC -> "打死電擊中的敵人時炸開（牠最大生命 " + n + "% 起的閃電傷害，照技能等級）";
            case LineageKeys.LIGHTNING_EXTREMES -> "閃電傷害只擲出最小值或最大值";
            case LineageKeys.CHAOS_SHOCKS -> "混沌傷害也算進感電";
            case LineageKeys.CHAOS_FREEZES -> "混沌傷害也累積凍結";
            case LineageKeys.CHAOS_IGNITES -> "混沌傷害也算進點燃";
            case LineageKeys.PHYSICAL_FREEZES -> "物理傷害也累積凍結";
            case LineageKeys.BIFURCATE -> "暴擊擲兩次（兩次都成功時暴擊加成算兩次）";
            case LineageKeys.CRIT_CAP -> "暴擊率最多 " + n + "%";
            case LineageKeys.RAGE_ON_MELEE_HIT -> "近戰擊中時獲得最大怒氣 " + n + "% 的怒氣";
            case LineageKeys.CONSUME_FREEZE -> "用掉敵人的凍結，更多 " + n + "% 傷害，觸發自然交換";
            case LineageKeys.MANA_LEECH_ELEMENTAL -> "魔力偷取照元素傷害算，不照物理傷害";
            case LineageKeys.CRIT_PER_5_RAGE -> "你每 5 點怒氣增加 " + n + "% 暴擊率；暴擊時失去全部怒氣";
            case LineageKeys.CRUSH_ON_HIT -> "擊中時壓碎敵人 " + n + " 秒";
            case LineageKeys.CRUSHED_BREAK -> "擊中壓碎中的敵人時破甲，等於物理傷害的 " + n + "%";
            case LineageKeys.POISON_FASTER_PER_RAGE -> "你每點怒氣讓中毒的傷害快 " + n + "%";
            case LineageKeys.PER_COOLDOWN_SKILL -> "最近 8 秒每用過一種不同的冷卻技能，更多 " + n + "% 傷害";
            case LineageKeys.LIFE_FLASK_MORE -> "生命藥劑生效時更多 " + n + "% 傷害";
            case LineageKeys.PHYS_TAKEN_ON_BREAK -> "完全破甲時讓敵人永久受到的物理傷害增加 " + n + "%（最多 20%）";
            case LineageKeys.CHAOS_BREAKS -> "混沌傷害也破甲，等於混沌傷害的 " + n + "%";
            case LineageKeys.PER_COMMAND -> "最近 15 秒每用過一種不同的指令技能，更多 " + n + "% 傷害";
            case LineageKeys.VS_ISOLATED -> "對孤立的敵人（6 格內沒有其他敵人）擊中更多 " + n + "%"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
            default -> null;
        };
    }
}
