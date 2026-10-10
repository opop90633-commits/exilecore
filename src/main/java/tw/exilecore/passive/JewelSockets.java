package tw.exilecore.passive;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemStats;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 天賦樹的珠寶孔（PoE2 的珠寶插槽）：把背包裡的珠寶放進配了的珠寶孔、拿出來，以及把放在沒配置孔位裡的珠寶還給玩家。
 * 珠寶的詞綴跟天賦一樣直接加在角色身上（{@link #modifiers}），沒有需求。珠寶存在 {@link PlayerState#jewels()}，
 * 跟著角色存檔（character_inventory.jewels）。
 */
public final class JewelSockets {

    private JewelSockets() {
    }

    /** 這顆東西能不能放進珠寶孔；可以回傳 null，不行回傳原因（給玩家看）。 */
    public static String problem(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "這不是珠寶";
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return "這不是珠寶";
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        if (base == null || !base.isJewel()) {
            return "這不是珠寶（珠寶是赤星晶、翠星晶、蒼星晶、白星晶）";
        }
        if (!data.get().identified()) {
            return "要先鑑定（找鑑定師或用鑑定卷軸）";
        }
        return null;
    }

    /** 是不是我們的珠寶（鑑定與否不管）。 */
    public static boolean isJewel(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return false;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        return base != null && base.isJewel();
    }

    /**
     * 把一顆珠寶放進孔位。呼叫端負責把珠寶從背包拿掉（成功才拿）。
     *
     * @return 成功，或失敗的原因
     */
    public static PassiveState.Result socket(final ExileCore plugin, final Player player, final PassiveNode node, final ItemStack stack) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return PassiveState.Result.fail("先建立角色（/char）");
        }
        if (!node.isSocket()) {
            return PassiveState.Result.fail("這裡不是珠寶孔");
        }
        if (!state.passives().contains(node.id())) {
            return PassiveState.Result.fail("要先配這個珠寶孔（左鍵，1 點）");
        }
        if (state.jewel(node.id()) != null) {
            return PassiveState.Result.fail("這個孔已經有珠寶了，先點它拿出來");
        }
        final String problem = problem(plugin, stack);
        if (problem != null) {
            return PassiveState.Result.fail(problem);
        }
        // 2026-10：傳奇珠寶一次只能配一顆
        if (isUnique(plugin, stack)) {
            for (final ItemStack placed : state.jewels().values()) {
                if (isUnique(plugin, placed)) {
                    return PassiveState.Result.fail("傳奇珠寶只能配一顆（天賦樹上已經有一顆了）");
                }
            }
        }
        final ItemStack one = stack.clone();
        one.setAmount(1);
        state.setJewel(node.id(), one);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    private static boolean isUnique(final ExileCore plugin, final ItemStack stack) {
        return stack != null && plugin.items().read(stack).map(data -> data.rarity() == tw.exilecore.item.Rarity.UNIQUE).orElse(false);
    }

    /** 把孔位裡的珠寶拿回背包（背包滿就放隨身背包，都滿就不拿）。 */
    public static PassiveState.Result unsocket(final ExileCore plugin, final Player player, final PassiveNode node) {
        final PlayerState state = plugin.players().get(player);
        final ItemStack jewel = state.jewel(node.id());
        if (jewel == null) {
            return PassiveState.Result.fail("這個孔是空的");
        }
        if (plugin.loot() != null && !plugin.loot().fits(player, state, jewel)) {
            return PassiveState.Result.fail("背包與隨身背包都滿了，先清出一格");
        }
        state.setJewel(node.id(), null);
        giveBack(plugin, player, state, jewel);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    /**
     * 把放在「沒配置、不存在或已經不是珠寶孔」的節點裡的珠寶還給玩家（退點、匯入規劃、樹改版、換職業、登入時檢查）。
     *
     * @param reason 給玩家看的原因（null 不提示）
     * @return 還了幾顆
     */
    public static int reclaim(final ExileCore plugin, final Player player, final PlayerState state, final PassiveTree tree, final String reason) {
        if (state.jewels().isEmpty() || tree == null) {
            return 0; // 樹沒載入（資料檔壞了）：珠寶先留在原位，不要亂還
        }
        final List<String> orphaned = new ArrayList<>();
        for (final String nodeId : state.jewels().keySet()) {
            final PassiveNode node = tree.node(nodeId);
            if (node == null || !node.isSocket() || !state.passives().contains(nodeId)) {
                orphaned.add(nodeId);
            }
        }
        for (final String nodeId : orphaned) {
            final ItemStack jewel = state.jewels().remove(nodeId);
            if (jewel != null) {
                giveBack(plugin, player, state, jewel);
            }
        }
        if (!orphaned.isEmpty()) {
            state.markDirty();
            if (reason != null) {
                player.sendMessage(plugin.text().prefixed("<yellow>" + reason + "：" + orphaned.size() + " 顆珠寶已拿回背包"));
            }
        }
        return orphaned.size();
    }

    /** 先放背包、再放隨身背包，都滿了掉在腳下（跟掉落一樣）。 */
    private static void giveBack(final ExileCore plugin, final Player player, final PlayerState state, final ItemStack stack) {
        ItemStack rest = stack;
        if (plugin.loot() != null) {
            rest = plugin.loot().stow(player, state, stack);
        } else {
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            rest = leftover.isEmpty() ? null : leftover.values().iterator().next();
        }
        if (rest != null && !rest.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            player.sendMessage(plugin.text().prefixed("<red>背包滿了，珠寶掉在你腳下"));
        }
    }

    /** 放在有配置的珠寶孔裡的珠寶給角色的修飾器（未鑑定的珠寶什麼都沒有，但放不進去）。 */
    public static List<Modifier> modifiers(final ExileCore plugin, final PlayerState state, final PassiveTree tree) {
        if (state.jewels().isEmpty() || tree == null || plugin.items() == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        boolean uniqueSeen = false;
        for (final Map.Entry<String, ItemStack> entry : state.jewels().entrySet()) {
            final PassiveNode node = tree.node(entry.getKey());
            if (node == null || !node.isSocket() || !state.passives().contains(entry.getKey())) {
                continue;
            }
            if (isUnique(plugin, entry.getValue())) {
                if (uniqueSeen) {
                    continue; // 以前放進去的第二顆傳奇珠寶不算
                }
                uniqueSeen = true;
            }
            list.addAll(plugin.items().modifiers(entry.getValue()));
        }
        return list;
    }

    /** 顯示用：孔位裡那顆珠寶的名字（帶稀有度顏色）與詞綴行；孔位空的回傳空清單。 */
    public static List<String> describe(final ExileCore plugin, final ItemStack jewel) {
        final List<String> lines = new ArrayList<>();
        if (jewel == null) {
            return lines;
        }
        final Optional<ItemData> data = plugin.items().read(jewel);
        if (data.isEmpty()) {
            return lines;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        lines.add("<" + data.get().rarity().color() + ">" + ItemRenderer.escape(ItemStats.displayName(plugin.items().repository(), data.get()))
                + (base != null && base.jewelColor() != null ? " <" + base.jewelColor() + ">◆" : ""));
        for (final ItemStats.AffixLine line : ItemStats.affixLines(plugin.items().repository(), data.get())) {
            lines.add("<white>" + ItemRenderer.escape(line.ranged()) + " <dark_gray>(T" + line.tier() + ")");
        }
        return lines;
    }

    /** 孔位裡珠寶的顏色（#rrggbb）；空的回傳 null。 */
    public static String color(final ExileCore plugin, final ItemStack jewel) {
        if (jewel == null) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(jewel);
        if (data.isEmpty()) {
            return null;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        return base == null ? null : base.jewelColor();
    }
}
