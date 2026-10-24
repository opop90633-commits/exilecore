package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 照 PoE2 做的幾個技能的專屬機制（2026-10）：
 * <ul>
 *   <li>蓄力放開（{@link ChargeSpec}）：穿雲箭、蓄勁一擊的完美時機，炎核爆的疊層</li>
 *   <li>whirl：旋矛擊每揮一次讓旋風升一階（最多 3 階），離開旋風範圍或 3 秒沒再揮就崩散，階數越高越痛</li>
 *   <li>lance：霆落矛插在落點 6 秒，每 0.8 秒朝附近的敵人放一道連鎖閃電，時間到炸開並留下帶電地；同時最多 3 支（第 4 支讓最舊的提早炸開）。
 *       2026-10-15 照 PoE2 的風暴之槍：有怒星就消耗一顆，這支只插 1.5 秒但每 0.2 秒放電、物理全部轉閃電；
 *       你的其他技能打在矛附近（2.5 格）會把它提早引爆</li>
 *   <li>bolt_spear：擲雷槍（PoE2 的閃電長矛）有怒星就消耗一顆，矛分裂成 3 支</li>
 *   <li>ember：星火連珠每放一次在頭上叫出一顆火星（最多 6～10 顆），最後一次施放 1.3 秒後一顆接一顆射向敵人，每一顆更多 5% 傷害</li>
 * </ul>
 */
final class Signatures {

    /** 旋風最多幾階。 */
    static final int WHIRL_MAX = 3;
    /** 旋風幾 tick 沒再揮就崩散。 */
    static final long WHIRL_LIFE = 60;
    /** 旋風一階的半徑（格）與每多一階加多少。 */
    static final double WHIRL_RADIUS = 2.2;
    static final double WHIRL_RADIUS_PER_STAGE = 0.6;
    /** 崩散傷害：原技能一下的幾成，每多一階更多 150%（PoE2）。 */
    static final double WHIRL_SHARE = 0.6;
    static final double WHIRL_MORE_PER_STAGE = 1.5;

    /** 霆落矛插多久、多久放電一次、同時幾支、放電與炸開是原技能一下的幾成。 */
    static final long LANCE_LIFE = 120;
    static final long LANCE_ZAP = 16;
    static final int LANCE_MAX = 3;
    static final double LANCE_ZAP_RANGE = 6;
    static final double LANCE_ZAP_SHARE = 0.25;
    static final double LANCE_BURST_SHARE = 0.8;
    /** 2026-10-15：消耗怒星的那一支：插多久、多久放電一次；炸開留下的帶電地是原技能一下的幾成；其他技能打在幾格內會引爆。 */
    static final long LANCE_CHARGED_LIFE = 30;
    static final long LANCE_CHARGED_ZAP = 4;
    static final double LANCE_GROUND_SHARE = 0.2;
    static final double LANCE_DETONATE_RANGE = 2.5;
    /** 擲雷槍消耗怒星時分裂成幾支。 */
    static final int BOLT_SPEAR_COPIES = 3;

    /** 火星懸浮多久（最後一次施放之後）、一顆接一顆隔幾 tick、每顆更多幾 %。 */
    static final long EMBER_HOVER = 26;
    static final long EMBER_GAP = 2;
    static final double EMBER_MORE_PER = 0.05;

    private final ExileCore plugin;
    private final SkillService service;

    private static final class Whirl {
        Location center;
        int stage;
        long expires;
        SkillBuild build;
        final String skillId;

        Whirl(final Location center, final SkillBuild build, final long now) {
            this.center = center;
            this.stage = 1;
            this.expires = now + WHIRL_LIFE;
            this.build = build;
            this.skillId = build.definition().id();
        }

        double radius() {
            return (WHIRL_RADIUS + WHIRL_RADIUS_PER_STAGE * (stage - 1)) * Math.max(0.5, build.areaScale());
        }
    }

    private record Lance(Location at, SkillBuild build, long until, long[] nextZap, long zapEvery) {
    }

    private static final class Embers {
        final Deque<SkillBuild> queue = new ArrayDeque<>();
        long fireAt;
        long nextShot;
        int fired;
        UUID target;
    }

    private final Map<UUID, Whirl> whirls = new HashMap<>();
    private final Map<UUID, Deque<Lance>> lances = new HashMap<>();
    private final Map<UUID, Embers> embers = new HashMap<>();
    /** 下一支霆落矛有沒有消耗怒星（beforeCast 記、lodge 用）。 */
    private final java.util.Set<UUID> chargedLance = new java.util.HashSet<>();

