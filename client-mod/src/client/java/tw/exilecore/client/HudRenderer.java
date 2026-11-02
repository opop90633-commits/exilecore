package tw.exilecore.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.ModConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 畫技能列、增益列、目標框、生命與能量護盾（只在連上星骸伺服器、收到畫面資料時）。
 * 顏色都要寫 alpha（0xFF……），不然字是透明的。
 */
final class HudRenderer {

    private HudRenderer() {
    }

    private static final int SLOT = 18;
    private static final int GAP = 2;
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    private static boolean active() {
        final Minecraft mc = Minecraft.getInstance();
        return ExileCoreClient.connected && ExileCoreClient.hud != null && ExileCoreClient.config.hudEnabled && mc.player != null
                && !mc.gui.hud.isHidden();
    }

    static void extract(final GuiGraphicsExtractor g, final DeltaTracker delta) {
        if (!active()) {
            return;
        }
        final HudData data = ExileCoreClient.hud;
        final long age = ExileCoreClient.hudAge;
        final ModConfig config = ExileCoreClient.config;
        final Font font = Minecraft.getInstance().font;
        // 2026-11-02（C 組）：每個元件平移設定畫面拖過的偏移（HudDragBinding）
        if (config.showSkillBar && !data.slots().isEmpty()) {
            HudDragBinding.begin(g, HudDragBinding.SKILL_BAR);
            skillBar(g, font, data, age, config);
            HudDragBinding.end(g);
        }
        if (config.showBuffs && (!data.buffs().isEmpty() || !data.sides().auras().isEmpty())) {
            // 2026-11-02 照站長（第三版）：聊天打開時，增益欄（拖過的位置）疊到聊天框就淡出到半透明（HudLayout.chatFade）
            fade = HudLayout.chatFade(box(tw.exilecore.client.core.HudDrag.bounds(HudDragBinding.BUFFS, g.guiWidth(), g.guiHeight())), chatOpen(),
                    chatBox(g.guiWidth(), g.guiHeight(), true), g.guiWidth(), g.guiHeight());
            HudDragBinding.begin(g, HudDragBinding.BUFFS);
            buffs(g, font, data, age, config);
            expandedBuffs(g, font, data, age); // 2026-11-02（1.4）：按住 Tab 列出全部增益（跟著增益列一起拖）
            HudDragBinding.end(g);
            if (orbsShown()) {
                // 2026-11-02 照站長：光環那一行自己一個元件（可以分開拖）；這次之前跟著增益列一起拖
                fade = HudLayout.chatFade(box(tw.exilecore.client.core.HudDrag.bounds(HudDragBinding.AURAS, g.guiWidth(), g.guiHeight())),
                        chatOpen(), chatBox(g.guiWidth(), g.guiHeight(), true), g.guiWidth(), g.guiHeight());
                HudDragBinding.begin(g, HudDragBinding.AURAS);
                auraStrip(g, font, data);
                HudDragBinding.end(g);
            }
            fade = 1f;
        } else {
            buffBox = null;
        }
        if (config.showTarget && data.target() != null && !bossBarShows(data.target())) {
            HudDragBinding.begin(g, HudDragBinding.TARGET);
            target(g, font, data.target(), config);
            HudDragBinding.end(g);
        }
        // 2026-11-02 照站長（1.4）：提示列、側邊面板、生命球旁的異常與守護、魔力球旁的戰鬥資源、經驗條上的字（各自的類別）
        final float partial = delta.getGameTimeDeltaPartialTick(false);
        if (config.showNotices) {
            HudDragBinding.begin(g, HudDragBinding.NOTICES); // 2026-11-02（C 組合併）
            NoticeRenderer.extract(g, font, partial);
            HudDragBinding.end(g);
        }
        if (config.showPanels) {
            // 2026-11-02 照站長：面板群整群的偏移（PANELS）與拖出來的單一面板都在 PanelRenderer 裡面套
            PanelRenderer.extract(g, font, data, age);
        }
        if (orbsShown()) {
            if (config.showStatus) {
                HudDragBinding.begin(g, HudDragBinding.STATUS); // 2026-11-02（C 組合併）
                StatusRenderer.extract(g, font, data, age + partial);
                HudDragBinding.end(g);
            }
            if (config.showResources && !data.res().isEmpty()) {
                HudDragBinding.begin(g, HudDragBinding.RESOURCES); // 2026-11-02（C 組合併）
                ResourceRenderer.extract(g, font, data.res());
                HudDragBinding.end(g);
            }
            if (config.showXp) {
                HudDragBinding.begin(g, HudDragBinding.XP); // 2026-11-02（C 組合併）
                XpRenderer.extract(g, font, data.self());
                HudDragBinding.end(g);
            }
        }
    }

