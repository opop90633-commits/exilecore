package tw.exilecore.combat;

import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「一次性的特殊效果」的 Bukkit 那一邊（鍵與純計算在 {@link KeystoneKeys}）：
 * 格擋時的護盾與移動速度、各種補償（元素、抵銷的物理、偏斜、護盾、創傷信仰、召喚物）、長棍暴擊擊退、範圍擊退、感電擴散、
 * 召喚物破甲、流血／破甲的敵人不能再生、擊殺回復、荊棘偷取、戰吼與藥劑的附帶效果、引導解詛咒、被標記／釘住的敵人不能暴擊。
 */
public final class KeystoneEffects implements Listener {

    /** 不能再生生命的標記（Debuffs 的代號；MonsterMechanics 看這個）。 */
    public static final String NO_REGEN_ID = "keystone.no_regen";
    /** 衰弱（PoE2 Debilitate）的代號。 */
    public static final String DEBILITATE_ID = "keystone.debilitate";
    /** 衰弱（poe2db Debilitate）：造成的傷害減少 10%、移動速度減少 20%。 */
    public static final double DEBILITATE_LESS = 0.1;
    public static final double DEBILITATE_SLOW = 0.2;
    /** 補償（recoup）分幾秒回（PoE2：4 秒）。 */
    static final int RECOUP_SECONDS = 4;
    /** 感電擴散的半徑（1.5 格 = 1.5 格）。 */
    static final double SHOCK_SPREAD_RADIUS = 1.5;
    /** 格擋次數記多久（10 秒）。 */
    static final long BLOCK_WINDOW = 200;

    private final ExileCore plugin;
    private final Debuffs debuffs;
    /** 玩家 → 最近格擋的 tick（10 秒內）。 */
    private final Map<UUID, ArrayDeque<Long>> blocks = new HashMap<>();

    KeystoneEffects(final ExileCore plugin, final Debuffs debuffs) {
        this.plugin = plugin;
        this.debuffs = debuffs;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private java.util.random.RandomGenerator random() {
        return plugin.items().random();
    }

    // ---- 給別的類別呼叫的（靜態，沒有狀態） ----

    /** 這一下是暴擊：情境多 {@link KeystoneKeys#CRIT_HIT}。 */
    public static Set<String> critContext(final Set<String> context, final boolean crit) {
        if (!crit) {
            return context;
        }
        final Set<String> tagged = new HashSet<>(context);
        tagged.add(KeystoneKeys.CRIT_HIT);
        return tagged;
    }

    /** 這一下是暴擊：技能多帶 {@link KeystoneKeys#CRIT_HIT}（異常、暈眩累積看技能的情境）。 */
    public static SkillProfile critSkill(final SkillProfile skill, final boolean crit) {
        return crit ? TargetConditions.tag(skill, Set.of(KeystoneKeys.CRIT_HIT)) : skill;
    }

    /** 怪物打玩家：被標記／釘住的怪不能暴擊、被標記的怪命中降低（玩家有那些天賦時）。 */
    public static List<Modifier> monsterAttack(final StatSheet defenderSheet, final LivingEntity attacker, final Debuffs debuffs, final long now,
                                               final List<Modifier> mods) {
        final boolean markedNoCrit = StatKeys.flag(defenderSheet, KeystoneKeys.MARKED_NO_CRIT);
        final boolean pinnedNoCrit = StatKeys.flag(defenderSheet, KeystoneKeys.PINNED_NO_CRIT);
        final double markedAccuracy = defenderSheet.increased(KeystoneKeys.MARKED_ACCURACY, Set.of());
        if (!markedNoCrit && !pinnedNoCrit && markedAccuracy == 0) {
            return mods;
        }
        final boolean marked = debuffs.marked(attacker.getUniqueId(), now);
        final boolean pinned = debuffs.has(attacker.getUniqueId(), "pin", now);
        final List<Modifier> out = new ArrayList<>(mods);
        if (marked && markedNoCrit || pinned && pinnedNoCrit) {
            out.add(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "無法暴擊"));
        }
        if (marked && markedAccuracy != 0) {
            out.add(Modifier.increased(StatKeys.ACCURACY, markedAccuracy, "標記"));
        }
        return out;
    }

