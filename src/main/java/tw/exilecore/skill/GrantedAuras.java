package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-26：權杖、長杖賦予的三個特殊常駐技能（照 PoE2）。
 * <ul>
 *   <li><b>雷霆共鳴</b>（Fulmination）：開著時，你的技能第一次打中身邊（Presence）感電中的敵人，也打身邊其他感電中的敵人（最多 8 個），
 *       多打的那幾下更少 90%→71%（逐級），物理全部轉成閃電，不會造成感電。</li>
 *   <li><b>折光之鏡</b>（Mirror of Refraction）：身邊每隔 3.9→2 秒（逐級；冷卻回復加快）出現一面鏡子（最多 4 面、8 秒）；
 *       你的法術投射物碰到鏡子就把它打碎，從鏡子往四周複製 7 發（複製出來的還能再被別的鏡子複製）。</li>
 *   <li><b>鴉群密謀</b>（Spiraling Conspiracy）：身邊有稀有、傳奇敵人時每 2→1.05 秒（逐級）加入一隻烏鴉；你或召喚物殺敵時，
 *       每 1 點怪物強度（一般 1、魔法 2、稀有 5、傳奇 10）加入一隻。烏鴉群繞著你，每半秒打周圍的敵人（每隻一份傷害）。</li>
 * </ul>
 * 逐級的數值寫在 skills.yml 那三個技能的 level_modifiers（fulmination.less、mirror.interval、raven.interval）。
 */
final class GrantedAuras implements Listener {

    static final String FULMINATION = "fulmination";
    static final String MIRROR = "mirror_of_refraction";
    static final String RAVENS = "spiraling_conspiracy";
    /** 烏鴉群打人用的隱藏技能（每隻烏鴉一份）。 */
    static final String RAVEN_STRIKE = "proc_raven_flock";
    /** 身邊（PoE2 的 Presence）多遠。 */
    static final double PRESENCE = 8.0;

    static final int FULMINATION_TARGETS = 8;
    static final int MIRROR_MAX = 4;
    static final int MIRROR_TICKS = 160;
    static final int MIRROR_COPIES = 7;
    /** 碰到鏡子的距離。 */
    static final double MIRROR_HIT = 1.2;
    /** 烏鴉最多幾隻（原作沒寫上限，這裡設 40）。 */
    static final int RAVEN_MAX = 40;
    /** 每隻烏鴉留多久（原作沒寫，這裡設 20 秒）。 */
    static final int RAVEN_TICKS = 400;

    private record Mirror(Location at, long until) {
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final SkillDamage damage;
    private final Map<UUID, List<Mirror>> mirrors = new HashMap<>();
    private final Map<UUID, Long> nextMirror = new HashMap<>();
    /** 每隻烏鴉的到期 tick。 */
    private final Map<UUID, List<Long>> ravens = new HashMap<>();
    private final Map<UUID, Long> nextRaven = new HashMap<>();
    /** 雷霆共鳴：同一個玩家同一個技能最近一次連帶打的 tick（同一次施放只連帶一次）。 */
    private final Map<String, Long> fulminated = new HashMap<>();

