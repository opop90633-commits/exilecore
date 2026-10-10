package tw.exilecore.admin;

import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * 管理網頁發「指定詞綴（與數值）」的裝備、傳奇（2026-10）。存在 admin_deliveries.affixes，一條一段，用分號隔開：
 * <pre>詞綴代號[@階級代號][:變數=值,變數=值]</pre>
 * 值是物品上存的整數（小數的詞綴：網頁先乘上除數）。
 * <ul>
 *   <li>裝備：照填的詞綴做（不再隨機擲別的）；沒寫階級 = 最強的一階；沒寫的變數在那一階的範圍裡擲。
 *       稀有度至少是選的那個，放不下就往上升（普通 → 魔法 → 稀有）。</li>
 *   <li>傳奇：只改數值（詞綴是傳奇本身的，階級不用寫）；沒寫的照常擲。</li>
 * </ul>
 * 數值可以超出範圍（補償、測試用），說明裡會標出來。
 */
public final class AffixSpec {

    /** 一條。 */
    public record Entry(String id, Integer tier, Map<String, Integer> vars) {
    }

    public static final int MAX_ENTRIES = 12;
    public static final int MAX_LENGTH = 2000;
    private static final int VALUE_LIMIT = 100_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final Pattern VAR = Pattern.compile("[A-Za-z0-9_]+");

    private AffixSpec() {
    }

