package tw.exilecore.ascendancy;

import tw.exilecore.charge.ChargeText;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 昇華天賦、分身獎勵、稱號加成用的屬性（純函式）：設定檔的寫法（{@link #parse}）、哪些屬性、哪一種寫法遊戲真的會讀（{@link #problem}），
 * 以及寫成玩家看得懂的一行（{@link #describe}，跟天賦樹的說明同一種寫法：「增加 30% 火焰傷害」「+8% 火焰抗性」）。
 */
public final class StatLines {

    private static final Set<ModType> FLAT = EnumSet.of(ModType.FLAT);
    private static final Set<ModType> INCREASED = EnumSet.of(ModType.INCREASED);
    private static final Set<ModType> SCALED = EnumSet.of(ModType.INCREASED, ModType.MORE);
    private static final Set<ModType> FLAT_OR_INCREASED = EnumSet.of(ModType.FLAT, ModType.INCREASED);
    private static final Set<ModType> ANY = EnumSet.allOf(ModType.class);

    /** 屬性 → {名稱, 可以用的寫法}（照這個順序列在說明文件）。 */
    private static final Map<String, Entry> STATS = new LinkedHashMap<>();

    private record Entry(String name, Set<ModType> types, boolean tagged) {
    }

    /** 可以當「物理傷害額外附加為…」的元素。 */
    private static final Map<String, String> GAINS = new LinkedHashMap<>();
    /** 標籤的名字（傷害、速度前面的那幾個字）。 */
    private static final Map<String, String> TAG_NAMES = new LinkedHashMap<>();
    /** 傷害類型的標籤。 */
    private static final Set<String> DAMAGE_TYPES = Set.of(Tags.PHYSICAL, Tags.ELEMENTAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS);
    /** 武器標籤：寫成「使用弓時」。 */
    private static final Set<String> WEAPONS = Set.of(Tags.SWORD, Tags.AXE, Tags.MACE, Tags.DAGGER, Tags.CLAW, Tags.WAND,
            Tags.STAFF, Tags.BOW, Tags.CROSSBOW, Tags.QUARTERSTAFF, Tags.SPEAR, Tags.TALISMAN, Tags.ONE_HAND, Tags.TWO_HAND);

    static {
        put(StatKeys.LIFE_MAX, "最大生命", ANY, false);
        put(StatKeys.MANA_MAX, "最大魔力", ANY, false);
        put(StatKeys.ES_MAX, "最大能量護盾", ANY, false);
        put(StatKeys.LIFE_REGEN, "每秒生命回復", FLAT_OR_INCREASED, false);
        put(StatKeys.LIFE_REGEN_PERCENT, "每秒回復最大生命", FLAT, false); // 2026-10-24：德魯伊的分身獎勵
        put(StatKeys.MANA_REGEN, "魔力回復", FLAT_OR_INCREASED, false);
        put(StatKeys.ARMOUR, "護甲", ANY, false);
        put(StatKeys.EVASION, "閃避", ANY, false);
        put(StatKeys.BLOCK_CHANCE, "格擋率", FLAT_OR_INCREASED, false);
        put(StatKeys.RESIST_FIRE, "火焰抗性", FLAT, false);
        put(StatKeys.RESIST_COLD, "冰冷抗性", FLAT, false);
        put(StatKeys.RESIST_LIGHTNING, "閃電抗性", FLAT, false);
        put(StatKeys.RESIST_CHAOS, "混沌抗性", FLAT, false);
        put(StatKeys.RESIST_MAX, "所有抗性上限", FLAT, false);
        put(StatKeys.DAMAGE, "傷害", SCALED, true);
        put(StatKeys.ATTACK_SPEED, "攻擊速度", SCALED, true);
        put(StatKeys.CAST_SPEED, "施法速度", SCALED, true);
        put(StatKeys.CRIT_CHANCE, "暴擊率", INCREASED, true);
        put(StatKeys.CRIT_MULTI, "暴擊傷害", FLAT, true);
        put(StatKeys.ACCURACY, "命中值", FLAT_OR_INCREASED, false);
        put(StatKeys.MOVEMENT_SPEED, "移動速度", INCREASED, false);
        put(StatKeys.STR, "力量", FLAT_OR_INCREASED, false);
        put(StatKeys.DEX, "敏捷", FLAT_OR_INCREASED, false);
        put(StatKeys.INT, "智慧", FLAT_OR_INCREASED, false);
        put(StatKeys.DROP_RARITY, "物品掉落稀有度", INCREASED, false);
        put(StatKeys.DROP_QUANTITY, "物品掉落數量", INCREASED, false);
        put(StatKeys.EXPERIENCE_GAIN, "打怪經驗", INCREASED, false); // 2026-10-18
        put(StatKeys.GOLD_FIND, "怪物掉落金幣", INCREASED, false); // 2026-10-18
        put(StatKeys.FLASK_LIFE_RECOVERY, "生命藥劑回復", INCREASED, false);
        put(StatKeys.FLASK_MANA_RECOVERY, "魔力藥劑回復", INCREASED, false);
        put(StatKeys.SKILL_AREA, "範圍效果", SCALED, true);
        put(StatKeys.AURA_EFFECT, "光環效果", SCALED, false);
        put(StatKeys.AURA_RESERVATION, "光環的魔力保留", INCREASED, true); // 2026-10-26：可以加標籤（元技能 meta、常駐 persistent；PoE2 祈願者、戰術家）
        put(StatKeys.TOTEM_LIFE, "圖騰生命", INCREASED, false);
        put(StatKeys.SPIRIT_MAX, "最大精魂", FLAT_OR_INCREASED, false); // 2026-10：昇華改成 +x 最大精魂（固定值）
        // 2026-10-26 照 PoE2：殘骸撿取距離（星焰術士）、元素湧動（林嵐獵姬）
        put(tw.exilecore.stats.TreeMechanicKeys.REMNANT_RANGE, "拾取殘骸的距離", INCREASED, false);
        put("surge.fire", "層火焰湧動（消耗戰星時觸發元素湧動）", FLAT, false);
        put("surge.cold", "層冰冷湧動（消耗戰星時觸發元素湧動）", FLAT, false);
        put("surge.lightning", "層閃電湧動（消耗戰星時觸發元素湧動）", FLAT, false);
        for (final String signature : tw.exilecore.stats.SignatureText.TEXT.keySet()) {
            put(signature, "招牌天賦", FLAT, false);
        }
        // 2026-10-22：獲得 x% 傷害為額外的某元素傷害、某元素的抗性上限（以前是招牌天賦的寫死說明）
        for (final String type : List.of(Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS)) {
            put(StatKeys.DAMAGE_EXTRA + type, "額外" + type, FLAT, false);
            put(StatKeys.RESIST_MAX + "." + type, "抗性上限", FLAT, false);
        }
        put(StatKeys.SKILL_PROJECTILES, "投射物", FLAT, false);
        put(StatKeys.SKILL_PROJECTILE_SPEED, "投射物速度", SCALED, false);
        put(StatKeys.SKILL_PIERCE, "穿透", FLAT, false);
        put(StatKeys.STRIKE_TARGETS, "打擊額外目標", FLAT, false);
        put(StatKeys.MELEE_SPLASH, "近戰擴散", FLAT, true);
        put(StatKeys.SKILL_COST, "魔力消耗", SCALED, false);
        put(StatKeys.LEECH_LIFE, "生命偷取", FLAT, true);
        put(StatKeys.LEECH_MANA, "魔力偷取", FLAT, true);
        put(StatKeys.CORPSE_WALK, "移動時留下屍體", FLAT, false);
        put(StatKeys.CORPSE_LIFE_REGEN, "每具屍體的生命回復", FLAT, false);
        put(StatKeys.CORPSE_MOVEMENT, "每具屍體的移動速度", FLAT, true);
        put(StatKeys.LEECH_LIFE_ELEMENTAL, "元素傷害的生命偷取", FLAT, true);
        put(StatKeys.LEECH_INSTANT, "立即偷取", FLAT, false);
        put(StatKeys.LEECH_RATE, "最大偷取速度", INCREASED, false);
        put(StatKeys.LEECH_EFFECT, "偷取的量", INCREASED, false);
        put(StatKeys.FLAG_INSTANT_LEECH, "偷取全部立即生效", FLAT, false);
        put(StatKeys.THORNS_DAMAGE, "荊棘傷害", INCREASED, false);   // 2026-10-17：昇華用得到
        put(StatKeys.FLAG_THORNS_ANY_HIT, "荊棘也會被遠程攻擊與法術觸發", FLAT, false);
        put(StatKeys.ES_RECHARGE, "能量護盾回充速度", SCALED, false);
        put(StatKeys.ES_DELAY, "能量護盾回充延遲", INCREASED, false);
        put(StatKeys.CHARGE_DURATION, "戰星的持續時間", INCREASED, false);
        put(StatKeys.IGNITE_CHANCE, "點燃機率", FLAT_OR_INCREASED, false);
        put(StatKeys.IGNITE_MAGNITUDE, "點燃傷害", SCALED, false);
        put(StatKeys.IGNITE_DURATION, "點燃持續時間", INCREASED, false);
        put(StatKeys.CHILL_EFFECT, "冰緩效果", INCREASED, false);
        put(StatKeys.CHILL_DURATION, "冰緩持續時間", INCREASED, false);
        put(StatKeys.FREEZE_BUILDUP, "凍結累積", SCALED, false);
        put(StatKeys.FREEZE_DURATION, "凍結持續時間", INCREASED, false);
        put(StatKeys.SHOCK_CHANCE, "感電機率", FLAT_OR_INCREASED, false);
        put(StatKeys.SHOCK_EFFECT, "感電效果", INCREASED, false);
        put(StatKeys.SHOCK_DURATION, "感電持續時間", INCREASED, false);
        put(StatKeys.BLEED_CHANCE, "流血機率", FLAT_OR_INCREASED, true); // 2026-10-26：可以加標籤（暴擊引起流血：crit_hit）
        put(StatKeys.BLEED_MAGNITUDE, "流血傷害", SCALED, false);
        put(StatKeys.BLEED_DURATION, "流血持續時間", INCREASED, false);
        put(StatKeys.POISON_CHANCE, "中毒機率", FLAT_OR_INCREASED, false);
        put(StatKeys.POISON_MAGNITUDE, "中毒傷害", SCALED, false);
        put(StatKeys.POISON_DURATION, "中毒持續時間", SCALED, false);
        put(StatKeys.STUN_BUILDUP, "暈眩累積", SCALED, false);
        put(StatKeys.ARMOUR_BREAK, "破甲（物理傷害的 %）", FLAT_OR_INCREASED, false);
        put(StatKeys.SKILL_TOTEMS, "圖騰上限", FLAT, false);
        put(tw.exilecore.stats.TotemKeys.TAKES_DAMAGE, "擊中傷害先由最近的圖騰承受", FLAT, false); // 2026-10-26 照 PoE2 堅木之壘
        put(tw.exilecore.stats.TotemKeys.FLAG_ON_ATTACK_PROJECTILE, "圖騰只在你發射攻擊投射物時使用技能", FLAT, false); // 2026-10-26 照 PoE2 砲彈準備好了！
        put(StatKeys.CRIT_BONUS, "暴擊傷害加成", SCALED, true);
        put(StatKeys.ATTR_CHOICE, "任一屬性", FLAT, false);
        put(StatKeys.STUN_THRESHOLD, "暈眩門檻", INCREASED, false);
        put(StatKeys.STUN_DURATION, "暈眩持續時間", INCREASED, false);
        put(StatKeys.ELECTROCUTE_CHANCE, "電擊機率", FLAT_OR_INCREASED, false);
        put(StatKeys.ELECTROCUTE_BUILDUP, "電擊累積", SCALED, false);
        put(StatKeys.ELECTROCUTE_DURATION, "電擊持續時間", INCREASED, false);
        put(StatKeys.AILMENT_DURATION, "異常狀態持續時間", INCREASED, false);
        put(StatKeys.AILMENT_THRESHOLD, "元素異常門檻", INCREASED, false);
        put(StatKeys.AVOID_IGNITE, "避免被點燃", FLAT, false);
        put(StatKeys.AVOID_CHILL, "避免被冰緩", FLAT, false);
        put(StatKeys.AVOID_FREEZE, "避免被凍結", FLAT, false);
        put(StatKeys.AVOID_SHOCK, "避免被感電", FLAT, false);
        put(StatKeys.AVOID_BLEED, "避免流血", FLAT, false);
        put(StatKeys.AVOID_POISON, "避免中毒", FLAT, false);
        put(StatKeys.AVOID_STUN, "避免暈眩", FLAT, false);
        put(StatKeys.AVOID_ELECTROCUTE, "避免電擊", FLAT, false);
        put(StatKeys.SELF_AILMENT_DURATION, "自己身上的元素異常持續時間", INCREASED, false);
        put(StatKeys.SKILL_CHAINS, "連鎖", FLAT, false);
        put(StatKeys.SKILL_DURATION, "持續時間", SCALED, true);
        put(StatKeys.SKILL_COOLDOWN, "冷卻時間", SCALED, true);
        // 特殊效果（傳奇裝備；昇華與天賦也能用）
        put(StatKeys.SKILL_LEVEL, "技能等級", FLAT, true);
        put(StatKeys.DAMAGE_DOUBLE, "雙倍傷害機率", FLAT, true);
        put(StatKeys.LIFE_ON_HIT, "擊中回復生命", FLAT, true);
        put(StatKeys.LIFE_ON_KILL, "擊殺回復生命", FLAT, false);
        put(StatKeys.MANA_ON_KILL, "擊殺回復魔力", FLAT, false);
        put(StatKeys.ES_ON_KILL, "擊殺回復能量護盾", FLAT, false);
        put(StatKeys.ONSLAUGHT_ON_KILL, "擊殺獲得疾速", FLAT, false);
        put(StatKeys.KILL_EXPLODE, "殺死的敵人爆炸", FLAT, false);
        put(StatKeys.DAMAGE_FROM_MANA, "傷害先扣魔力", FLAT, false);
        put(StatKeys.LIFE_RECOUP, "受到的傷害回復為生命", FLAT, false);
        put(StatKeys.STEAL_MODS, "殺死稀有怪物獲得牠的詞綴", FLAT, false);
        put(StatKeys.ARMOUR_ELEMENTAL, "護甲套用在元素傷害", FLAT, false);
        put(StatKeys.PHYS_REDUCTION, "額外物理傷害減免", FLAT, false);
        put(StatKeys.resistMax(tw.exilecore.stats.DamageType.FIRE), "最大火焰抗性", FLAT, false);
        put(StatKeys.resistMax(tw.exilecore.stats.DamageType.COLD), "最大冰冷抗性", FLAT, false);
        put(StatKeys.resistMax(tw.exilecore.stats.DamageType.LIGHTNING), "最大閃電抗性", FLAT, false);
        // 2026-10-26 照 PoE2 0.5 換昇華天賦用到的（引擎本來就有，這裡補上說明與檢查）
        put(StatKeys.MARK_EFFECT, "印記效果", SCALED, false);
        put(StatKeys.CURSE_EFFECT, "詛咒效果", SCALED, false);
        put(StatKeys.FLASK_CHARGES_GAINED_LIFE, "獲得的生命藥劑充能", INCREASED, false);
        put(StatKeys.FLASK_CHARGES_GAINED_MANA, "獲得的魔力藥劑充能", INCREASED, false);
        put(StatKeys.CHARM_CHARGES_GAINED, "獲得的護符充能", SCALED, false); // 2026-10-26：也能寫更多（精妙符印；CharmService 照 apply 算）
        put(StatKeys.PRESENCE_AREA, "存在範圍", INCREASED, false);
        put(StatKeys.CRIT_BONUS_TAKEN, "被暴擊時的暴擊傷害加成", INCREASED, false);
        put(StatKeys.RAGE_MAX, "最大怒氣", FLAT, false);
        put(StatKeys.SLOW_MAGNITUDE, "減速幅度", INCREASED, false);
        put(StatKeys.LEECH_MANA_EFFECT, "魔力偷取的量", INCREASED, false);
        put(AscendancyMechanics.POINT_BLANK, "零點射擊", FLAT, false);
        put(AscendancyMechanics.FAR_SHOT, "遠射", FLAT, false);
        put(AscendancyMechanics.DRUIDIC_CHAMPION, "德魯伊之冠", FLAT, false);
        // 2026-10-26 照 PoE2：嵌晶戰團的減少屬性需求（裝備與技能寶石）、應變能力（tw.exilecore.skill.GemAttributes）
        put(tw.exilecore.stats.TreeEffectKeys.ITEM_ATTR_REQUIREMENT, "裝備與技能寶石的屬性需求", INCREASED, false);
        put(tw.exilecore.skill.GemAttributes.HIGHEST, "寶石的屬性需求可由你最高的屬性滿足", FLAT, false);

        GAINS.put(Tags.FIRE, "火焰");
        GAINS.put(Tags.COLD, "冰冷");
        GAINS.put(Tags.LIGHTNING, "閃電");
        GAINS.put(Tags.CHAOS, "混沌");

        // 照句子裡的順序：先技能種類，再傷害類型，最後武器
        TAG_NAMES.put(Tags.MELEE, "近戰");
        TAG_NAMES.put(Tags.PROJECTILE, "投射物");
        TAG_NAMES.put(Tags.AREA, "範圍");
        TAG_NAMES.put(Tags.STRIKE, "打擊");
        TAG_NAMES.put(Tags.SLAM, "猛擊");
        TAG_NAMES.put(Tags.ATTACK, "攻擊");
        TAG_NAMES.put(Tags.SPELL, "法術");
        TAG_NAMES.put(Tags.MOVEMENT, "移動");
        TAG_NAMES.put(Tags.MINION, "召喚物");
        TAG_NAMES.put(tw.exilecore.stats.TreeMechanicKeys.COMPANION, "夥伴"); // 2026-10-27 照 PoE2（靈狩者：夥伴的傷害、生命、範圍）
        TAG_NAMES.put(Tags.TOTEM, "圖騰");
        TAG_NAMES.put(Tags.TRAP, "陷阱");
        TAG_NAMES.put(Tags.AURA, "光環");
        TAG_NAMES.put(Tags.HERALD, "捷光環");
        TAG_NAMES.put(Tags.WARCRY, "戰吼");
        TAG_NAMES.put(Tags.PERSISTENT, "常駐");
        TAG_NAMES.put(Tags.META, "主要寶石"); // 2026-10-22
        TAG_NAMES.put(Tags.MARK, "印記");
        TAG_NAMES.put("curse", "詛咒"); // 2026-10-26：詛咒技能的標籤（skills.yml 的 tags: [curse]；PoE2 骸主的詛咒範圍）
        TAG_NAMES.put(Tags.SHAPESHIFT, "變身");
        TAG_NAMES.put(Tags.BEAR, "熊形態");
        TAG_NAMES.put(Tags.WEREWOLF, "狼人形態");
        TAG_NAMES.put(Tags.WYVERN, "飛龍形態");
        TAG_NAMES.put(Tags.PHYSICAL, "物理");
        TAG_NAMES.put(Tags.ELEMENTAL, "元素");
        TAG_NAMES.put(Tags.FIRE, "火焰");
        TAG_NAMES.put(Tags.COLD, "冰冷");
        TAG_NAMES.put(Tags.LIGHTNING, "閃電");
        TAG_NAMES.put(Tags.CHAOS, "混沌");
        TAG_NAMES.put(Tags.SWORD, "劍");
        TAG_NAMES.put(Tags.AXE, "斧");
        TAG_NAMES.put(Tags.MACE, "錘");
        TAG_NAMES.put(Tags.DAGGER, "匕首");
        TAG_NAMES.put(Tags.CLAW, "爪");
        TAG_NAMES.put(Tags.WAND, "法杖");
        TAG_NAMES.put(Tags.STAFF, "長杖");
        TAG_NAMES.put(Tags.BOW, "弓");
        TAG_NAMES.put(Tags.CROSSBOW, "弩");
        TAG_NAMES.put(Tags.QUARTERSTAFF, "長棍");
        TAG_NAMES.put(Tags.SPEAR, "矛");
        TAG_NAMES.put(Tags.TALISMAN, "獸魂符");
        TAG_NAMES.put(Tags.ONE_HAND, "單手武器");
        TAG_NAMES.put(Tags.TWO_HAND, "雙手武器");
        TAG_NAMES.putAll(AscKeys.TAGS); // 2026-10-26：昇華這次做出來的機制用到的標籤（觸發的、鬥旗）
    }

    private static void put(final String stat, final String name, final Set<ModType> types, final boolean tagged) {
        STATS.put(stat, new Entry(name, types, tagged));
    }

    private StatLines() {
    }

    /**
     * 設定檔的一串加成：{ stat, type, value, tags }。type 不寫是 FLAT；INCREASED／MORE 的 value 寫百分比（30 = 30%）。
     * 只檢查寫法（type、value 有沒有）；遊戲會不會讀另外用 {@link #problem} 檢查。
     *
     * @param source 修飾器的來源（除錯、屬性來源用）
     */
    public static List<Modifier> parse(final List<YamlNode> list, final String source) {
        final List<Modifier> stats = new ArrayList<>();
        for (final YamlNode node : list) {
            final String typeText = node.string("type", "FLAT").trim().toUpperCase(Locale.ROOT);
            final ModType type;
            try {
                type = ModType.valueOf(typeText);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".type：要是 FLAT、INCREASED 或 MORE，卻是「" + typeText + "」");
            }
            if (!node.has("value")) {
                throw new IllegalArgumentException(node.path() + "：缺少 value");
            }
            final double value = node.decimal("value", 0);
            stats.add(new Modifier(node.requireString("stat").trim(), type, type == ModType.FLAT ? value : value / 100.0,
                    new LinkedHashSet<>(node.strings("tags")), source));
        }
        return stats;
    }

    /** 同一種屬性（屬性、寫法、標籤都一樣）的數值加起來（好幾個來源的總和；照第一次出現的順序）。 */
    public static List<Modifier> merge(final List<Modifier> modifiers, final String source) {
        final Map<String, Modifier> merged = new LinkedHashMap<>();
        for (final Modifier modifier : modifiers) {
            final String key = modifier.stat() + '|' + modifier.type() + '|' + String.join(",", new java.util.TreeSet<>(modifier.tags()));
            final Modifier before = merged.get(key);
            merged.put(key, new Modifier(modifier.stat(), modifier.type(), (before == null ? 0 : before.value()) + modifier.value(),
                    modifier.tags(), source));
        }
        return new ArrayList<>(merged.values());
    }

    /** 這條屬性這裡認不認得（2026-10：ModifierText 不認得的交給這裡，護符、增益的說明才不會出現英文代號）。 */
    public static boolean knows(final String stat) {
        return STATS.containsKey(stat) || tw.exilecore.stats.SignatureText.TEXT.containsKey(stat) || ChargeText.isChargeStat(stat)
                || AscKeys.handles(stat) // 2026-10-26
                || stat.equals(StatKeys.DAMAGE_VS_LOW_LIFE)
                || stat.startsWith(StatKeys.DAMAGE_GAIN) && GAINS.containsKey(stat.substring(StatKeys.DAMAGE_GAIN.length()));
    }

    /** 標籤的中文（不認得回傳 null）。 */
    public static String tagWord(final String tag) {
        return TAG_NAMES.get(tag);
    }

    /** 能用的屬性（說明文件、錯誤訊息用）。 */
    public static List<String> stats() {
        final List<String> list = new ArrayList<>(STATS.keySet());
        for (final String type : GAINS.keySet()) {
            list.add(StatKeys.DAMAGE_GAIN + type);
        }
        for (final String charge : ChargeText.ids()) {
            list.add(StatKeys.chargeChance(charge));
            list.add(StatKeys.chargeOnHit(charge));
            list.add(StatKeys.chargeMax(charge));
        }
        return list;
    }

    /** 這條修飾器有什麼問題（遊戲不會讀、寫法不對）；沒問題回傳 null。 */
    public static String problem(final Modifier modifier) {
        final String stat = modifier.stat();
        if (AscKeys.handles(stat)) {
            return AscKeys.problem(modifier); // 2026-10-26：昇華原本（尚未生效）的機制（AscendancyEffects）
        }
        if (ChargeText.isGenerationStat(stat)) {
            // 2026-10：多一顆的機率、定期給、事件冷卻（秒）
            if (modifier.type() != ModType.FLAT || !modifier.tags().isEmpty()) {
                return stat + " 要用 FLAT、不能加標籤";
            }
            return modifier.value() <= 0 ? stat + " 要大於 0" : null;
        }
        if (ChargeText.isChargeStat(stat)) {
            // 戰星：獲得的機率（百分比點數）、上限（顆數）；擊中時獲得可以限定技能種類（近戰、攻擊…），不能限定武器
            if (!ChargeText.ids().contains(ChargeText.chargeOf(stat))) {
                return stat + "：沒有這種戰星（charges.yml 的代號：" + String.join("、", ChargeText.ids()) + "）";
            }
            if (modifier.type() != ModType.FLAT) {
                return stat + " 要用 FLAT（機率是百分比點數、上限是顆數）";
            }
            if (!modifier.tags().isEmpty()) {
                if (!ChargeText.isOnHit(stat)) {
                    return stat + " 不能加標籤";
                }
                for (final String tag : modifier.tags()) {
                    if (!TAG_NAMES.containsKey(tag) || WEAPONS.contains(tag) || Tags.MINION.equals(tag)) {
                        return stat + "：只能加技能種類的標籤（melee、attack、spell…），不能是 " + tag + "（武器不分；召喚物打的不算）";
                    }
                }
            }
            return modifier.value() == 0 ? stat + " 的數值是 0" : null;
        }
        if (stat.startsWith(StatKeys.DAMAGE_GAIN)) {
            if (!GAINS.containsKey(stat.substring(StatKeys.DAMAGE_GAIN.length()))) {
                return stat + "：額外附加只能是 " + String.join("、", GAINS.keySet());
            }
            if (modifier.type() != ModType.FLAT) {
                return stat + " 要用 FLAT（物理傷害的百分之幾）";
            }
            return modifier.tags().isEmpty() ? null : stat + " 不能加標籤";
        }
        if (stat.equals(StatKeys.DAMAGE_VS_LOW_LIFE)) {
            // 2026-10-21：對低血的敵人增加 x%（補刀、斬首）；說明另外寫（describe），這裡也要認得，不然昇華檔驗證會說不支援
            if (modifier.type() != ModType.FLAT) {
                return stat + " 要用 FLAT（增加幾 %）";
            }
            return modifier.tags().isEmpty() ? null : stat + " 不能加標籤";
        }
        if (stat.equals(AscendancyService.EXTRA_PASSIVE_POINTS)) {
            // 2026-10-26：武器大師的額外天賦點（AscendancyModule 加進可用天賦點）
            return modifier.type() == ModType.FLAT && modifier.tags().isEmpty() && modifier.value() > 0 ? null : stat + " 要用 FLAT、大於 0、不能加標籤";
        }
        final Entry entry = STATS.get(stat);
        if (entry == null) {
            return "不支援的屬性 " + stat;
        }
        if (!entry.types().contains(modifier.type())) {
            return stat + " 只能用 " + typeNames(entry.types()) + "（寫成 " + modifier.type() + " 遊戲不會讀）";
        }
        if (!modifier.tags().isEmpty()) {
            // 2026-10-26：只有 minion 標籤 = 給召喚物的（MinionSheets 把主人帶 minion 標籤的修飾器全部複製過去），每一種屬性都可以
            // 2026-10-27：minion＋companion = 只給夥伴的（天賦樹也這樣寫）
            if (!entry.tagged() && !modifier.tags().equals(Set.of(Tags.MINION))
                    && !modifier.tags().equals(Set.of(Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION))) {
                return stat + " 不能加標籤";
            }
            for (final String tag : modifier.tags()) {
                if (!TAG_NAMES.containsKey(tag)) {
                    return stat + "：不認得的標籤 " + tag;
                }
                if (stat.equals(StatKeys.SKILL_LEVEL) && WEAPONS.contains(tag)) {
                    return stat + "：技能等級照技能自己的標籤（spell、chaos…），不看武器";
                }
            }
        }
        if (modifier.value() == 0) {
            return stat + " 的數值是 0";
        }
        return null;
    }

    private static String typeNames(final Set<ModType> types) {
        final List<String> names = new ArrayList<>();
        for (final ModType type : types) {
            names.add(type.name());
        }
        return String.join("／", names);
    }

    /** 一條修飾器的說明（數值是引擎格式：INCREASED／MORE 是小數）。 */
    public static String describe(final Modifier modifier) {
        final String stat = modifier.stat();
        if (AscKeys.handles(stat)) {
            return AscKeys.describe(modifier); // 2026-10-26
        }
        final double value = modifier.value();
        final String charge = ChargeText.describe(stat, value, prefix(modifier));
        if (charge != null) {
            return charge;
        }
        if (stat.startsWith(StatKeys.DAMAGE_GAIN)) {
            final String type = GAINS.getOrDefault(stat.substring(StatKeys.DAMAGE_GAIN.length()), stat);
            return "物理傷害的 " + number(value) + "% 額外附加為" + type + "傷害";
        }
        if (stat.equals(StatKeys.DAMAGE_VS_LOW_LIFE)) {
            // 2026-10-21：以前寫死「增加 35%」，兩個節點（補刀、斬首）加起來 70% 也只顯示 35%
            return "對低血（35% 以下）的敵人增加 " + number(value) + "% 擊中傷害";
        }
        if (stat.startsWith(StatKeys.DAMAGE_EXTRA) && !stat.startsWith(StatKeys.DAMAGE_EXTRA_PER_MANA)) {
            final String type = elementWord(stat.substring(StatKeys.DAMAGE_EXTRA.length()));
            if (type != null) {
                return "獲得 " + number(value) + "% 傷害為額外的" + type + "傷害";
            }
        }
        if (stat.startsWith(StatKeys.RESIST_MAX + ".")) {
            final String type = elementWord(stat.substring(StatKeys.RESIST_MAX.length() + 1));
            if (type != null) {
                return signed(value) + "% 最大" + type + "抗性";
            }
        }
        final String signature = tw.exilecore.stats.SignatureText.TEXT.get(stat);
        if (signature != null) {
            return signature;
        }
        final Entry entry = STATS.get(stat);
        if (entry == null && !knows(stat)) {
            // 2026-10：不認得的屬性不要把英文代號秀給玩家看（網頁、選單都會經過這裡）
            if (stat.startsWith("flag.")) {
                return "特殊效果";
            }
            return "特殊效果（" + (modifier.type() == ModType.FLAT ? number(value) : number(value * 100) + "%") + "）";
        }
        final String name = entry == null ? stat : entry.name();
        return switch (stat) {
            // 2026-10-24：「增加 x%」的生命回復以前也寫成「每秒回復 x 生命」（0.05 顯示成 0.1）
            case StatKeys.LIFE_REGEN -> modifier.type() == ModType.FLAT ? "每秒回復 " + number(value) + " 生命" : scaled(modifier, name);
            case StatKeys.LIFE_REGEN_PERCENT -> "每秒回復 " + fine(value) + "% 最大生命";
            case StatKeys.MANA_REGEN -> modifier.type() == ModType.FLAT ? "每秒回復 " + number(value) + " 魔力" : scaled(modifier, name);
            case StatKeys.BLOCK_CHANCE, StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING, StatKeys.RESIST_CHAOS ->
                    signed(value) + "% " + name;
            case StatKeys.RESIST_MAX -> name + " " + signed(value) + "%";
            case StatKeys.SKILL_PROJECTILES -> "額外 " + number(value) + " 個投射物";
            case StatKeys.SKILL_PIERCE -> "投射物多穿透 " + number(value) + " 個敵人";
            case StatKeys.STRIKE_TARGETS -> "打擊技能多打 " + number(value) + " 個附近的敵人";
            case StatKeys.MELEE_SPLASH -> "近戰打擊打中時，目標周圍的敵人受到這一下 " + number(value) + "% 的傷害";
            case StatKeys.SKILL_CHAINS -> "多連鎖 " + number(value) + " 次";
            case StatKeys.SKILL_LEVEL -> signed(value) + " " + (prefix(modifier).isEmpty() ? "全部" : skillPrefix(modifier)) + "技能等級";
            case StatKeys.DAMAGE_DOUBLE -> withWeapons(modifier, prefix(modifier) + "擊中時 " + number(value) + "% 機率造成雙倍傷害");
            case StatKeys.LIFE_ON_HIT -> withWeapons(modifier, prefix(modifier) + "每次擊中回復 " + number(value) + " 生命");
            case StatKeys.LIFE_ON_KILL -> "擊殺時回復 " + number(value) + "% 最大生命";
            case StatKeys.MANA_ON_KILL -> "擊殺時回復 " + number(value) + "% 最大魔力";
            case StatKeys.ES_ON_KILL -> "擊殺時回復 " + number(value) + "% 最大能量護盾";
            case StatKeys.ONSLAUGHT_ON_KILL -> "擊殺時 " + number(value) + "% 機率獲得疾速";
            case StatKeys.KILL_EXPLODE -> "你殺死的敵人爆炸，對周圍造成牠最大生命 " + number(value) + "% 的火焰傷害";
            case StatKeys.DAMAGE_FROM_MANA -> "受到的傷害有 " + number(value) + "% 先從魔力扣除";
            case StatKeys.LIFE_RECOUP -> "受到的傷害有 " + number(value) + "% 之後回復為生命";
            case StatKeys.STEAL_MODS -> "殺死稀有怪物時，獲得牠的詞綴 " + number(value) + " 秒";
            case StatKeys.ARMOUR_ELEMENTAL -> "護甲的 " + number(value) + "% 也套用在元素傷害上";
            case StatKeys.PHYS_REDUCTION -> number(value) + "% 額外物理傷害減免";
            case StatKeys.LEECH_LIFE -> "造成的物理" + prefix(modifier) + "傷害有 " + number(value) + "% 偷取為生命";
            case StatKeys.LEECH_MANA -> "造成的物理" + prefix(modifier) + "傷害有 " + number(value) + "% 偷取為魔力";
            case StatKeys.CORPSE_WALK -> "移動時每 1.5 秒在腳下留下一具屍體";
            case StatKeys.CORPSE_LIFE_REGEN -> "附近每具屍體，每秒回復 " + number(value) + " 生命";
            case StatKeys.CORPSE_MOVEMENT -> "附近每具屍體，增加 " + number(value) + "% 移動速度";
            case StatKeys.LEECH_LIFE_ELEMENTAL -> "造成的元素傷害有 " + number(value) + "% 偷取為生命";
            case StatKeys.LEECH_INSTANT -> "偷取有 " + number(value) + "% 立即生效";
            case StatKeys.FLAG_INSTANT_LEECH -> "偷取全部立即生效（一下最多回 6% 最大生命）";
            case StatKeys.FLAG_NO_LEECH -> "無法偷取";
            case StatKeys.FLAG_THORNS_ANY_HIT -> "荊棘也會被遠程攻擊與法術觸發";
            case tw.exilecore.stats.TotemKeys.TAKES_DAMAGE -> "擊中的傷害有 " + number(value) + "% 先從你最近的圖騰生命扣除";
            case tw.exilecore.stats.TotemKeys.FLAG_ON_ATTACK_PROJECTILE -> "圖騰只在你發射攻擊投射物時使用技能";
            case StatKeys.IGNITE_CHANCE, StatKeys.SHOCK_CHANCE, StatKeys.BLEED_CHANCE, StatKeys.POISON_CHANCE, StatKeys.ELECTROCUTE_CHANCE ->
                    modifier.type() == ModType.FLAT ? name + " " + signed(value) + "%" : scaled(modifier, name);
            case StatKeys.AVOID_IGNITE, StatKeys.AVOID_CHILL, StatKeys.AVOID_FREEZE, StatKeys.AVOID_SHOCK, StatKeys.AVOID_BLEED, StatKeys.AVOID_POISON,
                 StatKeys.AVOID_STUN, StatKeys.AVOID_ELECTROCUTE ->
                    number(value) + "% 機率" + name;
            case StatKeys.SELF_AILMENT_DURATION -> name + (value < 0 ? "減少 " : "增加 ") + number(Math.abs(value) * 100) + "%";
            case StatKeys.CRIT_MULTI -> withWeapons(modifier, signed(value) + "% " + prefix(modifier) + name);
            case StatKeys.AURA_RESERVATION -> value < 0 && modifier.type() == ModType.INCREASED
                    ? "精魂技能的保留效率增加 " + number(Math.abs(value) * 100) + "%" : scaled(modifier, name);
            default -> {
                if (modifier.type() == ModType.FLAT) {
                    yield withWeapons(modifier, signed(value) + " " + prefix(modifier) + name);
                }
                yield withWeapons(modifier, scaled(modifier, prefix(modifier) + name));
            }
        };
    }

    /** 前面加上「使用弓時」（天賦樹的寫法：「使用錘時 +15% 暴擊傷害」「使用斧時增加 6% 攻擊速度」）。 */
    private static String withWeapons(final Modifier modifier, final String text) {
        final String weapons = weapons(modifier);
        if (weapons.isEmpty()) {
            return text;
        }
        return weapons + (text.startsWith("+") || text.startsWith("-") ? " " : "") + text;
    }

    /**
     * 一串修飾器的說明，一行一條；火焰、冰冷、閃電抗性一樣多時合成「所有元素抗性」。
     */
    public static List<String> describeAll(final List<Modifier> modifiers) {
        final List<String> lines = new ArrayList<>();
        final Double all = sameElementalResist(modifiers);
        boolean merged = false;
        for (final Modifier modifier : modifiers) {
            if (all != null && isElementalResist(modifier)) {
                if (!merged) {
                    lines.add(signed(all) + "% 所有元素抗性");
                    merged = true;
                }
                continue;
            }
            lines.add(describe(modifier));
        }
        return lines;
    }

    private static boolean isElementalResist(final Modifier modifier) {
        final String stat = modifier.stat();
        return modifier.type() == ModType.FLAT && modifier.tags().isEmpty()
                && (stat.equals(StatKeys.RESIST_FIRE) || stat.equals(StatKeys.RESIST_COLD) || stat.equals(StatKeys.RESIST_LIGHTNING));
    }

    /** 火焰、冰冷、閃電抗性各剛好一條、數值一樣：那個數值；不然 null。 */
    private static Double sameElementalResist(final List<Modifier> modifiers) {
        final Map<String, Double> found = new LinkedHashMap<>();
        for (final Modifier modifier : modifiers) {
            if (isElementalResist(modifier) && found.put(modifier.stat(), modifier.value()) != null) {
                return null;
            }
        }
        if (found.size() != 3) {
            return null;
        }
        final double first = found.values().iterator().next();
        for (final double value : found.values()) {
            if (value != first) {
                return null;
            }
        }
        return first;
    }

    /** 增加／減少、更多／更少。 */
    private static String scaled(final Modifier modifier, final String what) {
        final boolean up = modifier.value() >= 0;
        final String percent = number(Math.abs(modifier.value()) * 100);
        if (modifier.type() == ModType.MORE) {
            return (up ? "更多 " : "更少 ") + percent + "% " + what;
        }
        return (up ? "增加 " : "減少 ") + percent + "% " + what;
    }

    /** 不是武器的標籤：寫在屬性名字前面（「近戰物理傷害」）。 */
    private static String prefix(final Modifier modifier) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> tag : TAG_NAMES.entrySet()) {
            if (modifier.tags().contains(tag.getKey()) && (!WEAPONS.contains(tag.getKey()) || modifier.stat().equals(StatKeys.DAMAGE))) {
                text.append(tag.getValue());
            }
        }
        return text.toString();
    }

    /** 技能等級的標籤：傷害類型在前（「火焰法術」「混沌」「近戰攻擊」）。 */
    private static String skillPrefix(final Modifier modifier) {
        final StringBuilder types = new StringBuilder();
        final StringBuilder kinds = new StringBuilder();
        for (final Map.Entry<String, String> tag : TAG_NAMES.entrySet()) {
            if (!modifier.tags().contains(tag.getKey()) || WEAPONS.contains(tag.getKey())) {
                continue;
            }
            (DAMAGE_TYPES.contains(tag.getKey()) ? types : kinds).append(tag.getValue());
        }
        return types.toString() + kinds;
    }

    /** 武器標籤（傷害以外的屬性）：「使用弓時」。 */
    private static String weapons(final Modifier modifier) {
        if (modifier.stat().equals(StatKeys.DAMAGE)) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (final Map.Entry<String, String> tag : TAG_NAMES.entrySet()) {
            if (WEAPONS.contains(tag.getKey()) && modifier.tags().contains(tag.getKey())) {
                names.add(tag.getValue());
            }
        }
        return names.isEmpty() ? "" : "使用" + String.join("或", names) + "時";
    }

    private static String signed(final double value) {
        return (value >= 0 ? "+" : "-") + number(Math.abs(value));
    }

    /** 整數不帶小數點，其他留一位。 */
    /** fire／cold／lightning／chaos → 火焰／冰冷／閃電／混沌（其他回傳 null）。 */
    private static String elementWord(final String type) {
        return GAINS.get(type);
    }

    /** 小數到兩位（0.25% 這種，number 只到一位會變成 0.3）。 */
    static String fine(final double value) {
        final double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? Long.toString(Math.round(rounded))
                : String.format(Locale.ROOT, "%.2f", rounded).replaceAll("0+$", "");
    }

    static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? Long.toString(Math.round(rounded)) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
