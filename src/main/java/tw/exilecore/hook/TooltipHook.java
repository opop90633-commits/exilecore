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
 */
public final class TooltipHook {

    /** 一個物品最多列幾個技能。 */
    static final int MAX_SKILLS = 6;
    /** 一個技能最多列幾條詞綴。 */
    static final int MAX_MODS = 3;

    private static final Map<UUID, List<SkillService.SkillHint>> HINTS = new ConcurrentHashMap<>();
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
            final List<String> lines = lines(core, hints, stack);
            if (lines.isEmpty()) {
                return List.of();
            }
            final List<Component> out = new ArrayList<>();
            out.add(Component.empty());
            for (final String line : lines) {
                out.add(ItemRenderer.line(line));
            }
            return out;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    static List<String> lines(final ExileCore core, final List<SkillService.SkillHint> hints, final ItemStack stack) {
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
            int shown = 0;
            int more = 0;
            for (final SkillService.SkillHint hint : hints) {
                final List<Modifier> useful = new ArrayList<>();
                for (final Modifier mod : mods) {
                    if (SkillService.gearAffects(hint.definition(), hint.context(), mod)) {
                        useful.add(mod);
                    }
                }
                if (useful.isEmpty()) {
                    continue;
                }
                if (shown >= MAX_SKILLS) {
                    more++;
                    continue;
                }
                final List<String> texts = ModifierText.describeAll(useful);
                final String joined = String.join("、", texts.subList(0, Math.min(MAX_MODS, texts.size())))
                        + (texts.size() > MAX_MODS ? "…等 " + texts.size() + " 條" : "");
                lines.add("<white>　" + ItemRenderer.escape(hint.name()) + "<gray>：" + ItemRenderer.escape(joined));
                shown++;
            }
            if (!lines.isEmpty()) {
                lines.add(0, "<#e7c56b>◆ 對你的技能有效：");
                if (more > 0) {
                    lines.add("<dark_gray>　…還有 " + more + " 個技能");
                }
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
            final List<String> head = names.subList(0, Math.min(8, names.size()));
            lines.add("<#e7c56b>◆ 可以輔助你的：<white>" + ItemRenderer.escape(String.join("、", head))
                    + (names.size() > head.size() ? "<gray> 等 " + names.size() + " 個" : ""));
        }
        return lines;
    }
}
