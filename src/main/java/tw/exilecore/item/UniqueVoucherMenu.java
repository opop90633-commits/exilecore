package tw.exilecore.item;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 傳奇兌換券（2026-10 PoE2 改版：舊的傳奇全部退役，玩家手上的換成兌換券）：拿在主手右鍵打開，
 * 列出全部現役的傳奇（追逐級的不能換），點一件、再點一次確定，就用掉一張券換成那件傳奇（數值在範圍裡擲）。
 * 兌換券是通貨，可以交易、放通貨交易所。
 */
public final class UniqueVoucherMenu extends Menu {

    private static final int PER_PAGE = CONTENT_END - CONTENT_START;
    /** 上面一排：部位的篩選（null = 全部）。 */
    private static final List<ItemSlot> FILTERS = new ArrayList<>();

    static {
        FILTERS.add(null);
        FILTERS.add(ItemSlot.MAIN_HAND);
        FILTERS.add(ItemSlot.OFF_HAND);
        FILTERS.add(ItemSlot.HEAD);
        FILTERS.add(ItemSlot.CHEST);
        FILTERS.add(ItemSlot.LEGS);
        FILTERS.add(ItemSlot.FEET);
        FILTERS.add(ItemSlot.AMULET);
        FILTERS.add(ItemSlot.RING);
    }

    private final List<UniqueItem> shown = new ArrayList<>();
    private int page;
    /** 搜尋（2026-10）：名稱、基底、詞綴的文字。 */
    private static final int SLOT_SEARCH = 46;
    private String query = "";
    private int filter;
    /** 點過一次、等確定的那件（代號）。 */
    private String pending;

