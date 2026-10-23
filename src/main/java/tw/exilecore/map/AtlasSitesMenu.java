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
 */
public final class AtlasSitesMenu extends Menu {

    private static final int LIST_SIZE = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_HELP = 49;
    private static final int SLOT_CLOSE = 53;

    private final MapService service;
    /** 格子 → 星圖上的格子。 */
    private final Map<Integer, AtlasGrid.Cell> shown = new HashMap<>();
    /** 正在替這座塔選石板（null = 列塔與城塞）。 */
    private AtlasGrid.Cell picking;
    private final Map<Integer, AtlasTablets.Kind> pickShown = new HashMap<>();

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
            items.add(glowing(icon(Material.LANTERN, "<green>塔 (" + tower.q() + ", " + tower.r() + ")",
                    "<gray>距離 " + tower.distance() + "・T" + tower.tier() + "・怪物等級 " + config.tiers().level(config.tiers().clamp(tower.tier())),
                    "<gray>爬到頂、打倒塔守就完成：解鎖周圍 " + rules.unlock().onTower() + " 格、天賦點 +" + rules.tower().points() + "、掉 1 片先驅石板",
                    "<yellow>點一下開（不用地圖物品，塔只能打一次）")));
            cells.add(tower);
        }
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            final AtlasTablets.Kind kind = service.tabletAt(player, tower.q(), tower.r());
            final int radius = rules.tower().radius() + service.atlasBonus(player).count("atlas.tower.radius");
            if (kind == null) {
                items.add(icon(Material.SOUL_LANTERN, "<#d8c8a0>塔 (" + tower.q() + ", " + tower.r() + ")<gray> 已完成・還沒插石板",
                        "<gray>插一片先驅石板，效果罩到周圍 " + radius + " 格的地圖",
                        "<yellow>點一下選石板"));
            } else {
                items.add(icon(Material.SOUL_LANTERN, "<#d8c8a0>塔 (" + tower.q() + ", " + tower.r() + ")<gray> " + kind.displayName(),
                        "<gray>" + ItemRenderer.escape(kind.description()),
                        "<gray>還能用 <white>" + service.tabletUses(player, tower.q(), tower.r()) + "<gray> / " + AtlasTablets.USES + " 張地圖",
                        "<dark_gray>罩到周圍 " + radius + " 格；罩到的地圖每開一張用掉 1 次，用完可以再插"));
            }
            cells.add(tower);
        }
        for (final AtlasGrid.Cell citadel : atlas.openCitadels()) {
            final MapConfig.MapDef map = config.find(citadel.citadelFinal());
            final String name = map == null ? String.valueOf(citadel.citadelFinal()) : map.name();
            final java.util.Set<String> completed = service.completed(player);
            final boolean ready = map != null && Atlas.finalRequirementsMet(config, map, completed);
            final String missing = map == null ? "" : Atlas.missingRequirements(config, map, completed);
            final int best = Math.max(1, service.bestTier(player));
            final ItemStack icon = icon(Material.END_CRYSTAL, (ready ? "<#c48bff>" : "<gray>") + "城塞・" + ItemRenderer.escape(name) + " (" + citadel.q() + ", " + citadel.r() + ")",
                    "<gray>距離 " + citadel.distance() + (map == null ? "" : "・Boss：<#af6025>" + ItemRenderer.escape(map.boss().name())),
                    "<gray>第一次從這裡開不用天穹碎片；攻破後那個終局用碎片開",
                    "<gray>會開在 T" + Math.min(best, citadel.tier()) + "<dark_gray>（你完成過的最高階 T" + best + " 與這格的 T" + citadel.tier() + " 取低）",
                    ready ? "<yellow>點一下開" : "<red>要先打贏 " + ItemRenderer.escape(missing));
            items.add(ready ? glowing(icon) : icon);
            cells.add(citadel);
        }
        for (int i = 0; i < Math.min(LIST_SIZE, items.size()); i++) {
            set(i, items.get(i));
            shown.put(i, cells.get(i));
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
                "<gray>石板：完成事件 3% 掉那種事件的、地圖 Boss 2% 掉首領石板、塔必掉一片")));
        set(SLOT_CLOSE, closeButton());
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
            extra.add(have > 0 ? "<yellow>左鍵：插進塔 (" + picking.q() + ", " + picking.r() + ")<gray>（你有 " + have + " 片）" : "<red>你沒有這種石板");
            set(slot, have > 0 ? targetHighlight(appendLore(icon, extra)) : appendLore(icon, extra));
            pickShown.put(slot, kind);
            slot++;
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回塔與城塞"));
        set(SLOT_HELP, helpButton(List.of("<gray>選一片石板插進塔 (" + picking.q() + ", " + picking.r() + ")", "<gray>插了拿不回來；罩到的地圖每開一張用掉 1 次，" + AtlasTablets.USES + " 次用完可以再插")));
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
            } else {
                new AtlasMenu(plugin, player, service).open();
            }
            return;
        }
        if (!acceptAction(click)) {
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
        if (cell.kind() == AtlasGrid.Kind.TOWER) {
            if (atlas.isCompleted(cell.q(), cell.r())) {
                if (service.tabletAt(player, cell.q(), cell.r()) != null) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>這座塔已經插了石板"));
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
