package tw.exilecore.currency;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemService;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 通貨物品的建立、辨識與套用。
 */
public final class CurrencyService {

    /** 套用結果。DESTROYED：僥倖石失敗，物品碎掉了（呼叫端要把它拿掉）。 */
    public enum Result { APPLIED, NOT_CURRENCY, NOT_EQUIPMENT, NOT_APPLICABLE, DESTROYED }

    /** 通貨使用紀錄的去處（資料庫 currency_log）。 */
    @FunctionalInterface
    public interface UsageLog {
        void record(Long characterId, String playerName, String itemUuid, String currencyId, String before, String after);
    }

    /**
     * 做出某種通貨的物品，給別的模組接手（2026-10：未切割寶石算通貨，物品本身是寶石，由 skill 模組做）。
     * 不是它管的種類回傳 null，照一般通貨做。
     */
    @FunctionalInterface
    public interface Maker {
        ItemStack make(CurrencyType type, int amount);
    }

    /** 沒有通貨標記的物品是哪一種通貨（舊版做的未切割寶石只有寶石的標記）；不是回傳 null。 */
    @FunctionalInterface
    public interface Resolver {
        String idOf(ItemStack stack);
    }

    private final Logger log;
    private final CurrencyRepository repository;
    private final ItemService items;
    private final NamespacedKey currencyKey;
    private final boolean resourcePackEnabled;
    private final Plugin plugin;
    private UsageLog usageLog = (characterId, playerName, itemUuid, currencyId, before, after) -> { };
    private Maker maker = (type, amount) -> null;
    private Resolver resolver = stack -> null;

    public CurrencyService(final Plugin plugin, final Logger log, final CurrencyRepository repository,
                           final ItemService items, final boolean resourcePackEnabled) {
        this.log = log;
        this.repository = repository;
        this.items = items;
        this.currencyKey = new NamespacedKey(plugin, "currency");
        this.resourcePackEnabled = resourcePackEnabled;
        this.plugin = plugin;
    }

    /**
     * 僥倖石變成傳奇的機率（2026-10）：跟商人賭博賭到傳奇的機率一樣（economy.yml 的 gamble.rarity_weights：
     * 這個基底能做出的傳奇有地圖 Boss 也會掉的用 boss_unique，不然用 unique）。經濟模組沒啟用時照 currencies.yml 的 success。
     */
    public double chanceOdds(final CurrencyType type, final List<UniqueItem> candidates) {
        if (plugin instanceof tw.exilecore.ExileCore core && core.economy() != null) {
            boolean bossPool = false;
            for (final UniqueItem unique : candidates) {
                bossPool |= unique.droppedBy("map_boss");
            }
            // 2026-10-28 照 PoE2 的稀有度：乘這個基底能做出的傳奇的賭博權重倍率（UniqueRarity.factor）；這次之前每個基底一樣
            return core.economy().config().gambleUniqueChance(bossPool) * items.repository().gambleFactor(candidates);
        }
        return type.success();
    }

    public void setUsageLog(final UsageLog usageLog) {
        this.usageLog = usageLog;
    }

    /** skill 模組啟用時接上：未切割寶石的物品怎麼做、舊的未切割寶石怎麼認（停用時傳 null 拿掉）。 */
    public void setGemHooks(final Maker maker, final Resolver resolver) {
        this.maker = maker == null ? (type, amount) -> null : maker;
        this.resolver = resolver == null ? stack -> null : resolver;
    }

    /** 通貨物品上的標記（PDC 的鍵）：別的模組做通貨物品（未切割寶石）時掛同一個。 */
    public NamespacedKey key() {
        return currencyKey;
    }

    public CurrencyRepository repository() {
        return repository;
    }

