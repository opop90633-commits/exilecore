package tw.exilecore.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tw.exilecore.client.core.HudDrag;
import tw.exilecore.client.core.MinimapData;
import tw.exilecore.client.core.ModConfig;

/**
 * 2026-11-02 照站長（C 組）：地圖裡的小地圖（右上角最上面）與大地圖（按 M 切換，畫面中間）。這次之前沒有小地圖。
 * <p>
 * 格子照伺服器進圖時送的版面（{@link MinimapData}），走過的地方才亮（迷霧）；玩家是轉著的箭頭、隊友綠點、Boss 骷髏（打倒了變灰的勾）、
 * 事件是各自顏色的菱形（完成了變暗的勾）。小地圖北方朝上，以玩家為中心；大地圖整張放得下。
 * <p>
 * 右上角的位置與大小在 ModConfig（minimapSize、minimapMarginX、minimapMarginY），也可以在設定畫面拖動（代號 {@value #ID}）。
 * B 組的地圖資訊框接在下面：{@code HudDrag.bottomOf("minimap")}（不在地圖裡、關掉時是 0）。
 */
final class MinimapRenderer {

    private MinimapRenderer() {
    }

    static final String ID = "minimap";
    /** 小地圖看得到幾格（方塊，整個邊長）。 */
    static final int VIEW = 96;
    /** 小地圖下面那一行字（探索 %）的高度。 */
    static final int LABEL = 7;

    private static DynamicTexture texture;
    private static Identifier textureId;
    private static MinimapData painted;
    private static int paintedRevision = -1;
    private static boolean paintedAll;

    static boolean shown() {
        final Minecraft mc = Minecraft.getInstance();
        return ExileCoreClient.connected && ExileCoreClient.config.showMinimap && FeedsClient.minimap != null && mc.player != null
                && !mc.gui.hud.isHidden() && FeedsClient.minimap.contains(mc.player.getX(), mc.player.getZ());
    }

    /** 小地圖的範圍（沒拖過）：右上角。不在地圖裡回傳 null（B 組的資訊框就貼著畫面頂）。 */
    static HudDrag.Rect bounds(final int width, final int height) {
        final ModConfig config = ExileCoreClient.config;
        if (!shown() && !(Minecraft.getInstance().gui.screen() instanceof HudEditScreen)) {
            return null;
        }
        final int size = config.minimapSize;
        // 原版右上角的狀態效果圖示出現時往下讓（跟地圖資訊框一樣）
        final int effects = Minecraft.getInstance().player == null ? 0 : MapPanelRenderer.effectsOffset(Minecraft.getInstance());
        return new HudDrag.Rect(width - config.minimapMarginX - size, config.minimapMarginY + effects, size, size + LABEL);
    }

    static void clear() {
        painted = null;
        paintedRevision = -1;
    }

    /** 迷霧變了才重畫那張圖（每格一個像素）。 */
    private static Identifier texture(final MinimapData data) {
        if (texture == null || painted == null || painted.width() != data.width() || painted.height() != data.height()) {
            texture = new DynamicTexture(() -> "exilecore_client minimap", data.width(), data.height(), true);
            textureId = Identifier.fromNamespaceAndPath(ExileCoreClient.MOD_ID, "minimap");
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            paintedRevision = -1;
        }
        final boolean all = ExileCoreClient.config.mapShowAll;
        if (painted != data || paintedRevision != data.revision() || paintedAll != all) {
            painted = data;
            paintedRevision = data.revision();
            paintedAll = all;
            final NativeImage image = texture.getPixels();
            if (image != null) {
                final int[] pixels = data.pixels(all); // 2026-11-02 照站長：沒走過的房間也畫（暗一點）
                for (int z = 0; z < data.height(); z++) {
                    for (int x = 0; x < data.width(); x++) {
                        image.setPixel(x, z, pixels[z * data.width() + x]);
                    }
                }
                texture.upload();
            }
        }
        return textureId;
    }

