package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5：過剩 II（Overabundance II，原作寫「上限」的技能 +1 上限）、頂點 II（Culmination II，自己的一份連擊）。 */
class OverabundanceCulminationTest {

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

    private static double value(final SupportDefinition support, final String stat, final ModType type) {
        double sum = 0;
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().equals(stat) && modifier.type() == type) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    private static SkillBuild build(final String skill, final StatSheet sheet, final SupportDefinition... supports) {
        return SkillBuild.of(config, config.skill(skill), 10, 60, false, List.of(supports), Set.of(Tags.MACE), sheet);
    }

    // ---- 過剩 II ----

    @Test
    void overabundanceTwoIsThePoe2Gem() {
        final SupportDefinition over = support("overabundance_ii");
        assertEquals("過剩 II", over.name());
        assertEquals(3, over.tier(), "PoE2 第 4 階 → 我們的 3 階");
        assertEquals(Set.of(SkillLimits.TAG), over.requires());
        assertEquals(1, value(over, KeystoneKeys.SKILL_LIMIT, ModType.FLAT), 1e-9, "上限 +1");
        assertEquals(-0.4, value(over, StatKeys.SKILL_DURATION, ModType.MORE), 1e-9, "技能持續時間更少 40%");
        assertTrue(over.cuttable());
        // 照「I／II 只留最高階」：繁置（Overabundance I）併進來
        assertNull(config.support("overabundance"));
        assertEquals("overabundance_ii", config.supportMergedInto("overabundance"));
        assertNotNull(config.supportMergeNotice("overabundance"));
        assertEquals(SupportColours.GREEN, SupportColours.colour("overabundance_ii"));
    }

    @Test
    void everyLimitSkillExistsAndIsTagged() {
        for (final String id : SkillLimits.SKILLS) {
            final SkillDefinition skill = config.skill(id);
            assertNotNull(skill, id);
            assertTrue(skill.tags().contains(SkillLimits.TAG), id);
        }
        // 原作寫「上限」的：圖騰、鐘、地面效果、有上限的召喚物、印記、祭品、插著的矛、龍捲風…都掛得上
        for (final String id : List.of("shockwave_totem", "siege_ballista", "totem_casting", "tempest_bell", "hollow_focus", "firestorm",
                "volcanic_fissure", "profane_ritual", "shield_wall", "raise_zombie", "raging_spirits", "bone_spikes", "ravenous_swarm",
                "hunters_mark", "voltaic_mark", "blood_offering", "explosive_spear", "storm_javelin", "whirlwind_lance", "tornado_shot",
                "toxic_growth", "earthquake", "bramble_arrow", "ember_seed", "galvanic_field", "mirror_of_refraction")) {
            assertNull(support("overabundance_ii").refusal(config.skill(id)), id);
        }
        // 沒有上限的（或原作的上限不是「上限」）掛不上：法術、靠精魂的召喚、固定 10 根的英靈圖騰、我們沒做上限的毒霧
        for (final String id : List.of("fireball", "summon_skeletons", "ancestral_totem", "caustic_arrow", "frost_mine", "boneshatter")) {
            assertNotNull(support("overabundance_ii").refusal(config.skill(id)), id);
        }
        // 星咒圖騰（宿主）：孔裡放的法術本身沒有上限也掛得上（圖騰的上限）
        assertNull(support("overabundance_ii").hostRefusal(config.skill("totem_casting"), config.skill("fireball")));
    }

    @Test
    void limitAddsTotemsAndLimitedMinionsAndShortensDuration() {
        final StatSheet sheet = new StatSheet();
        final SupportDefinition over = support("overabundance_ii");
        assertEquals(1, build("shockwave_totem", sheet).totems());
        final SkillBuild totem = build("shockwave_totem", sheet, over);
        assertEquals(2, totem.totems(), "圖騰上限 +1");
        assertEquals(1, SkillLimits.bonus(sheet, totem));
        assertEquals(Math.round(12 * 20 * 0.6), totem.durationTicks(12), "持續時間更少 40%");
        assertEquals(build("raise_zombie", sheet).minions() + 1, build("raise_zombie", sheet, over).minions(), "喚屍上限 +1");
        // 身上的「+1 上限」（傳奇、天賦樹）不加在靠精魂的召喚物上（原作沒有上限）
        final StatSheet global = new StatSheet();
        global.add(Modifier.flat(KeystoneKeys.SKILL_LIMIT, 1, "測試"));
        assertEquals(build("summon_skeletons", sheet).minions(), build("summon_skeletons", global).minions());
        assertEquals(2, build("shockwave_totem", global).totems(), "圖騰的上限吃身上的 +1 上限");
        assertEquals(10, build("ancestral_totem", global).totems(), "英靈圖騰固定 10 根");
    }

