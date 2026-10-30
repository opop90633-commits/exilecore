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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // 2026-10：圖騰施法與 6 顆觸發改成主要寶石（拿掉 7 顆），加了 2 顆戰星來源（滿星、撲殺聚怒）
        final List<String> ids = config.supports().stream().map(SupportDefinition::id).toList();
        assertEquals(293 + 21 + 2 + 10 + 3 + 24 - 19 + 1 + 120 + 68 - 17 - 3 - 4 - 2 + 1 + 1 - 1 + 1 + 2, ids.size()); // 2026-10-30 照 PoE2：＋處刑 II、III // 2026-10-30 照 PoE2：＋骸令 II（Bidding II） // 2026-10-29 照站長：導電併進雷殛（－1） // 2026-10-29 照站長加回衝勢（＋1） // 2026-10-28：＋混沌同調 // 2026-10-28 照站長刪掉震符印、符刃（－2） // 2026-10-27 照站長刪掉箭幕、跛行、活雷 II、衝勢（－4） // 2026-10-27：照站長只留最高階，再併掉持久、速冷、分矢（−3） // 2026-10-27：照站長刪掉原作沒有的 17 顆（supports.yml 的 retired） // 2026-10-27：＋68 顆血脈輔助（supports_lineage.yml） // 2026-10-27：＋120 顆照 PoE2 0.5 補上的一般輔助（tools/poe2_supports/missing_0.5.tsv） // 2026-10-27：照 PoE2 0.5 恢復節流（Efficiency） // 2026-10-26：照站長 I／II／III 只留最高階（刪 19 顆，併入最高階）； 2026-10-26：照 PoE2 把 I／II／III 拆成不同寶石（＋18 顆）、新增 6 顆戰吼輔助（不竭嘯、霜仇嘯、契血嘯、血譽、碎晶嘯、怒嘯 II） // 2026-10-25：＋3 顆能量輔助（湧能、返能、擴能） // 2026-10：＋怒嘯；2026-10-15：＋碎界擴散（虛星專屬）；＋潮汐共鳴（淵鯨專屬）；2026-10-18：＋七隻事件巔峰 Boss 各 3 顆專屬、淵鯨再 2 顆；2026-10-24：＋10 顆戰星輔助（恆星、倍星、抑星…；磐震、磐破、雷星只留最強的那一級）
        assertEquals(List.of("increased_area", "pierce", "faster_attacks", "faster_casting", "added_fire",
                "critical_strikes", "lingering", "minion_damage", "concentrated_effect"), ids.subList(0, 9), "照檔案裡的順序（2026-10-27：持久併入長留，拿掉；2026-10-28：疾矢升成疾矢 III（第 3 階），排到後面）");
        assertEquals(3, config.support("faster_projectiles").tier(), "2026-10-28 照 PoE2 Projectile Acceleration III（最高階）");
        assertTrue(ids.containsAll(List.of("concentrated_effect", "chain", "life_leech", "searing_ii", "lacerate_iii", "envenom_iii", "concussive",
                "electrocute", "glacial", "static_charge", "swift_cooldown", "burst", "fork", "critical_damage", "added_chaos",
                "charge_profusion", "cull_frenzy", "minion_life", "minion_speed", "charge_endurance",
                "spell_echo", "multiple_projectiles", "elemental_focus",
                "starfall_resonance", "permafrost_heart", "styx_ferry", "skyburn_fury", "abyssal_touch", "myriad_stars",
                "oblivion_focus", "storm_maze_heart", "rift_echo", "starfall_core", "phantom_double", "world_collapse", "void_legion", "shattering_strike")));
        // 改版拿掉的輔助：不在清單裡，身上的換成同階的未切割輔助寶石
        for (final String retired : List.of("multistrike", "multiple_totems", "melee_physical", "life_on_hit", "aura_effect", "generosity",
                "trap", "returning_projectiles", "elemental_damage_with_attacks", "frenzy_on_hit", "power_on_hit", "endurance_on_hit",
                "spell_totem", "trigger_crit", "trigger_hit_taken", "trigger_kill", "trigger_ailment", "cast_on_dodge", "cast_on_minion_death")) {
            assertNull(config.support(retired), retired);
        }
        assertEquals(3, config.retiredSupportTier("multistrike"));
        assertEquals(2, config.retiredSupportTier("melee_physical"));
        assertEquals(76 + 6 - 1 - 3 - 1 - 1 - 1, config.supportsUpTo(1).size()); // 2026-10-29：導電（第 1 階）併進雷殛（－1） // 2026-10-28 照 PoE2：疾矢升成疾矢 III（第 3 階）（−1） // 2026-10-28：繁置（Overabundance I，第 1 階）併入過剩 II（第 3 階）（−1） // 2026-10-27：持久、分矢併入第 2 階的長留、分光，初裂改成 Bleed II（第 2 階），第 1 階 −3 // 2026-10-27：刪掉原作沒有的凝凍 // 2026-10-27：＋6 顆第 1 階的新輔助 // 2026-10-27 照 PoE2 對照 79 顆輔助的階級（回震 II、目眩 II、殘肢、熟招、厚晶、脆晶、撒蒺升階；這次之前 80） // 2026-10-26：I／II／III 只留最高階，第 1 階少了割裂、淬毒、釘地、急蝕、冰嚙（這次之前 85）； 2026-10-26：照 PoE2 的階級（I／II／III 拆開、新增戰吼輔助）；這次之前 79 // 2026-10-26：照 PoE2 的階級改了一些；2026-10-24：＋恆星、蔓寒
        assertEquals(239 + 76 - 16 - 3 - 3 - 2 - 1 - 1 + 1 - 1, config.supportsUpTo(2).size()); // 2026-10-29：導電併進雷殛（－1） // 2026-10-29 照站長加回第 2 階的衝勢（＋1） // 2026-10-28 照 PoE2：疾矢升成疾矢 III（第 3 階）（－1） // 2026-10-28：繁置併入第 3 階的過剩 II（－1） // 2026-10-28 照站長刪掉第 2 階的震符印、符刃（－2） // 2026-10-27 照站長刪掉第 2 階的箭幕、跛行、衝勢（－3） // 2026-10-27：持久、速冷、分矢併入最高階（−3） // 2026-10-27：刪掉原作沒有的 16 顆第 1–2 階 // 2026-10-27：＋76 顆第 1–2 階的新輔助 // 2026-10-27 照 PoE2：巨魄、腐蝕嘯、電印地、累勢、漸熾、播火、節流是第 3 階（這次之前 245） // 2026-10-26：I／II／III 只留最高階（第 1–2 階少 17 顆；這次之前 262）； 2026-10-26：同上；這次之前 255 // 2026-10：＋怒嘯；2026-10-24：＋倍星、抑星、狂速、碎地，磐震、磐破改第 3 階
        assertEquals(297 - 8 + 120 - 17 - 3 - 4 - 2 + 1 + 1 - 1 + 1 + 2, config.supportsUpTo(3).size(), "2026-10-30：＋處刑 II、III（第 3 階）；2026-10-30：＋骸令 II（第 3 階）；2026-10-29：導電併進雷殛（－1）；2026-10-29 照站長加回衝勢（＋1）；2026-10-28：＋混沌同調；2026-10-28 照站長刪掉震符印、符刃（－2）；2026-10-27 照站長刪掉箭幕、跛行、活雷 II、衝勢（－4）；2026-10-27：持久、速冷、分矢併入最高階（−3）；2026-10-27：刪掉原作沒有的 17 顆；專屬掉落的 13 顆切不出來（2026-10-27：supports.yml 原本的 8 顆血脈輔助改成切不出來，−8；2026-10-27：＋120 顆照 PoE2 0.5 補上的；2026-10-27：＋節流；2026-10-24：＋10 顆戰星輔助；2026-10-26：＋24 顆照 PoE2 拆開與新增的；2026-10-26：I／II／III 只留最高階，−19 顆，這次之前 315）");
        for (final SupportDefinition support : config.supports()) {
            assertFalse(support.name().isBlank(), support.id());
            assertFalse(support.modifiers().isEmpty(), support.id());
            assertTrue(support.manaMultiplier() >= 1.0 || support.lineage(), support.id() + " 輔助寶石都會讓魔力變貴（2026-10-27：血脈的願晶照 PoE2 是 90%）");
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
        assertTrue(support("increased_area").supports(skill("boneshatter")), "2026-10-26 照 PoE2：碎脊擊是範圍打擊");
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
        assertEquals("光環技能不行", surge.refusal(skill("discipline")));
        assertEquals("要法術技能", surge.refusal(skill("frost_aegis")), "2026-10-29 照 PoE2：凜冬之殼是常駐增益、不是光環");
        assertEquals("捷光環技能不行", surge.refusal(skill("herald_of_ash")));
        assertEquals(null, surge.refusal(skill("fireball")));
        assertEquals("要法術技能", surge.refusal(skill("boneshatter")));
        final SupportDefinition power = support("charge_endurance");
        assertEquals("攻擊技能或法術技能（召喚物、不會造成傷害的技能除外）", power.supportsText(config.all()));
        assertEquals("召喚物技能不行", power.refusal(skill("raise_zombie")));
        assertEquals(null, power.refusal(skill("fireball")));
        assertEquals(null, power.refusal(skill("boneshatter")));
        assertEquals("要攻擊技能", support("life_leech").refusal(skill("fireball")), "沒有排除的：照 requires 講");
        // 2026-10-29 照 PoE2 的 Minion Types：飲血照召喚物的攻擊也能掛在召喚技能上，說明多寫一句；這次之前只寫「攻擊技能」
        assertEquals("攻擊技能；召喚物的技能符合時也能掛在召喚技能上（效果給召喚物）", support("life_leech").supportsText(config.all()));
        assertEquals("近戰技能（召喚物除外）", support("rage").supportsText(config.all()),
                "召喚物用近戰打（melee 標籤）：真的會碰到");
        // 2026-10-29 照 PoE2 的 Minion Types：星骸共鳴也能照召喚物的範圍技能掛在召喚技能上；這次之前只寫「任何技能」
        assertEquals("任何技能；召喚物的技能符合時也能掛在召喚技能上（效果給召喚物）", support("starfall_resonance").supportsText(config.all()));
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
        assertEquals(1, supported.pierce(), "2026-10-26 照 PoE2 Pierce I：穿透 1");
        assertEquals(Math.sqrt(1.35), supported.areaScale(), 1e-9, "增加 35% 範圍 → 半徑 ×√1.35");
        assertEquals(plain.explode() * Math.sqrt(1.35), supported.explode(), 1e-9);
        assertEquals(plain.speed() * 1.4, supported.speed(), 1e-9);
        assertEquals(Math.round(fireball.mana().at(10) * 1.0 * 1.0 * 1.3 * 1.2 * SkillConfig.manaCostScale()), supported.manaCost(), "魔力倍率相乘");

        final SkillBuild concentrated = SkillBuild.of(config, skill("frost_nova"), 1, 60, false, List.of(support("concentrated_effect")), Set.of(), sheet);
        assertEquals(skill("frost_nova").params().radius().at(1) * Math.sqrt(0.5), concentrated.radius(), 1e-9);

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
        assertTrue(skill("rake").tags().contains(Tags.TRAVEL) && skill("leap_slam").tags().contains(Tags.TRAVEL));
        // 就算別的地方給了 skill.repeats，也只有自己放的打擊、扇形、繞身一圈的攻擊會重複
        final StatSheet repeating = new StatSheet().add(new Modifier(StatKeys.SKILL_REPEATS, ModType.FLAT, 9, Set.of(), "測試"));
        assertEquals(SkillBuild.MAX_REPEATS, SkillBuild.of(config, skill("boneshatter"), 1, 60, false, List.of(), Set.of(), repeating).repeats(),
                "最多 5 下（2026-10-28 照 PoE2 三連刺一次刺六下：4 → 5）");
        for (final String id : List.of("fireball", "permafrost_bolts", "leap_slam", "rake", "ancestral_totem", "rain_of_arrows",
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
        // 2026-10-26 照 PoE2 的沉重（Heft）：只有最大傷害更多 30%（武器 10–10：最大 13，平均 11.5）；這次之前物理一律更多 25%、攻速更少 5%
        assertEquals(basePhysical * 1.15, strongPhysical, 1e-9, "最大物理傷害更多 30%（沉重）；迅擊 2026-10-26 照 PoE2 不再減傷");
        assertTrue(DamageCalculator.expectedOutgoing(sheet, strong.profile()).get(DamageType.FIRE) > 0, "引火：物理的 25% 額外附加為火焰");
        assertEquals(base.intervalTicks(sheet) / 1.15, strong.intervalTicks(sheet), 1e-9, "增加 15% 攻擊速度（迅擊）；2026-10-26 沉重不再減攻速");
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

    /** 2026-10-26：照站長拿掉的技能：定義刪掉、列在 skills.yml 的 retired；玩家手上的舊寶石換成同等級的未切割技能寶石。 */
    @Test
    void removedSkillGemsBecomeUncutGemsOfTheSameLevel() {
        final java.util.Map<String, String> removed = new java.util.LinkedHashMap<>();
        removed.put("frost_snap", "霜綻");
        removed.put("storm_spin", "斬風旋");
        removed.put("prismatic_strike", "幻元擊");
        removed.put("prismatic_arrow", "幻元箭");
        removed.put("ember_nest_totem", "焰巢圖騰");
        removed.put("frost_pulse_totem", "霜脈圖騰");
        removed.put("storm_pylon_totem", "雷樁圖騰");
        removed.put("bulwark_totem", "磐守圖騰");
        removed.put("bone_steed", "骨駒");
        removed.put("hoof_trample", "鐵蹄踐踏");
        removed.put("frost_stream", "霜流");
        removed.put("storm_lash", "雷鞭");
        removed.put("star_cadence", "星律");
        removed.put("phantom_step", "幻身步");
        int level = 3;
        for (final java.util.Map.Entry<String, String> entry : removed.entrySet()) {
            final String id = entry.getKey();
            assertNull(config.skill(id), id + " 拿掉了");
            assertTrue(config.retiredSkill(id), id);
            assertEquals(entry.getValue(), config.retiredSkillName(id), "通知用的名稱");
            // 存在物品裡的舊寶石（插了輔助、開過孔）照樣讀得出來，換成同等級的未切割技能寶石
            final GemData old = GemData.decode(GemData.skill(new SkillLoadout.Entry(id, level, 4, List.of("pierce", "", "chain"))).encode()).orElseThrow();
            assertEquals(GemData.uncutSkill(level), old.removedSkillReplacement(config), id);
            level += 2;
        }
        assertNull(config.skill("proc_cold_snap_ground"), "霜綻的冰地（隱藏技能）一起拿掉");
        assertNull(config.skill("proc_phantom_decoy"), "幻身步的殘影（隱藏技能）一起拿掉");
        // 2026-10-26：矢幕、爆破矢、焚燼併入別的技能（merged，見下一個測試）；這次之前列在留著的
        for (final String kept : List.of("lingering_illusion", "summon_mount", "linked_volley", "blast_arrow", "flame_stream")) {
            assertTrue(config.skill(kept) != null && !config.retiredSkill(kept), kept + " 留著");
        }
        assertEquals("some_old_skill", config.retiredSkillName("some_old_skill"), "沒列在 retired 的顯示代號");
        assertNull(GemData.skill(new SkillLoadout.Entry("fireball", 5)).removedSkillReplacement(config), "還在的技能不換");
        assertNull(GemData.uncutSkill(5).removedSkillReplacement(config));
        assertNull(GemData.support("pierce").removedSkillReplacement(config));
    }

    /** 2026-10-26：照站長併入別的技能（skills.yml 的 merged）：舊寶石直接換成併入的技能寶石，等級、孔、品質、腐化、輔助都保留。 */
    @Test
    void mergedSkillGemsBecomeTheSkillTheyMergedInto() {
        final java.util.Map<String, String[]> merged = new java.util.LinkedHashMap<>();
        merged.put("arrow_barrage", new String[] {"linked_volley", "矢幕已經併入連珠"});
        merged.put("charged_shot", new String[] {"blast_arrow", "爆破矢已經併入爆焰矢"});
        merged.put("scorch_breath", new String[] {"flame_stream", "焚燼已經併入焚流"});
        int level = 7;
        for (final java.util.Map.Entry<String, String[]> entry : merged.entrySet()) {
            final String id = entry.getKey();
            final String into = entry.getValue()[0];
            assertNull(config.skill(id), id + " 定義刪掉了");
            assertFalse(config.retiredSkill(id), id + " 不是換成未切割寶石");
            assertEquals(into, config.mergedInto(id));
            assertEquals(entry.getValue()[1], config.mergeNotice(id), "通知");
            // 存在物品裡的舊寶石：插了輔助、開過孔、有品質、腐化過
            final SkillLoadout.Entry oldEntry = new SkillLoadout.Entry(id, level, 4, List.of("pierce", "", "chain~"), null, null, true, 13);
            final GemData old = GemData.decode(GemData.skill(oldEntry).encode()).orElseThrow();
            final GemData replaced = old.removedSkillReplacement(config);
            assertEquals(GemData.Kind.SKILL, replaced.kind(), id);
            final SkillLoadout.Entry now = replaced.entry();
            assertEquals(into, now.skillId());
            assertEquals(level, now.level(), "等級保留");
            assertEquals(4, now.sockets(), "開過的孔保留");
            assertEquals(13, now.quality(), "品質保留");
            assertTrue(now.corrupted(), "腐化保留");
            assertEquals(List.of("pierce", "", "chain"), List.of(now.support(0), now.support(1), now.support(2)), "輔助保留");
            assertTrue(now.resonant(2), "共鳴保留");
            // 放在宿主寶石第 1 孔的也換
            final SkillLoadout.Entry host = new SkillLoadout.Entry("fireball", 5).withInner(new SkillLoadout.Entry(id, level));
            assertEquals(into, config.merge(host).inner().skillId(), "宿主第 1 孔裡的");
            assertEquals("fireball", config.merge(host).skillId());
            level += 3;
        }
        assertNull(config.mergedInto("fireball"));
        assertNull(config.merge(new SkillLoadout.Entry("fireball", 5)), "沒併入的不換");
        assertNull(config.mergeNotice("frost_snap"), "換成未切割寶石的不算併入");
    }

    @Test
    void uncutGemsDropByMonsterLevel() {
        final Random random = new Random(5);
        final int max = config.uncutLevelFor(30);
        // 2026-10-26：需求等級照 PoE2 之後，掉落改看 uncut_drop_levels（30 級的怪最高掉 11 級；30 級的角色只能用到 8 級）
        assertEquals(11, max);
        assertEquals(8, config.maxLevelFor(30));
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
        // 2026-10-27 照 PoE2 0.5 的寶石階級（poe2db Uncut_Skill_Gem；tools/apply_poe2_gem_tiers.py）：第 1 階的技能換了一批
        //   （這次之前 escape_shot, raise_guard, explosive_bolt, rapid_fire, tempest_flurry, lightning_spear, fireball, spark, raise_zombie,
        //   armour_piercing_bolt, frostbolt, essence_drain, bear_maul, wolf_shred, lunar_assault, wyvern_rend, disengage, parry, frozen_locus,
        //   lightning_rod, fragmentation_rounds）
        //   2026-10-30 照 PoE2：架盾、招架變成盾牌、圓盾賦予（沒有寶石），從第 1 階拿掉
        assertEquals(List.of("earthquake", "boneshatter", "lightning_arrow", "escape_shot", "permafrost_bolts", "explosive_grenade",
                "killing_palm", "whirling_slash", "frost_nova", "spark", "flame_wall", "armour_piercing_bolt", "rolling_slam", "skeletal_archers",
                "venom_burst_arrow", "gale_spear", "frost_mine", "bone_spikes", "falling_thunder", "glacial_cascade", "explosive_spear", "contagion",
                "bear_maul", "bear_slam", "wolf_shred", "lunar_assault", "wyvern_rend", "volcano", "entangle", "disengage", "frozen_locus",
                "lightning_rod", "fragmentation_rounds"),
                config.engravable(1).stream().map(SkillDefinition::id).toList(), "Lv.1 的未切割寶石能雕的技能（第 1 階的技能）");
        assertTrue(config.engravable(13).size() < config.visible().size());
        // 2026-10：巔峰 Boss 的專屬技能寶石（source: awakener）雕不出來，其他的 Lv.14 起全部都能雕（最高第 14 階）
        final List<SkillDefinition> exclusive = config.skillsFrom("awakener");
        assertEquals(List.of("void_meteor", "oblivion_ray", "storm_labyrinth", "fourfold_ray", "rift_slash", "starfall_volley"),
                exclusive.stream().map(SkillDefinition::id).toList());
        // 2026-10：星骸之海的淵鯨的專屬（source: abyss_whale）也雕不出來
        assertEquals(List.of("tide_call", "abyssal_pull"), config.skillsFrom("abyss_whale").stream().map(SkillDefinition::id).toList()); // 2026-10-18：＋鯨吞漩渦
        assertFalse(config.skill("tide_call").engravable());
        final int granted = config.skillsFrom("weapon").size(); // 2026-10-26：法杖、長杖、權杖賦予的（27 個＋骸骨戰士）沒有寶石
        assertEquals(33 + 1 + 1 + 1 + 2, granted); // 2026-10-30 照 PoE2：＋盾牌賦予的架盾、圓盾賦予的招架；2026-10-28：＋新生之儀（綺夢賦予）；2026-10-27 照 PoE2：＋每把矛賦予的擲矛；2026-10-26：＋咒擲、封釋、雷霆共鳴、折光之鏡、鴉群密謀；2026-10-28：＋烈陽珠（PoE2 0.5 只有長杖賦予）
        final int ascendancy = config.skillsFrom("ascendancy").size(); // 2026-10-26 照 PoE2：昇華天賦賦予的（看得到的 44 個）沒有寶石
        assertEquals(44 + 7 + 1 + 1, ascendancy); // 2026-10-29 照 PoE2：＋克拉里的殘酷、先祖之魂（面板上看得到）；2026-10-27 照 PoE2 0.5：＋靈狩者、鳴鐘武師、淵井骸主賦予的 7 個
        assertEquals(config.visible().size() - exclusive.size() - 2 - 14 - granted - ascendancy, config.engravable(14).size(), "Lv.14 起全部都能雕（專屬的除外；2026-10-18：事件巔峰的 14 顆、淵鯨的 2 顆也除外；2026-10-26：武器賦予的、昇華天賦賦予的也除外）");
        assertEquals(config.engravable(14).size(), config.engravable(20).size());
        for (final SkillDefinition skill : exclusive) {
            assertFalse(skill.engravable(), skill.id());
            assertFalse(config.engravable(20).contains(skill), skill.id() + "：未切割技能寶石雕不出來");
        }
    }

    /** 2026-10-30 照 PoE2：掘骸也吃召喚物輔助（只給骨構體），原作不能輔助掘骸的 8 顆、只給常駐召喚物的 3 顆掛不上。 */
    @Test
    void unearthTakesMinionSupportsLikePoe2() {
        final SkillDefinition unearth = config.skill("bone_spikes");
        for (final String ok : List.of("minion_damage", "meat_shield", "muster", "controlled_destruction")) {
            assertNull(config.support(ok).refusal(unearth), ok);
        }
        for (final String no : List.of("hinder", "perpetual_charge", "intense_agony", "persistent_ground", "static_shocks",
                "charge_profusion", "heightened_charges", "empowered_sparks", "essence_harvest", "deathmarch", "giant_minions")) {
            assertNotNull(config.support(no).refusal(unearth), no);
        }
        final SkillBuild build = SkillBuild.of(config, unearth, 20, 90, false, List.of(config.support("minion_damage")), Set.of(),
                new tw.exilecore.stats.StatSheet());
        assertTrue(build.profile().local().stream().filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.DAMAGE))
                .allMatch(m -> m.tags().contains(tw.exilecore.stats.Tags.MINION)), "骨刺吃不到召喚物輔助的傷害");
        assertEquals(List.of("minion_damage"), SkillBuild.of(config, config.skill("unearth_construct"), 20, 90, false, build.supports(), Set.of(),
                new tw.exilecore.stats.StatSheet()).supports().stream().map(SupportDefinition::id).toList(), "骨構體吃得到");
    }
}
