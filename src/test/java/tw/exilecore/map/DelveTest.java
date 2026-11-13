package tw.exilecore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.currency.CurrencyRepository;

/** 2026-11-07 照站長「地心礦坑簡化版、新增深坑鑰匙、能組隊、排行不重置」：星脈深坑的設定與排行。 */
class DelveTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedSettings() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.DelveSettings delve = config.delve();
        assertEquals(1, delve.cost());
        assertEquals(0, delve.difficulty(1), 1e-9, "第一層不加");
        assertEquals(0.5, delve.difficulty(11), 1e-9, "每層 +5%");
        assertEquals(0, delve.levelBonus(1));
        assertEquals(20, delve.levelBonus(80), "等級最多 +20");
        assertEquals(16, delve.count(1, 1));
        assertTrue(delve.guardianFloor(10) && delve.guardianFloor(20) && !delve.guardianFloor(9));
        assertEquals(2 * (int) Math.round(1 + 3 * 9 / 50.0), delve.rewards(10), "守衛層獎勵兩倍");
        assertEquals(2 + 1 + 10, delve.azurite(10));
        assertTrue(delve.darkRate(30, 0) > delve.darkRate(1, 0), "越深黑暗漲越快");
        assertTrue(delve.darkRate(1, 5) < delve.darkRate(1, 0), "燈芯升級漲得慢");
        assertEquals(20, delve.upgradeCost(0));
        assertEquals(-1, delve.upgradeCost(5), "滿級");
        assertEquals(1, delve.startFloor(0, 0));
        assertEquals(25, delve.startFloor(30, 0), "最深 − 5");
        assertEquals(29, delve.startFloor(30, 9), "深掘最少退 1 層");
        // 2026-11-07 照站長「選檢查點」：第 1 層、每過一隻守衛的下一層，加上最深的那層
        assertEquals(java.util.List.of(1), delve.checkpoints(0, 0));
        assertEquals(java.util.List.of(1, 11, 21, 25), delve.checkpoints(30, 0));
        assertEquals(java.util.List.of(1, 11, 21), delve.checkpoints(26, 0), "剛好在檢查點不重複");
        assertEquals(0.15, delve.keyChance(), 1e-9);
    }

    @Test
    void vaalDropsUseShippedCurrencies() throws IOException {
        // 2026-11-07 照站長：星脈深坑的怪物掉瓦爾通貨（map_events.yml 的 delve.vaal_drops）
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.VaalDrops drops = config.vaalDrops();
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertEquals(11, drops.weights().size());
        for (final String id : drops.weights().keySet()) {
            assertNotNull(currencies.get(id), "currencies.yml 有 " + id);
        }
        assertEquals(0.006, drops.chanceAt(0, 1), 1e-9, "普通怪 0.6%");
        assertEquals(0.012, drops.chanceAt(0, 51), 1e-9, "第 51 層兩倍");
        assertEquals(1.0, drops.chanceAt(3, 1), 1e-9, "守衛一定掉");
        assertTrue(drops.weights().get("vaal_infuser_armour") > drops.weights().get("architect_orb"), "灌注器比雙蝕石常見");
        assertEquals(0.05, drops.guardianUnique(), 1e-9, "2026-11-07 照站長：守衛傳奇 5%");
    }

    @Test
    void regaliaOnlyDropsInDelve() throws IOException {
        // 2026-11-07 照站長「祭紋華袍改成會掉落，只在深坑小怪掉、不能賭博」
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.VaalDrops drops = config.vaalDrops();
        assertEquals("body_str_dex_int_01", drops.baseId());
        assertEquals(0.002, drops.baseChance(), 1e-9);
        final tw.exilecore.item.ItemRepository items = new tw.exilecore.item.ItemRepository();
        items.load(DATA);
        assertNotNull(items.base(drops.baseId()));
        assertFalse(items.retiredBase(drops.baseId()), "會掉落了");
        assertTrue(items.heistOnlyBase(drops.baseId()), "特殊來源的基底");
        assertTrue(items.basesDroppableAt(100).stream().noneMatch(b -> b.id().equals(drops.baseId())), "一般掉落沒有");
        assertTrue(items.basesSoldAt(100).stream().noneMatch(b -> b.id().equals(drops.baseId())), "商人、賭博沒有");
        assertTrue(items.heistOnlyBasesAt(100).stream().noneMatch(b -> b.id().equals(drops.baseId())), "劫盜不掉");
    }

    @Test
    void currenciesExist() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertNotNull(currencies.get(config.delve().key()), "currencies.yml 有深坑鑰匙");
        assertNotNull(currencies.get(config.delve().azurite()), "currencies.yml 有藍晶");
    }

    /** 2026-11-07 照站長「守衛新增掉落星坑 BOSS 鑰匙，然後再做一隻 BOSS」：鑰匙設定、20% 掉落、讀得到通貨。 */
    @Test
    void bossKeyFromGuardians() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.DelveBossSettings boss = config.delveBoss();
        assertEquals("delve_boss_key", boss.key());
        assertEquals(0.2, boss.keyChance(), 1e-9, "守衛每隻每人 20%");
        assertEquals(1, boss.cost());
        assertEquals("晶淵王座", boss.name());
        assertTrue(boss.dropsKey(0.1999) && !boss.dropsKey(0.2) && !boss.dropsKey(0.9));
        assertFalse(new MapEventConfig.DelveBossSettings("k", 0, "x", 1).dropsKey(0), "0% 不掉");
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertNotNull(currencies.get(boss.key()), "currencies.yml 有深坑首領鑰匙");
        assertEquals(tw.exilecore.currency.CurrencyGroups.Group.VAAL, tw.exilecore.currency.CurrencyGroups.of(currencies.get(boss.key())), "放瓦爾通貨那組");
        // 沒寫照預設；寫了照寫的（chance 是 %）
        final MapEventConfig.DelveBossSettings parsed = MapEventConfig.parseDelveBoss(tw.exilecore.core.config.YamlNode.load("delve",
                new java.io.StringReader("boss_key: { id: other_key, chance: 35 }\nboss: { name: 測試王座, cost: 3 }\n")), MapEventConfig.DelveBossSettings.DEFAULTS);
        assertEquals(new MapEventConfig.DelveBossSettings("other_key", 0.35, "測試王座", 3), parsed);
        assertEquals(MapEventConfig.DelveBossSettings.DEFAULTS,
                MapEventConfig.parseDelveBoss(tw.exilecore.core.config.YamlNode.load("delve", new java.io.StringReader("name: x\n")), MapEventConfig.DelveBossSettings.DEFAULTS));
    }

    /** 2026-11-07：深坑首領是災厄級（同其他三隻）、招式組躲得掉、傳奇每件各自 1%、必掉藍晶。 */
    @Test
    void bossIsCalamityWithItsOwnKit() throws IOException {
        final MapConfig maps = new MapConfig();
        maps.load(DATA);
        final MapConfig.EventBoss boss = maps.eventBoss(Delve.BOSS_KIND);
        assertNotNull(boss, "maps.yml 的 event_bosses 有 delve_boss");
        assertEquals(94, boss.level());
        assertEquals(8_000_000, boss.recommendedDps(), 1e-9); // 2026-11-13 照站長：怪物血量 ×2、顯示一致（之前 400 萬）
        assertEquals(0.01, boss.uniqueChance(), 1e-9, "sources 寫 delve_boss 的傳奇每件各自 1%");
        assertTrue(boss.extra().stream().anyMatch(e -> "currency".equals(e.kind()) && "azurite".equals(e.id()) && e.chance() >= 1), "必掉藍晶");
        assertEquals(3, boss.boss().phases().size());
        assertTrue(boss.boss().phases().stream().allMatch(p -> p.mechanic() != null && p.mechanic().kind() == PhaseMechanic.Kind.OPENING), "每階段一個破綻");
        assertEquals(240, boss.boss().enrage().after(), 1e-9);
        assertEquals(1, boss.boss().hazards().size());
        final tw.exilecore.skill.BossSkillConfig skills = new tw.exilecore.skill.BossSkillConfig();
        skills.load(DATA);
        int special = 0;
        for (final tw.exilecore.skill.BossSkillUse use : boss.boss().skills()) {
            final tw.exilecore.skill.BossSkill skill = skills.skill(use.id());
            assertNotNull(skill, use.id());
            if (tw.exilecore.skill.BossAlerts.special(skill)) {
                special++;
            }
            final String hint = tw.exilecore.skill.BossAlerts.hint(skill);
            assertTrue(hint.codePointCount(0, hint.length()) <= tw.exilecore.skill.BossSkill.MAX_HINT, use.id() + " 的提示太長");
            assertTrue(skill.telegraph() >= 1.0 || skill.kind() == tw.exilecore.skill.BossSkill.Kind.SLAM && skill.telegraph() + skill.delay() >= 1.5,
                    use.id() + "：前搖至少 1 秒");
        }
        assertTrue(special >= 8, "特殊招式夠多：" + special);
        for (int phase = 1; phase <= 3; phase++) {
            final int p = phase;
            assertTrue(boss.boss().skills().stream().filter(u -> u.fromPhase() == p).count() >= 2, "第 " + phase + " 階段至少兩招新的");
        }
        assertTrue(skills.skill(boss.boss().hazards().getFirst().skill()).kind().hazardCapable());
    }

    @Test
    void ladderKeepsDeepestAndSurvivesReload(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("delve_ladder.json");
        final DelveLadder ladder = new DelveLadder(file);
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        assertTrue(ladder.record(a, "A", "甲", "戰士", 12, 100));
        assertFalse(ladder.record(a, "A", "甲", "戰士", 8, 200), "比較淺不算");
        assertTrue(ladder.record(b, "B", "乙", "女巫", 12, 50));
        assertEquals(b, ladder.top(10).get(0).getKey(), "一樣深的先打到的在前");
        assertTrue(ladder.record(a, "A", "甲", "戰士", 20, 300));
        ladder.save();
        assertTrue(Files.exists(file));
        final DelveLadder loaded = new DelveLadder(file);
        loaded.load();
        assertEquals(20, loaded.best(a));
        assertEquals(1, loaded.rank(a));
        assertEquals(2, loaded.rank(b));
        assertEquals(20, loaded.serverBest());
    }
}
