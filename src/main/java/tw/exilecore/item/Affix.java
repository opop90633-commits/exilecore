package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一條詞綴的定義（設定檔）：
 * <pre>
 * life_flat:
 *   type: prefix
 *   group: life
 *   spawn_tags: [armour, jewellery, shield]
 *   exclude_tags: [caster]          # 可省略：帶這些標籤的基底不出（例如法杖、長杖不出攻擊用的詞綴）
 *   names: [星骸的, 天賜的, 神軀的, 不朽的, 雄壯的, 壯碩的, 強健的, 健康的]   # 依排名 1（最強）→ N
 *   text: "+{value} 最大生命"
 *   tiers:
 *     - { tier: -1, ilvl: 78, value: [115, 129], weight: 50 }
 *     - { tier: 0, ilvl: 68, value: [100, 114], weight: 90 }
 *     - { tier: 1, ilvl: 60, value: [80, 99], weight: 150 }
 *   mods:
 *     - { stat: life.max, type: FLAT, value: "{value}" }
 * </pre>
 *
 * @param id        代號
 * @param type      前綴／後綴
 * @param group     同群組的詞綴一件物品只會出現一個
 * @param spawnTags 只出現在帶這些標籤（任一）的基底上
 * @param excludeTags 基底帶這些標籤（任一）就不出，優先於 spawnTags
 * <p>
 * 階級代號（tier）越小越強；物品上存的是代號，所以後來加更強的階級時用 0、-1…，舊物品不用轉換。
 * 玩家看到的「T1」是排名（{@link #rank}）：這條詞綴現在最強的階級是 T1。
 *
 * @param names     各排名的名稱，索引 = 排名 − 1；不夠長時用最後一個
 * @param text      顯示文字，變數用大括號
 * @param tiers     階級，依代號遞增排序（第一個最強）
 * @param mods      修飾器範本
 */
public record Affix(String id, AffixType type, String group, Set<String> spawnTags, Set<String> excludeTags, Set<String> requireTags,
                    List<String> names, String text, List<AffixTier> tiers, List<AffixMod> mods) {

    public Affix {
        spawnTags = Set.copyOf(spawnTags);
        excludeTags = Set.copyOf(excludeTags);
        requireTags = requireTags == null ? Set.of() : Set.copyOf(requireTags);
        names = List.copyOf(names);
        tiers = List.copyOf(tiers);
        mods = List.copyOf(mods);
    }

    public static Affix parse(final String id, final YamlNode node) {
        final AffixType type = AffixType.parse(node.requireString("type"));
        final String group = node.string("group", id);
        final Set<String> spawnTags = new HashSet<>(node.strings("spawn_tags"));
        if (spawnTags.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：spawn_tags 不能是空的");
        }
        final Set<String> excludeTags = new HashSet<>(node.strings("exclude_tags"));
        // 2026-10：require_tags 基底要全部都有（PoE2 的混合防具：力量＋敏捷才出「增加護甲與閃避」）
        final Set<String> requireTags = new HashSet<>(node.strings("require_tags"));
        final List<String> names = node.strings("names");
        final String text = node.requireString("text");
        final List<AffixTier> tiers = new ArrayList<>();
        for (final YamlNode tierNode : node.list("tiers")) {
            tiers.add(AffixTier.parse(tierNode));
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一個階級");
        }
        tiers.sort(Comparator.comparingInt(AffixTier::tier));
        for (int i = 1; i < tiers.size(); i++) {
            if (tiers.get(i).tier() == tiers.get(i - 1).tier()) {
                throw new IllegalArgumentException(node.path() + ".tiers：階級代號 " + tiers.get(i).tier() + " 重複");
            }
        }
        final List<AffixMod> mods = new ArrayList<>();
        for (final YamlNode modNode : node.list("mods")) {
            mods.add(AffixMod.parse(modNode));
        }
        if (mods.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一條 mods");
        }
        checkVariables(node.path(), text, tiers, mods);
        return new Affix(id, type, group, spawnTags, excludeTags, requireTags, names, text, tiers, mods);
    }

    /** 文字與 mods 用到的變數，每個階級都要有（不然擲出來的詞綴會缺值、顯示會留著大括號）。 */
    private static void checkVariables(final String where, final String text, final List<AffixTier> tiers, final List<AffixMod> mods) {
        final Set<String> used = new HashSet<>();
        final java.util.regex.Matcher matcher = AffixMod.VARIABLE.matcher(text);
        while (matcher.find()) {
            used.add(matcher.group(1));
            AffixMod.divisorOf(matcher.group(2), where + ".text");
        }
        for (final AffixMod mod : mods) {
            if (mod.variable() != null) {
                used.add(mod.variable());
            }
        }
        for (final AffixTier tier : tiers) {
            for (final String variable : used) {
                if (!tier.vars().containsKey(variable)) {
                    throw new IllegalArgumentException(where + "：階級 " + tier.tier() + " 沒有變數 " + variable
                            + "（text 或 mods 用到了它）");
                }
            }
        }
    }

    /** 這條詞綴能不能出現在這個基底上（帶 exclude_tags 的不行，再看有沒有 spawn_tags 的任一個）。 */
    public boolean canSpawnOn(final ItemBase base) {
        if (!base.tags().containsAll(requireTags)) {
            return false;
        }
        for (final String tag : base.tags()) {
            if (excludeTags.contains(tag)) {
                return false;
            }
        }
        for (final String tag : base.tags()) {
            if (spawnTags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 物品等級允許的階級（ilvl ≤ 物品等級）。 */
    public List<AffixTier> tiersFor(final int itemLevel) {
        final List<AffixTier> list = new ArrayList<>();
        for (final AffixTier tier : tiers) {
            if (tier.ilvl() <= itemLevel) {
                list.add(tier);
            }
        }
        if (banded() && list.size() > 1) {
            // 2026-10-26（PoE2 換界石）：每一個換界石階段帶只會出那一段的數值（T16 一定是最高的那一階），不會擲到低階
            AffixTier best = list.getFirst();
            for (final AffixTier tier : list) {
                if (tier.ilvl() > best.ilvl()) {
                    best = tier;
                }
            }
            return List.of(best);
        }
        return list;
    }

    /** 2026-10-26：地圖（換界石）詞綴照階段帶，只出物品等級允許的最高那一階（PoE2）。 */
    public boolean banded() {
        return spawnTags.contains("map") || spawnTags.contains("map_desecrated");
    }

    public AffixTier tier(final int number) {
        for (final AffixTier tier : tiers) {
            if (tier.tier() == number) {
                return tier;
            }
        }
        throw new IllegalArgumentException("詞綴 " + id + " 沒有階級 " + number);
    }

    /**
     * 這個階級在這條詞綴裡的排名（1 = 最強，玩家看到的「T1」）：照代號由小到大數。
     * 找不到這個代號（詞綴的階級被刪掉了）時回傳代號本身（至少 1）。
     */
    public int rank(final int tierId) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).tier() == tierId) {
                return i + 1;
            }
        }
        return Math.max(1, tierId);
    }

    /** 這個階級的名稱（前綴形容詞或後綴稱號），照排名。 */
    public String nameFor(final int tierId) {
        if (names.isEmpty()) {
            return "";
        }
        final int index = Math.min(names.size(), rank(tierId)) - 1;
        return names.get(index);
    }

    /** 把顯示文字裡的變數換成擲出的值。 */
    public String render(final Map<String, Integer> vars) {
        return substitute(text, vars);
    }

    /** 這個階級每個變數的範圍（詞綴的階級被刪掉了是空的）。 */
    public Map<String, int[]> ranges(final int tierId) {
        for (final AffixTier tier : tiers) {
            if (tier.tier() == tierId) {
                return tier.vars();
            }
        }
        return Map.of();
    }

    /** 範圍的顏色（數值後面的「(10–19)」）。 */
    public static final String RANGE_COLOR = "#8c8c8c";

    /**
     * 一個擲出的整數照除數顯示：除數 1 就是整數；10、100 是小數（5 → 0.5、10 → 1、15 → 1.5），拿掉多餘的 0。
     */
    public static String number(final int value, final int divisor) {
        if (divisor <= 1) {
            return String.valueOf(value);
        }
        return java.math.BigDecimal.valueOf(value).movePointLeft(divisor >= 100 ? 2 : 1).stripTrailingZeros().toPlainString();
    }

    /**
     * 顯示文字（MiniMessage；文字已經跳脫）：每個數值後面接著這一階的範圍，PoE 按住 Alt 看到的樣子，例如「+15(10–19) 最大生命」、
     * 「附加 5(3–6) 到 12(10–14) 物理傷害」。上下限一樣（固定值）的不接。變數寫成 {名字/10} 的是小數（見 {@link AffixMod}）。
     */
    public static String substituteWithRanges(final String rawTemplate, final Map<String, Integer> vars, final Map<String, int[]> ranges) {
        final String template = choices(rawTemplate, vars); // 2026-10-26
        final StringBuilder out = new StringBuilder();
        final java.util.regex.Matcher matcher = AffixMod.VARIABLE.matcher(template);
        int last = 0;
        while (matcher.find()) {
            out.append(escape(template.substring(last, matcher.start())));
            final Integer value = vars.get(matcher.group(1));
            if (value == null) {
                out.append(escape(matcher.group()));
            } else {
                final int divisor = AffixMod.divisorOf(matcher.group(2), template);
                out.append(number(value, divisor));
                final int[] range = ranges.get(matcher.group(1));
                if (range != null && range.length == 2 && range[0] != range[1]) {
                    // 2026-10-22：模板寫「-{value}%」的（石衛鎧的 -x% 抗性上限）範圍也帶負號：-1(-1～-5)%，以前寫成 -1(1–5)% 像是正的
                    final boolean negative = matcher.start() > 0 && template.charAt(matcher.start() - 1) == '-';
                    final String low = number(Math.min(range[0], range[1]), divisor);
                    final String high = number(Math.max(range[0], range[1]), divisor);
                    out.append('<').append(RANGE_COLOR).append(">(")
                            .append(negative ? "-" + low + "～-" + high : low + "–" + high)
                            .append(")</").append(RANGE_COLOR).append('>');
                }
            }
            last = matcher.end();
        }
        out.append(escape(template.substring(last)));
        return out.toString();
    }

    /**
     * 圖鑑用（2026-10）：這一階的文字，數值換成範圍，例如「+(10–19) 最大生命」、「附加 (3–6) 到 (10–14) 物理傷害」；
     * 上下限一樣的寫一個數字。回傳 MiniMessage（文字已經跳脫）。
     */
    public String rangeText(final int tierId) {
        final Map<String, int[]> ranges = ranges(tierId);
        final StringBuilder out = new StringBuilder();
        final String text = choices(this.text, Map.of()); // 2026-10-26：{名字:甲|乙} 在圖鑑列出全部（甲／乙）
        final java.util.regex.Matcher matcher = AffixMod.VARIABLE.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(escape(text.substring(last, matcher.start())));
            final int[] range = ranges.get(matcher.group(1));
            if (range == null || range.length != 2) {
                out.append(escape(matcher.group()));
            } else {
                final int divisor = AffixMod.divisorOf(matcher.group(2), text);
                final int low = Math.min(range[0], range[1]);
                final int high = Math.max(range[0], range[1]);
                out.append(low == high ? number(low, divisor) : "(" + number(low, divisor) + "–" + number(high, divisor) + ")");
            }
            last = matcher.end();
        }
        out.append(escape(text.substring(last)));
        return out.toString();
    }

    /** MiniMessage 的跳脫（跟 ItemRenderer.escape 一樣；這裡不能用它：測試沒有 Bukkit）。 */
    static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** 2026-10-26：文字裡的選項 {@code {名字:甲|乙|丙}}（永恆珠寶的征服者）。 */
    static final java.util.regex.Pattern CHOICE = java.util.regex.Pattern.compile("\\{([A-Za-z0-9_]+):([^{}]*)}");

    /**
     * 2026-10-26：把 {@code {名字:甲|乙|丙}} 換成擲出的那一個（值 1 是甲）；沒有這個變數或超出範圍時列出全部（甲／乙／丙）。
     * 數值的 {@code {名字}} 不受影響。
     */
    public static String choices(final String template, final Map<String, Integer> vars) {
        if (template == null || template.indexOf(':') < 0) {
            return template;
        }
        final java.util.regex.Matcher matcher = CHOICE.matcher(template);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String[] options = matcher.group(2).split("\\|", -1);
            final Integer value = vars == null ? null : vars.get(matcher.group(1));
            final String chosen = value != null && value >= 1 && value <= options.length ? options[value - 1] : String.join("／", options);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(chosen));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String substitute(final String rawTemplate, final Map<String, Integer> vars) {
        final String template = choices(rawTemplate, vars); // 2026-10-26
        final StringBuilder out = new StringBuilder();
        final java.util.regex.Matcher matcher = AffixMod.VARIABLE.matcher(template);
        int last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start());
            final Integer value = vars.get(matcher.group(1));
            out.append(value == null ? matcher.group() : number(value, AffixMod.divisorOf(matcher.group(2), template)));
            last = matcher.end();
        }
        out.append(template, last, template.length());
        return out.toString();
    }
}
