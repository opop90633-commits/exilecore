package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 星圖天賦（地圖天賦樹）：一條分支一欄。第一排是分支（點了幾個、目前的效果），第二到五排是節點（從上往下點），
 * 最下面一排是天賦點、點數怎麼來、全部退回。左鍵點、右鍵退（免費，同一條下面點了的一起退）；不能點的會說為什麼。
 * <p>
 * 節點的顏色（沒有資源包也看得出來）：綠＝已點、黃＝現在可以點、橘＝下一個但天賦點不夠、灰＝要先點上面的；大天賦用自己的圖示。
 */
public final class AtlasTreeMenu extends Menu {

    private static final int SLOT_ATLAS = 45;
    private static final int SLOT_HELP = 47;
    private static final int SLOT_POINTS = 49;
    private static final int SLOT_RESET = 51;
    private static final int SLOT_CLOSE = 53;
    /** 全部退回要在幾毫秒內點第二下。 */
    private static final long RESET_CONFIRM_MILLIS = 5000;

    private enum State { TAKEN, AVAILABLE, POOR, LOCKED }

    private final MapService service;
    /** 第一次點「全部退回」的時間（0 = 沒有在等確認）。 */
    private long resetArmedAt;

    public AtlasTreeMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#9fd3ff>星圖天賦");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final AtlasTree tree = service.tree();
        final AtlasTree.Earned earned = service.atlasEarned(player);
        final List<String> allocated = service.atlasAllocated(player);
        final int left = Math.max(0, earned.total() - tree.spent(allocated));
        final List<AtlasTree.Branch> branches = tree.branches();
        for (int column = 0; column < branches.size() && column < AtlasTree.MAX_BRANCHES; column++) {
            final AtlasTree.Branch branch = branches.get(column);
            set(column, header(tree, branch, allocated));
            for (final AtlasTree.Node node : branch.nodes()) {
                if (node.index() < AtlasTree.MAX_NODES) {
                    set((node.index() + 1) * 9 + column, node(tree, branch, node, allocated, left));
                }
            }
        }
        set(SLOT_ATLAS, icon(Material.FILLED_MAP, "<#f5b942>← 星圖", "<gray>回到星圖（地圖的解鎖進度）"));
        set(SLOT_HELP, help(tree, earned));
        set(SLOT_POINTS, points(tree, earned, allocated, left));
        set(SLOT_RESET, reset(allocated));
        set(SLOT_CLOSE, closeButton());
    }

    /** 分支：名稱、說明、點了幾個、這條目前的效果。 */
    private ItemStack header(final AtlasTree tree, final AtlasTree.Branch branch, final List<String> allocated) {
        final List<String> lore = new ArrayList<>();
        if (!branch.description().isBlank()) {
            lore.add("<gray>" + ItemRenderer.escape(branch.description()));
        }
        final List<String> taken = new ArrayList<>();
        for (final AtlasTree.Node node : branch.nodes()) {
            if (allocated.contains(node.id())) {
                taken.add(node.id());
            }
        }
        lore.add("<gray>已點 <white>" + taken.size() + "<gray> / " + branch.nodes().size());
        final List<String> effects = tree.bonus(taken).describe();
        if (effects.isEmpty()) {
            lore.add("<dark_gray>還沒點：從下面第一個開始");
        } else {
            for (final String line : effects) {
                lore.add("<green>• " + ItemRenderer.escape(line));
            }
        }
        final ItemStack icon = icon(material(branch.icon(), Material.FILLED_MAP), "<" + branch.color() + ">" + ItemRenderer.escape(branch.name()), lore);
        return taken.isEmpty() ? icon : glowing(icon);
    }

    private ItemStack node(final AtlasTree tree, final AtlasTree.Branch branch, final AtlasTree.Node node, final List<String> allocated,
                           final int left) {
        final State state = state(tree, branch, node, allocated, left);
        final List<String> lore = new ArrayList<>();
        for (final AtlasTree.Mod mod : node.mods()) {
            lore.add("<white>" + ItemRenderer.escape(AtlasTree.describe(mod.stat(), mod.value())));
        }
        lore.add("<dark_gray>" + (node.notable() ? "大天賦・" : "") + node.cost() + " 點");
        lore.add("");
        switch (state) {
            case TAKEN -> {
                final int deeper = tree.refund(allocated, node.id()).size() - 1;
                lore.add("<green>✔ 已點");
                lore.add("<gray>右鍵退回（免費" + (deeper > 0 ? "，連同下面已點的 " + deeper + " 個" : "") + "）");
            }
            case AVAILABLE -> lore.add("<yellow>左鍵點<gray>（要 " + node.cost() + " 點，你還有 " + left + " 點）");
            case POOR -> {
                lore.add("<red>天賦點不夠：要 " + node.cost() + " 點，你還有 " + left + " 點");
                lore.add("<dark_gray>完成新的地圖、終局、極境，或打到更高階拿天賦點");
            }
            case LOCKED -> {
                final AtlasTree.Node previous = branch.nodes().get(node.index() - 1);
                lore.add("<gray>先點上面的「" + ItemRenderer.escape(previous.name()) + "」");
            }
        }
        final String color = switch (state) {
            case TAKEN -> "<green>";
            case AVAILABLE -> "<yellow>";
            case POOR -> "<#ffaa55>";
            case LOCKED -> "<gray>";
        };
        final String name = color + (state == State.TAKEN ? "✔ " : "") + ItemRenderer.escape(node.name())
                + (node.notable() ? " <" + branch.color() + ">★" : "");
        final Material material;
        if (node.notable()) {
            material = material(node.icon().isBlank() ? branch.icon() : node.icon(), Material.NETHER_STAR);
        } else {
            material = switch (state) {
                case TAKEN -> Material.LIME_STAINED_GLASS_PANE;
                case AVAILABLE -> Material.YELLOW_STAINED_GLASS_PANE;
                case POOR -> Material.ORANGE_STAINED_GLASS_PANE;
                case LOCKED -> Material.GRAY_STAINED_GLASS_PANE;
            };
        }
        final ItemStack icon = icon(material, name, lore);
        return state == State.TAKEN ? glowing(icon) : icon;
    }

    private static State state(final AtlasTree tree, final AtlasTree.Branch branch, final AtlasTree.Node node, final List<String> allocated,
                               final int left) {
        if (allocated.contains(node.id())) {
            return State.TAKEN;
        }
        if (node.index() > 0 && !allocated.contains(branch.nodes().get(node.index() - 1).id())) {
            return State.LOCKED;
        }
        return node.cost() <= left ? State.AVAILABLE : State.POOR;
    }

    /** 天賦點怎麼來（照這個角色目前的進度）。 */
    private ItemStack help(final AtlasTree tree, final AtlasTree.Earned earned) {
        final AtlasTree.Points rules = tree.points();
        final MapConfig config = service.config();
        final List<String> lore = new ArrayList<>();
        if (rules.perMap() > 0) {
            lore.add("<gray>完成一張不同的地圖 <white>+" + rules.perMap() + "<gray>（你完成了 <white>" + earned.maps() / rules.perMap()
                    + "<gray> / " + config.maps().size() + "）");
        }
        if (rules.perFinal() > 0) {
            lore.add("<gray>打贏一個終局 <white>+" + rules.perFinal() + "<gray>（你打贏了 <white>" + earned.finals() / rules.perFinal()
                    + "<gray> / " + (config.finals().size() - config.eventPinnacles().size()) + "）"); // 2026-10-18：事件巔峰不算
        }
        if (rules.perUber() > 0) {
            lore.add("<gray>打贏一個極境 <white>+" + rules.perUber() + "<gray>（你打贏了 <white>" + earned.ubers() / rules.perUber() + "<gray>）");
        }
        if (!rules.tierMilestones().isEmpty()) {
            final List<String> tiers = new ArrayList<>();
            for (final int tier : rules.tierMilestones()) {
                tiers.add("T" + tier);
            }
            final int best = service.bestTier(player);
            final int next = tree.nextMilestone(best);
            lore.add("<gray>最高完成階級到 " + String.join("、", tiers) + " 各 <white>+1");
            lore.add("<gray>  你現在 <white>" + (best > 0 ? "T" + best : "還沒完成過") + "<gray>"
                    + (next > 0 ? "，下一點在 T" + next : "，都拿到了"));
        }
        lore.add("");
        lore.add("<gray>左鍵點、右鍵退（<white>退點免費<gray>，同一條下面點了的一起退）");
        lore.add("<gray>效果算在<white>你開的地圖<gray>上，隊友進你的地圖也吃");
        lore.add("<dark_gray>改了之後開的地圖才算（開著的不變）；極境、回響、試煉不吃");
        lore.add("<dark_gray>天賦點就算全部拿到也點不滿：要挑方向");
        return icon(Material.BOOK, "<white>天賦點怎麼來", lore);
    }

    /** 天賦點：還有幾點、總共幾點、目前全部的效果。 */
    private ItemStack points(final AtlasTree tree, final AtlasTree.Earned earned, final List<String> allocated, final int left) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>還沒用 <white>" + left + "<gray>・總共 " + earned.total() + "<dark_gray>（全部點滿要 " + tree.totalCost() + "）");
        final List<String> effects = tree.bonus(allocated).describe();
        if (effects.isEmpty()) {
            lore.add("<dark_gray>還沒點任何天賦");
        } else {
            lore.add("");
            lore.add("<#9fd3ff>你開的地圖：");
            for (final String line : effects) {
                lore.add("<green>• " + ItemRenderer.escape(line));
            }
        }
        final ItemStack icon = icon(Material.NETHER_STAR, "<#9fd3ff>星圖天賦點 <white>" + left + "<gray> / " + earned.total(), lore);
        return left > 0 ? glowing(icon) : icon;
    }

    private ItemStack reset(final List<String> allocated) {
        if (allocated.isEmpty()) {
            return icon(Material.GRINDSTONE, "<gray>全部退回", "<dark_gray>還沒點任何天賦");
        }
        if (armed()) {
            return glowing(icon(Material.GRINDSTONE, "<red>再點一次：全部退回", "<gray>退回 " + allocated.size() + " 個天賦（免費）",
                    "<dark_gray>5 秒內沒點就取消"));
        }
        return icon(Material.GRINDSTONE, "<white>全部退回", "<gray>退回 " + allocated.size() + " 個天賦（免費）", "<dark_gray>要點兩下確認");
    }

    private boolean armed() {
        return resetArmedAt > 0 && System.currentTimeMillis() - resetArmedAt <= RESET_CONFIRM_MILLIS;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_ATLAS -> new AtlasMenu(plugin, player, service).open();
            case SLOT_CLOSE -> goBack();
            case SLOT_RESET -> clickReset(click);
            default -> {
                final int row = slot / 9;
                final int column = slot % 9;
                final List<AtlasTree.Branch> branches = service.tree().branches();
                if (row < 1 || row > AtlasTree.MAX_NODES || column >= branches.size()) {
                    return;
                }
                final AtlasTree.Branch branch = branches.get(column);
                if (row - 1 >= branch.nodes().size()) {
                    return;
                }
                clickNode(branch.nodes().get(row - 1), click);
            }
        }
    }

    private void clickNode(final AtlasTree.Node node, final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        resetArmedAt = 0;
        final boolean taken = service.atlasAllocated(player).contains(node.id());
        if (click.isRightClick()) {
            if (!taken) {
                player.sendActionBar(plugin.text().parse("<gray>「" + ItemRenderer.escape(node.name()) + "」還沒點（左鍵點）"));
                return;
            }
            final List<String> removed = service.refundAtlas(player, node.id());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            int points = 0;
            for (final String id : removed) {
                final AtlasTree.Node other = service.tree().node(id);
                points += other == null ? 0 : other.cost();
            }
            player.sendActionBar(plugin.text().parse("<yellow>退回「" + ItemRenderer.escape(node.name()) + "」"
                    + (removed.size() > 1 ? "（連同下面的 " + (removed.size() - 1) + " 個）" : "") + "<gray>，拿回 " + points + " 點"));
            redraw();
            return;
        }
        if (taken) {
            player.sendActionBar(plugin.text().parse("<gray>「" + ItemRenderer.escape(node.name()) + "」已經點了（右鍵退回）"));
            return;
        }
        final String problem = service.allocateAtlas(player, node.id());
        if (problem != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        final List<String> effects = new ArrayList<>();
        for (final AtlasTree.Mod mod : node.mods()) {
            effects.add(AtlasTree.describe(mod.stat(), mod.value()));
        }
        player.sendActionBar(plugin.text().parse("<green>點了「" + ItemRenderer.escape(node.name()) + "」<gray>：" + ItemRenderer.escape(String.join("、", effects))));
        redraw();
    }

    private void clickReset(final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        if (service.atlasAllocated(player).isEmpty()) {
            player.sendActionBar(plugin.text().parse("<gray>還沒點任何天賦"));
            return;
        }
        if (!armed()) {
            resetArmedAt = System.currentTimeMillis();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
            player.sendActionBar(plugin.text().parse("<yellow>再點一次「全部退回」確認（5 秒內）"));
            redraw();
            return;
        }
        resetArmedAt = 0;
        final int count = service.resetAtlas(player);
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.6f, 1.0f);
        player.sendActionBar(plugin.text().parse("<yellow>退回了 " + count + " 個星圖天賦（免費）"));
        redraw();
    }
}
