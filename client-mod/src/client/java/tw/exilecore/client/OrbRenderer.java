package tw.exilecore.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.OrbArt;
import tw.exilecore.client.core.OrbLayout;

/**
 * 2026-11-02 照站長：PoE 風格的生命球（快捷欄左邊）、魔力球（右邊），取代原版的愛心與飢餓條（設定 {@code orbs}，F9 切回原本的生命條）。
 * <p>
 * 每一幀用 {@link OrbArt} 畫成一張圖（解析度 = 球的 GUI 大小 × GUI 縮放，看起來是平滑的圓）再貼上去：
 * 液面往伺服器給的值平滑地靠過去、有波紋與浮動的光；剛被打掉的那段淡淡的留一下再退掉（PoE 的「剛受到的傷害」）；
 * 保留的生命是球頂的灰色。數字照原版字型縮小寫在球中間。
 * <p>
 * 1.3（2026-11-02 照站長改成 PoE 1）：能量護盾是疊在紅色生命上面一層半透明、有光澤的藍白色液面（高度照能量護盾比例；1.2 是外環）；
 * 混沌免疫（最大生命 1）生命球整顆是能量護盾、數字寫能量護盾；心靈昇華（傷害先扣魔力）在魔力球的框上畫一段紫紅色、框下緣寫比例，
 * 生命球的框上也點一個紫紅色的小點；保留的魔力跟生命一樣是球頂的暗色。
 * 靈能電池（我們是 PoE2 的版本：能量護盾全部變成最大魔力）已經算進最大魔力，沒有另外一層。
 */
final class OrbRenderer {

    private OrbRenderer() {
    }

    private static final Orb LIFE = new Orb("life");
    private static final Orb MANA = new Orb("mana");
    private static long lastNanos;
    private static double phase;

    /** 液面往目標靠近的速度（每秒）；剛被打掉的那段退得慢一點。 */
    private static final double RATE = 9;
    private static final double TRAIL_RATE = 2.2;

    /** 一顆球：平滑後的值與它的圖。 */
    private static final class Orb {
        final String name;
        double level = -1;
        double trail;
        double shield;
        DynamicTexture texture;
        Identifier id;
        int pixels;

        Orb(final String name) {
            this.name = name;
        }

        void update(final double target, final double shieldTarget, final double dt) {
            if (level < 0) {
                level = target;
                trail = target;
                shield = shieldTarget;
                return;
            }
            level = OrbArt.approach(level, target, dt, RATE);
            trail = trail < level ? level : OrbArt.approach(trail, level, dt, TRAIL_RATE);
            shield = OrbArt.approach(shield, shieldTarget, dt, RATE);
        }

