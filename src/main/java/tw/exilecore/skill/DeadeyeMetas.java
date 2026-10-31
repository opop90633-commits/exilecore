package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 2026-10-28 照 PoE2 0.5：星矢（Deadeye）昇華賦予的兩個主要寶石（Meta），孔裡放技能、其他孔的輔助套在孔裡的技能與它自己身上。
 * 這次之前兩個都不能放技能：預告射擊放面板上第一個印記、幻影重複你自己放的投射物攻擊；冷卻恢復（迅返）、延長持續時間（長留）掛不上去。
 * <ul>
 *   <li>預告射擊（RePoE MetaDeadeyeMarksPlayer、SupportMetaDeadeyeMarksPlayer：allowed_types Mark）：孔裡放印記
 *       （詛咒不行），每 5 秒把孔裡的印記放在 10 格內一個還沒被標記的敵人身上（好幾個照孔的順序輪流）。
 *       印記吃其他孔的輔助（印記有 Duration、Cooldown：長留加印記持續時間、迅返加快這個間隔）。</li>
 *   <li>幻影銳眼（RePoE MetaMirageDeadeyePlayer、MirageDeadeyeSpawnPlayer：Duration、Cooldown，冷卻 10 秒、幻影 4.05～6 秒；
 *       SupportMirageDeadeyePlayer：allowed RangedAttack／CrossbowAmmoSkill，excluded Melee、Meta、Triggered、Persistent…；
 *       skill_disabled_unless_cloned、幻影傷害更少 50%）：孔裡放遠程投射物攻擊；你自己發射遠程投射物攻擊時留下幻影，
 *       幻影在持續時間內一直用孔裡的攻擊（你自己不能用孔裡的攻擊；引導的照引導時間放、有冷卻的幻影自己算冷卻；不能消耗戰星）。
 *       其他孔的迅返、長留套在幻影上（冷卻、持續時間）。</li>
 * </ul>
 * 純邏輯（AscGranted 執行、SkillService 算數值）。
 */
public final class DeadeyeMetas {

    private DeadeyeMetas() {
    }

    /** 預告射擊。 */
    public static final String CALLED_SHOTS = "called_shots";
    /** 幻影銳眼。 */
    public static final String MIRAGE = "mirage_deadeye";

    /**
     * 預告射擊自己的冷卻（秒；PoE2「Applies a socketed Mark to 1 Enemy every five seconds」，buff ascendancy_deadeye_marks_cooldown
     * 「You cannot yet Mark another enemy with this Skill」）：定時放與印記用掉後補放共用這一個冷卻。
     * 預告射擊沒有 Cooldown／AffectedByCooldownRate 類型，冷卻恢復不縮短它（縮短的是孔裡印記自己的冷卻）。
     */
    public static final double CALLED_INTERVAL = 5;
    /** 預告射擊放印記的範圍（格；PoE2 active_skill_base_area_of_effect_radius 100 = 10 米，吃範圍加成）。 */
    public static final double CALLED_RADIUS = tw.exilecore.core.Units.metres(10); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 孔裡的印記被用掉時，再標記被用掉那隻 3 米內的另一個敵人（SupportMetaDeadeyeMarksPlayer，100% 機率）。 */
    public static final double SPREAD_RADIUS = tw.exilecore.core.Units.metres(3); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 幻影的冷卻（秒；RePoE MirageDeadeyeSpawnPlayer cooldown 10000）。 */
    public static final double MIRAGE_COOLDOWN = 10;
    /** 幻影傷害更少（%；support_lingering_mirage_damage_+%_final -50）。 */
    public static final double MIRAGE_LESS = 50;
    /** 幻影找目標的範圍（格）。 */
    public static final double MIRAGE_RANGE = 24;

    /** 這個技能是不是星矢的主要寶石（孔裡放技能）。 */
    public static boolean hosts(final SkillDefinition skill) {
        return skill != null && (CALLED_SHOTS.equals(skill.id()) || MIRAGE.equals(skill.id()));
    }

    /** 這顆技能能不能放進 host 的孔；可以回傳 null，不行回傳原因（接在技能名字後面）。 */
    public static String problem(final SkillDefinition host, final SkillDefinition inner) {
        if (inner == null || inner.hidden()) {
            return "不認得的技能";
        }
        if (inner.grantedOnly()) {
            return MetaGems.GRANTED_ONLY;
        }
        if (inner.tags().contains(Tags.META) || SupportDefinition.hostsSkill(inner) || SocketedWarcry.hosts(inner)) {
            return "主要寶石不能放";
        }
        if (host != null && CALLED_SHOTS.equals(host.id())) {
            if (inner.mechanic() != Mechanic.MARK || inner.params().mark() == null || inner.params().mark().curse()) {
                return "只能放印記（詛咒不行）";
            }
            return null;
        }
        if (host != null && MIRAGE.equals(host.id())) {
            if (!inner.isAttack() || !inner.tags().contains(Tags.PROJECTILE) || inner.tags().contains(Tags.MELEE)) {
                return "只能放遠程的投射物攻擊";
            }
            final Mechanic mechanic = inner.mechanic();
            if (mechanic == Mechanic.AURA || mechanic == Mechanic.DASH || mechanic == Mechanic.LEAP || mechanic == Mechanic.FLICKER
                    || mechanic == Mechanic.WARCRY || mechanic == Mechanic.META || inner.deploy() != null
                    || inner.tags().contains(Tags.PERSISTENT)) {
                return "只能放遠程的投射物攻擊（常駐、位移、圖騰陷阱不行）";
            }
            // 2026-10-28 照 PoE2 0.5：引導／蓄力的攻擊（穿雲箭、熾漿砲、爆焰矢）與有冷卻的攻擊都可以放（幻影自己引導、自己算冷卻）；
            // 這次之前（同一天稍早）這兩種不能放
            return null;
        }
        return "不是星矢的主要寶石";
    }

