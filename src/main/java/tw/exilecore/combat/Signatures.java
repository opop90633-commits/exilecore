package tw.exilecore.combat;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 昇華的招牌天賦（2026-10）：每個昇華一個只有它有的能力（說明在 {@link tw.exilecore.stats.SignatureText}）。
 * 傷害管道（{@link CombatListener}）每一下問這裡：玩家打出去的倍率、玩家挨打的倍率，打完再通知（暴擊、閃避、擊殺）。
 * 狀態（背水的冷卻、虛步、獻祭的增益、移動中、身邊幾個隊友）只在記憶體，下線就清掉。
 */
public final class Signatures {

    /** 多久更新一次「移動中」與「身邊幾個隊友」（tick）。 */
    public static final long PERIOD = 10;

    private static final double LAST_STAND_AT = 0.35;
    private static final long LAST_STAND_TICKS = 8 * 20;
    private static final long LAST_STAND_COOLDOWN = 30 * 20;
    private static final long VOID_STEP_TICKS = 4 * 20;
    private static final long SACRIFICE_TICKS = 5 * 20;
    private static final long THUNDER_SOUL_GAP = 5;
    private static final double LEGION_RANGE = 12;
    private static final int LEGION_MAX = 5;

    private final ExileCore plugin;
    private final Map<UUID, Long> lastStandUntil = new HashMap<>();
    private final Map<UUID, Long> lastStandReady = new HashMap<>();
    private final Map<UUID, Long> voidStepUntil = new HashMap<>();
    private final Map<UUID, Long> sacrificeUntil = new HashMap<>();
    private final Map<UUID, Long> thunderSoulAt = new HashMap<>();
    private final Map<UUID, Location> lastSeen = new HashMap<>();
    private final Map<UUID, Boolean> moving = new HashMap<>();
    private final Map<UUID, Integer> allies = new HashMap<>();

    public Signatures(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static boolean has(final StatSheet sheet, final String flag) {
        return sheet != null && StatKeys.flag(sheet, flag);
    }

    /** 每 {@link #PERIOD} tick：誰在移動、誰身邊有幾個隊友（只算有這兩個天賦的人）。 */
    public void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final UUID id = player.getUniqueId();
            final PlayerState state = plugin.players().get(player);
            if (state == null || !state.hasCharacter()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            if (has(sheet, StatKeys.SIG_WINDWALK)) {
                final Location at = player.getLocation();
                final Location before = lastSeen.put(id, at);
                moving.put(id, before != null && before.getWorld() == at.getWorld()
                        && Math.hypot(before.getX() - at.getX(), before.getZ() - at.getZ()) > 0.6);
            } else {
                lastSeen.remove(id);
                moving.remove(id);
            }
            if (has(sheet, StatKeys.SIG_LEGION)) {
                allies.put(id, countAllies(player));
            } else {
                allies.remove(id);
            }
        }
        lastStandUntil.values().removeIf(until -> until < now);
        lastStandReady.values().removeIf(ready -> ready <= now); // 2026-10-18：下線不清了，冷卻過了在這裡清
        voidStepUntil.values().removeIf(until -> until < now);
        sacrificeUntil.values().removeIf(until -> until < now);
    }

    private int countAllies(final Player player) {
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        final java.util.Set<UUID> party = new java.util.HashSet<>();
        if (plugin.party() != null) {
            party.addAll(plugin.party().groupMembers(player.getUniqueId()));
        }
        int count = 0;
        for (final Entity entity : player.getNearbyEntities(LEGION_RANGE, 6, LEGION_RANGE)) {
            if (entity instanceof Player other) {
                if (party.contains(other.getUniqueId()) && !other.isDead()
                        && (other.getGameMode() == GameMode.SURVIVAL || other.getGameMode() == GameMode.ADVENTURE)) {
                    count++;
                }
            } else if (entity instanceof LivingEntity living && minions != null && minions.isMinion(living)) {
                final Player owner = minions.owner(living);
                if (owner != null && owner.getUniqueId().equals(player.getUniqueId())) {
                    count++;
                }
            }
            if (count >= LEGION_MAX) {
                break;
            }
        }
        return count;
    }

