package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2（PoB2 skillTypes CreatesFissure）：冰脊裂（Glacial Cascade）、熔裂踏（Fury of the Mountain）、雷浪（Storm Wave）、
 * 回旋鍛錘（Forge Hammer）也是裂地技能——岔裂（分岔）、隆起（多裂開）、狂裂（Kaom's Madness）、分裂地面（機率多一道）作用在它們自己的裂縫上：
 * <ul>
 *   <li>冰脊裂：裂縫就是整排冰稜（扇形，含最後一根大冰稜），多的裂縫是整排冰稜偏轉一個角度（SkillExecutor 扇形照舊）。</li>
 *   <li>雷浪：裂縫就是前方 8 公尺的閃電裂隙（SkillExecutor.stormWave，瞬間判定），多的裂縫是轉過去的另一道裂隙；
 *       分岔從裂隙上岔出去，長度、寬度照分岔的範圍縮短。</li>
 *   <li>熔裂踏：每捶一下往前方 120 度內隨機一個方向裂出一道熔岩地裂（MoltenFissures，7 公尺、12 秒、最多 12 道）；多的、分岔的裂縫從這一道轉出去。</li>
 *   <li>回旋鍛錘：裂縫只有戰吼震碎鍛錘的熔岩裂地（擲出去、收回不是裂縫）；多的裂縫加進螺旋的道數（照原作 base_number_of_fissures 加上去），
 *       分岔從每一道裂地上岔出去（StageLeftovers）；留 8 秒、最多 10 道（MoltenFissures）。</li>
 * </ul>
 * 純邏輯是 static（單元測試）。
 */
final class FissureSkills {

    static final String GLACIAL = "ice_ridge_rift";
    static final String FURY = "molten_crack_stomp";
    static final String STORM_WAVE = "thunder_surge";
    static final String FORGE = StageLeftovers.FORGE;

    /**
     * 熔裂踏一道裂縫多長（格）。PoB2／poe2db 都沒有長度的數字；照炎脈裂地（同一種 Molten Fissure）1 級的長度 7 公尺（{@link MoltenFissures#MOUNTAIN_LENGTH_METRES}）。
     * 寬度是技能半徑（skills.yml 的 nova.radius 1.0 = PoB2 active_skill_base_area_of_effect_radius 10）。
     */
    static final double FURY_LENGTH = tw.exilecore.core.Units.metres(MoltenFissures.MOUNTAIN_LENGTH_METRES);
    /** 熔裂踏同時最多幾道裂縫（fury_of_the_mountain_base_maximum_number_of_fissures 12）。 */
    static final int FURY_MAX = MoltenFissures.MOUNTAIN_LIMIT;
    /** 熔裂踏的裂縫留幾秒（base_skill_effect_duration 12000）。 */
    static final double FURY_SECONDS = MoltenFissures.MOUNTAIN_SECONDS;
    /** 熔裂踏的裂縫往前方幾度的扇面裡隨機（bear_fury_of_the_mountain_front_facing_cone_angle 120）。 */
    static final double FURY_ARC = 120;
    /** 回旋鍛錘的裂地留幾秒（Fissures 的 base_skill_effect_duration 8000；skills.yml 的 stage duration 沒寫時用）。 */
    static final double FORGE_SECONDS = 8;
    /** 回旋鍛錘的裂地同時最多幾道（forge_hammer_base_maximum_number_of_fissures 10）。 */
    static final int FORGE_MAX = 10;
    /** 分岔偏幾度（同 SkillExecutor 的分岔）。 */
    static final double BRANCH_DEGREES = 40;

    private FissureSkills() {
    }

    /**
     * 雷浪的裂縫（前方的閃電裂隙，{@link StormWave}）：雷浪、帶 fissure 標籤。不是回旋鍛錘（鍛錘的裂縫是戰吼震碎的熔岩裂地）。
     * 2026-11-01 雷浪改成瞬間的裂隙（扇形機制）之後不再看是不是投射物；這次之前是「帶 fissure 標籤的投射物」。
     */
    static boolean wave(final SkillBuild build) {
        return wave(build.definition());
    }

    static boolean wave(final SkillDefinition skill) {
        return STORM_WAVE.equals(skill.id()) && skill.tags().contains(tw.exilecore.stats.KeystoneKeys.FISSURE);
    }

    /** 熔裂踏這一下的裂縫偏離面向幾弧度：random01（0–1）平均落在前方 {@link #FURY_ARC} 度裡。 */
    static double furyTurn(final double random01) {
        return Math.toRadians(FURY_ARC) * (Math.max(0, Math.min(1, random01)) - 0.5);
    }

    /** 分岔的長度、寬度倍率：範圍更少 areaLess%（面積），長度開根號（同 SkillExecutor 的分岔）。 */
    static double branchLinear(final double areaLess) {
        return Math.sqrt(Math.max(0.1, 1 - areaLess / 100.0));
    }

    /** 回旋鍛錘一次震碎裂出幾道：原本的、隆起多的、狂裂多的（最多 6）、分裂地面擲中多一道。 */
    static int forgeCount(final int base, final int additional, final int kaom, final boolean chance) {
        return Math.max(1, base) + Math.max(0, additional) + Math.max(0, Math.min(6, kaom)) + (chance ? 1 : 0);
    }

    /** 平面上把 (x, z) 轉 angle 弧度（同 SkillExecutor.turnedFissure 的方向）。 */
    static double[] rotate(final double x, final double z, final double angle) {
        return new double[] {x * Math.cos(angle) - z * Math.sin(angle), x * Math.sin(angle) + z * Math.cos(angle)};
    }

    /**
     * 回旋鍛錘的一道螺旋裂地（ray：前半段方向 [0][1]、後半段方向 [2][3]，總長 length）上，比例 t（0–1）那一點離起點的位移、
     * 和那一段的方向：{dx, dz, dirX, dirZ}。
     */
    static double[] alongSpiral(final double[] ray, final double length, final double t) {
        final double d = Math.max(0, Math.min(1, t)) * length;
        final double half = length / 2;
        if (d <= half) {
            return new double[] {ray[0] * d, ray[1] * d, ray[0], ray[1]};
        }
        return new double[] {ray[0] * half + ray[2] * (d - half), ray[1] * half + ray[3] * (d - half), ray[2], ray[3]};
    }
}
