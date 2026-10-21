package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 星圖的生成規則（data/atlas.yml，2026-10-21：程序生成的六角格星圖）。純資料；沒有這個檔案就全部用預設值。
 */
public final class AtlasConfig {

    /** 距離 → 階級範圍。 */
    public record TierBand(int fromDistance, int minTier, int maxTier) {
    }

    public record Tower(int every, int minDistance, int radius, int points) {
    }

    public record Citadels(int every, int minDistance, int points) {
    }

    /**
     * 腐化區。
     *
     * @param chance       每片區域腐化的機率
     * @param patch        一片大約幾格（區域的粗細）
     * @param monsterLevel 怪物等級加成
     * @param pack         怪物群 +%
     * @param rare         稀有怪 +%
     * @param quantity     物品數量 +%
     * @param rarity       稀有度 +%
     */
    public record Corruption(int minDistance, double chance, int patch, int monsterLevel, double pack, double rare, double quantity,
                             double rarity) {
    }

    /**
     * 淨化區（2026-10-21）：跟腐化區一樣一片一片長在星圖上（不會跟腐化區重疊）；難度不變，第一次打倒 Boss 時有機率掉定星石。
     *
     * @param chance         每片區域淨化的機率
     * @param patch          一片大約幾格
     * @param fractureChance 第一次打倒 Boss 時，每個有份的人掉一顆定星石的機率
     */
    public record Purification(int minDistance, double chance, int patch, double fractureChance) {
    }

    public record Unlock(int startRadius, int onComplete, int onTower) {
    }

    /** 天賦點：每完成一格 perCell；完成 softCap 格之後每 afterCapEvery 格才 1 點；每座塔、城塞另外給。 */
    public record Points(int perCell, int softCap, int afterCapEvery, int perTower, int perCitadel) {
    }

    /** 掉落的地圖最高幾階：一開始 start，每完成 perCells 格高一階。 */
    public record DropCap(int start, int perCells) {
    }

    /** 一種地圖在星圖上的規則。 */
    public record MapRule(int minDistance, int weight) {
    }

    private double emptyThreshold = 0.25;
    private int regionScale = 3;
    private List<TierBand> tiers = List.of(new TierBand(1, 1, 2), new TierBand(3, 3, 5), new TierBand(5, 6, 9), new TierBand(8, 10, 13),
            new TierBand(11, 14, 16));
    private double bossChance = 0.15;
    private double bossLife = 1.5;
    private int bossMaps = 2;
    private double bossUnique = 2.0;
    private double hiddenChance = 0.03;
    private Tower tower = new Tower(35, 3, 3, 2);
    private Citadels citadels = new Citadels(150, 8, 2);
    private Corruption corruption = new Corruption(6, 0.2, 12, 1, 30, 50, 20, 30);
    /** 腐化區第一次打倒 Boss 時，每個有份的人掉一顆蝕變石的機率（2026-10-21，corruption.vaal_chance）。 */
    private double vaalChance = 0.35;
    private Purification purification = new Purification(6, 0.12, 12, 0.08);
    private Unlock unlock = new Unlock(1, 1, 3);
    private Points points = new Points(1, 60, 3, 2, 2);
    private DropCap dropCap = new DropCap(4, 2);
    private Map<String, MapRule> maps = Map.of();
    private List<String> problems = List.of();

