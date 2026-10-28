package tw.exilecore.heist;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 金庫之主的收藏（劫盜終局打倒之後的寶箱）：照權重不重複地挑幾件。純邏輯，不依賴 Bukkit。
 * 2026-10-28 照站長：特殊項鍊（劫盜專屬基底）也放進收藏，權重照 heist.yml 的 special_amulet.finale_weight（一件劫盜專屬傳奇算 1）；
 * 這次之前收藏只有劫盜專屬傳奇（平均洗牌挑），沒有權重。
 */
final class FinalePool {

    /** 收藏裡的一種東西：unique 或 base（二選一），weight 是抽中的權重。 */
    record Entry(String unique, String base, double weight) {
        static Entry unique(final String id) {
            return new Entry(id, null, 1.0);
        }

        static Entry base(final String id, final double weight) {
            return new Entry(null, id, weight);
        }
    }

    private FinalePool() {
    }

    /** 照權重不重複地挑 count 件（權重 ≤ 0 的不挑；全部挑完就停）。 */
    static List<Entry> pick(final List<Entry> entries, final int count, final RandomGenerator random) {
        final List<Entry> left = new ArrayList<>();
        for (final Entry entry : entries) {
            if (entry.weight() > 0) {
                left.add(entry);
            }
        }
        final List<Entry> picked = new ArrayList<>();
        while (picked.size() < count && !left.isEmpty()) {
            double total = 0;
            for (final Entry entry : left) {
                total += entry.weight();
            }
            double roll = random.nextDouble() * total;
            int index = left.size() - 1;
            for (int i = 0; i < left.size(); i++) {
                roll -= left.get(i).weight();
                if (roll < 0) {
                    index = i;
                    break;
                }
            }
            picked.add(left.remove(index));
        }
        return picked;
    }
}
