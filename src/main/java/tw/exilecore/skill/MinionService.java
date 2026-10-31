package tw.exilecore.skill;

import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.Corpses;
import tw.exilecore.combat.MinionHooks;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterSheets;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 玩家的召喚物（殭屍、骷髏、焚魂、魔像）：原版生物當身體，AI 由我們管——
 * 原版的移動與找目標的 AI 全部拿掉（才不會去打玩家、村民），每 tick 由這裡決定要跟著主人、追哪隻怪、什麼時候打。
 * <ul>
 *   <li>目標：主人剛打過的怪優先，不然是自己附近最近的怪；離主人太遠的怪不追，跑太遠就瞬移回主人身邊。</li>
 *   <li>打人：用原版的傷害事件（傷害來源是召喚物），{@link tw.exilecore.combat.CombatListener} 透過 {@link MinionHooks}
 *       換成主人技能描述、召喚物屬性表算出來的數字；掉落、經驗、傷害數字都算主人的。</li>
 *   <li>被打：怪物會回頭打召喚物（原版的仇恨），每隔一下附近沒目標的怪也會先打召喚物；玩家與召喚物之間互不傷害。</li>
 *   <li>數值每 2 秒照主人目前的屬性表重算（換裝、配點馬上跟上）。</li>
 * </ul>
 * 主人下線、換角色就全部收掉；伺服器重開不會留下（不存檔）。
 */
public final class MinionService implements Listener, MinionHooks {

    /** 離主人超過這麼遠（格）就瞬移回來。 */
    static final double TELEPORT_DISTANCE = 28;
    /** 沒目標時離主人超過這麼遠就走回來。 */
    static final double FOLLOW_START = 5;
    static final double FOLLOW_STOP = 2.5;
    /** 主人附近多遠內的怪，召喚物會主動去打（2026-10：改成以主人為中心，16 → 12，免得跑出畫面外去打）。 */
    static final double AGGRO = 12;
    /** 目標離主人超過這麼遠就不追了（2026-10：22 → 16）。 */
    static final double LEASH = 16;
    /** 附近沒目標的怪多遠內會先打召喚物。 */
    static final double PASSIVE_TAUNT = 5;
    /** 主人剛打過的怪算「主人的目標」幾 tick。 */
    static final long FOCUS_TICKS = 60;
    /** 焚魂飛行速度（格／tick）。 */
    static final double FLY_SPEED = 0.32;
    /** 一個玩家全部的召喚物最多幾隻（各技能的上限另外算；2026-10-30 照 PoE2：精魂召喚不算在內；2026-10-16：40 → 50；2026-10-27 照站長：50 → 40；2026-10-29 照站長：40 → 60 → 80）。 */
    static final int MAX_PER_PLAYER = 80;

    /** 一隻召喚物。 */
    static final class Minion {
        final UUID owner;
        /** 叫牠出來的技能（等級、輔助）；焚魂這類補時間時換成最新的。 */
        SkillBuild build;
        final SummonSpec spec;
        final Mob mob;
        /** 頭上的名字（技能名與主人；血不滿時後面加百分比）。 */
        final String name;
        final long bornTick;
        /** 到哪個 tick 消失（0 = 永久）。 */
        long untilTick;
        /** 上一次重算屬性表用的材料（2026-10）：都沒變就不重算、不重送血量與速度。 */
        SkillBuild builtFor;
        MinionSheets.Base builtBase;
        List<Modifier> builtModifiers;
        StatSheet sheet;
        /** 疊好輔助寶石的屬性表（打人、被打都用這張）。 */
        StatSheet effective;
        SkillProfile profile;
        double healthScale = 1;
        int attackTicks = 20;
        double regenPercent;
        UUID target;
        long nextAttack;
        long nextPath;
        long nextRetarget;
        long nextTaunt;
        long nextRebuild;
        boolean idleStopped;
        /** 卡住偵測（2026-10）：上次檢查的位置、連續幾次該走卻沒動。 */
        double lastX = Double.NaN;
        double lastZ = Double.NaN;
        int stillChecks;
        /** 祭品（2026-10）給的增益與到期的 tick。 */
        List<Modifier> offering = List.of();
        long offeringUntil;
        /** 2026-10-26（天賦樹：夥伴擊殺時疾速）：額外的增益與到期的 tick。 */
        List<Modifier> treeBuff = List.of();
        long treeBuffUntil;
        /** 2026-10-30 照 PoE2 Ancestral Spirits：先祖之靈的型態（其他召喚物是 null）。 */
        AncestralForms form;
        /** 2026-10-30：先祖玉獸的戰吼、先祖戰角的號角、先祖靈龜的光環給召喚物的增益（各自一份）與到期的 tick。 */
        final Map<String, List<Modifier>> allyBuffs = new HashMap<>();
        final Map<String, Long> allyBuffUntil = new HashMap<>();
        /** 強化（2026-10 PoE2 喚屍：吃銳星叫出來的那隻）：一直有效的修飾器。 */
        List<Modifier> empowered = List.of();
        /** 2026-10-30 照 PoE2 Tame Beast：收服的野獸帶著的怪物詞綴（最多 4 個）。 */
        List<Modifier> tamed = List.of();
        /** 2026-10-30 照 PoE2 Tame Beast：收服的野獸自己的招式（那種怪的招式＋詞綴給的招式，boss_skills.yml）。 */
        List<BossSkillUse> tamedSkills = List.of();
        /** 殘影（2026-10）：站著不動、不打人，只負責嘲諷；不算數量上限、不顯示在快捷欄、死了不觸發。 */
        boolean decoy;
        /**
         * 2026-10-30 照 PoE2（Offering Spike）：祭品的骨柱。也是 decoy（不動、不打、不算數量、不在快捷欄），
         * 但不嘲諷，只像一般召喚物一樣讓附近還沒有目標的怪去打它；被打死祭品的效果就結束。
         */
        boolean spike;
        /** 2026-10-30（群鋒）：現在的更多傷害（0.21 = 更多 21%；每秒照主人有幾種復活型召喚物重算）。 */
        double musterMore;
        // ---- 2026-10-26 照 PoE2 ----
        /** 能量護盾（我們的單位）與上限；最後一次受傷的 tick（4 秒後開始回充）。 */
        double es;
        double esMax;
        long lastHurt;
        /** PoE2 的傷害、生命換成我們的要乘多少（{@link MinionSheets#poe2Factors}）。 */
        double[] factors = {1, 1};
        /** 自己的技能、指令的冷卻：代號 → 好了的 tick。 */
        final Map<String, Long> ready = new HashMap<>();
        /** 狂卒：怒氣、上次獲得怒氣的 tick、激怒中（從哪個 tick 開始）。 */
        int rage;
        long rageGainedAt;
        long enragedAt = -1;
        /** 祭司的治療給的生命回復（每秒，我們的單位）到哪個 tick。 */
        double regenPerSecond;
        long regenUntil;
        /** 霜術士的冰甲到哪個 tick、被打時反擊的冰冷傷害 {最小, 最大}。 */
        long iceArmourUntil;
        double[] iceArmourCold = {0, 0};
        /** 2026-10-28 照 PoE2 Skeletal Cleric 的品質：被祭司復活之後到哪個 tick 不會受到傷害（0 = 沒有）。 */
        long immuneUntil;
        /** 2026-10-28 照 PoE2 Bone Offering 的品質：骸骨祭的護盾（這一輪）消失了沒、消失之後「受到的傷害更少」到哪個 tick。 */
        long shieldLostAt = -1;
        long shieldLessUntil;
        /** 狼的躍擊：落地的 tick（0 = 沒在跳）。 */
        long leapLandAt;
        /** 這一下用的屬性表與技能描述（召喚物自己的技能：碎骨、電弧…）；null = 普攻。 */
        Attack override;
        /** 附身的那種怪物（原版生物代號）；不是附身是 null。 */
        String spectreType;
        /**
         * 2026-10-29 照 PoE2：召喚物自己的生命偷取（一份最多牠最大生命的 10%、1 秒回完、每秒最多 20%，跟玩家的 {@link tw.exilecore.character.Leech} 一樣）；
         * 這次之前召喚物的偷取一下直接回。
         */
        final tw.exilecore.character.Leech leech = new tw.exilecore.character.Leech();

        boolean onCooldown(final String ability, final long now) {
            return ready.getOrDefault(ability, 0L) > now;
        }

        void startCooldown(final String ability, final long now, final double seconds) {
            // 2026-10-26 照 PoE2：召喚物的冷卻回復速度（主人的「召喚物的冷卻回復速度」）；這次之前不吃
            // 2026-10-27 照 PoE2 Commandment：掛了號令的召喚物，指令的冷卻回復速度另外增加 100%
            // 2026-10-29 照 PoE2 的 Minion Types（Cooldown）：從召喚物這邊掛的迅返、爆發這類（skill.cooldown、cooldown.recovery）也算；
            // 這次之前只看主人給的召喚物冷卻回復速度
            final StatSheet stats = effective != null ? effective : sheet;
            final double recovery = (stats == null ? 0 : stats.increased(tw.exilecore.stats.StatKeys.COOLDOWN_RECOVERY, Set.of()))
                    + (COMMAND.equals(ability) && commandment(this) ? tw.exilecore.stats.SupportExtraKeys.COMMANDMENT_RECOVERY : 0);
            final double scale = stats == null ? 1.0 : Math.max(0.1, stats.apply(tw.exilecore.stats.StatKeys.SKILL_COOLDOWN, 1.0, Set.of()));
            ready.put(ability, now + Math.round(seconds * scale * 20 / Math.max(0.1, 1 + recovery)));
        }

        Minion(final UUID owner, final SkillBuild build, final SummonSpec spec, final Mob mob, final String name, final long now,
               final long untilTick) {
            this.owner = owner;
            this.build = build;
            this.spec = spec;
            this.mob = mob;
            this.name = name;
            this.bornTick = now;
            this.untilTick = untilTick;
            this.nextTaunt = now + 20;
        }

        String skillId() {
            return build.definition().id();
        }

        /**
         * 2026-10-29 照 PoE2（MinionsAreUndamagable）：不會受傷的召喚物——砲手、巨靈（只聽指令）與 summon.immortal 的（幻化武器）。
         * 這次之前只有砲手、巨靈（幻化武器用生命 10 倍、抗性 75% 硬撐，還是會死）。
         */
        boolean undamageable() {
            return MinionAbilities.commandOnly(skillId()) || spec.immortal();
        }
    }

    /** 主人剛打過的怪。 */
    private record Focus(UUID target, long tick) {
    }

