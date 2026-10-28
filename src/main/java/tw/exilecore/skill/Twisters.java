package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-10-28 照 PoE2 0.5 Twister（旋風矛；poe2db／RePoE TwisterPlayer）：
 * <ul>
 *   <li>甩出旋風（投射物數量幾個就幾個，互相至少隔 15°；品質 x% 機率多一個），每秒 7.5 格、亂竄著往前，持續 3 秒（吃持續時間）；</li>
 *   <li>旋風半徑 0.5 格（吃範圍），穿過所有敵人，一路「重複」打中旋風裡的敵人並致盲——同一次放出去的旋風打同一個敵人至少隔 0.66 秒；</li>
 *   <li>碰到你其他技能的旋風（旋矛擊、颶風槍）就用掉它：多出一個旋風，而且每一階旋風更多 80% 傷害（多出來的那個也是）；</li>
 *   <li>飛過元素地面（點燃、冰緩、感電地面）或用掉元素旋風，就變成那種元素的旋風：獲得 50% 傷害為那種元素的額外傷害（剩下的時間都是）。</li>
 * </ul>
 * 這次之前是一般的投射物（每秒 4 格、12 格射程、穿透 20 次，每個敵人只打一次），沒有用掉旋風、沒有元素旋風、品質是增加傷害。
 */
final class Twisters {

    /** skills.yml 的 signature。 */
    static final String SIGNATURE = "twister";

    /** 持續時間（秒，base_skill_effect_duration 3000）。 */
    static final double DURATION = 3.0;
    /** 同一次放出去的旋風打同一個敵人的間隔（tick；twister_hit_interval_ms 660）。 */
    static final long HIT_INTERVAL = Math.round(0.66 * 20);
    /** 用掉的旋風每一階更多幾成（twister_damage_+%_final_per_whirling_slash_stage 80）。 */
    static final double MORE_PER_STAGE = 0.8;
    /** 元素旋風獲得幾 % 傷害為那種元素的額外傷害（twister_gain_%_elemental_damage_of_corresponding_type 50）。 */
    static final double ELEMENT_GAIN = 50;
    /** 好幾個旋風時互相至少隔幾度（minimum_per_projectile_spread_angle_degrees 15）。 */
    static final double SPREAD = 15;
    /** 旋風的半徑（格，active_skill_base_area_of_effect_radius 5）。 */
    static final double RADIUS = 0.5;
    /** FLAT（%）：多一個旋風的機率（品質：每 1% 品質 1%）。 */
    static final String EXTRA_CHANCE = "twister.extra_chance";
    /** 每個玩家同時最多幾個（太多就丟最舊的）。 */
    static final int MAX_PER_PLAYER = 40;

    /** 同一次放出去的旋風共用：每個敵人下次可以再被打的 tick。 */
    static final class Volley {
        final Map<UUID, Long> nextHit = new HashMap<>();

        /** 這個敵人現在能不能被打；能的話記下下一次的時間。 */
        boolean tryHit(final UUID target, final long now) {
            final Long next = nextHit.get(target);
            if (next != null && now < next) {
                return false;
            }
            nextHit.put(target, now + HIT_INTERVAL);
            return true;
        }
    }

    private static final class Twister {
        final SkillBuild build;
        final Volley volley;
        final boolean primary;
        Location at;
        Vector direction;
        Vector heading;
        int ticksLeft;
        /** 用掉的旋風加起來幾階。 */
        int stages;
        DamageType element;
        /** 2026-10-28 照 PoE2（貝雷克的戒指、元素之章）：視為受到哪幾種元素地面強化。 */
        WindGround wind = WindGround.NONE;
        SkillProfile profile;

        Twister(final SkillBuild build, final Volley volley, final boolean primary, final Location at, final Vector direction, final int ticks) {
            this.build = build;
            this.volley = volley;
            this.primary = primary;
            this.at = at;
            this.direction = direction.clone();
            this.heading = direction.clone();
            this.ticksLeft = ticks;
            this.profile = build.profile();
        }
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Signatures signatures;
    private final Map<UUID, List<Twister>> twisters = new HashMap<>();

    Twisters(final ExileCore plugin, final SkillService service, final Signatures signatures) {
        this.plugin = plugin;
        this.service = service;
        this.signatures = signatures;
    }

    // ---- 純邏輯 ----

