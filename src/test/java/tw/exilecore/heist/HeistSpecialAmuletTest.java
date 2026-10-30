package tw.exilecore.heist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照站長：特殊項鍊只在劫盜掉（0.5%），也在金庫之主的收藏裡；一般掉落、商人、賭博都拿不到。 */
class HeistSpecialAmuletTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final Set<String> SPECIAL = Set.of("jamulet_twisted", "jamulet_distorted", "jamulet_dusk", "jamulet_gloam", "jamulet_penumbra",
            "jamulet_tenebrous", "jamulet_corona",
            // 2026-10-28 照站長：特殊戒指也只在劫盜掉
            "jring_biostatic", "jring_unset", "jring_breach", "jring_breach_refined", "jring_abyssal", "jring_dusk", "jring_gloam",
            "jring_penumbra", "jring_tenebrous");
    static final Set<String> RETIRED_SPECIAL = Set.of("jamulet_lament", "jamulet_portent", "jamulet_absent");
    static ItemRepository repository;
    static HeistConfig heist;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        heist = new HeistConfig();
        heist.load(YamlNode.load(DATA.resolve(HeistConfig.FILE)));
    }

    @Test
    void specialAmuletsAreHeistOnlyAndOrdinaryAmuletsAreNot() {
        for (final String id : SPECIAL) {
            assertTrue(repository.heistOnlyBase(id), id);
            assertFalse(repository.retiredBase(id), id);
        }
        for (final String id : RETIRED_SPECIAL) {
            assertTrue(repository.heistOnlyBase(id) && repository.retiredBase(id), id + "：還停用著，打開之後一樣劫盜專屬");
        }
        int ordinary = 0;
        for (final ItemBase base : repository.bases()) {
            if (base.slot() == ItemSlot.AMULET && !"jamulet_special".equals(base.family())) {
                assertFalse(repository.heistOnlyBase(base.id()), base.id() + "：一般的項鍊照常掉");
                ordinary++;
            }
        }
        assertTrue(ordinary >= 11, "PoE2 一般項鍊（赤血、蒼泉……金耀）");
        assertTrue(repository.basesDroppableAt(100).stream().anyMatch(base -> base.id().equals("jamulet_01")));
        assertTrue(repository.basesSoldAt(100).stream().anyMatch(base -> base.id().equals("jamulet_01")));
    }

    @Test
    void specialAmuletsAreNotDroppedSoldOrGambled() {
        for (final int level : new int[] {1, 40, 60, 100}) {
            for (final ItemBase base : repository.basesDroppableAt(level)) {
                assertFalse(repository.heistOnlyBase(base.id()), "一般掉落（怪物、地圖獎勵）：" + base.id());
            }
            for (final ItemBase base : repository.basesSoldAt(level)) {
                assertFalse(repository.heistOnlyBase(base.id()), "商人、賭裝商人：" + base.id());
            }
        }
        // 抽很多次也抽不到
        final Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final ItemBase dropped = repository.pickDroppable(100, random);
            assertFalse(dropped != null && SPECIAL.contains(dropped.id()), "pickDroppable");
            final ItemBase sold = repository.pickSold(100, random, tw.exilecore.item.LootBias.NONE);
            assertFalse(sold != null && SPECIAL.contains(sold.id()), "pickSold");
        }
    }

    @Test
    void heistPoolHoldsExactlyTheActiveSpecialAmulets() {
        final Set<String> pool = new TreeSet<>();
        for (final ItemBase base : repository.heistOnlyBasesAt(100)) {
            pool.add(base.id());
        }
        assertEquals(new TreeSet<>(SPECIAL), pool);
        // 日冕墜飾要 40 級
        assertFalse(repository.heistOnlyBasesAt(39).stream().anyMatch(base -> base.id().equals("jamulet_corona")));
        assertTrue(repository.heistOnlyBasesAt(40).stream().anyMatch(base -> base.id().equals("jamulet_corona")));
        final Random random = new Random(3);
        final Set<String> seen = new TreeSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(repository.pickHeistOnly(100, random).id());
        }
        assertEquals(new TreeSet<>(SPECIAL), seen);
    }

    @Test
    void heistConfigDropsAtHalfAPercent() {
        final HeistConfig.SpecialAmulets amulets = heist.specialAmulets();
        // 2026-10-30 照站長：只出現在金庫之主的收藏（金庫箱、大金庫不掉）；這次之前各 0.005
        assertEquals(0, amulets.chestChance(), 1e-9);
        assertEquals(0, amulets.vaultChance(), 1e-9);
        assertTrue(amulets.rare() > 0 && amulets.rare() < 1);
        assertEquals(0.045, amulets.finaleWeight(), 1e-9); // 2026-10-28：加了 9 種特殊戒指，0.1 → 0.045（總量不變）
        // 沒寫 special_amulet 的 heist.yml 用預設值
        final HeistConfig bare = new HeistConfig();
        bare.load(YamlNode.load("bare.yml", new java.io.StringReader("settings: {}\nsites:\n  a:\n    name: A\n    monsters: { ZOMBIE: 1 }\n    boss: { name: B, type: ZOMBIE }\n")));
        assertEquals(HeistConfig.SpecialAmulets.DEFAULTS, bare.specialAmulets());
    }

    @Test
    void finalePoolPicksAmuletsByWeight() {
        final List<FinalePool.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            entries.add(FinalePool.Entry.unique("u" + i));
        }
        for (final String id : SPECIAL) {
            entries.add(FinalePool.Entry.base(id, 0.045));
        }
        final Random random = new Random(11);
        int withAmulet = 0;
        final int runs = 20000;
        final Map<String, Integer> amuletCounts = new HashMap<>();
        for (int run = 0; run < runs; run++) {
            final List<FinalePool.Entry> picked = FinalePool.pick(entries, 5, random);
            assertEquals(5, picked.size());
            assertEquals(5, picked.stream().distinct().count(), "不重複");
            boolean any = false;
            for (final FinalePool.Entry entry : picked) {
                if (entry.base() != null) {
                    any = true;
                    amuletCounts.merge(entry.base(), 1, Integer::sum);
                }
            }
            withAmulet += any ? 1 : 0;
        }
        final double share = withAmulet / (double) runs;
        assertTrue(share > 0.15 && share < 0.25, "五個寶箱裡出現特殊項鍊的機率約兩成：" + share);
        assertEquals(SPECIAL, amuletCounts.keySet());
        // 權重 0 的不挑；不夠挑就全給
        assertEquals(1, FinalePool.pick(List.of(FinalePool.Entry.unique("a"), FinalePool.Entry.base("b", 0)), 5, random).size());
    }

    @Test
    void ordinaryHeistDataStillLoads() {
        assertNotNull(heist.finale());
    }
}
