package tw.exilecore.currency;

import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixChips;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.Catalyst;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 地圖機制掉的特殊通貨怎麼改裝備（照 PoE2）：
 * <ul>
 *   <li><b>星魄</b>（寶箱）：2026-10-26 照 PoE2 0.5 分階——次級、一般、高階：魔法 → 稀有，加一條固定階級的詞綴；
 *       完美、腐化：稀有物品隨機移除一條，再加那條詞綴。舊的（沒寫 essence_tier）照以前：普通 → 魔法、魔法 → 稀有。</li>
 *   <li><b>催化劑</b>（裂隙）：戒指、項鍊品質照稀有度 +5／+2／+1（上限 20），品質變成某一類詞綴的數值加成；換種類會重來。</li>
 *   <li><b>星籤</b>（流寇）：掛在物品上，下一顆對應的通貨照規則用，用完就消失。</li>
 * </ul>
 * 純邏輯，不依賴 Bukkit。
 */
public final class Crafting {

    /** 催化劑在普通物品上每顆加幾 % 品質（魔法 2、稀有與傳奇 1，見 {@link CurrencyOperation#qualityByRarity}）、上限。 */
    public static final int CATALYST_STEP = 5;
    public static final int CATALYST_MAX = 20;
    /**
     * 2026-10-26：催化至臻籤（Omen of Catalysing Exaltation）每 1% 催化劑品質讓那一類詞綴的權重多幾倍
     * （PoE2 沒公開數字；20% 品質 = 權重 ×2）。
     */
    public static final double CATALYSING_PER_QUALITY = 0.05;

    private Crafting() {
    }

    /** 映星鏡不能複製這件的原因（可以回傳 null）。 */
    private static String mirrorProblem(final ItemData data, final ItemBase base) {
        if (base.isMap()) {
            return "地圖不能複製";
        }
        if (base.isFlaskLike()) {
            return (base.isFlask() ? "藥劑" : "護符") + "不能複製";
        }
        // 2026-10-26 照 PoE2：傳奇可以複製（這次之前不行）
        if (data.starter()) {
            return "新手裝備不能複製";
        }
        if (!data.identified()) {
            return "要先鑑定";
        }
        if (data.corrupted()) {
            return "已腐化的物品不能複製";
        }
        return null;
    }

    /**
     * 映星鏡（2026-10）：做出這件的鏡像複製品——詞綴、數值、品質、符文、催化劑、注入全部一樣，換一個新的物品代號（不會被當成複製 bug），
     * 標成複製品（之後不能再改造、不能再複製）。鎖定、掛著的星籤、預見鎖不跟過去。原本那件不動。先確認過 {@link #problem}。
     */
    public static ItemData mirror(final ItemData original) {
        final ItemData copy = original.copy();
        copy.regenerateUuid();
        copy.setMirrored(true);
        copy.setLocked(false);
        copy.setOmen(null);
        copy.setForesight(null);
        return copy;
    }

    /** 符文（2026-10）：嵌進一個空孔（先確認過 {@link #problem}）。 */
    public static void applyRune(final CurrencyType type, final ItemData data) {
        data.addRune(type.affix());
    }

    /** 2026-10-26：戒指、項鍊（PoE2 的催化劑只能用在這兩種；腰帶不行）。 */
    static boolean catalystTarget(final ItemBase base) {
        return base.slot() == ItemSlot.RING || base.slot() == ItemSlot.AMULET;
    }

