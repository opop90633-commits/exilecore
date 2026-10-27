package tw.exilecore.passive;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.Crafting;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.currency.InstilRecipes;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.map.MapEventConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 注入（PoE2 的蒸餾情緒）：拿著蒸餾情緒點背包裡的項鍊會打開這裡——列出能注入的核心，每個寫著它的配方（三種情緒，照順序）、
 * 你各有幾顆；買得起的有綠框，點一個就照配方花三顆把它注入項鍊（項鍊上原本注入的換掉）。情緒先從背包扣，再從通貨袋扣。
 * 2026-10-27 照 PoE2 0.5：每個核心固定的配方（{@link InstilRecipes}，沒有配方的核心不列）；這次之前每個核心都能注入，
 * 花 3 顆同一種情緒、哪一種看離天賦樹中心多遠。
 */
public final class InstilMenu extends Menu {

    private static final int ROWS = 6;
    private static final int LIST_SIZE = (ROWS - 1) * 9;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;

    private final PassiveTree tree;
    /** 項鍊在玩家背包的第幾格。 */
    private final int amuletSlot;
    /** 項鍊的 UUID（開著選單時換了東西就不做）。 */
    private final String amuletUuid;
    private final String query;
    private int page;
    private final List<PassiveNode> shown = new ArrayList<>();

    public InstilMenu(final ExileCore plugin, final Player player, final PassiveTree tree, final int amuletSlot, final String amuletUuid,
                      final String query) {
        super(plugin, player, ROWS, "<dark_gray>注入項鍊：選一個核心");
        this.tree = tree;
        this.amuletSlot = amuletSlot;
        this.amuletUuid = amuletUuid;
        this.query = query == null ? "" : query.trim();
    }

    private MapEventConfig.Delirium delirium() {
        return plugin.maps() != null ? plugin.maps().eventConfig().delirium() : new MapEventConfig().delirium();
    }

    /** 情緒的名稱（設定裡沒有的顯示代號）。 */
    private String emotionName(final String id) {
        final CurrencyType type = plugin.currencies().repository().get(id);
        return type == null ? id : type.name();
    }

    /** 這個核心的配方裡最稀有的情緒排第幾（排序用；map_events.yml 的 delirium.emotions 由便宜到稀有）。 */
    private int rarity(final PassiveNode node) {
        final List<String> order = delirium().emotions();
        int max = -1;
        final List<String> recipe = InstilRecipes.recipe(node.id());
        if (recipe != null) {
            for (final String id : recipe) {
                max = Math.max(max, order.indexOf(id));
            }
        }
        return max;
    }

