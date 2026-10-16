package tw.exilecore.life;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.module.Module;

import java.util.List;

/** life 模組：生活玩法「星骸之海」（data/life.yml；2026-10）。要在委託模組之後啟用（資料記在帳號進度）。 */
public final class LifeModule implements Module, Listener {

    private final ExileCore plugin;
    private final LifeConfig config = new LifeConfig();
    private LifeService service;
    private BukkitTask task;

    public LifeModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "life";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new LifeService(plugin, config);
        if (config.enabled()) {
            service.loadWorld();
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.players().addBuffSource(player -> service == null ? List.of() : service.foodModifiers(player)); // 料理的加成
        // 淵鯨比照巔峰——怪物、Boss 詞綴與階段、狂暴的傷害；場地上的玩家吃巔峰的玩家詞綴
        plugin.monsters().addAugmenter((entity, sheet) -> {
            if (service != null) {
                service.whale().augment(entity, sheet);
            }
        });
        plugin.players().addSituationalSource(player -> service == null ? List.of() : service.whale().playerModifiers(player));
        if (plugin.bounties() != null) {
            plugin.bounties().addReadyListener(player -> {
                if (service != null) {
                    service.onReady(player); // 登入、換角色：讀生活資料，料理的加成接著算
                }
            });
        }
        registerTitles();
        task = plugin.repeat("life.tick", service::tick, 40, 2);
        plugin.getSLF4JLogger().info("生活玩法：{}（{}；材料 {} 種、配方 {} 個）", config.enabled() ? config.name() : "關",
                service.world() == null ? "世界沒有載入" : service.world().getName(), config.materials().size(), config.recipes().size());
    }

    @Override
    public void reload() throws Exception {
        validated(); // 設定有錯就整個不動（島、淵鯨也不收）
        if (service != null) {
            // 正在的小島、淵鯨先收掉（設定可能改了座標、淵鯨可能拿掉）
            service.islands().shutdown();
            service.whale().shutdown();
        }
        load();
        if (service != null && service.world() == null && config.enabled()) {
            service.loadWorld();
        }
        registerTitles();
    }