    static void extract(final GuiGraphicsExtractor g, final DeltaTracker delta) {
        if (!shown()) {
            return;
        }
        final MinimapData data = FeedsClient.minimap;
        final Font font = Minecraft.getInstance().font;
        if (FeedsClient.bigMap) {
            drawBig(g, font, data);
            return;
        }
        final HudDrag.Rect area = bounds(g.guiWidth(), g.guiHeight());
        if (area == null) {
            return;
        }
        HudDragBinding.begin(g, ID);
        drawMini(g, font, data, area);
        HudDragBinding.end(g);
    }

    private static void drawMini(final GuiGraphicsExtractor g, final Font font, final MinimapData data, final HudDrag.Rect area) {
        final LocalPlayer player = Minecraft.getInstance().player;
        final int size = area.width();
        final int x0 = area.x();
        final int y0 = area.y();
        g.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xFF5A5040);
        g.fill(x0, y0, x0 + size, y0 + size, 0xC0080808);
        final double perCell = size / (VIEW / (double) data.res()); // 一格幾個 GUI 像素
        final double pcx = data.cellX(player.getX());
        final double pcz = data.cellZ(player.getZ());
        final float cx = x0 + size / 2f;
        final float cy = y0 + size / 2f;
        final int texX = Math.round((float) (cx - pcx * perCell));
        final int texY = Math.round((float) (cy - pcz * perCell));
        g.enableScissor(x0, y0, x0 + size, y0 + size);
        g.blit(RenderPipelines.GUI_TEXTURED, texture(data), texX, texY, 0, 0, (int) Math.round(data.width() * perCell),
                (int) Math.round(data.height() * perCell), data.width(), data.height(), data.width(), data.height());
        if (ExileCoreClient.config.mapMonsters) {
            mobs(g, data, texX, texY, perCell, 1, 1f); // 2026-11-02 照站長：小地圖也標怪
        }
        icons(g, font, data, texX, texY, perCell, false);
        FeedText.arrow(g, font, cx, cy, player.getYRot() + 180, 0xFFFFE070, 0.9f); // yaw 0 是朝南（往下）
        g.disableScissor();
        final String label = "探索 " + Math.round(data.explored() * 100) + "%　[" + FeedsClient.mapKeyName() + "] 大地圖";
        FeedText.text(g, font, Component.literal(label).withStyle(s -> s.withColor(0xA0A0A0)), x0, y0 + size + 1.5f, 0.5f);
    }

    /**
     * 大地圖：疊在畫面上（照 PoE2 的地圖疊加）。2026-11-02 照站長：以你為中心（地圖跟著你動）、不畫底框，
     * 不透明度照設定（bigMapOpacity）、沒走過的房間也畫（mapShowAll）、標怪；比例照舊（整張的長邊 ≈ 畫面高）。
     * 這次之前是畫面中間一個黑框、整張圖固定不動。
     */
    private static void drawBig(final GuiGraphicsExtractor g, final Font font, final MinimapData data) {
        final LocalPlayer player = Minecraft.getInstance().player;
        final int size = Math.max(60, Math.min(g.guiWidth() - 40, g.guiHeight() - 80));
        final double perCell = size / (double) Math.max(data.width(), data.height());
        final float opacity = Math.max(0.2f, Math.min(1f, ExileCoreClient.config.bigMapOpacity / 100f));
        final float cx = g.guiWidth() / 2f;
        final float cy = g.guiHeight() / 2f;
        final int left = Math.round((float) (cx - data.cellX(player.getX()) * perCell));
        final int top = Math.round((float) (cy - data.cellZ(player.getZ()) * perCell));
        final int alpha = Math.round(opacity * 255) << 24;
        g.blit(RenderPipelines.GUI_TEXTURED, texture(data), left, top, 0, 0, (int) Math.round(data.width() * perCell),
                (int) Math.round(data.height() * perCell), data.width(), data.height(), data.width(), data.height(), alpha | 0xFFFFFF);
        if (ExileCoreClient.config.mapMonsters) {
            mobs(g, data, left, top, perCell, 2, opacity);
        }
        icons(g, font, data, left, top, perCell, true);
        FeedText.arrow(g, font, cx, cy, player.getYRot() + 180, 0xFFFFE070, 1.1f);
        // 左上角：標題與說明（不擋中間）
        final String explored = "地圖　探索 " + Math.round(data.explored() * 100) + "%";
        FeedText.text(g, font, Component.literal(explored).withStyle(s -> s.withColor(0xF5B942)), 8, 8, 0.75f);
        final String hint = "◆ 事件　☠ Boss　● 隊友　▲ 你　▪ 怪（白 普通、藍 魔法、黃 稀有）　[" + FeedsClient.mapKeyName() + "] 收起";
        FeedText.text(g, font, Component.literal(hint).withStyle(s -> s.withColor(0xB0B0B0)), 8, 16, 0.55f);
    }

    /** 2026-11-02 照站長：怪（普通白、魔法藍、稀有黃、傳奇橘）畫成小方塊；稀有、傳奇大一點、外面一圈黑邊。 */
    private static void mobs(final GuiGraphicsExtractor g, final MinimapData data, final int left, final int top, final double perCell,
                             final int dot, final float opacity) {
        final int alpha = Math.round(Math.max(0.6f, opacity) * 255) << 24;
        for (final MinimapData.Mob mob : data.mobs()) {
            final int x = (int) Math.round(left + data.cellX(mob.x() + 0.5) * perCell);
            final int y = (int) Math.round(top + data.cellZ(mob.z() + 0.5) * perCell);
            final int half = mob.rarity() >= 2 ? dot : Math.max(1, dot - 1);
            g.fill(x - half - 1, y - half - 1, x + half + 1, y + half + 1, alpha);
            g.fill(x - half, y - half, x + half, y + half, alpha | mob.color());
        }
    }

    /** 事件、Boss、隊友（世界座標換成畫面座標：left、top 是格子原點在畫面上的位置）。 */
    private static void icons(final GuiGraphicsExtractor g, final Font font, final MinimapData data, final int left, final int top, final double perCell,
                              final boolean names) {
        for (final MinimapData.Event event : data.events()) {
            final int cellX = (int) Math.floor(data.cellX(event.x()));
            final int cellZ = (int) Math.floor(data.cellZ(event.z()));
            if (!names && !data.seen(cellX, cellZ) && event.state() == 0) {
                continue; // 小地圖上沒走到的事件不畫（大地圖畫：知道哪裡有東西）
            }
            final float x = (float) (left + data.cellX(event.x()) * perCell);
            final float y = (float) (top + data.cellZ(event.z()) * perCell);
            final int color = event.done() ? 0xFF606060 : 0xFF000000 | event.color();
            glyph(g, font, event.done() ? "✔" : "◆", x, y, color, names ? 0.9f : 0.7f);
            if (names) {
                FeedText.centered(g, font, Component.literal(event.name() + (event.done() ? "（完成）" : event.state() == 1 ? "（進行中）" : ""))
                        .withStyle(s -> s.withColor(event.done() ? 0x808080 : event.color())), x, y + 4, 0.5f);
            }
        }
        final MinimapData.Boss boss = data.boss();
        if (boss != null) {
            final float x = (float) (left + data.cellX(boss.x()) * perCell);
            final float y = (float) (top + data.cellZ(boss.z()) * perCell);
            final boolean dead = "dead".equals(boss.state());
            glyph(g, font, dead ? "✔" : "☠", x, y, dead ? 0xFF808080 : "fight".equals(boss.state()) ? 0xFFFF3030 : 0xFFFF7070, names ? 1.1f : 0.85f);
            if (names) {
                FeedText.centered(g, font, Component.literal(dead ? "Boss（打倒了）" : "Boss").withStyle(s -> s.withColor(0xFF7070)), x, y + 5, 0.5f);
            }
        }
        for (final MinimapData.Mate mate : data.party()) {
            final float x = (float) (left + data.cellX(mate.x()) * perCell);
            final float y = (float) (top + data.cellZ(mate.z()) * perCell);
            glyph(g, font, "●", x, y, 0xFF55FF55, 0.7f);
            if (names) {
                FeedText.centered(g, font, Component.literal(mate.name()).withStyle(s -> s.withColor(0x88FF88)), x, y + 4, 0.5f);
            }
        }
    }

    private static void glyph(final GuiGraphicsExtractor g, final Font font, final String glyph, final float x, final float y, final int color,
                              final float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, glyph, -font.width(glyph) / 2, -4, color, true);
        g.pose().popMatrix();
    }
}
