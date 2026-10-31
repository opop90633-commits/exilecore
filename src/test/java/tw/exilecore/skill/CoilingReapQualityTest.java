package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.GrantedSkillKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5：纏蝕箭的分裂、詛咒連鎖與品質，血鐮的暴擊弱點持續時間與品質。 */
class CoilingReapQualityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static Modifier mod(final String skill, final String stat) {
        return config.skill(skill).modifiers().stream().filter(m -> m.stat().equals(stat)).findFirst()
                .orElseThrow(() -> new AssertionError(skill + " 沒有 " + stat));
    }

    private static double qualityFlat(final String skill, final int quality, final String stat) {
        final List<Modifier> mods = GemQuality.modifiers(skill, quality, "q");
        return mods.stream().filter(m -> m.stat().equals(stat)).mapToDouble(Modifier::value).sum();
    }

    @Test
    void coilingBoltsSplitsAndChainsFromCursed() {
        assertEquals(2, mod("entwine_rot_arrow", ManaTempest.SPLIT).value(), 1e-9, "Splits towards 2 targets");
        assertEquals(1, mod("entwine_rot_arrow", GrantedSkillKeys.FLAG_PROJECTILES_TO_SPLIT).value(), 1e-9);
        assertEquals(1, mod("entwine_rot_arrow", GrantedSkillKeys.FLAG_CHAIN_CURSED).value(), 1e-9);
        final Modifier range = mod("entwine_rot_arrow", GrantedSkillKeys.CHAIN_RANGE);
        assertEquals(ModType.INCREASED, range.type());
        assertEquals(0.5, range.value(), 1e-9, "50% increased chaining range");
        assertEquals(2, config.skill("entwine_rot_arrow").params().projectiles().intAt(1), "物理、混沌各一發");
    }

    @Test
    void splitTargetCount() {
        assertEquals(2, GrantedSkillKeys.splitTargets(2, 0, true));
        assertEquals(3, GrantedSkillKeys.splitTargets(2, 1, true), "多一發投射物 = 多分裂一個目標");
        assertEquals(2, GrantedSkillKeys.splitTargets(2, 1, false), "一般技能的投射物數量不影響分裂");
        assertEquals(2, GrantedSkillKeys.splitTargets(2.5, 0, true), "品質的小數捨去（5% 品質還是 2）");
        assertEquals(3, GrantedSkillKeys.splitTargets(3.0, 0, true));
        assertEquals(1, GrantedSkillKeys.splitTargets(2, -1, true), "少一發投射物 = 少分裂一個");
        assertEquals(0, GrantedSkillKeys.splitTargets(0, -3, true));
        assertEquals(GrantedSkillKeys.MAX_SPLIT, GrantedSkillKeys.splitTargets(4, 50, true));
        // 魔湧照舊：整數直接用
        assertEquals(1, GrantedSkillKeys.splitTargets(1, 0, false));
    }

    @Test
    void projectileCountIgnoresModifiersWhenTheyGoToSplit() {
        assertEquals(2, GrantedSkillKeys.projectileCount(2, 2, true, 20));
        assertEquals(4, GrantedSkillKeys.projectileCount(2, 2, false, 20));
        assertEquals(1, GrantedSkillKeys.projectileCount(1, -3, false, 20));
        assertEquals(20, GrantedSkillKeys.projectileCount(1, 40, false, 20));
        assertEquals(3, GrantedSkillKeys.projectileCount(2, 0.6, false, 20), "四捨五入（跟之前一樣）");
    }

    @Test
    void cursedChaining() {
        assertTrue(GrantedSkillKeys.canChain(0, true, true), "被詛咒的一定連鎖");
        assertFalse(GrantedSkillKeys.canChain(0, true, false));
        assertFalse(GrantedSkillKeys.canChain(0, false, true), "技能沒有這條就照連鎖次數");
        assertTrue(GrantedSkillKeys.canChain(1, false, false));
        assertFalse(GrantedSkillKeys.consumesChain(true, true), "從被詛咒的連鎖不扣次數（任意多個）");
        assertTrue(GrantedSkillKeys.consumesChain(true, false));
        assertTrue(GrantedSkillKeys.consumesChain(false, true));
        assertEquals(15, GrantedSkillKeys.chainRange(SkillBuild.PROJECTILE_CHAIN_RANGE, 0.5), 1e-9);
        assertEquals(10, GrantedSkillKeys.chainRange(SkillBuild.PROJECTILE_CHAIN_RANGE, 0), 1e-9);
    }

    @Test
    void coilingBoltsQuality() {
        assertEquals(2, qualityFlat("entwine_rot_arrow", 20, ManaTempest.SPLIT), 1e-9, "+(0—2) 分裂目標");
        assertEquals("品質 +20%：分裂目標 +2", SkillService.qualityText(config.skill("entwine_rot_arrow"), 20));
        assertEquals("品質 +15%：分裂目標 +1", SkillService.qualityText(config.skill("entwine_rot_arrow"), 15));
        assertEquals("品質 +5%：分裂目標 +0", SkillService.qualityText(config.skill("entwine_rot_arrow"), 5));
        // 20% 品質：基礎 2 + 品質 2 = 4
        assertEquals(4, GrantedSkillKeys.splitTargets(2 + qualityFlat("entwine_rot_arrow", 20, ManaTempest.SPLIT), 0, true));
        assertEquals(2, GrantedSkillKeys.splitTargets(2 + qualityFlat("entwine_rot_arrow", 9, ManaTempest.SPLIT), 0, true));
    }

    @Test
    void reapCriticalWeaknessDurationAndQuality() {
        assertEquals(6, mod("blood_sickle", GrantedSkillKeys.CRIT_WEAKNESS_SECONDS).value(), 1e-9, "Critical Weakness duration is 6 seconds");
        assertEquals(120, GrantedSkillKeys.critWeaknessTicks(6, 80));
        assertEquals(160, GrantedSkillKeys.critWeaknessTicks(6 + qualityFlat("blood_sickle", 20, GrantedSkillKeys.CRIT_WEAKNESS_SECONDS), 80), "20% 品質 8 秒");
        assertEquals(80, GrantedSkillKeys.critWeaknessTicks(0, 80), "沒寫照預設 4 秒");
        assertEquals("品質 +20%：暴擊弱點持續時間 +2 秒", SkillService.qualityText(config.skill("blood_sickle"), 20));
        assertEquals("品質 +5%：暴擊弱點持續時間 +0.5 秒", SkillService.qualityText(config.skill("blood_sickle"), 5));
    }

    @Test
    void tableQualityTextDescribesTheTableNotDamage() {
        final String arc = SkillService.qualityText(config.skill("chain_thunder"), 20);
        assertTrue(arc.contains("連鎖"), arc);
        assertFalse(arc.contains("傷害"), arc);
        // 沒寫在表上的照舊
        assertTrue(GemQuality.modifiers("fireball_of_nowhere", 20, "q") == null);
    }
}
