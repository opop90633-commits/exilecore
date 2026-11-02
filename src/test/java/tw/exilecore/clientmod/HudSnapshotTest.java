package tw.exilecore.clientmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 送給模組的畫面資料：JSON 格式、倒數不算變化、魔力承受比例。 */
class HudSnapshotTest {

    private static HudSnapshot snapshot(final long cooldownEnd, final double life) {
        return new HudSnapshot(
                List.of(new HudSnapshot.Slot(0, "fireball", "火球", "minecraft:book", "currency:skill_fireball", true, cooldownEnd, 60, 0, 1, 12, null),
                        new HudSnapshot.Slot(4, "war_cry", "戰吼", "minecraft:goat_horn", null, true, 0, 0, 2, 3, 0, "魔力不足")),
                List.of(new HudSnapshot.Buff("疾速", 1100, 200, 0, -1)),
                new HudSnapshot.Target("狂暴蠻兵", 0xffff77, "稀有", 30, 500, 1000, 40, false,
                        List.of(new MiniParts.Part("燃 2.5s", 0xff7b3d))),
                new HudSnapshot.Vitals(life, 1000, 200, 300, 400, 50, 120, 0.4));
    }

    @Test
    void json() {
        final JsonObject root = JsonParser.parseString(snapshot(1040, 800).toJson(1000)).getAsJsonObject();
        assertEquals(1, root.get("v").getAsInt());
        assertEquals(1000, root.get("t").getAsLong());
        final JsonObject first = root.getAsJsonArray("slots").get(0).getAsJsonObject();
        assertEquals(40, first.get("cd").getAsLong(), "冷卻剩 40 tick");
        assertEquals(60, first.get("ct").getAsLong());
        assertEquals("currency:skill_fireball", first.get("m").getAsString());
        assertEquals(12, first.get("mc").getAsInt());
        assertFalse(first.has("u"), "只能放一次的不寫次數");
        final JsonObject second = root.getAsJsonArray("slots").get(1).getAsJsonObject();
        assertFalse(second.has("cd"), "沒在冷卻");
        assertEquals(2, second.get("u").getAsInt());
        assertEquals(3, second.get("mu").getAsInt());
        assertEquals("魔力不足", second.get("p").getAsString());
        final JsonObject buff = root.getAsJsonArray("buffs").get(0).getAsJsonObject();
        assertEquals(100, buff.get("l").getAsLong());
        assertEquals(200, buff.get("t").getAsLong());
        final JsonObject target = root.getAsJsonObject("target");
        assertEquals(0xffff77, target.get("c").getAsInt());
        assertEquals(40, target.get("sh").getAsInt());
        assertEquals("燃 2.5s", target.getAsJsonArray("st").get(0).getAsJsonObject().get("t").getAsString());
        final JsonObject vit = root.getAsJsonObject("vit");
        assertEquals(800, vit.get("hp").getAsLong());
        assertEquals(200, vit.get("rl").getAsLong());
        assertEquals(300, vit.get("es").getAsLong());
        assertEquals(0.4, vit.get("ms").getAsDouble(), 1e-9);
        assertTrue(snapshot(1040, 800).toJson(1000).length() < 1024, "封包很小");
        assertFalse(vit.has("rmp"), "2026-11-02：沒有保留魔力不寫");
        final HudSnapshot reserved = new HudSnapshot(List.of(), List.of(), null, new HudSnapshot.Vitals(1, 1, 0, 0, 0, 50, 100, 0, 25));
        assertEquals(25, JsonParser.parseString(reserved.toJson(0)).getAsJsonObject().getAsJsonObject("vit").get("rmp").getAsLong());
    }

    @Test
    void signatureIgnoresCountdown() {
        assertEquals(snapshot(1040, 800).signature(), snapshot(1040, 800).signature());
        assertEquals(snapshot(1040, 800).toJson(1000).replace("\"t\":1000,", ""), snapshot(1040, 800).toJson(1000).replace("\"t\":1000,", ""));
        assertNotEquals(snapshot(1040, 800).toJson(1000), snapshot(1040, 800).toJson(1003), "倒數會變");
        assertNotEquals(snapshot(1040, 800).signature(), snapshot(1100, 800).signature(), "重新放了");
        assertNotEquals(snapshot(1040, 800).signature(), snapshot(1040, 799).signature(), "血量變了");
        final String noTarget = new HudSnapshot(List.of(), List.of(), null, null).toJson(5);
        assertEquals("{\"v\":1,\"t\":5,\"slots\":[],\"buffs\":[]}", noTarget);
    }

    @Test
    void manaShare() {
        assertEquals(0, HudSnapshot.manaShare(0, 0, false, 100), 1e-9);
        assertEquals(0.4, HudSnapshot.manaShare(40, 0, false, 100), 1e-9);
        assertEquals(1, HudSnapshot.manaShare(150, 0, false, 100), 1e-9, "最多全部");
        assertEquals(0.25, HudSnapshot.manaShare(0, 50, false, 100), 1e-9, "元素那部分當一半");
        assertEquals(1, HudSnapshot.manaShare(10, 0, true, 100), 1e-9, "心宇和諧");
        assertEquals(0, HudSnapshot.manaShare(40, 0, true, 0), 1e-9, "沒魔力扣不到");
    }

    @Test
    void joinStatus() {
        assertEquals("a  b", ClientModService.join("a", "b"));
        assertEquals("b", ClientModService.join("", "b"));
        assertEquals("a", ClientModService.join("a", null));
    }
}
