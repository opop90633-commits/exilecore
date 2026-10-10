package tw.exilecore.zone;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /ec zone} 管理指令：擺放區域。
 * <pre>
 * /ec zone list                           列出區域與版面狀態
 * /ec zone here                           你現在在哪個區域、這裡的怪物等級、你在這裡的防刷掉落倍率
 * /ec zone fatigue reset                  清掉你的防刷狀態（每個區域都回到全額掉落；測試用）
 * /ec zone pos1 | pos2                    選取範圍的兩個角落（你腳下的方塊）
 * /ec zone define &lt;區域&gt;                 用 pos1、pos2 設定區域範圍（高度自動包含整個世界）
 * /ec zone entry &lt;區域&gt;                  用你的位置當區域的落點（營地的落點＝回城的位置）
 * /ec zone arena &lt;區域&gt;                  用你的位置當 Boss 場地中心
 * /ec zone checkpoint add &lt;區域&gt; &lt;代號&gt; [名稱]    在你的位置放檢查點
 * /ec zone checkpoint remove &lt;區域&gt; &lt;代號&gt;
 * /ec zone tp &lt;區域&gt; [檢查點]            傳送過去（管理員不受「發現過才能傳送」限制）
 * /ec zone boss spawn &lt;區域&gt;             立刻生成（或重新生成）Boss
 * /ec zone boss reset &lt;區域&gt;             移除 Boss、清掉重生冷卻
 * /ec zone clear                          移除所有區域刷出來的怪物
 * /ec zone autolayout [confirm]           以你為營地中心一鍵產生整套版面與營地 NPC
 * </pre>
 */
public final class ZoneAdminCommand {

