package tw.exilecore.stats;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 2026-10-26：PoE2 0.5 天賦樹 stat_map 裡 new_key／needs_tag 那一批（戰吼速度以外、還沒有屬性鍵的）照 PoE2 真的生效用的屬性鍵。
 * 不放進 {@link StatKeys}，方便合併；每個鍵寫消費的地方。純邏輯的換算也放這裡（不依賴 Bukkit，可以直接測）。
 */
public final class TreeEffectKeys {

    private TreeEffectKeys() {
    }

    // ---- 移動、防禦 ----

    /** INCREASED（負的＝減少）：使用技能時的移動速度懲罰（PoE2：引導、舉盾這些「邊動作邊走」的減速）。Channelling、SkillService.guard 用。 */
    public static final String ACTION_PENALTY = "movement.action_penalty";
    /** INCREASED：裝備的盾給的護甲、閃避、能量護盾。PlayerStateService.ascendancyEquipment 用（照盾的基底數值加）。 */
    public static final String SHIELD_DEFENCES = "shield.defences";
    /** INCREASED：裝備的胸甲給的護甲。同上。 */
    public static final String BODY_ARMOUR = "armour.body";
    /** INCREASED：裝備的法器給的能量護盾。同上。 */
    public static final String FOCUS_ES = "focus.es";
    /** INCREASED：裝備的箭袋給的加成（箭袋上每一條詞綴放大）。同上。 */
    public static final String QUIVER_EFFECT = "quiver.effect";
    /** INCREASED（負的＝減少）：裝備的屬性需求。PlayerStateService 檢查需求時用。 */
    public static final String ITEM_ATTR_REQUIREMENT = "item.attr_requirement";
    /** FLAT：每點敏捷 +x 暈眩門檻。屬性表算完時換成 FLAT stun.threshold（{@link #applyDerived}）。 */
    public static final String STUN_THRESHOLD_PER_DEX = "stun.threshold_per_dex";
    /** FLAT：每點智慧 +x 命中（「獲得等同智慧的命中」是 1）。同上。 */
    public static final String ACCURACY_PER_INT = "accuracy.per_int";
    /** FLAT %：最大生命的 x% 轉換成最大能量護盾（生命更少 x%、護盾加上那麼多）。同上。 */
    public static final String LIFE_CONVERT_ES = "life.convert_es";
    /** FLAT %：受到的傷害有 x% 跳過能量護盾直接扣生命。DamageCalculator 分護盾、生命時用。 */
    public static final String ES_BYPASS = "es.bypass";
    /** FLAT：被偏斜的擊中再多擋 x%（基礎 40%）。DamageCalculator 用。 */
    public static final String DEFLECT_PREVENT = "deflection.prevent";
    /** INCREASED（負的＝變慢）：你身上的增益時間流逝的速度（PoE2 Buffs on you expire x% slower）。SkillService.buff 用。 */
    public static final String BUFF_TIME = "buff.time_passed";

    // ---- 回復 ----

    /** INCREASED：回復（Recoup）的速度（基礎 4 秒回完）。{@link tw.exilecore.character.Recoup} 用。 */
    public static final String RECOUP_SPEED = "recoup.speed";
    /** FLAT %：受到的元素傷害有 x% 之後回復為能量護盾。CombatListener 用。 */
    public static final String ES_RECOUP_ELEMENTAL = "es.recoup_elemental";
    /** INCREASED：生命回復速度（生命再生、偷取、回復都快 x%）。PlayerStateService.tick 用。 */
    public static final String LIFE_RECOVERY_RATE = "life.recovery_rate";

    // ---- 異常、減益 ----

