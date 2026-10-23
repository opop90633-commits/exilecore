package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：腐化過的傳奇數值可以超出原本範圍（×0.78～1.22），不能被當成舊版重擲（以前穿上去 52% 變回 48%）。 */
class CorruptUniqueTest {

    @Test
    void corruptedUniqueKeepsOutOfRangeRolls() throws IOException {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final ItemGenerator generator = new ItemGenerator(repository);
        int outside = 0;
        for (final UniqueItem unique : repository.uniques()) {
            for (int seed = 0; seed < 20; seed++) {
                final ItemData data = generator.generateUnique(unique, 84, new Random(seed));
                generator.corruptUniqueValues(data, new Random(seed * 31L + 7));
                data.setCorrupted(true);
                final List<Map<String, Integer>> before = new ArrayList<>();
                for (final RolledAffix rolled : data.affixes()) {
                    before.add(new LinkedHashMap<>(rolled.vars()));
                    final Affix affix = repository.affix(rolled.id());
                    if (affix != null && !UniqueUpgrade.fits(affix.tiers().getFirst(), rolled.vars())) {
                        outside++;
                    }
                }
                assertFalse(UniqueUpgrade.outdated(repository, data), unique.id() + " 種子 " + seed);
                assertFalse(UniqueUpgrade.apply(repository, data), unique.id() + " 種子 " + seed);
                for (int i = 0; i < before.size(); i++) {
                    assertEquals(before.get(i), data.affixes().get(i).vars(), unique.id() + " 種子 " + seed + " 第 " + (i + 1) + " 條");
                }
            }
        }
        assertTrue(outside > 0, "腐化應該會擲出超出原本範圍的數值");
        // 沒腐化的超出範圍還是照舊版補正
        final UniqueItem any = repository.uniques().iterator().next();
        final ItemData plain = generator.generateUnique(any, 84, new Random(3));
        for (final RolledAffix rolled : plain.affixes()) {
            if (!rolled.vars().isEmpty() && rolled.vars().values().stream().anyMatch(v -> v != 0)) {
                final Map<String, Integer> vars = new LinkedHashMap<>(rolled.vars());
                vars.replaceAll((key, value) -> value * 3 + 5);
                rolled.setVars(vars);
                break;
            }
        }
        assertTrue(UniqueUpgrade.outdated(repository, plain));
    }
}
