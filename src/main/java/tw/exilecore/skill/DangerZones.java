package tw.exilecore.skill;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 招式預警的地面標示，兩種畫法看玩家有沒有套介面資源包：
 * <ul>
 *   <li>有套（2026-10）：一片平貼在地上的圖（圓、扇形、長條；資源包的 exilecore:mark/…，見 {@link GroundMarks}），
 *       招式的顏色、半透明、外框；上面再疊一片從中間慢慢長滿的，長滿就落下。一個範圍只有一兩個實體，
 *       客戶端不用重畫地板，也不用送粒子（打 Boss 時畫面偶爾卡一下就是這兩個）。只有套了資源包的人看得到這些實體。</li>
 *   <li>沒套：把危險範圍的地板暫時「看起來」換成有顏色的方塊（只送給客戶端，伺服器的方塊沒變），結束後換回原本的樣子。
 *       只標實心的整塊地板（半磚、樓梯、水不標，那裡還是只有粒子）。</li>
 * </ul>
 * 顏色照招式的傷害類型：物理紅、火焰橘、冰冷淡藍、閃電黃、混沌紫；快落下時換成亮紅；安全的地方綠色。
 * 2026-10-31 照站長：範圍一律畫紅色（玩家的地面效果是淡色虛線，紅色只留給要躲的）；傷害類型改成範圍中間一個小字
 * （火、冰、雷、混，物理不寫）；快落下時泛白；安全的地方照舊綠色。
 */
final class DangerZones {

    /** 幾格內的玩家看得到標示。 */
    private static final double VIEW = 64;
    /** 往下找地板最多幾格、往上最多幾格。 */
    private static final int DOWN = 4;
    private static final int UP = 2;
    /** 平面標示：填滿的那一片多久更新一次（tick；兩次之間客戶端自己補成連續的動畫）。 */
    private static final int FILL_STEP = 4;
    /** 平面標示：形狀換了（光束每 4 tick 轉一次、跟著目標走）時花幾 tick 轉過去、移過去。 */
    private static final int MOVE_STEP = 4;
    /** 平面標示的實體都掛這個標籤（萬一沒清到可以找出來）。 */
    static final String TAG = "exilecore_mark";

    /**
     * 一塊範圍：形狀（中心或起點、方向、大小）＋地板上對應的方塊（沒套資源包的人看的、判斷「站在裡面」用的）。
     */
    static final class Shape {
        enum Kind { DISC, RING, SECTOR, STRIP }

        final Kind kind;
        final Location origin;
        final double fx;
        final double fz;
        /** 圓的半徑、扇形與長條的長度。 */
        final double size;
        /** 扇形的角度、長條的半寬。 */
        final double extra;
        final List<Block> cells;

        private Shape(final Kind kind, final Location origin, final Vector facing, final double size, final double extra,
                      final List<Block> cells) {
            this.kind = kind;
            this.origin = origin.clone();
            final double length = facing == null ? 0 : Math.hypot(facing.getX(), facing.getZ());
            this.fx = length < 1e-9 ? 0 : facing.getX() / length;
            this.fz = length < 1e-9 ? 1 : facing.getZ() / length;
            this.size = size;
            this.extra = extra;
            this.cells = cells;
        }

        GroundMarks.Placement placement(final double progress) {
            return switch (kind) {
                case DISC -> GroundMarks.disc(size, progress);
                case RING -> GroundMarks.ring(size);
                case SECTOR -> GroundMarks.sector(fx, fz, size, extra, progress);
                case STRIP -> GroundMarks.strip(fx, fz, size, extra, progress);
            };
        }
    }

    /** 一塊標示。 */
    private static final class Zone {
        final World world;
        final Shape shape;
        final Location center;
        Material color;
        /** 顏色換了，看過（地板方塊）的人要重送。 */
        boolean dirty;
        /** 看到地板方塊的人。 */
        final Set<UUID> viewers = new HashSet<>();
        /** 平面標示：外框那一片、慢慢填滿那一片（沒人套資源包時是 null）。 */
        ItemDisplay outline;
        ItemDisplay fill;
        /** 2026-10-31：傷害類型的小字（範圍中間；物理、安全區、沒有的是 null）。 */
        org.bukkit.entity.TextDisplay hint;
        /** 看到平面標示的人。 */
        final Set<UUID> markViewers = new HashSet<>();
        /** 平面離地多高（疊在一起的標示錯開一點點，不會互相閃爍）。 */
        final double lift;
        /** 填滿的進度：最近一次、那時的 tick、每 tick 長多少、上次送出的 tick。 */
        double progress = -1;
        long progressTick;
        double rate;
        long fillSent;

