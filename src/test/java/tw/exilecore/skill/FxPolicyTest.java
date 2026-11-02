package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 粒子特效的規則（{@link FxPolicy}，2026-11-02 照站長「伺服器粒子太多」）。 */
class FxPolicyTest {

    private static final UUID ME = new UUID(1, 1);
    private static final UUID YOU = new UUID(2, 2);

    // ---- 一圈幾點 ----

    @Test
    void ringPointsFollowCircumference() {
        assertEquals(FxPolicy.RING_MIN, FxPolicy.ringPoints(0.3, 1), "很小的圈至少 6 點");
        assertEquals(FxPolicy.RING_MIN, FxPolicy.ringPoints(0.8, 1));
        assertEquals(15, FxPolicy.ringPoints(2, 1), "半徑 2：周長 12.6 格，每 0.85 格一點");
        assertEquals(22, FxPolicy.ringPoints(3, 1));
        assertEquals(37, FxPolicy.ringPoints(5, 1));
        assertEquals(FxPolicy.RING_MAX, FxPolicy.ringPoints(8, 1), "很大的圈最多 48 點");
        assertEquals(FxPolicy.RING_MAX, FxPolicy.ringPoints(30, 1));
        for (double r = 0.5; r <= 12; r += 0.25) {
            final int points = FxPolicy.ringPoints(r, 1);
            final double spacing = 2 * Math.PI * r / points;
            assertTrue(points == FxPolicy.RING_MIN || points == FxPolicy.RING_MAX || spacing >= 0.7 && spacing <= 1.0,
                    "半徑 " + r + "：大約每 0.7–1 格一點（" + spacing + "）");
            assertTrue(FxPolicy.ringPoints(r + 0.25, 1) >= points, "越大的圈點越多");
        }
    }

    @Test
    void ringPointsThinWithDensity() {
        assertEquals(0, FxPolicy.ringPoints(3, 0), "密度 0 不畫");
        assertEquals(0, FxPolicy.ringPoints(0, 1), "沒有半徑不畫");
        assertEquals(9, FxPolicy.ringPoints(3, 0.4), "四成：22 → 9");
        assertEquals(FxPolicy.RING_SPARSE_MIN, FxPolicy.ringPoints(1, 0.25), "稀疏的外框至少 4 點");
        assertEquals(12, FxPolicy.ringPoints(8, 0.25));
        assertTrue(FxPolicy.ringPoints(0.5, 0.6) <= FxPolicy.ringPoints(0.5, 1), "打薄不會比全部多");
    }

    @Test
    void areaPointsAreSparse() {
        assertEquals(1, FxPolicy.areaPoints(0.5, 1));
        assertEquals(7, FxPolicy.areaPoints(3, 1), "半徑 3：28 平方格，每 4 平方格一點");
        assertEquals(FxPolicy.AREA_MAX, FxPolicy.areaPoints(10, 1), "最多 24 點");
        assertEquals(0, FxPolicy.areaPoints(3, 0));
        assertEquals(3, FxPolicy.areaPoints(3, 0.4));
    }

    // ---- 送給誰 ----

    @Test
    void viewRangeIsHorizontalAndVertical() {
        assertTrue(FxPolicy.inView(0, 0, 0, 24));
        assertTrue(FxPolicy.inView(24, 0, 0, 24), "剛好 24 格");
        assertTrue(FxPolicy.inView(16, 20, 16, 24), "水平 22.6 格、上下 20 格：看得到（不是球形）");
        assertFalse(FxPolicy.inView(24.1, 0, 0, 24));
        assertFalse(FxPolicy.inView(17, 0, 17, 24), "水平 24.04 格");
        assertFalse(FxPolicy.inView(0, 24.5, 0, 24), "上下超過 24 格");
        assertFalse(FxPolicy.inView(0, -30, 0, 24));
        assertTrue(FxPolicy.inView(40, 0, 0, 48), "重要的送 48 格");
    }

    // ---- 特效等級 ----

    @Test
    void qualityHighShowsEverything() {
        for (final FxPolicy.Kind kind : FxPolicy.Kind.values()) {
            for (final FxPolicy.Relation relation : FxPolicy.Relation.values()) {
                assertEquals(1.0, FxPolicy.density(FxPolicy.Quality.HIGH, relation, false, kind, false), 1e-9, relation + " " + kind);
            }
        }
    }

