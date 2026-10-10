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
        assertEquals(List.of("increased_area", "faster_projectiles", "pierce", "faster_attacks", "faster_casting", "added_fire",
                        "increased_duration", "critical_strikes", "life_on_hit", "lingering", "minion_damage",
                        "concentrated_effect", "chain", "melee_physical", "life_leech", "searing", "lacerate", "envenom", "concussive", "electrocute",
                        "glacial", "static_charge",
                        "cooldown_recovery", "burst", "fork", "elemental_damage_with_attacks", "critical_damage", "added_chaos",
                        "spell_totem", "trap", "minion_life", "aura_effect", "generosity", "warcry_power", "minion_speed",
                        "frenzy_on_hit", "power_on_hit", "endurance_on_hit",
                        "multiple_totems", "spell_echo", "multistrike", "returning_projectiles", "multiple_projectiles", "elemental_focus",
                        "starfall_resonance", "permafrost_heart", "styx_ferry", "skyburn_fury", "abyssal_touch", "myriad_stars"),
                config.supports().stream().map(SupportDefinition::id).toList());
        assertEquals(11, config.supportsUpTo(1).size());
        assertEquals(38, config.supportsUpTo(2).size(), "第 2 階多了聚怒、聚銳、聚磐（2026-10）");
        assertEquals(44, config.supportsUpTo(3).size(), "專屬掉落的 6 顆切不出來；第 3 階多了複擊（2026-10）");
        for (final SupportDefinition support : config.supports()) {
            assertFalse(support.name().isBlank(), support.id());
            assertFalse(support.modifiers().isEmpty(), support.id());
            assertTrue(support.manaMultiplier() >= 1.0, support.id() + " 輔助寶石都會讓魔力變貴");
        }
    }

    @Test
    void supportsOnlyFitMatchingSkills() {
        assertTrue(support("pierce").supports(skill("fireball")));
        assertTrue(support("pierce").supports(skill("split_arrow")));
        assertFalse(support("pierce").supports(skill("frost_nova")), "霜星爆不是投射物");
        assertTrue(support("chain").supports(skill("arc")), "鏈雷是連鎖技能");
        assertTrue(support("chain").supports(skill("spark")));
        assertFalse(support("chain").supports(skill("cleave")));
        assertTrue(support("melee_physical").supports(skill("heavy_strike")));
        assertFalse(support("melee_physical").supports(skill("split_arrow")));
        assertTrue(support("faster_casting").supports(skill("fireball")));
        assertFalse(support("faster_casting").supports(skill("heavy_strike")));
        assertTrue(support("increased_area").supports(skill("cleave")));
        assertTrue(support("increased_area").supports(skill("fireball")), "熾星彈的爆炸是範圍");
        assertFalse(support("increased_area").supports(skill("heavy_strike")));
        assertTrue(support("life_leech").supports(skill("burning_arrow")));
        assertFalse(support("life_leech").supports(skill("frost_nova")));
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
        final SupportDefinition power = support("power_on_hit");
        assertEquals("攻擊技能或法術技能（召喚物、光環除外）", power.supportsText(config.all()),
                "戰吼不算法術了，排除裡的戰吼碰不到，不寫");
        assertEquals(List.of("minion", "aura", "warcry"), power.relevantExcludes(null), "沒有技能清單：全部列");
        assertEquals("攻擊技能或法術技能（召喚物、光環、戰吼除外）", power.supportsText(null));
        assertEquals("召喚物技能不行", power.refusal(skill("raise_zombie")));
        assertEquals("光環技能不行", power.refusal(skill("hatred")));
        assertEquals(null, power.refusal(skill("fireball")));
        assertEquals(null, power.refusal(skill("heavy_strike")));
        assertEquals("要攻擊技能", support("frenzy_on_hit").refusal(skill("fireball")), "沒有排除的：照 requires 講");
        assertEquals("攻擊技能", support("frenzy_on_hit").supportsText(config.all()));
        assertEquals("近戰技能（召喚物除外）", support("endurance_on_hit").supportsText(config.all()),
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
                support("pierce"), support("increased_area"), support("faster_projectiles"), support("melee_physical")), Set.of(), sheet);
        assertEquals(4, supported.supports().size(), "蠻力不能輔助法術，掛了也不算");
        assertEquals(3, supported.projectiles());
        assertEquals(0, supported.spread(), 1e-9, "本來一發的技能多了投射物：不張成扇形，沿同一條線前後連發（2026-10）");
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

    /** 複擊（2026-10）：近戰攻擊按一次連打三下，照攻擊間隔一下一下打，魔力只扣一次，打完才能做別的。 */
    @Test
    void multistrikeRepeatsMeleeAttacks() {
        final SupportDefinition multistrike = support("multistrike");
        assertEquals("複擊", multistrike.name());
        assertEquals(3, multistrike.tier());
        assertEquals("近戰技能（猛擊、突進、召喚物、圖騰除外）", multistrike.supportsText(config.all()));
        assertEquals("近戰攻擊按一次自動再打 2 下（魔力只扣一次）",
                ModifierText.describe(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 2, Set.of(), "")));
        final StatSheet sheet = new StatSheet();
        for (final String id : List.of("heavy_strike", "cleave", "puncture", "boneshatter", "tempest_flurry", "killing_palm",
                "ice_strike", "whirling_slash", "flicker_strike")) {
            final SkillDefinition definition = skill(id);
            assertTrue(multistrike.supports(definition), id);
            final SkillBuild plain = SkillBuild.of(config, definition, 10, 60, false, List.of(), Set.of(), sheet);
            final SkillBuild repeated = SkillBuild.of(config, definition, 10, 60, false, List.of(multistrike), Set.of(), sheet);
            assertEquals(0, plain.repeats(), id);
            assertEquals(2, repeated.repeats(), id + "：按一次再打兩下");
            assertEquals(1.4, plain.intervalTicks(sheet) / repeated.intervalTicks(sheet), 1e-9, id + "：每一下快 40%");
            assertEquals(Math.round(definition.mana().at(10) * 1.5), repeated.manaCost(), id + "：三下只扣一次 ×1.5 的魔力");
        }
        assertEquals("猛擊技能不行", multistrike.refusal(skill("ground_slam")), "出手慢的猛擊一次要鎖好幾秒");
        assertEquals("猛擊技能不行", multistrike.refusal(skill("earthquake")));
        assertEquals("猛擊技能不行", multistrike.refusal(skill("leap_slam")));
        assertEquals("突進技能不行", multistrike.refusal(skill("whirling_blades")), "一路衝過去的位移");
        assertEquals("突進技能不行", multistrike.refusal(skill("lunge")));
        assertNull(multistrike.refusal(skill("flicker_strike")), "瞬影擊是瞬移到敵人身邊：可以，連閃三次（2026-10）");
        assertTrue(skill("flicker_strike").tags().contains(Tags.MOVEMENT) && !skill("flicker_strike").tags().contains(Tags.TRAVEL));
        assertTrue(skill("whirling_blades").tags().contains(Tags.TRAVEL) && skill("leap_slam").tags().contains(Tags.TRAVEL));
        assertEquals("圖騰技能不行", multistrike.refusal(skill("ancestral_totem")));
        assertEquals("召喚物技能不行", multistrike.refusal(skill("raise_zombie")), "召喚物用近戰打，但不是自己揮");
        assertEquals("要近戰技能", multistrike.refusal(skill("split_arrow")));
        assertEquals("要近戰技能", multistrike.refusal(skill("fireball")));
        assertFalse(multistrike.supports(skill(SkillConfig.DEFAULT_MELEE)), "普攻不能掛");
        // 就算別的地方給了 skill.repeats，也只有自己放的打擊、扇形、繞身一圈的攻擊會重複
        final StatSheet repeating = new StatSheet().add(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 9, Set.of(), "測試"));
        assertEquals(SkillBuild.MAX_REPEATS, SkillBuild.of(config, skill("heavy_strike"), 1, 60, false, List.of(), Set.of(), repeating).repeats(),
                "最多 4 下");
        for (final String id : List.of("fireball", "split_arrow", "leap_slam", "whirling_blades", "lunge", "ancestral_totem", "rain_of_arrows")) {
            assertEquals(0, SkillBuild.of(config, skill(id), 1, 60, false, List.of(), Set.of(), repeating).repeats(), id);
        }
        assertEquals(0, SkillBuild.of(config, skill(SkillConfig.DEFAULT_MELEE), 1, 60, true, List.of(), Set.of(), repeating).repeats(),
                "普攻不重複");

        // 排程：第一下之後，每隔一次攻擊間隔再打一下；全部打完再隔一次間隔才能做別的
        final SkillBuild build = SkillBuild.of(config, skill("heavy_strike"), 1, 60, false, List.of(multistrike), Set.of(), sheet);
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
        final SkillDefinition heavy = skill("heavy_strike");
        final SkillBuild base = SkillBuild.of(config, heavy, 1, 1, false, List.of(), Set.of(Tags.AXE), sheet);
        final SkillBuild strong = SkillBuild.of(config, heavy, 1, 1, false,
                List.of(support("melee_physical"), support("faster_attacks"), support("added_fire")), Set.of(Tags.AXE), sheet);
        final double basePhysical = DamageCalculator.expectedOutgoing(sheet, base.profile()).get(DamageType.PHYSICAL);
        final double strongPhysical = DamageCalculator.expectedOutgoing(sheet, strong.profile()).get(DamageType.PHYSICAL);
        assertEquals(basePhysical * 1.4 * 0.9, strongPhysical, 1e-9, "40% 更多近戰物理 × 10% 更少（迅擊）");
        assertTrue(DamageCalculator.expectedOutgoing(sheet, strong.profile()).get(DamageType.FIRE) > 0, "引火：物理的 25% 額外附加為火焰");
        assertEquals(base.intervalTicks(sheet) / 1.25, strong.intervalTicks(sheet), 1e-9, "25% 更多攻擊速度");
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
        assertEquals(20, GemData.uncutSkill(99).level(), "等級夾在 1–20");
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
        assertEquals(List.of("heavy_strike", "cleave", "dash", "split_arrow", "explosive_bolt", "rapid_fire", "tempest_flurry",
                "lightning_spear", "fireball", "spark", "flame_dash", "bear_trap", "chaos_bolt", "raise_zombie",
                "purity_of_elements", "vitality", "clarity", "precision", "enduring_cry"),
                config.engravable(1).stream().map(SkillDefinition::id).toList(), "Lv.1 的未切割寶石能雕的技能（每個職業的起始技能都在）");
        assertEquals(config.visible().size(), config.engravable(5).size(), "Lv.5 起全部都能雕");
    }
}
