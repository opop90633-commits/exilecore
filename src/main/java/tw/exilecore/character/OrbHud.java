package tw.exilecore.character;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import tw.exilecore.web.HudFont;
import tw.exilecore.web.OrbArt;
import tw.exilecore.web.OrbFont;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長：PoE 風格的生命球、魔力球（套了資源包、沒有用星骸輔助模組畫生命的人；字型見 {@link OrbFont}）。
 * 接在動作列最後面（淨寬 0）：左球貼著快捷欄左邊（副手格外側）、右球貼著右邊（攻擊冷卻指示外側），高度不超過聊天欄最下面那行。
 * 這次之前是原版愛心＋藍色吸收愛心＋經驗等級，數字寫在動作列。純函式（測試用）。
 */
public final class OrbHud {

    private static final Key HUD_FONT = Key.key(HudFont.FONT);
    private static final Key ORB_FONT = Key.key(OrbFont.FONT);
    static final int WHITE = 0xFFFFFF;
    static final int GRAY = 0xB4B4BC;
    static final int SHIELD = 0x9FE0FF;

    private OrbHud() {
    }

    /** 一個字：x 是左緣（相對畫面中間，GUI 像素）、往前走 advance、顏色。 */
    public record Placed(char glyph, int x, int advance, int color) {
    }

    /**
     * 這個人的生命、護盾、魔力（跟動作列同一份）。manaShare = 受到的傷害先扣魔力的比例（心靈昇華，0～1）；
     * reservedMana = 保留的魔力（2026-11-02 第二版）。
     */
    public record Vitals(double life, double maxLife, double reservedLife, double energyShield, double maxEnergyShield, double mana,
                         double maxMana, double manaShare, double reservedMana) {

        public Vitals(final double life, final double maxLife, final double reservedLife, final double energyShield,
                      final double maxEnergyShield, final double mana, final double maxMana) {
            this(life, maxLife, reservedLife, energyShield, maxEnergyShield, mana, maxMana, 0, 0);
        }

        /** 混沌免疫（最大生命 1、有能量護盾）：生命球整顆是能量護盾。 */
        public boolean chaosInoculation() {
            return maxLife <= 1 && maxEnergyShield > 0;
        }
    }

    static final int MOM_COLOR = 0xFFC8F4;

    /**
     * 左球格子的左緣、右球格子的左緣（相對畫面中間）。慣用手是左手時副手格在快捷欄右邊（91～120）、攻擊冷卻指示在左邊，
     * 兩邊都留 {@value OrbFont#INNER} 格，所以換手不用動。
     */
    static int leftBox() {
        return -OrbFont.OUTER;
    }

    static int rightBox() {
        return OrbFont.INNER;
    }

