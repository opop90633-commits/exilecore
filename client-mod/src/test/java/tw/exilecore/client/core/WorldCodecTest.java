package tw.exilecore.client.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（1.4 B 組）：跟插件 WorldHudProtocol、FxProtocol 的格式對得上。 */
class WorldCodecTest {

    /** 插件 FxProtocol.Writer 寫出來的：兩個數字（暴擊 1234 打到實體 42；自己閃避）＋一顆火焰粒子。 */
    static final String FX_FROM_PLUGIN = "01148001070206e99683e981bf05666c616d65010100030615004000200060d209ff80402a060a004000800080aaaaaa0000000000800000008003";
    /** 插件 WorldHudProtocol.encodeMobs：一隻傳奇 Boss（無敵、護盾、詛咒＋感電），拿掉 5。 */
    static final String MOBS_FROM_PLUGIN = "010001e0a7123f50c890020105";

    @Test
    void helloFeatures() {
        final byte[] hello = Protocol.hello(2, "1.4.0", true, true, true, true);
        final var root = JsonParser.parseString(new String(WorldCodec.withFeatures(hello, true, false, true), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertTrue(root.get("mapbox").getAsBoolean());
        assertFalse(root.has("dmgnum"), "關掉的不寫");
        assertTrue(root.get("mobbars").getAsBoolean());
        assertEquals(2, root.get("protocol").getAsInt());
        assertSame(hello, WorldCodec.withFeatures(hello, false, false, false));
    }

    @Test
    void numbersFromPlugin() {
        final List<FxCodec.Number> numbers = new ArrayList<>();
        final List<FxCodec.Effect> effects = FxCodec.decode(HexFormat.of().parseHex(FX_FROM_PLUGIN), numbers);
        assertNotNull(effects);
        assertEquals(1, effects.size(), "粒子照舊");
        assertEquals(2, numbers.size());
        final FxCodec.Number hit = numbers.getFirst();
        assertEquals(1234, hit.value());
        assertEquals(0xFF8040, hit.rgb());
        assertTrue(hit.emphasis());
        assertEquals(42, hit.target());
        assertEquals(64.25, hit.y(), 0.01);
        final FxCodec.Number dodge = numbers.get(1);
        assertEquals(-1, dodge.value());
        assertEquals("閃避", dodge.text());
        assertTrue(dodge.self());
        // 只要特效的照舊讀得到（數字略過）
        assertEquals(1, FxCodec.decode(HexFormat.of().parseHex(FX_FROM_PLUGIN)).size());
    }

    @Test
    void mobsFromPlugin() {
        final WorldCodec.Mobs mobs = WorldCodec.parseMobs(HexFormat.of().parseHex(MOBS_FROM_PLUGIN));
        assertNotNull(mobs);
        final WorldCodec.MobBar bar = mobs.bars().getFirst();
        assertEquals(300000, bar.entityId());
        assertEquals(80, bar.life());
        assertEquals(200, bar.shield());
        assertEquals(WorldCodec.RARITY_UNIQUE, bar.rarity());
        assertTrue(bar.boss());
        assertTrue(bar.invulnerable());
        assertTrue(bar.has(WorldCodec.MARK_CURSED));
        assertTrue(bar.has(WorldCodec.MARK_SHOCK));
        assertFalse(bar.has(WorldCodec.MARK_IGNITE));
        assertEquals(List.of(5), mobs.removed());
        assertEquals(MOBS_FROM_PLUGIN, HexFormat.of().formatHex(WorldCodec.encodeMobs(false, mobs.bars(), mobs.removed())));
        assertNull(WorldCodec.parseMobs(new byte[] {2}));
        assertNull(WorldCodec.parseMobs(new byte[] {1, 0, 5}));
    }

    @Test
    void mapPanel() {
        final String json = "{\"v\":1,\"name\":\"沉船灣 T7\",\"color\":10474495,\"tier\":7,\"level\":70,\"mods\":[[[\"怪物生命增加 30%\",16777079]]],"
                + "\"portals\":5,\"unit\":\"次\",\"explored\":42,\"cleared\":30,\"boss\":{\"s\":1,\"name\":\"潮汐統帥\",\"life\":62},"
                + "\"events\":[{\"n\":\"裂隙\",\"c\":12880895,\"s\":\"還有 12 秒\",\"p\":40,\"st\":1}],\"future\":123}";
        final WorldCodec.MapPanel panel = WorldCodec.parseMap(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(panel);
        assertEquals("沉船灣 T7", panel.name());
        assertEquals(0xFFFF77, panel.mods().getFirst().getFirst().color());
        assertEquals(WorldCodec.BOSS_FIGHTING, panel.boss());
        assertEquals(62, panel.bossLife());
        assertEquals("還有 12 秒", panel.events().getFirst().status());
        assertSame(WorldCodec.CLEAR, WorldCodec.parseMap("{\"v\":1,\"clear\":true}".getBytes(StandardCharsets.UTF_8)));
        assertNull(WorldCodec.parseMap("壞".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aim() {
        assertEquals("0100", HexFormat.of().formatHex(WorldCodec.aim(-1)));
        assertEquals("0101", HexFormat.of().formatHex(WorldCodec.aim(0)));
        assertEquals(8, HexFormat.of().formatHex(WorldCodec.aim(300000)).length(), "版本 1 個位元組＋varint 3 個");
    }
}
