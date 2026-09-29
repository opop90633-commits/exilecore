package tw.exilecore.currency;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemService;

import java.util.List;
import java.util.Optional;

/**
 * 通貨物品的建立、辨識與套用。
 */
public final class CurrencyService {

    /** 套用結果。 */
    public enum Result { APPLIED, NOT_CURRENCY, NOT_EQUIPMENT, NOT_APPLICABLE }

    private final Logger log;
    private final CurrencyRepository repository;
    private final ItemService items;
    private final NamespacedKey currencyKey;
    private final boolean resourcePackEnabled;

    public CurrencyService(final Plugin plugin, final Logger log, final CurrencyRepository repository,
                           final ItemService items, final boolean resourcePackEnabled) {
        this.log = log;
        this.repository = repository;
        this.items = items;
        this.currencyKey = new NamespacedKey(plugin, "currency");
        this.resourcePackEnabled = resourcePackEnabled;
    }

    public CurrencyRepository repository() {
        return repository;
    }

    /** 做出 amount 顆通貨。 */
    public ItemStack create(final CurrencyType type, final int amount) {
        Material material = Material.matchMaterial(type.material());
        if (material == null) {
            material = Material.EMERALD;
        }
        final ItemStack stack = ItemStack.of(material, Math.max(1, Math.min(type.stack(), amount)));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#f5b942>" + ItemRenderer.escape(type.name())));
            meta.lore(List.of(
                    ItemRenderer.line("<gray>" + ItemRenderer.escape(type.description())),
                    Component.empty(),
                    ItemRenderer.line("<dark_gray>拿在游標上點擊裝備來使用"),
                    ItemRenderer.line("<dark_gray>通貨・堆疊上限 " + type.stack())));
            meta.setMaxStackSize(type.stack());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.setEnchantmentGlintOverride(true);
            if (resourcePackEnabled && type.model() != null) {
                final NamespacedKey model = NamespacedKey.fromString(type.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
            meta.getPersistentDataContainer().set(currencyKey, PersistentDataType.STRING, type.id());
        });
        return stack;
    }

    public Optional<CurrencyType> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        final String id = stack.getPersistentDataContainer().get(currencyKey, PersistentDataType.STRING);
        return id == null ? Optional.empty() : Optional.ofNullable(repository.get(id));
    }

    public boolean isCurrency(final ItemStack stack) {
        return read(stack).isPresent();
    }

    /**
     * 把一顆通貨用在一件裝備上。成功時裝備會被改寫並重繪，但通貨的數量由呼叫端扣。
     */
    public Result apply(final ItemStack currencyStack, final ItemStack target, final String playerName) {
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
        if (!type.canApply(data, items.generator())) {
            return Result.NOT_APPLICABLE;
        }
        final String before = items.toJson(data);
        type.operation().apply(data, items.generator(), items.random());
        items.update(target, data);
        // 階段 2 改寫進資料庫的 currency_log；現在先留在伺服器紀錄，查作弊與看經濟都靠它
        log.info("[currency] {} 對 {} 使用 {}：{} → {}", playerName, data.uuid(), type.id(), before, items.toJson(data));
        return Result.APPLIED;
    }
}
