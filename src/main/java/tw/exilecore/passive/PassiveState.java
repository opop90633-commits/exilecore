package tw.exilecore.passive;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;

import java.util.List;

/**
 * 一個玩家對天賦樹的操作：配點、退點，含點數與規則檢查。改動只寫進 PlayerState，存檔由 character 模組負責。
 */
public final class PassiveState {

    /** 操作結果。 */
    public record Result(boolean ok, String message) {
        static Result fail(final String message) {
            return new Result(false, message);
        }

        static Result success() {
            return new Result(true, "");
        }
    }

    private final ExileCore plugin;
    private final Player player;
    private final PassiveTree tree;

    public PassiveState(final ExileCore plugin, final Player player, final PassiveTree tree) {
        this.plugin = plugin;
        this.player = player;
        this.tree = tree;
    }

    private PlayerState state() {
        return plugin.players().get(player);
    }

    /** 這個角色的起點節點；沒角色為 null。 */
    public PassiveNode start() {
        final CharacterRecord character = state().character();
        return character == null ? null : tree.start(character.characterClass().id());
    }

    public Result allocate(final PassiveNode node) {
        final PlayerState state = state();
        final PassiveNode start = start();
        if (start == null) {
            return Result.fail("你還沒有角色");
        }
        if (state.passives().contains(node.id())) {
            return Result.fail("已經配過了");
        }
        if (!tree.canAllocate(state.passives(), start, node)) {
            return Result.fail("要先連到旁邊的節點");
        }
        if (plugin.players().availablePoints(state) <= 0) {
            return Result.fail("天賦點不夠，升級會給");
        }
        state.passives().add(node.id());
        state.markDirty();
        lockAttributes();
        plugin.players().sheet(player);
        return Result.success();
    }