    GrantedAuras(final ExileCore plugin, final SkillService service, final SkillDamage damage) {
        this.plugin = plugin;
        this.service = service;
        this.damage = damage;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個光環開著嗎；開著的話它的等級（沒開回傳 0）。 */
    private int level(final Player player, final String skillId) {
        if (!service.auras().isActive(player.getUniqueId(), skillId)) {
            return 0;
        }
        final tw.exilecore.character.SkillLoadout loadout = plugin.players().get(player).skills();
        final int position = loadout.find(skillId);
        return position < 0 ? 0 : Math.max(1, loadout.skill(position).level());
    }

    /** level_modifiers 裡某個屬性在這一級的值（沒寫回傳 fallback）。 */
    private double levelValue(final String skillId, final String stat, final int level, final double fallback) {
        for (final ScaledMod mod : service.config().levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return fallback;
    }

    // ---- 雷霆共鳴 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit()
                || event.skill().id().endsWith(FULMINATION_SUFFIX) || plugin.ailments() == null) {
            return;
        }
        final LivingEntity defender = event.defender();
        if (!plugin.ailments().has(defender, AilmentType.SHOCK)) {
            return;
        }
        final int level = level(player, FULMINATION);
        if (level <= 0 || defender.getLocation().distanceSquared(player.getLocation()) > PRESENCE * PRESENCE
                || !defender.getWorld().equals(player.getWorld())) {
            return;
        }
        final String key = player.getUniqueId() + ":" + SupportProcs.baseId(event.skill().id());
        final long now = now();
        if (now - fulminated.getOrDefault(key, Long.MIN_VALUE / 2) < 6) {
            return; // 同一次施放（多發、連鎖）只連帶一次
        }
        fulminated.put(key, now);
        if (fulminated.size() > 2048) {
            fulminated.values().removeIf(tick -> now - tick > 200);
        }
        final double less = levelValue(FULMINATION, StatKeys.FULMINATION_LESS, level, 90);
        final SkillProfile base = event.skill();
        final Map<tw.exilecore.stats.DamageType, Double> conversion = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
        conversion.put(tw.exilecore.stats.DamageType.LIGHTNING, 1.0);
        final SkillProfile extra = new SkillProfile(base.id() + FULMINATION_SUFFIX, base.tags(), base.effectiveness(), base.spellDamage(),
                base.spellDamageMax(), conversion, base.local())
                .scaled(Math.max(0, 1 - less / 100.0), "雷霆共鳴")
                .withLocal(List.of(new Modifier(StatKeys.SHOCK_CHANCE, tw.exilecore.stats.ModType.MORE, -1.0, java.util.Set.of(), "雷霆共鳴")));
        int count = 0;
        for (final LivingEntity other : service.targets().around(player.getLocation(), PRESENCE, player)) {
            if (count >= FULMINATION_TARGETS) {
                break;
            }
            if (other == defender || !plugin.ailments().has(other, AilmentType.SHOCK)) {
                continue;
            }
            damage.hit(player, other, extra, null);
            final Location a = defender.getLocation().add(0, 1, 0);
            final Location b = other.getLocation().add(0, 1, 0);
            Fx.as(player.getUniqueId(), () -> line(a, b));
            count++;
        }
    }

    static final String FULMINATION_SUFFIX = ".fulmination";

    private static void line(final Location a, final Location b) {
        final World world = a.getWorld();
        final Vector step = b.toVector().subtract(a.toVector());
        final double length = step.length();
        if (length < 1e-6 || world == null) {
            return;
        }
        step.multiply(0.5 / length);
        final Location at = a.clone();
        for (double d = 0; d < length; d += 0.5) {
            Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
    }

    // ---- 每半秒：鏡子、烏鴉 ----

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                tickMirrors(player, now);
                tickRavens(player, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的鏡子／烏鴉出錯", player.getName(), e);
            }
        }
    }

