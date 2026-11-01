package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5：技能擊退的距離換算（我們的擊退是設給敵人的水平速度，skill.knockback 的值就是這個速度）。
 * <ul>
 *   <li>Minecraft 的生物被設成水平速度 v、往上 0.3 時（SkillExecutor.knockback），飛出去再落地滑停一共大約走 {@link #BLOCKS_PER_VELOCITY} × v 格
 *       （空中每 tick 乘 0.91、落地乘 0.546；{@link #distance} 是同一套模擬）。所以「擊退 x 公尺」寫成速度 x ÷ 6.92。</li>
 *   <li>PoB 的 base_knockback_distance 照 0.01 公尺一單位讀（蛇嵐突 1500 = 15 公尺、風蛇 1500 − 1000 = 5 公尺）。</li>
 *   <li>原作只寫 global_knockback（沒寫基礎距離）的技能：PoB、poe2db 都沒有數字，照我們其他技能一直用的 {@link #DEFAULT_VELOCITY}（約 2.8 格）。</li>
 *   <li>嵐棍橫掃（Wind Blast）：照距離調整擊退，貼身更多 200%、到 6 公尺（範圍邊緣）更少 90%，中間照距離線性（{@link #windBlast}）。</li>
 *   <li>蛇嵐突：對傳奇敵人擊退距離更多 200%（Boss 有擊退抗性的照樣推不動）。</li>
 * </ul>
 * 純邏輯，不依賴 Bukkit。
 */
final class KnockbackMath {

    /** 水平速度 1 大約推幾格。 */
    static final double BLOCKS_PER_VELOCITY = 6.92;
    /** 只寫「擊退」沒寫距離的預設速度。 */
    static final double DEFAULT_VELOCITY = 0.4;
    /** 技能分段（SkillStages）自己的擊退（三連刺只有最後一刺擊退）：FLAT，跟 skill.knockback 同單位。 */
    static final String STAGE = "stage.knockback";
    /** 嵐棍橫掃：貼身更多 200%、最遠更少 90%。 */
    static final double WIND_BLAST_NEAR = 2.0;
    static final double WIND_BLAST_FAR = -0.9;
    /** 蛇嵐突：對傳奇敵人更多 200%。 */
    static final double SERPENT_UNIQUE_MORE = 2.0;
    /** 蛇嵐突的風蛇：擊退 5 公尺（主突 15 公尺的三分之一）。 */
    static final double SERPENT_SHARE = 5.0 / 15.0;

    private KnockbackMath() {
    }

    /** 要推 metres 公尺的速度。 */
    static double velocityFor(final double metres) {
        return metres <= 0 ? 0 : tw.exilecore.core.Units.metres(metres) / BLOCKS_PER_VELOCITY;
    }

    /** 模擬：水平速度 h、往上 0.3 推出去一共走幾格（測試對照 {@link #BLOCKS_PER_VELOCITY}）。 */
    static double distance(final double velocity) {
        double h = velocity;
        double vy = 0.3;
        double x = 0;
        double y = 0;
        boolean air = true;
        for (int tick = 0; tick < 400 && h > 1e-4; tick++) {
            x += h;
            y += vy;
            if (y <= 0) {
                y = 0;
                air = false;
            }
            if (air) {
                vy = (vy - 0.08) * 0.98;
                h *= 0.91;
            } else {
                vy = 0;
                h *= 0.546;
            }
        }
        return x;
    }

    /** 嵐棍橫掃：離你 distance 格、範圍 limit 格時擊退是原本的幾倍（貼身 3 倍、邊緣 0.1 倍）。 */
    static double windBlast(final double distance, final double limit) {
        final double t = limit <= 0 ? 1 : Math.max(0, Math.min(1, distance / limit));
        return Math.max(0, 1 + WIND_BLAST_NEAR + (WIND_BLAST_FAR - WIND_BLAST_NEAR) * t);
    }
}
