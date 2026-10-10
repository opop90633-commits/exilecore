package tw.exilecore.zone;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 區域在世界裡的位置（plugins/ExileCore/zone-layout.yml）：範圍、入口、檢查點、Boss 場地。
 * 由 /ec zone 指令寫入，跟遊戲內容的 zones.yml 分開，所以更新插件時不會洗掉你擺好的版面。純資料，不依賴 Bukkit。
 */
public final class ZoneLayout {

    /** 一個點（世界名稱＋座標＋朝向）。 */
    public record Point(String world, double x, double y, double z, float yaw) {
        public double distance2d(final double otherX, final double otherZ) {
            return Math.hypot(x - otherX, z - otherZ);
        }
    }

    /** 長方體範圍（含邊界）。 */
    public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Box of(final String world, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
            return new Box(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        public boolean contains(final String worldName, final double x, final double y, final double z) {
            return world.equals(worldName) && x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }

        /** 從一點到範圍四個角落最遠的水平距離。 */
        public double farthestCorner2d(final double x, final double z) {
            double max = 0;
            for (final int cx : new int[] {minX, maxX}) {
                for (final int cz : new int[] {minZ, maxZ}) {
                    max = Math.max(max, Math.hypot(cx - x, cz - z));
                }
            }
            return max;
        }
    }

    /** 檢查點。 */
    public record Checkpoint(String id, String name, Point point) {
    }

    /** 一個區域的版面。 */
    public static final class Entry {
        private Box box;
        private Point entry;
        private Point arena;
        private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();

        public Box box() {
            return box;
        }

        public void setBox(final Box box) {
            this.box = box;
        }

        /** 傳送進這個區域（或營地）時的落點。 */
        public Point entry() {
            return entry;
        }

        public void setEntry(final Point entry) {
            this.entry = entry;
        }

        /** Boss 場地中心。 */
        public Point arena() {
            return arena;
        }

        public void setArena(final Point arena) {
            this.arena = arena;
        }

        public Collection<Checkpoint> checkpoints() {
            return checkpoints.values();
        }

        public Checkpoint checkpoint(final String id) {
            return checkpoints.get(id);
        }

        public void putCheckpoint(final Checkpoint checkpoint) {
            checkpoints.put(checkpoint.id(), checkpoint);
        }

        public Checkpoint removeCheckpoint(final String id) {
            return checkpoints.remove(id);
        }

        public void clearCheckpoints() {
            checkpoints.clear();
        }
    }

    private final Map<String, Entry> zones = new LinkedHashMap<>();

    public Entry get(final String zoneId) {
        return zones.get(zoneId);
    }

    public Entry getOrCreate(final String zoneId) {
        return zones.computeIfAbsent(zoneId, id -> new Entry());
    }

    public Map<String, Entry> all() {
        return zones;
    }

    public void clear() {
        zones.clear();
    }

    // ---- 讀寫 ----

    public void load(final Path file) throws IOException {
        zones.clear();
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        for (final String zoneId : root.keys()) {
            final YamlNode node = root.node(zoneId);
            final String world = node.requireString("world");
            final Entry entry = new Entry();
            if (node.has("box")) {
                final Object raw = node.raw("box");
                if (!(raw instanceof List<?> list) || list.size() != 6) {
                    throw new IllegalArgumentException(node.path() + ".box：要寫成 [minX, minY, minZ, maxX, maxY, maxZ]");
                }
                final int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = ((Number) list.get(i)).intValue();
                }
                entry.setBox(Box.of(world, v[0], v[1], v[2], v[3], v[4], v[5]));
            }
            if (node.has("entry")) {
                entry.setEntry(point(world, node.node("entry")));
            }
            if (node.has("arena")) {
                entry.setArena(point(world, node.node("arena")));
            }
            final YamlNode checkpoints = node.node("checkpoints");
            for (final String id : checkpoints.keys()) {
                final YamlNode cp = checkpoints.node(id);
                entry.putCheckpoint(new Checkpoint(id, cp.string("name", id), point(world, cp)));
            }
            zones.put(zoneId, entry);
        }
    }

    private static Point point(final String world, final YamlNode node) {
        return new Point(world, node.decimal("x", 0), node.decimal("y", 0), node.decimal("z", 0), (float) node.decimal("yaw", 0));
    }

    public void save(final Path file) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        for (final Map.Entry<String, Entry> zone : zones.entrySet()) {
            final Entry entry = zone.getValue();
            final String world = worldOf(entry);
            if (world == null) {
                continue;
            }
            final Map<String, Object> node = new LinkedHashMap<>();
            node.put("world", world);
            if (entry.box != null) {
                node.put("box", List.of(entry.box.minX(), entry.box.minY(), entry.box.minZ(),
                        entry.box.maxX(), entry.box.maxY(), entry.box.maxZ()));
            }
            if (entry.entry != null) {
                node.put("entry", pointMap(entry.entry));
            }
            if (entry.arena != null) {
                node.put("arena", pointMap(entry.arena));
            }
            if (!entry.checkpoints.isEmpty()) {
                final Map<String, Object> checkpoints = new LinkedHashMap<>();
                for (final Checkpoint checkpoint : entry.checkpoints.values()) {
                    final Map<String, Object> cp = new LinkedHashMap<>();
                    cp.put("name", checkpoint.name());
                    cp.putAll(pointMap(checkpoint.point()));
                    checkpoints.put(checkpoint.id(), cp);
                }
                node.put("checkpoints", checkpoints);
            }
            root.put(zone.getKey(), node);
        }
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setIndent(2);
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# 區域在世界裡的位置。由 /ec zone 指令產生；要手動改也可以，改完 /ec reload。\n");
            new Yaml(options).dump(root, writer);
        }
    }

    private static String worldOf(final Entry entry) {
        if (entry.box != null) {
            return entry.box.world();
        }
        if (entry.entry != null) {
            return entry.entry.world();
        }
        if (entry.arena != null) {
            return entry.arena.world();
        }
        final List<Checkpoint> list = new ArrayList<>(entry.checkpoints.values());
        return list.isEmpty() ? null : list.get(0).point().world();
    }

    private static Map<String, Object> pointMap(final Point point) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", round(point.x()));
        map.put("y", round(point.y()));
        map.put("z", round(point.z()));
        map.put("yaw", round(point.yaw()));
        return map;
    }

    private static double round(final double value) {
        return Math.round(value * 10) / 10.0;
    }
}
