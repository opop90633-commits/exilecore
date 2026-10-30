package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-29 照 PoE2 的 Minion Types（召喚物技能的種類）：召喚技能寶石除了自己的標籤，還有一組「召喚物的技能」的標籤
 * （骸骨戰士：攻擊、近戰；骸骨射手：攻擊、投射物、範圍、持續…，寫在 skills.yml 的 summon.minion_tags）。
 * 輔助寶石的 requires 符合技能自己的標籤「或」召喚物的技能標籤就能掛，從召喚物這邊掛上去的效果給召喚物的技能
 * （貫穿掛骸骨射手：射手的箭會穿透；沉錘掛骸骨戰士：戰士的攻擊更多傷害）。
 * <p>
 * 照站長「掛上去完全沒有作用的技能就不讓掛」（{@link SupportGuards}）：從召喚物這邊掛的輔助，每一條修飾器都要是召喚物用得到的
 * （{@link #usable}；消耗、跟召喚物無關的速度代價這種「沒差」的可以帶著），而且至少一條是真的有好處的；
 * 2026-10-29 照 PoE2：遠程召喚物的投射物真的會飛（{@link MinionProjectiles}），投射物速度（疾矢、緩彈、重矢…）對牠們有作用；
 * 這次之前彈道是直接命中的，投射物速度算「沒差」，只動速度的輔助掛不上。
 * 主人自己的機制（怒氣、連擊、戰星、魔力、引導、複擊、觸發、擊退…）召喚物吃不到，照舊不能掛（輔助的 excludes 寫 minion 的也照舊不行）。
 * 這次之前召喚技能只能掛帶 minion 標籤的輔助（兇猛召喚這類），沉錘、貫穿、連環這些全部掛不上。
 * 純函式，不依賴 Bukkit。
 */
public final class MinionTypes {

    /** summon.minion_tags 能寫的標籤（我們的標籤字彙；PoE2 的 MeleeSingleTarget、CreatesGroundEffect、Cascadable 這種沒有對應的不寫）。 */
    public static final Set<String> VOCABULARY = new LinkedHashSet<>(List.of(Tags.ATTACK, Tags.SPELL, Tags.MELEE, Tags.PROJECTILE,
            "chain", Tags.AREA, Tags.SLAM, Tags.STRIKE, Tags.DURATION, Tags.COOLDOWN, Tags.WARCRY,
            Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS));

    /**
     * 自己不是召喚技能、但會叫出召喚物的技能（召喚物的設定在別的隱藏技能上）：掘骸（Unearth）的骨構體。
     * 照 poe2db Unearth 的 Minion Types（Attack, Melee, MeleeSingleTarget, Area；骨構體沒有範圍攻擊，範圍不列），跟 unearth_construct 的 minion_tags 一樣。
     */
    static final Map<String, Set<String>> HOSTED = Map.of(Unearth.ID, Set.of(Tags.ATTACK, Tags.MELEE));

    /** 不是元素類型的「打法」標籤：修飾器帶這些標籤時，召喚物的技能要有才吃得到（傷害類型的標籤可以靠轉換、附加得到，不擋）。 */
    private static final Set<String> DAMAGE_TYPES = Set.of(Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS, Tags.ELEMENTAL,
            Tags.MINION);

    /**
     * 召喚物打中敵人才有作用、而且召喚物打中時真的會算的屬性（CombatListener 對召喚物的那一下也算：條件傷害、破甲、偷取；
     * 異常、暈眩、電擊、眩暈、定身照原本的流程）。
     */
    private static final Set<String> HIT_STATS = Set.of(StatKeys.DAMAGE, SupportKeys.DAMAGE_HIT, SupportKeys.DAMAGE_MAX, StatKeys.PENETRATION,
            StatKeys.CRIT_CHANCE, StatKeys.CRIT_BONUS, StatKeys.CRIT_MULTI,
            StatKeys.IGNITE_CHANCE, StatKeys.IGNITE_MAGNITUDE, StatKeys.IGNITE_DURATION, StatKeys.SHOCK_CHANCE, StatKeys.SHOCK_EFFECT,
            StatKeys.SHOCK_DURATION, StatKeys.CHILL_EFFECT, StatKeys.CHILL_DURATION, StatKeys.FREEZE_BUILDUP, StatKeys.FREEZE_DURATION,
            StatKeys.BLEED_CHANCE, StatKeys.BLEED_MAGNITUDE, StatKeys.BLEED_DURATION, StatKeys.POISON_CHANCE, StatKeys.POISON_MAGNITUDE,
            StatKeys.POISON_DURATION, SupportKeys.POISON_STACKS, StatKeys.AILMENT_DURATION, StatKeys.FLAG_NO_AILMENTS,
            SupportKeys.IGNITE_VS_FROZEN, SupportKeys.IGNITE_VS_FROZEN_UNIQUE, SupportKeys.POISON_VS_BLEEDING,
            StatKeys.STUN_BUILDUP, StatKeys.STUN_DURATION, StatKeys.ELECTROCUTE_CHANCE, StatKeys.ELECTROCUTE_BUILDUP, StatKeys.ELECTROCUTE_DURATION,
            StatKeys.DAZE_CHANCE, StatKeys.FLAG_CULLING, SupportKeys.FLAG_CULLING_RARE, SupportKeys.FLAG_GAIN_AS_COLD,
            // 條件傷害（2026-10-29：CombatListener 對召喚物的那一下也算；這次之前只有玩家打的才算）
            StatKeys.DAMAGE_VS_LOW_LIFE, StatKeys.DAMAGE_VS_FULL_LIFE, StatKeys.CRIT_VS_FULL_LIFE, StatKeys.DAMAGE_VS_RARE, StatKeys.DAMAGE_VS_UNIQUE,
            StatKeys.DAMAGE_VS_CURSED, StatKeys.DAMAGE_CLOSE, SupportKeys.EXTRA_FIRE_VS_IGNITED, SupportKeys.DAMAGE_FAR, SupportKeys.CRIT_VS_IMMOBILISED,
            StatKeys.DAMAGE_VS_AILMENT + "chilled", StatKeys.DAMAGE_VS_AILMENT + "frozen", StatKeys.DAMAGE_VS_AILMENT + "ignited",
            StatKeys.DAMAGE_VS_AILMENT + "shocked", StatKeys.DAMAGE_VS_AILMENT + "poisoned", StatKeys.DAMAGE_VS_AILMENT + "bleeding",
            // 破甲、偷取（2026-10-29：召喚物打的破甲照算、偷取回給召喚物自己）
            StatKeys.ARMOUR_BREAK, SupportKeys.BREAK_VS_FROZEN, StatKeys.LEECH_LIFE, StatKeys.LEECH_LIFE_ELEMENTAL, StatKeys.LIFE_ON_HIT);
    /** 一整類的（附加為、額外傷害）。 */
    private static final List<String> HIT_PREFIXES = List.of(StatKeys.DAMAGE_GAIN, StatKeys.DAMAGE_EXTRA);
    /** 投射物本身的機制：召喚物真的射出投射物（遠程召喚物）才有作用（{@link MinionProjectiles} 的投射物照這幾條穿透、連鎖、分裂、多射）。 */
    private static final Set<String> PROJECTILE_STATS = Set.of(StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_PROJECTILES);

    private MinionTypes() {
    }

    /** 這個技能的召喚物技能標籤（召喚技能的 summon.minion_tags；掘骸照 {@link #HOSTED}）；不是召喚技能是空的。 */
    public static Set<String> of(final SkillDefinition skill) {
        if (skill == null) {
            return Set.of();
        }
        if (skill.summon() != null) {
            return skill.summon().minionTags();
        }
        return HOSTED.getOrDefault(skill.id(), Set.of());
    }

    /** 召喚物會不會打中敵人（攻擊、會造成傷害的法術）；骸骨祭司只治療、復活，不會。 */
    static boolean hits(final SkillDefinition skill) {
        final Set<String> tags = of(skill);
        return tags.contains(Tags.ATTACK) || tags.contains(Tags.SPELL) && !MinionAbilities.CLERIC.equals(skill.id());
    }

    /** 召喚物真的會射出投射物（站遠處射擊的骸骨射手、焰術士、霜術士、雷術士的電弧）：穿透、連鎖、分裂、多射才有作用。 */
    static boolean shoots(final SkillDefinition skill) {
        if (skill.summon() != null) {
            return skill.summon().isRanged();
        }
        return false;
    }

    /** 只聽指令的（砲手）：平常不攻擊，攻擊速度沒有作用。 */
    private static boolean commandOnly(final SkillDefinition skill) {
        return MinionAbilities.commandOnly(skill.id());
    }

    /** 檢查的結果：ok = 可以從召喚物這邊掛；matched = requires 符合召喚物的技能標籤（不行的時候 reason 是給玩家看的原因）。 */
    record Verdict(boolean ok, boolean matched, String reason) {
        static final Verdict NO = new Verdict(false, false, null);
    }

    /** 這顆輔助能不能照召喚物的技能標籤掛在這個技能上。 */
    static Verdict check(final SupportDefinition support, final SkillDefinition skill) {
        final Set<String> tags = of(skill);
        if (tags.isEmpty() || support.excludes().contains(Tags.MINION)) {
            return Verdict.NO; // excludes 寫 minion 的：照 PoE2 不能用在召喚物上（或主人自己的機制），照舊
        }
        if (!support.requires().isEmpty() && java.util.Collections.disjoint(support.requires(), tags)) {
            return Verdict.NO;
        }
        for (final String tag : support.excludes()) {
            if (tags.contains(tag)) {
                return Verdict.NO;
            }
        }
        boolean benefit = false;
        boolean anyUse = false;
        boolean needsHits = false;
        boolean projectile = false;
        for (final Modifier modifier : support.modifiers()) {
            final Use use = usable(modifier, skill, tags);
            if (use == Use.NO) {
                return new Verdict(false, true, "召喚物的技能吃不到這顆輔助的效果");
            }
            if (use == Use.HIT) {
                needsHits = true;
            }
            anyUse |= use != Use.IGNORED;
            if (PROJECTILE_STATS.contains(modifier.stat()) || StatKeys.SKILL_CHAINS.equals(modifier.stat())) {
                projectile = true;
            }
            if (use != Use.IGNORED && beneficial(modifier)) {
                benefit = true;
            }
        }
        if (!anyUse) {
            return Verdict.NO; // 只有消耗、投射物速度這種跟召喚物無關的（節流）：照技能自己的原因講
        }
        if (projectile && !shoots(skill)) {
            return new Verdict(false, true, "召喚物沒有真的射出投射物（穿透、連鎖、分裂、多射吃不到）");
        }
        if (needsHits && !hits(skill)) {
            return new Verdict(false, true, "召喚物不會造成傷害");
        }
        if (!benefit) {
            return new Verdict(false, true, "召喚物的技能吃不到這顆輔助的效果");
        }
        return new Verdict(true, true, null);
    }

    /** 一條修飾器對召喚物：HIT = 打中才有用、UTILITY = 不用打中也有用、IGNORED = 沒差（不算好處也不擋）、NO = 召喚物吃不到。 */
    enum Use { HIT, UTILITY, IGNORED, NO }

    static Use usable(final Modifier modifier, final SkillDefinition skill, final Set<String> tags) {
        final String stat = modifier.stat();
        final boolean penalty = modifier.value() < 0;
        switch (stat) {
            case StatKeys.SKILL_COST -> {
                return Use.IGNORED; // 消耗是主人放技能的
            }
            case StatKeys.SKILL_PROJECTILE_SPEED -> {
                // 2026-10-29 照 PoE2：遠程召喚物的投射物真的會飛（MinionProjectiles），速度有作用；不射投射物的照舊沒差。
                // 這次之前一律沒差（彈道直接命中）
                return tags.contains(Tags.PROJECTILE) && shoots(skill) ? Use.UTILITY : Use.IGNORED;
            }
            case SupportKeys.FLAG_PROJECTILE_SPEED_TO_DAMAGE -> {
                // 2026-10-29 照 PoE2 Projectile Acceleration III：投射物速度的增減也套用在召喚物的投射物傷害（MinionService.rebuild）；這次之前吃不到
                return tags.contains(Tags.PROJECTILE) && shoots(skill) ? Use.HIT : Use.NO;
            }
            case StatKeys.ATTACK_SPEED -> {
                return tags.contains(Tags.ATTACK) && !commandOnly(skill) ? Use.UTILITY : penalty ? Use.IGNORED : Use.NO;
            }
            case StatKeys.CAST_SPEED -> {
                return tags.contains(Tags.SPELL) ? Use.UTILITY : penalty ? Use.IGNORED : Use.NO;
            }
            case StatKeys.SKILL_AREA -> {
                return tags.contains(Tags.AREA) ? Use.UTILITY : penalty ? Use.IGNORED : Use.NO;
            }
            case StatKeys.SKILL_DURATION -> {
                return tags.contains(Tags.DURATION) ? Use.UTILITY : penalty ? Use.IGNORED : Use.NO;
            }
            case StatKeys.SKILL_COOLDOWN, StatKeys.COOLDOWN_RECOVERY -> {
                final boolean worse = StatKeys.SKILL_COOLDOWN.equals(stat) ? modifier.value() > 0 : penalty;
                return tags.contains(Tags.COOLDOWN) ? Use.UTILITY : worse ? Use.IGNORED : Use.NO;
            }
            case StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_PROJECTILES -> {
                return tags.contains(Tags.PROJECTILE) ? Use.UTILITY : Use.NO;
            }
            case StatKeys.SKILL_CHAINS -> {
                return tags.contains(Tags.PROJECTILE) || tags.contains("chain") ? Use.UTILITY : Use.NO;
            }
            case StatKeys.SKILL_KNOCKBACK -> {
                return tags.contains(Tags.ATTACK) && !commandOnly(skill) ? Use.HIT : Use.NO; // 2026-10-29：召喚物普攻打中時擊退（MinionService.attack）
            }
            case StatKeys.PIN_CHANCE -> {
                return tags.contains(Tags.PROJECTILE) && tags.contains(Tags.ATTACK) ? Use.HIT : Use.NO; // 定身：投射物攻擊才累積（HitEffects）
            }
            default -> {
            }
        }
        if (!hitStat(stat)) {
            return Use.NO;
        }
        // 帶「打法」標籤（近戰、投射物、範圍、法術…）的：召喚物的技能要有那個標籤才吃得到
        for (final String tag : modifier.tags()) {
            if (!DAMAGE_TYPES.contains(tag) && !tags.contains(tag)) {
                return penalty ? Use.IGNORED : Use.NO;
            }
        }
        return Use.HIT;
    }

    private static boolean hitStat(final String stat) {
        if (HIT_STATS.contains(stat)) {
            return true;
        }
        for (final String prefix : HIT_PREFIXES) {
            if (stat.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 真的有好處的那一條（冷卻、代價這種是負的才是好處）。 */
    private static boolean beneficial(final Modifier modifier) {
        if (StatKeys.SKILL_COOLDOWN.equals(modifier.stat())) {
            return modifier.value() < 0;
        }
        if (StatKeys.SKILL_PROJECTILE_SPEED.equals(modifier.stat())) {
            return modifier.value() != 0; // 2026-10-29 照 PoE2：緩彈（更慢）也是那顆輔助的效果（投射物停在場上比較久），照玩家的技能一樣可以掛
        }
        return modifier.value() > 0;
    }

    /** 這顆輔助在這個技能上只靠召喚物的技能標籤掛上去（效果只給召喚物；說明列輔助時標「召喚物」）。 */
    public static boolean throughMinions(final SupportDefinition support, final SkillDefinition skill) {
        if (support.ownRefusal(skill) == null) {
            return false;
        }
        // 2026-10-30 照 PoE2：掘骸上的召喚物輔助（要 minion 的）也是只給骨構體
        return support.unearthMinionSupport(skill) || !of(skill).isEmpty() && check(support, skill).ok();
    }

    /**
     * 掘骸這種自己會打人的技能：只靠召喚物的技能標籤掛上去的輔助，修飾器補上 minion 標籤，只給召喚物（骨刺吃不到）；
     * 消耗倍率照舊算在技能上。召喚技能本身不打人，原樣就好。
     */
    static List<Modifier> forHost(final SupportDefinition support, final SkillDefinition skill, final List<Modifier> modifiers) {
        if (skill.summon() != null || !throughMinions(support, skill)) {
            return modifiers;
        }
        final List<Modifier> list = new ArrayList<>(modifiers.size());
        for (final Modifier modifier : modifiers) {
            if (StatKeys.SKILL_COST.equals(modifier.stat()) || modifier.tags().contains(Tags.MINION)) {
                list.add(modifier);
            } else {
                final Set<String> tags = new java.util.HashSet<>(modifier.tags());
                tags.add(Tags.MINION);
                list.add(modifier.withTags(tags));
            }
        }
        return list;
    }

    /** 技能說明：「召喚物的技能：攻擊、近戰、範圍」；沒有召喚物技能標籤回傳 null。 */
    public static String line(final SkillDefinition skill) {
        final Set<String> tags = of(skill);
        if (tags.isEmpty()) {
            return null;
        }
        final List<String> names = new ArrayList<>();
        for (final String tag : VOCABULARY) {
            if (tags.contains(tag)) {
                names.add(ModifierText.tagName(tag));
            }
        }
        return "<gray>召喚物的技能：<white>" + String.join("、", names)
                + " <dark_gray>（照這些標籤也能掛輔助，效果給召喚物）";
    }

    // ---- 投射物（2026-10-29 照 PoE2：遠程召喚物的彈道吃穿透、連鎖、分裂、多射） ----

    /**
     * 召喚物一次射擊的投射物機制：多射幾發、穿透幾個、連鎖幾次、分不分裂，
     * 2026-10-29 加上 speed＝投射物速度的倍率（1 = 原本的速度；照 SkillBuild 一樣算，最少 0.1 倍）。
     */
    public record Projectiles(int extra, int pierce, int chains, boolean fork, double speed) {
        public static final Projectiles NONE = new Projectiles(0, 0, 0, false, 1.0);

        public boolean any() {
            return extra > 0 || pierce > 0 || chains > 0 || fork || Math.abs(speed - 1) > 1e-9;
        }

        /** 實際每 tick 飛幾格：base（{@link MinionAbilities#projectileSpeed}）× 倍率，夾在 0.2～6（跟玩家的 SkillBuild 一樣）。 */
        public double speedOf(final double base) {
            return Math.max(0.2, Math.min(6, base * speed));
        }
    }

    /** 照召喚物疊好輔助的屬性表（{@link MinionSheets} 的 effective）與這一下的標籤算投射物機制。 */
    public static Projectiles projectiles(final StatSheet effective, final Set<String> tags) {
        if (effective == null) {
            return Projectiles.NONE;
        }
        final int extra = clamp((int) Math.floor(effective.flat(StatKeys.SKILL_PROJECTILES, tags) + 1e-6), 0, 8);
        final int pierce = clamp((int) Math.round(effective.flat(StatKeys.SKILL_PIERCE, tags)), 0, 20);
        final int chains = clamp((int) Math.floor(effective.flat(StatKeys.SKILL_CHAINS, tags) + 1e-6), 0, 10);
        final boolean fork = effective.flat(StatKeys.SKILL_FORK, tags) >= 1 - 1e-6;
        // 2026-10-29 照 PoE2：投射物速度（疾矢、緩彈、重矢、主人給召喚物的…），算法跟玩家的技能（SkillBuild）一樣
        final double speed = Math.max(0.1, effective.apply(StatKeys.SKILL_PROJECTILE_SPEED, 1.0, tags));
        return new Projectiles(extra, pierce, chains, fork, speed);
    }

    /**
     * 2026-10-29 照 PoE2 Projectile Acceleration III（疾矢 III）：召喚物的投射物「投射物速度的增加與減少也套用在投射物傷害」時，
     * 要加在投射物傷害上的增加（小數；沒有這個旗標或不是投射物回傳 0）。跟玩家的 {@link SkillBuild#projectileSpeedAsDamage} 一樣。
     */
    public static double speedAsDamage(final StatSheet effective, final Set<String> tags) {
        if (effective == null || !tags.contains(Tags.PROJECTILE)) {
            return 0;
        }
        return SkillBuild.projectileSpeedAsDamage(effective, tags);
    }

    /** 說明用：「召喚物的投射物：額外 2 發、穿透 1 個、連鎖 1 次、第一次打中時分裂」；都沒有回傳 null。 */
    public static String projectileLine(final Projectiles projectiles) {
        return projectileLine(projectiles, 0);
    }

    /**
     * 2026-10-29：會射出真的投射物的召喚物（baseSpeed &gt; 0，{@link MinionAbilities#projectileSpeed}）一定寫速度
     * 「召喚物的投射物：每秒 84 格（×1.4）、額外 2 發…」；baseSpeed 0 照舊只在有輔助時寫。
     */
    public static String projectileLine(final Projectiles projectiles, final double baseSpeed) {
        if (projectiles == null || baseSpeed <= 0 && !projectiles.any()) {
            return null;
        }
        final List<String> parts = new ArrayList<>();
        final boolean faster = Math.abs(projectiles.speed() - 1) > 1e-9;
        final String factor = "×" + ModifierText.number(Math.round(projectiles.speed() * 100) / 100.0);
        if (baseSpeed > 0) {
            parts.add("每秒 <white>" + ModifierText.number(Math.round(projectiles.speedOf(baseSpeed) * 20 * 10) / 10.0) + "</white> 格"
                    + (faster ? "（速度 " + factor + "）" : ""));
        } else if (faster) {
            parts.add("速度 <white>" + factor + "</white>");
        }
        if (projectiles.extra() > 0) {
            parts.add("額外 <white>" + projectiles.extra() + "</white> 發（散開射出）");
        }
        if (projectiles.pierce() > 0) {
            parts.add("穿透 <white>" + projectiles.pierce() + "</white> 個敵人");
        }
        if (projectiles.fork()) {
            parts.add("第一次打中時分裂成兩發");
        }
        if (projectiles.chains() > 0) {
            parts.add("連鎖 <white>" + projectiles.chains() + "</white> 次");
        }
        return "<gray>召喚物的投射物：" + String.join("<gray>、", parts);
    }

    // ---- 打中時（2026-10-29：CombatListener 對召喚物的那一下；這次之前條件傷害、偷取只算玩家打的） ----

    /**
     * 目標在召喚物打中那一刻的狀態（CombatListener 量好傳進來）。
     *
     * @param ailments 目標身上有的異常（chilled、frozen、ignited、shocked、poisoned、bleeding）
     * @param distance 召喚物到目標的距離（格）
     */
    public record Target(boolean lowLife, boolean fullLife, boolean rare, boolean unique, boolean cursed, boolean close, double distance,
                         Set<String> ailments, boolean immobilised) {
    }

    /**
     * 召喚物這一下的條件加成（跟玩家打的同一套，CombatListener 的玩家那段）：對低血、滿血、稀有／傳奇、被詛咒、近身、身上有異常的敵人，
     * 炙獵（對點燃中的額外火焰）、遠穿（照距離）、對被定住的暴擊率。
     */
    public static List<Modifier> conditional(final StatSheet skillSheet, final Set<String> tags, final Target target) {
        final List<Modifier> list = new ArrayList<>();
        final double vsLow = skillSheet.flat(StatKeys.DAMAGE_VS_LOW_LIFE, Set.of()) + skillSheet.increased(StatKeys.DAMAGE_VS_LOW_LIFE, tags) * 100;
        if (vsLow > 0 && target.lowLife()) {
            list.add(Modifier.increased(StatKeys.DAMAGE, vsLow / 100.0, "低血的敵人"));
        }
        final double vsLowMore = skillSheet.more(StatKeys.DAMAGE_VS_LOW_LIFE, tags); // 2026-10-30 照 PoE2：處刑是更多
        if (vsLowMore > 1 + 1e-9 && target.lowLife()) {
            list.add(Modifier.more(StatKeys.DAMAGE, vsLowMore - 1, "處刑（低血的敵人）"));
        }
        if (target.fullLife()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_VS_FULL_LIFE, StatKeys.DAMAGE, tags, "對滿血的敵人");
            scaled(list, skillSheet, StatKeys.CRIT_VS_FULL_LIFE, StatKeys.CRIT_CHANCE, tags, "對滿血的敵人");
        }
        if (target.rare()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_VS_RARE, StatKeys.DAMAGE, tags, "對稀有、傳奇");
        }
        if (target.unique()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_VS_UNIQUE, StatKeys.DAMAGE, tags, "對傳奇、Boss");
        }
        if (target.cursed()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_VS_CURSED, StatKeys.DAMAGE, tags, "對詛咒的敵人");
        }
        if (target.close()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_CLOSE, StatKeys.DAMAGE, tags, "近身");
        }
        for (final String word : target.ailments()) {
            scaled(list, skillSheet, StatKeys.DAMAGE_VS_AILMENT + word, StatKeys.DAMAGE, tags, "對異常的敵人");
        }
        final double extraFire = skillSheet.flat(SupportKeys.EXTRA_FIRE_VS_IGNITED, tags);
        if (extraFire > 0 && target.ailments().contains("ignited")) {
            list.add(Modifier.flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.FIRE), extraFire, "炙獵"));
        }
        final double far = skillSheet.flat(SupportKeys.DAMAGE_FAR, tags);
        if (far > 0) {
            final double near = tw.exilecore.core.Units.metres(3.5); // 遠穿 3.5～7 公尺（跟玩家的一樣，SupportMechanics.preHit）
            final double scale = Math.max(0, Math.min(1, (target.distance() - near) / near));
            if (scale > 0) {
                list.add(new Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.MORE, far * scale / 100.0, Set.of(Tags.ATTACK), "遠穿"));
            }
        }
        final double vsImmobile = skillSheet.increased(SupportKeys.CRIT_VS_IMMOBILISED, tags);
        if (vsImmobile != 0 && target.immobilised()) {
            list.add(Modifier.increased(StatKeys.CRIT_CHANCE, vsImmobile, "對被定住的敵人"));
        }
        return list;
    }

    /** 條件屬性的增加、更多換成這一下的 damage（或暴擊率）的增加、更多。 */
    private static void scaled(final List<Modifier> list, final StatSheet sheet, final String stat, final String into, final Set<String> tags,
                               final String source) {
        final double increased = sheet.increased(stat, tags);
        final double more = sheet.more(stat, tags);
        if (increased != 0) {
            list.add(Modifier.increased(into, increased, source));
        }
        if (more != 1) {
            list.add(Modifier.more(into, more - 1, source));
        }
    }

    /**
     * 召喚物這一下偷取多少生命（我們的單位，還沒封頂）：物理傷害 × 生命偷取 %、元素傷害 × 元素偷取 %，再乘偷取的量。
     * 2026-10-29 照 PoE2（Life Leech 可以輔助骸骨戰士）：召喚物偷取回給召喚物自己，跟玩家一樣存成一份一份的偷取
     * （{@link #addLeech}、{@link #recoverLeech}：一份最多牠最大生命的 10%、1 秒回完，每秒最多 20%）；
     * 這次之前沒有召喚物的偷取，一下直接回、最多最大生命的 10%（LEECH_CAP）。
     */
    public static double leech(final StatSheet effective, final tw.exilecore.stats.SkillProfile skill, final tw.exilecore.stats.DamagePacket dealt) {
        double life = 0;
        final double physical = dealt.get(tw.exilecore.stats.DamageType.PHYSICAL);
        if (physical > 0) {
            life += physical * effective.flat(StatKeys.LEECH_LIFE, skill.contextFor(tw.exilecore.stats.DamageType.PHYSICAL)) / 100.0;
        }
        for (final tw.exilecore.stats.DamageType type : new tw.exilecore.stats.DamageType[] {tw.exilecore.stats.DamageType.FIRE,
                tw.exilecore.stats.DamageType.COLD, tw.exilecore.stats.DamageType.LIGHTNING}) {
            final double amount = dealt.get(type);
            if (amount > 0) {
                life += amount * effective.flat(StatKeys.LEECH_LIFE_ELEMENTAL, skill.contextFor(type)) / 100.0;
            }
        }
        return life <= 0 ? 0 : life * Math.max(0, effective.apply(StatKeys.LEECH_EFFECT, 1.0, Set.of()));
    }

    /** 2026-10-29 照 PoE2：召喚物擊中偷到的生命存進牠自己的偷取（一份最多最大生命的 10%）；回傳實際存進去的量。 */
    public static double addLeech(final tw.exilecore.character.Leech leech, final double amount, final double maxLife) {
        return leech == null || amount <= 0 ? 0 : leech.addHit(amount, maxLife);
    }

    /**
     * 2026-10-29 照 PoE2（跟玩家的 PlayerStateService 一樣）：召喚物的偷取經過 seconds 秒回多少生命（我們的單位）：
     * 每份 1 秒回完、加起來每秒最多最大生命的 20%（召喚物的「最大偷取速度」再乘），「生命回復速度」讓偷取回得更快；
     * 回到滿血剩下的偷取全部結束。life = 牠現在的生命（我們的單位）。
     */
    public static double recoverLeech(final tw.exilecore.character.Leech leech, final double seconds, final double maxLife, final double life,
                                      final StatSheet effective) {
        if (leech == null || leech.pending() <= 0) {
            return 0;
        }
        if (life >= maxLife - 1e-6) {
            leech.clear(); // 滿血：偷取結束（PoE 規則）
            return 0;
        }
        final double rate = effective == null ? 1.0 : Math.max(0.1, effective.apply(StatKeys.LEECH_RATE, 1.0, MinionSheets.CONTEXT));
        final double recovery = effective == null ? 1.0
                : tw.exilecore.stats.TreeEffectKeys.recoveryRate(effective, tw.exilecore.stats.TreeEffectKeys.LIFE_RECOVERY_RATE);
        final double amount = Math.min(leech.tick(seconds * recovery, maxLife, rate), Math.max(0, maxLife - life));
        if (life + amount >= maxLife - 1e-6) {
            leech.clear();
        }
        return amount;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