    /** 生活的稱號（從帳號進度算：瓶中信收齊、生活等級 50、打倒淵鯨）。 */
    private void registerTitles() {
        final tw.exilecore.bounty.BountyService bounties = plugin.bounties();
        if (bounties == null || config.titles().isEmpty()) {
            return;
        }
        final java.util.Map<String, tw.exilecore.bounty.BountyConfig.Title> titles = new java.util.LinkedHashMap<>();
        for (final LifeConfig.Title title : config.titles().values()) {
            titles.put(title.id(), new tw.exilecore.bounty.BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        bounties.registerTitles(titles);
        bounties.setTitleSource("life", new tw.exilecore.bounty.BountyService.TitleSource() {
            @Override
            public java.util.Set<String> owned(final java.util.Map<String, String> progress) {
                final java.util.Set<String> owned = new java.util.HashSet<>();
                final int letters = Math.min(63, config.letters().size());
                if (letters > 0 && Long.bitCount(number(progress.get(LifeService.LETTERS)) & ((1L << letters) - 1)) >= letters) {
                    owned.add("letter_finder");
                }
                if (config.levelOf(number(progress.get(LifeService.XP))) >= WANDERER_LEVEL) {
                    owned.add("sea_wanderer");
                }
                if (number(progress.get(LifeService.WHALE)) > 0) {
                    owned.add("abyss_hunter");
                }
                owned.retainAll(titles.keySet());
                return owned;
            }

            @Override
            public java.util.List<String> howTo(final Player player, final String id) {
                return switch (id) {
                    case "letter_finder" -> titles.containsKey(id) ? java.util.List.of("<gray>" + config.name() + "：收集全部 " + config.letters().size()
                            + " 封瓶中信（目前 " + (service == null ? 0 : service.lettersRead(player)) + " 封）") : null;
                    case "sea_wanderer" -> titles.containsKey(id) ? java.util.List.of("<gray>" + config.name() + "：生活等級 " + WANDERER_LEVEL) : null;
                    case "abyss_hunter" -> titles.containsKey(id) && config.whale() != null
                            ? java.util.List.of("<gray>" + config.name() + "：打倒深淵巨獸「" + config.whale().name() + "」") : null;
                    default -> null;
                };
            }

            @Override
            public String sourceName(final String id) {
                return titles.containsKey(id) ? config.name() : null;
            }
        });
    }

    static final int WANDERER_LEVEL = 50;

    private static long number(final String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.shutdown();
        }
    }

    private void load() throws Exception {
        validated();
        config.load(plugin.dataFolder().resolve("data"));
    }

    /** 讀一份新的設定檢查（有問題丟例外）。 */
    private void validated() throws Exception {
        final LifeConfig fresh = new LifeConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = fresh.validate(
                id -> plugin.currencies() == null || plugin.currencies().repository().get(id) != null,
                name -> org.bukkit.Material.matchMaterial(name) != null);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(LifeConfig.FILE + " 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
    }

    public LifeService service() {
        return service;
    }

    // ---- 事件 ----

    /** 右鍵：攔截網收網、星索鉤撈東西（在海上一律不甩原版的釣竿）。比技能的右鍵（MONITOR）早。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        if (service == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && (service.islands().interact(player, event.getClickedBlock()) || service.useStation(player, event.getClickedBlock()) || service.collectNet(player, event.getClickedBlock()))) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            return;
        }
        if (service.foodOf(event.getItem()) != null) {
            // 吃料理（營地、海上；戰鬥區手上拿的是武器）
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            service.eat(player, event.getItem());
            return;
        }
        if (LifeWhale.HORN_ITEM.equals(service.itemId(event.getItem()))) {
            // 吹深淵號角叫出淵鯨（不甩原版的號角）
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            service.whale().blow(player, event.getItem());
            return;
        }
        if (LifeService.HOOK_ITEM.equals(service.itemId(event.getItem())) && service.useHook(player)) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFish(final PlayerFishEvent event) {
        if (service != null && (service.lifeMode(event.getPlayer().getLocation())
                || LifeService.HOOK_ITEM.equals(service.itemId(event.getPlayer().getInventory().getItemInMainHand())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(final BlockPlaceEvent event) {
        if (service == null) {
            return;
        }
        final org.bukkit.Location where = event.getBlock().getLocation();
        if ((service.islands().onIsland(where) || service.whale().inArena(where)) && !event.getPlayer().hasPermission(ExileCore.PERMISSION_ADMIN)) {
            event.setCancelled(true); // 小島、淵鯨的場地不能蓋
            return;
        }
        if (!service.raftArea(where)) {
            if (service.itemId(event.getItemInHand()) != null && !event.getPlayer().hasPermission(ExileCore.PERMISSION_ADMIN)) {
                event.setCancelled(true); // 筏的方塊只能在海上用
            }
            return;
        }
        final String problem = service.canPlace(event.getPlayer(), event.getBlockPlaced(), event.getItemInHand());
        if (problem != null) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(final BlockBreakEvent event) {
        if (service == null) {
            return;
        }
        final org.bukkit.Location where = event.getBlock().getLocation();
        if ((service.islands().onIsland(where) || service.whale().inArena(where)) && !event.getPlayer().hasPermission(ExileCore.PERMISSION_ADMIN)) {
            event.setCancelled(true); // 小島、淵鯨的場地不能拆
            return;
        }
        if (!service.raftArea(where)) {
            return;
        }
        final String problem = service.breakBlock(event.getPlayer(), event.getBlock());
        if (problem != null) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
        } else {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (service != null && service.lifeModeOrSea(event.getBlock().getLocation()) && !event.getPlayer().hasPermission(ExileCore.PERMISSION_ADMIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (service != null && service.lifeModeOrSea(event.getBlock().getLocation()) && !event.getPlayer().hasPermission(ExileCore.PERMISSION_ADMIN)) {
            event.setCancelled(true);
        }
    }

    /** 渦齒鯊被打倒。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(final org.bukkit.event.entity.EntityDeathEvent event) {
        if (service != null && service.lifeModeOrSea(event.getEntity().getLocation())) {
            service.onDeath(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        if (service != null && service.lifeModeOrSea(event.getPlayer().getLocation())) {
            service.diedAtSea(event.getPlayer());
        }
    }

    /** 死在海上回自己的筏（比區域的重生晚，蓋過去）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        if (service == null) {
            return;
        }
        final org.bukkit.Location at = service.respawn(event.getPlayer());
        if (at != null) {
            event.setRespawnLocation(at);
        }
    }

    /** 島上的怪、淵鯨不會去盯筏上的人（筏上不能放技能，等於只能挨打）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(final org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (service == null || !(event.getTarget() instanceof Player target) || !service.lifeMode(target.getLocation())) {
            return;
        }
        final String tag = event.getEntity().getPersistentDataContainer().get(service.driftKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (tag != null && (tag.startsWith("island:") || tag.startsWith("whale"))) {
            event.setCancelled(true);
        }
    }

    /** 淵鯨（快樂惡魂）不能拴、不能騎、不能互動。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(final org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (service != null && service.whale().isWhale(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeash(final org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        if (service != null && service.whale().isWhale(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 下線：在委託模組卸載帳號之前把生活資料寫回去（LOWEST 先跑）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }

    // ---- 指令 ----

    /**
     * 玩家指令：/sea 打開星骸之海。
     * 管理員（2026-10：先只開給管理員）：/sea allow &lt;玩家&gt; 先開放給某人、/sea deny &lt;玩家&gt; 拿掉、/sea list 名單、
     * /sea open 全部開放、/sea close 關回只有管理員與名單、/sea stamina &lt;玩家&gt; [數量] 給精力（沒寫數量 = 回滿；不受每天上限）。
     * 一起玩（2026-10-16）：/sea home 回自己的筏、/sea invite／visit／accept／kick &lt;玩家&gt;、/sea member add|remove &lt;玩家&gt;、/sea members。
     */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        final java.util.function.Predicate<CommandSourceStack> admin = source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN);
        return List.of(Commands.literal("sea").executes(context -> {
            final Entity executor = context.getSource().getExecutor();
            if (!(executor instanceof Player player)) {
                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                return Command.SINGLE_SUCCESS;
            }
            open(plugin, player);
            return Command.SINGLE_SUCCESS;
        })
                .then(Commands.literal("home").executes(context -> social(plugin, context.getSource(), "home", null)))
                .then(Commands.literal("accept").executes(context -> social(plugin, context.getSource(), "accept", null)))
                .then(Commands.literal("members").executes(context -> social(plugin, context.getSource(), "members", null)))
                .then(Commands.literal("invite").then(onlineArgument(plugin)
                        .executes(context -> social(plugin, context.getSource(), "invite", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("visit").then(onlineArgument(plugin)
                        .executes(context -> social(plugin, context.getSource(), "visit", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("kick").then(onlineArgument(plugin)
                        .executes(context -> social(plugin, context.getSource(), "kick", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("member")
                        .then(Commands.literal("add").then(onlineArgument(plugin)
                                .executes(context -> social(plugin, context.getSource(), "member_add", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("remove").then(onlineArgument(plugin)
                                .executes(context -> social(plugin, context.getSource(), "member_remove", com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("open").requires(admin).executes(context -> access(plugin, context.getSource().getSender(), "open", null)))
                .then(Commands.literal("close").requires(admin).executes(context -> access(plugin, context.getSource().getSender(), "close", null)))
                .then(Commands.literal("list").requires(admin).executes(context -> access(plugin, context.getSource().getSender(), "list", null)))
                .then(Commands.literal("allow").requires(admin)
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (final Player online : plugin.getServer().getOnlinePlayers()) {
                                        builder.suggest(online.getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> access(plugin, context.getSource().getSender(), "allow",
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("stamina").requires(admin)
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (final Player online : plugin.getServer().getOnlinePlayers()) {
                                        builder.suggest(online.getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> stamina(plugin, context.getSource().getSender(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player"), 0))
                                .then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100000))
                                        .executes(context -> stamina(plugin, context.getSource().getSender(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player"),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("deny").requires(admin)
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    final LifeService life = plugin.life();
                                    if (life != null) {
                                        for (final String name : life.allowed().values()) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> access(plugin, context.getSource().getSender(), "deny",
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "player")))))
                .build());
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> onlineArgument(final ExileCore plugin) {
        return Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (final Player online : plugin.getServer().getOnlinePlayers()) {
                        builder.suggest(online.getName());
                    }
                    return builder.buildFuture();
                });
    }

    /** 找玩家：在線的照現在的名字，不在線的照伺服器記得的（沒來過 null）。 */
    private static org.bukkit.OfflinePlayer findPlayer(final ExileCore plugin, final String name) {
        final Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return plugin.getServer().getOfflinePlayerIfCached(name);
    }

    /** 一起玩的指令（2026-10-16）。 */
    private static int social(final ExileCore plugin, final CommandSourceStack source, final String action, final String name) {
        if (!(source.getExecutor() instanceof Player player)) {
            source.getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final LifeService life = plugin.life();
        if (life == null) {
            player.sendMessage(plugin.text().prefixed("<red>生活模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        if (!life.canEnter(player)) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(life.config().name()) + "還沒有開放"));
            return Command.SINGLE_SUCCESS;
        }
        String problem = null;
        String done = null;
        switch (action) {
            case "home" -> problem = life.travel(player, false);
            case "accept" -> problem = life.accept(player);
            case "members" -> {
                final LifeService.Profile profile = life.profile(player);
                final java.util.Map<java.util.UUID, String> mine = profile == null || profile.plot < 0 ? java.util.Map.of() : life.membersOf(profile.plot);
                player.sendMessage(plugin.text().prefixed("<white>你的木筏的共用成員：" + (mine.isEmpty() ? "<gray>沒有（/sea member add 玩家）"
                        : "<green>" + ItemRenderer.escape(String.join("、", mine.values())))));
                final List<String> others = new java.util.ArrayList<>();
                for (final int plot : life.memberOf(player.getUniqueId())) {
                    others.add(life.ownerName(plot));
                }
                if (!others.isEmpty()) {
                    player.sendMessage(plugin.text().prefixed("<white>你也是這些人的共用成員：<green>" + ItemRenderer.escape(String.join("、", others))
                            + "<gray>（/sea visit 名字 直接去）"));
                }
            }
            default -> {
                final org.bukkit.OfflinePlayer target = findPlayer(plugin, name);
                if (target == null) {
                    problem = "找不到 " + name;
                    break;
                }
                final String shown = target.getName() == null ? name : target.getName();
                final Player online = target.getPlayer();
                switch (action) {
                    case "invite" -> {
                        if (online == null) {
                            problem = shown + " 不在線上";
                        } else {
                            problem = life.invite(player, online);
                            done = "邀請了 " + shown + "（" + LifeService.INVITE_MILLIS / 60_000 + " 分鐘內有效）";
                        }
                    }
                    case "visit" -> problem = life.visit(player, target.getUniqueId(), shown);
                    case "kick" -> {
                        if (online == null) {
                            problem = shown + " 不在線上";
                        } else {
                            problem = life.kick(player, online);
                            done = "請 " + shown + " 離開了";
                        }
                    }
                    case "member_add" -> {
                        problem = life.addMember(player, target.getUniqueId(), shown);
                        done = shown + " 成了你的木筏的共用成員：可以蓋、拆、收網、種田、掌舵（/sea visit " + player.getName() + " 直接來）";
                        if (problem == null && online != null) {
                            online.sendMessage(plugin.text().prefixed("<" + life.config().color() + ">" + ItemRenderer.escape(player.getName())
                                    + " 把你加成木筏的共用成員<gray>（/sea visit " + ItemRenderer.escape(player.getName()) + " 直接去）"));
                        }
                    }
                    case "member_remove" -> {
                        problem = life.removeMember(player, target.getUniqueId(), shown);
                        done = "把 " + shown + " 從共用成員拿掉了";
                    }
                    default -> {
                    }
                }
            }
        }
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
        } else if (done != null) {
            player.sendMessage(plugin.text().prefixed("<green>" + ItemRenderer.escape(done)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int stamina(final ExileCore plugin, final org.bukkit.command.CommandSender sender, final String name, final int amount) {
        final LifeService life = plugin.life();
        if (life == null) {
            sender.sendMessage(plugin.text().prefixed("<red>生活模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final Player target = plugin.getServer().getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(name) + " 不在線上"));
            return Command.SINGLE_SUCCESS;
        }
        final int restored = life.grantStamina(target, amount);
        if (restored < 0) {
            sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(target.getName()) + " 還沒有角色"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<green>" + ItemRenderer.escape(target.getName()) + " 的精力 +" + restored
                + "<gray>（現在 " + life.stamina(target) + "）"));
        if (restored > 0 && target != sender) {
            target.sendMessage(plugin.text().prefixed("<#7fd4ff>精力 +" + restored + "<gray>（現在 " + life.stamina(target) + "）"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int access(final ExileCore plugin, final org.bukkit.command.CommandSender sender, final String action, final String name) {
        final LifeService life = plugin.life();
        if (life == null) {
            sender.sendMessage(plugin.text().prefixed("<red>生活模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final String title = ItemRenderer.escape(life.config().name());
        switch (action) {
            case "open" -> {
                life.setOpenToAll(true);
                sender.sendMessage(plugin.text().prefixed("<green>" + title + "全部開放了"));
                for (final Player online : plugin.getServer().getOnlinePlayers()) {
                    online.sendMessage(plugin.text().prefixed("<#7fd4ff>🌊 " + title + "開放了！<gray>選單 → " + title + "（/sea）"));
                }
            }
            case "close" -> sender.sendMessage(plugin.text().prefixed("<yellow>" + title + "關回只開放給管理員與名單上的 " + life.allowed().size() + " 人"
                    + (life.openToAll() ? "" : "（本來就沒有全部開放）")));
            case "list" -> {
                sender.sendMessage(plugin.text().prefixed("<white>" + title + "：" + (life.openToAll() ? "<green>全部開放" : "<yellow>只開放給管理員與名單")
                        + "<gray>；名單 " + life.allowed().size() + " 人"));
                if (!life.allowed().isEmpty()) {
                    sender.sendMessage(plugin.text().parse("<gray>" + ItemRenderer.escape(String.join("、", life.allowed().values()))));
                }
            }
            case "allow", "deny" -> {
                Player online = plugin.getServer().getPlayerExact(name);
                java.util.UUID id = online == null ? null : online.getUniqueId();
                String shown = online == null ? name : online.getName();
                if (id == null) {
                    final org.bukkit.OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
                    if (cached != null) {
                        id = cached.getUniqueId();
                        shown = cached.getName() == null ? name : cached.getName();
                    }
                }
                if (id == null && "deny".equals(action)) {
                    for (final java.util.Map.Entry<java.util.UUID, String> entry : life.allowed().entrySet()) {
                        if (entry.getValue().equalsIgnoreCase(name)) {
                            id = entry.getKey();
                            shown = entry.getValue();
                        }
                    }
                }
                if (id == null) {
                    sender.sendMessage(plugin.text().prefixed("<red>找不到玩家 " + ItemRenderer.escape(name) + "（要上線過這個伺服器）"));
                    return Command.SINGLE_SUCCESS;
                }
                if ("allow".equals(action)) {
                    final boolean added = life.allow(id, shown);
                    sender.sendMessage(plugin.text().prefixed(added ? "<green>" + ItemRenderer.escape(shown) + " 可以進" + title + "了"
                            : "<gray>" + ItemRenderer.escape(shown) + " 本來就在名單上"));
                    if (added && online != null) {
                        online.sendMessage(plugin.text().prefixed("<#7fd4ff>🌊 你可以先進" + title + "了！<gray>選單 → " + title + "（/sea）"));
                    }
                } else {
                    final boolean removed = life.deny(id);
                    sender.sendMessage(plugin.text().prefixed(removed ? "<yellow>" + ItemRenderer.escape(shown) + " 從名單拿掉了"
                            + (life.openToAll() ? "（現在全部開放，他還是進得去）" : "") : "<gray>" + ItemRenderer.escape(shown) + " 本來就不在名單上"));
                }
            }
            default -> {
            }
        }
        if ("close".equals(action)) {
            life.setOpenToAll(false);
        }
        return Command.SINGLE_SUCCESS;
    }

    public static void open(final ExileCore plugin, final Player player) {
        final LifeService life = plugin.life();
        if (life == null || !life.config().enabled()) {
            player.sendMessage(plugin.text().prefixed("<red>星骸之海沒有開放"));
        } else if (!life.canEnter(player)) {
            player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(life.config().name()) + "還沒有開放，敬請期待"));
        } else if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色"));
        } else {
            new LifeMenu(plugin, player, life).open();
        }
    }
}
