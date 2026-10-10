package tw.exilecore.item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 隱匿詞綴與揭露（2026-10，PoE 的 Veiled／Unveil）。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>隱匿詞綴：劫盜掉的裝備上，佔一個前綴或後綴的位子、沒有效果（affixes/veiled.yml 的 {@value #VEILED_PREFIX}、
 *       {@value #VEILED_SUFFIX}，spawn_tags 寫 unique_only 所以一般不會擲到）。</li>
 *   <li>揭露：盜賊首領把一條隱匿詞綴換成「揭露詞綴」——affixes/unveiled.yml 裡代號以 {@value #UNVEILED} 開頭的詞綴，
 *       一般的掉落、通貨都擲不到（{@link ItemGenerator#eligible} 跳過），只能靠揭露或揭露石。三個選項選一個。</li>
 *   <li>揭露石：在有空位的魔法／稀有裝備上加一條隱匿詞綴（再找盜賊首領揭露）。</li>
 *   <li>一件裝備同時只能有一條隱匿或揭露詞綴（2026-10）。</li>
 * </ul>
 */
public final class Veiled {

    public static final String VEILED_PREFIX = "veiled_prefix";
    public static final String VEILED_SUFFIX = "veiled_suffix";
    /** 揭露詞綴的代號開頭。 */
    public static final String UNVEILED = "unveiled_";
    /** 揭露時給幾個選項。 */
    public static final int CHOICES = 3;

    private Veiled() {
    }

    public static boolean isVeiled(final String affixId) {
        return VEILED_PREFIX.equals(affixId) || VEILED_SUFFIX.equals(affixId);
    }

    public static boolean isUnveiled(final String affixId) {
        return affixId != null && affixId.startsWith(UNVEILED);
    }

    /** 物品上有沒有隱匿或揭露詞綴（一件只能有一條）。 */
    public static boolean hasSpecial(final ItemData data) {
        for (final RolledAffix rolled : data.affixes()) {
            if (isVeiled(rolled.id()) || isUnveiled(rolled.id())) {
                return true;
            }
        }
        return false;
    }

    /** 隱匿傳奇（uniques.yml 寫 veiled 的）：補上那條隱匿詞綴（已經有隱匿或揭露詞綴的不加）。 */
    public static void addUniqueVeiled(final ItemData data, final ItemRepository repository, final RandomGenerator random) {
        final List<AffixType> types = repository.uniqueVeiled(data.unique());
        if (types.isEmpty() || hasSpecial(data)) {
            return;
        }
        for (final AffixType type : types) {
            final String id = type == AffixType.PREFIX ? VEILED_PREFIX : VEILED_SUFFIX;
            final Affix veiled = repository.affix(id);
            if (veiled != null) {
                final AffixTier tier = veiled.tiers().getFirst();
                data.affixes().add(new RolledAffix(id, tier.tier(), tier.roll(random)));
            }
        }
    }

    /** 這條是不是隱匿或揭露詞綴。 */
    public static boolean isSpecial(final String affixId) {
        return isVeiled(affixId) || isUnveiled(affixId);
    }

    /** 物品上的隱匿詞綴（照順序）。 */
    public static List<RolledAffix> veiledOn(final ItemData data) {
        final List<RolledAffix> list = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (isVeiled(rolled.id())) {
                list.add(rolled);
            }
        }
        return list;
    }

    /**
     * 加一條隱匿詞綴（劫盜的掉落）：那一種（前綴／後綴）有空位就加；滿了就先隨機拿掉一條同一種的再加。
     *
     * @return 有沒有加上去（找不到隱匿詞綴的定義、不是魔法／稀有、珠寶地圖這些不加）
     */
    public static boolean addVeiled(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
        return addVeiled(data, generator, random, true);
    }

    /**
     * 同上；replaceWhenFull = false 時只加在有空位的那一種（揭露石：不拿掉原本的詞綴），都沒空位回傳 false。
     * 已經有隱匿或揭露詞綴的不加（一件只能有一條）。
     */
    public static boolean addVeiled(final ItemData data, final ItemGenerator generator, final RandomGenerator random,
                                    final boolean replaceWhenFull) {
        final ItemBase base = generator.repository().base(data.base());
        if (base == null || base.isMap() || base.isJewel() || (data.rarity() != Rarity.MAGIC && data.rarity() != Rarity.RARE)
                || hasSpecial(data)) {
            return false;
        }
        AffixType type = random.nextBoolean() ? AffixType.PREFIX : AffixType.SUFFIX;
        if (!replaceWhenFull && !generator.canAddAffix(data, type)) {
            type = type == AffixType.PREFIX ? AffixType.SUFFIX : AffixType.PREFIX;
            if (!generator.canAddAffix(data, type)) {
                return false;
            }
        }
        final String id = type == AffixType.PREFIX ? VEILED_PREFIX : VEILED_SUFFIX;
        final Affix veiled = generator.repository().affix(id);
        if (veiled == null) {
            return false;
        }
        if (!generator.canAddAffix(data, type)) {
            final List<RolledAffix> same = new ArrayList<>();
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = generator.repository().affix(rolled.id());
                if (affix != null && affix.type() == type && !isVeiled(rolled.id())) {
                    same.add(rolled);
                }
            }
            if (same.isEmpty()) {
                return false;
            }
            data.affixes().remove(same.get(random.nextInt(same.size())));
        }
        final AffixTier tier = veiled.tiers().getFirst();
        data.affixes().add(new RolledAffix(id, tier.tier(), tier.roll(random)));
        return true;
    }

    /**
     * 揭露的選項：跟這條隱匿詞綴同一種（前綴／後綴）、出得了這個基底、群組沒被其他詞綴佔、物品等級夠的揭露詞綴，
     * 依權重不重複地挑最多 count 個，每個都先擲好階級與數值（選了就是這個結果）。
     *
     * @param replacing 要換掉的那條（null = 新加一條，群組照全部詞綴算）
     */
    public static List<RolledAffix> options(final ItemData data, final AffixType type, final RolledAffix replacing,
                                            final ItemGenerator generator, final RandomGenerator random, final int count) {
        final ItemBase base = generator.repository().base(data.base());
        if (base == null) {
            return List.of();
        }
        final Set<String> usedGroups = new HashSet<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (rolled == replacing) {
                continue;
            }
            final Affix affix = generator.repository().affix(rolled.id());
            if (affix != null) {
                usedGroups.add(affix.group());
            }
        }
        final List<Affix> pool = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();
        for (final Affix affix : generator.repository().affixes()) {
            if (!isUnveiled(affix.id()) || affix.type() != type || usedGroups.contains(affix.group()) || !affix.canSpawnOn(base)) {
                continue;
            }
            int weight = 0;
            for (final AffixTier tier : affix.tiersFor(data.ilvl())) {
                weight += tier.weight();
            }
            if (weight > 0) {
                pool.add(affix);
                weights.add(weight);
            }
        }
        final List<RolledAffix> chosen = new ArrayList<>();
        while (chosen.size() < count && !pool.isEmpty()) {
            int total = 0;
            for (final int weight : weights) {
                total += weight;
            }
            int pick = random.nextInt(total);
            int index = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                pick -= weights.get(i);
                if (pick < 0) {
                    index = i;
                    break;
                }
            }
            final Affix affix = pool.remove(index);
            weights.remove(index);
            chosen.add(roll(affix, data.ilvl(), random));
        }
        return chosen;
    }

    /** 依權重擲一個物品等級允許的階級與數值。 */
    static RolledAffix roll(final Affix affix, final int itemLevel, final RandomGenerator random) {
        final List<AffixTier> tiers = affix.tiersFor(itemLevel);
        int total = 0;
        for (final AffixTier tier : tiers) {
            total += tier.weight();
        }
        AffixTier chosen = tiers.getLast();
        int pick = random.nextInt(Math.max(1, total));
        for (final AffixTier tier : tiers) {
            pick -= tier.weight();
            if (pick < 0) {
                chosen = tier;
                break;
            }
        }
        return new RolledAffix(affix.id(), chosen.tier(), chosen.roll(random));
    }

    /** 把 veiled 換成 chosen（揭露）；veiled 不在物品上時回傳 false。 */
    public static boolean replace(final ItemData data, final RolledAffix veiled, final RolledAffix chosen) {
        final int index = data.affixes().indexOf(veiled);
        if (index < 0) {
            return false;
        }
        data.affixes().set(index, chosen.copy());
        return true;
    }

    /** 這條隱匿詞綴是前綴還是後綴。 */
    public static AffixType typeOf(final RolledAffix veiled) {
        return VEILED_PREFIX.equals(veiled.id()) ? AffixType.PREFIX : AffixType.SUFFIX;
    }

    /** 揭露石能不能用：已鑑定的魔法／稀有裝備（不是地圖、珠寶）、還沒有隱匿或揭露詞綴、前綴或後綴有空位。 */
    public static boolean canUnveil(final ItemData data, final ItemGenerator generator) {
        if (!data.identified() || (data.rarity() != Rarity.MAGIC && data.rarity() != Rarity.RARE) || hasSpecial(data)) {
            return false;
        }
        final ItemBase base = generator.repository().base(data.base());
        if (base == null || base.isMap() || base.isJewel() || generator.repository().affix(VEILED_PREFIX) == null) {
            return false;
        }
        return generator.canAddAffix(data, AffixType.PREFIX) || generator.canAddAffix(data, AffixType.SUFFIX);
    }

    /** 揭露石：加一條隱匿詞綴（有空位的那一種；兩種都有空位隨機），之後找盜賊首領揭露。 */
    public static boolean unveilRandom(final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
        return addVeiled(data, generator, random, false);
    }
}
