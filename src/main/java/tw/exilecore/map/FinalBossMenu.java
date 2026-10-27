package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-27：地圖裝置的「終局」選單（取代原本的對話框：Boss 是一顆點了循環切換的按鈕）。
 * <pre>
 *   第一排        說明（用掉幾片天穹碎片、最高能開到幾階、建議每秒是什麼意思）
 *   第二排        每一隻終局 Boss 一顆按鈕（4 隻：10、12、14、16）：名稱、所屬區域、打贏過沒、這一階的建議每秒；選中的有外框／發光
 *   第四排        階級（左鍵 −1、右鍵 +1、Shift 跳到最低／最高）・打開傳送門
 *   最下面        返回地圖裝置
 * </pre>
 * 還沒解鎖的 Boss 也列出來（灰的，寫要先打贏誰），點了只說原因。
 */
final class FinalBossMenu extends Menu {

    private static final int ROWS = 5;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_TIER = 30;
    private static final int SLOT_OPEN = 32;
    private static final int SLOT_BACK = 36;

    private final MapService service;
    private final Map<Integer, MapConfig.MapDef> shown = new HashMap<>();
    private String selectedId;
    private int tier;

    FinalBossMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, ROWS, "<#f5b942>終局");
        this.service = service;
        this.tier = Math.max(1, service.bestTier(player));
        for (final MapConfig.MapDef map : service.config().regularFinals()) {
            if (service.finalProblem(player, map) == null) {
                selectedId = map.id(); // 預設選第一隻能開的
                break;
            }
        }
        if (selectedId == null && !service.config().regularFinals().isEmpty()) {
            selectedId = service.config().regularFinals().getFirst().id();
        }
    }

    /**
     * Boss 按鈕的位置：一排最多 7 顆（10–16）；4 隻以下隔一格排在正中間（4 隻 = 10、12、14、16），5～7 隻連著排在正中間，
     * 再多就往下一排接（2026-10-27）。
     */
    static List<Integer> bossSlots(final int count) {
        final List<Integer> slots = new ArrayList<>();
        int left = count;
        int row = 1;
        while (left > 0 && row <= 2) {
            final int inRow = Math.min(7, left);
            if (inRow <= 4) {
                final int start = 4 - (inRow - 1); // 中間是第 4 欄；隔一格
                for (int i = 0; i < inRow; i++) {
                    slots.add(row * 9 + start + i * 2);
                }
            } else {
                final int start = 4 - (inRow - 1) / 2;
                for (int i = 0; i < inRow; i++) {
                    slots.add(row * 9 + start + i);
                }
            }
            left -= inRow;
            row++;
        }
        return slots;
    }

    @Override
    protected void draw() {
        shown.clear();
        for (int i = 0; i < ROWS * 9; i++) {
            set(i, filler());
        }
        final MapConfig config = service.config();
        final Set<String> completed = service.completed(player);
        final int best = Math.max(1, service.bestTier(player));
        tier = Math.max(1, Math.min(best, tier));
        final CurrencyType fragment = plugin.currencies() == null ? null : plugin.currencies().repository().get(config.drops().fragment());
        final String fragmentName = fragment == null ? "天穹碎片" : ItemRenderer.escape(fragment.name());
        final int have = fragment == null ? 0 : service.countCurrency(player, fragment);
        final int cost = config.drops().fragmentCost();

        final List<MapConfig.MapDef> finals = config.regularFinals();
        final List<Integer> slots = bossSlots(finals.size());
        for (int i = 0; i < finals.size() && i < slots.size(); i++) {
            final MapConfig.MapDef map = finals.get(i);
            shown.put(slots.get(i), map);
            set(slots.get(i), bossIcon(config, map, completed));
        }

        set(SLOT_INFO, icon(Material.NETHER_STAR, "<#f5b942>終局",
                "<gray>要用掉 " + cost + " 片" + fragmentName + "<dark_gray>（你有 " + have + " 片）",
                "<gray>最高能開到你完成過的最高階（T" + best + "）",
                "<dark_gray>階級越高 Boss 越強，掉的地圖與裝備越好",
                MapService.recommendedNote(config.finalDpsCap()),
                "",
                "<yellow>點上面的 Boss 選要打哪一隻，下面調階級，再按「打開傳送門」"));

        final MapConfig.MapDef selected = selected();
        set(SLOT_TIER, tierIcon(config, selected, best));
        final String problem = selected == null ? "沒有終局" : service.finalProblem(player, selected);
        if (problem == null) {
            set(SLOT_OPEN, glowing(icon(Material.LIME_DYE, "<green>打開傳送門",
                    "<gray>" + ItemRenderer.escape(selected.boss().name()) + "（" + ItemRenderer.escape(selected.name()) + "）・T" + tier,
                    "<gray>會用掉 " + cost + " 片" + fragmentName + "（你有 " + have + " 片）",
                    "", "<yellow>左鍵：打開")));
        } else {
            set(SLOT_OPEN, icon(Material.GRAY_DYE, "<gray>打開傳送門", "<red>" + ItemRenderer.escape(problem)));
        }
        set(SLOT_BACK, backButton("地圖裝置"));
    }

    private MapConfig.MapDef selected() {
        return selectedId == null ? null : service.config().find(selectedId);
    }

    /** 一隻 Boss 的按鈕：名稱、所屬區域、打贏過沒、這一階的建議每秒；選中的畫外框（沒有資源包：發光）。 */
    private ItemStack bossIcon(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed) {
        final boolean done = completed.contains(map.id());
        final boolean isSelected = map.id().equals(selectedId);
        final String problem = service.finalProblem(player, map);
        final String missing = Atlas.missingRequirements(config, map, completed);
        final boolean locked = !missing.isEmpty() || !Atlas.unlocked(config, map, completed);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>區域：<white>" + ItemRenderer.escape(map.name()));
        lore.add(done ? "<green>✔ 已打贏" : "<gray>還沒打贏");
        if (map.lore() != null && !map.lore().isBlank()) {
            lore.add("<dark_gray>" + ItemRenderer.escape(map.lore()));
        }
        final double recommended = service.finalRecommendedDps(map, tier);
        if (recommended > 0) {
            lore.add("<gray>T" + tier + " 建議每秒 <gold>" + BossDpsCommand.wan(recommended));
        }
        lore.add("");
        if (!missing.isEmpty()) {
            lore.add("<red>先打贏 " + ItemRenderer.escape(missing));
        } else if (problem != null && locked) {
            lore.add("<red>" + ItemRenderer.escape(problem));
        }
        lore.add(isSelected ? "<#f5b942>▶ 已選這隻" : locked ? "<dark_gray>還不能選" : "<yellow>左鍵：選這隻");
        final String color = locked ? "<dark_gray>" : isSelected ? "<#f5b942>" : "<#af6025>";
        final ItemStack stack = icon(bossMaterial(map), color + ItemRenderer.escape(map.boss().name()), lore);
        return isSelected ? highlight(stack) : stack;
    }

    /** Boss 的圖示：那種生物的生怪蛋（沒有就下界之星）。 */
    private static Material bossMaterial(final MapConfig.MapDef map) {
        final String type = map.boss() == null || map.boss().type() == null ? null : map.boss().type();
        final Material egg = type == null ? null : Material.matchMaterial(type + "_SPAWN_EGG");
        return egg == null || !egg.isItem() ? Material.NETHER_STAR : egg;
    }

    /** 階級按鈕：現在選的階級、怪物等級、生命倍率、選中那隻的建議每秒；列出每一階的建議每秒（目前這一階標起來）。 */
    private ItemStack tierIcon(final MapConfig config, final MapConfig.MapDef selected, final int best) {
        final List<String> lore = new ArrayList<>();
        final double life = config.finalBossLifeMultiplier(tier);
        lore.add("<gray>怪物等級 <white>" + service.finalLevel(tier) + (life > 1.0 ? "<gray>・Boss 生命 ×" + MapDeviceMenu.number(life) : ""));
        if (selected != null) {
            lore.add("<gray>" + ItemRenderer.escape(selected.boss().name()) + " 建議每秒 <gold>" + BossDpsCommand.wan(service.finalRecommendedDps(selected, tier)));
            lore.add("");
            for (int t = best; t >= 1; t--) {
                if (best > 8 && t < best - 7 && t != 1) {
                    continue; // 太長：只列最高的 8 階與 T1
                }
                lore.add((t == tier ? "<#f5b942>▶ " : "<dark_gray>・") + "T" + t + " <gray>" + service.finalLevel(t) + " 級・建議每秒 "
                        + BossDpsCommand.wan(service.finalRecommendedDps(selected, t)));
            }
        }
        lore.add("");
        lore.add("<yellow>左鍵：降一階　右鍵：升一階");
        lore.add("<yellow>Shift＋左鍵：T1　Shift＋右鍵：T" + best);
        final ItemStack stack = icon(Material.EXPERIENCE_BOTTLE, "<white>階級：<#f5b942>T" + tier, lore);
        return withCount(stack, tier);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            goBack();
            return;
        }
        if (click == ClickType.DOUBLE_CLICK) {
            return;
        }
        final int best = Math.max(1, service.bestTier(player));
        if (slot == SLOT_TIER) {
            if (click.isShiftClick()) {
                tier = click.isRightClick() ? best : 1;
            } else {
                tier = click.isRightClick() ? Math.min(best, tier + 1) : Math.max(1, tier - 1);
            }
            redraw();
            return;
        }
        final MapConfig.MapDef map = shown.get(slot);
        if (map != null) {
            final Set<String> completed = service.completed(player);
            final String missing = Atlas.missingRequirements(service.config(), map, completed);
            if (!missing.isEmpty() || !Atlas.unlocked(service.config(), map, completed)) {
                final String problem = service.finalProblem(player, map);
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem == null ? "還不能開" : problem)));
                return;
            }
            selectedId = map.id();
            redraw();
            return;
        }
        if (slot == SLOT_OPEN && acceptAction(click)) {
            final MapConfig.MapDef chosen = selected();
            if (chosen == null) {
                return;
            }
            final String failed = service.activateFinal(player, chosen, tier);
            if (failed != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(failed)));
                redraw();
                return;
            }
            player.closeInventory();
        }
    }

    @Override
    protected void goBack() {
        new MapDeviceMenu(plugin, player, service).open();
    }
}
