package tw.exilecore.festival;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 節慶活動（2026-10 萬燈夜）的選單：活動說明、傳送到墓園、南瓜王、點燈、挑戰、兌換商店。 */
public final class FestivalMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int SLOT_TRAVEL = 10;
    private static final int SLOT_KING = 12;
    private static final int SLOT_LAMPS = 14;
    private static final int SLOT_CHALLENGES = 16;
    private static final int FIRST_ITEM = 18;

    private final FestivalService service;
    private final Map<Integer, FestivalConfig.ShopItem> shown = new HashMap<>();
    private int confirmSlot = -1;
    private long confirmUntil;

    public FestivalMenu(final ExileCore plugin, final Player player, final FestivalService service) {
        super(plugin, player, 6, "<dark_gray>" + ItemRenderer.escape(service.config().name()));
        this.service = service;
    }

    private String color() {
        return service.config().color();
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        final FestivalConfig config = service.config();
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        final boolean running = service.running();

        // 說明
        final List<String> info = new ArrayList<>();
        info.add(running ? "<green>進行中：<white>" + config.format(config.start()) + " ～ " + config.format(config.end())
                : "<gray>活動結束了；兌換開到 <white>" + config.format(config.shopUntil()));
        info.add("");
        info.add("<gray>・<white>幽燈墓園<gray>：永遠是夜晚的墓園，站在南瓜燈旁邊守 " + config.lanterns().guardSeconds() + " 秒點燈");
        info.add("<gray>・<white>南瓜王<gray>：每天 " + hours(config) + " 出現在墓園中央");
        info.add("<gray>・<white>不給糖就搗蛋<gray>：地圖裡的怪掉糖果；稀有怪、地圖 Boss 有機會留下搗蛋南瓜");
        info.add("<gray>・挑戰、兌換限定時裝與稱號（時裝、稱號拿到是永久的）");
        info.add("");
        info.add("<gray>全服點燈：<white>" + service.globalCount() + "／" + config.lanterns().globalTarget()
                + " <gray>（點滿全服物品數量 +" + config.lanterns().globalQuantity() + "%，" + config.lanterns().globalMinutes() + " 分鐘）");
        final long bonus = service.bonusMinutesLeft();
        if (bonus > 0) {
            info.add("<" + color() + ">✦ 萬燈齊亮：全服物品數量 +" + config.lanterns().globalQuantity() + "%（還剩 " + bonus + " 分鐘）");
        }
        info.add("");
        info.add("<gray>你的素材：");
        info.add("<" + color() + ">" + ItemRenderer.escape(service.currencyName(FestivalConfig.CANDY)) + " <white>"
                + wallet.available(player, FestivalConfig.CANDY) + "　<#ffd27f>" + ItemRenderer.escape(service.currencyName(FestivalConfig.WICK))
                + " <white>" + wallet.available(player, FestivalConfig.WICK));
        info.add("<dark_gray>（背包、通貨袋、倉庫的通貨頁都算）");
        final ItemStack infoIcon = icon(Material.JACK_O_LANTERN, "<" + color() + ">🎃 " + ItemRenderer.escape(config.name()), info);
        set(SLOT_INFO, bonus > 0 ? glowing(infoIcon) : infoIcon);

        // 傳送
        if (running) {
            set(SLOT_TRAVEL, icon(Material.ENDER_PEARL, "<#7fd4ff>前往幽燈墓園", "<gray>免費；回營地：/town",
                    "<gray>墓園裡死掉會回營地", "", "<yellow>左鍵：傳送"));
        } else {
            set(SLOT_TRAVEL, icon(Material.GRAY_DYE, "<dark_gray>前往幽燈墓園", "<gray>活動結束了"));
        }

        // 南瓜王
        final FestivalConfig.Boss boss = config.boss();
        final List<String> king = new ArrayList<>();
        if (service.kingRunning()) {
            final long left = service.kingSecondsLeft();
            king.add("<green>在墓園裡！" + String.format(Locale.ROOT, "<gray>（還剩 %d:%02d）", left / 60, left % 60));
        } else if (!running) {
            king.add("<gray>活動結束了");
        } else {
            king.add("<gray>下一次：<white>" + (service.nextKingClock() == null ? "等待排程" : service.nextKingClock()) + "<gray>（台灣時間）");
        }
        king.add("<gray>每天 " + hours(config) + "，打 " + boss.durationMinutes() + " 分鐘");
        king.add("<gray>世界 Boss 等級（Lv." + boss.level() + "），生命照在場 " + boss.countLevel() + " 等以上的人數");
        king.add("");
        king.add("<gray>每個有份的人：");
        king.add("<#ffd27f>・燈芯 " + boss.wick()[0] + "–" + boss.wick()[1] + "<gray>、<" + color() + ">糖果 " + boss.candy());
        king.add("<#ff9ad5>・" + trim(boss.cosmeticChance() * 100) + "% 掉一件還沒有的萬燈夜時裝");
        king.add("<dark_gray>（都吃物品數量、物品稀有度）");
        final ItemStack kingIcon = icon(Material.CARVED_PUMPKIN, "<" + color() + ">" + ItemRenderer.escape(boss.name()), king);
        set(SLOT_KING, service.kingRunning() ? glowing(kingIcon) : kingIcon);

        // 點燈
        final int mask = service.litToday(player);
        final int count = config.lanterns().count();
        final StringBuilder dots = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dots.append((mask & 1 << i) != 0 ? "<" + color() + ">●" : "<dark_gray>○");
        }
        final List<String> lamps = new ArrayList<>();
        lamps.add("<gray>今天：<white>" + Integer.bitCount(mask) + "／" + count + " 盞　" + dots);
        lamps.add("<gray>站在南瓜燈旁邊守 " + config.lanterns().guardSeconds() + " 秒點亮（燈裡會竄出幽魂）");
        lamps.add("<gray>一天點滿全部：<#ffd27f>燈芯 ×" + config.lanterns().dailyWick());
        lamps.add("<gray>每天（台灣時間）重新點；每個人點自己的燈");
        set(SLOT_LAMPS, icon(Integer.bitCount(mask) == count ? Material.JACK_O_LANTERN : Material.LANTERN, "<#ffd27f>點燈", lamps));

        // 挑戰
        final List<String> challenges = new ArrayList<>();
        boolean all = true;
        for (final FestivalService.Challenge challenge : service.challenges(player)) {
            all &= challenge.done();
            challenges.add((challenge.done() ? "<green>✔ " : "<gray>・") + ItemRenderer.escape(challenge.name()) + " <white>" + challenge.progress()
                    + "／" + challenge.goal() + " <dark_gray>→ " + ItemRenderer.escape(challenge.reward()));
        }
        final FestivalConfig.Title title = config.titles().get(config.challenges().title());
        if (title != null) {
            challenges.add("");
            challenges.add((all ? "<green>✔ " : "<gray>") + "全部完成：稱號【" + styled(title) + (all ? "<green>" : "<gray>") + "】");
            for (final String line : StatLines.describeAll(title.stats())) {
                challenges.add("<#8fd3ff>　擁有就有：" + ItemRenderer.escape(line));
            }
        }
        challenges.add("<dark_gray>（帳號共用；達成自動發）");
        set(SLOT_CHALLENGES, icon(Material.WRITABLE_BOOK, "<#f5b942>活動挑戰", challenges));

        // 兌換
        int slot = FIRST_ITEM;
        for (final FestivalConfig.ShopItem item : config.shop()) {
            if (slot >= 45) {
                break;
            }
            shown.put(slot, item);
            set(slot, entry(item, slot));
            slot++;
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private static String hours(final FestivalConfig config) {
        final StringBuilder text = new StringBuilder();
        for (final int hour : config.boss().hours()) {
            text.append(text.isEmpty() ? "" : "、").append(String.format(Locale.ROOT, "%02d:00", hour));
        }
        return text.toString();
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String styled(final FestivalConfig.Title title) {
        final String color = title.color();
        if (color.startsWith("gradient:")) {
            return "<" + color + ">" + ItemRenderer.escape(title.name()) + "</gradient>";
        }
        return "<" + color + ">" + ItemRenderer.escape(title.name()) + "</" + color + ">";
    }

    private ItemStack entry(final FestivalConfig.ShopItem item, final int slot) {
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        final List<String> lore = new ArrayList<>();
        final boolean owned = service.owned(player, item);
        ItemStack stack = null;
        switch (item.kind()) {
            case COSMETIC -> {
                final tw.exilecore.sponsor.SponsorService sponsors = plugin.sponsors();
                lore.add("<" + color() + ">★ " + ItemRenderer.escape(service.config().name()) + "限定時裝<gray>（永久擁有，帳號共用）");
                if (sponsors != null) {
                    final String where = tw.exilecore.sponsor.CosmeticPreview.slotName(sponsors, item.cosmetic());
                    if (!where.isEmpty()) {
                        lore.add("<gray>幻化：" + where + "（贊助 → 裝備幻化 套上去）");
                    }
                    final var cosmetic = sponsors.config().cosmetics().get(item.cosmetic());
                    if (cosmetic != null && cosmetic.description() != null && !cosmetic.description().isBlank()) {
                        lore.add("<dark_gray>" + ItemRenderer.escape(cosmetic.description()));
                    }
                    final String name = tw.exilecore.sponsor.CosmeticPreview.label(sponsors, item.cosmetic());
                    stack = tw.exilecore.sponsor.CosmeticPreview.of(sponsors, item.cosmetic(), name, List.of());
                }
                if (stack == null) {
                    stack = icon(Material.LEATHER_HORSE_ARMOR, "<white>" + ItemRenderer.escape(service.cosmeticName(item.cosmetic())), List.of());
                }
            }
            case TITLE -> {
                final FestivalConfig.Title title = service.config().titles().get(item.title());
                lore.add("<gray>限定稱號（永久擁有，帳號共用）");
                if (title != null) {
                    for (final String line : StatLines.describeAll(title.stats())) {
                        lore.add("<#8fd3ff>擁有就有：" + ItemRenderer.escape(line));
                    }
                }
                stack = icon(Material.NAME_TAG, title == null ? item.title() : "【" + styled(title) + "】", List.of());
            }
            default -> {
                final CurrencyType type = plugin.currencies().repository().get(item.currency());
                final String name = "<white>" + ItemRenderer.escape(service.currencyName(item.currency())) + " ×" + item.amount();
                if (type != null) {
                    try {
                        stack = plugin.currencies().create(type, Math.max(1, Math.min(item.amount(), type.stack())));
                        stack.editMeta(meta -> meta.displayName(ItemRenderer.line(name)));
                    } catch (final RuntimeException e) {
                        stack = null;
                    }
                }
                if (stack == null) {
                    stack = icon(Material.PAPER, name, List.of());
                }
            }
        }
        lore.add("");
        if (owned) {
            lore.add("<green>✔ 已經有了");
        } else {
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
                    : !service.shopOpen() ? "<dark_gray>兌換已經結束" : enough ? "<yellow>左鍵：兌換" : "<dark_gray>素材不夠");
        }
        final ItemStack result = appendLore(stack, lore);
        return confirmSlot == slot || owned ? glowing(result) : result;
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
        if (slot == SLOT_TRAVEL) {
            player.closeInventory();
            final String problem = service.travel(player);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            }
            return;
        }
        final FestivalConfig.ShopItem item = shown.get(slot);
        if (item == null || click != ClickType.LEFT || service.owned(player, item)) {
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