    /** INCREASED：你造成的致盲效果（命中、閃避更少 20% 的那 20%）。HitEffects 用。 */
    public static final String BLIND_EFFECT = "blind.effect";
    /** INCREASED：你造成的眩暈效果（受到的暈眩累積更多 50% 的那 50%）。HitEffects 用。 */
    public static final String DAZE_EFFECT = "daze.effect";
    /** INCREASED：你造成的凋零效果（每層受到的混沌傷害增加 5% 的那 5%）。HitEffects 用。 */
    public static final String WITHERED_EFFECT = "withered.effect";
    /** INCREASED：點燃造成傷害的速度（每秒傷害乘、持續時間除，總傷害不變）。AilmentService 用。 */
    public static final String IGNITE_SPEED = "ignite.speed";
    /** INCREASED：傷害型異常（點燃、流血、中毒）造成傷害的速度。同上。 */
    public static final String AILMENT_SPEED = "ailment.speed";
    /** INCREASED：破甲（部分與完全）的持續時間。AilmentService.breakArmour 用。 */
    public static final String ARMOUR_BREAK_DURATION = "armour.break_duration";
    /** FLAT %：荊棘傷害無視敵人護甲的機率。CombatListener 用。 */
    public static final String THORNS_IGNORE_ARMOUR = "thorns.ignore_armour";
    /** INCREASED（負的＝減少）：被你詛咒的敵人的生命再生速度。詛咒落地時疊在敵人身上的 {@link #REGEN_RATE}。 */
    public static final String CURSE_ENEMY_REGEN = "curse.enemy_regen";
    /** INCREASED：這隻怪的生命再生速度（減益帶的；MonsterMechanics 的再生乘上去）。 */
    public static final String REGEN_RATE = "life.regen_rate";
    /** INCREASED（負的＝縮小懲罰）：遠距離的命中懲罰。CombatListener 算距離懲罰時用。 */
    public static final String ACCURACY_RANGE_PENALTY = "accuracy.range_penalty";

    // ---- 技能 ----

    /** FLAT %：多連鎖一次的機率（會連鎖的技能才有；寫 lightning 標籤只給閃電技能）。SkillExecutor、ProjectileEngine 用。 */
    public static final String CHAIN_CHANCE = "skill.chain_chance";
    /** FLAT %：投射物分裂時多分出一發的機率。ProjectileEngine.fork 用。 */
    public static final String FORK_EXTRA_CHANCE = "skill.fork_extra_chance";
    /** FLAT %：多射兩發投射物的機率（PoE2 天賦：法術技能 x% 機率多射 2 發）。ProjectileEngine 用。 */
    public static final String PROJECTILE_CHANCE_TWO = "skill.projectile_chance_two";
    /** FLAT %：你自己使用的猛擊多一次餘震的機率。SkillExecutor 用。 */
    public static final String SLAM_AFTERSHOCK_CHANCE = "slam.aftershock_chance";
    /** FLAT：所有技能寶石的品質 +x%。SkillService.qualityModifiers 的呼叫端加上去。 */
    public static final String SKILL_QUALITY = "skill.quality";
    /** FLAT：圖騰的元素抗性上限 +x（加在 75 上）。SkillDeployables 用。 */
    public static final String TOTEM_RESIST_MAX = "totem.resist_max";
    /** FLAT %：消耗屍體時不毀掉屍體的機率。{@link #corpseSaved}。 */
    public static final String CORPSE_SAVE = "corpse.save_chance";
    /** FLAT %：暴擊時獲得秘術增強的機率。CombatListener 用。 */
    public static final String ARCANE_SURGE_ON_CRIT = "arcane_surge.on_crit";
    /** INCREASED：召喚物死掉之後回來的速度。AuraService 用。 */
    public static final String MINION_REVIVE_SPEED = "minion.revive_speed";
    /** INCREASED：你身上護符的效果。CharmService 用。 */
    public static final String CHARM_EFFECT = "charm.effect";

    // ---- 怒氣 ----

    /** FLAT（毫秒）：怒氣開始流失前多等多久。SkillResources 用。 */
    public static final String RAGE_LOSS_DELAY = "rage.loss_delay";
    /** INCREASED（負的＝變慢）：怒氣流失的速度。SkillResources 用。 */
    public static final String RAGE_DECAY = "rage.decay";
    /** FLAT：擊中點燃敵人時獲得 x 點怒氣。AilmentService 用。 */
    public static final String RAGE_ON_IGNITE = "rage.on_ignite";

    /** 標籤：被先祖強化的攻擊（PoE2 Ancestrally Boosted：英靈圖騰叫出來的英靈打的那一下）。SkillDeployables.ancestorHits 補上。 */
    public static final String ANCESTRAL_BOOST = "ancestral_boost";

    /** 暴擊給的秘術增強持續 4 秒（PoE2 預設）；增益本身在 tw.exilecore.skill.TreeMechanics#arcaneSurge（2026-10-26 合併成一套）。 */
    public static final int ARCANE_SURGE_TICKS = 80;

    /** INCREASED 換成倍率：1 ＋ 增加（最低 floor）。 */
    public static double scale(final StatSheet sheet, final String stat, final Set<String> context, final double floor) {
        return Math.max(floor, 1 + sheet.increased(stat, context));
    }

    /** 機率（百分比，可以超過 100）：超過 100 的每 100 必定 1 次，剩下的擲。 */
    public static int fromChance(final double percent, final RandomGenerator random) {
        if (percent <= 0) {
            return 0;
        }
        int extra = (int) Math.floor(percent / 100.0);
        if (random.nextDouble() * 100 < percent - extra * 100) {
            extra++;
        }
        return extra;
    }

