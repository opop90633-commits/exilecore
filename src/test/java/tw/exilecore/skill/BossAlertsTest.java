package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 終局與試煉 Boss 的特殊招式提示（{@link BossAlerts}，2026-10）。 */
class BossAlertsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MapConfig maps;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
    }

    private static BossSkill skill(final String text) {
        return BossSkill.parse("x", YamlNode.load("test.yml", new StringReader(text)));
    }

    @Test
    void mechanicKindsAreSpecialPlainAttacksNeedAHint() {
        assertTrue(BossAlerts.special(skill("{ name: 甲, kind: sanctuary, radius: 20 }")));
        assertTrue(BossAlerts.special(skill("{ name: 甲, kind: nova, radius: 9 }")));
        assertTrue(BossAlerts.special(skill("{ name: 甲, kind: ring, radius: 1.5, reach: 14 }")));
        assertFalse(BossAlerts.special(skill("{ name: 甲, kind: slam, radius: 3 }")), "一般的紅圈只有動作列");
        final BossSkill hinted = skill("{ name: 甲, kind: slam, radius: 3, hint: \"十顆一起落：一直移動\" }");
        assertTrue(BossAlerts.special(hinted), "寫了 hint 就算特殊招式");
        assertEquals("十顆一起落：一直移動", BossAlerts.hint(hinted));
        assertTrue(BossAlerts.deadly(skill("{ name: 甲, kind: pull, radius: 10 }")));
        assertFalse(BossAlerts.deadly(skill("{ name: 甲, kind: orbs }")));
    }

    @Test
    void defaultHintsSayWhatToDo() {
        assertEquals("從 Boss 身邊爆開：馬上往外跑出 14 格", BossAlerts.hint(skill("{ name: 甲, kind: nova, radius: 14 }")));
        assertEquals("全場大招：躲進綠色的安全區", BossAlerts.hint(skill("{ name: 甲, kind: sanctuary, radius: 20 }")));
        assertTrue(BossAlerts.hint(skill("{ name: 甲, kind: curse, radius: 14, taken: 30, duration: 8 }")).contains("30%"));
        assertTrue(BossAlerts.hazardHint(skill("{ name: 甲, kind: slam, radius: 3 }"), 9).endsWith("（大約每 9 秒）"));
        assertEquals(24, BossAlerts.titleTicks(skill("{ name: 甲, kind: nova, radius: 9, telegraph: 0.2 }")), "至少 1.2 秒");
        assertEquals(60, BossAlerts.titleTicks(skill("{ name: 甲, kind: sanctuary, radius: 20, telegraph: 5 }")), "最多 3 秒");
    }

    @Test
    void hintsAreShort() {
        assertThrows(IllegalArgumentException.class,
                () -> skill("{ name: 甲, kind: slam, radius: 3, hint: \"" + "很長".repeat(20) + "\" }"));
        for (final BossSkill skill : config.bossSkills().skills().values()) {
            final String hint = BossAlerts.hint(skill);
            assertFalse(hint.isBlank(), skill.id());
            assertTrue(hint.codePointCount(0, hint.length()) <= BossSkill.MAX_HINT, skill.id() + " 的提示太長：" + hint);
        }
    }

    @Test
    void everyFinalBossHasAnnouncedSkillsInEveryPhase() {
        int checked = 0;
        final List<MapConfig.MapDef> finals = new ArrayList<>(maps.finals());
        for (final MapConfig.MapDef map : finals) {
            final MapConfig.Boss boss = map.boss();
            for (int phase = 0; phase <= boss.phases().size(); phase++) {
                boolean announced = false;
                for (final BossSkillConfig.Entry entry : config.bossSkills().kitFor(boss.type(), boss.skills())) {
                    if (entry.fromPhase() == phase && BossAlerts.special(entry.skill())) {
                        announced = true;
                    }
                }
                assertTrue(announced, map.id() + " 第 " + phase + " 階段沒有任何會跳大字說明的招式");
            }
            checked++;
        }
        assertTrue(checked >= 4, "四隻終局 Boss");
    }
}
