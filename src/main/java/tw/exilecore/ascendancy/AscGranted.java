package tw.exilecore.ascendancy;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.DeadeyeMetas;
import tw.exilecore.skill.Mechanic;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5：昇華天賦賦予的技能（skills.yml 的 BEGIN/END ascendancy，tools/gen_asc_skills.py 產生）自己的效果。
 * 數值照原作；引擎做不到的地方寫在 skills.yml 那個技能的說明與註解。
 * 2026-10-27 照 PoE2：三位巨靈與支援火力的砲手是有實體的召喚物（MinionService，只聽指令、不會受傷），指令從巨靈那裡出手（巨靈會瞬移過去）；
 * 冥想改成真的引導（Channelling 的 recharge 模式，受到傷害中斷），這裡不再處理。
 */
final class AscGranted implements Listener {

    private final AscendancyEffects effects;
    private final ExileCore plugin;

    /** 一個玩家（賦予的技能）的狀態。 */
    private static final class Own {
        int temper;
        int[] motes = new int[3];
        long moteNext;
        long boilNext;
        int swarm;
        long swarmHit;
        long sprintEnd;
        long calledNext;
        int calledIndex; // 2026-10-28：預告射擊孔裡好幾個印記時輪流
        long mirageUntil;
        long mirageReady;
        int mirageIndex;
        int mirageGeneration; // 2026-10-28：第幾個幻影（舊幻影排好的下一步作廢）
        final Map<String, Long> mirageCooldowns = new HashMap<>(); // 2026-10-28 照 PoE2：這個幻影自己的冷卻（技能代號 → 好的 tick）
        Location mirageAt;
        long alignNext;
        String alignSkill;
        long alignUntil;
        long flameNext;
        int purple;
        long purpleUntil;
        long lifeRemnantReady;
        long voidCharges = 3;
        long voidRecharge;
        double ward = -1;
        long wardReady;
        long demonUntil;
        int demonflame;
        long demonTick;
        long archonUntil;
        long archonTornado;
        long apocalypseUntil;
        long apocalypseNext;
        long beetleNext;
        int beetles;
        int deceptions;
        long deceptionLast;
        Location clone;
        long cloneUntil;
        long zealotryReady;
        final ArrayDeque<Long> damaging = new ArrayDeque<>();
        final ArrayDeque<double[]> rift = new ArrayDeque<>();
        Location[] riftAt = new Location[0];
        final ArrayDeque<Location> riftLocations = new ArrayDeque<>();
        long riftNext;
        long hounds;
        String sacrificeMod;
    }

    private final Map<UUID, Own> owns = new HashMap<>();
    /** 敵人身上的血泡、命定苦痛、時間凍結次數。 */
    private final Map<UUID, Integer> boils = new HashMap<>();
    private final Map<UUID, double[]> agony = new HashMap<>();
    private final Map<UUID, UUID> agonyOwner = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> frozenAt = new HashMap<>();
    /** 2026-10-27：上次清掉已經不在的敵人（沒死就消失、區塊卸載的）是哪個 tick。 */
    private long prunedAt;

    /**
     * 2026-10-27：一隻敵人最多累積幾個血泡。原作（poe2db Blood Boil）沒寫上限，但原作的怪活不久；
     * 我們的怪可以一直待在存在範圍裡（卡牆、站樁），這次之前沒有上限，久了範圍與傷害沒有盡頭。
     */
    static final int BOIL_MAX = 20;

    /** 2026-10-27 照 PoE2：爆開的範圍 1.5 公尺（2026-10-29 起換成 2.25 格），每個血泡更多 20% 範圍（加總，不是連乘）；這次之前是 1.2 的次方連乘。 */
    static double boilRadius(final int boil) {
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（這次之前 1.5 格）
        return tw.exilecore.core.Units.metres(1.5) * Math.sqrt(1 + 0.2 * Math.max(0, Math.min(BOIL_MAX, boil)));
    }

    /** 再加一個血泡（最多 {@link #BOIL_MAX}）。 */
    static int addBoil(final int current) {
        return Math.min(BOIL_MAX, Math.max(0, current) + 1);
    }
    /** 最近死掉的稀有怪物（儀式獻祭）：位置、詞綴、到哪個 tick。 */
    private final List<RareCorpse> rares = new ArrayList<>();
    /** 地上的殘骸（夏烏拉之炎、生命痕跡）。 */
    private final List<Remnant> remnants = new ArrayList<>();
    /** 納維拉的綠洲。 */
    private final List<Oasis> oases = new ArrayList<>();

    private record RareCorpse(Location at, List<String> mods, long until) {
    }

    private record Oasis(UUID owner, Location at, double radius, long until) {
    }

    private static final class Remnant {
        final UUID owner;
        final Location at;
        final String kind;
        final double value;
        final long until;
        final ItemDisplay display;
        boolean reappear;

        Remnant(final UUID owner, final Location at, final String kind, final double value, final long until, final ItemDisplay display,
                final boolean reappear) {
            this.owner = owner;
            this.at = at;
            this.kind = kind;
            this.value = value;
            this.until = until;
            this.display = display;
            this.reappear = reappear;
        }
    }

    AscGranted(final AscendancyEffects effects) {
        this.effects = effects;
        this.plugin = effects.plugin;
    }

    private Own own(final Player player) {
        return owns.computeIfAbsent(player.getUniqueId(), id -> new Own());
    }

    private long now() {
        return effects.now();
    }

    private boolean on(final Player player, final String skillId) {
        return plugin.skills() != null && plugin.skills().auraOn(player, skillId);
    }

    private double level(final Player player, final String skillId, final String stat) {
        // 2026-10-30 照 PoE2：昇華技能的品質（gem_quality.yml）加在照等級的數值上；只有品質才有的屬性（等級表沒有）就是品質給的值
        return plugin.skills() == null ? 0 : plugin.skills().levelValue(skillId, stat, plugin.skills().grantedLevel(player))
                + plugin.skills().ascQualityFlat(player, skillId, stat);
    }

    void forget(final UUID player) {
        final Own own = owns.remove(player);
        remnants.removeIf(remnant -> {
            if (remnant.owner.equals(player)) {
                if (remnant.display != null && remnant.display.isValid()) {
                    remnant.display.remove();
                }
                return true;
            }
            return false;
        });
        oases.removeIf(oasis -> oasis.owner().equals(player));
        if (own != null) {
            own.rift.clear();
        }
    }

    // =====================================================================
    // 放技能（AscendancyEffects.beforeCast）
    // =====================================================================

