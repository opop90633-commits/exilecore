package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 戰吼：瞬間吼一聲。2026-10-26 照 PoE2 0.5 重做：數範圍內敵人的強度（普通 1、魔法 2、稀有 10、傳奇 20），
 * 照強度強化接下來幾次攻擊（附近沒有敵人就不強化）、給守護或怒氣；有的會打傷害、震退、動搖敵人（死掉時燃爆）。
 * 舊的效果（回復生命、隊友增益、受到的傷害增加、嘲諷）只留給不是原作戰吼的技能（神龕祝禱、戰旗、祭品）。
 */
final class Warcries {

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillBuffs buffs;

    /** 嘲諷：持續時間內被嘲諷的怪只打你。 */
    private final Taunts taunts;

    Warcries(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillBuffs buffs) {
        this.plugin = plugin;
        this.taunts = new Taunts(plugin);
        this.targets = targets;
        this.visuals = visuals;
        this.buffs = buffs;
    }

    Taunts taunts() {
        return taunts;
    }

    /** 銀月庇一次最多用掉幾點怒氣（比任何怒氣上限都大：等於全部）。 */
    static final int RAGE_FEED_CAP = 1000;

    /** 祭柱找骷髏的範圍（格）。 */
    static final double SPIKE_SEARCH = 12;
    /** 2026-10-30：骸骨祭（骨柱生命 0.35 倍；其他祭品 0.18 倍）。 */
    static final String ROLE_BONE = "bone_rite";

    /** 一根祭柱（2026-10-30 照 PoE2：mob 是骨柱本身的召喚物，被打死祭品就結束）。 */
    private record Spike(org.bukkit.entity.BlockDisplay display, org.bukkit.scheduler.BukkitTask task, long start, java.util.UUID mob) {
        void remove(final MinionService minions) {
            task.cancel();
            if (display.isValid()) {
                display.remove();
            }
            if (minions != null) {
                minions.removeSpike(mob);
            }
        }
    }

    private MinionService minionService() {
        return plugin.skills() == null ? null : plugin.skills().minions();
    }

    /**
     * 每個人、每種祭品同時只有一根（新的換掉舊的）。鍵是「玩家/祭品代號」；2026-10-28 照 PoE2「上限 1 根骨柱」吃 +x 上限（過剩）：
     * 多的那幾根鍵是「玩家/祭品代號#2」「#3」…，超過上限最舊的那根倒下。
     */
    private final java.util.Map<String, Spike> spikes = new java.util.HashMap<>();

    /** 「玩家/祭品代號#2」→「祭品代號」。 */
    private static String spikeSkill(final String keyAfterPrefix) {
        final int hash = keyAfterPrefix.indexOf('#');
        return hash < 0 ? keyAfterPrefix : keyAfterPrefix.substring(0, hash);
    }

    /**
     * 花魔力之前：祭柱放不放得出來。2026-10-30 照 PoE2（Skewer a Skeleton on a bone spike）：要 {@link #SPIKE_SEARCH} 格內有你的骷髏；
     * 這次之前用屍體，沒有屍體就獻祭任何一隻召喚物。
     */
    String spikeProblem(final Player player) {
        if (tw.exilecore.ascendancy.AscendancyEffects.offeringVictim(player, SPIKE_SEARCH) != null) {
            return null; // 2026-10-27 照 PoE2（昇華・不情願的獻祭）：撲殺範圍內的敵人也能當祭品的目標
        }
        final MinionService minions = minionService();
        return minions != null && minions.offeringSkeleton(player, player.getLocation(), SPIKE_SEARCH, false) != null
                ? null : "附近沒有你的骷髏（祭品要串一隻骷髏立骨柱）";
    }

    /** 祭柱的位置：串最近的一隻自己的骷髏（獻祭掉）；沒有回傳 null。 */
    private org.bukkit.Location spikeSpot(final Player player) {
        final MinionService minions = minionService();
        return minions == null ? null : minions.offeringSkeleton(player, player.getLocation(), SPIKE_SEARCH, true);
    }

