package tw.exilecore.party;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.List;
import java.util.Optional;

/**
 * party 模組：隊伍（/party、/p）與多人的規則：
 * <ul>
 *   <li>隊伍最多 6 人；隊友共用地圖的傳送門（地圖模組）。</li>
 *   <li>掉落：傷害有到 5% 的人各擲一份（怪物模組的 {@code KillCredit}）。</li>
 *   <li>經驗：傷害最多的隊伍、附近的隊員照等級分。</li>
 *   <li>附近（2026-10-26）：同世界、水平 nearby_radius（一間 Boss 房，40 格）內、活著、不在城鎮；經驗、掉落、隊伍加成只算附近的。</li>
 *   <li>人多時怪物變硬：刷怪時附近每多一個玩家，普通／魔法／稀有／傳奇生命 +50%／+60%／+80%／+135%（2026-10-27 照 PoE2；最多算 6 人）。</li>
 * </ul>
 * 設定在 data/party.yml。要在怪物與地圖模組之後啟用。
 */
public final class PartyModule implements Module {

    private final ExileCore plugin;
    private final PartyConfig config = new PartyConfig();
    private PartyService service;
    private BukkitTask task;

    public PartyModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "party";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.monsters() == null || plugin.players() == null) {
            throw new IllegalStateException("怪物或角色模組沒有啟用（看上面的錯誤訊息），隊伍模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final PartyService created = new PartyService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new PartyListener(created), plugin);
        // 人多時怪物變硬：刷怪那一刻算附近的玩家（記在怪物身上），屬性表加上生命
        plugin.monsters().setPlayerCounter(created::countPlayers);
        plugin.monsters().addAugmenter((entity, sheet) -> {
            final int players = plugin.monsters().playersAround(entity);
            if (players <= 1) {
                return;
            }
            final Optional<MonsterData> data = plugin.monsters().data(entity);
            if (data.isEmpty()) {
                return;
            }
            final double more = config.lifeFactor(data.get().rarity(), players) - 1;
            if (more > 0) {
                sheet.add(Modifier.more(StatKeys.LIFE_MAX, more, "多人（" + Math.min(players, config.maxCountedPlayers()) + " 人）"));
            }
        });
        service = created;
        task = plugin.repeat("party", created::tick, 20, 20);
        plugin.getSLF4JLogger().info("隊伍：最多 {} 人；傷害 {}% 以上各拿一份掉落；刷怪時附近每多一人，普通怪生命 +{}%、傳奇 +{}%",
                config.maxSize(), Math.round(config.shareThreshold() * 100),
                Math.round(config.lifePerExtraPlayer(tw.exilecore.monster.MonsterRarity.NORMAL)),
                Math.round(config.lifePerExtraPlayer(tw.exilecore.monster.MonsterRarity.UNIQUE)));
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        if (service != null) {
            service.reloaded();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.clear();
        }
        if (plugin.monsters() != null) {
            plugin.monsters().setPlayerCounter(null);
        }
    }

    /** 隊伍；還沒啟用時為 null。 */
    public PartyService service() {
        return service;
    }

    /** 玩家指令：/party、/p。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return PartyCommand.build(plugin);
    }
}