    /** 做出 amount 顆通貨（未切割寶石交給 skill 模組做成寶石）。 */
    public ItemStack create(final CurrencyType type, final int amount) {
        final ItemStack made = maker.make(type, amount);
        if (made != null) {
            return made;
        }
        Material material = Material.matchMaterial(type.material());
        if (material == null) {
            material = Material.EMERALD;
        }
        final ItemStack stack = ItemStack.of(material, Math.max(1, Math.min(type.stack(), amount)));
        stack.editMeta(meta -> {
            // 2026-10-27：星籤的名稱照稀有度上色（OmenRarity）；這次之前所有通貨都是金色
            final OmenRarity omenRarity = OmenRarity.of(type);
            meta.displayName(ItemRenderer.line((omenRarity == null ? "<#f5b942>" : omenRarity.tag()) + ItemRenderer.escape(type.name())));
            final List<Component> lore = new ArrayList<>();
            if (omenRarity != null) {
                lore.add(ItemRenderer.line("<gray>星籤・稀有度 " + omenRarity.tag() + omenRarity.label()));
            }
            lore.add(ItemRenderer.line("<gray>" + ItemRenderer.escape(type.description())));
            if (!type.flavor().isBlank()) {
                lore.add(ItemRenderer.line("<dark_gray><i>" + ItemRenderer.escape(type.flavor())));
            }
            lore.add(Component.empty());
            // 2026-10-17：不改物品的（碎片、鑰匙、入場券、活動素材…）不寫「點擊裝備來使用」
            if (CurrencyGroups.card(type)) {
                // 2026-10-19 星諭卡：右鍵用（手上、背包裡）
                lore.add(ItemRenderer.line(CurrencyGroups.DECK.equals(type.id())
                        ? "<#c9a0ff>右鍵：打開一個　Shift＋右鍵：整疊打開"
                        : "<#c9a0ff>右鍵：兌換一套　Shift＋右鍵：這種卡全部兌換"));
                lore.add(ItemRenderer.line("<dark_gray>撿起來直接進倉庫通貨頁的「星諭卡」；在那裡按 Q 可以花金幣賭卡"));
            } else if (type.isSkillCurrency()) {
                lore.add(ItemRenderer.line("<dark_gray>打開技能面板（/skills），拿在游標上點技能來使用"));
            } else if (type.operation() == CurrencyOperation.RESONANCE) {
                // 2026-10-18：共鳴石用在專屬輔助寶石上（背包裡的寶石，或技能面板上掛著的）
                lore.add(ItemRenderer.line("<dark_gray>拿在游標上點一顆專屬掉落的輔助寶石，或點技能面板（/skills）裡掛著的那顆"));
            } else if (type.operation() != CurrencyOperation.NONE) {
                lore.add(ItemRenderer.line("<dark_gray>拿在游標上點擊裝備來使用"));
            }
            lore.add(ItemRenderer.line("<dark_gray>通貨・堆疊上限 " + type.stack()));
            meta.lore(tw.exilecore.core.text.TextWrap.lore(lore)); // 2026-10-26：長的說明自動換行
            meta.setMaxStackSize(type.stack());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.setEnchantmentGlintOverride(true);
            // 自己畫的通貨圖：用下面的 custom_model_data 標記（不看 resource-pack.enabled）；這裡只處理伺服器自己指定的別種模型
            if (resourcePackEnabled && type.model() != null && CurrencyArt.idOf(type.model()) == null) {
                final NamespacedKey model = NamespacedKey.fromString(type.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(currencyKey, PersistentDataType.STRING, type.id());
        });
        tag(stack, type);
        ItemRenderer.nativeText(stack); // 2026-10 效能：說明存成原版的文字元件
        return stack;
    }

    /**
     * 通貨的專屬貼圖：物品上掛 custom_model_data 的字串 {@code currency:圖的代號}，資源包把那種原版物品改成「看到這個字串就畫我們的圖」
     * （見 UiPack）。沒套資源包的玩家看到的是原本的物品；不用 item_model，所以不會在沒開 resource-pack.enabled 時沒貼圖、
     * 也不會在沒套資源包時變成紫黑方塊。資料元件放在 editMeta 之後設定，才不會被 ItemMeta 寫回時蓋掉。
     *
     * @return 有沒有改到物品
     */
    private static boolean tag(final ItemStack stack, final CurrencyType type) {
        String art = CurrencyArt.artOf(type);
        if (art == null && OmenRarity.of(type) != null) {
            art = OmenRarity.of(type).artId(); // 2026-10-27：沒有自己的圖的星籤也掛標記，資源包照稀有度在格子裡畫外框
        }
        if (art == null) {
            return false;
        }
        final String key = CurrencyArt.modelKey(art);
        final CustomModelData current = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (current != null && !current.strings().isEmpty() && key.equals(current.strings().getFirst())) {
            return false;
        }
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(key).build());
        return true;
    }

    /**
     * 選單裡代表某種通貨的圖示（通貨袋、倉庫的通貨頁、掉落過濾、功勳兌換…）掛上那種通貨的貼圖標記，跟真的通貨長得一樣。
     * 圖示的材質要是那種通貨的材質（{@link CurrencyType#material()}）；發光、外框之類的處理之後再呼叫也可以。回傳同一個物品。
     */
    public static ItemStack decorate(final ItemStack icon, final CurrencyType type) {
        if (icon != null && !icon.isEmpty() && type != null) {
            tag(icon, type);
        }
        return icon;
    }

    /**
     * 舊版做的通貨補上貼圖標記（登入、換角色、開倉庫時全部掃一遍；之後新做的通貨一出生就有）。不是通貨、已經有標記、沒有圖的回傳 false。
     *
     * @return 有沒有改到物品
     */
    public boolean refresh(final ItemStack stack) {
        final Optional<CurrencyType> type = read(stack);
        if (type.isEmpty()) {
            return false;
        }
        if (!stack.getPersistentDataContainer().has(currencyKey, PersistentDataType.STRING)) {
            // 舊版的未切割寶石（只有寶石的標記）：換成現在的樣子（掛上通貨標記），才疊得上新撿到的
            final ItemStack fresh = maker.make(type.get(), stack.getAmount());
            if (fresh != null && fresh.getType() == stack.getType()) {
                stack.setItemMeta(fresh.getItemMeta());
                return true;
            }
        }
        // 名稱或說明改過（2026-10 換掉跟別的遊戲一樣的名字、再改得更直觀）：整個換成照現在的定義做的（通貨沒有個別的資料，只差數量）
        final ItemStack fresh = create(type.get(), stack.getAmount());
        // 2026-10-27：星籤換了原版材質（以前都是書）：舊的那疊材質不變，名稱、說明、外框標記照樣換成新的
        if (fresh != null && (fresh.getType() == stack.getType() || OmenRarity.of(type.get()) != null)
                && fresh.hasItemMeta() && stack.hasItemMeta()
                && fresh.getItemMeta().getClass() == stack.getItemMeta().getClass()
                && !ItemRenderer.sameText(fresh.getItemMeta(), stack.getItemMeta())) {
            stack.setItemMeta(fresh.getItemMeta());
            return true;
        }
        return tag(stack, type.get());
    }

    public Optional<CurrencyType> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String id = stack.getPersistentDataContainer().get(currencyKey, PersistentDataType.STRING);
        if (id == null) {
            id = resolver.idOf(stack); // 舊版的未切割寶石
        }
        return id == null ? Optional.empty() : Optional.ofNullable(repository.get(id));
    }

