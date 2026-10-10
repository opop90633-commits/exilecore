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

    /** 是不是戰星的屬性（charge.chance／charge.on_hit／charge.max.代號）。charge.duration 不算（它是一般的增加）。 */
    public static boolean isChargeStat(final String stat) {
        return chargeOf(stat) != null;
    }

    /** 擊中時獲得的那一種（可以加技能種類的標籤：「近戰擊中時」）。 */
    public static boolean isOnHit(final String stat) {
        return stat.startsWith(StatKeys.CHARGE_ON_HIT) && chargeOf(stat) != null;
    }

    /** 屬性鍵後面的戰星代號；不是戰星屬性回傳 null。 */
    public static String chargeOf(final String stat) {
        for (final String prefix : List.of(StatKeys.CHARGE_CHANCE, StatKeys.CHARGE_ON_HIT, StatKeys.CHARGE_MAX)) {
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
        final String id = chargeOf(stat);
        if (id == null) {
            return null;
        }
        final String name = name(id);
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
}
