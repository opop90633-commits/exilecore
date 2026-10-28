package tw.exilecore.skill;

/**
 * 2026-10-28 照 PoE2 0.5 Lightning Warp（雷遁；poe2db）：除了敵人，也能瞄準自己的滾雷珠（Ball Lightning）投射物。
 * <ul>
 *   <li>瞄準滾雷珠 → 瞬移到雷珠那裡，雷珠在同一個爆炸（周圍 3 格閃電傷害）裡消失，照樣獲得一層閃電灌注；
 *       <b>不</b>留感電地面（原作 0.2.0 起只有瞄準敵人才留）。</li>
 *   <li>目標優先（原作 0.5.0 修正）：可以撲殺的敵人、滾雷珠都比「準心只是指著、還不能撲殺的敵人」優先。
 *       可以撲殺的敵人跟滾雷珠都在準心上時，挑沿著準心比較近的那個（等於滑鼠先碰到誰就是誰）；一樣近就算敵人
 *       （撲殺還多殺一隻、雷珠留著繼續電）。兩個都沒有才對準心指著的那隻（還不能撲殺）掛標記。</li>
 * </ul>
 * 這次之前只能瞄準敵人，而且只看準心第一個碰到的敵人（前面擋著一隻還不能撲殺的，後面那隻可以撲殺的就瞄不到）。
 */
public final class LightningWarp {

    /** 滾雷珠的技能代號。 */
    static final String BALL = "ball_lightning";
    /** 找滾雷珠時離準心射線幾格內算指著（跟敵人的 1.5 格一樣）。 */
    static final double BALL_TOLERANCE = 1.5;

    private LightningWarp() {
    }

    /** 這一下雷遁的目標種類。 */
    enum Pick {
        /** 可以撲殺的敵人：瞬移進去炸開。 */
        CULL,
        /** 自己的滾雷珠：瞬移過去、雷珠在爆炸裡消失。 */
        BALL,
        /** 還不能撲殺的敵人：掛標記，掉到門檻自動瞬移。 */
        MARK,
        /** 什麼都沒指著。 */
        NONE
    }

    /**
     * 挑目標。參數是沿著準心的距離（格），null = 準心上沒有這種東西。
     *
     * @param cullAlong 準心上最近一隻可以撲殺的敵人
     * @param ballAlong 準心上最近一顆自己的滾雷珠
     * @param anyEnemy  準心上有沒有敵人（不管能不能撲殺）
     */
    static Pick choose(final Double cullAlong, final Double ballAlong, final boolean anyEnemy) {
        if (cullAlong != null && (ballAlong == null || cullAlong <= ballAlong)) {
            return Pick.CULL;
        }
        if (ballAlong != null) {
            return Pick.BALL;
        }
        return anyEnemy ? Pick.MARK : Pick.NONE;
    }

    /**
     * 2026-10-28 照 PoE2：主要寶石觸發的雷遁挑目標（不看準心）。觸發的目標在範圍內就對牠（撲殺得到 → 瞬移，不然標記）；
     * 沒有目標就找範圍內撲殺得到的、再來任何一隻（標記）；都沒有回傳 NONE（照準心找滾雷珠）。
     *
     * @param preferred          觸發的目標（暴擊打到的那隻）在範圍內
     * @param preferredCullable  那隻撲殺得到
     * @param cullNear           沒有觸發的目標時，範圍內有撲殺得到、看得到的敵人
     * @param anyNear            沒有觸發的目標時，範圍內有看得到的敵人
     */
    static Pick chooseTriggered(final boolean preferred, final boolean preferredCullable, final boolean cullNear, final boolean anyNear) {
        if (preferred) {
            return preferredCullable ? Pick.CULL : Pick.MARK;
        }
        if (cullNear) {
            return Pick.CULL;
        }
        return anyNear ? Pick.MARK : Pick.NONE;
    }

    /**
     * 2026-10-28 照 PoE2：法術圖騰放的雷遁挑目標。原作的圖騰只對撲殺範圍內的敵人放雷遁（官方論壇 Early Access Bug Reports
     * 「Spell Totem Lightning Warp Targeting Issue」：totems only cast it on enemies in culling range、不瞄準滾雷珠；
     * 「Lightning warp + spell totems interaction」：圖騰掛不上標記），撲殺得到 → 圖騰自己瞬移進去炸開；
     * 沒有撲殺得到的 → NONE（圖騰換放孔裡別的法術，只有雷遁就等）。這次之前雷遁不能插進星咒圖騰。
     *
     * @param cullNear 圖騰範圍內有撲殺得到、看得到的敵人
     */
    static Pick chooseTotem(final boolean cullNear) {
        return cullNear ? Pick.CULL : Pick.NONE;
    }

    /** 這種目標要不要留感電地面：瞄準敵人才留，瞄準滾雷珠不留（原作 0.2.0）。 */
    static boolean shockedGround(final Pick pick) {
        return pick == Pick.CULL || pick == Pick.MARK;
    }
}