    /**
     * 選單裡代表某種通貨的圖示：跟真的那顆長得一樣（貼圖、寶石的模型都一樣），但拿掉所有標記，不會被當成真的通貨或寶石。
     * 名稱與說明由呼叫端蓋掉。
     */
    public ItemStack icon(final CurrencyType type, final int amount) {
        final ItemStack stack = create(type, Math.max(1, amount));
        stack.editMeta(meta -> {
            final var container = meta.getPersistentDataContainer();
            for (final NamespacedKey key : new ArrayList<>(container.getKeys())) {
                container.remove(key);
            }
        });
        return stack;
    }

    public boolean isCurrency(final ItemStack stack) {
        return read(stack).isPresent();
    }

    /**
     * 把一顆通貨用在一件裝備上。成功時裝備會被改寫並重繪，但通貨的數量由呼叫端扣。
     */
    public Result apply(final ItemStack currencyStack, final ItemStack target, final String playerName) {
        return apply(currencyStack, target, playerName, null);
    }

    /** 同上，並把紀錄掛在角色底下。 */
    public Result apply(final ItemStack currencyStack, final ItemStack target, final String playerName, final Long characterId) {
        final Optional<CurrencyType> currency = read(currencyStack);
        if (currency.isEmpty()) {
            return Result.NOT_CURRENCY;
        }
        final Optional<ItemData> dataOptional = items.read(target);
        if (dataOptional.isEmpty()) {
            return Result.NOT_EQUIPMENT;
        }
        final ItemData data = dataOptional.get();
        final CurrencyType type = currency.get();
        if (!usable(type, data)) {
            return Result.NOT_APPLICABLE;
        }
        final String before = items.toJson(data);
        final List<String> linesBefore = affixTexts(data);
        final ItemData snapshot = type.operation() == CurrencyOperation.CORRUPT ? data.copy() : null; // 2026-10-28：腐化結果要寫在聊天
        final Outcome outcome = run(type, data, random(data, type));
        if (outcome.result() == Result.NOT_APPLICABLE) {
            return Result.NOT_APPLICABLE;
        }
        if (outcome.omen() != null) {
            lastOmen = outcome.omen();
        }
        if (outcome.result() == Result.DESTROYED) {
            usageLog.record(characterId, playerName, data.uuid(), type.id(), before, "{\"destroyed\":true}");
            log.info("[currency] {} 對 {} 使用 {}：碎掉了", playerName, data.uuid(), type.id());
            return Result.DESTROYED;
        }
        items.update(target, data);
        lastChanges = diff(linesBefore, affixTexts(data)); // 2026-10-27 照站長：移除、新增了哪一條（星籤移除最低那條也看得出來）
        if (snapshot != null) {
            lastChanges = lastChanges.withHeader(corruptionSummary(snapshot, data, lastChanges, target)); // 2026-10-28 照站長：腐化結果顯示在聊天
        }
        final String after = items.toJson(data);
        // 寫進資料庫的 currency_log（查作弊、看經濟），主控台也留一行
        usageLog.record(characterId, playerName, data.uuid(), type.id(), before, after);
        log.info("[currency] {} 對 {} 使用 {}{}", playerName, data.uuid(), type.id(),
                type.operation() == CurrencyOperation.CHANCE ? "：變成傳奇 " + data.unique() : "");
        return Result.APPLIED;
    }

