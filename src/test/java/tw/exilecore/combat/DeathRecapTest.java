package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 死亡回顧的一筆：持續傷害合併（回顧本身要有伺服器，不在這裡測）。 */
class DeathRecapTest {

    @Test
    void dotHitsMerge() {
        final DeathRecap.Hit first = new DeathRecap.Hit(100, "點燃", "持續燃燒", Map.of(DamageType.FIRE, 30.0), Map.of(DamageType.FIRE, 30.0),
                10, 20, 980, 1000, true);
        final DeathRecap.Hit second = new DeathRecap.Hit(110, "點燃", "持續燃燒", Map.of(DamageType.FIRE, 30.0), Map.of(DamageType.FIRE, 30.0),
                0, 30, 950, 1000, true);
        final DeathRecap.Hit merged = first.merge(second);
        assertEquals(110, merged.tick(), "時間用最後一筆的");
        assertEquals(60.0, merged.dealt().get(DamageType.FIRE), 1e-9);
        assertEquals(10.0, merged.es(), 1e-9);
        assertEquals(50.0, merged.life(), 1e-9);
        assertEquals(950.0, merged.lifeAfter(), 1e-9, "剩多少用最後一筆的");
        assertEquals(60.0, merged.total(), 1e-9);
        assertTrue(merged.dot());
    }
}
