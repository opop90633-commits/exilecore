package tw.exilecore.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.OrbLayout;
import tw.exilecore.client.core.RingArt;
import tw.exilecore.client.core.StatusIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：生命球旁邊的東西——自己身上的異常、詛咒（左球正上方的小圖示：紅框、詛咒紫框，上面寫字、下面倒數）、
 * 入場保護（綠框）、守護（疊在生命球框上的一圈金色的環，長度照守護 ÷ 最大生命，球頂寫守護量）。
 * 這次之前都寫在動作列的字裡（伺服器照招呼的 status 不寫了）。
 */
final class StatusRenderer {

    private StatusRenderer() {
    }

    private static final int FRAME = 0xFFC03030;
    private static final int CURSE_FRAME = 0xFF9A5AE0;
    private static final int SAFE_FRAME = 0xFF4FC89A;

    private static DynamicTexture ring;
    private static Identifier ringId;
    private static int ringPixels;
    private static double ringFraction = -1;

    static void extract(final GuiGraphicsExtractor g, final Font font, final HudData data, final double elapsed) {
        final HudData.Self self = data.self();
        final int size = OrbRenderer.size();
        final List<StatusIcons.Icon> icons = new ArrayList<>();
        for (final StatusIcons.Icon icon : StatusIcons.of(self.status())) {
            if (!icon.expired(elapsed)) {
                icons.add(icon);
            }
        }
        if (self.safe()) {
            icons.add(new StatusIcons.Icon("保護", 0, "", -1, 0x9FE8C8, false));
        }
        if (!icons.isEmpty()) {
            icons(g, font, icons, size, elapsed, self.safe());
        }
        if (self.guard() > 0 && data.vitals() != null) {
            guard(g, font, self.guard(), data.vitals().maxLife(), size);
        }
    }

    private static void icons(final GuiGraphicsExtractor g, final Font font, final List<StatusIcons.Icon> icons, final int size,
                              final double elapsed, final boolean safe) {
        final int perRow = HudLayout.iconsPerRow(size);
        final int slots = perRow * HudLayout.ICON_ROWS; // 2026-11-02：只在左球正上方（左邊是增益欄）
        final HudLayout.Box box = HudLayout.statusIcons(g.guiWidth(), g.guiHeight(), size, Math.min(icons.size(), slots));
        for (int i = 0; i < Math.min(icons.size(), slots); i++) {
            final int column = i % perRow;
            final int row = i / perRow;
            final int x = box.right() - (column + 1) * HudLayout.ICON_STEP + 2;
            final int y = box.bottom() - (row + 1) * HudLayout.ICON_STEP + 2;
            if (i == slots - 1 && icons.size() > slots) {
                final String more = "+" + (icons.size() - slots + 1);
                g.fill(x, y, x + HudLayout.ICON, y + HudLayout.ICON, 0xC0101010);
                g.outline(x, y, HudLayout.ICON, HudLayout.ICON, 0xFF606060);
                small(g, font, more, x + HudLayout.ICON / 2f, y + 4.5f, 0xFFFFFFFF, 0.6f, HudLayout.ICON - 2);
                break;
            }
            icon(g, font, icons.get(i), x, y, elapsed, safe && i == icons.size() - 1);
        }
    }

    private static void icon(final GuiGraphicsExtractor g, final Font font, final StatusIcons.Icon icon, final int x, final int y,
                             final double elapsed, final boolean safe) {
        final int s = HudLayout.ICON;
        final int color = 0xFF000000 | icon.color();
        g.fill(x, y, x + s, y + s, 0xD0140808);
        g.fill(x + 1, y + 1, x + s - 1, y + s - 1, 0x30000000 | icon.color()); // 淡淡的底色（照異常的顏色）
        g.outline(x, y, s, s, safe ? SAFE_FRAME : icon.curse() ? CURSE_FRAME : FRAME);
        final String value = icon.text(elapsed);
        small(g, font, icon.label(), x + s / 2f, y + (value.isEmpty() ? 4f : 1.5f), color, icon.label().length() > 1 ? 0.5f : 0.75f, s - 2);
        if (!value.isEmpty()) {
            small(g, font, value, x + s / 2f, y + s - 5.5f, 0xFFFFFFFF, 0.5f, s);
        }
        if (icon.stacks() > 1) {
            final String stacks = Integer.toString(icon.stacks());
            g.pose().pushMatrix();
            g.pose().translate(x + s - 0.5f - font.width(stacks) * 0.5f, y + 0.5f);
            g.pose().scale(0.5f, 0.5f);
            g.text(font, stacks, 0, 0, 0xFFFFFF55, true);
            g.pose().popMatrix();
        }
    }

    /** 守護環：疊在生命球框上（不多佔位置）；球頂寫守護量。 */
    private static void guard(final GuiGraphicsExtractor g, final Font font, final long guard, final long maxLife, final int size) {
        final Minecraft mc = Minecraft.getInstance();
        final int pixels = Math.max(16, Math.min(256, size * Math.max(1, mc.getWindow().getGuiScale())));
        final double fraction = Math.round(RingArt.fraction(guard, maxLife) * 100) / 100.0;
        if (ring == null || ringPixels != pixels) {
            ringPixels = pixels;
            ring = new DynamicTexture(() -> "exilecore_client guard ring", pixels, pixels, true);
            ringId = Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "orb/guard");
            mc.getTextureManager().register(ringId, ring);
            ringFraction = -1;
        }
        if (fraction != ringFraction) {
            ringFraction = fraction;
            final int[] argb = RingArt.ring(pixels, fraction);
            final NativeImage image = ring.getPixels();
            if (image != null) {
                for (int y = 0; y < pixels; y++) {
                    for (int x = 0; x < pixels; x++) {
                        image.setPixel(x, y, argb[y * pixels + x]);
                    }
                }
                ring.upload();
            }
        }
        final int left = OrbLayout.leftX(g.guiWidth(), size);
        final int top = OrbLayout.top(g.guiHeight(), size);
        g.blit(RenderPipelines.GUI_TEXTURED, ringId, left, top, 0, 0, size, size, pixels, pixels, pixels, pixels);
        // 守護量寫在球正上方（異常圖示下面那 5 格，HudLayout.guardText）；寫在球裡會跟能量護盾的數字疊在一起
        final HudLayout.Box text = HudLayout.guardText(g.guiWidth(), g.guiHeight(), size);
        small(g, font, "+" + HudRenderer.grouped(guard) + " 守護", text.x() + text.w() / 2f, text.y() + 0.5f, RingArt.GOLD_LIGHT, 0.5f, size + 8);
    }

    /** 置中的小字（太寬再縮小）。 */
    static void small(final GuiGraphicsExtractor g, final Font font, final String text, final float cx, final float y, final int color,
                      final float scale, final float maxWidth) {
        final int width = font.width(text);
        final float k = width <= 0 ? scale : Math.min(scale, maxWidth / width);
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(k, k);
        g.text(font, text, -width / 2, 0, color, true);
        g.pose().popMatrix();
    }
}
