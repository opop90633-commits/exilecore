package tw.exilecore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.client.core.MobBars;
import tw.exilecore.client.core.WorldCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：怪物頭上的小血條（exilecore:mobs，{@link MobBars}）。面向鏡頭；顏色照稀有度（稀有、傳奇、Boss
 * 比較寬）；能量護盾是生命條上一層藍白色半透明（跟生命球的罩層一樣，PoE 1 風格）＋下面一條細的護盾條；無敵（鎖血、結晶、開場、
 * 庇護光環）整條變灰、金色外框、左邊一個鎖頭；狀態用右邊的小色塊（狂暴、光環、替身、回血、詛咒、點燃、冰緩、凍結、感電）。
 * 這次之前怪物頭上只有原版名牌（看不到血量）。
 */
final class MobBarRenderer {

    /** 多遠的怪才畫（格）。 */
    static final double RANGE = 40;
    static final float HEIGHT = 0.09f;
    static final float FRAME = 0.014f;
    static final float SHIELD_HEIGHT = 0.035f;
    static final float MARK = 0.06f;

    private MobBarRenderer() {
    }

    /** 一條要畫的血條（位置是相對鏡頭的）。 */
    private record Job(Vec3 center, WorldCodec.MobBar bar, boolean aimed, float scale) {
    }