    // ---- 技能列 ----

    /** 2026-11-02（1.3）：有球時技能列的下緣在畫面底往上幾格（快捷欄正上方、經驗等級數字的上面）。 */
    static final int DOCK_BOTTOM = 37;

    private static void skillBar(final GuiGraphicsExtractor g, final Font font, final HudData data, final long age, final ModConfig config) {
        final int count = data.slots().size();
        final int width = count * (SLOT + GAP) - GAP;
        int x0 = g.guiWidth() / 2 + config.skillBarX;
        int y0 = g.guiHeight() + config.skillBarY;
        final boolean docked = orbsShown();
        if (docked) {
            // 2026-11-02 照站長（1.3）：有球時放在快捷欄正上方（原本愛心、盔甲那兩排的高度；按鍵寫在格子裡面），
            //   快捷欄兩側讓給球；1.2 是推到右球的外側（會疊到右下角的字幕）
            x0 = g.guiWidth() / 2 - Math.round(width * config.scale / 2);
            y0 = g.guiHeight() - DOCK_BOTTOM - Math.round(SLOT * config.scale);
        } else if (config.skillBarX > 0 && x0 + width * config.scale > g.guiWidth() - 2) {
            // 右邊放不下（GUI 縮放很大）：改放到快捷欄正上方
            x0 = g.guiWidth() / 2 - Math.round(width * config.scale / 2);
            y0 = g.guiHeight() - 60 - Math.round(SLOT * config.scale);
        }
        g.pose().pushMatrix();
        g.pose().translate(x0, y0);
        g.pose().scale(config.scale, config.scale);
        for (int i = 0; i < count; i++) {
            final HudData.Slot slot = data.slots().get(i);
            final int x = i * (SLOT + GAP);
            drawSlot(g, font, slot, x, age, config, docked);
        }
        g.pose().popMatrix();
    }

    /** 1.3：有球、技能列在快捷欄正上方時，盔甲、氧氣（往上 15）與坐騎血量（往上 25）移到技能列上面；不然 0。 */
    static int statusBarLift(final Identifier bar) {
        final ModConfig config = ExileCoreClient.config;
        if (!orbsShown() || !config.showSkillBar || ExileCoreClient.hud.slots().isEmpty()) {
            return 0;
        }
        return bar.equals(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MOUNT_HEALTH) ? 25 : 15;
    }

