package tw.exilecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import tw.exilecore.client.core.FloatingNumbers;
import tw.exilecore.client.core.FxCodec;
import tw.exilecore.client.core.ModConfig;

import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：畫傷害數字（exilecore:fx 的 NUMBER，{@link FloatingNumbers}）。
 * 打出的：在目標頭上往上飄（像原版名牌那樣面向鏡頭、跟著距離變小）、暴擊放大、淡出；受到的：設定 takenNumbers＝edge 畫在準心左下
 * （畫面上），world 畫在自己眼前（世界裡）。這次之前是伺服器生的文字顯示實體。
 */
final class DamageNumberRenderer {

    static final FloatingNumbers NUMBERS = new FloatingNumbers();
    /** 世界裡的字：一個 GUI 像素幾格（原版名牌是 0.025）。 */
    static final float WORLD_SCALE = 0.03f;
    static final int FULL_BRIGHT = 0xF000F0;

    private DamageNumberRenderer() {
    }

    static void clear() {
        NUMBERS.clear();
    }

    static void tick() {
        NUMBERS.tick();
    }

    /** 從特效封包讀到的數字（{@link FxRenderer#onPacket}）。 */
    static void accept(final List<FxCodec.Number> numbers) {
        final ModConfig config = ExileCoreClient.config;
        for (final FxCodec.Number n : numbers) {
            if (n.self() && "off".equals(config.takenNumbers)) {
                continue;
            }
            NUMBERS.add(n, config.mergeDamageNumbers);
        }
    }

    private static int argb(final int rgb, final float alpha) {
        final int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return a << 24 | rgb & 0xFFFFFF;
    }

    // ---- 世界裡 ----

    static void render(final LevelRenderContext context) {
        if (NUMBERS.size() == 0 || context.levelState() == null || context.levelState().cameraRenderState == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final var cameraState = context.levelState().cameraRenderState;
        final Vec3 camera = cameraState.pos;
        if (camera == null || cameraState.orientation == null) {
            return;
        }
        final float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        final boolean worldSelf = "world".equals(ExileCoreClient.config.takenNumbers);
        final float userScale = ExileCoreClient.config.damageNumberScale;
        final Font font = mc.font;
        final PoseStack pose = context.poseStack();
        for (final FloatingNumbers.Floater f : NUMBERS.floaters()) {
            if (f.self && !worldSelf) {
                continue;
            }
            final double progress = f.progress(partial);
            final float alpha = FloatingNumbers.alpha(progress);
            if (alpha <= 0.02f) {
                continue;
            }
            final double rise = FloatingNumbers.rise(progress) * (f.self ? 0.5 : FloatingNumbers.RISE);
            final double x = f.x + f.jitterX * (f.self ? 0.9 : 1);
            final double y = f.y + rise + (f.self ? -0.35 : 0);
            final double z = f.z + f.jitterZ * (f.self ? 0 : 1);
            if ((x - camera.x) * (x - camera.x) + (z - camera.z) * (z - camera.z) > 64 * 64) {
                continue;
            }
            final String label = f.label();
            final FormattedCharSequence text = Component.literal(label).getVisualOrderText();
            final float scale = WORLD_SCALE * FloatingNumbers.scale(f, partial) * userScale;
            pose.pushPose();
            pose.translate(x - camera.x, y - camera.y, z - camera.z);
            pose.mulPose(cameraState.orientation);
            pose.scale(scale, -scale, scale);
            context.submitNodeCollector().submitText(pose, -font.width(text) / 2f, 0, text, true, Font.DisplayMode.NORMAL, FULL_BRIGHT,
                    argb(f.rgb, alpha), 0, 0);
            pose.popPose();
        }
    }

    // ---- 畫面上（受到的，takenNumbers = edge） ----

    static void extractHud(final GuiGraphicsExtractor g, final DeltaTracker delta) {
        final ModConfig config = ExileCoreClient.config;
        if (NUMBERS.size() == 0 || !"edge".equals(config.takenNumbers) || Minecraft.getInstance().gui.hud.isHidden()) {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        final float partial = delta.getGameTimeDeltaPartialTick(false);
        // 準心左下：從準心往左 50、往下 14 開始往上飄 26 像素（不擋準心、不擋右邊的目標）
        final float baseX = g.guiWidth() / 2f - 50;
        final float baseY = g.guiHeight() / 2f + 14;
        for (final FloatingNumbers.Floater f : NUMBERS.floaters()) {
            if (!f.self) {
                continue;
            }
            final double progress = f.progress(partial);
            final float alpha = FloatingNumbers.alpha(progress);
            if (alpha <= 0.02f) {
                continue;
            }
            final float scale = FloatingNumbers.scale(f, partial) * 0.75f * config.damageNumberScale;
            final String label = f.label();
            g.pose().pushMatrix();
            g.pose().translate(baseX + f.jitterX * 24, baseY - (float) (FloatingNumbers.rise(progress) * 26));
            g.pose().scale(scale, scale);
            g.text(font, label, -font.width(label), 0, argb(f.rgb, alpha), true);
            g.pose().popMatrix();
        }
    }
}
