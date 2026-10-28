package tw.exilecore.trade;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.economy.GoldService;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 面對面交易：邀請、接受、兩邊放東西、確認、成交（照 PoE 的交易視窗）。
 * <p>
 * 成交時再核對一次每一樣東西（同一件、數量一樣、通貨袋還夠），模擬兩邊的背包放不放得下，
 * 然後一次搬完，兩個人的存檔跟交易紀錄在同一個資料庫交易寫入（CharacterService.commit）。
 * <p>
 * 手續費（{@link TradeFee}）：兩邊各付自己收到的東西估價的 %，金幣不夠不能確認；成交時跟東西一起扣、一起存檔。
 */
public final class TradeService {

    /** 背包：快捷列＋主背包。 */
    private static final int BACKPACK = 36;

    private final ExileCore plugin;
    /** 被邀請的人 → （邀請的人 → 期限）。 */
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    private final Map<UUID, TradeSession> sessions = new HashMap<>();

    public TradeService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private EconomyConfig config() {
        return plugin.economy().config();
    }

    public boolean inTrade(final Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ---- 邀請 ----

    /** player 邀請 target 交易。 */
    public void request(final Player player, final Player target) {
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.text().prefixed("<red>找不到那個玩家"));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(plugin.text().prefixed("<red>不能跟自己交易"));
            return;
        }
        final String problem = problem(player, true);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        final String targetProblem = problem(target, false);
        if (targetProblem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(plugin.displayName(target)) + "：" + targetProblem));
            return;
        }
        if (!PlayerSettings.enabled(plugin.players().get(target), PlayerSettings.TRADE_REQUESTS)) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(plugin.displayName(target)) + " 關掉了交易邀請"));
            return;
        }
        if (!near(player, target)) {
            player.sendMessage(plugin.text().prefixed("<red>要在 " + (int) config().tradeRange() + " 格內才能交易"));
            return;
        }
        // 對方也邀請過你：直接開
        final Map<UUID, Long> mine = requests.get(player.getUniqueId());
        if (mine != null && mine.containsKey(target.getUniqueId()) && mine.get(target.getUniqueId()) > System.currentTimeMillis()) {
            mine.remove(target.getUniqueId());
            open(target, player);
            return;
        }
        requests.computeIfAbsent(target.getUniqueId(), key -> new LinkedHashMap<>())
                .put(player.getUniqueId(), System.currentTimeMillis() + config().tradeRequestSeconds() * 1000L);
        final String name = ItemRenderer.escape(plugin.displayName(player));
        player.sendMessage(plugin.text().prefixed("<gray>已邀請 <white>" + ItemRenderer.escape(plugin.displayName(target))
                + "</white> 交易（" + config().tradeRequestSeconds() + " 秒內有效）"));
        target.sendMessage(plugin.text().prefixed("<#f5b942>" + name + " <gray>想跟你交易　"
                + "<green><click:run_command:'/trade accept " + plugin.displayName(player) + "'><hover:show_text:'打開交易視窗'>[接受]</hover></click></green> "
                + "<red><click:run_command:'/trade decline " + plugin.displayName(player) + "'><hover:show_text:'拒絕這次邀請'>[拒絕]</hover></click></red>"));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
    }

    /** 接受邀請；fromName 為 null 時接受最新的一個。 */
    public void accept(final Player player, final String fromName) {
        final Player from = findRequester(player, fromName);
        if (from == null) {
            player.sendMessage(plugin.text().prefixed(fromName == null ? "<red>沒有人邀請你交易（或邀請過期了）"
                    : "<red>" + ItemRenderer.escape(fromName) + " 沒有邀請你交易（或邀請過期了）"));
            return;
        }
        requests.get(player.getUniqueId()).remove(from.getUniqueId());
        final String problem = problem(player, true);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        final String fromProblem = problem(from, true);
        if (fromProblem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(plugin.displayName(from)) + "：" + fromProblem));
            return;
        }
        if (!near(player, from)) {
            player.sendMessage(plugin.text().prefixed("<red>要在 " + (int) config().tradeRange() + " 格內才能交易"));
            return;
        }
        open(from, player);
    }

    public void decline(final Player player, final String fromName) {
        final Player from = findRequester(player, fromName);
        if (from == null) {
            player.sendMessage(plugin.text().prefixed("<gray>沒有要拒絕的交易邀請"));
            return;
        }
        requests.get(player.getUniqueId()).remove(from.getUniqueId());
        player.sendMessage(plugin.text().prefixed("<gray>已拒絕 " + ItemRenderer.escape(plugin.displayName(from)) + " 的交易邀請"));
        from.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(plugin.displayName(player)) + " 拒絕了交易"));
    }

    /** 還有效的邀請人名字（指令提示用）。 */
    public List<String> requesterNames(final Player player) {
        final List<String> names = new ArrayList<>();
        final Map<UUID, Long> map = requests.get(player.getUniqueId());
        if (map == null) {
            return names;
        }
        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, Long> entry : map.entrySet()) {
            final Player requester = plugin.getServer().getPlayer(entry.getKey());
            if (requester != null && entry.getValue() > now) {
                names.add(plugin.displayName(requester));
            }
        }
        return names;
    }

    private Player findRequester(final Player player, final String fromName) {
        final Map<UUID, Long> map = requests.get(player.getUniqueId());
        if (map == null) {
            return null;
        }
        final long now = System.currentTimeMillis();
        Player found = null;
        for (final Map.Entry<UUID, Long> entry : map.entrySet()) {
            if (entry.getValue() <= now) {
                continue;
            }
            final Player requester = plugin.getServer().getPlayer(entry.getKey());
            if (requester == null) {
                continue;
            }
            if (fromName == null || plugin.displayName(requester).equalsIgnoreCase(fromName.strip())
                    || requester.getName().equalsIgnoreCase(fromName.strip())) {
                found = requester; // 沒指定名字：最後一個（最新的）
            }
        }
        return found;
    }

    /** 不能交易的原因；null = 可以。 */
    private String problem(final Player player, final boolean self) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        if (inTrade(player)) {
            return self ? "你正在跟別人交易" : "正在跟別人交易";
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return "現在不能交易";
        }
        return null;
    }

    private boolean near(final Player a, final Player b) {
        if (!a.getWorld().equals(b.getWorld())) {
            // 2026-10-22 主城分流：兩個人都在營地（不同線）也可以交易——看不到對方，距離照營地的座標算
            if (plugin.zones() == null || !plugin.zones().inTown(a.getLocation()) || !plugin.zones().inTown(b.getLocation())) {
                return false;
            }
            final double range = config().tradeRange();
            final double dx = a.getLocation().getX() - b.getLocation().getX();
            final double dz = a.getLocation().getZ() - b.getLocation().getZ();
            return dx * dx + dz * dz <= Math.max(range * range, 48 * 48);
        }
        final double range = config().tradeRange();
        return a.getLocation().distanceSquared(b.getLocation()) <= range * range;
    }

    // ---- 視窗 ----

    private void open(final Player a, final Player b) {
        final TradeSession session = new TradeSession(a, b);
        sessions.put(a.getUniqueId(), session);
        sessions.put(b.getUniqueId(), session);
        session.a.menu = new TradeMenu(plugin, a, this, session, session.a);
        session.b.menu = new TradeMenu(plugin, b, this, session, session.b);
        session.a.menu.open();
        session.b.menu.open();
        for (final Player player : List.of(a, b)) {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
        }
    }

    void offerItem(final TradeSession session, final TradeSession.Side side, final int slot) {
        final ItemStack stack = side.player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (side.offersSlot(slot)) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<gray>這一格已經放上去了（點左邊的拿回來）"));
            return;
        }
        if (side.offers.size() >= Math.min(TradeMenu.CELLS, config().tradeMaxItems())) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>最多放 " + Math.min(TradeMenu.CELLS, config().tradeMaxItems()) + " 樣"));
            return;
        }
        if (plugin.gold().amountOf(stack).isPresent()) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>金幣不能交易"));
            return;
        }
        if (tw.exilecore.flask.FlaskService.isPlaceholder(stack)) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<gray>這是空的藥劑欄（提示），不是東西"));
            return;
        }
        if (plugin.items().read(stack).map(ItemData::locked).orElse(false)) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<#ffd27f>🔒 這件鎖定了（/lock 解除後才能交易）"));
            return;
        }
        if (tw.exilecore.item.AccountBound.isBound(stack)) {
            // 2026-10-28：綁定帳號的物品（職業轉職卷）不能交易
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>" + tw.exilecore.item.AccountBound.problem(stack,
                    tw.exilecore.item.AccountBound.Channel.TRADE)));
            return;
        }
        if (tw.exilecore.life.LifeService.isHook(stack)) {
            // 2026-10-27：星索鉤綁定帳號；這次之前交易給別人再到選單領一把＝複製（公會倉庫、市集、丟地上已經擋了）
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>星索鉤綁定帳號，不能交易"));
            return;
        }
        if (stack.hasData(DataComponentTypes.BUNDLE_CONTENTS) || stack.hasData(DataComponentTypes.CONTAINER)) {
            // 裝在裡面的東西估不到價（手續費會變成最低），也看不清楚
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>收納袋、界伏盒這類容器不能交易（把裡面的東西拿出來再放）"));
            return;
        }
        side.offers.add(new TradeSession.ItemOffer(slot, stack.clone()));
        changed(session, side);
    }

    void offerPouch(final TradeSession session, final TradeSession.Side side, final String currencyId, final int wanted) {
        final CurrencyPouch pouch = plugin.players().get(side.player).bag().pouch();
        final int offered = side.pouchOffered(currencyId);
        final int room = pouch.amount(currencyId) - offered;
        if (room <= 0) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<gray>通貨袋裡的這種通貨已經全部放上去了"));
            return;
        }
        final int add = Math.min(room, wanted);
        // 同一種通貨合成一格
        for (int i = 0; i < side.offers.size(); i++) {
            if (side.offers.get(i) instanceof TradeSession.PouchOffer existing && existing.currencyId().equals(currencyId)) {
                side.offers.set(i, new TradeSession.PouchOffer(currencyId, existing.amount() + add));
                changed(session, side);
                return;
            }
        }
        if (side.offers.size() >= Math.min(TradeMenu.CELLS, config().tradeMaxItems())) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>最多放 " + Math.min(TradeMenu.CELLS, config().tradeMaxItems()) + " 樣"));
            return;
        }
        side.offers.add(new TradeSession.PouchOffer(currencyId, add));
        changed(session, side);
    }

    void removeOffer(final TradeSession session, final TradeSession.Side side, final int index) {
        if (index < 0 || index >= side.offers.size()) {
            return;
        }
        side.offers.remove(index);
        changed(session, side);
    }

    /** 內容改了：兩邊的確認都取消，對方要等一下才能確認。 */
    private void changed(final TradeSession session, final TradeSession.Side side) {
        side.lastChange = plugin.getServer().getCurrentTick();
        final boolean hadConfirm = session.a.confirmed || session.b.confirmed;
        session.a.confirmed = false;
        session.b.confirmed = false;
        side.player.playSound(side.player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
        final TradeSession.Side other = session.other(side);
        tw.exilecore.gui.ActionBar.send(other.player, plugin.text().parse("<yellow>" + ItemRenderer.escape(plugin.displayName(side.player)) + " 改了交易內容"
                + (hadConfirm ? "，確認已取消" : "")));
        redraw(session);
        // 等待時間過了把按鈕畫回來
        final long wait = config().confirmDelaySeconds() * 20L;
        if (wait > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!session.finished) {
                    redraw(session);
                }
            }, wait + 1);
        }
    }

    /** 這一邊還要等幾 tick 才能確認（對方剛改過內容）。 */
    long confirmWaitTicks(final TradeSession session, final TradeSession.Side side, final long now) {
        final long since = now - session.other(side).lastChange;
        return Math.max(0, config().confirmDelaySeconds() * 20L - since);
    }

    // ---- 手續費 ----

    /** side 這一邊收下對方放的東西要付的手續費（估價看放上去那一刻的樣子；成交前會核對東西沒變）。 */
    TradeFee.Quote fee(final TradeSession session, final TradeSession.Side side) {
        final List<TradeFee.Received> received = new ArrayList<>();
        for (final TradeSession.Offer offer : session.other(side).offers) {
            received.add(appraise(offer));
        }
        final TradeFee.Quote quote = TradeFee.quote(config(), received);
        if (feeFree(side.player)) {
            return new TradeFee.Quote(quote.count(), quote.value(), 0); // 2026-10 月卡福利：面對面交易不收手續費
        }
        return quote;
    }

    /** 有月卡：面對面交易不收手續費（2026-10；只免自己那一份，對方沒月卡照付）。 */
    boolean feeFree(final org.bukkit.entity.Player player) {
        return player != null && plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    private TradeFee.Received appraise(final TradeSession.Offer offer) {
        return switch (offer) {
            case TradeSession.PouchOffer currency -> new TradeFee.Currency(currency.currencyId(), currency.amount());
            case TradeSession.ItemOffer item -> {
                final ItemStack stack = item.snapshot();
                final Optional<CurrencyType> currency = plugin.currencies().read(stack);
                if (currency.isPresent()) {
                    yield new TradeFee.Currency(currency.get().id(), stack.getAmount());
                }
                final Optional<ItemData> data = plugin.items().read(stack);
                yield data.isPresent()
                        ? new TradeFee.Gear(data.get().rarity(), data.get().ilvl(), stack.getAmount())
                        : new TradeFee.Other(stack.getAmount());
            }
        };
    }

    void toggleConfirm(final TradeSession session, final TradeSession.Side side) {
        if (side.confirmed) {
            side.confirmed = false;
            redraw(session);
            return;
        }
        if (confirmWaitTicks(session, side, plugin.getServer().getCurrentTick()) > 0) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<yellow>對方剛改過內容，先看清楚再確認"));
            return;
        }
        if (side.offers.isEmpty() && session.other(side).offers.isEmpty()) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<gray>兩邊都還沒放東西"));
            return;
        }
        final long fee = fee(session, side).fee();
        final long gold = plugin.gold().balance(side.player);
        if (gold < fee) {
            tw.exilecore.gui.ActionBar.send(side.player, plugin.text().parse("<red>金幣不夠付手續費：要 " + GoldService.format(fee)
                    + "，你有 " + GoldService.format(gold)));
            side.player.playSound(side.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        side.confirmed = true;
        side.confirmedFee = fee;
        side.player.playSound(side.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
        if (session.a.confirmed && session.b.confirmed) {
            complete(session);
        } else {
            redraw(session);
            tw.exilecore.gui.ActionBar.send(session.other(side).player, plugin.text().parse("<green>" + ItemRenderer.escape(plugin.displayName(side.player)) + " 已經確認"));
        }
    }

    private void redraw(final TradeSession session) {
        for (final TradeSession.Side side : List.of(session.a, session.b)) {
            if (side.menu != null && side.player.getOpenInventory().getTopInventory().getHolder() == side.menu) {
                side.menu.redraw();
            }
        }
    }

    /** 取消（關掉兩邊的視窗）。 */
    public void cancel(final TradeSession session, final String reason) {
        if (session.finished) {
            return;
        }
        session.finished = true;
        sessions.remove(session.a.player.getUniqueId(), session);
        sessions.remove(session.b.player.getUniqueId(), session);
        for (final TradeSession.Side side : List.of(session.a, session.b)) {
            if (side.player.isOnline()) {
                if (side.player.getOpenInventory().getTopInventory().getHolder() == side.menu) {
                    side.player.closeInventory();
                }
                side.player.sendMessage(plugin.text().prefixed("<gray>交易取消：" + ItemRenderer.escape(reason)));
            }
        }
    }

    // ---- 成交 ----

    private void complete(final TradeSession session) {
        final TradeSession.Side a = session.a;
        final TradeSession.Side b = session.b;
        for (final TradeSession.Side side : List.of(a, b)) {
            final String problem = plugin.characters().transactionProblem(side.player);
            if (problem != null) {
                fail(session, ItemRenderer.escape(plugin.displayName(side.player)) + "：" + problem);
                return;
            }
            final String stale = verify(side);
            if (stale != null) {
                fail(session, ItemRenderer.escape(plugin.displayName(side.player)) + " " + stale);
                return;
            }
        }
        if (!near(a.player, b.player)) {
            cancel(session, "兩個人離太遠了");
            return;
        }
        // 模擬兩邊收東西之後的背包
        final Plan planA = plan(a, b);
        final Plan planB = plan(b, a);
        if (planA.leftover > 0) {
            fail(session, ItemRenderer.escape(plugin.displayName(a.player)) + " 的背包放不下（還差 " + planA.leftover + " 個）");
            return;
        }
        if (planB.leftover > 0) {
            fail(session, ItemRenderer.escape(plugin.displayName(b.player)) + " 的背包放不下（還差 " + planB.leftover + " 個）");
            return;
        }
        // 手續費：每個人付自己收到的東西估價的 %。成交這一刻再算一次，跟按確認時看到的不一樣就不成交
        // （內容一改兩邊的確認就取消，所以只有中途 /ec reload 改了費率或通貨價值才會不一樣）
        final long feeA = fee(session, a).fee();
        final long feeB = fee(session, b).fee();
        if (feeA != a.confirmedFee || feeB != b.confirmedFee) {
            fail(session, "手續費剛改過（伺服器更新了設定），請看一下再確認一次");
            return;
        }
        // 先扣兩邊的手續費：扣不到（金幣不夠）就退回已經扣的、不成交。之後就不會再失敗了
        if (!plugin.gold().take(a.player, feeA, tw.exilecore.economy.EconomyLedger.FEE_TRADE)) {
            fail(session, ItemRenderer.escape(plugin.displayName(a.player)) + " 的金幣不夠付手續費（要 " + GoldService.format(feeA) + "）");
            return;
        }
        if (!plugin.gold().take(b.player, feeB, tw.exilecore.economy.EconomyLedger.FEE_TRADE)) {
            plugin.gold().refund(a.player, feeA, tw.exilecore.economy.EconomyLedger.FEE_TRADE);
            fail(session, ItemRenderer.escape(plugin.displayName(b.player)) + " 的金幣不夠付手續費（要 " + GoldService.format(feeB) + "）");
            return;
        }
        // 紀錄（先記：之後東西就搬走了）
        final List<TradeLogRepository.Entry> entries = new ArrayList<>();
        log(session, a, b, entries);
        log(session, b, a, entries);
        logFee(session, a, feeA, entries);
        logFee(session, b, feeB, entries);
        // 一次搬完：拿走兩邊通貨袋裡的，再把模擬好的背包寫回去，最後把收到的通貨放進通貨袋。
        // 金幣（上面扣的手續費）跟背包、通貨袋一樣在下面的存檔裡，同一個資料庫交易寫入
        for (final TradeSession.Side side : List.of(a, b)) {
            final CurrencyPouch pouch = plugin.players().get(side.player).bag().pouch();
            for (final TradeSession.Offer offer : side.offers) {
                if (offer instanceof TradeSession.PouchOffer currency) {
                    pouch.withdraw(currency.currencyId(), currency.amount());
                }
            }
        }
        apply(a, planA);
        apply(b, planB);
        session.finished = true;
        sessions.remove(a.player.getUniqueId(), session);
        sessions.remove(b.player.getUniqueId(), session);
        for (final TradeSession.Side side : List.of(a, b)) {
            side.player.closeInventory();
            plugin.players().markDirty(side.player);
        }
        final String description = "交易 " + a.player.getName() + " ↔ " + b.player.getName();
        final TradeLogRepository log = plugin.economy().tradeLog();
        plugin.characters().commit(List.of(a.player, b.player), connection -> log.insert(connection, entries), null, description);
        plugin.getSLF4JLogger().info("[trade] {}：{} 樣 ↔ {} 樣，手續費 {}／{} 金幣（{}）", description, a.offers.size(), b.offers.size(),
                feeA, feeB, session.id);
        for (final TradeSession.Side side : List.of(a, b)) {
            final TradeSession.Side other = session.other(side);
            final long fee = side == a ? feeA : feeB;
            side.player.sendMessage(plugin.text().prefixed("<green>交易完成：<gray>給了 <white>" + side.offers.size()
                    + "</white> 樣、收到 <white>" + other.offers.size() + "</white> 樣（跟 " + ItemRenderer.escape(plugin.displayName(other.player)) + "）"
                    + (fee > 0 ? "，手續費 <#f5b942>" + GoldService.format(fee) + " 金幣" : "")));
            side.player.playSound(side.player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        }
    }

    /** 手續費也記一筆（誰付了多少；查「金幣怎麼少了」用）。 */
    private void logFee(final TradeSession session, final TradeSession.Side payer, final long fee,
                        final List<TradeLogRepository.Entry> entries) {
        if (fee > 0) {
            entries.add(new TradeLogRepository.Entry(session.id, TradeLogRepository.TRADE, null, "交易手續費（金幣）",
                    (int) Math.min(Integer.MAX_VALUE, fee), payer.player.getUniqueId(), payer.player.getName(), null, "手續費"));
        }
    }

    /** 核對放上去的東西還在：同一件、數量一樣；通貨袋還夠。不對回傳原因。 */
    private String verify(final TradeSession.Side side) {
        final PlayerInventory inventory = side.player.getInventory();
        final CurrencyPouch pouch = plugin.players().get(side.player).bag().pouch();
        final Map<String, Integer> pouchNeed = new HashMap<>();
        for (final TradeSession.Offer offer : side.offers) {
            if (offer instanceof TradeSession.ItemOffer item) {
                final ItemStack current = inventory.getItem(item.slot());
                if (current == null || current.isEmpty() || !current.isSimilar(item.snapshot())
                        || current.getAmount() != item.snapshot().getAmount()) {
                    return "放上去的東西變了（移動、用掉或數量變了）";
                }
            } else if (offer instanceof TradeSession.PouchOffer currency) {
                pouchNeed.merge(currency.currencyId(), currency.amount(), Integer::sum);
            }
        }
        for (final Map.Entry<String, Integer> entry : pouchNeed.entrySet()) {
            if (pouch.amount(entry.getKey()) < entry.getValue()) {
                return "通貨袋裡的通貨不夠了";
            }
        }
        return null;
    }

    /** 一邊收東西之後的背包（0–35）與要放進通貨袋的通貨。 */
    private static final class Plan {
        ItemStack[] contents;
        final Map<String, Integer> pouchDeposits = new LinkedHashMap<>();
        int leftover;
    }

    /** 模擬 receiver 給出自己的東西、收下 giver 的東西之後的樣子。 */
    private Plan plan(final TradeSession.Side receiver, final TradeSession.Side giver) {
        final Plan plan = new Plan();
        final PlayerInventory inventory = receiver.player.getInventory();
        plan.contents = new ItemStack[BACKPACK];
        for (int slot = 0; slot < BACKPACK; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            plan.contents[slot] = stack == null || stack.isEmpty() ? null : stack.clone();
        }
        for (final TradeSession.Offer offer : receiver.offers) {
            if (offer instanceof TradeSession.ItemOffer item) {
                plan.contents[item.slot()] = null; // 給出去的那幾格空出來
            }
        }
        if (plugin.flasks() != null) {
            // 藥劑欄不放收到的東西：空出來的放回空瓶提示（收到的藥劑成交後再放進去）
            for (final int slot : plugin.flasks().slots(receiver.player)) {
                if (plan.contents[slot] == null || plan.contents[slot].isEmpty()) {
                    plan.contents[slot] = plugin.flasks().placeholderFor(receiver.player, slot);
                }
            }
        }
        final CurrencyPouch pouch = plugin.players().get(receiver.player).bag().pouch();
        final Map<String, Integer> afterGiving = new HashMap<>();
        for (final TradeSession.Offer offer : receiver.offers) {
            if (offer instanceof TradeSession.PouchOffer currency) {
                afterGiving.merge(currency.currencyId(), currency.amount(), Integer::sum);
            }
        }
        final List<ItemStack> incoming = new ArrayList<>();
        int unplaceable = 0;
        for (final TradeSession.Offer offer : giver.offers) {
            if (offer instanceof TradeSession.ItemOffer item) {
                incoming.add(giver.player.getInventory().getItem(item.slot()).clone());
            } else if (offer instanceof TradeSession.PouchOffer currency) {
                // 先放進通貨袋（每種最多 CAP 個），放不下的變成物品放背包
                final int have = pouch.amount(currency.currencyId()) - afterGiving.getOrDefault(currency.currencyId(), 0)
                        + plan.pouchDeposits.getOrDefault(currency.currencyId(), 0);
                final int fits = Math.max(0, Math.min(currency.amount(), pouch.cap() - have));
                if (fits > 0) {
                    plan.pouchDeposits.merge(currency.currencyId(), fits, Integer::sum);
                }
                final int rest = currency.amount() - fits;
                final CurrencyType type = plugin.currencies().repository().get(currency.currencyId());
                if (type == null) {
                    unplaceable += rest; // 通貨設定剛被拿掉：做不出物品，算放不下（交易不成立，不會憑空消失）
                    continue;
                }
                for (int left = rest; left > 0; left -= type.stack()) {
                    incoming.add(plugin.currencies().create(type, Math.min(left, type.stack())));
                }
            }
        }
        plan.leftover = place(plan.contents, incoming) + unplaceable;
        return plan;
    }

    /**
     * 模擬把東西放進格子：先疊到一樣的東西上，再放空格。contents 會被改。
     *
     * @return 放不下的數量
     */
    static int place(final ItemStack[] contents, final List<ItemStack> items) {
        int leftover = 0;
        for (final ItemStack item : items) {
            ItemStack rest = item.clone();
            for (int i = 0; i < contents.length && rest != null; i++) {
                final ItemStack current = contents[i];
                if (current == null || current.isEmpty() || !current.isSimilar(rest)) {
                    continue;
                }
                final int room = current.getMaxStackSize() - current.getAmount();
                if (room <= 0) {
                    continue;
                }
                final int moved = Math.min(room, rest.getAmount());
                contents[i] = current.asQuantity(current.getAmount() + moved);
                rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
            }
            for (int i = 0; i < contents.length && rest != null; i++) {
                if (contents[i] != null && !contents[i].isEmpty()) {
                    continue;
                }
                final int moved = Math.min(rest.getMaxStackSize(), rest.getAmount());
                contents[i] = rest.asQuantity(moved);
                rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
            }
            if (rest != null) {
                leftover += rest.getAmount();
            }
        }
        return leftover;
    }

    private void apply(final TradeSession.Side side, final Plan plan) {
        final PlayerInventory inventory = side.player.getInventory();
        for (int slot = 0; slot < BACKPACK; slot++) {
            inventory.setItem(slot, plan.contents[slot]);
        }
        final CurrencyPouch pouch = plugin.players().get(side.player).bag().pouch();
        for (final Map.Entry<String, Integer> entry : plan.pouchDeposits.entrySet()) {
            pouch.deposit(entry.getKey(), entry.getValue());
        }
        if (plugin.flasks() != null) {
            plugin.flasks().autoPlace(side.player, true); // 收到的藥劑：那一種的藥劑欄空著就放進去
            plugin.flasks().ensureSlots(side.player);
        }
    }

    private void log(final TradeSession session, final TradeSession.Side from, final TradeSession.Side to,
                     final List<TradeLogRepository.Entry> entries) {
        for (final TradeSession.Offer offer : from.offers) {
            if (offer instanceof TradeSession.ItemOffer item) {
                final ItemStack stack = from.player.getInventory().getItem(item.slot());
                final String uuid = plugin.items().read(stack).map(ItemData::uuid).orElse(null);
                entries.add(new TradeLogRepository.Entry(session.id, TradeLogRepository.TRADE, uuid, Wallet.describe(stack),
                        stack.getAmount(), from.player.getUniqueId(), from.player.getName(), to.player.getUniqueId(), to.player.getName()));
            } else if (offer instanceof TradeSession.PouchOffer currency) {
                final CurrencyType type = plugin.currencies().repository().get(currency.currencyId());
                entries.add(new TradeLogRepository.Entry(session.id, TradeLogRepository.TRADE, null,
                        (type == null ? currency.currencyId() : type.name()) + "（通貨袋）", currency.amount(),
                        from.player.getUniqueId(), from.player.getName(), to.player.getUniqueId(), to.player.getName()));
            }
        }
    }

    /** 成交前發現問題：取消兩邊的確認，視窗留著讓他們調整。 */
    private void fail(final TradeSession session, final String reason) {
        session.a.confirmed = false;
        session.b.confirmed = false;
        for (final TradeSession.Side side : List.of(session.a, session.b)) {
            side.player.sendMessage(plugin.text().prefixed("<red>交易沒有成功：" + reason));
            side.player.playSound(side.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
        redraw(session);
    }

    // ---- 定時、登出 ----

    /** 每秒：過期的邀請、離太遠或不能交易的人。 */
    public void tick() {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<UUID, Map<UUID, Long>>> iterator = requests.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Map<UUID, Long>> entry = iterator.next();
            entry.getValue().values().removeIf(expires -> expires <= now);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        for (final TradeSession session : List.copyOf(new java.util.LinkedHashSet<>(sessions.values()))) {
            if (session.finished) {
                continue;
            }
            final Player a = session.a.player;
            final Player b = session.b.player;
            if (!a.isOnline() || !b.isOnline() || a.isDead() || b.isDead()) {
                cancel(session, "有人離開了");
            } else if (!a.getWorld().equals(b.getWorld())
                    || a.getLocation().distanceSquared(b.getLocation()) > Math.pow(config().tradeRange() * 1.5, 2)) {
                cancel(session, "兩個人離太遠了");
            } else {
                // 交易中撿到金幣（隊伍分到的、自動撿取）：確認鈕上的「你有多少」跟著更新
                for (final TradeSession.Side side : List.of(session.a, session.b)) {
                    if (side.menu != null && side.shownGold != plugin.gold().balance(side.player)
                            && side.player.getOpenInventory().getTopInventory().getHolder() == side.menu) {
                        side.menu.redraw();
                    }
                }
            }
        }
    }

    public void quit(final Player player) {
        final TradeSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            cancel(session, ItemRenderer.escape(plugin.displayName(player)) + " 離線了");
        }
        requests.remove(player.getUniqueId());
        for (final Map<UUID, Long> map : requests.values()) {
            map.remove(player.getUniqueId());
        }
    }

    /** 模組停用：取消所有交易（東西都還在各自身上）。 */
    public void closeAll() {
        for (final TradeSession session : List.copyOf(new java.util.LinkedHashSet<>(sessions.values()))) {
            cancel(session, "伺服器重新載入");
        }
        requests.clear();
    }
}
