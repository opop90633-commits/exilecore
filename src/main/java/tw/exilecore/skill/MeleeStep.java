package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

/**
 * 2026-10-28 照 PoE2 的 melee_conditional_step_distance（近戰條件位移；RePoE skills.json 的隱藏屬性，單位 0.1 公尺）：
 * 近戰攻擊出手時往前踏一步再打。這次之前我們的近戰技能都是原地打（只有斷魂掌、裂血掠、騰躍砸擊這些本來就會位移的會動）。
 *
 * <ul>
 *   <li>有條件（conditional）：準心指著敵人（或技能鎖定的目標）時，牠已經在這一下打得到的距離內就不動；
 *       在外面就朝牠踏過去，最多踏 {@link Spec#distance()}，走到打得到的地方就停（不會踩過頭）。</li>
 *   <li>準心沒指著敵人、這一下的範圍裡也沒有敵人：朝準心指的地面踏過去，最多 {@link Spec#distance()}；
 *       準心指著腳邊（比踏一步還近）就只踏到那裡，指著自己腳下就不動。</li>
 *   <li>走 {@link DashPath}：會被牆擋住、不穿牆（停在最後一個站得下的地方），可以爬上 1 格、走下 3 格。</li>
 *   <li>被定住（凍結、重暈、電擊、釘住）時不動，原地打。</li>
 *   <li>踏完才打：打擊、新星、扇形、旋矛擊的旋風都在踏完的位置。</li>
 * </ul>
 *
 * skills.yml 寫法：{@code step: { distance: 2.6 }}（conditional 預設 true；寫 false 就是不管有沒有目標都踏滿）。
 */
final class MeleeStep {

    /** 踏不到這麼遠就不動（傳送 0.2 格只會讓畫面抖一下）。 */
    static final double MIN_MOVE = 0.25;
    /** 鎖定的目標最遠多遠還朝牠踏（更遠的當成沒有目標，朝準心的地面踏）。 */
    static final double LOCK_RANGE = 16;

    /**
     * @param distance    最多踏幾格（PoE2 的 melee_conditional_step_distance ÷ 10，公尺 = 格）
     * @param conditional 有條件：目標已經打得到就不動、走到打得到就停（PoE2 全部都是）
     */
    record Spec(double distance, boolean conditional) {
        Spec {
            if (!(distance > 0) || distance > 10) {
                throw new IllegalArgumentException("step.distance：要在 0 到 10 格之間，卻是 " + distance);
            }
        }
    }

    /** skills.yml 的 step（沒寫回傳 null）：{@code step: 2.6} 或 {@code step: { distance: 2.6, conditional: true }}。 */
    static Spec parse(final YamlNode node) {
        if (!node.has("step")) {
            return null;
        }
        final Object raw = node.raw("step");
        try {
            if (raw instanceof Number number) {
                return new Spec(number.doubleValue(), true);
            }
            final YamlNode sub = node.node("step");
            return new Spec(sub.decimal("distance", 0), sub.bool("conditional", true));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(node.path() + "." + e.getMessage(), e);
        }
    }

    /**
     * 朝敵人踏多遠：{@code distance} 是到牠的水平距離，{@code reach} 是這一下打得到多遠。
     * 有條件的：已經打得到回傳 0，不然踏到剛好打得到（最多 spec.distance）。
     */
    static double towardTarget(final Spec spec, final double distance, final double reach) {
        if (!spec.conditional()) {
            return spec.distance();
        }
        return capped(spec, distance - Math.max(0, reach));
    }

    /** 朝準心的地面踏多遠：{@code cursor} 是準心指的地面的水平距離（沒指到地面＝無限遠）。 */
    static double towardGround(final Spec spec, final double cursor) {
        if (!spec.conditional()) {
            return spec.distance();
        }
        return capped(spec, cursor);
    }

    /** 實際要走的距離（夾在 spec.distance 以內；太短、算不出來回傳 0）。 */
    static double capped(final Spec spec, final double wanted) {
        final double move = Math.min(spec.distance(), wanted);
        return Double.isNaN(move) || move < MIN_MOVE ? 0 : move;
    }

    /** 這一下打得到多遠（判斷目標在不在範圍裡用）：打擊看打擊距離、新星看半徑、扇形看長度、地面看長度或半徑。 */
    static double reach(final SkillBuild build) {
        return switch (build.definition().mechanic()) {
            case STRIKE, FLICKER -> build.reach();
            case NOVA -> build.radius();
            case CONE -> build.length();
            case ZONE -> Math.max(build.length(), build.radius());
            default -> Math.max(build.reach(), build.radius());
        };
    }

    private MeleeStep() {
    }
}
