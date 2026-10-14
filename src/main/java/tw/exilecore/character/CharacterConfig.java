package tw.exilecore.character;

import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * data/character.yml：等級上限（100）、角色格數、經驗表、等級差懲罰、死亡懲罰、新手裝備。純資料，不依賴 Bukkit。
 */
public final class CharacterConfig {

    /** 角色格的上限（含功勳換的；角色選擇是兩列）。 */
    public static final int MAX_SLOTS = 18;

    /**
     * 一個職業的新手裝備：推薦的武器（照順序排在選單最前面）、一起送的防具、拿單手武器時送的盾。
     * 能選哪些武器由職業的起始屬性決定（見 {@link StarterKits}），不是這裡列的。
     */
    public record StarterClass(List<String> recommended, List<String> armour, String shield) {
        public StarterClass {
            recommended = List.copyOf(recommended);
            armour = List.copyOf(armour);
        }
    }

    /**
     * 起始技能規則：武器有這個標籤就送這個技能（由上往下找第一個符合的）。
     *
     * @param classId 只有這個職業適用（null = 每個職業都適用），例如混沌術士拿法杖送混沌彈、其他人拿法杖送熾星彈
     */
    public record StarterSkillRule(String weaponTag, String skill, String classId) {
        public StarterSkillRule(final String weaponTag, final String skill) {
            this(weaponTag, skill, null);
        }

        /** 這條規則適用這個職業、這把武器嗎。 */
        public boolean matches(final CharacterClass characterClass, final Set<String> weaponTags) {
            return weaponTags.contains(weaponTag) && (classId == null || (characterClass != null && classId.equals(characterClass.id())));
        }
    }

    private static final StarterClass NO_STARTER = new StarterClass(List.of(), List.of(), null);

    /**
     * 預設的經驗表：從 1 級起，升到下一級需要的經驗（第 i 個是 i+1 級 → i+2 級）。跟 PoE2（也跟 PoE）的經驗表一樣，
     * 1 到 100 級總共 4,250,334,444。出貨的 character.yml 每一級都照這個寫；檔案沒寫 experience 時用這個。
     */
    static final long[] DEFAULT_EXPERIENCE = {
            525L, 1_235L, 2_021L, 3_403L, 5_002L, 7_138L, 10_053L, 13_804L, 18_512L, 24_297L,
            31_516L, 39_878L, 50_352L, 62_261L, 76_465L, 92_806L, 112_027L, 133_876L, 158_538L, 187_025L,
            218_895L, 255_366L, 295_852L, 341_805L, 392_470L, 449_555L, 512_121L, 583_857L, 662_181L, 747_411L,
            844_146L, 949_053L, 1_064_952L, 1_192_712L, 1_333_241L, 1_487_491L, 1_656_447L, 1_841_143L, 2_046_202L, 2_265_837L,
            2_508_528L, 2_776_124L, 3_061_734L, 3_379_914L, 3_723_676L, 4_099_570L, 4_504_444L, 4_951_099L, 5_430_907L, 5_957_868L,
            6_528_910L, 7_153_414L, 7_827_968L, 8_555_414L, 9_353_933L, 10_212_541L, 11_142_646L, 12_157_041L, 13_252_160L, 14_441_758L,
            15_731_508L, 17_127_265L, 18_635_053L, 20_271_765L, 22_044_909L, 23_950_783L, 26_019_833L, 28_261_412L, 30_672_515L, 33_287_878L,
            36_118_904L, 39_163_425L, 42_460_810L, 46_024_718L, 49_853_964L, 54_008_554L, 58_473_753L, 63_314_495L, 68_516_464L, 74_132_190L,
            80_182_477L, 86_725_730L, 93_748_717L, 101_352_108L, 109_524_907L, 118_335_069L, 127_813_148L, 138_033_822L, 149_032_391L,
            160_890_604L, 173_648_795L, 187_372_170L, 202_153_736L, 218_041_909L, 235_163_399L, 253_547_862L, 273_358_532L, 294_631_836L,
            317_515_914L
    };

