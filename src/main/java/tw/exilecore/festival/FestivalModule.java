package tw.exilecore.festival;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/** festival 模組：節慶活動（data/festival.yml；2026-10 萬聖節「萬燈夜」）。要在委託、贊助模組之後啟用（稱號、時裝在帳號進度裡）。 */
public final class FestivalModule implements Module, Listener {

    private final ExileCore plugin;
    private final FestivalConfig config = new FestivalConfig();
    private FestivalService service;
    private BukkitTask task;

    public FestivalModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "festival";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new FestivalService(plugin, config);
        service.registerTitles();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("festival.tick", service::tick, 40, 20);
        plugin.getSLF4JLogger().info("節慶活動：{}（{} ～ {}，兌換到 {}；兌換 {} 樣）", config.enabled() ? config.name() : "關",
                config.format(config.start()), config.format(config.end()), config.format(config.shopUntil()), config.shop().size());
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.registerTitles();
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
        final FestivalConfig fresh = new FestivalConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = fresh.validate(
                id -> plugin.currencies() == null || plugin.currencies().repository().get(id) != null,
                id -> plugin.sponsors() == null || plugin.sponsors().config().cosmetics().containsKey(id),
                id -> plugin.skills() == null || plugin.skills().config().bossSkills().skill(id) != null);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(FestivalConfig.FILE + " 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.load(plugin.dataFolder().resolve("data"));
    }

    public FestivalService service() {
        return service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (service != null && service.role(event.getEntity()) != null) {
            service.onDeath(event.getEntity(), event.getEntity().getKiller());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (service == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Entity entity = event.getRightClicked();
        if (service.role(entity) != null && service.interact(event.getPlayer(), entity)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }

    /** 玩家指令：/festival 打開萬燈夜（活動期間＋兌換期間）。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("festival").executes(context -> {
            final Entity executor = context.getSource().getExecutor();
            if (!(executor instanceof Player player)) {
                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                return Command.SINGLE_SUCCESS;
            }
            open(plugin, player);
            return Command.SINGLE_SUCCESS;
        }).build());
    }

    /** 打開節慶活動的選單（沒有在進行就說一聲）。 */
    public static void open(final ExileCore plugin, final Player player) {
        final FestivalService festival = plugin.festival();
        if (festival == null) {
            player.sendMessage(plugin.text().prefixed("<red>節慶活動模組沒有啟用（看主控台的錯誤訊息）"));
        } else if (!festival.visible()) {
            player.sendMessage(plugin.text().prefixed("<gray>現在沒有節慶活動"));
        } else {
            new FestivalMenu(plugin, player, festival).open();
        }
    }
}
