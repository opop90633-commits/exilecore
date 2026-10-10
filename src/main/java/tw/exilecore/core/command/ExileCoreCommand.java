package tw.exilecore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.balance.BalanceCommand;
import tw.exilecore.map.MapCommand;
import tw.exilecore.ops.OpsModule;
import tw.exilecore.world.WorldCommand;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.npc.NpcType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.flask.FlaskCommands;
import tw.exilecore.skill.SkillCommands;
import tw.exilecore.zone.ZoneAdminCommand;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /ec} 管理指令（別名 {@code /exilecore}）。
 * <pre>
 * /ec help [分類|all]               所有指令與功能（分類；見 {@link CommandHelp}）
 * /ec ping                          伺服器是否活著、TPS 與 MSPT
 * /ec version                       插件、Paper、Java 版本與已啟用模組
 * /ec db                            資料庫連線狀態
 * /ec reload                        重新載入 config.yml 與資料檔
 * /ec data [status|diff 檔|accept 檔]  資料檔體檢：你改過的檔案缺了新版的哪些鍵、把 .new 換上來（見 {@link DataCommand}）
 * /ec give &lt;基底&gt; [稀有度] [物品等級]   給自己一件裝備
 * /ec giveunique &lt;傳奇&gt; [物品等級]    給自己一件傳奇（測試特殊效果）
 * /ec givecurrency &lt;通貨&gt; [數量]      給自己通貨
 * /ec identify                      鑑定手上的物品
 * /ec affix &lt;詞綴&gt; [階級代號]          把一條詞綴加到手上的物品（測試用；同群組的換掉，沒寫階級 = 最強的一階）
 * /ec item                          印出手上物品的資料
 * /ec level &lt;等級&gt;                  設定目前角色的等級（經驗歸零）
 * /ec xp &lt;經驗&gt;                     給目前角色經驗（會升級）
 * /ec points &lt;點數&gt;                 給目前角色額外天賦點
 * /ec refund &lt;次數&gt;                 給目前角色免費退點次數
 * /ec gold &lt;金幣&gt;                   給自己的帳號金幣（負數扣；所有角色共用）
 * /ec resetpassives                 把目前角色的天賦全部退掉
 * /ec save                          立刻存檔（所有在線玩家）
 * /ec stats [名字]                  看自己（或某個在線玩家）的屬性表
 * /ec spawn &lt;生物&gt; &lt;等級&gt; [稀有度]    在面前生成一隻等級化的怪物
 * /ec debug                         切換命中除錯訊息（含經驗）
 * /ec npc spawn &lt;種類&gt;              在面前放一個 NPC（identifier 鑑定師、stash 倉庫、salvage 拆解、waypoint 傳送、map_device 地圖裝置、vendor 商人、market 市集掌櫃）
 * /ec npc remove                    移除身邊 5 格內最近的 NPC
 * /ec npc list                      列出目前載入中的 NPC
 * /ec npc story [代號]              列出劇情人物；在面前放一個（劇情世界會自己擺，手動擺版面的伺服器用這個）
 * /ec quest list|start|stage|complete|reset   任務（見 {@link tw.exilecore.quest.QuestCommand}）
 * /ec bounty [reroll|complete|weekly|merits]  每日委託、每週回響、功勳（見 {@link tw.exilecore.bounty.BountyCommand}）
 * /ec ascend [trial|untrial|reset|wipe]       試煉、昇華（見 {@link tw.exilecore.ascendancy.AscendancyCommand}）
 * /ec zone ...                      擺放區域、檢查點、Boss 場地（見 {@link ZoneAdminCommand}）
 * /ec skill ...                     技能面板的測試指令（見 {@link SkillCommands}）
 * /ec gem ...                       給自己寶石：未切割、技能、輔助（見 {@link SkillCommands}）
 * /ec flask ...                     給自己藥劑、補滿藥劑（見 {@link FlaskCommands}）
 * /ec balance [等級]                平衡報表：參考角色的輸出、擊殺秒數、被打一下扣幾 %、建議的錨點（見 {@link BalanceCommand}）
 * /ec world ...                     劇情世界：狀態、傳送到營地、重擺版面與 NPC（見 {@link WorldCommand}）
 * /ec map ...                       地圖：給地圖、直接開、列出／進入／關閉實例、星圖進度、天穹碎片（見 {@link MapCommand}）
 * /ec perf                          效能：TPS、各部分花多少時間、資料庫排隊、實體數
 * /ec economy [天數|flow|players|values|update] 交易系統狀態與經濟報表（見 {@link tw.exilecore.economy.EconomyCommand}）
 * /ec trace &lt;物品UUID&gt;             一件裝備的交易與通貨紀錄；/ec trace player &lt;名字&gt; 一個人最近的交易
 * /ec dupes                         找同一個 UUID 出現在兩個地方的裝備
 * /ec backup [list|restore 檔名]     馬上備份、列出備份、還原（還原只能在主控台）
 * /ec wipe [all|confirm 碼|cancel]  清檔（只能在主控台，見 {@link tw.exilecore.ops.WipeService}）
 * /ec web [logout]                  管理網頁的一次性登入連結（發放物資、改設定、公告、維護模式）；logout 全部登出
 * </pre>
 */
