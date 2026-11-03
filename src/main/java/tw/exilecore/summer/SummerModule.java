package tw.exilecore.summer;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/**
 * summer 模組：夏日活動「星潮祭」（2026-11-03 照站長；data/summer_festival.yml）。
 * 跟萬燈夜（festival）平行、獨立：各自的場地、素材、商店，兩個活動可以同時存在。要在委託、贊助模組之後啟用（稱號、時裝在帳號進度裡）。
 */
public final class SummerModule implements Module, Listener {

    private final ExileCore plugin;
    private final SummerConfig config = new SummerConfig();
    private SummerService service;
    private BukkitTask task;
    private BukkitTask fastTask;

    public SummerModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "summer";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new SummerService(plugin, config);
        service.registerTitles();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("summer.tick", service::tick, 40, 20);
        fastTask = plugin.repeat("summer.melons", service::tickFast, 60, 2);
        plugin.getSLF4JLogger().info("夏日活動：{}（{} ～ {}，兌換到 {}；兌換 {} 樣）", config.enabled() ? config.name() : "關",
                config.format(config.start()), config.format(config.end()), config.format(config.shopUntil()), config.shop().size());
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.registerTitles();
            service.reloaded();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (fastTask != null) {
            fastTask.cancel();
            fastTask = null;
        }
        if (service != null) {
            service.shutdown();
        }
    }

    private void load() throws Exception {
        final SummerConfig fresh = new SummerConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = fresh.validate(
                id -> plugin.currencies() == null || plugin.currencies().repository().get(id) != null,
                id -> plugin.sponsors() == null || plugin.sponsors().config().cosmetics().containsKey(id),
                id -> plugin.skills() == null || plugin.skills().config().bossSkills().skill(id) != null);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(SummerConfig.FILE + " 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.load(plugin.dataFolder().resolve("data"));
    }

    public SummerService service() {
        return service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (service != null && service.role(event.getEntity()) != null) {
            service.onDeath(event.getEntity(), event.getEntity().getKiller());
        }
    }

    /** 右鍵漂流瓶、西瓜。 */
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

    /** 左鍵（打）西瓜也算打破；不當成攻擊、不放技能（SkillInputListener 在 LOW 看 isMelon 先讓路，所以這裡要在它之後）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        if (service != null && service.isMelon(event.getAttacked())) {
            event.setCancelled(true);
            service.breakMelon(event.getPlayer(), event.getAttacked().getUniqueId());
        }
    }

    /** 拿鏟子右鍵海灘的沙子：挖寶（SkillInputListener 看 isDigging 先讓路）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDig(final PlayerInteractEvent event) {
        if (service == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !service.isDigging(event.getPlayer(), event.getItem(), event.getClickedBlock())) {
            return;
        }
        if (service.dig(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }

    /** 玩家指令：/summer 打開星潮祭（活動期間＋兌換期間）。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("summer").executes(context -> {
            final Entity executor = context.getSource().getExecutor();
            if (!(executor instanceof Player player)) {
                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                return Command.SINGLE_SUCCESS;
            }
            open(plugin, player);
            return Command.SINGLE_SUCCESS;
        }).build());
    }

    /** 打開夏日活動的選單（沒有在進行就說一聲）。 */
    public static void open(final ExileCore plugin, final Player player) {
        final SummerService summer = plugin.summer();
        if (summer == null) {
            player.sendMessage(plugin.text().prefixed("<red>夏日活動模組沒有啟用（看主控台的錯誤訊息）"));
        } else if (!summer.visible()) {
            player.sendMessage(plugin.text().prefixed("<gray>現在沒有夏日活動"));
        } else {
            new SummerMenu(plugin, player, summer).open();
        }
    }
}
