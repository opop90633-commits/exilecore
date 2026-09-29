package tw.exilecore.stats;

/**
 * 一次命中的計算結果。
 *
 * @param hit        是否命中（攻擊擲命中失敗 = false）
 * @param blocked    是否被格擋
 * @param crit       是否暴擊
 * @param outgoing   攻擊方打出的傷害（暴擊後、減免前）
 * @param dealt      減免後實際造成的傷害
 * @param esDamage   由能量護盾吸收的部分
 * @param lifeDamage 扣到生命的部分
 */
public record HitResult(boolean hit, boolean blocked, boolean crit,
                        DamagePacket outgoing, DamagePacket dealt,
                        double esDamage, double lifeDamage) {

    public static HitResult miss() {
        return new HitResult(false, false, false, new DamagePacket(), new DamagePacket(), 0, 0);
    }

    public static HitResult block() {
        return new HitResult(true, true, false, new DamagePacket(), new DamagePacket(), 0, 0);
    }

    public double total() {
        return esDamage + lifeDamage;
    }

    /** 給除錯訊息用。 */
    public String describe() {
        if (!hit) {
            return "未命中";
        }
        if (blocked) {
            return "被格擋";
        }
        return (crit ? "暴擊！" : "") + Math.round(total()) + "（" + dealt + "）"
                + (esDamage > 0 ? "，護盾吸收 " + Math.round(esDamage) : "");
    }
}
