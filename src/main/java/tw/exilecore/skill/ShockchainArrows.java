package tw.exilecore.skill;

import org.bukkit.util.Vector;

/**
 * 2026-10-28 照 PoE2 0.5 Shockchain Arrow（電鍊箭矢，技能代號 chain_arrow）：
 * <ul>
 *   <li>箭自動追蹤受到閃電異常（感電、電擊）的敵人（{@link #FLAG_HOMING}）。2026-10-28 照 PoE2 RePoE
 *       always_pierce_non_shocked_enemies_if_targeting_shocked_enemy：追著<b>感電</b>的敵人飛時穿過路上<b>沒有感電</b>的敵人
 *       （{@link #piercesOnTheWay}；這次之前照「閃電異常」算，電擊中的也算）。</li>
 *   <li>2026-10-28 照 PoE2 0.4.0 改版說明（「初始投射物消耗感電，產生一支會連鎖的次級投射物，最後一次連鎖後在最後一個目標附近插一根雷霆之杖；
 *       次級投射物打中時不消耗感電」）與 RePoE shockchain_arrow_X_additional_chains_on_first_hitting_shocked_or_electrocuted_enemy 3：
 *       箭<b>第一次</b>打中感電或電擊中的敵人時充能（{@link #onHit}），在那裡放出震波（skills.yml stages 的 shockwave），多連鎖 3 次
 *       （{@link #CHAINS_ON_AILMENT}）；之後（次級投射物）打中的每一下都放震波。2026-10-29 照站長還原原作的寶石說明：連鎖的箭打中另一隻閃電異常的敵人
 *       重複這個過程（再多 3 次），一支箭總共最多連鎖 8 次（{@link #MAX_CHAINS}，「箭矢連鎖 8 次後消失」）；2026-10-28 那一版只充能一次。
 *       連鎖距離 8 公尺（PoE2 0.2.0）。</li>
 *   <li>充能過的箭最後一次連鎖之後掉到地上變成雷霆之杖（{@link #lodgesRod}、{@link LightningRods}；skills.yml 的 rod：20 秒、最多 10 支、
 *       被連鎖 8 次消失、被連到時充能 1 秒（PoE2 0.2.0）再用震波的傷害炸開）。</li>
 *   <li>只有還沒充能的初始箭打中時用掉敵人身上的感電（{@link #consumesShock}；電擊不用掉）：先算這一箭的傷害（還吃得到感電），
 *       用掉感電之後才放震波（震波吃不到被用掉的感電）；次級投射物（連鎖的箭）打中時不用掉感電（PoE2 0.4.0）。
 *       這次之前連鎖的每一下也都用掉感電。箭和震波都不會感電。</li>
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
    static final double CHAIN_RANGE = tw.exilecore.core.Units.metres(8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（8 公尺 = 12 格）
    /**
     * 追蹤：找多遠以內的閃電異常敵人、只找前方幾度以內的、每 tick 最多轉幾度。
     * 2026-10-28 查過：PoE2 的追蹤寫在投射物本身（.ot／.ao），RePoE skills.json、stats、poe2db、官方改版說明都沒有任何追蹤的數值
     * （ShockchainArrowPlayer 只有 always_pierce_non_shocked_enemies_if_targeting_shocked_enemy），只能照手感取：16 格、前方 120°、每 tick 12°。
     */
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
     * @param charges   這一下讓箭充能（第一次打中閃電異常的敵人）
     */
    record Outcome(int addChains, boolean shockwave, boolean charges) {
    }

    /**
     * 純函式：perAilment 是 {@link #CHAINS_ON_AILMENT}；afflicted＝目標身上有感電或電擊；charged＝箭已經充能過（變成連鎖的次級投射物）。
     * 2026-10-28 照 PoE2「on first hitting」：還沒充能、打中閃電異常的：充能、多連鎖 perAilment 次、放震波；充能過的每一下都放震波、不再多給連鎖；
     * 都不是：普通的一箭。這次之前（chained 參數）連鎖打中閃電異常的再多給一次。
     */
    static Outcome onHit(final int perAilment, final boolean afflicted, final boolean charged) {
        if (perAilment <= 0) {
            return new Outcome(0, false, false);
        }
        if (charged) {
            // 2026-10-29 照站長還原原作（寶石說明「若這些連鎖的箭矢擊中另一名受到閃電異常狀態的敵人，會重複此過程」）：
            //   連鎖的箭打中另一隻閃電異常的敵人再多連鎖 perAilment 次（總共最多 MAX_CHAINS 次，見 capChains）；這次之前不再多給
            return new Outcome(afflicted ? perAilment : 0, true, false);
        }
        return afflicted ? new Outcome(perAilment, true, true) : new Outcome(0, false, false);
    }

    /** 2026-10-29 照 PoE2「箭矢連鎖 8 次後消失」：一支箭總共最多連鎖幾次。 */
    static final int MAX_CHAINS = 8;

    /** 純函式：剩下幾次連鎖（已經連鎖過 chained 次），總共不超過 {@link #MAX_CHAINS}。 */
    static int capChains(final int chainsLeft, final int chained) {
        return Math.max(0, Math.min(chainsLeft, MAX_CHAINS - chained));
    }

    /** 純函式：2026-10-28 照 PoE2 0.4.0：只有初始的箭（還沒充能）打中時用掉感電，次級投射物不用掉。 */
    static boolean consumesShock(final boolean flag, final boolean charged) {
        return flag && !charged;
    }

    /**
     * 純函式：2026-10-28 照 PoE2 always_pierce_non_shocked_enemies_if_targeting_shocked_enemy：正在追的是感電的敵人（homingShocked）、
     * 打到的不是那一隻（otherTarget）、也沒有感電（targetShocked）就穿過去。這一下會讓箭充能（電擊中、還沒充能）的不穿，照原作「第一次打中」充能。
     */
    static boolean piercesOnTheWay(final boolean homingShocked, final boolean otherTarget, final boolean targetShocked, final boolean chargesHere) {
        return homingShocked && otherTarget && !targetShocked && !chargesHere;
    }

    /** 純函式：2026-10-28 照 PoE2：充能過的箭（變成次級投射物）最後一次連鎖之後插成雷霆之杖；這次之前看連鎖過幾次。 */
    static boolean lodgesRod(final int perAilment, final boolean charged) {
        return perAilment > 0 && charged;
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
            case CHAINS_ON_AILMENT -> "打中感電或電擊中的敵人時多連鎖 " + ModifierText.number(value) + " 次（連鎖的箭再打中另一隻也一樣，總共最多 "
                    + MAX_CHAINS + " 次），充能之後打中的每一下都放出震波";
            case FLAG_HOMING -> "箭自動追蹤受到閃電異常狀態的敵人";
            case FLAG_CONSUME_SHOCK -> "打中時消耗敵人身上的感電（不消耗電擊；連鎖的箭不消耗）";
            default -> null;
        };
    }
}
