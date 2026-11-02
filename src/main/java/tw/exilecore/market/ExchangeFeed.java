package tw.exilecore.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.clientmod.UiFeed;
import tw.exilecore.currency.CurrencyType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-02 照站長：通貨交易所的原生畫面（PoE2 的 Currency Exchange 樣子：左邊選「我要」、右邊選「我有」、中間市價與深度、下面數量與下單、
 * 我的掛單）。裝了模組、招呼寫了 {@code exchangeui:true} 的玩家打開交易所時用這個；沒裝模組照舊開箱子選單（{@link ExchangeMenu}）。
 * 這次之前只有箱子選單。下單、取消、成交、手續費、交易寫入全部走 {@link MarketService#placeOrder}／{@link MarketService#cancelOrder}
 * （跟箱子選單同一套），這裡只負責選通貨、算數量（照 {@link ExchangeMenu} 的市價、參考價算法）與送狀態。
 */
public final class ExchangeFeed implements UiFeed.Source {

    public static final String KIND = "exchange";
    /** 招呼裡的旗標。 */
    public static final String FLAG = "exchangeui";
    /** 深度表每邊最多幾個價位。 */
    static final int DEPTH = 8;

    private final ExileCore plugin;
    /** 每個人選的通貨、數量（跟箱子選單同一種，回來時保留）。 */
    private final Map<UUID, ExchangeMenu.Choice> choices = new HashMap<>();
    /** 行情網頁資料的快取（同一份 bytes 只解析一次）。 */
    private byte[] marketRaw;
    private JsonObject marketPairs;

    public ExchangeFeed(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private MarketService service() {
        return plugin.market();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String flag() {
        return FLAG;
    }

    @Override
    public String problem(final Player player) {
        final MarketService service = service();
        if (service == null || plugin.economy() == null || plugin.currencies() == null) {
            return "交易所沒有開啟";
        }
        if (!service.canUseHere(player)) {
            return "通貨交易所只能在營地打開";
        }
        return null;
    }

    private ExchangeMenu.Choice choice(final Player player) {
        return choices.computeIfAbsent(player.getUniqueId(), id -> new ExchangeMenu.Choice());
    }

    private static boolean ready(final ExchangeMenu.Choice choice) {
        return choice.give != null && choice.want != null && !choice.give.equals(choice.want);
    }

    /** 可以交易的通貨（跟箱子選單一樣：economy.yml 的 values 裡有的；星幣要有贊助模組）。 */
    private List<String> tradable() {
        final List<String> ids = new ArrayList<>();
        for (final String id : service().config().values().keySet()) {
            if (plugin.currencies().repository().get(id) != null || MarketService.starCoin(id) && plugin.sponsors() != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 通貨圖示：{原版物品, 自訂模型字串（資源包的通貨美術）}。 */
    private String[] icon(final String id) {
        final ItemStack stack;
        if (MarketService.starCoin(id)) {
            stack = tw.exilecore.sponsor.SponsorService.coinIcon();
        } else {
            final CurrencyType type = plugin.currencies().repository().get(id);
            stack = type == null ? null : plugin.currencies().icon(type, 1);
        }
        if (stack == null) {
            return new String[] {"minecraft:paper", null};
        }
        String model = null;
        final io.papermc.paper.datacomponent.item.CustomModelData data = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (data != null && !data.strings().isEmpty()) {
            model = data.strings().getFirst();
        }
        return new String[] {stack.getType().getKey().toString(), model};
    }

    @Override
    public JsonObject state(final Player player, final JsonObject args) {
        final MarketService service = service();
        final ExchangeMenu.Choice choice = choice(player);
        final JsonObject root = new JsonObject();
        final List<String> recent = service.recent(player.getUniqueId());
        final JsonArray currencies = new JsonArray();
        for (final String id : tradable()) {
            final JsonObject c = new JsonObject();
            c.addProperty("id", id);
            c.addProperty("n", service.currencyName(id));
            final String[] icon = icon(id);
            c.addProperty("i", icon[0]);
            if (icon[1] != null) {
                c.addProperty("m", icon[1]);
            }
            c.addProperty("g", MarketService.starCoin(id) ? tw.exilecore.currency.CurrencyGroups.Group.BASIC.displayName()
                    : tw.exilecore.currency.CurrencyGroups.of(plugin.currencies().repository().get(id)).displayName());
            c.addProperty("have", service.wallet().available(player, id));
            final int recentIndex = recent.indexOf(id);
            if (recentIndex >= 0) {
                c.addProperty("r", recentIndex); // 最近換過（越小越近）
            }
            currencies.add(c);
        }
        root.add("currencies", currencies);
        root.addProperty("max", service.config().maxOrderAmount());
        root.addProperty("maxOrders", service.maxOrders(player));
        root.addProperty("gold", plugin.gold().balance(player));
        if (choice.give != null) {
            root.addProperty("give", choice.give);
        }
        if (choice.want != null) {
            root.addProperty("want", choice.want);
        }
        root.addProperty("giveAmount", choice.giveAmount);
        root.addProperty("wantAmount", choice.wantAmount);
        root.addProperty("source", choice.source);
        if (ready(choice)) {
            final UUID me = player.getUniqueId();
            // 可以馬上換的（別人用「我要的」換「我給的」）：lotWant 個我要的 ← lotGive 個我給的
            root.add("asks", levels(ExchangeMath.levels(service.book().makersFor(choice.give, choice.want, me), me), false));
            // 跟我同方向在排隊的（別人也用「我給的」換「我要的」）
            root.add("bids", levels(ExchangeMath.levels(service.book().competitors(choice.give, choice.want), me), true));
            final long[] reference = referencePrice(choice);
            if (reference != null) {
                final JsonArray ref = new JsonArray();
                ref.add(reference[0]);
                ref.add(reference[1]);
                root.add("ref", ref); // {我要的值, 我給的值}：1 個我要的 ≈ ref[0]/ref[1] 個我給的
            }
            final JsonObject market = market(choice.want, choice.give);
            if (market != null) {
                root.add("market", market);
            }
            final ExchangeBook.Result preview = service.book().simulate(choice.give, choice.giveAmount, choice.want, choice.wantAmount, me);
            final JsonObject p = new JsonObject();
            p.addProperty("received", preview.received());
            p.addProperty("paid", preview.paid());
            p.addProperty("restGive", preview.restGive());
            p.addProperty("restWant", preview.restWant());
            p.addProperty("refund", preview.refund());
            p.addProperty("resting", preview.resting());
            p.addProperty("fee", service.exchangeFee(player, choice.give, choice.giveAmount));
            root.add("preview", p);
        }
        final JsonArray orders = new JsonArray();
        for (final ExchangeBook.Order order : service.book().of(player.getUniqueId())) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", order.id());
            o.addProperty("give", order.give());
            o.addProperty("want", order.want());
            o.addProperty("giveTotal", order.giveTotal());
            o.addProperty("wantTotal", order.wantTotal());
            o.addProperty("taken", order.taken());
            o.addProperty("remaining", order.remaining());
            o.addProperty("confirmed", order.confirmed());
            o.addProperty("ago", MarketService.ago(order.createdAt()));
            orders.add(o);
        }
        root.add("orders", orders);
        return root;
    }

    private static JsonArray levels(final List<ExchangeMath.Level> levels, final boolean markMine) {
        final JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(DEPTH, levels.size()); i++) {
            final ExchangeMath.Level level = levels.get(i);
            final JsonObject o = new JsonObject();
            o.addProperty("lg", level.lotGive());
            o.addProperty("lw", level.lotWant());
            o.addProperty("stock", level.stock());
            o.addProperty("orders", level.orders());
            if (markMine && level.mine()) {
                o.addProperty("mine", true);
            }
            out.add(o);
        }
        return out;
    }

    /** 行情（交易所最近的成交）：pairs[a][b] 是用 b 計價的 a（last 個 b 換 1 個 a）；照 a = 我要的找，找不到反過來。 */
    private JsonObject market(final String want, final String give) {
        final MarketWeb web = plugin.marketWeb();
        if (web == null) {
            return null;
        }
        final byte[] raw = web.json();
        if (raw != marketRaw) {
            marketRaw = raw;
            try {
                final JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                marketPairs = parsed.isJsonObject() && parsed.getAsJsonObject().has("pairs") ? parsed.getAsJsonObject().getAsJsonObject("pairs") : null;
            } catch (final RuntimeException e) {
                marketPairs = null;
            }
        }
        if (marketPairs == null) {
            return null;
        }
        final JsonObject direct = pair(want, give);
        if (direct != null) {
            final JsonObject out = direct.deepCopy();
            out.remove("daily");
            out.remove("hourly");
            out.addProperty("inverse", false);
            return out;
        }
        final JsonObject inverse = pair(give, want);
        if (inverse != null) {
            final JsonObject out = inverse.deepCopy();
            out.remove("daily");
            out.remove("hourly");
            out.addProperty("inverse", true); // last 是用我要的計價的我給的
            return out;
        }
        return null;
    }

    private JsonObject pair(final String a, final String b) {
        if (!marketPairs.has(a) || !marketPairs.get(a).isJsonObject()) {
            return null;
        }
        final JsonObject byCounter = marketPairs.getAsJsonObject(a);
        return byCounter.has(b) && byCounter.get(b).isJsonObject() ? byCounter.getAsJsonObject(b) : null;
    }

    @Override
    public String action(final Player player, final String action, final JsonObject message) {
        final MarketService service = service();
        final ExchangeMenu.Choice choice = choice(player);
        final int max = service.config().maxOrderAmount();
        switch (action) {
            case "pair" -> {
                final List<String> tradable = tradable();
                final String give = string(message, "give");
                final String want = string(message, "want");
                choice.give = give != null && tradable.contains(give) ? give : null;
                choice.want = want != null && tradable.contains(want) && !want.equals(choice.give) ? want : null;
                if (choice.give != null) {
                    service.remember(player.getUniqueId(), choice.give);
                }
                if (choice.want != null) {
                    service.remember(player.getUniqueId(), choice.want);
                }
                if (ready(choice)) {
                    marketForWant(player, choice, Math.max(1, choice.wantAmount), max);
                }
                return null;
            }
            case "quote" -> {
                if (!ready(choice)) {
                    return null;
                }
                final int amount = clamp(integer(message, "amount", 1), max);
                if ("give".equals(string(message, "mode"))) {
                    marketForGive(player, choice, amount, max);
                } else {
                    marketForWant(player, choice, amount, max);
                }
                return null;
            }
            case "amounts" -> {
                choice.giveAmount = clamp(integer(message, "give", choice.giveAmount), max);
                choice.wantAmount = clamp(integer(message, "want", choice.wantAmount), max);
                choice.source = "自訂";
                return null;
            }
            case "nudge" -> {
                if (ready(choice)) {
                    final boolean up = message.has("up") && message.get("up").getAsBoolean();
                    apply(choice, ExchangeMath.nudge(choice.giveAmount, choice.wantAmount, up, max), up ? "自訂：出價調高" : "自訂：出價調低");
                }
                return null;
            }
            case "ref" -> {
                final long[] reference = ready(choice) ? referencePrice(choice) : null;
                if (reference != null) {
                    apply(choice, ExchangeMath.fromWant(choice.wantAmount, reference[1], reference[0], max), "參考價");
                }
                return null;
            }
            case "match" -> {
                // 跟排隊中第 i 個價位一樣（排在他們後面）
                if (!ready(choice)) {
                    return null;
                }
                final List<ExchangeMath.Level> bids = ExchangeMath.levels(service.book().competitors(choice.give, choice.want), player.getUniqueId());
                final int index = integer(message, "index", -1);
                if (index >= 0 && index < bids.size()) {
                    final ExchangeMath.Level level = bids.get(index);
                    apply(choice, ExchangeMath.fromWant(choice.wantAmount, level.lotWant(), level.lotGive(), max), "跟排隊中的出價一樣");
                }
                return null;
            }
            case "place" -> {
                if (!ready(choice)) {
                    return "<red>先選「我要」和「我有」的通貨";
                }
                // 模組送來它畫面上確認過的數量：跟伺服器記的不一樣就不下（畫面還沒更新），請玩家再看一次
                if (integer(message, "give", choice.giveAmount) != choice.giveAmount || integer(message, "want", choice.wantAmount) != choice.wantAmount) {
                    return "<yellow>數量剛剛變了，確認一次再下單";
                }
                final String problem = service.placeOrder(player, choice.give, choice.giveAmount, choice.want, choice.wantAmount);
                if (problem != null) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return "<red>" + tw.exilecore.item.ItemRenderer.escape(problem);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
                return "<green>下單了（結果寫在聊天欄）";
            }
            case "cancel" -> {
                final String id = string(message, "id");
                if (id == null) {
                    return null;
                }
                final String problem = service.cancelOrder(player, id);
                if (problem != null) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return "<red>" + tw.exilecore.item.ItemRenderer.escape(problem);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
                return "<gray>取消了，沒成交的退回";
            }
            default -> {
                return null;
            }
        }
    }

    // ---- 市價、參考價（照 ExchangeMenu 的算法）----

    private long[] referencePrice(final ExchangeMenu.Choice choice) {
        final long giveValue = service().config().value(choice.give);
        final long wantValue = service().config().value(choice.want);
        return giveValue <= 0 || wantValue <= 0 ? null : new long[] {wantValue, giveValue};
    }

    private ExchangeMath.Simulator simulator(final Player player, final ExchangeMenu.Choice choice) {
        return (give, want) -> service().book().simulate(choice.give, give, choice.want, want, player.getUniqueId()).received();
    }

    private void marketForWant(final Player player, final ExchangeMenu.Choice choice, final int want, final int max) {
        final ExchangeMath.Amounts amounts = ExchangeMath.cheapestFor(simulator(player, choice), want, max);
        if (amounts != null) {
            apply(choice, amounts, "市價：馬上全部成交");
            return;
        }
        final List<ExchangeBook.Order> makers = service().book().makersFor(choice.give, choice.want, player.getUniqueId());
        if (!makers.isEmpty()) {
            final ExchangeBook.Order worst = makers.getLast();
            apply(choice, ExchangeMath.fromWant(want, worst.lotGive(), worst.lotWant(), max), "市場上不夠：照最後一個價位，剩下的掛著");
            return;
        }
        final long[] reference = referencePrice(choice);
        if (reference != null) {
            apply(choice, ExchangeMath.fromWant(want, reference[1], reference[0], max), "沒有人在賣：照參考價掛著");
            return;
        }
        apply(choice, new ExchangeMath.Amounts(choice.giveAmount, Math.max(1, Math.min(max, want))), "沒有市價也沒有參考價：自己定價格");
    }

    private void marketForGive(final Player player, final ExchangeMenu.Choice choice, final int give, final int max) {
        final ExchangeMath.Amounts amounts = ExchangeMath.mostFor(simulator(player, choice), give, max);
        if (amounts != null) {
            apply(choice, amounts, "市價：能換多少換多少");
            return;
        }
        final long[] reference = referencePrice(choice);
        if (reference != null) {
            apply(choice, ExchangeMath.fromGive(give, reference[1], reference[0], max), "沒有人在賣（或給太少）：照參考價掛著");
            return;
        }
        apply(choice, new ExchangeMath.Amounts(Math.max(1, Math.min(max, give)), choice.wantAmount), "沒有市價也沒有參考價：自己定價格");
    }

    private static void apply(final ExchangeMenu.Choice choice, final ExchangeMath.Amounts amounts, final String source) {
        choice.giveAmount = amounts.give();
        choice.wantAmount = amounts.want();
        choice.source = source;
    }

    private static int clamp(final int value, final int max) {
        return Math.max(1, Math.min(max, value));
    }

    private static String string(final JsonObject message, final String key) {
        return message.has(key) && message.get(key).isJsonPrimitive() ? message.get(key).getAsString() : null;
    }

    private static int integer(final JsonObject message, final String key, final int fallback) {
        try {
            return message.has(key) && message.get(key).isJsonPrimitive() ? message.get(key).getAsInt() : fallback;
        } catch (final RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void chest(final Player player) {
        new ExchangeMenu(plugin, player, service(), new MarketMenu.State(), choice(player)).open();
    }

    @Override
    public String signature(final Player player) {
        final MarketService service = service();
        if (service == null) {
            return null;
        }
        final ExchangeMenu.Choice choice = choice(player);
        int book = 0;
        for (final ExchangeBook.Order order : service.book().all()) {
            book = book * 31 + order.id().hashCode() * 7 + order.remaining() + (order.confirmed() ? 1 : 0);
        }
        int wallet = 0;
        for (final String id : tradable()) {
            wallet = wallet * 31 + service.wallet().available(player, id);
        }
        return book + ":" + wallet + ":" + plugin.gold().balance(player) + ":" + choice.give + ":" + choice.want + ":" + choice.giveAmount
                + ":" + choice.wantAmount + ":" + (plugin.marketWeb() == null ? 0 : System.identityHashCode(plugin.marketWeb().json()));
    }

    /** 2026-11-02 照站長：裝了模組（招呼寫了 exchangeui）就開原生的交易所畫面；開了回傳 true（呼叫的地方就不開箱子選單）。 */
    static boolean openNativeFrom(final ExileCore plugin, final Player player) {
        return plugin.clientMod() != null && plugin.clientMod().ui() != null && plugin.clientMod().ui().open(player, KIND, null);
    }

    /** 下線：清掉選擇。 */
    @Override
    public void closed(final Player player) {
        if (!player.isOnline()) {
            choices.remove(player.getUniqueId());
        }
    }
}
