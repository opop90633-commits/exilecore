package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.passive.JewelSockets;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 天賦組（2026-10，月卡福利）：每個角色 {@value PlayerState#GEAR_SETS} 組天賦樹，每一組有自己的配點與珠寶孔裡的珠寶。
 * 切換時現在的配點與珠寶收回原本那一組（珠寶還在那一組的孔裡，只是不生效），換成那一組的。切換不花金幣。
 * 沒有月卡也能切回第 1 組（不會卡住珠寶）。
 * <p>
 * 配點存在角色進度 treeset.組.段（規劃代碼切成幾段，一段最多 {@value #CHUNK} 字）；珠寶跟角色存檔一起存（CharacterService 的 tree: 代號）。
 */
public final class TreeSetMenu extends Menu {

    private static final int FIRST = 11;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final int CHUNK = 200;
    private static final int MAX_CHUNKS = 8;

    private final PassiveTree tree;
    private final Runnable back;

    public TreeSetMenu(final ExileCore plugin, final Player player, final PassiveTree tree, final Runnable back) {
        super(plugin, player, 3, "<dark_gray>天賦組（月卡）");
        this.tree = tree;
        this.back = back;
    }

    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    private static String chunkKey(final int set, final int chunk) {
        return "treeset." + set + "." + chunk;
    }

    /** 存著的那一組的規劃代碼；沒存過 null。 */
    private String stored(final PlayerState state, final int set) {
        final StringBuilder code = new StringBuilder();
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            final String part = state.progress().get(chunkKey(set, chunk));
            if (part == null) {
                break;
            }
            code.append(part);
        }
        return code.isEmpty() ? null : code.toString();
    }

    private void store(final PlayerState state, final int set, final String code) {
        int chunk = 0;
        if (code != null) {
            for (int from = 0; from < code.length() && chunk < MAX_CHUNKS; from += CHUNK, chunk++) {
                plugin.characters().setProgress(player, chunkKey(set, chunk), code.substring(from, Math.min(code.length(), from + CHUNK)));
            }
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
        final int active = state.activeTreeSet();
        final boolean card = card();
        set(4, icon(Material.CLOCK, SponsorService.CARD_COLOR + "天賦組",
                "<gray>每一組有自己的配點與珠寶孔裡的珠寶，切換時一起換（不花金幣）",
                "<gray>只有正在用的那一組有效果；其他組的珠寶還在那一組的孔裡，只是不生效",
                "<gray>在天賦樹配點、退點、放珠寶，改的都是正在用的那一組",
                card ? "<green>月卡：可以隨時切換" : "<red>沒有月卡：只能切回第 1 組"));
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final boolean current = set == active;
            final List<String> lore = new ArrayList<>();
            lore.add(current ? "<green>使用中" : "<gray>放著（不生效）");
            final int nodes;
            final int jewels;
            if (current) {
                nodes = state.passives().size();
                jewels = state.jewels().size();
            } else {
                final String code = stored(state, set);
                int count = 0;
                if (code != null) {
                    try {
                        count = TreeCode.decode(tree, code).nodes().size();
                    } catch (final TreeCode.InvalidCode e) {
                        count = -1;
                    }
                }
                nodes = count;
                jewels = state.treeJewels(set).size();
            }
            lore.add(nodes < 0 ? "<red>存的配點讀不出來（天賦樹改版？）" : nodes == 0 ? "<dark_gray>還沒配點" : "<gray>配了 <white>" + nodes + "</white> 點");
            if (jewels > 0) {
                lore.add("<gray>珠寶 <#c77dff>" + jewels + "</#c77dff> 顆");
            }
            lore.add("");
            if (!current) {
                lore.add(card || set == 0 ? "<yellow>左鍵：換成這一組" : "<dark_gray>要有月卡才能換成這一組");
            }
            final ItemStack icon = icon(current ? Material.NETHER_STAR : nodes > 0 ? Material.ENDER_EYE : Material.ENDER_PEARL,
                    (current ? "<green>" : "<white>") + "第 " + (set + 1) + " 組", lore);
            set(FIRST + set, current ? highlight(icon) : icon);
        }
        set(SLOT_BACK, backButton("天賦樹"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            back.run();
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
        final CharacterRecord character = state.character();
        if (character == null) {
            return;
        }
        final int active = state.activeTreeSet();
        if (set == active) {
            return;
        }
        if (!card() && set != 0) {
            fail("要有月卡才能換成第 " + (set + 1) + " 組（沒有月卡可以切回第 1 組）");
            return;
        }
        // 要換上的那一組：讀得出來、是這個職業的、點數夠、連得到起點
        final PassiveNode start = tree.start(character.characterClass().id());
        if (start == null) {
            fail("天賦樹沒有這個職業的起點");
            return;
        }
        final String code = stored(state, set);
        Set<String> nodes = new HashSet<>();
        if (code != null) {
            try {
                final TreeCode.Decoded decoded = TreeCode.decode(tree, code);
                if (!decoded.classId().equals(character.characterClass().id())) {
                    fail("第 " + (set + 1) + " 組是別的職業的配點");
                    return;
                }
                nodes = new HashSet<>(decoded.nodes());
            } catch (final TreeCode.InvalidCode e) {
                fail("第 " + (set + 1) + " 組的配點讀不出來（天賦樹改版？）");
                return;
            }
        }
        final int total = plugin.players().availablePoints(state) + state.passives().size();
        if (nodes.size() > total) {
            fail("第 " + (set + 1) + " 組配了 " + nodes.size() + " 點，你現在只有 " + total + " 點");
            return;
        }
        if (tree.reachable(nodes, start, tw.exilecore.passive.PassiveState.rules(plugin, state)).size() != nodes.size()) {
            fail("第 " + (set + 1) + " 組的配點連不到起點（天賦樹改版？）");
            return;
        }
        // 現在的收進原本那一組
        store(state, active, TreeCode.encode(tree, character.characterClass().id(), state.passives()));
        final Map<String, ItemStack> current = new LinkedHashMap<>(state.jewels());
        final ItemStack phylactery = current.remove(JewelSockets.PHYLACTERY); // 2026-10-27：命匣是昇華天賦的孔，不跟著天賦組換
        state.jewels().clear();
        state.treeJewels(active).clear();
        state.treeJewels(active).putAll(current);
        // 換上那一組
        state.passives().clear();
        state.passives().addAll(nodes);
        state.jewels().putAll(state.treeJewels(set));
        state.treeJewels(set).clear();
        if (phylactery != null) {
            state.jewels().put(JewelSockets.PHYLACTERY, phylactery);
        }
        plugin.characters().setProgress(player, PlayerState.TREE_SET_KEY, String.valueOf(set));
        JewelSockets.reclaim(plugin, player, state, tree, "那一組的珠寶孔沒有配置");
        state.markDirty();
        plugin.players().sheet(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<green>換成第 " + (set + 1) + " 組天賦（配了 " + nodes.size() + " 點）"));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
        redraw();
    }

    private void fail(final String message) {
        player.sendMessage(plugin.text().prefixed("<red>" + message));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
    }
}
