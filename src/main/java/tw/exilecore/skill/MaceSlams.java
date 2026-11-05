package tw.exilecore.skill;

import tw.exilecore.core.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2026-11-01 照 PoE2 0.5（站長「機制沒做的補齊、衝向目標的要鎖敵」）：錘／熊的猛擊技能自己的數值與純邏輯。
 * 執行在 {@link SkillExecutor}（騰躍砸擊、震野雙錘、震野踏、炎脈裂地、熔裂踏）、地裂在 {@link MoltenFissures}。
 * <ul>
 *   <li>騰躍砸擊（Leap Slam）：鎖定準心上／附近的敵人跳到牠身上；落點範圍內一定擊退；用掉完全破甲換更多 500% 暈眩累積；
 *       自己不能破甲；跳超過 3 公尺時，照距離最多再 +0.5 秒攻擊時間。</li>
 *   <li>震野雙錘（Rolling Slam）：先在面前砸一圈 1.4 公尺（擊退、更多 50% 暈眩累積），往前滾 2 公尺再砸一圈 1.8 公尺（傷害是第一下兩倍，
 *       對重暈的敵人再更多 50%）。</li>
 *   <li>震野踏（Furious Slam）：面前一圈 2 公尺的震波，0.5 秒後再往前（偏 20 度內）一圈；狂怒猛擊 3 公尺、每一圈留 4 秒鋸齒地面。</li>
 *   <li>碎脊擊（Boneshatter）：震波由傳奇敵人（含 Boss）的重暈造成時更多 50% 傷害。</li>
 * </ul>
 */
final class MaceSlams {

    private MaceSlams() {
    }

    static final String LEAP = "soaring_smash";
    static final String ROLLING = "field_quake_hammers";
    static final String FURIOUS = "field_quake_stomp";
    static final String EARTHQUAKE = "ground_tremor";
    static final String BONESHATTER_WAVE = "proc_boneshatter_wave";

    // ---- 騰躍砸擊 ----
    /** active_skill_consume_enemy_fully_broken_armour_to_gain_hit_damage_stun_multiplier_+% 500（小數）。 */
    static final double LEAP_CONSUME_STUN_MORE = 5.0;
    /** skill_added_attack_time_by_distance_ms 500、leap_slam_animation_distance_threshold 30（0.1 公尺）。 */
    static final double LEAP_DISTANCE_SECONDS = 0.5;
    static final double LEAP_DISTANCE_THRESHOLD = Units.metres(3);
    /** 鎖敵：準心沒有直接指著敵人時，找準心指的地面這麼多格內最近的敵人。 */
    static final double LEAP_LOCK_RADIUS = Units.metres(3);
    /** leap_slam_always_knockback_within_range：落點範圍內一定擊退（擊退力道；技能自己的擊退比較大照技能的）。 */
    static final double LEAP_KNOCKBACK = 0.6;

    // ---- 熔軀崩擊（2026-11-05 照 PoE2 Molten Crash，焚王獸符賦予）----
    static final String MOLTEN = "molten_dive_crash";
    /** 依跳的距離最多 +0.3 秒（原作「+0.55 秒，依距離最多再 +0.3 秒」；+0.55 寫在 skills.yml 的 skill.added_time）。 */
    static final double MOLTEN_DISTANCE_SECONDS = 0.3;
    /** 第二下在落地後幾 tick。 */
    static final long MOLTEN_SECOND_DELAY = 6;
    /** 落地時用掉幾點怒氣多裂一道（molten_crash 的 consume 10 rage）。 */
    static final int MOLTEN_RAGE = 10;
    /** 熔岩地裂：留 8 秒、同時最多 8 道、分岔 2 道。 */
    static final double MOLTEN_FISSURE_SECONDS = 8;
    static final int MOLTEN_FISSURE_MAX = 8;
    /** 品質：熔岩地裂多幾秒（gem_quality.yml）。 */
    static final String MOLTEN_QUALITY_SECONDS = "molten.fissure_seconds";
    /** 多裂的那一道往旁邊偏幾度。 */
    static final double MOLTEN_EXTRA_ANGLE = 25;

