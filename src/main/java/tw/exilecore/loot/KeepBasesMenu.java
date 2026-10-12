package tw.exilecore.loot;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 掉落過濾：只撿的基底（2026-10）。點一個基底加入或拿掉，可以一直加；有挑的話普通、魔法、稀有裝備只撿這些基底，
 * 稀有度照過濾器「依稀有度」的設定（傳奇、珠寶、地圖不受影響）。每個角色各自設定，關閉時存檔。
 */
final class KeepBasesMenu extends Menu {

    private enum Category {
        ALL("全部", Material.CHEST),
        KEPT("已保留", Material.EMERALD),
        WEAPON("武器", Material.IRON_SWORD),
        ARMOUR("防具", Material.IRON_CHESTPLATE),
        OFFHAND("盾、箭袋", Material.SHIELD),
        JEWELLERY("飾品", Material.GOLD_NUGGET);

        private final String title;
        private final Material icon;

        Category(final String title, final Material icon) {
            this.title = title;
            this.icon = icon;
        }

        boolean matches(final ItemBase base) {
            return switch (this) {
                case ALL, KEPT -> true;
                case WEAPON -> base.isWeapon();
                case ARMOUR -> base.isArmour() && !base.isShield();
                case OFFHAND -> base.isShield() || base.isQuiver();
                case JEWELLERY -> base.isJewellery();
            };
        }
    }

    private static final int PER_PAGE = 36;
    private static final int SLOT_SEARCH = 7;
    private static final int SLOT_INFO = 8;
    private static final int SLOT_PREV = 47;
    private static final int SLOT_CLEAR = 49;
    private static final int SLOT_NEXT = 51;

    private final LootService service;
    private final Runnable back;
    private final long characterId;
    private final Map<Integer, ItemBase> shown = new HashMap<>();
    private LootFilter filter;
    private Category category = Category.ALL;
    private String query = "";
    private int page;
    private boolean changed;

    KeepBasesMenu(final ExileCore plugin, final Player player, final LootService service, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>掉落過濾：只撿的基底");
        this.service = service;
        this.back = back;
        this.filter = service.filter(player);
        final var character = plugin.players().get(player).character();
        this.characterId = character == null ? -1 : character.id();
    }

    private boolean stale() {
        final var character = plugin.players().get(player).character();
        return character == null || character.id() != characterId;
    }

    @Override
    protected void onClose() {
        if (changed && !stale()) {
            plugin.characters().save(player);
            changed = false;
        }
    }

    /** 可以選的基底：能穿戴的裝備（地圖、珠寶本來就會撿，不列）。 */
    private List<ItemBase> bases() {
        final List<ItemBase> list = new ArrayList<>();
        final String needle = query.toLowerCase(Locale.ROOT);
        for (final ItemBase base : plugin.items().repository().bases()) {
            if (base.isMap() || base.isJewel() || !base.isWearable() || !category.matches(base)) {
                continue;
            }
            if (category == Category.KEPT && !filter.keepsBase(base.id())) {
                continue;
            }
            if (!needle.isEmpty() && !base.name().toLowerCase(Locale.ROOT).contains(needle) && !base.id().contains(needle)) {
                continue;
            }
            list.add(base);
        }
        // 2026-10：跟商人賭博一樣的排序（VendorService.bases）：欄位 → 同一類（照資料檔的順序）排在一起 →
        // 沒有固定詞綴的在前 → 傷害種類少的在前 → 暴擊、攻速 → 防禦
        final java.util.Map<String, Integer> familyOrder = new java.util.HashMap<>();
        for (final ItemBase base : plugin.items().repository().bases()) {
            familyOrder.putIfAbsent(base.family(), familyOrder.size());
        }
        list.sort(Comparator.comparing((ItemBase base) -> base.slot().ordinal())
                .thenComparingInt(base -> familyOrder.getOrDefault(base.family(), Integer.MAX_VALUE))
                .thenComparing(base -> base.implicit() != null)
                .thenComparingInt(base -> base.damage().size())
                .thenComparingDouble(ItemBase::crit).thenComparingDouble(ItemBase::aps)
                .thenComparingDouble(base -> base.armour() + base.evasion() + base.energyShield())
                .thenComparing(ItemBase::id));
        return list;
    }