    /** 2026-10-27 照 PoE2 Commandment：這隻召喚物掛了號令（指令以外的技能不造成傷害、指令冷卻回復快 100%）。 */
    static boolean commandment(final Minion minion) {
        for (final SupportDefinition support : minion.build.supports()) {
            if (support.flags(tw.exilecore.stats.SupportExtraKeys.FLAG_COMMANDMENT)) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10-27：指令放出來的那幾招（震地咆哮、毒氣箭、爆裂殉身、死亡風暴、冰甲、從天而降）的技能描述代號結尾。 */
    static final Set<String> COMMAND_PROFILES = Set.of("shattering_roar", "gas_arrow", "gas_cloud", "gas_explosion", "explosive_demise",
            "death_storm", "ice_armour", "death_from_above");

    /** 這一招是不是指令放的（技能描述代號是「召喚技能.招式」）。 */
    static boolean commandProfile(final String profileId) {
        final int dot = profileId == null ? -1 : profileId.lastIndexOf('.');
        return dot >= 0 && COMMAND_PROFILES.contains(profileId.substring(dot + 1));
    }

    /** Commandment：指令以外的招式更少 100% 傷害。 */
    private static SkillProfile commanded(final Minion minion, final SkillProfile profile) {
        if (profile == null || commandProfile(profile.id()) || !commandment(minion)) {
            return profile;
        }
        return profile.withLocal(List.of(Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, -1.0, "號令")));
    }

    private final ExileCore plugin;
    private final SkillConfig config;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    /** 2026-10-29 照 PoE2：遠程召喚物射出的投射物（真的會飛）。 */
    private final MinionProjectiles projectiles;
    private final NamespacedKey key;
    private final NamespacedKey skillKey;
    private final NamespacedKey speedKey;
    private final NamespacedKey armourKey;
    private final NamespacedKey toughnessKey;
    private final List<Minion> minions = new ArrayList<>();
    private final Map<UUID, Minion> byEntity = new HashMap<>();
    private final Map<UUID, Focus> focus = new HashMap<>();
    /** 光環：主人開著的、隊友給的光環效果也給召喚物。 */
    private AuraService auras;
    private Taunts taunts;
    /** 召喚物吼一聲嘲諷多久（tick）：吼的間隔，最多 3 秒。 */
    private static final long MINION_TAUNT_MAX = 3 * 20;

    MinionService(final ExileCore plugin, final SkillConfig config, final SkillTargets targets, final SkillVisuals visuals) {
        this.plugin = plugin;
        this.config = config;
        this.targets = targets;
        this.visuals = visuals;
        this.projectiles = new MinionProjectiles(plugin, targets);
        this.key = new NamespacedKey(plugin, "minion");
        this.skillKey = new NamespacedKey(plugin, "minion_skill");
        this.speedKey = new NamespacedKey(plugin, "minion_speed");
        this.armourKey = new NamespacedKey(plugin, "minion_no_armour");
        this.toughnessKey = new NamespacedKey(plugin, "minion_no_toughness");
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    void setAuras(final AuraService auras) {
        this.auras = auras;
    }

    /** 2026-10-17：召喚物的吼叫也走共用的嘲諷（身上已經有嘲諷的不搶、嘲諷之間有冷卻）。 */
    void setTaunts(final Taunts taunts) {
        this.taunts = taunts;
    }

    // ---- 查詢 ----

    /** 這個玩家某個技能目前有幾隻（說明用）。 */
    /** 2026-10-27（塗油限定 狂颮之主）：owner 用 skillId 叫出來、最新的那一隻（剛復活的）；沒有回傳 null。 */
    UUID newest(final UUID owner, final String skillId) {
        Minion newest = null;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId) && !minion.decoy
                    && (newest == null || minion.bornTick >= newest.bornTick)) {
                newest = minion;
            }
        }
        return newest == null ? null : newest.mob.getUniqueId();
    }

    int count(final UUID owner, final String skillId) {
        int count = 0;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 2026-10-27：某個技能最多能有幾隻才不會超過一個人的總上限（{@link #MAX_PER_PLAYER} 減掉其他技能的召喚物）。
     * 精魂召喚照這個夾住上限；這次之前單一技能上限 41～50，或好幾種加起來超過 40，會每 0.5 秒叫一隻、擠掉最舊的一直輪替。
     */
    int room(final UUID owner, final String skillId) {
        int others = 0;
        for (final Minion minion : minions) {
            // 2026-10-27：殘影（分身）不算召喚物上限（這次之前算進去，放一個殘影精魂召喚的上限就少一隻、被收掉一隻）
            if (minion.owner.equals(owner) && !minion.decoy && !spirit(minion) && !minion.skillId().equals(skillId)) { // 2026-10-30：精魂召喚不算總上限
                others++;
            }
        }
        return clampToRoom(Integer.MAX_VALUE, others);
    }

    /** 2026-10-30 照 PoE2：精魂召喚（每隻保留精魂）只受精魂限制，不算進一個人的召喚物總上限、也不會被別的召喚擠掉。 */
    static boolean spirit(final Minion minion) {
        return minion.build.definition().aura() != null;
    }

    /** 2026-10-27：上限 wanted 夾在總上限剩下的空間裡（測試用）。 */
    static int clampToRoom(final int wanted, final int others) {
        return Math.max(0, Math.min(wanted, MAX_PER_PLAYER - others));
    }

    /** 2026-10-27：真的被打死（不是被新叫的擠掉、時間到、收起來）的隻數，拿了就歸零（天賦樹「召喚物復活時回復生命」用）。 */
    int takeDeaths(final UUID owner, final String skillId) {
        final Integer died = deaths.remove(owner + ":" + skillId);
        return died == null ? 0 : died;
    }

    /**
     * 2026-10-27：這個玩家還沒被拿走的死亡數全部丟掉（精魂召喚每次同步完呼叫）：不是精魂召喚的（一般召喚、每隔幾秒叫一隻的）
     * 沒人拿，這次之前會一直累積到登出，那個技能之後開成精魂召喚時一次算成好幾隻「等著復活」。
     */
    void clearDeaths(final UUID owner) {
        deaths.keySet().removeIf(key -> key.startsWith(owner + ":"));
    }

    /** 這個玩家全部有幾隻。 */
    public int count(final UUID owner) {
        int count = 0;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && !minion.decoy) {
                count++;
            }
        }
        return count;
    }

    int size() {
        return minions.size();
    }

    /** 魔像在的時候給主人的增益（屬性表的來源）。 */
    List<Modifier> modifiers(final Player player) {
        List<Modifier> list = null;
        final Set<String> counted = new HashSet<>();
        double undeadPower = 0; // 2026-10-26 照 PoE2 忠誠獻祭：不死召喚物的強度總值（ConquestEffects 換成更多傷害）
        for (final Minion minion : minions) {
            if (minion.owner.equals(player.getUniqueId()) && !minion.decoy && minion.mob.isValid()
                    && tw.exilecore.passive.ConquestEffects.undead(minion.mob.getType().name())) {
                undeadPower += tw.exilecore.passive.ConquestEffects.minionPower(minion.spec.life());
            }
            if (minion.owner.equals(player.getUniqueId()) && !minion.spec.buff().isEmpty() && counted.add(minion.skillId())) {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.addAll(minion.spec.buff());
            }
        }
        if (undeadPower > 0) {
            if (list == null) {
                list = new ArrayList<>();
            }
            list.add(Modifier.flat(tw.exilecore.passive.ConquestEffects.UNDEAD_POWER, undeadPower, "不死召喚物"));
        }
        return list == null ? List.of() : list;
    }

    // ---- 召喚 ----

    /** 花魔力之前：召不召得出來（要屍體的技能附近要有屍體）。 */
    String precheck(final Player player, final SkillBuild build) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null) {
            return null;
        }
        if (spec.corpse() && corpseNear(player, spec) == null) {
            if (powerRaise(build) && powerCharges(player) > 0) {
                return null; // 喚屍（2026-10 PoE2）：沒有屍體就吃一顆銳星
            }
            return powerRaise(build) ? "附近沒有屍體，身上也沒有銳星" : "附近沒有屍體";
        }
        return null;
    }

    private Corpses.Corpse corpseNear(final Player player, final SummonSpec spec) {
        final Location at = player.getLocation();
        return plugin.combat().corpses().nearest(at.getWorld().getUID(), at.getX(), at.getZ(), spec.search(), now());
    }

    /** 祭品（2026-10）：主人身邊 radius 格內自己的召喚物得到增益 ticks；回傳幾隻。 */
    /** 2026-10-18 召歸令：owner 在同一個世界的召喚物全部傳到他身邊（殘影不算）；回傳傳了幾隻。 */
    int recall(final Player owner) {
        int count = 0;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner.getUniqueId()) || minion.decoy || !minion.mob.isValid() || !minion.mob.getWorld().equals(owner.getWorld())) {
                continue;
            }
            minion.mob.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, minion.mob.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
            teleportToOwner(minion, owner);
            count++;
        }
        return count;
    }

    int offer(final Player player, final double radius, final int ticks, final List<Modifier> mods) {
        return offer(player, radius, ticks, mods, List.of());
    }

    /** 2026-10-26：同上，只給這些技能叫出來的召喚物（空的 = 全部；銀月庇照 PoE2 只給狼）。 */
    int offer(final Player player, final double radius, final int ticks, final List<Modifier> mods, final List<String> skills) {
        final long now = now();
        final Location at = player.getLocation();
        int count = 0;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(player.getUniqueId()) || !minion.mob.isValid() || !minion.mob.getWorld().equals(at.getWorld())
                    || minion.mob.getLocation().distanceSquared(at) > radius * radius
                    || (!skills.isEmpty() && !skills.contains(minion.skillId()))) {
                continue;
            }
            minion.offering = List.copyOf(mods);
            minion.offeringUntil = now + ticks;
            rebuild(minion, plugin.players().sheet(player), false);
            minion.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, minion.mob.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            count++;
        }
        return count;
    }

    /**
     * 指令（2026-10）：owner 的召喚物裡聽命的（技能代號在 who 裡，或 who 有 any；殘影不算、20 格內），照離主人由近到遠，最多 max 隻；
     * 回傳每隻的位置（腳）與牠現在的目標（沒有就 null）。
     */
    java.util.List<java.util.Map.Entry<Location, LivingEntity>> commandable(final Player owner, final java.util.List<String> who, final int max) {
        return commandable(owner, who, max, true);
    }

    /** 2026-10-26：obey = false 只是看有沒有聽命的（放之前的檢查），不算「聽從指令」。 */
    java.util.List<java.util.Map.Entry<Location, LivingEntity>> commandable(final Player owner, final java.util.List<String> who, final int max,
                                                                             final boolean obey) {
        final java.util.List<Minion> list = new ArrayList<>();
        final Location at = owner.getLocation();
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(at.getWorld())
                    && minion.mob.getLocation().distanceSquared(at) <= 20 * 20
                    && (who.contains("any") && !MinionAbilities.commandOnly(minion.skillId()) || who.contains(minion.skillId()))) { // 2026-10-27：砲手、巨靈只聽自己的指令
                list.add(minion);
            }
        }
        list.sort(java.util.Comparator.comparingDouble(m -> m.mob.getLocation().distanceSquared(at)));
        final java.util.List<java.util.Map.Entry<Location, LivingEntity>> out = new ArrayList<>();
        for (final Minion minion : list) {
            if (out.size() >= max) {
                break;
            }
            minion.mob.swingMainHand();
            if (obey) {
                obeyed(owner, minion);
            }
            final LivingEntity target = minion.target == null ? null : living(minion.target);
            // 2026-10：Map.entry 不收 null——召喚物沒有目標時整個指令技能丟 NullPointerException（魔力、冷卻扣了卻沒放出來）
            out.add(new java.util.AbstractMap.SimpleImmutableEntry<>(minion.mob.getLocation(), target));
        }
        return out;
    }

    /** 祭柱（2026-10）：at 附近 radius 格內 owner 的召喚物（殘影不算）得到增益 ticks；回傳幾隻。 */
    int offerAt(final Player owner, final Location at, final double radius, final int ticks, final List<Modifier> mods) {
        final long now = now();
        int count = 0;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner.getUniqueId()) || minion.decoy || !minion.mob.isValid() || !minion.mob.getWorld().equals(at.getWorld())
                    || minion.mob.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            final boolean fresh = minion.offeringUntil <= now;
            // 2026-10-30 照 PoE2 Bone Offering：這一輪護盾已經爆掉的召喚物，骨柱還立著時不再拿到護盾（減傷與吸收）
            minion.offering = !fresh && minion.shieldLostAt >= 0 ? withoutShield(mods) : List.copyOf(mods);
            minion.offeringUntil = now + ticks;
            if (fresh) {
                minion.shieldLostAt = -1; // 2026-10-28：新的一輪祭品，護盾重新有
                rebuild(minion, plugin.players().sheet(owner), false);
                minion.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, minion.mob.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.02);
            }
            count++;
        }
        return count;
    }

    /** 祭柱沒有屍體時（2026-10）：獻祭一隻 near 附近 search 格內自己最舊的召喚物（殘影不算），回傳牠倒下的位置；沒有回傳 null。 */
    Location sacrifice(final Player owner, final Location near, final double search) {
        Minion pick = null;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(near.getWorld())
                    && !minion.undamageable() // 2026-10-27：不會受傷的砲手、巨靈不能獻祭（2026-10-29：幻化武器也不能）
                    && minion.mob.getLocation().distanceSquared(near) <= search * search && (pick == null || minion.bornTick < pick.bornTick)) {
                pick = minion;
            }
        }
        if (pick == null) {
            return null;
        }
        final Location at = pick.mob.getLocation();
        remove(pick, Gone.EXPIRED);
        return at;
    }

    /** 2026-10-26（骸舞，PoE2 Danse Macabre）：多獻祭一隻附近的骷髏（技能代號有 skeleton 的召喚物，最舊的那隻）；有獻祭到回傳 true。 */
    boolean sacrificeSkeleton(final Player owner, final Location near, final double search) {
        Minion pick = null;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && (minion.skillId().contains("skelet") || minion.skillId().startsWith("summon_bone_"))
                    && minion.mob.getWorld().equals(near.getWorld()) && minion.mob.getLocation().distanceSquared(near) <= search * search
                    && (pick == null || minion.bornTick < pick.bornTick)) {
                pick = minion;
            }
        }
        if (pick == null) {
            return false;
        }
        pick.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, pick.mob.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.03);
        remove(pick, Gone.EXPIRED);
        return true;
    }

    /** 2026-10-26：全部的召喚物（燃僕每秒燒自己、點燃身邊；殘影不算）。 */
    List<Minion> all() {
        final List<Minion> list = new ArrayList<>();
        for (final Minion minion : minions) {
            if (!minion.decoy) {
                list.add(minion);
            }
        }
        return list;
    }

    /**
     * 2026-10-26（群鋒，PoE2 Muster）：掛了群鋒的召喚物，主人每有一種不同的復活型召喚物（永久的召喚物），更多 x% 傷害。
     */
    private SkillProfile musterProfile(final Minion minion) {
        updateMuster(minion);
        return minion.musterMore <= 0 ? minion.profile
                : minion.profile.withLocal(List.of(Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, minion.musterMore, "群鋒")));
    }

    /** 2026-10-30：群鋒也套用在召喚物的技能（法術、射擊、碎骨…）與狂卒的怒氣攻擊上（這次之前只有普攻有）。 */
    private static List<Modifier> musterModifiers(final Minion minion) {
        return minion.musterMore <= 0 ? List.of() : List.of(Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, minion.musterMore, "群鋒"));
    }

    /** 每秒重算一次群鋒：掛的群鋒 x% × 主人有幾種不同的復活型召喚物。 */
    private void updateMuster(final Minion minion) {
        double per = 0;
        for (final SupportDefinition support : minion.build.supports()) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(tw.exilecore.stats.SupportKeys.MUSTER)) {
                    per += modifier.value();
                }
            }
        }
        if (per <= 0) {
            minion.musterMore = 0;
            return;
        }
        final Set<String> kinds = new java.util.HashSet<>();
        for (final Minion other : minions) {
            if (other.owner.equals(minion.owner) && !other.decoy && other.spec.permanent() && other.mob.isValid()) {
                kinds.add(other.skillId());
            }
        }
        minion.musterMore = per * kinds.size() / 100.0;
    }

    /** 祭柱：附近有沒有自己的召喚物可以獻祭（花魔力之前看）。 */
    boolean canSacrifice(final Player owner, final Location near, final double search) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && minion.mob.getWorld().equals(near.getWorld())
                    && minion.mob.getLocation().distanceSquared(near) <= search * search) {
                return true;
            }
        }
        return false;
    }

    /** 夥伴（2026-10）：這個技能現在有沒有活著的。 */
    boolean hasCompanion(final UUID owner, final String skillId) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId) && minion.mob.isValid() && !minion.mob.isDead()) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10-26（天賦樹「夥伴在你的臨在範圍內時」）：有沒有活著的夥伴（技能帶 companion 標籤）在 radius 格內。 */
    public boolean companionNear(final org.bukkit.entity.Player owner, final double radius) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && !minion.mob.isDead()
                    && minion.build.definition().tags().contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION)
                    && near(owner, minion, radius)) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10-26（天賦樹「臨在範圍內每有一個召喚物」）：radius 格內活著的召喚物數（幻身不算）。 */
    public int countNear(final org.bukkit.entity.Player owner, final double radius) {
        int count = 0;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && !minion.mob.isDead()
                    && near(owner, minion, radius)) {
                count++;
            }
        }
        return count;
    }

    /** 2026-10-27（天賦樹「你的召喚物全部都是夥伴時」）：至少一隻、而且每一隻活著的召喚物都是夥伴（幻身不算）。 */
    public boolean allCompanions(final UUID owner) {
        boolean any = false;
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner) || minion.decoy || !minion.mob.isValid() || minion.mob.isDead()) {
                continue;
            }
            if (!isCompanion(minion)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** 2026-10-27（天賦樹「存在範圍內的夥伴有疾速」）：radius 格內活著的夥伴。 */
    List<UUID> companionsNear(final org.bukkit.entity.Player owner, final double radius) {
        final List<UUID> list = new ArrayList<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && isCompanion(minion) && minion.mob.isValid() && !minion.mob.isDead()
                    && near(owner, minion, radius)) {
                list.add(minion.mob.getUniqueId());
            }
        }
        return list;
    }

    private static boolean near(final org.bukkit.entity.Player owner, final Minion minion, final double radius) {
        return owner.getWorld().equals(minion.mob.getWorld()) && owner.getLocation().distanceSquared(minion.mob.getLocation()) <= radius * radius;
    }

    /** 精魂召喚（2026-10，照 PoE2 每隻算精魂）：這個技能超過 keep 隻的收掉（精魂變少了）。 */
    void trim(final UUID owner, final String skillId, final int keep) {
        int seen = 0;
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId)) {
                if (++seen > keep) {
                    remove(minion, Gone.EXPIRED);
                }
            }
        }
    }

    /** 2026-10-29：這種召喚物只留最新的 keep 隻（多的從最早叫出來的開始收掉；先祖之魂跟著圖騰）。 */
    void trimOldest(final UUID owner, final String skillId, final int keep) {
        final List<Minion> mine = new ArrayList<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(skillId)) {
                mine.add(minion);
            }
        }
        mine.sort(java.util.Comparator.comparingLong(minion -> minion.bornTick));
        for (int i = 0; i < mine.size() - Math.max(0, keep); i++) {
            remove(mine.get(i), Gone.EXPIRED);
        }
    }

    /** 夥伴（2026-10）：光環沒開著的夥伴收掉。 */
    void dismissCompanions(final UUID owner, final Set<String> keep) {
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.build.definition().mechanic() == Mechanic.AURA && !keep.contains(minion.skillId())) {
                remove(minion, Gone.EXPIRED);
            }
        }
    }

    /** 號召（2026-10）：自己的召喚物全部瞬間叫回腳邊、放掉原本的目標；回傳叫回幾隻。 */
    int rally(final Player player) {
        final List<Minion> mine = new ArrayList<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(player.getUniqueId()) && minion.mob.isValid() && !minion.mob.isDead()) {
                mine.add(minion);
            }
        }
        final Location center = player.getLocation();
        for (int i = 0; i < mine.size(); i++) {
            final Minion minion = mine.get(i);
            final double angle = Math.PI * 2 * i / Math.max(1, mine.size());
            Location to = center.clone().add(Math.cos(angle) * 1.6, 0, Math.sin(angle) * 1.6);
            if (!to.getBlock().isPassable() || !to.clone().add(0, 1, 0).getBlock().isPassable()) {
                to = center.clone();
            }
            to.setYaw(center.getYaw());
            final Location from = minion.mob.getLocation();
            tw.exilecore.skill.Fx.spawn(from.getWorld(), org.bukkit.Particle.REVERSE_PORTAL, from.add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            minion.mob.setTarget(null);
            minion.target = null;
            minion.mob.teleport(to);
            tw.exilecore.skill.Fx.spawn(to.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, to.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
        }
        return mine.size();
    }

    /** 喚屍這類（2026-10 PoE2）：沒有屍體時可以吃一顆銳星。 */
    private static boolean powerRaise(final SkillBuild build) {
        return SkillExecutor.hasFlag(build.definition(), tw.exilecore.stats.StatKeys.FLAG_POWER_RAISE);
    }

    private int powerCharges(final Player player) {
        return plugin.combat() == null || plugin.combat().charges() == null ? 0 : plugin.combat().charges().count(player, "power");
    }

    /** PoE2 強化殭屍「擊中傷害更多 x%」（寶石 1～40 級）。 */
    static final double[] EMPOWERED_DAMAGE = {100, 105, 110, 116, 122, 128, 134, 141, 148, 155, 163, 171, 180, 189, 198, 208, 218, 229, 241, 253,
            265, 279, 293, 307, 323, 339, 356, 373, 392, 412, 432, 454, 476, 500, 525, 552, 579, 608, 639, 670};

    /**
     * 強化殭屍的加成（2026-10-26 照 PoE2）：受到的傷害更少 30%（這裡用生命更多 43% 代替，耐打程度一樣）、
     * 擊中傷害更多 100%～253%（隨寶石等級）、移動速度更多 25%。
     */
    static List<Modifier> empowered(final int level) {
        return empowered(level, 0);
    }

    /**
     * 2026-10-28 照 PoE2 Raise Zombie 的品質：強化的效果增加 effectPercent%（受到的傷害更少、擊中傷害更多、移動速度更多都照比例放大）。
     */
    static List<Modifier> empowered(final int level, final double effectPercent) {
        final double scale = Math.max(0, 1 + effectPercent / 100.0);
        final double more = EMPOWERED_DAMAGE[Math.max(1, Math.min(EMPOWERED_DAMAGE.length, level)) - 1] / 100.0 * scale;
        final double less = Math.min(0.9, 0.3 * scale);
        return List.of(
                Modifier.more(tw.exilecore.stats.StatKeys.LIFE_MAX, 1 / (1 - less) - 1, "強化"),
                Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, more, "強化"),
                Modifier.more(tw.exilecore.stats.StatKeys.MOVEMENT_SPEED, 0.25 * scale, "強化"));
    }

    /** 召出來（超過上限就把同一個技能最舊的收掉）。 */
    void summon(final Player player, final SkillBuild build) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null) {
            return;
        }
        if (tw.exilecore.ascendancy.AscendancyEffects.umbral(player, build)) {
            return; // 2026-10-27 照 PoE2（昇華・幽影之井）：骷髏改成幽影之魂，不叫出來
        }
        if (spec.corpse() && powerRaise(build) && corpseNear(player, spec) == null && powerCharges(player) > 0) {
            // 2026-10-24：戰星輔助（恆星不扣、倍星叫兩批）
            final int benefit = plugin.combat().charges().spend(player, "power", build.profile().effective(plugin.players().sheet(player)),
                    build.profile().tags());
            if (benefit > 0) {
                for (int i = 0; i < benefit; i++) {
                    summon(player, build, 3, false, empowered(build.level(), build.qualityFlat(tw.exilecore.stats.GemQualityKeys.ZOMBIE_EMPOWER_EFFECT))); // 吃一顆銳星：一隻強化的加兩隻一般的，從面前爬出來
                }
                return;
            }
        }
        summon(player, build, spec.count(), spec.corpse(), List.of());
    }

    /**
     * 一直在叫的召喚（焚魂：每放一次火焰法術叫一隻）：還沒滿就叫一隻；滿了就把最快消失的那隻補滿時間與生命，不重生（2026-10）。
     * 以前滿了之後每一次都刪一隻、生一隻——引導型的火焰法術每秒 8～20 次生物的生成與移除。對戰鬥來說一樣：
     * 都是「隻數維持在上限、最舊的那隻重新算時間」，差別只在那隻留在原地、不是從你面前重新出來。
     */
    void summonOrRefresh(final Player player, final SkillBuild build) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null || spec.permanent() || spec.corpse()) {
            summon(player, build);
            return;
        }
        final UUID owner = player.getUniqueId();
        final String skillId = build.definition().id();
        final int limit = Math.max(1, Math.min(build.minions(), room(owner, skillId))); // 2026-10-27：夾在總上限剩下的空間（不然滿 40 之後每次都叫新的擠掉舊的）
        int alive = 0;
        Minion oldest = null;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && !minion.decoy && minion.skillId().equals(skillId) && minion.mob.isValid() && !minion.mob.isDead()) {
                alive++;
                if (oldest == null || minion.untilTick < oldest.untilTick) {
                    oldest = minion;
                }
            }
        }
        if (alive < limit || oldest == null || oldest.untilTick <= 0) {
            summon(player, build);
            return;
        }
        final long now = now();
        oldest.untilTick = now + build.durationTicks(spec.duration() + durationFlat(build));
        if (oldest.build != build) {
            oldest.build = build; // 技能升級、換輔助了：下一 tick 照新的重算
            oldest.nextRebuild = 0;
        }
        healFraction(oldest.mob, 1.0);
        syncName(oldest, now);
    }

    /**
     * 2026-10-27 照 PoE2 Unearth（掘骸）：在這幾個點各叫一隻（屍體已經由呼叫的人用掉；超過上限照樣收掉最舊的）。
     */
    void summonAt(final Player player, final SkillBuild build, final List<Location> points) {
        summonAt(player, build, points, null);
    }

    /** 同上；carried：2026-10-28 照 PoE2 Contagion，每個點的屍體帶著的瘟疫（跟 points 一一對應，null／裡面的 null = 沒有）。 */
    void summonAt(final Player player, final SkillBuild build, final List<Location> points, final List<Corpses.Contagion> carried) {
        if (build.definition().summon() == null || points.isEmpty()) {
            return;
        }
        summon(player, build, points.size(), false, List.of(), points, carried);
    }

    private void summon(final Player player, final SkillBuild build, final int count, final boolean useCorpse, final List<Modifier> first) {
        summon(player, build, count, useCorpse, first, null, null);
    }

    private void summon(final Player player, final SkillBuild build, final int count, final boolean useCorpse, final List<Modifier> first,
                        final List<Location> fixed, final List<Corpses.Contagion> fixedCarried) {
        final SummonSpec spec = build.definition().summon();
        final long now = now();
        final UUID owner = player.getUniqueId();
        final int limit = Math.max(1, build.minions());
        final Vector facing = SkillTargets.facing(player);
        final String ownerName = plugin.players().get(player).character() == null ? player.getName()
                : plugin.players().get(player).character().name();
        // 2026-10-30 照 PoE2 Tame Beast：收服了野獸的狼伴叫的是那隻野獸（名字、種類、強度照那種怪，帶著牠最多 4 個怪物詞綴）
        final boolean beast = MinionAbilities.BEAST.equals(build.definition().id());
        final String tamedKind = beast ? tamedKind(player) : null;
        final String name = "<#8fd3ff>" + tw.exilecore.item.ItemRenderer.escape(tamedKind != null
                ? "夥伴：" + spectreName(plugin.monsters().config(), tamedKind) : beast ? "夥伴：狼" : build.definition().name())
                + " <gray>（" + tw.exilecore.item.ItemRenderer.escape(ownerName) + "）";
        for (int i = 0; i < count; i++) {
            Location at;
            String spectreKind = tamedKind;
            Corpses.Contagion carried = null; // 2026-10-28 照 PoE2 Contagion：復活帶著瘟疫的屍體，召喚物擊中時傳出瘟疫
            if (fixed != null) {
                at = ground(fixed.get(i).clone()); // 2026-10-27 掘骸：屍體的位置
                carried = fixedCarried != null && i < fixedCarried.size() ? fixedCarried.get(i) : null;
            } else if (useCorpse) {
                final Corpses.Corpse corpse = corpseNear(player, spec);
                if (corpse == null) {
                    break; // 第二隻沒屍體就算了
                }
                carried = plugin.combat().corpses().contagion(corpse);
                if (!tw.exilecore.stats.TreeEffectKeys.corpseSaved(plugin.players().sheet(player), plugin.random())) { // 2026-10-26 照 PoE2 天賦樹：x% 機率不毀掉屍體
                    plugin.combat().corpses().remove(corpse);
                }
                at = ground(new Location(player.getWorld(), corpse.x(), corpse.y(), corpse.z()));
                if (spec.spectre()) {
                    // 附身（2026-10）：屍體原本是什麼怪就用什麼當身體（不能用的照技能寫的種類）
                    spectreKind = corpse.type() != null && SummonSpec.SPECTRES.contains(corpse.type()) ? corpse.type() : null;
                }
            } else {
                // 面前 1.8 格，兩隻以上左右排開
                final double side = count == 1 ? 0 : (i - (count - 1) / 2.0) * 1.4;
                final Vector right = new Vector(-facing.getZ(), 0, facing.getX());
                at = ground(player.getLocation().add(facing.clone().multiply(1.8)).add(right.multiply(side)));
            }
            if (at == null) {
                at = player.getLocation();
            }
            at = spread(owner, at); // 2026-10-30 照站長：召喚時不要疊在同一格（旁邊有自己的召喚物就往外找空格）
            if (spec.spectre() && !useCorpse) {
                spectreKind = boundSpectre(player); // 2026-10-26 附魂術（精魂召喚）：叫附身的那種怪
            }
            if (spec.flying()) {
                at.add(0, 1.2, 0);
            }
            at.setYaw(player.getLocation().getYaw());
            // 同一個技能同時最多幾隻：多的收最舊的；一個人全部也有上限
            final List<Minion> mine = new ArrayList<>();
            final List<Minion> all = new ArrayList<>();
            for (final Minion minion : minions) {
                if (minion.owner.equals(owner) && !minion.decoy) { // 2026-10-27：殘影不算總上限（跟 room 一樣）
                    if (!spirit(minion)) { // 2026-10-30：精魂召喚不算總上限
                        all.add(minion);
                    }
                    if (minion.skillId().equals(build.definition().id())) {
                        mine.add(minion);
                    }
                }
            }
            while (mine.size() >= limit) {
                final Minion oldest = mine.removeFirst();
                all.remove(oldest);
                remove(oldest, Gone.REPLACED);
            }
            while (build.definition().aura() == null && all.size() >= MAX_PER_PLAYER) {
                remove(all.removeFirst(), Gone.REPLACED);
            }
            // 2026-10-30 照 PoE2 Ancestral Spirits：每隻先祖之靈抽一種型態（玉法師、玉獸、戰角、靈龜），身體、倍率、招式照那一種
            final AncestralForms form = ANCESTRAL.equals(build.definition().id()) ? AncestralForms.pick(plugin.random()) : null;
            final String shown = form == null ? name : "<#8fd3ff>" + form.label() + " <gray>（" + tw.exilecore.item.ItemRenderer.escape(ownerName) + "）";
            final Mob mob = spawn(player, build, form != null ? form.spec(spec) : spec, at, shown, form != null ? form.entity() : spectreKind);
            if (mob == null) {
                continue;
            }
            if (carried != null && plugin.ailments() != null) {
                plugin.ailments().carryContagion(mob, carried);
            }
            // 2026-10-26 照 PoE2 附魂術：附身的召喚物用那種怪物原本的倍率（monsters.yml 的 types；生命、傷害）
            final SummonSpec own = form != null ? form.spec(spec) : spec.spectre() && spectreKind != null ? spectreSpec(spec, spectreKind)
                    : tamedKind != null ? tamedSpec(spec, tamedKind) : spec;
            final Minion minion = new Minion(owner, build, own, mob, shown, now, spec.permanent() ? 0 : now + build.durationTicks(spec.duration() + durationFlat(build)));
            minion.spectreType = spec.spectre() ? spectreKind : null;
            minion.form = form;
            if (tamedKind != null) {
                minion.tamed = tamedModifiers(player);
                minion.tamedSkills = tamedSkillUses(player, tamedKind);
            }
            if (i == 0 && !first.isEmpty()) {
                minion.empowered = first;
                mob.setGlowing(true); // 強化的那隻發光，好認
            }
            rebuild(minion, plugin.players().sheet(player), true);
            minions.add(minion);
            byEntity.put(mob.getUniqueId(), minion);
            syncName(minion, now); // 有時間限制的一出生就看得到剩幾秒
            visuals.summon(build.definition().visual(), at, spec);
        }
        if (!spec.buff().isEmpty()) {
            plugin.players().markDirty(player);
        }
        refreshHud(owner);
    }

    /** 2026-10-30 照 PoE2 Offering Spike：骨柱的生命是同等級召喚物的幾倍（Pain／Power 0.18、Bone 0.35，poe2db Offering Spike 的 Life%）。 */
    static final double SPIKE_LIFE = 0.18;
    static final double SPIKE_LIFE_BONE = 0.35;

    /**
     * 2026-10-30 照 PoE2（Pain／Bone／Soul Offering）：在 at 立一根骨柱，骨柱本身是召喚物（等級＝寶石等級 × 2，生命照召喚物表 × lifeMultiplier，
     * 吃召喚物生命的加成與「祭品的最大生命」），不動、不打人；ticks 後或被打死就倒。own 是一直給骨柱的修飾器（骸骨祭：骨柱也有護盾）。
     * 回傳骨柱的實體代號（叫不出來回傳 null）。
     */
    UUID spike(final Player player, final SkillBuild build, final Location at, final double lifeMultiplier, final int ticks, final List<Modifier> own) {
        if (at.getWorld() == null) {
            return null;
        }
        final java.util.Map<String, Object> summon = new java.util.LinkedHashMap<>();
        summon.put("kind", "SKELETON");
        summon.put("ally", true);
        summon.put("life", lifeMultiplier);
        summon.put("armour", 1.0); // poe2db：Armour 2413 = 同等級召喚物的護甲
        summon.put("duration", Math.max(0.5, Math.min(120, ticks / 20.0)));
        summon.put("speed", 0.5);
        final SummonSpec spec = SummonSpec.parse(tw.exilecore.core.config.YamlNode.of("祭品骨柱", java.util.Map.of("summon", summon)),
                build.definition().name());
        Location spot = ground(at.clone());
        if (spot == null) {
            spot = at.clone();
        }
        final String name = "<#e0d6b8>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name()) + "骨柱";
        final Mob mob = spawn(player, build, spec, spot, name, null);
        if (mob == null) {
            return null;
        }
        mob.setAI(false);
        mob.setInvisible(true); // 外觀是 Warcries 立的骨柱方塊
        final EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            equipment.clear();
        }
        final long now = now();
        final Minion minion = new Minion(player.getUniqueId(), build, spec, mob, name, now, now + ticks);
        minion.decoy = true;
        minion.spike = true;
        final StatSheet ownerSheet = plugin.players().sheet(player);
        final double offeringLife = ownerSheet == null ? 0
                : ownerSheet.increased(tw.exilecore.stats.TreeMechanicKeys.OFFERING_LIFE, build.profile().tags());
        final List<Modifier> always = new ArrayList<>(own);
        if (Math.abs(offeringLife) > 1e-9) {
            always.add(new Modifier(tw.exilecore.stats.StatKeys.LIFE_MAX, tw.exilecore.stats.ModType.INCREASED, offeringLife, Set.of(), "祭品的最大生命"));
        }
        minion.empowered = List.copyOf(always);
        rebuild(minion, ownerSheet, true);
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            speed.setBaseValue(0);
        }
        minions.add(minion);
        byEntity.put(mob.getUniqueId(), minion);
        syncName(minion, now);
        return mob.getUniqueId();
    }

    /** 2026-10-30：這根骨柱還立著嗎（沒被打死、沒到期）。 */
    boolean spikeStanding(final UUID mob) {
        final Minion minion = mob == null ? null : byEntity.get(mob);
        return minion != null && minion.spike && minion.mob.isValid() && !minion.mob.isDead();
    }

    /** 2026-10-30：祭品結束（時間到、被新的換掉）：把骨柱收掉。 */
    void removeSpike(final UUID mob) {
        final Minion minion = mob == null ? null : byEntity.get(mob);
        if (minion != null && minion.spike) {
            remove(minion, Gone.QUIET);
        }
    }

    /**
     * 2026-10-30 照 PoE2（Skewer a Skeleton on a bone spike）：祭品要串一隻你的骷髏（骸骨戰士、狂卒、射手、術士、祭司…）。
     * 找 near 附近 search 格內最近的一隻，獻祭掉，回傳牠的位置；沒有回傳 null（take = false 只看有沒有）。
     */
    Location offeringSkeleton(final Player owner, final Location near, final double search, final boolean take) {
        Minion pick = null;
        double best = Double.MAX_VALUE;
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid() && !minion.mob.isDead()
                    && SKELETONS.contains(minion.skillId()) && minion.mob.getWorld().equals(near.getWorld())) {
                final double d = minion.mob.getLocation().distanceSquared(near);
                if (d <= search * search && d < best) {
                    best = d;
                    pick = minion;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        final Location at = pick.mob.getLocation();
        if (take) {
            pick.mob.getWorld().spawnParticle(org.bukkit.Particle.SOUL, at.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.03);
            remove(pick, Gone.EXPIRED);
        }
        return at;
    }

    /**
     * 殘影（2026-10）：在 at 留一個主人模樣的分身（戴主人的頭、穿主人的盔甲外觀），站著不動、一直嘲諷附近的怪，
     * 時間到或被打爆就消失。同一個人同時只有一個（新的換掉舊的）；不算召喚物數量上限。
     */
    public void decoy(final Player player, final SkillBuild build, final Location at) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null || at.getWorld() == null) {
            return;
        }
        final UUID owner = player.getUniqueId();
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner) && minion.decoy && !minion.spike) { // 2026-10-30：祭品的骨柱不是殘影
                remove(minion, Gone.REPLACED);
            }
        }
        final long now = now();
        final String name = "<#b8c7ff>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name());
        Location spot = ground(at.clone());
        if (spot == null) {
            spot = at.clone();
        }
        spot.setYaw(player.getLocation().getYaw());
        final Mob mob = spawn(player, build, spec, spot, name, null);
        if (mob == null) {
            return;
        }
        final Minion minion = new Minion(owner, build, spec, mob, name, now, now + build.durationTicks(spec.duration() + durationFlat(build)));
        minion.decoy = true;
        rebuild(minion, plugin.players().sheet(player), true);
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            speed.setBaseValue(0);
        }
        final EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
                skull.setOwningPlayer(player);
                head.setItemMeta(skull);
            }
            equipment.setItem(EquipmentSlot.HEAD, head, true);
            final EntityEquipment worn = player.getEquipment();
            for (final EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND}) {
                final ItemStack item = worn == null ? null : worn.getItem(slot);
                if (item != null && !item.isEmpty()) {
                    final ItemStack look = new ItemStack(item.getType()); // 只要外觀，不帶物品資料
                    equipment.setItem(slot, look, true);
                }
            }
        }
        mob.setGlowing(true);
        minions.add(minion);
        byEntity.put(mob.getUniqueId(), minion);
        syncName(minion, now);
        taunt(minion, spec.tauntRadius(), true);
        visuals.taunt(build.definition().visual(), spot, spec.tauntRadius());
    }

    /** 一個技能的召喚物數量（快捷欄右邊；2026-10）。 */
    public record HudCount(String name, int count, int limit) {
    }

    /**
     * 每個有召喚物的技能：名字、現在幾隻、上限（上限看最近召出來那隻的設定，換了輔助寶石下次召喚就更新）。
     */
    public java.util.List<HudCount> hudCounts(final UUID owner) {
        final java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Minion> latest = new java.util.HashMap<>();
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner) || minion.decoy) {
                continue;
            }
            counts.computeIfAbsent(minion.skillId(), key -> new int[1])[0]++;
            latest.merge(minion.skillId(), minion, (x, y) -> y.bornTick >= x.bornTick ? y : x);
        }
        final java.util.List<HudCount> list = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<String, int[]> entry : counts.entrySet()) {
            final Minion minion = latest.get(entry.getKey());
            list.add(new HudCount(minion.build.definition().name(), entry.getValue()[0], Math.max(1, minion.build.minions())));
        }
        return list;
    }

    /** 召喚物多了、少了：馬上更新主人的動作列（快捷欄右邊的數量）。 */
    private void refreshHud(final UUID owner) {
        final Player player = plugin.getServer().getPlayer(owner);
        if (player != null && player.isOnline()) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(player);
            if (state != null && state.hasCharacter()) {
                plugin.players().display(player, state);
            }
        }
    }

    private Mob spawn(final Player player, final SkillBuild build, final SummonSpec spec, final Location at, final String name, final String kind) {
        // 2026-10-29 照 PoE2 Manifest Weapon：幻化武器拿的是你主手那把武器的複製（外觀照你的武器）；這次之前一律拿鐵劍
        final ItemStack copied = MinionAbilities.MANIFEST.equals(build.definition().id()) ? manifestItem(player) : null;
        final EntityType type;
        try {
            type = EntityType.valueOf(kind != null ? kind : spec.kind());
        } catch (final IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("召喚物種類不存在：{}", spec.kind());
            return null;
        }
        final Consumer<Entity> setup = created -> {
            if (!(created instanceof Mob mob)) {
                return;
            }
            if (mob instanceof Ageable ageable) {
                ageable.setAdult();
            }
            mob.setPersistent(false);
            mob.setRemoveWhenFarAway(false);
            mob.setCanPickupItems(false);
            mob.setSilent(true); // 召喚物靜音（2026-10）：原版的叫聲、受傷、死亡、腳步、攻擊的聲音都不出
            mob.setCollidable(false);
            mob.customName(plugin.text().parse(name));
            mob.setCustomNameVisible(true);
            // 2026-10-30 馴獸：收服的苦力怕不會爆、史萊姆固定大小（死掉不分裂：onSlimeSplit）
            if (mob instanceof org.bukkit.entity.Creeper creeper) {
                creeper.setExplosionRadius(0);
                creeper.setMaxFuseTicks(Integer.MAX_VALUE);
            }
            if (mob instanceof org.bukkit.entity.Slime slime) {
                slime.setSize(2);
            }
            if (mob instanceof org.bukkit.entity.Horse horse) { // 2026-10-30 喚駒：陪你打的戰馬跟騎的那匹長一樣（不能被騎：Mounts.onInteract）
                horse.setColor(org.bukkit.entity.Horse.Color.BLACK);
                horse.setStyle(org.bukkit.entity.Horse.Style.WHITE);
                horse.getInventory().setArmor(new ItemStack(Material.IRON_HORSE_ARMOR));
            }
            mob.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            mob.getPersistentDataContainer().set(skillKey, PersistentDataType.STRING, build.definition().id());
            if (MinionAbilities.commandOnly(build.definition().id()) || spec.immortal()) {
                mob.setInvulnerable(true); // 2026-10-27 照 PoE2：砲手、巨靈不會受傷（2026-10-29：幻化武器也是）
            }
            if (mob instanceof Zombie zombie) {
                zombie.setShouldBurnInDay(false);
                zombie.setCanBreakDoors(false);
                final AttributeInstance reinforcements = zombie.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
                if (reinforcements != null) {
                    reinforcements.setBaseValue(0);
                }
            }
            if (mob instanceof AbstractSkeleton skeleton) {
                skeleton.setShouldBurnInDay(false);
            }
            if (mob instanceof IronGolem golem) {
                golem.setPlayerCreated(true);
                final AttributeInstance scale = golem.getAttribute(Attribute.SCALE);
                if (scale != null) {
                    scale.setBaseValue(0.85);
                }
            }
            if (mob instanceof Vex vex) {
                vex.setLimitedLifetime(false);
                vex.setCharging(false);
            }
            final AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) {
                follow.setBaseValue(48);
            }
            cancelAttribute(mob.getAttribute(Attribute.ARMOR), armourKey);
            cancelAttribute(mob.getAttribute(Attribute.ARMOR_TOUGHNESS), toughnessKey);
            final EntityEquipment equipment = mob.getEquipment();
            if (equipment != null) {
                equipment.clear();
                final Material helmet = spec.helmet() == null ? null : Material.matchMaterial(spec.helmet());
                final Material weapon = spec.weapon() == null ? null : Material.matchMaterial(spec.weapon());
                if (helmet != null) {
                    equipment.setItem(EquipmentSlot.HEAD, new ItemStack(helmet), true);
                }
                if (copied != null) {
                    equipment.setItem(EquipmentSlot.HAND, copied, true);
                } else if (weapon != null) {
                    equipment.setItem(EquipmentSlot.HAND, new ItemStack(weapon), true);
                }
                for (final EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        equipment.setDropChance(slot, 0f);
                    } catch (final IllegalArgumentException ignored) {
                        // 不是裝備格（例如 BODY、SADDLE 在這種生物身上）
                    }
                }
            }
        };
        try {
            final Entity spawned = spawnTyped(at.getWorld(), at, type.getEntityClass(), setup);
            if (!(spawned instanceof Mob mob) || !spawned.isValid()) {
                if (spawned != null) {
                    spawned.remove();
                }
                return null; // 被其他插件取消了
            }
            tameGoals(mob);
            MinionCollision.add(plugin, mob.getUniqueId()); // 2026-10：不推擠玩家（客戶端看隊伍的碰撞規則）
            return mob;
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("召不出{}（{}）", spec.kindName(), e.toString());
            return null;
        }
    }

    private static <T extends Entity> T spawnTyped(final World world, final Location at, final Class<T> type, final Consumer<Entity> setup) {
        return world.spawn(at, type, false, setup);
    }

    /** 拿掉原版的移動與找目標 AI（只留浮水、看東看西這些），由我們來指揮。跨世界之後原版會重新裝上，要再拿一次。 */
    private void tameGoals(final Mob mob) {
        final MobGoals goals = plugin.getServer().getMobGoals();
        goals.removeAllGoals(mob, GoalType.MOVE);
        goals.removeAllGoals(mob, GoalType.TARGET);
        mob.setTarget(null);
    }

    /** 把一個原版屬性壓到 0（頭盔的護甲不能再減一次傷；減免由引擎算）。 */
    private static void cancelAttribute(final AttributeInstance attribute, final NamespacedKey key) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(0);
        attribute.removeModifier(key);
        attribute.addModifier(new AttributeModifier(key, -1000, AttributeModifier.Operation.ADD_NUMBER));
    }

    /** 這個點往下找地面（最多 4 格）；找不到回傳 null。 */
    /** 2026-10-30：離 at 最近、沒有自己的召喚物站著的地面（同一格、或水平 0.9 格內算有人）；找不到照原本的位置。 */
    private Location spread(final UUID owner, final Location at) {
        if (at.getWorld() == null || !occupied(owner, at)) {
            return at;
        }
        for (int ring = 1; ring <= 3; ring++) {
            final int steps = 8 * ring;
            final double offset = plugin.random().nextDouble() * Math.PI * 2; // 每次從不同方向開始找，不會都往同一邊排
            for (int i = 0; i < steps; i++) {
                final double angle = offset + i * Math.PI * 2 / steps;
                final Location probe = at.clone().add(Math.cos(angle) * ring, 0, Math.sin(angle) * ring);
                final Location spot = ground(probe);
                if (spot != null && Math.abs(spot.getY() - at.getY()) <= 1.5 && !occupied(owner, spot)) {
                    spot.setYaw(at.getYaw());
                    return spot;
                }
            }
        }
        return at;
    }

    private boolean occupied(final UUID owner, final Location at) {
        for (final Minion minion : minions) {
            if (!minion.owner.equals(owner) || !minion.mob.isValid() || !minion.mob.getWorld().equals(at.getWorld())) {
                continue;
            }
            final Location there = minion.mob.getLocation();
            final double dx = there.getX() - at.getX();
            final double dz = there.getZ() - at.getZ();
            if (dx * dx + dz * dz < 0.81 && Math.abs(there.getY() - at.getY()) < 2) {
                return true;
            }
        }
        return false;
    }

    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && y < world.getMaxHeight()
                    && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5, point.getYaw(), 0);
            }
        }
        return null;
    }

    // ---- 數值 ----

    /** 照主人目前的屬性表重算這隻的屬性表，再把原版的血量上限、移動速度同步過去。 */
    private void rebuild(final Minion minion, final StatSheet ownerSheet, final boolean fresh) {
        final MonsterConfig monsters = plugin.monsters().config();
        final org.bukkit.entity.Player ownerPlayer = plugin.getServer().getPlayer(minion.owner);
        // 2026-10-26：照 PoE2 的召喚物（summon.ally）用召喚物表換算；超過 20 級的技能等級照 extraScale
        final MinionSheets.Base base = MinionSheets.base(config, minion.spec, minion.build.level(),
                ownerPlayer == null ? 0 : plugin.players().get(ownerPlayer).level(), monsters::lifeAt, monsters::damageAt,
                monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance());
        // 2026-10-26 照 PoE2：「夥伴…」只給夥伴、「存在範圍內的友軍…」只給在主人存在範圍內的（每 2 秒看一次距離）
        final boolean inPresence = ownerPlayer != null && ownerPlayer.getWorld().equals(minion.mob.getWorld())
                && ownerPlayer.getLocation().distanceSquared(minion.mob.getLocation())
                <= Math.pow(tw.exilecore.combat.Presence.radius(ownerSheet), 2);
        final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.forMinion(MinionSheets.ownerModifiers(ownerSheet),
                MinionSheets.conditions(minion.build.definition(), inPresence)));
        if (auras != null) {
            ownerModifiers.addAll(auras.effectsFor(minion.owner)); // 光環給召喚物的是完整效果（PoE 的規矩）
        }
        ownerModifiers.addAll(minion.empowered);
        ownerModifiers.addAll(minion.tamed); // 2026-10-30 照 PoE2 Tame Beast：收服的野獸保留的怪物詞綴
        if (tw.exilecore.ascendancy.AscendancyEffects.get() != null) {
            ownerModifiers.addAll(tw.exilecore.ascendancy.AscendancyEffects.get().minionWeapon(minion.owner, minion.mob.getLocation())); // 2026-10-27 照 PoE2：看看我怎麼辦事的（友軍含召喚物）
        }
        if (plugin.skills() != null) {
            ownerModifiers.addAll(plugin.skills().supportMechanics().lineage().minionModifiers(minion, ownerSheet)); // 2026-10-27 照 PoE2：巨伴、縛魔、殘戰
        }
        final boolean offered = minion.offeringUntil > now();
        if (offered) {
            ownerModifiers.addAll(minion.offering); // 祭品（2026-10）
        }
        final boolean armoured = minion.iceArmourUntil > now();
        if (armoured) {
            ownerModifiers.addAll(ICE_ARMOUR); // 2026-10-26 霜術士的冰甲：受到的擊中傷害更少 40%
        }
        // 2026-10-28 照 PoE2 Bone Offering 的品質：護盾消失後 1 秒受到的傷害更少 x%
        final boolean shieldLost = minion.shieldLessUntil > now();
        if (shieldLost) {
            final double less = shieldLostLess(minion.offering);
            for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
                ownerModifiers.add(Modifier.flat(tw.exilecore.stats.StatKeys.DEBUFF_TAKEN + type.tag(), -less, "骸骨祭"));
            }
        }
        final boolean treeBuffed = minion.treeBuffUntil > now();
        if (treeBuffed) {
            ownerModifiers.addAll(minion.treeBuff); // 2026-10-26 天賦樹：夥伴擊殺時的疾速
        }
        long allyExpiry = Long.MAX_VALUE;
        for (final java.util.Iterator<Map.Entry<String, Long>> it = minion.allyBuffUntil.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now()) {
                it.remove();
                minion.allyBuffs.remove(entry.getKey());
                continue;
            }
            ownerModifiers.addAll(minion.allyBuffs.getOrDefault(entry.getKey(), List.of())); // 2026-10-30 先祖之靈給友軍的增益
            allyExpiry = Math.min(allyExpiry, entry.getValue() + 1);
        }
        // 2026-10-27 照 PoE2 Crazed Minions：最近復活過的召喚物更多傷害（到期那一刻重算）
        final java.util.Map.Entry<List<Modifier>, Long> crazed = plugin.skills() == null ? null
                : plugin.skills().supportMechanics().extras().crazed(minion);
        if (crazed != null) {
            ownerModifiers.addAll(crazed.getKey());
        }
        // 2026-10-26 照 PoE2 天賦「夥伴獲得你的力量」：力量給的生命（每點 2，換成召喚物的單位）；2026-10-27 巨伴的閃避、偏斜也在這裡
        // （跟召喚物說明、召喚物每秒傷害共用 MinionEstimates；生命換算照這次的係數，這次之前用上一次重算的，第一次是 1）
        final double[] factors = MinionSheets.refFactors(config, minion.build.level(),
                ownerPlayer == null ? 0 : plugin.players().get(ownerPlayer).level(), monsters::lifeAt, monsters::damageAt);
        ownerModifiers.addAll(MinionEstimates.ownerDerived(minion.build, ownerSheet, factors[1], !minion.decoy));
        final long expiry = Math.min(Math.min(Math.min(Math.min(offered ? minion.offeringUntil + 1 : Long.MAX_VALUE, armoured ? minion.iceArmourUntil + 1 : Long.MAX_VALUE),
                treeBuffed ? minion.treeBuffUntil + 1 : Long.MAX_VALUE), crazed != null ? crazed.getValue() + 1 : Long.MAX_VALUE),
                Math.min(shieldLost ? minion.shieldLessUntil + 1 : Long.MAX_VALUE, allyExpiry));
        // 2026-10：每 2 秒看一次，主人的屬性、光環、技能都沒變就不用重算（以前每隻每 2 秒整個重算、重送血量與移動速度給附近的人）
        if (!fresh && minion.sheet != null && minion.builtFor == minion.build && base.equals(minion.builtBase)
                && ownerModifiers.equals(minion.builtModifiers)) {
            minion.nextRebuild = Math.min(now() + 40, expiry);
            return;
        }
        minion.factors = factors;
        minion.builtFor = minion.build;
        minion.builtBase = base;
        minion.builtModifiers = ownerModifiers;
        minion.sheet = MinionSheets.build(minion.spec, base, ownerModifiers);
        tw.exilecore.item.unique.UniqueDerived.mirrorResists(ownerSheet, minion.sheet); // 2026-10-26（冥河之衛）：召喚物的抗性等於你的
        minion.profile = MinionSheets.profile(minion.build);
        minion.effective = minion.profile.local().isEmpty() ? minion.sheet // 2026-10：輔助寶石給召喚物的也拿掉 minion 標籤（防禦類才讀得到）
                : minion.sheet.overlay(MinionSheets.stripMinion(minion.profile.local()));
        // 2026-10-29 照 PoE2 Projectile Acceleration III（疾矢 III 從召喚物這邊掛）：投射物速度的增減也套用在召喚物的投射物傷害上
        final double speedDamage = MinionTypes.speedAsDamage(minion.effective, minion.profile.tags());
        if (Math.abs(speedDamage) > 1e-9) {
            minion.profile = minion.profile.withLocal(List.of(new Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED,
                    speedDamage, Set.of(tw.exilecore.stats.Tags.PROJECTILE), "投射物速度")));
            minion.effective = minion.sheet.overlay(MinionSheets.stripMinion(minion.profile.local()));
        }
        minion.attackTicks = MinionSheets.attackTicks(MinionSheets.attacksPerSecond(minion.effective, minion.profile));
        minion.regenPercent = MinionSheets.regenPercent(minion.effective);
        final double life = MinionSheets.life(minion.effective);
        minion.healthScale = MonsterSheets.healthScale(life);
        syncVanilla(minion, life, fresh);
        // 2026-10-26 照 PoE2：能量護盾（剛出來是滿的；上限變了照比例）
        final double esMax = MinionSheets.energyShield(minion.effective);
        minion.es = fresh || minion.esMax <= 0 ? esMax : Math.min(esMax, minion.es / minion.esMax * esMax);
        minion.esMax = esMax;
        minion.nextRebuild = Math.min(now() + 40, expiry);
    }

    /** 冰甲：受到的擊中傷害更少 40%（每一種傷害類型都少 40%）。 */
    private static final List<Modifier> ICE_ARMOUR = iceArmour();

    private static List<Modifier> iceArmour() {
        final List<Modifier> list = new ArrayList<>();
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            list.add(Modifier.flat(tw.exilecore.stats.StatKeys.DEBUFF_TAKEN + type.tag(), -MinionAbilities.ARMOUR_LESS, "冰甲"));
        }
        return List.copyOf(list);
    }

    private void syncVanilla(final Minion minion, final double life, final boolean fresh) {
        final Mob mob = minion.mob;
        final AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            final double fraction = fresh || health.getValue() <= 0 ? 1.0 : Math.max(0, Math.min(1, mob.getHealth() / health.getValue()));
            for (final AttributeModifier modifier : List.copyOf(health.getModifiers())) {
                health.removeModifier(modifier);
            }
            final double vanilla = Math.min(MonsterSheets.VANILLA_HEALTH_CAP, life);
            health.setBaseValue(vanilla);
            mob.setHealth(Math.max(0.5, Math.min(vanilla, vanilla * fraction)));
        }
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
            // 2026-10-29 照站長：召喚物移動速度基礎 ×2（skills.yml 的 minion_move_scale）
            final double scale = minion.spec.speed() * SkillConfig.minionMoveScale() * MinionSheets.movementScale(minion.effective) - 1;
            if (Math.abs(scale) > 1e-6) {
                speed.addModifier(new AttributeModifier(speedKey, scale, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    // ---- 每 tick ----

    void tick() {
        final long now = now();
        tickTaming(now); // 2026-10-30 照 PoE2 Tame Beast：鬼火纏住的野獸
        projectiles.tick(); // 2026-10-29 照 PoE2：遠程召喚物的投射物往前飛
        tickClouds(now); // 2026-10-26：射手的毒雲
        if (now % 200 == 0) {
            fallen.removeIf(down -> down.until() <= now);
        }
        if (minions.isEmpty()) {
            if (now % 200 == 0) {
                focus.clear();
                aggroCache.clear();
            }
            return;
        }
        if (now % 200 == 0) {
            aggroCache.values().removeIf(aggro -> now - aggro.tick() > 200); // 下線、沒召喚物的人不留著
        }
        if (now % 20 == 0) {
            syncVisibility();
            for (final Minion minion : minions) {
                if (!minion.decoy) {
                    updateMuster(minion); // 2026-10-30：群鋒（技能、怒氣攻擊讀這個）
                }
            }
        }
        for (final Minion minion : new ArrayList<>(minions)) {
            if (!byEntity.containsKey(minion.mob.getUniqueId())) {
                continue; // 這一輪已經被收掉了
            }
            try {
                tickOne(minion, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("召喚物更新出錯（{}）", minion.skillId(), e);
                remove(minion, Gone.QUIET);
            }
        }
    }

    /**
     * 2026-10-16：「隱藏別人的技能特效」也隱藏別人的召喚物（每秒對一次：剛開關設定、剛走進來的人也跟上）。
     * 自己的一定看得到；競技場裡不隱藏（看不到敵人的召喚物不公平）。
     */
    private void syncVisibility() {
        for (final Minion minion : minions) {
            final Mob mob = minion.mob;
            if (!mob.isValid()) {
                continue;
            }
            final boolean arena = plugin.pvp() != null && plugin.pvp().inArena(mob.getLocation());
            for (final Player viewer : mob.getWorld().getPlayers()) {
                final boolean hide = !arena && !viewer.getUniqueId().equals(minion.owner) && Fx.hides(viewer);
                if (hide && viewer.canSee(mob)) {
                    viewer.hideEntity(plugin, mob);
                } else if (!hide && !viewer.canSee(mob)) {
                    viewer.showEntity(plugin, mob);
                }
            }
        }
    }

    private void tickOne(final Minion minion, final long now) {
        final java.util.UUID previousFx = Fx.source(minion.owner); // 2026-10：隱藏別人的技能特效
        try {
            tickOneInner(minion, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickOneInner(final Minion minion, final long now) {
        final Mob mob = minion.mob;
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (!mob.isValid() || mob.isDead()) {
            remove(minion, Gone.QUIET);
            return;
        }
        if (owner == null || !owner.isOnline()) {
            remove(minion, Gone.EXPIRED);
            return;
        }
        if (minion.untilTick > 0 && now >= minion.untilTick) {
            remove(minion, Gone.EXPIRED);
            return;
        }
        if (minion.form == AncestralForms.TURTLE && now % 20 == minion.bornTick % 20) {
            turtleAura(minion, owner); // 2026-10-30 照 PoE2 Ancestral Spirit Turtle：光環
        }
        if (now % 20 == minion.bornTick % 20 && MinionAbilities.MANIFEST.equals(minion.skillId())) {
            syncManifestWeapon(minion, owner); // 2026-10-29 照 PoE2：換了武器，幻化武器跟著換外觀
        }
        if (minion.decoy) {
            // 殘影：不動、不打、不跟著主人；換世界就收掉，每 taunt 秒嘲諷一次
            if (!mob.getWorld().equals(owner.getWorld())) {
                remove(minion, Gone.EXPIRED);
                return;
            }
            if (now % 20 == minion.bornTick % 20) {
                syncName(minion, now);
            }
            if (minion.spec.taunt() > 0 && now >= minion.nextTaunt) {
                minion.nextTaunt = now + Math.round(minion.spec.taunt() * 20);
                taunt(minion, minion.spec.tauntRadius(), true);
            }
            if (minion.spike && now >= minion.nextTaunt) {
                minion.nextTaunt = now + 20;
                taunt(minion, PASSIVE_TAUNT, false); // 2026-10-30 照 PoE2：骨柱是召喚物，附近還沒有目標的怪會去打它（不強制嘲諷）
            }
            return;
        }
        if (owner.isDead()) {
            // 2026-10-27：主人死了（還沒重生）召喚物停手（不搶擊殺、不觸發主人的回復）；這次之前照打
            if (mob.getTarget() != null) {
                mob.setTarget(null);
            }
            return;
        }
        if (now >= minion.nextRebuild) {
            rebuild(minion, plugin.players().sheet(owner), false);
        }
        if (now % 200 == 17) {
            tameGoals(mob); // 保險：跨世界、區塊重載之後原版 AI 會回來
        }
        // 離太遠、不同世界：瞬移回主人身邊
        if (!mob.getWorld().equals(owner.getWorld()) || mob.getLocation().distanceSquared(owner.getLocation()) > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            teleportToOwner(minion, owner);
            return;
        }
        if (minion.regenPercent > 0 && now % 20 == 0) {
            healFraction(mob, minion.regenPercent / 100.0);
        }
        if (minion.spec.immortal() && now % 20 == minion.bornTick % 20) {
            healFraction(mob, 1.0); // 2026-10-29 照 PoE2（幻化武器不會受傷）：保險，萬一哪裡直接扣了血，每秒補滿（砲手、巨靈在下面同樣補）
        }
        if (minion.spec.heal() > 0 && now % 20 == minion.bornTick % 20) {
            healAllies(minion, owner); // 治療的召喚物（2026-10）
        }
        refTick(minion, now); // 2026-10-26：能量護盾回充、祭司的回復、怒氣、激怒失血、躍擊落地
        if (!mob.isValid() || mob.isDead() || !byEntity.containsKey(mob.getUniqueId())) {
            return;
        }
        if (MinionAbilities.CLERIC.equals(minion.skillId())) {
            // 2026-10-26 照 PoE2：祭司不打人，只治療受傷的召喚物、復活倒下的骷髏，跟著你走
            cleric(minion, owner, now);
            if (now % 20 == minion.bornTick % 20) {
                syncName(minion, now);
            }
            idle(minion, owner, now);
            watchStuck(minion, owner, mob.getLocation().distanceSquared(owner.getLocation()) > FOLLOW_START * FOLLOW_START, now);
            return;
        }
        if (MinionAbilities.commandOnly(minion.skillId())) {
            // 2026-10-27 照 PoE2：砲手、巨靈只在你下指令時行動，平常跟著你（砲手站在你身後），不會受傷
            if (now % 20 == minion.bornTick % 20) {
                syncName(minion, now);
                healFraction(mob, 1.0);
            }
            standBy(minion, owner, now);
            return;
        }
        if (now % 20 == minion.bornTick % 20) {
            syncName(minion, now);
        }
        if (minion.spec.flying() && now % 2 == 0) {
            visuals.minionTrail(minion.build.definition().visual(), mob.getLocation().add(0, 0.4, 0));
        }

        // 目標
        LivingEntity target = minion.target == null ? null : living(minion.target);
        if (target != null && (!validTarget(target, owner, mob) || !withinSwarm(minion, owner, target))) {
            target = null;
            minion.target = null;
        }
        if (target == null && now >= minion.nextRetarget) {
            minion.nextRetarget = now + 5;
            if (!ownerInTown(owner)) {
                target = findTarget(minion, owner);
                minion.target = target == null ? null : target.getUniqueId();
            }
        }
        if (target == null) {
            idle(minion, owner, now);
            watchStuck(minion, owner, mob.getLocation().distanceSquared(owner.getLocation()) > FOLLOW_START * FOLLOW_START, now);
            return;
        }
        minion.idleStopped = false;
        mob.lookAt(target);
        if (mob.getTarget() != target) {
            mob.setTarget(target);
        }
        if (!(mob instanceof Vex)) {
            mob.setAggressive(true);
        }
        // 距離量到目標的碰撞箱邊緣，所以只加自己的半個身寬
        final double reach = minion.spec.isRanged() ? minion.spec.ranged() : minion.spec.reach() + mob.getWidth() / 2;
        final double distance = Math.sqrt(SkillTargets.boxDistanceSquared(mob.getLocation(), target.getBoundingBox()));
        final boolean inReach = distance <= reach && (minion.spec.isRanged() || Math.abs(target.getLocation().getY() - mob.getLocation().getY()) <= 3);
        final boolean seesTarget = !inReach || mob.hasLineOfSight(target); // 碰得到才看視線（同一 tick 原版有快取）
        if (minion.spec.flying()) {
            fly(mob, SkillTargets.center(target), inReach ? 0.4 : 1.0);
            ((Vex) mob).setCharging(distance <= reach + 3);
        } else if (now >= minion.nextPath) {
            minion.nextPath = now + 10;
            // 碰得到但看不到（隔著柱子）：繼續走過去，不然會站在柱子後面發呆（2026-10）
            if (!inReach || !seesTarget) {
                mob.getPathfinder().moveTo(target, 1.2);
            } else {
                mob.getPathfinder().stopPathfinding();
            }
        }
        if (!minion.spec.flying()) {
            watchStuck(minion, owner, !inReach || !seesTarget, now);
        }
        if (minion.leapLandAt > 0) {
            return; // 躍擊在空中：落地才打
        }
        if (MinionAbilities.MANIFEST.equals(minion.skillId()) && now >= minion.nextAttack && !minion.onCooldown(MANIFEST_LUNGE, now)
                && distance >= MinionAbilities.MANIFEST_LUNGE_MIN && distance <= MinionAbilities.MANIFEST_LUNGE_MAX
                && manifestForm(owner) == MinionAbilities.ManifestForm.SPEAR && mob.hasLineOfSight(target)) {
            minion.nextAttack = now + lunge(minion, target, owner, now); // 2026-10-29 照 PoE2：幻化成矛的突進（冷卻 3 秒）
        } else if (wolf(minion) && now >= minion.nextAttack && !minion.onCooldown(LEAP, now) && distance >= MinionAbilities.LEAP_MIN
                && distance <= MinionAbilities.LEAP_MAX && mob.isOnGround() && mob.hasLineOfSight(target)) {
            minion.nextAttack = now + leap(minion, target, distance, now); // 2026-10-26 照 PoE2：狼的躍擊（冷卻 7 秒）
        } else if (minion.form == AncestralForms.JADE_HULK && now >= minion.nextAttack && !minion.onCooldown(LEAP, now)
                && distance >= AncestralForms.DASH_MIN && distance <= AncestralForms.DASH_MAX && mob.isOnGround() && mob.hasLineOfSight(target)) {
            // 2026-10-30 照 PoE2 Ancestral Jade Hulk 的幻步（DTTAncestralJadeHulkLeapSlam：冷卻 5 秒、最少 2.5 公尺、最遠 7 公尺），落地衝擊 150%、更多 50%
            minion.nextAttack = now + leap(minion, target, distance, now);
            minion.startCooldown(LEAP, now, AncestralForms.DASH_COOLDOWN);
        } else if (inReach && now >= minion.nextAttack && seesTarget) {
            minion.nextAttack = now + act(minion, target, owner, now); // 2026-10-26：照 PoE2 各自的技能（碎骨、火彈、電弧、冰箭…）
        }
        // 魔像：每隔幾秒吼一聲，把附近的怪引過來打它
        if (minion.spec.taunt() > 0 && now >= minion.nextTaunt) {
            minion.nextTaunt = now + Math.round(minion.spec.taunt() * 20);
            taunt(minion, minion.spec.tauntRadius(), true);
            visuals.taunt(minion.build.definition().visual(), mob.getLocation(), minion.spec.tauntRadius());
        } else if (now % 20 == minion.bornTick % 20 && !ignoredByEnemies(minion)) {
            taunt(minion, PASSIVE_TAUNT, false); // 附近還沒有目標的怪先打召喚物（焚魂、蟲群敵人不理，照 PoE2）
        }
    }

    /**
     * 普攻：近戰的直接打目標；遠程的（骸骨射手的箭、焰術士的火彈）射出真的會飛的投射物（{@link #shoot}），打中時才算傷害。
     * 2026-10-29 照 PoE2：投射物有速度（吃投射物速度）、多射的一律散開射出、各自穿透／分裂／連鎖（{@link MinionProjectiles}）；
     * 打中時連周圍一起打的（焰術士的火彈）每一發打中的地方各炸一次，沒打中敵人落在地上也炸。
     * 這次之前遠程的是直接命中的彈道，回傳打中的那幾隻，多射的只打 60° 內其他的敵人。
     *
     * @param bonus 焰術士品質的「多丟幾顆火彈」（照 PoE2 Fires +(0—2) Projectiles，算多射）
     */
    private void attack(final Minion minion, final LivingEntity target, final int bonus) {
        final Mob mob = minion.mob;
        mob.swingMainHand();
        // 2026-10-26 照 PoE2：狂卒每點怒氣攻擊傷害更多 1%
        // 2026-10-28 照 PoE2 Skeletal Reaver 的品質：召喚物的怒氣效果增加 (0—20)%
        final Attack raged = minion.rage > 0 && minion.profile != null ? with(minion, musterProfile(minion).withLocal(List.of(Modifier.more(
                tw.exilecore.stats.StatKeys.DAMAGE, MinionAbilities.RAGE_DAMAGE * minion.rage * rageEffect(minion), "怒氣")))) : null;
        // 2026-10-29 照 PoE2（Knockback 可以輔助骸骨戰士）：從召喚物這邊掛的擊退（skill.knockback）讓普攻把敵人往外推；Boss 不會被推
        final double knockback = minion.effective == null || minion.profile == null ? 0
                : Math.max(0, minion.effective.apply(tw.exilecore.stats.StatKeys.SKILL_KNOCKBACK,
                minion.effective.flat(tw.exilecore.stats.StatKeys.SKILL_KNOCKBACK, minion.profile.tags()), minion.profile.tags()));
        if (!minion.spec.isRanged()) {
            visuals.minionAttack(minion.build.definition().visual(), mob, target);
            impact(minion, target, target.getLocation(), raged, knockback, 1.0);
            return;
        }
        shoot(minion, target, minion.spec.bolt(), bonus, new MinionProjectiles.Handler() {
            @Override
            public void hit(final MinionProjectiles.Shot shot, final LivingEntity struck, final Location at) {
                if (mob.isValid()) {
                    visuals.minionAttack(minion.build.definition().visual(), mob, struck); // 2026-10-29：打中的特效在投射物飛到時才放
                }
                impact(minion, struck, at, raged, knockback, shot.damage());
            }

            @Override
            public void stop(final MinionProjectiles.Shot shot, final Location at, final boolean afterHit) {
                if (!afterHit && minion.spec.radius() > 0) {
                    impact(minion, null, at, raged, knockback, shot.damage()); // 火彈沒打中敵人：落地照樣炸
                }
            }
        });
    }

    /**
     * 2026-10-29：普攻打到的那一下（近戰的目標、投射物打中的、火彈落地的地方）：直接打中的 struck（可以是 null）＋範圍內的
     * （summon.radius：焰術士的火彈），每一隻照普攻的傷害（怒氣、分裂的更少 20% 另外算）、擊退；焰術士的火彈再引爆附近的毒雲。
     */
    private void impact(final Minion minion, final LivingEntity struck, final Location at, final Attack raged, final double knockback,
                        final double scale) {
        final Mob mob = minion.mob;
        if (!mob.isValid() || mob.isDead()) {
            return;
        }
        final List<LivingEntity> hits = new ArrayList<>();
        if (struck != null) {
            hits.add(struck);
        }
        if (minion.spec.radius() > 0) {
            // 2026-10-17：「召喚物範圍」的加成以前讀不到——半徑照範圍效果開根號放大（跟技能的範圍一樣算法）
            final double area = minion.effective == null || minion.profile == null ? 1.0
                    : Math.sqrt(Math.max(0.05, minion.effective.apply(tw.exilecore.stats.StatKeys.SKILL_AREA, 1.0, minion.profile.tags())));
            final Location center = struck != null ? struck.getLocation() : at;
            for (final LivingEntity other : targets.around(center, minion.spec.radius() * area, plugin.getServer().getPlayer(minion.owner))) {
                if (!hits.contains(other)) {
                    hits.add(other);
                }
            }
        }
        // 分裂出來的投射物傷害更少 20%（跟玩家的一樣）：換成帶倍率的這一下
        Attack with = raged;
        if (Math.abs(scale - 1) > 1e-9 && minion.profile != null) {
            with = new Attack(plugin.getServer().getPlayer(minion.owner), minion.sheet,
                    (raged != null ? raged.profile() : commanded(minion, musterProfile(minion))).scaled(scale, "分裂"));
        }
        for (final LivingEntity hit : hits) {
            if (hit.isValid() && !hit.isDead()) {
                if (with != null) {
                    strike(minion, hit, with);
                } else {
                    hit.damage(1.0, mob); // 數字由 CombatListener 換掉（MinionHooks），這裡的 1 只是觸發
                }
                if (knockback > 0 && hit.isValid() && !hit.isDead() && plugin.monsters() != null
                        && !plugin.monsters().data(hit).map(data -> data.boss()).orElse(false)) {
                    final Vector push = hit.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                    if (push.lengthSquared() < 1e-6) {
                        push.setX(1);
                    }
                    hit.setVelocity(push.normalize().multiply(knockback).setY(0.3)); // 跟技能的擊退一樣（SkillExecutor.knockback）
                }
            }
        }
        if (MinionAbilities.ARSONIST.equals(minion.skillId())) {
            // 火彈：1.6 格、70% 轉火焰（summon 的 radius、convert）；每一發落點都會引爆毒雲（2026-10-29：投射物真的飛到才引爆）
            detonate(minion.owner, struck != null ? struck.getLocation() : at, MinionAbilities.FIRE_BOMB_RADIUS * areaScale(minion), minion);
            Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at.clone().add(0, 0.3, 0), 10, 0.5, 0.2, 0.5, 0.02);
        }
    }

    /**
     * 2026-10-29 照 PoE2：遠程召喚物朝 target 射一次（{@link MinionProjectiles}）：速度＝基礎速度（{@link MinionAbilities#projectileSpeed}）×
     * 投射物速度，射程＝射擊距離 × {@value MinionAbilities#PROJECTILE_RANGE_SCALE}；多射、穿透、分裂、連鎖照召喚物疊好輔助的屬性表。
     */
    private void shoot(final Minion minion, final LivingEntity target, final String kind, final int bonus, final MinionProjectiles.Handler handler) {
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner == null) {
            return;
        }
        final MinionTypes.Projectiles shot = minion.profile == null ? MinionTypes.Projectiles.NONE
                : MinionTypes.projectiles(minion.effective, minion.profile.tags());
        final double base = MinionAbilities.projectileSpeed(minion.skillId());
        final double speed = shot.speedOf(base > 0 ? base : MinionAbilities.SHARD_SPEED);
        projectiles.launch(minion.owner, minion.mob, SkillTargets.center(target), kind, shot, bonus, speed,
                Math.max(6, minion.spec.ranged() * MinionAbilities.PROJECTILE_RANGE_SCALE), handler);
    }

    /** 遠程召喚物的彈道：一串粒子從手邊到目標身上。 */
    private static void bolt(final Mob mob, final LivingEntity target, final String kind) {
        final Location from = mob.getEyeLocation();
        final Location to = SkillTargets.center(target);
        final org.bukkit.util.Vector step = to.toVector().subtract(from.toVector());
        final double length = step.length();
        if (length < 0.1) {
            return;
        }
        step.multiply(0.5 / length);
        final org.bukkit.Particle particle = switch (kind) {
            case "fire" -> org.bukkit.Particle.FLAME;
            case "cold" -> org.bukkit.Particle.SNOWFLAKE;
            case "lightning" -> org.bukkit.Particle.ELECTRIC_SPARK;
            case "chaos" -> org.bukkit.Particle.WITCH;
            default -> org.bukkit.Particle.CRIT;
        };
        final Location at = from.clone();
        for (double d = 0; d < length; d += 0.5) {
            tw.exilecore.skill.Fx.spawn(at.getWorld(), particle, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
        if ("arrow".equals(kind)) {
            mob.getWorld().playSound(mob.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.4f, 1.2f);
        }
    }

    /** 治療（2026-10）：主人與附近 8 格內自己的召喚物回復最大生命的 heal%。 */
    private void healAllies(final Minion healer, final Player owner) {
        final double fraction = healer.spec.heal() / 100.0;
        final Location at = healer.mob.getLocation();
        if (owner.getWorld().equals(at.getWorld()) && owner.getLocation().distanceSquared(at) <= 64) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
            if (state.life() < state.maxLife()) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * fraction));
                plugin.players().display(owner, state);
                tw.exilecore.skill.Fx.spawn(owner.getWorld(), org.bukkit.Particle.HEART, owner.getLocation().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
            }
        }
        for (final Minion other : minions) {
            if (other.owner.equals(healer.owner) && other.mob.isValid() && other.mob.getWorld().equals(at.getWorld())
                    && other.mob.getLocation().distanceSquared(at) <= 64) {
                healFraction(other.mob, fraction);
            }
        }
        tw.exilecore.skill.Fx.spawn(at.getWorld(), org.bukkit.Particle.HAPPY_VILLAGER, at.clone().add(0, 1.2, 0), 6, 0.6, 0.4, 0.6, 0);
    }

    private void idle(final Minion minion, final Player owner, final long now) {
        final Mob mob = minion.mob;
        if (mob.getTarget() != null) {
            mob.setTarget(null);
        }
        if (!(mob instanceof Vex)) {
            mob.setAggressive(false);
        }
        final Location home = owner.getLocation();
        final double distance = mob.getLocation().distance(home);
        if (minion.spec.flying()) {
            // 每隻在主人頭上各佔一個位置（照出生的 tick 排角度），不會全擠在同一點
            final double angle = Math.toRadians((minion.bornTick * 47) % 360);
            final Location hover = home.clone().add(Math.cos(angle) * 1.3, 2.0, Math.sin(angle) * 1.3);
            fly(mob, hover, hover.distance(mob.getLocation()) > 1.0 ? 0.8 : 0.15);
            ((Vex) mob).setCharging(false);
            return;
        }
        if (distance > FOLLOW_START) {
            if (now >= minion.nextPath) {
                minion.nextPath = now + 10;
                mob.getPathfinder().moveTo(home, distance > 12 ? 1.5 : 1.15); // 落後太多就用跑的
                minion.idleStopped = false;
            }
        } else if (distance <= FOLLOW_STOP && !minion.idleStopped) {
            mob.getPathfinder().stopPathfinding();
            minion.idleStopped = true;
        }
        if (now % 40 == minion.bornTick % 40) {
            mob.lookAt(owner);
        }
    }

    /** 會飛的：直接往目標飄（速度照倍率；靠近了就慢下來）。 */
    private void fly(final Mob mob, final Location to, final double factor) {
        final Vector delta = to.toVector().subtract(mob.getLocation().toVector());
        final double length = delta.length();
        if (length < 0.2) {
            mob.setVelocity(new Vector(0, 0, 0));
            return;
        }
        final double speed = Math.min(length, FLY_SPEED * factor * speedScale(mob));
        mob.setVelocity(delta.multiply(speed / length));
    }

    private double speedScale(final Mob mob) {
        final AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) {
            return 1.0;
        }
        return Math.max(0.3, speed.getValue() / speed.getBaseValue());
    }

    /**
     * 召喚物卡住（2026-10）：該走路（離主人太遠、碰不到目標）卻 2 秒沒動——原版 AI 跑回來、跨世界後還沒接上、卡在柱子後面——
     * 先把原版 AI 再拿掉一次、重新找路；連續 3 秒還是沒動就瞬移回主人身邊。每秒檢查一次。
     */
    private void watchStuck(final Minion minion, final Player owner, final boolean wantsToMove, final long now) {
        if (now % 20 != minion.bornTick % 20 || minion.spec.flying()) {
            return;
        }
        final Location at = minion.mob.getLocation();
        final double moved = Double.isNaN(minion.lastX) ? Double.MAX_VALUE : Math.hypot(at.getX() - minion.lastX, at.getZ() - minion.lastZ);
        minion.lastX = at.getX();
        minion.lastZ = at.getZ();
        if (!wantsToMove || moved >= 0.3) {
            minion.stillChecks = 0;
            return;
        }
        minion.stillChecks++;
        if (minion.stillChecks == 2) {
            tameGoals(minion.mob);
            if (!minion.mob.hasAI()) {
                minion.mob.setAI(true);
            }
            minion.idleStopped = false;
            minion.nextPath = 0;
        } else if (minion.stillChecks >= 3) {
            minion.stillChecks = 0;
            teleportToOwner(minion, owner);
        }
    }

    private void teleportToOwner(final Minion minion, final Player owner) {
        final Vector facing = SkillTargets.facing(owner);
        Location spot = ground(owner.getLocation().subtract(facing.multiply(1.5)));
        if (spot == null) {
            spot = owner.getLocation();
        }
        if (minion.spec.flying()) {
            spot.add(0, 1.2, 0);
        }
        final boolean crossWorld = !minion.mob.getWorld().equals(owner.getWorld());
        minion.mob.teleport(spot);
        minion.target = null;
        minion.nextPath = 0;
        if (crossWorld) {
            tameGoals(minion.mob); // 跨世界是新的實體：原版 AI 要再拿掉
            // 2026-10：剛進地圖時區塊還在載入，原版 AI 可能晚一點才接上（召喚物站在原地發呆）：半秒後再拿掉一次、重新找路
            final Mob mob = minion.mob;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (mob.isValid()) {
                    tameGoals(mob);
                    minion.idleStopped = false;
                    minion.nextPath = 0;
                    minion.stillChecks = 0;
                }
            }, 10L);
        }
        minion.idleStopped = false;
        minion.lastX = Double.NaN;
        visuals.minionBlink(spot);
    }

    private boolean ownerInTown(final Player owner) {
        return plugin.skills() != null && plugin.skills().inTown(owner);
    }

    private boolean validTarget(final LivingEntity target, final Player owner, final Mob mob) {
        return targets.valid(target, owner) && target.getWorld().equals(mob.getWorld())
                && target.getLocation().distanceSquared(owner.getLocation()) <= LEASH * LEASH
                && target.getLocation().distanceSquared(mob.getLocation()) <= (AGGRO + 6) * (AGGRO + 6);
    }

    /** 主人剛打過的怪優先，不然是自己附近最近、離主人不會太遠的怪。 */
    private LivingEntity findTarget(final Minion minion, final Player owner) {
        final Focus focused = focus.get(owner.getUniqueId());
        if (focused != null && now() - focused.tick() <= FOCUS_TICKS) {
            final LivingEntity target = living(focused.target());
            if (target != null && validTarget(target, owner, minion.mob) && withinSwarm(minion, owner, target)) {
                return target;
            }
        }
        // 2026-10：找主人身邊 AGGRO 格內、離這隻召喚物最近的怪（以前是找召喚物自己身邊 16 格，常常跑去打畫面外的）
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        final org.bukkit.Location at = minion.mob.getLocation();
        for (final LivingEntity candidate : aggroAround(owner)) {
            if (!candidate.isValid() || candidate.isDead() || !candidate.getWorld().equals(at.getWorld())) {
                continue; // 清單是幾 tick 前掃的：這段時間死掉、傳走的不算
            }
            final double distance = candidate.getLocation().distanceSquared(at);
            if (distance < bestDistance && withinSwarm(minion, owner, candidate)) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 主人身邊的怪多久重掃一次（tick）。 */
    private static final long AGGRO_TTL = 5;

    /** 掃過的「主人身邊的怪」。 */
    private record Aggro(long tick, UUID world, List<LivingEntity> list) {
    }

    private final Map<UUID, Aggro> aggroCache = new HashMap<>();

    /**
     * 主人身邊 AGGRO 格內的怪（2026-10）：同一個主人的召喚物共用一份、{@value #AGGRO_TTL} tick 重掃一次。
     * 以前每隻沒目標的召喚物各掃一次同一個範圍（40 隻就掃 40 次）；每隻本來就是 5 tick 才找一次目標，反應時間一樣。
     */
    private List<LivingEntity> aggroAround(final Player owner) {
        final long now = now();
        final Aggro cached = aggroCache.get(owner.getUniqueId());
        if (cached != null && now - cached.tick() < AGGRO_TTL && cached.world().equals(owner.getWorld().getUID())) {
            return cached.list();
        }
        final List<LivingEntity> list = targets.around(owner.getLocation(), AGGRO, owner);
        aggroCache.put(owner.getUniqueId(), new Aggro(now, owner.getWorld().getUID(), list));
        return list;
    }

    /**
     * 把附近的怪引過來打這隻（all = false 只引還沒有目標的）。
     * 2026-10-17：all（吼叫）走共用的嘲諷：身上已經有嘲諷（玩家的戰吼、別隻召喚物）或還在嘲諷冷卻中的怪不會被搶過來。
     */
    private void taunt(final Minion minion, final double radius, final boolean all) {
        final long ticks = Math.min(MINION_TAUNT_MAX, Math.max(20, Math.round(minion.spec.taunt() * 20)));
        for (final LivingEntity near : targets.around(minion.mob.getLocation(), radius)) {
            if (!(near instanceof Mob monster)) {
                continue;
            }
            if (all) {
                if (taunts != null) {
                    taunts.taunt(monster, minion.mob, ticks);
                } else {
                    monster.setTarget(minion.mob);
                }
            } else if (monster.getTarget() == null && (taunts == null || taunts.canTaunt(monster))) {
                monster.setTarget(minion.mob);
            }
        }
    }

    private LivingEntity living(final UUID uuid) {
        final Entity entity = plugin.getServer().getEntity(uuid);
        return entity instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    /** 頭上的名字：有時間限制的後面寫剩幾秒（2026-10，跟圖騰一樣），血不滿時再加上百分比。每秒更新一次。 */
    private void syncName(final Minion minion, final long now) {
        final AttributeInstance health = minion.mob.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || health.getValue() <= 0) {
            return;
        }
        final int percent = (int) Math.round(100 * Math.max(0, Math.min(1, minion.mob.getHealth() / health.getValue())));
        final StringBuilder text = new StringBuilder(minion.name);
        if (minion.untilTick > 0) {
            final long seconds = Math.max(0, (minion.untilTick - now + 19) / 20);
            text.append(" <white>").append(seconds).append("</white><gray> 秒");
        }
        if (minion.esMax > 0 && minion.es < minion.esMax * 0.995) {
            text.append(" <aqua>盾 ").append((int) Math.round(100 * Math.max(0, minion.es) / minion.esMax)).append('%'); // 2026-10-26 能量護盾
        }
        if (percent < 100) {
            text.append(" <red>").append(percent).append('%');
        }
        minion.mob.customName(plugin.text().parse(text.toString()));
    }

    private static void healFraction(final Mob mob, final double fraction) {
        final AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || mob.getHealth() >= health.getValue()) {
            return;
        }
        mob.setHealth(Math.min(health.getValue(), mob.getHealth() + health.getValue() * fraction));
    }

    // ---- 2026-10-26 照 PoE2 0.5：召喚物自己的技能與指令（數值見 MinionAbilities）----

    private static final String LEAP = "leap";
    private static final String MANIFEST_SLAM = "manifest_slam";
    private static final String MANIFEST_SWEEP = "manifest_sweep";
    private static final String MANIFEST_LUNGE = "manifest_lunge";
    private static final String MANIFEST_RAKE = "manifest_rake";
    private static final String BONEBREAKER = "bonebreaker";
    private static final String HEAL = "heal";
    private static final String REVIVE = "revive";
    private static final String COMMAND = "command";
    /** 2026-10-30 照 PoE2 Ancestral Spirits：先祖之靈各型態的招式冷卻。 */
    static final String ANCESTRAL = "forebear_manifest";
    private static final String ENCASE = "ancestral_encase";
    private static final String HULK_CHARGE = "ancestral_charge";
    private static final String HULK_CRY = "molten_soul_shriek";
    private static final String HORN = "ancestral_horn";
    /**
     * 2026-10-27：指令的冷卻另外記在這裡（主人＋技能 → 好了的 tick）：重新召喚（拔掉再裝技能、重新登入）不會重設冷卻。
     * 這次之前只記在每一隻召喚物身上，叫一批新的就能馬上再下指令。
     */
    private final Map<String, Long> commandReady = new HashMap<>();
    /** 會自己復活的骷髏（死亡風暴打倒下的骷髏、祭司復活的對象）。 */
    static final Set<String> SKELETONS = Set.of(MinionAbilities.WARRIOR, MinionAbilities.BRUTE, MinionAbilities.SNIPER,
            MinionAbilities.ARSONIST, MinionAbilities.STORM, MinionAbilities.FROST, MinionAbilities.CLERIC, MinionAbilities.REAVER);
    /** 附魂術附身的那種怪（角色進度）。 */
    static final String SPECTRE_KEY = "spectre.bound";
    /** 死亡風暴的感電地面（隱藏技能，skills.yml）。 */
    static final String SHOCKED_GROUND = "proc_minion_shocked_ground";
    /** 指令找多遠內的召喚物。 */
    static final double COMMAND_RANGE = 20;

    /** 倒下的骷髏（等 12 秒復活）。 */
    private record Fallen(UUID owner, Location at, long until) {
    }

    private final List<Fallen> fallen = new ArrayList<>();

    /** 射手的毒雲。 */
    private static final class Cloud {
        final UUID owner;
        final Minion sniper;
        final Location at;
        final long born;
        final long until;
        final double baseRadius;
        final double poisonBase;
        final StatSheet sheet;
        final SkillProfile explosion;

        Cloud(final UUID owner, final Minion sniper, final Location at, final long born, final double baseRadius, final double poisonBase,
              final StatSheet sheet, final SkillProfile explosion) {
            this.owner = owner;
            this.sniper = sniper;
            this.at = at;
            this.born = born;
            // 2026-10-29 照 PoE2（Skeletal Sniper 的 Minion Types 有 Duration）：毒雲的持續時間吃召喚物的持續時間加成（長留這類）；這次之前固定 8 秒
            this.until = born + Math.round(MinionAbilities.GAS_SECONDS * durationScale(sniper) * 20);
            this.baseRadius = baseRadius;
            this.poisonBase = poisonBase;
            this.sheet = sheet;
            this.explosion = explosion;
        }

        double radius(final long now) {
            final double seconds = Math.max(0, now - born) / 20.0;
            return baseRadius * (1 + Math.min(MinionAbilities.GAS_GROWTH_MAX, MinionAbilities.GAS_GROWTH * seconds));
        }
    }

    private final List<Cloud> clouds = new ArrayList<>();

    private static boolean wolf(final Minion minion) {
        return MinionAbilities.WOLVES.equals(minion.skillId()) || MinionAbilities.BEAST.equals(minion.skillId());
    }

    /** 焚魂、蟲群：敵人不會主動去打（PoE2）。 */
    private static boolean ignoredByEnemies(final Minion minion) {
        return MinionAbilities.SPIRITS.equals(minion.skillId()) || MinionAbilities.SWARM.equals(minion.skillId())
                || minion.undamageable(); // 2026-10-27：砲手、巨靈不會受傷（敵人也不去打）；2026-10-29：幻化武器也是（原作沒寫敵人會不會打牠，打了也沒用，乾脆不去打）
    }

    /** 蟲群只打主人身邊 4 格內的敵人；其他召喚物都算。 */
    private static boolean withinSwarm(final Minion minion, final Player owner, final LivingEntity target) {
        if (!MinionAbilities.SWARM.equals(minion.skillId())) {
            return true;
        }
        // 2026-10-28 照 PoE2 Ravenous Swarm 的品質：攻擊半徑 +(0—1) 公尺（加在 4 公尺上，再乘範圍）
        final double radius = (MinionAbilities.SWARM_RADIUS + tw.exilecore.core.Units.metres(minion.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.SWARM_RADIUS))) * areaScale(minion) + target.getWidth() / 2; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
        return target.getWorld().equals(owner.getWorld()) && target.getLocation().distanceSquared(owner.getLocation()) <= radius * radius;
    }

    /** 範圍倍率換成半徑倍率（√）。 */
    /** 2026-10-28 照 PoE2 技能品質（掘骸的骨構體）：召喚物的持續時間 +x 秒（加在基礎上，再乘持續時間倍率）。 */
    static double durationFlat(final SkillBuild build) {
        return Math.max(0, SkillZones.localFlat(build.profile(), tw.exilecore.stats.GemQualityKeys.DURATION_FLAT));
    }

    /** 2026-10-28 照 PoE2 Skeletal Reaver 的品質：召喚物的怒氣效果倍率（1 + 品質的 x%）。 */
    private static double rageEffect(final Minion minion) {
        return Math.max(0, 1 + (minion.build == null ? 0 : minion.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.MINION_RAGE_EFFECT)) / 100.0);
    }

    /** 2026-10-29 照 PoE2 的 Minion Types（Duration）：召喚物自己的技能效果持續時間倍率（從召喚物這邊掛的長留這類）。 */
    static double durationScale(final Minion minion) {
        return minion == null || minion.effective == null || minion.profile == null ? 1.0
                : Math.max(0.1, minion.effective.apply(tw.exilecore.stats.StatKeys.SKILL_DURATION, 1.0, minion.profile.tags()));
    }

    private static double areaScale(final Minion minion) {
        return minion.effective == null || minion.profile == null ? 1.0
                : Math.sqrt(Math.max(0.05, minion.effective.apply(tw.exilecore.stats.StatKeys.SKILL_AREA, 1.0, minion.profile.tags())));
    }

    /** 法術（電弧、冰箭、治療）施放幾 tick（吃召喚物的施法速度）。 */
    private static long castTicks(final Minion minion, final double seconds) {
        final double speed = minion.effective == null ? 1.0
                : Math.max(0.2, minion.effective.apply(tw.exilecore.stats.StatKeys.CAST_SPEED, 1.0, Set.of(tw.exilecore.stats.Tags.SPELL)));
        return Math.max(4, Math.round(20 * seconds / speed));
    }

    /** 每 tick：能量護盾回充、祭司的回復、怒氣消退、激怒失血、躍擊落地、冰甲的樣子。 */
    private void refTick(final Minion minion, final long now) {
        final boolean second = now % 20 == minion.bornTick % 20;
        if (minion.esMax > 0 && minion.es < minion.esMax && now - minion.lastHurt >= MinionAbilities.ES_DELAY_TICKS && now % 5 == 0) {
            final double rate = MinionAbilities.ES_RECHARGE_PER_SECOND
                    * Math.max(0, 1 + minion.effective.increased(tw.exilecore.stats.StatKeys.ES_RECHARGE, MinionSheets.CONTEXT));
            minion.es = Math.min(minion.esMax, minion.es + minion.esMax * rate * 0.25);
        }
        if (minion.regenUntil > now && second) {
            heal(minion.mob, minion.regenPerSecond);
        }
        if (minion.leech.pending() > 0 && minion.effective != null && minion.healthScale > 0) {
            // 2026-10-29 照 PoE2：召喚物的偷取每 tick 回一點（每份 1 秒回完、每秒最多最大生命 20%）；滿血剩下的結束
            final double recovered = MinionTypes.recoverLeech(minion.leech, 0.05, MinionSheets.life(minion.effective),
                    minion.mob.getHealth() / minion.healthScale, minion.effective);
            if (recovered > 0) {
                heal(minion.mob, recovered);
            }
        }
        if (minion.rage > 0 && second && now - minion.rageGainedAt >= MinionAbilities.RAGE_DECAY_DELAY) {
            minion.rage = Math.max(0, minion.rage - (int) MinionAbilities.RAGE_DECAY_PER_SECOND);
        }
        if (minion.enragedAt >= 0 && second && now > minion.enragedAt && !minion.undamageable()) { // 2026-10-29：不會受傷的不會失血
            // 激怒：每秒失去最大生命 1%，每過一秒失去的速度更多 20%
            final double loss = MinionSheets.life(minion.effective) * MinionAbilities.enrageDegen((now - minion.enragedAt) / 20.0 - 1)
                    * minion.healthScale;
            final double left = minion.mob.getHealth() - loss;
            minion.mob.setHealth(Math.max(0, left));
            if (left <= 0) {
                return;
            }
        }
        if (minion.leapLandAt > 0 && now >= minion.leapLandAt) {
            land(minion, now);
        }
        if (minion.iceArmourUntil > now && now % 10 == 0) {
            Fx.spawn(minion.mob.getWorld(), org.bukkit.Particle.SNOWFLAKE, minion.mob.getLocation().add(0, 1, 0), 2, 0.3, 0.5, 0.3, 0);
        }
    }

    /** 這一下用 with（屬性表與技能描述）打 target：召喚物自己的技能。 */
    private void strike(final Minion minion, final LivingEntity target, final Attack with) {
        if (!target.isValid() || target.isDead() || !minion.mob.isValid()) {
            return;
        }
        final Attack previous = minion.override;
        minion.override = with;
        try {
            target.damage(1.0, minion.mob); // 數字由 CombatListener 換掉（MinionHooks.attack 回傳 override）
        } finally {
            minion.override = previous;
        }
    }

    /** 召喚物的攻擊技能：普攻的描述乘 factor（碎骨 180%、毒氣箭 80%…），加幾個標籤；conversion 不是 null 就換掉物理轉換。 */
    private static SkillProfile attackProfile(final Minion minion, final String id, final double factor, final Set<String> extraTags,
                                              final Map<tw.exilecore.stats.DamageType, Double> conversion, final List<Modifier> extra) {
        final SkillProfile base = minion.profile;
        final Set<String> tags = new HashSet<>(base.tags());
        tags.addAll(extraTags);
        final List<Modifier> local = new ArrayList<>(base.local());
        local.addAll(musterModifiers(minion)); // 2026-10-30：群鋒
        if (Math.abs(factor - 1) > 1e-9) {
            local.add(Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, factor - 1, id));
        }
        local.addAll(extra);
        return new SkillProfile(base.id() + "." + id, tags, base.effectiveness(), null, null,
                conversion == null ? base.conversion() : conversion, local);
    }

    /**
     * 召喚物的法術（電弧、冰箭、死亡風暴、震地咆哮的冰爆…）：傷害照 PoE2 的表（寶石等級）換成我們的單位，
     * 不吃召喚物的武器傷害與傷害倍率（PoB 也是），基礎暴擊率照技能。
     */
    private static SkillProfile spellProfile(final Minion minion, final String id, final tw.exilecore.stats.DamageType type,
                                             final double[] ref, final double crit, final Set<String> extraTags, final List<Modifier> extra) {
        final SkillProfile base = minion.profile;
        final Set<String> tags = new HashSet<>(base.tags());
        tags.remove(tw.exilecore.stats.Tags.ATTACK);
        tags.remove(tw.exilecore.stats.Tags.MELEE);
        tags.remove(tw.exilecore.stats.Tags.PROJECTILE);
        tags.remove(tw.exilecore.stats.Tags.PHYSICAL);
        tags.add(type.tag());
        tags.addAll(extraTags);
        final double factor = minion.factors[0];
        final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket().set(type, ref[0] * factor);
        final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket().set(type, Math.max(ref[0], ref[1]) * factor);
        final List<Modifier> local = new ArrayList<>(base.local());
        local.addAll(musterModifiers(minion)); // 2026-10-30：群鋒
        local.add(Modifier.flat(tw.exilecore.stats.StatKeys.SPELL_BASE_CRIT, crit, id));
        local.addAll(extra);
        return new SkillProfile(base.id() + "." + id, tags, 1.0, min, max, Map.of(), local);
    }

    private Attack with(final Minion minion, final SkillProfile profile) {
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        return new Attack(owner, minion.sheet, commanded(minion, profile)); // 2026-10-27 照 PoE2 Commandment
    }

    /** 碰得到目標時出手（照 PoE2 每一種自己的技能）；回傳幾 tick 後再出手。 */
    private long act(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        switch (minion.skillId()) {
            case MinionAbilities.STORM -> {
                arc(minion, target, owner);
                return castTicks(minion, MinionAbilities.ARC_CAST);
            }
            case MinionAbilities.FROST -> {
                iceShard(minion, target, owner);
                return castTicks(minion, MinionAbilities.SHARD_CAST);
            }
            case MinionAbilities.BRUTE -> {
                if (!minion.onCooldown(BONEBREAKER, now)) {
                    bonebreaker(minion, target, owner, now);
                    return Math.max(4, Math.round(minion.attackTicks / MinionAbilities.BONEBREAKER_SPEED));
                }
            }
            case MinionAbilities.ARSONIST -> {
                // 火彈：1.6 格、70% 轉火焰（summon 的 radius、convert），落點引爆毒雲（impact）。
                // 2026-10-28 照 PoE2 Skeletal Arsonist 的品質（Fires +(0—2) Projectiles，無條件捨去）：
                // 2026-10-29 照 PoE2 改成多射——跟輔助的多射一起散開射出（附近沒有別的敵人也射）；這次之前落在附近其他的敵人身上、沒有別的就不丟
                attack(minion, target, OriginalSkills.qualityInt(minion.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.EXTRA_PROJECTILES)));
                return minion.attackTicks;
            }
            case MinionAbilities.MANIFEST -> {
                return manifestAct(minion, target, owner, now); // 2026-10-29 照 PoE2：照武器類型多一招
            }
            case MinionAbilities.BEAST -> {
                // 2026-10-30 照 PoE2 Tame Beast：收服的野獸用那種怪自己的招式與詞綴給的招式（冷卻好了就放，沒有就普攻）
                final long used = minion.tamedSkills.isEmpty() ? 0 : beastSkill(minion, target, owner, now);
                if (used > 0) {
                    return used;
                }
            }
            case ANCESTRAL -> {
                // 2026-10-30 照 PoE2：先祖之靈照型態用自己的招式（冷卻好了就放，沒有就普攻）
                final long used = ancestralAct(minion, target, owner, now);
                if (used > 0) {
                    return used;
                }
            }
            case MinionAbilities.REAVER -> {
                attack(minion, target, 0);
                if (minion.enragedAt >= 0) {
                    minion.rage = Math.min(MinionAbilities.RAGE_MAX, minion.rage + MinionAbilities.RAGE_ON_HIT); // 激怒：擊中獲得 2 點怒氣
                    minion.rageGainedAt = now;
                }
                return Math.max(4, Math.round(minion.attackTicks / (1 + MinionAbilities.RAGE_SPEED * minion.rage * rageEffect(minion))));
            }
            default -> {
            }
        }
        attack(minion, target, 0);
        return minion.attackTicks;
    }

    // ---- 先祖之靈（2026-10-30 照 PoE2 Ancestral Spirits 的四種型態）----

    /** 型態的招式：好了就放，回傳幾 tick 後再出手；0 = 這次普攻。 */
    private long ancestralAct(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        if (minion.form == null) {
            return 0;
        }
        switch (minion.form) {
            case JADECASTER -> {
                if (!minion.onCooldown(ENCASE, now)) {
                    minion.startCooldown(ENCASE, now, AncestralForms.ENCASE_COOLDOWN);
                    encase(minion, target, owner, now);
                    return castTicks(minion, 1.0);
                }
                jadeBolt(minion, target);
                return castTicks(minion, AncestralForms.CASTER_BOLT_CAST);
            }
            case JADE_HULK -> {
                if (!minion.onCooldown(HULK_CRY, now)) {
                    minion.startCooldown(HULK_CRY, now, AncestralForms.HULK_CRY_COOLDOWN);
                    ancestralBuff(minion, owner, "ancestral_hulk_cry", "先祖玉獸的戰吼", List.of(Modifier.increased(tw.exilecore.stats.StatKeys.DAMAGE,
                            AncestralForms.HULK_CRY_DAMAGE / 100.0, "先祖玉獸的戰吼")), Math.round(AncestralForms.ALLY_BUFF_SECONDS * 20), true);
                    return castTicks(minion, 1.0);
                }
                if (!minion.onCooldown(HULK_CHARGE, now)) {
                    minion.startCooldown(HULK_CHARGE, now, AncestralForms.CHARGE_COOLDOWN);
                    tableCharge(minion, target);
                    return minion.attackTicks;
                }
            }
            case WARHORN -> {
                if (!minion.onCooldown(HORN, now)) {
                    minion.startCooldown(HORN, now, AncestralForms.HORN_COOLDOWN);
                    final String source = "先祖戰角的號角";
                    ancestralBuff(minion, owner, "ancestral_horn", source, List.of(
                            Modifier.flat(tw.exilecore.stats.StatKeys.RAGE_ON_HIT, AncestralForms.HORN_RAGE, source),
                            Modifier.increased(tw.exilecore.stats.StatKeys.ATTACK_SPEED, AncestralForms.HORN_SPEED / 100.0, source),
                            Modifier.increased(tw.exilecore.stats.StatKeys.CAST_SPEED, AncestralForms.HORN_SPEED / 100.0, source)),
                            Math.round(AncestralForms.ALLY_BUFF_SECONDS * 20), true);
                    return castTicks(minion, 1.0);
                }
            }
            case TURTLE -> {
            }
        }
        return 0;
    }

    /** 玉法師的冰冷投射物（照 PoE2 召喚物表的每一下傷害 × 型態的傷害倍率，±20%）。 */
    private void jadeBolt(final Minion minion, final LivingEntity target) {
        final double hit = MinionSheets.refAllyDamage(MinionSheets.allyLevel(minion.build.level())) * minion.spec.damage();
        final Attack bolt = with(minion, spellProfile(minion, "jade_bolt", tw.exilecore.stats.DamageType.COLD, new double[] {hit * 0.8, hit * 1.2},
                AncestralForms.CASTER_BOLT_CRIT, Set.of(tw.exilecore.stats.Tags.PROJECTILE), List.of()));
        minion.mob.swingMainHand();
        shoot(minion, target, "cold", 0, (shot, struck, where) -> {
            final boolean forked = Math.abs(shot.damage() - 1) > 1e-9;
            strike(minion, struck, forked ? scaled(bolt, shot.damage()) : bolt);
        });
    }

    /** 玉法師的封玉：定身目標周圍的敵人。 */
    private void encase(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        if (plugin.combat() == null) {
            return;
        }
        final Location center = target.getLocation();
        for (final LivingEntity near : targets.around(center, AncestralForms.ENCASE_RADIUS * areaScale(minion) + target.getWidth() / 2, owner)) {
            plugin.combat().debuffs().apply(near.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("pin", owner.getUniqueId(),
                    now + AncestralForms.ENCASE_TICKS, 0, false, null, List.of(), 1.0));
            if (plugin.combat().numbers() != null) {
                plugin.combat().numbers().word(owner, near, "定身");
            }
        }
        Fx.spawn(center.getWorld(), org.bukkit.Particle.HAPPY_VILLAGER, center.clone().add(0, 1, 0), 20, 0.8, 0.6, 0.8, 0.02);
    }

    /** 玉獸的圓盾衝鋒：撞向目標，擊退並大量暈眩累積。 */
    private void tableCharge(final Minion minion, final LivingEntity target) {
        final Vector direction = target.getLocation().toVector().subtract(minion.mob.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() > 1e-4) {
            direction.normalize();
            minion.mob.setVelocity(direction.clone().multiply(0.6).setY(0.1));
        }
        minion.mob.swingMainHand();
        final Attack bash = with(minion, musterProfile(minion).withLocal(List.of(Modifier.more(tw.exilecore.stats.StatKeys.STUN_BUILDUP,
                AncestralForms.CHARGE_STUN_MORE, "圓盾衝鋒"))));
        strike(minion, target, bash);
        final boolean boss = plugin.monsters().data(target).map(tw.exilecore.monster.MonsterData::boss).orElse(false);
        if (target.isValid() && !target.isDead() && !boss && direction.lengthSquared() > 1e-4) {
            target.setVelocity(direction.multiply(0.8).setY(0.3));
        }
    }

    /**
     * 先祖之靈給友軍的增益（範圍內的主人與主人的召喚物）：主人用技能增益（{@link SkillBuffs}），召喚物各自記一份。
     * show：放出來時的特效（光環每秒刷新時不放）。
     */
    private void ancestralBuff(final Minion source, final Player owner, final String id, final String name, final List<Modifier> mods,
                               final long ticks, final boolean show) {
        final Location at = source.mob.getLocation();
        final double radius = (show ? AncestralForms.ALLY_RADIUS : AncestralForms.TURTLE_RADIUS) * areaScale(source);
        final double radiusSq = radius * radius;
        if (owner.getWorld().equals(at.getWorld()) && owner.getLocation().distanceSquared(at) <= radiusSq && plugin.skills() != null) {
            plugin.skills().buffs().apply(owner, id, name, (int) ticks, mods);
        }
        final StatSheet ownerSheet = plugin.players().sheet(owner);
        for (final Minion other : minions) {
            if (!other.owner.equals(source.owner) || other.decoy || !other.mob.isValid() || !other.mob.getWorld().equals(at.getWorld())
                    || other.mob.getLocation().distanceSquared(at) > radiusSq) {
                continue;
            }
            final boolean fresh = !other.allyBuffs.containsKey(id);
            other.allyBuffs.put(id, mods);
            other.allyBuffUntil.put(id, now() + ticks);
            if (fresh) {
                rebuild(other, ownerSheet, false);
            }
        }
        if (show) {
            Fx.spawn(at.getWorld(), org.bukkit.Particle.NOTE, at.clone().add(0, source.mob.getHeight() + 0.3, 0), 6, 0.4, 0.2, 0.4, 0.5);
        }
    }

    /** 靈龜的光環：每秒刷新範圍內友軍的防禦增加 80%、格擋率增加 40%（持續 1.5 秒，離開範圍很快就沒了）。 */
    private void turtleAura(final Minion minion, final Player owner) {
        final String source = "先祖靈龜的光環";
        ancestralBuff(minion, owner, "ancestral_turtle", source, List.of(
                Modifier.increased(tw.exilecore.stats.StatKeys.ARMOUR, AncestralForms.TURTLE_DEFENCES / 100.0, source),
                Modifier.increased(tw.exilecore.stats.StatKeys.EVASION, AncestralForms.TURTLE_DEFENCES / 100.0, source),
                Modifier.increased(tw.exilecore.stats.StatKeys.ES_MAX, AncestralForms.TURTLE_DEFENCES / 100.0, source),
                Modifier.increased(tw.exilecore.stats.StatKeys.BLOCK_CHANCE, AncestralForms.TURTLE_BLOCK / 100.0, source)), 30, false);
    }

    // ---- 幻化武器（2026-10-29 照 PoE2 Manifest Weapon）----

    /** 主人裝備欄主手的那把武器（複製一份、數量 1）；沒拿回傳 null。 */
    private ItemStack manifestItem(final Player owner) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
        if (state == null) {
            return null;
        }
        for (int i = 0; i < tw.exilecore.character.PlayerState.JEWELLERY_SLOTS.length; i++) {
            if (tw.exilecore.character.PlayerState.JEWELLERY_SLOTS[i] == tw.exilecore.item.ItemSlot.MAIN_HAND) {
                final ItemStack stack = state.jewellery(i);
                if (stack == null || stack.isEmpty()) {
                    return null;
                }
                final ItemStack copy = stack.clone();
                copy.setAmount(1);
                return copy;
            }
        }
        return null;
    }

    /**
     * 主人現在拿的武器幻化成哪一種（{@link MinionAbilities#manifestForm}）：看生效的主手武器（需求不符的不算），
     * 主手真的有拿東西（空掌之術、毀面者這種「當作拿著」的不算）；不能幻化回傳 null。
     */
    MinionAbilities.ManifestForm manifestForm(final Player owner) {
        if (owner == null || manifestItem(owner) == null) {
            return null;
        }
        return MinionAbilities.manifestForm(plugin.players().get(owner).weaponTags());
    }

    /** 主人換了武器：手上拿的換成新的那把。 */
    private void syncManifestWeapon(final Minion minion, final Player owner) {
        final EntityEquipment equipment = minion.mob.getEquipment();
        if (equipment == null) {
            return;
        }
        final ItemStack wanted = manifestItem(owner);
        final ItemStack holding = equipment.getItemInMainHand();
        if (wanted == null || wanted.isSimilar(holding)) {
            return;
        }
        equipment.setItem(EquipmentSlot.HAND, wanted, true);
    }

    /**
     * 碰得到目標時的這一下：錘的重擊（180%、攻擊時間兩倍、冷卻 6 秒）、長棍的橫掃（90%、打身邊一圈、冷卻 6 秒）、
     * 獸魂符的爪擊（160%、冷卻 3 秒）好了就用，不然普通的打擊。回傳幾 tick 後再出手。
     */
    private long manifestAct(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        final MinionAbilities.ManifestForm form = manifestForm(owner);
        if (form == MinionAbilities.ManifestForm.MACE && !minion.onCooldown(MANIFEST_SLAM, now)) {
            minion.startCooldown(MANIFEST_SLAM, now, MinionAbilities.MANIFEST_SLAM_COOLDOWN);
            minion.mob.swingMainHand();
            final Location at = target.getLocation();
            manifestArea(minion, owner, target, at, MinionAbilities.MANIFEST_SLAM_RADIUS, "manifest_slam", MinionAbilities.MANIFEST_SLAM_DAMAGE,
                    Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.SLAM));
            Fx.spawn(at.getWorld(), org.bukkit.Particle.EXPLOSION, at.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0);
            Fx.spawn(at.getWorld(), org.bukkit.Particle.BLOCK_CRUMBLE, at, 16, 0.6, 0.1, 0.6, 0, Material.STONE.createBlockData());
            return Math.max(4, Math.round(minion.attackTicks / MinionAbilities.MANIFEST_SLAM_SPEED));
        }
        if (form == MinionAbilities.ManifestForm.QUARTERSTAFF && !minion.onCooldown(MANIFEST_SWEEP, now)) {
            minion.startCooldown(MANIFEST_SWEEP, now, MinionAbilities.MANIFEST_SWEEP_COOLDOWN);
            minion.mob.swingMainHand();
            final Location at = minion.mob.getLocation();
            manifestArea(minion, owner, target, at, MinionAbilities.MANIFEST_SWEEP_RADIUS, "manifest_sweep", MinionAbilities.MANIFEST_SWEEP_DAMAGE,
                    Set.of(tw.exilecore.stats.Tags.AREA));
            Fx.spawn(at.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, at.clone().add(0, 0.8, 0), 6,
                    MinionAbilities.MANIFEST_SWEEP_RADIUS / 2, 0.1, MinionAbilities.MANIFEST_SWEEP_RADIUS / 2, 0);
            return minion.attackTicks;
        }
        if (form == MinionAbilities.ManifestForm.TALISMAN && !minion.onCooldown(MANIFEST_RAKE, now)) {
            minion.startCooldown(MANIFEST_RAKE, now, MinionAbilities.MANIFEST_RAKE_COOLDOWN);
            minion.mob.swingMainHand();
            final Location at = target.getLocation();
            manifestArea(minion, owner, target, at, MinionAbilities.MANIFEST_RAKE_RADIUS, "manifest_rake", MinionAbilities.MANIFEST_RAKE_DAMAGE,
                    Set.of(tw.exilecore.stats.Tags.AREA));
            Fx.spawn(at.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, at.clone().add(0, 1, 0), 4, 0.6, 0.3, 0.6, 0);
            return minion.attackTicks;
        }
        attack(minion, target, 0);
        return minion.attackTicks;
    }

    /** 矛的突進：衝到目標面前（3～20 公尺），落點 180% 範圍；回傳幾 tick 後再出手。 */
    private long lunge(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        minion.startCooldown(MANIFEST_LUNGE, now, MinionAbilities.MANIFEST_LUNGE_COOLDOWN);
        final Location from = minion.mob.getLocation();
        final Vector direction = target.getLocation().toVector().subtract(from.toVector());
        final double length = direction.length();
        if (length < 1e-3) {
            return minion.attackTicks;
        }
        direction.multiply(1 / length);
        final double stop = Math.max(0, length - minion.spec.reach() - target.getWidth() / 2);
        final Location to = from.clone().add(direction.clone().multiply(stop));
        to.setDirection(direction);
        minion.mob.getPathfinder().stopPathfinding();
        for (double d = 0; d < stop; d += 0.6) {
            Fx.spawn(from.getWorld(), org.bukkit.Particle.CRIT, from.clone().add(direction.clone().multiply(d)).add(0, 0.8, 0), 1, 0, 0, 0, 0);
        }
        minion.mob.teleport(to);
        minion.mob.swingMainHand();
        manifestArea(minion, owner, target, target.getLocation(), MinionAbilities.MANIFEST_LUNGE_RADIUS, "manifest_lunge",
                MinionAbilities.MANIFEST_LUNGE_DAMAGE, Set.of(tw.exilecore.stats.Tags.AREA));
        Fx.spawn(target.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 2, 0.3, 0.2, 0.3, 0);
        return minion.attackTicks;
    }

    /** 打 center 周圍 radius 格（吃召喚物的範圍加成）的敵人，瞄準的那隻一定算到。 */
    private void manifestArea(final Minion minion, final Player owner, final LivingEntity aimed, final Location center, final double radius,
                              final String id, final double factor, final Set<String> tags) {
        if (owner == null) {
            return;
        }
        final double reach = radius * areaScale(minion);
        final List<LivingEntity> hits = new ArrayList<>(targets.around(center, reach, owner));
        if (aimed != null && aimed.isValid() && !hits.contains(aimed)) {
            hits.add(aimed);
        }
        final Attack hit = with(minion, attackProfile(minion, id, factor, tags, null, List.of()));
        for (final LivingEntity near : hits) {
            strike(minion, near, hit);
        }
    }

    /** 碎骨：180%，重暈蓄勢待暈的敵人時接震波（480%、2 格）。 */
    private void bonebreaker(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        minion.startCooldown(BONEBREAKER, now, MinionAbilities.BONEBREAKER_COOLDOWN);
        minion.mob.swingMainHand();
        final boolean primed = plugin.ailments() != null && plugin.ailments().isPrimed(target);
        strike(minion, target, with(minion, attackProfile(minion, "bonebreaker", MinionAbilities.BONEBREAKER_DAMAGE,
                Set.of(tw.exilecore.stats.Tags.AREA), null, List.of())));
        Fx.spawn(target.getWorld(), org.bukkit.Particle.BLOCK_CRUMBLE, target.getLocation(), 12, 0.4, 0.1, 0.4, 0,
                Material.BONE_BLOCK.createBlockData());
        if (primed && plugin.ailments().heavyStunned(target)) {
            final Location at = target.getLocation();
            final double radius = MinionAbilities.SHOCKWAVE_RADIUS * areaScale(minion);
            final Attack wave = with(minion, attackProfile(minion, "shockwave", MinionAbilities.SHOCKWAVE_DAMAGE,
                    Set.of(tw.exilecore.stats.Tags.AREA), null, List.of()));
            for (final LivingEntity near : targets.around(at, radius, owner)) {
                strike(minion, near, wave);
            }
            Fx.spawn(at.getWorld(), org.bukkit.Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
        }
    }

    /** 電弧：打目標再連鎖 2 次（3.5 格）。 */
    private void arc(final Minion minion, final LivingEntity target, final Player owner) {
        final int level = minion.build.level();
        final Attack hit = with(minion, spellProfile(minion, "chain_thunder", tw.exilecore.stats.DamageType.LIGHTNING,
                MinionAbilities.range(MinionAbilities.ARC_LIGHTNING, level), MinionAbilities.ARC_CRIT, Set.of(),
                List.of(Modifier.more(tw.exilecore.stats.StatKeys.SHOCK_CHANCE, 1.0, "電弧"))));
        final List<LivingEntity> chain = new ArrayList<>();
        chain.add(target);
        LivingEntity last = target;
        // 2026-10-29 照 PoE2（Skeletal Storm Mage 的 Minion Types 有 Chains）：連環這類「多連鎖 x 次」加在電弧的 2 次上
        final int chains = MinionAbilities.ARC_CHAINS + MinionTypes.projectiles(minion.effective, hit.profile().tags()).chains();
        for (int i = 0; i < chains; i++) {
            LivingEntity next = null;
            for (final LivingEntity near : targets.around(last.getLocation(), MinionAbilities.ARC_CHAIN_RANGE, owner)) {
                if (!chain.contains(near)) {
                    next = near;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            chain.add(next);
            last = next;
        }
        bolt(minion.mob, target, "lightning");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                line(chain.get(i - 1), chain.get(i));
            }
            strike(minion, chain.get(i), hit);
        }
    }

    private static void line(final LivingEntity from, final LivingEntity to) {
        final Location a = SkillTargets.center(from);
        final Location b = SkillTargets.center(to);
        final Vector step = b.toVector().subtract(a.toVector());
        final double length = step.length();
        if (length < 0.1) {
            return;
        }
        step.multiply(0.5 / length);
        final Location at = a.clone();
        for (double d = 0; d < length; d += 0.5) {
            Fx.spawn(at.getWorld(), org.bukkit.Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
    }

    /** 冰箭：投射物打目標，再炸開 0.8 格；必定凍結累積、凍結累積更多 100%。 */
    private void iceShard(final Minion minion, final LivingEntity target, final Player owner) {
        final int level = minion.build.level();
        final List<Modifier> freeze = List.of(Modifier.more(tw.exilecore.stats.StatKeys.FREEZE_BUILDUP, 1.0, "冰箭"));
        final Attack shard = with(minion, spellProfile(minion, "ice_shard", tw.exilecore.stats.DamageType.COLD,
                MinionAbilities.range(MinionAbilities.SHARD_COLD, level), MinionAbilities.SHARD_CRIT, Set.of(tw.exilecore.stats.Tags.PROJECTILE), freeze));
        final Attack explosion = with(minion, spellProfile(minion, "ice_shard_explosion", tw.exilecore.stats.DamageType.COLD,
                MinionAbilities.range(MinionAbilities.SHARD_EXPLOSION_COLD, level), MinionAbilities.SHARD_EXPLOSION_CRIT,
                Set.of(tw.exilecore.stats.Tags.AREA), freeze));
        // 2026-10-29 照 PoE2（Skeletal Frost Mage 的 Minion Types 有 Projectile）：冰箭吃多射、穿透、分裂、連鎖，每一發打中的都炸開；
        // 這次之前只打目標一隻。2026-10-29 照 PoE2：冰箭真的會飛（MinionProjectiles，吃投射物速度），多射的一律散開射出；這次之前直接命中
        minion.mob.swingMainHand();
        shoot(minion, target, "cold", 0, (shot, hit, where) -> {
            final boolean forked = Math.abs(shot.damage() - 1) > 1e-9; // 分裂出來的更少 20%（跟玩家的一樣）
            strike(minion, hit, forked ? scaled(shard, shot.damage()) : shard);
            final Location at = hit.getLocation();
            final Attack blast = forked ? scaled(explosion, shot.damage()) : explosion;
            for (final LivingEntity near : targets.around(at, MinionAbilities.SHARD_RADIUS * areaScale(minion) + hit.getWidth() / 2, owner)) {
                strike(minion, near, blast);
            }
            Fx.spawn(at.getWorld(), org.bukkit.Particle.SNOWFLAKE, at.clone().add(0, 1, 0), 8, 0.3, 0.3, 0.3, 0.02);
        });
    }

    /** 2026-10-29：同一招的傷害乘 factor（分裂出來的投射物）。 */
    private static Attack scaled(final Attack attack, final double factor) {
        return new Attack(attack.owner(), attack.sheet(), attack.profile().scaled(factor, "分裂"));
    }

    /** 躍擊：往目標跳過去，落地時打 1 格內的敵人並擊退；回傳幾 tick 後再出手。 */
    private long leap(final Minion minion, final LivingEntity target, final double distance, final long now) {
        minion.startCooldown(LEAP, now, MinionAbilities.LEAP_COOLDOWN);
        final Vector direction = target.getLocation().toVector().subtract(minion.mob.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 1e-4) {
            return minion.attackTicks;
        }
        minion.mob.getPathfinder().stopPathfinding();
        // 空中每 tick 水平速度 × 0.91：十 tick 大約走 6.7 倍的初速
        direction.normalize().multiply(Math.max(0, distance - 0.6) / 6.7);
        minion.mob.setVelocity(direction.setY(0.42));
        minion.leapLandAt = now + 10;
        final long extra = Math.round(MinionAbilities.LEAP_MAX_EXTRA * 20 * Math.min(1, distance / MinionAbilities.LEAP_MAX));
        return 10 + Math.max(4, Math.round(minion.attackTicks / MinionAbilities.LEAP_SPEED)) + extra;
    }

    private void land(final Minion minion, final long now) {
        minion.leapLandAt = 0;
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner == null) {
            return;
        }
        final Location at = minion.mob.getLocation();
        final double radius = MinionAbilities.LEAP_RADIUS * areaScale(minion) + minion.mob.getWidth() / 2;
        final Attack slam = minion.form == AncestralForms.JADE_HULK
                ? with(minion, attackProfile(minion, "jade_leap", AncestralForms.DASH_IMPACT, Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.SLAM), null,
                        List.of(Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, AncestralForms.DASH_IMPACT_MORE, "幻步落地"))))
                : with(minion, attackProfile(minion, "leap", 1.0, Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.SLAM), null, List.of()));
        final List<LivingEntity> hits = new ArrayList<>(targets.around(at, radius, owner));
        final LivingEntity aimed = minion.target == null ? null : living(minion.target);
        if (aimed != null && !hits.contains(aimed) && Math.sqrt(SkillTargets.boxDistanceSquared(at, aimed.getBoundingBox())) <= radius) {
            hits.add(aimed);
        }
        for (final LivingEntity near : hits) {
            strike(minion, near, slam);
            if (near.isValid() && !near.isDead() && plugin.monsters() != null
                    && !plugin.monsters().data(near).map(data -> data.boss()).orElse(false)) {
                final Vector push = near.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (push.lengthSquared() > 1e-4) {
                    near.setVelocity(push.normalize().multiply(0.5).setY(0.2)); // 擊退、推出落點
                }
            }
        }
        Fx.spawn(at.getWorld(), org.bukkit.Particle.CLOUD, at.clone().add(0, 0.2, 0), 8, 0.4, 0.05, 0.4, 0.02);
    }

    /** 祭司：治療一隻受傷的召喚物（4 秒的固定生命回復）、復活一隻倒下的骷髏（冷卻 5 秒）。 */
    private void cleric(final Minion cleric, final Player owner, final long now) {
        if (!cleric.onCooldown(HEAL, now)) {
            Minion pick = null;
            double lowest = 1;
            final Location at = cleric.mob.getLocation();
            for (final Minion other : minions) {
                if (!other.owner.equals(cleric.owner) || other.decoy || !other.mob.isValid() || other.mob.isDead()
                        || ignoredByEnemies(other) || !other.mob.getWorld().equals(at.getWorld())
                        || other.mob.getLocation().distanceSquared(at) > MinionAbilities.HEAL_RANGE * MinionAbilities.HEAL_RANGE) {
                    continue;
                }
                final AttributeInstance health = other.mob.getAttribute(Attribute.MAX_HEALTH);
                final double fraction = health == null || health.getValue() <= 0 ? 1 : other.mob.getHealth() / health.getValue();
                if (fraction < lowest - 1e-6 && other.regenUntil <= now) {
                    lowest = fraction;
                    pick = other;
                }
            }
            if (pick != null) {
                cleric.mob.swingMainHand();
                pick.regenUntil = now + Math.round(MinionAbilities.HEAL_SECONDS * 20);
                pick.regenPerSecond = MinionAbilities.HEAL_PER_MINUTE[MinionAbilities.row40(cleric.build.level())] / 60.0 * cleric.factors[1];
                Fx.spawn(pick.mob.getWorld(), org.bukkit.Particle.HAPPY_VILLAGER, pick.mob.getLocation().add(0, 1.2, 0), 6, 0.4, 0.4, 0.4, 0);
                cleric.ready.put(HEAL, now + castTicks(cleric, MinionAbilities.HEAL_CAST));
            }
        }
        final String revived = !cleric.onCooldown(REVIVE, now) && auras != null ? auras.reviveSkeleton(cleric.owner, now) : null;
        if (revived != null) {
            cleric.startCooldown(REVIVE, now, MinionAbilities.REVIVE_COOLDOWN);
            // 2026-10-28 照 PoE2 Skeletal Cleric 的品質：復活的骷髏 (0—3) 秒不會受到傷害
            final double immune = cleric.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CLERIC_REVIVE_IMMUNE);
            if (immune > 0) {
                final UUID back = newest(cleric.owner, revived);
                final Minion fresh = back == null ? null : byEntity.get(back);
                if (fresh != null) {
                    fresh.immuneUntil = now + Math.round(immune * 20);
                }
            }
            Fx.spawn(cleric.mob.getWorld(), org.bukkit.Particle.TOTEM_OF_UNDYING, cleric.mob.getLocation().add(0, 1.2, 0), 10, 0.3, 0.5, 0.3, 0.1);
        }
    }

    /** 冰甲：打牠的敵人受到冰冷傷害（從這隻召喚物身上打出去）。 */
    private void retaliate(final Minion minion, final LivingEntity attacker) {
        if (!byEntity.containsKey(minion.mob.getUniqueId()) || !attacker.isValid() || attacker.isDead() || minion.profile == null) {
            return;
        }
        strike(minion, attacker, with(minion, spellProfile(minion, "ice_armour", tw.exilecore.stats.DamageType.COLD,
                new double[] {minion.iceArmourCold[0] / Math.max(1e-9, minion.factors[0]), minion.iceArmourCold[1] / Math.max(1e-9, minion.factors[0])},
                5, Set.of(), List.of())));
    }

    // ---- 毒雲 ----

    /** 每 tick：毒雲變大、每秒讓裡面的敵人中毒；碰到點燃中的敵人就炸開；時間到消失。 */
    private void tickClouds(final long now) {
        if (clouds.isEmpty()) {
            return;
        }
        for (final Cloud cloud : new ArrayList<>(clouds)) {
            final Player owner = plugin.getServer().getPlayer(cloud.owner);
            if (now >= cloud.until || owner == null || !owner.isOnline() || !clouds.contains(cloud)) {
                clouds.remove(cloud);
                continue;
            }
            final double radius = cloud.radius(now);
            if (now % 4 == 0) {
                final double angle = (now * 0.4) % (Math.PI * 2);
                Fx.as(cloud.owner, () -> tw.exilecore.skill.Fx.disk(cloud.at.getWorld(), org.bukkit.Particle.DUST_COLOR_TRANSITION, cloud.at.clone().add(Math.cos(angle) * radius * 0.6, 0.4, Math.sin(angle) * radius * 0.6), 3, radius, 0.2, 0, new org.bukkit.Particle.DustTransition(org.bukkit.Color.fromRGB(0x6a, 0xb0, 0x3c), org.bukkit.Color.fromRGB(0x3c, 0x5a, 0x20), 1.4f)));
            }
            if ((now - cloud.born) % 20 != 0) {
                continue;
            }
            boolean ignited = false;
            for (final LivingEntity near : targets.around(cloud.at, radius, owner)) {
                if (plugin.ailments() != null && plugin.ailments().has(near, tw.exilecore.ailment.AilmentType.IGNITE)) {
                    ignited = true;
                }
                if (plugin.ailments() != null) {
                    plugin.ailments().poisonFrom(owner, near, cloud.poisonBase, cloud.sheet); // 不擊中，像 220% 的擊中一樣中毒
                }
            }
            if (ignited) {
                explode(cloud, null, now); // 碰到點燃中的敵人
            }
        }
    }

    /** 引爆器（火彈、爆裂殉身）：at 附近 radius 內 owner 的毒雲炸開。 */
    private void detonate(final UUID owner, final Location at, final double radius, final Minion by) {
        if (clouds.isEmpty()) {
            return;
        }
        final long now = now();
        for (final Cloud cloud : new ArrayList<>(clouds)) {
            if (cloud.owner.equals(owner) && cloud.at.getWorld().equals(at.getWorld())) {
                final double reach = radius + cloud.radius(now);
                if (cloud.at.distanceSquared(at) <= reach * reach) {
                    explode(cloud, by, now);
                }
            }
        }
    }

    private void explode(final Cloud cloud, final Minion by, final long now) {
        if (!clouds.remove(cloud)) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(cloud.owner);
        final Minion source = byEntity.containsKey(cloud.sniper.mob.getUniqueId()) ? cloud.sniper
                : by != null && byEntity.containsKey(by.mob.getUniqueId()) ? by : null;
        Fx.spawn(cloud.at.getWorld(), org.bukkit.Particle.EXPLOSION, cloud.at.clone().add(0, 0.5, 0), 2, 0.4, 0.2, 0.4, 0);
        Fx.spawn(cloud.at.getWorld(), org.bukkit.Particle.FLAME, cloud.at.clone().add(0, 0.5, 0), 30, cloud.radius(now) * 0.5, 0.3,
                cloud.radius(now) * 0.5, 0.05);
        if (owner == null || source == null) {
            return; // 射手、引爆的都不在了：只有樣子
        }
        final Attack blast = new Attack(owner, cloud.sheet, cloud.explosion);
        for (final LivingEntity near : targets.around(cloud.at, cloud.radius(now), owner)) {
            strike(source, near, blast);
        }
    }

    // ---- 指令 ----

    /** 準心指的點（有鎖定的敵人就是牠腳下）。 */
    private Location aim(final Player player, final LivingEntity preferred) {
        if (preferred != null && preferred.getWorld().equals(player.getWorld())) {
            return preferred.getLocation();
        }
        return targets.aimPoint(Origin.of(player), 24);
    }

    /**
     * 按下精魂召喚的按鍵：對那種召喚物下指令（PoE2 的 Command）。範圍內每一隻冷卻好了的各放一次（冰甲只要一隻），
     * 花指令的魔力。這個技能沒有指令回傳 null（照舊提示「一直開著」）；其他情況回傳要給玩家看的一句話。
     */
    String command(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final String skillId = build.definition().id();
        final MinionAbilities.Command command = MinionAbilities.command(skillId);
        if (command == null || MinionAbilities.RAVENS.equals(skillId)) {
            return null;
        }
        if (ownerInTown(player)) {
            return "<red>營地裡不能下指令";
        }
        final long now = now();
        final Location at = player.getLocation();
        final String commandKey = player.getUniqueId() + ":" + skillId;
        final long skillReady = commandReady.getOrDefault(commandKey, 0L);
        final List<Minion> mine = new ArrayList<>();
        final List<Minion> ready = new ArrayList<>();
        for (final Minion minion : minions) {
            if (minion.owner.equals(player.getUniqueId()) && !minion.decoy && minion.skillId().equals(skillId) && minion.mob.isValid()
                    && minion.mob.getWorld().equals(at.getWorld()) && minion.mob.getLocation().distanceSquared(at) <= COMMAND_RANGE * COMMAND_RANGE
                    && minion.profile != null) {
                mine.add(minion);
                if (!minion.onCooldown(COMMAND, now) && skillReady <= now) {
                    ready.add(minion);
                }
            }
        }
        final String name = tw.exilecore.item.ItemRenderer.escape(command.name());
        if (mine.isEmpty()) {
            return "<red>附近沒有聽「" + name + "」的召喚物";
        }
        if (ready.isEmpty()) {
            long left = Long.MAX_VALUE;
            for (final Minion minion : mine) {
                left = Math.min(left, Math.max(skillReady, minion.ready.getOrDefault(COMMAND, now)) - now);
            }
            return String.format(java.util.Locale.ROOT, "<gray>%s冷卻中（%.1f 秒）", name, left / 20.0);
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final int mana = command.manaAt(build.level());
        if (state.mana() < mana) {
            return "<red>魔力不夠（" + name + "要 " + mana + " 點）";
        }
        final Location target = aim(player, preferred);
        final int used = switch (skillId) {
            case MinionAbilities.BRUTE -> each(ready, now, command, minion -> roar(minion, player, now));
            case MinionAbilities.SNIPER -> each(ready, now, command, minion -> gasArrow(minion, player, target, now));
            case MinionAbilities.ARSONIST -> demise(ready, player, target, command, now);
            case MinionAbilities.STORM -> each(ready, now, command, minion -> deathStorm(minion, player, target));
            case MinionAbilities.FROST -> each(List.of(nearest(ready, target)), now, command, minion -> iceArmour(minion, player, target));
            case MinionAbilities.REAVER -> enrage(mine, now, command);
            case MinionAbilities.ARTILLERY -> each(ready, now, command, minion -> deathFromAbove(minion, player, target)); // 2026-10-27 照 PoE2
            default -> 0;
        };
        if (used <= 0) {
            return MinionAbilities.ARSONIST.equals(skillId) ? "<red>目標附近沒有生命夠低、能引爆的召喚物" : "<red>" + name + "沒有放出來";
        }
        if (mana > 0) {
            state.setMana(state.mana() - mana);
        }
        long until = now;
        for (final Minion minion : mine) {
            until = Math.max(until, minion.ready.getOrDefault(COMMAND, now));
        }
        if (commandReady.size() > 256) {
            commandReady.values().removeIf(tick -> tick <= now);
        }
        commandReady.put(commandKey, until);
        return "<#8fd3ff>指令：" + name + "（" + used + " 隻）";
    }

    /** 2026-10-26 照 PoE2（矛盾命令）：召喚物聽從指令時失去最大生命的 x%（主人的 minion.command_life_loss；扣不死）。 */
    private void obeyed(final Minion minion) {
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null) {
            obeyed(owner, minion);
        }
    }

    private void obeyed(final Player owner, final Minion minion) {
        final double percent = plugin.players().sheet(owner).flat(tw.exilecore.passive.ConquestEffects.COMMAND_LIFE_LOSS, MinionSheets.CONTEXT);
        if (percent <= 0 || !minion.mob.isValid() || minion.undamageable()) { // 2026-10-29：不會受傷的召喚物不扣
            return;
        }
        final AttributeInstance max = minion.mob.getAttribute(Attribute.MAX_HEALTH);
        final double full = max == null ? minion.mob.getHealth() : max.getValue();
        minion.mob.setHealth(Math.max(1, Math.min(full, minion.mob.getHealth() - full * percent / 100.0)));
    }

    private static Minion nearest(final List<Minion> list, final Location at) {
        Minion best = list.getFirst();
        for (final Minion minion : list) {
            if (minion.mob.getLocation().distanceSquared(at) < best.mob.getLocation().distanceSquared(at)) {
                best = minion;
            }
        }
        return best;
    }

    private int each(final List<Minion> list, final long now, final MinionAbilities.Command command, final Consumer<Minion> action) {
        for (final Minion minion : list) {
            obeyed(minion);
            minion.startCooldown(COMMAND, now, command.cooldown());
            minion.mob.swingMainHand();
            action.accept(minion);
        }
        return list.size();
    }

    /** 震地咆哮：恫嚇 4 格內的敵人 4 秒；範圍內凍結中的敵人與冰甲中的召喚物各炸一次冰冷傷害（2.4 格），用掉凍結。 */
    private void roar(final Minion brute, final Player owner, final long now) {
        final Location at = brute.mob.getLocation();
        final double radius = MinionAbilities.ROAR_RADIUS * areaScale(brute);
        brute.mob.getWorld().playSound(at, org.bukkit.Sound.ENTITY_RAVAGER_ROAR, 0.8f, 1.2f);
        tw.exilecore.skill.Fx.disk(at.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, at.clone().add(0, 1, 0), 8, radius, 0.2, 0);
        final Attack blast = with(brute, spellProfile(brute, "shattering_roar", tw.exilecore.stats.DamageType.COLD,
                MinionAbilities.range(MinionAbilities.ROAR_COLD, brute.build.level()), MinionAbilities.ROAR_CRIT,
                Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.WARCRY), List.of()));
        final List<Location> bursts = new ArrayList<>();
        for (final LivingEntity near : targets.around(at, radius, owner)) {
            if (plugin.combat() != null) {
                plugin.combat().debuffs().apply(near.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("minion_intimidate", owner.getUniqueId(),
                        now + Math.round(MinionAbilities.ROAR_SECONDS * durationScale(brute) * 20), MinionAbilities.INTIMIDATE_TAKEN, false)); // 2026-10-29：恫嚇吃持續時間
            }
            if (plugin.ailments() != null && plugin.ailments().isFrozen(near)) {
                plugin.ailments().cure(near, tw.exilecore.ailment.AilmentType.FREEZE);
                bursts.add(near.getLocation());
            }
        }
        for (final Minion ally : List.copyOf(minions)) {
            if (ally.owner.equals(brute.owner) && ally.iceArmourUntil > now && ally.mob.isValid() && ally.mob.getWorld().equals(at.getWorld())
                    && ally.mob.getLocation().distanceSquared(at) <= radius * radius) {
                ally.iceArmourUntil = 0; // 冰甲算凍結：被消耗
                ally.nextRebuild = 0;
                bursts.add(ally.mob.getLocation());
            }
        }
        final double burst = MinionAbilities.ROAR_EXPLOSION * areaScale(brute);
        for (final Location point : bursts) {
            Fx.spawn(point.getWorld(), org.bukkit.Particle.ITEM_SNOWBALL, point.clone().add(0, 0.8, 0), 20, burst * 0.4, 0.4, burst * 0.4, 0.1);
            for (final LivingEntity near : targets.around(point, burst, owner)) {
                strike(brute, near, blast);
            }
        }
    }

    /** 毒氣箭：射到目標點（80%、1.4 格），留下 8 秒的毒雲。 */
    /**
     * 2026-10-27 照 PoE2 昇華：只聽指令的召喚物（砲手、巨靈）平常的位置：砲手站在你身後 2.5 格（PoE2：take up positions behind you），
     * 巨靈飄在你頭上（會飛的照 {@link #idle}）。
     */
    private void standBy(final Minion minion, final Player owner, final long now) {
        final Mob mob = minion.mob;
        if (mob.getTarget() != null) {
            mob.setTarget(null);
        }
        if (!MinionAbilities.ARTILLERY.equals(minion.skillId())) {
            idle(minion, owner, now);
            return;
        }
        if (!(mob instanceof Vex)) {
            mob.setAggressive(false);
        }
        final Vector facing = SkillTargets.facing(owner);
        final Location spot = owner.getLocation().subtract(facing.multiply(MinionAbilities.ARTILLERY_BEHIND));
        final double distance = mob.getLocation().distance(spot);
        if (distance > 1.2 && now >= minion.nextPath) {
            minion.nextPath = now + 10;
            mob.getPathfinder().moveTo(spot, distance > 8 ? 1.5 : 1.15);
        } else if (distance <= 0.8) {
            mob.getPathfinder().stopPathfinding();
        }
        if (now % 20 == minion.bornTick % 20) {
            mob.lookAt(owner.getLocation().add(facing.multiply(10)));
        }
        watchStuck(minion, owner, distance > FOLLOW_START, now);
    }

    /** 2026-10-27：owner 那個技能叫的（只聽指令的）召喚物在哪裡；沒有（或 20 格外、不同世界）回傳 null。 */
    public Location locate(final Player owner, final String skillId) {
        final Minion minion = commandOnlyMinion(owner, skillId);
        return minion == null ? null : minion.mob.getLocation();
    }

    /** 2026-10-27：巨靈聽指令瞬移到 at（PoE2：克拉里的欺瞞、魯兹安的清算會瞬移過去），之後再回到你身邊。回傳有沒有這隻。 */
    public boolean relocate(final Player owner, final String skillId, final Location at) {
        final Minion minion = commandOnlyMinion(owner, skillId);
        if (minion == null || at == null || at.getWorld() == null || !at.getWorld().equals(minion.mob.getWorld())) {
            return false;
        }
        final Location spot = at.clone();
        if (minion.spec.flying()) {
            spot.add(0, 1.0, 0);
        }
        spot.setYaw(minion.mob.getLocation().getYaw());
        minion.mob.teleport(spot);
        minion.mob.swingMainHand();
        minion.idleStopped = false;
        minion.nextPath = now() + 20;
        visuals.minionBlink(spot);
        return true;
    }

    /** 2026-10-29 照 PoE2：那位巨靈的屬性表（巨靈的指令照這張算；沒叫出來回傳 null）。 */
    public StatSheet djinnSheet(final UUID owner, final String djinn) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner) && minion.skillId().equals(djinn) && !minion.decoy && minion.mob.isValid() && minion.sheet != null) {
                return minion.sheet;
            }
        }
        return null;
    }

    /**
     * 2026-10-29 照 PoE2：這一下是巨靈的指令的話，回傳放它的那隻巨靈（同一個世界就算，不管多遠：瞬移過去的也是牠打的）；
     * 不是巨靈的指令、或巨靈沒叫出來回傳 null（{@link DjinnCommands}）。
     */
    public LivingEntity djinnHitter(final Player owner, final tw.exilecore.stats.SkillProfile profile) {
        final String djinn = profile == null ? null : DjinnCommands.djinnFor(profile.id());
        if (djinn == null) {
            return null;
        }
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && minion.skillId().equals(djinn) && !minion.decoy && minion.mob.isValid()
                    && minion.mob.getWorld().equals(owner.getWorld())) {
                return minion.mob;
            }
        }
        return null;
    }

    private Minion commandOnlyMinion(final Player owner, final String skillId) {
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && minion.skillId().equals(skillId) && !minion.decoy && minion.mob.isValid()
                    && minion.mob.getWorld().equals(owner.getWorld())
                    && minion.mob.getLocation().distanceSquared(owner.getLocation()) <= COMMAND_RANGE * COMMAND_RANGE) {
                return minion;
            }
        }
        return null;
    }

    /**
     * 2026-10-27 照 PoE2 0.5（Supporting Fire 的 Command: Death From Above）：砲手朝目標射一發信號彈，之後每 0.75 秒一輪箭雨，共 6 輪，
     * 每一輪打 14 × 14 公尺（半徑 7.9 公尺的圓）內的敵人 763% 傷害。砲手每 1 點主人的力量增加 1% 傷害、每 3 點敏捷增加 1% 範圍。
     */
    private void deathFromAbove(final Minion artillery, final Player owner, final Location point) {
        final Location at = point.clone();
        final StatSheet ownerSheet = plugin.players().sheet(owner);
        final double str = Math.max(0, ownerSheet.get(tw.exilecore.stats.StatKeys.STR));
        final double dex = Math.max(0, ownerSheet.get(tw.exilecore.stats.StatKeys.DEX));
        final double radius = MinionAbilities.VOLLEY_RADIUS * areaScale(artillery) * Math.sqrt(1 + dex / MinionAbilities.DEX_PER_AREA / 100.0);
        final SkillProfile volley = attackProfile(artillery, "death_from_above", MinionAbilities.VOLLEY_DAMAGE,
                Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.PROJECTILE), null,
                List.of(Modifier.increased(tw.exilecore.stats.StatKeys.DAMAGE, str / MinionAbilities.STR_PER_DAMAGE / 100.0, "掩護火力：力量")));
        final Attack hit = with(artillery, volley);
        // 2026-10-30 照 PoE2 Death From Above（attaching to an enemy if it lands on one）：信號彈落在敵人身上就黏著牠，之後每一輪箭雨照牠現在的位置；
        // 這次之前箭雨一律落在原地
        LivingEntity attached = null;
        double closest = MinionAbilities.FLARE_ATTACH * MinionAbilities.FLARE_ATTACH;
        for (final LivingEntity near : targets.around(at, MinionAbilities.FLARE_ATTACH, owner)) {
            final double distance = near.getLocation().distanceSquared(at);
            if (distance <= closest) {
                closest = distance;
                attached = near;
            }
        }
        final LivingEntity flare = attached;
        Fx.spawn(at.getWorld(), org.bukkit.Particle.FIREWORK, at.clone().add(0, 0.5, 0), 12, 0.2, 0.6, 0.2, 0.05);
        for (int i = 0; i < MinionAbilities.VOLLEYS; i++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!byEntity.containsKey(artillery.mob.getUniqueId()) || !owner.isOnline() || !owner.getWorld().equals(at.getWorld())) {
                    return;
                }
                if (flare != null && flare.isValid() && !flare.isDead() && flare.getWorld().equals(at.getWorld())) {
                    at.setX(flare.getLocation().getX());
                    at.setY(flare.getLocation().getY());
                    at.setZ(flare.getLocation().getZ());
                }
                tw.exilecore.skill.Fx.disk(at.getWorld(), org.bukkit.Particle.CRIT, at.clone().add(0, 4, 0), 40, radius, 0.5, 0.0);
                at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_ARROW_HIT, 0.6f, 0.8f);
                for (final LivingEntity near : targets.around(at, radius, owner)) {
                    strike(artillery, near, hit);
                }
            }, MinionAbilities.VOLLEY_DELAY + i * MinionAbilities.VOLLEY_TICKS);
        }
    }

    private void gasArrow(final Minion sniper, final Player owner, final Location point, final long now) {
        final Location at = point.clone();
        Fx.spawn(at.getWorld(), org.bukkit.Particle.CRIT, at.clone().add(0, 1.5, 0), 6, 0.1, 0.6, 0.1, 0);
        final double area = areaScale(sniper);
        final Attack impact = with(sniper, attackProfile(sniper, "gas_arrow", MinionAbilities.GAS_IMPACT, Set.of(tw.exilecore.stats.Tags.AREA), null, List.of()));
        for (final LivingEntity near : targets.around(at, MinionAbilities.GAS_RADIUS * area, owner)) {
            strike(sniper, near, impact);
        }
        // 毒雲：像 220% 傷害的擊中一樣中毒（照召喚物這一下的物理傷害，吃傷害加成）；被引爆時 630%、全部轉火焰
        final SkillProfile poison = attackProfile(sniper, "gas_cloud", MinionAbilities.GAS_POISON, Set.of(tw.exilecore.stats.Tags.AREA), null, List.of());
        final StatSheet effective = poison.effective(sniper.sheet);
        final Set<String> context = poison.contextFor(tw.exilecore.stats.DamageType.PHYSICAL);
        final double weapon = (effective.flat(tw.exilecore.stats.StatKeys.WEAPON_MIN, context) + effective.flat(tw.exilecore.stats.StatKeys.WEAPON_MAX, context)) / 2;
        final double base = weapon * Math.max(0, 1 + effective.increased(tw.exilecore.stats.StatKeys.DAMAGE, context))
                * effective.more(tw.exilecore.stats.StatKeys.DAMAGE, context);
        final SkillProfile explosion = attackProfile(sniper, "gas_explosion", MinionAbilities.GAS_EXPLOSION,
                Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.FIRE), Map.of(tw.exilecore.stats.DamageType.FIRE, 1.0), List.of());
        clouds.add(new Cloud(sniper.owner, sniper, at, now, MinionAbilities.GAS_RADIUS * area, base, sniper.sheet, explosion));
    }

    /** 爆裂殉身：每一隻焰術士各引爆目標 3 格內一隻生命低於門檻的自己的召喚物。 */
    private int demise(final List<Minion> arsonists, final Player owner, final Location point, final MinionAbilities.Command command, final long now) {
        int used = 0;
        final Set<Minion> taken = new HashSet<>();
        for (final Minion arsonist : arsonists) {
            final double threshold = MinionAbilities.DEMISE_THRESHOLD[MinionAbilities.row40(arsonist.build.level())] * arsonist.factors[1];
            Minion victim = null;
            for (final Minion other : minions) {
                if (other == arsonist || taken.contains(other) || !other.owner.equals(arsonist.owner) || other.decoy || !other.mob.isValid()
                        || other.mob.isDead() || MinionAbilities.SWARM.equals(other.skillId()) || other.undamageable() // 2026-10-29：幻化武器也不能引爆
                        || !other.mob.getWorld().equals(point.getWorld())
                        || other.mob.getLocation().distanceSquared(point) > MinionAbilities.DEMISE_SEARCH * MinionAbilities.DEMISE_SEARCH
                        || other.mob.getHealth() / other.healthScale >= threshold) {
                    continue;
                }
                if (victim == null || other.mob.getLocation().distanceSquared(point) < victim.mob.getLocation().distanceSquared(point)) {
                    victim = other;
                }
            }
            if (victim == null) {
                continue;
            }
            taken.add(victim);
            obeyed(arsonist);
            arsonist.startCooldown(COMMAND, now, command.cooldown());
            arsonist.mob.swingMainHand();
            final Location at = victim.mob.getLocation();
            final double extra = MinionSheets.life(victim.effective) * MinionAbilities.DEMISE_LIFE_PERCENT / 100.0 / MinionAbilities.DEMISE_DAMAGE;
            final Set<String> fire = Set.of(tw.exilecore.stats.Tags.FIRE);
            final Attack blast = with(arsonist, attackProfile(arsonist, "explosive_demise", MinionAbilities.DEMISE_DAMAGE,
                    Set.of(tw.exilecore.stats.Tags.AREA, tw.exilecore.stats.Tags.FIRE), Map.of(tw.exilecore.stats.DamageType.FIRE, 1.0),
                    List.of(new Modifier(tw.exilecore.stats.StatKeys.DAMAGE_ADDED_MIN, tw.exilecore.stats.ModType.FLAT, extra, fire, "爆裂殉身"),
                            new Modifier(tw.exilecore.stats.StatKeys.DAMAGE_ADDED_MAX, tw.exilecore.stats.ModType.FLAT, extra, fire, "爆裂殉身"))));
            final double radius = MinionAbilities.DEMISE_RADIUS * areaScale(arsonist);
            victim.mob.setHealth(0); // 被引爆的召喚物死掉（會復活的照常復活）
            Fx.spawn(at.getWorld(), org.bukkit.Particle.EXPLOSION_EMITTER, at.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            tw.exilecore.skill.Fx.disk(at.getWorld(), org.bukkit.Particle.FLAME, at.clone().add(0, 0.5, 0), 30, radius, 0.4, 0.08);
            for (final LivingEntity near : targets.around(at, radius, owner)) {
                strike(arsonist, near, blast);
            }
            detonate(arsonist.owner, at, radius, arsonist);
            used++;
        }
        return used;
    }

    /** 死亡風暴：施放 2 秒後，目標區域裡每一具倒下的骷髏、屍體落雷（2 格）並留下感電地面。 */
    private void deathStorm(final Minion mage, final Player owner, final Location point) {
        final long delay = castTicks(mage, MinionAbilities.STORM_CAST);
        Fx.spawn(point.getWorld(), org.bukkit.Particle.ELECTRIC_SPARK, point.clone().add(0, 0.3, 0), 20, 2, 0.1, 2, 0);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!byEntity.containsKey(mage.mob.getUniqueId()) || !owner.isOnline() || !owner.getWorld().equals(point.getWorld())) {
                return;
            }
            final long now = now();
            final double area = MinionAbilities.STORM_AREA * areaScale(mage);
            final List<Location> strikes = new ArrayList<>();
            fallen.removeIf(down -> down.until() <= now);
            for (final Fallen down : fallen) {
                if (down.owner().equals(mage.owner) && down.at().getWorld().equals(point.getWorld()) && down.at().distanceSquared(point) <= area * area) {
                    strikes.add(down.at());
                }
            }
            if (plugin.combat() != null) {
                for (final tw.exilecore.combat.Corpses.Corpse corpse : plugin.combat().corpses().near(point.getWorld().getUID(), point.getX(), point.getZ(),
                        area, now)) {
                    strikes.add(new Location(point.getWorld(), corpse.x(), corpse.y(), corpse.z()));
                }
            }
            final Attack bolt = with(mage, spellProfile(mage, "death_storm", tw.exilecore.stats.DamageType.LIGHTNING,
                    MinionAbilities.range(MinionAbilities.STORM_LIGHTNING, mage.build.level()), MinionAbilities.STORM_CRIT,
                    Set.of(tw.exilecore.stats.Tags.AREA), List.of()));
            final double radius = MinionAbilities.STORM_RADIUS * areaScale(mage);
            final SkillDefinition ground = config.skill(SHOCKED_GROUND);
            for (final Location at : strikes) {
                Fx.spawn(at.getWorld(), org.bukkit.Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), 30, 0.2, 2.0, 0.2, 0.1);
                at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 1.4f);
                for (final LivingEntity near : targets.around(at, radius, owner)) {
                    strike(mage, near, bolt);
                }
                if (ground != null && plugin.skills() != null) {
                    final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
                    final SkillBuild build = SkillBuild.of(config, ground, mage.build.level(), state.level(), false, List.of(), state.weaponTags(),
                            plugin.players().sheet(owner));
                    plugin.skills().procCast(Origin.at(owner, at.clone().add(0, 0.2, 0), SkillTargets.facing(owner)), build, null);
                }
            }
        }, delay);
    }

    /** 冰甲：施放 1 秒後，目標 4 格內自己的召喚物得到 6 秒的冰甲。 */
    private void iceArmour(final Minion mage, final Player owner, final Location point) {
        final long delay = castTicks(mage, 1.0);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!byEntity.containsKey(mage.mob.getUniqueId()) || !owner.isOnline()) {
                return;
            }
            final long now = now();
            final double radius = MinionAbilities.ARMOUR_RADIUS * areaScale(mage);
            final double[] cold = MinionAbilities.range(MinionAbilities.ARMOUR_COLD, mage.build.level());
            for (final Minion ally : List.copyOf(minions)) {
                if (!ally.owner.equals(mage.owner) || ally.decoy || !ally.mob.isValid() || !ally.mob.getWorld().equals(point.getWorld())
                        || ally.mob.getLocation().distanceSquared(point) > radius * radius) {
                    continue;
                }
                ally.iceArmourUntil = now + Math.round(MinionAbilities.ARMOUR_SECONDS * durationScale(mage) * 20); // 2026-10-29：冰甲吃持續時間
                ally.iceArmourCold = new double[] {cold[0] * mage.factors[0], cold[1] * mage.factors[0]};
                rebuild(ally, plugin.players().sheet(owner), false);
                Fx.spawn(ally.mob.getWorld(), org.bukkit.Particle.SNOWFLAKE, ally.mob.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
            }
        }, delay);
    }

    /** 激怒：全部的狂卒一直激怒（擊中獲得怒氣、慢慢失去生命）。 */
    private int enrage(final List<Minion> reavers, final long now, final MinionAbilities.Command command) {
        for (final Minion reaver : reavers) {
            obeyed(reaver);
            reaver.startCooldown(COMMAND, now, command.cooldown());
            reaver.enragedAt = now;
            reaver.mob.swingMainHand();
            Fx.spawn(reaver.mob.getWorld(), org.bukkit.Particle.ANGRY_VILLAGER, reaver.mob.getLocation().add(0, 1.6, 0), 3, 0.3, 0.2, 0.3, 0);
        }
        return reavers.size();
    }

    // ---- 附魂術 ----

    /** 附身的那種怪（角色進度；沒有或不能用回傳 null = 照技能寫的殭屍）。 */
    String boundSpectre(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        return state == null ? null : boundSpectre(state);
    }

    static String boundSpectre(final tw.exilecore.character.PlayerState state) {
        final String kind = state.progress().get(SPECTRE_KEY);
        return kind != null && SummonSpec.SPECTRES.contains(kind) ? kind : null;
    }

    /** 附身的那種怪的強度 P（monsters.yml 的生命倍率；沒附身 = 1）。 */
    static double spectrePower(final tw.exilecore.character.PlayerState state, final MonsterConfig monsters) {
        final String kind = boundSpectre(state);
        return kind == null || monsters == null ? 1.0 : Math.max(0.05, monsters.type(kind).lifeMultiplier());
    }

    /** 附身那種怪的名字（monsters.yml 沒寫名字的用代號；沒附身是殭屍）。 */
    static String spectreName(final MonsterConfig monsters, final String kind) {
        if (kind == null) {
            return "殭屍";
        }
        final String name = monsters == null ? null : monsters.type(kind).name();
        return name == null || name.isBlank() ? kind : name;
    }

    private SummonSpec spectreSpec(final SummonSpec spec, final String kind) {
        final MonsterConfig.TypeSettings type = plugin.monsters().config().type(kind);
        return type == null ? spec : spec.withMultipliers(spec.life() * type.lifeMultiplier(), spec.damage() * type.damageMultiplier());
    }

    /** 按附魂術的鍵：附身附近一具屍體（之後一直叫那種怪，每隻精魂照牠的強度）。 */
    String bindSpectre(final Player player, final SkillBuild build) {
        if (ownerInTown(player)) {
            return "<red>營地裡不能附身";
        }
        final SummonSpec spec = build.definition().summon();
        final Location at = player.getLocation();
        Corpses.Corpse pick = null;
        if (plugin.combat() != null && spec != null) {
            double best = Double.MAX_VALUE;
            for (final Corpses.Corpse corpse : plugin.combat().corpses().near(at.getWorld().getUID(), at.getX(), at.getZ(), spec.search(), now())) {
                final double distance = Math.pow(corpse.x() - at.getX(), 2) + Math.pow(corpse.z() - at.getZ(), 2);
                if (corpse.type() != null && SummonSpec.SPECTRES.contains(corpse.type()) && distance < best) {
                    best = distance;
                    pick = corpse;
                }
            }
        }
        final MonsterConfig monsters = plugin.monsters().config();
        if (pick == null) {
            final String now = boundSpectre(player);
            return "<gray>附近沒有能附身的屍體（現在附身的是" + tw.exilecore.item.ItemRenderer.escape(spectreName(monsters, now)) + "）";
        }
        if (!tw.exilecore.stats.TreeEffectKeys.corpseSaved(plugin.players().sheet(player), plugin.random())) { // 2026-10-26 照 PoE2 天賦樹：x% 機率不毀掉屍體
            plugin.combat().corpses().remove(pick);
        }
        if (plugin.characters() != null) {
            plugin.characters().setProgress(player, SPECTRE_KEY, pick.type());
        }
        trim(player.getUniqueId(), MinionAbilities.SPECTRE, 0);
        if (auras != null) {
            auras.resummonNow(player.getUniqueId(), MinionAbilities.SPECTRE);
        }
        plugin.players().markDirty(player);
        Fx.spawn(player.getWorld(), org.bukkit.Particle.SOUL, new Location(player.getWorld(), pick.x(), pick.y() + 0.5, pick.z()), 20, 0.4, 0.5, 0.4, 0.03);
        final double power = Math.max(0.05, monsters.type(pick.type()).lifeMultiplier());
        return "<#c7a6ff>附身：" + tw.exilecore.item.ItemRenderer.escape(spectreName(monsters, pick.type()))
                + "（每隻保留 " + MinionAbilities.spectreSpirit(power) + " 精魂）";
    }

    // ---- 馴獸（2026-10-30 照 PoE2 Tame Beast）----

    /** 收服的野獸（角色進度）：原版生物代號、保留的怪物詞綴（逗號分隔）。 */
    static final String TAME_KIND_KEY = "tame.kind";
    static final String TAME_MODS_KEY = "tame.mods";
    /** 原作：鬼火持續 8 秒、阻礙 30%，收服的野獸最多保留 4 個怪物詞綴。 */
    static final long TAME_TICKS = 160;
    /** 2026-10-30 照 PoE2 Tame Beast（RePoE base_skill_effect_duration）：鬼火持續時間（毫秒，1～40 級：8 秒 → 20 級 11.8 秒 → 40 級 14.8 秒）。 */
    static final int[] WISP_MS = {8000, 8200, 8400, 8600, 8800, 9000, 9200, 9400, 9600, 9800, 10000, 10200, 10400, 10600, 10800, 11000, 11200,
        11400, 11600, 11800, 12000, 12200, 12400, 12600, 12800, 13000, 13200, 13400, 13600, 13800, 13900, 14000, 14100, 14200, 14300, 14400,
        14500, 14600, 14700, 14800};
    /** FLAT（秒，品質）：鬼火持續時間多幾秒（照 PoE2：每 1% 品質 +0.08 秒）。 */
    static final String WISP_SECONDS = "tame.wisp_seconds";

    /** 鬼火纏住幾 tick：照寶石等級、品質，吃持續時間。這次之前固定 8 秒。 */
    static long wispTicks(final SkillBuild build) {
        if (build == null) {
            return TAME_TICKS;
        }
        final double seconds = WISP_MS[Math.max(1, Math.min(WISP_MS.length, build.level())) - 1] / 1000.0 + build.qualityFlat(WISP_SECONDS);
        return Math.max(20, Math.round(seconds * 20 * build.durationScale()));
    }
    static final double TAME_HINDER = 0.3;
    static final int TAME_MOD_LIMIT = 4;
    static final double TAME_RANGE = 20;

    private record Engulf(LivingEntity target, UUID owner, long until, String kind, List<String> mods) {
    }

    /** 被鬼火纏住的野獸（實體 → 誰放的）。 */
    private final Map<UUID, Engulf> engulfed = new HashMap<>();

    /** 可以收服的：天賦樹「野獸」名單上的原版生物。 */
    static boolean tameable(final org.bukkit.entity.EntityType type) {
        return tw.exilecore.stats.TreeConditions.VS_BEAST.equals(tw.exilecore.combat.TargetConditions.family(type));
    }

    /** 收服的野獸種類（沒有回傳 null = 照技能寫的狼）。 */
    String tamedKind(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        return state == null ? null : tamedKind(state);
    }

    static String tamedKind(final tw.exilecore.character.PlayerState state) {
        final String kind = state.progress().get(TAME_KIND_KEY);
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return tameable(org.bukkit.entity.EntityType.valueOf(kind)) ? kind : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    static List<String> tamedMods(final tw.exilecore.character.PlayerState state) {
        final String mods = state.progress().get(TAME_MODS_KEY);
        if (tamedKind(state) == null || mods == null || mods.isBlank()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final String id : mods.split(",")) {
            if (!id.isBlank() && out.size() < TAME_MOD_LIMIT) {
                out.add(id.trim());
            }
        }
        return out;
    }

    /** 收服的野獸的強度 P（monsters.yml 的生命倍率；沒收服回傳 fallback）。 */
    static double tamedPower(final tw.exilecore.character.PlayerState state, final MonsterConfig monsters, final double fallback) {
        final String kind = tamedKind(state);
        return kind == null || monsters == null ? fallback : Math.max(0.05, monsters.type(kind).lifeMultiplier());
    }

    /** 收服的野獸用那種怪的倍率（生命；傷害跟附魂術一樣再乘隱藏等級補正）。 */
    private SummonSpec tamedSpec(final SummonSpec spec, final String kind) {
        final MonsterConfig.TypeSettings type = plugin.monsters().config().type(kind);
        return type == null ? spec : spec.withMultipliers(type.lifeMultiplier(), type.damageMultiplier() * MinionAbilities.SPECTRE_BEAST_FIXUP);
    }

    /** 收服的野獸保留的怪物詞綴換成修飾器（跟儀式獻祭拿到的一樣算法）。 */
    private List<Modifier> tamedModifiers(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state == null) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        for (final String id : tamedMods(state)) {
            final MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(id);
            if (mod == null) {
                continue;
            }
            final String source = "夥伴詞綴：" + mod.name();
            for (final tw.exilecore.item.AffixMod affixMod : mod.mods()) {
                out.add(affixMod.toModifier(Map.of(), source));
            }
            for (final Map.Entry<tw.exilecore.stats.DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
                out.add(Modifier.flat(tw.exilecore.stats.StatKeys.damageGain(entry.getKey()), entry.getValue() * 100, source));
            }
            if (mod.vanillaSpeedBonus() > 0) {
                out.add(Modifier.increased(tw.exilecore.stats.StatKeys.MOVEMENT_SPEED, mod.vanillaSpeedBonus(), source));
            }
            if (mod.aura() != null) {
                for (final tw.exilecore.item.AffixMod affixMod : mod.aura().stats()) {
                    out.add(affixMod.toModifier(Map.of(), source));
                }
            }
        }
        return List.copyOf(out);
    }

    /** 收服的野獸保留的詞綴名字（說明用）。 */
    List<String> tamedModNames(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final List<String> out = new ArrayList<>();
        if (state != null) {
            for (final String id : tamedMods(state)) {
                final MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(id);
                out.add(mod == null ? id : mod.name());
            }
        }
        return out;
    }

    /**
     * 按狼伴的鍵：用鬼火纏住準心（或面前最近）的稀有野獸 8 秒（阻礙 30%）；這段時間內牠被打倒就收服（之後狼伴叫的是那隻野獸）。
     */
    String startTame(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (ownerInTown(player)) {
            return "<red>營地裡不能馴獸";
        }
        LivingEntity pick = tameTarget(player, preferred) ? preferred : null;
        if (pick == null) {
            double best = Double.MAX_VALUE;
            final Vector facing = SkillTargets.facing(player);
            for (final Entity entity : player.getNearbyEntities(TAME_RANGE, 8, TAME_RANGE)) {
                if (entity instanceof LivingEntity living && tameTarget(player, living)) {
                    final Vector to = living.getLocation().toVector().subtract(player.getLocation().toVector());
                    final double distance = to.length();
                    if (distance > TAME_RANGE) {
                        continue;
                    }
                    // 面前的優先：距離乘上偏離準心的程度
                    final double score = distance * (2 - (distance < 1e-6 ? 1 : to.normalize().dot(facing)));
                    if (score < best) {
                        best = score;
                        pick = living;
                    }
                }
            }
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final String current = state == null ? null : tamedKind(state);
        if (pick == null) {
            return "<gray>附近沒有稀有的野獸可以馴服（現在的夥伴：" + tw.exilecore.item.ItemRenderer.escape(
                    current == null ? "狼" : spectreName(plugin.monsters().config(), current)) + "）";
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(pick).orElse(null);
        final List<String> mods = data == null ? List.of() : data.mods().subList(0, Math.min(TAME_MOD_LIMIT, data.mods().size()));
        final long now = now();
        final long wisp = wispTicks(build);
        engulfed.put(pick.getUniqueId(), new Engulf(pick, player.getUniqueId(), now + wisp, pick.getType().name(), List.copyOf(mods)));
        if (plugin.combat() != null) {
            plugin.combat().debuffs().apply(pick.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("hinder", player.getUniqueId(), now + wisp,
                    0, false, null, List.of(), TAME_HINDER));
        }
        Fx.spawn(pick.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, pick.getLocation().add(0, 1, 0), 30, 0.5, 0.6, 0.5, 0.03);
        return "<#9fe0ff>鬼火纏住了" + tw.exilecore.item.ItemRenderer.escape(spectreName(plugin.monsters().config(), pick.getType().name()))
                + String.format(java.util.Locale.ROOT, "：%.1f 秒內打倒牠就能收服", wisp / 20.0);
    }

    private boolean tameTarget(final Player player, final LivingEntity entity) {
        if (entity == null || entity instanceof Player || !entity.isValid() || entity.isDead() || isMinion(entity) || !tameable(entity.getType())) {
            return false;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(entity).orElse(null);
        return data != null && data.rarity() == tw.exilecore.monster.MonsterRarity.RARE && !data.boss()
                && entity.getWorld() == player.getWorld();
    }

    private void tickTaming(final long now) {
        if (engulfed.isEmpty()) {
            return;
        }
        final java.util.Iterator<Engulf> it = engulfed.values().iterator();
        while (it.hasNext()) {
            final Engulf engulf = it.next();
            final LivingEntity target = engulf.target();
            if (target.isDead()) {
                it.remove();
                final Player owner = plugin.getServer().getPlayer(engulf.owner());
                if (owner != null && owner.isOnline()) {
                    capture(owner, engulf, target.getLocation());
                }
                continue;
            }
            if (!target.isValid() || now > engulf.until()) {
                it.remove();
                continue;
            }
            if (now % 10 == 0) {
                Fx.spawn(target.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, target.getLocation().add(0, 1, 0), 6, 0.4, 0.5, 0.4, 0.01);
            }
        }
    }

    private void capture(final Player owner, final Engulf engulf, final Location at) {
        if (plugin.characters() == null) {
            return;
        }
        plugin.characters().setProgress(owner, TAME_KIND_KEY, engulf.kind());
        plugin.characters().setProgress(owner, TAME_MODS_KEY, String.join(",", engulf.mods()));
        trim(owner.getUniqueId(), MinionAbilities.BEAST, 0);
        if (auras != null) {
            auras.resummonNow(owner.getUniqueId(), MinionAbilities.BEAST);
        }
        plugin.players().markDirty(owner);
        Fx.spawn(at.getWorld(), org.bukkit.Particle.SOUL, at.clone().add(0, 0.8, 0), 30, 0.5, 0.6, 0.5, 0.03);
        final MonsterConfig monsters = plugin.monsters().config();
        final List<String> names = tamedModNames(owner);
        final double power = Math.max(0.05, monsters.type(engulf.kind()).lifeMultiplier());
        plugin.players().get(owner).notice("<#9fe0ff>收服了" + tw.exilecore.item.ItemRenderer.escape(spectreName(monsters, engulf.kind()))
                + (names.isEmpty() ? "" : "（" + tw.exilecore.item.ItemRenderer.escape(String.join("、", names)) + "）")
                + "<gray>：每隻保留最大精魂的 " + ModifierText.number(MinionAbilities.companionSpiritPercent(power)) + "%", now() + 80);
        plugin.players().display(owner, plugin.players().get(owner));
    }

    /** 收服的野獸的招式：那種怪的（monsters.yml types.＊.skills）＋保留的詞綴給的（mods.＊.skills）。 */
    private List<BossSkillUse> tamedSkillUses(final Player player, final String kind) {
        final List<BossSkillUse> out = new ArrayList<>();
        final MonsterConfig monsters = plugin.monsters().config();
        final MonsterConfig.TypeSettings type = monsters.type(kind);
        if (type != null) {
            out.addAll(type.skills());
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state != null) {
            for (final String id : tamedMods(state)) {
                final MonsterConfig.MonsterMod mod = monsters.mod(id);
                if (mod != null) {
                    out.addAll(mod.skills());
                }
            }
        }
        return List.copyOf(out);
    }

    /** 招式的名字（說明用）。 */
    List<String> tamedSkillNames(final Player player) {
        final String kind = tamedKind(player);
        final List<String> out = new ArrayList<>();
        if (kind != null) {
            for (final BossSkillUse use : tamedSkillUses(player, kind)) {
                final BossSkill skill = config.bossSkills().skill(use.id());
                if (skill != null && !out.contains(skill.name())) {
                    out.add(skill.name());
                }
            }
        }
        return out;
    }

    /**
     * 收服的野獸放一個冷卻好了、碰得到目標的招式；回傳幾 tick 後再出手（沒有能放的回傳 0 = 普攻）。
     * 照怪物那邊的招式表（boss_skills.yml）：傷害是牠「普通一下」的幾倍、轉成招式的傷害類型；打的是你的敵人。
     * 前搖照招式的一半（陪你打的不畫紅圈）；召喚、場地機制（光束、擴散環、安全區…）這類只有 Boss 才有意義的照一圈範圍打。
     */
    private long beastSkill(final Minion minion, final LivingEntity target, final Player owner, final long now) {
        for (final BossSkillUse use : minion.tamedSkills) {
            final BossSkill skill = config.bossSkills().skill(use.id());
            if (skill == null || skill.kind() == BossSkill.Kind.SUMMON) {
                continue;
            }
            final String key = "tame:" + use.id();
            if (minion.onCooldown(key, now)) {
                continue;
            }
            final double distance = minion.mob.getLocation().distance(target.getLocation());
            if (skill.range() > 0 && distance > skill.range()) {
                continue;
            }
            minion.startCooldown(key, now, use.cooldown() > 0 ? use.cooldown() : Math.max(2, skill.cooldown()));
            final long windup = Math.max(2, Math.round(skill.telegraph() * 10));
            minion.mob.swingMainHand();
            Fx.spawn(minion.mob.getWorld(), particle(skill.type()), minion.mob.getLocation().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0.02);
            final Location aim = target.getLocation().clone();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (minion.mob.isValid() && !minion.mob.isDead() && owner.isOnline()) {
                    beastCast(minion, skill, use, target.isValid() && !target.isDead() ? target.getLocation() : aim, owner);
                }
            }, windup);
            return windup + minion.attackTicks;
        }
        return 0;
    }

    private static org.bukkit.Particle particle(final tw.exilecore.stats.DamageType type) {
        if (type == null) {
            return org.bukkit.Particle.CRIT;
        }
        return switch (type) {
            case FIRE -> org.bukkit.Particle.FLAME;
            case COLD -> org.bukkit.Particle.SNOWFLAKE;
            case LIGHTNING -> org.bukkit.Particle.ELECTRIC_SPARK;
            case CHAOS -> org.bukkit.Particle.WITCH;
            default -> org.bukkit.Particle.CRIT;
        };
    }

    private void beastCast(final Minion minion, final BossSkill skill, final BossSkillUse use, final Location at, final Player owner) {
        if (minion.profile == null) {
            return;
        }
        final double times = skill.damage() * (use.damage() > 0 ? use.damage() : 1.0);
        SkillProfile profile = musterProfile(minion).scaled(Math.max(0, times), skill.name())
                .derive(minion.profile.id() + ".tame." + skill.id(), Set.of(tw.exilecore.stats.Tags.AREA), Set.of(tw.exilecore.stats.Tags.MELEE));
        if (skill.type() != null && skill.type() != tw.exilecore.stats.DamageType.PHYSICAL) {
            profile = profile.withLocal(List.of(Modifier.flat(tw.exilecore.stats.StatKeys.damageConvert(skill.type()), 100, skill.name())));
        }
        final Attack hit = with(minion, profile);
        final org.bukkit.Particle fx = particle(skill.type());
        final Location from = minion.mob.getLocation();
        final java.util.function.BiConsumer<Location, Double> burst = (center, radius) -> {
            tw.exilecore.skill.Fx.disk(center.getWorld(), fx, center.clone().add(0, 0.3, 0), (int) Math.min(60, 12 * radius), radius, 0.3, 0.02);
            if (times > 0) {
                for (final LivingEntity enemy : targets.around(center, radius, owner)) {
                    strike(minion, enemy, hit);
                }
            }
        };
        final double radius = skill.radius() > 0 ? skill.radius() : 3;
        switch (skill.kind()) {
            case SLAM -> {
                final int count = Math.max(1, skill.count());
                for (int i = 0; i < count; i++) {
                    final Location spot = i == 0 ? at.clone() : at.clone().add((plugin.random().nextDouble() - 0.5) * 2 * Math.max(2, skill.scatter()), 0,
                            (plugin.random().nextDouble() - 0.5) * 2 * Math.max(2, skill.scatter()));
                    final long delay = Math.round(i * Math.max(0.1, skill.stagger()) * 20) + Math.round(skill.delay() * 10);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> burst.accept(spot, radius), Math.max(1, delay));
                }
            }
            case NOVA, RING, PULL, SANCTUARY, BEAM, ORBS, MARK, ECHOES -> burst.accept(from, radius);
            case CONE -> {
                final double length = skill.length() > 0 ? skill.length() : 6;
                final double half = Math.toRadians((skill.angle() > 0 ? skill.angle() : 60) / 2);
                final Vector facing = at.toVector().subtract(from.toVector()).setY(0);
                if (facing.lengthSquared() < 1e-6) {
                    return;
                }
                facing.normalize();
                Fx.spawn(from.getWorld(), fx, from.clone().add(facing.clone().multiply(length / 2)).add(0, 1, 0), 30, length / 4, 0.3, length / 4, 0.02);
                for (final LivingEntity enemy : targets.around(from, length, owner)) {
                    final Vector to = enemy.getLocation().toVector().subtract(from.toVector()).setY(0);
                    if (to.lengthSquared() < 1e-6 || to.normalize().angle(facing) <= half) {
                        strike(minion, enemy, hit);
                    }
                }
            }
            case VOLLEY -> {
                // 每一發打一個敵人：準心那隻先，其他照遠近
                final int count = Math.max(1, skill.count());
                final double reach = skill.reach() > 0 ? skill.reach() : 15;
                final List<LivingEntity> enemies = new ArrayList<>(targets.around(from, reach, owner));
                enemies.sort(java.util.Comparator.comparingDouble(enemy -> enemy.getLocation().distanceSquared(at)));
                for (int i = 0; i < Math.min(count, Math.max(1, enemies.size())) && !enemies.isEmpty(); i++) {
                    final LivingEntity enemy = enemies.get(i % enemies.size());
                    line(minion.mob, enemy);
                    strike(minion, enemy, hit);
                }
            }
            case BLINK -> {
                final Vector back = from.toVector().subtract(at.toVector()).setY(0);
                final Location land = at.clone().add(back.lengthSquared() < 1e-6 ? new Vector() : back.normalize().multiply(1.2));
                land.setDirection(at.toVector().subtract(land.toVector()));
                minion.mob.teleport(land);
                Fx.spawn(land.getWorld(), org.bukkit.Particle.PORTAL, land.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.2);
                if (times > 0) {
                    burst.accept(land, skill.radius() > 0 ? skill.radius() : 2.5);
                }
            }
            case CHARGE -> {
                final Vector path = at.toVector().subtract(from.toVector()).setY(0);
                final double reach = Math.min(path.length(), skill.reach() > 0 ? skill.reach() : 10);
                if (reach < 0.5) {
                    burst.accept(from, 2.0);
                    return;
                }
                path.normalize();
                final double width = skill.width() > 0 ? skill.width() : 1.5;
                final java.util.Set<LivingEntity> hitOnce = new java.util.HashSet<>();
                for (double d = 0; d <= reach; d += 1) {
                    final Location step = from.clone().add(path.clone().multiply(d));
                    for (final LivingEntity enemy : targets.around(step, width, owner)) {
                        if (hitOnce.add(enemy)) {
                            strike(minion, enemy, hit);
                        }
                    }
                }
                final Location end = from.clone().add(path.clone().multiply(Math.max(0, reach - 1)));
                end.setDirection(path);
                minion.mob.teleport(end);
                Fx.spawn(from.getWorld(), org.bukkit.Particle.CLOUD, end.clone().add(0, 0.5, 0), 12, 0.4, 0.2, 0.4, 0.02);
            }
            case CURSE -> {
                tw.exilecore.skill.Fx.disk(at.getWorld(), fx, at.clone().add(0, 1, 0), 30, radius, 0.5, 0.02);
                if (plugin.combat() != null && skill.taken() > 0) {
                    final long until = now() + Math.round(Math.max(1, skill.duration()) * 20);
                    for (final LivingEntity enemy : targets.around(at, radius, owner)) {
                        plugin.combat().debuffs().apply(enemy.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("tame." + skill.id(),
                                owner.getUniqueId(), until, skill.taken() / 100.0, false));
                    }
                }
            }
            case ZONE -> {
                final int count = Math.max(1, skill.count());
                final long life = Math.round(Math.max(1, skill.duration()) * 20);
                final long every = Math.max(5, Math.round((skill.interval() > 0 ? skill.interval() : 1) * 20));
                for (int i = 0; i < count; i++) {
                    final Location spot = i == 0 ? at.clone() : at.clone().add((plugin.random().nextDouble() - 0.5) * 2 * Math.max(2, skill.scatter()), 0,
                            (plugin.random().nextDouble() - 0.5) * 2 * Math.max(2, skill.scatter()));
                    for (long t = 0; t < life; t += every) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            if (minion.mob.isValid() && owner.isOnline()) {
                                burst.accept(spot, radius);
                            }
                        }, Math.max(1, t));
                    }
                }
            }
            default -> burst.accept(at, radius);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSlimeSplit(final org.bukkit.event.entity.SlimeSplitEvent event) {
        if (isMinion(event.getEntity())) {
            event.setCancelled(true); // 2026-10-30 馴獸：收服的史萊姆死了不分裂
        }
    }

    // ---- 收掉 ----

    private enum Gone { EXPIRED, REPLACED, DIED, QUIET }

    /** 2026-10-27：主人＋技能 → 被打死還沒算過的隻數（{@link #takeDeaths}）。 */
    private final Map<String, Integer> deaths = new HashMap<>();

    private void remove(final Minion minion, final Gone why) {
        if (ANCESTRAL.equals(minion.skillId()) && now() - minion.bornTick < 100 && minions.contains(minion)) {
            // 2026-10-30 除錯：先祖之靈出來 5 秒內就沒了，記下原因（燃僕讓先祖之靈馬上死掉的問題）
            final org.bukkit.event.entity.EntityDamageEvent last = minion.mob.getLastDamageCause();
            plugin.getSLF4JLogger().info("[先祖之靈] {} 出來 {} tick 就沒了：{}；最後傷害 {}（{}）；血量 {}/{}、換算 {}、生命 {}、輔助 {}",
                    minion.form, now() - minion.bornTick, why, last == null ? "無" : last.getCause(),
                    last instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity ? byEntity.getDamager().getType() : "-",
                    String.format(java.util.Locale.ROOT, "%.1f", minion.mob.getHealth()),
                    minion.mob.getAttribute(Attribute.MAX_HEALTH) == null ? "?" : String.format(java.util.Locale.ROOT, "%.1f",
                            minion.mob.getAttribute(Attribute.MAX_HEALTH).getValue()),
                    String.format(java.util.Locale.ROOT, "%.4f", minion.healthScale),
                    minion.effective == null ? "?" : String.format(java.util.Locale.ROOT, "%.0f", MinionSheets.life(minion.effective)),
                    minion.build.supports().stream().map(SupportDefinition::id).toList(),
                    new Throwable("呼叫位置"));
        }
        if (why == Gone.DIED && !minion.decoy && minions.contains(minion)) {
            deaths.merge(minion.owner + ":" + minion.skillId(), 1, Integer::sum);
        }
        minions.remove(minion);
        byEntity.remove(minion.mob.getUniqueId());
        MinionCollision.remove(plugin, minion.mob.getUniqueId());
        final Location at = minion.mob.getLocation();
        if (minion.mob.isValid() && why != Gone.DIED) {
            minion.mob.remove();
        }
        if (why != Gone.QUIET) {
            visuals.minionGone(minion.build.definition().visual(), at, minion.spec, why == Gone.DIED);
        }
        if (!minion.spec.buff().isEmpty()) {
            final Player owner = plugin.getServer().getPlayer(minion.owner);
            if (owner != null) {
                plugin.players().markDirty(owner);
            }
        }
        if (why != Gone.QUIET) {
            refreshHud(minion.owner);
        }
    }

    /** 某個玩家的全部收掉（登出、換角色）。 */
    void forget(final UUID owner) {
        for (final Minion minion : new ArrayList<>(minions)) {
            if (minion.owner.equals(owner)) {
                remove(minion, Gone.QUIET);
            }
        }
        focus.remove(owner);
        projectiles.forget(owner); // 2026-10-29
        deaths.keySet().removeIf(key -> key.startsWith(owner + ":")); // 2026-10-27
        clouds.removeIf(cloud -> cloud.owner.equals(owner)); // 2026-10-26
        fallen.removeIf(down -> down.owner().equals(owner));
    }

    /** 全部收掉（插件停用）；也清掉上次沒收乾淨的。 */
    void clear() {
        for (final Minion minion : new ArrayList<>(minions)) {
            remove(minion, Gone.QUIET);
        }
        clouds.clear(); // 2026-10-26
        fallen.clear();
        projectiles.clear(); // 2026-10-29
        sweep();
    }

    /** 清掉世界裡帶著我們標記、但不在名單上的生物（上次當機留下的）。 */
    void sweep() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final LivingEntity entity : world.getLivingEntities()) {
                if (!(entity instanceof Player) && entity.getPersistentDataContainer().has(key) && !byEntity.containsKey(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
    }

    // ---- 給戰鬥模組（MinionHooks）----

    @Override
    public boolean isMinion(final Entity entity) {
        return entity != null && !(entity instanceof Player)
                && (byEntity.containsKey(entity.getUniqueId()) || entity.getPersistentDataContainer().has(key));
    }

    @Override
    public Attack attack(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion == null) {
            return null;
        }
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null && minion.override != null) {
            return minion.override; // 2026-10-26：召喚物自己的技能（碎骨、電弧、冰箭…）這一下的屬性表與技能描述
        }
        return owner == null ? null : new Attack(owner, minion.sheet, commanded(minion, musterProfile(minion))); // 2026-10-26：群鋒；2026-10-27：號令
    }

    @Override
    public double energyShield(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? 0 : Math.max(0, minion.es);
    }

    @Override
    public void damaged(final LivingEntity entity, final double esDamage, final LivingEntity attacker) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion == null) {
            return;
        }
        final long now = now();
        minion.lastHurt = now;
        if (esDamage > 0) {
            minion.es = Math.max(0, minion.es - esDamage);
        }
        if (minion.iceArmourUntil > now && attacker != null && !(attacker instanceof Player) && !isMinion(attacker)) {
            // 冰甲：打牠的敵人受到冰冷傷害（下一 tick 才打，不在這一下的傷害結算裡面再開一次結算）
            plugin.getServer().getScheduler().runTask(plugin, () -> retaliate(minion, attacker));
        }
    }

    /**
     * 2026-10-28 照 PoE2 Bone Offering 的品質（Shielded Minions take x% less Damage for 1 second after Shield is lost）：
     * 骸骨祭給的護盾吸收「第一下超過最大生命 25% 的擊中」（我們的骸骨祭用物理減傷代替護盾，這裡只算護盾什麼時候沒掉）；
     * 護盾沒掉之後 1 秒受到的傷害更少 x%。
     */
    @Override
    public void hitTaken(final LivingEntity entity, final double total) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        final long now = now();
        if (minion == null || minion.offeringUntil <= now || minion.shieldLostAt >= 0 || minion.effective == null) {
            return;
        }
        final double less = shieldLostLess(minion.offering);
        if (less <= 0 || total <= MinionSheets.life(minion.effective) * SHIELD_HIT_FRACTION) {
            return;
        }
        minion.shieldLostAt = now;
        minion.shieldLessUntil = now + SHIELD_LOST_TICKS;
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null) {
            rebuild(minion, plugin.players().sheet(owner), false);
        }
    }

    /**
     * 2026-10-30 照 PoE2 Bone Offering（bone_offering_hit_threshold_%_of_maximum_life 25）：有骸骨祭護盾的召喚物（含骨柱自己）
     * 受到超過最大生命 25% 的擊中時，護盾把這一下整個吸掉然後爆掉（這一輪祭品不再有護盾，也不再有減傷）；回傳有沒有吸掉。
     * 這次之前沒有吸收，只用物理傷害減免代替。
     */
    @Override
    public boolean shieldAbsorbs(final LivingEntity entity, final double total) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        final long now = now();
        if (minion == null || minion.shieldLostAt >= 0 || minion.effective == null) {
            return false;
        }
        final boolean shielded = minion.offeringUntil > now && hasBoneShield(minion.offering) || minion.spike && hasBoneShield(minion.empowered);
        if (!shielded || total <= MinionSheets.life(minion.effective) * SHIELD_HIT_FRACTION) {
            return false;
        }
        minion.shieldLostAt = now;
        final List<Modifier> source = minion.spike ? minion.empowered : minion.offering;
        if (shieldLostLess(source) > 0) {
            minion.shieldLessUntil = now + SHIELD_LOST_TICKS; // 品質：護盾消失後 1 秒更少 x% 傷害
        }
        // 護盾爆掉：拿掉護盾的減傷與記號（品質「護盾消失後更少傷害」那條留著，rebuild 照 shieldLessUntil 算）
        if (minion.spike) {
            minion.empowered = withoutShield(minion.empowered);
        } else {
            minion.offering = withoutShield(minion.offering);
        }
        final Location at = entity.getLocation().add(0, 1, 0);
        entity.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, at, 30, 0.4, 0.5, 0.4, 0.1, org.bukkit.Material.BONE_BLOCK.createBlockData());
        entity.getWorld().playSound(at, org.bukkit.Sound.ENTITY_SKELETON_HURT, 0.8f, 0.6f);
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null) {
            rebuild(minion, plugin.players().sheet(owner), false);
        }
        return true;
    }

    /** 骸骨祭的護盾（受到的擊中傷害減少、護盾記號）拿掉之後的修飾器。 */
    private static List<Modifier> withoutShield(final List<Modifier> modifiers) {
        if (!hasBoneShield(modifiers)) {
            return List.copyOf(modifiers);
        }
        final List<Modifier> kept = new ArrayList<>();
        for (final Modifier modifier : modifiers) {
            if (!modifier.stat().startsWith(tw.exilecore.stats.StatKeys.DEBUFF_TAKEN)
                    && !modifier.stat().equals(tw.exilecore.stats.TreeMechanicKeys.OFFERING_BONE_SHIELD)) {
                kept.add(modifier);
            }
        }
        return List.copyOf(kept);
    }

    private static boolean hasBoneShield(final List<Modifier> modifiers) {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(tw.exilecore.stats.TreeMechanicKeys.OFFERING_BONE_SHIELD) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 骸骨祭的護盾吸收超過最大生命幾成的擊中（PoE2 25%）、消失之後「更少傷害」撐幾 tick（1 秒）。 */
    static final double SHIELD_HIT_FRACTION = 0.25;
    static final long SHIELD_LOST_TICKS = 20;

    /** 祭品的增益裡帶的「護盾消失後更少 x%」（骸骨祭的品質）。 */
    static double shieldLostLess(final List<Modifier> offering) {
        double total = 0;
        for (final Modifier modifier : offering) {
            if (modifier.stat().equals(tw.exilecore.stats.GemQualityKeys.SHIELD_LOST_LESS)) {
                total += modifier.value();
            }
        }
        return Math.min(90, Math.max(0, total));
    }

    @Override
    public Player owner(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? null : plugin.getServer().getPlayer(minion.owner);
    }

    @Override
    public StatSheet sheet(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? null : minion.effective;
    }

    /** 2026-10-26：這隻召喚物是哪個技能叫的（不是召喚物回傳 null）。 */
    String skillOf(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? null : minion.skillId();
    }

    @Override
    public double healthScale(final LivingEntity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        return minion == null ? 1.0 : minion.healthScale;
    }

    /** owner 的召喚物（殘影不算）每隻回復 amount（2026-10：藥劑的「召喚物也回復」詞綴）。 */
    /** 2026-10-30：這隻召喚物的技能有沒有掛忠僕。 */
    private static boolean loyal(final Minion minion) {
        for (final SupportDefinition support : minion.build.supports()) {
            if (support.flags(tw.exilecore.stats.SupportExtraKeys.LOYALTY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompanion(final Minion minion) {
        return !minion.decoy && minion.build.definition().tags().contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION);
    }

    /** 2026-10-26（天賦樹）：這隻是不是夥伴；是的話回傳主人，不是回傳 null。 */
    UUID companionOwner(final UUID entity) {
        final Minion minion = byEntity.get(entity);
        return minion != null && isCompanion(minion) ? minion.owner : null;
    }

    /** 2026-10-26（天賦樹）：給一隻召喚物一段時間的增益（夥伴擊殺時疾速）。 */
    void buffMinion(final UUID entity, final List<Modifier> mods, final int ticks) {
        final Minion minion = byEntity.get(entity);
        final org.bukkit.entity.Player owner = minion == null ? null : plugin.getServer().getPlayer(minion.owner);
        if (owner == null || !minion.mob.isValid()) {
            return;
        }
        minion.treeBuff = List.copyOf(mods);
        minion.treeBuffUntil = now() + ticks;
        rebuild(minion, plugin.players().sheet(owner), false);
    }

    /** 2026-10-26（天賦樹）：主人的夥伴回復 amount 生命（我們的單位；藥劑、偷取也給夥伴）。 */
    void healCompanions(final Player owner, final double amount) {
        if (amount <= 0) {
            return;
        }
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && isCompanion(minion) && minion.mob.isValid()) {
                heal(minion.mob, amount);
            }
        }
    }

    /**
     * 2026-10-26（天賦樹「擊中傷害的 x% 先由你的夥伴的生命承受」）：從主人的一隻夥伴身上扣最多 amount 生命（我們的單位，夥伴至少留 1 點），
     * 回傳真的扣了多少（沒有夥伴是 0）。
     */
    double absorbForOwner(final UUID owner, final double amount) {
        if (amount <= 0) {
            return 0;
        }
        // 2026-10-30 照 PoE2 Loyalty「Supported Companion's Life」：掛了忠僕的夥伴先承受；這次之前照召喚順序，可能是沒掛忠僕的那隻
        final List<Minion> order = new ArrayList<>(minions);
        order.sort(java.util.Comparator.comparingInt(minion -> loyal(minion) ? 0 : 1));
        for (final Minion minion : order) {
            if (!minion.owner.equals(owner) || !isCompanion(minion) || !minion.mob.isValid() || minion.mob.isDead()
                    || minion.undamageable()) { // 2026-10-29：不會受傷的召喚物不替你承受
                continue;
            }
            final double life = minion.mob.getHealth() / Math.max(1e-6, minion.healthScale);
            final double taken = Math.min(amount, Math.max(0, life - 1));
            if (taken <= 0) {
                continue;
            }
            minion.mob.setHealth(Math.max(1e-3, minion.mob.getHealth() - taken * minion.healthScale));
            minion.lastHurt = now();
            return taken;
        }
        return 0;
    }

    public void healOwned(final Player owner, final double amount) {
        if (amount <= 0) {
            return;
        }
        for (final Minion minion : minions) {
            if (minion.owner.equals(owner.getUniqueId()) && !minion.decoy && minion.mob.isValid()) {
                heal(minion.mob, amount);
            }
        }
    }

    @Override
    public void leech(final LivingEntity entity, final double amount) {
        // 2026-10-29 照 PoE2：召喚物擊中偷到的生命存進牠自己的偷取（一份最多牠最大生命的 10%），poe2Tick 慢慢回；這次之前直接回
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion != null && amount > 0 && minion.effective != null) {
            MinionTypes.addLeech(minion.leech, amount, MinionSheets.life(minion.effective));
        }
    }

    @Override
    public void heal(final LivingEntity entity, final double amount) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        if (minion == null || amount <= 0) {
            return;
        }
        final AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            entity.setHealth(Math.min(health.getValue(), entity.getHealth() + amount * minion.healthScale));
        }
    }

    // ---- 事件 ----

    /** 主人打中的怪：召喚物接下來優先打它。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (event.attacker() instanceof Player player && event.result().hit() && !(event.defender() instanceof Player)
                && !minions.isEmpty()) {
            focus.put(player.getUniqueId(), new Focus(event.defender().getUniqueId(), now()));
        }
    }

    /**
     * 2026-10-24（霧鴉，PoE2 Mist Raven）：這隻召喚物撲殺一個敵人時主人拿幾顆戰星（叫出牠的技能的 charge.minion_cull.代號）。
     * 不是召喚物、沒有這條屬性回傳空的。
     */
    public Map<String, Integer> cullCharges(final org.bukkit.entity.Entity entity) {
        final Minion minion = byEntity.get(entity.getUniqueId());
        final Player owner = minion == null ? null : plugin.getServer().getPlayer(minion.owner);
        if (owner == null || plugin.combat() == null || plugin.combat().charges() == null) {
            return Map.of();
        }
        final tw.exilecore.stats.StatSheet effective = minion.build.profile().effective(plugin.players().sheet(owner));
        final Map<String, Integer> gain = new java.util.LinkedHashMap<>();
        for (final tw.exilecore.charge.ChargeType type : plugin.combat().charges().config().types()) {
            final int count = (int) Math.round(effective.flat(tw.exilecore.charge.ChargeService.MINION_CULL + type.id(), minion.build.profile().tags()));
            if (count > 0) {
                gain.put(type.id(), count);
            }
        }
        return gain;
    }

    /** 召喚物死了：不掉東西、不給經驗，從名單拿掉。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        final Minion minion = byEntity.get(event.getEntity().getUniqueId());
        if (minion == null) {
            if (isMinion(event.getEntity())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
            }
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (!minion.decoy && SKELETONS.contains(minion.skillId())) {
            // 2026-10-26：倒下的骷髏（12 秒後才回來）：雷術士的死亡風暴打這裡
            fallen.add(new Fallen(minion.owner, event.getEntity().getLocation(), now() + MinionAbilities.RESUMMON_TICKS));
        }
        remove(minion, Gone.DIED);
        final Player owner = plugin.getServer().getPlayer(minion.owner);
        if (owner != null && minion.decoy && ChargeEffects.ILLUSION.equals(minion.skillId()) && plugin.skills() != null) {
            plugin.skills().chargeEffects().illusionDied(owner, minion.build, event.getEntity().getLocation()); // 2026-10-24：幻身被打掉留下殘影
        }
        if (owner != null && !minion.decoy && plugin.skills() != null && plugin.skills().triggers() != null) {
            // 2026-10：召喚物死亡觸發（2026-10-26 照 PoE2：只算持續型；乘召喚物強度＝相對生命，等級比區域低太多打折）
            plugin.skills().triggers().minionDeath(owner, event.getEntity().getLocation(), minion.untilTick <= 0,
                    tw.exilecore.skill.SkillTriggers.minionPower(minion.spec.life(),
                            MinionSheets.monsterLevel(config, minion.build.level(), plugin.players().get(owner).level()),
                            plugin.monsters() == null ? 0 : plugin.monsters().levelFor(event.getEntity().getLocation())));
            minionDied(owner, minion, event.getEntity().getLocation());
        }
    }

    /** 召喚物死掉時叫出牠的那個技能掛的輔助（2026-10）：觸發、回魔力（魂收）、治療附近的召喚物（骸恤）。 */
    private void minionDied(final Player owner, final Minion minion, final org.bukkit.Location at) {
        final SkillService skills = plugin.skills();
        final String skillId = minion.skillId();
        skills.procEvent(owner, skillId, "minion_death", at, null);
        if (skills.treeMechanics() != null) {
            skills.treeMechanics().minionDied(owner, minion.build.definition()); // 2026-10-26 照 PoE2 天賦：會復活的常駐召喚物被擊殺時獲得易爆
        }
        final double mana = skills.supportFlat(owner, skillId, tw.exilecore.stats.StatKeys.MANA_ON_MINION_DEATH);
        if (mana > 0) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
            if (state.mana() < state.maxMana()) {
                state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * mana / 100.0));
                plugin.players().display(owner, state);
            }
        }
        final double heal = skills.supportFlat(owner, skillId, tw.exilecore.stats.StatKeys.MINION_DEATH_HEAL);
        if (heal > 0) {
            for (final Minion other : List.copyOf(byEntity.values())) {
                if (other == minion || !other.owner.equals(minion.owner) || other.decoy || !other.mob.isValid() || other.mob.isDead()
                        || !other.mob.getWorld().equals(at.getWorld()) || other.mob.getLocation().distanceSquared(at) > 9) {
                    continue;
                }
                final AttributeInstance health = other.mob.getAttribute(Attribute.MAX_HEALTH);
                final double max = health == null ? other.mob.getHealth() : health.getValue();
                other.mob.setHealth(Math.min(max, other.mob.getHealth() + max * heal / 100.0));
                if (plugin.ailments() != null) {
                    plugin.ailments().clear(other.mob);
                }
                other.mob.getWorld().spawnParticle(org.bukkit.Particle.HEART, other.mob.getLocation().add(0, other.mob.getHeight() + 0.2, 0), 2, 0.2, 0.1, 0.2, 0);
            }
        }
    }

    /** 召喚物不會被太陽燒。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        if (event.getClass() == EntityCombustEvent.class && isMinion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 召喚物不變身（殭屍泡水變沉屍）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        if (isMinion(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 召喚物不會把玩家或別的召喚物當目標（原版 AI 萬一回來了也擋住）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(final EntityTargetLivingEntityEvent event) {
        if (isMinion(event.getEntity()) && (event.getTarget() instanceof Player || isMinion(event.getTarget()))) {
            event.setCancelled(true);
            return;
        }
        // 2026-10-26 照 PoE2：敵人不會主動去打焚魂（Enemies will not directly engage these Minions）、蟲群（不能被選為目標）
        final Minion target = event.getTarget() == null ? null : byEntity.get(event.getTarget().getUniqueId());
        if (target != null && ignoredByEnemies(target)) {
            event.setCancelled(true);
        }
    }

    /** 2026-10-26 照 PoE2：蟲群不能被選為目標（打不到）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwarmHit(final EntityDamageByEntityEvent event) {
        final Minion target = byEntity.get(event.getEntity().getUniqueId());
        if (target != null && (MinionAbilities.SWARM.equals(target.skillId()) || target.undamageable() // 2026-10-29：幻化武器也不會受傷
                || target.immuneUntil > now())) { // 2026-10-28 照 PoE2 Skeletal Cleric 的品質：剛復活的骷髏
            event.setCancelled(true); // 2026-10-27：砲手、巨靈也不會受傷（PoE2 MinionsAreUndamagable）
        }
    }

    /** 召喚物打人不擊退（PoE 沒有擊退）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (event instanceof EntityPushedByEntityAttackEvent pushed && isMinion(pushed.getPushedBy())) {
            event.setCancelled(true);
        }
    }

    /** 環境（火、岩漿、摔落、窒息…）不傷召喚物；掉進虛空、/kill 照舊。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEnvironment(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || !isMinion(event.getEntity())) {
            return;
        }
        switch (event.getCause()) {
            case VOID, KILL, CUSTOM, WORLD_BORDER -> {
            }
            default -> event.setCancelled(true);
        }
    }
}
