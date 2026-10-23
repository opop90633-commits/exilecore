package tw.exilecore.monster;

import tw.exilecore.ExileCore;
import tw.exilecore.character.LevelPace;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.module.Module;

import java.nio.file.Path;

/** monster 模組：怪物等級化（原版生物與 MythicMobs）、稀有度、詞綴、掉落。 */
public final class MonsterModule implements Module {

    private final ExileCore plugin;
    private final MonsterConfig config;
    private final DropTable drops;
    private MonsterService service;
    private MonsterEffects effects;
    private KillCredit credit;
    private BukkitTask effectsTask;
    private MonsterMechanics mechanics;
    private BukkitTask mechanicsTask;
    private MythicHook mythic = NoMythic.INSTANCE;

    public MonsterModule(final ExileCore plugin, final MonsterConfig config, final DropTable drops) {
        this.plugin = plugin;
        this.config = config;
        this.drops = drops;
    }

    @Override
    public String id() {
        return "monster";
    }

    @Override
    public void enable() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        drops.load(data);
        service = new MonsterService(plugin, config, plugin.items().random());
        credit = new KillCredit(plugin, service);
        effects = new MonsterEffects(plugin, service);
        service.addDecorateListener(effects::track);
        plugin.getServer().getPluginManager().registerEvents(effects, plugin);
        effectsTask = plugin.repeat("monster.effects", effects::tick, 20, 20);
        // 2026-10-23：PoE2 稀有怪詞綴的特殊行為（足跡、熔岩護盾、閃電幻影、時間泡泡…）
        mechanics = new MonsterMechanics(plugin, service, effects);
        effects.mechanics(mechanics);
        plugin.getServer().getPluginManager().registerEvents(mechanics, plugin);
        mechanicsTask = plugin.repeat("monster.mechanics", mechanics::tick, 40, 2);
        plugin.getServer().getPluginManager().registerEvents(new MonsterListener(plugin, service, drops), plugin);
        mythic = MythicSupport.create(plugin, service);
        plugin.getSLF4JLogger().info("怪物設定：等級 {} 到 {}，{} 種詞綴；{}", config.minLevel(), config.maxLevel(), config.mods().size(),
                LevelPace.summary(plugin.players().config(), config::experienceAt));
        checkPace();
    }

    @Override
    public void reload() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        drops.load(data);
        plugin.getSLF4JLogger().info("怪物經驗：{}", LevelPace.summary(plugin.players().config(), config::experienceAt));
        checkPace();
    }

    /** character.yml 的經驗表跟 monsters.yml 的怪物經驗搭不搭（角色模組比這個模組先載入、先重新載入）。 */
    private void checkPace() {
        for (final String problem : LevelPace.problems(plugin.players().config(), config::experienceAt)) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    @Override
    public void disable() {
        if (effectsTask != null) {
            effectsTask.cancel();
            effectsTask = null;
        }
        if (mechanicsTask != null) {
            mechanicsTask.cancel();
            mechanicsTask = null;
        }
    }

    public MonsterService service() {
        return service;
    }

    /** MythicMobs 橋接；沒裝時是什麼都不做的 {@link NoMythic}。 */
    public MythicHook mythic() {
        return mythic;
    }

    public DropTable drops() {
        return drops;
    }

    /** 怪物死亡時誰有份（掉落、經驗）。 */
    public KillCredit credit() {
        return credit;
    }

    /** 2026-10-23：詞綴的特殊行為（被打時的護盾、近身、幻影）；模組沒啟用時為 null。 */
    public MonsterMechanics mechanics() {
        return mechanics;
    }

    /** 詞綴的持續效果（光環、召喚、招式）；模組沒啟用時為 null。 */
    public MonsterEffects effects() {
        return effects;
    }
}