    private static void drawSlot(final GuiGraphicsExtractor g, final Font font, final HudData.Slot slot, final int x, final long age,
                                 final ModConfig config, final boolean labelInside) {
        final boolean castable = slot.castable(age);
        g.fill(x, 0, x + SLOT, SLOT, 0xB0101018);
        g.outline(x, 0, SLOT, SLOT, slot.bound() ? 0xFF8A7A50 : 0xFF404040);
        g.item(icon(slot.item(), slot.model()), x + 1, 1);
        final double fraction = slot.cooldownFraction(age);
        if (fraction > 0) {
            // 冷卻：從上往下蓋一層暗色（像原版的物品冷卻），中間寫剩幾秒
            final int height = (int) Math.ceil(16 * fraction);
            g.fill(x + 1, 1 + 16 - height, x + 17, 17, 0xA0000000);
            final String seconds = HudData.seconds(slot.cooldownLeft(age));
            g.centeredText(font, seconds, x + SLOT / 2, 5, 0xFFFFFFFF);
        } else if (!castable) {
            g.fill(x + 1, 1, x + 17, 17, 0x90400000); // 放不了（魔力不足、武器不符、營地）：暗紅
        } else if (!slot.bound()) {
            g.fill(x + 1, 1, x + 17, 17, 0x60000000); // 沒綁技能（普攻）：暗一點
        }
        if (slot.gloryNeed() > 0) {
            // 2026-11-02 照站長（1.4）：榮耀——格子底下一條細條（夠了綠色、不夠灰色）＋左下角「現在/需要」
            //   （資源包版寫在快捷欄那一格上方；sides 的人那段不接在動作列了）
            final int color = slot.gloryReady() ? 0xFF55FF55 : 0xFFA0A0A0;
            g.fill(x + 1, SLOT - 3, x + SLOT - 1, SLOT - 1, 0xC0000000);
            g.fill(x + 1, SLOT - 3, x + 1 + (int) Math.round((SLOT - 2) * slot.gloryFraction()), SLOT - 1, color);
            final String glory = Math.min(slot.gloryNow(), slot.gloryNeed()) + "/" + slot.gloryNeed();
            g.pose().pushMatrix();
            g.pose().translate(x + 1.5f, SLOT - 7.5f);
            g.pose().scale(0.5f, 0.5f);
            g.text(font, glory, 0, 0, slot.gloryReady() ? 0xFF7CFF7C : 0xFFD0D0D0, true);
            g.pose().popMatrix();
        }
        final int uses = slot.uses(age);
        if (slot.maxUses() > 1) {
            final String text = Integer.toString(uses);
            g.text(font, text, x + SLOT - 1 - font.width(text), SLOT - 8, uses > 0 ? 0xFFFFFF55 : 0xFFFF5555, true);
        }
        if (config.showKeyLabels) {
            final String label = keyLabel(slot.slot());
            g.pose().pushMatrix();
            if (labelInside) {
                // 1.3：技能列在快捷欄正上方時，按鍵寫在格子左上角（下面是經驗等級的數字）
                g.pose().translate(x + 1.5f, 1.5f);
                g.pose().scale(0.5f, 0.5f);
                g.text(font, label, 0, 0, castable ? 0xFFE0E0E0 : 0xFFFF7070, true);
            } else {
                g.pose().translate(x + SLOT / 2f, SLOT + 1);
                g.pose().scale(0.5f, 0.5f);
                g.centeredText(font, label, 0, 0, castable ? 0xFFC8C8C8 : 0xFFFF7070);
            }
            g.pose().popMatrix();
        }
    }

    /** 技能格下面的字：綁的按鍵（沒綁寫格子的名字）。 */
    private static String keyLabel(final int slot) {
        if (slot < 0 || slot >= ExileCoreClient.SKILL_KEYS.length) {
            return "";
        }
        final KeyMapping key = ExileCoreClient.SKILL_KEYS[slot];
        if (key != null && !key.isUnbound()) {
            final String name = key.getTranslatedKeyMessage().getString();
            return name.length() > 6 ? name.substring(0, 6) : name;
        }
        return ExileCoreClient.SLOT_LABELS[slot];
    }

