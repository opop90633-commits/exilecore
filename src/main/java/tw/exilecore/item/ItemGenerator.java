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
    /** 2026-10-22 效能：基底＋型態 → 本來就擲得到的詞綴（{@link #pool}）。 */
    private final java.util.Map<String, List<Affix>> pools = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int poolVersion = Integer.MIN_VALUE;

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

    /** 映星鏡：一般怪物每隻的掉落機率（2026-10，maps.yml 的 drops.mirror_mob_chance；地圖模組載入時設定）。 */
    private static volatile double mirrorChance = 0.0000001;

    public static double mirrorChance() {
        return mirrorChance;
    }

    public static void setMirrorChance(final double chance) {
        mirrorChance = Math.max(0, Math.min(1, chance));
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
        // 2026-10-21：破裂的詞綴（定星石）移除不掉
        final List<RolledAffix> removable = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (!data.isFractured(rolled)) {
                removable.add(rolled);
            }
        }
        if (removable.isEmpty()) {
            return false;
        }
        data.affixes().remove(removable.get(random.nextInt(removable.size())));
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

    /**
     * 蝕變石（2026-10-23，照 PoE2）：隨機 1～3 條詞綴換成新的詞綴（前綴換前綴、後綴換後綴，不管上限）。
     * 破裂、隱匿、腐化詞綴不動；換不出新的就留著原本那條。回傳換了幾條。
     */
    public int replaceRandomAffixes(final ItemData data, final RandomGenerator random) {
        final List<RolledAffix> candidates = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (!data.isFractured(rolled) && !isCorruptionEnchant(rolled.id()) && !Veiled.isVeiled(rolled.id())
                    && repository.affix(rolled.id()) != null) {
                candidates.add(rolled);
            }
        }
        final int count = Math.min(candidates.size(), 1 + random.nextInt(3));
        int replaced = 0;
        for (int i = 0; i < count; i++) {
            final RolledAffix pick = candidates.remove(random.nextInt(candidates.size()));
            final int index = data.affixes().indexOf(pick);
            data.affixes().remove(index);
            if (addAffix(data, repository.affix(pick.id()).type(), random, true)) {
                replaced++;
            } else {
                data.affixes().add(index, pick);
            }
        }
        return replaced;
    }

    /**
     * 蝕變石對傳奇（2026-10-23，照 PoE2）：先在原本範圍內重骰數值，每條再乘 0.78～1.22（0.01 一級），可以超出原本範圍。
     * 寫死不能縮放的（0、開關）乘了還是一樣。
     */
    public void corruptUniqueValues(final ItemData data, final RandomGenerator random) {
        rerollValues(data, random);
        for (final RolledAffix rolled : data.affixes()) {
            if (isCorruptionEnchant(rolled.id())) {
                continue;
            }
            final double factor = (78 + random.nextInt(45)) / 100.0;
            final java.util.Map<String, Integer> scaled = new java.util.LinkedHashMap<>();
            rolled.vars().forEach((key, value) -> scaled.put(key, (int) Math.round(value * factor)));
            rolled.setVars(scaled);
        }
    }

    /** 重擲每條詞綴的數值，階級不變。 */
    public void rerollValues(final ItemData data, final RandomGenerator random) {
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null || data.isFractured(rolled)) { // 2026-10-21：破裂的詞綴數值也不變
                continue;
            }
            // 2026-10：詞綴照 PoE2 重做過，舊物品的階級代號可能已經不在了：照排名換成最接近的階級
            final AffixTier tier = affix.tiers().stream().filter(t -> t.tier() == rolled.tier()).findFirst()
                    .orElse(affix.tiers().get(Math.min(affix.tiers().size(), Math.max(1, rolled.tier())) - 1));
            rolled.setVars(tier.roll(random));
        }
    }

    /**
     * 蝕變石的「腐化詞綴」（2026-10-21，affixes/corrupted.yml，照 PoE2 的腐化附魔）：spawn_tags 寫 corruption 加上出在哪些裝備
     * （任一個標籤符合就行，跟一般詞綴一樣）、require_tags 全部都要有、exclude_tags 不能有。
     * 隨機挑一條這件裝備能有的加上去（照權重）；已經有一條就換掉（一件只會有一條）。沒有能加的回傳 false。
     */
    public boolean addCorruptionEnchant(final ItemData data, final RandomGenerator random) {
        final ItemBase base = repository.base(data.base());
        if (base == null) {
            return false;
        }
        final List<Affix> pool = new ArrayList<>();
        int total = 0;
        for (final Affix affix : repository.affixes()) {
            if (isCorruptionEnchant(affix.id()) && corruptionFits(affix, base) && !affix.tiers().isEmpty()) {
                pool.add(affix);
                total += Math.max(1, affix.tiers().getFirst().weight());
            }
        }
        if (pool.isEmpty()) {
            return false;
        }
        int pick = random.nextInt(total);
        Affix chosen = pool.getLast();
        for (final Affix affix : pool) {
            pick -= Math.max(1, affix.tiers().getFirst().weight());
            if (pick < 0) {
                chosen = affix;
                break;
            }
        }
        data.affixes().removeIf(rolled -> isCorruptionEnchant(rolled.id()));
        data.affixes().add(new RolledAffix(chosen.id(), chosen.tiers().getFirst().tier(), chosen.tiers().getFirst().roll(random)));
        return true;
    }

    /** 這條是不是蝕變石加的腐化詞綴。 */
    public boolean isCorruptionEnchant(final String affixId) {
        final Affix affix = repository.affix(affixId);
        return affix != null && affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG);
    }

    /** 腐化詞綴出不出在這個基底上：spawn_tags（corruption 以外）任一個符合、require_tags 全有、exclude_tags 都沒有。 */
    public static boolean corruptionFits(final Affix affix, final ItemBase base) {
        if (!base.tags().containsAll(affix.requireTags())) {
            return false;
        }
        for (final String tag : base.tags()) {
            if (affix.excludeTags().contains(tag)) {
                return false;
            }
        }
        for (final String tag : affix.spawnTags()) {
            if (!tag.equals(ItemRepository.CORRUPTION_TAG) && base.tags().contains(tag)) {
                return true;
            }
        }
        return false;
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
        return addAffix(data, type, random, false);
    }

    /** 同上；ignoreLimit = 不管前綴、後綴的上限（2026-10-21：蝕變石腐化珠寶時「多一條詞綴」，照 PoE2 可以超過上限）。 */
    public boolean addAffix(final ItemData data, final AffixType type, final RandomGenerator random, final boolean ignoreLimit) {
        AffixType chosen = type;
        if (chosen == null) {
            final boolean prefixOk = ignoreLimit || canAddAffix(data, AffixType.PREFIX);
            final boolean suffixOk = ignoreLimit || canAddAffix(data, AffixType.SUFFIX);
            if (!prefixOk && !suffixOk) {
                return false;
            }
            chosen = prefixOk && suffixOk ? (random.nextBoolean() ? AffixType.PREFIX : AffixType.SUFFIX)
                    : prefixOk ? AffixType.PREFIX : AffixType.SUFFIX;
        } else if (!ignoreLimit && !canAddAffix(data, chosen)) {
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
        final int ilvl = data.ilvl();
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : pool(base, type)) {
            if (usedGroups.contains(affix.group()) || !hasTierFor(affix, ilvl)) {
                continue;
            }
            list.add(affix);
        }
        return list;
    }

    private static boolean hasTierFor(final Affix affix, final int itemLevel) {
        for (final AffixTier tier : affix.tiers()) {
            if (tier.ilvl() <= itemLevel) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-22 效能：這個基底、這種型態「本來就擲得到」的詞綴（型態、基底標籤、不是揭露／腐化／退役的），照基底快取。
     * 以前每擲一條詞綴都把幾百條詞綴整個掃一遍、一條條比標籤（spark：打怪掉落佔主執行緒快 1%）。資料重讀就整個丟掉。
     */
    private List<Affix> pool(final ItemBase base, final AffixType type) {
        final int version = repository.version();
        if (version != poolVersion) {
            pools.clear();
            poolVersion = version;
        }
        final String key = base.id() + '|' + type.name();
        final List<Affix> cached = pools.get(key);
        if (cached != null) {
            return cached;
        }
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : repository.affixes()) {
            if (affix.type() != type || !affix.canSpawnOn(base)) {
                continue;
            }
            if (Veiled.isUnveiled(affix.id())) {
                continue; // 揭露詞綴只能靠揭露（盜賊首領）或揭露石，一般的掉落與通貨擲不到
            }
            if (affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG) || affix.spawnTags().contains(ItemRepository.RETIRED_TAG)) {
                continue; // 2026-10-21 修正：腐化詞綴只有蝕變石加得上（以前掉落、通貨會當成一般的後綴擲出來）
            }
            list.add(affix);
        }
        final List<Affix> frozen = List.copyOf(list);
        pools.put(key, frozen);
        return frozen;
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
        for (final RolledAffix rolled : data.affixes()) {
            final Affix other = repository.affix(rolled.id());
            if (data.isFractured(rolled) && other != null && other.group().equals(affix.group())) {
                return "同一群組的 " + rolled.id() + " 是破裂的詞綴，換不掉"; // 2026-10-21
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
