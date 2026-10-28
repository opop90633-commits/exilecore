package tw.exilecore.skill;

/**
 * 2026-10-28 照 PoE2 0.5 Whirling Assault（迴旋棍步）：「一路前進，用一連串旋轉的打擊攻擊身邊的敵人」（純邏輯，不依賴 Bukkit）。
 * <ul>
 *   <li>整個動作時間（攻擊時間 + 1 秒，{@code skill.added_time}）裡照直線往前走 {@code range} 格（RePoE attack_maximum_action_distance_+ 42 → 4.2 米），
 *       落點照 {@link DashPath}（被牆擋住就停在牆前、可以爬上 1 格、走下 3 格）。</li>
 *   <li>沿路轉 {@code spins} 圈：第 i 圈（0 起算）在動作時間的 (i + 0.5) / spins 那一刻、走到同樣比例的位置時打一圈。
 *       每一圈是一個獨立的打擊範圍（PoE2 0.5「旋風突擊移動時會產生多個傷害範圍」：濺射每個範圍各算一次）。
 *       RePoE／poe2db 沒有寫幾圈（由動畫決定；論壇：「不管加多少攻速，打的次數都一樣」），這裡取 4 圈。</li>
 *   <li>每一圈的半徑 = 長棍的近戰打擊距離 1.4 米（RePoE Warstaff range 14）+ 寶石的 +1.4 米 = 2.8 格，再乘範圍（每級更多 1%）。</li>
 *   <li>被凍結、重暈、電擊（不能行動）就整招停下來；被釘住（定身）只是不再往前走，照樣轉圈打。</li>
 * </ul>
 * 這次之前迴旋棍步是瞬移 7 格、順路一條寬 1.4 格的線打一下並擊退 0.3。
 */
public final class WhirlingAssault {

    private WhirlingAssault() {
    }

    /** 整個動作至少幾 tick（攻速再快也要能轉完）。 */
    public static final int MIN_TICKS = 4;

    /** 動作時間 total tick 裡第 i 圈在第幾 tick 打（0 起算、遞增、都落在 [1, total]）。 */
    public static int spinTick(final int index, final int spins, final int total) {
        final int ticks = Math.max(MIN_TICKS, total);
        final int at = (int) Math.round(ticks * fraction(index, spins));
        return Math.max(1, Math.min(ticks, at));
    }

    /** 第 i 圈在整段的哪個比例（時間、位置都一樣）：(i + 0.5) / spins。 */
    public static double fraction(final int index, final int spins) {
        if (spins <= 0) {
            return 1;
        }
        final int clamped = Math.max(0, Math.min(spins - 1, index));
        return (clamped + 0.5) / spins;
    }

    /** 第 tick 個 tick 走到整段的哪個比例（直線、等速，走完整個動作時間剛好到落點）。 */
    public static double progress(final int tick, final int total) {
        final int ticks = Math.max(MIN_TICKS, total);
        return Math.max(0, Math.min(1, tick / (double) ticks));
    }
}
