package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/** 2026-11-01 照 PoE2 Armour Breaker（碎甲錘）：雙持時攻擊速度更多 40%。 */
class ArmourBreakerTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void dualWieldingGivesFortyPercentMoreAttackSpeed() {
        final SkillBuild build = SkillBuild.of(config, config.skill("plate_crush_hammer"), 20, 90, false, List.of(), Set.of(), new StatSheet());
        assertSame(build, SkillService.dualWieldSpeed(build, false), "沒有雙持：不變");
        final SkillBuild dual = SkillService.dualWieldSpeed(build, true);
        final double more = dual.profile().effective(new StatSheet()).more(StatKeys.ATTACK_SPEED, Set.of());
        assertEquals(1.4, more, 1e-9, "雙持：攻擊速度更多 40%");
        assertEquals("雙持時攻擊速度更多 40%", ModifierText.describe(
                new Modifier(SkillService.DUAL_WIELD_ATTACK_SPEED, ModType.FLAT, 40, Set.of(), "")));
    }
}
