package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三部分（四）光環與戰吼：設定、保留、效果隨等級與光環效果倍率、捷的隱藏技能、戰吼的增益與強化（純邏輯的部分）。 */
class AuraWarcryTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static SkillBuild build(final String skill, final int gemLevel, final StatSheet sheet, final SupportDefinition... supports) {
        return SkillBuild.of(config, config.skill(skill), gemLevel, 100, false, List.of(supports), Set.of(Tags.MACE), sheet);
    }

    @Test
    void aurasParseWithReservationAndScaledEffects() {
        // 2026-10 PoE2 改版：舊的光環（憎恨…）拿掉了，用凜冬之殼（給隊友的精魂光環）
        final SkillDefinition aegis = config.skill("frost_aegis");
        assertEquals(Mechanic.AURA, aegis.mechanic());
        assertNotNull(aegis.aura());
        assertEquals(30, aegis.aura().reserve(), 1e-9);
        assertTrue(aegis.aura().allies());
        assertFalse(aegis.aura().herald());
        assertTrue(aegis.tags().containsAll(Set.of(Tags.AURA, Tags.AREA)));
        assertFalse(aegis.tags().contains(Tags.SPELL), "光環不算法術（2026-10，照 PoE2）");
        assertFalse(aegis.dealsDamage());
        assertNull(aegis.damage());
        assertEquals(0, aegis.mana().last(), 1e-9, "光環用保留的");
        assertEquals(2, aegis.aura().effects().size());
        final ScaledMod armour = aegis.aura().effects().getFirst();
        assertEquals(StatKeys.ARMOUR, armour.stat());
        assertEquals(20, armour.value().at(1), 1e-9);
        assertEquals(35, armour.value().at(20), 1e-9);

        final StatSheet owner = new StatSheet();
        final SkillBuild low = build("frost_aegis", 1, owner);
        final SkillBuild high = build("frost_aegis", 20, owner);
        assertEquals(30, low.reserve(), 1e-9);
        assertEquals(1.0, low.auraEffect(), 1e-9);
        assertEquals(0.20, low.auraModifiers().getFirst().value(), 1e-9, "增加 20% 護甲（百分比換成小數）");
        assertEquals(0.35, high.auraModifiers().getFirst().value(), 1e-9);
        assertEquals(StatKeys.RESIST_FIRE, high.auraModifiers().get(1).stat());
        assertEquals(15, high.auraModifiers().get(1).value(), 1e-9, "+15% 火焰抗性（FLAT 直接用）");
        assertFalse(low.generosity());
        assertEquals(0, low.exerts());
        assertNull(low.deploy());
        assertFalse(low.echo());
    }

    @Test
    void onlyAllyAurasReachTheOwnersMinions() {
        // 2026-10：只給自己的常駐增益（allies: false）不再給召喚物；給隊友的光環照舊給完整效果
        final StatSheet owner = new StatSheet();
        // 攻擊型的四個（戰意昂揚、崩滅心咒、元素潮汐、磨蝕之志）2026-10 也改回只給自己（銀月庇 2026-10-15 改成戰吼）
        for (final String id : List.of("storm_staff", "battle_rage",
                "combat_frenzy", "ruin_mantra", "elemental_conflux", "attrition", "magma_barrier")) { // 2026-10-24：熔殼照 PoE2 只給自己
            final SkillBuild self = build(id, 20, owner);
            assertFalse(self.definition().aura().allies(), id);
            assertFalse(self.auraModifiers().isEmpty(), id + "：自己還是有效果");
            assertTrue(self.minionAuraModifiers().isEmpty(), id + "：只給自己的不給召喚物");
        }
        for (final String id : List.of("wind_dancer", "mana_remnants", "time_of_need")) {
            final SkillBuild shared = build(id, 20, owner);
            assertTrue(shared.definition().aura().allies(), id);
            assertEquals(shared.auraModifiers(), shared.minionAuraModifiers(), id + "：給隊友的光環，召喚物吃到一樣的效果");
            assertFalse(shared.minionAuraModifiers().isEmpty(), id);
        }
        // 帶 minion 標籤的效果（骸餘饗的召喚物生命）走主人的屬性表：主人身上有這條，MinionSheets.ownerModifiers 會帶過去
        final SkillBuild feast = build("grim_feast", 20, owner);
        assertTrue(feast.minionAuraModifiers().isEmpty());
        final StatSheet withFeast = new StatSheet().addAll(feast.auraModifiers());
        assertEquals(1, MinionSheets.ownerModifiers(withFeast).size(), "召喚物生命那一條");
        assertEquals(StatKeys.LIFE_MAX, MinionSheets.ownerModifiers(withFeast).getFirst().stat());
        // 不是光環的技能沒有
        assertTrue(build("summon_skeletons", 20, owner).minionAuraModifiers().isEmpty());
    }

    @Test
    void heraldsTriggerOnTheirPoe2Conditions() {
        // 2026-10（照 PoE2）：捷不再是「殺死就觸發」
        final Set<AilmentType> none = Set.of();
        // 燼使：自己的攻擊擊中，過量擊殺 20% 以上
        final AuraSpec.KillCondition ash = config.skill("herald_of_ash").aura().condition();
        assertEquals(new AuraSpec.KillCondition(true, null, 20), ash);
        assertTrue(ash.met(true, none, 20));
        assertTrue(ash.met(true, none, 350));
        assertFalse(ash.met(true, none, 19.9), "過量不到 20%");
        assertFalse(ash.met(false, none, 80), "法術、召喚物、捷自己的效果打死的不算");
        // 雷使：攻擊殺死感電中的；霜使：攻擊殺死凍結中的
        final AuraSpec.KillCondition thunder = config.skill("herald_of_thunder").aura().condition();
        assertEquals(new AuraSpec.KillCondition(true, AilmentType.SHOCK, 0), thunder);
        assertTrue(thunder.met(true, Set.of(AilmentType.SHOCK, AilmentType.IGNITE), 0));
        assertFalse(thunder.met(true, Set.of(AilmentType.IGNITE), 0), "沒感電");
        assertFalse(thunder.met(false, Set.of(AilmentType.SHOCK), 0), "不是攻擊");
        final AuraSpec.KillCondition ice = config.skill("herald_of_ice").aura().condition();
        assertEquals(new AuraSpec.KillCondition(true, AilmentType.FREEZE, 0), ice);
        assertFalse(ice.met(true, Set.of(AilmentType.CHILL), 0), "只有冰緩不算凍結");
        assertTrue(ice.met(true, Set.of(AilmentType.CHILL, AilmentType.FREEZE), 0));
        // 血宣、疫使：身上有流血／中毒就好，不用是攻擊（毒死、召喚物打死的也算）
        final AuraSpec.KillCondition blood = config.skill("herald_of_blood").aura().condition();
        assertEquals(new AuraSpec.KillCondition(false, AilmentType.BLEED, 0), blood);
        assertTrue(blood.met(false, Set.of(AilmentType.BLEED), 0));
        assertFalse(blood.met(true, none, 500));
        final AuraSpec.KillCondition plague = config.skill("herald_of_plague").aura().condition();
        assertEquals(new AuraSpec.KillCondition(false, AilmentType.POISON, 0), plague);
        assertTrue(plague.met(false, Set.of(AilmentType.POISON), 0));
        // 沒寫條件的照舊：殺死就觸發；說明文字
        assertTrue(AuraSpec.KillCondition.ALWAYS.always());
        assertTrue(AuraSpec.KillCondition.ALWAYS.met(false, none, 0));
        assertEquals("用攻擊擊中殺死感電中的敵人", thunder.describe());
        assertEquals("殺死中毒中的敵人", plague.describe());
        assertTrue(ash.describe().contains("過量擊殺") && ash.describe().contains("20%"), ash.describe());
        // 過量擊殺的算法：多打出來的傷害 ÷ 最大生命
        assertEquals(0, AuraService.overkillPercent(500, 800, 1000), 1e-9, "沒打死");
        assertEquals(20, AuraService.overkillPercent(300, 100, 1000), 1e-9);
        assertEquals(250, AuraService.overkillPercent(3000, 500, 1000), 1e-9);
        assertEquals(0, AuraService.overkillPercent(3000, 500, 0), 1e-9);
    }

    @Test
    void berserkAndSavageFuryCarryTheirPoe2Costs() {
        // 赤怒（Berserk）：怒氣效果 40～59%；怒氣沒有在消退時每點怒氣每秒失去 0.1% 最大生命
        final SkillDefinition berserk = config.skill("berserk");
        final AuraSpec.LifeCost cost = berserk.aura().lifeCost();
        assertNotNull(cost);
        assertEquals(0.1, cost.perRage(), 1e-9);
        assertEquals(3.0, cost.percentPerSecond(30, false), 1e-9, "30 點怒氣 = 每秒 3%");
        assertEquals(5.0, cost.percentPerSecond(50, false), 1e-9);
        assertEquals(0, cost.percentPerSecond(50, true), 1e-9, "怒氣在消退：不扣");
        assertEquals(0, cost.percentPerSecond(0, false), 1e-9);
        assertEquals(0.40, build("berserk", 1, new StatSheet()).auraModifiers().getFirst().value(), 1e-9);
        assertEquals(0.59, build("berserk", 20, new StatSheet()).auraModifiers().getFirst().value(), 1e-9);
        // 怒氣消退：40 tick 沒拿到才開始
        assertFalse(SkillResources.rageDecaying(20, 100, 100 + SkillResources.RAGE_DECAY_DELAY - 1));
        assertTrue(SkillResources.rageDecaying(20, 100, 100 + SkillResources.RAGE_DECAY_DELAY));
        assertFalse(SkillResources.rageDecaying(0, 100, 1000), "沒有怒氣");
        // 扣生命：直接扣、扣不死（最低留 1 點）
        assertEquals(970, AuraService.lifeAfterLoss(1000, 1000, 3), 1e-9);
        assertEquals(985, AuraService.lifeAfterLoss(1000, 1000, 3 * AuraService.TICK_SECONDS), 1e-9, "半秒扣一次");
        assertEquals(1, AuraService.lifeAfterLoss(20, 1000, 3), 1e-9);
        assertEquals(0.5, AuraService.lifeAfterLoss(0.5, 1000, 3), 1e-9, "本來就不到 1 點的不動");

        // 獸血沸（Savage Fury）：沒有一直有的效果了；攻擊累積 20 點 → 狂暴 10 秒，更多傷害 20～29%＋疾速，每秒失去 3% 生命，要獸形
        final SkillDefinition savage = config.skill("savage_fury");
        final AuraSpec.Fury fury = savage.aura().fury();
        assertNotNull(fury);
        assertTrue(savage.aura().effects().isEmpty());
        assertEquals(20, fury.max());
        assertEquals(200, fury.ticks(20), "20 點 × 0.5 秒");
        assertEquals(100, fury.ticks(10));
        assertEquals(200, fury.ticks(99), "超過上限照上限");
        assertEquals(3, fury.lifeLoss(), 1e-9);
        assertTrue(fury.onslaught());
        assertTrue(fury.form());
        final Modifier low = fury.effects().getFirst().toModifier(1, 1.0, "x");
        final Modifier high = fury.effects().getFirst().toModifier(20, 1.0, "x");
        assertEquals(StatKeys.DAMAGE, low.stat());
        assertEquals(ModType.MORE, low.type());
        assertEquals(0.20, low.value(), 1e-9);
        assertEquals(0.29, high.value(), 1e-9);
        assertFalse(savage.aura().allies());
        assertTrue(build("savage_fury", 20, new StatSheet()).minionAuraModifiers().isEmpty(), "狂暴只給自己");
        // 其他光環沒有代價、沒有獸性
        assertNull(config.skill("wind_dancer").aura().lifeCost());
        assertNull(config.skill("wind_dancer").aura().fury());
        assertTrue(config.skill("wind_dancer").aura().condition().always());
    }

    @Test
    void archmageScalesWithMaximumManaAndCostsAShareOfIt() {
        // 2026-10：祕法之心照 PoE2 的 Archmage 重做，係數砍半：每 100 最大魔力 2% 額外閃電；每次施法多花最大魔力的 8% → 6.1%
        final SkillDefinition archmage = config.skill("archmage");
        assertEquals(100, archmage.aura().reserve(), 1e-9);
        assertFalse(archmage.aura().allies());
        final List<Modifier> low = build("archmage", 1, new StatSheet()).auraModifiers();
        final List<Modifier> high = build("archmage", 20, new StatSheet()).auraModifiers();
        assertEquals(2, low.size());
        assertEquals(StatKeys.damageExtraPerMana(DamageType.LIGHTNING), low.get(0).stat());
        assertEquals(2.0, low.get(0).value(), 1e-9);
        assertEquals(2.0, high.get(0).value(), 1e-9, "傷害的係數不隨等級變");
        assertEquals(StatKeys.SKILL_COST_MAX_MANA, low.get(1).stat());
        assertEquals(8.0, low.get(1).value(), 1e-9);
        assertEquals(6.1, high.get(1).value(), 1e-9);
        assertEquals(Set.of(Tags.SPELL), low.get(0).tags());
        assertEquals("每 100 點最大魔力，法術獲得 2% 傷害為額外的閃電傷害", ModifierText.describe(low.get(0)));
        assertEquals("法術魔力消耗多最大魔力的 6.1%", ModifierText.describe(high.get(1)));

        // 額外閃電：1,000 魔力 = 20%，3,900 魔力 = 78%；攻擊不算、引導技能不算、沒開光環是 0
        final Set<String> spell = Set.of(Tags.SPELL, Tags.FIRE);
        final StatSheet thousand = new StatSheet().setBase(StatKeys.MANA_MAX, 1000).addAll(high);
        assertEquals(20, DamageCalculator.extraPerMana(thousand, DamageType.LIGHTNING, spell), 1e-9);
        assertEquals(0, DamageCalculator.extraPerMana(thousand, DamageType.FIRE, spell), 1e-9);
        assertEquals(0, DamageCalculator.extraPerMana(thousand, DamageType.LIGHTNING, Set.of(Tags.ATTACK, Tags.FIRE)), 1e-9);
        final StatSheet stacker = new StatSheet().setBase(StatKeys.MANA_MAX, 3900).addAll(high);
        assertEquals(78, DamageCalculator.extraPerMana(stacker, DamageType.LIGHTNING, spell), 1e-9);
        assertEquals(0, DamageCalculator.extraPerMana(new StatSheet().setBase(StatKeys.MANA_MAX, 3900), DamageType.LIGHTNING, spell), 1e-9);
        final StatSheet channelling = new StatSheet().setBase(StatKeys.MANA_MAX, 1000).addAll(high)
                .add(Modifier.flat(StatKeys.CHANNEL_STAGES, 8, "引導"));
        assertEquals(0, DamageCalculator.extraPerMana(channelling, DamageType.LIGHTNING, spell), 1e-9);

        // 多花的魔力：最大魔力 × 6.1%；不用魔力的技能、攻擊、引導技能不算
        assertEquals(61, SkillBuild.extraManaCost(20, thousand, spell), 1e-9);
        assertEquals(237.9, SkillBuild.extraManaCost(20, stacker, spell), 1e-9);
        assertEquals(0, SkillBuild.extraManaCost(0, thousand, spell), 1e-9);
        assertEquals(0, SkillBuild.extraManaCost(20, thousand, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals(0, SkillBuild.extraManaCost(20, channelling, spell), 1e-9);
        // 真的技能：火球的消耗 = （基礎 + 61）× 倍率；沒開祕法之心的照舊
        final int plain = build("fireball", 20, new StatSheet().setBase(StatKeys.MANA_MAX, 1000)).manaCost();
        final int taxed = build("fireball", 20, thousand).manaCost();
        assertEquals(plain + 61, taxed);
        // 光環自己不用魔力，不會被自己加價
        assertEquals(0, build("archmage", 20, thousand).manaCost());
    }

    @Test
    void linkedVolleyRepeatsTheNextBowOrSpearProjectileAttack() {
        // 2026-10：連珠（照 PoE2 的 Barrage）：下一次弓／矛的投射物攻擊重複 +2 次、每次少 50%，每顆怒星再 +1 次
        final SkillDefinition volley = config.skill("linked_volley");
        assertEquals(Mechanic.GUARD, volley.mechanic());
        assertEquals(Set.of("bow", "spear"), volley.weapons());
        assertTrue(volley.usableWith(Set.of(Tags.BOW, Tags.TWO_HAND)));
        assertFalse(volley.usableWith(Set.of(Tags.MACE)));
        assertEquals(2, volley.cooldown(), 1e-9);
        final BarrageSpec spec = config.barrage("linked_volley");
        assertNotNull(spec);
        assertNull(config.barrage("ice_tipped_arrows"), "別的增益技能沒有");
        assertEquals(2, spec.repeats());
        assertEquals("frenzy", spec.charge());
        assertEquals(2, spec.repeatsWith(0));
        assertEquals(5, spec.repeatsWith(3), "三顆怒星：再多三次");
        assertEquals(BarrageSpec.MAX_REPEATS, spec.repeatsWith(99));
        assertEquals(0.5, spec.damageScale(), 1e-9);
        assertEquals(4, spec.gapTicks(), "0.2 秒");
        assertEquals(600, spec.cooldownAttackTime().at(1), 1e-9);
        assertEquals(410, spec.cooldownAttackTime().at(20), 1e-9);
        // 用掉之後的冷卻：2 秒＋攻擊時間 × 600%（1 級）；攻擊時間 0.5 秒（10 tick）= 5 秒；最多 30 秒；冷卻回復照乘
        assertEquals(100, spec.usedCooldownTicks(2, 10, 600, 1.0));
        assertEquals(81, spec.usedCooldownTicks(2, 10, 410, 1.0), "20 級：2 ＋ 0.5 × 4.1 = 4.05 秒");
        assertEquals(600, spec.usedCooldownTicks(2, 200, 600, 1.0), "最多 30 秒");
        assertEquals(80, spec.usedCooldownTicks(2, 10, 600, 0.8));
        // 能被強化的：弓、矛的投射物攻擊；近戰、法術、拿別的武器、有冷卻的、自己會重複的不行
        final Set<String> bow = Set.of(Tags.BOW, Tags.TWO_HAND);
        assertTrue(BarrageSpec.empowers(config.skill("ice_shot"), bow, 0));
        assertTrue(BarrageSpec.empowers(config.skill("arrow_barrage"), bow, 0));
        assertFalse(BarrageSpec.empowers(config.skill("ice_shot"), Set.of(Tags.CROSSBOW), 0), "手上不是弓或矛");
        assertFalse(BarrageSpec.empowers(config.skill("ice_shot"), bow, 2), "自己就會重複的");
        assertFalse(BarrageSpec.empowers(config.skill("fireball"), bow, 0), "法術");
        assertFalse(BarrageSpec.empowers(config.skill("linked_volley"), bow, 0));
        for (final SkillDefinition each : config.all()) {
            if (BarrageSpec.empowers(each, bow, 0)) {
                assertTrue(each.isAttack() && each.mechanic() == Mechanic.PROJECTILE && each.cooldown() <= 0, each.id());
            }
        }
    }

    @Test
    void lunarBlessingIsAWerewolfWarcryFedByRage() {
        // 2026-10-15 照 PoE2 的月光賜福：會變身狼人的戰吼，冷卻 22 秒、增益 5 秒，用掉全部怒氣每點多 0.2 秒；月光是隱藏技能
        final SkillDefinition lunar = config.skill("lunar_blessing");
        assertEquals(Mechanic.WARCRY, lunar.mechanic());
        assertTrue(lunar.tags().contains(Tags.WEREWOLF) && lunar.tags().contains(Tags.SHAPESHIFT), "放了就變狼人");
        assertFalse(lunar.tags().contains(Tags.SPELL), "戰吼不算法術");
        assertEquals(22, lunar.cooldown(), 1e-9);
        assertEquals(5, lunar.warcry().duration(), 1e-9);
        assertEquals(0.2, lunar.warcry().rageExtend(), 1e-9);
        assertFalse(lunar.warcry().buff().isEmpty());
        assertFalse(lunar.warcry().minionBuff().isEmpty(), "召喚物（狼）也有增益");
        final SkillDefinition beam = config.skill(SupportProcs.LUNAR_MOONBEAM);
        assertTrue(beam.hidden());
        assertTrue(beam.isAttack());
        assertTrue(beam.tags().contains(Tags.COLD));
        assertFalse(beam.tags().contains(Tags.WEREWOLF), "月光打中不會再落月光");
        assertEquals(SupportProcs.LUNAR_BLESSING, lunar.id());
        assertEquals(0, config.skill("arctic_howl").warcry().rageExtend(), 1e-9, "其他戰吼不吃怒氣");
    }

    @Test
    void weaponBoundAurasNeedTheirWeapon() {
        // 2026-10：寫了 weapons 的光環要拿著那種武器才開得起來（AuraService 照 usableWith 開關）
        final SkillDefinition staff = config.skill("storm_staff");
        assertEquals(List.of("quarterstaff"), List.copyOf(staff.weapons()));
        assertTrue(staff.usableWith(Set.of(Tags.QUARTERSTAFF, Tags.TWO_HAND)));
        assertFalse(staff.usableWith(Set.of(Tags.MACE)), "權杖、錘開不起來");
        assertFalse(staff.usableWith(Set.of(Tags.BOW)));
        assertTrue(config.skill("shard_scavenger").usableWith(Set.of(Tags.CROSSBOW)));
        assertFalse(config.skill("shard_scavenger").usableWith(Set.of(Tags.BOW)));
        assertTrue(config.skill("combat_frenzy").usableWith(Set.of(Tags.BOW)), "沒寫 weapons 的什麼武器都行");
        // 崩滅心咒要長棍、銀月庇要獸魂符（PoE2 的原型都是那種武器的技能）
        assertTrue(config.skill("ruin_mantra").usableWith(Set.of(Tags.QUARTERSTAFF)));
        assertFalse(config.skill("ruin_mantra").usableWith(Set.of(Tags.MACE)));
        assertTrue(config.skill("lunar_blessing").usableWith(Set.of(Tags.TALISMAN)));
        assertFalse(config.skill("lunar_blessing").usableWith(Set.of(Tags.BOW)));
        // 疫使跟其他捷光環一樣只有擊殺效果，沒有常駐的額外傷害
        assertTrue(config.skill("herald_of_plague").aura().effects().isEmpty());
        assertTrue(config.skill("herald_of_plague").aura().herald());
    }

    @Test
    void auraEffectAndReservationScaleWithOwnerStats() {
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.20, Set.of(), "領袖"));
        owner.add(new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, -0.10, Set.of(), "領袖"));
        final SkillBuild aegis = build("frost_aegis", 20, owner);
        assertEquals(1.2, aegis.auraEffect(), 1e-9);
        assertEquals(0.35 * 1.2, aegis.auraModifiers().getFirst().value(), 1e-9, "每一條效果都乘光環效果");
        assertEquals(30 / 1.1, aegis.reserve(), 1e-9, "2026-10 保留效率：減少 10% → 保留 ÷ 1.1");

        // 強效光環、慷慨這兩顆輔助寶石 2026-10 改版拿掉了（supports.yml 的 retired），引擎的 aura.effect、flag.generosity 還在：用自己做的輔助測
        assertNull(config.support("aura_effect"));
        assertNull(config.support("generosity"));
        final SupportDefinition auraEffect = new SupportDefinition("aura_effect", "強效光環", "", "BEACON", 2, Set.of(Tags.AURA), Set.of(), 1.0,
                List.of(new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.25, Set.of(), "強效光環"),
                        new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, 0.20, Set.of(), "強效光環")), List.of());
        final SkillBuild boosted = build("frost_aegis", 20, owner, auraEffect);
        assertEquals(1, boosted.supports().size());
        assertEquals(1.45, boosted.auraEffect(), 1e-9, "增加 20% + 25%（增加相加）");
        assertEquals(30 * (1 - 0.10 + 0.20), boosted.reserve(), 1e-9, "保留 -10% + 20% = 增加 10%，照乘");

        final SupportDefinition generosity = new SupportDefinition("generosity", "慷慨", "", "BEACON", 2, Set.of(Tags.AURA), Set.of(), 1.0,
                List.of(Modifier.flat(StatKeys.FLAG_GENEROSITY, 1, "慷慨"),
                        new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.4, Set.of(), "慷慨")), List.of());
        final SkillBuild generous = build("frost_aegis", 20, owner, generosity);
        assertTrue(generous.generosity());
        assertFalse(auraEffect.supports(config.skill("fireball")), "強效光環只能掛光環");
        assertFalse(config.support("spell_echo").supports(config.skill("frost_aegis")), "雙詠不能掛光環");
        assertNotNull(MetaGems.problem(config.meta("totem_casting"), config.skill("frost_aegis")), "光環不能插進星咒圖騰");
        assertTrue(config.support("increased_area").supports(config.skill("frost_aegis")), "擴散讓隊友的範圍更大");
    }

    @Test
    void heraldsPointAtHiddenSubSkillsThatTakeTheHeraldsSupports() {
        final SkillDefinition herald = config.skill("herald_of_ash");
        assertTrue(herald.aura().herald());
        assertEquals("herald_ash_burst", herald.aura().onKill());
        assertEquals(100, herald.aura().onKillChance(), 1e-9);
        assertFalse(herald.aura().allies(), "捷只給自己（與召喚物）");
        final SkillDefinition burst = config.skill("herald_ash_burst");
        assertNotNull(burst);
        assertTrue(burst.hidden());
        assertFalse(burst.isAttack());
        assertEquals(Mechanic.NOVA, burst.mechanic());
        assertNotNull(burst.damage());
        assertFalse(config.visible().contains(burst), "隱藏的不會出現在雕琢選單");
        // 捷的輔助用在它的效果上：擴散（範圍）、純化（元素）都掛得上隱藏的爆發；強效光環掛不上
        assertTrue(config.support("increased_area").supports(burst));
        assertTrue(config.support("elemental_focus").supports(burst));
        assertFalse(config.support("faster_casting").supports(burst), "疾詠不能掛捷（捷的效果是擊殺時觸發）");
        assertFalse(config.support("increased_area").supports(config.skill("default_melee")), "普攻還是不能掛輔助");
        final StatSheet owner = new StatSheet();
        final SkillBuild sub = SkillBuild.of(config, burst, 20, 100, false, List.of(config.support("increased_area")), Set.of(), owner);
        assertEquals(1, sub.supports().size());
        assertTrue(sub.radius() > burst.params().radius().at(20), "範圍變大");
        assertEquals(Mechanic.NOVA, config.skill("herald_thunder_bolt").mechanic(), "2026-10-24：雷使照 PoE2 落在那一下的目標上");
        assertEquals(Mechanic.NOVA, config.skill("herald_ice_shatter").mechanic());
    }

    @Test
    void warcriesParseWithBuffDebuffAndExerts() {
        // 2026-10 PoE2 改版：戰吼換成堅壁、焚獄、震地
        final SkillDefinition rally = config.skill("infernal_cry");
        assertEquals(Mechanic.WARCRY, rally.mechanic());
        assertNotNull(rally.warcry());
        assertTrue(rally.tags().containsAll(Set.of(Tags.WARCRY, Tags.DURATION, Tags.AREA, Tags.COOLDOWN)));
        assertFalse(rally.tags().contains(Tags.SPELL), "戰吼不算法術（2026-10）");
        assertEquals(8, rally.cooldown(), 1e-9);
        assertEquals(3, rally.warcry().exerts());
        assertEquals(25, rally.warcry().exertMore().at(1), 1e-9);
        assertEquals(40, rally.warcry().exertMore().at(20), 1e-9);
        assertFalse(rally.warcry().taunt());
        assertEquals(0, rally.warcry().taken().last(), 1e-9);

        final StatSheet owner = new StatSheet();
        final SkillBuild build = build("infernal_cry", 20, owner);
        assertEquals(3, build.exerts());
        final List<Modifier> buff = build.warcryModifiers();
        assertEquals(1, buff.size());
        assertEquals(StatKeys.damageGain(DamageType.FIRE), buff.getFirst().stat());
        assertEquals(30, buff.getFirst().value(), 1e-9);
        assertEquals(8 * 20, build.durationTicks(rally.warcry().duration()));

        final SkillBuild strong = build("infernal_cry", 20, owner, config.support("warcry_power"), config.support("increased_duration"));
        assertEquals(5, strong.exerts(), "強勢戰吼 +2 次");
        assertTrue(strong.cooldownTicks() > build.cooldownTicks(), "冷卻變長");
        assertTrue(strong.durationTicks(rally.warcry().duration()) > build.durationTicks(rally.warcry().duration()), "持久撐更久");
        owner.add(new Modifier(StatKeys.WARCRY_EXERTS, ModType.FLAT, 1, Set.of(Tags.WARCRY), "戰場號令"));
        owner.add(new Modifier(StatKeys.SKILL_COOLDOWN, ModType.INCREASED, -0.15, Set.of(Tags.WARCRY), "戰場號令"));
        final SkillBuild tree = build("infernal_cry", 20, owner);
        assertEquals(4, tree.exerts());
        assertEquals(Math.round(8 * 20 * 0.85), tree.cooldownTicks());
        assertEquals(0, build("boneshatter", 20, owner).cooldownTicks(), "戰吼的冷卻減少不影響別的技能");

        final SkillDefinition enduring = config.skill("fortifying_cry");
        assertEquals(8, enduring.warcry().heal().at(1), 1e-9);
        assertEquals(15, enduring.warcry().heal().at(20), 1e-9);
        assertTrue(enduring.warcry().taunt());
        final SkillDefinition intimidating = config.skill("seismic_cry");
        assertEquals(10, intimidating.warcry().taken().at(1), 1e-9);
        assertEquals(20, intimidating.warcry().taken().at(20), 1e-9);
        assertEquals(2, intimidating.warcry().exerts());
        assertFalse(config.support("warcry_power").supports(config.skill("fireball")));
        assertTrue(config.support("cooldown_recovery").supports(rally));
        // 戰吼不算法術（2026-10）：法術的輔助寶石掛不上去，原因講清楚
        assertFalse(config.support("faster_casting").supports(rally), "疾詠要法術");
        assertEquals("要法術技能", config.support("faster_casting").refusal(rally));
        assertFalse(config.support("added_fire").supports(rally), "引火（攻擊或法術）也不行");
        assertNotNull(MetaGems.problem(config.meta("totem_casting"), rally), "戰吼不能插進星咒圖騰");
        assertNull(config.support("warcry_power").refusal(rally));
    }

    @Test
    void exertedAttacksMultiplyTheProfileAndCountDown() {
        final StatSheet owner = new StatSheet();
        final SkillBuild strike = build("boneshatter", 20, owner);
        SkillRuntime.Exert exert = new SkillRuntime.Exert(2, 50, 100);
        assertTrue(exert.active(10));
        assertFalse(exert.active(100), "時間到了就沒了");
        final SkillBuild exerted = strike.withProfile(strike.profile().scaled(1 + exert.more() / 100.0, "戰吼強化"));
        assertEquals(strike.definition(), exerted.definition());
        assertEquals(1, exerted.profile().local().size() - strike.profile().local().size(), "多一條更多 50% 傷害");
        assertEquals(0.5, exerted.profile().local().getLast().value(), 1e-9);
        assertEquals(ModType.MORE, exerted.profile().local().getLast().type());
        exert = exert.consume();
        assertNotNull(exert);
        assertEquals(1, exert.left());
        assertNull(exert.consume(), "用完就沒了");
    }

    @Test
    void scaledModAndModifierText() {
        assertEquals(List.of("附加 4–22 火焰傷害", "增加 16% 冰冷傷害"), ModifierText.describeAll(List.of(
                new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 4, Set.of(Tags.FIRE), ""),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 22, Set.of(Tags.FIRE), ""),
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.16, Set.of(Tags.COLD), ""))));
        assertEquals("增加 25% 光環效果", ModifierText.describe(new Modifier(StatKeys.AURA_EFFECT, ModType.INCREASED, 0.25, Set.of(), "")));
        assertEquals("精魂技能的保留效率增加 10%", ModifierText.describe(new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, -0.10, Set.of(), "")));
        assertEquals("戰吼多強化 2 次攻擊", ModifierText.describe(new Modifier(StatKeys.WARCRY_EXERTS, ModType.FLAT, 2, Set.of(), "")));
        assertEquals("增加 45% 護甲", ModifierText.describe(new Modifier(StatKeys.ARMOUR, ModType.INCREASED, 0.45, Set.of(), "")));
        assertEquals("+16% 火焰抗性", ModifierText.describe(new Modifier(StatKeys.RESIST_FIRE, ModType.FLAT, 16, Set.of(), "")));
    }

    @Test
    void brokenAuraAndWarcryBlocksAreRejected() throws IOException {
        final Path dir = Files.createTempDirectory("exilecore-aura");
        Files.copy(DATA.resolve("supports.yml"), dir.resolve("supports.yml"));
        final String original = Files.readString(DATA.resolve("skills.yml"));
        assertRejected(dir, original.replace("on_kill: { skill: herald_ash_burst,", "on_kill: { skill: nothing_here,"), "on_kill");
        assertRejected(dir, original.replace("on_kill: { skill: herald_ash_burst,", "on_kill: { skill: fireball,"), "hidden");
        // 2026-10：捷的觸發條件、代價、獸性寫錯也擋下來
        assertRejected(dir, original.replace("attack: true, ailment: shock }", "attack: true, ailment: soggy }"), "ailment");
        assertRejected(dir, original.replace("life_cost: { per_rage: 0.1 }", "life_cost: { per_rage: 0 }"), "per_rage");
        assertRejected(dir, original.replace("        seconds_per: 0.5\n", "        seconds_per: 0\n"), "seconds_per");
        assertRejected(dir, original.replace("    cooldown: 8\n    mana: [7, 16]", "    mana: [7, 16]"), "cooldown");
        assertRejected(dir, original.replace("      reserve: 30          # 2026-10：25 → 30（照 PoE2 的 Arctic Armour）",
                "      reserve: 130"), "reserve");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        assertFalse(content.equals(Files.readString(DATA.resolve("skills.yml"))), "測試要真的改到東西：" + mention);
        Files.writeString(dir.resolve("skills.yml"), content);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }
}
