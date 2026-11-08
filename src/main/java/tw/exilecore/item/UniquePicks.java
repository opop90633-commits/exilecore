package tw.exilecore.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 2026-11-04 照站長「妄想症（隨機配置天賦）、信仰稜鏡（隨機一個技能等級提升）都做出來」：傳奇上「隨機的那一個」是哪個。
 * <p>
 * 不另外存在物品上：照物品的 UUID 決定（同一件永遠是同樣的那幾個），從排序過的候選裡抽。候選由別的模組啟動時接上
 * （天賦樹：PassiveModule 接核心天賦；技能：SkillConfig 接玩家能學的技能）；沒接上（測試、還沒載入）時回傳空的。
 * 候選改了（天賦樹改版、技能增刪）抽到的可能會換，跟原作的「天賦樹改版重置」一樣。純資料，不依賴 Bukkit。
 */
public final class UniquePicks {

    /** 候選：代號（排序過）與顯示名稱。 */
    public record Pool(List<String> ids, Function<String, String> names) {
        public Pool {
            ids = ids.stream().sorted().distinct().toList();
        }
    }

    /** 核心天賦（妄想症）。 */
    public static final String NOTABLE = "notable";
    /** 玩家能學的主動技能（信仰稜鏡）。 */
    public static final String SKILL = "skill";
    /** 2026-11-07 照站長「瓦爾傳奇照原作」（熔肉之晶，PoE2「Random 1 Keystone Passive Skill [1,33]」）：天賦樹上的基石（PassiveModule 接上）。 */
    public static final String KEYSTONE = "keystone";
    /**
     * 2026-11-07（艾瑟琳的華儀，PoE2「as though it was also a [Random socketable equipment type]」）：魂核符文的部位代號
     * （{@link Runes#SLOT_KEYS} 去掉胸甲，加上軍用武器 weapon、法杖類 caster）。固定的，不用接。
     */
    public static final String CORE_SLOT = "core_slot";

    private static volatile Pool notables;
    private static volatile Pool skills;
    private static volatile Pool keystones;
    /** 2026-11-07：基石的說明行（顯示用；沒接上回傳 null）。 */
    private static volatile Function<String, List<String>> keystoneLines;

    /** 2026-11-07：{@link #CORE_SLOT} 的候選與名稱（手套照慣例是腿甲那一欄）。 */
    private static final java.util.Map<String, String> CORE_SLOT_NAMES = java.util.Map.of("head", "頭盔", "legs", "手套（腿甲）", "feet", "鞋子",
            "shield", "盾", "buckler", "輕盾", "focus", "法器", "sceptre", "權杖", "weapon", "軍用武器", "caster", "法杖類");
    private static final Pool CORE_SLOTS = new Pool(new ArrayList<>(CORE_SLOT_NAMES.keySet()), CORE_SLOT_NAMES::get);

    private UniquePicks() {
    }

    public static void setNotables(final Pool pool) {
        notables = pool;
    }

    public static void setSkills(final Pool pool) {
        skills = pool;
    }

    /** 2026-11-07（熔肉之晶）：接上基石的候選與說明行（PassiveModule 載入天賦樹時）。 */
    public static void setKeystones(final Pool pool, final Function<String, List<String>> lines) {
        keystones = pool;
        keystoneLines = lines;
    }

    /** 2026-11-08（譫妄精髓）：詞綴上決定抽哪個基石的變數。 */
    public static final String KEYSTONE_SEED = "seed";

    /** 2026-11-08（譫妄精髓）：第 value 個基石（value % 基石數，照代號排序）；候選沒接上回傳 null。 */
    public static String keystoneAt(final int value) {
        final Pool pool = keystones;
        if (pool == null || pool.ids().isEmpty()) {
            return null;
        }
        return pool.ids().get(Math.floorMod(value, pool.ids().size()));
    }