    private int levelCap = 100;
    private int characterSlots = 3;
    private int nameMin = 2;
    /** 角色名稱的過濾（2026-10；內建的字＋character.yml 的 name_filter）。 */
    private final NameFilter nameFilter = new NameFilter();
    private int nameMax = 12;
    private int pointsPerLevel = 1;
    /** 全服經驗加倍（character.yml 的 experience_bonus）：打怪、委託的經驗再乘這個；檔案沒寫 = 1（不加倍）。 */
    private double experienceBonus = 1.0;
    private final TreeMap<Integer, Long> experience = new TreeMap<>();
    private boolean levelPenaltyEnabled = true;
    private int safeBase = 3;
    private int safePerLevels = 16;
    /**
     * 算等級差的時候怪物等級最高算幾級（0 = 不限）。出貨的 character.yml 寫 80（PoE2 的地圖最高 80 級）；
     * 檔案沒寫 = 0：自己改過的舊 character.yml（新版在 .new）照原本的規則，不會只套到一半。
     */
    private int monsterLevelCap = 0;
    /** 高等級另外乘的倍率：從這一級起。出貨的檔案照 PoE 寫 95–99 級；檔案沒寫 = 不乘（同上）。 */
    private final TreeMap<Integer, Double> highLevel = new TreeMap<>();
    /**
     * 2026-10（等級上限 120）：算經驗懲罰（等級差、高等級的減少）時玩家等級最高算幾級（0 = 不限）。出貨的檔案寫 99：
     * 100 級以後打怪的經驗跟 99 級一樣，升級的難度只看經驗表（不然 100 級以後等級差懲罰一路掉到 1%，根本練不動）。
     */
    private int penaltyLevelCap = 0;
    /** 2026-10：高等級的死亡懲罰（從這一級起扣幾 %；例如 100: 0.20、110: 0.30）；沒寫到的等級照 map_death_penalty。 */
    private final TreeMap<Integer, Double> deathPenaltyTiers = new TreeMap<>();
    private final TreeMap<Integer, Double> deathPenalty = new TreeMap<>();
    private double mapDeathPenalty = 0.10;
    /** 2026-10：統一的死亡懲罰——這個等級以上在哪裡死都扣 map_death_penalty（10%），以下都不扣。 */
    private int deathPenaltyFromLevel = 60;
    private int respecFreeUntilLevel = 20;
    private final TreeMap<Integer, Long> respecCost = new TreeMap<>();
    private Map<String, StarterClass> starterClasses = defaultStarterClasses();
    private List<StarterSkillRule> starterSkills = defaultStarterSkills();

    public CharacterConfig() {
        // character.yml 沒寫時的預設值（跟出貨的 character.yml 一樣）
        for (int level = 1; level <= DEFAULT_EXPERIENCE.length; level++) {
            experience.put(level, DEFAULT_EXPERIENCE[level - 1]);
        }
        deathPenalty.put(1, 0.0);
        respecCost.put(20, 60L);
        respecCost.put(60, 1500L);
        respecCost.put(100, 4500L);
    }

    /** character.yml 沒有 starter 區塊（或沒寫某一項）時用的預設值，跟預設的 character.yml 一樣（自己改過的檔案，新版放在 .new）。 */
    private static Map<String, StarterClass> defaultStarterClasses() {
        final Map<String, StarterClass> map = new LinkedHashMap<>();
        map.put("marauder", new StarterClass(List.of("mace1_01", "mace2_01"),
                List.of("helm_str_01", "body_str_01", "legs_str_01", "boots_str_01"), "tshield_01"));
        map.put("mercenary", new StarterClass(List.of("xbow_01"),
                List.of("helm_str_dex_01", "body_str_dex_01", "legs_str_dex_01", "boots_str_dex_01"), "kshield_01"));
        map.put("huntress", new StarterClass(List.of("spear_01"),
                List.of("helm_dex_01", "body_dex_01", "legs_dex_01", "boots_dex_01"), "buckler_01"));
        map.put("ranger", new StarterClass(List.of("bow_01"),
                List.of("helm_dex_01", "body_dex_01", "legs_dex_01", "boots_dex_01"), "buckler_01"));
        map.put("monk", new StarterClass(List.of("qstaff_01"),
                List.of("helm_dex_int_01", "body_dex_int_01", "legs_dex_int_01", "boots_dex_int_01"), "buckler_01"));
        map.put("sorceress", new StarterClass(List.of("staff_01", "wand_01"),
                List.of("helm_int_01", "body_int_01", "legs_int_01", "boots_int_01"), "focus_01"));
        map.put("witch", new StarterClass(List.of("wand_01", "staff_01"),
                List.of("helm_int_01", "body_int_01", "legs_int_01", "boots_int_01"), "focus_01"));
        map.put("druid", new StarterClass(List.of("talis_01"),
                List.of("helm_str_01", "body_str_01", "legs_str_01", "boots_str_01"), "tshield_01"));
        return map;
    }

