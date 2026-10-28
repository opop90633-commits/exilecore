package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-28 照 PoE2：打中之後才會發生的分段（skills.yml 的 stages）。
 * <ul>
 *   <li>三連刺（Rapid Assault）：最後一刺在目標身上留下矛頭（stages.last 的 count 個、duration 秒）；你的引爆類技能（detonator 標籤）
 *       用在 3 格內時每個矛頭在牠身上炸開（stages.explosion 的傷害表、半徑）。</li>
 *   <li>霜牙刺（Fangs of Frost）：打中被招架的敵人用掉「被招架」，在牠身上炸開冰霜（stages.burst）。</li>
 *   <li>震脈掌（Staggering Palm）：打到重暈的敵人給你增益（stages.projectile 的 duration 秒，每重暈一隻再加，最多 30 秒），
 *       增益期間你用長棍的其他攻擊時再射出一發投射物（stages.projectile 的傷害表；射出的樣子照 reeling_palm_bolt）。</li>
 * </ul>
 * 每一段的傷害都照觸發它的那個技能這一級的表換算（SkillStages.profile），輔助、加成照吃；造成的傷害換成另一個代號（不會再觸發自己）。
 */
public final class StageEffects implements Listener {

    /** 引爆類技能用在多近的地方會引爆矛頭（跟炎爆矛一樣 3 格）。 */
    static final double DETONATE_RANGE = tw.exilecore.core.Units.metres(3.0); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 震脈掌的投射物：射出的樣子（隱藏技能）。 */
    static final String PALM_BOLT = "reeling_palm_bolt";
    /** 震脈掌的增益最長幾秒（PoE2 staggering_palm_buff_max_duration_ms 30000）。 */
    static final double PALM_MAX_SECONDS = 30;

    private final ExileCore plugin;
    private final SkillService service;

    /** 玩家 → 最後一刺用的技能（還沒套最後一刺那一段的）：打中時留下矛頭。 */
    private final Map<UUID, SkillBuild> lastStab = new HashMap<>();
    /** 玩家 → 插在哪些敵人身上的矛頭（到期的 tick）。 */
    private final Map<UUID, Map<UUID, Deque<Long>>> spearheads = new HashMap<>();
    /** 玩家 → 插矛頭的那個技能（引爆時照它的等級）。 */
    private final Map<UUID, SkillBuild> spearheadBuild = new HashMap<>();
    /** 玩家 → 震脈掌增益到哪個 tick。 */
    private final Map<UUID, Long> palmUntil = new HashMap<>();

