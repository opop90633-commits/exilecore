package tw.exilecore.combat;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.character.PlayerState;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.IncomingDamage;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 接管原版傷害（設計文件「傷害計算流程」與「與原版規則的切割」）：
 * 原版的傷害數字一律丟掉，命中只是觸發器；
 * 玩家打怪物 → 算完把數字寫回事件（怪物的原版護甲已歸零）；
 * 怪物打玩家 → 扣我們自己的生命／護盾，原版傷害設為 0，生命歸零才讓原版處理死亡。
 */
public final class CombatListener implements Listener {

    /** 生命歸零時交給原版殺死玩家用的傷害。 */
    private static final double LETHAL = 1_000_000;

    /**
     * 算好但還沒套用到玩家身上的傷害：等事件確定沒被其他插件取消（MONITOR）再扣。
     * manaDamage：傳奇「受到的傷害先從魔力扣」那一份；recoup：之後慢慢回成生命的量。
     */
    private record Pending(double esDamage, double lifeDamage, double manaDamage, double recoup,
                           String source, String skill, DamagePacket dealt, DamagePacket outgoing, LivingEntity thornsTarget,
                           boolean byEnemy, Player pvpAttacker) {
    }

    /** 玩家打中怪物：等事件確定沒被取消（MONITOR）才回的「擊中回復生命」。 */
    private record LifeGain(Player player, double amount) {
    }

    /** 算好、等傷害事件確定沒被取消（MONITOR）才套用的元素異常。 */
    private record PendingAilment(LivingEntity attacker, SkillProfile skill, StatSheet attackerSheet, StatSheet defenderSheet,
                                  HitResult result, double taken, MonsterData defenderMonster) {
    }

    private final ExileCore plugin;
    private final EnvironmentDamage environment;
    /** 週期性環境傷害（火、岩漿）的限速：玩家 → 上次套用的 tick。 */
    private final Map<UUID, Long> environmentCooldown = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, PendingAilment> pendingAilments = new HashMap<>();
    /** 玩家打怪物：等事件確定沒被取消（MONITOR）才記進傷害紀錄（掉落分份、經驗歸屬）。 */
    private final Map<UUID, Credit> pendingCredits = new HashMap<>();

    private record Credit(UUID player, double amount) {
    }

    /**
     * 玩家打怪物：等事件確定沒被取消（MONITOR）才冒傷害數字、擲戰星。
     *
     * @param chargeChances 玩家自己打的這一下「擊中時獲得戰星」的機率（照技能，含輔助寶石；召喚物打的是空的）
     */
    private record PendingNumber(Player player, double amount, boolean crit, tw.exilecore.stats.DamagePacket dealt, boolean own,
                                 java.util.Map<String, Double> chargeChances) {
    }

    /**
     * 玩家自己打的這一下擊中時獲得戰星的機率。召喚物打的、荊棘的反彈、殺死的敵人爆炸都不算擊中（空的）；沒有戰星系統也是空的。
     */
    /** 這一擊的標籤（減益的標籤限制用）：技能的標籤；攻擊也算物理（威壓：物理傷害）。 */
    private static java.util.Set<String> hitTags(final SkillProfile skill) {
        if (skill == null || skill.tags() == null) {
            return java.util.Set.of("attack", "physical");
        }
        if (skill.tags().contains("attack") && !skill.tags().contains("physical")) {
            final java.util.Set<String> tags = new java.util.HashSet<>(skill.tags());
            tags.add("physical");
            return tags;
        }
        return skill.tags();
    }

    private java.util.Map<String, Double> chargeChances(final boolean own, final SkillProfile skill, final StatSheet attackerSheet) {
        if (!own || charges == null || Thorns.ID.equals(skill.id()) || KillEffects.EXPLOSION.equals(skill.id()) || Presence.BURN_ID.equals(skill.id())) {
            return java.util.Map.of();
        }
        return charges.hitChances(skill.effective(attackerSheet), skill.tags());
    }

    private final Map<UUID, PendingNumber> pendingNumbers = new HashMap<>();
    private final Map<UUID, LifeGain> pendingLifeGains = new HashMap<>();
    private final SkillDamage skills;
    private final DamageNumbers numbers;
    private final Debuffs debuffs;
    /** 三相輔助的元素共鳴（2026-10）。 */
    private final Trinity trinity = new Trinity();
    private DodgeRoll dodge;

    void setDodge(final DodgeRoll dodge) {
        this.dodge = dodge;
    }
    /** PoE2 的命中熵（2026-10）：閃避不擲骰，照比例平均閃掉。 */
    private final tw.exilecore.stats.Entropy entropy = new tw.exilecore.stats.Entropy();
    /** 死亡回顧：玩家挨的每一下都記一筆。 */
    private final DeathRecap recap;
    /** 怪物打中玩家要不要擊退（config.yml 的 combat.monster-knockback，預設不要：PoE 沒有）。 */
    private boolean monsterKnockback;
    /** 球（暴擊時、被打中時獲得）；沒接上就是 null。 */
    private ChargeService charges;
    private Signatures signatures;

    void setSignatures(final Signatures signatures) {
        this.signatures = signatures;
    }

    public CombatListener(final ExileCore plugin, final EnvironmentDamage environment, final SkillDamage skills,
                          final DamageNumbers numbers, final Debuffs debuffs, final DeathRecap recap) {
        this.plugin = plugin;
        this.environment = environment;
        this.skills = skills;
        this.numbers = numbers;
        this.debuffs = debuffs;
        this.recap = recap;
    }

    void setMonsterKnockback(final boolean monsterKnockback) {
        this.monsterKnockback = monsterKnockback;
    }

    /** 戰鬥數據（側欄）。 */
    private CombatHud hud;

    void setHud(final CombatHud hud) {
        this.hud = hud;
    }

    void setCharges(final ChargeService charges) {
        this.charges = charges;
    }

