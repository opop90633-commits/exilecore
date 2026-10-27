package tw.exilecore.market;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 選價格：上面兩列選用哪種通貨標價，中間加減數量（或點數量跳出輸入框），第五列是市集上同類東西的價格（點一下照著標），下面確認。
 * 新上架（背包第幾格）與改價（已經上架的）共用。
 */
final class PriceMenu extends Menu {

    private static final int CURRENCY_SLOTS = 18;
    /**
     * 2026-10：第三列左邊是通貨頁的分組，右邊兩格換頁。
     * 2026-10-26：分組變成 11 組放不下，改成 18 ◀ 上一組、19～21 包含目前那組的三組、23 下一組 ▶（這次之前固定放前五組，後面的組點不到）。
     */
    private static final int[] GROUP_SLOTS = {19, 20, 21};
    private static final int SLOT_PREV_GROUP = 18;
    private static final int SLOT_NEXT_GROUP = 23;
    private static final int SLOT_PREV_PAGE = 24;
    private static final int SLOT_NEXT_PAGE = 26;
    private static final int SLOT_ITEM = 22;
    private static final int[] STEP_SLOTS = {27, 28, 29, 33, 34, 35};
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};
    private static final int SLOT_AMOUNT = 31;
    /** 第五列：同類的價格（標籤、最便宜的 7 件、中間價）。 */
    private static final int SLOT_SIMILAR_LABEL = 36;
    private static final int SIMILAR_START = 37;
    private static final int SIMILAR_SHOWN = 7;
    private static final int SLOT_MEDIAN = 44;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CONFIRM = 49;
    private static final int SLOT_CLOSE = 53;

    private final MarketService service;
    private final MarketMenu.State state;
    /** 新上架：背包第幾格與當時的樣子；改價時是 -1。 */
    private final int slot;
    private final ItemStack snapshot;
    /** 改價：哪一件；新上架時是 null。 */
    private final Listing listing;
    /** 能拿來標價的全部通貨（輸入框、照別人價格標時用）。 */
    private final List<String> currencies = new ArrayList<>();
    /** 現在這一組（畫面上那兩列）。 */
    private final List<String> shown = new ArrayList<>();
    private tw.exilecore.currency.CurrencyGroups.Group group = tw.exilecore.currency.CurrencyGroups.Group.BASIC;
    private int page;
    private final List<Listing> similar = new ArrayList<>();
    private String currency;
    private int amount;

    PriceMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state,
              final int slot, final ItemStack snapshot) {
        super(plugin, player, 6, "<dark_gray>上架：選價格");
        this.service = service;
        this.state = state;
        this.slot = slot;
        this.snapshot = snapshot;
        this.listing = null;
        this.amount = 1;
        init(null);
    }

    PriceMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state,
              final Listing listing) {
        super(plugin, player, 6, "<dark_gray>改價");
        this.service = service;
        this.state = state;
        this.slot = -1;
        this.snapshot = listing.item;
        this.listing = listing;
        this.amount = listing.priceAmount;
        init(listing.priceCurrency);
    }

    private void init(final String preferred) {
        // 2026-10：通貨頁上的東西都能拿來標價（照通貨頁的分組與順序；要有參考價值，見 economy.yml 的 values）
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (service.config().priceable(type.id())) {
                currencies.add(type.id());
            }
        }
        currency = preferred != null && currencies.contains(preferred) ? preferred : currencies.isEmpty() ? null : currencies.getFirst();
        if (currency != null) {
            showGroupOf(currency);
        }
    }

    /** 換到某一組（page 照那一種通貨在這組的位置）。 */
    private void showGroupOf(final String id) {
        final CurrencyType type = plugin.currencies().repository().get(id);
        group = type == null ? tw.exilecore.currency.CurrencyGroups.Group.BASIC : tw.exilecore.currency.CurrencyGroups.of(type);
        fillShown();
        page = Math.max(0, shown.indexOf(id)) / CURRENCY_SLOTS;
    }

    /** 2026-10-26：分組頁籤顯示的第一組（包含目前那組的一段，頭尾不超出）。 */
    private int firstGroupShown() {
        final int count = tw.exilecore.currency.CurrencyGroups.Group.values().length;
        return Math.max(0, Math.min(count - GROUP_SLOTS.length, group.ordinal() - GROUP_SLOTS.length / 2));
    }

    private void fillShown() {
        shown.clear();
        for (final CurrencyType type : tw.exilecore.currency.CurrencyGroups.in(plugin.currencies().repository().all(), group)) {
            if (currencies.contains(type.id())) {
                shown.add(type.id());
            }
        }
    }

    private int pages() {
        return Math.max(1, (shown.size() + CURRENCY_SLOTS - 1) / CURRENCY_SLOTS);
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, i < CURRENCY_SLOTS ? null : filler());
        }
        page = Math.max(0, Math.min(page, pages() - 1));
        final int from = page * CURRENCY_SLOTS;
        for (int i = 0; i < CURRENCY_SLOTS && from + i < shown.size(); i++) {
            final CurrencyType type = plugin.currencies().repository().get(shown.get(from + i));
            final boolean selected = type.id().equals(currency);
            final ItemStack icon = plugin.currencies().create(type, 1);
            appendLore(icon, "", selected ? "<green>✔ 用這個標價" : "<yellow>左鍵：用這個標價");
            // 選的那個：外框（沒資源包：通貨本來就會發光，其他的把光拿掉）
            set(i, selected ? highlight(icon) : withoutGlint(icon));
        }
        final tw.exilecore.currency.CurrencyGroups.Group[] groups = tw.exilecore.currency.CurrencyGroups.Group.values();
        final int firstGroup = firstGroupShown();
        for (int i = 0; i < Math.min(GROUP_SLOTS.length, groups.length); i++) {
            final int g = firstGroup + i;
            final List<CurrencyType> members = tw.exilecore.currency.CurrencyGroups.in(plugin.currencies().repository().all(), groups[g]);
            final ItemStack tab = members.isEmpty() ? new ItemStack(Material.SUNFLOWER) : plugin.currencies().create(members.getFirst(), 1);
            final tw.exilecore.currency.CurrencyGroups.Group each = groups[g];
            final boolean current = each == group;
            tab.editMeta(meta -> {
                meta.displayName(ItemRenderer.line((current ? "<#f5b942>" : "<white>") + each.displayName()));
                meta.lore(List.of(ItemRenderer.line("<gray>" + each.about()), ItemRenderer.line(""),
                        ItemRenderer.line(current ? "<green>✔ 目前在這一組" : "<yellow>左鍵：看這一組")));
                meta.setEnchantmentGlintOverride(false);
            });
            set(GROUP_SLOTS[i], current ? highlight(tab) : tab);
        }
        if (groups.length > GROUP_SLOTS.length) {
            set(SLOT_PREV_GROUP, icon(Material.ARROW, "<white>◀ 上一組", "<gray>" + group.previous().displayName(),
                    "<dark_gray>第 " + (group.ordinal() + 1) + " / " + groups.length + " 組"));
            set(SLOT_NEXT_GROUP, icon(Material.ARROW, "<white>下一組 ▶", "<gray>" + group.next().displayName(),
                    "<dark_gray>第 " + (group.ordinal() + 1) + " / " + groups.length + " 組"));
        }
        if (pages() > 1) {
            set(SLOT_PREV_PAGE, icon(Material.PAPER, "<white>◀ 上一頁", "<gray>第 " + (page + 1) + " / " + pages() + " 頁"));
            set(SLOT_NEXT_PAGE, icon(Material.PAPER, "<white>下一頁 ▶", "<gray>第 " + (page + 1) + " / " + pages() + " 頁"));
        }
        set(SLOT_ITEM, snapshot.clone());
        for (int i = 0; i < STEP_SLOTS.length; i++) {
            final int step = STEPS[i];
            set(STEP_SLOTS[i], icon(step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    (step < 0 ? "<red>" : "<green>+") + step));
        }
        final String name = currency == null ? "（沒有能標價的通貨）" : service.currencyName(currency);
        set(SLOT_AMOUNT, icon(Material.NAME_TAG, "<#f5b942>" + amount + " " + ItemRenderer.escape(name),
                "<yellow>左鍵：輸入通貨與數量"));
        final long fee = service.listingFee(player, snapshot); // 2026-10：照東西本身的估價，跟標價無關
        set(SLOT_CONFIRM, glowing(icon(Material.LIME_CONCRETE, listing == null ? "<green>確認上架" : "<green>確認改價",
                "<gray>標價：<white>" + amount + " " + ItemRenderer.escape(name),
                String.format(Locale.ROOT, "<gray>手續費：<#f5b942>%,d 金幣<gray>（你有 %,d）", fee, plugin.gold().balance(player)),
                "<gray>" + service.config().relistMinutes() + " 分鐘內不能下架或改價",
                "", "<yellow>左鍵：確認")));
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回商店頁"));
        set(SLOT_CLOSE, closeButton());
        drawSimilar();
    }

    /** 第五列：市集上同類的東西賣多少（不含自己的），點一下照那個價格標。 */
    private void drawSimilar() {
        similar.clear();
        similar.addAll(service.similar(snapshot, player.getUniqueId()));
        if (similar.isEmpty()) {
            set(SLOT_SIMILAR_LABEL, icon(Material.PAPER, "<gray>市集上還沒有同類的東西",
                    "<dark_gray>同類＝同一件傳奇，或同一個基底、同一種稀有度", "<dark_gray>自己決定價格吧"));
            return;
        }
        final Listing low = similar.getFirst();
        final Listing mid = similar.get(similar.size() / 2);
        final Listing high = similar.getLast();
        set(SLOT_SIMILAR_LABEL, glowing(icon(Material.KNOWLEDGE_BOOK, "<#f5b942>同類的價格（" + similar.size() + " 件）",
                "<gray>最便宜：<white>" + priceText(low),
                "<gray>中間：<white>" + priceText(mid),
                "<gray>最貴：<white>" + priceText(high),
                "<dark_gray>同類＝同一件傳奇，或同一個基底、同一種稀有度",
                "<dark_gray>右邊是最便宜的幾件，點一下照著標")));
        for (int i = 0; i < Math.min(SIMILAR_SHOWN, similar.size()); i++) {
            final Listing listing = similar.get(i);
            final CurrencyType type = plugin.currencies().repository().get(listing.priceCurrency);
            final ItemStack icon = type == null ? new ItemStack(Material.PAPER) : plugin.currencies().create(type, 1);
            icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), listing.priceAmount)));
            icon.editMeta(meta -> {
                meta.displayName(tw.exilecore.item.ItemRenderer.line("<#f5b942>" + ItemRenderer.escape(priceText(listing))));
                meta.lore(null);
                meta.setEnchantmentGlintOverride(false);
            });
            appendLore(icon, "<dark_gray>上架 " + MarketService.ago(listing.listedAt),
                    "", "<yellow>左鍵：照這個價格", listing.priceAmount > 1 ? "<yellow>右鍵：比它便宜 1 個" : "");
            set(SIMILAR_START + i, icon);
        }
        if (similar.size() >= 3) {
            set(SLOT_MEDIAN, icon(Material.COMPARATOR, "<#f5b942>照中間價：" + ItemRenderer.escape(priceText(mid)),
                    "<gray>一半的同類比這個便宜、一半比較貴", "", "<yellow>左鍵：照這個價格"));
        }
    }

    private String priceText(final Listing listing) {
        return service.wallet().price(listing.priceCurrency, listing.priceAmount);
    }

    /** 照某一件的價格標（undercut = 少 1 個）。 */
    private void copyPrice(final Listing listing, final boolean undercut) {
        if (!currencies.contains(listing.priceCurrency)) {
            player.sendMessage(plugin.text().prefixed("<red>那個價格用的通貨不能拿來標價"));
            return;
        }
        currency = listing.priceCurrency;
        showGroupOf(currency);
        amount = Math.max(1, Math.min(service.config().maxPrice(), listing.priceAmount - (undercut ? 1 : 0)));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        redraw();
    }

    private static ItemStack withoutGlint(final ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(false));
        return stack;
    }

    @Override
    protected void onClick(final int clicked, final ClickType click, final InventoryClickEvent event) {
        if (clicked < CURRENCY_SLOTS) {
            final int index = page * CURRENCY_SLOTS + clicked;
            if (index < shown.size()) {
                currency = shown.get(index);
                redraw();
            }
            return;
        }
        final int groupCount = tw.exilecore.currency.CurrencyGroups.Group.values().length;
        for (int i = 0; i < GROUP_SLOTS.length; i++) {
            final boolean arrow = groupCount > GROUP_SLOTS.length && (clicked == SLOT_PREV_GROUP || clicked == SLOT_NEXT_GROUP);
            if (arrow || GROUP_SLOTS[i] == clicked && firstGroupShown() + i < groupCount) {
                group = arrow ? (clicked == SLOT_PREV_GROUP ? group.previous() : group.next())
                        : tw.exilecore.currency.CurrencyGroups.Group.values()[firstGroupShown() + i];
                fillShown();
                page = 0;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                redraw();
                return;
            }
        }
        if ((clicked == SLOT_PREV_PAGE || clicked == SLOT_NEXT_PAGE) && pages() > 1) {
            page = (page + (clicked == SLOT_NEXT_PAGE ? 1 : pages() - 1)) % pages();
            redraw();
            return;
        }
        for (int i = 0; i < STEP_SLOTS.length; i++) {
            if (STEP_SLOTS[i] == clicked) {
                amount = Math.max(1, Math.min(service.config().maxPrice(), amount + STEPS[i]));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                redraw();
                return;
            }
        }
        if (clicked >= SIMILAR_START && clicked < SIMILAR_START + SIMILAR_SHOWN) {
            final int index = clicked - SIMILAR_START;
            if (index < similar.size()) {
                copyPrice(similar.get(index), click.isRightClick());
            }
            return;
        }
        if (clicked == SLOT_MEDIAN && similar.size() >= 3) {
            copyPrice(similar.get(similar.size() / 2), false);
            return;
        }
        switch (clicked) {
            case SLOT_AMOUNT -> promptPrice();
            case SLOT_CONFIRM -> {
                if (acceptAction(click)) {
                    confirm();
                }
            }
            case SLOT_BACK -> new ShopMenu(plugin, player, service, state).open();
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 跳出輸入框：一次選好通貨、打好數量。 */
    private void promptPrice() {
        if (currencies.isEmpty()) {
            return;
        }
        final List<tw.exilecore.gui.Dialogs.Option> options = new ArrayList<>();
        for (final String id : currencies) {
            options.add(new tw.exilecore.gui.Dialogs.Option(id, ItemRenderer.escape(service.currencyName(id))));
        }
        plugin.dialogs().form(player, "<dark_gray>" + (listing == null ? "上架標價" : "改價"),
                List.of("<gray>數量 1 到 " + service.config().maxPrice(),
                        String.format(Locale.ROOT, "<dark_gray>上架手續費：東西本身估價的 %s%%，付金幣（至少 %,d；跟標價無關，有月卡免費）",
                                ShopMenu.trim(service.config().listingFee().percent()), service.config().listingFee().min())),
                List.of(new tw.exilecore.gui.Dialogs.Choice("currency", "通貨", options, currency),
                        new tw.exilecore.gui.Dialogs.Text("amount", "數量", Integer.toString(amount), 6)),
                "好", answers -> {
                    if (currencies.contains(answers.get("currency"))) {
                        currency = answers.get("currency");
                        showGroupOf(currency);
                    }
                    try {
                        amount = Math.max(1, Math.min(service.config().maxPrice(), Integer.parseInt(answers.get("amount").trim())));
                    } catch (final NumberFormatException e) {
                        player.sendMessage(plugin.text().prefixed("<red>數量要是數字"));
                    }
                    open();
                }, this::open);
    }

    private void confirm() {
        if (currency == null) {
            return;
        }
        final String problem = listing == null
                ? service.list(player, slot, snapshot, currency, amount)
                : service.reprice(player, listing.id, currency, amount);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        player.sendMessage(plugin.text().prefixed(listing == null ? "<green>上架了：" : "<green>改好價格：")
                .append(snapshot.effectiveName())
                .append(plugin.text().parse("<gray>，" + ItemRenderer.escape(service.wallet().price(currency, amount)))));
        player.playSound(player.getLocation(), Sound.BLOCK_BARREL_CLOSE, 0.6f, 1.2f);
        new ShopMenu(plugin, player, service, state).open();
    }
}
