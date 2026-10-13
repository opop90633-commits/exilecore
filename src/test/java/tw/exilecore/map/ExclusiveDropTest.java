package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.loot.LootFilter;
import tw.exilecore.monster.DropTable;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 專屬掉落：地圖事件的傳奇（1%）、終局與極境的專屬輔助寶石、極境的專屬傳奇、指定寶石的掉落與過濾器。 */
class ExclusiveDropTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final List<String> FINALS = List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel");
    static ItemRepository repository;
    static ItemGenerator generator;
    static SkillConfig skills;
    static MapConfig maps;
    static MapEventConfig events;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        skills = new SkillConfig();
        skills.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
        events = new MapEventConfig();
        events.load(DATA);
    }

    // ---- 地圖事件 ----

    @Test
    void everyMapEventHasItsOwnUniqueAtOnePercent() {
        for (final MapEventConfig.Kind kind : MapEventConfig.Kind.values()) {
            assertEquals(0.01, events.uniqueChance(kind), 1e-9, kind + "：事件的專屬傳奇 1%（2026-10 從 2% 調低）");
            final String source = MapEventConfig.uniqueSource(kind);
            assertEquals(kind.name().toLowerCase(java.util.Locale.ROOT), source);
            final ItemData unique = MapDrops.unique(generator, source, 70, new Random(3));
            assertNotNull(unique, source + " 要有專屬傳奇");
            assertEquals(Rarity.UNIQUE, unique.rarity());
        }
        // 祭壇、幻霧：獎勵裡的「傳奇」那一種，從各自的專屬池抽
        assertEquals(1, events.ritual().rewardWeights().get(MapEventConfig.Reward.UNIQUE), "2026-10：2 → 1");
        assertEquals(1, events.delirium().rewardWeights().get(MapEventConfig.Reward.UNIQUE), "幻霧也會給傳奇（2026-10：2 → 1）");
        final MapEventConfig defaults = new MapEventConfig();
        assertEquals(events.ritual().rewardWeights(), defaults.ritual().rewardWeights(), "程式的預設值跟出貨的 map_events.yml 一樣");
        assertEquals(events.delirium().rewardWeights(), defaults.delirium().rewardWeights());
        // 2026-10：祭壇、幻霧的專屬池各有好幾件（原本的血祭、霧中鏡退役了），抽到的一定在自己的池裡
        for (final String source : List.of(MapEventConfig.RITUAL_SOURCE, MapEventConfig.DELIRIUM_SOURCE)) {
            final Set<String> pool = repository.uniquesFor(source, 70).stream().map(u -> u.id()).collect(java.util.stream.Collectors.toSet());
            for (int seed = 0; seed < 20; seed++) {
                final String drawn = MapDrops.unique(generator, source, 70, new Random(seed)).unique();
                assertTrue(pool.contains(drawn), source + " 抽到別的池的傳奇：" + drawn);
                assertFalse(repository.retiredUnique(drawn), source + " 抽到退役的傳奇：" + drawn);
            }
        }
        // 地圖 Boss 的共通池不會掉事件的
        for (int seed = 0; seed < 200; seed++) {
            final ItemData common = MapDrops.unique(generator, "map_boss", 84, new Random(seed));
            assertFalse(Set.of("shrine_lamp", "thousand_locks", "rift_sceptre", "exile_oath", "blood_rite", "mist_mirror")
                    .contains(common.unique()), "地圖 Boss 掉到事件的傳奇：" + common.unique());
        }
    }

    @Test
    void eventUniqueChanceCanBeConfigured() throws IOException {
        final Path folder = Files.createTempDirectory("events");
        Files.writeString(folder.resolve("map_events.yml"), """
                shrine:
                  unique_chance: 0.5
                exile:
                  unique_chance: 3
                """);
        final MapEventConfig custom = new MapEventConfig();
        custom.load(folder);
        assertEquals(0.5, custom.uniqueChance(MapEventConfig.Kind.SHRINE), 1e-9);
        assertEquals(1.0, custom.uniqueChance(MapEventConfig.Kind.EXILE), 1e-9, "夾在 0–1");
        assertEquals(0.01, custom.uniqueChance(MapEventConfig.Kind.BREACH), 1e-9, "沒寫的照預設 1%");
    }

    @Test
    void shrineLampLengthensShrineBuffs() {
        final ItemData lamp = generator.generateUnique(repository.unique("shrine_lamp"), 70, new Random(4));
        final StatSheet sheet = new StatSheet().addAll(ItemStats.modifiers(repository, lamp));
        final double longer = sheet.increased(StatKeys.SHRINE_DURATION, Set.of());
        assertTrue(longer >= 0.8 && longer <= 1.0, "神龕的增益持續時間增加 80–100%：" + longer);
    }

    // ---- 專屬輔助寶石 ----

    @Test
    void exclusiveSupportsComeFromBossesNotFromUncutGems() {
        int exclusive = 0;
        for (final SupportDefinition support : skills.supports()) {
            if (!support.cuttable()) {
                exclusive++;
                assertEquals(3, support.tier(), support.id());
                assertFalse(support.notes().isEmpty(), support.id() + "：寫出是誰掉的");
                assertFalse(skills.supportsUpTo(3).contains(support), support.id() + "：未切割輔助寶石切不出來");
            }
        }
        assertEquals(13, exclusive, "四個終局各一顆＋兩顆極境專屬＋七顆巔峰（覺醒之主・虛星）專屬");
        for (final String finalId : FINALS) {
            final List<SupportDefinition> own = skills.supportsFrom(finalId);
            assertEquals(1, own.size(), finalId + " 的專屬輔助");
            assertTrue(own.getFirst().droppedBy(finalId));
        }
        assertEquals(List.of("abyssal_touch", "myriad_stars"), skills.supportsFrom("uber").stream().map(SupportDefinition::id).toList());
        assertTrue(skills.supportsFrom("nobody").isEmpty());
        assertEquals(0.01, maps.drops().finalSupportChance(), 1e-9, "2026-10：2% → 1%");
        // 寫法：source 讀得進來；舊的建構子（沒有 source）是一般的
        final SupportDefinition plain = new SupportDefinition("x", "x", "", "BOOK", 1, Set.of(), Set.of(), 1.0, List.of(), List.of());
        assertTrue(plain.cuttable());
        assertNull(plain.source());
        assertEquals("sky_crucible", skills.support("starfall_resonance").source());
    }

    @Test
    void specificSupportDropsAreNeverFilteredOut() {
        final DropTable.Drop drop = DropTable.Drop.support("styx_ferry", 87);
        assertEquals(DropTable.Kind.SUPPORT_GEM, drop.kind());
        assertEquals("styx_ferry", drop.gem());
        assertEquals(87, drop.level());
        assertTrue(drop.isSpecificGem());
        assertFalse(DropTable.Drop.special(DropTable.Kind.SUPPORT_GEM, 70).isSpecificGem(), "未切割輔助寶石");
        assertNull(new DropTable.Drop(DropTable.Kind.FLASK, null, null, 10).gem(), "舊的建構子");
        // 過濾器把輔助寶石全藏起來時：專屬的（稀有度當成傳奇）照樣顯示
        final LootFilter filter = new LootFilter();
        filter.toggleSupportGems();
        assertTrue(filter.hidesSupportGems());
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.SUPPORT_GEM, null, null, false, 0, Set.of(), 0, 2),
                LootFilter.Viewer.NONE), "一般的未切割輔助寶石照藏");
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.SUPPORT_GEM, Rarity.UNIQUE, null, false, 0, Set.of(), 0, 0),
                LootFilter.Viewer.NONE), "專屬的永遠顯示");
    }

    // ---- 極境 ----

    @Test
    void uberDropsExclusiveUniquesAndSupports() {
        for (final String finalId : FINALS) {
            final MapConfig.Uber uber = maps.uber(finalId);
            assertNotNull(uber, finalId);
            assertEquals(0.03, uber.drops().exclusiveChance(), 1e-9, finalId + "（2026-10：5% → 3%）");
            assertEquals("uber_" + finalId, MapConfig.uberSource(finalId));
            final ItemData exclusive = MapDrops.unique(generator, MapConfig.uberSource(finalId), 91, new Random(2));
            assertNotNull(exclusive, finalId + " 的極境專屬傳奇");
            assertEquals(1, repository.uniquesFor(MapConfig.uberSource(finalId), 91).size());
            // 極境的額外掉落：這個終局自己的專屬輔助（2%，比一般版本的 1% 高）＋兩顆極境專屬（各 1%）（2026-10 調低）
            double own = 0;
            int uberOnly = 0;
            for (final MapConfig.ExtraDrop extra : uber.drops().extra()) {
                if ("support".equals(extra.kind())) {
                    final SupportDefinition support = skills.support(extra.id());
                    if (support.droppedBy(finalId)) {
                        own = extra.chance();
                    } else if (support.droppedBy("uber")) {
                        uberOnly++;
                        assertEquals(0.01, extra.chance(), 1e-9, finalId + "：極境專屬輔助 " + extra.id() + " 1%");
                    }
                }
            }
            assertEquals(0.02, own, 1e-9, finalId + "：極境掉自己專屬輔助 2%");
            assertTrue(own > maps.drops().finalSupportChance(), finalId + "：極境掉自己專屬輔助的機率比一般版本高");
            assertEquals(2, uberOnly, finalId + "：兩顆極境專屬的輔助");
        }
        // 沒寫 exclusive_chance：預設 5%
        final MapConfig.Uber bare = MapConfig.parseUber(YamlNode.load("test.yml", new java.io.StringReader("name: x\n")));
        assertEquals(0.05, bare.drops().exclusiveChance(), 1e-9);
    }
    @Test
    void pinnacleDropsItsOwnSupportsAndSkillGems() {
        // 2026-10：覺醒之主・虛星（萬界之巔）的專屬輔助、專屬技能寶石都寫在巔峰的 drops.extra，各 1%（沒有賽季重置，保持稀缺）
        final MapConfig.UberDrops drops = maps.pinnacle("awakener").drops();
        final List<String> supports = new java.util.ArrayList<>();
        final List<String> gems = new java.util.ArrayList<>();
        for (final MapConfig.ExtraDrop extra : drops.extra()) {
            if ("support".equals(extra.kind()) && skills.support(extra.id()).droppedBy("awakener")) {
                supports.add(extra.id());
                assertEquals(0.01, extra.chance(), 1e-9, extra.id());
            } else if ("skill".equals(extra.kind())) {
                assertNotNull(skills.skill(extra.id()), extra.id());
                assertTrue(skills.skill(extra.id()).droppedBy("awakener"), extra.id());
                gems.add(extra.id());
                assertEquals(0.01, extra.chance(), 1e-9, extra.id());
            }
        }
        assertEquals(skills.supportsFrom("awakener").stream().map(SupportDefinition::id).toList(), supports, "每一顆專屬輔助都會掉");
        assertEquals(skills.skillsFrom("awakener").stream().map(tw.exilecore.skill.SkillDefinition::id).toList(), gems, "每一顆專屬技能寶石都會掉");
        assertEquals(7, supports.size());
        assertEquals(6, gems.size());
        assertEquals(0.05, drops.uniqueChance(), 1e-9, "自己的十件傳奇 5%（2026-10：3% → 5%）");
        assertEquals(0.06, drops.exclusiveChance(), 1e-9, "極境專屬 6%（2026-10：3% → 6%）");
        assertEquals(0.20, drops.commonChance(maps.drops().finalCommonChance()), 1e-9, "共通傳奇 20%（只有巔峰；終局、極境照 final_common_chance）");
        for (final MapConfig.ExtraDrop extra : drops.extra()) {
            if (List.of("abyssal_touch", "myriad_stars").contains(extra.id())) {
                assertEquals(0.02, extra.chance(), 1e-9, extra.id() + "：2026-10 1% → 2%");
            }
        }
        // 沒寫 common_chance：照 drops.final_common_chance
        assertEquals(0.1, MapConfig.parseUber(YamlNode.load("test.yml", new java.io.StringReader("name: x\n"))).drops().commonChance(0.1), 1e-9);
        // 寫 skill: 的會讀成技能寶石
        final MapConfig.Uber parsed = MapConfig.parseUber(YamlNode.load("test.yml", new java.io.StringReader(
                "name: x\ndrops:\n  extra:\n    - { skill: void_meteor, chance: 0.5 }\n")));
        assertEquals(List.of(new MapConfig.ExtraDrop("skill", "void_meteor", 1, 0.5)), parsed.drops().extra());
    }
}
