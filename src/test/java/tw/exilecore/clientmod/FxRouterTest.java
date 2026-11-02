package tw.exilecore.clientmod;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import tw.exilecore.skill.FxEffect;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 哪些人的特效改由模組畫：裝了 1.1 模組（effects）vs 1.0 模組 vs 沒裝；超過上限改回粒子、重要的不會不見；範圍的生命週期。 */
class FxRouterTest {

    private static final UUID MODDED = UUID.randomUUID();
    private static final UUID OLD_MOD = UUID.randomUUID();
    private static final UUID VANILLA = UUID.randomUUID();
    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);

    private static FxEffect once(final FxEffect.Shape shape, final int count, final double density, final boolean important) {
        return new FxEffect(shape, FxEffect.Phase.ONCE, 0, null, 10.5, 64, -3.25, 10.5, 64, -3.25, shape == FxEffect.Shape.RING ? 3 : 0,
                Particle.DUST, RED, 0, 0, 0, 1.0, count, density, 0, null, important);
    }

    private static FxEffect zone(final long id, final FxEffect.Phase phase, final double radius, final boolean important) {
        return new FxEffect(FxEffect.Shape.AREA, phase, id, null, 0, 64, 0, 0, 64, 0, radius, Particle.FLAME, null, 0, 0.5, 0, 0.01, 30,
                1.0, 200, null, important);
    }

    /** 跟 ClientModService 一樣：招呼的協定版本與 effects 決定能不能接手。 */
    private static FxRouter router() {
        final FxRouter router = new FxRouter();
        final ClientModProtocol.Hello modern = ClientModProtocol.decodeHello(ClientModProtocol.encodeHello(
                new ClientModProtocol.Hello(2, "1.1.0", true, true, true)));
        final ClientModProtocol.Hello old = ClientModProtocol.decodeHello(
                "{\"protocol\":1,\"modVersion\":\"1.0.0\",\"hud\":true,\"hearts\":true,\"effects\":true}".getBytes());
        router.setCapable(MODDED, ClientModProtocol.negotiate(modern.protocol()) >= 2 && modern.effects());
        router.setCapable(OLD_MOD, ClientModProtocol.negotiate(old.protocol()) >= 2 && old.effects());
        return router;
    }

    @Test
    void decisionByViewer() {
        final FxRouter router = router();
        final FxEffect burst = once(FxEffect.Shape.BURST, 10, 1, false);
        assertTrue(router.route(MODDED, burst), "1.1 模組、effects:true：模組畫");
        assertFalse(router.route(OLD_MOD, burst), "1.0 模組（協定 1，effects 不算數）：照常收粒子");
        assertFalse(router.route(VANILLA, burst), "沒裝模組：照常收粒子");
        assertFalse(router.route(null, burst));
        final List<Map.Entry<UUID, byte[]>> sent = router.drain();
        assertEquals(1, sent.size());
        assertEquals(MODDED, sent.getFirst().getKey());
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(sent.getFirst().getValue());
        assertEquals(1, decoded.size());
        assertEquals("dust", decoded.getFirst().style().particle());
        assertEquals(0xff0000, decoded.getFirst().style().a());
        assertEquals(10, decoded.getFirst().entry().count());
        assertTrue(router.drain().isEmpty(), "送完清空");
    }

    @Test
    void densityAppliedBeforeSending() {
        final FxRouter router = router();
        router.route(MODDED, once(FxEffect.Shape.BURST, 20, 0.25, false));
        router.route(MODDED, once(FxEffect.Shape.RING, 0, 0.5, false));
        for (int i = 0; i < 100; i++) {
            assertTrue(router.route(MODDED, once(FxEffect.Shape.POINT, 1, 0.3, false)), "打薄掉的點也算接手（不送粒子）");
        }
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(router.drain().getFirst().getValue());
        assertEquals(5, decoded.get(0).entry().count(), "20 顆 × 0.25");
        assertEquals(tw.exilecore.skill.FxPolicy.ringPoints(3, 0.5), decoded.get(1).entry().count());
        assertEquals(30, decoded.size() - 2, "一顆一顆的點照密度留三成");
    }

    @Test
    void overflowFallsBackButImportantSurvives() {
        final FxRouter router = router();
        router.maxBytes(FxRouter.MIN_MAX_BYTES);
        int routed = 0;
        int fallback = 0;
        for (int i = 0; i < 500; i++) {
            if (router.route(MODDED, once(FxEffect.Shape.BURST, 5, 1, false))) {
                routed++;
            } else {
                fallback++;
            }
        }
        assertTrue(routed > 0 && fallback > 0, "超過上限的改回送粒子");
        assertEquals(fallback, router.overflowThisTick(MODDED));
        // 一般的已經滿了，重要的還有額外的空間
        int important = 0;
        for (int i = 0; i < 20; i++) {
            if (router.route(MODDED, once(FxEffect.Shape.BURST, 5, 1, true))) {
                important++;
            }
        }
        assertEquals(20, important, "重要的照樣由模組畫");
        // 重要的也滿了：改回送粒子（return false = Fx 照常送粒子，不會不見）
        boolean refused = false;
        for (int i = 0; i < 2000 && !refused; i++) {
            refused = !router.route(MODDED, once(FxEffect.Shape.BURST, 5, 1, true));
        }
        assertTrue(refused);
        final byte[] bytes = router.drain().getFirst().getValue();
        assertTrue(bytes.length <= FxRouter.MIN_MAX_BYTES * FxRouter.IMPORTANT_FACTOR);
        assertNotNull(FxProtocol.decode(bytes));
        assertTrue(router.route(MODDED, once(FxEffect.Shape.BURST, 5, 1, false)), "下一個 tick 重新算");
    }

    @Test
    void zoneLifecycle() {
        final FxRouter router = router();
        assertTrue(router.route(MODDED, zone(7, FxEffect.Phase.START, 3, false)));
        assertFalse(router.route(OLD_MOD, zone(7, FxEffect.Phase.START, 3, false)));
        assertEquals(1, router.zones(MODDED));
        assertTrue(router.route(MODDED, zone(7, FxEffect.Phase.UPDATE, 4, false)));
        assertFalse(router.route(MODDED, zone(8, FxEffect.Phase.UPDATE, 4, false)), "沒收過 START 的 UPDATE 不送");
        assertFalse(router.route(OLD_MOD, zone(7, FxEffect.Phase.STOP, 4, false)));
        // 模組把特效關掉：新的不接手，但收過的照樣送完
        router.setCapable(MODDED, false);
        assertFalse(router.route(MODDED, zone(9, FxEffect.Phase.START, 3, false)));
        assertFalse(router.route(MODDED, once(FxEffect.Shape.BURST, 3, 1, false)));
        assertTrue(router.route(MODDED, zone(7, FxEffect.Phase.UPDATE, 5, false)));
        assertTrue(router.route(MODDED, zone(7, FxEffect.Phase.STOP, 5, false)));
        assertFalse(router.route(MODDED, zone(7, FxEffect.Phase.STOP, 5, false)), "STOP 只送一次");
        assertEquals(0, router.zones(MODDED));
        final List<FxProtocol.Decoded> decoded = FxProtocol.decode(router.drain().getFirst().getValue());
        assertEquals(4, decoded.size());
        assertEquals(FxProtocol.PHASE_START, decoded.get(0).entry().phase());
        assertEquals(200, decoded.get(0).entry().duration());
        assertEquals(0.5, decoded.get(0).entry().dy(), 1e-6, "AREA 裡面的點多高");
        assertEquals(FxProtocol.PHASE_UPDATE, decoded.get(1).entry().phase());
        assertEquals(4, decoded.get(1).entry().radius(), 0.02);
        assertEquals(FxProtocol.PHASE_STOP, decoded.get(3).entry().phase());
        assertEquals(7, decoded.get(3).entry().id());
        assertTrue(router.drain().isEmpty());
        assertFalse(router.capable(MODDED));
    }

    @Test
    void updatesAndStopsIgnoreTheCap() {
        final FxRouter router = router();
        router.maxBytes(FxRouter.MIN_MAX_BYTES);
        for (int id = 1; id <= 3; id++) {
            assertTrue(router.route(MODDED, zone(id, FxEffect.Phase.START, 3, false)));
        }
        router.drain();
        while (router.route(MODDED, once(FxEffect.Shape.BURST, 5, 1, true))) {
            // 塞滿（連重要的額度也用完）
        }
        assertFalse(router.route(MODDED, zone(50, FxEffect.Phase.START, 3, true)), "新的範圍滿了：先用粒子，下次重畫再問");
        for (int id = 1; id <= 3; id++) {
            assertTrue(router.route(MODDED, zone(id, FxEffect.Phase.UPDATE, 6, false)));
            assertTrue(router.route(MODDED, zone(id, FxEffect.Phase.STOP, 6, false)));
        }
        assertEquals(0, router.zones(MODDED));
    }

    @Test
    void worldChangeForgetsZones() {
        final FxRouter router = router();
        router.route(MODDED, zone(1, FxEffect.Phase.START, 3, false));
        router.worldChanged(MODDED);
        assertTrue(router.drain().isEmpty(), "舊世界還沒送的不送");
        assertFalse(router.route(MODDED, zone(1, FxEffect.Phase.STOP, 3, false)));
        assertTrue(router.capable(MODDED));
        router.remove(MODDED);
        assertFalse(router.capable(MODDED));
    }

    @Test
    void styles() {
        assertEquals(FxProtocol.Style.none("flame"), FxRouter.style(Particle.FLAME, null));
        assertEquals(FxProtocol.Style.none("flame"), FxRouter.style(Particle.FLAME, "多餘的資料不管"));
        assertEquals(FxProtocol.Style.dust("dust", 0xff0000, 1.2f), FxRouter.style(Particle.DUST, RED));
        assertNull(FxRouter.style(Particle.DUST, null), "DUST 沒有顏色：伺服器送也會錯，照原本的走");
        final Particle.DustTransition transition = new Particle.DustTransition(Color.fromRGB(1, 2, 3), Color.fromRGB(4, 5, 6), 0.5f);
        assertEquals(FxProtocol.Style.transition("dust_color_transition", 0x010203, 0x040506, 0.5f),
                FxRouter.style(Particle.DUST_COLOR_TRANSITION, transition));
        assertEquals(FxProtocol.DATA_DUST, FxRouter.style(Particle.DUST, transition).kind());
        assertEquals(FxProtocol.Style.color("entity_effect", Color.fromARGB(128, 255, 0, 0).asARGB()),
                FxRouter.style(Particle.ENTITY_EFFECT, Color.fromARGB(128, 255, 0, 0)));
        assertEquals(FxProtocol.DATA_FLOAT, FxRouter.style(Particle.SCULK_CHARGE, 0.5f).kind());
        assertEquals(FxProtocol.DATA_INT, FxRouter.style(Particle.SHRIEK, 5).kind());
        assertNull(FxRouter.style(null, null));
        // 模組不會畫的資料種類（震動）：改回送粒子
        assertNull(FxRouter.style(Particle.VIBRATION, null));
        // 資料跟粒子對不上的 ONCE：不接手
        final FxRouter router = router();
        final FxEffect wrong = new FxEffect(FxEffect.Shape.POINT, FxEffect.Phase.ONCE, 0, null, 0, 0, 0, 0, 0, 0, 0, Particle.DUST, null, 0,
                0, 0, 0, 1, 1, 0, null, false);
        assertFalse(router.route(MODDED, wrong));
    }
}
