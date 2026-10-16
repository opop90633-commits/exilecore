package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.Sound;
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

/** 海圖桌（星骸之海）：選一座島開過去。 */
final class LifeVoyageMenu extends Menu {

    private final LifeService service;
    private final Map<Integer, LifeConfig.Island> shown = new HashMap<>();

    LifeVoyageMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 3, "<dark_gray>海圖桌");
        this.service = service;
    }

    private static Material icon(final String id) {
        return switch (id) {
            case "reef" -> Material.BRAIN_CORAL_BLOCK;
            case "ruin" -> Material.AMETHYST_BLOCK;
            case "wreck" -> Material.DARK_OAK_BOAT;
            default -> Material.PRISMARINE;
        };
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        shown.clear();
        final LifeConfig config = service.config();
        final String status = service.islands().status(player);
        set(4, icon(Material.FILLED_MAP, "<" + config.color() + ">海圖桌",
                "<gray>筏上要有一面船帆；航行時漂流物會變多",
                "<gray>島在筏的東邊，島上是戰鬥區（可以放技能）",
                "<gray>離開筏、下線，島就漂走了",
                "<gray>精力 <white>" + service.stamina(player),
                status == null ? "" : "<" + config.color() + ">" + ItemRenderer.escape(status)));
        final int level = service.level(player);
        int slot = 10;
        for (final LifeConfig.Island island : config.islands().values()) {
            if (slot > 16) {
                break;
            }
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>航行 " + island.travelSeconds() + " 秒；停留 " + island.minutes() + " 分鐘");
            lore.add("<gray>精力 " + island.stamina());
            lore.add("<gray>守衛 " + island.count()[0] + "–" + island.count()[1] + " 隻"
                    + (island.captain() == null ? "" : "；首領「" + ItemRenderer.escape(island.captain().name()) + "」"));
            final StringBuilder loot = new StringBuilder();
            for (final String id : island.loot().keySet()) {
                loot.append(loot.isEmpty() ? "" : "、").append(service.materialName(id));
            }
            lore.add("<#9be37f>寶箱：" + ItemRenderer.escape(loot.toString()));
            if (!island.currencies().isEmpty()) {
                lore.add("<#9be37f>　　　" + Math.round(island.currencyChance() * 100) + "% 有通貨（每天的額度跟瓶中信共用）");
            }
            if (island.hornShard() > 0) {
                lore.add("<#ff9be0>　　　" + Math.round(island.hornShard() * 100) + "% 有" + ItemRenderer.escape(service.materialName("horn_shard")));
            }
            if (island.letter()) {
                lore.add("<#bfe6ff>　　　一定有一封瓶中信（先給沒讀過的）");
            }
            lore.add("<gray>生活經驗 +" + island.xp());
            lore.add("");
            lore.add(level < island.level() ? "<red>要生活等級 " + island.level() : "<yellow>左鍵：起航");
            shown.put(slot, island);
            set(slot, icon(icon(island.id()), "<white>" + ItemRenderer.escape(island.name()) + " <dark_gray>Lv." + island.level(), lore));
            slot += 2;
        }
        final LifeConfig.Whale whale = config.whale();
        if (whale != null) {
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>深淵巨獸「" + ItemRenderer.escape(whale.name()) + "」：巔峰級的 Boss");
            lore.add("<gray>號角在自己的筏上右鍵吹，牠從筏的東邊浮上來");
            lore.add("<gray>倒下 " + whale.attempts() + " 次就失敗；" + whale.minutes() + " 分鐘內打倒");
            lore.add("<gray>號角是成品，可以交易");
            lore.add("");
            lore.add("<gray>需要：");
            boolean enough = true;
            for (final Map.Entry<String, Integer> cost : whale.hornCost().entrySet()) {
                final int have = service.material(player, cost.getKey());
                enough &= have >= cost.getValue();
                lore.add((have >= cost.getValue() ? "<green>" : "<red>") + "・" + ItemRenderer.escape(service.materialName(cost.getKey())) + " ×"
                        + cost.getValue() + " <dark_gray>（有 " + have + "）");
            }
            lore.add("");
            lore.add(level < whale.hornLevel() ? "<red>要生活等級 " + whale.hornLevel() : enough ? "<yellow>左鍵：做一支" : "<dark_gray>材料不夠");
            set(SLOT_HORN, icon(Material.GOAT_HORN, "<#5fd3ff>" + ItemRenderer.escape(whale.hornName()), lore));
        }
        set(22, closeButton());
    }

    private static final int SLOT_HORN = 20;

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == 22) {
            player.closeInventory();
            return;
        }
        if (!acceptAction(click) || !click.isLeftClick()) {
            return;
        }
        if (slot == SLOT_HORN && service.config().whale() != null) {
            final String problem = service.whale().craftHorn(player);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            }
            redraw();
            return;
        }
        final LifeConfig.Island island = shown.get(slot);
        if (island == null) {
            return;
        }
        final String problem = service.islands().sail(player, island);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        player.closeInventory();
    }
}