    private void raiseSpike(final Player player, final SkillBuild build, final org.bukkit.Location at, final double radius, final int ticks,
                            final List<Modifier> mods, final double powerEvery, final double regenPercent, final int limit) {
        final String skillId = build.definition().id();
        // 2026-10-30：魂火祭這種增益給你自己的（沒有給召喚物的增益）：骨柱倒了增益跟著結束
        final boolean selfBuff = build.definition().warcry() != null && build.definition().warcry().minionBuff().isEmpty();
        final String baseKey = player.getUniqueId() + "/" + skillId;
        // 2026-10-28 照 PoE2：同一種祭品最多 limit 根（1 + 過剩的 +x 上限），滿了最舊的倒下；這次之前一律換掉唯一那根
        final List<String> standing = new java.util.ArrayList<>();
        for (int i = 1; i <= Math.max(1, limit) + 8; i++) {
            final String key = i == 1 ? baseKey : baseKey + "#" + i;
            if (spikes.containsKey(key)) {
                standing.add(key);
            }
        }
        while (standing.size() >= Math.max(1, limit)) {
            String oldestKey = standing.getFirst();
            for (final String key : standing) {
                if (spikes.get(key).start() < spikes.get(oldestKey).start()) {
                    oldestKey = key;
                }
            }
            standing.remove(oldestKey);
            final Spike old = spikes.remove(oldestKey);
            if (old != null) {
                old.remove(minionService());
            }
        }
        String free = baseKey;
        for (int i = 2; spikes.containsKey(free); i++) {
            free = baseKey + "#" + i;
        }
        final String key = free;
        final org.bukkit.Location base = at.clone();
        base.setYaw(0);
        base.setPitch(0);
        final org.bukkit.entity.BlockDisplay display = at.getWorld().spawn(base, org.bukkit.entity.BlockDisplay.class, d -> {
            d.setBlock(org.bukkit.Material.BONE_BLOCK.createBlockData());
            d.setPersistent(false);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-0.2f, 0f, -0.2f), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.4f, 2.2f, 0.4f), new org.joml.Quaternionf()));
        });
        at.getWorld().playSound(at, org.bukkit.Sound.BLOCK_BONE_BLOCK_PLACE, 1f, 0.6f);
        at.getWorld().spawnParticle(org.bukkit.Particle.SOUL, at.clone().add(0, 1, 0), 20, 0.3, 0.8, 0.3, 0.03);
        // 2026-10-30 照 PoE2 Offering Spike：骨柱本身是召喚物（骸骨祭生命 0.35 倍、其他 0.18 倍），骸骨祭的護盾也給它自己
        final MinionService minionsNow = minionService();
        final java.util.UUID spikeMob = minionsNow == null ? null : minionsNow.spike(player, build, at, ROLE_BONE.equals(skillId)
                ? MinionService.SPIKE_LIFE_BONE : MinionService.SPIKE_LIFE, ticks, mods);
        final long start = plugin.getServer().getCurrentTick();
        final long end = start + ticks;
        final long[] nextPower = {start + Math.round(powerEvery * 20)};
        final org.bukkit.scheduler.BukkitTask[] holder = new org.bukkit.scheduler.BukkitTask[1];
        // 2026-10-27：計時給 /ec perf 看（fx.warcry）
        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> plugin.perf().time("fx.warcry", () -> {
            final long now = plugin.getServer().getCurrentTick();
            final MinionService minions = minionService();
            final boolean broken = spikeMob != null && minions != null && !minions.spikeStanding(spikeMob);
            if (now >= end || broken || !player.isOnline() || !display.isValid() || !player.getWorld().equals(at.getWorld())) {
                final Spike mine = spikes.get(key);
                if (mine != null && mine.task() == holder[0]) {
                    spikes.remove(key);
                }
                holder[0].cancel();
                if (minions != null) {
                    minions.removeSpike(spikeMob);
                }
                if (broken && selfBuff && player.isOnline() && spikes.keySet().stream().noneMatch(k -> k.equals(baseKey) || k.startsWith(baseKey + "#"))) {
                    buffs.remove(player, skillId); // 2026-10-30 照 PoE2 Soul Offering：骨柱被打死，增益馬上結束
                }
                if (display.isValid()) {
                    at.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, at.clone().add(0, 1, 0), 10, 0.2, 0.6, 0.2, 0.02);
                    display.remove();
                }
                // 2026-10-24（祭星）：祭柱倒了，祭柱活了多久就回復多久
                if (regenPercent > 0 && plugin.skills() != null && player.isOnline()) {
                    plugin.skills().chargeEffects().offeringRegen(player, regenPercent, (int) ((now - start) / 20));
                }
                return;
            }
            if (powerEvery > 0 && now >= nextPower[0] && plugin.combat() != null && plugin.combat().charges() != null) {
                nextPower[0] += Math.round(powerEvery * 20); // 2026-10-24（祭星）：祭柱在的時候每 4 秒一顆銳星
                plugin.combat().charges().grant(player, "power", 1);
            }
            if (plugin.skills() != null && plugin.skills().minions() != null) {
                plugin.skills().minions().offerAt(player, at, radius, 25, mods);
            }
            // 地上一圈：看得到範圍
            for (int i = 0; i < 16; i++) {
                final double a = Math.PI * 2 * i / 16 + now * 0.05;
                tw.exilecore.skill.Fx.spawn(at.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME,
                        at.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0);
            }
        }), 1, 10);
        spikes.put(key, new Spike(display, holder[0], start, spikeMob));
        if (plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
            plugin.skills().treeLeftovers().offeringCreated(player); // 2026-10-27 照 PoE2 天賦樹：立祭品時回復生命
        }
    }

    /** 2026-10-27 照 PoE2 天賦樹（至少兩種不同的祭品）：這個人現在立著幾種祭品（每種同時只有一根）。 */
    int activeOfferings(final java.util.UUID player) {
        final String prefix = player + "/";
        final java.util.Set<String> kinds = new java.util.HashSet<>(); // 2026-10-28：同一種有好幾根（過剩）也只算一種
        for (final java.util.Map.Entry<String, Spike> entry : spikes.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().display().isValid()) {
                kinds.add(spikeSkill(entry.getKey().substring(prefix.length())));
            }
        }
        return kinds.size();
    }

    /** 2026-10-27 照 PoE2 祭脈（Kulemak's Dominion）：這個人現在立著的祭柱（祭品技能代號 → 位置）。 */
    java.util.Map<String, org.bukkit.Location> offeringSpots(final java.util.UUID player) {
        final String prefix = player + "/";
        final java.util.Map<String, org.bukkit.Location> spots = new java.util.HashMap<>();
        for (final java.util.Map.Entry<String, Spike> entry : spikes.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().display().isValid()) {
                spots.putIfAbsent(spikeSkill(entry.getKey().substring(prefix.length())), entry.getValue().display().getLocation()); // 2026-10-28：同一種好幾根時取一根
            }
        }
        return spots;
    }

    /** 關掉、重讀：祭柱全部收掉。 */
    void clearSpikes() {
        for (final Spike spike : spikes.values()) {
            spike.remove(minionService());
        }
        spikes.clear();
    }

    // ---- 2026-10-26 照 PoE2 0.5：強度、強化、守護、燃爆、回聲 ----

    /** 盾的攻擊（堅壁戰吼的強化只給這些；照 PoE2 的 Shield 攻擊）。 */
    static final java.util.Set<String> SHIELD_ATTACKS = java.util.Set.of("iron_wall_ram", "shield_drum", "earth_wall_strike");

    /** 2026-10-27 照 PoE2（Fortifying Cry 要 Armoured Shield）：強化盾的攻擊的戰吼要在副手裝有護甲的盾。 */
    static boolean needsShield(final SkillDefinition definition) {
        return definition.warcry() != null && definition.warcry().empower() != null && "shield".equals(definition.warcry().empower().kind());
    }

    /** 2026-10-27 照 PoE2（Arctic Howl：Freezes enemies that are Primed for Freeze）：強化要凍結的戰吼，吼的時候蓄勢待凍的敵人直接凍結。 */
    static boolean freezesPrimed(final WarcrySpec spec) {
        return spec != null && spec.empower() != null && "freeze".equals(spec.empower().condition());
    }

    /** 技能面板估強化次數用的強度（一群裡有一隻稀有怪）。 */
    static final double TYPICAL_POWER = 10;

    /** 2026-10-26：戰吼面板上的說明（強度、強化、守護、燃爆…，照 PoE2）。 */
    static List<String> describe(final SkillBuild build) {
        final WarcrySpec spec = build.definition().warcry();
        final List<String> lines = new java.util.ArrayList<>();
        if (spec == null || !spec.cry()) {
            return lines;
        }
        final int level = build.level();
        lines.add("<gray>算範圍內敵人的強度：普通 1、魔法 2、稀有 10、傳奇與 Boss 20（附近沒有敵人就不強化、不給守護）");
        final WarcrySpec.Empower empower = spec.empower();
        if (empower != null) {
            final String when = empower.condition() == null ? "" : "freeze".equals(empower.condition())
                    ? "有敵人被凍結（或打到凍結中的敵人）時，" : "有敵人被重暈（或打到重暈中的敵人）時，";
            if (empower.whileActive()) {
                lines.add(String.format(Locale.ROOT, "<gray>%s%.1f 秒內你的%s全部被強化：", when, spec.duration() * build.durationScale(), empower.kindText()));
            } else {
                lines.add(String.format(Locale.ROOT, "<gray>%s每 <white>%d</white> 點強度強化一次%s（最多算到 %d 點，至少 1 次%s），%.1f 秒內有效：",
                        when, empower.per(), empower.kindText(), empower.cap(), build.exerts() > 0 ? "，再多 " + build.exerts() + " 次" : "",
                        spec.duration() * build.durationScale()));
            }
            if (empower.gainFire().at(level) > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　獲得 %s%% 傷害為額外火焰傷害", ModifierText.number(empower.gainFire().at(level))));
            }
            if (empower.coldMax().at(level) > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　每 5 點強度附加 %s–%s 冰冷傷害（最多算到 %d 點強度）",
                        ModifierText.number(empower.coldMin().at(level)), ModifierText.number(empower.coldMax().at(level)), empower.cap()));
            }
            if (empower.armourBreak().at(level) > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　擊中時破甲 %s 點", ModifierText.number(empower.armourBreak().at(level))));
            }
            if (empower.extraTargets() > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　多打 %d 個敵人", empower.extraTargets()));
            }
            if (empower.aftershocks() > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　多一次餘震（0.5 秒後在同一個地方再砸一次）"));
            }
            if (empower.chilledGround()) {
                lines.add("<#f5b942>　猛擊留下 4 秒的冰緩地面");
            }
            if (empower.trigger() != null) {
                lines.add("<#f5b942>　出手時觸發盾波（算被強化的攻擊）");
            }
            if (empower.more().at(level) > 0) {
                lines.add(String.format(Locale.ROOT, "<#f5b942>　更多 %s%% 傷害", ModifierText.number(empower.more().at(level))));
            }
        }
        final WarcrySpec.Extras extras = spec.extras();
        if (extras.guardPer5().at(level) > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>每 5 點強度獲得 <white>%s</white> 點守護（最多算到 %d 點強度），%.0f 秒",
                    ModifierText.number(extras.guardPer5().at(level)), extras.guardCap(), extras.guardSeconds()));
        }
        if (extras.allyColdMax().at(level) > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>範圍內的隊友每 5 點強度附加 %s–%s 冰冷傷害",
                    ModifierText.number(extras.allyColdMin().at(level)), ModifierText.number(extras.allyColdMax().at(level))));
        }
        if (extras.combust() != null) {
            lines.add(String.format(Locale.ROOT, "<gray>範圍內的敵人動搖 %.0f 秒：這段時間死掉的話 0.2 秒後燃爆，打周圍的敵人火焰傷害（屍體每 1 點強度更多 10%%）",
                    spec.duration() * build.durationScale()));
        }
        if (extras.ragePerPower() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>每 1 點強度獲得 <white>%s</white> 點怒氣", ModifierText.number(extras.ragePerPower())));
        }
        if (extras.glory() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>要 <white>%d</white> 點榮耀才能用（點燃敵人時照牠的強度獲得榮耀），沒有冷卻", extras.glory()));
        }
        if (extras.endurancePct() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>用掉身上全部的磐星，每顆持續時間更多 <white>%s%%</white>", ModifierText.number(extras.endurancePct())));
        }
        if (extras.steps() != null) {
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（SupportMechanics.STEP_METRES 1.2 公尺 = 1.8 格）
            lines.add("<gray>持續期間每走 1.2 格觸發火山步（身邊一圈火焰猛擊）；近戰打擊打中時觸發火山噴發（三顆熔岩彈，0.4 秒一次）");
        }
        if (extras.knockback()) {
            lines.add("<gray>震退範圍內的敵人");
        }
        if (freezesPrimed(spec)) {
            lines.add("<gray>蓄勢待凍的敵人直接凍結");
        }
        if (needsShield(build.definition())) {
            lines.add("<gray>要在副手裝有護甲的盾");
        }
        lines.add(String.format(Locale.ROOT, "<gray>使用時間 %.1f 秒（只吃戰吼速度）", build.definition().castTime()));
        return lines;
    }

    /** 震嘯：每次回聲隔幾 tick（1.3 秒）。 */
    static final long ECHO_DELAY = 26;
    /** 焚獄戰吼：敵人死掉之後隔幾 tick 燃爆（200 毫秒）。 */
    static final long COMBUST_DELAY = 4;

    /** 一隻敵人的強度（PoE2：普通 1、魔法 2、稀有 10、傳奇與 Boss 20）；不是怪物 0。 */
    double power(final LivingEntity target) {
        if (target == null || target instanceof Player || plugin.monsters() == null) {
            return 0;
        }
        return plugin.monsters().data(target).map(Warcries::power).orElse(0.0);
    }

    static double power(final tw.exilecore.monster.MonsterData data) {
        if (data.boss()) {
            return WarcrySpec.POWER_UNIQUE;
        }
        return switch (data.rarity()) {
            case NORMAL -> WarcrySpec.POWER_NORMAL;
            case MAGIC -> WarcrySpec.POWER_MAGIC;
            case RARE -> WarcrySpec.POWER_RARE;
            case UNIQUE -> WarcrySpec.POWER_UNIQUE;
        };
    }

    /** 焚獄戰吼動搖的敵人：誰吼的、幾級、哪個技能、到哪個 tick。 */
    private record Destabilised(UUID owner, int level, String combust, long untilTick) {
    }

    private final java.util.Map<UUID, Destabilised> destabilised = new java.util.HashMap<>();

    /** 怪物死掉（SupportMechanics 呼叫）：被焚獄戰吼動搖的，200 毫秒後在屍體那裡燃爆（每 1 點強度更多 10% 傷害）。 */
    void onDeath(final LivingEntity dead) {
        final Destabilised mark = destabilised.remove(dead.getUniqueId());
        final long now = plugin.getServer().getCurrentTick();
        if (destabilised.size() > 2048) {
            destabilised.values().removeIf(each -> each.untilTick() < now);
        }
        if (mark == null || mark.untilTick() < now || plugin.skills() == null) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(mark.owner());
        final SkillDefinition sub = plugin.skills().config().skill(mark.combust());
        if (owner == null || sub == null || !owner.getWorld().equals(dead.getWorld())) {
            return;
        }
        final double corpsePower = power(dead);
        final org.bukkit.Location at = dead.getLocation().add(0, 0.3, 0);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline()) {
                return;
            }
            final PlayerState state = plugin.players().get(owner);
            SkillBuild build = SkillBuild.of(plugin.skills().config(), sub, mark.level(), state.level(), false, List.of(), state.weaponTags(),
                    plugin.players().sheet(owner));
            build = build.withProfile(build.profile().scaled(1 + 0.10 * corpsePower, "燃爆（屍體強度）"));
            plugin.skills().procCast(Origin.at(owner, at, SkillTargets.facing(owner)), build, null);
        }, COMBUST_DELAY);
    }

    /** 吼。回傳要顯示的提示（null = 沒有）。 */
    String shout(final Player player, final SkillRuntime runtime, final SkillBuild build) {
        return shout(player, runtime, build, 0);
    }

    /**
     * 吼一聲（echo：第幾次回聲，0 = 本人吼的）。2026-10-26 照 PoE2：
     * 數範圍內敵人的強度 → 決定強化幾次攻擊（沒有敵人不強化）、守護、怒氣；吼的傷害、動搖、震退；戰吼輔助的效果。
     */
    String shout(final Player player, final SkillRuntime runtime, final SkillBuild build, final int echo) {
        final WarcrySpec spec = build.definition().warcry();
        if (spec == null) {
            return null;
        }
        final long now = plugin.getServer().getCurrentTick();
        final PlayerState state = plugin.players().get(player);
        final String id = build.definition().id();
        final String name = build.definition().name();
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        final java.util.Set<String> tags = build.profile().tags();
        // 震嘯（PoE2 Echoing Cry）：回聲 2 次，每回聲一次範圍更多 100%（「範圍更少 50%」寫在輔助的 skill.area）
        final int echoes = (int) Math.round(Math.max(0, effective.flat(tw.exilecore.stats.SupportKeys.WARCRY_ECHOES, tags)));
        final double echoArea = 1.0 + echo;
        final double radius = baseRadius(spec, effective, tags) * build.areaScale() * Math.sqrt(echoArea); // 2026-10-27：＋焚獄戰吼品質的半徑
        // 2026-10-28 照 PoE2 Soul Offering 的品質：骨刺持續時間 +(0—1) 秒（加在基礎上，再乘持續時間倍率）
        int ticks = build.durationTicks(spec.duration() + Math.max(0, SkillZones.localFlat(build.profile(), tw.exilecore.stats.GemQualityKeys.DURATION_FLAT)));
        // 銀月庇（2026-10，PoE2 的月光賜福）：吼的時候用掉全部怒氣，每點怒氣增益多撐 rage_extend 秒
        int rageFed = 0;
        if (spec.rageExtend() > 0 && plugin.skills() != null && echo == 0) {
            rageFed = plugin.skills().resources().spendRage(player, RAGE_FEED_CAP);
            ticks += (int) Math.round(rageFed * spec.rageExtend() * 20);
        }
        // 熔魂嘯（PoE2 Ancestral Cry）：用掉全部磐星，每顆持續時間更多 50%
        int enduranceUsed = 0;
        if (spec.extras().endurancePct() > 0 && echo == 0 && plugin.combat() != null && plugin.combat().charges() != null) {
            final tw.exilecore.charge.ChargeService charges = plugin.combat().charges();
            while (charges.count(player, "endurance") > 0 && enduranceUsed < 20) {
                if (charges.spend(player, "endurance", effective, tags) <= 0) {
                    break;
                }
                enduranceUsed++;
            }
            // 2026-10-27 照 PoE2（熔魂嘯的品質）：每顆磐星另外增加 x% 持續時間
            final double perCharge = Math.max(0, effective.flat(tw.exilecore.stats.WarcryKeys.DURATION_PER_ENDURANCE, tags));
            ticks = (int) Math.round(ticks * (1 + enduranceUsed * spec.extras().endurancePct() / 100.0) * (1 + enduranceUsed * perCharge / 100.0));
        }
        final StringBuilder notice = new StringBuilder("<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(name)
                + (echo > 0 ? "<gray>（回聲 " + echo + "）" : ""));
        if (rageFed > 0) {
            notice.append(String.format(Locale.ROOT, "<gray>：獻上 <white>%d</white> 點怒氣，增益 <white>%.1f</white> 秒", rageFed, ticks / 20.0));
        }
        if (enduranceUsed > 0) {
            notice.append(String.format(Locale.ROOT, "<gray>：用掉 <white>%d</white> 顆磐星，持續 <white>%.1f</white> 秒", enduranceUsed, ticks / 20.0));
        }

        // 回復（舊寫法：只有神龕祝禱這種非原作的技能有）
        final double heal = spec.heal().at(build.level());
        if (heal > 0 && state.life() < state.maxLife()) {
            state.setLife(state.life() + state.maxLife() * heal / 100.0);
            plugin.players().display(player, state);
        }

        // 增益：自己（與範圍內的隊友：只有寫 allies 的）
        final List<Modifier> modifiers = build.warcryModifiers();
        // 2026-10：輔助加的那份（蓄嘯…）照輔助分開記：同一顆輔助掛在好幾個戰吼上只有一份（最後吼的那個刷新時間）
        for (final java.util.Map.Entry<String, List<Modifier>> grant : build.warcrySupportGrants().entrySet()) {
            buffs.apply(player, "warcry_support:" + grant.getKey(), grant.getValue().getFirst().source(), ticks, grant.getValue());
        }
        if (!build.warcryOwnModifiers().isEmpty()) {
            buffs.apply(player, id, name, ticks, build.warcryOwnModifiers());
        }
        // 2026-10-26 照 PoE2 天賦：戰旗也給受影響者 +x% 全元素抗性（自己與範圍內的隊友）
        final List<Modifier> bannerExtra = TreeMechanics.bannerExtras(effective, tags, name);
        if (!bannerExtra.isEmpty()) {
            buffs.apply(player, id + ".tree", name, ticks, bannerExtra);
            if (spec.allies()) {
                for (final Player ally : allies(player, radius)) {
                    buffs.apply(ally, id + ".tree", name, ticks, bannerExtra);
                }
            }
        }
        if (plugin.skills() != null && (spec.extras().steps() != null || spec.extras().eruption() != null)) {
            plugin.skills().supportMechanics().startKaom(player, spec, build.level(), now + ticks); // 熔魂嘯：火山步、火山噴發
        }
        if (!modifiers.isEmpty() && spec.allies()) {
            for (final Player ally : allies(player, radius)) {
                buffs.apply(ally, id, name, ticks, build.warcryOwnModifiers()); // 輔助加的「你得到」只給自己
                plugin.players().get(ally).notice("<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(name)
                        + "<gray>：" + tw.exilecore.item.ItemRenderer.escape(player.getName()) + " 的戰吼給了你增益", now + 60);
            }
        }

        // 祭柱（2026-10 PoE2 的祭品）：2026-10-30 照 PoE2 串一隻自己的骷髏立一根骨柱（骨柱是召喚物）；這次之前用掉一具屍體（沒有就獻祭一隻自己的召喚物）立一根祭柱，柱子附近的召喚物一直有增益
        // 2026-10-30 照 PoE2：魂火祭（增益給你、沒有給召喚物的）也立骨柱；這次之前只有給召喚物增益的祭品才立
        if (spec.spike() && plugin.skills() != null && plugin.skills().minions() != null) {
            final List<Modifier> minionMods = new java.util.ArrayList<>();
            // 2026-10-27 照 PoE2（昇華・不情願的獻祭）：撲殺範圍內的敵人當目標（撲殺牠，每點強度效果增加 1%），祭品改成影響你
            final boolean unwilling = tw.exilecore.ascendancy.AscendancyEffects.unwillingOffering(player);
            // 2026-10-30：先串骨柱用的那隻骷髏，再算骸舞多獻的（這次之前骸舞先拿，只有一隻骷髏時祭品就沒得串）
            final org.bukkit.Location spikeAt = unwilling ? null : spikeSpot(player);
            if (!unwilling && spikeAt == null) {
                buffs.remove(player, id);
                return "<red>附近沒有你的骷髏（祭品要串一隻骷髏立骨柱）";
            }
            // 2026-10-26（骸舞，PoE2 Danse Macabre）：多用掉一隻骷髏，有用到才有 30% 增益效果
            final double extraEffect = plugin.skills().supportMechanics().offeringExtraSkeleton(player, build);
            final org.bukkit.entity.LivingEntity victim = unwilling ? tw.exilecore.ascendancy.AscendancyEffects.offeringVictim(player, SPIKE_SEARCH) : null;
            final org.bukkit.Location victimAt = victim == null ? null : victim.getLocation();
            final double power = victim == null ? 0 : tw.exilecore.ascendancy.AscendancyEffects.cullForOffering(player, victim);
            for (final ScaledMod mod : spec.minionBuff()) {
                minionMods.add(mod.toModifier(build.level(), build.auraEffect() * (1 + extraEffect) * (1 + power / 100.0), name));
            }
            // 2026-10-28 照 PoE2 Bone Offering 的品質：護盾消失後 1 秒更少 x% 傷害（MinionService.hitTaken）
            final double shieldLess = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.SHIELD_LOST_LESS);
            if (shieldLess > 0) {
                minionMods.add(Modifier.flat(tw.exilecore.stats.GemQualityKeys.SHIELD_LOST_LESS, shieldLess, name));
            }
            if (unwilling) {
                final org.bukkit.Location at = victimAt != null ? victimAt : spikeSpot(player);
                if (at == null) {
                    return "<red>附近沒有屍體、召喚物，也沒有撲殺範圍內的敵人";
                }
                buffs.apply(player, id + ".unwilling", name, ticks, minionMods);
                at.getWorld().spawnParticle(org.bukkit.Particle.SCULK_SOUL, at.clone().add(0, 1, 0), 20, 0.4, 0.8, 0.4, 0.02);
                notice.append(String.format(Locale.ROOT, "<gray>：祭品的增益給你（%.0f 秒）", ticks / 20.0));
                if (power > 0) {
                    notice.append(String.format(Locale.ROOT, "<gray>，撲殺的敵人強度 %.0f：效果增加 %.0f%%", power, power));
                }
            } else {
                final org.bukkit.Location at = spikeAt;
                raiseSpike(player, build, at, radius, ticks, minionMods,
                        effective.flat(tw.exilecore.charge.ChargeService.SUP_OFFERING_POWER, tags),
                        effective.flat(tw.exilecore.charge.ChargeService.SUP_OFFERING_REGEN, tags),
                        1 + (build.definition().tags().contains(SkillLimits.TAG) ? SkillLimits.bonus(effective, tags) : 0)); // 2026-10-28：過剩
                notice.append(String.format(Locale.ROOT, "<gray>：立起祭柱（%.0f 秒，半徑 %.0f 格）", ticks / 20.0, radius));
                if (extraEffect > 0) {
                    notice.append("<gray>，多獻上一隻骷髏");
                }
            }
        } else if (!spec.minionBuff().isEmpty() && plugin.skills() != null && plugin.skills().minions() != null) {
            final List<Modifier> minionMods = new java.util.ArrayList<>();
            for (final ScaledMod mod : spec.minionBuff()) {
                minionMods.add(mod.toModifier(build.level(), build.auraEffect(), name));
            }
            minionMods.addAll(build.qualityGrants()); // 2026-10-27 照 PoE2（銀月庇的品質）：增益（你與狼群）另外獲得 x% 傷害為冰冷
            if (spec.recall()) { // 2026-10-18 召歸令：先把全部召喚物傳回身邊，再給增益（傳回來之後都在範圍內）
                final int recalled = plugin.skills().minions().recall(player);
                if (recalled == 0) {
                    return "<red>你現在沒有召喚物";
                }
                notice.append(String.format(Locale.ROOT, "<gray>：%d 隻召喚物回到你身邊", recalled));
            }
            // 2026-10-26：銀月庇照 PoE2 只給狼（minion_skills）
            final int count = plugin.skills().minions().offer(player, spec.recall() ? Math.max(radius, 12) : radius, ticks, minionMods,
                    spec.extras().minionSkills());
            notice.append(count > 0 ? String.format(Locale.ROOT, "<gray>：%d 隻召喚物得到增益", count)
                    : spec.extras().minionSkills().isEmpty() ? "<gray>：附近沒有你的召喚物" : "");
        }

        // 戰星：直接獲得（只給自己；滿了就把最舊的那顆換新）
        if (!spec.charges().isEmpty() && plugin.combat() != null && plugin.combat().charges() != null) {
            final tw.exilecore.charge.ChargeService charges = plugin.combat().charges();
            final List<String> gained = new java.util.ArrayList<>();
            for (final java.util.Map.Entry<String, Integer> entry : spec.charges().entrySet()) {
                charges.grant(player, entry.getKey(), entry.getValue());
                gained.add("<" + charges.colorOf(entry.getKey()) + ">" + tw.exilecore.item.ItemRenderer.escape(charges.nameOf(entry.getKey()))
                        + "</" + charges.colorOf(entry.getKey()) + ">" + (entry.getValue() > 1 ? "×" + entry.getValue() : ""));
            }
            notice.append("<gray>：獲得 ").append(String.join("<gray>、", gained));
        }

        // ---- 範圍內的敵人：強度、傷害、動搖、震退 ----
        final List<LivingEntity> enemies = targets.around(player.getLocation(), radius, player);
        double power = 0;
        for (final LivingEntity target : enemies) {
            power += power(target);
        }
        if (spec.cry()) {
            power *= Math.max(0, effective.apply(tw.exilecore.stats.SupportKeys.WARCRY_POWER, 1.0, tags)); // 震嘯：強度更少 50%
            power = Math.max(power, effective.flat(tw.exilecore.stats.KeystoneKeys.WARCRY_MIN_POWER, tags)); // 2026-10-26 照 PoE2 天賦樹：戰吼至少 x 強度
        }
        final double taken = spec.taken().at(build.level()) * build.auraEffect();
        final boolean shockwave = build.definition().dealsDamage() && plugin.combat() != null && plugin.combat().skillDamage() != null;
        final boolean knockback = spec.extras().knockback();
        int hit = 0;
        for (final LivingEntity target : enemies) {
            hit++;
            if (taken > 0) {
                plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff(id, player.getUniqueId(), now + ticks, taken / 100.0, false));
            }
            if (spec.taunt() && target instanceof Mob mob) {
                taunts.taunt(mob, player, ticks);
            }
            if (spec.extras().combust() != null) {
                destabilised.put(target.getUniqueId(), new Destabilised(player.getUniqueId(), build.level(), spec.extras().combust(), now + ticks));
            }
            final org.bukkit.util.Vector push = knockback ? knockback(player, target) : null;
            if (shockwave && target.isValid() && !target.isDead()) {
                plugin.combat().skillDamage().hit(player, target, build.profile(), push);
            } else if (push != null && target.isValid()) {
                target.setVelocity(target.getVelocity().add(push));
            }
            // 2026-10-27 照 PoE2（寒夜嗥 Arctic Howl）：蓄勢待凍的敵人直接凍結（吼的擊中本身不累積凍結）；這次之前沒有
            if (freezesPrimed(spec) && plugin.ailments() != null && target.isValid() && !target.isDead()) {
                plugin.ailments().freezeIfPrimed(target);
            }
        }
        if (shockwave && hit > 0) {
            notice.append(String.format(Locale.ROOT, "<gray>　打中 %d 隻", hit));
        }
        if (hit > 0 && (taken > 0 || spec.taunt())) {
            notice.append(String.format(Locale.ROOT, "<gray>　%d 隻敵人%s", hit, taken > 0 ? (spec.taunt() ? "受到的傷害增加、被嘲諷" : "受到的傷害增加") : "被嘲諷"));
        }
        if (spec.extras().combust() != null && hit > 0) {
            notice.append(String.format(Locale.ROOT, "<gray>　%d 隻敵人動搖（死掉時燃爆）", hit));
        }

        if (spec.cry()) {
            notice.append(String.format(Locale.ROOT, "<dark_gray>（強度 %s）", ModifierText.number(power)));
            // 守護（堅壁戰吼）：每 5 點強度 x 點，最多算到 20 點強度；有任何強度至少算一次
            final double guardPer5 = spec.extras().guardPer5().at(build.level());
            if (guardPer5 > 0 && power > 0) {
                // 2026-10-27 照 PoE2（堅壁戰吼的品質）：守護更多 x%
                final double guard = guardPer5 * per5(power, spec.extras().guardCap())
                        * (1 + Math.max(0, effective.flat(tw.exilecore.stats.WarcryKeys.GUARD_MORE, tags)) / 100.0);
                state.addGuard(guard, now + Math.round(spec.extras().guardSeconds() * 20));
                plugin.players().display(player, state);
                notice.append(String.format(Locale.ROOT, "<gray>：獲得 <white>%d</white> 點守護（%.0f 秒）", Math.round(guard), spec.extras().guardSeconds()));
            }
            // 怒氣（裂甲熊咆：每 1 點強度 2 點；聚怒嘯：每 5 點強度 4 點）
            final double perFive = effective.flat(tw.exilecore.stats.SupportKeys.WARCRY_RAGE_PER_5_POWER, tags);
            // 2026-10-27 照 PoE2（裂甲熊咆的品質）：每 1 點強度多 x 點怒氣（只加在本來就給怒氣的戰吼上）
            final double perPower = spec.extras().ragePerPower() > 0
                    ? spec.extras().ragePerPower() + Math.max(0, effective.flat(tw.exilecore.stats.WarcryKeys.RAGE_PER_POWER, tags)) : 0;
            final double rage = power * perPower + (perFive > 0 ? Math.floor(power / 5 + 1e-9) * perFive : 0);
            if (rage > 0 && plugin.skills() != null) {
                final int gained = plugin.skills().resources().gainRage(player, rage);
                if (gained > 0) {
                    notice.append(String.format(Locale.ROOT, "<gray>：獲得 <white>%d</white> 點怒氣", gained));
                }
            }
            // 戰吼輔助：碎甲吼、契血嘯、碎晶嘯（SupportMechanics）
            int bonus = 0;
            if (plugin.skills() != null) {
                bonus = plugin.skills().supportMechanics().onWarcry(player, build, enemies, radius, notice);
                plugin.skills().leftovers().onWarcry(player, radius); // 2026-10-28 照 PoE2：戰吼讓土牆崩碎、鍛錘震碎成裂地
            }
            // 2026-10-26 照 PoE2 天賦樹：戰吼時回復生命魔力、解除點燃、衰弱與暴擊弱點、機率多強化 3 次（KeystoneEffects）
            bonus += tw.exilecore.combat.KeystoneEffects.onWarcry(plugin, player, effective, tags, enemies, notice);
            // 強化
            if (spec.empowers()) {
                final int finalBonus = bonus;
                final double finalPower = power;
                final int finalTicks = ticks;
                final String condition = spec.empower() == null ? null : spec.empower().condition();
                if (condition == null) {
                    empower(player, runtime, build, power, ticks, bonus, echo, notice);
                } else if (conditionMet(condition, enemies)) {
                    empower(player, runtime, build, power, ticks, bonus, echo, notice);
                } else if (!enemies.isEmpty()) {
                    // 重暈、凍結是同一下打出來的：下一 tick 再看一次
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && conditionMet(condition, enemies)) {
                            final StringBuilder later = new StringBuilder("<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(name));
                            empower(player, runtime, build, finalPower, finalTicks, finalBonus, echo, later);
                            plugin.players().get(player).notice(later.toString(), plugin.getServer().getCurrentTick() + 60);
                            plugin.players().display(player, plugin.players().get(player));
                        }
                    }, 1);
                    notice.append(condition.equals("freeze") ? "<gray>：沒有凍結的敵人，不強化" : "<gray>：沒有重暈的敵人，不強化");
                } else {
                    notice.append("<gray>：附近沒有敵人，不強化");
                }
            }
        } else if (build.exerts() > 0) {
            // 舊寫法（神龕祝禱這種非原作的技能）：固定幾次、更多 x%
            empower(player, runtime, build, 1, ticks, 0, echo, notice);
        }

        visuals.warcry(build.definition().visual(), player, radius);
        // 震嘯：1.3 秒後回聲（再吼一次，不花魔力、不進冷卻）
        if (echo < echoes) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !player.isDead()) {
                    final String text = shout(player, runtime, build, echo + 1);
                    if (text != null) {
                        plugin.players().get(player).notice(text, plugin.getServer().getCurrentTick() + 60);
                        plugin.players().display(player, plugin.players().get(player));
                    }
                }
            }, ECHO_DELAY);
        }
        return notice.toString();
    }

    /** 每 5 點強度算一次（最多算到 cap 點；有任何強度至少一次）。 */
    static int per5(final double power, final int cap) {
        if (power <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(Math.min(power, cap) / 5 + 1e-9));
    }

    /** 重暈、凍結的條件：範圍內有一隻在重暈中、凍結中。 */
    private boolean conditionMet(final String condition, final List<LivingEntity> enemies) {
        if (plugin.ailments() == null) {
            return false;
        }
        for (final LivingEntity target : enemies) {
            if (!target.isValid()) {
                continue;
            }
            if ("freeze".equals(condition) ? plugin.ailments().isFrozen(target) : plugin.ailments().heavyStunned(target)) {
                return true;
            }
        }
        return false;
    }

    /** 給強化（照強度算次數）與寒夜嗥給隊友的冰冷傷害。 */
    private void empower(final Player player, final SkillRuntime runtime, final SkillBuild build, final double power, final int ticks,
                         final int bonus, final int echo, final StringBuilder notice) {
        final WarcrySpec spec = build.definition().warcry();
        final long now = plugin.getServer().getCurrentTick();
        final String id = build.definition().id();
        final String name = build.definition().name();
        if (spec.empower() == null) {
            // 舊寫法：固定次數、更多 x%（不吃戰吼效果）
            final double more = spec.exertMore().at(build.level());
            runtime.exert = new SkillRuntime.Exert(build.exerts(), more, now + ticks);
            notice.append(String.format(Locale.ROOT, "<gray>：接下來 <white>%d</white> 次攻擊更多 <white>%d%%</white> 傷害", build.exerts(), Math.round(more)));
            return;
        }
        int count = spec.empowersFor(power);
        if (count <= 0) {
            notice.append("<gray>：附近沒有敵人，不強化");
            return;
        }
        if (count < WarcrySpec.Empower.ALL) {
            count += build.exerts() + bonus; // 「+x 次強化」（強勢戰吼、天賦）、碎晶嘯
        }
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        final double tireless = effective.flat(tw.exilecore.stats.SupportKeys.WARCRY_TIRELESS, build.profile().tags());
        final boolean cull = StatKeys.flag(effective, tw.exilecore.stats.SupportKeys.FLAG_EMPOWERED_CULL);
        final SkillRuntime.Empowered old = echo > 0 ? runtime.empowered.get(id) : null;
        final int left = old != null && old.active(now) && count < WarcrySpec.Empower.ALL ? Math.min(WarcrySpec.Empower.ALL - 1, old.left() + count) : count;
        runtime.empowered.put(id, new SkillRuntime.Empowered(name, left, now + ticks, spec.empower(), build.level(), power, tireless, cull,
                empoweredExtra(effective, build.profile().tags(), name),
                effective.flat(tw.exilecore.stats.WarcryKeys.AFTERSHOCK_MORE, build.profile().tags()),
                effective.flat(tw.exilecore.stats.WarcryKeys.TRIGGER_RADIUS_FLAT, build.profile().tags())));
        notice.append(count >= WarcrySpec.Empower.ALL
                ? String.format(Locale.ROOT, "<gray>：%.0f 秒內的%s都被強化", ticks / 20.0, spec.empower().kindText())
                : String.format(Locale.ROOT, "<gray>：強化接下來 <white>%d</white> 次%s", left, spec.empower().kindText()));
        // 寒夜嗥：隊友每 5 點強度附加冰冷傷害（最多算到強化的上限）
        final double allyMin = spec.extras().allyColdMin().at(build.level());
        final double allyMax = spec.extras().allyColdMax().at(build.level());
        if (allyMax > 0) {
            final int times = per5(power, spec.empower().cap());
            final List<Modifier> cold = new java.util.ArrayList<>(List.of(
                    new Modifier(StatKeys.DAMAGE_ADDED_MIN, tw.exilecore.stats.ModType.FLAT, allyMin * times, java.util.Set.of(tw.exilecore.stats.Tags.COLD), name),
                    new Modifier(StatKeys.DAMAGE_ADDED_MAX, tw.exilecore.stats.ModType.FLAT, allyMax * times, java.util.Set.of(tw.exilecore.stats.Tags.COLD), name)));
            // 2026-10-27 照 PoE2（寒夜嗥的品質）：給隊友的增益另外增加 x% 凍結累積
            final double freeze = effective.flat(tw.exilecore.stats.WarcryKeys.ALLY_FREEZE_BUILDUP, build.profile().tags());
            if (freeze > 0) {
                cold.add(Modifier.increased(StatKeys.FREEZE_BUILDUP, freeze / 100.0, name));
            }
            int given = 0;
            for (final Player ally : allies(player, baseRadius(spec, effective, build.profile().tags()) * build.areaScale())) {
                buffs.apply(ally, id, name, ticks, cold);
                given++;
            }
            if (given > 0) {
                notice.append(String.format(Locale.ROOT, "<gray>，%d 位隊友附加冰冷傷害", given));
            }
        }
    }

    /**
     * 2026-10-27 照 PoE2：被這個戰吼強化的攻擊另外加的修飾器——焚獄戰吼品質的「強化攻擊獲得 x% 傷害為額外火焰」、
     * 裂甲熊咆插槽的「被插槽戰吼強化的攻擊增加 x% 傷害」（WarcryKeys）。
     */
    static List<Modifier> empoweredExtra(final tw.exilecore.stats.StatSheet effective, final java.util.Set<String> tags, final String name) {
        final List<Modifier> list = new java.util.ArrayList<>();
        final double fire = effective.flat(tw.exilecore.stats.WarcryKeys.EMPOWER_GAIN_FIRE, tags);
        if (fire > 0) {
            list.add(Modifier.flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.FIRE), fire, name + "強化（品質）"));
        }
        final double damage = effective.flat(tw.exilecore.stats.WarcryKeys.EMPOWERED_DAMAGE, tags);
        if (damage > 0) {
            list.add(Modifier.increased(StatKeys.DAMAGE, damage / 100.0, name + "強化（裂甲熊咆插槽）"));
        }
        return List.copyOf(list);
    }

    /** 2026-10-27 照 PoE2（焚獄戰吼的品質：戰吼半徑 +x 格）：戰吼的基礎半徑（還沒乘範圍倍率）。 */
    static double baseRadius(final WarcrySpec spec, final tw.exilecore.stats.StatSheet effective, final java.util.Set<String> tags) {
        return spec.radius() + tw.exilecore.core.Units.metres(Math.max(0, effective.flat(tw.exilecore.stats.WarcryKeys.RADIUS_FLAT, tags))); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
    }

    /** 範圍內同一個世界的隊友（不含自己）。 */
    private List<Player> allies(final Player player, final double radius) {
        final List<Player> list = new java.util.ArrayList<>();
        if (plugin.party() == null) {
            return list;
        }
        for (final UUID member : plugin.party().groupMembers(player.getUniqueId())) {
            final Player ally = member.equals(player.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
            if (ally != null && ally.getWorld().equals(player.getWorld()) && ally.getLocation().distanceSquared(player.getLocation()) <= radius * radius) {
                list.add(ally);
            }
        }
        return list;
    }

    /** 往外推一下（PoE2 的戰吼震退）。 */
    private static org.bukkit.util.Vector knockback(final Player player, final LivingEntity target) {
        final org.bukkit.util.Vector away = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-6) {
            away.setX(1);
        }
        return away.normalize().multiply(0.6).setY(0.25);
    }
}