    /** 玩家自己放了一個技能（扣完消耗之後、真的出手之前）：回傳這一下要多加的修飾器。 */
    List<Modifier> used(final Player player, final PlayerState state, final SkillBuild build, final LivingEntity target) {
        final List<Modifier> local = new ArrayList<>();
        final SkillDefinition definition = build.definition();
        final String id = definition.id();
        final Own own = own(player);
        final long now = now();
        final boolean damaging = definition.mechanic() != Mechanic.AURA && definition.mechanic() != Mechanic.SUMMON
                && definition.mechanic() != Mechanic.WARCRY && build.deploy() == null && !definition.hidden();
        if (damaging) {
            own.damaging.addLast(now);
            while (!own.damaging.isEmpty() && now - own.damaging.peekFirst() > AscendancyEffects.RECENT) {
                own.damaging.pollFirst();
            }
            // 赤炎利劍：你使用造成傷害的技能時，魯兹安用狂熱斬擊（冷卻 4 秒）
            // 2026-10-27 照 PoE2：魯兹安是有實體的巨靈，瞬移到目標旁邊斬（這次之前沒有實體，直接在你前方 3 格斬）
            if (on(player, "ruzhan_djinn") && now >= own.zealotryReady && plugin.skills() != null && djinnAt(player, "ruzhan_djinn") != null) {
                own.zealotryReady = now + Math.round(Math.max(1, level(player, "ruzhan_djinn", "asc.zealotry_cooldown")) * 20);
                final Location at = aim(player, target, 3);
                moveDjinn(player, "ruzhan_djinn", at.clone().subtract(player.getLocation().getDirection().setY(0).normalize().multiply(1.5)));
                effects.later(4, () -> plugin.skills().triggerSkill(player, "asc_ruzhan_zealotry", at));
            }
            // 惡魔變身：使用法術以外的技能就變回人形
            if (own.demonUntil > now && !build.profile().tags().contains(Tags.SPELL)) {
                endDemon(player, own);
            }
            // 命運同調：在下一個殘影出現前自己施放同一個法術 → 強化（更多 30%）
            if (own.alignSkill != null && own.alignSkill.equals(id) && now <= own.alignUntil) {
                local.add(Modifier.more(StatKeys.DAMAGE, 0.30, "命途同調"));
                own.alignSkill = null;
                tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.ENCHANT, player.getLocation().add(0, 1.4, 0), 30, 0.5, 0.6, 0.5, 0.5);
            }
            // 幻影銳眼（2026-10-28 照 PoE2 Mirage Deadeye：主要寶石）：你發射遠程投射物攻擊時留下幻影（冷卻 10 秒），
            // 幻影在持續時間內一直用孔裡的遠程攻擊（傷害更少 50%；tickPlayer）。冷卻、持續時間吃其他孔的迅返、長留與身上的加成。
            // 這次之前孔裡不能放技能：幻影重複你接下來的投射物攻擊，冷卻固定 10 秒、持續時間不吃加成
            if (on(player, DeadeyeMetas.MIRAGE) && build.profile().tags().contains(Tags.ATTACK) && build.profile().tags().contains(Tags.PROJECTILE)
                    && !build.profile().tags().contains(Tags.MELEE) && now >= own.mirageReady && own.mirageUntil <= now
                    && plugin.skills() != null && !plugin.skills().deadeyeSocketed(player, DeadeyeMetas.MIRAGE).isEmpty()) {
                final SkillBuild host = plugin.skills().deadeyeHost(player, DeadeyeMetas.MIRAGE);
                final StatSheet effective = host == null ? null : host.profile().effective(state.sheet());
                final Set<String> context = host == null ? Set.of() : host.profile().tags();
                final double seconds = Math.max(4, level(player, DeadeyeMetas.MIRAGE, "asc.mirage_seconds"))
                        * DeadeyeMetas.durationFactor(effective, context);
                own.mirageAt = player.getLocation().clone();
                own.mirageUntil = now + DeadeyeMetas.ticks(seconds);
                own.mirageReady = now + DeadeyeMetas.ticks(DeadeyeMetas.MIRAGE_COOLDOWN * DeadeyeMetas.cooldownFactor(effective, context));
                own.mirageCooldowns.clear();
                final int generation = ++own.mirageGeneration;
                effects.later(6, () -> mirageStep(player, generation));
                player.getWorld().spawnParticle(Particle.CLOUD, own.mirageAt.clone().add(0, 1, 0), 20, 0.3, 0.8, 0.3, 0.01);
            }
        }
        switch (id) {
            case "encase_in_jade" -> {
                final AscendancyEffects.AscState s = effects.state(player);
                if (s.jade > 0) {
                    final double seconds = Math.max(4, level(player, id, "asc.jade_guard_seconds"));
                    final double perJade = 0.06 + level(player, id, "asc.jade_guard_percent") / 100.0; // 2026-10-30 照 PoE2：品質每層 +(0—1)% 最大生命
                    state.addGuard(state.maxLife() * perJade * s.jade, now + Math.round(seconds * 20));
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#5fd38a>封入翠玉：用掉 " + s.jade + " 層玉石，獲得 "
                            + Math.round(state.maxLife() * perJade * s.jade) + " 守護"));
                    s.jade = 0;
                    plugin.players().markDirty(player);
                }
            }
            case "temper_weapon" -> {
                own.temper = 12;
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
            }
            case "ritual_sacrifice" -> ritualSacrifice(player, state, own);
            case "unbound_avatar" -> rebuff(player, build, Math.max(8, level(player, id, "asc.buff_seconds")));
            case "archon_of_chayula" -> {
                own.archonUntil = now + 200;
                own.archonTornado = now;
            }
            case "demon_form" -> {
                if (own.demonUntil > now) {
                    effects.later(1, () -> endDemon(player, own));
                } else {
                    own.demonUntil = now + 600;
                    own.demonflame = 0;
                    own.demonTick = now + 20;
                }
            }
            case "apocalypse" -> {
                own.apocalypseUntil = now + 240;
                own.apocalypseNext = now + 5;
            }
            case "time_snap" -> effects.later(1, () -> plugin.skills().resetCooldowns(player, "time_snap"));
            case "time_freeze" -> timeFreeze(player);
            case "inevitable_agony" -> {
                final Location at = aim(player, target, 20);
                final double radius = Math.max(1.5, level(player, id, "asc.agony_radius"));
                for (final LivingEntity enemy : effects.enemiesNear(player, at, radius)) {
                    agony.put(enemy.getUniqueId(), new double[] {0, now + 240});
                    agonyOwner.put(enemy.getUniqueId(), player.getUniqueId());
                }
                tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SCULK_SOUL, at.clone().add(0, 0.5, 0), 30, radius / 2, 0.3, radius / 2, 0.02);
            }
            case "temporal_rift" -> temporalRift(player, state, own);
            case "navira_oasis" -> {
                if (!navira(player)) {
                    break;
                }
                // 2026-10-27 照 PoE2：納維拉在牠所在的地方變出綠洲（這次之前巨靈沒有實體，綠洲在你腳下）
                final Location djinn = djinnAt(player, "navira_djinn");
                // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（綠洲半徑 8 公尺；這次之前 8 格）
                oases.add(new Oasis(player.getUniqueId(), (djinn == null ? player.getLocation() : djinn).clone(), tw.exilecore.core.Units.metres(8), now + 300));
            }
            case "navira_well" -> {
                if (!navira(player)) {
                    break;
                }
                state.setLastDamageTick(now - 400); // 能量護盾立刻開始回充
            }
            case "navira_fracturing" -> navira(player);
            case "kelari_deception" -> {
                // 連續使用每次更多 50%（最多 300%）；分身還在時再用，分身也爆炸
                own.deceptions = now - own.deceptionLast <= 120 ? Math.min(6, own.deceptions + 1) : 0;
                own.deceptionLast = now;
                if (own.deceptions > 0) {
                    local.add(Modifier.more(StatKeys.DAMAGE, 0.5 * own.deceptions, "珂芮絲的詭計"));
                }
                if (own.clone != null && own.cloneUntil > now && plugin.skills() != null) {
                    // 2026-10-29 照 PoE2：分身爆炸跟欺瞞同一級、也吃連續使用的更多傷害（這次之前用角色能用的最高級、沒吃到）
                    final Location clone = own.clone;
                    final int level = build.level();
                    final List<Modifier> sequence = List.copyOf(local);
                    effects.later(2, () -> plugin.skills().triggerSkill(player, "asc_kelari_clone", clone, level, sequence));
                }
                own.clone = aim(player, target, 20);
                own.cloneUntil = now + 80;
                moveDjinn(player, "kelari_djinn", own.clone); // 2026-10-27 照 PoE2：克拉里瞬移到目標的地方炸開
            }
            case "kelari_brutality" -> {
                // 2026-10-29 照 PoE2 Kelari's Brutality：飛刀鑽進地面，3 公尺內 12 處短暫延遲後爆出刀刃（中間那一處是技能本身，另外 11 處撒在周圍）
                final Location center = aim(player, target, 20);
                final double spread = tw.exilecore.core.Units.metres(3) * build.areaScale();
                final int level = build.level();
                final List<Modifier> extra = List.copyOf(local);
                final java.util.Random random = new java.util.Random();
                for (int i = 0; i < 11; i++) {
                    final double angle = random.nextDouble() * Math.PI * 2;
                    final double distance = Math.sqrt(random.nextDouble()) * spread;
                    final Location at = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                    effects.later(12 + random.nextInt(6), () -> {
                        if (plugin.skills() != null) {
                            plugin.skills().triggerSkill(player, "asc_kelari_blade", at, level, extra);
                        }
                    });
                }
            }
            case "kelari_judgment" -> moveDjinn(player, "kelari_djinn", aim(player, target, 20)); // 2026-10-27：克拉里飛過去懸起刀刃
            case "ruzhan_reckoning" -> {
                final Location at = aim(player, target, 20);
                moveDjinn(player, "ruzhan_djinn", at); // 2026-10-27 照 PoE2：魯兹安瞬移到目標的地方
                effects.later(18, () -> plugin.skills().triggerSkill(player, "asc_ruzhan_wave", at));
            }
            case "ruzhan_trap" -> moveDjinn(player, "ruzhan_djinn", aim(player, target, 20)); // 2026-10-27：魯兹安把巨劍擲過去
            case "explosive_concoction" -> {
                // 另外對附近最多 4 個點燃中的敵人各丟一瓶小的
                // 2026-10-28 照 PoE2 Mini Flasks：自己的表（stage.second 50% → 145% → 330%）、1.2 公尺（主瓶 1.8）；這次之前固定 145／185、同樣大
                final Location at = aim(player, target, 16);
                final SkillBuild mini = tw.exilecore.skill.StageAttacks.stage(build, "second");
                final SkillBuild smaller = mini.withAreaAndProjectiles(mini.areaScale() * 12.0 / 18.0, mini.projectiles());
                int thrown = 0;
                for (final LivingEntity enemy : effects.enemiesNear(player, at, 8)) {
                    if (thrown >= 4 + (int) Math.floor(build.qualityFlat("asc.explosive_extra") + 1e-9)) { // 2026-10-30 照 PoE2：品質多丟 (0—2) 個
                        break;
                    }
                    if (plugin.ailments() != null && plugin.ailments().has(enemy, AilmentType.IGNITE)) {
                        thrown++;
                        final SkillBuild small = smaller;
                        effects.later(8, () -> plugin.skills().recast(player, small, enemy.getLocation(), player.getLocation().getDirection(), enemy));
                    }
                }
            }
            default -> {
            }
        }
        return local;
    }

    /** 準心指的地方（有目標就是目標腳下）。 */
    private static Location aim(final Player player, final LivingEntity target, final double range) {
        if (target != null && target.getWorld() == player.getWorld()) {
            return target.getLocation();
        }
        final org.bukkit.util.RayTraceResult hit = player.getWorld().rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        if (hit != null) {
            return hit.getHitPosition().toLocation(player.getWorld());
        }
        return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
    }

    /** 戰吼型的增益照等級的秒數重給一次（引擎的戰吼持續時間是固定的）。 */
    private void rebuff(final Player player, final SkillBuild build, final double seconds) {
        effects.later(1, () -> {
            if (plugin.skills() != null && !build.warcryOwnModifiers().isEmpty()) {
                plugin.skills().buff(player, build.definition().id(), build.definition().name(), (int) Math.round(seconds * 20), build.warcryOwnModifiers());
            }
        });
    }

    /** 2026-10-27：那位巨靈（有實體的召喚物）在哪裡；沒召喚或不在 20 格內回傳 null。 */
    private Location djinnAt(final Player player, final String djinn) {
        return plugin.skills() == null || plugin.skills().minions() == null ? null : plugin.skills().minions().locate(player, djinn);
    }

    /** 2026-10-27：巨靈聽指令瞬移到 at（之後自己回到你身邊）。 */
    private void moveDjinn(final Player player, final String djinn, final Location at) {
        if (plugin.skills() != null && plugin.skills().minions() != null && at != null) {
            plugin.skills().minions().relocate(player, djinn, at);
        }
    }

    /** 納維拉的指令：要先召喚納維拉；觸發納維拉的寧靜（附近友軍魔力再生增加，4 秒）。 */
    private boolean navira(final Player player) {
        if (djinnAt(player, "navira_djinn") == null) {
            return false; // 2026-10-27：納維拉不在（放之前 AscendancyEffects.cost 已經擋了，這是保險）
        }
        final double regen = navira_calming(player);
        if (regen > 0) {
            for (final Player ally : player.getWorld().getPlayers()) {
                if (ally.equals(player) || ally.getLocation().distanceSquared(player.getLocation()) <= 100 && plugin.party() != null
                        && plugin.party().groupMembers(player.getUniqueId()).contains(ally.getUniqueId())) {
                    plugin.skills().buff(ally, "navira_calming", "娜瑟菈的寧靜", 80, List.of(Modifier.increased(StatKeys.MANA_REGEN, regen / 100.0, "娜瑟菈的寧靜")));
                }
            }
        }
        return true;
    }

    private double navira_calming(final Player player) {
        // 原作 1 級 10% → 20 級 67%（skills.yml 的 level_modifiers）
        return Math.max(10, level(player, "navira_djinn", "asc.calming_regen"));
    }

    private void endDemon(final Player player, final Own own) {
        own.demonUntil = 0;
        own.demonflame = 0;
        if (plugin.skills() != null) {
            plugin.skills().removeBuff(player, "demon_form");
        }
        plugin.players().markDirty(player);
    }

    private void timeFreeze(final Player player) {
        final long now = now();
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（時間凍結 10 公尺；這次之前 10 格）
        for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), tw.exilecore.core.Units.metres(10))) {
            final ArrayDeque<Long> times = frozenAt.computeIfAbsent(enemy.getUniqueId(), id -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() > 600) {
                times.pollFirst();
            }
            final long ticks = Math.round(60 / Math.pow(2, times.size()));
            times.addLast(now);
            if (plugin.ailments() != null) {
                plugin.ailments().holdStill(enemy, ticks);
            }
            enemy.getWorld().spawnParticle(Particle.SNOWFLAKE, enemy.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.6f);
    }

    private void temporalRift(final Player player, final PlayerState state, final Own own) {
        if (own.rift.isEmpty() || own.riftLocations.isEmpty()) {
            return;
        }
        final double[] snapshot = own.rift.peekFirst();
        final Location at = own.riftLocations.peekFirst();
        if (at.getWorld() != player.getWorld()) {
            return;
        }
        player.teleport(at.clone().setDirection(player.getLocation().getDirection()));
        state.setLife(Math.min(state.maxLife(), snapshot[0]));
        state.setMana(Math.min(state.maxMana(), snapshot[1]));
        state.setEnergyShield(Math.min(state.maxEnergyShield(), snapshot[2]));
        own.rift.clear();
        own.riftLocations.clear();
        plugin.players().display(player, state);
        player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, player.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
    }

    /** 儀式獻祭：附近有稀有怪物的屍體就拿牠的詞綴；沒有就割自己 20% 生命，拿一個隨機的怪物詞綴（取代上一個）。 */
    private void ritualSacrifice(final Player player, final PlayerState state, final Own own) {
        if (plugin.monsters() == null || plugin.skills() == null) {
            return;
        }
        final long now = now();
        final int ticks = (int) Math.round(Math.max(82, level(player, "ritual_sacrifice", "asc.sacrifice_seconds")) * 20);
        rares.removeIf(rare -> rare.until() < now);
        RareCorpse best = null;
        for (final RareCorpse rare : rares) {
            if (rare.at().getWorld() == player.getWorld() && rare.at().distanceSquared(player.getLocation()) <= 64) {
                best = rare;
            }
        }
        if (best != null) {
            rares.remove(best);
            for (final String modId : best.mods()) {
                grantMod(player, modId, "儀典獻祭", "ritual." + modId, ticks);
            }
            return;
        }
        state.setLife(Math.max(1, state.life() - state.maxLife() * 0.20));
        final List<tw.exilecore.monster.MonsterConfig.MonsterMod> all = new ArrayList<>(plugin.monsters().config().mods());
        if (all.isEmpty()) {
            return;
        }
        if (own.sacrificeMod != null) {
            plugin.skills().removeBuff(player, "ritual.self");
        }
        final tw.exilecore.monster.MonsterConfig.MonsterMod mod = all.get(plugin.random().nextInt(all.size()));
        own.sacrificeMod = mod.id();
        grantMod(player, mod.id(), "儀典獻祭", "ritual.self", ticks);
        plugin.players().display(player, state);
    }

    private void grantMod(final Player player, final String modId, final String sourceName, final String buffId, final int ticks) {
        final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(modId);
        if (mod == null) {
            return;
        }
        final String source = sourceName + "：" + mod.name();
        final List<Modifier> modifiers = new ArrayList<>();
        for (final tw.exilecore.item.AffixMod affixMod : mod.mods()) {
            modifiers.add(affixMod.toModifier(Map.of(), source));
        }
        for (final Map.Entry<DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
            modifiers.add(Modifier.flat(StatKeys.damageGain(entry.getKey()), entry.getValue() * 100, source));
        }
        if (mod.vanillaSpeedBonus() > 0) {
            modifiers.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, mod.vanillaSpeedBonus(), source));
        }
        if (mod.aura() != null) {
            for (final tw.exilecore.item.AffixMod affixMod : mod.aura().stats()) {
                modifiers.add(affixMod.toModifier(Map.of(), source));
            }
        }
        if (!modifiers.isEmpty()) {
            plugin.skills().buff(player, buffId, mod.name() + "（" + sourceName + "）", ticks, modifiers);
        }
    }

    // =====================================================================
    // 擊中、擊殺、翻滾
    // =====================================================================

    /** 玩家打出去的這一下的條件加成（賦予的技能）。 */
    List<Modifier> conditional(final Player attacker, final LivingEntity defender, final StatSheet sheet, final SkillProfile skill) {
        final List<Modifier> out = new ArrayList<>();
        final Own own = owns.get(attacker.getUniqueId());
        final String id = skill.id();
        if (own != null) {
            // 赤炎利劍：魯兹安最近每用過一個造成傷害的技能更多 10%
            if (id.startsWith("ruzhan_") || id.equals("asc_ruzhan_zealotry") || id.equals("asc_ruzhan_wave")) {
                if (!own.damaging.isEmpty()) {
                    out.add(Modifier.more(StatKeys.DAMAGE, 0.10 * own.damaging.size(), "赤炎利劍"));
                }
            }
            // 惡魔變身：每層惡魔烈焰更多 3% 法術傷害
            if (own.demonUntil > now() && own.demonflame > 0 && skill.tags().contains(Tags.SPELL)) {
                out.add(Modifier.more(StatKeys.DAMAGE, 0.03 * own.demonflame, "惡魔烈焰"));
            }
            // 女王進軍的蟲群：每層蟲群前進更多 2%
            if (id.equals("asc_swarm") && own.swarm > 0) {
                out.add(Modifier.more(StatKeys.DAMAGE, 0.02 * own.swarm, "蟲群前進"));
            }
        }
        if (id.startsWith("ruzhan_fury") && plugin.ailments() != null
                && (plugin.ailments().isIncapacitated(defender) || plugin.ailments().pinned(defender))) {
            out.add(Modifier.more(StatKeys.DAMAGE, 0.60, "羅贊的怒焰"));
        }
        if (id.startsWith("navira_fracturing") && plugin.ailments() != null && !plugin.ailments().has(defender, AilmentType.CHILL)) {
            out.add(Modifier.more(StatKeys.DAMAGE, -1.0, "只碎裂冰緩中的敵人"));
        }
        return out;
    }

    boolean boiling(final LivingEntity defender) {
        return boils.getOrDefault(defender.getUniqueId(), 0) > 0;
    }

    /** 玩家打中怪物之後。 */
    void hit(final Player player, final HitEvent event, final MonsterData data) {
        final LivingEntity target = event.defender();
        final SkillProfile skill = event.skill();
        if (skill == null) {
            return;
        }
        final Set<String> tags = skill.tags();
        final boolean totem = tags.contains(Tags.TOTEM_HIT);
        final long now = now();
        final Own own = own(player);
        final StatSheet sheet = effects.sheet(player);
        // 命定苦痛：記下 25% 的擊中傷害
        final double[] mark = agony.get(target.getUniqueId());
        if (mark != null) {
            mark[0] += event.result().total() * 0.25;
        }
        // 鍛鍊武器：強化過的近戰攻擊擊中時觸發燃爆
        if (own.temper > 0 && !totem && tags.contains(Tags.MELEE) && skill.isAttack() && plugin.skills() != null) {
            own.temper--;
            final Location at = target.getLocation();
            effects.later(1, () -> plugin.skills().triggerSkill(player, "asc_combust", at));
        }
        // 榮耀：壞滅災變（元素擊中）、夏烏拉統治者（混沌擊中）
        if (plugin.skills() != null && !totem) {
            final DamagePacket dealt = event.result().dealt();
            final double power = effects.power(target);
            if (dealt.get(DamageType.FIRE) + dealt.get(DamageType.COLD) + dealt.get(DamageType.LIGHTNING) > 0) {
                plugin.skills().gainGlory(player, "elemental_hit", power);
            }
            if (dealt.get(DamageType.CHAOS) > 0) {
                plugin.skills().gainGlory(player, "chaos_hit", power);
            }
        }
        // 夏烏拉統治者：擊中 25% 機率凋零 4 秒
        if (own.archonUntil > now && plugin.combat() != null && plugin.random().nextDouble() < 0.25) {
            tw.exilecore.combat.HitEffects.addWithered(plugin.combat().debuffs(), target.getUniqueId(), player.getUniqueId(), now, 1,
                    tw.exilecore.combat.HitEffects.WITHERED_TICKS);
        }
        // 生命痕跡：擊中時（最多每 3 秒一次）產生痕跡
        if (on(player, "life_remnants") && now >= own.lifeRemnantReady) {
            own.lifeRemnantReady = now + 60;
            spawnRemnant(player, target.getLocation(), "life", level(player, "life_remnants", "asc.remnant_life"), 160);
        }
        // 流血混合物：加劇流血；酸性靈藥：消耗中毒爆裂
        if (plugin.ailments() != null) {
            if (has(skill, "asc.aggravate_on_hit") || AscendancyEffects.has(sheet, AscKeys.CURSE_AGGRAVATE) && plugin.combat() != null
                    && plugin.combat().debuffs().cursed(target.getUniqueId(), now)) {
                effects.later(1, () -> plugin.ailments().aggravateBleeds(target, player.getUniqueId()));
            }
            final double consume = sum(skill, "asc.consume_poison");
            if (consume > 0 && plugin.ailments().has(target, AilmentType.POISON)) {
                final double burst = plugin.ailments().debuffDps(target) * 2 * consume / 100.0;
                plugin.ailments().cure(target, AilmentType.POISON);
                if (burst > 0) {
                    plugin.ailments().dealFlatPhysical(target, burst, player.getUniqueId());
                    target.getWorld().spawnParticle(Particle.ITEM_SLIME, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
                }
            }
        }
    }

    private static boolean has(final SkillProfile skill, final String stat) {
        return sum(skill, stat) > 0;
    }

    private static double sum(final SkillProfile skill, final String stat) {
        double total = 0;
        for (final Modifier modifier : skill.local()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.value();
            }
        }
        return total;
    }

    /** 這隻實體還在（載入中、沒被移除）。 */
    private boolean present(final UUID id) {
        final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
        return entity != null && entity.isValid();
    }

    /** 2026-10-27：死了時空裂隙的殘影清掉（復活後不能倒轉回死前的位置與生命）；這次之前只檢查同一個世界。 */
    @EventHandler
    public void onPlayerDeath(final org.bukkit.event.entity.PlayerDeathEvent event) {
        final Own own = owns.get(event.getPlayer().getUniqueId());
        if (own != null) {
            own.rift.clear();
            own.riftLocations.clear();
        }
    }

    void death(final LivingEntity dead, final Player killer) {
        final UUID id = dead.getUniqueId();
        // 鮮血沸騰：血泡爆開，每個血泡給附近的敵人一層腐化之血（每秒死掉那隻最大生命 2% 的物理，5 秒）
        final int boil = boils.getOrDefault(id, 0);
        boils.remove(id);
        final org.bukkit.attribute.AttributeInstance max = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double maxLife = max == null ? 0 : max.getValue();
        if (boil > 0 && maxLife > 0 && plugin.ailments() != null) {
            Player owner = null;
            for (final Player player : dead.getWorld().getPlayers()) {
                if (on(player, "blood_boil") && player.getLocation().distanceSquared(dead.getLocation()) <= 400) {
                    owner = player;
                    break;
                }
            }
            if (owner != null) {
                final Player source = owner;
                final double radius = boilRadius(boil);
                final List<LivingEntity> victims = effects.enemiesNear(source, dead.getLocation(), radius);
                dead.getWorld().spawnParticle(Particle.DUST, dead.getLocation().add(0, 1, 0), 30, radius / 2, 0.5, radius / 2, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(0x8a0303), 1.4f));
                final double perSecond = maxLife * 0.02 * Math.min(BOIL_MAX, boil);
                // 2026-10-30 照 PoE2 Blood Boil 的品質：腐化之血持續時間 +(0—1.5) 秒（最後不滿一秒的照比例）；這次之前固定 5 秒
                final double seconds = 5 + Math.max(0, level(source, "blood_boil", "asc.boil_seconds"));
                for (int i = 1; i <= (int) Math.ceil(seconds - 1e-9); i++) {
                    final double share = Math.min(1, seconds - (i - 1));
                    effects.later(20L * i, () -> {
                        for (final LivingEntity victim : victims) {
                            if (victim.isValid() && !victim.isDead()) {
                                plugin.ailments().dealFlatPhysical(victim, perSecond * share, source.getUniqueId());
                            }
                        }
                    });
                }
            }
        }
        agony.remove(id);
        agonyOwner.remove(id);
        frozenAt.remove(id);
        // 儀式獻祭：記下最近死掉的稀有怪物
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(dead).orElse(null);
        if (data != null && data.rarity() == MonsterRarity.RARE && !data.mods().isEmpty()) {
            rares.add(new RareCorpse(dead.getLocation().clone(), List.copyOf(data.mods()), now() + 600));
            if (rares.size() > 50) {
                rares.removeFirst();
            }
        }
        // 生命痕跡：擊殺 25% 機率
        if (killer != null && on(killer, "life_remnants")
                && plugin.random().nextDouble() < 0.25 + level(killer, "life_remnants", "asc.remnant_kill_chance") / 100.0) { // 2026-10-30 照 PoE2：品質 +(0—10)%
            spawnRemnant(killer, dead.getLocation(), "life", level(killer, "life_remnants", "asc.remnant_life"), 160);
        }
    }

    /** 翻滾時（虛空幻影）：留下 1 點生命的幻影 2 秒，被敵人打掉（2 秒後身邊有敵人）就爆炸。最多存 3 次、每 4 秒回一次。 */
    void dodge(final Player player) {
        if (!on(player, "void_illusion")) {
            return;
        }
        final Own own = own(player);
        final long now = now();
        if (own.voidCharges < 3 && now >= own.voidRecharge) {
            own.voidCharges = Math.min(3, own.voidCharges + Math.max(1, (now - own.voidRecharge) / 80 + 1));
            own.voidRecharge = now + 80;
        }
        if (own.voidCharges <= 0) {
            return;
        }
        own.voidCharges--;
        if (own.voidRecharge < now) {
            own.voidRecharge = now + 80;
        }
        final Location at = player.getLocation().clone();
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.02);
        effects.later(Math.round(Math.max(1, level(player, "void_illusion", "asc.illusion_seconds")) * 20), () -> {
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（虛空幻影爆炸 3 公尺，跟 asc_void_explosion 的範圍一樣；這次之前 3 格）
            // 2026-10-30 照 PoE2 Void Illusion 的品質：爆炸半徑 +(0—0.4) 公尺（範圍照半徑平方換算）
            final double extra = Math.max(0, level(player, "void_illusion", "asc.void_radius"));
            if (!player.isOnline() || effects.enemiesNear(player, at, tw.exilecore.core.Units.metres(3 + extra)).isEmpty() || plugin.skills() == null) {
                return;
            }
            if (extra > 0) {
                final double area = Math.pow((3 + extra) / 3, 2) - 1;
                plugin.skills().triggerSkill(player, "asc_void_explosion", at, plugin.skills().grantedLevel(player),
                        List.of(Modifier.increased(StatKeys.SKILL_AREA, area, "虛無幻影品質")));
            } else {
                plugin.skills().triggerSkill(player, "asc_void_explosion", at);
            }
        });
    }

    /** 被擊中時（高尚禦盾：失去一顆寶珠）；巫術護盾的屏障先吃元素傷害。回傳屏障吃掉多少。 */
    double ward(final Player player, final PlayerState state, final StatSheet sheet, final double total, final DamagePacket dealt) {
        final Own own = owns.get(player.getUniqueId());
        if (own != null && (own.motes[0] + own.motes[1] + own.motes[2]) > 0) {
            final List<Integer> have = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (own.motes[i] > 0) {
                    have.add(i);
                }
            }
            own.motes[have.get(plugin.random().nextInt(have.size()))]--;
            plugin.players().markDirty(player);
        }
        if (!on(player, "sorcery_ward") || dealt == null || dealt.total() <= 0 || total <= 0) {
            return 0;
        }
        final Own o = own(player);
        final long now = now();
        final double max = Math.min(32000, (sheet.get(StatKeys.ARMOUR) + sheet.get(StatKeys.EVASION))
                * (0.30 + Math.max(0, level(player, "sorcery_ward", "asc.ward_percent")) / 100.0)); // 2026-10-30 照 PoE2：品質 +(0—5)%
        if (o.ward < 0 || now >= o.wardReady && o.ward < max) {
            o.ward = max;
        }
        final double share = AscendancyEffects.has(sheet, AscKeys.WARD_ALL) ? 1.0
                : (dealt.get(DamageType.FIRE) + dealt.get(DamageType.COLD) + dealt.get(DamageType.LIGHTNING)) / dealt.total();
        final double want = total * share;
        final double taken = Math.min(o.ward, want);
        if (taken > 0) {
            o.ward -= taken;
            o.wardReady = now + Math.round(Math.max(6, level(player, "sorcery_ward", "asc.ward_delay")) * 20);
        }
        return taken;
    }

    // =====================================================================
    // 殘骸（夏烏拉之炎、生命痕跡）
    // =====================================================================

    private void spawnRemnant(final Player owner, final Location base, final String kind, final double value, final long ticks) {
        if (base.getWorld() == null) {
            return;
        }
        final Location at = base.clone().add(0, 0.5, 0);
        final org.bukkit.Material material = switch (kind) {
            case "purple" -> org.bukkit.Material.AMETHYST_SHARD;
            case "blue" -> org.bukkit.Material.LAPIS_LAZULI;
            default -> org.bukkit.Material.REDSTONE;
        };
        final ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.45f, 0.45f, 0.45f), new org.joml.Quaternionf()));
            d.setVisibleByDefault(false); // 2026-10-27 照站長：痕跡只有自己看得到（別人撿不到，看到只是礙眼）
        });
        owner.showEntity(plugin, display);
        final boolean reappear = AscendancyEffects.has(effects.sheet(owner), "remnant.reappear_ms"); // 2026-10-26：跟灌注殘骸同一個（風暴回憶）
        remnants.add(new Remnant(owner.getUniqueId(), at, kind, value, now() + ticks, display, reappear));
    }

    private void tickRemnants(final long now) {
        final Iterator<Remnant> iterator = remnants.iterator();
        final List<Remnant> again = new ArrayList<>();
        while (iterator.hasNext()) {
            final Remnant remnant = iterator.next();
            final Player owner = plugin.getServer().getPlayer(remnant.owner);
            if (owner == null || now >= remnant.until || remnant.display == null || !remnant.display.isValid()) {
                if (remnant.display != null && remnant.display.isValid()) {
                    remnant.display.remove();
                }
                iterator.remove();
                continue;
            }
            final StatSheet sheet = effects.sheet(owner);
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（撿殘骸 1.6 公尺；這次之前 1.6 格）
            final double pickup = tw.exilecore.core.Units.metres(1.6) * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.TreeMechanicKeys.REMNANT_RANGE, Set.of()));
            if (owner.getWorld() != remnant.at.getWorld() || owner.getLocation().distanceSquared(remnant.at) > pickup * pickup) {
                continue;
            }
            remnant.display.remove();
            iterator.remove();
            collect(owner, remnant, sheet);
            if (remnant.reappear) {
                again.add(remnant);
            }
        }
        for (final Remnant remnant : again) {
            final Player owner = plugin.getServer().getPlayer(remnant.owner);
            if (owner != null) {
                effects.later(60, () -> {
                    if (owner.isOnline()) {
                        final long left = 160;
                        spawnRemnantOnce(owner, remnant.at, remnant.kind, remnant.value, left);
                    }
                });
            }
        }
    }

    private void spawnRemnantOnce(final Player owner, final Location at, final String kind, final double value, final long ticks) {
        spawnRemnant(owner, at.clone().subtract(0, 0.5, 0), kind, value, ticks);
        if (!remnants.isEmpty()) {
            remnants.getLast().reappear = false; // 只再出現一次
        }
    }

    private void collect(final Player owner, final Remnant remnant, final StatSheet sheet) {
        final PlayerState state = plugin.players().get(owner);
        final double effect = Math.max(0, 1 + sheet.increased(AscKeys.REMNANT_EFFECT, Set.of()) + flameEffect(owner, remnant.kind));
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
        switch (remnant.kind) {
            case "life" -> {
                // 生命痕跡：回復生命，可以溢出到 1.5 倍（溢出的部分變成守護）
                final double amount = remnant.value * effect;
                final double room = Math.max(0, state.maxLife() - state.life());
                state.setLife(state.life() + Math.min(room, amount));
                // 2026-10-28：溢出的部分疊加到現有的守護上（合計最多 0.5 倍最大生命）；這次之前取較大的（連撿好幾個不會疊，看起來像沒作用）
                final double overflow = Math.max(0, amount - room);
                if (overflow > 0) {
                    final double total = Math.min(state.maxLife() * 0.5, state.guard(now()) + overflow);
                    state.addGuard(total, now() + 160);
                }
            }
            case "red" -> state.addLeech(state.maxLife() * 0.20 * effect);
            case "blue" -> state.addManaLeech(state.maxMana() * 0.20 * effect);
            default -> {
                final Own own = own(owner);
                own.purple = (int) Math.min(10, own.purple + 1);
                own.purpleUntil = now() + 200;
                plugin.players().markDirty(owner);
            }
        }
        plugin.players().display(owner, state);
    }

    /** 遁入裂痕的寶石等級給的夏烏拉之炎效果增加（0～50%）。 */
    private double flameEffect(final Player owner, final String kind) {
        if (kind.equals("life")) {
            return 0;
        }
        return level(owner, "into_the_breach", "asc.flame_effect") / 100.0
                * (1 + Math.max(0, level(owner, "into_the_breach", "asc.flame_effect_inc")) / 100.0); // 2026-10-30 照 PoE2：品質增加 (0—15)% 夏烏拉之炎效果
    }

    // =====================================================================
    // 每 10 tick
    // =====================================================================

    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead() || plugin.skills() == null) {
                continue;
            }
            try {
                tickPlayer(player, state, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("昇華賦予的技能（每秒）出錯", e);
            }
        }
        tickRemnants(now);
        // 命定苦痛：時間到（或足以處決）時一次失去記下的生命
        final Iterator<Map.Entry<UUID, double[]>> iterator = agony.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, double[]> entry = iterator.next();
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                iterator.remove();
                continue;
            }
            final double tracked = entry.getValue()[0];
            final boolean expired = now >= entry.getValue()[1];
            final org.bukkit.attribute.AttributeInstance max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            final double cull = max == null ? 0 : max.getValue() * 0.10;
            if (expired || tracked > 0 && living.getHealth() - tracked <= cull) {
                iterator.remove();
                final Player owner = plugin.getServer().getPlayer(agonyOwner.getOrDefault(entry.getKey(), new UUID(0, 0)));
                agonyOwner.remove(entry.getKey());
                if (tracked > 0) {
                    living.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, living.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
                    if (living.getHealth() - tracked <= cull && owner != null) {
                        living.damage(living.getHealth() + 1, owner); // 處決
                    } else {
                        living.setHealth(Math.max(1, living.getHealth() - tracked));
                    }
                }
            }
        }
        oases.removeIf(oasis -> oasis.until() <= now);
        // 2026-10-27：沒死就消失（區塊卸載、被清掉）的敵人不會有死亡事件，每 10 秒清一次；這次之前只在死亡時清，會一直累積
        if (now - prunedAt >= 200) {
            prunedAt = now;
            boils.keySet().removeIf(id -> !present(id));
            frozenAt.keySet().removeIf(id -> !present(id));
            agonyOwner.keySet().removeIf(id -> !agony.containsKey(id));
        }
        // 血肉低語：被詛咒的目標至少保留 15% 生命
        if (plugin.combat() != null && AscendancyEffects.every(now, 20)) {
            for (final UUID target : plugin.combat().debuffs().targets()) {
                if (!plugin.combat().debuffs().cursed(target, now)) {
                    continue;
                }
                double reserve = 0;
                for (final tw.exilecore.combat.Debuffs.Debuff debuff : plugin.combat().debuffs().active(target, now)) {
                    final Player source = debuff.source() == null ? null : plugin.getServer().getPlayer(debuff.source());
                    if (source != null) {
                        reserve = Math.max(reserve, AscendancyEffects.flat(effects.sheet(source), AscKeys.CURSE_RESERVE));
                    }
                }
                final org.bukkit.entity.Entity entity = reserve > 0 ? plugin.getServer().getEntity(target) : null;
                if (entity instanceof LivingEntity living && !(entity instanceof Player) && living.isValid()) {
                    final org.bukkit.attribute.AttributeInstance max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (max != null && living.getHealth() > max.getValue() * (1 - reserve / 100.0)) {
                        living.setHealth(max.getValue() * (1 - reserve / 100.0));
                    }
                }
            }
        }
    }

    private void tickPlayer(final Player player, final PlayerState state, final long now) {
        final StatSheet sheet = state.sheet();
        final Own own = owns.get(player.getUniqueId());
        final boolean any = own != null || on(player, "virtuous_barrier") || on(player, "blood_boil") || on(player, "into_the_breach")
                || on(player, "called_shots") || on(player, "align_fate") || on(player, "kelari_malediction") || on(player, "queens_procession")
                || on(player, "infernal_hound") || state.skills().find("temporal_rift") >= 0 || !oases.isEmpty();
        if (!any) {
            return;
        }
        final Own o = own(player);
        boolean dirty = false;
        // 時空裂隙：每 0.25 秒記一個殘影（最多 16 個）
        if (state.skills().find("temporal_rift") >= 0 && now >= o.riftNext) {
            o.riftNext = now + 5;
            o.rift.addLast(new double[] {state.life(), state.mana(), state.energyShield()});
            o.riftLocations.addLast(player.getLocation().clone());
            while (o.rift.size() > 16) {
                o.rift.pollFirst();
                o.riftLocations.pollFirst();
            }
        }
        // 高尚禦盾：每隔一陣子一顆隨機寶珠
        if (on(player, "virtuous_barrier")) {
            if (now >= o.moteNext) {
                o.moteNext = now + Math.round(Math.max(1.5, level(player, "virtuous_barrier", "asc.mote_interval")) * 20);
                final int[] cap = moteCaps(state);
                final List<Integer> open = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if (o.motes[i] < cap[i]) {
                        open.add(i);
                    }
                }
                if (!open.isEmpty()) {
                    o.motes[open.get(plugin.random().nextInt(open.size()))]++;
                    dirty = true;
                }
            }
        } else if (o.motes[0] + o.motes[1] + o.motes[2] > 0) {
            o.motes = new int[3];
            dirty = true;
        }
        // 鮮血沸騰：存在範圍內的非傳奇敵人累積血泡
        if (on(player, "blood_boil") && now >= o.boilNext) {
            o.boilNext = now + Math.round(Math.max(1.5, level(player, "blood_boil", "asc.boil_interval")) * 20);
            for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), AscendancyEffects.presence(sheet))) {
                final MonsterData data = plugin.monsters().data(enemy).orElse(null);
                if (data != null && !data.boss() && data.rarity() != MonsterRarity.UNIQUE) {
                    boils.put(enemy.getUniqueId(), addBoil(boils.getOrDefault(enemy.getUniqueId(), 0)));
                    enemy.getWorld().spawnParticle(Particle.DUST, enemy.getLocation().add(0, enemy.getHeight(), 0), 3, 0.2, 0.1, 0.2, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(0xb00000), 1f));
                }
            }
        }
        // 遁入裂痕：夏烏拉之炎
        if (on(player, "into_the_breach") && now >= o.flameNext) {
            o.flameNext = now + Math.round(Math.max(1.2, level(player, "into_the_breach", "asc.flame_interval")) * 20);
            final int colour = (int) AscendancyEffects.flat(sheet, AscKeys.FLAME_COLOUR);
            final String kind = switch (colour) {
                case 1 -> "purple";
                case 2 -> "blue";
                case 3 -> "red";
                default -> new String[] {"purple", "blue", "red"}[plugin.random().nextInt(3)];
            };
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double distance = 2 + plugin.random().nextDouble() * 6.5;
            final Location at = player.getLocation().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            at.setY(player.getLocation().getY());
            spawnRemnant(player, at, kind, 0, 500);
        }
        if (o.purple > 0 && now >= o.purpleUntil) {
            o.purple = 0;
            dirty = true;
        }
        // 預告射擊（2026-10-28 照 PoE2 Called Shots：主要寶石）：預告射擊自己的冷卻（5 秒，不吃冷卻恢復）好了、孔裡的印記自己的冷卻也好了，
        // 就把它放在範圍內（10 格，吃範圍加成）一個還沒被標記的敵人身上（好幾個照孔的順序輪流）；印記用掉時馬上補放（markConsumed）。
        // 這次之前放面板上第一個印記、間隔固定 5 秒（同一天稍早：間隔吃冷卻恢復、不看印記自己的冷卻）
        if (!markListening && plugin.combat() != null && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().onMarkConsumed(this::markConsumed);
            markListening = true;
        }
        if (on(player, DeadeyeMetas.CALLED_SHOTS) && now >= o.calledNext && plugin.combat() != null) {
            calledApply(player, o, now);
        }
        // 命運同調：殘影施放面板上一個法術
        if (on(player, "align_fate") && now >= o.alignNext) {
            final List<String> spells = alignSpells(state);
            final double base = Math.max(9, level(player, "align_fate", "asc.visage_interval"));
            final double speed = Math.max(0.1, 1 + sheet.increased(StatKeys.COOLDOWN_RECOVERY, Set.of())) * (1 + 0.25 * spells.size())
                    * (1 + Math.max(0, level(player, "align_fate", "asc.visage_frequency")) / 100.0); // 2026-10-30 照 PoE2：品質更多 (0—20)% 殘影頻率
            o.alignNext = now + Math.round(base * 20 / speed);
            if (!spells.isEmpty()) {
                final String spell = spells.get(plugin.random().nextInt(spells.size()));
                final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 15);
                if (!near.isEmpty()) {
                    plugin.skills().castForFree(player, spell, near.get(plugin.random().nextInt(near.size())), 1.0, "命途同調");
                    o.alignSkill = spell;
                    o.alignUntil = o.alignNext;
                    player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.5, 0), 25, 0.6, 0.6, 0.6, 0.6);
                }
            }
        }
        // 克拉里的惡言：消耗存在範圍內的屍體，生出屍甲蟲衝向敵人爆炸
        if (on(player, "kelari_malediction") && on(player, "kelari_djinn") && now >= o.beetleNext && plugin.combat() != null) {
            o.beetleNext = now + Math.round(Math.max(0.9, level(player, "kelari_malediction", "asc.beetle_interval")) * 20);
            final int max = (int) Math.max(4, level(player, "kelari_malediction", "asc.beetle_max"));
            final tw.exilecore.combat.Corpses.Corpse corpse = plugin.combat().corpses().nearest(player.getWorld().getUID(),
                    player.getLocation().getX(), player.getLocation().getZ(), AscendancyEffects.presence(sheet), now);
            if (corpse != null) {
                plugin.combat().corpses().remove(corpse);
                final Location at = new Location(player.getWorld(), corpse.x(), corpse.y(), corpse.z());
                final List<LivingEntity> near = effects.enemiesNear(player, at, 10);
                for (int i = 0; i < 2 && i < Math.max(1, Math.min(max, near.size())); i++) {
                    final LivingEntity target = near.isEmpty() ? null : near.get(i % near.size());
                    if (target != null) {
                        final long delay = 10 + i * 4L;
                        effects.later(delay, () -> plugin.skills().triggerSkill(player, "asc_corpse_beetle", target.getLocation()));
                    }
                }
                at.getWorld().spawnParticle(Particle.FALLING_DUST, at.clone().add(0, 0.5, 0), 20, 0.4, 0.3, 0.4, 0,
                        org.bukkit.Material.SAND.createBlockData());
            }
        }
        // 女王進軍：衝刺時化成蝗蟲，每 0.5 秒打 3 公尺（4.5 格）內的敵人
        if (on(player, "queens_procession")) {
            if (player.isSprinting()) {
                o.sprintEnd = now;
                if (now >= o.swarmHit) {
                    o.swarmHit = now + 10;
                    swarm(player, state, o);
                }
            } else if (o.swarm > 0 && now - o.sprintEnd >= 80) {
                o.swarm = 0;
            }
        }
        // 召喚獄炎獵犬：獵犬身邊 2.5 公尺（3.75 格）的敵人點燃（照獵犬最大生命的 20%）
        if (on(player, "infernal_hound") && AscendancyEffects.every(now, 20) && plugin.skills().minions() != null && plugin.ailments() != null) {
            for (final org.bukkit.entity.Entity entity : player.getNearbyEntities(30, 10, 30)) {
                if (entity instanceof org.bukkit.entity.Wolf wolf && plugin.skills().minions().isMinion(wolf)
                        && player.equals(plugin.skills().minions().owner(wolf))) {
                    final org.bukkit.attribute.AttributeInstance hp = wolf.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    // 2026-10-27 修正：原版血量是縮過的（真正生命 × healthScale，healthScale ≤ 1），要除回去；以前乘 max(1, 縮放) 等於沒還原，
                    // 獵犬生命超過 1024 時點燃一律只照 1024 算
                    final double houndLife = hp == null ? 0 : hp.getValue() / Math.max(1e-6, plugin.skills().minions().healthScale(wolf));
                    // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（獵犬點燃 2.5 公尺；這次之前 2.5 格）
                    for (final LivingEntity enemy : effects.enemiesNear(player, wolf.getLocation(), tw.exilecore.core.Units.metres(2.5))) {
                        plugin.ailments().igniteFrom(player, enemy, houndLife * 0.20, sheet);
                    }
                    wolf.getWorld().spawnParticle(Particle.FLAME, wolf.getLocation().add(0, 0.5, 0), 6, 1.2, 0.2, 1.2, 0.01);
                }
            }
        }
        // 夏烏拉統治者：每 3 秒觸發夏烏拉之志
        if (o.archonUntil > now && now - o.archonTornado >= 60) {
            o.archonTornado = now;
            final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 10);
            final Location at = near.isEmpty() ? player.getLocation() : near.getFirst().getLocation();
            plugin.skills().triggerSkill(player, "asc_chayula_will", at);
        }
        // 壞滅災變：每 0.75 秒一種天災
        if (o.apocalypseUntil > now && now >= o.apocalypseNext) {
            o.apocalypseNext = now + Math.max(1, Math.round(15 / (1 + Math.max(0, level(player, "apocalypse", "asc.apocalypse_frequency")) / 100.0))); // 2026-10-30 照 PoE2：品質增加 (0—15)% 頻率
            final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 7);
            final Location at = near.isEmpty() ? player.getLocation().add(plugin.random().nextGaussian() * 3, 0, plugin.random().nextGaussian() * 3)
                    : near.get(plugin.random().nextInt(near.size())).getLocation();
            plugin.skills().triggerSkill(player, new String[] {"asc_apocalypse_fire", "asc_apocalypse_cold", "asc_apocalypse_lightning"}[plugin.random().nextInt(3)], at);
        }
        // 惡魔變身：每秒一層惡魔烈焰（最多 10 層；黑暗精通：沒有上限），每層每秒失去生命；剩 1 生命就變回人形
        if (o.demonUntil > now) {
            if (now >= o.demonTick) {
                o.demonTick = now + 20;
                final boolean uncapped = AscendancyEffects.has(sheet, AscKeys.DEMONFLAME_UNCAPPED);
                o.demonflame = uncapped ? o.demonflame + 1 : Math.min(10, o.demonflame + 1);
                dirty = true;
            }
            final double loss = level(player, "demon_form", "asc.demon_life_loss") * o.demonflame * AscendancyEffects.PERIOD / 20.0;
            if (state.life() - loss <= 1) {
                state.setLife(1);
                endDemon(player, o);
            } else {
                state.setLife(state.life() - loss);
            }
            plugin.players().display(player, state);
            o.demonUntil = Math.max(o.demonUntil, now + AscendancyEffects.PERIOD + 1); // 一直到變回人形
        }
        // 納維拉的綠洲：站在裡面的你與隊友藥劑充能、受到的傷害回復
        for (final Oasis oasis : oases) {
            if (oasis.at().getWorld() == player.getWorld() && oasis.at().distanceSquared(player.getLocation()) <= oasis.radius() * oasis.radius()) {
                plugin.skills().buff(player, "navira_oasis.in", "娜瑟菈的綠洲", 15, List.of(Modifier.flat(StatKeys.LIFE_RECOUP, 30, "娜瑟菈的綠洲"),
                        Modifier.flat(StatKeys.MANA_RECOUP, 20, "娜瑟菈的綠洲")));
                if (AscendancyEffects.every(now, 40) && plugin.flasks() != null) {
                    plugin.flasks().addCharges(player, 1, tw.exilecore.flask.FlaskKind.LIFE);
                    plugin.flasks().addCharges(player, 1, tw.exilecore.flask.FlaskKind.MANA);
                }
                if (AscendancyEffects.every(now, 20)) {
                    oasis.at().getWorld().spawnParticle(Particle.DRIPPING_WATER, oasis.at().clone().add(0, 1, 0), 20, oasis.radius() / 2, 0.4,
                            oasis.radius() / 2, 0);
                }
            }
        }
        if (dirty) {
            plugin.players().markDirty(player);
        }
    }

    /** 女王進軍：打一下蝗蟲群。 */
    private void swarm(final Player player, final PlayerState state, final Own own) {
        if (plugin.combat() == null || plugin.combat().skillDamage() == null) {
            return;
        }
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（蝗蟲群 3 公尺；這次之前 3 格）
        final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), tw.exilecore.core.Units.metres(3));
        player.getWorld().spawnParticle(Particle.ASH, player.getLocation().add(0, 1, 0), 30, 1.5, 0.8, 1.5, 0);
        if (near.isEmpty()) {
            return;
        }
        final double min = level(player, "queens_procession", "asc.swarm_min");
        final double max = level(player, "queens_procession", "asc.swarm_max");
        final double amount = (min + plugin.random().nextDouble() * Math.max(0, max - min)) * (1 + 0.02 * own.swarm);
        final SkillProfile profile = new SkillProfile("asc_swarm", Set.of(Tags.ATTACK, Tags.AREA, Tags.PHYSICAL), 1.0,
                new DamagePacket().set(DamageType.PHYSICAL, amount));
        for (final LivingEntity enemy : near) {
            plugin.combat().skillDamage().hit(player, enemy, profile, null);
        }
        // 打中時：你受到一點物理傷害、獲得一層蟲群前進
        own.swarm = Math.min(50, own.swarm + 1);
        effects.selfHit(player, state, new DamagePacket().set(DamageType.PHYSICAL, level(player, "queens_procession", "asc.swarm_self")), "女皇巡行");
    }

    /** 高尚禦盾：每種寶珠的上限 = 3 ＋ 面板上需要那種屬性的技能數（我們的寶石只有一種顏色：算單一屬性，兩倍）。 */
    private int[] moteCaps(final PlayerState state) {
        final int[] caps = {3, 3, 3};
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null) {
                continue;
            }
            final int colour = AscBuild.skillColour(entry.skillId());
            if (colour >= 0) {
                caps[colour] += 2;
            }
        }
        return caps;
    }

    // =====================================================================
    // 星矢的主要寶石（2026-10-28 照 PoE2 0.5）
    // =====================================================================

    /** 印記被用掉的通知登記了沒。 */
    private boolean markListening;

    /** 預告射擊放一次孔裡的印記（預告射擊、印記自己的冷卻都好了才放）；放了就重新開始預告射擊的冷卻。回傳有沒有放。 */
    private boolean calledApply(final Player player, final Own o, final long now) {
        final List<SkillBuild> marks = plugin.skills().deadeyeSocketed(player, DeadeyeMetas.CALLED_SHOTS);
        if (marks.isEmpty() || plugin.combat() == null) {
            return false;
        }
        final SkillBuild host = plugin.skills().deadeyeHost(player, DeadeyeMetas.CALLED_SHOTS);
        final double radius = DeadeyeMetas.CALLED_RADIUS * (host == null ? 1.0 : host.areaScale());
        for (int i = 0; i < marks.size(); i++) {
            final SkillBuild mark = marks.get(Math.floorMod(o.calledIndex + i, marks.size()));
            if (plugin.skills().cooldownLeft(player, mark.definition().id()) > 0) {
                continue;
            }
            for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), radius)) {
                if (!plugin.combat().debuffs().marked(enemy.getUniqueId(), now) && plugin.skills().castSocketedMark(player, mark, enemy, true)) {
                    o.calledIndex += i + 1;
                    o.calledNext = now + DeadeyeMetas.ticks(Math.max(1, level(player, DeadeyeMetas.CALLED_SHOTS, "asc.called_seconds")));
                    return true;
                }
            }
            return false; // 範圍內沒有沒被標記的敵人
        }
        return false;
    }

    /** 印記被用掉（Debuffs.remove）：放的人開著預告射擊、是孔裡的印記，下一 tick 擴散、補放。 */
    private void markConsumed(final UUID target, final String markId, final UUID source, final long now) {
        final Player player = plugin.getServer().getPlayer(source);
        if (player == null || !player.isOnline() || plugin.skills() == null || !on(player, DeadeyeMetas.CALLED_SHOTS)) {
            return;
        }
        effects.later(1, () -> calledConsumed(player, target, markId));
    }

    /**
     * 2026-10-28 照 PoE2 Called Shots：孔裡的印記被用掉——
     * 擴散（SupportMetaDeadeyeMarksPlayer「Supported Skills will Mark another Enemy within 3 Metres of Marked Enemy when Consumed」，100%）：
     * 被用掉那隻 3 格內另一個還沒被標記的敵人；
     * 補放（「Consuming socketed Marks will cause them to be reapplied to an unmarked Enemy. This reapplication can happen at most once every few seconds」）：
     * 預告射擊的冷卻好了就馬上放到一個沒被標記的敵人身上。
     */
    private void calledConsumed(final Player player, final UUID targetId, final String markId) {
        if (!player.isOnline() || plugin.combat() == null || !on(player, DeadeyeMetas.CALLED_SHOTS)) {
            return;
        }
        SkillBuild mark = null;
        for (final SkillBuild socketed : plugin.skills().deadeyeSocketed(player, DeadeyeMetas.CALLED_SHOTS)) {
            if (socketed.definition().id().equals(markId)) {
                mark = socketed;
                break;
            }
        }
        if (mark == null) {
            return;
        }
        final long now = now();
        final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(targetId);
        if (entity instanceof LivingEntity consumed && consumed.getWorld() == player.getWorld()) {
            for (final LivingEntity enemy : effects.enemiesNear(player, consumed.getLocation(), DeadeyeMetas.SPREAD_RADIUS)) {
                if (!enemy.equals(consumed) && !plugin.combat().debuffs().marked(enemy.getUniqueId(), now)) {
                    plugin.skills().castSocketedMark(player, mark, enemy, false);
                    break;
                }
            }
        }
        final Own o = own(player);
        if (now >= o.calledNext) {
            calledApply(player, o, now);
        }
    }

    /**
     * 2026-10-28 照 PoE2 Mirage Deadeye：幻影的下一步——照孔的順序挑一個（這個幻影自己的冷卻好了的）攻擊，朝最近的敵人用；
     * 引導／蓄力的先站著引導（疊滿、有完美時機的在完美時機放開），幻影先消失就沒放出去。
     */
    private void mirageStep(final Player player, final int generation) {
        final Own o = owns.get(player.getUniqueId());
        if (o == null || o.mirageGeneration != generation || plugin.skills() == null) {
            return;
        }
        final long now = now();
        if (!player.isOnline() || o.mirageUntil <= now || o.mirageAt == null || o.mirageAt.getWorld() != player.getWorld()
                || !on(player, DeadeyeMetas.MIRAGE)) {
            o.mirageUntil = Math.min(o.mirageUntil, now);
            return;
        }
        final List<SkillBuild> attacks = plugin.skills().deadeyeSocketed(player, DeadeyeMetas.MIRAGE);
        SkillBuild attack = null;
        long wait = 5;
        if (!attacks.isEmpty() && !effects.enemiesNear(player, o.mirageAt, DeadeyeMetas.MIRAGE_RANGE).isEmpty()) {
            long soonest = Long.MAX_VALUE;
            for (int i = 0; i < attacks.size(); i++) {
                final SkillBuild candidate = attacks.get(Math.floorMod(o.mirageIndex + i, attacks.size()));
                final Long ready = o.mirageCooldowns.get(candidate.definition().id());
                if (ready == null || ready <= now) {
                    attack = candidate;
                    o.mirageIndex += i + 1;
                    break;
                }
                soonest = Math.min(soonest, ready - now);
            }
            if (attack == null) {
                wait = Math.max(1, Math.min(20, soonest));
            }
        }
        if (attack == null) {
            effects.later(wait, () -> mirageStep(player, generation));
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final tw.exilecore.skill.SkillService.MirageRelease release = plugin.skills().mirageRelease(player, attack);
        final SkillBuild fired = DeadeyeMetas.mirageBuild(release.build());
        if (attack.cooldownTicks() > 0) {
            o.mirageCooldowns.put(attack.definition().id(), now + release.holdTicks() + attack.cooldownTicks());
        }
        final long gap = Math.max(4, Math.round(attack.intervalTicks(sheet)));
        if (release.holdTicks() > 0) {
            final Location at = o.mirageAt.clone().add(0, 1.2, 0);
            for (long t = 0; t < release.holdTicks(); t += 5) {
                effects.later(t, () -> {
                    if (o.mirageGeneration == generation && o.mirageUntil > now()) {
                        at.getWorld().spawnParticle(Particle.ENCHANT, at, 6, 0.3, 0.4, 0.3, 0.3);
                    }
                });
            }
            effects.later(release.holdTicks(), () -> mirageFire(player, generation, fired));
            effects.later(release.holdTicks() + 4, () -> mirageStep(player, generation));
        } else {
            mirageFire(player, generation, fired);
            effects.later(gap, () -> mirageStep(player, generation));
        }
    }

    /** 幻影放出那一下（朝幻影最近的敵人）；幻影已經消失就不放。 */
    private void mirageFire(final Player player, final int generation, final SkillBuild fired) {
        final Own o = owns.get(player.getUniqueId());
        final long now = now();
        if (o == null || o.mirageGeneration != generation || o.mirageUntil <= now || o.mirageAt == null || !player.isOnline()
                || o.mirageAt.getWorld() != player.getWorld()) {
            return;
        }
        final List<LivingEntity> near = effects.enemiesNear(player, o.mirageAt, DeadeyeMetas.MIRAGE_RANGE);
        if (near.isEmpty()) {
            return;
        }
        LivingEntity target = near.getFirst();
        for (final LivingEntity enemy : near) {
            if (enemy.getLocation().distanceSquared(o.mirageAt) < target.getLocation().distanceSquared(o.mirageAt)) {
                target = enemy;
            }
        }
        final Location from = o.mirageAt.clone();
        plugin.skills().recast(player, fired, from, target.getLocation().toVector().subtract(from.toVector()).normalize(), target);
        // 2026-10-31：霆落矛改成投射物，幻影擲的矛停下來時自己插地（SkillService.lanceLanded）；
        // 這次之前（2026-10-30）這裡呼叫 proxyLanded 插在目標腳下
        from.getWorld().spawnParticle(Particle.END_ROD, from.clone().add(0, 1, 0), 8, 0.2, 0.5, 0.2, 0.01);
    }

    private List<String> alignSpells(final PlayerState state) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            final SkillDefinition definition = entry == null ? null : plugin.skills().config().skill(entry.skillId());
            if (definition == null || definition.hidden() || definition.isAttack() || !definition.tags().contains(Tags.SPELL)
                    || definition.cooldown() > 0 || !definition.dealsDamage() || definition.tags().contains("channelling")
                    || definition.tags().contains("command") || definition.mechanic() == Mechanic.AURA || definition.mechanic() == Mechanic.WARCRY
                    || definition.mechanic() == Mechanic.SUMMON || definition.mechanic() == Mechanic.META) {
                continue;
            }
            out.add(definition.id());
        }
        return out;
    }

    /** 這些技能給的暫時修飾器（高尚禦盾的寶珠、紫色夏烏拉之炎）。 */
    List<Modifier> buffModifiers(final Player player, final PlayerState state) {
        final Own own = owns.get(player.getUniqueId());
        if (own == null) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        if (own.motes[0] > 0) {
            out.add(Modifier.increased(StatKeys.LIFE_MAX, 0.02 * own.motes[0], "正氣護盾"));
        }
        if (own.motes[1] > 0) {
            out.add(Modifier.increased(StatKeys.ARMOUR, 0.05 * own.motes[1], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.EVASION, 0.05 * own.motes[1], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.ES_MAX, 0.05 * own.motes[1], "正氣護盾"));
        }
        if (own.motes[2] > 0) {
            final double regen = 0.05 + Math.max(0, level(player, "virtuous_barrier", "asc.mote_regen")) / 100.0; // 2026-10-30 照 PoE2：品質每顆 +(0—2.5)%
            out.add(Modifier.increased(StatKeys.LIFE_REGEN, regen * own.motes[2], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.MANA_REGEN, regen * own.motes[2], "正氣護盾"));
        }
        if (own.purple > 0) {
            final double effect = 1 + state.sheet().increased(AscKeys.REMNANT_EFFECT, Set.of()) + flameEffect(player, "purple");
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.CHAOS, 7 * own.purple * effect, "霞瑤之炎"));
        }
        return out;
    }

    /** 元素展現：照力量、敏捷、智慧的比例選一種（火、電、冰）。 */
    static String expressionFor(final AscendancyEffects effects, final Player player) {
        final StatSheet sheet = effects.sheet(player);
        final double str = Math.max(0, sheet.get(StatKeys.STR));
        final double dex = Math.max(0, sheet.get(StatKeys.DEX));
        final double intelligence = Math.max(0, sheet.get(StatKeys.INT));
        final double total = str + dex + intelligence;
        final double roll = effects.plugin.random().nextDouble() * Math.max(1e-9, total);
        if (total <= 0 || roll < str) {
            return "asc_expression_fire";
        }
        return roll < str + dex ? "asc_expression_lightning" : "asc_expression_cold";
    }

    /** 衝刺突擊：衝刺時無法被重度暈眩（衝刺開始、結束時重算屬性表）。 */
    @EventHandler
    public void onSprint(final PlayerToggleSprintEvent event) {
        if (AscendancyEffects.has(effects.sheet(event.getPlayer()), AscKeys.SPRINT_STUN) || on(event.getPlayer(), "queens_procession")) {
            plugin.players().markDirty(event.getPlayer());
        }
    }
}
