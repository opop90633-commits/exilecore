package tw.exilecore.skill;

import org.bukkit.util.Vector;

/**
 * 2026-10-28 照 PoE2 0.5 Shockchain Arrow（電鍊箭矢，技能代號 chain_arrow）：
 * <ul>
 *   <li>箭自動追蹤受到閃電異常（感電、電擊）的敵人（{@link #FLAG_HOMING}）；追著這種敵人飛的時候穿過路上沒有閃電異常的敵人
 *       （RePoE always_pierce_non_shocked_enemies_if_targeting_shocked_enemy）。</li>
 *   <li>第一次打中感電或電擊中的敵人：箭充能，在那裡放出震波（skills.yml stages 的 shockwave），再多連鎖 3 次（{@link #CHAINS_ON_AILMENT}）；
 *       連鎖的每一下也都放出震波；連鎖打中另一隻有閃電異常的敵人時再多 3 次（可重複）。連鎖距離 8 公尺（PoE2 0.2.0）。</li>
 *   <li>最後一次連鎖之後箭掉到地上變成雷霆之杖（{@link LightningRods}；skills.yml 的 rod：20 秒、最多 10 支、被連鎖 8 次消失）。</li>
 *   <li>箭打中時用掉敵人身上的感電（{@link #FLAG_CONSUME_SHOCK}），電擊不用掉；箭和震波都不會感電。</li>
 * </ul>
 * 這次之前（跳電矢）固定連鎖 5 次、每一下都炸震波、不追蹤、不用掉感電、沒有雷霆之杖。這裡放純邏輯（可以單元測試），
 * 造成傷害、找目標在 {@link ProjectileEngine}。
 */
final class ShockchainArrows {

    /** FLAT：第一次打中感電或電擊中的敵人時多連鎖幾次（PoE2 shockchain_arrow_X_additional_chains_on_first_hitting_shocked_or_electrocuted_enemy 3）。 */
    static final String CHAINS_ON_AILMENT = "projectile.chains_on_lightning_ailment";
    /** FLAG：箭追蹤受到閃電異常的敵人；追著牠飛時穿過路上沒有閃電異常的敵人。 */
    static final String FLAG_HOMING = "flag.home_lightning_ailment";
    /** FLAG：投射物打中時用掉敵人身上的感電（PoE2 SkillConsumesShock；電擊不用掉）。 */
    static final String FLAG_CONSUME_SHOCK = "flag.consume_shock_on_hit";
    /** 震波那一段（skills.yml stages 的代號）。 */
    static final String SHOCKWAVE = "shockwave";
    /** PoE2 0.2.0：Shockchain Arrow 連鎖最遠 8 公尺。 */
    static final double CHAIN_RANGE = 8;
    /** 追蹤：找多遠以內的閃電異常敵人（PoE2 沒寫，照箭的射程取一段）、只找前方幾度以內的、每 tick 最多轉幾度。 */
    static final double HOMING_RANGE = 16;
    static final double HOMING_CONE = 120;
    static final double HOMING_TURN = 12;

    private ShockchainArrows() {
    }

    /**
     * 打中一個敵人之後怎麼做。
     *
     * @param addChains 這一下多給幾次連鎖
     * @param shockwave 這一下要不要放震波
     */
    record Outcome(int addChains, boolean shockwave) {
    }

    /**
     * 純函式：perAilment 是 {@link #CHAINS_ON_AILMENT}；afflicted＝目標身上有感電或電擊；chained＝這一下是連鎖過來的。
     * 有閃電異常：多連鎖 perAilment 次、放震波；連鎖過來的每一下都放震波；兩個都不是：普通的一箭。
     */
    static Outcome onHit(final int perAilment, final boolean afflicted, final boolean chained) {
        if (perAilment <= 0) {
            return new Outcome(0, false);
        }
        return new Outcome(afflicted ? perAilment : 0, afflicted || chained);
    }

    /** 純函式：連鎖過至少一次的箭停下來（最後一次連鎖之後）插成雷霆之杖。 */
    static boolean lodgesRod(final int perAilment, final int chainsDone) {
        return perAilment > 0 && chainsDone > 0;
    }

    /**
     * 純函式：追著飛：direction（單位）往 to 轉，最多轉 maxDegrees 度；回傳新的單位方向。to 在正後方或長度 0 就不轉。
     */
    static Vector steer(final Vector direction, final Vector to, final double maxDegrees) {
        final double length = to.length();
        if (length < 1e-9) {
            return direction.clone();
        }
        final Vector want = to.clone().multiply(1 / length);
        final double dot = Math.max(-1, Math.min(1, direction.dot(want)));
        final double angle = Math.acos(dot);
        final double max = Math.toRadians(maxDegrees);
        if (angle <= max) {
            return want;
        }
        final Vector axis = direction.getCrossProduct(want);
        if (axis.lengthSquared() < 1e-12) {
            return direction.clone(); // 正後方：不知道往哪邊轉，照原本的飛
        }
        return direction.clone().rotateAroundAxis(axis.normalize(), max).normalize();
    }

    /** 純函式：to 在 direction 前方 cone 度的錐裡（追蹤只找前面的）。 */
    static boolean ahead(final Vector direction, final Vector to, final double cone) {
        final double length = to.length();
        return length > 1e-9 && direction.dot(to) / length >= Math.cos(Math.toRadians(cone / 2));
    }

    /** 技能說明（ModifierText）；不是這裡的回傳 null。 */
    static String describe(final String stat, final double value) {
        return switch (stat) {
            case CHAINS_ON_AILMENT -> "第一次打中感電或電擊中的敵人時多連鎖 " + ModifierText.number(value) + " 次，打中的每一下都放出震波";
            case FLAG_HOMING -> "箭自動追蹤受到閃電異常狀態的敵人";
            case FLAG_CONSUME_SHOCK -> "打中時消耗敵人身上的感電（不消耗電擊）";
            default -> null;
        };
    }
}
