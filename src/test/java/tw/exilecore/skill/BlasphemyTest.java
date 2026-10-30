package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.core.Units;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-29 照 PoE2 Blasphemy（咒環）：設定讀得進來、孔裡只能放詛咒、每個詛咒保留 60 精魂、效果更少照等級表、半徑 +1 公尺。
 */
class BlasphemyTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        tw.exilecore.currency.GemQuality.load(DATA);
    }

    private static SkillLoadout.Entry curse(final String id, final int level) {
        return new SkillLoadout.Entry(id, level, 2, List.of());
    }

    @Test
    void definitionLoads() {
        final SkillDefinition blasphemy = config.skill(Blasphemy.ID);
        assertNotNull(blasphemy);
        assertEquals("咒環", blasphemy.name());
        assertEquals(Mechanic.AURA, blasphemy.mechanic());
        assertEquals(8, blasphemy.tier());
        assertTrue(blasphemy.tags().contains(Tags.META), "主要寶石");
        assertTrue(blasphemy.tags().contains(Tags.PERSISTENT));
        assertTrue(SupportDefinition.hostsSkill(blasphemy), "孔裡放技能寶石");
        assertNull(config.meta(Blasphemy.ID), "不是能量觸發的引星");
        assertTrue(Blasphemy.hosts(blasphemy));
        assertTrue(GemAttributes.isGem(blasphemy) && GemAttributes.listed(Blasphemy.ID), "是寶石、有屬性需求");
        assertEquals(2, GemAttributes.colourIndex(Blasphemy.ID), "智慧寶石");
        assertEquals(Blasphemy.RESERVE_PER_CURSE, blasphemy.aura().reserve(), 1e-9);
    }

    @Test
    void onlyCursesAccepted() {
        for (final String id : List.of("despair_curse", "elemental_weakness_curse", "enfeeble_curse", "temporal_curse", "vulnerability_curse")) {
            assertNull(Blasphemy.problem(config.skill(id)), id + "：詛咒可以放");
        }
        assertNotNull(Blasphemy.problem(config.skill("fireball")), "法術不行");
        assertNotNull(Blasphemy.problem(config.skill("hunters_mark")), "印記不行");
        assertNotNull(Blasphemy.problem(config.skill("meta_crit")), "主要寶石不行");
        assertNotNull(Blasphemy.problem(config.skill(Blasphemy.ID)), "自己不行");
        assertTrue(Blasphemy.problem(config.skill("fireball")).contains("詛咒"), "原因要說只能放詛咒");
    }

    @Test
    void reservesSixtyPerCurse() {
        final SkillDefinition blasphemy = config.skill(Blasphemy.ID);
        final SkillBuild base = SkillBuild.of(config, blasphemy, 10, 90, false, List.of(), Set.of(), new StatSheet());
        assertEquals(0, base.withReserveCount(0).reserve(), 1e-9, "沒放詛咒不保留");
        assertEquals(60, base.withReserveCount(1).reserve(), 1e-9);
        assertEquals(120, base.withReserveCount(2).reserve(), 1e-9);
        assertEquals(180, base.withReserveCount(3).reserve(), 1e-9);
        // 品質 20%：10% 保留效率 → 每個 60 ÷ 1.1
        final SkillBuild quality = SkillBuild.of(config, blasphemy, 10, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                SkillService.qualityModifiers(blasphemy, 20));
        assertEquals(120 / 1.1, quality.withReserveCount(2).reserve(), 1e-6);
        // 孔裡放錯的不算
        final SkillLoadout.Entry host = new SkillLoadout.Entry(Blasphemy.ID, 10, 4, List.of())
                .withSpell(0, curse("despair_curse", 10)).withSpell(1, curse("enfeeble_curse", 10));
        assertEquals(2, Blasphemy.curses(config, host));
        assertEquals(0, Blasphemy.curses(config, new SkillLoadout.Entry(Blasphemy.ID, 10, 4, List.of())));
        assertEquals(1, Blasphemy.curses(config, new SkillLoadout.Entry(Blasphemy.ID, 10, 4, List.of())
                .withSpell(0, curse("despair_curse", 10)).withSpell(1, curse("fireball", 10))));
        assertEquals(Blasphemy.reserve(2), 120, 1e-9);
    }

    @Test
    void magnitudeTable() {
        assertEquals(40, Blasphemy.LESS_MAGNITUDE.length);
        assertEquals(50, Blasphemy.lessMagnitude(1), 1e-9);
        assertEquals(48, Blasphemy.lessMagnitude(5), 1e-9);
        assertEquals(46, Blasphemy.lessMagnitude(10), 1e-9);
        assertEquals(43, Blasphemy.lessMagnitude(15), 1e-9);
        assertEquals(41, Blasphemy.lessMagnitude(20), 1e-9);
        assertEquals(36, Blasphemy.lessMagnitude(30), 1e-9);
        assertEquals(33, Blasphemy.lessMagnitude(40), 1e-9);
        for (int i = 1; i < Blasphemy.LESS_MAGNITUDE.length; i++) {
            assertTrue(Blasphemy.LESS_MAGNITUDE[i] <= Blasphemy.LESS_MAGNITUDE[i - 1], "等級越高更少的越少：" + (i + 1));
        }
        // 套到詛咒上：詛咒效果倍率 × (1 − x%)；額外的品質效果再增加
        final SkillDefinition despair = config.skill("despair_curse");
        final double plain = SkillBuild.of(config, despair, 10, 90, false, List.of(), Set.of(), new StatSheet()).auraEffect();
        final double one = SkillBuild.of(config, despair, 10, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                Blasphemy.curseModifiers(1, 0)).auraEffect();
        final double twenty = SkillBuild.of(config, despair, 10, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                Blasphemy.curseModifiers(20, 0)).auraEffect();
        final double quality = SkillBuild.of(config, despair, 10, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                Blasphemy.curseModifiers(20, 20)).auraEffect();
        assertEquals(plain * 0.5, one, 1e-9);
        assertEquals(plain * 0.59, twenty, 1e-9);
        assertEquals(plain * 1.2 * 0.59, quality, 1e-9);
    }

    @Test
    void radiusPlusOneMetre() {
        final SkillParams.Mark spec = config.skill("despair_curse").params().mark();
        assertTrue(spec.curse());
        for (final int level : new int[] {1, 10, 20}) {
            final SkillParams.Mark widened = Blasphemy.widened(spec, level);
            assertEquals(spec.radius(level) + Units.metres(1), widened.radius(level), 1e-9);
            assertEquals(spec.radius(level) + 1.0, Blasphemy.baseRadius(spec, level), 1e-9, "1 公尺 = 1 格（2026-10-30；之前 1.5 格）");
            assertTrue(widened.curse());
            assertEquals(spec.duration(level), widened.duration(level), 1e-9);
            assertEquals(spec.effects(), widened.effects());
        }
    }

    @Test
    void qualityTexts() {
        final SkillDefinition blasphemy = config.skill(Blasphemy.ID);
        assertNotNull(SkillService.qualityText(blasphemy, 20));
        assertEquals("插槽詛咒的效果增加 20%", SkillService.advancedQualityText(blasphemy, 20));
    }
}
