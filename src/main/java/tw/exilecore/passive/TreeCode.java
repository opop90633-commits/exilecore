package tw.exilecore.passive;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 天賦規劃的代碼：{@code EC1.樹版本.職業.配點}，配點是「檔案裡第幾個節點有沒有配」的位元，用 base64url 寫。
 * 網頁規劃器與遊戲內的 /tree import、/tree web 共用（網頁的 JavaScript 照同一個格式）。
 * 起點不算在裡面（永遠已配置）。純邏輯。
 */
public final class TreeCode {

    public static final String PREFIX = "EC1";

    /** 解出來的規劃。 */
    public record Decoded(String classId, Set<String> nodes) {
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
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
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
        return new Decoded(classId, nodes);
    }
}
