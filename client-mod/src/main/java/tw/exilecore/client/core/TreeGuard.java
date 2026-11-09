package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2026-11-02 照站長（D 組）：原生天賦樹的防呆。純 Java（測試用）。這次之前（1.4 第一版）右鍵直接退點、左鍵直接整條配上。
 * <ul>
 *   <li>退點要花金幣時，右鍵先跳確認（退哪個節點、花多少、你有多少）；再按一次右鍵或按「確認」才退，滑鼠移開或 Esc 取消。
 *       免費的（免費退點次數、未滿等級、月卡、星圖天賦）照舊直接退。</li>
 *   <li>整條路一次配到 {@code confirmPathPoints}（ModConfig，預設 5）點以上時也先確認；0 = 不確認。</li>
 * </ul>
 */
public final class TreeGuard {

    /** 一個等確認的動作。node 是節點；path 是要配的路（退點是空的）。 */
    public record Pending(String action, int node, List<Integer> path, List<String> lines) {
        public boolean refund() {
            return "refund".equals(action);
        }
    }

    private TreeGuard() {
    }

    /** 退這個節點要花幾金幣（0 = 免費）。 */
    public static long refundCost(final TreeModel model, final int node) {
        if ("asc".equals(model.data.kind)) {
            final Long cost = model.state.refundCost.get(model.data.ids[node]);
            return cost == null ? 0 : cost;
        }
        return "passive".equals(model.data.kind) ? model.state.refundEach : 0;
    }

    /** 右鍵退點要不要先確認；要的話回傳確認的內容，不用回傳 null。 */
    public static Pending refund(final TreeModel model, final int node) {
        final long cost = refundCost(model, node);
        if (cost <= 0) {
            return null;
        }
        final long gold = model.state.gold;
        final List<String> lines = new ArrayList<>();
        lines.add("<#f5b942>退點確認");
        lines.add("<white>退掉「" + model.name(node) + "<white>」");
        lines.add(String.format(Locale.ROOT, "<gray>要花 <#f5b942>%,d<gray> 金幣，你有 " + (gold >= cost ? "<#f5b942>" : "<red>") + "%,d", cost, gold));
        if (gold < cost) {
            lines.add("<red>金幣不夠，伺服器會拒絕");
        }
        lines.add("<dark_gray>再按一次右鍵或按「確認」退點；點別的地方或 Esc 取消");
        return new Pending("refund", node, List.of(), lines);
    }

    /** 整條路配點要不要先確認（need = 要幾點；threshold 0 = 不確認）。 */
    public static boolean pathNeedsConfirm(final int need, final int threshold) {
        return threshold > 0 && need >= threshold;
    }

    /** 整條路配點的確認內容。 */
    public static Pending path(final TreeModel model, final int node, final List<Integer> path, final int need) {
        final List<String> lines = new ArrayList<>();
        lines.add("<#77aaff>配點確認");
        lines.add("<white>整條路配到「" + model.name(node) + "<white>」");
        lines.add("<gray>共 " + path.size() + " 個節點、<white>" + need + "<gray> 點（剩 " + model.state.points + " 點）");
        lines.add("<dark_gray>再按一次左鍵或按「確認」配點；點別的地方或 Esc 取消");
        return new Pending("alloc", node, path, lines);
    }

    /**
     * 2026-11-09 照站長「client mod 的天賦畫面也要能點武器組的組」：整條路配成武器組天賦的確認內容（動作 walloc）。
     * 武器組天賦只有這一組比另一組多的部分才花一般天賦點，點數交給伺服器檢查。
     */
    public static Pending weaponPath(final TreeModel model, final int node, final List<Integer> path, final int set) {
        final List<String> lines = new ArrayList<>();
        lines.add(TreeModel.weaponSetTag(set) + "⚔ 武器組天賦配點確認");
        lines.add("<white>整條路配到「" + model.name(node) + "<white>」，配成" + TreeModel.weaponSetTag(set) + TreeModel.weaponSetName(set) + "<white>專用");
        final int used = set == 2 ? model.state.weaponUsed2 : model.state.weaponUsed1;
        lines.add("<gray>共 " + path.size() + " 個節點（這一組 " + used + " → " + (used + path.size()) + "／" + model.state.weaponCap + "）");
        lines.add("<dark_gray>再按一次左鍵或按「確認」配點；點別的地方或 Esc 取消");
        return new Pending("walloc", node, path, lines);
    }

    // 2026-11-02 照站長：拿掉「滑鼠移開就取消」——從節點移到下面的確認框途中一定會經過空白，按鈕永遠點不到；
    //   改成點別的地方或 Esc 才取消（PassiveTreeScreen.clickAt）。這次之前是 cancelledByHover
}
