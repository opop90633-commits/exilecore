package tw.exilecore.clientmod;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.skill.FxEffect;
import tw.exilecore.skill.FxPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-02 星骸輔助模組 1.1：決定哪些特效改由模組畫（{@link tw.exilecore.skill.Fx.ViewerRouter} 的內容；不碰 Player，測試用）。
 *
 * <ul>
 *   <li>只接手「裝了模組、協定 2 以上、招呼說 effects:true、而且註冊了 exilecore:fx 頻道」的人（{@link #setCapable}）；
 *       舊版模組（協定 1）、沒裝模組的人一律 false（照常收粒子）。</li>
 *   <li>一次性的（ONCE）：一顆一顆的點照這個人的密度打薄（跟沒裝模組時伺服器打薄的方式一樣），一團的顆數、一圈的點數也先算好。</li>
 *   <li>持續範圍：START 接手了才記下編號；UPDATE、STOP 只送給收過 START 的人，而且一定送（很小，不受上限影響；
 *       模組關掉特效以後收過的範圍還是送到結束，免得畫一半不見）。</li>
 *   <li>每個人每 tick 最多 {@link #maxBytes} 位元組；超過的改回送粒子（return false）。重要的（預警、危險區）可以用到
 *       {@link #IMPORTANT_FACTOR} 倍，再超過也是改回送粒子——不會不見。</li>
 *   <li>資料是模組不會畫的種類（震動、間歇泉……）也改回送粒子。</li>
 * </ul>
 * 2026-11-02 照站長（模組 1.4 B 組）：傷害數字（{@link #number}）也走這裡：招呼說 dmgnum:true、註冊了 exilecore:fx 的人
 * （{@link #setNumbers}）數字放進同一包，伺服器就不為他生文字顯示實體；超過上限的改回生實體。跟特效的開關分開（只開其中一個也可以）。
 * 只在主執行緒用。
 */
public final class FxRouter {

    /** 每人每 tick 預設最多幾位元組。 */
    public static final int DEFAULT_MAX_BYTES = 16 * 1024;
    public static final int MIN_MAX_BYTES = 1024;
    public static final int MAX_MAX_BYTES = 256 * 1024;
    /** 重要的特效可以用到上限的幾倍。 */
    public static final int IMPORTANT_FACTOR = 4;
    /** 一個人最多記幾個由模組畫的持續範圍（超過的最舊的先忘掉；那些的 UPDATE／STOP 就不送了，模組自己會逾時清掉）。 */
    static final int MAX_ZONES = 2048;

    /** 一個人的狀態。 */
    static final class Viewer {
        boolean capable;
        /** 2026-11-02：傷害數字由模組畫。 */
        boolean numbers;
        /** 收過 START 的持續範圍（照收到的順序）。 */
        final Set<Long> zones = java.util.Collections.newSetFromMap(new LinkedHashMap<>());
        FxProtocol.Writer batch = new FxProtocol.Writer();
        /** 一顆一顆的點打薄用的計數。 */
        long sequence;
        /** 這個 tick 改回送粒子的（統計）。 */
        int overflow;
    }

    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private int maxBytes = DEFAULT_MAX_BYTES;
    /** 總計（/ec 或除錯用）。 */
    private long sentBytes;
    private long sentPackets;
    private long overflowed;

    /** 每人每 tick 最多幾位元組（夾在 1 KB–256 KB）。 */
    public void maxBytes(final int value) {
        maxBytes = Math.max(MIN_MAX_BYTES, Math.min(MAX_MAX_BYTES, value));
    }

    public int maxBytes() {
        return maxBytes;
    }

    /** 這個人現在要不要由模組畫新的特效（false 時收過 START 的範圍照樣把 UPDATE／STOP 送完）。 */
    public void setCapable(final UUID id, final boolean capable) {
        if (id == null) {
            return;
        }
        if (capable) {
            viewers.computeIfAbsent(id, k -> new Viewer()).capable = true;
        } else {
            final Viewer viewer = viewers.get(id);
            if (viewer != null) {
                viewer.capable = false;
                if (!viewer.numbers && viewer.zones.isEmpty() && viewer.batch.isEmpty()) {
                    viewers.remove(id);
                }
            }
        }
    }

    /** 2026-11-02：這個人的傷害數字要不要由模組畫（模組 1.4 招呼 dmgnum:true、註冊了 exilecore:fx）。 */
    public void setNumbers(final UUID id, final boolean numbers) {
        if (id == null) {
            return;
        }
        if (numbers) {
            viewers.computeIfAbsent(id, k -> new Viewer()).numbers = true;
        } else {
            final Viewer viewer = viewers.get(id);
            if (viewer != null) {
                viewer.numbers = false;
                if (!viewer.capable && viewer.zones.isEmpty() && viewer.batch.isEmpty()) {
                    viewers.remove(id);
                }
            }
        }
    }

    public boolean numbers(final UUID id) {
        final Viewer viewer = id == null ? null : viewers.get(id);
        return viewer != null && viewer.numbers;
    }

    /**
     * 2026-11-02：傷害數字交給模組畫（true = 放進這個 tick 的封包了，伺服器不用生文字顯示實體）。
     * 很小，上限用重要特效的（{@link #IMPORTANT_FACTOR} 倍）；超過了 false（改回生實體，不會不見）。
     */
    public boolean number(final UUID id, final FxProtocol.Number number) {
        final Viewer viewer = id == null || number == null ? null : viewers.get(id);
        if (viewer == null || !viewer.numbers) {
            return false;
        }
        if (!viewer.batch.addNumber(number, limit(true))) {
            return overflow(viewer, true);
        }
        return true;
    }

    public boolean capable(final UUID id) {
        final Viewer viewer = id == null ? null : viewers.get(id);
        return viewer != null && viewer.capable;
    }

    /** 離線：全部忘掉。 */
    public void remove(final UUID id) {
        viewers.remove(id);
    }

    /** 換世界：模組會清掉畫面上的範圍，這邊也忘掉（之後那些的 UPDATE／STOP 不送）。 */
    public void worldChanged(final UUID id) {
        final Viewer viewer = viewers.get(id);
        if (viewer != null) {
            viewer.zones.clear();
            viewer.batch = new FxProtocol.Writer(); // 舊世界還沒送出的也不要了
        }
    }

    public void clear() {
        viewers.clear();
    }

    /** 這個人由模組畫的持續範圍有幾個（測試用）。 */
    int zones(final UUID id) {
        final Viewer viewer = viewers.get(id);
        return viewer == null ? 0 : viewer.zones.size();
    }

    /**
     * Fx 問：這個人的這個特效由模組畫嗎（true = 不送粒子給他）。
     */
    public boolean route(final UUID id, final FxEffect fx) {
        if (id == null || fx == null) {
            return false;
        }
        final Viewer viewer = viewers.get(id);
        if (viewer == null) {
            return false;
        }
        final int shape = fx.shape().ordinal();
        switch (fx.phase()) {
            case UPDATE -> {
                if (!viewer.zones.contains(fx.id())) {
                    return false;
                }
                final FxProtocol.Style style = style(fx.particle(), fx.data());
                if (style == null) {
                    return false;
                }
                viewer.batch.add(entry(fx, FxProtocol.PHASE_UPDATE, fx.count()), style, 0); // 一定送
                return true;
            }
            case STOP -> {
                if (!viewer.zones.remove(fx.id())) {
                    return false;
                }
                viewer.batch.add(FxProtocol.Entry.stop(shape, fx.id(), fx.important()), null, 0); // 一定送
                return true;
            }
            case START -> {
                if (!viewer.capable) {
                    return false;
                }
                if (viewer.zones.contains(fx.id())) {
                    return true; // 已經在畫（Fx 不會重複問，保險）
                }
                final FxProtocol.Style style = style(fx.particle(), fx.data());
                if (style == null || !viewer.batch.add(entry(fx, FxProtocol.PHASE_START, fx.count()), style, limit(fx.important()))) {
                    return overflow(viewer, style != null);
                }
                if (viewer.zones.size() >= MAX_ZONES) {
                    final Long oldest = viewer.zones.iterator().next();
                    viewer.zones.remove(oldest);
                }
                viewer.zones.add(fx.id());
                return true;
            }
            default -> {
                if (!viewer.capable) {
                    return false;
                }
                final double density = Math.max(0, Math.min(1, fx.density()));
                final int count;
                switch (fx.shape()) {
                    case POINT -> {
                        if (fx.count() <= 1 && !fx.important() && !FxPolicy.keep(viewer.sequence++, density)) {
                            return true; // 打薄掉這一點（沒裝模組時伺服器也不會送這一點）
                        }
                        count = fx.count();
                    }
                    case RING -> count = FxPolicy.ringPoints(fx.radius(), density);
                    default -> count = FxPolicy.scaledCount(fx.count(), density);
                }
                if (count <= 0 && fx.shape() == FxEffect.Shape.RING) {
                    return true;
                }
                final FxProtocol.Style style = style(fx.particle(), fx.data());
                if (style == null || !viewer.batch.add(entry(fx, FxProtocol.PHASE_ONCE, count), style, limit(fx.important()))) {
                    return overflow(viewer, style != null);
                }
                return true;
            }
        }
    }

    private boolean overflow(final Viewer viewer, final boolean counted) {
        if (counted) {
            viewer.overflow++;
            overflowed++;
        }
        return false; // 改回送粒子
    }

    private int limit(final boolean important) {
        return important ? maxBytes * IMPORTANT_FACTOR : maxBytes;
    }

    private static FxProtocol.Entry entry(final FxEffect fx, final int phase, final int count) {
        final boolean zone = phase == FxProtocol.PHASE_START || phase == FxProtocol.PHASE_UPDATE;
        return new FxProtocol.Entry(fx.shape().ordinal(), phase, fx.id(), fx.important(), fx.x(), fx.y(), fx.z(), fx.ex(), fx.ey(), fx.ez(),
                fx.radius(), count, zone ? fx.density() : 1.0, zone ? fx.durationTicks() : 0, fx.dx(), fx.dy(), fx.dz(), fx.extra());
    }

    /** 這個 tick 要送的封包（送完清空）：玩家 → 位元組。 */
    public List<Map.Entry<UUID, byte[]>> drain() {
        final List<Map.Entry<UUID, byte[]>> out = new ArrayList<>();
        for (final Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            final Viewer viewer = entry.getValue();
            viewer.overflow = 0;
            if (viewer.batch.isEmpty()) {
                continue;
            }
            final byte[] bytes = viewer.batch.toBytes();
            viewer.batch = new FxProtocol.Writer();
            sentBytes += bytes.length;
            sentPackets++;
            out.add(Map.entry(entry.getKey(), bytes));
        }
        viewers.values().removeIf(v -> !v.capable && !v.numbers && v.zones.isEmpty());
        return out;
    }

    /** {送出的位元組, 封包數, 改回送粒子的特效數}（開服以來）。 */
    public long[] totals() {
        return new long[] {sentBytes, sentPackets, overflowed};
    }

    // ---- 粒子 → 樣式 ----

    /**
     * 粒子＋資料 → 樣式；模組不會畫的（資料種類不認得、資料跟粒子對不上）回傳 null（改回送粒子）。
     */
    public static FxProtocol.Style style(final Particle particle, final Object data) {
        if (particle == null) {
            return null;
        }
        final String key;
        final Class<?> type;
        try {
            key = FxProtocol.shortId(particle.getKey().toString());
            type = particle.getDataType();
        } catch (final RuntimeException e) {
            return null;
        }
        if (type == null || type == Void.class) {
            return FxProtocol.Style.none(key);
        }
        if (data == null || !type.isInstance(data)) {
            return null; // 伺服器送也會出錯的組合：照原本的走
        }
        if (type == Particle.DustTransition.class) {
            final Particle.DustTransition dust = (Particle.DustTransition) data;
            return FxProtocol.Style.transition(key, dust.getColor().asRGB(), dust.getToColor().asRGB(), dust.getSize());
        }
        if (type == Particle.DustOptions.class) {
            final Particle.DustOptions dust = (Particle.DustOptions) data;
            if (dust instanceof Particle.DustTransition transition && particle == Particle.DUST_COLOR_TRANSITION) {
                return FxProtocol.Style.transition(key, transition.getColor().asRGB(), transition.getToColor().asRGB(), transition.getSize());
            }
            return FxProtocol.Style.dust(key, dust.getColor().asRGB(), dust.getSize());
        }
        if (BlockData.class.isAssignableFrom(type)) {
            final String state = ((BlockData) data).getAsString();
            return state == null || state.isEmpty() ? null : FxProtocol.Style.block(key, FxProtocol.shortId(state));
        }
        if (type == ItemStack.class) {
            final ItemStack item = (ItemStack) data;
            return item.getType().isAir() ? null : FxProtocol.Style.item(key, FxProtocol.shortId(item.getType().getKey().toString()));
        }
        if (type == Color.class) {
            return FxProtocol.Style.color(key, ((Color) data).asARGB());
        }
        if (type == Particle.Spell.class) {
            final Particle.Spell spell = (Particle.Spell) data;
            return FxProtocol.Style.spell(key, spell.getColor().asRGB(), spell.getPower());
        }
        if (type == Float.class) {
            return FxProtocol.Style.floating(key, (Float) data);
        }
        if (type == Integer.class) {
            return FxProtocol.Style.integer(key, (Integer) data);
        }
        if (type == Particle.Trail.class) {
            final Particle.Trail trail = (Particle.Trail) data;
            final Location target = trail.getTarget();
            return target == null ? null
                    : FxProtocol.Style.trail(key, target.getX(), target.getY(), target.getZ(), trail.getColor().asRGB(), trail.getDuration());
        }
        return null;
    }

    /** 測試用：這個人這個 tick 已經排了幾筆。 */
    int pending(final UUID id) {
        final Viewer viewer = viewers.get(id);
        return viewer == null ? 0 : viewer.batch.count();
    }

    /** 測試用：這個人這個 tick 改回送粒子幾筆。 */
    int overflowThisTick(final UUID id) {
        final Viewer viewer = viewers.get(id);
        return viewer == null ? 0 : viewer.overflow;
    }

    /** 測試用。 */
    Set<Long> zoneIds(final UUID id) {
        final Viewer viewer = viewers.get(id);
        return viewer == null ? Set.of() : new HashSet<>(viewer.zones);
    }
}