    /** 一次使用的結果：成功、碎掉、不能用；生效的星籤（沒有是 null）。 */
    private record Outcome(Result result, CurrencyType omen) {
    }

    /** 這顆通貨現在能不能用在這件上（規則面；星籤的限制在 {@link #run} 裡）。 */
    private boolean usable(final CurrencyType type, final ItemData data) {
        if ((data.corrupted() || data.sanctified()) && !type.operation().allowedOnCorrupted()) {
            return false; // 已腐化（2026-10）、已聖化（2026-10-26）：不能再改造（鑑定、嵌符文到空孔除外）
        }
        if (type.operation() == CurrencyOperation.MIRROR) {
            return false; // 映星鏡會多出一件物品：走 mirror()（呼叫端要接住複製品），不能當成一般通貨用
        }
        return type.canApply(data, items.generator());
    }

    /**
     * 2026-10-26：這次擲骰用的亂數——上了預見鎖（PoE2 的 Hinekora's Lock）的照物品記的種子擲，所以先算一次給玩家看的結果跟真的用一樣。
     * 預見鎖本身照一般的亂數。
     */
    private java.util.random.RandomGenerator random(final ItemData data, final CurrencyType type) {
        if (data.foresight() != null && type.operation() != CurrencyOperation.FORESEE) {
            return new java.util.SplittableRandom(data.foresight());
        }
        return items.random();
    }

