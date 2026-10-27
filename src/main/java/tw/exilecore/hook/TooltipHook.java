package tw.exilecore.hook;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.skill.GemData;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.skill.SkillService;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Recently;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑鼠指到物品時「對你的技能」的提示（2026-10，要裝 PacketEvents）：裝備、珠寶列出吃得到的技能與詞綴，
 * 輔助寶石列出技能面板上它能輔助的技能。
 * <p>
 * 物品本身不改：只在送給這個玩家的封包裡多加幾行說明（{@link PacketTooltips}），每個人看到的是自己的技能。
 * 技能面板的資料在主執行緒每 5 秒算一次存起來（封包在網路執行緒，只讀這份）；變了就重送一次背包讓說明更新。
 * 沒裝 PacketEvents 時整個功能不啟用（這個類別不碰任何 PacketEvents 的型別，ExileCore 可以放心呼叫）。
 * <p>
 * 2026-10：預設收著（只有一行「對你的 N 個技能有效」）；在任何背包、箱子裡對著物品按 F（換到副手的鍵）展開、再按一次收起來。
 * 背包裡的 F 本來就被擋掉（副手是顯示用的複本），所以不會影響別的操作；每個人各自記，下線就回到收著。
 */
public final class TooltipHook {

    /** 最多列幾條詞綴（說明框太長會被畫面切掉）。 */
    static final int MAX_MODS = 4;

    private static final Map<UUID, List<SkillService.SkillHint>> HINTS = new ConcurrentHashMap<>();
    /** 展開「對你的技能」說明的人（2026-10；按 F 切換）。 */
    private static final java.util.Set<UUID> EXPANDED = ConcurrentHashMap.newKeySet();
    private static volatile ExileCore plugin;
    private static Object listener;

    private TooltipHook() {
    }

