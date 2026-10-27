package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatKeys;


/**
 * 觸發（2026-10 第二版，照 PoE2 的主要寶石 Cast on X；見 {@link MetaGems}）：條件成立時，開著的那種主要寶石累積能量，
 * 滿了就觸發插在它裡面的所有法術（2026-10-26；這次之前只有第 1 孔）。以前的觸發輔助（暴擊觸發、受擊觸發…）已經拿掉（supports.yml 的 retired）。
 * <ul>
 *   <li>暴擊時（crit）：你用技能暴擊敵人，能量 × 怪物強度 × 打掉牠異常門檻的 %（被觸發的法術自己的暴擊不算）。</li>
 *   <li>元素異常（ailment）：冰凍 ×10、感電 ×1、點燃 ×1 × 這個點燃會造成牠異常門檻的 %，再乘怪物強度。</li>
 *   <li>翻滾（dodge，每格）、能量護盾被擊中扣掉（barrier，每點）、持續型召喚物死掉（minion_death，乘召喚物強度、等級太低打折）、
 *       近戰擊殺（melee_kill，乘怪物強度）。</li>
 * </ul>
 */
public final class SkillTriggers implements Listener {

    private final ExileCore plugin;
    private final SkillService service;

    SkillTriggers(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.result().blocked()) {
            return;
        }
        // 2026-10-21：圖騰、英靈打中的不給主要寶石能量、不用掉狙擊印記（照 PoE：圖騰的擊中不算你的）；引爆毒霧照樣
        final boolean totem = event.skill() != null && event.skill().tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT);
        if (!totem && event.attacker() instanceof Player player && event.result().crit() && event.skill() != null
                && !event.skill().id().contains(MetaGems.TRIGGERED_SUFFIX)) {
            // 2026-10-26 照 PoE2 Cast on Critical：每點怪物強度 1 能量 × 這一下打掉牠異常門檻的百分比（最多 100）；這次之前固定 10 × 強度
            service.metas().gain(player, MetaSpec.Kind.CRIT, service.metas().power(event.defender())
                    * service.metas().critPercent(event.defender(), event.result().total()), event.defender());
        }
        // 2026-10-26（久印，PoE2 Eternal Mark）：第一次觸發不用掉印記（怒星照給）
        final boolean keep = !totem && event.attacker() instanceof Player marker && event.result().crit() && plugin.combat() != null
                && plugin.combat().debuffs().has(event.defender().getUniqueId(), "hunters_mark", plugin.getServer().getCurrentTick())
                && service.supportMechanics().keepMark(event.defender(), marker, "hunters_mark");
        if (keep && plugin.combat().charges() != null) {
            plugin.combat().charges().grant((Player) event.attacker(), "frenzy", 1);
        }
        if (!keep && !totem && event.attacker() instanceof Player player && event.result().crit() && plugin.combat() != null
                && plugin.combat().debuffs().remove(event.defender().getUniqueId(), "hunters_mark", player.getUniqueId(),
                plugin.getServer().getCurrentTick())) {
            // 狙擊印記（2026-10 PoE2）：暴擊用掉印記，給一顆怒星
            if (plugin.combat().charges() != null) {
                plugin.combat().charges().grant(player, "frenzy", 1);
            }
        }
        if (event.attacker() instanceof Player player && event.result().dealt().get(tw.exilecore.stats.DamageType.FIRE) > 0
                && event.skill() != null && !event.skill().id().endsWith(".zone")) {
            service.zones().detonateGas(player, event.defender().getLocation()); // 火焰引爆毒霧（2026-10 PoE2）
            service.zones().igniteOil(player, event.defender().getLocation()); // 2026-10-26 照 PoE2：火焰點燃火油
        }
        if (event.defender() instanceof Player player && !(event.attacker() instanceof Player) && event.result().esDamage() > 0) {
            // 2026-10-26 照 PoE2 Barrier Invocation：能量護盾被敵人擊中扣掉的量（障祈引星的 energy 0.1 = 每 10 點 1 能量）；
            //   這次之前是受創引星的「被擊中時每次 30」（原作 0.5 沒有 Cast when Damage Taken，拿掉）
            service.metas().gain(player, MetaSpec.Kind.BARRIER, event.result().esDamage(), event.attacker());
        }
        // 2026-10-26（昇華・擊中時火焰法術，PoE2 Fire Spell on Melee Hit）：近戰攻擊擊中時照怪物強度累積能量
        if (!totem && event.attacker() instanceof Player player && event.skill() != null && event.skill().isAttack()
                && event.skill().tags().contains(tw.exilecore.stats.Tags.MELEE) && !(event.defender() instanceof Player)) {
            service.metas().gain(player, MetaSpec.Kind.MELEE_HIT, service.metas().power(event.defender()), event.defender());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player) {
            return;
        }
        final String skillId = service.lastSkill(event.getEntity(), killer);
        final SkillDefinition skill = skillId == null ? null : service.config().skill(skillId);
        if (skill != null && skill.tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            // 2026-10-26 照 PoE2 Reaper's Invocation（刈祈引星）：每點怪物強度 30；這次之前斬殺引星（Cast on Melee Kill，原作 0.5 拿掉）固定 300
            service.metas().gain(killer, MetaSpec.Kind.MELEE_KILL, service.metas().power(event.getEntity()), null);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.metas().forget(event.getPlayer().getUniqueId());
    }

    /** 翻滾時（DodgeRoll 呼叫，2026-10）。 */
    public void dodge(final Player player) {
        dodge(player, tw.exilecore.combat.DodgeRoll.BASE_METRES);
    }

    /** 翻滾時；metres 是這次翻滾的距離（2026-10-26 照 PoE2 Cast on Dodge：每格 2 能量；這次之前每次固定 12）。 */
    public void dodge(final Player player, final double metres) {
        service.metas().gain(player, MetaSpec.Kind.DODGE, metres, null);
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            plugin.combat().charges().onEvent(player, "dodge", null, null, null); // 戰星：翻滾時（2026-10）
        }
        if (StatKeys.flag(plugin.players().sheet(player), StatKeys.FLAG_DODGE_CALTROPS)) {
            service.originals().dodge(player); // 2026-10-26 照 PoE2 蒺藜步：往兩旁撒 3 顆鐵蒺（這次之前腳下一片 4 秒的鐵蒺地）
        }
        service.chargeEffects().onDodge(player, plugin.players().sheet(player)); // 2026-10-24：幻身
        tw.exilecore.ascendancy.AscendancyEffects.dodge(player); // 2026-10-26（昇華・虛空幻影）
        // 2026-10-26：照站長拿掉幻身步（翻滾留下殘影 proc_phantom_decoy）；照 PoE2 的幻身（lingering_illusion）在 ChargeEffects
    }

    /** 自己的召喚物死掉時（MinionService 呼叫，2026-10）：找牠倒下的地方附近的敵人當目標。 */
    public void minionDeath(final Player owner, final org.bukkit.Location at) {
        minionDeath(owner, at, true);
    }

    /** 同上；persistent = 沒有持續時間的召喚物（2026-10-26 照 PoE2 Cast on Minion Death：只有持續型召喚物死掉給能量）。 */
    public void minionDeath(final Player owner, final org.bukkit.Location at, final boolean persistent) {
        minionDeath(owner, at, persistent, 1);
    }

    /** 2026-10-26：召喚物等級比區域低超過這麼多級才開始打折（PoE2「Very underlevelled Minions」）。 */
    static final int MINION_LEVEL_GRACE = 10;
    /** 2026-10-26：超過上面那個級距後，每低 1 級能量少幾 %。 */
    static final double MINION_LEVEL_PENALTY = 5;
    /** 2026-10-26：等級懲罰最多打到剩幾成。 */
    static final double MINION_LEVEL_FLOOR = 0.1;

    /**
     * 2026-10-26 照 PoE2 Cast on Minion Death（「Gains 50 base Energy when a Minion is killed, modified by the Minion's Power」，
     * 每 2% 召喚物相對防禦 1 能量；「Very underlevelled Minions will incur an Energy generation penalty」）：
     * 召喚物強度＝牠的相對生命倍率（summon.life，一般的 1），等級比區域低超過 {@value #MINION_LEVEL_GRACE} 級時每多低 1 級少
     * {@value #MINION_LEVEL_PENALTY}%（最少剩 {@value #MINION_LEVEL_FLOOR} 倍；原作沒公布公式，這是近似）。這次之前每隻固定 50。
     */
    public static double minionPower(final double lifeMultiplier, final int minionLevel, final int areaLevel) {
        final double base = Math.max(0.05, lifeMultiplier);
        final int gap = areaLevel - minionLevel - MINION_LEVEL_GRACE;
        final double penalty = gap <= 0 ? 1 : Math.max(MINION_LEVEL_FLOOR, 1 - gap * MINION_LEVEL_PENALTY / 100.0);
        return base * penalty;
    }

    /** 同上；power 是 {@link #minionPower} 算出來的召喚物強度（乘在殞從引星的 50 上）。 */
    public void minionDeath(final Player owner, final org.bukkit.Location at, final boolean persistent, final double power) {
        if (!persistent) {
            plugin.players().conditions().onMinionDied(owner, plugin.getServer().getCurrentTick());
            return;
        }
        LivingEntity target = null;
        double best = 100;
        for (final org.bukkit.entity.Entity near : at.getWorld().getNearbyEntities(at, 6, 4, 6)) {
            if (near instanceof LivingEntity living && !(near instanceof Player) && service.minions() != null && !service.minions().isMinion(living)
                    && living.isValid() && !living.isDead() && plugin.monsters() != null && plugin.monsters().data(living).isPresent()
                    && (plugin.training() == null || plugin.training().canTarget(living, owner))) {
                final double d = living.getLocation().distanceSquared(at);
                if (d < best) {
                    best = d;
                    target = living;
                }
            }
        }
        service.metas().gain(owner, MetaSpec.Kind.MINION_DEATH, Math.max(0, power), target); // 2026-10-26：乘召喚物強度（這次之前固定 1）
        plugin.players().conditions().onMinionDied(owner, plugin.getServer().getCurrentTick()); // 2026-10-26：天賦樹「最近有召喚物死掉」
    }

    /**
     * 讓敵人冰凍、感電、點燃時（異常模組呼叫）：冰凍的能量 ×10（照 PoE2，2026-10-26）。
     * 主要寶石觸發的法術造成的異常不算（skillId 帶 {@link MetaGems#TRIGGERED_SUFFIX}），不然會自己一直觸發自己。
     */
    /** 2026-10-26 照 PoE2 Siphon Elements（素痕湧）：冰凍（每點強度）、點燃、感電敵人時 x% 機率在牠身上留下同元素的灌注殘骸。 */
    public static final String SIPHON_REMNANT = "infusion.remnant_on_ailment";
    /** 素痕湧的技能代號（殘骸輔助掛在它上面）。 */
    static final String SIPHON_ELEMENTS = "siphon_elements";

    private void siphonRemnant(final Player player, final LivingEntity target, final tw.exilecore.ailment.AilmentType type) {
        final int element = switch (type) {
            case IGNITE -> 0;
            case FREEZE -> 1;
            case SHOCK -> 2;
            default -> -1;
        };
        if (element < 0) {
            return;
        }
        final double chance = plugin.players().sheet(player).flat(SIPHON_REMNANT, java.util.Set.of())
                * (type == tw.exilecore.ailment.AilmentType.FREEZE ? Math.max(1, service.metas().power(target)) : 1);
        if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
            // 2026-10-27：掛在素痕湧上的殘骸輔助（和骸、磁骸、骸效）跟著這個殘骸走
            final ChargeEffects.RemnantMods mods = new ChargeEffects.RemnantMods(
                    service.supportFlat(player, SIPHON_ELEMENTS, tw.exilecore.stats.SupportExtraKeys.REMNANT_RANGE),
                    service.supportFlat(player, SIPHON_ELEMENTS, tw.exilecore.stats.SupportExtraKeys.REMNANT_EXTRA),
                    service.supportFlat(player, SIPHON_ELEMENTS, tw.exilecore.stats.SupportExtraKeys.REMNANT_EFFECT),
                    service.supportFlat(player, SIPHON_ELEMENTS, tw.exilecore.stats.SupportExtraKeys.REMNANT_DELAY));
            service.chargeEffects().dropInfusionRemnant(player, target.getLocation(), element, mods);
        }
    }

    public void ailment(final Player player, final LivingEntity target, final tw.exilecore.ailment.AilmentType type, final String skillId) {
        ailment(player, target, type, skillId, MetaGems.CRIT_PERCENT_FALLBACK);
    }

    /**
     * 2026-10-26 照 PoE2 Cast on Elemental Ailment／Elemental Invocation：點燃的能量「modified by the percentage of the enemy's
     * Ailment Threshold the Ignite will deal」——igniteThresholdPercent 是這個點燃總傷害占牠異常門檻的 %（最多算 100，跟暴擊一樣）；
     * 冰凍、感電不看。這次之前點燃固定每點強度 1。
     */
    public void ailment(final Player player, final LivingEntity target, final tw.exilecore.ailment.AilmentType type, final String skillId,
                        final double igniteThresholdPercent) {
        plugin.players().conditions().onAilment(player, target, type, plugin.getServer().getCurrentTick()); // 2026-10-26：天賦樹「最近點燃／感電過敵人」
        service.supportMechanics().lineage().ailment(player, type, skillId); // 2026-10-27 照 PoE2 燼裁：點燃時獲得元素執政
        if (skillId != null && skillId.contains(MetaGems.TRIGGERED_SUFFIX)) {
            return;
        }
        service.originals().onAilment(player, target, type); // 2026-10-26 照 PoE2：雷流場的電球（素痕湧在下面的 siphonRemnant）
        // 2026-10-26 照 PoE2 Cast on Elemental Ailment：冰凍每點強度 10、感電每點強度 1、點燃每點強度 1 × 打掉異常門檻的 %
        //   （異變引星的 energy 寫 1）；這次之前冰凍 ×3、點燃固定 1
        final double kind = type == tw.exilecore.ailment.AilmentType.FREEZE ? 10
                : type == tw.exilecore.ailment.AilmentType.IGNITE ? Math.max(0, Math.min(100, igniteThresholdPercent)) : 1;
        tw.exilecore.ascendancy.AscendancyEffects.ailment(player, target, type); // 2026-10-26（昇華）：易爆、感電／冰緩地面
        service.metas().gain(player, MetaSpec.Kind.AILMENT, kind * service.metas().power(target), target);
        if (type == tw.exilecore.ailment.AilmentType.IGNITE) {
            service.supportMechanics().onIgnite(player, target); // 2026-10-26：熔魂嘯的榮耀（點燃敵人時照牠的強度）
        }
        siphonRemnant(player, target, type);
        if (type == tw.exilecore.ailment.AilmentType.IGNITE || type == tw.exilecore.ailment.AilmentType.FREEZE
                || type == tw.exilecore.ailment.AilmentType.SHOCK) {
            service.supportMechanics().gainGlory(player, "ailment", 1); // 2026-10-26 照 PoE2：熾陽之矛的榮耀（冰凍、感電、點燃各 1 點）
        }
    }
}
