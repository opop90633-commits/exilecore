package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.TargetConditions;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeConditions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2：保留生命時「滿血」＝目前生命等於沒被保留的最大生命；低血照最大生命。 */
class FullLifeReservationTest {

    @Test
    void fullLifeMeansUnreservedLife() {
        final StatSheet sheet = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.LIFE_MAX, 1000, "測試"),
                Modifier.flat(LineageKeys.LIFE_RESERVED, 20, "血契保留")));
        final double unreserved = PlayerState.unreservedLife(sheet);
        assertEquals(800, unreserved, 1e-6);
        assertTrue(PlayerState.isFullLife(800, unreserved), "保留 20%：回到 800 就算滿血");
        assertFalse(PlayerState.isFullLife(799, unreserved));
        assertFalse(PlayerState.isFullLife(800, 1000), "沒保留時 800/1000 不是滿血");
        assertEquals(1.0, PlayerState.lifeFractionOfUnreserved(800, unreserved), 1e-9);
        assertEquals(0.5, PlayerState.lifeFractionOfUnreserved(400, unreserved), 1e-9);
        assertEquals(0, PlayerState.missingLife(800, unreserved), 1e-9, "保留的部分不算缺血");
        assertEquals(300, PlayerState.missingLife(500, unreserved), 1e-9);
    }

    @Test
    void entityFullFraction() {
        assertTrue(LifeChecks.fullFraction(1.0));
        assertTrue(LifeChecks.fullFraction(0.9995));
        assertFalse(LifeChecks.fullFraction(0.99));
    }

    @Test
    void targetConditionsUseTheFullLifeFlag() {
        // 玩家目標保留了生命：生命比例 0.8（照最大生命），但已經是滿血
        final TargetConditions.Target reservedPlayer = new TargetConditions.Target(3, false, false, false, false, false, false, false,
                0.8, false, false, false, false, false, false, false, false, false, true);
        assertTrue(TargetConditions.tags(reservedPlayer).contains(TreeConditions.VS_FULL_LIFE));
        assertFalse(TargetConditions.tags(reservedPlayer).contains(TreeConditions.VS_LOW_LIFE), "低血照最大生命");
        // 舊的建構子：照生命比例（怪物）
        final TargetConditions.Target monster = new TargetConditions.Target(3, false, false, false, false, false, false, false,
                0.8, false, false, false, false, false, false, false, false, false);
        assertFalse(monster.fullLife());
        assertFalse(TargetConditions.tags(monster).contains(TreeConditions.VS_FULL_LIFE));
        final TargetConditions.Target fullMonster = new TargetConditions.Target(3, false, false, false, false, false, false, false,
                1.0, false, false, false, false, false, false, false, false, false);
        assertTrue(fullMonster.fullLife());
    }

    @Test
    void painAttunementFullFlagOverridesFraction() {
        final StatSheet pain = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.FLAG_PAIN_ATTUNEMENT, 1, "測試")));
        assertEquals(1, BuffImpact.painAttunement(pain, 0.8, true).size(), "保留生命回滿：滿血那一半生效");
        assertTrue(BuffImpact.painAttunement(pain, 0.8, false).isEmpty());
        assertEquals(1, BuffImpact.painAttunement(pain, 1.0).size(), "舊的寫法照比例");
    }

    @Test
    void panelShowsReservedLife() {
        assertEquals("", StatsReport.reservedLifeText(1000, 0));
        assertEquals(" <dark_gray>（保留 20%，可用 800）", StatsReport.reservedLifeText(1000, 0.2));
        assertEquals(" <dark_gray>（保留 12.5%，可用 875）", StatsReport.reservedLifeText(1000, 0.125));
    }
}
