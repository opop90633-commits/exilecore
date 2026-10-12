package tw.exilecore.sponsor;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 贊助的設定（data/sponsor.yml）。純資料，不依賴 Bukkit（粒子名稱在模組裡另外檢查）。
 */
public final class SponsorConfig {

    public static final String FILE = "sponsor.yml";
    /** 累積贊助最多多幾頁倉庫、隨身背包（倉庫、背包的頁數上限跟著這兩個）。 */
    public static final int MAX_EXTRA_STASH = 480;
    public static final int MAX_EXTRA_BAG = 98;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** 外觀特效的稀有度。 */
    public enum Rarity {
        COMMON("普通", "#c8c8c8"),
        RARE("稀有", "#5fb4ff"),
        EPIC("史詩", "#c27dff"),
        LEGENDARY("傳說", "#ffb84d");

        private final String displayName;
        private final String color;

        Rarity(final String displayName, final String color) {
            this.displayName = displayName;
            this.color = color;
        }

        public String displayName() {
            return displayName;
        }

        public String color() {
            return color;
        }

        static Rarity parse(final String text, final String where) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "：稀有度要寫 common、rare、epic、legendary，卻是「" + text + "」");
            }
        }
    }

    /** 特效的形狀。 */
    public enum Style {
        RING("腳下的光環"), HALO("頭上的光環"), SPIRAL("螺旋"), WINGS("翅膀"), TRAIL("走路的足跡"), ORBIT("環繞的光點"),
        CROWN("頭上的冠"), AURA("身邊飄散");

        private final String displayName;

        Style(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static Style parse(final String text, final String where) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "：style 要寫 ring、halo、spiral、wings、trail、orbit、crown、aura，卻是「" + text + "」");
            }
        }
    }

    /** 累積贊助一階：累積多少星幣、稱號、名字顏色與額外功能（listings 0 = 照 economy.yml）。 */
    public record Tier(long amount, String id, String title, String color, String nameColor, int stashPages, int bagPages, int listings,
                       boolean bypassFull, boolean chatColor, boolean customTitle, boolean campFly,
                       double experience, double gold, double deathPenalty) {
        /** 經驗、金幣多幾 %，死亡懲罰改成幾 %（-1 = 照 character.yml）。2026-10。 */
        public boolean hasBonus() {
            return experience > 0 || gold > 0 || deathPenalty >= 0;
        }
    }

    /** 登入天數的稱號。 */
    /** stats：擁有就有的小加成（2026-10；登入天數是免費拿的，可以有加成——用錢買的稱號不能有）。 */
    public record LoginTitle(int days, String id, String name, String color, List<tw.exilecore.stats.Modifier> stats) {
    }

    /** 一種全服加倍。quantity、rarity 是小數（1 = +100%）；experience、gold 是倍數。 */
    public record BoostType(String id, String name, String label, String icon, double experience, double quantity, double rarity,
                            double gold) {
    }

    /**
     * 一個外觀特效（粒子）或幻化外觀（skin = true）。fashionPrice 0 = 不能用時裝幣直接換。
     * 幻化：skinSlot 是 weapon（近戰武器、法杖）、bow、crossbow、offhand（盾、箭袋）或 armor（頭、身、腿、腳一整套），
     * art 是 web/skins/ 裡那一套美術的代號（tools/gen_skin_art.py 畫的）。
     */
    public record Cosmetic(String id, String name, Rarity rarity, Style style, String particle, String color, boolean rainbow,
                           long fashionPrice, String description, boolean skin, String skinSlot, String art, String emote, String limited) {
        public Cosmetic(final String id, final String name, final Rarity rarity, final Style style, final String particle, final String color,
                        final boolean rainbow, final long fashionPrice, final String description, final boolean skin, final String skinSlot,
                        final String art, final String emote) {
            this(id, name, rarity, style, particle, color, rainbow, fashionPrice, description, skin, skinSlot, art, emote, null);
        }

        /** 是不是動作（2026-10：按設定的按鍵做的動作，見 Emotes）。 */
        public boolean isEmote() {
            return emote != null;
        }

        /**
         * 絕版、限定（2026-10）：limited 寫來源（例如「S1 贊助禮包」）。不能抽（抽獎箱不能放）、不能用時裝幣或月卡幣換，
         * 只有管理網頁、/ec sponsor give … cosmetic 發得出來；選單上顯示「S1 贊助禮包限定」，沒有的人看不到。
         */
        public boolean isLimited() {
            return limited != null && !limited.isEmpty();
        }

        /** 「S1 贊助禮包限定」這種字（沒有限定回傳空字串）。 */
        public String limitedTag() {
            return isLimited() ? limited + "限定" : "";
        }
    }

    /** 限定稱號（2026-10，limited_titles）：只有管理網頁、指令發得出來（例如 S1 贊助禮包）；limited 是來源，選單上顯示「…限定」。 */
    public record LimitedTitle(String id, String name, String color, String limited, List<tw.exilecore.stats.Modifier> stats) {
        public LimitedTitle {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }

        public String limitedTag() {
            return limited + "限定";
        }
    }

    /** 一組時裝（2026-10，bundles）：管理網頁、指令可以一次發整組（例如 S1 贊助禮包的整套）；項目可以是時裝代號或 title:稱號代號。 */
    public record Bundle(String id, String name, List<String> cosmetics) {
        public Bundle {
            cosmetics = List.copyOf(cosmetics);
        }
    }

    /** 動作的種類（type: emote 的 emote: 要寫這些）。 */
    public static final java.util.Set<String> EMOTES = java.util.Set.of("sidestep", "kick_ball", "bear_shake", "head_bob", "king_sit", "love_you", "six_seven");

    /** 幻化的種類。 */
    public static final java.util.Set<String> SKIN_SLOTS = java.util.Set.of("weapon", "bow", "crossbow", "offhand", "armor",
            "headgear", "back", "pet"); // 2026-10：頭飾、背飾、寵物（3D 模型）

    /** 一種抽獎箱。 */
    public record Box(String id, String name, String icon, long starPrice, long fashionPrice, int pity, String description,
                      Map<String, Double> weights) {
        public Box {
            weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        }

        /** 權重加起來。 */
        public double total() {
            double total = 0;
            for (final double weight : weights.values()) {
                total += weight;
            }
            return total;
        }
    }

    /** 月卡幣兌換的種類。 */
    public enum CardKind { TITLE, COSMETIC, FASHION_COINS, CHARACTER_SLOT, RENAME_CARD }

    /** 月卡幣兌換的一樣東西。 */
    /** hidden：不再賣（商店不顯示、不能換），已經換過的人照樣擁有（2026-10：月卡稱號下架）。 */
    public record CardItem(String id, CardKind kind, long price, String name, String color, String cosmetic, int amount, boolean hidden) {
    }

    private boolean enabled = true;
    private int loginMinutes = 30;
    private long slotPrice = 100;
    private int slotMax = 200;
    private long stashPrice = 100;
    private int stashMax = 10;
    private long bagPrice = 300;
    /** 2026-10：用星幣加大通貨袋（每種通貨的上限）、地圖頁。 */
    private long pouchPrice = 150;
    private int pouchStep = 2500;
    private int pouchMax = 4;
    private long mapPrice = 150;
    private int mapStep = 500;
    private int mapMax = 4;
    /** 倉庫通貨頁加大（2026-10）。 */
    private long stashCurrencyPrice = 150;
    private int stashCurrencyStep = 5000;
    private int stashCurrencyMax = 4;
    private int bagMax = 5;
    private long listingPrice = 150;
    /** 改名卡（2026-10）：一張改一次角色名字。 */
    private long renamePrice = 50;
    /** 公會（2026-10）：公會改名卡、公會倉庫頁（頁是公會的，不是買的人的）。 */
    private long guildRenamePrice = 300;
    private long guildStashPrice = 300;
    private int guildStashMax;
    private int listingMax = 15;
    private long cardPrice = 100;
    private int cardDays = 30;
    private int dailyCardCoins = 1;
    private int dailyFashionCoins = 1;
    /** 2026-10 月卡福利：買下時送的時裝幣、月卡幣；連續第 2 個月、第 3 個月起多送的月卡幣；每天免費開一次的箱子。 */
    private int welcomeFashionCoins = 5;
    private int welcomeCardCoins = 10;
    private int renewalSecond = 5;
    private int renewalThird = 10;
    private String freeBox = "random";
    private long boostPrice = 10;
    private int boostMinutes = 15;
    private int boostMaxMinutes = 240;
    private Map<String, BoostType> boosts = Map.of();
    /** 2026-10 個人加倍藥（boosts.personal）：價格、一罐幾分鐘、最多排幾分鐘。 */
    private long personalBoostPrice = 10;
    private int personalBoostMinutes = 20;
    private int personalBoostMaxMinutes = 240;
    private List<Tier> tiers = List.of();
    private List<LoginTitle> loginTitles = List.of();
    private Map<String, Cosmetic> cosmetics = Map.of();
    private Map<String, Bundle> bundles = Map.of();
    private Map<String, LimitedTitle> limitedTitles = Map.of();
    private Map<Rarity, Integer> duplicateRefund = Map.of();
    private Map<String, Box> boxes = Map.of();
    private List<CardItem> cardShop = List.of();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            enabled = false;
            return;
        }
        parse(YamlNode.load(file));
    }

    /** 讀一份設定（單元測試直接給）；有錯丟 IllegalArgumentException，不會改到現在的設定。 */
    public void parse(final YamlNode root) {
        final SponsorConfig fresh = new SponsorConfig();
        fresh.read(root);
        copyFrom(fresh);
    }

    private void read(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        enabled = settings.bool("enabled", true);
        loginMinutes = Math.max(1, Math.min(24 * 60, settings.integer("login_minutes", 30)));
        final YamlNode slot = root.node("character_slot");
        slotPrice = positive(slot, "price", 100);
        slotMax = Math.max(1, slot.integer("max", 200));
        final YamlNode stash = root.node("stash_page");
        stashPrice = positive(stash, "price", 100);
        stashMax = Math.max(0, Math.min(MAX_EXTRA_STASH - 30, stash.integer("max", 0)));
        final YamlNode bag = root.node("bag_page");
        bagPrice = positive(bag, "price", 300);
        bagMax = Math.max(0, Math.min(MAX_EXTRA_BAG - 10, bag.integer("max", 0)));
        final YamlNode pouch = root.node("pouch_upgrade");
        pouchPrice = positive(pouch, "price", 150);
        pouchStep = Math.max(1, pouch.integer("step", 2500));
        pouchMax = Math.max(0, Math.min(20, pouch.integer("max", 4)));
        final YamlNode mapNode = root.node("map_upgrade");
        mapPrice = positive(mapNode, "price", 150);
        mapStep = Math.max(1, mapNode.integer("step", 500));
        mapMax = Math.max(0, Math.min(20, mapNode.integer("max", 4)));
        final YamlNode stashCurrencyNode = root.node("stash_currency_upgrade");
        stashCurrencyPrice = positive(stashCurrencyNode, "price", 150);
        stashCurrencyStep = Math.max(1, stashCurrencyNode.integer("step", 5000));
        stashCurrencyMax = Math.max(0, Math.min(20, stashCurrencyNode.integer("max", 4)));
        final YamlNode listing = root.node("listing");
        listingPrice = positive(listing, "price", 50);
        listingMax = Math.max(0, listing.integer("max", 0));
        renamePrice = positive(root.node("rename_card"), "price", 50);
        guildRenamePrice = positive(root.node("guild_rename_card"), "price", 300);
        final YamlNode guildStash = root.node("guild_stash_page");
        guildStashPrice = positive(guildStash, "price", 300);
        guildStashMax = Math.max(0, Math.min(tw.exilecore.guild.GuildStash.MAX_PAGES, guildStash.integer("max", 0)));
        final YamlNode card = root.node("monthly_card");
        cardPrice = positive(card, "price", 100);
        cardDays = Math.max(1, Math.min(366, card.integer("days", 30)));
        dailyCardCoins = Math.max(0, card.integer("daily_card_coins", 1));
        dailyFashionCoins = Math.max(0, card.integer("daily_fashion_coins", 1));
        welcomeFashionCoins = Math.max(0, card.integer("welcome_fashion_coins", 5));
        welcomeCardCoins = Math.max(0, card.integer("welcome_card_coins", 10));
        final YamlNode renewal = card.node("renewal_bonus");
        renewalSecond = Math.max(0, renewal.integer("second", 5));
        renewalThird = Math.max(0, renewal.integer("third", 10));
        freeBox = card.string("daily_free_box", "random").trim();

        final YamlNode boostNode = root.node("boosts");
        boostPrice = positive(boostNode, "price", 10);
        boostMinutes = Math.max(1, Math.min(24 * 60, boostNode.integer("minutes", 15)));
        boostMaxMinutes = Math.max(boostMinutes, Math.min(7 * 24 * 60, boostNode.integer("max_minutes", 240)));
        final Map<String, BoostType> boostMap = new LinkedHashMap<>();
        final YamlNode types = boostNode.node("types");
        for (final String id : types.keys()) {
            final YamlNode node = types.node(id);
            checkId(id, node.path());
            final double experience = node.decimal("experience", 1);
            final double quantity = node.decimal("quantity", 0) / 100.0;
            final double rarity = node.decimal("rarity", 0) / 100.0;
            final double gold = node.decimal("gold", 1);
            if (experience < 1 || experience > 10 || gold < 1 || gold > 10 || quantity < 0 || quantity > 5 || rarity < 0 || rarity > 5) {
                throw new IllegalArgumentException(node.path() + "：倍數要在 1–10 倍、加成要在 0–500% 之間");
            }
            if (experience == 1 && gold == 1 && quantity == 0 && rarity == 0) {
                throw new IllegalArgumentException(node.path() + "：沒有寫加倍什麼（experience、quantity、rarity、gold）");
            }
            final String name = node.string("name", id).trim();
            boostMap.put(id, new BoostType(id, name, node.string("label", name).trim(), node.string("icon", "EXPERIENCE_BOTTLE").trim(),
                    experience, quantity, rarity, gold));
        }
        boosts = Collections.unmodifiableMap(boostMap);
        final YamlNode personalNode = boostNode.node("personal");
        personalBoostPrice = positive(personalNode, "price", 10);
        personalBoostMinutes = Math.max(1, Math.min(24 * 60, personalNode.integer("minutes", 20)));
        personalBoostMaxMinutes = Math.max(personalBoostMinutes, Math.min(7 * 24 * 60, personalNode.integer("max_minutes", 240)));

        final List<Tier> tierList = new ArrayList<>();
        long previous = 0;
        for (final YamlNode node : root.list("tiers")) {
            final long amount = node.integer("amount", 0);
            if (amount <= previous) {
                throw new IllegalArgumentException(node.path() + ".amount：要比前一階大（由小排到大）");
            }
            previous = amount;
            final String id = node.requireString("id").trim();
            checkId(id, node.path());
            final String color = color(node.string("color", "#ffffff").trim(), node.path() + ".color");
            final String nameColor = nameColor(node.string("name_color", color).trim(), node.path() + ".name_color");
            tierList.add(new Tier(amount, id, node.requireString("title").trim(), color, nameColor,
                    Math.max(0, Math.min(30, node.integer("stash_pages", 0))),
                    Math.max(0, Math.min(10, node.integer("bag_pages", 0))),
                    Math.max(0, Math.min(45, node.integer("listings", 0))),
                    node.bool("bypass_full", false), node.bool("chat_color", false), node.bool("custom_title", false),
                    node.bool("camp_fly", false),
                    Math.max(0, Math.min(100, node.decimal("experience", 0))),
                    Math.max(0, Math.min(100, node.decimal("gold", 0))),
                    node.has("death_penalty") ? Math.max(0, Math.min(100, node.decimal("death_penalty", 10))) : -1));
        }
        tiers = List.copyOf(tierList);

        final List<LoginTitle> loginList = new ArrayList<>();
        int previousDays = 0;
        for (final YamlNode node : root.list("login_titles")) {
            final int days = node.integer("days", 0);
            if (days <= previousDays) {
                throw new IllegalArgumentException(node.path() + ".days：要比前一個大（由小排到大）");
            }
            previousDays = days;
            final String id = node.requireString("id").trim();
            checkId(id, node.path());
            final String titleName = node.requireString("name").trim();
            loginList.add(new LoginTitle(days, id, titleName, color(node.string("color", "#ffffff").trim(), node.path() + ".color"),
                    tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "稱號「" + titleName + "」")));
        }
        loginTitles = List.copyOf(loginList);

        final Map<String, Cosmetic> cosmeticMap = new LinkedHashMap<>();
        final YamlNode cosmeticNode = root.node("cosmetics");
        for (final String id : cosmeticNode.keys()) {
            final YamlNode node = cosmeticNode.node(id);
            checkId(id, node.path());
            final String particle = node.string("particle", "END_ROD").trim().toUpperCase(Locale.ROOT);
            final String color = node.has("color") ? color(node.string("color", "").trim(), node.path() + ".color") : null;
            final String type = node.string("type", "particle").trim().toLowerCase(Locale.ROOT);
            final boolean skin = type.equals("skin");
            String emote = null;
            if (type.equals("emote")) {
                emote = node.requireString("emote").trim().toLowerCase(Locale.ROOT);
                if (!EMOTES.contains(emote)) {
                    throw new IllegalArgumentException(node.path() + ".emote：要寫 sidestep（閃身步）、kick_ball（踢球）、bear_shake（抖毛）、head_bob（搖頭晃腦）、king_sit（王之坐姿）、love_you（我真的特別愛你）或 six_seven（67）");
                }
            } else if (!skin && !type.equals("particle")) {
                throw new IllegalArgumentException(node.path() + ".type：要寫 particle（粒子特效）、skin（裝備幻化）或 emote（動作）");
            }
            String skinSlot = null;
            String art = null;
            if (skin) {
                skinSlot = node.requireString("slot").trim().toLowerCase(Locale.ROOT);
                if (!SKIN_SLOTS.contains(skinSlot)) {
                    throw new IllegalArgumentException(node.path() + ".slot：幻化要寫 weapon、bow、crossbow、offhand、armor、headgear（頭飾）、back（背飾）或 pet（寵物）");
                }
                art = node.requireString("art").trim().toLowerCase(Locale.ROOT);
                if (!ID.matcher(art).matches()) {
                    throw new IllegalArgumentException(node.path() + ".art：要寫美術的代號（web/skins/ 裡的，例如 starlight）");
                }
            }
            final String limited = node.string("limited", "").trim();
            if (!limited.isEmpty() && node.integer("fashion_price", 0) > 0) {
                throw new IllegalArgumentException(node.path() + "：限定（limited）的時裝不能用時裝幣換，fashion_price 要是 0");
            }
            cosmeticMap.put(id, new Cosmetic(id, node.requireString("name").trim(), Rarity.parse(node.string("rarity", "common"), node.path()),
                    Style.parse(node.string("style", "aura"), node.path()), particle, color, node.bool("rainbow", false),
                    limited.isEmpty() ? Math.max(0, node.integer("fashion_price", 0)) : 0, node.string("description", "").trim(), skin, skinSlot, art, emote,
                    limited.isEmpty() ? null : limited));
        }
        cosmetics = Collections.unmodifiableMap(cosmeticMap);

        final Map<String, LimitedTitle> limitedMap = new LinkedHashMap<>();
        final YamlNode limitedNode = root.node("limited_titles");
        for (final String id : limitedNode.keys()) {
            final YamlNode node = limitedNode.node(id);
            checkId(id, node.path());
            final String source = node.string("limited", "").trim();
            if (source.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".limited：要寫來源（例如「S1 贊助禮包」）");
            }
            final String limitedName = node.requireString("name").trim();
            limitedMap.put(id, new LimitedTitle(id, limitedName, nameColor(node.string("color", "#ffffff").trim(), node.path() + ".color"), source,
                    tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "稱號「" + limitedName + "」")));
        }
        limitedTitles = Collections.unmodifiableMap(limitedMap);

        final Map<String, Bundle> bundleMap = new LinkedHashMap<>();
        final YamlNode bundleNode = root.node("bundles");
        for (final String id : bundleNode.keys()) {
            final YamlNode node = bundleNode.node(id);
            checkId(id, node.path());
            final List<String> ids = new ArrayList<>();
            for (final String raw : node.strings("cosmetics")) {
                final String cosmetic = raw.trim();
                if (cosmetic.startsWith("title:")) {
                    if (!limitedMap.containsKey(cosmetic.substring("title:".length()))) {
                        throw new IllegalArgumentException(node.path() + ".cosmetics：沒有「" + cosmetic + "」這個限定稱號（limited_titles）");
                    }
                } else if (!cosmeticMap.containsKey(cosmetic)) {
                    throw new IllegalArgumentException(node.path() + ".cosmetics：沒有「" + cosmetic + "」這個時裝（cosmetics）");
                }
                ids.add(cosmetic);
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".cosmetics：沒有東西");
            }
            bundleMap.put(id, new Bundle(id, node.string("name", id).trim(), ids));
        }
        bundles = Collections.unmodifiableMap(bundleMap);

        final Map<Rarity, Integer> refund = new LinkedHashMap<>();
        final YamlNode refundNode = root.node("duplicate_refund");
        for (final Rarity rarity : Rarity.values()) {
            refund.put(rarity, Math.max(0, refundNode.integer(rarity.name().toLowerCase(Locale.ROOT), 0)));
        }
        duplicateRefund = Collections.unmodifiableMap(refund);

        final Map<String, Box> boxMap = new LinkedHashMap<>();
        final YamlNode boxNode = root.node("boxes");
        for (final String id : boxNode.keys()) {
            final YamlNode node = boxNode.node(id);
            checkId(id, node.path());
            final Map<String, Double> weights = new LinkedHashMap<>();
            final YamlNode weightNode = node.node("weights");
            for (final String cosmetic : weightNode.keys()) {
                if (!cosmeticMap.containsKey(cosmetic)) {
                    throw new IllegalArgumentException(weightNode.path() + "：沒有「" + cosmetic + "」這個外觀特效（cosmetics）");
                }
                if (cosmeticMap.get(cosmetic).isLimited()) {
                    throw new IllegalArgumentException(weightNode.path() + "." + cosmetic + "：限定的時裝（" + cosmeticMap.get(cosmetic).limitedTag() + "）不能放進抽獎箱");
                }
                final double weight = weightNode.decimal(cosmetic, 0);
                if (weight <= 0) {
                    throw new IllegalArgumentException(weightNode.path() + "." + cosmetic + "：權重要大於 0");
                }
                weights.put(cosmetic, weight);
            }
            if (weights.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".weights：沒有東西");
            }
            final long star = Math.max(0, node.integer("star_price", 0));
            final long fashion = Math.max(0, node.integer("fashion_price", 0));
            if (star == 0 && fashion == 0) {
                throw new IllegalArgumentException(node.path() + "：star_price、fashion_price 至少要有一個");
            }
            boxMap.put(id, new Box(id, node.string("name", id).trim(), node.string("icon", "CHEST").trim(), star, fashion,
                    Math.max(0, node.integer("pity", 0)), node.string("description", "").trim(), weights));
        }
        boxes = Collections.unmodifiableMap(boxMap);

        final List<CardItem> cardList = new ArrayList<>();
        for (final YamlNode node : root.list("card_shop")) {
            final String id = node.requireString("id").trim();
            checkId(id, node.path());
            final CardKind kind;
            try {
                kind = CardKind.valueOf(node.requireString("kind").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".kind：要寫 title、cosmetic、fashion_coins、character_slot、rename_card");
            }
            final long price = positive(node, "price", 0);
            String name = node.string("name", "").trim();
            String cosmetic = null;
            switch (kind) {
                case TITLE -> {
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException(node.path() + ".name：稱號要寫名字");
                    }
                }
                case COSMETIC -> {
                    cosmetic = node.requireString("cosmetic").trim();
                    if (!cosmeticMap.containsKey(cosmetic)) {
                        throw new IllegalArgumentException(node.path() + ".cosmetic：沒有「" + cosmetic + "」這個外觀特效");
                    }
                    if (cosmeticMap.get(cosmetic).isLimited()) {
                        throw new IllegalArgumentException(node.path() + ".cosmetic：限定的時裝（" + cosmeticMap.get(cosmetic).limitedTag() + "）不能用月卡幣換");
                    }
                    name = name.isEmpty() ? cosmeticMap.get(cosmetic).name() : name;
                }
                case FASHION_COINS -> name = name.isEmpty() ? "時裝幣" : name;
                case CHARACTER_SLOT -> name = name.isEmpty() ? "角色欄位 +1" : name;
                case RENAME_CARD -> name = name.isEmpty() ? "改名卡" : name;
                default -> {
                }
            }
            cardList.add(new CardItem(id, kind, price, name, color(node.string("color", "#ffffff").trim(), node.path() + ".color"), cosmetic,
                    Math.max(1, node.integer("amount", 1)), node.bool("hidden", false)));
        }
        cardShop = List.copyOf(cardList);

        // 稱號的代號不能重複（同一個稱號表）
        final java.util.Set<String> titleIds = new java.util.HashSet<>();
        for (final Tier tier : tiers) {
            unique(titleIds, tier.id(), "tiers");
        }
        for (final LoginTitle title : loginTitles) {
            unique(titleIds, title.id(), "login_titles");
        }
        for (final CardItem item : cardShop) {
            if (item.kind() == CardKind.TITLE) {
                unique(titleIds, item.id(), "card_shop");
            }
        }
        for (final LimitedTitle title : limitedTitles.values()) {
            unique(titleIds, title.id(), "limited_titles");
        }
    }

    private static void unique(final java.util.Set<String> ids, final String id, final String where) {
        if (!ids.add(id)) {
            throw new IllegalArgumentException(FILE + " 的 " + where + "：稱號代號「" + id + "」重複了");
        }
    }

    private static long positive(final YamlNode node, final String key, final long def) {
        final long value = node.integer(key, (int) def);
        if (value <= 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要大於 0");
        }
        return value;
    }

    private static void checkId(final String id, final String where) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(where + "：代號「" + id + "」只能用小寫英文、數字、底線（最多 32 字）");
        }
    }

    private static String color(final String text, final String where) {
        if (!COLOR.matcher(text).matches()) {
            throw new IllegalArgumentException(where + "：顏色要寫成 \"#rrggbb\"，卻是「" + text + "」");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /** "#rrggbb" 或 "gradient:#rrggbb:#rrggbb"。 */
    static String nameColor(final String text, final String where) {
        if (text.startsWith("gradient:")) {
            final String[] parts = text.substring("gradient:".length()).split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException(where + "：漸層要寫成 \"gradient:#rrggbb:#rrggbb\"");
            }
            final List<String> colors = new ArrayList<>();
            for (final String part : parts) {
                colors.add(color(part.trim(), where));
            }
            return "gradient:" + String.join(":", colors);
        }
        return color(text, where);
    }

    private void copyFrom(final SponsorConfig other) {
        enabled = other.enabled;
        loginMinutes = other.loginMinutes;
        slotPrice = other.slotPrice;
        slotMax = other.slotMax;
        stashPrice = other.stashPrice;
        stashMax = other.stashMax;
        bagPrice = other.bagPrice;
        pouchPrice = other.pouchPrice;
        pouchStep = other.pouchStep;
        pouchMax = other.pouchMax;
        mapPrice = other.mapPrice;
        mapStep = other.mapStep;
        mapMax = other.mapMax;
        stashCurrencyPrice = other.stashCurrencyPrice;
        stashCurrencyStep = other.stashCurrencyStep;
        stashCurrencyMax = other.stashCurrencyMax;
        bagMax = other.bagMax;
        listingPrice = other.listingPrice;
        renamePrice = other.renamePrice;
        guildRenamePrice = other.guildRenamePrice;
        guildStashPrice = other.guildStashPrice;
        guildStashMax = other.guildStashMax;
        listingMax = other.listingMax;
        cardPrice = other.cardPrice;
        cardDays = other.cardDays;
        dailyCardCoins = other.dailyCardCoins;
        dailyFashionCoins = other.dailyFashionCoins;
        welcomeFashionCoins = other.welcomeFashionCoins;
        welcomeCardCoins = other.welcomeCardCoins;
        renewalSecond = other.renewalSecond;
        renewalThird = other.renewalThird;
        freeBox = other.freeBox;
        boostPrice = other.boostPrice;
        boostMinutes = other.boostMinutes;
        boostMaxMinutes = other.boostMaxMinutes;
        boosts = other.boosts;
        personalBoostPrice = other.personalBoostPrice;
        personalBoostMinutes = other.personalBoostMinutes;
        personalBoostMaxMinutes = other.personalBoostMaxMinutes;
        tiers = other.tiers;
        loginTitles = other.loginTitles;
        cosmetics = other.cosmetics;
        bundles = other.bundles;
        limitedTitles = other.limitedTitles;
        duplicateRefund = other.duplicateRefund;
        boxes = other.boxes;
        cardShop = other.cardShop;
    }

    public boolean enabled() {
        return enabled;
    }

    public int loginMinutes() {
        return loginMinutes;
    }

    public long slotPrice() {
        return slotPrice;
    }

    public int slotMax() {
        return slotMax;
    }

    /** 上架格的上限（防手滑）。 */
    public static final int MAX_LISTINGS = 1000;

    /** 買了 bought 次、上限 max（0 = 沒有上限）：算數的次數。 */
    public static long capped(final long bought, final int max) {
        return max <= 0 ? bought : Math.min(max, bought);
    }

    /** 買到上限了沒（max 0 = 沒有上限，永遠沒到）。 */
    public static boolean atMax(final long bought, final int max) {
        return max > 0 && bought >= max;
    }

    public long stashPrice() {
        return stashPrice;
    }

    /** 公會改名卡的價格（星幣）。 */
    public long guildRenamePrice() {
        return guildRenamePrice;
    }

    /** 公會倉庫頁的價格（星幣）。 */
    public long guildStashPrice() {
        return guildStashPrice;
    }

    /** 一個公會最多用星幣買幾頁（0 = 只看公會倉庫的總頁數上限）。 */
    public int guildStashMax() {
        return guildStashMax;
    }

    public int stashMax() {
        return stashMax;
    }

    public long bagPrice() {
        return bagPrice;
    }

    public int bagMax() {
        return bagMax;
    }

    public long listingPrice() {
        return listingPrice;
    }

    public long renamePrice() {
        return renamePrice;
    }

    public int listingMax() {
        return listingMax;
    }

    public long cardPrice() {
        return cardPrice;
    }

    public int cardDays() {
        return cardDays;
    }

    public int dailyCardCoins() {
        return dailyCardCoins;
    }

    public int dailyFashionCoins() {
        return dailyFashionCoins;
    }

    public int welcomeFashionCoins() {
        return welcomeFashionCoins;
    }

    public int welcomeCardCoins() {
        return welcomeCardCoins;
    }

    /** 連續第 streak 個月（1 = 第一次或斷過）多送的月卡幣。 */
    public int renewalBonus(final int streak) {
        return streak <= 1 ? 0 : streak == 2 ? renewalSecond : renewalThird;
    }

    /** 有月卡每天免費開一次的箱子代號（空字串 = 沒有這個福利）。 */
    public String freeBox() {
        return freeBox;
    }

    public long boostPrice() {
        return boostPrice;
    }

    public int boostMinutes() {
        return boostMinutes;
    }

    public int boostMaxMinutes() {
        return boostMaxMinutes;
    }

    public long personalBoostPrice() {
        return personalBoostPrice;
    }

    public int personalBoostMinutes() {
        return personalBoostMinutes;
    }

    public int personalBoostMaxMinutes() {
        return personalBoostMaxMinutes;
    }

    public Map<String, BoostType> boosts() {
        return boosts;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public List<LoginTitle> loginTitles() {
        return loginTitles;
    }

    public Map<String, Cosmetic> cosmetics() {
        return cosmetics;
    }

    public int duplicateRefund(final Rarity rarity) {
        return duplicateRefund.getOrDefault(rarity, 0);
    }

    public Map<String, Box> boxes() {
        return boxes;
    }

    public List<CardItem> cardShop() {
        return cardShop;
    }

    /** 一組時裝（2026-10，bundles：管理網頁、指令一次發整組）。 */
    public Map<String, Bundle> bundles() {
        return bundles;
    }

    /** 限定稱號（2026-10，limited_titles：只有發放拿得到）。 */
    public Map<String, LimitedTitle> limitedTitles() {
        return limitedTitles;
    }

    /** 累積到 total 時最高的一階（還沒到 null）。 */
    /** 到過的每一階的經驗加成加起來（%；2026-10 疊加）。 */
    public double reachedExperience(final long total) {
        double sum = 0;
        for (final Tier tier : tiers) {
            if (total >= tier.amount()) {
                sum += tier.experience();
            }
        }
        return sum;
    }

    /** 到過的每一階的金幣加成加起來（%；2026-10 疊加）。 */
    public double reachedGold(final long total) {
        double sum = 0;
        for (final Tier tier : tiers) {
            if (total >= tier.amount()) {
                sum += tier.gold();
            }
        }
        return sum;
    }

    public Tier tierFor(final long total) {
        Tier best = null;
        for (final Tier tier : tiers) {
            if (total >= tier.amount()) {
                best = tier;
            }
        }
        return best;
    }

    /** 下一階（都到了 null）。 */
    public Tier nextTier(final long total) {
        for (final Tier tier : tiers) {
            if (total < tier.amount()) {
                return tier;
            }
        }
        return null;
    }

    public long pouchPrice() {
        return pouchPrice;
    }

    public int pouchStep() {
        return pouchStep;
    }

    public int pouchMax() {
        return pouchMax;
    }

    public long stashCurrencyPrice() {
        return stashCurrencyPrice;
    }

    public int stashCurrencyStep() {
        return stashCurrencyStep;
    }

    public int stashCurrencyMax() {
        return stashCurrencyMax;
    }

    public long mapPrice() {
        return mapPrice;
    }

    public int mapStep() {
        return mapStep;
    }

    public int mapMax() {
        return mapMax;
    }
}
