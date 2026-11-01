package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照站長「有些技能有增益但快捷欄沒顯示時間」：增益代號歸哪一個技能格（SkillService.ownsBuff）。 */
class SlotBuffOwnerTest {

    @Test
    void sameIdAndSubBuffsBelongToTheSkill() {
        assertTrue(SkillService.ownsBuff("collapse_heart_chant", List.of(), "collapse_heart_chant", null));
        assertTrue(SkillService.ownsBuff("war_banner", List.of(), "war_banner.tree", null), "戰旗的天賦樹那份");
        assertTrue(SkillService.ownsBuff("bone_offering", List.of(), "bone_offering.unwilling", null), "不情願的獻祭");
        assertTrue(SkillService.ownsBuff("wing_blade_slash", List.of(), StageAttacks.REND_BUFF, null), "翼刃帶電");
        assertTrue(SkillService.ownsBuff("miasma_zone", List.of(), StageAttacks.BLOOM_BUFF, null), "毒花");
        assertTrue(SkillService.ownsBuff("mirage_spirit_spring", List.of(), "mirage_spirit_spring.quality", null));
        assertTrue(SkillService.ownsBuff("mirage_spirit_haven", List.of(), "mirage_spirit_haven.in", null));
        assertFalse(SkillService.ownsBuff("mirage_spirit", List.of(), "mirage_spirit_haven.in", null), "代號只是開頭一樣的不算");
    }

    @Test
    void prefixedBuffsBelongToTheSkill() {
        assertTrue(SkillService.ownsBuff("frost_arrowhead", List.of(), "guard.frost_arrowhead", null));
        assertTrue(SkillService.ownsBuff("beast_blood_boil", List.of(), "fury.beast_blood_boil", null), "獸血沸的狂暴");
        assertTrue(SkillService.ownsBuff("thunder_lure_seal", List.of(), MarkActivation.BUFF_PREFIX + "thunder_lure_seal", null));
        assertFalse(SkillService.ownsBuff("seal", List.of(), MarkActivation.BUFF_PREFIX + "thunder_lure_seal", null), "前綴後面要整個是技能代號");
        assertTrue(SkillService.ownsBuff("collapse_heart_chant", List.of(), "purple_flames", null), "紫焰");
        assertTrue(SkillService.ownsBuff("rite_offering", List.of(), "ritual.self", null));
        assertFalse(SkillService.ownsBuff("collapse_heart_chant", List.of(), "charm.thawing", null));
    }

    @Test
    void registeredOwnerWins() {
        assertTrue(SkillService.ownsBuff("raise_shield", List.of(), "guard", "raise_shield"));
        assertFalse(SkillService.ownsBuff("parry", List.of(), "guard", "raise_shield"), "共用的 guard 只畫在最後給的那一格");
        assertTrue(SkillService.ownsBuff("magic_surge", List.of(), ManaTempest.BUFF_ID, "magic_surge"));
        assertTrue(SkillService.ownsBuff("hellfire_war_shout", List.of(), "warcry_support:howl_aim", "hellfire_war_shout"));
        assertFalse(SkillService.ownsBuff("hellfire_war_shout", List.of(), "warcry_support:howl_aim", null), "沒登記的戰吼輔助那份留在旁邊");
        assertFalse(SkillService.ownsBuff("guard", List.of(), "guard.x", "x"), "有登記就只看登記的");
    }

    @Test
    void socketedSupportBuffsBelongToTheSlot() {
        assertTrue(SkillService.ownsBuff("ice_nova", List.of("frost_shell"), "frost_shell", null), "冰淞");
        assertTrue(SkillService.ownsBuff("ice_nova", java.util.Arrays.asList(null, "flawless_form"), "support.flawless_form", null));
        assertFalse(SkillService.ownsBuff("ice_nova", List.of(), "frost_shell", null), "沒插冰淞的格子不算");
        assertFalse(SkillService.ownsBuff("ice_nova", null, "frost_shell", null));
    }
}
