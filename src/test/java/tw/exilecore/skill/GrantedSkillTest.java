package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：法杖、長杖、權杖賦予的技能（PoE2 的 Grants Skill）。 */
class GrantedSkillTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        config = new SkillConfig();
        config.load(DATA);
    }

    @Test
    void everyCasterBaseGrantsAnExistingSkill() {
        int casters = 0;
        for (final ItemBase base : items.bases()) {
            if (base.hasTag("wand") || base.hasTag("staff") || base.hasTag("sceptre")) {
                casters++;
                final String skill = items.grantedSkill(base.id());
                assertNotNull(skill, base.id() + " 要賦予技能");
                assertNotNull(config.skill(skill), base.id() + " 賦予的 " + skill + " 不存在");
                assertTrue(base.damage().isEmpty(), base.id() + " 照 PoE2 沒有傷害");
            }
        }
        assertEquals(14 + 17 + 16, casters);
        for (final String skill : items.grantedSkills().values()) {
            assertFalse(config.skill(skill).hidden(), skill);
        }
    }

    @Test
    void oldCasterBasesFollowTheMergeChain() {
        // wand_01 → wand_14 → cwand_13（兩層合併；2026-10-29 照站長：舊的換成原作施法基底最高階，這次之前到 1 等的 cwand_01、cstaff_01、csceptre_01）
        assertEquals("cwand_13", items.canonicalBase("wand_01"));
        assertEquals("entwine_rot_arrow", items.grantedSkill("wand_01"));
        assertEquals("entwine_rot_arrow", items.grantedSkill("wand_14"));
        assertEquals("cold_heart", items.grantedSkill("staff_17"));
        assertEquals("summon_skeletons", items.grantedSkill("sceptre_16"));
        assertEquals("scorching_sun_pearl", items.grantedSkill("cstaff_05"));
        assertNull(items.grantedSkill("mace1_23"));
        assertNull(items.grantedSkill(null));
    }

    @Test
    void grantedOnlySkillsHaveNoGem() {
        for (final String id : java.util.List.of("erosion_star_shot", "flame_star_shot", "law_shield", "evil_thought", "summon_skeletons")) {
            assertEquals("weapon", config.skill(id).source(), id);
            assertFalse(config.skill(id).engravable(), id + " 沒有寶石，雕不出來");
        }
        // 2026-10-28 照 PoE2 0.5（RePoE skill_gems：Solar Orb crafting_level 0）：烈陽珠只有長杖賦予，沒有寶石（這次之前雕得出來）
        assertEquals("weapon", config.skill("scorching_sun_pearl").source());
        assertFalse(config.skill("scorching_sun_pearl").engravable(), "烈陽珠沒有寶石");
        assertEquals(0, config.skill("erosion_star_shot").mana().last(), "武器賦予的不耗魔力");
    }

    @Test
    void loadoutAddsGrantedSkillsAndKeepsThemWhenTheWeaponLeaves() {
        final SkillLoadout loadout = new SkillLoadout();
        assertTrue(loadout.syncGranted(Map.of("erosion_star_shot", 5)));
        final int position = loadout.find("erosion_star_shot");
        assertTrue(position >= 0);
        assertTrue(loadout.skill(position).granted());
        assertEquals(5, loadout.skill(position).level());
        assertFalse(loadout.dormant(loadout.skill(position)));
        // 等級跟著武器
        assertTrue(loadout.syncGranted(Map.of("erosion_star_shot", 9)));
        assertEquals(9, loadout.skill(position).level());
        assertFalse(loadout.syncGranted(Map.of("erosion_star_shot", 9)), "沒變就不用存");
        // 綁上按鍵，拿掉武器：留著但不能用（按鍵當普攻）
        assertTrue(loadout.bind(3, position));
        assertNotNull(loadout.boundSkill(3));
        loadout.syncGranted(Map.of());
        assertTrue(loadout.dormant(loadout.skill(position)));
        assertNull(loadout.boundSkill(3));
        assertEquals(position, loadout.find("erosion_star_shot"), "輔助、按鍵都留著");
        // 2026-11-05 照站長：已經有寶石的技能也另外放一格賦予的（項鍊的暴擊引星＋寶石的暴擊引星同時存在）；
        //   這次之前（2026-10-28）有寶石就不另外放
        final SkillLoadout gem = new SkillLoadout();
        gem.put("roaming_thunder", 12);
        assertTrue(gem.syncGranted(Map.of("roaming_thunder", 3)));
        assertEquals(2, gem.count("roaming_thunder"));
        final int granted = gem.findGranted("roaming_thunder");
        assertTrue(granted >= 0);
        assertEquals(3, gem.skill(granted).level());
        assertEquals(12, gem.skill(gem.find("roaming_thunder")).level(), "都沒綁時照面板順序（寶石那格）");
        assertTrue(gem.bind(2, granted));
        assertEquals(granted, gem.find("roaming_thunder"), "綁著的那格先");
    }

    @Test
    void amuletGrantFreeReservationAndCopies() {
        // 2026-11-05 照站長（空無墜飾）：項鍊賦予的不用保留精魂；寶石那格照常；兩顆寶石照舊不行
        assertEquals(7, items.grantsOneOf("jamulet_absent").size());
        for (final String id : items.grantsOneOf("jamulet_absent")) {
            assertNotNull(config.skill(id), "空無墜飾的技能 " + id);
        }
        assertTrue(items.grantsNoReservation("jamulet_absent"));
        final tw.exilecore.item.ItemData amulet = new tw.exilecore.item.ItemData("jamulet_absent", tw.exilecore.item.Rarity.NORMAL, 70);
        items.rollGrant(amulet, new java.util.Random(1));
        final String picked = items.pickedGrant(amulet);
        assertTrue(items.grantsOneOf("jamulet_absent").contains(picked));
        assertEquals(picked, items.pickedGrant(amulet.copy()), "複製的同一個");
        final SkillLoadout loadout = new SkillLoadout();
        loadout.put("critical_guide_star", 20);
        loadout.syncGranted(Map.of("critical_guide_star", 18));
        loadout.setReservationFree(java.util.Set.of("critical_guide_star"));
        final int granted = loadout.findGranted("critical_guide_star");
        assertTrue(loadout.reservationFree(loadout.skill(granted)));
        assertFalse(loadout.reservationFree(loadout.skill(loadout.find("critical_guide_star"))));
        assertNull(GemCopies.placeProblem(config, new SkillLoadout(), new SkillLoadout.Entry("critical_guide_star", 1), 0));
        final SkillLoadout onlyGranted = new SkillLoadout();
        onlyGranted.syncGranted(Map.of("critical_guide_star", 18));
        assertNull(GemCopies.placeProblem(config, onlyGranted, new SkillLoadout.Entry("critical_guide_star", 20), 5), "項鍊那格不擋寶石");
        assertNotNull(GemCopies.placeProblem(config, loadout, new SkillLoadout.Entry("critical_guide_star", 20), 7), "兩顆寶石不行");
    }

    @Test
    void grantedFlagSurvivesEncoding() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("erosion_star_shot", 4).withGranted(true);
        final SkillLoadout.Entry decoded = SkillLoadout.Entry.decode("erosion_star_shot", 4, entry.encodeSupports());
        assertTrue(decoded.granted());
        assertFalse(SkillLoadout.Entry.decode("erosion_star_shot", 4, new SkillLoadout.Entry("erosion_star_shot", 4).encodeSupports()).granted());
    }

    @Test
    void specialGrantedSkillsFollowRef() {
        assertEquals(MetaSpec.Kind.SPELL_CAST, config.meta("incant_hurl").kind(), "咒擲：自己施法給能量");
        final BarrageSpec unleash = config.barrage("seal_release");
        assertTrue(unleash.spells(), "封釋強化法術");
        assertEquals(2, unleash.repeats());
        assertTrue(unleash.empowersSkill(config.skill("blazing_star_shell"), java.util.Set.of(), 0));
        assertFalse(unleash.empowersSkill(config.skill("sky_column_thunder"), java.util.Set.of(), 0), "有冷卻的不行（2026-10-26：霜綻拿掉了，改用天柱雷）");
        assertFalse(unleash.empowersSkill(config.skill("spine_crush"), java.util.Set.of("mace"), 0), "攻擊不行");
        assertEquals(90, config.levelModifiers("thunder_resonance").get(0).value().at(1), 1e-9);
        assertEquals(71, config.levelModifiers("thunder_resonance").get(0).value().at(20), 1e-9);
        assertEquals(2.0, config.levelModifiers("light_bend_mirror").get(0).value().at(20), 1e-9);
        assertEquals("incant_hurl", items.grantedSkill("cwand_14"));
        assertEquals("light_bend_mirror", items.grantedSkill("cstaff_17"));
        assertEquals("thunder_resonance", items.grantedSkill("csceptre_16"));
        final SkillConfig.ChargeCost cost = config.chargeCost("hero_spirit_totem");
        assertEquals("endurance", cost.charge());
        assertEquals(3, cost.count());
        assertEquals(0, config.skill("hero_spirit_totem").cooldown(), 1e-9, "英靈圖騰照原作沒有冷卻");
        assertEquals(0, config.skill("thunder_escape").cooldown(), 1e-9, "雷遁照原作沒有冷卻");
        for (final String removed : java.util.List.of("frost_orb", "static_field", "titan_slam", "miasma_arrow", "sweeping_repel", "lunge", "void_bolt")) {
            assertNull(config.skill(removed), removed + " 已移除");
        }
    }
}
