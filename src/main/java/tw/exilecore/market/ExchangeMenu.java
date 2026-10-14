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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通貨交易所（PoE2 的 Currency Exchange；2026-10 改成跟 PoE2 一樣好用）：
 * <ul>
 *   <li>上面兩列選通貨（左鍵＝我給、右鍵＝我要）。第一段是「常用」（最近換過的、你身上最多的），也可以搜尋名稱。</li>
 *   <li>第三列是市場深度：左邊四格是可以馬上換的價位（同價的單合在一起，顯示還有多少），中間是市價摘要，
 *       右邊四格是跟你同方向、在排隊的出價。</li>
 *   <li>第四、五列：打「我要幾個」或「我給幾個」就照市價（照掛著的單由便宜到貴吃下去）算好另一邊；也可以直接按要 1／10／100…。
 *       價格可以 ±5% 微調（多給一點比較快成交、少給一點掛著等）。</li>
 *   <li>成交的通貨自動放進你的通貨（倉庫通貨頁 → 通貨袋 → 背包），不用再去領。</li>
 * </ul>
 */
final class ExchangeMenu extends Menu {

    private static final int CURRENCY_SLOTS = 18;
    /** 第三列：可以馬上換的價位（左四格）、市價摘要、同方向的出價（右四格）。 */
    private static final int[] ASK_SLOTS = {18, 19, 20, 21};
    private static final int SLOT_SUMMARY = 22;
    private static final int[] BID_SLOTS = {23, 24, 25, 26};
    // 第四列
    private static final int SLOT_WANT_INPUT = 27;
    private static final int SLOT_GIVE_INPUT = 28;
    private static final int SLOT_CUSTOM = 29;
    private static final int SLOT_CURRENT = 31;
    private static final int SLOT_CHEAPER = 32;
    private static final int SLOT_PRICE = 33;
    private static final int SLOT_DEARER = 34;
    private static final int SLOT_REFERENCE = 35;
    // 第五列：照市價要幾個、用我全部的
    private static final int[] QUICK_SLOTS = {36, 37, 38, 39, 40};
    private static final int[] QUICK_WANTS = {1, 5, 10, 50, 100};
    private static final int SLOT_ALL_MINE = 42;
    private static final int SLOT_BUY_OUT = 43;
    // 最下面
    private static final int SLOT_BACK = 45;
    private static final int SLOT_SECTION = 46;
    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_ORDERS = 48;
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
        /** 搜尋的字（null = 沒在搜尋）。 */
        String search;
        /** 現在的數量是怎麼來的（顯示用）：市價、參考價、自訂。 */
        String source = "";
    }

    /** 上面兩排的一段：一組通貨的一頁。 */
    private record Section(String name, String about, List<String> ids) {
    }

    private final MarketService service;
    private final MarketMenu.State state;
    private final Choice choice;
    private final List<String> currencies = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<ExchangeMath.Level> asks = new ArrayList<>();

    ExchangeMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state) {
        this(plugin, player, service, state, new Choice());
    }

    ExchangeMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state,
                 final Choice choice) {
        super(plugin, player, 6, "<dark_gray>通貨交易所");
        this.service = service;
        this.state = state;
        this.choice = choice;
        buildSections();
        if (choice.section < 0 || choice.section >= sections.size()) {
            choice.section = 0;
        }
        if (!sections.isEmpty()) {
            currencies.addAll(sections.get(choice.section).ids());
        }
    }

    private List<String> tradable() {
        final List<String> ids = new ArrayList<>();
        for (final String id : service.config().values().keySet()) {
            if (plugin.currencies().repository().get(id) != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void buildSections() {
        final List<String> all = tradable();
        // 常用：最近換過、選過的，再補你身上最多的
        final Set<String> common = new LinkedHashSet<>();
        for (final String id : service.recent(player.getUniqueId())) {
            if (all.contains(id)) {
                common.add(id);
            }
        }
        final List<String> owned = new ArrayList<>(all);
        final java.util.Map<String, Integer> have = new java.util.HashMap<>();
        for (final String id : owned) {
            have.put(id, service.wallet().available(player, id));
        }
        owned.removeIf(id -> have.get(id) <= 0);
        owned.sort((a, b) -> Integer.compare(have.get(b), have.get(a)));
        for (final String id : owned) {
            if (common.size() >= CURRENCY_SLOTS) {
                break;
            }
            common.add(id);
        }
        if (!common.isEmpty()) {
            sections.add(new Section("常用", "最近換過的，和你身上最多的通貨", List.copyOf(common)));
        }
        if (choice.search != null && !choice.search.isBlank()) {
            final String query = choice.search.trim().toLowerCase(Locale.ROOT);
            final List<String> found = new ArrayList<>();
            for (final String id : all) {
                if (service.currencyName(id).toLowerCase(Locale.ROOT).contains(query) || id.toLowerCase(Locale.ROOT).contains(query)) {
                    found.add(id);
                }
            }
            sections.add(new Section("搜尋：" + choice.search.trim(), "名稱裡有「" + choice.search.trim() + "」的通貨（" + found.size() + " 種）",
                    found.subList(0, Math.min(CURRENCY_SLOTS, found.size()))));
        }
        final java.util.Map<tw.exilecore.currency.CurrencyGroups.Group, List<String>> grouped =
                new java.util.EnumMap<>(tw.exilecore.currency.CurrencyGroups.Group.class);
        for (final String id : all) {
            grouped.computeIfAbsent(tw.exilecore.currency.CurrencyGroups.of(plugin.currencies().repository().get(id)),
                    group -> new ArrayList<>()).add(id);
        }
        for (final tw.exilecore.currency.CurrencyGroups.Group group : tw.exilecore.currency.CurrencyGroups.Group.values()) {
            final List<String> ids = grouped.getOrDefault(group, List.of());
            final int pages = (ids.size() + CURRENCY_SLOTS - 1) / CURRENCY_SLOTS;
            for (int page = 0; page < pages; page++) {
                sections.add(new Section(group.displayName() + (pages > 1 ? "（" + (page + 1) + "／" + pages + "）" : ""), group.about(),
                        List.copyOf(ids.subList(page * CURRENCY_SLOTS, Math.min(ids.size(), (page + 1) * CURRENCY_SLOTS)))));
            }
        }
    }

    private String name(final String id) {
        return ItemRenderer.escape(service.currencyName(id));
    }

    private int max() {
        return service.config().maxOrderAmount();
    }

    private boolean ready() {
        return choice.give != null && choice.want != null && !choice.give.equals(choice.want);
    }

    // ---- 畫 ----

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, i < CURRENCY_SLOTS ? null : filler());
        }
        for (int i = 0; i < Math.min(CURRENCY_SLOTS, currencies.size()); i++) {
            final CurrencyType type = plugin.currencies().repository().get(currencies.get(i));
            if (type == null) {
                continue;
            }
            final boolean give = type.id().equals(choice.give);
            final boolean want = type.id().equals(choice.want);
            ItemStack icon = plugin.currencies().icon(type, 1);
            final int have = service.wallet().available(player, type.id());
            appendLore(icon, "", String.format(Locale.ROOT, "<gray>你有 <white>%,d</white>（通貨袋＋背包＋倉庫）", have),
                    give ? "<#7fd4ff>◀ 你給這個" : want ? "<#f5b942>▶ 你要這個" : "",
                    "<yellow>左鍵：我給這個　右鍵：我要這個");
            if (give || want) {
                icon = highlight(icon);
            } else {
                icon.editMeta(meta -> meta.setEnchantmentGlintOverride(false));
            }
            set(i, icon);
        }
        drawDepth();
        drawInputs();
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回市集"));
        if (sections.size() > 1) {
            set(SLOT_SECTION, sectionIcon());
        }
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<white>搜尋通貨" + (choice.search == null ? "" : "：<#f5b942>" + ItemRenderer.escape(choice.search)),
                "<gray>打名稱的一部分（例如「崇高」「星魄」）", "", "<yellow>左鍵：搜尋" + (choice.search == null ? "" : "　右鍵：清掉搜尋")));
        set(SLOT_ORDERS, icon(Material.WRITABLE_BOOK, "<#f5b942>我的掛單（" + service.book().of(player.getUniqueId()).size() + " / "
                + service.maxOrders(player) + "）", "<gray>看每張單成交了多少、取消", "<dark_gray>成交的通貨會自動放進你的通貨", "",
                "<yellow>左鍵：查看"));
        set(SLOT_PLACE, placeIcon());
        set(SLOT_CLOSE, closeButton());
    }

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

    /** 「1 個 A ≈ x 個 B」：每 den 個 A 換 num 個 B；小於 1 時反過來寫，比較好讀。 */
    private String ratio(final String a, final long num, final String b, final long den) {
        final double value = (double) num / den;
        if (value >= 1 || value <= 0) {
            return "1 " + name(a) + " ≈ " + number(value) + " " + name(b);
        }
        return number(1 / value) + " " + name(a) + " ≈ 1 " + name(b);
    }

    private static String number(final double value) {
        if (value >= 100) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    /** 第三列：市場深度。 */
    private void drawDepth() {
        asks.clear();
        if (!ready()) {
            set(SLOT_SUMMARY, icon(Material.GLASS_PANE, "<gray>先選「我給」和「我要」的通貨",
                    "<dark_gray>上面左鍵選給的、右鍵選要的", "<dark_gray>（常用那一段有你最近換過的）"));
            return;
        }
        final List<ExchangeBook.Order> makers = service.book().makersFor(choice.give, choice.want, player.getUniqueId());
        asks.addAll(ExchangeMath.levels(makers, player.getUniqueId()));
        final List<ExchangeMath.Level> bids = ExchangeMath.levels(service.book().competitors(choice.give, choice.want), player.getUniqueId());
        final CurrencyType wantType = plugin.currencies().repository().get(choice.want);
        final CurrencyType giveType = plugin.currencies().repository().get(choice.give);
        int cumulative = 0;
        for (int i = 0; i < ASK_SLOTS.length; i++) {
            if (i >= asks.size()) {
                set(ASK_SLOTS[i], i == 0 ? icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>沒有人在賣" + name(choice.want),
                        "<dark_gray>（沒有人用" + name(choice.want) + "換" + name(choice.give) + "）", "<dark_gray>你可以掛單等別人來換") : filler());
                continue;
            }
            final ExchangeMath.Level level = asks.get(i);
            cumulative += level.stock();
            final ItemStack icon = wantType == null ? new ItemStack(Material.PAPER) : plugin.currencies().icon(wantType, 1);
            icon.setAmount(Math.max(1, Math.min(64, level.stock())));
            final ItemStack named = icon(icon.getType(), (i == 0 ? "<#5bd65b>市價　" : "<white>") + ratio(choice.want, level.lotWant(), choice.give, level.lotGive()),
                    String.format(Locale.ROOT, "<gray>這個價位還有 <white>%,d</white> 個%s（%d 張單）", level.stock(), name(choice.want), level.orders()),
                    String.format(Locale.ROOT, "<dark_gray>到這個價位為止總共 %,d 個", cumulative),
                    "", "<yellow>左鍵：買到這個價位為止（" + String.format(Locale.ROOT, "%,d", Math.min(max(), cumulative)) + " 個）");
            set(ASK_SLOTS[i], withCount(copyLook(icon, named), level.stock()));
        }
        for (int i = 0; i < BID_SLOTS.length; i++) {
            if (i >= bids.size()) {
                set(BID_SLOTS[i], i == 0 ? icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>沒有人跟你同方向在排隊",
                        "<dark_gray>你掛的單會是第一個") : filler());
                continue;
            }
            final ExchangeMath.Level level = bids.get(i);
            final ItemStack icon = giveType == null ? new ItemStack(Material.PAPER) : plugin.currencies().icon(giveType, 1);
            final ItemStack named = icon(icon.getType(), "<#7fd4ff>排隊中：" + ratio(choice.want, level.lotGive(), choice.give, level.lotWant())
                            + (level.mine() ? " <#f5b942>（有你的）" : ""),
                    String.format(Locale.ROOT, "<gray>別人也在用%s換%s：掛了 <white>%,d</white> 個%s（%d 張單）",
                            name(choice.give), name(choice.want), level.stock(), name(choice.give), level.orders()),
                    "<dark_gray>出價比他們高，你的單會先成交", "", "<yellow>左鍵：跟這個價格一樣（排在他們後面）");
            set(BID_SLOTS[i], withCount(copyLook(icon, named), level.stock()));
        }
        // 中間：市價摘要
        final List<String> lore = new ArrayList<>();
        if (!asks.isEmpty()) {
            final ExchangeMath.Level best = asks.getFirst();
            lore.add("<gray>市價：<#5bd65b>" + ratio(choice.want, best.lotWant(), choice.give, best.lotGive()));
            int total = 0;
            for (final ExchangeMath.Level level : asks) {
                total += level.stock();
            }
            lore.add(String.format(Locale.ROOT, "<gray>可以馬上換到：<white>%,d</white> 個%s（%d 個價位）", total, name(choice.want), asks.size()));
        } else {
            lore.add("<gray>市價：<dark_gray>目前沒有人在賣" + name(choice.want));
        }
        final long[] reference = referencePrice();
        if (reference != null) {
            lore.add("<gray>參考價：" + ratio(choice.want, reference[0], choice.give, reference[1])
                    + (service.config().followsMarket() ? "<dark_gray>（最近 " + service.config().reference().days() + " 天的成交）" : ""));
        }
        lore.add(String.format(Locale.ROOT, "<gray>你有 <white>%,d</white> 個%s", service.wallet().available(player, choice.give), name(choice.give)));
        lore.add("");
        lore.add("<dark_gray>左邊：可以馬上換的價位　右邊：跟你同方向在排隊的出價");
        lore.add("<yellow>左鍵：重新整理");
        set(SLOT_SUMMARY, icon(Material.COMPASS, "<#f5b942>" + name(choice.give) + " → " + name(choice.want), lore));
    }

    /** 把 icon 的外觀（通貨圖示）套上 named 的名字與說明。 */
    private static ItemStack copyLook(final ItemStack look, final ItemStack named) {
        final ItemStack out = look.clone();
        out.editMeta(meta -> {
            final org.bukkit.inventory.meta.ItemMeta source = named.getItemMeta();
            meta.displayName(source.displayName());
            meta.lore(source.lore());
            meta.setEnchantmentGlintOverride(false);
        });
        return out;
    }

    private void drawInputs() {
        if (!ready()) {
            return;
        }
        final String give = name(choice.give);
        final String want = name(choice.want);
        set(SLOT_WANT_INPUT, icon(Material.CHEST, "<#f5b942>我要幾個" + want + "？",
                "<gray>打數量，照市價算要給多少", "<dark_gray>（照掛著的單由便宜到貴吃下去；市場上不夠的照最後的價格掛著）", "",
                "<yellow>左鍵：輸入數量"));
        set(SLOT_GIVE_INPUT, icon(Material.BUNDLE, "<#7fd4ff>我給幾個" + give + "？",
                "<gray>打數量，照市價算最多能換到幾個", "", "<yellow>左鍵：輸入數量"));
        set(SLOT_CUSTOM, icon(Material.NAME_TAG, "<white>自訂數量與價格", "<gray>兩邊的數量自己打（價格 = 給 ÷ 要）",
                "<dark_gray>想掛便宜一點慢慢等時用", "", "<yellow>左鍵：輸入"));
        set(SLOT_CURRENT, icon(Material.PAPER, "<white>給 " + choice.giveAmount + " " + give + "，換 " + choice.wantAmount + " " + want,
                "<gray>價格：" + ratio(choice.want, choice.giveAmount, choice.give, choice.wantAmount),
                choice.source.isEmpty() ? "" : "<dark_gray>（" + ItemRenderer.escape(choice.source) + "）"));
        set(SLOT_CHEAPER, icon(Material.RED_STAINED_GLASS_PANE, "<red>出價低一點（約 −5%）", "<gray>少給一點；比市價低的會掛著等人來換",
                "", "<yellow>左鍵：調低"));
        set(SLOT_PRICE, icon(Material.EMERALD, "<#5bd65b>照市價", "<gray>照你要的數量重新算成市價", "", "<yellow>左鍵：照市價"));
        set(SLOT_DEARER, icon(Material.LIME_STAINED_GLASS_PANE, "<green>出價高一點（約 +5%）", "<gray>多給一點；比別人出價高的先成交",
                "", "<yellow>左鍵：調高"));
        final long[] reference = referencePrice();
        set(SLOT_REFERENCE, reference == null ? filler() : icon(Material.MAP, "<#7fd4ff>照參考價",
                "<gray>參考價：" + ratio(choice.want, reference[0], choice.give, reference[1]),
                service.config().followsMarket() ? "<dark_gray>（最近 " + service.config().reference().days() + " 天的成交價算的）"
                        : "<dark_gray>（伺服器設定的參考比例）", "", "<yellow>左鍵：照參考價算（要的數量不變）"));
        for (int i = 0; i < QUICK_SLOTS.length; i++) {
            set(QUICK_SLOTS[i], withCount(icon(Material.GOLD_NUGGET, "<#f5b942>照市價要 " + QUICK_WANTS[i] + " 個" + want,
                    "", "<yellow>左鍵：填好（再按下單）　右鍵：直接下單"), QUICK_WANTS[i]));
        }
        final int mine = service.wallet().available(player, choice.give);
        set(SLOT_ALL_MINE, icon(Material.HOPPER, "<#7fd4ff>用我全部的" + give + "換",
                String.format(Locale.ROOT, "<gray>你有 %,d 個（一次最多 %,d）", mine, max()), "<gray>照市價能換多少換多少", "",
                "<yellow>左鍵：填好"));
        int total = 0;
        for (final ExchangeMath.Level level : asks) {
            total += level.stock();
        }
        set(SLOT_BUY_OUT, total <= 0 ? filler() : icon(Material.DIAMOND, "<#5bd65b>市場上的全部買下",
                String.format(Locale.ROOT, "<gray>現在掛著 %,d 個%s", total, want), "", "<yellow>左鍵：填好"));
    }

    private ItemStack placeIcon() {
        if (!ready()) {
            return icon(Material.GRAY_CONCRETE, "<gray>下單", "<gray>先選「我給」和「我要」的通貨");
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>給 <white>" + choice.giveAmount + " " + name(choice.give) + "<gray>，換 <white>" + choice.wantAmount + " " + name(choice.want));
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
        final int have = service.wallet().available(player, choice.give);
        if (have < choice.giveAmount) {
            lore.add(String.format(Locale.ROOT, "<red>你只有 %,d 個%s", have, name(choice.give)));
        }
        lore.add(String.format(Locale.ROOT, "<gray>手續費：<#f5b942>%,d 金幣<gray>（你有 %,d）",
                service.exchangeFee(player, choice.give, choice.giveAmount), plugin.gold().balance(player)));
        lore.add("<dark_gray>成交的通貨自動放進你的通貨（之後才成交的也是）");
        lore.add("");
        lore.add("<yellow>左鍵：下單");
        return glowing(icon(Material.LIME_CONCRETE, "<green>下單", lore));
    }

    // ---- 價格 ----

    private ExchangeMath.Simulator simulator() {
        return (give, want) -> service.book().simulate(choice.give, give, choice.want, want, player.getUniqueId()).received();
    }

    /** 參考價：每 [1] 個我給的換 [0] 個我要的…這裡回傳 {我要的價值, 我給的價值}：每 value(want) 個我給的 ≈ value(give) 個我要的。 */
    private long[] referencePrice() {
        final long giveValue = service.config().value(choice.give);
        final long wantValue = service.config().value(choice.want);
        return giveValue <= 0 || wantValue <= 0 ? null : new long[] {wantValue, giveValue};
    }

    /** 照市價要 want 個：市場上夠就吃到夠為止；不夠就照最後一個價位（沒有人賣照參考價）把剩下的掛著。 */
    private void marketForWant(final int want) {
        final ExchangeMath.Amounts amounts = ExchangeMath.cheapestFor(simulator(), want, max());
        if (amounts != null) {
            apply(amounts, "市價：馬上全部成交");
            return;
        }
        final List<ExchangeBook.Order> makers = service.book().makersFor(choice.give, choice.want, player.getUniqueId());
        if (!makers.isEmpty()) {
            final ExchangeBook.Order worst = makers.getLast();
            apply(ExchangeMath.fromWant(want, worst.lotGive(), worst.lotWant(), max()), "市場上不夠：照最後一個價位，剩下的掛著");
            return;
        }
        final long[] reference = referencePrice();
        if (reference != null) {
            apply(ExchangeMath.fromWant(want, reference[1], reference[0], max()), "沒有人在賣：照參考價掛著");
            return;
        }
        apply(new ExchangeMath.Amounts(choice.giveAmount, Math.max(1, Math.min(max(), want))), "沒有市價也沒有參考價：自己定價格");
    }

    private void marketForGive(final int give) {
        final ExchangeMath.Amounts amounts = ExchangeMath.mostFor(simulator(), give, max());
        if (amounts != null) {
            apply(amounts, "市價：能換多少換多少");
            return;
        }
        final long[] reference = referencePrice();
        if (reference != null) {
            apply(ExchangeMath.fromGive(give, reference[1], reference[0], max()), "沒有人在賣（或給太少）：照參考價掛著");
            return;
        }
        apply(new ExchangeMath.Amounts(Math.max(1, Math.min(max(), give)), choice.wantAmount), "沒有市價也沒有參考價：自己定價格");
    }

    private void apply(final ExchangeMath.Amounts amounts, final String source) {
        choice.giveAmount = amounts.give();
        choice.wantAmount = amounts.want();
        choice.source = source;
    }

    // ---- 點 ----

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
                service.remember(player.getUniqueId(), id);
                if (ready()) {
                    marketForWant(choice.wantAmount); // 選好兩種：馬上照市價算好
                }
                click(1.3f);
                redraw();
            }
            return;
        }
        for (int i = 0; i < ASK_SLOTS.length; i++) {
            if (slot == ASK_SLOTS[i] && ready() && i < asks.size()) {
                int upTo = 0;
                for (int k = 0; k <= i; k++) {
                    upTo += asks.get(k).stock();
                }
                marketForWant(Math.min(max(), upTo));
                click(1.5f);
                redraw();
                return;
            }
        }
        for (final int bidSlot : BID_SLOTS) {
            if (slot == bidSlot && ready()) {
                final List<ExchangeMath.Level> bids = ExchangeMath.levels(service.book().competitors(choice.give, choice.want), player.getUniqueId());
                final int index = java.util.Arrays.stream(BID_SLOTS).boxed().toList().indexOf(slot);
                if (index < bids.size()) {
                    final ExchangeMath.Level level = bids.get(index);
                    apply(ExchangeMath.fromWant(choice.wantAmount, level.lotWant(), level.lotGive(), max()), "跟排隊中的出價一樣");
                    click(1.5f);
                    redraw();
                }
                return;
            }
        }
        for (int i = 0; i < QUICK_SLOTS.length; i++) {
            if (slot == QUICK_SLOTS[i] && ready()) {
                marketForWant(QUICK_WANTS[i]);
                if (click.isRightClick()) {
                    place(click);
                } else {
                    click(1.5f);
                }
                redraw();
                return;
            }
        }
        if (!ready() && slot >= 27 && slot < 45) {
            return;
        }
        switch (slot) {
            case SLOT_SUMMARY -> redraw();
            case SLOT_WANT_INPUT -> askOne(false);
            case SLOT_GIVE_INPUT -> askOne(true);
            case SLOT_CUSTOM -> askBoth();
            case SLOT_CHEAPER, SLOT_DEARER -> {
                final ExchangeMath.Amounts amounts = ExchangeMath.nudge(choice.giveAmount, choice.wantAmount, slot == SLOT_DEARER, max());
                apply(amounts, slot == SLOT_DEARER ? "自訂：出價調高" : "自訂：出價調低");
                click(slot == SLOT_DEARER ? 1.6f : 1.1f);
                redraw();
            }
            case SLOT_PRICE -> {
                marketForWant(choice.wantAmount);
                click(1.5f);
                redraw();
            }
            case SLOT_REFERENCE -> {
                final long[] reference = referencePrice();
                if (reference != null) {
                    apply(ExchangeMath.fromWant(choice.wantAmount, reference[1], reference[0], max()), "參考價");
                    click(1.5f);
                    redraw();
                }
            }
            case SLOT_ALL_MINE -> {
                final int mine = service.wallet().available(player, choice.give);
                if (mine <= 0) {
                    player.sendMessage(plugin.text().prefixed("<red>你沒有" + name(choice.give)));
                    return;
                }
                marketForGive(Math.min(max(), mine));
                click(1.5f);
                redraw();
            }
            case SLOT_BUY_OUT -> {
                int total = 0;
                for (final ExchangeMath.Level level : asks) {
                    total += level.stock();
                }
                if (total > 0) {
                    marketForWant(Math.min(max(), total));
                    click(1.5f);
                    redraw();
                }
            }
            case SLOT_BACK -> new MarketMenu(plugin, player, service, state).open();
            case SLOT_SECTION -> {
                if (sections.size() > 1) {
                    choice.section = Math.floorMod(choice.section + (click.isRightClick() ? -1 : 1), sections.size());
                    currencies.clear();
                    currencies.addAll(sections.get(choice.section).ids());
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
                    redraw();
                }
            }
            case SLOT_SEARCH -> {
                if (click.isRightClick() && choice.search != null) {
                    choice.search = null;
                    choice.section = 0;
                    new ExchangeMenu(plugin, player, service, state, choice).open();
                } else {
                    askSearch();
                }
            }
            case SLOT_ORDERS -> new OrdersMenu(plugin, player, service, state, choice).open();
            case SLOT_PLACE -> {
                place(click);
                redraw();
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    private void place(final ClickType click) {
        if (!ready() || !acceptAction(click)) {
            return;
        }
        final String problem = service.placeOrder(player, choice.give, choice.giveAmount, choice.want, choice.wantAmount);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
    }

    private void click(final float pitch) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, pitch);
    }

    private void reopen() {
        new ExchangeMenu(plugin, player, service, state, choice).open();
    }

    /** 只打一個數量：照市價算另一邊。 */
    private void askOne(final boolean give) {
        final String what = give ? "我給幾個" + name(choice.give) : "我要幾個" + name(choice.want);
        plugin.dialogs().form(player, "<dark_gray>通貨交易所：" + what,
                List.of("<gray>照市價算另一邊（市場上不夠的照最後的價格掛著）", "<dark_gray>數量 1 到 " + max()),
                List.of(new tw.exilecore.gui.Dialogs.Text("amount", what, Integer.toString(give ? choice.giveAmount : choice.wantAmount), 6)),
                "照市價算", answers -> {
                    final Integer amount = number(answers.get("amount"));
                    if (amount != null) {
                        if (give) {
                            marketForGive(amount);
                        } else {
                            marketForWant(amount);
                        }
                    }
                    reopen();
                }, this::reopen);
    }

    /** 兩個數量都自己打（價格 = 給 ÷ 要）。 */
    private void askBoth() {
        plugin.dialogs().form(player, "<dark_gray>通貨交易所：自訂",
                List.of("<gray>我給 <white>" + name(choice.give) + "</white>，想換到 <white>" + name(choice.want),
                        "<dark_gray>數量 1 到 " + max() + "；成交用不到的通貨不會拿走"),
                List.of(new tw.exilecore.gui.Dialogs.Text("give", "我給（數量）", Integer.toString(choice.giveAmount), 6),
                        new tw.exilecore.gui.Dialogs.Text("want", "我要（數量）", Integer.toString(choice.wantAmount), 6)),
                "好", answers -> {
                    final Integer give = number(answers.get("give"));
                    final Integer want = number(answers.get("want"));
                    apply(new ExchangeMath.Amounts(give == null ? choice.giveAmount : give, want == null ? choice.wantAmount : want), "自訂");
                    reopen();
                }, this::reopen);
    }

    private void askSearch() {
        plugin.dialogs().form(player, "<dark_gray>通貨交易所：搜尋",
                List.of("<gray>打通貨名稱的一部分"),
                List.of(new tw.exilecore.gui.Dialogs.Text("q", "名稱", choice.search == null ? "" : choice.search, 16)),
                "搜尋", answers -> {
                    final String q = answers.get("q") == null ? "" : answers.get("q").trim();
                    choice.search = q.isEmpty() ? null : q;
                    choice.section = -1;
                    final ExchangeMenu menu = new ExchangeMenu(plugin, player, service, state, choice);
                    menu.showSearch();
                    menu.open();
                }, this::reopen);
    }

    /** 搜尋完直接跳到搜尋結果那一段。 */
    private void showSearch() {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).name().startsWith("搜尋：")) {
                choice.section = i;
                currencies.clear();
                currencies.addAll(sections.get(i).ids());
                return;
            }
        }
    }

    private Integer number(final String text) {
        try {
            return Math.max(1, Math.min(max(), Integer.parseInt(text == null ? "" : text.trim())));
        } catch (final NumberFormatException e) {
            player.sendMessage(plugin.text().prefixed("<red>「" + ItemRenderer.escape(String.valueOf(text)) + "」不是數字"));
            return null;
        }
    }
}
