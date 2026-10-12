package tw.exilecore.sponsor;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.admin.DeliveryService;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * sponsor 模組（2026-10）：星幣、累積贊助、月卡、全服加倍、外觀特效、抽獎箱、登入天數稱號（data/sponsor.yml）。
 * 資料存在委託模組的帳號進度裡，所以要在委託模組之後啟用。
 */
public final class SponsorModule implements Module, Listener {

    private final ExileCore plugin;
    private final SponsorConfig config = new SponsorConfig();
    private SponsorService service;
    private CosmeticRenderer renderer;
    /** 3D 背飾與寵物（2026-10）。 */
    private ModelCosmetics models;
    private Emotes emotes;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public SponsorModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "sponsor";
    }

    private static boolean personalSourceRegistered;

    @Override
    public void enable() throws Exception {
        if (plugin.bounties() == null) {
            throw new IllegalStateException("委託模組沒有啟用（看上面的錯誤訊息），贊助的資料沒地方存");
        }
        load();
        service = new SponsorService(plugin, config);
        if (!personalSourceRegistered) {
            // 2026-10 個人加倍藥：掉落數量、稀有度進屬性表（只註冊一次；查的時候找現在的贊助模組）
            personalSourceRegistered = true;
            plugin.players().addModifierSource(player -> plugin.sponsors() == null ? java.util.List.of() : plugin.sponsors().personalModifiers(player));
        }
        renderer = new CosmeticRenderer(plugin, service);
        service.registerTitles();
        for (final SponsorConfig.Cosmetic cosmetic : config.cosmetics().values()) {
            renderer.particle(cosmetic.particle()); // 先檢查粒子名稱（不能用的在主控台提醒）
        }
        plugin.bounties().addReadyListener(player -> {
            if (service != null) {
                service.onReady(player);
            }
        });
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tasks.add(plugin.repeat("sponsor.tick", service::tick, 20, 20));
        tasks.add(plugin.repeat("sponsor.minute", service::minute, 20 * 60, 20 * 60));
        tasks.add(plugin.repeat("sponsor.cosmetics", renderer::tick, 2, 2));
        emotes = new Emotes(plugin, service);
        service.vouchers = new StarVouchers(plugin, service);
        plugin.getServer().getPluginManager().registerEvents(service.vouchers, plugin);
        service.emotes = emotes;
        plugin.getServer().getPluginManager().registerEvents(emotes, plugin);
        models = new ModelCosmetics(plugin, service);
        models.cleanup();
        tasks.add(plugin.repeat("sponsor.models", models::tick, 1, 1));
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (service.loaded(player)) {
                service.onReady(player);
            }
        }
        plugin.getSLF4JLogger().info("贊助：{}（{} 階累積贊助、{} 個外觀特效、{} 種抽獎箱、{} 種全服加倍）", config.enabled() ? "開" : "關",
                config.tiers().size(), config.cosmetics().size(), config.boxes().size(), config.boosts().size());
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.reloaded();
        }
    }

    @Override
    public void disable() {
        for (final BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        if (models != null) {
            models.stop();
        }
        if (emotes != null) {
            emotes.stop();
        }
        if (service != null) {
            service.stop();
        }
    }

    private void load() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
    }

    /** 打開贊助選單（主選單的「贊助」、倉庫的贊助頁）。 */
    public static void openMenu(final ExileCore plugin, final Player player) {
        if (plugin.sponsors() != null) {
            SponsorMenus.open(plugin, player, plugin.sponsors());
        }
    }

    /** 執行期；模組沒啟用時為 null。 */
    public SponsorService service() {
        return service;
    }

    // ---- 登入 ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (service != null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            service.preLogin(event.getUniqueId());
        }
    }

    /** 伺服器滿了：累積贊助有「滿人也能進」的放行（只放行人數已滿，不放行維護、封鎖）。 */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(final PlayerLoginEvent event) {
        if (service != null && event.getResult() == PlayerLoginEvent.Result.KICK_FULL && service.bypassFull(event.getPlayer().getUniqueId())) {
            event.allow();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (models != null) {
            models.forget(event.getPlayer().getUniqueId());
        }
        if (service != null) {
            service.onQuit(event.getPlayer());
        }
    }

    // ---- 指令 ----

    /** /sponsor：打開贊助；/sponsor title 文字 [#顏色]、/sponsor title clear、/sponsor odds。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("sponsor")
                .executes(context -> {
                    final Player player = player(plugin, context);
                    if (player != null) {
                        SponsorMenus.open(plugin, player, plugin.sponsors());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("title")
                        .then(Commands.literal("clear").executes(context -> {
                            final Player player = player(plugin, context);
                            if (player != null) {
                                tell(plugin, player, plugin.sponsors().setCustomTitle(player, null, null), "<green>自訂稱號拿掉了");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context -> {
                            final Player player = player(plugin, context);
                            if (player != null) {
                                final String raw = StringArgumentType.getString(context, "text").trim();
                                // 文字後面接顏色：一個 #rrggbb、好幾個（漸層）、或 rainbow（2026-10）
                                final String[] words = raw.split("\\s+");
                                int first = words.length;
                                while (first > 1 && (words[first - 1].startsWith("#") || words[first - 1].equalsIgnoreCase("rainbow")
                                        || words[first - 1].equals("彩虹"))) {
                                    first--;
                                }
                                final String text = String.join(" ", java.util.Arrays.copyOfRange(words, 0, first));
                                final String color = first < words.length ? String.join(" ", java.util.Arrays.copyOfRange(words, first, words.length)) : null;
                                tell(plugin, player, plugin.sponsors().setCustomTitle(player, text, color),
                                        "<green>自訂稱號設好了<gray>（選單的「稱號」戴上）");
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("odds").executes(context -> {
                    if (plugin.sponsors() == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    final CommandSender sender = context.getSource().getSender();
                    for (final SponsorConfig.Box box : plugin.sponsors().config().boxes().values()) {
                        final List<String> parts = new ArrayList<>();
                        for (final var entry : box.weights().entrySet()) {
                            final SponsorConfig.Cosmetic cosmetic = plugin.sponsors().config().cosmetics().get(entry.getKey());
                            parts.add(SponsorService.rarityTag(cosmetic) + " <white>" + Odds.percent(entry.getValue(), box.total()));
                        }
                        sender.sendMessage(plugin.text().prefixed("<#ff9ad5>" + ItemRenderer.escape(box.name()) + "<gray>（保底 " + box.pity() + " 箱）："
                                + String.join("<gray>、", parts)));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build());
    }

    /**
     * /emote（2026-10）：打開動作選單；/emote 動作名稱（或代號）：直接做一次（不用先綁按鍵；Tab 會列出你擁有的）。
     */
    public static LiteralCommandNode<CommandSourceStack> emoteCommand(final ExileCore plugin, final String name) {
        return Commands.literal(name)
                .executes(context -> {
                    final Player player = player(plugin, context);
                    if (player != null) {
                        new SponsorMenus.EmoteMenu(plugin, player, plugin.sponsors()).open();
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("emote", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            if (plugin.sponsors() != null && context.getSource().getExecutor() instanceof Player player) {
                                final String typed = builder.getRemaining().trim();
                                for (final SponsorConfig.Cosmetic cosmetic : plugin.sponsors().config().cosmetics().values()) {
                                    if (cosmetic.isEmote() && plugin.sponsors().ownsCosmetic(player, cosmetic.id())
                                            && (typed.isEmpty() || cosmetic.name().contains(typed) || cosmetic.emote().startsWith(typed))) {
                                        builder.suggest(cosmetic.name());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            final Player player = player(plugin, context);
                            if (player == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            final String typed = StringArgumentType.getString(context, "emote").trim();
                            SponsorConfig.Cosmetic found = null;
                            for (final SponsorConfig.Cosmetic cosmetic : plugin.sponsors().config().cosmetics().values()) {
                                if (cosmetic.isEmote() && (cosmetic.name().equals(typed) || cosmetic.emote().equalsIgnoreCase(typed)
                                        || cosmetic.id().equalsIgnoreCase(typed))) {
                                    found = cosmetic;
                                    break;
                                }
                            }
                            if (found == null) {
                                player.sendMessage(plugin.text().prefixed("<red>沒有叫「" + ItemRenderer.escape(typed) + "」的動作"));
                            } else if (!plugin.sponsors().ownsCosmetic(player, found.id())) {
                                player.sendMessage(plugin.text().prefixed("<red>你還沒有「" + ItemRenderer.escape(found.name()) + "」這個動作"));
                            } else if (plugin.sponsors().emotes != null) {
                                plugin.sponsors().emotes.play(player, found, false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private static void tell(final ExileCore plugin, final Player player, final String problem, final String ok) {
        player.sendMessage(plugin.text().prefixed(problem == null ? ok : "<red>" + ItemRenderer.escape(problem)));
    }

    private static Player player(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return null;
        }
        if (plugin.sponsors() == null) {
            player.sendMessage(plugin.text().prefixed("<red>贊助模組沒有啟用（看主控台的錯誤訊息）"));
            return null;
        }
        return player;
    }

    /**
     * /ec sponsor give 玩家 數量 [bonus]（管理員、主控台、贊助商城）：發星幣（離線的下次登入發；bonus = 贈送，不算累積贊助）；
     * /ec sponsor info 玩家、/ec sponsor take 玩家 數量 [refund]（線上的人）。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("sponsor")
                .then(Commands.literal("give").then(Commands.argument("player", StringArgumentType.word()).suggests(onlineAccounts(plugin))
                        // 2026-10：/ec sponsor give 玩家 cosmetic 時裝代號|bundle:組代號（絕版、限定時裝只有這條路；贊助商城的 S1 禮包也用這行）
                        .then(Commands.literal("cosmetic").then(Commands.argument("id", StringArgumentType.word()).suggests(cosmeticIds(plugin))
                                .executes(context -> giveCosmetic(plugin, context))))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1, 1_000_000))
                                .executes(context -> give(plugin, context, "paid"))
                                .then(Commands.literal("bonus").executes(context -> give(plugin, context, "bonus")))
                                .then(Commands.literal("card").executes(context -> give(plugin, context, "card")))
                                .then(Commands.literal("fashion").executes(context -> give(plugin, context, "fashion"))))))
                .then(Commands.literal("info").then(Commands.argument("player", StringArgumentType.word()).suggests(onlineAccounts(plugin)).executes(context -> {
                    final CommandSender sender = context.getSource().getSender();
                    final Player target = online(plugin, sender, StringArgumentType.getString(context, "player"));
                    if (target != null) {
                        sender.sendMessage(plugin.text().prefixed("<white>" + ItemRenderer.escape(target.getName()) + " 的贊助："));
                        for (final String line : plugin.sponsors().describe(target)) {
                            sender.sendMessage(plugin.text().parse(line));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("take").then(Commands.argument("player", StringArgumentType.word()).suggests(onlineAccounts(plugin))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1, 1_000_000))
                                .executes(context -> take(plugin, context, false))
                                .then(Commands.literal("refund").executes(context -> take(plugin, context, true))))));
    }

    private static Player online(final ExileCore plugin, final CommandSender sender, final String name) {
        if (plugin.sponsors() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>贊助模組沒有啟用"));
            return null;
        }
        final Player target = plugin.getServer().getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(name) + " 不在線（玩家 ID）"));
        }
        return target;
    }

    private static int take(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final boolean refund) {
        final CommandSender sender = context.getSource().getSender();
        final Player target = online(plugin, sender, StringArgumentType.getString(context, "player"));
        if (target != null) {
            final long amount = LongArgumentType.getLong(context, "amount");
            final String problem = plugin.sponsors().take(target, amount, refund);
            sender.sendMessage(plugin.text().prefixed(problem == null ? "<green>扣了 " + amount + " 星幣" + (refund ? "（累積贊助也扣）" : "")
                    : "<red>" + ItemRenderer.escape(problem)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int give(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String ref) {
        final boolean bonus = !"paid".equals(ref);
        final String what = "card".equals(ref) ? "月卡幣" : "fashion".equals(ref) ? "時裝幣" : "星幣";
        final CommandSender sender = context.getSource().getSender();
        final String name = StringArgumentType.getString(context, "player");
        final long amount = LongArgumentType.getLong(context, "amount");
        if (plugin.sponsors() == null || plugin.admin() == null || plugin.admin().deliveries() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>贊助或管理模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final DeliveryService.Spec spec = new DeliveryService.Spec(DeliveryService.SPONSOR, ref, null, 0, amount);
        plugin.admin().deliveries().grant(name, List.of(spec), "paid".equals(ref) ? "贊助" : "贊助（贈送" + what + "）", sender.getName()).whenComplete((created, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(plugin.text().prefixed("<red>發不出去：" + ItemRenderer.escape(String.valueOf(error.getMessage()))));
                        plugin.getSLF4JLogger().error("[贊助] 發 {} {} 給 {} 失敗", amount, what, name, error);
                    } else if (!created.unknown().isEmpty()) {
                        sender.sendMessage(plugin.text().prefixed("<red>找不到玩家「" + ItemRenderer.escape(name) + "」（要進過伺服器）"));
                    } else {
                        sender.sendMessage(plugin.text().prefixed("<green>發給 " + ItemRenderer.escape(name) + " " + amount + " " + what
                                + ("bonus".equals(ref) ? "（贈送，不算累積）" : "") + "<gray>（離線的下次登入發）"));
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int giveCosmetic(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        final String name = StringArgumentType.getString(context, "player");
        final String id = StringArgumentType.getString(context, "id");
        if (plugin.sponsors() == null || plugin.admin() == null || plugin.admin().deliveries() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>贊助或管理模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final String label;
        try {
            plugin.sponsors().resolveCosmetics(id);
            label = plugin.sponsors().cosmeticLabel(id);
        } catch (final IllegalArgumentException e) {
            sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(e.getMessage())));
            return Command.SINGLE_SUCCESS;
        }
        final DeliveryService.Spec spec = new DeliveryService.Spec(DeliveryService.COSMETIC, id, null, 0, 1);
        plugin.admin().deliveries().grant(name, List.of(spec), "贊助（" + label + "）", sender.getName()).whenComplete((created, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(plugin.text().prefixed("<red>發不出去：" + ItemRenderer.escape(String.valueOf(error.getMessage()))));
                        plugin.getSLF4JLogger().error("[贊助] 發時裝 {} 給 {} 失敗", id, name, error);
                    } else if (!created.unknown().isEmpty()) {
                        sender.sendMessage(plugin.text().prefixed("<red>找不到玩家「" + ItemRenderer.escape(name) + "」（要進過伺服器）"));
                    } else {
                        sender.sendMessage(plugin.text().prefixed("<green>發給 " + ItemRenderer.escape(name) + " " + ItemRenderer.escape(label)
                                + "<gray>（離線的下次登入發；已經有的不重複給）"));
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    /** 時裝代號與 bundle:組代號（按 TAB 補完；2026-10）。 */
    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> cosmeticIds(final ExileCore plugin) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            if (plugin.sponsors() != null) {
                for (final String id : plugin.sponsors().config().bundles().keySet()) {
                    if (("bundle:" + id).startsWith(remaining)) {
                        builder.suggest("bundle:" + id);
                    }
                }
                for (final String id : plugin.sponsors().config().limitedTitles().keySet()) {
                    if (("title:" + id).startsWith(remaining)) {
                        builder.suggest("title:" + id);
                    }
                }
                for (final String id : plugin.sponsors().config().cosmetics().keySet()) {
                    if (id.startsWith(remaining)) {
                        builder.suggest(id);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    /** 在線玩家的 ID（按 TAB 補完；2026-10）。 */
    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> onlineAccounts(final ExileCore plugin) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        };
    }
}
