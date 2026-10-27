package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 的龍捲射擊（poe2db／RePoE Tornado Shot）：裂空箭。 */
class TornadoShotPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void tornadoDealsPhysicalDotPerLevel() {
        final TornadoSpec spec = config.tornado("tornado_shot");
        assertNotNull(spec);
        assertEquals(4.3, spec.dps(1), 1e-9);
        assertEquals(43.9, spec.dps(10), 1e-9);
        assertEquals(208.7, spec.dps(20), 1e-9);
        assertEquals(241.8, spec.dps(21), 1e-9);
        assertEquals(4450.3, spec.dps(40), 1e-9);
        assertEquals(3.0, spec.radius().at(20), 1e-9, "龍捲風 3 格（secondary radius 30）");
        assertEquals(1.0, config.skill("tornado_shot").params().explode().at(20), 1e-9, "落地衝擊 1 格（radius 10）");
        assertEquals(15, spec.duration(), 1e-9);
    }

    @Test
    void tornadoDisappearsAfterHitsPerLevel() {
        final TornadoSpec spec = config.tornado("tornado_shot");
        assertEquals(5, spec.hits(1));
        assertEquals(5, spec.hits(5));
        assertEquals(6, spec.hits(6));
        assertEquals(8, spec.hits(20));
        assertEquals(9, spec.hits(21));
        assertEquals(12, spec.hits(40));
    }

    @Test
    void copiedProjectilesLessAddsWithQualityMore() {
        final TornadoSpec spec = config.tornado("tornado_shot");
        assertEquals(60, spec.copyLess(1), 1e-9);
        assertEquals(41, spec.copyLess(20), 1e-9);
        assertEquals(21, spec.copyLess(40), 1e-9);
        assertEquals(0.59, spec.copyFactor(20, 0), 1e-9);
        // 品質 20%：+15（同一條 tornado_shot_projectile_damage_+%_final：−41 + 15 = −26）
        final double more = SkillService.qualityFlat(config.skill("tornado_shot"), 20, TornadoSpec.COPY_MORE);
        assertEquals(15, more, 1e-9);
        assertEquals(0.74, spec.copyFactor(20, more), 1e-9);
        final String text = SkillService.qualityText(config.skill("tornado_shot"), 20);
        assertTrue(text.contains("複製的投射物更多 15%"), text);
    }

    @Test
    void advancedQualityRaisesTornadoLimit() {
        final TornadoSpec spec = config.tornado("tornado_shot");
        assertEquals(1, spec.limit(0));
        final List<Modifier> advanced = tw.exilecore.ascendancy.AscBuild.advanced("tornado_shot", 20, List.of());
        final double bonus = advanced.stream().filter(m -> m.stat().equals(TornadoSpec.LIMIT)).mapToDouble(Modifier::value).sum();
        assertEquals(3, spec.limit(bonus), "20% 品質 +2");
        assertEquals(2, spec.limit(1.5), "小數捨去");
    }

    @Test
    void dotScalesWithPhysicalAndProjectileButNotAttackOrArea() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 0.5, "物理").withTags(Set.of(Tags.PHYSICAL)));
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 0.3, "投射物").withTags(Set.of(Tags.PROJECTILE)));
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 1.0, "攻擊").withTags(Set.of(Tags.ATTACK)));
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 1.0, "範圍").withTags(Set.of(Tags.AREA)));
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 1.0, "火焰").withTags(Set.of(Tags.FIRE)));
        assertEquals(208.7 * 1.8, TornadoSpec.scaledDps(sheet, 208.7, 1.0), 1e-6);
        assertEquals(208.7 * 1.8 * 1.25, TornadoSpec.scaledDps(sheet, 208.7, 1.25), 1e-6, "急蝕的非異常持續傷害更多");
        assertEquals(10, TornadoSpec.scaledDps(null, 10, 1.0), 1e-9);
    }

    @Test
    void windTagIsShown() {
        assertTrue(config.skill("tornado_shot").tags().contains("wind"));
        assertEquals("疾風", ModifierText.tagName("wind"));
        assertTrue(SkillService.tagNames(config.skill("tornado_shot")).contains("疾風"));
    }
}
