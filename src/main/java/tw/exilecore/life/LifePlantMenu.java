package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 種田（星骸之海）：空的海水盆、菜圃按右鍵，選要種什麼。 */
final class LifePlantMenu extends Menu {

    private final LifeService service;
    private final Block block;
    private final String planter;
    private final Map<Integer, LifeConfig.Crop> shown = new HashMap<>();

    LifePlantMenu(final ExileCore plugin, final Player player, final LifeService service, final Block block, final String planter) {
        super(plugin, player, 3, "<dark_gray>" + ("sea".equals(planter) ? "海水盆" : "菜圃") + "：種什麼");
        this.service = service;
        this.block = block;
        this.planter = planter;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        shown.clear();
        final LifeConfig config = service.config();
        final int level = service.level(player);
        int slot = 10;
        for (final LifeConfig.Crop crop : config.crops().values()) {
            if (!planter.equals(crop.planter()) || slot >= 17) {
                continue;
            }
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>長 " + crop.minutes() + " 分鐘（下線也會長）");
            lore.add("<gray>種子：" + ItemRenderer.escape(service.materialName(crop.seed())) + " <white>" + service.material(player, crop.seed()));
            if (crop.water() > 0) {
                lore.add("<gray>淡水 ×" + crop.water() + " <dark_gray>（有 " + service.material(player, config.freshWater()) + "）");
            }
            if (crop.sprinkler()) {
                lore.add("<gray>旁邊 4 格內要有灑水器");
            }
            lore.add("<gray>精力 2");
            final StringBuilder harvest = new StringBuilder();
            for (final Map.Entry<String, int[]> give : crop.harvest().entrySet()) {
                harvest.append(harvest.isEmpty() ? "" : "、").append(service.materialName(give.getKey())).append(" ").append(give.getValue()[0])
                        .append("–").append(give.getValue()[1]);
            }
            lore.add("<#9be37f>收成：" + ItemRenderer.escape(harvest.toString()) + "（種子還你一些）");
            lore.add("");
            lore.add(level < crop.level() ? "<red>要生活等級 " + crop.level() : "<yellow>左鍵：種");
            shown.put(slot, crop);
            set(slot, icon(Menu.material(crop.ripe(), Material.WHEAT), "<white>" + ItemRenderer.escape(crop.name()), lore));
            slot++;
        }
        set(22, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == 22) {
            player.closeInventory();
            return;
        }
        if (!acceptAction(click) || !click.isLeftClick()) {
            return;
        }
        final LifeConfig.Crop crop = shown.get(slot);
        if (crop == null) {
            return;
        }
        final String problem = service.plant(player, block, crop);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        player.closeInventory();
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#9be37f>種下了" + ItemRenderer.escape(crop.name()) + "<gray>（" + crop.minutes() + " 分鐘後收成）"));
    }
}
