package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.World;

import java.util.UUID;

/**
 * 一個特效的描述（2026-11-02）：{@link Fx} 每次要畫東西時先描述成這個，給 {@link Fx.ViewerRouter} 決定這個人要不要改由客戶端模組畫；
 * 沒被接手的人照常收粒子。欄位：
 *
 * <ul>
 *   <li>{@code shape}：{@link Shape#POINT} 一顆、{@link Shape#BURST} 一團（count 顆散在 offset 裡）、{@link Shape#RING} 地上一圈、
 *       {@link Shape#AREA} 一塊圓形範圍（外框＋裡面）、{@link Shape#LINE} 從 center 到 end 的一條、{@link Shape#BEAM} 光束（同 LINE，較粗較亮）。</li>
 *   <li>{@code phase}：{@link Phase#ONCE} 一次性的；持續範圍用 {@link Phase#START}（出現，一次，附 durationTicks 與 radius）、
 *       {@link Phase#UPDATE}（半徑或位置變了，例如越來越大的毒雲）、{@link Phase#STOP}（消失）。</li>
 *   <li>{@code id}：持續範圍的穩定編號（START／UPDATE／STOP 同一個；ONCE 是 0）。</li>
 *   <li>{@code world}、{@code x/y/z}：中心（POINT、BURST 是那一點；RING、AREA 是圓心，y 是地面高度；LINE、BEAM 是起點）。</li>
 *   <li>{@code ex/ey/ez}：LINE、BEAM 的終點（其他形狀等於中心）。</li>
 *   <li>{@code radius}：RING、AREA 的半徑（格）；BURST 是 offset 的最大值；其他 0。</li>
 *   <li>{@code particle}、{@code data}：原本要送的粒子與它的資料（{@link Particle.DustOptions} 顏色大小、BlockData、ItemStack……可能是 null）。</li>
 *   <li>{@code dx/dy/dz/extra}：原本的 offset 與 extra（速度）；AREA 的 particle／data 是外框的，dy 是裡面的點離地多高、extra 是裡面的點的速度。</li>
 *   <li>{@code count}：原本要送的顆數（全密度；RING 是一圈的點數）。</li>
 *   <li>{@code density}：這個人看的密度（0–1，已經照特效等級、誰的特效、重要與否算好；送進 router 的一定 &gt; 0）。</li>
 *   <li>{@code durationTicks}：持續範圍還剩多久（tick；不知道是 -1；ONCE 是 0）。</li>
 *   <li>{@code owner}：誰的特效（玩家 UUID；怪物、Boss、環境是 null）。</li>
 *   <li>{@code important}：重要的（Boss／怪物招式的預警、地圖機制的危險區）：每個人一律全部看得到。</li>
 * </ul>
 */
public record FxEffect(Shape shape, Phase phase, long id, World world, double x, double y, double z, double ex, double ey, double ez,
                       double radius, Particle particle, Object data, double dx, double dy, double dz, double extra, int count,
                       double density, int durationTicks, UUID owner, boolean important) {

    /** 形狀。 */
    public enum Shape {
        POINT, BURST, RING, AREA, LINE, BEAM
    }

    /** 一次性的，或持續範圍的出現／變化／消失。 */
    public enum Phase {
        ONCE, START, UPDATE, STOP
    }

    /** 同一個特效、給另一個人看的密度。 */
    public FxEffect withDensity(final double value) {
        return value == density ? this : new FxEffect(shape, phase, id, world, x, y, z, ex, ey, ez, radius, particle, data, dx, dy, dz, extra,
                count, value, durationTicks, owner, important);
    }

    /** 同一個持續範圍換一個階段（START → UPDATE → STOP）。 */
    public FxEffect withPhase(final Phase value, final int remaining) {
        return new FxEffect(shape, value, id, world, x, y, z, ex, ey, ez, radius, particle, data, dx, dy, dz, extra, count, density, remaining,
                owner, important);
    }
}
