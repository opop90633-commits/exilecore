package tw.exilecore.skill;

import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 Raging Spirits（召喚焚魂，PoB2 act_int.lua RagingSpiritsPlayer）：開著時你自己放的火焰法術叫出焚魂——
 * <ul>
 *   <li>範圍法術：每 1.5 公尺的範圍半徑一隻（skill_raging_spirit_per_x_radius 15），施放時叫出；</li>
 *   <li>投射物法術：投射物打中敵人時叫一隻（撲向被打中的那隻）；</li>
 *   <li>一次施放最多 2 隻（skill_raging_spirits_max_per_action 2）。</li>
 * </ul>
 * 這次之前：範圍法術一律兩隻、其他火焰法術一律一隻（不看範圍大小，投射物不用打中）。
 * 叫出來的焚魂鎖定目標：範圍法術是你鎖定（準心）的敵人，沒有就挑落點範圍內離落點最近的；投射物是被打中的那隻。
 */
final class RagingSpirits {

    /** 一次施放最多叫幾隻。 */
    static final int MAX_PER_ACTION = 2;
    /** 範圍法術：每幾公尺（格）的半徑一隻。 */
    static final double METRES_PER_SPIRIT = 1.5;
    /** 投射物法術放出去之後，幾 tick 內它的投射物打中還算這一次施放（夠飛完最遠的射程）。 */
    static final long PROJECTILE_WINDOW_TICKS = 100;

    private RagingSpirits() {
    }

    /** 會叫焚魂的法術：火焰法術（不是攻擊）。 */
    static boolean fireSpell(final SkillDefinition definition) {
        return !definition.isAttack() && definition.tags().contains(Tags.SPELL) && definition.tags().contains(Tags.FIRE);
    }

    /** 照「投射物打中時叫」的規則：投射物機制的法術（火球這類，爆炸也算在投射物上）。 */
    static boolean onHit(final SkillDefinition definition) {
        return definition.mechanic() == Mechanic.PROJECTILE;
    }

    /**
     * 法術的範圍半徑（格，已含範圍加成）：一般是 radius（地面、爆炸、打擊範圍），沒有就看爆炸半徑；
     * 一直線的地面效果（焰幕這種牆）沒有半徑，用牆長的一半當半徑（原作的牆用「最大長度」縮放範圍，沒有半徑屬性；這是最接近的做法）。
     */
    static double areaRadius(final SkillBuild build) {
        final SkillParams.Zone zone = build.params().zone();
        final double base = Math.max(build.radius(), build.explode());
        return zone != null && zone.line() ? Math.max(base, build.length() / 2) : base;
    }

    /** 這個半徑叫幾隻：每 1.5 格一隻（無條件捨去），最多 2 隻。 */
    static int perRadius(final double radius) {
        if (!(radius > 0)) {
            return 0;
        }
        return Math.min(MAX_PER_ACTION, (int) Math.floor(radius / METRES_PER_SPIRIT + 1e-9));
    }

    /**
     * 施放當下叫幾隻：範圍法術照半徑；投射物法術是打中時才叫（這裡 0）；不是範圍也不是投射物的火焰法術原作不叫。
     */
    static int onCast(final SkillDefinition definition, final double radius) {
        if (!fireSpell(definition) || onHit(definition) || !definition.tags().contains(Tags.AREA)) {
            return 0;
        }
        return perRadius(radius);
    }

    /** 投射物火焰法術的「這一次施放還可以叫幾隻」：每個玩家、每個技能一份，再放一次就重算。 */
    static final class Budget {
        private record Cast(long tick, int left) {
        }

        private final Map<UUID, Map<String, Cast>> casts = new HashMap<>();

        /** 自己放了一次投射物火焰法術。 */
        void arm(final UUID owner, final String skillId, final long now) {
            casts.computeIfAbsent(owner, k -> new HashMap<>()).put(skillId, new Cast(now, MAX_PER_ACTION));
        }

        /** 這個技能的投射物打中了：這一次施放還能叫就扣一隻、回傳 true。 */
        boolean take(final UUID owner, final String skillId, final long now) {
            final Map<String, Cast> mine = casts.get(owner);
            final Cast cast = mine == null ? null : mine.get(skillId);
            if (cast == null || now - cast.tick() > PROJECTILE_WINDOW_TICKS || cast.left() <= 0) {
                return false;
            }
            mine.put(skillId, new Cast(cast.tick(), cast.left() - 1));
            return true;
        }

        void clear(final UUID owner) {
            casts.remove(owner);
        }
    }
}
