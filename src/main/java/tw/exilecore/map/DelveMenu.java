package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-07 照站長「地心礦坑簡化版」：星脈深坑的升級（用藍晶買，跟著角色）與排行（帳號最深的一層，不重置）。
 * 地圖裝置的深坑按鈕右鍵打開。2026-11-07：加上選起始層（檢查點）。
 */
final class DelveMenu extends Menu {

    private static final int ROWS = 4;
    private static final int[] UPGRADE_SLOTS = {10, 12, 14};
    private static final int SLOT_LADDER = 16;
    /** 2026-11-07 照站長「選檢查點」：起始層。 */
    private static final int SLOT_START = 31;
    private static final int SLOT_INFO = 27;
    /** 2026-11-07 照站長「守衛新增掉落星坑 BOSS 鑰匙，然後再做一隻 BOSS」：晶淵王座（深坑首領）。 */
    private static final int SLOT_BOSS = 33;
    private static final int SLOT_BACK = 35;
    private static final int LADDER_SIZE = 10;

    private final MapService service;
    private final Delve delve;

    DelveMenu(final ExileCore plugin, final Player player, final MapService service, final Delve delve) {
        super(plugin, player, ROWS, "<dark_gray>" + delve.settings().name());
        this.service = service;
        this.delve = delve;
    }

    @Override
    protected void draw() {
        final MapEventConfig.DelveSettings settings = delve.settings();
        final CurrencyType azurite = delve.azurite();
        final int have = azurite == null ? 0 : service.countCurrency(player, azurite);
        final String currency = azurite == null ? "藍晶" : ItemRenderer.escape(azurite.name());
        final Delve.Upgrade[] upgrades = Delve.Upgrade.values();
        for (int i = 0; i < upgrades.length && i < UPGRADE_SLOTS.length; i++) {
            final Delve.Upgrade upgrade = upgrades[i];
            final int level = delve.upgrade(player, upgrade);
            final int cost = settings.upgradeCost(level);
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>每級：" + upgrade.perLevel);
            lore.add("<gray>現在 <white>" + level + "</white> / " + settings.upgradeMax() + " 級");
            if (upgrade == Delve.Upgrade.DEEP) {
                lore.add("<gray>下次從第 <white>" + delve.startFloor(player) + "</white> 層開始（最深 " + delve.best(player) + " 層）");
            }
            lore.add("");
            if (cost < 0) {
                lore.add("<green>已經滿級");
            } else {
                lore.add((have >= cost ? "<green>" : "<red>") + "下一級：" + cost + " 個" + currency + "（你有 " + have + " 個）");
                if (have >= cost) {
                    lore.add("<green>點一下升級");
                }
            }
            final Material material = switch (upgrade) {
                case WICK -> Material.LANTERN;
                case LIGHT -> Material.GLOWSTONE_DUST;
                case DEEP -> Material.IRON_PICKAXE;
            };
            final var stack = icon(material, "<#7cc4ff>" + upgrade.label + " <white>" + level + "/" + settings.upgradeMax(), lore);
            set(UPGRADE_SLOTS[i], cost >= 0 && have >= cost ? glowing(stack) : stack);
        }
        final DelveLadder ladder = delve.ladder();
        final List<String> lines = new ArrayList<>();
        final List<Map.Entry<UUID, DelveLadder.Entry>> top = ladder.top(LADDER_SIZE);
        if (top.isEmpty()) {
            lines.add("<dark_gray>還沒有人過關過");
        }
        final SimpleDateFormat date = new SimpleDateFormat("MM/dd");
        for (int i = 0; i < top.size(); i++) {
            final DelveLadder.Entry entry = top.get(i).getValue();
            final boolean me = top.get(i).getKey().equals(player.getUniqueId());
            lines.add((i == 0 ? "<gold>" : i < 3 ? "<yellow>" : "<gray>") + (i + 1) + ". " + (me ? "<green>" : "<white>") + ItemRenderer.escape(entry.name())
                    + " <gray>第 <white>" + entry.best() + "</white> 層 <dark_gray>" + ItemRenderer.escape(entry.className()) + "・" + date.format(new Date(entry.at())));
        }
        final int rank = ladder.rank(player.getUniqueId());
        lines.add("");
        lines.add(rank > 0 ? "<gray>你的帳號：第 <white>" + rank + "</white> 名（第 " + ladder.best(player.getUniqueId()) + " 層）" : "<gray>你的帳號還沒上榜");
        lines.add("<dark_gray>排行記帳號打過最深的一層，不會重置");
        set(SLOT_LADDER, icon(Material.LODESTONE, "<gold>深坑排行", lines));
        set(SLOT_INFO, icon(Material.BOOK, "<white>藍晶",
                "<gray>每過一層每個人各拿 " + settings.azuriteBase() + " 個起（每 10 層多 " + settings.azuritePer10() + " 個；守衛層多 " + settings.guardianAzurite() + " 個）",
                "<gray>升級跟著角色；你有 <white>" + have + "</white> 個" + currency));
        // 2026-11-07 照站長「星坑能選層數嗎 → 選檢查點」
        final List<Integer> options = delve.checkpoints(player);
        final int start = delve.startFloor(player);
        final List<String> startLore = new ArrayList<>();
        startLore.add("<gray>下一場從第 <white>" + start + "</white> 層開始");
        final StringBuilder row = new StringBuilder("<gray>能選：");
        for (int i = 0; i < options.size(); i++) {
            final int floor = options.get(i);
            row.append(i == 0 ? "" : "<dark_gray>・").append(floor == start ? "<green>" + floor : "<gray>" + floor);
        }
        startLore.add(row.toString());
        startLore.add("<dark_gray>第 1 層、每過一隻守衛的下一層，最深的是照你最深 " + delve.best(player) + " 層算的");
        startLore.add("");
        startLore.add(options.size() > 1 ? "<yellow>左鍵往深、右鍵往淺" : "<dark_gray>先打過第 " + settings.guardianEvery() + " 層的守衛才有別的檢查點");
        set(SLOT_START, icon(Material.RECOVERY_COMPASS, "<#7cc4ff>起始層：第 " + start + " 層", startLore));
        drawBoss();
        set(SLOT_BACK, backButton("地圖裝置"));
    }

