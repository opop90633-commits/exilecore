package tw.exilecore.life;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 研究台（星骸之海）：放進材料研究，解鎖新配方。 */
final class LifeResearchMenu extends Menu {

    private final LifeService service;
    private final Map<Integer, LifeConfig.Research> shown = new HashMap<>();

    LifeResearchMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 4, "<dark_gray>研究台");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 36; i++) {
            set(i, filler());
        }
        shown.clear();
        set(4, icon(Material.LECTERN, "<#f5b942>研究台", "<gray>放進材料（會用掉）研究新東西，解鎖新配方",
                "<gray>研究是帳號共用的"));
        final LifeConfig config = service.config();
        final int level = service.level(player);
        int slot = 9;
        for (final LifeConfig.Research entry : config.research().values()) {
            if (slot >= 27) {
                break;
            }
            shown.put(slot, entry);
            set(slot, entry(entry, level));
            slot++;
        }
        set(31, closeButton());
    }

    private ItemStack entry(final LifeConfig.Research entry, final int level) {
        final LifeConfig config = service.config();
        final boolean done = service.researched(player, entry.id());
        final List<String> lore = new ArrayList<>();
        if (!entry.description().isBlank()) {
            lore.add("<gray>" + ItemRenderer.escape(entry.description()));
        }
        final List<String> unlocks = new ArrayList<>();
        for (final LifeConfig.Recipe recipe : config.recipes()) {
            if (entry.id().equals(recipe.research())) {
                unlocks.add(service.recipeName(recipe));
            }
        }
        if (!unlocks.isEmpty()) {
            lore.add("<#8fd3ff>解鎖：" + ItemRenderer.escape(String.join("、", unlocks)));
        }
        lore.add("");
        if (done) {
            lore.add("<green>✔ 研究過了");
        } else {
            lore.add("<gray>需要：");
            boolean enough = true;
            for (final Map.Entry<String, Integer> need : entry.needs().entrySet()) {
                final int have = service.material(player, need.getKey());
                enough &= have >= need.getValue();
                lore.add((have >= need.getValue() ? "<green>" : "<red>") + "・" + ItemRenderer.escape(service.materialName(need.getKey())) + " ×"
                        + need.getValue() + " <dark_gray>（有 " + have + "）");
            }
            lore.add("");
            lore.add(level < entry.level() ? "<red>要生活等級 " + entry.level() : enough ? "<yellow>左鍵：研究" : "<dark_gray>材料不夠");
        }
        final ItemStack stack = icon(done ? Material.ENCHANTED_BOOK : Material.BOOK, (done ? "<green>" : "<white>") + ItemRenderer.escape(entry.name()), lore);
        return done ? glowing(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == 31) {
            player.closeInventory();
            return;
        }
        if (!acceptAction(click) || !click.isLeftClick()) {
            return;
        }
        final LifeConfig.Research entry = shown.get(slot);
        if (entry == null) {
            return;
        }
        final String problem = service.research(player, entry);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        }
        redraw();
    }
}
