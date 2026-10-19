package tw.exilecore.card;

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
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * 星諭卡（2026-10-19，data/cards.yml）：集滿一套換獎勵。卡本身是通貨（currencies.yml 的 card_ 開頭），這裡是規則。純資料，不依賴 Bukkit。
 * <ul>
 *   <li>掉落：地圖怪先照 {@link #chance} 擲有沒有卡，再從這個地點能掉的卡照 weight 抽一張（{@link #pick}）。</li>
 *   <li>兌換：集滿 set 張換 reward。</li>
 *   <li>星諭牌堆：打開照 weight × deck[tier] 從全部的卡抽一張（{@link #pickAny}；2026-10-19 照等級打折，稀有卡不會跟常見卡一樣常見）。</li>
 *   <li>賭卡（PoE 花園）：一疊 n 張花 gamble[tier] × n 金幣，變成 0～2n 張（每個整數一樣機率，{@link #gamble}）。</li>
 * </ul>
 */
public final class CardConfig {

    public static final String FILE = "cards.yml";
    /** 星諭牌堆的通貨代號。 */
    public static final String DECK = "stacked_deck";
    /** 星諭卡的通貨代號前綴。 */
    public static final String PREFIX = "card_";

    /** 卡的等級：賭卡的金幣、兌換時要不要全服公告。 */
    public enum Tier {
        COMMON("常見"), MID("中階"), RARE("稀有"), CHASE("追逐");

        private final String displayName;

        Tier(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** 兌換時全服公告。 */
        public boolean announced() {
            return this == RARE || this == CHASE;
        }
    }

    /** 掉落的地方：map（那張圖的任何怪）、boss（那張圖的 Boss）、event（完成那種事件）、any（任何地圖的怪）。 */
    public record Source(String kind, String key) {
        static Source parse(final String where, final String text) {
            final String raw = text.trim().toLowerCase(Locale.ROOT);
            if (raw.equals("any")) {
                return new Source("any", "");
            }
            final int colon = raw.indexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) {
                throw new IllegalArgumentException(where + "：來源要寫 map:地圖、boss:地圖、event:事件 或 any，卻是「" + text + "」");
            }
            final String kind = raw.substring(0, colon);
            if (!kind.equals("map") && !kind.equals("boss") && !kind.equals("event")) {
                throw new IllegalArgumentException(where + "：不認得的來源種類「" + kind + "」（map、boss、event、any）");
            }
            return new Source(kind, raw.substring(colon + 1));
        }
    }

    /** 換到什麼：currency（＋amount）、unique（一件指定的）、unique_pool（某個來源的傳奇隨機一件）、essence（隨機星魄 amount 顆）。 */
    public record Reward(String kind, String id, int amount) {
    }

    public record Card(String id, Tier tier, int set, int weight, List<Source> sources, int minTier, Reward reward) {
        public Card {
            sources = List.copyOf(sources);
        }

        /** 地圖裡殺怪時能不能掉（boss = 這隻是那張圖的 Boss）。 */
        public boolean dropsFromKill(final String mapId, final int tier, final boolean boss) {
            if (tier < minTier) {
                return false;
            }
            for (final Source source : sources) {
                switch (source.kind()) {
                    case "any" -> {
                        return true;
                    }
                    case "map" -> {
                        if (source.key().equals(mapId)) {
                            return true;
                        }
                    }
                    case "boss" -> {
                        if (boss && source.key().equals(mapId)) {
                            return true;
                        }
                    }
                    default -> {
                    }
                }
            }
            return false;
        }

        /** 完成這種事件時能不能掉。 */
        public boolean dropsFromEvent(final String event, final int tier) {
            if (tier < minTier) {
                return false;
            }
            for (final Source source : sources) {
                if (source.kind().equals("event") && source.key().equals(event)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 掉卡的機率。 */
    public record Chances(double normal, double magic, double rare, double unique, double boss, double finalBoss, double event) {
        static final Chances DEFAULT = new Chances(0.003, 0.008, 0.03, 0.03, 0.25, 0.4, 0.08);
    }

    private Map<String, Card> cards = Map.of();
    private Chances chances = Chances.DEFAULT;
    private int itemLevel = 84;
    private Map<Tier, Long> gamble = Map.of();
    /** 2026-10-19 星諭牌堆：每個等級的卡權重再乘幾倍（settings.deck；沒寫 = 1）。 */
    private Map<Tier, Double> deck = Map.of();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            cards = Map.of();
            return;
        }
        load(YamlNode.load(file));
    }

    /** 核心邏輯（單元測試直接餵 YAML）；全部讀成功才換上。 */
    public void load(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        final YamlNode chance = settings.node("chance");
        final Chances parsedChances = new Chances(
                probability(chance, "normal", Chances.DEFAULT.normal()), probability(chance, "magic", Chances.DEFAULT.magic()),
                probability(chance, "rare", Chances.DEFAULT.rare()), probability(chance, "unique", Chances.DEFAULT.unique()),
                probability(chance, "boss", Chances.DEFAULT.boss()), probability(chance, "final_boss", Chances.DEFAULT.finalBoss()),
                probability(chance, "event", Chances.DEFAULT.event()));
        final Map<Tier, Long> parsedGamble = new LinkedHashMap<>();
        final YamlNode costs = settings.node("gamble");
        for (final Tier tier : Tier.values()) {
            parsedGamble.put(tier, Math.max(0L, (long) costs.decimal(tier.name().toLowerCase(Locale.ROOT), 0)));
        }
        final Map<Tier, Double> parsedDeck = new LinkedHashMap<>();
        final YamlNode deckNode = settings.node("deck");
        for (final Tier tier : Tier.values()) {
            final double factor = deckNode.decimal(tier.name().toLowerCase(Locale.ROOT), 1);
            if (factor < 0) {
                throw new IllegalArgumentException(deckNode.path() + "." + tier.name().toLowerCase(Locale.ROOT) + "：不能是負的");
            }
            parsedDeck.put(tier, factor);
        }
        final Map<String, Card> parsed = new LinkedHashMap<>();
        final YamlNode list = root.node("cards");
        for (final String id : list.keys()) {
            final YamlNode node = list.node(id);
            final String where = node.path();
            if (!id.startsWith(PREFIX)) {
                throw new IllegalArgumentException(where + "：星諭卡的代號要是 " + PREFIX + " 開頭（才會自動進「星諭卡」那一組）");
            }
            final Tier tier;
            try {
                tier = Tier.valueOf(node.string("tier", "common").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".tier：要是 common、mid、rare 或 chase");
            }
            final int set = node.integer("set", 0);
            if (set < 1 || set > 50) {
                throw new IllegalArgumentException(where + ".set：一套要 1 到 50 張");
            }
            final List<Source> sources = new ArrayList<>();
            for (final String text : node.strings("sources")) {
                sources.add(Source.parse(where + ".sources", text));
            }
            if (sources.isEmpty()) {
                throw new IllegalArgumentException(where + ".sources：至少要一個掉落的地方");
            }
            parsed.put(id, new Card(id, tier, set, Math.max(0, node.integer("weight", 100)), sources,
                    Math.max(1, node.integer("min_tier", 1)), reward(where + ".reward", node.node("reward"))));
        }
        cards = Collections.unmodifiableMap(parsed);
        chances = parsedChances;
        itemLevel = Math.max(1, Math.min(100, settings.integer("item_level", 84)));
        gamble = Collections.unmodifiableMap(parsedGamble);
        deck = Collections.unmodifiableMap(parsedDeck);
    }

    /** 2026-10-19：星諭牌堆裡這張卡的權重（weight × 這個等級的倍數）。 */
    public double deckWeight(final Card card) {
        return card.weight() * deck.getOrDefault(card.tier(), 1.0);
    }

    private static double probability(final YamlNode node, final String key, final double def) {
        return Math.max(0, Math.min(1, node.decimal(key, def)));
    }

    private static Reward reward(final String where, final YamlNode node) {
        if (node.has("currency")) {
            return new Reward("currency", node.requireString("currency").trim(), Math.max(1, node.integer("amount", 1)));
        }
        if (node.has("unique")) {
            return new Reward("unique", node.requireString("unique").trim(), 1);
        }
        if (node.has("unique_pool")) {
            return new Reward("unique_pool", node.requireString("unique_pool").trim().toLowerCase(Locale.ROOT), 1);
        }
        if (node.has("essence")) {
            return new Reward("essence", "", Math.max(1, node.integer("essence", 1)));
        }
        throw new IllegalArgumentException(where + "：要寫 currency（＋amount）、unique、unique_pool 或 essence");
    }

    /**
     * 資料檢查：獎勵的通貨、傳奇、每張卡在 currencies.yml 裡要存在。
     *
     * @param currency 有這種通貨
     * @param unique   有這件傳奇
     * @param pool     這個來源有傳奇可以抽
     */
    public List<String> validate(final Predicate<String> currency, final Predicate<String> unique, final Predicate<String> pool) {
        final List<String> problems = new ArrayList<>();
        if (!currency.test(DECK)) {
            problems.add(FILE + "：currencies.yml 沒有星諭牌堆 " + DECK);
        }
        for (final Card card : cards.values()) {
            if (!currency.test(card.id())) {
                problems.add(FILE + "：" + card.id() + " 不在 currencies.yml 裡（卡要先是一種通貨）");
            }
            final Reward reward = card.reward();
            switch (reward.kind()) {
                case "currency" -> {
                    if (!currency.test(reward.id())) {
                        problems.add(FILE + "：" + card.id() + " 換的通貨 " + reward.id() + " 不在 currencies.yml 裡");
                    }
                }
                case "unique" -> {
                    if (!unique.test(reward.id())) {
                        problems.add(FILE + "：" + card.id() + " 換的傳奇 " + reward.id() + " 不在 uniques.yml 裡");
                    }
                }
                case "unique_pool" -> {
                    if (!pool.test(reward.id())) {
                        problems.add(FILE + "：" + card.id() + " 換的傳奇來源 " + reward.id() + " 沒有任何傳奇");
                    }
                }
                default -> {
                }
            }
        }
        return problems;
    }

    public Card card(final String id) {
        return cards.get(id);
    }

    public Map<String, Card> cards() {
        return cards;
    }

    public Chances chances() {
        return chances;
    }

    public int itemLevel() {
        return itemLevel;
    }

    /** 賭一張這個等級的卡要多少金幣。 */
    public long gambleCost(final Tier tier) {
        return gamble.getOrDefault(tier, 0L);
    }

    /** 照 weight 從這些卡裡抽一張；都沒有（或權重都是 0）回傳 null。 */
    public static Card pick(final List<Card> candidates, final RandomGenerator random) {
        long total = 0;
        for (final Card card : candidates) {
            total += card.weight();
        }
        if (total <= 0) {
            return null;
        }
        long roll = (long) (random.nextDouble() * total);
        for (final Card card : candidates) {
            roll -= card.weight();
            if (roll < 0) {
                return card;
            }
        }
        return candidates.getLast();
    }

    /** 地圖裡殺怪時能掉的卡。 */
    public List<Card> forKill(final String mapId, final int tier, final boolean boss) {
        final List<Card> list = new ArrayList<>();
        for (final Card card : cards.values()) {
            if (card.dropsFromKill(mapId, tier, boss)) {
                list.add(card);
            }
        }
        return list;
    }

    /** 完成這種事件時能掉的卡。 */
    public List<Card> forEvent(final String event, final int tier) {
        final List<Card> list = new ArrayList<>();
        for (final Card card : cards.values()) {
            if (card.dropsFromEvent(event, tier)) {
                list.add(card);
            }
        }
        return list;
    }

    /** 星諭牌堆：從全部的卡照 {@link #deckWeight}（weight × 等級的倍數）抽一張；都是 0 回傳 null。 */
    public Card pickAny(final RandomGenerator random) {
        double total = 0;
        for (final Card card : cards.values()) {
            total += deckWeight(card);
        }
        if (total <= 0) {
            return null;
        }
        double roll = random.nextDouble() * total;
        Card last = null;
        for (final Card card : cards.values()) {
            final double weight = deckWeight(card);
            if (weight <= 0) {
                continue;
            }
            last = card;
            roll -= weight;
            if (roll < 0) {
                return card;
            }
        }
        return last;
    }

    /** 賭卡（PoE 花園）：n 張變成 0 到 2n 張，每個整數一樣機率。 */
    public static int gamble(final int count, final RandomGenerator random) {
        return count <= 0 ? 0 : random.nextInt(2 * count + 1);
    }
}
