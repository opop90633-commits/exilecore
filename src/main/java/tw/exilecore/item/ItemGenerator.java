package tw.exilecore.item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 物品生成與詞綴擲骰（設計文件「物品系統」）：
 * 先決定稀有度與詞綴數量 → 每條詞綴從「標籤符合、物品等級夠、群組未被佔用」的池子依權重抽 →
 * 再依權重抽階級 → 在範圍內擲值。通貨的每個操作都是這裡幾個方法的組合。
 * 純邏輯，不依賴 Bukkit。
 */
public final class ItemGenerator {

    private final ItemRepository repository;

    public ItemGenerator(final ItemRepository repository) {
        this.repository = repository;
    }

    public ItemRepository repository() {
        return repository;
    }

    /** 生成一件新物品：固定詞綴擲值、依稀有度擲詞綴、魔法與稀有預設未鑑定。 */
    public ItemData generate(final ItemBase base, final Rarity rarity, final int itemLevel, final RandomGenerator random) {
        final ItemData data = new ItemData(base.id(), Rarity.NORMAL, itemLevel);
        if (base.implicit() != null) {
            data.setImplicitVars(base.implicit().roll(random));
        }
        data.setSockets(rollSockets(base, itemLevel, random));
        switch (rarity) {
            case MAGIC -> upgradeToMagic(data, random);
            case RARE -> upgradeToRare(data, random);
            default -> data.setRarity(rarity);
        }
        if (rarity == Rarity.MAGIC || rarity == Rarity.RARE) {
            data.setIdentified(false);
        }
        return data;
    }

    /** 插槽數：高等級才可能滿槽。 */
    public int rollSockets(final ItemBase base, final int itemLevel, final RandomGenerator random) {
        final int max = base.sockets();
        if (max <= 0) {
            return 0;
        }
        // 每個插槽的門檻等級：第 n 槽需要大約 (n-1)*12 級
        int allowed = 1;
        for (int n = 2; n <= max; n++) {
            if (itemLevel >= (n - 1) * 12) {
                allowed = n;
            }
        }
        // 在 1..allowed 之間偏向少的分布
        int sockets = 1;
        for (int n = 2; n <= allowed; n++) {
            if (random.nextDouble() < 0.55) {
                sockets = n;
            } else {
                break;
            }
        }
        return sockets;
    }

    // ---- 稀有度變換（通貨操作用） ----

    /** 普通 → 魔法：1 條詞綴，50% 再一條另一種型態。 */
    public void upgradeToMagic(final ItemData data, final RandomGenerator random) {
        data.setRarity(Rarity.MAGIC);
        data.affixes().clear();
        final AffixType first = random.nextBoolean() ? AffixType.PREFIX : AffixType.SUFFIX;
        addAffix(data, first, random);
        if (random.nextBoolean()) {
            addAffix(data, first.other(), random);
        }
    }

    /** 普通 → 稀有：4 條起，各 25% 機率加到 5 與 6。 */
    public void upgradeToRare(final ItemData data, final RandomGenerator random) {
        data.setRarity(Rarity.RARE);
        data.affixes().clear();
        int count = 4;
        if (random.nextDouble() < 0.25) {
            count++;
            if (random.nextDouble() < 0.25) {
                count++;
            }
        }
        for (int i = 0; i < count; i++) {
            if (!addAffix(data, null, random)) {
                break;
            }
        }
        ensureRareName(data, random);
    }

    /** 魔法 → 稀有：保留原詞綴，再加一條。 */
    public void magicToRare(final ItemData data, final RandomGenerator random) {
        data.setRarity(Rarity.RARE);
        addAffix(data, null, random);
        ensureRareName(data, random);
    }

    /** 移除所有詞綴，變回普通（固定詞綴與品質保留）。 */
    public void scour(final ItemData data) {
        data.affixes().clear();
        data.setRarity(Rarity.NORMAL);
        data.setName(null);
    }