    private static List<StarterSkillRule> defaultStarterSkills() {
        return List.of(new StarterSkillRule("bow", "lightning_arrow"), new StarterSkillRule("crossbow", "explosive_bolt"),
                new StarterSkillRule("quarterstaff", "tempest_flurry"), new StarterSkillRule("spear", "lightning_spear"),
                new StarterSkillRule("wand", "essence_drain", "witch"), new StarterSkillRule("staff", "essence_drain", "witch"),
                new StarterSkillRule("wand", "fireball"), new StarterSkillRule("staff", "fireball"),
                new StarterSkillRule("talisman", "bear_maul"),
                new StarterSkillRule("mace", "boneshatter"), new StarterSkillRule("melee", "earthquake"));
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("character.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        levelCap = Math.max(2, root.integer("level_cap", levelCap));
        characterSlots = Math.max(1, Math.min(MAX_SLOTS, root.integer("character_slots", characterSlots)));
        if (root.has("name_length")) {
            final int[] range = root.range("name_length");
            nameMin = Math.max(1, range[0]);
            nameMax = Math.max(nameMin, range[1]);
        }
        final YamlNode filter = root.node("name_filter");
        nameFilter.configure(filter.strings("blocked"), filter.strings("reserved"), filter.strings("allow"));
        pointsPerLevel = Math.max(0, root.integer("passive_points_per_level", pointsPerLevel));
        final double bonus = root.decimal("experience_bonus", 1.0);
        if (!(bonus >= MIN_EXPERIENCE_BONUS && bonus <= MAX_EXPERIENCE_BONUS)) { // 也擋掉 .nan
            throw new IllegalArgumentException("character.yml：experience_bonus 要在 " + MIN_EXPERIENCE_BONUS + " 到 " + MAX_EXPERIENCE_BONUS
                    + " 之間（全服經驗的倍數，1 = 不加倍），卻是 " + bonus);
        }
        experienceBonus = bonus;
        if (root.has("experience")) {
            experience.clear();
            final YamlNode node = root.node("experience");
            for (final String key : node.keys()) {
                experience.put(Integer.parseInt(key.trim()), (long) node.decimal(key, 0));
            }
            if (experience.isEmpty()) {
                throw new IllegalArgumentException("character.yml：experience 至少要有一個等級");
            }
        }
        final YamlNode penalty = root.node("level_penalty");
        levelPenaltyEnabled = penalty.bool("enabled", levelPenaltyEnabled);
        safeBase = penalty.integer("safe_base", safeBase);
        safePerLevels = Math.max(1, penalty.integer("safe_per_levels", safePerLevels));
        monsterLevelCap = Math.max(0, penalty.integer("monster_level_cap", monsterLevelCap));
        penaltyLevelCap = Math.max(0, penalty.integer("player_level_cap", 0));
        if (penalty.has("high_level")) {
            // 先全部讀完再換上去：寫錯時保留原本的
            final TreeMap<Integer, Double> parsed = new TreeMap<>();
            final YamlNode node = penalty.node("high_level");
            for (final String key : node.keys()) {
                final double value = node.decimal(key, 1.0);
                if (value <= 0 || value > 1) {
                    throw new IllegalArgumentException("character.yml：level_penalty.high_level." + key + " 要大於 0、最多 1（倍率）");
                }
                parsed.put(Integer.parseInt(key.trim()), value);
            }
            highLevel.clear();
            highLevel.putAll(parsed);
        }
        if (root.has("death_penalty")) {
            deathPenalty.clear();
            final YamlNode node = root.node("death_penalty");
            for (final String key : node.keys()) {
                deathPenalty.put(Integer.parseInt(key.trim()), node.decimal(key, 0));
            }
        }
        mapDeathPenalty = Math.max(0, root.decimal("map_death_penalty", mapDeathPenalty));
        deathPenaltyFromLevel = Math.max(1, root.integer("death_penalty_from_level", deathPenaltyFromLevel));
        deathPenaltyTiers.clear();
        if (root.has("death_penalty_tiers")) {
            final YamlNode node = root.node("death_penalty_tiers");
            for (final String key : node.keys()) {
                final double value = node.decimal(key, 0);
                if (value < 0 || value > 1) {
                    throw new IllegalArgumentException("character.yml：death_penalty_tiers." + key + " 要在 0 到 1 之間（比例），卻是 " + value);
                }
                deathPenaltyTiers.put(Integer.parseInt(key.trim()), value);
            }
        }
        final YamlNode respec = root.node("respec");
        respecFreeUntilLevel = Math.max(1, respec.integer("free_until_level", respecFreeUntilLevel));
        if (respec.has("gold_per_point")) {
            respecCost.clear();
            final YamlNode node = respec.node("gold_per_point");
            for (final String key : node.keys()) {
                respecCost.put(Integer.parseInt(key.trim()), (long) node.decimal(key, 0));
            }
            if (respecCost.isEmpty()) {
                throw new IllegalArgumentException("character.yml：respec.gold_per_point 至少要有一個等級");
            }
        }
        loadStarter(root.has("starter") ? root.node("starter") : null);
    }

    /**
     * starter 區塊：先全部解析完再換上去，寫錯時保留原本的設定。
     * 沒寫的部分（整個區塊、skills、某個職業、職業裡的某一項）用預設值；shield 寫 none 表示不送盾。
     * 代號只去掉前後空白，大小寫照寫（跟基底檔、技能檔的代號一樣）。
     */
    private void loadStarter(final YamlNode starter) {
        final Map<String, StarterClass> classes = defaultStarterClasses();
        List<StarterSkillRule> skills = defaultStarterSkills();
        if (starter != null) {
            final YamlNode classesNode = starter.node("classes");
            for (final String key : classesNode.keys()) {
                final CharacterClass characterClass = CharacterClass.parse(key, null);
                if (characterClass == null) {
                    throw new IllegalArgumentException("character.yml：starter.classes 不認得的職業 " + key);
                }
                final YamlNode node = classesNode.node(key);
                final StarterClass defaults = classes.getOrDefault(characterClass.id(), NO_STARTER);
                String shield = defaults.shield();
                if (node.has("shield")) {
                    final String text = node.string("shield", "");
                    shield = text == null || text.isBlank() || text.trim().equalsIgnoreCase("none") ? null : text.trim();
                }
                classes.put(characterClass.id(), new StarterClass(
                        node.has("recommended") ? trimmed(node.strings("recommended")) : defaults.recommended(),
                        node.has("armour") ? trimmed(node.strings("armour")) : defaults.armour(), shield));
            }
            if (starter.has("skills")) {
                final List<StarterSkillRule> parsed = new ArrayList<>();
                for (final YamlNode rule : starter.list("skills")) {
                    String classId = null;
                    if (rule.has("class")) {
                        final CharacterClass only = CharacterClass.parse(rule.string("class", ""), null);
                        if (only == null) {
                            throw new IllegalArgumentException("character.yml：starter.skills 不認得的職業 " + rule.string("class", "")
                                    + "（" + CharacterClass.idList() + "）");
                        }
                        classId = only.id();
                    }
                    parsed.add(new StarterSkillRule(rule.requireString("weapon").trim(), rule.requireString("skill").trim(), classId));
                }
                skills = List.copyOf(parsed);
            }
        }
        starterClasses = classes;
        starterSkills = skills;
    }

    private static List<String> trimmed(final List<String> values) {
        final List<String> list = new ArrayList<>();
        for (final String value : values) {
            if (value != null && !value.isBlank()) {
                list.add(value.trim());
            }
        }
        return list;
    }

    /** 職業的新手裝備設定；沒設定的職業只有武器（沒有推薦、防具與盾）。 */
    public StarterClass starter(final CharacterClass characterClass) {
        return starterClasses.getOrDefault(characterClass.id(), NO_STARTER);
    }

    public List<StarterSkillRule> starterSkills() {
        return starterSkills;
    }

    /** 這個職業拿這些標籤的武器時送的起始技能；沒有符合的規則回傳 null（改用職業的起始技能）。 */
    public String starterSkill(final CharacterClass characterClass, final Set<String> weaponTags) {
        for (final StarterSkillRule rule : starterSkills) {
            if (rule.matches(characterClass, weaponTags)) {
                return rule.skill();
            }
        }
        return null;
    }

    /** 在終局地圖裡死亡損失的比例（階段 4）。 */
    /** 幾等以上死亡才扣經驗。 */
    public int deathPenaltyFromLevel() {
        return deathPenaltyFromLevel;
    }

    public double mapDeathPenalty() {
        return mapDeathPenalty;
    }

    /** 未滿這個等級退點免費。 */
    public int respecFreeUntilLevel() {
        return respecFreeUntilLevel;
    }

    /** 退一點要多少金幣（線性內插）；未滿免費等級為 0。 */
    public long respecCost(final int level) {
        if (level < respecFreeUntilLevel) {
            return 0;
        }
        return LevelCurve.atLong(respecCost, level, 0, LevelCurve.Mode.LINEAR);
    }

    public int levelCap() {
        return levelCap;
    }

    public int characterSlots() {
        return characterSlots;
    }

    /** 角色名稱的過濾。 */
    public NameFilter nameFilter() {
        return nameFilter;
    }

    public int nameMin() {
        return nameMin;
    }

    public int nameMax() {
        return nameMax;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
    }

    /** experience_bonus 的範圍。 */
    public static final double MIN_EXPERIENCE_BONUS = 0.1;
    public static final double MAX_EXPERIENCE_BONUS = 10;

    /** 全服經驗的倍數（打怪、委託的經驗再乘這個；1 = 不加倍）。 */
    public double experienceBonus() {
        return experienceBonus * tw.exilecore.sponsor.Boosts.experience(); // 2026-10：贊助的全服經驗加倍再乘上去
    }

    /** 設定檔（管理網頁）的全服經驗倍數，不含贊助的全服加倍。 */
    public double configuredExperienceBonus() {
        return experienceBonus;
    }

    /** 全服經驗加倍的寫法「×2」「×1.5」；沒有加倍（1）回傳 null。 */
    public String experienceBonusText() {
        return bonusText(experienceBonus());
    }

    /** 2 → 「×2」、1.5 → 「×1.5」、1 → null。 */
    public static String bonusText(final double bonus) {
        if (Math.abs(bonus - 1) < 1e-9) {
            return null;
        }
        return "×" + java.math.BigDecimal.valueOf(bonus).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 從 level 升到 level+1 需要的經驗（沒列的等級用相鄰值線性內插）；到達上限後為 0。 */
    public long experienceToNext(final int level) {
        if (level >= levelCap) {
            return 0;
        }
        return LevelCurve.atLong(experience, level, 0, LevelCurve.Mode.LINEAR);
    }

    /** 升級可用的天賦點總數（不含額外點數）：從 2 級起每級 pointsPerLevel。 */
    public int pointsFromLevel(final int level) {
        return Math.max(0, level - 1) * pointsPerLevel;
    }

    /**
     * 擊殺經驗的總倍率（0.01 到 1）：等級差（{@link #levelDifferenceFactor}）× 高等級的減少（{@link #highLevelMultiplier}），最低 0.01。
     */
    public double levelPenalty(final int playerLevel, final int monsterLevel) {
        if (!levelPenaltyEnabled) {
            return 1.0;
        }
        return Math.max(0.01, levelDifferenceFactor(playerLevel, monsterLevel) * highLevelMultiplier(playerLevel));
    }

    /**
     * 等級差的倍率（0 到 1，PoE 的公式）。怪物等級先夾在 monster_level_cap 以下（PoE2 的地圖最高 80 級），
     * 安全範圍 = safeBase + 玩家等級 ÷ safePerLevels，超出的等級差 d：((L+5) ÷ (L+5+d^2.5))^1.5。
     */
    public double levelDifferenceFactor(final int playerLevel, final int monsterLevel) {
        if (!levelPenaltyEnabled) {
            return 1.0;
        }
        final int counted = monsterLevelCap > 0 ? Math.min(monsterLevel, monsterLevelCap) : monsterLevel;
        final int level = penaltyLevel(playerLevel);
        final int safe = safeBase + level / safePerLevels;
        final int diff = Math.max(0, Math.abs(level - counted) - safe);
        if (diff == 0) {
            return 1.0;
        }
        final double base = level + 5.0;
        return Math.pow(base / (base + Math.pow(diff, 2.5)), 1.5);
    }

    /** 高等級另外乘的倍率（high_level：從寫的那一級起，到下一個寫的等級為止）；沒寫到的等級 1。 */
    public double highLevelMultiplier(final int playerLevel) {
        if (!levelPenaltyEnabled) {
            return 1.0;
        }
        final Map.Entry<Integer, Double> entry = highLevel.floorEntry(penaltyLevel(playerLevel));
        return entry == null ? 1.0 : entry.getValue();
    }

    /** 算懲罰用的玩家等級（player_level_cap 以上照那一級）。 */
    private int penaltyLevel(final int playerLevel) {
        return penaltyLevelCap > 0 ? Math.min(playerLevel, penaltyLevelCap) : playerLevel;
    }

    /** 算經驗懲罰時玩家等級最高算幾級（0 = 不限）。 */
    public int penaltyLevelCap() {
        return penaltyLevelCap;
    }

    /**
     * 這個等級死亡扣「這一級升級所需經驗」的比例（還沒算累積贊助的減免）：death_penalty_tiers 寫到的等級照那個，
     * 其他照 map_death_penalty；未滿 death_penalty_from_level 不扣。
     */
    public double deathPenaltyAt(final int level) {
        if (level < deathPenaltyFromLevel) {
            return 0;
        }
        final Map.Entry<Integer, Double> tier = deathPenaltyTiers.floorEntry(level);
        return tier == null ? mapDeathPenalty : tier.getValue();
    }

    /** 「60 等以上死亡扣這一級 10%（100 等起 20%、110 等起 30%）」；都不扣回傳 null。 */
    public String deathPenaltyText() {
        if (mapDeathPenalty <= 0 && deathPenaltyTiers.isEmpty()) {
            return null;
        }
        final StringBuilder text = new StringBuilder(deathPenaltyFromLevel + " 等以上死亡扣這一級 " + LevelPace.percent(mapDeathPenalty) + " 的經驗");
        if (!deathPenaltyTiers.isEmpty()) {
            final List<String> parts = new java.util.ArrayList<>();
            deathPenaltyTiers.forEach((level, ratio) -> parts.add(level + " 等起 " + LevelPace.percent(ratio)));
            text.append("（").append(String.join("、", parts)).append("）");
        }
        return text.toString();
    }

    /** 高等級的死亡懲罰（等級 → 比例，照等級排）。 */
    public java.util.NavigableMap<Integer, Double> deathPenaltyTiers() {
        return java.util.Collections.unmodifiableNavigableMap(deathPenaltyTiers);
    }

    /** 算等級差時怪物等級最高算幾級（0 = 不限）。 */
    public int monsterLevelCap() {
        return monsterLevelCap;
    }

    /** 死亡損失的比例（目前等級所需經驗的幾分之幾，線性內插）。 */
    public double deathPenalty(final int level) {
        return LevelCurve.at(deathPenalty, level, 0, LevelCurve.Mode.LINEAR);
    }
}