    private UniqueVoucherMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>傳奇兌換券：選一件傳奇");
    }

    /** 主手拿著傳奇兌換券右鍵：打開選單（回傳有沒有處理）。 */
    public static boolean tryOpen(final ExileCore plugin, final Player player, final ItemStack item, final EquipmentSlot hand) {
        if (hand != EquipmentSlot.HAND || !isVoucher(plugin, item)) {
            return false;
        }
        new UniqueVoucherMenu(plugin, player).open();
        return true;
    }

    static boolean isVoucher(final ExileCore plugin, final ItemStack item) {
        if (item == null || item.isEmpty() || plugin.currencies() == null || plugin.items() == null) {
            return false;
        }
        final CurrencyType type = plugin.currencies().read(item).orElse(null);
        return type != null && ItemService.VOUCHER_ID.equals(type.id());
    }

    private List<UniqueItem> candidates() {
        final ItemRepository repository = plugin.items().repository();
        final ItemSlot slot = FILTERS.get(filter);
        final List<UniqueItem> list = new ArrayList<>();
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase base = repository.base(unique.base());
            if (unique.chase() || base == null || slot != null && !matches(base.slot(), slot)) {
                continue;
            }
            if (!query.isBlank()) {
                final List<String> texts = new ArrayList<>(List.of(unique.name(), base.name()));
                for (final String affixId : unique.affixIds()) {
                    final tw.exilecore.item.Affix affix = repository.affix(affixId);
                    if (affix != null) {
                        texts.add(affix.text());
                    }
                }
                if (!Menu.matches(query, texts.toArray(String[]::new))) {
                    continue;
                }
            }
            list.add(unique);
        }
        list.sort(Comparator.<UniqueItem>comparingInt(unique -> repository.base(unique.base()).slot().ordinal())
                .thenComparingInt(UniqueItem::dropLevel).thenComparing(UniqueItem::name));
        return list;
    }

    private static boolean matches(final ItemSlot slot, final ItemSlot filter) {
        if (filter == ItemSlot.RING) {
            return slot.name().startsWith("RING") || slot == ItemSlot.BELT;
        }
        if (filter == ItemSlot.AMULET) {
            return slot == ItemSlot.AMULET || slot == ItemSlot.JEWEL;
        }
        return slot == filter;
    }

    private static String filterName(final ItemSlot slot) {
        if (slot == null) {
            return "全部";
        }
        return switch (slot) {
            case MAIN_HAND -> "武器";
            case OFF_HAND -> "副手（盾、箭袋）";
            case HEAD -> "頭盔";
            case CHEST -> "胸甲";
            case LEGS -> "護腿";
            case FEET -> "鞋子";
            case AMULET -> "項鍊・珠寶";
            case RING -> "戒指・腰帶";
            default -> slot.name();
        };
    }

    private int pageCount(final int size) {
        return Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    protected void draw() {
        frame();
        final List<UniqueItem> all = candidates();
        page = Math.max(0, Math.min(page, pageCount(all.size()) - 1));
        for (int i = 0; i < FILTERS.size(); i++) {
            final ItemStack tab = icon(i == 0 ? Material.NETHER_STAR : Material.PAPER,
                    (i == filter ? "<#f5b942>▶ " : "<white>") + filterName(FILTERS.get(i)),
                    i == filter ? "<green>顯示中" : "<yellow>點一下：只看這個部位");
            set(i, i == filter ? highlight(tab) : tab);
        }
        set(SLOT_SEARCH, searchButton(query, "傳奇"));
        shown.clear();
        for (int i = page * PER_PAGE; i < all.size() && shown.size() < PER_PAGE; i++) {
            shown.add(all.get(i));
        }
        for (int i = 0; i < shown.size(); i++) {
            set(CONTENT_START + i, preview(shown.get(i)));
        }
        final int pages = pageCount(all.size());
        if (pages > 1) {
            set(SLOT_PREV, page > 0 ? pageButton(false, page, pages) : bar());
            set(SLOT_NEXT, page < pages - 1 ? pageButton(true, page, pages) : bar());
        }
        set(SLOT_HELP, helpButton(List.of(
                "舊的傳奇在改版時全部退役，換成了這種兌換券",
                "點一件傳奇、再點一次確定：用掉手上的一張兌換券換成它",
                "數值在範圍裡隨機擲；追逐級的傳奇不能換",
                "物品等級照你的角色等級（低於那件傳奇的最低物品等級就用最低的）",
                "",
                "<dark_gray>兌換券是通貨：可以交易、放通貨交易所")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack preview(final UniqueItem unique) {
        final ItemData data;
        try {
            data = plugin.items().generator().generateUnique(unique, itemLevel(unique), new Random(unique.id().hashCode()));
        } catch (final RuntimeException e) {
            return icon(Material.BARRIER, "<red>" + ItemRenderer.escape(unique.name()), "<gray>做不出預覽");
        }
        data.setIdentified(true);
        final ItemStack stack = plugin.items().renderer().create(data); // 只畫外觀，不寫物品資料
        final boolean confirming = unique.id().equals(pending);
        final ItemStack shownStack = appendLore(stack, "", "<dark_gray>（數值是範例，換到的會在範圍裡重擲）",
                confirming ? "<#f5b942>▶ 再點一次：用一張兌換券換這件" : "<yellow>點一下：選這件");
        return confirming ? highlight(shownStack) : shownStack;
    }

    /** 換到的物品等級：照角色等級；比這件傳奇的最低物品等級低就用最低物品等級。 */
    private int itemLevel(final UniqueItem unique) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final int level = state != null && state.hasCharacter() ? state.level() : 1;
        return Math.max(Math.max(1, unique.dropLevel()), level);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            player.closeInventory();
            return;
        }
        if (slot >= 0 && slot < FILTERS.size()) {
            if (filter != slot) {
                filter = slot;
                page = 0;
                pending = null;
                redraw();
            }
            return;
        }
        if (slot == SLOT_SEARCH) {
            searchClick(click, "傳奇", query, text -> {
                query = text;
                page = 0;
                pending = null;
            });
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_PREV ? -1 : 1;
            pending = null;
            redraw();
            return;
        }
        if (slot < CONTENT_START || slot >= CONTENT_START + shown.size() || !acceptAction(click)) {
            return;
        }
        final UniqueItem unique = shown.get(slot - CONTENT_START);
        if (!unique.id().equals(pending)) {
            pending = unique.id();
            redraw();
            return;
        }
        redeem(unique);
    }

    private void redeem(final UniqueItem unique) {
        final ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isVoucher(plugin, hand)) {
            player.sendMessage(plugin.text().prefixed("<red>手上要拿著傳奇兌換券"));
            player.closeInventory();
            return;
        }
        final ItemService items = plugin.items();
        final ItemData data = items.generator().generateUnique(unique, itemLevel(unique), items.random());
        data.setIdentified(true);
        final ItemStack made = items.toStack(data);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 1 ? null : hand.asQuantity(hand.getAmount() - 1));
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(made);
        for (final ItemStack rest : leftover.values()) {
            plugin.personalDrops().dropForever(player, rest);
            player.sendMessage(plugin.text().prefixed("<yellow>背包滿了，傳奇掉在腳邊"));
        }
        plugin.getSLF4JLogger().info("[傳奇兌換券] {} 換了 {}（{}）", player.getName(), unique.name(), unique.id());
        player.sendMessage(plugin.text().prefixed("<#af6025>傳奇兌換券：換成了 ").append(made.effectiveName()));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.2f);
        pending = null;
        if (isVoucher(plugin, player.getInventory().getItemInMainHand())) {
            redraw();
        } else {
            player.closeInventory();
        }
    }
}
