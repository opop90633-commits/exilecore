package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tw.exilecore.ExileCore;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-31 照站長：德魯伊變身的外觀，用原版生物重現——熊形態是北極熊、狼人形態是放大的狼（紅眼）、飛龍形態是放大的夜魅
 * （飛在頭頂高度）。變身中的人隱形，別人看不到他身上的裝備，只看到那隻野獸跟著他走、轉向；自己看不到那隻（不擋視線）。
 * <p>
 * 那隻野獸只是外觀：不會動、不會打、打不死、不會被打到（沒有怪物資料，技能不會把它當目標）、不會被太陽燒。
 * 變身的增益沒了（時間到、換回人形、死亡、下線、換世界）就收掉，裝備也重新送給大家。
 */
public final class ShapeshiftCostumes implements Listener {

    /** 外觀實體都掛這個標籤（萬一沒清到可以找出來）。 */
    static final String TAG = "exilecore_costume";
    /** 多久重送一次「看不到裝備」（tick；換裝備時伺服器會再送一次真的）。 */
    private static final int EQUIPMENT_EVERY = 10;
    /** 幾格內的玩家要重送（看得到的範圍）。 */
    private static final double VIEW = 64;

    /**
     * 2026-11-05 照站長「先關閉變形這個部分，變形後還是自己的 skin」：關掉外觀（不隱形、不生野獸、裝備照常給人看）；
     * 形態的效果照舊。tick 還是照跑，關掉之前已經穿上的會照常脫掉。改回 true 就恢復（模組 1.5.0 的第三人稱顯示也還在）。
     */
    static final boolean ENABLED = false;

    /**
     * 2026-11-05 照站長「德魯伊變形改成頭套，三種都先做」：變身時頭上的顯示用物品換成那個形態的 3D 頭套（資源包 exilecore:skin/form_<形態>_helmet，tools/gen_models3d.py 的 form_*），
     * 身上的盔甲不顯示、本人隱形（頭上的東西隱形也照畫）。遊戲在第一人稱本來就不畫自己頭上的東西，所以第一人稱看不到、
     * 第三人稱與別人看得到；不用模組、不生假生物（不擋視線、不推人）。頭上的東西由 Gear.sync 放（headModel）。
     * 缺點：模型跟著頭轉，抬頭、低頭會一起傾斜。關掉（false）就跟 ENABLED 一樣只有效果、沒有外觀。
     */
    public static final boolean HEAD_MODE = true;

    private static final Map<String, String> HEAD_MODELS = Map.of(
            "熊形態", "form_bear", "狼人形態", "form_werewolf", "飛龍形態", "form_wyvern");

    /** 頭套模式：正在變身的人 → 形態名稱；我們加了隱形的人。 */
    private final Map<UUID, String> heads = new HashMap<>();
    private final java.util.Set<UUID> headInvisible = new java.util.HashSet<>();

    /** 頭上該戴的頭套模型代號（form_bear…）；沒變身、頭套模式關掉回傳 null。Gear.sync 用。 */
    public String headModel(final Player player) {
        if (!HEAD_MODE || player == null) {
            return null;
        }
        final String form = heads.get(player.getUniqueId());
        return form == null ? null : HEAD_MODELS.get(form);
    }

