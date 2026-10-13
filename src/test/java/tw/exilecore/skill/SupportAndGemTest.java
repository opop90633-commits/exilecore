package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次 2：輔助寶石的資料與效果、寶石物品的資料、未切割寶石的掉落。 */
class SupportAndGemTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertTrue(support != null, "沒有輔助 " + id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        return config.skill(id);
    }

    @Test
    void fortySupportsInThreeTiers() {
        // 2026-10 PoE2 改版：輔助寶石 290 顆（第 1 階 81、第 2 階 166、第 3 階 43，其中 13 顆專屬掉落：終局 4、極境 2、巔峰 7）；
        // 2026-10：圖騰施法與 6 顆觸發改成主要寶石（拿掉 7 顆），加了 2 顆戰星來源（滿星、斷命聚怒）
        final List<String> ids = config.supports().stream().map(SupportDefinition::id).toList();
        assertEquals(290, ids.size());
        assertEquals(List.of("increased_area", "faster_projectiles", "pierce", "faster_attacks", "faster_casting", "added_fire",
                "increased_duration", "critical_strikes", "lingering", "minion_damage"), ids.subList(0, 10), "照檔案裡的順序");
        assertTrue(ids.containsAll(List.of("concentrated_effect", "chain", "life_leech", "searing", "lacerate", "envenom", "concussive",
                "electrocute", "glacial", "static_charge", "cooldown_recovery", "burst", "fork", "critical_damage", "added_chaos",
                "charge_profusion", "cull_frenzy", "minion_life", "warcry_power", "minion_speed", "charge_frenzy", "charge_power", "charge_endurance",
                "spell_echo", "multiple_projectiles", "elemental_focus",
                "starfall_resonance", "permafrost_heart", "styx_ferry", "skyburn_fury", "abyssal_touch", "myriad_stars",
                "oblivion_focus", "storm_maze_heart", "rift_echo", "starfall_core", "phantom_double", "world_collapse", "void_legion")));
        // 改版拿掉的輔助：不在清單裡，身上的換成同階的未切割輔助寶石
        for (final String retired : List.of("multistrike", "multiple_totems", "melee_physical", "life_on_hit", "aura_effect", "generosity",
                "trap", "returning_projectiles", "elemental_damage_with_attacks", "frenzy_on_hit", "power_on_hit", "endurance_on_hit",
                "spell_totem", "trigger_crit", "trigger_hit_taken", "trigger_kill", "trigger_ailment", "cast_on_dodge", "cast_on_minion_death")) {
            assertNull(config.support(retired), retired);
        }
        assertEquals(3, config.retiredSupportTier("multistrike"));
        assertEquals(2, config.retiredSupportTier("melee_physical"));
        assertEquals(81, config.supportsUpTo(1).size());
        assertEquals(247, config.supportsUpTo(2).size());
        assertEquals(277, config.supportsUpTo(3).size(), "專屬掉落的 13 顆切不出來");
        for (final SupportDefinition support : config.supports()) {
            assertFalse(support.name().isBlank(), support.id());
            assertFalse(support.modifiers().isEmpty(), support.id());
            assertTrue(support.manaMultiplier() >= 1.0, support.id() + " 輔助寶石都會讓魔力變貴");
        }
    }

    @Test
    void supportsOnlyFitMatchingSkills() {
        assertTrue(support("pierce").supports(skill("fireball")));
        assertTrue(support("pierce").supports(skill("lightning_arrow")));
        assertFalse(support("pierce").supports(skill("frost_nova")), "霜星爆不是投射物");
        assertTrue(support("chain").supports(skill("arc")), "鏈雷是連鎖技能");
        assertTrue(support("chain").supports(skill("spark")));
        assertFalse(support("chain").supports(skill("earthquake")));
        assertTrue(support("rage").supports(skill("boneshatter")));
        assertFalse(support("rage").supports(skill("lightning_arrow")));
        assertTrue(support("faster_casting").supports(skill("fireball")));
        assertFalse(support("faster_casting").supports(skill("boneshatter")));
        assertTrue(support("increased_area").supports(skill("earthquake")));
        assertTrue(support("increased_area").supports(skill("fireball")), "熾星彈的爆炸是範圍");
        assertFalse(support("increased_area").supports(skill("boneshatter")));
        assertTrue(support("life_leech").supports(skill("lightning_arrow")));
        assertFalse(support("life_leech").supports(skill("frost_nova")), "2026-10：生命偷取只輔助攻擊");
        for (final SupportDefinition support : config.supports()) {
            assertFalse(support.supports(skill(SkillConfig.DEFAULT_MELEE)), "普攻不能掛輔助：" + support.id());
            assertEquals("普攻不能掛輔助寶石", support.refusal(skill(SkillConfig.DEFAULT_MELEE)));
        }
    }

    /**
     * 2026-10：「可以輔助」以前只寫 requires——聚銳寫「攻擊技能或法術技能」，召喚物、光環的法術卻掛不上去，
     * 掛不上去的訊息還寫「要攻擊技能或法術技能」。現在排除的種類也寫出來（只列真的會碰到的），拒絕的原因照實際擋下來的那條。
     */
    @Test
    void supportTextsListWhatIsExcluded() {
        final SupportDefinition surge = support("arcane_surge");
        assertEquals("法術技能（召喚物、捷光環除外）", surge.supportsText(config.all()),
                "光環不算法術了（2026-10），排除裡的光環碰不到，不寫");
        assertEquals(List.of("minion", "aura", "herald"), surge.relevantExcludes(null), "沒有技能清單：全部列");
        assertEquals("法術技能（召喚物、光環、捷光環除外）", surge.supportsText(null));
        assertEquals("召喚物技能不行", surge.refusal(skill("raise_zombie")));
        assertEquals("光環技能不行", surge.refusal(skill("frost_aegis")));
        assertEquals("捷光環技能不行", surge.refusal(skill("herald_of_ash")));
        assertEquals(null, surge.refusal(skill("fireball")));
        assertEquals("要法術技能", surge.refusal(skill("boneshatter")));
        final SupportDefinition power = support("charge_power");
        assertEquals("攻擊技能或法術技能（召喚物、不會造成傷害的技能除外）", power.supportsText(config.all()));
        assertEquals("召喚物技能不行", power.refusal(skill("raise_zombie")));
        assertEquals(null, power.refusal(skill("fireball")));
        assertEquals(null, power.refusal(skill("boneshatter")));
        assertEquals("要攻擊技能", support("life_leech").refusal(skill("fireball")), "沒有排除的：照 requires 講");
        assertEquals("攻擊技能", support("life_leech").supportsText(config.all()));
        assertEquals("近戰技能（召喚物除外）", support("rage").supportsText(config.all()),
                "召喚物用近戰打（melee 標籤）：真的會碰到");
        assertEquals("任何技能", support("starfall_resonance").supportsText(config.all()));
        // 說明裡寫的排除，都真的有玩家學得到的技能被它擋下來
        for (final SupportDefinition support : config.supports()) {
            for (final String tag : support.relevantExcludes(config.all())) {
                assertTrue(config.visible().stream().anyMatch(skill -> skill.tags().contains(tag) && !support.supports(skill)),
                        support.id() + " 的 " + tag);
            }
        }
    }

    @Test
    void modifierTextsReadNaturally() {
        assertEquals("40% 更多近戰物理傷害", ModifierText.describe(
                new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.4, Set.of(Tags.PHYSICAL, Tags.MELEE), "")));
        assertEquals("25% 更少傷害", ModifierText.describe(new Modifier(StatKeys.DAMAGE, ModType.MORE, -0.25, Set.of(), "")));
        assertEquals("額外 2 個投射物", ModifierText.describe(new Modifier(StatKeys.SKILL_PROJECTILES, ModType.FLAT, 2, Set.of(), "")));
        assertEquals("增加 40% 範圍效果", ModifierText.describe(new Modifier(StatKeys.SKILL_AREA, ModType.INCREASED, 0.4, Set.of(), "")));
        assertEquals("物理傷害的 25% 額外附加為火焰傷害", ModifierText.describe(
                new Modifier(StatKeys.damageGain(DamageType.FIRE), ModType.FLAT, 25, Set.of(), "")));
        assertEquals("造成的物理傷害有 5% 偷取為生命", ModifierText.describe(new Modifier(StatKeys.LEECH_LIFE, ModType.FLAT, 5, Set.of(), "")));
        assertEquals("任何技能", new SupportDefinition("x", "x", "", "BOOK", 1, Set.of(), Set.of(), 1.0,
                List.of(Modifier.flat(StatKeys.SKILL_PIERCE, 1, "x")), List.of()).requiresText());
    }

    @Test
    void buildsApplySupportsToNumbersAndMana() {
        final StatSheet sheet = new StatSheet();
        final SkillDefinition fireball = skill("fireball");
        final SkillBuild plain = SkillBuild.of(config, fireball, 10, 60, false, List.of(), Set.of(), sheet);
        assertEquals(1, plain.projectiles());
        assertEquals(0, plain.pierce());
        assertEquals(1.0, plain.areaScale(), 1e-9);
        assertEquals(SkillProfiles.manaCost(fireball, 10), plain.manaCost());

        final SkillBuild supported = SkillBuild.of(config, fireball, 10, 60, false, List.of(support("multiple_projectiles"),
                support("pierce"), support("increased_area"), support("faster_projectiles"), support("rage")), Set.of(), sheet);
        assertEquals(4, supported.supports().size(), "怒火（要近戰）不能輔助法術，掛了也不算");
        assertEquals(3, supported.projectiles());
        assertEquals(24, supported.spread(), 1e-9, "本來一發的技能多了投射物：排成扇形");
        assertEquals(2, supported.pierce());
        assertEquals(Math.sqrt(1.4), supported.areaScale(), 1e-9, "增加 40% 範圍 → 半徑 ×√1.4");
        assertEquals(plain.explode() * Math.sqrt(1.4), supported.explode(), 1e-9);
        assertEquals(plain.speed() * 1.5, supported.speed(), 1e-9);
        assertEquals(Math.round(fireball.mana().at(10) * 1.4 * 1.2 * 1.2 * 1.15), supported.manaCost(), "魔力倍率相乘");

        final SkillBuild concentrated = SkillBuild.of(config, skill("frost_nova"), 1, 60, false, List.of(support("concentrated_effect")), Set.of(), sheet);
        assertEquals(skill("frost_nova").params().radius().at(1) * Math.sqrt(0.7), concentrated.radius(), 1e-9);

        final SkillBuild arc = SkillBuild.of(config, skill("arc"), 1, 60, false, List.of(support("chain")), Set.of(), sheet);
        assertEquals(skill("arc").params().chains().intAt(1) + 1, arc.chains(), "鏈雷多連鎖一次");
        final SkillBuild spark = SkillBuild.of(config, skill("spark"), 1, 60, false, List.of(support("chain")), Set.of(), sheet);
        assertEquals(1, spark.chains(), "投射物打中之後連鎖一次");
    }

    /**
     * 複擊（2026-10）：近戰攻擊按一次連打幾下，照攻擊間隔一下一下打，魔力只扣一次，打完才能做別的。
     * 複擊這顆輔助寶石在 2026-10 PoE2 改版拿掉了（supports.yml 的 retired），引擎的 skill.repeats 還在，這裡直接用屬性測。
     */
    @Test
    void multistrikeRepeatsMeleeAttacks() {
        assertNull(config.support("multistrike"), "複擊寶石改版拿掉了");
        assertEquals(3, config.retiredSupportTier("multistrike"), "身上的換成第 3 階的未切割輔助寶石");
        assertEquals("近戰攻擊按一次自動再打 2 下（魔力只扣一次）",
                ModifierText.describe(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 2, Set.of(), "")));
        final StatSheet twice = new StatSheet().add(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 2, Set.of(), "測試"));
        for (final String id : List.of("boneshatter", "tempest_flurry", "killing_palm", "ice_strike", "whirling_slash", "flicker_strike")) {
            final SkillDefinition definition = skill(id);
            assertEquals(0, SkillBuild.of(config, definition, 10, 60, false, List.of(), Set.of(), new StatSheet()).repeats(), id);
            assertEquals(2, SkillBuild.of(config, definition, 10, 60, false, List.of(), Set.of(), twice).repeats(), id + "：按一次再打兩下");
        }
        assertTrue(skill("flicker_strike").tags().contains(Tags.MOVEMENT) && !skill("flicker_strike").tags().contains(Tags.TRAVEL),
                "瞬影擊是瞬移到敵人身邊，不算突進");
        assertTrue(skill("lunge").tags().contains(Tags.TRAVEL) && skill("leap_slam").tags().contains(Tags.TRAVEL));
        // 就算別的地方給了 skill.repeats，也只有自己放的打擊、扇形、繞身一圈的攻擊會重複
        final StatSheet repeating = new StatSheet().add(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 9, Set.of(), "測試"));
        assertEquals(SkillBuild.MAX_REPEATS, SkillBuild.of(config, skill("boneshatter"), 1, 60, false, List.of(), Set.of(), repeating).repeats(),
                "最多 4 下");
        for (final String id : List.of("fireball", "permafrost_bolts", "leap_slam", "lunge", "ancestral_totem", "rain_of_arrows",
                "raise_zombie")) {
            assertEquals(0, SkillBuild.of(config, skill(id), 1, 60, false, List.of(), Set.of(), repeating).repeats(), id);
        }
        assertEquals(0, SkillBuild.of(config, skill(SkillConfig.DEFAULT_MELEE), 1, 60, true, List.of(), Set.of(), repeating).repeats(),
                "普攻不重複");

        // 排程：第一下之後，每隔一次攻擊間隔再打一下；全部打完再隔一次間隔才能做別的
        final SkillBuild build = SkillBuild.of(config, skill("boneshatter"), 1, 60, false, List.of(), Set.of(), twice);
        SkillRuntime.Repeat repeat = new SkillRuntime.Repeat(112.5, 12.5, build.repeats(), build, null);
        assertEquals(137.5, repeat.doneAt(), 1e-9, "100 打第一下、112.5 與 125 再打，137.5 才能做別的（跟連按三下一樣）");
        repeat = repeat.next();
        assertEquals(125, repeat.atTick(), 1e-9);
        assertEquals(1, repeat.left());
        assertEquals(137.5, repeat.doneAt(), 1e-9);
        assertEquals(null, repeat.next(), "打完了");
    }

    @Test
    void gemLevelIsCappedByCharacterLevel() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild capped = SkillBuild.of(config, skill("fireball"), 20, 10, false, List.of(), Set.of(), sheet);
        assertEquals(config.maxLevelFor(10), capped.level(), "角色等級不夠：照能用的最高等級施放");
        assertEquals(20, capped.gemLevel());
        assertTrue(capped.levelCapped());
        final SkillBuild fine = SkillBuild.of(config, skill("fireball"), 3, 10, false, List.of(), Set.of(), sheet);
        assertFalse(fine.levelCapped());
        final SkillBuild defaultAttack = SkillBuild.of(config, skill(SkillConfig.DEFAULT_BOW), 1, 1, true,
                List.of(support("pierce")), Set.of(Tags.BOW), sheet);
        assertTrue(defaultAttack.supports().isEmpty(), "普攻沒有輔助");
    }

    @Test
    void supportsChangeDamageAndSpeedOnlyForTheirSkill() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.CRIT_MULTI, 150)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "斧"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "斧"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.0, "斧"));
        final SkillDefinition heavy = skill("boneshatter");
        final SkillBuild base = SkillBuild.of(config, heavy, 1, 1, false, List.of(), Set.of(Tags.MACE), sheet);
        final SkillBuild strong = SkillBuild.of(config, heavy, 1, 1, false,
                List.of(support("heft"), support("faster_attacks"), support("added_fire")), Set.of(Tags.MACE), sheet);
        final double basePhysical = DamageCalculator.expectedOutgoing(sheet, base.profile()).get(DamageType.PHYSICAL);
        final double strongPhysical = DamageCalculator.expectedOutgoing(sheet, strong.profile()).get(DamageType.PHYSICAL);
        assertEquals(basePhysical * 1.25 * 0.9, strongPhysical, 1e-9, "25% 更多物理（重擊）× 10% 更少（迅擊）");
        assertTrue(DamageCalculator.expectedOutgoing(sheet, strong.profile()).get(DamageType.FIRE) > 0, "引火：物理的 25% 額外附加為火焰");
        assertEquals(base.intervalTicks(sheet) / (1.25 * 0.95), strong.intervalTicks(sheet), 1e-9, "25% 更多攻擊速度（迅擊）× 5% 更少（重擊）");
        assertEquals(basePhysical, DamageCalculator.expectedOutgoing(sheet, base.profile()).get(DamageType.PHYSICAL), 1e-9);
    }

    @Test
    void gemDataSurvivesTheItemRoundTrip() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 7, 4, List.of("pierce", "", "chain"));
        final GemData skillGem = GemData.skill(entry);
        assertEquals(Optional.of(skillGem), GemData.decode(skillGem.encode()));
        assertEquals(entry, GemData.decode(skillGem.encode()).orElseThrow().entry());
        assertEquals(Optional.of(GemData.uncutSkill(20)), GemData.decode(GemData.uncutSkill(20).encode()));
        assertEquals(Optional.of(GemData.uncutSupport(3)), GemData.decode(GemData.uncutSupport(3).encode()));
        assertEquals(Optional.of(GemData.support("pierce")), GemData.decode(GemData.support("pierce").encode()));
        assertEquals(21, GemData.uncutSkill(99).level(), "等級夾在 1–21（2026-10：Lv.21 的未切割寶石只有劫盜大金庫掉）");
        assertEquals(1, GemData.uncutSupport(0).level(), "階級夾在 1–3");
        assertTrue(GemData.decode("nonsense").isEmpty());
        assertTrue(GemData.decode("skill;;5;").isEmpty(), "技能寶石一定要有技能");
        assertTrue(GemData.decode("uncut_skill;;abc;").isEmpty());
        assertEquals(1, GemData.skill(entry).maxStack(), "技能寶石不能疊");
        assertEquals(20, GemData.uncutSkill(3).maxStack());
    }

    @Test
    void uncutGemsDropByMonsterLevel() {
        final Random random = new Random(5);
        final int max = config.uncutLevelFor(30);
        assertEquals(config.maxLevelFor(30), max);
        int top = 0;
        int lower = 0;
        for (int i = 0; i < 2000; i++) {
            final int level = config.rollUncutLevel(30, random);
            assertTrue(level >= max - 2 && level <= max, "等級 " + level);
            if (level == max) {
                top++;
            } else if (level == max - 2) {
                lower++;
            }
        }
        // 2026-10：兩成是最高等級、四成低 1 級、四成低 2 級（skills.yml 的 uncut_level_weights；以前 60/30/10）
        assertTrue(top > 300 && top < 500, "兩成是最高等級：" + top);
        assertTrue(lower > 700 && lower < 900, "四成低 2 級：" + lower);
        assertEquals(0.2, config.uncutLevelChance(0), 1e-9);
        assertEquals(0.4, config.uncutLevelChance(2), 1e-9);
        assertEquals(0, config.uncutLevelChance(3), 1e-9);
        assertEquals(1, config.rollUncutLevel(1, random), "1 級的怪掉 Lv.1");
        assertEquals(1, config.supportTierFor(1));
        assertEquals(2, config.supportTierFor(15));
        assertEquals(3, config.supportTierFor(60));
        for (int i = 0; i < 200; i++) {
            assertEquals(1, config.rollSupportTier(10, random));
            final int tier = config.rollSupportTier(40, random);
            assertTrue(tier >= 1 && tier <= 3);
        }
        assertEquals(List.of("escape_shot", "raise_guard", "explosive_bolt", "rapid_fire", "tempest_flurry", "lightning_spear", "fireball",
                "spark", "raise_zombie", "armour_piercing_bolt", "fortifying_cry", "frostbolt", "essence_drain", "bear_maul", "wolf_shred",
                "disengage", "frozen_locus", "lightning_rod", "fragmentation_rounds"),
                config.engravable(1).stream().map(SkillDefinition::id).toList(), "Lv.1 的未切割寶石能雕的技能（第 1 階的技能）");
        assertTrue(config.engravable(13).size() < config.visible().size());
        // 2026-10：巔峰 Boss 的專屬技能寶石（source: awakener）雕不出來，其他的 Lv.14 起全部都能雕（最高第 14 階）
        final List<SkillDefinition> exclusive = config.skillsFrom("awakener");
        assertEquals(List.of("void_meteor", "oblivion_ray", "storm_labyrinth", "fourfold_ray", "rift_slash", "starfall_volley"),
                exclusive.stream().map(SkillDefinition::id).toList());
        assertEquals(config.visible().size() - exclusive.size(), config.engravable(14).size(), "Lv.14 起全部都能雕（專屬的除外）");
        assertEquals(config.engravable(14).size(), config.engravable(20).size());
        for (final SkillDefinition skill : exclusive) {
            assertFalse(skill.engravable(), skill.id());
            assertFalse(config.engravable(20).contains(skill), skill.id() + "：未切割技能寶石雕不出來");
        }
    }
}
