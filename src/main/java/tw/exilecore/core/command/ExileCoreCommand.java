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
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code /ec} 管理指令（別名 {@code /exilecore}）。
 * <pre>
 * /ec ping                          伺服器是否活著、TPS 與 MSPT
 * /ec version                       插件、Paper、Java 版本與已啟用模組
 * /ec db                            資料庫連線狀態
 * /ec reload                        重新載入 config.yml 與資料檔
 * /ec give &lt;基底&gt; [稀有度] [物品等級]   給自己一件裝備
 * /ec givecurrency &lt;通貨&gt; [數量]      給自己通貨
 * /ec identify                      鑑定手上的物品
 * /ec item                          印出手上物品的資料
 * /ec level &lt;等級&gt;                  設定自己的測試等級
 * /ec stats                         看自己的屬性表
 * /ec spawn &lt;生物&gt; &lt;等級&gt; [稀有度]    在面前生成一隻等級化的怪物
 * /ec debug                         切換命中除錯訊息
 * </pre>
 */
public final class ExileCoreCommand {

    private ExileCoreCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(final ExileCore plugin) {
        return Commands.literal("ec")
                .requires(source -> source.getSender().hasPermission(ExileCore.PERMISSION_ADMIN))
                .executes(context -> help(plugin, context.getSource().getSender()))
                .then(Commands.literal("ping").executes(context -> ping(plugin, context.getSource().getSender())))
                .then(Commands.literal("version").executes(context -> version(plugin, context.getSource().getSender())))
                .then(Commands.literal("db").executes(context -> db(plugin, context.getSource().getSender())))
                .then(Commands.literal("reload").executes(context -> reload(plugin, context.getSource().getSender())))
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
                .then(Commands.literal("givecurrency")
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(suggest(() -> plugin.currencies().repository().all().stream().map(CurrencyType::id).toList()))
                                .executes(context -> giveCurrency(plugin, context, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 99))
                                        .executes(context -> giveCurrency(plugin, context, IntegerArgumentType.getInteger(context, "amount"))))))
                .then(Commands.literal("identify").executes(context -> identify(plugin, context)))
                .then(Commands.literal("item").executes(context -> item(plugin, context)))
                .then(Commands.literal("level")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(context -> level(plugin, context, IntegerArgumentType.getInteger(context, "level")))))
                .then(Commands.literal("stats").executes(context -> stats(plugin, context)))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(suggest(() -> java.util.List.of("zombie", "skeleton", "spider", "creeper", "husk", "stray", "drowned", "witch", "enderman")))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> spawn(plugin, context, null))
                                        .then(Commands.argument("rarity", StringArgumentType.word())
                                                .suggests(suggest(() -> java.util.List.of("normal", "magic", "rare")))
                                                .executes(context -> spawn(plugin, context, StringArgumentType.getString(context, "rarity")))))))
                .then(Commands.literal("debug").executes(context -> debug(plugin, context)))
                .build();
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
        sender.sendMessage(plugin.text().prefixed("<gray>指令：<white>ping、version、db、reload、give、givecurrency、identify、item、level、stats、spawn、debug"));
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
        giveOrDrop(player, stack);
        player.sendMessage(plugin.text().prefixed("<gray>給你一件 <white>" + rarity.displayName() + " " + base.name() + "</white>（物品等級 " + ilvl + "）"));
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
            giveOrDrop(player, plugin.currencies().create(type, batch));
            remaining -= batch;
        }
        player.sendMessage(plugin.text().prefixed("<gray>給你 <white>" + amount + " 個 " + type.name() + "</white>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int identify(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final ItemStack stack = player.getInventory().getItemInMainHand();
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>手上不是 ExileCore 的裝備"));
            return Command.SINGLE_SUCCESS;
        }
        data.get().setIdentified(true);
        plugin.items().update(stack, data.get());
        plugin.players().markDirty(player);
        player.sendMessage(plugin.text().prefixed("<green>已鑑定"));
        return Command.SINGLE_SUCCESS;
    }

    private static int item(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final ItemStack stack = player.getInventory().getItemInMainHand();
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>手上不是 ExileCore 的裝備"));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(plugin.text().prefixed("<gray>" + escape(plugin.items().toJson(data.get()))));
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
        plugin.players().setLevel(player, level);
        player.sendMessage(plugin.text().prefixed("<gray>測試等級設為 <white>" + level));
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(final ExileCore plugin, final CommandContext<CommandSourceStack> context) {
        final Player player = playerOrNull(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillProfile attack = SkillProfile.defaultAttack(state.weaponTags());
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, attack);
        final double aps = DamageCalculator.attacksPerSecond(sheet, Set.of(Tags.ATTACK, Tags.MELEE));
        final double crit = DamageCalculator.critChance(sheet, attack);

        player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<white>等級 %d　力量 %d　敏捷 %d　智慧 %d",
                state.level(), Math.round(state.strength()), Math.round(state.dexterity()), Math.round(state.intelligence()))));
        player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>生命 <white>%d</white>（回復 %.1f/秒）　魔力 <white>%d</white>（回復 %.1f/秒）　護盾 <white>%d",
                Math.round(state.maxLife()), sheet.get(StatKeys.LIFE_REGEN), Math.round(state.maxMana()), sheet.get(StatKeys.MANA_REGEN), Math.round(state.maxEnergyShield()))));
        player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>護甲 <white>%d</white>　閃避 <white>%d</white>　命中 <white>%d</white>　格擋 <white>%d%%",
                Math.round(sheet.get(StatKeys.ARMOUR)), Math.round(sheet.get(StatKeys.EVASION)), Math.round(sheet.get(StatKeys.ACCURACY)), Math.round(sheet.get(StatKeys.BLOCK_CHANCE)))));
        player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>抗性 火 <white>%d%%</white> 冰 <white>%d%%</white> 雷 <white>%d%%</white> 混沌 <white>%d%%</white>（上限 %d%%）",
                Math.round(sheet.get(StatKeys.RESIST_FIRE)), Math.round(sheet.get(StatKeys.RESIST_COLD)),
                Math.round(sheet.get(StatKeys.RESIST_LIGHTNING)), Math.round(sheet.get(StatKeys.RESIST_CHAOS)), Math.round(sheet.get(StatKeys.RESIST_MAX)))));
        player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>預設攻擊 平均 <white>%s</white>　每秒 <white>%.2f</white> 次　暴擊 <white>%.1f%%</white> ×%.0f%%　武器標籤 %s",
                expected, aps, crit * 100, sheet.get(StatKeys.CRIT_MULTI), state.weaponTags())));
        player.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>估計每秒傷害（未減免、含暴擊）<white>%d",
                Math.round(expected.total() * aps * (1 + crit * (sheet.get(StatKeys.CRIT_MULTI) / 100.0 - 1))))));
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
        player.sendMessage(plugin.text().prefixed("<gray>生成 <white>Lv." + level + " " + rarity.displayName() + " " + plugin.monsters().typeName(living)));
        return Command.SINGLE_SUCCESS;
    }

    // ---- 工具 ----

    private static void giveOrDrop(final Player player, final ItemStack stack) {
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (final ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