    /** 這次放幾個旋風：投射物數量＋品質的機率（每 100% 一定多一個，剩下的擲一次）。 */
    static int count(final int projectiles, final double extraChance, final RandomGenerator random) {
        return Math.max(1, projectiles) + ProjectileEngine.extraFromChance(extraChance, random);
    }

    /** 第 index 個旋風（共 count 個）偏離正前方幾度：以正前方為中心，互相隔 15°。 */
    static double offset(final int index, final int count) {
        return (index - (count - 1) / 2.0) * SPREAD;
    }

    /** 用掉的旋風加起來 stages 階：更多幾倍（每階更多 80%）。 */
    static double more(final int stages) {
        return 1 + MORE_PER_STAGE * Math.max(0, stages);
    }

    // ---- 放出去 ----

    /** ProjectileEngine.launch：旋風矛（signature: twister）放出旋風，取代一般的投射物。 */
    void launch(final Origin origin, final SkillBuild build) {
        final Player owner = origin.owner();
        if (owner == null) {
            return;
        }
        final int count = count(build.projectiles(), SkillZones.localFlat(build.profile(), EXTRA_CHANCE), plugin.random());
        final Vector forward = origin.facing();
        final Volley volley = new Volley();
        final int ticks = build.durationTicks(DURATION);
        final Location feet = origin.feet().clone();
        final WindGround wind = WindGround.of(plugin.players().sheet(owner)); // 2026-10-28 照 PoE2（貝雷克的戒指、元素之章）
        for (int i = 0; i < count; i++) {
            final Vector direction = forward.clone().rotateAroundY(Math.toRadians(offset(i, count)));
            final Twister twister = new Twister(build, volley, true, feet.clone().add(direction.clone().multiply(0.6)), direction, ticks);
            if (wind != WindGround.NONE) {
                twister.wind = wind;
                twister.element = wind.primary();
                refresh(twister);
            }
            add(owner.getUniqueId(), twister);
        }
        feet.getWorld().playSound(feet, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.7f, 1.2f);
    }

    private void add(final UUID owner, final Twister twister) {
        final List<Twister> mine = twisters.computeIfAbsent(owner, uuid -> new ArrayList<>());
        mine.add(twister);
        while (mine.size() > MAX_PER_PLAYER) {
            mine.removeFirst();
        }
    }

    // ---- 每 tick ----

    void tick(final Player owner, final long now) {
        final List<Twister> mine = twisters.get(owner.getUniqueId());
        if (mine == null) {
            return;
        }
        final List<Twister> born = new ArrayList<>();
        for (final Iterator<Twister> it = mine.iterator(); it.hasNext(); ) {
            final Twister twister = it.next();
            if (twister.at.getWorld() != owner.getWorld() || !service.eligible(owner)) {
                it.remove();
                continue;
            }
            if (--twister.ticksLeft < 0) {
                it.remove();
                end(owner, twister);
                continue;
            }
            step(twister);
            final double radius = (twister.build.size() > 0 ? twister.build.size() : RADIUS) * Math.max(0.5, twister.build.areaScale());
            interact(owner, twister, radius, born, now);
            for (final LivingEntity target : service.targets().around(twister.at, radius, owner)) {
                if (twister.volley.tryHit(target.getUniqueId(), now)) {
                    service.executor().hitOne(owner, twister.profile, target, twister.at, 0);
                }
            }
            draw(twister, radius, now);
        }
        mine.addAll(born);
        if (mine.isEmpty()) {
            twisters.remove(owner.getUniqueId());
        }
    }

    /** 亂竄著往前走一步（沿著地面：一格高的坎爬上去、往下最多掉兩格；撞牆彈開）。 */
    private void step(final Twister twister) {
        final RandomGenerator random = plugin.random();
        final double wobble = Math.max(0, twister.build.wobble());
        twister.direction.rotateAroundY(Math.toRadians((random.nextDouble() * 2 - 1) * wobble));
        twister.direction.multiply(0.8).add(twister.heading.clone().multiply(0.2)).setY(0); // 大致保持原本的方向
        if (twister.direction.lengthSquared() < 1e-6) {
            twister.direction = twister.heading.clone();
        }
        twister.direction.normalize();
        final Location next = twister.at.clone().add(twister.direction.clone().multiply(Math.max(0.05, twister.build.speed())));
        Block feet = next.getBlock();
        if (!feet.isPassable()) {
            final Block up = feet.getRelative(BlockFace.UP);
            if (up.isPassable() && up.getRelative(BlockFace.UP).isPassable()) {
                next.setY(up.getY());
            } else {
                // 撞牆：往回彈、偏一點
                twister.direction.multiply(-1).rotateAroundY(Math.toRadians((random.nextDouble() * 2 - 1) * 60));
                twister.heading = twister.direction.clone();
                return;
            }
        } else {
            for (int i = 0; i < 2 && next.getBlock().getRelative(BlockFace.DOWN).isPassable(); i++) {
                next.setY(next.getBlockY() - 1);
            }
        }
        twister.at = next;
    }

