package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分身獎勵：每種職業只算等級最高的那個、只給到達的最高一階、整個帳號都拿到。 */
class LegacyTest {

    static AscendancyConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    static String className(final String classId) {
        final tw.exilecore.character.CharacterClass type = tw.exilecore.character.CharacterClass.parse(classId, null);
        return type == null ? classId : type.displayName();
    }

    /** 某個職業的那一筆（照 legacy 的順序：重甲鬥士、重弩手、長矛獵手、弓箭手、棍僧、元素法師、混沌術士）。 */
    static Legacy.Bonus of(final List<Legacy.Bonus> bonuses, final String classId) {
        final List<String> order = List.copyOf(config.legacy().keySet());
        return bonuses.get(order.indexOf(classId));
    }

    @Test
    void bestCharacterOfEachClassCounts() {
        // 門檻是 60／75／90（劇情裡練到的 30、45 級拿不到了）
        final List<Legacy.Bonus> bonuses = Legacy.bonuses(config, List.of(
                new Legacy.Member("marauder", "小蠻", 70),
                new Legacy.Member("marauder", "大蠻", 92),
                new Legacy.Member("witch", "小巫", 59)));
        assertEquals(7, bonuses.size(), "七個職業各一筆");
        final Legacy.Bonus marauder = of(bonuses, "marauder");
        assertEquals("大蠻", marauder.best().name(), "兩個重甲鬥士只算高的那個");
        assertEquals(90, marauder.tier().level());
        assertNull(marauder.next(), "已經最高一階");
        final Legacy.Bonus ranger = of(bonuses, "ranger");
        assertNull(ranger.best());
        assertFalse(ranger.active());
        assertEquals(60, ranger.next().level());
        final Legacy.Bonus witch = of(bonuses, "witch");
        assertEquals("小巫", witch.best().name());
        assertNull(witch.tier(), "59 級還沒到第一階");
        assertEquals(60, witch.next().level());

        final List<Modifier> modifiers = Legacy.modifiers(bonuses, LegacyTest::className);
        assertEquals(List.of(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.06, Set.of(), "分身獎勵（重甲鬥士 Lv.90）")), modifiers,
                "只給最高的那一階，不是三階加起來");
    }

    @Test
    void tiersFollowTheLevels() {
        final List<Legacy.Bonus> mid = Legacy.bonuses(config, List.of(new Legacy.Member("sorceress", "術", 75)));
        final Legacy.Bonus sorceress = of(mid, "sorceress");
        assertEquals(75, sorceress.tier().level());
        assertEquals(90, sorceress.next().level());
        final List<Modifier> modifiers = Legacy.modifiers(mid, LegacyTest::className);
        assertEquals(3, modifiers.size(), "三種元素抗性");
        for (final Modifier modifier : modifiers) {
            assertEquals(ModType.FLAT, modifier.type());
            assertEquals(4, modifier.value(), 1e-9);
            assertEquals("分身獎勵（元素法師 Lv.75）", modifier.source());
        }
        // 混沌術士改成混沌抗性
        final List<Modifier> witch = Legacy.modifiers(Legacy.bonuses(config, List.of(new Legacy.Member("witch", "巫", 90))),
                LegacyTest::className);
        assertEquals(List.of(new Modifier(StatKeys.RESIST_CHAOS, ModType.FLAT, 12, Set.of(), "分身獎勵（混沌術士 Lv.90）")), witch);
        assertTrue(Legacy.modifiers(Legacy.bonuses(config, List.of()), LegacyTest::className).isEmpty(), "沒有角色：沒有獎勵");
    }
}