    /** 喝藥劑：生命藥劑給守護、魔力藥劑回復生命、魔力藥劑解除詛咒。 */
    public static void flaskUsed(final ExileCore plugin, final Player player, final PlayerState state, final StatSheet sheet, final boolean life,
                                 final double recovery, final int ticks) {
        final long now = plugin.getServer().getCurrentTick();
        if (life) {
            final double guard = sheet.flat(KeystoneKeys.LIFE_FLASK_GUARD, Set.of());
            if (guard > 0 && ticks > 0) {
                // 每秒回復量的 x%，4 秒
                state.addGuard(recovery / (ticks / 20.0) * guard / 100.0, now + 80);
            }
            return;
        }
        final double lifePercent = sheet.flat(KeystoneKeys.MANA_FLASK_LIFE, Set.of());
        if (lifePercent > 0 && !state.ruined(now)) {
            state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * lifePercent / 100.0));
        }
        if (StatKeys.flag(sheet, KeystoneKeys.MANA_FLASK_UNCURSE) && plugin.combat() != null) {
            plugin.combat().debuffs().removeCurses(player.getUniqueId());
        }
    }

    /** 引導：連續引導 2 秒解除詛咒（每一段引導一次）。 */
    private static final Map<UUID, long[]> CHANNEL = new HashMap<>();

    public static void channelled(final ExileCore plugin, final Player player, final PlayerState state, final long now) {
        if (!StatKeys.flag(state.sheet(), KeystoneKeys.CHANNEL_UNCURSE)) {
            CHANNEL.remove(player.getUniqueId());
            return;
        }
        // {這一段開始的 tick, 上一下的 tick, 這一段解過了沒}
        final long[] streak = CHANNEL.computeIfAbsent(player.getUniqueId(), uuid -> new long[] {now, now, 0});
        if (now - streak[1] > tw.exilecore.stats.TreeConditions.CHANNEL_GRACE) {
            streak[0] = now;
            streak[2] = 0;
        }
        streak[1] = now;
        if (streak[2] == 0 && now - streak[0] >= 40 && plugin.combat() != null) {
            streak[2] = 1;
            plugin.combat().debuffs().removeCurses(player.getUniqueId());
        }
    }

    /**
     * 戰吼：回復生命與魔力、解除點燃、衰弱、暴擊弱點；回傳多強化幾次攻擊（15% 機率多 3 次）。
     */
    public static int onWarcry(final ExileCore plugin, final Player player, final StatSheet effective, final Set<String> tags,
                               final List<LivingEntity> enemies, final StringBuilder notice) {
        final long now = plugin.getServer().getCurrentTick();
        final PlayerState state = plugin.players().get(player);
        final double restore = effective.flat(KeystoneKeys.WARCRY_RESTORE, tags);
        if (restore > 0) {
            if (!state.ruined(now)) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * restore / 100.0));
            }
            state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * restore / 100.0));
        }
        if (StatKeys.flag(effective, KeystoneKeys.WARCRY_REMOVE_IGNITE) && plugin.ailments() != null) {
            plugin.ailments().cure(player, AilmentType.IGNITE);
        }
        final double debilitate = effective.flat(KeystoneKeys.WARCRY_DEBILITATE, tags);
        final int weakness = (int) Math.round(effective.flat(KeystoneKeys.WARCRY_CRIT_WEAKNESS, tags));
        if (plugin.combat() != null && (debilitate > 0 || weakness > 0)) {
            final Debuffs debuffs = plugin.combat().debuffs();
            for (final LivingEntity enemy : enemies) {
                if (debilitate > 0) {
                    debuffs.apply(enemy.getUniqueId(), new Debuffs.Debuff(DEBILITATE_ID, player.getUniqueId(), now + Math.round(debilitate * 20), 0,
                            false, null, List.of(Modifier.increased(StatKeys.DAMAGE, -DEBILITATE_LESS, "衰弱")), DEBILITATE_SLOW));
                }
                if (weakness > 0) {
                    HitEffects.addCritWeakness(debuffs, enemy.getUniqueId(), player.getUniqueId(), now, weakness, HitEffects.CRIT_WEAKNESS_TICKS);
                }
            }
        }
        final double chance = effective.flat(KeystoneKeys.WARCRY_EMPOWER3_CHANCE, tags);
        if (chance > 0 && plugin.items().random().nextDouble() * 100 < chance) {
            notice.append("<#f5b942>　（多強化 3 次）");
            return 3;
        }
        return 0;
    }

    /**
     * 2026-10-27 照 PoE2：玩家這一下擊中前疊上的：攻擊抽出最強的穿刺（{@link Impales}，加在減免前的物理）、
     * 箭照飛行距離的暴擊率（狙擊手：7 格給滿；飛行距離用你到目標的距離，跟遠星一樣）。
     */
    public static List<Modifier> attackerConditional(final Player attacker, final LivingEntity defender, final StatSheet skillSheet,
                                                     final SkillProfile skill) {
        List<Modifier> out = List.of();
        final Set<String> tags = skill.tags();
        if (skill.isAttack() && !tags.contains(Tags.TOTEM_HIT) && !StatKeys.flag(skillSheet, tw.exilecore.stats.SupportExtraKeys.FLAG_NO_EXTRACT)) { // 2026-10-27 照 PoE2 Impale 輔助：不會抽出穿刺
            final double impale = Impales.shared().strongest(defender.getUniqueId(), attacker.getUniqueId());
            if (impale > 0) {
                out = new ArrayList<>(out);
                out.add(Modifier.flat(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_EXTRACT, impale, "穿刺"));
            }
        }
        if (tags.contains(Tags.BOW) && tags.contains(Tags.PROJECTILE) && attacker.getWorld().equals(defender.getWorld())) {
            final double max = skillSheet.increased(tw.exilecore.stats.KeystoneOriginKeys.ARROW_DISTANCE_CRIT, tags);
            final double crit = tw.exilecore.stats.KeystoneOriginKeys.arrowCrit(max, attacker.getLocation().distance(defender.getLocation()));
            if (crit > 0) {
                out = new ArrayList<>(out);
                out.add(new Modifier(StatKeys.CRIT_CHANCE, tw.exilecore.stats.ModType.INCREASED, crit, Set.of(), "狙擊手"));
            }
        }
        return out;
    }

    // ---- 擊中 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        if (!result.hit() || event.skill() == null) {
            return;
        }
        final LivingEntity defender = event.defender();
        if (defender instanceof Player player) {
            defended(player, event.attacker(), result);
            return;
        }
        if (result.blocked()) {
            return;
        }
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        if (minions != null && minions.isMinion(defender)) {
            final Player owner = minions.owner(defender);
            final double recoup = owner == null ? 0 : plugin.players().sheet(owner).flat(KeystoneKeys.MINION_RECOUP, Set.of());
            if (recoup > 0 && result.total() > 0) {
                minions.heal(defender, result.total() * Math.min(100, recoup) / 100.0); // 召喚物的補償（一次補回）
            }
            return;
        }
        final MonsterData monster = plugin.monsters() == null ? null : plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return;
        }
        final Player owner = event.credit();
        if (owner == null) {
            return;
        }
        final StatSheet ownerSheet = plugin.players().sheet(owner);
        final SkillProfile skill = event.skill();
        final Set<String> tags = skill.tags();
        final long now = now();
        if (event.attacker() == owner) {
            final StatSheet effective = skill.effective(ownerSheet);
            impale(owner, defender, skill, effective, result); // 2026-10-27 照 PoE2 穿刺
            if (tags.contains(Tags.THORNS)) {
                final double leech = ownerSheet.flat(KeystoneKeys.THORNS_LEECH, Set.of());
                if (leech > 0 && result.total() > 0) {
                    final PlayerState state = plugin.players().get(owner);
                    if (!state.ruined(now)) {
                        state.addHitLeech(result.total() * leech / 100.0); // 荊棘傷害偷取為生命（2026-10-27：照擊中的偷取規則）
                    }
                }
            }
            if (result.crit() && StatKeys.flag(effective, KeystoneKeys.KNOCKBACK_CRIT_QUARTERSTAFF) && tags.contains(Tags.QUARTERSTAFF)) {
                knockback(owner, defender);
            } else if (tags.contains(Tags.AREA)) {
                final double chance = effective.flat(KeystoneKeys.AREA_KNOCKBACK, tags);
                if (chance > 0 && random().nextDouble() * 100 < chance) {
                    knockback(owner, defender);
                }
            }
            final double spread = effective.flat(KeystoneKeys.SHOCK_SPREAD, tags);
            if (spread > 0 && result.dealt().get(DamageType.LIGHTNING) > 0 && plugin.ailments() != null) {
                final boolean before = plugin.ailments().has(defender, AilmentType.SHOCK);
                // 感電在這一下之後才上（CombatListener 的 MONITOR）：下一 tick 看這一下有沒有讓牠感電
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> spreadShock(owner, defender, before, spread), 1);
            }
        }
        // 流血、完全破甲的敵人不能再生（看一下之後牠的狀態）
        final boolean bleedNoRegen = StatKeys.flag(ownerSheet, KeystoneKeys.BLEED_NO_REGEN);
        final boolean brokenNoRegen = StatKeys.flag(ownerSheet, KeystoneKeys.BROKEN_NO_REGEN);
        if ((bleedNoRegen || brokenNoRegen) && plugin.ailments() != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!defender.isValid() || defender.isDead()) {
                    return;
                }
                if (bleedNoRegen && plugin.ailments().has(defender, AilmentType.BLEED)
                        || brokenNoRegen && Double.isInfinite(plugin.ailments().armourBroken(defender))) {
                    debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff(NO_REGEN_ID, owner.getUniqueId(), now() + 20, 0, false));
                }
            }, 1);
        }
        // 召喚物破甲（等同物理傷害 x%）
        if (minions != null && minions.isMinion(event.attacker()) && plugin.ailments() != null) {
            final double percent = ownerSheet.flat(KeystoneKeys.MINION_BREAK, Set.of());
            final double physical = result.outgoing().get(DamageType.PHYSICAL);
            if (percent > 0 && physical > 0) {
                final double armour = plugin.monsters().sheet(defender, monster).get(StatKeys.ARMOUR);
                if (armour > 0) {
                    plugin.ailments().breakArmour(defender, monster, physical * percent / 100.0, 100, armour);
                }
            }
        }
    }

    /**
     * 2026-10-27 照 PoE2 穿刺：攻擊擊中抽掉最強的那一個（傷害在 CombatListener 擊中前就加好了）；
     * 法術擊中照「法術擊中時 x% 機率穿刺」存下減免前物理傷害的 30%（乘穿刺效果）。
     */
    private void impale(final Player owner, final LivingEntity defender, final SkillProfile skill, final StatSheet effective, final HitResult result) {
        final Set<String> tags = skill.tags();
        if (skill.isAttack() && !StatKeys.flag(effective, tw.exilecore.stats.SupportExtraKeys.FLAG_NO_EXTRACT)) { // 2026-10-27：穿刺輔助不抽出
            Impales.shared().extract(defender.getUniqueId(), owner.getUniqueId());
        }
        if (!tags.contains(Tags.SPELL)) {
            return;
        }
        final double chance = effective.flat(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_SPELL_CHANCE, tags);
        final double physical = result.outgoing().get(DamageType.PHYSICAL);
        if (chance > 0 && physical > 0 && random().nextDouble() * 100 < chance) {
            Impales.shared().add(defender.getUniqueId(), owner.getUniqueId(), tw.exilecore.stats.KeystoneOriginKeys.impaleAmount(physical,
                    effective.increased(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_MAGNITUDE, tags)));
            defender.getWorld().spawnParticle(Particle.CRIT, defender.getLocation().add(0, defender.getHeight() * 0.6, 0), 6, 0.2, 0.3, 0.2, 0.05);
        }
    }

    /** 玩家挨打：格擋的護盾與移動速度、各種補償、創傷信仰。 */
    private void defended(final Player player, final LivingEntity attacker, final HitResult result) {
        final PlayerState state = plugin.players().get(player);
        if (state == null || !state.hasCharacter() || state.isDead()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final long now = now();
        if (result.blocked()) {
            final double es = sheet.flat(KeystoneKeys.ES_ON_BLOCK, Set.of());
            if (es > 0 && state.maxEnergyShield() > 0) {
                addEs(state, sheet, es);
                plugin.players().display(player, state);
            }
            if (sheet.increased(KeystoneKeys.MOVE_PER_BLOCK, Set.of()) != 0) {
                final ArrayDeque<Long> list = blocks.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>());
                list.addLast(now);
                plugin.players().markDirty(player);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.players().markDirty(player); // 10 秒到了：那一次格擋不算了
                    }
                }, BLOCK_WINDOW + 1);
            }
            return;
        }
        final double dealt = result.dealt().total();
        double life = 0;
        final double elemental = sheet.flat(KeystoneKeys.RECOUP_ELEMENTAL, Set.of());
        if (elemental > 0) {
            life += (result.dealt().get(DamageType.FIRE) + result.dealt().get(DamageType.COLD) + result.dealt().get(DamageType.LIGHTNING))
                    * elemental / 100.0;
        }
        final double prevented = Math.max(0, result.outgoing().get(DamageType.PHYSICAL) - result.dealt().get(DamageType.PHYSICAL));
        final double preventedLife = sheet.flat(KeystoneKeys.RECOUP_PREVENTED, Set.of());
        if (preventedLife > 0) {
            life += prevented * preventedLife / 100.0;
        }
        final double deflected = sheet.flat(KeystoneKeys.RECOUP_DEFLECTED, Set.of());
        if (deflected > 0 && result.deflected()) {
            life += dealt * deflected / 100.0;
        }
        if (life > 0 && !state.ruined(now)) {
            state.addLeech(life); // 跟其他補償一樣存進偷取慢慢回
        }
        double es = dealt * Math.max(0, sheet.flat(KeystoneKeys.ES_RECOUP, Set.of())) / 100.0;
        final double scarred = sheet.flat(KeystoneKeys.SCARRED_RECOUP, Set.of());
        if (scarred > 0 && prevented > 0 && attacker != null && plugin.monsters() != null) {
            // 創傷信仰：每點敵人強度（一般 1、魔法 2、稀有 5、傳奇 10）抵銷的物理傷害 x% 補償為護盾
            final double power = plugin.monsters().data(attacker).map(data -> switch (data.rarity()) {
                case NORMAL -> 1.0;
                case MAGIC -> 2.0;
                case RARE -> 5.0;
                case UNIQUE -> 10.0;
            }).orElse(1.0);
            es += prevented * scarred * power / 100.0;
        }
        if (es > 0 && state.maxEnergyShield() > 0) {
            // 2026-10-27：跟生命的回復一樣進 state.recoup()（禁療中不回、吃回復速度、死掉清掉；創傷信仰的上限在 PlayerStateService 套）；
            // 這次之前每一下排 4 個延遲工作直接加護盾，禁療中也照回
            state.recoup().add(tw.exilecore.character.Recoup.ES_CAPPED, es, // 2026-10-27：分開一種，上限只套這份
                    tw.exilecore.stats.TreeEffectKeys.scale(sheet, tw.exilecore.stats.TreeEffectKeys.RECOUP_SPEED, Set.of(), 0.1));
        }
    }

    /** 加能量護盾（創傷信仰：不會回到超過護甲）。 */
    private static void addEs(final PlayerState state, final StatSheet sheet, final double amount) {
        double target = state.energyShield() + amount;
        if (StatKeys.flag(sheet, KeystoneKeys.FLAG_SCARRED_FAITH)) {
            target = Math.min(target, Math.max(state.energyShield(), sheet.get(StatKeys.ARMOUR)));
        }
        state.setEnergyShield(target);
    }

    /** 過去 10 秒格擋了幾次（「每格擋一次增加 x% 移動速度」）。 */
    public List<Modifier> modifiers(final Player player) {
        final ArrayDeque<Long> list = blocks.get(player.getUniqueId());
        if (list == null) {
            return List.of();
        }
        final long now = now();
        while (!list.isEmpty() && now - list.peekFirst() > BLOCK_WINDOW) {
            list.pollFirst();
        }
        if (list.isEmpty()) {
            blocks.remove(player.getUniqueId());
            return List.of();
        }
        final double per = plugin.players().get(player).sheet().increased(KeystoneKeys.MOVE_PER_BLOCK, Set.of());
        return per == 0 ? List.of()
                : List.of(Modifier.increased(StatKeys.MOVEMENT_SPEED, per * Math.min(KeystoneKeys.BLOCK_STACK_CAP, list.size()), "格擋"));
    }

    private void knockback(final Player owner, final LivingEntity target) {
        final Vector push = target.getLocation().toVector().subtract(owner.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 1e-6) {
            return;
        }
        target.setVelocity(target.getVelocity().add(push.normalize().multiply(0.6).setY(0.25)));
    }

    /** 這一下讓目標感電了：機率讓 1.5 格內的其他敵人也感電（同樣的效果、4 秒）。 */
    private void spreadShock(final Player owner, final LivingEntity target, final boolean before, final double chance) {
        if (before || !target.isValid() || plugin.ailments() == null || !plugin.ailments().has(target, AilmentType.SHOCK)
                || random().nextDouble() * 100 >= chance) {
            return;
        }
        final double effect = Math.max(0, plugin.ailments().damageTakenMultiplier(target) - 1);
        if (effect <= 0) {
            return;
        }
        for (final org.bukkit.entity.Entity near : target.getNearbyEntities(SHOCK_SPREAD_RADIUS, SHOCK_SPREAD_RADIUS, SHOCK_SPREAD_RADIUS)) {
            if (near instanceof LivingEntity other && other != owner && !(other instanceof Player) && plugin.monsters() != null
                    && plugin.monsters().data(other).isPresent()) {
                plugin.ailments().groundStatusOnMonster(other, 0, effect, 80);
                other.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, other.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
            }
        }
    }

    /** 玩家 → 上次戰星產生器給戰星的 tick。 */
    private final Map<UUID, Long> generated = new HashMap<>();

    /**
     * 每秒：寶石匠的戰星產生器（PoE2 Thaumaturgical Dynamism：每 5 秒隨機一顆戰星，鑲嵌的寶石屬性需求越高越容易拿到那一種）。
     * 我們照角色的力量／敏捷／智慧當權重（力量→磐星、敏捷→怒星、智慧→銳星）。
     */
    void tick() {
        final long now = now();
        // 2026-10-27：穿刺記在不在了的怪物身上的拿掉
        Impales.shared().prune(uuid -> plugin.getServer().getEntity(uuid) instanceof LivingEntity living && living.isValid() && !living.isDead());
        if (plugin.combat() == null || plugin.combat().charges() == null) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final StatSheet sheet = plugin.players().sheet(player);
            final double seconds = sheet.flat(KeystoneKeys.CHARGE_GENERATOR, Set.of());
            if (seconds <= 0 || player.isDead()) {
                generated.remove(player.getUniqueId());
                continue;
            }
            final long last = generated.computeIfAbsent(player.getUniqueId(), uuid -> now);
            if (now - last < Math.round(seconds * 20)) {
                continue;
            }
            generated.put(player.getUniqueId(), now);
            final double str = Math.max(1, sheet.get(StatKeys.STR));
            final double dex = Math.max(1, sheet.get(StatKeys.DEX));
            final double intelligence = Math.max(1, sheet.get(StatKeys.INT));
            final double roll = random().nextDouble() * (str + dex + intelligence);
            final String charge = roll < str ? "endurance" : roll < str + dex ? "frenzy" : "power";
            plugin.combat().charges().grant(player, charge, 1);
        }
    }

    // ---- 擊殺 ----

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || plugin.monsters() == null || plugin.monsters().data(dead).isEmpty()) {
            return;
        }
        Impales.shared().forget(dead.getUniqueId());
        final StatSheet sheet = plugin.players().sheet(killer);
        // 2026-10-27 照 PoE2 暴烈衝擊：殺死燃燒中的敵人時 x% 機率爆炸，造成牠最大生命十分之一的火焰傷害；這次之前平衡等效成「0.5% 機率整條生命、不限燃燒」
        final double burning = sheet.flat(tw.exilecore.stats.KeystoneOriginKeys.BURNING_EXPLODE_CHANCE, Set.of());
        if (burning > 0 && plugin.ailments() != null && plugin.ailments().has(dead, AilmentType.IGNITE) && plugin.combat() != null
                && plugin.combat().killEffects() != null && random().nextDouble() * 100 < burning) {
            final org.bukkit.attribute.AttributeInstance max = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            final double maxLife = plugin.monsters().data(dead).map(data -> plugin.monsters().sheet(dead, data).get(StatKeys.LIFE_MAX)).orElse(0.0);
            final double life = maxLife > 0 ? maxLife : max == null ? 0 : max.getValue();
            if (life > 0) {
                plugin.combat().killEffects().explodeAt(killer, dead.getLocation().add(0, 0.5, 0),
                        life * tw.exilecore.stats.KeystoneOriginKeys.BURNING_EXPLODE_SHARE, DamageType.FIRE);
            }
        }
        final double full = sheet.flat(KeystoneKeys.KILL_FULL_LIFE, Set.of());
        final double poisoned = sheet.flat(KeystoneKeys.KILL_POISONED_LIFE, Set.of());
        if (full <= 0 && poisoned <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(killer);
        if (state.isDead() || state.ruined(now())) {
            return;
        }
        double gain = 0;
        if (full > 0 && random().nextDouble() * 100 < full) {
            gain = state.maxLife();
        }
        if (poisoned > 0 && plugin.ailments() != null && plugin.ailments().has(dead, AilmentType.POISON)) {
            gain += state.maxLife() * poisoned / 100.0;
        }
        if (gain > 0) {
            state.setLife(Math.min(state.maxLife(), state.life() + gain));
            plugin.players().display(killer, state);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        blocks.remove(event.getPlayer().getUniqueId());
        generated.remove(event.getPlayer().getUniqueId());
        CHANNEL.remove(event.getPlayer().getUniqueId());
    }
}