    /** 真的做（data 會被改）：星籤、高階／完美通貨的最低詞綴等級、僥倖石都在這裡。 */
    private Outcome run(final CurrencyType type, final ItemData data, final java.util.random.RandomGenerator random) {
        // 掛著的星籤：這顆通貨觸發的話照星籤的規則做，星籤用掉
        final CurrencyType omen = data.omen() == null ? null : repository.get(data.omen());
        final boolean omenFires = Crafting.triggers(omen, type);
        if (omenFires && Crafting.omenProblem(omen, type, data, items.generator()) != null) {
            return new Outcome(Result.NOT_APPLICABLE, null);
        }
        final boolean hadForesight = data.foresight() != null;
        final CurrencyType[] fired = {null};
        final Result[] result = {Result.APPLIED};
        ItemGenerator.withMinModLevel(type.minModLevel(), () -> {
            switch (type.operation()) {
                case CHANCE -> {
                    final boolean noDestroy = omenFires && omen.omen().effect() == CurrencyType.Omen.Effect.NO_DESTROY;
                    final boolean ancients = omenFires && omen.omen().effect() == CurrencyType.Omen.Effect.ANCIENTS;
                    result[0] = chance(type, data, random, ancients);
                    if (omenFires && (noDestroy && result[0] == Result.DESTROYED || ancients && result[0] == Result.APPLIED)) {
                        fired[0] = omen;
                        data.setOmen(null);
                    }
                    if (noDestroy && result[0] == Result.DESTROYED) {
                        result[0] = Result.APPLIED; // 2026-10-26（Omen of Chance）：失敗也不碎，物品不變
                    }
                }
                case ESSENCE -> {
                    if (Crafting.applyEssence(type, data, items.generator(), random, omenFires ? omen : null)) {
                        fired[0] = omen;
                        data.setOmen(null);
                    }
                }
                case CATALYST -> Crafting.applyCatalyst(type, data);
                case RUNE -> Crafting.applyRune(type, data);
                case OMEN -> Crafting.attachOmen(type, data);
                default -> {
                    if (omenFires) {
                        if (Crafting.applyWithOmen(omen, type, data, items.generator(), random)) {
                            fired[0] = omen;
                        }
                        data.setOmen(null);
                    } else {
                        type.operation().apply(data, items.generator(), random);
                    }
                }
            }
        });
        if (hadForesight && type.operation() != CurrencyOperation.FORESEE && type.operation() != CurrencyOperation.OMEN) {
            data.setForesight(null); // 2026-10-26：預見鎖：物品被改過就沒了
        }
        return new Outcome(result[0], fired[0]);
    }

    /**
     * 2026-10-26：預見鎖（PoE2 的 Hinekora's Lock）：這顆通貨用在這件上會變成怎樣（不改原本的物品）。
     * 沒上預見鎖、不能用回傳 null；碎掉回傳一件 rarity 不變、affixes 清空、標成碎掉（{@link Preview#destroyed}）的預覽。
     */
    public Preview foresee(final ItemStack currencyStack, final ItemStack target) {
        final CurrencyType type = read(currencyStack).orElse(null);
        final ItemData data = items.read(target).orElse(null);
        if (type == null || data == null || data.foresight() == null || type.operation() == CurrencyOperation.FORESEE
                || type.operation() == CurrencyOperation.OMEN || type.operation() == CurrencyOperation.NONE || !usable(type, data)) {
            return null;
        }
        final ItemData copy = data.copy();
        final Outcome outcome = run(type, copy, random(data, type));
        if (outcome.result() == Result.NOT_APPLICABLE) {
            return null;
        }
        return new Preview(type, copy, outcome.result() == Result.DESTROYED);
    }

    /** 2026-10-26：預見的結果。 */
    public record Preview(CurrencyType currency, ItemData after, boolean destroyed) {
    }

    /** 複製裝備的通貨（operation: MIRROR，預設是映星鏡）；currencies.yml 沒有這種通貨回傳 null。 */
    public CurrencyType mirrorType() {
        return repository.firstWith(CurrencyOperation.MIRROR).orElse(null);
    }

    /** 2026-10-18：共鳴石（operation: RESONANCE）；currencies.yml 沒有這種通貨回傳 null。 */
    public CurrencyType resonanceType() {
        return repository.firstWith(CurrencyOperation.RESONANCE).orElse(null);
    }

    /**
     * 映星鏡：做出 target 的鏡像複製品（新的物品，原本那件不動）。不能複製（不是裝備、傳奇、已腐化、已經是複製品…）回傳 null。
     * 通貨的數量、把複製品交給玩家都由呼叫端處理；紀錄寫在複製品的代號底下（before = 原本那件，after = 複製品）。
     */
    public ItemStack mirror(final CurrencyType type, final ItemStack target, final String playerName, final Long characterId) {
        if (type == null || type.operation() != CurrencyOperation.MIRROR) {
            return null;
        }
        final ItemData data = items.read(target).orElse(null);
        if (data == null || !type.canApply(data, items.generator())) {
            return null;
        }
        final ItemData copy = Crafting.mirror(data);
        final ItemStack made = target.clone();
        made.setAmount(1);
        items.update(made, copy);
        usageLog.record(characterId, playerName, copy.uuid(), type.id(), items.toJson(data), items.toJson(copy));
        log.info("[currency] {} 對 {} 使用 {}：複製品 {}", playerName, data.uuid(), type.id(), copy.uuid());
        return made;
    }

