package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：光環那一行搬到畫面左下角（著色器的 Java 版）、字型的 ascent。 */
class AuraShaderTest {

    @Test
    void movesToLeftCorner() {
        // GUI 640 寬：原本右緣在 320 − 164 = 156、寬 100 → 左緣 56；搬到 3
        final int color = 50 << 16 | PartyFont.AURA_MARK << 8 | PartyFont.AURA_NAME;
        final double[] moved = PartyFont.auraMove(color, 56, 640);
        assertEquals(3, moved[0], 1e-9);
        assertEquals(0xffffff, (int) moved[1], "換回白色");
        final double[] icon = PartyFont.auraMove(50 << 16 | PartyFont.AURA_MARK << 8 | PartyFont.AURA_ICON, 60, 640);
        assertEquals(0x8fd3ff, (int) icon[1]);
        assertEquals(7, icon[0], 1e-9, "同一行的字一起搬");
    }

    @Test
    void staysWhenNoRoom() {
        // GUI 480 寬：右緣在 76、寬 100 → 左緣 −24，往左搬不了：不動
        final int color = 50 << 16 | PartyFont.AURA_MARK << 8 | PartyFont.AURA_NAME;
        assertEquals(-24, PartyFont.auraMove(color, -24, 480)[0], 1e-9);
    }

    @Test
    void otherTextUntouched() {
        assertNull(PartyFont.auraMove(0xffffff, 10, 640));
        assertNull(PartyFont.auraMove(0x8fd3ff, 10, 640));
    }

    @Test
    void shaderHasAuraMove() {
        assertTrue(PartyFont.VSH_88.contains("ecAuraMove(position, color)"));
        assertTrue(PartyFont.VSH_97.contains("ecAuraMove(position, color)"));
        assertTrue(PartyFont.VSH_88.contains("c.g == 209"));
    }

    @Test
    void rowAscent() {
        assertEquals(-50, HudFont.rowAscent(15), "快捷欄那一排（原本的 ASCENT）");
        assertEquals(-54, HudFont.rowAscent(HudFont.AURA_TOP));
        assertEquals(-29, HudFont.rowAscent(36));
    }

    // ---- 2026-11-02 照站長（第三版）：增益、召喚物搬到右下角 ----

    @Test
    void cornerCopyMovesToRightEdge() {
        // GUI 480 寬：右下角那塊推到 240 + 12000 − 60（寬 60 的那格）→ 搬到 480 − 3 − 60
        final double[] moved = PartyFont.cornerMove(0xf5b942, 240 + PartyFont.CORNER_SHIFT - 60, 250, 480, 270);
        assertEquals(480 - 3 - 60, moved[0], 1e-9);
        assertEquals(0, moved[1], 1e-9, "顏色照舊（真正的顏色）");
        final double[] wide = PartyFont.cornerMove(0xffffff, 480 + PartyFont.CORNER_SHIFT - 60, 500, 960, 540);
        assertEquals(960 - 3 - 60, wide[0], 1e-9, "GUI 縮放 2 也貼著右邊");
    }

    @Test
    void fallbackCopyHiddenOnlyNearBottom() {
        final int marked = PartyFont.fallbackColor(0xf5b942);
        assertEquals(1, PartyFont.cornerMove(marked, 60, 250, 480, 270)[1], 1e-9);
        assertEquals(1, PartyFont.cornerMove(0x010203, 60, 250, 480, 270)[1], 1e-9, "備用那份的影子");
        assertNull(PartyFont.cornerMove(marked, 60, 50, 480, 270), "畫面上面的不管");
        assertNull(PartyFont.cornerMove(0xf5b942, 60, 250, 480, 270), "真正的顏色（聊天、別的字）不管");
        assertNull(PartyFont.cornerMove(0xffffff, 60, 250, 480, 270));
    }

    @Test
    void partyFrameIgnoresCornerCopy() {
        assertTrue(PartyFont.VSH_88.contains("ecBuffMove(position, color)"));
        assertTrue(PartyFont.VSH_97.contains("ecBuffMove(position, color)"));
        assertTrue(PartyFont.VSH_88.contains("Position.x > guiW * 0.5 + " + 10000 + ".0"), "隊伍框不搬右下角那塊");
        assertTrue(PartyFont.CORNER_SEEN > PartyFont.PRE_SHIFT + 1000, "跟隊伍框分得開");
        assertTrue(PartyFont.VSH_88.contains(String.valueOf(PartyFont.fallbackColor(0xffffff))));
    }
}