    /** 孔裡要放什麼（給玩家看）。 */
    public static String wants(final SkillDefinition host) {
        return host != null && CALLED_SHOTS.equals(host.id()) ? "印記" : "遠程投射物攻擊";
    }

    /**
     * 這顆輔助是不是套在主要寶石自己身上（幻影的冷卻、持續時間）：只改冷卻時間、冷卻回復速度、持續時間的（迅返、長留…）。
     */
    public static boolean onHost(final SupportDefinition support) {
        if (support.modifiers().isEmpty()) {
            return false;
        }
        for (final Modifier modifier : support.modifiers()) {
            final String stat = modifier.stat();
            if (!stat.equals(StatKeys.SKILL_COOLDOWN) && !stat.equals(StatKeys.COOLDOWN_RECOVERY) && !stat.equals(StatKeys.SKILL_DURATION)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 2026-10-28 照 PoE2（RePoE 的輔助 allowed_types 對 Meta／Persistent／Buff／Area 判斷）：這顆輔助套在主要寶石自己身上——
     * 只改冷卻、持續時間的（幻影：MirageDeadeyeSpawnPlayer 有 Cooldown、Duration）、
     * 常駐增益的「技能開著時你獲得…」（泉命、凝準、醇藥、泉魔、絕擊、威宣…：Persistent AND Buff，保留精魂）、
     * 預告射擊的範圍（擴散、凝聚：Called Shots 有 Area，放印記的範圍）。
     */
    public static boolean appliesToHost(final SupportDefinition support, final SkillDefinition host) {
        return onHost(support) || persistentBuff(support) || host != null && CALLED_SHOTS.equals(host.id()) && area(support);
    }

    /** 常駐增益輔助：要光環／捷光環／常駐技能，效果是「技能開著時你獲得」（grant.、persistent.、血脈的 lineage.u.）。 */
    static boolean persistentBuff(final SupportDefinition support) {
        if (!support.requires().contains(Tags.AURA) && !support.requires().contains(Tags.HERALD) && !support.requires().contains(Tags.PERSISTENT)
                && !support.requires().contains(SupportDefinition.PERSISTENT_BUFF)) { // 2026-10-29
            return false;
        }
        for (final Modifier modifier : support.modifiers()) {
            final String stat = modifier.stat();
            if (stat.startsWith(StatKeys.GRANT_PREFIX) || stat.startsWith("persistent.") || stat.startsWith("lineage.u.")) {
                return true;
            }
        }
        return false;
    }

    /** 範圍輔助（要範圍技能、改範圍的）。 */
    static boolean area(final SupportDefinition support) {
        if (!support.requires().contains(Tags.AREA)) {
            return false;
        }
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().equals(StatKeys.SKILL_AREA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-28 照 PoE2（SupportMirageDeadeyePlayer：support_lingering_mirage_damage_+%_final -50、cannot_consume_power_frenzy_endurance_charges）：
     * 幻影用的那一下：傷害更少 50%、不能消耗戰星（銳星、怒星、磐星）。
     */
    public static SkillBuild mirageBuild(final SkillBuild attack) {
        return attack.withProfile(attack.profile().scaled(1 - MIRAGE_LESS / 100.0, "幻影神射")
                .withLocal(java.util.List.of(Modifier.flat(tw.exilecore.charge.ChargeService.SUP_INHIBIT, 1, "幻影神射"))));
    }

    /** 冷卻倍率（冷卻時間 ÷ 冷卻回復速度；跟 SkillBuild 一樣夾在一成）。 */
    public static double cooldownFactor(final StatSheet effective, final Set<String> tags) {
        if (effective == null) {
            return 1.0;
        }
        return Math.max(0.1, effective.apply(StatKeys.SKILL_COOLDOWN, 1.0, tags)) / Math.max(0.1, effective.apply(StatKeys.COOLDOWN_RECOVERY, 1.0, tags));
    }

    /** 持續時間倍率（跟 SkillBuild 一樣夾在一成）。 */
    public static double durationFactor(final StatSheet effective, final Set<String> tags) {
        return effective == null ? 1.0 : Math.max(0.1, effective.apply(StatKeys.SKILL_DURATION, 1.0, tags));
    }

    /** 幾秒換成 tick（至少 1）。 */
    public static long ticks(final double seconds) {
        return Math.max(1, Math.round(seconds * 20));
    }
}
