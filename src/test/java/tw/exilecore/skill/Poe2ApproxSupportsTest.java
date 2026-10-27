package tw.exilecore.skill;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「29 顆用近似的做法，這個能改成原作嗎？」：tools/poe2_supports/missing_0.5.tsv 標 approx 的輔助改成照 PoE2 0.5 原作
 * （最後一擊、圖騰分裂、跟著殘骸走的殘骸輔助、必殺決心的斬擊、裂地從中間分岔…），剩下做不到的仍標 approx 並寫理由。
 */
class Poe2ApproxSupportsTest {

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

    @Test
    void noneStayApproximateAndRemovedOnesAreGone() throws IOException {
        final List<String> approx = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        for (final String line : Files.readAllLines(Path.of("tools/poe2_supports/missing_0.5.tsv"), StandardCharsets.UTF_8)) {
            final String[] cells = line.split("\t", -1);
            if (!line.startsWith("#") && cells.length > 8 && cells[7].equals("removed")) {
                removed.add(cells[4]);
                assertNull(config.support(cells[4]), cells[4] + " 已經刪掉");
            }
            if (!line.startsWith("#") && cells.length > 8 && cells[7].equals("approx")) {
                approx.add(cells[4]);
                assertFalse(cells[8].isBlank(), cells[4] + " 要寫為什麼做不到");
            }
        }
        // 2026-10-27 照站長：矛陣改成照 PoE2 Spearfield 插在地上的矛，遲爆、再布 II 改成 exact；箭幕、跛行、活雷 II、衝勢刪掉（tsv 標 removed）
        //   這次之前剩 delayed_reaction、fusillade、hobble、living_lightning_ii、momentum、rearm_ii 六顆近似
        assertEquals(List.of(), approx);
        assertEquals(List.of("fusillade", "hobble", "living_lightning_ii", "momentum"), removed);
    }

