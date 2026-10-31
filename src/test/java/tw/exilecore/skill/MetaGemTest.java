package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.charge.ChargeText;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主要寶石（2026-10，照 PoE2 的 Meta Gem）與新的戰星來源：設定讀得進來、插槽能插什麼、能量上限、說明文字。
 */
class MetaGemTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    @Test
    void metaGemsLoad() {
        final MetaSpec totem = config.meta("star_spell_totem");
        assertNotNull(totem);
        assertTrue(totem.totem());
        assertEquals(Mechanic.META, config.skill("star_spell_totem").mechanic());
        assertEquals(3, totem.charges());
        assertEquals(1, totem.totems().intAt(1));
        // 2026-10-26 照 PoE2 逐級：13 級一根、14 級兩根、23 級三根（這次之前 15 級起兩根）
        assertEquals(1, totem.totems().intAt(13), "13 級還是一根");
        assertEquals(2, totem.totems().intAt(14));
        assertEquals(3, totem.totems().intAt(23));
        // 2026-10-26 照 PoE2 0.5 的 Meta 寶石：Cast on Critical／Elemental Ailment／Dodge／Minion Death（滿了自動觸發）、
        //   Elemental／Reaper's／Barrier Invocation（祈喚：存能量、按下去觸發）；這次之前還有受創引星、斬殺引星（原作已經沒有）
        for (final String id : List.of("critical_guide_star", "mutation_guide_star", "roll_guide_star", "fallen_servant_star",
                "element_prayer_star", "scythe_prayer_star", "ward_prayer_star")) {
            final MetaSpec spec = config.meta(id);
            assertNotNull(spec, id);
            assertEquals(Mechanic.AURA, config.skill(id).mechanic(), id + " 是常駐的（保留精魂）");
            assertTrue(config.skill(id).aura().reserve() > 0, id);
            assertTrue(spec.energy() > 0, id);
            assertEquals(id.endsWith("_prayer_star"), spec.invocation(), id); // 2026-10-31：祈喚改名成 *_prayer_star
            assertEquals(spec.invocation(), config.skill(id).tags().contains(tw.exilecore.stats.TreeMechanicKeys.INVOCATION), id + "：invocation 標籤跟祈喚一致");
        }
        assertEquals(MetaSpec.Kind.CRIT, config.meta("critical_guide_star").kind());
        assertEquals(1, config.meta("critical_guide_star").energy(), 1e-9, "照 PoE2：每點強度 1 × 打掉異常門檻的 %");
        assertEquals(20, config.meta("critical_guide_star").lessDamage(), 1e-9);
        assertEquals(100, config.skill("critical_guide_star").aura().reserve(), 1e-9, "照 PoE2：暴擊時施放保留 100 精魂");
        assertEquals(30, config.skill("fallen_servant_star").aura().reserve(), 1e-9);
        assertEquals(2, config.meta("roll_guide_star").energy(), 1e-9, "照 PoE2：每格 2");
        assertEquals(60, config.skill("element_prayer_star").aura().reserve(), 1e-9);
        assertEquals(30, config.skill("scythe_prayer_star").aura().reserve(), 1e-9);
        assertEquals(30, config.skill("ward_prayer_star").aura().reserve(), 1e-9);
        assertEquals(500, config.meta("element_prayer_star").maxEnergy(), 1e-9);
        assertEquals(68, config.meta("element_prayer_star").energyMore().at(20), 1e-9);
        assertEquals(30, config.meta("scythe_prayer_star").energy(), 1e-9);
        assertEquals(MetaSpec.Kind.BARRIER, config.meta("ward_prayer_star").kind());
        assertEquals(0.1, config.meta("ward_prayer_star").energy(), 1e-9, "每 10 點護盾傷害 1 能量");
        assertTrue(config.meta("incant_hurl").invocation(), "咒擲（Spellslinger）也是祈喚");
        for (final String retired : List.of("meta_damage_taken", "meta_melee_kill")) {
            assertNull(config.skill(retired), retired);
            assertTrue(config.retiredSkill(retired), retired + " 身上的換成未切割技能寶石");
        }
        assertNull(config.support("boundless_energy_i"), "照站長 I／II／III 只留最高階：只有湧能 II");
        assertNull(config.meta("blazing_star_shell"));
        // 以前的觸發輔助、圖騰施法拿掉了，身上的換成未切割寶石
        for (final String retired : List.of("spell_totem", "trigger_crit", "trigger_hit_taken", "trigger_kill", "trigger_ailment",
                "cast_on_dodge", "cast_on_minion_death")) {
            assertNull(config.support(retired), retired);
        }
        assertEquals(3, config.retiredSupportTier("trigger_ailment"));
        assertEquals(2, config.retiredSupportTier("spell_totem"));
    }

    @Test
    void maxEnergyFollowsCastTime() {
        assertEquals(120, MetaSpec.maxEnergy(config.skill("blazing_star_shell")), 1e-9, "熾星彈 1.2 秒 = 120");
        assertTrue(MetaSpec.maxEnergy(config.skill("chain_thunder")) >= 10);
        assertEquals(1, MetaGems.power(MonsterRarity.NORMAL), 1e-9);
        assertEquals(20, MetaGems.power(MonsterRarity.UNIQUE), 1e-9, "2026-10-26 照 原作資料 Monster Power：傳奇 20（這次之前 10）");
    }

    /** 2026-10-26 照 PoE2：暴擊引星照打掉異常門檻的 %（最多 100）；祈喚一次觸發幾次。 */
    @Test
    void critPercentAndInvokeTimes() {
        assertEquals(10, MetaGems.critPercent(100, 1000), 1e-9);
        assertEquals(100, MetaGems.critPercent(5000, 1000), 1e-9, "打超過門檻最多算 100%");
        assertEquals(MetaGems.CRIT_PERCENT_FALLBACK, MetaGems.critPercent(100, 0), 1e-9);
        assertEquals(4, MetaGems.invokeTimes(500, 120, false), "500 能量、熾星彈 120 一次 → 4 次");
        assertEquals(1, MetaGems.invokeTimes(500, 120, true), "有冷卻的一次只放一發");
        assertEquals(0, MetaGems.invokeTimes(119, 120, false));
        assertEquals(MetaGems.MAX_INVOKES, MetaGems.invokeTimes(900, 10, false));
        final MetaSpec spec = config.meta("scythe_prayer_star");
        assertEquals(900, MetaGems.invocationMax(spec, new double[] {0, 80, 0}, new double[] {1, 1, 0}), 1e-9, "擴能 +80%");
    }

    /**
     * 2026-10-26 照 PoE2（poe2db Cast on Critical 等）：「Triggers all Socketed Spells and loses all Energy on reaching maximum Energy」——
     * 滿了失去全部能量（這次之前多的留著，最多 99%）；上限是所有插槽法術加起來；返能中了退還一半。
     */
    @Test
    void triggerLosesAllEnergyAndSumsSockets() {
        assertEquals(0, MetaGems.afterGain(250, 120, false), 1e-9, "滿了就歸零（不留溢出）");
        assertEquals(60, MetaGems.afterGain(250, 120, true), 1e-9, "返能：退還上限的一半");
        assertEquals(80, MetaGems.afterGain(80, 120, false), 1e-9, "沒滿照存");
        final List<SkillDefinition> two = List.of(config.skill("blazing_star_shell"), config.skill("blazing_star_shell"));
        assertEquals(240, MetaGems.totalMaxEnergy(two), 1e-9, "兩個插槽法術的上限加起來");
        final SkillDefinition fireball = config.skill("blazing_star_shell");
        final MetaGems.Socket socket = new MetaGems.Socket(0, new tw.exilecore.character.SkillLoadout.Entry("critical_guide_star", 1), config.meta("critical_guide_star"),
                List.of(new tw.exilecore.character.SkillLoadout.Entry("blazing_star_shell", 1), new tw.exilecore.character.SkillLoadout.Entry("chain_thunder", 1)),
                List.of(fireball, config.skill("chain_thunder")));
        assertEquals("critical_guide_star:blazing_star_shell+chain_thunder", socket.key());
        assertEquals(MetaSpec.maxEnergy(fireball) + MetaSpec.maxEnergy(config.skill("chain_thunder")), socket.maxEnergy(), 1e-9);
        assertEquals(fireball, socket.spell(), "第一個插槽法術");
    }

    /** 2026-10-26 照 PoE2：點燃照打掉異常門檻的 %、殞從引星乘召喚物強度、等級太低打折。 */
    @Test
    void minionPowerPenalisesUnderlevelledMinions() {
        assertEquals(1, SkillTriggers.minionPower(1, 70, 70), 1e-9);
        assertEquals(0.88, SkillTriggers.minionPower(0.88, 70, 75), 1e-9, "低 10 級以內不打折");
        assertEquals(0.5, SkillTriggers.minionPower(1, 50, 70), 1e-9, "低 20 級：多低的 10 級各少 5%");
        assertEquals(SkillTriggers.MINION_LEVEL_FLOOR, SkillTriggers.minionPower(1, 1, 80), 1e-9, "最少剩一成");
        assertTrue(SkillTriggers.minionPower(2, 70, 70) > SkillTriggers.minionPower(1, 70, 70), "強度高的給比較多");
    }

    /** 2026-10-26 照 PoE2 Fluke：觸發出來的技能回復 0～20% 消耗；骰運掛在引星上照宿主算才吃得到。 */
    @Test
    void flukeRecoversPartOfCost() {
        final SupportDefinition fluke = config.support("dice_luck");
        assertTrue(fluke.modifiers().stream().anyMatch(m -> m.stat().equals(tw.exilecore.stats.SupportKeys.FLUKE_REFUND) && m.value() == 20));
        assertEquals(0, SkillService.flukeRecovery(50, 20, 0), 1e-9);
        assertEquals(10, SkillService.flukeRecovery(50, 20, 1), 1e-9, "最多回 20%");
        assertEquals(5, SkillService.flukeRecovery(50, 20, 0.5), 1e-9);
        assertEquals(0, SkillService.flukeRecovery(0, 20, 1), 1e-9, "沒有消耗就沒有回復");
        // 2026-10-27 照 poe2db Fluke「recover … of cost on use」：主要寶石觸發的法術不花魔力，回復照實際付的（0）
        assertTrue(fluke.description().contains("實際付的消耗"), fluke.description());
    }

    /** 2026-10-27：木甲只算被擊中打掉的能量護盾（這次之前被打後 10 tick 內任何減少都算）。 */
    @Test
    void barkskinCountsOnlyHitEnergyShield() {
        assertEquals(0, PersistentBuffs.barkskinLoss(0, 500), 1e-9, "沒被打掉就不算");
        assertEquals(120, PersistentBuffs.barkskinLoss(120, 500), 1e-9);
        assertEquals(80, PersistentBuffs.barkskinLoss(120, 80), 1e-9, "最多到原本有的");
        assertEquals(120, PersistentBuffs.barkskinLoss(120, -1), 1e-9);
    }

    @Test
    void onlyCastableSpellsFitSockets() {
        final MetaSpec crit = config.meta("critical_guide_star");
        final MetaSpec totem = config.meta("star_spell_totem");
        assertNull(MetaGems.problem(crit, config.skill("blazing_star_shell")));
        assertNull(MetaGems.problem(totem, config.skill("blazing_star_shell")));
        assertNotNull(MetaGems.problem(crit, config.skill("spine_crush")), "攻擊不行");
        assertNotNull(MetaGems.problem(crit, config.skill("corpse_summon")), "召喚不行");
        assertNotNull(MetaGems.problem(crit, config.skill("winter_shell")), "光環不行");
        assertNotNull(MetaGems.problem(crit, config.skill("mutation_guide_star")), "主要寶石不能插主要寶石");
        assertNotNull(MetaGems.problem(totem, config.skill("nether_puppet")), "本來就是圖騰的不行");
        for (final SkillDefinition skill : config.all()) {
            if (skill.cooldown() > 0 && skill.type() == SkillType.SPELL) {
                assertNotNull(MetaGems.problem(totem, skill), skill.id() + " 有冷卻：法術圖騰不行");
            }
        }
    }

    @Test
    void newChargeStatsAreDescribedAndValid() {
        assertEquals("重暈敵人時 50% 機率獲得一顆磐星", ChargeText.describe("charge.event.stun_applied.endurance", 50, ""));
        assertEquals("撲殺敵人時獲得一顆怒星", ChargeText.describe("charge.event.cull.frenzy", 100, ""));
        assertEquals("獲得一顆銳星時 15% 機率多獲得一顆", ChargeText.describe("charge.extra.power", 15, ""));
        assertEquals("獲得戰星時 25% 機率多獲得一顆同種的", ChargeText.describe("charge.extra", 25, ""));
        assertTrue(ChargeText.describe("charge.periodic", 10, "").startsWith("每分鐘獲得 10 顆戰星（每 6 秒一顆"));
        assertEquals("冰凍敵人時給的戰星每 4 秒最多一次", ChargeText.describe("charge.event_cooldown.freeze", 4, ""));
        assertNull(ChargeText.chargeOf("charge.event.nope.frenzy"), "不認得的事件");
        for (final String stat : List.of("charge.event.dodge.frenzy", "charge.extra.endurance", "charge.extra", "charge.periodic",
                "charge.event_cooldown.shock")) {
            assertTrue(StatLines.knows(stat), stat);
            assertNull(StatLines.problem(new Modifier(stat, ModType.FLAT, 10, Set.of(), "test")), stat);
        }
        assertNotNull(StatLines.problem(new Modifier("charge.extra", ModType.INCREASED, 0.1, Set.of(), "test")), "要 FLAT");
        assertNotNull(config.support("full_stars"));
        assertTrue(config.skill("battle_spirit_rise").aura().effects().stream().anyMatch(e -> e.stat().equals("charge.event.freeze.frenzy")),
                "戰意昂揚照 PoE2 改成冰凍、感電、重暈時給怒星");
        assertNull(config.skill("star_cadence"), "2026-10-26：照站長拿掉星律（charge.periodic 屬性還在）；這次之前是常駐光環");
    }
}
