package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import tw.exilecore.sponsor.SponsorConfig;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 贊助商店頁（網址/shop，2026-10，給金流（PayUni）審核與玩家看）：儲值方案（新台幣）、星幣可以換的東西（照 sponsor.yml 現在的價格）、
 * 抽獎箱的機率、累積贊助的稱號、商家資料與條款。商家資料、儲值方案寫在 config.yml 的 web.shop。
 */
final class ShopData {

    private ShopData() {
    }

    static byte[] json(final ConfigurationSection shop, final SponsorConfig sponsor, final boolean checkout, final boolean sandbox) {
        final JsonObject root = new JsonObject();
        root.addProperty("checkout", checkout);
        root.addProperty("sandbox", checkout && sandbox);
        root.add("merchant", merchant(shop));
        root.add("packages", packages(shop));
        root.addProperty("payments", shop == null ? "" : shop.getString("payments", ""));
        if (sponsor != null) {
            root.add("card", card(sponsor));
            root.add("items", items(sponsor));
            root.add("boxes", boxes(sponsor));
            root.add("tiers", tiers(sponsor));
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject merchant(final ConfigurationSection shop) {
        final JsonObject merchant = new JsonObject();
        final ConfigurationSection section = shop == null ? null : shop.getConfigurationSection("merchant");
        for (final String key : List.of("name", "owner", "tax-id", "email", "phone", "address", "hours")) {
            merchant.addProperty(key, section == null ? "" : section.getString(key, "").trim());
        }
        return merchant;
    }

    private static JsonArray packages(final ConfigurationSection shop) {
        final JsonArray list = new JsonArray();
        final List<Map<?, ?>> raw = shop == null ? List.of() : shop.getMapList("packages");
        for (final Map<?, ?> entry : raw) {
            final long stars = number(entry.get("stars"));
            final long price = number(entry.get("price"));
            if (stars <= 0 || price <= 0) {
                continue;
            }
            final JsonObject item = new JsonObject();
            item.addProperty("stars", stars);
            item.addProperty("price", price);
            item.addProperty("bonus", Math.max(0, number(entry.get("bonus"))));
            final Object name = entry.get("name");
            item.addProperty("name", name == null ? "" : name.toString());
            list.add(item);
        }
        return list;
    }

    private static long number(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString().trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject card(final SponsorConfig sponsor) {
        final JsonObject card = new JsonObject();
        card.addProperty("price", sponsor.cardPrice());
        card.addProperty("days", sponsor.cardDays());
        card.addProperty("daily_card_coins", sponsor.dailyCardCoins());
        card.addProperty("daily_fashion_coins", sponsor.dailyFashionCoins());
        card.addProperty("welcome_card_coins", sponsor.welcomeCardCoins());
        card.addProperty("welcome_fashion_coins", sponsor.welcomeFashionCoins());
        return card;
    }

    private static JsonArray items(final SponsorConfig sponsor) {
        final JsonArray list = new JsonArray();
        add(list, "帳號", "角色欄位 +1", sponsor.slotPrice(), "多開一個角色");
        add(list, "帳號", "倉庫頁 +1", sponsor.stashPrice(), "倉庫多一頁（45 格），帳號共用");
        add(list, "帳號", "隨身背包頁 +1", sponsor.bagPrice(), "隨身背包多一頁（45 格），帳號裡每個角色都有");
        add(list, "帳號", "通貨袋加大", sponsor.pouchPrice(), "每種通貨的上限 +" + sponsor.pouchStep() + "（最多 " + sponsor.pouchMax() + " 次）");
        add(list, "帳號", "倉庫地圖頁加大", sponsor.mapPrice(), "地圖頁 +" + sponsor.mapStep() + " 張（最多 " + sponsor.mapMax() + " 次）");
        add(list, "帳號", "倉庫通貨頁加大", sponsor.stashCurrencyPrice(),
                "每種通貨的上限 +" + sponsor.stashCurrencyStep() + "（最多 " + sponsor.stashCurrencyMax() + " 次）");
        add(list, "帳號", "市集上架格 +1", sponsor.listingPrice(), "市集商店頁多上架一件");
        add(list, "帳號", "改名卡", sponsor.renamePrice(), "改一次角色名字");
        add(list, "公會", "公會改名卡", sponsor.guildRenamePrice(), "改一次公會的名稱與標籤");
        add(list, "公會", "公會倉庫頁 +1", sponsor.guildStashPrice(), "整個公會共用的倉庫多一頁");
        add(list, "加倍", "全服加倍（" + sponsor.boostMinutes() + " 分鐘）", sponsor.boostPrice(),
                "全服一起吃的經驗、掉落數量、稀有度或金幣加倍，公告贊助者");
        for (final SponsorConfig.Box box : sponsor.boxes().values()) {
            if (box.starPrice() > 0) {
                add(list, "時裝", box.name(), box.starPrice(), "隨機一件時裝（外觀，不加能力），機率見下方");
            }
        }
        return list;
    }

    private static void add(final JsonArray list, final String group, final String name, final long price, final String about) {
        if (price <= 0) {
            return;
        }
        final JsonObject item = new JsonObject();
        item.addProperty("group", group);
        item.addProperty("name", name);
        item.addProperty("stars", price);
        item.addProperty("about", about);
        list.add(item);
    }

    private static JsonArray boxes(final SponsorConfig sponsor) {
        final JsonArray list = new JsonArray();
        for (final SponsorConfig.Box box : sponsor.boxes().values()) {
            final double total = box.total();
            if (total <= 0) {
                continue;
            }
            final JsonObject item = new JsonObject();
            item.addProperty("name", box.name());
            item.addProperty("stars", box.starPrice());
            item.addProperty("fashion", box.fashionPrice());
            item.addProperty("pity", box.pity());
            item.addProperty("description", box.description() == null ? "" : box.description());
            final Map<String, Double> byRarity = new LinkedHashMap<>();
            final JsonArray entries = new JsonArray();
            for (final Map.Entry<String, Double> weight : box.weights().entrySet()) {
                final SponsorConfig.Cosmetic cosmetic = sponsor.cosmetics().get(weight.getKey());
                final String rarity = cosmetic == null ? "其他" : cosmetic.rarity().displayName();
                final double percent = weight.getValue() * 100 / total;
                byRarity.merge(rarity, percent, Double::sum);
                final JsonObject entry = new JsonObject();
                entry.addProperty("name", cosmetic == null ? weight.getKey() : cosmetic.name());
                entry.addProperty("rarity", rarity);
                entry.addProperty("percent", Math.round(percent * 10000) / 10000.0);
                entries.add(entry);
            }
            final JsonArray rarities = new JsonArray();
            for (final Map.Entry<String, Double> rarity : byRarity.entrySet()) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("rarity", rarity.getKey());
                entry.addProperty("percent", Math.round(rarity.getValue() * 100) / 100.0);
                rarities.add(entry);
            }
            item.add("rarities", rarities);
            item.add("entries", entries);
            list.add(item);
        }
        return list;
    }

    private static JsonArray tiers(final SponsorConfig sponsor) {
        final JsonArray list = new JsonArray();
        for (final SponsorConfig.Tier tier : sponsor.tiers()) {
            final JsonObject item = new JsonObject();
            item.addProperty("amount", tier.amount());
            item.addProperty("title", tier.title());
            item.addProperty("color", tier.color());
            final JsonArray perks = new JsonArray();
            if (tier.stashPages() > 0) {
                perks.add("倉庫 +" + tier.stashPages() + " 頁");
            }
            if (tier.bagPages() > 0) {
                perks.add("隨身背包 +" + tier.bagPages() + " 頁");
            }
            if (tier.listings() > 0) {
                perks.add("市集上架 " + tier.listings() + " 件");
            }
            if (tier.bypassFull()) {
                perks.add("滿人也進得來");
            }
            if (tier.chatColor()) {
                perks.add("聊天顏色");
            }
            if (tier.customTitle()) {
                perks.add("自訂稱號");
            }
            if (tier.campFly()) {
                perks.add("營地飛行");
            }
            item.add("perks", perks);
            list.add(item);
        }
        return list;
    }

}
