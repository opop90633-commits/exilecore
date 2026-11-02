package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（1.4 B 組）：頭上血條的狀態與要不要畫。 */
class MobBarsTest {

    private static WorldCodec.MobBar bar(final int id, final int life, final int rarity) {
        return new WorldCodec.MobBar(id, life, -1, rarity, false, false, 0);
    }

    @Test
    void applyDeltas() {
        final MobBars bars = new MobBars();
        bars.apply(new WorldCodec.Mobs(true, List.of(bar(1, 255, 0), bar(2, 255, 0)), List.of()));
        assertEquals(2, bars.size());
        bars.apply(new WorldCodec.Mobs(false, List.of(bar(1, 100, 0)), List.of(2)));
        assertEquals(1, bars.size());
        assertEquals(100, bars.get(1).life());
        bars.apply(new WorldCodec.Mobs(true, List.of(bar(3, 255, 0)), List.of()));
        assertFalse(bars.has(1), "完整的包先清掉舊的");
        bars.apply(null);
        assertEquals(1, bars.size());
    }

    @Test
    void whichBarsShow() {
        assertFalse(MobBars.shows(bar(1, 255, WorldCodec.RARITY_NORMAL), false, false), "普通怪滿血不畫");
        assertTrue(MobBars.shows(bar(1, 255, WorldCodec.RARITY_NORMAL), false, true));
        assertTrue(MobBars.shows(bar(1, 255, WorldCodec.RARITY_NORMAL), true, false), "準心指著的一定畫");
        assertTrue(MobBars.shows(bar(1, 200, WorldCodec.RARITY_NORMAL), false, false));
        assertTrue(MobBars.shows(bar(1, 255, WorldCodec.RARITY_RARE), false, false));
        assertTrue(MobBars.shows(new WorldCodec.MobBar(1, 255, -1, 0, false, false, WorldCodec.MARK_IGNITE), false, false), "有狀態要畫");
        assertFalse(MobBars.shows(bar(1, 0, WorldCodec.RARITY_UNIQUE), true, true), "死了不畫");
    }

    @Test
    void nameTags() {
        final MobBars bars = new MobBars();
        bars.apply(new WorldCodec.Mobs(true, List.of(bar(1, 255, 0)), List.of()));
        assertTrue(bars.hidesName(1, -1, 10 * 10));
        assertFalse(bars.hidesName(1, 1, 10 * 10), "準心指著");
        assertFalse(bars.hidesName(1, -1, 5 * 5), "很近");
        assertFalse(bars.hidesName(9, -1, 10 * 10), "沒有血條的（玩家、別的伺服器的）照原版");
    }

    @Test
    void looks() {
        assertTrue(MobBars.width(bar(1, 1, WorldCodec.RARITY_RARE)) > MobBars.width(bar(1, 1, WorldCodec.RARITY_NORMAL)));
        assertTrue(MobBars.width(new WorldCodec.MobBar(1, 1, -1, 0, true, false, 0)) > MobBars.width(bar(1, 1, WorldCodec.RARITY_UNIQUE)));
        final WorldCodec.MobBar locked = new WorldCodec.MobBar(1, 200, -1, WorldCodec.RARITY_UNIQUE, true, true, 0);
        assertEquals(0xFF8A8A8A, MobBars.lifeColor(locked), "無敵變灰");
        assertEquals(0xFFFFD040, MobBars.frameColor(locked), "金色外框");
        assertEquals(9, MobBars.MARK_COLORS.length);
    }
}
