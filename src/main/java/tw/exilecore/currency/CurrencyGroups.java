package tw.exilecore.currency;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 通貨袋、倉庫通貨頁、通貨交易所的分組：通貨種類超過一頁（45 格）之後分成幾組，各一個分頁。純邏輯。
 * 一般通貨（怪物會掉的）一組、未切割寶石一組（2026-10 算通貨）、星魄一組、其他機制的通貨（催化劑、星籤、蒸餾情緒）一組；
 * 2026-10-18：Boss 門票另外一組，放在最後：事件巔峰的入場券（ticket_ 開頭）、巔峰碎片（fragment_ 開頭）、終局的天穹碎片、極境的深淵星核、
 * 金庫之主的鑰印（共鳴石算一般通貨）。
 */
public final class CurrencyGroups {

    /** 一組。 */
    public enum Group {
        BASIC("一般通貨", "改造裝備、開孔、開地圖用的石頭與碎片"),
        GEM("未切割寶石", "右鍵雕成技能寶石、切成輔助寶石，或幫技能升級"),
        ESSENCE("星魄", "保證加上某一條詞綴"),
        MECHANIC("催化劑・星籤・蒸餾情緒", "飾品品質、改變下一次改造、項鍊注入"),
        KEYS("Boss 門票", "開終局、極境、巔峰、事件巔峰、金庫之主的碎片與入場券"), // 2026-10-18
        CARDS("星諭卡", "集滿一套換獎勵；右鍵兌換一套、Shift＋右鍵全部兌換、Q 花金幣賭卡"); // 2026-10-19

        private final String displayName;
        private final String about;

        Group(final String displayName, final String about) {
            this.displayName = displayName;
            this.about = about;
        }

        public String displayName() {
            return displayName;
        }

        /** 一句話說明這一組是什麼（分頁按鈕的說明）。 */
        public String about() {
            return about;
        }

        /** 下一組（輪回來）。 */
        public Group next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Group previous() {
            return values()[(ordinal() + values().length - 1) % values().length];
        }
    }

    /** 一組最多幾種（通貨袋、倉庫通貨頁的一組是四排 36 格；多的放不下、看不到）。 */
    public static final int PAGE_CAPACITY = 36;

    /** 2026-10-18：事件入場券的代號前綴（ticket_<事件>），這些自動進「Boss 門票」那一組。 */
    public static final String TICKET_PREFIX = "ticket_";
    /** 2026-10-18：巔峰碎片的代號前綴（fragment_<終局>）。 */
    public static final String FRAGMENT_PREFIX = "fragment_";
    /** 2026-10-18：其他開 Boss 用的門票：終局的天穹碎片、極境的深淵星核、金庫之主的鑰印。 */
    public static final java.util.Set<String> BOSS_KEYS = java.util.Set.of("sky_fragment", "abyss_core", "heist_finale_key");

    /** 2026-10-18：這種通貨是不是開 Boss 用的門票。 */
    public static boolean bossKey(final CurrencyType type) {
        return type.id().startsWith(TICKET_PREFIX) || type.id().startsWith(FRAGMENT_PREFIX) || BOSS_KEYS.contains(type.id());
    }

    private CurrencyGroups() {
    }

    /** 超過一頁放得下的組（資料檔加了太多同一組的通貨）；都放得下回傳空的。 */
    public static List<String> overfull(final Collection<CurrencyType> all) {
        final List<String> problems = new ArrayList<>();
        for (final Group group : Group.values()) {
            final int size = in(all, group).size();
            if (size > PAGE_CAPACITY) {
                problems.add(group.displayName() + " 有 " + size + " 種（一頁只放得下 " + PAGE_CAPACITY + " 種）");
            }
        }
        return problems;
    }

    /** 2026-10-19：星諭卡的代號前綴（card_<名稱>）與星諭牌堆。 */
    public static final String CARD_PREFIX = "card_";
    public static final String DECK = "stacked_deck";

    /** 2026-10-19：這種通貨是星諭卡或星諭牌堆。 */
    public static boolean card(final CurrencyType type) {
        return type.id().startsWith(CARD_PREFIX) || DECK.equals(type.id());
    }

    /** 這種通貨在哪一組。 */
    public static Group of(final CurrencyType type) {
        if (card(type)) {
            return Group.CARDS; // 2026-10-19
        }
        if (type.uncutGem()) {
            return Group.GEM;
        }
        if (bossKey(type)) {
            return Group.KEYS; // 2026-10-18：Boss 門票（共鳴石算一般通貨）
        }
        return switch (type.operation()) {
            case ESSENCE -> Group.ESSENCE;
            case CATALYST, OMEN, INSTIL, RUNE -> Group.MECHANIC;
            default -> Group.BASIC;
        };
    }

    /** 這一組的通貨（照資料檔的順序）。 */
    public static List<CurrencyType> in(final Collection<CurrencyType> all, final Group group) {
        final List<CurrencyType> list = new ArrayList<>();
        for (final CurrencyType type : all) {
            if (of(type) == group) {
                list.add(type);
            }
        }
        return list;
    }

    /** 每一組裡有幾種通貨是有的（amount 回傳那一種有幾個）。 */
    public static int[] kindsHeld(final Collection<CurrencyType> all, final ToIntFunction<CurrencyType> amount) {
        final int[] counts = new int[Group.values().length];
        for (final CurrencyType type : all) {
            if (amount.applyAsInt(type) > 0) {
                counts[of(type).ordinal()]++;
            }
        }
        return counts;
    }

    /** 哪一組有東西就先看哪一組（都沒有看一般通貨）。 */
    public static Group first(final Collection<CurrencyType> all, final ToIntFunction<CurrencyType> amount) {
        final int[] counts = kindsHeld(all, amount);
        for (final Group group : Group.values()) {
            if (counts[group.ordinal()] > 0) {
                return group;
            }
        }
        return Group.BASIC;
    }

    /** 顯示用：「一般通貨 12 種・星魄 2 種・…」（0 種的也列，玩家才知道有這些組）。 */
    public static String summary(final int[] counts) {
        final StringBuilder text = new StringBuilder();
        for (final Group group : Group.values()) {
            if (!text.isEmpty()) {
                text.append("・");
            }
            text.append(group.displayName()).append(' ').append(counts[group.ordinal()]).append(" 種");
        }
        return text.toString();
    }
}
