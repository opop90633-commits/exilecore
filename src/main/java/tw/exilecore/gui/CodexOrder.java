package tw.exilecore.gui;

import org.bukkit.Material;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;

import java.util.Comparator;
import java.util.List;

/**
 * 圖鑑（遊戲裡的 /codex 與網頁圖鑑）的排列順序與珠寶、護符的圖示（2026-10-27）。
 * <p>
 * 這次之前基底只照「部位、需要等級」排：主手的單手劍、弓、長杖、權杖…全部照等級混在一起；詞綴只分前綴、後綴，
 * 其餘照資料檔載入的順序。現在：
 * <ul>
 *   <li>基底：部位 → 種類（武器照固定的武器類型順序；防具、盾照屬性；副手是盾 → 法器 → 箭袋；珠寶照顏色，
 *       一般 → 遺時 → 永恆；藥劑生命在前）→ 需要等級 → 掉落等級 → 名稱 → 代號（一定分得出先後，排序穩定）。</li>
 *   <li>詞綴：前綴 → 後綴 → 最低一階要的物品等級 → 詞綴群組 → 文字 → 代號。</li>
 * </ul>
 * 純資料，不依賴玩家、選單狀態（網頁圖鑑也用）。
 */
public final class CodexOrder {

    /** 武器類型的順序（標籤）：單手在前、雙手在後；劍、斧、錘分單手、雙手；權杖也有 mace 標籤，先認權杖。 */
    private static final List<String> WEAPON_TYPES = List.of(
            // 單手
            "sword_1h", "axe_1h", "mace_1h", "dagger", "claw", "spear", "flail", "sceptre", "wand",
            // 雙手
            "sword_2h", "axe_2h", "mace_2h", "quarterstaff", "talisman", "staff", "bow", "crossbow");

    /** 防具、盾的屬性順序：力量、敏捷、智慧、力敏、力智、敏智。 */
    private static final List<String> ATTRIBUTES = List.of("str_only", "dex_only", "int_only", "str_dex_armour", "str_int_armour", "dex_int_armour");

    /** 基底的順序。 */
    public static final Comparator<ItemBase> BASES = Comparator.<ItemBase>comparingInt(base -> base.slot().ordinal())
            .thenComparingInt(CodexOrder::kindRank)
            .thenComparingInt(base -> base.requirements().level())
            .thenComparingInt(ItemBase::dropLevel)
            .thenComparing(ItemBase::name)
            .thenComparing(ItemBase::id);

    /** 詞綴的順序。 */
    public static final Comparator<Affix> AFFIXES = Comparator.<Affix>comparingInt(affix -> affix.type() == AffixType.PREFIX ? 0 : 1)
            .thenComparingInt(CodexOrder::minIlvl)
            .thenComparing(affix -> affix.group() == null ? "" : affix.group())
            .thenComparing(affix -> affix.tiers().isEmpty() ? "" : affix.rangeText(affix.tiers().getFirst().tier()))
            .thenComparing(Affix::id);

    private CodexOrder() {
    }

    /** 同一個部位裡的種類順序（越小越前面）。 */
    static int kindRank(final ItemBase base) {
        if (base.isWeapon()) {
            return weaponRank(base);
        }
        if (base.isJewel()) {
            return jewelRank(base);
        }
        if (base.isFlask()) {
            return base.hasTag("life_flask") ? 0 : base.hasTag("mana_flask") ? 1 : 2;
        }
        if (base.isQuiver()) {
            return 200;
        }
        if (base.hasTag("focus")) {
            return 100;
        }
        for (int i = 0; i < ATTRIBUTES.size(); i++) {
            if (base.hasTag(ATTRIBUTES.get(i))) {
                return i;
            }
        }
        return ATTRIBUTES.size();
    }

    /** 武器類型的位置；認不出來的排在最後。 */
    static int weaponRank(final ItemBase base) {
        final boolean twoHand = base.hasTag("two_hand");
        final String type;
        if (base.hasTag("sceptre")) {
            type = "sceptre";
        } else if (base.hasTag("sword") || base.hasTag("axe") || base.hasTag("mace")) {
            final String family = base.hasTag("sword") ? "sword" : base.hasTag("axe") ? "axe" : "mace";
            type = family + (twoHand ? "_2h" : "_1h");
        } else {
            String found = null;
            for (final String each : WEAPON_TYPES) {
                if (base.hasTag(each)) {
                    found = each;
                    break;
                }
            }
            type = found;
        }
        final int index = type == null ? -1 : WEAPON_TYPES.indexOf(type);
        return index < 0 ? WEAPON_TYPES.size() + (twoHand ? 1 : 0) : index;
    }

    /** 珠寶：赤、翠、蒼、白，一般 → 遺時（半徑）→ 永恆。 */
    static int jewelRank(final ItemBase base) {
        if (base.hasTag("timeless")) {
            return 20;
        }
        final int offset = base.isRadiusJewel() ? 10 : 0;
        final boolean ruby = base.hasTag("ruby") || base.hasTag("r_ruby");
        final boolean emerald = base.hasTag("emerald") || base.hasTag("r_emerald");
        final boolean sapphire = base.hasTag("sapphire") || base.hasTag("r_sapphire");
        final int colours = (ruby ? 1 : 0) + (emerald ? 1 : 0) + (sapphire ? 1 : 0);
        return offset + (colours != 1 ? 3 : ruby ? 0 : emerald ? 1 : 2);
    }

    private static int minIlvl(final Affix affix) {
        int min = Integer.MAX_VALUE;
        for (final AffixTier tier : affix.tiers()) {
            min = Math.min(min, tier.ilvl());
        }
        return min;
    }

    /**
     * 圖鑑裡珠寶、護符的圖示（2026-10-27）：這次之前直接用資料檔的原版物品，白星晶、遺時白星晶、永恆珠寶都是鑽石，
     * 遺時珠寶跟同色的一般珠寶一樣，霜鱗護符的青金石也跟蒼星晶一樣，分不出來。現在：
     * 一般珠寶照顏色（紅石、綠寶石、青金石、鑽石）；遺時珠寶（半徑）用同色的方塊（紅石磚、綠寶石磚、青金石磚、鑽石磚）；
     * 永恆珠寶用地獄之星；護符照資料檔，撞到珠寶的材質就換一個（青金石 → 海晶碎片）。
     * 只給圖鑑顯示用，不改真的物品；不是珠寶、護符回傳 null（照原本的圖示）。
     */
    public static Material icon(final ItemBase base) {
        if (base == null) {
            return null;
        }
        if (base.isJewel()) {
            if (base.hasTag("timeless")) {
                return Material.NETHER_STAR;
            }
            final int colour = jewelRank(base) % 10;
            if (base.isRadiusJewel()) {
                return switch (colour) {
                    case 0 -> Material.REDSTONE_BLOCK;
                    case 1 -> Material.EMERALD_BLOCK;
                    case 2 -> Material.LAPIS_BLOCK;
                    default -> Material.DIAMOND_BLOCK;
                };
            }
            return switch (colour) {
                case 0 -> Material.REDSTONE;
                case 1 -> Material.EMERALD;
                case 2 -> Material.LAPIS_LAZULI;
                default -> Material.DIAMOND;
            };
        }
        if (base.isCharm()) {
            final Material material = Menu.material(base.iconMaterial(), Material.AMETHYST_SHARD);
            return switch (material) {
                case REDSTONE, EMERALD, LAPIS_LAZULI, DIAMOND, POTION -> Material.PRISMARINE_SHARD;
                default -> material;
            };
        }
        return null;
    }
}
