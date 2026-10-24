package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
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

/**
 * 星圖上的塔與城塞（2026-10-21，地圖裝置的「塔與城塞」按鈕）：
 * 列出解鎖了還沒打的塔（點了直接開，不用地圖物品）、完成了還沒插石板的塔（點了選一片石板插上）、解鎖了還沒攻破的城塞（點了開那個終局，第一次不用碎片）。
 * 選石板時這個選單換成石板的清單。
 * 2026-10-23：插了石板的塔排前面、用信標標出來（數量 = 剩下的次數）；周圍已經沒有吃得到石板的地圖、又沒插石板的塔自動不列；
 * Shift＋右鍵手動移除（「移除的塔」按鈕可以放回來）。
 */
public final class AtlasSitesMenu extends Menu {

    private static final int LIST_SIZE = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_HIDDEN = 47; // 2026-10-23：看移除的塔
    private static final int SLOT_HELP = 49;
    private static final int SLOT_CLOSE = 53;

    private final MapService service;
    /** 格子 → 星圖上的格子。 */
    private final Map<Integer, AtlasGrid.Cell> shown = new HashMap<>();
    /** 正在替這座塔選石板（null = 列塔與城塞）。 */
    private AtlasGrid.Cell picking;
    private final Map<Integer, AtlasTablets.Kind> pickShown = new HashMap<>();
    /** 2026-10-23：正在看移除了的塔（可以放回來）。 */
    private boolean showHidden;

