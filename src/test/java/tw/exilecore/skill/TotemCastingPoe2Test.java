package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
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
 * 2026-10-28 照 PoE2 Spell Totem（Totemable）：雷遁、骸爆、五個詛咒、縛根交給星咒圖騰（{@link TotemRules#spellTotemCastable}）。
 * 這次之前這 8 個原作 Totemable 的法術插不進星咒圖騰（tools/audit_poe2_meta_sockets.py 列成偏差）。
 */
class TotemCastingPoe2Test {

    static SkillConfig config;

    /** 原作 Totemable、這次之前我們的圖騰放不出來的。 */
    static final List<String> NOW_TOTEMABLE = List.of("storm_blink", "detonate_dead", "elemental_weakness_curse", "enfeeble_curse",
            "temporal_curse", "vulnerability_curse", "despair_curse", "entangle");

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void poe2TotemableSpellsFitSpellTotem() {
        final MetaSpec totem = config.meta("totem_casting");
        for (final String id : NOW_TOTEMABLE) {
            final SkillDefinition spell = config.skill(id);
            assertNotNull(spell, id);
            assertTrue(TotemRules.spellTotemCastable(spell), id);
            assertNull(MetaGems.problem(totem, spell), id + "：原作 Spell + Totemable，可以插星咒圖騰");
        }
    }

    @Test
    void nonTotemableStayRefused() {
        final MetaSpec totem = config.meta("totem_casting");
        // 閃現：原作 Spell + Cooldown + Travel（沒有 Totemable／Triggerable）；鏡影穿霧：原作沒有、有冷卻、幻霧之鏡專屬
        for (final String id : List.of("blink", "mirror_step", "hunters_mark", "voltaic_mark", "freezing_mark", "bloodhounds_mark")) {
            assertNotNull(MetaGems.problem(totem, config.skill(id)), id);
        }
        assertFalse(TotemRules.spellTotemCastable(config.skill("blink")), "一般衝刺圖騰放不出來");
        assertFalse(TotemRules.spellTotemCastable(config.skill("summon_mount")), "一般衝刺圖騰放不出來");
        assertFalse(TotemRules.spellTotemCastable(config.skill("hunters_mark")), "沒有範圍的印記不是詛咒");
        // 被觸發的路徑照舊：閃現、鏡影穿霧不能插暴擊引星（原作不是 Triggerable）
        assertNotNull(MetaGems.problem(config.meta("meta_crit"), config.skill("blink")));
        assertNotNull(MetaGems.problem(config.meta("meta_crit"), config.skill("mirror_step")));
    }

    @Test
    void totemBuildsBecomeTotems() {
        final StatSheet sheet = new StatSheet();
        final SupportDefinition carrier = new SupportDefinition("meta.totem_casting", "星咒圖騰", "", "PAPER", 1, Set.of(), Set.of(), 1.0,
                List.of(Modifier.flat(StatKeys.FLAG_SPELL_TOTEM, 1, "星咒圖騰")), List.of());
        for (final String id : NOW_TOTEMABLE) {
            final SkillBuild build = SkillBuild.of(config, config.skill(id), 10, 60, false, List.of(carrier), Set.of(Tags.BOW), sheet);
            assertNotNull(build.deploy(), id + "：交給圖騰放");
            assertEquals(DeploySpec.Kind.TOTEM, build.deploy().kind(), id);
            assertTrue(build.profile().tags().contains(Tags.TOTEM), id + "：算圖騰技能（吃圖騰傷害；骸爆照圖騰的目標找屍體）");
        }
        assertNull(SkillBuild.of(config, config.skill("blink"), 10, 60, false, List.of(carrier), Set.of(Tags.BOW), sheet).deploy(),
                "閃現不會變成圖騰");
    }

    @Test
    void totemWarpOnlyCullable() {
        // 原作論壇：圖騰只對撲殺範圍內的敵人放雷遁、不掛標記、不瞄準滾雷珠
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.chooseTotem(true));
        assertEquals(LightningWarp.Pick.NONE, LightningWarp.chooseTotem(false));
    }

    @Test
    void totemSkipsSpellsItCannotCastNow() {
        // 孔裡三個法術（0 雷遁、1 熾星彈、2 骸爆）：雷遁沒有撲殺得到的、骸爆沒有屍體 → 跳到熾星彈
        final List<Integer> asked = new ArrayList<>();
        assertEquals(1, SkillDeployables.firstUsable(0, 3, index -> {
            asked.add(index);
            return index == 1;
        }));
        assertEquals(List.of(0, 1), asked);
        assertEquals(2, SkillDeployables.firstUsable(2, 3, index -> index == 1), "從第 3 孔輪：2 → 0 → 1");
        assertEquals(0, SkillDeployables.firstUsable(5, 3, index -> true), "輪到的那個可以就放它");
        assertEquals(-1, SkillDeployables.firstUsable(0, 2, index -> false), "都放不出來：等一下再看");
    }
}
