package tw.exilecore.quest;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任務的狀態機：自動開始、說話、殺怪、撿任務物品、打倒 Boss、已經做過的事直接算、選獎勵、追蹤、永久獎勵。 */
class QuestLogTest {

    private static final String YAML = """
            npcs:
              chief: { name: 主事 }
              fisher: { name: 漁夫 }
            quests:
              intro:
                kind: main
                name: 序
                giver: chief
                auto: true
                stages:
                  - { text: 跟主事說話, talk: chief, lines: [來了。] }
                  - { text: 找鑑定師, talk: identifier, lines: [卷軸拿去。] }
                  - { text: 走到海岸, checkpoint: [coast, entrance] }
                rewards:
                  - { stat: life.max, flat: 10 }
              ch1:
                kind: main
                name: 一
                giver: chief
                requires: [intro]
                auto: true
                stages:
                  - { text: 找漁夫, talk: fisher, lines: [牠在沙洲。] }
                  - { text: 打倒 Boss, boss: coast }
                  - { text: 回報, talk: chief, lines: [做得好。] }
                rewards:
                  - { passive_points: 2 }
                  - { stat: resist.cold, flat: 10 }
                choose:
                  life: { label: 生命, rewards: [ { stat: flask.life_recovery, increased: 25 } ] }
                  mana: { label: 魔力, rewards: [ { stat: flask.mana_recovery, increased: 25 } ] }
              charts:
                kind: side
                name: 航海圖
                giver: fisher
                after: ch1
                stages:
                  - { text: 撿航海圖, collect: { zone: coast, count: 2, item: 碎片, chance: 0.5 } }
                  - { text: 交給漁夫, talk: fisher, lines: [謝了。] }
                rewards:
                  - { passive_points: 1 }
              rares:
                kind: side
                name: 稀有
                giver: fisher
                after: ch1
                level: 10
                stages:
                  - { text: 打稀有怪, kill: { zone: coast, count: 2, rarity: rare } }
                  - { text: 到 12 級, level: 12 }
                  - { text: 完成地圖, maps: 1 }
                rewards:
                  - { stat: life.max, increased: 5 }
            """;

    static QuestConfig config() {
        final QuestConfig config = new QuestConfig();
        config.load(YamlNode.load("quests.yml", new StringReader(YAML)));
        assertEquals(List.of(), config.validate(List.of("camp", "coast"), List.of("coast"), List.of("identifier"), 60));
        return config;
    }

    /** 一直擲出同一個數字的亂數。 */
    static RandomGenerator fixed(final double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    /** 做一件事，把結果寫回 progress（模擬服務寫回角色進度）。 */
    static QuestLog apply(final Map<String, String> progress, final QuestLog log) {
        for (final QuestLog.Write write : log.writes()) {
            if (write.value() == null) {
                progress.remove(write.key());
            } else {
                progress.put(write.key(), write.value());
            }
        }
        return log;
    }

    static QuestLog log(final QuestConfig config, final Map<String, String> progress, final int level) {
        return new QuestLog(config, progress, level, null, 1000);
    }

    @Test
    void stateRoundTrips() {
        assertEquals(new QuestLog.State(QuestLog.Phase.ACTIVE, 2, 5, 0, null), QuestLog.parse(QuestLog.active(2, 5)));
        assertEquals(new QuestLog.State(QuestLog.Phase.DONE, 0, 0, 77, "mana"), QuestLog.parse(QuestLog.done(77, "mana")));
        assertEquals(new QuestLog.State(QuestLog.Phase.DONE, 0, 0, 77, null), QuestLog.parse(QuestLog.done(77, null)));
        assertEquals(QuestLog.Phase.LOCKED, QuestLog.parse(null).phase());
        assertEquals(QuestLog.Phase.LOCKED, QuestLog.parse("a.x.1").phase(), "寫壞了當作沒開始");
        assertEquals(QuestLog.Phase.LOCKED, QuestLog.parse("zzz").phase());
        assertEquals("q.ch1", QuestLog.key("ch1"));
    }

    @Test
    void storyFlowsFromIntroToChapterOne() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        // 新角色：序章自動開始
        QuestLog log = apply(progress, log(config, progress, 1));
        log.settleAll();
        apply(progress, log);
        assertEquals(QuestLog.active(0, 0), progress.get("q.intro"));
        assertInstanceOf(QuestLog.Accepted.class, log.events().getFirst());
        assertEquals(QuestLog.Phase.LOCKED, log.phase(config.quest("ch1")));

        // 跟主事說話 → 找鑑定師；跟漁夫說話沒用
        log = log(config, progress, 1);
        log.talk("fisher");
        assertTrue(log.writes().isEmpty());
        log = log(config, progress, 1);
        log.talk("chief");
        apply(progress, log);
        assertEquals(QuestLog.active(1, 0), progress.get("q.intro"));
        assertEquals(List.of(new QuestLog.Spoke(config.quest("intro"), 0), new QuestLog.Advanced(config.quest("intro"), 0, 1)),
                log.events());
        assertTrue(log.waitsFor("identifier"));
        assertFalse(log.waitsFor("chief"));

        // 檢查點早就發現過了：跟鑑定師說完話，序章直接完成，第一章自動開始
        progress.put("cp.coast.entrance", "1");
        log = log(config, progress, 1);
        log.talk("identifier");
        apply(progress, log);
        assertEquals(QuestLog.Phase.DONE, QuestLog.parse(progress.get("q.intro")).phase());
        assertEquals(QuestLog.active(0, 0), progress.get("q.ch1"));
        assertTrue(log.events().stream().anyMatch(event -> event instanceof QuestLog.Completed done
                && done.quest().id().equals("intro")));
        assertTrue(log.events().stream().anyMatch(event -> event instanceof QuestLog.Accepted accepted
                && accepted.quest().id().equals("ch1")));
    }

