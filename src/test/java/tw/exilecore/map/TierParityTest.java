package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-27 照站長「完全照原作，但等級不用動」：每一階的生命、傷害倍率（settings.tier_parity）。 */
class TierParityTest {

    @Test
    void tiersScaleToPoe2AreaLevelsButFinalBossesDoNot() throws Exception {
        final MapConfig maps = new MapConfig();
        maps.load(Path.of("src/main/resources/data"));
        assertEquals(1.274, maps.parityLife(1), 1e-9, "T1：我們 61 級，原作 65 級");
        assertEquals(1.0, maps.parityLife(11), 1e-9, "T11：兩邊都是 75 級");
        assertEquals(0.889, maps.parityLife(16), 1e-9, "T16：我們 84 級，原作 80 級");
        assertEquals(0.892, maps.parityDamage(16), 1e-9);
        assertEquals(1.0, maps.parityLife(0), 1e-9, "特殊實例（階級 0）不乘");
        assertEquals(8.846, maps.finalBossLifeMultiplier(16), 1e-9, // 2026-10-29 照站長 ×2（這次之前 4.423）
                "終局 Boss 照 tier_life_table，不受影響");
        assertEquals(0.5, maps.monsterLifeScale(), 1e-9, "2026-10-27 照站長：一般怪物生命砍半");
    }
}