    /** 2026-11-08（譫妄精髓）：這件物品詞綴抽到的基石（local.keystone_pick；照詞綴順序）。 */
    public static List<String> affixKeystones(final ItemRepository repository, final ItemData data) {
        final List<String> out = new ArrayList<>();
        if (repository == null || data == null) {
            return out;
        }
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final AffixMod mod : affix.mods()) {
                if (tw.exilecore.item.unique.UniqueStats.LOCAL_KEYSTONE_PICK.equals(mod.stat())) {
                    // 抽第幾個看這條詞綴擲到的 seed（文字不顯示）；沒有 seed 的照物品 UUID
                    final Integer seed = rolled.vars().get(KEYSTONE_SEED);
                    final String id = keystoneAt(seed != null ? seed : data.uuid() == null ? 0 : data.uuid().hashCode());
                    if (id != null && !out.contains(id)) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    /** 2026-11-07：基石的說明行（沒接上、不認得回傳空的）。 */
    public static List<String> keystoneLines(final String id) {
        final Function<String, List<String>> lines = keystoneLines;
        final List<String> out = lines == null ? null : lines.apply(id);
        return out == null ? List.of() : out;
    }

    private static Pool pool(final String kind) {
        if (KEYSTONE.equals(kind)) {
            return keystones;
        }
        if (CORE_SLOT.equals(kind)) {
            return CORE_SLOTS;
        }
        return NOTABLE.equals(kind) ? notables : SKILL.equals(kind) ? skills : null;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（熔肉之晶，PoE2「Random 1 Keystone Passive Skill [1,33]」）：這顆珠寶配置的隨機基石。
     * 原本那條 {@link tw.exilecore.item.unique.VaalKeys#JEWEL_ALLOCATE_KEYSTONES}（n 個）抽前面的；瓦爾那條
     * {@link tw.exilecore.item.unique.VaalKeys#JEWEL_RANDOM_KEYSTONE}（m 個）接著抽，而且原本那條被栽培換掉了也先空出它的那一個——
     * 瓦爾抽到的一定跟原本那條（有的話、本來會有的話）不同。都照這顆的 UUID（同一顆永遠一樣）。候選沒接上回傳空的。
     */
    public static List<String> keystonePicks(final ItemData data, final List<tw.exilecore.stats.Modifier> mods) {
        double original = 0;
        double vaal = 0;
        for (final tw.exilecore.stats.Modifier mod : mods) {
            if (tw.exilecore.item.unique.VaalKeys.JEWEL_ALLOCATE_KEYSTONES.equals(mod.stat())) {
                original += mod.value();
            } else if (tw.exilecore.item.unique.VaalKeys.JEWEL_RANDOM_KEYSTONE.equals(mod.stat())) {
                vaal += mod.value();
            }
        }
        final int own = (int) Math.max(0, Math.round(original));
        final int extra = (int) Math.max(0, Math.round(vaal));
        if (own + extra == 0) {
            return List.of();
        }
        final int skip = extra > 0 ? Math.max(own, 1) : own; // 瓦爾的從這裡開始（原本那條至少佔一個）
        final List<String> drawn = picks(data, KEYSTONE, skip + extra);
        final List<String> out = new ArrayList<>(drawn.subList(0, Math.min(own, drawn.size())));
        if (drawn.size() > skip) {
            out.addAll(drawn.subList(skip, drawn.size()));
        }
        return out;
    }

    /** 這件物品抽到的 count 個（不重複）；候選沒接上回傳空的。 */
    public static List<String> picks(final ItemData data, final String kind, final int count) {
        final Pool pool = pool(kind);
        if (pool == null || pool.ids().isEmpty() || count <= 0 || data == null) {
            return List.of();
        }
        final String seed = (data.uuid() == null ? data.base() + ":" + data.ilvl() : data.uuid()) + ":" + kind;
        final java.util.Random random = new java.util.Random(seed.hashCode() * 31L + seed.length());
        final List<String> left = new ArrayList<>(pool.ids());
        final List<String> out = new ArrayList<>();
        while (out.size() < count && !left.isEmpty()) {
            out.add(left.remove(random.nextInt(left.size())));
        }
        return out;
    }

    /**
     * 2026-11-04 照站長「不敗亡者的隨機插槽詞綴補上」（PoE2 Morior Invictus：[3 Random Socket Modifiers]，poe2db 列的 10 種）：
     * 每一種「每個嵌著東西的插槽」給多少（範圍）。stats 是修飾器（數值是一個孔的）。
     */
    public record SocketMod(String id, String text, double min, double max, List<String> stats, tw.exilecore.stats.ModType type) {
        public SocketMod {
            stats = List.copyOf(stats);
        }
    }

    public static final List<SocketMod> SOCKET_MODS = List.of(
            new SocketMod("attributes", "每個鑲嵌的插槽使所有能力值 +{v}", 5, 7, List.of("attr.str", "attr.dex", "attr.int"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("elemental", "每個鑲嵌的插槽使全元素抗性 +{v}%", 8, 10, List.of("resist.fire", "resist.cold", "resist.lightning"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("life", "每個鑲嵌的插槽使最大生命 +{v}", 45, 60, List.of("life.max"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("mana", "每個鑲嵌的插槽使最大魔力 +{v}", 50, 60, List.of("mana.max"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("chaos", "每個鑲嵌的插槽使混沌抗性 +{v}%", 10, 13, List.of("resist.chaos"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("defences", "每個鑲嵌的插槽增加 {v}% 全域護甲、閃避與能量護盾", 9, 12, List.of("armour", "evasion", "es.max"), tw.exilecore.stats.ModType.INCREASED),
            new SocketMod("regen", "每個鑲嵌的插槽每秒回復 {v} 生命", 8, 12, List.of("life.regen"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("crit_taken", "每個鑲嵌的插槽使擊中你的暴擊傷害加成減少 {v}%", 15, 20, List.of("crit.bonus_taken"), tw.exilecore.stats.ModType.INCREASED),
            new SocketMod("spirit", "每個鑲嵌的插槽使精魂 +{v}", 10, 14, List.of("spirit.max"), tw.exilecore.stats.ModType.FLAT),
            new SocketMod("stun", "每個鑲嵌的插槽使暈眩門檻 +{v}", 70, 90, List.of("stun.threshold"), tw.exilecore.stats.ModType.FLAT));

    /** 一條擲好的插槽詞綴：哪一種、一個孔給多少。 */
    public record RolledSocketMod(SocketMod mod, int value) {
        public String text() {
            return mod.text().replace("{v}", Integer.toString(value));
        }

        /** 嵌著 sockets 個東西時給的修飾器。 */
        public List<tw.exilecore.stats.Modifier> modifiers(final int sockets, final String source) {
            final double sign = "crit_taken".equals(mod.id()) ? -1 : 1;
            final double amount = mod.type() == tw.exilecore.stats.ModType.INCREASED ? sign * value / 100.0 * sockets : sign * value * sockets;
            final List<tw.exilecore.stats.Modifier> out = new ArrayList<>();
            for (final String stat : mod.stats()) {
                out.add(new tw.exilecore.stats.Modifier(stat, mod.type(), amount, java.util.Set.of(), source));
            }
            return out;
        }
    }

    /** 2026-11-04（不敗亡者）：這件抽到的 count 種插槽詞綴（不重複；種類與數值照物品的 UUID 決定）。 */
    public static List<RolledSocketMod> socketMods(final ItemData data, final int count) {
        if (data == null || count <= 0) {
            return List.of();
        }
        final String seed = (data.uuid() == null ? data.base() + ":" + data.ilvl() : data.uuid()) + ":socket";
        final java.util.Random random = new java.util.Random(seed.hashCode() * 31L + seed.length());
        final List<SocketMod> left = new ArrayList<>(SOCKET_MODS);
        final List<RolledSocketMod> out = new ArrayList<>();
        while (out.size() < count && !left.isEmpty()) {
            final SocketMod mod = left.remove(random.nextInt(left.size()));
            out.add(new RolledSocketMod(mod, (int) mod.min() + random.nextInt((int) (mod.max() - mod.min()) + 1)));
        }
        return out;
    }

    /** 顯示名稱（沒接上或不認得回傳代號）。 */
    public static String name(final String kind, final String id) {
        final Pool pool = pool(kind);
        final String name = pool == null ? null : pool.names().apply(id);
        return name == null ? id : name;
    }
}
