package tw.exilecore.character;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterKitTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static CharacterConfig config;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        config = new CharacterConfig();
        config.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
    }

    private static List<String> ids(final CharacterClass characterClass) {
        final List<String> list = new ArrayList<>();
        for (final StarterKits.Option option : StarterKits.weapons(items, config, characterClass)) {
            list.add(option.base().id() + (option.recommended() ? "*" : ""));
        }
        return list;
    }

    @Test
    void everyWeaponTheStartingAttributesAllowIsOfferedRecommendedFirst() {
        // 2026-10-26：法杖、長杖、權杖換成 PoE2 的（沒有屬性需求的第 1 把，誰都拿得動，排在最後）；2026-10：劍、斧、匕首、爪拿掉了；推薦的分階換成合併後的基底；每一類只列一把（物品等級 1 的屬性需求）
        // 2026-10-26 照 PoE2：每一類最低那一階（物品等級 1）都沒有屬性需求，起始屬性 29 點每一類都拿得動（這次之前照職業屬性有些拿不動）
        assertEquals(List.of("mace1_23*", "mace2_26*", "spear_16", "qstaff_21", "talis_15", "bow_14", "xbow_06", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.MARAUDER));
        assertEquals(List.of("xbow_23*", "mace1_09", "mace2_09", "spear_16", "qstaff_21", "talis_15", "bow_14", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.MERCENARY));
        assertEquals(List.of("spear_26*", "mace1_09", "mace2_09", "qstaff_21", "talis_15", "bow_14", "xbow_06", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.HUNTRESS));
        assertEquals(List.of("bow_23*", "mace1_09", "mace2_09", "spear_16", "qstaff_21", "talis_15", "xbow_06", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.RANGER));
        assertEquals(List.of("qstaff_25*", "mace1_09", "mace2_09", "spear_16", "talis_15", "bow_14", "xbow_06", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.MONK));
        // 元素法師、混沌術士：推薦的順序不一樣
        assertEquals(List.of("cstaff_01*", "cwand_01*", "mace1_09", "mace2_09", "spear_16", "qstaff_21", "talis_15", "bow_14", "xbow_06", "csceptre_01"),
                ids(CharacterClass.SORCERESS));
        assertEquals(List.of("cwand_01*", "cstaff_01*", "mace1_09", "mace2_09", "spear_16", "qstaff_21", "talis_15", "bow_14", "xbow_06", "csceptre_01"),
                ids(CharacterClass.WITCH));
        // 德魯伊：獸魂符推薦
        assertEquals(List.of("talis_18*", "mace1_09", "mace2_09", "spear_16", "qstaff_21", "bow_14", "xbow_06", "cwand_01", "cstaff_01", "csceptre_01"),
                ids(CharacterClass.DRUID));
    }

    @Test
    void offeredWeaponsReallyWorkForTheClass() {
        for (final CharacterClass characterClass : CharacterClass.values()) {
            for (final StarterKits.Option option : StarterKits.weapons(items, config, characterClass)) {
                final ItemBase base = option.base();
                assertTrue(base.isWeapon());
                // 屬性需求照物品等級縮放：看新手武器那個物品等級的需求
                final ItemBase.Requirements req = ItemStats.requirements(items, base, StarterKits.ITEM_LEVEL);
                assertTrue(req.str() <= characterClass.strength()
                        && req.dex() <= characterClass.dexterity()
                        && req.intelligence() <= characterClass.intelligence(), characterClass + " " + base.id());
                // 起始技能要存在、而且這把武器用得了
                final String skillId = StarterKits.skill(config, characterClass, base);
                assertNotNull(skillId, base.id() + " 沒有對到起始技能");
                final SkillDefinition skill = skills.skill(skillId);
                assertNotNull(skill, "skills.yml 沒有 " + skillId);
                assertFalse(skill.hidden());
                assertTrue(skill.usableWith(base.tags()), skillId + " 不能用 " + base.id());
            }
            assertNotNull(StarterKits.resolveWeapon(items, config, characterClass, null));
        }
    }

    @Test
    void starterSkillFollowsTheWeapon() {
        final CharacterClass marauder = CharacterClass.MARAUDER;
        // 錘送碎骨擊；其他近戰武器（例如拿掉的斧）送地震
        assertEquals("boneshatter", StarterKits.skill(config, marauder, items.base("mace1_23")));
        assertEquals("boneshatter", StarterKits.skill(config, marauder, items.base("mace2_26")));
        assertEquals("fireball", StarterKits.skill(config, marauder, items.base("csceptre_01")), "2026-10-26：權杖照 PoE2 沒有傷害（不是錘），送法術");
        assertEquals("essence_drain", StarterKits.skill(config, CharacterClass.WITCH, items.base("csceptre_01")));
        assertEquals("earthquake", StarterKits.skill(config, marauder, items.base("hand_axe")));
        assertEquals("lightning_arrow", StarterKits.skill(config, CharacterClass.RANGER, items.base("bow_23")));
        assertEquals("explosive_bolt", StarterKits.skill(config, CharacterClass.MERCENARY, items.base("xbow_23")));
        assertEquals("tempest_flurry", StarterKits.skill(config, CharacterClass.MONK, items.base("qstaff_25")));
        assertEquals("lightning_spear", StarterKits.skill(config, CharacterClass.HUNTRESS, items.base("spear_26")));
        assertEquals("bear_maul", StarterKits.skill(config, CharacterClass.DRUID, items.base("talis_18")));
        // 法杖、長杖：混沌術士送汲魂彈，其他職業送熾星彈
        assertEquals("fireball", StarterKits.skill(config, CharacterClass.SORCERESS, items.base("wand_14")));
        assertEquals("fireball", StarterKits.skill(config, CharacterClass.SORCERESS, items.base("staff_17")));
        assertEquals("fireball", StarterKits.skill(config, marauder, items.base("wand_14")));
        assertEquals("essence_drain", StarterKits.skill(config, CharacterClass.WITCH, items.base("wand_14")));
        assertEquals("essence_drain", StarterKits.skill(config, CharacterClass.WITCH, items.base("staff_17")));
        // 不看職業的武器：誰拿都一樣
        assertEquals("explosive_bolt", StarterKits.skill(config, CharacterClass.WITCH, items.base("xbow_23")));
        assertNull(StarterKits.skill(config, marauder, null));
    }

    @Test
    void armourSetAndShieldMatchTheClass() {
        final List<ItemSlot> slots = List.of(ItemSlot.HEAD, ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET);
        for (final CharacterClass characterClass : CharacterClass.values()) {
            final List<ItemBase> armour = StarterKits.armour(items, config, characterClass);
            assertEquals(4, armour.size(), characterClass + " 要有整套防具");
            for (int i = 0; i < 4; i++) {
                assertEquals(slots.get(i), armour.get(i).slot());
            }
            // 單手武器送盾、雙手武器不送
            final ItemBase shield = StarterKits.shield(items, config, characterClass, items.base("wand_14"));
            assertNotNull(shield, characterClass + " 拿單手武器要送盾");
            assertEquals(ItemSlot.OFF_HAND, shield.slot());
            assertNull(StarterKits.shield(items, config, characterClass, items.base("mace2_26")));
        }
        // 2026-10：設定寫的 *_01 分階換成合併後的基底
        assertEquals("body_str_25", StarterKits.armour(items, config, CharacterClass.MARAUDER).get(1).id());
        assertEquals("body_str_dex_27", StarterKits.armour(items, config, CharacterClass.MERCENARY).get(1).id());
        assertEquals("body_dex_22", StarterKits.armour(items, config, CharacterClass.HUNTRESS).get(1).id());
        assertEquals("body_dex_22", StarterKits.armour(items, config, CharacterClass.RANGER).get(1).id());
        assertEquals("body_dex_int_18", StarterKits.armour(items, config, CharacterClass.MONK).get(1).id(), "棍僧：閃避＋能量護盾");
        assertEquals("body_int_20", StarterKits.armour(items, config, CharacterClass.SORCERESS).get(1).id());
        assertEquals("body_int_20", StarterKits.armour(items, config, CharacterClass.WITCH).get(1).id());
        assertEquals("body_str_25", StarterKits.armour(items, config, CharacterClass.DRUID).get(1).id());
        assertEquals("tshield_24", StarterKits.shield(items, config, CharacterClass.MARAUDER, items.base("mace1_23")).id());
        assertEquals("kshield_22", StarterKits.shield(items, config, CharacterClass.MERCENARY, items.base("mace1_09")).id());
        assertEquals("buckler_23", StarterKits.shield(items, config, CharacterClass.HUNTRESS, items.base("spear_26")).id(), "矛配小盾");
        assertEquals("buckler_23", StarterKits.shield(items, config, CharacterClass.RANGER, items.base("spear_16")).id());
        assertEquals("buckler_23", StarterKits.shield(items, config, CharacterClass.MONK, items.base("spear_16")).id());
        assertEquals("focus_23", StarterKits.shield(items, config, CharacterClass.SORCERESS, items.base("wand_14")).id());
        assertEquals("focus_23", StarterKits.shield(items, config, CharacterClass.WITCH, items.base("wand_14")).id());
        assertNull(StarterKits.shield(items, config, CharacterClass.MONK, items.base("qstaff_25")), "長棍是雙手");
        assertNull(StarterKits.shield(items, config, CharacterClass.DRUID, items.base("talis_18")), "獸魂符是雙手");
    }

    @Test
    void resolveFallsBackWhenTheChoiceIsNotAllowed() {
        // 選了拿不動的武器（例如設定重載後）：改送第一個能選的
        assertEquals("mace1_23", StarterKits.resolveWeapon(items, config, CharacterClass.MARAUDER, "cstaff_14").id());
        assertEquals("bow_23", StarterKits.resolveWeapon(items, config, CharacterClass.RANGER, "nope").id());
        assertEquals("cstaff_01", StarterKits.resolveWeapon(items, config, CharacterClass.WITCH, "cstaff_01").id());
    }

    @Test
    void starterSectionIsOptionalAndMergesWithDefaults(@TempDir final Path dir) throws IOException {
        // 沒有 starter 區塊（自己改過 character.yml、新版放在 .new）：用內建的預設值，跟預設檔一樣
        Files.writeString(dir.resolve("character.yml"), "level_cap: 60\n");
        final CharacterConfig bare = new CharacterConfig();
        bare.load(dir);
        for (final CharacterClass characterClass : CharacterClass.values()) {
            assertEquals(config.starter(characterClass), bare.starter(characterClass));
        }
        assertEquals(config.starterSkills(), bare.starterSkills());

        Files.writeString(dir.resolve("character.yml"), """
                starter:
                  classes:
                    witch: { recommended: [ csceptre_01 ], shield: none }
                    ranger: { armour: [scale_helmet] }
                  skills:
                    - { weapon: mace, skill: cleave }
                """);
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        assertEquals(List.of("csceptre_01"), custom.starter(CharacterClass.WITCH).recommended());
        assertEquals(config.starter(CharacterClass.WITCH).armour(), custom.starter(CharacterClass.WITCH).armour(), "沒寫的項目用預設");
        assertNull(custom.starter(CharacterClass.WITCH).shield(), "none = 不送盾");
        assertEquals(List.of("scale_helmet"), custom.starter(CharacterClass.RANGER).armour());
        assertEquals("buckler_01", custom.starter(CharacterClass.RANGER).shield());
        assertEquals("tshield_01", custom.starter(CharacterClass.MARAUDER).shield(), "沒列的職業用預設");
        assertEquals("cleave", custom.starterSkill(CharacterClass.MARAUDER, Set.of("weapon", "one_hand", "mace", "melee")));
        assertNull(custom.starterSkill(CharacterClass.RANGER, Set.of("bow")));
        assertEquals("csceptre_01", StarterKits.weapons(items, custom, CharacterClass.WITCH).get(0).base().id());
        assertNull(StarterKits.shield(items, custom, CharacterClass.WITCH, items.base("wand_14")));

        // 重載成沒有 starter 的檔案：回到預設，不留上一次的自訂
        Files.writeString(dir.resolve("character.yml"), "level_cap: 60\n");
        custom.load(dir);
        assertEquals(config.starter(CharacterClass.WITCH), custom.starter(CharacterClass.WITCH));
        assertEquals(config.starterSkills(), custom.starterSkills());
    }

    @Test
    void defaultDataHasNoStarterProblems() {
        assertEquals(List.of(), StarterKits.problems(items, config));
        assertEquals(List.of(), StarterKits.skillProblems(config, id -> skills.skill(id) != null && !skills.skill(id).hidden()));
        assertEquals(3, StarterKits.skillProblems(config, id -> !id.equals("fireball")).size(), "法杖、長杖、權杖三條規則都指到熾星彈");
    }

    @Test
    void problemsPointAtTyposAndUnusableGear(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("character.yml"), """
                starter:
                  classes:
                    marauder: { recommended: [cstaff_14, hand_axee], armour: [helm_str_dex_01, buckler_23], shield: cstaff_14 }
                """);
        // 2026-10-26 照 PoE2 需求：法袍、法器最低那一階沒有屬性需求，重甲鬥士也穿得上；改用 1 級穿不上的力敏頭盔（最低那一階要 5 級）、
        // 副手寫不是盾的東西（這次之前 armour: [body_int_20, ...]、shield: focus_23）
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        final List<String> problems = StarterKits.problems(items, custom);
        assertEquals(5, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).contains("cstaff_14") && problems.get(0).contains("拿不動"));
        assertTrue(problems.get(1).contains("hand_axee"));
        assertTrue(problems.get(2).contains("helm_str_dex_01") && problems.get(2).contains("穿不上"));
        assertTrue(problems.get(3).contains("buckler_23") && problems.get(3).contains("不是防具"));
        assertTrue(problems.get(4).contains("cstaff_14"));
        // 推薦的武器全部不能用時，照基底順序列出能用的（每一類第一把）
        assertEquals("mace1_09", StarterKits.weapons(items, custom, CharacterClass.MARAUDER).get(0).base().id());
    }

    @Test
    void starterSkillRulesCanBeLimitedToOneClass(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("character.yml"), """
                starter:
                  skills:
                    - { weapon: wand, skill: spark, class: sorceress }
                    - { weapon: wand, skill: fireball }
                """);
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        assertEquals("spark", custom.starterSkill(CharacterClass.SORCERESS, Set.of("wand")));
        assertEquals("fireball", custom.starterSkill(CharacterClass.WITCH, Set.of("wand")), "別的職業跳過這條");
        assertEquals("sorceress", custom.starterSkills().get(0).classId());
        assertNull(custom.starterSkills().get(1).classId());

        Files.writeString(dir.resolve("character.yml"), "starter:\n  skills:\n    - { weapon: wand, skill: spark, class: paladin }\n");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new CharacterConfig().load(dir));
        assertTrue(error.getMessage().contains("paladin"));
    }

    @Test
    void unknownClassInStarterSectionIsRejected(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("character.yml"), "starter:\n  classes:\n    paladin: { recommended: [sceptre] }\n");
        final CharacterConfig broken = new CharacterConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains("paladin"));
        // 寫錯時保留原本的設定
        assertEquals("tshield_01", broken.starter(CharacterClass.MARAUDER).shield());
    }
}