    /** 這顆通貨不能用在這件上的原因（能用回傳 null）。只管星魄、催化劑、星籤…；其他通貨回傳 null。 */
    public static String problem(final CurrencyType type, final ItemData data, final ItemGenerator generator) {
        final ItemBase base = generator.repository().base(data.base());
        if (base == null) {
            return "不認得的基底";
        }
        if (data.mirrored()) {
            // 2026-10：映星鏡照出來的複製品不能再動（鑑定除外——複製品本來就鑑定過了）
            return type.operation() == CurrencyOperation.MIRROR ? "這件已經是鏡像複製品，不能再複製" : "鏡像複製品不能再改造";
        }
        if (data.sanctified() && !type.operation().allowedOnCorrupted()) {
            return type.operation() == CurrencyOperation.MIRROR ? "已聖化的物品不能複製" : "已聖化的物品不能再改造"; // 2026-10-26
        }
        if (type.operation() == CurrencyOperation.MIRROR) {
            return mirrorProblem(data, base);
        }
        if (base.isFlaskLike()) {
            // 2026-10：藥劑、護符最多魔法（PoE2）：只有鑑定、蛻變、增幅、改造、機會→不行、抹除、神聖、祝福這幾種能用
            switch (type.operation()) {
                case IDENTIFY, UPGRADE_TO_MAGIC, ADD_AFFIX_MAGIC, REROLL_MAGIC, REMOVE_AFFIX, SCOUR, REROLL_VALUES, CORRUPT, QUALITY_FLASK, FORESEE -> { // 2026-10-23：蝕變石、凝露琉璃（品質）
                }
                default -> {
                    return (base.isFlask() ? "藥劑" : "護符") + "最多魔法，不能用" + type.name();
                }
            }
        }
        if (type.minModLevel() > 0) {
            final String why = minLevelProblem(type, data, generator);
            if (why != null) {
                return why;
            }
        }
        switch (type.operation()) {
            case ESSENCE -> {
                return essenceProblem(type, data, generator, base);
            }
            case UNVEIL -> {
                if (base.isMap() || !tw.exilecore.item.Veiled.hasUnveiledFor(base, generator.repository())) {
                    return (base.isMap() ? "地圖" : base.isJewel() ? "這種珠寶" : "這種裝備") + "沒有對應的揭露詞綴，不能加隱匿詞綴"; // 2026-10
                }
                if (tw.exilecore.item.Veiled.hasSpecial(data)) {
                    return "一件裝備只能有一條隱匿或揭露詞綴";
                }
                if (!data.identified()) {
                    return "要先鑑定";
                }
                if (!generator.canAddAffix(data, tw.exilecore.item.AffixType.PREFIX)
                        && !generator.canAddAffix(data, tw.exilecore.item.AffixType.SUFFIX)) {
                    return "前綴、後綴都滿了，加不了隱匿詞綴";
                }
                return null;
            }
            case INSTIL -> {
                if (base.slot() != tw.exilecore.item.ItemSlot.AMULET) {
                    return "蒸餾情緒只能注入項鍊";
                }
                if (!data.identified()) {
                    return "要先鑑定";
                }
                return null;
            }
            case RUNE -> {
                final int slots = tw.exilecore.item.Runes.slots(generator.repository(), base, data);
                if (slots == 0) {
                    return "這種裝備沒有符文孔（武器、防具、副手才有）";
                }
                if (generator.repository().rune(type.affix()) == null) {
                    return "設定錯了：runes.yml 沒有 " + type.affix();
                }
                if (data.runes().size() >= slots) {
                    return "符文孔都滿了（" + slots + " 個）";
                }
                return null;
            }
            case CATALYST -> {
                if (!catalystTarget(base)) {
                    return "催化劑只能打在戒指、項鍊上"; // 2026-10-26 照 PoE2：腰帶不行
                }
                if (data.quality() >= CATALYST_MAX && type.catalyst() == Catalyst.parse(data.catalyst())) {
                    return "品質已經到 " + CATALYST_MAX + "% 了";
                }
                return null;
            }
            case OMEN -> {
                if (type.omen() == null) {
                    return "設定錯了：星籤沒有規則";
                }
                if (type.id().equals(data.omen())) {
                    return "這件已經掛著同一個星籤了";
                }
                return null;
            }
            case ADD_SOCKET -> {
                if (data.unique() != null) {
                    return "傳奇的符文孔是固定的，不能再開";
                }
                if (!tw.exilecore.item.Runes.artificerTarget(base)) {
                    return "工匠石只能用在攻擊武器、法杖、長杖、防具上";
                }
                final int max = tw.exilecore.item.Runes.slots(base);
                if (data.runeSockets() == null || data.runeSockets() >= max) {
                    return "符文孔已經開到上限了（" + max + " 個）";
                }
                return null;
            }
            case FORESEE -> {
                if (data.foresight() != null) {
                    return "這件已經上了預見鎖：拿通貨點它就看得到結果";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 2026-10-26：高階／完美通貨：物品等級要夠、而且要有需求等級夠高的詞綴能加。 */
    private static String minLevelProblem(final CurrencyType type, final ItemData data, final ItemGenerator generator) {
        final int min = type.minModLevel();
        switch (type.operation()) {
            case UPGRADE_TO_MAGIC, ADD_AFFIX_MAGIC, MAGIC_TO_RARE, ADD_AFFIX_RARE, REMOVE_AND_ADD -> {
            }
            default -> {
                return null;
            }
        }
        if (data.ilvl() < min) {
            return "物品等級 " + data.ilvl() + "：" + type.name() + "只加需求等級 " + min + " 以上的詞綴，這件出不來";
        }
        final ItemData after = data.copy();
        if (type.operation() == CurrencyOperation.UPGRADE_TO_MAGIC) {
            after.setRarity(Rarity.MAGIC);
            after.affixes().clear();
        } else if (type.operation() == CurrencyOperation.MAGIC_TO_RARE) {
            after.setRarity(Rarity.RARE);
        }
        final boolean[] ok = {false};
        ItemGenerator.withMinModLevel(min, () -> ok[0] = type.operation() == CurrencyOperation.REMOVE_AND_ADD
                ? !generator.eligible(after, AffixType.PREFIX).isEmpty() || !generator.eligible(after, AffixType.SUFFIX).isEmpty()
                : generator.canAddAffix(after, null));
        return ok[0] ? null : "沒有需求等級 " + min + " 以上的詞綴能加在這件上";
    }

    // ---- 星魄 ----

    private static String essenceProblem(final CurrencyType type, final ItemData data, final ItemGenerator generator, final ItemBase base) {
        if (base.isMap()) {
            return "星魄不能用在地圖上";
        }
        final Affix affix = essenceAffix(generator, type, base, null);
        if (affix == null) {
            return "設定錯了：找不到詞綴 " + type.affix();
        }
        if (!affix.canSpawnOn(base)) {
            return type.name() + "的詞綴（" + affix.render(sample(affix, data.ilvl())) + "）不會出現在" + base.slot().displayName() + "上";
        }
        final CurrencyType.EssenceTier tier = type.essenceTier();
        if (tier == null) {
            if (affix.tiersFor(data.ilvl()).isEmpty()) {
                return "物品等級太低，這條詞綴還出不來";
            }
        } else if (tier.replaces()) {
            if (data.rarity() != Rarity.RARE) {
                return tier.displayName() + "星魄只能用在稀有物品上（隨機移除一條，再加它的那條）";
            }
        } else if (data.rarity() != Rarity.MAGIC) {
            return type.name() + "只能用在魔法物品上（變成稀有，加它的那條）";
        }
        // 2026-10 防呆：已經有同一類的詞綴就不能用（以前會把原本那條蓋掉）；完美、腐化會把同一類的那條換掉（破裂的不行）
        for (final RolledAffix rolled : data.affixes()) {
            final Affix existing = generator.repository().affix(rolled.id());
            if (existing != null && existing.group().equals(affix.group())) {
                if (tier != null && tier.replaces() && !data.isFractured(rolled)) {
                    return null;
                }
                return "這件已經有同一類的詞綴（" + existing.render(rolled.vars()) + "），不能再用" + type.name();
            }
        }
        if (tier != null && tier.replaces()) {
            if (removableForEssence(data, generator, affix.type(), null).isEmpty()) {
                return "沒有能移除的詞綴（破裂的拿不掉），或" + (affix.type() == AffixType.PREFIX ? "前綴" : "後綴") + "滿了";
            }
        }
        // 魔法物品前綴或後綴滿了也可以用（2026-10 修正）：星魄先把它升成稀有（前綴、後綴各三條的空間）再加那條，一定放得下
        return null;
    }

    /**
     * 完美、腐化星魄可以拿掉哪幾條（2026-10-26）：破裂的不算；星魄那條的那一型滿了就只能拿那一型的；
     * only 不是 null 時只拿那一型（PoE2 的 Omen of Sinistral／Dextral Crystallisation）。
     */
    private static List<RolledAffix> removableForEssence(final ItemData data, final ItemGenerator generator, final AffixType adding,
                                                         final AffixType only) {
        final boolean full = generator.countOf(data, adding) >= generator.maxAffixes(data, adding);
        final List<RolledAffix> list = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = generator.repository().affix(rolled.id());
            if (affix == null || data.isFractured(rolled)) {
                continue;
            }
            if (only != null && affix.type() != only) {
                continue;
            }
            if (full && affix.type() != adding) {
                continue;
            }
            list.add(rolled);
        }
        return list;
    }

    private static java.util.Map<String, Integer> sample(final Affix affix, final int ilvl) {
        final List<AffixTier> tiers = affix.tiersFor(ilvl);
        final AffixTier tier = tiers.isEmpty() ? affix.tiersFor(Integer.MAX_VALUE).getLast() : tiers.getFirst();
        final java.util.Map<String, Integer> vars = new java.util.LinkedHashMap<>();
        for (final java.util.Map.Entry<String, int[]> entry : tier.vars().entrySet()) {
            vars.put(entry.getKey(), entry.getValue()[0]);
        }
        return vars;
    }

    /**
     * 星魄在這個基底上給哪一條詞綴（2026-10 PoE2 改版：同一族在不同部位拆成好幾條，同一個 group）：
     * 設定的那條能出在這個基底上就用它，不然找同群組、同類型、能出在這裡的。
     * 2026-10-26：affix_random 的（PoE2 的 Essence of the Infinite：力量、敏捷或智慧）在出得了的幾條裡隨機挑（random 是 null 時挑第一條）。
     */
    static Affix essenceAffix(final ItemGenerator generator, final CurrencyType type, final tw.exilecore.item.ItemBase base,
                              final RandomGenerator random) {
        // 2026-10：affix 可以寫好幾個（逗號分開）：PoE2 的詞綴表把武器的本地詞綴（攻速、附加物理、暴擊）跟飾品、防具的分開了，
        // 一顆星魄照基底挑第一個出得來的
        Affix first = null;
        final List<Affix> fits = new ArrayList<>();
        for (final String id : type.affix().split(",")) {
            final Affix affix = generator.repository().affix(id.trim());
            if (affix == null) {
                continue;
            }
            if (first == null) {
                first = affix;
            }
            Affix found = null;
            if (base == null || affix.canSpawnOn(base)) {
                found = affix;
            } else {
                for (final Affix other : generator.repository().affixes()) {
                    if (other.group().equals(affix.group()) && other.type() == affix.type() && other.canSpawnOn(base)
                            && !other.spawnTags().contains(tw.exilecore.item.ItemRepository.RETIRED_TAG)) {
                        found = other;
                        break;
                    }
                }
            }
            if (found != null) {
                if (!type.crafts().affixRandom()) {
                    return found;
                }
                fits.add(found);
            }
        }
        if (!fits.isEmpty()) {
            return fits.get(random == null ? 0 : random.nextInt(fits.size()));
        }
        return first;
    }

    /** 用星魄：先確認 {@link #problem} 是 null。 */
    public static void applyEssence(final CurrencyType type, final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
        applyEssence(type, data, generator, random, null);
    }

    /**
     * 用星魄（先確認 {@link #problem} 是 null）。omen：完美、腐化星魄觸發的星籤（只移除前綴／後綴；沒有是 null）。
     *
     * @return 星籤有沒有用掉
     */
    public static boolean applyEssence(final CurrencyType type, final ItemData data, final ItemGenerator generator, final RandomGenerator random,
                                       final CurrencyType omen) {
        final Affix affix = essenceAffix(generator, type, generator.repository().base(data.base()), random);
        final CurrencyType.EssenceTier essenceTier = type.essenceTier();
        boolean omenUsed = false;
        if (essenceTier != null && essenceTier.replaces()) {
            // 完美、腐化（PoE2）：同一類的那條先換掉；沒有就隨機拿掉一條（那一型滿了只拿那一型；星籤只拿前綴／後綴）
            RolledAffix same = null;
            for (final RolledAffix rolled : data.affixes()) {
                final Affix existing = generator.repository().affix(rolled.id());
                if (existing != null && existing.group().equals(affix.group()) && !data.isFractured(rolled)) {
                    same = rolled;
                }
            }
            if (same != null) {
                data.affixes().remove(same);
            } else {
                AffixType only = null;
                if (omen != null && omen.omen() != null) {
                    only = switch (omen.omen().effect()) {
                        case KEEP_PREFIXES -> AffixType.SUFFIX;
                        case KEEP_SUFFIXES -> AffixType.PREFIX;
                        default -> null;
                    };
                }
                List<RolledAffix> candidates = removableForEssence(data, generator, affix.type(), only);
                if (candidates.isEmpty()) {
                    candidates = removableForEssence(data, generator, affix.type(), null);
                } else {
                    omenUsed = only != null;
                }
                if (!candidates.isEmpty()) {
                    data.affixes().remove(candidates.get(random.nextInt(candidates.size())));
                }
            }
            final AffixTier tier = bestTier(affix);
            data.affixes().add(new RolledAffix(affix.id(), tier.tier(), tier.roll(random)));
            data.setIdentified(true);
            return omenUsed;
        }
        // 同群組的舊詞綴先拿掉（星魄的那條換上去）
        data.affixes().removeIf(rolled -> {
            final Affix existing = generator.repository().affix(rolled.id());
            return existing != null && existing.group().equals(affix.group());
        });
        if (data.rarity() == Rarity.NORMAL) {
            data.setRarity(Rarity.MAGIC);
        } else if (data.rarity() == Rarity.MAGIC) {
            data.setRarity(Rarity.RARE);
            generator.ensureRareName(data, random);
        }
        final AffixTier tier = type.essenceLevel() > 0 ? fixedTier(affix, type.essenceLevel()) : pickTier(affix, data.ilvl(), random);
        data.affixes().add(new RolledAffix(affix.id(), tier.tier(), tier.roll(random)));
        // 剛從普通變魔法：星魄那條就是它的詞綴（PoE2：星魄做出來的魔法物品只有那一條）；魔法變稀有：原本的留著再加星魄那條
        data.setIdentified(true);
        return false;
    }

    /**
     * 2026-10-26 照 PoE2 的精髓：次級、一般、高階給的是固定的那一階——需求等級 ≤ level 的最好那一階（不管物品等級）；
     * 那條詞綴最差的一階都比 level 高就給最差的那階。
     */
    static AffixTier fixedTier(final Affix affix, final int level) {
        AffixTier best = null;
        AffixTier worst = null;
        for (final AffixTier tier : affix.tiers()) {
            if (worst == null || tier.ilvl() < worst.ilvl()) {
                worst = tier;
            }
            if (tier.ilvl() <= level && (best == null || tier.ilvl() > best.ilvl())) {
                best = tier;
            }
        }
        return best != null ? best : worst;
    }

    /** 完美、腐化星魄：那條詞綴最好的一階（星魄專屬的詞綴只有一階）。 */
    static AffixTier bestTier(final Affix affix) {
        AffixTier best = affix.tiers().getFirst();
        for (final AffixTier tier : affix.tiers()) {
            if (tier.ilvl() > best.ilvl()) {
                best = tier;
            }
        }
        return best;
    }

    /** 星魄的階級：照權重抽，但抽到最差的那階就往上一階（有的話）。 */
    static AffixTier pickTier(final Affix affix, final int ilvl, final RandomGenerator random) {
        final List<AffixTier> tiers = new ArrayList<>(affix.tiersFor(ilvl));
        tiers.sort((a, b) -> Integer.compare(a.tier(), b.tier())); // 代號小 = 好
        int total = 0;
        for (final AffixTier tier : tiers) {
            total += Math.max(1, tier.weight());
        }
        int pick = random.nextInt(total);
        int index = tiers.size() - 1;
        for (int i = 0; i < tiers.size(); i++) {
            pick -= Math.max(1, tiers.get(i).weight());
            if (pick < 0) {
                index = i;
                break;
            }
        }
        if (index == tiers.size() - 1 && tiers.size() > 1) {
            index--; // 不會是最差的那階
        }
        return tiers.get(index);
    }

    // ---- 催化劑 ----

    /**
     * 打催化劑：同種類品質照稀有度加（普通 +5、魔法 +2、稀有與傳奇 +1，上限 20）；不同種類換掉、品質從那個數字開始。
     * 2026-10-27 照 PoE2（催化劑跟其他品質通貨一樣照稀有度）；這次之前不分稀有度每顆 +{@link #CATALYST_STEP}。
     */
    public static void applyCatalyst(final CurrencyType type, final ItemData data) {
        final Catalyst current = Catalyst.parse(data.catalyst());
        final int step = CurrencyOperation.qualityByRarity(data);
        if (current != type.catalyst()) {
            data.setCatalyst(type.catalyst().id());
            data.setQuality(Math.min(CATALYST_MAX, step));
        } else {
            data.setQuality(Math.min(CATALYST_MAX, data.quality() + step));
        }
    }

    // ---- 星籤 ----

    /** 掛星籤。 */
    public static void attachOmen(final CurrencyType type, final ItemData data) {
        data.setOmen(type.id());
    }

    /** 這件掛著的星籤會不會被這顆通貨觸發。 */
    public static boolean triggers(final CurrencyType omen, final CurrencyType used) {
        return omen != null && omen.omen() != null && omen.omen().firesOn(used);
    }

    /**
     * 星籤生效時這顆通貨為什麼不能用（null = 可以）：例如「只加前綴」但前綴滿了。
     */
    public static String omenProblem(final CurrencyType omen, final CurrencyType used, final ItemData data, final ItemGenerator generator) {
        final CurrencyType.Omen.Effect effect = omen.omen().effect();
        final boolean adds = used.operation() == CurrencyOperation.ADD_AFFIX_RARE || used.operation() == CurrencyOperation.ADD_AFFIX_MAGIC
                || used.operation() == CurrencyOperation.MAGIC_TO_RARE || used.operation() == CurrencyOperation.REMOVE_AND_ADD;
        final boolean removes = used.operation() == CurrencyOperation.REMOVE_AFFIX || used.operation() == CurrencyOperation.REMOVE_AND_ADD
                || used.operation() == CurrencyOperation.ESSENCE;
        switch (effect) {
            case FORCE_PREFIX, FORCE_SUFFIX -> {
                final AffixType wanted = effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX;
                if (used.operation() == CurrencyOperation.UNVEIL) {
                    // 2026-10-26：揭露石＋Necromancy 星籤：那一型要有空位
                    return generator.canAddAffix(data, wanted) ? null
                            : omen.name() + "：" + (wanted == AffixType.PREFIX ? "前綴" : "後綴") + "沒有空位，加不了隱匿詞綴";
                }
                if (!adds) {
                    return null;
                }
                final ItemData after = data.copy();
                if (used.operation() == CurrencyOperation.MAGIC_TO_RARE) {
                    after.setRarity(Rarity.RARE); // 變稀有之後空位比較多
                }
                final boolean[] ok = {false};
                if (used.operation() == CurrencyOperation.REMOVE_AND_ADD) {
                    // 亂流石（2026-10-15 修正）：那一型滿了的話拿掉的一定是那一型，拿掉之後要加得上去
                    makeRoomFor(after, wanted, generator, null);
                    ItemGenerator.withMinModLevel(used.minModLevel(), () -> ok[0] = generator.canAddAffix(after, wanted));
                    return ok[0] ? null : omen.name() + "：沒有能加的" + (wanted == AffixType.PREFIX ? "前綴" : "後綴") + "，這顆通貨用不上去";
                }
                ItemGenerator.withMinModLevel(used.minModLevel(), () -> ok[0] = generator.canAddAffix(after, wanted));
                return ok[0] ? null : omen.name() + "：" + (wanted == AffixType.PREFIX ? "前綴" : "後綴") + "沒有空位，這顆通貨用不上去";
            }
            case KEEP_PREFIXES, KEEP_SUFFIXES -> {
                if (!removes) {
                    return null;
                }
                final AffixType removable = effect == CurrencyType.Omen.Effect.KEEP_PREFIXES ? AffixType.SUFFIX : AffixType.PREFIX;
                if (countRemovable(data, generator, removable) == 0) {
                    return omen.name() + "：這件沒有" + (removable == AffixType.PREFIX ? "前綴" : "後綴") + "可以移除";
                }
                return null;
            }
            case REMOVE_VEILED -> {
                for (final RolledAffix rolled : data.affixes()) {
                    if (tw.exilecore.item.Veiled.isSpecial(rolled.id()) && !data.isFractured(rolled)) {
                        return null;
                    }
                }
                return omen.name() + "：這件沒有隱匿或揭露詞綴";
            }
            case HOMOGENISE -> {
                final ItemData after = data.copy();
                if (used.operation() == CurrencyOperation.MAGIC_TO_RARE) {
                    after.setRarity(Rarity.RARE);
                }
                final boolean[] ok = {false};
                ItemGenerator.withMinModLevel(used.minModLevel(), () -> ok[0] = !homogeneous(after, generator).isEmpty());
                return ok[0] ? null : omen.name() + "：沒有跟這件的詞綴同一類、又加得上去的詞綴";
            }
            case IMPLICIT_ONLY -> {
                final var base = generator.repository().base(data.base());
                return base != null && base.implicit() != null && !base.implicit().vars().isEmpty() ? null
                        : omen.name() + "：這件沒有能重骰的固定詞綴";
            }
            case CATALYSING -> {
                return Catalyst.parse(data.catalyst()) != null && data.quality() > 0 ? null : omen.name() + "：這件沒有催化劑品質";
            }
            default -> {
                return null;
            }
        }
    }

    private static int countRemovable(final ItemData data, final ItemGenerator generator, final AffixType type) {
        int n = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = generator.repository().affix(rolled.id());
            if (affix != null && affix.type() == type && !data.isFractured(rolled)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 照星籤的規則用這顆通貨（呼叫前先確認 {@link #omenProblem} 是 null）。沒對應到的操作照普通的做。
     * 高階／完美通貨的最低詞綴等級由呼叫端包好（{@link ItemGenerator#withMinModLevel}）。
     *
     * @return 星籤有沒有真的改變做法（有就該把星籤拿掉）
     */
    public static boolean applyWithOmen(final CurrencyType omen, final CurrencyType used, final ItemData data, final ItemGenerator generator,
                                        final RandomGenerator random) {
        final CurrencyType.Omen.Effect effect = omen.omen().effect();
        switch (used.operation()) {
            case ADD_AFFIX_RARE, ADD_AFFIX_MAGIC -> {
                switch (effect) {
                    case FORCE_PREFIX -> generator.addAffix(data, AffixType.PREFIX, random);
                    case FORCE_SUFFIX -> generator.addAffix(data, AffixType.SUFFIX, random);
                    case DOUBLE_ADD -> {
                        generator.addAffix(data, null, random);
                        generator.addAffix(data, null, random);
                    }
                    case HOMOGENISE -> addWeighted(data, generator, random, homogeneous(data, generator), null);
                    case CATALYSING -> {
                        // 2026-10-26（Omen of Catalysing Exaltation）：用掉催化劑的品質，那一類詞綴的權重 × (1 + 品質 × 5%)
                        final Catalyst catalyst = Catalyst.parse(data.catalyst());
                        final double boost = 1 + data.quality() * CATALYSING_PER_QUALITY;
                        final List<Affix> pool = new ArrayList<>();
                        for (final AffixType type : List.of(AffixType.PREFIX, AffixType.SUFFIX)) {
                            if (generator.countOf(data, type) < generator.maxAffixes(data, type)) {
                                pool.addAll(generator.eligible(data, type));
                            }
                        }
                        addWeighted(data, generator, random, pool, affix -> catalyst != null && catalysed(affix, catalyst) ? boost : 1.0);
                        data.setQuality(0);
                        data.setCatalyst(null);
                    }
                    default -> {
                        used.operation().apply(data, generator, random);
                        return false;
                    }
                }
                return true;
            }
            case MAGIC_TO_RARE -> {
                if (effect != CurrencyType.Omen.Effect.FORCE_PREFIX && effect != CurrencyType.Omen.Effect.FORCE_SUFFIX
                        && effect != CurrencyType.Omen.Effect.HOMOGENISE) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                data.setRarity(Rarity.RARE);
                generator.ensureRareName(data, random);
                if (effect == CurrencyType.Omen.Effect.HOMOGENISE) {
                    addWeighted(data, generator, random, homogeneous(data, generator), null);
                } else {
                    generator.addAffix(data, effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX, random);
                }
                return true;
            }
            case UPGRADE_TO_RARE -> {
                if (effect != CurrencyType.Omen.Effect.MAX_PREFIXES && effect != CurrencyType.Omen.Effect.MAX_SUFFIXES) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                // 2026-10-26（Omen of Sinistral／Dextral Alchemy）：4 條裡那一型放到最多（3 條），另一型補滿 4 條
                final AffixType most = effect == CurrencyType.Omen.Effect.MAX_PREFIXES ? AffixType.PREFIX : AffixType.SUFFIX;
                data.setRarity(Rarity.RARE);
                data.affixes().clear();
                final var base = generator.repository().base(data.base());
                final int total = base != null && base.isJewel() ? 3 : 4;
                int added = 0;
                for (int i = 0; i < generator.maxAffixes(data, most) && added < total; i++) {
                    if (generator.addAffix(data, most, random)) {
                        added++;
                    }
                }
                while (added < total && generator.addAffix(data, most.other(), random)) {
                    added++;
                }
                generator.ensureRareName(data, random);
                data.setIdentified(true);
                return true;
            }
            case REMOVE_AFFIX -> {
                if (effect == CurrencyType.Omen.Effect.REMOVE_TWO) {
                    // 2026-10-26（Omen of Greater Annulment）：移除兩條
                    generator.removeRandomAffix(data, random);
                    generator.removeRandomAffix(data, random);
                    return true;
                }
                if (!removeByOmen(effect, data, generator, random)) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                return true;
            }
            case REMOVE_AND_ADD -> {
                if (effect == CurrencyType.Omen.Effect.FORCE_PREFIX || effect == CurrencyType.Omen.Effect.FORCE_SUFFIX) {
                    // 加前綴／加後綴籤＋亂流石（2026-10-15 修正）：那一型滿了的話拿掉的一定是那一型（以前隨便拿一條，
                    // 拿到另一型就加不上去，結果是白白少一條）；沒滿就照常隨便拿一條。加的一定是那一型
                    final AffixType wanted = effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX;
                    if (!makeRoomFor(data, wanted, generator, random)) {
                        generator.removeRandomAffix(data, random);
                    }
                    generator.addAffix(data, wanted, random);
                    return true;
                }
                final boolean removed = removeByOmen(effect, data, generator, random);
                if (!removed) {
                    generator.removeRandomAffix(data, random);
                }
                generator.addAffix(data, null, random);
                return removed;
            }
            case CORRUPT -> {
                if (effect != CurrencyType.Omen.Effect.ALWAYS_CHANGE) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                CurrencyOperation.corruptAlways(data, generator, random); // 2026-10-26（Omen of Corruption）
                return true;
            }
            case REROLL_VALUES -> {
                if (effect == CurrencyType.Omen.Effect.IMPLICIT_ONLY) {
                    generator.rerollImplicit(data, random); // 2026-10-26（Omen of the Blessed）：只重骰固定詞綴
                    return true;
                }
                if (effect == CurrencyType.Omen.Effect.SANCTIFY && data.rarity() == Rarity.RARE) {
                    // 2026-10-26（Omen of Sanctification）：稀有物品聖化——數值重骰再各乘 0.78～1.22，之後不能再改造
                    generator.corruptUniqueValues(data, random);
                    data.setSanctified(true);
                    return true;
                }
                used.operation().apply(data, generator, random);
                return false;
            }
            case UNVEIL -> {
                if (effect != CurrencyType.Omen.Effect.FORCE_PREFIX && effect != CurrencyType.Omen.Effect.FORCE_SUFFIX) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                // 2026-10-26（Omen of Sinistral／Dextral Necromancy）：揭露石只加前綴／後綴的隱匿詞綴
                tw.exilecore.item.Veiled.addVeiled(data, generator, random, false,
                        effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX);
                return true;
            }
            default -> {
                used.operation().apply(data, generator, random);
                return false;
            }
        }
    }

    /** 這條詞綴有沒有修飾器吃得到這種催化劑（2026-10-26：催化至臻籤用）。 */
    private static boolean catalysed(final Affix affix, final Catalyst catalyst) {
        if (affix.tiers().isEmpty()) {
            return false;
        }
        final java.util.Map<String, Integer> vars = new java.util.HashMap<>();
        for (final java.util.Map.Entry<String, int[]> entry : affix.tiers().getFirst().vars().entrySet()) {
            vars.put(entry.getKey(), entry.getValue()[0]);
        }
        for (final AffixMod mod : affix.mods()) {
            if (catalyst.matches(mod.toModifier(vars, ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-26（Omen of Homogenising Exaltation／Coronation）：跟物品上已經有的詞綴有相同標籤（傷害以外：生命、火焰、攻擊…）、
     * 加得上去的詞綴。
     */
    static List<Affix> homogeneous(final ItemData data, final ItemGenerator generator) {
        final java.util.Set<AffixChips.Chip> existing = java.util.EnumSet.noneOf(AffixChips.Chip.class);
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = generator.repository().affix(rolled.id());
            if (affix != null) {
                existing.addAll(generator.repository().chipsOf(affix));
            }
        }
        existing.remove(AffixChips.Chip.DAMAGE);
        final List<Affix> list = new ArrayList<>();
        for (final AffixType type : List.of(AffixType.PREFIX, AffixType.SUFFIX)) {
            if (generator.countOf(data, type) >= generator.maxAffixes(data, type)) {
                continue;
            }
            for (final Affix affix : generator.eligible(data, type)) {
                for (final AffixChips.Chip chip : generator.repository().chipsOf(affix)) {
                    if (existing.contains(chip)) {
                        list.add(affix);
                        break;
                    }
                }
            }
        }
        return list;
    }

    /**
     * 從 pool 依權重（這條詞綴能抽的階級權重合計 × boost）抽一條加上去（2026-10-26：同類、催化至臻籤用；照現在的最低詞綴等級）。
     *
     * @return 有沒有加上去
     */
    static boolean addWeighted(final ItemData data, final ItemGenerator generator, final RandomGenerator random, final List<Affix> pool,
                               final java.util.function.ToDoubleFunction<Affix> boost) {
        final double[] weights = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < pool.size(); i++) {
            double sum = 0;
            for (final AffixTier tier : ItemGenerator.rollableTiers(pool.get(i), data.ilvl())) {
                sum += tier.weight();
            }
            weights[i] = sum * (boost == null ? 1 : boost.applyAsDouble(pool.get(i)));
            total += weights[i];
        }
        if (total <= 0) {
            return false;
        }
        double pick = random.nextDouble() * total;
        Affix affix = pool.getLast();
        for (int i = 0; i < pool.size(); i++) {
            pick -= weights[i];
            if (pick < 0) {
                affix = pool.get(i);
                break;
            }
        }
        final List<AffixTier> tiers = ItemGenerator.rollableTiers(affix, data.ilvl());
        int tierTotal = 0;
        for (final AffixTier tier : tiers) {
            tierTotal += tier.weight();
        }
        AffixTier chosen = tiers.getLast();
        if (tierTotal > 0) {
            int tierPick = random.nextInt(tierTotal);
            for (final AffixTier tier : tiers) {
                tierPick -= tier.weight();
                if (tierPick < 0) {
                    chosen = tier;
                    break;
                }
            }
        }
        data.affixes().add(new RolledAffix(affix.id(), chosen.tier(), chosen.roll(random)));
        return true;
    }

    /**
     * 這一型滿了的話拿掉一條這一型的（random 為 null 時拿第一條：只是試算）。
     *
     * @return 有沒有拿（沒滿就不拿）
     */
    private static boolean makeRoomFor(final ItemData data, final AffixType type, final ItemGenerator generator, final RandomGenerator random) {
        if (generator.countOf(data, type) < generator.maxAffixes(data, type)) {
            return false;
        }
        final List<RolledAffix> same = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = generator.repository().affix(rolled.id());
            if (affix != null && affix.type() == type && !data.isFractured(rolled)) { // 2026-10-21：破裂的不拿
                same.add(rolled);
            }
        }
        if (same.isEmpty()) {
            return false;
        }
        data.affixes().remove(same.get(random == null ? 0 : random.nextInt(same.size())));
        return true;
    }

    /** 這一階的物品等級需求（詞綴的階級被刪掉了就當 0：最差）。 */
    private static int tierLevel(final Affix affix, final int tierId) {
        try {
            return affix.tier(tierId).ilvl();
        } catch (final IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * 「去裂」星籤會拿掉的候選（2026-10-27 照站長：改成 T 數字最大（最差）的那條；一樣差的再比需求等級最低的，還一樣的隨機挑一條）；
     * 破裂的拿不掉。物品說明也用這個預告會移除哪一條。
     * 這次之前照 PoE2 的 Omen of Whittling 只比需求等級（階數少的詞綴 T1 需求等級也可能很低，會先被拿掉，玩家覺得像 bug）。
     */
    public static List<RolledAffix> lowestAffixes(final ItemData data, final tw.exilecore.item.ItemRepository repository) {
        final List<RolledAffix> worst = new ArrayList<>();
        int worstRank = Integer.MIN_VALUE;
        int worstLevel = Integer.MAX_VALUE;
        for (final RolledAffix rolled : data.affixes()) {
            if (data.isFractured(rolled)) {
                continue; // 2026-10-21：破裂的詞綴拿不掉
            }
            final Affix affix = repository.affix(rolled.id());
            final int rank = affix == null ? Integer.MAX_VALUE : affix.rank(rolled.tier());
            final int level = affix == null ? 0 : tierLevel(affix, rolled.tier());
            if (rank > worstRank || rank == worstRank && level < worstLevel) {
                worstRank = rank;
                worstLevel = level;
                worst.clear();
                worst.add(rolled);
            } else if (rank == worstRank && level == worstLevel) {
                worst.add(rolled);
            }
        }
        return worst;
    }

    /** 照星籤移除一條：最差的、只拿後綴、只拿前綴、只拿隱匿／揭露的；星籤跟移除無關回傳 false（沒動）。 */
    private static boolean removeByOmen(final CurrencyType.Omen.Effect effect, final ItemData data, final ItemGenerator generator,
                                        final RandomGenerator random) {
        final List<RolledAffix> candidates = new ArrayList<>();
        switch (effect) {
            case REMOVE_LOWEST -> {
                final List<RolledAffix> worst = lowestAffixes(data, generator.repository());
                if (worst.isEmpty()) {
                    return false;
                }
                data.affixes().remove(worst.get(random == null || worst.size() == 1 ? 0 : random.nextInt(worst.size())));
                return true;
            }
            case KEEP_PREFIXES, KEEP_SUFFIXES -> {
                final AffixType removable = effect == CurrencyType.Omen.Effect.KEEP_PREFIXES ? AffixType.SUFFIX : AffixType.PREFIX;
                for (final RolledAffix rolled : data.affixes()) {
                    final Affix affix = generator.repository().affix(rolled.id());
                    if (affix != null && affix.type() == removable && !data.isFractured(rolled)) { // 2026-10-21：破裂的不拿
                        candidates.add(rolled);
                    }
                }
                if (candidates.isEmpty()) {
                    return false;
                }
                data.affixes().remove(candidates.get(random.nextInt(candidates.size())));
                return true;
            }
            case REMOVE_VEILED -> {
                // 2026-10-26（Omen of Light）：只拿隱匿／揭露詞綴（PoE2 的褻瀆詞綴）
                for (final RolledAffix rolled : data.affixes()) {
                    if (tw.exilecore.item.Veiled.isSpecial(rolled.id()) && !data.isFractured(rolled)) {
                        candidates.add(rolled);
                    }
                }
                if (candidates.isEmpty()) {
                    return false;
                }
                data.affixes().remove(candidates.get(random.nextInt(candidates.size())));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- 注入（蒸餾情緒 → 項鍊）----

    /** 注入一個核心要幾顆蒸餾情緒（照 PoE2：三格，配方見 {@link InstilRecipes}）。 */
    public static final int INSTIL_COST = InstilRecipes.SIZE;

    /*
     * 2026-10-27 照 PoE2 0.5：拿掉「離天賦樹中心幾格用第幾階的情緒、3 顆同一種」（instilTier：≤7 格第 1 階、≤11 第 2 階、≤15 第 3 階、
     * 更遠第 4 階），改成每個核心固定的三種情緒配方（data/instil.yml）。
     */

    /**
     * 2026-10-27：這個核心能不能注入、手上的情緒夠不夠（owned：情緒代號 → 有幾顆）。可以回傳 null，不行回傳原因。
     */
    public static String instilProblem(final String nodeId, final java.util.function.ToIntFunction<String> owned,
                                       final java.util.function.Function<String, String> nameOf) {
        final java.util.Map<String, Integer> cost = InstilRecipes.cost(nodeId);
        if (cost.isEmpty()) {
            return "這個核心不能注入（沒有情緒配方）";
        }
        for (final java.util.Map.Entry<String, Integer> entry : cost.entrySet()) {
            final int have = owned.applyAsInt(entry.getKey());
            if (have < entry.getValue()) {
                return "要 " + entry.getValue() + " 顆" + nameOf.apply(entry.getKey()) + "，你有 " + have;
            }
        }
        return null;
    }

    /**
     * 2026-10-27 照 PoE2：幻霧獎勵抽哪一種情緒的權重，掉落等級（currencies.yml 的 drop_level）比地圖等級高的那幾種是 0。
     * 全部都不夠（設定錯了）就照原本的權重。
     */
    public static java.util.List<Integer> emotionWeightsAt(final java.util.List<String> emotions, final java.util.List<Integer> weights,
                                                           final int level, final java.util.function.Function<String, CurrencyType> lookup) {
        final java.util.List<Integer> out = new java.util.ArrayList<>(weights.size());
        boolean any = false;
        for (int i = 0; i < weights.size(); i++) {
            final CurrencyType type = i < emotions.size() ? lookup.apply(emotions.get(i)) : null;
            final boolean ok = type == null || type.dropLevel() <= level;
            out.add(ok ? weights.get(i) : 0);
            any |= ok && weights.get(i) > 0;
        }
        return any ? out : weights;
    }

    /** 把核心注入項鍊（原本注入的換掉）。呼叫端已經檢查過通貨與數量。 */
    public static void instil(final ItemData data, final String nodeId) {
        data.setInstilled(nodeId);
    }

    /** 2026-10-26：給測試用——這些操作會照高階／完美通貨的最低詞綴等級。 */
    static final Set<CurrencyOperation> MIN_LEVEL_OPERATIONS = Set.of(CurrencyOperation.UPGRADE_TO_MAGIC, CurrencyOperation.ADD_AFFIX_MAGIC,
            CurrencyOperation.MAGIC_TO_RARE, CurrencyOperation.ADD_AFFIX_RARE, CurrencyOperation.REMOVE_AND_ADD);
}
