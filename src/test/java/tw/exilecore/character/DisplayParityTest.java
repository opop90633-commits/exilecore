package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.SelfDebuffs;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27：面板、技能每秒傷害、網頁要跟戰鬥實際算的一樣（效果有生效、面板看得到）。
 */
class DisplayParityTest {

    @Test
    void temporalChainsCooldownGoesIntoSheet() {
        // 遲滯詛咒：冷卻時間增加 35%（放進屬性表），移動以外的其他減益照舊擊中時疊
        final List<Modifier> mods = List.of(Modifier.increased(StatKeys.SKILL_COOLDOWN, 0.35, "換界石：遲滯詛咒"),
                Modifier.flat(StatKeys.RESIST_FIRE, -24, "換界石：三元詛咒"));
        final StatSheet sheet = new StatSheet();
        sheet.addAll(SelfDebuffs.sheetPart(mods));
        assertEquals(1.35, sheet.apply(StatKeys.SKILL_COOLDOWN, 1.0, Set.of()), 1e-9);
        assertEquals(0, sheet.get(StatKeys.RESIST_FIRE), 1e-9, "抗性照舊擊中時才疊");
        assertEquals(1, SelfDebuffs.hitPart(mods).size());
        assertEquals(StatKeys.RESIST_FIRE, SelfDebuffs.hitPart(mods).get(0).stat(), "冷卻時間不在擊中時再疊一次");
    }

    @Test
    void painAttunementFollowsLife() {
        final StatSheet sheet = new StatSheet();
        assertTrue(BuffImpact.painAttunement(sheet, 1.0).isEmpty(), "沒點基石");
        sheet.add(Modifier.flat(StatKeys.FLAG_PAIN_ATTUNEMENT, 1, "痛極生力"));
        assertEquals(-0.3, BuffImpact.painAttunement(sheet, 1.0).get(0).value(), 1e-9, "滿血：更少 30%");
        assertTrue(BuffImpact.painAttunement(sheet, 0.6).isEmpty());
        assertEquals(0.3, BuffImpact.painAttunement(sheet, 0.3).get(0).value(), 1e-9, "低血：更多 30%");
    }

    @Test
    void primalHungerRemovesRage() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0.20, BuffImpact.rageModifiers(20, sheet).get(0).value(), 1e-9);
        sheet.add(Modifier.flat(tw.exilecore.stats.KeystoneKeys.FLAG_PRIMAL_HUNGER, 1, "原初飢餓"));
        assertTrue(BuffImpact.rageModifiers(20, sheet).isEmpty(), "原初飢餓：怒氣沒有效果（跟戰鬥一樣）");
    }

    @Test
    void alwaysOnAscendancyEstimates() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(tw.exilecore.ascendancy.AscKeys.RANDOM_ELEMENT, 10, "四時更迭"));
        sheet.add(Modifier.flat(tw.exilecore.ascendancy.AscKeys.ACCURACY_DAMAGE, 25, "穿透"));
        sheet.setBase("asc.base.weapon_accuracy", 400);
        final StatSheet estimated = sheet.overlay(BuffImpact.estimateModifiers(sheet, 1.0));
        for (final String element : new String[] {Tags.FIRE, Tags.COLD, Tags.LIGHTNING}) {
            assertEquals(10 / 3.0, estimated.flat(StatKeys.DAMAGE_EXTRA + element, Set.of()), 1e-9, "隨機元素：各 1/3 的期望值");
        }
        assertEquals(100, estimated.flat(StatKeys.DAMAGE_ADDED_MIN, Set.of(Tags.PHYSICAL, Tags.ATTACK)), 1e-9, "武器命中 400 × 25%");
        assertEquals(0, estimated.flat(StatKeys.DAMAGE_ADDED_MIN, Set.of(Tags.PHYSICAL, Tags.SPELL)), 1e-9, "法術沒有");
    }

    @Test
    void conditionalEffectsAreListedNotAdded() {
        final StatSheet sheet = new StatSheet();
        assertTrue(BuffImpact.conditionalNames(sheet).isEmpty());
        sheet.add(new Modifier(StatKeys.DAMAGE_VS_LOW_LIFE, ModType.INCREASED, 0.2, Set.of(), "天賦"));
        sheet.add(new Modifier(StatKeys.DAMAGE_VS_RARE, ModType.INCREASED, 0.1, Set.of(), "天賦"));
        sheet.add(new Modifier(StatKeys.DAMAGE_VS_UNIQUE, ModType.INCREASED, 0.1, Set.of(), "天賦"));
        assertEquals(List.of("對低血的敵人", "對稀有、傳奇"), BuffImpact.conditionalNames(sheet));
    }
}
