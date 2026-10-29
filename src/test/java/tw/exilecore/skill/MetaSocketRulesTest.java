package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2：主要寶石的插槽照原作輔助那一面的 allowed_types／excluded_types（{@link MetaSocketRules}；
 * 對照工具 tools/audit_poe2_meta_sockets.py）。
 */
class MetaSocketRulesTest {

    static SkillConfig config;

    /** 原作 Spell + Triggerable 的主要寶石（星咒圖騰另外看 Totemable）。 */
    static final List<String> TRIGGER_METAS = List.of("meta_crit", "meta_ailment", "meta_dodge", "meta_minion_death",
            "meta_elemental_invocation", "meta_reaper_invocation", "meta_barrier_invocation", "spellslinger");

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void lightningWarpFitsCastOnCritical() {
        final SkillDefinition warp = config.skill("storm_blink");
        assertNull(MetaGems.problem(config.meta("meta_crit"), warp), "雷遁原作 Spell + Triggerable：可以插暴擊引星");
        for (final String meta : TRIGGER_METAS) {
            assertNull(MetaGems.problem(config.meta(meta), warp), "雷遁 → " + meta);
        }
        assertNull(MetaGems.engineProblem(warp), "被觸發的路徑放得出雷遁");
        // 2026-10-28 照 PoE2 Totemable：圖騰自己瞬移進撲殺得到的敵人（這次之前圖騰放不出位移，插不進星咒圖騰）
        assertNull(MetaGems.problem(config.meta("totem_casting"), warp), "雷遁原作 Totemable：可以插星咒圖騰");
        assertNotNull(MetaGems.problem(config.meta("fire_spell_on_hit"), warp), "擊中時火焰法術只收火焰法術");
    }

    @Test
    void poe2ExcludedStaysRefused() {
        final MetaSpec crit = config.meta("meta_crit");
        // 引導（原作沒有 Triggerable）：這次之前插得進去
        for (final String id : List.of("bone_storm", "flame_stream", "volcano", "inferno_blast")) {
            assertNotNull(MetaGems.problem(crit, config.skill(id)), id + "：引導不能被觸發");
        }
        // 印記：原作不是 Spell（我們的印記本來就沒有 spell 標籤）
        for (final String id : List.of("voltaic_mark", "freezing_mark", "bloodhounds_mark")) {
            assertNotNull(MetaGems.problem(crit, config.skill(id)), id + "：印記原作不是法術");
        }
        assertNotNull(MetaGems.problem(crit, config.skill("dark_idol")), "冥偶：原作 SummonsTotem，不能被觸發");
        // 殞從引星：原作 excluded CreatesMinion（掘骸）
        assertNotNull(MetaGems.problem(config.meta("meta_minion_death"), config.skill("bone_spikes")), "掘骸會產生召喚物");
        assertNull(MetaGems.problem(crit, config.skill("bone_spikes")), "其他引星照樣收掘骸");
        // 閃現：原作不是 Triggerable；鏡影穿霧：原作沒有，衝刺照舊不行
        assertNotNull(MetaGems.problem(crit, config.skill("blink")));
        assertNotNull(MetaGems.problem(crit, config.skill("mirror_step")));
        // 常駐、召喚、戰吼、主要寶石
        assertNotNull(MetaGems.problem(crit, config.skill("frost_aegis")));
        assertNotNull(MetaGems.problem(crit, config.skill("raise_zombie")));
        assertNotNull(MetaGems.problem(crit, config.skill("meta_ailment")));
    }

    @Test
    void ordinarySpellsStillFit() {
        for (final String meta : TRIGGER_METAS) {
            for (final String id : List.of("fireball", "arc", "comet", "frost_nova", "detonate_dead", "vulnerability_curse", "entangle",
                    "frost_mine", "profane_ritual")) {
                assertNull(MetaGems.problem(config.meta(meta), config.skill(id)), id + " → " + meta);
            }
        }
        // 星咒圖騰（Totemable）：引導可以（原作 Totemable、沒有冷卻）
        assertNull(MetaGems.problem(config.meta("totem_casting"), config.skill("flame_stream")));
    }

    @Test
    void listsAreConsistent() {
        for (final String id : MetaSocketRules.TRIGGERABLE) {
            assertNotNull(config.skill(id), id);
            assertFalse(MetaSocketRules.shaped(config.skill(id)), id + "：照形狀本來就算，不用寫在 TRIGGERABLE");
            assertTrue(MetaSocketRules.triggerable(config.skill(id)), id);
        }
        for (final String id : MetaSocketRules.NOT_TRIGGERABLE) {
            assertNotNull(config.skill(id), id);
            assertTrue(MetaSocketRules.shaped(config.skill(id)), id + "：照形狀本來就不算，不用寫在 NOT_TRIGGERABLE");
            assertFalse(MetaSocketRules.triggerable(config.skill(id)), id);
        }
        for (final String id : MetaSocketRules.CREATES_MINION) {
            assertNotNull(config.skill(id), id);
        }
    }

    @Test
    void triggeredWarpTargetsTheTrigger() {
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.chooseTriggered(true, true, false, false), "暴擊打到的那隻撲殺得到：瞬移進去");
        assertEquals(LightningWarp.Pick.MARK, LightningWarp.chooseTriggered(true, false, true, true), "打到的那隻還撲殺不到：掛標記（不換別隻）");
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.chooseTriggered(false, false, true, true), "沒有目標：找撲殺得到的");
        assertEquals(LightningWarp.Pick.MARK, LightningWarp.chooseTriggered(false, false, false, true));
        assertEquals(LightningWarp.Pick.NONE, LightningWarp.chooseTriggered(false, false, false, false));
        // 2026-10-29 照 PoE2：被觸發的也會傳到滾雷珠（撲殺得到的敵人優先，滾雷珠比掛標記優先）
        assertEquals(LightningWarp.Pick.BALL, LightningWarp.chooseTriggered(true, false, true, false, true), "打到的那隻還撲殺不到、有滾雷珠：傳過去");
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.chooseTriggered(true, true, true, false, true), "撲殺得到比滾雷珠優先");
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.chooseTriggered(false, false, true, true, true));
        assertEquals(LightningWarp.Pick.BALL, LightningWarp.chooseTriggered(false, false, true, false, false), "附近沒有敵人也傳到滾雷珠");
    }
}
