package tw.exilecore.map;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26（PoE2 換界石）：地圖詞綴帶來的場地危害——
 * <ul>
 *   <li>週期詛咒（map.curse_zone.enfeeble／temporal_chains／elemental_weakness）：每 {@link #CURSE_INTERVAL} tick 在每個玩家腳下
 *       出現一圈詛咒（{@link #CURSE_RADIUS} 格），{@link #CURSE_DELAY} tick 後還站在圈裡的人被詛咒 {@link #CURSE_SECONDS} 秒；</li>
 *   <li>地面效果（map.ground.fire／chill／shock）：開地圖時在地板上撒一塊塊點燃、冰緩、感電的地面
 *       （PoE2：每 100 格地圖區塊 3～8 塊，換算成每 {@link #TILE_AREA} 格地板一份）。</li>
 * </ul>
 */
final class MapHazards {

    static final long CURSE_INTERVAL = 200;
    static final long CURSE_DELAY = 30;
    static final double CURSE_RADIUS = 3.0;
    static final double CURSE_SECONDS = 6;
    /** PoE2 一個地圖區塊大約 23 單位 × 23 單位，100 塊 ≈ 530 平方公尺（格）。 */
    static final double TILE_AREA = 530;
    /** PoE2 怪物傷害表 68 級的每下平均（地面點燃的「每 10 秒 x 點」以這個等級為準，照怪物傷害表換到其他等級）。 */
    static final double FIRE_REFERENCE_DAMAGE = 233.12;
    /** 換界石的週期詛咒用的固定的源頭（不是哪個玩家放的）。 */
    static final UUID SOURCE = UUID.nameUUIDFromBytes("exilecore:map-curse".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final ExileCore plugin;
    private final MapService service;
    private final Random random = new Random();
    /** 實例 → 下一次週期詛咒的 tick。 */
    private final Map<Integer, Long> nextCurse = new HashMap<>();
    /** 撒過地面效果的實例。 */
    private final Set<Integer> grounded = new HashSet<>();
    private final List<Zone> zones = new ArrayList<>();

    private record Zone(int instance, Location center, String curse, long fireAt) {
    }

    MapHazards(final ExileCore plugin, final MapService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 每 10 tick。 */
    void tick(final long now) {
        final Set<Integer> alive = new HashSet<>();
        for (final MapInstance instance : service.instances()) {
            alive.add(instance.id());
            final MapStats stats = instance.stats();
            if (stats == null) {
                continue;
            }
            ground(instance, stats);
            final List<String> curses = curses(stats);
            if (!curses.isEmpty()) {
                final long next = nextCurse.computeIfAbsent(instance.id(), id -> now + CURSE_INTERVAL);
                if (now >= next) {
                    nextCurse.put(instance.id(), now + CURSE_INTERVAL);
                    for (final UUID uuid : instance.inside()) {
                        final Player player = plugin.getServer().getPlayer(uuid);
                        if (player != null && !player.isDead() && instance.contains(player.getLocation())) {
                            zones.add(new Zone(instance.id(), player.getLocation(), curses.get(random.nextInt(curses.size())), now + CURSE_DELAY));
                            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.6f, 0.7f);
                        }
                    }
                }
            }
        }
        // 實例關了：詛咒圈、地面效果收掉
        nextCurse.keySet().retainAll(alive);
        for (final Iterator<Integer> it = grounded.iterator(); it.hasNext(); ) {
            final int id = it.next();
            if (!alive.contains(id)) {
                if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
                    plugin.monsterModule().mechanics().clearMapPatches(owner(id));
                }
                it.remove();
            }
        }
        for (final Iterator<Zone> it = zones.iterator(); it.hasNext(); ) {
            final Zone zone = it.next();
            if (!alive.contains(zone.instance())) {
                it.remove();
                continue;
            }
            if (now < zone.fireAt()) {
                ring(zone.center(), Particle.WITCH);
                continue;
            }
            it.remove();
            ring(zone.center(), Particle.SOUL);
            for (final Player player : zone.center().getNearbyPlayers(CURSE_RADIUS, 2)) {
                curse(player, zone.curse(), now);
            }
        }
    }

    /** 這張圖有哪幾種週期詛咒。 */
    static List<String> curses(final MapStats stats) {
        final List<String> list = new ArrayList<>();
        for (final String kind : List.of("enfeeble", "temporal_chains", "elemental_weakness")) {
            if (stats.extra("map.curse_zone." + kind) > 0) {
                list.add(kind);
            }
        }
        return list;
    }

    /**
     * 詛咒的效果（照我們的詛咒技能 20 級的數值）：萎力 = 造成的傷害更少 29%；遲滯 = 移動慢 30%、技能冷卻增加 35%；
     * 三元 = 火焰、冰冷、閃電抗性 −24%。
     */
    static Debuffs.Debuff debuff(final String kind, final long until) {
        return switch (kind) {
            case "enfeeble" -> new Debuffs.Debuff("map_curse.enfeeble", SOURCE, until, 0, false, null,
                    List.of(Modifier.more(StatKeys.DAMAGE, -0.29, "換界石：萎力詛咒")), 0);
            case "temporal_chains" -> new Debuffs.Debuff("map_curse.temporal_chains", SOURCE, until, 0, false, null,
                    List.of(Modifier.increased(StatKeys.SKILL_COOLDOWN, 0.35, "換界石：遲滯詛咒")), 0.30);
            default -> new Debuffs.Debuff("map_curse.elemental_weakness", SOURCE, until, 0, false, null,
                    List.of(Modifier.flat(StatKeys.RESIST_FIRE, -24, "換界石：三元詛咒"), Modifier.flat(StatKeys.RESIST_COLD, -24, "換界石：三元詛咒"),
                            Modifier.flat(StatKeys.RESIST_LIGHTNING, -24, "換界石：三元詛咒")), 0);
        };
    }

    private void curse(final Player player, final String kind, final long now) {
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        // 2026-10-26（計數之戒）：你身上的詛咒持續時間減少 x%
        final double seconds = CURSE_SECONDS * Math.max(0.1, 1 + plugin.players().sheet(player)
                .increased(tw.exilecore.item.unique.UniqueStats.CURSE_SELF_DURATION, Set.of()));
        final long until = now + Math.round(seconds * 20);
        plugin.combat().debuffs().applyCurse(player.getUniqueId(), debuff(kind, until), "map_curse." + kind);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.4f, 1.6f);
        player.sendMessage(plugin.text().parse("<#b07aff>你被換界石的" + switch (kind) {
            case "enfeeble" -> "萎力詛咒";
            case "temporal_chains" -> "遲滯詛咒";
            default -> "三元詛咒";
        } + "纏上了（" + Math.round(seconds) + " 秒）"));
    }

    private static void ring(final Location center, final Particle particle) {
        for (int i = 0; i < 16; i++) {
            final double angle = Math.PI * 2 * i / 16;
            center.getWorld().spawnParticle(particle, center.getX() + Math.cos(angle) * CURSE_RADIUS, center.getY() + 0.1,
                    center.getZ() + Math.sin(angle) * CURSE_RADIUS, 1, 0, 0, 0, 0);
        }
    }

    static String owner(final int instanceId) {
        return "map:" + instanceId;
    }

    /** 開地圖之後第一次看到：照詞綴撒地面效果（一張圖只撒一次）。 */
    private void ground(final MapInstance instance, final MapStats stats) {
        if (grounded.contains(instance.id())) {
            return;
        }
        final double fire = stats.extra("map.ground.fire");
        final double chill = stats.extra("map.ground.chill");
        final double shock = stats.extra("map.ground.shock");
        final double perHundred = stats.extra("map.ground.patches");
        if (fire <= 0 && chill <= 0 && shock <= 0 || perHundred <= 0 || instance.world() == null
                || plugin.monsterModule() == null || plugin.monsterModule().mechanics() == null) {
            grounded.add(instance.id());
            return;
        }
        grounded.add(instance.id());
        final MapLayout layout = instance.layout();
        final List<int[]> floor = new ArrayList<>();
        for (int x = layout.minX(); x <= layout.maxX(); x++) {
            for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                if (layout.isFloor(x, z)) {
                    floor.add(new int[] {x, z});
                }
            }
        }
        if (floor.isEmpty()) {
            return;
        }
        final int count = (int) Math.max(1, Math.round(perHundred * floor.size() / TILE_AREA));
        final double radius = Math.max(1, stats.extra("map.ground.radius") / 10.0); // PoE2 的半徑單位 ÷ 10 = 公尺（格）
        final AilmentType type = fire > 0 ? AilmentType.IGNITE : chill > 0 ? AilmentType.CHILL : AilmentType.SHOCK;
        final double value;
        if (fire > 0) {
            final double[] damage = plugin.monsters() == null ? new double[] {1, 1} : plugin.monsters().config().damageAt(instance.level());
            value = fire / 10.0 * ((damage[0] + damage[1]) / 2) / FIRE_REFERENCE_DAMAGE; // 每秒火焰傷害
        } else if (chill > 0) {
            value = chill / 100.0;
        } else {
            value = 0.2; // PoE2 感電地面：受到的傷害增加 20%
        }
        final long until = plugin.getServer().getCurrentTick() + 20L * 60 * 60 * 3; // 實例關掉時另外收
        for (int i = 0; i < count; i++) {
            final int[] cell = floor.get(random.nextInt(floor.size()));
            final Location at = new Location(instance.world(), instance.originX() + cell[0] + 0.5, MapLayout.FLOOR_Y + 1,
                    instance.originZ() + cell[1] + 0.5);
            plugin.monsterModule().mechanics().addMapPatch(owner(instance.id()), at, type, value, radius, until);
        }
    }
}
