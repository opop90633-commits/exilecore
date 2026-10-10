package tw.exilecore.character;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
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
        // 重甲鬥士 32/14/14：只有長杖（智慧 22）和弓（敏捷 16）拿不動
        assertEquals(List.of("hand_axe*", "war_hammer*", "great_axe*", "great_hammer*",
                "short_sword", "dagger", "claw", "wand", "great_sword", "sceptre", "crossbow", "quarterstaff", "spear"),
                ids(CharacterClass.MARAUDER));
        // 重弩手 23/23/14：弩推薦；長杖拿不動
        assertEquals(List.of("crossbow*", "short_sword", "hand_axe", "war_hammer", "dagger", "claw", "wand", "great_sword",
                "great_axe", "bow", "sceptre", "quarterstaff", "spear"), ids(CharacterClass.MERCENARY));
        // 長矛獵手 18/28/14：矛推薦
        assertEquals(List.of("spear*", "short_sword", "hand_axe", "war_hammer", "dagger", "claw", "wand", "great_sword", "bow",
                "sceptre", "crossbow", "quarterstaff"), ids(CharacterClass.HUNTRESS));
        // 弓箭手 14/32/14：戰錘、巨斧、巨錘（力量）和長杖拿不動
        assertEquals(List.of("bow*", "dagger*", "claw*",
                "short_sword", "hand_axe", "wand", "great_sword", "sceptre", "crossbow", "quarterstaff", "spear"), ids(CharacterClass.RANGER));
        // 棍僧 14/23/23：長棍推薦
        assertEquals(List.of("quarterstaff*", "short_sword", "hand_axe", "dagger", "claw", "wand", "great_sword", "staff", "bow",
                "sceptre", "crossbow", "spear"), ids(CharacterClass.MONK));
        // 元素法師、混沌術士 14/14/32：推薦的順序不一樣
        assertEquals(List.of("staff*", "wand*",
                "short_sword", "hand_axe", "dagger", "claw", "great_sword", "sceptre", "crossbow", "quarterstaff", "spear"),
                ids(CharacterClass.SORCERESS));
        assertEquals(List.of("wand*", "staff*",
                "short_sword", "hand_axe", "dagger", "claw", "great_sword", "sceptre", "crossbow", "quarterstaff", "spear"),
                ids(CharacterClass.WITCH));
    }

    @Test
    void offeredWeaponsReallyWorkForTheClass() {
        for (final CharacterClass characterClass : CharacterClass.values()) {
            for (final StarterKits.Option option : StarterKits.weapons(items, config, characterClass)) {
                final ItemBase base = option.base();
                assertTrue(base.isWeapon());
                assertTrue(base.requirements().str() <= characterClass.strength()
                        && base.requirements().dex() <= characterClass.dexterity()
                        && base.requirements().intelligence() <= characterClass.intelligence(), characterClass + " " + base.id());
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
        assertEquals("heavy_strike", StarterKits.skill(config, marauder, items.base("hand_axe")));
        assertEquals("heavy_strike", StarterKits.skill(config, marauder, items.base("great_sword")));
        assertEquals("heavy_strike", StarterKits.skill(config, marauder, items.base("sceptre")));
        assertEquals("split_arrow", StarterKits.skill(config, CharacterClass.RANGER, items.base("bow")));
        assertEquals("explosive_bolt", StarterKits.skill(config, CharacterClass.MERCENARY, items.base("crossbow")));
        assertEquals("tempest_flurry", StarterKits.skill(config, CharacterClass.MONK, items.base("quarterstaff")));
        assertEquals("lightning_spear", StarterKits.skill(config, CharacterClass.HUNTRESS, items.base("spear")));
        // 法杖、長杖：混沌術士送混沌彈，其他職業送熾星彈
        assertEquals("fireball", StarterKits.skill(config, CharacterClass.SORCERESS, items.base("wand")));
        assertEquals("fireball", StarterKits.skill(config, CharacterClass.SORCERESS, items.base("staff")));
        assertEquals("fireball", StarterKits.skill(config, marauder, items.base("wand")));
        assertEquals("chaos_bolt", StarterKits.skill(config, CharacterClass.WITCH, items.base("wand")));
        assertEquals("chaos_bolt", StarterKits.skill(config, CharacterClass.WITCH, items.base("staff")));
        // 不看職業的武器：誰拿都一樣
        assertEquals("explosive_bolt", StarterKits.skill(config, CharacterClass.WITCH, items.base("crossbow")));
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
            final ItemBase shield = StarterKits.shield(items, config, characterClass, items.base("dagger"));
            assertNotNull(shield, characterClass + " 拿單手武器要送盾");
            assertEquals(ItemSlot.OFF_HAND, shield.slot());
            assertNull(StarterKits.shield(items, config, characterClass, items.base("great_sword")));
        }
        assertEquals("plate_body", StarterKits.armour(items, config, CharacterClass.MARAUDER).get(1).id());
        assertEquals("scale_body", StarterKits.armour(items, config, CharacterClass.MERCENARY).get(1).id());
        assertEquals("leather_body", StarterKits.armour(items, config, CharacterClass.HUNTRESS).get(1).id());
        assertEquals("leather_body", StarterKits.armour(items, config, CharacterClass.RANGER).get(1).id());
        assertEquals("wrap_body", StarterKits.armour(items, config, CharacterClass.MONK).get(1).id(), "棍僧：閃避＋能量護盾的纏布");
        assertEquals("silk_body", StarterKits.armour(items, config, CharacterClass.SORCERESS).get(1).id());
        assertEquals("silk_body", StarterKits.armour(items, config, CharacterClass.WITCH).get(1).id());
        assertEquals("tower_shield", StarterKits.shield(items, config, CharacterClass.MARAUDER, items.base("hand_axe")).id());
        assertEquals("kite_shield", StarterKits.shield(items, config, CharacterClass.MERCENARY, items.base("short_sword")).id());
        assertEquals("buckler", StarterKits.shield(items, config, CharacterClass.HUNTRESS, items.base("spear")).id(), "矛配小盾");
        assertEquals("buckler", StarterKits.shield(items, config, CharacterClass.RANGER, items.base("claw")).id());
        assertEquals("buckler", StarterKits.shield(items, config, CharacterClass.MONK, items.base("dagger")).id());
        assertEquals("spirit_shield", StarterKits.shield(items, config, CharacterClass.SORCERESS, items.base("wand")).id());
        assertEquals("spirit_shield", StarterKits.shield(items, config, CharacterClass.WITCH, items.base("wand")).id());
        assertNull(StarterKits.shield(items, config, CharacterClass.MONK, items.base("quarterstaff")), "長棍是雙手");
    }

    @Test
    void resolveFallsBackWhenTheChoiceIsNotAllowed() {
        // 選了拿不動的武器（例如設定重載後）：改送第一個能選的
        assertEquals("hand_axe", StarterKits.resolveWeapon(items, config, CharacterClass.MARAUDER, "staff").id());
        assertEquals("bow", StarterKits.resolveWeapon(items, config, CharacterClass.RANGER, "nope").id());
        assertEquals("staff", StarterKits.resolveWeapon(items, config, CharacterClass.WITCH, "staff").id());
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
                    witch: { recommended: [ sceptre ], shield: none }
                    ranger: { armour: [scale_helmet] }
                  skills:
                    - { weapon: mace, skill: cleave }
                """);
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        assertEquals(List.of("sceptre"), custom.starter(CharacterClass.WITCH).recommended());
        assertEquals(config.starter(CharacterClass.WITCH).armour(), custom.starter(CharacterClass.WITCH).armour(), "沒寫的項目用預設");
        assertNull(custom.starter(CharacterClass.WITCH).shield(), "none = 不送盾");
        assertEquals(List.of("scale_helmet"), custom.starter(CharacterClass.RANGER).armour());
        assertEquals("buckler", custom.starter(CharacterClass.RANGER).shield());
        assertEquals("tower_shield", custom.starter(CharacterClass.MARAUDER).shield(), "沒列的職業用預設");
        assertEquals("cleave", custom.starterSkill(CharacterClass.MARAUDER, Set.of("weapon", "one_hand", "mace", "melee")));
        assertNull(custom.starterSkill(CharacterClass.RANGER, Set.of("bow")));
        assertEquals("sceptre", StarterKits.weapons(items, custom, CharacterClass.WITCH).get(0).base().id());
        assertNull(StarterKits.shield(items, custom, CharacterClass.WITCH, items.base("wand")));

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
        assertEquals(2, StarterKits.skillProblems(config, id -> !id.equals("fireball")).size(), "法杖、長杖兩條規則都指到熾星彈");
    }

    @Test
    void problemsPointAtTyposAndUnusableGear(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("character.yml"), """
                starter:
                  classes:
                    marauder: { recommended: [staff, hand_axee], armour: [silk_body, buckler], shield: spirit_shield }
                """);
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        final List<String> problems = StarterKits.problems(items, custom);
        assertEquals(5, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).contains("staff") && problems.get(0).contains("拿不動"));
        assertTrue(problems.get(1).contains("hand_axee"));
        assertTrue(problems.get(2).contains("silk_body") && problems.get(2).contains("穿不上"));
        assertTrue(problems.get(3).contains("buckler") && problems.get(3).contains("不是防具"));
        assertTrue(problems.get(4).contains("spirit_shield"));
        // 推薦的武器全部不能用時，照基底順序列出能用的
        assertEquals("short_sword", StarterKits.weapons(items, custom, CharacterClass.MARAUDER).get(0).base().id());
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
        assertEquals("tower_shield", broken.starter(CharacterClass.MARAUDER).shield());
    }
}
