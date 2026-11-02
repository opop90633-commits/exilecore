package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模組自己畫的持續範圍（伺服器送的 START／UPDATE／STOP），照編號記著。純 Java（測試用）；時間用遊戲 tick（呼叫的人給）。
 *
 * <ul>
 *   <li>START：記下（同編號的換掉）。UPDATE：換成新的位置／半徑（沒看過的編號當 START，例如 START 那包比較晚到）。STOP：拿掉。</li>
 *   <li>到期：知道還剩多久的，剩餘時間 + {@link #GRACE_TICKS} 後自己拿掉（伺服器的 STOP 掉了也不會一直留著）；
 *       不知道多久的（光環之類）最多留 {@link #UNKNOWN_TICKS}（每次 UPDATE 重算）。</li>
 *   <li>最多 {@link #MAX_ZONES} 個（超過時先丟最舊的不重要的）。</li>
 *   <li>斷線、換世界：{@link #clear}。</li>
 * </ul>
 */
public final class FxZones {

    /** 知道剩多久的範圍，到期後再多等幾 tick（伺服器的 STOP 通常會先到）。 */
    public static final int GRACE_TICKS = 40;
    /** 不知道多久的範圍最多留多久（15 分鐘；伺服器一定會送 STOP，這只是保險）。 */
    public static final int UNKNOWN_TICKS = 20 * 60 * 15;
    public static final int MAX_ZONES = 512;

    /** 一個範圍。 */
    public static final class Zone {
        private FxCodec.Effect effect;
        private final long started;
        private long updated;
        private long expires;
        /** 畫的那邊的快取（地面高度等），位置變了清掉。 */
        public Object cache;

        Zone(final FxCodec.Effect effect, final long now) {
            this.effect = effect;
            this.started = now;
            set(effect, now);
        }

        private void set(final FxCodec.Effect value, final long now) {
            this.effect = value;
            this.updated = now;
            this.expires = value.duration() >= 0 ? now + value.duration() + GRACE_TICKS : now + UNKNOWN_TICKS;
            this.cache = null;
        }

        public FxCodec.Effect effect() {
            return effect;
        }

        public long started() {
            return started;
        }

        public long updated() {
            return updated;
        }

        public long expires() {
            return expires;
        }

        public long id() {
            return effect.id();
        }
    }

    private final Map<Long, Zone> zones = new LinkedHashMap<>();

    /** 收到一個持續範圍的訊息（ONCE 不理）。 */
    public void apply(final FxCodec.Effect effect, final long now) {
        if (effect == null) {
            return;
        }
        switch (effect.phase()) {
            case FxCodec.PHASE_STOP -> zones.remove(effect.id());
            case FxCodec.PHASE_START, FxCodec.PHASE_UPDATE -> {
                final Zone zone = zones.get(effect.id());
                if (zone != null) {
                    zone.set(effect, now);
                    return;
                }
                if (zones.size() >= MAX_ZONES) {
                    evict();
                }
                zones.put(effect.id(), new Zone(effect, now));
            }
            default -> {
                // ONCE：不是持續的
            }
        }
    }

    /** 丟最舊的不重要的（都重要的話丟最舊的）。 */
    private void evict() {
        Long victim = null;
        for (final Zone zone : zones.values()) {
            if (!zone.effect.important()) {
                victim = zone.id();
                break;
            }
        }
        if (victim == null && !zones.isEmpty()) {
            victim = zones.keySet().iterator().next();
        }
        if (victim != null) {
            zones.remove(victim);
        }
    }

    /** 到期的拿掉；回傳拿掉了幾個。 */
    public int tick(final long now) {
        int removed = 0;
        for (final Iterator<Zone> it = zones.values().iterator(); it.hasNext(); ) {
            final Zone zone = it.next();
            if (now >= zone.expires || now < zone.updated) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    public void clear() {
        zones.clear();
    }

    public int size() {
        return zones.size();
    }

    public Zone get(final long id) {
        return zones.get(id);
    }

    public Collection<Zone> zones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    /** 複製一份（畫的時候用，邊畫邊改不會出錯）。 */
    public List<Zone> snapshot() {
        return new ArrayList<>(zones.values());
    }
}
