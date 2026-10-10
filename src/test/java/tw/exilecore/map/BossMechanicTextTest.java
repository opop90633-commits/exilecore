package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boss 機制的大字與聊天室說明（{@link BossMechanicText}，2026-10）。 */
class BossMechanicTextTest {

    private static MapConfig.Boss boss(final List<MapConfig.Phase> phases, final MapConfig.Enrage enrage) {
        return new MapConfig.Boss("霜心", "STRAY", null, true, 10, 1, 1, List.of(), null, null, phases, List.of(), enrage, List.of());
    }

    @Test
    void shieldComesFirstThenSummonsSkillsAndStats() {
        final MapConfig.Phase phase = new MapConfig.Phase(0.5, "寒冬降臨", null, "STRAY", 4, List.of(), 0.2,
                new MapConfig.Shield(3, "END_CRYSTAL", "冰晶", 100, 20, 25));
        final List<String> notes = BossMechanicText.phaseNotes(phase, List.of("冰魄", "霜光"));
        assertEquals(List.of("⛨ 護盾：20 秒內打掉 3 顆「冰晶」", "叫出 4 隻小怪", "新招式：冰魄、霜光", "Boss 動作變快"), notes);
        assertEquals("<white>⛨ 護盾：20 秒內打掉 3 顆「冰晶」", BossMechanicText.phaseSubtitle(notes));
        assertEquals("<red><bold>第 1 階段・寒冬降臨", BossMechanicText.phaseTitle(1, phase));
        assertTrue(BossMechanicText.phaseChat(1, phase, notes).startsWith("<red>▶ 第 1 階段「寒冬降臨」<gray>：⛨ 護盾"));
        final MapConfig.Phase plain = new MapConfig.Phase(0.3, "死戰", null, null, 0, List.of(), 0);
        assertEquals(List.of("Boss 變得更強"), BossMechanicText.phaseNotes(plain, List.of()));
    }

    @Test
    void overviewListsPhasesEnrageAndHazards() {
        final MapConfig.Boss boss = boss(List.of(
                new MapConfig.Phase(0.66, "寒冬降臨", null, null, 0, List.of(), 0, new MapConfig.Shield(3, "END_CRYSTAL", "冰晶", 100, 20, 25)),
                new MapConfig.Phase(0.33, "絕對零度", null, null, 0, List.of(), 0)), new MapConfig.Enrage(180, 50, 0.2, null));
        assertEquals("<#f5b942>【機制】<gray>血量 66% 進第 1 階段「寒冬降臨」（⛨ 護盾）；血量 33% 進第 2 階段「絕對零度」；"
                + "開打 3:00 後狂暴（傷害更多 50%）；場地危害：冰柱", BossMechanicText.overview(boss, List.of("冰柱")));
        assertNull(BossMechanicText.overview(boss(List.of(), null), List.of()), "沒有機制就不說");
    }

    @Test
    void namesAreEscaped() {
        final MapConfig.Phase phase = new MapConfig.Phase(0.5, "<red>壞</red>", null, null, 0, List.of(), 0);
        assertEquals("<red><bold>第 2 階段・\\<red>壞\\</red>", BossMechanicText.phaseTitle(2, phase));
    }

    @Test
    void everyShippedFinalBossExplainsItsMechanics() throws IOException {
        final MapConfig maps = new MapConfig();
        maps.load(Path.of("src/main/resources/data"));
        for (final MapConfig.MapDef map : maps.finals()) {
            final String overview = BossMechanicText.overview(map.boss(), List.of());
            assertNotNull(overview, map.id());
            assertTrue(overview.contains("階段"), map.id() + "：" + overview);
            for (final MapConfig.Phase phase : map.boss().phases()) {
                assertTrue(!BossMechanicText.phaseNotes(phase, List.of()).isEmpty(), map.id());
            }
            final MapConfig.Enrage enrage = map.boss().enrage();
            if (enrage != null) {
                assertTrue(BossMechanicText.enraged(enrage)[1].contains("快點打倒牠"));
            }
        }
    }
}
