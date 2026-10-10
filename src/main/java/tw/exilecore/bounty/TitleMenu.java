package tw.exilecore.bounty;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.event.EventConfig;
import tw.exilecore.event.EventMenu;
import tw.exilecore.event.EventProgress;
import tw.exilecore.event.MonthlyEventService;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 稱號（選單的「稱號」）：帳號擁有的稱號一覽，戴著的有外框，點一下戴上／拿下；
 * 還沒有的也列出來（灰色）並寫怎麼拿：功勳兌換（價格，點了直接去兌換）、每月活動限定（哪個月的主題、挑戰全部完成；
 * 這個月就是的話寫剩幾天與做了幾個挑戰）。擁有的排前面。
 */
final class TitleMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int FIRST = 9;
    private static final int LAST = 44;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_TAKE_OFF = 49;
    private static final int SLOT_SHOP = 50;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_PREV = 46;
    private static final int SLOT_NEXT = 52;
    /** 搜尋（2026-10）。 */
    private static final int SLOT_SEARCH = 48;
    private String query = "";
    private static final int PER_PAGE = LAST - FIRST + 1;

    private final BountyService service;
    private final Map<Integer, BountyConfig.Title> shown = new HashMap<>();
    private int page;
    private int pages = 1;

    TitleMenu(final ExileCore plugin, final Player player, final BountyService service) {
        super(plugin, player, 6, "<dark_gray>稱號");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        final Set<String> owned = service.ownedTitles(player);
        final String worn = service.wornTitle(player);
        final List<BountyConfig.Title> all = service.allTitles();
        final List<BountyConfig.Title> ordered = new ArrayList<>();
        for (final BountyConfig.Title title : all) {
            if (owned.contains(title.id()) && tw.exilecore.gui.Menu.matches(query, title.name())) {
                ordered.add(title);
            }
        }
        for (final BountyConfig.Title title : all) {
            if (!owned.contains(title.id()) && service.sourceListed(player, title.id()) && tw.exilecore.gui.Menu.matches(query, title.name())) {
                ordered.add(title);
            }
        }
        set(SLOT_SEARCH, searchButton(query, "稱號"));

        final List<String> header = new ArrayList<>();
        header.add("<gray>TAB 列表與聊天時，名字前面的字");
        header.add(worn == null ? "<gray>現在：<dark_gray>沒戴（點一個戴上）" : "<gray>現在：" + MeritShop.tag(service.title(worn)));
        header.add("<gray>買過或拿過就是整個帳號的，隨時換、隨時拿下");
        // 稱號的永久加成：擁有就有（不用戴），整個帳號的角色都吃得到
        header.add("");
        header.add("<#7fe07f>稱號加成<gray>（擁有就有、不用戴，帳號裡每個角色都有）：");
        final List<String> bonus = tw.exilecore.ascendancy.StatLines.describeAll(service.titleBonus(player));
        if (bonus.isEmpty()) {
            header.add("<dark_gray>  還沒有（拿到稱號就會有）");
        }
        for (final String line : bonus) {
            header.add("<white>  " + BountyService.escape(line));
        }
        pages = Math.max(1, (ordered.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        if (pages > 1) {
            header.add("<dark_gray>第 " + (page + 1) + "／" + pages + " 頁（下面的 ◀ ▶ 換頁）");
        }
        set(SLOT_HEADER, glowing(icon(Material.NAME_TAG, "<#f5b942>稱號　<white>擁有 " + owned.size() + "／" + all.size(), header)));

        final long merits = Math.max(0, service.merits(player));
        int slot = FIRST;
        for (final BountyConfig.Title title : ordered.subList(Math.min(ordered.size(), page * PER_PAGE), ordered.size())) {
            if (slot > LAST) {
                break;
            }
            shown.put(slot, title);
            set(slot, entry(title, owned.contains(title.id()), title.id().equals(worn), merits));
            slot++;
        }
        if (all.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>目前沒有任何稱號", "<dark_gray>bounties.yml 的 shop.titles、events.yml 的主題 title"));
        }

        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        if (worn != null) {
            set(SLOT_TAKE_OFF, icon(Material.SHEARS, "<white>拿下稱號", "<gray>名字前面不顯示稱號（擁有的還在）", "", "<yellow>左鍵：拿下"));
        }
        set(SLOT_SHOP, icon(Material.EMERALD, "<#f5b942>功勳兌換", "<gray>用功勳買稱號（你有 " + BountyService.MERIT + BountyService.format(merits) + " 功勳</#f5b942><gray>）",
                "", "<yellow>左鍵：打開"));
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_CLOSE, closeButton());
    }

    /** 一個稱號：擁有的用名牌（戴著的有外框），還沒有的灰掉並寫怎麼拿。 */
    private ItemStack entry(final BountyConfig.Title title, final boolean owned, final boolean worn, final long merits) {
        final List<String> lore = new ArrayList<>();
        final boolean shop = service.isShopTitle(title.id());
        final EventConfig.Theme theme = shop ? null : eventTheme(title.id());
        final String bonus = MeritShop.bonusText(title);
        if (owned) {
            lore.add(worn ? "<green>戴著" : "<gray>擁有");
            lore.add("<gray>來源：" + sourceOf(title, shop, theme));
            if (bonus != null) {
                lore.add("<#7fe07f>永久加成：<white>" + bonus + " <dark_gray>（擁有就有）");
            }
            lore.add("");
            lore.add(worn ? "<yellow>左鍵：拿下" : "<yellow>左鍵：戴上");
            final ItemStack icon = icon(Material.NAME_TAG, MeritShop.tag(title), lore);
            return worn ? highlight(icon) : icon;
        }
        lore.add("<dark_gray>還沒有");
        if (bonus != null) {
            lore.add("<gray>拿到就有的永久加成：<white>" + bonus);
        }
        final List<String> howTo = shop ? null : service.sourceHowTo(player, title.id());
        if (howTo != null) {
            lore.addAll(howTo);
        } else if (shop) {
            lore.add("<gray>功勳兌換：" + BountyService.MERIT + BountyService.format(title.price()) + " 功勳</#f5b942><gray>（你有 "
                    + BountyService.format(merits) + "）" + (merits >= title.price() ? " <green>買得起" : ""));
            lore.add("");
            lore.add("<yellow>左鍵：去功勳兌換");
        } else if (theme != null) {
            lore.addAll(eventLines(theme));
            lore.add("");
            lore.add("<yellow>左鍵：打開每月活動");
        } else if (service.bossTitleFor(title.id()) != null) {
            final BountyConfig.BossTitle boss = service.bossTitleFor(title.id());
            lore.add("<gray>擊殺稱號：打倒 <white>" + BountyService.escape(boss.bossName()) + "<gray> " + boss.kills() + " 次");
            lore.add("<gray>目前：<white>" + service.bossKills(player, boss.boss()) + "／" + boss.kills() + "<dark_gray>（整個帳號一起算，倒下時在場就算）");
        } else {
            lore.add("<gray>限定稱號：活動或特殊事件才拿得到");
        }
        return icon(shop ? Material.PAPER : Material.GRAY_DYE, "<gray>【" + BountyService.escape(title.name()) + "】", lore);
    }

    private static String source(final BountyConfig.Title title, final boolean shop, final EventConfig.Theme theme) {
        if (shop) {
            return "功勳兌換（" + BountyService.format(title.price()) + " 功勳）";
        }
        if (theme != null) {
            return "每月活動 " + MonthlyEventService.themeName(theme) + "<gray> 限定";
        }
        return "限定稱號";
    }

    private String sourceOf(final BountyConfig.Title title, final boolean shop, final EventConfig.Theme theme) {
        final String external = shop || theme != null ? null : service.sourceName(title.id());
        if (external != null) {
            return external;
        }
        final BountyConfig.BossTitle boss = shop || theme != null ? null : service.bossTitleFor(title.id());
        return boss != null ? "擊殺稱號（打倒 " + BountyService.escape(boss.bossName()) + " " + boss.kills() + " 次）" : source(title, shop, theme);
    }

    /** 這個限定稱號是哪個每月活動主題的；不是（或活動模組沒開）null。 */
    private EventConfig.Theme eventTheme(final String titleId) {
        final MonthlyEventService events = plugin.events();
        if (events == null) {
            return null;
        }
        for (final EventConfig.Theme theme : events.config().themes()) {
            if (theme.title() != null && theme.titleId().equals(titleId)) {
                return theme;
            }
        }
        return null;
    }

    /** 還沒拿到的限定稱號怎麼拿：哪個月、挑戰做了幾個。 */
    private List<String> eventLines(final EventConfig.Theme theme) {
        final MonthlyEventService events = plugin.events();
        final List<String> lines = new ArrayList<>();
        lines.add("<gray>每月活動 " + MonthlyEventService.themeName(theme) + "<gray> 限定：那個月的挑戰全部完成");
        final EventConfig.Theme current = events.theme();
        if (current != null && current.id().equals(theme.id())) {
            final EventProgress progress = events.progress(player);
            int done = 0;
            if (progress != null) {
                for (final EventConfig.Challenge challenge : theme.challenges()) {
                    if (progress.done(challenge.id())) {
                        done++;
                    }
                }
            }
            lines.add("<green>就是這個月！<gray>剩 <white>" + events.daysLeft() + "</white> 天，挑戰完成 <white>" + done + "／" + theme.challenges().size());
            return lines;
        }
        final YearMonth next = events.config().nextMonthFor(theme, events.month().plusMonths(1));
        if (next == null) {
            lines.add("<dark_gray>目前的輪替排不到這個主題（events.yml 的 settings.force 指定了別的）");
        } else {
            lines.add("<gray>下次輪到：<white>" + next.getYear() + " 年 " + next.getMonthValue() + " 月");
        }
        return lines;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_CLOSE -> {
                goBack();
                return;
            }
            case SLOT_SEARCH -> {
                searchClick(click, "稱號", query, text -> {
                    query = text;
                    page = 0;
                });
                return;
            }
            case SLOT_SHOP -> {
                new MeritShopMenu(plugin, player, service).open();
                return;
            }
            case SLOT_PREV, SLOT_NEXT -> {
                if (pages > 1) {
                    page = Math.floorMod(page + (slot == SLOT_NEXT ? 1 : -1), pages);
                    redraw();
                }
                return;
            }
            case SLOT_TAKE_OFF -> {
                if (service.wornTitle(player) != null) {
                    result(service.wearTitle(player, null));
                }
                return;
            }
            default -> {
            }
        }
        if (!acceptAction(click)) {
            return;
        }
        final BountyConfig.Title title = shown.get(slot);
        if (title == null) {
            return;
        }
        if (title.id().equals(service.wornTitle(player))) {
            result(service.wearTitle(player, null));
        } else if (service.ownedTitles(player).contains(title.id())) {
            result(service.wearTitle(player, title.id()));
        } else if (service.isShopTitle(title.id())) {
            new MeritShopMenu(plugin, player, service).open();
        } else if (plugin.events() != null && eventTheme(title.id()) != null) {
            new EventMenu(plugin, player, plugin.events()).open();
        }
    }

    private void result(final String problem) {
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + BountyService.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        redraw();
    }
}
