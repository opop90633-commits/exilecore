package tw.exilecore.character;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 帳號與角色的載入、切換、建立、刪除與存檔。
 * <p>
 * 所有資料庫存取都在背景執行緒；主執行緒只做「把狀態拍成快照」與「把載入結果套到玩家身上」。
 * 同一條背景執行緒依序執行，所以「先存 A 再讀 B」的順序不會亂。
 * <p>
 * 交易、市集、通貨交易所這類「動到別人或共用資料」的操作走 {@link #commit}：參與的玩家的存檔跟操作的資料庫工作
 * 放在同一個交易。萬一寫入失敗，交易系統整個暫停（{@link #economyFrozen()}），參與的玩家之後的存檔先不寫
 * （不然會寫進「拿到了東西、但市集那邊沒扣掉」的半套狀態），每 15 秒把卡住的操作連同他們最新的存檔一起重試，成功才恢復。
 */
public final class CharacterService {

    /**
     * 角色配點是照哪一版天賦樹配的（tree.json 的 version）。跟現在的版本不一樣（或沒記過、而現在不是第 1 版）時，
     * 載入時把天賦點全部退回：改版後節點代號的意思不一樣了，留著會配到奇怪的地方。
     */
    public static final String PROGRESS_TREE_VERSION = "tree.version";
    /** 建角色時選的新手武器（基底代號）；有這筆、沒有 {@link #PROGRESS_STARTER_KIT} 的角色載入時送新手裝備。 */
    public static final String PROGRESS_STARTER_WEAPON = "starter.weapon";
    /** 跟著新手武器決定的起始技能（技能代號）；技能模組送起始技能時優先用它。 */
    public static final String PROGRESS_STARTER_SKILL = "starter.skill";
    /** "1" = 新手裝備送過了。 */
    public static final String PROGRESS_STARTER_KIT = "starter.kit";

    private final ExileCore plugin;
    private final PlayerStateService states;
    private final CharacterConfig config;
    private final CharacterRepository repository = new CharacterRepository();
    /** 每個帳號的角色清單（摘要列，依格子排序）。 */
    private final Map<UUID, List<CharacterRecord>> rosters = new HashMap<>();
    /** 正在載入或切換中的玩家，期間不接受第二個切換請求。 */
    private final Set<UUID> busy = new HashSet<>();
    private final NamespacedKey legacyLevelKey;

    // ---- 經濟操作的存檔（見 commit）----

    /** 寫入失敗、等重試的一筆操作（只在資料庫執行緒上讀寫）。 */
    private record Deferred(String opId, Set<UUID> members, CharacterRepository.ExtraWork work) {
    }

    /** 重試間隔（tick）。 */
    private static final long RETRY_TICKS = 20L * 15;
    /** 有操作寫入失敗：交易系統暫停（背景執行緒設定、主執行緒讀）。 */
    private volatile boolean economyFrozen;
    /** 有卡住的操作的玩家：他們自己的存檔先不寫，等重試一起寫。 */
    private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    /** 卡住的操作，依發生順序（只在資料庫執行緒上碰；加鎖以防萬一）。 */
    private final List<Deferred> deferred = new ArrayList<>();
    /** 還在排隊寫入的操作數（主執行緒）。 */
    private final Map<UUID, Integer> inflight = new HashMap<>();
    /** 有操作還沒寫完就登出的玩家，登出那一刻的存檔（重試時用）。 */
    private final Map<UUID, CharacterRepository.SaveJob> frozenSnapshots = new ConcurrentHashMap<>();
    /** 卡住的操作重試成功時通知（市集用來確認上架、收入）。 */
    private final List<Consumer<Set<String>>> recoveryListeners = new ArrayList<>();
    private BukkitTask retryTask;
    private boolean retrying;
    /** 還原備份中：不寫任何存檔（還原完的資料不能被記憶體裡的舊資料蓋掉）。 */
    private volatile boolean writesPaused;

    private final Gear gear;

    public CharacterService(final ExileCore plugin, final PlayerStateService states, final CharacterConfig config) {
        this.plugin = plugin;
        this.states = states;
        this.config = config;
        this.legacyLevelKey = new NamespacedKey(plugin, "level");
        this.gear = new Gear(plugin);
    }

    /** 角色面板的裝備欄（武器、副手、防具）。 */
    public Gear gear() {
        return gear;
    }

    public CharacterRepository repository() {
        return repository;
    }

    /** 這個帳號有幾個角色格：設定的基本數加上功勳換的。 */
    public int slotsFor(final Player player) {
        final int extra = plugin.bounties() == null ? 0 : plugin.bounties().extraCharacterSlots(player);
        // 2026-10：加上贊助買的角色欄位（沒有上限）
        final int sponsor = plugin.sponsors() == null ? 0 : plugin.sponsors().extraCharacterSlots(player);
        return Math.min(CharacterConfig.MAX_SLOTS, config.characterSlots() + extra) + sponsor;
    }

    public CharacterConfig config() {
        return config;
    }

    /**
     * 這個人目前職業的掉落調整（2026-10，drops.yml 的 class_bias）：照職業的新手裝備（character.yml 的 starter.classes）
     * 算出自己職業的基底類；沒有角色、掉落模組沒開 = 不調整。
     */
    public tw.exilecore.item.LootBias lootBias(final Player player) {
        if (player == null || plugin.monsterModule() == null || plugin.items() == null) {
            return tw.exilecore.item.LootBias.NONE;
        }
        final PlayerState state = plugin.players().get(player);
        if (state == null || !state.hasCharacter()) {
            return tw.exilecore.item.LootBias.NONE;
        }
        final CharacterConfig.StarterClass starter = config.starter(state.character().characterClass());
        return plugin.monsterModule().drops().classBias(plugin.items().repository(), starter.recommended(), starter.armour(), starter.shield());
    }

    /** 帳號裡的角色（摘要）。 */
    public List<CharacterRecord> roster(final Player player) {
        return rosters.getOrDefault(player.getUniqueId(), List.of());
    }

    public Optional<CharacterRecord> rosterEntry(final Player player, final int slot) {
        for (final CharacterRecord record : roster(player)) {
            if (record.slot() == slot) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    public boolean isBusy(final Player player) {
        return busy.contains(player.getUniqueId());
    }

    /** 這個玩家有交易、市集的操作還沒寫進資料庫（排隊中或寫入失敗等重試）：不能換角色。 */
    public boolean hasUnsavedTrades(final Player player) {
        final UUID uuid = player.getUniqueId();
        return inflight.containsKey(uuid) || blocked.contains(uuid);
    }

    /** 交易系統是否因為寫入失敗暫停中。 */
    public boolean economyFrozen() {
        return economyFrozen;
    }

    /**
     * 能不能做交易、市集、通貨交易所的操作：角色載入好了、沒有在切換、交易系統沒有暫停、資料庫連著。
     *
     * @return null = 可以；否則是給玩家看的原因
     */
    public String transactionProblem(final Player player) {
        final PlayerState state = states.get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return "先選一個角色（/char）";
        }
        if (busy.contains(player.getUniqueId())) {
            return "角色資料處理中，請稍等";
        }
        if (economyFrozen) {
            return "交易系統暫停中（資料庫寫入失敗，正在重試），請稍後再試";
        }
        if (!plugin.database().isConnected()) {
            return "資料庫沒有連線，暫時不能交易";
        }
        return null;
    }

    /** 還原備份：之後不再寫存檔（直到 {@link #resumeWrites()} 或重開伺服器）。 */
    public void pauseWrites() {
        writesPaused = true;
    }

    public void resumeWrites() {
        writesPaused = false;
    }

    /** 是不是暫停存檔中（還原備份）。任何執行緒都可以看。 */
    public boolean writesPaused() {
        return writesPaused;
    }

    /** 卡住的操作重試成功時呼叫（參數是那些操作的代號）。 */
    public void onRecovered(final Consumer<Set<String>> listener) {
        recoveryListeners.add(listener);
    }

    // ---- 登入 ----

    /** 登入：讀帳號與角色清單，有使用中的角色就直接載入，沒有就提示建角色。 */
    public void onJoin(final Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        if (blocked.contains(uuid) || inflight.containsKey(uuid)) {
            // 上次登出前的交易還沒寫進資料庫：現在載入會拿到舊的資料
            sync(() -> player.kick(plugin.text().parse("<yellow>你上次的交易還在存檔中，請一分鐘後再登入")));
            return;
        }
        frozenSnapshots.remove(uuid); // 操作都寫好了：登出時留的快照用不到了
        busy.add(uuid);
        plugin.database().async(connection -> {
            final CharacterRepository.Account account = repository.loadOrCreateAccount(connection, uuid, name);
            final List<CharacterRecord> roster = repository.listCharacters(connection, uuid);
            CharacterRepository.Loaded active = null;
            if (account.activeCharacter() != null) {
                active = repository.loadCharacter(connection, account.activeCharacter()).orElse(null);
            }
            return new JoinResult(account, roster, active);
        }).whenComplete((result, error) -> sync(() -> {
            if (!stillOnline(player)) {
                return;
            }
            busy.remove(uuid);
            if (error != null) {
                plugin.getSLF4JLogger().error("載入 {} 的角色失敗", name, error);
                player.sendMessage(plugin.text().prefixed("<red>角色資料載入失敗，請通知管理員（主控台有錯誤訊息）"));
                return;
            }
            rosters.put(uuid, result.roster());
            final PlayerState state = states.get(player);
            state.setGold(result.account().gold()); // 帳號共用的金幣
            state.setLoaded(true);
            if (result.active() != null) {
                apply(player, state, result.active(), true);
            } else if (result.roster().isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<gray>你還沒有角色，先選一個職業吧"));
                plugin.characterMenus().openSelect(player);
            } else {
                player.sendMessage(plugin.text().prefixed("<gray>輸入 <white>/char</white> 選擇要使用的角色"));
                plugin.characterMenus().openSelect(player);
            }
        }));
    }

    private record JoinResult(CharacterRepository.Account account, List<CharacterRecord> roster, CharacterRepository.Loaded active) {
    }

    /** 把載入的角色套到玩家身上：狀態、背包、飾品、配點、資源。 */
    /**
     * 舊版畫的物品（ItemRenderer.RENDER_VERSION）重畫一次：背包與身上、飾品欄、隨身背包。資料不動，只更新名稱、描述與元件
     * （例如詞綴改成顯示排名、地圖名稱帶階級、武器的攻擊距離）。倉庫裡的東西拿出來、下次換角色或登入時才更新。
     */
    private void refreshItems(final Player player, final PlayerState state) {
        final tw.exilecore.item.ItemService items = plugin.items();
        if (items == null) {
            return;
        }
        final tw.exilecore.currency.CurrencyService currencies = plugin.currencies();
        final PlayerInventory inventory = player.getInventory();
        retireUniques(player, state, items);
        for (int i = 0; i < inventory.getSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (stack == null) {
                continue;
            }
            boolean changed = items.refresh(stack);
            if (!changed && currencies != null) {
                changed = currencies.refresh(stack); // 通貨：舊版做的補上貼圖標記（資源包靠它挑圖）、名稱改過的重做
            }
            changed |= refreshGem(player, stack); // 寶石：技能、輔助改過名字的重畫；拿掉的換成未切割寶石
            if (changed) {
                inventory.setItem(i, stack);
            }
        }
        for (final ItemStack stack : state.jewellery()) {
            items.refresh(stack);
        }
        for (final ItemStack stack : state.jewels().values()) {
            items.refresh(stack);
        }
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            for (final ItemStack stack : state.gearSet(set)) {
                if (stack != null) {
                    items.refresh(stack);
                }
            }
            for (final ItemStack stack : state.treeJewels(set).values()) {
                items.refresh(stack);
            }
        }
        if (state.bag() != null && state.bag().usable() && plugin.skills() != null) {
            // 拿掉的技能、輔助寶石（2026-10）：換成未切割寶石，拆下來的輔助放進隨身背包的空格，放不下的放進背包
            final List<ItemStack[]> pages = new ArrayList<>();
            for (int page = 0; page < state.bag().storedPages(); page++) {
                pages.add(state.bag().page(page));
            }
            final List<ItemStack> leftover = plugin.skills().gems().migrateArrays(pages);
            if (!leftover.isEmpty()) {
                plugin.skills().gems().giveAll(player, leftover);
            }
        }
        if (state.bag() != null && state.bag().usable()) {
            for (int page = 0; page < state.bag().storedPages(); page++) {
                for (final ItemStack stack : state.bag().page(page)) {
                    items.refresh(stack);
                    if (currencies != null) {
                        currencies.refresh(stack);
                    }
                    refreshGem(stack);
                }
            }
        }
    }

    /** 背包的格子數（0–35；36 起是護甲與副手）。 */
    private static final int STORAGE_SLOTS = 36;

    /**
     * 2026-10 PoE2 改版：退役的傳奇換成傳奇兌換券。背包裡的原地換；穿在身上的（護甲、副手、飾品欄、沒在用的裝備組）
     * 拿下來，兌換券放進背包（放不下的掉在腳邊）。離線補正換到裝備欄裡的兌換券也一起拿出來。
     */
    private void retireUniques(final Player player, final PlayerState state, final tw.exilecore.item.ItemService items) {
        final PlayerInventory inventory = player.getInventory();
        final List<ItemStack> give = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            final ItemStack voucher = items.retiredReplacement(stack);
            if (i < STORAGE_SLOTS) {
                if (voucher != null) {
                    inventory.setItem(i, voucher);
                    count++;
                }
            } else if (voucher != null || isVoucher(stack)) {
                inventory.setItem(i, null);
                give.add(voucher != null ? voucher : stack);
                count += voucher != null ? 1 : 0;
            }
        }
        for (int i = 0; i < state.jewellery().length; i++) {
            final ItemStack stack = state.jewellery(i);
            final ItemStack voucher = items.retiredReplacement(stack);
            if (voucher != null || isVoucher(stack)) {
                state.setJewellery(i, null);
                give.add(voucher != null ? voucher : stack);
                count += voucher != null ? 1 : 0;
            }
        }
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final ItemStack[] gear = state.gearSet(set);
            for (int i = 0; i < gear.length; i++) {
                final ItemStack voucher = items.retiredReplacement(gear[i]);
                if (voucher != null || isVoucher(gear[i])) {
                    give.add(voucher != null ? voucher : gear[i]);
                    count += voucher != null ? 1 : 0;
                    gear[i] = null;
                }
            }
        }
        if (state.bag() != null && state.bag().usable()) {
            for (int page = 0; page < state.bag().storedPages(); page++) {
                count += items.replaceRetired(state.bag().page(page));
            }
        }
        for (final ItemStack stack : give) {
            for (final ItemStack rest : inventory.addItem(stack).values()) {
                plugin.personalDrops().dropForever(player, rest);
            }
        }
        if (count > 0) {
            plugin.players().markDirty(player);
            player.sendMessage(plugin.text().prefixed("<yellow>傳奇改版：你身上、隨身背包裡的 " + count
                    + " 件舊傳奇換成了傳奇兌換券（拿在主手右鍵，選一件新的傳奇）。倉庫裡的打開倉庫時會換"));
        }
    }

    private boolean isVoucher(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && plugin.currencies() != null
                && plugin.currencies().read(stack).map(type -> tw.exilecore.item.ItemService.VOUCHER_ID.equals(type.id())).orElse(false);
    }

    /** 寶石物品照現在的技能、輔助名稱重畫（技能模組沒啟用時什麼都不做）。 */
    private boolean refreshGem(final Player player, final ItemStack stack) {
        if (stack == null || plugin.skills() == null) {
            return false;
        }
        final java.util.List<ItemStack> extra = plugin.skills().gems().migrateRemoved(stack);
        if (extra != null) {
            // 放回去之後才給拆下來的輔助（不然可能剛好放進還沒看過的格子，再看一次也沒關係）
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.skills().gems().giveAll(player, extra));
            return true;
        }
        return plugin.skills().gems().refresh(stack);
    }

    private boolean refreshGem(final ItemStack stack) {
        return stack != null && plugin.skills() != null && plugin.skills().gems().refresh(stack);
    }

    private void apply(final Player player, final PlayerState state, final CharacterRepository.Loaded loaded, final boolean replaceInventory) {
        final CharacterRecord record = loaded.record();
        final Set<String> passives = new HashSet<>(loaded.passives());
        final PassiveTree tree = plugin.passives().tree();
        boolean newTree = false;
        if (tree != null) {
            if (treeOutdated(tree, loaded.progress().get(PROGRESS_TREE_VERSION))) {
                newTree = true;
                if (!passives.isEmpty()) {
                    player.sendMessage(plugin.text().prefixed("<yellow>天賦樹改版了（七個職業的新天賦樹）：這個角色的 " + passives.size()
                            + " 點天賦全部退回，打 <white>/tree</white> 重新配"));
                    passives.clear();
                }
            }
            final PassiveNode start = tree.start(record.characterClass().id());
            if (start != null) {
                final Set<String> removed = tree.prune(passives, start);
                if (!removed.isEmpty()) {
                    player.sendMessage(plugin.text().prefixed("<yellow>天賦樹改版，有 " + removed.size() + " 個配點已退回"));
                }
            }
        }
        state.setCharacter(record, passives, loaded.progress(), loaded.skills());
        if (newTree) {
            stampTreeVersion(state, tree, true);
        }
        // 經驗表改版換算過等級的角色：先說明（下面接著會列出還給你的珠寶、需求不夠的裝備）
        announceLevelConversion(player, state);
        final String bonus = config.experienceBonusText();
        if (bonus != null && record.level() < config.levelCap()) {
            player.sendMessage(plugin.text().prefixed("<#ffd27f>" + (config.experienceBonus() > 1 ? "全服經驗加倍中" : "全服經驗減少中")
                    + "：打怪、委託拿到的經驗 " + bonus));
        }
        if (replaceInventory) {
            try {
                InventoryCodec.applyInventory(player, loaded.inventory().contents());
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的背包資料損毀，改用空背包", record.name(), e);
                player.getInventory().clear();
            }
        }
        ItemStack[] jewellery;
        try {
            jewellery = InventoryCodec.decodeJewellery(loaded.inventory().jewellery(), PlayerState.STORED_SLOTS);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("{} 的飾品資料損毀，改用空欄位", record.name(), e);
            jewellery = new ItemStack[PlayerState.STORED_SLOTS];
        }
        for (int i = 0; i < jewellery.length; i++) {
            state.setJewellery(i, jewellery[i]);
        }
        Bag bag;
        try {
            bag = InventoryCodec.decodeBag(loaded.inventory().bag(), loaded.inventory().bagCurrency());
        } catch (final RuntimeException e) {
            // 讀不出來：物品頁暫停使用，存檔時把原本的資料原封不動寫回去，等管理員處理（通貨袋照常）
            plugin.getSLF4JLogger().error("{} 的隨身背包資料損毀，這次登入暫停使用物品頁（資料保留）", record.name(), e);
            bag = new Bag(CurrencyPouch.decode(loaded.inventory().bagCurrency()));
            bag.markUnreadable(loaded.inventory().bag());
            player.sendMessage(plugin.text().prefixed("<red>隨身背包的資料讀取失敗，物品頁暫停使用，請通知管理員"));
        }
        state.setBag(bag);
        // 天賦樹珠寶孔裡的珠寶：孔位沒配（樹改版、退點）的還給玩家
        Map<String, ItemStack> jewels;
        try {
            jewels = InventoryCodec.decodeJewels(loaded.inventory().jewels());
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("{} 的珠寶資料損毀，改用空的", record.name(), e);
            jewels = Map.of();
        }
        for (final Map.Entry<String, ItemStack> entry : jewels.entrySet()) {
            // 2026-10：沒在用的裝備組跟珠寶存在同一欄（代號 gear:組:格）
            if (entry.getKey().startsWith(TREE_PREFIX)) {
                // 沒在用的天賦組的珠寶（代號 tree:組:節點）
                final String rest = entry.getKey().substring(TREE_PREFIX.length());
                final int colon = rest.indexOf(':');
                try {
                    final int set = Integer.parseInt(rest.substring(0, colon));
                    if (set >= 0 && set < PlayerState.GEAR_SETS) {
                        state.treeJewels(set).put(rest.substring(colon + 1), entry.getValue());
                        continue;
                    }
                } catch (final RuntimeException ignored) {
                    // 壞掉的代號：當成一般的珠寶
                }
            }
            if (entry.getKey().startsWith(GEAR_PREFIX)) {
                final String[] parts = entry.getKey().substring(GEAR_PREFIX.length()).split(":");
                try {
                    final int set = Integer.parseInt(parts[0]);
                    final int slot = Integer.parseInt(parts[1]);
                    if (set >= 0 && set < PlayerState.GEAR_SETS && slot >= 0 && slot < PlayerState.JEWELLERY_SLOTS.length) {
                        state.gearSet(set)[slot] = entry.getValue();
                        continue;
                    }
                } catch (final RuntimeException ignored) {
                    // 壞掉的代號：當成一般的珠寶處理（會被還給玩家）
                }
            }
            state.jewels().put(entry.getKey(), entry.getValue());
        }
        tw.exilecore.passive.JewelSockets.reclaim(plugin, player, state, tree, "天賦樹的珠寶孔沒有配置");
        refreshItems(player, state);
        states.rebuild(player, state);
        if (record.life() <= 0) {
            // 沒存過（-1）或存檔時已死亡（0）：回滿
            state.restoreAll();
        } else {
            state.setLife(Math.max(1, record.life()));
            state.setMana(record.mana());
            state.setEnergyShield(record.energyShield());
        }
        states.display(player, state);
        states.updateListName(player); // TAB 列表：等級與職業
        player.sendMessage(plugin.text().prefixed("<gray>使用角色 " + record.characterClass().color() + escape(record.name())
                + " <gray>（" + record.characterClass().displayName() + " Lv." + record.level() + "）　<dark_gray>Shift＋F 或 /menu 打開選單"));
        if (plugin.skills() != null) {
            plugin.skills().onCharacterApplied(player, state); // 起始技能、清掉上一個角色的施放狀態
        }
        if (plugin.flasks() != null) {
            plugin.flasks().onCharacterApplied(player, state); // 起始藥劑放進藥劑欄、清掉回復中的效果
        }
        if (plugin.ailments() != null) {
            plugin.ailments().clear(player); // 上一個角色身上的點燃、冰緩…不帶過來
        }
        if (plugin.quests() != null) {
            plugin.quests().onCharacterApplied(player); // 自動開始的任務（序章）、任務追蹤
        }
        if (plugin.bounties() != null) {
            plugin.bounties().onCharacterApplied(player); // 今天的每日委託、稱號
        }
        // 裝備改版：舊角色手上、身上的裝備搬進角色面板（只做一次；新角色什麼都沒有，只記下來）
        gear.migrate(player, state);
        if (state.progress().containsKey(PROGRESS_STARTER_WEAPON) && !"1".equals(state.progress().get(PROGRESS_STARTER_KIT))) {
            // 新角色；或建立時剛好斷線、當機，還沒拿到新手裝備的角色（下次載入補送）
            giveStarterKit(player, state);
        }
        gear.sync(player);
        states.rebuild(player, state);
        for (final Consumer<Player> hook : afterApply) {
            try {
                hook.accept(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("套用角色之後的處理失敗（{}）", player.getName(), e);
            }
        }
        if (ClassSplit.pending(record.characterClass(), state.progress())) {
            // 原本的混沌術士：等登入的訊息、標題跑完再問要當元素法師還是混沌術士
            final long id = record.id();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!stillOnline(player)) {
                    return; // 下線了（或重新登入成另一個 Player）：不要替他建一個新的狀態
                }
                final PlayerState current = states.find(player.getUniqueId()).orElse(null);
                if (current != null && current.hasCharacter() && current.character().id() == id) {
                    offerWitchSplit(player);
                }
            }, 60L);
        }
    }

    // ---- 天賦樹改版 ----

    /** 這個角色的配點是舊版天賦樹的嗎（沒記過版本的角色是第 1 版配的）。 */
    private static boolean treeOutdated(final PassiveTree tree, final String recorded) {
        return tw.exilecore.passive.TreeVersion.outdated(tree.version(), recorded);
    }

    /**
     * 記下這個角色照現在這一版配點（退回的配點也一起寫進資料庫，同一個資料庫工作：當機也不會留著舊的配點又記成新版）。
     *
     * @param clearPassives 資料庫裡的配點也清掉
     */
    private void stampTreeVersion(final PlayerState state, final PassiveTree tree, final boolean clearPassives) {
        final CharacterRecord record = state.character();
        if (record == null) {
            return;
        }
        final String version = String.valueOf(tree.version());
        state.progress().put(PROGRESS_TREE_VERSION, version);
        final long id = record.id();
        plugin.database().async(connection -> {
            repository.inTransaction(connection, () -> {
                if (clearPassives) {
                    repository.savePassives(connection, id, Set.of());
                }
                repository.setProgress(connection, id, PROGRESS_TREE_VERSION, version);
            });
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("記錄天賦樹版本失敗（角色 {}）", id, error);
            }
        });
    }

    /** /ec reload 換了一版天賦樹：線上的角色照登入時一樣全部退點。 */
    public void treeReloaded() {
        final PassiveTree tree = plugin.passives().tree();
        if (tree == null) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = states.get(player);
            if (!state.hasCharacter() || !treeOutdated(tree, state.progress().get(PROGRESS_TREE_VERSION))) {
                continue;
            }
            if (!state.passives().isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<yellow>天賦樹改版了：這個角色的 " + state.passives().size()
                        + " 點天賦全部退回，打 <white>/tree</white> 重新配"));
                state.passives().clear();
                state.markDirty();
            }
            tw.exilecore.passive.JewelSockets.reclaim(plugin, player, state, tree, "天賦樹改版");
            stampTreeVersion(state, tree, true);
        }
    }

    // ---- 混沌術士分成兩個職業（元素法師、混沌術士）----

    /** 原本的混沌術士：跳出對話框問一次要當元素法師還是留在混沌術士（/char witch 也能打開）。 */
    public void offerWitchSplit(final Player player) {
        final PlayerState state = states.get(player);
        final CharacterRecord record = state.character();
        if (record == null || !ClassSplit.pending(record.characterClass(), state.progress())) {
            player.sendMessage(plugin.text().prefixed("<gray>這個角色不用選（只有職業分成兩個以前建的混沌術士要選一次）"));
            return;
        }
        final long id = record.id();
        final List<String> body = new ArrayList<>();
        body.add("<gray>以前的混沌術士也教元素法術，現在分成了兩個職業。這個角色可以選一次，<red>選了不能改</red>：");
        body.add("");
        body.add(CharacterClass.SORCERESS.color() + "元素法師<gray>：元素法術（火、冰、電）");
        body.add("<dark_gray>　昇華職業：" + escape(ascendancyNames(CharacterClass.SORCERESS)));
        body.add(CharacterClass.WITCH.color() + "混沌術士<gray>：混沌法術與血");
        body.add("<dark_gray>　昇華職業：" + escape(ascendancyNames(CharacterClass.WITCH)));
        body.add("");
        body.add("<gray>起始屬性一樣；等級、裝備、技能、倉庫都不變。");
        final String ascendancy = chosenAscendancyName(state);
        if (ascendancy != null) {
            final String owner = chosenAscendancyClass(state);
            final boolean keepsWithSorceress = !ClassSplit.dropsAscendancy(owner, CharacterClass.SORCERESS);
            final boolean keepsWithWitch = !ClassSplit.dropsAscendancy(owner, CharacterClass.WITCH);
            body.add("<yellow>你的昇華職業是" + escape(ascendancy) + "：選元素法師" + (keepsWithSorceress ? "會留著" : "會退掉")
                    + "，選混沌術士" + (keepsWithWitch ? "會留著" : "會退掉") + "（退掉的話昇華點還你，可以重選）。");
        }
        body.add("<dark_gray>先關掉也可以：下次登入會再問，或打 /char witch");
        plugin.dialogs().notice(player, "<#b07cff>選一次職業：元素法師或混沌術士", body,
                List.of(tw.exilecore.gui.Dialogs.Button.run(CharacterClass.SORCERESS.color() + "當元素法師",
                                () -> confirmWitchSplit(player, id, CharacterClass.SORCERESS)),
                        tw.exilecore.gui.Dialogs.Button.run(CharacterClass.WITCH.color() + "留在混沌術士",
                                () -> confirmWitchSplit(player, id, CharacterClass.WITCH))), 2);
    }

    private void confirmWitchSplit(final Player player, final long id, final CharacterClass target) {
        final PlayerState state = states.get(player);
        if (state.character() == null || state.character().id() != id) {
            return; // 對話框開著的時候換了角色
        }
        final List<String> body = new ArrayList<>();
        body.add("<red>選了就不能改。");
        if (target == CharacterClass.SORCERESS) {
            body.add("<gray>職業變成元素法師：天賦樹的起點換到元素法師那裡，天賦點全部退回（可以重配）。");
        }
        final String ascendancy = chosenAscendancyName(state);
        if (ascendancy != null && ClassSplit.dropsAscendancy(chosenAscendancyClass(state), target)) {
            body.add("<yellow>昇華職業" + escape(ascendancy) + "會退掉，昇華點還你。");
        }
        plugin.dialogs().confirm(player, "<#f5b942>確定要當" + target.color() + target.displayName() + "<#f5b942>？", body,
                "確定", () -> applyWitchSplit(player, id, target), () -> offerWitchSplit(player));
    }

    /** 這個職業的昇華職業名稱「星火術士、虛衣祭司」（照設定檔）。 */
    private String ascendancyNames(final CharacterClass characterClass) {
        if (plugin.ascendancy() == null) {
            return "—";
        }
        final List<String> names = new ArrayList<>();
        for (final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy ascendancy : plugin.ascendancy().config().forClass(characterClass.id())) {
            names.add(ascendancy.name());
        }
        return names.isEmpty() ? "—" : String.join("、", names);
    }

    /**
     * 管理員換職業（/ec setclass）：天賦全部退回（珠寶還給玩家）、昇華職業退掉（昇華點還在，/ascend 重選）、
     * 混沌術士的二選一記成選過了。等級、裝備、技能、通貨都不動。回傳錯誤訊息；成功 null。
     */
    public String adminChangeClass(final Player player, final CharacterClass target) {
        final PlayerState state = states.get(player);
        final CharacterRecord record = state.character();
        if (record == null) {
            return "這個玩家還沒有選角色";
        }
        if (busy.contains(player.getUniqueId()) || hasUnsavedTrades(player)) {
            return "這個玩家的角色資料處理中（交易或存檔），等一下再試";
        }
        if (record.characterClass() == target) {
            return "已經是" + target.displayName() + "了";
        }
        final CharacterClass from = record.characterClass();
        final long id = record.id();
        record.setCharacterClass(target);
        updateRoster(player.getUniqueId(), record);
        state.passives().clear();
        state.markDirty();
        tw.exilecore.passive.JewelSockets.reclaim(plugin, player, state, plugin.passives().tree(), "換了職業，天賦全部退回");
        state.progress().put(ClassSplit.PROGRESS, target.id());
        final boolean hadAscendancy = state.progress().containsKey(tw.exilecore.ascendancy.Ascension.CHOSEN);
        state.progress().remove(tw.exilecore.ascendancy.Ascension.CHOSEN);
        state.progress().remove(tw.exilecore.ascendancy.Ascension.NODES);
        // 天賦全部退回了：屬性點的選擇也清掉（重新配的照新職業的主屬性）
        final List<String> attrKeys = new java.util.ArrayList<>();
        for (final String key : state.progress().keySet()) {
            if (key.startsWith("attr.")) {
                attrKeys.add(key);
            }
        }
        attrKeys.forEach(state.progress()::remove);
        plugin.database().async(connection -> {
            repository.inTransaction(connection, () -> {
                repository.updateClass(connection, id, target);
                repository.savePassives(connection, id, Set.of());
                repository.setProgress(connection, id, ClassSplit.PROGRESS, target.id());
                repository.deleteProgress(connection, id, tw.exilecore.ascendancy.Ascension.CHOSEN);
                repository.deleteProgress(connection, id, tw.exilecore.ascendancy.Ascension.NODES);
                for (final String key : attrKeys) {
                    repository.deleteProgress(connection, id, key);
                }
            });
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("寫入換職業失敗（角色 {}）", id, error);
            }
        });
        plugin.players().markDirty(player);
        plugin.players().sheet(player);
        states.updateListName(player);
        save(player);
        if (plugin.quests() != null) {
            plugin.quests().updateTracker(player);
        }
        player.showTitle(net.kyori.adventure.title.Title.title(plugin.text().parse(target.color() + target.displayName()),
                plugin.text().parse("<gray>" + escape(target.description()))));
        player.sendMessage(plugin.text().prefixed("<gray>管理員把這個角色的職業換成 " + target.color() + target.displayName()
                + "<gray>：天賦點全部退回了（/tree 重配）" + (hadAscendancy ? "；昇華職業退掉了，昇華點還你（/ascend 重選）" : "")));
        plugin.getSLF4JLogger().info("[管理] {} 的角色 {} 職業 {} → {}", player.getName(), record.name(), from.id(), target.id());
        return null;
    }

    /** 寫進選擇：職業（元素法師才改）與選過的記號在同一個資料庫工作裡；昇華職業不屬於新職業的退掉。 */
    private void applyWitchSplit(final Player player, final long characterId, final CharacterClass target) {
        if (!stillOnline(player)) {
            return;
        }
        final PlayerState state = states.get(player);
        final CharacterRecord record = state.character();
        if (record == null || record.id() != characterId || !ClassSplit.pending(record.characterClass(), state.progress())
                || !ClassSplit.allowed(target)) {
            return; // 已經選過了（兩個對話框同時開著之類）、或對話框開著的時候換了角色
        }
        if (busy.contains(player.getUniqueId()) || hasUnsavedTrades(player)) {
            player.sendMessage(plugin.text().prefixed("<gray>角色資料處理中，請稍等一下再選（/char witch）"));
            return;
        }
        final boolean changesClass = target != record.characterClass();
        final boolean dropsAscendancy = ClassSplit.dropsAscendancy(chosenAscendancyClass(state), target);
        final long id = record.id();
        if (changesClass) {
            record.setCharacterClass(target);
            updateRoster(player.getUniqueId(), record);
            state.passives().clear(); // 起點換了：天賦全部退回
            state.markDirty();
            tw.exilecore.passive.JewelSockets.reclaim(plugin, player, state, plugin.passives().tree(), "換了職業，天賦全部退回");
        }
        state.progress().put(ClassSplit.PROGRESS, target.id());
        if (dropsAscendancy) {
            state.progress().remove(tw.exilecore.ascendancy.Ascension.CHOSEN);
            state.progress().remove(tw.exilecore.ascendancy.Ascension.NODES);
        }
        plugin.database().async(connection -> {
            repository.inTransaction(connection, () -> {
                if (changesClass) {
                    repository.updateClass(connection, id, target);
                    repository.savePassives(connection, id, Set.of());
                }
                repository.setProgress(connection, id, ClassSplit.PROGRESS, target.id());
                if (dropsAscendancy) {
                    repository.deleteProgress(connection, id, tw.exilecore.ascendancy.Ascension.CHOSEN);
                    repository.deleteProgress(connection, id, tw.exilecore.ascendancy.Ascension.NODES);
                }
            });
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("寫入混沌術士的職業選擇失敗（角色 {}）", id, error);
            }
        });
        plugin.players().sheet(player);
        states.updateListName(player);
        save(player);
        if (plugin.quests() != null) {
            plugin.quests().updateTracker(player);
        }
        player.showTitle(net.kyori.adventure.title.Title.title(plugin.text().parse(target.color() + target.displayName()),
                plugin.text().parse("<gray>" + escape(target.description()))));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
        player.sendMessage(plugin.text().prefixed("<gray>這個角色" + (changesClass ? "現在是 " : "留在 ") + target.color()
                + target.displayName() + (changesClass ? "<gray>，天賦點全部退回了（/tree 重配）" : "")
                + (dropsAscendancy ? "<gray>；昇華職業退掉了，昇華點還你（/ascend 重選）" : "")));
        plugin.getSLF4JLogger().info("{} 的混沌術士角色 {} 選了 {}", player.getName(), record.name(), target.id());
    }

    /** 選的昇華職業的名字（沒選、或昇華模組沒啟用回傳 null）。 */
    private String chosenAscendancyName(final PlayerState state) {
        final String id = state.progress().get(tw.exilecore.ascendancy.Ascension.CHOSEN);
        if (id == null || plugin.ascendancy() == null) {
            return null;
        }
        final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy ascendancy = plugin.ascendancy().config().ascendancy(id);
        return ascendancy == null ? null : ascendancy.name();
    }

    /** 選的昇華職業屬於哪個職業（沒選、設定檔裡沒有了回傳 null）。 */
    private String chosenAscendancyClass(final PlayerState state) {
        final String id = state.progress().get(tw.exilecore.ascendancy.Ascension.CHOSEN);
        if (id == null || plugin.ascendancy() == null) {
            return null;
        }
        final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy ascendancy = plugin.ascendancy().config().ascendancy(id);
        return ascendancy == null ? null : ascendancy.classId();
    }

    // ---- 進度 ----

    /**
     * 直接寫某個角色（用編號，不一定是現在這隻）的進度到資料庫（2026-10：換角色時把上一隻還沒存的統計補存）。
     * 不動記憶體裡的進度；那隻角色下次載入時從資料庫讀。
     */
    public void setProgressFor(final long characterId, final String key, final String value) {
        plugin.database().async(connection -> {
            repository.setProgress(connection, characterId, key, value);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("寫入角色 {} 的進度 {} 失敗", characterId, key, error);
            }
        });
    }

    /** 記一筆角色進度（檢查點、Boss 首殺…），馬上寫進資料庫。 */
    public void setProgress(final Player player, final String key, final String value) {
        final PlayerState state = states.get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            return;
        }
        final boolean fresh = state.progress().put(key, value) == null;
        if (key.startsWith(tw.exilecore.ascendancy.Ascension.TRIAL_PREFIX)) {
            state.markDirty(); // 第四試煉：元素抗性懲罰（2026-10）
            if (fresh && key.equals(tw.exilecore.ascendancy.Ascension.trialKey(plugin.players().fourthTrial()))) {
                player.sendMessage(plugin.text().prefixed("<#ff7a5a>第一次完成第四試煉：火焰、冰冷、閃電抗性 -"
                        + PlayerStateService.RESIST_PENALTY_STEP + "%<gray>（抗性懲罰，現在共 <white>-"
                        + plugin.players().resistPenalty(state) + "%</white>）"));
            }
        }
        final long id = character.id();
        plugin.database().async(connection -> {
            repository.setProgress(connection, id, key, value);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("寫入角色進度 {} 失敗", key, error);
            }
        });
    }

    /**
     * 經驗表改版時換算過等級的角色（資料庫遷移 12、13，{@link LevelConversion}）：第一次登入說明換算結果，說完刪掉那筆進度。
     * 等級一樣的說明只會是遷移 13 補回原本的等級（遷移 12 降過級、天賦已經退回）。
     */
    private void announceLevelConversion(final Player player, final PlayerState state) {
        final String note = state.progress().get(LevelConversion.PROGRESS_KEY);
        if (note != null) {
            final int[] levels = LevelConversion.parseNote(note);
            if (levels != null) {
                final boolean lowered = levels[1] < levels[0];
                final String passives = "；天賦已經免費全部退回，打 <white>/tree</white> 重新配（昇華天賦不變）";
                if (levels[1] == levels[0]) {
                    player.sendMessage(plugin.text().prefixed("<yellow>經驗表改版了（練等變難）：這個角色照以前殺怪的量換算，等級一樣是 <white>Lv."
                            + levels[0] + "</white>" + passives));
                } else {
                    player.sendMessage(plugin.text().prefixed("<yellow>經驗表改版了（練等變難）：這個角色照以前殺怪的量，從 <white>Lv." + levels[0]
                            + "</white> 換算成 <white>Lv." + levels[1] + "</white>" + (lowered ? passives : "")));
                }
                if (lowered) {
                    player.sendMessage(plugin.text().prefixed("<gray>等級需求比現在高的裝備先不生效（聊天欄會列出是哪幾件），升到需求等級就恢復"));
                }
            }
            removeProgress(player, LevelConversion.PROGRESS_KEY);
        }
        final String boost = state.progress().get(LevelConversion.BOOST_KEY);
        if (boost != null) {
            final int[] levels = LevelConversion.parseNote(boost);
            if (levels != null) {
                player.sendMessage(plugin.text().prefixed("<#ffd27f>全服經驗加倍：舊角色的等級換算改成照加倍後的速度，這個角色從 <white>Lv." + levels[0]
                        + "</white> 補到 <white>Lv." + levels[1] + "</white>；多的天賦點打 <white>/tree</white> 配"));
            }
            removeProgress(player, LevelConversion.BOOST_KEY);
        }
    }

    /** 刪掉一筆角色進度（記憶體與資料庫）。 */
    public void removeProgress(final Player player, final String key) {
        final PlayerState state = states.get(player);
        final CharacterRecord character = state.character();
        if (character == null || state.progress().remove(key) == null) {
            return;
        }
        if (key.startsWith(tw.exilecore.ascendancy.Ascension.TRIAL_PREFIX)) {
            state.markDirty();
        }
        final long id = character.id();
        plugin.database().async(connection -> {
            repository.deleteProgress(connection, id, key);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("刪除角色進度 {} 失敗", key, error);
            }
        });
    }

    // ---- 存檔 ----

    /**
     * 存檔時一起提交的額外工作（倉庫模組設定）：回傳 null 表示這個玩家沒有要一起存的東西。
     * 在主執行緒呼叫，要在這裡把資料拍成快照。
     */
    private java.util.function.Function<Player, CharacterRepository.ExtraWork> saveExtension = player -> null;

    public void setSaveExtension(final java.util.function.Function<Player, CharacterRepository.ExtraWork> extension) {
        this.saveExtension = extension;
    }

    /** 拍快照之前要先做的事（例如把開著的隨身背包畫面同步回背包），在主執行緒呼叫。 */
    private final List<Consumer<Player>> beforeSave = new ArrayList<>();

    /** 角色套用到玩家身上之後（登入、建立、切換）要做的事，在主執行緒呼叫（例如領管理員發的物資）。 */
    private final List<Consumer<Player>> afterApply = new ArrayList<>();

    public void addAfterApply(final Consumer<Player> hook) {
        afterApply.add(hook);
    }

    public void addBeforeSave(final Consumer<Player> hook) {
        beforeSave.add(hook);
    }

    /**
     * 主執行緒：把目前的角色拍成存檔快照（角色列、背包、飾品、隨身背包、配點、技能、帳號金幣、倉庫）。
     * 沒角色或還沒載入完回傳 null。
     */
    public CharacterRepository.SaveJob snapshot(final Player player) {
        final PlayerState state = states.get(player);
        final CharacterRecord character = state.character();
        if (!state.isLoaded() || character == null) {
            return null;
        }
        for (final Consumer<Player> hook : beforeSave) {
            try {
                hook.accept(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("存檔前的同步失敗（{}）", player.getName(), e);
            }
        }
        character.setResources(state.life(), state.mana(), state.energyShield());
        character.setPlaytimeSeconds(character.playtimeSeconds() + state.sessionSeconds());
        state.resetSession();
        final CharacterRecord snapshot = character.snapshot();
        updateRoster(player.getUniqueId(), snapshot);
        final Bag bag = state.bag();
        // 游標上的東西：背包有空格就算在背包，沒有就算在隨身背包（存檔後剛好當機也不會消失）
        final ItemStack cursor = player.getItemOnCursor();
        final ItemStack cursorForBag = cursor.isEmpty() || InventoryCodec.hasEmptySlot(player) ? null : cursor;
        final CharacterRepository.InventoryBlob blob = new CharacterRepository.InventoryBlob(
                InventoryCodec.encodeInventory(player), InventoryCodec.encodeJewellery(state.jewellery()),
                bag.unreadable() != null ? bag.unreadable() : InventoryCodec.encodeBag(bag, cursorForBag), bag.pouch().encode(),
                InventoryCodec.encodeJewels(withGearSets(state)));
        return new CharacterRepository.SaveJob(player.getUniqueId(), snapshot, blob, Set.copyOf(state.passives()),
                state.skills().copy(), state.gold(), saveExtension.apply(player));
    }

    /** 沒在用的裝備組在珠寶那一欄的代號前綴。 */
    static final String GEAR_PREFIX = "gear:";
    /** 沒在用的天賦組的珠寶的代號前綴。 */
    static final String TREE_PREFIX = "tree:";

    /** 珠寶＋沒在用的裝備組（存檔用）。 */
    private static Map<String, ItemStack> withGearSets(final PlayerState state) {
        final Map<String, ItemStack> all = new java.util.LinkedHashMap<>(state.jewels());
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final ItemStack[] items = state.gearSet(set);
            for (int slot = 0; slot < items.length; slot++) {
                if (items[slot] != null && !items[slot].isEmpty()) {
                    all.put(GEAR_PREFIX + set + ":" + slot, items[slot]);
                }
            }
            for (final Map.Entry<String, ItemStack> jewel : state.treeJewels(set).entrySet()) {
                all.put(TREE_PREFIX + set + ":" + jewel.getKey(), jewel.getValue());
            }
        }
        return all;
    }

    /** 存目前的角色（背景執行緒）。沒角色或還沒載入完就跳過；有交易卡住等重試的玩家也先不寫（重試時一起寫）。 */
    public CompletableFuture<Void> save(final Player player) {
        final CharacterRepository.SaveJob job = snapshot(player);
        if (job == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String name = player.getName();
        return plugin.database().async(connection -> {
            if (writesPaused) {
                return Boolean.FALSE; // 還原備份中
            }
            if (blocked.contains(job.account())) {
                return Boolean.FALSE; // 有還沒寫進去的交易：單獨存會存成半套，等重試一起寫
            }
            repository.saveAll(connection, List.of(job), null);
            return Boolean.TRUE;
        }).whenComplete((saved, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("存檔 {} 的角色 {} 失敗", name, job.record().name(), error);
            }
        }).thenApply(ignored -> null);
    }

    // ---- 經濟操作（交易、市集、通貨交易所）----

    /**
     * 經濟操作的存檔：work（交易紀錄、市集的上架或成交…）跟這些玩家的存檔在同一個資料庫交易。
     * 呼叫前先用 {@link #transactionProblem} 檢查，並且已經把記憶體裡的東西改好（拿走、給予）。
     * <p>
     * 寫入失敗時不還原記憶體：交易系統暫停，這筆操作與這些玩家記下來，之後他們的存檔先不寫，
     * 每 15 秒連同他們最新的存檔重試一次，成功才恢復（登出的人用登出那一刻的存檔）。
     *
     * @param opId 操作代號（市集用來確認上架、收入；可為 null）
     * @param what 給記錄看的說明
     * @return 主執行緒上完成：true = 寫進去了；false = 卡住了等重試
     */
    public CompletableFuture<Boolean> commit(final Collection<Player> players, final CharacterRepository.ExtraWork work,
                                             final String opId, final String what) {
        final Set<UUID> members = new LinkedHashSet<>();
        final List<CharacterRepository.SaveJob> jobs = new ArrayList<>();
        for (final Player player : players) {
            if (members.add(player.getUniqueId())) {
                final CharacterRepository.SaveJob job = snapshot(player);
                if (job != null) {
                    jobs.add(job);
                }
            }
        }
        for (final UUID uuid : members) {
            inflight.merge(uuid, 1, Integer::sum);
        }
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        plugin.database().async(connection -> {
            boolean anyBlocked = false;
            for (final UUID uuid : members) {
                anyBlocked |= blocked.contains(uuid);
            }
            if (writesPaused) {
                return Boolean.FALSE; // 還原備份中（沒有人在線，不會走到這裡）
            }
            if (economyFrozen || anyBlocked) {
                defer(opId, members, work); // 前面已經有卡住的操作：排在它們後面一起重試
                return Boolean.FALSE;
            }
            repository.saveAll(connection, jobs, work);
            return Boolean.TRUE;
        }, failure -> {
            // 在資料庫執行緒上：連線拿不到、寫入失敗都算，排在後面的存檔之前就把這筆操作記下來
            if (writesPaused) {
                return Boolean.FALSE;
            }
            plugin.getSLF4JLogger().error("{} 寫入資料庫失敗：交易系統暫停，之後每 15 秒重試到成功為止", what, failure);
            defer(opId, members, work);
            return Boolean.FALSE;
        }).whenComplete((committed, error) -> sync(() -> {
            for (final UUID uuid : members) {
                inflight.computeIfPresent(uuid, (key, count) -> count <= 1 ? null : count - 1);
            }
            final boolean ok = error == null && Boolean.TRUE.equals(committed);
            if (error != null) {
                plugin.getSLF4JLogger().error("{} 沒有排進資料庫（資料庫沒有連線？）", what, error);
            }
            if (ok) {
                forgetFrozen(members);
            } else {
                ensureRetry();
                for (final UUID uuid : members) {
                    final Player player = plugin.getServer().getPlayer(uuid);
                    if (player != null) {
                        player.sendMessage(plugin.text().prefixed(
                                "<red>交易存檔失敗，交易系統暫停，正在重試（你的東西都還在，先不要換角色）"));
                    }
                }
            }
            result.complete(ok);
        }));
        return result;
    }

    /** 資料庫執行緒：把操作記下來等重試，參與的玩家之後的存檔先不寫。 */
    private void defer(final String opId, final Set<UUID> members, final CharacterRepository.ExtraWork work) {
        synchronized (deferred) {
            deferred.add(new Deferred(opId, Set.copyOf(members), work));
            blocked.addAll(members);
            economyFrozen = true;
        }
    }

    private void ensureRetry() {
        if (retryTask == null) {
            retryTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::retryDeferred, RETRY_TICKS, RETRY_TICKS);
        }
    }

    /** 主執行緒：卡住的操作連同相關玩家最新的存檔（登出的用登出時的）一起重試。 */
    private void retryDeferred() {
        if (!economyFrozen && blocked.isEmpty()) {
            if (retryTask != null) {
                retryTask.cancel();
                retryTask = null;
            }
            return;
        }
        if (retrying) {
            return;
        }
        final Map<UUID, CharacterRepository.SaveJob> jobs = new LinkedHashMap<>();
        for (final UUID uuid : Set.copyOf(blocked)) {
            final Player player = plugin.getServer().getPlayer(uuid);
            final CharacterRepository.SaveJob job = player != null ? snapshot(player) : frozenSnapshots.get(uuid);
            if (job == null) {
                plugin.getSLF4JLogger().error("重試交易存檔：找不到 {} 的存檔快照（這個玩家的部分沒辦法一起寫）", uuid);
                continue;
            }
            jobs.put(uuid, job);
        }
        retrying = true;
        plugin.database().async(connection -> {
            if (writesPaused || !jobs.keySet().containsAll(blocked)) {
                return null; // 還原備份中；或期間又有人被擋下來（他們還沒有快照），下一輪再一起寫
            }
            final List<Deferred> works;
            synchronized (deferred) {
                works = List.copyOf(deferred);
            }
            repository.saveAll(connection, List.copyOf(jobs.values()), c -> {
                for (final Deferred entry : works) {
                    if (entry.work() != null) {
                        entry.work().run(c);
                    }
                }
            });
            synchronized (deferred) {
                deferred.removeAll(works);
                if (deferred.isEmpty()) {
                    blocked.clear();
                    economyFrozen = false;
                }
            }
            final Set<String> ops = new HashSet<>();
            for (final Deferred entry : works) {
                if (entry.opId() != null) {
                    ops.add(entry.opId());
                }
            }
            return ops;
        }).whenComplete((ops, error) -> sync(() -> {
            retrying = false;
            if (error != null) {
                plugin.getSLF4JLogger().error("重試交易存檔失敗，15 秒後再試", error);
                return;
            }
            if (ops == null) {
                return;
            }
            plugin.getSLF4JLogger().info("卡住的交易存檔已寫入（{} 筆操作），交易系統恢復", ops.size());
            forgetFrozen(jobs.keySet());
            for (final Consumer<Set<String>> listener : recoveryListeners) {
                try {
                    listener.accept(ops);
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().error("交易恢復的通知失敗", e);
                }
            }
            for (final UUID uuid : jobs.keySet()) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.sendMessage(plugin.text().prefixed("<green>交易存檔完成，交易系統恢復"));
                }
            }
        }));
    }

    /** 操作都寫好的玩家：登出時留的快照用不到了（在線的玩家重試時用身上的，也用不到）。 */
    private void forgetFrozen(final Collection<UUID> members) {
        for (final UUID uuid : members) {
            if (!inflight.containsKey(uuid) && !blocked.contains(uuid)) {
                frozenSnapshots.remove(uuid);
            }
        }
    }

    /** 這個玩家有寫入失敗、等重試的交易：不能丟東西、撿東西（不然東西會跑到別人的存檔裡）。 */
    public boolean tradeLocked(final Player player) {
        return blocked.contains(player.getUniqueId());
    }

    /** 所有在線玩家存檔（定時自動存檔、關伺服器前）；有卡住的交易時順便重試。 */
    public void saveAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            save(player);
        }
        if (economyFrozen) {
            retryDeferred();
        }
    }

    /** 登出：存檔後清掉狀態。有交易還沒寫完的話，先留下登出那一刻的存檔（重試時用）。 */
    public void onQuit(final Player player) {
        final UUID uuid = player.getUniqueId();
        if (inflight.containsKey(uuid) || blocked.contains(uuid)) {
            final CharacterRepository.SaveJob job = snapshot(player);
            if (job != null) {
                frozenSnapshots.put(uuid, job);
            }
        }
        save(player);
        rosters.remove(uuid);
        busy.remove(uuid);
        states.remove(player);
    }

    // ---- 建立、切換、刪除 ----

    /** 名稱已經有人用（建立、改名時查資料庫）。 */
    public static final String NAME_TAKEN = "這個名稱已經有人用了";

    /** 建立角色時名稱被用掉了（在資料庫執行緒上丟出，回主執行緒時告訴玩家）。 */
    private static final class NameTakenException extends RuntimeException {
        NameTakenException() {
            super(NAME_TAKEN, null, false, false);
        }
    }

    private static boolean nameTaken(final Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof NameTakenException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 存進資料庫的名稱：去掉前後空白、全形英數字換成半形（NFKC；「Ａlice」跟「Alice」是同一個名字，不能拿來冒充別人）。
     */
    public static String normalizeName(final String name) {
        return name == null ? "" : java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC).trim();
    }

    /** 名稱檢查（長度與字元；有沒有人用要查資料庫，建立、改名時才查）；合法回傳 null，否則回傳原因。 */
    public String validateName(final String name) {
        if (name == null) {
            return "名稱不能是空的";
        }
        final String trimmed = normalizeName(name);
        final int length = trimmed.codePointCount(0, trimmed.length());
        if (length < config.nameMin() || length > config.nameMax()) {
            return "名稱要 " + config.nameMin() + " 到 " + config.nameMax() + " 個字";
        }
        for (int i = 0; i < trimmed.length(); ) {
            final int codePoint = trimmed.codePointAt(i);
            if (!Character.isLetterOrDigit(codePoint) && codePoint != '_') {
                return "名稱只能用中英文、數字與底線";
            }
            i += Character.charCount(codePoint);
        }
        // 2026-10：辱罵、色情、歧視、仇恨的字與冒充管理員的名字（character.yml 的 name_filter 可以加字、放行）
        return config.nameFilter().problem(trimmed);
    }

    /**
     * 建立角色並切換過去，送新手裝備（選的武器、職業的防具、單手武器再送盾）。
     * 第一個角色會沿用玩家現在的背包（階段 1 測試拿到的東西不會消失），新手裝備只放進空的欄位；
     * 之後的角色從空背包開始。
     *
     * @param weaponBaseId 選單選的新手武器基底；null 或不能選時用第一個能選的
     */
    public void create(final Player player, final int slot, final CharacterClass characterClass, final String weaponBaseId,
                       final String name, final Consumer<String> onError) {
        final UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) {
            onError.accept("角色資料還在處理中，請稍等");
            return;
        }
        if (hasUnsavedTrades(player)) {
            onError.accept("交易還在存檔中，請稍等再換角色");
            return;
        }
        final PlayerState state = states.get(player);
        if (!state.isLoaded()) {
            onError.accept("角色資料還沒載入完成");
            return;
        }
        if (slot < 1 || slot > slotsFor(player)) {
            onError.accept("沒有第 " + slot + " 格");
            return;
        }
        if (rosterEntry(player, slot).isPresent()) {
            onError.accept("第 " + slot + " 格已經有角色了");
            return;
        }
        final String problem = validateName(name);
        if (problem != null) {
            onError.accept(problem);
            return;
        }
        final boolean firstCharacter = roster(player).isEmpty();
        final CharacterRecord record = new CharacterRecord(0, uuid, slot, normalizeName(name), characterClass);
        // 階段 1 的測試等級存在玩家 PDC，第一個角色沿用（建好了才拿掉：名稱被用掉時重來一次還在）
        final Integer legacyLevel = player.getPersistentDataContainer().get(legacyLevelKey, PersistentDataType.INTEGER);
        final boolean usesLegacyLevel = firstCharacter && legacyLevel != null;
        if (usesLegacyLevel) {
            record.setLevel(Math.min(config.levelCap(), legacyLevel));
        }
        // 只有帳號的第一個角色沿用目前的背包與飾品；其他情況（含使用中的角色載入失敗）都從空的開始，避免複製物品
        final boolean keepInventory = firstCharacter && !state.hasCharacter();
        final CharacterRepository.InventoryBlob initialInventory = keepInventory
                ? new CharacterRepository.InventoryBlob(InventoryCodec.encodeInventory(player), InventoryCodec.encodeJewellery(state.jewellery()))
                : new CharacterRepository.InventoryBlob(null, null);
        // 新手武器（選的那把不能選了就換第一個能選的）與跟著它的起始技能，記在角色進度裡
        final ItemBase weapon = StarterKits.resolveWeapon(plugin.items().repository(), config, characterClass, weaponBaseId);
        final Map<String, String> starterProgress = new HashMap<>();
        if (characterClass == CharacterClass.WITCH) {
            starterProgress.put(ClassSplit.PROGRESS, characterClass.id()); // 新的混沌術士知道自己選的是混沌，不用再問
        }
        if (plugin.passives().tree() != null) {
            starterProgress.put(PROGRESS_TREE_VERSION, String.valueOf(plugin.passives().tree().version()));
        }
        if (weapon != null) {
            starterProgress.put(PROGRESS_STARTER_WEAPON, weapon.id());
            final String skill = StarterKits.skill(config, characterClass, weapon);
            if (skill != null) {
                starterProgress.put(PROGRESS_STARTER_SKILL, skill);
            }
        }
        final Map<String, String> progress = Map.copyOf(starterProgress);

        busy.add(uuid);
        if (state.hasCharacter()) {
            save(player);
        }
        plugin.database().async(connection -> {
            if (repository.nameTaken(connection, record.name(), 0, uuid)) {
                throw new NameTakenException();
            }
            repository.createCharacter(connection, record, initialInventory, progress);
            return new CharacterRepository.Loaded(record, initialInventory, Set.of(), progress, new SkillLoadout());
        }).whenComplete((loaded, error) -> sync(() -> {
            if (!stillOnline(player)) {
                return;
            }
            busy.remove(uuid);
            if (error != null && nameTaken(error)) {
                onError.accept(NAME_TAKEN);
                return;
            }
            if (error != null) {
                plugin.getSLF4JLogger().error("建立 {} 的角色失敗", player.getName(), error);
                onError.accept("建立角色失敗，請通知管理員");
                return;
            }
            updateRoster(uuid, loaded.record());
            if (usesLegacyLevel) {
                player.getPersistentDataContainer().remove(legacyLevelKey);
            }
            // 關掉視窗（游標上的東西會回到背包；開著的隨身背包、倉庫會同步回去），再把等待期間的變動存一次
            player.closeInventory();
            if (states.get(player).hasCharacter()) {
                save(player); // 等待期間舊角色可能還有變動（撿到東西、拿到經驗），再存一次
            }
            apply(player, states.get(player), loaded, !keepInventory); // 起始技能、藥劑、新手裝備都在這裡送（各自會存檔）
            arriveAtCamp(player);
            player.sendMessage(plugin.text().prefixed("<green>角色建立完成：" + escape(loaded.record().name())
                    + "（" + characterClass.displayName() + "）"));
            if (plugin.world() != null) {
                plugin.world().greet(player); // 每個新角色都是剛被押送船丟上岸的流放者
            }
        }));
    }

    /**
     * 新手裝備（全部普通、物品等級 1，標成新手裝備：拆解沒有東西）：
     * 武器、職業的防具、（拿單手武器的）盾直接裝在角色面板的裝備欄（{@link Gear}）。
     * 裝備欄上原本有東西時不覆蓋：改放背包空格，背包滿了放隨身背包，再滿才掉在腳邊。
     * 先記「送過了」再存檔：當機最多少拿一套，不會拿兩套。送完生命、魔力、能量護盾補滿（防具加的上限也算進去）。
     */
    private void giveStarterKit(final Player player, final PlayerState state) {
        final ItemRepository items = plugin.items().repository();
        final CharacterClass characterClass = state.characterClass();
        final ItemBase weapon = StarterKits.resolveWeapon(items, config, characterClass, state.progress().get(PROGRESS_STARTER_WEAPON));
        setProgress(player, PROGRESS_STARTER_KIT, "1");
        final List<String> received = new ArrayList<>();
        final Placement placement = new Placement(player, state);
        if (weapon != null) {
            final ItemStack stack = plugin.items().createStarter(weapon, StarterKits.ITEM_LEVEL);
            if (!gear.equipDirect(player, state, stack)) {
                placement.stow(stack);
            }
            received.add(weapon.name());
        }
        final List<ItemBase> kit = new ArrayList<>(StarterKits.armour(items, config, characterClass));
        final ItemBase shield = StarterKits.shield(items, config, characterClass, weapon);
        if (shield != null) {
            kit.add(shield);
        }
        for (final ItemBase base : kit) {
            final ItemStack stack = plugin.items().createStarter(base, StarterKits.ITEM_LEVEL);
            if (!gear.equipDirect(player, state, stack)) {
                placement.stow(stack);
            }
            received.add(base.name());
        }
        gear.sync(player);
        states.rebuild(player, state);
        state.restoreAll();
        states.display(player, state);
        save(player);
        if (received.isEmpty()) {
            return;
        }
        final String where = placement.dropped ? "<yellow>（有些欄位原本有東西、背包也滿了，掉在腳邊，記得撿起來）"
                : placement.bagged ? "<yellow>（有些欄位原本有東西，放進隨身背包：Shift＋F → 隨身背包）"
                : placement.stowed ? "<yellow>（有些欄位原本有東西，放在背包裡）"
                : "<gray>（已經裝在角色面板上：Shift＋F → 角色）";
        player.sendMessage(plugin.text().prefixed("<gray>新手裝備：<white>" + escape(String.join("、", received)) + where));
    }

    private final class Placement {
        private final Player player;
        private final PlayerState state;
        private boolean stowed;
        private boolean bagged;
        private boolean dropped;

        Placement(final Player player, final PlayerState state) {
            this.player = player;
            this.state = state;
        }

        void stow(final ItemStack stack) {
            final PlayerInventory inventory = player.getInventory();
            for (int i = 9; i < 36; i++) {
                if (isEmpty(inventory.getItem(i))) {
                    inventory.setItem(i, stack);
                    stowed = true;
                    return;
                }
            }
            for (int i = 0; i < 9; i++) {
                if (i != Gear.HAND_SLOT && isEmpty(inventory.getItem(i))
                        && (plugin.flasks() == null || !plugin.flasks().isFlaskSlot(player, i))) {
                    inventory.setItem(i, stack);
                    stowed = true;
                    return;
                }
            }
            // 新角色的隨身背包是空的（90 格），這裡的視窗也都關了，所以直接放
            if (state.bag() != null && state.bag().add(stack)) {
                bagged = true;
                return;
            }
            plugin.personalDrops().dropForever(player, stack);
            dropped = true;
        }
    }

    private static boolean isEmpty(final ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** 切換到帳號裡的另一個角色。 */
    public void switchTo(final Player player, final long characterId, final Consumer<String> onError) {
        final UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) {
            onError.accept("角色資料還在處理中，請稍等");
            return;
        }
        if (hasUnsavedTrades(player)) {
            onError.accept("交易還在存檔中，請稍等再換角色");
            return;
        }
        final PlayerState state = states.get(player);
        if (!state.isLoaded()) {
            onError.accept("角色資料還沒載入完成");
            return;
        }
        if (state.hasCharacter() && state.character().id() == characterId) {
            onError.accept("已經在使用這個角色了");
            return;
        }
        boolean inRoster = false;
        for (final CharacterRecord record : roster(player)) {
            if (record.id() == characterId) {
                inRoster = true;
                break;
            }
        }
        if (!inRoster) {
            onError.accept("這不是你的角色");
            return;
        }
        busy.add(uuid);
        save(player);
        plugin.database().async(connection -> {
            final Optional<CharacterRepository.Loaded> loaded = repository.loadCharacter(connection, characterId);
            if (loaded.isEmpty()) {
                throw new IllegalStateException("角色 " + characterId + " 不存在");
            }
            repository.setActiveCharacter(connection, uuid, characterId);
            return loaded.get();
        }).whenComplete((loaded, error) -> sync(() -> {
            if (!stillOnline(player)) {
                return;
            }
            busy.remove(uuid);
            if (error != null) {
                plugin.getSLF4JLogger().error("切換 {} 的角色失敗", player.getName(), error);
                onError.accept("切換角色失敗，請通知管理員");
                return;
            }
            // 關掉視窗（游標上的東西會回到背包），再把等待期間的變動存一次
            player.closeInventory();
            final PlayerState current = states.get(player);
            if (current.hasCharacter()) {
                save(player);
            }
            for (int i = 0; i < PlayerState.STORED_SLOTS; i++) {
                current.setJewellery(i, null);
            }
            apply(player, current, loaded, true);
            arriveAtCamp(player);
            updateRoster(uuid, loaded.record());
        }));
    }

    /** 刪除一個沒在使用中的角色（軟刪除）。 */
    public void delete(final Player player, final long characterId, final Consumer<String> onError) {
        final UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) {
            onError.accept("角色資料還在處理中，請稍等");
            return;
        }
        if (hasUnsavedTrades(player)) {
            onError.accept("交易還在存檔中，請稍等再換角色");
            return;
        }
        final PlayerState state = states.get(player);
        if (state.hasCharacter() && state.character().id() == characterId) {
            onError.accept("不能刪除使用中的角色，先切換到別的角色");
            return;
        }
        CharacterRecord target = null;
        for (final CharacterRecord record : roster(player)) {
            if (record.id() == characterId) {
                target = record;
            }
        }
        if (target == null) {
            onError.accept("這不是你的角色");
            return;
        }
        final String targetName = target.name();
        busy.add(uuid);
        plugin.database().async(connection -> {
            // 通貨袋裡的通貨跟著角色一起沒了：記成消耗（經濟報表的存量才對得上）
            final Map<String, Integer> pouch = plugin.economy().repository().pouchOf(connection, characterId);
            repository.deleteCharacter(connection, characterId);
            return pouch;
        }).whenComplete((pouch, error) -> sync(() -> {
            if (error == null && pouch != null) {
                for (final Map.Entry<String, Integer> entry : pouch.entrySet()) {
                    plugin.economy().ledger().consumed(entry.getKey(), tw.exilecore.economy.EconomyLedger.DELETE, entry.getValue());
                }
            }
            if (!stillOnline(player)) {
                return;
            }
            busy.remove(uuid);
            if (error != null) {
                plugin.getSLF4JLogger().error("刪除 {} 的角色失敗", player.getName(), error);
                onError.accept("刪除角色失敗，請通知管理員");
                return;
            }
            final List<CharacterRecord> roster = new ArrayList<>();
            for (final CharacterRecord record : roster(player)) {
                if (record.id() != characterId) {
                    roster.add(record);
                }
            }
            rosters.put(uuid, roster);
            states.markDirty(player); // 分身獎勵跟著帳號的角色清單算：少了一個角色，加成可能跟著變
            player.sendMessage(plugin.text().prefixed("<gray>已刪除角色 " + escape(targetName)));
        }));
    }

    /** 改名。 */
    public void rename(final Player player, final String name, final Consumer<String> onError) {
        final PlayerState state = states.get(player);
        if (!state.hasCharacter()) {
            onError.accept("你還沒有角色");
            return;
        }
        final String problem = validateName(name);
        if (problem != null) {
            onError.accept(problem);
            return;
        }
        final String trimmed = normalizeName(name);
        final long id = state.character().id();
        final UUID account = player.getUniqueId();
        // 2026-10：改名要用一張改名卡（贊助商城買；管理員不用）。先扣，名字被用走或失敗就還回去
        final boolean needsCard = plugin.sponsors() != null && !player.hasPermission(ExileCore.PERMISSION_ADMIN);
        if (needsCard && !plugin.sponsors().useRenameCard(player)) {
            onError.accept("改名要用一張改名卡（/sponsor 贊助商城購買）");
            return;
        }
        // 查有沒有人用跟改名在同一個資料庫工作裡（資料庫只有一條執行緒：兩個人同時改成同一個名字，只有一個會成功）
        plugin.database().async(connection -> {
            if (repository.nameTaken(connection, trimmed, id, account)) {
                return true;
            }
            repository.renameCharacter(connection, id, trimmed);
            return false;
        }).whenComplete((taken, error) -> sync(() -> {
            if (!stillOnline(player)) {
                return;
            }
            if (error != null) {
                plugin.getSLF4JLogger().error("查角色名稱 {} 失敗", trimmed, error);
                if (needsCard) {
                    plugin.sponsors().refundRenameCard(player);
                }
                onError.accept("改名失敗，請通知管理員（改名卡已經還給你）");
                return;
            }
            if (Boolean.TRUE.equals(taken)) {
                if (needsCard) {
                    plugin.sponsors().refundRenameCard(player);
                }
                onError.accept(NAME_TAKEN + "（改名卡已經還給你）");
                return;
            }
            final PlayerState current = states.get(player);
            if (current.hasCharacter() && current.character().id() == id) {
                current.character().setName(trimmed);
                updateRoster(player.getUniqueId(), current.character());
                save(player);
                states.updateListName(player); // TAB、聊天、私訊用新的名字
            } else {
                // 等的時候換了角色：資料庫已經改好了，角色清單跟著改
                for (final CharacterRecord entry : rosters.getOrDefault(player.getUniqueId(), List.of())) {
                    if (entry.id() == id) {
                        entry.setName(trimmed);
                    }
                }
            }
            player.sendMessage(plugin.text().prefixed("<gray>角色改名為 " + escape(trimmed)));
        }));
    }

    /** 新角色與切換角色後回到營地（有設定營地的話）。 */
    private void arriveAtCamp(final Player player) {
        if (plugin.zones() != null) {
            plugin.zones().sendToCamp(player, false);
        }
    }

    /** 回主執行緒。 */
    private void sync(final Runnable runnable) {
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    /** 這個 Player 物件是否還是現在在線的那一個（快速登出再登入會換一個新物件，舊的不能再用）。 */
    private boolean stillOnline(final Player player) {
        return plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    /** 把角色清單裡對應的摘要換成最新的資料。 */
    private void updateRoster(final UUID uuid, final CharacterRecord record) {
        final List<CharacterRecord> roster = new ArrayList<>();
        boolean found = false;
        for (final CharacterRecord entry : rosters.getOrDefault(uuid, List.of())) {
            if (entry.id() == record.id()) {
                roster.add(record);
                found = true;
            } else {
                roster.add(entry);
            }
        }
        if (!found) {
            roster.add(record);
            roster.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        }
        rosters.put(uuid, roster);
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
