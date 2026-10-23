package tw.exilecore.loot;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 隨身背包（6 列）。2026-10 改成統一版面：最下面一排固定是「← 回選單、物品第 1 頁、物品第 2 頁、通貨袋、說明、
 * 整理（通貨袋：存入背包裡的通貨）、全部放進來、掉落過濾、關閉」，每個頁籤一直看得到、不用輪著點。
 * <ul>
 *   <li>物品頁：上面 45 格跟箱子一樣拖拉。</li>
 *   <li>通貨袋：最上面一排是四個分組的頁籤（一般通貨、未切割寶石、星魄、催化劑・星籤・蒸餾情緒），下面四排是那一組的通貨；
 *       點通貨拿到游標上（可以直接點背包裡的裝備使用），拿著通貨點這裡放回去，點背包裡的通貨存進來。</li>
 * </ul>
 */
public final class BagMenu extends Menu {

    static final int CURRENCY_PAGE = Bag.MAX_PAGES;
    private static final int PAGE_SLOTS = Bag.PAGE_SIZE;
    private static final int NAV_START = 45;
    /** 跟倉庫同一個版面（2026-10）：◀ 上一頁、這一頁的頁籤、下一頁 ▶、通貨袋。 */
    private static final int SLOT_PREVIOUS_PAGE = 45;
    /** 這一頁的頁籤（通貨袋上是「← 回物品頁」）。 */
    private static final int SLOT_PAGE_TAB = 46;
    private static final int SLOT_NEXT_PAGE = 47;
    private static final int SLOT_CURRENCY = 48;
    /** 物品頁：整理；通貨袋：存入背包裡所有通貨。 */
    private static final int SLOT_TOOL = 50;
    /** 物品頁：主背包全部放進來。 */
    private static final int SLOT_STOW = 51;
    /** 搜尋與過濾（2026-10）：左鍵輸入關鍵字、右鍵換種類、Shift＋左鍵換稀有度、Shift＋右鍵清掉。 */
    private static final int SLOT_FILTER = 52;
    /** 種類過濾可以選的（2026-10 分細：全部、武器、防具與各部位、飾品與各部位、珠寶、護符、寶石、藥劑、地圖、其他）。 */
    private static final List<tw.exilecore.stash.StashCategory> CATEGORIES = List.of(
            tw.exilecore.stash.StashCategory.ALL, tw.exilecore.stash.StashCategory.WEAPON, tw.exilecore.stash.StashCategory.ARMOUR,
            tw.exilecore.stash.StashCategory.OFF_HAND, tw.exilecore.stash.StashCategory.HELMET, tw.exilecore.stash.StashCategory.BODY,
            tw.exilecore.stash.StashCategory.LEGS, tw.exilecore.stash.StashCategory.BOOTS, tw.exilecore.stash.StashCategory.JEWELLERY,
            tw.exilecore.stash.StashCategory.RING, tw.exilecore.stash.StashCategory.AMULET, tw.exilecore.stash.StashCategory.BELT,
            tw.exilecore.stash.StashCategory.JEWEL, tw.exilecore.stash.StashCategory.CHARM, tw.exilecore.stash.StashCategory.GEM,
            tw.exilecore.stash.StashCategory.FLASK, tw.exilecore.stash.StashCategory.MAP, tw.exilecore.stash.StashCategory.OTHER);

    /** 搜尋與過濾的條件（重新打開視窗時帶著）。rarity null = 全部稀有度。 */
    record Filter(String query, tw.exilecore.stash.StashCategory category, tw.exilecore.item.Rarity rarity) {
        static final Filter NONE = new Filter("", tw.exilecore.stash.StashCategory.ALL, null);

        boolean active() {
            return !query.isBlank() || category != tw.exilecore.stash.StashCategory.ALL || rarity != null;
        }

        String describe() {
            final List<String> parts = new ArrayList<>();
            if (!query.isBlank()) {
                parts.add("「" + query + "」");
            }
            if (category != tw.exilecore.stash.StashCategory.ALL) {
                parts.add(category.displayName());
            }
            if (rarity != null) {
                parts.add(rarity.displayName());
            }
            return String.join("・", parts);
        }
    }
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;
    /** 通貨袋：最上面一排是分組的頁籤，下面四排是通貨。 */
    private static final int CURRENCY_START = CONTENT_START;
    private static final int CURRENCY_SLOTS = tw.exilecore.currency.CurrencyGroups.PAGE_CAPACITY; // 四排（CONTENT_START 到 CONTENT_END）
    private static final int INVENTORY_MAIN_START = 9;
    private static final int INVENTORY_END = 36;