    /** 注入（選單選好核心之後）：寫紀錄（跟其他通貨一樣進 currency_log）。 */
    public void recordUse(final Long characterId, final String playerName, final String itemUuid, final String currencyId,
                          final String before, final String after) {
        usageLog.record(characterId, playerName, itemUuid, currencyId, before, after);
        log.info("[currency] {} 對 {} 使用 {}", playerName, itemUuid, currencyId);
    }

    /** 2026-10-27：上一次 apply 移除、新增的詞綴（玩家看得到的那一行字；沒變是空的）。 */
    public record Changes(List<String> removed, List<String> added, String header) {
        public Changes(final List<String> removed, final List<String> added) {
            this(removed, added, null);
        }

        /** 2026-10-28：最前面多一行（腐化結果）。 */
        public Changes withHeader(final String header) {
            return new Changes(removed, added, header);
        }

        public boolean isEmpty() {
            return removed.isEmpty() && added.isEmpty();
        }

        /** 給聊天用的一段 MiniMessage（沒變、也沒有開頭那行回傳 null）。 */
        public String message() {
            if (isEmpty() && header == null) {
                return null;
            }
            final StringBuilder out = new StringBuilder(header == null ? "" : header);
            for (final String line : removed) {
                out.append(out.isEmpty() ? "" : "\n").append("<#ff7070>－ 移除：<gray>").append(ItemRenderer.escape(line));
            }
            for (final String line : added) {
                out.append(out.isEmpty() ? "" : "\n").append("<#7dff8a>＋ 新增：<gray>").append(ItemRenderer.escape(line));
            }
            return out.toString();
        }
    }

    private Changes lastChanges = new Changes(List.of(), List.of());

    /** 上一次 {@link #apply} 移除、新增了哪些詞綴（拿一次就清掉）。 */
    public Changes takeChanges() {
        final Changes changes = lastChanges;
        lastChanges = new Changes(List.of(), List.of());
        return changes;
    }

