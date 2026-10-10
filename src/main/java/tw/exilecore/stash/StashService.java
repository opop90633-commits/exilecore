package tw.exilecore.stash;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.npc.NpcType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 倉庫：登入時從資料庫載入整個帳號的倉庫，之後都在記憶體裡操作；
 * 存檔跟著角色存檔一起（同一個交易），所以背包與倉庫永遠是同一個時間點的樣子，搬東西時當機也不會複製或消失。
 */
public final class StashService {

    /** 倉庫管理員附近幾格內可以用 /stash。 */
    private static final double NPC_RANGE = 6;

    private final ExileCore plugin;
    private final StashRepository repository = new StashRepository();
    private final Map<UUID, Stash> stashes = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private final Set<UUID> failed = new HashSet<>();
    private final Map<UUID, StashMenu> open = new HashMap<>();

    public StashService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    public StashRepository repository() {
        return repository;
    }

    // ---- 載入與卸載 ----

    public void load(final Player player) {
        final UUID uuid = player.getUniqueId();
        loading.add(uuid);
        failed.remove(uuid);
        plugin.database().async(connection -> repository.load(connection, uuid)).whenComplete((snapshot, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    loading.remove(uuid);
                    if (plugin.getServer().getPlayer(uuid) != player) {
                        return; // 已經登出（或重新登入換了新物件，新的那次會自己載入）
                    }
                    if (error != null) {
                        failed.add(uuid);
                        plugin.getSLF4JLogger().error("載入 {} 的倉庫失敗", player.getName(), error);
                        return;
                    }
                    try {
                        final Stash stash = decode(snapshot);
                        refreshItems(stash);
                        stashes.put(uuid, stash);
                    } catch (final RuntimeException e) {
                        // 讀不出來就不載入：不開放使用、也不會存檔，資料庫裡的資料保持原樣等管理員處理
                        failed.add(uuid);
                        plugin.getSLF4JLogger().error("{} 的倉庫資料損毀，暫停使用倉庫", player.getName(), e);
                    }
                }));
    }

    public void unload(final Player player) {
        stashes.remove(player.getUniqueId());
        loading.remove(player.getUniqueId());
        failed.remove(player.getUniqueId());
        open.remove(player.getUniqueId());
    }

    public Optional<Stash> stash(final Player player) {
        final Stash stash = stashes.get(player.getUniqueId());
        if (stash != null && plugin.sponsors() != null) {
            stash.setMapCap(Stash.MAP_CAP + plugin.sponsors().extraMapSlots(player)); // 2026-10：星幣加大的地圖頁
            stash.setCurrencyCap(Stash.CURRENCY_CAP + plugin.sponsors().extraStashCurrency(player)); // 星幣加大的通貨頁
        }
        return Optional.ofNullable(stash);
    }

    // ---- 存檔（角色存檔的延伸） ----

    /**
     * 主執行緒：把倉庫拍成快照，回傳要在角色存檔交易裡執行的工作；倉庫沒載入回傳 null（不存）。
     * 倉庫正開著時先把畫面上的內容同步回來，快照才會跟背包一致。
     */
    public CharacterRepository.ExtraWork snapshot(final Player player) {
        final UUID uuid = player.getUniqueId();
        final Stash stash = stashes.get(uuid);
        if (stash == null) {
            return null;
        }
        final StashMenu menu = open.get(uuid);
        if (menu != null) {
            menu.syncToModel();
        }
        final StashRepository.Snapshot snapshot = encode(stash);
        return connection -> repository.save(connection, uuid, snapshot);
    }

    static StashRepository.Snapshot encode(final Stash stash) {
        final Map<Integer, byte[]> pages = new HashMap<>();
        for (int page = 0; page < stash.pageCount(); page++) {
            final ItemStack[] items = new ItemStack[Stash.PAGE_SIZE];
            final ItemStack[] source = stash.page(page);
            for (int i = 0; i < Stash.PAGE_SIZE; i++) {
                items[i] = source[i] == null || source[i].isEmpty() ? ItemStack.empty() : source[i];
            }
            pages.put(Stash.storageKey(page), ItemStack.serializeItemsAsBytes(items));
        }
        final Map<Integer, StashRepository.Tab> tabs = new HashMap<>();
        for (int page = 0; page < stash.pageCount(); page++) {
            if (!stash.tabName(page).isEmpty() || stash.tabCategory(page) != StashCategory.ALL) {
                tabs.put(page, new StashRepository.Tab(stash.tabName(page), stash.tabCategory(page).name()));
            }
        }
        // 地圖頁：整份存成一個特殊頁碼（空的也要存，拿光之後資料庫裡才不會留著舊的）
        final List<ItemStack> maps = stash.maps();
        final ItemStack[] mapItems = new ItemStack[Math.max(1, maps.size())];
        for (int i = 0; i < mapItems.length; i++) {
            mapItems[i] = i < maps.size() && maps.get(i) != null && !maps.get(i).isEmpty() ? maps.get(i) : ItemStack.empty();
        }
        pages.put(Stash.MAP_STORE_KEY, ItemStack.serializeItemsAsBytes(mapItems));
        return new StashRepository.Snapshot(pages, new HashMap<>(stash.currencies()), tabs);
    }

    /** 倉庫裡舊版畫的物品重畫、舊版的傳奇照目前的定義補正（下次存檔時一起存）。 */
    private void refreshItems(final Stash stash) {
        if (plugin.items() == null) {
            return;
        }
        final tw.exilecore.currency.CurrencyService currencies = plugin.currencies();
        if (plugin.skills() != null) {
            // 拿掉的技能、輔助寶石（2026-10）：換成未切割寶石，拆下來的輔助放進倉庫的空格
            final java.util.List<ItemStack[]> pages = new java.util.ArrayList<>();
            for (int page = 0; page < stash.pageCount(); page++) {
                pages.add(stash.page(page));
            }
            final java.util.List<ItemStack> leftover = plugin.skills().gems().migrateArrays(pages);
            if (!leftover.isEmpty()) {
                plugin.getSLF4JLogger().warn("寶石補正：倉庫放不下拆下來的輔助 {}（請手動補給）", plugin.skills().gems().describe(leftover));
            }
        }
        for (int page = 0; page < stash.pageCount(); page++) {
            plugin.items().replaceRetired(stash.page(page)); // 退役的傳奇換成傳奇兌換券（2026-10 PoE2 改版）
            for (final org.bukkit.inventory.ItemStack stack : stash.page(page)) {
                plugin.items().refresh(stack);
                if (currencies != null) {
                    currencies.refresh(stack); // 通貨：舊版做的補上貼圖標記、名稱改過的重做
                }
                if (stack != null && plugin.skills() != null) {
                    plugin.skills().gems().refresh(stack); // 寶石：技能、輔助改過名字的重畫
                }
            }
        }
        for (final ItemStack map : stash.maps()) {
            plugin.items().refresh(map);
        }
    }

    static Stash decode(final StashRepository.Snapshot snapshot) {
        final Stash stash = new Stash();
        // 資料庫裡有東西的頁都留著（多買的頁；就算功勳兌換的紀錄比較少也不會讓東西不見）。
        // 2026-10：地圖頁（{@link Stash#MAP_STORE_KEY}）不是倉庫頁——之前當成第 101 頁讀進來，倉庫變成 101 頁、還多出一份地圖；
        // 後面空的頁不算（那次多存的空頁），能用幾頁由功勳、贊助決定（打開倉庫時補上）
        final Map<Integer, ItemStack[]> decoded = new HashMap<>();
        int highest = -1;
        for (final Map.Entry<Integer, byte[]> entry : snapshot.pages().entrySet()) {
            final int page = Stash.pageOfKey(entry.getKey());
            if (page < 0 || page >= Stash.MAX_PAGES) {
                continue;
            }
            final ItemStack[] items = ItemStack.deserializeItemsFromBytes(entry.getValue());
            decoded.put(page, items);
            for (final ItemStack item : items) {
                if (item != null && !item.isEmpty()) {
                    highest = Math.max(highest, page);
                    break;
                }
            }
        }
        for (final Map.Entry<Integer, StashRepository.Tab> entry : snapshot.tabs().entrySet()) {
            if (entry.getKey() >= 0 && entry.getKey() < Stash.MAX_PAGES) {
                highest = Math.max(highest, entry.getKey()); // 改過名稱、分類的頁也留著
            }
        }
        stash.ensurePages(highest + 1);
        for (final Map.Entry<Integer, ItemStack[]> entry : decoded.entrySet()) {
            final int page = entry.getKey();
            if (page >= stash.pageCount()) {
                continue;
            }
            final ItemStack[] items = entry.getValue();
            final ItemStack[] target = stash.page(page);
            for (int i = 0; i < Math.min(items.length, Stash.PAGE_SIZE); i++) {
                target[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
            }
        }
        final byte[] mapBytes = snapshot.pages().get(Stash.MAP_STORE_KEY);
        if (mapBytes != null) {
            for (final ItemStack map : ItemStack.deserializeItemsFromBytes(mapBytes)) {
                if (map != null && !map.isEmpty() && stash.mapCount() < stash.mapCap()) {
                    stash.maps().add(map);
                }
            }
        }
        for (final Map.Entry<String, Integer> entry : snapshot.currency().entrySet()) {
            stash.setCurrency(entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<Integer, StashRepository.Tab> entry : snapshot.tabs().entrySet()) {
            final int page = entry.getKey();
            if (page >= 0 && page < stash.pageCount()) {
                stash.setTab(page, entry.getValue().name(), StashCategory.parseTab(entry.getValue().category()));
            }
        }
        return stash;
    }

    // ---- 打開 ----

    /**
     * 打開倉庫。{@code viaNpc} 為 true 表示是點倉庫管理員（不用檢查位置）；
     * /stash 只能在營地或倉庫管理員旁邊用。
     */
    public void open(final Player player, final boolean viaNpc) {
        open(player, viaNpc, null);
    }

    /** 打開倉庫並搜尋（search 是 null 或空白 = 不搜尋；有搜尋就跳到第一個有符合的頁）。 */
    public void open(final Player player, final boolean viaNpc, final String search) {
        open(player, viaNpc, search, 0, true, 0);
    }

    /** 打開倉庫、停在第 page 頁（輸入框、全部頁籤一覽回來時；搜尋中也不跳頁）。 */
    public void open(final Player player, final boolean viaNpc, final String search, final int page) {
        open(player, viaNpc, search, page, false, page);
    }

    /**
     * 打開倉庫。
     *
     * @param jump     有搜尋時跳到第一個有符合的頁（剛輸入關鍵字時）
     * @param itemPage 停在通貨頁時，「← 回物品頁」回到哪一頁
     */
    public void open(final Player player, final boolean viaNpc, final String search, final int page, final boolean jump,
                     final int itemPage) {
        final UUID uuid = player.getUniqueId();
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return;
        }
        if (open.containsKey(uuid) || player.getOpenInventory().getTopInventory().getHolder() instanceof StashMenu) {
            return; // 已經開著（改過的客戶端可以在視窗開著時送指令）：不開第二個，避免兩個畫面同時拿著同一份倉庫
        }
        final Stash stash = stashes.get(uuid);
        if (stash == null) {
            player.sendMessage(plugin.text().prefixed(failed.contains(uuid)
                    ? "<red>倉庫資料讀取失敗，請通知管理員（主控台有錯誤訊息）"
                    : "<gray>倉庫資料載入中，請稍等"));
            return;
        }
        if (!viaNpc && !canUseHere(player)) {
            player.sendMessage(plugin.text().prefixed("<red>倉庫只能在營地打開"));
            return;
        }
        ensurePages(player);
        new StashMenu(plugin, player, this, stash, search, page, jump, itemPage).open();
    }

    /** 功勳多換的倉庫頁補上（打開倉庫、剛換到時）。 */
    public void ensurePages(final Player player) {
        final Stash stash = stashes.get(player.getUniqueId());
        if (stash != null && plugin.sponsors() != null) {
            // 2026-10：星幣加大的地圖頁（打開倉庫、買了之後都套；以前只有部分地方套到，打開倉庫還是 1000）
            stash.setMapCap(Stash.MAP_CAP + plugin.sponsors().extraMapSlots(player));
            stash.setCurrencyCap(Stash.CURRENCY_CAP + plugin.sponsors().extraStashCurrency(player));
        }
        if (stash != null && plugin.bounties() != null) {
            // 2026-10：加上累積贊助多的頁
            final int sponsor = plugin.sponsors() == null ? 0 : plugin.sponsors().extraStashPages(player);
            final int entitled = Stash.BASE_PAGES + plugin.bounties().extraStashPages(player) + sponsor;
            stash.ensurePages(entitled);
            // 2026-10：收回累積贊助、買的頁之後頁數變少：後面的東西搬到前面，搬不完的頁鎖起來（只能拿出來）
            if (stash.pageCount() > entitled && plugin.sponsors() != null && plugin.sponsors().loaded(player)
                    && plugin.bounties().accountLoaded(player)) {
                final int locked = stash.shrinkTo(entitled);
                if (locked > 0) {
                    player.sendMessage(plugin.text().prefixed("<yellow>倉庫有 " + locked
                            + " 頁已經收回：裡面的東西前面放不下，只能拿出來（拿空就會消失）"));
                }
            }
        }
    }

    private boolean canUseHere(final Player player) {
        if (player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            return true;
        }
        if (plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
            return true;
        }
        for (final Entity entity : player.getNearbyEntities(NPC_RANGE, NPC_RANGE, NPC_RANGE)) {
            if (plugin.npcs().type(entity).orElse(null) == NpcType.STASH) {
                return true;
            }
        }
        return false;
    }

    void opened(final Player player, final StashMenu menu) {
        open.put(player.getUniqueId(), menu);
    }

    void closed(final Player player, final StashMenu menu) {
        open.remove(player.getUniqueId(), menu);
    }

    /**
     * 模組停用（關伺服器）：把開著的倉庫同步回來再關掉。停用中的插件收不到事件，StashMenu.onClose 不會跑，
     * 所以這裡直接同步；選單保留在清單裡，之後角色模組的最後一次存檔拍快照時還會再同步一次（內容相同）。
     */
    public void closeAll() {
        for (final Map.Entry<UUID, StashMenu> entry : Map.copyOf(open).entrySet()) {
            entry.getValue().returnCursor(); // 收不到關閉事件：游標上的東西先放回去
            entry.getValue().syncToModel();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                player.closeInventory();
            }
        }
    }
}
