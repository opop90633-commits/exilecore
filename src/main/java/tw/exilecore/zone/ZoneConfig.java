package tw.exilecore.zone;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.skill.BossSkillUse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * data/zones.yml：區域的遊戲內容（名稱、等級範圍、怪物、Boss）與刷怪、Boss 的共用設定。
 * 區域在世界裡的位置不在這裡（見 {@link ZoneLayout}）。純資料，不依賴 Bukkit。
 */
public final class ZoneConfig {

    /** 刷怪清單的一項：原版生物（ZOMBIE）或 MythicMobs 怪物（mythic:代號）。 */
    public record SpawnEntry(String id, int weight) {
        public boolean isMythic() {
            return id.startsWith("mythic:");
        }

        public String mythicId() {
            return isMythic() ? id.substring("mythic:".length()) : null;
        }
    }

    /**
     * 區域 Boss。
     *
     * @param name             顯示名稱
     * @param mythicId         MythicMobs 怪物代號（可為 null）；沒裝 MythicMobs 或找不到時用 vanillaType
     * @param vanillaType      原版生物
     * @param lifeMultiplier   在傳奇稀有度之上再乘的生命倍率
     * @param damageMultiplier 同上，傷害
     * @param scale            原版體型倍率（1 = 原本大小）
     * @param mods             怪物詞綴
     * @param intro            登場台詞（「」開頭是 Boss 說的話，其他是旁白；可為 null）
     * @param defeat           倒下時的旁白（可為 null）
     * @param skills           招式組（data/boss_skills.yml 的代號；空的 = 用這種生物的預設招式組）
     */
    public record BossDefinition(String name, String mythicId, String vanillaType, double lifeMultiplier,
                                 double damageMultiplier, double scale, List<String> mods, String intro, String defeat,
                                 List<BossSkillUse> skills) {
        public BossDefinition {
            mods = List.copyOf(mods);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        /** 沒寫招式組（用這種生物的預設）。 */
        public BossDefinition(final String name, final String mythicId, final String vanillaType, final double lifeMultiplier,
                              final double damageMultiplier, final double scale, final List<String> mods, final String intro, final String defeat) {
            this(name, mythicId, vanillaType, lifeMultiplier, damageMultiplier, scale, mods, intro, defeat, List.of());
        }
    }

    /** 一個區域。icon 是傳送選單裡的圖示材質，intro 是每個角色第一次進來時的旁白（都可為 null）。 */
    public record ZoneDefinition(String id, String name, boolean town, int minLevel, int maxLevel,
                                 List<SpawnEntry> monsters, BossDefinition boss, String icon, String intro) {
        public ZoneDefinition {
            monsters = List.copyOf(monsters);
        }
    }

    /** 一種怪物群。size 對稀有群是小弟數量。 */
    public record PackSettings(int weight, int minSize, int maxSize) {
    }

    /** 刷怪設定。 */
    public record SpawnerSettings(int intervalTicks, int minDistance, int maxDistance, int maxNearby,
                                  double nearbyRadius, int worldCap, PackSettings normal, PackSettings magic,
                                  PackSettings rare) {
    }

    /** Boss 設定。 */
    public record BossSettings(double radius, int respawnSeconds, int resetSeconds, int firstKillPoints) {
    }

    private final Map<String, ZoneDefinition> zones = new LinkedHashMap<>();
    private SpawnerSettings spawner = defaultSpawner();
    private BossSettings boss = new BossSettings(20, 90, 60, 1);
    private ZoneFatigue.Settings fatigue = ZoneFatigue.Settings.defaults();

    private static SpawnerSettings defaultSpawner() {
        return new SpawnerSettings(40, 16, 28, 14, 32, 250,
                new PackSettings(70, 3, 5), new PackSettings(24, 2, 4), new PackSettings(6, 2, 3));
    }

    public void load(final Path dataFolder) throws IOException {
        zones.clear();
        spawner = defaultSpawner();
        fatigue = ZoneFatigue.Settings.defaults();
        final Path file = dataFolder.resolve("zones.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode zonesNode = root.node("zones");
        for (final String id : zonesNode.keys()) {
            zones.put(id, parseZone(id, zonesNode.node(id)));
        }

        final YamlNode spawnerNode = root.node("spawner");
        final int[] distance = spawnerNode.has("distance") ? spawnerNode.range("distance") : new int[] {16, 28};
        final YamlNode packs = spawnerNode.node("packs");
        spawner = new SpawnerSettings(
                Math.max(10, spawnerNode.integer("interval_ticks", 40)),
                Math.max(4, Math.min(distance[0], distance[1])), Math.max(6, Math.max(distance[0], distance[1])),
                Math.max(1, spawnerNode.integer("max_nearby", 14)),
                Math.max(8, spawnerNode.decimal("nearby_radius", 32)),
                Math.max(10, spawnerNode.integer("world_cap", 250)),
                parsePack(packs.node("normal"), "size", new PackSettings(70, 3, 5)),
                parsePack(packs.node("magic"), "size", new PackSettings(24, 2, 4)),
                parsePack(packs.node("rare"), "minions", new PackSettings(6, 2, 3)));

        final YamlNode bossNode = root.node("boss");
        boss = new BossSettings(
                Math.max(4, bossNode.decimal("radius", 20)),
                Math.max(0, bossNode.integer("respawn_seconds", 90)),
                Math.max(5, bossNode.integer("reset_seconds", 60)),
                Math.max(0, bossNode.integer("first_kill_points", 1)));

        final YamlNode fatigueNode = root.node("fatigue");
        final ZoneFatigue.Settings defaults = ZoneFatigue.Settings.defaults();
        final double floor = fatigueNode.decimal("floor", defaults.floor());
        if (floor < 0 || floor > 1) {
            throw new IllegalArgumentException(fatigueNode.path() + ".floor：要在 0 到 1 之間");
        }
        fatigue = new ZoneFatigue.Settings(fatigueNode.bool("enabled", defaults.enabled()),
                Math.max(0, fatigueNode.decimal("free", defaults.free())), Math.max(0, fatigueNode.decimal("span", defaults.span())), floor,
                Math.max(0, fatigueNode.decimal("recover_per_minute", defaults.recoverPerMinute())),
                Math.max(0, fatigueNode.decimal("boss_weight", defaults.bossWeight())));
    }

    private static ZoneDefinition parseZone(final String id, final YamlNode node) {
        final String name = node.requireString("name");
        final boolean town = node.bool("town", false);
        int min = 1;
        int max = 1;
        if (node.has("level")) {
            final int[] range = node.range("level");
            min = Math.max(1, Math.min(range[0], range[1]));
            max = Math.max(min, Math.max(range[0], range[1]));
        } else if (!town) {
            throw new IllegalArgumentException(node.path() + "：缺少 level: [最低, 最高]");
        }
        final List<SpawnEntry> monsters = new ArrayList<>();
        final YamlNode monstersNode = node.node("monsters");
        for (final String key : monstersNode.keys()) {
            final int weight = monstersNode.integer(key, 0);
            if (weight > 0) {
                final String entry = key.trim();
                monsters.add(new SpawnEntry(entry.startsWith("mythic:") ? entry : entry.toUpperCase(Locale.ROOT), weight));
            }
        }
        BossDefinition bossDefinition = null;
        if (node.has("boss")) {
            final YamlNode bossNode = node.node("boss");
            bossDefinition = new BossDefinition(
                    bossNode.requireString("name"),
                    bossNode.string("mythic", null),
                    bossNode.string("type", "ZOMBIE").trim().toUpperCase(Locale.ROOT),
                    Math.max(0.01, bossNode.decimal("life", 1.0)),
                    Math.max(0.01, bossNode.decimal("damage", 1.0)),
                    Math.max(0.1, Math.min(16, bossNode.decimal("scale", 1.0))),
                    bossNode.strings("mods"),
                    bossNode.string("intro", null),
                    bossNode.string("defeat", null),
                    BossSkillUse.parseList(bossNode, "skills"));
        }
        return new ZoneDefinition(id, name, town, min, max, monsters, bossDefinition, node.string("icon", null),
                node.string("intro", null));
    }

    private static PackSettings parsePack(final YamlNode node, final String sizeKey, final PackSettings def) {
        if (node.keys().isEmpty()) {
            return def;
        }
        final int[] size = node.has(sizeKey) ? node.range(sizeKey) : new int[] {def.minSize(), def.maxSize()};
        return new PackSettings(Math.max(0, node.integer("weight", def.weight())),
                Math.max(0, Math.min(size[0], size[1])), Math.max(0, Math.max(size[0], size[1])));
    }

    public Collection<ZoneDefinition> zones() {
        return zones.values();
    }

    public ZoneDefinition zone(final String id) {
        return zones.get(id);
    }

    /** 不是城鎮的區域，依設定檔順序。 */
    public List<ZoneDefinition> combatZones() {
        final List<ZoneDefinition> list = new ArrayList<>();
        for (final ZoneDefinition zone : zones.values()) {
            if (!zone.town()) {
                list.add(zone);
            }
        }
        return list;
    }

    public SpawnerSettings spawner() {
        return spawner;
    }

    public BossSettings boss() {
        return boss;
    }

    /** 防刷的設定。 */
    public ZoneFatigue.Settings fatigue() {
        return fatigue;
    }
}
