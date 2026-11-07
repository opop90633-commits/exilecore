package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：瓦爾傳奇與遠古魂核的防禦、回復鍵（純計算的部分）。 */
class VaalDefenceKeysTest {

    private static StatSheet defender() {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        return sheet;
    }

    @Test
    void elementalTakenAsChaos() {
        final StatSheet sheet = defender();
        sheet.add(Modifier.flat(VaalKeys.TAKEN_ELEMENTAL_AS_CHAOS, 20, "測試"));
        final DamagePacket raw = new DamagePacket().set(DamageType.FIRE, 100).set(DamageType.COLD, 50).set(DamageType.PHYSICAL, 10);
        final DamagePacket out = UniqueDefence.reroute(raw, sheet);
        assertEquals(80, out.get(DamageType.FIRE), 1e-9);
        assertEquals(40, out.get(DamageType.COLD), 1e-9);
        assertEquals(30, out.get(DamageType.CHAOS), 1e-9, "20% 的火焰、冰冷視為混沌");
        assertEquals(10, out.get(DamageType.PHYSICAL), 1e-9, "物理不換");
        assertEquals(100, raw.get(DamageType.FIRE), 1e-9, "原本的封包不改");
        // 物理先換成的火焰不再換一次
        sheet.add(Modifier.flat(UniqueStats.TAKEN_PHYSICAL_AS_FIRE, 100, "測試"));
        final DamagePacket both = UniqueDefence.reroute(raw, sheet);
        assertEquals(90, both.get(DamageType.FIRE), 1e-9);
        assertEquals(30, both.get(DamageType.CHAOS), 1e-9);
    }

    @Test
    void lightningResistIgnoredAndChaosArmour() {
        final StatSheet sheet = defender();
        sheet.add(Modifier.flat(StatKeys.RESIST_LIGHTNING, 75, "測試"));
        final DamagePacket hit = new DamagePacket().set(DamageType.LIGHTNING, 100);
        assertEquals(25, DamageCalculator.mitigate(hit, sheet, null, Set.of()).get(DamageType.LIGHTNING), 1e-6);
        sheet.add(Modifier.flat(VaalKeys.FLAG_LIGHTNING_RESIST_IGNORED, 1, "測試"));
        assertEquals(100, DamageCalculator.mitigate(hit, sheet, null, Set.of()).get(DamageType.LIGHTNING), 1e-6, "閃電抗性不影響");
        assertEquals(75, sheet.get(StatKeys.RESIST_LIGHTNING), 1e-9, "抗性照常顯示");

        final StatSheet chaos = defender();
        chaos.add(Modifier.flat(StatKeys.ARMOUR, 1000, "測試"));
        final DamagePacket chaosHit = new DamagePacket().set(DamageType.CHAOS, 100);
        assertEquals(100, DamageCalculator.mitigate(chaosHit, chaos, null, Set.of()).get(DamageType.CHAOS), 1e-6);
        chaos.add(Modifier.flat(VaalKeys.ARMOUR_CHAOS, 75, "測試"));
        assertTrue(DamageCalculator.mitigate(chaosHit, chaos, null, Set.of()).get(DamageType.CHAOS) < 100, "75% 護甲也套用在混沌");
    }

    @Test
    void volatilityPartlyCold() {
        final StatSheet sheet = defender();
        sheet.add(Modifier.flat(StatKeys.RESIST_COLD, 50, "測試"));
        assertEquals(500, tw.exilecore.skill.TreeMechanics.volatilityDamage(5, sheet), 1e-9);
        sheet.add(Modifier.flat(VaalKeys.TAKEN_VOLATILITY_PHYSICAL_AS_COLD, 100, "測試"));
        assertEquals(250, tw.exilecore.skill.TreeMechanics.volatilityDamage(5, sheet), 1e-9, "全部視為冰冷，吃 50% 冰冷抗性");
    }

    @Test
    void earlyDerived() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.STR, 103, "測試"));
        sheet.add(Modifier.flat(VaalKeys.PER_STR_ES_RECHARGE, 1, "測試"));
        sheet.add(Modifier.flat(VaalKeys.MANA_PER_3_HELMET_ARMOUR, 1, "測試"));
        final StatSheet out = new StatSheet().addAll(UniqueDerived.vaalEarly(sheet, 302));
        assertEquals(0.25, out.increased(StatKeys.ES_RECHARGE, Set.of()), 1e-9, "103 力量 → 25 個 4 點 → 增加 25%");
        assertEquals(100, out.flat(StatKeys.MANA_MAX, Set.of()), 1e-9, "302 頭盔護甲 → +100 最大魔力");
        assertTrue(UniqueDerived.vaalEarly(new StatSheet(), 302).isEmpty());
    }

    @Test
    void lateDerived() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.ES_MAX, 200, "測試"));
        sheet.add(Modifier.flat(VaalKeys.PER_ES_LIFE_REGEN, 0.05, "測試"));
        sheet.add(Modifier.flat(VaalKeys.FLAG_NO_ENERGY_SHIELD, 1, "測試"));
        sheet.add(Modifier.flat(VaalKeys.FLAG_MOVEMENT_TO_ES_RECHARGE, 1, "測試"));
        sheet.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, -0.1, "測試"));
        sheet.add(Modifier.flat(VaalKeys.LIGHT_RADIUS_AS_AREA, 50, "測試"));
        sheet.add(Modifier.increased(VaalKeys.LIGHT_RADIUS, 0.3, "測試"));
        sheet.add(Modifier.flat(VaalKeys.ARMOUR_FROM_LIFE_LOST_8S, 35, "測試"));
        sheet.addAll(UniqueDerived.vaalLate(sheet, 400));
        assertEquals(10, sheet.flat(StatKeys.LIFE_REGEN, Set.of()), 1e-9, "照歸零之前的 200 護盾：每秒 10 生命");
        assertEquals(0, sheet.get(StatKeys.ES_MAX), 1e-9, "無法擁有能量護盾");
        assertEquals(0, tw.exilecore.character.PlayerState.maxEnergyShield(sheet), 1e-9);
        assertEquals(-0.1, sheet.increased(StatKeys.ES_RECHARGE, Set.of()), 1e-9, "移動速度的減少也套用");
        assertEquals(0.15, sheet.increased(StatKeys.SKILL_AREA, Set.of()), 1e-9, "30% 光照範圍 × 50%");
        assertEquals(140, sheet.flat(StatKeys.ARMOUR, Set.of()), 1e-9, "失去 400 生命 × 35%");
    }

    @Test
    void smallHelpers() {
        assertEquals(100, tw.exilecore.combat.DodgeRoll.sacrificed(800, 1000, 10), 1e-9);
        assertEquals(49, tw.exilecore.combat.DodgeRoll.sacrificed(50, 1000, 10), 1e-9, "最多扣到剩 1 點");
        assertEquals(0, tw.exilecore.combat.DodgeRoll.sacrificed(1, 1000, 10), 1e-9);
    }
}
