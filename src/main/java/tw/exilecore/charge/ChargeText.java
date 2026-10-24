package tw.exilecore.charge;

import tw.exilecore.stats.StatKeys;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 戰星屬性的說明文字（天賦、傳奇、昇華、輔助寶石、/stats 共用）：
 * {@code charge.chance.代號}「擊殺時 15% 機率獲得一顆怒星」、{@code charge.on_hit.代號}「擊中時 5% 機率獲得一顆怒星」、
 * {@code charge.max.代號}「+1 怒星上限」。純函式，不依賴 Bukkit。
 * <p>
 * 名字、預設的觸發時機與每顆的效果照 charges.yml（讀進來時 {@link #register}）；還沒讀過時用內建的銳星、怒星、磐星（沒有效果的說明）。
 * <p>
 * 說明文字裡的 {@code {charge:代號}} 換成「名字（每顆：效果）」（{@link #expand}；輔助寶石聚怒、聚銳、聚磐的說明用，
 * 改了 charges.yml 說明跟著變）。
 */
public final class ChargeText {

    private record Entry(String name, String trigger, String effect) {
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{charge:([a-z_]+)}");

    private static final Map<String, Entry> DEFAULTS = defaults();

    private static volatile Map<String, Entry> entries = DEFAULTS;

    private ChargeText() {
    }

    private static Map<String, Entry> defaults() {
        final Map<String, Entry> map = new LinkedHashMap<>();
        map.put("power", new Entry("銳星", "暴擊時", ""));
        map.put("frenzy", new Entry("怒星", "擊殺時", ""));
        map.put("endurance", new Entry("磐星", "被敵人擊中時", ""));
        return Collections.unmodifiableMap(map);
    }

    /** charges.yml 讀進來了：之後的說明用裡面的名字與觸發時機（順序照內建的、再接 charges.yml 多的）。 */
    public static void register(final Collection<ChargeType> types) {
        final Map<String, Entry> fresh = new LinkedHashMap<>(DEFAULTS);
        for (final ChargeType type : types) {
            fresh.put(type.id(), new Entry(type.name(), type.triggerText(), String.join("、", type.describe())));
        }
        entries = Collections.unmodifiableMap(fresh);
    }

    /** 認得的戰星代號（內建的銳星、怒星、磐星，加上 charges.yml 多寫的）。 */
    public static List<String> ids() {
        return List.copyOf(entries.keySet());
    }

    /** 戰星的名字（不認得就是代號本身）。 */
    public static String name(final String id) {
        final Entry entry = entries.get(id);
        return entry == null ? id : entry.name();
    }

    /** 這種戰星預設的觸發時機（「擊殺時」）；不認得是「觸發時」。 */
    public static String trigger(final String id) {
        final Entry entry = entries.get(id);
        return entry == null ? "觸發時" : entry.trigger();
    }

    /** 每顆的效果（「增加 40% 暴擊率」；好幾條用頓號接起來）；不認得或還沒讀 charges.yml 是空字串。 */
    public static String effect(final String id) {
        final Entry entry = entries.get(id);
        return entry == null ? "" : entry.effect();
    }

    /**
     * 說明文字裡的 {@code {charge:代號}} 換成「怒星（每顆：增加 4% 攻擊速度、…）」；不認得的代號換成代號本身。
     * 沒有預留位置的照原樣回傳。
     */
    public static String expand(final String text) {
        if (text == null || !text.contains("{charge:")) {
            return text;
        }
        final java.util.regex.Matcher matcher = PLACEHOLDER.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String id = matcher.group(1);
            final String effect = effect(id);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    name(id) + (effect.isEmpty() ? "" : "（每顆：" + effect + "）")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 說明文字裡 {@code {charge:代號}} 寫到的代號（檢查設定檔用）。 */
    public static List<String> placeholders(final String text) {
        if (text == null || !text.contains("{charge:")) {
            return List.of();
        }
        final java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        final java.util.regex.Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** 是不是戰星的屬性（charge.chance／charge.on_hit／charge.max／charge.event.事件／charge.extra.代號，加上 {@link #isGenerationStat} 那幾種）。
     *  charge.duration 不算（它是一般的增加）。 */
    public static boolean isChargeStat(final String stat) {
        return chargeOf(stat) != null || isGenerationStat(stat);
    }

    /** 事件給戰星時文字裡的事件（2026-10；charge.event.事件.代號）。 */
    public static final Map<String, String> EVENTS = events();

    private static Map<String, String> events() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("stun_applied", "重暈敵人時");
        map.put("armour_broken", "完全破甲時");
        map.put("freeze", "冰凍敵人時");
        map.put("shock", "讓敵人感電時");
        map.put("ignite", "點燃敵人時");
        map.put("cull", "撲殺敵人時");
        map.put("kill_rare", "擊殺稀有或傳奇怪物時");
        map.put("dodge", "翻滾時");
        map.put("electrocute", "電擊敵人時"); // 2026-10-24
        map.put("pin", "釘住敵人時"); // 2026-10-24
        map.put("stun_primed", "重暈蓄勢待暈的敵人時"); // 2026-10-24：鼓翼風
        map.put("block", "格擋時"); // 2026-10-24：熔殼
        map.put("kill", "擊殺敵人時"); // 2026-10-24：剔殘（只算那個技能打死的；稀有怪多一顆、傳奇怪多兩顆）
        return Collections.unmodifiableMap(map);
    }

    /** 不是某一種戰星、但跟戰星的獲得有關的屬性（2026-10）：charge.extra、charge.periodic、charge.event_cooldown.事件。 */
    public static boolean isGenerationStat(final String stat) {
        return stat.equals("charge.extra") || stat.equals("charge.extra_random") || stat.equals("charge.periodic")
                || stat.startsWith("charge.event_cooldown.") && EVENTS.containsKey(stat.substring("charge.event_cooldown.".length()));
    }

    /** 擊中時獲得的那一種（可以加技能種類的標籤：「近戰擊中時」）。 */
    public static boolean isOnHit(final String stat) {
        return stat.startsWith(StatKeys.CHARGE_ON_HIT) && chargeOf(stat) != null;
    }

    /** 屬性鍵後面的戰星代號；不是戰星屬性回傳 null。 */
    public static String chargeOf(final String stat) {
        if (stat.startsWith("charge.event.")) { // 2026-10：charge.event.事件.代號
            final String rest = stat.substring("charge.event.".length());
            final int dot = rest.lastIndexOf('.');
            if (dot <= 0 || !EVENTS.containsKey(rest.substring(0, dot))) {
                return null;
            }
            final String id = rest.substring(dot + 1);
            return id.matches("[a-z_]+") ? id : null;
        }
        for (final String prefix : List.of(StatKeys.CHARGE_CHANCE, StatKeys.CHARGE_ON_HIT, StatKeys.CHARGE_MAX, "charge.extra.")) {
            if (stat.startsWith(prefix)) {
                final String id = stat.substring(prefix.length());
                return id.matches("[a-z_]+") ? id : null;
            }
        }
        return null;
    }

    /**
     * 一條戰星屬性的說明；不是戰星屬性回傳 null。
     *
     * @param prefix 技能種類的標籤寫成的前綴（「近戰」「攻擊」；沒有是空字串），只有擊中時獲得用得到
     */
    public static String describe(final String stat, final double value, final String prefix) {
        final String support = supportText(stat, value);
        if (support != null) {
            return support; // 2026-10-24：戰星輔助
        }
        if (stat.equals("charge.extra")) {
            return "獲得戰星時 " + number(value) + "% 機率多獲得一顆同種的";
        }
        if (stat.equals("charge.extra_random")) {
            return "獲得戰星時 " + number(value) + "% 機率多獲得一顆隨機種類的";
        }
        if (stat.equals("charge.periodic")) {
            return "每分鐘獲得 " + number(value) + " 顆戰星（每 " + number(60 / Math.max(0.01, value)) + " 秒一顆；照最高的能力值：力量" + name("endurance")
                    + "、敏捷" + name("frenzy") + "、智慧" + name("power") + "）";
        }
        if (stat.startsWith("charge.event_cooldown.")) {
            final String event = EVENTS.get(stat.substring("charge.event_cooldown.".length()));
            return event == null ? null : event + "給的戰星每 " + number(value) + " 秒最多一次";
        }
        final String id = chargeOf(stat);
        if (id == null) {
            return null;
        }
        final String name = name(id);
        if (stat.startsWith("charge.event.")) {
            final String rest = stat.substring("charge.event.".length());
            final String event = EVENTS.get(rest.substring(0, rest.lastIndexOf('.')));
            return event + (value >= 100 ? "獲得一顆" : " " + number(value) + "% 機率獲得一顆") + name;
        }
        if (stat.startsWith("charge.extra.")) {
            return "獲得一顆" + name + "時 " + number(value) + "% 機率多獲得一顆";
        }
        if (stat.startsWith(StatKeys.CHARGE_MAX)) {
            return (value >= 0 ? "+" : "") + number(value) + " " + name + "上限";
        }
        if (stat.startsWith(StatKeys.CHARGE_ON_HIT)) {
            return prefix + "擊中時 " + number(value) + "% 機率獲得一顆" + name;
        }
        return trigger(id) + " " + number(value) + "% 機率獲得一顆" + name;
    }

    private static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** 2026-10-24：戰星輔助的屬性（charge.sup.*）說明；不是回傳 null。 */
    static String supportText(final String stat, final double value) {
        if (!stat.startsWith("charge.sup.") && !stat.startsWith(ChargeService.LOSE_AT_MAX) && !stat.startsWith(ChargeService.ZONE_END)
                && !stat.startsWith(ChargeService.ON_PARRIED) && !stat.startsWith(ChargeService.MINION_CULL) && !java.util.Set.of(ChargeService.RESONANCE, ChargeService.REGULATION_CONSUME,
                ChargeService.EVENT_COOLDOWN_SHARED, ChargeService.ON_MAX_RAGE).contains(stat)) {
            return null;
        }
        if (stat.startsWith(ChargeService.SUP_UNIQUE)) {
            final String rest = stat.substring(ChargeService.SUP_UNIQUE.length());
            final int dot = rest.lastIndexOf('.');
            final String event = dot <= 0 ? null : EVENTS.get(rest.substring(0, dot));
            return dot <= 0 ? null : "對傳奇怪物" + (event == null ? "" : event.replace("時", "")) + "時直接獲得 " + number(value) + " 顆" + name(rest.substring(dot + 1));
        }
        if (stat.startsWith(ChargeService.LOSE_AT_MAX)) {
            return name(stat.substring(ChargeService.LOSE_AT_MAX.length())) + "滿了就全部失去，並讓你感電";
        }
        if (stat.startsWith(ChargeService.ZONE_END)) {
            return "地面效果持續到結束時獲得 " + number(value) + " 顆" + name(stat.substring(ChargeService.ZONE_END.length()));
        }
        if (stat.startsWith(ChargeService.MINION_CULL)) {
            return "召喚物每撲殺一個敵人，你獲得 " + number(value) + " 顆" + name(stat.substring(ChargeService.MINION_CULL.length()));
        }
        if (stat.startsWith(ChargeService.ON_PARRIED)) {
            return "擊中被招架的敵人時用掉招架，放出震波並獲得 " + number(Math.round(value)) + " 顆" + name(stat.substring(ChargeService.ON_PARRIED.length()))
                    + "（寶石 8 級、16 級各多一顆；一次施放一次）";
        }
        return switch (stat) {
            case ChargeService.RESONANCE -> "該獲得" + name("frenzy") + "時改獲得" + name("power") + "、該獲得" + name("endurance") + "時改獲得"
                    + name("frenzy") + "、該獲得" + name("power") + "時改獲得" + name("endurance");
            case ChargeService.REGULATION_CONSUME -> "每 " + number(value) + " 秒消耗每種戰星各一顆";
            case ChargeService.EVENT_COOLDOWN_SHARED -> "上面這幾種事件給的戰星共用冷卻";
            case ChargeService.ON_MAX_RAGE -> "怒氣滿時獲得一顆隨機戰星（每 " + number(value) + " 秒最多一次）";
            case ChargeService.SUP_KEEP -> "消耗戰星時 " + number(value) + "% 機率不扣掉，但效果照拿";
            case ChargeService.SUP_DOUBLE -> "消耗戰星的效果 " + number(value) + "% 機率加倍";
            case ChargeService.SUP_INHIBIT -> "不消耗戰星與灌注；你身上每有一種戰星或灌注，更多 " + number(value) + "% 傷害";
            case ChargeService.SUP_FEROCITY -> "放的時候消耗一顆" + name("frenzy") + "：這一次更多 " + number(value) + "% 技能速度；不會產生" + name("frenzy");
            case ChargeService.SUP_ABIDING_HEX -> "放的時候消耗一顆" + name("power") + "：這一次更多 " + number(value) + "% 持續時間";
            case ChargeService.SUP_JAGGED -> "放的時候消耗一顆" + name("endurance") + "：在落點留下 " + number(value) + " 秒的碎石地（裡面的敵人減速 20%）";
            case ChargeService.SUP_CREEPING -> "造出冰晶時消耗一顆" + name("power") + "：那顆冰晶周圍結出冰緩地（每秒變大 50%，最多 4 倍）";
            case ChargeService.SUP_SPARKS -> "產生" + name("power") + "時強化接下來 " + number(value) + " 次攻擊：強化的攻擊用出去時射出游雷";
            case ChargeService.SUP_SPARK_PROJECTILES -> "雷星每次射出 " + number(value) + " 顆游雷";
            case ChargeService.SUP_OFFERING_POWER -> "祭柱還在的時候每 " + number(value) + " 秒獲得一顆" + name("power");
            case ChargeService.SUP_OFFERING_SACRIFICE -> "放的時候犧牲目前生命的 " + number(value) + "%";
            case ChargeService.SUP_OFFERING_REGEN -> "祭柱倒下後每秒回復 " + number(value) + "% 生命與魔力，持續的時間等於祭柱撐了多久";
            case ChargeService.SUP_COMBO_POWER -> "用掉連擊時，每一層 " + number(value) + "% 機率獲得一顆" + name("power") + "（超過 100% 的部分必定給）";
            case ChargeService.SUP_PERFECT_ENDURANCE -> "完美時機打中敵人時獲得 " + number(value) + " 顆" + name("endurance");
            default -> null;
        };
    }
}
