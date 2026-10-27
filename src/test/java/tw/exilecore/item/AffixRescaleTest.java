package tw.exilecore.item;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：詞綴資料改版時，舊物品身上的詞綴照比例換到新範圍（AffixRescale）。 */
class AffixRescaleTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    private static ItemBase baseFor(final Affix affix) {
        return repository.bases().stream().filter(affix::canSpawnOn).findFirst().orElseThrow();
    }

    private static Map<String, int[]> range(final int lo, final int hi) {
        final Map<String, int[]> ranges = new LinkedHashMap<>();
        ranges.put("value", new int[] {lo, hi});
        return ranges;
    }

    /** 一件稀有物品，帶一條「上次記下的範圍是 old」的詞綴（模擬資料改版之前擲的）。 */
    private static ItemData rare(final Affix affix, final int tier, final int value, final Map<String, int[]> old) {
        final ItemData data = new ItemData(baseFor(affix).id(), Rarity.RARE, 84);
        final RolledAffix rolled = new RolledAffix(affix.id(), tier, Map.of("value", value));
        if (old != null) {
            rolled.record(tier, old, affix.group(), affix.type().name().toLowerCase(java.util.Locale.ROOT));
        }
        data.affixes().add(rolled);
        return data;
    }

    @Test
    void rangeGrowsKeepsTheSamePosition() {
        final Affix affix = repository.affix("life_flat");
        final int[] now = affix.ranges(1).get("value"); // [120, 149]
        final ItemData data = rare(affix, 1, 107, range(100, 110)); // 舊範圍的 70%
        assertTrue(AffixRescale.apply(repository, data));
        final RolledAffix rolled = data.affixes().getFirst();
        assertEquals((int) Math.round(now[0] + 0.7 * (now[1] - now[0])), rolled.vars().get("value"), "70% 換到新範圍的 70%");
        assertArrayEquals(now, rolled.ranges().get("value"), "記下新的範圍");
        assertFalse(AffixRescale.apply(repository, data), "換過一次就不再換");
    }

    @Test
    void rangeShrinksKeepsTheSamePosition() {
        final Affix affix = repository.affix("life_flat");
        final int[] now = affix.ranges(1).get("value");
        final ItemData data = rare(affix, 1, 300, range(200, 400)); // 舊範圍的 50%
        AffixRescale.apply(repository, data);
        assertEquals((int) Math.round(now[0] + 0.5 * (now[1] - now[0])), data.affixes().getFirst().vars().get("value"));
        final ItemData top = rare(affix, 1, 400, range(200, 400));
        AffixRescale.apply(repository, top);
        assertEquals(now[1], top.affixes().getFirst().vars().get("value"), "最高的還是最高");
    }

    @Test
    void oldItemsWithoutRecordedRangesAreClamped() {
        final Affix affix = repository.affix("life_flat");
        final int[] now = affix.ranges(1).get("value");
        final ItemData inside = rare(affix, 1, now[0] + 3, null);
        assertTrue(AffixRescale.apply(repository, inside), "第一次要記下範圍");
        assertEquals(now[0] + 3, inside.affixes().getFirst().vars().get("value"), "在新範圍裡的留著");
        assertNotNull(inside.affixes().getFirst().ranges());
        final ItemData above = rare(affix, 1, now[1] + 50, null);
        AffixRescale.apply(repository, above);
        assertEquals(now[1], above.affixes().getFirst().vars().get("value"), "超出的夾進新範圍");
        final ItemData below = rare(affix, 1, 1, null);
        AffixRescale.apply(repository, below);
        assertEquals(now[0], below.affixes().getFirst().vars().get("value"));
    }

    @Test
    void missingTierBecomesTheNearestTier() {
        final Affix affix = repository.affix("life_flat");
        final AffixTier weakest = affix.tiers().getLast();
        final ItemData data = rare(affix, weakest.tier() + 5, 10, range(5, 15)); // 這一階新資料沒有了，舊範圍的 50%
        AffixRescale.apply(repository, data);
        final RolledAffix rolled = data.affixes().getFirst();
        assertEquals(weakest.tier(), rolled.tier(), "換成代號最接近的那一階");
        final int[] now = weakest.vars().get("value");
        assertEquals((int) Math.round(now[0] + 0.5 * (now[1] - now[0])), rolled.vars().get("value"));
        assertEquals(affix.tiers().getFirst().tier(), AffixRescale.nearestTier(affix, -40).tier(), "比最強還強的換成最強的");
    }

    @Test
    void removedAffixIsReplacedBySameGroup() {
        final Affix affix = repository.affix("life_flat");
        final ItemData data = new ItemData(baseFor(affix).id(), Rarity.RARE, 84);
        final RolledAffix gone = new RolledAffix("life_flat_removed_2026", 1, Map.of("value", 75));
        gone.record(1, range(50, 100), affix.group(), "prefix"); // 擲出時記下的群組、前後綴
        data.affixes().add(gone);
        data.setFractured("life_flat_removed_2026");
        assertTrue(AffixRescale.apply(repository, data));
        assertEquals(1, data.affixes().size());
        final RolledAffix now = data.affixes().getFirst();
        final Affix replaced = repository.affix(now.id());
        assertNotNull(replaced, now.id());
        assertEquals(affix.group(), replaced.group(), "同一個群組");
        assertEquals(AffixType.PREFIX, replaced.type());
        final int[] range = replaced.ranges(now.tier()).get("value");
        assertEquals((int) Math.round(range[0] + 0.5 * (range[1] - range[0])), now.vars().get("value"), "位置照原本的 50%");
        assertEquals(now.id(), data.fractured(), "破裂的跟著換");
        assertFalse(AffixRescale.apply(repository, data));
    }

    @Test
    void retiredAffixIsReplacedBySameKind() {
        final Affix retired = repository.affix("jewel_life_inc");
        assertTrue(AffixRescale.retired(retired));
        final ItemBase jewel = repository.bases().stream().filter(ItemBase::isJewel).filter(base -> !base.isRadiusJewel())
                .findFirst().orElseThrow();
        final ItemData data = new ItemData(jewel.id(), Rarity.RARE, 80);
        data.affixes().add(new RolledAffix(retired.id(), retired.tiers().getFirst().tier(), Map.of("value", 8)));
        assertTrue(AffixRescale.apply(repository, data));
        assertEquals(1, data.affixes().size(), "換掉，不是只拿掉");
        final Affix replaced = repository.affix(data.affixes().getFirst().id());
        assertNotNull(replaced);
        assertFalse(AffixRescale.retired(replaced), replaced.id());
        assertEquals(AffixType.PREFIX, replaced.type(), "同前後綴");
        assertTrue(replaced.canSpawnOn(jewel), replaced.id() + " 要能出在 " + jewel.id());
        assertTrue(replaced.mods().stream().anyMatch(mod -> mod.stat().equals("life.max")), "同屬性：" + replaced.id());
        assertTrue(replaced.tiersFor(80).stream().anyMatch(tier -> tier.tier() == data.affixes().getFirst().tier()), "物品等級擲得到的那階");
    }

    @Test
    void unknownAffixWithoutRecordIsReplacedByARandomOne() {
        final Affix affix = repository.affix("life_flat");
        final ItemData data = new ItemData(baseFor(affix).id(), Rarity.RARE, 84);
        data.affixes().add(new RolledAffix("no_such_affix_ever", 1, Map.of("value", 5)));
        final ItemData copy = data.copy();
        assertTrue(AffixRescale.apply(repository, data));
        assertEquals(1, data.affixes().size(), "補一條隨機詞綴");
        assertNotNull(repository.affix(data.affixes().getFirst().id()));
        // 同一件（UUID 一樣）補出來每次都一樣（讀物品時每次都換算，不能每次不一樣）
        AffixRescale.apply(repository, copy);
        assertEquals(data.affixes().getFirst().id(), copy.affixes().getFirst().id());
        assertEquals(data.affixes().getFirst().vars(), copy.affixes().getFirst().vars());
    }

    @Test
    void corruptedItemsKeepTheirCorruptionAndScale() {
        final Affix affix = repository.affix("life_flat");
        final int[] now = affix.ranges(1).get("value");
        final ItemData data = rare(affix, 1, 122, range(50, 100)); // 腐化：舊範圍最高的 1.22 倍
        data.setCorrupted(true);
        AffixRescale.apply(repository, data);
        assertTrue(data.corrupted(), "腐化狀態不動");
        assertEquals((int) Math.round(now[1] * 1.22), data.affixes().getFirst().vars().get("value"), "照超出的倍數換到新範圍");
        // 沒記範圍的舊腐化物品：放寬成 0.78～1.22 倍再夾
        final ItemData old = rare(affix, 1, now[1] + 10, null);
        old.setCorrupted(true);
        AffixRescale.apply(repository, old);
        assertEquals(now[1] + 10, old.affixes().getFirst().vars().get("value"));
        // 腐化詞綴（蝕變石）一樣照比例
        final Affix enchant = repository.affixes().stream()
                .filter(one -> one.spawnTags().contains(ItemRepository.CORRUPTION_TAG))
                .filter(one -> one.tiers().getFirst().vars().values().stream().anyMatch(r -> r[0] != r[1]))
                .findFirst().orElseThrow();
        final String key = enchant.tiers().getFirst().vars().entrySet().stream().filter(e -> e.getValue()[0] != e.getValue()[1])
                .findFirst().orElseThrow().getKey();
        final int[] enchantNow = enchant.tiers().getFirst().vars().get(key);
        final ItemBase base = repository.bases().stream().filter(b -> ItemGenerator.corruptionFits(enchant, b)).findFirst().orElseThrow();
        final ItemData withEnchant = new ItemData(base.id(), Rarity.RARE, 84);
        withEnchant.setCorrupted(true);
        final Map<String, Integer> vars = new LinkedHashMap<>();
        final Map<String, int[]> oldRanges = new LinkedHashMap<>();
        enchant.tiers().getFirst().vars().forEach((k, r) -> {
            vars.put(k, k.equals(key) ? 1000 : Math.min(r[0], r[1]));
            oldRanges.put(k, k.equals(key) ? new int[] {0, 1000} : r);
        });
        final RolledAffix rolled = new RolledAffix(enchant.id(), enchant.tiers().getFirst().tier(), vars);
        rolled.record(rolled.tier(), oldRanges, enchant.group(), enchant.type().name().toLowerCase(java.util.Locale.ROOT));
        withEnchant.affixes().add(rolled);
        AffixRescale.apply(repository, withEnchant);
        assertEquals(enchant.id(), withEnchant.affixes().getFirst().id());
        assertEquals(Math.max(enchantNow[0], enchantNow[1]), withEnchant.affixes().getFirst().vars().get(key), "腐化詞綴的最高換到新範圍的最高");
        assertTrue(withEnchant.corrupted());
    }

    @Test
    void uniqueAffixesFollowUniquesYml() {
        final ItemGenerator generator = new ItemGenerator(repository);
        UniqueItem target = null;
        String key = null;
        int index = -1;
        outer:
        for (final UniqueItem unique : repository.uniques()) {
            final ItemData probe = generator.generateUnique(unique, 84, new Random(1));
            for (int i = 0; i < probe.affixes().size(); i++) {
                final Affix affix = repository.affix(probe.affixes().get(i).id());
                if (affix == null || !probe.affixes().get(i).id().startsWith(UniqueItem.AFFIX_PREFIX)) {
                    continue;
                }
                for (final Map.Entry<String, int[]> entry : affix.tiers().getFirst().vars().entrySet()) {
                    if (entry.getValue()[1] - entry.getValue()[0] >= 10) {
                        target = unique;
                        key = entry.getKey();
                        index = i;
                        break outer;
                    }
                }
            }
        }
        assertNotNull(target, "要有一件範圍夠寬的傳奇");
        final ItemData data = generator.generateUnique(target, 84, new Random(1));
        final RolledAffix rolled = data.affixes().get(index);
        final Affix affix = repository.affix(rolled.id());
        final int[] now = affix.tiers().getFirst().vars().get(key);
        // 模擬舊版 uniques.yml：這一條的範圍是 [0, 10]，擲到 10（最高）
        final Map<String, int[]> old = new LinkedHashMap<>();
        final Map<String, Integer> vars = new LinkedHashMap<>(rolled.vars());
        for (final Map.Entry<String, int[]> entry : affix.tiers().getFirst().vars().entrySet()) {
            old.put(entry.getKey(), entry.getKey().equals(key) ? new int[] {0, 10} : entry.getValue());
        }
        vars.put(key, 10);
        rolled.setVars(vars);
        rolled.record(rolled.tier(), old, affix.group(), affix.type().name().toLowerCase(java.util.Locale.ROOT));
        UniqueUpgrade.apply(repository, data);
        AffixRescale.apply(repository, data);
        assertEquals(Math.max(now[0], now[1]), data.affixes().get(index).vars().get(key), "舊範圍最高的換到 uniques.yml 現在範圍的最高");
        assertFalse(UniqueUpgrade.apply(repository, data));
        assertFalse(AffixRescale.apply(repository, data), "只換一次");
    }

    @Test
    void implicitFollowsTheNewRange() {
        ItemBase base = null;
        String key = null;
        for (final ItemBase candidate : repository.bases()) {
            if (candidate.implicit() == null) {
                continue;
            }
            for (final Map.Entry<String, int[]> entry : candidate.implicit().vars().entrySet()) {
                if (entry.getValue()[1] - entry.getValue()[0] >= 4) {
                    base = candidate;
                    key = entry.getKey();
                    break;
                }
            }
            if (base != null) {
                break;
            }
        }
        assertNotNull(base);
        final ItemData data = new ItemData(base.id(), Rarity.NORMAL, 84);
        final Implicit implicit = ItemStats.implicit(repository, base, data);
        final Map<String, Integer> vars = new LinkedHashMap<>();
        final Map<String, int[]> old = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : implicit.vars().entrySet()) {
            vars.put(entry.getKey(), entry.getKey().equals(key) ? 1 : Math.min(entry.getValue()[0], entry.getValue()[1]));
            old.put(entry.getKey(), entry.getKey().equals(key) ? new int[] {1, 2} : entry.getValue());
        }
        data.setImplicitVars(vars);
        data.setImplicitRanges(old);
        assertTrue(AffixRescale.apply(repository, data));
        final int[] now = implicit.vars().get(key);
        assertEquals(Math.min(now[0], now[1]), data.implicitVars().get(key), "舊範圍最低的換到新範圍最低");
        assertFalse(AffixRescale.apply(repository, data));
    }

    @Test
    void recordedRangesSurviveJson() {
        final RolledAffix rolled = new RolledAffix("life_flat", 1, Map.of("value", 130));
        rolled.record(1, range(120, 149), "increased_life", "prefix");
        final Gson gson = new Gson();
        final RolledAffix back = gson.fromJson(gson.toJson(rolled), RolledAffix.class);
        assertArrayEquals(new int[] {120, 149}, back.ranges().get("value"));
        assertEquals("increased_life", back.group());
        final RolledAffix plain = gson.fromJson("{\"id\":\"life_flat\",\"tier\":1,\"vars\":{\"value\":130}}", RolledAffix.class);
        assertNull(plain.ranges(), "舊物品沒記");
        assertNotEquals(0, repository.affixRevision());
    }
}
