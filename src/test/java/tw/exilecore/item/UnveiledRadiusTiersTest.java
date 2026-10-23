package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：遺時星晶的揭露詞綴兩階數值本來一樣：第 2 階改弱，舊物品讀的時候照那一階補上新變數。 */
class UnveiledRadiusTiersTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    private static String rendered(final String id, final int tier) {
        final Affix affix = repository.affix(id);
        final RolledAffix old = new RolledAffix(id, tier, Map.of("value", 1)); // 舊物品：只有 value
        final ItemData data = new ItemData("no_such_base", Rarity.RARE, 80);
        data.affixes().add(old);
        assertTrue(AffixUpgrade.apply(repository, data), id + " 要補新變數");
        return affix.render(old.vars());
    }

    @Test
    void secondTierIsWeakerAndOldItemsUpgrade() {
        for (final String id : new String[] {"unveiled_jr_percent_maximum_life", "unveiled_jr_percent_maximum_mana",
                "unveiled_jr_damage_taken_recoup_life", "unveiled_jr_damage_taken_from_mana_before_life"}) {
            assertTrue(rendered(id, 1).contains(" 1%"), rendered(id, 1));
            assertTrue(rendered(id, 2).contains("0.5%"), rendered(id, 2));
        }
        final String flask1 = rendered("unveiled_jr_mana_flask_charges_per_second", 1);
        final String flask2 = rendered("unveiled_jr_mana_flask_charges_per_second", 2);
        assertTrue(flask1.contains(" 6 "), flask1);
        assertTrue(flask2.contains(" 4 "), flask2);
        for (final AffixMod mod : repository.affix("unveiled_jr_percent_maximum_life").mods()) {
            assertEquals(0.005, mod.toModifier(Map.of("p", 5), "x").value(), 1e-9, "第 2 階是增加 0.5%");
        }
    }
}
