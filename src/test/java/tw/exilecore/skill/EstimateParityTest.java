package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.AscKeys;
import tw.exilecore.ascendancy.AscendancyEffects;
import tw.exilecore.ascendancy.AscendancyMechanics;
import tw.exilecore.character.BuffImpact;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.LineageKeys;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27：戰鬥有作用、但面板／技能說明／召喚物／網頁沒算到或寫錯的（血滲、傲寒、只減擊中、異能賦予、德魯伊之冠、
 * 虛擬角色的昇華增益、看看我怎麼辦事的、巨伴、夥伴獲得你的力量、震眩、條件加成的名字）。
 */
class EstimateParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String skill, final String... supports) {
        final java.util.List<SupportDefinition> list = new java.util.ArrayList<>();
        for (final String id : supports) {
            list.add(config.support(id));
        }
        return SkillBuild.of(config, config.skill(skill), 20, 60, false, list, Set.of(), new StatSheet());
    }

    @Test
    void lineageEstimateMatchesPrepare() {
        final SkillBuild plain = build("earthquake");
        assertTrue(LineageMechanics.estimateModifiers(plain, 1000).isEmpty(), "沒掛血脈輔助");
        // 傲寒：照滿魔力付 20%，附加這筆的 15% 物理與冰冷（跟 prepare 一樣）
        final SkillBuild hubris = build("earthquake", "olroths_hubris");
        final List<Modifier> mods = LineageMechanics.estimateModifiers(hubris, 1000);
        assertEquals(LineageMechanics.hubrisAdded(200), mods);
        final StatSheet sheet = new StatSheet();
        final StatSheet effective = LineageMechanics.estimated(hubris, 1000).profile().effective(sheet);
        assertEquals(30, effective.flat(StatKeys.DAMAGE_ADDED_MIN, Set.copyOf(DamageType.COLD.tags())), 1e-9);
        // 血滲：每 20 點生命消耗 2%（最多 40%）
        final SkillBuild blood = build("earthquake", "ataluis_bloodletting");
        final double expected = LineageMechanics.bloodlettingPercent(blood.manaCost(), 2);
        assertEquals(expected, LineageMechanics.estimated(blood, 0).profile().effective(sheet)
                .flat(StatKeys.damageExtra(DamageType.PHYSICAL), Set.of(Tags.PHYSICAL)), 1e-9);
    }

    @Test
    void hitOnlyFactorUsesSupports() {
        final StatSheet sheet = new StatSheet();
        assertEquals(1.0, SupportMechanics.hitOnlyFactor(sheet, build("fireball").profile()), 1e-9);
        final SkillBuild searing = build("fireball", "searing_ii");
        final double factor = SupportMechanics.hitOnlyFactor(sheet, searing.profile());
        assertTrue(factor < 1.0, "燎原 II：擊中傷害更少");
        assertEquals(searing.profile().effective(sheet).more(tw.exilecore.stats.SupportKeys.DAMAGE_HIT, searing.profile().tags()), factor, 1e-9);
    }

    @Test
    void eldritchAndDruidicEstimates() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(AscKeys.ELDRITCH, 5, "異能加持"));
        sheet.add(Modifier.flat(StatKeys.ES_MAX, 200, "裝備"));
        final StatSheet estimated = sheet.overlay(BuffImpact.estimateModifiers(sheet, 1.0));
        assertEquals(1.3, estimated.more(StatKeys.DAMAGE, Set.of(Tags.SPELL)), 1e-9, "法術更多 30%");
        assertEquals(1.0, estimated.more(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9, "攻擊沒有");

        final StatSheet druid = new StatSheet();
        assertTrue(AscendancyMechanics.druidicModifiers(druid, 20).isEmpty());
        druid.add(Modifier.flat(AscendancyMechanics.DRUIDIC_CHAMPION, 1, "德魯伊之魁"));
        assertEquals(0.10, AscendancyMechanics.druidicModifiers(druid, 20).get(0).value(), 1e-9, "20 點怒氣：法術更多 10%");
        assertTrue(AscendancyMechanics.druidicModifiers(druid, 1).isEmpty());
    }

    @Test
    void virtualCharacterGetsSheetOnlyAscendancyBuffs() {
        final StatSheet sheet = new StatSheet();
        assertTrue(AscendancyEffects.virtualBuffs(sheet).isEmpty());
        sheet.add(Modifier.flat(AscKeys.COMPANION_WEAPON, 60, "凱絲塔的平衡"));
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.copyOf(DamageType.PHYSICAL.tags()), "武器"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 200, Set.copyOf(DamageType.PHYSICAL.tags()), "武器"));
        sheet.add(Modifier.flat(AscKeys.CONDUIT, 1, "死靈導體"));
        final StatSheet result = sheet.overlay(AscendancyEffects.virtualBuffs(sheet));
        final Set<String> companion = Set.of(Tags.PHYSICAL, Tags.ATTACK, Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION);
        assertEquals(120, result.flat(StatKeys.DAMAGE_ADDED_MAX, companion), 1e-9, "夥伴拿主手武器 60%");
        assertEquals(30, result.flat(StatKeys.DAMAGE_EXTRA + Tags.CHAOS, Set.of(Tags.CHAOS)), 1e-9, "不潔之力：30% 額外混沌");
    }

    @Test
    void minionEstimatesMatchCombat() {
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 100, Set.copyOf(DamageType.PHYSICAL.tags()), "武器"));
        owner.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 200, Set.copyOf(DamageType.PHYSICAL.tags()), "武器"));
        assertTrue(AscendancyEffects.minionWeaponFrom(owner).isEmpty());
        owner.add(Modifier.flat(AscKeys.ALLY_WEAPON_DAMAGE, 25, "看看我怎麼辦事的"));
        assertEquals(50, AscendancyEffects.minionWeaponFrom(owner).get(1).value(), 1e-9, "主手武器 25%");

        owner.add(Modifier.flat(StatKeys.EVASION, 1000, "裝備"));
        owner.add(Modifier.flat(StatKeys.STR, 100, "天賦"));
        owner.add(Modifier.flat(tw.exilecore.stats.TreeNewMechanicKeys.FLAG_COMPANION_STRENGTH, 1, "天賦"));
        final SkillBuild wolves = build("wolf_pack", "tangmazus_thurible");
        final StatSheet derived = new StatSheet().overlay(MinionEstimates.estimate(wolves, owner, 2.0));
        assertEquals(400, derived.flat(StatKeys.EVASION, Set.of()), 1e-9, "巨伴：你閃避的 40%");
        assertEquals(400, derived.flat(StatKeys.LIFE_MAX, Set.of()), 1e-9, "力量 100 × 2 生命 × 換算 2");
        assertEquals(50, derived.flat(StatKeys.DAMAGE_ADDED_MAX, Set.of(Tags.PHYSICAL, Tags.ATTACK)), 1e-9, "看看我怎麼辦事的");
        assertTrue(MinionEstimates.ownerDerived(wolves, owner, 2.0, false).stream().noneMatch(m -> "你的力量".equals(m.source())), "殘影不算夥伴");
    }

    @Test
    void dazingTextMatchesEffect() {
        final SupportDefinition dazing = config.support("dazing");
        assertTrue(dazing.description().contains("8 秒") && dazing.description().contains("暈眩累積更多 50%"));
        assertFalse(dazing.description().contains("傷害更少"));
        assertTrue(dazing.modifiers().stream().noneMatch(m -> m.stat().equals(StatKeys.STUN_BUILDUP)), "照 PoE2：沒有增加暈眩累積");
        assertTrue(ModifierText.describe(Modifier.flat(StatKeys.DAZE_CHANCE, 30, "x")).contains("8 秒內受到的暈眩累積更多 50%"));
    }

    @Test
    void conditionalNamesReadSkillSheets() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild execute = build("earthquake", "execute");
        assertTrue(BuffImpact.conditionalNames(sheet).isEmpty(), "角色的表上沒有");
        assertEquals(List.of("對低血的敵人"), BuffImpact.conditionalNames(List.of(sheet, execute.profile().effective(sheet))));
        final StatSheet beast = new StatSheet();
        beast.add(Modifier.flat(LineageKeys.LOW_LIFE_ONLY, 1, "掠獸"));
        beast.add(Modifier.flat(LineageKeys.PER_POISON, 8, "毒慾"));
        beast.add(Modifier.flat(AscendancyMechanics.POINT_BLANK, 20, "零點射擊"));
        assertEquals(List.of(BuffImpact.BEASTRITE, "毒慾（敵人身上的中毒）", "零點射擊（距離）"), BuffImpact.conditionalNames(beast));
    }
}
