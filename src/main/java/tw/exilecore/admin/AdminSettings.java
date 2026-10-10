package tw.exilecore.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 管理網頁上能改的遊戲設定：每一項對到一個資料檔（或 config.yml）的鍵、值的種類與範圍、改完要重新載入哪些模組。
 * 改的值存在 {@link AdminOverrides}，不動資料檔。純 Java，不依賴 Bukkit。
 */
public final class AdminSettings {

    /** 網頁上的分組。 */
    public enum Group {
        BONUS("全服加成"),
        EVENT("活動與公告"),
        ECONOMY("經濟與掉率"),
        SERVER("伺服器開關");

        private final String label;

        Group(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 值的種類。 */
    public enum Kind { DECIMAL, INTEGER, BOOLEAN, CHOICE }

    /**
     * 一項設定。
     *
     * @param file     資料檔（相對於 data/）；{@code config.yml} 是插件的設定檔
     * @param key      檔案裡的鍵（點分隔）
     * @param min      網頁上能填的最小值（顯示的單位）
     * @param max      最大值（顯示的單位）
     * @param step     網頁上的間隔（只是輸入框的提示）
     * @param scale    檔案裡的值 × scale = 網頁上顯示的值（機率存 0.05、顯示 5%）
     * @param unit     單位（顯示用）
     * @param fallback 檔案沒寫這個鍵時程式用的值（檔案的單位）
     * @param reload   改完要重新載入的模組（config.yml 的鍵是即時讀的，不用）
     */
    public record Setting(String id, Group group, String label, String help, String file, String key, Kind kind,
                          double min, double max, double step, double scale, String unit, Object fallback, List<String> reload) {

        public Setting {
            reload = List.copyOf(reload);
        }

        /** 是 config.yml 的鍵（不是 data/ 的資料檔）。 */
        public boolean config() {
            return CONFIG_FILE.equals(file);
        }

        /** 檔案裡的值 → 網頁上顯示的值。讀不懂的照原樣。 */
        public Object display(final Object stored) {
            if (stored == null) {
                return null;
            }
            return switch (kind) {
                case DECIMAL -> {
                    final Double number = number(stored);
                    yield number == null ? stored : round(BigDecimal.valueOf(number).multiply(BigDecimal.valueOf(scale)));
                }
                case INTEGER -> {
                    final Double number = number(stored);
                    yield number == null ? stored : (Object) Math.round(number * scale);
                }
                case BOOLEAN -> stored instanceof Boolean ? stored : Boolean.parseBoolean(String.valueOf(stored).trim());
                case CHOICE -> String.valueOf(stored);
            };
        }

        /**
         * 網頁送來的值（顯示的單位）→ 要存的值。
         *
         * @param choices 選項（{@link Kind#CHOICE} 才用）
         * @throws IllegalArgumentException 不合法（訊息給管理員看）
         */
        public Object parse(final Object input, final Collection<String> choices) {
            if (input == null) {
                throw new IllegalArgumentException(label + "：沒有填值");
            }
            switch (kind) {
                case BOOLEAN -> {
                    if (input instanceof Boolean b) {
                        return b;
                    }
                    final String text = String.valueOf(input).trim().toLowerCase(Locale.ROOT);
                    if (text.equals("true") || text.equals("false")) {
                        return Boolean.parseBoolean(text);
                    }
                    throw new IllegalArgumentException(label + "：要是開或關");
                }
                case CHOICE -> {
                    final String text = String.valueOf(input).trim();
                    if (choices == null || !choices.contains(text)) {
                        throw new IllegalArgumentException(label + "：沒有「" + text + "」這個選項");
                    }
                    return text;
                }
                default -> {
                    final Double number = number(input);
                    if (number == null || !Double.isFinite(number)) {
                        throw new IllegalArgumentException(label + "：要填數字");
                    }
                    if (!(number >= min && number <= max)) {
                        throw new IllegalArgumentException(label + "：要在 " + plain(min) + " 到 " + plain(max) + " 之間");
                    }
                    if (kind == Kind.INTEGER) {
                        if (number != Math.rint(number)) {
                            throw new IllegalArgumentException(label + "：要填整數");
                        }
                        final double stored = number / scale;
                        if (stored != Math.rint(stored)) {
                            throw new IllegalArgumentException(label + "：要填 " + plain(scale) + " 的倍數");
                        }
                        return (int) Math.round(stored);
                    }
                    return round(BigDecimal.valueOf(number).divide(BigDecimal.valueOf(scale), 8, RoundingMode.HALF_UP));
                }
            }
        }
    }

    /** config.yml（不是 data/ 的資料檔）。 */
    public static final String CONFIG_FILE = "config.yml";

    /** 全部（網頁照這個順序顯示）。 */
    public static final List<Setting> ALL = List.of(
            // 全服加成
            new Setting("xp_bonus", Group.BONUS, "經驗倍數",
                    "打怪、委託拿到的經驗乘這個數。1 = 正常、2 = 經驗加倍。不是 1 的時候，畫面最上面會一直掛一條金色的公告（掉落、金幣的加成也一起寫在上面）。",
                    "character.yml", "experience_bonus", Kind.DECIMAL, 0.1, 10, 0.1, 1, "倍", 1.0, List.of("character")),
            new Setting("drop_quantity", Group.BONUS, "掉落數量",
                    "怪物、地圖寶箱掉的東西多幾 %（裝備、通貨、寶石、藥劑、地圖；跟其他物品數量加成相加，金幣不受影響）。",
                    "drops.yml", "server_bonus.quantity", Kind.INTEGER, 0, 500, 5, 1, "%", 0, List.of("monster")),
            new Setting("drop_rarity", Group.BONUS, "掉落稀有度",
                    "掉落的裝備是魔法、稀有的機會多幾 %（加在物品稀有度上）。",
                    "drops.yml", "server_bonus.rarity", Kind.INTEGER, 0, 500, 5, 1, "%", 0, List.of("monster")),
            new Setting("gold_bonus", Group.BONUS, "金幣倍數",
                    "怪物、寶箱掉的金幣乘這個數。商人、賭博的價格不跟著變（價格照金幣表算，不含這個倍數）。",
                    "drops.yml", "server_bonus.gold", Kind.DECIMAL, 0.1, 10, 0.1, 1, "倍", 1.0, List.of("monster")),
            // 活動
            new Setting("event_theme", Group.EVENT, "每月活動主題",
                    "指定這個月用哪個主題（地圖的加成、主題怪、每月挑戰跟著換）；「照月份輪替」= 不指定。",
                    "events.yml", "settings.force", Kind.CHOICE, 0, 0, 0, 1, "", "", List.of("event")),
            // 經濟與掉率
            new Setting("trade_fee", Group.ECONOMY, "面對面交易手續費",
                    "交易時每個人付「自己收到的東西估價」的幾 %（金幣）。",
                    "economy.yml", "trade.fee.percent", Kind.DECIMAL, 0, 50, 0.5, 1, "%", 3.0, List.of("economy")),
            new Setting("trade_fee_min", Group.ECONOMY, "面對面交易最低手續費",
                    "有收到東西時至少付幾個金幣。",
                    "economy.yml", "trade.fee.min", Kind.INTEGER, 0, 1_000_000, 10, 1, "金幣", 10, List.of("economy")),
            new Setting("purchase_fee", Group.ECONOMY, "市集購買手續費",
                    "在市集買東西時，買家付售價估價的幾 %。",
                    "economy.yml", "market.purchase_fee.percent", Kind.DECIMAL, 0, 50, 0.5, 1, "%", 3.0, List.of("economy")),
            new Setting("listing_fee", Group.ECONOMY, "市集上架手續費",
                    "上架（改價也算）時，賣家付標價估價的幾 %。",
                    "economy.yml", "market.listing_fee.percent", Kind.DECIMAL, 0, 50, 0.5, 1, "%", 2.0, List.of("economy")),
            new Setting("exchange_fee", Group.ECONOMY, "通貨交易所手續費",
                    "掛單時付掛出去的通貨估價的幾 %（取消不退）。",
                    "economy.yml", "exchange.fee.percent", Kind.DECIMAL, 0, 50, 0.5, 1, "%", 2.0, List.of("economy")),
            new Setting("gamble_price", Group.ECONOMY, "賭博價格",
                    "賭一次 = 你的等級的普通怪平均掉的金幣 × 這個數（90 = 大約打 90 隻怪的金幣）。",
                    "economy.yml", "gamble.price.kills", Kind.INTEGER, 1, 10_000, 5, 1, "隻怪的金幣", 90, List.of("economy")),
            new Setting("vendor_price", Group.ECONOMY, "商人裝備價格",
                    "普通裝備 = 物品等級的普通怪平均掉的金幣 × 這個數；魔法 3 倍、稀有 10 倍。",
                    "economy.yml", "vendor.price.kills", Kind.INTEGER, 1, 10_000, 1, 1, "隻怪的金幣", 12, List.of("economy")),
            new Setting("vendor_refresh", Group.ECONOMY, "商人馬上換一批",
                    "花金幣馬上換一批貨的價格 = 你的等級的普通怪平均掉的金幣 × 這個數。",
                    "economy.yml", "vendor.refresh_kills", Kind.INTEGER, 1, 10_000, 1, 1, "隻怪的金幣", 18, List.of("economy")),
            new Setting("boss_unique", Group.ECONOMY, "地圖 Boss 傳奇掉率",
                    "地圖 Boss 掉傳奇的機率（每個有份的人各擲一次）。",
                    "maps.yml", "drops.boss_unique_chance", Kind.DECIMAL, 0, 100, 0.5, 100, "%", 0.05, List.of("map")),
            new Setting("named_unique", Group.ECONOMY, "具名 Boss 傳奇掉率",
                    "具名 Boss 掉自己那件傳奇的機率。",
                    "maps.yml", "drops.named_unique_chance", Kind.DECIMAL, 0, 100, 0.5, 100, "%", 0.05, List.of("map")),
            new Setting("final_unique", Group.ECONOMY, "終局 Boss 傳奇掉率",
                    "終局 Boss 掉自己專屬那件傳奇的機率（極境另外算）。",
                    "maps.yml", "drops.final_unique_chance", Kind.DECIMAL, 0, 100, 0.5, 100, "%", 0.05, List.of("map")),
            // 伺服器開關（維護模式另外做）
            new Setting("pvp", Group.SERVER, "PvP 競技場",
                    "關掉時不能進競技場，裡面的人也不能互打（場地留著）。",
                    "pvp.yml", "enabled", Kind.BOOLEAN, 0, 0, 0, 1, "", true, List.of("pvp")),
            new Setting("allow_drop", Group.SERVER, "能不能丟東西",
                    "開 = 玩家可以按 Q、拖出背包丟東西（丟出去的只有自己看得到、撿得到）；關 = 只能用選單的垃圾桶刪掉。",
                    CONFIG_FILE, "items.allow-drop", Kind.BOOLEAN, 0, 0, 0, 1, "", false, List.of())
    );

    private AdminSettings() {
    }

    /** 找一項設定（沒有回傳 null）。 */
    public static Setting find(final String id) {
        for (final Setting setting : ALL) {
            if (setting.id().equals(id)) {
                return setting;
            }
        }
        return null;
    }

    static Double number(final Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 最多 6 位小數，去掉多餘的 0（0.05 不會變成 0.05000000000000001）。 */
    static double round(final BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
    }

    static String plain(final double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
