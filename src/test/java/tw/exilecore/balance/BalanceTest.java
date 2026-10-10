package tw.exilecore.balance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真實資料檔跑平衡模型：出貨的怪物生命、傷害、法術成長要對得上 balance.yml 的目標。 */
class BalanceTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static SkillConfig skills;
    static MonsterConfig monsters;
    static BalanceConfig config;
    static BalanceModel model;
    static ZoneConfig zones;
    static List<BalanceModel.Row> rows;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
        config = new BalanceConfig();
        config.load(DATA);
        model = new BalanceModel(items, skills, monsters, config);
        zones = new ZoneConfig();
        zones.load(DATA);
        rows = model.rows(100);
    }

    @Test
    void referenceBuildsResolve() {
        assertEquals(List.of(), model.problems());
        assertEquals(CharacterClass.MARAUDER, config.attack().characterClass());
        assertEquals("heavy_strike", config.attack().skill());
        assertEquals("fireball", config.spell().skill());
        assertEquals(0.4, config.killSeconds(1), 1e-9);
        assertEquals(1.0, config.killSeconds(60), 1e-9);
        assertEquals(0.4 * Math.pow(2.5, 29 / 59.0), config.killSeconds(30), 1e-9, "目標也是等比內插");
        assertEquals(1.25, config.killSeconds(84), 1e-9, "T16 地圖");
        assertEquals(1.4, config.killSeconds(100), 1e-9);
        assertEquals(84, config.itemLevelCap(), "裝備最高到 T16 地圖的物品等級");
        assertEquals(2, config.supportSockets(14));
        assertEquals(3, config.supportSockets(15));
        assertEquals(5, config.supportSockets(60));
    }

    /** 1 到 100 級每一級（地圖到 T16 = 84 級，角色到 100 級）。 */
    @Test
    void shippedCurvesHitTheTargetsAtEveryLevel() {
        assertEquals(100, rows.size());
        double previousAttack = 0;
        for (final BalanceModel.Row row : rows) {
            final int level = row.level();
            final double kill = row.attackSeconds() / row.targetSeconds();
            assertTrue(kill > 0.8 && kill < 1.25, "等級 " + level + " 攻擊擊殺 " + row.attackSeconds() + " 秒，目標 " + row.targetSeconds());
            assertTrue(row.spellRatio() > 0.65 && row.spellRatio() < 0.95, "等級 " + level + " 法術是攻擊的 " + row.spellRatio());
            final double hit = row.hitPercent() / row.targetHitPercent();
            assertTrue(hit > 0.8 && hit < 1.25, "等級 " + level + " 被打一下扣 " + row.hitPercent() + "%，目標 " + row.targetHitPercent());
            assertTrue(row.attack().dps() >= previousAttack * 0.98, "等級 " + level + " 參考角色的輸出不該變低");
            previousAttack = row.attack().dps();
            // 實際要揮完整下：平均至少 1 下，實際秒數不會比換算的快
            assertTrue(row.attack().swingsToKill() >= 1.0 && row.attack().swingsToKill() < 3.5, "等級 " + level + " 平均 " + row.attack().swingsToKill() + " 下");
            assertTrue(row.attack().actualSeconds() >= row.attackSeconds() * 0.95, "等級 " + level);
        }
        // 實際打死一隻的秒數也是越後面越久（前期一下就死）
        assertTrue(rows.get(59).attack().actualSeconds() > rows.get(0).attack().actualSeconds() * 1.5);
    }

    @Test
    void zoneBossesHitTheirFightTimes() {
        final List<BalanceModel.BossSpec> specs = BalanceModel.bossSpecs(zones);
        assertEquals(5, specs.size());
        double previous = 0;
        for (final BalanceModel.BossSpec spec : specs) {
            final BalanceModel.BossRow boss = model.boss(spec);
            assertEquals(1.0, boss.attackSeconds() / boss.targetSeconds(), 0.1, spec.zone() + " 要打 " + boss.attackSeconds() + " 秒");
            assertTrue(boss.attackSeconds() > previous, "後面的 Boss 打比較久");
            previous = boss.attackSeconds();
        }
    }

    @Test
    void monsterLifeGrowsSmoothly() {
        double previous = monsters.lifeAt(1);
        for (int level = 2; level <= 100; level++) {
            final double life = monsters.lifeAt(level);
            final double growth = life / previous - 1;
            if (level <= 60) {
                assertTrue(growth > 0.03 && growth < 0.15, "等級 " + level + " 生命成長 " + growth);
            } else if (level <= 84) {
                assertTrue(growth > 0.01 && growth < 0.08, "地圖 " + level + " 級生命成長 " + growth);
            } else {
                assertTrue(growth > 0 && growth < 0.05, "84 級以後（只剩天賦的成長）" + level + " 級生命成長 " + growth);
            }
            previous = life;
        }
        // 經驗：地圖的怪越高級越多（PoE 的怪物經驗表），91 級以後不再增加
        assertTrue(monsters.experienceAt(84) > monsters.experienceAt(60));
        assertTrue(monsters.experienceAt(91) > monsters.experienceAt(90));
        assertEquals(monsters.experienceAt(91), monsters.experienceAt(100), 1e-9);
    }

    @Test
    void suggestionsReproduceTheShippedAnchors() {
        final BalanceModel.Suggestions suggestions = model.suggest(rows);
        for (final Map.Entry<Integer, Double> entry : monsters.lifeAnchors().entrySet()) {
            final double suggested = suggestions.life().get(entry.getKey());
            assertEquals(1.0, suggested / entry.getValue(), 0.05, "生命錨點 " + entry.getKey());
        }
        for (final Map.Entry<Integer, Double> entry : skills.spellGrowthAnchors().entrySet()) {
            assertEquals(1.0, suggestions.spellGrowth().get(entry.getKey()) / entry.getValue(), 0.05, "法術成長錨點 " + entry.getKey());
        }
        assertEquals(monsters.damageAnchors().keySet(), suggestions.damage().keySet());
    }

    @Test
    void anchorsWithoutEnoughDataAreExtendedByTheCurrentRatios() {
        final NavigableMap<Integer, Double> current = new TreeMap<>(monsters.lifeAnchors());
        final Map<Integer, Double> targets = new TreeMap<>();
        for (int level = 1; level <= 61; level++) {
            targets.put(level, monsters.lifeAt(level));
        }
        // 上限 61：70 級只有一個點，不拿去貼，照原本 70 ÷ 60 的比例延伸
        final NavigableMap<Integer, Double> cap61 = BalanceModel.fitAndExtend(current, targets, 61);
        assertEquals(current.get(70) / current.get(60), cap61.get(70) / cap61.get(60), 1e-9);
        assertEquals(1.0, cap61.get(60) / current.get(60), 0.02);
        // 上限 41：50 級只有一個點，50、60 照原本的比例從 40 延伸
        final Map<Integer, Double> upTo41 = new TreeMap<>();
        for (int level = 1; level <= 41; level++) {
            upTo41.put(level, monsters.lifeAt(level));
        }
        final NavigableMap<Integer, Double> cap41 = BalanceModel.fitAndExtend(current, upTo41, 41);
        assertEquals(current.get(50) / current.get(40), cap41.get(50) / cap41.get(40), 1e-9);
        assertEquals(current.get(60) / current.get(40), cap41.get(60) / cap41.get(40), 1e-9);
        // 法術成長只有寶石需求等級的點：51–63 級沒有點，64 照比例從 50 延伸
        final NavigableMap<Integer, Double> growth = new TreeMap<>(skills.spellGrowthAnchors());
        final Map<Integer, Double> requirementTargets = new TreeMap<>();
        for (int skillLevel = 1; skillLevel <= 20; skillLevel++) {
            final int required = skills.requiredLevel(skillLevel);
            if (required <= 53) {
                requirementTargets.put(required, skills.spellGrowth(required));
            }
        }
        final NavigableMap<Integer, Double> cap53 = BalanceModel.fitAndExtend(growth, requirementTargets, 53);
        assertEquals(growth.get(64) / growth.get(50), cap53.get(64) / cap53.get(50), 1e-9);
        assertEquals(growth.get(80) / growth.get(50), cap53.get(80) / cap53.get(50), 1e-9);
    }

    @Test
    void referenceThatCannotKillDoesNotHang(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("balance.yml"), "reference:\n  tree: { damage_per_level: -5 }\n");
        final BalanceConfig weak = new BalanceConfig();
        weak.load(dir);
        final BalanceModel.Row row = new BalanceModel(items, skills, monsters, weak).row(60);
        assertTrue(Double.isInfinite(row.attack().swingsToKill()), "打不死：∞ 下，不模擬");
        assertTrue(Double.isInfinite(row.attack().actualSeconds()));
    }

    @Test
    void curveFitRecoversAGeometricCurve() {
        final Map<Integer, Double> targets = new TreeMap<>();
        for (int level = 1; level <= 60; level++) {
            targets.put(level, 10 * Math.pow(1.08, level - 1));
        }
        final NavigableMap<Integer, Double> fitted = CurveFit.fit(new int[] {1, 20, 40, 60}, targets);
        assertEquals(1.0, fitted.get(1) / 10.0, 1e-4);
        assertEquals(1.0, fitted.get(60) / (10 * Math.pow(1.08, 59)), 1e-4);
        assertEquals(1.0, fitted.get(20) / (10 * Math.pow(1.08, 19)), 1e-4);
        assertEquals(Math.sqrt(10 * 20.0), CurveFit.fit(new int[] {5}, Map.of(1, 10.0, 9, 20.0)).get(5), 1e-9, "一個錨點 = 幾何平均");
        assertTrue(CurveFit.fit(new int[] {1, 10}, Map.of()).isEmpty());
    }

    @Test
    void gearValuesBlendBetweenTierUnlocks() {
        // 附加物理：T5 1 級、T4 14 級、T3 28 級…；中等裝備（第二好）在 28 級是 T4 的中間值
        final var affix = items.affix("added_phys");
        assertEquals(1.5, BalanceModel.gearValues(affix, 1, 2).get("min"), 1e-9);
        assertEquals(1.5, BalanceModel.gearValues(affix, 14, 2).get("min"), 1e-9, "14 級 T4 剛解鎖，第二好還是 T5");
        assertEquals(5.0, BalanceModel.gearValues(affix, 28, 2).get("min"), 1e-9);
        final double mid = BalanceModel.gearValues(affix, 21, 2).get("min");
        assertEquals(1.5 * Math.sqrt(5.0 / 1.5), mid, 1e-9, "兩個解鎖等級中間等比過渡");
        assertEquals(17.0, BalanceModel.gearValues(affix, 55, 1).get("min"), 1e-9, "55 級最好的階級（代號 1）");
        assertEquals(17 * Math.pow(20 / 17.0, 5 / 13.0), BalanceModel.gearValues(affix, 60, 1).get("min"), 1e-9,
                "55 到 68 級之間往下一個階級（代號 0）等比過渡");
        assertEquals(23.0, BalanceModel.gearValues(affix, 84, 1).get("min"), 1e-9, "78 級起有代號 -1 的階級");
        assertEquals(20.0, BalanceModel.gearValues(affix, 84, 2).get("min"), 1e-9, "84 級的中等裝備是代號 0");
        double previous = 0;
        for (int level = 1; level <= 100; level++) {
            final double value = BalanceModel.gearValues(affix, level, 2).get("max");
            assertTrue(value >= previous, "等級 " + level);
            previous = value;
        }
        assertFalse(BalanceModel.gearValues(affix, 0, 2).containsKey("min"), "0 級什麼都出不了");
    }

    @Test
    void problemsCatchSkillsThatDoNotFitTheBuild(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("balance.yml"), """
                reference:
                  attack: { class: ranger, weapon: bow, skill: heavy_strike }
                  spell: { class: witch, weapon: wand, skill: split_arrow }
                """);
        final BalanceConfig wrong = new BalanceConfig();
        wrong.load(dir);
        final List<String> problems = new BalanceModel(items, skills, monsters, wrong).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("heavy_strike") && p.contains("bow")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("split_arrow") && p.contains("不是法術")), problems.toString());
    }

    @Test
    void configFallsBackAndRejectsUnknownClass(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("balance.yml"), "targets:\n  spell_vs_attack: 0.5\n");
        final BalanceConfig partial = new BalanceConfig();
        partial.load(dir);
        assertEquals(0.5, partial.spellVsAttack(), 1e-9);
        assertEquals(config.attack(), partial.attack(), "沒寫的用預設（跟資料檔一樣）");
        assertEquals(config.spell(), partial.spell());
        assertEquals(config.killSeconds(33), partial.killSeconds(33), 1e-9);
        assertEquals(config.bossSeconds(36), partial.bossSeconds(36), 1e-9);
        Files.writeString(dir.resolve("balance.yml"), "reference:\n  attack: { class: paladin, weapon: hand_axe, skill: heavy_strike }\n");
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> partial.load(dir));
        assertTrue(error.getMessage().contains("paladin"));
        assertEquals(0.5, partial.spellVsAttack(), 1e-9, "寫錯時保留原本的設定");
    }
}
