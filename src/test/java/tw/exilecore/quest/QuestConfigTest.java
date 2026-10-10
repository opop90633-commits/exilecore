package tw.exilecore.quest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.CharacterConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.npc.NpcType;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/quests.yml：讀得進來、代號都對得上、主線一章接一章、獎勵加起來是設計的數字；寫錯的檔會被擋下來。 */
class QuestConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static QuestConfig config;
    static List<String> zones;
    static List<String> bossZones;
    static List<String> functional;
    static int levelCap;

    @BeforeAll
    static void load() throws IOException {
        config = new QuestConfig();
        config.load(DATA);
        final ZoneConfig zoneConfig = new ZoneConfig();
        zoneConfig.load(DATA);
        zones = new ArrayList<>();
        bossZones = new ArrayList<>();
        for (final ZoneConfig.ZoneDefinition zone : zoneConfig.zones()) {
            zones.add(zone.id());
            if (zone.boss() != null) {
                bossZones.add(zone.id());
            }
        }
        functional = new ArrayList<>();
        for (final NpcType type : NpcType.values()) {
            if (type.functional()) {
                functional.add(type.id());
            }
        }
        final CharacterConfig characters = new CharacterConfig();
        characters.load(DATA);
        levelCap = characters.levelCap();
    }

    static List<String> validate(final QuestConfig quests) {
        return quests.validate(zones, bossZones, functional, levelCap);
    }

    @Test
    void shippedFileIsValid() {
        assertEquals(List.of(), validate(config));
        assertEquals(7, config.quests(QuestConfig.Kind.MAIN).size(), "序章、五章、終章");
        assertEquals(11, config.quests(QuestConfig.Kind.SIDE).size(), "五個區域各兩個＋營地的試煉");
        assertEquals(8, config.npcs().size(), "六個劇情人物＋營地軍需官＋星衛");
        assertEquals("bounty", config.npc("ji_heng").opens(), "軍需官打開委託板");
        assertEquals("trial", config.npc("ming_yi").opens(), "星衛打開昇華與試煉");
        for (final QuestConfig.Npc npc : config.npcs().values()) {
            assertNotNull(npc.placement(), npc.id() + " 要擺在劇情世界");
            assertFalse(npc.idle().isEmpty(), npc.id() + " 沒有閒聊");
        }
    }

    @Test
    void mainStoryIsAChain() {
        final List<QuestConfig.Quest> main = config.quests(QuestConfig.Kind.MAIN);
        assertEquals("prologue", main.getFirst().id());
        assertTrue(main.getFirst().requires().isEmpty());
        for (int i = 0; i < main.size(); i++) {
            final QuestConfig.Quest quest = main.get(i);
            assertTrue(quest.auto(), quest.id() + "：主線一章接一章自動開始");
            if (i > 0) {
                assertEquals(List.of(main.get(i - 1).id()), quest.requires(), quest.id() + " 要接在上一章後面");
            }
        }
        // 五章各在一個區域、都要打倒那個區域的 Boss
        final Set<String> bosses = new HashSet<>();
        for (final QuestConfig.Quest quest : main) {
            for (final QuestConfig.Stage stage : quest.stages()) {
                if (stage.objective().goal() == QuestConfig.Goal.BOSS) {
                    bosses.add(stage.objective().target());
                }
            }
        }
        assertEquals(Set.copyOf(bossZones), bosses, "每個區域 Boss 都在主線裡");
    }

    @Test
    void sideQuestsBelongToTheirZone() {
        for (final QuestConfig.Quest quest : config.quests(QuestConfig.Kind.SIDE)) {
            final QuestConfig.Npc giver = config.npc(quest.giver());
            assertNotNull(giver, quest.id() + " 的 giver 要是劇情人物");
            assertEquals(quest.zone(), giver.placement().zone(), quest.id() + " 的 giver 要在那個區域");
            assertFalse(quest.auto());
            assertFalse(quest.offer().isEmpty(), quest.id() + " 沒有給任務時說的話");
            assertNotNull(quest.after(), quest.id() + " 要在那一章開始之後");
            assertEquals(QuestConfig.Goal.TALK, quest.stages().getLast().objective().goal(), quest.id() + "：回去找人回報");
            assertEquals(quest.giver(), quest.stages().getLast().objective().target());
        }
    }

    @Test
    void rewardsAddUpToTheDesign() {
        assertEquals(9, config.totalPassivePoints(), "主線 4 點、支線 5 點");
        int choices = 0;
        for (final QuestConfig.Quest quest : config.quests()) {
            if (!quest.choices().isEmpty()) {
                choices++;
                assertEquals(QuestConfig.Goal.TALK, quest.stages().getLast().objective().goal());
            }
            for (final QuestConfig.Stage stage : quest.stages()) {
                if (stage.objective().goal() == QuestConfig.Goal.TALK) {
                    assertFalse(stage.lines().isEmpty(), quest.id() + "：說話的那一步要有台詞");
                }
                assertTrue(stage.text().length() <= 16, quest.id() + "：任務追蹤那一行太長「" + stage.text() + "」");
            }
        }
        assertEquals(4, choices, "第五章、淺灘的燈、低語的源頭、冰封的信");
    }

    @Test
    void trialObjectivesMatchTheAscendancyFile() throws IOException {
        final tw.exilecore.ascendancy.AscendancyConfig ascendancy = new tw.exilecore.ascendancy.AscendancyConfig();
        ascendancy.load(DATA);
        final List<String> trials = new ArrayList<>();
        for (final tw.exilecore.ascendancy.AscendancyConfig.Trial trial : ascendancy.trials()) {
            trials.add(trial.id());
        }
        assertEquals(List.of(), config.validate(zones, bossZones, functional, levelCap, trials));
        final QuestConfig.Quest call = config.quest("trial_call");
        assertEquals(QuestConfig.Goal.TRIAL, call.stage(1).objective().goal());
        assertEquals("ember", call.stage(1).objective().target());
        assertEquals(List.of("ch3_ruins"), call.requires(), "曜京那一章之後");
        assertTrue(QuestConfig.Goal.TRIAL.retroactive(), "完成過的試煉直接算");
        // 寫錯的試煉代號
        final QuestConfig bad = new QuestConfig();
        bad.load(YamlNode.load("quests.yml", new StringReader("""
                npcs:
                  a: { name: 甲 }
                quests:
                  q:
                    name: 一
                    giver: a
                    stages:
                      - { text: 試煉, trial: nope }
                      - { text: 說話, talk: a }
                """)));
        assertTrue(String.join("\n", bad.validate(zones, bossZones, functional, levelCap, trials)).contains("沒有試煉 nope"));
        assertEquals(List.of(), bad.validate(zones, bossZones, functional, levelCap), "昇華模組沒啟用：不檢查試煉代號");
    }

    @Test
    void badFilesAreRejected() {
        final QuestConfig bad = new QuestConfig();
        bad.load(YamlNode.load("quests.yml", new StringReader("""
                npcs:
                  a: { name: 甲, opens: shop }
                quests:
                  q1:
                    kind: main
                    name: 一
                    giver: nobody
                    requires: [q2]
                    stages:
                      - { text: 打 Boss, boss: camp }
                      - { text: 殺怪, kill: { zone: nowhere, count: 3 } }
                    rewards:
                      - { stat: damage, flat: 5 }
                    choose:
                      x: { label: X, rewards: [ { stat: life.max, flat: 1 } ] }
                  q2:
                    kind: side
                    name: 二
                    giver: a
                    requires: [q1]
                    stages:
                      - { text: 走到, checkpoint: [coast, entrence] }
                      - { text: 說話, talk: a }
                    rewards:
                      - { stat: movement.speed, flat: 5 }
                      - { stat: resist.fire, increased: 5 }
                """)));
        final List<String> problems = validate(bad);
        final String all = String.join("\n", problems);
        assertTrue(all.contains("giver：沒有 NPC nobody"), all);
        assertTrue(all.contains("沒有區域 Boss"), all);
        assertTrue(all.contains("沒有區域 nowhere"), all);
        assertTrue(all.contains("不支援的屬性 damage"), all);
        assertTrue(all.contains("至少要兩個選項"), all);
        assertTrue(all.contains("最後一步要是 talk"), all);
        assertTrue(all.contains("繞成一圈"), all);
        assertTrue(all.contains("檢查點要是 entrance、midway 或 boss，卻是「entrence」"), all);
        assertTrue(all.contains("movement.speed 要寫 increased"), all);
        assertTrue(all.contains("resist.fire 要寫 flat"), all);
        assertTrue(all.contains("不認得的功能 shop"), all);
        // 一步兩個目標、沒有目標、代號寫錯
        assertThrows(IllegalArgumentException.class, () -> new QuestConfig().load(YamlNode.load("quests.yml", new StringReader("""
                quests:
                  q:
                    name: 一
                    giver: a
                    stages:
                      - { text: 兩個, talk: a, boss: coast }
                """))));
        assertThrows(IllegalArgumentException.class, () -> new QuestConfig().load(YamlNode.load("quests.yml", new StringReader("""
                quests:
                  Bad-Id:
                    name: 一
                    giver: a
                    stages:
                      - { text: 說話, talk: a }
                """))));
        assertThrows(IllegalArgumentException.class, () -> new QuestConfig().load(YamlNode.load("quests.yml", new StringReader("""
                quests:
                  track:
                    name: 一
                    giver: a
                    stages:
                      - { text: 說話, talk: a }
                """))), "track 是保留的代號");
    }
}
