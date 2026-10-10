package tw.exilecore.skill;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRenderer;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 技能指令。
 * <pre>
 * /skills、/skill                     技能面板（玩家）
 * /ec skill list                      列出所有技能與代號（管理員）
 * /ec skill give &lt;技能&gt; [等級]        加到自己的技能面板（已經有就改等級；沒寫等級 = 角色能用的最高等級）
 * /ec skill remove &lt;技能&gt;             從自己的技能面板移除
 * /ec skill reset                     清空自己的技能面板（下次載入角色會再送起始技能）
 * /ec gem uncut &lt;等級&gt; [數量]           未切割技能寶石
 * /ec gem uncutsupport &lt;階級&gt; [數量]    未切割輔助寶石
 * /ec gem skill &lt;技能&gt; [等級]           技能寶石
 * /ec gem support &lt;輔助&gt; [數量]         輔助寶石
 * </pre>
 */
public final class SkillCommands {

    private SkillCommands() {
    }

    /** 玩家指令。 */
    public static List<LiteralCommandNode<CommandSourceStack>> build(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> skills = Commands.literal("skills")
                .executes(context -> run(plugin, context, player -> open(plugin, player)))
                .build();
        final LiteralCommandNode<CommandSourceStack> skill = Commands.literal("skill")
                .executes(context -> run(plugin, context, player -> open(plugin, player)))
                .build();
        return List.of(skills, skill);
    }

    private static void open(final ExileCore plugin, final Player player) {
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        new SkillMenu(plugin, player, plugin.skills()).open();
    }

