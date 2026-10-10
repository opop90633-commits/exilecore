package tw.exilecore.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 參考價值跟著交易所的成交價走：基準不動、買賣雙方太少不動、一天最多變一步、有上下限、少數怪價格與分身對倒拉不動。 */
class ReferenceValuesTest {

    private static final ReferenceValues.Settings SETTINGS = new ReferenceValues.Settings(true, "zenith_orb", 7, 5, 0.2, 0.25, 4.0);

    private static Map<String, Long> configured() {
        final Map<String, Long> values = new LinkedHashMap<>();
        values.put("glimmer_orb", 10L);
        values.put("bounty_orb", 60L);
        values.put("zenith_orb", 1500L);
        return values;
    }

    /** n 筆「give 換 want」的成交。 */
    private static List<ReferenceValues.Trade> trades(final int n, final String give, final int giveAmount, final String want, final int wantAmount) {
        final List<ReferenceValues.Trade> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new ReferenceValues.Trade(give, giveAmount, want, wantAmount));
        }
        return list;
    }

    @Test
    void anchorNeverMovesAndOthersFollowTrades() {
        // 市場上 1 個至臻石換 50 個豐饒石：豐饒石值 30（設定 60）→ 一天最多降 1/1.2
        final ReferenceValues.Result result = ReferenceValues.compute(trades(10, "zenith_orb", 1, "bounty_orb", 50),
                Map.of(), configured(), SETTINGS);
        assertEquals(1500, result.values().get("zenith_orb"), "基準不動");
        assertEquals(50, result.values().get("bounty_orb"), "60 ÷ 1.2 = 50：一天最多變 20%");
        assertEquals(10, result.pairs().get("bounty_orb"));
        assertEquals(10, result.values().get("glimmer_orb"), "沒有成交的照舊");
        assertEquals(0, result.pairs().get("glimmer_orb"));
        // 隔天再算一次（現在的值是 50）：繼續往 30 走
        final ReferenceValues.Result next = ReferenceValues.compute(trades(10, "zenith_orb", 1, "bounty_orb", 50),
                result.values(), configured(), SETTINGS);
        assertEquals(42, next.values().get("bounty_orb"), "50 ÷ 1.2 ≈ 41.7");
    }

    @Test
    void smallMovesLandExactly() {
        // 1 個至臻石換 25 個豐饒石 → 60（就是設定值）；換 24 個 → 62.5
        final ReferenceValues.Result result = ReferenceValues.compute(trades(6, "bounty_orb", 24, "zenith_orb", 1),
                Map.of(), configured(), SETTINGS);
        assertEquals(63, result.values().get("bounty_orb"), "1500 ÷ 24 = 62.5（在一步以內，直接到）");
    }

    @Test
    void selfDealingWithAnAltIsOneVote() {
        // 5 組不同的玩家照正常價成交（1 至臻 = 25 豐饒 → 60）
        final List<ReferenceValues.Trade> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(new ReferenceValues.Trade("zenith_orb", 2, "bounty_orb", 50, "seller" + i, "buyer" + i));
        }
        // 一個人跟分身對倒 40 次、金額很大、價格很誇張（1 至臻只換 5 豐饒 → 300）：不管誰掛單，都是同一組
        for (int i = 0; i < 40; i++) {
            list.add(i % 2 == 0
                    ? new ReferenceValues.Trade("bounty_orb", 50, "zenith_orb", 10, "main", "alt")
                    : new ReferenceValues.Trade("zenith_orb", 10, "bounty_orb", 50, "alt", "main"));
        }
        final ReferenceValues.Result result = ReferenceValues.compute(list, Map.of(), configured(), SETTINGS);
        assertEquals(6, result.pairs().get("bounty_orb"), "5 組正常的＋分身那一組");
        assertEquals(60, result.values().get("bounty_orb"), "中位數還是正常價");
        // 只有分身那一組：不到 5 組，不動
        final ReferenceValues.Result alone = ReferenceValues.compute(list.subList(5, list.size()), Map.of(), configured(), SETTINGS);
        assertEquals(1, alone.pairs().get("bounty_orb"));
        assertEquals(60, alone.values().get("bounty_orb"));
    }

    @Test
    void oneVoteIsTheValueWeightedAverageOfThatPair() {
        // 同一組買賣雙方：一筆 1 至臻換 25 豐饒（60）、一筆 3 至臻換 50 豐饒（90）→ 照金額平均：(60×1500 + 90×4500) ÷ 6000 = 82.5
        final List<ReferenceValues.Trade> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(new ReferenceValues.Trade("zenith_orb", 1, "bounty_orb", 25, "a" + i, "b" + i));
            list.add(new ReferenceValues.Trade("zenith_orb", 3, "bounty_orb", 50, "b" + i, "a" + i));
        }
        final ReferenceValues.Result result = ReferenceValues.compute(list, Map.of("bounty_orb", 70L), configured(), SETTINGS);
        assertEquals(5, result.pairs().get("bounty_orb"));
        assertEquals(83, result.values().get("bounty_orb"), "70 → 82.5（一步以內，直接到）");
        assertEquals(82.5, ReferenceValues.median(List.of(80.0, 85.0)), 1e-9, "偶數個取中間兩個的平均");
    }

    @Test
    void tooFewTradesKeepsTheCurrentValue() {
        final ReferenceValues.Result result = ReferenceValues.compute(trades(4, "zenith_orb", 1, "bounty_orb", 10),
                Map.of("bounty_orb", 70L), configured(), SETTINGS);
        assertEquals(70, result.values().get("bounty_orb"), "少於 5 筆：不動（用現在的值）");
        assertEquals(4, result.pairs().get("bounty_orb"));
    }

    @Test
    void boundsAreRelativeToConfiguredValues() {
        // 現在已經是設定值的 0.26 倍，市場還想再往下：停在 0.25 倍
        final ReferenceValues.Result low = ReferenceValues.compute(trades(10, "zenith_orb", 1, "bounty_orb", 1000),
                Map.of("bounty_orb", 16L), configured(), SETTINGS);
        assertEquals(15, low.values().get("bounty_orb"), "最低 60 × 0.25 = 15");
        final ReferenceValues.Result high = ReferenceValues.compute(trades(10, "bounty_orb", 1, "zenith_orb", 1),
                Map.of("bounty_orb", 230L), configured(), SETTINGS);
        assertEquals(240, high.values().get("bounty_orb"), "最高 60 × 4 = 240");
    }

    @Test
    void aFewOddTradesCannotMoveTheMedian() {
        // 9 筆正常價（1 至臻 = 25 豐饒）、3 筆很怪的價格（1 至臻 = 1000 豐饒，金額小）
        final List<ReferenceValues.Trade> list = new ArrayList<>(trades(9, "zenith_orb", 4, "bounty_orb", 100));
        list.addAll(trades(3, "zenith_orb", 1, "bounty_orb", 1000));
        final ReferenceValues.Result result = ReferenceValues.compute(list, Map.of(), configured(), SETTINGS);
        assertEquals(60, result.values().get("bounty_orb"), "加權中位數還是正常價");
    }

    @Test
    void tradesBetweenNonAnchorCurrenciesCountForBoth() {
        // 1 個豐饒石換 3 個微光石：微光石 = 20（設定 10 → 一天最多 12）、豐饒石 = 30（設定 60 → 最多降到 50）
        final ReferenceValues.Result result = ReferenceValues.compute(trades(8, "bounty_orb", 1, "glimmer_orb", 3),
                Map.of(), configured(), SETTINGS);
        assertEquals(12, result.values().get("glimmer_orb"));
        assertEquals(50, result.values().get("bounty_orb"));
    }

    @Test
    void disabledOrUnknownCurrenciesUseConfiguredValues() {
        final ReferenceValues.Settings off = new ReferenceValues.Settings(false, "zenith_orb", 7, 5, 0.2, 0.25, 4.0);
        final ReferenceValues.Result result = ReferenceValues.compute(trades(10, "zenith_orb", 1, "bounty_orb", 50),
                Map.of("bounty_orb", 40L), configured(), off);
        assertEquals(60, result.values().get("bounty_orb"), "關掉：一律用設定值");
        // 交易裡有不能標價的通貨（identify_scroll）：那一筆不算
        final ReferenceValues.Result unknown = ReferenceValues.compute(trades(10, "identify_scroll", 1, "bounty_orb", 50),
                Map.of(), configured(), SETTINGS);
        assertEquals(60, unknown.values().get("bounty_orb"));
        assertTrue(unknown.values().keySet().containsAll(configured().keySet()));
        assertEquals(configured().size(), unknown.values().size(), "只算設定檔裡的通貨");
    }
}