    /** 背包＋通貨袋裡有幾顆。 */
    private int owned(final String emotionId) {
        int count = 0;
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(type -> type.id().equals(emotionId)).orElse(false)) {
                count += stack.getAmount();
            }
        }
        final PlayerState state = plugin.players().get(player);
        if (state.bag().usable()) {
            count += state.bag().pouch().amount(emotionId);
        }
        return count;
    }

    private List<PassiveNode> candidates() {
        final List<PassiveNode> list = new ArrayList<>();
        final String needle = query.toLowerCase(Locale.ROOT);
        // 2026-10-27 照 PoE2 0.5：天賦樹上的核心加上塗油限定的核心（PassiveTree.anointable）；沒有配方的不能注入
        for (final PassiveTree.Anointable anointable : tree.anointable()) {
            final PassiveNode node = anointable.node();
            if (InstilRecipes.recipe(node.id()) == null) {
                continue;
            }
            if (!needle.isEmpty()) {
                boolean hit = node.name().toLowerCase(Locale.ROOT).contains(needle);
                for (final String line : node.description()) {
                    hit |= line.toLowerCase(Locale.ROOT).contains(needle);
                }
                if (!hit) {
                    continue;
                }
            }
            list.add(node);
        }
        list.sort(Comparator.comparingInt(this::rarity).thenComparing(PassiveNode::name));
        return list;
    }

    @Override
    protected void draw() {
        shown.clear();
        final List<PassiveNode> all = candidates();
        final int pages = Math.max(1, (all.size() + LIST_SIZE - 1) / LIST_SIZE);
        page = Math.max(0, Math.min(pages - 1, page));
        final ItemData amulet = amulet();
        final String current = amulet == null ? null : amulet.instilled();
        for (int i = 0; i < LIST_SIZE; i++) {
            final int index = page * LIST_SIZE + i;
            if (index >= all.size()) {
                set(i, null);
                continue;
            }
            final PassiveNode node = all.get(index);
            shown.add(node);
            final boolean affordable = Crafting.instilProblem(node.id(), this::owned, this::emotionName) == null;
            final List<String> lore = new ArrayList<>();
            lore.add(tree.isAnointOnly(node.id()) ? "<#c77dff>塗油限定核心 <dark_gray>（天賦樹上沒有，只能注入）" : "<#f5b942>核心");
            for (final String line : node.description()) {
                lore.add("<white>" + ItemRenderer.escape(line));
            }
            lore.add("");
            lore.add("<gray>配方（照順序）：");
            for (final String id : InstilRecipes.recipe(node.id())) {
                lore.add("<dark_gray>· <white>" + ItemRenderer.escape(emotionName(id)));
            }
            for (final java.util.Map.Entry<String, Integer> entry : InstilRecipes.cost(node.id()).entrySet()) {
                final int have = owned(entry.getKey());
                lore.add((have >= entry.getValue() ? "<green>" : "<red>") + "要 " + entry.getValue() + " 顆"
                        + ItemRenderer.escape(emotionName(entry.getKey())) + "<gray>（你有 " + have + "）");
            }
            lore.add(affordable ? "<yellow>左鍵：注入這個核心" : "<dark_gray>幻霧的獎勵會掉蒸餾情緒");
            if (node.id().equals(current)) {
                lore.add("<#c77dff>這條項鍊現在注入的就是它");
            } else if (current != null) {
                lore.add("<dark_gray>會換掉現在注入的核心");
            }
            final ItemStack icon = icon(material(node.icon(), Material.ENCHANTED_BOOK), "<#c77dff>" + ItemRenderer.escape(node.name()), lore);
            set(i, node.id().equals(current) ? highlight(icon) : affordable ? targetHighlight(icon) : icon);
        }
        for (int slot = LIST_SIZE; slot < ROWS * 9; slot++) {
            set(slot, filler());
        }
        set(SLOT_PREV, icon(Material.ARROW, "<white>上一頁", "<gray>第 " + (page + 1) + "／" + pages + " 頁"));
        set(SLOT_NEXT, icon(Material.ARROW, "<white>下一頁", "<gray>第 " + (page + 1) + "／" + pages + " 頁"));
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<#f5b942>搜尋核心",
                query.isEmpty() ? "<gray>名稱或說明，例如「生命」、「暴擊」" : "<gray>目前：<white>" + ItemRenderer.escape(query),
                "", "<yellow>左鍵：輸入關鍵字", query.isEmpty() ? "" : "<yellow>右鍵：清掉"));
        final List<String> info = new ArrayList<>();
        info.add("<gray>項鍊：" + (amulet == null ? "<red>不見了" : "<white>" + ItemRenderer.escape(amuletName(amulet))));
        info.add(current == null ? "<gray>目前沒有注入" : "<gray>目前注入：<#c77dff>" + ItemRenderer.escape(nodeName(current)));
        info.add("");
        info.add("<gray>每個核心有固定的三種情緒配方（同一種可以重複）");
        info.add("<gray>你的情緒（便宜 → 稀有）：");
        for (final String id : delirium().emotions()) {
            info.add("<dark_gray>· <white>" + ItemRenderer.escape(emotionName(id)) + " <gray>" + owned(id));
        }
        info.add("");
        info.add("<gray>一條項鍊只能注入一個核心，再注入就換掉");
        info.add("<gray>注入的效果跟天賦一樣直接加在身上，/stats 看得到");
        set(SLOT_INFO, icon(Material.GLASS_BOTTLE, "<#c77dff>注入", info));
        set(SLOT_CLOSE, closeButton());
    }

    private String amuletName(final ItemData data) {
        return tw.exilecore.item.ItemStats.displayName(plugin.items().repository(), data);
    }

    private String nodeName(final String id) {
        final PassiveNode node = tree.anointNode(id);
        return node == null ? id : node.name();
    }

    /** 那一格還是同一條項鍊嗎；是的話回傳它的資料。 */
    private ItemData amulet() {
        final ItemStack stack = player.getInventory().getItem(amuletSlot);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || !amuletUuid.equals(data.get().uuid())) {
            return null;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        return base == null || base.slot() != ItemSlot.AMULET ? null : data.get();
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_CLOSE -> goBack();
            case SLOT_PREV -> {
                page--;
                redraw();
            }
            case SLOT_NEXT -> {
                page++;
                redraw();
            }
            case SLOT_SEARCH -> {
                if (click.isRightClick()) {
                    new InstilMenu(plugin, player, tree, amuletSlot, amuletUuid, "").open();
                } else {
                    plugin.dialogs().text(player, "<dark_gray>搜尋核心", List.of("<gray>名稱或說明含這段文字的核心", "<dark_gray>例如「生命」、「暴擊」、「火焰」"),
                            "關鍵字", query, 30, text -> new InstilMenu(plugin, player, tree, amuletSlot, amuletUuid, text).open(),
                            () -> new InstilMenu(plugin, player, tree, amuletSlot, amuletUuid, query).open());
                }
            }
            default -> {
                if (slot < LIST_SIZE && slot < shown.size() && acceptAction(click)) {
                    instil(shown.get(slot));
                }
            }
        }
    }

    private void instil(final PassiveNode node) {
        final ItemStack stack = player.getInventory().getItem(amuletSlot);
        final ItemData data = amulet();
        if (data == null || stack == null) {
            fail("項鍊不在原來的格子了，重新拿蒸餾情緒點它");
            player.closeInventory();
            return;
        }
        if (!data.identified()) {
            fail("要先鑑定");
            return;
        }
        if (data.mirrored() || data.corrupted()) {
            fail(data.mirrored() ? "鏡像複製品不能再改造" : "已腐化的物品不能再改造");
            return;
        }
        if (node.id().equals(data.instilled())) {
            fail("這條項鍊已經注入這個核心了");
            return;
        }
        final String problem = Crafting.instilProblem(node.id(), this::owned, this::emotionName);
        if (problem != null) {
            fail(problem);
            return;
        }
        final java.util.Map<String, Integer> cost = InstilRecipes.cost(node.id());
        for (final java.util.Map.Entry<String, Integer> entry : cost.entrySet()) {
            consume(entry.getKey(), entry.getValue());
        }
        final String before = plugin.items().toJson(data);
        Crafting.instil(data, node.id());
        plugin.items().update(stack, data);
        player.getInventory().setItem(amuletSlot, stack);
        final PlayerState state = plugin.players().get(player);
        // 2026-10-27：配方可能有好幾種情緒——第一種記使用紀錄（recordUse 記 1 顆消耗），其餘照實際用掉的補記消耗
        boolean first = true;
        for (final java.util.Map.Entry<String, Integer> entry : cost.entrySet()) {
            if (first) {
                plugin.currencies().recordUse(state.hasCharacter() ? state.character().id() : null, player.getName(), data.uuid(), entry.getKey(),
                        before, plugin.items().toJson(data));
            }
            final int extra = entry.getValue() - (first ? 1 : 0);
            if (extra > 0) {
                plugin.economy().ledger().consumed(entry.getKey(), tw.exilecore.economy.EconomyLedger.CRAFT, extra);
            }
            first = false;
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.9f, 1.3f);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.1f);
        player.sendMessage(plugin.text().prefixed("<#c77dff>注入了「" + ItemRenderer.escape(node.name()) + "」：")
                .append(stack.effectiveName()));
        redraw();
    }

    /** 先扣背包裡的，再扣通貨袋。 */
    private void consume(final String emotionId, final int amount) {
        int left = amount;
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty() || !plugin.currencies().read(stack).map(type -> type.id().equals(emotionId)).orElse(false)) {
                continue;
            }
            final int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
            left -= take;
        }
        if (left > 0) {
            final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
            left -= pouch.withdraw(emotionId, left);
            plugin.players().get(player).markDirty();
        }
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
