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
 * 常駐的地面圈（2026-10，減少粒子）：光環、焚燒光環、怪物的光環詞綴、裂隙、祭壇這些「一直在的圈」，
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
    /** 轉多快：每 tick 幾弧度（16 秒一圈）。 */
    static final double SPIN_RATE = Math.PI * 2 / (16 * 20);

    private static final class Decal {
        ItemDisplay display;
        Entity owner;
        double size;
        double angle;
        boolean spin;
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

    /** 跟著實體走的圈（怪物的光環詞綴、焚燒光環）：每 {@link #FOLLOW_STEP} tick 移到實體腳下（{@link #follow()}）。 */
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
        // 大小變了也重畫（2026-10：焚心業火掛上擴散之後，地上的圈還是原本的大小，傷害範圍其實已經變大了）
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
            // 2026-10-18：用 canSee 判斷（重新登入後 showEntity 的狀態不在了，記 UUID 會讓重登的人永遠看不到）
            if (player.getLocation().distanceSquared(at) <= VIEW * VIEW && marked(player) && !player.canSee(decal.display)) {
                player.showEntity(plugin, decal.display);
            }
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
}
