package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 站長「技能與輔助也比照 poe2db」：寶石階級、未切割寶石升級、鐵壁守／瘟囊爆、掘骸、拿掉的自創寶石、節流、引導的每秒魔力。 */
class Poe2GemParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void gemTiersFollowPoe2db() {
        // poe2db Uncut_Skill_Gem（技能 1–13 階）、Spirit_Gems（精魂 4／8／14 階）
        assertEquals(11, config.skill("comet").tier());
        assertEquals(11, config.skill("tornado_shot").tier());
        assertEquals(11, config.skill("whirling_rush").tier(), "Whirling Assault");
        assertEquals(9, config.skill("flame_stream").tier(), "Incinerate");
        assertEquals(13, config.skill("skeletal_cleric").tier());
        assertEquals(1, config.skill("volcano").tier());
        assertEquals(7, config.skill("explosive_bolt").tier(), "Explosive Shot");
        assertEquals(5, config.skill("true_strike").tier(), "Perfect Strike");
        assertEquals(14, config.skill("archmage").tier());
        assertEquals(8, config.skill("mirage_archer").tier());
        assertEquals(4, config.skill("herald_of_ash").tier());
        assertEquals(4, config.skill("attrition").tier());
        assertEquals(4, config.skill("iron_ward").tier());
        assertEquals(4, config.skill("plague_bearer").tier());
    }

    @Test
    void uncutGemSetsTheGemLevel() {
        // PoE2：未切割技能寶石直接把技能升到它的等級（這次之前一顆升一級）
        final SkillLoadout.Entry gem = new SkillLoadout.Entry("fireball", 5);
        assertEquals(12, GemService.levelUpTarget(gem, 12));
        assertEquals(SkillLoadout.GEM_CAP, GemService.levelUpTarget(gem, 99));
    }

    @Test
    void selfMadeGemsAreRetiredOrMerged() {
        for (final String id : List.of("battle_rage", "minion_recall", "command_skeleton_slam", "command_volley", "command_elements",
                "command_beast_pounce")) {
            assertNull(config.skill(id), id);
            assertTrue(config.retiredSkill(id), id + "：身上的換成同等級的未切割技能寶石（插的輔助、孔、品質退還）");
            final GemData old = GemData.decode(GemData.skill(new SkillLoadout.Entry(id, 7, 4, List.of("pierce", "", "chain"))).encode()).orElseThrow();
            assertEquals(GemData.uncutSkill(7), old.removedSkillReplacement(config), id);
        }
        // 掘骸（召喚）併進 bone_spikes（PoE2 Unearth）：等級、孔、輔助保留
        assertNull(config.skill("bone_raise"));
        final SkillLoadout.Entry merged = config.merge(new SkillLoadout.Entry("bone_raise", 9, 3, List.of("minion_damage")));
        assertNotNull(merged);
        assertEquals("bone_spikes", merged.skillId());
        assertEquals(9, merged.level());
        assertTrue(Unearth.is(SkillBuild.of(config, config.skill("bone_spikes"), 9, 100, false, List.of(), java.util.Set.of(), new tw.exilecore.stats.StatSheet())));
        final SkillDefinition construct = config.skill(Unearth.CONSTRUCT);
        assertNotNull(construct.summon());
        assertTrue(construct.hidden());
        // 召喚物寶石自己帶指令（按鍵下指令），不靠另外的指令寶石
        for (final String id : List.of("skeletal_archers", "skeletal_pyromancer", "skeletal_stormcaller", "skeletal_reaver", "skeletal_frostcaller")) {
            assertTrue(config.skill(id).description().contains("指令"), id);
        }
    }

    @Test
    void ironWardAndPlagueBearerAreSpiritGems() {
        for (final String id : List.of("iron_ward", "plague_bearer")) {
            final SkillDefinition definition = config.skill(id);
            assertEquals(Mechanic.AURA, definition.mechanic(), id);
            assertEquals(30, definition.aura().reserve(), 1e-9, id + " 保留 30 精魂");
        }
        assertTrue(config.skill(StoredReleases.IRON_WARD_RELEASE).hidden());
        assertTrue(config.skill(StoredReleases.PLAGUE_BEARER_RELEASE).hidden());
        // poe2db：鐵壁守 20 級存 480%、上限 22,886；瘟囊爆上限 20 級 15,257
        assertEquals(480, StoredReleases.at(StoredReleases.IRON_WARD_PERCENT, 20), 1e-9);
        assertEquals(22886, StoredReleases.at(StoredReleases.IRON_WARD_CAP, 20), 1e-9);
        assertEquals(15257, StoredReleases.at(StoredReleases.PLAGUE_CAP, 20), 1e-9);
        assertEquals(82074, StoredReleases.at(StoredReleases.IRON_WARD_CAP, 40), 1e-9);
        // 存到上限為止
        assertEquals(65, StoredReleases.store(60, 100, 65), 1e-9);
        // 存滿：鐵壁守範圍更多 200%（半徑 √3 倍）、瘟囊爆更多 600%（√7 倍）；空的不放大
        assertEquals(Math.sqrt(3), StoredReleases.radiusScale(100, 100, StoredReleases.IRON_WARD_AREA), 1e-9);
        assertEquals(Math.sqrt(7), StoredReleases.radiusScale(500, 100, StoredReleases.PLAGUE_AREA), 1e-9);
        assertEquals(1, StoredReleases.radiusScale(0, 100, StoredReleases.PLAGUE_AREA), 1e-9);
        // 中毒預計傷害：物理＋混沌 1000、機率 50% → 1000 × 40% × 0.5
        assertEquals(200, StoredReleases.expectedPoison(1000, 50), 1e-9);
        assertEquals(400, StoredReleases.expectedPoison(1000, 300), 1e-9, "機率最多 100%");
    }

    @Test
    void efficiencyIsBack() {
        final SupportDefinition efficiency = config.support("efficiency");
        assertNotNull(efficiency, "PoE2 0.5 還有 Efficiency：恢復");
        assertEquals(3, efficiency.tier(), "Efficiency II 第 4 階");
        boolean less = false;
        for (final Modifier modifier : efficiency.modifiers()) {
            less |= modifier.stat().equals(StatKeys.SKILL_COST) && Math.abs(modifier.value() + 0.4) < 1e-9;
        }
        assertTrue(less, "消耗更少 40%");
        assertFalse(efficiency.supports(config.skill("herald_of_ash")), "不能掛保留精魂的");
        assertEquals("保留精魂的技能不行", efficiency.refusal(config.skill("skeletal_archers")));
        assertTrue(efficiency.supports(config.skill("fireball")));
    }

    @Test
    void channelledCostsArePerSecond() {
        // poe2db：擂盾每秒 7 → 47 → 183、熔裂踏每秒 10 → 68 → 263 魔力（引導，按住逐秒扣）
        for (final String id : List.of("resonating_shield", "mountain_fury")) {
            final SkillDefinition definition = config.skill(id);
            assertNotNull(config.charge(id), id + " 是引導");
            assertTrue(config.charge(id).drain(), id + " 魔力按住逐秒扣");
        }
        assertEquals(47, config.skill("resonating_shield").mana().intAt(20));
        assertEquals(68, config.skill("mountain_fury").mana().intAt(20));
        assertTrue(config.skill("flame_stream").damageByLevel(), "焚流（Incinerate）照 poe2db 逐級");
    }

    @Test
    void burgeonScalesWithChannelTime() {
        // poe2db Burgeon II：每引導 1 秒更多 20%，最多 40%
        final SupportDefinition burgeon = config.support("burgeon");
        assertEquals(0, Burgeon.more(20, 40, 0), 1e-9);
        assertEquals(10, Burgeon.more(20, 40, 0.5), 1e-9);
        assertEquals(40, Burgeon.more(20, 40, 5), 1e-9);
        // 只掛得上引導／蓄力的技能（channelling 標籤），包括 ChargeSpec 的引導與按住一直放的
        for (final String id : List.of("inferno_blast", "flame_breath", "bone_storm", "flame_stream", "resonating_shield")) {
            assertNull(burgeon.refusal(config.skill(id)), id);
        }
        assertEquals("要引導或蓄力的技能", burgeon.refusal(config.skill("fireball")));
        // 屬性表上讀得到（掛在技能上的修飾器）
        final SkillBuild build = SkillBuild.of(config, config.skill("inferno_blast"), 10, 100, false, List.of(burgeon), java.util.Set.of(),
                new tw.exilecore.stats.StatSheet());
        assertEquals(40, Burgeon.more(build.profile().effective(new tw.exilecore.stats.StatSheet()), 3), 1e-9);
        assertEquals(20, Burgeon.more(build.profile().effective(new tw.exilecore.stats.StatSheet()), 1), 1e-9);
    }
}
