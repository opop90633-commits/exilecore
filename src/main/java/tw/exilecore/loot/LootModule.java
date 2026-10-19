package tw.exilecore.loot;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.TrashMenu;
import tw.exilecore.module.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * loot 模組（階段 3 批次 3）：掉落過濾器、殺怪自動撿取、隨身背包（物品＋通貨袋）、選單。
 * 要在 character、monster、currency、economy 之後啟用；隨身背包跟著角色存檔（character_inventory）。
 * <p>
 * 玩家指令：{@code /menu} 選單（也可以按 Shift＋F）、{@code /bag} 隨身背包、{@code /filter} 掉落過濾。
 */
public final class LootModule implements Module {

    private final ExileCore plugin;
    private org.bukkit.scheduler.BukkitTask swapTask;
    private final LootConfig config = new LootConfig();
    private LootService service;

    public LootModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "loot";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.players() == null || plugin.monsters() == null) {
            throw new IllegalStateException("角色或怪物模組沒有啟用（看上面的錯誤訊息），掉落模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final LootService created = new LootService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new LootListener(plugin, created), plugin);
        plugin.characters().addBeforeSave(created::syncOpenBag);
        service = created;
        // 2026-10-19 防最後一下換裝：每秒記一次每個人的物品稀有度、數量（SwapGuard）
        swapTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> SwapGuard.sample(plugin), 20L, 20L);
        warnUnknownCurrencies();
        plugin.getSLF4JLogger().info("掉落：自動撿取{}，過濾器預設「{}」（{}）", config.autoPickup() ? "開" : "關",
                config.preset(config.defaultPresetId()).name(), String.join("、", config.presetNames()));
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        warnUnknownCurrencies();
    }

    @Override
    public void disable() {
        if (swapTask != null) {
            swapTask.cancel();
            swapTask = null;
        }
        if (service != null) {
            service.closeAll();
        }
    }

    public LootService service() {
        return service;
    }

    /** 過濾器預設組合裡拼錯的通貨代號（不會壞，只是沒作用）。 */
    private void warnUnknownCurrencies() {
        final List<String> unknown = new ArrayList<>();
        for (final String id : config.mentionedCurrencies()) {
            if (plugin.currencies().repository().get(id) == null) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            plugin.getSLF4JLogger().warn("loot.yml 的過濾器或 notable.quiet_currencies 寫到不認得的通貨：{}（看 currencies.yml 的代號）", String.join("、", unknown));
        }
    }

    /** 玩家指令：/menu、/bag、/filter、/trash（垃圾桶）。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        final LiteralCommandNode<CommandSourceStack> menu = Commands.literal("menu")
                .executes(context -> run(plugin, context.getSource(), player -> new MainMenu(plugin, player).open()))
                .build();
        final LiteralCommandNode<CommandSourceStack> bag = Commands.literal("bag")
                .executes(context -> run(plugin, context.getSource(), player -> {
                    if (plugin.loot() == null) {
                        player.sendMessage(plugin.text().prefixed("<red>掉落模組沒有啟用（看主控台的錯誤訊息）"));
                    } else {
                        plugin.loot().openBag(player);
                    }
                }))
                .build();
        final LiteralCommandNode<CommandSourceStack> filter = Commands.literal("filter")
                .executes(context -> run(plugin, context.getSource(), player -> {
                    if (plugin.loot() == null) {
                        player.sendMessage(plugin.text().prefixed("<red>掉落模組沒有啟用（看主控台的錯誤訊息）"));
                    } else {
                        plugin.loot().openFilter(player);
                    }
                }))
                .build();
        final LiteralCommandNode<CommandSourceStack> trash = Commands.literal("trash")
                .executes(context -> run(plugin, context.getSource(), player -> {
                    final var state = plugin.players().get(player);
                    if (!state.isLoaded() || !state.hasCharacter()) {
                        player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
                    } else {
                        new TrashMenu(plugin, player).open();
                    }
                }))
                .build();
        return List.of(menu, bag, filter, trash);
    }

    private static int run(final ExileCore plugin, final CommandSourceStack source, final Consumer<Player> action) {
        if (source.getExecutor() instanceof Player player) {
            action.accept(player);
        } else {
            source.getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