    /** 熔軀崩擊跳了 distance 格多花幾秒：3 公尺內不加，到最遠加 0.3 秒（線性，同騰躍砸擊的算法）。 */
    static double moltenAddedSeconds(final double distance, final double range) {
        return leapAddedSeconds(distance, range) / LEAP_DISTANCE_SECONDS * MOLTEN_DISTANCE_SECONDS;
    }

    // ---- 震野雙錘 ----
    /** 第一下更多 50% 暈眩累積（只有第一下）、第二下對重暈的敵人更多 50% 傷害、兩下中間往前滾 2 公尺（attack_maximum_action_distance_+ 20）。 */
    static final double ROLLING_FIRST_STUN_MORE = 0.5;
    static final double ROLLING_HEAVY_STUNNED_MORE = 0.5;
    static final double ROLLING_STEP = Units.metres(2);
    static final double ROLLING_KNOCKBACK = 0.6;

    // ---- 震野踏 ----
    /** aftershock_delay_ms 500、cascade_aftershock_angle_offset_override 20、cascade_aftershock_spacing_area_%_override 100。 */
    static final long FURIOUS_SECOND_DELAY = 10;
    static final double FURIOUS_ANGLE = 20;
    static final double FURIOUS_SPACING = 1.0;
    /** 狂怒猛擊的鋸齒地面：4 秒（base_skill_effect_duration 4000）。 */
    static final double FURIOUS_JAGGED_SECONDS = 4;

    // ---- 地動 ----
    /** jagged_ground_effect_+% 100：地動的鋸齒地面效果（減速）增加 100%。 */
    static final double EARTHQUAKE_JAGGED_EFFECT = 1.0;

    // ---- 碎脊擊 ----
    /** boneshatter_damage_+%_final_if_created_from_unique 50。 */
    static final double BONESHATTER_UNIQUE_MORE = 0.5;

    /** 這些技能自己處理「打在地裂上引爆」（{@link SkillExecutor#slamUsed}）。 */
    static boolean handlesOwnEruption(final String skillId) {
        return LEAP.equals(skillId) || ROLLING.equals(skillId) || FURIOUS.equals(skillId) || MOLTEN.equals(skillId) || MoltenFissures.is(skillId);
    }

    /** 騰躍砸擊跳了 distance 格（最遠 range 格）多花幾秒：3 公尺內不加，到最遠加 0.5 秒（線性）。 */
    static double leapAddedSeconds(final double distance, final double range) {
        if (distance <= LEAP_DISTANCE_THRESHOLD) {
            return 0;
        }
        final double span = Math.max(1e-6, range - LEAP_DISTANCE_THRESHOLD);
        return LEAP_DISTANCE_SECONDS * Math.min(1, (distance - LEAP_DISTANCE_THRESHOLD) / span);
    }

    /** 震野踏第二圈的中心離第一圈多遠（半徑的倍數）：照 spacing 100%，兩圈剛好相切。 */
    static double furiousOffset(final double radius) {
        return radius * 2 * FURIOUS_SPACING;
    }

    /** 震野踏第二圈偏的角度（弧度）：roll 0～1 對應 -20°～+20°。 */
    static double furiousTurn(final double roll) {
        return Math.toRadians(FURIOUS_ANGLE) * (Math.max(0, Math.min(1, roll)) * 2 - 1);
    }

    /** 碎脊擊震波：傳奇（含 Boss）的重暈造成的更多 50%；雙持時更少 dualLess%（技能自己寫的雙持傷害，負數）。 */
    static double boneshatterWaveFactor(final boolean unique, final double dualWieldPercent) {
        return (unique ? 1 + BONESHATTER_UNIQUE_MORE : 1.0) * Math.max(0, 1 + dualWieldPercent / 100.0);
    }

