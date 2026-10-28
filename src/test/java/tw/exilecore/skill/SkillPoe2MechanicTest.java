package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.SkillMechanicKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2 把技能的平衡等效換回原作機制（汲魂彈、毒霧、毒爆、瘴苞、火油、捷光環的標籤）。 */
class SkillPoe2MechanicTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static boolean has(final String skill, final String stat) {
        return config.skill(skill).modifiers().stream().anyMatch(m -> m.stat().equals(stat))
                || config.levelModifiers(skill).stream().anyMatch(m -> m.stat().equals(stat));
    }

    @Test
    void essenceDrainHasItsOwnDotTable() {
        assertTrue(has("essence_drain", SkillMechanicKeys.DEBUFF_CHAOS_BASE));
        assertFalse(has("essence_drain", tw.exilecore.stats.StatKeys.DEBUFF_CHAOS), "不再換算成這一下的幾 %");
    }

    /** 2026-10-26 照 PoE2：汲魂彈、疫潮有 Duration 標籤，減益的持續時間吃技能持續時間的加成。 */
    @Test
    void chaosDebuffsScaleWithSkillDuration() {
        for (final String id : new String[] {"essence_drain", "contagion"}) {
            assertTrue(config.skill(id).tags().contains(Tags.DURATION), id);
        }
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        final SkillBuild drain = SkillBuild.of(config, config.skill("essence_drain"), 1, 1, false, java.util.List.of(), java.util.Set.of(), sheet);
        assertEquals(4, tw.exilecore.ailment.AilmentService.debuffSeconds(drain.profile().effective(sheet), drain.profile()), 1e-9);
        final tw.exilecore.stats.StatSheet longer = new tw.exilecore.stats.StatSheet();
        longer.add(tw.exilecore.stats.Modifier.increased(tw.exilecore.stats.StatKeys.SKILL_DURATION, 0.5, "測試"));
        final SkillBuild contagion = SkillBuild.of(config, config.skill("contagion"), 1, 1, false, java.util.List.of(), java.util.Set.of(), longer);
        assertEquals(7.5, tw.exilecore.ailment.AilmentService.debuffSeconds(contagion.profile().effective(longer), contagion.profile()), 1e-9,
                "疫潮 5 秒 × 增加 50% 持續時間");
    }

    /** 2026-10-26 照 PoE2：凝霜箭沒有範圍，打中時往前噴出扇形冰片（8→11 片、100% 傷害效益、全部轉冰）。 */
    @Test
    void iceShotSpraysShardsInsteadOfExploding() {
        final SkillDefinition ice = config.skill("ice_shot");
        assertFalse(ice.tags().contains(Tags.AREA));
        assertEquals(0, ice.params().explode().last(), 1e-9);
        final SkillParams.Scatter shards = ice.params().scatter();
        assertNotNull(shards);
        assertTrue(shards.onHit());
        assertEquals(8, shards.count(1));
        assertEquals(9, shards.count(10));
        assertEquals(11, shards.count(20));
        assertEquals(1.0, shards.effectiveness(), 1e-9);
        assertEquals(1.0, shards.convert().get(tw.exilecore.stats.DamageType.COLD), 1e-9);
    }

    /** 2026-10-26 照 PoE2：架盾是攻擊，盾擊用盾牌護甲換算傷害（每 5 點 3～4 點物理、5% 暴擊），主手武器不算。 */
    @Test
    void raiseShieldIsAnAttackThatBashesWithTheShield() {
        final SkillDefinition guard = config.skill("raise_guard");
        assertTrue(guard.isAttack());
        assertTrue(guard.dealsDamage());
        assertTrue(guard.tags().containsAll(java.util.Set.of(Tags.ATTACK, Tags.MELEE, Tags.AREA, Tags.PHYSICAL, "channelling")));
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        sheet.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.WEAPON_MIN, tw.exilecore.stats.ModType.FLAT, 50,
                java.util.Set.of(Tags.PHYSICAL), "劍"));
        sheet.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.WEAPON_MAX, tw.exilecore.stats.ModType.FLAT, 90,
                java.util.Set.of(Tags.PHYSICAL), "劍"));
        sheet.add(tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.StatKeys.WEAPON_CRIT, 8, "劍"));
        final SkillBuild build = SkillBuild.of(config, guard, 1, 1, false, java.util.List.of(), java.util.Set.of(), sheet);
        final tw.exilecore.stats.StatSheet bash = sheet.overlay(Channelling.shieldWeapon(build.profile().effective(sheet), build.profile(), 100, false));
        final java.util.Set<String> physical = build.profile().contextFor(tw.exilecore.stats.DamageType.PHYSICAL);
        assertEquals(60, bash.flat(tw.exilecore.stats.StatKeys.WEAPON_MIN, physical), 1e-9, "100 護甲：每 5 點 3");
        assertEquals(80, bash.flat(tw.exilecore.stats.StatKeys.WEAPON_MAX, physical), 1e-9, "100 護甲：每 5 點 4");
        assertEquals(5, bash.flat(tw.exilecore.stats.StatKeys.WEAPON_CRIT, build.profile().tags()), 1e-9);
        assertEquals(0.8, build.profile().effectiveness(), 1e-9);
    }

    /** 2026-10-26 照 PoE2：魔湧（Mana Tempest）是召出風暴的法術，不再是光環。 */
    @Test
    void manaTempestIsAStormNotAnAura() {
        final SkillDefinition tempest = config.skill("mana_storm");
        assertTrue(ManaTempest.is(tempest));
        assertEquals(Mechanic.GUARD, tempest.mechanic());
        assertTrue(tempest.tags().containsAll(java.util.Set.of(Tags.SPELL, "storm", "cooldown")), tempest.tags().toString());
        assertFalse(tempest.tags().contains(Tags.AREA), "風暴不吃範圍加成");
        assertEquals(1, tempest.cooldown(), 1e-9);
        assertEquals(0.6, tempest.castTime(), 1e-9);
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        final SkillBuild build = SkillBuild.of(config, tempest, 20, 100, false, java.util.List.of(), java.util.Set.of(), sheet);
        double gain = 0;
        double split = 0;
        for (final tw.exilecore.stats.Modifier modifier : ManaTempest.effects(build)) {
            assertTrue(modifier.tags().contains(Tags.SPELL), modifier.toString());
            if (modifier.stat().equals(tw.exilecore.stats.StatKeys.damageExtra(tw.exilecore.stats.DamageType.LIGHTNING))) {
                gain += modifier.value();
            } else if (modifier.stat().equals(ManaTempest.SPLIT)) {
                split += modifier.value();
            }
        }
        assertEquals(78, gain, 1e-9, "20 級：獲得 78% 額外閃電");
        assertEquals(3, split, 1e-9, "20 級：分裂 3 個目標");
        assertEquals(2, ManaTempest.costPerSecond(200, 0), 1e-9, "每秒最大魔力 1%");
        assertEquals(2 + 0.3 * 50, ManaTempest.costPerSecond(200, 50 * ManaTempest.SPENT_SHARE), 1e-9, "花掉的 30% 加上去");
        final org.bukkit.Location center = new org.bukkit.Location(null, 0, 64, 0);
        assertTrue(ManaTempest.inside(center, new org.bukkit.Location(null, tw.exilecore.core.Units.metres(2.5), 64, 0))); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertFalse(ManaTempest.inside(center, new org.bukkit.Location(null, tw.exilecore.core.Units.metres(2.7), 64, 0)));
    }

    @Test
    void poisonCloudsAndBurstsDoNotHit() {
        assertTrue(has("caustic_arrow", SkillMechanicKeys.FLAG_GROUND_POISON_ONLY));
        assertTrue(has("gas_grenade", SkillMechanicKeys.FLAG_GROUND_POISON_ONLY));
        assertTrue(has("venom_burst_arrow", SkillMechanicKeys.FLAG_BURST_POISON_ONLY));
        assertTrue(has("oil_grenade", SkillMechanicKeys.FLAG_GROUND_NEEDS_IGNITE));
        assertFalse(has("toxic_growth", tw.exilecore.stats.StatKeys.POISON_CHANCE), "毒苞落地不一定中毒");
    }

    @Test
    void toxicPustulesStayAndDetonate() {
        final SkillConfig.LodgeSpec lodge = config.lodge("toxic_growth");
        assertNotNull(lodge);
        assertTrue(lodge.expire());
        assertEquals(15, lodge.duration(), 1e-9);
        assertEquals(5, lodge.max());
        assertNotNull(config.skill("proc_toxic_pustule"));
    }

    @Test
    void detonatorsAndHeldGuard() {
        assertEquals("hexblast", config.signature("hex_burst"));
        assertEquals("sunder", config.signature("prism_rend"));
        assertFalse(has("prism_rend", tw.exilecore.stats.StatKeys.FLAG_RANDOM_ELEMENT), "不再隨機元素");
        assertEquals(ChargeSpec.Mode.HOLD, config.charge("raise_guard").mode());
        assertEquals(0, config.skill("raise_guard").cooldown(), 1e-9);
        assertTrue(has("coiling_bolts", SkillMechanicKeys.FLAG_SPLIT_TYPES));
        assertEquals(2, config.skill("coiling_bolts").params().projectiles().intAt(1));
    }

    @Test
    void attackHeraldsTakeAttackSupports() {
        assertTrue(config.skill("herald_of_thunder").tags().contains(Tags.ATTACK));
        assertTrue(config.skill("herald_of_ice").tags().contains(Tags.ATTACK));
    }
}
