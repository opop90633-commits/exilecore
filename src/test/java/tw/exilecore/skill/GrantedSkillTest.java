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
        assertEquals("coiling_bolts", items.grantedSkill("wand_01"));
        assertEquals("coiling_bolts", items.grantedSkill("wand_14"));
        assertEquals("heart_of_ice", items.grantedSkill("staff_17"));
        assertEquals("summon_skeletons", items.grantedSkill("sceptre_16"));
        assertEquals("sun_orb", items.grantedSkill("cstaff_05"));
        assertNull(items.grantedSkill("mace1_23"));
        assertNull(items.grantedSkill(null));
    }

    @Test
    void grantedOnlySkillsHaveNoGem() {
        for (final String id : java.util.List.of("chaos_bolt", "firebolt", "discipline", "malice", "summon_skeletons")) {
            assertEquals("weapon", config.skill(id).source(), id);
            assertFalse(config.skill(id).engravable(), id + " 沒有寶石，雕不出來");
        }
        // 2026-10-28 照 PoE2 0.5（RePoE skill_gems：Solar Orb crafting_level 0）：烈陽珠只有長杖賦予，沒有寶石（這次之前雕得出來）
        assertEquals("weapon", config.skill("sun_orb").source());
        assertFalse(config.skill("sun_orb").engravable(), "烈陽珠沒有寶石");
        assertEquals(0, config.skill("chaos_bolt").mana().last(), "武器賦予的不耗魔力");
    }

    @Test
    void loadoutAddsGrantedSkillsAndKeepsThemWhenTheWeaponLeaves() {
        final SkillLoadout loadout = new SkillLoadout();
        assertTrue(loadout.syncGranted(Map.of("chaos_bolt", 5)));
        final int position = loadout.find("chaos_bolt");
        assertTrue(position >= 0);
        assertTrue(loadout.skill(position).granted());
        assertEquals(5, loadout.skill(position).level());
        assertFalse(loadout.dormant(loadout.skill(position)));
        // 等級跟著武器
        assertTrue(loadout.syncGranted(Map.of("chaos_bolt", 9)));
        assertEquals(9, loadout.skill(position).level());
        assertFalse(loadout.syncGranted(Map.of("chaos_bolt", 9)), "沒變就不用存");
        // 綁上按鍵，拿掉武器：留著但不能用（按鍵當普攻）
        assertTrue(loadout.bind(3, position));
        assertNotNull(loadout.boundSkill(3));
        loadout.syncGranted(Map.of());
        assertTrue(loadout.dormant(loadout.skill(position)));
        assertNull(loadout.boundSkill(3));
        assertEquals(position, loadout.find("chaos_bolt"), "輔助、按鍵都留著");
        // 已經有寶石的技能不另外放（2026-10-28：游雷有寶石、傳奇也賦予；這次之前用烈陽珠，照 PoE2 它沒有寶石）
        final SkillLoadout gem = new SkillLoadout();
        gem.put("spark", 12);
        assertFalse(gem.syncGranted(Map.of("spark", 3)));
        assertFalse(gem.skill(gem.find("spark")).granted());
        assertEquals(12, gem.skill(gem.find("spark")).level());
    }

    @Test
    void grantedFlagSurvivesEncoding() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("chaos_bolt", 4).withGranted(true);
        final SkillLoadout.Entry decoded = SkillLoadout.Entry.decode("chaos_bolt", 4, entry.encodeSupports());
        assertTrue(decoded.granted());
        assertFalse(SkillLoadout.Entry.decode("chaos_bolt", 4, new SkillLoadout.Entry("chaos_bolt", 4).encodeSupports()).granted());
    }

    @Test
    void specialGrantedSkillsFollowPoe2() {
        assertEquals(MetaSpec.Kind.SPELL_CAST, config.meta("spellslinger").kind(), "咒擲：自己施法給能量");
        final BarrageSpec unleash = config.barrage("unleash_spell");
        assertTrue(unleash.spells(), "封釋強化法術");
        assertEquals(2, unleash.repeats());
        assertTrue(unleash.empowersSkill(config.skill("fireball"), java.util.Set.of(), 0));
        assertFalse(unleash.empowersSkill(config.skill("lightning_pillar"), java.util.Set.of(), 0), "有冷卻的不行（2026-10-26：霜綻拿掉了，改用天柱雷）");
        assertFalse(unleash.empowersSkill(config.skill("boneshatter"), java.util.Set.of("mace"), 0), "攻擊不行");
        assertEquals(90, config.levelModifiers("fulmination").get(0).value().at(1), 1e-9);
        assertEquals(71, config.levelModifiers("fulmination").get(0).value().at(20), 1e-9);
        assertEquals(2.0, config.levelModifiers("mirror_of_refraction").get(0).value().at(20), 1e-9);
        assertEquals("spellslinger", items.grantedSkill("cwand_14"));
        assertEquals("mirror_of_refraction", items.grantedSkill("cstaff_17"));
        assertEquals("fulmination", items.grantedSkill("csceptre_16"));
        final SkillConfig.ChargeCost cost = config.chargeCost("ancestral_totem");
        assertEquals("endurance", cost.charge());
        assertEquals(3, cost.count());
        assertEquals(0, config.skill("ancestral_totem").cooldown(), 1e-9, "英靈圖騰照原作沒有冷卻");
        assertEquals(0, config.skill("storm_blink").cooldown(), 1e-9, "雷遁照原作沒有冷卻");
        for (final String removed : java.util.List.of("frost_orb", "static_field", "titan_slam", "miasma_arrow", "sweeping_repel", "lunge", "void_bolt")) {
            assertNull(config.skill(removed), removed + " 已移除");
        }
    }
}
