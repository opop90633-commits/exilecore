package tw.exilecore.flask;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次 2：藥劑的設定檔、藥劑欄、掉落。 */
class FlaskConfigTest {

    static FlaskConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new FlaskConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void basesAndSlotsLoad() {
        assertEquals(22, config.bases().size(), "生命、魔力各 11 種（PoE2 的九種＋地圖用的兩種）");
        assertArrayEquals(new int[] {0, 1}, config.slots(), "藥劑欄是快捷欄 1、2");
        assertTrue(config.isFlaskSlot(0));
        assertFalse(config.isFlaskSlot(2));
        // 照 PoE2：一格生命、一格魔力
        assertEquals(0, config.slotFor(FlaskKind.LIFE), "生命藥劑放 1");
        assertEquals(1, config.slotFor(FlaskKind.MANA), "魔力藥劑放 2");
        assertEquals(FlaskKind.LIFE, config.kindAt(0));
        assertEquals(FlaskKind.MANA, config.kindAt(1));
        assertNull(config.kindAt(2));
        assertEquals(2, config.firstWeaponSlot(), "武器放在第 3 格");
        assertEquals(5, config.chargesPerKill(MonsterRarity.RARE));
        assertEquals(List.of("crude_life_flask", "crude_mana_flask"), config.starter());
        assertEquals(FlaskKind.LIFE, config.base("crude_life_flask").kind());
        assertEquals(FlaskKind.MANA, config.base("crude_mana_flask").kind());
        for (final FlaskBase base : config.bases()) {
            assertTrue(base.uses() >= 6, base.id() + " 滿充能至少喝 6 次");
            assertTrue(base.recovery() > 0 && base.duration() > 0, base.id());
        }
        final FlaskBase top = config.base("heavens_life_flask");
        assertEquals(60, top.level());
        assertEquals(920, top.recovery(), 1e-9);
        // 等級 100：T7（68 級）、T14（80 級）地圖起掉的兩種，回復量接著往上
        assertEquals(68, config.base("molten_life_flask").level());
        assertEquals(80, config.base("relic_life_flask").level());
        assertTrue(config.base("relic_life_flask").recovery() > config.base("molten_life_flask").recovery());
        assertTrue(config.base("molten_life_flask").recovery() > top.recovery());
        assertTrue(config.base("relic_mana_flask").recovery() > config.base("heavens_mana_flask").recovery());
    }

    @Test
    void dropsComeFromTheBestFewBasesAtOrBelowTheMonsterLevel() {
        final Random random = new Random(11);
        boolean life = false;
        boolean mana = false;
        for (int i = 0; i < 500; i++) {
            final FlaskBase low = config.rollDrop(1, random);
            assertNotNull(low);
            assertEquals(1, low.level(), "1 級的怪只掉最低階的藥劑");
            final FlaskBase high = config.rollDrop(60, random);
            assertTrue(high.level() >= 40 && high.level() <= 60, "60 級的怪掉最高的三種：" + high.id());
            life |= high.kind() == FlaskKind.LIFE;
            mana |= high.kind() == FlaskKind.MANA;
            final FlaskBase mid = config.rollDrop(20, random);
            assertTrue(mid.level() <= 20 && mid.level() >= 4, "需求等級不能超過怪物等級：" + mid.id());
        }
        assertTrue(life && mana, "生命、魔力都會掉");
        for (int i = 0; i < 200; i++) {
            final FlaskBase map = config.rollDrop(84, random);
            assertTrue(map.level() >= 60, "T16 地圖掉最高的三種：" + map.id());
        }
        assertNull(config.rollDrop(0, new Random(1)), "沒有能掉的基底");
    }
}
