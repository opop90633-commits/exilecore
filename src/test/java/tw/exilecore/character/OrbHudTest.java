package tw.exilecore.character;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import tw.exilecore.web.OrbArt;
import tw.exilecore.web.OrbFont;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02：生命球、魔力球（資源包）的排字。 */
class OrbHudTest {

    @Test
    void orbsFlankTheHotbarOutsideOffhandAndAttackIndicator() {
        final int left = OrbHud.leftBox();
        final int right = OrbHud.rightBox();
        assertTrue(left + OrbArt.BOX <= -91 - 29, "左球在副手格外側");
        assertTrue(right >= 91 + 6 + 18, "右球在攻擊冷卻指示外側");
        assertTrue(right >= 91 + 29, "左手慣用時副手格在右邊也不會被蓋住");
        assertEquals(-(left + OrbArt.BOX), right, "左右對稱");
        assertTrue(OrbFont.TOP < 40, "不超過聊天欄最下面那行");
        // 快捷欄兩側的字從球外側開始
        assertTrue(HotbarSides.leftEdge(true) >= OrbFont.OUTER);
        assertTrue(HotbarSides.rightStart(true) >= OrbFont.OUTER);
        assertEquals(124, HotbarSides.leftEdge(false), "沒有球照舊");
        assertEquals(97, HotbarSides.rightStart(false));
        assertTrue(HotbarSides.lootStart(List.of(), false, true) >= OrbFont.OUTER);
        assertEquals(HotbarSides.LOOT_START, HotbarSides.lootStart(List.of(), false, false));
    }

    @Test
    void layoutPicksSegmentsAndLayers() {
        final List<OrbHud.Placed> glyphs = OrbHud.layout(new OrbHud.Vitals(600, 1000, 200, 150, 300, 40, 160));
        assertEquals(OrbFont.life(12), glyphs.get(0).glyph(), "生命 60%：第 12 段");
        assertEquals(OrbFont.shield(10), glyphs.get(1).glyph(), "能量護盾罩層：一半");
        assertEquals(OrbFont.reserved(4), glyphs.get(2).glyph(), "保留 20%");
        assertEquals(OrbFont.frame(), glyphs.get(3).glyph());
        assertTrue(glyphs.contains(new OrbHud.Placed(OrbFont.mana(5), OrbHud.rightBox(), OrbFont.ORB_ADVANCE, OrbHud.WHITE)), "魔力 25%");
        // 數字都在自己的球格子裡
        for (final OrbHud.Placed placed : glyphs) {
            final int box = placed.x() < 0 ? OrbHud.leftBox() : OrbHud.rightBox();
            assertTrue(placed.x() >= box && placed.x() < box + OrbArt.BOX, placed.toString());
        }
        final long shieldDigits = glyphs.stream().filter(p -> p.color() == OrbHud.SHIELD).count();
        assertEquals(3, shieldDigits, "護盾「150」");
        final List<OrbHud.Placed> noShield = OrbHud.layout(new OrbHud.Vitals(1000, 1000, 0, 0, 0, 0, 100));
        assertEquals(OrbFont.life(20), noShield.get(0).glyph());
        assertEquals(OrbFont.frame(), noShield.get(1).glyph(), "沒有保留就沒有灰色那層");
        assertFalse(noShield.stream().anyMatch(p -> p.color() == OrbHud.SHIELD));
        assertTrue(noShield.contains(new OrbHud.Placed(OrbFont.mana(0), OrbHud.rightBox(), OrbFont.ORB_ADVANCE, OrbHud.WHITE)), "沒魔力是空的");
    }

    @Test
    void chaosInoculationShowsEnergyShieldAsTheWholeOrb() {
        final OrbHud.Vitals ci = new OrbHud.Vitals(1, 1, 0, 3000, 4000, 50, 100);
        assertTrue(ci.chaosInoculation());
        final List<OrbHud.Placed> glyphs = OrbHud.layout(ci);
        assertEquals(OrbFont.shieldLiquid(15), glyphs.get(0).glyph(), "75% 的能量護盾液體");
        assertEquals(OrbFont.frame(), glyphs.get(1).glyph(), "沒有紅色、沒有罩層");
        assertFalse(glyphs.stream().anyMatch(p -> p.glyph() == OrbFont.life(1) || p.glyph() == OrbFont.life(0)));
        assertEquals("3,000", digits(glyphs.stream().filter(p -> p.x() < 0).toList(), OrbHud.WHITE), "數字寫能量護盾");
        assertFalse(new OrbHud.Vitals(1, 1, 0, 0, 0, 0, 0).chaosInoculation(), "沒有能量護盾不算");
        assertFalse(new OrbHud.Vitals(500, 500, 0, 100, 100, 0, 0).chaosInoculation());
    }

