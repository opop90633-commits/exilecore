package tw.exilecore.item;

import com.google.common.collect.HashMultimap;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 ItemData 畫成玩家看到的物品：材質、名稱、描述、旗標。
 * 所有原版屬性行都隱藏、原版屬性全部清空（傷害由我們的引擎算），物品不可破壞、不可堆疊。
 */
public final class ItemRenderer {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ItemRepository repository;
    private final boolean resourcePackEnabled;

    public ItemRenderer(final ItemRepository repository, final boolean resourcePackEnabled) {
        this.repository = repository;
        this.resourcePackEnabled = resourcePackEnabled;
    }

    /** 建立一個全新的 ItemStack（不含 PDC，由 ItemService 補上）。 */
    public ItemStack create(final ItemData data) {
        final ItemBase base = repository.requireBase(data.base());
        Material material = Material.matchMaterial(base.material());
        if (material == null) {
            material = Material.STICK;
        }
        final ItemStack stack = ItemStack.of(material);
        render(stack, data);
        return stack;
    }

    /** 依資料重繪既有物品的外觀。 */
    public void render(final ItemStack stack, final ItemData data) {
        final ItemBase base = repository.requireBase(data.base());
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, data);
        final List<Component> lore = buildLore(base, data, snapshot);
        final Component name = line("<" + data.rarity().color() + ">" + escape(ItemStats.displayName(repository, data)));

        // 原版屬性（劍的攻擊力、盔甲的護甲值）全部清空：傷害與防禦都由我們的引擎算
        stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.setUnbreakable(true);
            meta.setMaxStackSize(1);
            meta.setAttributeModifiers(HashMultimap.create());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM);
            meta.setEnchantmentGlintOverride(data.rarity() == Rarity.UNIQUE);
            if (resourcePackEnabled && base.model() != null) {
                final NamespacedKey model = NamespacedKey.fromString(base.model());
                if (model != null) {
                    meta.setItemModel(model);
                }
            }
        });
    }

    private List<Component> buildLore(final ItemBase base, final ItemData data, final ItemStats.Snapshot snapshot) {
        final List<Component> lore = new ArrayList<>();
        final String color = data.rarity().color();

        if (data.rarity() == Rarity.RARE || data.rarity() == Rarity.UNIQUE || !data.identified()) {
            lore.add(line("<" + color + ">" + escape(base.name()) + " <dark_gray>" + data.rarity().displayName()));
        } else {
            lore.add(line("<dark_gray>" + data.rarity().displayName() + escape(slotSuffix(base))));
        }
        if (data.quality() > 0) {
            lore.add(line("<gray>品質：<#8888ff>+" + data.quality() + "%"));
        }

        // 基底數值
        if (base.isWeapon()) {
            for (final Map.Entry<DamageType, double[]> entry : snapshot.damage().entrySet()) {
                lore.add(line("<gray>" + entry.getKey().displayName() + "傷害：<white>"
                        + (int) entry.getValue()[0] + " - " + (int) entry.getValue()[1]));
            }
            lore.add(line(String.format(Locale.ROOT, "<gray>暴擊率：<white>%.1f%%  <gray>每秒攻擊：<white>%.2f", snapshot.crit(), snapshot.aps())));
        }
        if (snapshot.armour() > 0) {
            lore.add(line("<gray>護甲：<white>" + (int) snapshot.armour()));
        }
        if (snapshot.evasion() > 0) {
            lore.add(line("<gray>閃避：<white>" + (int) snapshot.evasion()));
        }
        if (snapshot.energyShield() > 0) {
            lore.add(line("<gray>能量護盾：<white>" + (int) snapshot.energyShield()));
        }
        if (snapshot.block() > 0) {
            lore.add(line("<gray>格擋率：<white>" + (int) snapshot.block() + "%"));
        }

        // 需求
        final ItemBase.Requirements req = snapshot.requirements();
        final StringBuilder requirement = new StringBuilder("<gray>需求：等級 <white>" + req.level());
        if (req.str() > 0) {
            requirement.append("<gray>，力量 <white>").append(req.str());
        }
        if (req.dex() > 0) {
            requirement.append("<gray>，敏捷 <white>").append(req.dex());
        }
        if (req.intelligence() > 0) {
            requirement.append("<gray>，智慧 <white>").append(req.intelligence());
        }
        lore.add(line(requirement.toString()));

        if (data.sockets() > 0) {
            lore.add(line("<gray>插槽：<white>" + "◯ ".repeat(data.sockets()).trim()));
        }

        // 固定詞綴與詞綴
        final boolean hasImplicit = base.implicit() != null;
        final boolean hasAffixes = !data.affixes().isEmpty();
        if (hasImplicit || hasAffixes) {
            lore.add(line("<dark_gray>────────────"));
        }
        if (hasImplicit) {
            lore.add(line("<#a0a0ff>" + escape(base.implicit().render(data.implicitVars())) + " <dark_gray>(固定)"));
        }
        if (hasAffixes) {
            if (hasImplicit) {
                lore.add(line("<dark_gray>────────────"));
            }
            if (!data.identified()) {
                lore.add(line("<#ff5555>未鑑定 <dark_gray>— 對它使用鑑定卷軸"));
            } else {
                for (final ItemStats.AffixLine affixLine : ItemStats.affixLines(repository, data)) {
                    lore.add(line("<" + color + ">" + escape(affixLine.text()) + " <dark_gray>(T" + affixLine.tier() + ")"));
                }
            }
        }
        lore.add(line("<dark_gray>物品等級 " + data.ilvl()));
        return lore;
    }

    private static String slotSuffix(final ItemBase base) {
        return "・" + base.slot().displayName();
    }

    /** MiniMessage → Component，並關掉原版描述預設的斜體。 */
    public static Component line(final String miniMessage) {
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    /** 把資料裡的文字放進 MiniMessage 前先跳脫標籤字元。 */
    public static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
