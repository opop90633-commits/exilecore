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
 * 2026-10-26 照 PoE2 0.5 星魄分四階、星籤每種通貨分開，一頁放不下：星魄分防禦類、攻擊類、法術與腐化三組，星籤自己一組；符文分兩組，共 11 組：最上面一排放不下，超過 9 組時頁籤列兩端是 ◀ ▶，中間 7 格跟著目前那組捲（{@link #tabRow}）。
 */
public final class CurrencyGroups {

    /** 一組。 */
    public enum Group {
        BASIC("一般通貨", "改造裝備、開孔、開地圖用的石頭與碎片"),
        GEM("未切割寶石", "右鍵雕成技能寶石、切成輔助寶石，或幫技能升級"),
        ESSENCE("星魄", "保證加上某一條詞綴（防禦類：生命、魔力、防禦、抗性、屬性、尋寶）"),
        ESSENCE_ATTACK("星魄・攻擊", "保證加上某一條詞綴（攻擊類：附加傷害、命中、攻速、暴擊；移速、能盾）"), // 2026-10-26
        ESSENCE_CASTER("星魄・法術與腐化", "保證加上某一條詞綴（法術類：法傷、施速、統御；腐化星魄）"), // 2026-10-26
        MECHANIC("催化劑・蒸餾情緒", "飾品品質、項鍊注入"),
        OMEN("星籤", "改變下一次改造（掛在物品上，下一顆對應的通貨照星籤的規則用）"), // 2026-10-26：以前跟催化劑同一組
        // 2026-10-26 照 PoE2：符文 13 個家族 × 4 階，一頁放不下，分兩組（次級與普通、高階與完美）
        RUNES("符文", "嵌進武器、防具的符文孔：次級與普通"),
        RUNES_HIGH("高階符文", "嵌進武器、防具的符文孔：高階與完美"),
        // 2026-10-18；2026-10-21 加先驅石板；2026-10-26 加劫盜、活動的素材（一般通貨那組加了高階／完美通貨放不下）
        KEYS("門票・劫盜・活動", "開終局、極境、巔峰、事件巔峰、金庫之主的碎片與入場券、插在星圖塔上的先驅石板，還有劫盜與活動的素材"),
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

    /** 頁籤列的格子放「上一組」箭頭（{@link #tabRow}）。 */
    public static final int TAB_PREVIOUS = -2;
    /** 頁籤列的格子放「下一組」箭頭。 */
    public static final int TAB_NEXT = -3;
    /** 頁籤列的空格。 */
    public static final int TAB_NONE = -1;
    /** 最上面一排的格數。 */
    public static final int TAB_ROW = 9;

    /**
     * 2026-10-26：通貨袋、倉庫通貨頁最上面一排（9 格）每一格放哪一組（組的序號；{@link #TAB_NONE} 空格、{@link #TAB_PREVIOUS}／{@link #TAB_NEXT} 箭頭）。
     * 組數 9 以下全部排出來（跟 Menu.tabSlots 一樣：4 組以下隔一格置中、5 組以上連著置中）；
     * 超過 9 組（照 PoE2 星魄分階、符文分兩組、星籤自己一組之後有 11 組）第一格是 ◀、最後一格是 ▶（換到上一組／下一組，頭尾接著），
     * 中間 7 格是包含目前那組的一段，換組時跟著捲，全部的組都看得到。這次之前最多只放得下 9 組，多的看不到。
     */
    public static int[] tabRow(final int count, final int current) {
        final int[] row = new int[TAB_ROW];
        java.util.Arrays.fill(row, TAB_NONE);
        if (count <= 0) {
            return row;
        }
        if (count <= TAB_ROW) {
            if (count <= 4) {
                final int start = 4 - (count - 1);
                for (int i = 0; i < count; i++) {
                    row[start + i * 2] = i;
                }
            } else {
                final int start = (TAB_ROW - count) / 2;
                for (int i = 0; i < count; i++) {
                    row[start + i] = i;
                }
            }
            return row;
        }
        final int width = TAB_ROW - 2;
        final int clamped = Math.max(0, Math.min(count - 1, current));
        final int first = Math.max(0, Math.min(count - width, clamped - width / 2));
        row[0] = TAB_PREVIOUS;
        row[TAB_ROW - 1] = TAB_NEXT;
        for (int i = 0; i < width; i++) {
            row[1 + i] = first + i;
        }
        return row;
    }

    /** 一組最多幾種（通貨袋、倉庫通貨頁的一組是四排 36 格；多的放不下、看不到）。 */
    public static final int PAGE_CAPACITY = 36;

    /** 2026-10-18：事件入場券的代號前綴（ticket_<事件>），這些自動進「Boss 門票」那一組。 */
    public static final String TICKET_PREFIX = "ticket_";
    /** 2026-10-18：巔峰碎片的代號前綴（fragment_<終局>）。 */
    public static final String FRAGMENT_PREFIX = "fragment_";
    /** 2026-10-18：其他開 Boss 用的門票：終局的天穹碎片、極境的深淵星核、金庫之主的鑰印。 */
    public static final java.util.Set<String> BOSS_KEYS = java.util.Set.of("sky_fragment", "abyss_core", "heist_finale_key");

    /** 2026-10-26：活動素材（event_<名稱>）跟劫盜的東西也放門票那組（一般通貨那組加了高階／完美通貨，36 格放不下）。 */
    public static final String EVENT_PREFIX = "event_";
    public static final java.util.Set<String> OTHER_KEYS = java.util.Set.of("heist_contract", "heist_blueprint", "unveil_stone", "unique_voucher");

    /** 2026-10-21：星圖的先驅石板（tablet_<種類>）：插在塔上，跟門票放同一組（一般通貨那組放不下了）。 */
    public static final String TABLET_PREFIX = "tablet_";

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
        if (bossKey(type) || type.id().startsWith(TABLET_PREFIX) || type.id().startsWith(EVENT_PREFIX) || OTHER_KEYS.contains(type.id())) {
            return Group.KEYS; // 2026-10-18：Boss 門票（共鳴石算一般通貨）；2026-10-21：星圖石板也放這組
        }
        return switch (type.operation()) {
            case ESSENCE -> essenceGroup(type);
            case OMEN -> Group.OMEN;
            case CATALYST, INSTIL -> Group.MECHANIC;
            case RUNE -> type.affix() != null && (type.affix().endsWith("_greater") || type.affix().endsWith("_perfect"))
                    ? Group.RUNES_HIGH : Group.RUNES; // 2026-10-26：符文自己兩組（照階級）
            default -> Group.BASIC;
        };
    }

    /** 2026-10-26：攻擊類的星魄（代號去掉 lesser_／greater_／perfect_ 之後）。 */
    static final java.util.Set<String> ATTACK_ESSENCES = java.util.Set.of("essence_torment", "essence_blaze", "essence_rime", "essence_thunder",
            "essence_accuracy", "essence_battle", "essence_seeking", "essence_haste", "essence_ward");
    /** 2026-10-26：法術類的星魄。 */
    static final java.util.Set<String> CASTER_ESSENCES = java.util.Set.of("essence_sorcery", "essence_alacrity", "essence_command");

    private static Group essenceGroup(final CurrencyType type) {
        if (type.essenceTier() == CurrencyType.EssenceTier.CORRUPTED) {
            return Group.ESSENCE_CASTER;
        }
        final String family = type.id().replaceFirst("^(lesser|greater|perfect)_", "");
        if (ATTACK_ESSENCES.contains(family)) {
            return Group.ESSENCE_ATTACK;
        }
        return CASTER_ESSENCES.contains(family) ? Group.ESSENCE_CASTER : Group.ESSENCE;
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
