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

/**
 * data/ascendancy.yml：讀得進來、四個試煉一共 8 點、每個職業兩三個昇華職業、昇華天賦照 PoE2 0.5（2026-10-26：會分岔、有環、有「選一個」）；
 * 寫錯的檔會被擋下來。
 */
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
        assertEquals(0, config.settings().weeklyOpens(), "2026-10-25 試煉不限次數（只留開完的冷卻）");
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

    /**
     * 2026-10-26 照 PoE2 0.5：每個昇華的天賦全部換成原作的（數量照原作，起點不算）。樹會分岔、Ritualist 與 Invoker 有環、
     * 銳眼／追獵者／嵌晶／夏烏拉有「選一個」。這次之前是四條分支、每條四個、一共 16 個。
     */
    @Test
    void everyAscendancyIsThePoe2Tree() {
        // 2026-10-27 照 PoE2 0.5：補上靈狩者（Spirit Walker）、鳴鐘武師（Martial Artist）、淵井骸主（Abyssal Lich）；這次之前 20 個
        assertEquals(23, config.ascendancies().size(), "八個職業一共二十三個");
        assertEquals(3, config.version(), "2026-10-26 換成原作的天賦：第 2 版；同一天做出（尚未生效）的機制：第 3 版（已配的全部退回）");
        for (final String classId : classes) {
            final int count = config.forClass(classId).size();
            assertTrue(count >= 2 && count <= 4, classId + " 有 " + count + " 個");
        }
        assertEquals(3, config.forClass("huntress").size()); // 2026-10-27：林嵐獵姬、儀祭者、靈狩者
        assertEquals(3, config.forClass("monk").size()); // 2026-10-27：喚雷者、虛步者、鳴鐘武師
        assertEquals(4, config.forClass("witch").size()); // 2026-10-27：疫喚者、血術、骸主、淵井骸主
        assertEquals(3, config.forClass("marauder").size());
        assertEquals(2, config.forClass("druid").size());
        // 原作的天賦數（不含起點）
        final Map<String, Integer> sizes = Map.ofEntries(Map.entry("ironwarden", 16), Map.entry("berserker", 16), Map.entry("forgesmith", 23),
                Map.entry("witchhunter", 16), Map.entry("legionnaire", 20), Map.entry("gemling", 19), Map.entry("amazon", 18),
                Map.entry("ritualist", 18), Map.entry("stararrow", 18), Map.entry("windwalker", 23), Map.entry("invoker", 19),
                Map.entry("voidwalker", 23), Map.entry("starfire", 20), Map.entry("voidrobe", 16), Map.entry("sandsage", 22),
                Map.entry("blightcaller", 22), Map.entry("bloodmage", 17), Map.entry("lich", 18), Map.entry("starseer", 16),
                Map.entry("wildshaman", 16), Map.entry("spiritwalker", 17), Map.entry("martialartist", 16), Map.entry("abysslich", 18));
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            assertEquals(sizes.get(ascendancy.id()), ascendancy.nodes().size(), ascendancy.id());
            final List<AscendancyConfig.Cell> layout = ascendancy.layout();
            assertEquals(ascendancy.nodes().size(), layout.size(), ascendancy.id() + "：每個天賦都畫得出來");
            int cost = 0;
            for (final AscendancyConfig.Cell cell : layout) {
                final AscendancyConfig.Node node = cell.node();
                assertTrue(cell.depth() < AscendancyConfig.MAX_DEPTH, node.id());
                assertEquals(node.option() || !node.unlock().isEmpty() || "sanguimancy".equals(node.id()) ? 0 : 1, node.cost(),
                        node.id() + "：一個 1 點（選項、2026-10-27 神聖合一這種 unlock 的不花點數；血卜術照站長自帶免費）");
                cost += node.cost();
                assertTrue(node.choice() || !node.lines().isEmpty(), node.id() + "：說明行照原作寫");
                for (final String line : node.lines()) {
                    assertFalse(line.matches(".*[A-Za-z]{3,}.*"), node.id() + " 的說明沒寫成中文：" + line);
                    assertFalse(line.contains("狂怒球") || line.contains("暴擊球") || line.contains("耐力球"), node.id() + "：戰星用我們的名字：" + line);
                }
                if (node.stats().isEmpty() && !node.choice() && node.note() == null) { // 鐵匠傑作：效果在後面的胸甲天賦上（補充說明有寫）
                    assertTrue(node.lines().stream().allMatch(line -> line.endsWith(AscendancyLayout.PENDING)),
                            node.id() + "：沒有加成的天賦，說明行要寫（尚未生效）");
                }
                for (final Modifier modifier : node.stats()) {
                    assertNull(StatLines.problem(modifier), node.id());
                }
            }
            assertTrue(cost > config.totalPoints(), ascendancy.id() + "：8 點配不完（要選）");
            assertFalse(ascendancy.summary().isBlank());
        }
        // 分岔、環、選一個
        final AscendancyConfig.Ascendancy ritualist = config.ascendancy("ritualist");
        assertEquals(3, ritualist.node("unfurled_finger").requires().size(), "舒展之指接在三個減少的天賦上");
        assertEquals(2, config.ascendancy("invoker").node("shall_rage").requires().size(), "怒號接在兩個元素傷害上（環）");
        final AscendancyConfig.Ascendancy stararrow = config.ascendancy("stararrow");
        assertTrue(stararrow.node("projectile_proximity").choice());
        assertEquals(List.of("point_blank", "far_shot"), stararrow.options("projectile_proximity").stream().map(AscendancyConfig.Node::id).toList());
        assertEquals(List.of("projectile_proximity"), stararrow.node("projectile_speed_2").requires(), "選一個的天賦也能往後接");
        assertEquals(3, config.ascendancy("gemling").options("implanted_gems").size());
        assertEquals(3, config.ascendancy("voidwalker").options("lucid_dreaming").size());
        assertEquals(5, config.ascendancy("windwalker").options("brew_concoction").size());
        final AscendancyConfig.Ascendancy smith = config.ascendancy("forgesmith");
        assertEquals(12, smith.children("smiths_masterwork").size(), "鍛師名作後面接 12 個胸甲天賦");
        assertTrue(smith.rows() > AscendancyLayout.VISIBLE_ROWS, "比 4 排高：選單要捲動");
        // 照原作的數值
        assertEquals(List.of(new Modifier("life.max", ModType.MORE, 0.15, java.util.Set.of(), "昇華「幽祕血統」")),
                config.ascendancy("ironwarden").node("mysterious_lineage").stats());
        assertEquals(List.of("更多 15% 最大生命"), config.ascendancy("ironwarden").node("mysterious_lineage").lines());
    }

    /** 2026-10-26：分岔的樹怎麼排進選單：第一個後續同一排，其他的往下一排；接在起點的各自從新的一排開始。 */
    @Test
    void layoutBranchesDownwards() {
        final AscendancyConfig.Ascendancy stararrow = config.ascendancy("stararrow");
        final Map<String, AscendancyConfig.Cell> cells = new java.util.HashMap<>();
        for (final AscendancyConfig.Cell cell : stararrow.layout()) {
            cells.put(cell.node().id(), cell);
        }
        final AscendancyConfig.Cell choice = cells.get("projectile_proximity");
        assertEquals(1, choice.depth());
        assertEquals(choice.row(), cells.get("point_blank").row(), "第一個選項跟它同一排");
        assertEquals(2, cells.get("point_blank").depth());
        assertEquals(choice.row() + 1, cells.get("far_shot").row(), "第二個選項往下一排");
        assertEquals(choice.row() + 2, cells.get("projectile_speed_2").row());
        assertEquals(3, cells.get("endless_munitions").depth());
        assertEquals(0, cells.get("mark_effect").row(), "第一個接在起點的在第 0 排");
        // branches（網頁規劃器）：每一排一串
        int count = 0;
        for (final List<AscendancyConfig.Node> row : stararrow.branches()) {
            assertFalse(row.isEmpty());
            count += row.size();
        }
        assertEquals(stararrow.nodes().size(), count);
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
        // 2026-10-24：德魯伊改成每秒回復最大生命的 %（以前「增加 5% 生命回復」被寫成「每秒回復 0.1 生命」）
        assertEquals(List.of("每秒回復 0.25% 最大生命"), StatLines.describeAll(config.legacy().get("druid").getFirst().stats()));
        assertEquals(List.of("每秒回復 0.75% 最大生命"), StatLines.describeAll(config.legacy().get("druid").getLast().stats()));
        assertEquals("增加 5% 生命再生速度" /* 2026-10-27 照 PoE2 Life Regeneration rate 用語；這次之前「每秒生命回復」 */, StatLines.describe(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.LIFE_REGEN,
                tw.exilecore.stats.ModType.INCREASED, 0.05, java.util.Set.of(), "test")));
        // 原本混沌術士的兩個昇華職業跟著元素法術給了元素法師，混沌術士是新的；2026-10 兩邊各多一個
        assertEquals(List.of("starfire", "voidrobe", "sandsage"), config.forClass("sorceress").stream().map(AscendancyConfig.Ascendancy::id).toList());
        // 2026-10-27 照 PoE2 0.5：多了淵井骸主（Abyssal Lich）；這次之前 [blightcaller, bloodmage, lich]
        assertEquals(List.of("blightcaller", "bloodmage", "lich", "abysslich"), config.forClass("witch").stream().map(AscendancyConfig.Ascendancy::id).toList());
    }

    @Test
    void badFilesAreRejected() {
        final AscendancyConfig bad = new AscendancyConfig();
        bad.load(YamlNode.load("ascendancy.yml", new StringReader("""
                settings: { cooldown_seconds: -1, weekly_opens: -2 }
                trials:
                  one:
                    name: 一
                    requires: { trial: nope, boss: camp, level: 999 }
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
                      f: { name: 己, requires: [a, nope], choice: true, lines: [選一個] }
                      g: { name: 庚, option_of: f, cost: 1, lines: [選項] }
                      h: { name: 辛, option_of: a, lines: [選項] }
                      i: { name: 壬, requires: g, lines: [接在選項後面] }
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
                "trials.one.requires.level：要在 0 到 " + levelCap + " 之間",
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
                "ascendancies.hero.nodes.d：接不到起點",
                "ascendancies.hero.nodes.e：接不到起點",
                "ascendancies.hero.nodes.f.requires：這個昇華職業沒有天賦 nope",
                "ascendancies.hero.nodes.f：「選一個」的天賦至少要有兩個選項",
                "ascendancies.hero.nodes.g.cost：選項不花點數",
                "ascendancies.hero.nodes.h.option_of：a 不是這個昇華職業「選一個」的天賦",
                "ascendancies.hero.nodes.i.requires：不能接在選項 g 後面",
                "legacy.marauder.30.stats：resist.fire 只能用 FLAT",
                "legacy.marauder.130：等級要在 1 到 " + levelCap + " 之間",
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

    @Test
    void levelRaceHasATitleForEveryAscendancy() {
        final AscendancyConfig.LevelRace race = config.levelRace();
        assertTrue(race.enabled());
        assertEquals(100, race.level());
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            final String title = race.title(ascendancy);
            assertTrue(title.startsWith("巔峰"), title);
            assertTrue(title.length() <= 5, "稱號要短：" + title);
            assertTrue(names.add(title), "稱號重複：" + title);
        }
        assertEquals("race_bloodmage", AscendancyConfig.LevelRace.titleId("bloodmage"));
    }
}