    @Test
    void qualityMidThinsOtherPlayersOnly() {
        for (final FxPolicy.Kind kind : FxPolicy.Kind.values()) {
            assertEquals(1.0, FxPolicy.density(FxPolicy.Quality.MID, FxPolicy.Relation.SELF, false, kind, false), 1e-9, "自己的全部");
            assertEquals(0.4, FxPolicy.density(FxPolicy.Quality.MID, FxPolicy.Relation.OTHER, false, kind, false), 1e-9, "別人的四成");
            assertEquals(1.0, FxPolicy.density(FxPolicy.Quality.MID, FxPolicy.Relation.WORLD, false, kind, false), 1e-9, "怪物、環境全部");
        }
    }

    @Test
    void qualityLow() {
        final FxPolicy.Quality low = FxPolicy.Quality.LOW;
        for (final FxPolicy.Kind kind : FxPolicy.Kind.values()) {
            assertEquals(0.6, FxPolicy.density(low, FxPolicy.Relation.SELF, false, kind, false), 1e-9, "自己的六成");
            assertEquals(1.0, FxPolicy.density(low, FxPolicy.Relation.WORLD, false, kind, false), 1e-9, "環境、怪物照常");
        }
        assertEquals(0, FxPolicy.density(low, FxPolicy.Relation.OTHER, false, FxPolicy.Kind.ONE_SHOT, false), 1e-9, "別人的打擊不顯示");
        assertEquals(0, FxPolicy.density(low, FxPolicy.Relation.OTHER, false, FxPolicy.Kind.FILL, false), 1e-9, "別人的範圍裡面不顯示");
        final double outline = FxPolicy.density(low, FxPolicy.Relation.OTHER, false, FxPolicy.Kind.OUTLINE, false);
        assertTrue(outline > 0 && outline < 0.5, "別人的範圍只剩稀疏的外框");
        assertEquals(FxPolicy.RING_SPARSE_MIN, FxPolicy.ringPoints(1.5, outline));
    }

    @Test
    void importantAlwaysFull() {
        for (final FxPolicy.Quality quality : FxPolicy.Quality.values()) {
            for (final FxPolicy.Relation relation : FxPolicy.Relation.values()) {
                for (final FxPolicy.Kind kind : FxPolicy.Kind.values()) {
                    assertEquals(1.0, FxPolicy.density(quality, relation, true, kind, false), 1e-9, quality + " " + relation + " " + kind);
                    assertEquals(1.0, FxPolicy.density(quality, relation, true, kind, true), 1e-9, "關掉別人的特效也照常");
                }
            }
        }
    }

    @Test
    void hidingOthersStillShowsOwnAndWorld() {
        for (final FxPolicy.Quality quality : FxPolicy.Quality.values()) {
            assertEquals(0, FxPolicy.density(quality, FxPolicy.Relation.OTHER, false, FxPolicy.Kind.OUTLINE, true), 1e-9);
            assertTrue(FxPolicy.density(quality, FxPolicy.Relation.SELF, false, FxPolicy.Kind.ONE_SHOT, true) > 0);
            assertEquals(1.0, FxPolicy.density(quality, FxPolicy.Relation.WORLD, false, FxPolicy.Kind.ONE_SHOT, true), 1e-9);
        }
    }

    @Test
    void relationOfOwner() {
        assertEquals(FxPolicy.Relation.WORLD, FxPolicy.Relation.of(null, ME), "不知道是誰的算環境");
        assertEquals(FxPolicy.Relation.SELF, FxPolicy.Relation.of(ME, ME));
        assertEquals(FxPolicy.Relation.OTHER, FxPolicy.Relation.of(YOU, ME));
    }

    @Test
    void qualitySetting() {
        assertEquals(FxPolicy.Quality.HIGH, FxPolicy.Quality.parse(null), "沒設定過是高");
        assertEquals(FxPolicy.Quality.HIGH, FxPolicy.Quality.parse("???"));
        assertEquals(FxPolicy.Quality.MID, FxPolicy.Quality.parse("mid"));
        assertEquals(FxPolicy.Quality.LOW, FxPolicy.Quality.parse(FxPolicy.Quality.LOW.key()));
        assertEquals(FxPolicy.Quality.MID, FxPolicy.Quality.HIGH.next(), "高 → 中");
        assertEquals(FxPolicy.Quality.LOW, FxPolicy.Quality.MID.next(), "中 → 低");
        assertEquals(FxPolicy.Quality.HIGH, FxPolicy.Quality.LOW.next(), "低 → 高");
        assertEquals("高", FxPolicy.Quality.HIGH.label());
        assertEquals("中", FxPolicy.Quality.MID.label());
        assertEquals("低", FxPolicy.Quality.LOW.label());
    }

