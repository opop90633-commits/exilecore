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
        // 2026-10-26 照 PoE2：只有九階（熔星、星骸兩階拿掉，retired 的定義留著讓舊物品能用）；這次之前 22（各 11 種）
        assertEquals(18, config.bases().size(), "生命、魔力各 9 種（PoE2 的九階）");
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
        // 2026-10-26 照 PoE2：熔星（68 級）、星骸（80 級）兩階拿掉——定義還在（舊物品照常能喝），但不在現役清單裡
        for (final String id : List.of("molten_life_flask", "relic_life_flask", "molten_mana_flask", "relic_mana_flask")) {
            assertNotNull(config.base(id), id + " 的定義要留著");
            assertTrue(config.base(id).retired(), id);
            assertFalse(config.bases().contains(config.base(id)), id + " 不在現役清單");
        }
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
            // 2026-10-26：最高的三種是 40、50、60 級（熔星、星骸拿掉了；這次之前 ≥ 60）
            assertTrue(map.level() >= 40 && !map.retired(), "T16 地圖掉最高的三種：" + map.id());
        }
        assertNull(config.rollDrop(0, new Random(1)), "沒有能掉的基底");
    }
}
