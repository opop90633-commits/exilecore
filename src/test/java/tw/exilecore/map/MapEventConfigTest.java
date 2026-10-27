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
        // 2026-10-18：深淵裂縫、保險箱
        assertEquals(15, config.weights().get(MapEventConfig.Kind.ABYSS), "深淵裂縫的權重 15");
        assertFalse(config.weights().containsKey(MapEventConfig.Kind.SAFE), "保險箱不照權重抽");
        assertEquals(0.35, config.safe().chance(), 1e-9);
        assertEquals(0.01, config.uniqueChance(MapEventConfig.Kind.ABYSS), 1e-9);
        assertEquals(0.0, config.uniqueChance(MapEventConfig.Kind.SAFE), 1e-9, "保險箱沒有專屬傳奇");
    }

    @Test
    void abyssAndSafeLoad() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.Abyss abyss = config.abyss();
        assertEquals(1.5, abyss.advanceSeconds(), 1e-9);
        assertEquals(3, abyss.advance());
        assertEquals(30, abyss.minLength());
        assertEquals(45, abyss.maxLength());
        assertEquals(2, abyss.minWave());
        assertEquals(3, abyss.maxWave());
        assertEquals(0.4, abyss.quantityBonus(), 1e-9, "40% 寫成小數");
        assertEquals(8, abyss.follow(), 1e-9);
        assertEquals(6, abyss.stallSeconds(), 1e-9);
        assertEquals(3.0, abyss.bossLife(), 1e-9);
        assertEquals(List.of("slammer", "swift"), abyss.bossMods());
        assertEquals(3, abyss.rolls());
        assertEquals(0.6, abyss.chestQuantity(), 1e-9);
        assertEquals(0.8, abyss.chestRarity(), 1e-9);
        assertEquals(1, abyss.minShards()); // 2026-10-18：擁有者決定先給 1 顆
        assertEquals(1, abyss.maxShards());
        assertEquals(20, abyss.radius(), 1e-9);
        assertEquals(20, abyss.linger(), 1e-9);
        // 招牌通貨真的在 currencies.yml 裡
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(DATA);
        assertNotNull(currencies.get(abyss.shardCurrency()), "abyss.shard_currency：" + abyss.shardCurrency());
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        for (final String id : abyss.bossMods()) {
            assertNotNull(monsters.mod(id), "abyss.boss.mods 要是 monsters.yml 裡的詞綴：" + id);
        }
        final MapEventConfig.Safe safe = config.safe();
        assertEquals(0.35, safe.chance(), 1e-9);
        assertEquals(20, safe.unlockSeconds(), 1e-9);
        assertEquals(4, safe.waveSeconds(), 1e-9);
        assertEquals(3, safe.minWave());
        assertEquals(5, safe.maxWave());
        assertEquals(6, safe.radius(), 1e-9);
        assertEquals(15, safe.grace(), 1e-9);
        assertEquals(2, safe.minRolls());
        assertEquals(3, safe.maxRolls());
        assertEquals(0.5, safe.quantityBonus(), 1e-9);
        assertEquals(1.0, safe.rarityBonus(), 1e-9);
        assertEquals(2, safe.minCurrency());
        assertEquals(4, safe.maxCurrency());
        assertEquals(0.2, safe.blueprintChance(), 1e-9);
        assertEquals(20, safe.rewardRadius(), 1e-9);
        // 程式的預設值跟出貨的檔案一樣
        final MapEventConfig defaults = new MapEventConfig();
        assertEquals(defaults.abyss(), abyss);
        assertEquals(defaults.safe(), safe);
        assertEquals(defaults.weights(), config.weights());
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
        assertEquals(65, ritual.rewardWeights().get(MapEventConfig.Reward.OMEN), "祭壇以星籤為主（2026-10-27：30 → 65）");
        // 2026-10-27 照 PoE2 0.5：只有星籤與傳奇
        assertEquals(java.util.Set.of(MapEventConfig.Reward.OMEN, MapEventConfig.Reward.UNIQUE), ritual.rewardWeights().keySet());
        assertEquals(10000, config.ritualBankThreshold(), "剩下的貢品存滿 10000 換一張入場券");
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
        // 2026-10-27 照 PoE2：十種情緒（這次之前四種，第 9 階用最後一個＝妄想）
        assertEquals("distilled_isolation", delirium.emotion(10));
        assertEquals("distilled_contempt", delirium.emotion(20), "沒有這一階用最後一個");
        assertEquals(13, delirium.emotionWeights().size(), "十種＋三種強效（塗油限定核心用）");
        // 蒸餾情緒真的在 currencies.yml 裡，而且是注入用的
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(DATA);
        for (final String id : delirium.emotions()) {
            assertNotNull(currencies.get(id), id);
            assertEquals(tw.exilecore.currency.CurrencyOperation.INSTIL, currencies.get(id).operation(), id);
            assertEquals(0, currencies.get(id).dropWeight(), "怪物不掉：" + id);
        }
    }

    /** 2026-10-18：深淵裂縫的路線只走地板、一格一格相連、從事件的位置出發、長度不超過上限。 */
    @Test
    void riftPathsStayOnTheFloor() {
        final java.util.Random random = new java.util.Random(11);
        int planned = 0;
        for (long seed = 1; seed <= 12; seed++) {
            for (final MapLayout.Style style : MapLayout.Style.values()) {
                final MapLayout layout = MapLayout.generate(seed * 7919, 8, style);
                for (final MapLayout.Room room : layout.rooms()) {
                    if (room.kind() != MapLayout.Kind.NORMAL && room.kind() != MapLayout.Kind.SIDE) {
                        continue;
                    }
                    final MapLayout.Spot start = MapEvents.spotIn(layout, room);
                    if (start == null) {
                        continue;
                    }
                    final List<MapLayout.Spot> path = MapEvents.planRift(layout, start, 40, random);
                    assertFalse(path.isEmpty(), style + " #" + seed + "：要有路線");
                    assertEquals(start, path.getFirst(), "從事件的位置出發");
                    assertTrue(path.size() <= 40, "不超過長度上限");
                    for (int i = 0; i < path.size(); i++) {
                        final MapLayout.Spot spot = path.get(i);
                        assertTrue(layout.isFloor(spot.x(), spot.z()), style + " #" + seed + "：第 " + i + " 格不是地板");
                        // 2026-10-18：不裂進起點、Boss 房，也不裂到任何房間的中心（事件、寶箱放在那）
                        final MapLayout.Room at = layout.roomAt(spot.x(), spot.z());
                        assertTrue(at == null || at.kind() == MapLayout.Kind.NORMAL || at.kind() == MapLayout.Kind.SIDE,
                                style + " #" + seed + "：第 " + i + " 格裂進了" + (at == null ? "" : at.kind().name()));
                        assertTrue(at == null || at == room || spot.x() != at.cx() || spot.z() != at.cz(), style + " #" + seed + "：裂到了別的房間中心");
                        if (i > 0) {
                            final MapLayout.Spot previous = path.get(i - 1);
                            assertEquals(1, Math.abs(spot.x() - previous.x()) + Math.abs(spot.z() - previous.z()), "一格一格相連");
                        }
                    }
                    planned++;
                }
            }
        }
        assertTrue(planned > 50, "有測到東西：" + planned);
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
        // 2026-10-18：weights 裡寫 safe 不算（保險箱照 safe_chance）
        Files.writeString(temp.resolve("map_events.yml"), "settings:\n  weights: { shrine: 50, safe: 50 }\n");
        final MapEventConfig withSafe = new MapEventConfig();
        withSafe.load(temp);
        assertEquals(50, withSafe.totalWeight());
        assertFalse(withSafe.weights().containsKey(MapEventConfig.Kind.SAFE));
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
