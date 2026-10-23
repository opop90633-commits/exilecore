package tw.exilecore.sponsor;

import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.bounty.BountyService;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 贊助的執行期（主執行緒；{@link #chatColor}、{@link #bypassFull} 給非同步的聊天與登入用）。
 * <p>
 * 資料都存在帳號進度（account_progress，委託模組管的那一張）：星幣、累積贊助、買的格子、月卡、月卡幣、時裝幣、
 * 擁有的特效、戴著的特效、月卡幣換的稱號、自訂稱號、登入天數。花錢的操作跟角色存檔在同一個資料庫交易裡寫
 * （{@link BountyService#commitAccount}）；管理員發的星幣跟「這筆已經領了」同一個交易（{@link #grantWork}）。
 */
public final class SponsorService {

    // ---- 帳號進度的鍵 ----
    static final String COINS = "sp.coins";
    static final String TOTAL = "sp.total";
    static final String SLOTS = "sp.slots";
    static final String STASH = "sp.stash";
    static final String BAG = "sp.bag";
    /** 2026-10：通貨袋、地圖頁加大了幾次。 */
    static final String POUCH = "sp.pouch";
    static final String MAPS = "sp.maps";
    static final String STASH_CURRENCY = "sp.stashcur";
    static final String LISTING = "sp.listing";
    /** 改名卡（2026-10）：還有幾張。 */
    static final String RENAME = "sp.rename";
    /** 公會改名卡（2026-10）：還有幾張。 */
    static final String GUILD_RENAME = "sp.guild_rename";
    /** 這個帳號幫公會買過幾頁倉庫（只是紀錄：頁在公會身上）。 */
    static final String GUILD_STASH = "sp.guild_stash";
    /** 月卡：「開始那天|最後一天|發到哪一天」（epoch day）。 */
    static final String CARD = "sp.card";
    static final String CARD_COINS = "sp.mcoins";
    static final String FASHION_COINS = "sp.fcoins";
    static final String COSMETIC = "sp.cos.";
    static final String WORN = "sp.worn";
    static final String CARD_TITLE = "sp.title.";
    /** 限定稱號（2026-10，發放拿到的）：sp.ltitle.代號 = 1。 */
    static final String LIMITED_TITLE = "sp.ltitle.";
    static final String PITY = "sp.pity.";
    /** 月卡連續買了幾個月（中間斷超過一天就重算）。 */
    static final String CARD_STREAK = "sp.cardstreak";
    /** 月卡每日免費箱：上次開的那天（epoch day）。 */
    static final String FREE_BOX = "sp.freebox";
    /** 2026-10：還沒開的月卡免費抽獎箱（月卡每一天發一箱，沒開的累積下去）。 */
    static final String FREE_BOXES = "sp.freeboxes";
    /** 自訂稱號：「代號|#顏色|文字」。 */
    static final String CUSTOM_TITLE = "sp.ctitle";
    /** 自己選的名字顏色（2026-10）：累積到的某一階的代號（用那一階的名字顏色）；沒選照最高那一階的。 */
    static final String NAME_COLOR = "sp.namecolor";
    static final String FLY_OFF = "sp.fly_off";
    /** 箭袋的幻化不要顯示在手上（2026-10；沒寫 = 顯示）。 */
    static final String QUIVER_SKIN_OFF = "sp.quiver_skin_off";
    static final String LOGIN_DAYS = "login.days";
    /** 今天在線幾分鐘：「epoch day|分鐘」。 */
    static final String LOGIN_TODAY = "login.day";

    /** 自訂稱號最多幾個字。 */
    public static final int CUSTOM_TITLE_MAX = 8;
    private static final String BOOST_FILE = "sponsor-boosts.yml";
    static final String COIN = "<#f5b942>";
    static final String CARD_COLOR = "<#bcd4ff>";
    static final String FASHION_COLOR = "<#ff9ad5>";

    private final ExileCore plugin;
    private final SponsorConfig config;
    private final Random random = new Random();
    /** 線上玩家用到過的最高一階（非同步讀：聊天顏色、伺服器滿了）。 */
    private final Map<UUID, SponsorConfig.Tier> tiers = new ConcurrentHashMap<>();
    /** 登入時先查好的「伺服器滿了也能進」（AsyncPlayerPreLoginEvent 查資料庫）。 */
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    /** 今天在線的分鐘數：epoch day、分鐘。 */
    private final Map<UUID, long[]> online = new HashMap<>();
    /** 贊助模組開的營地飛行。 */
    private final Set<UUID> flying = new HashSet<>();
    private final Map<String, Boosts.Active> boosts = new LinkedHashMap<>();
    /** 2026-10 個人加倍藥：帳號進度的鍵（後面接加倍的代號），值是什麼時候結束（毫秒）。 */
    static final String PERSONAL_BOOST = "sp.pboost.";
    /** 2026-10-22：個人加倍「存著、還沒開」的時間（毫秒）：買了先存起來，自己開、自己關，下線自動關（剩的時間存回來）。 */
    static final String PERSONAL_BANK = "sp.pbank.";
    /** 線上玩家正在吃的個人加倍（玩家:代號）：結束的時候重算屬性表、通知。 */
    private final Set<String> personalSeen = new HashSet<>();
    private long lastDay = -1;

    SponsorService(final ExileCore plugin, final SponsorConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.perks = new TierPerks(plugin, this);
        loadBoosts();
        recompute();
    }

    public SponsorConfig config() {
        return config;
    }

    /** 2026-10-23：累積贊助每一階的主打。 */
    final TierPerks perks;

    BountyService accounts() {
        return plugin.bounties();
    }

    // ---- 讀帳號進度 ----

    String value(final Player player, final String key) {
        final BountyService accounts = accounts();
        return accounts == null ? null : accounts.progressValue(player, key);
    }

    long number(final Player player, final String key) {
        return parse(value(player, key));
    }

    static long parse(final String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(text.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public boolean loaded(final Player player) {
        return accounts() != null && accounts().accountLoaded(player);
    }

    public long coins(final Player player) {
        return number(player, COINS);
    }

    public long total(final Player player) {
        return number(player, TOTAL);
    }

    public long cardCoins(final Player player) {
        return number(player, CARD_COINS);
    }

    public long fashionCoins(final Player player) {
        return number(player, FASHION_COINS);
    }

    public long loginDays(final Player player) {
        return number(player, LOGIN_DAYS);
    }

    /** 用到過的最高一階（還沒到 null）。 */
    public SponsorConfig.Tier tier(final Player player) {
        return config.tierFor(total(player));
    }

    // ---- 給別的模組用：累積贊助的經驗、金幣、死亡懲罰（2026-10）----

    /** 累積贊助給的經驗加成（0.05 = 多 5%）：到過的每一階加起來（6 階各 5% = 30%）；沒有 0。 */
    public double experienceBonus(final Player player) {
        return config.reachedExperience(total(player)) / 100.0;
    }

    /** 累積贊助給的金幣加成（0.05 = 多 5%）：到過的每一階加起來；沒有 0。 */
    public double goldBonus(final Player player) {
        return config.reachedGold(total(player)) / 100.0;
    }

    // ---- 個人加倍藥（2026-10）：跟全服加倍「加起來」，不是乘起來（全服 2 倍＋個人 2 倍 = 3 倍）----

    /** 這種個人加倍什麼時候結束（毫秒；沒有 0）。 */
    public long personalBoostUntil(final Player player, final String typeId) {
        return number(player, PERSONAL_BOOST + typeId);
    }

    /** 正在吃的個人加倍。 */
    public List<SponsorConfig.BoostType> personalBoosts(final Player player) {
        final List<SponsorConfig.BoostType> list = new ArrayList<>();
        if (!config.enabled() || !loaded(player)) {
            return list;
        }
        final long now = System.currentTimeMillis();
        for (final SponsorConfig.BoostType type : config.boosts().values()) {
            if (personalBoostUntil(player, type.id()) > now) {
                list.add(type);
            }
        }
        return list;
    }

    /**
     * 個人經驗加倍：加在「1 + 累積贊助的加成」裡的值。全服的經驗倍數是乘在外面的，所以除掉它，
     * 讓結果是「全服倍數 + 個人多的」（全服 ×2、個人 ×2 → ×3；全服 ×2.1 → ×3.1）。
     * 2026-10-16：全服倍數改成完整的那個（character.yml 的 experience_bonus × 贊助的全服加倍 × 在線人數），
     * 以前只除贊助的全服加倍，character.yml 開 ×2 時變成乘起來（×4.2）。
     */
    public double personalExperience(final Player player) {
        double extra = 0;
        for (final SponsorConfig.BoostType type : personalBoosts(player)) {
            extra += type.experience() - 1;
        }
        return extra <= 0 ? 0 : extra / Math.max(1, plugin.players().config().experienceBonus());
    }

    /** 個人金幣加倍：同 {@link #personalExperience}（全服 ×2、個人 ×2 → ×3）。 */
    public double personalGold(final Player player) {
        double extra = 0;
        for (final SponsorConfig.BoostType type : personalBoosts(player)) {
            extra += type.gold() - 1;
        }
        // 全服的金幣倍數是 drops.yml 的 server_bonus.gold × 全服加倍 × 在線人數加成（DropTable.serverGold）；只除全服加倍會變成相乘
        final double server = plugin.monsterModule() == null ? Boosts.gold() : plugin.monsterModule().drops().serverGold();
        return extra <= 0 ? 0 : extra / Math.max(1, server);
    }

    /** 個人掉落數量、稀有度加倍：進屬性表的物品數量、稀有度（跟全服的 +100% 本來就是加起來的）。 */
    public List<tw.exilecore.stats.Modifier> personalModifiers(final Player player) {
        double quantity = 0;
        double rarity = 0;
        for (final SponsorConfig.BoostType type : personalBoosts(player)) {
            quantity += type.quantity();
            rarity += type.rarity();
        }
        final List<tw.exilecore.stats.Modifier> list = new ArrayList<>();
        if (quantity > 0) {
            list.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DROP_QUANTITY, tw.exilecore.stats.ModType.INCREASED,
                    quantity, Set.of(), "個人加倍藥"));
        }
        if (rarity > 0) {
            list.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DROP_RARITY, tw.exilecore.stats.ModType.INCREASED,
                    rarity, Set.of(), "個人加倍藥"));
        }
        return list;
    }

    private static String trimNumber(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 個人加倍的名字：「全服經驗加倍」→「個人經驗加倍」。 */
    static String personalName(final SponsorConfig.BoostType type) {
        return type.name().startsWith("全服") ? "個人" + type.name().substring(2) : "個人" + type.name();
    }

    /** 存著還沒開的時間（毫秒）。 */
    public long personalBoostBank(final Player player, final String typeId) {
        return number(player, PERSONAL_BANK + typeId);
    }

    /** 正在跑的剩幾毫秒（沒開 0）。 */
    public long personalBoostLeft(final Player player, final String typeId) {
        return Math.max(0, personalBoostUntil(player, typeId) - System.currentTimeMillis());
    }

    /**
     * 買一罐（2026-10-22 改）：正在開著就接著延長；沒開就存起來（到贊助選單右鍵開）。
     * 存著的加上正在跑的，最多 max_minutes。
     */
    String buyPersonalBoost(final Player player, final String typeId) {
        final SponsorConfig.BoostType type = config.boosts().get(typeId);
        if (type == null) {
            return "沒有這種加倍";
        }
        final long now = System.currentTimeMillis();
        final long running = personalBoostLeft(player, type.id());
        final long bank = personalBoostBank(player, type.id());
        final long add = config.personalBoostMinutes() * 60_000L;
        if (running + bank + add > config.personalBoostMaxMinutes() * 60_000L) {
            return personalName(type) + "最多存 " + config.personalBoostMaxMinutes() + " 分鐘，先用掉一些再買";
        }
        final Map<String, String> values = running > 0
                ? Map.of(PERSONAL_BOOST + type.id(), String.valueOf(now + running + add))
                : Map.of(PERSONAL_BANK + type.id(), String.valueOf(bank + add));
        final String problem = spend(player, config.personalBoostPrice(), values, personalName(type) + " " + config.personalBoostMinutes() + " 分鐘");
        if (problem != null) {
            return problem;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        if (running > 0) {
            final long minutes = (running + add + 30_000) / 60_000;
            player.sendMessage(plugin.text().prefixed("<#ffd27f>" + ItemRenderer.escape(personalName(type)) + "<gray>：延長，剩 <white>" + minutes
                    + "</white> 分鐘<dark_gray>（只有你自己；跟全服加倍加起來算）"));
        } else {
            final long minutes = (bank + add + 30_000) / 60_000;
            player.sendMessage(plugin.text().prefixed("<#ffd27f>" + ItemRenderer.escape(personalName(type)) + "<gray>：存起來了，共 <white>" + minutes
                    + "</white> 分鐘<dark_gray>（贊助選單右鍵開啟；不用時再右鍵關掉，下線自動關，剩的時間留著）"));
        }
        return null;
    }

    /** 開、關個人加倍（2026-10-22）：開 = 存著的時間開始跑；關 = 剩的時間存回去。 */
    String togglePersonalBoost(final Player player, final String typeId) {
        final SponsorConfig.BoostType type = config.boosts().get(typeId);
        if (type == null) {
            return "沒有這種加倍";
        }
        if (accounts() == null || !accounts().accountLoaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        final long now = System.currentTimeMillis();
        final long running = personalBoostLeft(player, type.id());
        final long bank = personalBoostBank(player, type.id());
        final String key = player.getUniqueId() + ":" + type.id();
        if (running > 0) {
            final Map<String, String> values = new LinkedHashMap<>();
            values.put(PERSONAL_BOOST + type.id(), null);
            values.put(PERSONAL_BANK + type.id(), String.valueOf(bank + running));
            accounts().commitAccount(player, values, "贊助：" + personalName(type) + "暫停");
            personalSeen.remove(key);
            plugin.players().markDirty(player);
            player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(personalName(type)) + "關掉了，剩 <white>"
                    + minutesText(bank + running) + "</white> 存著"));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
            return null;
        }
        if (bank <= 0) {
            return "你沒有存著的" + personalName(type) + "，先買一罐（左鍵）";
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(PERSONAL_BOOST + type.id(), String.valueOf(now + bank));
        values.put(PERSONAL_BANK + type.id(), null);
        accounts().commitAccount(player, values, "贊助：" + personalName(type) + "開啟");
        personalSeen.add(key);
        plugin.players().markDirty(player); // 掉落數量、稀有度進屬性表
        player.sendMessage(plugin.text().prefixed("<#ffd27f>" + ItemRenderer.escape(personalName(type)) + "<gray>開啟：剩 <white>" + minutesText(bank)
                + "</white><dark_gray>（只有你自己；跟全服加倍加起來算；下線自動關）"));
        player.showTitle(Title.title(plugin.text().parse("<#ffd27f>" + ItemRenderer.escape(personalName(type))),
                plugin.text().parse("<gray>" + minutesText(bank)), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        return null;
    }

    /** 下線（或登入時發現還開著：當機、沒存到）：開著的全部關掉，剩的時間存回去。 */
    void pausePersonalBoosts(final Player player) {
        pausePersonalBoosts(player, false);
    }

    /** onlyStale：只關「這次上線還沒開過」的（登入時用；換角色時開著的照開著）。 */
    private void pausePersonalBoosts(final Player player, final boolean onlyStale) {
        if (accounts() == null || !accounts().accountLoaded(player)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Map<String, String> values = new LinkedHashMap<>();
        for (final SponsorConfig.BoostType type : config.boosts().values()) {
            final long until = personalBoostUntil(player, type.id());
            if (until <= 0 || onlyStale && personalSeen.contains(player.getUniqueId() + ":" + type.id())) {
                continue;
            }
            values.put(PERSONAL_BOOST + type.id(), null);
            if (until > now) {
                values.put(PERSONAL_BANK + type.id(), String.valueOf(personalBoostBank(player, type.id()) + until - now));
            }
        }
        if (!values.isEmpty()) {
            accounts().commitAccount(player, values, "贊助：個人加倍下線暫停");
        }
        if (!onlyStale) {
            final String prefix = player.getUniqueId() + ":";
            personalSeen.removeIf(key -> key.startsWith(prefix)); // 下線：下次登入一律從關著開始
        }
    }

    private static String minutesText(final long millis) {
        final long seconds = Math.max(0, millis / 1000);
        return seconds >= 60 ? (seconds + 30) / 60 + " 分鐘" : seconds + " 秒";
    }

    /** 每秒：個人加倍開始（登入時）、結束——重算屬性表，結束時通知。 */
    private void personalTick() {
        final long now = System.currentTimeMillis();
        final Set<String> online = new HashSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!loaded(player)) {
                continue;
            }
            for (final SponsorConfig.BoostType type : config.boosts().values()) {
                final String key = player.getUniqueId() + ":" + type.id();
                final boolean active = config.enabled() && personalBoostUntil(player, type.id()) > now;
                if (active) {
                    online.add(key);
                    if (personalSeen.add(key)) {
                        plugin.players().markDirty(player);
                    }
                } else if (personalSeen.remove(key)) {
                    plugin.players().markDirty(player);
                    player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(personalName(type)) + "結束了"));
                }
            }
        }
        personalSeen.retainAll(online);
    }

    /** 2026-10-23 晚：最高階的筏多大（格；沒有 0）、吊床多回幾點精力（星骸之海用）。 */
    public int raftSizeBonus(final Player player) {
        return config.enabled() ? perks.perksOf(player).raftSize() : 0;
    }

    public int bedStaminaBonus(final Player player) {
        return config.enabled() ? perks.perksOf(player).bedStamina() : 0;
    }

    /** 2026-10-23：累積贊助是第幾階（1 起；沒有 0）——TAB 的排序用（管理員 → 贊助（階級高的在前）→ 一般玩家）。 */
    public int tierRank(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        return tier == null ? 0 : config.tiers().indexOf(tier) + 1;
    }

    /** 2026-10-23：升級的煙火（累積贊助的主打）。 */
    public void celebrateLevel(final Player player) {
        if (config.enabled()) {
            perks.celebrateLevel(player);
        }
    }

    /** 死亡懲罰的比例：累積贊助那一階有寫 death_penalty 就用那個（不會比原本的重），沒有照原本的 base。 */
    public double deathPenalty(final Player player, final double base) {
        final SponsorConfig.Tier tier = tier(player);
        return tier == null || tier.deathPenalty() < 0 ? base : Math.min(base, tier.deathPenalty() / 100.0);
    }

    // ---- 給別的模組用：格子、名字顏色 ----

    public int extraCharacterSlots(final Player player) {
        return (int) Math.min(config.slotMax(), number(player, SLOTS)) + perks.characterSlots(player); // 2026-10-23：加上累積贊助送的
    }

    public int extraStashPages(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        return (int) Math.min(SponsorConfig.MAX_EXTRA_STASH, (tier == null ? 0 : tier.stashPages()) + SponsorConfig.capped(number(player, STASH), config.stashMax()));
    }

    public int extraBagPages(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        return (int) Math.min(SponsorConfig.MAX_EXTRA_BAG, (tier == null ? 0 : tier.bagPages()) + SponsorConfig.capped(number(player, BAG), config.bagMax()));
    }

    /** 累積贊助送的上架上限（0 = 沒有，照 economy.yml）。 */
    public int tierListings(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        return tier == null ? 0 : tier.listings();
    }

    /** 用星幣多買的上架格。 */
    public int boughtListings(final Player player) {
        return (int) Math.min(SponsorConfig.MAX_LISTINGS, SponsorConfig.capped(number(player, LISTING), config.listingMax()));
    }

    /** 商店頁滿了的時候補的一句。 */
    public String listingsHint(final Player player) {
        return SponsorConfig.atMax(number(player, LISTING), config.listingMax()) ? "" : "（贊助 /sponsor 可以加上架格）";
    }

    /** 名字顏色（"#rrggbb"、"gradient:…" 或 "rainbow"；沒有 null）。 */
    public String nameColor(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        if (tier == null) {
            return null;
        }
        // 2026-10-23 晚：最高階自選的顏色（/sponsor color）記在同一個鍵，開頭是 # 或 gradient:、rainbow
        final String own = value(player, NAME_COLOR);
        if (own != null && tier.perks().customNameColor() && (own.startsWith("#") || own.startsWith("gradient:") || own.equals("rainbow"))) {
            return own;
        }
        final SponsorConfig.Tier chosen = chosenColorTier(player);
        return chosen != null ? chosen.nameColor() : tier.nameColor();
    }

    /** 2026-10-23 晚：名字前的徽章（最高階的 ★；沒有空字串）。 */
    public String nameBadge(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        if (tier == null || tier.perks().badge().isEmpty()) {
            return "";
        }
        return "<" + tier.color() + ">" + ItemRenderer.escape(tier.perks().badge()) + "</" + tier.color() + ">";
    }

    /** 2026-10-23 晚：自選名字顏色（/sponsor color；null = 回到階級的顏色）；回傳問題，成功 null。 */
    String setNameColor(final Player player, final String raw) {
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        final SponsorConfig.Tier tier = tier(player);
        if (tier == null || !tier.perks().customNameColor()) {
            return "名字顏色自選要累積贊助到有這個功能的階級（/sponsor 看）";
        }
        final String color = raw == null ? null : titleColor(raw);
        if (raw != null && color == null) {
            return "顏色要寫成 #rrggbb（例如 #ffd24a）；漸層寫 2～5 個顏色，或寫 rainbow（彩虹）";
        }
        accounts().writeProgress(player, NAME_COLOR, color);
        plugin.players().updateListName(player);
        return null;
    }

    /** 累積到的、有名字顏色的那幾階（由低到高）。 */
    List<SponsorConfig.Tier> colorTiers(final Player player) {
        final long total = total(player);
        final List<SponsorConfig.Tier> list = new ArrayList<>();
        for (final SponsorConfig.Tier each : config.tiers()) {
            if (total >= each.amount() && each.nameColor() != null) {
                list.add(each);
            }
        }
        return list;
    }

    /** 自己選的那一階的名字顏色（2026-10：累積到的那幾階隨時換）；沒選或那一階還沒累積到 null。 */
    SponsorConfig.Tier chosenColorTier(final Player player) {
        final String id = value(player, NAME_COLOR);
        if (id == null) {
            return null;
        }
        for (final SponsorConfig.Tier each : colorTiers(player)) {
            if (each.id().equals(id)) {
                return each;
            }
        }
        return null;
    }

    /** 換成累積到的下一個（step = 1）或上一個（-1）名字顏色；回傳問題。 */
    String cycleNameColor(final Player player, final int step) {
        final List<SponsorConfig.Tier> list = colorTiers(player);
        if (list.isEmpty()) {
            return "名字顏色要累積贊助才有";
        }
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        final SponsorConfig.Tier chosen = chosenColorTier(player);
        int index = chosen == null ? list.size() - 1 : list.indexOf(chosen);
        index = Math.floorMod(index + step, list.size());
        final Map<String, String> values = new java.util.HashMap<>();
        values.put(NAME_COLOR, index == list.size() - 1 ? null : list.get(index).id()); // 最高那一階 = 預設，不用記
        accounts().commitAccount(player, values, "名字顏色");
        plugin.players().updateListName(player);
        return null;
    }

    /** 聊天可以上色（非同步）。 */
    public boolean chatColor(final UUID uuid) {
        final SponsorConfig.Tier tier = tiers.get(uuid);
        return tier != null && tier.chatColor();
    }

    // ---- 登入、角色載入 ----

    /** AsyncPlayerPreLoginEvent（非同步）：查累積贊助，夠的話記下來，伺服器滿了也放行。 */
    void preLogin(final UUID uuid) {
        bypass.remove(uuid);
        if (!config.enabled()) {
            return;
        }
        try {
            final String text = plugin.database().sync(connection -> {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT progress_value FROM account_progress WHERE account_uuid = ? AND progress_key = ?")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, TOTAL);
                    try (java.sql.ResultSet result = statement.executeQuery()) {
                        return result.next() ? result.getString(1) : null;
                    }
                }
            });
            final SponsorConfig.Tier tier = config.tierFor(parse(text));
            if (tier != null && tier.bypassFull()) {
                bypass.add(uuid);
            }
        } catch (final Exception e) {
            plugin.getSLF4JLogger().warn("查 {} 的累積贊助失敗（伺服器滿了時照一般玩家）", uuid, e);
        }
    }

    boolean bypassFull(final UUID uuid) {
        return bypass.contains(uuid);
    }

    /** 帳號與角色都載入好了（登入、換角色）。 */
    void onReady(final Player player) {
        if (!config.enabled()) {
            return;
        }
        pausePersonalBoosts(player, true); // 2026-10-22：上線時個人加倍一律是關著的（上次下線沒存到、當機也一樣），要用自己開
        applyPouch(player);
        if (vouchers != null) {
            vouchers.onReady(player); // 星幣券：下線時開的券、兌換了還沒加的星幣，上線補上
        }
        registerCustomTitle(player); // 先登記自訂稱號，再重算名字（2026-10：順序反了，重開之後 TAB 不顯示自訂稱號）
        refresh(player);
        payCard(player, true);
        perks.refresh(player); // 2026-10-23：每週免費箱、到階送的時裝、終身月卡
        greet(player);
    }

    void onQuit(final Player player) {
        final UUID uuid = player.getUniqueId();
        tiers.remove(uuid);
        bypass.remove(uuid);
        final long[] today = online.remove(uuid);
        if (today != null && today[1] < config.loginMinutes() && accounts() != null && accounts().accountLoaded(player)) {
            accounts().writeProgress(player, LOGIN_TODAY, today[0] + "|" + today[1]);
        }
        flying.remove(uuid);
        // 2026-10-18：試穿下線要清（以前一直留著）；再試穿的冷卻只清已經過了的（還沒過的留著，重登不能跳過冷卻）
        tryOns.remove(uuid);
        final long tick = plugin.getServer().getCurrentTick();
        tryOnAgain.values().removeIf(again -> again <= tick);
    }

    /** 累積贊助、買的格子變了：名字、稱號、背包頁、倉庫頁跟上。 */
    void refresh(final Player player) {
        final SponsorConfig.Tier tier = tier(player);
        if (tier == null) {
            tiers.remove(player.getUniqueId());
        } else {
            tiers.put(player.getUniqueId(), tier);
        }
        final PlayerState state = plugin.players().get(player);
        // 2026-10-17：開著隨身背包或倉庫的時候先關掉（關的時候把畫面寫回去），再搬東西——
        // 以前收回頁數時畫面上的東西之後寫回會變兩份，或把搬進來的蓋掉
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof tw.exilecore.loot.BagMenu
                || player.getOpenInventory().getTopInventory().getHolder() instanceof tw.exilecore.stash.StashMenu) {
            player.closeInventory();
        }
        if (state.isLoaded()) {
            state.bag().setPages(tw.exilecore.character.Bag.BASE_PAGES + extraBagPages(player));
            if (loaded(player) && state.bag().storedPages() > state.bag().pages()) {
                // 2026-10：收回累積贊助、買的頁：後面的東西搬到前面，搬不完的頁只能拿出來
                final int locked = state.bag().compact();
                state.markDirty();
                if (locked > 0) {
                    player.sendMessage(plugin.text().prefixed("<yellow>隨身背包有 " + locked
                            + " 頁已經收回：裡面的東西前面放不下，只能拿出來（拿空就會消失）"));
                }
            }
        }
        if (plugin.stash() != null) {
            plugin.stash().ensurePages(player);
        }
        if (accounts() != null) {
            accounts().refreshTitle(player);
        }
        plugin.players().updateListName(player); // 2026-10-23：TAB 照贊助階級排、名字前的徽章
    }

    private void greet(final Player player) {
        final long coins = coins(player);
        final String card = cardText(player);
        if (coins > 0 || card != null) {
            player.sendMessage(plugin.text().prefixed("<gray>贊助：" + COIN + "星幣 " + format(coins) + "</#f5b942>"
                    + (card == null ? "" : "<gray>　" + card) + "<gray>　<white><click:run_command:'/sponsor'><hover:show_text:'打開贊助'>/sponsor</hover></click>"));
        }
    }

    // ---- 管理員發的星幣 ----

    /**
     * 發星幣（管理網頁、/ec sponsor give、贊助商城）：記憶體改好，回傳寫進資料庫的工作（放進領取的交易）。
     * paid = 儲值（算進累積贊助）；false = 贈送。帳號還沒載入回傳 null（等一下再領）。
     */
    public CharacterRepository.ExtraWork grantWork(final Player player, final long amount, final boolean paid) {
        if (accounts() == null || !accounts().accountLoaded(player) || amount <= 0) {
            return null;
        }
        final SponsorConfig.Tier before = tier(player);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(COINS, String.valueOf(coins(player) + amount));
        if (paid) {
            values.put(TOTAL, String.valueOf(total(player) + amount));
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        plugin.getSLF4JLogger().info("[贊助] {} 收到 {} 星幣（{}），現在 {}，累積 {}", player.getName(), amount, paid ? "儲值" : "贈送",
                coins(player), total(player));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.text().prefixed(COIN + "收到 " + format(amount) + " 星幣</#f5b942><gray>（" + (paid ? "感謝你的贊助！" : "贈送")
                        + "）現在有 " + format(coins(player)) + "　<white><click:run_command:'/sponsor'>/sponsor</click>"));
                tierUp(player, before);
            }
        });
        return work;
    }

    /**
     * 發月卡幣或時裝幣（2026-10，管理網頁、/ec sponsor give … card|fashion）：跟 {@link #grantWork} 一樣回傳寫進資料庫的工作；
     * 帳號還沒載入回傳 null。
     */
    public CharacterRepository.ExtraWork grantCoinsWork(final Player player, final long amount, final boolean fashion) {
        if (accounts() == null || !accounts().accountLoaded(player) || amount <= 0) {
            return null;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        if (fashion) {
            values.put(FASHION_COINS, String.valueOf(fashionCoins(player) + amount));
        } else {
            values.put(CARD_COINS, String.valueOf(cardCoins(player) + amount));
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        final String what = fashion ? "時裝幣" : "月卡幣";
        plugin.getSLF4JLogger().info("[贊助] {} 收到 {} {}", player.getName(), amount, what);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.text().prefixed((fashion ? FASHION_COLOR : CARD_COLOR) + "收到 " + format(amount) + " " + what
                        + "<gray>　現在有 " + format(fashion ? fashionCoins(player) : cardCoins(player))
                        + "　<white><click:run_command:'/sponsor'>/sponsor</click>"));
            }
        });
        return work;
    }

    /** 管理員發免費抽獎箱（2026-10，月卡的那種；跟月卡每天送的累積在一起）。帳號還沒載入 null。 */
    public CharacterRepository.ExtraWork grantFreeBoxesWork(final Player player, final long amount) {
        if (accounts() == null || !accounts().accountLoaded(player) || amount <= 0) {
            return null;
        }
        final long have = freeBoxes(player);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(FREE_BOXES, String.valueOf(have + amount));
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        plugin.getSLF4JLogger().info("[贊助] {} 收到免費抽獎箱 {} 箱（原本 {}）", player.getName(), amount, have);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.text().prefixed(CARD_COLOR + "收到 " + format(amount) + " 箱免費抽獎箱"
                        + "<gray>　現在有 " + format(freeBoxes(player)) + " 箱　<white><click:run_command:'/sponsor'>/sponsor</click>"));
            }
        });
        return work;
    }

    /** 收回了多少，和寫進資料庫的工作。 */
    public record Revoked(long taken, CharacterRepository.ExtraWork work) {
    }

    /**
     * 管理員收回（2026-10，管理網頁的「收回」）：what = total（累積贊助）、coins、card、fashion、bought_stash、bought_bag、bought_listing。
     * 有多少拿多少（不會變負的）。累積贊助、買的頁變少之後馬上重算：稱號、名字顏色、倉庫頁、隨身背包頁、上架格都跟著降；
     * 收回的頁上的東西先搬到前面的空格，放不下的那幾頁只能拿出來。帳號還沒載入回傳 null。
     */
    public Revoked revokeWork(final Player player, final String what, final long amount) {
        if (accounts() == null || !accounts().accountLoaded(player) || amount <= 0) {
            return null;
        }
        final String key = switch (what == null ? "" : what) {
            case "total" -> TOTAL;
            case "coins" -> COINS;
            case "card" -> CARD_COINS;
            case "fashion" -> FASHION_COINS;
            case "bought_stash" -> STASH;
            case "bought_bag" -> BAG;
            case "bought_listing" -> LISTING;
            case "free_box" -> FREE_BOXES;
            default -> null;
        };
        if (key == null) {
            return null;
        }
        final long have = FREE_BOXES.equals(key) ? freeBoxes(player) : number(player, key);
        final long taken = Math.max(0, Math.min(have, amount));
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(key, String.valueOf(have - taken));
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        plugin.getSLF4JLogger().info("[贊助] 管理員收回 {} 的 {} {}（原本 {}）", player.getName(), key, taken, have);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refresh(player); // 稱號、背包頁、倉庫頁、上架格跟著降
            }
        });
        return new Revoked(taken, work);
    }

    /**
     * 管理員改月卡天數（2026-10，測試有沒有月卡用）：delta 正的加天數（沒有月卡就從今天開始，不送開卡禮），負的扣天數
     * （扣到昨天為止 = 沒有月卡）。回傳實際改了幾天與要一起存的帳號資料；帳號還沒載入 null。
     */
    public Revoked cardDaysWork(final Player player, final long delta) {
        if (accounts() == null || !accounts().accountLoaded(player) || delta == 0) {
            return null;
        }
        final long today = today();
        final long[] card = card(player);
        final long[] fresh;
        final long changed;
        if (delta > 0) {
            // 2026-10-18：今天收回又加回：「發到哪一天」留著（今天發過就不再發一次）
            fresh = card != null && card[1] >= today ? new long[] {card[0], card[1] + delta, card[2]}
                    : new long[] {today, today + delta - 1, card == null ? today - 1 : Math.max(today - 1, card[2])};
            changed = delta;
        } else if (card == null || card[1] < today) {
            fresh = card;
            changed = 0;
        } else {
            final long end = Math.max(today - 1, card[1] + delta);
            fresh = new long[] {card[0], end, card[2]}; // 2026-10-18：發到哪一天不往小改（以前改小，同一天加回來又發一次）
            changed = card[1] - end;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        if (fresh != null) {
            values.put(CARD, fresh[0] + "|" + fresh[1] + "|" + fresh[2]);
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        plugin.getSLF4JLogger().info("[贊助] 管理員改 {} 的月卡天數 {}（實際 {} 天）", player.getName(), delta, changed);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refresh(player);
                if (delta > 0) {
                    payCard(player, false); // 今天的月卡幣、時裝幣
                }
                plugin.players().updateListName(player); // 名字旁的月亮
                plugin.players().markDirty(player);
            }
        });
        return new Revoked(changed, work);
    }

    /** 升階了：公告、套用。 */
    private void tierUp(final Player player, final SponsorConfig.Tier before) {
        refresh(player);
        final SponsorConfig.Tier after = tier(player);
        if (after == null || after.equals(before)) {
            return;
        }
        final String who = displayName(player);
        broadcast("<#ffd27f>✦ " + ItemRenderer.escape(who) + " 成為 <" + after.color() + ">【" + ItemRenderer.escape(after.title()) + "】</"
                + after.color() + "><#ffd27f>，感謝支持《星骸》！");
        perks.refresh(player); // 2026-10-23：到階送的時裝、終身月卡
        player.showTitle(Title.title(plugin.text().parse("<" + after.color() + ">【" + ItemRenderer.escape(after.title()) + "】"),
                plugin.text().parse("<gray>累積贊助 " + format(after.amount()) + " 星幣・新的稱號、名字顏色與功能已經開啟"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    /** 管理員扣星幣（退款）；回傳問題（null = 成功）。不動累積贊助（退款的話另外用 refund）。 */
    String take(final Player player, final long amount, final boolean refund) {
        if (!loaded(player)) {
            return "帳號資料載入中";
        }
        final long coins = coins(player);
        if (coins < amount) {
            return "星幣只有 " + format(coins);
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(COINS, String.valueOf(coins - amount));
        if (refund) {
            values.put(TOTAL, String.valueOf(Math.max(0, total(player) - amount)));
        }
        accounts().commitAccount(player, values, "贊助：管理員扣星幣");
        refresh(player);
        plugin.getSLF4JLogger().info("[贊助] 管理員扣 {} 的 {} 星幣（{}）", player.getName(), amount, refund ? "退款，累積也扣" : "累積不變");
        return null;
    }

    // ---- 花錢 ----

    /** 能不能花錢（模組、帳號、交易系統）；可以 null。 */
    String spendProblem(final Player player) {
        if (!config.enabled()) {
            return "贊助沒有開放（sponsor.yml 的 settings.enabled）";
        }
        if (accounts() == null) {
            return "委託模組沒有啟用（贊助的資料存在那裡）";
        }
        return accounts().spendProblem(player);
    }

    private String notEnough(final Player player, final long price) {
        final long coins = coins(player);
        return coins < price ? "星幣不夠：要 " + format(price) + "，你有 " + format(coins) + "（/store 贊助商城儲值）" : null;
    }

    /** 扣星幣＋其他的值一起寫；回傳問題（null = 成功）。 */
    private String spend(final Player player, final long price, final Map<String, String> values, final String what) {
        final String problem = spendProblem(player);
        if (problem != null) {
            return problem;
        }
        final String poor = notEnough(player, price);
        if (poor != null) {
            return poor;
        }
        final Map<String, String> all = new LinkedHashMap<>();
        all.put(COINS, String.valueOf(coins(player) - price));
        all.putAll(values);
        if (!accounts().commitAccount(player, all, "贊助：" + what)) {
            return "帳號資料載入中，請稍等";
        }
        plugin.getSLF4JLogger().info("[贊助] {} 花 {} 星幣：{}（剩 {}）", player.getName(), price, what, coins(player));
        return null;
    }

    /** 星幣券（2026-10）：扣星幣（開券）。成功 null。 */
    String spendCoins(final Player player, final long amount, final String what) {
        return spend(player, amount, Map.of(), what);
    }

    /** 星幣券：加星幣（兌換、開券失敗退回）。不算累積贊助。 */
    void addCoins(final Player player, final long amount, final String what) {
        if (amount <= 0) {
            return;
        }
        accounts().commitAccount(player, Map.of(COINS, String.valueOf(coins(player) + amount)), "贊助：" + what);
        plugin.getSLF4JLogger().info("[贊助] {} 得到 {} 星幣：{}（現在 {}）", player.getName(), amount, what, coins(player));
    }

    /** 2026-10-18：星幣券兌換：加星幣改在記憶體，回傳寫進資料庫的工作（跟 credited = 1 放同一個交易）；帳號沒載入 null。 */
    CharacterRepository.ExtraWork addCoinsWork(final Player player, final long amount, final String what) {
        if (amount <= 0 || accounts() == null || !accounts().accountLoaded(player)) {
            return null;
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, Map.of(COINS, String.valueOf(coins(player) + amount)));
        if (work != null) {
            plugin.getSLF4JLogger().info("[贊助] {} 得到 {} 星幣：{}（現在 {}）", player.getName(), amount, what, coins(player));
        }
        return work;
    }

    /** 星幣券的處理（SponsorModule 設定）。 */
    StarVouchers vouchers;

    String buyCharacterSlot(final Player player) {
        final long bought = number(player, SLOTS);
        if (bought >= config.slotMax()) {
            return "角色欄位已經買到 " + config.slotMax() + " 個了";
        }
        final String problem = spend(player, config.slotPrice(), Map.of(SLOTS, String.valueOf(bought + 1)), "角色欄位 +1");
        if (problem == null) {
            ok(player, "<green>角色欄位 +1（現在 " + plugin.characters().slotsFor(player) + " 格；/char 建立新角色）");
        }
        return problem;
    }

    String buyStashPage(final Player player) {
        final long bought = number(player, STASH);
        if (SponsorConfig.atMax(bought, config.stashMax())) {
            return "倉庫頁已經買到 " + config.stashMax() + " 頁了";
        }
        if (extraStashPages(player) >= SponsorConfig.MAX_EXTRA_STASH) {
            return "倉庫頁已經到上限了";
        }
        final String problem = spend(player, config.stashPrice(), Map.of(STASH, String.valueOf(bought + 1)), "倉庫頁 +1");
        if (problem == null) {
            refresh(player);
            ok(player, "<green>倉庫頁 +1（打開倉庫就看得到）");
        }
        return problem;
    }

    String buyBagPage(final Player player) {
        final long bought = number(player, BAG);
        if (SponsorConfig.atMax(bought, config.bagMax())) {
            return "隨身背包頁已經買到 " + config.bagMax() + " 頁了";
        }
        if (extraBagPages(player) >= SponsorConfig.MAX_EXTRA_BAG) {
            return "隨身背包頁已經到上限了";
        }
        final String problem = spend(player, config.bagPrice(), Map.of(BAG, String.valueOf(bought + 1)), "隨身背包頁 +1");
        if (problem == null) {
            refresh(player);
            ok(player, "<green>隨身背包頁 +1（帳號裡每個角色都有；/bag）");
        }
        return problem;
    }

    // ---- 通貨袋、地圖頁加大（2026-10） ----

    /** 星幣加大的通貨袋（每種多幾個）。 */
    public int extraPouch(final Player player) {
        return (int) Math.min(Integer.MAX_VALUE, number(player, POUCH) * config.pouchStep());
    }

    /** 星幣加大的倉庫通貨頁（每種多幾個）。 */
    public int extraStashCurrency(final Player player) {
        return (int) Math.min(Integer.MAX_VALUE - tw.exilecore.stash.Stash.CURRENCY_CAP,
                number(player, STASH_CURRENCY) * config.stashCurrencyStep());
    }

    String buyStashCurrency(final Player player) {
        final long bought = number(player, STASH_CURRENCY);
        if (bought >= config.stashCurrencyMax()) {
            return "倉庫通貨頁已經加大到上限了（" + config.stashCurrencyMax() + " 次）";
        }
        final String problem = spend(player, config.stashCurrencyPrice(), Map.of(STASH_CURRENCY, String.valueOf(bought + 1)),
                "倉庫通貨頁 +" + config.stashCurrencyStep());
        if (problem == null) {
            if (plugin.stash() != null) {
                plugin.stash().ensurePages(player); // 馬上套上新的上限
            }
            ok(player, "<green>倉庫通貨頁每種通貨的上限 +" + format(config.stashCurrencyStep()) + "（帳號共用）");
        }
        return problem;
    }

    /** 星幣加大的地圖頁（多幾張）。 */
    public int extraMapSlots(final Player player) {
        return (int) Math.min(Integer.MAX_VALUE, number(player, MAPS) * config.mapStep());
    }

    /** 通貨袋的上限套到這個角色的袋子上（登入、換角色、買了之後）。 */
    void applyPouch(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state.isLoaded()) {
            state.bag().pouch().setCap(tw.exilecore.character.CurrencyPouch.CAP + extraPouch(player));
        }
    }

    String buyPouch(final Player player) {
        final long bought = number(player, POUCH);
        if (bought >= config.pouchMax()) {
            return "通貨袋已經加大到上限了（" + config.pouchMax() + " 次）";
        }
        final String problem = spend(player, config.pouchPrice(), Map.of(POUCH, String.valueOf(bought + 1)), "通貨袋上限 +" + config.pouchStep());
        if (problem == null) {
            applyPouch(player);
            ok(player, "<green>通貨袋每種通貨的上限 +" + format(config.pouchStep()) + "（帳號裡每個角色都有）");
        }
        return problem;
    }

    String buyMapSlots(final Player player) {
        final long bought = number(player, MAPS);
        if (bought >= config.mapMax()) {
            return "地圖頁已經加大到上限了（" + config.mapMax() + " 次）";
        }
        final String problem = spend(player, config.mapPrice(), Map.of(MAPS, String.valueOf(bought + 1)), "地圖頁 +" + config.mapStep());
        if (problem == null) {
            if (plugin.stash() != null) {
                plugin.stash().ensurePages(player); // 馬上套上新的上限
            }
            ok(player, "<green>倉庫的地圖頁上限 +" + format(config.mapStep()) + " 張");
        }
        return problem;
    }

    String buyListing(final Player player) {
        final long bought = number(player, LISTING);
        if (SponsorConfig.atMax(bought, config.listingMax()) || bought >= SponsorConfig.MAX_LISTINGS) {
            return "上架格已經買到 " + config.listingMax() + " 格了";
        }
        final String problem = spend(player, config.listingPrice(), Map.of(LISTING, String.valueOf(bought + 1)), "上架格 +1");
        if (problem == null) {
            ok(player, "<green>市集商店頁的上架上限 +1");
        }
        return problem;
    }

    // ---- 改名卡（2026-10） ----

    String buyRenameCard(final Player player) {
        final long have = number(player, RENAME);
        final String problem = spend(player, config.renamePrice(), Map.of(RENAME, String.valueOf(have + 1)), "改名卡");
        if (problem == null) {
            ok(player, "<green>買了一張改名卡（現在有 " + (have + 1) + " 張）<gray>：/char rename 新名字");
        }
        return problem;
    }

    /** 還有幾張改名卡。 */
    public long renameCards(final Player player) {
        return number(player, RENAME);
    }

    /** 用掉一張改名卡（改名送出前先扣，失敗了 {@link #refundRenameCard} 還回去）；沒有回傳 false。 */
    public boolean useRenameCard(final Player player) {
        if (!loaded(player)) {
            return false;
        }
        final long have = number(player, RENAME);
        if (have <= 0) {
            return false;
        }
        accounts().commitAccount(player, Map.of(RENAME, String.valueOf(have - 1)), "用掉改名卡");
        return true;
    }

    /**
     * 改名失敗時人已經下線（2026-10）：直接在資料庫把改名卡加回 1 張（資料庫只有一條執行緒，排在扣卡之後，不會算錯）。
     * 在資料庫執行緒呼叫。
     */
    public static void refundRenameCardOffline(final java.sql.Connection connection, final java.util.UUID account) throws java.sql.SQLException {
        long have = 0;
        boolean exists = false;
        try (java.sql.PreparedStatement select = connection.prepareStatement(
                "SELECT progress_value FROM account_progress WHERE account_uuid = ? AND progress_key = ?")) {
            select.setString(1, account.toString());
            select.setString(2, RENAME);
            try (java.sql.ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    exists = true;
                    have = parse(result.getString(1));
                }
            }
        }
        try (java.sql.PreparedStatement write = connection.prepareStatement(exists
                ? "UPDATE account_progress SET progress_value = ? WHERE account_uuid = ? AND progress_key = ?"
                : "INSERT INTO account_progress (progress_value, account_uuid, progress_key) VALUES (?, ?, ?)")) {
            write.setString(1, String.valueOf(have + 1));
            write.setString(2, account.toString());
            write.setString(3, RENAME);
            write.executeUpdate();
        }
    }

    /** 2026-10-17：星幣退給已經下線的人：直接加到資料庫的帳號進度（下次登入讀到）。 */
    void refundCoinsOffline(final java.util.UUID account, final long amount, final String what) {
        if (amount <= 0) {
            return;
        }
        plugin.database().async(connection -> {
            long have = 0;
            boolean exists = false;
            try (java.sql.PreparedStatement select = connection.prepareStatement(
                    "SELECT progress_value FROM account_progress WHERE account_uuid = ? AND progress_key = ?")) {
                select.setString(1, account.toString());
                select.setString(2, COINS);
                try (java.sql.ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        exists = true;
                        have = parse(result.getString(1));
                    }
                }
            }
            try (java.sql.PreparedStatement write = connection.prepareStatement(exists
                    ? "UPDATE account_progress SET progress_value = ? WHERE account_uuid = ? AND progress_key = ?"
                    : "INSERT INTO account_progress (progress_value, account_uuid, progress_key) VALUES (?, ?, ?)")) {
                write.setString(1, String.valueOf(have + amount));
                write.setString(2, account.toString());
                write.setString(3, COINS);
                write.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("[贊助] {}：{} 星幣退不回去（{}），請手動補", what, amount, account, error);
            } else {
                plugin.getSLF4JLogger().info("[贊助] {}：{} 星幣退回 {}（離線，寫進資料庫）", what, amount, account);
            }
        });
    }

    public void refundRenameCard(final Player player) {
        if (loaded(player)) {
            accounts().commitAccount(player, Map.of(RENAME, String.valueOf(number(player, RENAME) + 1)), "改名失敗，還回改名卡");
        }
    }

    // ---- 公會：改名卡、倉庫頁（2026-10） ----

    String buyGuildRenameCard(final Player player) {
        final long have = number(player, GUILD_RENAME);
        final String problem = spend(player, config.guildRenamePrice(), Map.of(GUILD_RENAME, String.valueOf(have + 1)), "公會改名卡");
        if (problem == null) {
            ok(player, "<green>買了一張公會改名卡（現在有 " + (have + 1) + " 張）<gray>：會長用 /guild rename 新標籤 新名稱");
        }
        return problem;
    }

    /** 還有幾張公會改名卡。 */
    public long guildRenameCards(final Player player) {
        return number(player, GUILD_RENAME);
    }

    /** 用掉一張公會改名卡（公會模組在確定可以改名之後才呼叫）；沒有回傳 false。 */
    public boolean useGuildRenameCard(final Player player) {
        if (!loaded(player)) {
            return false;
        }
        final long have = number(player, GUILD_RENAME);
        if (have <= 0) {
            return false;
        }
        return accounts().commitAccount(player, Map.of(GUILD_RENAME, String.valueOf(have - 1)), "用掉公會改名卡");
    }

    /** 幫自己的公會買一頁倉庫：頁是公會的（退出不會帶走，公會解散就沒了）。 */
    String buyGuildStashPage(final Player player) {
        final tw.exilecore.guild.GuildService guilds = plugin.guilds();
        if (guilds == null) {
            return "公會模組沒有啟用";
        }
        final String blocked = guilds.boughtPageProblem(player, config.guildStashMax());
        if (blocked != null) {
            return blocked;
        }
        final String problem = spend(player, config.guildStashPrice(),
                Map.of(GUILD_STASH, String.valueOf(number(player, GUILD_STASH) + 1)), "公會倉庫頁 +1");
        if (problem == null) {
            guilds.addBoughtPage(player);
            ok(player, "<green>公會倉庫頁 +1（打開公會倉庫就看得到）");
        }
        return problem;
    }

    // ---- 月卡 ----

    private ZoneId zone() {
        return plugin.economy() == null ? ZoneId.systemDefault() : plugin.economy().ledger().zone();
    }

    long today() {
        return LocalDate.now(zone()).toEpochDay();
    }

    /** 月卡：開始、最後一天、發到哪一天；沒買過 null。 */
    long[] card(final Player player) {
        final String text = value(player, CARD);
        if (text == null) {
            return null;
        }
        final String[] parts = text.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        return new long[] {parse(parts[0]), parse(parts[1]), parse(parts[2])};
    }

    /** 月卡還剩幾天（含今天；沒有 0）。 */
    public long cardDaysLeft(final Player player) {
        final long[] card = card(player);
        return card == null ? 0 : Math.max(0, card[1] - today() + 1);
    }

    String cardText(final Player player) {
        final long left = cardDaysLeft(player);
        if (left <= 0) {
            return discordBoost(player) ? "<#ff73fa>Discord 加成中（月卡功能）</#ff73fa>" : null;
        }
        return CARD_COLOR + "月卡剩 " + left + " 天</#bcd4ff>" + (discordBoost(player) ? "<#ff73fa>＋Discord 加成</#ff73fa>" : "");
    }

    /**
     * 有月卡的功能（2026-10）：月卡還沒到期，或連結的 Discord 帳號正在加成 Discord 伺服器（加成結束就自動沒有）。
     * 每天發的月卡幣、時裝幣、免費抽獎箱只看真的月卡（{@link #cardDaysLeft}）。
     */
    public boolean cardActive(final Player player) {
        return cardDaysLeft(player) > 0 || discordBoost(player);
    }

    /** 連結的 Discord 帳號正在加成 Discord 伺服器。 */
    public boolean discordBoost(final Player player) {
        final tw.exilecore.discord.DiscordBridge bridge = plugin.discord();
        return bridge != null && bridge.link().boosting(player);
    }

    String buyCard(final Player player) {
        final long today = today();
        final long[] card = card(player);
        final long[] fresh;
        if (card != null && card[1] >= today) {
            fresh = new long[] {card[0], card[1] + config.cardDays(), card[2]};
        } else {
            // 2026-10-18：今天被收回後再買：今天已經發過的不再發（發到哪一天留著）
            fresh = new long[] {today, today + config.cardDays() - 1, card == null ? today - 1 : Math.max(today - 1, card[2])};
        }
        // 2026-10 月卡福利：開卡禮（買下就送）、續卡獎勵（月卡還沒過期、或昨天才到期就算連續）
        final boolean continued = card != null && card[1] >= today - 1;
        final int streak = continued ? (int) Math.max(1, number(player, CARD_STREAK)) + 1 : 1;
        final int bonus = config.renewalBonus(streak);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(CARD, fresh[0] + "|" + fresh[1] + "|" + fresh[2]);
        values.put(CARD_STREAK, String.valueOf(streak));
        values.put(CARD_COINS, String.valueOf(cardCoins(player) + config.welcomeCardCoins() + bonus));
        values.put(FASHION_COINS, String.valueOf(fashionCoins(player) + config.welcomeFashionCoins()));
        final String problem = spend(player, config.cardPrice(), values, "月卡 " + config.cardDays() + " 天");
        if (problem == null) {
            ok(player, "<green>月卡 " + config.cardDays() + " 天<gray>（到 " + LocalDate.ofEpochDay(fresh[1]) + "）：每天換日自動發 "
                    + config.dailyCardCoins() + " 月卡幣、" + config.dailyFashionCoins() + " 時裝幣到倉庫的贊助頁");
            player.sendMessage(plugin.text().prefixed(CARD_COLOR + "開卡禮<gray>：" + FASHION_COLOR + "+" + config.welcomeFashionCoins()
                    + " 時裝幣</#ff9ad5><gray>、" + CARD_COLOR + "+" + (config.welcomeCardCoins() + bonus) + " 月卡幣</#bcd4ff>"
                    + (bonus > 0 ? "<gray>（連續第 " + streak + " 個月，多送 " + bonus + "）" : "")));
            payCard(player, false);
            plugin.players().updateListName(player); // 名字旁的月亮
        }
        return problem;
    }

    /** 發月卡還沒發的天（今天以前、月卡的最後一天以前）。 */
    void payCard(final Player player, final boolean login) {
        final long[] card = card(player);
        if (card == null || accounts() == null || !accounts().accountLoaded(player)) {
            return;
        }
        final long until = Math.min(today(), card[1]);
        final long from = Math.max(card[2] + 1, card[0]);
        if (until < from) {
            return;
        }
        final long days = until - from + 1;
        final long cardCoins = days * config.dailyCardCoins();
        final long fashion = days * config.dailyFashionCoins();
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(CARD, card[0] + "|" + card[1] + "|" + until);
        values.put(CARD_COINS, String.valueOf(cardCoins(player) + cardCoins));
        values.put(FASHION_COINS, String.valueOf(fashionCoins(player) + fashion));
        final boolean boxes = !config.freeBox().isEmpty();
        if (boxes) {
            values.put(FREE_BOXES, String.valueOf(freeBoxes(player) + days)); // 2026-10：每一天一箱免費抽獎箱，沒開的累積
        }
        if (!accounts().commitAccount(player, values, "月卡")) {
            return;
        }
        player.sendMessage(plugin.text().prefixed(CARD_COLOR + "月卡" + (days > 1 ? "（補發 " + days + " 天）" : "") + "<gray>：" + CARD_COLOR + "+"
                + cardCoins + " 月卡幣</#bcd4ff><gray>、" + FASHION_COLOR + "+" + fashion + " 時裝幣</#ff9ad5><gray> 放進倉庫的贊助頁"
                + (boxes ? "；免費抽獎箱 " + CARD_COLOR + "+" + days + "</#bcd4ff><gray>（現在 " + freeBoxes(player) + " 箱，/sponsor 打開）" : "")
                + (card[1] - until <= 3 ? "<yellow>（月卡剩 " + Math.max(0, card[1] - today() + 1) + " 天）" : "")));
        if (!login) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        }
    }

    // ---- 全服加倍 ----

    String buyBoost(final Player player, final String typeId) {
        return buyBoost(player, typeId, false);
    }

    /** free：用累積贊助送的免費次數（2026-10-23），不扣星幣。 */
    String buyBoost(final Player player, final String typeId, final boolean free) {
        final SponsorConfig.BoostType type = config.boosts().get(typeId);
        if (type == null) {
            return "沒有這種加倍";
        }
        final Map<String, String> freeUse = free ? perks.useFreeBoost(player) : Map.of();
        if (free && freeUse == null) {
            return "這個週期的免費加倍用完了";
        }
        final long now = System.currentTimeMillis();
        final Boosts.Active current = boosts.get(type.id());
        final long start = current == null ? now : Math.max(now, current.until());
        final int boostMinutes = config.boostMinutes() + perks.perksOf(player).boostMinutes(); // 2026-10-23 晚：最高階放的加倍多幾分鐘
        final long until = start + boostMinutes * 60_000L;
        if (until - now > config.boostMaxMinutes() * 60_000L) {
            return type.name() + "已經排到 " + config.boostMaxMinutes() + " 分鐘了，等一下再買";
        }
        final String problem = spend(player, free ? 0 : config.boostPrice(), freeUse,
                type.name() + " " + boostMinutes + " 分鐘" + (free ? "（累積贊助的免費次數）" : ""));
        if (problem != null) {
            return problem;
        }
        final String who = displayName(player);
        boosts.put(type.id(), new Boosts.Active(type.id(), type.label(), until, who));
        saveBoosts();
        recompute();
        final long minutes = (until - now + 30_000) / 60_000;
        broadcast("<#ffd27f>✦ " + ItemRenderer.escape(who) + " 贊助了 <white>" + ItemRenderer.escape(type.name()) + "</white> "
                + boostMinutes + " 分鐘，全服一起享受！<gray>（" + (current == null ? "" : "延長，") + "剩 " + minutes + " 分鐘）");
        for (final Player each : plugin.getServer().getOnlinePlayers()) {
            each.showTitle(Title.title(plugin.text().parse("<#ffd27f>" + ItemRenderer.escape(type.name())),
                    plugin.text().parse("<gray>" + ItemRenderer.escape(who) + " 贊助・" + minutes + " 分鐘"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
            each.playSound(each.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        }
        return null;
    }

    Map<String, Boosts.Active> boosts() {
        return Map.copyOf(boosts);
    }

    /** 每秒：到時間的加倍結束、倍數重算、營地飛行。 */
    void tick() {
        final long now = System.currentTimeMillis();
        boolean changed = false;
        for (final var iterator = boosts.entrySet().iterator(); iterator.hasNext(); ) {
            final Map.Entry<String, Boosts.Active> entry = iterator.next();
            if (entry.getValue().until() <= now) {
                iterator.remove();
                changed = true;
                final SponsorConfig.BoostType type = config.boosts().get(entry.getKey());
                broadcast("<gray>" + ItemRenderer.escape(type == null ? entry.getValue().label() : type.name()) + "結束了，謝謝 "
                        + ItemRenderer.escape(entry.getValue().sponsor()) + " 的贊助");
            }
        }
        if (changed) {
            saveBoosts();
            recompute();
        }
        personalTick();
        campFlight();
        final long today = today();
        if (lastDay >= 0 && today != lastDay) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                payCard(player, false); // 換日：月卡
                perks.refresh(player); // 2026-10-23：換週的免費箱
            }
        }
        lastDay = today;
    }

    private void recompute() {
        double experience = 1;
        double quantity = 0;
        double rarity = 0;
        double gold = 1;
        final Map<String, Boosts.Active> active = new LinkedHashMap<>();
        if (config.enabled()) {
            for (final Boosts.Active boost : boosts.values()) {
                final SponsorConfig.BoostType type = config.boosts().get(boost.type());
                if (type == null) {
                    continue;
                }
                experience *= type.experience();
                quantity += type.quantity();
                rarity += type.rarity();
                gold *= type.gold();
                active.put(boost.type(), boost);
            }
        }
        Boosts.set(active, experience, quantity, rarity, gold);
    }

    private void loadBoosts() {
        final File file = new File(plugin.getDataFolder(), BOOST_FILE);
        if (!file.exists()) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        final long now = System.currentTimeMillis();
        for (final String key : yaml.getKeys(false)) {
            final long until = yaml.getLong(key + ".until");
            final SponsorConfig.BoostType type = config.boosts().get(key);
            if (until > now && type != null) {
                boosts.put(key, new Boosts.Active(key, type.label(), until, yaml.getString(key + ".sponsor", "")));
            }
        }
    }

    private void saveBoosts() {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Boosts.Active boost : boosts.values()) {
            yaml.set(boost.type() + ".until", boost.until());
            yaml.set(boost.type() + ".sponsor", boost.sponsor());
        }
        try {
            yaml.save(new File(plugin.getDataFolder(), BOOST_FILE));
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("存不了 {}", BOOST_FILE, e);
        }
    }

    void reloaded() {
        recompute();
        registerTitles();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (loaded(player)) {
                refresh(player);
            }
        }
    }

    void stop() {
        Boosts.clear();
        for (final UUID uuid : flying) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
        }
        flying.clear();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final long[] today = online.get(player.getUniqueId());
            if (today != null && today[1] < config.loginMinutes() && loaded(player)) {
                accounts().writeProgress(player, LOGIN_TODAY, today[0] + "|" + today[1]);
            }
        }
    }

    // ---- 營地飛行 ----

    boolean flyEnabled(final Player player) {
        return value(player, FLY_OFF) == null;
    }

    void toggleFly(final Player player) {
        accounts().writeProgress(player, FLY_OFF, flyEnabled(player) ? "1" : null);
    }

    private boolean inCamp(final Player player) {
        return plugin.zones() != null && plugin.zones().zoneAt(player.getLocation()).map(tw.exilecore.zone.Zone::town).orElse(false);
    }

    private void campFlight() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final UUID uuid = player.getUniqueId();
            final SponsorConfig.Tier tier = tiers.get(uuid);
            final boolean normal = player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
            final boolean allowed = normal && tier != null && tier.campFly() && loaded(player) && flyEnabled(player) && inCamp(player);
            if (allowed && !player.getAllowFlight()) {
                player.setAllowFlight(true);
                flying.add(uuid);
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#ffd27f>營地飛行：按兩下空白鍵起飛"));
            } else if (!allowed && flying.remove(uuid)) {
                if (normal) {
                    final boolean wasFlying = player.isFlying();
                    player.setFlying(false);
                    player.setAllowFlight(false);
                    if (wasFlying) {
                        player.setFallDistance(0);
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 100, 0, false, false));
                    }
                }
            }
        }
    }

    // ---- 登入天數 ----

    /** 每分鐘：線上的人今天在線多一分鐘，到了 login_minutes 算一天。 */
    void minute() {
        if (!config.enabled() || accounts() == null) {
            return;
        }
        final long today = today();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!accounts().accountLoaded(player)) {
                continue;
            }
            long[] state = online.get(player.getUniqueId());
            if (state == null || state[0] != today) {
                state = new long[] {today, 0};
                final String saved = value(player, LOGIN_TODAY);
                if (saved != null) {
                    final String[] parts = saved.split("\\|");
                    if (parts.length == 2 && parse(parts[0]) == today) {
                        state[1] = parse(parts[1]);
                    }
                }
                online.put(player.getUniqueId(), state);
            }
            if (state[1] >= config.loginMinutes()) {
                continue; // 今天已經算過了
            }
            state[1]++;
            if (state[1] >= config.loginMinutes()) {
                final long days = loginDays(player) + 1;
                final Map<String, String> values = new LinkedHashMap<>();
                values.put(LOGIN_DAYS, String.valueOf(days));
                values.put(LOGIN_TODAY, today + "|" + state[1]);
                accounts().commitAccount(player, values, "登入天數");
                for (final SponsorConfig.LoginTitle title : config.loginTitles()) {
                    if (title.days() == days) {
                        player.sendMessage(plugin.text().prefixed("<green>登入滿 " + days + " 天，拿到稱號 <" + title.color() + ">【"
                                + ItemRenderer.escape(title.name()) + "】</" + title.color() + "><gray>（選單的「稱號」戴上）"));
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
                        accounts().refreshTitle(player);
                    }
                }
            } else if (state[1] % 5 == 0) {
                accounts().writeProgress(player, LOGIN_TODAY, today + "|" + state[1]);
            }
        }
    }

    /** 今天在線幾分鐘（選單顯示）。 */
    long minutesToday(final Player player) {
        final long[] state = online.get(player.getUniqueId());
        return state == null || state[0] != today() ? 0 : state[1];
    }

    // ---- 稱號 ----

    /** 累積贊助、登入天數、月卡幣的稱號登記到稱號系統（啟用、重讀時）。 */
    void registerTitles() {
        final BountyService accounts = accounts();
        if (accounts == null) {
            return;
        }
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        for (final SponsorConfig.Tier tier : config.tiers()) {
            titles.put(tier.id(), new BountyConfig.Title(tier.id(), tier.title(), tier.color(), 0));
        }
        for (final SponsorConfig.LoginTitle title : config.loginTitles()) {
            titles.put(title.id(), new BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        for (final SponsorConfig.CardItem item : config.cardShop()) {
            if (item.kind() == SponsorConfig.CardKind.TITLE) {
                titles.put(item.id(), new BountyConfig.Title(item.id(), item.name(), item.color(), 0));
            }
        }
        for (final SponsorConfig.LimitedTitle title : config.limitedTitles().values()) {
            titles.put(title.id(), new BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        if (!titles.isEmpty()) {
            accounts.registerTitles(titles);
        }
        accounts.setTitleSource("sponsor", new Source());
    }

    /** 自訂稱號（代號、顏色、文字）；沒有 null。 */
    String[] customTitle(final Map<String, String> progress) {
        final String text = progress.get(CUSTOM_TITLE);
        if (text == null) {
            return null;
        }
        final String[] parts = text.split("\\|", 3);
        return parts.length == 3 ? parts : null;
    }

    String[] customTitle(final Player player) {
        final String text = value(player, CUSTOM_TITLE);
        return text == null ? null : customTitle(Map.of(CUSTOM_TITLE, text));
    }

    private void registerCustomTitle(final Player player) {
        final String[] custom = customTitle(player);
        if (custom != null && accounts() != null) {
            accounts().registerTitles(Map.of(custom[0], new BountyConfig.Title(custom[0], custom[2], custom[1], 0)));
            accounts().refreshTitle(player); // 戴著的話 TAB、頭上的名字馬上換
        }
    }

    /** 設自訂稱號（text null = 拿掉）；回傳問題。 */
    String setCustomTitle(final Player player, final String text, final String color) {
        final SponsorConfig.Tier tier = tier(player);
        if (tier == null || !tier.customTitle()) {
            final SponsorConfig.Tier need = firstTier(SponsorConfig.Tier::customTitle);
            return "自訂稱號要累積贊助 " + (need == null ? "（沒有開放）" : format(need.amount()) + " 星幣");
        }
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        if (text == null) {
            accounts().writeProgress(player, CUSTOM_TITLE, null);
            accounts().refreshTitle(player);
            return null;
        }
        final String clean = text.strip().replace("|", "").replace("<", "").replace(">", "").replace("&", "").replace("\\", "");
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > CUSTOM_TITLE_MAX) {
            return "稱號要 1 到 " + CUSTOM_TITLE_MAX + " 個字";
        }
        final String bad = plugin.characters().config().nameFilter().problem(clean);
        if (bad != null) {
            return bad.replace("名稱", "稱號");
        }
        final String hex = titleColor(color == null || color.isBlank() ? tier.color() : color);
        if (hex == null) {
            return "顏色要寫成 #rrggbb（例如 #ffd24a）；漸層寫 2～5 個顏色（#ff5555 #ffd24a #55ffff），或寫 rainbow（彩虹）";
        }
        final String id = "ct_" + player.getUniqueId().toString().replace("-", "").substring(0, 12);
        accounts().writeProgress(player, CUSTOM_TITLE, id + "|" + hex + "|" + clean);
        registerCustomTitle(player);
        plugin.getSLF4JLogger().info("[贊助] {} 的自訂稱號：{}", player.getName(), clean);
        return null;
    }

    /**
     * 自訂稱號的顏色（2026-10）：一個 #rrggbb；2～5 個顏色（空白或逗號隔開）= 漸層 gradient:#a:#b:…；rainbow = 彩虹。
     * 不合法回傳 null。
     */
    static String titleColor(final String raw) {
        final String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("rainbow") || text.equals("彩虹")) {
            return "rainbow";
        }
        final String[] parts = text.split("[\\s,，]+");
        if (parts.length < 1 || parts.length > 5) {
            return null;
        }
        for (final String part : parts) {
            if (!part.matches("#[0-9a-f]{6}")) {
                return null;
            }
        }
        return parts.length == 1 ? parts[0] : "gradient:" + String.join(":", parts);
    }

    SponsorConfig.Tier firstTier(final java.util.function.Predicate<SponsorConfig.Tier> test) {
        for (final SponsorConfig.Tier tier : config.tiers()) {
            if (test.test(tier)) {
                return tier;
            }
        }
        return null;
    }

    /** 給稱號系統：擁有哪些、怎麼拿。 */
    private final class Source implements BountyService.TitleSource {
        @Override
        public Set<String> owned(final Map<String, String> progress) {
            final Set<String> owned = new LinkedHashSet<>();
            final long total = parse(progress.get(TOTAL));
            boolean custom = false;
            for (final SponsorConfig.Tier tier : config.tiers()) {
                if (total >= tier.amount()) {
                    owned.add(tier.id());
                    custom |= tier.customTitle();
                }
            }
            final long days = parse(progress.get(LOGIN_DAYS));
            for (final SponsorConfig.LoginTitle title : config.loginTitles()) {
                if (days >= title.days()) {
                    owned.add(title.id());
                }
            }
            for (final SponsorConfig.CardItem item : config.cardShop()) {
                if (item.kind() == SponsorConfig.CardKind.TITLE && progress.containsKey(CARD_TITLE + item.id())) {
                    owned.add(item.id());
                }
            }
            for (final SponsorConfig.LimitedTitle title : config.limitedTitles().values()) {
                if (progress.containsKey(LIMITED_TITLE + title.id())) {
                    owned.add(title.id());
                }
            }
            final String[] customTitle = customTitle(progress);
            if (custom && customTitle != null) {
                owned.add(customTitle[0]);
            }
            return owned;
        }

        @Override
        public List<String> howTo(final Player player, final String id) {
            for (final SponsorConfig.Tier tier : config.tiers()) {
                if (tier.id().equals(id)) {
                    final List<String> lines = new ArrayList<>();
                    lines.add("<gray>累積贊助 " + COIN + format(tier.amount()) + " 星幣</#f5b942><gray>（你累積 " + format(total(player)) + "）");
                    // 2026-10：這一階的經驗、金幣（到這一階為止累加）與死亡懲罰
                    final double experience = config.reachedExperience(tier.amount());
                    final double gold = config.reachedGold(tier.amount());
                    if (experience > 0 || gold > 0) {
                        lines.add("<#7fe07f>到這一階：經驗 +" + trimNumber(experience) + "%、金幣 +" + trimNumber(gold) + "%<dark_gray>（每階累加）");
                    }
                    if (tier.deathPenalty() >= 0) {
                        lines.add("<#7fe07f>死亡扣經驗 " + trimNumber(tier.deathPenalty()) + "%");
                    }
                    lines.add("<dark_gray>一起開啟的名字顏色與功能看 /sponsor");
                    return lines;
                }
            }
            for (final SponsorConfig.LoginTitle title : config.loginTitles()) {
                if (title.id().equals(id)) {
                    return List.of("<gray>登入 " + title.days() + " 天（一天在線 " + config.loginMinutes() + " 分鐘以上算一天；你 " + loginDays(player) + " 天）");
                }
            }
            for (final SponsorConfig.CardItem item : config.cardShop()) {
                if (item.id().equals(id)) {
                    return List.of("<gray>月卡幣兌換：" + CARD_COLOR + item.price() + " 月卡幣</#bcd4ff><gray>（你有 " + cardCoins(player) + "；/sponsor）");
                }
            }
            final SponsorConfig.LimitedTitle limited = config.limitedTitles().get(id);
            if (limited != null) {
                return List.of("<#ffd24a>★ " + ItemRenderer.escape(limited.limitedTag()) + "<gray>（絕版：沒有別的方法拿到）");
            }
            if (id.startsWith("ct_")) {
                return List.of("<gray>自訂稱號（/sponsor title 文字）");
            }
            return null;
        }

        @Override
        public boolean listed(final Player player, final String id) {
            if (id.startsWith("ct_")) {
                return false; // 別人的自訂稱號
            }
            if (config.limitedTitles().containsKey(id)) {
                return false; // 2026-10：限定（絕版）稱號沒有的人不列
            }
            final long total = total(player);
            for (final SponsorConfig.Tier tier : config.tiers()) {
                if (tier.id().equals(id)) {
                    return true; // 2026-10：累積贊助的稱號每一階都列出來（以前只列下一階）
                }
            }
            // 2026-10：登入天數的稱號全部列出來（預覽加成；以前只列下一個）；下架的月卡稱號沒有的人不列
            for (final SponsorConfig.CardItem item : config.cardShop()) {
                if (item.id().equals(id)) {
                    return !item.hidden();
                }
            }
            return true;
        }

        @Override
        public String sourceName(final String id) {
            for (final SponsorConfig.Tier tier : config.tiers()) {
                if (tier.id().equals(id)) {
                    return "累積贊助 " + format(tier.amount()) + " 星幣";
                }
            }
            for (final SponsorConfig.LoginTitle title : config.loginTitles()) {
                if (title.id().equals(id)) {
                    return "登入 " + title.days() + " 天";
                }
            }
            for (final SponsorConfig.CardItem item : config.cardShop()) {
                if (item.id().equals(id)) {
                    return "月卡幣兌換";
                }
            }
            final SponsorConfig.LimitedTitle limited = config.limitedTitles().get(id);
            if (limited != null) {
                return limited.limitedTag();
            }
            return id.startsWith("ct_") ? "自訂稱號" : null;
        }
    }

    // ---- 外觀特效 ----

    public boolean ownsCosmetic(final Player player, final String id) {
        return value(player, COSMETIC + id) != null || perks.allCosmetics(player, config.cosmetics().get(id)); // 2026-10-23：最高階所有時裝都能套
    }

    /** 發得出去的一樣東西（2026-10）：時裝，或 title:代號 的限定稱號。 */
    public record Grantable(String id, String name, boolean title, boolean limited, String limitedTag, SponsorConfig.Cosmetic cosmetic) {
        String progressKey() {
            return title ? LIMITED_TITLE + id : COSMETIC + id;
        }
    }

    /** 發放的結果（2026-10）：given 這次給了哪幾件（已經有的不算）、work 寫進資料庫的工作（什麼都沒給是 null）。 */
    public record Granted(List<Grantable> given, List<Grantable> alreadyOwned, CharacterRepository.ExtraWork work) {
    }

    /**
     * 直接發時裝（2026-10，管理網頁、/ec sponsor give … cosmetic：S1 贊助禮包這種絕版、限定的東西只有這條路）：
     * ref 是時裝代號，或「bundle:組代號」一次發整組（bundles）。記憶體改好，回傳寫進資料庫的工作（放進領取的交易）；
     * 帳號還沒載入回傳 null；沒有這個代號丟 IllegalArgumentException。
     */
    public Granted grantCosmeticWork(final Player player, final String ref) {
        final List<Grantable> wanted = resolveCosmetics(ref);
        if (accounts() == null || !accounts().accountLoaded(player)) {
            return null;
        }
        final List<Grantable> given = new ArrayList<>();
        final List<Grantable> owned = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();
        for (final Grantable item : wanted) {
            if (value(player, item.progressKey()) != null) {
                owned.add(item);
                continue;
            }
            values.put(item.progressKey(), "1");
            given.add(item);
        }
        if (given.isEmpty()) {
            return new Granted(given, owned, null);
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        final StringBuilder names = new StringBuilder();
        for (final Grantable item : given) {
            names.append(names.isEmpty() ? "" : "、").append(item.title() ? "稱號「" + item.name() + "」" : item.name());
        }
        plugin.getSLF4JLogger().info("[贊助] {} 收到 {}（{}）", player.getName(), names, ref);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            final Grantable first = given.getFirst();
            final String what = first.title() ? "<#ffd24a>稱號「" + ItemRenderer.escape(first.name()) + "」</#ffd24a>" : rarityTag(first.cosmetic());
            final boolean onlyTitles = given.stream().allMatch(Grantable::title);
            player.sendMessage(plugin.text().prefixed(FASHION_COLOR + "收到" + (first.title() ? "稱號" : "時裝") + "</#ff9ad5>：" + what
                    + (given.size() > 1 ? "<gray> 等 " + given.size() + " 件" : "")
                    + (first.limited() ? " <#ffd24a>★ " + ItemRenderer.escape(first.limitedTag()) : "")
                    + (onlyTitles ? "<gray>　選單 → 稱號 換上" : "<gray>　贊助 → 裝備幻化 套上去" + (given.stream().anyMatch(Grantable::title) ? "；稱號在選單 → 稱號 換上" : ""))
                    + "　<white><click:run_command:'/sponsor'>/sponsor</click>"));
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        });
        return new Granted(given, owned, work);
    }

    /**
     * 收回時裝、限定稱號（2026-10，/ec sponsor revoke）：ref 跟發放一樣（時裝代號、title:稱號代號、bundle:組代號）。
     * 拿掉擁有的紀錄，戴著的特效、套著的幻化、綁在按鍵上的動作一起清掉；戴著的稱號由 refreshTitle 換下來。
     * 記憶體改好，回傳寫進資料庫的工作（放進領取的交易）。回傳的 given 是這次收回的、alreadyOwned 是本來就沒有的。
     * 帳號還沒載入回傳 null；沒有這個代號丟 IllegalArgumentException。
     */
    public Granted revokeCosmeticWork(final Player player, final String ref) {
        final List<Grantable> wanted = resolveCosmetics(ref);
        if (accounts() == null || !accounts().accountLoaded(player)) {
            return null;
        }
        final List<Grantable> taken = new ArrayList<>();
        final List<Grantable> notOwned = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();
        for (final Grantable item : wanted) {
            if (value(player, item.progressKey()) == null) {
                notOwned.add(item);
                continue;
            }
            values.put(item.progressKey(), null);
            taken.add(item);
            if (item.title()) {
                continue;
            }
            if (item.id().equals(value(player, WORN))) {
                values.put(WORN, null);
            }
            for (final Look look : Look.values()) {
                if (item.id().equals(value(player, TRANSMOG + look.name()))) {
                    values.put(TRANSMOG + look.name(), null);
                }
            }
            for (final EmoteKey key : EmoteKey.values()) {
                if (item.id().equals(value(player, EMOTE_KEY + key.name()))) {
                    values.put(EMOTE_KEY + key.name(), null);
                }
            }
        }
        if (taken.isEmpty()) {
            return new Granted(taken, notOwned, null);
        }
        final CharacterRepository.ExtraWork work = accounts().accountWork(player, values);
        if (work == null) {
            return null;
        }
        final StringBuilder names = new StringBuilder();
        for (final Grantable item : taken) {
            names.append(names.isEmpty() ? "" : "、").append(item.title() ? "稱號「" + item.name() + "」" : item.name());
        }
        plugin.getSLF4JLogger().info("[贊助] 管理員收回 {} 的 {}（{}）", player.getName(), names, ref);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            refresh(player); // 戴著的稱號沒有了就換下來
            plugin.characters().gear().resync(player); // 套著的幻化拿掉
            player.sendMessage(plugin.text().prefixed(FASHION_COLOR + "管理員收回了</#ff9ad5>：<white>" + ItemRenderer.escape(names.toString())));
        });
        return new Granted(taken, notOwned, work);
    }

    /** 「時裝代號」「title:稱號代號」或「bundle:組代號」→ 要發的東西（沒有丟 IllegalArgumentException）。 */
    public List<Grantable> resolveCosmetics(final String ref) {
        final String id = ref == null ? "" : ref.trim();
        if (id.startsWith("bundle:")) {
            final SponsorConfig.Bundle bundle = config.bundles().get(id.substring("bundle:".length()));
            if (bundle == null) {
                throw new IllegalArgumentException("沒有這一組時裝：" + id + "（sponsor.yml 的 bundles）");
            }
            final List<Grantable> list = new ArrayList<>();
            for (final String itemId : bundle.cosmetics()) {
                final Grantable item = grantable(itemId);
                if (item != null) {
                    list.add(item);
                }
            }
            if (list.isEmpty()) {
                throw new IllegalArgumentException("這一組時裝是空的：" + id);
            }
            return list;
        }
        final Grantable item = grantable(id);
        if (item == null) {
            throw new IllegalArgumentException(id.startsWith("title:") ? "沒有這個限定稱號：" + id + "（sponsor.yml 的 limited_titles）"
                    : "沒有這個時裝：" + id + "（sponsor.yml 的 cosmetics）");
        }
        return List.of(item);
    }

    /** 一個代號（時裝、title:稱號）→ 發得出去的東西；沒有 null。 */
    private Grantable grantable(final String id) {
        if (id.startsWith("title:")) {
            final SponsorConfig.LimitedTitle title = config.limitedTitles().get(id.substring("title:".length()));
            return title == null ? null : new Grantable(title.id(), title.name(), true, true, title.limitedTag(), null);
        }
        final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
        return cosmetic == null ? null : new Grantable(cosmetic.id(), cosmetic.name(), false, cosmetic.isLimited(), cosmetic.limitedTag(), cosmetic);
    }

    /** 給人看的名字：時裝或稱號的名字，或「整組 ×n 件」。 */
    public String cosmeticLabel(final String ref) {
        final String id = ref == null ? "" : ref.trim();
        if (id.startsWith("bundle:")) {
            final SponsorConfig.Bundle bundle = config.bundles().get(id.substring("bundle:".length()));
            return bundle == null ? id : "時裝整組「" + bundle.name() + "」（" + bundle.cosmetics().size() + " 件）";
        }
        final Grantable item = grantable(id);
        return item == null ? id : (item.title() ? "稱號 " : "時裝 ") + item.name() + (item.limited() ? "（" + item.limitedTag() + "）" : "");
    }

    /** 戴著的特效（沒戴、已經沒有這個特效 null）。 */
    public SponsorConfig.Cosmetic worn(final Player player) {
        final SponsorConfig.Cosmetic trying = tryingOn(player);
        if (trying != null && !trying.skin() && !trying.isEmote()) {
            return trying; // 試穿中的特效（2026-10）
        }
        final String id = value(player, WORN);
        final SponsorConfig.Cosmetic cosmetic = id == null || !ownsCosmetic(player, id) ? null : config.cosmetics().get(id);
        return cosmetic == null || cosmetic.skin() || cosmetic.isEmote() ? null : cosmetic;
    }

    // ---- 動作（2026-10）----

    static final String EMOTE_KEY = "sp.emk.";

    /** 動作的播放（SponsorModule 設定）。 */
    Emotes emotes;

    /** 可以綁動作的按鍵（Shift＋F 是選單，不能綁）。 */
    public enum EmoteKey {
        F("F（換手鍵）"), Q("Q（丟東西鍵）"), SNEAK_Q("Shift＋Q");

        private final String displayName;

        EmoteKey(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 這個按鍵綁的動作（沒綁、已經沒有那個動作 null）。 */
    public SponsorConfig.Cosmetic emoteOn(final Player player, final EmoteKey key) {
        final String id = value(player, EMOTE_KEY + key.name());
        final SponsorConfig.Cosmetic cosmetic = id == null || !ownsCosmetic(player, id) ? null : config.cosmetics().get(id);
        return cosmetic == null || !cosmetic.isEmote() ? null : cosmetic;
    }

    /** 這個動作綁在哪個按鍵（沒綁 null）。 */
    public EmoteKey keyOf(final Player player, final String emoteId) {
        for (final EmoteKey key : EmoteKey.values()) {
            final SponsorConfig.Cosmetic on = emoteOn(player, key);
            if (on != null && on.id().equals(emoteId)) {
                return key;
            }
        }
        return null;
    }

    /** 把動作綁到按鍵（key null = 解除）；同一個按鍵原本綁的會被換掉，同一個動作只綁一個鍵。 */
    String bindEmote(final Player player, final String emoteId, final EmoteKey key) {
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(emoteId);
        if (cosmetic == null || !cosmetic.isEmote() || !ownsCosmetic(player, emoteId)) {
            return "還沒有這個動作";
        }
        final EmoteKey old = keyOf(player, emoteId);
        if (old != null) {
            accounts().writeProgress(player, EMOTE_KEY + old.name(), null);
        }
        if (key != null) {
            accounts().writeProgress(player, EMOTE_KEY + key.name(), emoteId);
        }
        return null;
    }

    String wear(final Player player, final String id) {
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        if (id != null && !ownsCosmetic(player, id)) {
            return "還沒有這個特效";
        }
        if (id != null && config.cosmetics().get(id) != null && config.cosmetics().get(id).skin()) {
            return "幻化外觀要在「裝備幻化」裡套到裝備上";
        }
        if (id != null && config.cosmetics().get(id) != null && config.cosmetics().get(id).isEmote()) {
            return "動作要在「動作」裡設定按鍵";
        }
        accounts().writeProgress(player, WORN, id);
        return null;
    }

    String buyCosmetic(final Player player, final String id) {
        final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
        if (cosmetic == null || cosmetic.fashionPrice() <= 0) {
            return "這個特效不能直接兌換";
        }
        if (ownsCosmetic(player, id)) {
            return "已經有了";
        }
        final String problem = spendProblem(player);
        if (problem != null) {
            return problem;
        }
        final long have = fashionCoins(player);
        if (have < cosmetic.fashionPrice()) {
            return "時裝幣不夠：要 " + cosmetic.fashionPrice() + "，你有 " + have;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(FASHION_COINS, String.valueOf(have - cosmetic.fashionPrice()));
        values.put(COSMETIC + id, "1");
        if (!cosmetic.skin() && !cosmetic.isEmote()) {
            values.put(WORN, id);
        }
        accounts().commitAccount(player, values, "時裝幣兌換 " + cosmetic.name());
        ok(player, "<green>換到" + (cosmetic.isEmote() ? "動作 " : cosmetic.skin() ? "幻化外觀 " : "外觀特效 ") + rarityTag(cosmetic)
                + (cosmetic.isEmote() ? "<green>（在動作選單設定按鍵）" : cosmetic.skin() ? "<green>（贊助 → 裝備幻化 套上去）" : "<green>，已經戴上"));
        return null;
    }

    static String rarityTag(final SponsorConfig.Cosmetic cosmetic) {
        return "<" + cosmetic.rarity().color() + ">[" + cosmetic.rarity().displayName() + "] " + ItemRenderer.escape(cosmetic.name()) + "</"
                + cosmetic.rarity().color() + ">";
    }

    // ---- 裝備幻化（2026-10）----

    static final String TRANSMOG = "sp.tm.";

    /** 可以幻化的地方（主手照拿的是什麼分三種，各記各的）。 */
    public enum Look {
        WEAPON("近戰武器、法杖", "weapon"),
        BOW("弓", "bow"),
        CROSSBOW("弩", "crossbow"),
        OFFHAND("副手（盾、箭袋）", "offhand"),
        HEAD("頭", "armor"),
        CHEST("身", "armor"),
        LEGS("腿", "armor"),
        FEET("腳", "armor"),
        /** 背飾（2026-10，3D 翅膀、光輪）：不是裝備，跟著背後飄。 */
        BACK("背飾", "back"),
        /** 寵物（2026-10，3D 模型）：跟在旁邊飄。 */
        PET("寵物", "pet");

        private final String displayName;
        private final String skinSlot;

        Look(final String displayName, final String skinSlot) {
            this.displayName = displayName;
            this.skinSlot = skinSlot;
        }

        public String displayName() {
            return displayName;
        }

        /** 這一格顯示的裝備是哪一種幻化（主手看物品：弓、弩、其他）。 */
        static Look of(final tw.exilecore.item.ItemSlot slot, final org.bukkit.Material type) {
            return switch (slot) {
                case MAIN_HAND -> type == org.bukkit.Material.BOW ? BOW : type == org.bukkit.Material.CROSSBOW ? CROSSBOW : WEAPON;
                case OFF_HAND -> OFFHAND;
                case HEAD -> HEAD;
                case CHEST -> CHEST;
                case LEGS -> LEGS;
                case FEET -> FEET;
                default -> null;
            };
        }

        /** 角色面板上的欄位。 */
        tw.exilecore.item.ItemSlot itemSlot() {
            return switch (this) {
                case WEAPON, BOW, CROSSBOW -> tw.exilecore.item.ItemSlot.MAIN_HAND;
                case OFFHAND -> tw.exilecore.item.ItemSlot.OFF_HAND;
                case HEAD -> tw.exilecore.item.ItemSlot.HEAD;
                case CHEST -> tw.exilecore.item.ItemSlot.CHEST;
                case LEGS -> tw.exilecore.item.ItemSlot.LEGS;
                case FEET -> tw.exilecore.item.ItemSlot.FEET;
                case BACK, PET -> null;
            };
        }

        /** 是不是裝備的格子（背飾、寵物不是）。 */
        public boolean equipment() {
            return this != BACK && this != PET;
        }
    }

    /** 這個地方套的幻化（沒套、已經沒有這個外觀 null）。 */
    public SponsorConfig.Cosmetic transmog(final Player player, final Look look) {
        final SponsorConfig.Cosmetic trying = tryingOn(player);
        if (trying != null && fits(trying, look)) {
            return trying; // 試穿中（2026-10）：蓋過這一格原本套的
        }
        final String id = value(player, TRANSMOG + look.name());
        if (id == null || !ownsCosmetic(player, id)) {
            return null;
        }
        final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
        return cosmetic != null && fits(cosmetic, look) ? cosmetic : null;
    }

    static boolean fits(final SponsorConfig.Cosmetic cosmetic, final Look look) {
        return cosmetic.skin() && (look.skinSlot.equals(cosmetic.skinSlot())
                || look == Look.HEAD && "headgear".equals(cosmetic.skinSlot())); // 頭飾（3D）套在頭上
    }

    /** 頭飾（3D）：沒戴頭盔也顯示（回傳要放在頭上的顯示用物品；沒套頭飾 null）。 */
    public ItemStack headgearOnly(final Player player) {
        if (!config.enabled() || !loaded(player) || !packSent()) {
            return null;
        }
        final SponsorConfig.Cosmetic skin = transmog(player, Look.HEAD);
        if (skin == null || !"headgear".equals(skin.skinSlot())) {
            return null;
        }
        final ItemStack stack = ItemStack.of(org.bukkit.Material.PAPER);
        stack.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text(skin.name())));
        applySkin(skin, Look.HEAD, stack);
        return stack;
    }

    /** 套上（id null = 拿掉）幻化；回傳問題。 */
    String setTransmog(final Player player, final Look look, final String id) {
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        if (id != null) {
            final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
            if (cosmetic == null || !ownsCosmetic(player, id)) {
                return "還沒有這個外觀";
            }
            if (!fits(cosmetic, look)) {
                return "這個外觀不能套在" + look.displayName();
            }
        }
        accounts().writeProgress(player, TRANSMOG + look.name(), id);
        plugin.characters().gear().resync(player);
        return null;
    }

    // ---- 試穿（2026-10）----

    /** 試穿多久（tick）：30 秒。 */
    static final long TRY_ON_TICKS = 20L * 30;

    /** 試穿中的時裝：代號、到第幾 tick 為止。 */
    private record TryOn(String id, long until) {
    }

    private final Map<UUID, TryOn> tryOns = new ConcurrentHashMap<>();
    /** 同一件時裝再試穿要等到（「玩家:時裝」→ tick）：避免一直重按把限定時裝當成自己的穿。 */
    private final Map<String, Long> tryOnAgain = new ConcurrentHashMap<>();
    static final long TRY_ON_AGAIN_TICKS = 20L * 120;

    /** 取消試穿（選單的「拿下」：試穿中只拿掉試穿的，不動真的戴著的）；有在試穿回傳 true。 */
    boolean cancelTryOn(final Player player) {
        if (tryOns.remove(player.getUniqueId()) == null) {
            return false;
        }
        plugin.characters().gear().resync(player);
        return true;
    }

    /**
     * 試穿還沒有的時裝 {@value #TRY_ON_TICKS} tick：外觀（武器、副手、防具、頭飾、背飾、寵物）照幻化的方式蓋上去，特效照戴著的方式出現；
     * 不存檔、時間到自動拿掉。別人也看得到（顯示用的裝備、3D 模型本來就是大家看得到的）。限定（絕版）、動作不能試穿。
     *
     * @return 問題（null = 開始試穿）
     */
    /**
     * 節慶活動的限定時裝（2026-10 萬燈夜）：活動到兌換結束之前，沒有的人也看得到、能試穿（不能用時裝幣買，要去活動兌換）。
     * limited 寫的來源跟節慶活動的名字一樣才算。
     */
    boolean festivalPreview(final SponsorConfig.Cosmetic cosmetic) {
        final tw.exilecore.festival.FestivalService festival = plugin.festival();
        return cosmetic.isLimited() && festival != null && festival.visible() && festival.config().name().equals(cosmetic.limited());
    }

    String tryOn(final Player player, final String id) {
        if (!loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
        if (cosmetic == null) {
            return "沒有這個時裝";
        }
        if (cosmetic.isEmote()) {
            return "動作不能試穿";
        }
        if (cosmetic.isLimited() && !ownsCosmetic(player, id) && !festivalPreview(cosmetic)) {
            return "限定的時裝不能試穿";
        }
        if (cosmetic.skin() && !packSent()) {
            return "伺服器沒有送介面資源包，3D 時裝畫不出來";
        }
        final long now = plugin.getServer().getCurrentTick();
        final String againKey = player.getUniqueId() + ":" + id;
        final Long again = tryOnAgain.get(againKey);
        if (again != null && now < again && !ownsCosmetic(player, id)) {
            return "這件剛試穿過，" + Math.max(1, (again - now) / 20) + " 秒後可以再試";
        }
        tryOnAgain.put(againKey, now + TRY_ON_AGAIN_TICKS);
        final long until = now + TRY_ON_TICKS;
        final TryOn entry = new TryOn(id, until);
        tryOns.put(player.getUniqueId(), entry);
        plugin.characters().gear().resync(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (tryOns.remove(player.getUniqueId(), entry) && player.isOnline()) {
                plugin.characters().gear().resync(player);
                player.sendMessage(plugin.text().prefixed("<gray>試穿結束：" + rarityTag(cosmetic)));
            }
        }, TRY_ON_TICKS + 1);
        return null;
    }

    /** 試穿中的時裝（沒有、時間到了 null）。 */
    public SponsorConfig.Cosmetic tryingOn(final Player player) {
        final TryOn entry = tryOns.get(player.getUniqueId());
        if (entry == null) {
            return null;
        }
        if (plugin.getServer().getCurrentTick() > entry.until()) {
            tryOns.remove(player.getUniqueId(), entry);
            return null;
        }
        return config.cosmetics().get(entry.id());
    }

    /** 試穿的時候哪裡看得到（給玩家的提示）。 */
    static String tryOnHint(final SponsorConfig.Cosmetic cosmetic) {
        if (!cosmetic.skin()) {
            return "特效在身上";
        }
        return switch (cosmetic.skinSlot()) {
            case "weapon" -> "要拿著近戰武器或法杖才看得到";
            case "bow" -> "要拿著弓才看得到";
            case "crossbow" -> "要拿著弩才看得到";
            case "offhand" -> "副手要有盾或箭袋才看得到";
            case "armor" -> "穿著的防具會換成這套的樣子";
            case "headgear" -> "戴在頭上";
            case "back" -> "在背後";
            case "pet" -> "跟在身邊";
            default -> "";
        };
    }

    /** 介面資源包有沒有送（沒送的話自己的模型畫不出來，就不換）。 */
    boolean packSent() {
        return plugin.getConfig().getBoolean("resource-pack.send", true);
    }

    /**
     * 畫面上顯示的裝備（{@code Gear} 的顯示用複本）套上幻化：只改外觀（物品模型、穿在身上的樣子），真的裝備、數值都不動。
     * 美術在介面資源包裡（web/skins/），所以沒送資源包時不換。
     */
    public void applyTransmog(final Player player, final tw.exilecore.item.ItemSlot slot, final ItemStack display) {
        if (!config.enabled() || display == null || !loaded(player) || !packSent()) {
            return;
        }
        final Look look = Look.of(slot, display.getType());
        if (look == null) {
            return;
        }
        final SponsorConfig.Cosmetic skin = transmog(player, look);
        if (skin != null) {
            applySkin(skin, look, display);
        }
    }

    /** 玩家選了要把箭袋的幻化顯示在手上（預設是；沒套幻化時箭袋本來就不顯示）。 */
    boolean quiverSkinEnabled(final Player player) {
        return value(player, QUIVER_SKIN_OFF) == null;
    }

    void toggleQuiverSkin(final Player player) {
        if (!loaded(player)) {
            return;
        }
        accounts().writeProgress(player, QUIVER_SKIN_OFF, quiverSkinEnabled(player) ? "1" : null);
        plugin.characters().gear().resync(player);
    }

    /**
     * 副手的箭袋要不要拿在手上（2026-10）：箭袋平常不顯示（{@code Gear}）；副手套了幻化、玩家沒關掉顯示、
     * 而且資源包送出去了（幻化的樣子在資源包裡）才顯示。
     */
    public boolean showsQuiverSkin(final Player player) {
        return config.enabled() && loaded(player) && packSent() && quiverSkinEnabled(player) && transmog(player, Look.OFFHAND) != null;
    }

    /** 這一套美術的物品模型代號（資源包的 items/skin/…）。 */
    static org.bukkit.NamespacedKey model(final SponsorConfig.Cosmetic skin, final Look look, final org.bukkit.Material type) {
        final String part = switch (look) {
            case WEAPON -> type == org.bukkit.Material.BLAZE_ROD || type == org.bukkit.Material.BREEZE_ROD ? "staff" : "blade";
            case BOW -> "bow";
            case CROSSBOW -> "crossbow";
            case OFFHAND -> type == org.bukkit.Material.SHIELD ? "shield" : "quiver";
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case FEET -> "boots";
            case BACK -> pairedBack(skin) ? "back_r" : "back";
            case PET -> "pet";
        };
        return new org.bukkit.NamespacedKey("exilecore", "skin/" + skin.art() + "_" + part);
    }

    /** 3D 戰甲（代號 armor3d_…）：頭盔是 3D 模型、穿著身體時肩上有 3D 肩甲。 */
    static boolean armor3d(final SponsorConfig.Cosmetic skin) {
        return skin != null && skin.art() != null && skin.art().startsWith("armor3d_");
    }

    /** 背飾是左右兩片的翅膀（代號 …_wings：資源包裡有 _back_r、_back_l）。 */
    static boolean pairedBack(final SponsorConfig.Cosmetic skin) {
        return skin.art() != null && skin.art().endsWith("_wings");
    }

    /** 把一個幻化外觀套到一件顯示用的物品上（選單預覽也用）。 */
    void applySkin(final SponsorConfig.Cosmetic skin, final Look look, final ItemStack display) {
        display.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, model(skin, look, display.getType()));
        final org.bukkit.inventory.EquipmentSlot equipment = switch (look) {
            case HEAD -> org.bukkit.inventory.EquipmentSlot.HEAD;
            case CHEST -> org.bukkit.inventory.EquipmentSlot.CHEST;
            case LEGS -> org.bukkit.inventory.EquipmentSlot.LEGS;
            case FEET -> org.bukkit.inventory.EquipmentSlot.FEET;
            default -> null;
        };
        if (equipment != null && ("headgear".equals(skin.skinSlot())
                || equipment == org.bukkit.inventory.EquipmentSlot.HEAD && armor3d(skin))) {
            // 頭飾（3D）：不要穿在身上的貼圖，頭上直接畫物品模型（原版的頭部物品層）
            display.setData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE,
                    io.papermc.paper.datacomponent.item.Equippable.equippable(equipment).build());
            display.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.DYED_COLOR);
            display.editMeta(meta -> {
                if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armor) {
                    armor.setTrim(null);
                }
            });
        } else if (equipment != null) {
            // 穿在身上的樣子：資源包的 equipment/skin_<套>.json
            display.setData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE,
                    io.papermc.paper.datacomponent.item.Equippable.equippable(equipment)
                            .assetId(net.kyori.adventure.key.Key.key("exilecore", "skin_" + skin.art())).build());
            display.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.DYED_COLOR);
            display.editMeta(meta -> {
                if (meta instanceof org.bukkit.inventory.meta.ArmorMeta armor) {
                    armor.setTrim(null);
                }
            });
        }
    }

    // ---- 抽獎箱 ----

    /** 這種箱子連續幾箱沒有傳說。 */
    int pity(final Player player, final String boxId) {
        return (int) number(player, PITY + boxId);
    }

    /**
     * 開一箱。star = 用星幣買（false = 時裝幣）。回傳問題（null = 成功）。
     */
    /** 有月卡的人今天還能不能免費開（2026-10 月卡福利）。 */
    boolean freeBoxReady(final Player player) {
        return !config.freeBox().isEmpty() && freeBoxes(player) > 0;
    }

    /**
     * 還能免費開幾箱（2026-10：月卡每一天發一箱，跟月卡幣一起發、沒上線的日子補發；沒開的累積到之後）。
     * 舊帳號還沒有這個欄位：今天的月卡已經發過、今天還沒免費開過就算 1 箱（跟以前的規則一樣）。
     */
    public long freeBoxes(final Player player) {
        final String raw = value(player, FREE_BOXES);
        if (raw != null) {
            return parse(raw);
        }
        final long[] card = card(player);
        final long today = today();
        return card != null && card[0] <= today && card[2] >= today && card[1] >= today && number(player, FREE_BOX) != today ? 1 : 0;
    }

    String openBox(final Player player, final String boxId, final boolean star) {
        return openBox(player, boxId, star, false);
    }

    String openBox(final Player player, final String boxId, final boolean star, final boolean free) {
        final SponsorConfig.Box box = config.boxes().get(boxId);
        if (box == null) {
            return "沒有這種箱子";
        }
        if (free) {
            if (!boxId.equals(config.freeBox())) {
                return "這種箱子沒有每日免費";
            }
            if (freeBoxes(player) <= 0) {
                return cardDaysLeft(player) <= 0 ? "免費開箱是月卡的福利（月卡每天送一箱，沒開的會累積）" : "免費的箱子開完了，明天再送一箱";
            }
        }
        final long price = free ? 0 : star ? box.starPrice() : box.fashionPrice();
        if (price <= 0 && !free) {
            return star ? "這種箱子不能用星幣買" : "這種箱子不能用時裝幣買";
        }
        final String problem = spendProblem(player);
        if (problem != null) {
            return problem;
        }
        if (free) {
            // 不用錢
        } else if (star) {
            final String poor = notEnough(player, price);
            if (poor != null) {
                return poor;
            }
        } else if (fashionCoins(player) < price) {
            return "時裝幣不夠：要 " + price + "，你有 " + fashionCoins(player);
        }
        final int pity = pity(player, boxId);
        final boolean forced = box.pity() > 0 && pity + 1 >= box.pity();
        final SponsorConfig.Cosmetic won = roll(player, box, forced);
        final boolean duplicate = ownsCosmetic(player, won.id());
        final int refund = duplicate ? config.duplicateRefund(won.rarity()) : 0;
        final Map<String, String> values = new LinkedHashMap<>();
        if (star) {
            values.put(COINS, String.valueOf(coins(player) - price));
        }
        values.put(FASHION_COINS, String.valueOf(fashionCoins(player) - (star ? 0 : price) + refund));
        values.put(PITY + boxId, String.valueOf(won.rarity() == SponsorConfig.Rarity.LEGENDARY ? 0 : pity + 1));
        if (free) {
            values.put(FREE_BOX, String.valueOf(today()));
            values.put(FREE_BOXES, String.valueOf(Math.max(0, freeBoxes(player) - 1)));
        }
        if (!duplicate) {
            values.put(COSMETIC + won.id(), "1");
        }
        accounts().commitAccount(player, values, "開 " + box.name());
        plugin.getSLF4JLogger().info("[贊助] {} 開 {}（{} {}）：{}{}", player.getName(), box.name(), price,
                free ? "月卡每日免費" : star ? "星幣" : "時裝幣", won.name(),
                duplicate ? "（重複，退 " + refund + " 時裝幣）" : "");
        player.showTitle(Title.title(plugin.text().parse(rarityTag(won)),
                plugin.text().parse(duplicate ? "<gray>已經有了：退回 " + refund + " 時裝幣" : won.isEmote() ? "<green>新的動作！" : won.skin() ? "<green>新的幻化外觀！" : "<green>新的外觀特效！"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
        player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(box.name()) + "：" + rarityTag(won)
                + (duplicate ? "<gray>（重複，退回 " + FASHION_COLOR + refund + " 時裝幣</#ff9ad5><gray>）"
                        : won.isEmote() ? "<green>（動作選單設定按鍵）" : won.skin() ? "<green>（裝備幻化裡套上）" : "<green>（外觀特效裡戴上）")
                + (forced ? "<#ffd27f>　保底！" : "")));
        player.playSound(player.getLocation(), won.rarity() == SponsorConfig.Rarity.LEGENDARY ? Sound.UI_TOAST_CHALLENGE_COMPLETE
                : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        if (won.rarity() == SponsorConfig.Rarity.LEGENDARY && !duplicate) {
            broadcast("<#ffd27f>✦ " + ItemRenderer.escape(displayName(player)) + " 從" + ItemRenderer.escape(box.name()) + "抽到 " + rarityTag(won) + "<#ffd27f>！");
        }
        return null;
    }

    /** 抽一個：照權重；保底的話只從傳說裡抽（還沒有的優先）。 */
    SponsorConfig.Cosmetic roll(final Player player, final SponsorConfig.Box box, final boolean forced) {
        final Map<String, Double> pool = new LinkedHashMap<>();
        for (final Map.Entry<String, Double> entry : box.weights().entrySet()) {
            final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(entry.getKey());
            if (!forced || cosmetic.rarity() == SponsorConfig.Rarity.LEGENDARY) {
                pool.put(entry.getKey(), entry.getValue());
            }
        }
        if (forced) {
            final Map<String, Double> fresh = new LinkedHashMap<>();
            for (final Map.Entry<String, Double> entry : pool.entrySet()) {
                if (!ownsCosmetic(player, entry.getKey())) {
                    fresh.put(entry.getKey(), entry.getValue());
                }
            }
            if (!fresh.isEmpty()) {
                pool.clear();
                pool.putAll(fresh);
            }
            if (pool.isEmpty()) {
                pool.putAll(box.weights()); // 箱子裡沒有傳說：照一般的抽
            }
        }
        return config.cosmetics().get(Odds.pick(pool, random.nextDouble()));
    }

    // ---- 月卡幣兌換 ----

    boolean ownsCardItem(final Player player, final SponsorConfig.CardItem item) {
        return switch (item.kind()) {
            case TITLE -> value(player, CARD_TITLE + item.id()) != null;
            case COSMETIC -> ownsCosmetic(player, item.cosmetic());
            default -> false;
        };
    }

    String buyCardItem(final Player player, final String id) {
        SponsorConfig.CardItem item = null;
        for (final SponsorConfig.CardItem each : config.cardShop()) {
            if (each.id().equals(id)) {
                item = each;
            }
        }
        if (item == null || item.hidden()) {
            return "沒有這個東西";
        }
        if (ownsCardItem(player, item)) {
            return "已經有了";
        }
        final String problem = spendProblem(player);
        if (problem != null) {
            return problem;
        }
        final long have = cardCoins(player);
        if (have < item.price()) {
            return "月卡幣不夠：要 " + item.price() + "，你有 " + have;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(CARD_COINS, String.valueOf(have - item.price()));
        switch (item.kind()) {
            case TITLE -> values.put(CARD_TITLE + item.id(), "1");
            case COSMETIC -> {
                values.put(COSMETIC + item.cosmetic(), "1");
                final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(item.cosmetic());
                if (cosmetic != null && !cosmetic.skin() && !cosmetic.isEmote()) {
                    values.put(WORN, item.cosmetic());
                }
            }
            case FASHION_COINS -> values.put(FASHION_COINS, String.valueOf(fashionCoins(player) + item.amount()));
            case CHARACTER_SLOT -> {
                if (number(player, SLOTS) >= config.slotMax()) {
                    return "角色欄位已經買到 " + config.slotMax() + " 個了";
                }
                values.put(SLOTS, String.valueOf(number(player, SLOTS) + 1));
            }
            case RENAME_CARD -> values.put(RENAME, String.valueOf(number(player, RENAME) + item.amount())); // 2026-10
            default -> {
            }
        }
        accounts().commitAccount(player, values, "月卡幣兌換 " + item.name());
        if (item.kind() == SponsorConfig.CardKind.TITLE) {
            accounts().refreshTitle(player);
        }
        ok(player, "<green>月卡幣換到 <white>" + ItemRenderer.escape(item.name()) + (item.kind() == SponsorConfig.CardKind.FASHION_COINS
                || item.kind() == SponsorConfig.CardKind.RENAME_CARD ? " ×" + item.amount() : "")
                + (item.kind() == SponsorConfig.CardKind.RENAME_CARD ? "<gray>（/char rename 新名字）" : "") + (item.kind() == SponsorConfig.CardKind.TITLE ? "<gray>（選單的「稱號」戴上）" : ""));
        return null;
    }

    // ---- 共用 ----

    private void ok(final Player player, final String message) {
        player.sendMessage(plugin.text().prefixed(message + "<gray>　剩 " + COIN + format(coins(player)) + " 星幣"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
    }

    String displayName(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter() ? state.character().name() : player.getName();
    }

    private void broadcast(final String text) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(plugin.text().prefixed(text));
        }
        plugin.getServer().getConsoleSender().sendMessage(plugin.text().prefixed(text));
    }

    static String format(final long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 這個帳號的贊助資料（/ec sponsor info）。 */
    public List<String> describe(final Player player) {
        final List<String> lines = new ArrayList<>();
        final SponsorConfig.Tier tier = tier(player);
        lines.add("<gray>星幣 <white>" + format(coins(player)) + "<gray>　累積 <white>" + format(total(player))
                + "<gray>（" + (tier == null ? "還沒有階級" : ItemRenderer.escape(tier.title())) + "）");
        lines.add("<gray>月卡幣 <white>" + cardCoins(player) + "<gray>　時裝幣 <white>" + fashionCoins(player) + "<gray>　"
                + (cardText(player) == null ? "沒有月卡" : cardText(player)));
        lines.add("<gray>買的：角色欄位 <white>" + number(player, SLOTS) + "<gray>、倉庫頁 <white>" + number(player, STASH) + "<gray>、背包頁 <white>"
                + number(player, BAG) + "<gray>、上架格 <white>" + number(player, LISTING) + "<gray>　登入 <white>" + loginDays(player) + "<gray> 天");
        return lines;
    }
}