    /** 動作中的移動懲罰：基礎懲罰（小數）×（1 ＋ 增加懲罰）× 更多懲罰，夾在 0～1。 */
    public static double actionPenalty(final StatSheet sheet, final double basePenalty) {
        return actionPenalty(sheet, basePenalty, Set.of());
    }

    /**
     * 2026-10-26 照 PoE2（Path of Building CalcOffence：MovementSpeedPenalty 的 INC × MORE）：基礎懲罰 ×（1 ＋ 增加）× 更多，夾在 0～1。
     * 「更多／更少」是衝刺突擊（更少 30%）、鑲嵌的寶石綠色（更少 40%）、凝息（更多 30%）這種；這次之前只算增加。
     */
    public static double actionPenalty(final StatSheet sheet, final double basePenalty, final Set<String> context) {
        return Math.max(0, Math.min(1, basePenalty * Math.max(0, 1 + sheet.increased(ACTION_PENALTY, context))
                * Math.max(0, sheet.more(ACTION_PENALTY, context))));
    }

    /** 增益持續 tick：照「增益時間流逝的速度」換算（流逝慢 10% → 持續 ÷ 0.9）。 */
    public static int buffTicks(final StatSheet sheet, final int ticks) {
        final double rate = Math.max(0.1, 1 + sheet.increased(BUFF_TIME, Set.of()));
        return rate == 1.0 ? ticks : (int) Math.max(1, Math.round(ticks / rate));
    }

    /** 異常造成傷害的速度倍率（每秒傷害 ×、持續時間 ÷）。specific 是那種異常自己的鍵（沒有就 null）。 */
    public static double ailmentSpeed(final StatSheet attacker, final String specific, final Set<String> context) {
        double inc = attacker.increased(AILMENT_SPEED, context);
        if (specific != null) {
            inc += attacker.increased(specific, context);
        }
        return Math.max(0.1, 1 + inc);
    }

    /** 屍體這次有沒有留下來（不毀掉）。 */
    public static boolean corpseSaved(final StatSheet sheet, final RandomGenerator random) {
        final double chance = sheet.flat(CORPSE_SAVE, Set.of());
        return chance > 0 && random.nextDouble() * 100 < chance;
    }

    /** 裝備的屬性需求倍率（減少 4% → 0.96）。 */
    public static double requirementScale(final StatSheet sheet) {
        return Math.max(0, 1 + sheet.increased(ITEM_ATTR_REQUIREMENT, Set.of()));
    }

    /**
     * 屬性表算完屬性之後：每點敏捷的暈眩門檻、等同智慧的命中、最大生命轉換成護盾（PoE2 天賦樹）。
     * 要在屬性值、生命都加完之後呼叫（PlayerStateService.build）。
     */
    public static void applyDerived(final StatSheet sheet) {
        final double perDex = sheet.flat(STUN_THRESHOLD_PER_DEX, Set.of());
        if (perDex > 0) {
            sheet.add(Modifier.flat(StatKeys.STUN_THRESHOLD, Math.floor(sheet.get(StatKeys.DEX) * perDex), "敏捷"));
        }
        final double perInt = sheet.flat(ACCURACY_PER_INT, Set.of());
        if (perInt > 0) {
            sheet.add(Modifier.flat(StatKeys.ACCURACY, Math.floor(sheet.get(StatKeys.INT) * perInt), "智慧"));
        }
        final double convert = Math.min(100, sheet.flat(LIFE_CONVERT_ES, Set.of()));
        if (convert > 0) {
            final double life = sheet.get(StatKeys.LIFE_MAX);
            sheet.add(Modifier.more(StatKeys.LIFE_MAX, -convert / 100.0, "生命轉換"));
            sheet.add(Modifier.flat(StatKeys.ES_MAX, Math.floor(life * convert / 100.0), "生命轉換"));
        }
    }

    /** 一件裝備的修飾器放大 effect 倍（箭袋效果、護符效果）：開關、MORE 不放大。 */
    public static List<Modifier> amplify(final List<Modifier> modifiers, final double extra, final String source) {
        final java.util.ArrayList<Modifier> list = new java.util.ArrayList<>();
        for (final Modifier mod : modifiers) {
            if (mod.type() != ModType.MORE && !mod.stat().startsWith("flag.")) {
                list.add(new Modifier(mod.stat(), mod.type(), mod.value() * extra, mod.tags(), source));
            }
        }
        return list;
    }
}
