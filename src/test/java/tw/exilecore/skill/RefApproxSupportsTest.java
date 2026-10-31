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
class RefApproxSupportsTest {

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
                approx.add(SkillIds.current(cells[4])); // 2026-10-31：tsv 寫的是舊代號
                assertFalse(cells[8].isBlank(), cells[4] + " 要寫為什麼做不到");
            }
        }
        // 2026-10-27 照站長：矛陣改成照 PoE2 Spearfield 插在地上的矛，遲爆、再布 II 改成 exact；箭幕、跛行、活雷 II、衝勢刪掉（tsv 標 removed）
        //   這次之前剩 delayed_reaction、fusillade、hobble、living_lightning_ii、momentum、rearm_ii 六顆近似
        // 2026-10-27 照站長「放技能減速全部移除」：輕身（原作只有減少移動懲罰）沒有原作效果可以做，平衡等效成攻擊／施法速度
        // 2026-10-29 照站長加回衝勢（Momentum），照原作（exact），不再是 removed
        assertEquals(List.of("light_body"), approx);
        assertEquals(List.of("fusillade", "hobble", "living_lightning_ii"), removed);
    }

    @Test
    void finalStrikeComesAtTheEndOfAQuickCombo() {
        // 寒冰震波：第 3 下；風暴連擊：第 4 下（RePoE 的 stat set）
        assertEquals(3, SupportExtraKeys.FINAL_STRIKE_SKILLS.get("frost_shockwave"));
        assertEquals(4, SupportExtraKeys.FINAL_STRIKE_SKILLS.get("gale_combo"));
        int count = 0;
        String last = null;
        long tick = 0;
        final List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int step = SupportExtras.comboStep(last, tick, count, "frost_shockwave", i * 20L, 3);
            steps.add(step);
            count = step >= 3 ? 0 : step;
            last = "frost_shockwave";
            tick = i * 20L;
        }
        assertEquals(List.of(1, 2, 3, 1), steps, "第 3 下是最後一擊，之後從頭");
        assertEquals(1, SupportExtras.comboStep("frost_shockwave", 0, 2, "frost_shockwave", SupportExtraKeys.FINAL_STRIKE_WINDOW + 1, 3), "隔太久重來");
        assertEquals(1, SupportExtras.comboStep("blazing_star_shell", 0, 2, "frost_shockwave", 10, 3), "中間用了別的技能重來");
        assertEquals(0, SupportExtras.comboStep("blazing_star_shell", 0, 0, "blazing_star_shell", 10, 0), "沒有連段的技能");
        // 只能掛在有最後一擊的打擊上
        assertNull(support("rising_swell_iii").refusal(skill("gale_combo")));
        assertNull(support("split_summit").refusal(skill("frost_shockwave")));
        assertEquals("要有最後一擊的打擊（寒冰震波、原獸連擊、風暴連擊、撕咬）", support("split_summit").refusal(skill("triple_stab")));
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
        final SkillBuild build = SkillBuild.of(config, skill("blazing_star_shell"), 10, 100, false, List.of(), Set.of(), new StatSheet());
        final SkillBuild twice = SkillDeployables.splinterBuild(build, 2, new double[] {15, 15, 15, 7});
        final StatSheet sheet = new StatSheet();
        final double before = build.profile().effective(sheet).more(StatKeys.DAMAGE, build.profile().tags());
        final double after = twice.profile().effective(sheet).more(StatKeys.DAMAGE, twice.profile().tags());
        assertEquals(0.70, after / before, 1e-9, "分裂兩次：傷害更少 30%");
        assertEquals(0.86, twice.profile().effective(sheet).more(StatKeys.CAST_SPEED, twice.profile().tags())
                / build.profile().effective(sheet).more(StatKeys.CAST_SPEED, build.profile().tags()), 1e-9, "技能速度每次更少 7%");
        assertEquals(Math.sqrt(0.70), twice.areaScale() / build.areaScale(), 1e-9, "範圍更少 30%（半徑開根號）");
        final SupportDefinition splinter = support("split_totem_ii");
        assertTrue(splinter.flags(SupportExtraKeys.TOTEM_ONLY_ONE), "同時只能有一根");
        assertTrue(splinter.flags(SupportExtraKeys.SPLINTER));
        for (final Modifier modifier : splinter.modifiers()) {
            assertFalse(modifier.stat().equals(StatKeys.SKILL_TOTEMS), "不再是平衡等效的多 2 根");
        }
    }

    @Test
    void remnantSupportsTravelWithTheirRemnants() {
        final StatSheet sheet = new StatSheet().overlay(support("harmony_bones_ii").modifiers()).overlay(support("bone_effect_iii").modifiers());
        final ChargeEffects.RemnantMods mods = ChargeEffects.RemnantMods.of(sheet, Set.of());
        assertEquals(35, mods.range(), 1e-9);
        assertEquals(20, mods.extra(), 1e-9);
        assertEquals(40, mods.effect(), 1e-9);
        assertEquals(3, mods.delaySeconds(), 1e-9, "撿到 3 秒後才生效");
        assertEquals(ChargeEffects.REMNANT_PICKUP * 1.35, ChargeEffects.remnantPickup(0.35), 1e-9);
        // 會留殘骸的技能才掛得上：響指、素痕湧、燼種另外算；魔泉湧不會留殘骸
        assertNull(support("magnet_bones").refusal(skill("anomaly_devour_blast")));
        assertNull(support("magnet_bones").refusal(skill("thunder_cloud")));
        assertEquals("要會留下殘骸的技能", support("magnet_bones").refusal(skill("magic_spring_well")));
    }

    @Test
    void commandmentOnlyMutesNonCommandAttacks() {
        assertTrue(MinionService.commandProfile("summon_bone_bowmen.gas_arrow"));
        assertTrue(MinionService.commandProfile("summon_bone_thundermage.death_storm"));
        assertFalse(MinionService.commandProfile("summon_bone_heavy.bonebreaker"));
        assertFalse(MinionService.commandProfile("summon_bone_heavy"));
        assertNull(support("army_order").refusal(skill(MinionAbilities.BRUTE)));
        assertEquals("要有指令的召喚物", support("army_order").refusal(skill("corpse_summon")));
        for (final Modifier modifier : support("army_order").modifiers()) {
            assertFalse(modifier.stat().equals(StatKeys.DAMAGE), "更少傷害只給指令以外的招式（MinionService），不是整個召喚物");
        }
    }

    @Test
    void smallHelpers() {
        assertEquals(3, SupportExtras.stompSteps(4.6), "每 1.5 格一步");
        assertEquals(0, SupportExtras.stompSteps(1.4));
        assertEquals(20, SupportExtras.refractionExposure(2, 10000), 1e-9);
        assertEquals(80, SupportExtras.refractionExposure(2, 100000), 1e-9, "最多 80%");
        assertEquals(1 / 3.0, SkillExecutor.branchPoint(1, 2), 1e-9, "兩道分岔在主裂縫的三分之一、三分之二處");
        assertEquals(2 / 3.0, SkillExecutor.branchPoint(2, 2), 1e-9);
        // 不破：輕暈門檻更多 200%、重暈門檻更多 30%（放的那段時間）
        final List<String> stats = new ArrayList<>();
        for (final Modifier modifier : support("never_broken").modifiers()) {
            stats.add(modifier.stat());
        }
        assertEquals(List.of(SupportExtraKeys.USING_PREFIX + SupportExtraKeys.LIGHT_STUN_THRESHOLD,
                SupportExtraKeys.USING_PREFIX + SupportExtraKeys.HEAVY_STUN_THRESHOLD), stats);
        // 良機：完美時機區間更短 35%
        assertTrue(support("good_chance_ii").flags(SupportExtraKeys.PERFECT_WINDOW_SHORTER));
        // 延樂：要連擊的驚雷之鐘也是有使用條件的技能
        assertNull(support("deferred_joy").refusal(skill("startling_thunder_bell")));
    }
}
