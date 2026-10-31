package tw.exilecore.passive;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.stats.Modifier;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2 0.5：永恆珠寶（英雄悲劇／卡爾葛、不朽恨意／深淵族）征服範圍 1500 內的天賦。 */
class TimelessJewelTest {

    private static TimelessJewels.Legion kalguur() {
        return TimelessJewels.legions().get(1);
    }

    private static TimelessJewels.Legion abyss() {
        return TimelessJewels.legions().get(2);
    }

    @Test
    void dataMatchesRefLegionPassives() {
        assertEquals("霜嶺", kalguur().name());
        assertEquals(37, kalguur().notables().size());
        assertEquals(3, kalguur().keystones().size());
        assertNull(kalguur().small(), "霜嶺不動小天賦（原作 資料沒有霜嶺的小點附加）");
        assertEquals("深淵族", abyss().name());
        assertEquals(30, abyss().notables().size());
        assertEquals(5, abyss().keystones().size());
        assertEquals(5, abyss().small().tribute());
        assertEquals(3, abyss().attribute().tribute());
        // 征服者 → 基石（台服名稱）
        assertEquals("黑暗血鐮訓練", kalguur().keystone(1).name());
        assertEquals("循環教義", kalguur().keystone(2).name());
        assertEquals("騎士信條", kalguur().keystone(3).name());
        assertEquals("薩岡", kalguur().conqueror(3).name());
        assertEquals("血肉獻祭", abyss().keystone(1).name());
        assertEquals("遠目獻祭", abyss().keystone(5).name());
        // 每一行都有中文、標了「尚未生效」的沒有修飾器
        for (final TimelessJewels.Legion legion : TimelessJewels.legions().values()) {
            for (final List<TimelessJewels.Template> group : List.of(legion.notables(), legion.keystones())) {
                for (final TimelessJewels.Template template : group) {
                    assertFalse(template.name().matches(".*[A-Za-z].*"), template.id() + " 名稱要中文：" + template.name());
                    for (final TimelessJewels.Line line : template.lines()) {
                        assertFalse(line.text().matches(".*[A-Za-z]{3,}.*"), template.id() + " 說明要中文：" + line.text());
                        if (line.text().endsWith("（尚未生效）")) {
                            assertTrue(line.stats().isEmpty(), template.id());
                        }
                    }
                }
            }
        }
    }

