package tw.exilecore.currency;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 一種通貨的定義（data/currencies.yml）：
 * <pre>
 * reroll_rare:
 *   name: 亂流石
 *   material: ENDER_EYE
 *   model: exilecore:currency/reroll_rare
 *   stack: 10
 *   operation: REROLL_RARE
 *   applies_to: { rarity: [RARE] }
 *   drop_weight: 300
 *   description: 重新隨機一件稀有物品的所有詞綴
 * </pre>
 *
 * @param id          代號
 * @param name        顯示名稱
 * @param material    原版材質
 * @param model       資源包模型（可選）
 * @param stack       堆疊上限
 * @param operation   對物品做的操作
 * @param rarities    可用在哪些稀有度（空 = 全部）
 * @param dropWeight  掉落權重
 * @param description 一句話說明
 */
public record CurrencyType(String id, String name, String material, String model, int stack,
                           CurrencyOperation operation, Set<Rarity> rarities, int dropWeight, String description) {

    public CurrencyType {
        rarities = Set.copyOf(rarities);
    }

    public static CurrencyType parse(final String id, final YamlNode node) {
        final CurrencyOperation operation = CurrencyOperation.valueOf(node.requireString("operation").trim().toUpperCase(Locale.ROOT));
        final Set<Rarity> rarities = EnumSet.noneOf(Rarity.class);
        for (final String text : node.node("applies_to").strings("rarity")) {
            rarities.add(Rarity.parse(text, Rarity.NORMAL));
        }
        return new CurrencyType(id, node.requireString("name"),
                node.requireString("material").trim().toUpperCase(Locale.ROOT),
                node.string("model", null), Math.max(1, Math.min(99, node.integer("stack", 20))),
                operation, rarities, node.integer("drop_weight", 0), node.string("description", ""));
    }

    /** 這顆通貨能不能用在這件物品上。 */
    public boolean canApply(final ItemData data, final ItemGenerator generator) {
        if (!rarities.isEmpty() && !rarities.contains(data.rarity())) {
            return false;
        }
        return operation.canApply(data, generator);
    }
}
