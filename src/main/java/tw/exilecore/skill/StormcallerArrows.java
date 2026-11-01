package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2 Stormcaller Arrow（召霆矢，PoB2 StormcallerArrowPlayer）：
 * <ul>
 *   <li>箭（statset Arrow）：真的投射物（每秒 54 格）飛向準心，打中路上碰到的敵人就停、沒碰到落在準心；落雷照箭落下的地方。自己的傷害表 20% → 62% → 166%、80% 轉閃電，
 *       沒有落雷那段的感電加成（skills.yml 的 stages.arrow）；</li>
 *   <li>落雷（statset Bolt）：1.5 秒後打一圈、感電機率更多 500%、感電「當作傷害更多 50%」（active_skill_shock_as_though_damage_+%_final 50）；
 *       落雷讓任何一個敵人感電時，落點 secondary radius（1.5 → 3.0 → 3.7 公尺，吃範圍）內所有敵人都套上同樣的感電。</li>
 * </ul>
 * 純函式與屬性代號，不依賴 Bukkit。這次之前只有落雷、沒有箭的那一下、沒有感電擴散與「當作傷害」。
 */
public final class StormcallerArrows {

    /** skills.yml 的 stages：箭的那一下。 */
    public static final String ARROW = "arrow";
    /** FLAT（level_modifiers，格）：落雷讓敵人感電時，落點多遠內的敵人都感電（0 = 不擴散）。 */
    public static final String SPREAD_RADIUS = "shock.spread_radius";
    /** FLAT（落雷那一下的 profile 自己帶，已經乘過範圍）：感電擴散的半徑（SkillZones 讀）。 */
    public static final String SPREAD_SCALED = "shock.spread_radius_scaled";
    /** FLAT（%）：感電機率照「傷害更多 x%」算（AilmentService：感電的基礎傷害 × (1 + x ÷ 100)）。 */
    public static final String SHOCK_AS_THOUGH = "shock.as_though_damage";

    /** 2026-11-01 照 poe2db Stormcaller Arrow（Projectile Speed: 54 metres per Second）：箭每 tick 飛幾格（1 公尺 = 1 格）。 */
    public static final double SPEED = 54.0 / 20.0;

    /** 箭的速度：{@link #SPEED} × 投射物速度倍率（夾在 0.2～6 格／tick，跟一般投射物一樣）。 */
    public static double speed(final double projectileSpeedScale) {
        return Math.max(0.2, Math.min(6, SPEED * Math.max(0.1, projectileSpeedScale)));
    }

    /** 只屬於落雷那一段的修飾器（箭的那一下拿掉）。 */
    static final Set<String> BOLT_ONLY = Set.of(StatKeys.SHOCK_CHANCE, SPREAD_RADIUS, SPREAD_SCALED, SHOCK_AS_THOUGH);

    private StormcallerArrows() {
    }

    /** 感電機率用的傷害倍率：1 + x ÷ 100（負的當 0）。 */
    public static double asThough(final double percent) {
        return 1 + Math.max(0, percent) / 100.0;
    }

    /** 感電擴散的半徑：等級的半徑 × 範圍的倍率（areaScale 是半徑倍率）。 */
    public static double spreadRadius(final double base, final double areaScale) {
        return base <= 0 ? 0 : base * Math.max(0, areaScale);
    }

    /** 箭的那一下：拿掉只屬於落雷的修飾器（感電機率更多 500%、當作傷害、擴散），不算範圍傷害。 */
    static SkillProfile arrowOnly(final SkillProfile stage) {
        final List<Modifier> kept = new ArrayList<>();
        for (final Modifier modifier : stage.local()) {
            if (!BOLT_ONLY.contains(modifier.stat())) {
                kept.add(modifier);
            }
        }
        final Set<String> tags = new java.util.HashSet<>(stage.tags());
        tags.remove(tw.exilecore.stats.Tags.AREA);
        return new SkillProfile(stage.id(), tags, stage.effectiveness(), stage.spellDamage(), stage.spellDamageMax(), stage.conversion(), kept);
    }
}
