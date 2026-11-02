package tw.exilecore.slots;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.bounty.BountyService;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.economy.GoldService;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.npc.NpcType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 拉霸機（2026-11-02 照站長；這次之前沒有）：下注、抽結果、派獎、稱號、公告。
 * <p>
 * 稱號（2026-11-02 照站長）：每個押注額一個，用那個押注額中頭獎才給那一個；這次之前只有一個「金運亨通」，
 * 押 1,000 以上中頭獎或保底（累積 3,000 把，帳號進度 slots.pity）給。保底拿掉了：slots.pity 不再累積也不再讀（舊的值留在帳號裡不刪）。
 * <p>
 * 按下去那一刻：扣押注、用伺服器的亂數抽好結果（獎品、稱號都決定好），記成「還沒結算」的一把；
 * 選單的滾輪轉完才派獎（{@link #settle}）。中途關掉選單、斷線（離線事件最先處理）、關伺服器（模組停用）都馬上結算，
 * 不會白扣押注、也不會多拿。主執行緒用。
 */
public final class SlotsService {

    /** 營地放過拉霸機的記號（世界資料夾裡；刪掉的話下次啟動會再放一次）。 */
    private static final String MACHINE_MARKER = "exilecore-slots-v1.txt";
    /** 離拉霸機幾格內算在機台旁邊（/slots 在營地外也能用）。 */
    private static final double NPC_RANGE = 6;
    /** 金幣的顏色（跟金幣的提示一樣）。 */
    static final String GOLD_COLOR = "#f5b942";

    /** 一把：按下去時就決定好的結果。 */
    static final class Spin {
        final long bet;
        final SlotsConfig.Outcome outcome;
        final int[] line;
        long gold;
        SlotsConfig.CurrencyPrize currency;
        SlotsConfig.CurrencyPrize bonus;
        String cosmetic;
        /** 這一把頭獎給的稱號（這個押注額那一階；沒有 null）。 */
        SlotsConfig.Title title;
        boolean shown;

        Spin(final long bet, final SlotsConfig.Outcome outcome, final int[] line) {
            this.bet = bet;
            this.outcome = outcome;
            this.line = line;
        }

        boolean win() {
            return outcome != null;
        }
    }

    /** 本次戰績（這次登入）。 */
    static final class Session {
        int spins;
        int wins;
        long wagered;
        long returned;
        long best;
        int jackpots;
    }

    private final ExileCore plugin;
    private final SlotsConfig config;
    private final Map<UUID, Spin> pending = new HashMap<>();
    private final Map<UUID, Long> lastSpin = new HashMap<>();
    private final Map<UUID, Long> autoReady = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    SlotsService(final ExileCore plugin, final SlotsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public SlotsConfig config() {
        return config;
    }

    private RandomGenerator random() {
        return plugin.random();
    }

    // ---- 稱號 ----

    /** 拉霸限定稱號（每個押注額一個）登記到稱號系統（啟用與重讀時）。 */
    void registerTitle() {
        final BountyService bounties = plugin.bounties();
        if (bounties == null || config.titles().isEmpty()) {
            return;
        }
        final Map<String, BountyConfig.Title> defs = new java.util.LinkedHashMap<>();
        for (final SlotsConfig.Title title : config.titles()) {
            defs.put(title.id(), new BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        bounties.registerTitles(defs);
        bounties.setTitleSource("slots", new BountyService.TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of(); // 拿到時寫進帳號（grantTitle）
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                final SlotsConfig.Title current = config.titleById(id);
                if (current == null) {
                    return null;
                }
                return List.of("<gray>營地的拉霸機（/slots）：押 <white>" + GoldService.format(current.bet()) + "</white> 金幣中頭獎就拿到",
                        "<dark_gray>只算這個押注額（押更大的中頭獎不會給這一個）",
                        "<gray>擁有就有加成（帳號共用，不用戴），拉霸稱號全部疊加");
            }

            @Override
            public String sourceName(final String id) {
                return config.titleById(id) != null ? "拉霸機" : null;
            }
        });
    }

    /** 有沒有這個拉霸限定稱號。 */
    boolean ownsTitle(final Player player, final SlotsConfig.Title title) {
        return title != null && plugin.bounties() != null && plugin.bounties().ownedTitles(player).contains(title.id());
    }

    // ---- 能不能用 ----

    /** 在這裡能不能開拉霸機（營地、機台旁邊、管理員）；可以回傳 null。 */
    String placeProblem(final Player player, final boolean viaNpc) {
        if (viaNpc || !config.townOnly() || player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            return null;
        }
        if (plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
            return null;
        }
        if (plugin.npcs() != null) {
            for (final Entity entity : player.getNearbyEntities(NPC_RANGE, NPC_RANGE, NPC_RANGE)) {
                if (plugin.npcs().type(entity).orElse(null) == NpcType.SLOTS) {
                    return null;
                }
            }
        }
        return "拉霸機只能在營地用";
    }

    /** 還在轉的那一把（沒有 null）。 */
    Spin pending(final Player player) {
        return pending.get(player.getUniqueId());
    }

    Session session(final Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), uuid -> new Session());
    }

    private final Map<UUID, Long> chosen = new HashMap<>();

    /** 上次選的押注額（這次登入；沒有 null）。 */
    Long chosenBet(final Player player) {
        return chosen.get(player.getUniqueId());
    }

    void choose(final Player player, final long bet) {
        chosen.put(player.getUniqueId(), bet);
    }

    /** 2026-11-02 照站長：加速（1、2、4 倍；記到下線）；這次之前沒有。 */
    private final Map<UUID, Integer> speeds = new HashMap<>();

    int speed(final Player player) {
        return speeds.getOrDefault(player.getUniqueId(), 1);
    }

    /** 2026-11-02 照站長：自動連拉一次拉幾把（玩家自己選；記到下線）。這次之前固定 slots.yml 的 auto.spins。 */
    static final int[] AUTO_CHOICES = {5, 10, 20, 50, 100, 200, 500};
    private final Map<UUID, Integer> autoCounts = new HashMap<>();

    int autoCount(final Player player) {
        return autoCounts.getOrDefault(player.getUniqueId(), config.autoSpins());
    }

    /** 換下一個（step 1）或上一個（step -1）把數，到頭繞回去。 */
    int cycleAutoCount(final Player player, final int step) {
        final int current = autoCount(player);
        int index = 0;
        for (int i = 0; i < AUTO_CHOICES.length; i++) {
            if (AUTO_CHOICES[i] <= current) {
                index = i;
            }
        }
        final int next = AUTO_CHOICES[Math.floorMod(index + step, AUTO_CHOICES.length)];
        autoCounts.put(player.getUniqueId(), next);
        return next;
    }

    /** 換下一段速度：1 → 2 → 4 → 1。 */
    int cycleSpeed(final Player player) {
        final int next = switch (speed(player)) {
            case 1 -> 2;
            case 2 -> 4;
            default -> 1;
        };
        speeds.put(player.getUniqueId(), next);
        return next;
    }

    /** 自動連拉還要等幾秒（0 = 可以開）。 */
    long autoWaitSeconds(final Player player) {
        final Long ready = autoReady.get(player.getUniqueId());
        final long left = ready == null ? 0 : ready - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 自動連拉結束（拉完或停下）：開始算冷卻。 */
    void autoEnded(final Player player) {
        autoReady.put(player.getUniqueId(), System.currentTimeMillis() + config.autoCooldownSeconds() * 1000L);
    }

    // ---- 拉一把 ----

    /** 拉一把的結果：成功是 spin，不行是 problem。 */
    record Started(Spin spin, String problem) {
    }

    /**
     * 拉一把：扣押注、抽好結果（記成還沒結算）。選單轉完呼叫 {@link #settle}。
     */
    Started start(final Player player, final long bet) {
        if (!config.enabled()) {
            return new Started(null, "拉霸機暫停中");
        }
        if (!config.bets().contains(bet)) {
            return new Started(null, "沒有這個押注額");
        }
        final UUID uuid = player.getUniqueId();
        if (pending.containsKey(uuid)) {
            return new Started(null, "還在轉");
        }
        final long now = System.currentTimeMillis();
        final Long last = lastSpin.get(uuid);
        if (last != null && now - last < config.minGapMillis()) {
            return new Started(null, "太快了，等一下再拉");
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return new Started(null, "先建立角色（/char）");
        }
        final BountyService bounties = plugin.bounties();
        if (bounties == null) {
            return new Started(null, "委託模組沒有啟用（帳號資料），拉霸機不能用");
        }
        final String problem = bounties.spendProblem(player);
        if (problem != null) {
            return new Started(null, problem);
        }
        if (!plugin.gold().take(player, bet, EconomyLedger.SLOTS_BET)) {
            return new Started(null, "金幣不夠（押 " + GoldService.format(bet) + "，你有 " + GoldService.format(plugin.gold().balance(player)) + "）");
        }
        lastSpin.put(uuid, now);
        final RandomGenerator random = random();
        final SlotsConfig.Outcome outcome = config.roll(random).orElse(null);
        final Spin spin = new Spin(bet, outcome, config.line(outcome, random));
        resolve(player, spin, random);
        pending.put(uuid, spin);
        final Session session = session(player);
        session.spins++;
        session.wagered += bet;
        return new Started(spin, null);
    }

    /** 獎品照結果決定好（通貨抽哪一種、時裝抽哪一件、要不要給稱號）。 */
    private void resolve(final Player player, final Spin spin, final RandomGenerator random) {
        final SlotsConfig.Outcome outcome = spin.outcome;
        if (outcome == null) {
            return;
        }
        switch (outcome.kind()) {
            case GOLD -> spin.gold = SlotsConfig.goldPrize(outcome.multiplier(), spin.bet);
            case CURRENCY -> {
                spin.currency = config.currencyPrize(outcome.pool(), outcome.multiplier(), spin.bet, this::value, random).orElse(null);
                if (spin.currency == null) {
                    spin.gold = SlotsConfig.goldPrize(outcome.multiplier(), spin.bet); // 押注太小，一顆都換不到：改給金幣
                }
            }
            case COSMETIC -> {
                final List<String> unowned = unownedCosmetics(player);
                if (unowned.isEmpty()) {
                    spin.gold = SlotsConfig.goldPrize(outcome.multiplier(), spin.bet); // 都有了：改給金幣
                } else {
                    spin.cosmetic = unowned.get(random.nextInt(unowned.size()));
                }
            }
            case JACKPOT -> {
                spin.gold = SlotsConfig.goldPrize(outcome.multiplier(), spin.bet);
                if (outcome.bonus() != null) {
                    spin.bonus = config.currencyPrize(outcome.bonus().pool(), outcome.bonus().multiplier(), spin.bet, this::value, random).orElse(null);
                    if (spin.bonus == null) {
                        spin.gold += SlotsConfig.goldPrize(outcome.bonus().multiplier(), spin.bet);
                    }
                }
                // 2026-11-02 照站長：只給這個押注額那一階（押更大的不會順便給比較低的）；已經有、被管理員收回的不給
                final SlotsConfig.Title title = config.titleFor(spin.bet);
                if (canGetTitle(player, title)) {
                    spin.title = title;
                }
            }
        }
    }

    private boolean canGetTitle(final Player player, final SlotsConfig.Title title) {
        return title != null && plugin.bounties() != null && !ownsTitle(player, title) && !plugin.bounties().titleRevoked(player, title.id());
    }

    /** 通貨的參考價值（金幣；economy.yml，跟著市價走）。 */
    long value(final String currencyId) {
        return plugin.economy() == null ? 0 : plugin.economy().config().value(currencyId);
    }

    /** 拉霸限定時裝裡還沒有的（sponsor.yml 有定義的才算）。 */
    List<String> unownedCosmetics(final Player player) {
        final List<String> list = new ArrayList<>();
        if (plugin.sponsors() == null) {
            return list;
        }
        for (final String id : config.cosmetics()) {
            if (plugin.sponsors().config().cosmetics().containsKey(id) && !plugin.sponsors().ownsCosmetic(player, id)) {
                list.add(id);
            }
        }
        return list;
    }

    String cosmeticName(final String id) {
        final var cosmetic = plugin.sponsors() == null ? null : plugin.sponsors().config().cosmetics().get(id);
        return cosmetic == null ? id : cosmetic.name();
    }

    String currencyName(final String id) {
        final CurrencyType type = plugin.currencies().repository().get(id);
        return type == null ? id : type.name();
    }

    // ---- 結算 ----

    /** 還沒結算的那一把派獎（滾輪轉完、關掉選單、離線、關服）；沒有就什麼都不做。回傳結算的那一把。 */
    Spin settle(final Player player) {
        final Spin spin = pending.remove(player.getUniqueId());
        if (spin == null) {
            return null;
        }
        long returned = 0;
        if (spin.gold > 0) {
            if (plugin.gold().earn(player, spin.gold, EconomyLedger.SLOTS)) {
                returned += spin.gold;
            } else {
                plugin.getSLF4JLogger().error("[拉霸機] {} 的 {} 金幣派不出去（角色沒載入）", player.getName(), spin.gold);
            }
        }
        for (final SlotsConfig.CurrencyPrize prize : new SlotsConfig.CurrencyPrize[] {spin.currency, spin.bonus}) {
            if (prize != null) {
                give(player, prize);
                returned += value(prize.id()) * prize.amount();
            }
        }
        if (spin.cosmetic != null) {
            grantCosmetic(player, spin.cosmetic);
        }
        if (spin.title != null && !grantTitle(player, spin.title)) {
            spin.title = null; // 沒發出去（帳號沒載入、剛好已經有了）：公告、結果不寫稱號
        }
        final Session session = session(player);
        if (spin.win()) {
            session.wins++;
            session.returned += returned;
            session.best = Math.max(session.best, returned);
            if (spin.outcome.kind() == SlotsConfig.Kind.JACKPOT) {
                session.jackpots++;
            }
        }
        plugin.players().markDirty(player);
        log(player, spin);
        announce(player, spin);
        if (!spin.shown && spin.win() && player.isOnline()) {
            player.sendMessage(plugin.text().prefixed("<" + GOLD_COLOR + ">🎰 拉霸機<gray>（結算）：" + prizeText(spin)));
        }
        return spin;
    }

    /** 通貨照現有的方式放：倉庫的通貨頁 → 通貨袋 → 背包，都滿了掉在腳下（只有自己撿得到）。 */
    private void give(final Player player, final SlotsConfig.CurrencyPrize prize) {
        final CurrencyType type = plugin.currencies().repository().get(prize.id());
        if (type == null) {
            plugin.getSLF4JLogger().warn("[拉霸機] 沒有「{}」這種通貨，{} 的獎沒發出去", prize.id(), player.getName());
            return;
        }
        final int rest = plugin.economy().wallet().deposit(player, prize.id(), prize.amount());
        if (rest > 0) {
            plugin.economy().wallet().dropFor(player, plugin.currencies().create(type, rest));
        }
        plugin.economy().ledger().of(player).produced(prize.id(), EconomyLedger.SLOTS, prize.amount());
    }

    private void grantCosmetic(final Player player, final String id) {
        if (plugin.bounties() == null || !plugin.bounties().commitAccount(player, Map.of("sp.cos." + id, "1"), "拉霸機時裝")) {
            plugin.getSLF4JLogger().warn("[拉霸機] {} 的時裝 {} 發不出去（帳號沒載入）", player.getName(), id);
            return;
        }
        if (player.isOnline()) {
            player.sendMessage(plugin.text().prefixed("<#ff9ad5>收到時裝</#ff9ad5>：<white>" + ItemRenderer.escape(cosmeticName(id))
                    + " <#ffd24a>★ 拉霸機限定<gray>　贊助 → 3D 時裝 戴上　<white><click:run_command:'/sponsor'>/sponsor</click>"));
        }
    }

    /** 發稱號（頭獎）；發出去了回傳 true。全服公告在 {@link #announce}（頭獎公告寫出是哪個稱號，不另外發一則）。 */
    private boolean grantTitle(final Player player, final SlotsConfig.Title title) {
        if (plugin.bounties() == null) {
            return false;
        }
        final String problem = plugin.bounties().grantTitle(player, title.id());
        if (problem != null) {
            plugin.getSLF4JLogger().warn("[拉霸機] {} 的稱號 {} 發不出去：{}", player.getName(), title.name(), problem);
            return false;
        }
        final String tag = titleTag(title);
        if (player.isOnline()) {
            player.sendMessage(plugin.text().prefixed("<" + GOLD_COLOR + ">🎰 拉霸機頭獎：<gray>你拿到了限定稱號 " + tag + "<gray>（怪物掉落金幣 +"
                    + number(title.goldFindPercent()) + "%，擁有就有、跟其他拉霸稱號疊加；已經戴上，/title 可以換）"));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
        return true;
    }

    /** 稱號的樣子（跟 TAB、聊天的一樣：【名字】，顏色可以是漸層）。 */
    static String titleTag(final SlotsConfig.Title title) {
        return tw.exilecore.bounty.MeritShop.tag(new BountyConfig.Title(title.id(), title.name(), title.color(), 0));
    }

    private String name(final Player player) {
        return plugin.chat() == null ? player.getName() : plugin.chat().display(player);
    }

    /** 中了什麼（一行，MiniMessage）。 */
    String prizeText(final Spin spin) {
        if (!spin.win()) {
            return "<gray>沒中";
        }
        final StringBuilder text = new StringBuilder();
        if (spin.outcome.kind() == SlotsConfig.Kind.JACKPOT) {
            text.append("<gradient:#ffe066:#ff9f1c><bold>頭獎！</bold></gradient> ");
        }
        if (spin.gold > 0) {
            text.append("<").append(GOLD_COLOR).append(">+").append(GoldService.format(spin.gold)).append(" 金幣</").append(GOLD_COLOR).append(">");
        }
        for (final SlotsConfig.CurrencyPrize prize : new SlotsConfig.CurrencyPrize[] {spin.currency, spin.bonus}) {
            if (prize != null) {
                text.append(text.isEmpty() ? "" : "<gray>、").append("<white>").append(ItemRenderer.escape(currencyName(prize.id())))
                        .append(" ×").append(prize.amount());
            }
        }
        if (spin.cosmetic != null) {
            text.append(text.isEmpty() ? "" : "<gray>、").append("<#ff9ad5>時裝「").append(ItemRenderer.escape(cosmeticName(spin.cosmetic))).append("」");
        }
        if (spin.title != null) {
            text.append(text.isEmpty() ? "" : "<gray>、").append("<#ffd24a>限定稱號 ").append(titleTag(spin.title));
        }
        return text.toString();
    }

    private void log(final Player player, final Spin spin) {
        if (!spin.win()) {
            return;
        }
        final SlotsConfig.Outcome outcome = spin.outcome;
        if (outcome.kind() == SlotsConfig.Kind.GOLD && outcome.multiplier() < 10) {
            return; // 小獎不記，主控台會被洗掉
        }
        plugin.getSLF4JLogger().info("[拉霸機] {} 押 {} → {}（金幣 {}{}{}{}{}）", player.getName(), spin.bet, outcome.name(), spin.gold,
                spin.currency == null ? "" : "，" + spin.currency.id() + " ×" + spin.currency.amount(),
                spin.bonus == null ? "" : "，" + spin.bonus.id() + " ×" + spin.bonus.amount(),
                spin.cosmetic == null ? "" : "，時裝 " + spin.cosmetic,
                spin.title == null ? "" : "，稱號 " + spin.title.id());
    }

    /** 公告：頭獎全服＋Discord；金幣倍數到 announce_multiplier 的只在遊戲內。 */
    private void announce(final Player player, final Spin spin) {
        if (!spin.win()) {
            return;
        }
        final SlotsConfig.Outcome outcome = spin.outcome;
        final boolean jackpot = outcome.kind() == SlotsConfig.Kind.JACKPOT;
        if (!jackpot && !(outcome.kind() == SlotsConfig.Kind.GOLD && outcome.multiplier() >= config.announceMultiplier())) {
            return;
        }
        final String who = ItemRenderer.escape(name(player));
        final String what = jackpot ? "<gradient:#ffe066:#ff9f1c><bold>頭獎</bold></gradient>" : "<white>×" + number(outcome.multiplier());
        final String message = "<" + GOLD_COLOR + ">🎰 <white>" + who + "</white><gray> 在營地的拉霸機押 " + GoldService.format(spin.bet)
                + " 金幣拉出 " + what + "<gray>，贏得 " + prizeText(spin);
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            if (!jackpot && plugin.players().hidesSystem(online)) {
                continue; // 過濾系統訊息的人不看小一點的獎（頭獎照常）
            }
            online.sendMessage(plugin.text().prefixed(message));
            if (jackpot) {
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.2f);
            }
        }
        if (jackpot && plugin.discord() != null) {
            // Discord 只發頭獎（照 rareDrop 的規則：只留真正的大獎，不洗版）
            final StringBuilder plain = new StringBuilder(GoldService.format(spin.gold) + " 金幣");
            for (final SlotsConfig.CurrencyPrize prize : new SlotsConfig.CurrencyPrize[] {spin.currency, spin.bonus}) {
                if (prize != null) {
                    plain.append("、").append(currencyName(prize.id())).append(" ×").append(prize.amount());
                }
            }
            if (spin.title != null) {
                plain.append("、限定稱號「").append(spin.title.name()).append("」（金幣掉落 +").append(number(spin.title.goldFindPercent())).append("%）");
            }
            plugin.discord().rareDrop("🎰 " + who + " 在營地的拉霸機押 " + GoldService.format(spin.bet) + " 金幣拉出頭獎，贏得 "
                    + ItemRenderer.escape(plain.toString()));
        }
    }

    static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    // ---- 離線、關服 ----

    /** 離線（最先處理，角色存檔之前）：還在轉的那一把馬上結算。 */
    void quit(final Player player) {
        settle(player);
        final UUID uuid = player.getUniqueId();
        sessions.remove(uuid);
        lastSpin.remove(uuid);
        chosen.remove(uuid);
        speeds.remove(uuid);
        autoCounts.remove(uuid);
    }

    /** 關服、重讀插件：每個人還在轉的那一把都結算。 */
    void settleAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            settle(player);
        }
        pending.clear();
    }

    // ---- 營地的機台 ----

    /** 第一次啟動：每條線的營地放一台拉霸機（放過、或已經有了就不放）。 */
    void placeMachines() {
        if (!config.autoPlace() || plugin.npcs() == null || plugin.world() == null || plugin.world().worlds() == null) {
            return;
        }
        final List<World> towns = plugin.world().worlds().channels().towns();
        for (final World town : towns) {
            final Path marker = town.getWorldPath().resolve(MACHINE_MARKER);
            if (Files.exists(marker)) {
                continue;
            }
            final double y = config.machineY() != null ? config.machineY() : tw.exilecore.worldgen.CampaignLayout.CAMP_Y + 1;
            final Location spot = new Location(town, config.machineX(), y, config.machineZ(), config.machineYaw(), 0f);
            town.getChunkAtAsync(spot).whenComplete((chunk, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null || chunk == null) {
                    plugin.getSLF4JLogger().warn("拉霸機：載入 {} 營地的區塊失敗，下次啟動再放", town.getName());
                    return;
                }
                chunk.addPluginChunkTicket(plugin);
                // 實體跟區塊分開載入：等兩秒再找有沒有已經放的
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    try {
                        boolean found = false;
                        final Location center = new Location(town, 0, spot.getY(), 0);
                        for (final Entity entity : plugin.npcs().loaded()) {
                            if (entity.getWorld().equals(town) && entity.getLocation().distanceSquared(center) < 80 * 80
                                    && plugin.npcs().type(entity).orElse(null) == NpcType.SLOTS) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            plugin.npcs().spawn(spot, NpcType.SLOTS);
                            plugin.getSLF4JLogger().info("拉霸機：放在 {} 的營地（{}, {}, {}）", town.getName(), spot.getX(), spot.getY(), spot.getZ());
                        }
                        Files.writeString(marker, "ExileCore 拉霸機放過了（data/slots.yml 的 machine）。刪掉這個檔，下次啟動營地沒有拉霸機的話會再放一台\n");
                    } catch (final Exception e) {
                        plugin.getSLF4JLogger().warn("拉霸機：{} 的營地放不了：{}", town.getName(), e.toString());
                    } finally {
                        chunk.removePluginChunkTicket(plugin);
                    }
                }, 40L);
            }));
        }
    }
}
