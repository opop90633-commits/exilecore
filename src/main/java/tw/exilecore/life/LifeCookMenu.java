package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 烤爐、料理台（星骸之海）：做料理（成品，可以交易）。 */
final class LifeCookMenu extends Menu {

    private final LifeService service;
    private final String station;
    private final Map<Integer, LifeConfig.Recipe> shown = new HashMap<>();

    LifeCookMenu(final ExileCore plugin, final Player player, final LifeService service, final String station) {
        super(plugin, player, 4, "<dark_gray>" + ("grill".equals(station) ? "烤爐" : "料理台"));
        this.service = service;
        this.station = station;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 36; i++) {
            set(i, filler());
        }
        shown.clear();
        final LifeConfig config = service.config();
        set(4, icon("grill".equals(station) ? Material.SMOKER : Material.CRAFTER, "<#ffb46b>" + ("grill".equals(station) ? "烤爐" : "料理台"),
                "<gray>左鍵做 1 份；Shift＋左鍵做 5 份", "<gray>料理可以交易；右鍵吃",
                "<gray>精力 <white>" + service.stamina(player)));
        final int level = service.level(player);
        int slot = 9;
        for (final LifeConfig.Recipe recipe : config.recipes()) {
            if (!station.equals(recipe.station()) || recipe.food() == null || slot >= 27) {
                continue;
            }
            shown.put(slot, recipe);
            set(slot, entry(recipe, level));
            slot++;
        }
        set(31, closeButton());
    }

    private ItemStack entry(final LifeConfig.Recipe recipe, final int level) {
        final LifeConfig config = service.config();
        final LifeConfig.Food food = config.foods().get(recipe.food());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>吃了 " + food.minutes() + " 分鐘：");
        for (final String line : StatLines.describeAll(food.stats())) {
            lore.add("<#8fd3ff>・" + ItemRenderer.escape(line));
        }
        if (food.stamina() > 0) {
            lore.add("<gray>・在海上回 " + food.stamina() + " 點精力");
        }
        lore.add("");
        lore.add("<gray>需要：");
        boolean enough = true;
        for (final Map.Entry<String, Integer> cost : recipe.cost().entrySet()) {
            final int have = service.material(player, cost.getKey());
            enough &= have >= cost.getValue();
            lore.add((have >= cost.getValue() ? "<green>" : "<red>") + "・" + ItemRenderer.escape(service.materialName(cost.getKey())) + " ×"
                    + cost.getValue() + " <dark_gray>（有 " + have + "）");
        }
        if (recipe.stamina() > 0) {
            lore.add("<gray>・精力 " + recipe.stamina());
        }
        lore.add("");
        final boolean locked = recipe.research() != null && !service.researched(player, recipe.research());
        if (locked) {
            final LifeConfig.Research entry = config.research().get(recipe.research());
            lore.add("<red>要先在研究台研究「" + ItemRenderer.escape(entry == null ? recipe.research() : entry.name()) + "」");
        } else if (level < recipe.level()) {
            lore.add("<red>要生活等級 " + recipe.level());
        } else {
            lore.add(enough ? "<yellow>左鍵：做　Shift＋左鍵：做 5 份" : "<dark_gray>材料不夠");
        }
        final ItemStack stack = service.foodItem(food, recipe.amount());
        stack.editMeta(meta -> meta.lore(null)); // 選單上換成配方的說明
        final ItemStack shownStack = Menu.appendLore(stack, lore);
        return !locked && level >= recipe.level() && enough ? glowing(shownStack) : shownStack;
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
        final LifeConfig.Recipe recipe = shown.get(slot);
        if (recipe == null) {
            return;
        }
        final String problem = service.cook(player, recipe, click.isShiftClick() ? 5 : 1);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        } else {
            player.playSound(player.getLocation(), "grill".equals(station) ? Sound.BLOCK_FIRE_AMBIENT : Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.2f);
        }
        redraw();
    }
}
