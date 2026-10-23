package tw.exilecore.currency;

import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.Catalyst;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 地圖機制掉的特殊通貨怎麼改裝備（照 PoE2）：
 * <ul>
 *   <li><b>星魄</b>（寶箱）：普通 → 魔法、魔法 → 稀有，一定帶星魄的那條詞綴（同群組的換掉；階級照物品等級抽，不會是最差的）。</li>
 *   <li><b>催化劑</b>（裂隙）：飾品品質 +5（上限 20），品質變成某一類詞綴的數值加成；換種類會重來。</li>
 *   <li><b>星籤</b>（流寇）：掛在物品上，下一顆對應的通貨照規則用（只加前綴、只加後綴、加兩條、移除最差的、不動前綴、不動後綴），用完就消失。</li>
 * </ul>
 * 純邏輯，不依賴 Bukkit。
 */
public final class Crafting {

    /** 催化劑每顆加幾 % 品質、上限。 */
    public static final int CATALYST_STEP = 5;
    public static final int CATALYST_MAX = 20;

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
        if (data.rarity() == Rarity.UNIQUE || data.unique() != null) {
            return "傳奇不能複製";
        }
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
     * 標成複製品（之後不能再改造、不能再複製）。鎖定、掛著的星籤不跟過去。原本那件不動。先確認過 {@link #problem}。
     */
    public static ItemData mirror(final ItemData original) {
        final ItemData copy = original.copy();
        copy.regenerateUuid();
        copy.setMirrored(true);
        copy.setLocked(false);
        copy.setOmen(null);
        return copy;
    }

    /** 符文（2026-10）：嵌進一個空孔（先確認過 {@link #problem}）。 */
    public static void applyRune(final CurrencyType type, final ItemData data) {
        data.addRune(type.affix());
    }

    /** 這顆通貨不能用在這件上的原因（能用回傳 null）。只管星魄、催化劑、星籤；其他通貨回傳 null。 */
    public static String problem(final CurrencyType type, final ItemData data, final ItemGenerator generator) {
        final ItemBase base = generator.repository().base(data.base());
        if (base == null) {
            return "不認得的基底";
        }
        if (data.mirrored()) {
            // 2026-10：映星鏡照出來的複製品不能再動（鑑定除外——複製品本來就鑑定過了）
            return type.operation() == CurrencyOperation.MIRROR ? "這件已經是鏡像複製品，不能再複製" : "鏡像複製品不能再改造";
        }
        if (type.operation() == CurrencyOperation.MIRROR) {
            return mirrorProblem(data, base);
        }
        if (base.isFlaskLike()) {
            // 2026-10：藥劑、護符最多魔法（PoE2）：只有鑑定、蛻變、增幅、改造、機會→不行、抹除、神聖、祝福這幾種能用
            switch (type.operation()) {
                case IDENTIFY, UPGRADE_TO_MAGIC, ADD_AFFIX_MAGIC, REROLL_MAGIC, REMOVE_AFFIX, SCOUR, REROLL_VALUES, CORRUPT -> { // 2026-10-23：蝕變石（品質）
                }
                default -> {
                    return (base.isFlask() ? "藥劑" : "護符") + "最多魔法，不能用" + type.name();
                }
            }
        }
        switch (type.operation()) {
            case ESSENCE -> {
                if (base.isMap()) {
                    return "星魄不能用在地圖上";
                }
                final Affix affix = essenceAffix(generator, type, base);
                if (affix == null) {
                    return "設定錯了：找不到詞綴 " + type.affix();
                }
                if (!affix.canSpawnOn(base)) {
                    return type.name() + "的詞綴（" + affix.render(sample(affix, data.ilvl())) + "）不會出現在" + base.slot().displayName() + "上";
                }
                if (affix.tiersFor(data.ilvl()).isEmpty()) {
                    return "物品等級太低，這條詞綴還出不來";
                }
                // 2026-10 防呆：已經有同一類的詞綴就不能用（以前會把原本那條蓋掉）
                for (final tw.exilecore.item.RolledAffix rolled : data.affixes()) {
                    final Affix existing = generator.repository().affix(rolled.id());
                    if (existing != null && existing.group().equals(affix.group())) {
                        return "這件已經有同一類的詞綴（" + existing.render(rolled.vars()) + "），不能再用" + type.name();
                    }
                }
                // 魔法物品前綴或後綴滿了也可以用（2026-10 修正）：星魄先把它升成稀有（前綴、後綴各三條的空間）再加那條，一定放得下
                return null;
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
                if (!base.isJewellery()) {
                    return "催化劑只能打在戒指、項鍊、腰帶上";
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
            default -> {
                return null;
            }
        }
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

    // ---- 星魄 ----

    /**
     * 星魄在這個基底上給哪一條詞綴（2026-10 PoE2 改版：同一族在不同部位拆成好幾條，同一個 group）：
     * 設定的那條能出在這個基底上就用它，不然找同群組、同類型、能出在這裡的。
     */
    static Affix essenceAffix(final ItemGenerator generator, final CurrencyType type, final tw.exilecore.item.ItemBase base) {
        // 2026-10：affix 可以寫好幾個（逗號分開）：PoE2 的詞綴表把武器的本地詞綴（攻速、附加物理、暴擊）跟飾品、防具的分開了，
        // 一顆星魄照基底挑第一個出得來的
        Affix first = null;
        for (final String id : type.affix().split(",")) {
            final Affix affix = generator.repository().affix(id.trim());
            if (affix == null) {
                continue;
            }
            if (first == null) {
                first = affix;
            }
            if (base == null || affix.canSpawnOn(base)) {
                return affix;
            }
            for (final Affix other : generator.repository().affixes()) {
                if (other.group().equals(affix.group()) && other.type() == affix.type() && other.canSpawnOn(base)) {
                    return other;
                }
            }
        }
        return first;
    }

    /** 用星魄：先確認 {@link #problem} 是 null。 */
    public static void applyEssence(final CurrencyType type, final ItemData data, final ItemGenerator generator, final RandomGenerator random) {
        final Affix affix = essenceAffix(generator, type, generator.repository().base(data.base()));
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
        final AffixTier tier = pickTier(affix, data.ilvl(), random);
        data.affixes().add(new RolledAffix(affix.id(), tier.tier(), tier.roll(random)));
        // 剛從普通變魔法：星魄那條就是它的詞綴（PoE2：星魄做出來的魔法物品只有那一條）；魔法變稀有：原本的留著再加星魄那條
        data.setIdentified(true);
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

    /** 打催化劑：同種類品質 +5（上限 20）；不同種類換掉、品質從 5 開始。 */
    public static void applyCatalyst(final CurrencyType type, final ItemData data) {
        final Catalyst current = Catalyst.parse(data.catalyst());
        if (current != type.catalyst()) {
            data.setCatalyst(type.catalyst().id());
            data.setQuality(CATALYST_STEP);
        } else {
            data.setQuality(Math.min(CATALYST_MAX, data.quality() + CATALYST_STEP));
        }
    }

    // ---- 星籤 ----

    /** 掛星籤。 */
    public static void attachOmen(final CurrencyType type, final ItemData data) {
        data.setOmen(type.id());
    }

    /** 這件掛著的星籤會不會被這顆通貨觸發。 */
    public static boolean triggers(final CurrencyType omen, final CurrencyType used) {
        return omen != null && omen.omen() != null && omen.omen().triggers().contains(used.operation());
    }

    /**
     * 星籤生效時這顆通貨為什麼不能用（null = 可以）：例如「只加前綴」但前綴滿了。
     */
    public static String omenProblem(final CurrencyType omen, final CurrencyType used, final ItemData data, final ItemGenerator generator) {
        final CurrencyType.Omen.Effect effect = omen.omen().effect();
        final boolean adds = used.operation() == CurrencyOperation.ADD_AFFIX_RARE || used.operation() == CurrencyOperation.ADD_AFFIX_MAGIC
                || used.operation() == CurrencyOperation.MAGIC_TO_RARE || used.operation() == CurrencyOperation.REMOVE_AND_ADD;
        final boolean removes = used.operation() == CurrencyOperation.REMOVE_AFFIX || used.operation() == CurrencyOperation.REMOVE_AND_ADD;
        switch (effect) {
            case FORCE_PREFIX, FORCE_SUFFIX -> {
                if (!adds) {
                    return null;
                }
                final AffixType wanted = effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX;
                final ItemData after = data.copy();
                if (used.operation() == CurrencyOperation.MAGIC_TO_RARE) {
                    after.setRarity(Rarity.RARE); // 變稀有之後空位比較多
                }
                if (used.operation() == CurrencyOperation.REMOVE_AND_ADD) {
                    // 亂流石（2026-10-15 修正）：那一型滿了的話拿掉的一定是那一型，拿掉之後要加得上去
                    makeRoomFor(after, wanted, generator, null);
                    if (!generator.canAddAffix(after, wanted)) {
                        return omen.name() + "：沒有能加的" + (wanted == AffixType.PREFIX ? "前綴" : "後綴") + "，這顆通貨用不上去";
                    }
                    return null;
                }
                if (!generator.canAddAffix(after, wanted)) {
                    return omen.name() + "：" + (wanted == AffixType.PREFIX ? "前綴" : "後綴") + "沒有空位，這顆通貨用不上去";
                }
                return null;
            }
            case KEEP_PREFIXES, KEEP_SUFFIXES -> {
                if (!removes) {
                    return null;
                }
                final AffixType removable = effect == CurrencyType.Omen.Effect.KEEP_PREFIXES ? AffixType.SUFFIX : AffixType.PREFIX;
                if (generator.countOf(data, removable) == 0) {
                    return omen.name() + "：這件沒有" + (removable == AffixType.PREFIX ? "前綴" : "後綴") + "可以移除";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 照星籤的規則用這顆通貨（呼叫前先確認 {@link #omenProblem} 是 null）。沒對應到的操作照普通的做。
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
                    default -> {
                        used.operation().apply(data, generator, random);
                        return false;
                    }
                }
                return true;
            }
            case MAGIC_TO_RARE -> {
                if (effect != CurrencyType.Omen.Effect.FORCE_PREFIX && effect != CurrencyType.Omen.Effect.FORCE_SUFFIX) {
                    used.operation().apply(data, generator, random);
                    return false;
                }
                data.setRarity(Rarity.RARE);
                generator.ensureRareName(data, random);
                generator.addAffix(data, effect == CurrencyType.Omen.Effect.FORCE_PREFIX ? AffixType.PREFIX : AffixType.SUFFIX, random);
                return true;
            }
            case REMOVE_AFFIX -> {
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
                switch (effect) {
                    case FORCE_PREFIX -> generator.addAffix(data, AffixType.PREFIX, random);
                    case FORCE_SUFFIX -> generator.addAffix(data, AffixType.SUFFIX, random);
                    default -> generator.addAffix(data, null, random);
                }
                return removed || effect == CurrencyType.Omen.Effect.FORCE_PREFIX || effect == CurrencyType.Omen.Effect.FORCE_SUFFIX;
            }
            default -> {
                used.operation().apply(data, generator, random);
                return false;
            }
        }
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

    /** 照星籤移除一條：最差的、只拿後綴、只拿前綴；星籤跟移除無關回傳 false（沒動）。 */
    private static boolean removeByOmen(final CurrencyType.Omen.Effect effect, final ItemData data, final ItemGenerator generator,
                                        final RandomGenerator random) {
        final List<RolledAffix> candidates = new ArrayList<>();
        switch (effect) {
            case REMOVE_LOWEST -> {
                // 階級（T 幾）最差的；一樣差的比那一階的物品等級需求（越低越差，照 PoE 的去劣）；還是一樣就隨機挑一條
                // （2026-10-16：以前一樣差時固定拿第一條，T3 暴擊率跟 T3 魔力偷取一起時拿掉的是暴擊率）
                final List<RolledAffix> worst = new ArrayList<>();
                int worstRank = -1;
                int worstLevel = Integer.MAX_VALUE;
                for (final RolledAffix rolled : data.affixes()) {
                    if (data.isFractured(rolled)) {
                        continue; // 2026-10-21：破裂的詞綴拿不掉
                    }
                    final Affix affix = generator.repository().affix(rolled.id());
                    final int rank = affix == null ? 99 : affix.rank(rolled.tier());
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
            default -> {
                return false;
            }
        }
    }

    // ---- 注入（蒸餾情緒 → 項鍊）----

    /** 注入一個核心要幾顆蒸餾情緒。 */
    public static final int INSTIL_COST = 3;
    /** 離天賦樹中心（切比雪夫距離）幾格以內用第幾階的情緒：≤7 第 1 階、≤11 第 2 階、≤15 第 3 階、更遠第 4 階。 */
    private static final int[] INSTIL_RINGS = {7, 11, 15};

    /**
     * 這個核心要用第幾階的蒸餾情緒（1 起；map_events.yml 的 delirium.emotions 照階級排）：
     * 離天賦樹中心越遠的核心越強，要越稀有的情緒。
     */
    public static int instilTier(final int x, final int y) {
        final int distance = Math.max(Math.abs(x), Math.abs(y));
        for (int i = 0; i < INSTIL_RINGS.length; i++) {
            if (distance <= INSTIL_RINGS[i]) {
                return i + 1;
            }
        }
        return INSTIL_RINGS.length + 1;
    }

    /** 把核心注入項鍊（原本注入的換掉）。呼叫端已經檢查過通貨與數量。 */
    public static void instil(final ItemData data, final String nodeId) {
        data.setInstilled(nodeId);
    }
}
