package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2 Commandment（軍令；PoB2 SupportCommandment、SkillType.CommandableMinion）：掛得上的召喚物、
 * 指令冷卻回復速度（INC 相加，只有指令吃得到）、巨靈指令以外的攻擊更少 100%。
 */
class CommandablesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void armyOrderIsRetired() {
        // 2026-11-08 照站長：照 PoE2 拿掉軍令（玩家身上的直接刪掉，不給未切割輔助寶石）；引擎的 minion.army_order 還在；這次之前測掛得上哪些召喚物
        assertNull(config.support("army_order"));
        assertTrue(config.deletedSupport("army_order"));
        for (final String id : List.of(MinionAbilities.BRUTE, MinionAbilities.SNIPER, MinionAbilities.ARSONIST, MinionAbilities.STORM,
                MinionAbilities.FROST, MinionAbilities.REAVER, MinionAbilities.ARTILLERY, MinionAbilities.RAVENS,
                MinionAbilities.KELARI, MinionAbilities.NAVIRA, MinionAbilities.RUZHAN)) {
            assertTrue(Commandables.commandable(id), id);
        }
        for (final String id : List.of(MinionAbilities.WARRIOR, MinionAbilities.CLERIC, MinionAbilities.WOLVES, MinionAbilities.SPIRITS,
                MinionAbilities.SWARM, MinionAbilities.SPECTRE, MinionAbilities.MANIFEST, "sand_spirit_venom_word", "corpse_summon")) {
            assertTrue(!Commandables.commandable(id), id);
        }
    }

    @Test
    void commandStrikesForTheEstimate() {
        final tw.exilecore.stats.SkillProfile base = new tw.exilecore.stats.SkillProfile("summon_bone_heavy", Set.of(Tags.ATTACK, Tags.MELEE),
                1.0, null, null, java.util.Map.of(), List.of());
        final MinionService.CommandStrike roar = MinionService.commandStrike(MinionAbilities.BRUTE, base, 1.0, 20, 0, 40);
        assertEquals(40, roar.hits(), 1e-9, "40 個冰甲爆裂點：每個各打一下");
        assertTrue(roar.profile().tags().contains(Commandables.TAG));
        assertTrue(!roar.profile().tags().contains(Tags.ATTACK), "冰爆是法術");
        assertEquals(1, MinionService.commandStrike(MinionAbilities.BRUTE, base, 1.0, 20, 0, 1).hits(), 1e-9);
        assertEquals(MinionAbilities.VOLLEYS, MinionService.commandStrike(MinionAbilities.ARTILLERY, base, 1.0, 20, 100, 1).hits(), 1e-9);
        assertNull(MinionService.commandStrike(MinionAbilities.FROST, base, 1.0, 20, 0, 1), "冰甲不造成傷害");
        assertNull(MinionService.commandStrike(MinionAbilities.WARRIOR, base, 1.0, 20, 0, 1));
    }

    @Test
    void biddingFitsCommandableSkeletons() {
        // 2026-11-08 照站長「召喚骸骨重卒原作能吃骸令 II 嗎」：PoB2 Bidding 是 CommandableMinion 或 CommandsMinions，骷髏也掛得上
        for (final String skill : List.of(MinionAbilities.BRUTE, MinionAbilities.SNIPER, MinionAbilities.ARTILLERY)) {
            assertNull(config.support("bone_command_ii").refusal(config.skill(skill)), skill);
            assertNull(config.support("bone_command").refusal(config.skill(skill)), skill);
        }
        assertNotNull(config.support("bone_command_ii").refusal(config.skill(MinionAbilities.WARRIOR)), "骸骨戰士沒有指令");
        // 震地咆哮（指令）更多 30%
        final SkillBuild brute = SkillBuild.of(config, config.skill(MinionAbilities.BRUTE), 20, 100, false,
                List.of(config.support("bone_command_ii")), Set.of(), new StatSheet());
        assertEquals(List.of(config.support("bone_command_ii")), brute.supports(), "掛得上");
        final StatSheet plain = new StatSheet();
        final StatSheet bidding = plain.overlay(MinionSheets.stripMinion(MinionSheets.profile(brute).local()));
        final tw.exilecore.stats.SkillProfile base = new tw.exilecore.stats.SkillProfile("summon_bone_heavy", Set.of(Tags.ATTACK, Tags.MELEE),
                1.0, null, null, java.util.Map.of(), List.of());
        final MinionService.CommandStrike roar = MinionService.commandStrike(MinionAbilities.BRUTE, base, 1.0, 20, 0, 1);
        final double before = tw.exilecore.stats.DamageCalculator.expectedOutgoing(plain, roar.profile()).total();
        assertTrue(before > 0);
        assertEquals(before * 1.3, tw.exilecore.stats.DamageCalculator.expectedOutgoing(bidding, roar.profile()).total(), before * 1e-6);
        assertEquals(0.30, bidding.increased(StatKeys.COOLDOWN_RECOVERY, Commandables.CONTEXT), 1e-9, "指令的冷卻回復增加 30%");
    }

    @Test
    void commandCostTakesYourCostModifiers() {
        // 2026-11-08 照站長：魔偶符文「指令技能減少 30% 消耗」（skill.cost INCREASED -30% [command]）、全服魔力消耗倍率都吃得到；
        //   只給召喚物的（[minion]）不算你的
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.SKILL_COST, ModType.INCREASED, -0.30, Set.of(Commandables.TAG), "魔偶符文"));
        owner.add(new Modifier(StatKeys.SKILL_COST, ModType.INCREASED, -0.50, Set.of(Tags.MINION), "給召喚物的"));
        assertEquals((int) Math.round(360 * 0.7 * SkillConfig.manaCostScale()), MinionAbilities.Command.commandCost(360, owner));
        assertEquals((int) Math.round(360 * SkillConfig.manaCostScale()), MinionAbilities.Command.commandCost(360, new StatSheet()));
        assertEquals(360, MinionAbilities.Command.commandCost(360, null));
    }

    @Test
    void commandRecoveryAddsAndOnlyCommandsGetIt() {
        final StatSheet minion = new StatSheet();
        minion.add(Modifier.increased(StatKeys.COOLDOWN_RECOVERY, 0.20, "召喚物的冷卻回復速度"));
        minion.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.INCREASED, 0.25, Commandables.CONTEXT, "指令技能冷卻"));
        minion.addAll(Commandables.minionModifiers(MinionAbilities.BRUTE, true));
        assertEquals(0.20 + 0.25 + 1.0, Commandables.recovery(minion), 1e-9, "INC 相加：20% ＋ 25% ＋ 軍令 100%");
        assertEquals(0.20, minion.increased(StatKeys.COOLDOWN_RECOVERY, Set.of()), 1e-9, "召喚物自己的其他招式只吃一般的");
        // 震地咆哮 8 秒 ÷ (1 ＋ 1.45) = 3.27 秒
        assertEquals(2.45, Commandables.rate(minion), 1e-9);
        assertEquals(Math.round(8 * 20 / 2.45), Commandables.readyTicks(8, 1.0, Commandables.rate(minion)));
        assertEquals(80, Commandables.readyTicks(8, 1.0, 2.0), "只有軍令：冷卻減半");
        minion.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.MORE, 1.0, Commandables.CONTEXT, "更多"));
        assertEquals(4.9, Commandables.rate(minion), 1e-9, "更多的另外乘");
        assertEquals(0.0, Commandables.recovery(new StatSheet().addAll(Commandables.minionModifiers(MinionAbilities.BRUTE, false))), 1e-9);
        assertTrue(Commandables.minionModifiers(MinionAbilities.WARRIOR, true).isEmpty(), "不能下指令的不給");
    }

    @Test
    void djinnAttacksAreMutedButCommandsAreNot() {
        final StatSheet ruzhan = new StatSheet().addAll(Commandables.minionModifiers(MinionAbilities.RUZHAN, true));
        assertEquals(0.0, ruzhan.more(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.MELEE, Tags.AREA, Tags.FIRE)), 1e-9, "狂熱斬擊（攻擊）不造成傷害");
        assertEquals(1.0, ruzhan.more(StatKeys.DAMAGE, Set.of(Tags.SPELL, Tags.AREA, Tags.FIRE, Commandables.TAG)), 1e-9, "指令（法術）照打");
        assertEquals(1.0, ruzhan.increased(StatKeys.COOLDOWN_RECOVERY, Set.of(Tags.SPELL, Tags.FIRE, Commandables.TAG)), 1e-9,
                "巨靈的指令技能（照巨靈的屬性表算）冷卻回復速度增加 100%");
        // 砲手、骷髏不放「攻擊更少」：牠們的指令（從天而降、毒氣箭）本身是攻擊，指令以外的招式在 MinionService 照技能描述判斷
        final StatSheet artillery = new StatSheet().addAll(Commandables.minionModifiers(MinionAbilities.ARTILLERY, true));
        assertEquals(1.0, artillery.more(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.AREA, Tags.PROJECTILE)), 1e-9);
    }

    @Test
    void ownerSideSheetOnlyTakesMinionModifiers() {
        final StatSheet owner = new StatSheet();
        owner.add(Modifier.increased(StatKeys.COOLDOWN_RECOVERY, 0.30, "你自己的冷卻回復速度"));
        owner.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.INCREASED, 0.20, Set.of(Tags.MINION, Commandables.TAG), "指令技能冷卻"));
        owner.add(new Modifier(StatKeys.COOLDOWN_RECOVERY, ModType.INCREASED, 0.20, Set.of(Tags.MINION), "召喚物的冷卻回復速度"));
        final StatSheet ravens = Commandables.ownerSide(owner, List.of(), MinionAbilities.RAVENS, true);
        assertEquals(0.20 + 0.20 + 1.0, Commandables.recovery(ravens), 1e-9, "你自己的冷卻回復速度不給召喚物");
        assertEquals(0.40, Commandables.recovery(Commandables.ownerSide(owner, List.of(), MinionAbilities.RAVENS, false)), 1e-9);
    }

    @Test
    void treeCommandCooldownNodesOnlyReachCommands() throws IOException {
        final String tree = java.nio.file.Files.readString(Path.of("src/main/resources/data/passives/tree.json"));
        // 「增加 x% 召喚物的指令技能冷卻回復速度」的節點標 [minion, command]（一般的召喚物冷卻回復速度照舊 [minion]）
        assertTrue(tree.contains("\"stat\":\"cooldown.recovery\",\"type\":\"INCREASED\",\"value\":0.25,\"tags\":[\"minion\",\"command\"]"));
        assertTrue(!tree.contains("\"stat\":\"cooldown.recovery\",\"type\":\"INCREASED\",\"value\":0.25,\"tags\":[\"minion\"]}"));
    }
}
