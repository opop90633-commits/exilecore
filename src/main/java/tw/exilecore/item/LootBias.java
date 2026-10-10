package tw.exilecore.item;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 照職業調整掉落基底的權重（2026-10）：自己職業的基底類（family，例如武僧的長棍、閃避＋能量護盾的防具）乘 match，
 * 屬性被職業包含的防具類（武僧的純閃避、純能量護盾）乘 partial，其他 ×1。
 * 只在同一個部位裡重新分配（{@link ItemRepository} 先照原本的比例抽部位），所以頭、身、武器……各部位掉的比例不變；
 * 傳奇不吃這個（掉落時先用沒調過的權重擲傳奇）。純邏輯（不碰 Bukkit）。
 *
 * @param families   職業的基底類
 * @param attributes 職業防具的屬性（str、dex、int）
 */
public record LootBias(Set<String> families, Set<String> attributes, double match, double partial) {

    public static final LootBias NONE = new LootBias(Set.of(), Set.of(), 1, 1);

    public LootBias {
        families = Set.copyOf(families);
        attributes = Set.copyOf(attributes);
        match = Math.max(0, match);
        partial = Math.max(0, partial);
    }

    /** 沒有任何調整。 */
    public boolean none() {
        return (families.isEmpty() || match == 1) && (attributes.isEmpty() || partial == 1);
    }

    /** 這一類（用其中一個基底代表）的權重倍率。 */
    public double factor(final ItemBase sample) {
        if (sample.family() != null && families.contains(sample.family())) {
            return match;
        }
        if (sample.isArmour() || sample.isShield()) {
            final Set<String> own = armourAttributes(sample);
            if (!own.isEmpty() && attributes.containsAll(own)) {
                return partial;
            }
        }
        return 1;
    }

    /** 防具、盾的屬性（標籤 str_armour、dex_armour、int_armour）。 */
    public static Set<String> armourAttributes(final ItemBase base) {
        final Set<String> attributes = new HashSet<>();
        for (final String attribute : new String[] {"str", "dex", "int"}) {
            if (base.hasTag(attribute + "_armour")) {
                attributes.add(attribute);
            }
        }
        return attributes;
    }

    /**
     * 用職業的新手裝備（推薦武器、防具、盾）算出來：那些基底的類；拿弓的多算箭袋；防具的屬性。
     */
    public static LootBias fromStarter(final ItemRepository repository, final Collection<String> weapons, final Collection<String> armour,
                                       final String shield, final double match, final double partial) {
        final Set<String> families = new HashSet<>();
        final Set<String> attributes = new HashSet<>();
        for (final String id : weapons) {
            final ItemBase base = repository.base(id);
            if (base != null && base.family() != null) {
                families.add(base.family());
                if (base.hasTag("bow")) {
                    families.add("quiver");
                }
            }
        }
        for (final String id : armour) {
            final ItemBase base = repository.base(id);
            if (base != null) {
                if (base.family() != null) {
                    families.add(base.family());
                }
                attributes.addAll(armourAttributes(base));
            }
        }
        if (shield != null) {
            final ItemBase base = repository.base(shield);
            if (base != null && base.family() != null) {
                families.add(base.family());
            }
        }
        return new LootBias(families, attributes, match, partial);
    }
}
