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

    public ItemCodec(final Plugin plugin) {
        this.dataKey = new NamespacedKey(plugin, "data");
        this.baseKey = new NamespacedKey(plugin, "base");
        this.rarityKey = new NamespacedKey(plugin, "rarity");
    }

    /** 這個物品是不是 ExileCore 的裝備。 */
    public boolean isEquipment(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(dataKey);
    }

    public Optional<ItemData> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        final String json = stack.getPersistentDataContainer().get(dataKey, PersistentDataType.STRING);
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

    /** 舊版資料格式的轉換；目前只有版本 1。 */
    private static ItemData migrate(final ItemData data) {
        if (data == null || data.base() == null) {
            return null;
        }
        return data;
    }
}
