package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冰晶（2026-10，照 PoE2 的 Ice Crystals）：霜壁、凍壁矢這類技能（技能自己帶 crystal.count）留下幾顆冰晶。
 * 你擊中冰晶旁邊（{@value #HIT_RADIUS} 格內）的敵人時，冰晶被打一下；打 {@value #BASE_HITS} 下（crystal.hits 可以改）就碎掉，
 * 炸出一圈冰（隱藏技能 proc_crystal_burst，用留下冰晶那個技能的寶石等級）。時間到沒打碎就自己融掉，不會炸。
 * 輔助：厚晶（要打更多下）、脆晶（更少下）、霜蝕晶（冰晶周圍一直冰）、晶刺（碎掉時往四周噴冰片）、冰鍍（打碎時你得到附加冰冷的增益）。
 */
public final class IceCrystals implements Listener {

    static final int BASE_HITS = 3;
    static final double HIT_RADIUS = 2.4;
    static final double BASE_SECONDS = 8;
    static final int MAX_PER_PLAYER = 20;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, List<Crystal>> crystals = new HashMap<>();

    private static final class Crystal {
        final Location at;
        final BlockDisplay display;
        final long untilTick;
        final int level;
        final boolean shards;
        final boolean verglas;
        int hitsLeft;
        long lastHit;

        Crystal(final Location at, final BlockDisplay display, final long untilTick, final int level, final int hits, final boolean shards,
                final boolean verglas) {
            this.at = at;
            this.display = display;
            this.untilTick = untilTick;
            this.level = level;
            this.hitsLeft = hits;
            this.shards = shards;
            this.verglas = verglas;
        }
    }

    IceCrystals(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個技能（照它的屬性）要留下幾顆冰晶；0 = 不留。 */
    static int count(final SkillBuild build, final StatSheet sheet) {
        return Math.max(0, Math.min(8, (int) Math.round(build.profile().effective(sheet).flat(StatKeys.CRYSTAL_COUNT, build.profile().tags()))));
    }

    /** 在 center 留下 count 顆冰晶：一顆放正中間，多顆沿著 along（單位向量）排成一排、間隔 1.6 格。 */
    void spawn(final Player owner, final SkillBuild build, final Location center, final int count, final Vector along) {
        if (count <= 0 || center.getWorld() == null) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(owner);
        final StatSheet effective = build.profile().effective(sheet);
        final java.util.Set<String> tags = build.profile().tags();
        final int hits = (int) Math.max(1, Math.round(effective.apply(StatKeys.CRYSTAL_HITS,
                BASE_HITS + effective.flat(StatKeys.CRYSTAL_HITS, tags), tags)));
        final boolean hoarfrost = StatKeys.flag(effective, StatKeys.FLAG_HOARFROST);
        final boolean shards = StatKeys.flag(effective, StatKeys.FLAG_CRYSTAL_SHARDS);
        final boolean verglas = StatKeys.flag(effective, StatKeys.FLAG_VERGLAS);
        final long until = now() + build.durationTicks(BASE_SECONDS);
        final List<Crystal> mine = crystals.computeIfAbsent(owner.getUniqueId(), key -> new ArrayList<>());
        final Vector step = along == null || along.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : along.clone().setY(0).normalize();
        for (int i = 0; i < count; i++) {
            final double offset = (i - (count - 1) / 2.0) * 1.6;
            final Location at = center.clone().add(step.clone().multiply(offset));
            at.setY(Math.floor(center.getY()));
            final BlockDisplay display = at.getWorld().spawn(at.clone().add(-0.35, 0, -0.35), BlockDisplay.class, d -> {
                d.setBlock(Material.BLUE_ICE.createBlockData());
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.7f, 1.1f, 0.7f), new AxisAngle4f()));
                d.setPersistent(false);
                d.setGlowing(true);
            });
            final Crystal crystal = new Crystal(at.clone().add(0, 0.5, 0), display, until, build.gemLevel(), hits, shards, verglas);
            mine.add(crystal);
            if (hoarfrost) {
                cast(owner, "proc_hoarfrost", crystal.level, crystal.at);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(owner.getUniqueId(), crystal), until - now() + 1);
        }
        while (mine.size() > MAX_PER_PLAYER) {
            final Crystal oldest = mine.removeFirst();
            oldest.display.remove();
        }
        center.getWorld().playSound(center, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    private void expire(final UUID owner, final Crystal crystal) {
        final List<Crystal> mine = crystals.get(owner);
        if (mine != null && mine.remove(crystal)) {
            crystal.display.remove();
            crystal.at.getWorld().spawnParticle(Particle.SNOWFLAKE, crystal.at, 8, 0.3, 0.4, 0.3, 0.01);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.skill() == null) {
            return;
        }
        final List<Crystal> mine = crystals.get(player.getUniqueId());
        if (mine == null || mine.isEmpty() || event.skill().id().startsWith("proc_crystal")
                || event.skill().id().startsWith("proc_hoarfrost")) { // 2026-10-17：冰晶旁邊的霜蝕地面也不打冰晶（以前站著就自己碎）
            return; // 冰晶自己炸出來的不再打冰晶（避免連鎖一次全碎）
        }
        final Location hit = event.defender().getLocation();
        final long now = now();
        final List<Crystal> broken = new ArrayList<>();
        for (final Iterator<Crystal> it = mine.iterator(); it.hasNext(); ) {
            final Crystal crystal = it.next();
            if (!crystal.at.getWorld().equals(hit.getWorld()) || crystal.at.distanceSquared(hit) > HIT_RADIUS * HIT_RADIUS
                    || crystal.lastHit == now) {
                continue;
            }
            crystal.lastHit = now; // 同一下打中好幾個敵人只算一下
            crystal.hitsLeft--;
            crystal.at.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, crystal.at, 6, 0.2, 0.3, 0.2, 0.05);
            if (crystal.hitsLeft <= 0) {
                it.remove();
                broken.add(crystal);
            }
        }
        for (final Crystal crystal : broken) {
            shatter(player, crystal);
        }
    }

    private void shatter(final Player owner, final Crystal crystal) {
        crystal.display.remove();
        crystal.at.getWorld().playSound(crystal.at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 0.9f);
        cast(owner, "proc_crystal_burst", crystal.level, crystal.at);
        if (crystal.shards) {
            cast(owner, "proc_crystal_shards", crystal.level, crystal.at);
        }
        if (crystal.verglas) {
            service.buff(owner, "verglas", "冰鍍", 120, List.of(new tw.exilecore.stats.Modifier(StatKeys.DAMAGE_GAIN + "cold",
                    tw.exilecore.stats.ModType.FLAT, 15, java.util.Set.of(), "冰鍍")));
        }
    }

    private void cast(final Player owner, final String skillId, final int level, final Location at) {
        final SkillDefinition definition = service.config().skill(skillId);
        if (definition == null) {
            return;
        }
        final PlayerState state = plugin.players().get(owner);
        final SkillBuild build = SkillBuild.of(service.config(), definition, Math.max(1, level), state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(owner));
        service.procCast(Origin.at(owner, at.clone(), SkillTargets.facing(owner)), build, null);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    void clear(final UUID owner) {
        final List<Crystal> mine = crystals.remove(owner);
        if (mine != null) {
            mine.forEach(crystal -> crystal.display.remove());
        }
    }

    /** 外掛關掉時全部拿掉。 */
    void clearAll() {
        crystals.values().forEach(list -> list.forEach(crystal -> crystal.display.remove()));
        crystals.clear();
    }
}