    /** /ec skill 底下的管理指令。 */
    public static LiteralArgumentBuilder<CommandSourceStack> adminNode(final ExileCore plugin) {
        return Commands.literal("skill")
                .then(Commands.literal("list").executes(context -> list(plugin, context.getSource().getSender())))
                .then(Commands.literal("give")
                        .then(skillArgument(plugin)
                                .executes(context -> run(plugin, context, player -> give(plugin, player,
                                        StringArgumentType.getString(context, "skill"), null)))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, SkillLoadout.MAX_LEVEL))
                                        .executes(context -> run(plugin, context, player -> give(plugin, player,
                                                StringArgumentType.getString(context, "skill"),
                                                IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("remove")
                        .then(skillArgument(plugin)
                                .executes(context -> run(plugin, context, player -> {
                                    final String error = plugin.skills().remove(player, StringArgumentType.getString(context, "skill"));
                                    player.sendMessage(plugin.text().prefixed(error == null ? "<green>已移除" : "<red>" + ItemRenderer.escape(error)));
                                }))))
                .then(Commands.literal("reset")
                        .executes(context -> run(plugin, context, player -> {
                            plugin.skills().reset(player);
                            player.sendMessage(plugin.text().prefixed("<green>技能面板已清空（重新登入或換角色會再送起始技能；寶石不會退回）"));
                        })));
    }

    /** /ec gem 底下的管理指令：給自己各種寶石（測試用）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> gemNode(final ExileCore plugin) {
        return Commands.literal("gem")
                .then(Commands.literal("uncut")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, SkillLoadout.MAX_LEVEL))
                                .executes(context -> run(plugin, context, player -> giveGem(plugin, player,
                                        GemData.uncutSkill(IntegerArgumentType.getInteger(context, "level")), 1)))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> run(plugin, context, player -> giveGem(plugin, player,
                                                GemData.uncutSkill(IntegerArgumentType.getInteger(context, "level")),
                                                IntegerArgumentType.getInteger(context, "amount")))))))
                .then(Commands.literal("uncutsupport")
                        .then(Commands.argument("tier", IntegerArgumentType.integer(1, SupportDefinition.MAX_TIER))
                                .executes(context -> run(plugin, context, player -> giveGem(plugin, player,
                                        GemData.uncutSupport(IntegerArgumentType.getInteger(context, "tier")), 1)))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> run(plugin, context, player -> giveGem(plugin, player,
                                                GemData.uncutSupport(IntegerArgumentType.getInteger(context, "tier")),
                                                IntegerArgumentType.getInteger(context, "amount")))))))
                .then(Commands.literal("skill")
                        .then(skillArgument(plugin)
                                .executes(context -> run(plugin, context, player -> giveSkillGem(plugin, player,
                                        StringArgumentType.getString(context, "skill"), null)))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, SkillLoadout.MAX_LEVEL))
                                        .executes(context -> run(plugin, context, player -> giveSkillGem(plugin, player,
                                                StringArgumentType.getString(context, "skill"),
                                                IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("support")
                        .then(supportArgument(plugin)
                                .executes(context -> run(plugin, context, player -> giveSupportGem(plugin, player,
                                        StringArgumentType.getString(context, "support"), 1)))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> run(plugin, context, player -> giveSupportGem(plugin, player,
                                                StringArgumentType.getString(context, "support"),
                                                IntegerArgumentType.getInteger(context, "amount")))))));
    }

    private static void giveGem(final ExileCore plugin, final Player player, final GemData data, final int amount) {
        int left = amount;
        while (left > 0) {
            final int batch = Math.min(left, data.maxStack());
            plugin.skills().gems().give(player, plugin.skills().gems().create(data, batch));
            left -= batch;
        }
        final String currency = data.currencyId();
        if (currency != null && plugin.currencies() != null && plugin.currencies().repository().get(currency) != null) {
            // 未切割寶石算通貨（2026-10）：管理員給的記進流水帳的產出
            plugin.economy().ledger().produced(currency, tw.exilecore.economy.EconomyLedger.ADMIN, amount);
        }
        player.sendMessage(plugin.text().prefixed("<green>給了 " + amount + " 顆寶石"));
    }

    private static void giveSkillGem(final ExileCore plugin, final Player player, final String skillId, final Integer level) {
        final SkillDefinition definition = plugin.skills().config().skill(skillId.toLowerCase(Locale.ROOT));
        if (definition == null || definition.hidden()) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這個技能：" + ItemRenderer.escape(skillId)));
            return;
        }
        final int actual = level == null ? plugin.skills().config().maxLevelFor(plugin.players().get(player).level()) : level;
        giveGem(plugin, player, GemData.skill(new SkillLoadout.Entry(definition.id(), actual)), 1);
    }

    private static void giveSupportGem(final ExileCore plugin, final Player player, final String supportId, final int amount) {
        final SupportDefinition support = plugin.skills().config().support(supportId.toLowerCase(Locale.ROOT));
        if (support == null) {
            player.sendMessage(plugin.text().prefixed("<red>沒有這個輔助：" + ItemRenderer.escape(supportId)));
            return;
        }
        giveGem(plugin, player, GemData.support(support.id()), amount);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> supportArgument(final ExileCore plugin) {
        return Commands.argument("support", StringArgumentType.word()).suggests((context, builder) -> {
            if (plugin.skills() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final SupportDefinition support : plugin.skills().config().supports()) {
                    if (support.id().startsWith(remaining)) {
                        builder.suggest(support.id());
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> skillArgument(final ExileCore plugin) {
        return Commands.argument("skill", StringArgumentType.word()).suggests((context, builder) -> {
            if (plugin.skills() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final SkillDefinition definition : plugin.skills().config().visible()) {
                    if (definition.id().startsWith(remaining)) {
                        builder.suggest(definition.id());
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private static void give(final ExileCore plugin, final Player player, final String skillId, final Integer level) {
        final String error = plugin.skills().give(player, skillId.toLowerCase(Locale.ROOT), level);
        if (error != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(error)));
            return;
        }
        final SkillLoadout.Entry entry = plugin.players().get(player).skills().skill(
                plugin.players().get(player).skills().find(skillId.toLowerCase(Locale.ROOT)));
        final SkillDefinition definition = plugin.skills().config().skill(entry.skillId());
        player.sendMessage(plugin.text().prefixed("<green>技能面板加入 " + ItemRenderer.escape(definition.name())
                + " Lv." + entry.level() + "，<white>/skills</white> 綁到按鍵上"));
    }

    private static int list(final ExileCore plugin, final CommandSender sender) {
        if (plugin.skills() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>技能模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>技能（代號　名稱　種類　運作方式）："));
        for (final SkillDefinition definition : plugin.skills().config().visible()) {
            sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>  <white>%s</white>　%s　%s　%s　寶石 Lv.%d 起%s",
                    definition.id(), ItemRenderer.escape(definition.name()), definition.typeName(),
                    definition.mechanic().key(), definition.tier(),
                    definition.weapons().isEmpty() ? "" : "　需要" + definition.weaponText())));
        }
        sender.sendMessage(plugin.text().prefixed("<gray>輔助寶石（代號　名稱　階級　可以輔助）："));
        for (final SupportDefinition support : plugin.skills().config().supports()) {
            sender.sendMessage(plugin.text().parse(String.format(Locale.ROOT, "<gray>  <white>%s</white>　%s　第 %d 階　%s",
                    support.id(), ItemRenderer.escape(support.name()), support.tier(), support.supportsText(plugin.skills().config().all()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.skills() == null) {
            player.sendMessage(plugin.text().prefixed("<red>技能模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }
}
