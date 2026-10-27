package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Emergency Reload：強化的弩矢照每個彈匣的發數用掉，到期或用完就沒了。 */
class EmergencyReloadTest {

    @Test
    void empoweredBoltsRunOutPerClip() {
        final SkillRuntime runtime = new SkillRuntime();
        runtime.empoweredBolts.put("a", 2);
        runtime.empoweredBolts.put("b", 1);
        runtime.empoweredUntil = 100;
        assertTrue(runtime.takeEmpoweredBolt("a", 10));
        assertTrue(runtime.takeEmpoweredBolt("a", 11));
        assertFalse(runtime.takeEmpoweredBolt("a", 12), "a 的彈匣用完了");
        assertTrue(runtime.takeEmpoweredBolt("b", 13));
        assertTrue(runtime.empoweredBolts.isEmpty());
        assertFalse(runtime.takeEmpoweredBolt("c", 14), "沒被強化的技能");
    }

    @Test
    void empoweredBoltsExpire() {
        final SkillRuntime runtime = new SkillRuntime();
        runtime.empoweredBolts.put("a", 5);
        runtime.empoweredUntil = 100;
        assertFalse(runtime.takeEmpoweredBolt("a", 100), "6 秒到了");
        assertTrue(runtime.empoweredBolts.isEmpty());
    }
}
