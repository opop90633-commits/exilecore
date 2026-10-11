package tw.exilecore.combat;

import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 戰鬥數據（2026-10）：一個玩家最近幾秒打出的傷害（每秒，分類型）、受到的傷害（每秒，分類型）、受到的最大一擊。純邏輯，不依賴 Bukkit。
 * <p>
 * 用滑動視窗：每半秒（{@link #BUCKET_TICKS}）一格，留最近 {@link #BUCKETS} 格（5 秒）。剛開打不到 5 秒時照實際打了多久算，
 * 至少算 1 秒（第一下不會算成一秒幾萬）。停手超過 5 秒重新算。
 * 自己打的、召喚物與圖騰打的、點燃流血中毒的每一跳都算「打出的」；扣掉的護盾、生命、魔力算「受到的」。
 * 持續傷害（流血、中毒、點燃）另外記一份，側欄多一行「持續」分開列（2026-10：以前併在物理、混沌、火焰裡看不出來）；
 * 持續傷害的一跳不算「最大一擊」。
 * <p>
 * 2026-10：打出的傷害另外照「哪個技能打的」記一份（技能的名字；地面效果、引爆這些算在原本的技能上，召喚物算在召喚技能上，
 * 點燃、流血、中毒各算一項「××（持續）」），每一項都分傷害類型：側欄的「技能明細」與 /dps 的整場統計用。
 */
public final class CombatMeter {

    /** 一格多長（tick）。 */
    public static final int BUCKET_TICKS = 10;
    /** 留幾格。 */
    public static final int BUCKETS = 10;
    /** 視窗多長（tick，5 秒）。 */
    public static final int WINDOW_TICKS = BUCKET_TICKS * BUCKETS;
    private static final int TYPES = DamageType.values().length;
    private static final int AILMENTS = AilmentType.values().length;
    /** 側欄「持續」那一行列哪幾種（照這個順序比多少）。 */
    private static final List<AilmentType> DOTS = List.of(AilmentType.BLEED, AilmentType.POISON, AilmentType.IGNITE);

    private final long[] ids = new long[BUCKETS];
    private final double[][] dealt = new double[BUCKETS][TYPES];
    private final double[][] taken = new double[BUCKETS][TYPES];
    private final double[][] dotDealt = new double[BUCKETS][AILMENTS];
    private final double[][] dotTaken = new double[BUCKETS][AILMENTS];
    private final double[] biggest = new double[BUCKETS];
    private final DamageType[] biggestType = new DamageType[BUCKETS];
    /** 每個技能打出的：技能名 → [格][類型]（視窗裡沒有數字的在 {@link #snapshot} 清掉）。 */
    private final Map<String, double[][]> skillDealt = new LinkedHashMap<>();
    /** 這一段戰鬥到現在每個技能打出的總傷害：技能名 → [類型]（停手超過一個視窗、重新開打時清掉）。 */
    private final Map<String, double[]> fight = new LinkedHashMap<>();
    /** 這一段戰鬥從哪個 tick 開始。 */
    private long started = Long.MIN_VALUE;
    /** 最後一次有動靜。 */
    private long last = Long.MIN_VALUE;

    public CombatMeter() {
        Arrays.fill(ids, Long.MIN_VALUE);
    }

    /**
     * 一個時間點看到的數字。
     *
     * @param seconds       算了幾秒（1 到 5）
     * @param dealt         打出的每秒傷害（照 {@link DamageType} 的順序）
     * @param taken         受到的每秒傷害（同上）
     * @param biggest       視窗裡受到的最大一擊（扣掉的護盾＋生命＋魔力；持續傷害的一跳不算）
     * @param biggestType   最大一擊主要是哪一種傷害（沒有是 null）
     * @param dotDealt      打出的每秒持續傷害（照 {@link AilmentType} 的順序；已經算在 dealt 裡）
     * @param dotTaken      受到的每秒持續傷害（同上；已經算在 taken 裡）
     */
    public record Snapshot(double seconds, double[] dealt, double[] taken, double biggest, DamageType biggestType,
                           double[] dotDealt, double[] dotTaken, List<SkillRow> skills) {

        /** 沒有技能明細的（舊的呼叫法）。 */
        public Snapshot(final double seconds, final double[] dealt, final double[] taken, final double biggest, final DamageType biggestType,
                        final double[] dotDealt, final double[] dotTaken) {
            this(seconds, dealt, taken, biggest, biggestType, dotDealt, dotTaken, List.of());
        }

        public double dps() {
            return Arrays.stream(dealt).sum();
        }

        public double takenPerSecond() {
            return Arrays.stream(taken).sum();
        }
    }

    /**
     * 一個技能的數字（照 {@link DamageType} 的順序）：{@link Snapshot#skills()} 裡是每秒傷害，{@link Fight#skills()} 裡是總傷害。
     */
    public record SkillRow(String name, double[] amounts) {
        public double total() {
            return Arrays.stream(amounts).sum();
        }
    }

    /**
     * 一整段戰鬥的統計（/dps）。
     *
     * @param seconds 打了幾秒（第一下到最後一下，至少 1 秒）
     * @param skills  每個技能打出的總傷害（多到少）
     */
    public record Fight(double seconds, List<SkillRow> skills) {
        public double total() {
            double sum = 0;
            for (final SkillRow row : skills) {
                sum += row.total();
            }
            return sum;
        }
    }

    private int slot(final long tick) {
        final long id = Math.floorDiv(tick, BUCKET_TICKS);
        final int index = (int) Math.floorMod(id, (long) BUCKETS);
        if (ids[index] != id) {
            ids[index] = id;
            Arrays.fill(dealt[index], 0);
            Arrays.fill(taken[index], 0);
            Arrays.fill(dotDealt[index], 0);
            Arrays.fill(dotTaken[index], 0);
            biggest[index] = 0;
            biggestType[index] = null;
            for (final double[][] rows : skillDealt.values()) {
                Arrays.fill(rows[index], 0);
            }
        }
        return index;
    }

    private void touch(final long tick) {
        if (last == Long.MIN_VALUE || tick - last > WINDOW_TICKS) {
            started = tick; // 停手超過一個視窗：重新算
            fight.clear();
        }
        last = Math.max(last, tick);
    }

    /** 打出一下（各類型多少；減免之後的）。 */
    public void dealt(final long tick, final Map<DamageType, Double> parts) {
        dealt(tick, null, parts);
    }

    /** 打出一下，記在 skill 這個技能底下（null、空的 = 不分技能，只算總數）。 */
    public void dealt(final long tick, final String skill, final Map<DamageType, Double> parts) {
        boolean any = false;
        for (final Map.Entry<DamageType, Double> part : parts.entrySet()) {
            if (part.getKey() != null && part.getValue() != null && part.getValue() > 0) {
                if (!any) {
                    touch(tick);
                    any = true;
                }
                final int index = slot(tick);
                dealt[index][part.getKey().ordinal()] += part.getValue();
                bySkill(skill, index, part.getKey(), part.getValue());
            }
        }
    }

    private void bySkill(final String skill, final int index, final DamageType type, final double amount) {
        if (skill == null || skill.isBlank()) {
            return;
        }
        skillDealt.computeIfAbsent(skill, key -> new double[BUCKETS][TYPES])[index][type.ordinal()] += amount;
        fight.computeIfAbsent(skill, key -> new double[TYPES])[type.ordinal()] += amount;
    }

    /** 持續傷害在技能明細裡的名字：「流血（持續）」。 */
    public static String dotName(final AilmentType kind) {
        return kind.displayName() + "（持續）";
    }

    /** 打出的傷害（只有一種類型）。 */
    public void dealt(final long tick, final DamageType type, final double amount) {
        if (type == null || amount <= 0) {
            return;
        }
        touch(tick);
        dealt[slot(tick)][type.ordinal()] += amount;
    }

    /** 打出的一跳持續傷害：算進那種傷害類型，也另外記在「持續」。 */
    public void dealtDot(final long tick, final AilmentType kind, final DamageType type, final double amount) {
        if (kind == null || type == null || amount <= 0) {
            return;
        }
        touch(tick);
        final int index = slot(tick);
        dealt[index][type.ordinal()] += amount;
        dotDealt[index][kind.ordinal()] += amount;
        bySkill(dotName(kind), index, type, amount);
    }

    /**
     * 受到一下：actual 是真正扣掉的（護盾＋生命＋魔力），照 parts 的比例分給各類型（parts 是空的算物理）。
     */
    public void taken(final long tick, final Map<DamageType, Double> parts, final double actual) {
        if (actual <= 0) {
            return;
        }
        touch(tick);
        final int index = slot(tick);
        double sum = 0;
        for (final Map.Entry<DamageType, Double> part : parts.entrySet()) {
            if (part.getKey() != null && part.getValue() != null && part.getValue() > 0) {
                sum += part.getValue();
            }
        }
        DamageType main = DamageType.PHYSICAL;
        if (sum <= 0) {
            taken[index][DamageType.PHYSICAL.ordinal()] += actual;
        } else {
            double most = -1;
            for (final Map.Entry<DamageType, Double> part : parts.entrySet()) {
                if (part.getKey() == null || part.getValue() == null || part.getValue() <= 0) {
                    continue;
                }
                taken[index][part.getKey().ordinal()] += actual * part.getValue() / sum;
                if (part.getValue() > most) {
                    most = part.getValue();
                    main = part.getKey();
                }
            }
        }
        if (actual > biggest[index]) {
            biggest[index] = actual;
            biggestType[index] = main;
        }
    }

    /** 受到一下（只有一種類型）。 */
    public void taken(final long tick, final DamageType type, final double actual) {
        taken(tick, Map.of(type == null ? DamageType.PHYSICAL : type, 1.0), actual);
    }

    /** 受到一跳持續傷害（點燃、流血、中毒）：算進那種傷害類型、另外記在「持續」，不算最大一擊。 */
    public void takenDot(final long tick, final AilmentType kind, final DamageType type, final double actual) {
        if (kind == null || actual <= 0) {
            return;
        }
        touch(tick);
        final int index = slot(tick);
        taken[index][(type == null ? DamageType.PHYSICAL : type).ordinal()] += actual;
        dotTaken[index][kind.ordinal()] += actual;
    }

    /** 最近 5 秒內有沒有打出或受到傷害。 */
    public boolean active(final long now) {
        return last != Long.MIN_VALUE && now - last <= WINDOW_TICKS;
    }

    /** 現在的數字。 */
    public Snapshot snapshot(final long now) {
        final double[] outDealt = new double[TYPES];
        final double[] outTaken = new double[TYPES];
        final double[] outDotDealt = new double[AILMENTS];
        final double[] outDotTaken = new double[AILMENTS];
        double big = 0;
        DamageType bigType = null;
        final long current = Math.floorDiv(now, BUCKET_TICKS);
        for (int i = 0; i < BUCKETS; i++) {
            if (ids[i] == Long.MIN_VALUE || ids[i] > current || current - ids[i] >= BUCKETS) {
                continue;
            }
            for (int t = 0; t < TYPES; t++) {
                outDealt[t] += dealt[i][t];
                outTaken[t] += taken[i][t];
            }
            for (int a = 0; a < AILMENTS; a++) {
                outDotDealt[a] += dotDealt[i][a];
                outDotTaken[a] += dotTaken[i][a];
            }
            if (biggest[i] > big) {
                big = biggest[i];
                bigType = biggestType[i];
            }
        }
        // 視窗從最舊那一格的開頭算到現在；這一段戰鬥比較短就照實際的，至少 1 秒
        final long windowStart = (current - (BUCKETS - 1)) * BUCKET_TICKS;
        final long from = started == Long.MIN_VALUE ? windowStart : Math.max(windowStart, started);
        final double seconds = Math.max(1.0, Math.min(WINDOW_TICKS, now - from + 1) / 20.0);
        for (int t = 0; t < TYPES; t++) {
            outDealt[t] /= seconds;
            outTaken[t] /= seconds;
        }
        for (int a = 0; a < AILMENTS; a++) {
            outDotDealt[a] /= seconds;
            outDotTaken[a] /= seconds;
        }
        // 每個技能：視窗裡的加起來 ÷ 秒數；視窗裡已經沒有數字的技能清掉（不會越積越多）
        final List<SkillRow> skills = new ArrayList<>();
        for (final Iterator<Map.Entry<String, double[][]>> it = skillDealt.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, double[][]> entry = it.next();
            final double[] sum = new double[TYPES];
            double total = 0;
            for (int i = 0; i < BUCKETS; i++) {
                if (ids[i] == Long.MIN_VALUE || ids[i] > current || current - ids[i] >= BUCKETS) {
                    continue;
                }
                for (int t = 0; t < TYPES; t++) {
                    sum[t] += entry.getValue()[i][t];
                    total += entry.getValue()[i][t];
                }
            }
            if (total <= 0) {
                it.remove();
                continue;
            }
            for (int t = 0; t < TYPES; t++) {
                sum[t] /= seconds;
            }
            skills.add(new SkillRow(entry.getKey(), sum));
        }
        skills.sort((a, b) -> Double.compare(b.total(), a.total()));
        return new Snapshot(seconds, outDealt, outTaken, big, bigType, outDotDealt, outDotTaken, List.copyOf(skills));
    }

    /** 這一段戰鬥（從第一下到最後一下）每個技能打出的總傷害；還沒打過是空的。 */
    public Fight fight() {
        final List<SkillRow> rows = new ArrayList<>();
        for (final Map.Entry<String, double[]> entry : fight.entrySet()) {
            rows.add(new SkillRow(entry.getKey(), entry.getValue().clone()));
        }
        rows.sort((a, b) -> Double.compare(b.total(), a.total()));
        final double seconds = started == Long.MIN_VALUE || last == Long.MIN_VALUE ? 1.0 : Math.max(1.0, (last - started + 1) / 20.0);
        return new Fight(seconds, List.copyOf(rows));
    }

    // ---- 側欄的字 ----

    /** 側欄這一段的標題。 */
    public static final String TITLE = "<#ff6b6b><b>⚔ 戰鬥數據</b><dark_gray>（近 5 秒）";

    /**
     * 側欄的幾行（MiniMessage；第一行是標題）：輸出、輸出的類型、輸出的持續傷害、承受、承受的類型、承受的持續傷害、最大一擊。
     */
    public static List<String> lines(final Snapshot snapshot) {
        return lines(snapshot, false);
    }

    /** 技能明細最多列幾個技能（一個技能兩行；側欄一共只有 15 行）。 */
    public static final int SKILL_ROWS = 4;

    /**
     * 同上；bySkill = 「技能明細」（2026-10，設定裡開）：輸出那一段改成每個技能一項（名字、每秒傷害、佔幾成，下一行是傷害類型），
     * 多到少最多 {@link #SKILL_ROWS} 個，其他的併成一行；承受縮成一行（每秒多少、最大一擊）。
     */
    public static List<String> lines(final Snapshot snapshot, final boolean bySkill) {
        if (!bySkill || snapshot.skills().isEmpty()) {
            return overview(snapshot);
        }
        final List<String> lines = new ArrayList<>();
        lines.add(TITLE);
        final double dps = snapshot.dps();
        lines.add(" <gray>輸出 <white><b>" + number(dps) + "</b><gray>/秒");
        double rest = 0;
        int others = 0;
        int shown = 0;
        for (final SkillRow row : snapshot.skills()) {
            if (row.total() < 0.5) {
                continue;
            }
            if (shown >= SKILL_ROWS) {
                rest += row.total();
                others++;
                continue;
            }
            shown++;
            lines.add(" <white>" + escape(shorten(row.name())) + " <gray>" + number(row.total())
                    + (dps > 0 ? " <dark_gray>" + Math.round(row.total() / dps * 100) + "%" : ""));
            final String types = types(row.amounts());
            if (!types.isEmpty()) {
                lines.add("   " + types);
            }
        }
        if (others > 0) {
            lines.add(" <gray>其他 " + others + " 個 " + number(rest));
        }
        final double taken = snapshot.takenPerSecond();
        final StringBuilder hurt = new StringBuilder(" <gray>承受 ")
                .append(taken >= 0.5 ? "<#ff8080><b>" + number(taken) + "</b>" : "<white>0").append("<gray>/秒");
        if (snapshot.biggest() >= 0.5 && snapshot.biggestType() != null) {
            hurt.append(" <dark_gray>最大 <white>").append(number(snapshot.biggest())).append(" <").append(DamageColors.of(snapshot.biggestType()))
                    .append('>').append(snapshot.biggestType().displayName());
        }
        lines.add(hurt.toString());
        return lines;
    }

    /** 技能名太長的截短（側欄比較窄）。 */
    static String shorten(final String name) {
        return name.length() <= 8 ? name : name.substring(0, 7) + "…";
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * /dps 的整場統計（MiniMessage，一行一個）：總傷害、打了幾秒、平均每秒；每個技能的總傷害、佔幾成、平均每秒，下一行是各傷害類型。
     * 沒打過回傳空的。
     */
    public static List<String> report(final Fight fight) {
        final List<String> lines = new ArrayList<>();
        final double total = fight.total();
        if (total <= 0) {
            return lines;
        }
        lines.add("<#ff6b6b><b>⚔ 這一段戰鬥</b> <gray>打了 <white>" + String.format(Locale.ROOT, "%.1f", fight.seconds()) + "</white> 秒，總傷害 <white>"
                + number(total) + "</white>，平均 <white>" + number(total / fight.seconds()) + "</white>/秒");
        for (final SkillRow row : fight.skills()) {
            if (row.total() < 0.5) {
                continue;
            }
            lines.add(" <white>" + escape(row.name()) + " <gray>" + number(row.total()) + " <dark_gray>（" + Math.round(row.total() / total * 100)
                    + "%，" + number(row.total() / fight.seconds()) + "/秒）");
            final StringBuilder types = new StringBuilder();
            final List<DamageType> order = new ArrayList<>(List.of(DamageType.values()));
            order.removeIf(type -> row.amounts()[type.ordinal()] < 0.5);
            order.sort((a, b) -> Double.compare(row.amounts()[b.ordinal()], row.amounts()[a.ordinal()]));
            for (final DamageType type : order) {
                types.append(types.isEmpty() ? "" : " ").append('<').append(DamageColors.of(type)).append('>').append(type.displayName()).append(' ')
                        .append(number(row.amounts()[type.ordinal()])).append(" <dark_gray>").append(Math.round(row.amounts()[type.ordinal()] / row.total() * 100))
                        .append('%');
            }
            lines.add("   " + types);
        }
        return lines;
    }

    private static List<String> overview(final Snapshot snapshot) {
        final List<String> lines = new ArrayList<>();
        lines.add(TITLE);
        lines.add(" <gray>輸出 <white><b>" + number(snapshot.dps()) + "</b><gray>/秒");
        for (final String row : typeRows(snapshot.dealt())) { // 2026-10：全部類型都列（一行三種）
            lines.add("  " + row);
        }
        final String dealtDots = dots(snapshot.dotDealt());
        if (!dealtDots.isEmpty()) {
            lines.add("  <gray>持續 " + dealtDots);
        }
        final double taken = snapshot.takenPerSecond();
        lines.add(" <gray>承受 " + (taken >= 0.5 ? "<#ff8080><b>" + number(taken) + "</b>" : "<white>0") + "<gray>/秒");
        for (final String row : typeRows(snapshot.taken())) { // 2026-10：全部類型都列（一行三種）
            lines.add("  " + row);
        }
        final String takenDots = dots(snapshot.dotTaken());
        if (!takenDots.isEmpty()) {
            lines.add("  <gray>持續 " + takenDots);
        }
        if (snapshot.biggest() >= 0.5 && snapshot.biggestType() != null) {
            lines.add(" <gray>最大一擊 <white>" + number(snapshot.biggest()) + " <" + DamageColors.of(snapshot.biggestType()) + ">"
                    + snapshot.biggestType().displayName());
        }
        return lines;
    }

    /** 全部的類型，多到少，一行三種（側欄比較窄）；每秒不到 0.5 的不寫。 */
    static List<String> typeRows(final double[] perSecond) {
        final List<DamageType> order = new ArrayList<>(List.of(DamageType.values()));
        order.removeIf(type -> perSecond[type.ordinal()] < 0.5);
        order.sort((a, b) -> Double.compare(perSecond[b.ordinal()], perSecond[a.ordinal()]));
        final List<String> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0 && i % 3 == 0) {
                rows.add(text.toString());
                text = new StringBuilder();
            }
            final DamageType type = order.get(i);
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append('<').append(DamageColors.of(type)).append('>').append(type.displayName()).append(' ')
                    .append(number(perSecond[type.ordinal()]));
        }
        if (!text.isEmpty()) {
            rows.add(text.toString());
        }
        return rows;
    }

    /** 「火焰 1,404 物理 936」：多到少，最多三種，每秒不到 0.5 的不寫。 */
    static String types(final double[] perSecond) {
        final List<DamageType> order = new ArrayList<>(List.of(DamageType.values()));
        order.removeIf(type -> perSecond[type.ordinal()] < 0.5);
        order.sort((a, b) -> Double.compare(perSecond[b.ordinal()], perSecond[a.ordinal()]));
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(3, order.size()); i++) {
            final DamageType type = order.get(i);
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append('<').append(DamageColors.of(type)).append('>').append(type.displayName()).append(' ')
                    .append(number(perSecond[type.ordinal()]));
        }
        return text.toString();
    }

    /** 「流血 120 中毒 80」：持續傷害多到少，每秒不到 0.5 的不寫；都沒有是空字串。 */
    static String dots(final double[] perSecond) {
        final List<AilmentType> order = new ArrayList<>(DOTS);
        order.removeIf(kind -> perSecond[kind.ordinal()] < 0.5);
        order.sort((a, b) -> Double.compare(perSecond[b.ordinal()], perSecond[a.ordinal()]));
        final StringBuilder text = new StringBuilder();
        for (final AilmentType kind : order) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append('<').append(kind.color()).append('>').append(kind.displayName()).append(' ')
                    .append(number(perSecond[kind.ordinal()]));
        }
        return text.toString();
    }

    /** 1,234、12.3萬（數字大時縮短，側欄比較窄）。 */
    static String number(final double value) {
        final long rounded = Math.round(value);
        if (rounded < 100_000) {
            return String.format(Locale.ROOT, "%,d", rounded);
        }
        if (rounded < 100_000_000) {
            return String.format(Locale.ROOT, "%.1f萬", rounded / 10_000.0);
        }
        return String.format(Locale.ROOT, "%.2f億", rounded / 100_000_000.0);
    }
}