    /** 讀 data/atlas.yml；沒有就是預設值。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("atlas.yml");
        if (!Files.exists(file)) {
            problems = List.of("atlas.yml 不存在：星圖的生成規則全部用預設值");
            return;
        }
        apply(YamlNode.load(file));
    }

    public static AtlasConfig parse(final YamlNode root) {
        final AtlasConfig config = new AtlasConfig();
        config.apply(root);
        return config;
    }

    private void apply(final YamlNode root) {
        final List<String> found = new ArrayList<>();
        emptyThreshold = clamp(root.decimal("empty_threshold", 0.25), 0, 0.45, "empty_threshold", found);
        regionScale = (int) clamp(root.integer("region_scale", 3), 1, 10, "region_scale", found);
        if (root.has("tiers")) {
            final List<TierBand> bands = new ArrayList<>();
            if (root.raw("tiers") instanceof List<?> rows) {
                for (final Object row : rows) {
                    if (row instanceof List<?> parts && parts.size() >= 3 && parts.get(0) instanceof Number from
                            && parts.get(1) instanceof Number lo && parts.get(2) instanceof Number hi) {
                        bands.add(new TierBand(Math.max(0, from.intValue()), Math.max(1, lo.intValue()), Math.max(1, hi.intValue())));
                    } else {
                        found.add("atlas.yml：tiers 的每一列要寫 [最近距離, 最低階, 最高階]，卻是「" + row + "」");
                    }
                }
            } else {
                found.add("atlas.yml：tiers 要是清單");
            }
            if (!bands.isEmpty()) {
                bands.sort((a, b) -> Integer.compare(a.fromDistance(), b.fromDistance()));
                tiers = List.copyOf(bands);
            }
        }
        bossChance = clamp(root.decimal("boss_chance", 0.15), 0, 1, "boss_chance", found);
        bossLife = clamp(root.decimal("boss_life", 1.5), 1, 10, "boss_life", found);
        bossMaps = (int) clamp(root.integer("boss_maps", 2), 0, 10, "boss_maps", found);
        bossUnique = clamp(root.decimal("boss_unique", 2.0), 1, 10, "boss_unique", found);
        hiddenChance = clamp(root.decimal("hidden_chance", 0.03), 0, 1, "hidden_chance", found);
        final YamlNode t = root.node("tower");
        tower = new Tower((int) clamp(t.integer("every", 35), 5, 1000, "tower.every", found), Math.max(1, t.integer("min_distance", 3)),
                (int) clamp(t.integer("radius", 3), 1, 6, "tower.radius", found), Math.max(0, t.integer("points", 2)));
        final YamlNode c = root.node("citadels");
        citadels = new Citadels((int) clamp(c.integer("every", 150), 10, 10000, "citadels.every", found), Math.max(1, c.integer("min_distance", 8)),
                Math.max(0, c.integer("points", 2)));
        final YamlNode k = root.node("corruption");
        corruption = new Corruption(Math.max(1, k.integer("min_distance", 6)), clamp(k.decimal("chance", 0.2), 0, 1, "corruption.chance", found),
                (int) clamp(k.integer("patch", 12), 1, 100, "corruption.patch", found), Math.max(0, k.integer("monster_level", 1)),
                Math.max(0, k.decimal("pack", 30)), Math.max(0, k.decimal("rare", 50)), Math.max(0, k.decimal("quantity", 20)),
                Math.max(0, k.decimal("rarity", 30)));
        vaalChance = clamp(k.decimal("vaal_chance", 0.35), 0, 1, "corruption.vaal_chance", found);
        final YamlNode pu = root.node("purification");
        purification = new Purification(Math.max(1, pu.integer("min_distance", 6)), clamp(pu.decimal("chance", 0.12), 0, 1, "purification.chance", found),
                (int) clamp(pu.integer("patch", 12), 1, 100, "purification.patch", found),
                clamp(pu.decimal("fracture_chance", 0.08), 0, 1, "purification.fracture_chance", found));
        final YamlNode u = root.node("unlock");
        unlock = new Unlock((int) clamp(u.integer("start_radius", 1), 1, 3, "unlock.start_radius", found),
                (int) clamp(u.integer("on_complete", 1), 1, 3, "unlock.on_complete", found),
                (int) clamp(u.integer("on_tower", 3), 1, 6, "unlock.on_tower", found));
        final YamlNode p = root.node("points");
        points = new Points(Math.max(0, p.integer("per_cell", 1)), Math.max(0, p.integer("soft_cap", 60)), Math.max(1, p.integer("after_cap_every", 3)),
                Math.max(0, p.integer("per_tower", 2)), Math.max(0, p.integer("per_citadel", 2)));
        final YamlNode d = root.node("drop_cap");
        dropCap = new DropCap(Math.max(1, d.integer("start", 4)), Math.max(1, d.integer("per_cells", 2)));
        final Map<String, MapRule> rules = new LinkedHashMap<>();
        final YamlNode all = root.node("maps");
        for (final String id : all.keys()) {
            final YamlNode rule = all.node(id);
            rules.put(id, new MapRule(Math.max(0, rule.integer("min_distance", -1)), Math.max(1, rule.integer("weight", 100))));
        }
        maps = Collections.unmodifiableMap(rules);
        problems = List.copyOf(found);
    }

    private static double clamp(final double value, final double min, final double max, final String key, final List<String> found) {
        if (value < min || value > max) {
            found.add("atlas.yml：" + key + " 要在 " + AtlasTree.number(min) + "–" + AtlasTree.number(max) + " 之間（先夾到範圍內）");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public double emptyThreshold() {
        return emptyThreshold;
    }

    public int regionScale() {
        return regionScale;
    }

    public List<TierBand> tiers() {
        return tiers;
    }

    /** 這個距離的格子是幾階到幾階（[最低, 最高]）。 */
    public int[] tierRange(final int distance) {
        TierBand chosen = tiers.get(0);
        for (final TierBand band : tiers) {
            if (distance >= band.fromDistance()) {
                chosen = band;
            }
        }
        return new int[] {chosen.minTier(), chosen.maxTier()};
    }

    public double bossChance() {
        return bossChance;
    }

    public double bossLife() {
        return bossLife;
    }

    public int bossMaps() {
        return bossMaps;
    }

    public double bossUnique() {
        return bossUnique;
    }

    public double hiddenChance() {
        return hiddenChance;
    }

    public Tower tower() {
        return tower;
    }

    public Citadels citadels() {
        return citadels;
    }

    public Corruption corruption() {
        return corruption;
    }

    public double vaalChance() {
        return vaalChance;
    }

    public Purification purification() {
        return purification;
    }

    public Unlock unlock() {
        return unlock;
    }

    public Points points() {
        return points;
    }

    public DropCap dropCap() {
        return dropCap;
    }

    /** 這種地圖的規則；沒寫的 min_distance 是 -1（照圈數換算）。 */
    public MapRule rule(final String mapId) {
        final MapRule rule = maps.get(mapId);
        return rule == null ? new MapRule(-1, 100) : rule;
    }

    public List<String> problems() {
        return problems;
    }
}
