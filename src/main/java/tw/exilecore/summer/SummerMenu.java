package tw.exilecore.summer;

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

/** 夏日活動「星潮祭」（2026-11-03）的選單：活動說明、傳送到海灘、海妖、挖寶、大亂鬥、挑戰、兌換商店。照萬燈夜的 FestivalMenu。 */
public final class SummerMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int SLOT_TRAVEL = 10;
    private static final int SLOT_KRAKEN = 12;
    private static final int SLOT_DIG = 13;
    private static final int SLOT_MELEE = 14;
    private static final int SLOT_CHALLENGES = 16;
    private static final int FIRST_ITEM = 18;

    private final SummerService service;
    private final Map<Integer, SummerConfig.ShopItem> shown = new HashMap<>();
    private int confirmSlot = -1;
    /** 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」：兌換變多（入場券、傳奇），一頁 27 格放不下，分頁（第幾頁，0 起）。 */
    private int page;
    private static final int PER_PAGE = 45 - FIRST_ITEM;
    private long confirmUntil;

    public SummerMenu(final ExileCore plugin, final Player player, final SummerService service) {
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
        final SummerConfig config = service.config();
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        final boolean running = service.running();

        // 說明
        final List<String> info = new ArrayList<>();
        info.add(running ? "<green>進行中：<white>" + config.format(config.start()) + " ～ " + config.format(config.end())
                : "<gray>活動結束了；兌換開到 <white>" + config.format(config.shopUntil()));
        info.add("");
        info.add("<gray>・<white>沙灘挖寶<gray>：拿鏟子右鍵海灘的沙子，每天 " + config.dig().spotsPerDay() + " 個藏寶點（動作條說熱不熱）");
        info.add("<gray>・<white>西瓜大亂鬥<gray>：每天 " + hours(config.melee().hours(), config.meleeMinute()) + " 在海灘開一局，踩破、打破西瓜計分");
        info.add("<gray>・<white>" + ItemRenderer.escape(config.boss().name()) + "<gray>：每天 " + hours(config.boss().hours(), config.bossMinute()) + " 浮上海灘中央");
        info.add("<gray>・<white>漂流瓶<gray>：地圖裡的怪掉貝殼；稀有怪、地圖 Boss 有機會留下漂流瓶（瓶中信或藏寶圖）");
        info.add("<gray>・挑戰、兌換限定時裝與稱號（時裝、稱號拿到是永久的）");
        info.add("");
        info.add("<gray>全服打破的西瓜：<white>" + service.globalCount() + "／" + config.melee().globalTarget()
                + " <gray>（滿了「滿潮」：全服物品數量 +" + config.melee().globalQuantity() + "%，" + config.melee().globalMinutes() + " 分鐘）");
        final long bonus = service.bonusMinutesLeft();
        if (bonus > 0) {
            info.add("<" + color() + ">✦ 滿潮：全服物品數量 +" + config.melee().globalQuantity() + "%（還剩 " + bonus + " 分鐘）");
        }
        info.add("");
        info.add("<gray>你的素材：");
        info.add("<" + color() + ">" + ItemRenderer.escape(service.currencyName(SummerConfig.SHELL)) + " <white>"
                + wallet.available(player, SummerConfig.SHELL) + "　<#ffe9c9>" + ItemRenderer.escape(service.currencyName(SummerConfig.PEARL))
                + " <white>" + wallet.available(player, SummerConfig.PEARL));
        info.add("<dark_gray>（背包、通貨袋、倉庫的通貨頁都算）");
        final ItemStack infoIcon = icon(Material.NAUTILUS_SHELL, "<" + color() + ">🌊 " + ItemRenderer.escape(config.name()), info);
        set(SLOT_INFO, bonus > 0 ? glowing(infoIcon) : infoIcon);

        // 傳送
        if (running) {
            set(SLOT_TRAVEL, icon(Material.ENDER_PEARL, "<#7fd4ff>前往星潮海灘", "<gray>免費；回營地：/town",
                    "<gray>海灘上死掉會回營地", "", "<yellow>左鍵：傳送"));
        } else {
            set(SLOT_TRAVEL, icon(Material.GRAY_DYE, "<dark_gray>前往星潮海灘", "<gray>活動結束了"));
        }

        // 海妖
        final SummerConfig.Boss boss = config.boss();
        final List<String> kraken = new ArrayList<>();
        if (service.krakenRunning()) {
            final long left = service.krakenSecondsLeft();
            kraken.add("<green>在海灘上！" + String.format(Locale.ROOT, "<gray>（還剩 %d:%02d）", left / 60, left % 60));
        } else if (!running) {
            kraken.add("<gray>活動結束了");
        } else {
            kraken.add("<gray>下一次：<white>" + (service.nextKrakenClock() == null ? "等待排程" : service.nextKrakenClock()) + "<gray>（台灣時間）");
        }
        kraken.add("<gray>每天 " + hours(boss.hours(), config.bossMinute()) + "，打 " + boss.durationMinutes() + " 分鐘");
        kraken.add("<gray>世界 Boss 等級（Lv." + boss.level() + "），生命照在場 " + boss.countLevel() + " 等以上的人數");
        kraken.add("");
        kraken.add("<gray>每個有份的人：");
        kraken.add("<#ffe9c9>・珍珠 " + boss.pearl()[0] + "–" + boss.pearl()[1] + "<gray>、<" + color() + ">貝殼 " + boss.shells());
        kraken.add("<#ff9ad5>・" + trim(boss.cosmeticChance() * 100) + "% 掉一件還沒有的星潮祭時裝");
        kraken.add("<dark_gray>（都吃物品數量、物品稀有度）");
        final ItemStack krakenIcon = icon(Material.TRIDENT, "<" + color() + ">" + ItemRenderer.escape(boss.name()), kraken);
        set(SLOT_KRAKEN, service.krakenRunning() ? glowing(krakenIcon) : krakenIcon);

        // 挖寶
        final int mask = service.dugToday(player);
        final int count = config.dig().spotsPerDay();
        final StringBuilder dots = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dots.append((mask & 1 << i) != 0 ? "<" + color() + ">●" : "<dark_gray>○");
        }
        final List<String> dig = new ArrayList<>();
        dig.add("<gray>今天：<white>" + Integer.bitCount(mask) + "／" + count + " 個　" + dots);
        dig.add("<gray>拿著武器<white>右鍵沙灘的沙子</white>就挖：很熱（4 格內）、溫（8 格內）、冷（海妖、大亂鬥進行中右鍵照常放技能）");
        dig.add("<gray>挖到：<" + color() + ">貝殼 ×" + config.dig().shells() + "<gray>；" + trim(config.dig().chestChance() * 100)
                + "% 是海盜寶箱（" + config.dig().guardSeconds() + " 秒內清掉守衛拿雙倍、" + trim(config.dig().pearlChance() * 100) + "% 多一顆珍珠）");
        dig.add("<gray>一天挖滿全部：<#ffe9c9>珍珠 ×" + config.dig().dailyPearl());
        dig.add("<gray>每天（台灣時間）重新藏；每個人的藏寶點都不一樣");
        if (service.holdsMap(player)) {
            dig.add("");
            dig.add("<#ffd166>🗺 手上有一張藏寶圖：<gray>下一張地圖的 Boss 多掉 " + config.bottle().mapExtra() + " 件東西");
        }
        set(SLOT_DIG, icon(Integer.bitCount(mask) == count ? Material.GOLDEN_SHOVEL : Material.IRON_SHOVEL, "<#f5d79a>沙灘挖寶", dig));

        // 大亂鬥
        final SummerConfig.Melee melee = config.melee();
        final List<String> round = new ArrayList<>();
        if (service.roundRunning()) {
            final long left = service.roundSecondsLeft();
            round.add("<green>進行中！" + String.format(Locale.ROOT, "<gray>（還剩 %d:%02d）", left / 60, left % 60));
        } else if (!running) {
            round.add("<gray>活動結束了");
        } else {
            round.add("<gray>下一局：<white>" + (service.nextRoundClock() == null ? "等待排程" : service.nextRoundClock()) + "<gray>（台灣時間）");
        }
        round.add("<gray>每天 " + hours(melee.hours(), config.meleeMinute()) + "，一局 " + melee.seconds() + " 秒");
        round.add("<gray>海灘上每 " + melee.spawnEvery() + " 秒冒出 " + melee.spawnCount() + " 顆西瓜：踩破、打破 1 分；金西瓜 10 分");
        round.add("<gray>前三名：<#ffe9c9>珍珠 " + tops(melee.topPearls()) + "<gray>；每一分 <" + color() + ">貝殼 ×" + melee.shellsPerPoint());
        round.add("<gray>全服每打破 " + melee.globalTarget() + " 顆：滿潮（全服物品數量 +" + melee.globalQuantity() + "%）");
        final ItemStack roundIcon = icon(Material.MELON, "<#7cff6b>西瓜大亂鬥", round);
        set(SLOT_MELEE, service.roundRunning() ? glowing(roundIcon) : roundIcon);

        // 挑戰
        final List<String> challenges = new ArrayList<>();
        boolean all = true;
        for (final SummerService.Challenge challenge : service.challenges(player)) {
            all &= challenge.done();
            challenges.add((challenge.done() ? "<green>✔ " : "<gray>・") + ItemRenderer.escape(challenge.name()) + " <white>" + challenge.progress()
                    + "／" + challenge.goal() + " <dark_gray>→ " + ItemRenderer.escape(challenge.reward()));
        }
        final SummerConfig.Title title = config.titles().get(config.challenges().title());
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
        final List<SummerConfig.ShopItem> items = config.shop();
        final int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int slot = FIRST_ITEM;
        for (int i = page * PER_PAGE; i < items.size() && slot < 45; i++) {
            final SummerConfig.ShopItem item = items.get(i);
            shown.put(slot, item);
            set(slot, entry(item, slot));
            slot++;
        }
        if (pages > 1) { // 2026-11-05：分頁
            if (page > 0) {
                set(SLOT_PREV, pageButton(false, page, pages));
            }
            if (page < pages - 1) {
                set(SLOT_NEXT, pageButton(true, page, pages));
            }
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    /** 時間的列表；連續的縮成「09:45～23:45 每小時」（2026-11-03：照 minute 錯開，這次之前一律整點）。 */
    private static String hours(final java.util.SortedSet<Integer> hours, final int minute) {
        if (hours.size() >= 4 && hours.last() - hours.first() + 1 == hours.size()) {
            return String.format(Locale.ROOT, "%02d:%02d～%02d:%02d 每小時", hours.first(), minute, hours.last(), minute);
        }
        final StringBuilder text = new StringBuilder();
        for (final int hour : hours) {
            text.append(text.isEmpty() ? "" : "、").append(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
        }
        return text.toString();
    }

    private static String tops(final int[] tops) {
        final StringBuilder text = new StringBuilder();
        for (final int top : tops) {
            text.append(text.isEmpty() ? "" : "／").append(top);
        }
        return text.toString();
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String styled(final SummerConfig.Title title) {
        final String color = title.color();
        if (color.startsWith("gradient:")) {
            return "<" + color + ">" + ItemRenderer.escape(title.name()) + "</gradient>";
        }
        return "<" + color + ">" + ItemRenderer.escape(title.name()) + "</" + color + ">";
    }

    private ItemStack entry(final SummerConfig.ShopItem item, final int slot) {
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
                    if (tw.exilecore.sponsor.CosmeticPreview.isEmote(sponsors, item.cosmetic())) {
                        lore.add("<gray>動作：贊助 → 動作 綁到 F／Q／Shift＋Q（或打 /emote 名稱）"); // 2026-11-03 星潮祭的衝浪
                    }
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
                final SummerConfig.Title title = service.config().titles().get(item.title());
                lore.add("<gray>限定稱號（永久擁有，帳號共用）");
                if (title != null) {
                    for (final String line : StatLines.describeAll(title.stats())) {
                        lore.add("<#8fd3ff>擁有就有：" + ItemRenderer.escape(line));
                    }
                }
                stack = icon(Material.NAME_TAG, title == null ? item.title() : "【" + styled(title) + "】", List.of());
            }
            case UNIQUE -> { // 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」：直接畫出這件傳奇（跟不定時活動的兌換一樣）
                final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(item.unique());
                if (unique != null && plugin.items().repository().base(unique.base()) != null) {
                    try {
                        final tw.exilecore.item.ItemData data = plugin.items().generator().generateUnique(unique, item.itemLevel(),
                                new java.util.Random(unique.id().hashCode()));
                        data.setIdentified(true);
                        stack = plugin.items().renderer().create(data); // 只畫外觀，不寫物品資料
                    } catch (final RuntimeException e) {
                        stack = null;
                    }
                }
                lore.add("");
                lore.add("<" + color() + ">★ " + ItemRenderer.escape(service.config().name()) + "專屬傳奇<gray>（物品等級 " + item.itemLevel() + "）");
                lore.add("<dark_gray>預覽：數值是隨機擲的一個例子；兌換時重新擲");
                if (stack == null) {
                    stack = icon(Material.GOLDEN_SWORD, "<#af6025>" + ItemRenderer.escape(unique == null ? item.unique() : unique.name()), List.of());
                }
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
            if (item.kind() == SummerConfig.Kind.COSMETIC && plugin.sponsors() != null) {
                // 2026-11-03 照站長：動作右鍵是做一次看看；這次之前一律寫「試穿」（動作按了只會說不能試穿）
                lore.add(tw.exilecore.sponsor.CosmeticPreview.isEmote(plugin.sponsors(), item.cosmetic()) ? "<yellow>右鍵：做一次看看"
                        : "<yellow>右鍵：試穿 " + tw.exilecore.sponsor.CosmeticPreview.tryOnSeconds() + " 秒");
            }
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
        final int pages = Math.max(1, (service.config().shop().size() + PER_PAGE - 1) / PER_PAGE);
        if (slot == SLOT_PREV && page > 0 || slot == SLOT_NEXT && page < pages - 1) { // 2026-11-05：換頁
            page += slot == SLOT_NEXT ? 1 : -1;
            confirmSlot = -1;
            redraw();
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
        final SummerConfig.ShopItem item = shown.get(slot);
        if (item != null && click == ClickType.RIGHT && item.kind() == SummerConfig.Kind.COSMETIC && plugin.sponsors() != null
                && !service.owned(player, item)) {
            // 右鍵試穿（關掉選單才看得到自己）
            final String problem = tw.exilecore.sponsor.CosmeticPreview.tryOn(plugin, plugin.sponsors(), player, item.cosmetic());
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            } else {
                player.closeInventory();
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.2f);
            }
            return;
        }
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
