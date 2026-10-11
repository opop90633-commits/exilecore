package tw.exilecore.map;

import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/map_events.yml 讀得起來、數字合理、流寇的詞綴真的存在。 */
class MapEventConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedFileLoads() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        assertEquals(2, config.minPerMap());
        assertEquals(3, config.maxPerMap());
        assertEquals(100, config.totalWeight());
        assertEquals(5, config.shrines().size(), "五種神龕");
        assertEquals(3, config.boxes().size(), "三種寶箱");
        assertEquals(40, config.shrineDuration());
        // 疾速神龕：30% 寫成小數 0.3
        final MapEventConfig.Shrine acceleration = config.shrines().stream().filter(shrine -> shrine.id().equals("acceleration")).findFirst().orElseThrow();
        assertEquals(3, acceleration.mods().size());
        assertEquals(0.30, acceleration.mods().getFirst().value(), 1e-9);
        assertEquals(ModType.INCREASED, acceleration.mods().getFirst().type());
        // 再生神龕是固定值
        final MapEventConfig.Shrine regeneration = config.shrines().stream().filter(shrine -> shrine.id().equals("regeneration")).findFirst().orElseThrow();
        assertEquals(3.0, regeneration.mods().getFirst().value(), 1e-9);
        // 星圖寶箱掉地圖、匠心寶箱掉通貨
        final MapEventConfig.Box cartographer = config.boxes().stream().filter(box -> box.id().equals("cartographer")).findFirst().orElseThrow();
        assertEquals(1, cartographer.minMaps());
        assertEquals(2, cartographer.maxMaps());
        final MapEventConfig.Box artisan = config.boxes().stream().filter(box -> box.id().equals("artisan")).findFirst().orElseThrow();
        // 2026-10：3–6 → 2–4（低階通貨權重減半，好通貨的數量跟以前差不多）
        assertEquals(2, artisan.minCurrency());
        assertEquals(4, artisan.maxCurrency());
        assertEquals(0.5, config.breachQuantity(), 1e-9);
        assertEquals(4.0, config.exileLife(), 1e-9);
        assertFalse(config.exileNames().isEmpty());
    }

    @Test
    void ritualAndDeliriumLoad() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.Ritual ritual = config.ritual();
        assertEquals(0.3, ritual.chance(), 1e-9);
        assertEquals(3, ritual.altars());
        assertEquals(9.0, ritual.radius(), 1e-9);
        assertEquals(3, ritual.minWaves());
        assertEquals(4, ritual.maxWaves());
        assertEquals(12, ritual.tributeFor(tw.exilecore.monster.MonsterRarity.NORMAL, 0));
        assertEquals(15, ritual.tributeFor(tw.exilecore.monster.MonsterRarity.NORMAL, 1), "第二座 +25%");
        assertEquals(180, ritual.tributeFor(tw.exilecore.monster.MonsterRarity.RARE, 2), "第三座 +50%");
        assertEquals(7, ritual.rewards());
        assertEquals(0.2, ritual.rerollCost(), 1e-9);
        assertEquals(1.5, ritual.rerollGrowth(), 1e-9);
        assertEquals(30, ritual.rewardWeights().get(MapEventConfig.Reward.OMEN), "祭壇以星籤為主");
        for (final MapEventConfig.Reward kind : ritual.rewardWeights().keySet()) {
            assertNotNull(ritual.prices().get(kind), kind + " 要有價格");
            assertTrue(ritual.prices().get(kind)[0] <= ritual.prices().get(kind)[1]);
        }
        assertFalse(ritual.rewardWeights().containsKey(MapEventConfig.Reward.EMOTION), "蒸餾情緒是幻霧的");

        final MapEventConfig.Delirium delirium = config.delirium();
        assertEquals(0.3, delirium.chance(), 1e-9);
        assertEquals(30, delirium.duration(), 1e-9, "2026-10：75 → 30 秒");
        assertEquals(0.6, delirium.maxDifficulty(), 1e-9, "60% 寫成小數");
        assertEquals(0.0, delirium.difficultyAt(0), 1e-9);
        assertEquals(0.3, delirium.difficultyAt(12.5), 1e-9, "25 秒升到最高（2026-10：60 → 25）：12.5 秒時一半");
        assertEquals(0.6, delirium.difficultyAt(999), 1e-9);
        assertEquals(List.of(20, 50, 100, 170), delirium.thresholds());
        assertEquals(0, delirium.sharesFor(19));
        assertEquals(1, delirium.sharesFor(20));
        assertEquals(4, delirium.sharesFor(500));
        assertEquals(2, delirium.rewards());
        assertEquals(40, delirium.rewardWeights().get(MapEventConfig.Reward.EMOTION));
        assertEquals("distilled_ire", delirium.emotion(1));
        assertEquals("distilled_paranoia", delirium.emotion(4));
        assertEquals("distilled_paranoia", delirium.emotion(9), "沒有這一階用最後一個");
        assertEquals(4, delirium.emotionWeights().size());
        // 蒸餾情緒真的在 currencies.yml 裡，而且是注入用的
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(DATA);
        for (final String id : delirium.emotions()) {
            assertNotNull(currencies.get(id), id);
            assertEquals(tw.exilecore.currency.CurrencyOperation.INSTIL, currencies.get(id).operation(), id);
            assertEquals(0, currencies.get(id).dropWeight(), "怪物不掉：" + id);
        }
    }

    @Test
    void exileModsExistInMonstersYml() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        for (final String id : config.exileMods()) {
            assertNotNull(monsters.mod(id), "map_events.yml 的 exile.mods 要是 monsters.yml 裡的詞綴：" + id);
        }
        assertTrue(config.exileMods().stream().anyMatch(id -> monsters.mod(id).hasSkills()), "流寇要有帶招式的詞綴");
    }

    @Test
    void shrineStatsAreKnown() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final Set<String> known = Set.of("movement.speed", "attack.speed", "cast.speed", "damage", "armour", "evasion", "es.max",
                "life.regen_percent", "drop.rarity", "drop.quantity");
        for (final MapEventConfig.Shrine shrine : config.shrines()) {
            for (final Modifier mod : shrine.mods()) {
                assertTrue(known.contains(mod.stat()), shrine.id() + " 用了不認得的屬性 " + mod.stat());
            }
        }
    }

    @Test
    void brokenFilesAreRejected(@org.junit.jupiter.api.io.TempDir final Path temp) throws IOException {
        Files.writeString(temp.resolve("map_events.yml"), "settings:\n  weights: { tower: 5 }\n");
        assertThrows(IllegalArgumentException.class, () -> new MapEventConfig().load(temp), "不認得的事件");
        Files.writeString(temp.resolve("map_events.yml"), "shrine:\n  kinds:\n    x: { name: 空 }\n");
        assertThrows(IllegalArgumentException.class, () -> new MapEventConfig().load(temp), "神龕沒有 mods");
        Files.writeString(temp.resolve("map_events.yml"), "shrine:\n  kinds:\n    x: { name: 怪, mods: [{ stat: damage, type: BIG, value: 1 }] }\n");
        assertThrows(IllegalArgumentException.class, () -> new MapEventConfig().load(temp), "type 寫錯");
        // 沒有檔案：預設
        final MapEventConfig defaults = new MapEventConfig();
        defaults.load(temp.resolve("nowhere"));
        assertEquals(100, defaults.totalWeight());
        assertEquals(3, defaults.shrines().size());
    }
}