    public AtlasSitesMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#d8c8a0>塔與城塞");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        pickShown.clear();
        if (picking != null) {
            drawTablets();
            return;
        }
        final AtlasProgress atlas = service.atlasProgress(player);
        final MapConfig config = service.config();
        final AtlasConfig rules = atlas.grid().config();
        final List<ItemStack> items = new ArrayList<>();
        final List<AtlasGrid.Cell> cells = new ArrayList<>();
        for (final AtlasGrid.Cell tower : atlas.openTowers()) {
            items.add(glowing(icon(Material.LANTERN, "<green>塔 " + AtlasMenu.where(tower.q(), tower.r()) + "",
                    "<gray>距離 " + tower.distance() + "・T" + tower.tier() + "・怪物等級 " + config.tiers().level(config.tiers().clamp(tower.tier())),
                    "<gray>爬到頂、打倒塔守就完成：解鎖周圍 " + rules.unlock().onTower() + " 格、天賦點 +" + rules.tower().points() + "、掉 1 片先驅石板",
                    "<yellow>點一下開（不用地圖物品，塔只能打一次）")));
            cells.add(tower);
        }
        // 2026-10-23：完成的塔：插了石板的排前面、明顯標出來；周圍已經沒有吃得到石板的地圖（沒插石板）自動不列；玩家可以手動移除
        final int radius = rules.tower().radius() + service.atlasBonus(player).count("atlas.tower.radius");
        final List<ItemStack> withTablet = new ArrayList<>();
        final List<AtlasGrid.Cell> withTabletCells = new ArrayList<>();
        final List<ItemStack> empty = new ArrayList<>();
        final List<AtlasGrid.Cell> emptyCells = new ArrayList<>();
        int hiddenCount = 0;
        int uselessCount = 0;
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            final AtlasTablets.Kind kind = service.tabletAt(player, tower.q(), tower.r());
            final int targets = service.tabletTargets(player, atlas, tower);
            final boolean hidden = service.towerHidden(player, tower.q(), tower.r());
            if (hidden) {
                hiddenCount++;
            }
            if (showHidden != hidden) {
                continue;
            }
            if (!showHidden && kind == null && targets == 0) {
                uselessCount++;
                continue; // 插了也沒有地圖吃得到
            }
            final String where = "塔 " + AtlasMenu.where(tower.q(), tower.r()) + "";
            final String reach = targets > 0 ? "<gray>周圍 " + radius + " 格還有 <white>" + targets + "<gray> 格地圖吃得到石板"
                    : "<red>周圍 " + radius + " 格已經沒有吃得到石板的地圖了";
            final String remove = showHidden ? "<yellow>Shift＋右鍵：放回清單" : "<dark_gray>Shift＋右鍵：從清單移除"
                    + (kind == null ? "" : "（石板留在塔上照樣有效）");
            if (kind == null) {
                empty.add(icon(Material.SOUL_LANTERN, "<#d8c8a0>" + where + "<gray> 已完成・還沒插石板",
                        reach,
                        targets > 0 ? "<yellow>左鍵：選石板插上" : "<dark_gray>插了也沒用",
                        remove));
                emptyCells.add(tower);
            } else {
                final int uses = service.tabletUses(player, tower.q(), tower.r());
                final ItemStack icon = icon(Material.BEACON, "<#ffd75e>✦ " + where + "・" + kind.displayName(),
                        "<#ffe9a8>" + ItemRenderer.escape(kind.description()),
                        "<gray>還能用 <white>" + uses + "<gray> / " + AtlasTablets.USES + " 張地圖<dark_gray>（物品數量 = 剩下的次數）",
                        reach,
                        "<dark_gray>罩到的地圖每開一張用掉 1 次，用完可以再插",
                        remove);
                icon.setAmount(Math.max(1, Math.min(64, uses)));
                withTablet.add(highlight(glowing(icon)));
                withTabletCells.add(tower);
            }
        }
        items.addAll(withTablet);
        cells.addAll(withTabletCells);
        items.addAll(empty);
        cells.addAll(emptyCells);
        if (showHidden) {
            drawList(items, cells);
            if (items.isEmpty()) {
                set(22, icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>沒有移除的塔"));
            }
            set(SLOT_BACK, icon(Material.ARROW, "<white>← 回塔與城塞"));
            set(SLOT_CLOSE, closeButton());
            return;
        }
        for (final AtlasGrid.Cell citadel : atlas.openCitadels()) {
            // 2026-10-24：自己的終局還不能打的城塞順延到下一個能打的終局
            final String target = service.citadelTarget(player, citadel);
            final MapConfig.MapDef own = config.find(citadel.citadelFinal());
            final MapConfig.MapDef map = config.find(target);
            final String name = (map == null ? String.valueOf(target) : map.name())
                    + (target != null && !target.equals(citadel.citadelFinal()) && own != null ? "（" + own.name() + "的城塞）" : "");
            final java.util.Set<String> completed = service.completed(player);
            final boolean ready = map != null && Atlas.finalRequirementsMet(config, map, completed);
            final String missing = map == null ? "" : Atlas.missingRequirements(config, map, completed);
            final int best = Math.max(1, service.bestTier(player));
            final ItemStack icon = icon(Material.END_CRYSTAL, (ready ? "<#c48bff>" : "<gray>") + "城塞・" + ItemRenderer.escape(name) + " " + AtlasMenu.where(citadel.q(), citadel.r()) + "",
                    "<gray>距離 " + citadel.distance() + (map == null ? "" : "・Boss：<#af6025>" + ItemRenderer.escape(map.boss().name())),
                    "<gray>第一次從這裡開不用天穹碎片；攻破後那個終局用碎片開",
                    "<gray>會開在 T" + Math.min(best, citadel.tier()) + "<dark_gray>（你完成過的最高階 T" + best + " 與這格的 T" + citadel.tier() + " 取低）",
                    ready ? "<yellow>點一下開" : "<red>要先打贏 " + ItemRenderer.escape(missing));
            items.add(ready ? glowing(icon) : icon);
            cells.add(citadel);
        }
        drawList(items, cells);
        if (hiddenCount > 0 || uselessCount > 0) {
            set(SLOT_HIDDEN, icon(Material.GRAY_DYE, "<gray>移除的塔：" + hiddenCount + " 座",
                    uselessCount > 0 ? "<dark_gray>另外 " + uselessCount + " 座周圍已經沒有吃得到石板的地圖，自動不列" : "",
                    hiddenCount > 0 ? "<yellow>點一下看（可以放回來）" : ""));
        }
        if (items.isEmpty()) {
            set(22, icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>還沒看到塔或城塞",
                    "<gray>塔在距離 " + rules.tower().minDistance() + " 格以外、城塞在 " + rules.citadels().minDistance() + " 格以外",
                    "<gray>完成格子往外走就會看到（/atlas）"));
        }
        set(SLOT_BACK, icon(Material.FILLED_MAP, "<#f5b942>← 星圖", "<gray>看星圖上塔與城塞的位置"));
        set(SLOT_HELP, helpButton(List.of(
                "<gray>塔：先驅留下的瞭望塔。完成後解鎖周圍一片、拿天賦點、掉一片先驅石板",
                "<gray>石板插進完成的塔，效果罩到塔周圍的所有地圖（事件更多、Boss 更強、掉更多）",
                "<gray>城塞：終局 Boss 的入口。第一次從城塞開不用天穹碎片，攻破後那個終局就能用碎片開",
                "<gray>石板：完成事件 3% 掉那種事件的、地圖 Boss 2% 掉首領石板、塔必掉一片",
                "<gray>插了石板的塔用<#ffd75e>信標</gray>標出來；周圍沒有吃得到石板的地圖的塔自動不列",
                "<gray>Shift＋右鍵完成的塔：從清單移除（左下「移除的塔」可以放回來）")));
        set(SLOT_CLOSE, closeButton());
    }

    private void drawList(final List<ItemStack> items, final List<AtlasGrid.Cell> cells) {
        for (int i = 0; i < Math.min(LIST_SIZE, items.size()); i++) {
            set(i, appendLore(items.get(i), "<aqua>右鍵：在星圖上看這一格")); // 2026-10-23
            shown.put(i, cells.get(i));
        }
    }

    private void drawTablets() {
        int slot = 0;
        for (final AtlasTablets.Kind kind : AtlasTablets.Kind.values()) {
            final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(kind.currencyId());
            final int have = type == null ? 0 : service.countCurrency(player, type);
            final ItemStack icon = type == null ? icon(Material.SMOOTH_STONE_SLAB, "<gray>" + kind.displayName(), "<red>currencies.yml 沒有 " + kind.currencyId())
                    : plugin.currencies().create(type, Math.max(1, Math.min(64, have)));
            final List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("<gray>" + ItemRenderer.escape(kind.description()));
            extra.add(have > 0 ? "<yellow>左鍵：插進塔 " + AtlasMenu.where(picking.q(), picking.r()) + "<gray>（你有 " + have + " 片）" : "<red>你沒有這種石板");
            set(slot, have > 0 ? targetHighlight(appendLore(icon, extra)) : appendLore(icon, extra));
            pickShown.put(slot, kind);
            slot++;
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回塔與城塞"));
        set(SLOT_HELP, helpButton(List.of("<gray>選一片石板插進塔 " + AtlasMenu.where(picking.q(), picking.r()) + "", "<gray>插了拿不回來；罩到的地圖每開一張用掉 1 次，" + AtlasTablets.USES + " 次用完可以再插")));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            if (picking != null) {
                picking = null;
                redraw();
            } else if (showHidden) {
                showHidden = false;
                redraw();
            } else {
                new AtlasMenu(plugin, player, service).open();
            }
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (slot == SLOT_HIDDEN && picking == null && !showHidden) {
            showHidden = true;
            redraw();
            return;
        }
        if (picking != null) {
            final AtlasTablets.Kind kind = pickShown.get(slot);
            if (kind == null) {
                return;
            }
            final String problem = service.insertTablet(player, picking, kind);
            if (problem != null) {
                fail(problem);
                return;
            }
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
            picking = null;
            redraw();
            return;
        }
        final AtlasGrid.Cell cell = shown.get(slot);
        if (cell == null) {
            return;
        }
        final AtlasProgress atlas = service.atlasProgress(player);
        if (cell.kind() == AtlasGrid.Kind.TOWER && atlas.isCompleted(cell.q(), cell.r()) && click == ClickType.SHIFT_RIGHT) {
            // 2026-10-23：手動移除／放回
            service.setTowerHidden(player, cell.q(), cell.r(), !showHidden);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, showHidden ? 1.4f : 0.8f);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>塔 " + AtlasMenu.where(cell.q(), cell.r()) + " "
                    + (showHidden ? "放回清單了" : "從清單移除了（下面「移除的塔」可以放回來）")));
            redraw();
            return;
        }
        if (click == ClickType.RIGHT) {
            // 2026-10-23：右鍵：打開星圖、視窗移到這一格
            new AtlasMenu(plugin, player, service).focus(cell.q(), cell.r()).open();
            return;
        }
        if (showHidden) {
            return;
        }
        if (cell.kind() == AtlasGrid.Kind.TOWER) {
            if (atlas.isCompleted(cell.q(), cell.r())) {
                if (service.tabletAt(player, cell.q(), cell.r()) != null) {
                    new AtlasMenu(plugin, player, service).focus(cell.q(), cell.r()).open(); // 2026-10-23：插好石板的：左鍵也直接去看
                    return;
                }
                if (service.tabletTargets(player, atlas, cell) == 0) {
                    fail("這座塔周圍已經沒有吃得到石板的地圖了");
                    return;
                }
                picking = cell;
                redraw();
                return;
            }
            final String problem = service.openTower(player, cell);
            if (problem != null) {
                fail(problem);
                return;
            }
            player.closeInventory();
            return;
        }
        if (cell.kind() == AtlasGrid.Kind.CITADEL) {
            final String problem = service.activateCitadel(player, cell, 0);
            if (problem != null) {
                fail(problem);
                return;
            }
            player.closeInventory();
        }
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(message)));
    }
}
