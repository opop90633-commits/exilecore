package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：弓技能（螺旋箭雨、荊棘矢、電殛矢、召霆矢、凜霜扇矢、雷鳴箭、蝕毒箭）補上的機制，與躍退射刪掉。 */
class BowParityMechanicsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static double flat(final String skill, final String stat) {
        double sum = 0;
        for (final Modifier modifier : config.skill(skill).modifiers()) {
            if (modifier.stat().equals(stat)) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    private static double level(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        throw new AssertionError(skill + " 沒有 " + stat);
    }

    @Test
    void spiralVolleyFrenzyMath() {
        assertEquals(1.0, SpiralVolley.damageMultiplier(0, 100, 30), 1e-9, "沒用掉怒星不加");
        assertEquals(2 * 1.3, SpiralVolley.damageMultiplier(1, 100, 30), 1e-9);
        assertEquals(2 * 1.9, SpiralVolley.damageMultiplier(3, 100, 30), 1e-9, "每顆 30% 相加");
        assertEquals(0, SpiralVolley.extraChains(0, 1));
        assertEquals(3, SpiralVolley.extraChains(3, 1));
        assertEquals(2, SpiralVolley.aimed(25, 2));
        assertEquals(25, SpiralVolley.aimed(25, 40));
        assertEquals(20, SkillBuild.projectileCap(1));
        assertEquals(20, SkillBuild.projectileCap(20));
        assertEquals(35, SkillBuild.projectileCap(25), "基礎超過 20 支的照自己的上限");
        final SkillDefinition helix = config.skill("helix_arrow_rain");
        assertEquals(25, helix.params().projectiles().intAt(1));
        assertEquals(0, helix.params().wobble(), 1e-9);
        assertEquals(100, flat("helix_arrow_rain", SpiralVolley.CONSUMED_MORE), 1e-9);
        assertEquals(30, flat("helix_arrow_rain", SpiralVolley.CONSUMED_MORE_PER), 1e-9);
        assertEquals(1, flat("helix_arrow_rain", SpiralVolley.CONSUMED_CHAINS), 1e-9);
        assertEquals(6, flat("helix_arrow_rain", SpiralVolley.TARGET_RADIUS), 1e-9);
    }

    @Test
    void vinePlantStoresStrongestPoison() {
        assertEquals(50, VinePlants.extraDps(100, 50, 1.0), 1e-9);
        assertEquals(55, VinePlants.extraDps(100, 50, 1.1), 1e-9, "20% 品質更多 10%");
        assertEquals(0, VinePlants.extraDps(0, 50, 1.1), 1e-9);
        assertArrayEquals(new double[] {80, 140}, VinePlants.store(0, 0, 80, 100, 40), 1e-9);
        assertArrayEquals(new double[] {80, 140}, VinePlants.store(80, 140, 50, 110, 40), 1e-9, "比較弱的不換");
        assertArrayEquals(new double[] {90, 150}, VinePlants.store(80, 140, 90, 110, 40), 1e-9, "比較強的換掉、重新計時");
        assertArrayEquals(new double[] {50, 190}, VinePlants.store(80, 140, 50, 150, 40), 1e-9, "過期了直接換");
        assertEquals(50, flat("thorn_shaft", VinePlants.STORED), 1e-9);
        final List<Modifier> quality = GemQuality.modifiers("thorn_shaft", 20, "q");
        assertEquals(1, quality.size());
        assertEquals(VinePlants.STORED, quality.getFirst().stat());
        assertEquals(0.10, quality.getFirst().value(), 1e-9);
        assertNotNull(GemQualityText.body("thorn_shaft", quality, 20));
        assertFalse(GemQualityText.NO_EFFECT.containsKey("thorn_shaft"));
    }

    @Test
    void electrocutingRodLastsTwentyFiveSeconds() {
        assertEquals(1.0, ElectrocutingRods.electrocuteSource(true, 0), 1e-9, "插著棒：沒有來源也累積");
        assertEquals(0, ElectrocutingRods.electrocuteSource(false, 0), 1e-9);
        assertEquals(0.4, ElectrocutingRods.electrocuteSource(false, 0.4), 1e-9);
        assertEquals(500, ElectrocutingRods.rodTicks(25, 1.0));
        assertEquals(750, ElectrocutingRods.rodTicks(25, 1.5), "吃技能持續時間");
        assertEquals(0, ElectrocutingRods.rodTicks(0, 1.0));
        assertEquals(25, flat("electrocute_shaft", ElectrocutingRods.ROD_SECONDS), 1e-9);
        assertEquals(0, flat("electrocute_shaft", StatKeys.ELECTROCUTE_BUILDUP), 1e-9, "原作沒有電擊累積更多 100%");
    }

    @Test
    void stormcallerArrowStagesAndSpread() {
        assertEquals(1.5, StormcallerArrows.asThough(50), 1e-9);
        assertEquals(1.0, StormcallerArrows.asThough(-10), 1e-9);
        assertEquals(4.5, StormcallerArrows.spreadRadius(3.0, 1.5), 1e-9);
        assertEquals(2.7, StormcallerArrows.speed(1.0), 1e-9, "54 公尺／秒 ÷ 20 tick");
        assertEquals(5.4, StormcallerArrows.speed(2.0), 1e-9, "吃投射物速度");
        assertEquals(6.0, StormcallerArrows.speed(10.0), 1e-9, "跟一般投射物一樣最多 6 格／tick");
        assertEquals(1.5, config.skill("thunder_call_shaft").params().delay(), 1e-9, "箭插 1.5 秒後落雷");
        assertEquals(1.5, level("thunder_call_shaft", StormcallerArrows.SPREAD_RADIUS, 1), 1e-9);
        assertEquals(3.0, level("thunder_call_shaft", StormcallerArrows.SPREAD_RADIUS, 20), 1e-9);
        assertEquals(3.7, level("thunder_call_shaft", StormcallerArrows.SPREAD_RADIUS, 40), 1e-9);
        assertEquals(50, flat("thunder_call_shaft", StormcallerArrows.SHOCK_AS_THOUGH), 1e-9);
        final SkillStages.Stage arrow = SkillStages.get("thunder_call_shaft", StormcallerArrows.ARROW);
        assertNotNull(arrow);
        assertEquals(0.62, arrow.effectiveness().at(20), 1e-9);
        final SkillProfile bolt = new SkillProfile("thunder_call_shaft", Set.of(Tags.ATTACK, Tags.AREA, Tags.PROJECTILE), 1.0, null, null, Map.of(),
                List.of(Modifier.more(StatKeys.SHOCK_CHANCE, 5.0, "召霆矢"), Modifier.flat(StormcallerArrows.SHOCK_AS_THOUGH, 50, "召霆矢"),
                        Modifier.flat("other", 1, "召霆矢")));
        final SkillProfile only = StormcallerArrows.arrowOnly(bolt);
        assertEquals(1, only.local().size(), "箭沒有落雷的感電加成");
        assertFalse(only.tags().contains(Tags.AREA));
    }

    @Test
    void freezingSalvoAndThunderclapAndGas() {
        assertEquals(0, FreezingSalvo.repeatsFromProjectiles(1));
        assertEquals(2, FreezingSalvo.repeatsFromProjectiles(3));
        assertEquals(3, flat("chill_fan_shafts", FreezingSalvo.FRAGMENT_RADIUS), 1e-9);
        final SkillStages.Stage beam = SkillStages.get("thunderclap_arrow", ProjectileEngine.BEAM);
        assertNotNull(beam);
        assertEquals(1.0, beam.effectiveness().at(1), 1e-9);
        assertEquals(1.0, beam.effectiveness().at(40), 1e-9, "雷束 100%，不隨等級");
        assertEquals(2, beam.count().intAt(20));
        assertEquals(20, flat("venom_etch_arrow", tw.exilecore.stats.SupportExtraKeys.ENCROACH), 1e-9);
        assertEquals(80, flat("venom_etch_arrow", tw.exilecore.stats.SupportExtraKeys.ENCROACH_MAX_KEY), 1e-9);
        assertEquals(6, flat("venom_etch_arrow", "limit.ground"), 1e-9);
    }

    @Test
    void escapeShotIsRetired() {
        assertNull(config.skill("leapback_shoot"));
        assertNull(config.skill("proc_ice_fragment_escape"));
        assertTrue(config.retiredSkill("leapback_shoot"));
        assertEquals("躍退射", config.retiredSkillName("leapback_shoot"));
    }
}
