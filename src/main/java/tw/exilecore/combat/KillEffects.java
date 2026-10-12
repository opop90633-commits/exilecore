package tw.exilecore.combat;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 擊殺時的特殊效果（傳奇裝備）：回復生命、魔力、能量護盾；殺死的敵人爆炸；疾速。
 * 怪物死亡時由 MonsterListener 呼叫 {@link #onKill}。爆炸排到下一個 tick（連鎖爆炸一個 tick 炸一圈，不會遞迴）。
 */
public final class KillEffects implements Listener {

    /** 疾速多久（tick）。 */
    public static final long ONSLAUGHT_TICKS = 80;
    /** 疾速：增加多少攻擊、施法與移動速度（小數）。 */
    public static final double ONSLAUGHT_SPEED = 0.20;
    /** 爆炸的半徑（格）。 */
    public static final double EXPLODE_RADIUS = 3.0;
    /** 爆炸的傷害來源代號（不是技能、不是攻擊：吃「增加火焰傷害」「增加範圍傷害」，不吃法術與攻擊的加成）。 */
    public static final String EXPLOSION = "unique.explosion";

    private final ExileCore plugin;
    private final SkillDamage damage;
    /** 疾速到哪個 tick。 */
    private final Map<UUID, Long> onslaught = new HashMap<>();

    private final ChargeService charges;

    KillEffects(final ExileCore plugin, final SkillDamage damage, final ChargeService charges) {
        this.plugin = plugin;
        this.damage = damage;
        this.charges = charges;
    }

    /** 屬性表的來源：疾速中增加 20% 攻擊、施法、移動速度。 */
    public List<Modifier> modifiers(final Player player) {
        final Long until = onslaught.get(player.getUniqueId());
        if (until == null || plugin.getServer().getCurrentTick() >= until) {
            return List.of();
        }
        return List.of(Modifier.increased(StatKeys.ATTACK_SPEED, ONSLAUGHT_SPEED, "疾速"),
                Modifier.increased(StatKeys.CAST_SPEED, ONSLAUGHT_SPEED, "疾速"),
                Modifier.increased(StatKeys.MOVEMENT_SPEED, ONSLAUGHT_SPEED, "疾速"));
    }

    /** 疾速中嗎。 */
    public boolean hasOnslaught(final Player player) {
        final Long until = onslaught.get(player.getUniqueId());
        return until != null && plugin.getServer().getCurrentTick() < until;
    }

    /**
     * 怪物死了：殺死牠的玩家身上有擊殺效果就觸發。
     *
     * @param victimMaxLife 怪物的最大生命（我們的數值，不是原版血量；爆炸的傷害照這個算）
     */
    public void onKill(final Player killer, final LivingEntity victim, final double victimMaxLife) {
        if (killer == null || !killer.isOnline()) {
            return;
        }
        final PlayerState state = plugin.players().get(killer);
        if (!state.hasCharacter() || state.isDead()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(killer);
        if (charges != null) {
            charges.onKill(killer); // 怒星
        }
        boolean changed = false;
        final double life = sheet.flat(StatKeys.LIFE_ON_KILL, Set.of());
        if (life > 0 && state.life() < state.maxLife()) {
            state.setLife(state.life() + state.maxLife() * life / 100.0);
            changed = true;
        }
        // PoE2：擊殺時回復固定生命／魔力
        final double lifeFlat = sheet.flat(StatKeys.LIFE_PER_KILL, Set.of());
        if (lifeFlat > 0 && state.life() < state.maxLife()) {
            state.setLife(Math.min(state.maxLife(), state.life() + lifeFlat));
            changed = true;
        }
        final double manaFlat = sheet.flat(StatKeys.MANA_PER_KILL, Set.of());
        if (manaFlat > 0 && state.mana() < state.maxMana()) {
            state.setMana(Math.min(state.maxMana(), state.mana() + manaFlat));
            changed = true;
        }
        final double mana = sheet.flat(StatKeys.MANA_ON_KILL, Set.of());
        if (mana > 0 && state.mana() < state.maxMana()) {
            state.setMana(state.mana() + state.maxMana() * mana / 100.0);
            changed = true;
        }
        final double es = sheet.flat(StatKeys.ES_ON_KILL, Set.of());
        if (es > 0 && state.energyShield() < state.maxEnergyShield()) {
            state.setEnergyShield(state.energyShield() + state.maxEnergyShield() * es / 100.0);
            changed = true;
        }
        if (changed) {
            plugin.players().display(killer, state);
        }
        final double onslaughtChance = sheet.flat(StatKeys.ONSLAUGHT_ON_KILL, Set.of());
        if (onslaughtChance > 0 && plugin.items().random().nextDouble() * 100 < onslaughtChance) {
            grantOnslaught(killer);
        }
        final double explode = sheet.flat(StatKeys.KILL_EXPLODE, Set.of());
        if (explode > 0 && victimMaxLife > 0) {
            explode(killer.getUniqueId(), victim.getLocation().add(0, 0.5, 0), victimMaxLife * Math.min(100, explode) / 100.0);
        }
        // 2026-10（傳奇）：用擊中殺死完全破甲的敵人，牠碎裂爆開（物理）
        final double shatter = sheet.flat(StatKeys.KILL_SHATTER, Set.of());
        if (shatter > 0 && victimMaxLife > 0 && plugin.ailments() != null
                && plugin.ailments().armourBroken(victim) == Double.POSITIVE_INFINITY) {
            explode(killer.getUniqueId(), victim.getLocation().add(0, 0.5, 0), victimMaxLife * Math.min(100, shatter) / 100.0, DamageType.PHYSICAL);
        }
        // 狂熱審判（獵巫者，PoE2）：x% 機率讓殺死的敵人爆炸，造成牠最大生命 100% 的傷害
        final double explodeChance = sheet.flat(StatKeys.KILL_EXPLODE_CHANCE, Set.of());
        if (explodeChance > 0 && victimMaxLife > 0 && plugin.items().random().nextDouble() * 100 < explodeChance) {
            explode(killer.getUniqueId(), victim.getLocation().add(0, 0.5, 0), victimMaxLife);
        }
        final double steal = sheet.flat(StatKeys.STEAL_MODS, Set.of());
        if (steal > 0) {
            stealMods(killer, victim, steal);
        }
    }

    /**
     * 斬獲腰環（追逐級傳奇；2026-10 以前叫逐星者）：殺死稀有怪物時，牠的詞綴變成你的增益 seconds 秒（同一種詞綴再殺一隻就重新計時）。
     * 詞綴的 stats 直接套（壯碩的 = 更多最大生命、鐵甲的 = 增加護甲、致命的 = 暴擊…），
     * 「附加 X% 物理為火焰」變成你的額外獲得，極速的變成增加移動速度，光環的詞綴給光環裡的數值；只有招式的詞綴沒有東西可拿。
     */
    private void stealMods(final Player killer, final LivingEntity victim, final double seconds) {
        if (plugin.skills() == null || plugin.monsters() == null) {
            return;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(victim).orElse(null);
        if (data == null || data.rarity() != tw.exilecore.monster.MonsterRarity.RARE || data.mods().isEmpty()) {
            return;
        }
        final int ticks = (int) Math.round(seconds * 20);
        int stolen = 0;
        for (final String modId : data.mods()) {
            final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(modId);
            if (mod == null) {
                continue;
            }
            final String source = "斬獲腰環：" + mod.name();
            final List<Modifier> modifiers = new java.util.ArrayList<>();
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
            if (modifiers.isEmpty()) {
                continue;
            }
            plugin.skills().buff(killer, "steal." + mod.id(), mod.name() + "（斬獲腰環）", ticks, modifiers);
            stolen++;
        }
        if (stolen > 0) {
            killer.sendActionBar(plugin.text().parse("<#ffb347>✦ 斬獲腰環：偷到 " + stolen + " 條詞綴（" + Math.round(seconds) + " 秒）"));
            killer.playSound(killer.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.6f, 1.5f);
            final Location at = victim.getLocation().add(0, 1, 0);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.END_ROD, at, 20, 0.5, 0.6, 0.5, 0.05);
        }
    }

    private void grantOnslaught(final Player player) {
        final UUID uuid = player.getUniqueId();
        final long now = plugin.getServer().getCurrentTick();
        final boolean wasActive = hasOnslaught(player);
        onslaught.put(uuid, now + ONSLAUGHT_TICKS);
        if (!wasActive) {
            plugin.players().markDirty(player); // 屬性表重算：多了疾速
            player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.5f, 1.6f);
        }
        final PlayerState state = plugin.players().get(player);
        state.notice("<#ffd27f>疾速 <gray>4 秒（攻擊、施法、移動 +20%）", now + 40);
        plugin.players().display(player, state);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final Long until = onslaught.get(uuid);
            if (until != null && plugin.getServer().getCurrentTick() >= until) {
                onslaught.remove(uuid);
                final Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) {
                    plugin.players().markDirty(online); // 疾速結束
                }
            }
        }, ONSLAUGHT_TICKS + 1);
    }

    /** 下一個 tick 在這裡爆炸：打周圍的怪物（不打玩家），傷害算殺死牠的那個玩家的。 */
    private void explode(final UUID killerId, final Location at, final double amount) {
        explode(killerId, at, amount, DamageType.FIRE);
    }

    private void explode(final UUID killerId, final Location at, final double amount, final DamageType type) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final Player killer = plugin.getServer().getPlayer(killerId);
            final World world = at.getWorld();
            if (killer == null || world == null || !killer.getWorld().equals(world)) {
                return;
            }
            final SkillProfile profile = new SkillProfile(EXPLOSION, Set.of(Tags.AREA, type.tag()), 0,
                    new DamagePacket().set(type, amount));
            final double radiusSquared = EXPLODE_RADIUS * EXPLODE_RADIUS;
            for (final LivingEntity target : world.getNearbyLivingEntities(at, EXPLODE_RADIUS)) {
                if (target instanceof Player || target.isDead() || !target.isValid() || plugin.monsters().data(target).isEmpty()
                        || target.getLocation().add(0, 0.5, 0).distanceSquared(at) > radiusSquared) {
                    continue; // getNearby 是方框：再用距離篩成圓的
                }
                damage.hit(killer, target, profile, null);
            }
            tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
            tw.exilecore.skill.Fx.spawn(world, type == DamageType.FIRE ? Particle.FLAME : Particle.CRIT, at, 24, EXPLODE_RADIUS / 2, 0.4,
                    EXPLODE_RADIUS / 2, 0.04);
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.6f, 1.3f);
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        onslaught.remove(event.getPlayer().getUniqueId());
        if (plugin.combat() != null && plugin.combat().signatures() != null) {
            plugin.combat().signatures().forget(event.getPlayer().getUniqueId());
        }
    }
}
