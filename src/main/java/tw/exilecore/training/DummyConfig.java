package tw.exilecore.training;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * data/dummies.yml：營地的訓練假人（打它看自己的傷害）。純資料，不依賴 Bukkit。
 * 沒有這個檔時用這裡的預設值（2026-10-29 照站長：只有首領一隻；這次之前六隻：無防禦、一般、重甲、閃避、抗性、首領）。
 */
public final class DummyConfig {

    public static final String FILE = "dummies.yml";
    /** level 寫 player：跟打它的玩家同等級。 */
    public static final int LEVEL_PLAYER = 0;
    /** level 寫 map：最高階地圖的怪物等級（maps.yml 的 T16）。 */
    public static final int LEVEL_MAP = -1;
    /** 外觀（一整套原版盔甲的材質；none = 不穿）。 */
    static final Set<String> LOOKS = Set.of("none", "leather", "chainmail", "iron", "golden", "diamond", "netherite");

    /**
     * 一隻假人。
     *
     * @param level    怪物等級；{@link #LEVEL_PLAYER}＝跟打它的玩家同等級、{@link #LEVEL_MAP}＝最高階地圖的等級
     * @param boss     首領（傳奇稀有度；首領的規則照地圖首領）
     * @param life     生命倍率（假人不會死；生命只影響元素異常的門檻）
     * @param armour   護甲：同等級一般怪物的幾倍（0 = 沒有）
     * @param evasion  閃避：同等級一般怪物的幾倍
     * @param offsetX  相對營地落點（重生點）的位置
     * @param scale    體型
     * @param look     外觀（{@link #LOOKS}）
     */
    public record Dummy(String id, String name, String note, int level, boolean boss, double life, double armour, double evasion,
                        double fire, double cold, double lightning, double chaos, double offsetX, double offsetZ, float yaw,
                        double scale, String look, double bossFactor) {
        /** 沒寫 boss_factor 的：首領 1、其他 0.6。 */
        public Dummy(final String id, final String name, final String note, final int level, final boolean boss, final double life,
                     final double armour, final double evasion, final double fire, final double cold, final double lightning,
                     final double chaos, final double offsetX, final double offsetZ, final float yaw, final double scale, final String look) {
            this(id, name, note, level, boss, life, armour, evasion, fire, cold, lightning, chaos, offsetX, offsetZ, yaw, scale, look,
                    boss ? 1 : DEFAULT_BOSS_FACTOR);
        }
    }

    /** 打這隻假人的每秒傷害換成打 Boss 的（2026-10）：沒寫 boss_factor、或紀錄對不到假人的時候用這個。 */
    public static final double DEFAULT_BOSS_FACTOR = 0.6;

    private double arenaMargin;
    private int idleSeconds;
    /** 每人一組自己的假人（2026-10）：同時最多幾組（0 = 關掉，大家打共用的那組）。 */
    private int personalMax;
    private List<Dummy> dummies;

    public DummyConfig() {
        reset();
    }

    private void reset() {
        arenaMargin = 6;
        idleSeconds = 4;
        personalMax = 10;
        dummies = defaults();
    }

    /** 跟出貨的 dummies.yml 一樣（2026-10-29 照站長：只保留首領；位置照舊，營地東南邊 [28, 20]）。 */
    public static List<Dummy> defaults() {
        return List.of(
                new Dummy("boss", "訓練假人・首領", "像最高階地圖的首領：抗性 40%／25%", LEVEL_MAP, true, 10, 1, 1, 40, 40, 40, 25, 28, 20, 180f, 1.3, "diamond", 1));
    }

    /**
     * 2026-10-29：拿掉的五隻假人的 boss_factor（照拿掉前的 dummies.yml）。玩家身上還留著打它們的舊紀錄（換裝備前一直算數），
     * 照原本的倍率換成打 Boss 的每秒傷害，不會因為假人拿掉就一律變成 {@link #DEFAULT_BOSS_FACTOR}。
     */
    static final java.util.Map<String, Double> RETIRED = java.util.Map.of(
            "訓練假人・無防禦", 0.55, "訓練假人・一般", 0.6, "訓練假人・重甲", 0.75, "訓練假人・閃避", 0.65, "訓練假人・抗性", 0.95);

    /** 讀 dummies.yml；先讀進一份新的，全部檢查過才換上去（寫錯時保留原本的設定）。 */
    public void load(final Path dataFolder) throws IOException {
        final DummyConfig fresh = new DummyConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        arenaMargin = fresh.arenaMargin;
        idleSeconds = fresh.idleSeconds;
        personalMax = fresh.personalMax;
        // 2026-10-29 照站長：訓練假人只保留首領。伺服器上自己改過（例如 /ec dummy move 搬過）的 dummies.yml 不會自動換新版，
        // 所以讀檔時直接只留 boss: true 的；這次之前檔案裡寫幾隻就放幾隻
        dummies = fresh.dummies.stream().filter(Dummy::boss).toList();
    }