    private final LootService service;
    private final Bag bag;
    private final List<CurrencyType> currencyOrder = new ArrayList<>();
    private final int[] groupTabs = tabSlots(tw.exilecore.currency.CurrencyGroups.Group.values().length);
    private int page;
    /** 最後看的物品頁（從通貨袋回去用）。 */
    private int lastItemPage;
    /** 通貨袋看哪一組（最上面一排的頁籤；一打開先看有東西的那組）。 */
    private tw.exilecore.currency.CurrencyGroups.Group currencyGroup;
    /** 畫面真的打開了（沒打開就不能把畫面同步回背包，不然會把空白寫回去）。 */
    private boolean active;
    /** 搜尋與過濾（有條件時只能看不能搬，跟倉庫的搜尋一樣）。 */
    private Filter filter = Filter.NONE;
    private List<String> terms = List.of();
    private tw.exilecore.stash.StashOrganizer organizer;

    BagMenu(final ExileCore plugin, final Player player, final LootService service, final Bag bag) {
        this(plugin, player, service, bag, 0, (tw.exilecore.currency.CurrencyGroups.Group) null);
    }

    /** 打開時先看哪一頁（通貨袋 {@link #CURRENCY_PAGE}）、通貨袋看哪一組（null = 有東西的那組）。 */
    BagMenu(final ExileCore plugin, final Player player, final LootService service, final Bag bag, final int startPage,
            final tw.exilecore.currency.CurrencyGroups.Group group) {
        super(plugin, player, 6, "<dark_gray>隨身背包");
        this.service = service;
        this.bag = bag;
        this.page = Math.max(0, Math.min(CURRENCY_PAGE, startPage));
        this.currencyGroup = group;
    }

    /** 帶著搜尋與過濾打開（跳到第一個有符合的頁）。 */
    BagMenu(final ExileCore plugin, final Player player, final LootService service, final Bag bag, final int startPage, final Filter filter) {
        this(plugin, player, service, bag, startPage, (tw.exilecore.currency.CurrencyGroups.Group) null);
        setFilter(filter == null ? Filter.NONE : filter);
        if (this.filter.active() && page < CURRENCY_PAGE && matchesOn(page) == 0) {
            for (int p = 0; p < bag.viewPages(); p++) {
                if (matchesOn(p) > 0) {
                    this.page = p;
                    break;
                }
            }
        }
        this.lastItemPage = page < CURRENCY_PAGE ? page : 0;
    }

    private void setFilter(final Filter value) {
        filter = value;
        terms = value.query().isBlank() ? List.of() : tw.exilecore.market.MarketSearch.terms(value.query());
    }

    private boolean filtering() {
        return filter.active();
    }