    StageEffects(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 連刺的最後一下要出手了（SkillService.repeatStrikeInner）：記下來，打中時插矛頭。 */
    void armLast(final Player player, final SkillBuild unstaged) {
        if (SkillStages.get(unstaged.definition().id(), SkillStages.LAST) != null
                && SkillStages.get(unstaged.definition().id(), "explosion") != null) {
            lastStab.put(player.getUniqueId(), unstaged);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit()) {
            return;
        }
        final SkillProfile skill = event.skill();
        final LivingEntity target = event.defender();
        final long now = now();
        // 三連刺：最後一刺插矛頭
        final SkillBuild stab = lastStab.get(player.getUniqueId());
        if (stab != null && stab.definition().id().equals(skill.id()) && SkillStages.mark(skill, SkillStages.IS + SkillStages.LAST) > 0) {
            final SkillStages.Stage last = SkillStages.get(skill.id(), SkillStages.LAST);
            final int limit = Math.max(1, last.count().intAt(stab.level()));
            final Deque<Long> heads = spearheads.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>())
                    .computeIfAbsent(target.getUniqueId(), uuid -> new ArrayDeque<>());
            while (heads.size() >= limit) {
                heads.pollFirst();
            }
            final long headUntil = now + Math.round((last.duration() > 0 ? last.duration() : 30) * 20 * Math.max(0.1, stab.durationScale()));
            heads.addLast(headUntil);
            // 2026-10-28 照 PoE2 Rapid Assault 的品質：(0—20)% 機率多留一個矛頭
            final double extraTip = stab.qualityFlat(tw.exilecore.stats.GemQualityKeys.EXTRA_SPEAR_TIP);
            if (extraTip > 0 && plugin.random().nextDouble() * 100 < extraTip) {
                heads.addLast(headUntil);
            }
            spearheadBuild.put(player.getUniqueId(), stab);
            service.leftovers().spearheadMaim(player, target, headUntil); // 2026-10-28 照 PoE2 Rapid Assault：插著矛頭的敵人被癱瘓
        }
        // 霜牙刺：打中被招架的敵人，用掉招架、炸開冰霜
        final SkillStages.Stage burst = SkillStages.get(skill.id(), "burst");
        if (burst != null && plugin.combat() != null && parried(target, player, now)) {
            final SkillBuild build = loadout(player, skill.id());
            // 2026-10-28 照 PoE2 Fangs of Frost 的品質：(0—15)% 機率不用掉被招架的減益
            final double keep = build == null ? 0 : build.qualityFlat(tw.exilecore.stats.GemQualityKeys.KEEP_PARRIED);
            if (!(keep > 0 && plugin.random().nextDouble() * 100 < keep)) {
                plugin.combat().debuffs().remove(target.getUniqueId(), ChargeEffects.PARRIED, player.getUniqueId(), now);
            }
            if (build != null) {
                final double radius = SkillStages.radius(build, "burst", 2.2 * build.areaScale());
                final SkillProfile blast = SkillStages.profile(build, build.profile(), "burst")
                        .derive(skill.id() + ".burst", Set.of(Tags.AREA), Set.of(Tags.STRIKE));
                hitAround(player, target.getLocation(), radius, blast, Particle.SNOWFLAKE);
                target.getWorld().playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.8f, 1.2f);
                service.leftovers().fangsGround(player, target.getLocation()); // 2026-10-28 照 PoE2 Fangs of Frost：留下冰緩地面
            }
        }
        // 震脈掌：重暈敵人給增益（每重暈一隻加一次持續時間，最多 30 秒）
        final SkillStages.Stage palm = SkillStages.get(skill.id(), "projectile");
        if (palm != null && plugin.ailments() != null && plugin.ailments().heavyStunned(target)) {
            final long add = Math.round((palm.duration() > 0 ? palm.duration() : 7.5) * 20);
            final long cap = now + Math.round(Math.max(palm.duration(), PALM_MAX_SECONDS) * 20);
            final long until = Math.max(now, palmUntil.getOrDefault(player.getUniqueId(), now)) + add;
            palmUntil.put(player.getUniqueId(), Math.min(cap, until));
            player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0.05);
        }
    }

    /** 用了一個技能之後（SkillService）：引爆類技能引爆附近的矛頭；震脈掌增益中的長棍攻擊多射一發投射物。 */
    int afterUse(final Player player, final SkillDefinition definition, final Location at) {
        final long now = now();
        int detonated = 0;
        if (at != null && definition.tags().contains(tw.exilecore.stats.TreeMechanicKeys.DETONATOR)) {
            detonated = detonate(player, definition, at, now);
        }
        final Long until = palmUntil.get(player.getUniqueId());
        if (until != null && until < now) {
            palmUntil.remove(player.getUniqueId());
        } else if (until != null && definition.isAttack() && !definition.hidden() && definition.weapons().contains("quarterstaff")
                && SkillStages.get(definition.id(), "projectile") == null) {
            firePalm(player);
        }
        return detonated; // 2026-10-28：引爆了幾個矛頭（雷躍的品質）
    }

    private int detonate(final Player player, final SkillDefinition definition, final Location at, final long now) {
        final Map<UUID, Deque<Long>> mine = spearheads.get(player.getUniqueId());
        final SkillBuild build = spearheadBuild.get(player.getUniqueId());
        if (mine == null || build == null || build.definition().id().equals(definition.id())) {
            return 0;
        }
        final double radius = SkillStages.radius(build, "explosion", 2.4 * build.areaScale());
        final SkillProfile blast = SkillStages.profile(build, build.profile(), "explosion")
                .derive(build.definition().id() + ".explosion", Set.of(Tags.AREA), Set.of(Tags.STRIKE));
        final List<Runnable> booms = new ArrayList<>();
        for (final Iterator<Map.Entry<UUID, Deque<Long>>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Deque<Long>> entry = it.next();
            entry.getValue().removeIf(expires -> expires < now);
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entry.getValue().isEmpty() || !(entity instanceof LivingEntity living) || !living.isValid()) {
                it.remove();
                continue;
            }
            if (!living.getWorld().equals(at.getWorld()) || living.getLocation().distance(at) > DETONATE_RANGE + radius) {
                continue;
            }
            final int count = entry.getValue().size();
            it.remove();
            for (int i = 0; i < count; i++) {
                booms.add(() -> hitAround(player, living.getLocation(), radius, blast, Particle.CRIT));
            }
        }
        booms.forEach(Runnable::run);
        return booms.size();
    }

    /** 牠身上有沒有這個玩家的「被招架」。 */
    private boolean parried(final LivingEntity target, final Player player, final long now) {
        for (final tw.exilecore.combat.Debuffs.Debuff debuff : plugin.combat().debuffs().active(target.getUniqueId(), now)) {
            if (ChargeEffects.PARRIED.equals(debuff.id()) && player.getUniqueId().equals(debuff.source())) {
                return true;
            }
        }
        return false;
    }

    private void firePalm(final Player player) {
        final SkillDefinition bolt = service.config().skill(PALM_BOLT);
        final SkillBuild palm = loadout(player, firstWithStage(player, "projectile"));
        if (bolt == null || palm == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild base = SkillBuild.of(service.config(), bolt, palm.level(), state.level(), false, List.of(), state.weaponTags(), sheet);
        final SkillProfile profile = SkillStages.profile(palm, palm.profile(), "projectile")
                .derive(PALM_BOLT, Set.of(Tags.PROJECTILE), Set.of(Tags.MELEE, Tags.STRIKE));
        service.procCast(Origin.of(player), base.withProfile(profile), null);
    }

    /** 這個玩家技能面板上第一個有這一段的技能（震脈掌）。 */
    private String firstWithStage(final Player player, final String key) {
        final PlayerState state = plugin.players().get(player);
        for (int i = 0; i < 64; i++) {
            final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry != null && SkillStages.get(entry.skillId(), key) != null) {
                return entry.skillId();
            }
        }
        return null;
    }

    /** 技能面板上這個技能這時候的數值（不在面板上回傳 null）。 */
    private SkillBuild loadout(final Player player, final String skillId) {
        if (skillId == null) {
            return null;
        }
        final SkillDefinition definition = service.config().skill(skillId);
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        if (definition == null || position < 0) {
            return null;
        }
        return service.build(state, plugin.players().sheet(player), definition, state.skills().skill(position));
    }

    private void hitAround(final Player player, final Location at, final double radius, final SkillProfile profile, final Particle particle) {
        if (plugin.combat() == null || at.getWorld() == null) {
            return;
        }
        for (final LivingEntity victim : service.targets().around(at, radius, player)) {
            plugin.combat().skillDamage().hit(player, victim, profile, null);
        }
        at.getWorld().spawnParticle(particle, at.clone().add(0, 0.8, 0), 14, radius * 0.4, 0.4, radius * 0.4, 0.05);
    }

    /** 下線、換角色：清掉這個玩家的矛頭、增益。 */
    void forget(final UUID player) {
        lastStab.remove(player);
        spearheads.remove(player);
        spearheadBuild.remove(player);
        palmUntil.remove(player);
    }

    /** 震脈掌的增益還有幾 tick（說明、測試用；沒有是 0）。 */
    long palmTicksLeft(final UUID player) {
        final Long until = palmUntil.get(player);
        return until == null ? 0 : Math.max(0, until - now());
    }

    /** 這個敵人身上插著幾個這個玩家的矛頭。 */
    int spearheadsOn(final UUID player, final UUID target) {
        final Map<UUID, Deque<Long>> mine = spearheads.get(player);
        final Deque<Long> heads = mine == null ? null : mine.get(target);
        return heads == null ? 0 : heads.size();
    }
}
