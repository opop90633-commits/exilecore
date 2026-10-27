package tw.exilecore.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * ItemData 與物品 PersistentDataContainer 之間的轉換。
 * 主要資料是一個 JSON 字串（{@code exilecore:data}），另外存兩個索引鍵（基底、稀有度）方便快速過濾。
 */
public final class ItemCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final NamespacedKey dataKey;
    private final NamespacedKey baseKey;
    private final NamespacedKey rarityKey;
    /** 傳奇：上次照 uniques.yml 檢查（補正）時的定義指紋（{@link ItemRepository#uniqueRevision()}）。 */
    private final NamespacedKey uniqueRevisionKey;
    /** 2026-10-26：上次照詞綴資料換算時的指紋（{@link ItemRepository#affixRevision()}、{@link AffixRescale}）。 */
    private final NamespacedKey affixRevisionKey;

    public ItemCodec(final Plugin plugin) {
        this.dataKey = new NamespacedKey(plugin, "data");
        this.baseKey = new NamespacedKey(plugin, "base");
        this.rarityKey = new NamespacedKey(plugin, "rarity");
        this.uniqueRevisionKey = new NamespacedKey(plugin, "unique_rev");
        this.affixRevisionKey = new NamespacedKey(plugin, "affix_rev");
    }

    /** 這個物品是不是 ExileCore 的裝備。 */
    public boolean isEquipment(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(dataKey);
    }

    public Optional<ItemData> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return parse(rawJson(stack));
    }

    /** 2026-10-27：PDC 裡原樣的資料 JSON（沒有回傳 null）。 */
    public String rawJson(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(dataKey, PersistentDataType.STRING);
    }

    /** 2026-10-27：把資料 JSON 解回物品資料（舊格式照樣轉換）。 */
    public Optional<ItemData> parse(final String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            final ItemData data = GSON.fromJson(json, ItemData.class);
            return Optional.ofNullable(migrate(data));
        } catch (final JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    /** 2026-10-27 效能：基底代號（只看 PDC，不解 JSON）；沒有回傳 null。 */
    public String baseId(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(baseKey, PersistentDataType.STRING);
    }

    /** 是不是傳奇（只看 PDC 的稀有度，不解 JSON）。 */
    public boolean isUnique(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return Rarity.UNIQUE.name().equals(stack.getPersistentDataContainer().get(rarityKey, PersistentDataType.STRING));
    }

    /** 上次檢查這件傳奇時的定義指紋（沒有 = null）。 */
    public Integer uniqueRevision(final ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getPersistentDataContainer().get(uniqueRevisionKey, PersistentDataType.INTEGER);
    }

    public void stampUniqueRevision(final ItemStack stack, final int revision) {
        stack.editPersistentDataContainer(container -> container.set(uniqueRevisionKey, PersistentDataType.INTEGER, revision));
    }

    /** 2026-10-26：上次照詞綴資料換算時的指紋（沒有 = null，這次之前做的舊物品）。 */
    public Integer affixRevision(final ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getPersistentDataContainer().get(affixRevisionKey, PersistentDataType.INTEGER);
    }

    public void stampAffixRevision(final ItemStack stack, final int revision) {
        stack.editPersistentDataContainer(container -> container.set(affixRevisionKey, PersistentDataType.INTEGER, revision));
    }

    public void write(final ItemStack stack, final ItemData data) {
        stack.editPersistentDataContainer(container -> write(container, data));
    }

    public void write(final PersistentDataContainer container, final ItemData data) {
        container.set(dataKey, PersistentDataType.STRING, GSON.toJson(data));
        container.set(baseKey, PersistentDataType.STRING, data.base());
        container.set(rarityKey, PersistentDataType.STRING, data.rarity().name());
    }

    public String toJson(final ItemData data) {
        return GSON.toJson(data);
    }

    /**
     * 舊版資料格式的轉換；目前只有版本 1。
     * 2026-10-26：詞綴範圍、拿掉的詞綴跟著新資料換算不在這裡做（這裡拿不到詞綴資料），在 {@link ItemService#read}／{@link ItemService#refresh}
     * 做（{@link AffixRescale}），refresh 寫回物品並記下指紋。
     */
    private static ItemData migrate(final ItemData data) {
        if (data == null || data.base() == null) {
            return null;
        }
        return data;
    }
}
