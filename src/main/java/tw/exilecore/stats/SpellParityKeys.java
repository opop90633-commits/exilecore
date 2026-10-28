package tw.exilecore.stats;

/**
 * 2026-10-28 照 PoE2：非攻擊技能補做的機制用到的屬性鍵（天柱雷的追加雷、印記的感電／凍結累積）。
 * 跟 {@link StatKeys} 分開放，免得大家都改同一個檔。
 */
public final class SpellParityKeys {

    private SpellParityKeys() {
    }

    /**
     * FLAT（寫在 level_modifiers，逐級）：範圍內有感電中的敵人時，多劈幾道雷，隨機分給這一下打到的敵人
     * （PoE2 Lightning Conduit：1 → 2 道）。
     */
    public static final String SHOCKED_BOLTS = "skill.shocked_bolts";

    /**
     * INCREASED（印記放在敵人身上）：擊中這個敵人時電擊累積增加（PoE2 Voltaic Mark：25% → 35% → 45%），
     * 跟攻擊者的「增加電擊累積」加在一起。
     */
    public static final String ELECTROCUTE_BUILDUP_TAKEN = "electrocute.buildup_taken";

    /**
     * FLAT（印記）：印記被觸發（引雷印：被電擊；霜縛印：被凍結）時，放印記的人獲得「x% 傷害為額外閃電／冰冷傷害」的增益，
     * 印記用掉（PoE2：30%、10 秒）。值是百分比；增益的元素看印記效果（電擊累積 → 閃電、凍結累積 → 冰冷）。
     */
    public static final String MARK_ACTIVATION_GAIN = "mark.activation_gain";

    /** FLAT（印記）：印記被觸發後給的增益持續幾秒（PoE2：10 秒）。 */
    public static final String MARK_ACTIVATION_SECONDS = "mark.activation_seconds";
}
