package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Tags;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基底物品定義（設定檔 data/bases/*.yml）：
 * <pre>
 * short_sword:
 *   name: 短劍
 *   material: IRON_SWORD
 *   model: exilecore:weapon/short_sword     # 可選，資源包啟用時才寫進物品
 *   slot: MAIN_HAND
 *   tags: [weapon, one_hand, sword, melee]
 *   drop_level: 1
 *   requirements: { level: 1, str: 8, dex: 8 }
 *   damage: { physical: [6, 14] }
 *   aps: 1.5
 *   crit: 5.0
 *   sockets: 3
 *   drop_weight: 10                          # 可選，掉落時抽到這個基底的權重（預設 10；珠寶比較少見）
 * </pre>
 * 防具用 armour／evasion／es，盾另有 block。數值是「物品等級 1」的值，
 * 實際數值依物品等級縮放（見 {@link ItemStats}）。
 * 2026-10：寫了 {@code fixed: true} 的基底（照 PoE2 分階的那批）數值與屬性需求固定，不跟物品等級縮放（PoE2 的做法：
 * 高階的基底本身數值就高）。{@code family} 是同一類的分階基底（例如全部的單手錘）：掉落時先照 drop_weight 抽一類、
 * 再從那一類裡掉得出來的基底平均抽一個，所以分很多階也不會讓那一類變多。
 * <p>
 * 2026-10 第二版：分階改成「同一類只差數值大小的合併成一個」（tools/gen_poe2_bases.py 的 collapse）：留下來的寫
 * {@code base_level}（寫的數值是哪個物品等級的值，實際數值 = 寫的 × 物品等級的曲線 ÷ base_level 的曲線）、
 * {@code implicit_level}（固定詞綴在這個物品等級以下等比例變小），合併掉的寫 {@code retired} ＋ {@code merged_into}
 * （舊物品照常用原本的固定數值）。
 * <p>
 * 珠寶（slot: JEWEL，標籤 jewel 加上顏色 ruby／emerald／sapphire，白星晶四個都有）不能穿戴，放進天賦樹的珠寶孔；
 * 詞綴照顏色出（affixes/jewels.yml 的 spawn_tags）。
 */
