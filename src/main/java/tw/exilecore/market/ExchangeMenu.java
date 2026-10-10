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
 * 通貨交易所（PoE2 的 Currency Exchange）：
 * 上面兩列選通貨（左鍵＝我給、右鍵＝我要），第三列是目前別人掛的、可以馬上成交的單（點一下照那個價格填數量），
 * 第四、五列調整給多少、要多少，右邊兩格一鍵照市價（目前最好的單）或參考價（最近幾天的成交價；還沒有時用 economy.yml）算出另一邊，
 * 最下面下單（先跟合得來的單成交，剩下的掛著）。
 * 通貨照通貨袋的分組（一般通貨、未切割寶石、星魄、催化劑・星籤・蒸餾情緒）分開放，最下面一排的「通貨種類」切換；
 * 一組超過 18 種就分成幾頁（2026-10：以前只放得下前 18 種）。
 */
final class ExchangeMenu extends Menu {

    private static final int CURRENCY_SLOTS = 18;
    private static final int BOOK_START = 18;
    private static final int BOOK_SIZE = 9;
    private static final int[] GIVE_STEPS = {27, 28, 29, 31, 32, 33};
    private static final int[] WANT_STEPS = {36, 37, 38, 40, 41, 42};
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};
    private static final int SLOT_GIVE = 30;
    private static final int SLOT_WANT = 39;
    /** 一鍵帶入：照我給的算我要的（市價、參考價），照我要的算我給的。 */
    private static final int SLOT_GIVE_MARKET = 34;
    private static final int SLOT_GIVE_REFERENCE = 35;
    private static final int SLOT_WANT_MARKET = 43;
    private static final int SLOT_WANT_REFERENCE = 44;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_SECTION = 46;
    private static final int SLOT_ORDERS = 47;
    private static final int SLOT_PLACE = 49;
    private static final int SLOT_CLOSE = 53;

    /** 交易所的選擇（回來時保留）。 */
    static final class Choice {
        String give;
        String want;
        int giveAmount = 1;
        int wantAmount = 1;
        /** 上面兩排看的是哪一段（-1 = 還沒選：看選了的通貨那一段，沒有就第一段）。 */
        int section = -1;
    }

    /** 上面兩排的一段：一組通貨的一頁。 */
    private record Section(String name, String about, List<String> ids) {
    }

    private final MarketService service;
    private final MarketMenu.State state;
    private final Choice choice;
    private final List<String> currencies = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<ExchangeBook.Order> visible = new ArrayList<>();

    ExchangeMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state) {
        this(plugin, player, service, state, new Choice());
    }

    ExchangeMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state,
                 final Choice choice) {
        super(plugin, player, 6, "<dark_gray>通貨交易所");
        this.service = service;
        this.state = state;
        this.choice = choice;
        final java.util.Map<tw.exilecore.currency.CurrencyGroups.Group, List<String>> grouped =
                new java.util.EnumMap<>(tw.exilecore.currency.CurrencyGroups.Group.class);
        for (final String id : service.config().values().keySet()) {
            final CurrencyType type = plugin.currencies().repository().get(id);
            if (type != null) {
                grouped.computeIfAbsent(tw.exilecore.currency.CurrencyGroups.of(type), group -> new ArrayList<>()).add(id);
            }
        }
        for (final tw.exilecore.currency.CurrencyGroups.Group group : tw.exilecore.currency.CurrencyGroups.Group.values()) {
            final List<String> ids = grouped.getOrDefault(group, List.of());
            final int pages = (ids.size() + CURRENCY_SLOTS - 1) / CURRENCY_SLOTS;
            for (int page = 0; page < pages; page++) {
                sections.add(new Section(group.displayName() + (pages > 1 ? "（" + (page + 1) + "／" + pages + "）" : ""), group.about(),
                        List.copyOf(ids.subList(page * CURRENCY_SLOTS, Math.min(ids.size(), (page + 1) * CURRENCY_SLOTS)))));
            }
        }
        if (choice.section < 0 || choice.section >= sections.size()) {
            choice.section = 0;
            for (int i = 0; i < sections.size(); i++) {
                if (choice.give != null && sections.get(i).ids().contains(choice.give)) {
                    choice.section = i;
                    break;
                }
            }
        }
        if (!sections.isEmpty()) {
            currencies.addAll(sections.get(choice.section).ids());
        }
    }

    /** 最下面一排的「通貨種類」：現在看哪一段，每一段的名字都列出來。 */
    private ItemStack sectionIcon() {
        final List<String> lore = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            final Section section = sections.get(i);
            lore.add((i == choice.section ? "<#f5b942>▶ " : "<gray>　") + ItemRenderer.escape(section.name())
                    + "<dark_gray>（" + section.ids().size() + " 種）");
        }
        if (choice.section < sections.size()) {
            lore.add("");
            lore.add("<dark_gray>" + ItemRenderer.escape(sections.get(choice.section).about()));
        }
        lore.add("");
        lore.add("<yellow>左鍵：下一個　右鍵：上一個");
        final String current = sections.isEmpty() ? "（沒有）" : sections.get(choice.section).name();
        return icon(Material.SUNFLOWER, "<white>通貨種類：<#f5b942>" + ItemRenderer.escape(current), lore);
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, i < CURRENCY_SLOTS ? null : filler());
        }
        for (int i = 0; i < Math.min(CURRENCY_SLOTS, currencies.size()); i++) {
            final CurrencyType type = plugin.currencies().repository().get(currencies.get(i));
            final boolean give = type.id().equals(choice.give);
            final boolean want = type.id().equals(choice.want);
            ItemStack icon = plugin.currencies().icon(type, 1);
            final int have = service.wallet().available(player, type.id());
            appendLore(icon, "", String.format(Locale.ROOT, "<gray>你有 <white>%,d</white>（通貨袋＋背包＋倉庫）", have),
                    give ? "<#7fd4ff>◀ 你給這個" : want ? "<#f5b942>▶ 你要這個" : "",
                    "<yellow>左鍵：我給這個　右鍵：我要這個");
            if (give || want) {
                icon = highlight(icon); // 選了的：外框（沒資源包：通貨本來就會發光，其他的把光拿掉）
            } else {
                icon.editMeta(meta -> meta.setEnchantmentGlintOverride(false));
            }
            set(i, icon);
        }
        drawBook();
        for (int i = 0; i < GIVE_STEPS.length; i++) {
            set(GIVE_STEPS[i], step(STEPS[i], "給"));
            set(WANT_STEPS[i], step(STEPS[i], "要"));
        }
        set(SLOT_GIVE, icon(Material.BUNDLE, "<#7fd4ff>我給：" + amountText(choice.give, choice.giveAmount),
                "<gray>最多拿出這麼多（成交用不到的不會拿）", "<yellow>左鍵：輸入數量"));
        set(SLOT_WANT, icon(Material.CHEST, "<#f5b942>我要：" + amountText(choice.want, choice.wantAmount),
                "<gray>想換到這麼多（價格 = 給 ÷ 要）", "<yellow>左鍵：輸入數量"));
        drawPresets();
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回市集"));
        if (sections.size() > 1) {
            set(SLOT_SECTION, sectionIcon());
        }
        set(SLOT_ORDERS, icon(Material.WRITABLE_BOOK, "<#f5b942>我的掛單（" + service.book().of(player.getUniqueId()).size() + " / "
                + service.maxOrders(player) + "）", "<yellow>左鍵：查看、取消"));
        set(SLOT_PLACE, placeIcon());
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack step(final int step, final String what) {
        return icon(step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                (step < 0 ? "<red>" : "<green>+") + step + " <gray>（" + what + "）");
    }

    private String amountText(final String currency, final int amount) {
        return currency == null ? "<gray>（先在上面選通貨）" : "<white>" + amount + " " + ItemRenderer.escape(service.currencyName(currency));
    }

    /** 第三列：可以馬上成交的單（別人給你要的、要你給的），便宜的在前。 */
    private void drawBook() {
        visible.clear();
        if (choice.give == null || choice.want == null || choice.give.equals(choice.want)) {
            set(BOOK_START + 4, icon(Material.GLASS_PANE, "<gray>先選「我給」和「我要」的通貨",
                    "<dark_gray>左鍵選給的、右鍵選要的"));
            return;
        }
        final List<ExchangeBook.Order> makers = service.book().makersFor(choice.give, choice.want, player.getUniqueId());
        if (makers.isEmpty()) {
            set(BOOK_START + 4, icon(Material.GLASS_PANE, "<gray>目前沒有人用" + ItemRenderer.escape(service.currencyName(choice.want))
                    + "換" + ItemRenderer.escape(service.currencyName(choice.give)), "<dark_gray>你可以掛單等別人來換"));
            return;
        }
        for (int i = 0; i < Math.min(BOOK_SIZE, makers.size()); i++) {
            final ExchangeBook.Order order = makers.get(i);
            visible.add(order);
            final CurrencyType type = plugin.currencies().repository().get(order.give());
            final ItemStack icon = type == null ? new ItemStack(Material.PAPER) : plugin.currencies().icon(type, 1);
            icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), order.remaining())));
            final double each = (double) order.lotWant() / order.lotGive();
            appendLore(icon, "",
                    "<#f5b942>" + order.lotGive() + " " + ItemRenderer.escape(service.currencyName(order.give())) + " ← "
                            + order.lotWant() + " " + ItemRenderer.escape(service.currencyName(order.want())) + "<gray>（一批）",
                    String.format(Locale.ROOT, "<gray>每個要 %.2f 個%s", each, ItemRenderer.escape(service.currencyName(order.want()))),
                    "<gray>還有 " + order.lots() + " 批（" + order.remaining() + " 個）",
                    "<dark_gray>掛單：" + ItemRenderer.escape(order.playerName()),
                    "<yellow>左鍵：照這個價格換一批　右鍵：全部換");
            set(BOOK_START + i, icon);
        }
    }

    private boolean ready() {
        return choice.give != null && choice.want != null && !choice.give.equals(choice.want);
    }

    /** 市價：目前最好的那張單，{給, 要} = 每「要」個我給的換「給」個我要的；沒有人掛單回傳 null。 */
    private long[] marketPrice() {
        final List<ExchangeBook.Order> makers = service.book().makersFor(choice.give, choice.want, player.getUniqueId());
        if (makers.isEmpty()) {
            return null;
        }
        final ExchangeBook.Order best = makers.getFirst();
        return new long[] {best.lotGive(), best.lotWant()};
    }

    /** 參考價：照兩種通貨的參考價值（跟著成交價走；每 wantValue 個我給的換 giveValue 個我要的）；值不知道回傳 null。 */
    private long[] referencePrice() {
        final long giveValue = service.config().value(choice.give);
        final long wantValue = service.config().value(choice.want);
        return giveValue <= 0 || wantValue <= 0 ? null : new long[] {giveValue, wantValue};
    }

    private void drawPresets() {
        if (!ready()) {
            return;
        }
        final String giveName = ItemRenderer.escape(service.currencyName(choice.give));
        final String wantName = ItemRenderer.escape(service.currencyName(choice.want));
        final long[] market = marketPrice();
        final long[] reference = referencePrice();
        final int max = service.config().maxOrderAmount();
        if (market == null) {
            final ItemStack none = icon(Material.GRAY_DYE, "<gray>照市價", "<dark_gray>目前沒有人用" + wantName + "換" + giveName);
            set(SLOT_GIVE_MARKET, none);
            set(SLOT_WANT_MARKET, none.clone());
        } else {
            final ExchangeMath.Amounts fromGive = ExchangeMath.fromGive(choice.giveAmount, market[0], market[1], max);
            final ExchangeMath.Amounts fromWant = ExchangeMath.fromWant(choice.wantAmount, market[0], market[1], max);
            set(SLOT_GIVE_MARKET, icon(Material.EMERALD, "<#f5b942>照市價：我給這些能換多少",
                    String.format(Locale.ROOT, "<gray>市價：1 個%s ≈ %s 個%s", giveName, rate(market[0], market[1]), wantName),
                    "<gray>→ 給 <white>" + fromGive.give() + "</white>、要 <white>" + fromGive.want(),
                    "<dark_gray>（目前最好的那張單的價格）", "", "<yellow>左鍵：照這個填"));
            set(SLOT_WANT_MARKET, icon(Material.EMERALD, "<#f5b942>照市價：要換到這些要給多少",
                    String.format(Locale.ROOT, "<gray>市價：1 個%s ≈ %s 個%s", wantName, rate(market[1], market[0]), giveName),
                    "<gray>→ 給 <white>" + fromWant.give() + "</white>、要 <white>" + fromWant.want(),
                    "", "<yellow>左鍵：照這個填"));
        }
        if (reference == null) {
            set(SLOT_GIVE_REFERENCE, filler());
            set(SLOT_WANT_REFERENCE, filler());
        } else {
            final ExchangeMath.Amounts fromGive = ExchangeMath.fromGive(choice.giveAmount, reference[0], reference[1], max);
            final ExchangeMath.Amounts fromWant = ExchangeMath.fromWant(choice.wantAmount, reference[0], reference[1], max);
            set(SLOT_GIVE_REFERENCE, icon(Material.PAPER, "<#7fd4ff>照參考價：我給這些能換多少",
                    String.format(Locale.ROOT, "<gray>參考價：1 個%s ≈ %s 個%s", giveName, rate(reference[0], reference[1]), wantName),
                    "<gray>→ 給 <white>" + fromGive.give() + "</white>、要 <white>" + fromGive.want(),
                    service.config().followsMarket()
                            ? "<dark_gray>（最近 " + service.config().reference().days() + " 天的成交價算的參考比例，不一定是現在的市價）"
                            : "<dark_gray>（伺服器設定的參考比例，不一定是現在的市價）", "", "<yellow>左鍵：照這個填"));
            set(SLOT_WANT_REFERENCE, icon(Material.PAPER, "<#7fd4ff>照參考價：要換到這些要給多少",
                    String.format(Locale.ROOT, "<gray>參考價：1 個%s ≈ %s 個%s", wantName, rate(reference[1], reference[0]), giveName),
                    "<gray>→ 給 <white>" + fromWant.give() + "</white>、要 <white>" + fromWant.want(),
                    "", "<yellow>左鍵：照這個填"));
        }
    }

    /** 每 den 個換 num 個 → 每 1 個換幾個（顯示用）。 */
    private static String rate(final long num, final long den) {
        final double value = (double) num / den;
        return value >= 10 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private ItemStack placeIcon() {
        if (choice.give == null || choice.want == null || choice.give.equals(choice.want)) {
            return icon(Material.GRAY_CONCRETE, "<gray>下單", "<gray>先選「我給」和「我要」的通貨");
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>給 " + amountText(choice.give, choice.giveAmount) + "<gray>，換 " + amountText(choice.want, choice.wantAmount));
        lore.add(String.format(Locale.ROOT, "<gray>價格：每個%s %.2f 個%s", ItemRenderer.escape(service.currencyName(choice.want)),
                (double) choice.giveAmount / choice.wantAmount, ItemRenderer.escape(service.currencyName(choice.give))));
        final ExchangeBook.Result preview = service.book().simulate(choice.give, choice.giveAmount, choice.want, choice.wantAmount,
                player.getUniqueId());
        if (preview.received() > 0) {
            lore.add("<green>馬上成交：換到 " + preview.received() + "（付 " + preview.paid() + "）");
        }
        if (preview.resting()) {
            lore.add("<#7fd4ff>剩下的掛著：給 " + preview.restGive() + " 換 " + preview.restWant());
        } else if (preview.received() <= 0) {
            lore.add("<red>這個比例掛不出去：給的太少");
        }
        if (preview.refund() > 0) {
            lore.add("<dark_gray>用不到的 " + preview.refund() + " 個不會拿走");
        }
        lore.add(String.format(Locale.ROOT, "<gray>手續費：<#f5b942>%,d 金幣<gray>（你有 %,d）",
                service.exchangeFee(player, choice.give, choice.giveAmount), plugin.gold().balance(player)));
        lore.add("<dark_gray>成交的通貨：馬上成交的直接給你，之後成交的到市集掌櫃那裡領");
        lore.add("");
        lore.add("<yellow>左鍵：下單");
        return glowing(icon(Material.LIME_CONCRETE, "<green>下單", lore));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < CURRENCY_SLOTS) {
            if (slot < currencies.size()) {
                final String id = currencies.get(slot);
                if (click.isRightClick()) {
                    choice.want = id;
                    if (id.equals(choice.give)) {
                        choice.give = null;
                    }
                } else {
                    choice.give = id;
                    if (id.equals(choice.want)) {
                        choice.want = null;
                    }
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
                redraw();
            }
            return;
        }
        if (slot >= BOOK_START && slot < BOOK_START + BOOK_SIZE) {
            final int index = slot - BOOK_START;
            if (index < visible.size()) {
                final ExchangeBook.Order order = visible.get(index);
                final int lots = click.isRightClick() ? order.lots() : 1;
                choice.giveAmount = Math.max(1, Math.min(service.config().maxOrderAmount(), lots * order.lotWant()));
                choice.wantAmount = Math.max(1, Math.min(service.config().maxOrderAmount(), lots * order.lotGive()));
                redraw();
            }
            return;
        }
        for (int i = 0; i < STEPS.length; i++) {
            if (GIVE_STEPS[i] == slot) {
                choice.giveAmount = clamp(choice.giveAmount + STEPS[i]);
                redraw();
                return;
            }
            if (WANT_STEPS[i] == slot) {
                choice.wantAmount = clamp(choice.wantAmount + STEPS[i]);
                redraw();
                return;
            }
        }
        if (ready() && (slot == SLOT_GIVE_MARKET || slot == SLOT_GIVE_REFERENCE || slot == SLOT_WANT_MARKET || slot == SLOT_WANT_REFERENCE)) {
            final long[] price = slot == SLOT_GIVE_MARKET || slot == SLOT_WANT_MARKET ? marketPrice() : referencePrice();
            if (price != null) {
                final int max = service.config().maxOrderAmount();
                final ExchangeMath.Amounts amounts = slot == SLOT_GIVE_MARKET || slot == SLOT_GIVE_REFERENCE
                        ? ExchangeMath.fromGive(choice.giveAmount, price[0], price[1], max)
                        : ExchangeMath.fromWant(choice.wantAmount, price[0], price[1], max);
                choice.giveAmount = amounts.give();
                choice.wantAmount = amounts.want();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
                redraw();
            }
            return;
        }
        if (slot == SLOT_SECTION && sections.size() > 1) {
            choice.section = Math.floorMod(choice.section + (click.isRightClick() ? -1 : 1), sections.size());
            currencies.clear();
            currencies.addAll(sections.get(choice.section).ids());
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
            redraw();
            return;
        }
        switch (slot) {
            case SLOT_GIVE -> ask(true);
            case SLOT_WANT -> ask(false);
            case SLOT_BACK -> new MarketMenu(plugin, player, service, state).open();
            case SLOT_ORDERS -> new OrdersMenu(plugin, player, service, state, choice).open();
            case SLOT_PLACE -> {
                if (!acceptAction(click)) {
                    return;
                }
                final String problem = service.placeOrder(player, choice.give, choice.giveAmount, choice.want, choice.wantAmount);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + problem));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                }
                redraw();
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    private int clamp(final int value) {
        return Math.max(1, Math.min(service.config().maxOrderAmount(), value));
    }

    /** 跳出輸入框：兩個數量一起改（價格＝給 ÷ 要）。 */
    private void ask(final boolean give) {
        final String giveName = choice.give == null ? "（還沒選）" : ItemRenderer.escape(service.currencyName(choice.give));
        final String wantName = choice.want == null ? "（還沒選）" : ItemRenderer.escape(service.currencyName(choice.want));
        plugin.dialogs().form(player, "<dark_gray>通貨交易所：數量",
                List.of("<gray>我給 <white>" + giveName + "</white>，想換到 <white>" + wantName,
                        "<dark_gray>數量 1 到 " + service.config().maxOrderAmount() + "；成交用不到的通貨不會拿走"),
                List.of(new tw.exilecore.gui.Dialogs.Text("give", "我給（數量）", Integer.toString(choice.giveAmount), 6),
                        new tw.exilecore.gui.Dialogs.Text("want", "我要（數量）", Integer.toString(choice.wantAmount), 6)),
                "好", answers -> {
                    choice.giveAmount = number(answers.get("give"), choice.giveAmount);
                    choice.wantAmount = number(answers.get("want"), choice.wantAmount);
                    new ExchangeMenu(plugin, player, service, state, choice).open();
                }, () -> new ExchangeMenu(plugin, player, service, state, choice).open());
    }

    private int number(final String text, final int fallback) {
        try {
            return clamp(Integer.parseInt(text.trim()));
        } catch (final NumberFormatException e) {
            player.sendMessage(plugin.text().prefixed("<red>「" + ItemRenderer.escape(text) + "」不是數字，維持 " + fallback));
            return fallback;
        }
    }
}