    /**
     * 排好的字（照畫的順序）。2026-11-02 第二版照站長改成 PoE 1：
     * <ul>
     *   <li>生命球：紅色液體、能量護盾罩層（半透明藍白，高度照能量護盾比例）、保留的灰色、框、數字（能量護盾、生命、最大生命）；</li>
     *   <li>混沌免疫：生命球整顆是能量護盾的液體，數字寫能量護盾；</li>
     *   <li>魔力球：藍色液體、保留的暗色、框、心靈昇華的紫紅色標記（框上從正下方長、比例寫在框的下緣）、數字。</li>
     * </ul>
     * 這次之前能量護盾是框外面的外環、沒有混沌免疫與心靈昇華的樣子。
     */
    public static List<Placed> layout(final Vitals v) {
        final List<Placed> out = new ArrayList<>();
        final int left = leftBox();
        final double maxLife = Math.max(1, v.maxLife());
        final boolean shield = v.maxEnergyShield() > 0;
        final double esFraction = shield ? v.energyShield() / v.maxEnergyShield() : 0;
        if (v.chaosInoculation()) {
            out.add(orb(OrbFont.shieldLiquid(OrbArt.segment(esFraction, OrbFont.LEVELS)), left));
            out.add(orb(OrbFont.frame(), left));
            text(out, left, 1, OrbFont.compact(Math.round(v.energyShield())), WHITE); // 藍白的液體上，白字比青色清楚
            text(out, left, 3, "/" + OrbFont.compact(Math.round(v.maxEnergyShield())), GRAY);
        } else {
            out.add(orb(OrbFont.life(OrbArt.segment(v.life() / maxLife, OrbFont.LEVELS)), left));
            final int es = OrbArt.segment(esFraction, OrbFont.LEVELS);
            if (es > 0) {
                out.add(orb(OrbFont.shield(es), left));
            }
            final int reserved = OrbArt.segment(v.reservedLife() / maxLife, OrbFont.LEVELS);
            if (reserved > 0) {
                out.add(orb(OrbFont.reserved(reserved), left));
            }
            out.add(orb(OrbFont.frame(), left));
            if (shield) {
                text(out, left, 0, OrbFont.compact(Math.round(v.energyShield())), SHIELD);
                text(out, left, 2, OrbFont.compact(Math.round(v.life())), WHITE);
                text(out, left, 4, "/" + OrbFont.compact(Math.round(v.maxLife())), GRAY);
            } else {
                text(out, left, 1, OrbFont.compact(Math.round(v.life())), WHITE);
                text(out, left, 3, "/" + OrbFont.compact(Math.round(v.maxLife())), GRAY);
            }
        }
        final int right = rightBox();
        final double maxMana = Math.max(1, v.maxMana());
        out.add(orb(OrbFont.mana(OrbArt.segment(v.mana() / maxMana, OrbFont.LEVELS)), right));
        final int reservedMana = OrbArt.segment(v.reservedMana() / maxMana, OrbFont.LEVELS);
        if (reservedMana > 0) {
            out.add(orb(OrbFont.reserved(reservedMana), right));
        }
        out.add(orb(OrbFont.frame(), right));
        final int mom = OrbArt.segment(v.manaShare(), OrbFont.LEVELS);
        if (mom > 0) {
            out.add(orb(OrbFont.mom(mom), right));
        }
        text(out, right, 1, OrbFont.compact(Math.round(v.mana())), WHITE);
        text(out, right, 3, "/" + OrbFont.compact(Math.round(v.maxMana())), GRAY);
        if (mom > 0) {
            text(out, right, 5, momPercent(v.manaShare()), MOM_COLOR);
        }
        return out;
    }

    /** 心靈昇華的比例（「40%」）；有一點就至少 1%。 */
    static String momPercent(final double share) {
        final long percent = Math.max(1, Math.min(100, Math.round(share * 100)));
        return percent + "%";
    }

    private static Placed orb(final char glyph, final int x) {
        return new Placed(glyph, x, OrbFont.ORB_ADVANCE, WHITE);
    }

    /** 一行數字，在球格子裡置中。 */
    private static void text(final List<Placed> out, final int box, final int line, final String text, final int color) {
        int x = box + (OrbArt.BOX - OrbFont.width(text) + 1) / 2;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final char glyph = OrbFont.digit(line, c);
            if (glyph != 0) {
                out.add(new Placed(glyph, x, OrbFont.DIGIT_ADVANCE, color));
            }
            x += OrbFont.step(c);
        }
    }

    /**
     * 組成動作列的一段（淨寬 0）：start 是這段開始時游標在哪（相對畫面中間；接在置中的主要那段後面 = 那段寬度的一半）。
     */
    public static Component render(final List<Placed> glyphs, final int start) {
        final TextComponent.Builder root = Component.text().shadowColor(ShadowColor.none());
        int cursor = start;
        for (final Placed placed : glyphs) {
            if (placed.x() != cursor) {
                root.append(Component.text(HudFont.shift(placed.x() - cursor)).font(HUD_FONT));
            }
            root.append(Component.text(String.valueOf(placed.glyph()), TextColor.color(placed.color())).font(ORB_FONT));
            cursor = placed.x() + placed.advance();
        }
        if (cursor != start) {
            root.append(Component.text(HudFont.shift(start - cursor)).font(HUD_FONT));
        }
        return root.build();
    }
}
