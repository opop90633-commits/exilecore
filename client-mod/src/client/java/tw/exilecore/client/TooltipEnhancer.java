package tw.exilecore.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import tw.exilecore.client.core.TooltipModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * 2026-11-02 照站長（C 組）：裝備、寶石說明平常顯示精簡版（名稱、基底、主要詞綴），按住 Shift 完整說明（伺服器的 lore 原封不動）、
 * 按住 Alt 詞綴階級與數值範圍（{@link TooltipModel}）。這次之前一律是伺服器的完整說明（現在沒裝模組的人照舊）。
 * <p>
 * 物品是不是星骸的裝備、寶石，看 custom_data 裡 Bukkit 的 PDC（{@code PublicBukkitValues} 的 {@code exilecore:data}、
 * {@code exilecore:gem}）：原版 26.2 的 custom_data 有同步到客戶端（沒寫 networkSynchronized 的元件預設照 NBT 編碼送），
 * Paper 的物品混淆預設關著、也只管別人身上的裝備，自己背包裡的物品原封不動送來。讀不到的物品照原本的完整說明。
 */
final class TooltipEnhancer {

    private TooltipEnhancer() {
    }

    static final String PDC = "PublicBukkitValues";
    static final String DATA_KEY = "exilecore:data";
    static final String GEM_KEY = "exilecore:gem";

    /** 同一份 custom_data（物品沒變）不用每一幀重讀 JSON。 */
    private static final Map<CustomData, Optional<TooltipModel.ItemInfo>> CACHE = new WeakHashMap<>();

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            try {
                rewrite(stack, lines);
            } catch (final RuntimeException e) {
                // 說明欄出錯不要讓遊戲當掉：照原本的
            }
        });
    }

    /** 這件物品的資料（不是星骸的回傳 null）。 */
    static TooltipModel.ItemInfo info(final ItemStack stack) {
        final CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) {
            return null;
        }
        return CACHE.computeIfAbsent(custom, key -> {
            final CompoundTag pdc = key.copyTag().getCompoundOrEmpty(PDC);
            final String json = pdc.getStringOr(DATA_KEY, "");
            if (!json.isEmpty()) {
                return Optional.ofNullable(TooltipModel.parseEquipment(json));
            }
            return pdc.contains(GEM_KEY) ? Optional.of(TooltipModel.gem()) : Optional.empty();
        }).orElse(null);
    }

    private static void rewrite(final ItemStack stack, final List<Component> lines) {
        final Minecraft mc = Minecraft.getInstance();
        if (!ExileCoreClient.connected || !ExileCoreClient.config.compactTooltips || mc.hasShiftDown() || stack.isEmpty()) {
            return; // Shift：完整說明（伺服器的 lore 原封不動）
        }
        final ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return;
        }
        final TooltipModel.ItemInfo info = info(stack);
        if (info == null) {
            return;
        }
        // 說明在提示框裡的位置：第一行 lore 的字對得上的那一行開始（前面是名稱）
        final String first = lore.lines().getFirst().getString();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getString().equals(first)) {
                start = i;
                break;
            }
        }
        final int end = start + lore.lines().size();
        if (start < 0 || end > lines.size()) {
            return;
        }
        final List<TooltipModel.Line> model = new ArrayList<>();
        for (int i = start; i < end; i++) {
            model.add(line(lines.get(i)));
        }
        final List<TooltipModel.Line> shown = mc.hasAltDown() ? TooltipModel.tiers(model, info) : TooltipModel.compact(model, info);
        if (shown == model) {
            return;
        }
        final List<Component> replaced = new ArrayList<>();
        for (final TooltipModel.Line line : shown) {
            replaced.add(component(line));
        }
        final List<Component> tail = new ArrayList<>(lines.subList(end, lines.size()));
        lines.subList(start, lines.size()).clear();
        lines.addAll(replaced);
        lines.addAll(tail);
    }

    /** 一行說明拆成一段一段（字＋顏色）。 */
    static TooltipModel.Line line(final Component component) {
        final List<TooltipModel.Seg> segs = new ArrayList<>();
        component.visit((FormattedText.StyledContentConsumer<Object>) (style, text) -> {
            if (!text.isEmpty()) {
                final TextColor color = style.getColor();
                segs.add(new TooltipModel.Seg(text, color == null ? -1 : color.getValue() & 0xFFFFFF));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return new TooltipModel.Line(segs);
    }

    static Component component(final TooltipModel.Line line) {
        final MutableComponent out = Component.empty();
        for (final TooltipModel.Seg seg : line.segs()) {
            final MutableComponent part = Component.literal(seg.text());
            out.append(seg.color() < 0 ? part : part.withStyle(style -> style.withColor(TextColor.fromRgb(seg.color())).withItalic(false)));
        }
        return out.withStyle(style -> style.withItalic(false));
    }
}