    @Test
    void kalguurConquersNotablesAndKeystonesButNotSmalls() {
        final PassiveTree tree = RadiusJewelTest.tree();
        final TimelessJewels.Conquest conquest = new TimelessJewels.Conquest("sock", kalguur(), 1234, 2);
        final Map<String, PassiveNode> conquered = TimelessJewels.conquered(tree, conquest, Set.of("n", "n2", "k", "a"));
        // 範圍 1500：n（849）、n2（1400）是核心，k（1450）是基石；a、b、c、far 是小天賦（卡爾葛不換）；s2 在 5000 外
        assertEquals(Set.of("n", "n2", "k"), conquered.keySet());
        assertEquals("循環教義", conquered.get("k").name(), "巴魯克（第 2 個征服者）的基石");
        assertEquals(PassiveNode.Type.KEYSTONE, conquered.get("k").type());
        assertTrue(conquered.get("k").modifiers().stream().anyMatch(m -> m.stat().equals(TimelessJewels.FLAG_ALT_DEX)));
        assertTrue(kalguur().notables().stream().anyMatch(t -> t.name().equals(conquered.get("n").name())), "核心換成霜嶺的核心");
        assertEquals(PassiveNode.Type.NOTABLE, conquered.get("n").type());
        // 屬性表：被征服的換掉，其他照原本
        final List<Modifier> mods = TimelessJewels.treeModifiers(tree, Set.of("n", "k", "a"), null, conquered);
        assertFalse(mods.stream().anyMatch(m -> m.stat().equals("flag.iron_reflexes")), "原本的基石沒了");
        assertFalse(mods.stream().anyMatch(m -> m.stat().equals("damage") && Math.abs(m.value() - 0.2) < 1e-9 && m.tags().isEmpty()),
                "原本的核心沒了");
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals("life.max") && m.value() == 10), "小天賦照原本");
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals(TimelessJewels.FLAG_ALT_DEX)));
    }

    @Test
    void sameSeedSameResultDifferentSeedsSpread() {
        final Set<String> names = new HashSet<>();
        for (int seed = TimelessJewels.SEED_MIN; seed < TimelessJewels.SEED_MIN + 200; seed++) {
            final TimelessJewels.Template first = TimelessJewels.notable(kalguur(), seed, "p12345");
            assertEquals(first, TimelessJewels.notable(kalguur(), seed, "p12345"), "確定性");
            names.add(first.name());
        }
        assertTrue(names.size() > 25, "種子不同會換成不同的核心：" + names.size());
        assertNotEquals(TimelessJewels.notable(kalguur(), 500, "p1").name() + TimelessJewels.notable(kalguur(), 500, "p2").name()
                        + TimelessJewels.notable(kalguur(), 500, "p3").name(),
                TimelessJewels.notable(kalguur(), 500, "p1").name().repeat(3), "同一顆珠寶不同節點也會不同");
    }

    @Test
    void abyssTurnsSmallsIntoTributeAndScalesNotables() {
        final PassiveTree tree = RadiusJewelTest.tree();
        final TimelessJewels.Conquest conquest = new TimelessJewels.Conquest("sock", abyss(), 777, 1);
        final Set<String> allocated = Set.of("a", "b", "c", "far", "attr", "n");
        final Map<String, PassiveNode> conquered = TimelessJewels.conquered(tree, conquest, allocated);
        // 小天賦換成 +5 貢禮（沒有屬性）、屬性點保留「+5 任一屬性」再加 +3 貢禮
        assertEquals("貢禮", conquered.get("a").name());
        assertTrue(conquered.get("a").modifiers().isEmpty());
        assertTrue(conquered.get("attr").modifiers().stream().anyMatch(m -> m.stat().equals("attr.choice")), "屬性點原本的效果還在");
        assertTrue(conquered.get("attr").description().contains("+3 貢禮"));
        // 貢禮：4 個小天賦 × 5 + 屬性點 3 = 23（黑暗門徒再增加 20%）
        final TimelessJewels.Template notable = TimelessJewels.notable(abyss(), 777, "n");
        final int expected = (int) Math.floor(23 * (1 + notable.lines().stream().mapToDouble(TimelessJewels.Line::tributeIncrease).sum() / 100.0) + 1e-9);
        assertTrue(conquered.get("n").description().contains("（目前已配置的貢禮：" + expected + "）"), conquered.get("n").description().toString());
        // 每 N 點貢禮的行：值 = 原本 × (貢禮 / N)
        double want = 0;
        String stat = null;
        for (final TimelessJewels.Line line : notable.lines()) {
            if (line.per() > 0 && !line.stats().isEmpty()) {
                stat = line.stats().getFirst().stat();
                want = line.stats().getFirst().value() * (expected / line.per());
                break;
            }
        }
        if (stat != null) {
            final String key = stat;
            final double got = conquered.get("n").modifiers().stream().filter(m -> m.stat().equals(key)).mapToDouble(Modifier::value).sum();
            assertEquals(want, got, 1e-9);
        }
        // 沒配小天賦就沒有貢禮
        assertTrue(TimelessJewels.conquered(tree, conquest, Set.of("n")).get("n").description().contains("（目前已配置的貢禮：0）"));
    }

    @Test
    void uniquesRollSeedAndConquerorAndDropFromRefSources() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final ItemGenerator generator = new ItemGenerator(repository);
        final UniqueItem heroic = repository.unique("frost_elegy_jewel");
        final UniqueItem undying = repository.unique("abyss_grudge_jewel");
        assertEquals("英雄末路", heroic.name());
        assertEquals("永恆憎恨", undying.name());
        assertTrue(heroic.droppedBy("final"), "照 PoE2：終局 Boss 掉");
        assertTrue(undying.droppedBy("abyss_lich"), "照 PoE2：深淵的巔峰 Boss 掉");
        // 2026-10-26：固定 1%（drop_chance），不在來源的權重池裡
        assertEquals(0.01, repository.dropChance("frost_elegy_jewel"), 1e-12);
        assertEquals(0.01, repository.dropChance("abyss_grudge_jewel"), 1e-12);
        assertFalse(repository.uniquesFor("final", 84).contains(heroic));
        assertFalse(repository.uniquesFor("abyss_lich", 84).contains(undying));
        assertTrue(repository.fixedChanceUniques("final", 84).contains(heroic));
        assertTrue(repository.fixedChanceUniques("abyss_lich", 84).contains(undying));
        assertTrue(TimelessJewels.isTimeless(repository.base(heroic.base())));
        assertFalse(repository.base(heroic.base()).isRadiusJewel());
        assertEquals(0, repository.base(heroic.base()).dropWeight(), "一般掉落抽不到恆時珠寶基底");
        final java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 30; i++) {
            final ItemData data = generator.generateUnique(i % 2 == 0 ? heroic : undying, 80, random);
            final TimelessJewels.Conquest conquest = TimelessJewels.read(repository, "x", data);
            assertNotNull(conquest);
            assertEquals(i % 2 == 0 ? 1 : 2, conquest.legion().code());
            assertTrue(conquest.seed() >= TimelessJewels.SEED_MIN && conquest.seed() <= TimelessJewels.SEED_MAX, "種子 " + conquest.seed());
            assertTrue(conquest.conqueror() >= 1 && conquest.conqueror() <= conquest.legion().conquerors().size());
            // 文字照征服者顯示名字（{who:甲|乙}）
            final String first = ItemStats.affixLines(repository, data).getFirst().text();
            assertTrue(first.contains(conquest.conquerorInfo().name()), first);
            assertFalse(first.contains("{"), first);
            // 永恆珠寶自己的詞綴不進屬性表（JewelSockets.modifiers 跳過）
            final PassiveTree tree = RadiusJewelTest.tree();
            assertTrue(JewelSockets.modifiers(repository, Map.of("sock", data), Set.of("sock"), tree).isEmpty());
        }
        // 圖鑑的範圍文字列出全部征服者
        final Affix affix = repository.affix(heroic.affixIds().getFirst());
        assertTrue(affix.rangeText(1).contains("蕾希雅／巴魯克／薩岡"), affix.rangeText(1));
    }

    @Test
    void activeConquestNeedsAllocatedSocket() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final ItemData data = new ItemGenerator(repository).generateUnique(repository.unique("frost_elegy_jewel"), 80, new java.util.Random(3));
        final PassiveTree tree = RadiusJewelTest.tree();
        assertNull(TimelessJewels.active(repository, Map.of("sock", data), Set.of(), tree), "孔沒配不算");
        final TimelessJewels.Conquest conquest = TimelessJewels.active(repository, Map.of("sock", data), Set.of("sock", "k"), tree);
        assertNotNull(conquest);
        final List<Modifier> mods = TimelessJewels.treeModifiers(repository, Map.of("sock", data), tree, Set.of("sock", "k"), null);
        assertFalse(mods.stream().anyMatch(m -> m.stat().equals("flag.iron_reflexes")), "基石被征服");
        assertTrue(mods.stream().anyMatch(m -> m.stat().startsWith("flag.timeless_alt_")), "換成征服者的基石");
    }
}