    /** 玩家自己打出去的這一下乘多少（召喚物打的不算）。 */
    public double outgoing(final Player attacker, final StatSheet sheet, final SkillProfile skill, final LivingEntity target,
                           final MonsterData targetMonster) {
        if (sheet == null) {
            return 1.0;
        }
        final UUID id = attacker.getUniqueId();
        final long now = now();
        final PlayerState state = plugin.players().get(attacker);
        final AilmentService ailments = plugin.ailments();
        double more = 1.0;
        if (has(sheet, StatKeys.SIG_BLOOD_RAGE) && state != null && state.maxLife() > 0) {
            final double missing = Math.max(0, Math.min(1, 1 - state.life() / state.maxLife()));
            more *= 1 + 0.5 * missing;
        }
        if (has(sheet, StatKeys.SIG_FAR_SHOT) && skill.tags().contains(Tags.PROJECTILE)
                && attacker.getWorld() == target.getWorld()) {
            final double distance = attacker.getLocation().distance(target.getLocation());
            more *= 1 + Math.min(0.4, Math.max(0, distance - 5) * 0.02);
        }
        if (has(sheet, StatKeys.SIG_WINDWALK) && moving.getOrDefault(id, false)) {
            more *= 1.10;
        }
        if (ailments != null) {
            if (has(sheet, StatKeys.SIG_WILDFIRE) && ailments.has(target, AilmentType.IGNITE)) {
                more *= 1.30;
            }
            if (has(sheet, StatKeys.SIG_WINTER_TOMB)) {
                if (ailments.isFrozen(target)) {
                    more *= 1.50;
                } else if (ailments.has(target, AilmentType.CHILL)) {
                    more *= 1.25;
                }
            }
            if (has(sheet, StatKeys.SIG_PLAGUE_LORD)) {
                more *= 1 + Math.min(0.30, ailments.poisonStacks(target) * 0.06);
            }
        }
        if (has(sheet, StatKeys.SIG_BLOOD_FEAST) && skill.tags().contains(Tags.SPELL) && state != null
                && state.maxLife() > 0 && state.life() >= state.maxLife() * 0.9) {
            more *= 1.15;
        }
        if (has(sheet, StatKeys.SIG_VERDICT)) {
            final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null && max.getValue() > 0 && target.getHealth() < max.getValue() * 0.5) {
                more *= 1.35;
            }
        }
        if (has(sheet, StatKeys.SIG_LEGION)) {
            more *= 1 + 0.06 * allies.getOrDefault(id, 0);
        }
        if (has(sheet, StatKeys.SIG_VOID_STEP) && voidStepUntil.getOrDefault(id, 0L) >= now
                && !skill.tags().contains(Tags.TOTEM_HIT)) { // 2026-10-21：虛步只給你自己打的那一下
            more *= 1.60; // 2026-10-17：這裡只看，真的打中才用掉（hitLanded）——以前被閃掉、擋掉也用掉
        }
        if (has(sheet, StatKeys.SIG_GODSLAYER) && targetMonster != null) {
            if (targetMonster.boss()) {
                more *= 1.35;
            } else if (targetMonster.rarity() == MonsterRarity.RARE || targetMonster.rarity() == MonsterRarity.UNIQUE) {
                more *= 1.25;
            }
        }
        if (has(sheet, StatKeys.SIG_SACRIFICE) && sacrificeUntil.getOrDefault(id, 0L) >= now) {
            more *= 1.20;
        }
        return more;
    }

    /** 玩家挨的這一下乘多少（背水、御風、軍陣）。 */
    public double incoming(final Player defender, final PlayerState state, final StatSheet sheet) {
        if (sheet == null || state == null) {
            return 1.0;
        }
        final UUID id = defender.getUniqueId();
        final long now = now();
        double less = 1.0;
        if (has(sheet, StatKeys.SIG_LAST_STAND)) {
            // 2026-10-17：這裡只看（觸發這一下也減半），真的被打中才啟動、進冷卻（hitTaken）——以前被閃掉的攻擊也會啟動
            if (lastStandUntil.getOrDefault(id, 0L) >= now || lastStandWouldTrigger(id, state, now)) {
                less *= 0.5;
            }
        }
        if (has(sheet, StatKeys.SIG_WINDWALK) && moving.getOrDefault(id, false)) {
            less *= 0.80;
        }
        if (has(sheet, StatKeys.SIG_LEGION)) {
            less *= 1 - 0.04 * allies.getOrDefault(id, 0);
        }
        return less;
    }

    private boolean lastStandWouldTrigger(final UUID id, final PlayerState state, final long now) {
        return lastStandUntil.getOrDefault(id, 0L) < now && lastStandReady.getOrDefault(id, 0L) <= now
                && state.maxLife() > 0 && state.life() < state.maxLife() * LAST_STAND_AT;
    }

    /** 玩家真的被打中（沒閃掉、沒擋掉）：背水這時才啟動。 */
    public void hitTaken(final Player defender, final PlayerState state, final StatSheet sheet) {
        if (sheet == null || state == null || !has(sheet, StatKeys.SIG_LAST_STAND)) {
            return;
        }
        final UUID id = defender.getUniqueId();
        final long now = now();
        if (lastStandWouldTrigger(id, state, now)) {
            lastStandUntil.put(id, now + LAST_STAND_TICKS);
            lastStandReady.put(id, now + LAST_STAND_COOLDOWN);
            defender.getWorld().playSound(defender.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 0.6f);
            defender.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, defender.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
            defender.sendActionBar(plugin.text().parse("<#d4a24c><bold>背水</bold> <gray>8 秒內受到的傷害減半"));
        }
    }

    /** 玩家真的打中（沒被閃掉、擋掉）：虛步的「下一下」這時才用掉。 */
    public void hitLanded(final Player attacker, final StatSheet sheet, final LivingEntity target) {
        if (sheet == null || !has(sheet, StatKeys.SIG_VOID_STEP)) {
            return;
        }
        final UUID id = attacker.getUniqueId();
        if (voidStepUntil.getOrDefault(id, 0L) >= now()) {
            voidStepUntil.remove(id); // 只有下一下
            target.getWorld().spawnParticle(Particle.REVERSE_PORTAL, target.getLocation().add(0, 1, 0), 18, 0.3, 0.5, 0.3, 0.05);
        }
    }

    /** 玩家閃掉、擋掉一下（虛步）。 */
    public void avoided(final Player defender, final StatSheet sheet) {
        if (has(sheet, StatKeys.SIG_VOID_STEP)) {
            voidStepUntil.put(defender.getUniqueId(), now() + VOID_STEP_TICKS);
        }
    }

    /** 玩家自己打中之後（雷魂、血宴）。 */
    public void dealt(final Player attacker, final StatSheet sheet, final SkillProfile skill, final HitResult result) {
        if (sheet == null) {
            return;
        }
        final PlayerState state = plugin.players().get(attacker);
        if (state == null || !state.hasCharacter() || state.isDead()) {
            return;
        }
        boolean changed = false;
        final long now = now();
        if (result.crit() && has(sheet, StatKeys.SIG_THUNDER_SOUL)
                && now - thunderSoulAt.getOrDefault(attacker.getUniqueId(), -100L) >= THUNDER_SOUL_GAP) {
            thunderSoulAt.put(attacker.getUniqueId(), now);
            state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * 0.03));
            state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield() + state.maxEnergyShield() * 0.03));
            changed = true;
        }
        if (has(sheet, StatKeys.SIG_BLOOD_FEAST) && skill.tags().contains(Tags.SPELL) && state.life() < state.maxLife()) {
            final double heal = Math.min(result.total() * 0.03, state.maxLife() * 0.02);
            if (heal > 0) {
                state.setLife(Math.min(state.maxLife(), state.life() + heal));
                changed = true;
            }
        }
        if (changed) {
            plugin.players().display(attacker, state);
        }
    }

    /** 玩家殺死怪物（獻祭）。 */
    public void killed(final Player killer) {
        final StatSheet sheet = plugin.players().sheet(killer);
        if (!has(sheet, StatKeys.SIG_SACRIFICE)) {
            return;
        }
        final PlayerState state = plugin.players().get(killer);
        if (state == null || !state.hasCharacter() || state.isDead()) {
            return;
        }
        state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * 0.04));
        sacrificeUntil.put(killer.getUniqueId(), now() + SACRIFICE_TICKS);
        killer.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, killer.getLocation().add(0, 1.2, 0), 4, 0.3, 0.3, 0.3, 0.02);
        plugin.players().display(killer, state);
    }

    public void forget(final UUID player) {
        lastStandUntil.remove(player);
        // 2026-10-18：背水的冷卻（伺服器 tick 的絕對時間）下線不清，重新登入不能馬上再觸發；過期的在 tick 裡清
        voidStepUntil.remove(player);
        sacrificeUntil.remove(player);
        thunderSoulAt.remove(player);
        lastSeen.remove(player);
        moving.remove(player);
        allies.remove(player);
    }
}
