package tw.exilecore.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.web.UiPack;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱子式選單的基底：一個 Inventory、一個觀看的玩家、點擊分派。
 * 所有點擊預設取消（玩家拿不走選單裡的物品）；子類別覆寫 {@link #onClick} 處理上方選單的點擊、
 * {@link #onBottomClick} 處理玩家自己背包的點擊（預設也取消）。
 * <p>
 * 倉庫這種「可以放東西進去」的選單覆寫 {@link #allowsItemMovement()} 回傳 true，
 * 點擊與拖曳就不會預設取消，改由 {@link #handleRawClick} 與 {@link #handleDrag} 自己決定。
 * <p>
 * 統一版面（2026-10，選單重新設計；六排的選單）：最上面一排是分頁（{@link #tabSlots}、{@link #tab}），
 * 中間四排是內容（每一排最左邊一格是這一排在做什麼的標籤，{@link #rowLabel}），最下面一排是固定的功能鍵：
 * 最左邊「← 返回」、中間「說明」（兩邊是上一頁、下一頁）、最右邊「關閉」，其他格放這個選單自己的動作。
 */
public abstract class Menu implements InventoryHolder {

    /** 統一版面：內容從第二排開始（第一排是分頁）。 */
    public static final int CONTENT_START = 9;
    /** 統一版面：內容到第五排為止（不含最下面一排）。 */
    public static final int CONTENT_END = 45;
    /** 統一版面的功能鍵：返回、上一頁、說明、下一頁、關閉。 */
    public static final int SLOT_BACK = 45;
    public static final int SLOT_PREV = 48;
    public static final int SLOT_HELP = 49;
    public static final int SLOT_NEXT = 50;
    public static final int SLOT_CLOSE_BAR = 53;

    /** 「真的做事」的按鈕（買、下架、取消掛單…）兩次之間至少隔多久。 */
    private static final long ACTION_GAP_MILLIS = 250;
    /** 選取外框的物品模型與顏色：金色＝選到的、目前這一頁；綠色＝拿著的東西可以放這裡。 */
    private static final Key HIGHLIGHT_MODEL = Key.key(UiPack.HIGHLIGHT);
    private static final Color FRAME_GOLD = Color.fromRGB(0xFFC83C);
    private static final Color FRAME_GREEN = Color.fromRGB(0x5CFF5C);
    private static final Color FRAME_RED = Color.fromRGB(0xFF5555);
    /** 介面小圖示的物品模型（{@link UiPack#ICONS}）。 */
    private static final Key UI_ICON_MODEL = Key.key(UiPack.UI_ICON);

    protected final ExileCore plugin;
    protected final Player player;
    private final Inventory inventory;
    private long lastAction;

    @SuppressWarnings("this-escape") // Bukkit 需要在建立 Inventory 時就拿到 holder
    protected Menu(final ExileCore plugin, final Player player, final int rows, final String title) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, rows * 9, plugin.text().parse(title));
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    /** 畫一次內容再打開。延後到下一個 tick，讓「在選單裡點按鈕開另一個選單」也安全。 */
    public void open() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isConnected()) {
                return; // 下線了（或這個玩家物件是上一次登入的）
            }
            // 目前開著可以放東西的選單（倉庫）：先關掉讓它把內容同步回去，再用最新的資料畫新的選單
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu current && current.allowsItemMovement()) {
                player.closeInventory();
            }
            draw();
            player.openInventory(inventory);
            // 真的打開了才算（其他插件可以取消開啟事件）；比對 holder 而不是 Inventory 物件本身
            if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                onOpen();
                if (allowsItemMovement()) {
                    // 2026-10：倉庫、隨身背包從別的選單打開時，偶爾客戶端只畫出按鈕、放東西的格子是空的（伺服器這邊東西都在，
                    // 重開就出現）：過 2 tick 再把整個視窗的內容送一次
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isConnected() && player.getOpenInventory().getTopInventory().getHolder() == this) {
                            player.updateInventory();
                        }
                    }, 2L);
                }
            }
        });
    }

    /** 選單真的打開之後（畫好、玩家看得到）。 */
    protected void onOpen() {
    }

    /** 重畫（選單還開著時呼叫）。 */
    public void redraw() {
        inventory.clear();
        draw();
    }

    /** 把所有格子畫上去。 */
    protected abstract void draw();

    /** 上方選單被點：slot 是選單內的格子。 */
    protected abstract void onClick(int slot, ClickType click, InventoryClickEvent event);

    /** 玩家自己背包被點（預設不做事，事件已取消）。 */
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
    }

    /** 選單被關掉。 */
    protected void onClose() {
    }

    /** true：點自己背包裡裝備的顯示用複本（第 9 格、副手、防具）也交給 {@link #onBottomClick}（角色面板用來卸下）；預設直接擋掉。 */
    protected boolean acceptsGearDisplay() {
        return false;
    }

    /** true：玩家可以把物品放進／拿出這個選單（點擊不預設取消）。 */
    protected boolean allowsItemMovement() {
        return false;
    }

    /** {@link #allowsItemMovement()} 為 true 時的點擊：自己決定要不要取消。 */
    protected void handleRawClick(final InventoryClickEvent event) {
        event.setCancelled(true);
    }

    /** {@link #allowsItemMovement()} 為 true 時的拖曳：自己決定要不要取消。 */
    protected void handleDrag(final InventoryDragEvent event) {
        event.setCancelled(true);
    }

    /**
     * 擋連點：買東西、下架、取消掛單這類按鈕做事之前先問一下，回傳 false 就什麼都不做。
     * 雙擊多送的那一下（DOUBLE_CLICK）一律不算；跟上一次不到 {@value #ACTION_GAP_MILLIS} 毫秒也不算
     * （畫面重畫後同一格已經換成下一件，手快點兩下會動到不是自己要的那件）。
     */
    protected boolean acceptAction(final ClickType click) {
        if (click == ClickType.DOUBLE_CLICK) {
            return false;
        }
        final long now = System.currentTimeMillis();
        if (now - lastAction < ACTION_GAP_MILLIS) {
            return false;
        }
        lastAction = now;
        return true;
    }

    // ---- 畫圖工具 ----

    protected void set(final int slot, final ItemStack stack) {
        inventory.setItem(slot, stack);
    }

    /** 做一個顯示用的物品：名稱與說明都是 MiniMessage。 */
    public static ItemStack icon(final Material material, final String name, final List<String> lore) {
        final ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line(name));
            meta.lore(tw.exilecore.core.text.TextWrap.lines(lore)); // 2026-10-26：長的說明自動換行（天賦、昇華節點、地圖詞綴…）
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        });
        return stack;
    }

    public static ItemStack icon(final Material material, final String name, final String... lore) {
        return icon(material, name, List.of(lore));
    }

    /** 在物品說明的最後面加幾行（MiniMessage）；回傳同一個物品（給顯示用的複本用，不要改到真的物品）。 */
    public static ItemStack appendLore(final ItemStack stack, final List<String> lines) {
        stack.editMeta(meta -> {
            final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.addAll(tw.exilecore.core.text.TextWrap.lines(lines)); // 2026-10-26：長行自動換行
            meta.lore(lore);
        });
        return stack;
    }

    public static ItemStack appendLore(final ItemStack stack, final String... lines) {
        return appendLore(stack, List.of(lines));
    }

    /** 帶發光的圖示（裝飾、按鈕、開關用；要讓人一眼看出「選到的是哪個」用 {@link #highlight}）。 */
    /**
     * 基底的圖示（2026-10-24）：用它的原版物品做，有簡圖的種類（普通、魔法、稀有裝備那張）就掛上 custom_model_data 的
     * {@code plain:種類}，跟掉落的裝備在格子裡長得一樣（圖鑑、商人賭博、拆解台…；沒套資源包的看到原版）。
     */
    public static ItemStack baseIcon(final tw.exilecore.item.ItemBase base, final String name, final List<String> lore) {
        final ItemStack stack = icon(material(base.iconMaterial(), Material.IRON_SWORD), name, lore);
        final String category = tw.exilecore.item.PlainArt.category(base);
        if (category != null) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                    .addString(tw.exilecore.item.PlainArt.modelKey(category)).build());
        }
        return stack;
    }

    public static ItemStack baseIcon(final tw.exilecore.item.ItemBase base, final String name, final String... lore) {
        return baseIcon(base, name, java.util.Arrays.asList(lore));
    }

    public static ItemStack glowing(final ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    /**
     * 選到的、目前這一頁、使用中的：套用介面資源包的人看到格子外面一圈<b>金色外框</b>（{@link UiPack#HIGHLIGHT}），
     * 沒套用的人（或這種物品畫不出框）看到發光。只給顯示用的複本用，最後再呼叫；要用回傳的那個。
     */
    protected ItemStack highlight(final ItemStack stack) {
        return mark(stack, FRAME_GOLD);
    }

    /** 拿著的東西可以放這裡：<b>綠色外框</b>（沒套用資源包：發光）。 */
    protected ItemStack targetHighlight(final ItemStack stack) {
        return mark(stack, FRAME_GREEN);
    }

    /** 看這個選單的人有沒有套用介面資源包（有：選到的畫外框；沒有：各選單用發光、換色、變暗來標）。 */
    protected boolean framed() {
        return plugin.web() != null && plugin.web().hasPack(player);
    }

    /**
     * 這個物品標起來會是外框嗎（看的人套用了資源包，而且這種物品畫得出框）。
     * 不會的話選單要自己用換色、變暗之類的對比（只發光看不出來）。
     */
    protected boolean frames(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !framed()) {
            return false;
        }
        if (artKey(stack) != null) {
            return true; // 專屬貼圖（傳奇、通貨）：框裡畫那張圖（外框的物品定義有對應的挑選）
        }
        final NamespacedKey type = stack.getType().getKey();
        final Key model = stack.getData(DataComponentTypes.ITEM_MODEL);
        // 換過模型的（介面小圖示、資料檔指定的模型）不畫框：框裡面只畫得出原版的樣子
        final boolean vanillaLook = model == null || model.asString().equals(type.asString());
        return vanillaLook && NamespacedKey.MINECRAFT.equals(type.getNamespace()) && UiPack.framable(type.getKey());
    }

    private ItemStack mark(final ItemStack stack, final Color color) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        if (frames(stack)) {
            final String art = artKey(stack);
            stack.setData(DataComponentTypes.ITEM_MODEL, HIGHLIGHT_MODEL);
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                    .addString(art != null ? art : stack.getType().getKey().getKey()).addColor(color).build());
            return stack;
        }
        return glowing(stack);
    }

    /** 物品上專屬貼圖的標記（傳奇 unique:代號、通貨 currency:代號）；沒有回傳 null。 */
    private static String artKey(final ItemStack stack) {
        final CustomModelData data = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (data == null || data.strings().isEmpty()) {
            return null;
        }
        final String first = data.strings().getFirst();
        return UiPack.isArtKey(first) ? first : null;
    }

    /**
     * 圖示右下角的數字（例如「那一頁有幾件符合搜尋」，最多 99）。劍、胸甲、藥水這些本來不能疊的也顯示得出來
     * （拿掉耐久、改成可以疊：會耐久的東西不能疊）。會改到物品本身：只給顯示用的複本用。
     */
    public static ItemStack withCount(final ItemStack stack, final int count) {
        final int amount = Math.max(1, Math.min(99, count));
        if (amount > stack.getMaxStackSize()) {
            stack.unsetData(DataComponentTypes.MAX_DAMAGE);
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 99);
        }
        stack.setAmount(amount);
        return stack;
    }

    /**
     * 介面小圖示（{@link UiPack#ICONS} 其中一個，例如 arrow_up）：套用介面資源包的人看到畫好的圖示，
     * 沒套用的人看到 fallback 這個原版物品。名稱與說明都是 MiniMessage。
     */
    /** 已經做好的按鈕換成介面小圖示（套了資源包的人看到）。 */
    protected ItemStack withUiIcon(final ItemStack stack, final String icon) {
        if (framed() && UiPack.ICONS.contains(icon)) {
            stack.setData(DataComponentTypes.ITEM_MODEL, UI_ICON_MODEL);
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(icon).build());
        }
        return stack;
    }

    protected ItemStack uiIcon(final String icon, final Material fallback, final String name, final String... lore) {
        final ItemStack stack = icon(fallback, name, lore);
        if (framed() && UiPack.ICONS.contains(icon)) {
            stack.setData(DataComponentTypes.ITEM_MODEL, UI_ICON_MODEL);
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(icon).build());
        }
        return stack;
    }

    // ---- 統一版面 ----

    /** 最上面一排的分頁位置：四個以內隔一格置中（1、3、5、7），更多就連著排、置中。 */
    public static int[] tabSlots(final int count) {
        final int n = Math.max(0, Math.min(9, count));
        final int[] slots = new int[n];
        if (n <= 4) {
            final int start = 4 - (n - 1);
            for (int i = 0; i < n; i++) {
                slots[i] = start + i * 2;
            }
        } else {
            final int start = (9 - n) / 2;
            for (int i = 0; i < n; i++) {
                slots[i] = start + i;
            }
        }
        return slots;
    }

    /** 第 row 排（0 起算）第 column 格的格子號碼。 */
    public static int slot(final int row, final int column) {
        return row * 9 + column;
    }

    /** 統一版面的外框：最上面一排與最下面一排鋪深色玻璃，中間四排清空。 */
    protected void frame() {
        final ItemStack bar = bar();
        for (int i = 0; i < 9; i++) {
            set(i, bar);
            set(SLOT_BACK + i, bar);
        }
        for (int i = CONTENT_START; i < CONTENT_END; i++) {
            set(i, null);
        }
    }

    /** 分頁與功能鍵那兩排的底（灰色玻璃，跟中間的內容分開）。 */
    public static ItemStack bar() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    /**
     * 一個分頁：目前這一頁名字前面「▶」、金色外框（沒資源包：發光）；其他頁寫「左鍵：切換」。
     *
     * @param about 這一頁有什麼（一兩行）
     */
    protected ItemStack tab(final Material material, final String name, final boolean current, final List<String> about) {
        final List<String> lore = new ArrayList<>(about);
        lore.add("");
        lore.add(current ? "<green>目前在這一頁" : "<yellow>左鍵：切換到這一頁");
        final ItemStack stack = icon(material, (current ? "<#f5b942>▶ " : "<white>") + name, lore);
        return current ? highlight(stack) : stack;
    }

    /** 這一排在做什麼的標籤（內容每一排的最左邊）：金色的「【名稱】」，說明寫在下面。 */
    public static ItemStack rowLabel(final Material material, final String name, final String... about) {
        final List<String> lore = new ArrayList<>();
        for (final String line : about) {
            lore.add("<gray>" + line);
        }
        return icon(material, "<#f5b942>【" + name + "】", lore);
    }

    /** 「← 返回」（最下面一排最左邊）。 */
    protected ItemStack backButton(final String where) {
        return uiIcon("nav_back", Material.ARROW, "<white>← 返回" + where, "<yellow>左鍵：回到" + where);
    }

    // ---- 搜尋（2026-10：清單很長的選單共用） ----

    /** 搜尋按鈕：左鍵輸入、右鍵清掉。 */
    protected ItemStack searchButton(final String query, final String what) {
        final boolean active = query != null && !query.isBlank();
        final ItemStack icon = icon(Material.SPYGLASS, "<white>搜尋" + what,
                active ? "<gray>現在：<white>" + ItemRenderer.escape(query) : "<gray>輸入名稱或說明的一部分",
                "", "<yellow>左鍵：輸入", active ? "<yellow>右鍵：清掉搜尋" : "");
        return active ? highlight(icon) : icon;
    }

    /**
     * 點了搜尋按鈕：右鍵清掉、左鍵跳出輸入框；改完呼叫 apply（新的字，空字串＝清掉），之後重新打開這個選單。
     */
    protected void searchClick(final ClickType click, final String what, final String current, final java.util.function.Consumer<String> apply) {
        if (click.isRightClick()) {
            apply.accept("");
            redraw();
            return;
        }
        plugin.dialogs().form(player, "<dark_gray>搜尋" + what, List.of("<gray>輸入名稱或說明的一部分（空白＝全部顯示）"),
                List.of(new Dialogs.Text("q", "關鍵字", current == null ? "" : current, 24)), "搜尋", answers -> {
                    apply.accept(answers.get("q").trim());
                    open();
                }, this::open);
    }

    /** 一段文字（可以有 MiniMessage 標籤）有沒有包含關鍵字（不分大小寫；空的關鍵字一律算有）。 */
    public static boolean matches(final String query, final String... texts) {
        if (query == null || query.isBlank()) {
            return true;
        }
        final String needle = query.toLowerCase(java.util.Locale.ROOT);
        for (final String text : texts) {
            if (text != null && text.replaceAll("<[^>]*>", "").toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 「說明」（最下面一排中間）：這個選單怎麼用。 */
    protected ItemStack helpButton(final List<String> lines) {
        final List<String> lore = new ArrayList<>();
        for (final String line : lines) {
            lore.add(line.isEmpty() || line.startsWith("<") ? line : "<gray>" + line);
        }
        final ItemStack stack = icon(Material.BOOK, "<white>說明", lore);
        if (framed()) {
            stack.setData(DataComponentTypes.ITEM_MODEL, UI_ICON_MODEL);
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString("help").build());
        }
        return stack;
    }

    /** 「上一頁」「下一頁」（最下面一排說明的兩邊）。 */
    protected ItemStack pageButton(final boolean next, final int page, final int pages) {
        return uiIcon(next ? "nav_next" : "nav_prev", Material.SPECTRAL_ARROW, "<white>" + (next ? "下一頁 ▶" : "◀ 上一頁"),
                "<gray>第 " + (page + 1) + "／" + Math.max(1, pages) + " 頁", "<yellow>左鍵：" + (next ? "下一頁" : "上一頁"));
    }

    /** 換頁按鈕（頁數多的時候）：說明多寫 Shift 跳 10 頁、右鍵跳到第一頁／最後一頁（2026-10，倉庫、背包可以買很多頁）。 */
    protected ItemStack jumpButton(final boolean next, final int page, final int pages) {
        final ItemStack stack = pageButton(next, page, pages);
        if (pages > 3) {
            appendLore(stack, "<yellow>Shift＋左鍵：" + (next ? "往後" : "往前") + " " + JUMP_PAGES + " 頁",
                    "<yellow>右鍵：" + (next ? "最後一頁" : "第一頁"));
        }
        return stack;
    }

    /** Shift 一次跳幾頁。 */
    public static final int JUMP_PAGES = 10;

    /**
     * 按了換頁按鈕之後到第幾頁：左鍵 ±1（頭尾相接）、Shift ±{@value #JUMP_PAGES}（停在頭尾）、右鍵跳到第一頁或最後一頁。
     */
    public static int stepPage(final int page, final int pages, final boolean next, final ClickType click) {
        if (pages <= 1) {
            return 0;
        }
        if (click.isRightClick()) {
            return next ? pages - 1 : 0;
        }
        if (click.isShiftClick()) {
            return Math.max(0, Math.min(pages - 1, page + (next ? JUMP_PAGES : -JUMP_PAGES)));
        }
        return Math.floorMod(page + (next ? 1 : -1), pages);
    }

    /**
     * 最下面一排最右邊的「← 返回選單」（2026-10 取代原本的「✖ 關閉」：關掉之後還要再按 Shift＋F 太麻煩）。
     * 點了呼叫 {@link #goBack()}；左下角有「← 返回 某處」的選單，那個是回上一層，這個是回選單。
     */
    protected ItemStack closeButton() {
        return uiIcon("nav_back", Material.ARROW, "<white>← 返回選單", "<gray>回到選單（Shift＋F）", "<yellow>左鍵：返回");
    }

    /** 最右下角「← 返回選單」按下去：回到選單。要回別的地方的選單自己覆寫。 */
    protected void goBack() {
        new MainMenu(plugin, player).open();
    }

    /** 會撿、開著、使用中：綠色外框（沒資源包或畫不出框：發光）。只給顯示用的複本用。 */
    protected ItemStack onMark(final ItemStack stack) {
        return mark(stack, FRAME_GREEN);
    }

    /** 會藏起來、關著：紅色外框（沒資源包或畫不出框：照原樣，名字前面的 ✘ 看得出來）。只給顯示用的複本用。 */
    protected ItemStack offMark(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !frames(stack)) {
            return stack;
        }
        return mark(stack, FRAME_RED);
    }

    /** 填滿空格的背景。 */
    public static ItemStack filler() {
        return icon(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    /** 從資料檔的材質名稱取得材質，不認得就用備用。 */
    public static Material material(final String name, final Material fallback) {
        if (name == null) {
            return fallback;
        }
        final Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }
}
