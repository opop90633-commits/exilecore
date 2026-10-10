package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/boss_skills.yml：招式庫、預設招式組、maps.yml／zones.yml 的 skills 寫法。 */
class BossSkillTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("test.yml", new StringReader(text));
    }

    @Test
    void shippedLibraryLoads() {
        final BossSkillConfig bosses = config.bossSkills();
        assertFalse(bosses.isEmpty());
        assertTrue(bosses.skills().size() >= 30, "招式庫至少 30 招，實際 " + bosses.skills().size());
        for (final BossSkill skill : bosses.skills().values()) {
            assertNotNull(skill.name());
            assertTrue(SkillConfig.VISUALS.contains(skill.visual()) || BossSkillConfig.VISUALS.contains(skill.visual()), skill.id() + " 的外觀");
            if (skill.kind() == BossSkill.Kind.SUMMON) {
                assertNotNull(skill.summonType(), skill.id() + " 要寫 summon");
                assertFalse(skill.dealsDamage());
            }
        }
        // 設定
        final BossSkillConfig.Settings settings = bosses.settings();
        assertEquals("pounce", settings.gapCloser());
        assertEquals(9, settings.chaseDistance(), 1e-9, "2026-10：12 → 9 格（站遠打的也會被撲過去）");
        assertEquals(2.0, settings.chaseSeconds(), 1e-9, "2026-10：2.5 → 2 秒");
        // Boss 跟一般怪物分開（2026-10：Boss 出招快一點，路上的怪不變）
        assertEquals(1.5, settings.gap(), 1e-9, "一般怪物的空檔不變");
        assertEquals(2.0, settings.firstDelay(), 1e-9);
        assertEquals(1.0, settings.bossGap(), 1e-9, "Boss 的空檔 1.5 → 1");
        assertEquals(1.5, settings.bossFirstDelay(), 1e-9, "Boss 剛看到人的等待 2 → 1.5");
        assertEquals(0.8, settings.bossCooldown(), 1e-9, "Boss 的招式冷卻 × 0.8");
        assertEquals(1.0, settings.gapFor(true), 1e-9);
        assertEquals(1.5, settings.gapFor(false), 1e-9);
        assertEquals(1.5, settings.firstDelayFor(true), 1e-9);
        assertEquals(2.0, settings.firstDelayFor(false), 1e-9);
        assertEquals(0.8, settings.cooldownFor(true), 1e-9);
        assertEquals(1.0, settings.cooldownFor(false), 1e-9, "一般怪物照招式寫的冷卻");
        assertEquals(BossSkillConfig.Settings.DEFAULT, new BossSkillConfig.Settings(settings.gap(), settings.firstDelay(), settings.warnRange(), null,
                settings.chaseDistance(), settings.chaseSeconds(), settings.phasePause(), settings.phaseUnlock(), settings.bossGap(),
                settings.bossFirstDelay(), settings.bossCooldown()), "程式的預設值跟出貨的 boss_skills.yml 一樣（追擊的招另外寫）");
        final BossSkill pounce = bosses.gapCloser();
        assertNotNull(pounce);
        assertEquals(BossSkill.Kind.BLINK, pounce.kind());
        assertTrue(pounce.pounce(), "撲擊：閃現後落地震一下");
        assertTrue(pounce.dealsDamage());
        // 每種預設招式組的招式都存在
        assertFalse(bosses.defaultsFor("ZOMBIE").isEmpty());
        assertFalse(bosses.defaultsFor("不認得的").isEmpty(), "沒列的生物用 default 那組");
        assertEquals(bosses.defaultsFor(BossSkillConfig.DEFAULT_KEY), bosses.defaultsFor("ALLAY"));
    }

    @Test
    void parseDefaultsAndChecks() {
        final BossSkill slam = config.bossSkills().skill("slam");
        assertNotNull(slam);
        assertEquals(BossSkill.Kind.SLAM, slam.kind());
        assertEquals(DamageType.PHYSICAL, slam.type());
        assertEquals("boss_physical", slam.visual(), "沒寫 visual 照傷害類型");
        assertEquals(BossSkill.Target.TARGET, slam.at());
        assertTrue(slam.dealsDamage());

        final BossSkill meteor = config.bossSkills().skill("meteor_storm");
        assertEquals(DamageType.FIRE, meteor.type());
        assertEquals("boss_fire", meteor.visual());
        assertEquals(6, meteor.count());
        assertEquals(BossSkill.Target.RANDOM, meteor.at());

        final BossSkill venom = config.bossSkills().skill("venom_pool");
        assertEquals(BossSkill.Kind.ZONE, venom.kind());
        assertEquals(BossSkill.Target.TARGET, venom.at(), "zone 預設落在目標腳下");

        final BossSkill curse = config.bossSkills().skill("curse_of_decay");
        assertFalse(curse.dealsDamage());
        assertEquals(25, curse.taken(), 1e-9);

        // 錯誤
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: fly")), "不認得的 kind");
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: summon\n")));
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: slam\ndamage: 0\n")), "打人的招式要有傷害");
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: curse\ndamage: 2\n")), "詛咒不寫傷害");
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: slam\ntype: holy\n")));
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: slam\ncooldown: 0.5\n")));
        assertThrows(IllegalArgumentException.class, () -> BossSkill.parse("x", yaml("kind: slam\nat: nowhere\n")));
        final BossSkill blink = BossSkill.parse("x", yaml("kind: blink\n"));
        assertFalse(blink.dealsDamage());
        assertFalse(blink.pounce());
        assertEquals(0.5, blink.telegraph(), 1e-9, "閃現、召喚的前搖預設短一點");
    }

    @Test
    void usesParseAsStringsOrMaps() {
        final YamlNode node = yaml("""
                skills:
                  - slam
                  - { id: meteor_storm, phase: 1 }
                  - { id: summon_drowned, cooldown: 15, damage: -1 }
                """);
        final List<BossSkillUse> uses = BossSkillUse.parseList(node, "skills");
        assertEquals(3, uses.size());
        assertEquals(new BossSkillUse("slam", -1, -1, 0), uses.get(0));
        assertEquals(1, uses.get(1).fromPhase());
        assertEquals(15, uses.get(2).cooldown(), 1e-9);
        assertEquals(List.of(), BossSkillUse.parseList(node, "nothing"), "沒寫 = 用預設");
        assertThrows(IllegalArgumentException.class, () -> BossSkillUse.parseList(yaml("skills: slam\n"), "skills"), "要是清單");
        assertThrows(IllegalArgumentException.class, () -> BossSkillUse.parseList(yaml("skills:\n  - { phase: 1 }\n"), "skills"), "要有 id");
        assertThrows(IllegalArgumentException.class, () -> BossSkillUse.parseList(yaml("skills:\n  - { id: slam, phase: 99 }\n"), "skills"));
        assertThrows(IllegalArgumentException.class, () -> BossSkillUse.parseList(yaml("skills:\n  - { id: slam, cooldown: 0 }\n"), "skills"));
    }

    @Test
    void kitResolution() {
        final BossSkillConfig bosses = config.bossSkills();
        // 沒寫：照生物
        final List<BossSkillConfig.Entry> drowned = bosses.kitFor("DROWNED", List.of());
        assertEquals(List.of("tide_slam", "trident_volley", "summon_drowned"), drowned.stream().map(entry -> entry.skill().id()).toList());
        assertEquals(List.of("slam", "charge"), bosses.kitFor("ALLAY", null).stream().map(entry -> entry.skill().id()).toList());
        // 寫了：照寫的，冷卻與傷害可以覆蓋，不認得的略過
        final List<BossSkillUse> uses = List.of(BossSkillUse.of("slam"), new BossSkillUse("meteor_storm", 30, 3.0, 2), BossSkillUse.of("nope"));
        final List<BossSkillConfig.Entry> kit = bosses.kitFor("DROWNED", uses);
        assertEquals(2, kit.size());
        assertSame(bosses.skill("slam"), kit.get(0).skill(), "沒覆蓋就是同一個物件");
        assertEquals(0, kit.get(0).fromPhase());
        final BossSkill storm = kit.get(1).skill();
        assertEquals(30, storm.cooldown(), 1e-9);
        assertEquals(3.0, storm.damage(), 1e-9);
        assertEquals(bosses.skill("meteor_storm").count(), storm.count(), "其他欄位照原本的");
        assertEquals(2, kit.get(1).fromPhase());
        assertEquals(List.of("nope"), bosses.unknown(uses));
    }

    @Test
    void libraryValidation() {
        final BossSkillConfig bosses = new BossSkillConfig();
        assertThrows(IllegalArgumentException.class, () -> bosses.load(yaml("""
                skills:
                  slam: { kind: slam, visual: rainbow }
                """)), "外觀要認得");
        assertThrows(IllegalArgumentException.class, () -> bosses.load(yaml("""
                settings: { gap_closer: slam }
                skills:
                  slam: { kind: slam }
                """)), "追擊要是 blink 或 charge");
        assertThrows(IllegalArgumentException.class, () -> bosses.load(yaml("""
                skills:
                  slam: { kind: slam }
                defaults:
                  ZOMBIE: [slam, ghost]
                """)), "預設招式組裡的招式要存在");
        bosses.load(yaml("""
                settings: { gap_closer: none, gap: 3 }
                skills:
                  slam: { kind: slam }
                  rush: { kind: charge, damage: 1.5 }
                defaults:
                  default: [slam]
                  zombie: [rush]
                """));
        assertNull(bosses.gapCloser());
        assertEquals(3, bosses.settings().gap(), 1e-9);
        assertEquals(1.0, bosses.settings().gapFor(true), 1e-9, "改一般怪物的 gap 不影響 Boss（沒寫 boss_gap 用預設）");
        assertEquals(3, bosses.settings().gapFor(false), 1e-9);
        assertEquals("rush", bosses.kitFor("Zombie", List.of()).getFirst().skill().id(), "生物代號不分大小寫");
        // Boss 專用的節奏（2026-10）
        final BossSkillConfig custom = new BossSkillConfig();
        custom.load(yaml("settings: { boss_gap: 2, boss_first_delay: 0.5, boss_cooldown: 1.25 }\nskills:\n  slam: { kind: slam }\n"));
        assertEquals(2, custom.settings().gapFor(true), 1e-9);
        assertEquals(0.5, custom.settings().firstDelayFor(true), 1e-9);
        assertEquals(1.25, custom.settings().cooldownFor(true), 1e-9);
        assertEquals(1.0, custom.settings().cooldownFor(false), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> custom.load(yaml("settings: { boss_cooldown: 0.1 }\n")), "冷卻倍率至少 0.25");
        assertThrows(IllegalArgumentException.class, () -> custom.load(yaml("settings: { boss_gap: -1 }\n")));
        assertEquals("slam", bosses.kitFor("SPIDER", List.of()).getFirst().skill().id());
        // 空檔案
        bosses.load(yaml(""));
        assertTrue(bosses.isEmpty());
        assertTrue(bosses.kitFor("ZOMBIE", List.of()).isEmpty());
        assertNull(bosses.gapCloser());
    }

    @Test
    void mapAndZoneBossesCarrySkills() throws IOException {
        final tw.exilecore.map.MapConfig.Boss boss = tw.exilecore.map.MapConfig.parseBoss(yaml("""
                name: 測試
                type: drowned
                skills:
                  - tide_slam
                  - { id: summon_drowned, phase: 1 }
                """));
        assertEquals(2, boss.skills().size());
        assertEquals(1, boss.skills().get(1).fromPhase());
        assertEquals(List.of(), tw.exilecore.map.MapConfig.parseBoss(yaml("name: 測試\n")).skills());
        // 出貨的 maps.yml、zones.yml：寫了的招式都存在
        final tw.exilecore.map.MapConfig maps = new tw.exilecore.map.MapConfig();
        maps.load(DATA);
        for (final tw.exilecore.map.MapConfig.MapDef map : maps.all()) {
            assertEquals(List.of(), config.bossSkills().unknown(map.boss().skills()), map.id());
        }
        final tw.exilecore.zone.ZoneConfig zones = new tw.exilecore.zone.ZoneConfig();
        zones.load(DATA);
        for (final tw.exilecore.zone.ZoneConfig.ZoneDefinition zone : zones.zones()) {
            if (zone.boss() != null) {
                assertEquals(List.of(), config.bossSkills().unknown(zone.boss().skills()), zone.id());
            }
        }
        // 昇華試煉的爐監：四場各一套自己的招式（不是生物的預設招式組），招式都存在、階段招式的階段真的有
        final tw.exilecore.ascendancy.AscendancyConfig ascendancy = new tw.exilecore.ascendancy.AscendancyConfig();
        ascendancy.load(DATA);
        final java.util.Set<String> kits = new java.util.HashSet<>();
        for (final tw.exilecore.ascendancy.AscendancyConfig.Trial trial : ascendancy.trials()) {
            final tw.exilecore.map.MapConfig.Boss warden = trial.boss();
            assertEquals(List.of(), config.bossSkills().unknown(warden.skills()), trial.id());
            assertTrue(warden.skills().size() >= 4, trial.id() + "：爐監至少四招");
            final List<String> ids = warden.skills().stream().map(tw.exilecore.skill.BossSkillUse::id).toList();
            assertTrue(kits.add(String.join(",", ids)), trial.id() + "：每場試煉的招式組要不一樣");
            for (final tw.exilecore.skill.BossSkillUse use : warden.skills()) {
                assertTrue(use.fromPhase() <= warden.phases().size(), trial.id() + "：" + use.id() + " 的階段超過 phases 的數量");
                assertTrue(use.id().startsWith("warden_"), trial.id() + "：爐監用自己的招式 " + use.id());
            }
        }
    }

    @Test
    void monsterModsAndTypesReferenceExistingSkills() throws IOException {
        final tw.exilecore.monster.MonsterConfig monsters = new tw.exilecore.monster.MonsterConfig();
        monsters.load(DATA);
        assertTrue(monsters.skillMods().size() >= 10, "帶招式的怪物詞綴");
        for (final tw.exilecore.monster.MonsterConfig.MonsterMod mod : monsters.skillMods()) {
            assertEquals(List.of(), config.bossSkills().unknown(mod.skills()), mod.id());
            assertTrue(mod.minLevel() >= 6, mod.id() + "：招式詞綴從 6 級起才出現");
        }
        assertEquals("MACE", monsters.mod("slammer").hand());
        assertNull(monsters.mod("pouncer").hand());
        assertFalse(monsters.mod("sturdy").hasSkills());
        assertTrue(monsters.skillTypes().size() >= 12, "有招式的生物種類");
        for (final var entry : monsters.skillTypes().entrySet()) {
            assertEquals(List.of(), config.bossSkills().unknown(entry.getValue().skills()), entry.getKey());
            assertTrue(entry.getValue().skillChance() > 0 && entry.getValue().skillChance() <= 1, entry.getKey());
        }
        assertEquals(0.25, monsters.type("ZOMBIE").skillChance(), 1e-9);
        assertEquals(14, monsters.type("ZOMBIE").skills().getFirst().cooldown(), 1e-9, "小怪的冷卻比 Boss 長");
        assertTrue(monsters.type("SKELETON").skills().isEmpty(), "骷髏本來就會射箭，不另外給招式");
        assertEquals(0.25, monsters.type("ALLAY").skillChance(), 1e-9, "沒寫的用預設值（但沒有招式）");
    }

    @Test
    void dataIndexListsFile() throws IOException {
        final String index = java.nio.file.Files.readString(DATA.resolve("index.txt"));
        assertTrue(index.contains("data/boss_skills.yml"), "新資料檔要列在 index.txt 才會同步到伺服器");
    }
}
