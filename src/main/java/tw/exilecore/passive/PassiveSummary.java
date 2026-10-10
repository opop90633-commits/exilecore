package tw.exilecore.passive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一堆天賦的說明加起來（PoE 天賦樹的「總覽」）：數字以外都一樣的說明合成一行、數字相加，
 * 例如「+10 力量」×3 → 「+30 力量」、「增加 6% 最大生命」＋「增加 8% 最大生命」→「增加 14% 最大生命」。
 * 沒有數字的（基石的特殊效果）各列一次。純邏輯，網頁規劃器用同一套規則。
 */
public final class PassiveSummary {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private PassiveSummary() {
    }

    /** 這些節點的說明加總（照第一次出現的順序）；珠寶孔的說明不加總，最後列一行「珠寶孔 ×N」。 */
    public static List<String> sum(final Collection<PassiveNode> nodes) {
        final List<String> lines = new ArrayList<>();
        int sockets = 0;
        for (final PassiveNode node : nodes) {
            if (node.isSocket()) {
                sockets++;
                continue;
            }
            lines.addAll(node.description());
        }
        final List<String> result = sumLines(lines);
        if (sockets > 0) {
            result.add("珠寶孔 ×" + sockets + "（放珠寶）");
        }
        return result;
    }

    /** 說明行的加總。 */
    public static List<String> sumLines(final Collection<String> lines) {
        final Map<String, double[]> totals = new LinkedHashMap<>();
        final Map<String, String> plain = new LinkedHashMap<>();
        for (final String line : lines) {
            final Matcher matcher = NUMBER.matcher(line);
            final List<Double> numbers = new ArrayList<>();
            final StringBuilder template = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                template.append(line, last, matcher.start()).append('\u0000');
                numbers.add(Double.parseDouble(matcher.group()));
                last = matcher.end();
            }
            template.append(line.substring(last));
            final String key = template.toString();
            if (numbers.isEmpty()) {
                plain.putIfAbsent(key, line);
                totals.putIfAbsent(key, null);
                continue;
            }
            final double[] sum = totals.computeIfAbsent(key, k -> new double[numbers.size()]);
            for (int i = 0; i < numbers.size(); i++) {
                sum[i] += numbers.get(i);
            }
        }
        final List<String> result = new ArrayList<>();
        for (final Map.Entry<String, double[]> entry : totals.entrySet()) {
            if (entry.getValue() == null) {
                result.add(plain.get(entry.getKey()));
                continue;
            }
            final StringBuilder line = new StringBuilder();
            int index = 0;
            for (final char c : entry.getKey().toCharArray()) {
                if (c == '\u0000') {
                    line.append(format(entry.getValue()[index++]));
                } else {
                    line.append(c);
                }
            }
            result.add(line.toString());
        }
        return result;
    }

    static String format(final double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return Long.toString(Math.round(value));
        }
        final String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
