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
import java.util.List;
import java.util.Locale;

/** 2026-10-23：營地的生活委託：每天幾張，交材料或料理換生活經驗、金幣（有機會附通貨）。只能在營地交。 */
final class LifeOrderMenu extends Menu {

    private static final int[] SLOTS = {10, 12, 14, 16};

    private final LifeService service;

    LifeOrderMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 3, "<dark_gray>生活委託");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final LifeConfig config = service.config();
        final LifeService.Profile profile = service.profile(player);
        final boolean inTown = plugin.zones() != null && plugin.zones().inTown(player.getLocation());
        set(4, icon(Material.PAPER, "<#f5b942>生活委託", "<gray>每天 " + config.ordersPerDay() + " 張（半夜換新的；照生活等級抽）",
                "<gray>交帳號裡的材料、或背包裡的料理", "<gray>獎勵：生活經驗、金幣；" + Math.round(config.orderCurrencyChance() * 100) + "% 附一顆通貨（佔每天海上通貨的額度）",
                inTown ? "<green>在營地：可以交" : "<red>要回營地才能交（/town）"));
        if (profile == null) {
            set(22, closeButton());
            return;
        }
        final List<LifeConfig.OrderToday> orders = service.extras().ordersToday(player, profile);
        for (int i = 0; i < Math.min(orders.size(), SLOTS.length); i++) {
            final LifeConfig.OrderToday order = orders.get(i);
            final LifeConfig.OrderDef def = order.def();
            final boolean done = service.extras().orderDone(profile, i);
            final String needName = service.extras().orderNeedName(def);
            final int have = "food".equals(def.kind()) ? service.extras().foodInInventory(player, def.need()) : service.material(player, def.need());
            final List<String> lore = new ArrayList<>();
            lore.add(("food".equals(def.kind()) ? "<gray>交料理（背包裡）：" : "<gray>交材料（帳號裡）：") + "<white>" + ItemRenderer.escape(needName) + " ×" + order.amount());
            lore.add((have >= order.amount() ? "<green>" : "<red>") + "你有 " + have);
            lore.add("");
            lore.add("<gray>獎勵：生活經驗 +" + def.xp() + (def.gold() > 0 ? String.format(Locale.ROOT, "、金幣 +%,d", def.gold()) : ""));
            lore.add("");
            lore.add(done ? "<green>✔ 今天交過了" : !inTown ? "<dark_gray>回營地再交" : have >= order.amount() ? "<yellow>左鍵：交" : "<dark_gray>不夠");
            Material icon = Material.PAPER;
            if ("food".equals(def.kind())) {
                final LifeConfig.Food food = config.foods().get(def.need());
                icon = food == null ? Material.COOKED_COD : LifeService.material(food.icon(), Material.COOKED_COD);
            } else {
                final LifeConfig.Material material = config.materials().get(def.need());
                icon = material == null ? Material.PAPER : LifeService.material(material.icon(), Material.PAPER);
            }
            final ItemStack stack = icon(icon, (done ? "<dark_gray>" : "<white>") + "委託：" + ItemRenderer.escape(needName), lore);
            set(SLOTS[i], !done && inTown && have >= order.amount() ? glowing(stack) : stack);
        }
        set(22, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == 22) {
            goBack();
            return;
        }
        if (!acceptAction(click) || !click.isLeftClick()) {
            return;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                final String problem = service.extras().turnIn(player, i);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                }
                redraw();
                return;
            }
        }
    }
}
