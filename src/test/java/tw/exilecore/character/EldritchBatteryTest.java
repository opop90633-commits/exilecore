package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02 照 PoB2：靈能電池換護盾「基礎」成魔力，只吃魔力的增加／更多，不吃「增加能量護盾 %」。 */
class EldritchBatteryTest {

    @Test
    void baseEnergyShieldConvertsAndUsesManaModifiers() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.MANA_MAX, 100, "測試"));
        sheet.add(Modifier.flat(StatKeys.ES_MAX, 400, "裝備"));
        sheet.add(Modifier.increased(StatKeys.ES_MAX, 1.5, "天賦"));
        sheet.add(Modifier.increased(StatKeys.MANA_MAX, 0.3, "天賦"));
        assertEquals(1000, PlayerState.maxEnergyShield(sheet), 1e-9, "沒有靈能電池：護盾照算 %");
        sheet.add(Modifier.flat(StatKeys.FLAG_ELDRITCH_BATTERY, 1, "測試"));
        assertEquals(0, PlayerState.maxEnergyShield(sheet), 1e-9);
        assertEquals((100 + 400) * 1.3, PlayerState.maxMana(sheet), 1e-9, "400 基礎護盾 + 100 魔力，乘 130% 魔力；150% 護盾不算");
        assertEquals(PlayerState.maxMana(sheet), DamageCalculator.maxMana(sheet), 1e-9, "兩邊一致");
    }
}
