package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長：隱匿傳奇的每一條隱匿詞綴都要揭露得出來（淵瞳面具等 4 件的隱匿前綴在防具上沒有揭露前綴，拿掉）。 */
class UniqueVeiledPoolTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    @Test
    void everyVeiledSideCanBeUnveiled() {
        final List<String> stuck = new ArrayList<>();
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase base = repository.base(unique.base());
            for (final AffixType type : repository.uniqueVeiled(unique.id())) {
                boolean any = false;
                for (final Affix affix : repository.affixes()) {
                    any |= Veiled.isUnveiled(affix.id()) && affix.type() == type && base != null && affix.canSpawnOn(base)
                            && !affix.spawnTags().contains(ItemRepository.RETIRED_TAG);
                }
                if (!any) {
                    stuck.add(unique.id() + " " + type);
                }
            }
        }
        assertTrue(stuck.isEmpty(), "揭露不了的隱匿詞綴：" + stuck);
    }

    @Test
    void oldItemsGetFixed() {
        final ItemData data = new ItemGenerator(repository).generateUnique(repository.unique("abyss_shroud_robe"), 70, new SplittableRandom(4));
        data.affixes().removeIf(rolled -> Veiled.isVeiled(rolled.id()));
        final Affix prefix = repository.affix(Veiled.VEILED_PREFIX);
        data.affixes().add(new RolledAffix(Veiled.VEILED_PREFIX, prefix.tiers().getFirst().tier(), Map.of()));
        data.affixes().add(new RolledAffix(Veiled.VEILED_PREFIX, prefix.tiers().getFirst().tier(), Map.of()));
        assertTrue(Veiled.syncUniqueVeiled(data, repository, new SplittableRandom(1)));
        assertEquals(0, data.affixes().stream().filter(rolled -> rolled.id().equals(Veiled.VEILED_PREFIX)).count(), "隱匿前綴拿掉");
        assertEquals(1, data.affixes().stream().filter(rolled -> rolled.id().equals(Veiled.VEILED_SUFFIX)).count(), "補一條隱匿後綴");
        assertTrue(!Veiled.syncUniqueVeiled(data, repository, new SplittableRandom(1)), "補正過就不再動");
    }
}
