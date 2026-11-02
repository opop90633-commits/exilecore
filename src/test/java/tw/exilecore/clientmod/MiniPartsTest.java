package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 異常、詛咒文字拆成有顏色的幾段（目標框）。 */
class MiniPartsTest {

    @Test
    void colours() {
        final List<MiniParts.Part> parts = MiniParts.parse("<#ff7b3d>燃 2.5s  <#c48bff>咒·萎力 <#ffcf5a>印·狩獵");
        assertEquals(List.of(new MiniParts.Part("燃 2.5s  ", 0xff7b3d), new MiniParts.Part("咒·萎力 ", 0xc48bff),
                new MiniParts.Part("印·狩獵", 0xffcf5a)), parts);
        assertEquals("燃 2.5s  咒·萎力 印·狩獵", MiniParts.plain("<#ff7b3d>燃 2.5s  <#c48bff>咒·萎力 <#ffcf5a>印·狩獵"));
    }

    @Test
    void namedClosingAndEscapes() {
        assertEquals(List.of(new MiniParts.Part("a", 0xAAAAAA), new MiniParts.Part("b<c", 0xFFFFFF)),
                MiniParts.parse("<gray>a</gray>b\\<c"));
        assertEquals(List.of(new MiniParts.Part("粗", 0xFFFFFF)), MiniParts.parse("<bold>粗</bold>"), "不認得的標籤拿掉");
        assertTrue(MiniParts.parse("").isEmpty());
        assertTrue(MiniParts.parse(null).isEmpty());
        assertEquals(List.of(new MiniParts.Part("x", 0x123456)), MiniParts.parse("<color:#123456>x"));
        assertEquals(List.of(new MiniParts.Part("a <b", 0xFFFFFF)), MiniParts.parse("a <b"), "沒關的 < 照原樣");
    }
}
