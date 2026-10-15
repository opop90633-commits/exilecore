package tw.exilecore.sponsor;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 時裝的圖示給別的模組的選單用（2026-10，萬燈夜的兌換）：跟贊助選單一樣，送了資源包就是那一套的 3D 樣子。 */
public final class CosmeticPreview {

    private CosmeticPreview() {
    }

    /** 沒有這個時裝 null。 */
    public static ItemStack of(final SponsorService service, final String id, final String name, final List<String> lore) {
        final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(id);
        if (cosmetic == null || !cosmetic.skin()) {
            return null;
        }
        return SponsorMenus.skinStack(service, cosmetic, name, lore);
    }

    /** 這個時裝的稀有度顏色加名字（「<#c27bff>巫女帽」這樣）。 */
    public static String label(final SponsorService service, final String id) {
        final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(id);
        return cosmetic == null ? id : SponsorService.rarityTag(cosmetic);
    }

    /** 幻化的部位（「近戰武器／法杖」這樣）。 */
    public static String slotName(final SponsorService service, final String id) {
        final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(id);
        if (cosmetic == null || cosmetic.skinSlot() == null) {
            return "";
        }
        return switch (cosmetic.skinSlot()) {
            case "weapon" -> "近戰武器、法杖";
            case "bow" -> "弓";
            case "crossbow" -> "十字弓";
            case "offhand" -> "盾、箭袋";
            case "headgear" -> "3D 頭飾";
            case "back" -> "3D 背飾";
            case "pet" -> "3D 寵物";
            default -> "防具";
        };
    }
}
