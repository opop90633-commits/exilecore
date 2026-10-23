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
    /** 2026-10-23：海域。 */
    private final Map<Integer, LifeConfig.Zone> zones = new HashMap<>();

    LifeVoyageMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 5, "<dark_gray>海圖桌"); // 2026-10-23：3 → 5 排（島變多、加海域）
        this.service = service;
    }

    private static Material icon(final LifeConfig.Island island) {
        return switch (island.id()) {
            case "supply" -> Material.CHEST;
            case "shark_nest" -> Material.PRISMARINE_CRYSTALS;
            case "treasure" -> Material.FILLED_MAP;
            case "deep_ruin" -> Material.CALCITE;
            default -> switch (island.shape()) {
                case "reef" -> Material.BRAIN_CORAL_BLOCK;
                case "ruin" -> Material.AMETHYST_BLOCK;
                case "wreck" -> Material.DARK_OAK_BOAT;
                default -> Material.PRISMARINE;
            };
        };
    }

    private static final int[] ISLAND_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int FIRST_ZONE = 28;
    private static final int SLOT_HORN = 34;
    private static final int SLOT_CLOSE = 40;

    @Override
    protected void draw() {
        for (int i = 0; i < 45; i++) {
            set(i, filler());
        }
        shown.clear();
        zones.clear();
        final LifeConfig config = service.config();
        final String status = service.islands().status(player);
        set(4, icon(Material.FILLED_MAP, "<" + config.color() + ">海圖桌",
                "<gray>筏上要有一面船帆；航行時漂流物會變多",
                "<gray>島在筏的東邊，島上是戰鬥區（可以放技能）",
                "<gray>離開筏、下線，島就漂走了",
                "<gray>精力 <white>" + service.stamina(player),
                status == null ? "" : "<" + config.color() + ">" + ItemRenderer.escape(status)));
        final int level = service.level(player);
        int index = 0;
        // 2026-10-23：照等級排，一排 7 座
        final List<LifeConfig.Island> sorted = new ArrayList<>(config.islands().values());
        sorted.sort(java.util.Comparator.comparingInt(LifeConfig.Island::level));
        for (final LifeConfig.Island island : sorted) {
            if (index >= ISLAND_SLOTS.length) {
                break;
            }
            final int slot = ISLAND_SLOTS[index++];
            final List<String> lore = new ArrayList<>();
            if (!island.description().isBlank()) {
                lore.add("<gray>" + ItemRenderer.escape(island.description()));
            }
            lore.add("<gray>航行 " + island.travelSeconds() + " 秒；停留 " + island.minutes() + " 分鐘");
            lore.add("<gray>精力 " + island.stamina());
            boolean hasNeeds = true;
            for (final Map.Entry<String, Integer> need : island.needs().entrySet()) {
                final int have = service.material(player, need.getKey());
                hasNeeds &= have >= need.getValue();
                lore.add((have >= need.getValue() ? "<green>" : "<red>") + "要 " + ItemRenderer.escape(service.materialName(need.getKey())) + " ×" + need.getValue()
                        + " <dark_gray>（有 " + have + "；出航用掉）");
            }
            lore.add(island.count()[1] <= 0 && island.captain() == null ? "<gray>沒有守衛" : "<gray>守衛 " + island.count()[0] + "–" + island.count()[1] + " 隻"
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
            lore.add(level < island.level() ? "<red>要生活等級 " + island.level() : !hasNeeds ? "<dark_gray>材料不夠" : "<yellow>左鍵：起航");
            shown.put(slot, island);
            set(slot, icon(icon(island), "<white>" + ItemRenderer.escape(island.name()) + " <dark_gray>Lv." + island.level(), lore));
        }
        // 2026-10-23：海域
        final LifeService.Profile me = service.profile(player);
        final String current = me == null ? LifeConfig.DEFAULT_ZONE : me.zone;
        set(27, rowLabel(Material.COMPASS, "<" + config.color() + ">海域", "筏主選的海域決定漂流物、魚、渦齒鯊；記在帳號裡"));
        int zoneSlot = FIRST_ZONE;
        for (final LifeConfig.Zone zone : config.zones().values()) {
            if (zoneSlot > 32) {
                break;
            }
            final boolean here = zone.id().equals(current);
            final List<String> lore = new ArrayList<>();
            if (!zone.description().isBlank()) {
                lore.add("<gray>" + ItemRenderer.escape(zone.description()));
            }
            final List<String> fishes = new ArrayList<>();
            for (final LifeConfig.Fish fish : config.fishing().fish()) {
                if (!fish.zones().isEmpty() && fish.zones().contains(zone.id())) {
                    fishes.add(service.materialName(fish.material()));
                }
            }
            if (!fishes.isEmpty()) {
                lore.add("<#7fd4ff>這裡才有的魚：" + ItemRenderer.escape(String.join("、", fishes)));
            }
            lore.add("");
            lore.add(here ? "<green>✔ 現在在這裡" : level < zone.level() ? "<red>要生活等級 " + zone.level() : "<yellow>左鍵：換到這裡");
            zones.put(zoneSlot, zone);
            final org.bukkit.inventory.ItemStack stack = icon(zone.id().equals("starfall") ? Material.AMETHYST_CLUSTER : zone.id().equals("deep") ? Material.DARK_PRISMARINE : Material.SAND,
                    "<white>" + ItemRenderer.escape(zone.name()) + " <dark_gray>Lv." + zone.level(), lore);
            set(zoneSlot++, here ? glowing(stack) : stack);
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
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (!acceptAction(click) || !click.isLeftClick()) {
            return;
        }
        final LifeConfig.Zone zone = zones.get(slot);
        if (zone != null) {
            final String problem = service.chooseZone(player, zone);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            }
            redraw();
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
