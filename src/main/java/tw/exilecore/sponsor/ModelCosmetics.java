package tw.exilecore.sponsor;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3D 背飾與寵物（2026-10）：自己畫的模型（tools/gen_models3d.py → 介面資源包）放在展示實體（ItemDisplay）上，每 tick 跟著玩家。
 * <ul>
 *   <li>背飾：翅膀是左右兩片（各自繞轉軸慢慢拍動），光輪一片（在背後慢慢轉）。跟著身體的方向，在背後一點點。</li>
 *   <li>寵物：飄在肩膀後面、上下浮動，離太遠（傳送、換世界）就直接跳過去。</li>
 * </ul>
 * 展示實體不存檔（重開就沒了，啟用時也會把留下來的清掉）、不擋路、沒有碰撞。旁觀、隱形、死掉的時候收起來。
 * 只有好看：不影響戰鬥。沒送介面資源包時不出現（畫不出模型）。
 */
final class ModelCosmetics {

    /** 展示實體的記號（啟用時清掉上次留下來的）。 */
    static final String TAG = "exilecore_cosmetic3d";
    /** 背飾：轉軸離腳底多高、往後多少（格）。 */
    private static final float BACK_HEIGHT = 1.32f;
    private static final float BACK_BEHIND = -0.26f;
    /** 背飾的大小。 */
    private static final float WING_SCALE = 0.9f;
    private static final float HALO_SCALE = 0.8f;
    /** 寵物的大小、在玩家的哪裡（往左、往後、多高）。 */
    private static final float PET_SCALE = 0.55f;
    private static final double PET_SIDE = 0.85;
    private static final double PET_BEHIND = 0.7;
    private static final double PET_HEIGHT = 1.35;
    /** 寵物幾 tick 動一次（2026-10-21 效能）。 */
    private static final int PET_EVERY = 2;
    /** 寵物離目標點超過這麼遠就直接跳過去。 */
    private static final double PET_SNAP = 10;
    /** 動畫多久更新一次（tick）。 */
    private static final int ANIMATE = 4;

    private static final class Worn {
        String backArt;
        String petArt;
        final List<ItemDisplay> back = new ArrayList<>();
        /**
         * 只有本人看得到的那一份背飾（2026-10）：自己的畫面裡人是馬上動的，展示實體要等伺服器來回一趟才跟上，
         * 高速倒退時翅膀會「跑到前面」。給本人的這份照他的延遲往移動方向多推；別人看到的照舊（{@link #back}，本人看不到）。
         */
        final List<ItemDisplay> backOwn = new ArrayList<>();
        /** 上一 tick 玩家在哪（背飾往移動方向多推一點用）。 */
        Location lastAt;
        /** 3D 戰甲的肩甲（右、左）。 */
        String shoulderArt;
        final List<ItemDisplay> shoulders = new ArrayList<>();
        ItemDisplay pet;
        Location petAt;
        /** 2026-10-21 效能：上次送出去的位置（沒動就不再傳送；主城站著不動的人很多）。 */
        Location sentBack;
        Location sentOwn;
        Location sentPet;
    }

    /** 跟上次送出去的位置一樣（差不到 1/100 格、身體方向差不到 1 度）就不用再傳送。 */
    static boolean same(final Location sent, final Location now) {
        if (sent == null || sent.getWorld() != now.getWorld()) {
            return false;
        }
        final double yaw = Math.abs(((sent.getYaw() - now.getYaw()) % 360 + 540) % 360 - 180);
        return sent.distanceSquared(now) < 1.0e-4 && yaw < 1.0;
    }

    private final ExileCore plugin;
    private final SponsorService service;
    private final Map<UUID, Worn> worn = new HashMap<>();
    private long tick;