    @Test
    void marksCanTagAsManyTargetsAsTheLimit() {
        final Debuffs debuffs = new Debuffs();
        final UUID me = UUID.randomUUID();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID c = UUID.randomUUID();
        debuffs.applyMark(a, new Debuffs.Debuff("hunters_mark", me, 100, 0, true, null, List.of(), 0), 2);
        debuffs.applyMark(b, new Debuffs.Debuff("hunters_mark", me, 110, 0, true, null, List.of(), 0), 2);
        assertTrue(debuffs.has(a, "hunters_mark", 0) && debuffs.has(b, "hunters_mark", 0), "上限 2：兩隻都標著");
        debuffs.applyMark(c, new Debuffs.Debuff("hunters_mark", me, 120, 0, true, null, List.of(), 0), 2);
        assertFalse(debuffs.has(a, "hunters_mark", 0), "第三隻：最早的那隻拿掉");
        assertTrue(debuffs.has(b, "hunters_mark", 0) && debuffs.has(c, "hunters_mark", 0));
        // 別種印記照舊全部拿掉；上限 1 照舊只留一個
        debuffs.applyMark(a, new Debuffs.Debuff("voltaic_mark", me, 130, 0, false, null, List.of(), 0));
        assertFalse(debuffs.has(b, "hunters_mark", 0) || debuffs.has(c, "hunters_mark", 0));
        assertTrue(debuffs.has(a, "voltaic_mark", 0));
    }

    // ---- 頂點 II ----

    @Test
    void culminationTwoIsThePoe2Gem() {
        final SupportDefinition top = support("culmination");
        assertEquals("頂點 II", top.name());
        assertEquals(3, top.tier(), "PoE2 第 5 階 → 我們的 3 階");
        assertEquals(4, value(top, SupportExtraKeys.CULMINATION, ModType.FLAT), 1e-9, "每用掉 1 層更多 4%");
        assertEquals(6, value(top, SupportExtraKeys.CULMINATION_DECAY, ModType.FLAT), 1e-9, "6 秒沒獲得就失去");
        assertEquals(10, value(top, SupportExtraKeys.CULMINATION_MAX, ModType.FLAT), 1e-9, "最多 10 層");
        assertEquals(0, value(top, StatKeys.DAMAGE_PER_COMBO, ModType.FLAT), 1e-9, "不再照身上那份連擊算");
        assertEquals(SupportColours.GREEN, SupportColours.colour("culmination"));
        // 近戰攻擊（猛擊這種本身不會獲得連擊的）可以掛
        assertNull(top.refusal(config.skill("earthquake")));
        assertNull(top.refusal(config.skill("leap_slam")));
        // 本來就會獲得連擊的打擊、有使用條件的（驚雷之鐘要 4 點連擊）、法術、召喚物不行
        assertNotNull(top.refusal(config.skill("boneshatter")));
        assertNotNull(top.refusal(config.skill("ice_strike")));
        assertNotNull(top.refusal(config.skill("tempest_bell")));
        assertNotNull(top.refusal(config.skill("fireball")));
        assertNotNull(top.refusal(config.skill("raise_zombie")));
    }

    @Test
    void culminationCountsOtherMeleeHitsSinceLastUse() {
        final ArrayDeque<SkillResources.MeleeHit> hits = new ArrayDeque<>();
        final long decay = SkillResources.culminationDecay(sheetWith(6), Set.of());
        assertEquals(120, decay, "6 秒 = 120 tick");
        // 同一下打中好幾個只算一次
        SkillResources.recordMeleeHit(hits, "boneshatter", 100);
        SkillResources.recordMeleeHit(hits, "boneshatter", 101);
        SkillResources.recordMeleeHit(hits, "boneshatter", 110);
        // 被輔助的技能自己的擊中不給自己連擊
        SkillResources.recordMeleeHit(hits, "earthquake", 120);
        SkillResources.recordMeleeHit(hits, "ice_strike", 130);
        assertEquals(2 + 1, SkillResources.culminationCombo(hits, Long.MIN_VALUE, "earthquake", 140, decay, 10));
        // 用過（用掉全部）之後重新算
        assertEquals(1, SkillResources.culminationCombo(hits, 120, "earthquake", 140, decay, 10));
        assertEquals(0, SkillResources.culminationCombo(hits, 130, "earthquake", 140, decay, 10));
        // 6 秒沒有獲得連擊：全部失去
        assertEquals(0, SkillResources.culminationCombo(hits, Long.MIN_VALUE, "earthquake", 130 + 121, decay, 10));
        // 中間斷過超過 6 秒：斷之前的不算
        SkillResources.recordMeleeHit(hits, "boneshatter", 400);
        SkillResources.recordMeleeHit(hits, "ice_strike", 410);
        assertEquals(2, SkillResources.culminationCombo(hits, Long.MIN_VALUE, "earthquake", 420, decay, 10));
        // 最多 10 層
        for (int i = 0; i < 15; i++) {
            SkillResources.recordMeleeHit(hits, i % 2 == 0 ? "boneshatter" : "ice_strike", 500 + i * 10);
        }
        assertEquals(10, SkillResources.culminationCombo(hits, Long.MIN_VALUE, "earthquake", 650, decay, 10));
        assertEquals(10, SkillResources.culminationMax(sheetWith(0), Set.of()), "沒寫上限照 10 層");
    }

    private static StatSheet sheetWith(final double decaySeconds) {
        final StatSheet sheet = new StatSheet();
        if (decaySeconds > 0) {
            sheet.add(Modifier.flat(SupportExtraKeys.CULMINATION_DECAY, decaySeconds, "測試"));
        }
        return sheet;
    }
}
