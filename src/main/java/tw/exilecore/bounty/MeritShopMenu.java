package tw.exilecore.bounty;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.flask.FlaskKind;
import tw.exilecore.gui.Menu;
import tw.exilecore.stash.Stash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 功勳兌換（委託板的「功勳兌換」）：倉庫頁、這個等級最好的藥劑、天穹碎片、稱號。都是便利品，不賣通貨與金幣。
 * 比較貴的（倉庫頁、碎片、稱號）會先問一次。
 */
final class MeritShopMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_STASH = 10;
    private static final int SLOT_CHARACTER_SLOT = 11;
    private static final int SLOT_LIFE = 12;
    private static final int SLOT_MANA = 13;
    private static final int SLOT_FRAGMENT = 14;
    /** 鑰匙類通貨（shop.keys，照順序）：天穹碎片右邊。 */
    private static final int[] KEY_SLOTS = {15, 16, 17};
    private static final int SLOT_TITLES_LABEL = 22;
    private static final int[] TITLE_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_TAKE_OFF = 49;
    private static final int SLOT_CLOSE = 53;

    private final BountyService service;
    private final Map<Integer, BountyConfig.Title> titles = new HashMap<>();

    MeritShopMenu(final ExileCore plugin, final Player player, final BountyService service) {
        super(plugin, player, 6, "<dark_gray>功勳兌換");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        titles.clear();
        final BountyConfig.Shop shop = service.config().shop();
        final long merits = Math.max(0, service.merits(player));
        set(SLOT_HEADER, glowing(icon(Material.EMERALD, BountyService.MERIT + "功勳 " + BountyService.format(merits),
                "<gray>完成每日委託、每週回響拿到的（帳號共用、不能交易）",
                "<dark_gray>這裡都是便利品：不賣通貨、不賣金幣")));

        // 倉庫頁
        final int extra = service.extraStashPages(player);
        final long pagePrice = shop.stashPages().size() > extra ? shop.stashPages().get(extra) : -1;
        final int pages = plugin.stash() == null ? Stash.BASE_PAGES + extra
                : plugin.stash().stash(player).map(Stash::pageCount).orElse(Stash.BASE_PAGES + extra);
        if (!shop.stashPages().isEmpty()) {
            set(SLOT_STASH, pagePrice < 0
                    ? icon(Material.ENDER_CHEST, "<green>✔ 倉庫頁", "<gray>現在 " + pages + " 頁，已經換完了")
                    : affordable(icon(Material.CHEST, BountyService.MERIT + "倉庫頁 +1",
                    "<gray>整個帳號共用的倉庫多一頁（45 格）",
                    "<gray>現在 <white>" + pages + "</white> 頁；還能再換 " + (shop.stashPages().size() - extra) + " 頁",
                    "<gray>價格：" + BountyService.MERIT + BountyService.format(pagePrice) + " 功勳",
                    "<dark_gray>超過 4 頁時，倉庫最下面一列右邊的按鈕換一組頁籤",
                    "",
                    "<yellow>左鍵：兌換（會先問一次）"), merits >= pagePrice));
        }
        // 角色格
        if (!shop.characterSlots().isEmpty()) {
            final int extraSlots = service.extraCharacterSlots(player);
            final long slotPrice = shop.characterSlots().size() > extraSlots ? shop.characterSlots().get(extraSlots) : -1;
            final int slots = plugin.characters().config().characterSlots() + extraSlots;
            set(SLOT_CHARACTER_SLOT, slotPrice < 0
                    ? icon(Material.PLAYER_HEAD, "<green>✔ 角色格", "<gray>現在 " + slots + " 格，已經換完了")
                    : affordable(icon(Material.ARMOR_STAND, BountyService.MERIT + "角色格 +1",
                    "<gray>這個帳號能多建一個角色",
                    "<gray>現在 <white>" + slots + "</white> 格；還能再換 " + (shop.characterSlots().size() - extraSlots) + " 個",
                    "<gray>價格：" + BountyService.MERIT + BountyService.format(slotPrice) + " 功勳",
                    "",
                    "<yellow>左鍵：兌換（會先問一次）"), merits >= slotPrice));
        }
        // 藥劑（沒寫價格 = 不賣）
        if (plugin.flasks() != null && shop.flask() > 0) {
            set(SLOT_LIFE, flask(FlaskKind.LIFE, Material.RED_DYE, "<#ff7777>", shop.flask(), merits));
            set(SLOT_MANA, flask(FlaskKind.MANA, Material.LAPIS_LAZULI, "<#7799ff>", shop.flask(), merits));
        }
        // 天穹碎片
        final CurrencyType fragment = service.fragment();
        if (fragment != null && shop.fragmentWeekly() > 0 && shop.fragment() > 0) {
            final int bought = service.fragmentsThisWeek(player);
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>開終局「" + finalName() + "」用（兩片）");
            lore.add("<gray>沒有每週上限");
            lore.add("<gray>價格：" + BountyService.MERIT + BountyService.format(shop.fragment()) + " 功勳");
            lore.add("<dark_gray>放進倉庫的通貨頁（滿了放通貨袋、背包）");
            lore.add("");
            lore.add("<yellow>左鍵：兌換（會先問一次）");
            final ItemStack icon = icon(Menu.material(fragment.material(), Material.ECHO_SHARD),
                    BountyService.MERIT + BountyService.escape(fragment.name()), lore);
            set(SLOT_FRAGMENT, tw.exilecore.currency.CurrencyService.decorate(
                    affordable(icon, merits >= shop.fragment()), fragment));
        }
        // 鑰匙類通貨（深淵星核、劫盜藍圖、金庫之主的鑰印）
        final List<BountyConfig.ShopKey> keys = service.config().shopKeys();
        for (int i = 0; i < keys.size() && i < KEY_SLOTS.length; i++) {
            final BountyConfig.ShopKey key = keys.get(i);
            final CurrencyType type = plugin.currencies().repository().get(key.currency());
            if (type == null) {
                continue;
            }
            final int bought = service.keysThisWeek(player, key);
            final List<String> lore = new ArrayList<>();
            if (!key.note().isEmpty()) {
                lore.add("<gray>" + BountyService.escape(key.note()));
            }
            lore.add("<gray>沒有每週上限");
            lore.add("<gray>價格：" + BountyService.MERIT + BountyService.format(key.price()) + " 功勳");
            lore.add("<dark_gray>放進倉庫的通貨頁（滿了放通貨袋、背包）");
            lore.add("");
            lore.add("<yellow>左鍵：兌換（會先問一次）");
            final ItemStack icon = icon(Menu.material(type.material(), Material.PAPER), BountyService.MERIT + BountyService.escape(type.name()), lore);
            set(KEY_SLOTS[i], tw.exilecore.currency.CurrencyService.decorate(
                    affordable(icon, merits >= key.price()), type));
        }
        // 稱號
        if (!shop.titles().isEmpty()) {
            set(SLOT_TITLES_LABEL, icon(Material.NAME_TAG, "<white>稱號",
                    "<gray>TAB 列表與聊天時，名字前面的字",
                    "<gray>買過就是整個帳號的，隨時換、隨時拿下"));
            final Set<String> owned = service.ownedTitles(player);
            final String worn = service.wornTitle(player);
            int index = 0;
            final List<BountyConfig.Title> all = new ArrayList<>(shop.titles().values());
            all.addAll(service.ownedExtraTitles(player)); // 每月活動的限定稱號：有了才列出來（只能戴，不能買）
            for (final BountyConfig.Title title : all) {
                if (index >= TITLE_SLOTS.length) {
                    break;
                }
                titles.put(TITLE_SLOTS[index], title);
                set(TITLE_SLOTS[index], title(title, owned.contains(title.id()), title.id().equals(worn), merits));
                index++;
            }
            if (worn != null) {
                set(SLOT_TAKE_OFF, icon(Material.SHEARS, "<white>拿下稱號", "<gray>名字前面不顯示稱號（買過的還在）", "", "<yellow>左鍵：拿下"));
            }
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回委託板"));
        set(SLOT_CLOSE, closeButton());
    }

    /** 買得起：發光；買不起：說明最後加一行紅字。 */
    private static ItemStack affordable(final ItemStack icon, final boolean ok) {
        return ok ? glowing(icon) : appendLore(icon, "<red>功勳不夠");
    }

    private ItemStack flask(final FlaskKind kind, final Material material, final String color, final long price, final long merits) {
        final FlaskBase base = service.bestFlask(player, kind);
        if (base == null) {
            return filler();
        }
        return affordable(icon(material, color + BountyService.escape(base.name()),
                "<gray>這個等級最好的" + (kind == FlaskKind.LIFE ? "生命" : "魔力") + "藥劑一瓶（充能滿的）",
                "<gray>藥劑欄空著就直接放進去",
                "<gray>價格：" + BountyService.MERIT + BountyService.format(price) + " 功勳",
                "",
                "<yellow>左鍵：兌換"), merits >= price);
    }

    private ItemStack title(final BountyConfig.Title title, final boolean owned, final boolean worn, final long merits) {
        final String name = MeritShop.tag(title);
        final String bonusText = MeritShop.bonusText(title);
        // 永久加成：擁有就有（不用戴）
        final String bonus = bonusText == null ? "<dark_gray>沒有加成" : "<#7fe07f>永久加成：<white>" + bonusText + " <dark_gray>（擁有就有，不用戴）";
        if (worn) {
            return highlight(icon(Material.NAME_TAG, name, "<green>戴著", bonus, "", "<yellow>左鍵：拿下"));
        }
        if (owned) {
            return icon(Material.NAME_TAG, name, "<gray>已經有了", bonus, "", "<yellow>左鍵：戴上");
        }
        return affordable(icon(Material.PAPER, name, "<gray>價格：" + BountyService.MERIT + BountyService.format(title.price()) + " 功勳",
                bonus, "", "<yellow>左鍵：兌換（會先問一次，換了就戴上）"), merits >= title.price());
    }

    private String finalName() {
        if (plugin.maps() == null || plugin.maps().config().finalMap() == null) {
            return "終局";
        }
        return BountyService.escape(plugin.maps().config().finalMap().name());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            service.open(player);
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        final BountyConfig.Shop shop = service.config().shop();
        switch (slot) {
            case SLOT_STASH -> {
                final int extra = service.extraStashPages(player);
                if (extra < shop.stashPages().size()) {
                    confirm("倉庫頁 +1", shop.stashPages().get(extra), () -> service.buyStashPage(player));
                }
            }
            case SLOT_CHARACTER_SLOT -> {
                final int extra = service.extraCharacterSlots(player);
                if (extra < shop.characterSlots().size()) {
                    confirm("角色格 +1", shop.characterSlots().get(extra), () -> service.buyCharacterSlot(player));
                }
            }
            case SLOT_LIFE -> {
                if (shop.flask() > 0) {
                    result(service.buyFlask(player, FlaskKind.LIFE));
                }
            }
            case SLOT_MANA -> {
                if (shop.flask() > 0) {
                    result(service.buyFlask(player, FlaskKind.MANA));
                }
            }
            case SLOT_FRAGMENT -> {
                final CurrencyType fragment = service.fragment();
                if (fragment == null || shop.fragmentWeekly() <= 0 || shop.fragment() <= 0) {
                    return;
                }
                // 2026-10：沒有每週上限
                confirm(BountyService.escape(fragment.name()) + " ×1", shop.fragment(), () -> service.buyFragment(player));
            }
            case 15, 16, 17 -> {
                final int index = slot - KEY_SLOTS[0];
                final List<BountyConfig.ShopKey> keys = service.config().shopKeys();
                if (index < 0 || index >= keys.size()) {
                    return;
                }
                final BountyConfig.ShopKey key = keys.get(index);
                final CurrencyType type = plugin.currencies().repository().get(key.currency());
                if (type == null) {
                    return;
                }
                // 2026-10：沒有每週上限
                confirm(BountyService.escape(type.name()) + " ×1", key.price(), () -> service.buyKey(player, key));
            }
            case SLOT_TAKE_OFF -> {
                if (service.wornTitle(player) != null) {
                    result(service.wearTitle(player, null));
                }
            }
            default -> {
                final BountyConfig.Title title = titles.get(slot);
                if (title == null) {
                    return;
                }
                if (title.id().equals(service.wornTitle(player))) {
                    result(service.wearTitle(player, null));
                } else if (service.ownedTitles(player).contains(title.id())) {
                    result(service.wearTitle(player, title.id()));
                } else {
                    confirm("稱號 " + MeritShop.tag(title), title.price(), () -> service.buyTitle(player, title.id()));
                }
            }
        }
    }

    /** 先問一次（對話視窗），按了再換；之後回到這個畫面。 */
    private void confirm(final String what, final long price, final Supplier<String> purchase) {
        final long merits = Math.max(0, service.merits(player));
        if (merits < price) {
            result("功勳不夠：要 " + BountyService.format(price) + "，你有 " + BountyService.format(merits));
            return;
        }
        plugin.dialogs().confirm(player, BountyService.MERIT + "功勳兌換",
                List.of("<gray>用 " + BountyService.MERIT + BountyService.format(price) + " 功勳</#f5b942><gray> 換 <white>" + what + "<gray>？",
                        "<dark_gray>換了不能退。剩下 " + BountyService.format(merits - price) + " 功勳"),
                "換", () -> {
                    final String problem = purchase.get();
                    if (problem != null) {
                        fail(problem);
                    } else {
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.1f);
                    }
                    new MeritShopMenu(plugin, player, service).open();
                }, () -> new MeritShopMenu(plugin, player, service).open());
    }

    private void result(final String problem) {
        if (problem != null) {
            fail(problem);
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        redraw();
    }

    private void fail(final String problem) {
        player.sendMessage(plugin.text().prefixed("<red>" + BountyService.escape(problem)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }
}