    /** 2026-11-07：晶淵王座——怎麼拿鑰匙、首領、掉什麼；有鑰匙點一下開。 */
    private void drawBoss() {
        final MapEventConfig.DelveBossSettings settings = delve.bossSettings();
        final CurrencyType key = delve.bossKey();
        final MapConfig.EventBoss boss = service.config().eventBoss(Delve.BOSS_KIND);
        if (key == null || boss == null) {
            return;
        }
        final int have = service.countCurrency(player, key);
        final List<String> lore = new ArrayList<>(MapDeviceMenu.delveBossLines(plugin, service, boss));
        lore.add("");
        lore.add("<gray>深坑守衛（每 " + delve.settings().guardianEvery() + " 層一隻）<white>" + Math.round(settings.keyChance() * 100)
                + "%</white> 掉一把" + ItemRenderer.escape(key.name()) + "<dark_gray>（每個有份的人各擲一次）");
        lore.add("<gray>" + ItemRenderer.escape(key.name()) + "：<white>" + have + "</white> / " + settings.cost());
        if (have >= settings.cost()) {
            lore.add("<green>點一下開啟（用掉 " + settings.cost() + " 把）");
        }
        final var stack = icon(have >= settings.cost() ? Material.CRYING_OBSIDIAN : Material.DEEPSLATE_TILES,
                "<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "<gray>・" + ItemRenderer.escape(boss.boss().name()), lore);
        set(SLOT_BOSS, have >= settings.cost() ? glowing(stack) : stack);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            goBack();
            return;
        }
        if (slot == SLOT_BOSS) { // 2026-11-07：晶淵王座
            final CurrencyType key = delve.bossKey();
            if (key == null || service.countCurrency(player, key) < delve.bossSettings().cost()) {
                return;
            }
            final String problem = delve.openBoss(player);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            } else {
                player.closeInventory();
            }
            return;
        }
        if (slot == SLOT_START) {
            if (delve.checkpoints(player).size() > 1) {
                delve.cycleStart(player, click.isRightClick() ? -1 : 1);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                redraw();
            }
            return;
        }
        final Delve.Upgrade[] upgrades = Delve.Upgrade.values();
        for (int i = 0; i < UPGRADE_SLOTS.length && i < upgrades.length; i++) {
            if (UPGRADE_SLOTS[i] != slot) {
                continue;
            }
            final String problem = delve.buy(player, upgrades[i]);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            } else {
                player.sendMessage(plugin.text().prefixed("<#7cc4ff>" + upgrades[i].label + "升到 " + delve.upgrade(player, upgrades[i]) + " 級"));
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
            }
            redraw();
            return;
        }
    }
}
