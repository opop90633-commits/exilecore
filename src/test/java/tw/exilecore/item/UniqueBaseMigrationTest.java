package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29：傳奇的基底換了，已經掉出來的也換過去；定義不能指到合併掉的舊分階。 */
class UniqueBaseMigrationTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    @Test
    void noUniqueSitsOnAMergedBase() {
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase base = repository.base(unique.base());
            assertNotNull(base, unique.id());
            assertEquals(unique.base(), repository.canonicalBase(unique.base()), unique.id() + " 的基底 " + unique.base() + " 已經合併掉了");
        }
    }

    @Test
    void droppedItemMovesToTheNewBase() {
        final UniqueItem unique = repository.unique("solar_sceptre");
        assertNotNull(unique);
        final ItemGenerator generator = new ItemGenerator(repository);
        final ItemData data = generator.generate(repository.requireBase(unique.base()), Rarity.NORMAL, 80, new java.util.Random(1));
        generator.makeUnique(data, unique, new java.util.Random(1));
        assertFalse(UniqueUpgrade.outdated(repository, data));
        data.setBase("staff_17"); // 2026-10-29 之前的舊基底
        assertTrue(BaseUpgrade.apply(repository, data), "舊長杖換成永凍長杖");
        assertEquals("cstaff_14", data.base());
        assertFalse(UniqueUpgrade.outdated(repository, data));
        assertEquals("heart_of_ice", repository.grantedSkill(data.base()));
    }

    @Test
    void oldStavesWandsSceptresMoveToTheTopCasterBase() {
        // 2026-10-29 照站長：舊的長杖、法杖、權杖不要變成 1 等武器：換成原作施法基底最高階，需求照 PoE2，物品等級保留
        for (final String[] pair : new String[][] {{"staff_16", "cstaff_14"}, {"staff_17", "cstaff_14"}, {"wand_13", "cwand_13"},
                {"wand_14", "cwand_13"}, {"sceptre_16", "csceptre_15"}}) {
            final ItemData data = new ItemGenerator(repository).generate(repository.requireBase(pair[1]), Rarity.RARE, 92, new java.util.Random(2));
            data.setBase(pair[0]);
            assertTrue(BaseUpgrade.apply(repository, data), pair[0]);
            assertEquals(pair[1], data.base());
            assertEquals(92, data.ilvl(), "物品等級保留");
            assertFalse(BaseUpgrade.apply(repository, data), "換過就不再換");
        }
        assertEquals(75, repository.base("cstaff_14").requirements().level());
        assertEquals(114, repository.base("cstaff_14").requirements().intelligence());
        assertEquals(65, repository.base("cwand_13").requirements().level());
        assertEquals(65, repository.base("csceptre_15").requirements().level());
        // 合併到照物品等級縮放的基底（防具）不動
        final ItemData boots = new ItemGenerator(repository).generate(repository.requireBase("boots_str_16"), Rarity.RARE, 80, new java.util.Random(3));
        boots.setBase("boots_str_05");
        assertFalse(BaseUpgrade.apply(repository, boots));
    }
}
