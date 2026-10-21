package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 星圖天賦組（2026-10-21，月卡福利；跟裝備組、天賦組一樣 {@value PlayerState#GEAR_SETS} 組）：每一組有自己的星圖天賦配點，
 * 切換時現在的配點收回原本那一組、換上那一組的（免費）。只有正在用的那一組有效果（之後開的地圖照它）。
 * 沒有月卡也能切回第 1 組。
 * <p>
 * 配點存在角色進度 atlasset.組.段（逗號隔開的代號，一段最多 {@value #CHUNK} 字）；正在用第幾組存在 {@value #ACTIVE_KEY}。
 */
public final class AtlasSetMenu extends Menu {

    public static final String ACTIVE_KEY = "atlassetactive";
    private static final int FIRST = 11;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final int CHUNK = 2000;
    private static final int MAX_CHUNKS = 4;

    private final MapService service;

    public AtlasSetMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 3, "<dark_gray>星圖天賦組（月卡）");
        this.service = service;
    }

    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    static String chunkKey(final int set, final int chunk) {
        return "atlasset." + set + "." + chunk;
    }

    /** 正在用第幾組（從 0 起）。 */
    public static int activeSet(final PlayerState state) {
        final String value = state.progress().get(ACTIVE_KEY);
        try {
            return value == null ? 0 : Math.max(0, Math.min(PlayerState.GEAR_SETS - 1, Integer.parseInt(value.trim())));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 存著的那一組的配點；沒存過是空的。 */
    private List<String> stored(final PlayerState state, final int set) {
        final StringBuilder code = new StringBuilder();
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            final String part = state.progress().get(chunkKey(set, chunk));
            if (part == null) {
                break;
            }
            code.append(part);
        }
        return AtlasTree.parseAllocation(code.toString());
    }

    private void store(final PlayerState state, final int set, final List<String> allocated) {
        final String code = AtlasTree.joinAllocation(allocated);
        int chunk = 0;
        for (int from = 0; from < code.length() && chunk < MAX_CHUNKS; from += CHUNK, chunk++) {
            plugin.characters().setProgress(player, chunkKey(set, chunk), code.substring(from, Math.min(code.length(), from + CHUNK)));
        }
        for (; chunk < MAX_CHUNKS; chunk++) {
            if (state.progress().containsKey(chunkKey(set, chunk))) {
                plugin.characters().removeProgress(player, chunkKey(set, chunk));
            }
        }
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final int active = activeSet(state);
        final boolean card = card();
        final AtlasTree tree = service.tree();
        final int budget = service.atlasEarned(player).total();
        set(4, icon(Material.CLOCK, "<#bcd4ff>星圖天賦組",
                "<gray>每一組有自己的星圖天賦配點，切換免費",
                "<gray>只有正在用的那一組有效果（之後開的地圖照它；開著的不變）",
                "<gray>在星圖天賦樹點的、退的，都是正在用的那一組",
                card ? "<green>月卡：可以隨時切換" : "<red>沒有月卡：只能切回第 1 組"));
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final boolean current = set == active;
            final List<String> allocated = current ? service.atlasAllocated(player) : tree.effective(stored(state, set), budget);
            final List<String> lore = new ArrayList<>();
            lore.add(current ? "<green>使用中" : "<gray>放著（不生效）");
            lore.add(allocated.isEmpty() ? "<dark_gray>還沒配點" : "<gray>配了 <white>" + allocated.size() + "</white> 個天賦（" + tree.spent(allocated) + " 點）");
            final List<String> effects = tree.bonus(allocated).describe();
            for (int i = 0; i < Math.min(5, effects.size()); i++) {
                lore.add("<green>• " + ItemRenderer.escape(effects.get(i)));
            }
            if (effects.size() > 5) {
                lore.add("<dark_gray>…還有 " + (effects.size() - 5) + " 項");
            }
            lore.add("");
            if (!current) {
                lore.add(card || set == 0 ? "<yellow>左鍵：換成這一組" : "<dark_gray>要有月卡才能換成這一組");
            }
            final ItemStack icon = icon(current ? Material.NETHER_STAR : allocated.isEmpty() ? Material.ENDER_PEARL : Material.ENDER_EYE,
                    (current ? "<green>" : "<white>") + "第 " + (set + 1) + " 組", lore);
            set(FIRST + set, current ? highlight(icon) : icon);
        }
        set(SLOT_BACK, backButton("星圖天賦"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            new AtlasTreeMenu(plugin, player, service).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        final int set = slot - FIRST;
        if (set < 0 || set >= PlayerState.GEAR_SETS || click != ClickType.LEFT) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        final int active = activeSet(state);
        if (set == active) {
            return;
        }
        if (!card() && set != 0) {
            fail("要有月卡才能換成第 " + (set + 1) + " 組（沒有月卡可以切回第 1 組）");
            return;
        }
        final List<String> target = stored(state, set);
        final List<String> effective = service.tree().effective(target, service.atlasEarned(player).total());
        // 現在的收進原本那一組，換上那一組（存的照點的順序；點數不夠或樹改了的從最後點的開始不算）
        store(state, active, service.atlasAllocated(player));
        service.replaceAtlas(player, effective);
        plugin.characters().setProgress(player, ACTIVE_KEY, String.valueOf(set));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
        player.sendMessage(plugin.text().prefixed("<#bcd4ff>星圖天賦換成第 " + (set + 1) + " 組<gray>（" + effective.size() + " 個天賦"
                + (effective.size() < target.size() ? "；有 " + (target.size() - effective.size()) + " 個點數不夠或樹改了，沒算" : "") + "）"));
        redraw();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(message)));
    }
}