    private void headTick(final Player player, final String form, final long now) {
        final UUID id = player.getUniqueId();
        final boolean wants = HEAD_MODELS.containsKey(form) && !player.isDead() && player.isOnline();
        final String was = heads.get(id);
        if (!wants) {
            if (was != null) {
                heads.remove(id);
                if (headInvisible.remove(id) && player.isOnline()) {
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                }
                syncGear(player); // 頭盔、盔甲換回來
            }
            return;
        }
        if (!form.equals(was)) {
            heads.put(id, form);
            syncGear(player);
        }
        if (now % 10 == 0 && (!player.hasPotionEffect(PotionEffectType.INVISIBILITY) || headInvisible.contains(id))) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
            headInvisible.add(id);
        }
    }

    private void syncGear(final Player player) {
        if (plugin.characters() != null && plugin.characters().gear() != null) {
            plugin.characters().gear().sync(player);
        }
    }

    private record Look(EntityType type, double scale, double lift) {
    }

    private static final Map<String, Look> LOOKS = Map.of(
            "熊形態", new Look(EntityType.POLAR_BEAR, 1.15, 0),
            "狼人形態", new Look(EntityType.WOLF, 1.9, 0),
            "飛龍形態", new Look(EntityType.PHANTOM, 1.7, 0.9));

    private static final class Costume {
        String form;
        LivingEntity entity;
        boolean invisibleByUs;
    }

    private final ExileCore plugin;
    private final Map<UUID, Costume> costumes = new HashMap<>();

    ShapeshiftCostumes(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 每 tick：照現在的形態穿上、換掉或脫掉外觀，外觀跟著人走。form 是空字串 = 沒變身。 */
    void tick(final Player player, final String form, final long now) {
        if (HEAD_MODE) {
            headTick(player, form, now); // 2026-11-05：頭套模式
        }
        final Look look = ENABLED && !HEAD_MODE ? LOOKS.get(form) : null; // 2026-11-05：外觀先關掉；頭套模式時不生假生物
        Costume costume = costumes.get(player.getUniqueId());
        if (look == null || player.isDead() || !player.isOnline()) {
            if (costume != null) {
                takeOff(player, costume);
                costumes.remove(player.getUniqueId());
            }
            return;
        }
        if (costume != null && (!form.equals(costume.form) || costume.entity == null || !costume.entity.isValid()
                || !costume.entity.getWorld().equals(player.getWorld()))) {
            removeEntity(costume);
            costume.entity = null;
        }
        if (costume == null) {
            costume = new Costume();
            costumes.put(player.getUniqueId(), costume);
        }
        costume.form = form;
        if (costume.entity == null) {
            costume.entity = spawn(player, look);
            if (seesOwn(player)) {
                player.showEntity(plugin, costume.entity); // 2026-11-05 照站長「第三人稱變形時會隱形」：裝了模組（1.5.0 起）的人自己也看得到，模組在第一人稱時不畫
            } else {
                player.hideEntity(plugin, costume.entity); // 自己看不到：不擋視線（spawn 時已經預設看不到，這行是保險）
            }
            showToOthers(player, costume.entity);
            hideEquipment(player);
        }
        final Location at = player.getLocation().add(0, look.lift(), 0);
        costume.entity.teleport(at);
        costume.entity.setRotation(at.getYaw(), at.getPitch());
        costume.entity.setFireTicks(0);
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY) || costume.invisibleByUs) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
            costume.invisibleByUs = true;
        }
        if (now % EQUIPMENT_EVERY == 0) {
            hideEquipment(player);
            showToOthers(player, costume.entity); // 2026-11-05：後來走進來的人也看得到
            if (seesOwn(player) && !player.canSee(costume.entity)) {
                player.showEntity(plugin, costume.entity); // 2026-11-05：變身之後才連上模組的也看得到自己的
            }
        }
    }

    /**
     * 2026-11-05 照站長「第一人稱要看不到德魯伊的變形，並且不能被自己的變形推擠」：外觀實體預設誰都看不到（spawn 時就設，
     * 變身的人從頭到尾收不到這隻）、只給附近別的玩家看。這次之前生出來之後才對自己 hideEntity，生出來那一下會先送到自己的畫面
     * （看得到一下、被它擠開）。
     */
    private void showToOthers(final Player owner, final LivingEntity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        for (final Player viewer : owner.getWorld().getPlayers()) {
            if (viewer != owner && !viewer.canSee(entity) && viewer.getLocation().distanceSquared(owner.getLocation()) <= VIEW * VIEW) {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    /** 2026-11-05：外觀實體放進「不推擠」的隊伍（碰撞規則 NEVER）：伺服器那邊也不會把變身的人推開。 */
    private static final String NO_PUSH_TEAM = "ec_costume";

    /**
     * 2026-11-05 照站長「第三人稱變形時會隱形」：每個人的外觀實體各自一個隊伍「ecc_＋變身的人的 UUID（沒有 -）」（碰撞規則一樣 NEVER）。
     * 模組（1.5.0 起）看到隊伍名稱是自己的就知道這隻是自己的變形，第一人稱時不畫、第三人稱照畫。
     * 這次之前全部放在同一個 {@link #NO_PUSH_TEAM}（模組分不出是誰的），自己一律看不到，第三人稱就變成隱形。
     */
    static final String OWN_TEAM_PREFIX = "ecc_";

    /** 裝了模組、而且是會在第一人稱藏起自己變形的版本。 */
    static final String SEES_OWN_SINCE = "1.5.0";

    static String ownTeam(final java.util.UUID owner) {
        return OWN_TEAM_PREFIX + owner.toString().replace("-", "");
    }

    private boolean seesOwn(final Player player) {
        return plugin.clientMod() != null && plugin.clientMod().modAtLeast(player, SEES_OWN_SINCE);
    }

    private static void noPush(final LivingEntity entity, final java.util.UUID owner) {
        final org.bukkit.scoreboard.Scoreboard board = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        final String name = ownTeam(owner);
        org.bukkit.scoreboard.Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        team.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        team.addEntry(entity.getUniqueId().toString());
    }

    private static void leaveNoPush(final LivingEntity entity) {
        final org.bukkit.scoreboard.Scoreboard board = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
        final org.bukkit.scoreboard.Team team = board.getEntryTeam(entity.getUniqueId().toString());
        if (team != null && (team.getName().equals(NO_PUSH_TEAM) || team.getName().startsWith(OWN_TEAM_PREFIX))) {
            team.removeEntry(entity.getUniqueId().toString());
            if (team.getName().startsWith(OWN_TEAM_PREFIX) && team.getEntries().isEmpty()) {
                team.unregister(); // 每個人一個的隊伍用完就收掉，不留在主計分板
            }
        }
    }

    private LivingEntity spawn(final Player player, final Look look) {
        final Location at = player.getLocation().add(0, look.lift(), 0);
        return (LivingEntity) at.getWorld().spawn(at, look.type().getEntityClass(), entity -> {
            final LivingEntity living = (LivingEntity) entity;
            living.addScoreboardTag(TAG);
            living.setVisibleByDefault(false); // 2026-11-05：預設誰都看不到，只給別的玩家看（showToOthers）；變身的人自己永遠收不到
            living.setPersistent(false);
            living.setRemoveWhenFarAway(false);
            living.setAI(false);
            living.setSilent(true);
            living.setInvulnerable(true);
            living.setCollidable(false);
            living.setGravity(false);
            living.setCanPickupItems(false);
            if (living instanceof org.bukkit.entity.Ageable ageable) {
                ageable.setAdult();
            }
            if (living instanceof Wolf wolf) {
                wolf.setAngry(true); // 紅眼
            }
            final AttributeInstance scale = living.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(look.scale());
            }
            noPush(living, player.getUniqueId()); // 2026-11-05：不會把變身的人推開
        });
    }

    /** 附近的人看不到這個人身上的裝備（隱形之後裝備還是會浮在空中）。 */
    private void hideEquipment(final Player player) {
        final Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
        for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            empty.put(slot, new ItemStack(Material.AIR));
        }
        for (final Player viewer : player.getWorld().getPlayers()) {
            if (viewer != player && viewer.getLocation().distanceSquared(player.getLocation()) <= VIEW * VIEW) {
                viewer.sendEquipmentChange(player, empty);
            }
        }
    }

    /** 換回人形：外觀收掉、拿掉我們加的隱形、裝備重新送給大家。 */
    private void takeOff(final Player player, final Costume costume) {
        removeEntity(costume);
        if (costume.invisibleByUs && player.isOnline()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (player.isOnline()) {
            final Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
            for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                final ItemStack item = player.getInventory().getItem(slot);
                real.put(slot, item == null ? new ItemStack(Material.AIR) : item);
            }
            for (final Player viewer : player.getWorld().getPlayers()) {
                if (viewer != player) {
                    viewer.sendEquipmentChange(player, real);
                }
            }
        }
    }

    private static void removeEntity(final Costume costume) {
        if (costume.entity != null) {
            leaveNoPush(costume.entity); // 2026-11-05：隊伍裡不留舊的
        }
        if (costume.entity != null && costume.entity.isValid()) {
            costume.entity.remove();
        }
    }

    /** 下線、停用：全部收掉。 */
    void clear() {
        for (final UUID id : java.util.List.copyOf(heads.keySet())) { // 2026-11-05：頭套模式
            final Player player = plugin.getServer().getPlayer(id);
            heads.remove(id);
            if (player != null) {
                if (headInvisible.contains(id)) {
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                }
                syncGear(player);
            }
        }
        headInvisible.clear();
        final Iterator<Map.Entry<UUID, Costume>> iterator = costumes.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Costume> entry = iterator.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                takeOff(player, entry.getValue());
            } else {
                removeEntity(entry.getValue());
            }
            iterator.remove();
        }
    }

    /** 不在線上的人：外觀收掉。 */
    void prune() {
        heads.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null); // 2026-11-05：頭套模式
        headInvisible.removeIf(id -> plugin.getServer().getPlayer(id) == null);
        final Iterator<Map.Entry<UUID, Costume>> iterator = costumes.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Costume> entry = iterator.next();
            if (plugin.getServer().getPlayer(entry.getKey()) == null) {
                removeEntity(entry.getValue());
                iterator.remove();
            }
        }
    }

    private static boolean costume(final Entity entity) {
        return entity.getScoreboardTags().contains(TAG);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        if (costume(event.getEntity())) {
            event.setCancelled(true); // 夜魅不會被太陽燒
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (costume(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(final EntityTargetEvent event) {
        if (event.getTarget() != null && costume(event.getTarget()) || costume(event.getEntity())) {
            event.setCancelled(true); // 怪物不會去追外觀、外觀也不會追人
        }
    }

    /** 只用在 {@link Mob}：避免外觀被當成可以騎、可以拴的。 */
    static boolean isCostume(final Entity entity) {
        return entity instanceof Mob && costume(entity);
    }
}
