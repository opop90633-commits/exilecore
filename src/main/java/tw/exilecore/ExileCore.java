package tw.exilecore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import tw.exilecore.character.CharacterModule;
import tw.exilecore.character.PlayerStateService;
import tw.exilecore.combat.CombatModule;
import tw.exilecore.core.CoreModule;
import tw.exilecore.core.command.ExileCoreCommand;
import tw.exilecore.core.config.Settings;
import tw.exilecore.core.db.Database;
import tw.exilecore.core.text.Text;
import tw.exilecore.currency.CurrencyModule;
import tw.exilecore.currency.CurrencyService;
import tw.exilecore.item.ItemModule;
import tw.exilecore.item.ItemService;
import tw.exilecore.module.ModuleRegistry;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterModule;
import tw.exilecore.monster.MonsterService;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * ExileCore 插件主類別：載入設定、依序啟用模組、註冊指令。
 * 模組的註冊順序就是啟用順序：core → item → currency → character → monster → combat。
 */
public final class ExileCore extends JavaPlugin {

    /** 管理指令權限；預設只有 OP 有。 */
    public static final String PERMISSION_ADMIN = "exilecore.admin";

    private Settings settings;
    private Text text;
    private Database database;
    private ModuleRegistry modules;

    private final RandomGenerator random = new SplittableRandom();
    private ItemModule itemModule;
    private CurrencyModule currencyModule;
    private PlayerStateService players;
    private MonsterModule monsterModule;

    @Override
    public void onEnable() {
        // 第一次啟動時把 config.yml 複製到 plugins/ExileCore/
        saveDefaultConfig();
        this.settings = Settings.load(getConfig());
        this.text = new Text(settings.prefix());

        registerPermissions();

        this.database = new Database(getSLF4JLogger(), getDataFolder().toPath());
        this.modules = new ModuleRegistry(getSLF4JLogger());
        registerModules();
        modules.enableAll();

        // Paper 的 Brigadier 指令：在生命週期事件裡註冊，伺服器 /reload 時也會重新註冊
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(ExileCoreCommand.build(this), "ExileCore 管理指令", List.of("exilecore")));

        getSLF4JLogger().info("ExileCore {} 已啟用（Minecraft {}，Java {}）",
                getPluginMeta().getVersion(), getServer().getMinecraftVersion(), Runtime.version().feature());
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
    }

    private void registerModules() {
        this.itemModule = new ItemModule(this, random);
        this.currencyModule = new CurrencyModule(this);
        this.players = new PlayerStateService(this);
        this.monsterModule = new MonsterModule(this, new MonsterConfig(), new DropTable());

        modules.register(new CoreModule(this, database));
        modules.register(itemModule);                       // 階段 1
        modules.register(currencyModule);                   // 階段 1
        modules.register(new CharacterModule(this, players)); // 階段 1 最小版；階段 2 補職業、經驗、持久化
        modules.register(monsterModule);                    // 階段 1
        modules.register(new CombatModule(this));           // 階段 1
        // 階段 2：passive、zone、stash
        // 階段 3：skill
        // 階段 4：map
        // 階段 5：economy
    }

    private void registerPermissions() {
        final PluginManager pluginManager = getServer().getPluginManager();
        if (pluginManager.getPermission(PERMISSION_ADMIN) == null) {
            pluginManager.addPermission(new Permission(PERMISSION_ADMIN, "ExileCore 管理指令", PermissionDefault.OP));
        }
    }

    /** 重新讀取 config.yml 與資料檔，並通知所有模組。 */
    public void reload() {
        reloadConfig();
        this.settings = Settings.load(getConfig());
        this.text = new Text(settings.prefix());
        modules.reloadAll();
    }

    public Path dataFolder() {
        return getDataFolder().toPath();
    }

    public Settings settings() {
        return settings;
    }

    public Text text() {
        return text;
    }

    public Database database() {
        return database;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public RandomGenerator random() {
        return random;
    }

    public ItemService items() {
        return itemModule.service();
    }

    public CurrencyService currencies() {
        return currencyModule.service();
    }

    public PlayerStateService players() {
        return players;
    }

    public MonsterService monsters() {
        return monsterModule.service();
    }

    public MonsterModule monsterModule() {
        return monsterModule;
    }
}
