package tw.exilecore.monster;

import org.junit.jupiter.api.Test;
import tw.exilecore.skill.GroundDecals;
import tw.exilecore.stats.DamageType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「怪物死掉冒出來的粒子換成比較明顯的、用材質包畫的」：效果圖的時間、顏色。 */
class MonsterFxTimingTest {

    @Test
    void raiseSoulSinksBackExactlyWhenTheMonsterGetsUp() {
        assertEquals(MonsterMechanics.RAISE_DELAY, GroundDecals.SPRITE_LEAD + Arrays.stream(MonsterMechanics.RAISE_SOUL_LEGS).sum(),
                "魂火沉回屍體的那一刻剛好爬起來");
        for (final int leg : MonsterMechanics.RAISE_SOUL_LEGS) {
            assertTrue(leg > 0 && leg <= 59, "傳送的補間最多 59 tick");
        }
    }

    @Test
    void soulFlightTimeFollowsDistance() {
        assertEquals(6, MonsterMechanics.soulTicks(0));
        assertEquals(6, MonsterMechanics.soulTicks(3));
        assertEquals(10, MonsterMechanics.soulTicks(8));
        assertEquals(24, MonsterMechanics.soulTicks(MonsterMechanics.SOUL_RADIUS * 2));
    }

    @Test
    void enemyDamageIsAlwaysRed() {
        // 2026-11-07 照站長「敵人給的傷害類的希望都是紅色」
        final Set<Integer> colours = new HashSet<>();
        for (final DamageType type : DamageType.values()) {
            colours.add(MonsterMechanics.typeColor(type));
        }
        assertEquals(Set.of(MonsterMechanics.ENEMY_DAMAGE_RED), colours, "每種傷害都是紅色");
    }

    @Test
    void crystalIdsArePerMonsterAndIndex() {
        final java.util.UUID id = java.util.UUID.randomUUID();
        assertTrue(MonsterMechanics.crystalId(id, 2).startsWith("mobcrystal:" + id + ":"));
        assertTrue(!MonsterMechanics.crystalId(id, 1).equals(MonsterMechanics.crystalId(id, 2)));
    }
}