    Signatures(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // ---- 蓄力放開 ----

    /** 這一招的速度倍率（法術：基礎施法時間 ÷ 現在的施法間隔；攻擊不算）。 */
    private static double speed(final SkillDefinition definition, final double intervalTicks) {
        return definition.isAttack() || definition.castTime() <= 0 || intervalTicks <= 0 ? 1.0 : definition.castTime() * 20 / intervalTicks;
    }

    /**
     * 按了一下（或按住中又收到一次）蓄力技能：還沒在蓄就開始蓄；同一招按住中就記下最後一次收到的時間。
     * 數字鍵沒辦法按住：按一下開始、再按一下放出。
     */
    void press(final Player player, final SkillRuntime runtime, final PlayerState state, final int key, final SkillDefinition definition,
               final SkillBuild build, final ChargeSpec spec, final LivingEntity preferred, final StatSheet sheet, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging != null && charging.skillId.equals(definition.id()) && charging.key == key) {
            if (!SkillLoadout.mouseKey(key) && now - charging.last > 3) {
                charging.releaseNow = true; // 數字鍵：再按一下放出
            }
            charging.last = now;
            return;
        }
        final double interval = build.intervalTicks(sheet);
        final SkillRuntime.Charging started = new SkillRuntime.Charging(definition.id(), key, now,
                spec.perfect() ? spec.perfectTick(interval) : -1, preferred == null ? null : preferred.getUniqueId());
        started.interval = interval;
        started.speed = speed(definition, interval);
        runtime.charging = started;
        state.notice("<#ffd27f>" + ItemRenderer.escape(definition.name()) + " 蓄力中…" + (SkillLoadout.mouseKey(key)
                ? "（放開出手）" : "（再按一次出手）"), now + 20);
        plugin.players().display(player, state);
        player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    /** 每 tick：蓄力中的提示、完美時機的叮一聲、放開的偵測。 */
    private void tickCharge(final Player player, final SkillRuntime runtime, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging == null) {
            return;
        }
        final ChargeSpec spec = service.config().charge(charging.skillId);
        if (spec == null || !service.eligible(player) || player.isDead()) {
            runtime.charging = null;
            return;
        }
        final long held = now - charging.start;
        final boolean left = charging.key == SkillLoadout.KEY_LEFT || charging.key == SkillLoadout.KEY_SNEAK_LEFT;
        final boolean mouse = SkillLoadout.mouseKey(charging.key);
        boolean released = charging.releaseNow || mouse && now - charging.last > (left ? 3 : 7);
        final long limit = spec.perfect() ? charging.perfectAt + ChargeSpec.OVERHOLD
                : Math.round(spec.stageTicks(charging.speed) * spec.stages()) + ChargeSpec.OVERHOLD;
        if (held > limit) {
            released = true; // 蓄太久：自動放出
        }
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition definition = service.config().skill(charging.skillId);
        final String name = definition == null ? charging.skillId : ItemRenderer.escape(definition.name());
        if (!released) {
            if (spec.perfect()) {
                if (!charging.cued && held >= charging.perfectAt) {
                    charging.cued = true;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f, 2.0f);
                    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1.2, 0), 18, 0.4, 0.5, 0.4, 0.05);
                }
                if (held % 2 == 0) {
                    state.notice(perfectBar(name, held, charging.perfectAt), now + 6);
                    plugin.players().display(player, state);
                }
            }
            if (spec.stages() > 0) {
                final int stage = spec.stagesAt(held, charging.speed);
                if (stage != charging.shownStage) {
                    charging.shownStage = stage;
                    state.notice(String.format(Locale.ROOT, "<#ff9a4d>%s 蓄力 %d／%d", name, stage, spec.stages()), now + 20);
                    plugin.players().display(player, state);
                    player.playSound(player.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 0.7f,
                            0.6f + 1.2f * stage / spec.stages());
                }
                final Location eye = player.getEyeLocation();
                player.getWorld().spawnParticle(Particle.FLAME, eye.add(eye.getDirection().multiply(1.2)), 2 + charging.shownStage, 0.15, 0.15, 0.15, 0.01);
            }
            return;
        }
        runtime.charging = null;
        final long releaseHeld = (charging.releaseNow ? now : Math.min(now, charging.last + 1)) - charging.start;
        final boolean perfect = spec.isPerfect(releaseHeld, charging.interval);
        runtime.releasing = new SkillRuntime.Release(charging.skillId, releaseHeld, perfect, charging.speed);
        final LivingEntity target = charging.target == null ? null
                : plugin.getServer().getEntity(charging.target) instanceof LivingEntity living && living.isValid() ? living : null;
        try {
            service.perform(player, runtime, charging.key, target, now);
        } finally {
            runtime.releasing = null;
        }
    }

    /** 完美時機的蓄力條：▮▮▮▯▯，到了變金色的「★ 放開！」。 */
    private static String perfectBar(final String name, final long held, final long perfectAt) {
        if (held >= perfectAt - ChargeSpec.WINDOW_BEFORE && held <= perfectAt + ChargeSpec.WINDOW_AFTER) {
            return "<#ffd700><bold>★ 放開！</bold> <white>" + name;
        }
        if (held > perfectAt + ChargeSpec.WINDOW_AFTER) {
            return "<gray>" + name + "：錯過完美時機（放開照一般的出手）";
        }
        final int total = 10;
        final int filled = (int) Math.min(total, Math.round(total * held / (double) Math.max(1, perfectAt)));
        return "<#ffd27f>" + name + " <white>" + "▮".repeat(filled) + "<dark_gray>" + "▯".repeat(total - filled);
    }

    /** 放開的那一下：完美時機（更多傷害、代號接 .perfect，打中時觸發 perfect_hit）、疊層（更多傷害、更大範圍）。 */
    SkillBuild applyRelease(final Player player, final PlayerState state, final SkillBuild build, final ChargeSpec spec,
                            final SkillRuntime.Release release, final long now) {
        SkillBuild result = build;
        final String name = ItemRenderer.escape(build.definition().name());
        if (spec.perfect()) {
            if (release.perfect()) {
                result = result.withProfile(result.profile().scaled(1 + spec.perfectMore() / 100.0, "完美時機")
                        .derive(result.profile().id() + SupportProcs.PERFECT_SUFFIX, Set.of(), Set.of()));
                state.notice("<#ffd700><bold>★ 完美時機！</bold> <white>" + name, now + 30);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.0f, 1.4f);
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
            } else {
                state.notice("<gray>" + name + "：沒抓到完美時機", now + 30);
            }
        }
        if (spec.stages() > 0) {
            final int stages = spec.stagesAt(release.held(), release.speed());
            final double more = spec.stageMore() * (stages - 1) / 100.0;
            if (more > 0) {
                result = result.withProfile(result.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, more, "蓄力"))));
            }
            final double area = spec.stageArea() * (stages - 1) / 100.0;
            if (area > 0) {
                result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(1 + area), result.projectiles());
            }
            state.notice(String.format(Locale.ROOT, "<#ff9a4d>%s 放出：%d／%d 層", name, stages, spec.stages()), now + 30);
        }
        plugin.players().display(player, state);
        return result;
    }

    // ---- 放之前：消耗怒星（2026-10-15，照 PoE2 的風暴之槍、閃電長矛） ----

    /** 放之前（SkillService 呼叫）：霆落矛、擲雷槍有怒星就消耗一顆，回傳這一次要用的技能。 */
    SkillBuild beforeCast(final Player player, final SkillDefinition definition, final SkillBuild build, final PlayerState state, final long now) {
        final String signature = service.config().signature(definition.id());
        if (!"lance".equals(signature) && !"bolt_spear".equals(signature)) {
            return build;
        }
        final int benefit = plugin.combat() == null ? 0
                : plugin.combat().charges().spend(player, "frenzy", build.profile().effective(state.sheet()), build.profile().tags()); // 2026-10-24：戰星輔助
        if (benefit <= 0) {
            return build;
        }
        if ("lance".equals(signature)) {
            chargedLance.add(player.getUniqueId());
            state.notice("<#ffe14a>" + tw.exilecore.item.ItemRenderer.escape(definition.name()) + " 消耗怒星：插 1.5 秒、每 0.2 秒放電", now + 30);
            plugin.players().display(player, state);
            return build.withProfile(build.profile().withLocal(List.of(
                    tw.exilecore.stats.Modifier.flat("damage.convert.lightning", 30, "怒星"))));
        }
        final int copies = BOLT_SPEAR_COPIES * benefit; // 倍星：分裂的支數加倍
        state.notice("<#ffe14a>" + tw.exilecore.item.ItemRenderer.escape(definition.name()) + " 消耗怒星：分裂成 " + copies + " 支", now + 30);
        plugin.players().display(player, state);
        return build.withAreaAndProjectiles(build.areaScale(), build.projectiles() + copies - 1);
    }

    /** 你的其他技能打在插著的霆落矛附近：把它提早引爆（2026-10-15，照 PoE2「被其他技能引爆」）。 */
    void detonateNear(final Player player, final SkillDefinition definition, final Location at) {
        if (at == null || "lance".equals(service.config().signature(definition.id()))) {
            return;
        }
        final Deque<Lance> mine = lances.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        final Iterator<Lance> it = mine.iterator();
        while (it.hasNext()) {
            final Lance lance = it.next();
            if (lance.at().getWorld() == at.getWorld() && lance.at().distance(at) <= LANCE_DETONATE_RANGE) {
                it.remove();
                detonate(player, lance);
            }
        }
    }

    // ---- 放完之後（旋矛擊的旋風、霆落矛插地）／取代施放（星火連珠） ----

    /** 這一招放完了（SkillService 呼叫）：旋矛擊升旋風、霆落矛插在落點。 */
    void afterCast(final Player player, final SkillDefinition definition, final SkillBuild build, final Location at, final long now) {
        final String signature = service.config().signature(definition.id());
        if ("whirl".equals(signature)) {
            whirl(player, build, now);
        } else if ("lance".equals(signature) && at != null) {
            lodge(player, build, at, now);
        } else if ("lance".equals(signature)) {
            chargedLance.remove(player.getUniqueId()); // 沒插下去：這次的怒星不留到下一支
        }
    }

    private void whirl(final Player player, final SkillBuild build, final long now) {
        final UUID uuid = player.getUniqueId();
        final Whirl old = whirls.get(uuid);
        if (old != null && old.center.getWorld() == player.getWorld() && old.center.distance(player.getLocation()) <= old.radius() + 0.5) {
            old.stage = Math.min(WHIRL_MAX, old.stage + 1);
            old.expires = now + WHIRL_LIFE;
            old.build = build;
            if (old.stage > 1) {
                player.playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, 0.4f, 0.8f + 0.3f * old.stage);
                final PlayerState state = plugin.players().get(player);
                state.notice(String.format(Locale.ROOT, "<#c7f0ff>旋風 %d／%d 階", old.stage, WHIRL_MAX), now + 20);
                plugin.players().display(player, state);
            }
            return;
        }
        if (old != null) {
            collapse(player, old);
        }
        whirls.put(uuid, new Whirl(player.getLocation(), build, now));
    }

    /** 旋風崩散：在中心炸一圈（階數越高越痛、越大），把敵人往外推。 */
    private void collapse(final Player player, final Whirl whirl) {
        final double share = WHIRL_SHARE * (1 + WHIRL_MORE_PER_STAGE * (whirl.stage - 1));
        final SkillBuild sub = subBuild(player, "whirling_slash_collapse", whirl.build, share);
        if (sub == null) {
            return;
        }
        final double radius = whirl.radius();
        final SkillDefinition def = sub.definition();
        final double base = def.params() == null || def.params().radius() == null ? WHIRL_RADIUS : def.params().radius().first();
        final SkillBuild sized = sub.withAreaAndProjectiles(radius / Math.max(0.5, base), sub.projectiles());
        service.procCast(Origin.at(player, whirl.center.clone().add(0, 0.2, 0), SkillTargets.facing(player)), sized, null);
        for (final LivingEntity target : service.targets().around(whirl.center, radius, player)) {
            final Vector push = target.getLocation().toVector().subtract(whirl.center.toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) {
                target.setVelocity(target.getVelocity().add(push.normalize().multiply(0.5 + 0.15 * whirl.stage).setY(0.25)));
            }
        }
        whirl.center.getWorld().spawnParticle(Particle.SWEEP_ATTACK, whirl.center.clone().add(0, 1, 0), 6 * whirl.stage, radius / 2, 0.4,
                radius / 2, 0);
        whirl.center.getWorld().playSound(whirl.center, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.8f, 1.0f);
    }

    private void lodge(final Player player, final SkillBuild build, final Location at, final long now) {
        final Deque<Lance> mine = lances.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>());
        while (mine.size() >= LANCE_MAX) {
            detonate(player, mine.pollFirst()); // 第 4 支：最舊的提早炸開
        }
        final boolean charged = chargedLance.remove(player.getUniqueId());
        final long zap = charged ? LANCE_CHARGED_ZAP : LANCE_ZAP;
        mine.addLast(new Lance(at.clone(), build, now + (charged ? LANCE_CHARGED_LIFE : LANCE_LIFE),
                new long[] {now + (charged ? 4 : 10) + zap}, zap));
    }

    private void detonate(final Player player, final Lance lance) {
        if (lance == null) {
            return;
        }
        final SkillBuild sub = subBuild(player, "storm_lance_burst", lance.build(), LANCE_BURST_SHARE);
        if (sub != null) {
            service.procCast(Origin.at(player, lance.at().clone().add(0, 0.2, 0), SkillTargets.facing(player)), sub, null);
        }
        // 2026-10-15（PoE2）：炸開的地方留下帶電地
        final SkillBuild ground = subBuild(player, "storm_lance_ground", lance.build(), LANCE_GROUND_SHARE);
        if (ground != null) {
            service.procCast(Origin.at(player, lance.at().clone().add(0, 0.1, 0), SkillTargets.facing(player)), ground, null);
        }
        lance.at().getWorld().playSound(lance.at(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    /** 星火連珠：叫出一顆火星懸在頭上（取代原本直接射出）；所有火星的懸浮時間重算。 */
    void conjureEmber(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final Embers mine = embers.computeIfAbsent(player.getUniqueId(), uuid -> new Embers());
        if (mine.fired > 0) {
            mine.fired = 0; // 上一輪還在射：新的一輪重新算
        }
        final int max = (int) Math.round(6 + 4 * (Math.min(SkillLoadout.MAX_LEVEL, build.level()) - 1) / (double) (SkillLoadout.MAX_LEVEL - 1));
        if (mine.queue.size() < max) {
            mine.queue.addLast(build);
        }
        mine.fireAt = now + EMBER_HOVER;
        mine.nextShot = mine.fireAt;
        if (preferred != null) {
            mine.target = preferred.getUniqueId();
        }
        final PlayerState state = plugin.players().get(player);
        state.notice(String.format(Locale.ROOT, "<#ff9a4d>火星 %d／%d", mine.queue.size(), max), now + 20);
        plugin.players().display(player, state);
    }

    // ---- 每 tick ----

    void tick(final Player player, final SkillRuntime runtime, final long now) {
        tickCharge(player, runtime, now);
        final UUID uuid = player.getUniqueId();
        final Whirl whirl = whirls.get(uuid);
        if (whirl != null) {
            tickWhirl(player, whirl, now);
        }
        final Deque<Lance> mine = lances.get(uuid);
        if (mine != null) {
            tickLances(player, mine, now);
            if (mine.isEmpty()) {
                lances.remove(uuid);
            }
        }
        final Embers ember = embers.get(uuid);
        if (ember != null) {
            tickEmbers(player, ember, now);
            if (ember.queue.isEmpty()) {
                embers.remove(uuid);
            }
        }
    }

    private void tickWhirl(final Player player, final Whirl whirl, final long now) {
        final boolean away = whirl.center.getWorld() != player.getWorld()
                || whirl.center.distance(player.getLocation()) > whirl.radius() + 0.5;
        if (away || now >= whirl.expires || !service.eligible(player)) {
            whirls.remove(player.getUniqueId());
            if (whirl.center.getWorld() == player.getWorld() && service.eligible(player)) {
                collapse(player, whirl);
            }
            return;
        }
        final double radius = whirl.radius();
        if (now % 2 == 0) {
            final double spin = now * 0.6;
            for (int i = 0; i < 3 + whirl.stage; i++) {
                final double angle = spin + i * Math.PI * 2 / (3 + whirl.stage);
                final Location point = whirl.center.clone().add(Math.cos(angle) * radius, 0.3 + 0.4 * (i % 3), Math.sin(angle) * radius);
                Fx.spawn(player.getWorld(), Particle.CLOUD, point, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
        if (now % 10 == 0) {
            // PoE2：旋風讓裡面的敵人移動變慢 30%
            for (final LivingEntity target : service.targets().around(whirl.center, radius, player)) {
                if (!(target instanceof Player)) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 12, 0, true, false, false));
                }
            }
        }
    }

    private void tickLances(final Player player, final Deque<Lance> mine, final long now) {
        final Iterator<Lance> it = mine.iterator();
        while (it.hasNext()) {
            final Lance lance = it.next();
            if (lance.at().getWorld() != player.getWorld() || !service.eligible(player)) {
                it.remove();
                continue;
            }
            if (now >= lance.until()) {
                it.remove();
                detonate(player, lance);
                continue;
            }
            if (now % 4 == 0) {
                Fx.spawn(player.getWorld(), Particle.ELECTRIC_SPARK, lance.at().clone().add(0, 1.0 + (now % 8) / 8.0, 0), 3, 0.1, 0.5, 0.1, 0.02);
            }
            if (now >= lance.nextZap()[0]) {
                lance.nextZap()[0] = now + lance.zapEvery();
                final List<LivingEntity> near = service.targets().around(lance.at(), LANCE_ZAP_RANGE, player);
                if (!near.isEmpty()) {
                    final SkillBuild sub = subBuild(player, "storm_lance_bolt", lance.build(), LANCE_ZAP_SHARE);
                    if (sub != null) {
                        final Location from = lance.at().clone().add(0, 1.5, 0);
                        final LivingEntity target = near.get(0);
                        final Vector direction = SkillTargets.center(target).toVector().subtract(from.toVector());
                        service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player)
                                : direction.normalize()), sub, target);
                    }
                }
            }
        }
    }

    private void tickEmbers(final Player player, final Embers ember, final long now) {
        if (!service.eligible(player)) {
            ember.queue.clear();
            return;
        }
        final Location head = player.getLocation().add(0, 2.6, 0);
        if (now % 2 == 0) {
            final int count = ember.queue.size();
            for (int i = 0; i < count; i++) {
                final double angle = now * 0.15 + i * Math.PI * 2 / Math.max(1, count);
                Fx.spawn(player.getWorld(), Particle.FLAME, head.clone().add(Math.cos(angle) * 0.7, 0, Math.sin(angle) * 0.7), 1, 0, 0, 0, 0);
            }
        }
        if (now < ember.fireAt || now < ember.nextShot || ember.queue.isEmpty()) {
            return;
        }
        ember.nextShot = now + EMBER_GAP;
        final SkillBuild build = ember.queue.pollFirst();
        final int index = ember.fired++;
        // 目標：上一次指著的敵人優先，不然找附近的（每一顆輪著挑不同的）
        LivingEntity target = null;
        if (ember.target != null && plugin.getServer().getEntity(ember.target) instanceof LivingEntity living && living.isValid()
                && !living.isDead() && living.getWorld() == player.getWorld() && living.getLocation().distanceSquared(player.getLocation()) < 30 * 30) {
            target = living;
        }
        final List<LivingEntity> near = service.targets().around(player.getLocation(), 24, player);
        if (!near.isEmpty() && (target == null || index > 0)) {
            target = near.get(index % near.size());
        }
        final Location from = head.clone().add(SkillTargets.facing(player).multiply(0.5));
        final Vector direction = target != null ? SkillTargets.center(target).toVector().subtract(from.toVector())
                : player.getEyeLocation().getDirection();
        final SkillBuild shot = index > 0 ? build.withProfile(build.profile().scaled(1 + EMBER_MORE_PER * index, "火星連射")) : build;
        service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize()),
                shot, target);
    }

    /** 隱藏技能的數值：用原技能那一下的傷害（含輔助、武器）乘 share，範圍、形狀照隱藏技能。 */
    private SkillBuild subBuild(final Player player, final String subId, final SkillBuild parent, final double share) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillBuild base = SkillBuild.of(service.config(), sub, parent.level(), state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        return base.withProfile(parent.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), Set.of()))
                .withAreaAndProjectiles(base.areaScale() * Math.max(0.5, parent.areaScale()), base.projectiles());
    }

    /** 登出、換角色：清掉這個人的旋風、插著的矛、火星。 */
    void forget(final UUID uuid) {
        whirls.remove(uuid);
        lances.remove(uuid);
        embers.remove(uuid);
        chargedLance.remove(uuid);
    }

    /** 測試用：現在的旋風階數（沒有 0）。 */
    int whirlStage(final UUID uuid) {
        final Whirl whirl = whirls.get(uuid);
        return whirl == null ? 0 : whirl.stage;
    }
}
