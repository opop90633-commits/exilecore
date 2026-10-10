package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyConfig;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未切割寶石算通貨（2026-10）：寶石與通貨代號互換、資料檔每一級都有、參考價值照等級往上。 */
class GemCurrencyTest {

    private static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void currencyIdsRoundTrip() {
        for (int level = 1; level <= SkillLoadout.MAX_LEVEL; level++) {
            final GemData gem = GemData.uncutSkill(level);
            assertEquals("uncut_skill_" + level, gem.currencyId());
            assertEquals(Optional.of(gem), GemData.fromCurrencyId(gem.currencyId()));
        }
        for (int tier = 1; tier <= SupportDefinition.MAX_TIER; tier++) {
            final GemData gem = GemData.uncutSupport(tier);
            assertEquals("uncut_support_" + tier, gem.currencyId());
            assertEquals(Optional.of(gem), GemData.fromCurrencyId(gem.currencyId()));
        }
        assertNull(GemData.support("fork").currencyId(), "切好的輔助寶石不是通貨");
        for (final String bad : new String[] {"uncut_skill_0", "uncut_skill_21", "uncut_skill_05", "uncut_skill_x", "uncut_support_4",
                "uncut_skill_", "glimmer_orb"}) {
            assertTrue(GemData.fromCurrencyId(bad).isEmpty(), bad);
        }
        assertTrue(GemData.fromCurrencyId(null).isEmpty());
    }

    @Test
    void everyLevelIsAShippedCurrencyWithAValue() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        final EconomyConfig economy = new EconomyConfig();
        economy.load(DATA);
        long previous = 0;
        for (int level = 1; level <= SkillLoadout.MAX_LEVEL; level++) {
            final GemData gem = GemData.uncutSkill(level);
            final CurrencyType type = currencies.get(gem.currencyId());
            assertNotNull(type, gem.currencyId());
            assertTrue(type.uncutGem());
            assertEquals(CurrencyOperation.NONE, type.operation(), "不能拿去點裝備");
            assertEquals(0, type.dropWeight(), "照寶石的掉落表掉，不從通貨的掉落表抽");
            assertEquals(gem.maxStack(), type.stack(), "通貨袋拿出來的一疊跟寶石的堆疊上限一樣");
            final long value = economy.value(type.id());
            assertTrue(value > previous, type.id() + " 的參考價值要比低一級的高");
            previous = value;
        }
        previous = 0;
        for (int tier = 1; tier <= SupportDefinition.MAX_TIER; tier++) {
            final GemData gem = GemData.uncutSupport(tier);
            final CurrencyType type = currencies.get(gem.currencyId());
            assertNotNull(type, gem.currencyId());
            assertEquals(gem.maxStack(), type.stack());
            final long value = economy.value(type.id());
            assertTrue(value > previous, type.id());
            previous = value;
        }
        assertTrue(!currencies.get("glimmer_orb").uncutGem());
    }

    @Test
    void uncutStackCannotBeChangedByTheDataFile() {
        final String yaml = "{ name: 甲, material: QUARTZ, stack: 40, operation: NONE }";
        assertEquals(CurrencyType.UNCUT_STACK, CurrencyType.parse("uncut_support_2",
                tw.exilecore.core.config.YamlNode.load("test.yml", new StringReader(yaml))).stack(), "寶石本身一疊就是 20 顆");
        assertEquals(40, CurrencyType.parse("glimmer_orb", tw.exilecore.core.config.YamlNode.load("test.yml", new StringReader(yaml))).stack());
        assertEquals(CurrencyType.UNCUT_STACK, GemData.uncutSkill(3).maxStack());
    }
}
