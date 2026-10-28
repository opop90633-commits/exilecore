package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SpellParityKeys;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.SupportExtraKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 0.5：戰旗、掠閃、喚駒保留精魂；掠閃距離、雷雲連鎖、天柱雷追加雷、地火柱火山彈逐級；
 * 引雷印、霜縛印改成電擊／凍結累積與觸發的增益。
 */
class SpellReservationParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String skill, final int level, final Set<String> weapons) {
        return SkillBuild.of(config, config.skill(skill), level, 100, false, List.of(), weapons, new StatSheet());
    }

    @Test
    void bannersBlinkAndMountReserveSpirit() {
        for (final String banner : List.of("defiance_banner", "war_banner", "dread_banner")) {
            final SkillDefinition definition = config.skill(banner);
            assertTrue(definition.reservesSpiritOnly(), banner);
            assertTrue(definition.aura().reservationOnly(), banner);
            assertEquals(Mechanic.WARCRY, definition.mechanic(), banner + " 照舊是插旗（戰吼的運作方式）");
            assertEquals(30, build(banner, 20, Set.of()).reserve(), 1e-9, banner);
            assertTrue(definition.aura().effects().isEmpty(), "只保留精魂，沒有光環效果");
            assertTrue(build(banner, 20, Set.of()).auraModifiers().isEmpty());
        }
        final SkillDefinition blink = config.skill("blink");
        assertTrue(blink.reservesSpiritOnly());
        assertEquals(60, build("blink", 1, Set.of()).reserve(), 1e-9);
        assertEquals(0, blink.mana().at(1), 1e-9, "PoE2 的掠閃不花魔力");
        assertEquals(0, blink.mana().at(20), 1e-9);
        assertEquals(100, build("summon_mount", 1, Set.of(Tags.BOW)).reserve(), 1e-9);
        // 光環照舊是光環
        assertFalse(config.skill("overwhelming_presence").reservesSpiritOnly());
    }

    @Test
    void reservationOnlyAuraNeedsNoEffectsButRealAurasStillDo() {
        final AuraSpec reserveOnly = new AuraSpec(30, 8, false, List.of(), null, 100);
        assertTrue(reserveOnly.reservationOnly());
        final AuraSpec real = new AuraSpec(30, 8, true,
                List.of(new ScaledMod(StatKeys.ARMOUR, tw.exilecore.stats.ModType.INCREASED, Scaled.of(10), true, Set.of())), null, 100);
        assertFalse(real.reservationOnly());
        // 精魂不夠：保留 60 的掠閃在剩 59 精魂時開不了
        assertNull(AuraService.fit(build("blink", 1, Set.of()), 59));
        assertNotNull(AuraService.fit(build("blink", 1, Set.of()), 60));
    }

    @Test
    void blinkDistanceFollowsTheGemTable() {
        assertEquals(tw.exilecore.core.Units.metres(4.0), build("blink", 1, Set.of()).range(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(tw.exilecore.core.Units.metres(4.4), build("blink", 10, Set.of()).range(), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(4.9), build("blink", 20, Set.of()).range(), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(5.5), config.skill("blink").params().dash().range(40, 0), 1e-9);
        // 沒寫逐級的位移照舊
        final SkillParams.Dash fixed = new SkillParams.Dash(0, Scaled.of(0), Scaled.of(0));
        assertEquals(8, fixed.range(20, 8), 1e-9);
    }

    @Test
    void stormOrbChainsVolcanoShardsConduitBolts() {
        final SkillParams.Zone orb = config.skill("storm_orb").params().zone();
        assertNotNull(orb.bolts());
        assertEquals(3, orb.bolts().chains().intAt(1));
        assertEquals(5, orb.bolts().chains().intAt(20));
        assertEquals(9, orb.bolts().chains().intAt(40));
        assertEquals(tw.exilecore.core.Units.metres(5.6), orb.bolts().range(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(tw.exilecore.core.Units.metres(3.5), orb.bolts().chainRange(), 1e-9);
        final SkillParams.Zone volcano = config.skill("volcano").params().zone();
        assertNotNull(volcano.shower());
        assertEquals(3, volcano.shower().count().intAt(1));
        assertEquals(5, volcano.shower().count().intAt(20));
        assertEquals(6, volcano.shower().count().intAt(40));
        assertEquals(tw.exilecore.core.Units.metres(0.6), volcano.shower().radius(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units）
        assertEquals(8, volcano.duration(), 1e-9);
        assertEquals(0.5, volcano.interval(), 1e-9);
        double bolts1 = 0;
        double bolts20 = 0;
        for (final ScaledMod mod : config.levelModifiers("lightning_pillar")) {
            if (mod.stat().equals(SpellParityKeys.SHOCKED_BOLTS)) {
                bolts1 = mod.value().at(1);
                bolts20 = mod.value().at(20);
            }
        }
        assertEquals(1, bolts1, 1e-9);
        assertEquals(2, bolts20, 1e-9);
    }

    /** 一維座標當目標（距離 = 差的絕對值）。 */
    private static double distance(final Double a, final Double b) {
        return Math.abs(a - b);
    }

    @Test
    void boltChainsToNearestUnhitWithinRange() {
        final List<Double> inRange = List.of(0.0);
        final List<Double> all = List.of(0.0, 3.0, 5.0, 20.0, 1.0);
        final List<Double> chain = ZonePulses.boltChain(inRange, all, 5, 3.5, SpellReservationParityTest::distance, new Random(1));
        // 0 → 1（最近）→ 3 → 5；20 太遠斷掉
        assertEquals(List.of(0.0, 1.0, 3.0, 5.0), chain);
        assertEquals(List.of(0.0, 1.0), ZonePulses.boltChain(inRange, all, 1, 3.5, SpellReservationParityTest::distance, new Random(1)));
        assertTrue(ZonePulses.boltChain(List.<Double>of(), all, 3, 3.5, SpellReservationParityTest::distance, new Random(1)).isEmpty());
    }

    @Test
    void showerAndExtraBoltsStayInside() {
        final Random random = new Random(7);
        final List<double[]> points = ZonePulses.showerPoints(6, 3.0, random);
        assertEquals(6, points.size());
        for (final double[] p : points) {
            assertTrue(Math.hypot(p[0], p[1]) <= 3.0 + 1e-9);
        }
        final List<String> hits = List.of("a", "b");
        final List<String> picked = ZonePulses.distribute(hits, 2, random);
        assertEquals(2, picked.size());
        assertTrue(hits.containsAll(picked));
        assertTrue(ZonePulses.distribute(List.<String>of(), 2, random).isEmpty());
    }

    @Test
    void marksRaiseBuildupInsteadOfLoweringResistance() {
        final SkillParams.Mark voltaic = config.skill("voltaic_mark").params().mark();
        assertEquals(0, voltaic.taken(), 1e-9, "PoE2 的引雷印不增加受到的傷害");
        assertFalse(voltaic.selfOnly(), "誰打都算");
        assertEquals(1, voltaic.effects().size());
        final Modifier electrocute = voltaic.effects().getFirst().toModifier(20, 1.0, "x");
        assertEquals(SpellParityKeys.ELECTROCUTE_BUILDUP_TAKEN, electrocute.stat());
        assertEquals(0.35, electrocute.value(), 1e-9);
        assertEquals(0.25, voltaic.effects().getFirst().toModifier(1, 1.0, "x").value(), 1e-9);
        assertEquals(0.45, voltaic.effects().getFirst().toModifier(40, 1.0, "x").value(), 1e-9);
        final SkillParams.Mark freezing = config.skill("freezing_mark").params().mark();
        final Modifier freeze = freezing.effects().getFirst().toModifier(20, 1.0, "x");
        assertEquals(SupportExtraKeys.FREEZE_BUILDUP_TAKEN, freeze.stat());
        assertEquals(tw.exilecore.stats.ModType.MORE, freeze.type());
        assertEquals(0.35, freeze.value(), 1e-9);
        // 電擊累積：攻擊者增加 20%、印記增加 35% 加在一起，再乘攻擊者的更多
        assertEquals((1 + 0.2 + 0.35) * 1.5, AilmentService.electrocuteBuildupScale(0.2, 1.5, 0.35), 1e-9);
        assertEquals(0, AilmentService.electrocuteBuildupScale(-2, 1, 0), 1e-9);
    }

    @Test
    void markActivationBuff() {
        final SkillDefinition voltaic = config.skill("voltaic_mark");
        final SkillDefinition freezing = config.skill("freezing_mark");
        assertEquals(AilmentType.ELECTROCUTE, MarkActivation.trigger(voltaic));
        assertEquals(AilmentType.FREEZE, MarkActivation.trigger(freezing));
        assertNull(MarkActivation.trigger(config.skill("bloodhounds_mark")));
        assertEquals(DamageType.LIGHTNING, MarkActivation.gainType(AilmentType.ELECTROCUTE));
        assertEquals(DamageType.COLD, MarkActivation.gainType(AilmentType.FREEZE));
        for (final SkillDefinition mark : List.of(voltaic, freezing)) {
            assertEquals(30, MarkActivation.flat(mark.modifiers(), SpellParityKeys.MARK_ACTIVATION_GAIN), 1e-9);
            assertEquals(10, MarkActivation.flat(mark.modifiers(), SpellParityKeys.MARK_ACTIVATION_SECONDS), 1e-9);
        }
    }

    @Test
    void newKeysHaveText() {
        for (final Map.Entry<String, Double> entry : Map.of(SpellParityKeys.SHOCKED_BOLTS, 2.0, SpellParityKeys.MARK_ACTIVATION_GAIN, 30.0,
                SpellParityKeys.MARK_ACTIVATION_SECONDS, 10.0).entrySet()) {
            final String text = ModifierText.describe(Modifier.flat(entry.getKey(), entry.getValue(), "x"));
            assertFalse(text.startsWith("特殊效果"), entry.getKey() + "：" + text);
        }
        final String electrocute = ModifierText.describe(Modifier.increased(SpellParityKeys.ELECTROCUTE_BUILDUP_TAKEN, 0.35, "x"));
        assertTrue(electrocute.contains("電擊累積") && electrocute.contains("35"), electrocute);
    }
}
