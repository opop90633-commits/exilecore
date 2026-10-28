package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-28 照 PoE2 0.5 的昇華「石拳之道」（Way of the Stonefist，武聖）：裝備的手套在穿著時基底變成磐石之拳（Fists of Stone），
 * 詞綴各自換成更強的相關詞綴（原作的 HandWraps 詞綴，一條換一條）。我們沒有手套欄，PoE2 的手套一律是腿甲（uniques.yml、bases 的慣例），
 * 所以這裡變的是腿甲。
 * <p>
 * 磐石之拳（RePoE base_items：FourGlovesDexIntAscendancy）：需求等級 1、沒有能力值需求、沒有基礎防禦，
 * 固定詞綴「每一玩家等級 +3 閃避值」「每一玩家等級 +1 最大能量護盾」（{@link #IMPLICIT_EVASION}、{@link #IMPLICIT_ES}）。
 * 原本那件的基底防禦、本地防禦詞綴、固定詞綴都不算；嵌著的符文照常。
 * <p>
 * 資料在 data/stonefist.yml：
 * <ul>
 *   <li>{@code affixes}：我們的詞綴代號 → 換成的那條（text、tiers、mods 跟 affixes/*.yml 一樣；tiers 的 tier 跟原本那條的階級代號對應）。</li>
 *   <li>{@code uniques}：傳奇代號 → 磐石之拳版本的每一行（跟 uniques.yml 的 mods 一樣，另外寫 {@code from}：接原本第幾行的擲骰，0 = 沒有對應）。</li>
 * </ul>
 * 數值照原本那條在範圍裡的位置換到新的範圍（擲到上限的換過去也是上限）；原本是固定值的取新範圍的中間。
 * 沒寫在資料裡的詞綴（腐化詞綴、沒有原作對應的揭露詞綴）照原本的生效（原作的「Explicit Modifiers」才會換）。
 * <p>
 * 這次之前（2026-10-27）：腿甲的防禦換成每角色等級 +3 閃避、+1 能量護盾，詞綴（固定詞綴、符文以外）的數值增加 50%。
 */
public final class Stonefist {

    public static final String FILE = "stonefist.yml";
    /** 修飾器的來源名稱。 */
    public static final String SOURCE = "磐石之拳";
    /** 磐石之拳的固定詞綴：每一玩家等級 +3 閃避值、+1 最大能量護盾。 */
    public static final int IMPLICIT_EVASION = 3;
    public static final int IMPLICIT_ES = 1;
    /** 磐石之拳的需求等級（原作 local level requirement override 1）。 */
    public static final int LEVEL_REQUIREMENT = 1;
    /**
     * stonefist.yml 裡「每一玩家等級 +x 閃避值／最大能量護盾」（原作 local_hand_wraps_evasion_rating_per_level 這類本地詞綴）：
     * 換算時乘上角色等級，變成閃避值、最大能量護盾的固定值（不進屬性表）。
     */
    public static final String EVASION_PER_LEVEL = "local.hand_wraps_evasion_per_level";
    public static final String ES_PER_LEVEL = "local.hand_wraps_es_per_level";
    /** stonefist.yml 自己才有的鍵（資料檢查用）。 */
    public static final Set<String> PER_LEVEL = Set.of(EVASION_PER_LEVEL, ES_PER_LEVEL);

    /** 傳奇的一行：接原本第幾行（1 起算，0 = 沒有對應）、換成的詞綴（只有一階）。 */
    public record Line(int from, Affix affix) {
    }

    /** stonefist.yml。 */
    public record Data(Map<String, Affix> affixes, Map<String, List<Line>> uniques) {
        public static final Data EMPTY = new Data(Map.of(), Map.of());

        public Data {
            affixes = Map.copyOf(affixes);
            uniques = Map.copyOf(uniques);
        }
    }

    private Stonefist() {
    }

    /** 讀 data/stonefist.yml（沒有這個檔回傳空的：不換詞綴，只有磐石之拳的固定詞綴）。 */
    public static Data load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            return Data.EMPTY;
        }
        return parse(YamlNode.load(file));
    }

    static Data parse(final YamlNode root) {
        final Map<String, Affix> affixes = new LinkedHashMap<>();
        final YamlNode affixRoot = root.node("affixes");
        for (final String id : affixRoot.keys()) {
            final YamlNode node = affixRoot.node(id);
            final List<AffixTier> tiers = new ArrayList<>();
            for (final YamlNode tierNode : node.list("tiers")) {
                tiers.add(AffixTier.parse(tierNode));
            }
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException(node.path() + "：至少要有一個階級");
            }
            tiers.sort(Comparator.comparingInt(AffixTier::tier));
            affixes.put(id, new Affix(SOURCE + "." + id, AffixType.SUFFIX, id, Set.of(UniqueItem.SPAWN_TAG), Set.of(), Set.of(), List.of(),
                    node.requireString("text"), tiers, mods(node)));
        }
        final Map<String, List<Line>> uniques = new LinkedHashMap<>();
        final YamlNode uniqueRoot = root.node("uniques");
        for (final String id : uniqueRoot.keys()) {
            final List<Line> lines = new ArrayList<>();
            int index = 0;
            for (final YamlNode lineNode : uniqueRoot.list(id)) {
                index++;
                final Map<String, int[]> vars = new LinkedHashMap<>();
                for (final String key : lineNode.keys()) {
                    if (!key.equals("text") && !key.equals("stats") && !key.equals("from")) {
                        vars.put(key, lineNode.range(key));
                    }
                }
                final List<AffixMod> mods = new ArrayList<>();
                for (final YamlNode stat : lineNode.list("stats")) {
                    mods.add(AffixMod.parse(stat));
                }
                if (mods.isEmpty()) {
                    throw new IllegalArgumentException(lineNode.path() + "：至少要有一條 stats");
                }
                final AffixTier tier = new AffixTier(1, 1, 1, vars.isEmpty() ? Map.of("value", new int[] {0, 0}) : vars);
                lines.add(new Line(Math.max(0, lineNode.integer("from", 0)),
                        new Affix(SOURCE + "." + id + "." + index, AffixType.SUFFIX, id, Set.of(UniqueItem.SPAWN_TAG), Set.of(), Set.of(),
                                List.of(), lineNode.requireString("text"), List.of(tier), mods)));
            }
            uniques.put(id, List.copyOf(lines));
        }
        return new Data(affixes, uniques);
    }

    private static List<AffixMod> mods(final YamlNode node) {
        final List<AffixMod> mods = new ArrayList<>();
        for (final YamlNode modNode : node.list("mods")) {
            mods.add(AffixMod.parse(modNode));
        }
        if (mods.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一條 mods");
        }
        return mods;
    }

    /** 一條換好的詞綴：換成的詞綴（null = 照原本的）、用哪一階、數值、原本那條。 */
    public record Converted(Affix affix, int tier, Map<String, Integer> vars, Affix original, RolledAffix rolled) {

        /** 顯示用：換成的（或原本的）那條。 */
        public Affix shown() {
            return affix == null ? original : affix;
        }
    }

    /** 這件物品穿在石拳之道的角色身上時，每一條詞綴換成什麼（照物品上的順序；傳奇多出來的行排在最後）。 */
    public static List<Converted> convert(final ItemRepository repository, final ItemData data) {
        final List<Converted> out = new ArrayList<>();
        if (!data.identified()) {
            return out;
        }
        final Data stonefist = repository.stonefist();
        final List<Line> uniqueLines = data.unique() == null ? null : stonefist.uniques().get(data.unique());
        final String uniquePrefix = data.unique() == null ? null : UniqueItem.AFFIX_PREFIX + data.unique() + ".";
        for (final RolledAffix rolled : data.affixes()) {
            final Affix original = repository.affix(rolled.id());
            if (original == null) {
                continue;
            }
            if (uniqueLines != null && rolled.id().startsWith(uniquePrefix)) {
                final int index = parseIndex(rolled.id().substring(uniquePrefix.length()));
                for (final Line line : uniqueLines) {
                    if (line.from() == index) {
                        out.add(new Converted(line.affix(), 1, carry(original, rolled, line.affix(), 1), original, rolled));
                    }
                }
                continue;
            }
            final Affix replacement = stonefist.affixes().get(rolled.id());
            if (replacement != null && !replacement.ranges(rolled.tier()).isEmpty()) {
                out.add(new Converted(replacement, rolled.tier(), carry(original, rolled, replacement, rolled.tier()), original, rolled));
            } else {
                out.add(new Converted(null, rolled.tier(), rolled.vars(), original, rolled)); // 照原本的（腐化詞綴、沒有對應的揭露詞綴）
            }
        }
        if (uniqueLines != null) {
            for (final Line line : uniqueLines) {
                if (line.from() == 0) {
                    out.add(new Converted(line.affix(), 1, midpoint(line.affix().ranges(1)), null, null));
                }
            }
        }
        return out;
    }

    private static int parseIndex(final String text) {
        try {
            return Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** 原本那條在範圍裡的位置（0～1；固定值是 0.5）。 */
    static double position(final Affix original, final RolledAffix rolled) {
        final Map<String, int[]> ranges = original.ranges(rolled.tier());
        for (final Map.Entry<String, int[]> entry : ranges.entrySet()) {
            final int[] range = entry.getValue();
            final Integer value = rolled.vars().get(entry.getKey());
            if (range == null || range.length != 2 || range[0] == range[1] || value == null) {
                continue;
            }
            final int lo = Math.min(range[0], range[1]);
            final int hi = Math.max(range[0], range[1]);
            final double t = (value - lo) / (double) (hi - lo);
            // 「減少 x%」這種寫成 [-20, -10] 的：照絕對值大小算位置（數字越大越強）
            return Math.max(0, Math.min(1, range[0] > range[1] ? 1 - t : t));
        }
        return 0.5;
    }

    private static Map<String, Integer> carry(final Affix original, final RolledAffix rolled, final Affix replacement, final int tier) {
        final double t = position(original, rolled);
        final Map<String, Integer> vars = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : replacement.ranges(tier).entrySet()) {
            vars.put(entry.getKey(), at(entry.getValue(), t));
        }
        return vars;
    }

    private static Map<String, Integer> midpoint(final Map<String, int[]> ranges) {
        final Map<String, Integer> vars = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : ranges.entrySet()) {
            vars.put(entry.getKey(), at(entry.getValue(), 0.5));
        }
        return vars;
    }

    /** 範圍 [a, b] 裡位置 t 的整數（寫成 [大, 小] 的反過來數，跟原作「(7-6)% less」一樣：越前面越強）。 */
    static int at(final int[] range, final double t) {
        return (int) Math.round(range[0] + (range[1] - range[0]) * t);
    }

    /**
     * 穿在石拳之道的角色身上時，這件給屬性表的修飾器（取代 {@link ItemStats#modifiers}）：磐石之拳的固定詞綴、換好的詞綴、嵌著的符文。
     *
     * @param level 角色等級（「每一玩家等級」）
     */
    public static List<Modifier> modifiers(final ItemRepository repository, final ItemBase base, final ItemData data, final int level) {
        final List<Modifier> list = new ArrayList<>();
        final int lv = Math.max(1, level);
        double evasion = IMPLICIT_EVASION * lv;
        double es = IMPLICIT_ES * lv;
        for (final Converted converted : convert(repository, data)) {
            if (converted.affix() == null) {
                // 照原本的：本地的防禦詞綴跟著原本的基底一起不見了
                for (final AffixMod mod : converted.original().mods()) {
                    if (mod.isLocal() || ItemStats.isArmourLocal(base, mod)) {
                        continue;
                    }
                    list.add(mod.toModifier(converted.vars(), SOURCE + " " + converted.original().id()));
                }
                continue;
            }
            for (final AffixMod mod : converted.affix().mods()) {
                final Modifier modifier = mod.toModifier(converted.vars(), SOURCE + " " + converted.affix().id());
                if (EVASION_PER_LEVEL.equals(mod.stat())) {
                    evasion += modifier.value() * lv;
                } else if (ES_PER_LEVEL.equals(mod.stat())) {
                    es += modifier.value() * lv;
                } else {
                    list.add(modifier);
                }
            }
        }
        list.add(0, Modifier.flat(StatKeys.EVASION, evasion, SOURCE));
        list.add(1, Modifier.flat(StatKeys.ES_MAX, es, SOURCE));
        if (base != null) {
            list.addAll(Runes.modifiers(repository, base, data));
        }
        return list;
    }

    /** 磐石之拳的需求：等級 1（能力值需求另外由 AscBuild.ignoresRequirements 不看）。 */
    public static ItemBase.Requirements requirements(final ItemBase.Requirements original) {
        return new ItemBase.Requirements(LEVEL_REQUIREMENT, original.str(), original.dex(), original.intelligence());
    }

    /** 物品說明多加的幾行（MiniMessage）：穿上之後變成什麼。 */
    public static List<String> lines(final ItemRepository repository, final ItemData data, final int level) {
        final List<String> lines = new ArrayList<>();
        final int lv = Math.max(1, level);
        lines.add("<#c8a165>石拳之道：穿上時變成磐石之拳（需求等級 1、不需要能力值）");
        lines.add("<#8888ff>每一玩家等級 +3 閃避值（+" + IMPLICIT_EVASION * lv + "）");
        lines.add("<#8888ff>每一玩家等級 +1 最大能量護盾（+" + IMPLICIT_ES * lv + "）");
        for (final Converted converted : convert(repository, data)) {
            final Affix affix = converted.shown();
            lines.add("<#8888ff>" + Affix.substituteWithRanges(affix.text(), converted.vars(), affix.ranges(converted.tier())));
        }
        return lines;
    }

    /** 這條是不是磐石之拳換上去的（{@link #modifiers} 的來源）。 */
    public static boolean isStonefist(final Modifier modifier) {
        return modifier.source() != null && modifier.source().startsWith(SOURCE);
    }
}
