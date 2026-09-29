package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

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
 * </pre>
 * 防具用 armour／evasion／es，盾另有 block。數值是「物品等級 1」的值，
 * 實際數值依物品等級縮放（見 {@link ItemStats}）。
 */
public record ItemBase(String id, String name, String material, String model, ItemSlot slot, Set<String> tags,
                       int dropLevel, Requirements requirements, Map<DamageType, int[]> damage,
                       double aps, double crit, double armour, double evasion, double energyShield,
                       double block, int sockets, Implicit implicit) {

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
        return new ItemBase(id, name, material, model, slot, tags, dropLevel, requirements, damage,
                node.decimal("aps", 0), node.decimal("crit", 0),
                node.decimal("armour", 0), node.decimal("evasion", 0), node.decimal("es", 0),
                node.decimal("block", 0), node.integer("sockets", 0), implicit);
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

    public boolean hasTag(final String tag) {
        return tags.contains(tag);
    }
}
