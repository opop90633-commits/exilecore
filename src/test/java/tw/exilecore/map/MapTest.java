package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.GuideArrow;
import tw.exilecore.worldgen.Palette;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地圖（階段 4）：用真實資料檔檢查地圖設定、星圖、地圖詞綴、掉落、傳奇、版面。 */
class MapTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static MapConfig config;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        config = new MapConfig();
        config.load(DATA);
        assertEquals(List.of(), config.validate(repository));
    }

    @Test
    void mapsLoadWithRingsAndBosses() {
        assertEquals(14, config.maps().size(), "12 張＋菌光洞窟、赤沙古道");
        final Map<Integer, Integer> perRing = new HashMap<>();
        int named = 0;
        for (final MapConfig.MapDef map : config.maps()) {
            perRing.merge(map.ring(), 1, Integer::sum);
            assertEquals(0, map.level(), "地圖本身沒有等級：照地圖物品的階級");
            assertTrue(MapConfig.THEMES.contains(map.theme()));
            assertFalse(map.monsters().isEmpty());
            if (map.boss().named()) {
                named++;
            }
        }
        assertEquals(Map.of(1, 3, 2, 3, 3, 3, 4, 3, 5, 2), perRing, "新地圖在第 3、4 圈（最外圈還是兩張：解鎖終局的條件不變）");
        // 版面樣式：每一種都有地圖在用（終局照舊是廳堂）
        final Set<MapLayout.Style> styles = new HashSet<>();
        for (final MapConfig.MapDef map : config.maps()) {
            styles.add(map.layout());
        }
        assertEquals(Set.of(MapLayout.Style.values()), styles);
        assertEquals(MapLayout.Style.CAVERN, config.find("glow_grotto").layout());
        assertEquals("fungal", config.find("glow_grotto").theme());
        assertEquals(MapLayout.Style.OPEN, config.find("red_sands").layout());
        assertEquals("desert", config.find("red_sands").theme());
        assertEquals(MapLayout.Style.ISLANDS, config.find("wreck_bay").layout());
        assertEquals(MapLayout.Style.CITY, config.find("ash_street").layout());
        assertEquals("volcano", config.find("burning_field").theme());
        assertEquals("volcano", config.find("ember_citadel").theme());
        for (final MapConfig.MapDef map : config.finals()) {
            assertEquals(MapLayout.Style.HALLS, map.layout(), map.id());
        }
        assertEquals(2, named, "兩個具名地圖 Boss");
        assertEquals(5, config.maxRing());
        final MapConfig.MapDef end = config.finalMap();
        assertNotNull(end);
        assertTrue(end.isFinal());
        assertTrue(end.boss().named());
        assertEquals(2, end.boss().phases().size());
        assertTrue(end.boss().phases().get(0).at() > end.boss().phases().get(1).at(), "階段依血量門檻由高到低");
        assertEquals(1, config.byBase("map_wreck_bay").ring());
        assertNull(config.byBase("short_sword"));
    }

    @Test
    void sixteenTiersFromMonsterLevel61To84() {
        final MapStats.Tiers tiers = config.tiers();
        assertEquals(16, tiers.max());
        assertEquals(61, tiers.level(1));
        assertEquals(65, tiers.level(5), "T1–T5 跟以前一樣");
        assertEquals(84, tiers.level(16));
        assertEquals(84, tiers.topLevel());
        int previous = 0;
        for (int tier = 1; tier <= 16; tier++) {
            assertTrue(tiers.level(tier) > previous, "T" + tier + " 比前一階高");
            previous = tiers.level(tier);
        }
        assertEquals(1, tiers.clamp(0));
        assertEquals(16, tiers.clamp(99));
        assertEquals(0, tiers.quantity(1), 1e-9);
        // 2026-10-27 照 PoE2：換界石沒有隨階級的物品數量、稀有度（這次之前 T16 +30%／+45%）
        assertEquals(0, tiers.quantity(16), 1e-9, "階級不給物品數量");
        assertEquals(0, tiers.rarity(16), 1e-9, "階級不給稀有度");
        assertEquals(MapStats.DEFAULT_TIERS, tiers, "程式的預設值跟出貨的 maps.yml 一樣");
        assertEquals(3, config.finalLevelBonus());
        // 終局：T5 = 68（跟以前一樣），T16 = 87
        assertEquals(68, MapStats.forTier(tiers, 5, config.finalLevelBonus()).monsterLevel());
        assertEquals(87, MapStats.forTier(tiers, 16, config.finalLevelBonus()).monsterLevel());
        assertEquals(16, MapStats.forTier(tiers, 40, 0).tier(), "超過最高階的當作最高階");
    }

    @Test
    void oldConfigWithOnlyLevelBaseStillLoads(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("maps.yml"), """
                settings:
                  level_base: 50
                maps:
                  wreck_bay:
                    base: map_wreck_bay
                    theme: beach
                    atlas: [0, 2]
                    start: true
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 測試 }
                final:
                  id: sky_crucible
                  name: 天穹熔爐
                  level: 68
                  theme: star
                  atlas: [8, 2]
                  monsters: { ZOMBIE: 1 }
                  boss: { name: 終局 }
                """);
        final MapConfig old = new MapConfig();
        old.load(dir);
        assertEquals(List.of(), old.validate(repository));
        assertEquals(16, old.tiers().max());
        assertEquals(51, old.tiers().level(1), "舊的 level_base：T1 = level_base + 1");
        assertEquals(66, old.tiers().level(16));
        assertEquals(3, old.finalLevelBonus(), "舊的 final.level 不用了，終局照階級＋3");
        assertEquals(1.0, old.finalLifeMultiplier(5), 1e-9, "沒寫 final.tier_life：照預設（T5 以下 ×1）");
        assertEquals(MapConfig.DEFAULT_FINAL_TIER_LIFE_MAX, old.finalLifeMultiplier(16), 1e-9, "沒寫 final.tier_life：照預設（T16 ×2.5）");
        assertEquals(old.drops().rarity(), old.drops().rarityTop(), "沒寫 rarity_top：每一階都照 rarity");
        assertEquals(0.01, old.drops().finalUniqueChance(), 1e-9, "舊的設定檔沒寫 final_unique_chance：終局自己的傳奇每件 1%，不是必掉");
        config.load(DATA); // MapStats 的階級表換回出貨的
        config.validate(repository);
        assertEquals(61, MapStats.tiers().level(1));
    }

    @Test
    void linksAreSymmetricAndPositionsUnique() {
        final Set<String> positions = new HashSet<>();
        for (final MapConfig.MapDef map : config.all()) {
            if (!map.onAtlas()) {
                assertTrue(config.isEventPinnacle(map.id()), map.id() + " 不在星圖上，只有事件巔峰可以"); // 2026-10-18
                assertTrue(map.links().isEmpty(), map.id());
                continue;
            }
            assertTrue(positions.add(map.col() + "," + map.row()), map.id());
            for (final String link : map.links()) {
                assertTrue(config.find(link).links().contains(map.id()), map.id() + " → " + link);
            }
        }
        // 連線畫在兩個節點中間的格子，不會蓋到節點
        for (final Atlas.Connector connector : Atlas.connectors(config)) {
            assertFalse(positions.contains(connector.col() + "," + connector.row()), connector.toString());
        }
        assertEquals(21, Atlas.connectors(config).size(), "15 條＋兩張新地圖的 6 條");
    }

    @Test
    void atlasUnlocksNeighboursAndFinal() {
        final Set<String> done = new LinkedHashSet<>();
        assertTrue(Atlas.unlocked(config, config.find("wreck_bay"), done));
        assertTrue(Atlas.unlocked(config, config.find("ash_street"), done));
        assertFalse(Atlas.unlocked(config, config.find("mist_ferry"), done));
        done.add("wreck_bay");
        assertTrue(Atlas.unlocked(config, config.find("mist_ferry"), done));
        assertFalse(Atlas.unlocked(config, config.find("royal_tomb"), done));
        assertFalse(Atlas.finalUnlocked(config, done));
        done.add("starfall_tundra");
        assertFalse(Atlas.finalUnlocked(config, done));
        done.add("observatory");
        assertTrue(Atlas.finalUnlocked(config, done));
        assertTrue(Atlas.unlocked(config, config.finalMap(), done));
        // 完成的地圖越多，掉落 +1 階的機率越高
        assertTrue(Atlas.tierUpChance(config, 12) > Atlas.tierUpChance(config, 0));
        assertEquals(0.15, Atlas.tierUpChance(config, 0), 1e-9);
        // 掉落的地圖最高幾階：一開始 T4，每完成一張不同的地圖高一階，12 張 = T16
        assertEquals(4, Atlas.dropTierCap(config, 0));
        assertEquals(10, Atlas.dropTierCap(config, 6));
        assertEquals(16, Atlas.dropTierCap(config, 12));
        assertEquals(16, Atlas.dropTierCap(config, 99));
    }

    @Test
    void bestTierIsRecordedAndOldProgressCountsAsTheRing() {
        final Map<String, String> progress = new HashMap<>();
        assertEquals(0, Atlas.bestTier(config, progress, Set.of()), "什麼都沒完成");
        // 地圖分階以前完成的（沒有記最高階）：以前的階級就是圈數
        assertEquals(5, Atlas.bestTier(config, progress, Set.of("wreck_bay", "observatory")));
        assertEquals(1, Atlas.bestTier(config, progress, Set.of("wreck_bay")));
        // 有記錄就照記錄（第 5 圈的地圖開 T2 完成，不會變成 T5）
        progress.put(Atlas.BEST_KEY, "2");
        assertEquals(2, Atlas.bestTier(config, progress, Set.of("wreck_bay", "observatory")));
        progress.put(Atlas.BEST_KEY, "99");
        assertEquals(16, Atlas.bestTier(config, progress, Set.of()), "最多到最高階");
        progress.put(Atlas.BEST_KEY, "壞掉");
        assertEquals(1, Atlas.bestTier(config, progress, Set.of("wreck_bay")), "寫壞了當作沒有");
        // 最高階的鍵不能是 atlas. 開頭（任務、委託、試煉用 atlas. 開頭的鍵數完成了幾張地圖）
        assertFalse(Atlas.BEST_KEY.startsWith(Atlas.PROGRESS_PREFIX));
        assertTrue(Atlas.completed(config, Map.of(Atlas.BEST_KEY, "7")).isEmpty());
    }

    @Test
    void mapAffixesSplitByTargetAndQualityAddsQuantity() {
        final ItemData map = MapDrops.create(config, generator, config.find("royal_tomb"), Rarity.NORMAL, 3, new Random(3));
        assertNotNull(map);
        assertEquals(3, map.mapTier());
        assertEquals(63, map.ilvl(), "物品等級 = 那一階的怪物等級");
        map.setRarity(Rarity.RARE);
        map.affixes().add(new RolledAffix("map_monster_life", 1, Map.of("value", 40)));
        map.affixes().add(new RolledAffix("map_player_resist", 1, Map.of("value", -15)));
        map.affixes().add(new RolledAffix("map_boss_life", 1, Map.of("value", 50)));
        map.affixes().add(new RolledAffix("map_pack_size", 1, Map.of("value", 30)));
        map.setQuality(20);
        final MapStats stats = MapStats.of(repository, map, config.tiers());
        assertEquals(3, stats.tier());
        assertEquals(63, stats.monsterLevel());
        // 2026-10-26 照 PoE2：換界石詞綴不給物品數量，給的是「換界石掉落更多」（怪物生命 15%）；停用的舊詞綴照舊給物品數量
        // 2026-10-27：階級不再給數量、稀有度（這次之前 T3 +4%／+6%）
        assertEquals(20 + 8 + 6 + 5, stats.quantity(), 1e-9, "品質 20 + 停用的舊詞綴的物品數量");
        assertEquals(15, stats.extra("map.waystones"), 1e-9, "怪物生命：換界石掉落更多 15%");
        assertEquals(23, stats.extra("map.rare_mod_chance"), 1e-9, "怪物生命：稀有怪帶詞綴的機率更多 23%");
        assertEquals(0 + 5 + 6, stats.rarity(), 1e-9, "詞綴（怪物生命照 PoE2 帶的是魔法與稀有怪物群，不是稀有度）");
        assertEquals(30, stats.packSize(), 1e-9);
        assertEquals(List.of("life.max"), stats.monsterModifiers().stream().map(Modifier::stat).toList());
        assertEquals(0.4, stats.monsterModifiers().getFirst().value(), 1e-9);
        assertEquals(List.of("life.max"), stats.bossModifiers().stream().map(Modifier::stat).toList());
        assertEquals(3, stats.playerModifiers().size());
        for (final Modifier modifier : stats.playerModifiers()) {
            assertTrue(modifier.stat().startsWith("resist."));
            assertEquals(-15, modifier.value(), 1e-9);
        }
        // 地圖的修飾器不會跑到裝備的屬性裡（地圖不是裝備，但就算拿在手上也只有這些前綴的屬性）
        for (final Modifier modifier : ItemStats.modifiers(repository, map)) {
            assertTrue(modifier.stat().startsWith("map.") || modifier.stat().contains(":"), modifier.stat());
        }
    }

    /** 2026-10-26（PoE2 換界石）：每一段只出那一段的數值；週期詛咒、地面效果的鍵存在 MapStats 的 extra。 */
    @Test
    void waystoneAffixesFollowPoe2Bands() {
        final tw.exilecore.item.Affix fire = repository.affix("map_monster_fire");
        assertEquals(List.of(0), fire.tiersFor(84).stream().map(tw.exilecore.item.AffixTier::tier).toList(), "T16 一定是最高階");
        assertEquals(List.of(1), fire.tiersFor(80).stream().map(tw.exilecore.item.AffixTier::tier).toList(), "T11–15");
        assertEquals(List.of(2), fire.tiersFor(70).stream().map(tw.exilecore.item.AffixTier::tier).toList(), "T6–10");
        assertEquals(List.of(3), fire.tiersFor(61).stream().map(tw.exilecore.item.AffixTier::tier).toList(), "T1–5");
        assertTrue(repository.affix("map_monster_cold").group().equals("map_monster_cold"), "火、冰、電、混沌各自一組");
        final ItemData map = MapDrops.create(config, generator, config.find("royal_tomb"), Rarity.NORMAL, 16, new Random(3));
        map.setRarity(Rarity.RARE);
        map.affixes().add(new RolledAffix("map_player_curse_enfeeble", 0, Map.of("value", 1)));
        map.affixes().add(new RolledAffix("map_ground_ignited", 0, Map.of("value", 7, "fire", 6000)));
        map.affixes().add(new RolledAffix("map_monster_fire", 0, Map.of("value", 22)));
        map.affixes().add(new RolledAffix("map_monster_damage", 0, Map.of("value", 22)));
        final MapStats stats = MapStats.of(repository, map, config.tiers());
        assertEquals(1, stats.extra("map.curse_zone.enfeeble"), 1e-9);
        assertEquals(6000, stats.extra("map.ground.fire"), 1e-9);
        assertEquals(7, stats.extra("map.ground.patches"), 1e-9);
        assertEquals(List.of("enfeeble"), MapHazards.curses(stats));
        // 換界石掉落更多：四條相乘（萎力 20%、地面 15%、火焰 15%、傷害 20%）
        assertEquals((1.2 * 1.15 * 1.15 * 1.2 - 1) * 100, stats.extra("map.waystones"), 1e-6);
        assertEquals(-0.29, MapHazards.debuff("enfeeble", 100).mods().getFirst().value(), 1e-9);
        // 2026-10-28 照 PoE2 Ignited Ground：6000 ÷ 10 ＝ 視同 600 點火焰傷害（68 級），照怪物傷害表換等級
        assertEquals(600, MapHazards.groundFire(6000, new double[] {MapHazards.FIRE_REFERENCE_DAMAGE, MapHazards.FIRE_REFERENCE_DAMAGE}), 1e-9);
        assertEquals(1200, MapHazards.groundFire(6000, new double[] {MapHazards.FIRE_REFERENCE_DAMAGE * 2, MapHazards.FIRE_REFERENCE_DAMAGE * 2}), 1e-9);
    }

    @Test
    void currenciesWorkOnMapsAndChiselAddsQuality() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        final Random random = new Random(7);
        final ItemData map = MapDrops.create(config, generator, config.find("wreck_bay"), Rarity.NORMAL, 1, random);
        assertTrue(currencies.get("chisel").canApply(map, generator));
        CurrencyOperation.QUALITY_MAP.apply(map, generator, random);
        assertEquals(5, map.quality());
        assertFalse(currencies.get("chisel").canApply(generator.generate(repository.base("short_sword"), Rarity.NORMAL, 1, random), generator));
        assertFalse(currencies.get("whetstone").canApply(map, generator), "礪鋒石不能用在地圖上");
        // 點金（星火石）→ 稀有地圖，詞綴都是地圖詞綴
        CurrencyOperation.UPGRADE_TO_RARE.apply(map, generator, random);
        assertEquals(Rarity.RARE, map.rarity());
        assertTrue(map.affixes().size() >= 4);
        for (final RolledAffix affix : map.affixes()) {
            assertTrue(affix.id().startsWith("map_"), affix.id());
        }
        assertNotNull(map.name());
        assertFalse(currencies.get("sky_fragment").canApply(map, generator), "天穹碎片不能用在物品上");
        // 地圖不會跟一般裝備一起掉
        for (final var base : repository.basesDroppableAt(100)) {
            assertFalse(base.isMap(), base.id());
        }
    }

    @Test
    void dropsOnlyUnlockedMaps() {
        final Random random = new Random(11);
        final Set<String> nothing = Set.of();
        for (int i = 0; i < 200; i++) {
            final ItemData map = MapDrops.roll(config, generator, 1, nothing, random);
            assertNotNull(map);
            final MapConfig.MapDef def = config.byBase(map.base());
            assertTrue(def.start(), "沒完成任何地圖時只會掉第 1 圈的地圖：" + def.id());
            assertTrue(map.identified());
            assertTrue(map.mapTier() == 1 || map.mapTier() == 2, "T1 的怪掉 T1，有機率 T2：" + map.mapTier());
            assertEquals(config.tiers().level(map.mapTier()), map.ilvl());
        }
        // 完成沉船灣：可能掉霧沼渡口（相連的），還沒完成的比較常掉
        final Set<String> some = Set.of("wreck_bay");
        final Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            seen.merge(config.byBase(MapDrops.roll(config, generator, 1, some, random).base()).id(), 1, Integer::sum);
        }
        assertTrue(seen.containsKey("mist_ferry"), seen.toString());
        assertFalse(seen.containsKey("royal_tomb"), seen.toString());
        assertTrue(seen.get("mist_ferry") > seen.get("wreck_bay") * 1.4, "前線的地圖（還沒完成）兩倍機率：" + seen);
    }

    @Test
    void dropTiersFollowTheMapAndAreCappedByTheAtlas() {
        final Random random = new Random(5);
        final Set<String> two = Set.of("wreck_bay", "ash_street");
        int up = 0;
        for (int i = 0; i < 1000; i++) {
            final ItemData map = MapDrops.roll(config, generator, 5, two, random);
            assertTrue(map.mapTier() == 5 || map.mapTier() == 6, "T5 的怪掉 T5，有機率 T6（上限 4 + 2 = T6）：" + map.mapTier());
            if (map.mapTier() == 6) {
                up++;
            }
        }
        assertTrue(up > 100 && up < 250, "高一階的機率 15% + 每完成一張 1% = 17%：" + up);
        // 完成兩張時上限 T6：開 T12 的地圖（別人交易來的）也只掉到 T6
        for (int i = 0; i < 200; i++) {
            assertEquals(6, MapDrops.roll(config, generator, 12, two, random).mapTier());
        }
        // 不看星圖（沒有人有份）：沒有上限
        assertEquals(16, MapDrops.roll(config, generator, 16, null, random).mapTier());
        // 高階多一點魔法、稀有地圖，但大部分還是要自己洗（2026-10：T16 從 15／35／50 改成 40／45／15）
        final Map<Rarity, Double> low = MapDrops.rarityWeights(config, 1);
        final Map<Rarity, Double> high = MapDrops.rarityWeights(config, 16);
        assertEquals(55, low.get(Rarity.NORMAL), 1e-9);
        assertEquals(40, high.get(Rarity.NORMAL), 1e-9);
        assertEquals(45, high.get(Rarity.MAGIC), 1e-9);
        assertEquals(15, high.get(Rarity.RARE), 1e-9);
        assertTrue(MapDrops.rarityWeights(config, 8).get(Rarity.RARE) > low.get(Rarity.RARE));
        int rares = 0;
        int lowRares = 0;
        for (int i = 0; i < 4000; i++) {
            rares += MapDrops.rollRarity(config, 16, random) == Rarity.RARE ? 1 : 0;
            lowRares += MapDrops.rollRarity(config, 1, random) == Rarity.RARE ? 1 : 0;
        }
        assertTrue(rares > lowRares, "T16 稀有 " + rares + "、T1 稀有 " + lowRares);
        assertTrue(rares < 4000 * 0.2, "T16 掉的地圖大部分不是稀有（要自己洗）：" + rares);
    }

    @Test
    void tierIsStoredOnTheItemAndOldMapsUseTheirRing() {
        final ItemData map = MapDrops.create(config, generator, config.find("wreck_bay"), Rarity.NORMAL, 7, new Random(2));
        assertEquals(7, map.mapTier());
        assertEquals("沉船灣 T7", ItemStats.displayName(repository, map));
        assertEquals(68, MapStats.of(repository, map, config.tiers()).monsterLevel());
        // 物品的 JSON 帶著階級；複製也帶著
        assertEquals(7, map.copy().mapTier());
        // 以前的地圖物品沒有寫階級：照基底的圈數（王陵地宮第 3 圈 = T3）
        final ItemData old = MapDrops.create(config, generator, config.find("royal_tomb"), Rarity.NORMAL, 1, new Random(2));
        old.setMapTier(null);
        assertEquals(3, ItemStats.mapTier(repository.base(old.base()), old));
        assertEquals(3, MapStats.of(repository, old, config.tiers()).tier());
        assertEquals("王陵地宮 T3", ItemStats.displayName(repository, old));
        // 高階的地圖詞綴（代號 0）要物品等級 84（T16；2026-10 照 PoE2 的四個階段 T1–5／6–10／11–15／16）
        final var life = repository.affix("map_monster_life");
        assertEquals(0, life.tiers().getFirst().tier());
        assertEquals(84, life.tiers().getFirst().ilvl());
        assertEquals("不滅的", life.nameFor(0));
        assertEquals("強韌的", life.nameFor(1), "舊的地圖詞綴名字不變");
        assertEquals(2, life.rank(1));
        // 不是地圖的物品沒有階級
        assertEquals(0, ItemStats.mapTier(repository.base("short_sword"), generator.generate(repository.base("short_sword"), Rarity.NORMAL, 1, new Random(1))));
    }

    @Test
    void uniquesLoadAndRoll() {
        assertEquals(127 + 21 + 5 + 2 + 6 + 30 + 2 + 91 + 1 + 9 + 21 - 3 + 1 + 1, repository.uniques().size(), "2026-10-29：＋鮮血屏障（Bloodbarrier）；2026-10-29：＋百目冠（Crown of Eyes）；2026-10-28 照站長刪掉焰扣束帶、奔雷皮靴、禁視鏈冠（－3）；2026-10-28：＋9 件照 PoE2（綺夢、阿茲里的輕視、謎容、神聖烈焰、元素之章、貝雷克的兩枚、薛朗的背包、吉爾菲的聖殿）；2026-10-28：＋黯炎；2026-10-27：＋91 件照 PoE2 的第二批傳奇（7 件追逐級）；2026-10-26：＋30 件照 PoE2 的新傳奇、永恆珠寶 2 件（英雄悲劇、不朽恨意）；2026-10-24：＋6 件戰星傳奇；2026-10-18：＋七隻事件巔峰 Boss 各 3 件、深淵裂縫事件 5 件；2026-10 照 PoE2 重做、加了很多傳奇（退役的不算；含巔峰 Boss 的 10 件、德魯伊的獸魂符 4 件；2026-10-15 追逐級多 8 件、共通多 6 件；星骸之海的淵鯨 3 件；2026-10-17 只能賭的踏骸之履；共通的荊棘盾棘壘）");
        final ItemData dropped = MapDrops.unique(generator, "final", 68, new Random(5));
        assertNotNull(dropped);
        assertEquals(Rarity.UNIQUE, dropped.rarity());
        assertTrue(java.util.Set.of("celestial_pivot", "meteor_anvil", "spider_bulwark", "hateforge_greaves", "tailwind_quiver", "sacred_flame",
                "atziris_contempt").contains(dropped.unique()), "終局掉的是終局的傳奇（2026-10：天穹之心、星鑄退役，換成新的兩件；2026-10-28 照 PoE2：＋原作 Boss 限定的四件）");
        assertNull(repository.unique("sky_heart"), "退役的傳奇定義刪掉了（2026-10-15）");
        final ItemData pivot = generator.generateUnique(repository.unique("celestial_pivot"), 68, new Random(5));
        assertEquals("celestial_pivot", pivot.unique());
        assertFalse(pivot.affixes().isEmpty());
        assertEquals(repository.unique("celestial_pivot").name(), ItemStats.displayName(repository, pivot));
        // 傳奇詞綴不會被隨機抽到
        final ItemData amulet = generator.generate(repository.base("jade_amulet"), Rarity.RARE, 80, new Random(9));
        for (final RolledAffix affix : amulet.affixes()) {
            assertFalse(affix.id().startsWith(UniqueItem.AFFIX_PREFIX));
        }
        assertNotNull(MapDrops.unique(generator, "royal_tomb", 63, new Random(1)));
        assertNotNull(MapDrops.unique(generator, "map_boss", 61, new Random(1)));
        assertNull(MapDrops.unique(generator, "nobody", 61, new Random(1)));
        // 其他終局 Boss 各自有自己的那件（掉不掉看 drops.final_unique_chance，不是必掉）
        // 2026-10-15：四個終局都多一件蛛巢壁壘（跟自己的那件一起抽）
        for (final String[] own : new String[][] {{"frost_throne", "rime_halo"}, {"bone_throne", "bone_cinch"}, {"ember_citadel", "molten_aegis"}}) {
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (int seed = 0; seed < 40; seed++) {
                seen.add(MapDrops.unique(generator, own[0], 68, new Random(seed)).unique());
            }
            assertEquals(java.util.Set.of(own[1], "spider_bulwark"), seen, own[0]);
        }
    }

    @Test
    void extraFinalsLoadAndUnlockAfterTheEmperor() {
        // 2026-10-18：事件巔峰（用入場券開的）也是 finals 的一種，數量照設定檔；星圖上的終局還是 5 個
        final int eventPinnacles = config.eventPinnacles().size();
        assertEquals(5 + eventPinnacles, config.finals().size(), "天穹熔爐 + 三個新終局 + 巔峰 + 事件巔峰");
        assertEquals(4, config.regularFinals().size(), "用天穹碎片開的終局");
        assertEquals(List.of("awakener"), config.fragmentPinnacles().stream().map(MapConfig.MapDef::id).toList(), "巔峰：萬界之巔");
        assertEquals("awakener", config.pinnacles().getFirst().id());
        assertEquals(config.finalMap(), config.finals().getFirst());
        assertEquals(14 + 5 + eventPinnacles, config.all().size());
        final Set<String> ids = new HashSet<>();
        for (final MapConfig.MapDef map : config.finals()) {
            assertTrue(map.isFinal());
            assertTrue(map.boss().named());
            assertEquals(config.pinnacle(map.id()) == null ? 2 : 3, map.boss().phases().size(), map.id());
            assertTrue(ids.add(map.id()));
            assertEquals(3, config.finalLevelBonus(map.id()));
            assertEquals(map, config.find(map.id()));
            assertEquals(config.isEventPinnacle(map.id()), !map.onAtlas(), map.id() + "：只有事件巔峰不在星圖上");
        }
        assertEquals(2, config.finalPoints("sky_crucible"));
        assertEquals(1, config.finalPoints("frost_throne"), "新終局首殺 1 點");
        assertEquals(List.of("sky_crucible"), config.finalInfo("bone_throne").requires());
        assertNull(config.finalInfo("wreck_bay"));
        // 解鎖：最外圈完成 → 只有天穹熔爐；打贏曜帝 → 其他三個
        final Set<String> done = new LinkedHashSet<>(List.of("starfall_tundra", "observatory"));
        assertTrue(Atlas.unlocked(config, config.find("sky_crucible"), done));
        assertFalse(Atlas.unlocked(config, config.find("frost_throne"), done));
        assertEquals("天穹熔爐", Atlas.missingRequirements(config, config.find("frost_throne"), done));
        done.add("sky_crucible");
        assertTrue(Atlas.unlocked(config, config.find("frost_throne"), done));
        assertTrue(Atlas.unlocked(config, config.find("ember_citadel"), done));
        assertEquals("", Atlas.missingRequirements(config, config.find("ember_citadel"), done));
        // 巔峰：四個終局都打贏才解鎖；碎片各由一個終局掉
        assertFalse(Atlas.unlocked(config, config.find("awakener"), done));
        done.addAll(List.of("frost_throne", "bone_throne", "ember_citadel"));
        assertTrue(Atlas.unlocked(config, config.find("awakener"), done));
        final MapConfig.Pinnacle pinnacle = config.pinnacle("awakener");
        assertEquals(4, pinnacle.fragments().size());
        assertEquals(Set.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel"),
                pinnacle.fragments().stream().map(MapConfig.FragmentSource::from).collect(java.util.stream.Collectors.toSet()));
        for (final MapConfig.FragmentSource fragment : pinnacle.fragments()) {
            assertEquals(0.25, fragment.chance(), 1e-9, fragment.currency() + "：每個終局 Boss 25%（2026-10：10% → 50% → 25%）");
            assertEquals(0.5, fragment.uberChance(), 1e-9, fragment.currency() + "：極境版本 50%（2026-10）");
            assertTrue(fragment.minTier() <= config.tiers().max());
        }
        assertEquals(config.tiers().max(), config.pinnacleStats("awakener", pinnacle).tier(), "巔峰開在最高階");
        assertTrue(config.pinnacleStats("awakener", pinnacle).monsterLevel() > config.uberStats("sky_crucible", config.uber("sky_crucible")).monsterLevel(),
                "巔峰的怪物等級比極境高");
        assertNull(config.uber("awakener"), "巔峰沒有極境版本");
        assertEquals(2, pinnacle.attempts(), "巔峰兩道門（兩次挑戰）");
        // 星圖位置不重複、招式都存在
        final Set<String> positions = new HashSet<>();
        for (final MapConfig.MapDef map : config.all()) {
            if (!map.onAtlas()) {
                continue; // 2026-10-18：事件巔峰不在星圖上
            }
            assertTrue(positions.add(map.col() + "," + map.row()), map.id() + " 的星圖位置重複");
        }
    }

    @Test
    void layoutsAreConnectedAndDeterministic() {
        for (long seed = 1; seed <= 40; seed++) {
            final MapLayout layout = MapLayout.generate(seed * 7919, 6 + (int) (seed % 6));
            final MapLayout again = MapLayout.generate(seed * 7919, 6 + (int) (seed % 6));
            assertEquals(layout.rooms(), again.rooms());
            assertEquals(layout.packSpots(), again.packSpots());
            assertTrue(layout.roomCount() >= 5, "seed " + seed + " 只有 " + layout.roomCount() + " 間");
            assertFalse(layout.packSpots().isEmpty());
            // 從入口走得到 Boss 房、每一群怪、回程門
            final boolean[][] seen = new boolean[MapLayout.SIZE][MapLayout.SIZE];
            final ArrayDeque<int[]> queue = new ArrayDeque<>();
            final MapLayout.Spot entrance = layout.entrance();
            assertTrue(layout.isFloor(entrance.x(), entrance.z()));
            queue.add(new int[] {entrance.x(), entrance.z()});
            seen[entrance.x()][entrance.z()] = true;
            while (!queue.isEmpty()) {
                final int[] p = queue.poll();
                for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    final int x = p[0] + d[0];
                    final int z = p[1] + d[1];
                    if (layout.isFloor(x, z) && !seen[x][z]) {
                        seen[x][z] = true;
                        queue.add(new int[] {x, z});
                    }
                }
            }
            assertTrue(seen[layout.boss().cx()][layout.boss().cz()], "seed " + seed + "：走不到 Boss 房");
            for (final MapLayout.Spot spot : layout.packSpots()) {
                assertTrue(seen[spot.x()][spot.z()], "seed " + seed + "：走不到怪物群 " + spot);
            }
            assertTrue(seen[layout.exitPortal().x()][layout.exitPortal().z()]);
            // 地板外面一定是牆（不會走到格子外面）
            for (int x = 0; x < MapLayout.SIZE; x++) {
                for (int z = 0; z < MapLayout.SIZE; z++) {
                    if (layout.isFloor(x, z)) {
                        assertTrue(x > 0 && z > 0 && x < MapLayout.SIZE - 1 && z < MapLayout.SIZE - 1, "seed " + seed + " 地板貼到格子邊");
                    }
                }
            }
        }
    }

    @Test
    void builderPlacesFloorWallsAndPortal() {
        final MapLayout layout = MapLayout.generate(42, 8);
        for (final String theme : MapConfig.THEMES) {
            final BufferSink sink = new BufferSink();
            MapBuilder.build(layout, theme, 1024, 2048, sink);
            assertTrue(sink.size() > 10_000, theme);
            final MapLayout.Spot entrance = layout.entrance();
            final int floor = sink.blockAt(1024 + entrance.x(), MapLayout.FLOOR_Y, 2048 + entrance.z());
            assertTrue(floor > 0, theme + "：入口沒有地板");
            assertTrue(sink.blockAt(1024 + entrance.x(), MapLayout.FLOOR_Y + 1, 2048 + entrance.z()) <= 0, theme + "：入口被擋住");
            final MapLayout.Spot exit = layout.exitPortal();
            assertEquals("crying_obsidian", Palette.blockName(sink.blockAt(1024 + exit.x(), MapLayout.FLOOR_Y, 2048 + exit.z())));
            for (int i = 0; i < sink.size(); i++) {
                assertTrue(sink.x(i) >= 1024 && sink.z(i) >= 2048 && sink.x(i) < 1024 + MapLayout.SIZE && sink.z(i) < 2048 + MapLayout.SIZE);
                assertTrue(sink.y(i) >= MapLayout.FLOOR_Y - 4 && sink.y(i) <= MapLayout.FLOOR_Y + 18, theme + " 蓋到清理範圍外 y=" + sink.y(i));
            }
        }
    }

    @Test
    void builderSealsTheMapWithBarriers() {
        final MapLayout layout = MapLayout.generate(42, 8);
        final int ceiling = MapLayout.FLOOR_Y + MapBuilder.CEILING;
        assertTrue(ceiling <= MapService.CLEAR_MAX_Y, "天花板要在清理範圍內");
        final int barrier = Palette.id("barrier");
        for (final String theme : MapConfig.THEMES) {
            final BufferSink sink = new BufferSink();
            MapBuilder.build(layout, theme, 1024, 2048, sink);
            // 同一格放兩次以最後一次為準（blockAt 一格一格找太慢，先整理成表）
            final java.util.Map<Long, Integer> blocks = new java.util.HashMap<>();
            for (int i = 0; i < sink.size(); i++) {
                assertTrue(sink.y(i) <= ceiling, theme + "：蓋到天花板上面 y=" + sink.y(i));
                blocks.put(cell(sink.x(i), sink.y(i), sink.z(i)), sink.block(i));
            }
            int floors = 0;
            int walls = 0;
            for (int x = layout.minX(); x <= layout.maxX(); x++) {
                for (int z = layout.minZ(); z <= layout.maxZ(); z++) {
                    if (layout.isFloor(x, z)) {
                        floors++;
                        assertEquals(barrier, blocks.getOrDefault(cell(1024 + x, ceiling, 2048 + z), Palette.AIR),
                                theme + "：地板上面沒有天花板 " + x + "," + z);
                        for (int y = MapLayout.FLOOR_Y + 1; y < ceiling; y++) {
                            assertTrue(blocks.getOrDefault(cell(1024 + x, y, 2048 + z), Palette.AIR) != barrier, theme + "：走道裡有屏障 y=" + y);
                        }
                    } else if (layout.isWall(x, z)) {
                        walls++;
                        // 牆從地板到天花板整根都是實心的（牆、樹葉或屏障）：怪物爬不上去、站不到牆頂
                        for (int y = MapLayout.FLOOR_Y; y <= ceiling; y++) {
                            assertTrue(blocks.getOrDefault(cell(1024 + x, y, 2048 + z), Palette.AIR) != Palette.AIR,
                                    theme + "：牆有缺口 " + x + "," + y + "," + z);
                        }
                    }
                }
            }
            assertTrue(floors > 1000 && walls > 1000, theme + " floors=" + floors + " walls=" + walls);
        }
    }

    private static long cell(final int x, final int y, final int z) {
        return ((long) x * 4096L + y) * 65536L + z;
    }

    @Test
    void guidesMarkEveryLegOfTheMainPathAndNothingElse() {
        int links = 0;
        for (long seed = 1; seed <= 120; seed++) {
            final int rooms = 6 + (int) (seed % 6);
            final MapLayout layout = MapLayout.generate(seed * 104_729, rooms);
            assertEquals(layout.guides(), MapLayout.generate(seed * 104_729, rooms).guides(), "seed " + seed + "：同一個種子路標一樣");
            assertFalse(layout.guides().isEmpty(), "seed " + seed + "：沒有路標");
            final Set<Long> cells = new HashSet<>();
            for (final MapLayout.Guide guide : layout.guides()) {
                for (final int[] cell : GuideArrow.cells(guide.x(), guide.z(), guide.dx(), guide.dz())) {
                    assertTrue(layout.isFloor(cell[0], cell[1]), "seed " + seed + "：箭頭蓋到牆 " + guide);
                    assertTrue(cells.add(((long) cell[0] << 32) | (cell[1] & 0xffffffffL)), "seed " + seed + "：箭頭重疊 " + guide);
                }
                // 每個箭頭都在主路的某一段上、指著那一段往 Boss 的方向（岔路、死路沒有）
                boolean onMainPath = false;
                for (final MapLayout.Link link : layout.links()) {
                    onMainPath |= onLeg(guide, link.from().cx(), link.from().cz(), link.cornerX(), link.cornerZ())
                            || onLeg(guide, link.cornerX(), link.cornerZ(), link.to().cx(), link.to().cz());
                }
                assertTrue(onMainPath, "seed " + seed + "：箭頭不在主路上 " + guide);
            }
            // 主路的每一段走道（兩個房間之間）至少一個箭頭
            for (final MapLayout.Link link : layout.links()) {
                links++;
                boolean guided = false;
                for (final MapLayout.Guide guide : layout.guides()) {
                    guided |= onLeg(guide, link.from().cx(), link.from().cz(), link.cornerX(), link.cornerZ())
                            || onLeg(guide, link.cornerX(), link.cornerZ(), link.to().cx(), link.to().cz());
                }
                assertTrue(guided, "seed " + seed + "：" + link.from().kind() + " → " + link.to().kind() + " 這段沒有箭頭");
            }
            assertEquals(MapLayout.Kind.BOSS, layout.links().getLast().to().kind(), "主路的最後一段走到 Boss 房");
        }
        assertTrue(links > 500, "測到的走道太少：" + links);
    }

    /** 箭頭的中心在 (x1,z1) → (x2,z2) 這一段直線上，而且指著同一個方向。 */
    private static boolean onLeg(final MapLayout.Guide guide, final int x1, final int z1, final int x2, final int z2) {
        final int dx = Integer.signum(x2 - x1);
        final int dz = Integer.signum(z2 - z1);
        if ((dx == 0 && dz == 0) || guide.dx() != dx || guide.dz() != dz) {
            return false;
        }
        return guide.x() >= Math.min(x1, x2) && guide.x() <= Math.max(x1, x2) && guide.z() >= Math.min(z1, z2) && guide.z() <= Math.max(z1, z2);
    }

    @Test
    void builderLaysGlowingArrowsOnTheFloor() {
        final MapLayout layout = MapLayout.generate(42, 8);
        assertFalse(layout.guides().isEmpty());
        for (final String theme : MapConfig.THEMES) {
            final BufferSink sink = new BufferSink();
            MapBuilder.build(layout, theme, 1024, 2048, sink);
            for (final MapLayout.Guide guide : layout.guides()) {
                for (final int[] cell : GuideArrow.cells(guide.x(), guide.z(), guide.dx(), guide.dz())) {
                    assertEquals("ochre_froglight", Palette.blockName(sink.blockAt(1024 + cell[0], MapLayout.FLOOR_Y, 2048 + cell[1])),
                            theme + "：路標不是發光方塊 " + guide);
                    if (layout.roomAt(cell[0], cell[1]) == null) {
                        assertTrue(sink.blockAt(1024 + cell[0], MapLayout.FLOOR_Y + 1, 2048 + cell[1]) <= 0, theme + "：路標上面被擋住 " + guide);
                    }
                }
            }
        }
    }

    @Test
    void finalBossUniqueIsAFivePercentDropNotGuaranteed() {
        assertEquals(0.01, config.drops().finalUniqueChance(), 1e-9, "終局 Boss 自己的傳奇每一件各自 1%（2026-10-16）");
        assertEquals(0.00034, config.drops().chaseChance(), 1e-12, "追逐級傳奇：所有地圖 Boss 固定 0.034%");
        assertEquals(MapConfig.DEFAULT_CHASE_CHANCE, config.drops().chaseChance(), 1e-12);
        assertEquals(0.05, config.drops().bossUniqueChance(), 1e-9, "地圖 Boss 的共通傳奇擲一次 5%（跟賭博一起校準）");
        assertEquals(0.01, config.drops().namedUniqueChance(), 1e-9, "具名 Boss 自己那件 1%");
        assertEquals(MapConfig.DEFAULT_BOSS_UNIQUE_CHANCE, config.drops().bossUniqueChance(), 1e-12);
        assertEquals(MapConfig.DEFAULT_NAMED_UNIQUE_CHANCE, config.drops().namedUniqueChance(), 1e-12);
        assertEquals(0.01, MapConfig.DEFAULT_UNIQUE_CHANCE, 1e-12, "地圖事件 1%（2026-10 從 2% 調低）");
        assertEquals(0.01, config.drops().finalCommonChance(), 1e-9, "終局另外算：共通傳奇擲一次 1%（2026-10-16）");
        assertEquals(0.01, config.drops().finalSupportChance(), 1e-9, "終局專屬輔助寶石 1%（2026-10 從 2% 調低）");
        // 設定檔的字面值寫對了（0 = 永遠不掉、1 = 必掉都行，夾在 0–1）
        assertTrue(config.drops().finalUniqueChance() > 0 && config.drops().finalUniqueChance() < 1);
    }

    @Test
    void campaignBossMapsCountPerDay() {
        assertEquals(3, config.drops().campaignBossDailyMaps());
        assertEquals(0, MapDrops.dailyCount(null, "2026-10-03"), "沒拿過");
        assertEquals(2, MapDrops.dailyCount("2026-10-03|2", "2026-10-03"));
        assertEquals(0, MapDrops.dailyCount("2026-10-02|3", "2026-10-03"), "昨天的不算");
        assertEquals(4, MapDrops.dailyCount("2026-10-03|4", "2026-10-03"), "用完以後記成 cap + 1（只提示一次）");
        assertEquals(0, MapDrops.dailyCount("2026-10-03|abc", "2026-10-03"), "寫壞了當作沒拿過");
        assertEquals(0, MapDrops.dailyCount("garbage", "2026-10-03"));
        assertEquals(0, MapDrops.dailyCount("|3", "2026-10-03"));
        assertEquals(0, MapDrops.dailyCount("2026-10-03|-5", "2026-10-03"));
    }

    @Test
    void auraAndSummonModsNeedLevel() throws IOException {
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        final MonsterConfig.MonsterMod summoner = monsters.mod("summoner");
        assertNotNull(summoner.summon());
        assertEquals(8, summoner.summon().intervalSeconds());
        assertTrue(summoner.minLevel() > 1);
        final MonsterConfig.MonsterMod warlord = monsters.mod("warlord_aura");
        assertNotNull(warlord.aura());
        assertEquals(10, warlord.aura().radius(), 1e-9);
        assertFalse(warlord.aura().stats().isEmpty());
        assertEquals(1, monsters.mod("swift").minLevel());
        assertNull(monsters.mod("swift").aura());
    }
    @Test
    void bossMapsChallengeThroughTwoDoors() {
        // 2026-10：終局、極境、巔峰用「門」算——一門一次挑戰；這一門結束（全部倒下或出來）才扣，下一門 Boss 回滿重來
        final MapConfig.MapDef awakener = config.find("awakener");
        final MapInstance instance = new MapInstance(1, awakener, null, MapLayout.generate(1, 6), null, 0, 0, 0,
                java.util.UUID.randomUUID(), "x", config.pinnacle("awakener").attempts(), 0);
        instance.setPinnacle(config.pinnacle("awakener"));
        assertTrue(instance.usesAttempts());
        assertFalse(instance.singleEntry(), "巔峰不再是每個人一次，改成門");
        final java.util.UUID first = java.util.UUID.randomUUID();
        final java.util.UUID late = java.util.UUID.randomUUID();
        assertFalse(instance.attemptActive());
        assertFalse(instance.startAttempt(), "第一門不用重置 Boss");
        instance.markAttemptEntry(first);
        assertTrue(instance.attemptActive());
        assertTrue(instance.enteredThisAttempt(first), "死了或出去的要等下一門");
        assertFalse(instance.enteredThisAttempt(late), "還沒進來的隊友可以跟進這一門");
        assertEquals(2, instance.portalsLeft(), "這一門結束才扣");
        instance.endAttempt();
        assertEquals(1, instance.portalsLeft());
        assertFalse(instance.enteredThisAttempt(first), "下一門大家都能進");
        assertTrue(instance.startAttempt(), "第二門：Boss 回滿重來");
        assertEquals(2, instance.attemptsUsed());
        instance.endAttempt();
        assertEquals(0, instance.portalsLeft(), "兩門用完");
        // 終局（一般版、極境）也一樣；一般地圖照舊
        final MapConfig.MapDef frost = config.find("frost_throne");
        assertTrue(new MapInstance(2, frost, null, MapLayout.generate(2, 6), null, 1, 0, 0, java.util.UUID.randomUUID(), "x", 2, 0).usesAttempts());
        final MapConfig.MapDef normal = config.find("wreck_bay");
        assertFalse(new MapInstance(3, normal, null, MapLayout.generate(3, 6), null, 2, 0, 0, java.util.UUID.randomUUID(), "x", 6, 0).usesAttempts());
    }
    @Test
    void bossRoomsStayWide() {
        // 2026-10-16：王房旁邊有別的房間時會縮小（最小 13 格），以前一成多的地圖王房很窄；現在主路挑王房旁邊空的、岔路不開在王房旁邊
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            int rooms = 0;
            for (long seed = 0; seed < 600; seed++) {
                final MapLayout layout = MapLayout.generate(seed, 8, style);
                assertTrue(layout.boss().halfX() >= 19, style + " #" + seed + "：王房半徑 " + layout.boss().halfX());
                rooms += layout.roomCount();
            }
            assertTrue(rooms / 600.0 >= 9, style + "：房間數沒有變少（平均 " + rooms / 600.0 + "）");
        }
    }

    @Test
    void lateComersToFinalBossesGetNoLoot() {
        // 2026-10-15：終局以上的 Boss，生命掉到 75% 以下才進來的人沒有掉落（隊友也一樣）；一般地圖不管
        final MapConfig.MapDef awakener = config.find("awakener");
        final MapInstance instance = new MapInstance(4, awakener, null, MapLayout.generate(4, 6), null, 0, 0, 0,
                java.util.UUID.randomUUID(), "x", config.pinnacle("awakener").attempts(), 0);
        instance.setPinnacle(config.pinnacle("awakener"));
        assertTrue(instance.lateEntryRule());
        final java.util.UUID early = java.util.UUID.randomUUID();
        final java.util.UUID atEdge = java.util.UUID.randomUUID();
        final java.util.UUID late = java.util.UUID.randomUUID();
        assertTrue(instance.lootEligible(late), "還沒有人記進名單（一下就打死）：都有");
        assertEquals(List.of(), instance.noteRoster(List.of(early), 1.0));
        assertEquals(List.of(), instance.noteRoster(List.of(early, atEdge), 0.75), "剛好 75% 還算");
        assertEquals(List.of(late), instance.noteRoster(List.of(early, atEdge, late), 0.74), "低於 75% 才進來的要提醒");
        assertEquals(List.of(), instance.noteRoster(List.of(early, atEdge, late), 0.5), "只提醒一次");
        assertTrue(instance.lootEligible(early));
        assertTrue(instance.lootEligible(atEdge));
        assertFalse(instance.lootEligible(late), "殘血才進來：沒有掉落");
        final MapConfig.MapDef frost = config.find("frost_throne");
        assertTrue(new MapInstance(5, frost, null, MapLayout.generate(5, 6), null, 1, 0, 0, java.util.UUID.randomUUID(), "x", 2, 0).lateEntryRule(),
                "終局也算");
        final MapInstance normal = new MapInstance(6, config.find("wreck_bay"), null, MapLayout.generate(6, 6), null, 2, 0, 0,
                java.util.UUID.randomUUID(), "x", 6, 0);
        assertFalse(normal.lateEntryRule());
        normal.noteRoster(List.of(early), 1.0);
        assertTrue(normal.lootEligible(late), "一般地圖不管什麼時候進來");
    }

    @Test
    void pinnacleDamageAboveTheCapIsHeavilyReduced() {
        // 2026-10-21 第四版：每翻一倍一段，比例逐段遞減（2000 萬 → 等效 26.43 萬）
        final MapConfig.Pinnacle pinnacle = config.pinnacle("awakener");
        // 2026-10-21 晚：巔峰照打 Boss 每秒 200 萬打 3 分半，400 萬起遞減
        assertEquals(4000000, pinnacle.dpsCap(), 1e-9); // 2026-10-29 照站長：400 萬起；2026-10-27 照站長：200 萬起遞減（這次之前 100 萬）
        // 遞減的算法照舊（用以前 15 萬那一組分段驗算）
        final List<MapConfig.DpsTier> over = List.of(new MapConfig.DpsTier(150000, 0.08), new MapConfig.DpsTier(300000, 0.045), new MapConfig.DpsTier(600000, 0.024), new MapConfig.DpsTier(1200000, 0.013), new MapConfig.DpsTier(2500000, 0.007), new MapConfig.DpsTier(5000000, 0.004), new MapConfig.DpsTier(10000000, 0.002), new MapConfig.DpsTier(20000000, 0.001));
        assertEquals(100000, DpsCap.effective(100000, 150000, over), 1e-6, "上限以內照算");
        assertEquals(162000, DpsCap.effective(300000, 150000, over), 1e-6);
        assertEquals(171000, DpsCap.effective(500000, 150000, over), 1e-6);
        assertEquals(175500, DpsCap.effective(600000, 150000, over), 1e-6);
        assertEquals(189900, DpsCap.effective(1200000, 150000, over), 1e-6);
        assertEquals(206800, DpsCap.effective(2500000, 150000, over), 1e-6);
        assertEquals(224300, DpsCap.effective(5000000, 150000, over), 1e-6);
        assertEquals(232300, DpsCap.effective(7000000, 150000, over), 1e-6);
        assertEquals(244300, DpsCap.effective(10000000, 150000, over), 1e-6);
        assertEquals(264300, DpsCap.effective(20000000, 150000, over), 1e-6);
        assertEquals(150000, DpsCap.effective(1000000, 150000, List.of()), 1e-6, "沒寫分段：超過的全部不算");
        final DpsCap cap = new DpsCap();
        // 2026-10-29 照站長：遞減看這一下之前的平均（不含這一下、不看假人紀錄）——單發大傷害照算，下一下才遞減
        assertEquals(10_000_000, cap.apply("a", 10_000_000, 150000, over, 0, 300000), 1e-6, "第一下一千萬照算");
        final double next = cap.apply("a", 10_000_000, 150000, over, 2, 300000);
        assertTrue(next < 10_000_000 * 0.2, "緊接著的下一下大幅遞減：" + next);
        // 沒打過假人、實際每秒打 50 萬：打一陣子之後等效大約 17.1 萬
        double dealt = 0;
        for (int tick = 0; tick < 20 * 20; tick += 2) {
            final double hit = cap.apply("b", 50000, 150000, over, tick, 0); // 每 2 tick 5 萬 = 每秒 50 萬
            if (tick >= 20 * 10) {
                dealt += hit;
            }
        }
        assertEquals(171000, dealt / 10, 10000, "後 10 秒平均每秒"); // 2026-10-29：看 4 秒平均、不含這一下，穩定值略高（約 17.8 萬）；這次之前容差 5000
        assertEquals(10, cap.apply("x", 10, 0, over, 0, 999999), 1e-9, "上限 0 = 不限");
        // 2026-10-21：開場 4 秒後只看實際打出來的：紀錄 47 萬、實際每秒 10 萬（每 2 tick 1 萬）的人不被減
        double last = 0;
        for (long tick = 0; tick <= 200; tick += 2) {
            last = cap.apply("c", 10000, 150000, over, tick, 470000);
        }
        assertEquals(10000, last, 1e-6, "實際每秒 10 萬，沒超過 15 萬：照算");
        cap.forget(0);
    }

    /** 2026-10-27：極境 Boss 的擊殺稱號要在旁邊而且打到傷害門檻才記（這次之前站在旁邊沒打也算）。 */
    @Test
    void bossKillCreditRequiresDamage() {
        final java.util.UUID hitter = java.util.UUID.randomUUID();
        final java.util.UUID idle = java.util.UUID.randomUUID();
        final Set<java.util.UUID> damagers = Set.of(hitter);
        assertTrue(MapBosses.earnsKillCredit(damagers, hitter, true));
        assertFalse(MapBosses.earnsKillCredit(damagers, idle, true), "沒打到門檻不算");
        assertFalse(MapBosses.earnsKillCredit(damagers, hitter, false), "不在 Boss 旁邊不算");
    }
}
