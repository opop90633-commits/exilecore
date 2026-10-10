package tw.exilecore.skill;

import java.util.Locale;

/**
 * 技能的運作方式（設計文件「技能腳本引擎」）。每種在 skills.yml 裡有同名的參數區塊。
 * 圖騰、陷阱、召喚物、光環與戰吼在後面的批次加。
 */
public enum Mechanic {
    /** 近戰打擊：前方一定角度與距離內的一個或全部敵人。 */
    STRIKE,
    /** 投射物：箭（原版箭矢）或魔法彈（粒子），可以多發、爆炸、亂竄、拋物線（榴彈）。 */
    PROJECTILE,
    /** 以自己為中心的圓形範圍（可以有餘震）。 */
    NOVA,
    /** 前方扇形範圍（崩土擊）。 */
    CONE,
    /** 跳到前方，落地時打周圍一圈。 */
    LEAP,
    /** 打中前方一個敵人，再連鎖跳到附近的敵人（鏈雷）。 */
    CHAIN,
    /** 位移：往面向的方向瞬間移動一段距離，可以順路打敵人、起點終點爆開、留下一道地面效果。 */
    DASH,
    /** 延遲落下：準心指的地方過一小段時間後炸開一圈（萬箭齊落、隕星）。 */
    IMPACT,
    /** 地面效果：在準心指的地方（或自己腳下、面前一道）留下一塊會持續傷害的區域（霜嵐、焰幕、虛空裂隙）。 */
    ZONE,
    /** 印記：對準心指的一個敵人施加減益（受到的傷害增加）。 */
    MARK,
    /** 骸爆：引爆準心附近最近的一具屍體（剛死的怪物），對周圍造成技能傷害加上屍體最大生命一定比例的火焰傷害。 */
    CORPSE,
    /** 召喚物：叫出跟著你、幫你打怪的生物（殭屍、骷髏、焚魂、魔像）；細節在 skills.yml 的 summon 區塊（{@link SummonSpec}）。 */
    SUMMON,
    /** 光環：開著就一直有效（保留一部分最大魔力），自己、範圍內的隊友與召喚物都得到效果；放在技能面板上就開著（{@link AuraSpec}）。 */
    AURA,
    /** 戰吼：瞬間吼一聲——回復生命、自己與隊友得到幾秒增益、接下來幾次攻擊更痛、範圍內的敵人受到的傷害增加並被嘲諷（{@link WarcrySpec}）。 */
    WARCRY,
    /** 瞬影：瞬移到準心指的敵人身旁（沒指到就找附近最近的）打一下（瞬影擊）。只有玩家本人能放（圖騰、陷阱、雙詠不行）。 */
    FLICKER,
    /** 號召（2026-10）：把自己所有的召喚物瞬間叫回腳邊（不造成傷害）。 */
    RALLY,
    /** 褻瀆（2026-10）：在準心的地方從地底拖出幾具屍體（給骸爆、喚屍用；不造成傷害）。 */
    DESECRATE,
    /** 舉盾（2026-10）：要拿盾；一段時間內自己得到增益（格擋、受到的傷害更少…；寫在 guard.effects），移動變慢。 */
    GUARD;

    /** skills.yml 裡的名稱（小寫）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Mechanic parse(final String where, final String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "：mechanic 不認得「" + text
                    + "」（可用 strike、projectile、nova、cone、leap、chain、dash、impact、zone、mark、corpse、summon、aura、warcry、flicker）");
        }
    }
}
