package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2：觸發技能只有自己資料的冷卻（cooldown: 0 = 沒有）、地面效果的上限每個技能各自算、撒蒺 +投射物多撒、
 * 蒺藜步與撒蒺共用上限、隱藏技能吃原技能輔助的持續時間。
 */
class SharedTriggerRulesTest {

    static SkillConfig config;

    /** PoB2／poe2db 沒有觸發技能資料、刻意照預設冷卻的（skills.yml 有註解）。 */
    static final Set<String> DEFAULT_COOLDOWN = Set.of("proc_dazing_cry", "proc_frozen_vortex", "proc_frost_spread");

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    // ---- 1. 觸發冷卻 ----

    @Test
    void triggerCooldownTicks() {
        assertEquals(0, SupportProcs.triggerCooldownTicks(0, true), "明寫 cooldown: 0：沒有冷卻");
        assertEquals(SupportProcs.COOLDOWN, SupportProcs.triggerCooldownTicks(0, false), "沒寫：照舊預設");
        assertEquals(8, SupportProcs.triggerCooldownTicks(0.4, false));
        assertEquals(3, SupportProcs.triggerCooldownTicks(0.15, false));
        assertEquals(1, SupportProcs.triggerCooldownTicks(0.01, false), "至少 1 tick");
    }

    @Test
    void poeTriggersWithoutCooldownHaveNone() {
        for (final String id : List.of("proc_shock_rift", "proc_crater", "proc_caltrops", "proc_aftershock", "proc_tremor", "proc_side_aftershock",
                "proc_maim", "proc_flame_pillar", "proc_fiery_death", "proc_blazing_critical", "proc_innervate")) {
            assertTrue(config.noTriggerCooldown(id), id + " 在 PoB2 沒有 cooldown");
            assertEquals(0, SupportProcs.triggerCooldownTicks(config.skill(id).cooldown(), true));
        }
        assertFalse(config.noTriggerCooldown("proc_static_orb"));
        assertFalse(config.noTriggerCooldown("proc_frozen_vortex"), "沒有資料的照預設");
    }

    @Test
    void poeCooldownsAndStoredUses() {
        assertEquals(0.15, config.skill("proc_bramble").cooldown(), 1e-9);
        assertEquals(6, config.cooldownUses("proc_bramble", 20));
        assertEquals(6, config.cooldownUses("proc_coursing_current", 20));
        assertEquals(5, config.cooldownUses("proc_fan_flames", 20));
        assertEquals(0.1, config.skill("proc_poison_spore").cooldown(), 1e-9);
        assertEquals(0.15, config.skill("proc_quill_burst").cooldown(), 1e-9);
        assertEquals(0.4, config.skill("proc_static_orb").cooldown(), 1e-9);
        assertEquals(3, config.cooldownUses("proc_static_orb", 20));
    }

    @Test
    void everySupportTriggerStatesItsCooldown() {
        for (final SupportDefinition support : config.supports()) {
            for (final Modifier modifier : support.modifiers()) {
                if (!ProcText.isProc(modifier)) {
                    continue;
                }
                final String id = modifier.tags().iterator().next();
                final SkillDefinition sub = config.skill(id);
                if (sub == null) {
                    continue;
                }
                assertTrue(sub.cooldown() > 0 || config.noTriggerCooldown(id) || DEFAULT_COOLDOWN.contains(id),
                        support.id() + " 觸發的 " + id + " 要照 PoB2 寫 cooldown（沒有冷卻寫 0）");
            }
        }
    }

    @Test
    void procTextShowsCooldown() {
        assertTrue(ProcText.details("proc_shock_rift").contains("沒有觸發冷卻"), ProcText.details("proc_shock_rift").toString());
        assertTrue(ProcText.details("proc_static_orb").stream().anyMatch(l -> l.contains("觸發冷卻 0.4 秒") && l.contains("3 次")),
                ProcText.details("proc_static_orb").toString());
        assertEquals("0.15", ProcText.seconds(0.15));
        assertEquals("1", ProcText.seconds(1.0));
    }