    ModelCosmetics(final ExileCore plugin, final SponsorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 啟用時：清掉上次沒收好的（當機、熱重載）。 */
    void cleanup() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG)) {
                    display.remove();
                }
            }
        }
    }

    void stop() {
        for (final Worn entry : worn.values()) {
            clear(entry);
        }
        worn.clear();
    }

    void forget(final UUID player) {
        final Worn entry = worn.remove(player);
        if (entry != null) {
            clear(entry);
        }
    }

    private static void clear(final Worn entry) {
        clearBack(entry);
        clearPet(entry);
        clearShoulders(entry);
    }

    private static void clearShoulders(final Worn entry) {
        for (final ItemDisplay display : entry.shoulders) {
            if (display.isValid()) {
                display.remove();
            }
        }
        entry.shoulders.clear();
        entry.shoulderArt = null;
    }

    private static void clearBack(final Worn entry) {
        for (final ItemDisplay display : entry.back) {
            if (display.isValid()) {
                display.remove();
            }
        }
        for (final ItemDisplay display : entry.backOwn) {
            if (display.isValid()) {
                display.remove();
            }
        }
        entry.back.clear();
        entry.backOwn.clear();
        entry.backArt = null;
        entry.sentBack = null;
        entry.sentOwn = null;
    }

    private static void clearPet(final Worn entry) {
        if (entry.pet != null && entry.pet.isValid()) {
            entry.pet.remove();
        }
        entry.pet = null;
        entry.petArt = null;
        entry.petAt = null;
        entry.sentPet = null;
    }

    /** 每 tick。 */
    void tick() {
        tick++;
        final boolean pack = service.packSent();
        for (final Iterator<Map.Entry<UUID, Worn>> it = worn.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Worn> entry = it.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                clear(entry.getValue());
                it.remove();
            }
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final SponsorConfig.Cosmetic back = pack && visible(player) ? service.transmog(player, SponsorService.Look.BACK) : null;
            final SponsorConfig.Cosmetic pet = pack && visible(player) ? service.transmog(player, SponsorService.Look.PET) : null;
            SponsorConfig.Cosmetic chest = pack && visible(player) ? service.transmog(player, SponsorService.Look.CHEST) : null;
            final ItemStack wornChest = player.getInventory().getChestplate();
            if (!SHOULDERS || !SponsorService.armor3d(chest) || wornChest == null || wornChest.isEmpty()) {
                chest = null; // 沒穿身體的裝備：沒有肩甲
            }
            Worn entry = worn.get(player.getUniqueId());
            if (entry == null) {
                if (back == null && pet == null && chest == null) {
                    continue;
                }
                entry = new Worn();
                worn.put(player.getUniqueId(), entry);
            }
            try {
                updateBack(player, entry, back);
                updatePet(player, entry, pet);
                updateShoulders(player, entry, chest);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("3D 時裝更新出錯（{}）：{}", player.getName(), e.toString());
                clear(entry);
            }
            if (entry.back.isEmpty() && entry.pet == null && entry.shoulders.isEmpty()) {
                worn.remove(player.getUniqueId());
            }
        }
    }

    private static boolean visible(final Player player) {
        return !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && !player.isInvisible()
                && !player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    // ---- 背飾 ----

    private void updateBack(final Player player, final Worn entry, final SponsorConfig.Cosmetic skin) {
        final String art = skin == null ? null : skin.art();
        if (art == null) {
            if (!entry.back.isEmpty()) {
                clearBack(entry);
            }
            return;
        }
        final boolean paired = SponsorService.pairedBack(skin);
        if (!art.equals(entry.backArt) || entry.back.isEmpty() || !entry.back.getFirst().isValid()
                || entry.back.getFirst().getWorld() != player.getWorld()) {
            clearBack(entry);
            entry.backArt = art;
            final List<String> models = paired ? List.of(art + "_back_r", art + "_back_l") : List.of(art + "_back");
            for (final String model : models) {
                final ItemDisplay shared = spawn(player.getLocation(), model);
                player.hideEntity(plugin, shared); // 別人看的這份本人看不到
                entry.back.add(shared);
                final ItemDisplay own = spawn(player.getLocation(), model, false);
                player.showEntity(plugin, own); // 只有本人看得到
                entry.backOwn.add(own);
            }
            for (final ItemDisplay display : entry.back) {
                display.setTeleportDuration(1); // 背飾：補間 1 tick（2 tick 會拖在後面）
            }
            for (final ItemDisplay display : entry.backOwn) {
                display.setTeleportDuration(1);
            }
        }
        // 2026-10：跟得更緊——往這一 tick 的移動方向多推一點（別人看你時有一點延遲），展示實體的移動補間只用 1 tick
        final Location before = entry.lastAt;
        final Location at = leading(player, entry);
        final Location own = ownLeading(player, before);
        at.setYaw(player.getBodyYaw());
        at.setPitch(0);
        final float height = player.isSneaking() ? BACK_HEIGHT - 0.3f : player.isSwimming() || player.isGliding() ? 0.4f : BACK_HEIGHT;
        if (!same(entry.sentBack, at)) { // 2026-10-21 效能：站著不動就不送
            for (final ItemDisplay display : entry.back) {
                display.teleport(at);
            }
            entry.sentBack = at.clone();
        }
        own.setYaw(player.getBodyYaw());
        own.setPitch(0);
        if (!same(entry.sentOwn, own)) {
            for (final ItemDisplay display : entry.backOwn) {
                display.teleport(own);
            }
            entry.sentOwn = own.clone();
        }
        if (tick % ANIMATE != 0) {
            return;
        }
        final double time = tick / 20.0;
        if (paired) {
            // 拍翅膀：兩片繞著垂直的轉軸往後擺（8° 到 30°），移動的時候拍快一點
            final boolean moving = player.getVelocity().setY(0).lengthSquared() > 0.003 || player.isSprinting();
            final double speed = moving ? 2.6 : 1.1;
            final float swing = (float) Math.toRadians(19 + 11 * Math.sin(time * speed * Math.PI));
            for (int i = 0; i < entry.back.size(); i++) {
                final float angle = i == 0 ? -swing : swing; // 0 = 右片、1 = 左片
                final Quaternionf rotation = new Quaternionf(new AxisAngle4f(angle, 0, 1, 0));
                set(entry.back.get(i), new Vector3f(0, height, BACK_BEHIND), rotation, WING_SCALE);
                if (i < entry.backOwn.size()) {
                    set(entry.backOwn.get(i), new Vector3f(0, height, BACK_BEHIND), rotation, WING_SCALE);
                }
            }
        } else {
            // 光輪：在背後慢慢轉
            final float spin = (float) Math.toRadians((time * 30) % 360);
            final Quaternionf rotation = new Quaternionf(new AxisAngle4f(spin, 0, 0, 1));
            set(entry.back.getFirst(), new Vector3f(0, height + 0.15f, BACK_BEHIND - 0.08f), rotation, HALO_SCALE);
            if (!entry.backOwn.isEmpty()) {
                set(entry.backOwn.getFirst(), new Vector3f(0, height + 0.15f, BACK_BEHIND - 0.08f), rotation, HALO_SCALE);
            }
        }
    }

    // ---- 3D 戰甲的肩甲 ----

    /**
     * 肩甲先關掉（2026-10）：展示實體只能跟著玩家的位置和身體方向，手臂擺動、蹲下前傾、別人看到的延遲都對不上，
     * 看起來會飄在肩膀旁邊。原版沒辦法把模型綁在手臂上，所以 3D 戰甲先只有 3D 頭盔。
     */
    private static final boolean SHOULDERS = false;

    /** 肩膀：離中線多遠、離腳底多高（格）。 */
    private static final float SHOULDER_SIDE = 0.37f;
    private static final float SHOULDER_HEIGHT = 1.42f;

    private void updateShoulders(final Player player, final Worn entry, final SponsorConfig.Cosmetic skin) {
        final String art = skin == null ? null : skin.art();
        if (art == null) {
            if (!entry.shoulders.isEmpty()) {
                clearShoulders(entry);
            }
            return;
        }
        if (!art.equals(entry.shoulderArt) || entry.shoulders.isEmpty() || !entry.shoulders.getFirst().isValid()
                || entry.shoulders.getFirst().getWorld() != player.getWorld()) {
            clearShoulders(entry);
            entry.shoulderArt = art;
            entry.shoulders.add(spawn(player.getLocation(), art + "_pauldron_r"));
            entry.shoulders.add(spawn(player.getLocation(), art + "_pauldron_l"));
        }
        final Location at = player.getLocation();
        at.setYaw(player.getBodyYaw());
        at.setPitch(0);
        for (final ItemDisplay display : entry.shoulders) {
            display.teleport(at);
        }
        if (tick % ANIMATE != 0) {
            return;
        }
        final boolean sneaking = player.isSneaking();
        final boolean low = player.isSwimming() || player.isGliding();
        final float height = low ? 0.45f : sneaking ? SHOULDER_HEIGHT - 0.3f : SHOULDER_HEIGHT;
        final float forward = sneaking ? 0.12f : 0f; // 蹲下時身體往前傾
        for (int i = 0; i < entry.shoulders.size(); i++) {
            // 0 = 右肩（面向的右手邊 = 本地的 −x）、1 = 左肩
            final float side = i == 0 ? -SHOULDER_SIDE : SHOULDER_SIDE;
            set(entry.shoulders.get(i), new Vector3f(side, height, forward), new Quaternionf(), 1.0f);
        }
    }

    /** 背飾要放的位置：現在的位置，再往這一 tick 的移動多推一點（走路時才不會拖在後面）。 */
    private static Location leading(final Player player, final Worn entry) {
        final Location now = player.getLocation();
        final Location before = entry.lastAt;
        entry.lastAt = now.clone();
        if (before == null || before.getWorld() != now.getWorld() || before.distanceSquared(now) > 4) {
            return now; // 剛出現、傳送、換世界：不推
        }
        return now.clone().add(now.toVector().subtract(before.toVector()).multiply(LEAD));
    }

    /**
     * 本人看的那份背飾放哪：往移動方向推「延遲＋2 tick」的距離（伺服器收到位置、送回展示實體、補間各要一點時間），
     * 最多推 8 tick；沒在動、剛傳送就放在原地。
     */
    private static Location ownLeading(final Player player, final Location before) {
        final Location now = player.getLocation();
        if (before == null || before.getWorld() != now.getWorld() || before.distanceSquared(now) > 4) {
            return now;
        }
        final double ticks = Math.min(8, Math.max(0, player.getPing()) / 50.0 + 2);
        return now.clone().add(now.toVector().subtract(before.toVector()).multiply(ticks));
    }

    /** 往移動方向多推幾 tick 的距離。 */
    private static final double LEAD = 0.5;

    // ---- 寵物 ----

    private void updatePet(final Player player, final Worn entry, final SponsorConfig.Cosmetic skin) {
        final String art = skin == null ? null : skin.art();
        if (art == null) {
            if (entry.pet != null) {
                clearPet(entry);
            }
            return;
        }
        final Location target = petTarget(player);
        if (!art.equals(entry.petArt) || entry.pet == null || !entry.pet.isValid() || entry.pet.getWorld() != player.getWorld()) {
            clearPet(entry);
            entry.petArt = art;
            entry.pet = spawn(target, art + "_pet");
            entry.petAt = target.clone();
            set(entry.pet, new Vector3f(), new Quaternionf(), PET_SCALE);
        }
        if (tick % PET_EVERY != 0 && entry.sentPet != null) {
            return; // 2026-10-21 效能：寵物 2 tick 動一次（補間 2 tick，看起來一樣順）
        }
        final Location now = entry.petAt;
        if (now.getWorld() != target.getWorld() || now.distanceSquared(target) > PET_SNAP * PET_SNAP) {
            entry.petAt = target.clone();
        } else {
            // 慢慢跟上（離越遠跟得越快）
            final Vector step = target.toVector().subtract(now.toVector()).multiply(0.4); // 2 tick 一次：0.22 → 0.4
            now.add(step);
        }
        final double bob = Math.sin(tick / 20.0 * 2.2) * 0.08;
        final Location place = entry.petAt.clone().add(0, bob, 0);
        place.setYaw(player.getLocation().getYaw());
        place.setPitch(0);
        if (!same(entry.sentPet, place)) {
            entry.pet.teleport(place);
            entry.sentPet = place.clone();
        }
    }

    /** 寵物要飄去的地方：玩家左後方、肩膀的高度。 */
    private static Location petTarget(final Player player) {
        final Location at = player.getLocation();
        final double yaw = Math.toRadians(player.getBodyYaw());
        // 面向 = (−sin, cos)；左手邊 = (cos, sin)
        final double fx = -Math.sin(yaw);
        final double fz = Math.cos(yaw);
        final double lx = Math.cos(yaw);
        final double lz = Math.sin(yaw);
        return new Location(at.getWorld(), at.getX() + lx * PET_SIDE - fx * PET_BEHIND, at.getY() + PET_HEIGHT,
                at.getZ() + lz * PET_SIDE - fz * PET_BEHIND);
    }

    // ---- 展示實體 ----

    private ItemDisplay spawn(final Location at, final String model) {
        return spawn(at, model, true);
    }

    /** visibleByDefault false：生出來就只有之後 showEntity 的人看得到（本人專用的那份背飾）。 */
    private ItemDisplay spawn(final Location at, final String model, final boolean visibleByDefault) {
        final ItemStack stack = ItemStack.of(Material.PAPER);
        stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL,
                new org.bukkit.NamespacedKey("exilecore", "skin/" + model));
        return at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setItemStack(stack);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBillboard(Display.Billboard.FIXED);
            display.setPersistent(false);
            display.addScoreboardTag(TAG);
            display.setTeleportDuration(2);
            display.setInterpolationDuration(ANIMATE);
            display.setShadowRadius(0);
            display.setViewRange(1.0f);
            display.setVisibleByDefault(visibleByDefault);
        });
    }

    private static void set(final ItemDisplay display, final Vector3f translation, final Quaternionf rotation, final float scale) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ANIMATE);
        display.setTransformation(new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    /** 給 /ec 之類查看：現在有幾個玩家戴著 3D 背飾或寵物。 */
    int active() {
        return worn.size();
    }

    @SuppressWarnings("unused")
    private static boolean ours(final Entity entity) {
        return entity instanceof ItemDisplay && entity.getScoreboardTags().contains(TAG);
    }
}
