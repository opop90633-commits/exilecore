package tw.exilecore.character;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在線人數加成（2026-10）：同時在線、真的在玩的人數到門檻，全服就有經驗、掉落數量、稀有度、金幣加成；人數掉下來就收回。
 * 門檻與數值寫在 config.yml 的 online_bonus。
 * <p>
 * 只算「在玩的人」：有角色、不是旁觀模式、{@code idle-minutes} 分鐘內按過移動鍵或說過話；同一個 IP 只算一個（擋分身灌人數）。
 * 人數掉到門檻以下要持續 {@link #GRACE_MILLIS} 才降階（有人換角色、斷線重連不會讓加成一閃一閃）。
 * <p>
 * 數值放在靜態欄位（跟贊助的全服加倍 {@link tw.exilecore.sponsor.Boosts} 一樣），讀設定時乘（加）上去：
 * 經驗、金幣是倍數（1.05 = +5%），數量、稀有度是小數（0.05 = +5%）。主執行緒改，讀的地方也在主執行緒。
 */
public final class OnlineBonus implements Listener {

    /** 一階：幾個人、各加多少 %（整數，總共，不是跟上一階的差）。 */
    public record Tier(int players, double experience, double quantity, double rarity, double gold) {
    }

    /** 人數掉下來多久才降階。 */
    static final long GRACE_MILLIS = 60_000L;

    private static volatile double experience = 1;
    private static volatile double quantity;
    private static volatile double rarity;
    private static volatile double gold = 1;

    /** 經驗再乘幾倍（1 = 沒有）。 */
    public static double experience() {
        return experience;
    }

    /** 掉落數量再加多少（小數）。 */
    public static double quantity() {
        return quantity;
    }

    /** 掉落稀有度再加多少（小數）。 */
    public static double rarity() {
        return rarity;
    }

    /** 金幣再乘幾倍。 */
    public static double gold() {
        return gold;
    }

    private final ExileCore plugin;
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private boolean enabled;
    private long idleMillis = 5 * 60_000L;
    private boolean onePerIp = true;
    private List<Tier> tiers = List.of();
    private int counted;
    private Tier current;
    /** 人數第一次掉到現在這一階以下的時間（0 = 沒掉）。 */
    private long droppedSince;

    OnlineBonus(final ExileCore plugin) {
        this.plugin = plugin;
        readConfig();
    }

    /** 讀 config.yml 的 online_bonus（啟動、/ec reload）。 */
    void readConfig() {
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("online_bonus");
        enabled = section != null && section.getBoolean("enabled", true);
        idleMillis = Math.max(1, section == null ? 5 : section.getInt("idle-minutes", 5)) * 60_000L;
        onePerIp = section == null || section.getBoolean("one-per-ip", true);
        tiers = section == null ? List.of() : parse(section.getMapList("tiers"));
        current = null;
        droppedSince = 0;
        apply(null);
    }

    static List<Tier> parse(final List<Map<?, ?>> raw) {
        final List<Tier> list = new ArrayList<>();
        for (final Map<?, ?> entry : raw) {
            final int players = (int) number(entry.get("players"));
            if (players <= 0) {
                continue;
            }
            list.add(new Tier(players, number(entry.get("experience")), number(entry.get("quantity")), number(entry.get("rarity")),
                    number(entry.get("gold"))));
        }
        list.sort(java.util.Comparator.comparingInt(Tier::players));
        return List.copyOf(list);
    }

    private static double number(final Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.doubleValue());
        }
        try {
            return value == null ? 0 : Math.max(0, Double.parseDouble(value.toString().trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 這個人數該拿哪一階（沒到第一階回傳 null）。 */
    static Tier tierFor(final List<Tier> tiers, final int players) {
        Tier best = null;
        for (final Tier tier : tiers) {
            if (players >= tier.players()) {
                best = tier;
            }
        }
        return best;
    }

    /** 下一階（已經最高回傳 null）。 */
    Tier next() {
        for (final Tier tier : tiers) {
            if (counted < tier.players()) {
                return tier;
            }
        }
        return null;
    }

    // ---- 在玩的人 ----

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInput(final org.bukkit.event.player.PlayerInputEvent event) {
        final org.bukkit.Input input = event.getInput();
        if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump()) {
            lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChat(final io.papermc.paper.event.player.AsyncChatEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> lastActive.put(uuid, System.currentTimeMillis()));
    }

    @EventHandler
    public void onJoin(final org.bukkit.event.player.PlayerJoinEvent event) {
        lastActive.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }

    /** 現在算進去的人數。 */
    public int counted() {
        return counted;
    }

    /** 現在這一階（沒有回傳 null）。 */
    public Tier current() {
        return current;
    }

    /** 每 2 秒：數人、換階。 */
    void tick() {
        if (!enabled || tiers.isEmpty()) {
            counted = 0;
            if (current != null) {
                current = null;
                apply(null);
            }
            return;
        }
        final long now = System.currentTimeMillis();
        final Set<String> addresses = new HashSet<>();
        int count = 0;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            if (!state.isLoaded() || !state.hasCharacter()) {
                continue;
            }
            if (steering(player)) {
                lastActive.put(player.getUniqueId(), now);
            }
            final Long active = lastActive.get(player.getUniqueId());
            if (active == null || now - active > idleMillis) {
                continue;
            }
            if (onePerIp) {
                final java.net.InetSocketAddress address = player.getAddress();
                final String ip = address == null || address.getAddress() == null ? player.getUniqueId().toString()
                        : address.getAddress().getHostAddress();
                if (!addresses.add(ip)) {
                    continue;
                }
            }
            count++;
        }
        counted = count;
        final Tier want = tierFor(tiers, count);
        if (want == current) {
            droppedSince = 0;
            return;
        }
        final boolean up = current == null || want != null && want.players() > current.players();
        if (!up) {
            if (droppedSince == 0) {
                droppedSince = now;
            }
            if (now - droppedSince < GRACE_MILLIS) {
                return; // 掉下來還沒滿一分鐘：先留著
            }
        }
        droppedSince = 0;
        final Tier before = current;
        current = want;
        apply(want);
        announce(before, want);
    }

    private static boolean steering(final Player player) {
        final org.bukkit.Input input = player.getCurrentInput();
        return input != null && (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump());
    }

    private static void apply(final Tier tier) {
        experience = tier == null ? 1 : 1 + tier.experience() / 100.0;
        quantity = tier == null ? 0 : tier.quantity() / 100.0;
        rarity = tier == null ? 0 : tier.rarity() / 100.0;
        gold = tier == null ? 1 : 1 + tier.gold() / 100.0;
    }

    private void announce(final Tier before, final Tier after) {
        final String message;
        if (after == null) {
            message = "<gray>在線人數加成結束了（在玩的人少於 " + tiers.getFirst().players() + " 人）";
        } else if (before == null || after.players() > before.players()) {
            message = "<#ffd27f>在線 " + after.players() + " 人！<white>全服加成：" + describe(after) + nextText();
        } else {
            message = "<gray>在線人數掉到 " + after.players() + " 人那一階：" + describe(after);
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(plugin.text().prefixed(message));
        }
        plugin.getSLF4JLogger().info("在線人數加成：{} 人 → {}", counted, after == null ? "沒有" : describe(after));
    }

    /** 「經驗 +5%、掉落數量 +5%」。 */
    static String describe(final Tier tier) {
        final List<String> parts = new ArrayList<>();
        if (tier.experience() > 0) {
            parts.add("經驗 +" + trim(tier.experience()) + "%");
        }
        if (tier.quantity() > 0) {
            parts.add("掉落數量 +" + trim(tier.quantity()) + "%");
        }
        if (tier.rarity() > 0) {
            parts.add("稀有度 +" + trim(tier.rarity()) + "%");
        }
        if (tier.gold() > 0) {
            parts.add("金幣 +" + trim(tier.gold()) + "%");
        }
        return String.join("、", parts);
    }

    /** 「（再 7 人：稀有度 +5%）」；已經最高回傳空字串。 */
    String nextText() {
        final Tier next = next();
        return next == null ? "" : "<gray>（再 " + (next.players() - counted) + " 人：" + describe(next) + "）";
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