    // ---- 打薄 ----

    @Test
    void keepSendsTheRightShare() {
        int kept = 0;
        for (long i = 0; i < 1000; i++) {
            if (FxPolicy.keep(i, 0.4)) {
                kept++;
            }
        }
        assertEquals(400, kept, "四成");
        for (long i = 0; i < 50; i++) {
            assertTrue(FxPolicy.keep(i, 1));
            assertFalse(FxPolicy.keep(i, 0));
        }
    }

    @Test
    void scaledCount() {
        assertEquals(4, FxPolicy.scaledCount(10, 0.4));
        assertEquals(1, FxPolicy.scaledCount(2, 0.25), "至少 1 顆");
        assertEquals(0, FxPolicy.scaledCount(0, 0.4), "count 0（有方向的粒子）不改");
        assertEquals(1, FxPolicy.scaledCount(1, 0.4), "一顆的走 keep");
        assertEquals(12, FxPolicy.scaledCount(12, 1));
    }

    // ---- 重畫的頻率 ----

    @Test
    void throttleRedrawsAtMostEveryTenTicks() {
        final FxPolicy.Throttle throttle = new FxPolicy.Throttle(FxPolicy.REDRAW_TICKS);
        assertEquals(10, FxPolicy.REDRAW_TICKS, "0.5 秒");
        assertTrue(throttle.due(Long.MIN_VALUE, 100), "第一次馬上畫");
        long last = 100;
        int draws = 1;
        for (long now = 101; now < 200; now++) { // 呼叫的人每 tick 都叫
            if (throttle.due(last, now)) {
                assertTrue(now - last >= 10);
                last = now;
                draws++;
            }
        }
        assertEquals(10, draws, "100 tick 畫 10 次");
        assertTrue(throttle.due(500, 400), "時間倒退（重開）就畫");
    }

    @Test
    void mergeSkipsOverlappingSameTypeSameOwner() {
        final FxPolicy.Merge merge = new FxPolicy.Merge(FxPolicy.REDRAW_TICKS);
        assertTrue(merge.claim(ME, "burning", 0, 64, 0, 3, 100));
        assertFalse(merge.claim(ME, "burning", 1.0, 64, 0.5, 3, 101), "中心在半徑一半以內：不畫");
        assertTrue(merge.claim(ME, "burning", 2.0, 64, 0, 3, 101), "離 2 格（超過 1.5）：要畫");
        assertTrue(merge.claim(YOU, "burning", 0, 64, 0, 3, 102), "別人的：要畫");
        assertTrue(merge.claim(ME, "chilled", 0, 64, 0, 3, 102), "不同種：要畫");
        assertTrue(merge.claim(ME, "burning", 0, 70, 0, 3, 103), "高度差很多：要畫");
        assertTrue(merge.claim(ME, "burning", 0.5, 64, 0, 3, 111), "下一輪（10 tick 後）重新算");
        assertTrue(merge.claim(null, "patch", 0, 64, 0, 1.3, 120));
        assertFalse(merge.claim(null, "patch", 0.3, 64, 0, 1.3, 121), "環境的也合併");
    }

    @Test
    void persistentDustIsBiggerSoItLastsLonger() {
        assertEquals(1.485f, FxPolicy.longerSize(1.1f), 1e-4);
        assertEquals(1.2f, FxPolicy.longerSize(0.6f), 1e-4, "至少 1.2");
        assertEquals(2.4f, FxPolicy.longerSize(2.0f), 1e-4, "最多 2.4");
    }

    @Test
    void descriptorCopies() {
        final FxEffect fx = new FxEffect(FxEffect.Shape.RING, FxEffect.Phase.START, 7, null, 1, 2, 3, 1, 2, 3, 4, null, null, 0, 0, 0, 0, 30,
                1.0, 100, ME, false);
        final FxEffect thin = fx.withDensity(0.4);
        assertEquals(0.4, thin.density(), 1e-9);
        assertEquals(7, thin.id());
        assertEquals(FxEffect.Shape.RING, thin.shape());
        final FxEffect stop = fx.withPhase(FxEffect.Phase.STOP, 0);
        assertEquals(FxEffect.Phase.STOP, stop.phase());
        assertEquals(7, stop.id(), "同一個編號");
        assertEquals(0, stop.durationTicks());
        assertEquals(ME, stop.owner());
    }
}
