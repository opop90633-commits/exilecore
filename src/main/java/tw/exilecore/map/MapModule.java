package tw.exilecore.map;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.util.List;

/**
 * map 模組（階段 4）：地圖物品、地圖裝置與傳送門、程式生成的地圖實例、地圖怪與 Boss、星圖、終局。
 * 詳細的玩法說明在 data/maps.yml 的開頭。
 */
public final class MapModule implements Module {

    private final ExileCore plugin;
    private final MapConfig config = new MapConfig();
    private MapService service;
    private BukkitTask task;

    public MapModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "map";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.monsters() == null || plugin.items() == null || plugin.zones() == null) {
            throw new IllegalStateException("怪物、物品或區域模組沒有啟用（看上面的錯誤訊息），地圖模組無法運作");
        }
        load();
        final MapService created = new MapService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new MapListener(plugin, created), plugin);
        plugin.getServer().getPluginManager().registerEvents(created.events(), plugin); // 地圖事件：寶箱、流寇
        plugin.getServer().getPluginManager().registerEvents(created.events().rituals(), plugin); // 祭壇：右鍵、儀式的怪
        plugin.getServer().getPluginManager().registerEvents(created.events().delirium(), plugin); // 幻霧：霧裡殺的怪
        plugin.getServer().getPluginManager().registerEvents(created.events().twists(), plugin); // 地圖變化：岔路、詛咒寶匣、隱藏房
        plugin.getServer().getPluginManager().registerEvents(created.bossLog(), plugin);
        plugin.getServer().getPluginManager().registerEvents(created.spectators(), plugin); // 極境、金庫之主死掉後觀戰 // 終局 Boss 開打一分鐘的傷害紀錄
        plugin.monsters().addAugmenter(created::augment);
        plugin.monsters().addLevelSource(created::levelAt);
        plugin.monsters().addInheritedTag(created.instanceKey());
        plugin.players().addSituationalSource(created::playerModifiers); // 地圖詞綴：網頁計算器的快照不記
        created.start();
        service = created;
        task = plugin.repeat("map", created::tick, 1, 1);
        plugin.getSLF4JLogger().info("地圖：{} 張（T1–T{}，怪物等級 {}–{}），{}終局；地圖世界 {}", config.maps().size(), config.tiers().max(),
                config.tiers().level(1), config.tiers().topLevel(), config.finalMap() == null ? "沒有" : "有",
                created.world() == null ? "沒有載入" : created.world().getName());
    }

    private void load() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        final List<String> problems = new java.util.ArrayList<>(config.validate(plugin.items().repository()));
        if (plugin.skills() != null) {
            problems.addAll(config.bossProblems(plugin.skills().config().bossSkills()));
        }
        // 巔峰（2026-10）：碎片的通貨要存在；額外掉落的通貨與輔助要存在
        for (final MapConfig.MapDef map : config.pinnacles()) {
            final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
            for (final MapConfig.FragmentSource fragment : pinnacle.fragments()) {
                if (plugin.currencies().repository().get(fragment.currency()) == null) {
                    problems.add("maps.yml：巔峰 " + map.id() + " 的碎片 " + fragment.currency() + " 不在 currencies.yml 裡（碎片不會掉、巔峰開不了）");
                }
            }
            // 2026-10-18：事件巔峰的入場券要存在，而且要有某種事件掉它（map_events.yml 的 ticket）
            if (pinnacle.ticket() != null) {
                if (plugin.currencies().repository().get(pinnacle.ticket()) == null) {
                    problems.add("maps.yml：事件巔峰 " + map.id() + " 的入場券 " + pinnacle.ticket() + " 不在 currencies.yml 裡（巔峰開不了）");
                } else if (service != null && service.ticketEventSource(pinnacle.ticket()) == null) { // 第一次載入時 service 還沒建（重讀時才查）
                    problems.add("maps.yml：事件巔峰 " + map.id() + " 的入場券 " + pinnacle.ticket() + " 沒有任何地圖事件掉（map_events.yml 的 ticket）");
                }
            }
            for (final MapConfig.ExtraDrop extra : pinnacle.drops().extra()) {
                if ("support".equals(extra.kind())) {
                    if (plugin.skills() != null && plugin.skills().config().support(extra.id()) == null) {
                        problems.add("maps.yml：巔峰 " + map.id() + " 的掉落寫了 supports.yml 沒有的輔助寶石 " + extra.id() + "（不會掉）");
                    }
                } else if ("skill".equals(extra.kind())) {
                    if (plugin.skills() != null && plugin.skills().config().skill(extra.id()) == null) {
                        problems.add("maps.yml：巔峰 " + map.id() + " 的掉落寫了 skills.yml 沒有的技能寶石 " + extra.id() + "（不會掉）");
                    }
                } else if (plugin.currencies().repository().get(extra.id()) == null) {
                    problems.add("maps.yml：巔峰 " + map.id() + " 的掉落寫了 currencies.yml 沒有的通貨 " + extra.id() + "（不會掉）");
                }
            }
        }
        // 極境：開門的通貨要存在；額外掉落的通貨要存在
        for (final MapConfig.MapDef map : config.finals()) {
            final MapConfig.Uber uber = config.uber(map.id());
            if (uber == null) {
                continue;
            }
            if (plugin.currencies().repository().get(uber.key()) == null) {
                problems.add("maps.yml：" + map.id() + " 的極境要用 currencies.yml 沒有的 " + uber.key() + " 開門（極境開不了）");
            }
            for (final MapConfig.ExtraDrop extra : uber.drops().extra()) {
                if ("support".equals(extra.kind())) {
                    if (plugin.skills() != null && plugin.skills().config().support(extra.id()) == null) {
                        problems.add("maps.yml：" + map.id() + " 的極境掉落寫了 supports.yml 沒有的輔助寶石 " + extra.id() + "（不會掉）");
                    }
                } else if ("skill".equals(extra.kind())) {
                    if (plugin.skills() != null && plugin.skills().config().skill(extra.id()) == null) {
                        problems.add("maps.yml：" + map.id() + " 的極境掉落寫了 skills.yml 沒有的技能寶石 " + extra.id() + "（不會掉）");
                    }
                } else if (plugin.currencies().repository().get(extra.id()) == null) {
                    problems.add("maps.yml：" + map.id() + " 的極境掉落寫了 currencies.yml 沒有的 " + extra.id() + "（不會掉）");
                }
            }
        }
        // 護盾結晶要是原版生物（生不出來的話護盾會取消）
        for (final MapConfig.MapDef map : config.all()) {
            final List<MapConfig.Phase> phases = new java.util.ArrayList<>(map.boss().phases());
            final MapConfig.Uber uber = config.uber(map.id());
            if (uber != null) {
                phases.addAll(uber.phases());
            }
            for (final MapConfig.Phase phase : phases) {
                if (phase.shield() != null && !livingType(phase.shield().type())) {
                    problems.add("maps.yml：" + map.id() + " 的護盾結晶 " + phase.shield().type() + " 不是原版的生物（護盾會取消）");
                }
            }
        }
        for (final String problem : problems) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    private static boolean livingType(final String name) {
        try {
            final org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            return type.isAlive() && type.isSpawnable() && type.getEntityClass() != null
                    && org.bukkit.entity.LivingEntity.class.isAssignableFrom(type.getEntityClass());
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.spawner().reload();
            service.reloadEvents();
            service.reloadTree();
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

    /** 地圖；還沒啟用時為 null。 */
    public MapService service() {
        return service;
    }
}
