package tw.exilecore.event;

import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 每月活動：這個月的主題（對地圖的影響）、每月挑戰的進度與獎勵（功勳、限定稱號）。
 * 進度存在帳號進度裡（{@link EventProgress#KEY}），由委託模組的帳號資料存取，所以要在委託模組之後啟用。
 */
public final class MonthlyEventService {

    private final ExileCore plugin;
    private final EventConfig config;
    /** 上次看到的月份（換月時廣播）。 */
    private YearMonth lastMonth;
    /** 這次登入提醒過了的玩家。 */
    private final java.util.Set<java.util.UUID> greeted = new java.util.HashSet<>();
    /** 每個玩家最近一次擊殺的結果：/event 顯示，玩家覺得沒計數時看這一行就知道為什麼（不存檔，下線就清掉）。 */
    private final Map<UUID, EventText.Note> lastNote = new HashMap<>();
    /** 上次因為怪物（地圖）太弱提醒的時間（毫秒）：{@link #WARN_EVERY_MILLIS} 最多提醒一次，免得洗頻。 */
    private final Map<UUID, Long> warnedAt = new HashMap<>();
    private static final long WARN_EVERY_MILLIS = 300_000;

    public MonthlyEventService(final ExileCore plugin, final EventConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.lastMonth = YearMonth.now();
    }

    public EventConfig config() {
        return config;
    }

    // ---- 這個月 ----

    public YearMonth month() {
        return YearMonth.now();
    }

    /** 這個月的主題；活動關著（或沒有主題）null。 */
    public EventConfig.Theme theme() {
        return config.enabled() ? config.themeFor(month()) : null;
    }

    /** 這個月還剩幾天（含今天）。 */
    public int daysLeft() {
        final LocalDate today = LocalDate.now();
        return today.lengthOfMonth() - today.getDayOfMonth() + 1;
    }

    /** 主題的名字（MiniMessage 上色）。 */
    public static String themeName(final EventConfig.Theme theme) {
        return EventText.themeName(theme);
    }

    /** 委託模組登記限定稱號（啟用與重讀時）。 */
    void registerTitles() {
        if (plugin.bounties() == null) {
            return;
        }
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        for (final EventConfig.Theme theme : config.themes()) {
            if (theme.title() != null) {
                titles.put(theme.titleId(), new BountyConfig.Title(theme.titleId(), theme.title().name(), theme.title().color(), 0,
                        theme.title().stats()));
            }
        }
        plugin.bounties().registerTitles(titles);
    }

    // ---- 對地圖的影響 ----

    /** 地圖怪物多掉的物品數量（小數）。 */
    public double quantityBonus() {
        final EventConfig.Theme theme = theme();
        // 2026-10 萬燈夜：全服點燈點滿的物品數量加成也是全域的，加在這裡（掉落、地圖、劫盜都吃得到）
        final double festival = plugin.festival() == null ? 0 : plugin.festival().quantityBonus();
        return (theme == null ? 0 : theme.quantity() / 100.0) + festival;
    }

    public double rarityBonus() {
        final EventConfig.Theme theme = theme();
        return theme == null ? 0 : theme.rarity() / 100.0;
    }

    /** 地圖裡稀有怪的詞綴：照稀有度抽，再加上主題的詞綴（沒有主題 null = 照一般抽）。 */
    public List<String> rareMods(final int level, final RandomGenerator random) {
        final EventConfig.Theme theme = theme();
        if (theme == null || theme.rareMod() == null || plugin.monsters() == null) {
            return null;
        }
        final List<String> mods = new ArrayList<>(plugin.monsters().rollMods(MonsterRarity.RARE, level));
        if (!mods.contains(theme.rareMod())) {
            if (!mods.isEmpty()) {
                mods.removeLast(); // 換掉一條，不會比平常多
            }
            mods.add(theme.rareMod());
        }
        return mods;
    }

    /** 事件權重加成（加在 map_events.yml 的 weights 上）。 */
    public int eventBoost(final String kind) {
        final EventConfig.Theme theme = theme();
        return theme == null ? 0 : theme.eventBoost().getOrDefault(kind, 0);
    }

    /** 這個月祭壇的機率（負的 = 照設定檔）。 */
    public double ritualChance() {
        final EventConfig.Theme theme = theme();
        return theme == null ? -1 : theme.ritualChance();
    }

    public double deliriumChance() {
        final EventConfig.Theme theme = theme();
        return theme == null ? -1 : theme.deliriumChance();
    }

    // ---- 進度 ----

    /** 這個帳號這個月的進度（帳號還沒載入或活動關著 null）。 */
    public EventProgress progress(final Player player) {
        if (theme() == null || plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return null;
        }
        return EventProgress.decode(plugin.bounties().progressValue(player, EventProgress.KEY), EventConfig.monthKey(month()));
    }

    private void save(final Player player, final EventProgress progress) {
        plugin.bounties().writeProgress(player, EventProgress.KEY, progress.encode());
    }

    /** 這個角色現在要打幾級以上的怪（地圖）才算數（每日委託也是這個數字；算法在委託模組）。 */
    public int minLevel(final Player player) {
        return plugin.bounties() == null ? 1 : plugin.bounties().minMonsterLevel(player);
    }

    /** 最近一次擊殺的結果（這次登入後還沒有擊殺 null）。 */
    public EventText.Note lastNote(final Player player) {
        return lastNote.get(player.getUniqueId());
    }

    /** 任務追蹤（側欄）上的每月活動；活動關著、帳號還沒載入、挑戰都做完了回傳空的。 */
    public List<String> trackerLines(final Player player) {
        final EventConfig.Theme theme = theme();
        final EventProgress progress = theme == null ? null : progress(player);
        return progress == null ? List.of() : EventText.trackerLines(theme, progress, minLevel(player));
    }

    /**
     * 殺了一隻怪（有份的每個人各算一次）。怪物比「角色等級 − level_gap」低的不算（不能回新手區刷），
     * 這時會當場說明（五分鐘最多一次），/event 的「最近一次擊殺」也寫著。
     */
    public void onKill(final Player player, final MonsterData data) {
        final EventConfig.Theme theme = theme();
        if (theme == null || data == null || plugin.bounties() == null) {
            return;
        }
        final int minLevel = minLevel(player);
        final long now = System.currentTimeMillis();
        if (data.level() < minLevel) {
            lastNote.put(player.getUniqueId(), new EventText.Note(EventText.Result.TOO_WEAK, data.level(), minLevel, List.of(), now));
            tooWeak(player, theme, "這隻怪", data.level(), minLevel);
            return;
        }
        final List<EventConfig.Kind> kinds = EventConfig.Kind.forKill(data.rarity(), data.boss(), data.mods(), theme.rareMod());
        final boolean counted = apply(player, theme, kinds, 1);
        lastNote.put(player.getUniqueId(), new EventText.Note(counted ? EventText.Result.COUNTED : EventText.Result.NOT_LOADED,
                data.level(), minLevel, counted ? kinds : List.of(), now));
    }

    /** 完成了一張地圖。 */
    public void onMapComplete(final Player player, final int mapLevel) {
        onMap(player, mapLevel, EventConfig.Kind.MAPS);
    }

    /** 完成了一個地圖事件（開寶箱、碰神龕、打完裂隙、殺流寇、做完祭壇、幻霧結束）。 */
    public void onMapEvent(final Player player, final int mapLevel) {
        onMap(player, mapLevel, EventConfig.Kind.EVENTS);
    }

    private void onMap(final Player player, final int mapLevel, final EventConfig.Kind kind) {
        final EventConfig.Theme theme = theme();
        if (theme == null || plugin.bounties() == null) {
            return;
        }
        final int minLevel = minLevel(player);
        if (mapLevel < minLevel) {
            tooWeak(player, theme, "這張地圖", mapLevel, minLevel);
            return;
        }
        apply(player, theme, List.of(kind), 1);
    }

    /** 怪物（地圖）太弱沒計入：說明為什麼、要幾級以上；五分鐘最多提醒一次。 */
    private void tooWeak(final Player player, final EventConfig.Theme theme, final String what, final int level, final int minLevel) {
        final long now = System.currentTimeMillis();
        final Long last = warnedAt.get(player.getUniqueId());
        if (last != null && now - last < WARN_EVERY_MILLIS) {
            return;
        }
        warnedAt.put(player.getUniqueId(), now);
        player.sendMessage(plugin.text().prefixed(themeName(theme) + "<gray>：" + EventText.tooWeak(what, level, minLevel)));
    }

    /**
     * 這幾種挑戰都加 amount；剛做完的給功勳；全部做完給稱號與額外功勳；存起來、重畫任務追蹤。
     * 一次擊殺只讀一次、存一次（不是每一種各存一次）。
     *
     * @return 帳號資料載入好了（false = 還沒載入，這次沒計入）
     */
    private boolean apply(final Player player, final EventConfig.Theme theme, final List<EventConfig.Kind> kinds, final int amount) {
        final EventProgress progress = progress(player);
        if (progress == null) {
            return false;
        }
        final List<EventConfig.Challenge> finished = new ArrayList<>();
        boolean changed = false;
        for (final EventConfig.Kind kind : kinds) {
            changed |= progress.advance(theme.challenges(), kind, amount, finished);
        }
        if (!changed) {
            return true; // 這幾種都做完了
        }
        int number = progress.doneCount() - finished.size();
        for (final EventConfig.Challenge challenge : finished) {
            number++;
            plugin.bounties().grantMerits(player, challenge.merits(), "每月挑戰：" + challenge.rendered());
            player.sendMessage(plugin.text().prefixed(themeName(theme) + " <green>挑戰完成：<white>" + ItemRenderer.escape(challenge.rendered())
                    + "<gray>　功勳 <#f5b942>+" + challenge.merits() + "</#f5b942>（" + number + "/" + theme.challenges().size() + "，/event）"));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        }
        if (progress.allDone(theme.challenges()) && !progress.titleGiven()) {
            progress.markTitleGiven();
            if (theme.bonusMerits() > 0) {
                plugin.bounties().grantMerits(player, theme.bonusMerits(), "每月活動全部完成：" + theme.name());
            }
            if (theme.title() != null) {
                final String problem = plugin.bounties().grantTitle(player, theme.titleId());
                if (problem != null && !problem.startsWith("已經有")) {
                    plugin.getSLF4JLogger().warn("發每月活動稱號給 {} 失敗：{}", player.getName(), problem);
                }
            }
            final String titleText = theme.title() == null ? "" : "，限定稱號 <" + theme.title().color() + ">【" + ItemRenderer.escape(theme.title().name()) + "】</" + theme.title().color() + ">已經戴上";
            player.sendMessage(plugin.text().prefixed(themeName(theme) + " <green>的挑戰全部完成！<gray>功勳 <#f5b942>+" + theme.bonusMerits() + "</#f5b942>" + titleText));
            if (theme.title() != null && !theme.title().stats().isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<#7fe07f>稱號的永久加成：<white>" + ItemRenderer.escape(String.join("、",
                        tw.exilecore.ascendancy.StatLines.describeAll(theme.title().stats()))) + "<gray>（擁有就有，不用戴；帳號裡每個角色都有）"));
            }
            player.showTitle(net.kyori.adventure.title.Title.title(plugin.text().parse(themeName(theme)),
                    plugin.text().parse("<green>每月挑戰全部完成"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(600))));
            if (config.announce()) {
                final String name = plugin.chat() == null ? player.getName() : plugin.chat().display(player);
                final Component message = plugin.text().prefixed("<gray>" + ItemRenderer.escape(name) + " 完成了 " + themeName(theme) + " <gray>的全部挑戰！");
                if (plugin.discord() != null) {
                    plugin.discord().achievement(ItemRenderer.escape(name) + " 完成了 " + themeName(theme) + " 的全部挑戰！");
                }
                for (final Player online : plugin.getServer().getOnlinePlayers()) {
                    if (online != player) {
                        online.sendMessage(message);
                    }
                }
            }
        }
        save(player, progress);
        if (plugin.quests() != null) {
            plugin.quests().updateTracker(player); // 側欄上的進度
        }
        return true;
    }

    /** 在線的人重畫任務追蹤（換月、重讀設定）。 */
    void refreshTrackers() {
        if (plugin.quests() == null) {
            return;
        }
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            plugin.quests().updateTracker(online);
        }
    }

    // ---- 提醒 ----

    /** 登入（帳號載入好）：這個月的主題與進度一行。 */
    public void greet(final Player player) {
        final EventConfig.Theme theme = theme();
        if (theme == null || !config.announce() || !greeted.add(player.getUniqueId())) {
            return;
        }
        final EventProgress progress = progress(player);
        final int done = progress == null ? 0 : progress.doneCount();
        player.sendMessage(plugin.text().prefixed("<gray>本月活動 " + themeName(theme) + "<gray>（剩 " + daysLeft() + " 天）：挑戰 <white>"
                + done + "/" + theme.challenges().size() + "<gray>　<yellow>/event<gray> 看詳情"));
    }

    void forget(final Player player) {
        greeted.remove(player.getUniqueId());
        lastNote.remove(player.getUniqueId());
        warnedAt.remove(player.getUniqueId());
    }

    /** 每分鐘：換月了就廣播新主題（挑戰自動從頭：進度記著月份）。 */
    void tick() {
        final YearMonth now = month();
        if (now.equals(lastMonth)) {
            return;
        }
        lastMonth = now;
        refreshTrackers(); // 側欄換成新主題的挑戰
        final EventConfig.Theme theme = theme();
        if (theme == null || !config.announce()) {
            return;
        }
        final Component message = plugin.text().prefixed("<gray>新的一個月：本月活動 " + themeName(theme) + "<gray>——" + ItemRenderer.escape(theme.lore())
                + " <yellow>/event");
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(message);
            online.playSound(online.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 1.0f);
        }
    }

    /** 主題對地圖的影響，一句一句（選單、/event 用）。 */
    public List<String> effectLines(final EventConfig.Theme theme) {
        final List<String> lines = new ArrayList<>();
        if (theme.quantity() > 0) {
            lines.add("地圖怪物物品數量 +" + Math.round(theme.quantity()) + "%");
        }
        if (theme.rarity() > 0) {
            lines.add("地圖怪物物品稀有度 +" + Math.round(theme.rarity()) + "%");
        }
        if (theme.rareMod() != null && plugin.monsters() != null) {
            final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(theme.rareMod());
            lines.add("地圖裡的稀有怪都是「" + (mod == null ? theme.rareMod() : mod.name()) + "」");
        }
        for (final Map.Entry<String, Integer> entry : theme.eventBoost().entrySet()) {
            final String name = switch (entry.getKey()) {
                case "shrine" -> "神龕";
                case "strongbox" -> "寶箱";
                case "breach" -> "裂隙";
                case "exile" -> "流寇";
                case "abyss" -> "深淵裂縫"; // 2026-10-18
                default -> entry.getKey();
            };
            lines.add(name + "更常出現（權重 +" + entry.getValue() + "）");
        }
        if (theme.ritualChance() >= 0) {
            lines.add("祭壇出現機率 " + Math.round(theme.ritualChance() * 100) + "%");
        }
        if (theme.deliriumChance() >= 0) {
            lines.add("幻霧出現機率 " + Math.round(theme.deliriumChance() * 100) + "%");
        }
        return lines;
    }
}
