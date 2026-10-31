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
     * manaDamage：傳奇「受到的傷害先從魔力扣」那一份；recoup：之後慢慢回成生命的量；manaRecoup：之後慢慢回成魔力的量（2026-10-21）。
     */
    private record Pending(double esDamage, double lifeDamage, double manaDamage, double recoup, double manaRecoup,
                           String source, String skill, DamagePacket dealt, DamagePacket outgoing, LivingEntity thornsTarget,
                           boolean byEnemy, Player pvpAttacker) {
    }

    /** 玩家打中怪物：等事件確定沒被取消（MONITOR）才回的「擊中回復生命」。 */
    private record LifeGain(Player player, double amount) {
    }

    /** 算好、等傷害事件確定沒被取消（MONITOR）才套用的元素異常。 */
    private record PendingAilment(LivingEntity attacker, SkillProfile skill, StatSheet attackerSheet, StatSheet defenderSheet,
                                  HitResult result, double taken, MonsterData defenderMonster, double stunScale,
                                  java.util.Map<tw.exilecore.stats.DamageType, Double> typeTaken) {
        PendingAilment(final LivingEntity attacker, final SkillProfile skill, final StatSheet attackerSheet, final StatSheet defenderSheet,
                       final HitResult result, final double taken, final MonsterData defenderMonster,
                       final java.util.Map<tw.exilecore.stats.DamageType, Double> typeTaken) {
            this(attacker, skill, attackerSheet, defenderSheet, result, taken, defenderMonster, 1.0, typeTaken);
        }

        PendingAilment withStunScale(final double scale) {
            return new PendingAilment(attacker, skill, attackerSheet, defenderSheet, result, taken, defenderMonster, scale, typeTaken);
        }
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
                                 java.util.Map<String, Double> chargeChances, String skillName) {
    }

    /** 技能代號 → 戰鬥數據上的名字（查過的記著）。 */
    private final java.util.Map<String, String> meterNames = new java.util.HashMap<>();

    /**
     * 這一下在戰鬥數據的「技能明細」裡算哪個技能（2026-10）：技能的名字；地面效果、引爆、鐘響這些衍生的（代號後面多一段）算在原本的技能上，
     * 召喚物的攻擊算在召喚技能上；不是技能的（普攻、擊殺爆炸、焚燒光環…）給一個固定的名字。
     */
    /**
     * 2026-10-23：怪物的普攻。原版近戰的出手節奏是固定的，怪物詞綴的「攻擊速度」（極速的、疾風光環）改成照比例加在每一下的傷害上
     * （每秒傷害一樣）；射箭的由 {@link tw.exilecore.monster.RangedGoal} 縮短間隔，這裡不再乘。
     * 2026-10-23：玩家給的減益（詛咒、減攻速）與冰緩也算，攻速變慢一樣照比例少打；只算原版的近戰揮擊（別的插件的技能傷害不算）。
     */
    private SkillProfile monsterAttack(final LivingEntity attacker, final LivingEntity defender, final StatSheet sheet, final boolean melee) {
        final SkillProfile base = SkillProfile.monsterAttack();
        if (!melee) {
            return base;
        }
        final java.util.List<Modifier> mods = debuffs.modifiers(attacker.getUniqueId(), defender.getUniqueId(), plugin.getServer().getCurrentTick());
        final StatSheet effective = mods.isEmpty() ? sheet : sheet.overlay(mods);
        double speed = effective.apply(StatKeys.ATTACK_SPEED, 1.0, base.tags());
        if (plugin.ailments() != null) {
            speed *= plugin.ailments().actionSpeed(attacker);
        }
        return base.scaled(monsterSpeedFactor(speed), "攻擊速度");
    }

    /** 怪物近戰的攻速換成傷害倍率（夾在 0.3～3）。純函式（測試用）。 */
    static double monsterSpeedFactor(final double speed) {
        return Math.max(0.3, Math.min(3.0, speed));
    }

    private String meterName(final SkillProfile skill) {
        final String id = skill.id();
        final String known = meterNames.get(id);
        if (known != null) {
            return known;
        }
        String name = null;
        if (plugin.skills() != null) {
            String probe = id;
            while (name == null) {
                final tw.exilecore.skill.SkillDefinition definition = plugin.skills().config().skill(probe);
                if (definition != null) {
                    name = definition.hidden() && definition.isAttack() ? "普攻" : definition.name();
                    break;
                }
                final int dot = probe.lastIndexOf('.');
                if (dot <= 0) {
                    break;
                }
                probe = probe.substring(0, dot);
            }
        }
        if (name == null) {
            name = switch (id) {
                case "default_attack" -> "普攻";
                case KillEffects.EXPLOSION -> "擊殺爆炸";
                case Presence.BURN_ID -> "焚燒";
                default -> id.startsWith("thorns") ? "荊棘" : "其他";
            };
        }
        if (meterNames.size() > 2000) {
            meterNames.clear(); // 不會到這麼多；保險
        }
        meterNames.put(id, name);
        return name;
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
        if (!own || charges == null || Thorns.ID.equals(skill.id()) || KillEffects.EXPLOSION.equals(skill.id()) || Presence.BURN_ID.equals(skill.id())
                || totemHit(skill)) { // 2026-10-21：圖騰、英靈打中的不給你戰星（照 PoE：圖騰的擊中不算你的）
            return java.util.Map.of();
        }
        return charges.hitChances(skill.effective(attackerSheet), skill.tags());
    }

    private final Map<UUID, PendingNumber> pendingNumbers = new HashMap<>();
    private final Map<UUID, LifeGain> pendingLifeGains = new HashMap<>();
    /**
     * 2026-10-18：算傷害時（HIGH）就會生效的副作用（相位形態／緊握傷口的延遲扣血、守護吃掉的量、偷取、收命收魂）
     * 改成記在這裡，等 MONITOR 確定事件沒被取消才執行；key 是挨打的那一隻，同一隻接連挨打照順序疊在一起。
     */
    private final Map<UUID, java.util.List<Runnable>> pendingEffects = new HashMap<>();

    /** 2026-10-18：記一個等事件確定沒被取消才執行的副作用。 */
    private void defer(final LivingEntity defender, final Runnable effect) {
        pendingEffects.computeIfAbsent(defender.getUniqueId(), uuid -> new java.util.ArrayList<>()).add(effect);
    }
    private final SkillDamage skills;
    private final DamageNumbers numbers;
    private final Debuffs debuffs;
    /** 三相輔助的元素共鳴（2026-10）。 */
    private final Trinity trinity = new Trinity();

    /** 三相輔助的元素共鳴（2026-10-22：畫面上方的戰鬥資源血條要顯示）。 */
    public Trinity trinity() {
        return trinity;
    }
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
            // 2026-10-29 照 PoE2：召喚物替你放的指令（巨靈的指令，SkillDamage 的 proxy）：技能描述照指令的，屬性表照召喚物的
            final SkillDamage.Context context = skills.contextFor(defender);
            skill = context != null && context.attacker() == attacker ? context.profile() : attack.profile();
            credit = attack.owner();
        } else {
            final Optional<MonsterData> data = plugin.monsters().data(attacker);
            if (data.isEmpty()) {
                return; // 沒等級化的生物（例如其他插件的）交給原版
            }

            attackerBase = plugin.monsters().sheet(attacker, data.get());
            final SkillDamage.Context context = skills.contextFor(defender);
            // Boss 的招式（skill 模組）：傷害倍率、類型照招式；其他都是怪物的普攻
            final boolean bySkill = context != null && context.attacker() == attacker;
            if (!bySkill && plugin.maps() != null && plugin.maps().bossSkillsOnly(attacker)) {
                // 2026-10：終局、極境、巔峰、金庫之主的 Boss 不普攻（只用招式；照 PoE1 的賽勒斯）
                event.setCancelled(true);
                return;
            }
            skill = bySkill ? context.profile() : monsterAttack(attacker, defender, attackerBase,
                    event.getDamager() == attacker && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                            || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK));
            credit = null;
        }
        if (credit != null) {
            plugin.monsters().ledger().touch(defender.getUniqueId(), credit.getUniqueId()); // 2026-10-31：擊殺歸屬的最後保底
            plugin.players().grace().end(credit, true); // 打到東西了（召喚物打到也算）：入場保護結束
        }

        // 防禦方
        final StatSheet defenderBase;
        final double defenderEs;
        PlayerState defenderState = null;
        MonsterData defenderMonster = null;
        // 2026-10-27 照 PoE2：翻滾開頭只躲得開近戰攻擊與投射物（沒有無敵時間；這次之前全部的擊中都躲得開）
        if (defenderIsPlayer && dodge != null && dodge.invulnerable((Player) defender)
                && (skill == null || !skill.tags().contains(DodgeRoll.UNDODGEABLE_TAG))
                && (dodge.iframe((Player) defender) // 2026-10-31 照站長：一開始 0.1 秒什麼都躲得開
                || DodgeRoll.avoids(skill == null || skill.isAttack() && skill.tags().contains(tw.exilecore.stats.Tags.MELEE),
                        event.getDamager() instanceof org.bukkit.entity.Projectile || skill != null && skill.tags().contains(tw.exilecore.stats.Tags.PROJECTILE)))) {
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
            defenderEs = minions.energyShield(defender); // 2026-10-26 照 PoE2：焰術士、霜術士、雷術士、祭司有能量護盾；這次之前 0
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
        java.util.List<Modifier> attackerDebuffs = debuffs.modifiers(attacker.getUniqueId(), defender.getUniqueId(), debuffTick);
        if (defenderIsPlayer && !attackerIsPlayer && !attackerIsMinion) {
            // 2026-10-26 照 PoE2 天賦樹：被你標記／釘住的敵人不能暴擊、命中降低（KeystoneEffects）
            attackerDebuffs = KeystoneEffects.monsterAttack(plugin.players().sheet((Player) defender), attacker, debuffs, debuffTick, attackerDebuffs);
        }
        if (attackerIsPlayer && skill.isAttack() && !skill.tags().contains(Tags.TOTEM_HIT) && attacker.getWorld().equals(defender.getWorld())) {
            // 2026-10-23（PoE2）：玩家的攻擊離越遠命中越差（量到目標身體的邊；3 格內不影響，25 格以上命中更少 90%；法術、圖騰、召喚物不算）
            // 2026-10-26 照 PoE2 天賦樹：「增加／減少 x% 遠距離的命中懲罰」
            final double falloff = Math.min(0.95, tw.exilecore.stats.Formulas.accuracyFalloff(
                    Math.max(0, attacker.getLocation().distance(defender.getLocation()) - defender.getWidth() / 2))
                    // 2026-10-28 照 PoE2 Sunder 的品質：技能自己的「減少照距離的命中懲罰」也算（這次之前只看角色身上的）
                    * tw.exilecore.stats.TreeEffectKeys.scale(skill.effective(attackerBase), tw.exilecore.stats.TreeEffectKeys.ACCURACY_RANGE_PENALTY,
                    java.util.Set.of(), 0));
            if (falloff > 0) {
                attackerDebuffs = new java.util.ArrayList<>(attackerDebuffs);
                attackerDebuffs.add(Modifier.more(StatKeys.ACCURACY, -falloff, "距離"));
            }
        }
        final StatSheet attackerSheet = attackerDebuffs.isEmpty() ? attackerBase : attackerBase.overlay(attackerDebuffs);
        // 2026-10-23：這一下的技能屬性表（疊上輔助寶石）與標籤只算一次（以前一下要重疊十幾次，每次都新建一張表）
        final StatSheet skillSheet = skill.effective(attackerSheet);
        final java.util.Set<String> hitTagSet = hitTags(skill);
        final java.util.List<Modifier> defenderDebuffs = debuffs.modifiers(defender.getUniqueId(), attacker.getUniqueId(), debuffTick);
        final StatSheet defenderSheet = defenderDebuffs.isEmpty() ? defenderBase : defenderBase.overlay(defenderDebuffs);

        // 破甲（2026-10 PoE2）：怪物被破掉的護甲從屬性表扣掉；完全破甲 = 沒有護甲，物理擊中受到的傷害增加 20%
        final double rawArmour = defenderSheet.get(StatKeys.ARMOUR);
        double brokenTaken = 0;
        StatSheet hitDefender = defenderSheet;
        if (defenderMonster != null && ailments != null && rawArmour > 0) {
            final double broken = ailments.armourBroken(defender);
            if (broken > 0) {
                // 2026-10-17：照比例扣（rawArmour 已經含增加；以前用 FLAT 扣會再乘一次增加，有「增加護甲」的怪一下就被扣光）
                hitDefender = defenderSheet.overlay(java.util.List.of(Modifier.more(StatKeys.ARMOUR, -Math.min(1.0, broken / rawArmour), "破甲")));
                if (Double.isInfinite(broken) && (hitTags(skill).contains(Tags.PHYSICAL)
                        || attackerIsPlayer && StatKeys.flag(attackerSheet, StatKeys.FLAG_IMPLODING))) { // 內爆衝擊：所有擊中都增加
                    // 2026-10-26 照 PoE2 天賦樹：「增加完全破甲的效果」
                    brokenTaken = ailments.fullyBrokenTaken() * Math.max(0, 1 + attackerSheet.increased(tw.exilecore.stats.TreeKeys.ARMOUR_BREAK_EFFECT, java.util.Set.of()));
                }
            }
        } else if (defenderIsPlayer && pvpAttacker == null && rawArmour > 0) {
            // 2026-10-27 照 PoE2：怪物破了你的護甲（PlayerArmourBreak）：照比例扣；完全破甲＝沒有護甲（玩家不會多受 20% 物理傷害）
            final double less = PlayerArmourBreak.lessFraction(PlayerArmourBreak.broken(defender.getUniqueId(), plugin.getServer().getCurrentTick()), rawArmour);
            if (less > 0) {
                hitDefender = defenderSheet.overlay(java.util.List.of(Modifier.more(StatKeys.ARMOUR, -less, "被破甲")));
            }
        }
        // 2026-10-26 照 PoE2 天賦樹：荊棘傷害有 x% 機率無視敵人護甲
        if (attackerIsPlayer && Thorns.ID.equals(skill.id()) && rawArmour > 0) {
            final double ignore = attackerSheet.flat(tw.exilecore.stats.TreeEffectKeys.THORNS_IGNORE_ARMOUR, java.util.Set.of());
            if (ignore > 0 && plugin.random().nextDouble() * 100 < ignore) {
                hitDefender = hitDefender.overlay(java.util.List.of(Modifier.more(StatKeys.ARMOUR, -1.0, "荊棘無視護甲")));
            }
        }
        // 感電：受到的傷害增加（在減免之後乘）；印記、戰吼的減益也在這裡乘
        final double taken = ((ailments == null ? 1.0 : ailments.damageTakenMultiplier(defender)) + brokenTaken)
                * debuffs.takenMultiplier(defender.getUniqueId(), attacker.getUniqueId(), plugin.getServer().getCurrentTick(), hitTagSet);
        // PvP：傷害乘上專屬的 PvP 減傷（元素異常照縮小後的傷害算：pendingAilment 存的是沒乘減傷的 taken）
        // 2026-10：召喚物、圖騰、陷阱打的再乘 minion_damage；挨打的玩家拿近戰武器再少 melee_reduction %
        final double pvpScale = pvpAttacker != null && plugin.pvp() != null
                ? plugin.pvp().damageMultiplier(attackerIsMinion || skill.tags().contains(tw.exilecore.stats.Tags.MINION)
                        || skill.tags().contains(tw.exilecore.stats.Tags.TOTEM) || skill.tags().contains(tw.exilecore.stats.Tags.TRAP),
                        defenderIsPlayer ? (Player) defender : null)
                : 1.0;
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
        final boolean trinityHit = attackerIsPlayer && StatKeys.flag(skillSheet, StatKeys.FLAG_TRINITY);
        StatSheet hitSheet = trinityHit ? attackerSheet.overlay(trinity.bonus(attacker.getUniqueId(), hitTick, skillSheet)) : attackerSheet; // 2026-10-26：逐級數值
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
            // 2026-10-17：傳奇寫的是 MORE（更多 35～70%），以前只讀 FLAT（昇華的裂勁），傳奇那條完全沒效果——兩種都算
            final double vsStunned = attackerSheet.flat(StatKeys.DAMAGE_VS_STUNNED, java.util.Set.of()) / 100.0
                    + (attackerSheet.more(StatKeys.DAMAGE_VS_STUNNED, java.util.Set.of()) - 1);
            // 2026-10-17：只算重暈（說明寫的「重暈中的敵人」；以前凍結、輕暈也算）
            if (vsStunned > 0 && ailments != null && ailments.heavyStunned(defender)) {
                conditional.add(Modifier.more(StatKeys.DAMAGE, vsStunned, "對暈眩的敵人"));
            }
            // 2026-10-27：天賦樹的「增加 x% 對低血敵人的擊中傷害」是 INCREASED（這次之前只讀 FLAT，天賦樹 12 個節點都沒有效果）
            final double vsLow = skillSheet.flat(StatKeys.DAMAGE_VS_LOW_LIFE, java.util.Set.of()) // 2026-10：輔助寶石（處刑）也算
                    + skillSheet.increased(StatKeys.DAMAGE_VS_LOW_LIFE, hitTagSet) * 100;
            final org.bukkit.attribute.AttributeInstance maxHealth = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            // 2026-10-30 照 PoE2 Execute I／II／III（support_executioner_damage_vs_enemies_on_low_life_+%_final）：處刑是更多（MORE），另外乘
            final double vsLowMore = skillSheet.more(StatKeys.DAMAGE_VS_LOW_LIFE, hitTagSet);
            final boolean lowEnemy = maxHealth != null && defender.getHealth() <= maxHealth.getValue() * 0.35;
            if (vsLow > 0 && lowEnemy) {
                conditional.add(Modifier.increased(StatKeys.DAMAGE, vsLow / 100.0, "低血的敵人"));
            }
            if (vsLowMore > 1 + 1e-9 && lowEnemy) {
                conditional.add(Modifier.more(StatKeys.DAMAGE, vsLowMore - 1, "處刑（低血的敵人）"));
            }
            // 2026-10-30 照 PoE2 Execute III：你自己低血（35% 以下）時擊中傷害更多
            final double whileLow = skillSheet.more(StatKeys.DAMAGE_WHILE_LOW_LIFE, hitTagSet);
            if (whileLow > 1 + 1e-9 && attacker instanceof Player attackingPlayer) {
                final tw.exilecore.character.PlayerState attackerLife = plugin.players().get(attackingPlayer);
                if (attackerLife != null && attackerLife.maxLife() > 0 && attackerLife.life() <= attackerLife.maxLife() * 0.35) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, whileLow - 1, "處刑 III（你低血）"));
                }
            }
            // 2026-10：輔助寶石的條件：對滿血的敵人、魔力 90% 以上、比敵人低血（背水）
            final StatSheet supportSheet = skillSheet;
            final double defenderFraction = maxHealth == null || maxHealth.getValue() <= 0 ? 1 : defender.getHealth() / maxHealth.getValue();
            if (defenderFraction >= 0.999) {
                final double fullMore = supportSheet.more(StatKeys.DAMAGE_VS_FULL_LIFE, hitTagSet);
                if (fullMore != 1) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, fullMore - 1, "對滿血的敵人"));
                }
                final double fullCrit = supportSheet.more(StatKeys.CRIT_VS_FULL_LIFE, hitTagSet);
                if (fullCrit != 1) {
                    conditional.add(Modifier.more(StatKeys.CRIT_CHANCE, fullCrit - 1, "對滿血的敵人"));
                }
                // 2026-10-27：天賦樹的「增加 x% 對滿血敵人的擊中傷害／暴擊率」是 INCREASED（這次之前只讀 MORE，沒有效果）
                final double fullIncreased = supportSheet.increased(StatKeys.DAMAGE_VS_FULL_LIFE, hitTagSet);
                if (fullIncreased != 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, fullIncreased, "對滿血的敵人"));
                }
                final double fullCritIncreased = supportSheet.increased(StatKeys.CRIT_VS_FULL_LIFE, hitTagSet);
                if (fullCritIncreased != 0) {
                    conditional.add(Modifier.increased(StatKeys.CRIT_CHANCE, fullCritIncreased, "對滿血的敵人"));
                }
            }
            final PlayerState selfState = plugin.players().get((Player) attacker);
            final double highMana = supportSheet.more(StatKeys.DAMAGE_HIGH_MANA, hitTagSet);
            if (highMana != 1 && selfState.maxMana() > 0 && selfState.mana() >= selfState.maxMana() * 0.9) {
                conditional.add(Modifier.more(StatKeys.DAMAGE, highMana - 1, "魔力充沛"));
            }
            final double clash = supportSheet.flat(StatKeys.DAMAGE_CLASH, hitTagSet);
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
            // 2026-10-31 照 PoE2 Chober Chaber（忠誠之錘）：召喚物傷害的增加、減少也套用在你身上（只算寫明召喚物的那幾條）
            if (attackerSheet.flat(StatKeys.MINION_DAMAGE_AFFECTS_YOU, java.util.Set.of()) > 0) {
                final java.util.Set<String> asMinion = new java.util.HashSet<>(hitTagSet);
                asMinion.add(tw.exilecore.stats.Tags.MINION);
                final double minionOnly = skillSheet.increased(StatKeys.DAMAGE, asMinion) - skillSheet.increased(StatKeys.DAMAGE, hitTagSet);
                if (minionOnly != 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, minionOnly, "召喚物傷害"));
                }
            }
            // 2026-10：對稀有、傳奇怪物（含 Boss、訓練假人・首領）增加傷害
            if (defenderMonster != null) {
                final boolean unique = defenderMonster.boss() || defenderMonster.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE;
                final boolean rare = unique || defenderMonster.rarity() == tw.exilecore.monster.MonsterRarity.RARE;
                final StatSheet withSupports = skillSheet;
                final double vsRare = rare ? withSupports.increased(StatKeys.DAMAGE_VS_RARE, hitTagSet) : 0;  // 2026-10-27：照這一擊的標籤讀（天賦的「攻擊」對稀有傷害帶 tags [attack]，以前傳空集合永遠沒算到）
                final double vsUnique = unique ? withSupports.increased(StatKeys.DAMAGE_VS_UNIQUE, hitTagSet) : 0;
                if (vsRare + vsUnique != 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, vsRare + vsUnique, "對稀有、傳奇"));
                }
                // 2026-10-27：MORE 的也要算（貪匣之鎖）；這次之前只算 INCREASED，更多的那幾條沒有作用
                final double moreRare = rare ? withSupports.more(StatKeys.DAMAGE_VS_RARE, hitTagSet) : 1;
                final double moreUnique = unique ? withSupports.more(StatKeys.DAMAGE_VS_UNIQUE, hitTagSet) : 1;
                if (moreRare != 1) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, moreRare - 1, "對稀有、傳奇"));
                }
                if (moreUnique != 1) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, moreUnique - 1, "對傳奇、Boss"));
                }
            }
            // 2026-10：條件傷害：對被詛咒的敵人、對近身的敵人
            final StatSheet conditionSheet = skillSheet;
            final boolean cursedTarget = debuffs.cursed(defender.getUniqueId(), debuffTick);
            final boolean closeTarget = attacker.getWorld().equals(defender.getWorld())
                    && attacker.getLocation().distanceSquared(defender.getLocation()) <= StatKeys.CLOSE_RANGE * StatKeys.CLOSE_RANGE;
            for (final String stat : new String[] {StatKeys.DAMAGE_VS_CURSED, StatKeys.DAMAGE_CLOSE}) {
                if (stat.equals(StatKeys.DAMAGE_VS_CURSED) ? !cursedTarget : !closeTarget) {
                    continue;
                }
                final double inc = conditionSheet.increased(stat, hitTagSet);
                double more = conditionSheet.more(stat, hitTagSet);
                if (stat.equals(StatKeys.DAMAGE_CLOSE) && more != 1) {
                    // 2026-10-30 照 PoE2 Close Combat II：1 公尺內全額，到 3.5 公尺遞減到沒有；這次之前 3.5 格內一律全額
                    more = 1 + (more - 1) * StatKeys.closeFalloff(attacker.getLocation().distance(defender.getLocation()));
                }
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
                    final double inc = conditionSheet.increased(stat, hitTagSet);
                    final double more = conditionSheet.more(stat, hitTagSet);
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
                final StatSheet comboSheet = skillSheet;
                final double perCombo = comboSheet.flat(StatKeys.DAMAGE_PER_COMBO, hitTagSet);
                final int combo = res.comboForDamage((Player) attacker);
                if (perCombo > 0 && combo > 0) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, perCombo * combo / 100.0, "連擊"));
                }
                final double finisher = comboSheet.flat(StatKeys.COMBO_FINISHER, hitTagSet);
                if (finisher > 0 && res.finisher((Player) attacker)) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, finisher / 100.0, "終式"));
                }
                final double forged = comboSheet.flat(StatKeys.RAGEFORGED, hitTagSet);
                if (forged > 0 && res.enraged((Player) attacker)) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, forged / 100.0, "怒鑄"));
                }
            }
            // 2026-10-24（抑星，PoE2 Inhibitor）：身上每有一種戰星或灌注，更多 4% 傷害
            final double inhibit = skillSheet.flat(tw.exilecore.charge.ChargeService.SUP_INHIBIT, hitTagSet);
            if (inhibit > 0 && plugin.skills() != null && charges != null) {
                int kinds = 0;
                for (final tw.exilecore.charge.ChargeType type : charges.config().types()) {
                    if (charges.count((Player) attacker, type.id()) > 0) {
                        kinds++;
                    }
                }
                for (final int layers : plugin.skills().resources().infusions((Player) attacker)) {
                    if (layers > 0) {
                        kinds++;
                    }
                }
                if (kinds > 0) {
                    conditional.add(Modifier.more(StatKeys.DAMAGE, inhibit * kinds / 100.0, "抑星"));
                }
            }
            // 2026-10-24（馭星訣的品質，PoE2）：身上每顆戰星增加 x% 傷害
            final double perCharge = attackerSheet.flat(StatKeys.DAMAGE_PER_CHARGE, java.util.Set.of());
            if (perCharge > 0 && charges != null) {
                int held = 0;
                for (final tw.exilecore.charge.ChargeType type : charges.config().types()) {
                    held += charges.count((Player) attacker, type.id());
                }
                if (held > 0) {
                    conditional.add(Modifier.increased(StatKeys.DAMAGE, perCharge * held / 100.0, "馭星訣"));
                }
            }
            // 2026-10-26：照 PoE2 重做的輔助：炙獵（對點燃中的敵人）、遠穿（照距離）、釘地 II（對被定住的敵人）
            if (plugin.skills() != null) {
                conditional.addAll(plugin.skills().supportMechanics().preHit((Player) attacker, defender, skillSheet, hitTagSet, skill)); // 2026-10-27：多帶技能描述
            }
            // 2026-10：怒氣：每點增加 1% 攻擊傷害
            if (plugin.skills() != null) {
                final int rage = plugin.skills().resources().rage((Player) attacker);
                if (rage > 0 && !StatKeys.flag(attackerSheet, tw.exilecore.stats.KeystoneKeys.FLAG_PRIMAL_HUNGER)) { // 2026-10-26 原初飢餓：怒氣沒有效果
                    final double per = 0.01 * Math.max(0, 1 + attackerSheet.increased(StatKeys.RAGE_EFFECT, java.util.Set.of()));
                    conditional.add(new Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, rage * per, java.util.Set.of(Tags.ATTACK), "怒氣"));
                }
            }
            // 2026-10-26（傳奇）：霜環、寒息箭袋、屠王巨鎚、夢魘長棍（tw.exilecore.item.unique.UniqueMechanics）
            conditional.addAll(tw.exilecore.item.unique.UniqueMechanics.attackerConditional(ailments, defender, skillSheet, hitTagSet));
            // 2026-10-26（昇華）：開放弱點、專注、隨機元素、血液沸騰、力量的代價、異能賦予…（AscendancyEffects）
            conditional.addAll(tw.exilecore.ascendancy.AscendancyEffects.attackerConditional((Player) attacker, defender, defenderMonster, skillSheet, hitTagSet, skill));
            // 2026-10-27 照 PoE2：抽出穿刺、箭照飛行距離的暴擊率（KeystoneEffects）
            conditional.addAll(KeystoneEffects.attackerConditional((Player) attacker, defender, skillSheet, skill));
            if (!conditional.isEmpty()) {
                hitSheet = hitSheet.overlay(conditional);
            }
        } else if (attackerIsMinion && !defenderIsPlayer) {
            // 2026-10-29 照 PoE2 的 Minion Types：從召喚物這邊掛的處刑、潛鋒、趁寒、屠巨、貼身、炙獵、遠穿…召喚物打的也算
            // （跟玩家的同一套條件，MinionTypes.conditional）；這次之前條件傷害只算玩家打的
            final java.util.List<Modifier> conditional = tw.exilecore.skill.MinionTypes.conditional(skillSheet, hitTagSet,
                    minionTarget(attacker, defender, defenderMonster, ailments, debuffTick));
            if (!conditional.isEmpty()) {
                hitSheet = hitSheet.overlay(conditional);
            }
        }
        if (defenderIsPlayer) {
            hitSheet = tw.exilecore.item.unique.UniqueMechanics.defenderConditional(hitSheet, defenderSheet, ailments, defender); // 2026-10-26（暗潮指環）
        }
        // 壁壘（PoE2 基石）：翻滾中受到的擊中傷害更少 30%
        if (defenderIsPlayer && dodge != null && dodge.rolling((Player) defender) && StatKeys.flag(defenderSheet, StatKeys.FLAG_BULWARK)) {
            signature *= 0.7;
        }
        // PoE2：玩家能閃避攻擊與法術的擊中（Boss 的法術、預警的大招不能閃）；玩家的法術不擲命中
        final boolean evadable = (skill.isAttack() || defenderIsPlayer && !isBoss(attacker))
                && !skill.tags().contains(DodgeRoll.UNDODGEABLE_TAG); // 2026-10：全場大招閃避也躲不掉，要站安全區
        final java.util.UUID entropyKey = defender.getUniqueId();
        // 2026-10-21：只加某一種傷害的減益（凋息加混沌、縛根加物理）：疊在挨打的那一方，只乘那一種傷害的部分
        final java.util.Map<tw.exilecore.stats.DamageType, Double> typeTaken = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
        final java.util.List<Modifier> typedMods = new java.util.ArrayList<>();
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            final double increased = debuffs.typedTaken(defender.getUniqueId(), attacker.getUniqueId(), hitTick, type);
            if (increased > 0) {
                typeTaken.put(type, 1 + increased);
                typedMods.add(Modifier.flat(StatKeys.DEBUFF_TAKEN + type.tag(), increased * 100, "減益"));
            }
        }
        if (!typedMods.isEmpty()) {
            hitDefender = hitDefender.overlay(typedMods);
        }
        // 2026-10-26 照 PoE2 天賦樹：「對致盲、被禁錮、遠處…的敵人」：照目標的狀態把條件標籤加進這一下的情境（只給傷害計算用）
        // 2026-10-27 照 PoE2 天賦樹：召喚物打的也看（「夥伴對被禁錮的敵人增加傷害」：主人的屬性表有條件修飾器就看）；這次之前只看玩家打的
        final Player tagOwner = attackerIsPlayer ? (Player) attacker : attackerIsMinion ? credit : null;
        final SkillProfile calcSkill = tagOwner != null && plugin.players().conditions().wantsTargetTags(tagOwner.getUniqueId())
                ? TargetConditions.tag(skill, TargetConditions.of(plugin, attacker, defender, defenderMonster, debuffs, ailments, hitTick)) : skill;
        if (defenderIsPlayer) {
            // 2026-10-26（昇華）：神聖儀式（當前護盾加到護甲）、保持警覺（敵人依距離的命中懲罰）
            hitDefender = tw.exilecore.ascendancy.AscendancyEffects.defenderSheet((Player) defender, defenderState, hitDefender);
            hitSheet = tw.exilecore.ascendancy.AscendancyEffects.enemyVsPlayer((Player) defender, attacker, hitSheet);
            // 2026-10-27 照 PoE2 天賦樹：6 格外打來的用更多護甲防禦、免疫曝露、身上的凋零效果減少（TreeLeftovers）
            hitDefender = tw.exilecore.skill.TreeLeftovers.defenderSheet(defender, attacker, hitDefender, defenderDebuffs,
                    debuffs, hitTick, skill.isAttack() && event.getDamager() instanceof org.bukkit.entity.Projectile && !skill.tags().contains(Tags.PROJECTILE));
            if (plugin.skills() != null && !attackerIsPlayer && !attackerIsMinion) {
                hitDefender = plugin.skills().supportMechanics().lineage().seraph((Player) defender, hitDefender); // 2026-10-27 照 PoE2 熾心
            }
            // 2026-10-30 照 PoE2 Parry（base_maximum_active_block_distance_for_non_projectiles 10、for_projectiles 15）：
            // 招架只擋 1 公尺內（投射物 1.5 公尺內）的敵人打來的擊中，遠的照一般格擋；這次之前沒有距離限制
            if (StatKeys.flag(hitDefender, StatKeys.FLAG_PARRY)) {
                final boolean projectile = skill.tags().contains(Tags.PROJECTILE) || event.getDamager() instanceof org.bukkit.entity.Projectile;
                final double reach = tw.exilecore.core.Units.metres(projectile ? PARRY_PROJECTILE_METRES : PARRY_MELEE_METRES);
                if (edgeDistance(attacker, defender) > reach) {
                    hitDefender = hitDefender.overlay(java.util.List.of(Modifier.flat(StatKeys.FLAG_PARRY, -1000, "招架：距離外")));
                }
            }
        }
        final HitResult rawResult = DamageCalculator.calculate(hitSheet, calcSkill, hitDefender, defenderEs, taken * pvpScale * signature,
                plugin.items().random(), chance -> entropy.hits(entropyKey, chance, hitTick, plugin.items().random()), evadable);
        // 2026-10-26（燎原、深切，PoE2）：「擊中傷害更少」只減擊中，異常（點燃、流血、中毒）照沒減之前的那一下算
        final HitResult result = tw.exilecore.skill.SupportMechanics.scaled(rawResult,
                tw.exilecore.skill.SupportMechanics.hitOnlyFactor(skillSheet, hitTagSet));
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
                // 2026-10-26 照 PoE2 天賦樹：暴擊時有 x% 機率獲得秘術增強 4 秒（跟湧詠、天賦同一個增益：TreeMechanics.grantArcaneSurge，
                // 增加 15% 施法速度、更多 20% 魔力回復，吃「你身上的秘術增強效果」）
                final double surge = attackerSheet.flat(tw.exilecore.stats.TreeEffectKeys.ARCANE_SURGE_ON_CRIT, java.util.Set.of());
                if (surge > 0 && plugin.skills() != null && plugin.skills().treeMechanics() != null && !totemHit(skill)
                        && plugin.random().nextDouble() * 100 < surge) {
                    plugin.skills().treeMechanics().grantArcaneSurge((Player) attacker, tw.exilecore.stats.TreeEffectKeys.ARCANE_SURGE_TICKS);
                }
            } else {
                hitter.markRecent(tw.exilecore.stats.TreeConditions.NON_CRIT, tick); // 2026-10-26：天賦樹「最近有一下沒暴擊」
            }
        }
        if (attackerIsPlayer && defenderMonster != null && ailments != null && result.hit() && !result.blocked()) {
            final StatSheet breakSheet = skillSheet;
            final java.util.Set<String> breakContext = KeystoneEffects.critContext(calcSkill.contextFor(DamageType.PHYSICAL), result.crit()); // 2026-10-26：含目標的條件標籤、暴擊（crit_hit）
            // 2026-10-26：霜裂甲（打凍結的）、烙印深（打被標記的）多破甲；碎甲吼的戰吼不能破甲（照 PoE2）
            final double extraBreak = plugin.skills() == null ? 0
                    : plugin.skills().supportMechanics().extraBreak((Player) attacker, breakSheet, breakContext, defender);
            final double breakPercent = Double.isInfinite(extraBreak) ? 0 : breakSheet.apply(StatKeys.ARMOUR_BREAK,
                    breakSheet.flat(StatKeys.ARMOUR_BREAK, breakContext) + extraBreak, breakContext); // 2026-10：「增加 x% 破甲」也算
            // 2026-10-26：固定量的破甲（PoE2 的 Armour Piercing Rounds）：當成「破掉這麼多的 100%」，增加破甲照乘
            final double breakFlat = Double.isInfinite(extraBreak) ? 0 : breakSheet.flat(StatKeys.ARMOUR_BREAK_FLAT, breakContext);
            final double flatScale = breakSheet.apply(StatKeys.ARMOUR_BREAK, 100, breakContext);
            final double breakBase = result.outgoing().get(DamageType.PHYSICAL) * Math.max(0, breakPercent) / 100.0
                    + Math.max(0, breakFlat) * flatScale / 100.0;
            if (breakBase > 0) {
                if (ailments.breakArmour(defender, defenderMonster, breakBase, 100, rawArmour, // 2026-10-26 照 PoE2 天賦樹：破甲持續時間
                        tw.exilecore.stats.TreeEffectKeys.scale(breakSheet, tw.exilecore.stats.TreeEffectKeys.ARMOUR_BREAK_DURATION, java.util.Set.of(), 0.1))
                        && plugin.skills() != null) {
                    plugin.skills().procEvent((Player) attacker, skill.id(), "armour_broken", null, defender); // 輔助：完全破甲時（2026-10）
                    if (plugin.skills().treeLeftovers() != null) {
                        plugin.skills().treeLeftovers().onFullBreak((Player) attacker, defender); // 2026-10-27 照 PoE2 天賦樹：完全破甲的敵人被癱瘓
                    }
                    if (charges != null && !totemHit(skill)) { // 2026-10-21：圖騰、英靈造成的完全破甲不給你戰星
                        charges.onEvent((Player) attacker, "armour_broken", breakSheet, skill.tags(), defender); // 戰星：完全破甲時（2026-10）
                    }
                }
            }
        }
        if (attackerIsMinion && defenderMonster != null && ailments != null && result.hit() && !result.blocked()) {
            // 2026-10-29 照 PoE2 的 Minion Types：從召喚物這邊掛的碎甲、重擊、拆甲、霜裂甲…召喚物打的物理傷害也破甲（完全破甲的戰星、觸發照舊只給玩家自己打的）；
            // 這次之前只有玩家打的會破甲
            final java.util.Set<String> breakContext = calcSkill.contextFor(DamageType.PHYSICAL);
            final double frozenBreak = ailments.isFrozen(defender) ? skillSheet.flat(tw.exilecore.stats.SupportKeys.BREAK_VS_FROZEN, breakContext) : 0;
            final double breakPercent = skillSheet.apply(StatKeys.ARMOUR_BREAK, skillSheet.flat(StatKeys.ARMOUR_BREAK, breakContext) + frozenBreak, breakContext);
            final double breakBase = result.outgoing().get(DamageType.PHYSICAL) * Math.max(0, breakPercent) / 100.0;
            if (breakBase > 0) {
                ailments.breakArmour(defender, defenderMonster, breakBase, 100, rawArmour,
                        tw.exilecore.stats.TreeEffectKeys.scale(skillSheet, tw.exilecore.stats.TreeEffectKeys.ARMOUR_BREAK_DURATION, java.util.Set.of(), 0.1));
            }
        }
        if (!attackerIsPlayer && !attackerIsMinion && defenderIsPlayer && result.hit() && !result.blocked() && rawArmour > 0) {
            // 2026-10-27 照 PoE2：帶破甲的怪物（稀有／魔法怪詞綴「粉碎的」、換界石「碎甲的」）擊中你時破你的護甲；這次之前只有玩家能破怪物的
            final double breakAmount = PlayerArmourBreak.amount(result.outgoing().get(DamageType.PHYSICAL),
                    skillSheet.flat(StatKeys.ARMOUR_BREAK, calcSkill.contextFor(DamageType.PHYSICAL)), PlayerArmourBreak.takenIncreased(defenderSheet));
            if (breakAmount > 0 && PlayerArmourBreak.onHit(defender.getUniqueId(), breakAmount, rawArmour, plugin.getServer().getCurrentTick())) {
                defender.getWorld().playSound(defender.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.8f, 0.8f);
                tw.exilecore.gui.ActionBar.send((Player) defender, plugin.text().parse("<#c0c0c0>護甲被完全破除（4 秒）"));
            }
        }
        if (trinityHit && result.hit() && !result.blocked() && !totemHit(skill)) { // 2026-10-21：圖騰、英靈打中的不疊共鳴（加成照吃）
            trinity.hit(attacker.getUniqueId(), result.dealt(), hitTick, skillSheet);
        }
        final HitEvent hitEvent = new HitEvent(attacker, defender, calcSkill, result, credit); // 2026-10-26：含目標的條件標籤（釘住、眩暈機率…）
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
                    gainOnBlock((Player) defender, defenderSheet); // 2026-10-21：腐化「格擋時回復生命／魔力」
                    if (plugin.skills() != null) {
                        plugin.skills().supportMechanics().lineage().blocked((Player) defender); // 2026-10-27 照 PoE2：雪封、岳盾
                    }
                    // 2026-10-27 照 PoE2：舉盾（含招架）格擋下來的擊中累積重暈（AilmentService.blockedHit）
                    if (ailments != null && plugin.skills() != null && plugin.skills().buffActivePrefix((Player) defender, "guard")) {
                        ailments.blockedHit((Player) defender, attacker, calcSkill, hitSheet, defenderSheet);
                    }
                    if (charges != null) {
                        charges.onEvent((Player) defender, "block", null, null, attacker); // 2026-10-24：格擋時給戰星（詞綴、天賦用）
                    }
                    if (plugin.skills() != null) {
                        plugin.skills().chargeEffects().onBlock((Player) defender, attacker, defenderSheet); // 2026-10-24：招架、熔殼
                    }
                    // 2026-10-15（蛛巢壁壘）：格擋時讓攻擊者中毒，視同造成這麼多基礎混沌傷害
                    final double blockPoison = defenderSheet.flat(StatKeys.BLOCK_POISON, java.util.Set.of());
                    if (blockPoison > 0 && ailments != null && !(attacker instanceof Player)) {
                        ailments.poisonFrom((Player) defender, attacker, blockPoison, defenderSheet);
                    }
                }
                if (signatures != null) {
                    signatures.avoided((Player) defender, defenderSheet); // 虛步
                }
            }
            return;
        }
        if (attackerIsPlayer && defenderMonster != null && plugin.skills() != null) {
            plugin.skills().totemAggro((Player) attacker, defender, totemHit(skill)); // 2026-10-26 照 PoE2：被圖騰打中的怪改打圖騰，被你打中的轉回你
        }
        // 2026-10-17：真的打中了才用掉虛步、啟動背水（以前算倍率時就用掉，被閃掉、擋掉也一樣）
        if (signatures != null) {
            if (attackerIsPlayer && !totemHit(skill)) { // 2026-10-21：圖騰、英靈打中的不用掉你的虛步
                signatures.hitLanded((Player) attacker, attackerSheet, defender);
            }
            if (defenderIsPlayer) {
                signatures.hitTaken((Player) defender, defenderState, defenderSheet);
            }
        }
        if (ailments != null && !defenderIsMinion) {
            // 點燃、冰緩、凍結、感電：等事件確定沒被其他插件取消再算（召喚物不會中異常；召喚物點燃的算主人點的）
            if (defenderMonster != null && StatKeys.flag(skillSheet, StatKeys.FLAG_STUN_FULL_LIFE)) {
                ailments.noteFullLife(defender); // 2026-10：重暈滿血的敵人（傷害寫進去之前先看）
            }
            // 2026-10-26：異常照「只減擊中」之前的那一下（rawResult）；寒焚、淬毒 III、急蝕、速蝕的條件加成疊在攻擊方的表上
            final StatSheet ailmentSheet = plugin.skills() == null ? attackerSheet
                    : plugin.skills().supportMechanics().lineage().ailmentSheet(attacker, // 2026-10-27 照 PoE2 怒毒：怒氣讓中毒更快
                    plugin.skills().supportMechanics().ailmentSheet(credit != null ? credit : attacker, attackerSheet, skillSheet, skill, defender, defenderMonster), skillSheet, skill); // 2026-10-27：多帶攻擊方
            if (attackerIsMinion && rawResult.hit() && !rawResult.blocked()) {
                ailments.carrierHit(attacker, defender); // 2026-10-28 照 PoE2 Contagion：復活帶著瘟疫的屍體的召喚物擊中時傳出瘟疫
            }
            pendingAilments.put(defender.getUniqueId(),
                    new PendingAilment(credit != null ? credit : attacker, KeystoneEffects.critSkill(calcSkill, rawResult.crit()), ailmentSheet, defenderSheet, rawResult, taken, defenderMonster,
                            typeTaken));
        }

        if (defenderIsPlayer) {
            // 被近戰打到：打完之後荊棘反彈給攻擊的怪物（遠程、法術不會）。怪物的普攻一律標成近戰，
            // 所以還要看這一下是不是牠本人直接打的（箭、火球、喚魔者的尖牙打到時 damager 是投射物或效果，不算）
            final boolean directHit = event.getDamager() == attacker && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
            final LivingEntity thornsTarget = !attackerIsPlayer && !attackerIsMinion && Thorns.triggersOn(directHit, skill.tags(),
                    StatKeys.flag(defenderSheet, StatKeys.FLAG_THORNS_ANY_HIT)) ? attacker : null;
            if (result.deflected()) {
                numbers.takenWord((Player) defender, "偏斜"); // 2026-10：偏斜的那一下傷害更少
            }
            // 2026-10-28 照 PoE2（主宰之觸）：受到稀有、傳奇敵人的傷害另外補償為生命
            hitByRare = !attackerIsPlayer && !attackerIsMinion && plugin.monsters() != null && plugin.monsters().data(attacker)
                    .map(data -> data.rarity() == tw.exilecore.monster.MonsterRarity.RARE || data.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE)
                    .orElse(false);
            applyToPlayer((Player) defender, defenderState, result.esDamage(), result.lifeDamage(), event,
                    describeSource(attacker), describeSkill(attacker, skill, event.getCause()), result.dealt(), result.outgoing(), thornsTarget,
                    (!attackerIsPlayer && !attackerIsMinion) || pvpAttacker != null, pvpAttacker);
            hitByRare = false;
            if (pvpAttacker != null) {
                pvpRewards(attackerIsPlayer, credit, attackerSheet, skill, result, defender);
            }
            if (!attackerIsPlayer && !attackerIsMinion) {
                // 2026-10-18：怪物偷取等事件確定沒被取消才回
                defer(defender, () -> monsterLeech(attacker, attackerSheet, result)); // 地圖詞綴：怪物偷取
            }
        } else if (defenderIsMinion) {
            // 召喚物跟怪物一樣：原版血量只是比例
            // 2026-10-26：護盾吸收的部分不扣血（這次之前整下都扣生命）
            // 2026-10-30 照 PoE2 Bone Offering：護盾吸收超過最大生命 25% 的那一下（整下吸掉、不中異常），然後護盾爆掉
            final boolean absorbed = result.total() > 0 && minions.shieldAbsorbs(defender, result.total());
            if (absorbed) {
                pendingAilments.remove(defender.getUniqueId());
            } else if (result.total() > 0) {
                minions.damaged(defender, result.esDamage(), attacker);
                minions.hitTaken(defender, result.total()); // 2026-10-28 照 PoE2 Bone Offering 的品質
            }
            if (absorbed) {
                event.setDamage(0);
            } else if (result.lifeDamage() > 0 || result.esDamage() <= 0) {
                final double rawMinionDamage = Math.max(0.01, result.lifeDamage() * minions.healthScale(defender));
                // 2026-10-27 照 PoE2：憨魄（召喚物不會受傷）、殘戰（致命的那一下留一口氣）
                final double minionDamage = plugin.skills() == null ? rawMinionDamage
                        : plugin.skills().supportMechanics().lineage().minionDamage(defender, rawMinionDamage);
                event.setDamage(plugin.combat() == null || plugin.combat().uniques() == null ? minionDamage
                        : plugin.combat().uniques().minionDamage(defender, minions.owner(defender), minionDamage)); // 2026-10-26（骸骨束帶）：只套在扣生命的部分
            } else {
                event.setDamage(0);
            }
            if (pvpAttacker != null) {
                lastPvpCapScale = 1.0; // 打召喚物沒有上限
                pvpRewards(attackerIsPlayer, credit, attackerSheet, skill, result, defender);
            }
        } else {
            // 巔峰 Boss 的每秒傷害上限（2026-10，maps.yml 的 pinnacle.dps_cap）：玩家與他的召喚物打的才算
            double total = credit != null && plugin.maps() != null
                    ? plugin.maps().capBossDamage(defender, credit.getUniqueId(), result.total()) : result.total();
            if (credit != null && plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
                // 2026-10-23：怪物詞綴（近身的、噬魂的、熔盾的、護盾的、幻雷的）
                // 扣護盾、熔岩爆炸、幻雷等事件確定沒被取消才做
                total = plugin.monsterModule().mechanics().incoming(defender, defenderMonster, attacker, credit, total,
                        effect -> defer(defender, effect), true);
            }
            // 2026-10-23：整下被擋掉（太遠打不到近身的、全被護盾吃掉）：不中異常、不偷取、不算擊中回復、不撲殺
            final boolean blocked = total <= 0 && result.total() > 0;
            if (blocked) {
                pendingAilments.remove(defender.getUniqueId());
            }
            if (total < result.total() && result.total() > 0) {
                // 2026-10-18：被每秒傷害遞減砍掉的部分也不算暈眩（重暈累積、輕暈機率照遞減後的傷害）
                final double stunScale = total / result.total();
                pendingAilments.computeIfPresent(defender.getUniqueId(), (uuid, pending) -> pending.withStunScale(stunScale));
            }
            // 怪物的原版血量上限 1024，生命更高的怪物按比例縮放
            event.setDamage(blocked ? 0 : plugin.monsters().toVanillaDamage(defender, defenderMonster, total));
            // 撲殺（2026-10，輔助寶石）：這一下之後生命低於門檻就直接打死
            // 撲殺：換階段鎖血中的 Boss 不能撲殺（2026-10：不然收命、收魂照給，Boss 卻沒死）
            // 2026-10-26：撲殺 I／II（PoE2）只對稀有、傳奇；撲殺 II 打死之後門檻增加
            // 2026-10-26 照 PoE2 天賦樹：撲殺與撲殺門檻帶這一下的情境（弓的攻擊、對被標記的、對野獸、對稀有傳奇、對被禁錮的）
            if (!blocked && (attackerIsPlayer || attackerIsMinion) && (StatKeys.flag(skillSheet, StatKeys.FLAG_CULLING)
                    || tw.exilecore.stats.AttributeScalingKeys.culls(skillSheet, calcSkill.tags())
                    || tw.exilecore.skill.SupportMechanics.cullsRare(skillSheet, defenderMonster)
                    || tw.exilecore.skill.OriginalSkills.culls(attacker, defender) // 2026-10-26 照 PoE2：磨蝕之志
                    // 2026-10-26 照 PoE2 天賦「讓敵人重暈的擊中有撲殺」：擊中重暈中的敵人有撲殺
                    || ailments != null && StatKeys.flag(skillSheet, tw.exilecore.stats.TreeNewMechanicKeys.FLAG_HEAVY_STUN_CULL)
                    && ailments.heavyStunned(defender))
                    && (plugin.maps() == null || plugin.maps().phaseFloor(defender) <= 0)) {
                final boolean culled = cull(event, defender, defenderMonster,
                        tw.exilecore.stats.AttributeScalingKeys.cullScale(skillSheet, calcSkill.tags())); // 這次之前 SupportMechanics.cullScale（不看情境）
                if (culled && attackerIsPlayer) {
                    // 2026-10-28 照 PoE2 斷魂掌：撲殺到目標才連衝下一隻（SkillExecutor 同一個 tick 查）；這次之前沒記
                    tw.exilecore.skill.KillingPalm.noteCull(attacker.getUniqueId(), defender.getUniqueId(), plugin.getServer().getCurrentTick());
                }
                if (culled && attackerIsPlayer && plugin.skills() != null) {
                    plugin.skills().supportMechanics().onCull((Player) attacker, skillSheet, skill.tags());
                }
                if (culled && attackerIsMinion && credit != null && charges != null && plugin.skills() != null
                        && plugin.skills().minions() != null) {
                    // 2026-10-24（霧鴉，PoE2 Mist Raven）：召喚物撲殺時照那個召喚技能的 minion_cull.代號 給主人戰星
                    final Player owner = credit;
                    final java.util.Map<String, Integer> gain = plugin.skills().minions().cullCharges(attacker);
                    if (!gain.isEmpty()) {
                        defer(defender, () -> gain.forEach((id, count) -> charges.grant(owner, id, count)));
                    }
                }
                if (culled && attackerIsPlayer) {
                    // 2026-10：收命、收魂（撲殺打死時回復）
                    // 2026-10-18：撲殺的戰星與回復等事件確定沒被取消（MONITOR）才給
                    defer(defender, () -> {
                        final StatSheet cullSheet = skillSheet;
                        if (charges != null) {
                            charges.onEvent((Player) attacker, "cull", cullSheet, skill.tags(), defender); // 戰星：撲殺時（2026-10，稀有度越高給越多）
                        }
                        final double lifePct = cullSheet.flat(StatKeys.LIFE_ON_CULL, skill.tags());
                        final double manaPct = cullSheet.flat(StatKeys.MANA_ON_CULL, skill.tags());
                        // 2026-10-28 照 PoE2 血鐮：撲殺時獲得固定的生命（逐級）；這次之前只有最大生命的 %
                        final double lifeFlat = cullSheet.flat(tw.exilecore.stats.GrantedSkillKeys.LIFE_ON_CULL_FLAT, skill.tags());
                        if (lifePct > 0 || manaPct > 0 || lifeFlat > 0) {
                            final PlayerState culler = plugin.players().get((Player) attacker);
                            if (!culler.ruined(plugin.getServer().getCurrentTick())) { // 2026-10-18：荒蕪禁療中不回生命
                                culler.setLife(Math.min(culler.maxLife(), culler.life()
                                        + tw.exilecore.stats.GrantedSkillKeys.cullLife(culler.maxLife(), lifePct, lifeFlat)));
                            }
                            culler.setMana(Math.min(culler.maxMana(), culler.mana() + culler.maxMana() * manaPct / 100.0));
                            plugin.players().display((Player) attacker, culler);
                        }
                        // 2026-10-28 照 PoE2 斷魂掌品質：撲殺時 4 秒內回復 x% 最大生命／魔力（量照撲殺當下的上限）；這次之前一次回
                        final double lifeSlow = cullSheet.flat(StatKeys.LIFE_ON_CULL_OVER_TIME, skill.tags());
                        final double manaSlow = cullSheet.flat(StatKeys.MANA_ON_CULL_OVER_TIME, skill.tags());
                        if (lifeSlow > 0 || manaSlow > 0) {
                            final PlayerState culler = plugin.players().get((Player) attacker);
                            recoverOverTime((Player) attacker, culler.maxLife() * lifeSlow / 100.0, culler.maxMana() * manaSlow / 100.0);
                        }
                    });
                }
            }
            if (credit != null && !blocked) {
                final double lifeOnHit = skillSheet.flat(StatKeys.LIFE_ON_HIT, skill.tags());
                if (attackerIsPlayer) {
                    // 2026-10-18：照 PoE，圖騰（含英靈）打中的偷取、擊中回復生命不回給玩家
                    if (!totemHit(skill)) {
                        defer(defender, () -> leech(credit, attackerSheet, skill, result)); // 2026-10-18：偷取等事件確定沒被取消才存
                        manaOnHit(credit, skillSheet.flat(StatKeys.MANA_ON_HIT, skill.tags()), defender);
                        if (lifeOnHit > 0) {
                            pendingLifeGains.put(defender.getUniqueId(), new LifeGain(credit, lifeOnHit));
                        }
                    }
                } else {
                    // 2026-10-29 照 PoE2（Life Leech 可以輔助召喚技能）：召喚物的偷取回給召喚物自己，跟玩家一樣存成一份一份的偷取慢慢回
                    // （MinionHooks.leech：一份最多牠最大生命的 10%、1 秒回完、每秒最多 20%）；這次之前一下直接回、最多最大生命的 10%
                    final double leeched = tw.exilecore.skill.MinionTypes.leech(skillSheet, skill, result.dealt());
                    final double heal = Math.max(0, lifeOnHit);
                    if (heal > 0 || leeched > 0) {
                        defer(defender, () -> { // 召喚物的擊中回復生命回給牠自己（2026-10-18：等事件確定沒被取消）
                            if (heal > 0) {
                                minions.heal(attacker, heal);
                            }
                            if (leeched > 0) {
                                minions.leech(attacker, leeched);
                            }
                        });
                    }
                }
                // 真正扣掉的生命（溢出的不算）：剩下的原版血量換回我們的生命
                final double scale = plugin.monsters().healthScale(defender, defenderMonster);
                final double remaining = scale > 0 ? defender.getHealth() / scale : total;
                pendingCredits.put(defender.getUniqueId(), new Credit(credit.getUniqueId(), Math.min(total, remaining)));
                // 2026-10：戰鬥數據（側欄的「輸出」）也照 Boss 每秒傷害上限之後的算：以前是上限前的，跟傷害排行、實際扣的血對不起來
                final tw.exilecore.stats.DamagePacket shown = total < result.total() && result.total() > 0
                        ? new tw.exilecore.stats.DamagePacket(result.dealt()).scale(total / result.total()) : result.dealt();
                pendingNumbers.put(defender.getUniqueId(), new PendingNumber(credit, total, result.crit(), shown, attackerIsPlayer && !totemHit(skill), // 2026-10-21：圖騰的暴擊不給你銳星
                        chargeChances(attackerIsPlayer, skill, attackerSheet), meterName(skill)));
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
        if (signatures != null && attackerIsPlayer && !totemHit(skill)) { // 2026-10-21：圖騰、英靈打中的不觸發血宴、雷魂（照 PoE）
            signatures.dealt((Player) attacker, attackerSheet, skill, result); // 雷魂、血宴
        }
        feedback(attacker, defender, result);
    }

    /** 2026-10-29：召喚物打中的那一刻目標的狀態（MinionTypes.conditional 用；跟玩家那段一樣的判斷）。 */
    private tw.exilecore.skill.MinionTypes.Target minionTarget(final LivingEntity attacker, final LivingEntity defender, final MonsterData monster,
                                                             final AilmentService ailments, final long tick) {
        final org.bukkit.attribute.AttributeInstance maxHealth = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double fraction = maxHealth == null || maxHealth.getValue() <= 0 ? 1 : defender.getHealth() / maxHealth.getValue();
        final boolean unique = monster != null && (monster.boss() || monster.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE);
        final boolean rare = unique || monster != null && monster.rarity() == tw.exilecore.monster.MonsterRarity.RARE;
        final double distance = attacker.getWorld().equals(defender.getWorld()) ? attacker.getLocation().distance(defender.getLocation()) : Double.MAX_VALUE;
        final java.util.Set<String> words = new java.util.HashSet<>();
        boolean immobilised = debuffs.has(defender.getUniqueId(), "pin", tick);
        if (ailments != null) {
            for (final tw.exilecore.ailment.AilmentType type : new tw.exilecore.ailment.AilmentType[] {tw.exilecore.ailment.AilmentType.CHILL,
                    tw.exilecore.ailment.AilmentType.FREEZE, tw.exilecore.ailment.AilmentType.IGNITE, tw.exilecore.ailment.AilmentType.SHOCK,
                    tw.exilecore.ailment.AilmentType.POISON, tw.exilecore.ailment.AilmentType.BLEED}) {
                if (type == tw.exilecore.ailment.AilmentType.FREEZE ? ailments.isFrozen(defender) : ailments.has(defender, type)) {
                    words.add(ailmentWord(type));
                }
            }
            immobilised |= ailments.isFrozen(defender) || ailments.heavyStunned(defender) || ailments.has(defender, tw.exilecore.ailment.AilmentType.ELECTROCUTE);
        }
        return new tw.exilecore.skill.MinionTypes.Target(fraction <= 0.35, fraction >= 0.999, rare, unique, debuffs.cursed(defender.getUniqueId(), tick),
                distance <= StatKeys.CLOSE_RANGE, distance, words, immobilised);
    }

    /** 撲殺的門檻（剩下的生命比例）：普通 35%、魔法 20%、稀有 10%、傳奇與 Boss 5%（2026-10-27 照 PoE2 CullingStrikeNormalThreshold 35；這次之前普通 30%）。 */
    public static double cullThreshold(final MonsterData monster) {
        if (monster == null) {
            return 0;
        }
        if (monster.boss()) {
            return 0.05;
        }
        return switch (monster.rarity()) {
            case NORMAL -> 0.35;
            case MAGIC -> 0.20;
            case RARE -> 0.10;
            case UNIQUE -> 0.05;
        };
    }

    private static boolean cull(final EntityDamageByEntityEvent event, final LivingEntity defender, final MonsterData monster) {
        return cull(event, defender, monster, 1.0);
    }

    /** 同上，門檻再乘 scale（2026-10-26：撲殺 II 的「撲殺門檻增加」）。 */
    private static boolean cull(final EntityDamageByEntityEvent event, final LivingEntity defender, final MonsterData monster, final double scale) {
        final double threshold = cullThreshold(monster) * scale;
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
        leech(player, sheet, skill, result, 1.0);
    }

    /** @param capScale 競技場的上限把這一下壓到幾成（2026-10-17：偷取照上限後真的打掉的算，以前照上限前的整下）。 */
    private void leech(final Player player, final StatSheet sheet, final SkillProfile skill, final HitResult result, final double capScale) {
        final StatSheet effective = skill.effective(sheet);
        if (StatKeys.flag(effective, StatKeys.FLAG_NO_LEECH)) {
            return; // 地圖詞綴：無法從怪物身上偷取
        }
        if (plugin.players().get(player).ruined(plugin.getServer().getCurrentTick())) {
            return; // 2026-10-18：荒蕪（黑水）禁療中偷不到：生命、魔力偷取都不存，立即偷取也不回（以前立即偷取照回、偷取為魔力照回）
        }
        double life = 0;
        double mana = 0;
        final double physical = result.dealt().get(DamageType.PHYSICAL);
        if (physical > 0) {
            final java.util.Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            life += physical * effective.flat(StatKeys.LEECH_LIFE, context) / 100.0;
            if (!StatKeys.flag(effective, tw.exilecore.stats.LineageKeys.MANA_LEECH_ELEMENTAL)) { // 2026-10-27 照 PoE2 元汲：魔力偷取照元素傷害算
                mana += physical * effective.flat(StatKeys.LEECH_MANA, context) / 100.0;
            }
        }
        if (StatKeys.flag(effective, tw.exilecore.stats.LineageKeys.MANA_LEECH_ELEMENTAL)) {
            final double elemental = result.dealt().get(DamageType.FIRE) + result.dealt().get(DamageType.COLD) + result.dealt().get(DamageType.LIGHTNING);
            mana += elemental * effective.flat(StatKeys.LEECH_MANA, skill.contextFor(DamageType.PHYSICAL)) / 100.0;
        }
        // 2026-10：元素傷害偷取為生命
        final boolean elementalAll = StatKeys.flag(effective, StatKeys.FLAG_LEECH_ELEMENTAL_ALL);
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            final double dealt = result.dealt().get(type);
            if (dealt > 0) {
                life += dealt * effective.flat(StatKeys.LEECH_LIFE_ELEMENTAL, skill.contextFor(type)) / 100.0;
                if (elementalAll || type == DamageType.LIGHTNING && StatKeys.flag(effective, StatKeys.FLAG_LEECH_LIGHTNING)) {
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
        if (skill.isSpell()) { // 2026-10：只算法術（反傷、擊殺爆炸、臨在灼燒不是法術）
            life += result.dealt().total() * effective.flat(StatKeys.LEECH_SPELL, java.util.Set.of()) / 100.0;
        }
        if (StatKeys.flag(effective, tw.exilecore.item.unique.UniqueStats.CANNOT_LEECH_MANA)) {
            mana = 0; // 2026-10-28 照 PoE2（磐石之拳的意識之緣）：無法偷取魔力
        }
        if (life <= 0 && mana <= 0) {
            return;
        }
        final double scale = Math.max(0, effective.apply(StatKeys.LEECH_EFFECT, 1.0, java.util.Set.of())) * Math.max(0, Math.min(1, capScale));
        life *= scale;
        mana *= scale * Math.max(0, 1 + effective.increased(StatKeys.LEECH_MANA_EFFECT, java.util.Set.of())); // 2026-10-21：只加魔力偷取量的
        // 立即偷取（2026-10，照 PoE）：x% 立即生效，其餘照常存起來慢慢回；一下最多立即回 {@value #INSTANT_LEECH_CAP} 的最大生命／魔力
        final double instant = StatKeys.flag(effective, StatKeys.FLAG_INSTANT_LEECH) ? 1.0
                : Math.max(0, Math.min(1, effective.flat(StatKeys.LEECH_INSTANT, java.util.Set.of()) / 100.0));
        final PlayerState state = plugin.players().get(player);
        boolean changed = false;
        // 2026-10-28 照 PoE2：生命偷取改成偷取能量護盾（精華收割器）、魔力偷取也回復能量護盾（磐石之拳的精華收割器）。
        // 我們沒有能量護盾的偷取池：照偷取一份的上限（最大能量護盾 10%）直接回
        double esLeech = 0;
        if (life > 0 && StatKeys.flag(effective, tw.exilecore.item.unique.UniqueStats.LIFE_LEECH_TO_ES)) {
            esLeech += life;
            life = 0;
        }
        if (mana > 0 && StatKeys.flag(effective, tw.exilecore.item.unique.UniqueStats.MANA_LEECH_ALSO_ES)) {
            esLeech += mana;
        }
        if (esLeech > 0 && state.energyShield() < state.maxEnergyShield()) {
            state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield() + Math.min(esLeech, state.maxEnergyShield() * ES_LEECH_CAP)));
            changed = true;
        }
        if (life > 0) {
            final double now = Math.min(life * instant, state.maxLife() * INSTANT_LEECH_CAP);
            if (now > 0 && state.life() < state.maxLife()) {
                state.setLife(Math.min(state.maxLife(), state.life() + now));
                changed = true;
            }
            state.addHitLeech(life - now); // 2026-10-27 照 PoE2：一份最多最大生命 10%、1 秒回完、每秒最多 20%（Leech）
            if (plugin.skills() != null && plugin.skills().treeMechanics() != null) {
                plugin.skills().treeMechanics().leechedLife(player, effective, life); // 2026-10-26 照 PoE2 天賦：夥伴也偷取同樣多的生命
            }
        }
        if (mana > 0) {
            final double now = Math.min(mana * instant, state.maxMana() * INSTANT_LEECH_CAP);
            if (now > 0 && state.mana() < state.maxMana()) {
                state.setMana(Math.min(state.maxMana(), state.mana() + now));
                changed = true;
            }
            state.addHitManaLeech(mana - now); // 2026-10-27 照 PoE2：同上
        }
        if (changed) {
            plugin.players().display(player, state);
        }
    }

    /** 2026-10-28：偷取為能量護盾一下最多回多少（最大能量護盾的比例；照 PoE2 偷取一份最多 10%）。 */
    static final double ES_LEECH_CAP = 0.10;

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

    /** 2026-10-18：這一下是不是圖騰打的（圖騰自己放的技能、英靈、引爆、焚鏈、怨燼；見 SkillDeployables）。 */
    private static boolean totemHit(final SkillProfile skill) {
        return skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT);
    }

    /** 競技場裡打中敵方玩家或召喚物：跟打怪一樣冒傷害數字；玩家本人打的還有偷取與擊中回復生命（等事件確定沒被取消）。 */
    private void pvpRewards(final boolean attackerIsPlayer, final Player credit, final StatSheet attackerSheet, final SkillProfile skill,
                            final HitResult result, final LivingEntity defender) {
        if (credit == null) {
            return;
        }
        pendingNumbers.put(defender.getUniqueId(), new PendingNumber(credit, result.total(), result.crit(), result.dealt(), attackerIsPlayer && !totemHit(skill),
                chargeChances(attackerIsPlayer, skill, attackerSheet), meterName(skill)));
        if (!attackerIsPlayer || totemHit(skill)) {
            return; // 2026-10-18：圖騰打的不給玩家偷取、擊中回復生命（照 PoE）
        }
        final double capScale = lastPvpCapScale;
        defer(defender, () -> leech(credit, attackerSheet, skill, result, capScale)); // 2026-10-18：偷取等事件確定沒被取消才存
        final double lifeOnHit = skill.effective(attackerSheet).flat(StatKeys.LIFE_ON_HIT, skill.tags());
        if (lifeOnHit > 0) {
            pendingLifeGains.put(defender.getUniqueId(), new LifeGain(credit, lifeOnHit));
        }
        manaOnHit(credit, skill.effective(attackerSheet).flat(StatKeys.MANA_ON_HIT, skill.tags()), defender);
    }

    /** 2026-10-26：擊中回復魔力（PoE2 的魔力吸取）：等事件確定沒被取消才回。 */
    private void manaOnHit(final Player credit, final double amount, final LivingEntity defender) {
        if (credit == null || amount <= 0) {
            return;
        }
        defer(defender, () -> {
            final PlayerState state = plugin.players().get(credit);
            state.setMana(Math.min(state.maxMana(), state.mana() + amount));
            plugin.players().display(credit, state);
        });
    }

    /**
     * 2026-10-28 照 PoE2（斷魂掌品質「撲殺一名敵人時，在 4 秒內恢復 x%」）：把 life、mana 平均分成
     * {@link tw.exilecore.skill.KillingPalm#RECOVERY_PULSES} 次、每 {@link tw.exilecore.skill.KillingPalm#RECOVERY_PERIOD_TICKS} tick 回一次
     * （合計 4 秒）。每次撲殺各自一份，可以疊；荒蕪禁療中那一次不回生命；死了、下線就停。
     */
    private void recoverOverTime(final Player player, final double life, final double mana) {
        final int pulses = tw.exilecore.skill.KillingPalm.RECOVERY_PULSES;
        final double lifeEach = tw.exilecore.skill.KillingPalm.perPulse(life);
        final double manaEach = tw.exilecore.skill.KillingPalm.perPulse(mana);
        if (lifeEach <= 0 && manaEach <= 0) {
            return;
        }
        final int[] left = {pulses};
        final long period = tw.exilecore.skill.KillingPalm.RECOVERY_PERIOD_TICKS;
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || player.isDead() || left[0]-- <= 0) {
                task.cancel();
                return;
            }
            final PlayerState state = plugin.players().get(player);
            if (lifeEach > 0 && !state.ruined(plugin.getServer().getCurrentTick())) {
                state.setLife(Math.min(state.maxLife(), state.life() + lifeEach));
            }
            if (manaEach > 0) {
                state.setMana(Math.min(state.maxMana(), state.mana() + manaEach));
            }
            plugin.players().display(player, state);
            if (left[0] <= 0) {
                task.cancel();
            }
        }, period, period);
    }

    /** 荊棘：對剛打到玩家的怪物反彈一筆物理傷害（算玩家的攻擊：吃玩家的屬性表、擊殺算玩家的）。 */
    private void reflect(final Player player, final LivingEntity target) {
        final SkillProfile thorns = Thorns.profile(plugin.players().sheet(player)); // 2026-10-26：也算傳奇的火焰、冰冷荊棘
        if (thorns == null || !target.isValid() || target.isDead()) {
            return;
        }
        skills.hit(player, target, thorns, null);
    }

    /** 2026-10-17：下線清掉環境傷害的冷卻與三相共鳴（以前每個進過伺服器的人各留一筆）。 */
    @EventHandler
    public void onQuitCleanup(final org.bukkit.event.player.PlayerQuitEvent event) {
        environmentCooldown.remove(event.getPlayer().getUniqueId());
        trinity.forget(event.getPlayer().getUniqueId());
    }

    /** 環境傷害（掉落、火、岩漿、溺水…）只處理玩家。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironmentDamage(final EntityDamageEvent event) {
        // 2026-10-17：雷擊、TNT、掉落的鐵砧、藥水雲這些「實體造成」但找不到攻擊者的，也照環境傷害換算
        // （以前兩邊都不管，玩家直接吃原版的數字、扣原版血量）
        if (event instanceof EntityDamageByEntityEvent byEntity && resolveAttacker(byEntity.getDamager()) != null) {
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
    /** 2026-10-28：這一下是稀有、傳奇怪物打的（applyToPlayer 的補償用；主執行緒，呼叫完就清掉）。 */
    private boolean hitByRare;

    /** 上一次 applyToPlayer 的競技場上限比例（主執行緒；緊接著的 pvpRewards 用）。 */
    private double lastPvpCapScale = 1.0;

    private void applyToPlayer(final Player player, final PlayerState state, final double esDamage,
                               final double lifeDamage, final EntityDamageEvent event,
                               final String source, final String skillName, final DamagePacket dealt, final DamagePacket outgoing,
                               final LivingEntity thornsTarget, final boolean byEnemy, final Player pvpAttacker) {
        final StatSheet sheet = plugin.players().sheet(player);
        double esTaken = esDamage;
        double lifeTaken = lifeDamage;
        if (pvpAttacker != null && plugin.pvp() != null && esTaken + lifeTaken > 0) {
            // PvP 保底（2026-10）：單一下、每秒最多打掉總血量的幾 %（pvp.yml）；超過的不算
            final double total = esTaken + lifeTaken;
            final double allowed = plugin.pvp().limit(player, total, true);
            if (allowed < total) {
                esTaken *= allowed / total;
                lifeTaken *= allowed / total;
            }
            lastPvpCapScale = total <= 0 ? 1.0 : Math.min(1.0, allowed / total);
        } else {
            lastPvpCapScale = 1.0;
        }
        if (byEnemy) {
            // 相位形態（時空術士）：受到的傷害更少 30%，4 秒後再受到那一擊 30% 的傷害
            if (StatKeys.flag(sheet, StatKeys.FLAG_PHASED_FORM) && esTaken + lifeTaken > 0) {
                final double later = (esTaken + lifeTaken) * 0.3;
                esTaken *= 0.7;
                lifeTaken *= 0.7;
                defer(player, () -> delayedLoss(player, later, 80, 1, true)); // 2026-10-18：事件被取消就不排延遲扣血
            }
            // 2026-10-26 照 PoE2 天賦：擊中傷害的 x% 先由你的夥伴的生命承受（沒有夥伴照常）
            final double toCompanion = sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.COMPANION_TAKES, java.util.Set.of());
            if (toCompanion > 0 && esTaken + lifeTaken > 0 && plugin.skills() != null && plugin.skills().treeMechanics() != null) {
                final double total = esTaken + lifeTaken;
                final double absorbed = plugin.skills().treeMechanics().companionAbsorb(player, total * Math.min(100, toCompanion) / 100.0);
                if (absorbed > 0) {
                    esTaken *= 1 - absorbed / total;
                    lifeTaken *= 1 - absorbed / total;
                }
            }
            // 2026-10-27 照 PoE2 伴償（Romira's Requital）：擊中傷害的 10% 先由夥伴承受，你補償其中 200% 為生命
            final double romira = plugin.skills() == null || plugin.skills().treeMechanics() == null ? 0
                    : plugin.skills().supportMechanics().lineage().companionShare(player);
            if (romira > 0 && esTaken + lifeTaken > 0) {
                final double total = esTaken + lifeTaken;
                final double absorbed = plugin.skills().treeMechanics().companionAbsorb(player, total * Math.min(100, romira) / 100.0);
                if (absorbed > 0) {
                    esTaken *= 1 - absorbed / total;
                    lifeTaken *= 1 - absorbed / total;
                    plugin.skills().supportMechanics().lineage().companionAbsorbed(player, absorbed);
                }
            }
            // 2026-10-26 照 PoE2 昇華「堅木之壘」：擊中傷害的 x% 先從你最近的圖騰的生命扣（沒有圖騰照常）
            final double toTotem = sheet.flat(tw.exilecore.stats.TotemKeys.TAKES_DAMAGE, java.util.Set.of());
            if (toTotem > 0 && esTaken + lifeTaken > 0 && plugin.skills() != null) {
                final double total = esTaken + lifeTaken;
                final double absorbed = plugin.skills().totemAbsorb(player, total * Math.min(100, toTotem) / 100.0);
                if (absorbed > 0) {
                    esTaken *= 1 - absorbed / total;
                    lifeTaken *= 1 - absorbed / total;
                }
            }
            // 緊握傷口（血術士）：擊中扣的生命有 25% 改成 4 秒內慢慢扣
            if (StatKeys.flag(sheet, StatKeys.FLAG_GRASPING_WOUNDS) && lifeTaken > 0) {
                final double later = lifeTaken * 0.25;
                lifeTaken -= later;
                defer(player, () -> delayedLoss(player, later, 10, 8, false)); // 2026-10-18：事件被取消就不排延遲扣血
            }
            // 2026-10-26 照 PoE2 血肉獻祭（不朽恨意，阿姆納姆）：擊中扣的生命避免 20%，再在 4 秒內失去避免掉的 150%
            if (StatKeys.flag(sheet, tw.exilecore.passive.ConquestEffects.FLESH) && lifeTaken > 0) {
                final double prevented = lifeTaken * tw.exilecore.passive.ConquestEffects.FLESH_PREVENTED;
                lifeTaken -= prevented;
                final double later = prevented * tw.exilecore.passive.ConquestEffects.FLESH_LOST;
                defer(player, () -> delayedLoss(player, later, 10, 8, false));
            }
        }
        if (esTaken + lifeTaken > 0 && plugin.skills() != null) {
            final double composed = esTaken + lifeTaken; // 2026-10-26 照 PoE2 Helbrym's Hide（蟄甲）：沉著期間受到的傷害記下來
            defer(player, () -> plugin.skills().druidSupports().damaged(player, composed));
        }
        // 守護（2026-10，護符詞綴）：效果期間先替你吃掉傷害（先吃護盾那份、再吃生命那份）
        if (esTaken + lifeTaken > 0 && state.guard(plugin.getServer().getCurrentTick()) > 0) {
            final long now = plugin.getServer().getCurrentTick();
            // 2026-10-18：這裡只算會吃掉多少（跟 absorbGuard 同樣的算法），真的扣守護等 MONITOR 確定事件沒被取消
            final double available = state.guard(now);
            final double fromEs = esTaken > 0 ? Math.min(available, esTaken) : 0;
            esTaken -= fromEs;
            final double fromLife = lifeTaken > 0 ? Math.min(available - fromEs, lifeTaken) : 0;
            lifeTaken -= fromLife;
            if (fromEs + fromLife > 0) {
                defer(player, () -> state.absorbGuard(fromEs + fromLife, now));
            }
        }
        // 2026-10-26（昇華）：黑暗、堅木之壘、無魂型態、心宇和諧、瓦拉什塔的直覺、永生（AscendancyEffects）
        double ascMana = 0;
        if (byEnemy && esTaken + lifeTaken > 0) {
            final double[] asc = tw.exilecore.ascendancy.AscendancyEffects.absorb(player, state, sheet, esTaken, lifeTaken, dealt);
            esTaken = asc[0];
            lifeTaken = asc[1];
            ascMana = asc[2];
        }
        // 傳奇「受到的傷害先從魔力扣」與「受到的傷害有 x% 之後慢慢回成生命」
        final IncomingDamage rawSplit = IncomingDamage.split(esTaken, lifeTaken, Math.max(0, state.mana() - ascMana),
                sheet.flat(StatKeys.DAMAGE_FROM_MANA, java.util.Set.of()), sheet.flat(StatKeys.LIFE_RECOUP, java.util.Set.of())
                        + (hitByRare ? sheet.flat(tw.exilecore.item.unique.UniqueStats.LIFE_RECOUP_VS_RARE, java.util.Set.of()) : 0));
        final IncomingDamage split = ascMana > 0
                ? new IncomingDamage(rawSplit.es(), rawSplit.life(), rawSplit.mana() + ascMana, rawSplit.recoup()) : rawSplit;
        final boolean lethal = state.life() - split.life() <= 0;
        event.setDamage(lethal ? LETHAL : 0);
        final double manaRecoup = (split.es() + split.life() + split.mana())
                * Math.max(0, Math.min(100, sheet.flat(StatKeys.MANA_RECOUP, java.util.Set.of()))) / 100.0; // 2026-10-21：腐化「受到的傷害回復為魔力」
        pending.put(player.getUniqueId(), new Pending(split.es(), split.life(), split.mana(), split.recoup(), manaRecoup, source, skillName, dealt, outgoing,
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
                state.setLastDamageTick(plugin.getServer().getCurrentTick()); // 2026-10-17：延遲扣血也算在戰鬥中
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
        // 2026-10-30：打你的那隻身上有詛咒、印記的話也寫出來（/hits、死亡回顧看得出來是不是被詛咒的怪打的）
        final String hex = plugin.ailments() == null ? "" : plugin.ailments().hexText(attacker.getUniqueId(), plugin.getServer().getCurrentTick(), false);
        return DeathRecap.describe(data.get().name(), data.get().level(),
                data.get().rarity() == tw.exilecore.monster.MonsterRarity.NORMAL ? null : data.get().rarity().displayName())
                + (hex.isEmpty() ? "" : hex + damageFactorText(attacker) + "<gray>");
    }

    /** 2026-10-30（查萎力詛咒）：打你的怪身上的減益讓牠的傷害乘多少（「更多／更少傷害」相乘）；沒有就是空的。 */
    private String damageFactorText(final LivingEntity attacker) {
        double factor = 1;
        boolean any = false;
        for (final Modifier modifier : debuffs.modifiers(attacker.getUniqueId(), null, plugin.getServer().getCurrentTick())) {
            if (StatKeys.DAMAGE.equals(modifier.stat()) && modifier.type() == tw.exilecore.stats.ModType.MORE && modifier.tags().isEmpty()) {
                factor *= 1 + modifier.value();
                any = true;
            }
        }
        return any ? String.format(java.util.Locale.ROOT, "<dark_gray>[傷害 ×%.2f]", Math.max(0, factor)) : "";
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
        // 2026-10-18：HIGH 記下的副作用（延遲扣血、守護、偷取、收命收魂）：取消了就丟掉，沒取消才執行（在套用傷害之前，跟以前的順序一樣）
        final java.util.List<Runnable> effects = pendingEffects.remove(event.getEntity().getUniqueId());
        if (effects != null && !event.isCancelled()) {
            for (final Runnable effect : effects) {
                // 2026-10-18：一個副作用出錯不要讓後面的（其他副作用、記傷害、異常）全部跳過
                try {
                    effect.run();
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().error("傷害的延遲效果出錯（{}）", event.getEntity().getType(), e);
                }
            }
        }
        final PendingAilment ailment = pendingAilments.remove(event.getEntity().getUniqueId());
        final Credit credit = pendingCredits.remove(event.getEntity().getUniqueId());
        if (credit != null && !event.isCancelled()) {
            plugin.monsters().ledger().record(event.getEntity().getUniqueId(), credit.player(), credit.amount());
        }
        final PendingNumber number = pendingNumbers.remove(event.getEntity().getUniqueId());
        if (number != null && !event.isCancelled() && event.getEntity() instanceof LivingEntity target && number.player().isOnline()) {
            numbers.hit(number.player(), target, number.amount(), number.crit(), number.dealt());
            if (hud != null) {
                hud.dealt(number.player(), number.skillName(), number.dealt().asMap()); // 戰鬥數據：自己與召喚物打出的（分技能）
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
            if (attackerState.hasCharacter() && !attackerState.isDead() && attackerState.life() < attackerState.maxLife()
                    && !attackerState.ruined(plugin.getServer().getCurrentTick())) { // 2026-10-18：荒蕪（黑水）禁療中不回
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
                plugin.players().conditions().note(player, tw.exilecore.stats.TreeConditions.HITS_TAKEN, "hit",
                        plugin.getServer().getCurrentTick()); // 2026-10-26：天賦樹「最近每被擊中一次」
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
                    // 2026-10-26 照 PoE2：回復 4 秒內平均回來（吃回復速度、生命回復速度）；這次之前存進偷取（每秒最多 10%）
                    final StatSheet recoupSheet = plugin.players().sheet(player);
                    final double recoupSpeed = tw.exilecore.stats.TreeEffectKeys.scale(recoupSheet, tw.exilecore.stats.TreeEffectKeys.RECOUP_SPEED,
                            java.util.Set.of(), 0.1);
                    if (damage.recoup() > 0 && !state.ruined(plugin.getServer().getCurrentTick())) { // 2026-10-18：禁療中不存
                        state.recoup().add(tw.exilecore.character.Recoup.LIFE, damage.recoup(), recoupSpeed
                                * tw.exilecore.stats.TreeEffectKeys.recoveryRate(recoupSheet, tw.exilecore.stats.TreeEffectKeys.LIFE_RECOVERY_RATE));
                    }
                    if (damage.manaRecoup() > 0) { // 2026-10-27 照 PoE2：魔力回復也吃魔力回復速度（這次之前只吃回復速度）
                        state.recoup().add(tw.exilecore.character.Recoup.MANA, damage.manaRecoup(), recoupSpeed
                                * tw.exilecore.stats.TreeEffectKeys.recoveryRate(recoupSheet, tw.exilecore.stats.TreeEffectKeys.MANA_RECOVERY_RATE));
                    }
                    // 2026-10-26 照 PoE2 天賦樹：受到的元素傷害有 x% 回復為能量護盾
                    final double esRecoup = Math.min(100, recoupSheet.flat(tw.exilecore.stats.TreeEffectKeys.ES_RECOUP_ELEMENTAL, java.util.Set.of()));
                    final double dealtTotal = damage.dealt().total();
                    if (esRecoup > 0 && dealtTotal > 0) {
                        final double elemental = (damage.dealt().get(DamageType.FIRE) + damage.dealt().get(DamageType.COLD)
                                + damage.dealt().get(DamageType.LIGHTNING)) / dealtTotal;
                        state.recoup().add(tw.exilecore.character.Recoup.ES,
                                (damage.esDamage() + damage.lifeDamage() + damage.manaDamage()) * elemental * esRecoup / 100.0, recoupSpeed);
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
                    ailment.result(), ailment.taken(), ailment.defenderMonster(), ailment.stunScale(), ailment.typeTaken());
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
    /** 2026-10-21（PoE2 腐化）：格擋時回復 x 生命／魔力（直接回，禁療中生命不回）。 */
    private void gainOnBlock(final Player player, final StatSheet sheet) {
        final PlayerState state = plugin.players().get(player);
        if (state == null || !state.hasCharacter() || state.isDead()) {
            return;
        }
        final double life = sheet.flat(StatKeys.LIFE_ON_BLOCK, java.util.Set.of());
        final double mana = sheet.flat(StatKeys.MANA_ON_BLOCK, java.util.Set.of());
        boolean changed = false;
        if (life > 0 && state.life() < state.maxLife() && !state.ruined(plugin.getServer().getCurrentTick())) {
            state.setLife(Math.min(state.maxLife(), state.life() + life));
            changed = true;
        }
        if (mana > 0 && state.mana() < state.maxMana()) {
            state.setMana(Math.min(state.maxMana(), state.mana() + mana));
            changed = true;
        }
        if (changed) {
            plugin.players().display(player, state);
        }
    }

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

    /** 2026-10-30 照 PoE2 Parry：招架擋得到的距離（公尺；近戰 1、投射物 1.5）。 */
    static final double PARRY_MELEE_METRES = 1.0;
    static final double PARRY_PROJECTILE_METRES = 1.5;

    /** 兩隻生物碰撞箱之間的距離（貼在一起是 0）。 */
    static double edgeDistance(final org.bukkit.entity.Entity a, final org.bukkit.entity.Entity b) {
        final org.bukkit.util.BoundingBox x = a.getBoundingBox();
        final org.bukkit.util.BoundingBox y = b.getBoundingBox();
        final double dx = Math.max(0, Math.max(x.getMinX() - y.getMaxX(), y.getMinX() - x.getMaxX()));
        final double dy = Math.max(0, Math.max(x.getMinY() - y.getMaxY(), y.getMinY() - x.getMaxY()));
        final double dz = Math.max(0, Math.max(x.getMinZ() - y.getMaxZ(), y.getMinZ() - x.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

}