        Zone(final World world, final Shape shape, final Location center, final Material color, final double lift) {
            this.world = world;
            this.shape = shape;
            this.center = center;
            this.color = color;
            this.lift = lift;
        }
    }

    private final ExileCore plugin;
    private final Map<String, Zone> zones = new HashMap<>();
    private int serial;

    DangerZones(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 傷害類型的地面顏色。 */
    static Material colorFor(final DamageType type) {
        return switch (type) {
            case FIRE -> Material.ORANGE_CONCRETE;
            case COLD -> Material.LIGHT_BLUE_CONCRETE;
            case LIGHTNING -> Material.YELLOW_CONCRETE;
            case CHAOS -> Material.PURPLE_CONCRETE;
            default -> Material.RED_CONCRETE;
        };
    }

    /** 快落下時閃的顏色。 */
    static final Material FLASH = Material.REDSTONE_BLOCK;

    /** 2026-10-31：畫出來的顏色——傷害類型的顏色一律換成紅色（快落下、安全區照舊）。 */
    static Material shown(final Material color) {
        return color == FLASH || color == SAFE ? color : Material.RED_CONCRETE;
    }

    /** 2026-10-31：傷害類型的小字（MiniMessage）；物理、安全區、快落下的回傳 null。 */
    static String hintText(final Material color) {
        return switch (color) {
            case ORANGE_CONCRETE -> "<#FF9A3C><bold>火";
            case LIGHT_BLUE_CONCRETE -> "<#8FE3FF><bold>冰";
            case YELLOW_CONCRETE -> "<#FFE14D><bold>雷";
            case PURPLE_CONCRETE -> "<#C88CFF><bold>混";
            default -> null;
        };
    }
    /** 安全的地方（全場大招的安全區、擴散環的缺口）：綠色。 */
    static final Material SAFE = Material.LIME_CONCRETE;

    /** 平面標示的顏色（RGB）：跟地板方塊同一套。 */
    static int rgb(final Material color) {
        return switch (color) {
            case ORANGE_CONCRETE -> 0xFF8A1E;
            case LIGHT_BLUE_CONCRETE -> 0x5FD3FF;
            case YELLOW_CONCRETE -> 0xFFE14D;
            case PURPLE_CONCRETE -> 0xB45CFF;
            case REDSTONE_BLOCK -> 0xFFD6D6; // 2026-10-31：快落下時泛白（這次之前亮紅 0xFF1F1F，範圍全紅之後看不出差別）
            case LIME_CONCRETE -> 0x4DFF6A;
            default -> 0xFF4A3A;
        };
    }

    // ---- 範圍 ----

    /** 圓：中心 radius 格內。 */
    static Shape disc(final Location center, final double radius) {
        // 2026-10：打不打得到是看身體的邊（半寬 0.3 格）碰不碰到圈，所以標示也往外多 0.3 格，站在標示外面就不會被打到
        return discOf(center, radius + PLAYER_HALF_WIDTH + MARK_MARGIN);
    }

    /**
     * 標示再往外多畫幾格（2026-10：試過大 1 格，玩家覺得範圍變大難躲，改回 0：標示剛好等於打得到的範圍——
     * 打不打得到看身體的邊（半寬 0.3）碰不碰到，標示也往外畫 0.3，腳站在標示外面就打不到）。
     */
    static final double MARK_MARGIN = 0;

    /** 玩家身體的半寬（判定是看身體的邊）。 */
    static final double PLAYER_HALF_WIDTH = 0.3;

    /** 安全區（站進來才安全）：不往外加，只標中心在圈裡的格子（判定另外放寬，站在任何一格綠色上都算在裡面）。 */
    static Shape safeDisc(final Location center, final double radius) {
        return discOf(center, radius);
    }

    private static Shape discOf(final Location center, final double radius) {
        final List<Block> cells = new ArrayList<>();
        final int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double x = center.getBlockX() + dx + 0.5;
                final double z = center.getBlockZ() + dz + 0.5;
                if (Math.hypot(x - center.getX(), z - center.getZ()) <= radius) {
                    final Block floor = floor(center.getWorld(), center.getBlockX() + dx, center.getBlockY(), center.getBlockZ() + dz);
                    if (floor != null) {
                        cells.add(floor);
                    }
                }
            }
        }
        return new Shape(Shape.Kind.DISC, center, null, radius, 0, cells);
    }

    /**
     * 大範圍的邊界（擴散環、全場大招、漩渦拉人的範圍）：只有平面標示畫一圈外框，地板不標
     * （裡面另外標安全的地方；沒套資源包的人照舊看粒子的圈）。
     */
    static Shape ring(final Location center, final double radius) {
        return new Shape(Shape.Kind.RING, center, null, radius, 0, List.of());
    }

    /** 扇形：從 feet 朝 facing、長 length、張角 angle 度。 */
    static Shape sector(final Location feet, final Vector facing, final double rawLength, final double angle) {
        final double length = rawLength + PLAYER_HALF_WIDTH + MARK_MARGIN;
        final List<Block> cells = new ArrayList<>();
        final int r = (int) Math.ceil(length);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double x = feet.getBlockX() + dx + 0.5 - feet.getX();
                final double z = feet.getBlockZ() + dz + 0.5 - feet.getZ();
                final double distance = Math.hypot(x, z);
                if (distance > length || distance < 0.3) {
                    continue;
                }
                if (SkillMath.inCone(facing.getX(), facing.getZ(), x, z, angle)) {
                    final Block floor = floor(feet.getWorld(), feet.getBlockX() + dx, feet.getBlockY(), feet.getBlockZ() + dz);
                    if (floor != null) {
                        cells.add(floor);
                    }
                }
            }
        }
        return new Shape(Shape.Kind.SECTOR, feet, facing, length, angle, cells);
    }

    /** 直線：從 from 朝 facing、長 length、左右各 halfWidth。 */
    static Shape strip(final Location from, final Vector facing, final double rawLength, final double rawHalfWidth) {
        final double length = rawLength + PLAYER_HALF_WIDTH + MARK_MARGIN;
        final double halfWidth = rawHalfWidth + PLAYER_HALF_WIDTH + MARK_MARGIN;
        final List<Block> cells = new ArrayList<>();
        final int r = (int) Math.ceil(length + halfWidth);
        final double norm = Math.max(1e-9, Math.hypot(facing.getX(), facing.getZ()));
        final double fx = facing.getX() / norm;
        final double fz = facing.getZ() / norm;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final double x = from.getBlockX() + dx + 0.5 - from.getX();
                final double z = from.getBlockZ() + dz + 0.5 - from.getZ();
                final double along = x * fx + z * fz;
                final double side = Math.abs(x * fz - z * fx);
                if (along < 0 || along > length || side > halfWidth) {
                    continue;
                }
                final Block floor = floor(from.getWorld(), from.getBlockX() + dx, from.getBlockY(), from.getBlockZ() + dz);
                if (floor != null) {
                    cells.add(floor);
                }
            }
        }
        return new Shape(Shape.Kind.STRIP, from, facing, length, halfWidth, cells);
    }

    /** 這一欄的地板：從 y+UP 往下找到第一個「實心整塊、上面是空的」的方塊；找不到是 null。 */
    private static Block floor(final World world, final int x, final int y, final int z) {
        for (int yy = y + UP; yy >= y - DOWN; yy--) {
            final Block block = world.getBlockAt(x, yy, z);
            if (block.getType().isOccluding() && !block.getRelative(0, 1, 0).getType().isOccluding()) {
                return block;
            }
            if (block.getType().isOccluding()) {
                return null; // 被埋在裡面（牆）
            }
        }
        return null;
    }

    // ---- 顯示 ----

    /** 這個玩家看平面標示（套了介面資源包）。 */
    private boolean marked(final Player player) {
        return plugin.web() != null && plugin.web().hasPack(player);
    }

    /**
     * 標出一塊範圍。同一個 id 已經標著（光束轉動、跟著目標走）：地板方塊只送變了的格子（離開的換回真的方塊、新進的換顏色），
     * 平面標示轉過去、移過去；以前是整塊收掉再整塊重送，客戶端每次都要重畫一大片地板。
     */
    void show(final String id, final Location center, final Shape shape, final Material element) {
        final Material color = shown(element); // 2026-10-31：一律紅色（傷害類型改成中間的小字）
        final Zone old = zones.get(id);
        if (old != null && old.world.equals(center.getWorld())) {
            move(id, old, center, shape, color);
            return;
        }
        hide(id);
        final Zone zone = new Zone(center.getWorld(), shape, center.clone(), color, 0.02 + (serial++ % 5) * 0.004);
        zones.put(id, zone);
        send(zone);
        final String hint = hintText(element);
        if (hint != null) {
            zone.hint = spawnHint(zone, hint);
        }
    }

    /** 2026-10-31：範圍中間的傷害類型小字（一直面向玩家、貼近地面）。 */
    private org.bukkit.entity.TextDisplay spawnHint(final Zone zone, final String text) {
        return zone.world.spawn(hintAt(zone), org.bukkit.entity.TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.addScoreboardTag(TAG);
            entity.text(plugin.text().parse(text));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setViewRange(0.8f);
            entity.setTeleportDuration(MOVE_STEP);
            entity.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f, 1.6f, 1.6f), new Quaternionf()));
        });
    }

    private static Location hintAt(final Zone zone) {
        final Location ground = anchor(zone, 0);
        return new Location(zone.world, zone.center.getX(), ground.getY() + 0.35, zone.center.getZ());
    }

    private static BlockPosition position(final Block block) {
        return Position.block(block.getX(), block.getY(), block.getZ());
    }

    /** 換成新的範圍：看過地板的人只收到變了的格子；平面標示轉過去、移過去。 */
    private void move(final String id, final Zone old, final Location center, final Shape shape, final Material color) {
        final Set<BlockPosition> next = new HashSet<>();
        for (final Block block : shape.cells) {
            next.add(position(block));
        }
        final Map<BlockPosition, BlockData> change = new HashMap<>();
        final Set<BlockPosition> before = new HashSet<>();
        for (final Block block : old.shape.cells) {
            final BlockPosition at = position(block);
            before.add(at);
            if (!next.contains(at)) {
                change.put(at, block.getBlockData()); // 離開範圍：換回真的方塊
            }
        }
        if (!change.isEmpty()) {
            // 離開的格子還在別塊標示裡（幾道光束在 Boss 腳下重疊）：照那一塊的顏色，不要換回真的方塊
            for (final Map.Entry<String, Zone> other : zones.entrySet()) {
                if (other.getKey().equals(id) || !other.getValue().world.equals(old.world)) {
                    continue;
                }
                BlockData otherColor = null;
                for (final Block block : other.getValue().shape.cells) {
                    final BlockPosition at = position(block);
                    if (change.containsKey(at)) {
                        if (otherColor == null) {
                            otherColor = other.getValue().color.createBlockData();
                        }
                        change.put(at, otherColor);
                    }
                }
            }
        }
        final BlockData fake = color.createBlockData();
        for (final Block block : shape.cells) {
            final BlockPosition at = position(block);
            if (old.color != color || !before.contains(at)) {
                change.put(at, fake);
            }
        }
        final Zone zone = new Zone(old.world, shape, center.clone(), color, old.lift);
        zone.viewers.addAll(old.viewers);
        // 下線、換了世界的人：回來時要收到整塊（不然只收到變了的那幾格，看到的是半條）
        zone.viewers.removeIf(uuid -> {
            final Player viewer = plugin.getServer().getPlayer(uuid);
            return viewer == null || !viewer.getWorld().equals(zone.world);
        });
        // 平面標示：接著用同一片，轉到新的方向、移到新的位置（客戶端補成連續的）
        zone.outline = old.outline;
        zone.fill = old.fill;
        zone.hint = old.hint;
        if (zone.hint != null && zone.hint.isValid()) {
            zone.hint.teleport(hintAt(zone)); // 2026-10-31：小字跟著範圍走
        }
        zone.markViewers.addAll(old.markViewers);
        zone.progress = old.progress;
        zone.progressTick = old.progressTick;
        zone.rate = old.rate;
        zone.fillSent = old.fillSent;
        zones.put(id, zone);
        if (!change.isEmpty()) {
            for (final UUID uuid : zone.viewers) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && player.getWorld().equals(zone.world)) {
                    player.sendMultiBlockChange(change);
                }
            }
        }
        if (zone.outline != null && zone.outline.isValid()) {
            place(zone, zone.outline, shape.placement(1), 0, MOVE_STEP);
            if (old.color != color) {
                zone.outline.setItemStack(item(zone.outline, color));
            }
        }
        if (zone.fill != null && zone.fill.isValid()) {
            place(zone, zone.fill, shape.placement(Math.max(0, zone.progress)), FILL_LIFT, MOVE_STEP);
            if (old.color != color) {
                zone.fill.setItemStack(item(zone.fill, color));
            }
        }
        send(zone); // 剛走近、還沒看過的人送整塊
    }

    /** 換顏色（快落下時變亮紅）；顏色沒變就不送。 */
    void recolor(final String id, final Material color) {
        final Zone zone = zones.get(id);
        if (zone == null || zone.color == color) {
            return;
        }
        zone.color = color;
        zone.dirty = true;
        if (zone.outline != null && zone.outline.isValid()) {
            zone.outline.setItemStack(item(zone.outline, color));
        }
        if (zone.fill != null && zone.fill.isValid()) {
            zone.fill.setItemStack(item(zone.fill, color));
        }
        send(zone);
    }

    /**
     * 前搖的進度（0–1，每 tick 叫一次）：平面標示上面那一片從中間長到滿，長滿就落下。
     * 每 {@value #FILL_STEP} tick 送一次「再過這麼久要長到多大」，客戶端自己補成連續的；沒人看平面標示時什麼都不做。
     */
    void progress(final String id, final double progress) {
        final Zone zone = zones.get(id);
        if (zone == null || zone.outline == null || !zone.outline.isValid()) {
            return;
        }
        final double clamped = Math.max(0, Math.min(1, progress));
        final long now = plugin.getServer().getCurrentTick();
        if (zone.progress >= 0 && now > zone.progressTick) {
            zone.rate = Math.max(0, (clamped - zone.progress) / (now - zone.progressTick));
        }
        zone.progress = clamped;
        zone.progressTick = now;
        if (zone.fill == null || !zone.fill.isValid()) {
            // 第一次：照現在的進度長出來（下一次更新才開始動，剛生出來的實體客戶端不會補動畫）
            zone.fill = spawn(zone, zone.shape.placement(clamped), FILL_LIFT);
            zone.fillSent = now;
            return;
        }
        if (now - zone.fillSent < FILL_STEP) {
            return;
        }
        final double target = Math.min(1, clamped + zone.rate * FILL_STEP);
        place(zone, zone.fill, zone.shape.placement(target), FILL_LIFT, FILL_STEP);
        zone.fillSent = now;
    }

    /** 收掉：看過地板的人換回真正的方塊；平面標示移除。 */
    void hide(final String id) {
        final Zone zone = zones.remove(id);
        if (zone == null) {
            return;
        }
        removeMarks(zone);
        if (zone.viewers.isEmpty() || zone.shape.cells.isEmpty()) {
            return;
        }
        final Map<BlockPosition, BlockData> real = new HashMap<>();
        for (final Block block : zone.shape.cells) {
            real.put(position(block), block.getBlockData());
        }
        // 2026-10-18：重疊的格子還在別塊標示裡（錯開落下的 slam）：照那一塊的顏色，不要換回真的方塊（同 move()）
        for (final Zone other : zones.values()) {
            if (!other.world.equals(zone.world)) {
                continue;
            }
            BlockData otherColor = null;
            for (final Block block : other.shape.cells) {
                final BlockPosition at = position(block);
                if (real.containsKey(at)) {
                    if (otherColor == null) {
                        otherColor = other.color.createBlockData();
                    }
                    real.put(at, otherColor);
                }
            }
        }
        for (final UUID uuid : zone.viewers) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getWorld().equals(zone.world)) {
                player.sendMultiBlockChange(real);
            }
        }
    }

    boolean has(final String id) {
        return zones.containsKey(id);
    }

    /** 這個玩家站在這塊範圍裡嗎（腳下或腳邊那格是範圍裡的地板）。 */
    boolean inside(final String id, final Player player) {
        final Zone zone = zones.get(id);
        if (zone == null || !player.getWorld().equals(zone.world)) {
            return false;
        }
        final Location feet = player.getLocation();
        for (final Block block : zone.shape.cells) {
            if (block.getX() == feet.getBlockX() && block.getZ() == feet.getBlockZ() && Math.abs(block.getY() - feet.getBlockY()) <= 2) {
                return true;
            }
        }
        return false;
    }

    /** 每 tick：剛走近的玩家補送。 */
    void tick() {
        if (zones.isEmpty()) {
            return;
        }
        for (final Zone zone : zones.values()) {
            send(zone);
        }
    }

    /** 送給附近還沒看過（或顏色變了）的玩家：套了資源包的看平面標示，沒套的看地板方塊。 */
    private void send(final Zone zone) {
        Map<BlockPosition, BlockData> fake = null;
        for (final Player player : zone.world.getPlayers()) {
            if (player.getLocation().distanceSquared(zone.center) > VIEW * VIEW) {
                continue;
            }
            final UUID uuid = player.getUniqueId();
            if (marked(player) && showMarks(zone, player)) {
                if (zone.viewers.remove(uuid) && !zone.shape.cells.isEmpty()) {
                    // 前搖到一半才套好資源包：地板換回真的
                    final Map<BlockPosition, BlockData> real = new HashMap<>();
                    for (final Block block : zone.shape.cells) {
                        real.put(position(block), block.getBlockData());
                    }
                    player.sendMultiBlockChange(real);
                }
                continue;
            }
            if (!zone.viewers.add(uuid) && !zone.dirty) {
                continue; // 看過了、顏色也沒變
            }
            if (zone.shape.cells.isEmpty()) {
                continue;
            }
            if (fake == null) {
                fake = new HashMap<>();
                final BlockData data = zone.color.createBlockData();
                for (final Block block : zone.shape.cells) {
                    fake.put(position(block), data);
                }
            }
            player.sendMultiBlockChange(fake);
        }
        zone.dirty = false;
    }

    /** 讓這個玩家看到平面標示（還沒有就生出來）；生不出來（那一區沒載入）回傳 false，改看地板方塊。 */
    private boolean showMarks(final Zone zone, final Player player) {
        if (zone.outline == null || !zone.outline.isValid()) {
            final Location anchor = anchor(zone, 0);
            if (!zone.world.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                return false;
            }
            zone.outline = spawn(zone, zone.shape.placement(1), 0);
            zone.markViewers.clear();
        }
        // 2026-10-18：已記過但看不到（重新登入後 showEntity 的狀態不在了）也要重新 showEntity
        if (zone.markViewers.add(player.getUniqueId()) || !player.canSee(zone.outline)) {
            player.showEntity(plugin, zone.outline);
            if (zone.fill != null && zone.fill.isValid()) {
                player.showEntity(plugin, zone.fill);
            }
        }
        return true;
    }

    /** 填滿那一片比外框高一點點（不會跟外框互相閃爍）。 */
    private static final double FILL_LIFT = 0.003;

    /**
     * 平面放在哪裡：形狀的中心或起點，高度是範圍裡最多格的那一層地板的上面（地板不平時大部分看得到；
     * 找不到地板——水面、半磚——就照出招時的高度）。
     */
    private static Location anchor(final Zone zone, final double extraLift) {
        final Location origin = zone.shape.origin;
        double y = origin.getY();
        if (!zone.shape.cells.isEmpty()) {
            final Map<Integer, Integer> counts = new HashMap<>();
            int best = Integer.MIN_VALUE;
            int bestCount = 0;
            for (final Block block : zone.shape.cells) {
                final int count = counts.merge(block.getY(), 1, Integer::sum);
                if (count > bestCount || (count == bestCount
                        && Math.abs(block.getY() + 1 - origin.getY()) < Math.abs(best + 1 - origin.getY()))) {
                    best = block.getY();
                    bestCount = count;
                }
            }
            y = best + 1;
        }
        return new Location(zone.world, origin.getX(), y + zone.lift + extraLift, origin.getZ(), 0, 0);
    }

    private ItemDisplay spawn(final Zone zone, final GroundMarks.Placement placement, final double extraLift) {
        final ItemDisplay display = zone.world.spawn(anchor(zone, extraLift), ItemDisplay.class, entity -> {
            entity.setVisibleByDefault(false); // 只有套了資源包的人看得到（沒套的會看到一張紙）
            entity.setPersistent(false);
            entity.addScoreboardTag(TAG);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setItemStack(item(placement.model(), zone.color));
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setShadowRadius(0);
            entity.setViewRange(1.5f);
            entity.setTeleportDuration(MOVE_STEP);
            entity.setInterpolationDuration(0);
            entity.setTransformation(transformation(placement));
        });
        for (final UUID uuid : zone.markViewers) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.getWorld().equals(zone.world)) {
                player.showEntity(plugin, display);
            }
        }
        return display;
    }

    /** 把平面換成新的擺法：位置變了就移過去，形狀與大小 ticks 個 tick 內變過去（客戶端補成連續的）。 */
    private void place(final Zone zone, final ItemDisplay display, final GroundMarks.Placement placement, final double extraLift,
                       final int ticks) {
        if (display == null || !display.isValid()) {
            return;
        }
        final ItemStack current = display.getItemStack();
        final Key model = current == null ? null : current.getData(DataComponentTypes.ITEM_MODEL);
        if (model == null || !model.value().equals("mark/" + placement.model())) {
            display.setItemStack(item(placement.model(), zone.color));
        }
        final Location anchor = anchor(zone, extraLift);
        if (display.getLocation().distanceSquared(anchor) > 1e-4) {
            display.teleport(anchor);
        }
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(transformation(placement));
    }

    private static Transformation transformation(final GroundMarks.Placement placement) {
        final float[] rotation = GroundMarks.quaternion(placement.yaw());
        return new Transformation(
                new Vector3f((float) placement.offsetX(), 0f, (float) placement.offsetZ()),
                new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]),
                new Vector3f((float) placement.scaleX(), 1f, (float) placement.scaleZ()),
                new Quaternionf());
    }

    /** 同一張圖換顏色。 */
    private static ItemStack item(final ItemDisplay display, final Material color) {
        final ItemStack current = display.getItemStack();
        final Key model = current == null ? null : current.getData(DataComponentTypes.ITEM_MODEL);
        final String name = model == null || !model.value().startsWith("mark/") ? "disc" : model.value().substring("mark/".length());
        return item(name, color);
    }

    private static ItemStack item(final String model, final Material color) {
        final ItemStack stack = new ItemStack(Material.PAPER);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("exilecore", "mark/" + model));
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                .addColor(Color.fromRGB(rgb(color))).build());
        return stack;
    }

    /** 移除平面標示，順便把每個人「看得到它」的紀錄清掉（不然會一直累積到下線）。 */
    private void removeMarks(final Zone zone) {
        for (final ItemDisplay display : new ItemDisplay[] {zone.outline, zone.fill}) {
            if (display == null) {
                continue;
            }
            for (final UUID uuid : zone.markViewers) {
                final Player player = plugin.isEnabled() ? plugin.getServer().getPlayer(uuid) : null; // 關伺服器時不能再 hideEntity，直接移除
                if (player != null && player.canSee(display)) {
                    player.hideEntity(plugin, display);
                }
            }
            if (display.isValid()) {
                display.remove();
            }
        }
        zone.outline = null;
        zone.fill = null;
        zone.markViewers.clear();
        if (zone.hint != null && zone.hint.isValid()) {
            zone.hint.remove(); // 2026-10-31
        }
        zone.hint = null;
    }

    /** 全部收掉（模組停用、Boss 消失）。 */
    void clear() {
        for (final String id : new ArrayList<>(zones.keySet())) {
            hide(id);
        }
    }

    /** 收掉某個前綴開頭的（一隻 Boss 的全部）。 */
    void hidePrefix(final String prefix) {
        final Iterator<String> iterator = new ArrayList<>(zones.keySet()).iterator();
        while (iterator.hasNext()) {
            final String id = iterator.next();
            if (id.startsWith(prefix)) {
                hide(id);
            }
        }
    }
}