    private boolean matches(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!terms.isEmpty() && !tw.exilecore.market.MarketSearch.matches(tw.exilecore.item.ItemSearch.text(plugin.items(), stack), terms)) {
            return false;
        }
        if (filter.category() != tw.exilecore.stash.StashCategory.ALL) {
            if (organizer == null) {
                organizer = new tw.exilecore.stash.StashOrganizer(plugin);
            }
            if (!filter.category().accepts(organizer.category(stack))) {
                return false;
            }
        }
        if (filter.rarity() != null) {
            final Optional<tw.exilecore.item.ItemData> data = plugin.items().read(stack);
            return data.isPresent() && data.get().rarity() == filter.rarity();
        }
        return true;
    }

    /** 這一頁有幾件符合。 */
    private int matchesOn(final int index) {
        int count = 0;
        for (final ItemStack stack : bag.page(index)) {
            if (matches(stack)) {
                count++;
            }
        }
        return count;
    }

    private int totalMatches() {
        int total = 0;
        for (int p = 0; p < bag.viewPages(); p++) {
            total += matchesOn(p);
        }
        return total;
    }

    @Override
    protected boolean allowsItemMovement() {
        return true;
    }

    @Override
    protected void onOpen() {
        if (stale()) {
            // 等著打開的這一個 tick 裡換了角色：這不是現在這個角色的背包，不要用
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                    player.closeInventory();
                }
            });
            return;
        }
        active = true;
        service.opened(player, this);
    }

    @Override
    protected void onClose() {
        if (!active) {
            return;
        }
        returnCursor();
        syncToModel();
        active = false;
        service.closed(player, this);
        plugin.players().markDirty(player); // 游標上的武器可能放回了手上那一格
        plugin.characters().save(player);
    }

    /** 這個視窗拿的不是玩家現在的隨身背包（換了角色）。 */
    private boolean stale() {
        return plugin.players().get(player).bag() != bag;
    }

    /** 模組停用（關伺服器）：收不到關閉事件，先把游標上的東西放回去、畫面同步回背包。 */
    void shutdown() {
        if (!active || stale()) {
            return;
        }
        returnCursor();
        syncToModel();
    }

    /** 畫面上是哪一個物品頁（通貨袋回傳 -1）。自動撿取不會放進這一頁。 */
    int displayedItemPage() {
        return active && page < CURRENCY_PAGE ? page : -1;
    }

    /** 自動撿到通貨時：開著通貨袋就重畫數量。 */
    void refreshCurrency() {
        if (active && page == CURRENCY_PAGE) {
            redraw();
        }
    }

    /**
     * 關閉時游標上還拿著東西：通貨放回通貨袋，其他放回背包，背包滿了放進隨身背包，都滿了交給原版（丟在腳下）。
     * 原版在「斷線關閉」時會把游標上的東西丟在地上，放回去才不會留在登出的地方被別人撿走。
     */
    private void returnCursor() {
        final ItemStack cursor = player.getItemOnCursor();
        if (cursor.isEmpty()) {
            return;
        }
        ItemStack rest = cursor.clone();
        final Optional<CurrencyType> type = plugin.currencies().read(rest);
        if (type.isPresent()) {
            final int accepted = bag.pouch().deposit(type.get().id(), rest.getAmount());
            rest = accepted >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - accepted);
        }
        if (rest != null) {
            ItemStack left = null;
            for (final ItemStack leftover : player.getInventory().addItem(rest).values()) {
                left = leftover;
            }
            rest = left;
        }
        if (rest != null && page < CURRENCY_PAGE && !filtering()) {
            final Inventory inventory = getInventory();
            for (int i = 0; i < PAGE_SLOTS && rest != null; i++) {
                final ItemStack item = inventory.getItem(i);
                if (item == null || item.isEmpty()) {
                    inventory.setItem(i, rest);
                    rest = null;
                }
            }
        }
        if (rest != null) {
            syncToModel();
            for (int p = 0; p < bag.pages() && rest != null; p++) {
                if (p != page) {
                    rest = LootService.fillEmpty(bag.page(p), rest);
                }
            }
        }
        player.setItemOnCursor(rest);
    }

    /** 把畫面上這一頁的內容寫回背包（換頁、關閉、存檔前）。 */
    void syncToModel() {
        if (!active || page >= CURRENCY_PAGE || filtering()) { // 過濾中畫面上不是真的東西
            return;
        }
        final Inventory inventory = getInventory();
        final ItemStack[] target = bag.page(page);
        for (int i = 0; i < PAGE_SLOTS; i++) {
            final ItemStack item = inventory.getItem(i);
            target[i] = item == null || item.isEmpty() ? null : item.clone();
        }
    }

    // ---- 畫面 ----

    @Override
    protected void draw() {
        if (page < CURRENCY_PAGE) {
            final ItemStack[] items = bag.page(page);
            for (int i = 0; i < PAGE_SLOTS; i++) {
                if (items[i] == null) {
                    set(i, null);
                } else if (!filtering()) {
                    set(i, items[i].clone());
                } else if (matches(items[i])) {
                    set(i, highlight(appendLore(items[i].clone(), "", "<#f5b942>✔ 符合", "<yellow>左鍵：結束過濾，停在這一頁")));
                } else {
                    set(i, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>（不符合）"));
                }
            }
        } else {
            drawCurrency();
        }
        drawNav();
    }

    private void drawNav() {
        for (int slot = NAV_START; slot < NAV_START + 9; slot++) {
            set(slot, bar());
        }
        final boolean currencyPage = page == CURRENCY_PAGE;
        if (currencyPage) {
            set(SLOT_PAGE_TAB, icon(Material.CHEST, "<white>← 回物品頁", "<gray>回到物品 第 " + (lastItemPage + 1) + " 頁", "",
                    "<yellow>左鍵：回去"));
        } else {
            set(SLOT_PREVIOUS_PAGE, bag.viewPages() > 1 ? jumpButton(false, page, bag.viewPages()) : bar());
            set(SLOT_NEXT_PAGE, bag.viewPages() > 1 ? jumpButton(true, page, bag.viewPages()) : bar());
            set(SLOT_PAGE_TAB, highlight(icon(Material.ENDER_CHEST, "<#f5b942>▶ 物品 第 " + (page + 1) + "／" + bag.viewPages() + " 頁",
                    filtering() ? "<gray>這一頁 <white>" + matchesOn(page) + "</white> 件符合（全部 " + totalMatches() + " 件）"
                            : "<gray>" + bag.itemCount(page) + " / " + PAGE_SLOTS + " 格有東西", "",
                    bag.viewPages() > 1 ? "<yellow>左鍵：全部頁一覽" : "<dark_gray>操作說明在下面的「？」")));
        }
        set(SLOT_CURRENCY, tab(Material.SUNFLOWER, "通貨袋", currencyPage,
                List.of("<gray>裝了 " + bag.pouch().kinds() + " 種通貨（每種最多 " + bag.pouch().cap() + " 個，不佔格子）",
                        "<gray>殺怪撿到的通貨、未切割寶石會自動收進來")));
        set(SLOT_HELP, helpButton(List.of(
                "<#f5b942>金幣 " + service.goldText(player),
                "",
                "每個角色自己的：" + bag.pages() + " 頁共 " + (bag.pages() * PAGE_SLOTS) + " 格，死掉也不會掉",
                "殺怪撿到的東西：背包滿了先放這裡",
                "物品頁：跟箱子一樣拖拉、Shift 點擊；右鍵一格直接拿到主背包",
                "◀ ▶ 換頁；拿著東西點 ◀ ▶ 可以帶著換頁",
                "搜尋・過濾：關鍵字、種類、稀有度（過濾中只能看，點符合的那件結束）",
                "通貨袋：最上面一排換組；點通貨拿到游標上，直接點裝備就能用",
                "　拿著通貨點通貨袋放回去，點背包裡的通貨存進來",
                "　未切割寶石：Shift＋右鍵直接雕琢、切割",
                "Shift＋F 或 /menu 打開選單，/bag 直接打開這裡",
                "<dark_gray>關閉時自動存檔")));
        if (currencyPage) {
            set(SLOT_TOOL, icon(Material.HOPPER, "<#7fd4ff>存入背包裡所有通貨",
                    "<gray>背包與快捷列裡的通貨、未切割寶石全部收進通貨袋", "", "<yellow>左鍵：存入"));
        } else {
            set(SLOT_TOOL, icon(Material.COMPARATOR, "<#7fd4ff>整理",
                    "<gray>疊在一起，照種類、稀有度排好", "", "<yellow>左鍵：整理", "<yellow>右鍵：掉落過濾（哪些東西不撿，/filter）"));
            set(SLOT_STOW, icon(Material.HOPPER, "<#7fd4ff>主背包全部放進來",
                    "<gray>背包上面三排（通貨進通貨袋）", "", "<yellow>左鍵：放進來",
                    "<yellow>右鍵：這一頁全部拿到主背包"));
        }
        if (currencyPage) {
            set(SLOT_FILTER, icon(Material.SPYGLASS, "<white>掉落過濾", "<gray>設定哪些東西不撿、直接換成金幣",
                    "", "<yellow>左鍵：打開（/filter）"));
        } else {
            final List<String> lore = new ArrayList<>();
            lore.add(filtering() ? "<gray>目前：<white>" + ItemRenderer.escape(filter.describe()) + "</white>（" + totalMatches() + " 件）"
                    : "<gray>名稱、詞綴、基底都找得到；空白隔開＝都要有");
            lore.add("<gray>種類：<white>" + filter.category().displayName() + "</white>　稀有度：<white>"
                    + (filter.rarity() == null ? "全部" : filter.rarity().displayName()));
            lore.add("<dark_gray>過濾中只能看：符合的發光、不符合的變灰");
            lore.add("");
            lore.add("<yellow>左鍵：輸入關鍵字");
            lore.add("<yellow>右鍵：換種類　Shift＋左鍵：換稀有度");
            if (filtering()) {
                lore.add("<yellow>Shift＋右鍵：結束過濾");
            }
            final ItemStack button = icon(Material.SPYGLASS, "<#f5b942>搜尋・過濾", lore);
            set(SLOT_FILTER, filtering() ? highlight(button) : button);
        }
        set(SLOT_CLOSE, closeButton()); // 最右邊是「← 返回選單」（原本最左邊的返回讓給 ◀ 上一頁）
    }

    private void drawCurrency() {
        currencyOrder.clear();
        final java.util.Collection<CurrencyType> all = plugin.currencies().repository().all();
        if (currencyGroup == null) {
            currencyGroup = tw.exilecore.currency.CurrencyGroups.first(all, type -> bag.pouch().amount(type.id()));
        }
        // 最上面一排：分組的頁籤
        for (int i = 0; i < 9; i++) {
            set(i, bar());
        }
        final int[] held = tw.exilecore.currency.CurrencyGroups.kindsHeld(all, type -> bag.pouch().amount(type.id()));
        final tw.exilecore.currency.CurrencyGroups.Group[] groups = tw.exilecore.currency.CurrencyGroups.Group.values();
        for (int g = 0; g < groups.length; g++) {
            final List<CurrencyType> members = tw.exilecore.currency.CurrencyGroups.in(all, groups[g]);
            final Material material = members.isEmpty() ? Material.SUNFLOWER : Menu.material(members.getFirst().material(), Material.SUNFLOWER);
            set(groupTabs[g], tab(material, groups[g].displayName(), groups[g] == currencyGroup,
                    List.of("<gray>" + groups[g].about(), "<gray>有 <white>" + held[g] + "</white> / " + members.size() + " 種")));
        }
        currencyOrder.addAll(tw.exilecore.currency.CurrencyGroups.in(all, currencyGroup));
        for (int i = 0; i < CURRENCY_SLOTS; i++) {
            final int slot = CURRENCY_START + i;
            if (i >= currencyOrder.size()) {
                set(slot, null);
                continue;
            }
            final CurrencyType type = currencyOrder.get(i);
            final int amount = bag.pouch().amount(type.id());
            final boolean gem = type.uncutGem() && plugin.skills() != null;
            final List<String> lore = new ArrayList<>();
            lore.add(String.format(Locale.ROOT, "<gray>有 <white>%,d</white> 個", amount));
            lore.add("<dark_gray>" + ItemRenderer.escape(type.description()));
            lore.add("");
            if (amount > 0) {
                if (gem) {
                    lore.add("<yellow>Shift＋右鍵：直接" + (type.id().startsWith(CurrencyType.UNCUT_SKILL_PREFIX) ? "雕琢、升級技能" : "切輔助寶石"));
                    lore.add("<yellow>左鍵：拿一疊　右鍵：拿 1 個　Shift＋左鍵：放進背包");
                } else if (plugin.cards() != null && plugin.cards().goesToStash(type)) {
                    // 2026-10-19 晚：星諭卡、牌堆在通貨袋裡也能直接用
                    final tw.exilecore.card.CardConfig.Card card = plugin.cards().config().card(type.id());
                    if (card != null) {
                        lore.add("<gray>一套 <white>" + card.set() + "</white> 張・可以換 <white>" + amount / card.set() + "</white> 套");
                    }
                    lore.add("<#c9a0ff>Shift＋右鍵：" + (tw.exilecore.card.CardService.isDeck(type) ? "全部打開" : "全部兌換"));
                    lore.add("<yellow>左鍵：拿一疊　右鍵：拿 1 個　Shift＋左鍵：放進背包");
                    lore.add("<dark_gray>賭卡要先存進倉庫");
                } else {
                    lore.add("<yellow>左鍵：拿一疊到游標上（直接點裝備使用）");
                    lore.add("<yellow>右鍵：拿 1 個　Shift＋左鍵：放進背包");
                }
            } else {
                lore.add("<dark_gray>點背包裡的" + ItemRenderer.escape(type.name()) + "存進來");
            }
            lore.add("<dark_gray>拿著通貨點這裡：放回通貨袋");
            // 跟背包裡真的那顆長得一樣（通貨的貼圖、寶石的模型），但沒有通貨的標記
            final ItemStack icon = plugin.currencies().icon(type, 1);
            icon.editMeta(meta -> {
                meta.displayName(ItemRenderer.line((amount > 0 ? "<#f5b942>" : "<dark_gray>") + ItemRenderer.escape(type.name())));
                final List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
                for (final String line : lore) {
                    lines.add(ItemRenderer.line(line));
                }
                meta.lore(lines);
                meta.setEnchantmentGlintOverride(amount > 0);
            });
            set(slot, withCount(icon, Math.min(99, Math.max(1, amount))));
        }
    }

    // ---- 點擊 ----

    @Override
    protected void handleRawClick(final InventoryClickEvent event) {
        if (!active || stale()) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }
        final int raw = event.getRawSlot();
        if (raw >= NAV_START && raw < NAV_START + 9) {
            event.setCancelled(true);
            onNav(raw, event.getClick());
            return;
        }
        if (page == CURRENCY_PAGE) {
            onCurrencyPageClick(event, raw);
            return;
        }
        if (filtering()) {
            // 過濾中只能看：點上面的格子、Shift 移動、收集到游標都不行；點一件符合的 = 結束過濾停在這一頁
            if (raw >= 0 && raw < PAGE_SLOTS) {
                event.setCancelled(true);
                final ItemStack real = bag.page(page)[raw];
                if (event.getCursor().isEmpty() && matches(real) && acceptAction(event.getClick())) {
                    clearFilter();
                }
                return;
            }
            if (event.getClick().isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            return;
        }
        if (bag.locked(page) && !takesOutOnly(event, raw)) {
            // 收回的頁（2026-10）：只能拿出來
            event.setCancelled(true);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>這一頁已經收回：只能把東西拿出來"));
            return;
        }
        if (raw >= 0 && raw < PAGE_SLOTS && event.getClick() == ClickType.RIGHT && event.getCursor().isEmpty()) {
            // 物品頁右鍵一格：整格拿到主背包（原版的右鍵是拿一半，裝備拿起來而已）
            final ItemStack current = event.getCurrentItem();
            if (current != null && !current.isEmpty()) {
                event.setCancelled(true);
                takeOut(raw);
                return;
            }
        }
        // 物品頁：這一頁的 45 格、自己的背包照原版操作（拿著通貨點裝備照樣可以使用）
    }

    /** 收回的頁上，這一下只是把東西拿出來（拿到游標、Shift 移到背包），沒有放東西進這一頁。 */
    private static boolean takesOutOnly(final InventoryClickEvent event, final int raw) {
        final org.bukkit.event.inventory.InventoryAction action = event.getAction();
        if (raw >= PAGE_SLOTS) {
            return action != org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                    && action != org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR;
        }
        return switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY, DROP_ALL_SLOT, DROP_ONE_SLOT, NOTHING -> true;
            default -> false;
        };
    }

    @Override
    protected void handleDrag(final InventoryDragEvent event) {
        if (!active || stale()) {
            event.setCancelled(true);
            return;
        }
        for (final int raw : event.getRawSlots()) {
            if (page != CURRENCY_PAGE && (bag.locked(page) || filtering()) && raw < PAGE_SLOTS) {
                event.setCancelled(true);
                return;
            }
            if (page == CURRENCY_PAGE ? raw < NAV_START + 9 : raw >= NAV_START && raw < NAV_START + 9) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        // 用 handleRawClick 處理
    }

    private void onNav(final int slot, final ClickType click) {
        if ((slot == SLOT_PREVIOUS_PAGE || slot == SLOT_NEXT_PAGE) && page < CURRENCY_PAGE && bag.viewPages() > 1) {
            switchTo(stepPage(page, bag.viewPages(), slot == SLOT_NEXT_PAGE, click));
        } else if (slot == SLOT_PAGE_TAB && page == CURRENCY_PAGE) {
            switchTo(lastItemPage);
        } else if (slot == SLOT_PAGE_TAB && bag.viewPages() > 1) {
            openOverview();
        } else if (slot == SLOT_CURRENCY) {
            switchTo(CURRENCY_PAGE);
        } else if (slot == SLOT_TOOL) {
            if (page == CURRENCY_PAGE) {
                depositAllCurrency();
            } else if (click.isRightClick()) {
                service.openFilter(player);
            } else {
                sort();
            }
        } else if (slot == SLOT_STOW && page < CURRENCY_PAGE) {
            if (click.isRightClick()) {
                takeOutPage();
            } else {
                stowInventory();
            }
        } else if (slot == SLOT_FILTER) {
            if (page == CURRENCY_PAGE) {
                service.openFilter(player);
            } else {
                onFilterButton(click);
            }
        } else if (slot == SLOT_CLOSE) {
            goBack(); // 返回選單
        }
    }

    /** 搜尋・過濾的按鈕。 */
    private void onFilterButton(final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            clearFilter();
        } else if (click == ClickType.SHIFT_LEFT) {
            final tw.exilecore.item.Rarity[] all = tw.exilecore.item.Rarity.values();
            final tw.exilecore.item.Rarity next = filter.rarity() == null ? all[0]
                    : filter.rarity().ordinal() + 1 < all.length ? all[filter.rarity().ordinal() + 1] : null;
            applyFilter(new Filter(filter.query(), filter.category(), next));
        } else if (click.isRightClick()) {
            final int at = CATEGORIES.indexOf(filter.category());
            applyFilter(new Filter(filter.query(), CATEGORIES.get((at + 1) % CATEGORIES.size()), filter.rarity()));
        } else if (click.isLeftClick()) {
            final int from = page;
            final Filter keep = filter;
            plugin.dialogs().text(player, "<dark_gray>隨身背包搜尋", List.of("<gray>名稱、詞綴、基底、稀有度都找得到；空白隔開＝每個都要有",
                            "<gray>留空＝只用種類、稀有度過濾"), "關鍵字", filter.query(), 50,
                    text -> reopen(from, new Filter(text, keep.category(), keep.rarity())), () -> reopen(from, keep));
        }
    }

    /** 搜尋輸入完：重新打開（用玩家現在的隨身背包）。 */
    private void reopen(final int from, final Filter value) {
        if (!player.isOnline()) {
            return;
        }
        final Bag current = plugin.players().get(player).bag();
        new BagMenu(plugin, player, service, current, Math.min(from, Math.max(0, current.viewPages() - 1)), value).open();
    }

    private void applyFilter(final Filter value) {
        // 2026-10-21 漏洞修正：開過濾前先把畫面寫回背包。以前直接照記憶體重畫：剛拿出來的東西又畫回來，
        // 再取消過濾就變成真的、可以再拿一次（複製）；過濾中關掉也不會寫回（syncToModel 過濾中不寫）
        syncToModel();
        setFilter(value);
        if (filtering() && matchesOn(page) == 0) {
            for (int p = 0; p < bag.viewPages(); p++) {
                if (matchesOn(p) > 0) {
                    page = p;
                    lastItemPage = p;
                    break;
                }
            }
        }
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        if (filtering()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#f5b942>過濾：" + ItemRenderer.escape(filter.describe()) + "<gray>（全部 " + totalMatches() + " 件符合）"));
        }
    }

    private void clearFilter() {
        setFilter(Filter.NONE);
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    /** 通貨袋最上面一排的分組頁籤。 */
    private void onGroupTab(final int raw) {
        final tw.exilecore.currency.CurrencyGroups.Group[] groups = tw.exilecore.currency.CurrencyGroups.Group.values();
        for (int g = 0; g < groups.length; g++) {
            if (groupTabs[g] == raw && groups[g] != currencyGroup) {
                currencyGroup = groups[g];
                redraw();
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
                return;
            }
        }
    }

    /** 全部頁一覽（2026-10：背包可以買很多頁）。 */
    private void openOverview() {
        syncToModel();
        new BagPagesMenu(plugin, player, service, bag, page).open();
    }

    private void switchTo(final int newPage) {
        if (newPage == page) {
            return;
        }
        syncToModel();
        page = newPage;
        if (page < CURRENCY_PAGE) {
            lastItemPage = page;
        }
        redraw();
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
    }

    // ---- 物品頁 ----

    /** 兩頁的東西疊在一起、照種類、稀有度、等級排好（跟倉庫的「整理」同一套規則）。 */
    private void sort() {
        syncToModel();
        final List<tw.exilecore.stash.StashOrganizer.Slots> rows = new ArrayList<>();
        for (int p = 0; p < bag.pages(); p++) {
            rows.add(tw.exilecore.stash.StashOrganizer.Slots.of(bag.page(p)));
        }
        new tw.exilecore.stash.StashOrganizer(plugin).sort(rows);
        redraw();
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
    }

    /** 畫面上這一頁的某一格拿到主背包（先疊、再放空格；放不下的留在原地）。 */
    private void takeOut(final int raw) {
        final Inventory top = getInventory();
        final ItemStack stack = top.getItem(raw);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final ItemStack rest = moveToMain(stack.clone());
        top.setItem(raw, rest);
        syncToModel();
        if (rest != null && rest.getAmount() == stack.getAmount()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>主背包滿了"));
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.3f);
        }
    }

    /** 放進主背包上面三排：先疊到同樣的，再放空格；回傳放不下的（全放下了是 null）。 */
    private ItemStack moveToMain(ItemStack rest) {
        final PlayerInventory inventory = player.getInventory();
        for (int slot = INVENTORY_MAIN_START; slot < INVENTORY_END && rest != null; slot++) {
            final ItemStack there = inventory.getItem(slot);
            if (there != null && !there.isEmpty() && there.isSimilar(rest) && there.getAmount() < there.getMaxStackSize()) {
                final int add = Math.min(rest.getAmount(), there.getMaxStackSize() - there.getAmount());
                there.setAmount(there.getAmount() + add);
                inventory.setItem(slot, there);
                rest = add >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - add);
            }
        }
        for (int slot = INVENTORY_MAIN_START; slot < INVENTORY_END && rest != null; slot++) {
            final ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()) {
                inventory.setItem(slot, rest);
                rest = null;
            }
        }
        return rest;
    }

    /** 這一頁的東西全部拿到主背包上面三排（先疊到同樣的，再放空格；放不下的留著）。通貨袋不動。 */
    private void takeOutPage() {
        syncToModel();
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] items = bag.page(page);
        int moved = 0;
        boolean full = false;
        for (int i = 0; i < items.length; i++) {
            final ItemStack stack = items[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ItemStack rest = moveToMain(stack.clone());
            if (rest == null || rest.getAmount() < stack.getAmount()) {
                moved++;
            }
            if (rest != null) {
                full = true;
            }
            items[i] = rest;
        }
        redraw();
        if (moved > 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>拿出 " + moved + " 格" + (full ? "<red>，主背包滿了" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        } else {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(full ? "<red>主背包滿了" : "<gray>這一頁沒有東西"));
        }
    }

    /** 背包上面三排（快捷列以外）的東西全部收進隨身背包；通貨進通貨袋。 */
    private void stowInventory() {
        syncToModel();
        final PlayerInventory inventory = player.getInventory();
        int moved = 0;
        boolean full = false;
        for (int slot = INVENTORY_MAIN_START; slot < INVENTORY_END; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack rest = stack.clone();
            final Optional<CurrencyType> type = plugin.currencies().read(rest);
            if (type.isPresent()) {
                final int accepted = bag.pouch().deposit(type.get().id(), rest.getAmount());
                rest = accepted >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - accepted);
            }
            for (int p = 0; p < bag.pages() && rest != null; p++) {
                rest = LootService.mergeInto(bag.page(p), rest);
            }
            for (int p = 0; p < bag.pages() && rest != null; p++) {
                rest = LootService.fillEmpty(bag.page(p), rest);
            }
            if (rest == null || rest.getAmount() < stack.getAmount()) {
                moved++;
            }
            if (rest != null) {
                full = true;
            }
            inventory.setItem(slot, rest);
        }
        redraw();
        if (moved > 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>收進 " + moved + " 格" + (full ? "<red>，隨身背包滿了" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
        } else {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(full ? "<red>隨身背包滿了" : "<gray>背包上面三排沒有東西"));
        }
    }

    // ---- 通貨袋 ----

    private void onCurrencyPageClick(final InventoryClickEvent event, final int raw) {
        final ClickType click = event.getClick();
        final ItemStack cursor = event.getCursor();
        if (raw >= 0 && raw < PAGE_SLOTS) {
            event.setCancelled(true);
            if (click == ClickType.DOUBLE_CLICK) {
                return; // 雙擊的第二下：第一下已經把通貨拿到游標上了，不要又放回去
            }
            if (raw < CURRENCY_START) {
                if (cursor.isEmpty()) {
                    onGroupTab(raw); // 最上面一排：換組
                } else {
                    depositCursor(event, cursor); // 拿著通貨點到頁籤也當成放回去
                }
                return;
            }
            if (!cursor.isEmpty()) {
                depositCursor(event, cursor);
            } else {
                withdraw(event, raw - CURRENCY_START, click);
            }
            return;
        }
        if (click == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true); // 不讓「收集到游標」碰到上面的圖示
            return;
        }
        if (!(event.getClickedInventory() instanceof PlayerInventory inventory)) {
            return;
        }
        final ItemStack current = event.getCurrentItem();
        if (!cursor.isEmpty() && plugin.currencies().isCurrency(cursor) && current != null && plugin.items().isEquipment(current)
                && (click == ClickType.LEFT || click == ClickType.RIGHT)) {
            return; // 拿著通貨點裝備 = 使用（交給通貨的監聽器）
        }
        if (cursor.isEmpty() && current != null && !current.isEmpty() && plugin.currencies().isCurrency(current)
                && (click == ClickType.LEFT || click == ClickType.RIGHT || click.isShiftClick())) {
            event.setCancelled(true);
            deposit(inventory, event.getSlot());
            return;
        }
        if (click.isShiftClick()) {
            event.setCancelled(true); // Shift 會把東西移到上面（通貨袋的圖示那裡）
        }
        // 其他（在自己的背包裡拿起、放下、交換）照原版
    }

    private void withdraw(final InventoryClickEvent event, final int slot, final ClickType click) {
        if (slot >= currencyOrder.size()) {
            return;
        }
        final CurrencyType type = currencyOrder.get(slot);
        final int have = bag.pouch().amount(type.id());
        if (have <= 0) {
            return;
        }
        if (click == ClickType.SHIFT_RIGHT && plugin.cards() != null && plugin.cards().goesToStash(type)) {
            plugin.cards().usePouch(player, bag.pouch(), type, true); // 2026-10-19 晚：星諭卡全部兌換、牌堆全部打開
            redraw();
            return;
        }
        if (click == ClickType.SHIFT_RIGHT && type.uncutGem() && plugin.skills() != null) {
            // 未切割寶石：直接用通貨袋裡的雕琢、切割（不用先拿出來）
            if (!plugin.skills().gems().openFromPouch(player, type.id())) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>先選一個角色"));
            }
            return;
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            withdrawToInventory(type, have);
            return;
        }
        final int want = click == ClickType.RIGHT ? 1 : Math.min(have, type.stack());
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        final int taken = bag.pouch().withdraw(type.id(), want);
        if (taken <= 0) {
            return;
        }
        event.getView().setCursor(plugin.currencies().create(type, taken));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.3f);
        redraw();
    }

    /** Shift＋點：能放多少就放多少進背包。 */
    private void withdrawToInventory(final CurrencyType type, final int have) {
        int remaining = have;
        int given = 0;
        while (remaining > 0) {
            final int batch = Math.min(remaining, type.stack());
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(plugin.currencies().create(type, batch));
            int notGiven = 0;
            for (final ItemStack rest : leftover.values()) {
                notGiven += rest.getAmount();
            }
            given += batch - notGiven;
            remaining -= batch;
            if (notGiven > 0) {
                break; // 背包滿了
            }
        }
        if (given <= 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>背包滿了"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        bag.pouch().withdraw(type.id(), given);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.3f);
        redraw();
    }

    /** 拿著通貨點通貨袋：整疊放回去。 */
    private void depositCursor(final InventoryClickEvent event, final ItemStack cursor) {
        final Optional<CurrencyType> type = plugin.currencies().read(cursor);
        if (type.isEmpty()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>通貨袋只能放通貨，物品請放在第 1、2 頁"));
            return;
        }
        final int accepted = bag.pouch().deposit(type.get().id(), cursor.getAmount());
        if (accepted <= 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(type.get().name()) + "已經裝滿 " + bag.pouch().cap() + " 個（贊助可以加大）"));
            return;
        }
        final int left = cursor.getAmount() - accepted;
        event.getView().setCursor(left <= 0 ? ItemStack.empty() : cursor.asQuantity(left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
        redraw();
    }

    private void deposit(final PlayerInventory inventory, final int slot) {
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final Optional<CurrencyType> type = plugin.currencies().read(stack);
        if (type.isEmpty()) {
            return;
        }
        final int accepted = bag.pouch().deposit(type.get().id(), stack.getAmount());
        if (accepted <= 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(type.get().name()) + "已經裝滿 " + bag.pouch().cap() + " 個（贊助可以加大）"));
            return;
        }
        final int left = stack.getAmount() - accepted;
        inventory.setItem(slot, left <= 0 ? null : stack.asQuantity(left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
        redraw();
    }

    private void depositAllCurrency() {
        final PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < INVENTORY_END; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Optional<CurrencyType> type = plugin.currencies().read(stack);
            if (type.isEmpty()) {
                continue;
            }
            final int accepted = bag.pouch().deposit(type.get().id(), stack.getAmount());
            if (accepted > 0) {
                total += accepted;
                final int left = stack.getAmount() - accepted;
                inventory.setItem(slot, left <= 0 ? null : stack.asQuantity(left));
            }
        }
        if (total > 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>收進 " + total + " 個通貨"));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
        } else {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>背包裡沒有通貨"));
        }
        redraw();
    }
}