    /**
     * 配了的屬性點（+x 任一屬性）還沒選過的，照現在職業的主屬性記下來（2026-10）：
     * 之後換職業、換昇華，天賦沒動的話屬性點不會跟著變。
     */
    public void lockAttributes() {
        final PlayerState state = state();
        if (plugin.characters() == null) {
            return;
        }
        for (final String id : state.passives()) {
            final PassiveNode node = tree.node(id);
            if (node == null || !PassiveMenu.isAttributeChoice(node) || state.progress().containsKey("attr." + id)) {
                continue;
            }
            plugin.characters().setProgress(player, "attr." + id,
                    tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), id));
        }
    }

    /** 有月卡：退點不花金幣、也不用掉免費退點次數（2026-10 月卡福利）。 */
    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardDaysLeft(player) > 0;
    }

    /** 退一點要花的金幣（有免費退點次數、有月卡時為 0）。 */
    public long refundCost() {
        final CharacterRecord character = state().character();
        if (character == null) {
            return 0;
        }
        if (character.refundPoints() > 0 || card()) {
            return 0;
        }
        return plugin.players().config().respecCost(character.level());
    }

    /** PoE2 做法：退點花金幣（未滿 20 級免費），先用免費退點次數。 */
    public Result refund(final PassiveNode node) {
        final PlayerState state = state();
        final PassiveNode start = start();
        final CharacterRecord character = state.character();
        if (start == null || character == null) {
            return Result.fail("你還沒有角色");
        }
        if (!state.passives().contains(node.id())) {
            return Result.fail("這個節點沒有配置");
        }
        if (!tree.canRefund(state.passives(), start, node)) {
            return Result.fail("退掉會讓其他節點斷線，先退外面的");
        }
        final long cost = refundCost();
        if (character.refundPoints() <= 0 && !card() && cost > state.gold()) {
            return Result.fail(String.format(java.util.Locale.ROOT, "金幣不夠：退這一點要 %,d，你有 %,d", cost, state.gold()));
        }
        final org.bukkit.inventory.ItemStack jewel = state.jewel(node.id());
        if (jewel != null && plugin.loot() != null && !plugin.loot().fits(player, state, jewel)) {
            return Result.fail("孔裡的珠寶放不回背包（背包與隨身背包都滿了）");
        }
        state.passives().remove(node.id());
        if (card()) {
            // 月卡：免費，也不用掉免費退點次數
        } else if (character.refundPoints() > 0) {
            character.setRefundPoints(character.refundPoints() - 1);
        } else {
            state.setGold(state.gold() - cost);
            plugin.economy().ledger().gold(tw.exilecore.economy.EconomyLedger.RESPEC, -cost);
        }
        JewelSockets.reclaim(plugin, player, state, tree, "退掉了珠寶孔");
        state.markDirty();
        plugin.players().sheet(player);
        return Result.success();
    }

    /**
     * 自動配路（PoE2 做法）：算出從已配置區域到目標最短要經過哪些未配置節點（含目標），
     * 點數不夠回傳失敗；成功時全部配上。
     */
    public Result allocatePath(final List<PassiveNode> path) {
        final PlayerState state = state();
        final PassiveNode start = start();
        if (start == null) {
            return Result.fail("你還沒有角色");
        }
        if (path.isEmpty()) {
            return Result.fail("走不到那裡");
        }
        final int points = plugin.players().availablePoints(state);
        if (points < path.size()) {
            return Result.fail("要 " + path.size() + " 點，你有 " + points + " 點");
        }
        for (final PassiveNode node : path) {
            if (!tree.canAllocate(state.passives(), start, node)) {
                state.markDirty();
                plugin.players().sheet(player);
                return Result.fail("路徑中途接不上（樹可能改版了），已配到能配的地方");
            }
            state.passives().add(node.id());
        }
        state.markDirty();
        plugin.players().sheet(player);
        lockAttributes();
        return Result.success();
    }

    /** 匯入規劃要花的金幣（先用免費退點次數，未滿 20 級退點免費）。 */
    public long planCost(final TreePlan.Plan plan) {
        final CharacterRecord character = state().character();
        if (character == null) {
            return 0;
        }
        if (card()) {
            return 0; // 月卡：退點免費
        }
        final int paid = Math.max(0, plan.removed().size() - character.refundPoints());
        return (long) paid * plugin.players().config().respecCost(character.level());
    }

    /** 這次匯入會用掉幾次免費退點。 */
    public int planFreeRefunds(final TreePlan.Plan plan) {
        if (card()) {
            return 0; // 月卡：不用掉免費退點次數
        }
        final CharacterRecord character = state().character();
        return character == null ? 0 : Math.min(character.refundPoints(), plan.removed().size());
    }

    /** 套用匯入的規劃（一次換成規劃的配點）；預覽之後配點變了就不套用。 */
    public Result applyPlan(final TreePlan.Plan plan) {
        final PlayerState state = state();
        final PassiveNode start = start();
        final CharacterRecord character = state.character();
        if (start == null || character == null) {
            return Result.fail("你還沒有角色");
        }
        if (!state.passives().equals(plan.base())) {
            return Result.fail("配點在這段時間變了，請重新匯入一次");
        }
        if (plugin.players().availablePoints(state) + plan.removed().size() < plan.added().size()) {
            return Result.fail("天賦點不夠");
        }
        if (tree.reachable(plan.result(), start).size() != plan.result().size()) {
            return Result.fail("規劃連不到起點");
        }
        final long cost = planCost(plan);
        if (cost > state.gold()) {
            return Result.fail(String.format(java.util.Locale.ROOT, "金幣不夠：退 %d 點要 %,d，你有 %,d",
                    plan.removed().size(), cost, state.gold()));
        }
        final int free = planFreeRefunds(plan);
        state.passives().clear();
        state.passives().addAll(plan.result());
        lockAttributes();
        character.setRefundPoints(character.refundPoints() - free);
        state.setGold(state.gold() - cost);
        plugin.economy().ledger().gold(tw.exilecore.economy.EconomyLedger.RESPEC, -cost);
        JewelSockets.reclaim(plugin, player, state, tree, "匯入的規劃沒有配那個珠寶孔");
        state.markDirty();
        plugin.players().sheet(player);
        return Result.success();
    }

    /** 管理指令：全部退掉（不消耗退點次數）；珠寶拿回背包。 */
    public int resetAll() {
        final PlayerState state = state();
        final int count = state.passives().size();
        state.passives().clear();
        JewelSockets.reclaim(plugin, player, state, tree, "天賦全部退回");
        state.markDirty();
        plugin.players().sheet(player);
        return count;
    }
}
