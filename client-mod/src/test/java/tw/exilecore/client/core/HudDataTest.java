package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 讀伺服器的畫面資料、自己倒數。 */
class HudDataTest {

    /** 插件 HudSnapshotTest 產生的那份（格式要對得上）。 */
    static final String JSON = "{\"v\":1,\"t\":1000,\"slots\":[{\"s\":0,\"id\":\"fireball\",\"n\":\"火球\",\"i\":\"minecraft:book\","
            + "\"m\":\"currency:skill_fireball\",\"b\":1,\"cd\":40,\"ct\":60,\"mc\":12,\"p\":\"冷卻\"},"
            + "{\"s\":4,\"id\":\"war_cry\",\"n\":\"戰吼\",\"i\":\"minecraft:goat_horn\",\"b\":1,\"u\":2,\"mu\":3,\"p\":\"魔力不足\"}],"
            + "\"buffs\":[{\"n\":\"疾速\",\"l\":100,\"t\":200,\"s\":-1}],"
            + "\"target\":{\"n\":\"狂暴蠻兵\",\"c\":16777079,\"r\":\"稀有\",\"lv\":30,\"hp\":500,\"mhp\":1000,\"sh\":40,\"boss\":0,"
            + "\"st\":[{\"t\":\"燃 2.5s\",\"c\":16743229}]},"
            + "\"vit\":{\"hp\":800,\"mhp\":1000,\"rl\":200,\"es\":300,\"mes\":400,\"mp\":50,\"mmp\":120,\"ms\":0.4}}";

    @Test
    void parse() {
        final HudData data = HudData.parse(JSON.getBytes(StandardCharsets.UTF_8));
        assertEquals(1000, data.serverTick());
        assertEquals(2, data.slots().size());
        final HudData.Slot fire = data.slots().get(0);
        assertEquals("currency:skill_fireball", fire.model());
        assertEquals(40, fire.cooldownLeft(0));
        assertEquals(20, fire.cooldownLeft(20));
        assertEquals(40 / 60.0, fire.cooldownFraction(0), 1e-9);
        assertFalse(fire.castable(0), "冷卻中");
        assertTrue(fire.castable(40), "冷卻好了就能放");
        final HudData.Slot cry = data.slots().get(1);
        assertNull(cry.model());
        assertEquals(2, cry.uses(0));
        assertFalse(cry.castable(1000), "魔力不足不會自己好");
        assertEquals(0.5, data.buffs().get(0).fraction(0), 1e-9);
        assertEquals(0, data.buffs().get(0).left(150));
        assertEquals(0.5, data.target().lifeFraction(), 1e-9);
        assertEquals(40, data.target().shieldPercent());
        assertEquals(0xff7b3d, data.target().status().get(0).color());
        assertEquals(200, data.vitals().reservedLife());
        assertEquals(0.4, data.vitals().manaShare(), 1e-9);
        assertNull(HudData.parse("{".getBytes(StandardCharsets.UTF_8)));
        final HudData empty = HudData.parse("{\"v\":1,\"t\":5,\"slots\":[],\"buffs\":[]}".getBytes(StandardCharsets.UTF_8));
        assertNull(empty.target());
        assertNull(empty.vitals());
    }

    @Test
    void seconds() {
        assertEquals("12", HudData.seconds(230));
        assertEquals("10", HudData.seconds(200));
        assertEquals("3.4", HudData.seconds(68));
        assertEquals("0.1", HudData.seconds(1));
        assertEquals("2m", HudData.duration(20 * 61));
        assertEquals("5.0s", HudData.duration(100));
    }
}
