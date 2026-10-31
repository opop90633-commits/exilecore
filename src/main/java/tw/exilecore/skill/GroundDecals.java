package tw.exilecore.skill;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 常駐的地面圈（2026-10，減少粒子）：光環、怪物的光環詞綴、裂隙、祭壇這些「一直在的圈」，
 * 以前每半秒用幾十顆粒子畫一次（一顆一個封包、附近每個玩家各一份）。
 * <p>
 * 套了介面資源包的玩家：改成一片平貼在地上的圈（資源包的 exilecore:mark/ring，跟招式預警同一張圖），
 * 生一次就好；跟著實體走的圈每 {@link #FOLLOW_STEP} tick 移一次（客戶端補成連續的；不用騎在身上——
 * 身上有乘客的玩家傳送會失敗），比每半秒幾十顆粒子少很多封包。
 * 沒套的玩家照舊看粒子，但粒子只送給他們（{@link #particle}），套了的人不會再收到。
 */
public final class GroundDecals {

    /** 幾格內的玩家看得到。 */
    private static final double VIEW = 48;
    /** 跟著實體走的圈多久移一次（tick）。 */
    static final int FOLLOW_STEP = 4;
    /** 圈的實體都掛這個標籤（萬一沒清到可以找出來）。 */
    static final String TAG = "exilecore_decal";

    /** 會轉的圖（光環的法陣與符文）多久送一次新的角度（tick）：客戶端在這段時間裡自己補成連續的轉動。 */
    static final int SPIN_STEP = 10;
    /** 常駐的圈用細線的那張（招式預警的 ring 線比較粗，圈一大就變成一條粗帶子）。 */
    private static final String RING = "ring_thin";
    /** 2026-10-31：玩家地面效果的圖（資源包 web/AuraSigils 的 ground_area）。 */
    static final String GROUND_AREA = "ground_area";
    /** 2026-10-31：別人看的玩家地面效果（只有虛線外框）。 */
    static final String GROUND_OUTLINE = "ground_outline";
    /** 轉多快：每 tick 幾弧度（16 秒一圈）。 */
    static final double SPIN_RATE = Math.PI * 2 / (16 * 20);

    private static final class Decal {
        ItemDisplay display;
        Entity owner;
        double size;
        double angle;
        boolean spin;
        /** 2026-10-31：貼圖（玩家的地面效果會換：快結束時只剩外框）。 */
        String model;
        /** 2026-10-31：施放者（玩家的地面效果；關掉「別人的技能特效」的人看不到別人的）；null = 每個人都看。 */
        java.util.UUID caster;
        /** 2026-10-31：給別人看的那一片（玩家的地面效果：別人只看虛線外框；沒有是 null）。 */
        ItemDisplay others;
        /** 2026-10-31：上次放的中心點（地面高度只在移動時重找）。 */
        double cx = Double.NaN;
        double cz = Double.NaN;
        double cy;
    }

    private final ExileCore plugin;
    private final Map<String, Decal> decals = new HashMap<>();

    GroundDecals(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 這個玩家看平面的圈（套了介面資源包）。 */
    public boolean marked(final Player player) {
        return plugin.web() != null && plugin.web().hasPack(player);
    }

    // ---- 粒子只送給沒套資源包的人 ----

    /** 附近沒套資源包的玩家（空的就不用畫粒子）。 */
    public List<Player> viewers(final Location at) {
        final List<Player> list = new ArrayList<>(2);
        for (final Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= VIEW * VIEW && !marked(player)) {
                list.add(player);
            }
        }
        return list;
    }

    /** 送一個粒子給這些玩家。 */
    public static void particle(final List<Player> viewers, final Particle particle, final Location at, final int count,
                                final double dx, final double dy, final double dz, final double speed, final Object data) {
        for (final Player player : viewers) {
            if (data == null) {
                player.spawnParticle(particle, at, count, dx, dy, dz, speed);
            } else {
                player.spawnParticle(particle, at, count, dx, dy, dz, speed, data);
            }
        }
    }

    // ---- 平面的圈 ----

    /** 固定在地上的圈（裂隙、祭壇）：同一個 id 再呼叫就移過去、換大小。 */
    public void ring(final String id, final Location center, final double radius, final int rgb) {
        Decal decal = decals.get(id);
        if (decal != null && (decal.display == null || !decal.display.isValid() || !decal.display.getWorld().equals(center.getWorld()))) {
            remove(id);
            decal = null;
        }
        final Location at = new Location(center.getWorld(), center.getX(), Math.floor(center.getY()) + 0.02, center.getZ());
        if (decal == null) {
            decal = new Decal();
            decal.size = radius * 2;
            decal.display = spawn(at, RING, decal.size, 0, rgb);
            decals.put(id, decal);
        } else {
            if (decal.display.getLocation().distanceSquared(at) > 1e-4) {
                decal.display.teleport(at);
            }
            if (Math.abs(decal.size - radius * 2) > 1e-6) {
                decal.size = radius * 2;
                decal.display.setTransformation(transformation(decal.size, 0));
            }
        }
        show(decal);
    }

    /**
     * 2026-10-31 照站長：玩家的地面效果（燃燒地面、毒霧、火油、冰緩地面、雷雲…）——平貼在地面上的半透明圈＋清楚的外框，
     * 照技能的顏色著色。同一個 id 再呼叫就移過去、換大小；ending（快結束了）換成只剩一條細外框。
     * 只有套了資源包的人看得到（沒套的照舊看粒子：{@link Fx#withoutPack}）；關掉「別人的技能特效」的人看不到別人的。
     */
    public void area(final String id, final java.util.UUID caster, final Location center, final double radius, final int rgb,
                     final boolean ending) {
        final String model = ending ? RING : GROUND_AREA;
        Decal decal = decals.get(id);
        if (decal != null && (decal.display == null || !decal.display.isValid() || !decal.display.getWorld().equals(center.getWorld())
                || !model.equals(decal.model))) {
            remove(id);
            decal = null;
        }
        final boolean fresh = decal == null;
        if (fresh) {
            decal = new Decal();
        }
        // 地面高度：中心點移了 0.25 格以上才重找（斜坡、台階上貼在腳下那一格的頂面）
        if (fresh || Math.abs(center.getX() - decal.cx) > 0.25 || Math.abs(center.getZ() - decal.cz) > 0.25) {
            decal.cx = center.getX();
            decal.cz = center.getZ();
            decal.cy = groundY(center);
        }
        final Location at = new Location(center.getWorld(), center.getX(), decal.cy, center.getZ());
        if (fresh) {
            decal.size = radius * 2;
            decal.model = model;
            decal.caster = caster;
            decal.display = spawn(at, model, decal.size, 0, rgb);
            if (caster != null) {
                // 2026-10-31 照站長：別人的地面只看虛線外框（不畫底），自己的才看完整的
                decal.others = spawn(at, ending ? RING : GROUND_OUTLINE, decal.size, 0, rgb);
            }
            decals.put(id, decal);
            show(decal);
            return;
        }
        for (final ItemDisplay display : new ItemDisplay[] {decal.display, decal.others}) {
            if (display == null || !display.isValid()) {
                continue;
            }
            if (display.getLocation().distanceSquared(at) > 1e-4) {
                display.teleport(at);
            }
        }
        if (Math.abs(decal.size - radius * 2) > 0.02) {
            decal.size = radius * 2;
            for (final ItemDisplay display : new ItemDisplay[] {decal.display, decal.others}) {
                if (display == null || !display.isValid()) {
                    continue;
                }
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(4);
                display.setTransformation(transformation(decal.size, 0));
            }
        }
    }

    /** 2026-10-31：這一點腳下的地面頂面高度（往下找 4 格內第一個不能穿過的方塊；半磚、台階照它的高度）。 */
    static double groundY(final Location at) {
        final World world = at.getWorld();
        final int x = (int) Math.floor(at.getX());
        final int z = (int) Math.floor(at.getZ());
        final int top = (int) Math.floor(at.getY() + 0.5);
        for (int y = top; y >= top - 4; y--) {
            final org.bukkit.block.Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable()) {
                final org.bukkit.util.BoundingBox box = block.getBoundingBox();
                final double surface = box.getHeight() > 0 ? box.getMaxY() : y + 1;
                return surface + 0.03;
            }
        }
        return Math.floor(at.getY()) + 0.03;
    }

    /** 跟著實體走的圈（怪物的光環詞綴）：每 {@link #FOLLOW_STEP} tick 移到實體腳下（{@link #follow()}）。 */
    public void follow(final String id, final Entity owner, final double radius, final int rgb) {
        follow(id, owner, RING, radius * 2, rgb, 0, false);
    }

    /**
     * 跟著實體走的圖：model 是 exilecore:mark/ 底下的圖名，size 是整張圖有幾格寬，angle 是繞 Y 軸的起始角度（弧度）；
     * spin 時每 {@link #SPIN_STEP} tick 轉一點（{@link #spin}，角度照伺服器的 tick 算，同一個人的法陣與符文一起轉）。
     */
    public void follow(final String id, final Entity owner, final String model, final double size, final int rgb, final double angle,
                       final boolean spin) {
        Decal decal = decals.get(id);
        // 大小變了也重畫（2026-10：範圍變大之後，地上的圈還是原本的大小）
        if (decal != null && (decal.display == null || !decal.display.isValid() || decal.owner != owner
                || !decal.display.getWorld().equals(owner.getWorld()) || Math.abs(decal.size - size) > 0.01)) {
            remove(id);
            decal = null;
        }
        if (decal == null) {
            if (!owner.isValid()) {
                return;
            }
            decal = new Decal();
            decal.owner = owner;
            decal.size = size;
            decal.angle = angle;
            decal.spin = spin;
            decal.display = spawn(feet(owner), model, size, spin ? spinAngle(decal, now()) : angle, rgb);
            decals.put(id, decal);
        }
        show(decal);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static double spinAngle(final Decal decal, final long tick) {
        return decal.angle + tick * SPIN_RATE;
    }

    /** 每 {@link #SPIN_STEP} tick：會轉的圖送下一個角度，客戶端在接下來 {@link #SPIN_STEP} tick 裡補成連續的轉動。 */
    public void spin() {
        final long target = now() + SPIN_STEP;
        for (final Decal decal : decals.values()) {
            if (!decal.spin || decal.display == null || !decal.display.isValid()) {
                continue;
            }
            decal.display.setInterpolationDelay(0);
            decal.display.setInterpolationDuration(SPIN_STEP);
            decal.display.setTransformation(transformation(decal.size, spinAngle(decal, target)));
        }
    }

    private static Location feet(final Entity owner) {
        final Location at = owner.getLocation();
        return new Location(at.getWorld(), at.getX(), at.getY() + 0.03, at.getZ());
    }

    /** 每 {@link #FOLLOW_STEP} tick：跟著實體走的圈移到實體腳下。 */
    public void follow() {
        for (final Decal decal : decals.values()) {
            if (decal.owner == null || decal.display == null || !decal.display.isValid() || !decal.owner.isValid()
                    || !decal.owner.getWorld().equals(decal.display.getWorld())) {
                continue;
            }
            final Location target = feet(decal.owner);
            if (decal.display.getLocation().distanceSquared(target) > 0.0004) {
                decal.display.teleport(target);
            }
        }
    }

    public boolean has(final String id) {
        return decals.containsKey(id);
    }

    public void remove(final String id) {
        final Decal decal = decals.remove(id);
        if (decal == null) {
            return;
        }
        if (decal.display != null && decal.display.isValid()) {
            decal.display.remove();
        }
        if (decal.others != null && decal.others.isValid()) {
            decal.others.remove();
        }
    }

    /** 某個前綴開頭的全部收掉。 */
    public void removePrefix(final String prefix) {
        for (final String id : new ArrayList<>(decals.keySet())) {
            if (id.startsWith(prefix)) {
                remove(id);
            }
        }
    }

    /** 每秒：主人不在了的收掉；附近新來的（套了資源包的）玩家看得到。 */
    public void tick() {
        final Iterator<Map.Entry<String, Decal>> iterator = decals.entrySet().iterator();
        while (iterator.hasNext()) {
            final Decal decal = iterator.next().getValue();
            if (decal.display == null || !decal.display.isValid() || decal.owner != null && (!decal.owner.isValid() || decal.owner.isDead()
                    || !decal.owner.getWorld().equals(decal.display.getWorld()))) {
                if (decal.display != null && decal.display.isValid()) {
                    decal.display.remove();
                }
                if (decal.others != null && decal.others.isValid()) {
                    decal.others.remove();
                }
                iterator.remove();
                continue;
            }
            show(decal);
        }
    }

    public void clear() {
        for (final String id : new ArrayList<>(decals.keySet())) {
            remove(id);
        }
    }

    private void show(final Decal decal) {
        final World world = decal.display.getWorld();
        final Location at = decal.display.getLocation();
        for (final Player player : world.getPlayers()) {
            if (decal.caster != null) {
                // 2026-10-31：玩家的地面效果——施放者看完整的那片、別人看虛線外框；關掉「別人的技能特效」的人都不看
                final boolean mine = decal.caster.equals(player.getUniqueId());
                final boolean near = player.getLocation().distanceSquared(at) <= VIEW * VIEW && marked(player);
                visible(player, decal.display, near && mine);
                visible(player, decal.others, near && !mine && !Fx.hides(player));
                continue;
            }
            // 2026-10-18：用 canSee 判斷（重新登入後 showEntity 的狀態不在了，記 UUID 會讓重登的人永遠看不到）
            if (player.getLocation().distanceSquared(at) <= VIEW * VIEW && marked(player) && !player.canSee(decal.display)) {
                player.showEntity(plugin, decal.display);
            }
        }
    }

    private void visible(final Player player, final ItemDisplay display, final boolean see) {
        if (display == null || !display.isValid()) {
            return;
        }
        if (see && !player.canSee(display)) {
            player.showEntity(plugin, display);
        } else if (!see && player.canSee(display)) {
            player.hideEntity(plugin, display);
        }
    }

    private ItemDisplay spawn(final Location at, final String model, final double size, final double angle, final int rgb) {
        return at.getWorld().spawn(at, ItemDisplay.class, entity -> {
            entity.setVisibleByDefault(false); // 只有套了資源包的人看得到（沒套的會看到一張紙）
            entity.setPersistent(false);
            entity.addScoreboardTag(TAG);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            final ItemStack stack = new ItemStack(Material.PAPER);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("exilecore", "mark/" + model));
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(Color.fromRGB(rgb)).build());
            entity.setItemStack(stack);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setShadowRadius(0);
            entity.setViewRange(1.5f);
            entity.setTeleportDuration(FOLLOW_STEP);
            entity.setTransformation(transformation(size, angle));
        });
    }

    /** 平貼在地上、size 格寬、繞 Y 軸轉 angle 弧度。 */
    private static Transformation transformation(final double size, final double angle) {
        final float scale = (float) Math.max(0.05, size);
        final float[] rotation = GroundMarks.quaternion(angle);
        return new Transformation(new Vector3f(0f, 0f, 0f), new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]),
                new Vector3f(scale, 1f, scale), new Quaternionf());
    }

    /** 2026-10-31：色相在紅色 ±20 度內、又夠飽和的顏色，色相推到 25 度（橘紅）。 */
    /** 2026-10-31 照站長：玩家地面效果的顏色——不用紅色、飽和度降一半、亮一點（淡色，不搶 Boss 警示圈的眼）。 */
    public static int soft(final int rgb) {
        final int away = awayFromRed(rgb);
        final float[] hsb = java.awt.Color.RGBtoHSB(away >> 16 & 0xFF, away >> 8 & 0xFF, away & 0xFF, null);
        return java.awt.Color.HSBtoRGB(hsb[0], hsb[1] * 0.55f, Math.max(hsb[2], 0.9f)) & 0xFFFFFF;
    }

    public static int awayFromRed(final int rgb) {
        final float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
        final float degrees = hsb[0] * 360f;
        if (hsb[1] < 0.35f || degrees > 20f && degrees < 340f) {
            return rgb;
        }
        return java.awt.Color.HSBtoRGB(25f / 360f, hsb[1], Math.max(hsb[2], 0.75f)) & 0xFFFFFF;
    }

}