    /** 原版物品＋custom_model_data 字串（資源包把它畫成技能圖）；物品代號不認得用書。 */
    static ItemStack icon(final String itemId, final String model) {
        final String key = itemId + "|" + model;
        return ICONS.computeIfAbsent(key, k -> {
            Item item = Items.BOOK;
            try {
                final Identifier id = Identifier.parse(itemId == null || itemId.isEmpty() ? "minecraft:book" : itemId);
                final Item found = BuiltInRegistries.ITEM.getValue(id);
                if (found != null && found != Items.AIR) {
                    item = found;
                }
            } catch (final RuntimeException ignored) {
                // 代號寫壞：用書
            }
            final ItemStack stack = new ItemStack(item);
            if (model != null && !model.isEmpty()) {
                stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(model), List.of()));
            }
            return stack;
        });
    }

    // ---- 增益列 ----

    // ---- 2026-11-02 照站長（第三版）：增益欄的位置、淡出 ----

    /** 增益欄畫在哪（沒拖過的位置；拖動畫面畫框、聊天打開時判斷疊到沒有用上一幀的）；沒畫 null。 */
    static HudLayout.Box buffBox;
    /** 這一幀增益欄的範圍（{@link HudLayout#buffArea}）。 */
    private static HudLayout.Box buffArea;
    /** 這一幀增益、光環的不透明度（聊天打開、疊到聊天框時 {@link HudLayout#CHAT_FADE}）。 */
    private static float fade = 1f;

    /** 套上淡出的顏色。 */
    private static int a(final int argb) {
        if (fade >= 1f) {
            return argb;
        }
        final int alpha = Math.max(4, Math.round((argb >>> 24) * fade));
        return alpha << 24 | argb & 0xFFFFFF;
    }

    static boolean chatOpen() {
        return Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen;
    }

    /** 原版聊天框的範圍（照玩家的聊天設定：寬、高、縮放）；focused = 打開時的高度。 */
    static HudLayout.Box chatBox(final int guiWidth, final int guiHeight, final boolean focused) {
        final net.minecraft.client.Options options = Minecraft.getInstance().options;
        final int width = net.minecraft.client.gui.components.ChatComponent.getWidth(options.chatWidth().get());
        final int height = net.minecraft.client.gui.components.ChatComponent.getHeight(
                focused ? options.chatHeightFocused().get() : options.chatHeightUnfocused().get());
        return HudLayout.chatBox(guiWidth, guiHeight, width, height, options.chatScale().get());
    }

    /** 增益欄沒拖過的預設範圍（聊天框上面）。 */
    static HudLayout.Box defaultBuffArea(final int guiWidth, final int guiHeight) {
        return HudLayout.buffArea(guiWidth, guiHeight, OrbRenderer.size(), chatBox(guiWidth, guiHeight, false));
    }

    private static HudLayout.Box box(final tw.exilecore.client.core.HudDrag.Rect rect) {
        return rect == null ? null : new HudLayout.Box(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /**
     * 2026-11-02 照站長（第三版）：增益欄——有倒數的增益（沒有球時光環也併在這裡）全部寫「名稱 秒數」，
     * 預設放在<b>聊天框（收起時）上面</b>（{@link HudLayout#buffArea}）：從畫面左邊往右一欄一欄排、每欄由下往上，
     * 塞不下先縮小（0.75），還是塞不下最後一格寫「+N」（按住 Tab 看全部）。重要的（護符、神龕）、技能格的、快結束的排最下面。
     * 可以在拖動 HUD 的畫面拖到別的地方（會記住，{@link HudDragBinding#BUFFS}）；聊天打開時疊到聊天框就淡出（{@link HudLayout#chatFade}）。
     * 這次之前：有球時在左球左邊從畫面底往上排（第 3 排以上進到聊天框）；沒有球時在快捷欄左邊往上疊（buffsX、buffsY）。
     */
    private static void buffs(final GuiGraphicsExtractor g, final Font font, final HudData data, final long age, final ModConfig config) {
        final List<HudData.Buff> timed = tw.exilecore.client.core.BuffOrder.sorted(data.buffs(), age, AURA_SLOT);
        if (orbsShown()) {
            PanelRenderer.auras = List.of(); // 光環是畫面最底下那一行（auraStrip，自己一個元件）
        } else {
            timed.addAll(auras(data));
        }
        timed.removeIf(buff -> buff.left(age) <= 0);
        final HudLayout.Box area = defaultBuffArea(g.guiWidth(), g.guiHeight());
        buffArea = area;
        if (timed.isEmpty()) {
            buffBox = null;
            return;
        }
        final List<Integer> widths = new java.util.ArrayList<>();
        for (final HudData.Buff buff : timed) {
            widths.add(buffWidth(font, buff, age));
        }
        final tw.exilecore.client.core.SideLayout.Result result = tw.exilecore.client.core.SideLayout.columnFromLeft(widths, area.x(),
                area.bottom(), area.y(), area.right());
        final float k = result.scale();
        for (final tw.exilecore.client.core.SideLayout.Cell cell : result.cells()) {
            int column = 0; // 同一欄一樣寬（這一欄最寬的那個）
            for (final tw.exilecore.client.core.SideLayout.Cell other : result.cells()) {
                if (other.x() == cell.x()) {
                    column = Math.max(column, widths.get(other.index()));
                }
            }
            g.pose().pushMatrix();
            g.pose().translate(cell.x(), cell.y());
            g.pose().scale(k, k);
            drawBuff(g, font, timed.get(cell.index()), age, column, column, 0);
            g.pose().popMatrix();
        }
        HudLayout.Box drawn = tw.exilecore.client.core.SideLayout.bounds(result, widths);
        if (result.more() > 0) {
            HIDDEN.addAll(timed.subList(timed.size() - result.more(), timed.size()));
            final tw.exilecore.client.core.SideLayout.Cell last = result.cells().isEmpty()
                    ? new tw.exilecore.client.core.SideLayout.Cell(0, area.x(), area.bottom()) : result.cells().getLast();
            final int moreY = last.y() - Math.round(tw.exilecore.client.core.SideLayout.ROW * k) >= area.y()
                    ? last.y() - Math.round(tw.exilecore.client.core.SideLayout.ROW * k) : last.y();
            final int moreWidth = Math.max(font.width("+" + result.more()) + 4, 14);
            final int moreX = moreY == last.y() ? last.x() + Math.round(widths.get(last.index()) * k) + 2 : last.x();
            g.pose().pushMatrix();
            g.pose().translate(moreX, moreY);
            g.pose().scale(k, k);
            more(g, font, result.more(), moreWidth, 0);
            g.pose().popMatrix();
            final HudLayout.Box tag = new HudLayout.Box(moreX, moreY, Math.round(moreWidth * k), Math.round(11 * k));
            drawn = drawn == null ? tag : union(drawn, tag);
        }
        buffBox = drawn;
    }

    private static HudLayout.Box union(final HudLayout.Box a, final HudLayout.Box b) {
        final int x = Math.min(a.x(), b.x());
        final int y = Math.min(a.y(), b.y());
        return new HudLayout.Box(x, y, Math.max(a.right(), b.right()) - x, Math.max(a.bottom(), b.bottom()) - y);
    }

    /**
     * 2026-11-02 照站長：固定的光環（沒有倒數的）畫面最底下一行，從畫面左邊往右排、到左球外側為止（聊天欄最下面那行的下面）；
     * 塞不下先縮小，還是塞不下最後寫「+N」。資源包版是同一個位置（著色器搬到左下角）。
     */
    static void auraStrip(final GuiGraphicsExtractor g, final Font font, final HudData data) {
        final List<String> auras = data.sides().auras();
        if (auras.isEmpty()) {
            return;
        }
        final HudLayout.Box strip = HudLayout.auraStrip(g.guiWidth(), g.guiHeight(), OrbRenderer.size());
        final List<Integer> widths = new java.util.ArrayList<>();
        for (final String aura : auras) {
            widths.add(font.width(aura) + 13);
        }
        final tw.exilecore.client.core.SideLayout.Result result = tw.exilecore.client.core.SideLayout.strip(widths,
                font.width("+" + auras.size()) + 4, strip.x(), strip.right(), strip.y());
        final float k = result.scale();
        int end = strip.x();
        for (final tw.exilecore.client.core.SideLayout.Cell cell : result.cells()) {
            final int w = widths.get(cell.index());
            g.pose().pushMatrix();
            g.pose().translate(cell.x(), strip.bottom() - 11 * k);
            g.pose().scale(k, k);
            g.fill(0, 0, w, 11, a(0x90000000));
            g.fill(0, 10, w, 11, a(0xFF4FD8C8));
            g.text(font, "✦", 2, 1, a(0xFF8FD3FF), true);
            g.text(font, auras.get(cell.index()), 11, 1, a(0xFFFFFFFF), true);
            g.pose().popMatrix();
            end = cell.x() + Math.round(w * k);
        }
        if (result.more() > 0) {
            g.pose().pushMatrix();
            g.pose().translate(end + 3, strip.bottom() - 11 * k);
            g.pose().scale(k, k);
            g.text(font, "+" + result.more(), 0, 1, a(0xFFC8C8C8), true);
            g.pose().popMatrix();
        }
    }

    /** 2026-11-02（1.4）：這一幀增益列放不下、藏起來的（按住 Tab 時列出全部）。 */
    private static final List<HudData.Buff> HIDDEN = new java.util.ArrayList<>();

    /** 「+N」那格：底色、數字，下面一行小字提示按住 Tab 看全部。 */
    private static void more(final GuiGraphicsExtractor g, final Font font, final int count, final int right, final int y) {
        final String text = "+" + count;
        final int width = Math.max(font.width(text) + 4, 14);
        g.fill(right - width, y, right, y + 11, a(0x90000000));
        g.text(font, text, right - width + 2, y + 1, a(0xFFC8C8C8), true);
        g.pose().pushMatrix();
        g.pose().translate(right - width / 2f, y - 5f);
        g.pose().scale(0.5f, 0.5f);
        g.centeredText(font, "Tab", 0, 0, a(0xFF8A8A8A));
        g.pose().popMatrix();
    }

    /**
     * 2026-11-02 照站長（1.4）：按住玩家清單鍵（Tab）時，左下增益列上面展開一個框，列出全部增益、光環與剩下的時間
     * （平常放不下的寫「+N」）。只在有東西被藏起來時畫；Tab 原本的玩家清單在畫面上方，不會疊到。
     */
    static void expandedBuffs(final GuiGraphicsExtractor g, final Font font, final HudData data, final long age) {
        final boolean any = !HIDDEN.isEmpty() || !PanelRenderer.auras.isEmpty();
        HIDDEN.clear();
        if (!any || !Minecraft.getInstance().options.keyPlayerList.isDown()) {
            return;
        }
        final List<HudData.Buff> all = tw.exilecore.client.core.BuffOrder.sorted(data.buffs(), age, AURA_SLOT);
        all.addAll(auras(data));
        int width = 80;
        for (final HudData.Buff buff : all) {
            width = Math.max(width, buffWidth(font, buff, age) + 4);
        }
        // 蓋在增益欄上面（按住 Tab 的時候）；2026-11-02：跟著增益欄的範圍（聊天框上面），這次之前貼著畫面底
        final HudLayout.Box area = buffArea != null ? buffArea : defaultBuffArea(g.guiWidth(), g.guiHeight());
        final int bottom = area.bottom();
        final int x0 = area.x();
        final int rows = Math.min(all.size(), Math.max(1, (bottom - 20) / 10));
        final int top = bottom - rows * 10 - 13;
        g.fill(x0, top, x0 + width, bottom, 0xD0101010);
        g.outline(x0, top, width, bottom - top, 0xFF8A7A50);
        g.text(font, "全部增益（" + all.size() + "）", x0 + 3, top + 2, 0xFFE0C080, true);
        for (int i = 0; i < rows; i++) {
            final HudData.Buff buff = all.get(i);
            final int y = top + 13 + i * 10;
            final String name = buff.stacks() > 1 ? buff.name() + " ×" + buff.stacks() : buff.name();
            final String time = timeText(buff, age);
            g.text(font, name, x0 + 3, y, buff.important() ? 0xFFFFE08A : 0xFFFFFFFF, true);
            g.text(font, time, x0 + width - 3 - font.width(time), y, timeColor(buff, buff.left(age)), true);
        }
    }

    /** 1.3：增益列（有球時）最下面那排的下緣在畫面底往上幾格（伺服器的光環寫在快捷欄那一排：往上 7～15）。 */
    static final int BUFF_BOTTOM = 15;

    private static int buffWidth(final Font font, final HudData.Buff buff, final long age) {
        final String name = buff.stacks() > 1 ? buff.name() + " ×" + buff.stacks() : buff.name();
        return Math.max(48, font.width(name) + font.width(timeText(buff, age)) + 8);
    }

    /** 2026-11-02（1.4）：光環當成增益列的一格（slot {@value #AURA_SLOT}：不倒數、青色）。 */
    static final int AURA_SLOT = -2;

    private static List<HudData.Buff> auras(final HudData data) {
        final List<HudData.Buff> out = new java.util.ArrayList<>();
        for (final String aura : data.sides().auras()) {
            out.add(new HudData.Buff(aura, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 0, AURA_SLOT));
        }
        return out;
    }

    private static String timeText(final HudData.Buff buff, final long age) {
        return buff.slot() == AURA_SLOT ? "光環" : HudData.duration(buff.left(age));
    }

    private static int barColor(final HudData.Buff buff) {
        return buff.slot() == AURA_SLOT ? 0xFF4FD8C8 : buff.slot() >= 0 ? 0xFFE0B040 : 0xFF60C0FF;
    }

    private static int timeColor(final HudData.Buff buff, final long left) {
        return buff.slot() == AURA_SLOT ? 0xFF7FE8D8 : left <= 60 ? 0xFFFF7070 : 0xFFC8C8C8;
    }

    private static void drawBuff(final GuiGraphicsExtractor g, final Font font, final HudData.Buff buff, final long age, final int right,
                                 final int width, final int y) {
        final long left = buff.left(age);
        final String time = timeText(buff, age);
        final String name = buff.stacks() > 1 ? buff.name() + " ×" + buff.stacks() : buff.name();
        g.fill(right - width, y, right, y + 11, a(0x90000000));
        final int bar = (int) Math.round(width * buff.fraction(age));
        g.fill(right - width, y + 10, right - width + bar, y + 11, a(barColor(buff)));
        g.text(font, name, right - width + 2, y + 1, a(0xFFFFFFFF), true);
        g.text(font, time, right - 2 - font.width(time), y + 1, a(timeColor(buff, left)), true);
    }

    // ---- 目標框 ----

    private static void target(final GuiGraphicsExtractor g, final Font font, final HudData.Target target, final ModConfig config) {
        final int width = 150;
        final int cx = g.guiWidth() / 2 + config.targetX;
        g.pose().pushMatrix();
        // 2026-11-02 照站長（1.4）：推到最後一條 Boss 血條下面；這次之前寫死在 targetY（第二條以後的血條被蓋住）
        g.pose().translate(cx, tw.exilecore.client.core.HudLayout.targetY(config.targetY, bossBars(), g.guiHeight()));
        g.pose().scale(config.scale, config.scale);
        final int left = -width / 2;
        g.fill(left - 2, -2, left + width + 2, 30, 0x90000000);
        final MutableComponent title = Component.literal(target.name()).withStyle(style -> style.withColor(TextColor.fromRgb(target.color())));
        final String level = (target.level() > 0 ? "Lv." + target.level() + " " : "") + target.rarity() + (target.boss() ? "·首領" : "");
        g.text(font, title, left, 0, 0xFFFFFFFF, true);
        g.text(font, level, left + width - font.width(level), 0, 0xFFAAAAAA, true);
        // 生命條（2026-11-02 1.4：打不動的整條灰色、金框、左邊一個鎖頭，跟怪物頭上的血條一樣）
        final int barY = 10;
        g.fill(left, barY, left + width, barY + 7, 0xFF300808);
        g.fill(left, barY, left + (int) Math.round(width * target.lifeFraction()), barY + 7, target.invulnerable() ? 0xFF8A8A8A : 0xFFD02828);
        if (target.shieldPercent() >= 0) {
            g.fill(left, barY, left + (int) Math.round(width * target.shieldPercent() / 100.0), barY + 2, 0xFF7FC8FF); // 護盾：上面一條藍線
        }
        g.outline(left, barY, width, 7, 0xFF000000);
        if (target.invulnerable()) {
            g.outline(left - 1, barY - 1, width + 2, 9, 0xFFFFD040);
            lock(g, left - 8, barY - 1);
        }
        final String life = grouped(target.life()) + " / " + grouped(target.maxLife())
                + (target.shieldPercent() >= 0 ? "  護盾 " + target.shieldPercent() + "%" : "");
        g.pose().pushMatrix();
        g.pose().translate(0, barY + 1);
        g.pose().scale(0.6f, 0.6f);
        g.centeredText(font, life, 0, 0, 0xFFFFFFFF);
        g.pose().popMatrix();
        // 異常、詛咒、印記、曝露
        if (!target.status().isEmpty()) {
            final MutableComponent status = Component.empty();
            for (final HudData.Part part : target.status()) {
                status.append(Component.literal(part.text()).withStyle(style -> style.withColor(TextColor.fromRgb(part.color()))));
            }
            g.pose().pushMatrix();
            g.pose().translate(left, barY + 10);
            g.pose().scale(0.75f, 0.75f);
            g.text(font, status, 0, 0, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
        g.pose().popMatrix();
    }

    /** 2026-11-02（1.4）：金色的小鎖頭（6×9，左上角在 x、y）。 */
    private static void lock(final GuiGraphicsExtractor g, final int x, final int y) {
        final int gold = 0xFFFFD040;
        g.outline(x + 1, y, 4, 4, gold); // 鎖環
        g.fill(x, y + 3, x + 6, y + 9, gold); // 鎖身
        g.fill(x + 2, y + 5, x + 4, y + 7, 0xFF6A4A00); // 鑰匙孔
    }

    /**
     * 2026-11-02 照站長：目標是首領、而且伺服器已經有一條它的 Boss 血條（名稱有它、血量比例對得上）：不再畫目標框（不然兩條）。
     * 訓練假人那種「每秒傷害」的條比例對不上，目標框照畫。這次之前一律畫。
     */
    static boolean bossBarShows(final HudData.Target target) {
        if (!target.boss()) {
            return false;
        }
        try {
            for (final net.minecraft.client.gui.components.LerpingBossEvent event : ((tw.exilecore.client.mixin.BossHealthOverlayAccessor)
                    Minecraft.getInstance().gui.hud.getBossOverlay()).exilecore$events().values()) {
                if (event.getName().getString().contains(target.name()) && Math.abs(event.getProgress() - target.lifeFraction()) < 0.03) {
                    return true;
                }
            }
        } catch (final RuntimeException e) {
            return false;
        }
        return false;
    }

    /** 2026-11-02（1.4）：畫面上有幾條 Boss 血條。 */
    static int bossBars() {
        try {
            return ((tw.exilecore.client.mixin.BossHealthOverlayAccessor) Minecraft.getInstance().gui.hud.getBossOverlay()).exilecore$events().size();
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    // ---- 生命與能量護盾（取代原版愛心；2026-11-02 起預設畫成球，OrbRenderer）----

    /**
     * 原版愛心的位置畫生命條（紅色；有「傷害先扣魔力」時照比例右邊畫藍色；保留的生命是灰色那段），上面一排畫能量護盾（藍）。
     *
     * @return false = 沒畫（不是星骸伺服器、沒資料、關掉了），照原版畫愛心
     */
    static boolean extractHearts(final GuiGraphicsExtractor g) {
        if (!drawsVitals()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final HudData.Vitals v = ExileCoreClient.hud.vitals();
        if (ExileCoreClient.config.orbs) {
            OrbRenderer.extract(g, v); // 2026-11-02 照站長：生命球、魔力球；這次之前是下面的生命條＋護盾條
            return true;
        }
        final Font font = mc.font;
        final int left = g.guiWidth() / 2 - 91;
        final int top = g.guiHeight() - 39;
        final int width = 81;
        final double max = Math.max(1, v.maxLife());
        // 生命條
        g.fill(left, top, left + width, top + 9, 0xFF200606);
        final int reserved = (int) Math.round(width * Math.min(1, v.reservedLife() / max));
        final int filled = (int) Math.round((width - reserved) * Math.min(1, v.life() / Math.max(1, max - v.reservedLife())));
        final double share = Math.max(0, Math.min(1, v.manaShare()));
        final int blue = (int) Math.round(filled * share);
        g.fill(left, top, left + filled - blue, top + 9, 0xFFC82020);
        if (blue > 0) {
            g.fill(left + filled - blue, top, left + filled, top + 9, 0xFF2A6CE0); // 傷害先扣魔力的那部分
        }
        if (reserved > 0) {
            g.fill(left + width - reserved, top, left + width, top + 9, 0xFF5A4848);
        }
        g.fill(left, top, left + filled, top + 1, 0x40FFFFFF);
        g.outline(left - 1, top - 1, width + 2, 11, 0xFF000000);
        final String life = grouped(v.life()) + "/" + grouped(v.maxLife() - v.reservedLife());
        drawSmall(g, font, life, left + width / 2f, top + 2.5f, 0xFFFFFFFF);
        // 能量護盾：原版第二排愛心的位置
        if (v.maxEnergyShield() > 0) {
            final int esTop = top - 8;
            final int es = (int) Math.round(width * Math.min(1, v.energyShield() / (double) v.maxEnergyShield()));
            g.fill(left, esTop, left + width, esTop + 6, 0xFF0A1830);
            g.fill(left, esTop, left + es, esTop + 6, 0xFF4AA8FF);
            g.fill(left, esTop, left + es, esTop + 1, 0x60FFFFFF);
            g.outline(left - 1, esTop - 1, width + 2, 8, 0xFF000000);
            drawSmall(g, font, grouped(v.energyShield()) + "/" + grouped(v.maxEnergyShield()), left + width / 2f, esTop + 1f, 0xFFE0F0FF);
        }
        return true;
    }

    /** 這一幀要不要畫生命（球或條）：連上星骸、有資料、設定「取代愛心」開著、不是創造／旁觀。 */
    private static boolean drawsVitals() {
        if (!active() || !ExileCoreClient.config.replaceHearts || ExileCoreClient.hud.vitals() == null) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.player.isCreative() && !mc.player.isSpectator();
    }

    /** 2026-11-02：這一幀畫的是生命球、魔力球（原版的飢餓條也藏起來；技能列、增益列避開球）。 */
    static boolean orbsShown() {
        return ExileCoreClient.config.orbs && drawsVitals();
    }

    private static void drawSmall(final GuiGraphicsExtractor g, final Font font, final String text, final float cx, final float y, final int color) {
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(0.5f, 0.5f);
        g.centeredText(font, text, 0, 0, color);
        g.pose().popMatrix();
    }

    static String grouped(final long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