    // ---- 2. 地面效果上限 ----

    @Test
    void zoneCapsArePerSkill() {
        assertEquals("proc_crater", SkillZones.capKey("proc_crater", "whatever"));
        assertEquals("flame_wall", SkillZones.capKey(null, "flame_wall"));
        assertEquals(9, SkillZones.skillCap("proc_crater", 9, 0), "熔坑照自己的 9 道");
        assertEquals(11, SkillZones.skillCap("storm", 9, 2), "昇華的 +2");
        assertEquals(SkillZones.DEFAULT_PER_SKILL, SkillZones.skillCap(null, 0, 5), "沒寫上限的：每個技能各自的預設");
        assertEquals(SkillZones.DEFAULT_PER_SKILL, SkillZones.skillCap("x", 0, 0));
        final int crater = config.skill("proc_crater").params().zone().max();
        assertEquals(9, crater);
        assertTrue(SkillZones.MAX_PER_PLAYER >= crater + 5 + 2 * SkillZones.DEFAULT_PER_SKILL, "效能的保險要夠放好幾個技能各自滿上限");
    }

    // ---- 3. 撒蒺 ----

    @Test
    void caltropsPerTriggerFollowProjectiles() {
        assertEquals(1, OriginalSkills.spearCaltropCount(0), "base_number_of_projectiles 1");
        assertEquals(3, OriginalSkills.spearCaltropCount(2));
        assertEquals(1, OriginalSkills.spearCaltropCount(-3), "至少 1 顆");
        assertEquals(OriginalSkills.SPEAR_CALTROP_MAX, OriginalSkills.spearCaltropCount(1000));
    }

    @Test
    void sharedCaltropLimitRemovesOldestOfEither() {
        final List<Long> trail = new ArrayList<>(List.of(5L, 9L));
        final List<Long> spear = new ArrayList<>(List.of(3L, 7L));
        assertEquals(3L, OriginalSkills.removeOldest(trail, spear, Long::longValue));
        assertEquals(5L, OriginalSkills.removeOldest(trail, spear, Long::longValue));
        assertEquals(7L, OriginalSkills.removeOldest(trail, spear, Long::longValue));
        assertEquals(9L, OriginalSkills.removeOldest(trail, spear, Long::longValue));
        assertNull(OriginalSkills.removeOldest(trail, spear, Long::longValue));
    }

    // ---- 5. 隱藏技能吃原技能輔助的持續時間 ----

    @Test
    void subSkillTakesParentDurationSupports() {
        final SkillDefinition parent = config.skill("kerosene_grenade");
        final List<Modifier> longer = SkillBuild.parentDurationModifiers(List.of(config.support("long_stay")), parent);
        assertFalse(longer.isEmpty());
        final List<Modifier> ground = SkillBuild.parentDurationModifiers(List.of(config.support("enduring_terrain")), parent);
        assertFalse(ground.isEmpty());
        final SkillBuild sub = SkillBuild.of(config, config.skill("proc_frost_nexus"), 1, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals(1.0, sub.durationScale(), 1e-9);
        assertEquals(1.35, sub.withParentDuration(longer, new StatSheet()).durationScale(), 1e-9, "原技能的持續時間更多 35%");
        final SkillBuild grounded = sub.withParentDuration(ground, new StatSheet());
        assertEquals(1.0, grounded.durationScale(), 1e-9);
        assertEquals(1.5, grounded.groundDurationScale(), 1e-9, "久地只拉長地面");
        final List<Modifier> both = new ArrayList<>(longer);
        both.addAll(ground);
        assertEquals(1.5 * 1.35, sub.withParentDuration(both, new StatSheet()).groundDurationScale(), 1e-9, "同一池再乘更多");
        assertEquals(sub, sub.withParentDuration(List.of(), new StatSheet()), "沒有就原樣");
        assertEquals(1.8, SkillBuild.parentDurationScale(0.5, 1.2), 1e-9);
        assertEquals(0.1, SkillBuild.parentDurationScale(-5, 1), 1e-9);
    }
}