    @Test
    void bossStepsCountRetroactivelyAndChoicesWaitForTheDialog() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>(Map.of("q.intro", QuestLog.done(1, null), "q.ch1", QuestLog.active(0, 0)));
        // 已經打過 Boss 的角色：找完漁夫直接跳到回報
        progress.put("boss.coast", "123");
        QuestLog log = log(config, progress, 30);
        log.talk("fisher");
        apply(progress, log);
        assertEquals(QuestLog.active(2, 0), progress.get("q.ch1"));
        // 回報：要選獎勵，先等著
        log = log(config, progress, 30);
        log.talk("chief");
        apply(progress, log);
        assertEquals(List.of(new QuestLog.Waiting(config.quest("ch1"))), log.events());
        assertEquals(QuestLog.active(2, 0), progress.get("q.ch1"));
        // 選錯 NPC、選不存在的選項都不行
        assertFalse(log(config, progress, 30).choose(config.quest("ch1"), "fisher", "life"));
        assertFalse(log(config, progress, 30).choose(config.quest("ch1"), "chief", "nothing"));
        log = log(config, progress, 30);
        assertTrue(log.choose(config.quest("ch1"), "chief", "mana"));
        apply(progress, log);
        assertEquals(QuestLog.done(1000, "mana"), progress.get("q.ch1"));
        // 永久獎勵：天賦點與屬性從完成的任務算
        assertEquals(2, QuestRewards.passivePoints(config, progress));
        final List<Modifier> modifiers = QuestRewards.modifiers(config, progress);
        assertTrue(modifiers.contains(new Modifier(StatKeys.LIFE_MAX, ModType.FLAT, 10, java.util.Set.of(), "任務「序」")));
        assertTrue(modifiers.contains(new Modifier(StatKeys.RESIST_COLD, ModType.FLAT, 10, java.util.Set.of(), "任務「一」")));
        assertTrue(modifiers.contains(new Modifier(StatKeys.FLASK_MANA_RECOVERY, ModType.INCREASED, 0.25, java.util.Set.of(), "任務「一」")));
        assertFalse(modifiers.stream().anyMatch(modifier -> modifier.stat().equals(StatKeys.FLASK_LIFE_RECOVERY)), "沒選的不給");
        assertEquals(List.of("天賦點 +2", "最大生命 +10", "冰冷抗性 +10%", "魔力藥劑回復 +25%"), QuestRewards.summary(config, progress));
        // 第一章做完：支線可以接了（等級夠的那個）
        final QuestLog after = log(config, progress, 5);
        assertEquals(List.of(config.quest("charts")), after.offers("fisher"));
        assertEquals("需要等級 10", after.lockReason(config.quest("rares")));
    }

    @Test
    void killsAndCollectsCountOnlyWhatMatches() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>(Map.of("q.intro", QuestLog.done(1, null), "q.ch1", QuestLog.done(2, "life")));
        QuestLog log = log(config, progress, 10);
        assertTrue(log.accept(config.quest("charts")));
        assertTrue(log.accept(config.quest("rares")));
        apply(progress, log);
        assertFalse(log(config, progress, 10).accept(config.quest("charts")), "接過了");

        // 撿任務物品：擲到 0.6（> 0.5）普通怪不掉、魔法怪（1.0）掉；別的區域不算
        log = log(config, progress, 10);
        log.kill("coast", MonsterRarity.NORMAL, fixed(0.6));
        log.kill("forest", MonsterRarity.MAGIC, fixed(0.0));
        log.kill("coast", MonsterRarity.MAGIC, fixed(0.6));
        apply(progress, log);
        assertEquals(QuestLog.active(0, 1), progress.get("q.charts"));
        assertEquals(List.of(new QuestLog.Counted(config.quest("charts"), 0, 1, 2)), log.events());
        // 稀有怪：兩邊都算（撿到 2/2 → 回去找漁夫；稀有 1/2）
        log = log(config, progress, 10);
        log.kill("coast", MonsterRarity.RARE, fixed(0.99));
        apply(progress, log);
        assertEquals(QuestLog.active(1, 0), progress.get("q.charts"));
        assertEquals(QuestLog.active(0, 1), progress.get("q.rares"));
        // 普通怪不算稀有
        log = log(config, progress, 10);
        log.kill("coast", MonsterRarity.NORMAL, fixed(0.0));
        apply(progress, log);
        assertEquals(QuestLog.active(0, 1), progress.get("q.rares"));
        // 傳奇算稀有 → 下一步「到 12 級」：10 級還不行
        log = log(config, progress, 10);
        log.kill("coast", MonsterRarity.UNIQUE, fixed(0.0));
        apply(progress, log);
        assertEquals(QuestLog.active(1, 0), progress.get("q.rares"));
        // 升到 12 級：直接過；完成過一張地圖：任務完成
        progress.put("atlas.wreck_bay", "1");
        log = log(config, progress, 12);
        log.settleAll();
        apply(progress, log);
        assertEquals(QuestLog.Phase.DONE, QuestLog.parse(progress.get("q.rares")).phase());
        assertEquals(List.of("天賦點 +2", "最大生命 +10", "最大生命 +5%", "冰冷抗性 +10%", "生命藥劑回復 +25%"),
                QuestRewards.summary(config, progress));
    }

    @Test
    void trackingPutsThePinnedQuestFirstAndClearsWhenDone() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>(Map.of("q.intro", QuestLog.done(1, null), "q.ch1", QuestLog.active(1, 0),
                "q.charts", QuestLog.active(0, 1)));
        QuestLog log = log(config, progress, 10);
        assertEquals(List.of(config.quest("ch1"), config.quest("charts")), log.tracked(3), "主線排前面");
        assertTrue(log.track(config.quest("charts")));
        assertFalse(log.track(config.quest("intro")), "完成的不能追蹤");
        apply(progress, log);
        log = log(config, progress, 10);
        assertEquals(List.of(config.quest("charts"), config.quest("ch1")), log.tracked(3));
        assertEquals(List.of(config.quest("charts")), log.tracked(1));
        final List<String> lines = QuestText.trackerLines(log);
        assertEquals("<#f5b942><b>任務</b>", lines.getFirst());
        assertTrue(lines.get(1).startsWith("<white>▶ "), lines.toString());
        assertTrue(lines.get(2).endsWith("<white>1/2"), lines.toString());
        // 追蹤中的任務完成了：拿掉追蹤
        log = log(config, progress, 10);
        log.kill("coast", MonsterRarity.RARE, fixed(0.0));
        log.talk("fisher");
        apply(progress, log);
        assertEquals(QuestLog.Phase.DONE, QuestLog.parse(progress.get("q.charts")).phase());
        assertNull(progress.get(QuestLog.TRACK));
    }

    @Test
    void trialGoalCountsCompletedTrials() {
        final QuestConfig config = new QuestConfig();
        config.load(YamlNode.load("quests.yml", new StringReader("""
                npcs:
                  guard: { name: 星衛 }
                quests:
                  gate:
                    kind: side
                    name: 門
                    giver: guard
                    stages:
                      - { text: 聽她說, talk: guard, lines: [門開著。] }
                      - { text: 完成試煉, trial: ember }
                      - { text: 回報, talk: guard, lines: [很好。] }
                """)));
        final Map<String, String> progress = new HashMap<>();
        QuestLog log = log(config, progress, 40);
        assertTrue(log.accept(config.quest("gate")));
        log.talk("guard");
        apply(progress, log);
        assertEquals(QuestLog.active(1, 0), progress.get("q.gate"), "還沒完成試煉：停在這一步");
        progress.put("trial.ember", "100"); // 昇華模組記的
        log = log(config, progress, 40);
        log.settleAll();
        assertEquals(QuestLog.active(2, 0), log.progress().get("q.gate"), "完成過試煉就往下走");
        assertTrue(log.waitsFor("guard"));
    }

    @Test
    void bossKillAdvancesEvenWithoutTheProgressKey() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>(Map.of("q.intro", QuestLog.done(1, null), "q.ch1", QuestLog.active(1, 0)));
        final QuestLog log = log(config, progress, 10);
        log.boss("forest");
        assertTrue(log.writes().isEmpty(), "別的區域的 Boss");
        log.boss("coast");
        assertEquals(QuestLog.active(2, 0), log.progress().get("q.ch1"));
        assertTrue(log.waitsFor("chief"));
    }

    @Test
    void adminCommandsAndShrunkQuests() {
        final QuestConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        QuestLog log = log(config, progress, 1);
        log.forceComplete(config.quest("ch1"), null);
        apply(progress, log);
        assertEquals(QuestLog.done(1000, "life"), progress.get("q.ch1"), "沒給選項就選第一個");
        log = log(config, progress, 1);
        log.reset(config.quest("ch1"));
        apply(progress, log);
        assertNull(progress.get("q.ch1"));
        // 任務改短了：停在最後一步
        progress.put("q.charts", QuestLog.active(9, 0));
        assertEquals(1, log(config, progress, 1).state(config.quest("charts")).stage());
    }
}
