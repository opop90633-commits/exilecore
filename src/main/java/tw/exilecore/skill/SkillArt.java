package tw.exilecore.skill;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.currency.CurrencyArt;

import java.util.List;
import java.util.Locale;

/**
 * 技能圖示（2026-10）：web/skill_art.txt（tools/gen_skill_art.py 產生）每個技能一張 16×16，代號 skill_技能代號。
 * 跟通貨的貼圖同一套（CurrencyArt 一起讀進來、資源包一起產生），掛在物品上的是 custom_model_data 字串 currency:skill_代號，
 * 沒套資源包的玩家看到的還是 skills.yml 的 icon 那個原版物品。
 */
public final class SkillArt {

    public static final String RESOURCE = "/web/skill_art.txt";
    public static final String PREFIX = "skill_";
    /** 技能寶石、圖示會用到的原版物品（資源包要在這些物品上加挑選）。 */
    public static final List<String> FALLBACK_ITEMS = List.of("heart_of_the_sea", "book", "wooden_sword");
    /** 劫掠的變體技能（fireball_anomalous 之類）沿用原本技能的圖。 */
    private static final List<String> VARIANT_SUFFIXES = List.of("_anomalous", "_divergent", "_phantasmal");

    private SkillArt() {
    }

    /** 這個技能的圖代號（skill_xxx）；沒有圖回傳 null。 */
    public static String artOf(final String skillId) {
        if (skillId == null) {
            return null;
        }
        if (CurrencyArt.has(PREFIX + skillId)) {
            return PREFIX + skillId;
        }
        for (final String suffix : VARIANT_SUFFIXES) {
            if (skillId.endsWith(suffix)) {
                final String base = PREFIX + skillId.substring(0, skillId.length() - suffix.length());
                return CurrencyArt.has(base) ? base : null;
            }
        }
        return null;
    }

    /** 輔助寶石的圖代號（support_xxx，2026-10）；沒有圖回傳 null。 */
    public static String supportArtOf(final String supportId) {
        return supportId != null && CurrencyArt.has("support_" + supportId) ? "support_" + supportId : null;
    }

    /** 掛上輔助寶石圖示的標記（有圖才掛；回傳同一個物品）。 */
    public static ItemStack decorateSupport(final ItemStack stack, final String supportId) {
        final String art = supportArtOf(supportId);
        if (stack != null && !stack.isEmpty() && art != null) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(CurrencyArt.modelKey(art)).build());
        }
        return stack;
    }

    public static String artOf(final SkillDefinition definition) {
        return definition == null ? null : artOf(definition.id());
    }

    /** 掛上技能圖示的標記（有圖才掛；回傳同一個物品）。 */
    public static ItemStack decorate(final ItemStack stack, final SkillDefinition definition) {
        final String art = artOf(definition);
        if (stack != null && !stack.isEmpty() && art != null) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(CurrencyArt.modelKey(art)).build());
        }
        return stack;
    }

    /** skills.yml 的 icon 對應的原版物品代號（小寫、沒有 minecraft:）；不是物品回傳 null。 */
    public static String vanillaItem(final String icon) {
        final Material material = icon == null ? null : Material.matchMaterial(icon);
        return material == null || !material.isItem() || material.isAir() ? null : material.getKey().getKey().toLowerCase(Locale.ROOT);
    }
}
