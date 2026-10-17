package tw.exilecore.stash;

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
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSearch;
import tw.exilecore.market.MarketSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 倉庫視窗（6 列）。2026-10 改成統一版面：最下面一排固定是「◀ 上一頁、這一頁的頁籤、下一頁 ▶、通貨頁、說明、搜尋、整理、
 * 收進倉庫、關閉」；點這一頁的頁籤打開「全部頁籤」一覽（16 頁一次看完、選一頁、改名稱與分類、全部頁重新分頁）。
 * 物品頁可以自由搬東西（原版操作）；通貨頁最上面一排是四個分組的頁籤，下面四排是那一組的通貨，點一下拿出、點背包裡的通貨存入。
 * <ul>
 *   <li>頁籤：右鍵改名稱與分類（武器、防具、飾品、寶石、藥劑、地圖、其他或全部）。</li>
 *   <li>收進倉庫：左鍵把背包上面三排、右鍵把隨身背包（含通貨袋）的通貨與物品照分類收進來（地圖先進地圖頁）。</li>
 *   <li>地圖頁（2026-10，PoE 的地圖倉庫頁）：最上面兩排是 T1–T16 與「全部階級」，下面三排是那一階的地圖（◀ ▶ 換批），
 *       點地圖拿出來、點背包裡的地圖存進去；最多 {@value Stash#MAP_CAP} 張，不佔物品頁的格子。</li>
 * </ul>
 * <p>
 * 搜尋（PoE 的倉庫搜尋）：輸入關鍵字後，符合的物品發光、不符合的變成灰色玻璃，換頁看得到每頁有幾件符合。
 * 搜尋中只能看不能搬（畫面上的灰色玻璃不是真的東西，也不會同步回倉庫）；點一件符合的物品或「結束搜尋」回到一般畫面。
 */
public final class StashMenu extends Menu {

    private static final int PAGE_SLOTS = Stash.PAGE_SIZE;
    private static final int NAV_START = 45;
    private static final int SLOT_PREVIOUS_PAGE = 45;
    /** 這一頁的頁籤（左鍵：全部頁籤一覽；右鍵：改名稱與分類）；通貨頁上是「回物品頁」。 */
    private static final int SLOT_TAB = 46;
    private static final int SLOT_NEXT_PAGE = 47;
    private static final int SLOT_CURRENCY_PAGE = 48;
    /** 物品頁：搜尋；通貨頁：存入通貨袋。 */
    private static final int SLOT_SEARCH = 50;
    private static final int SLOT_DEPOSIT_POUCH = 50;
    /** 物品頁：整理這一頁（搜尋中是結束搜尋）；通貨頁：存入背包裡的通貨。 */
    private static final int SLOT_SORT = 51;
    private static final int SLOT_DEPOSIT_CURRENCY = 51;
    /** 收進倉庫（左鍵背包、右鍵隨身背包）。 */
    private static final int SLOT_DEPOSIT = 52;
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;
    /** 通貨頁：最上面一排是分組的頁籤，下面四排是通貨。 */
    private static final int CURRENCY_START = CONTENT_START;
    private static final int CURRENCY_SLOTS = tw.exilecore.currency.CurrencyGroups.PAGE_CAPACITY; // 四排（CONTENT_START 到 CONTENT_END）
    /** 通貨頁（比任何物品頁的頁碼都大）。 */
    private static final int CURRENCY_PAGE = Stash.MAX_PAGES;
    /** 地圖頁（通貨頁的下一個）：最上面兩排是 T1–T16 與「全部」的頁籤，下面三排是那一階的地圖。 */
    static final int MAP_PAGE = Stash.MAX_PAGES + 1;
    /** 底下那排「地圖頁」的按鈕（原本的說明搬到全部頁籤一覽）。 */
    private static final int SLOT_MAP_PAGE = SLOT_HELP;
    /** 地圖頁：存入背包裡所有地圖。 */
    private static final int SLOT_DEPOSIT_MAPS = 50;
    private static final int MAX_TIER = 16;
    /** 「全部階級」的頁籤。 */
    private static final int SLOT_ALL_TIERS = 17;
    private static final int MAP_START = 18;
    private static final int MAP_SLOTS = PAGE_SLOTS - MAP_START; // 27 格
    /** 背包上面三排（快捷列以外）。 */
    private static final int INVENTORY_MAIN_START = 9;
    private static final int INVENTORY_END = 36;

    private final StashService service;
    private final Stash stash;
    private final StashOrganizer organizer;
    private final List<CurrencyType> currencyOrder = new ArrayList<>();
    private final int[] groupTabs = tabSlots(tw.exilecore.currency.CurrencyGroups.Group.values().length);
    private int page;
    /** 最後看的物品頁（從通貨頁回去用）。 */
    private int lastItemPage;
    /** 通貨頁看哪一組通貨（最上面一排的頁籤；一打開先看有東西的那組）。 */
    private tw.exilecore.currency.CurrencyGroups.Group currencyGroup;
    /** 畫面真的打開了（沒打開就不能把畫面同步回倉庫，不然會把空白寫回去）。 */
    private boolean active;
    /** 搜尋的關鍵字；空的 = 沒有在搜尋。 */
    private List<String> terms = List.of();
    private String query = "";
    /** 地圖頁看哪一階（0 = 全部階級；-1 = 還沒選，一打開先看有地圖的最高階）。 */
    private int mapTier = -1;
    /** 地圖頁這一階的第幾批（每批 27 張）。 */
    private int mapBatch;
    /** 地圖頁畫面上每一格對應 stash.maps() 的第幾張。 */
    private final List<Integer> mapView = new ArrayList<>();

    StashMenu(final ExileCore plugin, final Player player, final StashService service, final Stash stash) {
        this(plugin, player, service, stash, null);
    }

    StashMenu(final ExileCore plugin, final Player player, final StashService service, final Stash stash, final String search) {
        this(plugin, player, service, stash, search, 0, true, 0);
    }

    /**
     * @param jump     有搜尋時跳到第一個有符合的頁（剛輸入關鍵字時；從全部頁籤一覽選的頁不跳）
     * @param itemPage 從通貨頁開始時，「← 回物品頁」回到哪一頁
     */
    StashMenu(final ExileCore plugin, final Player player, final StashService service, final Stash stash, final String search,
              final int startPage, final boolean jump, final int itemPage) {
        super(plugin, player, 6, search == null || search.isBlank() ? "<dark_gray>倉庫（帳號共用）"
                : "<dark_gray>倉庫：搜尋「" + ItemRenderer.escape(search) + "」");
        this.service = service;
        this.stash = stash;
        this.organizer = new StashOrganizer(plugin);
        this.page = startPage == CURRENCY_PAGE || startPage == MAP_PAGE ? startPage : Math.max(0, Math.min(stash.pageCount() - 1, startPage));
        if (search != null && !search.isBlank()) {
            this.query = search.trim();
            this.terms = MarketSearch.terms(search);
            // 剛輸入關鍵字：跳到第一個有符合的頁
            for (int i = 0; jump && i < stash.pageCount(); i++) {
                if (matchesOn(i) > 0) {
                    this.page = i;
                    break;
                }
            }
        }
        this.lastItemPage = page < CURRENCY_PAGE ? page : Math.max(0, Math.min(stash.pageCount() - 1, itemPage));
    }

    /** 搜尋的關鍵字（全部頁籤一覽用）；沒有在搜尋是 null。 */
    String query() {
        return searching() ? query : null;
    }

    private boolean searching() {
        return !terms.isEmpty();
    }

    private boolean matches(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && MarketSearch.matches(ItemSearch.text(plugin.items(), stack), terms);
    }

    /** 這一頁有幾件符合搜尋（全部頁籤一覽也用）。 */
    int matchesOn(final int index) {
        int count = 0;
        for (final ItemStack stack : stash.page(index)) {
            if (matches(stack)) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected boolean allowsItemMovement() {
        return true;
    }

    @Override
    protected void onOpen() {
        active = true;
        service.opened(player, this);
    }

    @Override
    protected void onClose() {
        returnCursor();
        syncToModel();
        active = false;
        service.closed(player, this);
        plugin.players().markDirty(player);
        plugin.characters().save(player); // 背包與倉庫一起存
    }

    /**
     * 關閉時游標上還拿著東西：放回背包，背包滿了放進這一頁。
     * 原版在「斷線關閉」時會把游標上的東西丟在地上，放回去才不會留在登出的地方被別人撿走。
     */
    void returnCursor() {
        final ItemStack cursor = player.getItemOnCursor();
        if (cursor.isEmpty()) {
            return;
        }
        ItemStack rest = null;
        for (final ItemStack leftover : player.getInventory().addItem(cursor.clone()).values()) {
            rest = leftover;
        }
        if (rest != null && page < CURRENCY_PAGE) {
            final Inventory inventory = getInventory();
            for (int i = 0; i < PAGE_SLOTS && rest != null; i++) {
                final ItemStack item = inventory.getItem(i);
                if (item == null || item.isEmpty()) {
                    inventory.setItem(i, rest);
                    rest = null;
                }
            }
        }
        // 2026-10-17：還是放不下的（例如在通貨頁、地圖頁關掉）：掉在腳下只有自己撿得到（以前交給原版，誰都撿得到）
        player.setItemOnCursor(null);
        if (rest != null && !rest.isEmpty()) {
            plugin.economy().wallet().dropFor(player, rest);
        }
    }

    /** 把畫面上這一頁的內容寫回倉庫（換頁、關閉、存檔前）。搜尋中畫面上不是真的東西，不同步（搜尋中也不能搬）。 */
    void syncToModel() {
        if (!active || page >= CURRENCY_PAGE || searching()) {
            return;
        }
        final Inventory inventory = getInventory();
        final ItemStack[] target = stash.page(page);
        for (int i = 0; i < PAGE_SLOTS; i++) {
            final ItemStack item = inventory.getItem(i);
            target[i] = item == null || item.isEmpty() ? null : item.clone();
        }
    }

    // ---- 畫面 ----

    @Override
    protected void draw() {
        if (page < CURRENCY_PAGE) {
            final ItemStack[] items = stash.page(page);
            for (int i = 0; i < PAGE_SLOTS; i++) {
                if (items[i] == null) {
                    set(i, null);
                } else if (!searching()) {
                    set(i, items[i].clone());
                } else if (matches(items[i])) {
                    // 外框（沒資源包：發光；不符合的都換成灰色玻璃片了，還是看得出來）
                    set(i, highlight(appendLore(items[i].clone(), "", "<#f5b942>✔ 符合搜尋", "<yellow>左鍵：結束搜尋，停在這一頁")));
                } else {
                    set(i, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>（不符合搜尋）"));
                }
            }
        } else if (page == CURRENCY_PAGE) {
            drawCurrency();
        } else {
            drawMaps();
        }
        drawNav();
    }

    private void drawNav() {
        for (int slot = NAV_START; slot < NAV_START + 9; slot++) {
            set(slot, bar());
        }
        final boolean currencyPage = page == CURRENCY_PAGE;
        final boolean mapPage = page == MAP_PAGE;
        if (currencyPage || mapPage) {
            final StashCategory category = stash.tabCategory(lastItemPage);
            set(SLOT_TAB, icon(Menu.material(category.icon(), Material.CHEST), "<white>← 回物品頁",
                    "<gray>回到「" + ItemRenderer.escape(stash.tabLabel(lastItemPage)) + "」", "", "<yellow>左鍵：回去"));
        } else {
            final int pages = stash.pageCount();
            final int elsewhere = searching() ? totalMatches() - matchesOn(page) : 0;
            set(SLOT_PREVIOUS_PAGE, pages > 1 ? jumpButton(false, page, pages) : bar());
            set(SLOT_NEXT_PAGE, pages > 1 ? jumpButton(true, page, pages) : bar());
            final StashCategory category = stash.tabCategory(page);
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>第 " + (page + 1) + "／" + pages + " 頁・放：<white>" + category.displayName()
                    + (category == StashCategory.ALL ? "（什麼都放）" : ""));
            lore.add("<gray>" + stash.itemCount(page) + " / " + PAGE_SLOTS + " 格有東西");
            if (stash.locked(page)) {
                lore.add("<red>這一頁已經收回：只能拿出來，拿空就會消失");
            }
            if (searching()) {
                lore.add("<#f5b942>這一頁符合 " + matchesOn(page) + " 件" + (elsewhere > 0 ? "，其他頁 " + elsewhere + " 件" : ""));
            }
            lore.add("");
            // 2026-10：說明縮短；改名稱、分類、重新分頁都在一覽裡（右鍵直接改這一頁照舊能用）
            lore.add("<yellow>左鍵：全部頁籤一覽<dark_gray>（換頁、改名、分類、重新分頁）");
            lore.add("<yellow>Shift＋左鍵：跳到下一個有空格的頁");
            final ItemStack tab = icon(category == StashCategory.ALL ? Material.ENDER_CHEST : Menu.material(category.icon(), Material.CHEST),
                    "<#f5b942>▶ " + ItemRenderer.escape(stash.tabLabel(page)), lore);
            set(SLOT_TAB, highlight(elsewhere > 0 ? withCount(tab, elsewhere) : tab));
        }
        if (mapPage) {
            final int batches = mapBatches();
            set(SLOT_PREVIOUS_PAGE, batches > 1 ? pageButton(false, mapBatch, batches) : bar());
            set(SLOT_NEXT_PAGE, batches > 1 ? pageButton(true, mapBatch, batches) : bar());
        }
        int kinds = 0;
        for (final int amount : stash.currencies().values()) {
            if (amount > 0) {
                kinds++;
            }
        }
        set(SLOT_CURRENCY_PAGE, tab(Material.SUNFLOWER, "通貨頁", currencyPage,
                List.of("<gray>存了 " + kinds + " 種通貨（每種最多 " + String.format(Locale.ROOT, "%,d", stash.currencyCap()) + " 個）",
                        "<gray>點通貨拿出來，點背包裡的通貨存進去")));
        set(SLOT_MAP_PAGE, tab(Material.FILLED_MAP, "地圖頁", mapPage,
                List.of("<gray>存了 " + stash.mapCount() + " / " + stash.mapCap() + " 張地圖（照階級分開放）",
                        "<gray>點地圖拿出來，點背包裡的地圖存進去")));
        if (mapPage) {
            set(SLOT_DEPOSIT_MAPS, icon(Material.HOPPER, "<#7fd4ff>存入背包裡所有地圖",
                    "<gray>背包與快捷列裡的地圖全部存進來", "", "<yellow>左鍵：存入"));
        } else if (currencyPage) {
            set(SLOT_DEPOSIT_POUCH, icon(Material.BUNDLE, "<#7fd4ff>存入通貨袋裡所有通貨",
                    "<gray>隨身背包通貨袋裡的通貨全部存進來", "<gray>（換角色也拿得到）", "", "<yellow>左鍵：存入"));
            set(SLOT_DEPOSIT_CURRENCY, icon(Material.HOPPER, "<#7fd4ff>存入背包裡所有通貨",
                    "<gray>背包與快捷列裡的通貨全部存進來", "", "<yellow>左鍵：存入"));
        } else {
            set(SLOT_SEARCH, icon(Material.SPYGLASS, "<#f5b942>搜尋",
                    searching() ? "<gray>目前：<white>" + ItemRenderer.escape(query) : "<gray>名稱、詞綴、基底、稀有度都找得到",
                    "<dark_gray>空白隔開＝都要有，例如「火焰 抗性」", "", "<yellow>左鍵：輸入關鍵字"));
            if (searching()) {
                set(SLOT_SORT, icon(Material.BARRIER, "<white>結束搜尋", "<gray>回到可以搬東西的畫面（停在這一頁）", "", "<yellow>左鍵：結束"));
            } else {
                set(SLOT_SORT, icon(Material.COMPARATOR, "<#7fd4ff>整理這一頁",
                        "<gray>疊在一起，照種類、稀有度、等級排好", "", "<yellow>左鍵：整理",
                        "<yellow>右鍵：這一頁全部放進隨身背包"));
            }
        }
        final List<String> depositLore = new ArrayList<>();
        depositLore.add("<gray>照頁籤的分類放（通貨、地圖進自己的頁）");
        depositLore.add("");
        depositLore.add("<yellow>左鍵：背包上面三排");
        depositLore.add("<yellow>右鍵：隨身背包（含通貨袋）");
        set(SLOT_DEPOSIT, icon(Material.HOPPER_MINECART, "<#7fd4ff>收進倉庫", depositLore));
        set(SLOT_CLOSE, closeButton());
    }

    /** 全部頁加起來有幾件符合搜尋。 */
    private int totalMatches() {
        int total = 0;
        for (int i = 0; i < stash.pageCount(); i++) {
            total += matchesOn(i);
        }
        return total;
    }

    private void drawCurrency() {
        currencyOrder.clear();
        final java.util.Collection<CurrencyType> all = plugin.currencies().repository().all();
        if (currencyGroup == null) {
            currencyGroup = tw.exilecore.currency.CurrencyGroups.first(all, type -> stash.currency(type.id()));
        }
        for (int i = 0; i < 9; i++) {
            set(i, bar());
        }
        final int[] held = tw.exilecore.currency.CurrencyGroups.kindsHeld(all, type -> stash.currency(type.id()));
        final tw.exilecore.currency.CurrencyGroups.Group[] groups = tw.exilecore.currency.CurrencyGroups.Group.values();
        for (int g = 0; g < groups.length; g++) {
            final List<CurrencyType> members = tw.exilecore.currency.CurrencyGroups.in(all, groups[g]);
            final Material material = members.isEmpty() ? Material.SUNFLOWER : Menu.material(members.getFirst().material(), Material.SUNFLOWER);
            set(groupTabs[g], tab(material, groups[g].displayName(), groups[g] == currencyGroup,
                    List.of("<gray>" + groups[g].about(), "<gray>存了 <white>" + held[g] + "</white> / " + members.size() + " 種")));
        }
        currencyOrder.addAll(tw.exilecore.currency.CurrencyGroups.in(all, currencyGroup));
        for (int i = 0; i < CURRENCY_SLOTS; i++) {
            final int slot = CURRENCY_START + i;
            if (i >= currencyOrder.size()) {
                set(slot, null);
                continue;
            }
            final CurrencyType type = currencyOrder.get(i);
            final int amount = stash.currency(type.id());
            final List<String> lore = new ArrayList<>();
            lore.add(String.format(Locale.ROOT, "<gray>存了 <white>%,d</white> / %,d", amount, stash.currencyCap()));
            // 2026-10：撿到的通貨會先進通貨袋（/bag），不是倉庫；袋子裡有的話提醒一下，免得以為不見了
            final int inPouch = plugin.players().get(player).bag().pouch().amount(type.id());
            if (inPouch > 0) {
                lore.add(String.format(Locale.ROOT, "<#f5b942>通貨袋（/bag）裡還有 <white>%,d</white> 個<gray>：按「存入通貨袋裡所有通貨」，或在 /bag 直接拿", inPouch));
            }
            lore.add("<dark_gray>" + ItemRenderer.escape(type.description()));
            lore.add("");
            if (amount > 0) {
                lore.add("<yellow>左鍵：拿一疊（" + Math.min(amount, type.stack()) + " 個）");
                lore.add("<yellow>右鍵：拿 1 個　Shift＋左鍵：能拿的全部拿");
            } else {
                lore.add("<dark_gray>點背包裡的" + ItemRenderer.escape(type.name()) + "存入");
            }
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

    // ---- 地圖頁 ----

    /**
     * 地圖頁每張地圖的階級、稀有度、名稱（2026-10）：以前排序時每比一次就把 PDC 的 JSON 解一次，
     * 幾百張地圖一次畫面要解幾千次、拿一堆出來就卡；現在每張只解一次，物件沒換就一直用。
     */
    private final Map<ItemStack, MapInfo> mapInfo = new java.util.IdentityHashMap<>();

    private record MapInfo(int tier, int rarity, String name) {
    }

    private MapInfo infoOf(final ItemStack stack) {
        MapInfo info = mapInfo.get(stack);
        if (info == null) {
            info = new MapInfo(tierOf(stack), rarityOf(stack), StashOrganizer.plainName(stack));
            mapInfo.put(stack, info);
        }
        return info;
    }

    /** 地圖的階級（1–16）；不是地圖回傳 0。 */
    private int tierOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final Optional<tw.exilecore.item.ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return 0;
        }
        final tw.exilecore.item.ItemBase base = plugin.items().repository().base(data.get().base());
        if (base == null || !base.isMap()) {
            return 0;
        }
        return Math.max(1, Math.min(MAX_TIER, tw.exilecore.item.ItemStats.mapTier(base, data.get())));
    }

    private int rarityOf(final ItemStack stack) {
        return plugin.items().read(stack).map(data -> data.rarity().ordinal()).orElse(0);
    }

    /** 這一階（0 = 全部）的地圖在 stash.maps() 的位置：高階在前、稀有度高的在前、名稱。 */
    private List<Integer> mapsIn(final int tier) {
        final List<ItemStack> maps = stash.maps();
        if (mapInfo.size() > maps.size() * 2 + 64) {
            mapInfo.clear(); // 拿出去的地圖的資料不用留著
        }
        final List<Integer> list = new ArrayList<>();
        final List<MapInfo> infos = new ArrayList<>(maps.size());
        for (int i = 0; i < maps.size(); i++) {
            final MapInfo info = infoOf(maps.get(i));
            infos.add(info);
            if (tier == 0 || info.tier() == tier) {
                list.add(i);
            }
        }
        list.sort(java.util.Comparator.<Integer>comparingInt(i -> -infos.get(i).tier())
                .thenComparingInt(i -> -infos.get(i).rarity())
                .thenComparing(i -> infos.get(i).name()));
        return list;
    }

    private int mapBatches() {
        return Math.max(1, (mapsIn(Math.max(0, mapTier)).size() + MAP_SLOTS - 1) / MAP_SLOTS);
    }

    private void drawMaps() {
        final int[] counts = new int[MAX_TIER + 1];
        for (final ItemStack map : stash.maps()) {
            counts[infoOf(map).tier()]++;
        }
        if (mapTier < 0) {
            mapTier = 0;
            for (int tier = MAX_TIER; tier >= 1; tier--) {
                if (counts[tier] > 0) {
                    mapTier = tier;
                    break;
                }
            }
        }
        for (int tier = 1; tier <= MAX_TIER; tier++) {
            final Material material = tier >= 11 ? Material.RED_STAINED_GLASS_PANE : tier >= 6 ? Material.YELLOW_STAINED_GLASS_PANE
                    : Material.WHITE_STAINED_GLASS_PANE;
            // 有資源包：格子上直接畫「T 幾」（UiPack 的 map_t1–map_t16）；沒有：照舊用地圖／色塊，名字寫 T 幾
            final boolean current = tier == mapTier;
            final ItemStack plain = uiIcon("map_t" + tier, counts[tier] > 0 ? Material.FILLED_MAP : material,
                    (current ? "<#f5b942>▶ " : "<white>") + "T" + tier,
                    "<gray>存了 <white>" + counts[tier] + "</white> 張", "", current ? "<green>目前在這一頁" : "<yellow>左鍵：切換到這一頁");
            final ItemStack tab = current ? highlight(plain) : plain;
            set(tier - 1, counts[tier] > 0 ? withCount(tab, Math.min(99, counts[tier])) : tab);
        }
        set(16, bar());
        set(SLOT_ALL_TIERS, tab(Material.MAP, "全部階級", mapTier == 0,
                List.of("<gray>存了 <white>" + stash.mapCount() + "</white> / " + stash.mapCap() + " 張")));
        final List<Integer> view = mapsIn(mapTier);
        mapBatch = Math.max(0, Math.min(mapBatch, mapBatches() - 1));
        mapView.clear();
        for (int i = 0; i < MAP_SLOTS; i++) {
            final int index = mapBatch * MAP_SLOTS + i;
            if (index >= view.size()) {
                set(MAP_START + i, null);
                continue;
            }
            mapView.add(view.get(index));
            set(MAP_START + i, appendLore(stash.maps().get(view.get(index)).clone(), "",
                    "<yellow>左鍵：拿出來", "<yellow>Shift＋左鍵：這一階能拿的全部拿"));
        }
    }

    /** 選一階（0 = 全部）。 */
    private void onTierTab(final int raw) {
        final int tier = raw == SLOT_ALL_TIERS ? 0 : raw < MAX_TIER ? raw + 1 : -1;
        if (tier < 0 || tier == mapTier) {
            return;
        }
        mapTier = tier;
        mapBatch = 0;
        redraw();
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
    }

    /** 拿出地圖：左鍵一張，Shift 把這一階（這一批以後）能拿的都拿。 */
    private void withdrawMap(final int cell, final ClickType click) {
        if (cell < 0 || cell >= mapView.size()) {
            return;
        }
        final List<Integer> wanted = new ArrayList<>();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            wanted.addAll(mapsIn(mapTier));
        } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            wanted.add(mapView.get(cell));
        } else {
            return; // 雙擊、數字鍵、丟出：不拿
        }
        // 從後面拿起，前面的位置才不會跑掉
        wanted.sort(java.util.Comparator.reverseOrder());
        int given = 0;
        boolean full = false;
        for (final int index : wanted) {
            final ItemStack map = stash.maps().get(index);
            if (player.getInventory().firstEmpty() < 0) {
                full = true;
                break;
            }
            final Map<Integer, ItemStack> rest = player.getInventory().addItem(map.clone());
            if (!rest.isEmpty()) {
                full = true;
                break;
            }
            stash.takeMap(index);
            given++;
        }
        if (given == 0) {
            player.sendMessage(plugin.text().prefixed("<red>背包滿了"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (given > 1 || full) {
            player.sendActionBar(plugin.text().parse("<green>拿出 " + given + " 張地圖" + (full ? "<yellow>（背包滿了）" : "")));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.3f);
        redraw();
    }

    /** 點背包裡的地圖存進地圖頁。 */
    private void depositMap(final PlayerInventory inventory, final int slot) {
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (tierOf(stack) == 0) {
            player.sendActionBar(plugin.text().parse("<gray>地圖頁只能放地圖，其他東西請放在物品頁"));
            return;
        }
        if (!stash.depositMap(stack)) {
            player.sendMessage(plugin.text().prefixed("<red>地圖頁滿了（" + stash.mapCap() + " 張；贊助可以加大）"));
            return;
        }
        inventory.setItem(slot, null);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
        redraw();
    }

    /** 背包與快捷列裡的地圖全部存進地圖頁。 */
    private void depositAllMaps() {
        final PlayerInventory inventory = player.getInventory();
        int total = 0;
        boolean full = false;
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || tierOf(stack) == 0) {
                continue;
            }
            if (!stash.depositMap(stack)) {
                full = true;
                break;
            }
            inventory.setItem(slot, null);
            total++;
        }
        if (total > 0) {
            player.sendActionBar(plugin.text().parse("<green>存入 " + total + " 張地圖" + (full ? "<yellow>（地圖頁滿了）" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
        } else {
            player.sendActionBar(plugin.text().parse(full ? "<red>地圖頁滿了" : "<gray>背包裡沒有地圖"));
        }
        redraw();
    }

    // ---- 點擊 ----

    @Override
    protected void handleRawClick(final InventoryClickEvent event) {
        final int raw = event.getRawSlot();
        if (searching() && page < CURRENCY_PAGE) {
            // 搜尋中只能看：點符合的物品或「結束搜尋」回到一般畫面
            event.setCancelled(true);
            if (raw >= 0 && raw < PAGE_SLOTS) {
                if (matches(stash.page(page)[raw])) {
                    endSearch();
                }
            } else if (raw >= NAV_START && raw < NAV_START + 9) {
                onNav(raw, event.getClick());
            }
            return;
        }
        if (page == MAP_PAGE) {
            event.setCancelled(true);
            if (raw >= 0 && raw < MAP_START) {
                onTierTab(raw);
            } else if (raw >= MAP_START && raw < PAGE_SLOTS) {
                withdrawMap(raw - MAP_START, event.getClick());
            } else if (raw >= NAV_START && raw < NAV_START + 9) {
                onNav(raw, event.getClick());
            } else if (raw >= NAV_START + 9 && event.getClickedInventory() instanceof PlayerInventory inventory) {
                depositMap(inventory, event.getSlot());
            }
            return;
        }
        if (page == CURRENCY_PAGE) {
            event.setCancelled(true);
            if (raw >= 0 && raw < CURRENCY_START) {
                onGroupTab(raw);
            } else if (raw >= CURRENCY_START && raw < PAGE_SLOTS) {
                withdraw(raw - CURRENCY_START, event.getClick());
            } else if (raw >= NAV_START && raw < NAV_START + 9) {
                onNav(raw, event.getClick());
            } else if (raw >= NAV_START + 9 && event.getClickedInventory() instanceof PlayerInventory inventory) {
                deposit(inventory, event.getSlot());
            }
            return;
        }
        if (raw >= NAV_START && raw < NAV_START + 9) {
            event.setCancelled(true);
            onNav(raw, event.getClick());
            return;
        }
        if (stash.locked(page) && !takesOutOnly(event, raw)) {
            // 收回的頁（2026-10）：只能拿出來
            event.setCancelled(true);
            player.sendActionBar(plugin.text().parse("<yellow>這一頁已經收回：只能把東西拿出來"));
            return;
        }
        // 其他（這一頁的 45 格、自己的背包）照原版操作
    }

    /** 收回的頁上，這一下只是把東西拿出來（拿到游標、Shift 移到背包），沒有放東西進這一頁。 */
    private static boolean takesOutOnly(final InventoryClickEvent event, final int raw) {
        final org.bukkit.event.inventory.InventoryAction action = event.getAction();
        if (raw >= PAGE_SLOTS) {
            // 自己的背包：Shift 會移進這一頁，不行；其他照常
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
        if (searching()) {
            event.setCancelled(true);
            return;
        }
        for (final int raw : event.getRawSlots()) {
            if (page < CURRENCY_PAGE && stash.locked(page) && raw < PAGE_SLOTS) {
                event.setCancelled(true); // 收回的頁不能放
                return;
            }
            if (page >= CURRENCY_PAGE ? raw < NAV_START + 9 : raw >= NAV_START && raw < NAV_START + 9) {
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
        final int pages = stash.pageCount();
        if (slot == SLOT_TAB) {
            if (page >= CURRENCY_PAGE) {
                switchTo(lastItemPage);
            } else if (click.isRightClick()) {
                editTab(page);
            } else if (click.isShiftClick()) {
                // 2026-10：跳到下一個還有空格的頁（頭尾相接；全滿就留在這頁）
                for (int step = 1; step < pages; step++) {
                    final int candidate = (page + step) % pages;
                    if (stash.itemCount(candidate) < PAGE_SLOTS) {
                        switchTo(candidate);
                        return;
                    }
                }
                player.sendActionBar(plugin.text().parse("<red>其他頁都滿了"));
            } else {
                new StashTabsMenu(plugin, player, service, stash, page, query()).open();
            }
        } else if ((slot == SLOT_PREVIOUS_PAGE || slot == SLOT_NEXT_PAGE) && page == MAP_PAGE) {
            final int batches = mapBatches();
            if (batches > 1) {
                mapBatch = Math.floorMod(mapBatch + (slot == SLOT_NEXT_PAGE ? 1 : -1), batches);
                redraw();
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
            }
        } else if (slot == SLOT_MAP_PAGE) {
            switchTo(MAP_PAGE);
        } else if (page == MAP_PAGE && slot == SLOT_DEPOSIT_MAPS) {
            depositAllMaps();
        } else if ((slot == SLOT_PREVIOUS_PAGE || slot == SLOT_NEXT_PAGE) && page < CURRENCY_PAGE && pages > 1) {
            switchTo(stepPage(page, pages, slot == SLOT_NEXT_PAGE, click));
        } else if (slot == SLOT_CURRENCY_PAGE) {
            switchTo(CURRENCY_PAGE);
        } else if (page == CURRENCY_PAGE && slot == SLOT_DEPOSIT_CURRENCY) {
            depositAll();
        } else if (page == CURRENCY_PAGE && slot == SLOT_DEPOSIT_POUCH) {
            depositPouch();
        } else if (page < CURRENCY_PAGE && slot == SLOT_SEARCH) {
            plugin.dialogs().text(player, "<dark_gray>倉庫搜尋", List.of("<gray>名稱、詞綴、基底都找得到；空白隔開＝每個都要有"),
                    "關鍵字", query, 50,
                    text -> service.open(player, false, text, page, true, page), () -> service.open(player, false, null, page));
        } else if (page < CURRENCY_PAGE && slot == SLOT_SORT) {
            if (searching()) {
                endSearch();
            } else if (acceptAction(click)) {
                if (click.isRightClick()) {
                    pageToBag();
                } else {
                    sort();
                }
            }
        } else if (slot == SLOT_DEPOSIT) {
            if (acceptAction(click)) {
                depositEverything(click.isRightClick());
            }
        } else if (slot == SLOT_CLOSE) {
            goBack();
        }
    }

    /** 通貨頁最上面一排的分組頁籤。 */
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

    /** 改頁籤的名稱與分類（跳出輸入框；改完回到那一頁）。 */
    void editTab(final int tab) {
        final String keepSearch = searching() ? query : null;
        editTab(plugin, player, stash, tab, () -> service.open(player, false, keepSearch, tab), () -> service.open(player, false, keepSearch, page));
    }

    /**
     * 改頁籤的名稱與分類（倉庫與全部頁籤一覽共用）：跳出輸入框，改好存檔後跑 done，取消跑 cancel（各自回到原本的畫面）。
     */
    static void editTab(final ExileCore plugin, final Player player, final Stash stash, final int tab, final Runnable done,
                        final Runnable cancel) {
        final List<tw.exilecore.gui.Dialogs.Option> options = new ArrayList<>();
        for (final StashCategory category : StashCategory.tabChoices()) {
            options.add(new tw.exilecore.gui.Dialogs.Option(category.name(),
                    category.displayName() + (category == StashCategory.ALL ? "（什麼都放）" : "")));
        }
        plugin.dialogs().form(player, "<dark_gray>頁籤：" + ItemRenderer.escape(stash.tabLabel(tab)),
                List.of("<gray>「收進倉庫」和「整理全部頁」照分類放東西：",
                        "<gray>先放分類一樣的頁，再放大類（防具、飾品、其他）的頁，最後放「全部」的頁",
                        "<dark_gray>名稱空白＝第 " + (tab + 1) + " 頁"),
                List.of(new tw.exilecore.gui.Dialogs.Text("name", "名稱（最多 " + Stash.TAB_NAME_MAX + " 個字）", stash.tabName(tab),
                                Stash.TAB_NAME_MAX),
                        new tw.exilecore.gui.Dialogs.Choice("category", "放什麼", options, stash.tabCategory(tab).name())),
                "好", answers -> {
                    stash.setTab(tab, answers.get("name"), StashCategory.parseTab(answers.get("category")));
                    plugin.players().markDirty(player);
                    plugin.characters().save(player);
                    done.run();
                }, cancel);
    }

    /** 結束搜尋：停在目前這一頁，回到可以搬東西的畫面。 */
    private void endSearch() {
        terms = List.of();
        query = "";
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
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

    // ---- 通貨頁 ----

    private void withdraw(final int slot, final ClickType click) {
        if (slot >= currencyOrder.size()) {
            return;
        }
        final CurrencyType type = currencyOrder.get(slot);
        final int have = stash.currency(type.id());
        if (have <= 0) {
            return;
        }
        final int want = switch (click) {
            case LEFT -> Math.min(have, type.stack());
            case RIGHT -> 1;
            case SHIFT_LEFT, SHIFT_RIGHT -> have;
            default -> 0; // 雙擊多送的那一下、數字鍵、丟出：不拿（以前雙擊會多拿兩疊）
        };
        if (want <= 0) {
            return;
        }
        int remaining = want;
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
            player.sendMessage(plugin.text().prefixed("<red>背包滿了"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        stash.setCurrency(type.id(), have - given);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.3f);
        redraw();
    }

    private void deposit(final PlayerInventory inventory, final int slot) {
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final Optional<CurrencyType> type = plugin.currencies().read(stack);
        if (type.isEmpty()) {
            player.sendActionBar(plugin.text().parse("<gray>通貨頁只能放通貨，物品請放在物品頁"));
            return;
        }
        final int accepted = stash.depositCurrency(type.get().id(), stack.getAmount());
        if (accepted <= 0) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(type.get().name()) + "已經存滿 " + stash.currencyCap() + " 個"));
            return;
        }
        final int left = stack.getAmount() - accepted;
        inventory.setItem(slot, left <= 0 ? null : stack.asQuantity(left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
        redraw();
    }

    /** 隨身背包通貨袋裡的通貨全部存進倉庫（跟角色存檔同一個交易，不會兩邊都有或都沒有）。 */
    private void depositPouch() {
        final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
        final int total = StashOrganizer.depositPouch(stash, pouch);
        final boolean capped = pouch.kinds() > 0; // 還有剩：那幾種在倉庫裡已經存滿
        if (total > 0) {
            player.sendActionBar(plugin.text().parse("<green>存入 " + total + " 個通貨" + (capped ? "<gray>（有的已經存滿）" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
        } else {
            player.sendActionBar(plugin.text().parse(capped ? "<red>倉庫裡這些通貨已經存滿" : "<gray>通貨袋是空的"));
        }
        redraw();
    }

    /** 整理這一頁：疊好排好（全部頁照分類重新分頁在全部頁籤一覽）。 */
    private void sort() {
        syncToModel();
        organizer.sort(List.of(StashOrganizer.Slots.of(stash.page(page))));
        redraw();
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        player.sendActionBar(plugin.text().parse("<green>這一頁整理好了"));
    }

    /** 這一頁（畫面上看到的）全部放進隨身背包（2026-10）：先疊到同樣的，再放空格；通貨進通貨袋；放不下的留在倉庫。 */
    private void pageToBag() {
        syncToModel();
        // 倉庫開著時隨身背包不會同時開著（一次只能開一個視窗），直接改隨身背包的資料
        final tw.exilecore.character.Bag bag = plugin.players().get(player).bag();
        if (!bag.usable()) {
            player.sendActionBar(plugin.text().parse("<red>隨身背包讀不到，先不要搬"));
            return;
        }
        final ItemStack[] items = stash.page(page);
        int moved = 0;
        boolean full = false;
        for (int i = 0; i < items.length; i++) {
            final ItemStack stack = items[i];
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
                rest = tw.exilecore.loot.LootService.mergeInto(bag.page(p), rest);
            }
            for (int p = 0; p < bag.pages() && rest != null; p++) {
                rest = tw.exilecore.loot.LootService.fillEmpty(bag.page(p), rest);
            }
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
            player.sendActionBar(plugin.text().parse("<green>放進隨身背包 " + moved + " 格" + (full ? "<red>，隨身背包滿了，剩下的留在倉庫" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.1f);
        } else {
            player.sendActionBar(plugin.text().parse(full ? "<red>隨身背包滿了" : "<gray>這一頁沒有東西"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        }
    }

    /** 收進倉庫：背包上面三排，或隨身背包（兩頁＋通貨袋）。 */
    private void depositEverything(final boolean fromBag) {
        syncToModel();
        final StashOrganizer.Deposit result;
        int pouchCurrency = 0;
        if (fromBag) {
            // 倉庫開著時隨身背包不會同時開著（一次只能開一個視窗），直接改隨身背包的資料
            final tw.exilecore.character.Bag bag = plugin.players().get(player).bag();
            pouchCurrency = StashOrganizer.depositPouch(stash, bag.pouch());
            if (bag.usable()) {
                int stacks = 0;
                int currency = 0;
                int leftover = 0;
                final List<StashCategory> noPage = new ArrayList<>();
                for (int p = 0; p < bag.pages(); p++) {
                    final StashOrganizer.Deposit part = organizer.deposit(stash, StashOrganizer.Slots.of(bag.page(p)));
                    stacks += part.stacks();
                    currency += part.currency();
                    leftover += part.leftover();
                    for (final StashCategory category : part.noPage()) {
                        if (!noPage.contains(category)) {
                            noPage.add(category);
                        }
                    }
                }
                result = new StashOrganizer.Deposit(stacks, currency, leftover, noPage);
            } else {
                result = new StashOrganizer.Deposit(0, 0, 0, List.of());
            }
        } else {
            final PlayerInventory inventory = player.getInventory();
            result = organizer.deposit(stash, new StashOrganizer.Slots() {
                @Override
                public int size() {
                    return INVENTORY_END - INVENTORY_MAIN_START;
                }

                @Override
                public ItemStack get(final int index) {
                    return inventory.getItem(INVENTORY_MAIN_START + index);
                }

                @Override
                public void set(final int index, final ItemStack stack) {
                    inventory.setItem(INVENTORY_MAIN_START + index, stack);
                }
            });
        }
        redraw();
        final int currency = result.currency() + pouchCurrency;
        if (result.nothing() && pouchCurrency == 0) {
            player.sendActionBar(plugin.text().parse(result.leftover() > 0 ? "<red>放不下：" + leftoverReason(result)
                    : fromBag ? "<gray>隨身背包是空的" : "<gray>背包上面三排沒有東西"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        final StringBuilder text = new StringBuilder("<green>收進 ");
        if (result.stacks() > 0) {
            text.append(result.stacks()).append(" 格物品");
        }
        if (currency > 0) {
            text.append(result.stacks() > 0 ? "、" : "").append(currency).append(" 個通貨");
        }
        if (result.leftover() > 0) {
            text.append("<yellow>；").append(result.leftover()).append(" 格放不下（").append(leftoverReason(result)).append("）");
        }
        player.sendActionBar(plugin.text().parse(text.toString()));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
    }

    private static String leftoverReason(final StashOrganizer.Deposit result) {
        if (!result.noPage().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final StashCategory category : result.noPage()) {
                names.add(category.displayName());
            }
            return String.join("、", names) + "沒有對應的頁籤";
        }
        return "對應的頁滿了，或通貨存滿了";
    }

    private void depositAll() {
        final PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Optional<CurrencyType> type = plugin.currencies().read(stack);
            if (type.isEmpty()) {
                continue;
            }
            final int accepted = stash.depositCurrency(type.get().id(), stack.getAmount());
            if (accepted > 0) {
                total += accepted;
                final int left = stack.getAmount() - accepted;
                inventory.setItem(slot, left <= 0 ? null : stack.asQuantity(left));
            }
        }
        if (total > 0) {
            player.sendActionBar(plugin.text().parse("<green>存入 " + total + " 個通貨"));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.9f);
        } else {
            player.sendActionBar(plugin.text().parse("<gray>背包裡沒有可以存的通貨"));
        }
        redraw();
    }
}
