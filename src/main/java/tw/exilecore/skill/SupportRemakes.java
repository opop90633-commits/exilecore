package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.SupportRemakeKeys;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照站長「這些輔助的機制跟原作不一樣（用近似的），照原作重做」：重做的輔助共用的邏輯（鍵見 {@link SupportRemakeKeys}）。
 * 純函式放這裡（測試用）；掛勾在 SupportProcs（連震、沉響、竄電、崩勢、朽咒、撒蒺）、CombatListener（闊勢）、
 * SupportExtras.beforeUse（無情）、IceCrystals（晶刺）、ProjectileEngine（歧射、撒蒺）、SkillService（雙詠）、LineageMechanics（巨魄）。
 */
public final class SupportRemakes {

    private SupportRemakes() {
    }

    /**
     * PoE2 Tremors：擲一次（roll 0–100）決定多幾次餘震：10% 三次、15% 兩次、30% 一次（互斥）；multiplier 是「餘震機率增加」的倍率
     * （沉響：攻擊時間越長越高），三段一起放大。純函式。
     */
    public static int tremors(final double roll, final double multiplier) {
        final double m = Math.max(0, multiplier);
        final double three = SupportRemakeKeys.TREMOR_THREE * m;
        final double two = three + SupportRemakeKeys.TREMOR_TWO * m;
        final double one = two + SupportRemakeKeys.TREMOR_ONE * m;
        return roll < three ? 3 : roll < two ? 2 : roll < one ? 1 : 0;
    }

    /**
     * PoE2 Reverberate：攻擊時間每滿 0.25 秒，餘震機率增加 perQuarter%（intervalTicks＝這一下的攻擊時間，tick）。回傳機率的倍率。純函式。
     */
    public static double reverberate(final double perQuarter, final double intervalTicks) {
        if (perQuarter <= 0 || intervalTicks <= 0) {
            return 1.0;
        }
        final double quarters = Math.floor(intervalTicks / 5.0 + 1e-9); // 0.25 秒 = 5 tick
        return 1 + perQuarter / 100.0 * quarters;
    }

    /** PoE2 Overreach 的距離（格）：2 公尺。 */
    public static double overreachRange() {
        return tw.exilecore.core.Units.metres(SupportRemakeKeys.OVERREACH_METRES);
    }

    /**
     * PoE2 Overreach：對 2 公尺內（量到目標身體的邊）的目標命中的倍率（闊勢：更少 80% → 0.2）；遠一點的、沒掛的回傳 1。純函式（傳屬性表）。
     */
    public static double nearbyAccuracy(final StatSheet effective, final Set<String> tags, final double edgeDistance) {
        if (effective == null || edgeDistance > overreachRange() + 1e-9) {
            return 1.0;
        }
        return Math.max(0, effective.more(SupportRemakeKeys.ACCURACY_NEARBY, tags));
    }

    /** PoE2 Ruthless：這是第幾次使用（從 1 算），是不是重擊（每第 every 次）。純函式。 */
    public static boolean ruthlessBigHit(final int use, final int every) {
        return every > 0 && use > 0 && use % every == 0;
    }

    /** 這隻敵人現在是不是濕透（積雷雲的 Drenched）。 */
    static boolean drenched(final ExileCore plugin, final LivingEntity entity, final long now) {
        return plugin.combat() != null && plugin.combat().debuffs() != null
                && plugin.combat().debuffs().has(entity.getUniqueId(), SupportExtraKeys.DRENCHED_ID, now);
    }

    /**
     * PoE2 Coursing Current：從感電的敵人（source）出發，radius 格內最近的濕透敵人（不含 source）；沒有回傳 null。
     */
    static LivingEntity nearestDrenched(final ExileCore plugin, final SkillTargets targets, final Player owner, final LivingEntity source,
                                        final double radius, final long now) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final LivingEntity living : targets.around(source.getLocation(), radius, owner)) {
            if (living == source || !drenched(plugin, living, now)) {
                continue;
            }
            final double distance = living.getLocation().distanceSquared(source.getLocation());
            if (distance < bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Coursing Current 的連鎖不能碰的：source 自己、radius 格內所有沒濕透的敵人（「只能跟濕透的目標碰撞」）。 */
    static Set<UUID> notDrenched(final ExileCore plugin, final SkillTargets targets, final Player owner, final LivingEntity source,
                                 final double radius, final long now) {
        final Set<UUID> exclude = new HashSet<>();
        exclude.add(source.getUniqueId());
        for (final LivingEntity living : targets.around(source.getLocation(), radius, owner)) {
            if (!drenched(plugin, living, now)) {
                exclude.add(living.getUniqueId());
            }
        }
        return exclude;
    }

    /**
     * PoE2 Devastate（崩勢）：被輔助的技能造成重暈時讓敵人完全破甲（SupportProcs 收到 stun_applied 時呼叫）。
     * 完全破甲了照樣送 armour_broken 事件（熔甲、凝破、輔助的「完全破甲時」）。
     */
    static void devastate(final ExileCore plugin, final SkillService service, final Player player, final String skillId, final LivingEntity target) {
        if (target == null || plugin.ailments() == null || plugin.monsters() == null
                || service.supportFlat(player, skillId, SupportRemakeKeys.FULL_BREAK_ON_HEAVY_STUN) <= 0) {
            return;
        }
        final tw.exilecore.monster.MonsterData monster = plugin.monsters().data(target).orElse(null);
        if (monster == null || Double.isInfinite(plugin.ailments().armourBroken(target))) {
            return;
        }
        final double armour = plugin.monsters().sheet(target, monster).get(StatKeys.ARMOUR);
        // 破甲量直接給到護甲的 1000 倍（稀有度倍率再小也一次破滿）＝完全破甲
        if (armour > 0 && plugin.ailments().breakArmour(target, monster, armour * 1000, 100, armour)) {
            service.procEvent(player, skillId, "armour_broken", null, target);
        }
    }
}