    /** 技能說明的機制那幾行（{@link SkillService} mechanicLines）：這些技能照自己的做法寫；不是回傳 null。 */
    static List<String> mechanicLines(final SkillBuild build) {
        final String id = build.definition().id();
        final List<String> lines = new ArrayList<>();
        switch (id) {
            case LEAP -> {
                lines.add(String.format(Locale.ROOT, "<gray>鎖定準心上（或準心附近 %.0f 格內）的敵人跳過去，最遠 <white>%.0f</white> 格；沒有敵人就跳到準心指的地方",
                        LEAP_LOCK_RADIUS, build.range()));
                lines.add(String.format(Locale.ROOT, "<gray>落地半徑 <white>%.1f</white> 格，範圍內的敵人一定被擊退", build.radius()));
                lines.add("<gray>打中完全破甲的敵人時用掉破甲，這一下暈眩累積更多 <white>500%</white>；這個技能不能破甲");
                lines.add(String.format(Locale.ROOT, "<gray>跳超過 %.0f 格時照距離多花最多 <white>%.1f</white> 秒（不吃攻擊速度）", LEAP_DISTANCE_THRESHOLD, LEAP_DISTANCE_SECONDS));
            }
            case ROLLING -> {
                final SkillParams.Aftershock second = build.params().aftershock();
                final double first = build.length();
                lines.add(String.format(Locale.ROOT, "<gray>第一擊：面前一圈半徑 <white>%.1f</white> 格，擊退、暈眩累積更多 50%%", first));
                if (second != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>第二擊：往前滾 %.0f 格，<white>%.1f</white> 秒後再砸一圈半徑 <white>%.1f</white> 格，更多 <white>%d%%</white> 傷害；對重暈的敵人再更多 50%%",
                            ROLLING_STEP, second.delay(), first * second.radius(build.level()), Math.round(second.more(build.level()))));
                }
            }
            case FURIOUS -> {
                lines.add(String.format(Locale.ROOT, "<gray>面前一圈半徑 <white>%.1f</white> 格的震波，%.1f 秒後再往前一圈（偏 %.0f° 內）", build.length(),
                        FURIOUS_SECOND_DELAY / 20.0, FURIOUS_ANGLE));
                lines.add(String.format(Locale.ROOT, "<gray>狂怒猛擊：震波半徑 ×%.1f，每一圈留下 %.0f 秒的鋸齒地面（減速）", StageAttacks.ENRAGED_AREA, FURIOUS_JAGGED_SECONDS));
            }
            case MoltenFissures.VOLCANIC -> {
                final SkillParams.Zone zone = build.params().zone();
                lines.add(String.format(Locale.ROOT, "<gray>往前裂出一道 <white>%.1f</white> 格長、半徑 <white>%.1f</white> 格的地裂（每秒延伸 %.0f 格），中間往左右各岔一道；延伸時打中路上的敵人",
                        build.length(), build.radius(), Units.metres(MoltenFissures.SPEED_METRES)));
                lines.add(String.format(Locale.ROOT, "<gray>地裂留 <white>%.1f</white> 秒、同時最多 <white>%d</white> 道；用別的猛擊打在地裂上會沿著地裂擴散餘震",
                        zone.duration() * build.durationScale(), zone.max()));
            }
            case MoltenFissures.MOUNTAIN -> {
                lines.add(String.format(Locale.ROOT, "<gray>每捶一下往前方 %.0f° 內隨機的方向裂出一道 <white>%.1f</white> 格長、半徑 <white>%.1f</white> 格的地裂，延伸時打中路上的敵人",
                        MoltenFissures.MOUNTAIN_ARC, Units.metres(MoltenFissures.MOUNTAIN_LENGTH_METRES) * build.areaScale(), build.radius()));
                lines.add(String.format(Locale.ROOT, "<gray>地裂留 <white>%.1f</white> 秒、同時最多 <white>%d</white> 道；用別的猛擊打在地裂上會沿著地裂擴散餘震",
                        MoltenFissures.MOUNTAIN_SECONDS * build.durationScale(), MoltenFissures.MOUNTAIN_LIMIT));
            }
            default -> {
                return null;
            }
        }
        return lines;
    }
}
