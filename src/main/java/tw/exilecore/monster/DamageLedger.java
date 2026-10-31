package tw.exilecore.monster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 每隻怪物受到每個玩家多少傷害（純邏輯）：掉落照傷害佔比分份、經驗算給傷害最多的隊伍。
 * 只記玩家造成的傷害（打擊、技能、點燃），數值是真正扣掉的生命（不含溢出的部分）。
 * 怪物從世界移除（死亡、卸載）時清掉。只在主執行緒用。
 */
public final class DamageLedger {

    private final Map<UUID, Map<UUID, Double>> damage = new HashMap<>();
    /** 每個玩家第一次打到這隻怪物的 tick（2026-10：Boss 秒傷紀錄算「從開打到倒下」幾秒）。 */
    private final Map<UUID, Map<UUID, Long>> firstHit = new HashMap<>();
    /** 現在的 tick（單元測試不設就是 0）。 */
    private java.util.function.LongSupplier clock = () -> 0L;

    /** 設定時鐘（伺服器的 tick）。 */
    public void setClock(final java.util.function.LongSupplier clock) {
        this.clock = clock == null ? () -> 0L : clock;
    }

    /** 每個玩家第一次打到這隻怪物的 tick（沒有回傳空的）。 */
    public Map<UUID, Long> firstHits(final UUID monster) {
        final Map<UUID, Long> map = firstHit.get(monster);
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public long now() {
        return clock.getAsLong();
    }

    /** 記一筆：monster 被 player 打掉 amount 生命。 */
    public void record(final UUID monster, final UUID player, final double amount) {
        if (monster == null || player == null) {
            return;
        }
        touched.put(monster, player);
        if (!(amount > 0) || Double.isInfinite(amount)) {
            return;
        }
        damage.computeIfAbsent(monster, key -> new LinkedHashMap<>()).merge(player, amount, Double::sum);
        firstHit.computeIfAbsent(monster, key -> new HashMap<>()).putIfAbsent(player, clock.getAsLong());
    }

    /** 2026-10-31：每隻怪物最後一個碰到牠的玩家（技能、召喚物、持續傷害打中就算，就算沒扣到血）。 */
    private final Map<UUID, UUID> touched = new HashMap<>();

    /** 記一下：player 的東西打中了 monster（不論有沒有扣血）。 */
    public void touch(final UUID monster, final UUID player) {
        if (monster != null && player != null) {
            touched.put(monster, player);
        }
    }

    /** 最後一個碰到這隻怪物的玩家（沒有回傳 null）。 */
    public UUID lastTouched(final UUID monster) {
        return touched.get(monster);
    }

    /** 這隻怪物受到的玩家傷害（玩家 → 傷害；沒有回傳空的）。 */
    public Map<UUID, Double> damageTo(final UUID monster) {
        final Map<UUID, Double> map = damage.get(monster);
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public void forget(final UUID monster) {
        damage.remove(monster);
        touched.remove(monster);
        firstHit.remove(monster);
    }

    /** 記著幾隻怪物（除錯用）。 */
    public int size() {
        return damage.size();
    }

    public void clear() {
        damage.clear();
        touched.clear();
        firstHit.clear();
    }

    /**
     * 傷害佔總傷害 threshold 以上的玩家，傷害高的在前。總傷害是 0 時回傳空的。
     *
     * @param threshold 0–1（0.05 = 5%）
     */
    public static List<UUID> qualifying(final Map<UUID, Double> damage, final double threshold) {
        double total = 0;
        for (final double value : damage.values()) {
            total += Math.max(0, value);
        }
        final List<UUID> result = new ArrayList<>();
        if (total <= 0) {
            return result;
        }
        final double needed = total * Math.max(0, threshold);
        final List<Map.Entry<UUID, Double>> entries = new ArrayList<>(damage.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (final Map.Entry<UUID, Double> entry : entries) {
            // 容許一點浮點誤差：剛好 5% 也算
            if (entry.getValue() > 0 && entry.getValue() + 1e-9 * total >= needed) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 把傷害依群組（隊伍；沒隊伍的玩家自己一組）加總，回傳總傷害最高的那組；同分取先打的。沒有傷害回傳 null。
     */
    public static <G> G topGroup(final Map<UUID, Double> damage, final Function<UUID, G> groupOf) {
        final Map<G, Double> totals = new LinkedHashMap<>();
        for (final Map.Entry<UUID, Double> entry : damage.entrySet()) {
            if (entry.getValue() > 0) {
                totals.merge(groupOf.apply(entry.getKey()), entry.getValue(), Double::sum);
            }
        }
        G best = null;
        double bestValue = 0;
        for (final Map.Entry<G, Double> entry : totals.entrySet()) {
            if (best == null || entry.getValue() > bestValue) {
                best = entry.getKey();
                bestValue = entry.getValue();
            }
        }
        return best;
    }
}