public final class ExileCoreCommand {

    private ExileCoreCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(final ExileCore plugin) {
        final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ec")
                .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                .executes(context -> help(plugin, context.getSource().getSender()))
                .then(Commands.literal("help")
                        .executes(context -> help(plugin, context.getSource().getSender()))
                        .then(Commands.argument("topic", StringArgumentType.word())
                                .suggests(suggest(CommandHelp::ids))
                                .executes(context -> {
                                    CommandHelp.show(plugin, context.getSource().getSender(), StringArgumentType.getString(context, "topic"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("ping").executes(context -> ping(plugin, context.getSource().getSender())))
                .then(Commands.literal("version").executes(context -> version(plugin, context.getSource().getSender())))
                .then(Commands.literal("db").executes(context -> db(plugin, context.getSource().getSender())))
                .then(Commands.literal("reload").executes(context -> reload(plugin, context.getSource().getSender())))
                .then(DataCommand.node(plugin))
                .then(Commands.literal("give")
                        .then(Commands.argument("base", StringArgumentType.word())
                                .suggests(suggest(() -> plugin.items().repository().bases().stream().map(ItemBase::id).toList()))
                                .executes(context -> give(plugin, context, null, 0))
                                .then(Commands.argument("rarity", StringArgumentType.word())
                                        .suggests(suggest(() -> java.util.List.of("normal", "magic", "rare")))
                                        .executes(context -> give(plugin, context, StringArgumentType.getString(context, "rarity"), 0))
                                        .then(Commands.argument("ilvl", IntegerArgumentType.integer(1, 100))
                                                .executes(context -> give(plugin, context,
                                                        StringArgumentType.getString(context, "rarity"),
                                                        IntegerArgumentType.getInteger(context, "ilvl")))))))
                .then(Commands.literal("giveunique")
                        .then(Commands.argument("unique", StringArgumentType.word())
                                .suggests(suggest(() -> plugin.items().repository().uniques().stream().map(tw.exilecore.item.UniqueItem::id).toList()))
                                .executes(context -> giveUnique(plugin, context, 0))
                                .then(Commands.argument("ilvl", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> giveUnique(plugin, context, IntegerArgumentType.getInteger(context, "ilvl"))))))
                .then(Commands.literal("givecurrency")
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(suggest(() -> plugin.currencies().repository().all().stream().map(CurrencyType::id).toList()))
                                .executes(context -> giveCurrency(plugin, context, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 99))
                                        .executes(context -> giveCurrency(plugin, context, IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("identify").executes(context -> identify(plugin, context)))
                .then(Commands.literal("affix")
                        .then(Commands.argument("affix", StringArgumentType.word())
                                .suggests(suggest(() -> plugin.items().repository().affixes().stream()
                                        .filter(affix -> !affix.spawnTags().contains(tw.exilecore.item.UniqueItem.SPAWN_TAG))
                                        .map(tw.exilecore.item.Affix::id).sorted().toList()))
                                .executes(context -> forceAffix(plugin, context, null))
                                .then(Commands.argument("tier", IntegerArgumentType.integer(-9, 99))
                                        .executes(context -> forceAffix(plugin, context, IntegerArgumentType.getInteger(context, "tier"))))))
                .then(Commands.literal("item").executes(context -> item(plugin, context)))
                .then(Commands.literal("level")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(context -> level(plugin, context, IntegerArgumentType.getInteger(context, "level")))
                                .then(Commands.argument("target", StringArgumentType.word()).suggests(ExileCoreCommand::onlineNames)
                                        .executes(context -> levelOther(plugin, context, IntegerArgumentType.getInteger(context, "level"), false)))))
                .then(Commands.literal("addlevel") // 2026-10：給別人升幾級（/ec addlevel 級數 玩家）
                        .then(Commands.argument("levels", IntegerArgumentType.integer(1, 99))
                                .then(Commands.argument("target", StringArgumentType.word()).suggests(ExileCoreCommand::onlineNames)
                                        .executes(context -> levelOther(plugin, context, IntegerArgumentType.getInteger(context, "levels"), true)))))
                .then(Commands.literal("xp")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 2_000_000_000))
                                .executes(context -> xp(plugin, context, IntegerArgumentType.getInteger(context, "amount")))
                                .then(Commands.argument("target", StringArgumentType.word()).suggests(ExileCoreCommand::onlineNames)
                                        .executes(context -> xpOther(plugin, context, IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("points")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(-500, 500))
                                .executes(context -> points(plugin, context, IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("refund")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 500))
                                .executes(context -> refund(plugin, context, IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("gold")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(-1_000_000_000, 1_000_000_000))
                                .executes(context -> gold(plugin, context, IntegerArgumentType.getInteger(context, "amount")))))
                .then(Commands.literal("resetpassives").executes(context -> resetPassives(plugin, context)))
                .then(Commands.literal("setclass")
                        .then(Commands.argument("class", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (final tw.exilecore.character.CharacterClass value : tw.exilecore.character.CharacterClass.values()) {
                                        builder.suggest(value.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> setClass(plugin, context, StringArgumentType.getString(context, "class"), null))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(tw.exilecore.chat.ChatModule.targets(plugin))
                                        .executes(context -> setClass(plugin, context, StringArgumentType.getString(context, "class"),
                                                StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("save").executes(context -> save(plugin, context.getSource().getSender())))
                .then(Commands.literal("stats")
                        .executes(context -> stats(plugin, context, null))
                        .then(Commands.argument("player", StringArgumentType.greedyString())
                                .suggests(tw.exilecore.chat.ChatModule.targets(plugin))
                                .executes(context -> stats(plugin, context, StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("dummywatch").executes(context -> {
                    // 2026-10：管理員觀看打靶（別人打完靶的統計也送一份給你）
                    if (!(context.getSource().getSender() instanceof org.bukkit.entity.Player admin) || plugin.training() == null) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>要在遊戲裡用"));
                        return Command.SINGLE_SUCCESS;
                    }
                    final boolean on = plugin.training().toggleWatch(admin);
                    admin.sendMessage(plugin.text().prefixed(on ? "<green>打靶觀看：開（別人打完靶的統計會送給你）" : "<gray>打靶觀看：關"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("refreshitems").executes(context -> {
                    tw.exilecore.ops.ItemRefresher.run(plugin, context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("worldevent")
                        .executes(context -> worldEvent(plugin, context, "status", null))
                        .then(Commands.literal("here").executes(context -> worldEvent(plugin, context, "here", null)))
                        .then(Commands.literal("arena")
                                .executes(context -> worldEvent(plugin, context, "arena", "45"))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(20, 80))
                                        .executes(context -> worldEvent(plugin, context, "arena",
                                                String.valueOf(IntegerArgumentType.getInteger(context, "radius"))))))
                        .then(Commands.literal("clearspots").executes(context -> worldEvent(plugin, context, "clearspots", null)))
                        .then(Commands.literal("stop").executes(context -> worldEvent(plugin, context, "stop", null)))
                        .then(Commands.literal("start")
                                .then(Commands.literal("boss").executes(context -> worldEvent(plugin, context, "start", "boss")))
                                .then(Commands.literal("invasion").executes(context -> worldEvent(plugin, context, "start", "invasion")))))
                .then(Commands.literal("inv")
                        .then(Commands.argument("player", StringArgumentType.greedyString())
                                .suggests(tw.exilecore.chat.ChatModule.targets(plugin))
                                .executes(context -> inspect(plugin, context, StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(suggest(() -> java.util.List.of("zombie", "skeleton", "spider", "creeper", "husk", "stray", "drowned", "witch", "enderman")))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> spawn(plugin, context, null))
                                        .then(Commands.argument("rarity", StringArgumentType.word())
                                                .suggests(suggest(() -> java.util.List.of("normal", "magic", "rare")))
                                                .executes(context -> spawn(plugin, context, StringArgumentType.getString(context, "rarity")))))))
                .then(Commands.literal("debug").executes(context -> debug(plugin, context)))
                .then(Commands.literal("npc")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(suggest(() -> java.util.Arrays.stream(NpcType.values()).filter(NpcType::functional)
                                                .map(NpcType::id).toList()))
                                        .executes(context -> npcSpawn(plugin, context, StringArgumentType.getString(context, "type")))))
                        .then(Commands.literal("story")
                                .executes(context -> npcStoryList(plugin, context.getSource().getSender()))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(suggest(() -> plugin.quests() == null ? java.util.List.<String>of()
                                                : java.util.List.copyOf(plugin.quests().config().npcs().keySet())))
                                        .executes(context -> npcStory(plugin, context, StringArgumentType.getString(context, "id")))))
                        .then(Commands.literal("remove").executes(context -> npcRemove(plugin, context)))
                        .then(Commands.literal("list").executes(context -> npcList(plugin, context.getSource().getSender()))))
                .then(ZoneAdminCommand.node(plugin))
                .then(SkillCommands.adminNode(plugin))
                .then(SkillCommands.gemNode(plugin))
                .then(FlaskCommands.node(plugin))
                .then(BalanceCommand.node(plugin))
                .then(WorldCommand.node(plugin))
                .then(MapCommand.node(plugin))
                .then(tw.exilecore.quest.QuestCommand.adminNode(plugin))
                .then(tw.exilecore.bounty.BountyCommand.adminNode(plugin))
                .then(tw.exilecore.ascendancy.AscendancyCommand.adminNode(plugin))
                .then(tw.exilecore.pvp.PvpModule.adminNode(plugin))
                .then(tw.exilecore.admin.AdminModule.node(plugin))
                .then(tw.exilecore.admin.AdminModule.restartNode(plugin))
                .then(tw.exilecore.sponsor.SponsorModule.adminNode(plugin));
        for (final com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node : OpsModule.nodes(plugin)) {
            root.then(node); // perf、economy、dupes、trace、backup
        }
        return root.build();
    }

    private static SuggestionProvider<CommandSourceStack> suggest(final Supplier<java.util.List<String>> options) {
        return (context, builder) -> {
            final String remaining = builder.getRemainingLowerCase();
            for (final String option : options.get()) {
                if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };
    }

    private static Player playerOrNull(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Entity executor = context.getSource().getExecutor();
        if (executor instanceof Player player) {
            return player;
        }
        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
        return null;
    }

    // ---- 基本 ----

    private static int help(final ExileCore plugin, final CommandSender sender) {
        CommandHelp.show(plugin, sender, null); // 2026-10：所有指令的一覽（分類）在 CommandHelp
        return Command.SINGLE_SUCCESS;
    }

    private static int ping(final ExileCore plugin, final CommandSender sender) {
        final double tps = plugin.getServer().getTPS()[0];
        final double mspt = plugin.getServer().getAverageTickTime();
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<green>pong</green> <gray>TPS <white>%.1f</white>，每 tick <white>%.1f</white> 毫秒</gray>", tps, mspt)));
        return Command.SINGLE_SUCCESS;
    }

    private static int version(final ExileCore plugin, final CommandSender sender) {
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>ExileCore <white>%s</white>　Paper <white>%s</white>　Java <white>%s</white></gray>",
                plugin.getPluginMeta().getVersion(), plugin.getServer().getMinecraftVersion(), Runtime.version().feature())));
        sender.sendMessage(plugin.text().prefixed("<gray>已啟用模組：<white>" + String.join(", ", plugin.modules().enabledIds()) + "</white></gray>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int db(final ExileCore plugin, final CommandSender sender) {
        if (!plugin.database().isConnected()) {
            sender.sendMessage(plugin.text().prefixed("<red>資料庫未連線</red> <gray>請看主控台的錯誤訊息，或檢查 config.yml 的 storage 區塊</gray>"));
            return Command.SINGLE_SUCCESS;
        }
        final long millis = plugin.database().ping();
        sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>%s，來回 <white>%d</white> 毫秒</gray>", plugin.database().description(), millis)));
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(final ExileCore plugin, final CommandSender sender) {
        plugin.reload();
        sender.sendMessage(plugin.text().prefixed("<green>設定與資料檔已重新載入</green>"));
        return Command.SINGLE_SUCCESS;
    }

    // ---- 物品 ----

    private static int give(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String rarityText, final int ilvlArg) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String baseId = StringArgumentType.getString(context, "base");
        final ItemBase base = plugin.items().repository().base(baseId);
        if (base == null) {
            player.sendMessage(plugin.text().prefixed("<red>找不到基底 " + baseId));
            return Command.SINGLE_SUCCESS;
        }
        final Rarity rarity = Rarity.parse(rarityText, Rarity.NORMAL);
        final int ilvl = ilvlArg > 0 ? ilvlArg : plugin.players().get(player).level();
        final ItemStack stack = plugin.items().create(base, rarity, ilvl);
        giveOrDrop(plugin, player, stack);
        player.sendMessage(plugin.text().prefixed("<gray>給你一件 <white>" + rarity.displayName() + " " + base.name() + "</white>（物品等級 " + ilvl + "）"));
        return Command.SINGLE_SUCCESS;
    }

    /** 測試用：直接給一件傳奇（數值在範圍裡擲；物品等級沒寫 = 你的等級，但至少是這件傳奇的 drop_level）。 */
    private static int giveUnique(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int ilvlArg) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String id = StringArgumentType.getString(context, "unique");
        final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(id);
        if (unique == null || plugin.items().repository().base(unique.base()) == null) {
            player.sendMessage(plugin.text().prefixed("<red>找不到傳奇 " + id));
            return Command.SINGLE_SUCCESS;
        }
        final int ilvl = ilvlArg > 0 ? ilvlArg : Math.max(unique.dropLevel(), plugin.players().get(player).level());
        final tw.exilecore.item.ItemData data = plugin.items().generator().generateUnique(unique, ilvl, plugin.items().random());
        giveOrDrop(plugin, player, plugin.items().toStack(data));
        player.sendMessage(plugin.text().prefixed("<gray>給你一件 <#af6025>" + unique.name() + "</#af6025>（物品等級 " + ilvl + "）"));
        return Command.SINGLE_SUCCESS;
    }

    private static int giveCurrency(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String id = StringArgumentType.getString(context, "currency");
        final CurrencyType type = plugin.currencies().repository().get(id);
        if (type == null) {
            player.sendMessage(plugin.text().prefixed("<red>找不到通貨 " + id));
            return Command.SINGLE_SUCCESS;
        }
        int remaining = amount;
        while (remaining > 0) {
            final int batch = Math.min(remaining, type.stack());
            giveOrDrop(plugin, player, plugin.currencies().create(type, batch));
            remaining -= batch;
        }
        plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.ADMIN, amount);
        player.sendMessage(plugin.text().prefixed("<gray>給你 <white>" + amount + " 個 " + type.name() + "</white>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int identify(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final ItemStack stack = held(plugin, player);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>手上不是 ExileCore 的裝備"));
            return Command.SINGLE_SUCCESS;
        }
        data.get().setIdentified(true);
        plugin.items().update(stack, data.get());
        plugin.players().markDirty(player);
        plugin.characters().gear().sync(player);
        player.sendMessage(plugin.text().prefixed("<green>已鑑定"));
        return Command.SINGLE_SUCCESS;
    }

    /** /ec affix：把一條詞綴加到手上的物品（測試新詞綴用）。 */
    private static int forceAffix(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Integer tier) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final ItemStack stack = held(plugin, player);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>手上不是 ExileCore 的裝備"));
            return Command.SINGLE_SUCCESS;
        }
        final String id = StringArgumentType.getString(context, "affix");
        final String problem = plugin.items().generator().forceAffix(data.get(), id, tier, plugin.items().random());
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(problem)));
            return Command.SINGLE_SUCCESS;
        }
        plugin.items().update(stack, data.get());
        plugin.players().markDirty(player);
        plugin.characters().gear().sync(player);
        player.sendMessage(plugin.text().prefixed("<green>加上了 <white>" + tw.exilecore.item.ItemRenderer.escape(id)
                + "<gray>（滑到物品上看；同群組的舊詞綴已換掉）"));
        return Command.SINGLE_SUCCESS;
    }

    /** 手上的物品；手上是角色面板裝備的顯示複本時，改用裝在面板上的武器。 */
    private static ItemStack held(final ExileCore plugin, final Player player) {
        final ItemStack stack = player.getInventory().getItemInMainHand();
        if (tw.exilecore.character.Gear.isDisplay(stack)) {
            final ItemStack real = tw.exilecore.character.Gear.mainHand(plugin.players().get(player));
            return real == null ? stack : real;
        }
        return stack;
    }

    private static int item(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final ItemStack stack = held(plugin, player);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>手上不是 ExileCore 的裝備"));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(plugin.text().prefixed("<gray>" + escape(plugin.items().toJson(data.get()))));
        final String uuid = data.get().uuid();
        if (uuid != null && uuid.matches("[0-9a-fA-F-]+")) {
            player.sendMessage(plugin.text().parse("<gray>UUID：<white><click:copy_to_clipboard:'" + uuid + "'><hover:show_text:'點一下複製'>"
                    + uuid + "</hover></click></white>　<#f5b942><click:run_command:'/ec trace " + uuid
                    + "'><hover:show_text:'這件裝備的交易與通貨紀錄'>[追蹤]</hover></click>"));
        }
        for (final Modifier modifier : plugin.items().modifiers(stack)) {
            player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<dark_gray>  %s %s %.3f %s",
                    modifier.stat(), modifier.type(), modifier.value(), modifier.tags())));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---- 角色 ----

    private static int level(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int level) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.players().setLevel(player, level);
        player.sendMessage(plugin.text().prefixed("<gray>等級設為 <white>" + plugin.players().get(player).level() + "<gray>，經驗歸零"));
        return Command.SINGLE_SUCCESS;
    }

    private static int xp(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        final int gained = plugin.players().awardExperience(player, amount);
        final PlayerState state = plugin.players().get(player);
        player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>經驗 +%,d，升了 %d 級，現在 Lv.%d（%.1f%%）",
                amount, gained, state.level(), plugin.players().experienceProgress(state) * 100)));
        return Command.SINGLE_SUCCESS;
    }

    /** 線上玩家名字（指令的補全）。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> onlineNames(
            final CommandContext<CommandSourceStack> context, final com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (final Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(online.getName());
            }
        }
        return builder.buildFuture();
    }

    /** 指令裡指定的玩家（要在線、有角色）；不行就跟下指令的人說，回傳 null。 */
    private static Player targetOrNull(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final org.bukkit.command.CommandSender sender = context.getSource().getSender();
        final String name = StringArgumentType.getString(context, "target");
        final Player target = org.bukkit.Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到在線的玩家 " + tw.exilecore.item.ItemRenderer.escape(name)));
            return null;
        }
        if (!plugin.players().get(target).hasCharacter()) {
            sender.sendMessage(plugin.text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(target.getName()) + " 還沒選角色"));
            return null;
        }
        return target;
    }