    /** 有裝 PacketEvents 就啟用；回傳有沒有啟用。 */
    public static boolean start(final ExileCore core) {
        if (!core.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            core.getSLF4JLogger().info("物品的「對你的技能」提示：沒有裝 PacketEvents，不啟用");
            return false;
        }
        plugin = core;
        try {
            listener = PacketTooltips.register();
        } catch (final Throwable e) { // 版本對不上（NoSuchMethodError 這類）：不影響其他功能
            core.getSLF4JLogger().warn("物品的「對你的技能」提示：PacketEvents 註冊失敗，不啟用", e);
            plugin = null;
            return false;
        }
        core.repeat("tooltips", TooltipHook::refresh, 40, 100);
        core.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(final PlayerQuitEvent event) {
                HINTS.remove(event.getPlayer().getUniqueId());
                EXPANDED.remove(event.getPlayer().getUniqueId());
            }

            /** 對著有「對你的技能」說明的物品按 F：展開／收起（不換到副手）。 */
            @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
            public void onSwapKey(final org.bukkit.event.inventory.InventoryClickEvent event) {
                if (event.getClick() != org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                        || !(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
                    return;
                }
                final ItemStack current = event.getCurrentItem();
                final List<SkillService.SkillHint> hints = HINTS.get(player.getUniqueId());
                if (current == null || current.isEmpty() || hints == null || hints.isEmpty()) {
                    return;
                }
                try {
                    if (lines(core, hints, current, true).isEmpty()) {
                        return; // 沒有說明的物品照原本的處理
                    }
                } catch (final RuntimeException e) {
                    return;
                }
                event.setCancelled(true);
                if (!EXPANDED.remove(player.getUniqueId())) {
                    EXPANDED.add(player.getUniqueId());
                }
                core.getServer().getScheduler().runTask(core, () -> {
                    if (player.isOnline()) {
                        player.updateInventory(); // 重送一次，說明換成展開／收起的樣子
                    }
                });
            }
        }, core);
        core.getSLF4JLogger().info("物品的「對你的技能」提示：已啟用（PacketEvents）");
        return true;
    }

    /** 關閉。 */
    public static void stop() {
        if (listener != null) {
            try {
                PacketTooltips.unregister(listener);
            } catch (final Throwable ignored) {
                // PacketEvents 已經先關了
            }
            listener = null;
        }
        plugin = null;
        HINTS.clear();
    }

    /** 主執行緒：每個人的技能面板重算一次；變了就重送背包（說明跟著更新）。 */
    private static void refresh() {
        final ExileCore core = plugin;
        if (core == null || core.skills() == null) {
            return;
        }
        for (final Player player : core.getServer().getOnlinePlayers()) {
            final List<SkillService.SkillHint> fresh;
            try {
                fresh = List.copyOf(core.skills().skillHints(player));
            } catch (final RuntimeException e) {
                continue;
            }
            final List<SkillService.SkillHint> old = HINTS.put(player.getUniqueId(), fresh);
            if (old != null && !same(old, fresh)) {
                player.updateInventory();
            }
        }
    }

    private static boolean same(final List<SkillService.SkillHint> a, final List<SkillService.SkillHint> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id()) || !a.get(i).context().equals(b.get(i).context())) {
                return false;
            }
        }
        return true;
    }

    /** 網路執行緒：這個物品給這個玩家看時要多加的說明（不用加回傳空的）。 */
    static List<Component> extraLore(final Player viewer, final ItemStack stack) {
        final ExileCore core = plugin;
        if (core == null || viewer == null || stack == null || stack.isEmpty() || viewer.getGameMode() == GameMode.CREATIVE) {
            return List.of(); // 創造模式的物品會整個傳回伺服器，加了就會被寫進物品
        }
        final List<SkillService.SkillHint> hints = HINTS.get(viewer.getUniqueId());
        if (hints == null || hints.isEmpty()) {
            return List.of();
        }
        try {
            final List<String> lines = lines(core, hints, stack, EXPANDED.contains(viewer.getUniqueId()));
            if (lines.isEmpty()) {
                return List.of();
            }
            final List<Component> out = new ArrayList<>();
            out.add(Component.empty());
            out.addAll(tw.exilecore.core.text.TextWrap.lines(lines)); // 2026-10-26：長行自動換行
            return out;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    /** 一行最多放幾個技能名稱（說明框太寬會超出畫面）。 */
    static final int NAMES_PER_ROW = 4;

    /** 名稱用「、」串起來，每 perRow 個換一行（換行的那行最後留「、」表示還有）。 */
    static List<String> wrap(final List<String> names, final int perRow) {
        final List<String> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i += perRow) {
            final boolean last = i + perRow >= names.size();
            rows.add(String.join("、", names.subList(i, Math.min(names.size(), i + perRow))) + (last ? "" : "、"));
        }
        if (rows.isEmpty()) {
            rows.add("");
        }
        return rows;
    }

    static List<String> lines(final ExileCore core, final List<SkillService.SkillHint> hints, final ItemStack stack) {
        return lines(core, hints, stack, true);
    }

    /** expanded = false：只留第一行（幾個技能），後面寫「按 F 展開」。 */
    static List<String> lines(final ExileCore core, final List<SkillService.SkillHint> hints, final ItemStack stack, final boolean expanded) {
        final List<String> full = fullLines(core, hints, stack);
        if (full.isEmpty() || full.size() == 1 && full.getFirst().indexOf('：') < 0) {
            return full; // 只有一行（「技能面板上沒有它能輔助的技能」）：不用收
        }
        if (!expanded) {
            return List.of(stripColon(full.getFirst()) + " <dark_gray>（按 F 展開）");
        }
        final List<String> out = new ArrayList<>(full);
        out.add("<dark_gray>　按 F 收起");
        return out;
    }

    /** 收起時第一行的「：」與後面接的名字拿掉（輔助寶石那一行後面直接接技能名字）。 */
    private static String stripColon(final String line) {
        final int colon = line.indexOf('：');
        return colon < 0 ? line : line.substring(0, colon);
    }

    private static List<String> fullLines(final ExileCore core, final List<SkillService.SkillHint> hints, final ItemStack stack) {
        final List<String> lines = new ArrayList<>();
        final Optional<tw.exilecore.item.ItemData> data = core.items().read(stack);
        if (data.isPresent()) {
            final ItemBase base = core.items().repository().base(data.get().base());
            if (base == null || base.isMap() || base.slot() == ItemSlot.FLASK || base.slot() == ItemSlot.CHARM) {
                return lines;
            }
            final List<Modifier> mods = new ArrayList<>();
            for (final Modifier raw : ItemStats.modifiers(core.items().repository(), data.get())) {
                mods.add(Recently.hasCondition(raw.stat())
                        ? new Modifier(Recently.stat(raw.stat()), raw.type(), raw.value(), raw.tags(), raw.source()) : raw);
            }
            // 2026-10：說明框太長會被畫面切掉 → 照「詞綴」合併：每條詞綴一行，後面列吃得到它的技能
            final java.util.Map<String, List<String>> byMod = new java.util.LinkedHashMap<>();
            for (final SkillService.SkillHint hint : hints) {
                for (final Modifier mod : mods) {
                    if (!SkillService.gearAffects(hint.definition(), hint.context(), mod)) {
                        continue;
                    }
                    final String text = ModifierText.describe(mod);
                    if (text == null || text.isBlank() || text.startsWith("特殊效果")) {
                        continue; // 說不清楚的不列
                    }
                    final List<String> names = byMod.computeIfAbsent(text, key -> new ArrayList<>());
                    if (!names.contains(hint.name())) {
                        names.add(hint.name());
                    }
                }
            }
            if (byMod.isEmpty()) {
                return lines;
            }
            final List<java.util.Map.Entry<String, List<String>>> sorted = new ArrayList<>(byMod.entrySet());
            sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
            final java.util.Set<String> skills = new java.util.LinkedHashSet<>();
            byMod.values().forEach(skills::addAll);
            lines.add("<#e7c56b>◆ 對你的 " + skills.size() + " 個技能有效：");
            for (int i = 0; i < Math.min(MAX_MODS, sorted.size()); i++) {
                // 2026-10：技能全部列出來（以前超過 3 個寫「等 12 個」看不到是哪些）；太長的換行接著寫
                final List<String> rows = wrap(sorted.get(i).getValue(), NAMES_PER_ROW);
                lines.add("<white>　" + ItemRenderer.escape(sorted.get(i).getKey()) + " <dark_gray>→ <gray>" + ItemRenderer.escape(rows.getFirst()));
                for (int r = 1; r < rows.size(); r++) {
                    lines.add("<gray>　　　" + ItemRenderer.escape(rows.get(r)));
                }
            }
            if (sorted.size() > MAX_MODS) {
                lines.add("<dark_gray>　…還有 " + (sorted.size() - MAX_MODS) + " 條詞綴（技能面板 Shift＋右鍵看全部）");
            }
            return lines;
        }
        if (core.skills() == null) {
            return lines;
        }
        final Optional<GemData> gem = core.skills().gems().read(stack);
        if (gem.isEmpty() || gem.get().kind() != GemData.Kind.SUPPORT) {
            return lines;
        }
        final SupportDefinition support = core.skills().config().support(gem.get().id());
        if (support == null) {
            return lines;
        }
        final List<String> names = new ArrayList<>();
        for (final SkillService.SkillHint hint : hints) {
            if (support.supports(hint.definition())) {
                names.add(hint.name());
            }
        }
        if (names.isEmpty()) {
            lines.add("<#e7c56b>◆ <gray>你技能面板上沒有它能輔助的技能");
        } else {
            final List<String> rows = wrap(names, NAMES_PER_ROW + 1);
            lines.add("<#e7c56b>◆ 可以輔助你的 " + names.size() + " 個：<white>" + ItemRenderer.escape(rows.getFirst()));
            for (int r = 1; r < rows.size(); r++) {
                lines.add("<white>　" + ItemRenderer.escape(rows.get(r)));
            }
        }
        return lines;
    }
}
