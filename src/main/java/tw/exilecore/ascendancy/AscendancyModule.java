package tw.exilecore.ascendancy;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.map.MapConfig;
import tw.exilecore.module.Module;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.zone.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * ascendancy 模組：試煉、昇華職業與昇華天賦、分身獎勵（data/ascendancy.yml）。
 * 要在區域、怪物、地圖模組之後，任務模組之前啟用（任務的「完成試煉」目標、任務追蹤上的提醒用到它）。
 */
public final class AscendancyModule implements Module, Listener {

    private final ExileCore plugin;
    private final AscendancyConfig config = new AscendancyConfig();
    private AscendancyService service;

    public AscendancyModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "ascendancy";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new AscendancyService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 昇華天賦與分身獎勵：重算屬性表時從角色進度、帳號的角色清單算出來
        plugin.players().addStateModifierSource((player, state) -> service == null ? List.of() : service.modifiers(player, state));
        plugin.players().addPointSource(state -> service == null ? 0 : service.passivePoints(state)); // 2026-10-26：武器大師的額外天賦點
        AscendancyEffects.start(plugin); // 2026-10-26：昇華天賦原本（尚未生效）的機制
        // 2026-10-26：登入、建立、切換角色時檢查昇華天賦的版本（改版了就退點）
        plugin.characters().addAfterApply(player -> {
            if (service != null) {
                service.checkVersion(player);
            }
        });
        // 已經在線上的玩家（/reload）
        service.reloaded();
        // 衝等活動：要等稱號系統（委託模組，在這個模組後面啟用）好了才開始
        final AscendancyService created = service;
        plugin.getServer().getScheduler().runTask(plugin, () -> created.race().start());
        plugin.getSLF4JLogger().info("昇華：{} 個試煉（共 {} 昇華點）、{} 個昇華職業、{} 種職業有分身獎勵", config.trials().size(),
                config.totalPoints(), config.ascendancies().size(), config.legacy().size());
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.reloaded();
            service.race().reloaded();
        }
    }

    @Override
    public void disable() {
    }

    /** 讀 ascendancy.yml 並檢查；有問題就不換（啟用時直接失敗）。 */
    private void load() throws Exception {
        final AscendancyConfig fresh = new AscendancyConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        AscBuild.loadColours(plugin.dataFolder().resolve("data")); // 2026-10-26：寶石顏色（嵌晶）
        final List<String> classes = new ArrayList<>();
        for (final CharacterClass characterClass : CharacterClass.values()) {
            classes.add(characterClass.id());
        }
        // 區域、怪物模組沒啟用時不檢查區域 Boss 與怪物詞綴（試煉開不了，昇華天賦與分身獎勵照常）
        List<String> bossZones = null;
        if (plugin.zones() != null) {
            bossZones = new ArrayList<>();
            for (final Zone zone : plugin.zones().zones()) {
                if (zone.boss() != null) {
                    bossZones.add(zone.id());
                }
            }
        }
        List<String> mods = null;
        if (plugin.monsterModule() != null && plugin.monsterModule().service() != null) {
            mods = new ArrayList<>();
            for (final MonsterConfig.MonsterMod mod : plugin.monsters().config().mods()) {
                mods.add(mod.id());
            }
        }
        final List<String> problems = fresh.validate(classes, bossZones, mods, MapConfig.THEMES, plugin.players().config().levelCap());
        // 純設定的檢查管不到的：圖示要是原版「物品」（方塊型態的打不開選單）、怪物要是原版生物（不然 Boss 生不出來、試煉完成不了）
        for (final AscendancyConfig.Ascendancy ascendancy : fresh.ascendancies()) {
            checkItem(problems, "ascendancies." + ascendancy.id() + ".icon", ascendancy.icon());
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                checkItem(problems, "ascendancies." + ascendancy.id() + ".nodes." + node.id() + ".icon", node.icon());
            }
        }
        for (final AscendancyConfig.Trial trial : fresh.trials()) {
            final String where = "trials." + trial.id();
            for (final tw.exilecore.zone.ZoneConfig.SpawnEntry entry : trial.monsters()) {
                if (!entry.isMythic()) {
                    checkEntity(problems, where + ".monsters", entry.id());
                }
            }
            checkEntity(problems, where + ".boss.type", trial.boss().type());
            for (final MapConfig.Phase phase : trial.boss().phases()) {
                if (phase.summonType() != null) {
                    checkEntity(problems, where + ".boss.phases", phase.summonType());
                }
                if (phase.shield() != null) {
                    checkEntity(problems, where + ".boss.phases.shield.type", phase.shield().type());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("ascendancy.yml 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        // 招式寫錯只是略過（不擋載入）：主控台警告
        if (plugin.skills() != null) {
            final List<String> skillProblems = new ArrayList<>();
            for (final AscendancyConfig.Trial trial : fresh.trials()) {
                MapConfig.bossProblems(skillProblems, "ascendancy.yml：trials." + trial.id() + " 的 Boss", trial.boss(),
                        plugin.skills().config().bossSkills());
            }
            for (final String problem : skillProblems) {
                plugin.getSLF4JLogger().warn(problem);
            }
        }
        config.copyFrom(fresh);
    }

    private static void checkItem(final List<String> problems, final String where, final String name) {
        final org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
        if (material == null || !material.isItem()) {
            problems.add(where + "：" + name + " 不是原版物品");
        }
    }

    private static void checkEntity(final List<String> problems, final String where, final String name) {
        try {
            final org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            if (type.isAlive() && type.isSpawnable() && type.getEntityClass() != null
                    && org.bukkit.entity.LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
                return;
            }
        } catch (final IllegalArgumentException ignored) {
            // 下面記問題
        }
        problems.add(where + "：" + name + " 不是原版的生物（要寫 ZOMBIE 這種，或 mythic:代號）");
    }

    /** 執行期；模組沒啟用時為 null。 */
    public AscendancyService service() {
        return service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }
}