    /**
     * 怪物打玩家不擊退：近戰、箭與其他投射物（含「衝擊」附魔）、爆炸（苦力怕）、怪物技能的傷害，
     * 還有怪物把人推開的招式（伏守者的音波、疣豬獸的頂撞、劫毀獸的吼叫）都一樣。
     * 玩家打玩家（PvP）、盾牌格擋的反推照原版；擠在一起的推擠不會觸發這個事件；技能打怪物的擊退在 {@link SkillDamage#onKnockback}。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerKnockback(final EntityKnockbackEvent event) {
        if (monsterKnockback || !(event.getEntity() instanceof Player)) {
            return;
        }
        switch (event.getCause()) {
            case DAMAGE, ENTITY_ATTACK, EXPLOSION, SWEEP_ATTACK, PUSH -> {
                final Entity pusher = event instanceof EntityPushedByEntityAttackEvent pushed ? pushed.getPushedBy() : null;
                if (!(resolveAttacker(pusher) instanceof Player)) {
                    event.setCancelled(true); // 怪物（或沒有來源的爆炸、環境）造成的
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(final EntityDamageByEntityEvent event) {
        plugin.perf().time("combat.hit", () -> handleDamageByEntity(event));
    }

    private void handleDamageByEntity(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity defender)) {
            return;
        }
        final LivingEntity attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            // 例如 TNT、掉落的鐵砧：走環境傷害（Boss 不吃：原版的數字沒經過換算，見 BossDamageRules）
            if (isBoss(defender)) {
                event.setCancelled(true);
            }
            return;
        }
        final boolean attackerIsPlayer = attacker instanceof Player;
        final boolean defenderIsPlayer = defender instanceof Player;
        // 玩家的召喚物：打怪物算主人的，被怪物打用自己的屬性表；玩家、召喚物之間互不傷害——
        // 除了競技場：不同隊的玩家（與他們的召喚物）可以互打（PvP），傷害乘上專屬的 PvP 減傷
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        final boolean attackerIsMinion = minions != null && !attackerIsPlayer && minions.isMinion(attacker);
        final boolean defenderIsMinion = minions != null && !defenderIsPlayer && minions.isMinion(defender);
        Player pvpAttacker = null;
        if ((attackerIsPlayer || attackerIsMinion) && (defenderIsPlayer || defenderIsMinion)) {
            final Player attackerSide = attackerIsPlayer ? (Player) attacker : minions.owner(attacker);
            final Player defenderSide = defenderIsPlayer ? (Player) defender : minions.owner(defender);
            final tw.exilecore.pvp.PvpService arena = plugin.pvp();
            if (arena == null || attackerSide == null || defenderSide == null || !arena.canAttack(attackerSide, defenderSide)) {
                event.setCancelled(true);
                return;
            }
            pvpAttacker = attackerSide;
        }
        if (!attackerIsPlayer && !defenderIsPlayer && !attackerIsMinion && !defenderIsMinion) {
            if (isBoss(defender)) {
                // 怪物打到 Boss（骷髏的流彈、互相打起來）：原版的傷害沒經過換算，原版 1 點血等於 Boss 幾百點生命，不算
                event.setCancelled(true);
            }
            return; // 其他怪物互打交給原版
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) {
            event.setCancelled(true); // 原版荊棘附魔的反彈不算一次攻擊（反傷之後由技能與詞綴處理）
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM && !attackerIsPlayer && !attackerIsMinion
                && plugin.skills() != null && plugin.skills().bossSkills().drivesBoss(attacker.getUniqueId())) {
            // 掛了 Boss 招式的伏守者不用原版音波（沒有地上的警告、隔牆也打得到，招式裡有自己的音爆）；
            // 招式引擎平常就讓它放不出來，這裡是萬一沒擋住（Paper 版本對不上）時的保險
            event.setCancelled(true);
            return;
        }
        final AilmentService ailments = plugin.ailments();
        if (ailments != null && ailments.isIncapacitated(attacker)) {
            event.setCancelled(true); // 凍結中不能攻擊（凍結前射出的投射物打到也不算）
            return;
        }

        // 攻擊方的屬性表與技能；credit 是這一下算哪個玩家的（召喚物打的算主人）
        final StatSheet attackerBase;
        final SkillProfile skill;
        final Player credit;
        if (attackerIsPlayer) {
            final Player player = (Player) attacker;
            final PlayerState state = plugin.players().get(player);
            attackerBase = plugin.players().sheet(player);
            credit = player;
            final SkillDamage.Context context = skills.contextFor(defender);
            if (context != null && context.attacker() == player) {
                // 技能命中（skill 模組）：攻速與按鍵已經在施放時處理過
                skill = context.profile();
            } else {
                if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                    event.setCancelled(true); // 橫掃由範圍技能（橫劈）決定
                    return;
                }
                if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !consumeAttack(player, state, attackerBase)) {
                    event.setCancelled(true); // 攻速限制（skill 模組沒啟用時的原版近戰）
                    return;
                }
                skill = SkillProfile.defaultAttack(state.weaponTags());
            }
        } else if (attackerIsMinion) {
            final MinionHooks.Attack attack = minions.attack(attacker);
            if (attack == null) {
                event.setCancelled(true); // 主人不在了的召喚物不打人
                return;
            }
            attackerBase = attack.sheet();
            skill = attack.profile();
            credit = attack.owner();
        } else {
            final Optional<MonsterData> data = plugin.monsters().data(attacker);
            if (data.isEmpty()) {
                return; // 沒等級化的生物（例如其他插件的）交給原版
            }

            attackerBase = plugin.monsters().sheet(attacker, data.get());
            final SkillDamage.Context context = skills.contextFor(defender);
            // Boss 的招式（skill 模組）：傷害倍率、類型照招式；其他都是怪物的普攻
            skill = context != null && context.attacker() == attacker ? context.profile() : SkillProfile.monsterAttack();
            credit = null;
        }
        if (credit != null) {
            plugin.players().grace().end(credit, true); // 打到東西了（召喚物打到也算）：入場保護結束
        }

        // 防禦方
        final StatSheet defenderBase;
        final double defenderEs;
        PlayerState defenderState = null;
        MonsterData defenderMonster = null;
        if (defenderIsPlayer && dodge != null && dodge.invulnerable((Player) defender)
                && (skill == null || !skill.tags().contains(DodgeRoll.UNDODGEABLE_TAG))) {
            event.setCancelled(true); // 翻滾中（2026-10）：打不到
            if (attackerIsPlayer || credit != null) {
                numbers.word(credit != null ? credit : (Player) attacker, defender, "閃過");
            }
            return;
        }
        if (defenderIsPlayer) {
            defenderState = plugin.players().get((Player) defender);
            defenderBase = plugin.players().sheet((Player) defender);
            defenderEs = defenderState.energyShield();
        } else if (defenderIsMinion) {
            defenderBase = minions.sheet(defender);
            if (defenderBase == null) {
                event.setCancelled(true); // 沒在名單上的召喚物（上次當機留下的）：不會被打，之後會被清掉
                return;
            }
            defenderEs = 0;
        } else {
            final Optional<MonsterData> data = plugin.monsters().data(defender);
            if (data.isEmpty()) {
                if (attackerIsMinion) {
                    event.setCancelled(true); // 召喚物不打沒等級化的生物（NPC、動物）
                }
                return; // 打到沒等級化的生物：原版處理
            }
            defenderMonster = data.get();
            defenderBase = plugin.monsters().sheet(defender, defenderMonster);
            defenderEs = 0;
        }

        // 2026-10：減益帶的修飾器（曝露、削弱詛咒、眩暈）疊進雙方的屬性表
        final long debuffTick = plugin.getServer().getCurrentTick();
        final java.util.List<Modifier> attackerDebuffs = debuffs.modifiers(attacker.getUniqueId(), defender.getUniqueId(), debuffTick);
        final StatSheet attackerSheet = attackerDebuffs.isEmpty() ? attackerBase : attackerBase.overlay(attackerDebuffs);
        final java.util.List<Modifier> defenderDebuffs = debuffs.modifiers(defender.getUniqueId(), attacker.getUniqueId(), debuffTick);
        final StatSheet defenderSheet = defenderDebuffs.isEmpty() ? defenderBase : defenderBase.overlay(defenderDebuffs);

        // 破甲（2026-10 PoE2）：怪物被破掉的護甲從屬性表扣掉；完全破甲 = 沒有護甲，物理擊中受到的傷害增加 20%
        final double rawArmour = defenderSheet.get(StatKeys.ARMOUR);
        double brokenTaken = 0;
        StatSheet hitDefender = defenderSheet;
        if (defenderMonster != null && ailments != null && rawArmour > 0) {
            final double broken = ailments.armourBroken(defender);
            if (broken > 0) {
                hitDefender = defenderSheet.overlay(java.util.List.of(Modifier.flat(StatKeys.ARMOUR, -Math.min(rawArmour, broken), "破甲")));
                if (Double.isInfinite(broken) && (hitTags(skill).contains(Tags.PHYSICAL)
                        || attackerIsPlayer && StatKeys.flag(attackerSheet, StatKeys.FLAG_IMPLODING))) { // 內爆衝擊：所有擊中都增加
                    brokenTaken = ailments.fullyBrokenTaken();
                }
            }
        }
        // 感電：受到的傷害增加（在減免之後乘）；印記、戰吼的減益也在這裡乘
        final double taken = ((ailments == null ? 1.0 : ailments.damageTakenMultiplier(defender)) + brokenTaken)
                * debuffs.takenMultiplier(defender.getUniqueId(), attacker.getUniqueId(), plugin.getServer().getCurrentTick(), hitTags(skill));
        // PvP：傷害乘上專屬的 PvP 減傷（元素異常照縮小後的傷害算：pendingAilment 存的是沒乘減傷的 taken）
        final double pvpScale = pvpAttacker != null && plugin.pvp() != null ? plugin.pvp().damageMultiplier() : 1.0;
        // 昇華的招牌天賦（2026-10）：玩家自己打出去的、玩家挨打的倍率
        double signature = 1.0;
        if (signatures != null) {
            if (attackerIsPlayer) {
                signature *= signatures.outgoing((Player) attacker, attackerSheet, skill, defender, defenderMonster);
            }
            if (defenderIsPlayer) {
                signature *= signatures.incoming((Player) defender, defenderState, defenderSheet);
            }
        }
        // 三相輔助（2026-10）：照元素共鳴疊上更多元素傷害與穿透
        final long hitTick = plugin.getServer().getCurrentTick();
        final boolean trinityHit = attackerIsPlayer && StatKeys.flag(skill.effective(attackerSheet), StatKeys.FLAG_TRINITY);
        StatSheet hitSheet = trinityHit ? attackerSheet.overlay(trinity.bonus(attacker.getUniqueId(), hitTick)) : attackerSheet;
        // 痛苦調和（PoE2 基石）：滿血時暴擊傷害加成更少 30%，低血（35% 以下）時更多 30%
        if (attackerIsPlayer && StatKeys.flag(attackerSheet, StatKeys.FLAG_PAIN_ATTUNEMENT)) {
            final PlayerState painState = plugin.players().get((Player) attacker);
            final double fraction = painState.maxLife() <= 0 ? 1 : painState.life() / painState.maxLife();
            if (fraction >= 0.999) {
                hitSheet = hitSheet.overlay(java.util.List.of(Modifier.more(StatKeys.CRIT_BONUS, -0.3, "痛極生力")));
            } else if (fraction <= 0.35) {
                hitSheet = hitSheet.overlay(java.util.List.of(Modifier.more(StatKeys.CRIT_BONUS, 0.3, "痛極生力")));
            }
        }
        // PoE2 昇華：對重暈中的敵人更多傷害、對低血的敵人增加傷害、每 50 目前生命增加暴擊傷害加成
        if (attackerIsPlayer) {
            final java.util.List<Modifier> conditional = new java.util.ArrayList<>();
            final double vsStunned = attackerSheet.flat(StatKeys.DAMAGE_VS_STUNNED, java.util.Set.of());
            if (vsStunned > 0 && ailments != null && ailments.has(defender, tw.exilecore.ailment.AilmentType.STUN)) {
                conditional.add(Modifier.more(StatKeys.DAMAGE, vsStunned / 100.0, "裂勁"));
            }
            final double vsLow = skill.effective(attackerSheet).flat(StatKeys.DAMAGE_VS_LOW_LIFE, java.util.Set.of()); // 2026-10：輔助寶石（處刑）也算
            final org.bukkit.attribute.AttributeInstance maxHealth = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (vsLow > 0 && maxHealth != null && defender.getHealth() <= maxHealth.getValue() * 0.35) {
                conditional.add(Modifier.increased(StatKeys.DAMAGE, vsLow / 100.0, "低血的敵人"));
            }
            // 2026-10：輔助寶石的條件：對滿血的敵人、魔力 90% 以上、比敵人低血（背水）
            final StatSheet supportSheet = skill.effective(attackerSheet);
            final double defenderFraction = maxHealth == null || maxHealth.getValue() <= 0 ? 1 : defender.getHealth() / maxHealth.getValue();
            if (defenderFraction >= 0.999) {
                final double fullMore = supportSheet.more(StatKeys.DAMAGE_VS_FULL_LIFE, hitTags(skill));
                if (fullMore != 1) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, fullMore - 1, "對滿血的敵人"));
                }
                final double fullCrit = supportSheet.more(StatKeys.CRIT_VS_FULL_LIFE, hitTags(skill));
                if (fullCrit != 1) {
                    conditional.add(Modifier.more(StatKeys.CRIT_CHANCE, fullCrit - 1, "對滿血的敵人"));
                }
            }
            final PlayerState selfState = plugin.players().get((Player) attacker);
            final double highMana = supportSheet.more(StatKeys.DAMAGE_HIGH_MANA, hitTags(skill));
            if (highMana != 1 && selfState.maxMana() > 0 && selfState.mana() >= selfState.maxMana() * 0.9) {
                conditional.add(Modifier.more(StatKeys.DAMAGE, highMana - 1, "魔力充沛"));
            }
            final double clash = supportSheet.flat(StatKeys.DAMAGE_CLASH, hitTags(skill));
            if (clash > 0 && selfState.maxLife() > 0) {
                final double selfFraction = selfState.life() / selfState.maxLife();
                if (selfFraction < defenderFraction - 1e-6) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, clash / 100.0, "背水"));
                } else if (selfFraction > defenderFraction + 1e-6) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, -clash / 200.0, "背水"));
                }
            }
            final double perLife = attackerSheet.flat(StatKeys.CRIT_BONUS_PER_LIFE, java.util.Set.of());
            if (perLife > 0) {
                final double current = plugin.players().get((Player) attacker).life();
                conditional.add(Modifier.increased(StatKeys.CRIT_BONUS, Math.floor(current / 50) * perLife / 100.0, "血鋒"));
            }
            // 2026-10：對稀有、傳奇怪物（含 Boss、訓練假人・首領）增加傷害
            if (defenderMonster != null) {
                final boolean unique = defenderMonster.boss() || defenderMonster.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE;
                final boolean rare = unique || defenderMonster.rarity() == tw.exilecore.monster.MonsterRarity.RARE;
                final StatSheet withSupports = skill.effective(attackerSheet);
                final double vsRare = rare ? withSupports.increased(StatKeys.DAMAGE_VS_RARE, java.util.Set.of()) : 0;
                final double vsUnique = unique ? withSupports.increased(StatKeys.DAMAGE_VS_UNIQUE, java.util.Set.of()) : 0;
                if (vsRare + vsUnique != 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, vsRare + vsUnique, "對稀有、傳奇"));
                }
            }
            // 2026-10：條件傷害：對被詛咒的敵人、對近身的敵人
            final StatSheet conditionSheet = skill.effective(attackerSheet);
            final boolean cursedTarget = debuffs.cursed(defender.getUniqueId(), debuffTick);
            final boolean closeTarget = attacker.getWorld().equals(defender.getWorld())
                    && attacker.getLocation().distanceSquared(defender.getLocation()) <= StatKeys.CLOSE_RANGE * StatKeys.CLOSE_RANGE;
            for (final String stat : new String[] {StatKeys.DAMAGE_VS_CURSED, StatKeys.DAMAGE_CLOSE}) {
                if (stat.equals(StatKeys.DAMAGE_VS_CURSED) ? !cursedTarget : !closeTarget) {
                    continue;
                }
                final double inc = conditionSheet.increased(stat, hitTags(skill));
                final double more = conditionSheet.more(stat, hitTags(skill));
                if (inc != 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, inc, stat.equals(StatKeys.DAMAGE_CLOSE) ? "近身" : "對詛咒的敵人"));
                }
                if (more != 1) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, more - 1, stat.equals(StatKeys.DAMAGE_CLOSE) ? "近身" : "對詛咒的敵人"));
                }
            }
            // 2026-10：對身上有某種異常的敵人（冰緩、凍結、點燃、感電、中毒、流血）
            if (ailments != null) {
                for (final tw.exilecore.ailment.AilmentType type : new tw.exilecore.ailment.AilmentType[] {tw.exilecore.ailment.AilmentType.CHILL,
                        tw.exilecore.ailment.AilmentType.FREEZE, tw.exilecore.ailment.AilmentType.IGNITE, tw.exilecore.ailment.AilmentType.SHOCK,
                        tw.exilecore.ailment.AilmentType.POISON, tw.exilecore.ailment.AilmentType.BLEED}) {
                    final String stat = StatKeys.DAMAGE_VS_AILMENT + ailmentWord(type);
                    final double inc = conditionSheet.increased(stat, hitTags(skill));
                    final double more = conditionSheet.more(stat, hitTags(skill));
                    if ((inc != 0 || more != 1) && (type == tw.exilecore.ailment.AilmentType.FREEZE ? ailments.isFrozen(defender) : ailments.has(defender, type))) {
                        if (inc != 0) {
                            conditional.add(Modifier.increased(StatKeys.DAMAGE, inc, "對異常的敵人"));
                        }
                        if (more != 1) {
                            conditional.add(Modifier.more(StatKeys.DAMAGE, more - 1, "對異常的敵人"));
                        }
                    }
                }
            }
            // 2026-10：連擊（每層）、終式、怒鑄
            if (plugin.skills() != null) {
                final tw.exilecore.skill.SkillResources res = plugin.skills().resources();
                final StatSheet comboSheet = skill.effective(attackerSheet);
                final double perCombo = comboSheet.flat(StatKeys.DAMAGE_PER_COMBO, hitTags(skill));
                final int combo = res.comboForDamage((Player) attacker);
                if (perCombo > 0 && combo > 0) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, perCombo * combo / 100.0, "連擊"));
                }
                final double finisher = comboSheet.flat(StatKeys.COMBO_FINISHER, hitTags(skill));
                if (finisher > 0 && res.finisher((Player) attacker)) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, finisher / 100.0, "終式"));
                }
                final double forged = comboSheet.flat(StatKeys.RAGEFORGED, hitTags(skill));
                if (forged > 0 && res.enraged((Player) attacker)) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, forged / 100.0, "怒鑄"));
                }
            }
            // 2026-10：怒氣：每點增加 1% 攻擊傷害
            if (plugin.skills() != null) {
                final int rage = plugin.skills().resources().rage((Player) attacker);
                if (rage > 0) {
                    final double per = 0.01 * Math.max(0, 1 + attackerSheet.increased(StatKeys.RAGE_EFFECT, java.util.Set.of()));
                    conditional.add(new Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, rage * per, java.util.Set.of(Tags.ATTACK), "怒氣"));
                }
            }
            if (!conditional.isEmpty()) {
                hitSheet = hitSheet.overlay(conditional);
            }
        }
        // 壁壘（PoE2 基石）：翻滾中受到的擊中傷害更少 30%
        if (defenderIsPlayer && dodge != null && dodge.rolling((Player) defender) && StatKeys.flag(defenderSheet, StatKeys.FLAG_BULWARK)) {
            signature *= 0.7;
        }
        // PoE2：玩家能閃避攻擊與法術的擊中（Boss 的法術、預警的大招不能閃）；玩家的法術不擲命中
        final boolean evadable = (skill.isAttack() || defenderIsPlayer && !isBoss(attacker))
                && !skill.tags().contains(DodgeRoll.UNDODGEABLE_TAG); // 2026-10：全場大招閃避也躲不掉，要站安全區
        final java.util.UUID entropyKey = defender.getUniqueId();
        final HitResult result = DamageCalculator.calculate(hitSheet, skill, hitDefender, defenderEs, taken * pvpScale * signature,
                plugin.items().random(), chance -> entropy.hits(entropyKey, chance, hitTick, plugin.items().random()), evadable);
        // 獰笑焚身（煉獄使者）：暴擊時自己著火
        if (attackerIsPlayer && result.crit() && ailments != null) {
            final double selfBurn = attackerSheet.flat(StatKeys.SELF_IGNITE_ON_CRIT, java.util.Set.of());
            if (selfBurn > 0) {
                final PlayerState burner = plugin.players().get((Player) attacker);
                ailments.igniteSelf((Player) attacker, (burner.maxLife() + burner.maxEnergyShield()) * selfBurn / 100.0, 4);
            }
        }
        if (attackerIsPlayer && result.hit() && !result.blocked()) {
            // 2026-10：「最近擊中過敵人」「最近近戰／投射物攻擊擊中過」「最近暴擊過」的條件
            final PlayerState hitter = plugin.players().get((Player) attacker);
            final long tick = plugin.getServer().getCurrentTick();
            hitter.markRecent(tw.exilecore.stats.Recently.ENEMY_HIT, tick);
            if (skill.tags().contains(tw.exilecore.stats.Tags.MELEE)) {
                hitter.markRecent(tw.exilecore.stats.Recently.MELEE_HIT, tick);
            }
            if (skill.tags().contains(tw.exilecore.stats.Tags.ATTACK) && skill.tags().contains(tw.exilecore.stats.Tags.PROJECTILE)) {
                hitter.markRecent(tw.exilecore.stats.Recently.PROJECTILE_HIT, tick);
            }
            if (result.crit()) {
                hitter.markRecent(tw.exilecore.stats.Recently.CRIT, tick);
            }
        }
        if (attackerIsPlayer && defenderMonster != null && ailments != null && result.hit() && !result.blocked()) {
            final StatSheet breakSheet = skill.effective(attackerSheet);
            final java.util.Set<String> breakContext = skill.contextFor(DamageType.PHYSICAL);
            final double breakPercent = breakSheet.apply(StatKeys.ARMOUR_BREAK, breakSheet.flat(StatKeys.ARMOUR_BREAK, breakContext), breakContext); // 2026-10：「增加 x% 破甲」也算
            if (breakPercent > 0) {
                if (ailments.breakArmour(defender, defenderMonster, result.outgoing().get(DamageType.PHYSICAL), breakPercent, rawArmour)
                        && plugin.skills() != null) {
                    plugin.skills().procEvent((Player) attacker, skill.id(), "armour_broken", null, defender); // 輔助：完全破甲時（2026-10）
                }
            }
        }
        if (trinityHit && result.hit() && !result.blocked()) {
            trinity.hit(attacker.getUniqueId(), result.dealt(), hitTick);
        }
        final HitEvent hitEvent = new HitEvent(attacker, defender, skill, result, credit);
        plugin.getServer().getPluginManager().callEvent(hitEvent);
        if (hitEvent.isCancelled() || !result.hit() || result.blocked()) {
            event.setCancelled(true);
            feedback(attacker, defender, result);
            if (attackerIsPlayer && (!defenderIsPlayer || pvpAttacker != null) && !hitEvent.isCancelled()) {
                numbers.word((Player) attacker, defender, result.blocked() ? "格擋" : "未命中"); // 召喚物沒打中不洗版
            }
            if (defenderIsPlayer && !hitEvent.isCancelled()) {
                numbers.takenWord((Player) defender, result.blocked() ? "格擋" : "閃避"); // 自己閃掉、擋掉
                if (result.blocked()) {
                    plugin.players().get((Player) defender).markRecent(tw.exilecore.stats.Recently.BLOCK, plugin.getServer().getCurrentTick());
                }
                if (signatures != null) {
                    signatures.avoided((Player) defender, defenderSheet); // 虛步
                }
            }
            return;
        }
        if (ailments != null && !defenderIsMinion) {
            // 點燃、冰緩、凍結、感電：等事件確定沒被其他插件取消再算（召喚物不會中異常；召喚物點燃的算主人點的）
            if (defenderMonster != null && StatKeys.flag(skill.effective(attackerSheet), StatKeys.FLAG_STUN_FULL_LIFE)) {
                ailments.noteFullLife(defender); // 2026-10：重暈滿血的敵人（傷害寫進去之前先看）
            }
            pendingAilments.put(defender.getUniqueId(),
                    new PendingAilment(credit != null ? credit : attacker, skill, attackerSheet, defenderSheet, result, taken, defenderMonster));
        }

        if (defenderIsPlayer) {
            // 被近戰打到：打完之後荊棘反彈給攻擊的怪物（遠程、法術不會）。怪物的普攻一律標成近戰，
            // 所以還要看這一下是不是牠本人直接打的（箭、火球、喚魔者的尖牙打到時 damager 是投射物或效果，不算）
            final boolean directHit = event.getDamager() == attacker && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
            final LivingEntity thornsTarget = !attackerIsPlayer && !attackerIsMinion && Thorns.triggersOn(directHit, skill.tags()) ? attacker : null;
            if (result.deflected()) {
                numbers.takenWord((Player) defender, "偏斜"); // 2026-10：偏斜的那一下傷害更少
            }
            applyToPlayer((Player) defender, defenderState, result.esDamage(), result.lifeDamage(), event,
                    describeSource(attacker), describeSkill(attacker, skill, event.getCause()), result.dealt(), result.outgoing(), thornsTarget,
                    (!attackerIsPlayer && !attackerIsMinion) || pvpAttacker != null, pvpAttacker);
            if (pvpAttacker != null) {
                pvpRewards(attackerIsPlayer, credit, attackerSheet, skill, result, defender);
            }
            if (!attackerIsPlayer && !attackerIsMinion) {
                monsterLeech(attacker, attackerSheet, result); // 地圖詞綴：怪物偷取
            }
        } else if (defenderIsMinion) {
            // 召喚物跟怪物一樣：原版血量只是比例
            event.setDamage(Math.max(0.01, result.total() * minions.healthScale(defender)));
            if (pvpAttacker != null) {
                pvpRewards(attackerIsPlayer, credit, attackerSheet, skill, result, defender);
            }
        } else {
            // 怪物的原版血量上限 1024，生命更高的怪物按比例縮放
            event.setDamage(plugin.monsters().toVanillaDamage(defender, defenderMonster, result.total()));
            // 撲殺（2026-10，輔助寶石）：這一下之後生命低於門檻就直接打死
            // 撲殺：換階段鎖血中的 Boss 不能撲殺（2026-10：不然收命、收魂照給，Boss 卻沒死）
            if ((attackerIsPlayer || attackerIsMinion) && StatKeys.flag(skill.effective(attackerSheet), StatKeys.FLAG_CULLING)
                    && (plugin.maps() == null || plugin.maps().phaseFloor(defender) <= 0)) {
                if (cull(event, defender, defenderMonster) && attackerIsPlayer) {
                    // 2026-10：收命、收魂（斷命打死時回復）
                    final StatSheet cullSheet = skill.effective(attackerSheet);
                    final double lifePct = cullSheet.flat(StatKeys.LIFE_ON_CULL, skill.tags());
                    final double manaPct = cullSheet.flat(StatKeys.MANA_ON_CULL, skill.tags());
                    if (lifePct > 0 || manaPct > 0) {
                        final PlayerState culler = plugin.players().get((Player) attacker);
                        culler.setLife(Math.min(culler.maxLife(), culler.life() + culler.maxLife() * lifePct / 100.0));
                        culler.setMana(Math.min(culler.maxMana(), culler.mana() + culler.maxMana() * manaPct / 100.0));
                        plugin.players().display((Player) attacker, culler);
                    }
                }
            }
            if (credit != null) {
                final double lifeOnHit = skill.effective(attackerSheet).flat(StatKeys.LIFE_ON_HIT, skill.tags());
                if (attackerIsPlayer) {
                    leech(credit, attackerSheet, skill, result);
                    if (lifeOnHit > 0) {
                        pendingLifeGains.put(defender.getUniqueId(), new LifeGain(credit, lifeOnHit));
                    }
                } else if (lifeOnHit > 0) {
                    minions.heal(attacker, lifeOnHit); // 召喚物的擊中回復生命回給牠自己
                }
                // 真正扣掉的生命（溢出的不算）：剩下的原版血量換回我們的生命
                final double scale = plugin.monsters().healthScale(defender, defenderMonster);
                final double remaining = scale > 0 ? defender.getHealth() / scale : result.total();
                pendingCredits.put(defender.getUniqueId(), new Credit(credit.getUniqueId(), Math.min(result.total(), remaining)));
                pendingNumbers.put(defender.getUniqueId(), new PendingNumber(credit, result.total(), result.crit(), result.dealt(), attackerIsPlayer,
                        chargeChances(attackerIsPlayer, skill, attackerSheet)));
            }
        }
        if (!defenderIsPlayer && !defenderIsMinion && plugin.maps() != null) {
            // 換階段鎖血（2026-10）：還沒進下一個階段，這一下最多打到門檻（撲殺也一樣），然後馬上換階段
            final double floor = plugin.maps().phaseFloor(defender);
            if (floor > 0 && defender.getHealth() - event.getDamage() < floor) {
                event.setDamage(Math.max(0, defender.getHealth() - floor));
                plugin.maps().phaseLocked(defender);
            }
        }
        if (signatures != null && attackerIsPlayer) {
            signatures.dealt((Player) attacker, attackerSheet, skill, result); // 雷魂、血宴
        }
        feedback(attacker, defender, result);
    }

    /** 撲殺的門檻（剩下的生命比例）：普通 30%、魔法 20%、稀有 10%、傳奇與 Boss 5%。 */
    static double cullThreshold(final MonsterData monster) {
        if (monster == null) {
            return 0;
        }
        if (monster.boss()) {
            return 0.05;
        }
        return switch (monster.rarity()) {
            case NORMAL -> 0.30;
            case MAGIC -> 0.20;
            case RARE -> 0.10;
            case UNIQUE -> 0.05;
        };
    }

