package tw.exilecore.map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 玩家指令 {@code /atlas}（星圖）、{@code /atlas tree}（星圖天賦），管理指令 {@code /ec map ...}：
 * <pre>
 * /ec map give &lt;地圖&gt; [稀有度] [階級]   給自己一張地圖（normal／magic／rare，T1–T16，預設 T1）
 * /ec map open &lt;地圖|final&gt; [階級]    不用物品、不看星圖直接開一張（沒有詞綴，預設 T1）
 * /ec map list                     開著的地圖實例
 * /ec map tp &lt;編號&gt;                直接進入（不扣次數）
 * /ec map close &lt;編號|all&gt;          關閉實例（裡面的人送回營地、清格子）
 * /ec map complete &lt;地圖|all&gt;       把自己的星圖標成完成（測試終局用）
 * /ec map reset                    清掉自己的星圖進度（含最高完成階級、打贏過的極境、星圖天賦）
 * /ec map best &lt;階級&gt;              設定自己完成過的最高階（終局能開到這一階；0 = 清掉）
 * /ec map fragments [數量]          給自己天穹碎片
 * /ec map uber &lt;終局&gt;              直接開那個終局的極境（不用鑰匙、不用先打贏）
 * /ec map cores [數量]              給自己開極境的鑰匙（深淵星核）
 * </pre>
 */
public final class MapCommand {

    private MapCommand() {
    }

    /** /atlas（星圖）、/atlas tree（星圖天賦） */
    public static LiteralCommandNode<CommandSourceStack> atlas(final ExileCore plugin) {
        return Commands.literal("atlas")
                .executes(context -> openAtlas(plugin, context, false))
                .then(Commands.literal("tree").executes(context -> openAtlas(plugin, context, true)))
                .build();
    }

