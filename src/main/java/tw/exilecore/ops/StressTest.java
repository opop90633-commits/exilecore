package tw.exilecore.ops;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterRarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 壓力測試（管理員）：在腳邊一圈刷 N 隻帶招式的怪（等級照這裡的區域／地圖），幾秒後印一次 /ec perf 的報表——
 * TPS、每 tick 幾毫秒、ExileCore 各部分吃多少——看哪個系統在人多怪多時最吃時間。{@code /ec stress clear} 清掉刷出來的。
 */
public final class StressTest {

    /** 會用招式的生物（monsters.yml 的 types 有 skills 的，再加幾種會射的）。 */
    private static final List<EntityType> TYPES = List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.ZOMBIE_VILLAGER, EntityType.SKELETON,
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.VINDICATOR, EntityType.WITCH, EntityType.DROWNED, EntityType.WITHER_SKELETON,
            EntityType.PIGLIN_BRUTE, EntityType.HOGLIN, EntityType.PILLAGER, EntityType.STRAY, EntityType.BOGGED);
    private static final int MAX_COUNT = 200;
    private static final int MAX_SECONDS = 120;

    private final ExileCore plugin;
    private final List<UUID> spawned = new ArrayList<>();

    public StressTest(final ExileCore plugin) {
        this.plugin = plugin;
    }

    ExileCore plugin() {
        return plugin;
    }

    /** /ec stress 數量 [稀有度] [秒]、/ec stress clear */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("stress")
                .executes(context -> help(context.getSource().getSender()))
                .then(Commands.literal("clear").executes(context -> clear(context.getSource().getSender())))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                        .executes(context -> run(context, "rare", 20))
                        .then(Commands.argument("rarity", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (final String option : List.of("normal", "magic", "rare")) {
                                        if (option.startsWith(builder.getRemainingLowerCase())) {
                                            builder.suggest(option);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> run(context, StringArgumentType.getString(context, "rarity"), 20))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, MAX_SECONDS))
                                        .executes(context -> run(context, StringArgumentType.getString(context, "rarity"),
                                                IntegerArgumentType.getInteger(context, "seconds"))))));
    }

    private int help(final CommandSender sender) {
        sender.sendMessage(plugin.text().prefixed("<gray>/ec stress 數量 [normal|magic|rare] [秒]：在腳邊刷一圈帶招式的怪（等級照這裡），幾秒後印效能報表；"
                + "/ec stress clear 清掉。建議站在一張開著的地圖裡測，開著 /ec debug 看傷害。"));
        return Command.SINGLE_SUCCESS;
    }

    private int run(final CommandContext<CommandSourceStack> context, final String rarityText, final int seconds) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final int count = IntegerArgumentType.getInteger(context, "count");
        final MonsterRarity rarity = MonsterRarity.parse(rarityText, MonsterRarity.RARE);
        final Location center = player.getLocation();
        final int level = plugin.monsters().levelFor(center);
        final RandomGenerator random = plugin.items().random();
        int done = 0;
        for (int i = 0; i < count; i++) {
            // 一圈一圈往外：每圈約 12 隻，半徑 5 起每圈多 3 格
            final int ring = i / 12;
            final double angle = Math.PI * 2 * (i % 12) / 12 + ring * 0.26;
            final double radius = 5 + ring * 3 + random.nextDouble() * 1.5;
            final Location at = ground(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
            if (at == null) {
                continue;
            }
            final EntityType type = TYPES.get(random.nextInt(TYPES.size()));
            final Entity entity = center.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (!(entity instanceof LivingEntity living)) {
                entity.remove();
                continue;
            }
            plugin.monsters().decorate(living, level, rarity, null);
            plugin.monsters().pin(living);
            spawned.add(living.getUniqueId());
            done++;
        }
        final double before = plugin.getServer().getAverageTickTime();
        player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                "<gray>刷了 <white>%d</white> 隻 Lv.%d %s怪（%s）；現在每 tick 平均 %.1f 毫秒。%d 秒後印效能報表——這段時間請正常打",
                done, level, rarity.displayName(), rarity == MonsterRarity.RARE ? "每隻 2–4 條詞綴、最多 2 條招式" : "照種類的招式機率", before, seconds)));
        final int total = spawned.size();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int alive = 0;
            for (final UUID uuid : spawned) {
                final Entity entity = plugin.getServer().getEntity(uuid);
                if (entity != null && entity.isValid() && !entity.isDead()) {
                    alive++;
                }
            }
            player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                    "<gray>壓力測試 %d 秒結束：刷的 %d 隻還活著 <white>%d</white> 隻（刷之前每 tick %.1f 毫秒）", seconds, total, alive, before)));
            OpsModule.perf(plugin, player);
            player.sendMessage(plugin.text().parse("<dark_gray>skill.* 是技能系統各部分（boss = 招式、danger = 地板標示、minions = 召喚物）；/ec stress clear 清掉刷的怪"));
        }, seconds * 20L);
        return Command.SINGLE_SUCCESS;
    }

    /** 找這一欄能站的地面（上下各找 6 格：0、−1、+1、−2、+2…）。 */
    private static Location ground(final Location around) {
        for (int step = 0; step <= 12; step++) {
            final int dy = step % 2 == 0 ? step / 2 : -(step / 2 + 1);
            final Location at = around.clone().add(0, dy, 0);
            if (at.getBlock().isPassable() && at.clone().add(0, 1, 0).getBlock().isPassable() && !at.clone().add(0, -1, 0).getBlock().isPassable()) {
                return at.add(0.0, 0.1, 0.0);
            }
        }
        return null;
    }

    private int clear(final CommandSender sender) {
        int removed = 0;
        for (final UUID uuid : spawned) {
            final Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
                removed++;
            }
        }
        spawned.clear();
        sender.sendMessage(plugin.text().prefixed("<gray>清掉壓力測試刷的怪 <white>" + removed + "</white> 隻"));
        return Command.SINGLE_SUCCESS;
    }
}
