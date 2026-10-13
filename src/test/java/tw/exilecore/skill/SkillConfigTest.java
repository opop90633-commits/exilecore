package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** data/skills.yml 與技能的數值（等級、傷害、間隔）。 */
class SkillConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    @Test
    void shippedSkillsLoad() {
        // 2026-10 PoE2 改版：技能大改（250 個看得到的技能），這裡抽各種武器、運作方式的代表
        final List<String> visible = config.visible().stream().map(SkillDefinition::id).toList();
        assertEquals(271, visible.size(), "250 個 + 巔峰 Boss 的 6 個專屬技能寶石 + 2026-10 主要寶石 7 個（星咒圖騰、6 個引星）、星律、斬風旋、幻元擊、4 根法術圖騰、幻元箭");
        assertTrue(visible.containsAll(List.of("leap_slam", "earthquake", "boneshatter", "ancestral_totem", "flicker_strike",
                "caustic_arrow", "rain_of_arrows", "lightning_arrow", "ice_shot", "tornado_shot",
                "explosive_bolt", "permafrost_bolts", "rapid_fire", "explosive_grenade", "gas_grenade",
                "tempest_flurry", "ice_strike", "storm_wave", "killing_palm", "tempest_bell",
                "lightning_spear", "whirling_slash", "whirlwind_lance", "lunge", "hunters_mark",
                "fireball", "frost_nova", "spark", "arc", "comet", "flame_wall", "shockwave_totem",
                "essence_drain", "contagion", "detonate_dead", "raise_zombie", "summon_skeletons", "raging_spirits",
                "herald_of_ash", "herald_of_thunder", "herald_of_ice", "fortifying_cry", "infernal_cry", "seismic_cry", "bear_maul")),
                visible.toString());
        assertEquals("leap_slam", visible.getFirst(), "照檔案裡的順序");
        assertEquals(332, config.all().size(), "271 個技能 + 7 種普攻 + 54 個隱藏的效果（使者、觸發…）");
        for (final SkillDefinition definition : config.all()) {
            assertTrue(SkillConfig.VISUALS.contains(definition.visual()), definition.id());
            if (definition.mechanic() == Mechanic.WARCRY) {
                // 戰吼不算法術（2026-10）：type 寫 spell（照施法時間），但沒有 spell 標籤，也不是攻擊
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals("戰吼", definition.typeName(), definition.id());
            } else if (definition.summon() != null) {
                // 召喚技能不算法術（2026-10）：沒有 spell 標籤，種類寫「召喚」
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
                assertEquals("召喚", definition.typeName(), definition.id());
            } else if (definition.mechanic() == Mechanic.AURA && !definition.tags().contains(Tags.HERALD)) {
                // 光環、常駐技能不算法術（2026-10）：type 寫 spell（照施法時間），但沒有 spell 標籤；捷光環例外
                assertFalse(definition.tags().contains(Tags.SPELL) || definition.tags().contains(Tags.ATTACK), definition.id());
            } else {
                assertTrue(definition.tags().contains(definition.isAttack() ? Tags.ATTACK : Tags.SPELL), definition.id());
                assertEquals(definition.isAttack() ? "攻擊" : "法術", definition.typeName(), definition.id());
            }
            if (definition.hidden()) {
                assertEquals(0, definition.mana().last(), definition.id() + " 普攻、捷的效果不耗魔力");
            } else if (definition.mechanic() == Mechanic.AURA) {
                assertEquals(0, definition.mana().last(), definition.id() + " 光環用保留的，不耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else if (definition.id().endsWith("_banner") || config.mount(definition.id()) != null) {
                // 2026-10：旗幟（靠冷卻）與坐騎不耗魔力
                assertEquals(0, definition.mana().last(), definition.id() + " 旗幟、坐騎不耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
            } else {
                assertTrue(definition.mana().first() > 0, definition.id() + " 要耗魔力");
                assertFalse(definition.description().isBlank(), definition.id() + " 要有說明");
                assertNotNull(definition.icon());
            }
            if (definition.isAttack()) {
                assertNull(definition.damage(), definition.id());
            } else if (definition.dealsDamage()) {
                assertNotNull(definition.damage(), definition.id());
                assertEquals(1.0, definition.damageTypes().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
            } else {
                assertNull(definition.damage(), definition.id() + " 不造成傷害的法術（位移、印記）不寫 damage");
            }
        }
        // 弓的技能都用原版箭矢、需要弓；法術什麼武器都能放
        for (final String id : List.of("lightning_arrow", "ice_shot")) {
            assertTrue(config.skill(id).params().arrow(), id);
            assertEquals(Set.of(Tags.BOW), config.skill(id).weapons(), id);
        }
        for (final String id : List.of("fireball", "frost_nova", "spark", "arc", "essence_drain", "contagion")) {
            assertTrue(config.skill(id).weapons().isEmpty(), id);
        }
        // 新武器的技能：弩用原版箭矢；長棍、矛的只有那種武器能用
        for (final String id : List.of("explosive_bolt", "permafrost_bolts")) {
            assertTrue(config.skill(id).params().arrow(), id);
            assertEquals(Set.of(Tags.CROSSBOW), config.skill(id).weapons(), id);
        }
        assertEquals(Set.of(Tags.QUARTERSTAFF), config.skill("tempest_flurry").weapons());
        assertEquals(Set.of(Tags.QUARTERSTAFF), config.skill("ice_strike").weapons());
        assertEquals(Set.of(Tags.SPEAR), config.skill("lightning_spear").weapons());
        assertEquals(Set.of(Tags.SPEAR), config.skill("whirling_slash").weapons());
        assertTrue(config.skill("explosive_bolt").params().explode().first() > 0, "爆裂弩箭會爆炸");
        assertEquals(java.util.Map.of(tw.exilecore.stats.DamageType.CHAOS, 1.0), config.skill("essence_drain").damageTypes());
    }

    @Test
    void thrownSpearIsAProjectileNotMelee() {
        final Set<String> spear = Set.of("weapon", "one_hand", "spear", "melee");
        final tw.exilecore.stats.SkillProfile thrown = SkillProfiles.build(config, config.skill("lightning_spear"), 1, spear);
        assertTrue(thrown.tags().contains(Tags.PROJECTILE));
        assertFalse(thrown.tags().contains(Tags.MELEE), "擲出去的矛不吃近戰的加成");
        assertTrue(thrown.tags().contains(Tags.SPEAR), "武器的標籤照樣有：增加矛傷害吃得到");
        final tw.exilecore.stats.SkillProfile whirl = SkillProfiles.build(config, config.skill("whirling_slash"), 1, spear);
        assertTrue(whirl.tags().contains(Tags.MELEE), "旋矛擊是近戰");
    }

    @Test
    void everyClassHasAStarterSkill() {
        assertEquals("boneshatter", config.starter(CharacterClass.MARAUDER.id()).id());
        assertEquals("explosive_bolt", config.starter(CharacterClass.MERCENARY.id()).id());
        assertEquals("lightning_spear", config.starter(CharacterClass.HUNTRESS.id()).id());
        assertEquals("lightning_arrow", config.starter(CharacterClass.RANGER.id()).id());
        assertEquals("tempest_flurry", config.starter(CharacterClass.MONK.id()).id());
        assertEquals("fireball", config.starter(CharacterClass.SORCERESS.id()).id());
        assertEquals("essence_drain", config.starter(CharacterClass.WITCH.id()).id());
        for (final CharacterClass characterClass : CharacterClass.values()) {
            assertNotNull(config.starter(characterClass.id()), characterClass + " 沒有起始技能");
        }
        assertNull(config.starter("nobody"));
        // 起始技能要能用職業的起始武器（2026-10：重甲鬥士的新手武器是錘）
        assertTrue(config.starter("marauder").usableWith(Set.of("weapon", "one_hand", Tags.MACE, Tags.MELEE)));
    }

    @Test
    void defaultAttackFollowsTheWeapon() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(DATA);
        int ranged = 0;
        for (final ItemBase base : items.bases()) {
            if (!base.isWeapon()) {
                continue;
            }
            assertTrue(base.hasTag(Tags.MELEE) ^ base.hasTag(Tags.RANGED), base.id() + " 要是近戰或遠程其中一種");
            final String expected;
            if (base.hasTag(Tags.BOW)) {
                expected = SkillConfig.DEFAULT_BOW;
            } else if (base.hasTag(Tags.CROSSBOW)) {
                expected = SkillConfig.DEFAULT_CROSSBOW;
            } else if (base.hasTag(Tags.STAFF)) {
                expected = SkillConfig.DEFAULT_STAFF;
            } else if (base.hasTag(Tags.WAND)) {
                expected = SkillConfig.DEFAULT_WAND;
            } else if (base.hasTag(Tags.QUARTERSTAFF)) {
                expected = SkillConfig.DEFAULT_QUARTERSTAFF;
            } else if (base.hasTag(Tags.SPEAR)) {
                expected = SkillConfig.DEFAULT_SPEAR;
            } else {
                expected = SkillConfig.DEFAULT_MELEE;
            }
            assertEquals(expected, config.defaultAttack(base.tags()).id(), base.id());
            if (base.hasTag(Tags.RANGED)) {
                ranged++;
                assertTrue(SkillProfile.defaultAttack(base.tags()).tags().contains(Tags.PROJECTILE), base.id());
            }
        }
        assertEquals(items.bases().stream().filter(ItemBase::isWeapon)
                .filter(base -> base.hasTag(Tags.BOW) || base.hasTag(Tags.CROSSBOW) || base.hasTag(Tags.WAND) || base.hasTag(Tags.STAFF))
                .count(), ranged, "遠程只有弓、弩、法杖、長杖");
        assertEquals(16, ranged, "弓、弩、法杖、長杖（2026-10 各分好幾階）");
        // 長棍打兩個、矛刺得比較遠
        assertEquals(2, config.skill(SkillConfig.DEFAULT_QUARTERSTAFF).params().targets());
        assertTrue(config.skill(SkillConfig.DEFAULT_SPEAR).params().reach() > config.skill(SkillConfig.DEFAULT_MELEE).params().reach());
        assertEquals(SkillConfig.DEFAULT_MELEE, config.defaultAttack(Set.of()).id(), "徒手");
    }

    @Test
    void levelRequirementsAndGrowth() {
        assertEquals(1, config.requiredLevel(1));
        assertEquals(50, config.requiredLevel(18), "劇情打完（60 級）是 18 級");
        assertEquals(64, config.requiredLevel(19), "19 級要 64 級（T4 地圖）");
        assertEquals(80, config.requiredLevel(20), "20 級要 80 級（T14 地圖）");
        assertEquals(1, config.maxLevelFor(1));
        assertEquals(5, config.maxLevelFor(10));
        assertEquals(18, config.maxLevelFor(60));
        assertEquals(19, config.maxLevelFor(79));
        assertEquals(20, config.maxLevelFor(100));
        assertEquals(19, config.uncutLevelFor(70), "T8 地圖掉 19 級的未切割寶石");
        assertEquals(20, config.uncutLevelFor(84), "T14 以上的地圖才掉 20 級");
        assertEquals(1.05, config.spellGrowth(1), 1e-9);
        assertEquals(1.05 * Math.pow(1.13 / 1.05, 4 / 9.0), config.spellGrowth(5), 1e-9);   // 等比內插
        assertEquals(7.01, config.spellGrowth(64), 1e-9);
        assertEquals(9.23, config.spellGrowth(80), 1e-9);
        assertEquals(9.23, config.spellGrowth(100), 1e-9, "超過最後一個點取端點");
    }

    @Test
    void profilesScaleWithLevel() {
        final SkillDefinition fireball = config.skill("fireball");
        final SkillProfile level1 = SkillProfiles.build(config, fireball, 1, Set.of(Tags.SWORD));
        // 法術的基礎傷害 × 成長倍率（需求等級 1 是 1.05）
        assertEquals(8 * 1.05, level1.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(12 * 1.05, level1.spellMax().get(DamageType.FIRE), 1e-9);
        assertFalse(level1.tags().contains(Tags.SWORD), "法術不帶武器標籤");
        final SkillProfile level20 = SkillProfiles.build(config, fireball, 20, Set.of());
        assertEquals(8 * 9.23, level20.spellDamage().get(DamageType.FIRE), 1e-9, "20 級的需求等級 80 → 9.23");
        assertEquals(5, SkillProfiles.manaCost(fireball, 1));
        assertEquals(14, SkillProfiles.manaCost(fireball, 20));

        final SkillDefinition split = config.skill("permafrost_bolts");
        assertEquals(5, split.params().projectiles().intAt(1));
        assertEquals(5, split.params().projectiles().intAt(10));
        assertEquals(7, split.params().projectiles().intAt(20));
        final SkillProfile arrows = SkillProfiles.build(config, split, 1, Set.of("weapon", "two_hand", Tags.CROSSBOW, Tags.RANGED));
        assertTrue(arrows.tags().containsAll(Set.of(Tags.ATTACK, Tags.PROJECTILE, Tags.CROSSBOW)), "攻擊帶武器標籤");
        assertEquals(1.23, arrows.effectiveness(), 1e-9);

        final SkillProfile burning = SkillProfiles.build(config, config.skill("explosive_bolt"), 1, Set.of(Tags.CROSSBOW));
        assertEquals(0.5, burning.conversion().get(DamageType.FIRE), 1e-9);
    }

    @Test
    void intervalsUseAttackAndCastSpeed() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "武器"));
        final SkillDefinition heavy = config.skill("boneshatter");
        final SkillProfile heavyProfile = SkillProfiles.build(config, heavy, 1, Set.of(Tags.MACE));
        assertEquals(20 / (1.5 * 0.6), SkillProfiles.intervalTicks(sheet, heavy, heavyProfile), 1e-9);
        final SkillDefinition leap = config.skill("leap_slam");
        assertEquals(20 / (1.5 * 0.8), SkillProfiles.intervalTicks(sheet, leap, SkillProfiles.build(config, leap, 1, Set.of())), 1e-9);

        final SkillDefinition fireball = config.skill("fireball");
        final SkillProfile fire = SkillProfiles.build(config, fireball, 1, Set.of());
        assertEquals(24, SkillProfiles.intervalTicks(sheet, fireball, fire), 1e-9, "施法時間 1.2 秒");
        sheet.add(new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.5, Set.of(Tags.SPELL), "施法速度"));
        assertEquals(16, SkillProfiles.intervalTicks(sheet, fireball, fire), 1e-9);
        assertEquals(20 / (1.5 * 0.6), SkillProfiles.intervalTicks(sheet, heavy, heavyProfile), 1e-9, "施法速度不影響攻擊");

        // 面板上的「平均每次」：熾星彈 8–12 平均 10，乘 1 級的成長倍率 1.05
        final DamagePacket expected = DamageCalculator.expectedOutgoing(new StatSheet(), fire);
        assertEquals(10 * 1.05, expected.get(DamageType.FIRE), 1e-9);
    }

    @Test
    void badFilesAreRejectedWithTheFieldName(@TempDir final Path dir) throws IOException {
        final String shipped = Files.readString(DATA.resolve(SkillConfig.FILE), StandardCharsets.UTF_8);
        assertRejected(dir, shipped.replace("visual: fireball", "visual: lasers"), "visual");
        assertRejected(dir, shipped.replace("    effectiveness: [1.3, 1.82]\n", "    effectiveness: [1.3, 1.82]\n    damage: [1, 2]\n"), "damage");
        assertRejected(dir, shipped.replace("  default_staff:", "  default_staff_renamed:"), "default_staff");
        assertRejected(dir, shipped.replace("starter: witch", "starter: marauder"), "起始技能");
        assertRejected(dir, shipped.replace("convert: { fire: 0.5 }", "convert: { fire: 0.8, cold: 0.5 }"), "convert");
        assertRejected(dir, shipped.replace("speed: 0.55, range: 30", "speed: 9, range: 30"), "speed");
    }

    @Test
    void sameSupportCanGoOnDifferentSkillsUnlessTurnedOff(@TempDir final Path dir) throws IOException {
        assertFalse(config.supportOncePerCharacter(), "預設：不同技能可以各掛一顆同一種輔助");
        Files.writeString(dir.resolve(SkillConfig.FILE), Files.readString(DATA.resolve(SkillConfig.FILE), StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        final String supports = Files.readString(DATA.resolve(SkillConfig.SUPPORTS_FILE), StandardCharsets.UTF_8);
        assertTrue(supports.contains("once_per_character: false"));
        Files.writeString(dir.resolve(SkillConfig.SUPPORTS_FILE), supports.replace("once_per_character: false", "once_per_character: true"),
                StandardCharsets.UTF_8);
        final SkillConfig poe2 = new SkillConfig();
        poe2.load(dir);
        assertTrue(poe2.supportOncePerCharacter(), "改成 true：PoE2 的規則");
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve(SkillConfig.FILE), content, StandardCharsets.UTF_8);
        final SkillConfig broken = new SkillConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }

    @Test
    void rangedMinionsAreTaggedRangedNotProjectile() {
        // 2026-10：遠程召喚物（骸骨射手、術士）標「遠程」：「+x 投射物技能等級」吃不到、投射物的輔助（穿透、分光）掛不上
        for (final String id : List.of("skeletal_archers", "skeletal_pyromancer", "skeletal_stormcaller", "skeletal_frostcaller")) {
            final SkillDefinition summon = config.skill(id);
            assertTrue(summon.tags().contains(Tags.RANGED), id);
            assertFalse(summon.tags().contains(Tags.PROJECTILE), id);
            assertFalse(config.support("pierce").supports(summon), id + "：穿透掛不上");
            assertFalse(config.support("multiple_projectiles").supports(summon), id + "：分光掛不上");
        }
        assertTrue(config.skill("summon_skeletons").tags().contains(Tags.MELEE), "近戰的召喚物照舊");
    }
}
