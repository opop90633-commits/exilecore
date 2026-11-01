package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportRemakeKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照站長：機制跟原作不一樣（近似）的 17 顆輔助照 PoE2 重做（SupportRemakes）。 */
class SupportRemakesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertNotNull(skill, id);
        return skill;
    }

    private static boolean has(final SupportDefinition support, final String stat) {
        return support.modifiers().stream().anyMatch(m -> m.stat().equals(stat));
    }

    @Test
    void tremorsRollOnceIntoThreeBands() {
        // PoE2 Tremors：10% 三次、15% 兩次、30% 一次、其他 0
        assertEquals(3, SupportRemakes.tremors(0, 1));
        assertEquals(3, SupportRemakes.tremors(9.99, 1));
        assertEquals(2, SupportRemakes.tremors(10, 1));
        assertEquals(2, SupportRemakes.tremors(24.99, 1));
        assertEquals(1, SupportRemakes.tremors(25, 1));
        assertEquals(1, SupportRemakes.tremors(54.99, 1));
        assertEquals(0, SupportRemakes.tremors(55, 1));
        // 沉響放大機率：三段一起乘
        assertEquals(3, SupportRemakes.tremors(14.9, 1.5));
        assertEquals(1, SupportRemakes.tremors(82, 1.5));
        assertEquals(0, SupportRemakes.tremors(83, 1.5));
    }

    @Test
    void reverberateScalesWithAttackTime() {
        assertEquals(1.0, SupportRemakes.reverberate(15, 4.9), 1e-9, "不到 0.25 秒");
        assertEquals(1.15, SupportRemakes.reverberate(15, 5), 1e-9);
        assertEquals(1.6, SupportRemakes.reverberate(15, 20), 1e-9, "1 秒 = 4 個 0.25 秒");
        assertEquals(1.0, SupportRemakes.reverberate(0, 40), 1e-9, "沒掛");
    }

    @Test
    void ruthlessEveryFifthUse() {
        for (int use = 1; use <= 10; use++) {
            assertEquals(use == 5 || use == 10, SupportRemakes.ruthlessBigHit(use, SupportRemakeKeys.RUTHLESS_EVERY), "第 " + use + " 次");
        }
    }

    @Test
    void overreachOnlyNearby() {
        final StatSheet sheet = new StatSheet().add(new Modifier(SupportRemakeKeys.ACCURACY_NEARBY, ModType.MORE, -0.8, Set.of(), "闊勢"));
        assertEquals(0.2, SupportRemakes.nearbyAccuracy(sheet, Set.of(), 1.0), 1e-9);
        assertEquals(1.0, SupportRemakes.nearbyAccuracy(sheet, Set.of(), SupportRemakes.overreachRange() + 0.5), 1e-9);
        assertEquals(1.0, SupportRemakes.nearbyAccuracy(new StatSheet(), Set.of(), 0), 1e-9, "沒掛");
    }

    @Test
    void dataFollowsRef() {
        assertTrue(has(support("frost_infuse"), "damage.extra.cold"), "Cold Attunement：全部傷害的 25%");
        assertFalse(has(support("frost_infuse"), "damage.gain.cold"));
        assertTrue(has(support("thunder_infuse"), "damage.extra.lightning"));
        assertTrue(has(support("wide_stance"), SupportRemakeKeys.ACCURACY_NEARBY));
        assertFalse(has(support("wide_stance"), "accuracy"));
        assertTrue(support("crumbling_force").flags(SupportRemakeKeys.FULL_BREAK_ON_HEAVY_STUN));
        assertTrue(has(support("low_resound"), SupportRemakeKeys.AFTERSHOCK_PER_QUARTER));
        assertFalse(has(support("low_resound"), "attack.speed"), "原作沒有減速");
        assertTrue(has(support("merciless_heart"), SupportRemakeKeys.RUTHLESS));
        assertFalse(has(support("merciless_heart"), "damage"), "原作沒有傷害加成");
        assertTrue(has(support("split_shot"), SupportRemakeKeys.FORKED_DAMAGE));
        assertFalse(has(support("split_shot"), "damage"), "原本那一發不更少");
        assertTrue(has(support("twin_chant"), tw.exilecore.stats.KeystoneKeys.ECHO_AREA));
        assertTrue(has(support("broad_hex"), tw.exilecore.stats.SupportKeys.CURSE_DELAY));
        assertFalse(has(support("broad_hex"), "cast.speed"));
        assertTrue(has(support("flesh_bulwark"), "damage.taken"));
        assertFalse(has(support("flesh_bulwark"), "life.max"));
        assertEquals(1.0, support("huge_soul").manaMultiplier(), 1e-9, "Hulking Minions：魔耗 100%、精魂保留 130%");
        assertTrue(has(support("huge_soul"), "aura.reservation"));
        assertTrue(has(support("rotting_curse"), "proc.curse_applied"));
        assertTrue(has(support("scatter_spikes"), "proc.projectile_end"));
        assertTrue(has(support("darting_volt"), "proc.shock"));
        // 2026-11-01 照 PoE2 Enervation：感電時直接給增益（SupportProcs.enervation，沒有觸發冷卻）；這次之前 proc.shock 觸發 proc_enervation
        assertTrue(has(support("volt_drain"), tw.exilecore.stats.SupportExtraKeys.MANA_REGEN_ON_SHOCK));
        assertFalse(has(support("volt_drain"), "proc.shock"));
        assertNull(config.skill("proc_crystal_shards"), "晶刺不再噴冰片");
    }

    @Test
    void restrictionsFollowRef() {
        assertNotNull(support("scatter_spikes").refusal(skill("thunderclap_arrow")), "Caltrops 只限長矛的投射物攻擊");
        assertNull(support("scatter_spikes").refusal(skill("spear_throw")));
        assertNotNull(support("huge_soul").refusal(skill("corpse_summon")), "Hulking Minions 只限持續型召喚");
        assertNull(support("huge_soul").refusal(skill("summon_skeletons")));
    }

    @Test
    void textsReadNaturally() {
        assertEquals("對 2 格內的目標命中更少 80%", ModifierText.describe(
                new Modifier(SupportRemakeKeys.ACCURACY_NEARBY, ModType.MORE, -0.8, Set.of(), "")));
        assertEquals("分叉出來的投射物傷害更少 30%", ModifierText.describe(
                new Modifier(SupportRemakeKeys.FORKED_DAMAGE, ModType.MORE, -0.3, Set.of(), "")));
        assertEquals("每第 5 次使用是重擊，那一次暈眩傷害更多 500%", ModifierText.describe(
                new Modifier(SupportRemakeKeys.RUTHLESS, ModType.FLAT, 500, Set.of(), "")));
    }
}
