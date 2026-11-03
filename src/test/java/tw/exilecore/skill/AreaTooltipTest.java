package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03 照站長：技能說明上的範圍數字（半徑、長度）跟實際施放一樣乘範圍效果（{@link SkillBuild#areaScale()}，
 * 照 PoB2 calcRadius：半徑 × √(1 + 增加的範圍效果)）；說明文字裡的範圍字樣（{@link AreaText}）也一樣。
 */
class AreaTooltipTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id, final double increasedArea) {
        final StatSheet sheet = new StatSheet();
        if (increasedArea != 0) {
            sheet.add(Modifier.increased(StatKeys.SKILL_AREA, increasedArea, "測試"));
        }
        return SkillBuild.of(config, config.skill(id), 20, 100, false, List.of(), Set.of(), sheet);
    }

    /** 說明裡第一個「（前綴）數字 格」的數字。 */
    private static double number(final List<String> lines, final String prefix) {
        final Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*格");
        for (final String line : lines) {
            final Matcher matcher = pattern.matcher(line.replaceAll("<[^>]*>", ""));
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        throw new AssertionError("說明裡找不到「" + prefix + " … 格」：" + lines);
    }

    @Test
    void areaScaleIsSquareRootOfAreaOfEffect() {
        // PoB2 CalcOffence calcRadius：radius × √(areaMod)，areaMod = (1 + inc) × more
        assertEquals(1.0, build("frost_star_blast", 0).areaScale(), 1e-9);
        assertEquals(Math.sqrt(2.0), build("frost_star_blast", 1.0).areaScale(), 1e-9);
        assertEquals(Math.sqrt(1.5), build("frost_star_blast", 0.5).areaScale(), 1e-9);
    }

    @Test
    void novaRadiusInTooltipFollowsAreaOfEffect() {
        final SkillBuild plain = build("frost_star_blast", 0);
        final SkillBuild bigger = build("frost_star_blast", 1.0);
        final double before = number(SkillService.codexLines(plain, new StatSheet()), "半徑");
        final double after = number(SkillService.codexLines(bigger, new StatSheet()), "半徑");
        assertEquals(plain.radius(), before, 0.05, "說明寫的半徑就是施放用的 build.radius()");
        assertEquals(bigger.radius(), after, 0.05);
        assertEquals(before * Math.sqrt(2), after, 0.1, "增加 100% 範圍效果：半徑 ×√2");
        assertTrue(after > before);
    }

    @Test
    void wallLengthInTooltipFollowsAreaOfEffect() {
        // 火焰之牆（zone shape: line）：「面前一道 x 格長」照 build.length()
        final SkillBuild plain = build("flame_curtain", 0);
        final SkillBuild bigger = build("flame_curtain", 0.21);
        final double before = number(SkillService.codexLines(plain, new StatSheet()), "面前一道");
        final double after = number(SkillService.codexLines(bigger, new StatSheet()), "面前一道");
        assertEquals(plain.length(), before, 0.05);
        assertEquals(bigger.length(), after, 0.05);
        assertEquals(before * 1.1, after, 0.1, "增加 21% 範圍效果：長度 ×1.1（寫到小數一位，小幅增加也看得出來）");
    }

    @Test
    void auraAndWarcryRadiusShowOneDecimal() {
        final SkillBuild cry = build("molten_soul_shriek", 0.21);
        final double radius = number(SkillService.codexLines(cry, new StatSheet()), "半徑");
        assertEquals(Warcries.baseRadius(cry.definition().warcry(), new StatSheet(), cry.profile().tags()) * 1.1, radius, 0.05);
    }

    @Test
    void descriptionSnippetsExistAndScale() {
        for (final Map.Entry<String, List<String>> entry : AreaText.SNIPPETS.entrySet()) {
            final SkillDefinition definition = config.skill(entry.getKey());
            assertNotNull(definition, entry.getKey());
            assertTrue(definition.tags().contains(tw.exilecore.stats.Tags.AREA), entry.getKey() + " 要是範圍技能（areaScale 才會變）");
            for (final String snippet : entry.getValue()) {
                assertTrue(definition.description().contains(snippet), entry.getKey() + " 的說明找不到「" + snippet + "」");
            }
        }
        final String text = config.skill("scorching_sun_pearl").description();
        assertEquals(text, AreaText.description("scorching_sun_pearl", text, 1.0), "沒有範圍效果照原樣");
        final String scaled = AreaText.description("scorching_sun_pearl", text, build("scorching_sun_pearl", 1.0).areaScale());
        assertTrue(scaled.contains("周圍 <white>3.5</white><gray> 格"), scaled);
        assertTrue(scaled.contains("半徑 ×1.41"), scaled);
        // 不吃範圍效果的距離照寫
        final String fall = config.skill("myriad_arrow_fall").description();
        final String fallScaled = AreaText.description("myriad_arrow_fall", fall, Math.sqrt(2));
        assertTrue(fallScaled.contains("落點 <white>1</white><gray> 格"), fallScaled);
        assertTrue(fallScaled.contains("隨機散在 3.6 格內"), "散落範圍不是技能的半徑，照寫：" + fallScaled);
    }

    @Test
    void everyAreaSkillsRadiusLineScales() {
        // 所有範圍技能：codexLines 寫「半徑 x 格」的那幾行，x 跟著範圍效果變大（2026-11-03）
        final List<String> stuck = new ArrayList<>();
        for (final SkillDefinition definition : config.all()) {
            if (!definition.tags().contains(tw.exilecore.stats.Tags.AREA)) {
                continue;
            }
            final List<String> before;
            final List<String> after;
            try {
                before = SkillService.codexLines(SkillBuild.of(config, definition, 20, 100, false, List.of(), Set.of(), new StatSheet()), new StatSheet());
                final StatSheet sheet = new StatSheet().add(Modifier.increased(StatKeys.SKILL_AREA, 1.0, "測試"));
                after = SkillService.codexLines(SkillBuild.of(config, definition, 20, 100, false, List.of(), Set.of(), sheet), sheet);
            } catch (final RuntimeException skipped) {
                continue; // 要玩家狀態才寫得出來的技能（這裡不測）
            }
            for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
                // 「半徑 ×1.5」（相對於主要半徑的倍率）不算；只看寫了幾格的
                if (before.get(i).replaceAll("<[^>]*>", "").matches(".*半徑\\s*[0-9.]+\\s*格.*") && before.get(i).equals(after.get(i))) {
                    stuck.add(definition.id() + "：" + before.get(i).replaceAll("<[^>]*>", ""));
                }
            }
        }
        assertTrue(stuck.isEmpty(), "這些說明的半徑沒有跟著範圍效果變：" + stuck);
    }
}
