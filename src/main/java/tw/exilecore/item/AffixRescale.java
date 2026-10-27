package tw.exilecore.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 2026-10-26：詞綴資料改版時，舊物品身上的詞綴跟著新資料換算（照比例）；這次之前擲出來的數值是固定的，範圍改了也不會跟著變，
 * 拿掉的詞綴（spawn_tags 寫 retired、或整條刪掉）直接失效。
 * <ul>
 *   <li>每個變數照它在舊範圍的位置（例如擲到 70%）換到新範圍的同一個位置。舊範圍記在物品上（{@link RolledAffix#ranges()}、
 *       {@link ItemData#implicitRanges()}，擲出或換算時記下）；這次之前的舊物品沒記：還在新範圍裡的留著，超出的夾進新範圍。</li>
 *   <li>那一階新資料沒有了：換成代號最接近的那一階。</li>
 *   <li>拿掉的詞綴：換成同一個群組（沒有就同前後綴、同屬性）的詞綴，階級照物品等級擲得到的、位置照原本的比例；
 *       真的找不到同類的才拿掉，補一條同前後綴的隨機詞綴（物品 UUID 當種子，同一件每次補出來都一樣）。</li>
 *   <li>腐化（蝕變石）、聖化過的照樣換算，超出原本範圍的（傳奇腐化乘 0.78～1.22）照倍數換到新範圍；腐化、聖化狀態不動
 *       （不會因此變成可以再改）。腐化詞綴、星魄詞綴、傳奇詞綴、固有詞綴都一樣照比例。符文、注入只存代號，效果本來就照現在的資料算。</li>
 * </ul>
 * 換算完記下新的範圍，所以同一份資料只換一次（資料再改版時再換一次）。純邏輯，不依賴 Bukkit。
 */
public final class AffixRescale {

    private AffixRescale() {
    }

    /**
     * 照目前的詞綴資料換算這件物品（直接改 data）。
     *
     * @return 有沒有改（包括第一次記下範圍）
     */
    public static boolean apply(final ItemRepository repository, final ItemData data) {
        if (repository == null || data == null) {
            return false;
        }
        boolean changed = false;
        final ItemBase base = data.base() == null ? null : repository.base(data.base());
        final boolean loose = data.corrupted() || data.sanctified();
        final List<RolledAffix> affixes = data.affixes();
        for (int i = 0; i < affixes.size(); i++) {
            final RolledAffix rolled = affixes.get(i);
            if (rolled == null || rolled.id() == null || Veiled.isSpecial(rolled.id())) {
                continue; // 隱匿、揭露詞綴另外處理（Veiled）
            }
            final Affix affix = repository.affix(rolled.id());
            final boolean uniqueAffix = rolled.id().startsWith(UniqueItem.AFFIX_PREFIX);
            if (affix == null && uniqueAffix) {
                continue; // 傳奇定義裡的詞綴：UniqueUpgrade 照 uniques.yml 補正
            }
            if (affix == null || !uniqueAffix && retired(affix)) {
                if (base == null) {
                    continue;
                }
                final RolledAffix replacement = replacement(repository, data, base, i, rolled, affix);
                if (replacement != null) {
                    if (data.isFractured(rolled)) {
                        data.setFractured(replacement.id());
                    }
                    affixes.set(i, replacement);
                } else if (!replaceRandomly(repository, data, base, i, rolled, affix)) {
                    i--; // 拿掉了，補不到
                }
                changed = true;
                continue;
            }
            changed |= rescale(affix, rolled, loose);
        }
        changed |= rescaleImplicit(repository, base, data, loose);
        return changed;
    }

    /** 拿掉的詞綴：spawn_tags 寫 retired。 */
    static boolean retired(final Affix affix) {
        return affix.spawnTags().contains(ItemRepository.RETIRED_TAG);
    }

    // ---- 照比例換算 ----

    /** 一條詞綴照目前的資料換算（階級不在了換最接近的），記下新的範圍。回傳有沒有改。 */
    static boolean rescale(final Affix affix, final RolledAffix rolled, final boolean loose) {
        final AffixTier tier = nearestTier(affix, rolled.tier());
        final Map<String, int[]> now = tier.vars();
        final String side = affix.type().name().toLowerCase(java.util.Locale.ROOT);
        if (tier.tier() == rolled.tier() && sameRanges(rolled.ranges(), now)
                && affix.group().equals(rolled.group()) && side.equals(rolled.side())) {
            return false; // 已經是現在的資料
        }
        // 2026-10-27：階級合併（例如珠寶三階合成 PoE2 的一個範圍）：舊的那一階不在了、新範圍又整個包住舊範圍，
        // 擲出來的數值本來就合法，照位置換算反而會把高階的值換低，所以不動（記下新範圍）
        final Map<String, Integer> mapped = tier.tier() != rolled.tier() && contains(now, rolled.ranges())
                ? map(rolled.vars(), null, now, loose) : map(rolled.vars(), rolled.ranges(), now, loose);
        if (!mapped.equals(rolled.vars())) {
            rolled.setVars(mapped);
        }
        rolled.record(tier.tier(), now, affix.group(), side);
        return true;
    }

    private static boolean rescaleImplicit(final ItemRepository repository, final ItemBase base, final ItemData data, final boolean loose) {
        if (base == null || base.implicit() == null || base.implicit().vars().isEmpty()) {
            return false;
        }
        final Implicit implicit;
        try {
            implicit = ItemStats.implicit(repository, base, data);
        } catch (final RuntimeException e) {
            return false;
        }
        if (implicit == null || implicit.vars().isEmpty() || sameRanges(data.implicitRanges(), implicit.vars())) {
            return false;
        }
        final Map<String, Integer> mapped = map(data.implicitVars(), data.implicitRanges(), implicit.vars(), loose);
        if (!mapped.equals(data.implicitVars())) {
            data.setImplicitVars(mapped);
        }
        data.setImplicitRanges(implicit.vars());
        return true;
    }

    /** 這個代號的階級；新資料沒有這一階就用代號最接近的（一樣近取弱的那一階）。 */
    static AffixTier nearestTier(final Affix affix, final int tierId) {
        AffixTier best = null;
        for (final AffixTier tier : affix.tiers()) {
            if (tier.tier() == tierId) {
                return tier;
            }
            if (best == null || Math.abs(tier.tier() - tierId) < Math.abs(best.tier() - tierId)
                    || Math.abs(tier.tier() - tierId) == Math.abs(best.tier() - tierId) && tier.tier() > best.tier()) {
                best = tier;
            }
        }
        return best;
    }

    /** 2026-10-27：新範圍是不是每個變數都整個包住舊範圍（舊範圍沒記的當成不是）。 */
    static boolean contains(final Map<String, int[]> now, final Map<String, int[]> old) {
        if (old == null || old.isEmpty() || now == null) {
            return false;
        }
        for (final Map.Entry<String, int[]> entry : old.entrySet()) {
            final int[] o = entry.getValue();
            final int[] n = now.get(entry.getKey());
            if (o == null || n == null || o.length < 2 || n.length < 2) {
                return false;
            }
            if (Math.min(n[0], n[1]) > Math.min(o[0], o[1]) || Math.max(n[0], n[1]) < Math.max(o[0], o[1])) {
                return false;
            }
        }
        return true;
    }

    static boolean sameRanges(final Map<String, int[]> a, final Map<String, int[]> b) {
        if (a == null || b == null || !a.keySet().equals(b.keySet())) {
            return false;
        }
        for (final Map.Entry<String, int[]> entry : b.entrySet()) {
            if (!java.util.Arrays.equals(a.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 每個變數從舊範圍換到新範圍的同一個位置。
     * <ul>
     *   <li>舊範圍有記：照位置換（例如 70% 換到新範圍的 70%）；超出舊範圍的（腐化、聖化）照超出的倍數換。</li>
     *   <li>舊範圍沒記（舊物品）：在新範圍裡的留著，超出的夾進新範圍（腐化、聖化過的放寬成 0.78～1.22 倍）。</li>
     *   <li>新資料沒有的變數留著不動（說明、效果都照新資料，用不到）；新資料多的變數交給 {@link AffixUpgrade} 補。</li>
     * </ul>
     */
    static Map<String, Integer> map(final Map<String, Integer> vars, final Map<String, int[]> old, final Map<String, int[]> now,
                                    final boolean loose) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (vars == null) {
            return out;
        }
        for (final Map.Entry<String, Integer> entry : vars.entrySet()) {
            final int[] target = now.get(entry.getKey());
            final Integer value = entry.getValue();
            if (target == null || target.length < 2 || value == null) {
                out.put(entry.getKey(), value);
                continue;
            }
            final int[] source = old == null ? null : old.get(entry.getKey());
            out.put(entry.getKey(), source == null || source.length < 2
                    ? clamp(value, target, loose)
                    : convert(value, source, target, loose));
        }
        return out;
    }

    /** 照位置把一個值從舊範圍換到新範圍。 */
    static int convert(final int value, final int[] source, final int[] target, final boolean loose) {
        final int oldLo = Math.min(source[0], source[1]);
        final int oldHi = Math.max(source[0], source[1]);
        final int newLo = Math.min(target[0], target[1]);
        final int newHi = Math.max(target[0], target[1]);
        if (oldLo == newLo && oldHi == newHi) {
            return loose ? value : clamp(value, target, false);
        }
        if (value > oldHi || value < oldLo) {
            if (!loose) {
                return clamp(convert(Math.max(oldLo, Math.min(oldHi, value)), source, target, false), target, false);
            }
            // 腐化、聖化：超出舊範圍的照倍數（傳奇腐化乘 0.78～1.22）換到新範圍的那一端
            final int edgeOld = value > oldHi ? oldHi : oldLo;
            final int edgeNew = value > oldHi ? newHi : newLo;
            if (edgeOld == 0) {
                return edgeNew + (value - edgeOld);
            }
            return (int) Math.round(edgeNew * (value / (double) edgeOld));
        }
        if (oldLo == oldHi) {
            return (int) Math.round((newLo + newHi) / 2.0); // 原本是固定值：換到新範圍的中間
        }
        final double position = (value - oldLo) / (double) (oldHi - oldLo);
        return at(position, target);
    }

    /** 範圍裡第幾成的位置（0 = 最低、1 = 最高）。 */
    static int at(final double position, final int[] range) {
        final int lo = Math.min(range[0], range[1]);
        final int hi = Math.max(range[0], range[1]);
        final int value = (int) Math.round(lo + Math.max(0, Math.min(1, position)) * (hi - lo));
        return Math.max(lo, Math.min(hi, value));
    }

    /** 夾進範圍；腐化、聖化過的放寬成 0.78～1.22 倍（跟 {@link UniqueUpgrade#fits} 一樣）。 */
    static int clamp(final int value, final int[] range, final boolean loose) {
        int lo = Math.min(range[0], range[1]);
        int hi = Math.max(range[0], range[1]);
        if (loose) {
            lo = (int) Math.floor(Math.min(lo * UniqueUpgrade.CORRUPT_MIN, lo * UniqueUpgrade.CORRUPT_MAX));
            hi = (int) Math.ceil(Math.max(hi * UniqueUpgrade.CORRUPT_MIN, hi * UniqueUpgrade.CORRUPT_MAX));
        }
        return Math.max(lo, Math.min(hi, value));
    }

    // ---- 拿掉的詞綴 ----

    /** 詞綴的類別：腐化、星魄、藥劑護符、一般（換的時候只在同一類裡找）。 */
    private static String category(final Affix affix) {
        if (affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG)) {
            return ItemRepository.CORRUPTION_TAG;
        }
        if (affix.spawnTags().contains(ItemRepository.ESSENCE_TAG)) {
            return ItemRepository.ESSENCE_TAG;
        }
        return "normal";
    }

    private static Set<String> stats(final Affix affix) {
        final Set<String> stats = new HashSet<>();
        for (final AffixMod mod : affix.mods()) {
            stats.add(mod.stat() + "|" + mod.type());
        }
        return stats;
    }

    private static boolean fits(final Affix affix, final ItemBase base) {
        return affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG) ? ItemGenerator.corruptionFits(affix, base) : affix.canSpawnOn(base);
    }

    /**
     * 同類的新詞綴：同一個群組優先，其次同前後綴、同屬性（同一類：腐化換腐化、星魄換星魄）；這件物品上別條已經佔了的群組不行。
     * 找不到回傳 null。
     */
    static RolledAffix replacement(final ItemRepository repository, final ItemData data, final ItemBase base, final int index,
                                   final RolledAffix rolled, final Affix old) {
        final AffixType side = old != null ? old.type() : parseSide(rolled.side());
        final String group = old != null ? old.group() : rolled.group();
        if (side == null && group == null) {
            return null;
        }
        final Set<String> oldStats = old == null ? Set.of() : stats(old);
        final String oldCategory = old == null ? null : category(old);
        final Set<String> used = usedGroups(repository, data, index);
        Affix best = null;
        int bestScore = 0;
        final List<Affix> candidates = new ArrayList<>(repository.affixes());
        candidates.sort(Comparator.comparing(Affix::id));
        for (final Affix candidate : candidates) {
            if (retired(candidate) || candidate.spawnTags().contains(UniqueItem.SPAWN_TAG) || Veiled.isSpecial(candidate.id())
                    || candidate.id().equals(rolled.id()) || side != null && candidate.type() != side
                    || used.contains(candidate.group()) || !fits(candidate, base)) {
                continue;
            }
            int score = 0;
            if (group != null && group.equals(candidate.group())) {
                score = 1000;
            } else if (oldCategory != null && oldCategory.equals(category(candidate))) {
                final Set<String> shared = new HashSet<>(stats(candidate));
                shared.retainAll(oldStats);
                score = shared.size();
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) {
            return null;
        }
        final AffixTier tier = tierFor(best, data.ilvl(), old, rolled.tier());
        final double position = position(rolled, old);
        final Map<String, Integer> vars = new LinkedHashMap<>();
        tier.vars().forEach((key, range) -> vars.put(key, at(position, range)));
        final RolledAffix fresh = new RolledAffix(best.id(), tier.tier(), vars);
        fresh.record(tier.tier(), tier.vars(), best.group(), best.type().name().toLowerCase(java.util.Locale.ROOT));
        return fresh;
    }

    /** 物品等級擲得到的階級裡，照原本那一階的排名比例挑（原本是最強的就挑擲得到的最強）；不知道原本的排名就挑擲得到的最強。 */
    static AffixTier tierFor(final Affix affix, final int itemLevel, final Affix old, final int oldTier) {
        List<AffixTier> allowed = affix.tiersFor(itemLevel);
        if (allowed.isEmpty()) {
            allowed = List.of(affix.tiers().getLast()); // 物品等級都不夠：最弱的一階
        }
        if (old == null || old.tiers().size() <= 1 || allowed.size() == 1) {
            return allowed.getFirst();
        }
        final double fraction = (old.rank(nearestTier(old, oldTier).tier()) - 1) / (double) (old.tiers().size() - 1);
        return allowed.get((int) Math.round(Math.max(0, Math.min(1, fraction)) * (allowed.size() - 1)));
    }

    /** 原本每個變數在範圍裡的位置（平均）；不知道範圍回傳 0.5。 */
    static double position(final RolledAffix rolled, final Affix old) {
        Map<String, int[]> ranges = rolled.ranges();
        if (ranges == null && old != null) {
            ranges = nearestTier(old, rolled.tier()).vars();
        }
        if (ranges == null || rolled.vars() == null) {
            return 0.5;
        }
        double sum = 0;
        int count = 0;
        for (final Map.Entry<String, Integer> entry : rolled.vars().entrySet()) {
            final int[] range = ranges.get(entry.getKey());
            if (range == null || range.length < 2 || entry.getValue() == null || range[0] == range[1]) {
                continue;
            }
            final int lo = Math.min(range[0], range[1]);
            final int hi = Math.max(range[0], range[1]);
            sum += Math.max(0, Math.min(1, (entry.getValue() - lo) / (double) (hi - lo)));
            count++;
        }
        return count == 0 ? 0.5 : sum / count;
    }

    /**
     * 找不到同類的：拿掉，在同一個位置補一條同前後綴的隨機詞綴（不知道前後綴就補比較少的那一種）。
     * 傳奇不補隨機詞綴（只拿掉）；腐化詞綴補一條隨機的腐化詞綴。
     *
     * @return 有沒有補到（false = 只拿掉了）
     */
    private static boolean replaceRandomly(final ItemRepository repository, final ItemData data, final ItemBase base, final int index,
                                           final RolledAffix rolled, final Affix old) {
        data.affixes().remove(index);
        if (data.isFractured(rolled)) {
            data.setFractured(null);
        }
        if (data.rarity() == Rarity.UNIQUE && (old == null || !ItemRepository.CORRUPTION_TAG.equals(category(old)))) {
            return false;
        }
        final SplittableRandom random = new SplittableRandom(seed(data, index));
        final ItemGenerator generator = new ItemGenerator(repository);
        final int before = data.affixes().size();
        boolean added;
        try {
            if (old != null && ItemRepository.CORRUPTION_TAG.equals(category(old))) {
                added = generator.addCorruptionEnchant(data, random);
            } else {
                AffixType side = old != null ? old.type() : parseSide(rolled.side());
                if (side == null) {
                    side = generator.countOf(data, AffixType.PREFIX) <= generator.countOf(data, AffixType.SUFFIX) ? AffixType.PREFIX : AffixType.SUFFIX;
                }
                added = generator.addAffix(data, side, random, true);
            }
        } catch (final RuntimeException e) {
            added = false;
        }
        if (!added || data.affixes().size() <= before) {
            return false;
        }
        final RolledAffix fresh = data.affixes().removeLast();
        final Affix affix = repository.affix(fresh.id());
        if (affix != null) {
            fresh.record(fresh.tier(), affix.ranges(fresh.tier()), affix.group(), affix.type().name().toLowerCase(java.util.Locale.ROOT));
        }
        data.affixes().add(Math.min(index, data.affixes().size()), fresh);
        return true;
    }

    private static Set<String> usedGroups(final ItemRepository repository, final ItemData data, final int except) {
        final Set<String> used = new HashSet<>();
        for (int i = 0; i < data.affixes().size(); i++) {
            if (i == except) {
                continue;
            }
            final RolledAffix other = data.affixes().get(i);
            final Affix affix = other == null || other.id() == null ? null : repository.affix(other.id());
            if (affix != null && !retired(affix)) {
                used.add(affix.group());
            }
        }
        return used;
    }

    private static AffixType parseSide(final String side) {
        if (side == null) {
            return null;
        }
        try {
            return AffixType.parse(side);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static long seed(final ItemData data, final int index) {
        return Objects.hashCode(data.uuid()) * 1_000_003L + 7_919L * (index + 1);
    }
}