    private ZoneAdminCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("zone")
                .executes(context -> list(plugin, context.getSource().getSender()))
                .then(Commands.literal("list").executes(context -> list(plugin, context.getSource().getSender())))
                .then(Commands.literal("here").executes(context -> withPlayer(plugin, context, player -> here(plugin, player))))
                .then(Commands.literal("fatigue")
                        .executes(context -> withPlayer(plugin, context, player -> here(plugin, player)))
                        .then(Commands.literal("reset").executes(context -> withPlayer(plugin, context, player -> fatigueReset(plugin, player)))))
                .then(Commands.literal("pos1").executes(context -> withPlayer(plugin, context, player -> pos(plugin, player, 0))))
                .then(Commands.literal("pos2").executes(context -> withPlayer(plugin, context, player -> pos(plugin, player, 1))))
                .then(Commands.literal("define")
                        .then(zoneArgument(plugin, zone -> true)
                                .executes(context -> withZone(plugin, context, (player, zone) -> define(plugin, player, zone)))))
                .then(Commands.literal("entry")
                        .then(zoneArgument(plugin, zone -> true)
                                .executes(context -> withZone(plugin, context, (player, zone) -> entry(plugin, player, zone)))))
                .then(Commands.literal("arena")
                        .then(zoneArgument(plugin, zone -> !zone.town())
                                .executes(context -> withZone(plugin, context, (player, zone) -> arena(plugin, player, zone)))))
                .then(Commands.literal("checkpoint")
                        .then(Commands.literal("add")
                                .then(zoneArgument(plugin, zone -> !zone.town())
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .executes(context -> withZone(plugin, context, (player, zone) ->
                                                        checkpointAdd(plugin, player, zone, StringArgumentType.getString(context, "id"), null)))
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(context -> withZone(plugin, context, (player, zone) ->
                                                                checkpointAdd(plugin, player, zone, StringArgumentType.getString(context, "id"),
                                                                        StringArgumentType.getString(context, "name"))))))))
                        .then(Commands.literal("remove")
                                .then(zoneArgument(plugin, zone -> !zone.town())
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests(checkpointSuggestions(plugin))
                                                .executes(context -> withZone(plugin, context, (player, zone) ->
                                                        checkpointRemove(plugin, player, zone, StringArgumentType.getString(context, "id"))))))))
                .then(Commands.literal("tp")
                        .then(zoneArgument(plugin, zone -> true)
                                .executes(context -> withZone(plugin, context, (player, zone) -> tp(plugin, player, zone, null)))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(checkpointSuggestions(plugin))
                                        .executes(context -> withZone(plugin, context, (player, zone) ->
                                                tp(plugin, player, zone, StringArgumentType.getString(context, "id")))))))
                .then(Commands.literal("boss")
                        .then(Commands.literal("spawn")
                                .then(zoneArgument(plugin, zone -> zone.boss() != null)
                                        .executes(context -> withZone(plugin, context, (player, zone) -> bossSpawn(plugin, player, zone)))))
                        .then(Commands.literal("reset")
                                .then(zoneArgument(plugin, zone -> zone.boss() != null)
                                        .executes(context -> withZone(plugin, context, (player, zone) -> bossReset(plugin, player, zone))))))
                .then(Commands.literal("clear").executes(context -> clear(plugin, context.getSource().getSender())))
                .then(Commands.literal("autolayout")
                        .executes(context -> withPlayer(plugin, context, player -> autolayout(plugin, player, false)))
                        .then(Commands.literal("confirm")
                                .executes(context -> withPlayer(plugin, context, player -> autolayout(plugin, player, true)))));
    }

    // ---- 參數與共用 ----

    @FunctionalInterface
    private interface ZoneAction {
        void run(Player player, Zone zone);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> zoneArgument(
            final ExileCore plugin, final java.util.function.Predicate<Zone> filter) {
        return Commands.argument("zone", StringArgumentType.word()).suggests((context, builder) -> {
            if (plugin.zones() != null) {
                final String remaining = builder.getRemainingLowerCase();
                for (final Zone zone : plugin.zones().zones()) {
                    if (filter.test(zone) && zone.id().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(zone.id());
                    }
                }
            }
            return builder.buildFuture();
        });
    }

    private static SuggestionProvider<CommandSourceStack> checkpointSuggestions(final ExileCore plugin) {
        return (context, builder) -> {
            if (plugin.zones() != null) {
                final String zoneId = StringArgumentType.getString(context, "zone");
                plugin.zones().zone(zoneId).ifPresent(zone -> {
                    for (final ZoneLayout.Checkpoint checkpoint : zone.checkpoints()) {
                        if (checkpoint.id().startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(checkpoint.id());
                        }
                    }
                });
            }
            return builder.buildFuture();
        };
    }

    private static int withPlayer(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                                  final java.util.function.Consumer<Player> action) {
        final Entity executor = context.getSource().getExecutor();
        if (!(executor instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.zones() == null) {
            player.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    private static int withZone(final ExileCore plugin, final CommandContext<CommandSourceStack> context, final ZoneAction action) {
        return withPlayer(plugin, context, player -> {
            final String id = StringArgumentType.getString(context, "zone");
            final Optional<Zone> zone = plugin.zones().zone(id);
            if (zone.isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<red>zones.yml 裡沒有區域 " + ItemRenderer.escape(id)));
                return;
            }
            action.run(player, zone.get());
        });
    }

    /** 改完版面：重建、存檔、回報。 */
    private static void saved(final ExileCore plugin, final Player player, final String message) {
        final ZoneService zones = plugin.zones();
        zones.rebuild();
        try {
            zones.saveLayout();
        } catch (final IOException e) {
            player.sendMessage(plugin.text().prefixed("<red>存檔失敗：" + ItemRenderer.escape(e.getMessage())));
            return;
        }
        player.sendMessage(plugin.text().prefixed(message));
    }

    private static String format(final Location location) {
        return String.format(Locale.ROOT, "%s (%d, %d, %d)", location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    // ---- 指令 ----

    private static int list(final ExileCore plugin, final CommandSender sender) {
        final ZoneService zones = plugin.zones();
        if (zones == null) {
            sender.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        final ZoneModule module = plugin.zoneModule();
        sender.sendMessage(plugin.text().prefixed("<gray>區域（" + zones.zones().size() + "）　區域怪物 <white>"
                + module.spawner().alive() + "</white> 隻　MythicMobs：" + (plugin.mythic().available() ? "<green>已連接" : "<gray>沒有")));
        for (final Zone zone : zones.zones()) {
            final List<String> problems = zones.problems(zone);
            final String status = problems.isEmpty() ? "<green>OK" : "<yellow>" + String.join("、", problems);
            final String kind = zone.town() ? "營地" : zone.levelText();
            String extra = "";
            if (zone.placed()) {
                final ZoneLayout.Box box = zone.layout().box();
                extra = String.format(Locale.ROOT, " <dark_gray>%s x %d~%d z %d~%d，檢查點 %d", box.world(),
                        box.minX(), box.maxX(), box.minZ(), box.maxZ(), zone.checkpoints().size());
            }
            if (zone.boss() != null && module.bosses().boss(zone).isPresent()) {
                extra += " <#af6025>Boss 出現中";
            }
            sender.sendMessage(plugin.text().parse("<gray>  <white>" + zone.id() + "</white> " + ItemRenderer.escape(zone.name())
                    + " <dark_gray>(" + kind + ")</dark_gray> " + status + extra));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void here(final ExileCore plugin, final Player player) {
        final Location location = player.getLocation();
        final Optional<Zone> zone = plugin.zones().zoneAt(location);
        if (zone.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<gray>不在任何區域（怪物等級照離出生點的距離：Lv."
                    + plugin.monsters().levelFor(location) + "）"));
            return;
        }
        player.sendMessage(plugin.text().prefixed("<gray>在 <white>" + zone.get().id() + "</white> "
                + ItemRenderer.escape(zone.get().name()) + "，這裡的怪物等級 <white>Lv." + plugin.monsters().levelFor(location)));
        if (!zone.get().town()) {
            final int percent = (int) Math.round(plugin.zones().currentLootFactor(player, zone.get()) * 100);
            player.sendMessage(plugin.text().prefixed("<gray>防刷：你在這裡的掉落 <white>" + percent + "%</white>"
                    + "（角色等級到 Lv." + zone.get().maxLevel() + " 以後才會打折；<white>/ec zone fatigue reset</white> 清掉）"));
        }
    }

    private static void fatigueReset(final ExileCore plugin, final Player player) {
        plugin.zones().fatigue().reset(player.getUniqueId());
        player.sendMessage(plugin.text().prefixed("<green>清掉你的防刷狀態了：每個區域都回到全額掉落"));
    }

    private static void pos(final ExileCore plugin, final Player player, final int index) {
        final Location location = player.getLocation().toBlockLocation();
        plugin.zones().select(player, index, location);
        player.sendMessage(plugin.text().prefixed("<gray>pos" + (index + 1) + " = " + format(location)));
    }

    private static void define(final ExileCore plugin, final Player player, final Zone zone) {
        final Location[] selection = plugin.zones().selection(player);
        if (selection == null || selection[0] == null || selection[1] == null) {
            player.sendMessage(plugin.text().prefixed("<red>先用 /ec zone pos1 與 /ec zone pos2 選兩個角落"));
            return;
        }
        if (!selection[0].getWorld().equals(selection[1].getWorld())) {
            player.sendMessage(plugin.text().prefixed("<red>pos1 與 pos2 不在同一個世界"));
            return;
        }
        final World world = selection[0].getWorld();
        final ZoneLayout.Box box = ZoneLayout.Box.of(world.getName(),
                selection[0].getBlockX(), world.getMinHeight(), selection[0].getBlockZ(),
                selection[1].getBlockX(), world.getMaxHeight() - 1, selection[1].getBlockZ());
        plugin.zones().layout().getOrCreate(zone.id()).setBox(box);
        saved(plugin, player, String.format(Locale.ROOT, "<green>%s 的範圍：x %d~%d，z %d~%d（%d×%d）",
                ItemRenderer.escape(zone.name()), box.minX(), box.maxX(), box.minZ(), box.maxZ(),
                box.maxX() - box.minX() + 1, box.maxZ() - box.minZ() + 1));
    }

    private static void entry(final ExileCore plugin, final Player player, final Zone zone) {
        plugin.zones().layout().getOrCreate(zone.id()).setEntry(ZoneService.toPoint(player.getLocation()));
        saved(plugin, player, "<green>" + ItemRenderer.escape(zone.name()) + " 的落點設在 " + format(player.getLocation())
                + (zone.town() ? "（回城、新角色、死亡沒有重生點時都到這裡）" : ""));
    }

    private static void arena(final ExileCore plugin, final Player player, final Zone zone) {
        plugin.zones().layout().getOrCreate(zone.id()).setArena(ZoneService.toPoint(player.getLocation()));
        saved(plugin, player, "<green>" + ItemRenderer.escape(zone.name()) + " 的 Boss 場地設在 " + format(player.getLocation())
                + "（等級在這裡最高）");
    }

    private static void checkpointAdd(final ExileCore plugin, final Player player, final Zone zone, final String id, final String name) {
        if (!id.matches("[a-z0-9_]{1,24}")) {
            player.sendMessage(plugin.text().prefixed("<red>代號只能用小寫英文、數字、底線（最多 24 個字）"));
            return;
        }
        final ZoneLayout.Entry entry = plugin.zones().layout().getOrCreate(zone.id());
        final boolean existed = entry.checkpoint(id) != null;
        entry.putCheckpoint(new ZoneLayout.Checkpoint(id, name == null || name.isBlank() ? id : name.trim(),
                ZoneService.toPoint(player.getLocation())));
        saved(plugin, player, "<green>" + (existed ? "移動" : "新增") + "檢查點 " + id + "（" + ItemRenderer.escape(zone.name()) + "）："
                + format(player.getLocation()));
    }

    private static void checkpointRemove(final ExileCore plugin, final Player player, final Zone zone, final String id) {
        final ZoneLayout.Entry entry = plugin.zones().layout().getOrCreate(zone.id());
        if (entry.removeCheckpoint(id) == null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(zone.name()) + " 沒有檢查點 " + ItemRenderer.escape(id)));
            return;
        }
        saved(plugin, player, "<gray>移除了檢查點 " + ItemRenderer.escape(id));
    }

    private static void tp(final ExileCore plugin, final Player player, final Zone zone, final String checkpointId) {
        final ZoneLayout.Point point;
        if (checkpointId == null) {
            point = zone.layout().entry();
        } else {
            final ZoneLayout.Checkpoint checkpoint = zone.layout().checkpoint(checkpointId);
            point = checkpoint == null ? null : checkpoint.point();
        }
        final Location target = plugin.zones().toLocation(point);
        if (target == null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(zone.name()) + (checkpointId == null
                    ? " 還沒有落點（/ec zone entry）" : " 沒有檢查點 " + ItemRenderer.escape(checkpointId))));
            return;
        }
        player.teleportAsync(target);
        player.sendMessage(plugin.text().prefixed("<gray>傳送到 " + ItemRenderer.escape(zone.name())
                + (checkpointId == null ? "" : " 的 " + ItemRenderer.escape(checkpointId))));
    }

    private static void bossSpawn(final ExileCore plugin, final Player player, final Zone zone) {
        final Location arena = plugin.zones().toLocation(zone.layout().arena());
        if (arena == null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(zone.name()) + " 還沒有 Boss 場地（/ec zone arena）"));
            return;
        }
        final boolean ok = plugin.zoneModule().bosses().spawn(zone, arena);
        player.sendMessage(plugin.text().prefixed(ok ? "<green>生成了 " + ItemRenderer.escape(zone.boss().name())
                : "<red>生成失敗（看主控台）"));
    }

    private static void bossReset(final ExileCore plugin, final Player player, final Zone zone) {
        final boolean removed = plugin.zoneModule().bosses().reset(zone);
        player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(zone.name()) + " 的 Boss "
                + (removed ? "已移除，" : "") + "冷卻已清除"));
    }

    private static int clear(final ExileCore plugin, final CommandSender sender) {
        if (plugin.zones() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final int removed = plugin.zoneModule().spawner().removeAll();
        sender.sendMessage(plugin.text().prefixed("<gray>移除了 " + removed + " 隻區域怪物"));
        return Command.SINGLE_SUCCESS;
    }

    private static void autolayout(final ExileCore plugin, final Player player, final boolean confirmed) {
        final ZoneService zones = plugin.zones();
        final List<String> placed = new ArrayList<>();
        for (final Zone zone : zones.zones()) {
            if (zone.placed()) {
                placed.add(zone.id());
            }
        }
        if (!placed.isEmpty() && !confirmed) {
            player.sendMessage(plugin.text().prefixed("<yellow>已經擺好的區域（" + String.join("、", placed)
                    + "）會被蓋掉。確定要重新產生請輸入 <white>/ec zone autolayout confirm"));
            return;
        }
        AutoLayout.run(plugin, zones, player);
    }
}
