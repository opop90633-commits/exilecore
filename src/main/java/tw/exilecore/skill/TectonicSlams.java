package tw.exilecore.skill;

import java.util.List;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2 Tectonic Slams（雙震；Path of Building 0.5 SupportSlamAftershocksPlayer）：
 * <ul>
 *   <li>number_of_dual_cascade_aftershocks 1：「Supported Skills cause 1 additional Aftershock ahead and to each side of the initial area」——
 *       猛擊的同時，在原本那一下的範圍前方左右兩側各再震一次餘震（{@link #sideCentres}：從原本範圍的中心往面向的左右各 {@value #SIDE_DEGREES}° 偏、
 *       距離＝原本範圍的半徑，兩邊各一個，跟猛擊同時）；</li>
 *   <li>餘震＝那一下的重複（PoE2 的餘震「在同一個範圍造成同樣的傷害」：含輔助，範圍照原本那一下，{@link #initialArea}）；</li>
 *   <li>aftershock_damage_+%_final_from_support_dual_cascade_aftershocks −45：被輔助的技能的餘震傷害更少 45%（{@link #DAMAGE_LESS}）；</li>
 *   <li>aftershock_area_of_effect_+%_final_from_support_dual_cascade_aftershocks −30：被輔助的技能的餘震範圍更少 30%（{@link #AREA_LESS}；
 *       範圍效果，半徑照開根號）；</li>
 *   <li>support_dual_cascade_aftershocks_attack_speed_+%_final −15：攻擊速度更慢 15%（supports.yml 的 attack.speed MORE）。</li>
 * </ul>
 * 「更少傷害、更少範圍」照原作寫的是被輔助的技能的所有餘震：兩側的這兩下、餘震（Aftershock）與連震（Tremors）輔助觸發的、
 * 地動（Earthquake）自己的鋸齒地面餘震、鑄父之錘的餘震都算（{@link #AFTERSHOCKS}；SkillExecutor 的猛擊餘震）。
 * 這次之前雙震是一圈自己半徑（2～2.5 格）的側震、落在猛擊面前 2.5 格（不照原本那一下的範圍）、傷害 55%、範圍沒有更少、其他餘震不受影響。
 * 純函式，不依賴 Bukkit。
 */
final class TectonicSlams {

    /** 雙震觸發的隱藏技能（skills.yml）。 */
    static final String PROC = "proc_side_aftershock";
    /** FLAT：被輔助的技能的餘震傷害更少 x%（雙震 45）。 */
    static final String DAMAGE_LESS = "aftershock.damage_less";
    /** FLAT：被輔助的技能的餘震範圍更少 x%（雙震 30）。 */
    static final String AREA_LESS = "aftershock.area_less";
    /** 兩側的餘震從面向往左右偏幾度（原作寫「前方、兩側」，沒有給角度；我們取 45°）。 */
    static final double SIDE_DEGREES = 45;
    /** 算「餘震」的隱藏技能：餘震（Aftershock）、連震（Tremors）、雙震的兩側。 */
    static final Set<String> AFTERSHOCKS = Set.of("proc_aftershock", tw.exilecore.stats.SupportRemakeKeys.TREMOR_PROC, PROC);

    private TectonicSlams() {
    }

    /** 傷害倍率（更少 less%；最少 0）。 */
    static double damageFactor(final double less) {
        return less <= 0 ? 1.0 : Math.max(0, 1 - less / 100.0);
    }

    /** 半徑倍率（範圍更少 less%：半徑乘開根號；範圍最少剩 5%，跟 SkillBuild 的範圍倍率一樣）。 */
    static double radiusFactor(final double less) {
        return less <= 0 ? 1.0 : Math.sqrt(Math.max(0.05, 1 - less / 100.0));
    }

    /**
     * 原本那一下的範圍：atAim＝中心在技能的落點（重擊、區域：onUse 給的位置），不然在你腳下往面向前 forward 格；radius＝半徑（0 = 不知道）。
     */
    record Area(boolean atAim, double forward, double radius) {
    }

    /** 照被輔助的猛擊算原本那一下的範圍（新星、跳躍：腳下；扇形：前方一半長度、半徑一半長度；位移：終點那一圈；重擊、區域：落點）。 */
    static Area initialArea(final SkillBuild parent) {
        if (parent == null) {
            return new Area(true, 0, 0);
        }
        final Mechanic mechanic = parent.definition().mechanic();
        return switch (mechanic) {
            case NOVA, LEAP -> new Area(false, 0, parent.radius());
            case CONE -> new Area(false, parent.length() / 2, parent.length() / 2);
            case DASH -> new Area(false, 0, parent.params().dash() == null || parent.params().dash().endRadius() == null ? 0
                    : parent.scaledRadius(parent.params().dash().endRadius()));
            case IMPACT, ZONE -> new Area(true, 0, parent.radius());
            default -> new Area(true, 0, 0);
        };
    }

    /**
     * 兩側餘震的中心（相對原本範圍的中心 {dx, dz}）：面向 (fx, fz)（水平，會先正規化）往左右各轉 {@value #SIDE_DEGREES}°、距離 distance。
     * 兩個各在一側（一個往順時針轉、一個往逆時針轉）。
     */
    static List<double[]> sideCentres(final double fx, final double fz, final double distance) {
        double x = fx;
        double z = fz;
        final double length = Math.sqrt(x * x + z * z);
        if (length < 1e-9) {
            x = 0;
            z = 1;
        } else {
            x /= length;
            z /= length;
        }
        final double angle = Math.toRadians(SIDE_DEGREES);
        final double cos = Math.cos(angle);
        final double sin = Math.sin(angle);
        // 繞 y 軸轉 ±angle（跟 Bukkit Vector.rotateAroundY 同一個方向：x' = x cos + z sin、z' = −x sin + z cos）
        final double[] first = {(x * cos + z * sin) * distance, (-x * sin + z * cos) * distance};
        final double[] second = {(x * cos - z * sin) * distance, (x * sin + z * cos) * distance};
        return List.of(first, second);
    }

    /** 這個餘震的技能照「更少傷害、更少範圍」換掉（都沒有原樣回傳）。 */
    static SkillBuild lessened(final SkillBuild build, final double damageLess, final double areaLess) {
        SkillBuild result = build;
        if (damageLess > 0) {
            result = result.withProfile(result.profile().scaled(damageFactor(damageLess), "雙震"));
        }
        if (areaLess > 0) {
            result = result.withAreaAndProjectiles(result.areaScale() * radiusFactor(areaLess), result.projectiles());
        }
        return result;
    }

    /** 兩側餘震的技能：半徑換成原本那一下的半徑（radius ≤ 0 或這個技能沒有半徑就照它自己的），再照更少範圍、更少傷害。 */
    static SkillBuild sideBuild(final SkillBuild sub, final double radius, final double damageLess, final double areaLess) {
        SkillBuild result = sub;
        if (radius > 0 && sub.radius() > 1e-9) {
            result = result.withAreaAndProjectiles(sub.areaScale() * radius / sub.radius(), sub.projectiles());
        }
        return lessened(result, damageLess, areaLess);
    }

    /** SkillExecutor 的猛擊自己的餘震（地動的鋸齒地面、千鈞砸、鑄父之錘）：照技能身上的「餘震更少傷害」換掉這一下的描述。 */
    static tw.exilecore.stats.SkillProfile aftershockProfile(final tw.exilecore.stats.SkillProfile profile) {
        final double less = SkillZones.localFlat(profile, DAMAGE_LESS);
        return less > 0 ? profile.scaled(damageFactor(less), "雙震") : profile;
    }

    /** 同上：餘震的半徑倍率。 */
    static double aftershockRadius(final tw.exilecore.stats.SkillProfile profile) {
        return radiusFactor(SkillZones.localFlat(profile, AREA_LESS));
    }
}
