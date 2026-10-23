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
 * 注入（PoE2 的蒸餾情緒）：拿著蒸餾情緒點背包裡的項鍊會打開這裡——列出天賦樹所有的核心，每個寫著要哪一種情緒（看離天賦樹中心多遠）、
 * 你有幾顆；買得起的有綠框，點一個就花 3 顆把它注入項鍊（項鍊上原本注入的換掉）。情緒先從背包扣，再從通貨袋扣。
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

    /** 這個核心要的情緒。 */
    private CurrencyType emotionFor(final PassiveNode node) {
        final MapEventConfig.Delirium delirium = plugin.maps() != null ? plugin.maps().eventConfig().delirium() : new MapEventConfig().delirium();
        final String id = delirium.emotion(Crafting.instilTier(node.x(), node.y()));
        return id == null ? null : plugin.currencies().repository().get(id);
    }

    /** 背包＋通貨袋裡有幾顆。 */
    private int owned(final CurrencyType emotion) {
        int count = 0;
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(type -> type.id().equals(emotion.id())).orElse(false)) {
                count += stack.getAmount();
            }
        }
        final PlayerState state = plugin.players().get(player);
        if (state.bag().usable()) {
            count += state.bag().pouch().amount(emotion.id());
        }
        return count;
    }

    private List<PassiveNode> candidates() {
        final List<PassiveNode> list = new ArrayList<>();
        final String needle = query.toLowerCase(Locale.ROOT);
        for (final PassiveNode node : tree.nodes()) {
            if (node.type() != PassiveNode.Type.NOTABLE) {
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
        list.sort(Comparator.comparingInt((PassiveNode node) -> Crafting.instilTier(node.x(), node.y())).thenComparing(PassiveNode::name));
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
            final CurrencyType emotion = emotionFor(node);
            final int have = emotion == null ? 0 : owned(emotion);
            final boolean affordable = emotion != null && have >= Crafting.INSTIL_COST;
            final List<String> lore = new ArrayList<>();
            lore.add("<#f5b942>核心 <dark_gray>（離中心 " + Math.max(Math.abs(node.x()), Math.abs(node.y())) + " 格）");
            for (final String line : node.description()) {
                lore.add("<white>" + ItemRenderer.escape(line));
            }
            lore.add("");
            if (emotion == null) {
                lore.add("<red>設定裡沒有這一階的蒸餾情緒");
            } else {
                lore.add((affordable ? "<green>" : "<red>") + "要 " + Crafting.INSTIL_COST + " 顆" + ItemRenderer.escape(emotion.name())
                        + "<gray>（你有 " + have + "）");
                lore.add(affordable ? "<yellow>左鍵：注入這個核心" : "<dark_gray>幻霧的獎勵會掉蒸餾情緒");
            }
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
        info.add("<gray>離天賦樹中心越遠的核心要越稀有的情緒：");
        final MapEventConfig.Delirium delirium = plugin.maps() != null ? plugin.maps().eventConfig().delirium() : new MapEventConfig().delirium();
        final String[] ranges = {"≤7 格", "8–11 格", "12–15 格", "16 格以上"};
        for (int tier = 1; tier <= 4; tier++) {
            final CurrencyType emotion = delirium.emotion(tier) == null ? null : plugin.currencies().repository().get(delirium.emotion(tier));
            info.add("<dark_gray>" + ranges[tier - 1] + "：<white>" + (emotion == null ? "—" : ItemRenderer.escape(emotion.name()) + " <gray>（你有 " + owned(emotion) + "）"));
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
        final PassiveNode node = tree.node(id);
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
        final CurrencyType emotion = emotionFor(node);
        if (emotion == null) {
            fail("設定裡沒有這一階的蒸餾情緒");
            return;
        }
        if (node.id().equals(data.instilled())) {
            fail("這條項鍊已經注入這個核心了");
            return;
        }
        if (owned(emotion) < Crafting.INSTIL_COST) {
            fail("要 " + Crafting.INSTIL_COST + " 顆" + emotion.name() + "，你有 " + owned(emotion));
            return;
        }
        consume(emotion, Crafting.INSTIL_COST);
        final String before = plugin.items().toJson(data);
        Crafting.instil(data, node.id());
        plugin.items().update(stack, data);
        player.getInventory().setItem(amuletSlot, stack);
        final PlayerState state = plugin.players().get(player);
        plugin.currencies().recordUse(state.hasCharacter() ? state.character().id() : null, player.getName(), data.uuid(), emotion.id(),
                before, plugin.items().toJson(data));
        // 2026-10-18：recordUse 只記 1 顆消耗，注入實際用掉 INSTIL_COST 顆，補記其餘的
        plugin.economy().ledger().consumed(emotion.id(), tw.exilecore.economy.EconomyLedger.CRAFT, Crafting.INSTIL_COST - 1);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.playSound(player.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.9f, 1.3f);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.1f);
        player.sendMessage(plugin.text().prefixed("<#c77dff>注入了「" + ItemRenderer.escape(node.name()) + "」：")
                .append(stack.effectiveName()));
        redraw();
    }

    /** 先扣背包裡的，再扣通貨袋。 */
    private void consume(final CurrencyType emotion, final int amount) {
        int left = amount;
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty() || !plugin.currencies().read(stack).map(type -> type.id().equals(emotion.id())).orElse(false)) {
                continue;
            }
            final int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
            left -= take;
        }
        if (left > 0) {
            final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
            left -= pouch.withdraw(emotion.id(), left);
            plugin.players().get(player).markDirty();
        }
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
