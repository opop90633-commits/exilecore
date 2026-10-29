package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-26 照 PoE2 0.5：技能寶石的屬性需求（力量／敏捷／智慧），這次之前技能寶石只有角色等級需求。
 * <ul>
 *   <li>屬性的分配照 RePoE（poe2 skill_gems.json 的 requirement_weights）：紅＝力量、綠＝敏捷、藍＝智慧，混色的各一半
 *       （護時是 75 力量／25 智慧）。我們自己的技能（專屬掉落、指令、自創的常駐）照效果歸類：法術、召喚、詛咒是智慧，
 *       投射物攻擊、印記是敏捷，近戰猛擊、戰吼是力量；德魯伊的變身技能照原作的變身寶石是力量＋智慧。</li>
 *   <li>需求照 poe2db 每顆寶石的等級表：需求 = round(4 + 1.7 × 需求等級 × (分配 ÷ 100)^0.9)
 *       （火球 20 級 157 智慧、破甲彈 20 級 86／86、護時 20 級 122 力量／48 智慧，全部對得上；1 級的需求等級算 0）。
 *       寶石等級超過 20（腐化的 21 級）照 20 級的需求（poe2db 21 級以上沒有需求表）。</li>
 *   <li>輔助寶石（PoE2 0.3 起）沒有自己的需求，插上去讓那顆技能寶石的需求 +5（紅＋5 力量、綠＋5 敏捷、藍＋5 智慧，
 *       白色的不加；poe2db 每顆輔助都寫 Support Requirements: +5 Str／Dex／Int，不分階）。</li>
 *   <li>需求不足（照 PoE2）：那顆技能寶石不能用——按鍵放不出來（提示缺什麼）、光環／常駐／精魂召喚關掉，技能面板灰掉並寫出原因。
 *       宿主寶石孔裡的技能需求不足時，宿主也不能用（2026-10-27：孔裡好幾個時，全部都不足才算）。武器賦予的技能不是寶石，沒有屬性需求。</li>
 *   <li>天賦樹與昇華的「減少 x% 裝備與技能寶石的屬性需求」（{@link TreeEffectKeys#ITEM_ATTR_REQUIREMENT}）也乘在寶石上；
 *       嵌晶戰團的應變能力（{@link #HIGHEST}）：寶石的屬性需求可以用你最高的那個屬性滿足。</li>
 * </ul>
 * 資料寫在這裡（不在 skills.yml 逐行加欄位），新技能沒列在表上的照標籤自動歸類（{@link #fallback}）。
 */
public final class GemAttributes {

    /** FLAT 1：寶石的屬性需求可以用你最高的屬性滿足（嵌晶戰團・應變能力，PoE2 Adaptive Capability）。 */
    public static final String HIGHEST = "gem.attr_req_highest";

    /** 每顆有顏色的輔助寶石給技能寶石加的需求（PoE2 0.5：Support Requirements +5）。 */
    public static final int SUPPORT_REQUIREMENT = 5;

    /** 屬性分配（百分比，0–100）。 */
    public record Weights(int str, int dex, int intelligence) {
        public boolean none() {
            return str <= 0 && dex <= 0 && intelligence <= 0;
        }

        /** 主要的顏色（r／g／b；分配一樣多照表上寫的順序；沒有屬性回傳 0）。 */
        public char primary() {
            if (none()) {
                return 0;
            }
            if (str >= dex && str >= intelligence) {
                return SupportColours.RED;
            }
            return dex >= intelligence ? SupportColours.GREEN : SupportColours.BLUE;
        }

        /** 有分配到的顏色（r、g、b 的順序）。 */
        public List<Character> colours() {
            final List<Character> list = new ArrayList<>(3);
            if (str > 0) {
                list.add(SupportColours.RED);
            }
            if (dex > 0) {
                list.add(SupportColours.GREEN);
            }
            if (intelligence > 0) {
                list.add(SupportColours.BLUE);
            }
            return list;
        }
    }

    /** 屬性需求。 */
    public record Requirement(int str, int dex, int intelligence) {
        public static final Requirement NONE = new Requirement(0, 0, 0);

        public Requirement plus(final Requirement other) {
            return new Requirement(str + other.str, dex + other.dex, intelligence + other.intelligence);
        }

        /** 乘上「減少 x% 屬性需求」的倍率（四捨五入，跟裝備一樣）。 */
        public Requirement scaled(final double scale) {
            if (Math.abs(scale - 1) < 1e-9) {
                return this;
            }
            return new Requirement((int) Math.round(str * scale), (int) Math.round(dex * scale), (int) Math.round(intelligence * scale));
        }

        public boolean isEmpty() {
            return str <= 0 && dex <= 0 && intelligence <= 0;
        }
    }

    private static final Map<String, Weights> CODES = Map.of(
            "S", new Weights(100, 0, 0), "D", new Weights(0, 100, 0), "I", new Weights(0, 0, 100),
            "SD", new Weights(50, 50, 0), "DI", new Weights(0, 50, 50), "SI", new Weights(50, 0, 50),
            "S75I25", new Weights(75, 0, 25));

    /** 技能代號 → 分配。有 PoE2 對應的照 RePoE（poe2_skills.csv／gem_quality.yml 寫的原作名）；自創的照效果歸類（見類別說明）。 */
    private static final Map<String, Weights> SKILLS = new HashMap<>();

    static {
        table(
            entry("S", "leap_slam earthquake boneshatter ancestral_totem raise_guard shockwave_totem herald_of_ash fortifying_cry "
                    + "infernal_cry seismic_cry rolling_slam volcanic_fissure armour_breaker shield_rush shatter_spikes magma_spray "
                    + "true_strike stampede forge_hammer hammer_of_gods sunder supercharged_slam resonating_shield "
                    + "berserk overwhelming_presence scavenged_plating eternal_rage iron_ward shield_wall magma_barrier "
                    // 自創／專屬：近戰猛擊、戰吼（架盾是盾牌賦予的，原作沒有需求，照效果算力量）
                    + "ancestral_cry shrine_prayer chain_lash bone_fissure "),
            entry("D", "caustic_arrow rain_of_arrows lightning_arrow escape_shot ice_shot tornado_shot lightning_spear whirling_slash "
                    + "whirlwind_lance hunters_mark beast_companion herald_of_thunder thunder_arrow frost_volley storm_javelin "
                    + "spear_thicket rapid_thrust shock_arrow bramble_arrow venom_burst_arrow spiral_volley blast_arrow chain_arrow "
                    + "gale_spear serpent_fury prism_rend thunder_leap blood_harvest sunfire_spear explosive_spear fangs_of_frost "
                    + "snipe disengage parry glacial_lance primal_strikes cull_the_weak toxic_growth magnetic_salvo voltaic_mark "
                    + "bloodhounds_mark ice_tipped_arrows linked_volley toxic_domain alchemists_boon trail_of_caltrops summon_mount "
                    + "mirage_archer plague_bearer wind_dancer combat_frenzy herald_of_plague rake "
                    // 自創／專屬：投射物攻擊、閃擊、印記（招架是小盾賦予的，原作沒有需求，照效果算敏捷）
                    + "lightning_rod rift_slash starfall_volley nameless_backstab execution_mark "),
            entry("SD", "explosive_bolt permafrost_bolts rapid_fire explosive_grenade gas_grenade armour_piercing_bolt "
                    + "concussion_grenade galvanic_bolts siege_salvo hail_rounds defiance_banner piercing_rounds incendiary_shot "
                    + "frost_bolt_round storm_bolts oil_grenade voltaic_grenade cluster_grenade attrition war_banner dread_banner "
                    + "glacial_bolt plasma_blast herald_of_blood emergency_reload shard_scavenger fragmentation_rounds "
                    + "stormblast_bolts siege_ballista artillery_ballista "),
            entry("DI", "tempest_flurry ice_strike storm_wave killing_palm tempest_bell herald_of_ice gale_sweep vault_strike "
                    + "whirling_rush siphon_strike reeling_palm frost_palm frost_wave storm_staff ruin_mantra falling_thunder "
                    + "glacial_cascade flicker_strike gathering_storm freezing_mark ghost_dance lingering_illusion charge_regulation "
                    + "elemental_trinity frozen_locus meta_dodge meta_elemental_invocation meta_reaper_invocation "
                    + "meta_barrier_invocation "),
            entry("I", "fireball frost_nova spark arc comet flame_wall detonate_dead raise_zombie raging_spirits vulnerability_curse "
                    + "despair_curse elemental_weakness_curse enfeeble_curse temporal_curse blood_offering bone_offering "
                    + "soul_offering firestorm ember_volley storm_orb lightning_pillar hex_burst bone_storm "
                    + "skeletal_brute skeletal_archers skeletal_pyromancer skeletal_stormcaller frost_aegis frost_mine ice_wall "
                    + "storm_blink inferno_blast sun_orb ember_seed winter_eye bone_spikes dark_idol skeletal_reaver "
                    + "skeletal_frostcaller mana_storm flame_stream bound_spectre skeletal_cleric frostbolt ball_lightning "
                    + "essence_drain contagion frost_darts profane_ritual withering_presence blink mana_remnants ravenous_swarm "
                    // 自創／專屬：法術、召喚物的指令、引星
                    + "elemental_pulse infusion_bolt archmage "
                    + "elemental_conflux bone_cage snap convalescence grim_feast sacrifice siphon_elements void_meteor oblivion_ray "
                    + "storm_labyrinth fourfold_ray tide_call abyssal_pull dawn_pillar coin_barrage rift_hand breach_ring "
                    + "blood_altar tribute_spears mirror_shards mirror_step abyss_grasp meta_crit meta_ailment meta_minion_death "
                    + "blasphemy "), // 2026-10-29 照 PoE2 Blasphemy（咒環）：智慧
            // 德魯伊：原作的變身寶石是力量＋智慧（撼山爪、疾爪、血牙在原作是形態賦予的，照同一型的變身技能算）
            entry("SI", "bear_maul bear_slam mountain_fury rampage walking_calamity wolf_shred lunar_assault wolf_rend pounce "
                    + "cross_slash arctic_howl ferocious_roar lunar_blessing wyvern_rend wing_blast rolling_magma devour oil_barrage "
                    + "flame_breath thunderstorm primal_tornado thrashing_vines volcano entangle barkskin briarpatch savage_fury "
                    + "wolf_pack totem_casting "),
            entry("S75I25", "time_of_need "));
    }

    private GemAttributes() {
    }

    private static Map.Entry<String, String> entry(final String code, final String ids) {
        return Map.entry(code, ids);
    }

    @SafeVarargs
    private static void table(final Map.Entry<String, String>... entries) {
        for (final Map.Entry<String, String> entry : entries) {
            final Weights weights = CODES.get(entry.getKey());
            for (final String id : entry.getValue().split(" ")) {
                if (!id.isBlank()) {
                    SKILLS.put(id, weights);
                }
            }
        }
    }

    /**
     * 是不是技能寶石（有屬性需求）：隱藏的、武器賦予的（source: weapon）、昇華天賦賦予的（source: ascendancy，2026-10-26）不是寶石。
     */
    public static boolean isGem(final SkillDefinition definition) {
        return definition != null && !definition.hidden() && !definition.droppedBy("weapon") && !definition.droppedBy("ascendancy");
    }

    /**
     * 2026-10-27：技能寶石的顏色（0 紅、1 綠、2 藍；主要的那個屬性，分配一樣多照力量、敏捷、智慧的順序）；表上沒有的回傳 -1。
     * 昇華・嵌晶的寶石顏色（{@link tw.exilecore.ascendancy.AscBuild#skillColour}）以這裡為準，gem_colours.yml 的 skills 由
     * tools/gen_gem_colours.py 照這張表產生（這次之前那邊照標籤猜，長棍技能、架盾、獵殺印記、招架的顏色跟這裡不一樣）。
     */
    public static int colourIndex(final String skillId) {
        final Weights weights = SKILLS.get(skillId);
        if (weights == null) {
            return -1;
        }
        return switch (weights.primary()) {
            case SupportColours.RED -> 0;
            case SupportColours.GREEN -> 1;
            case SupportColours.BLUE -> 2;
            default -> -1;
        };
    }

    /** 表上有沒有寫這個技能（測試用：可見的技能都要寫）。 */
    public static boolean listed(final String skillId) {
        return SKILLS.containsKey(skillId);
    }

    /** 這個技能寶石的屬性分配；表上沒有的照標籤歸類。 */
    public static Weights weights(final SkillDefinition definition) {
        if (definition == null) {
            return new Weights(0, 0, 0);
        }
        final Weights listed = SKILLS.get(definition.id());
        return listed != null ? listed : fallback(definition);
    }

    /** 沒列在表上的（新加的技能）：法術、召喚、詛咒是智慧，攻擊有投射物是敏捷，其他攻擊、戰吼是力量，剩下的算智慧。 */
    static Weights fallback(final SkillDefinition definition) {
        final Set<String> tags = definition.tags();
        if (tags.contains("minion") || tags.contains("curse") || tags.contains("spell") && !definition.isAttack()) {
            return CODES.get("I");
        }
        if (definition.isAttack()) {
            return CODES.get(tags.contains("projectile") || tags.contains("bow") ? "D" : "S");
        }
        return CODES.get(tags.contains("warcry") ? "S" : "I");
    }

    /** 一個屬性的需求（PoE2：round(4 + 1.7 × 需求等級 × (分配／100)^0.9)；分配是 0 的沒有需求）。 */
    public static int attribute(final int levelRequirement, final int weight) {
        if (weight <= 0) {
            return 0;
        }
        return (int) Math.round(4 + 1.7 * Math.max(0, levelRequirement) * Math.pow(weight / 100.0, 0.9));
    }

    /** 寶石等級的需求等級：1 級是 0（原作），其他照 skills.yml 的 level_requirements；超過 20 級照 20 級。 */
    static int levelRequirement(final SkillConfig config, final int gemLevel) {
        if (gemLevel <= 1) {
            return 0;
        }
        return config.requiredLevel(Math.min(SkillLoadout.MAX_LEVEL, gemLevel));
    }

    /** 技能寶石本身的需求（不含輔助）。 */
    public static Requirement base(final SkillConfig config, final SkillDefinition definition, final int gemLevel) {
        final Weights weights = weights(definition);
        final int level = levelRequirement(config, gemLevel);
        return new Requirement(attribute(level, weights.str()), attribute(level, weights.dex()), attribute(level, weights.intelligence()));
    }

    /** 一顆輔助寶石給技能寶石加的需求（紅＋5 力量、綠＋5 敏捷、藍＋5 智慧；白色的 0）。 */
    public static Requirement support(final String supportId) {
        return switch (SupportColours.colour(supportId)) {
            case SupportColours.RED -> new Requirement(SUPPORT_REQUIREMENT, 0, 0);
            case SupportColours.GREEN -> new Requirement(0, SUPPORT_REQUIREMENT, 0);
            case SupportColours.BLUE -> new Requirement(0, 0, SUPPORT_REQUIREMENT);
            default -> Requirement.NONE;
        };
    }

    /** 技能寶石＋插著的輔助寶石（孔裡的技能寶石另外算）。武器賦予的技能沒有需求。 */
    public static Requirement of(final SkillConfig config, final SkillLoadout.Entry entry) {
        if (entry == null || entry.granted()) {
            return Requirement.NONE;
        }
        final SkillDefinition definition = config.skill(entry.skillId());
        if (!isGem(definition)) {
            return Requirement.NONE;
        }
        return supportsOn(base(config, definition, entry.level()), entry.supportIds());
    }

    /** 本身的需求加上這些輔助寶石的。 */
    public static Requirement supportsOn(final Requirement base, final Iterable<String> supportIds) {
        Requirement total = base;
        for (final String id : supportIds) {
            if (id != null && !id.isEmpty()) {
                total = total.plus(support(id));
            }
        }
        return total;
    }

    /** 寶石屬性需求的倍率（天賦樹、昇華的「減少 x% 裝備與技能寶石的屬性需求」）。 */
    public static double scale(final StatSheet sheet) {
        if (sheet == null) {
            return 1;
        }
        // 2026-10-28 照 PoE2（謎容「技能寶石沒有能力值需求」）：attr_req.none 是 2 → 0；裝備的那一種（1）不影響寶石
        return TreeEffectKeys.noAttributeRequirements(sheet) == 2 ? 0 : TreeEffectKeys.attributeScale(sheet);
    }

    /** 角色現在的力量、敏捷、智慧與能不能用最高屬性滿足。 */
    public record Attributes(double str, double dex, double intelligence, boolean highest) {
        public static Attributes of(final StatSheet sheet) {
            return new Attributes(sheet.get(StatKeys.STR), sheet.get(StatKeys.DEX), sheet.get(StatKeys.INT), StatKeys.flag(sheet, HIGHEST));
        }

        double has(final char colour) {
            if (highest) {
                return Math.max(str, Math.max(dex, intelligence));
            }
            return switch (colour) {
                case SupportColours.RED -> str;
                case SupportColours.GREEN -> dex;
                default -> intelligence;
            };
        }
    }

    /** 不足的原因（例如「力量不足（需要 52，你有 40）」）；夠的話回傳 null。 */
    public static String problem(final Requirement req, final Attributes attributes) {
        final List<String> parts = new ArrayList<>(3);
        check(parts, req.str(), attributes.has(SupportColours.RED), SupportColours.RED);
        check(parts, req.dex(), attributes.has(SupportColours.GREEN), SupportColours.GREEN);
        check(parts, req.intelligence(), attributes.has(SupportColours.BLUE), SupportColours.BLUE);
        return parts.isEmpty() ? null : String.join("、", parts);
    }

    private static void check(final List<String> parts, final int need, final double has, final char colour) {
        if (need > 0 && has + 1e-9 < need) {
            parts.add(String.format(Locale.ROOT, "%s不足（需要 %d，你有 %d）", label(colour), need, (int) Math.floor(has)));
        }
    }

    /**
     * 技能面板上屬性需求不足的技能（技能代號 → 原因）：宿主寶石孔裡的技能不足時宿主也算不足。
     * PlayerStateService 算完屬性表後交給 {@link SkillLoadout#syncUnmet}。
     */
    public static Map<String, String> unmet(final SkillConfig config, final SkillLoadout loadout, final StatSheet sheet) {
        final Map<String, String> unmet = new LinkedHashMap<>();
        if (config == null || loadout == null || sheet == null) {
            return unmet;
        }
        for (final SkillLoadout.Entry entry : loadout.entries()) {
            final String why = problem(config, entry, sheet);
            if (why != null) {
                unmet.put(entry.skillId(), why);
            }
            for (final SkillLoadout.Entry inner : entry.spells()) { // 2026-10-27：每一孔的技能寶石（這次之前只看第 1 孔）
                final String innerWhy = problem(config, inner, sheet);
                if (innerWhy != null) {
                    unmet.put(inner.skillId(), innerWhy);
                }
            }
        }
        return unmet;
    }

    /**
     * 2026-10-27 照站長：屬性不夠寶石等級的需求時，自動降到屬性夠的最高等級來用（裝備、輔助加的技能等級不算進需求，
     * 降完再加上去）。回傳寶石本身該用的等級；連 1 級都不夠（或不是寶石、沒有屬性表）回傳原本的等級——那種情況
     * {@link #problem(SkillConfig, SkillLoadout.Entry, StatSheet)} 會擋下來（孔裡的技能：innerEntries 帶著宿主的輔助一起檢查，
     * 跟 SkillBuild 用的 innerSupportsOf 一樣或更嚴，擋得住）。
     */
    public static int usableLevel(final SkillConfig config, final SkillDefinition definition, final int level,
                                  final Iterable<String> supportIds, final StatSheet sheet) {
        if (config == null || sheet == null || !isGem(definition) || level <= 1) {
            return level;
        }
        final double scale = scale(sheet);
        final Attributes attributes = Attributes.of(sheet);
        for (int candidate = level; candidate >= 1; candidate--) {
            if (problem(supportsOn(base(config, definition, candidate), supportIds).scaled(scale), attributes) == null) {
                return candidate;
            }
        }
        return level;
    }

    /** 同上，給技能面板上的一格。 */
    public static int usableLevel(final SkillConfig config, final SkillLoadout.Entry entry, final StatSheet sheet) {
        if (config == null || entry == null || entry.granted()) {
            return entry == null ? 1 : entry.level();
        }
        return usableLevel(config, config.skill(entry.skillId()), entry.level(), entry.supportIds(), sheet);
    }

    /** 能用的最低門檻：寶石 1 級的需求＋輔助（2026-10-27：等級會自動往下降，所以只有連 1 級都不夠才算不能用）。 */
    static Requirement minimum(final SkillConfig config, final SkillLoadout.Entry entry) {
        if (entry == null || entry.granted()) {
            return Requirement.NONE;
        }
        final SkillDefinition definition = config.skill(entry.skillId());
        if (!isGem(definition)) {
            return Requirement.NONE;
        }
        return supportsOn(base(config, definition, 1), entry.supportIds());
    }

    /** 這顆技能寶石（含孔裡的技能）用不用得了：不足回傳原因，夠（或不是寶石）回傳 null。 */
    public static String problem(final SkillConfig config, final SkillLoadout.Entry entry, final StatSheet sheet) {
        if (config == null || entry == null || sheet == null || entry.granted()) {
            return null;
        }
        final double scale = scale(sheet);
        final Attributes attributes = Attributes.of(sheet);
        // 2026-10-27：等級會自動降到夠用的等級，只有連 1 級的需求都不夠才不能用（這次之前看寶石本身的等級）
        final String why = problem(minimum(config, entry).scaled(scale), attributes);
        if (why != null) {
            return why;
        }
        // 2026-10-27：孔裡好幾個技能寶石時，全部都不足宿主才算不足（有一個能用就還有東西可放；這次之前只看第 1 孔）
        String first = null;
        for (final SkillLoadout.Entry spell : entry.spells()) {
            final String innerWhy = problem(minimum(config, spell).scaled(scale), attributes);
            if (innerWhy == null) {
                return null;
            }
            if (first == null) {
                final SkillDefinition inner = config.skill(spell.skillId());
                first = "孔裡的" + (inner == null ? spell.skillId() : inner.name()) + innerWhy;
            }
        }
        return first;
    }

    /**
     * 2026-10-27 照 PoE2：孔裡屬性需求不足的技能寶石不能用——主要寶石、法術圖騰、英靈圖騰只用孔裡夠用的那幾個
     * （這次之前宿主只要有一個夠用，孔裡不足的也一起觸發／放）。sheet 是 null 時全部照舊。
     */
    public static List<SkillLoadout.Entry> usable(final SkillConfig config, final List<SkillLoadout.Entry> entries, final StatSheet sheet) {
        final List<SkillLoadout.Entry> out = new ArrayList<>();
        for (final SkillLoadout.Entry entry : entries) {
            if (problem(config, entry, sheet) == null) {
                out.add(entry);
            }
        }
        return out;
    }

    // ---- 顯示 ----

    /** 顏色的 MiniMessage 標籤：紅 &lt;red&gt;（§c）、綠 &lt;green&gt;（§a）、藍 &lt;blue&gt;（§9）、白 &lt;white&gt;。 */
    public static String tag(final char colour) {
        return switch (colour) {
            case SupportColours.RED -> "<red>";
            case SupportColours.GREEN -> "<green>";
            case SupportColours.BLUE -> "<blue>";
            default -> "<white>";
        };
    }

    /** 顏色的 RGB（資源包的外框用；跟 §c／§a／§9 一樣）。白色回傳 0。 */
    public static int rgb(final char colour) {
        return switch (colour) {
            case SupportColours.RED -> 0xFF5555;
            case SupportColours.GREEN -> 0x55FF55;
            case SupportColours.BLUE -> 0x5555FF;
            default -> 0;
        };
    }

    /** 力量／敏捷／智慧；白色寫「無屬性」。 */
    public static String label(final char colour) {
        return switch (colour) {
            case SupportColours.RED -> "力量";
            case SupportColours.GREEN -> "敏捷";
            case SupportColours.BLUE -> "智慧";
            default -> "無屬性";
        };
    }

    /** 輔助寶石的名字顏色（白色的照白色）。 */
    public static String supportTag(final String supportId) {
        return tag(SupportColours.colour(supportId));
    }

    /** 輔助寶石的屬性標記，例如「&lt;red&gt;【力量】」。 */
    public static String supportBadge(final String supportId) {
        final char colour = SupportColours.colour(supportId);
        return tag(colour) + "【" + label(colour) + "】";
    }

    /** 技能寶石的名字顏色（主要的屬性；混色的用漸層，例如力量＋敏捷是紅到綠）。 */
    public static String skillTag(final SkillDefinition definition) {
        final List<Character> colours = weights(definition).colours();
        if (colours.size() >= 2) {
            final StringBuilder gradient = new StringBuilder("<gradient");
            for (final char colour : colours) {
                gradient.append(':').append(switch (colour) {
                    case SupportColours.RED -> "#FF5555";
                    case SupportColours.GREEN -> "#55FF55";
                    default -> "#5555FF";
                });
            }
            return gradient.append('>').toString();
        }
        return tag(weights(definition).primary());
    }

    /** 一段文字照技能寶石的顏色上色（混色的漸層會在後面關掉，不影響接著的文字）。 */
    public static String colourSkill(final SkillDefinition definition, final String text) {
        final String tag = skillTag(definition);
        return tag + text + (tag.startsWith("<gradient") ? "</gradient>" : "");
    }

    /** 輔助寶石說明的第一行，例如「&lt;red&gt;■ 力量輔助寶石（插上去讓技能寶石的力量需求 +5）」。 */
    public static String supportLine(final String supportId) {
        final char colour = SupportColours.colour(supportId);
        if (colour == 0) {
            return "<white>■ 無屬性輔助寶石 <dark_gray>（白色：不加技能寶石的屬性需求）";
        }
        return tag(colour) + "■ " + label(colour) + "輔助寶石 <dark_gray>（插上去讓技能寶石的" + label(colour) + "需求 +" + SUPPORT_REQUIREMENT + "）";
    }

    /** 沒套資源包時輔助寶石的外觀（原版染料的物品模型）；白色回傳 null（照舊惡魂之淚）。 */
    public static String dyeModel(final char colour) {
        return switch (colour) {
            case SupportColours.RED -> "red_dye";
            case SupportColours.GREEN -> "lime_dye";
            case SupportColours.BLUE -> "blue_dye";
            default -> null;
        };
    }

    /** 技能寶石的屬性標記，例如「&lt;red&gt;【力量&lt;gray&gt;／&lt;green&gt;敏捷&lt;red&gt;】」。 */
    public static String skillBadge(final SkillDefinition definition) {
        final List<Character> colours = weights(definition).colours();
        if (colours.isEmpty()) {
            return "<white>【無屬性】";
        }
        final StringBuilder badge = new StringBuilder(tag(colours.get(0))).append("【");
        for (int i = 0; i < colours.size(); i++) {
            if (i > 0) {
                badge.append("<gray>／");
            }
            badge.append(tag(colours.get(i))).append(label(colours.get(i)));
        }
        return badge.append(tag(colours.get(colours.size() - 1))).append("】").toString();
    }

    /** 需求寫成一行（例如「&lt;red&gt;力量 52&lt;gray&gt;・&lt;blue&gt;智慧 12」）；不足的那一項標成暗紅。attributes 是 null 就不比。 */
    public static String requirementText(final Requirement req, final Attributes attributes) {
        final List<String> parts = new ArrayList<>(3);
        part(parts, req.str(), SupportColours.RED, attributes);
        part(parts, req.dex(), SupportColours.GREEN, attributes);
        part(parts, req.intelligence(), SupportColours.BLUE, attributes);
        return String.join("<gray>・", parts);
    }

    private static void part(final List<String> parts, final int need, final char colour, final Attributes attributes) {
        if (need <= 0) {
            return;
        }
        final boolean short_ = attributes != null && attributes.has(colour) + 1e-9 < need;
        parts.add(tag(colour) + label(colour) + " " + (short_ ? "<dark_red><st>" + need + "</st> <red>不足" : String.valueOf(need)));
    }

    /**
     * 技能寶石的說明（物品、技能面板）用的幾行：屬性分配、需求（含輔助加的）、不足的話寫原因。
     *
     * @param sheet 角色的屬性表（null＝不比，例如還沒載入）
     */
    public static List<String> lines(final SkillConfig config, final SkillLoadout.Entry entry, final StatSheet sheet) {
        final List<String> lines = new ArrayList<>();
        if (entry == null || entry.granted()) {
            return lines;
        }
        final SkillDefinition definition = config.skill(entry.skillId());
        if (!isGem(definition)) {
            return lines;
        }
        final double scale = scale(sheet);
        final Requirement own = base(config, definition, entry.level());
        final Requirement total = of(config, entry);
        final Requirement scaled = total.scaled(scale);
        final Attributes attributes = sheet == null ? null : Attributes.of(sheet);
        lines.add("<gray>屬性：" + skillBadge(definition) + " <gray>需要 " + requirementText(scaled, attributes));
        final Requirement fromSupports = new Requirement(total.str() - own.str(), total.dex() - own.dex(),
                total.intelligence() - own.intelligence());
        if (!fromSupports.isEmpty() || Math.abs(scale - 1) > 1e-9) {
            lines.add("<dark_gray>　寶石本身 " + plainText(own)
                    + (fromSupports.isEmpty() ? "" : "、輔助寶石 +" + plainText(fromSupports))
                    + (Math.abs(scale - 1) > 1e-9 ? String.format(Locale.ROOT, "、需求 ×%.0f%%", scale * 100) : ""));
        }
        if (attributes != null) {
            final String why = problem(scaled, attributes);
            if (why != null) {
                final String floor = problem(minimum(config, entry).scaled(scale), attributes);
                if (floor != null) {
                    lines.add("<red>✖ " + floor + "（Lv.1 的需求）：這顆技能寶石不能使用");
                } else { // 2026-10-27：自動降級
                    lines.add("<yellow>⚠ " + why + "：自動以 Lv." + usableLevel(config, entry, sheet)
                            + " 使用（裝備加的技能等級另外加上去）");
                }
            }
        }
        return lines;
    }

    /** 不帶顏色的需求（例如「力量 52／智慧 12」）。 */
    public static String plainText(final Requirement req) {
        final List<String> parts = new ArrayList<>(3);
        if (req.str() > 0) {
            parts.add("力量 " + req.str());
        }
        if (req.dex() > 0) {
            parts.add("敏捷 " + req.dex());
        }
        if (req.intelligence() > 0) {
            parts.add("智慧 " + req.intelligence());
        }
        return parts.isEmpty() ? "無" : String.join("／", parts);
    }
}
