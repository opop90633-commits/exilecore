package tw.exilecore.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 傳奇改版（uniques.yml 的詞綴數量、順序、範圍或名字改了）時，已經掉出來的舊傳奇照新的定義補正：
 * 用那件傳奇的 {@code legacy} 對應表（沒寫就照序號）找舊的那一條，數值還在新範圍裡的留著，對不上的重擲
 * （物品 UUID 當種子，同一件每次補出來都一樣）；UUID、物品等級、品質、插槽不變，補完記下現在的版本。
 * <p>
 * 讀物品資料時就補（{@link ItemService#read}，所以屬性一定照新的算），重畫時寫回物品（{@link ItemService#refresh}）。
 * 純邏輯，不依賴 Bukkit。
 */
public final class UniqueUpgrade {

    private UniqueUpgrade() {
    }

    /** 這件是舊版的傳奇（版本舊、或跟目前的定義對不上）。不是傳奇、或那件傳奇已經從設定檔拿掉了：false（不動）。 */
    /**
     * 不是傳奇定義裡的詞綴、要原樣留著的：隱匿、揭露詞綴，還有蝕變石的腐化詞綴
     * （2026-10-21 修正：以前腐化過的傳奇每次讀出來都被當成舊版補正，腐化詞綴被拿掉，穿上去屬性沒算、說明那行也不見）。
     */
    private static boolean preserved(final ItemRepository repository, final String affixId) {
        if (Veiled.isSpecial(affixId)) {
            return true;
        }
        final Affix affix = repository.affix(affixId);
        return affix != null && affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG);
    }

    public static boolean outdated(final ItemRepository repository, final ItemData data) {
        final UniqueItem unique = definition(repository, data);
        return unique != null && outdated(repository, data, unique);
    }

    /**
     * 舊版的就照目前的定義補正（直接改 data）。
     *
     * @return 有沒有改
     */
    public static boolean apply(final ItemRepository repository, final ItemData data) {
        final UniqueItem unique = definition(repository, data);
        if (unique == null || !outdated(repository, data, unique)) {
            return false;
        }
        final int fromVersion = data.uniqueVersion();
        final Map<String, RolledAffix> previous = new HashMap<>();
        for (final RolledAffix rolled : data.affixes()) {
            previous.putIfAbsent(rolled.id(), rolled);
        }
        final List<RolledAffix> upgraded = new ArrayList<>();
        // 隱匿、揭露詞綴（隱匿傳奇）不是傳奇定義裡的詞綴：原樣留著，接在後面
        final List<RolledAffix> special = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (preserved(repository, rolled.id())) {
                special.add(rolled);
            }
        }
        int index = 0;
        for (final String affixId : unique.affixIds()) {
            index++;
            final Affix affix = repository.affix(affixId);
            if (affix == null) {
                continue;
            }
            final AffixTier tier = affix.tiers().getFirst();
            // 版本一樣（只是數值跑出範圍、或設定檔改了沒加版本）照序號找；版本不同照 legacy 對應表
            final int oldIndex = fromVersion == unique.version() ? index : unique.legacyIndex(fromVersion, index);
            final RolledAffix old = oldIndex <= 0 ? null : previous.get(UniqueItem.AFFIX_PREFIX + unique.id() + "." + oldIndex);
            // 2026-10-26：對得上的照比例換到新範圍（AffixRescale：有記舊範圍的照位置換，沒記的在範圍裡留著、超出的夾進去；
            // 腐化過的照倍數）；這次之前是超出範圍就重擲。變數對不上（換成別種數值）的才重擲。
            final RolledAffix fresh;
            if (old != null && old.vars() != null && tier.vars().keySet().equals(old.vars().keySet())) {
                fresh = new RolledAffix(affixId, tier.tier(), AffixRescale.map(old.vars(), old.ranges(), tier.vars(),
                        data.corrupted() || data.sanctified()));
            } else {
                fresh = new RolledAffix(affixId, tier.tier(), roll(tier, new SplittableRandom(seed(data, index))));
            }
            fresh.record(tier.tier(), tier.vars(), affix.group(), affix.type().name().toLowerCase(java.util.Locale.ROOT));
            upgraded.add(fresh);
        }
        data.affixes().clear();
        data.affixes().addAll(upgraded);
        data.affixes().addAll(special);
        data.setName(unique.name());
        data.setUniqueVersion(unique.version());
        return true;
    }

    private static UniqueItem definition(final ItemRepository repository, final ItemData data) {
        if (data == null || data.rarity() != Rarity.UNIQUE || data.unique() == null) {
            return null;
        }
        return repository.unique(data.unique());
    }

    private static boolean outdated(final ItemRepository repository, final ItemData data, final UniqueItem unique) {
        if (data.uniqueVersion() != unique.version() || !unique.name().equals(data.name())) {
            return true;
        }
        final List<String> expected = new ArrayList<>();
        for (final String affixId : unique.affixIds()) {
            if (repository.affix(affixId) != null) {
                expected.add(affixId);
            }
        }
        final List<RolledAffix> affixes = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            if (!preserved(repository, rolled.id())) {
                affixes.add(rolled);
            }
        }
        if (affixes.size() != expected.size()) {
            return true;
        }
        for (int i = 0; i < affixes.size(); i++) {
            final RolledAffix rolled = affixes.get(i);
            if (!expected.get(i).equals(rolled.id()) || !fits(repository.affix(rolled.id()).tiers().getFirst(), rolled.vars(), data.corrupted())) {
                return true;
            }
        }
        return false;
    }

    /** 擲出來的變數跟這一階的變數一樣、每個都在範圍裡。 */
    static boolean fits(final AffixTier tier, final Map<String, Integer> vars) {
        return fits(tier, vars, false);
    }

    /** 蝕變石對傳奇每條乘的倍數範圍（{@link ItemGenerator#corruptUniqueValues}）。 */
    static final double CORRUPT_MIN = 0.78;
    static final double CORRUPT_MAX = 1.22;

    /**
     * 2026-10-23 修正：腐化過的傳奇，數值可以超出原本範圍（蝕變石乘 0.78～1.22），範圍照著放寬；
     * 以前超出範圍就被當成舊版重擲，腐化擲出來的 52% 一穿上去就變回 48%。
     */
    static boolean fits(final AffixTier tier, final Map<String, Integer> vars, final boolean corrupted) {
        if (vars == null || !tier.vars().keySet().equals(vars.keySet())) {
            return false;
        }
        for (final Map.Entry<String, int[]> entry : tier.vars().entrySet()) {
            final Integer value = vars.get(entry.getKey());
            int lo = Math.min(entry.getValue()[0], entry.getValue()[1]);
            int hi = Math.max(entry.getValue()[0], entry.getValue()[1]);
            if (corrupted) {
                lo = (int) Math.floor(Math.min(lo * CORRUPT_MIN, lo * CORRUPT_MAX));
                hi = (int) Math.ceil(Math.max(hi * CORRUPT_MIN, hi * CORRUPT_MAX));
            }
            if (value == null || value < lo || value > hi) {
                return false;
            }
        }
        return true;
    }

    /** 照變數名排序擲（不管設定檔的順序怎麼寫，同一個種子擲出來都一樣）。 */
    private static Map<String, Integer> roll(final AffixTier tier, final SplittableRandom random) {
        final Map<String, Integer> rolled = new LinkedHashMap<>();
        for (final String key : new java.util.TreeSet<>(tier.vars().keySet())) {
            final int[] range = tier.vars().get(key);
            final int lo = Math.min(range[0], range[1]);
            final int hi = Math.max(range[0], range[1]);
            rolled.put(key, lo == hi ? lo : lo + random.nextInt(hi - lo + 1));
        }
        // 照設定檔的順序排回去（顯示用的順序）
        final Map<String, Integer> ordered = new LinkedHashMap<>();
        for (final String key : tier.vars().keySet()) {
            ordered.put(key, rolled.get(key));
        }
        return ordered;
    }

    private static long seed(final ItemData data, final int index) {
        final String uuid = data.uuid() == null ? "" : data.uuid();
        return uuid.hashCode() * 1_000_003L + index;
    }
}