    @Test
    void finalStrikeComesAtTheEndOfAQuickCombo() {
        // 寒冰震波：第 3 下；風暴連擊：第 4 下（RePoE 的 stat set）
        assertEquals(3, SupportExtraKeys.FINAL_STRIKE_SKILLS.get("ice_strike"));
        assertEquals(4, SupportExtraKeys.FINAL_STRIKE_SKILLS.get("tempest_flurry"));
        int count = 0;
        String last = null;
        long tick = 0;
        final List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int step = SupportExtras.comboStep(last, tick, count, "ice_strike", i * 20L, 3);
            steps.add(step);
            count = step >= 3 ? 0 : step;
            last = "ice_strike";
            tick = i * 20L;
        }
        assertEquals(List.of(1, 2, 3, 1), steps, "第 3 下是最後一擊，之後從頭");
        assertEquals(1, SupportExtras.comboStep("ice_strike", 0, 2, "ice_strike", SupportExtraKeys.FINAL_STRIKE_WINDOW + 1, 3), "隔太久重來");
        assertEquals(1, SupportExtras.comboStep("fireball", 0, 2, "ice_strike", 10, 3), "中間用了別的技能重來");
        assertEquals(0, SupportExtras.comboStep("fireball", 0, 0, "fireball", 10, 0), "沒有連段的技能");
        // 只能掛在有最後一擊的打擊上
        assertNull(support("crescendo_iii").refusal(skill("tempest_flurry")));
        assertNull(support("rending_apex").refusal(skill("ice_strike")));
        assertEquals("要有最後一擊的打擊（寒冰震波、原獸連擊、風暴連擊、撕咬）", support("rending_apex").refusal(skill("rapid_thrust")));
    }

    @Test
    void alignmentUsesFixedDirections() {
        assertTrue(SupportExtras.alignmentMatches(new Vector(0, 0, -1), 0), "面向 -z 是北");
        assertTrue(SupportExtras.alignmentMatches(new Vector(1, 0, 0), 1), "面向 +x 是東");
        assertFalse(SupportExtras.alignmentMatches(new Vector(1, 0, 0), 0));
        assertEquals(20, SupportExtraKeys.ALIGNMENT_DELAY_TICKS, "對準之後 1 秒才指示新的方向");
    }

    @Test
    void deadlyResolveScalesWithArmourPerLevel() {
        assertEquals(2.0, SupportExtraKeys.deadlyResolveScale(500, 1), 1e-9, "1 級每 5 點護甲 1%：500 點更多 100%");
        assertEquals(3.0, SupportExtraKeys.deadlyResolveScale(100000, 20), 1e-9, "最多更多 200%");
        assertEquals(1.5, SupportExtraKeys.deadlyResolveScale(5400, 20), 1e-9, "20 級每 108 點 1%");
        final SkillDefinition slash = skill("proc_deadly_resolve");
        assertEquals(Mechanic.CONE, slash.mechanic(), "朝敵人斬出 120 度的一刀");
    }

    @Test
    void splinteredTotemsAreSmaller() {
        assertEquals(0.85, SupportExtraKeys.splinterScale(15, 1), 1e-9);
        assertEquals(0.70, SupportExtraKeys.splinterScale(15, 2), 1e-9);
        final SkillBuild build = SkillBuild.of(config, skill("fireball"), 10, 100, false, List.of(), Set.of(), new StatSheet());
        final SkillBuild twice = SkillDeployables.splinterBuild(build, 2, new double[] {15, 15, 15, 7});
        final StatSheet sheet = new StatSheet();
        final double before = build.profile().effective(sheet).more(StatKeys.DAMAGE, build.profile().tags());
        final double after = twice.profile().effective(sheet).more(StatKeys.DAMAGE, twice.profile().tags());
        assertEquals(0.70, after / before, 1e-9, "分裂兩次：傷害更少 30%");
        assertEquals(0.86, twice.profile().effective(sheet).more(StatKeys.CAST_SPEED, twice.profile().tags())
                / build.profile().effective(sheet).more(StatKeys.CAST_SPEED, build.profile().tags()), 1e-9, "技能速度每次更少 7%");
        assertEquals(Math.sqrt(0.70), twice.areaScale() / build.areaScale(), 1e-9, "範圍更少 30%（半徑開根號）");
        final SupportDefinition splinter = support("splinter_totem_ii");
        assertTrue(splinter.flags(SupportExtraKeys.TOTEM_ONLY_ONE), "同時只能有一根");
        assertTrue(splinter.flags(SupportExtraKeys.SPLINTER));
        for (final Modifier modifier : splinter.modifiers()) {
            assertFalse(modifier.stat().equals(StatKeys.SKILL_TOTEMS), "不再是平衡等效的多 2 根");
        }
    }

    @Test
    void remnantSupportsTravelWithTheirRemnants() {
        final StatSheet sheet = new StatSheet().overlay(support("harmonic_remnants_ii").modifiers()).overlay(support("remnant_potency_iii").modifiers());
        final ChargeEffects.RemnantMods mods = ChargeEffects.RemnantMods.of(sheet, Set.of());
        assertEquals(35, mods.range(), 1e-9);
        assertEquals(20, mods.extra(), 1e-9);
        assertEquals(40, mods.effect(), 1e-9);
        assertEquals(3, mods.delaySeconds(), 1e-9, "撿到 3 秒後才生效");
        assertEquals(ChargeEffects.REMNANT_PICKUP * 1.35, ChargeEffects.remnantPickup(0.35), 1e-9);
        // 會留殘骸的技能才掛得上：響指、素痕湧、燼種另外算；魔泉湧不會留殘骸
        assertNull(support("magnetic_remnants").refusal(skill("snap")));
        assertNull(support("magnetic_remnants").refusal(skill("storm_orb")));
        assertEquals("要會留下殘骸的技能", support("magnetic_remnants").refusal(skill("mana_remnants")));
    }

    @Test
    void commandmentOnlyMutesNonCommandAttacks() {
        assertTrue(MinionService.commandProfile("skeletal_archers.gas_arrow"));
        assertTrue(MinionService.commandProfile("skeletal_stormcaller.death_storm"));
        assertFalse(MinionService.commandProfile("skeletal_brute.bonebreaker"));
        assertFalse(MinionService.commandProfile("skeletal_brute"));
        assertNull(support("commandment").refusal(skill(MinionAbilities.BRUTE)));
        assertEquals("要有指令的召喚物", support("commandment").refusal(skill("raise_zombie")));
        for (final Modifier modifier : support("commandment").modifiers()) {
            assertFalse(modifier.stat().equals(StatKeys.DAMAGE), "更少傷害只給指令以外的招式（MinionService），不是整個召喚物");
        }
    }

    @Test
    void smallHelpers() {
        assertEquals(3, SupportExtras.stompSteps(4.6), "每 1.5 公尺一步");
        assertEquals(0, SupportExtras.stompSteps(1.4));
        assertEquals(20, SupportExtras.refractionExposure(2, 10000), 1e-9);
        assertEquals(80, SupportExtras.refractionExposure(2, 100000), 1e-9, "最多 80%");
        assertEquals(1 / 3.0, SkillExecutor.branchPoint(1, 2), 1e-9, "兩道分岔在主裂縫的三分之一、三分之二處");
        assertEquals(2 / 3.0, SkillExecutor.branchPoint(2, 2), 1e-9);
        // 不破：輕暈門檻更多 200%、重暈門檻更多 30%（放的那段時間）
        final List<String> stats = new ArrayList<>();
        for (final Modifier modifier : support("unbreakable").modifiers()) {
            stats.add(modifier.stat());
        }
        assertEquals(List.of(SupportExtraKeys.USING_PREFIX + SupportExtraKeys.LIGHT_STUN_THRESHOLD,
                SupportExtraKeys.USING_PREFIX + SupportExtraKeys.HEAVY_STUN_THRESHOLD), stats);
        // 良機：完美時機區間更短 35%
        assertTrue(support("window_of_opportunity_ii").flags(SupportExtraKeys.PERFECT_WINDOW_SHORTER));
        // 延樂：要連擊的驚雷之鐘也是有使用條件的技能
        assertNull(support("delayed_gratification").refusal(skill("tempest_bell")));
    }
}