    private void tickMirrors(final Player player, final long now) {
        final UUID id = player.getUniqueId();
        final List<Mirror> mine = mirrors.get(id);
        if (mine != null) {
            mine.removeIf(mirror -> now >= mirror.until() || !mirror.at().getWorld().equals(player.getWorld()));
            for (final Mirror mirror : mine) {
                Fx.as(id, () -> Fx.spawn(mirror.at().getWorld(), Particle.END_ROD, mirror.at().clone().add(0, 1.2, 0), 4, 0.15, 0.5, 0.15, 0));
            }
        }
        final int level = level(player, MIRROR);
        if (level <= 0) {
            mirrors.remove(id);
            nextMirror.remove(id);
            return;
        }
        final long ready = nextMirror.getOrDefault(id, now);
        if (now < ready) {
            return;
        }
        final List<Mirror> list = mirrors.computeIfAbsent(id, k -> new ArrayList<>());
        if (list.size() < MIRROR_MAX) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double distance = 2.5 + plugin.random().nextDouble() * 3.5;
            final Location at = player.getLocation().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            list.add(new Mirror(at, now + MIRROR_TICKS));
            Fx.as(id, () -> Fx.spawn(at.getWorld(), Particle.FLASH, at.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0));
        }
        // 冷卻回復加快鏡子出現（PoE2：Modifiers to Cooldown Recovery Rate also apply）
        final double cooldownScale = Math.max(0.1, plugin.players().sheet(player).apply(StatKeys.SKILL_COOLDOWN, 1.0, java.util.Set.of()));
        final double seconds = levelValue(MIRROR, StatKeys.MIRROR_INTERVAL, level, 3.9) * cooldownScale;
        nextMirror.put(id, now + Math.max(10, Math.round(seconds * 20)));
    }

    /**
     * 投射物（ProjectileEngine）每 tick 問：這一發法術投射物有沒有碰到主人的鏡子；碰到就把鏡子打碎、回傳鏡子的位置（沒有回傳 null）。
     */
    Location shatter(final UUID owner, final World world, final Vector position) {
        final List<Mirror> mine = mirrors.get(owner);
        if (mine == null || mine.isEmpty()) {
            return null;
        }
        for (final Iterator<Mirror> it = mine.iterator(); it.hasNext(); ) {
            final Mirror mirror = it.next();
            final Location at = mirror.at();
            if (!at.getWorld().equals(world)) {
                continue;
            }
            final double dx = at.getX() - position.getX();
            final double dz = at.getZ() - position.getZ();
            final double dy = at.getY() + 1.0 - position.getY();
            if (dx * dx + dz * dz <= MIRROR_HIT * MIRROR_HIT && Math.abs(dy) <= 2.0) {
                it.remove();
                Fx.as(owner, () -> Fx.spawn(world, Particle.END_ROD, at.clone().add(0, 1.2, 0), 20, 0.3, 0.6, 0.3, 0.05));
                return at.clone().add(0, 1.0, 0);
            }
        }
        return null;
    }

    private void tickRavens(final Player player, final long now) {
        final UUID id = player.getUniqueId();
        final int level = level(player, RAVENS);
        if (level <= 0) {
            ravens.remove(id);
            nextRaven.remove(id);
            return;
        }
        final List<Long> flock = ravens.computeIfAbsent(id, k -> new ArrayList<>());
        flock.removeIf(until -> now >= until);
        if (now >= nextRaven.getOrDefault(id, now) && strongNearby(player)) {
            addRavens(player, 1, now);
            final double seconds = levelValue(RAVENS, StatKeys.RAVEN_INTERVAL, level, 2.0);
            nextRaven.put(id, now + Math.max(5, Math.round(seconds * 20)));
        }
        final SkillDefinition strike = service.config().skill(RAVEN_STRIKE);
        if (strike == null) {
            return;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final SkillBuild build = SkillBuild.of(service.config(), strike, level, state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        // 2026-10-26 照 PoE2 的振翅熵流（Winged Entropy）：你身邊 2 格的混沌＋物理持續傷害（每秒的量照寶石等級），
        // 每一隻烏鴉持續傷害更多 5%、範圍更多 5%；這次之前是每隻烏鴉一份傷害、3 格、沒有烏鴉就不打
        final double more = 1 + MinionAbilities.ENTROPY_PER_RAVEN * flock.size();
        final double radius = MinionAbilities.ENTROPY_RADIUS * Math.sqrt(more) * build.areaScale();
        final Location at = player.getLocation();
        final List<LivingEntity> victims = service.targets().around(at, radius, player);
        if (victims.isEmpty()) {
            return;
        }
        final double factor = poe2Damage(player, level) * more * 0.5; // 每半秒一次
        final int row = MinionAbilities.row(level);
        final tw.exilecore.stats.DamagePacket packet = new tw.exilecore.stats.DamagePacket()
                .set(tw.exilecore.stats.DamageType.CHAOS, MinionAbilities.ENTROPY_CHAOS[row] * factor)
                .set(tw.exilecore.stats.DamageType.PHYSICAL, MinionAbilities.ENTROPY_PHYSICAL[row] * factor);
        final List<Modifier> local = new ArrayList<>(build.profile().local());
        local.add(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "振翅熵流")); // 持續傷害：不會暴擊
        final SkillProfile profile = new SkillProfile(build.profile().id(), build.profile().tags(), 1.0, packet, packet, Map.of(), local);
        for (final LivingEntity target : victims) {
            damage.hit(player, target, profile, null);
        }
        final int shown = Math.min(12, flock.size());
        Fx.as(id, () -> {
            for (int i = 0; i < shown; i++) {
                final double angle = Math.PI * 2 * i / shown + now * 0.15;
                Fx.spawn(at.getWorld(), Particle.SQUID_INK, at.clone().add(Math.cos(angle) * radius * 0.7, 2.0, Math.sin(angle) * radius * 0.7),
                        1, 0, 0, 0, 0);
            }
        });
    }

    /** PoE2 的傷害換成我們的要乘多少（召喚物等級 = 寶石等級 × 2，跟召喚物同一套換算）。 */
    private double poe2Damage(final Player player, final int level) {
        if (plugin.monsters() == null) {
            return 1.0;
        }
        final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
        return MinionSheets.poe2Factors(service.config(), level, plugin.players().get(player).level(), monsters::lifeAt, monsters::damageAt)[0];
    }

    /** 亂鴉狂舞的冷卻（好了的 tick）。 */
    private final Map<UUID, Long> flightReady = new HashMap<>();

    /**
     * 2026-10-26 照 PoE2 的指令亂鴉狂舞（Mad Flight）：按鴉群密謀的鍵，用掉 10 隻烏鴉（冷卻 10 秒），
     * 準心的地方出現兩面鏡子（寬 2.2、長 7 格，順著你面對的方向）5 秒，中間的敵人每 0.12 秒被打一下
     * （烏鴉的武器傷害 550%，70% 轉混沌）。回傳給玩家看的一句話。
     */
    String madFlight(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (service.inTown(player)) {
            return "<red>營地裡不能下指令";
        }
        final UUID id = player.getUniqueId();
        final long now = now();
        final long ready = flightReady.getOrDefault(id, 0L);
        if (ready > now) {
            return String.format(java.util.Locale.ROOT, "<gray>亂鴉狂舞冷卻中（%.1f 秒）", (ready - now) / 20.0);
        }
        final List<Long> flock = ravens.get(id);
        if (flock == null || flock.size() < MinionAbilities.FLIGHT_RAVENS) {
            return "<red>烏鴉不夠（亂鴉狂舞要 " + MinionAbilities.FLIGHT_RAVENS + " 隻，現在 " + (flock == null ? 0 : flock.size()) + " 隻）";
        }
        flock.sort(null);
        flock.subList(0, MinionAbilities.FLIGHT_RAVENS).clear(); // 用掉最快要離開的 10 隻
        flightReady.put(id, now + Math.round(MinionAbilities.command(RAVENS).cooldown() * 20));
        final int level = Math.max(1, build.level());
        final Location center = preferred != null && preferred.getWorld().equals(player.getWorld()) ? preferred.getLocation()
                : service.targets().aimPoint(Origin.of(player), 24);
        final Vector facing = SkillTargets.facing(player).setY(0);
        if (facing.lengthSquared() < 1e-4) {
            facing.setX(1);
        }
        facing.normalize().multiply(MinionAbilities.FLIGHT_LENGTH / 2);
        final Location from = center.clone().subtract(facing);
        final Location to = center.clone().add(facing);
        // 烏鴉的武器傷害：PoE2 召喚物表（召喚物等級 = 寶石等級 × 2）× 0.67、上下 20%，× 550%；30% 物理、70% 混沌
        final double hit = MinionSheets.poe2AllyDamage(MinionSheets.allyLevel(level)) * MinionAbilities.RAVEN_DAMAGE * poe2Damage(player, level)
                * MinionAbilities.FLIGHT_DAMAGE;
        final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket()
                .set(tw.exilecore.stats.DamageType.PHYSICAL, hit * (1 - MinionAbilities.RAVEN_SPREAD) * (1 - MinionAbilities.FLIGHT_CHAOS))
                .set(tw.exilecore.stats.DamageType.CHAOS, hit * (1 - MinionAbilities.RAVEN_SPREAD) * MinionAbilities.FLIGHT_CHAOS);
        final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket()
                .set(tw.exilecore.stats.DamageType.PHYSICAL, hit * (1 + MinionAbilities.RAVEN_SPREAD) * (1 - MinionAbilities.FLIGHT_CHAOS))
                .set(tw.exilecore.stats.DamageType.CHAOS, hit * (1 + MinionAbilities.RAVEN_SPREAD) * MinionAbilities.FLIGHT_CHAOS);
        final SkillProfile profile = new SkillProfile("mad_flight", java.util.Set.of(tw.exilecore.stats.Tags.MINION, tw.exilecore.stats.Tags.AREA,
                tw.exilecore.stats.Tags.PHYSICAL, tw.exilecore.stats.Tags.CHAOS), 1.0, min, max, Map.of(),
                List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 5, "亂鴉狂舞"))); // 烏鴉的基礎暴擊率 5%
        final long ticks = Math.round(MinionAbilities.FLIGHT_SECONDS * 20);
        final double tick = MinionAbilities.FLIGHT_TICK * 20; // 每 2.4 tick 打一下
        new org.bukkit.scheduler.BukkitRunnable() {
            private int elapsed;
            private double next;

            @Override
            public void run() {
                plugin.perf().time("fx.aura", this::step); // 2026-10-27：計時給 /ec perf 看
            }

            private void step() {
                if (!player.isOnline() || !player.getWorld().equals(center.getWorld()) || elapsed >= ticks) {
                    cancel();
                    return;
                }
                if (elapsed % 4 == 0) {
                    Fx.as(id, () -> {
                        Fx.spawn(from.getWorld(), Particle.END_ROD, from.clone().add(0, 1.2, 0), 2, 0.1, 0.6, 0.1, 0);
                        Fx.spawn(to.getWorld(), Particle.END_ROD, to.clone().add(0, 1.2, 0), 2, 0.1, 0.6, 0.1, 0);
                        Fx.spawn(center.getWorld(), Particle.SQUID_INK, center.clone().add(0, 1.4, 0), 4, MinionAbilities.FLIGHT_LENGTH / 4, 0.4,
                                MinionAbilities.FLIGHT_WIDTH / 4, 0.02);
                    });
                }
                while (elapsed >= next) {
                    next += tick;
                    for (final LivingEntity target : service.targets().alongLine(from, to, MinionAbilities.FLIGHT_WIDTH / 2, player)) {
                        damage.hit(player, target, profile, null);
                    }
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return "<#c7a6ff>亂鴉狂舞：用掉 " + MinionAbilities.FLIGHT_RAVENS + " 隻烏鴉";
    }

    private boolean strongNearby(final Player player) {
        if (plugin.monsters() == null) {
            return false;
        }
        for (final LivingEntity target : service.targets().around(player.getLocation(), PRESENCE, player)) {
            final MonsterRarity rarity = plugin.monsters().data(target).map(data -> data.rarity()).orElse(null);
            if (rarity == MonsterRarity.RARE || rarity == MonsterRarity.UNIQUE
                    || plugin.monsters().data(target).map(data -> data.boss()).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private void addRavens(final Player player, final int count, final long now) {
        final List<Long> flock = ravens.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        for (int i = 0; i < count && flock.size() < RAVEN_MAX; i++) {
            flock.add(now + RAVEN_TICKS);
        }
    }

    /** 你或你的召喚物殺敵：每 1 點怪物強度加入一隻烏鴉。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        if (plugin.monsters() == null || plugin.monsters().data(dead).isEmpty()) {
            return;
        }
        Player owner = dead.getKiller();
        if (owner == null && dead.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity) {
            final Entity damager = byEntity.getDamager();
            if (damager instanceof LivingEntity living && service.minions() != null) {
                owner = service.minions().owner(living);
            }
        }
        if (owner == null || level(owner, RAVENS) <= 0) {
            return;
        }
        addRavens(owner, (int) Math.round(service.metas().power(dead)), now());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        mirrors.remove(id);
        nextMirror.remove(id);
        ravens.remove(id);
        nextRaven.remove(id);
        flightReady.remove(id);
    }

    /** 現在有幾隻烏鴉（說明、測試用）。 */
    int ravenCount(final UUID player) {
        final List<Long> flock = ravens.get(player);
        return flock == null ? 0 : flock.size();
    }
}