    void parse(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        arenaMargin = settings.decimal("arena_margin", arenaMargin);
        if (arenaMargin < 0 || arenaMargin > 32) {
            throw new IllegalArgumentException(settings.path() + ".arena_margin：要在 0 到 32 之間，卻是 " + arenaMargin);
        }
        idleSeconds = settings.integer("idle_seconds", idleSeconds);
        if (idleSeconds < 1 || idleSeconds > 60) {
            throw new IllegalArgumentException(settings.path() + ".idle_seconds：要在 1 到 60 之間，卻是 " + idleSeconds);
        }
        personalMax = settings.integer("personal_max", personalMax);
        if (personalMax < 0 || personalMax > 50) {
            throw new IllegalArgumentException(settings.path() + ".personal_max：要在 0 到 50 之間，卻是 " + personalMax);
        }
        if (!root.has("dummies")) {
            return;
        }
        final YamlNode list = root.node("dummies");
        final List<Dummy> parsed = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final String id : list.keys()) {
            final YamlNode node = list.node(id);
            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(node.path() + "：代號重複");
            }
            parsed.add(parseDummy(id, node));
        }
        dummies = List.copyOf(parsed);
    }

    private static Dummy parseDummy(final String id, final YamlNode node) {
        final String name = node.string("name", id);
        final String note = node.string("note", "");
        final int level = parseLevel(node);
        final boolean boss = node.bool("boss", false);
        final double life = node.decimal("life", 1);
        if (life <= 0 || life > 1000) {
            throw new IllegalArgumentException(node.path() + ".life：要大於 0、最多 1000，卻是 " + life);
        }
        final double armour = multiplier(node, "armour");
        final double evasion = multiplier(node, "evasion");
        final YamlNode resist = node.node("resist");
        final double fire = resistance(resist, "fire");
        final double cold = resistance(resist, "cold");
        final double lightning = resistance(resist, "lightning");
        final double chaos = resistance(resist, "chaos");
        final List<String> offset = node.strings("offset");
        if (offset.size() != 2) {
            throw new IllegalArgumentException(node.path() + ".offset：要寫成 [x, z]（相對營地落點的格數）");
        }
        final double offsetX;
        final double offsetZ;
        try {
            offsetX = Double.parseDouble(offset.get(0).trim());
            offsetZ = Double.parseDouble(offset.get(1).trim());
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(node.path() + ".offset：要是兩個數字，卻是 " + offset);
        }
        if (Math.abs(offsetX) > 200 || Math.abs(offsetZ) > 200) {
            throw new IllegalArgumentException(node.path() + ".offset：離營地落點太遠（最多 200 格）");
        }
        final float yaw = (float) node.decimal("yaw", 180);
        final double scale = node.decimal("scale", 1);
        if (scale < 0.5 || scale > 3) {
            throw new IllegalArgumentException(node.path() + ".scale：要在 0.5 到 3 之間，卻是 " + scale);
        }
        final String look = node.string("look", "none").toLowerCase(Locale.ROOT);
        if (!LOOKS.contains(look)) {
            throw new IllegalArgumentException(node.path() + ".look：不認得「" + look + "」（" + String.join("、", LOOKS.stream().sorted().toList()) + "）");
        }
        final double bossFactor = node.decimal("boss_factor", boss ? 1 : DEFAULT_BOSS_FACTOR);
        if (bossFactor <= 0 || bossFactor > 2) {
            throw new IllegalArgumentException(node.path() + ".boss_factor：要大於 0、最多 2，卻是 " + bossFactor);
        }
        return new Dummy(id, name, note, level, boss, life, armour, evasion, fire, cold, lightning, chaos, offsetX, offsetZ, yaw, scale, look,
                bossFactor);
    }

    private static int parseLevel(final YamlNode node) {
        final Object raw = node.raw("level");
        if (raw == null || "player".equalsIgnoreCase(String.valueOf(raw).trim())) {
            return LEVEL_PLAYER;
        }
        if ("map".equalsIgnoreCase(String.valueOf(raw).trim())) {
            return LEVEL_MAP;
        }
        final int level = node.integer("level", 1);
        if (level < 1 || level > 100) {
            throw new IllegalArgumentException(node.path() + ".level：要是 player、map 或 1 到 100，卻是 " + raw);
        }
        return level;
    }

    private static double multiplier(final YamlNode node, final String key) {
        final double value = node.decimal(key, 1);
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要在 0 到 100 之間（一般怪物的幾倍），卻是 " + value);
        }
        return value;
    }

    private static double resistance(final YamlNode node, final String key) {
        final double value = node.decimal(key, 0);
        if (value < -200 || value > 90) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要在 -200 到 90 之間（百分比），卻是 " + value);
        }
        return value;
    }

    /** 練習場的範圍：假人的外框往外幾格（在裡面可以施放技能）。 */
    public double arenaMargin() {
        return arenaMargin;
    }

    /** 停手幾秒算打完一段（聊天框給總結）。 */
    public int idleSeconds() {
        return idleSeconds;
    }

    public List<Dummy> dummies() {
        return dummies;
    }

    /** 每人一組自己的假人：同時最多幾組（0 = 關掉）。 */
    public int personalMax() {
        return personalMax;
    }

    /**
     * 打假人的紀錄換成打 Boss 的每秒傷害（2026-10）：紀錄記的是假人的名字（後面接「（Lv.等級）」），
     * 對到哪一隻就乘那一隻的 boss_factor；對不到乘 {@link #DEFAULT_BOSS_FACTOR}。
     */
    public double bossDps(final DummyRecord record) {
        if (record == null) {
            return 0;
        }
        for (final Dummy dummy : dummies) {
            if (record.dummy() != null && record.dummy().startsWith(dummy.name())) {
                return record.dps() * dummy.bossFactor();
            }
        }
        final Double retired = retiredFactor(record.dummy());
        return record.dps() * (retired == null ? DEFAULT_BOSS_FACTOR : retired);
    }

    /** 2026-10-29：拿掉的假人的 boss_factor（{@link #RETIRED}）；不是拿掉的那幾隻回傳 null。 */
    public static Double retiredFactor(final String target) {
        if (target == null) {
            return null;
        }
        for (final java.util.Map.Entry<String, Double> entry : RETIRED.entrySet()) {
            if (target.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Dummy dummy(final String id) {
        for (final Dummy dummy : dummies) {
            if (dummy.id().equalsIgnoreCase(id)) {
                return dummy;
            }
        }
        return null;
    }
}