    /** 用掉碰到的旋風、碰到元素地面變成元素旋風。 */
    private void interact(final Player owner, final Twister twister, final double radius, final List<Twister> born, final long now) {
        int stage = 0;
        DamageType element = null;
        final Signatures.ConsumedWhirl slash = signatures.consumeWhirl(owner.getUniqueId(), twister.at, radius);
        if (slash != null) {
            stage = slash.stage();
            element = slash.element();
        } else if (service.leftovers() != null) {
            stage = service.leftovers().consumeWhirl(owner.getUniqueId(), twister.at, radius);
        }
        boolean changed = false;
        if (stage > 0) {
            twister.stages += stage;
            changed = true;
        }
        if (twister.element == null && element != null) {
            twister.element = element;
            changed = true;
        }
        if (twister.element == null && now % 4 == 0) {
            twister.element = ElementalGround.at(plugin, twister.at, radius);
            changed |= twister.element != null;
        }
        if (changed) {
            refresh(twister);
        }
        if (stage > 0) {
            // 用掉一個旋風：多出一個旋風（同樣的階數加成與元素，持續時間重新算）
            final Vector direction = twister.direction.clone().rotateAroundY(Math.toRadians((plugin.random().nextDouble() * 2 - 1) * 45));
            final Twister extra = new Twister(twister.build, twister.volley, false, twister.at.clone(), direction,
                    twister.build.durationTicks(DURATION));
            extra.stages = twister.stages;
            extra.element = twister.element;
            extra.wind = twister.wind;
            refresh(extra);
            born.add(extra);
            twister.at.getWorld().playSound(twister.at, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.6f, 1.3f);
        }
    }

    private static void refresh(final Twister twister) {
        SkillProfile profile = twister.build.profile().scaled(more(twister.stages), "旋風階數");
        // 2026-10-28 照 PoE2（元素之章）：可以同時被好幾種元素地面強化時每一種各獲得；這次之前只有踩到的那一種
        final List<Modifier> gains = twister.wind.gains(twister.element, ELEMENT_GAIN, "元素旋風");
        if (!gains.isEmpty()) {
            profile = profile.withLocal(gains);
        }
        twister.profile = profile;
    }

    /** 旋風消失：亞馬遜的元素湧動（用掉湧動的投射物飛行結束時炸一發）照投射物算。 */
    private void end(final Player owner, final Twister twister) {
        if (twister.primary && SkillZones.localFlat(twister.build.profile(), SkillResources.SURGE_BLAST) > 0 && plugin.skills() != null) {
            plugin.skills().resources().surgeBlast(owner, twister.at);
        }
    }

    private static void draw(final Twister twister, final double radius, final long now) {
        if (now % 2 != 0) {
            return;
        }
        final World world = twister.at.getWorld();
        final Particle particle = twister.element == DamageType.FIRE ? Particle.FLAME
                : twister.element == DamageType.COLD ? Particle.SNOWFLAKE
                : twister.element == DamageType.LIGHTNING ? Particle.ELECTRIC_SPARK : Particle.CLOUD;
        final double spin = now * 0.9;
        for (int i = 0; i < 4; i++) {
            final double angle = spin + i * Math.PI / 2;
            final double r = radius * (0.5 + 0.25 * i);
            Fx.spawn(world, particle, twister.at.clone().add(Math.cos(angle) * r, 0.2 + 0.45 * i, Math.sin(angle) * r), 1, 0.02, 0.02, 0.02);
        }
    }

    /** 登出、換角色：清掉這個人的旋風。 */
    void forget(final UUID owner) {
        twisters.remove(owner);
    }

    /** 測試用：這個人現在有幾個旋風。 */
    int count(final UUID owner) {
        final List<Twister> mine = twisters.get(owner);
        return mine == null ? 0 : mine.size();
    }
}