    static void render(final LevelRenderContext context) {
        if (!ExileCoreClient.connected || !ExileCoreClient.config.mobBars || WorldHudClient.MOBS.size() == 0
                || context.levelState() == null || context.levelState().cameraRenderState == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        final var cameraState = context.levelState().cameraRenderState;
        final Vec3 camera = cameraState.pos;
        final Quaternionf orientation = cameraState.orientation;
        if (level == null || camera == null || orientation == null || mc.gui.hud.isHidden()) {
            return;
        }
        final float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        final List<Job> jobs = new ArrayList<>();
        for (final WorldCodec.MobBar bar : WorldHudClient.MOBS.all()) {
            final Entity entity = level.getEntity(bar.entityId());
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isInvisible()) {
                continue;
            }
            final boolean aimed = bar.entityId() == WorldHudClient.aimed;
            if (!MobBars.shows(bar, aimed, ExileCoreClient.config.mobBarsFullLife)) {
                continue;
            }
            final Vec3 feet = living.getPosition(partial);
            if (feet.distanceToSqr(camera) > RANGE * RANGE) {
                continue;
            }
            final Vec3 top = feet.add(0, living.getBbHeight() + 0.3, 0);
            jobs.add(new Job(top.subtract(camera), bar, aimed, aimed ? 1.15f : 1f));
        }
        if (jobs.isEmpty()) {
            return;
        }
        final Vector3f right = orientation.transform(new Vector3f(1, 0, 0));
        final Vector3f up = orientation.transform(new Vector3f(0, 1, 0));
        final PoseStack poseStack = context.poseStack();
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, consumer) -> {
            for (final Job job : jobs) {
                new Quads(pose, consumer, job.center(), right, up, job.scale()).bar(job.bar(), job.aimed());
            }
        });
    }

    /** 一條血條的四邊形（u 往右、v 往上，單位是格；layer 越大越靠近鏡頭，免得疊在一起閃）。 */
    private record Quads(PoseStack.Pose pose, VertexConsumer consumer, Vec3 center, Vector3f right, Vector3f up, float scale) {

        void rect(final float u0, final float v0, final float u1, final float v1, final int layer, final int argb) {
            // 往鏡頭那邊挪一點點（鏡頭在原點）
            final double length = Math.max(1e-3, center.length());
            final float push = 0.0025f * layer;
            final float cx = (float) (center.x - center.x / length * push);
            final float cy = (float) (center.y - center.y / length * push);
            final float cz = (float) (center.z - center.z / length * push);
            final float a0 = u0 * scale;
            final float a1 = u1 * scale;
            final float b0 = v0 * scale;
            final float b1 = v1 * scale;
            final float[][] corners = {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}};
            final float[] xs = new float[4];
            final float[] ys = new float[4];
            final float[] zs = new float[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = cx + right.x * corners[i][0] + up.x * corners[i][1];
                ys[i] = cy + right.y * corners[i][0] + up.y * corners[i][1];
                zs[i] = cz + right.z * corners[i][0] + up.z * corners[i][1];
            }
            for (int i = 0; i < 4; i++) { // 正面
                consumer.addVertex(pose, xs[i], ys[i], zs[i]).setColor(argb);
            }
            for (int i = 3; i >= 0; i--) { // 背面（不知道有沒有剔除背面，兩面都畫）
                consumer.addVertex(pose, xs[i], ys[i], zs[i]).setColor(argb);
            }
        }

        void bar(final WorldCodec.MobBar bar, final boolean aimed) {
            final float w = MobBars.width(bar);
            final float h = HEIGHT * (bar.boss() ? 1.3f : 1f);
            final float left = -w / 2;
            final float bottom = -h / 2;
            final boolean shield = bar.shield() >= 0;
            final float shieldBottom = shield ? bottom - SHIELD_HEIGHT - FRAME : bottom;
            // 外框（準心指著的白色、無敵金色、照稀有度）
            final int frame = aimed && !bar.invulnerable() ? 0xFFF0F0F0 : MobBars.frameColor(bar);
            final float f = bar.invulnerable() || aimed ? FRAME * 1.6f : FRAME;
            rect(left - f, shieldBottom - f, -left + f, -bottom + f, 0, frame);
            // 底
            rect(left, bottom, -left, -bottom, 1, 0xF0200808);
            // 生命
            final float life = (float) (w * bar.lifeFraction());
            rect(left, bottom, left + life, -bottom, 2, MobBars.lifeColor(bar));
            rect(left, -bottom - h * 0.25f, left + life, -bottom, 3, 0x40FFFFFF); // 上緣亮一點
            if (shield) {
                // 能量護盾：生命條上一層藍白色半透明（照比例）＋下面一條細的
                final float es = (float) (w * bar.shieldFraction());
                rect(left, bottom, left + es, -bottom, 4, 0x70BFE6FF);
                rect(left, shieldBottom, -left, bottom - FRAME, 1, 0xF00A1830);
                rect(left, shieldBottom, left + es, bottom - FRAME, 2, 0xFF9FD8FF);
            }
            if (bar.invulnerable()) {
                lock(left - f - 0.11f, (shieldBottom - bottom) / 2);
            }
            marks(bar, -left + f + 0.04f, -bottom);
        }

        /** 鎖頭（金色）：鎖身＋上面的弧（三條）。 */
        void lock(final float cx, final float cy) {
            final int gold = 0xFFFFD040;
            rect(cx - 0.05f, cy - 0.05f, cx + 0.05f, cy + 0.02f, 2, gold);
            rect(cx - 0.035f, cy + 0.02f, cx - 0.02f, cy + 0.07f, 2, gold);
            rect(cx + 0.02f, cy + 0.02f, cx + 0.035f, cy + 0.07f, 2, gold);
            rect(cx - 0.035f, cy + 0.055f, cx + 0.035f, cy + 0.07f, 2, gold);
            rect(cx - 0.01f, cy - 0.03f, cx + 0.01f, cy, 3, 0xFF6A4A00); // 鑰匙孔
        }

        /** 狀態的小色塊（右邊，一排最多 5 個）。 */
        void marks(final WorldCodec.MobBar bar, final float x0, final float top) {
            int n = 0;
            for (int bit = 0; bit < MobBars.MARK_COLORS.length; bit++) {
                if (!bar.has(1 << bit)) {
                    continue;
                }
                final float x = x0 + (n % 5) * (MARK + 0.015f);
                final float y = top - (n / 5) * (MARK + 0.015f);
                rect(x - 0.008f, y - MARK - 0.008f, x + MARK + 0.008f, y + 0.008f, 0, 0xC0000000);
                rect(x, y - MARK, x + MARK, y, 1, MobBars.MARK_COLORS[bit]);
                n++;
            }
        }
    }
}
