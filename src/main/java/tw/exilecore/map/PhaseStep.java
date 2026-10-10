package tw.exilecore.map;

/**
 * 地圖 Boss 換階段的規則（純邏輯，不依賴 Bukkit）：一次只換一個階段、兩個階段之間至少隔 {@link #GAP_TICKS}、
 * 護盾還在時不換。一下打掉一大段血（一次跨過好幾個門檻）時，召喚、護盾、加傷害、新的招式一個階段一個階段來，
 * 不會同時疊上來（以前兩個階段的小怪、護盾、加傷害會在同一個瞬間全部出現）。
 * 等下一個階段的這段時間 Boss 打不動（{@link Step#WAIT}），不會一口氣打過去、把下一個階段的護盾跳過。
 */
final class PhaseStep {

    /** 兩個階段之間至少隔幾 tick（3 秒）。 */
    static final long GAP_TICKS = 3 * 20;
    /** 還沒換過階段（剛生出來、重置後）。 */
    static final long NEVER = Long.MIN_VALUE / 4;

    /** 這次檢查的結果。 */
    enum Step {
        /** 血量還沒到下一個門檻（或護盾還在）：照常打。 */
        NONE,
        /** 血量到了，但上一個階段才剛開始：等一下，這段時間 Boss 打不動。 */
        WAIT,
        /** 進下一個階段。 */
        ENTER
    }

    private PhaseStep() {
    }

    /**
     * 這次檢查要怎麼做。
     *
     * @param nextAt        下一個階段的血量門檻（例如 0.66）
     * @param fraction      Boss 現在的血量比例
     * @param lastPhaseTick 上一次換階段的 tick（{@link #NEVER} = 還沒換過）
     * @param shielded      上一個階段的護盾還在（Boss 無敵中，護盾自己處理）
     */
    static Step step(final double nextAt, final double fraction, final long now, final long lastPhaseTick, final boolean shielded) {
        if (fraction > nextAt || shielded) {
            return Step.NONE;
        }
        return now - lastPhaseTick >= GAP_TICKS ? Step.ENTER : Step.WAIT;
    }
}
