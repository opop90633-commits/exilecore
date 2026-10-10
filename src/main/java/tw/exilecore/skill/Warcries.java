package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 戰吼：瞬間吼一聲——回復生命、自己（與範圍內的隊友）得到幾秒增益、接下來幾次攻擊被強化、直接獲得戰星，
 * 範圍內的敵人受到的傷害增加（誰打都算，{@link Debuffs}）並被嘲諷過來打你。
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

    /** 祭柱找屍體、獻祭召喚物的範圍（格）。 */
    static final double SPIKE_SEARCH = 12;

    /** 一根祭柱。 */
    private record Spike(org.bukkit.entity.BlockDisplay display, org.bukkit.scheduler.BukkitTask task) {
        void remove() {
            task.cancel();
            if (display.isValid()) {
                display.remove();
            }
        }
    }

    /** 每個人、每種祭品同時只有一根（新的換掉舊的）。 */
    private final java.util.Map<String, Spike> spikes = new java.util.HashMap<>();

    /** 花魔力之前：祭柱放不放得出來（附近要有屍體或自己的召喚物）。 */
    String spikeProblem(final Player player) {
        final org.bukkit.Location at = player.getLocation();
        if (plugin.combat() != null && plugin.combat().corpses().nearest(at.getWorld().getUID(), at.getX(), at.getZ(), SPIKE_SEARCH,
                plugin.getServer().getCurrentTick()) != null) {
            return null;
        }
        return plugin.skills() != null && plugin.skills().minions() != null && plugin.skills().minions().canSacrifice(player, at, SPIKE_SEARCH)
                ? null : "附近沒有屍體，也沒有召喚物可以獻祭";
    }

    /** 祭柱的位置：最近的屍體（用掉），沒有就獻祭一隻最舊的召喚物。 */
    private org.bukkit.Location spikeSpot(final Player player) {
        final org.bukkit.Location at = player.getLocation();
        if (plugin.combat() != null) {
            final tw.exilecore.combat.Corpses.Corpse corpse = plugin.combat().corpses().nearest(at.getWorld().getUID(), at.getX(), at.getZ(),
                    SPIKE_SEARCH, plugin.getServer().getCurrentTick());
            if (corpse != null) {
                plugin.combat().corpses().remove(corpse);
                return new org.bukkit.Location(at.getWorld(), corpse.x(), corpse.y(), corpse.z());
            }
        }
        return plugin.skills().minions().sacrifice(player, at, SPIKE_SEARCH);
    }

    private void raiseSpike(final Player player, final String skillId, final org.bukkit.Location at, final double radius, final int ticks,
                            final List<Modifier> mods) {
        final String key = player.getUniqueId() + "/" + skillId;
        final Spike old = spikes.remove(key);
        if (old != null) {
            old.remove();
        }
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
        final long end = plugin.getServer().getCurrentTick() + ticks;
        final org.bukkit.scheduler.BukkitTask[] holder = new org.bukkit.scheduler.BukkitTask[1];
        holder[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final long now = plugin.getServer().getCurrentTick();
            if (now >= end || !player.isOnline() || !display.isValid() || !player.getWorld().equals(at.getWorld())) {
                final Spike mine = spikes.get(key);
                if (mine != null && mine.task() == holder[0]) {
                    spikes.remove(key);
                }
                holder[0].cancel();
                if (display.isValid()) {
                    at.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, at.clone().add(0, 1, 0), 10, 0.2, 0.6, 0.2, 0.02);
                    display.remove();
                }
                return;
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
        }, 1, 10);
        spikes.put(key, new Spike(display, holder[0]));
    }

    /** 關掉、重讀：祭柱全部收掉。 */
    void clearSpikes() {
        for (final Spike spike : spikes.values()) {
            spike.remove();
        }
        spikes.clear();
    }

    /** 吼。回傳要顯示的提示（null = 沒有）。 */
    String shout(final Player player, final SkillRuntime runtime, final SkillBuild build) {
        final WarcrySpec spec = build.definition().warcry();
        if (spec == null) {
            return null;
        }
        final long now = plugin.getServer().getCurrentTick();
        final PlayerState state = plugin.players().get(player);
        final String id = build.definition().id();
        final String name = build.definition().name();
        final double radius = spec.radius() * build.areaScale();
        final int ticks = build.durationTicks(spec.duration());
        final StringBuilder notice = new StringBuilder("<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(name));

        // 回復
        final double heal = spec.heal().at(build.level());
        if (heal > 0 && state.life() < state.maxLife()) {
            state.setLife(state.life() + state.maxLife() * heal / 100.0);
            plugin.players().display(player, state);
        }

        // 增益：自己與範圍內的隊友
        final List<Modifier> modifiers = build.warcryModifiers();
        if (!modifiers.isEmpty()) {
            buffs.apply(player, id, name, ticks, modifiers);
            if (spec.allies() && plugin.party() != null) {
                for (final UUID member : plugin.party().groupMembers(player.getUniqueId())) {
                    final Player ally = member.equals(player.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
                    if (ally != null && ally.getWorld().equals(player.getWorld())
                            && ally.getLocation().distanceSquared(player.getLocation()) <= radius * radius) {
                        buffs.apply(ally, id, name, ticks, modifiers);
                        plugin.players().get(ally).notice("<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(name)
                                + "<gray>：" + tw.exilecore.item.ItemRenderer.escape(player.getName()) + " 的戰吼給了你增益", now + 60);
                    }
                }
            }
        }

        // 祭柱（2026-10 PoE2 的祭品）：用掉一具屍體（沒有就獻祭一隻自己的召喚物）立一根祭柱，柱子附近的召喚物一直有增益
        if (spec.spike() && !spec.minionBuff().isEmpty() && plugin.skills() != null && plugin.skills().minions() != null) {
            final List<Modifier> minionMods = new java.util.ArrayList<>();
            for (final ScaledMod mod : spec.minionBuff()) {
                minionMods.add(mod.toModifier(build.level(), build.auraEffect(), name));
            }
            final org.bukkit.Location at = spikeSpot(player);
            if (at == null) {
                return "<red>附近沒有屍體，也沒有召喚物可以獻祭";
            }
            raiseSpike(player, id, at, radius, ticks, minionMods);
            notice.append(String.format(Locale.ROOT, "<gray>：立起祭柱（%.0f 秒，半徑 %.0f 格）", ticks / 20.0, radius));
        } else if (!spec.minionBuff().isEmpty() && plugin.skills() != null && plugin.skills().minions() != null) {
            final List<Modifier> minionMods = new java.util.ArrayList<>();
            for (final ScaledMod mod : spec.minionBuff()) {
                minionMods.add(mod.toModifier(build.level(), build.auraEffect(), name));
            }
            final int count = plugin.skills().minions().offer(player, radius, ticks, minionMods);
            notice.append(count > 0 ? String.format(Locale.ROOT, "<gray>：%d 隻召喚物得到增益", count) : "<gray>：附近沒有你的召喚物");
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

        // 強化：接下來幾次攻擊更痛
        if (build.exerts() > 0) {
            final double more = spec.exertMore().at(build.level()) * build.auraEffect(); // 2026-10：戰吼效果
            runtime.exert = new SkillRuntime.Exert(build.exerts(), more, now + ticks);
            notice.append(String.format(Locale.ROOT, "<gray>：接下來 <white>%d</white> 次攻擊更多 <white>%d%%</white> 傷害", build.exerts(), Math.round(more)));
        }

        // 敵人：受到的傷害增加、嘲諷
        final double taken = spec.taken().at(build.level()) * build.auraEffect();
        int hit = 0;
        for (final LivingEntity target : targets.around(player.getLocation(), radius, player)) {
            hit++;
            if (taken > 0) {
                plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff(id, player.getUniqueId(), now + ticks, taken / 100.0, false));
            }
            if (spec.taunt() && target instanceof Mob mob) {
                taunts.taunt(mob, player, ticks);
            }
        }
        if (hit > 0 && (taken > 0 || spec.taunt())) {
            notice.append(String.format(Locale.ROOT, "<gray>　%d 隻敵人%s", hit, taken > 0 ? (spec.taunt() ? "受到的傷害增加、被嘲諷" : "受到的傷害增加") : "被嘲諷"));
        }
        visuals.warcry(build.definition().visual(), player, radius);
        return notice.toString();
    }
}
