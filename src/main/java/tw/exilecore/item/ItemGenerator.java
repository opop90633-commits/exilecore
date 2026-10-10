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

    /**
     * 生成一件新物品：固定詞綴擲值、依稀有度擲詞綴（掉落的魔法 1–2 條、稀有 4–6 條）。
     * 生成出來是已鑑定的（指令給的、通貨做出來的都能直接看）；掉落表會把魔法與稀有設成未鑑定。
     */
    public ItemData generate(final ItemBase base, final Rarity rarity, final int itemLevel, final RandomGenerator random) {
        final ItemData data = new ItemData(base.id(), Rarity.NORMAL, itemLevel);
        if (base.implicit() != null) {
            data.setImplicitVars(ItemStats.implicit(repository, base, data).roll(random)); // 2026-10：低物品等級的固定詞綴比較小
        }
        data.setSockets(rollSockets(base, itemLevel, random));
        switch (rarity) {
            case MAGIC -> upgradeToMagic(data, random, true);
            case RARE -> upgradeToRare(data, random, true);
            default -> data.setRarity(rarity);
        }
        return data;
    }

    /** 生成一件傳奇：固定的名字與詞綴，數值在範圍裡擲。 */
    public ItemData generateUnique(final UniqueItem unique, final int itemLevel, final RandomGenerator random) {
        final ItemBase base = repository.requireBase(unique.base());
        final ItemData data = new ItemData(base.id(), Rarity.UNIQUE, itemLevel);
        data.setUnique(unique.id());
        if (base.implicit() != null) {
            data.setImplicitVars(ItemStats.implicit(repository, base, data).roll(random)); // 傳奇照它那一階
        }
        data.setSockets(rollSockets(base, itemLevel, random));
        data.setName(unique.name());
        data.setUnique(unique.id());
        data.setUniqueVersion(unique.version());
        for (final String affixId : unique.affixIds()) {
            final Affix affix = repository.affix(affixId);
            if (affix != null) {
                data.affixes().add(new RolledAffix(affixId, 1, affix.tiers().getFirst().roll(random)));
            }
        }
        Veiled.addUniqueVeiled(data, repository, random);
        return data;
    }

    /**
     * 僥倖石成功：這件普通物品變成傳奇（還是同一件物品：UUID、物品等級、品質、固定詞綴、插槽都不變），
     * 名字與詞綴換成那件傳奇的（數值在範圍裡擲），而且是已鑑定的。
     */
    public void makeUnique(final ItemData data, final UniqueItem unique, final RandomGenerator random) {
        data.setRarity(Rarity.UNIQUE);
        data.setName(unique.name());
        data.setUnique(unique.id());
        data.setUniqueVersion(unique.version());
        data.affixes().clear();
        for (final String affixId : unique.affixIds()) {
            final Affix affix = repository.affix(affixId);
            if (affix != null) {
                data.affixes().add(new RolledAffix(affixId, 1, affix.tiers().getFirst().roll(random)));
            }
        }
        Veiled.addUniqueVeiled(data, repository, random);
        data.setIdentified(true);
    }

    /** 依權重從清單抽一件傳奇；清單是空的回傳 null。 */
    /**
     * 追逐級傳奇「小怪掉落」與「僥倖石賭到」共用的機率（2026-10）：maps.yml 的 drops.chase_mob_chance，
     * 地圖模組載入時設定；沒有地圖模組時照預設。
     */
    private static volatile double chaseChance = 0.000001;

    public static double chaseChance() {
        return chaseChance;
    }

    public static void setChaseChance(final double chance) {
        chaseChance = Math.max(0, Math.min(1, chance));
    }

    /** 這個基底、這個物品等級能出的追逐級傳奇（uniques.yml 的 chase: true，drop_level 要夠）。 */
    public List<UniqueItem> chaseUniques(final String base, final int itemLevel) {
        final List<UniqueItem> list = new java.util.ArrayList<>();
        for (final UniqueItem unique : repository().uniques()) {
            if (unique.chase() && unique.base().equals(base) && unique.dropLevel() <= itemLevel && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }

    /** 所有基底的追逐級傳奇（物品等級夠的；傳奇三換一用，2026-10）。 */
    public List<UniqueItem> chaseUniques(final int itemLevel) {
        final List<UniqueItem> list = new java.util.ArrayList<>();
        for (final UniqueItem unique : repository().uniques()) {
            if (unique.chase() && unique.dropLevel() <= itemLevel && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }

    public static UniqueItem pickUnique(final List<UniqueItem> candidates, final RandomGenerator random) {
        int total = 0;
        for (final UniqueItem unique : candidates) {
            total += unique.weight();
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (final UniqueItem unique : candidates) {
            pick -= unique.weight();
            if (pick < 0) {
                return unique;
            }
        }
        return candidates.getLast();
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

    /** 普通 → 魔法（通貨用，PoE2 的微光石）：固定 1 條詞綴。 */
    public void upgradeToMagic(final ItemData data, final RandomGenerator random) {
        upgradeToMagic(data, random, false);
    }

    /** 普通 → 魔法：1 條詞綴；{@code allowSecond} 時 50% 再一條另一種型態（掉落用）。 */
    public void upgradeToMagic(final ItemData data, final RandomGenerator random, final boolean allowSecond) {
        data.setRarity(Rarity.MAGIC);
        data.affixes().clear();
        final AffixType first = random.nextBoolean() ? AffixType.PREFIX : AffixType.SUFFIX;
        addAffix(data, first, random);
        if (allowSecond && random.nextBoolean()) {
            addAffix(data, first.other(), random);
        }
    }

    /** 普通 → 稀有（通貨用，PoE2 的星火石）：固定 4 條詞綴。 */
    public void upgradeToRare(final ItemData data, final RandomGenerator random) {
        upgradeToRare(data, random, false);
    }

    /**
     * 普通 → 稀有：4 條；{@code allowExtra} 時各 25% 機率加到 5 與 6（掉落用）。
     * 珠寶（PoE 的做法）：3 條，{@code allowExtra} 時 25% 機率 4 條（珠寶前綴、後綴各最多 2 條）。
     */
    public void upgradeToRare(final ItemData data, final RandomGenerator random, final boolean allowExtra) {
        data.setRarity(Rarity.RARE);
        data.affixes().clear();
        final ItemBase base = repository.base(data.base());
        final boolean jewel = base != null && base.isJewel();
        int count = jewel ? 3 : 4;
        if (allowExtra && random.nextDouble() < 0.25) {
            count++;
            if (!jewel && random.nextDouble() < 0.25) {
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

    /** 隨機移除一條詞綴（湮滅石；亂流石的前半）。沒有詞綴回傳 false；稀有度不變。 */
    public boolean removeRandomAffix(final ItemData data, final RandomGenerator random) {
        if (data.affixes().isEmpty()) {
            return false;
        }
        data.affixes().remove(random.nextInt(data.affixes().size()));
        return true;
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
            // 2026-10：詞綴照 PoE2 重做過，舊物品的階級代號可能已經不在了：照排名換成最接近的階級
            final AffixTier tier = affix.tiers().stream().filter(t -> t.tier() == rolled.tier()).findFirst()
                    .orElse(affix.tiers().get(Math.min(affix.tiers().size(), Math.max(1, rolled.tier())) - 1));
            rolled.setVars(tier.roll(random));
        }
    }

    /** 重擲固定詞綴的數值。 */
    public boolean rerollImplicit(final ItemData data, final RandomGenerator random) {
        final ItemBase base = repository.base(data.base());
        if (base == null || base.implicit() == null || base.implicit().vars().isEmpty()) {
            return false;
        }
        data.setImplicitVars(ItemStats.implicit(repository, base, data).roll(random));
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
        if (type == null) {
            return canAddAffix(data, AffixType.PREFIX) || canAddAffix(data, AffixType.SUFFIX);
        }
        return countOf(data, type) < maxAffixes(data, type) && !eligible(data, type).isEmpty();
    }

    /** 這件物品這種型態最多幾條詞綴：照稀有度，珠寶另外最多 {@value ItemBase#JEWEL_AFFIXES_PER_TYPE} 條。 */
    public int maxAffixes(final ItemData data, final AffixType type) {
        final Rarity rarity = data.rarity();
        final int max = type == AffixType.PREFIX ? rarity.maxPrefixes() : rarity.maxSuffixes();
        final ItemBase base = repository.base(data.base());
        if (base != null && base.isFlaskLike()) {
            return Math.min(max, 1); // 藥劑、護符最多魔法：一前綴一後綴
        }
        return base != null && base.isJewel() ? Math.min(max, ItemBase.JEWEL_AFFIXES_PER_TYPE) : max;
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
            if (Veiled.isUnveiled(affix.id())) {
                continue; // 揭露詞綴只能靠揭露（盜賊首領）或揭露石，一般的掉落與通貨擲不到
            }
            if (affix.tiersFor(data.ilvl()).isEmpty()) {
                continue;
            }
            list.add(affix);
        }
        return list;
    }

    // ---- 管理員測試 ----

    /**
     * 管理員測試用（/ec affix）：把指定的詞綴（與階級）加到物品上並鑑定。同群組的舊詞綴先換掉；
     * 普通的變魔法、魔法放不下變稀有（沒名字就取一個）；稀有（珠寶每種 2 條）放滿了就回傳原因。
     * 一樣只能加在這條詞綴會出現的基底上。
     *
     * @param tierCode 階級代號（null = 這條詞綴最強的一階）
     * @return 做不到的原因；成功回傳 null
     */
    public String forceAffix(final ItemData data, final String affixId, final Integer tierCode, final RandomGenerator random) {
        final ItemBase base = repository.base(data.base());
        if (base == null) {
            return "找不到基底 " + data.base();
        }
        if (data.rarity() == Rarity.UNIQUE) {
            return "傳奇不能加詞綴";
        }
        final Affix affix = repository.affix(affixId);
        if (affix == null || affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
            return "沒有這條詞綴：" + affixId;
        }
        if (!affix.canSpawnOn(base)) {
            return affix.id() + " 不會出在「" + base.name() + "」上（它出在標籤 " + String.join("、", new java.util.TreeSet<>(affix.spawnTags()))
                    + (affix.excludeTags().isEmpty() ? "" : "，但不出在 " + String.join("、", new java.util.TreeSet<>(affix.excludeTags()))) + "）";
        }
        AffixTier tier = affix.tiers().getFirst();
        if (tierCode != null) {
            tier = null;
            final List<String> codes = new ArrayList<>();
            for (final AffixTier candidate : affix.tiers()) {
                codes.add(String.valueOf(candidate.tier()));
                if (candidate.tier() == tierCode) {
                    tier = candidate;
                }
            }
            if (tier == null) {
                return affix.id() + " 沒有階級代號 " + tierCode + "（有：" + String.join("、", codes) + "；越小越強）";
            }
        }
        data.affixes().removeIf(rolled -> {
            final Affix other = repository.affix(rolled.id());
            return other != null && other.group().equals(affix.group());
        });
        int same = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix other = repository.affix(rolled.id());
            if (other != null && other.type() == affix.type()) {
                same++;
            }
        }
        final int jewelCap = base.isJewel() ? ItemBase.JEWEL_AFFIXES_PER_TYPE : Integer.MAX_VALUE;
        Rarity rarity = data.rarity();
        while (same >= Math.min(affix.type() == AffixType.PREFIX ? rarity.maxPrefixes() : rarity.maxSuffixes(), jewelCap)) {
            if (rarity == Rarity.NORMAL) {
                rarity = Rarity.MAGIC;
            } else if (rarity == Rarity.MAGIC) {
                rarity = Rarity.RARE;
            } else {
                final int cap = Math.min(affix.type() == AffixType.PREFIX ? rarity.maxPrefixes() : rarity.maxSuffixes(), jewelCap);
                return (affix.type() == AffixType.PREFIX ? "前綴" : "後綴") + "已經滿了（" + same + "/" + cap + "）";
            }
        }
        if (rarity != data.rarity()) {
            data.setRarity(rarity);
            if (rarity == Rarity.RARE) {
                ensureRareName(data, random);
            }
        }
        data.affixes().add(new RolledAffix(affix.id(), tier.tier(), tier.roll(random)));
        data.setIdentified(true);
        return null;
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
