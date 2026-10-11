package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/ascendancy.yml：讀得進來、四個試煉一共 8 點、每個職業兩三個昇華職業、分支放得進選單；寫錯的檔會被擋下來。 */
class AscendancyConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static AscendancyConfig config;
    static List<String> classes;
    static List<String> bossZones;
    static List<String> mods;
    static int levelCap;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(DATA);
        classes = new ArrayList<>();
        for (final CharacterClass characterClass : CharacterClass.values()) {
            classes.add(characterClass.id());
        }
        final ZoneConfig zones = new ZoneConfig();
        zones.load(DATA);
        bossZones = new ArrayList<>();
        for (final ZoneConfig.ZoneDefinition zone : zones.zones()) {
            if (zone.boss() != null) {
                bossZones.add(zone.id());
            }
        }
        monsters = new MonsterConfig();
        monsters.load(DATA);
        mods = new ArrayList<>();
        for (final MonsterConfig.MonsterMod mod : monsters.mods()) {
            mods.add(mod.id());
        }
        final CharacterConfig characters = new CharacterConfig();
        characters.load(DATA);
        levelCap = characters.levelCap();
    }

    static List<String> validate(final AscendancyConfig ascendancy) {
        return ascendancy.validate(classes, bossZones, mods, MapConfig.THEMES, levelCap);
    }

    @Test
    void shippedFileIsValid() {
        assertEquals(List.of(), validate(config));
        final List<String> ids = new ArrayList<>();
        for (final AscendancyConfig.Trial trial : config.trials()) {
            ids.add(trial.id());
        }
        assertEquals(List.of("ember", "venom", "frost", "star"), ids);
        assertEquals(8, config.totalPoints(), "四個試煉各 2 點");
        assertEquals(60, config.settings().cooldownSeconds());
        assertEquals(8, config.settings().weeklyOpens(), "每週最多開 8 次（不用地圖物品，不能拿來刷）");
        // 一個接一個：第一個看廢墟的 Boss，後面的要先完成前一個
        AscendancyConfig.Trial previous = null;
        for (final AscendancyConfig.Trial trial : config.trials()) {
            assertEquals(previous == null ? null : previous.id(), trial.requires().trial(), trial.id());
            if (previous != null) {
                assertTrue(trial.level() > previous.level(), trial.id() + " 的怪物等級要比前一個高");
            }
            previous = trial;
        }
        assertEquals("ruins", config.trial("ember").requires().boss(), "第三章（曜京）之後開始");
        assertEquals(6, config.trial("star").requires().maps(), "最後一個在終局");
    }

    @Test
    void everyClassHasTwoAscendanciesOfEightNotables() {
        // 2026-10 PoE2 改版：每個昇華一棵小樹（四條分支、最多 16 個天賦），每個天賦 1 點；有的職業三個昇華
        assertEquals(20, config.ascendancies().size(), "八個職業一共二十個");
        for (final String classId : classes) {
            final int count = config.forClass(classId).size();
            assertTrue(count == 2 || count == 3, classId + " 有 " + count + " 個");
        }
        assertEquals(3, config.forClass("marauder").size());
        assertEquals(2, config.forClass("druid").size());
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            assertTrue(ascendancy.nodes().size() <= 16, ascendancy.id());
            final List<List<AscendancyConfig.Node>> branches = ascendancy.branches();
            assertEquals(AscendancyConfig.MAX_BRANCHES, branches.size(), ascendancy.id() + "：四條分支");
            int placed = 0;
            for (final List<AscendancyConfig.Node> branch : branches) {
                assertTrue(branch.size() >= 2 && branch.size() <= AscendancyConfig.MAX_DEPTH, ascendancy.id() + "：每條二到四個");
                assertNull(branch.get(0).requires());
                for (int i = 1; i < branch.size(); i++) {
                    assertEquals(branch.get(i - 1).id(), branch.get(i).requires());
                }
                placed += branch.size();
            }
            assertEquals(ascendancy.nodes().size(), placed, ascendancy.id() + "：每個天賦都在一條分支上");
            int cost = 0;
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                assertEquals(1, node.cost(), node.id() + "：一個 1 點");
                cost += node.cost();
                for (final Modifier modifier : node.stats()) {
                    assertNull(StatLines.problem(modifier), node.id());
                    final String line = StatLines.describe(modifier);
                    assertFalse(line.contains(modifier.stat()), node.id() + " 的說明沒寫成中文：" + line);
                }
            }
            assertTrue(cost > config.totalPoints(), ascendancy.id() + "：8 點配不完（要選）");
            assertFalse(ascendancy.summary().isBlank());
        }
    }

    @Test
    void trialsBecomeMapsWithTheWarden() {
        final AscendancyConfig.Trial ember = config.trial("ember");
        final MapConfig.MapDef map = ember.map();
        assertEquals("trial_ember", map.id());
        assertEquals("第一試煉・餘燼", map.name());
        assertEquals(36, map.level());
        assertEquals("ruins", map.theme());
        assertFalse(map.isFinal());
        assertEquals(ember.lore(), map.lore());
        assertEquals("熾爐・殷燼", map.boss().name(), "同一個爐監殷燼，四道門各一個稱號");
        assertTrue(map.boss().named());
        assertEquals(1, map.boss().phases().size());
        assertEquals("SKELETON", map.boss().phases().getFirst().summonType());
        assertEquals(3, map.boss().phases().getFirst().summonCount());
        assertTrue(map.monsters().stream().anyMatch(entry -> entry.id().equals("HUSK")));
        // 一個比一個難（2026-10：生命倍率照 /ec balance 調過，不一定變大；看怪物等級的生命乘上倍率）
        double life = 0;
        double damage = 0;
        for (final AscendancyConfig.Trial trial : config.trials()) {
            final double effectiveLife = monsters.lifeAt(trial.level()) * trial.boss().life();
            assertTrue(effectiveLife > life && trial.boss().damage() > damage, trial.id());
            life = effectiveLife;
            damage = trial.boss().damage();
        }
    }

    @Test
    void legacyHasThreeTiersPerClass() {
        assertEquals(classes, new ArrayList<>(config.legacy().keySet()));
        for (final Map.Entry<String, List<AscendancyConfig.Tier>> entry : config.legacy().entrySet()) {
            final List<Integer> levels = new ArrayList<>();
            for (final AscendancyConfig.Tier tier : entry.getValue()) {
                levels.add(tier.level());
            }
            assertEquals(List.of(60, 75, 90), levels, entry.getKey() + "：門檻 60（劇情打完）、75、90");
        }
        final AscendancyConfig.Tier top = config.legacy().get("marauder").getLast();
        assertEquals(List.of(new Modifier("life.max", ModType.INCREASED, 0.06, java.util.Set.of(), "分身獎勵")), top.stats());
        assertEquals(List.of("+6% 所有元素抗性"), StatLines.describeAll(config.legacy().get("sorceress").getLast().stats()),
                "元素法師接了原本混沌術士的元素抗性");
        assertEquals(List.of("+12% 混沌抗性"), StatLines.describeAll(config.legacy().get("witch").getLast().stats()));
        // 原本混沌術士的兩個昇華職業跟著元素法術給了元素法師，混沌術士是新的；2026-10 兩邊各多一個
        assertEquals(List.of("starfire", "voidrobe", "sandsage"), config.forClass("sorceress").stream().map(AscendancyConfig.Ascendancy::id).toList());
        assertEquals(List.of("blightcaller", "bloodmage", "lich"), config.forClass("witch").stream().map(AscendancyConfig.Ascendancy::id).toList());
    }

    @Test
    void badFilesAreRejected() {
        final AscendancyConfig bad = new AscendancyConfig();
        bad.load(YamlNode.load("ascendancy.yml", new StringReader("""
                settings: { cooldown_seconds: -1, weekly_opens: -2 }
                trials:
                  one:
                    name: 一
                    requires: { trial: nope, boss: camp, level: 120 }
                    level: 0
                    points: 0
                    theme: moon
                    rooms: 3
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 甲, mods: [glowing] }
                  two:
                    name: 二
                    requires: { trial: three }
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 乙 }
                  three:
                    name: 三
                    requires: { trial: two }
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 丙 }
                ascendancies:
                  hero:
                    class: paladin
                    name: 英雄
                    color: red
                    nodes:
                      a: { name: 甲, stats: [ { stat: movement.speed, type: MORE, value: 5 } ] }
                      b: { name: 乙, requires: a, stats: [ { stat: life.max, value: 10, tags: [fire] } ] }
                      c: { name: 丙, requires: a, cost: 0, stats: [] }
                      d: { name: 丁, requires: e, stats: [ { stat: damage, type: INCREASED, value: 10, tags: [holy] } ] }
                      e: { name: 戊, requires: d, stats: [ { stat: damage.gain.holy, value: 10 } ] }
                legacy:
                  marauder:
                    30: [ { stat: resist.fire, type: INCREASED, value: 2 } ]
                    130: [ { stat: life.max, type: INCREASED, value: 2 } ]
                  paladin:
                    30: [ { stat: life.max, type: INCREASED, value: 2 } ]
                """)));
        final String problems = String.join("\n", validate(bad));
        for (final String expected : List.of(
                "settings.cooldown_seconds：不能是負的",
                "settings.weekly_opens：不能是負的",
                "trials.one.requires.trial：沒有試煉 nope",
                "trials.one.requires.boss：區域 camp 沒有區域 Boss",
                "trials.one.requires.level：要在 0 到 100 之間",
                "trials.one.level：怪物等級要在 1 到 100 之間",
                "trials.one.points：至少 1 點",
                "trials.one.theme：不認得的主題 moon",
                "trials.one.rooms：要在 5 到 12 之間",
                "trials.one.boss.mods：沒有怪物詞綴 glowing",
                "trials.two.requires.trial：前置試煉繞成一圈了",
                "ascendancies.hero.class：沒有職業 paladin",
                "ascendancies.hero.color：要寫成 \"#rrggbb\"",
                "ascendancies.hero.nodes.a.stats：movement.speed 只能用 INCREASED",
                "ascendancies.hero.nodes.b.stats：life.max 不能加標籤",
                "ascendancies.hero.nodes.c.cost：至少 1 點",
                "ascendancies.hero.nodes.c.stats：沒有任何加成",
                "ascendancies.hero.nodes.d.stats：damage：不認得的標籤 holy",
                "ascendancies.hero.nodes.e.stats：damage.gain.holy：額外附加只能是",
                "ascendancies.hero.nodes.a：後面接了 2 個天賦",
                "ascendancies.hero.nodes：有天賦接不到任何一條分支",
                "legacy.marauder.30.stats：resist.fire 只能用 FLAT",
                "legacy.marauder.130：等級要在 1 到 100 之間",
                "legacy.paladin：沒有職業 paladin")) {
            assertTrue(problems.contains(expected), "少了「" + expected + "」：\n" + problems);
        }
    }

    @Test
    void unreadableEntriesFailToLoad() {
        assertThrows(IllegalArgumentException.class, () -> new AscendancyConfig().load(YamlNode.load("a.yml", new StringReader("""
                ascendancies:
                  hero: { class: witch, name: 英雄, nodes: { a: { name: 甲, stats: [ { stat: life.max, type: LOTS, value: 1 } ] } } }
                """))), "寫錯的 type");
        assertThrows(IllegalArgumentException.class, () -> new AscendancyConfig().load(YamlNode.load("a.yml", new StringReader("""
                ascendancies:
                  hero: { class: witch, name: 英雄, nodes: { a: { name: 甲, stats: [ { stat: life.max } ] } } }
                """))), "沒寫 value");
        assertThrows(IllegalArgumentException.class, () -> new AscendancyConfig().load(YamlNode.load("a.yml", new StringReader("""
                trials:
                  Bad-Id: { name: 甲, monsters: { ZOMBIE: 1 }, boss: { name: 乙 } }
                """))), "代號");
        assertThrows(IllegalArgumentException.class, () -> new AscendancyConfig().load(YamlNode.load("a.yml", new StringReader("""
                trials:
                  one: { name: 甲, monsters: { ZOMBIE: 0 }, boss: { name: 乙 } }
                """))), "沒有怪");
        assertThrows(IllegalArgumentException.class, () -> new AscendancyConfig().load(YamlNode.load("a.yml", new StringReader("""
                legacy:
                  witch: { lots: [ { stat: life.max, value: 1 } ] }
                """))), "等級要是數字");
    }

    @Test
    void missingFileMeansNothing() throws IOException {
        final AscendancyConfig empty = new AscendancyConfig();
        empty.load(Path.of("does-not-exist"));
        assertTrue(empty.trials().isEmpty());
        assertTrue(empty.ascendancies().isEmpty());
        assertEquals(List.of(), validate(empty));
    }
}