public record ItemBase(String id, String name, String material, String model, ItemSlot slot, Set<String> tags,
                       int dropLevel, Requirements requirements, Map<DamageType, int[]> damage,
                       double aps, double crit, double armour, double evasion, double energyShield,
                       double block, int sockets, Implicit implicit, int dropWeight, boolean fixed, String family,
                       int baseLevel, int implicitLevel, String mergedInto) {

    /** 掉落權重的預設值。 */
    public static final int DEFAULT_DROP_WEIGHT = 10;
    /** 珠寶每種型態（前綴／後綴）最多幾條詞綴（稀有珠寶最多 4 條，PoE 的做法）。 */
    public static final int JEWEL_AFFIXES_PER_TYPE = 2;

    /** 裝備需求（物品等級 1 時）。 */
    public record Requirements(int level, int str, int dex, int intelligence) {
    }

    public ItemBase {
        tags = Set.copyOf(tags);
        damage = Map.copyOf(damage);
    }

    public static ItemBase parse(final String id, final YamlNode node) {
        final String name = node.requireString("name");
        final String material = node.requireString("material").trim().toUpperCase(Locale.ROOT);
        final String model = node.string("model", null);
        final ItemSlot slot = ItemSlot.parse(node.requireString("slot"));
        final Set<String> tags = new HashSet<>(node.strings("tags"));
        if (node.bool("two_handed", false)) {
            tags.add("two_hand");
        }
        final int dropLevel = node.integer("drop_level", 1);
        final YamlNode req = node.node("requirements");
        final Requirements requirements = new Requirements(
                req.integer("level", 1), req.integer("str", 0), req.integer("dex", 0), req.integer("int", 0));

        final Map<DamageType, int[]> damage = new EnumMap<>(DamageType.class);
        final YamlNode damageNode = node.node("damage");
        for (final String key : damageNode.keys()) {
            final DamageType type = DamageType.fromTag(key);
            if (type == null) {
                throw new IllegalArgumentException(damageNode.path() + "：不認得的傷害類型 " + key);
            }
            damage.put(type, damageNode.range(key));
        }
        final Implicit implicit = node.has("implicit") ? Implicit.parse(node.node("implicit")) : null;
        if (slot == ItemSlot.JEWEL) {
            tags.add("jewel");
        }
        return new ItemBase(id, name, material, model, slot, tags, dropLevel, requirements, damage,
                node.decimal("aps", 0), node.decimal("crit", 0),
                node.decimal("armour", 0), node.decimal("evasion", 0), node.decimal("es", 0),
                node.decimal("block", 0), node.integer("sockets", 0), implicit,
                Math.max(0, node.integer("drop_weight", DEFAULT_DROP_WEIGHT)), node.bool("fixed", false),
                node.string("family", id).trim(), Math.max(0, node.integer("base_level", 0)),
                Math.max(0, node.integer("implicit_level", 0)), node.string("merged_into", null));
    }

    /**
     * 選單圖示用的原版物品（2026-10）：基底寫了原版模型（minecraft:，例如長矛用鐵矛、長棍用竹子的樣子）就用那個，
     * 跟實際物品看起來一樣；其他用 material。
     */
    public String iconMaterial() {
        return model != null && model.startsWith("minecraft:") ? model.substring("minecraft:".length()).toUpperCase(Locale.ROOT) : material;
    }

    /** 數值（傷害、防禦、屬性需求）是哪個物品等級的值；沒寫（舊的基底）是 1。 */
    public int statLevel() {
        return Math.max(1, baseLevel);
    }

    /**
     * 物品等級 itemLevel 時的固定詞綴（2026-10）：implicit_level 以下照物品等級等比例變小（最多到寫的數值，不會更高）；
     * 數值很小的（上限 ≤ 3，例如彈匣多 1 發）不縮。沒有固定詞綴回傳 null。
     */
    public Implicit implicitAt(final int itemLevel, final double perLevel) {
        if (implicit == null || implicitLevel <= 1 || itemLevel >= implicitLevel) {
            return implicit;
        }
        final double factor = (1 + perLevel * (Math.max(1, itemLevel) - 1)) / (1 + perLevel * (implicitLevel - 1));
        return implicit.scaled(factor);
    }

    public boolean isWeapon() {
        return tags.contains("weapon");
    }

    public boolean isArmour() {
        return tags.contains("armour") || tags.contains("shield");
    }

    public boolean isJewellery() {
        return tags.contains("jewellery");
    }

    public boolean isShield() {
        return tags.contains("shield");
    }

    /** 箭袋（副手，只能配弓；2026-10 弩不行）。 */
    public boolean isQuiver() {
        return tags.contains("quiver");
    }

    public boolean hasTag(final String tag) {
        return tags.contains(tag);
    }

    /** 這把武器的普攻（物品說明、選武器的選單用）；不是武器、普通近戰的回傳 null。 */
    public String basicAttackHint() {
        if (!isWeapon()) {
            return null;
        }
        if (hasTag(Tags.BOW)) {
            return "遠程武器：普攻射出箭";
        }
        if (hasTag(Tags.CROSSBOW)) {
            return "遠程武器：普攻射出弩箭";
        }
        if (hasTag(Tags.RANGED)) {
            return "遠程武器：普攻射出魔法彈";
        }
        if (hasTag(Tags.QUARTERSTAFF)) {
            return "普攻橫掃：一次打到前方兩個敵人";
        }
        if (hasTag(Tags.SPEAR)) {
            return "普攻往前刺：打得比較遠";
        }
        return null;
    }

    /** 地圖（星痕）。 */
    public boolean isMap() {
        return slot == ItemSlot.MAP;
    }

    /** 珠寶（放進天賦樹的珠寶孔，不能穿戴）。 */
    public boolean isJewel() {
        return slot == ItemSlot.JEWEL;
    }

    /** 能穿戴、能裝在角色身上的裝備（武器、防具、盾、飾品）；地圖與珠寶不是。 */
    public boolean isWearable() {
        return !isMap() && !isJewel();
    }

    /** 珠寶的顏色（#rrggbb，介面用）：赤星晶紅、翠星晶綠、蒼星晶藍、其他（白星晶）白；不是珠寶回傳 null。 */
    public String jewelColor() {
        if (!isJewel()) {
            return null;
        }
        final boolean ruby = tags.contains("ruby");
        final boolean emerald = tags.contains("emerald");
        final boolean sapphire = tags.contains("sapphire");
        if (ruby && !emerald && !sapphire) {
            return "#ff5c6a";
        }
        if (emerald && !ruby && !sapphire) {
            return "#4fe08a";
        }
        if (sapphire && !ruby && !emerald) {
            return "#5f8dff";
        }
        return "#e8f6ff";
    }

    /**
     * 地圖在星圖上的圈數（標籤 map_t1 … map_t5，決定星圖的位置與終局的解鎖）；不是地圖回傳 0。
     * 地圖物品實際的階級（T1–T16）寫在物品上，舊的地圖沒寫時才用這個（見 {@link ItemStats#mapTier}）。
     */
    public int mapTier() {
        if (!isMap()) {
            return 0;
        }
        for (final String tag : tags) {
            if (tag.startsWith("map_t")) {
                try {
                    return Integer.parseInt(tag.substring(5));
                } catch (final NumberFormatException ignored) {
                    // 下一個
                }
            }
        }
        return 1;
    }
}