    /** 拆開；空的回傳空清單。寫錯丟 IllegalArgumentException。 */
    public static List<Entry> parse(final String text) {
        final List<Entry> list = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("指定的詞綴太長了");
        }
        for (final String raw : text.split(";")) {
            final String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            final int colon = part.indexOf(':');
            String head = colon < 0 ? part : part.substring(0, colon).trim();
            final String body = colon < 0 ? "" : part.substring(colon + 1).trim();
            Integer tier = null;
            final int at = head.indexOf('@');
            if (at >= 0) {
                try {
                    tier = Integer.parseInt(head.substring(at + 1).trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException("詞綴的階級要是數字：" + part);
                }
                head = head.substring(0, at).trim();
            }
            if (!ID.matcher(head).matches()) {
                throw new IllegalArgumentException("詞綴代號寫錯了：" + part);
            }
            final Map<String, Integer> vars = new LinkedHashMap<>();
            if (!body.isEmpty()) {
                for (final String pair : body.split(",")) {
                    final String p = pair.trim();
                    if (p.isEmpty()) {
                        continue;
                    }
                    final int eq = p.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("數值要寫成 變數=值：" + part);
                    }
                    final String name = p.substring(0, eq).trim();
                    if (!VAR.matcher(name).matches()) {
                        throw new IllegalArgumentException("變數名稱寫錯了：" + part);
                    }
                    final int value;
                    try {
                        value = Integer.parseInt(p.substring(eq + 1).trim());
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException("數值要是整數：" + part);
                    }
                    if (Math.abs((long) value) > VALUE_LIMIT) {
                        throw new IllegalArgumentException("數值太大了：" + part);
                    }
                    vars.put(name, value);
                }
            }
            list.add(new Entry(head, tier, vars));
        }
        if (list.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("一件最多指定 " + MAX_ENTRIES + " 條詞綴");
        }
        return list;
    }

    /** 整理成固定的寫法（存進資料庫的）；空的回傳 null。 */
    public static String format(final List<Entry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        final StringBuilder out = new StringBuilder();
        for (final Entry entry : entries) {
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(entry.id());
            if (entry.tier() != null) {
                out.append('@').append(entry.tier());
            }
            if (!entry.vars().isEmpty()) {
                out.append(':');
                boolean first = true;
                for (final Map.Entry<String, Integer> var : entry.vars().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(var.getKey()).append('=').append(var.getValue());
                }
            }
        }
        return out.toString();
    }

    // ---- 裝備 ----

    /**
     * 照指定的詞綴做裝備（data 是剛生成的，已經有固定詞綴與插槽；原本擲的詞綴會清掉）。
     *
     * @return 做不到的原因；成功回傳 null
     */
    public static String applyToItem(final ItemRepository repository, final ItemGenerator generator, final ItemData data,
                                     final List<Entry> entries, final RandomGenerator random) {
        data.affixes().clear();
        final Set<String> groups = new HashSet<>();
        for (final Entry entry : entries) {
            final Affix affix = repository.affix(entry.id());
            if (affix == null) {
                return "沒有這條詞綴：" + entry.id();
            }
            if (!groups.add(affix.group())) {
                return "「" + plain(affix.text()) + "」跟前面的詞綴是同一類（同一類只能有一條）";
            }
            final String problem = generator.forceAffix(data, affix.id(), entry.tier(), random);
            if (problem != null) {
                return problem;
            }
            final RolledAffix rolled = data.affixes().getLast();
            final String bad = checkVars(affix, rolled.tier(), entry.vars());
            if (bad != null) {
                return bad;
            }
            final Map<String, Integer> vars = new LinkedHashMap<>(rolled.vars());
            vars.putAll(entry.vars());
            rolled.setVars(vars);
        }
        if (data.rarity() == Rarity.RARE) {
            generator.ensureRareName(data, random);
        }
        data.setIdentified(true);
        return null;
    }

    /** 選了稀有度、還沒加詞綴的時候：照稀有度開始（稀有要有名字）。 */
    public static void startRarity(final ItemGenerator generator, final ItemData data, final Rarity rarity, final RandomGenerator random) {
        data.affixes().clear();
        data.setRarity(rarity);
        data.setName(null);
        if (rarity == Rarity.RARE) {
            generator.ensureRareName(data, random);
        }
    }

    // ---- 傳奇 ----

    /** 檢查傳奇的指定數值；回傳做不到的原因（沒問題 null）。 */
    public static String checkUnique(final ItemRepository repository, final UniqueItem unique, final List<Entry> entries) {
        final Set<String> seen = new HashSet<>();
        for (final Entry entry : entries) {
            if (!unique.affixIds().contains(entry.id())) {
                return unique.name() + " 沒有這條詞綴：" + entry.id();
            }
            if (!seen.add(entry.id())) {
                return entry.id() + " 寫了兩次";
            }
            final Affix affix = repository.affix(entry.id());
            if (affix == null) {
                return "沒有這條詞綴：" + entry.id();
            }
            final String bad = checkVars(affix, affix.tiers().getFirst().tier(), entry.vars());
            if (bad != null) {
                return bad;
            }
        }
        return null;
    }

    /** 把指定的數值寫進剛做好的傳奇。 */
    public static void applyToUnique(final ItemData data, final List<Entry> entries) {
        for (final Entry entry : entries) {
            for (final RolledAffix rolled : data.affixes()) {
                if (rolled.id().equals(entry.id())) {
                    final Map<String, Integer> vars = new LinkedHashMap<>(rolled.vars());
                    vars.putAll(entry.vars());
                    rolled.setVars(vars);
                }
            }
        }
    }

    // ---- 說明 ----

    /** 給人看的：每條詞綴的文字（沒指定的數值寫「隨機」，超出範圍的標出來）。 */
    public static String describe(final ItemRepository repository, final List<Entry> entries, final boolean unique) {
        final List<String> parts = new ArrayList<>();
        for (final Entry entry : entries) {
            final Affix affix = repository.affix(entry.id());
            if (affix == null) {
                parts.add(entry.id());
                continue;
            }
            final int tierId = unique || entry.tier() == null ? affix.tiers().getFirst().tier() : entry.tier();
            String text = plain(Affix.substitute(affix.text(), entry.vars())).replaceAll("\\{[^}]*}", "?");
            if (!unique) {
                text = "T" + affix.rank(tierId) + " " + text;
            }
            if (outOfRange(affix.ranges(tierId), entry.vars())) {
                text += "（超出範圍）";
            }
            parts.add(text);
        }
        return String.join("、", parts);
    }

    private static String checkVars(final Affix affix, final int tierId, final Map<String, Integer> vars) {
        final Map<String, int[]> ranges = affix.ranges(tierId);
        for (final String name : vars.keySet()) {
            if (!ranges.containsKey(name)) {
                return "「" + plain(affix.text()) + "」沒有變數 " + name + "（有：" + String.join("、", ranges.keySet()) + "）";
            }
        }
        return null;
    }

    private static boolean outOfRange(final Map<String, int[]> ranges, final Map<String, Integer> vars) {
        for (final Map.Entry<String, Integer> var : vars.entrySet()) {
            final int[] range = ranges.get(var.getKey());
            if (range != null && range.length == 2
                    && (var.getValue() < Math.min(range[0], range[1]) || var.getValue() > Math.max(range[0], range[1]))) {
                return true;
            }
        }
        return false;
    }

    /** 拿掉 MiniMessage 標籤。 */
    static String plain(final String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").replace("\\<", "<");
    }

    // ---- 網頁用的清單 ----

    /** 這個基底能出的詞綴（前綴在前），每條：代號、類型、文字、各階的範圍與變數的除數。 */
    public static List<Map<String, Object>> catalogFor(final ItemRepository repository, final ItemBase base) {
        final List<Map<String, Object>> list = new ArrayList<>();
        for (final AffixType type : AffixType.values()) {
            for (final Affix affix : repository.affixes()) {
                if (affix.type() != type || affix.spawnTags().contains(UniqueItem.SPAWN_TAG) || !affix.canSpawnOn(base)) {
                    continue;
                }
                list.add(describeAffix(affix, true));
            }
        }
        return list;
    }

    /** 這件傳奇的詞綴（照傳奇的順序）：代號、文字、範圍。 */
    public static List<Map<String, Object>> catalogFor(final ItemRepository repository, final UniqueItem unique) {
        final List<Map<String, Object>> list = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final String id : unique.affixIds()) {
            final Affix affix = repository.affix(id);
            if (affix != null && seen.add(id)) {
                list.add(describeAffix(affix, false));
            }
        }
        return list;
    }

    private static Map<String, Object> describeAffix(final Affix affix, final boolean allTiers) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", affix.id());
        row.put("type", affix.type().name().toLowerCase(Locale.ROOT));
        row.put("group", affix.group());
        row.put("text", plain(affix.text()));
        final Map<String, Integer> divisors = new LinkedHashMap<>();
        final java.util.regex.Matcher matcher = Pattern.compile("\\{([A-Za-z0-9_]+)(?:/(\\d+))?}").matcher(affix.text());
        while (matcher.find()) {
            int divisor = 1;
            if (matcher.group(2) != null) {
                try {
                    divisor = Integer.parseInt(matcher.group(2));
                } catch (final NumberFormatException ignored) {
                    divisor = 1;
                }
            }
            divisors.putIfAbsent(matcher.group(1), Math.max(1, divisor));
        }
        row.put("div", divisors);
        final List<Map<String, Object>> tiers = new ArrayList<>();
        for (final AffixTier tier : affix.tiers()) {
            final Map<String, Object> t = new LinkedHashMap<>();
            t.put("tier", tier.tier());
            t.put("rank", affix.rank(tier.tier()));
            t.put("ilvl", tier.ilvl());
            t.put("vars", tier.vars());
            tiers.add(t);
            if (!allTiers) {
                break;
            }
        }
        row.put("tiers", tiers);
        return row;
    }
}