    @Override
    protected void draw() {
        frame();
        shown.clear();
        final Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            final Category each = categories[i];
            final String count = each == Category.KEPT ? "（" + filter.keepBases().size() + "）" : "";
            set(i, tab(each.icon, each.title + count, each == category, List.of("<gray>只看" + each.title)));
        }
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<white>搜尋基底",
                query.isEmpty() ? "<gray>輸入名稱的一部分" : "<gray>現在：<white>" + ItemRenderer.escape(query),
                "", "<yellow>左鍵：輸入", query.isEmpty() ? "" : "<yellow>右鍵：清掉搜尋"));
        set(SLOT_INFO, icon(Material.BOOK, "<white>只撿的基底",
                "<gray>有挑的話，普通、魔法、稀有裝備只撿這些基底",
                "<gray>撿哪幾種稀有度照過濾器「依稀有度」那三格",
                "<gray>可以一直加；每個角色各自設定；全部拿掉就恢復原本的規則",
                "<dark_gray>傳奇、珠寶、地圖不受影響；有好詞綴（保留詞綴階級）的照樣撿",
                "",
                "<gray>現在保留了 <white>" + filter.keepBases().size() + "<gray> 種"));
        final List<ItemBase> list = bases();
        final int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int slot = 9;
        for (final ItemBase base : list.subList(page * PER_PAGE, Math.min(list.size(), (page + 1) * PER_PAGE))) {
            shown.put(slot, base);
            set(slot, entry(base));
            slot++;
        }
        if (list.isEmpty()) {
            set(22, icon(Material.GRAY_DYE, "<gray>沒有符合的基底",
                    category == Category.KEPT ? "<dark_gray>還沒有保留任何基底：到其他分頁點一下加入" : "<dark_gray>換個分頁或搜尋看看"));
        }
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_BACK, backButton("掉落過濾"));
        if (!filter.keepBases().isEmpty()) {
            set(SLOT_CLEAR, icon(Material.LAVA_BUCKET, "<red>全部清掉", "<gray>拿掉所有保留的基底（" + filter.keepBases().size() + " 種）",
                    "", "<yellow>Shift＋左鍵：全部清掉"));
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack entry(final ItemBase base) {
        final boolean kept = filter.keepsBase(base.id());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + base.slot().displayName() + "・掉落等級 " + base.dropLevel());
        lore.add(kept ? "<green>✔ 會撿（稀有度照過濾器）" : filter.keepBases().isEmpty() ? "<dark_gray>照一般的過濾規則" : "<dark_gray>不撿");
        lore.add("");
        lore.add(kept ? "<yellow>左鍵：拿掉" : "<yellow>左鍵：加入");
        final ItemStack stack = icon(Menu.material(base.iconMaterial(), Material.PAPER),
                (kept ? "<green>✔ " : "<white>") + ItemRenderer.escape(base.name()), lore);
        return kept ? highlight(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            onClose();
            back.run();
            return;
        }
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (slot >= 0 && slot < Category.values().length) {
            category = Category.values()[slot];
            page = 0;
            redraw();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_NEXT ? 1 : -1;
            final int pages = Math.max(1, (bases().size() + PER_PAGE - 1) / PER_PAGE);
            page = Math.floorMod(page, pages);
            redraw();
            return;
        }
        if (slot == SLOT_SEARCH) {
            if (click.isRightClick()) {
                query = "";
                page = 0;
                redraw();
                return;
            }
            plugin.dialogs().form(player, "<dark_gray>搜尋基底", List.of("<gray>輸入基底名稱的一部分（例如：劍、弓、戒指）"),
                    List.of(new tw.exilecore.gui.Dialogs.Text("q", "名稱", query, 20)), "搜尋", answers -> {
                        query = answers.get("q").trim();
                        page = 0;
                        open();
                    }, this::open);
            return;
        }
        if (stale()) {
            return;
        }
        if (slot == SLOT_CLEAR) {
            if (click != ClickType.SHIFT_LEFT || filter.keepBases().isEmpty()) {
                return;
            }
            final LootFilter next = filter.copy();
            next.clearKeepBases();
            apply(next);
            return;
        }
        final ItemBase base = shown.get(slot);
        if (base == null || !click.isLeftClick()) {
            return;
        }
        final LootFilter next = filter.copy();
        next.toggleKeepBase(base.id());
        apply(next);
    }

    private void apply(final LootFilter next) {
        filter = next;
        service.setFilter(player, next);
        changed = true;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        redraw();
    }
}
