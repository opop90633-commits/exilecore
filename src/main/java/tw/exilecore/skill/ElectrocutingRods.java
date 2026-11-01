package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 Electrocuting Arrow（電殛矢）：打中的敵人插上電殛棒（base_skill_effect_duration 25000，吃技能持續時間），
 * 插著棒的敵人受到的「所有」閃電傷害都累積電擊值（不管是誰打的、有沒有電擊的來源）；牠下次被電擊時棒子拔掉。
 * 品質：打中插著棒的目標，獲得 x% 傷害為額外閃電傷害（ProjectileEngine）。純資料／純函式，不依賴 Bukkit。
 * 這次之前電殛棒只是品質用的記號（最多 8 秒），電殛矢靠「電擊累積更多 100%」自己電擊。
 */
public final class ElectrocutingRods {

    /** 減益代號（combat.Debuffs；source = 插棒的玩家）。 */
    public static final String ROD_ID = "electrocuting_rod";
    /** FLAT（skills.yml 的 modifiers）：打中時插電殛棒、插幾秒（0 = 這個技能不插棒）。 */
    public static final String ROD_SECONDS = "rod.duration";

    private ElectrocutingRods() {
    }

    /**
     * 這一擊的「電擊來源比例」（AilmentService：累積量乘這個，最多 1）：插著電殛棒的目標一律當作 100% 的來源，不然照原本。
     *
     * @param rodded 目標身上插著電殛棒
     * @param chance 原本的比例（電擊機率等；0 = 沒有來源，閃電傷害不累積）
     */
    public static double electrocuteSource(final boolean rodded, final double chance) {
        return rodded ? Math.max(1.0, chance) : chance;
    }

    /** 電殛棒插幾 tick：秒數 × 持續時間倍率（durationScale），至少 1 tick；seconds ≤ 0 回傳 0（不插）。 */
    public static long rodTicks(final double seconds, final double durationScale) {
        if (seconds <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(seconds * 20 * Math.max(0, durationScale)));
    }
}
