package tw.exilecore.worldevent;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 選單的「不定時活動」（2026-10）：現在有沒有事件、下一次大概什麼時候、傳送過去、素材兌換。 */
public final class WorldEventMenu extends Menu {

    private static final int SLOT_STATUS = 4;
    private static final int SLOT_TRAVEL = 13;
    private static final int SLOT_FESTIVAL = 15; // 2026-10 萬燈夜
    private static final int FIRST_ITEM = 18;

    private final WorldEventService service;
    private final Map<Integer, WorldEventConfig.ShopItem> shown = new HashMap<>();
    private int confirmSlot = -1;
    private long confirmUntil;

    public WorldEventMenu(final ExileCore plugin, final Player player, final WorldEventService service) {
        super(plugin, player, 6, "<dark_gray>不定時活動：" + ItemRenderer.escape(service.config().name()));
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        final WorldEventConfig config = service.config();
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        final List<String> status = new ArrayList<>();
        if (service.running()) {
            final long left = service.secondsLeft();
            status.add("<green>進行中：<white>" + service.runningKind().displayName()
                    + String.format(Locale.ROOT, " <gray>（還剩 %d:%02d）", left / 60, left % 60));
            final org.bukkit.Location at = service.location();
            if (at != null) {
                status.add(String.format(Locale.ROOT, "<gray>地點：活動場地（%d, %d, %d）", at.getBlockX(), at.getBlockY(), at.getBlockZ()));
            }
        } else {
            final long minutes = service.minutesUntilNext();
            status.add(!config.enabled() ? "<dark_gray>活動沒有開啟" : minutes < 0 ? "<gray>等待下一次事件"
                    : service.nextClock() != null && config.onTheHour()
                    ? "<gray>下一次事件：<white>" + service.nextClock() + "</white> "
                    + (service.upcoming() == null ? "" : "<#ffd060>" + service.upcoming().displayName() + " ")
                    + "<gray>（台灣時間，約 " + minutes + " 分鐘後）"
                    : "<gray>下一次事件：大約 <white>" + minutes + "</white> 分鐘後");
        }
        status.add("");
        status.add(config.onTheHour() ? "<gray>整點在活動場地出現（選單可以傳送過去）：" : "<gray>不定時在活動場地出現：");
        status.add("<#ffb347>・世界 Boss「" + ItemRenderer.escape(config.boss().name()) + "」<gray>（極境難度，2% 專屬傳奇）");
        status.add("<#3fc8ff>・" + ItemRenderer.escape(config.invasion().name()) + "<gray>（" + config.invasion().waves() + " 波怪潮＋統帥）");
        status.add("<gray>每次 " + config.durationMinutes() + " 分鐘；沒完成、附近沒人就結束");
        status.add("");
        status.add("<gray>你的素材：");
        status.add("<#3fc8ff>" + ItemRenderer.escape(service.currencyName(config.invasion().currency())) + " <white>"
                + wallet.available(player, config.invasion().currency()));
        status.add("<#ffd060>" + ItemRenderer.escape(service.currencyName(config.boss().currency())) + " <white>"
                + wallet.available(player, config.boss().currency()));
        status.add("<dark_gray>（背包、通貨袋、倉庫的通貨頁都算）");
        set(SLOT_STATUS, glowing(icon(Material.BELL, "<#ffd060>" + ItemRenderer.escape(config.name()), status)));
        if (service.running()) {
            set(SLOT_TRAVEL, glowing(icon(Material.ENDER_PEARL, "<#7fd4ff>傳送到事件", "<gray>免費（傳到活動場地；回營地：/town）", "",
                    "<yellow>左鍵：傳送")));
        } else if (service.canTravel()) {
            // 2026-10：提早入場（事件還沒開始，先進活動場地等）
            final String next = service.nextClock();
            set(SLOT_TRAVEL, icon(Material.ENDER_PEARL, "<#7fd4ff>提早入場", "<gray>先進活動場地等事件開始（免費）",
                    next == null ? "<gray>等待下一次事件" : "<gray>下一次：<white>" + next, "<gray>回營地：/town", "", "<yellow>左鍵：傳送"));
        } else {
            final String next = service.nextClock();
            set(SLOT_TRAVEL, icon(Material.GRAY_DYE, "<dark_gray>傳送到事件", "<gray>事件進行中才能傳送",
                    next == null ? "<gray>等待下一次事件" : "<gray>下一次：<white>" + next));
        }

        final tw.exilecore.festival.FestivalService festival = plugin.festival();
        if (festival != null && festival.visible()) {
            final ItemStack pumpkin = icon(Material.JACK_O_LANTERN, "<" + festival.config().color() + ">🎃 " + ItemRenderer.escape(festival.config().name()),
                    festival.running() ? "<gray>萬聖節活動：幽燈墓園、點燈、南瓜王、不給糖就搗蛋" : "<gray>活動結束了，兌換還開著",
                    festival.kingRunning() ? "<green>南瓜王在幽燈墓園！" : festival.nextKingClock() == null ? ""
                            : "<gray>南瓜王：下一次 <white>" + festival.nextKingClock(), "", "<yellow>左鍵：打開<dark_gray>（/festival）");
            set(SLOT_FESTIVAL, festival.kingRunning() ? glowing(pumpkin) : pumpkin);
        }

        int slot = FIRST_ITEM;
        for (final WorldEventConfig.ShopItem item : config.shop()) {
            if (slot >= 45) {
                break;
            }
            shown.put(slot, item);
            set(slot, entry(item, slot));
            slot++;
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack entry(final WorldEventConfig.ShopItem item, final int slot) {
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        final List<String> lore = new ArrayList<>();
        final Material material;
        final String name;
        ItemStack preview = null;
        switch (item.kind()) {
            case TITLE -> {
                material = Material.NAME_TAG;
                name = "<" + item.titleColor() + ">【" + ItemRenderer.escape(item.titleName()) + "】";
                lore.add("<gray>限定稱號（永久擁有，帳號共用）");
                for (final String line : StatLines.describeAll(item.titleStats())) {
                    lore.add("<#8fd3ff>擁有就有：" + ItemRenderer.escape(line));
                }
                if (plugin.bounties() != null && plugin.bounties().ownedTitles(player).contains(item.titleId())) {
                    lore.add("<green>✔ 已經有了");
                }
            }
            case CURRENCY -> {
                final CurrencyType type = plugin.currencies().repository().get(item.currency());
                material = type == null ? Material.PAPER : Menu.material(type.material(), Material.PAPER);
                name = "<white>" + ItemRenderer.escape(service.currencyName(item.currency())) + " ×" + item.amount();
                if (type != null) {
                    // 2026-10：用真正的通貨畫（有專屬圖示、原本的說明），下面接兌換資訊
                    try {
                        preview = plugin.currencies().create(type, Math.max(1, Math.min(item.amount(), type.stack())));
                        preview.editMeta(meta -> meta.displayName(ItemRenderer.line(name)));
                    } catch (final RuntimeException e) {
                        preview = null;
                    }
                }
                if (preview == null && type != null && type.description() != null) {
                    lore.add("<gray>" + ItemRenderer.escape(type.description()));
                }
            }
            default -> {
                final UniqueItem unique = plugin.items().repository().unique(item.unique());
                material = Material.GOLDEN_SWORD;
                name = "<#af6025>" + ItemRenderer.escape(unique == null ? item.unique() : unique.name());
                // 2026-10：直接畫出這件傳奇（專屬圖示、詞綴與數值範圍），看得出是什麼裝
                if (unique != null && plugin.items().repository().base(unique.base()) != null) {
                    try {
                        final tw.exilecore.item.ItemData data = plugin.items().generator().generateUnique(unique, Math.max(1, item.itemLevel()),
                                new java.util.Random(unique.id().hashCode()));
                        data.setIdentified(true);
                        preview = plugin.items().renderer().create(data); // 只畫外觀，不寫物品資料
                    } catch (final RuntimeException e) {
                        preview = null;
                    }
                }
                if (preview != null) {
                    lore.add("");
                    lore.add("<#f5b942>━━ 兌換 ━━");
                    lore.add("<gray>Boss 專屬傳奇（物品等級 " + item.itemLevel() + "）");
                    lore.add("<dark_gray>預覽：數值是隨機擲的一個例子，括號裡是範圍；兌換時重新擲");
                } else {
                    lore.add("<gray>Boss 專屬傳奇（物品等級 " + item.itemLevel() + "，數值隨機）");
                }
            }
        }
        lore.add("");
        lore.add("<gray>需要：");
        boolean enough = true;
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            final int have = wallet.available(player, cost.getKey());
            enough &= have >= cost.getValue();
            lore.add((have >= cost.getValue() ? "<green>" : "<red>") + "・" + ItemRenderer.escape(service.currencyName(cost.getKey())) + " ×"
                    + cost.getValue() + " <dark_gray>（有 " + have + "）");
        }
        lore.add("");
        lore.add(confirmSlot == slot && System.currentTimeMillis() < confirmUntil ? "<#ffd27f>▶ 再點一次確認兌換"
                : enough ? "<yellow>左鍵：兌換" : "<dark_gray>素材不夠");
        final ItemStack stack = preview != null ? appendLore(preview, lore) : icon(material, name, lore);
        return confirmSlot == slot ? glowing(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (slot == SLOT_FESTIVAL && plugin.festival() != null && plugin.festival().visible()) {
            tw.exilecore.festival.FestivalModule.open(plugin, player);
            return;
        }
        if (slot == SLOT_TRAVEL && service.canTravel()) {
            player.closeInventory();
            final String problem = service.travel(player);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + problem));
            }
            return;
        }
        final WorldEventConfig.ShopItem item = shown.get(slot);
        if (item == null || click != ClickType.LEFT) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (confirmSlot != slot || now > confirmUntil) {
            confirmSlot = slot;
            confirmUntil = now + 5000;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
            redraw();
            return;
        }
        confirmSlot = -1;
        final String problem = service.buy(player, item);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        } else {
            player.sendMessage(plugin.text().prefixed("<green>兌換成功"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
        }
        redraw();
    }
}
