package tw.exilecore;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ailment.AilmentModule;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.character.CharacterCommand;
import tw.exilecore.character.CharacterConfig;
import tw.exilecore.character.CharacterModule;
import tw.exilecore.character.CharacterService;
import tw.exilecore.character.PlayerStateService;
import tw.exilecore.character.menu.CharacterMenus;
import tw.exilecore.combat.CombatModule;
import tw.exilecore.core.CoreModule;
import tw.exilecore.core.Perf;
import tw.exilecore.core.command.ExileCoreCommand;
import tw.exilecore.core.config.Settings;
import tw.exilecore.core.db.Database;
import tw.exilecore.core.text.Text;
import tw.exilecore.currency.CurrencyModule;
import tw.exilecore.currency.CurrencyService;
import tw.exilecore.economy.EconomyModule;
import tw.exilecore.economy.GoldService;
import tw.exilecore.flask.FlaskModule;
import tw.exilecore.flask.FlaskService;
import tw.exilecore.gui.ChatPrompts;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.item.ItemModule;
import tw.exilecore.item.ItemService;
import tw.exilecore.loot.LootModule;
import tw.exilecore.loot.LootService;
import tw.exilecore.map.MapCommand;
import tw.exilecore.map.MapModule;
import tw.exilecore.party.PartyModule;
import tw.exilecore.party.PartyService;
import tw.exilecore.map.MapService;
import tw.exilecore.market.MarketModule;
import tw.exilecore.market.MarketService;
import tw.exilecore.ops.OpsModule;
import tw.exilecore.trade.TradeModule;
import tw.exilecore.trade.TradeService;
import tw.exilecore.vendor.VendorModule;
import tw.exilecore.vendor.VendorService;
import tw.exilecore.web.WebModule;
import tw.exilecore.module.ModuleRegistry;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterModule;
import tw.exilecore.monster.MonsterService;
import tw.exilecore.monster.MythicHook;
import tw.exilecore.monster.NoMythic;
import tw.exilecore.npc.NpcModule;
import tw.exilecore.npc.NpcService;
import tw.exilecore.passive.PassiveModule;
import tw.exilecore.salvage.SalvageConfig;
import tw.exilecore.salvage.SalvageModule;
import tw.exilecore.skill.SkillCommands;
import tw.exilecore.skill.SkillModule;
import tw.exilecore.skill.SkillService;
import tw.exilecore.stash.StashModule;
import tw.exilecore.stash.StashService;
import tw.exilecore.world.WorldModule;
import tw.exilecore.zone.ZoneCommand;
import tw.exilecore.zone.ZoneModule;
import tw.exilecore.zone.ZoneService;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * ExileCore 插件主類別：載入設定、依序啟用模組、註冊指令。
 * 模組的註冊順序就是啟用順序：
 * core → item → currency → world → passive → character → economy → npc → monster → zone → stash → salvage → combat → skill → flask
 * → loot → ailment → map → party → guild → trade → market → vendor → ops → web → admin。
 * 伺服器叫《星骸》；ExileCore 是插件的代號。
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
    private PassiveModule passiveModule;
    private PlayerStateService players;
    private CharacterService characters;
    private CharacterMenus characterMenus;
    private ChatPrompts chatPrompts;
    private Dialogs dialogs;
    private tw.exilecore.gui.Sidebar sidebar;
    private tw.exilecore.monster.PersonalDrops personalDrops;
    private EconomyModule economyModule;
    private NpcModule npcModule;
    private MonsterModule monsterModule;
    private ZoneModule zoneModule;
    private StashModule stashModule;
    private SalvageModule salvageModule;
    private WorldModule worldModule;
    private CombatModule combatModule;
    private SkillModule skillModule;
    private FlaskModule flaskModule;
    private LootModule lootModule;
    private AilmentModule ailmentModule;
    private MapModule mapModule;
    private PartyModule partyModule;
    private tw.exilecore.guild.GuildModule guildModule;
    private tw.exilecore.chat.ChatModule chatModule;
    private tw.exilecore.training.TrainingModule trainingModule;
    private tw.exilecore.ladder.LadderModule ladderModule;
    private tw.exilecore.discord.DiscordModule discordModule;
    private tw.exilecore.guide.GuideModule guideModule;
    private tw.exilecore.pvp.PvpModule pvpModule;
    private tw.exilecore.heist.HeistModule heistModule;
    private tw.exilecore.anticheat.AntiCheatModule antiCheatModule;
    private TradeModule tradeModule;
    private MarketModule marketModule;
    private VendorModule vendorModule;
    private OpsModule opsModule;
    private WebModule webModule;
    private tw.exilecore.quest.QuestModule questModule;
    private tw.exilecore.bounty.BountyModule bountyModule;
    private tw.exilecore.sponsor.SponsorModule sponsorModule;
    private tw.exilecore.event.EventModule eventModule;
    private tw.exilecore.worldevent.WorldEventModule worldEventModule;
    private tw.exilecore.festival.FestivalModule festivalModule;
    private tw.exilecore.card.CardModule cardModule; // 2026-10-19 星諭卡
    private tw.exilecore.life.LifeModule lifeModule;
    private tw.exilecore.ascendancy.AscendancyModule ascendancyModule;
    private final Perf perf = new Perf();
    private tw.exilecore.admin.AdminOverrides overrides;
    private tw.exilecore.admin.AdminModule adminModule;

    @Override
    public void onEnable() {
        // 第一次啟動時把 config.yml 複製到 plugins/ExileCore/
        saveDefaultConfig();
        // 管理網頁改的設定（admin-overrides.yml）：讀資料檔時蓋上去、config.yml 載入後蓋上去（模組載入之前就要裝好）
        this.overrides = new tw.exilecore.admin.AdminOverrides(getDataFolder().toPath());
        final String overrideProblem = overrides.reload();
        if (overrideProblem != null) {
            getSLF4JLogger().error(overrideProblem);
        }
        tw.exilecore.core.config.YamlNode.overlay(overrides::forFile);
        tw.exilecore.loot.LootPenalty.load(getDataFolder().toPath(), getSLF4JLogger()); // 2026-10-18：隱藏的個人掉落倍率
        applyConfigOverrides();
        this.settings = Settings.load(getConfig());
        this.text = new Text(settings.prefix());

        registerPermissions();

        this.database = new Database(getSLF4JLogger(), getDataFolder().toPath());
        // 2026-10-27 效能：計時的區段單次超過 20 毫秒（一個 tick 只有 50）就寫一行警告（同區段 30 秒最多一次），站長看 log 就知道是哪裡卡
        perf.onSpike(Perf.SPIKE_MS, spike -> getSLF4JLogger().warn("【卡頓】{} 這一次花了 {} 毫秒（一個 tick 只有 50 毫秒）{}；/ec perf 看最近 10 秒，"
                + "要精準定位請用 roaming_thunder：/roaming_thunder profiler start --timeout 60", spike.name(), String.format(java.util.Locale.ROOT, "%.1f", spike.ms()),
                spike.suppressed() > 0 ? "，上次警告後另外又有 " + spike.suppressed() + " 次超過" : ""));
        this.modules = new ModuleRegistry(getSLF4JLogger());
        registerModules();
        modules.enableAll();
        // 2026-10-20 效能：實體消失時清掉玩家身上「看得到／看不到它」的紀錄（spark 上 canSee 佔 6～9%）
        final tw.exilecore.core.VisibilityJanitor janitor = new tw.exilecore.core.VisibilityJanitor(this);
        getServer().getPluginManager().registerEvents(janitor, this);
        repeat("visibility.sweep", janitor::sweep, 200, 10); // 2026-10-21：事件漏掉的紀錄每 10 tick 掃一個玩家
        // 2026-10-21 效能：怪物射出去沒打中的旋風彈會一直飛、一直存著（spark：一次 299 個）
        getServer().getPluginManager().registerEvents(new tw.exilecore.core.StrayProjectiles(this), this);
        if (!modules.isEnabled("core")) {
            // 資料庫沒連上或資料表遷移失敗：讀寫不了存檔，讓玩家進來只會用到舊的或半套的資料（例如沒換算的等級）
            getSLF4JLogger().error("核心模組（資料庫）啟用失敗，ExileCore 先停用：看上面的錯誤，修好後重開伺服器");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Paper 的 Brigadier 指令：在生命週期事件裡註冊，伺服器 /reload 時也會重新註冊
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(ExileCoreCommand.build(this), "ExileCore 管理指令", List.of("exilecore"));
            for (final LiteralCommandNode<CommandSourceStack> node : CharacterCommand.build(this)) {
                event.registrar().register(node, "ExileCore 角色指令");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : ZoneCommand.build(this)) {
                event.registrar().register(node, "ExileCore 傳送指令");
            }
            event.registrar().register(StashModule.command(this), "ExileCore 倉庫");
            event.registrar().register(tw.exilecore.salvage.SalvageModule.lockCommand(this), "ExileCore 鎖定裝備（防止誤拆）");
            event.registrar().register(tw.exilecore.currency.CraftBenchMenu.command(this), "ExileCore 洗裝台（左邊星籤、右邊通貨、中間裝備）"); // 2026-10-19
            event.registrar().register(tw.exilecore.combat.DeathRecap.command(this), "ExileCore 死亡回顧（上一次是被誰打死的）");
            event.registrar().register(tw.exilecore.combat.DeathRecap.hitsCommand(this), "ExileCore 最近受到的傷害是誰打的"); // 2026-10-30
            event.registrar().register(tw.exilecore.combat.CombatHud.command(this), "ExileCore 這一段戰鬥每個技能打了多少（分傷害類型）");
            event.registrar().register(tw.exilecore.ladder.LadderModule.command(this), "ExileCore 排行榜（等級、地圖、不死）");
            event.registrar().register(tw.exilecore.discord.DiscordModule.command(this), "ExileCore 客服單（Discord）");
            event.registrar().register(tw.exilecore.pvp.PvpModule.command(this), "ExileCore PvP 競技場");
            event.registrar().register(tw.exilecore.guide.GuideService.command(this), "ExileCore 新手引導");
            event.registrar().register(tw.exilecore.gui.CodexMenu.command(this), "ExileCore 圖鑑（傳奇、技能與輔助寶石、詞綴）");
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.gui.CommunityLinks.commands(this)) {
                event.registrar().register(node, "ExileCore Discord 與贊助商城的連結");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : SkillCommands.build(this)) {
                event.registrar().register(node, "ExileCore 技能面板");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : LootModule.commands(this)) {
                event.registrar().register(node, "ExileCore 選單、隨身背包、掉落過濾、垃圾桶");
            }
            event.registrar().register(MapCommand.atlas(this), "ExileCore 星圖");
            event.registrar().register(tw.exilecore.map.MapSpectators.command(this), "ExileCore 離開觀戰（極境、金庫之主死掉後）");
            for (final LiteralCommandNode<CommandSourceStack> node : PartyModule.commands(this)) {
                event.registrar().register(node, "ExileCore 隊伍");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.guild.GuildModule.commands(this)) {
                event.registrar().register(node, "ExileCore 公會（建立、邀請、公會頻道、公會倉庫、排行）");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : TradeModule.commands(this)) {
                event.registrar().register(node, "ExileCore 面對面交易");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : MarketModule.commands(this)) {
                event.registrar().register(node, "ExileCore 市集與通貨交易所");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.quest.QuestCommand.build(this)) {
                event.registrar().register(node, "ExileCore 任務日誌");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.bounty.BountyCommand.build(this)) {
                event.registrar().register(node, "ExileCore 委託板（每日委託、每週回響、功勳兌換）");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.sponsor.SponsorModule.commands(this)) {
                event.registrar().register(node, "ExileCore 贊助（星幣、月卡、全服加倍、外觀特效、抽獎箱）");
            }
            event.registrar().register(tw.exilecore.sponsor.SponsorModule.emoteCommand(this, "emote"), "ExileCore 動作（打開選單、直接做一個動作）",
                    List.of("em")); // 2026-10
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.event.EventModule.commands(this)) {
                event.registrar().register(node, "ExileCore 每月活動");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.life.LifeModule.commands(this)) {
                event.registrar().register(node, "ExileCore 生活玩法（星骸之海）");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.festival.FestivalModule.commands(this)) {
                event.registrar().register(node, "ExileCore 節慶活動（萬燈夜）");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.ascendancy.AscendancyCommand.build(this)) {
                event.registrar().register(node, "ExileCore 昇華與試煉");
            }
            for (final LiteralCommandNode<CommandSourceStack> node : tw.exilecore.chat.ChatModule.commands(this)) {
                event.registrar().register(node, "ExileCore 私訊（角色名稱或玩家 ID）");
            }
            event.registrar().register(tw.exilecore.admin.AdminModule.claimCommand(this), "ExileCore 領取管理員發的物資");
        });

        tw.exilecore.hook.TooltipHook.start(this); // 2026-10：物品的「對你的技能」提示（要裝 PacketEvents）
        tw.exilecore.hook.CullGlow.start(this); // 2026-10-28 照 PoE2 斷魂掌：可撲殺的敵人只在你的畫面上發光（要裝 PacketEvents，沒裝照舊紅點）
        new tw.exilecore.music.MusicService(this).start(); // 2026-10：主世界、地圖、Boss 的背景音樂

        getSLF4JLogger().info("ExileCore {} 已啟用（Minecraft {}，Java {}）",
                getPluginMeta().getVersion(), getServer().getMinecraftVersion(), Runtime.version().feature());
    }

    @Override
    public void onDisable() {
        tw.exilecore.hook.TooltipHook.stop();
        tw.exilecore.hook.CullGlow.stop();
        if (modules != null) {
            modules.disableAll();
        }
        tw.exilecore.core.config.YamlNode.overlay(null);
    }

    private void registerModules() {
        final CharacterConfig characterConfig = new CharacterConfig();
        this.itemModule = new ItemModule(this, random);
        this.currencyModule = new CurrencyModule(this);
        this.worldModule = new WorldModule(this);
        this.passiveModule = new PassiveModule(this);
        this.players = new PlayerStateService(this, characterConfig);
        this.characters = new CharacterService(this, players, characterConfig);
        this.characterMenus = new CharacterMenus(this);
        this.chatPrompts = new ChatPrompts(this);
        this.dialogs = new Dialogs(this);
        this.sidebar = new tw.exilecore.gui.Sidebar(this);
        this.personalDrops = new tw.exilecore.monster.PersonalDrops(this);
        this.economyModule = new EconomyModule(this);
        this.npcModule = new NpcModule(this);
        this.monsterModule = new MonsterModule(this, new MonsterConfig(), new DropTable());
        this.zoneModule = new ZoneModule(this);
        this.stashModule = new StashModule(this);
        this.salvageModule = new SalvageModule(this);
        this.combatModule = new CombatModule(this);
        this.skillModule = new SkillModule(this);
        this.flaskModule = new FlaskModule(this);
        this.lootModule = new LootModule(this);
        this.ailmentModule = new AilmentModule(this);
        this.mapModule = new MapModule(this);
        this.partyModule = new PartyModule(this);
        this.guildModule = new tw.exilecore.guild.GuildModule(this);
        this.tradeModule = new TradeModule(this);
        this.marketModule = new MarketModule(this);
        this.vendorModule = new VendorModule(this);
        this.opsModule = new OpsModule(this);
        this.webModule = new WebModule(this);
        this.ascendancyModule = new tw.exilecore.ascendancy.AscendancyModule(this);
        this.questModule = new tw.exilecore.quest.QuestModule(this);
        this.bountyModule = new tw.exilecore.bounty.BountyModule(this);
        this.sponsorModule = new tw.exilecore.sponsor.SponsorModule(this);
        this.eventModule = new tw.exilecore.event.EventModule(this);
        this.worldEventModule = new tw.exilecore.worldevent.WorldEventModule(this);
        this.festivalModule = new tw.exilecore.festival.FestivalModule(this);
        this.lifeModule = new tw.exilecore.life.LifeModule(this);
        this.chatModule = new tw.exilecore.chat.ChatModule(this);
        this.trainingModule = new tw.exilecore.training.TrainingModule(this);
        this.ladderModule = new tw.exilecore.ladder.LadderModule(this);
        this.discordModule = new tw.exilecore.discord.DiscordModule(this);
        this.guideModule = new tw.exilecore.guide.GuideModule(this);
        this.pvpModule = new tw.exilecore.pvp.PvpModule(this);
        this.heistModule = new tw.exilecore.heist.HeistModule(this);
        this.cardModule = new tw.exilecore.card.CardModule(this);
        this.antiCheatModule = new tw.exilecore.anticheat.AntiCheatModule(this);
        this.adminModule = new tw.exilecore.admin.AdminModule(this);

        modules.register(new CoreModule(this, database));
        modules.register(itemModule);                       // 裝備：基底、詞綴、稀有度、生成、鑑定、品質
        modules.register(currencyModule);                   // 通貨與改造
        modules.register(worldModule);                      // 世界觀《星骸》：伺服器列表說明、登場旁白、劇情世界
        modules.register(passiveModule);                    // 天賦樹資料
        modules.register(new CharacterModule(this, characterConfig, players, characters, chatPrompts)); // 職業、經驗、多角色、持久化
        modules.register(economyModule);                    // 帳號共用的金幣、通貨的價值與手續費、交易紀錄
        modules.register(npcModule);                        // 營地 NPC
        modules.register(monsterModule);                    // 怪物等級、稀有度、詞綴、掉落、MythicMobs 橋接
        modules.register(zoneModule);                       // 區域、刷怪群、檢查點、區域 Boss
        modules.register(stashModule);                      // 帳號倉庫
        modules.register(salvageModule);                    // 拆解台
        modules.register(combatModule);                     // 接管原版傷害、傷害數字
        modules.register(skillModule);                      // 施放、技能面板、投射物、技能腳本引擎、寶石
        modules.register(flaskModule);                      // 生命與魔力藥劑
        modules.register(lootModule);                       // 掉落過濾、自動撿取、隨身背包、選單
        modules.register(ailmentModule);                    // 元素異常（點燃、冰緩、凍結、感電）
        modules.register(mapModule);                        // 地圖、地圖裝置、星圖、終局
        modules.register(trainingModule);                   // 營地的訓練假人與練習場（地圖模組之後：首領假人的等級跟最高階地圖）
        modules.register(partyModule);                      // 組隊：隊伍、掉落分份、經驗分享、人多時怪物變硬
        modules.register(guildModule);                      // 公會：階級、公會頻道、等級與加成、公會倉庫、排行（角色、經濟、怪物之後）
        modules.register(tradeModule);                      // 面對面交易
        modules.register(marketModule);                     // 市集（商店頁）與通貨交易所
        modules.register(vendorModule);                     // 商人與賭博
        modules.register(ascendancyModule);                 // 試煉、昇華職業與昇華天賦、分身獎勵（任務模組之前：完成試煉的目標）
        modules.register(questModule);                      // 劇情與任務：主線、支線、劇情人物、任務追蹤、永久獎勵
        modules.register(bountyModule);                     // 每日委託、每週回響、功勳與功勳兌換（帳號共用）
        modules.register(sponsorModule);                    // 贊助：星幣、累積贊助、月卡、全服加倍、外觀特效、抽獎箱、登入天數（委託模組之後：帳號進度）
        modules.register(eventModule);                      // 每月活動：主題輪替、每月挑戰、限定稱號（委託模組之後：進度與稱號）
        modules.register(worldEventModule);                 // 不定時活動（開服活動）：世界 Boss、入侵怪潮、素材兌換（委託模組之後：稱號）
        modules.register(lifeModule);                       // 生活玩法「星骸之海」（2026-10）：海域、木筏、撈取、精力、生活等級（委託模組之後：帳號進度）
        modules.register(festivalModule);                   // 節慶活動（2026-10 萬燈夜）：幽燈墓園、點燈、南瓜王、糖果、兌換（委託、贊助之後：稱號、時裝）
        modules.register(chatModule);                       // 角色名稱（TAB、聊天）、私訊、聊天裡的物品連結（要在昇華、委託之後：TAB 的稱號）
        modules.register(ladderModule);                     // 排行榜（等級、地圖、不死）
        modules.register(guideModule);                      // 新手引導（側欄最上面；要在任務、技能、藥劑之後）
        modules.register(pvpModule);                        // PvP 競技場（世界、角色、戰鬥、隊伍之後）
        modules.register(heistModule);
        modules.register(cardModule);                       // 星諭卡（2026-10-19）：集滿一套換獎勵、星諭牌堆、賭卡（通貨、物品、倉庫、掉落、地圖之後）                      // 劫盜：合約、大劫盜、隱匿詞綴的揭露（地圖、怪物、物品、通貨之後）
        modules.register(antiCheatModule);                  // 防作弊：移動速度、飛行、懸空、瞬移（只管改遊戲數值那一類）
        modules.register(opsModule);                        // 維運：效能、備份、物品追查
        modules.register(webModule);                        // 內建網頁伺服器：介面資源包、網頁天賦規劃器
        modules.register(adminModule);                      // 管理網頁：發放物資、改遊戲設定、公告、維護模式（網頁伺服器之後）
        modules.register(discordModule);                    // 聊天與 Discord 同步（最後啟用、最先停用：開服、關服公告）
    }

    private void registerPermissions() {
        final PluginManager pluginManager = getServer().getPluginManager();
        if (pluginManager.getPermission(PERMISSION_ADMIN) == null) {
            pluginManager.addPermission(new Permission(PERMISSION_ADMIN, "ExileCore 管理指令", PermissionDefault.OP));
        }
    }

    /** 重新讀取 config.yml 與資料檔（含管理網頁改的設定），並通知所有模組。 */
    public void reload() {
        reloadConfig();
        final String overrideProblem = overrides.reload();
        if (overrideProblem != null) {
            getSLF4JLogger().error(overrideProblem);
        }
        applyConfigOverrides();
        this.settings = Settings.load(getConfig());
        this.text = new Text(settings.prefix());
        modules.reloadAll();
    }

    /** 把管理網頁改的 config.yml 的值蓋到記憶體裡的設定上（不寫回 config.yml）。 */
    private void applyConfigOverrides() {
        for (final java.util.Map.Entry<String, Object> entry : overrides.config().entrySet()) {
            getConfig().set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 管理網頁改了（或恢復了）config.yml 的一個鍵：馬上改記憶體裡的設定。value = null 時回到 config.yml 檔案裡的值
     * （檔案也沒寫就拿掉，讀的地方用預設值）。
     */
    public void applyConfigValue(final String key, final Object value) {
        if (value != null) {
            getConfig().set(key, value);
            return;
        }
        final java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        final Object fileValue = file.exists()
                ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file).get(key) : null;
        getConfig().set(key, fileValue instanceof org.bukkit.configuration.ConfigurationSection ? null : fileValue);
    }

    /** 管理網頁改的設定（admin-overrides.yml）。 */
    public tw.exilecore.admin.AdminOverrides overrides() {
        return overrides;
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

    /** 效能計時（/ec perf）。 */
    public Perf perf() {
        return perf;
    }

    /** 定時工作（主執行緒），順便計時給 /ec perf 看。 */
    public BukkitTask repeat(final String name, final Runnable task, final long delay, final long period) {
        return getServer().getScheduler().runTaskTimer(this, () -> perf.time(name, task), delay, period);
    }

    public ItemService items() {
        return itemModule.service();
    }

    public CurrencyService currencies() {
        return currencyModule.service();
    }

    public PassiveModule passives() {
        return passiveModule;
    }

    public PlayerStateService players() {
        return players;
    }

    public CharacterService characters() {
        return characters;
    }

    public CharacterMenus characterMenus() {
        return characterMenus;
    }

    public ChatPrompts chatPrompts() {
        return chatPrompts;
    }

    /** 跳出來的輸入框（取代在聊天打字）。 */
    /**
     * 掉在地上、有主人的東西（個人掉落、自己丟的、背包放不下掉出來的、金幣）：誰看得到、誰撿得到。
     * 一定有（不跟著怪物模組），怪物資料讀不進來時自己丟的東西也照樣只有自己看得到。
     */
    public tw.exilecore.monster.PersonalDrops personalDrops() {
        return personalDrops;
    }

    public Dialogs dialogs() {
        return dialogs;
    }

    /** 畫面右邊的側欄：任務追蹤與「最近撿到」。 */
    public tw.exilecore.gui.Sidebar sidebar() {
        return sidebar;
    }

    public GoldService gold() {
        return economyModule.gold();
    }

    /** 經濟（金幣、手續費、付款、交易紀錄）。 */
    public EconomyModule economy() {
        return economyModule;
    }

    /** 面對面交易；模組沒啟用時為 null。 */
    public TradeService trade() {
        return tradeModule == null ? null : tradeModule.service();
    }

    /** 市集與通貨交易所；模組沒啟用時為 null。 */
    public MarketService market() {
        return marketModule == null ? null : marketModule.service();
    }

    /** 商人與賭博；模組沒啟用時為 null。 */
    public VendorService vendor() {
        return vendorModule == null ? null : vendorModule.service();
    }

    /** 內建網頁伺服器（介面資源包、天賦規劃器）；模組沒啟用時為 null。 */
    public WebModule web() {
        return webModule != null && modules.isEnabled(webModule.id()) ? webModule : null;
    }

    /** 維運（備份）；模組沒啟用時為 null。 */
    public OpsModule ops() {
        return opsModule;
    }

    /** 管理網頁（發放物資、改設定、公告、維護模式）；模組沒啟用時為 null。 */
    public tw.exilecore.admin.AdminModule admin() {
        return adminModule != null && modules.isEnabled(adminModule.id()) ? adminModule : null;
    }

    public NpcService npcs() {
        return npcModule.service();
    }

    public MonsterService monsters() {
        return monsterModule.service();
    }

    public MonsterModule monsterModule() {
        return monsterModule;
    }

    /** MythicMobs 橋接；沒裝時什麼都不做。 */
    public MythicHook mythic() {
        return monsterModule == null ? NoMythic.INSTANCE : monsterModule.mythic();
    }

    /** 區域；模組沒啟用時為 null。 */
    public ZoneService zones() {
        return zoneModule == null ? null : zoneModule.service();
    }

    public ZoneModule zoneModule() {
        return zoneModule;
    }

    /** 倉庫；模組沒啟用時為 null。 */
    public StashService stash() {
        return stashModule == null ? null : stashModule.service();
    }

    /** 世界觀文字（MOTD、登場旁白）。 */
    public WorldModule world() {
        return worldModule;
    }

    /** 戰鬥（技能命中的傷害管道）。 */
    public CombatModule combat() {
        return combatModule;
    }

    /** 技能；模組沒啟用時為 null。 */
    public SkillService skills() {
        return skillModule == null ? null : skillModule.service();
    }

    /** 藥劑；模組沒啟用時為 null。 */
    public FlaskService flasks() {
        return flaskModule == null ? null : flaskModule.service();
    }

    /** 掉落（過濾器、自動撿取、隨身背包）；模組沒啟用時為 null。 */
    public LootService loot() {
        return lootModule == null ? null : lootModule.service();
    }

    /** 元素異常；模組沒啟用時為 null。 */
    public AilmentService ailments() {
        return ailmentModule == null ? null : ailmentModule.service();
    }

    /** 地圖；模組沒啟用時為 null。 */
    public MapService maps() {
        return mapModule == null ? null : mapModule.service();
    }

    /** 別人看到的名字：角色名稱（還沒選角色、聊天模組沒啟用時是玩家 ID）。 */
    public String displayName(final org.bukkit.entity.Player player) {
        final tw.exilecore.chat.ChatService chat = chat();
        return chat == null ? player.getName() : chat.display(player);
    }

    /** 營地的訓練假人；模組沒啟用時為 null。 */
    public tw.exilecore.training.TrainingService training() {
        return trainingModule == null ? null : trainingModule.service();
    }

    /** 新手引導；模組沒啟用時為 null。 */
    public tw.exilecore.guide.GuideService guide() {
        return guideModule == null ? null : guideModule.service();
    }

    /** Discord 同步；模組沒啟用或 config.yml 沒開時為 null。 */
    public tw.exilecore.discord.DiscordBridge discord() {
        return discordModule == null ? null : discordModule.bridge();
    }

    /** 排行榜；模組沒啟用時為 null。 */
    public tw.exilecore.ladder.LadderService ladder() {
        return ladderModule == null ? null : ladderModule.service();
    }

    /** PvP 競技場；模組沒啟用時為 null。 */
    /** 劫盜；模組沒啟用時為 null。 */
    public tw.exilecore.heist.HeistService heists() {
        return heistModule == null ? null : heistModule.service();
    }

    public tw.exilecore.pvp.PvpService pvp() {
        return pvpModule == null ? null : pvpModule.service();
    }

    /** 聊天：角色名稱、私訊、物品連結；模組沒啟用時為 null。 */
    public tw.exilecore.chat.ChatService chat() {
        return chatModule == null ? null : chatModule.service();
    }

    /** 隊伍；模組沒啟用時為 null。 */
    public PartyService party() {
        return partyModule == null ? null : partyModule.service();
    }

    /** 公會；模組沒啟用時為 null。 */
    public tw.exilecore.guild.GuildService guilds() {
        return guildModule == null ? null : guildModule.service();
    }

    /** 劇情與任務；模組沒啟用時為 null。 */
    public tw.exilecore.quest.QuestService quests() {
        return questModule == null ? null : questModule.service();
    }

    /** 每日委託、每週回響、功勳兌換；模組沒啟用時為 null。 */
    /** 防作弊（模組沒啟用時 null）。 */
    public tw.exilecore.anticheat.AntiCheatService antiCheat() {
        return antiCheatModule == null ? null : antiCheatModule.service();
    }

    /** 贊助（模組沒啟用時 null）。 */
    public tw.exilecore.sponsor.SponsorService sponsors() {
        return sponsorModule == null ? null : sponsorModule.service();
    }

    public tw.exilecore.bounty.BountyService bounties() {
        return bountyModule == null ? null : bountyModule.service();
    }

    /** 不定時活動（開服活動）；模組沒啟用時為 null。 */
    public tw.exilecore.worldevent.WorldEventService worldEvents() {
        return worldEventModule == null ? null : worldEventModule.service();
    }

    /** 生活玩法「星骸之海」（2026-10）；模組沒啟用時為 null。 */
    public tw.exilecore.life.LifeService life() {
        return lifeModule == null ? null : lifeModule.service();
    }

    /** 節慶活動（2026-10 萬燈夜）；模組沒啟用時為 null。 */
    /** 星諭卡（2026-10-19）；模組沒啟用時為 null。 */
    public tw.exilecore.card.CardService cards() {
        return cardModule == null ? null : cardModule.service();
    }

    public tw.exilecore.festival.FestivalService festival() {
        return festivalModule == null ? null : festivalModule.service();
    }

    /** 每月活動；模組沒啟用時為 null。 */
    public tw.exilecore.event.MonthlyEventService events() {
        return eventModule == null ? null : eventModule.service();
    }

    /** 試煉、昇華、分身獎勵；模組沒啟用時為 null。 */
    public tw.exilecore.ascendancy.AscendancyService ascendancy() {
        return ascendancyModule == null ? null : ascendancyModule.service();
    }

    /** 拆解台設定；模組沒啟用時為 null。 */
    public SalvageConfig salvage() {
        return salvageModule == null ? null : salvageModule.config();
    }
}
