package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.GrantedSkillKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：烈陽珠、架格、血脈牽、力印、重生之儀、衰雷環、聖地、噬屍宴補上的機制。 */
class WeaponGrantedParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id, final int level) {
        return SkillBuild.of(config, config.skill(id), level, 90, false, List.of(), Set.of(), new StatSheet());
    }

    @Test
    void solarOrbIgnitesAsThoughDealingItsOwnTable() {
        final SkillBuild orb = build("scorching_sun_pearl", 1);
        final StatSheet effective = orb.profile().effective(new StatSheet());
        assertEquals(6, effective.flat(GrantedSkillKeys.AS_THOUGH_MIN, orb.profile().tags()), 1e-9);
        assertEquals(8, effective.flat(GrantedSkillKeys.AS_THOUGH_MAX, orb.profile().tags()), 1e-9);
        final SkillBuild twenty = build("scorching_sun_pearl", 20);
        final StatSheet high = twenty.profile().effective(new StatSheet());
        assertEquals(287, high.flat(GrantedSkillKeys.AS_THOUGH_MIN, twenty.profile().tags()), 1e-9);
        assertEquals(430, high.flat(GrantedSkillKeys.AS_THOUGH_MAX, twenty.profile().tags()), 1e-9);
        assertEquals(0, effective.flat(StatKeys.IGNITE_CHANCE, orb.profile().tags()), 1e-9, "不再用點燃機率代替");
        // 1 公尺 ÷ 2.5 公尺：跟爆炸一起吃範圍
        assertEquals(1.0, SolarOrbs.igniteRadius(2.5), 1e-9);
        assertEquals(1.5, SolarOrbs.igniteRadius(3.75), 1e-9);
        assertTrue(SolarOrbs.due(0));
        assertFalse(SolarOrbs.due(5));
        assertTrue(SolarOrbs.due(10));
    }

    @Test
    void exsanguineFiresThreeTendrils() {
        assertEquals(3, SkillExecutor.chainBeams(build("blood_vein_tether", 1)));
        assertEquals(2, build("blood_vein_tether", 1).chains(), "每道連鎖 2 次");
        assertTrue(build("blood_vein_tether", 1).spread() > 0, "扇形");
        assertEquals(1, SkillExecutor.chainBeams(build("chain_thunder", 1)), "鏈雷照投射物數量");
    }

    @Test
    void sigilStagesNeedManaAndOneSecond() {
        final SacredGrounds.Stages stages = new SacredGrounds.Stages();
        assertFalse(stages.spend(40, 50, 0));
        assertTrue(stages.spend(20, 50, 0), "累積到 50 升一階");
        assertEquals(1, stages.stages);
        assertFalse(stages.spend(60, 50, 10), "不到 1 秒不升");
        assertEquals(1, stages.stages);
        assertTrue(stages.advance(50, 20), "隔 1 秒，留著的魔力升階");
        assertEquals(2, stages.stages);
        stages.spend(500, 50, 40);
        stages.advance(50, 60);
        stages.advance(50, 80);
        assertEquals(4, stages.stages);
        assertFalse(stages.spend(500, 50, 200), "最多 4 階");
        assertEquals(4, stages.stages);
        assertEquals(50, SacredGrounds.manaPercent(SacredGrounds.POWER), 1e-9);
        assertEquals(35, SacredGrounds.manaPercent(SacredGrounds.RESTORATION), 1e-9);
    }

    @Test
    void sigilRadiiFollowRef() {
        assertEquals(tw.exilecore.core.Units.metres(3.0), SacredGrounds.radius(SacredGrounds.POWER, 0, 1), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(3.8), SacredGrounds.radius(SacredGrounds.POWER, 0.8, 1), 1e-9, "品質 +0.8 公尺");
        assertEquals(tw.exilecore.core.Units.metres(4.5) * 2, SacredGrounds.radius(SacredGrounds.RESTORATION, 0, 2), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.2), SacredGrounds.radius(SacredGrounds.CONSECRATE, 0.8, 1), 1e-9, "聖地的品質是施法速度");
        // 品質寫在 gem_quality.yml：20% 品質 +0.8 公尺
        final SkillBuild sigil = SkillBuild.of(config, config.skill("might_seal"), 20, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                SkillService.qualityModifiers(config.skill("might_seal"), 20));
        assertEquals(0.8, sigil.qualityFlat(SacredGrounds.SIGIL_RADIUS), 1e-9);
    }

    @Test
    void sigilBuffsArePerStageValues() {
        final SkillDefinition power = config.skill("might_seal");
        final ScaledMod spell = power.params().mark().effects().getFirst();
        assertEquals(0.10, spell.toModifier(1, 1, "").value(), 1e-9);
        assertEquals(0.14 * 4, spell.toModifier(20, 4, "").value(), 1e-9, "4 階 × 14%");
        final SkillDefinition rite = config.skill("rebirth_ceremony");
        double regen = 0;
        double armour = 0;
        for (final ScaledMod effect : rite.params().mark().effects()) {
            if (effect.stat().equals(StatKeys.LIFE_REGEN)) {
                regen = effect.toModifier(20, 1, "").value();
            } else if (effect.stat().equals(StatKeys.ARMOUR)) {
                armour = effect.toModifier(40, 1, "").value();
            }
        }
        assertEquals(1300 / 60.0, regen, 0.01, "每分鐘 1300 生命");
        assertEquals(0.20, armour, 1e-9);
        assertTrue(SacredGrounds.handles("rebirth_ceremony") && SacredGrounds.sigil("rebirth_ceremony"));
        assertEquals(0, config.skill("corpse_devour_feast").cooldown(), 1e-9, "噬屍宴沒有冷卻");
    }

    @Test
    void consecratedGroundGrantsRegenAndCurseReduction() {
        final SkillDefinition consecrate = config.skill("holy_ground");
        double curse = 0;
        double regen = 0;
        for (final ScaledMod effect : consecrate.params().mark().effects()) {
            final Modifier mod = effect.toModifier(20, 1, "");
            if (effect.stat().equals(StatKeys.CURSE_EFFECT_ON_SELF)) {
                curse = mod.value();
            } else if (effect.stat().equals(StatKeys.LIFE_REGEN_PERCENT)) {
                regen = mod.value();
            }
        }
        assertEquals(5 * 1.38, regen, 1e-6);
        assertEquals(-0.5 * 1.38, curse, 0.006);
        assertEquals("受到的詛咒效果減少 50%", SacredGrounds.effectText(Modifier.increased(StatKeys.CURSE_EFFECT_ON_SELF, -0.5, "")));
    }

    @Test
    void feastEatsUpToFivePlusQuality() {
        assertEquals(5, SacredGrounds.feastCount(10, 0));
        assertEquals(8, SacredGrounds.feastCount(10, 3));
        assertEquals(2, SacredGrounds.feastCount(2, 3));
        assertEquals(0, SacredGrounds.feastCount(0, 0));
        assertEquals(35, SacredGrounds.feastRate(21, 5), 1e-9, "21 × 5 具 ÷ 3 秒");
    }

    @Test
    void enervatingNovaIsARingThatHitsHarderOutside() {
        assertFalse(EnervatingNova.inRing(0.5, 2.6), "1 格內不打");
        assertTrue(EnervatingNova.inRing(1.0, 2.6));
        assertEquals(1.0, EnervatingNova.distanceMore(1.0, 2.6), 1e-9);
        assertEquals(2.0, EnervatingNova.distanceMore(2.6, 2.6), 1e-9, "外緣更多 100%");
        assertEquals(1.5, EnervatingNova.distanceMore(1.8, 2.6), 1e-9);
        final SkillBuild nova = build("fading_thunder_ring", 1);
        assertEquals(100, nova.profile().effective(new StatSheet()).flat(StatKeys.ELECTROCUTE_CHANCE, nova.profile().contextFor(tw.exilecore.stats.DamageType.LIGHTNING)),
                1e-9, "閃電傷害會電擊");
    }

    @Test
    void parriedHitsCannotBeEvaded() {
        final SkillBuild attack = build("proc_parry_sweep", 1);
        final StatSheet parrying = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.FLAG_PARRY, 1, "招架")));
        assertTrue(tw.exilecore.stats.DamageCalculator.parryable(attack.profile(), parrying));
        assertFalse(tw.exilecore.stats.DamageCalculator.parryable(attack.profile(), new StatSheet()));
        assertFalse(tw.exilecore.stats.DamageCalculator.parryable(build("blood_vein_tether", 1).profile(), parrying), "法術擋不了");
    }

    @Test
    void tooltipsAreReadable() {
        for (final String id : SacredGrounds.SKILLS) {
            final String text = SacredGrounds.tooltip(build(id, 20));
            assertFalse(text.contains("特殊效果"), id + "：" + text);
            assertFalse(text.contains("要拿盾"), id + "：" + text);
        }
    }
}