    @Test
    void mindOverMatterMarksTheManaOrb() {
        final List<OrbHud.Placed> glyphs = OrbHud.layout(new OrbHud.Vitals(500, 500, 0, 0, 0, 80, 100, 0.4, 0));
        assertTrue(glyphs.contains(new OrbHud.Placed(OrbFont.mom(8), OrbHud.rightBox(), OrbFont.ORB_ADVANCE, OrbHud.WHITE)), "40%：第 8 段");
        assertEquals("40%", digits(glyphs, OrbHud.MOM_COLOR));
        assertEquals("1%", OrbHud.momPercent(0.001));
        final List<OrbHud.Placed> none = OrbHud.layout(new OrbHud.Vitals(500, 500, 0, 0, 0, 80, 100, 0, 0));
        assertFalse(none.stream().anyMatch(p -> p.color() == OrbHud.MOM_COLOR));
        // 保留的魔力：魔力球也有暗色那段
        final List<OrbHud.Placed> reserved = OrbHud.layout(new OrbHud.Vitals(500, 500, 0, 0, 0, 50, 100, 0, 25));
        assertTrue(reserved.contains(new OrbHud.Placed(OrbFont.reserved(5), OrbHud.rightBox(), OrbFont.ORB_ADVANCE, OrbHud.WHITE)));
    }

    @Test
    void xpBarShowsCharacterProgressWithOrbs() {
        assertEquals(0.42, PlayerStateService.xpBar(true, 0.42), 1e-9);
        assertEquals(0.999, PlayerStateService.xpBar(true, 1.5), 1e-9, "不會跳下一級");
        assertEquals(0.999, PlayerStateService.xpBar(false, 0), 1e-9, "滿級整條");
        assertEquals(0, PlayerStateService.xpBar(true, Double.NaN), 1e-9);
    }

    /** 某個顏色的字照 x 排好、換回原本的字。 */
    private static String digits(final List<OrbHud.Placed> glyphs, final int color) {
        final StringBuilder out = new StringBuilder();
        glyphs.stream().filter(p -> p.color() == color && p.advance() == OrbFont.DIGIT_ADVANCE)
                .sorted(java.util.Comparator.comparingInt(OrbHud.Placed::x)).forEach(p -> {
                    final int index = (p.glyph() - OrbFont.DIGITS) % 16;
                    out.append(OrbFont.DIGIT_SET.charAt(index));
                });
        return out.toString();
    }

    @Test
    void textIsCentredInTheOrb() {
        final List<OrbHud.Placed> glyphs = OrbHud.layout(new OrbHud.Vitals(5, 5, 0, 0, 0, 5, 5));
        final List<OrbHud.Placed> current = glyphs.stream().filter(p -> p.color() == OrbHud.WHITE && p.advance() == OrbFont.DIGIT_ADVANCE
                && p.x() < 0).toList();
        assertEquals(1, current.size());
        final int center = OrbHud.leftBox() + OrbArt.BOX / 2;
        assertTrue(Math.abs(current.get(0).x() + 2 - center) <= 1, "「5」在球中間：" + current.get(0).x());
    }

    @Test
    void renderedSegmentHasNoShadowAndEveryGlyph() {
        final List<OrbHud.Placed> glyphs = OrbHud.layout(new OrbHud.Vitals(800, 1000, 0, 0, 0, 50, 100));
        final Component component = OrbHud.render(glyphs, 37);
        assertTrue(component.shadowColor() != null, "不畫陰影");
        int count = 0;
        for (final Component child : component.children()) {
            if (child.font() != null && child.font().asString().equals(OrbFont.FONT)) {
                count += ((TextComponent) child).content().length();
            }
        }
        assertEquals(glyphs.size(), count);
    }
}
