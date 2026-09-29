package tw.exilecore.item;

import org.bukkit.inventory.ItemStack;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 其他模組操作裝備的唯一入口：建立、讀取、改完資料後寫回並重繪。
 */
public final class ItemService {

    private final ItemRepository repository;
    private final ItemGenerator generator;
    private final ItemCodec codec;
    private final ItemRenderer renderer;
    private final RandomGenerator random;

    public ItemService(final ItemRepository repository, final ItemGenerator generator, final ItemCodec codec,
                       final ItemRenderer renderer, final RandomGenerator random) {
        this.repository = repository;
        this.generator = generator;
        this.codec = codec;
        this.renderer = renderer;
        this.random = random;
    }

    public ItemRepository repository() {
        return repository;
    }

    public ItemGenerator generator() {
        return generator;
    }

    public RandomGenerator random() {
        return random;
    }

    /** 生成並做成物品。 */
    public ItemStack create(final ItemBase base, final Rarity rarity, final int itemLevel) {
        return toStack(generator.generate(base, rarity, itemLevel, random));
    }

    /** 把資料做成新的 ItemStack。 */
    public ItemStack toStack(final ItemData data) {
        final ItemStack stack = renderer.create(data);
        codec.write(stack, data);
        return stack;
    }

    public boolean isEquipment(final ItemStack stack) {
        return codec.isEquipment(stack);
    }

    public Optional<ItemData> read(final ItemStack stack) {
        return codec.read(stack);
    }

    /** 資料改了以後：寫回 PDC 並重繪外觀。 */
    public void update(final ItemStack stack, final ItemData data) {
        codec.write(stack, data);
        renderer.render(stack, data);
    }

    /** 這件物品給屬性表的修飾器（未鑑定只有基底數值）。 */
    public List<Modifier> modifiers(final ItemStack stack) {
        return read(stack).map(data -> ItemStats.modifiers(repository, data)).orElse(List.of());
    }

    public String toJson(final ItemData data) {
        return codec.toJson(data);
    }
}