    /** 重擲每條詞綴的數值，階級不變。 */
    public void rerollValues(final ItemData data, final RandomGenerator random) {
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            rolled.setVars(affix.tier(rolled.tier()).roll(random));
        }
    }

    /** 重擲固定詞綴的數值。 */
    public boolean rerollImplicit(final ItemData data, final RandomGenerator random) {
        final ItemBase base = repository.base(data.base());
        if (base == null || base.implicit() == null || base.implicit().vars().isEmpty()) {
            return false;
        }
        data.setImplicitVars(base.implicit().roll(random));
        return true;
    }

    // ---- 詞綴 ----

    public int countOf(final ItemData data, final AffixType type) {
        int count = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix != null && affix.type() == type) {
                count++;
            }
        }
        return count;
    }

    /** 這件物品還能不能加一條指定型態（null = 任一型態）的詞綴。 */
    public boolean canAddAffix(final ItemData data, final AffixType type) {
        final Rarity rarity = data.rarity();
        if (type == null) {
            return canAddAffix(data, AffixType.PREFIX) || canAddAffix(data, AffixType.SUFFIX);
        }
        final int max = type == AffixType.PREFIX ? rarity.maxPrefixes() : rarity.maxSuffixes();
        return countOf(data, type) < max && !eligible(data, type).isEmpty();
    }

    /**
     * 加一條詞綴。{@code type} 為 null 時，在還有空位的型態裡隨機選。
     *
     * @return 有沒有真的加上（沒空位或池子是空的就是 false）
     */
    public boolean addAffix(final ItemData data, final AffixType type, final RandomGenerator random) {
        AffixType chosen = type;
        if (chosen == null) {
            final boolean prefixOk = canAddAffix(data, AffixType.PREFIX);
            final boolean suffixOk = canAddAffix(data, AffixType.SUFFIX);
            if (!prefixOk && !suffixOk) {
                return false;
            }
            chosen = prefixOk && suffixOk ? (random.nextBoolean() ? AffixType.PREFIX : AffixType.SUFFIX)
                    : prefixOk ? AffixType.PREFIX : AffixType.SUFFIX;
        } else if (!canAddAffix(data, chosen)) {
            return false;
        }

        final List<Affix> pool = eligible(data, chosen);
        // 第一段：依「所有可用階級的權重總和」抽詞綴
        int totalWeight = 0;
        final int[] weights = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            int sum = 0;
            for (final AffixTier tier : pool.get(i).tiersFor(data.ilvl())) {
                sum += tier.weight();
            }
            weights[i] = sum;
            totalWeight += sum;
        }
        if (totalWeight <= 0) {
            return false;
        }
        int pick = random.nextInt(totalWeight);
        Affix affix = pool.getLast();
        for (int i = 0; i < pool.size(); i++) {
            pick -= weights[i];
            if (pick < 0) {
                affix = pool.get(i);
                break;
            }
        }
        // 第二段：依權重抽階級
        final List<AffixTier> tiers = affix.tiersFor(data.ilvl());
        int tierTotal = 0;
        for (final AffixTier tier : tiers) {
            tierTotal += tier.weight();
        }
        int tierPick = random.nextInt(tierTotal);
        AffixTier chosenTier = tiers.getLast();
        for (final AffixTier tier : tiers) {
            tierPick -= tier.weight();
            if (tierPick < 0) {
                chosenTier = tier;
                break;
            }
        }
        data.affixes().add(new RolledAffix(affix.id(), chosenTier.tier(), chosenTier.roll(random)));
        return true;
    }

    /** 這件物品現在可以抽到的詞綴：型態相符、基底標籤相符、群組未占用、等級夠。 */
    public List<Affix> eligible(final ItemData data, final AffixType type) {
        final ItemBase base = repository.requireBase(data.base());
        final Set<String> usedGroups = new HashSet<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix != null) {
                usedGroups.add(affix.group());
            }
        }
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : repository.affixes()) {
            if (affix.type() != type || usedGroups.contains(affix.group()) || !affix.canSpawnOn(base)) {
                continue;
            }
            if (affix.tiersFor(data.ilvl()).isEmpty()) {
                continue;
            }
            list.add(affix);
        }
        return list;
    }

    // ---- 名字 ----

    /** 稀有物品沒名字就取一個：隨機前綴 + 依分類的後綴。 */
    public void ensureRareName(final ItemData data, final RandomGenerator random) {
        if (data.name() != null && !data.name().isBlank()) {
            return;
        }
        final ItemBase base = repository.requireBase(data.base());
        final List<String> prefixes = repository.rareNamePrefixes();
        final List<String> suffixes = repository.rareNameSuffixes(base);
        final String prefix = prefixes.isEmpty() ? "無名" : prefixes.get(random.nextInt(prefixes.size()));
        final String suffix = suffixes.isEmpty() ? "" : suffixes.get(random.nextInt(suffixes.size()));
        data.setName(prefix + suffix);
    }
}