    private static boolean cull(final EntityDamageByEntityEvent event, final LivingEntity defender, final MonsterData monster) {
        final double threshold = cullThreshold(monster);
        final org.bukkit.attribute.AttributeInstance max = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (threshold <= 0 || max == null || max.getValue() <= 0) {
            return false;
        }
        final double left = defender.getHealth() - event.getDamage();
        if (left > 0 && left <= max.getValue() * threshold) {
            event.setDamage(defender.getHealth() + 1); // 撲殺：直接打死
            return true;
        }
        return false;
    }

    /** 異常狀態在屬性代號裡的寫法（damage.vs_chilled…）。 */
    static String ailmentWord(final tw.exilecore.ailment.AilmentType type) {
        return switch (type) {
            case CHILL -> "chilled";
            case FREEZE -> "frozen";
            case IGNITE -> "ignited";
            case SHOCK -> "shocked";
            case POISON -> "poisoned";
            case BLEED -> "bleeding";
            default -> type.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** 是不是 Boss（地圖、區域的 Boss；護盾結晶、召喚的小怪不算）。 */
    private boolean isBoss(final LivingEntity entity) {
        return !(entity instanceof Player) && plugin.monsters() != null
                && plugin.monsters().data(entity).map(tw.exilecore.monster.MonsterData::boss).orElse(false);
    }

    /** 生命與魔力偷取（飲血輔助、詞綴）：造成的物理傷害有 x% 存進偷取，之後慢慢回。 */
    private void leech(final Player player, final StatSheet sheet, final SkillProfile skill, final HitResult result) {
        final StatSheet effective = skill.effective(sheet);
        if (StatKeys.flag(effective, StatKeys.FLAG_NO_LEECH)) {
            return; // 地圖詞綴：無法從怪物身上偷取
        }
        double life = 0;
        double mana = 0;
        final double physical = result.dealt().get(DamageType.PHYSICAL);
        if (physical > 0) {
            final java.util.Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            life += physical * effective.flat(StatKeys.LEECH_LIFE, context) / 100.0;
            mana += physical * effective.flat(StatKeys.LEECH_MANA, context) / 100.0;
        }
        // 2026-10：元素傷害偷取為生命
        final boolean elementalAll = StatKeys.flag(effective, StatKeys.FLAG_LEECH_ELEMENTAL_ALL);
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            final double dealt = result.dealt().get(type);
            if (dealt > 0) {
                life += dealt * effective.flat(StatKeys.LEECH_LIFE_ELEMENTAL, skill.contextFor(type)) / 100.0;
                if (elementalAll) {
                    // 神秘收割（亞馬遜）：元素傷害也照物理的偷取率偷取
                    life += dealt * effective.flat(StatKeys.LEECH_LIFE, skill.contextFor(DamageType.PHYSICAL)) / 100.0;
                }
            }
        }
        // 夢魘之汁（夏烏拉）：混沌傷害也照物理的偷取率偷取
        final double chaosDealt = result.dealt().get(DamageType.CHAOS);
        if (chaosDealt > 0 && StatKeys.flag(effective, StatKeys.FLAG_LEECH_CHAOS)) {
            life += chaosDealt * effective.flat(StatKeys.LEECH_LIFE, skill.contextFor(DamageType.PHYSICAL)) / 100.0;
        }
        // 生命虹吸（血術士）：法術傷害的 x% 偷取為生命
        if (!skill.isAttack()) {
            life += result.dealt().total() * effective.flat(StatKeys.LEECH_SPELL, java.util.Set.of()) / 100.0;
        }
        if (life <= 0 && mana <= 0) {
            return;
        }
        final double scale = Math.max(0, effective.apply(StatKeys.LEECH_EFFECT, 1.0, java.util.Set.of()));
        life *= scale;
        mana *= scale;
        // 立即偷取（2026-10，照 PoE）：x% 立即生效，其餘照常存起來慢慢回；一下最多立即回 {@value #INSTANT_LEECH_CAP} 的最大生命／魔力
        final double instant = StatKeys.flag(effective, StatKeys.FLAG_INSTANT_LEECH) ? 1.0
                : Math.max(0, Math.min(1, effective.flat(StatKeys.LEECH_INSTANT, java.util.Set.of()) / 100.0));
        final PlayerState state = plugin.players().get(player);
        boolean changed = false;
        if (life > 0) {
            final double now = Math.min(life * instant, state.maxLife() * INSTANT_LEECH_CAP);
            if (now > 0 && state.life() < state.maxLife()) {
                state.setLife(Math.min(state.maxLife(), state.life() + now));
                changed = true;
            }
            state.addLeech(life - now);
        }
        if (mana > 0) {
            final double now = Math.min(mana * instant, state.maxMana() * INSTANT_LEECH_CAP);
            if (now > 0 && state.mana() < state.maxMana()) {
                state.setMana(Math.min(state.maxMana(), state.mana() + now));
                changed = true;
            }
            state.addManaLeech(mana - now);
        }
        if (changed) {
            plugin.players().display(player, state);
        }
    }

    /** 立即偷取一下最多回多少（最大生命／魔力的比例）。 */
    static final double INSTANT_LEECH_CAP = 0.06;

    /** 地圖詞綴「怪物擊中時偷取 x% 傷害為生命」（2026-10）：怪物打到玩家，照扣掉的量回血。 */
    private void monsterLeech(final LivingEntity monster, final StatSheet sheet, final HitResult result) {
        final double percent = sheet.flat(StatKeys.LEECH_LIFE, java.util.Set.of());
        if (percent <= 0 || monster.isDead()) {
            return;
        }
        final Optional<MonsterData> data = plugin.monsters().data(monster);
        if (data.isEmpty()) {
            return;
        }
        final double heal = result.total() * percent / 100.0 * plugin.monsters().healthScale(monster, data.get());
        final org.bukkit.attribute.AttributeInstance max = monster.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (heal > 0 && max != null) {
            monster.setHealth(Math.min(max.getValue(), monster.getHealth() + heal));
        }
    }

    /** 競技場裡打中敵方玩家或召喚物：跟打怪一樣冒傷害數字；玩家本人打的還有偷取與擊中回復生命（等事件確定沒被取消）。 */
    private void pvpRewards(final boolean attackerIsPlayer, final Player credit, final StatSheet attackerSheet, final SkillProfile skill,
                            final HitResult result, final LivingEntity defender) {
        if (credit == null) {
            return;
        }
        pendingNumbers.put(defender.getUniqueId(), new PendingNumber(credit, result.total(), result.crit(), result.dealt(), attackerIsPlayer,
                chargeChances(attackerIsPlayer, skill, attackerSheet)));
        if (!attackerIsPlayer) {
            return;
        }
        leech(credit, attackerSheet, skill, result);
        final double lifeOnHit = skill.effective(attackerSheet).flat(StatKeys.LIFE_ON_HIT, skill.tags());
        if (lifeOnHit > 0) {
            pendingLifeGains.put(defender.getUniqueId(), new LifeGain(credit, lifeOnHit));
        }
    }

    /** 荊棘：對剛打到玩家的怪物反彈一筆物理傷害（算玩家的攻擊：吃玩家的屬性表、擊殺算玩家的）。 */
    private void reflect(final Player player, final LivingEntity target) {
        final double[] range = Thorns.range(plugin.players().sheet(player));
        if (range == null || !target.isValid() || target.isDead()) {
            return;
        }
        skills.hit(player, target, Thorns.profile(range[0], range[1]), null);
    }

    /** 環境傷害（掉落、火、岩漿、溺水…）只處理玩家。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironmentDamage(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            if (event.getEntity() instanceof LivingEntity living && isBoss(living)
                    && !BossDamageRules.takesEnvironment(event.getCause().name())) {
                event.setCancelled(true); // Boss 不吃窒息、摔落、火、爆炸這些原版傷害（沒經過換算，見 BossDamageRules）
            }
            return;
        }
        final EnvironmentDamage.Mapping mapping = environment.mapping(event.getCause());
        if (mapping == null) {
            return; // 未設定的原因照原版（例如 /kill）
        }
        final PlayerState state = plugin.players().get(player);
        if (mapping.periodic()) {
            final long now = plugin.getServer().getCurrentTick();
            final Long last = environmentCooldown.get(player.getUniqueId());
            if (last != null && now - last < EnvironmentDamage.PERIODIC_INTERVAL_TICKS) {
                event.setCancelled(true);
                return;
            }
            environmentCooldown.put(player.getUniqueId(), now);
        }
        final double amount = event.getDamage() * mapping.multiplier() * state.maxLife() / 40.0;
        final DamagePacket outgoing = new DamagePacket().set(mapping.type(), amount);
        final DamagePacket dealt = mapping.ignoreMitigation() ? outgoing : DamageCalculator.mitigate(outgoing, plugin.players().sheet(player));
        if (plugin.ailments() != null) {
            dealt.scale(plugin.ailments().damageTakenMultiplier(player)); // 感電也讓環境傷害更痛
        }
        double es = state.energyShield();
        double esDamage = 0;
        double lifeDamage = dealt.total();
        if (es > 0) {
            final double rate = mapping.type() == DamageType.CHAOS ? DamageCalculator.CHAOS_ES_MULTIPLIER : 1.0; // PoE2：混沌對護盾兩倍
            esDamage = Math.min(es, lifeDamage * rate);
            lifeDamage -= esDamage / rate;
        }
        applyToPlayer(player, state, esDamage, lifeDamage, event, "環境", causeName(event.getCause()), dealt, outgoing, null, false, null);
    }

    /**
     * 準備扣玩家的護盾與生命：原版傷害改成 0，致死時改成致死值讓原版處理死亡。
     * 真正扣血在 {@link #onDamageMonitor}，這樣後面優先度更高的插件（領地保護、PvP 開關）取消事件時我們不會白扣。
     */
    private void applyToPlayer(final Player player, final PlayerState state, final double esDamage,
                               final double lifeDamage, final EntityDamageEvent event,
                               final String source, final String skillName, final DamagePacket dealt, final DamagePacket outgoing,
                               final LivingEntity thornsTarget, final boolean byEnemy, final Player pvpAttacker) {
        final StatSheet sheet = plugin.players().sheet(player);
        double esTaken = esDamage;
        double lifeTaken = lifeDamage;
        if (byEnemy) {
            // 相位形態（時空術士）：受到的傷害更少 30%，4 秒後再受到那一擊 30% 的傷害
            if (StatKeys.flag(sheet, StatKeys.FLAG_PHASED_FORM) && esTaken + lifeTaken > 0) {
                final double later = (esTaken + lifeTaken) * 0.3;
                esTaken *= 0.7;
                lifeTaken *= 0.7;
                delayedLoss(player, later, 80, 1, true);
            }
            // 緊握傷口（血術士）：擊中扣的生命有 25% 改成 4 秒內慢慢扣
            if (StatKeys.flag(sheet, StatKeys.FLAG_GRASPING_WOUNDS) && lifeTaken > 0) {
                final double later = lifeTaken * 0.25;
                lifeTaken -= later;
                delayedLoss(player, later, 10, 8, false);
            }
        }
        // 守護（2026-10，護符詞綴）：效果期間先替你吃掉傷害（先吃護盾那份、再吃生命那份）
        if (esTaken + lifeTaken > 0 && state.guard(plugin.getServer().getCurrentTick()) > 0) {
            final long now = plugin.getServer().getCurrentTick();
            final double fromEs = state.absorbGuard(esTaken, now);
            esTaken -= fromEs;
            final double fromLife = state.absorbGuard(lifeTaken, now);
            lifeTaken -= fromLife;
        }
        // 傳奇「受到的傷害先從魔力扣」與「受到的傷害有 x% 之後慢慢回成生命」
        final IncomingDamage split = IncomingDamage.split(esTaken, lifeTaken, state.mana(),
                sheet.flat(StatKeys.DAMAGE_FROM_MANA, java.util.Set.of()), sheet.flat(StatKeys.LIFE_RECOUP, java.util.Set.of()));
        final boolean lethal = state.life() - split.life() <= 0;
        event.setDamage(lethal ? LETHAL : 0);
        pending.put(player.getUniqueId(), new Pending(split.es(), split.life(), split.mana(), split.recoup(), source, skillName, dealt, outgoing,
                thornsTarget, byEnemy, pvpAttacker));
    }

    /**
     * 延後扣的傷害（PoE2 昇華）：delay tick 之後開始，分 steps 次每 delay tick 扣一次（steps = 1 就是一次扣完）。
     * shieldFirst：先扣能量護盾。扣到 0 就死。
     */
    private void delayedLoss(final Player player, final double amount, final long delay, final int steps, final boolean shieldFirst) {
        final double each = amount / steps;
        for (int i = 1; i <= steps; i++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                final PlayerState state = plugin.players().get(player);
                if (state.isDead() || plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
                    return;
                }
                double rest = each;
                if (shieldFirst && state.energyShield() > 0) {
                    final double absorbed = Math.min(state.energyShield(), rest);
                    state.setEnergyShield(state.energyShield() - absorbed);
                    rest -= absorbed;
                }
                state.setLife(state.life() - rest);
                if (state.life() <= 0) {
                    player.setHealth(0);
                } else {
                    plugin.players().display(player, state);
                }
            }, delay * i);
        }
    }

    /** 死亡回顧裡「誰打的」：怪物名稱（等級、稀有度）、玩家名稱。 */
    private String describeSource(final LivingEntity attacker) {
        if (attacker instanceof Player player) {
            return tw.exilecore.item.ItemRenderer.escape(plugin.chat() == null ? player.getName() : plugin.chat().display(player));
        }
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        if (minions != null && minions.isMinion(attacker)) {
            final Player owner = minions.owner(attacker);
            return owner == null ? "召喚物"
                    : tw.exilecore.item.ItemRenderer.escape(plugin.chat() == null ? owner.getName() : plugin.chat().display(owner)) + " 的召喚物";
        }
        final Optional<MonsterData> data = plugin.monsters().data(attacker);
        if (data.isEmpty()) {
            return attacker.getType().name();
        }
        return DeathRecap.describe(data.get().name(), data.get().level(),
                data.get().rarity() == tw.exilecore.monster.MonsterRarity.NORMAL ? null : data.get().rarity().displayName());
    }

    /** 死亡回顧裡「哪一招」：Boss／小怪的招式名稱、玩家的技能名稱，其他照原版的原因（普攻、射擊、爆炸）。 */
    private String describeSkill(final LivingEntity attacker, final SkillProfile skill, final EntityDamageEvent.DamageCause cause) {
        final String id = skill.id();
        if (id.startsWith("boss.") && plugin.skills() != null) {
            final tw.exilecore.skill.BossSkill boss = plugin.skills().config().bossSkills().skill(id.substring("boss.".length()));
            if (boss != null) {
                return boss.name();
            }
        }
        if (!"monster_attack".equals(id) && !"default_attack".equals(id) && plugin.skills() != null) {
            final tw.exilecore.skill.SkillDefinition definition = plugin.skills().config().skill(id);
            if (definition != null) {
                return definition.name();
            }
        }
        return switch (cause) {
            case PROJECTILE -> "射擊";
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "爆炸";
            case ENTITY_SWEEP_ATTACK -> "橫掃";
            case THORNS -> "荊棘";
            case MAGIC -> "法術";
            default -> "普攻";
        };
    }

    /** 環境傷害的原因名稱。 */
    static String causeName(final EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case FALL -> "掉落";
            case FIRE, FIRE_TICK -> "燃燒";
            case LAVA -> "岩漿";
            case HOT_FLOOR -> "岩漿塊";
            case CAMPFIRE -> "營火";
            case LIGHTNING -> "閃電";
            case FREEZE -> "凍傷";
            case DROWNING -> "溺水";
            case SUFFOCATION -> "窒息";
            case CONTACT -> "尖刺";
            case CRAMMING -> "擠壓";
            case POISON -> "中毒";
            case WITHER -> "凋零";
            case MAGIC -> "藥水";
            case DRAGON_BREATH -> "龍息";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "爆炸";
            case FALLING_BLOCK -> "落石";
            case VOID -> "虛空";
            case STARVATION -> "飢餓";
            default -> cause.name().toLowerCase(Locale.ROOT);
        };
    }

    /** 事件塵埃落定後才把算好的傷害寫進狀態，再算元素異常。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageMonitor(final EntityDamageEvent event) {
        final PendingAilment ailment = pendingAilments.remove(event.getEntity().getUniqueId());
        final Credit credit = pendingCredits.remove(event.getEntity().getUniqueId());
        if (credit != null && !event.isCancelled()) {
            plugin.monsters().ledger().record(event.getEntity().getUniqueId(), credit.player(), credit.amount());
        }
        final PendingNumber number = pendingNumbers.remove(event.getEntity().getUniqueId());
        if (number != null && !event.isCancelled() && event.getEntity() instanceof LivingEntity target && number.player().isOnline()) {
            numbers.hit(number.player(), target, number.amount(), number.crit(), number.dealt());
            if (hud != null) {
                hud.dealt(number.player(), number.dealt().asMap()); // 戰鬥數據：自己與召喚物打出的
            }
            if (number.own() && number.crit() && charges != null) {
                charges.onCrit(number.player()); // 銳星（自己的暴擊；召喚物的不算）
            }
            if (number.own() && charges != null) {
                charges.onDealtHit(number.player(), number.chargeChances()); // 擊中時獲得戰星（輔助寶石、詞綴、天賦）
            }
        }
        final LifeGain gain = pendingLifeGains.remove(event.getEntity().getUniqueId());
        if (gain != null && !event.isCancelled() && gain.player().isOnline()) {
            final PlayerState attackerState = plugin.players().get(gain.player());
            if (attackerState.hasCharacter() && !attackerState.isDead() && attackerState.life() < attackerState.maxLife()) {
                attackerState.setLife(attackerState.life() + gain.amount());
                plugin.players().display(gain.player(), attackerState);
            }
        }
        if (event.getEntity() instanceof Player player) {
            final Pending damage = pending.remove(player.getUniqueId());
            if (damage != null && !event.isCancelled()) {
                final PlayerState state = plugin.players().get(player);
                final double esBefore = state.energyShield();
                state.setEnergyShield(state.energyShield() - damage.esDamage());
                state.setLife(state.life() - damage.lifeDamage());
                if (damage.manaDamage() > 0) {
                    state.setMana(state.mana() - damage.manaDamage());
                }
                state.setLastDamageTick(plugin.getServer().getCurrentTick());
                state.setLastHitTick(plugin.getServer().getCurrentTick()); // 2026-10：「最近被擊中」的條件
                if (damage.pvpAttacker() != null && plugin.pvp() != null) {
                    plugin.pvp().recordHit(player, damage.pvpAttacker()); // 競技場：擊倒算最後打到的人
                }
                recap.record(player, new DeathRecap.Hit(plugin.getServer().getCurrentTick(), damage.source(), damage.skill(),
                        damage.dealt().asMap(), damage.outgoing().asMap(), Math.min(esBefore, damage.esDamage()), damage.lifeDamage(),
                        state.life(), state.maxLife(), false));
                final double lost = Math.min(esBefore, damage.esDamage()) + damage.lifeDamage() + damage.manaDamage();
                numbers.taken(player, lost, damage.dealt(), state.maxLife());
                if (hud != null) {
                    hud.taken(player, damage.dealt().asMap(), lost); // 戰鬥數據：受到的
                }
                if (!state.isDead()) {
                    if (damage.recoup() > 0) {
                        state.addLeech(damage.recoup());
                    }
                    plugin.players().display(player, state);
                    if (damage.byEnemy() && charges != null) {
                        charges.onHit(player); // 磐星
                    }
                    if (damage.thornsTarget() != null) {
                        reflect(player, damage.thornsTarget());
                    }
                }
            }
        }
        if (ailment != null && !event.isCancelled() && plugin.ailments() != null && event.getEntity() instanceof LivingEntity defender) {
            plugin.ailments().onHit(ailment.attacker(), defender, ailment.skill(), ailment.attackerSheet(), ailment.defenderSheet(),
                    ailment.result(), ailment.taken(), ailment.defenderMonster());
        }
    }

    /** 攻速限制：這次攻擊是否允許，允許就排下一次可攻擊的 tick。 */
    private boolean consumeAttack(final Player player, final PlayerState state, final StatSheet sheet) {
        final long now = plugin.getServer().getCurrentTick();
        if (now < state.nextAttackTick()) {
            return false;
        }
        final double speed = plugin.ailments() == null ? 1.0 : plugin.ailments().actionSpeed(player); // 冰緩：攻擊變慢
        final double aps = DamageCalculator.attacksPerSecond(sheet, SkillProfile.defaultAttack(state.weaponTags()).tags()) * speed;
        state.setNextAttackTick(now + Formulas.attackIntervalTicks(aps));
        return true;
    }

    private static LivingEntity resolveAttacker(final Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof EvokerFangs fangs) {
            return fangs.getOwner(); // 喚魔者的尖牙算喚魔者的攻擊
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource source = projectile.getShooter();
            if (source instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /** 除錯訊息（/ec debug 開啟時）。 */
    private void feedback(final LivingEntity attacker, final LivingEntity defender, final HitResult result) {
        if (attacker instanceof Player player) {
            final PlayerState state = plugin.players().get(player);
            if (state.debug()) {
                player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>你 → %s：%s",
                        name(defender), result.describe())));
            }
        }
        if (defender instanceof Player player) {
            final PlayerState state = plugin.players().get(player);
            if (state.debug()) {
                player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>%s → 你：%s",
                        name(attacker), result.describe())));
            }
        }
    }

    private String name(final LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.getName();
        }
        return plugin.monsters().data(entity).map(MonsterData::name).orElse(entity.getType().name());
    }
}