        /** 畫好這一幀的圖（大小換了重新建）。 */
        Identifier paint(final int size, final OrbArt.Liquid kind, final double reserved, final double mom) {
            if (texture == null || pixels != size) {
                pixels = size; // 舊的那張：register 換掉時 TextureManager 會關掉
                texture = new DynamicTexture(() -> "exilecore_client orb " + name, size, size, true);
                id = Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "orb/" + name);
                Minecraft.getInstance().getTextureManager().register(id, texture);
            }
            final int[] argb = OrbArt.compose(size, kind, level, trail, shield, reserved, mom, phase);
            final NativeImage image = texture.getPixels();
            if (image == null) {
                return id;
            }
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    image.setPixel(x, y, argb[y * size + x]);
                }
            }
            texture.upload();
            return id;
        }
    }

    /** 換伺服器、斷線：下次從現在的值開始（不要從上一場的血量滑過來）。 */
    static void reset() {
        LIFE.level = -1;
        MANA.level = -1;
    }

    /** 球多大（GUI 像素），HudRenderer 用來把技能列、增益列推到球的外側。 */
    static int size() {
        return OrbLayout.size(ExileCoreClient.config.scale);
    }

    static void extract(final GuiGraphicsExtractor g, final HudData.Vitals v) {
        final long now = System.nanoTime();
        final double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        phase = (phase + dt * 2.4) % (Math.PI * 200);
        final Minecraft mc = Minecraft.getInstance();
        final Font font = mc.font;
        final int size = size();
        final int pixels = Math.max(OrbArt.BOX, Math.min(256, size * Math.max(1, mc.getWindow().getGuiScale())));
        final int top = OrbLayout.top(g.guiHeight(), size);

        final double maxLife = Math.max(1, v.maxLife());
        final boolean ci = v.chaosInoculation();
        final double es = v.maxEnergyShield() > 0 ? clamp(v.energyShield() / (double) v.maxEnergyShield()) : 0;
        if (ci) {
            LIFE.update(es, 0, dt); // 混沌免疫：整顆是能量護盾
        } else {
            LIFE.update(clamp(v.life() / maxLife), es, dt);
        }
        final double maxMana = Math.max(1, v.maxMana());
        MANA.update(clamp(v.mana() / maxMana), 0, dt);
        final double mom = clamp(v.manaShare());

        final int leftX = OrbLayout.leftX(g.guiWidth(), size);
        g.blit(RenderPipelines.GUI_TEXTURED, LIFE.paint(pixels, ci ? OrbArt.Liquid.SHIELD : OrbArt.Liquid.LIFE,
                ci ? 0 : clamp(v.reservedLife() / maxLife), 0), leftX, top, 0, 0, size, size, pixels, pixels, pixels, pixels);
        final int rightX = OrbLayout.rightX(g.guiWidth());
        g.blit(RenderPipelines.GUI_TEXTURED, MANA.paint(pixels, OrbArt.Liquid.MANA, clamp(v.reservedMana() / maxMana), mom),
                rightX, top, 0, 0, size, size, pixels, pixels, pixels, pixels);

        // 數字：能量護盾（青）、目前（白）、最大（灰）
        // 2026-11-02 照站長：字放大（目前 0.62 → 0.8、最大與護盾 0.5 → 0.65，可用寬度也放寬）；這次之前字太小
        final float k = size / (float) OrbArt.BOX;
        final float cxLeft = leftX + size / 2f;
        if (ci) {
            line(g, font, HudRenderer.grouped(v.energyShield()), cxLeft, top + 11.5f * k, 0xFFFFFFFF, BIG * k, 29 * k);
            line(g, font, "/" + HudRenderer.grouped(v.maxEnergyShield()), cxLeft, top + 19.5f * k, 0xFFC0C0C8, SMALL * k, 27 * k);
        } else if (v.maxEnergyShield() > 0) {
            line(g, font, HudRenderer.grouped(v.energyShield()) + "/" + HudRenderer.grouped(v.maxEnergyShield()), cxLeft, top + 7.5f * k,
                    SHIELD_TEXT, SMALL * k, 24 * k); // 最上面那行球比較窄
            line(g, font, HudRenderer.grouped(v.life()), cxLeft, top + 14f * k, 0xFFFFFFFF, BIG * k, 29 * k);
            line(g, font, "/" + HudRenderer.grouped(v.maxLife()), cxLeft, top + 21.5f * k, 0xFFC0C0C8, SMALL * k, 27 * k);
        } else {
            line(g, font, HudRenderer.grouped(v.life()), cxLeft, top + 11.5f * k, 0xFFFFFFFF, BIG * k, 29 * k);
            line(g, font, "/" + HudRenderer.grouped(v.maxLife()), cxLeft, top + 19.5f * k, 0xFFC0C0C8, SMALL * k, 27 * k);
        }
        final float cxRight = rightX + size / 2f;
        line(g, font, HudRenderer.grouped(v.mana()), cxRight, top + 11.5f * k, 0xFFFFFFFF, BIG * k, 29 * k);
        line(g, font, "/" + HudRenderer.grouped(v.maxMana()), cxRight, top + 19.5f * k, 0xFFC0C0C8, SMALL * k, 27 * k);
        if (mom > 0) {
            // 心靈昇華：魔力球框下緣寫比例；生命球框的右下點一個紫紅色的小點（傷害有一部分不扣生命）
            line(g, font, Math.max(1, Math.round(mom * 100)) + "%", cxRight, top + 29.5f * k, MOM_TEXT, 0.5f * k, 18 * k);
            final int dot = Math.max(2, Math.round(2 * k));
            final int dx = leftX + Math.round(size * 0.82f);
            final int dy = top + Math.round(size * 0.82f);
            g.fill(dx - dot, dy - dot, dx + dot, dy + dot, 0xFF2A0626);
            g.fill(dx - dot + 1, dy - dot + 1, dx + dot - 1, dy + dot - 1, MOM_TEXT);
        }
    }

    private static final int SHIELD_TEXT = 0xFFA8E4FF;
    /** 2026-11-02：球裡的字：目前的數字、最大值與護盾那一行（乘球的縮放 k）。 */
    private static final float BIG = 0.8f;
    private static final float SMALL = 0.65f;
    private static final int MOM_TEXT = 0xFFFFC8F4;

    /** 置中的一行小字（太寬就再縮小，最多 maxWidth 寬）。 */
    private static void line(final GuiGraphicsExtractor g, final Font font, final String text, final float cx, final float y, final int color,
                             final float scale, final float maxWidth) {
        final int width = font.width(text);
        final float s = width <= 0 ? scale : Math.min(scale, maxWidth / width);
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(s, s);
        g.text(font, text, -width / 2, 0, color, true);
        g.pose().popMatrix();
    }

    private static double clamp(final double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}
