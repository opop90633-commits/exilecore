package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Whirling Assault（迴旋棍步）：一路前進、沿路轉好幾圈各打一次範圍、+1 秒總攻擊時間、品質。 */
class WhirlingAssaultTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static final Set<String> STAFF = Set.of(Tags.QUARTERSTAFF, Tags.MELEE, Tags.TWO_HAND);

    @Test
    void definitionMatchesRef() {
        final SkillDefinition whirl = config.skill("spinning_staff_step");
        assertEquals(Mechanic.DASH, whirl.mechanic());
        assertEquals(11, whirl.tier());
        assertTrue(whirl.tags().containsAll(List.of(Tags.ATTACK, Tags.MELEE, Tags.AREA, Tags.STRIKE)));
        assertEquals(0.85, whirl.attackSpeed(), 1e-9, "攻擊速度 85%");
        assertEquals(0.4, whirl.effectiveness().at(1), 1e-9);
        assertEquals(1.63, whirl.effectiveness().at(20), 1e-9);
        assertEquals(7.24, whirl.effectiveness().at(40), 1e-9);
        final SkillParams.Dash dash = whirl.params().dash();
        assertTrue(dash.whirls());
        assertEquals(4, dash.spins());
        assertEquals(tw.exilecore.core.Units.metres(2.8), dash.spinRadius(), 1e-9, "長棍 1.4 米 + 1.4 米"); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(0, dash.hitWidth(), 1e-9, "不再是順路一條線");
        assertEquals(0, whirl.params().knockback(), 1e-9, "原作沒有擊退");
        assertEquals(tw.exilecore.core.Units.metres(4.2), whirl.params().range(), 1e-9, "attack_maximum_action_distance_+ 42"); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertTrue(whirl.dealsDamage());
        assertTrue(whirl.modifiers().stream().anyMatch(m -> m.stat().equals(SkillProfiles.ADDED_TIME) && Math.abs(m.value() - 1.0) < 1e-9),
                "+1 秒總攻擊時間");
    }

    @Test
    void addedSecondIsNotScaledByAttackSpeed() {
        final SkillDefinition whirl = config.skill("spinning_staff_step");
        final SkillBuild slow = SkillBuild.of(config, whirl, 20, 90, false, List.of(), STAFF, new StatSheet());
        final StatSheet fast = new StatSheet();
        fast.add(Modifier.increased(StatKeys.ATTACK_SPEED, 1.0, "test"));
        final SkillBuild quick = SkillBuild.of(config, whirl, 20, 90, false, List.of(), STAFF, fast);
        final double slowTicks = slow.intervalTicks(new StatSheet());
        final double quickTicks = quick.intervalTicks(fast);
        // 攻擊時間那一段吃攻速（減半），+1 秒（20 tick）那一段不吃
        assertEquals((slowTicks - 20) / 2 + 20, quickTicks, 0.6);
        assertTrue(quickTicks > 20);
    }

    @Test
    void spinsAreSpreadEvenlyOverTheWholeAction() {
        // 37 tick 的動作：4 圈在 12.5%、37.5%、62.5%、87.5%
        assertEquals(5, WhirlingAssault.spinTick(0, 4, 37));
        assertEquals(14, WhirlingAssault.spinTick(1, 4, 37));
        assertEquals(23, WhirlingAssault.spinTick(2, 4, 37));
        assertEquals(32, WhirlingAssault.spinTick(3, 4, 37));
        int previous = 0;
        for (int i = 0; i < 4; i++) {
            final int at = WhirlingAssault.spinTick(i, 4, 6);
            assertTrue(at > previous || at == previous && at == 1, "遞增");
            assertTrue(at >= 1 && at <= 6);
            previous = at;
        }
        assertEquals(0.125, WhirlingAssault.fraction(0, 4), 1e-9);
        assertEquals(0.875, WhirlingAssault.fraction(3, 4), 1e-9);
        assertEquals(1, WhirlingAssault.spinTick(0, 4, 0), "動作時間最少 4 tick");
    }

    @Test
    void walksAtConstantSpeedAndArrivesAtTheEnd() {
        assertEquals(0, WhirlingAssault.progress(0, 40), 1e-9);
        assertEquals(0.5, WhirlingAssault.progress(20, 40), 1e-9);
        assertEquals(1, WhirlingAssault.progress(40, 40), 1e-9);
        assertEquals(1, WhirlingAssault.progress(50, 40), 1e-9);
    }

    @Test
    void spinRadiusGrowsWithLevelArea() {
        final SkillDefinition whirl = config.skill("spinning_staff_step");
        final SkillBuild one = SkillBuild.of(config, whirl, 1, 90, false, List.of(), STAFF, new StatSheet());
        final SkillBuild twenty = SkillBuild.of(config, whirl, 20, 90, false, List.of(), STAFF, new StatSheet());
        assertEquals(Math.sqrt(1.01), one.areaScale(), 1e-9);
        assertEquals(Math.sqrt(1.20), twenty.areaScale(), 1e-9, "20 級範圍更多 20%（半徑乘根號）");
    }

    @Test
    void qualityIsAttackSpeedAndAdvancedIsMorePhysical() {
        final SkillDefinition whirl = config.skill("spinning_staff_step");
        final List<Modifier> normal = SkillService.qualityModifiers(whirl, 20);
        assertEquals(1, normal.size());
        assertEquals(StatKeys.ATTACK_SPEED, normal.get(0).stat());
        assertEquals(ModType.INCREASED, normal.get(0).type());
        assertEquals(0.20, normal.get(0).value(), 1e-9, "20% 品質：增加 20% 攻擊速度");
        final List<Modifier> advanced = tw.exilecore.ascendancy.AscBuild.advanced("spinning_staff_step", 20, normal);
        assertTrue(advanced.stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && m.type() == ModType.MORE
                && Math.abs(m.value() - 0.20) < 1e-9 && m.tags().contains("physical")), "高階奇術：更多 20% 物理傷害 " + advanced);
    }
}
