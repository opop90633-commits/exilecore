package tw.exilecore.admin;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapDrops;
import tw.exilecore.skill.GemData;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.skill.SupportDefinition;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 管理員發的物資：寫進 admin_deliveries，玩家在線就馬上發，離線的下次登入（選好角色之後）發。
 * <ul>
 *   <li>東西在領的那一刻才做出來（裝備、傳奇的數值那時候才擲；物品等級沒指定 = 領的角色的等級）。</li>
 *   <li>通貨：倉庫的通貨頁 → 隨身背包的通貨袋 → 背包；裝備、寶石、藥劑、地圖：背包 → 隨身背包。放不下的部分留著，
 *       整理後打 /claim 再領（下次登入也會再試）。</li>
 *   <li>金幣、功勳是帳號共用的；免費退點次數給領的那個角色。</li>
 *   <li>給出去的東西跟「這筆已經領了」在同一個資料庫交易裡寫（{@code CharacterService.commit}），當機也不會領兩次或少領。</li>
 *   <li>正在發的那幾筆不能取消；取消了的、在資料庫讀出來之後才取消的也不會發。</li>
 * </ul>
 * 除了 {@link #create} 在資料庫執行緒上，其他都在主執行緒。
 */
public final class DeliveryService {

    public static final String CURRENCY = "currency";
    public static final String GOLD = "gold";
    public static final String MERITS = "merits";
    public static final String REFUND = "refund";
    public static final String ITEM = "item";
    public static final String UNIQUE = "unique";
    public static final String GEM = "gem";
    public static final String FLASK = "flask";
    public static final String MAP = "map";
    /** 贊助的星幣（2026-10）：ref "paid" = 儲值（算進累積贊助）、"bonus" = 贈送。 */
    public static final String SPONSOR = "sponsor";
    /** 時裝（2026-10）：ref 時裝代號或 bundle:組代號（sponsor.yml 的 bundles）；一次一份，已經有的不重複給。絕版、限定的時裝只有這條路。 */
    public static final String COSMETIC = "cosmetic";
    /**
     * 收回（2026-10）：管理員從玩家身上拿走東西，跟發放走同一套（在線馬上、離線下次登入）。有多少拿多少，拿完這筆就結束。
     * take_sponsor：ref total（累積贊助：稱號、倉庫頁、背包頁、上架格跟著降）、coins（星幣）、card（月卡幣）、fashion（時裝幣）、
     * bought_stash／bought_bag／bought_listing（星幣買的倉庫頁、背包頁、上架格）；
     * take_currency：ref 通貨代號；take_gold：金幣；take_item：ref 物品編號（完整的 UUID）或名稱關鍵字，數量 = 最多拿幾件；
     * 找的地方是領的那個角色的背包、隨身背包，加上帳號的倉庫。
     */
    public static final String TAKE_SPONSOR = "take_sponsor";
    public static final String TAKE_CURRENCY = "take_currency";
    public static final String TAKE_GOLD = "take_gold";
    public static final String TAKE_ITEM = "take_item";
    /** 收回時裝、限定稱號（2026-10）：ref 跟 {@link #COSMETIC} 一樣（時裝代號、title:稱號代號、bundle:組代號）；沒有的就略過。 */
    public static final String TAKE_COSMETIC = "take_cosmetic";

    /** 生活（2026-10-18）：ref mat:材料、item:筏的方塊與設施、food:料理、horn（深淵號角）（LifeService.adminChoices）。 */
    public static final String LIFE = "life";
    /** 收回生活材料（2026-10-18）：ref mat:材料；方塊、料理、號角是物品，用 {@link #TAKE_ITEM}。 */
    public static final String TAKE_LIFE = "take_life";
    /** 收回功勳（2026-10-18，帳號共用）。 */
    public static final String TAKE_MERITS = "take_merits";
    /** 收回免費退點（2026-10-18，領的那個角色的）。 */
    public static final String TAKE_REFUND = "take_refund";

    static boolean isTake(final String kind) {
        return kind != null && kind.startsWith("take_");
    }

    /** 一次讀幾筆來發（還有更多的話發完再讀）。 */
    static final int BATCH = 100;
    /** 一次發放最多幾樣東西、幾個帳號 × 幾樣（防手滑）。 */
    static final int MAX_SPECS = 20;
    static final int MAX_ROWS = 50_000;
    /** 「所有帳號」最多幾個帳號。 */
    static final int MAX_ACCOUNTS = 20_000;
    /** 等帳號資料（功勳、倉庫）載入：最多再試幾次、隔多久。 */
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_TICKS = 100;
    /** 登入（套用角色）之後多久領：等倉庫、功勳的帳號資料載入。 */
    private static final long JOIN_DELAY_TICKS = 60;

    /** 一樣要發的東西（網頁送來的，還沒檢查）。level：物品等級、寶石等級、輔助寶石的階、地圖的階（0 = 照領的角色）。 */
    public record Spec(String kind, String ref, String rarity, int level, long amount, String affixes) {

        public Spec(final String kind, final String ref, final String rarity, final int level, final long amount) {
            this(kind, ref, rarity, level, amount, null);
        }
    }

    /** 檢查過的一樣東西：給人看的說明。 */
    public record Planned(Spec spec, String label) {
    }

    /** 發給誰。mode：players（指定的帳號 UUID）、names（名字：帳號或角色）、online（現在在線的人）、all（所有帳號）。 */
    public record Targets(String mode, List<UUID> uuids, List<String> names) {
    }

    /** 寫進去的結果；unknown 不是空的時候什麼都沒寫；repeated = 同一個請求重送（之前已經寫過，這次沒有再寫）。 */
    public record Created(String batchId, int accounts, int rows, List<UUID> accountIds, List<String> unknown, boolean repeated) {
    }

    /**
     * 這一筆發了多少。waiting：帳號資料還沒載入（功勳），等一下再發；missing：資料檔裡已經沒有這個東西（改過、模組關掉），
     * 留著等管理員處理（取消，或改回資料檔）。
     */
    private record Given(long given, String line, List<TradeLogRepository.Entry> log, CharacterRepository.ExtraWork work,
                         boolean waiting, boolean missing) {
        /** 放不下（一個都沒發）。 */
        static final Given NONE = new Given(0, null, List.of(), null, false, false);
        static final Given WAIT = new Given(0, null, List.of(), null, true, false);
        static final Given MISSING = new Given(0, null, List.of(), null, false, true);

        Given(final long given, final String line, final List<TradeLogRepository.Entry> log, final CharacterRepository.ExtraWork work) {
            this(given, line, log, work, false, false);
        }
    }

    private record Update(long id, long remaining) {
    }

    private final ExileCore plugin;
    private final AdminRepository repository;
    /** 正在領的玩家。 */
    private final Set<UUID> claiming = new HashSet<>();
    /** 正在領的時候又有新的（管理員剛發的）：這次領完再領一次。 */
    private final Set<UUID> rerun = new HashSet<>();
    /** 已經發到玩家身上、還沒寫進資料庫的編號：這段時間不能取消。 */
    private final Set<Long> inflight = new HashSet<>();
    /** 取消了的編號（資料庫讀出來之後才取消的不發）。只留最近的。 */
    private final Set<Long> cancelled = new LinkedHashSet<>();
    /** 存檔卡住（交易系統暫停、重試中）的那幾次領取：操作代號 → 編號。恢復之前不能取消。 */
    private final Map<String, Set<Long>> stuck = new HashMap<>();

    public DeliveryService(final ExileCore plugin, final AdminRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        plugin.characters().onRecovered(ops -> {
            for (final String op : ops) {
                final Set<Long> ids = stuck.remove(op);
                if (ids != null) {
                    inflight.removeAll(ids);
                }
            }
        });
    }

    // ---- 檢查（主執行緒：要查資料檔） ----

    /** 檢查要發的東西、做出說明；不合法丟 IllegalArgumentException（訊息給管理員看）。 */
    public List<Planned> plan(final List<Spec> specs) {
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("還沒選要發什麼");
        }
        if (specs.size() > MAX_SPECS) {
            throw new IllegalArgumentException("一次最多發 " + MAX_SPECS + " 樣");
        }
        final List<Planned> planned = new ArrayList<>();
        for (final Spec spec : specs) {
            final Spec normalized = normalize(spec);
            planned.add(new Planned(normalized, label(normalized)));
        }
        return planned;
    }

    /** 每種一次最多幾個。 */
    static long maxAmount(final String kind) {
        return switch (kind) {
            case CURRENCY -> 5_000;
            case GOLD -> 1_000_000_000L;
            case MERITS -> 10_000_000;
            case SPONSOR, TAKE_SPONSOR, TAKE_CURRENCY -> 1_000_000;
            case TAKE_GOLD -> 1_000_000_000_000L;
            case TAKE_ITEM -> 10_000;
            case REFUND -> 100;
            case LIFE -> 100_000;
            case TAKE_LIFE -> 1_000_000;
            case TAKE_MERITS -> 1_000_000_000L;
            case TAKE_REFUND -> 1_000;
            case COSMETIC, TAKE_COSMETIC -> 1;
            default -> 50;
        };
    }

    private static Spec normalize(final Spec spec) {
        return new Spec(spec.kind(), spec.ref() == null ? null : spec.ref().trim(),
                spec.rarity() == null ? null : spec.rarity().trim().toLowerCase(Locale.ROOT), spec.level(), spec.amount(),
                ITEM.equals(spec.kind()) || UNIQUE.equals(spec.kind()) ? AffixSpec.format(AffixSpec.parse(spec.affixes())) : null);
    }

    private String label(final Spec spec) {
        final String kind = spec.kind() == null ? "" : spec.kind();
        if (spec.amount() < 1 || spec.amount() > maxAmount(kind)) {
            throw new IllegalArgumentException("數量要在 1 到 " + String.format(Locale.ROOT, "%,d", maxAmount(kind)) + " 之間");
        }
        final String count = spec.amount() > 1 ? " ×" + spec.amount() : "";
        switch (kind) {
            case CURRENCY -> {
                final CurrencyType type = plugin.currencies().repository().get(ref(spec));
                if (type == null) {
                    throw new IllegalArgumentException("沒有這種通貨：" + spec.ref());
                }
                return type.name() + " ×" + spec.amount();
            }
            case GOLD -> {
                return String.format(Locale.ROOT, "金幣 %,d", spec.amount());
            }
            case MERITS -> {
                if (plugin.bounties() == null) {
                    throw new IllegalArgumentException("委託模組沒有啟用，不能發功勳");
                }
                return String.format(Locale.ROOT, "功勳 %,d", spec.amount());
            }
            case REFUND -> {
                return "免費退點 ×" + spec.amount();
            }
            case COSMETIC -> {
                if (plugin.sponsors() == null) {
                    throw new IllegalArgumentException("贊助模組沒有啟用，不能發時裝");
                }
                plugin.sponsors().resolveCosmetics(ref(spec)); // 沒有這個代號會丟 IllegalArgumentException
                return plugin.sponsors().cosmeticLabel(ref(spec));
            }
            case SPONSOR -> {
                if (plugin.sponsors() == null) {
                    throw new IllegalArgumentException("贊助模組沒有啟用，不能發星幣");
                }
                if ("card".equals(spec.ref()) || "fashion".equals(spec.ref())) {
                    return String.format(Locale.ROOT, "%s %,d", "card".equals(spec.ref()) ? "月卡幣" : "時裝幣", spec.amount());
                }
                if ("free_box".equals(spec.ref())) {
                    if (spec.amount() > 1000) {
                        throw new IllegalArgumentException("免費抽獎箱一次最多發 1000 箱");
                    }
                    return String.format(Locale.ROOT, "免費抽獎箱 %,d 箱", spec.amount());
                }
                if ("card_days".equals(spec.ref())) {
                    if (spec.amount() > 3650) {
                        throw new IllegalArgumentException("月卡一次最多加 3650 天");
                    }
                    return "月卡 " + spec.amount() + " 天";
                }
                if (!"paid".equals(spec.ref()) && !"bonus".equals(spec.ref())) {
                    throw new IllegalArgumentException("星幣要選「儲值」「贈送」「月卡幣」「時裝幣」「月卡天數」或「免費抽獎箱」");
                }
                return String.format(Locale.ROOT, "星幣 %,d（%s）", spec.amount(), "bonus".equals(spec.ref()) ? "贈送" : "儲值");
            }
            case ITEM -> {
                final ItemBase base = plugin.items().repository().base(ref(spec));
                if (base == null || base.slot() == ItemSlot.MAP) {
                    throw new IllegalArgumentException("沒有這種裝備基底：" + spec.ref());
                }
                final Rarity rarity = Rarity.parse(spec.rarity(), null);
                if (rarity == null || rarity == Rarity.UNIQUE) {
                    throw new IllegalArgumentException("稀有度要是普通、魔法或稀有（傳奇請選「傳奇」）");
                }
                checkLevel(spec.level(), 100, "物品等級");
                final List<AffixSpec.Entry> affixes = AffixSpec.parse(spec.affixes());
                if (!affixes.isEmpty()) {
                    // 先試做一件（物品等級照填的；0 = 照角色，用 100 試）：詞綴出不出得了、放不放得下
                    final ItemData trial = plugin.items().generator().generate(base, Rarity.NORMAL, spec.level() > 0 ? spec.level() : 100,
                            plugin.items().random());
                    AffixSpec.startRarity(plugin.items().generator(), trial, base.isJewel() && rarity == Rarity.NORMAL ? Rarity.MAGIC : rarity,
                            plugin.items().random());
                    final String problem = AffixSpec.applyToItem(plugin.items().repository(), plugin.items().generator(), trial, affixes,
                            plugin.items().random());
                    if (problem != null) {
                        throw new IllegalArgumentException(base.name() + "：" + problem);
                    }
                    return trial.rarity().displayName() + " " + base.name() + levelText(spec.level(), "物品等級") + count
                            + "［" + AffixSpec.describe(plugin.items().repository(), affixes, false) + "］";
                }
                return rarity.displayName() + " " + base.name() + levelText(spec.level(), "物品等級") + count;
            }
            case UNIQUE -> {
                final UniqueItem unique = plugin.items().repository().unique(ref(spec));
                if (unique == null || plugin.items().repository().base(unique.base()) == null) {
                    throw new IllegalArgumentException("沒有這件傳奇：" + spec.ref());
                }
                checkLevel(spec.level(), 100, "物品等級");
                final List<AffixSpec.Entry> affixes = AffixSpec.parse(spec.affixes());
                if (!affixes.isEmpty()) {
                    final String problem = AffixSpec.checkUnique(plugin.items().repository(), unique, affixes);
                    if (problem != null) {
                        throw new IllegalArgumentException(problem);
                    }
                    return "傳奇 " + unique.name() + levelText(spec.level(), "物品等級") + count
                            + "［" + AffixSpec.describe(plugin.items().repository(), affixes, true) + "］";
                }
                return "傳奇 " + unique.name() + levelText(spec.level(), "物品等級") + count;
            }
            case GEM -> {
                final GemData data = gem(spec, 1);
                final String level = spec.level() > 0 ? " Lv." + data.level() : "（等級 = 角色等級）";
                final String name = switch (data.kind()) {
                    case UNCUT_SKILL -> "未切割技能寶石" + level;
                    case UNCUT_SUPPORT -> "未切割輔助寶石 第 " + data.level() + " 階";
                    case SKILL -> "技能寶石 " + plugin.skills().config().skill(data.id()).name() + level;
                    case SUPPORT -> "輔助寶石 " + plugin.skills().config().support(data.id()).name();
                };
                return name + count;
            }
            case FLASK -> {
                if (plugin.flasks() == null) {
                    throw new IllegalArgumentException("藥劑模組沒有啟用");
                }
                final FlaskBase base = plugin.flasks().config().base(ref(spec));
                if (base == null) {
                    throw new IllegalArgumentException("沒有這種藥劑：" + spec.ref());
                }
                return base.name() + count;
            }
            case MAP -> {
                final MapConfig.MapDef map = mapDef(spec);
                final Rarity rarity = Rarity.parse(spec.rarity(), null);
                if (rarity == null || rarity == Rarity.UNIQUE) {
                    throw new IllegalArgumentException("地圖的稀有度要是普通、魔法或稀有");
                }
                final MapConfig config = plugin.maps().config();
                if (spec.level() < 1 || spec.level() > config.tiers().max()) {
                    throw new IllegalArgumentException("地圖的階級要在 1 到 " + config.tiers().max() + " 之間");
                }
                return "地圖 " + map.name() + " T" + spec.level() + "（" + rarity.displayName() + "）" + count;
            }
            case TAKE_SPONSOR -> {
                if (plugin.sponsors() == null) {
                    throw new IllegalArgumentException("贊助模組沒有啟用");
                }
                final String what = takeSponsorName(ref(spec));
                if (what == null) {
                    throw new IllegalArgumentException("收回贊助要選：累積贊助、星幣、月卡幣、時裝幣、買的倉庫頁、買的背包頁、買的上架格、月卡天數、免費抽獎箱");
                }
                return String.format(Locale.ROOT, "收回 %s %,d", what, spec.amount());
            }
            case TAKE_COSMETIC -> {
                if (plugin.sponsors() == null) {
                    throw new IllegalArgumentException("贊助模組沒有啟用，不能收回時裝");
                }
                plugin.sponsors().resolveCosmetics(ref(spec)); // 沒有這個代號會丟 IllegalArgumentException
                return "收回 " + plugin.sponsors().cosmeticLabel(ref(spec));
            }
            case TAKE_CURRENCY -> {
                final CurrencyType type = plugin.currencies().repository().get(ref(spec));
                if (type == null) {
                    throw new IllegalArgumentException("沒有這種通貨：" + spec.ref());
                }
                return "收回 " + type.name() + " ×" + spec.amount();
            }
            case TAKE_GOLD -> {
                return String.format(Locale.ROOT, "收回 金幣 %,d", spec.amount());
            }
            case LIFE -> {
                if (plugin.life() == null) {
                    throw new IllegalArgumentException("生活模組沒有啟用");
                }
                final String name = plugin.life().adminName(ref(spec));
                if (name == null) {
                    throw new IllegalArgumentException("沒有這種生活的東西：" + spec.ref());
                }
                if (!ref(spec).startsWith("mat:") && spec.amount() > 640) {
                    throw new IllegalArgumentException("方塊、料理、號角一次最多 640 個（材料最多 100,000）");
                }
                return name + " ×" + spec.amount();
            }
            case TAKE_LIFE -> {
                if (plugin.life() == null) {
                    throw new IllegalArgumentException("生活模組沒有啟用");
                }
                final String name = ref(spec).startsWith("mat:") ? plugin.life().adminName(ref(spec)) : null;
                if (name == null) {
                    throw new IllegalArgumentException("收回生活只能選材料（方塊、料理、號角是物品，用「收回：物品」）：" + spec.ref());
                }
                return "收回 " + name + " ×" + spec.amount();
            }
            case TAKE_MERITS -> {
                if (plugin.bounties() == null) {
                    throw new IllegalArgumentException("委託模組沒有啟用，不能收回功勳");
                }
                return String.format(Locale.ROOT, "收回 功勳 %,d", spec.amount());
            }
            case TAKE_REFUND -> {
                return "收回 免費退點 ×" + spec.amount();
            }
            case TAKE_ITEM -> {
                final String keyword = ref(spec);
                if (keyword.length() < 2) {
                    throw new IllegalArgumentException("收回物品要寫物品編號或名稱關鍵字（至少 2 個字）");
                }
                return "收回 物品「" + keyword + "」最多 " + spec.amount() + " 件";
            }
            default -> throw new IllegalArgumentException("不認得的種類：" + kind);
        }
    }

    /** 收回贊助的哪一種（不認得 null）。 */
    static String takeSponsorName(final String ref) {
        return switch (ref == null ? "" : ref) {
            case "total" -> "累積贊助";
            case "coins" -> "星幣";
            case "card" -> "月卡幣";
            case "fashion" -> "時裝幣";
            case "bought_stash" -> "星幣買的倉庫頁";
            case "bought_bag" -> "星幣買的隨身背包頁";
            case "bought_listing" -> "星幣買的上架格";
            case "card_days" -> "月卡天數";
            case "free_box" -> "免費抽獎箱";
            default -> null;
        };
    }

    private static String ref(final Spec spec) {
        return spec.ref() == null ? "" : spec.ref().trim();
    }

    private static void checkLevel(final int level, final int max, final String what) {
        if (level < 0 || level > max) {
            throw new IllegalArgumentException(what + "要在 0（照領的角色）到 " + max + " 之間");
        }
    }

    private static String levelText(final int level, final String what) {
        return level > 0 ? "（" + what + " " + level + "）" : "";
    }

    /**
     * 寶石：ref 是 uncut_skill、uncut_support、skill:技能代號、support:輔助代號。
     * 技能寶石的等級 0 = 照領的角色（characterLevel，最高 {@link SkillLoadout#MAX_LEVEL}）；未切割輔助寶石一定要指定階。
     */
    private GemData gem(final Spec spec, final int characterLevel) {
        if (plugin.skills() == null) {
            throw new IllegalArgumentException("技能模組沒有啟用，不能發寶石");
        }
        final String ref = ref(spec);
        if (spec.level() < 0 || spec.level() > SkillLoadout.MAX_LEVEL) {
            throw new IllegalArgumentException("寶石等級要在 0（照領的角色）到 " + SkillLoadout.MAX_LEVEL + " 之間");
        }
        final int level = spec.level() > 0 ? spec.level() : Math.max(1, Math.min(SkillLoadout.MAX_LEVEL, characterLevel));
        if (ref.equals("uncut_skill")) {
            return GemData.uncutSkill(level);
        }
        if (ref.equals("uncut_support")) {
            if (spec.level() < 1 || spec.level() > SupportDefinition.MAX_TIER) {
                throw new IllegalArgumentException("未切割輔助寶石要選第 1 到 " + SupportDefinition.MAX_TIER + " 階");
            }
            return GemData.uncutSupport(spec.level());
        }
        if (ref.startsWith("skill:")) {
            final SkillDefinition skill = plugin.skills().config().skill(ref.substring(6));
            if (skill == null || skill.hidden()) {
                throw new IllegalArgumentException("沒有這個技能：" + ref.substring(6));
            }
            return GemData.skill(new SkillLoadout.Entry(skill.id(), level));
        }
        if (ref.startsWith("support:")) {
            final SupportDefinition support = plugin.skills().config().support(ref.substring(8));
            if (support == null) {
                throw new IllegalArgumentException("沒有這個輔助：" + ref.substring(8));
            }
            return GemData.support(support.id());
        }
        throw new IllegalArgumentException("不認得的寶石：" + ref);
    }

    private MapConfig.MapDef mapDef(final Spec spec) {
        if (plugin.maps() == null) {
            throw new IllegalArgumentException("地圖模組沒有啟用");
        }
        final MapConfig.MapDef map = plugin.maps().config().find(ref(spec));
        if (map == null || map.isFinal()) {
            throw new IllegalArgumentException("沒有這張地圖：" + spec.ref());
        }
        return map;
    }

    // ---- 寫進資料庫（資料庫執行緒） ----

    /**
     * 找出要發給哪些帳號、寫進去（同一個交易）。有找不到的名字時什麼都不寫，回傳那些名字。
     *
     * @param batchId 這次發放的代號（網頁每按一次「發放」產生一個；同一個請求重送時一樣 → 不會發兩次）
     * @param online  「線上的人」：主執行緒先收好的 UUID
     */
    public Created create(final Connection connection, final String batchId, final Targets targets, final List<UUID> online,
                          final List<Planned> plan, final String note, final String admin) throws SQLException {
        final List<UUID> already = repository.batchAccounts(connection, batchId);
        if (already != null) {
            return new Created(batchId, already.size(), 0, already, List.of(), true); // 逾時後重送：已經寫過了
        }
        final Map<UUID, String> accounts = new LinkedHashMap<>();
        final List<String> unknown = new ArrayList<>();
        switch (targets.mode()) {
            case "all" -> {
                final List<AdminRepository.Account> all = repository.allAccounts(connection, MAX_ACCOUNTS + 1);
                if (all.size() > MAX_ACCOUNTS) {
                    throw new IllegalArgumentException("帳號超過 " + MAX_ACCOUNTS + " 個，不能一次發給所有帳號");
                }
                for (final AdminRepository.Account account : all) {
                    accounts.put(account.uuid(), account.name());
                }
            }
            case "online", "players" -> {
                for (final UUID uuid : "online".equals(targets.mode()) ? online : targets.uuids()) {
                    final AdminRepository.Account account = repository.account(connection, uuid);
                    if (account == null) {
                        unknown.add(uuid.toString());
                    } else {
                        accounts.put(account.uuid(), account.name());
                    }
                }
            }
            case "names" -> {
                for (final String name : targets.names()) {
                    final AdminRepository.Account account = repository.resolve(connection, name);
                    if (account == null) {
                        unknown.add(name);
                    } else {
                        accounts.put(account.uuid(), account.name());
                    }
                }
            }
            default -> throw new IllegalArgumentException("不認得的對象：" + targets.mode());
        }
        if (!unknown.isEmpty()) {
            return new Created(null, 0, 0, List.of(), unknown, false);
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("沒有要發給誰");
        }
        if ((long) accounts.size() * plan.size() > MAX_ROWS) {
            throw new IllegalArgumentException("一次最多 " + MAX_ROWS + " 筆（帳號數 × 東西的種類），請分批發");
        }
        final List<AdminRepository.NewDelivery> rows = new ArrayList<>();
        for (final Map.Entry<UUID, String> account : accounts.entrySet()) {
            for (final Planned planned : plan) {
                final Spec spec = planned.spec();
                rows.add(new AdminRepository.NewDelivery(account.getKey(), account.getValue(), spec.kind(), spec.ref(), spec.rarity(),
                        spec.level(), spec.amount(), planned.label(), spec.affixes()));
            }
        }
        final boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            repository.insert(connection, batchId, rows, note, admin);
            final List<String> labels = new ArrayList<>();
            for (final Planned planned : plan) {
                labels.add(planned.label());
            }
            repository.log(connection, admin, "發放", targetText(targets, accounts.values()) + "：" + String.join("、", labels)
                    + (note == null || note.isBlank() ? "" : "（備註：" + note + "）") + "　批次 " + batchId.substring(0, 8));
            connection.commit();
        } catch (final SQLException | RuntimeException | Error e) {
            try {
                connection.rollback(); // 先還原，才把自動提交改回來（不然寫一半的會被提交）
            } catch (final SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
        return new Created(batchId, accounts.size(), rows.size(), List.copyOf(accounts.keySet()), List.of(), false);
    }

    private static String targetText(final Targets targets, final Collection<String> names) {
        final String list = names.size() <= 5 ? String.join("、", names) : String.join("、", new ArrayList<>(names).subList(0, 5)) + "…";
        return switch (targets.mode()) {
            case "all" -> "所有帳號（" + names.size() + " 個）";
            case "online" -> "線上所有人（" + names.size() + " 人：" + list + "）";
            default -> list + (names.size() > 5 ? "（共 " + names.size() + " 個帳號）" : "");
        };
    }

    // ---- 發（主執行緒） ----

    /**
     * 一輪領取：從第一頁讀到最後一頁（一頁 {@link #BATCH} 筆）。放不下的數量、等帳號資料的數量整輪加起來，
     * 最後才說一次、最多排一次重試（重試是新的一輪，attempt 加一，最多 {@link #MAX_ATTEMPTS} 輪）。
     */
    private static final class Pass {
        final boolean manual;
        final int attempt;
        int left;
        int waiting;
        int delivered;

        Pass(final boolean manual, final int attempt) {
            this.manual = manual;
            this.attempt = attempt;
        }
    }

    /**
     * 指令發放（/ec sponsor give、贊助商城）：發給一個名字（帳號或角色），寫進資料庫後在線的馬上發。主執行緒呼叫，
     * 主執行緒以外完成（呼叫端自己切回主執行緒）。
     */
    public CompletableFuture<Created> grant(final String name, final List<Spec> specs, final String note, final String admin) {
        final List<Planned> plan;
        try {
            plan = plan(specs);
        } catch (final IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        final String batchId = UUID.randomUUID().toString();
        final Targets targets = new Targets("names", List.of(), List.of(name));
        return plugin.database().async(connection -> create(connection, batchId, targets, List.of(), plan, note, admin)).thenApply(created -> {
            if (created.unknown().isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> push(created.accountIds()));
            }
            return created;
        });
    }

    /** 剛寫進去的：在線的人馬上領。 */
    public void push(final Collection<UUID> accounts) {
        for (final UUID uuid : accounts) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                claim(player, new Pass(false, 1), 0, true);
            }
        }
    }

    /** 角色套用到玩家身上之後（登入、建立、切換）：等帳號資料載入，再領。 */
    public void afterApply(final Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player) {
                claim(player, new Pass(false, 1), 0, true);
            }
        }, JOIN_DELAY_TICKS);
    }

    /** 領這個玩家的東西。manual = 玩家自己打 /claim（沒東西、不能領的時候要說）。 */
    public void claim(final Player player, final boolean manual) {
        claim(player, new Pass(manual, 1), 0, true);
    }

    /**
     * @param afterId 從哪一筆之後開始讀（0 = 從頭；同一輪的下一頁接著上一頁的最後一筆）
     * @param fresh   可能有新發的（管理員剛發、登入、玩家打 /claim）：正在領的話這次領完再從頭讀一次；
     *                重試、下一頁不是（正在領的話就不用了）
     */
    private void claim(final Player player, final Pass pass, final long afterId, final boolean fresh) {
        final UUID uuid = player.getUniqueId();
        if (!player.isOnline() || plugin.getServer().getPlayer(uuid) != player) {
            return; // 下線了（排好的下一頁、重試剛好在這一刻）：不要替他建一個新的狀態
        }
        final String problem = problem(player);
        if (problem != null) {
            if (pass.manual) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            }
            if (afterId != 0) {
                endPass(player, pass, true); // 讀到一半被打斷（角色資料處理中、交易系統暫停）：照樣收尾，等一下從頭再試
            } else if (!pass.manual && pass.attempt < MAX_ATTEMPTS && plugin.players().get(player).hasCharacter()) {
                retryLater(player, pass.attempt); // 角色資料處理中、交易系統暫停：等一下再試（還沒選角色的，選好時會再領）
            }
            return;
        }
        if (!claiming.add(uuid)) {
            if (fresh) {
                rerun.add(uuid); // 正在讀的那一批可能沒有剛發的：這次領完再從頭讀一次
            }
            if (pass.manual) {
                player.sendMessage(plugin.text().prefixed("<gray>正在領取中，請稍等"));
            }
            return;
        }
        plugin.database().async(connection -> repository.pending(connection, uuid, afterId, BATCH)).whenComplete((rows, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        claiming.remove(uuid);
                        rerun.remove(uuid);
                        plugin.getSLF4JLogger().error("讀 {} 的待領取物資失敗", player.getName(), error);
                        if (pass.manual && player.isOnline()) {
                            player.sendMessage(plugin.text().prefixed("<red>讀不到待領取的物資（資料庫出錯），請稍後再試"));
                        }
                        return;
                    }
                    if (!player.isOnline() || plugin.getServer().getPlayer(uuid) != player || problem(player) != null) {
                        claiming.remove(uuid); // 下線了、重新登入了、正在換角色：下次再領
                        rerun.remove(uuid);
                        return;
                    }
                    try {
                        deliver(player, rows, pass);
                    } catch (final RuntimeException e) {
                        // 不應該發生（每一筆的錯誤在 deliver 裡面接住了）：至少不要讓這個玩家之後都領不了。
                        // 已經發到身上、還沒寫進資料庫的編號留在 inflight：這次開服不會再發一次、也不能取消
                        claiming.remove(uuid);
                        rerun.remove(uuid);
                        plugin.getSLF4JLogger().error("發管理員的物資給 {} 時出錯（這一輪停下來，編號 {} 先不再發）", player.getName(), inflight, e);
                    }
                }));
    }

    /** 現在能不能領（null = 可以）。 */
    private String problem(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return "先選一個角色（/char）再領";
        }
        if (plugin.characters().isBusy(player)) {
            return "角色資料處理中，請稍等";
        }
        return plugin.characters().transactionProblem(player);
    }

    /** 發這一頁。 */
    private void deliver(final Player player, final List<AdminRepository.Delivery> rows, final Pass pass) {
        final UUID uuid = player.getUniqueId();
        final PlayerState state = plugin.players().get(player);
        // 操作代號也是交易紀錄的 trade_id（VARCHAR(36)）：只能是一個 UUID，加前綴會寫不進去、整筆存檔失敗
        final String opId = TradeLogRepository.newTradeId();
        final List<Update> updates = new ArrayList<>();
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        boolean gave = false;
        CharacterRepository.ExtraWork extra = null;
        for (final AdminRepository.Delivery row : rows) {
            if (cancelled.contains(row.id()) || inflight.contains(row.id())) {
                continue;
            }
            final Given given;
            try {
                given = give(player, state, row, opId);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("發管理員的物資（編號 {}，{}）給 {} 失敗", row.id(), row.label(), player.getName(), e);
                continue; // 留著（下次再試；一直失敗的話管理員可以取消）
            }
            if (given.waiting()) {
                pass.waiting++;
                continue;
            }
            if (given.missing()) {
                plugin.getSLF4JLogger().warn("管理員發給 {} 的物資（編號 {}，{}）在資料檔裡已經沒有了：先留著，到管理網頁的發放紀錄取消，或改回資料檔",
                        player.getName(), row.id(), row.label());
                continue;
            }
            if (given.given() <= 0) {
                pass.left++;
                continue;
            }
            final long remaining = Math.max(0, row.amount() - given.given());
            updates.add(new Update(row.id(), remaining));
            inflight.add(row.id());
            log.addAll(given.log());
            extra = CharacterRepository.ExtraWork.both(extra, given.work());
            gave |= !isTake(row.kind());
            lines.add((isTake(row.kind()) ? "<gray>· <red>" : "<gray>· <white>") + ItemRenderer.escape(given.line()) + "</white>"
                    + (row.note() == null || row.note().isBlank() ? "" : " <dark_gray>（" + ItemRenderer.escape(row.note()) + "）"));
            if (remaining > 0) {
                pass.left++;
            }
        }
        final long nextAfter = rows.size() >= BATCH ? rows.getLast().id() : -1; // 還有下一頁：接著讀（領不走的不會擋住後面的）
        if (updates.isEmpty()) {
            done(player, pass, nextAfter);
            return;
        }
        pass.delivered += updates.size();
        final Set<Long> ids = new HashSet<>();
        for (final Update update : updates) {
            ids.add(update.id());
        }
        final String who = state.character().name();
        final CharacterRepository.ExtraWork more = extra;
        final CharacterRepository.ExtraWork work = connection -> {
            for (final Update update : updates) {
                final int changed = update.remaining() <= 0 ? repository.markClaimed(connection, update.id(), who)
                        : repository.setRemaining(connection, update.id(), update.remaining());
                if (changed == 0) {
                    plugin.getSLF4JLogger().warn("管理員的物資編號 {} 寫領取紀錄時已經不是待領取（被取消或已領過）", update.id());
                }
            }
            if (!log.isEmpty()) {
                plugin.economy().tradeLog().insert(connection, log);
            }
            if (more != null) {
                more.run(connection);
            }
        };
        plugin.characters().commit(List.of(player), work, opId, "管理員發放的物資（" + player.getName() + "）").whenComplete((ok, error) -> {
            if (Boolean.TRUE.equals(ok)) {
                inflight.removeAll(ids);
                done(player, pass, nextAfter);
            } else {
                stuck.put(opId, ids); // 交易系統暫停中：重試成功（onRecovered）之前不能取消；這一輪到這裡為止
                claiming.remove(uuid);
                rerun.remove(uuid);
            }
        });
        player.sendMessage(plugin.text().prefixed(gave ? "<#f5b942>收到管理員發放的物資" : "<#f5b942>管理員調整了你的資料"));
        for (final String line : lines) {
            player.sendMessage(plugin.text().parse(line));
        }
        if (gave) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        }
    }

    /**
     * 這一頁做完了。還有下一頁（nextAfter ≥ 0）：同一輪接著讀；沒有了：這一輪結束——說一次還有幾樣放不下、
     * 等帳號資料的排一次重試；領的時候管理員又發了新的：從頭再讀一輪。都在下一個 tick。
     */
    private void done(final Player player, final Pass pass, final long nextAfter) {
        final UUID uuid = player.getUniqueId();
        claiming.remove(uuid);
        if (!player.isOnline() || plugin.getServer().getPlayer(uuid) != player) {
            rerun.remove(uuid);
            return;
        }
        if (nextAfter >= 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> claim(player, pass, nextAfter, false));
            return;
        }
        endPass(player, pass, false);
    }

    /**
     * 一輪結束：說一次還有幾樣放不下；這一輪中間管理員又發了新的：從頭再領一輪；
     * 等帳號資料的（或中途被打斷 interrupted）：等一下再試一輪（最多 {@link #MAX_ATTEMPTS} 輪）。
     */
    private void endPass(final Player player, final Pass pass, final boolean interrupted) {
        final UUID uuid = player.getUniqueId();
        if (pass.left > 0) {
            notifyLeft(player, pass.left);
        } else if (pass.manual && pass.delivered == 0 && !interrupted) {
            player.sendMessage(plugin.text().prefixed(pass.waiting > 0 ? "<gray>帳號資料還在載入，等一下再打 /claim" : "<gray>沒有待領取的物資"));
        }
        if (rerun.remove(uuid)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> claim(player, new Pass(false, 1), 0, true));
        } else if ((pass.waiting > 0 || interrupted) && pass.attempt < MAX_ATTEMPTS) {
            retryLater(player, pass.attempt);
        }
    }

    /** 等一下再試一輪（帳號資料還沒載入、角色資料處理中）。 */
    private void retryLater(final Player player, final int attempt) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player) {
                claim(player, new Pass(false, attempt + 1), 0, false);
            }
        }, RETRY_TICKS);
    }

    private void notifyLeft(final Player player, final int left) {
        player.sendMessage(plugin.text().prefixed("<yellow>還有 " + left + " 樣管理員發的物資放不下</yellow><gray>：整理背包或隨身背包後打 "
                + "<white><click:run_command:'/claim'><hover:show_text:'領取管理員發的物資'>/claim</hover></click></white> 再領"));
    }

    /** 發一筆（能發多少發多少）。 */
    private Given give(final Player player, final PlayerState state, final AdminRepository.Delivery row, final String opId) {
        final Wallet wallet = plugin.economy().wallet();
        final long amount = row.amount();
        final String admin = row.createdBy();
        switch (row.kind()) {
            case CURRENCY -> {
                final CurrencyType type = plugin.currencies().repository().get(row.ref());
                if (type == null) {
                    return Given.MISSING;
                }
                final int want = (int) Math.min(Integer.MAX_VALUE, amount);
                final int given = want - wallet.deposit(player, type.id(), want);
                if (given <= 0) {
                    return Given.NONE;
                }
                plugin.economy().ledger().produced(type.id(), EconomyLedger.ADMIN, given);
                return new Given(given, type.name() + " ×" + given, List.of(entry(opId, null, type.name(), given, admin, player)), null);
            }
            case GOLD -> {
                state.setGold(state.gold() + amount);
                plugin.economy().ledger().gold(EconomyLedger.ADMIN, amount);
                state.notice(String.format(Locale.ROOT, "<#f5b942>+%,d 金幣", amount), plugin.getServer().getCurrentTick() + 40);
                return new Given(amount, String.format(Locale.ROOT, "金幣 %,d", amount),
                        List.of(entry(opId, null, "金幣", (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), null);
            }
            case MERITS -> {
                if (plugin.bounties() == null) {
                    return Given.MISSING; // 委託模組關掉了
                }
                if (!plugin.bounties().accountLoaded(player)) {
                    return Given.WAIT;
                }
                final CharacterRepository.ExtraWork work = plugin.bounties().grantMeritsWork(player, amount);
                if (work == null) {
                    return Given.WAIT;
                }
                return new Given(amount, String.format(Locale.ROOT, "功勳 %,d", amount),
                        List.of(entry(opId, null, "功勳", (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), work);
            }
            case SPONSOR -> {
                if (plugin.sponsors() == null) {
                    return Given.MISSING; // 贊助模組關掉了
                }
                if (!plugin.sponsors().loaded(player)) {
                    return Given.WAIT;
                }
                if ("card_days".equals(row.ref())) {
                    final tw.exilecore.sponsor.SponsorService.Revoked days = plugin.sponsors().cardDaysWork(player, amount);
                    if (days == null) {
                        return Given.WAIT;
                    }
                    return new Given(amount, "月卡 " + amount + " 天",
                            List.of(entry(opId, null, "月卡天數", (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), days.work());
                }
                if ("free_box".equals(row.ref())) {
                    final CharacterRepository.ExtraWork boxes = plugin.sponsors().grantFreeBoxesWork(player, amount);
                    if (boxes == null) {
                        return Given.WAIT;
                    }
                    return new Given(amount, String.format(Locale.ROOT, "免費抽獎箱 %,d 箱", amount),
                            List.of(entry(opId, null, "免費抽獎箱", (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), boxes);
                }
                if ("card".equals(row.ref()) || "fashion".equals(row.ref())) {
                    final boolean fashion = "fashion".equals(row.ref());
                    final CharacterRepository.ExtraWork coins = plugin.sponsors().grantCoinsWork(player, amount, fashion);
                    if (coins == null) {
                        return Given.WAIT;
                    }
                    final String what = fashion ? "時裝幣" : "月卡幣";
                    return new Given(amount, String.format(Locale.ROOT, "%s %,d", what, amount),
                            List.of(entry(opId, null, what, (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), coins);
                }
                final CharacterRepository.ExtraWork work = plugin.sponsors().grantWork(player, amount, !"bonus".equals(row.ref()));
                if (work == null) {
                    return Given.WAIT;
                }
                return new Given(amount, String.format(Locale.ROOT, "星幣 %,d", amount),
                        List.of(entry(opId, null, "星幣", (int) Math.min(Integer.MAX_VALUE, amount), admin, player)), work);
            }
            case COSMETIC -> {
                if (plugin.sponsors() == null) {
                    return Given.MISSING;
                }
                if (!plugin.sponsors().loaded(player)) {
                    return Given.WAIT;
                }
                final tw.exilecore.sponsor.SponsorService.Granted granted;
                try {
                    granted = plugin.sponsors().grantCosmeticWork(player, row.ref());
                } catch (final IllegalArgumentException e) {
                    return Given.MISSING; // 資料檔裡已經沒有這個時裝
                }
                if (granted == null) {
                    return Given.WAIT;
                }
                final String label = plugin.sponsors().cosmeticLabel(row.ref());
                if (granted.given().isEmpty()) {
                    return new Given(1, label + "（已經有了，沒有重複給）",
                            List.of(entry(opId, null, label + "（已經有了）", 1, admin, player)), null);
                }
                final List<TradeLogRepository.Entry> log = new ArrayList<>();
                for (final tw.exilecore.sponsor.SponsorService.Grantable item : granted.given()) {
                    log.add(entry(opId, null, (item.title() ? "稱號 " : "時裝 ") + item.name(), 1, admin, player));
                }
                return new Given(1, label + (granted.alreadyOwned().isEmpty() ? "" : "（" + granted.alreadyOwned().size() + " 件已經有了）"), log, granted.work());
            }
            case TAKE_COSMETIC -> {
                if (plugin.sponsors() == null) {
                    return Given.MISSING;
                }
                if (!plugin.sponsors().loaded(player)) {
                    return Given.WAIT;
                }
                final tw.exilecore.sponsor.SponsorService.Granted revoked;
                try {
                    revoked = plugin.sponsors().revokeCosmeticWork(player, row.ref());
                } catch (final IllegalArgumentException e) {
                    return Given.MISSING; // 資料檔裡已經沒有這個時裝
                }
                if (revoked == null) {
                    return Given.WAIT;
                }
                final String label = plugin.sponsors().cosmeticLabel(row.ref());
                if (revoked.given().isEmpty()) {
                    return new Given(1, "收回 " + label + "（本來就沒有）",
                            List.of(entry(opId, null, "收回" + label + "（本來就沒有）", 1, admin, player)), null);
                }
                final List<TradeLogRepository.Entry> log = new ArrayList<>();
                for (final tw.exilecore.sponsor.SponsorService.Grantable item : revoked.given()) {
                    log.add(entry(opId, null, "收回" + (item.title() ? "稱號 " : "時裝 ") + item.name(), 1, admin, player));
                }
                return new Given(1, "收回 " + label + (revoked.alreadyOwned().isEmpty() ? "" : "（" + revoked.alreadyOwned().size() + " 件本來就沒有）"),
                        log, revoked.work());
            }
            case TAKE_SPONSOR -> {
                if (plugin.sponsors() == null) {
                    return Given.MISSING;
                }
                if (!plugin.sponsors().loaded(player)) {
                    return Given.WAIT;
                }
                final tw.exilecore.sponsor.SponsorService.Revoked revoked = "card_days".equals(row.ref())
                        ? plugin.sponsors().cardDaysWork(player, -amount) // 2026-10：拔掉月卡（扣天數，扣到沒有）
                        : plugin.sponsors().revokeWork(player, row.ref(), amount);
                if (revoked == null) {
                    return Given.WAIT;
                }
                final String what = takeSponsorName(row.ref());
                return new Given(amount, String.format(Locale.ROOT, "收回 %s %,d", what, revoked.taken()),
                        List.of(entry(opId, null, "收回" + what, (int) Math.min(Integer.MAX_VALUE, Math.max(1, revoked.taken())), admin, player)),
                        revoked.work());
            }
            case TAKE_CURRENCY -> {
                final CurrencyType type = plugin.currencies().repository().get(row.ref());
                if (type == null) {
                    return Given.MISSING;
                }
                final int taken = (int) Math.min(amount, wallet.available(player, type.id()));
                if (taken > 0 && wallet.take(player, type.id(), taken)) {
                    plugin.economy().ledger().consumed(type.id(), EconomyLedger.ADMIN_TAKE, taken); // 2026-10-18：收回另記一列，不跟發放抵掉
                }
                return new Given(amount, "收回 " + type.name() + " ×" + Math.max(0, taken),
                        List.of(entry(opId, null, "收回" + type.name(), Math.max(1, taken), admin, player)), null);
            }
            case TAKE_GOLD -> {
                final long taken = Math.min(amount, Math.max(0, state.gold()));
                state.setGold(state.gold() - taken);
                plugin.economy().ledger().gold(EconomyLedger.ADMIN_TAKE, -taken); // 2026-10-18：收回另記一列，不跟發放抵掉
                return new Given(amount, String.format(Locale.ROOT, "收回 金幣 %,d", taken),
                        List.of(entry(opId, null, "收回金幣", (int) Math.min(Integer.MAX_VALUE, Math.max(1, taken)), admin, player)), null);
            }
            case TAKE_ITEM -> {
                if (plugin.stash() != null && plugin.stash().stash(player).isEmpty()) {
                    return Given.WAIT; // 倉庫還沒載入
                }
                final List<TradeLogRepository.Entry> log = new ArrayList<>();
                final int taken = takeItems(player, state, row.ref(), (int) Math.min(Integer.MAX_VALUE, amount), opId, admin, log);
                if (log.isEmpty()) {
                    log.add(entry(opId, null, "收回物品「" + row.ref() + "」（沒有找到）", 1, admin, player));
                }
                return new Given(amount, "收回 物品「" + row.ref() + "」×" + taken, log, null);
            }
            case REFUND -> {
                state.character().setRefundPoints(state.character().refundPoints() + (int) amount);
                state.markDirty();
                return new Given(amount, "免費退點 ×" + amount + "（" + state.character().name() + "）", List.of(), null);
            }
            case TAKE_REFUND -> {
                final int have = Math.max(0, state.character().refundPoints());
                final int taken = (int) Math.min(have, amount);
                state.character().setRefundPoints(have - taken);
                state.markDirty();
                return new Given(amount, "收回 免費退點 ×" + taken + "（" + state.character().name() + "）", List.of(), null);
            }
            case LIFE -> {
                if (plugin.life() == null) {
                    return Given.MISSING;
                }
                final String name = plugin.life().adminName(row.ref());
                if (name == null) {
                    return Given.MISSING; // 資料檔裡已經沒有了
                }
                final long given = plugin.life().adminGive(player, row.ref(), amount);
                if (given == -1) {
                    return Given.WAIT; // 帳號資料還沒載入
                }
                if (given < 0) {
                    return Given.MISSING;
                }
                // 2026-10-18：材料跟「已領取」寫在同一個交易（數量 0 時這筆會被跳過，不拿 work，免得生活資料被標成已存卻沒寫）
                return new Given(given, name + " ×" + given,
                        List.of(entry(opId, null, name, (int) Math.min(Integer.MAX_VALUE, given), admin, player)),
                        given > 0 ? plugin.life().adminWork(player) : null);
            }
            case TAKE_LIFE -> {
                if (plugin.life() == null) {
                    return Given.MISSING;
                }
                final String name = plugin.life().adminName(row.ref());
                final long taken = plugin.life().adminTake(player, row.ref(), amount);
                if (taken == -1) {
                    return Given.WAIT;
                }
                if (taken < 0 || name == null) {
                    return Given.MISSING;
                }
                // 2026-10-18：材料跟「已領取」寫在同一個交易
                return new Given(amount, "收回 " + name + " ×" + taken,
                        List.of(entry(opId, null, "收回" + name, (int) Math.max(1, Math.min(Integer.MAX_VALUE, taken)), admin, player)), plugin.life().adminWork(player));
            }
            case TAKE_MERITS -> {
                if (plugin.bounties() == null) {
                    return Given.MISSING;
                }
                if (!plugin.bounties().accountLoaded(player)) {
                    return Given.WAIT;
                }
                final tw.exilecore.bounty.BountyService.TakenMerits taken = plugin.bounties().revokeMeritsWork(player, amount);
                if (taken == null) {
                    return Given.WAIT;
                }
                return new Given(amount, String.format(Locale.ROOT, "收回 功勳 %,d", taken.taken()),
                        List.of(entry(opId, null, "收回功勳", (int) Math.max(1, Math.min(Integer.MAX_VALUE, taken.taken())), admin, player)),
                        taken.work());
            }
            case ITEM, UNIQUE, GEM, FLASK, MAP -> {
                return giveItems(player, state, row, opId, wallet);
            }
            default -> {
                return Given.MISSING;
            }
        }
    }

    /** 裝備、傳奇、寶石、藥劑、地圖：一個一個做出來放，放不下就停。 */
    private Given giveItems(final Player player, final PlayerState state, final AdminRepository.Delivery row, final String opId,
                            final Wallet wallet) {
        final Spec spec = new Spec(row.kind(), row.ref(), row.rarity(), row.itemLevel(), row.amount(), row.affixes());
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        long given = 0;
        String name = null;
        boolean broken = false;
        while (given < row.amount()) {
            final ItemStack stack;
            try {
                stack = make(spec, state, row.amount() - given);
            } catch (final RuntimeException e) {
                // 做不出來（資料有問題）：已經放進去的照算，剩下的留著
                plugin.getSLF4JLogger().error("做管理員發的物資（編號 {}，{}）失敗", row.id(), row.label(), e);
                broken = true;
                break;
            }
            if (stack == null) {
                return given > 0 ? new Given(given, name + (given > 1 ? " ×" + given : ""), log, null) : Given.MISSING; // 資料檔裡沒有了：留著
            }
            // 放進去之前先記下來：疊到別的格子上時，原版會改掉這一疊的數量
            final int count = stack.getAmount();
            final String what = Wallet.describe(stack.asOne());
            final String itemUuid = plugin.items().read(stack).map(ItemData::uuid).orElse(null);
            // 未切割寶石算通貨（2026-10）：管理員發的也記進流水帳的產出
            final String currencyId = GEM.equals(row.kind()) && plugin.currencies() != null
                    ? plugin.currencies().read(stack).map(CurrencyType::id).orElse(null) : null;
            final boolean flask = FLASK.equals(row.kind()) && plugin.flasks() != null;
            if (!(flask && plugin.flasks().equipIfEmpty(player, stack))) {
                if (!wallet.fits(player, stack)) {
                    break; // 放不下：剩下的留著等 /claim
                }
                final ItemStack rest = wallet.give(player, stack);
                if (rest != null && !rest.isEmpty()) {
                    wallet.dropFor(player, rest); // 檢查過放得下，不應該發生；萬一有就掉在腳下（只有他看得到）
                }
            }
            given += count;
            name = what;
            if (currencyId != null) {
                plugin.economy().ledger().produced(currencyId, EconomyLedger.ADMIN, count);
            }
            log.add(entry(opId, itemUuid, what, count, row.createdBy(), player));
        }
        if (given <= 0) {
            return broken ? Given.MISSING : Given.NONE;
        }
        return new Given(given, name + (given > 1 ? " ×" + given : ""), log, null);
    }

    /** 做出一個（寶石可以一次一疊）；做不出來回傳 null。 */
    private ItemStack make(final Spec spec, final PlayerState state, final long remaining) {
        final int characterLevel = Math.max(1, state.level());
        switch (spec.kind()) {
            case ITEM -> {
                final ItemBase base = plugin.items().repository().base(spec.ref());
                final Rarity parsed = Rarity.parse(spec.rarity(), Rarity.NORMAL);
                if (base == null || base.slot() == ItemSlot.MAP || parsed == Rarity.UNIQUE) {
                    return null;
                }
                final Rarity rarity = base.isJewel() && parsed == Rarity.NORMAL ? Rarity.MAGIC : parsed; // 普通的珠寶什麼都沒有
                final int level = spec.level() > 0 ? spec.level() : characterLevel;
                final List<AffixSpec.Entry> affixes = AffixSpec.parse(spec.affixes());
                if (affixes.isEmpty()) {
                    return plugin.items().create(base, rarity, level);
                }
                // 指定詞綴（2026-10）：照填的做，不再隨機擲別的
                final ItemData data = plugin.items().generator().generate(base, Rarity.NORMAL, level, plugin.items().random());
                AffixSpec.startRarity(plugin.items().generator(), data, rarity, plugin.items().random());
                final String problem = AffixSpec.applyToItem(plugin.items().repository(), plugin.items().generator(), data, affixes,
                        plugin.items().random());
                if (problem != null) {
                    throw new IllegalStateException("指定的詞綴做不出來：" + problem);
                }
                return plugin.items().toStack(data);
            }
            case UNIQUE -> {
                final UniqueItem unique = plugin.items().repository().unique(spec.ref());
                if (unique == null || plugin.items().repository().base(unique.base()) == null) {
                    return null;
                }
                final int level = spec.level() > 0 ? spec.level() : Math.max(unique.dropLevel(), characterLevel);
                final ItemData data = plugin.items().generator().generateUnique(unique, level, plugin.items().random());
                AffixSpec.applyToUnique(data, AffixSpec.parse(spec.affixes()));
                return plugin.items().toStack(data);
            }
            case GEM -> {
                if (plugin.skills() == null) {
                    return null;
                }
                final GemData data;
                try {
                    data = gem(spec, characterLevel);
                } catch (final IllegalArgumentException e) {
                    return null;
                }
                return plugin.skills().gems().create(data, (int) Math.min(remaining, data.maxStack()));
            }
            case FLASK -> {
                final FlaskBase base = plugin.flasks() == null ? null : plugin.flasks().config().base(spec.ref());
                return base == null ? null : plugin.flasks().create(base, base.charges());
            }
            case MAP -> {
                if (plugin.maps() == null) {
                    return null;
                }
                final MapConfig config = plugin.maps().config();
                final MapConfig.MapDef map = config.find(spec.ref());
                final Rarity rarity = Rarity.parse(spec.rarity(), Rarity.NORMAL);
                if (map == null || map.isFinal() || rarity == Rarity.UNIQUE) {
                    return null;
                }
                final ItemData data = MapDrops.create(config, plugin.items().generator(), map, rarity, Math.max(1, spec.level()),
                        plugin.items().random());
                return data == null ? null : plugin.items().toStack(data);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 收回物品：背包 → 隨身背包（全部的頁，包括收回的頁）→ 倉庫（全部的頁），物品編號一樣或名稱包含關鍵字的，
     * 一件一件拿走（一疊算一件），最多 max 件。開著的介面先關掉（畫面上的東西跟資料才會一致）。回傳拿了幾件。
     */
    private int takeItems(final Player player, final PlayerState state, final String keyword, final int max, final String opId,
                          final String admin, final List<TradeLogRepository.Entry> log) {
        player.closeInventory();
        final String needle = keyword.trim().toLowerCase(Locale.ROOT);
        final java.util.function.Predicate<ItemStack> match = stack -> {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            final String uuid = plugin.items().read(stack).map(ItemData::uuid).orElse(null);
            if (uuid != null && uuid.equalsIgnoreCase(needle)) {
                return true;
            }
            return Wallet.describe(stack.asOne()).toLowerCase(Locale.ROOT).contains(needle);
        };
        final List<ItemStack[]> places = new ArrayList<>();
        final ItemStack[] inventory = player.getInventory().getContents();
        places.add(inventory);
        for (int page = 0; page < state.bag().storedPages(); page++) {
            places.add(state.bag().page(page));
        }
        if (plugin.stash() != null) { // 2026-10-18：倉庫模組沒啟用時 NPE，這筆會永遠卡住
            plugin.stash().stash(player).ifPresent(stash -> {
                for (int page = 0; page < stash.pageCount(); page++) {
                    places.add(stash.page(page));
                }
            });
        }
        int taken = 0;
        for (final ItemStack[] place : places) {
            for (int i = 0; i < place.length && taken < max; i++) {
                if (match.test(place[i])) {
                    final ItemStack stack = place[i];
                    log.add(entry(opId, plugin.items().read(stack).map(ItemData::uuid).orElse(null), "收回" + Wallet.describe(stack),
                            stack.getAmount(), admin, player));
                    place[i] = null;
                    taken++;
                }
            }
        }
        player.getInventory().setContents(inventory);
        state.markDirty();
        if (taken > 0) {
            plugin.getSLF4JLogger().info("[管理] 收回 {} 的物品「{}」{} 件（{}）", player.getName(), keyword, taken, admin);
        }
        return taken;
    }

    private static TradeLogRepository.Entry entry(final String opId, final String itemUuid, final String what, final int amount,
                                                  final String admin, final Player player) {
        return new TradeLogRepository.Entry(opId, TradeLogRepository.ADMIN, itemUuid, what, Math.max(1, amount), null,
                admin == null ? "" : admin, player.getUniqueId(), player.getName());
    }

    // ---- 取消 ----

    /**
     * 取消這些（主執行緒呼叫；結果在資料庫執行緒上完成）。正在發的不能取消（回傳的 busy）。
     */
    public CompletableFuture<Cancelled> cancel(final Collection<Long> ids, final String by) {
        final List<Long> busy = new ArrayList<>();
        final List<Long> candidates = new ArrayList<>();
        for (final long id : ids) {
            if (inflight.contains(id)) {
                busy.add(id);
            } else {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(new Cancelled(List.of(), busy));
        }
        cancelled.addAll(candidates);
        while (cancelled.size() > 50_000) {
            cancelled.remove(cancelled.iterator().next());
        }
        return plugin.database().async(connection -> {
            final List<Long> done = repository.cancel(connection, candidates, by);
            if (!done.isEmpty()) {
                repository.log(connection, by, "取消發放", done.size() + " 筆（編號 " + summarize(done) + "）");
            }
            return new Cancelled(done, busy);
        }).whenComplete((result, error) -> {
            if (error != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> candidates.forEach(cancelled::remove)); // 沒取消成功：照常發
            }
        });
    }

    /** 取消的結果：done 真的取消了；busy 正在發，不能取消。 */
    public record Cancelled(List<Long> done, List<Long> busy) {
    }

    private static String summarize(final List<Long> ids) {
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < ids.size() && i < 10; i++) {
            parts.add(String.valueOf(ids.get(i)));
        }
        return String.join("、", parts) + (ids.size() > 10 ? "…" : "");
    }

    /** 還沒寫進資料庫的編號（測試、狀態用）。 */
    int inflightCount() {
        return inflight.size();
    }
}
