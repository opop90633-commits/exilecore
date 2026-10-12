package tw.exilecore.training;

import java.util.Locale;

/**
 * 打訓練假人的實測紀錄（2026-10）：面板與網頁的「估計每秒傷害」沒算戰吼、怒氣這些打起來才有的加成，也沒算流血、
 * 點燃、中毒，跟實際打出來的可以差到幾十倍；所以把每個角色打假人的實測另外記一筆，在 /stats 與網頁的統計頁並排顯示。
 * 存在角色進度裡（{@link #KEY}）。純邏輯，不依賴 Bukkit。
 *
 * @param dps        每秒傷害
 * @param seconds    打了幾秒
 * @param dummy      哪一隻假人
 * @param dotPercent 持續傷害（流血、點燃、中毒）佔幾 %
 * @param at         什麼時候打的（毫秒）
 */
public record DummyRecord(long dps, double seconds, String dummy, int dotPercent, long at) {

    /** 角色進度的鍵。 */
    public static final String KEY = "training.best";
    /** 至少打這麼久才記（一兩下的爆發不算）。 */
    static final double MIN_SECONDS = 4; // 2026-10：8 → 4 秒
    /** 至少出手幾次才記。 */
    static final int MIN_ATTEMPTS = 4;
    /** 舊紀錄超過這麼久就直接換成新的（配裝、平衡都會變，太舊的最高紀錄沒有參考價值）。 */
    static final long STALE_MILLIS = 3L * 24 * 60 * 60 * 1000;

    /** 這一段夠不夠長、可以當紀錄。 */
    public static boolean qualifies(final double seconds, final int attempts) {
        return seconds >= MIN_SECONDS && attempts >= MIN_ATTEMPTS;
    }

    /** 這一筆要不要取代舊的：沒有舊的、比舊的高、或舊的太久了。 */
    public boolean replaces(final DummyRecord old) {
        return old == null || dps >= old.dps || at - old.at > STALE_MILLIS;
    }

    /** 存進角色進度的字串（假人名字放最後，名字裡有分隔符號也不怕）。 */
    public String encode() {
        return dps + "|" + String.format(Locale.ROOT, "%.1f", seconds) + "|" + dotPercent + "|" + at + "|" + dummy;
    }

    /** 讀回來；沒有或格式不對回傳 null。 */
    public static DummyRecord decode(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String[] parts = text.split("\\|", 5);
        if (parts.length < 5) {
            return null;
        }
        try {
            final long dps = Long.parseLong(parts[0]);
            return dps <= 0 ? null : new DummyRecord(dps, Double.parseDouble(parts[1]), parts[4], Integer.parseInt(parts[2]), Long.parseLong(parts[3]));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