    /** 2026-10：/ec level 等級 玩家（設成幾級）、/ec addlevel 級數 玩家（往上升幾級）。 */
    private static int levelOther(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int value, final boolean add) {
        final Player target = targetOrNull(plugin, context);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        final int before = plugin.players().get(target).level();
        plugin.players().setLevel(target, add ? before + value : value);
        final int after = plugin.players().get(target).level();
        context.getSource().getSender().sendMessage(plugin.text().prefixed("<gray>" + tw.exilecore.item.ItemRenderer.escape(target.getName())
                + " 的等級 <white>" + before + " → " + after + "<gray>（經驗歸零）"));
        if (!target.equals(context.getSource().getSender())) {
            target.sendMessage(plugin.text().prefixed("<gray>管理員把你的等級調成 <white>Lv." + after));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 2026-10：/ec xp 數量 玩家：給別人經驗（照一般的升級算）。 */
    private static int xpOther(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player target = targetOrNull(plugin, context);
        if (target == null) {
            return Command.SINGLE_SUCCESS;
        }
        final int gained = plugin.players().awardExperience(target, amount);
        final PlayerState state = plugin.players().get(target);
        context.getSource().getSender().sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>給 %s 經驗 +%,d，升了 %d 級，現在 Lv.%d（%.1f%%）", tw.exilecore.item.ItemRenderer.escape(target.getName()),
                amount, gained, state.level(), plugin.players().experienceProgress(state) * 100)));
        if (!target.equals(context.getSource().getSender())) {
            target.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>管理員給了你 <white>%,d<gray> 點經驗", amount)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int points(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        state.character().setBonusPoints(state.character().bonusPoints() + amount);
        player.sendMessage(plugin.text().prefixed("<gray>額外天賦點 " + (amount >= 0 ? "+" : "") + amount + "，可用 <white>" + plugin.players().availablePoints(state)));
        return Command.SINGLE_SUCCESS;
    }