    /**
     * 2026-10-28 照站長：腐化的結果（一行 MiniMessage）：物品名稱＋沒有變化／品質＋x%／多一個符文孔／階級 a → b／詞綴、數值變了（下面列出）。
     */
    String corruptionSummary(final ItemData before, final ItemData after, final Changes changes, final ItemStack stack) {
        final String name = stack == null ? "裝備" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.effectiveName());
        final List<String> parts = new ArrayList<>();
        if (after.quality() != before.quality()) {
            parts.add("品質 +" + (after.quality() - before.quality()) + "%（" + after.quality() + "%）");
        }
        if (after.corruptSockets() != before.corruptSockets()) {
            parts.add("多一個符文孔");
        }
        final tw.exilecore.item.ItemBase base = items.repository().base(after.base());
        if (base != null && base.isMap()) {
            final int from = tw.exilecore.item.ItemStats.mapTier(base, before);
            final int to = tw.exilecore.item.ItemStats.mapTier(base, after);
            if (from != to) {
                parts.add("階級 T" + from + " → T" + to);
            }
        }
        if (!changes.isEmpty()) {
            parts.add(after.rarity() == tw.exilecore.item.Rarity.UNIQUE && changes.added().size() == changes.removed().size()
                    && !changes.added().isEmpty() ? "數值重新擲骰" : "詞綴有變化");
        }
        final String result = parts.isEmpty() ? "<gray>沒有變化" : "<#d20000>" + String.join("、", parts);
        return "<#d20000>腐化 <white>" + ItemRenderer.escape(name) + "<gray>：" + result;
    }

    private List<String> affixTexts(final ItemData data) {
        if (!data.identified()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (final tw.exilecore.item.ItemStats.AffixLine line : tw.exilecore.item.ItemStats.affixLines(items.repository(), data)) {
            out.add(line.text() + (line.tier() > 0 ? "（T" + line.tier() + "）" : ""));
        }
        return out;
    }

    /** 兩份詞綴字串的差（同一行出現幾次就算幾條）。 */
    static Changes diff(final List<String> before, final List<String> after) {
        final List<String> removed = new ArrayList<>(before);
        final List<String> added = new ArrayList<>();
        for (final String line : after) {
            if (!removed.remove(line)) {
                added.add(line);
            }
        }
        return new Changes(removed, added);
    }

    /** 上一次 apply 時生效的星籤（給聊天提示用；沒有是 null）。 */
    private CurrencyType lastOmen;

    /** 上一次 {@link #apply} 有沒有星籤生效（拿一次就清掉）。 */
    public CurrencyType takeFiredOmen() {
        final CurrencyType fired = lastOmen;
        lastOmen = null;
        return fired;
    }

    /** 這顆通貨不能用在這件上的原因（給提示；不是星魄、催化劑、星籤就是 null）。 */
    public String explain(final ItemStack currencyStack, final ItemStack target) {
        final Optional<CurrencyType> type = read(currencyStack);
        final Optional<ItemData> data = items.read(target);
        if (type.isEmpty() || data.isEmpty()) {
            return null;
        }
        if (data.get().corrupted() && !type.get().operation().allowedOnCorrupted()) {
            return type.get().operation() == CurrencyOperation.MIRROR ? "已腐化的物品不能複製" : "已腐化的物品不能再改造";
        }
        if (data.get().sanctified() && !type.get().operation().allowedOnCorrupted()) {
            return "已聖化的物品不能再改造"; // 2026-10-26
        }
        final String problem = Crafting.problem(type.get(), data.get(), items.generator());
        if (problem != null) {
            return problem;
        }
        final CurrencyType omen = data.get().omen() == null ? null : repository.get(data.get().omen());
        if (Crafting.triggers(omen, type.get())) {
            return Crafting.omenProblem(omen, type.get(), data.get(), items.generator());
        }
        return null;
    }

    /**
     * 僥倖石：success 的機率變成這個基底的傳奇，不然物品碎掉（data 不動，呼叫端處理）。
     * 2026-10-26：ancients（PoE2 的 Omen of the Ancients）：成功時從同一個部位（裝備欄位）能用僥倖石做出來的傳奇裡隨機挑，不限這個基底。
     */
    private Result chance(final CurrencyType type, final ItemData data, final java.util.random.RandomGenerator random, final boolean ancients) {
        // 追逐級（2026-10）：這個基底有追逐級傳奇的話，先擲一次跟小怪一樣的極低機率
        final List<UniqueItem> chase = items.generator().chaseUniques(data.base(), data.ilvl());
        if (!chase.isEmpty() && random.nextDouble() < ItemGenerator.chaseChance()) {
            items.generator().makeUnique(data, ItemGenerator.pickUnique(chase, random), random);
            return Result.APPLIED;
        }
        final List<UniqueItem> candidates = CurrencyOperation.chanceUniques(data, items.generator());
        if (!candidates.isEmpty() && random.nextDouble() < chanceOdds(type, candidates)) {
            final List<UniqueItem> pool = ancients ? ancientUniques(data) : candidates;
            final UniqueItem pick = items.repository().pickGamble(pool.isEmpty() ? candidates : pool, random); // 2026-10-28：照賭博權重挑
            if (!pick.base().equals(data.base())) {
                // 同一類的別的基底：物品換成那個基底（固定詞綴照新的基底擲）
                final ItemData fresh = items.generator().generate(items.repository().requireBase(pick.base()),
                        tw.exilecore.item.Rarity.NORMAL, data.ilvl(), random);
                data.setBase(pick.base());
                data.setImplicitVars(fresh.implicitVars());
            }
            items.generator().makeUnique(data, pick, random);
            return Result.APPLIED;
        }
        return Result.DESTROYED;
    }

    /** 2026-10-26（Omen of the Ancients）：跟這件同一個裝備欄位、物品等級夠、能用僥倖石做出來的傳奇。 */
    private List<UniqueItem> ancientUniques(final ItemData data) {
        final tw.exilecore.item.ItemBase base = items.repository().base(data.base());
        final List<UniqueItem> list = new ArrayList<>();
        if (base == null) {
            return list;
        }
        for (final UniqueItem unique : items.repository().uniques()) {
            final tw.exilecore.item.ItemBase other = items.repository().base(unique.base());
            if (other != null && other.slot() == base.slot() && other.isWeapon() == base.isWeapon()
                    && unique.droppedBy(CurrencyOperation.CHANCE_SOURCE) && unique.dropLevel() <= data.ilvl() && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }
}
