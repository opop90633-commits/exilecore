package tw.exilecore.character;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 通貨袋：每種通貨記一個數量，不佔格子（隨身背包的一部分）。純資料，不依賴 Bukkit。
 * 存檔是 {@code glimmer_orb=12,whetstone=40} 這種短字串；已經從 currencies.yml 拿掉的通貨也照樣保留。
 */
public final class CurrencyPouch {

    /** 每種通貨最多幾個。 */
    public static final int CAP = 5000;

    private final Map<String, Integer> amounts = new TreeMap<>();
    /** 這個袋子每種最多幾個（2026-10：贊助可以用星幣加大；讀檔時不會裁掉超過的）。 */
    private int cap = CAP;

    public int cap() {
        return cap;
    }

    public void setCap(final int cap) {
        this.cap = Math.max(CAP, cap);
    }

    public int amount(final String id) {
        return amounts.getOrDefault(id, 0);
    }

    /** 唯讀的全部內容（代號 → 數量）。 */
    public Map<String, Integer> all() {
        return Collections.unmodifiableMap(amounts);
    }

    /** 有幾種通貨。 */
    public int kinds() {
        return amounts.size();
    }

    public void set(final String id, final int amount) {
        if (amount <= 0) {
            amounts.remove(id);
        } else {
            amounts.put(id, amount);
        }
    }

    /** 存入，回傳實際收下的數量（超過上限的部分不收）。 */
    public int deposit(final String id, final int amount) {
        final int have = amount(id);
        final int accepted = Math.max(0, Math.min(amount, cap - have));
        if (accepted > 0) {
            amounts.put(id, have + accepted);
        }
        return accepted;
    }

    /** 拿出，回傳實際拿到的數量。 */
    public int withdraw(final String id, final int amount) {
        final int have = amount(id);
        final int taken = Math.max(0, Math.min(amount, have));
        if (taken > 0) {
            set(id, have - taken);
        }
        return taken;
    }

    public String encode() {
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, Integer> entry : amounts.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    /** 讀存檔；null、空白或寫壞的項目略過。 */
    public static CurrencyPouch decode(final String text) {
        final CurrencyPouch pouch = new CurrencyPouch();
        if (text == null || text.isBlank()) {
            return pouch;
        }
        for (final String part : text.split(",")) {
            final int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                final String raw = part.substring(0, equals).trim();
                final String id = tw.exilecore.currency.CurrencyRepository.resolve(raw); // 2026-10-27：拿掉的通貨併進現在的那種
                final int amount = Integer.parseInt(part.substring(equals + 1).trim());
                pouch.set(id, id.equals(raw) ? amount : pouch.amount(id) + Math.max(0, amount));
            } catch (final NumberFormatException e) {
                // 寫壞的項目略過
            }
        }
        return pouch;
    }
}