    private static int refund(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        state.character().setRefundPoints(state.character().refundPoints() + amount);
        player.sendMessage(plugin.text().prefixed("<gray>免費退點次數 +" + amount + "，現在 <white>" + state.character().refundPoints()));
        return Command.SINGLE_SUCCESS;
    }

    private static int gold(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final int amount) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return Command.SINGLE_SUCCESS;
        }
        state.setGold(state.gold() + amount);
        plugin.economy().ledger().gold(tw.exilecore.economy.EconomyLedger.ADMIN, amount);
        player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>金幣 %s%,d，現在 <#f5b942>%,d<gray>（帳號共用）",
                amount >= 0 ? "+" : "", amount, state.gold())));
        return Command.SINGLE_SUCCESS;
    }

    private static int resetPassives(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        final int count = state.passives().size();
        state.passives().clear();
        state.markDirty();
        plugin.players().sheet(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<gray>退掉 " + count + " 個天賦節點，可用 <white>" + plugin.players().availablePoints(state)));
        return Command.SINGLE_SUCCESS;
    }

    private static int save(final ExileCore plugin, final CommandSender sender) {
        plugin.characters().saveAll();
        sender.sendMessage(plugin.text().prefixed("<gray>已排程存檔所有在線玩家的角色"));
        return Command.SINGLE_SUCCESS;
    }

    /** /ec stats [名字]：自己的或某個在線玩家的屬性表（後台也能看別人的）。 */
    /** /ec setclass 職業 [玩家]：換職業（天賦全部退回、昇華職業退掉）。 */
    private static int setClass(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String classText, final String name) {
        final CommandSender sender = context.getSource().getSender();
        final tw.exilecore.character.CharacterClass target = tw.exilecore.character.CharacterClass.parse(classText, null);
        if (target == null) {
            final java.util.List<String> ids = new java.util.ArrayList<>();
            for (final tw.exilecore.character.CharacterClass value : tw.exilecore.character.CharacterClass.values()) {
                ids.add(value.id() + "（" + value.displayName() + "）");
            }
            sender.sendMessage(plugin.text().prefixed("<red>沒有這個職業：" + tw.exilecore.item.ItemRenderer.escape(classText) + "<gray>（" + String.join("、", ids) + "）"));
            return Command.SINGLE_SUCCESS;
        }
        final Player player = name == null ? playerOrNull(plugin, context) : plugin.getServer().getPlayerExact(name);
        if (player == null) {
            if (name != null) {
                sender.sendMessage(plugin.text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(name) + " 不在線上（要在線才能換）"));
            }
            return Command.SINGLE_SUCCESS;
        }
        final String problem = plugin.characters().adminChangeClass(player, target);
        sender.sendMessage(plugin.text().prefixed(problem == null
                ? "<green>" + player.getName() + " 的職業換成 " + target.displayName() + "（天賦退回、昇華職業退掉）"
                : "<red>" + tw.exilecore.item.ItemRenderer.escape(problem)));
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String query) {
        tw.exilecore.character.CharacterCommand.showStats(plugin, context.getSource().getSender(), query);
        return Command.SINGLE_SUCCESS;
    }

    /** /ec worldevent（2026-10）：不定時活動的狀態、加地點、手動開始、結束。 */
    private static int worldEvent(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String action, final String kind) {
        final CommandSender sender = context.getSource().getSender();
        final tw.exilecore.worldevent.WorldEventService events = plugin.worldEvents();
        if (events == null) {
            sender.sendMessage(plugin.text().prefixed("<red>不定時活動模組沒有啟用（看主控台）"));
            return Command.SINGLE_SUCCESS;
        }
        switch (action) {
            case "here" -> {
                final Player player = playerOrNull(plugin, context);
                if (player != null) {
                    final String problem = events.addSpot(player.getLocation());
                    player.sendMessage(plugin.text().prefixed(problem == null ? "<green>加了一個事件地點（現在的位置）" : "<red>" + problem));
                }
            }
            case "arena" -> {
                final Player player = playerOrNull(plugin, context);
                if (player != null) {
                    final String problem = events.buildArena(player, player.getLocation(), Integer.parseInt(kind));
                    if (problem != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + problem));
                    }
                }
            }
            case "clearspots" -> {
                final String problem = events.clearSpots();
                sender.sendMessage(plugin.text().prefixed(problem == null ? "<green>清掉指令加的事件地點（world_events.yml 寫的不動）" : "<red>" + problem));
            }
            case "stop" -> {
                events.stop("管理員結束了事件");
                sender.sendMessage(plugin.text().prefixed("<green>結束了"));
            }
            case "start" -> {
                final Player player = context.getSource().getExecutor() instanceof Player p ? p : null;
                final String problem = events.start("boss".equals(kind) ? tw.exilecore.worldevent.WorldEventService.Kind.BOSS
                        : tw.exilecore.worldevent.WorldEventService.Kind.INVASION, null);
                sender.sendMessage(plugin.text().prefixed(problem == null ? "<green>開始了" : "<red>" + problem));
            }
            default -> sender.sendMessage(plugin.text().prefixed(events.running()
                    ? "<gray>進行中：" + events.runningKind().displayName() + "，還剩 " + events.secondsLeft() + " 秒"
                    : "<gray>沒有事件；下一次" + (events.nextClock() != null ? " " + events.nextClock() + "，" : "") + "大約 " + events.minutesUntilNext() + " 分鐘後。指令：arena [半徑]、here、clearspots、start boss|invasion、stop"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /ec inv 玩家（2026-10）：看玩家的背包、裝備、隨身背包，顯示並可以複製玩家與物品的 UUID。 */
    private static int inspect(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String query) {
        final Player admin = playerOrNull(plugin, context);
        if (admin == null) {
            return Command.SINGLE_SUCCESS;
        }
        final Player target = plugin.chat() != null ? plugin.chat().findOrTell(admin, query.trim()) : plugin.getServer().getPlayerExact(query.trim());
        if (target == null) {
            if (plugin.chat() == null) {
                admin.sendMessage(plugin.text().prefixed("<red>找不到在線的玩家「" + tw.exilecore.item.ItemRenderer.escape(query.trim()) + "」"));
            }
            return Command.SINGLE_SUCCESS;
        }
        new tw.exilecore.admin.InspectMenu(plugin, admin, target).open();
        return Command.SINGLE_SUCCESS;
    }

    private static int debug(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        state.setDebug(!state.debug());
        player.sendMessage(plugin.text().prefixed(state.debug() ? "<green>命中除錯訊息：開" : "<gray>命中除錯訊息：關"));
        return Command.SINGLE_SUCCESS;
    }

    // ---- 怪物 ----

    private static int spawn(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String rarityText) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final String typeName = StringArgumentType.getString(context, "type").toUpperCase(Locale.ROOT);
        final EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (final IllegalArgumentException e) {
            player.sendMessage(plugin.text().prefixed("<red>不認得的生物 " + typeName));
            return Command.SINGLE_SUCCESS;
        }
        final int level = IntegerArgumentType.getInteger(context, "level");
        final MonsterRarity rarity = MonsterRarity.parse(rarityText, MonsterRarity.NORMAL);
        final org.bukkit.util.Vector direction = player.getLocation().getDirection().setY(0);
        final Location location = direction.lengthSquared() < 0.01
                ? player.getLocation().add(3, 0, 0)
                : player.getLocation().add(direction.normalize().multiply(3));
        final Entity entity = player.getWorld().spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            player.sendMessage(plugin.text().prefixed("<red>" + typeName + " 不是生物"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.monsters().decorate(living, level, rarity, null);
        plugin.monsters().pin(living); // 營地的清怪不會清掉管理員生的
        player.sendMessage(plugin.text().prefixed("<gray>生成 <white>Lv." + level + " " + rarity.displayName() + " " + plugin.monsters().typeName(living)));
        return Command.SINGLE_SUCCESS;
    }

    // ---- NPC ----

    private static int npcSpawn(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String typeText) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final NpcType type = NpcType.parse(typeText);
        if (type == null) {
            player.sendMessage(plugin.text().prefixed("<red>不認得的 NPC 種類 " + escape(typeText)));
            return Command.SINGLE_SUCCESS;
        }
        if (type == NpcType.STORY) {
            player.sendMessage(plugin.text().prefixed("<red>劇情人物用 /ec npc story <代號>"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.npcs().spawn(inFront(player), type);
        player.sendMessage(plugin.text().prefixed("<gray>放了一個 <white>" + type.displayName() + "</white>（會跟著世界存檔；/ec npc remove 移除）"));
        return Command.SINGLE_SUCCESS;
    }

    /** 面前 2 格、面向你。 */
    private static Location inFront(final Player player) {
        final Location base = player.getLocation();
        final org.bukkit.util.Vector direction = base.getDirection().setY(0);
        final Location location = direction.lengthSquared() < 0.01
                ? base.clone().add(2, 0, 0)
                : base.clone().add(direction.normalize().multiply(2));
        location.setYaw(base.getYaw() + 180f);
        location.setPitch(0f);
        return location;
    }

    /** /ec npc story &lt;代號&gt;：在面前放一個劇情人物（手動擺版面的伺服器用；劇情世界會自己擺）。 */
    private static int npcStory(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final String id) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.quests() == null) {
            player.sendMessage(plugin.text().prefixed("<red>任務模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        final tw.exilecore.quest.QuestConfig.Npc definition = plugin.quests().config().npc(id);
        if (definition == null) {
            player.sendMessage(plugin.text().prefixed("<red>quests.yml 裡沒有劇情人物 " + escape(id) + "（/ec npc story 列出全部）"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.npcs().spawnStory(inFront(player), definition);
        player.sendMessage(plugin.text().prefixed("<gray>放了 <white>" + escape(definition.name()) + "</white>（會跟著世界存檔；/ec npc remove 移除）"
                + "<dark_gray>。劇情世界裡的劇情人物由程式擺：放在別的區塊的會被收回去"));
        return Command.SINGLE_SUCCESS;
    }

    private static int npcStoryList(final ExileCore plugin, final CommandSender sender) {
        if (plugin.quests() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>任務模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>劇情人物（劇情世界會自己擺；其他版面用 /ec npc story <代號> 放在面前）："));
        for (final tw.exilecore.quest.QuestConfig.Npc npc : plugin.quests().config().npcs().values()) {
            final tw.exilecore.quest.QuestConfig.Placement place = npc.placement();
            sender.sendMessage(plugin.text().parse("<gray>  " + npc.id() + " <white>" + escape(npc.name()) + " <dark_gray>"
                    + escape(npc.title()) + (place == null ? "" : "（" + place.zone()
                    + (place.checkpoint() == null ? "" : " " + place.checkpoint()) + "）")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int npcRemove(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final Optional<Entity> nearest = plugin.npcs().nearest(player, 5);
        if (nearest.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>身邊 5 格內沒有 NPC"));
            return Command.SINGLE_SUCCESS;
        }
        final String name = plugin.npcs().type(nearest.get()).map(NpcType::displayName).orElse("NPC");
        nearest.get().remove();
        player.sendMessage(plugin.text().prefixed("<gray>移除了 <white>" + name));
        return Command.SINGLE_SUCCESS;
    }

    private static int npcList(final ExileCore plugin, final CommandSender sender) {
        final java.util.List<Entity> npcs = plugin.npcs().loaded();
        if (npcs.isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<gray>目前載入的區塊裡沒有 NPC"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>目前載入的 NPC（" + npcs.size() + "）："));
        for (final Entity npc : npcs) {
            final Location location = npc.getLocation();
            final String story = plugin.npcs().storyId(npc);
            final String name = story != null && plugin.quests() != null && plugin.quests().config().npc(story) != null
                    ? escape(plugin.quests().config().npc(story).name()) + "（劇情）"
                    : plugin.npcs().type(npc).map(NpcType::displayName).orElse("NPC");
            sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>  %s <dark_gray>%s (%d, %d, %d)",
                    name, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---- 工具 ----

    /** 放進背包；放不下的掉在腳下，只有他看得到、撿得到（東西不能經過地上換手）。 */
    private static void giveOrDrop(final ExileCore plugin, final Player player, final ItemStack stack) {
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (final ItemStack rest : leftover.values()) {
            plugin.personalDrops().dropForever(player, rest);
        }
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
