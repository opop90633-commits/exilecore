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
            return core.economy().config().gambleUniqueChance(bossPool);
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
            meta.displayName(ItemRenderer.line("<#f5b942>" + ItemRenderer.escape(type.name())));
            final List<Component> lore = new ArrayList<>();
            lore.add(ItemRenderer.line("<gray>" + ItemRenderer.escape(type.description())));
            if (!type.flavor().isBlank()) {
                lore.add(ItemRenderer.line("<dark_gray><i>" + ItemRenderer.escape(type.flavor())));
            }
            lore.add(Component.empty());
            // 2026-10-17：不改物品的（碎片、鑰匙、入場券、活動素材…）不寫「點擊裝備來使用」
            if (type.isSkillCurrency()) {
                lore.add(ItemRenderer.line("<dark_gray>打開技能面板（/skills），拿在游標上點技能來使用"));
            } else if (type.operation() == CurrencyOperation.RESONANCE) {
                // 2026-10-18：共鳴石用在專屬輔助寶石上（背包裡的寶石，或技能面板上掛著的）
                lore.add(ItemRenderer.line("<dark_gray>拿在游標上點一顆專屬掉落的輔助寶石，或點技能面板（/skills）裡掛著的那顆"));
            } else if (type.operation() != CurrencyOperation.NONE) {
                lore.add(ItemRenderer.line("<dark_gray>拿在游標上點擊裝備來使用"));
            }
            lore.add(ItemRenderer.line("<dark_gray>通貨・堆疊上限 " + type.stack()));
            meta.lore(lore);
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
        final String art = CurrencyArt.artOf(type);
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
        if (fresh != null && fresh.getType() == stack.getType() && fresh.hasItemMeta() && stack.hasItemMeta()
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
        if (data.corrupted() && type.operation() != CurrencyOperation.IDENTIFY) {
            return Result.NOT_APPLICABLE; // 已腐化：不能再改造（2026-10）
        }
        if (type.operation() == CurrencyOperation.MIRROR) {
            return Result.NOT_APPLICABLE; // 映星鏡會多出一件物品：走 mirror()（呼叫端要接住複製品），不能當成一般通貨用
        }
        if (!type.canApply(data, items.generator())) {
            return Result.NOT_APPLICABLE;
        }
        final String before = items.toJson(data);
        if (type.operation() == CurrencyOperation.CHANCE) {
            return chance(type, data, target, before, playerName, characterId);
        }
        // 掛著的星籤：這顆通貨觸發的話照星籤的規則做，星籤用掉
        final CurrencyType omen = data.omen() == null ? null : repository.get(data.omen());
        final boolean omenFires = Crafting.triggers(omen, type);
        if (omenFires && Crafting.omenProblem(omen, type, data, items.generator()) != null) {
            return Result.NOT_APPLICABLE;
        }
        switch (type.operation()) {
            case ESSENCE -> Crafting.applyEssence(type, data, items.generator(), items.random());
            case CATALYST -> Crafting.applyCatalyst(type, data);
            case RUNE -> Crafting.applyRune(type, data);
            case OMEN -> Crafting.attachOmen(type, data);
            default -> {
                if (omenFires) {
                    if (Crafting.applyWithOmen(omen, type, data, items.generator(), items.random())) {
                        lastOmen = omen;
                    }
                    data.setOmen(null);
                } else {
                    type.operation().apply(data, items.generator(), items.random());
                }
            }
        }
        items.update(target, data);
        final String after = items.toJson(data);
        // 寫進資料庫的 currency_log（查作弊、看經濟），主控台也留一行
        usageLog.record(characterId, playerName, data.uuid(), type.id(), before, after);
        log.info("[currency] {} 對 {} 使用 {}", playerName, data.uuid(), type.id());
        return Result.APPLIED;
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
        if (data.get().corrupted() && type.get().operation() != CurrencyOperation.IDENTIFY) {
            return type.get().operation() == CurrencyOperation.MIRROR ? "已腐化的物品不能複製" : "已腐化的物品不能再改造";
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

    /** 僥倖石：success 的機率變成這個基底的傳奇，不然物品碎掉。 */
    private Result chance(final CurrencyType type, final ItemData data, final ItemStack target, final String before,
                          final String playerName, final Long characterId) {
        // 追逐級（2026-10）：這個基底有追逐級傳奇的話，先擲一次跟小怪一樣的極低機率
        final List<UniqueItem> chase = items.generator().chaseUniques(data.base(), data.ilvl());
        if (!chase.isEmpty() && items.random().nextDouble() < ItemGenerator.chaseChance()) {
            items.generator().makeUnique(data, ItemGenerator.pickUnique(chase, items.random()), items.random());
            items.update(target, data);
            usageLog.record(characterId, playerName, data.uuid(), type.id(), before, items.toJson(data));
            log.info("[currency] {} 對 {} 使用 {}：賭到追逐級傳奇 {}", playerName, data.uuid(), type.id(), data.unique());
            return Result.APPLIED;
        }
        final List<UniqueItem> candidates = CurrencyOperation.chanceUniques(data, items.generator());
        if (!candidates.isEmpty() && items.random().nextDouble() < chanceOdds(type, candidates)) {
            items.generator().makeUnique(data, ItemGenerator.pickUnique(candidates, items.random()), items.random());
            items.update(target, data);
            usageLog.record(characterId, playerName, data.uuid(), type.id(), before, items.toJson(data));
            log.info("[currency] {} 對 {} 使用 {}：變成傳奇 {}", playerName, data.uuid(), type.id(), data.unique());
            return Result.APPLIED;
        }
        usageLog.record(characterId, playerName, data.uuid(), type.id(), before, "{\"destroyed\":true}");
        log.info("[currency] {} 對 {} 使用 {}：碎掉了", playerName, data.uuid(), type.id());
        return Result.DESTROYED;
    }
}
