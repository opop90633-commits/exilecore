package tw.exilecore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import tw.exilecore.client.core.DeathData;
import tw.exilecore.client.core.HudData;

import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：死亡回顧面板。伺服器送 exilecore:death（插件整理好的那幾行），模組畫在死亡畫面的右邊；
 * 重生以後按鍵（預設 J）再叫出來（{@link Screen}，Esc 或再按一次關掉）。這次之前印在聊天欄（現在沒裝模組的人照舊）。
 */
final class DeathPanel {

    private DeathPanel() {
    }

    static final float SCALE = 0.7f;

    /** 死亡畫面打開時接上：畫面畫完之後在右邊畫面板。 */
    static void attach(final Screen screen) {
        if (!(screen instanceof DeathScreen)) {
            return;
        }
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
            final DeathData data = FeedsClient.lastDeath;
            if (data != null && ExileCoreClient.connected && ExileCoreClient.config.showDeathPanel && FeedsClient.deathFresh) {
                final int width = Math.min(230, g.guiWidth() / 2 - 110);
                if (width >= 120) {
                    draw(g, Minecraft.getInstance().font, data, g.guiWidth() - width - 8, 24, width);
                } else {
                    draw(g, Minecraft.getInstance().font, data, 8, 8, Math.min(230, g.guiWidth() - 16)); // 很窄：放上面
                }
            }
        });
    }

    /** 面板要多高。 */
    static int height(final Font font, final DeathData data, final int width) {
        int height = 16;
        for (final List<HudData.Part> line : data.body()) {
            height += FeedText.wrappedHeight(font, FeedText.component(line), width - 10, SCALE) + 1;
        }
        return height + 12;
    }

    static void draw(final GuiGraphicsExtractor g, final Font font, final DeathData data, final int x, final int y, final int width) {
        final int height = height(font, data, width);
        g.fill(x, y, x + width, y + height, 0xD0100606);
        g.fill(x, y, x + width, y + 13, 0xE0300808);
        g.outline(x, y, width, height, 0xFF7A2A20);
        g.text(font, Component.literal(data.title().isEmpty() ? "☠ 死亡回顧" : data.title()).withStyle(s -> s.withColor(0xFF5555)), x + 5, y + 3,
                0xFFFFFFFF, true);
        int dy = y + 17;
        for (final List<HudData.Part> line : data.body()) {
            dy += FeedText.wrapped(g, font, FeedText.component(line), x + 5, dy, width - 10, SCALE) + 1;
        }
        final String hint = "[" + FeedsClient.deathKeyName() + "] 重生後再看一次";
        FeedText.text(g, font, Component.literal(hint).withStyle(s -> s.withColor(0x707070)), x + 5, y + height - 8, 0.55f);
    }

    /** 重生以後按鍵叫出來的畫面。 */
    static final class RecapScreen extends Screen {

        RecapScreen() {
            super(Component.literal("死亡回顧"));
        }

        @Override
        public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            final DeathData data = FeedsClient.lastDeath;
            final int width = Math.min(260, this.width - 20);
            if (data == null) {
                g.centeredText(this.font, Component.literal("這次登入還沒死過"), this.width / 2, this.height / 2, 0xFFC0C0C0);
                return;
            }
            final int height = height(this.font, data, width);
            draw(g, this.font, data, this.width / 2 - width / 2, Math.max(8, this.height / 2 - height / 2), width);
        }

        @Override
        public boolean keyPressed(final KeyEvent event) {
            if (UiNet.closeKey(this, event) || FeedsClient.deathKey != null && FeedsClient.deathKey.matches(event)) {
                onClose();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