    private static int openAtlas(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final boolean tree) {
        final Player player = player(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.maps() == null) {
            player.sendMessage(plugin.text().prefixed("<red>地圖模組沒有啟用（看主控台的錯誤訊息）"));
        } else if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
        } else if (tree) {
            new AtlasTreeMenu(plugin, player, plugin.maps()).open();
        } else {
            new AtlasMenu(plugin, player, plugin.maps()).open();
        }
        return Command.SINGLE_SUCCESS;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("map")
                .executes(context -> list(plugin, context.getSource().getSender()))
                .then(Commands.literal("give")
                        .then(Commands.argument("map", StringArgumentType.word()).suggests(maps(plugin, false, false))
                                .executes(context -> give(plugin, context, "normal", 1))
                                .then(Commands.argument("rarity", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            for (final String option : List.of("normal", "magic", "rare")) {
                                                builder.suggest(option);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> give(plugin, context, StringArgumentType.getString(context, "rarity"), 1))
                                        .then(Commands.argument("tier", IntegerArgumentType.integer(1, MapConfig.MAX_TIERS))
                                                .executes(context -> give(plugin, context, StringArgumentType.getString(context, "rarity"),
                                                        IntegerArgumentType.getInteger(context, "tier")))))))
                .then(Commands.literal("open")
                        .then(Commands.argument("map", StringArgumentType.word()).suggests(maps(plugin, true, false))
                                .executes(context -> open(plugin, context, 1))
                                .then(Commands.argument("tier", IntegerArgumentType.integer(1, MapConfig.MAX_TIERS))
                                        .executes(context -> open(plugin, context, IntegerArgumentType.getInteger(context, "tier"))))))
                .then(Commands.literal("best")
                        .then(Commands.argument("tier", IntegerArgumentType.integer(0, MapConfig.MAX_TIERS))
                                .executes(context -> best(plugin, context, IntegerArgumentType.getInteger(context, "tier")))))
                .then(Commands.literal("list").executes(context -> list(plugin, context.getSource().getSender())))
                .then(Commands.literal("tp")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> teleport(plugin, context))))
                .then(Commands.literal("close")
                        .then(Commands.literal("all").executes(context -> close(plugin, context.getSource().getSender(), -1)))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> close(plugin, context.getSource().getSender(), IntegerArgumentType.getInteger(context, "id")))))
                .then(Commands.literal("complete")
                        .then(Commands.argument("map", StringArgumentType.word()).suggests(maps(plugin, true, true))
                                .executes(context -> complete(plugin, context, true))))
                .then(Commands.literal("reset").executes(context -> complete(plugin, context, false)))
                .then(Commands.literal("fragments")
                        .executes(context -> fragments(plugin, context, 2))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(context -> fragments(plugin, context, IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("uber")
                        .then(Commands.argument("map", StringArgumentType.word()).suggests(ubers(plugin))
                                .executes(context -> uber(plugin, context))))
                .then(Commands.literal("cores")
                        .executes(context -> cores(plugin, context, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(context -> cores(plugin, context, IntegerArgumentType.getInteger(context, "amount")))));
    }

    /** 有極境的終局代號。 */
    private static SuggestionProvider<CommandSourceStack> ubers(final ExileCore plugin) {
        return (context, builder) -> {
            if (plugin.maps() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final MapConfig.MapDef map : plugin.maps().config().finals()) {
                    if (plugin.maps().config().uber(map.id()) != null && map.id().startsWith(remaining)) {
                        builder.suggest(map.id());
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static int uber(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        final MapConfig.MapDef map = service.config().find(StringArgumentType.getString(context, "map"));
        if (map == null || !map.isFinal()) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這個終局"));
            return Command.SINGLE_SUCCESS;
        }
        final String problem = service.adminOpenUber(player, map);
        player.sendMessage(plugin.text().prefixed(problem == null ? "<gray>開了 " + ItemRenderer.escape(map.name()) + " 的極境"
                + "<dark_gray>（走地圖裝置後面的門進去；每個人只能進一次，/ec map tp 不受限制）" : "<red>" + ItemRenderer.escape(problem)));
        return Command.SINGLE_SUCCESS;
    }

    /** 給自己開極境的鑰匙（第一個有極境的終局用的那種）。 */
    private static int cores(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        String key = null;
        for (final MapConfig.MapDef map : service.config().finals()) {
            final MapConfig.Uber uber = service.config().uber(map.id());
            if (uber != null) {
                key = uber.key();
                break;
            }
        }
        final CurrencyType type = key == null ? null : plugin.currencies().repository().get(key);
        if (type == null) {
            player.sendMessage(plugin.text().prefixed("<red>沒有極境，或開極境的通貨不存在（maps.yml 的 uber.key）"));
            return Command.SINGLE_SUCCESS;
        }
        final org.bukkit.inventory.ItemStack stack = plugin.currencies().create(type, amount);
        plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.ADMIN, stack.getAmount());
        player.getInventory().addItem(stack).values()
                .forEach(rest -> plugin.personalDrops().dropForever(player, rest));
        player.sendMessage(plugin.text().prefixed("<gray>給了 " + amount + " 個" + ItemRenderer.escape(type.name())));
        return Command.SINGLE_SUCCESS;
    }

    private static SuggestionProvider<CommandSourceStack> maps(final ExileCore plugin, final boolean withFinal, final boolean withAll) {
        return (context, builder) -> {
            if (plugin.maps() != null) {
                final String remaining = builder.getRemainingLowerCase();
                final List<String> options = new ArrayList<>();
                for (final MapConfig.MapDef map : plugin.maps().config().all()) {
                    if (!map.isFinal() || withFinal) {
                        options.add(map.id());
                    }
                }
                if (withAll) {
                    options.add("all");
                }
                for (final String option : options) {
                    if (option.startsWith(remaining)) {
                        builder.suggest(option);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static Player player(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Entity executor = context.getSource().getExecutor();
        if (executor instanceof Player player) {
            return player;
        }
        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
        return null;
    }

    private static MapService service(final ExileCore plugin, final CommandSender sender) {
        if (plugin.maps() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>地圖模組沒有啟用（看主控台的錯誤訊息）"));
        }
        return plugin.maps();
    }

    private static int give(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String rarityText, final int tier) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        final MapConfig.MapDef map = service.config().find(StringArgumentType.getString(context, "map"));
        if (map == null || map.isFinal()) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這張地圖"));
            return Command.SINGLE_SUCCESS;
        }
        final Rarity rarity = Rarity.parse(rarityText, Rarity.NORMAL);
        final ItemData data = MapDrops.create(service.config(), plugin.items().generator(), map,
                rarity == Rarity.UNIQUE ? Rarity.RARE : rarity, tier, plugin.items().random());
        if (data == null) {
            player.sendMessage(plugin.text().prefixed("<red>基底 " + map.base() + " 不存在"));
            return Command.SINGLE_SUCCESS;
        }
        player.getInventory().addItem(plugin.items().toStack(data)).values()
                .forEach(rest -> plugin.personalDrops().dropForever(player, rest));
        player.sendMessage(plugin.text().prefixed("<gray>給了一張 <white>" + ItemRenderer.escape(map.name() + " T" + data.mapTier())));
        return Command.SINGLE_SUCCESS;
    }

    private static int best(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int tier) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色"));
            return Command.SINGLE_SUCCESS;
        }
        service.setBest(player, tier);
        player.sendMessage(plugin.text().prefixed(tier <= 0 ? "<gray>最高完成階級清掉了"
                : "<gray>最高完成階級設成 <white>T" + Math.min(tier, service.config().tiers().max()) + "<gray>（終局能開到這一階）"));
        return Command.SINGLE_SUCCESS;
    }

    private static int open(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int tier) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        final MapConfig.MapDef map = service.config().find(StringArgumentType.getString(context, "map"));
        if (map == null) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這張地圖"));
            return Command.SINGLE_SUCCESS;
        }
        final MapStats stats = MapStats.forTier(service.config().tiers(), tier, map.isFinal() ? service.config().finalLevelBonus(map.id()) : 0);
        final String problem = service.adminOpen(player, map, stats);
        player.sendMessage(plugin.text().prefixed(problem == null ? "<gray>開了 " + ItemRenderer.escape(map.name() + " T" + stats.tier())
                + "（怪物等級 " + stats.monsterLevel() + "）" : "<red>" + ItemRenderer.escape(problem)));
        return Command.SINGLE_SUCCESS;
    }

    private static int list(final ExileCore plugin, final CommandSender sender) {
        final MapService service = service(plugin, sender);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (service.instances().isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<gray>沒有開著的地圖；/ec map give 地圖 給自己一張，拿去營地的地圖裝置開"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>開著的地圖：" + service.instances().size() + " 個"));
        for (final MapInstance instance : service.instances()) {
            final int group = plugin.party() == null ? 1 : plugin.party().groupMembers(instance.owner()).size();
            sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT,
                    "<gray>  #%d <white>%s <gray>%s 開的%s・剩 %d 次・裡面 %d 人・擊殺 %d・清了 %d%%%s",
                    instance.id(), ItemRenderer.escape(instance.label()), ItemRenderer.escape(instance.ownerName()),
                    group > 1 ? "（隊伍 " + group + " 人）" : "", instance.portalsLeft(), instance.inside().size(),
                    instance.kills(), Math.round(instance.explored() * 100),
                    instance.state() == MapInstance.State.BUILDING ? "・成形中" : instance.bossKilled() ? "・<#f5b942>已完成" : "")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int teleport(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        final MapInstance instance = service.instance(IntegerArgumentType.getInteger(context, "id"));
        if (instance == null || instance.state() != MapInstance.State.OPEN) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這個開著的實例（/ec map list）"));
            return Command.SINGLE_SUCCESS;
        }
        service.adminEnter(player, instance);
        return Command.SINGLE_SUCCESS;
    }

    private static int close(final ExileCore plugin, final CommandSender sender, final int id) {
        final MapService service = service(plugin, sender);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        int closed = 0;
        for (final MapInstance instance : List.copyOf(service.instances())) {
            if (id < 0 || instance.id() == id) {
                service.close(instance, "管理員關閉");
                closed++;
            }
        }
        sender.sendMessage(plugin.text().prefixed("<gray>關閉了 " + closed + " 個地圖實例"));
        return Command.SINGLE_SUCCESS;
    }

    private static int complete(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final boolean complete) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色"));
            return Command.SINGLE_SUCCESS;
        }
        final List<MapConfig.MapDef> targets = new ArrayList<>();
        if (!complete) {
            targets.addAll(service.config().all());
        } else {
            final String id = StringArgumentType.getString(context, "map");
            if (id.equals("all")) {
                targets.addAll(service.config().maps());
            } else {
                final MapConfig.MapDef map = service.config().find(id);
                if (map == null) {
                    player.sendMessage(plugin.text().prefixed("<red>沒有這張地圖"));
                    return Command.SINGLE_SUCCESS;
                }
                targets.add(map);
            }
        }
        for (final MapConfig.MapDef map : targets) {
            service.setCompleted(player, map, complete);
        }
        if (!complete) {
            service.setBest(player, 0);
            service.resetUber(player); // 極境首殺的紀錄也清掉（可以重測）
            service.resetAtlas(player); // 星圖天賦也退掉（天賦點跟著星圖歸零）
        }
        player.sendMessage(plugin.text().prefixed(complete ? "<gray>星圖標成完成：" + targets.size() + " 張" : "<gray>星圖進度清掉了"));
        return Command.SINGLE_SUCCESS;
    }

    private static int fragments(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = player(plugin, context);
        final MapService service = player == null ? null : service(plugin, player);
        if (service == null) {
            return Command.SINGLE_SUCCESS;
        }
        final CurrencyType type = plugin.currencies().repository().get(service.config().drops().fragment());
        if (type == null) {
            player.sendMessage(plugin.text().prefixed("<red>currencies.yml 沒有 " + service.config().drops().fragment()));
            return Command.SINGLE_SUCCESS;
        }
        final org.bukkit.inventory.ItemStack stack = plugin.currencies().create(type, amount);
        plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.ADMIN, stack.getAmount());
        player.getInventory().addItem(stack).values()
                .forEach(rest -> plugin.personalDrops().dropForever(player, rest));
        player.sendMessage(plugin.text().prefixed("<gray>給了 " + amount + " 片" + ItemRenderer.escape(type.name())));
        return Command.SINGLE_SUCCESS;
    }
}
