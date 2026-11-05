package tw.exilecore.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 2026-11-06 照站長「空無墜飾預設必撿」：沒設定就是必撿；改成顯示會存下來，改回必撿就拿掉。 */
class AbsentDefaultAlwaysTest {

    @Test
    void absentAmuletIsAlwaysByDefaultAndShowSticks() {
        final LootFilter filter = new LootFilter();
        assertEquals(LootFilter.BaseRule.ALWAYS, filter.baseRule("jamulet_absent"));
        assertTrue(filter.alwaysBases().contains("jamulet_absent"));
        filter.setBaseRule("jamulet_absent", LootFilter.BaseRule.SHOW);
        assertEquals(LootFilter.BaseRule.SHOW, filter.baseRule("jamulet_absent"));
        final String saved = filter.encode();
        assertTrue(saved.contains("sb=jamulet_absent"), saved);
        filter.setBaseRule("jamulet_absent", LootFilter.BaseRule.ALWAYS);
        assertEquals(LootFilter.BaseRule.ALWAYS, filter.baseRule("jamulet_absent"));
        assertEquals(LootFilter.BaseRule.SHOW, filter.baseRule("iron_ring"), "其他基底照舊預設顯示");
    }
}
