package tw.exilecore.stats;

/**
 * 2026-11-01 照 PoE2（推擊、久地、震眩）：這幾顆輔助照原作改機制用到的屬性鍵（另開一個檔，方便跟其他輔助的修改合併）。
 * 機制：推擊在 {@code tw.exilecore.skill.SupportExtras#onHit}、久地在 {@code tw.exilecore.skill.SkillBuild}（持續時間倍率）、
 * 震眩在 {@code tw.exilecore.combat.HitEffects}／{@code tw.exilecore.ailment.AilmentService#dazeBuildup}。
 */
public final class SupportRuleKeys {

    private SupportRuleKeys() {
    }

    /**
     * 旗標（PoE2 Knockback 的 global_knockback）：被輔助的技能每一下擊中都擊退。技能自己有擊退的（近戰打擊、扇形、突進）照技能自己的方向推，
     * 自己不會擊退的（法術、投射物…）在擊中後從你這邊往外推，距離照 skill.knockback（擊退距離的更多照乘）。
     */
    public static final String KNOCKBACK_ON_HIT = "knockback.on_hit";

    /**
     * INCREASED（PoE2 Persistent Ground 的 ground_effect_duration_+%）：地面效果持續時間。只有產生地面效果的技能掛得上
     * （SupportGuards.GROUND_EFFECT_SKILLS），加在那個技能的持續時間倍率上（跟「技能效果持續時間」同一池增加）。
     */
    public static final String GROUND_DURATION = "ground.duration";

    /**
     * FLAT %（PoE2 Daze 輔助）：擊中造成的物理傷害有這個 % 累積在敵人的眩暈條上（門檻是暈眩門檻，ailments.yml 的 daze），
     * 滿了眩暈（8 秒內受到的暈眩累積更多 50%）。
     */
    public static final String DAZE_BUILDUP = "daze.buildup";
}
