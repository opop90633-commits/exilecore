package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2（PoB2 SummonSandDjinnPlayer／SummonWaterDjinnPlayer／SummonFireDjinnPlayer 的 minionSkillTypes、
 * CommandSandDjinnKnifeThrowPlayer… 的 CommandsMinions）：沙靈使的巨靈——輔助掛在巨靈的召喚技能上、照巨靈招式的類型，效果給巨靈的指令；
 * 指令技能本身只吃指令類的輔助。
 */
class DjinnSupportsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data")); // 2026-11-01：殘酷、裁決的品質
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, "沒有輔助 " + id);
        return support;
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertNotNull(skill, "沒有技能 " + id);
        return skill;
    }

    @Test
    void djinnMinionTypesFollowPob() {
        assertEquals(Set.of(Tags.SPELL, Tags.AREA, Tags.PHYSICAL, Tags.PROJECTILE, Tags.COOLDOWN), skill("foul_sand_spirit").summon().minionTags());
        assertEquals(Set.of(Tags.SPELL, Tags.AREA, Tags.COLD, Tags.COOLDOWN, Tags.DURATION), skill("final_mirage_spirit").summon().minionTags());
        assertEquals(Set.of(Tags.SPELL, Tags.ATTACK, Tags.MELEE, Tags.AREA, Tags.FIRE, Tags.PROJECTILE, Tags.DURATION, Tags.COOLDOWN),
                skill("flame_blade_spirit").summon().minionTags());
        assertEquals(Set.of(Tags.SPELL, Tags.AREA, Tags.PHYSICAL), MinionTypes.of(skill(DjinnCommands.VENOM)), "屍甲蟲（CorpseBeetlesPlayer）");
        for (final String id : List.of("foul_sand_spirit", "final_mirage_spirit", "flame_blade_spirit")) {
            assertFalse(skill(id).tags().contains(Tags.MELEE), id + "：巨靈不算近戰（原作只有 Minion、Persistent、CommandableMinion）");
            assertNotNull(MinionTypes.line(skill(id)), id);
        }
        assertTrue(skill("asc_ruzhan_zealotry").tags().containsAll(Set.of(Tags.ATTACK, Tags.MELEE, Tags.COOLDOWN)), "FlameSliceFireDjinn");
    }

    @Test
    void supportsOnTheDjinnFollowItsSkills() {
        // 範圍、物理、法術傷害的：三位都有範圍
        for (final String id : List.of("foul_sand_spirit", "final_mirage_spirit", "flame_blade_spirit")) {
            assertNull(support("condense_power").refusal(skill(id)), "凝聚 → " + id);
            assertNull(support("zone_expand").refusal(skill(id)), "擴域 → " + id);
            assertNull(support("bloodthirst_summon").refusal(skill(id)), "嗜殺召喚 → " + id);
            // 2026-11-01 照 PoE2：巨靈接到指令後用自己的施法速度放招式（DjinnCommands.djinnCastTicks）：疾詠掛在巨靈身上讓牠出手更快
            //   （這次之前指令整個照你的施法速度，疾詠掛不上）
            assertNull(support("quick_chant").refusal(skill(id)), "疾詠 → " + id);
        }
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("quick_chant").refusal(skill(DjinnCommands.VENOM)),
                "屍甲蟲跑到敵人身邊就炸，沒有施法動作");
        assertNull(support("savage_cruelty").refusal(skill("foul_sand_spirit")), "兇殘：克拉里的物理法術");
        // 持續：只有娜瑟菈、羅贊的招式有 Duration
        assertNull(support("long_stay").refusal(skill("final_mirage_spirit")));
        assertNull(support("long_stay").refusal(skill("flame_blade_spirit")));
        assertNotNull(support("long_stay").refusal(skill("foul_sand_spirit")), "克拉里的招式原作沒有 Duration");
        // 近戰：只有羅贊（狂熱斬擊）
        assertNull(support("sinking_hammer").refusal(skill("flame_blade_spirit")));
        assertEquals("要近戰技能", support("sinking_hammer").refusal(skill("foul_sand_spirit")));
        assertEquals("要近戰技能", support("sinking_hammer").refusal(skill("final_mirage_spirit")));
        // 投射物的機制：羅贊的怒焰、機關，克拉里的殘酷（飛刀）真的會射；娜瑟菈沒有投射物
        assertNull(support("pierce").refusal(skill("flame_blade_spirit")));
        assertEquals("要投射物技能", support("pierce").refusal(skill("final_mirage_spirit")));
        // 2026-11-01 照 PoE2 Kelari's Brutality：多射、歧射、投射物速度給飛刀；飛刀本來就穿透所有目標（always_pierce）、不能連鎖（CannotChain），
        //   多穿透、連鎖掛在克拉里身上沒有作用（這次之前投射物的輔助全部掛不上：「召喚物沒有真的射出投射物」）
        assertNull(support("light_split").refusal(skill("foul_sand_spirit")), "多重投射");
        assertNull(support("split_shot").refusal(skill("foul_sand_spirit")), "歧射");
        assertNull(support("swift_bolt_iii").refusal(skill("foul_sand_spirit")), "疾矢 III");
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("pierce").refusal(skill("foul_sand_spirit")), "貫穿：飛刀本來就穿透所有目標");
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("chain").refusal(skill("foul_sand_spirit")), "連環：飛刀不能連鎖");
        assertNull(support("chain").refusal(skill("flame_blade_spirit")), "羅贊的怒焰可以連鎖");
        // 連瀑：只有羅贊的清算是 Cascadable（MeteorFireDjinn）
        assertNull(support("linked_falls").refusal(skill("flame_blade_spirit")));
        assertEquals("召喚物的技能吃不到這顆輔助的效果", support("linked_falls").refusal(skill("foul_sand_spirit")));
        // 主人自己的機制照舊不行
        assertEquals("召喚物技能不行", support("twin_chant").refusal(skill("foul_sand_spirit")));
        // 屍甲蟲：照惡言的召喚物類型（法術、範圍、物理）
        assertNull(support("savage_cruelty").refusal(skill(DjinnCommands.VENOM)));
        assertNull(support("condense_power").refusal(skill(DjinnCommands.VENOM)));
        assertEquals("要投射物技能", support("pierce").refusal(skill(DjinnCommands.VENOM)));
    }

    @Test
    void commandSkillsOnlyTakeCommandSupports() {
        final String no = "指令技能只能掛指令、消耗、等級這類的輔助（傷害、範圍、投射物這些掛在巨靈的召喚技能上）";
        for (final String id : DjinnCommands.COMMANDS) {
            assertNotNull(config.skill(id), id);
            assertEquals(no, support("condense_power").refusal(skill(id)), "凝聚 → " + id);
            assertEquals(no, support("savage_cruelty").refusal(skill(id)), "兇殘 → " + id);
            assertEquals(no, support("pierce").refusal(skill(id)), "貫穿 → " + id);
            assertEquals(no, support("quick_chant").refusal(skill(id)), "疾詠 → " + id);
        }
        assertNull(support("bone_command").refusal(skill("sand_spirit_trick")), "骸令（Bidding：CommandsMinions）");
        assertNull(support("bone_command_ii").refusal(skill("blade_spirit_wrath_fire")));
        assertNull(DjinnCommands.commandRefusal("cost_saving", "mirage_spirit_freeze"), "節流（Efficiency：沒有要求的類型）");
        assertNull(DjinnCommands.commandRefusal("condense_power", "foul_sand_spirit"), "召喚技能本身不是指令");
        assertNull(DjinnCommands.commandRefusal("condense_power", "soul_sever_palm"));
    }

    @Test
    void hitsThatTakeTheDjinnGemsSupports() {
        for (final String id : List.of("sand_spirit_trick", "asc_kelari_clone", "asc_kelari_blade", "mirage_spirit_freeze", "blade_spirit_settle",
                "asc_ruzhan_wave", "asc_ruzhan_tornado", "asc_ruzhan_brand", "asc_ruzhan_zealotry", "blade_spirit_device.tornado")) {
            assertTrue(DjinnCommands.gemSupported(id), id);
        }
        assertFalse(DjinnCommands.gemSupported(DjinnCommands.BEETLE), "屍甲蟲吃惡言上的輔助，不吃克拉里召喚技能上的");
        assertFalse(DjinnCommands.gemSupported(DjinnCommands.BEETLE + ".x"));
        assertFalse(DjinnCommands.gemSupported("foul_sand_spirit"));
        assertFalse(DjinnCommands.gemSupported("soul_sever_palm"));
    }

    /** 掛在克拉里召喚技能上的嗜殺召喚（召喚物更多 30% 傷害）讓克拉里的詭計更痛（跟 MinionService 的 effective 一樣疊）。 */
    @Test
    void minionDamageSupportOnTheDjinnRaisesItsCommand() {
        final SupportDefinition bloodthirst = support("bloodthirst_summon");
        final SkillBuild kelari = SkillBuild.of(config, skill("foul_sand_spirit"), 20, 100, false, List.of(bloodthirst), Set.of(), new StatSheet());
        assertEquals(List.of(bloodthirst), kelari.supports(), "掛得上");
        final StatSheet base = new StatSheet();
        final StatSheet effective = base.overlay(MinionSheets.stripMinion(MinionSheets.profile(kelari).local()));
        final SkillBuild trick = SkillBuild.of(config, skill("sand_spirit_trick"), 20, 100, false, List.of(), Set.of(), effective);
        final double plain = DamageCalculator.expectedOutgoing(base, trick.profile()).total();
        final double boosted = DamageCalculator.expectedOutgoing(effective, trick.profile()).total();
        assertTrue(plain > 0);
        assertEquals(plain * 1.3, boosted, plain * 1e-6, "嗜殺召喚：更多 30%");

        // 範圍：擴域掛在羅贊身上，羅贊的清算（照羅贊的屬性表算）範圍變大
        final SkillBuild ruzhan = SkillBuild.of(config, skill("flame_blade_spirit"), 20, 100, false, List.of(support("zone_expand")), Set.of(),
                new StatSheet());
        final StatSheet ruzhanSheet = new StatSheet().overlay(MinionSheets.stripMinion(MinionSheets.profile(ruzhan).local()));
        final SkillBuild settle = SkillBuild.of(config, skill("blade_spirit_settle"), 20, 100, false, List.of(), Set.of(), ruzhanSheet);
        final SkillBuild settlePlain = SkillBuild.of(config, skill("blade_spirit_settle"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertTrue(settle.areaScale() > settlePlain.areaScale(), "範圍增加");
    }

    /** 2026-11-01 照 PoE2（PoB2 Command*Player castTime 0.4；minion.lua 巨靈招式的 castTime）：下指令 0.4 秒照你的施法速度，巨靈照自己的施法速度出手。 */
    @Test
    void commandIsQuickAndTheDjinnCastsAtItsOwnSpeed() {
        assertEquals(8.0, DjinnCommands.commandTicks(1.0), 1e-9, "0.4 秒");
        assertEquals(8.0 / 1.25, DjinnCommands.commandTicks(1.25), 1e-9, "你的施法速度增加 25%");
        assertEquals(16, DjinnCommands.djinnCastTicks(0.8, 1.0), "克拉里的欺瞞、殘酷 0.8 秒");
        assertEquals(46, DjinnCommands.djinnCastTicks(2.3, 1.0), "羅贊的清算、怒焰 2.3 秒");
        assertEquals(40, DjinnCommands.djinnCastTicks(2.3, 1.15), "巨靈的施法速度增加 15%");
        assertEquals(0.6, DjinnCommands.djinnCastTime("flame_blade_spirit", 0.75), 1e-9, "羅贊的烙印 LivingBombFireDjinn");
        assertEquals(2.0, DjinnCommands.djinnCastTime("final_mirage_spirit", 0.75), 1e-9, "娜瑟菈的擁抱 WaterBubbleWaterDjinn");
        assertEquals(1.2, DjinnCommands.djinnCastTime("mirage_spirit_freeze", skill("mirage_spirit_freeze").castTime()), 1e-9,
                "ChilledGroundBurstWaterDjinn");
        for (final String id : DjinnCommands.COMMANDS) {
            assertTrue(DjinnCommands.isCommand(id), id);
            assertNotNull(DjinnCommands.commandedDjinn(id), id);
        }
        assertTrue(DjinnCommands.isCommand("flame_blade_spirit"));
        assertEquals("final_mirage_spirit", DjinnCommands.commandedDjinn("final_mirage_spirit"));
        assertFalse(DjinnCommands.isCommand("foul_sand_spirit"), "珂芮絲自帶的殘酷是另外一個技能");
        assertFalse(DjinnCommands.isCommand("soul_sever_palm"));

        // 疾詠掛在克拉里身上（跟 MinionService 的 effective 一樣疊）：克拉里放欺瞞更快；你自己的施法速度不影響巨靈
        final SkillBuild kelari = SkillBuild.of(config, skill("foul_sand_spirit"), 20, 100, false, List.of(support("quick_chant")), Set.of(),
                new StatSheet());
        final StatSheet kelariSheet = new StatSheet().overlay(MinionSheets.stripMinion(MinionSheets.profile(kelari).local()));
        final SkillDefinition trick = skill("sand_spirit_trick");
        assertEquals(16, DjinnCommands.djinnCastTicks(new StatSheet(), trick));
        assertTrue(DjinnCommands.djinnCastTicks(kelariSheet, trick) < 16, "疾詠：施法速度增加");
        // 輕身掛在指令技能上：你下指令那一下更快（巨靈的施法不變）
        final SkillBuild order = SkillBuild.of(config, trick, 20, 100, false, List.of(support("light_body")), Set.of(), new StatSheet());
        assertTrue(DjinnCommands.commandTicks(order, new StatSheet()) < 8.0, "輕身：施法速度增加 6%");
    }

    /** 2026-11-01 照 PoE2 Bidding I／II（support_command_skill_damage_+%_final：只算 Command Skills）。 */
    @Test
    void biddingOnlyReachesCommands() {
        for (final String id : List.of("bone_command", "bone_command_ii")) {
            for (final tw.exilecore.stats.Modifier modifier : support(id).modifiers()) {
                assertTrue(modifier.tags().contains(Commandables.TAG), id + "：" + modifier.stat() + " 只給指令");
            }
        }
        // 召喚物的招式不帶 command（召喚技能的「可以下指令」標籤不算）：骷髏的普攻、自己的技能吃不到骸令
        final SkillBuild skeletons = SkillBuild.of(config, skill("summon_skeletons"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertFalse(MinionSheets.profile(skeletons).tags().contains(Commandables.TAG));
        // 骸令掛在羅贊身上：羅贊的清算（指令）更多 30%，狂熱斬擊（不是指令）不變
        final SkillBuild ruzhan = SkillBuild.of(config, skill("flame_blade_spirit"), 20, 100, false, List.of(support("bone_command_ii")), Set.of(),
                new StatSheet());
        assertEquals(List.of(support("bone_command_ii")), ruzhan.supports(), "掛得上");
        final StatSheet base = new StatSheet();
        final StatSheet bidding = base.overlay(MinionSheets.stripMinion(MinionSheets.profile(ruzhan).local()));
        final SkillBuild settle = SkillBuild.of(config, skill("blade_spirit_settle"), 20, 100, false, List.of(), Set.of(), base);
        final double plain = DamageCalculator.expectedOutgoing(base, settle.profile()).total();
        assertTrue(plain > 0);
        assertEquals(plain * 1.3, DamageCalculator.expectedOutgoing(bidding, settle.profile()).total(), plain * 1e-6, "清算：更多 30%");
        final SkillBuild wave = SkillBuild.of(config, skill("asc_ruzhan_wave"), 20, 100, false, List.of(), Set.of(), base);
        final double wavePlain = DamageCalculator.expectedOutgoing(base, wave.profile()).total();
        assertEquals(wavePlain * 1.3, DamageCalculator.expectedOutgoing(bidding, wave.profile()).total(), wavePlain * 1e-6, "清算的熔岩浪也是指令");
        final SkillBuild zealotry = SkillBuild.of(config, skill("asc_ruzhan_zealotry"), 20, 100, false, List.of(), Set.of(), base);
        assertEquals(DamageCalculator.expectedOutgoing(base, zealotry.profile()).total(),
                DamageCalculator.expectedOutgoing(bidding, zealotry.profile()).total(), 1e-6, "狂熱斬擊不是指令，骸令吃不到");
    }

    /** 2026-11-01 照 PoE2 Kelari's Brutality（KnifeThrowSandDjinn）：12 把真的會飛的飛刀，每秒 25 公尺，散在 3 公尺內。 */
    @Test
    void brutalityThrowsRealKnives() {
        final SkillDefinition brutality = skill(DjinnCommands.BRUTALITY);
        assertEquals(Mechanic.PROJECTILE, brutality.mechanic());
        final SkillBuild plain = SkillBuild.of(config, brutality, 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals(DjinnCommands.KNIVES, plain.projectiles(), "12 把");
        assertEquals(1.25, plain.speed(), 1e-9, "每秒 25 公尺");
        // 多重投射掛在克拉里身上：多擲 2 把
        final SkillBuild kelari = SkillBuild.of(config, skill("foul_sand_spirit"), 20, 100, false, List.of(support("light_split")), Set.of(),
                new StatSheet());
        final StatSheet kelariSheet = new StatSheet().overlay(MinionSheets.stripMinion(MinionSheets.profile(kelari).local()));
        final SkillBuild more = SkillBuild.of(config, brutality, 20, 100, false, List.of(), Set.of(), kelariSheet);
        assertEquals(DjinnCommands.KNIVES + 2, more.projectiles(), "多重投射：多 2 把");
        assertTrue(skill(DjinnCommands.BRUTALITY_BLADE).tags().contains(Commandables.TAG), "爆出來的刀刃也是指令");

        final java.util.Random random = new java.util.Random(7);
        final List<double[]> offsets = DjinnCommands.knifeOffsets(12, 3, random::nextDouble);
        assertEquals(12, offsets.size());
        assertEquals(0, offsets.getFirst()[0], 1e-9, "第一把落在目標點");
        assertEquals(0, offsets.getFirst()[1], 1e-9);
        for (final double[] offset : offsets) {
            assertTrue(Math.hypot(offset[0], offset[1]) <= 3 + 1e-9, "3 公尺內");
        }
    }

    /** 2026-11-01 照 PoE2 Kelari's Brutality（KnifeThrowSandDjinn qualityStats djinn_knife_throw_number_of_knives_created 0.1）：品質多 0～2 把飛刀。 */
    @Test
    void brutalityQualityAddsKnives() {
        final SkillDefinition brutality = skill(DjinnCommands.BRUTALITY);
        final List<tw.exilecore.stats.Modifier> quality = SkillService.qualityModifiers(brutality, 20);
        assertEquals(2, quality.stream().filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.SKILL_PROJECTILES))
                .mapToDouble(tw.exilecore.stats.Modifier::value).sum(), 1e-9, "20% 品質（昇華賦予的技能固定 20%）+2 把");
        final SkillBuild knives = SkillBuild.of(config, brutality, 20, 100, false, List.of(), Set.of(), new StatSheet().addAll(quality));
        assertEquals(DjinnCommands.KNIVES + 2, knives.projectiles(), "14 把（kelariKnives 照 build.projectiles() 擲）");
        assertEquals("品質 +20%：多擲 2 把飛刀", SkillService.qualityText(brutality, 20));
        final List<tw.exilecore.stats.Modifier> judgment = SkillService.qualityModifiers(skill(DjinnCommands.JUDGMENT), 20);
        assertEquals(2, judgment.stream().filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.CRIT_WEAKNESS_ON_HIT))
                .mapToDouble(tw.exilecore.stats.Modifier::value).sum(), 1e-9, "裁決：多 2 層暴擊弱點（HandSlamSandDjinn qualityStats 0.1）");
    }

    /**
     * 2026-11-01 照 PoE2 Kelari's Judgment（HandSlamSandDjinn：Channel、GainsStages、castTime 0.5、最多 4 段、channel_start_lock_cancelling_of_cast_time_% 100、
     * 每多一段更多 125% 傷害、範圍更多 25%、範圍 −20%～+20%；poe2db 每秒魔耗）：按住引導的指令。
     */
    @Test
    void judgmentIsAChannelledCommand() {
        final SkillDefinition judgment = skill(DjinnCommands.JUDGMENT);
        assertTrue(DjinnCommands.channelled(judgment.id()));
        assertFalse(DjinnCommands.channelled(DjinnCommands.BRUTALITY));
        assertTrue(judgment.tags().contains("channelling"));
        assertEquals("引導", Signatures.chargeWord(judgment), "提示寫「引導」");
        final ChargeSpec spec = config.charge(judgment.id());
        assertNotNull(spec, "按住引導");
        assertEquals(ChargeSpec.Mode.RELEASE, spec.mode());
        assertTrue(spec.lockedStart());
        assertTrue(spec.drain(), "引導中逐秒扣魔力");
        assertEquals(4, spec.stages());
        assertEquals(0.5, spec.stageTime(), 1e-9);
        assertEquals(0.5, judgment.castTime(), 1e-9);
        assertEquals(4, judgment.mana().intAt(1), "poe2db 每秒 3.87");
        assertEquals(40, judgment.mana().intAt(20), "poe2db 每秒 39.52");
        // 第一段不能取消：最短引導一段（照珂芮絲的施法速度）
        assertEquals(10, spec.minTicks(1.0));
        assertEquals(8, spec.minTicks(1.25), "珂芮絲的施法速度增加 25%");
        // 引導完一段 1 層，最多 4 層（2 秒）
        assertEquals(1, spec.stagesAt(10, 1.0));
        assertEquals(1, spec.stagesAt(19, 1.0));
        assertEquals(2, spec.stagesAt(20, 1.0));
        assertEquals(4, spec.stagesAt(40, 1.0));
        assertEquals(4, spec.stagesAt(90, 1.0));
        assertEquals(40, spec.fullStageTicks(1.0), 1e-9);
        // 其他引導照舊（一開始就 1 層）
        assertFalse(config.charge("shattered_bone_darts").lockedStart());

        // 引導滿 4 段：更多 375% 傷害、範圍更多 75%
        final StatSheet plain = new StatSheet();
        final SkillBuild one = SkillBuild.of(config, judgment, 1, 100, false, List.of(), Set.of(), plain);
        final SkillBuild four = Signatures.released(one, spec, 4, false, plain, 2.0);
        final double base = DamageCalculator.expectedOutgoing(plain, one.profile()).total();
        assertTrue(base > 0);
        assertEquals(base * 4.75, DamageCalculator.expectedOutgoing(plain, four.profile()).total(), base * 1e-6);
        assertEquals(one.areaScale() * Math.sqrt(1.75), four.areaScale(), 1e-9);
        assertEquals(base, DamageCalculator.expectedOutgoing(plain, Signatures.released(one, spec, 1, false, plain, 0.5).profile()).total(),
                base * 1e-6, "1 段沒有加成");

        // 每次震波範圍 −20%～+20%
        assertEquals(0.8, DjinnCommands.judgmentAreaRoll(0), 1e-9);
        assertEquals(1.0, DjinnCommands.judgmentAreaRoll(0.5), 1e-9);
        assertEquals(1.2, DjinnCommands.judgmentAreaRoll(1), 1e-9);

        // 疾詠掛在珂芮絲身上：段數疊得更快（你自己的施法速度不算）
        final SkillBuild kelari = SkillBuild.of(config, skill("foul_sand_spirit"), 20, 100, false, List.of(support("quick_chant")), Set.of(),
                new StatSheet());
        final StatSheet kelariSheet = new StatSheet().overlay(MinionSheets.stripMinion(MinionSheets.profile(kelari).local()));
        assertEquals(1.0, DjinnCommands.djinnCastSpeed(null, judgment), 1e-9);
        assertTrue(DjinnCommands.djinnCastSpeed(kelariSheet, judgment) > 1.0);
    }

    /** 2026-11-01 照 PoE2：估計（面板、網頁的每秒傷害）一輪的時間跟施放時一樣：下指令＋巨靈施法；裁決照引導滿的時間。 */
    @Test
    void estimatesUseCommandPlusDjinnCast() {
        assertEquals(8 + 16, DjinnCommands.cycleTicks(false, 8, 16, 0), 1e-9, "殘酷：0.4 秒＋0.8 秒");
        assertEquals(40, DjinnCommands.cycleTicks(true, 8, 16, 40), 1e-9, "裁決：引導滿 2 秒（引導就是指令）");
        assertEquals(1, DjinnCommands.cycleTicks(true, 0, 0, 0), 1e-9);
    }
}
