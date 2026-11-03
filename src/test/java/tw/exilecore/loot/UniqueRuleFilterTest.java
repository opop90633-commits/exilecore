package tw.exilecore.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

/** 2026-11-04 照站長「傳奇要能手動選擇每種傳奇那些要隱藏」：每一種傳奇各自隱藏／必撿。 */
class UniqueRuleFilterTest {

    private static LootFilter.Candidate unique(final String id, final LootFilter.UniqueGroup group) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.UNIQUE, null, false, 68, Set.of("armour", "body"), 0, 0, 0, null, group, id);
    }

    @Test
    void ownRuleBeatsGroup() {
        final LootFilter filter = new LootFilter();
        final LootFilter.Viewer viewer = LootFilter.Viewer.NONE;
        assertFalse(filter.hides(unique("a", LootFilter.UniqueGroup.COMMON), viewer));
        filter.toggleUniqueHidden("a");
        assertTrue(filter.hides(unique("a", LootFilter.UniqueGroup.COMMON), viewer), "這一種隱藏：分組會撿也不撿");
        assertFalse(filter.hides(unique("b", LootFilter.UniqueGroup.COMMON), viewer), "別種照分組");
        filter.toggleUniques(LootFilter.UniqueGroup.COMMON);
        filter.toggleUniqueAlways("b");
        assertFalse(filter.hides(unique("b", LootFilter.UniqueGroup.COMMON), viewer), "必撿：分組隱藏也撿");
        assertTrue(filter.hides(unique("c", LootFilter.UniqueGroup.COMMON), viewer));
        filter.toggleUniqueHidden("z");
        assertFalse(filter.hides(unique("z", LootFilter.UniqueGroup.CHASE), viewer), "追逐級永遠撿");
    }

    @Test
    void savedAndCopied() throws java.io.IOException {
        final LootFilter filter = new LootFilter();
        filter.toggleUniqueHidden("a");
        filter.toggleUniqueAlways("b");
        final String text = filter.encode();
        assertTrue(text.contains(";xu=a") && text.contains(";au=b"), text);
        final LootConfig config = new LootConfig();
        config.load(LootTest.DATA);
        final LootFilter back = LootFilter.decode(text, config);
        assertEquals(LootFilter.UniqueRule.HIDE, back.uniqueRule("a"));
        assertEquals(LootFilter.UniqueRule.ALWAYS, back.uniqueRule("b"));
        assertEquals(filter, back);
        assertEquals(filter, filter.copy());
        assertEquals(LootFilter.UniqueRule.HIDE, new LootFilter().withExtras(filter).uniqueRule("a"), "換預設組合帶過去");
    }
}
