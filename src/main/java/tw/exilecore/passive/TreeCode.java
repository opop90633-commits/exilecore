package tw.exilecore.passive;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 天賦規劃的代碼：{@code EC1.樹版本.職業.配點}，配點是「檔案裡第幾個節點有沒有配」的位元，用 base64url 寫。
 * 網頁規劃器與遊戲內的 /tree import、/tree web 共用（網頁的 JavaScript 照同一個格式）。
 * 起點不算在裡面（永遠已配置）。純邏輯。
 * <p>
 * 2026-10：屬性點（+5 任一屬性）選了力量／敏捷／智慧的，後面多接一段 {@code .屬性}：照檔案順序，每個「已配的屬性點」兩個位元
 * （0 = 沒指定，照職業的主屬性；1 = 力量、2 = 敏捷、3 = 智慧），一樣用 base64url。全部都沒指定就不接這一段，
 * 所以舊的代碼照樣能用、沒選屬性的代碼跟以前一模一樣。
 */
public final class TreeCode {

    public static final String PREFIX = "EC1";

    /** 屬性的代號（跟角色進度 attr.節點 存的一樣）；索引就是代碼裡的數字。 */
    private static final String[] ATTRIBUTES = {null, "str", "dex", "int"};

    /**
     * 解出來的規劃。
     *
     * @param attributes 屬性點選的屬性：節點代號 → str／dex／int（沒指定的不在裡面）
     */
    public record Decoded(String classId, Set<String> nodes, Map<String, String> attributes) {

        public Decoded {
            attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        public Decoded(final String classId, final Set<String> nodes) {
            this(classId, nodes, Map.of());
        }
    }

    /** 解不開的原因（給玩家看）。 */
    public static final class InvalidCode extends Exception {
        public InvalidCode(final String message) {
            super(message);
        }
    }

    private TreeCode() {
    }

    public static String encode(final PassiveTree tree, final String classId, final Set<String> allocated) {
        return encode(tree, classId, allocated, Map.of());
    }

    /**
     * @param attributes 屬性點選的屬性：節點代號 → str／dex／int；沒有的（或不認得的值）算沒指定
     */
    public static String encode(final PassiveTree tree, final String classId, final Set<String> allocated, final Map<String, String> attributes) {
        final String base = encodeNodes(tree, classId, allocated);
        final List<PassiveNode> order = tree.ordered();
        final byte[] bits = new byte[(order.size() + 3) / 4];
        int length = 0;
        int k = 0;
        for (final PassiveNode node : order) {
            if (node.isStart() || !allocated.contains(node.id()) || node.attributeChoice() <= 0) {
                continue;
            }
            final int value = attributeCode(attributes == null ? null : attributes.get(node.id()));
            if (value != 0) {
                bits[k / 4] |= (byte) (value << ((k % 4) * 2));
                length = k / 4 + 1;
            }
            k++;
        }
        return length == 0 ? base : base + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(bits, length));
    }

    private static int attributeCode(final String attribute) {
        for (int i = 1; i < ATTRIBUTES.length; i++) {
            if (ATTRIBUTES[i].equals(attribute)) {
                return i;
            }
        }
        return 0;
    }

    private static String encodeNodes(final PassiveTree tree, final String classId, final Set<String> allocated) {
        final List<PassiveNode> order = tree.ordered();
        final byte[] bits = new byte[(order.size() + 7) / 8];
        int length = 0;
        for (int i = 0; i < order.size(); i++) {
            final PassiveNode node = order.get(i);
            if (!node.isStart() && allocated.contains(node.id())) {
                bits[i / 8] |= (byte) (1 << (i % 8));
                length = i / 8 + 1;
            }
        }
        final byte[] trimmed = java.util.Arrays.copyOf(bits, length);
        return PREFIX + "." + tree.version() + "." + classId.toLowerCase(Locale.ROOT) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(trimmed);
    }

    public static Decoded decode(final PassiveTree tree, final String code) throws InvalidCode {
        final String text = code == null ? "" : code.trim();
        // 網址也可以直接貼（取 # 後面那段）
        final int hash = text.lastIndexOf('#');
        final String body = hash >= 0 ? text.substring(hash + 1) : text;
        final String[] parts = body.split("\\.", -1);
        if ((parts.length != 4 && parts.length != 5) || !parts[0].equals(PREFIX)) {
            throw new InvalidCode("看不懂這個代碼（要像 EC1.2.witch.xxxx，從網頁規劃器按「複製代碼」）");
        }
        final int version;
        try {
            version = Integer.parseInt(parts[1]);
        } catch (final NumberFormatException e) {
            throw new InvalidCode("代碼的版本號不對");
        }
        if (version != tree.version()) {
            throw new InvalidCode("這是舊版天賦樹的規劃（版本 " + version + "，現在是 " + tree.version() + "），請在網頁規劃器重新打開");
        }
        final String classId = parts[2].toLowerCase(Locale.ROOT);
        if (tree.start(classId) == null) {
            throw new InvalidCode("代碼裡的職業「" + parts[2] + "」不存在");
        }
        final byte[] bits;
        try {
            bits = Base64.getUrlDecoder().decode(parts[3]);
        } catch (final IllegalArgumentException e) {
            throw new InvalidCode("代碼的配點部分壞掉了（可能沒有複製完整）");
        }
        final List<PassiveNode> order = tree.ordered();
        if (bits.length > (order.size() + 7) / 8) {
            throw new InvalidCode("代碼的配點比天賦樹還多（可能是別的伺服器的代碼）");
        }
        final Set<String> nodes = new LinkedHashSet<>();
        for (int i = 0; i < bits.length * 8; i++) {
            if ((bits[i / 8] & (1 << (i % 8))) == 0) {
                continue;
            }
            if (i >= order.size()) {
                throw new InvalidCode("代碼的配點比天賦樹還多（可能是別的伺服器的代碼）");
            }
            final PassiveNode node = order.get(i);
            if (!node.isStart()) {
                nodes.add(node.id());
            }
        }
        return new Decoded(classId, nodes, parts.length == 5 ? decodeAttributes(order, nodes, parts[4]) : Map.of());
    }

    /** 代碼的第五段：已配的屬性點各選了哪個屬性。 */
    private static Map<String, String> decodeAttributes(final List<PassiveNode> order, final Set<String> nodes, final String text) throws InvalidCode {
        final byte[] bits;
        try {
            bits = Base64.getUrlDecoder().decode(text);
        } catch (final IllegalArgumentException e) {
            throw new InvalidCode("代碼的屬性點部分壞掉了（可能沒有複製完整）");
        }
        final Map<String, String> attributes = new LinkedHashMap<>();
        int k = 0;
        for (final PassiveNode node : order) {
            if (node.isStart() || !nodes.contains(node.id()) || node.attributeChoice() <= 0) {
                continue;
            }
            if (k / 4 < bits.length) {
                final int value = (bits[k / 4] >> ((k % 4) * 2)) & 3;
                if (value != 0) {
                    attributes.put(node.id(), ATTRIBUTES[value]);
                }
            }
            k++;
        }
        if (bits.length > (k + 3) / 4) {
            throw new InvalidCode("代碼的屬性點比配的屬性點還多（可能沒有複製完整）");
        }
        return attributes;
    }
}
