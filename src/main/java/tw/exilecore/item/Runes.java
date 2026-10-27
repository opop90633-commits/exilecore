package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 符文（2026-10，照 PoE2 的 Rune）：裝備有固定幾個符文孔，把符文（通貨）用在裝備上就嵌進一個空孔，
 * 照裝備是武器還是防具給不同的加成。嵌了拿不下來（只能用新的裝備）。定義在 data/runes.yml。純資料，不依賴 Bukkit。
 * <p>
 * 2026-10-26 照 PoE2 0.5：13 個家族 × 4 階（次級、普通、高階、完美），每一顆分三類加成：武器（weapon，PoE2 的 Martial Weapon）、
 * 法杖類（caster：法杖、長杖、權杖，PoE2 的 Wand or Staff；沒寫照 weapon）、防具與副手（armour）。一條加成可以有好幾個修飾器（mods）。
 * 防具上的護甲／閃避／能量護盾增加是本地的（跟防具詞綴一樣算進那件的數值，見 {@link ItemStats}）。
 * <p>
 * 符文孔：雙手武器 2、單手武器 1、胸甲 2、頭盔／褲子／鞋子／副手 1、飾品與地圖、珠寶沒有。
 */
public final class Runes {

    public static final String FILE = "runes.yml";

    /** 一條加成：說明文字與修飾器（2026-10-26：可以好幾個，例如附加傷害的最小、最大）。 */
    public record Line(String text, List<AffixMod> mods) {
        public Line {
            mods = List.copyOf(mods);
        }

        public Line(final String text, final AffixMod mod) {
            this(text, List.of(mod));
        }

        /** 第一個修飾器（舊的寫法一條只有一個）。 */
        public AffixMod mod() {
            return mods.getFirst();
        }
    }

    /**
     * 一種符文。caster：嵌在法杖類（有 caster 標籤：法杖、長杖、權杖）上的加成，沒寫照 weapon（2026-10-26）。
     * level：需求等級（PoE2 符文的 Requires Level；也是從幾級的怪物開始掉，0 = 沒寫）。
     * bondedWeapon／bondedCaster／bondedArmour：2026-10-27 照 PoE2 0.5 的羈絆詞綴（Bonded），只有昇華・聖賢智慧（Wisdom of the Maji）
     * 才有效果（{@link #bondedModifiers}）；法杖類沒寫就沒有（不照武器的）。
     */
    public record Rune(String id, String name, List<Line> weapon, List<Line> caster, List<Line> armour, int level,
                       List<Line> bondedWeapon, List<Line> bondedCaster, List<Line> bondedArmour) {
        public Rune {
            weapon = List.copyOf(weapon);
            caster = caster == null || caster.isEmpty() ? weapon : List.copyOf(caster);
            armour = List.copyOf(armour);
            bondedWeapon = bondedWeapon == null ? List.of() : List.copyOf(bondedWeapon);
            bondedCaster = bondedCaster == null ? List.of() : List.copyOf(bondedCaster);
            bondedArmour = bondedArmour == null ? List.of() : List.copyOf(bondedArmour);
        }

        public Rune(final String id, final String name, final List<Line> weapon, final List<Line> caster, final List<Line> armour, final int level) {
            this(id, name, weapon, caster, armour, level, List.of(), List.of(), List.of());
        }

        public Rune(final String id, final String name, final List<Line> weapon, final List<Line> armour) {
            this(id, name, weapon, List.of(), armour, 0);
        }

        /** 嵌在這種基底上的加成。 */
        public List<Line> on(final ItemBase base) {
            if (!base.isWeapon()) {
                return armour;
            }
            return base.tags().contains(CASTER_TAG) ? caster : weapon;
        }

        /** 2026-10-27：嵌在這種基底上的羈絆詞綴（只有聖賢智慧才有效果）。 */
        public List<Line> bondedOn(final ItemBase base) {
            if (!base.isWeapon()) {
                return bondedArmour;
            }
            return base.tags().contains(CASTER_TAG) ? bondedCaster : bondedWeapon;
        }
    }

    /** 法杖類武器的標籤（法杖、長杖、權杖；PoE2 符文的 Wand or Staff 那一類）。 */
    public static final String CASTER_TAG = "caster";

    private Runes() {
    }

    /** 這件基底有幾個符文孔。 */
    public static int slots(final ItemBase base) {
        if (base == null || !base.isWearable() || base.isJewellery()) {
            return 0;
        }
        if (base.isWeapon()) {
            return base.tags().contains("two_hand") ? 2 : 1;
        }
        return base.slot() == ItemSlot.CHEST ? 2 : 1;
    }

    /**
     * 2026-10-26 照 PoE2 的工匠石（Artificer's Orb：武器、法杖、長杖、防具）：這種基底的符文孔是用工匠石一個一個加的。
     * 權杖、箭袋不行（PoE2 不能加孔），那些照舊（基底有孔就全開）。
     */
    public static boolean artificerTarget(final ItemBase base) {
        return slots(base) > 0 && !base.tags().contains("sceptre") && !base.isQuiver();
    }

    /** 這件物品有幾個符文孔：傳奇寫了 rune_sockets 照傳奇（2026-10），不然照基底；再加蝕變石多開的（2026-10-21，可以超過上限）。 */
    public static int slots(final ItemRepository repository, final ItemBase base, final ItemData data) {
        final int extra = data == null ? 0 : data.corruptSockets();
        if (repository != null && data != null && data.unique() != null) {
            final int own = repository.uniqueRuneSockets(data.unique());
            if (own >= 0) {
                return own + extra;
            }
        }
        // 2026-10-26：工匠石拿掉了，符文孔照基底全開（中間那段時間做出來、記著 0 孔的也一樣全開）
        return slots(base) + extra;
    }

    static Map<String, Rune> load(final Path dataFolder) throws IOException {
        final Map<String, Rune> map = new LinkedHashMap<>();
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            return map;
        }
        final YamlNode root = YamlNode.load(file).node("runes");
        for (final String id : root.keys()) {
            final YamlNode node = root.node(id);
            final YamlNode bonded = node.node("bonded"); // 2026-10-27 照 PoE2：羈絆詞綴（可以不寫）
            map.put(id, new Rune(id, node.requireString("name"), lines(node, "weapon"),
                    node.has("caster") ? lines(node, "caster") : List.of(), lines(node, "armour"), Math.max(0, node.integer("level", 0)),
                    optionalLines(bonded, "weapon"), optionalLines(bonded, "caster"), optionalLines(bonded, "armour")));
        }
        return map;
    }

    /** 2026-10-27：可以沒有的一組加成（羈絆詞綴）。 */
    private static List<Line> optionalLines(final YamlNode node, final String key) {
        return node == null || !node.has(key) ? List.of() : lines(node, key);
    }

    private static List<Line> lines(final YamlNode node, final String key) {
        final List<Line> list = new ArrayList<>();
        for (final YamlNode child : node.list(key)) {
            if (child.has("mods")) { // 2026-10-26：一條加成好幾個修飾器 { text, mods: [ {stat…}, … ] }
                final List<AffixMod> mods = new ArrayList<>();
                for (final YamlNode mod : child.list("mods")) {
                    mods.add(AffixMod.parse(mod));
                }
                list.add(new Line(child.requireString("text"), mods));
            } else {
                list.add(new Line(child.requireString("text"), AffixMod.parse(child)));
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "." + key + "：至少要一條（{ text, stat, type, value }）");
        }
        return list;
    }

    /**
     * 符文效果的倍率：傳奇寫的 rune_effect，加上這件物品自己的「嵌在這件物品上的符文效果增加 x%」詞綴
     * （2026-10-26，淵座束帶 local.rune_effect；擲出來的數值）。
     */
    static double effect(final ItemRepository repository, final ItemData data) {
        double effect = 1 + (data.unique() == null || repository == null ? 0 : repository.uniqueRuneEffect(data.unique()));
        if (repository != null && data.identified()) {
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                if (affix == null) {
                    continue;
                }
                for (final AffixMod mod : affix.mods()) {
                    if (tw.exilecore.item.unique.UniqueStats.LOCAL_RUNE_EFFECT.equals(mod.stat())) {
                        effect += mod.toModifier(rolled.vars(), "").value();
                    }
                }
            }
        }
        return Math.max(0, effect);
    }

    /** 嵌在武器上、改武器自己數值的符文修飾器（2026-10-17，LocalMods 用；已乘上傳奇的符文效果增加）。 */
    static List<Modifier> weaponLocal(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty() || base == null || !base.isWeapon()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double effect = effect(repository, data);
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.on(base)) {
                for (final AffixMod mod : line.mods()) {
                    if (LocalMods.isWeaponLocal(mod)) {
                        final Modifier modifier = mod.toModifier(Map.of(), "符文 " + rune.name());
                        list.add(effect == 1 ? modifier
                                : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                    }
                }
            }
        }
        return list;
    }

    /** 嵌著的符文給的修飾器（武器本地的不算，見 {@link #weaponLocal}）。 */
    public static List<Modifier> modifiers(final ItemRepository repository, final ItemBase base, final ItemData data) { // 2026-10-26：昇華・聖賢智慧也要讀（public）
        if (data.runes().isEmpty()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double effect = effect(repository, data); // 傳奇的符文效果增加
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.on(base)) {
                for (final AffixMod mod : line.mods()) {
                    if (base.isWeapon() && LocalMods.isWeaponLocal(mod)) {
                        continue; // 2026-10-17：武器本地的（增加 x% 物理傷害、攻擊速度）算進武器面板（LocalMods）
                    }
                    if (!base.isWeapon() && (ItemStats.isArmourLocal(base, mod) || isDefenceIncrease(mod))) {
                        continue; // 2026-10-26：防具本地的（增加 x% 護甲、閃避、能量護盾）算進那件防具的數值（ItemStats）；那件沒有的那種防禦就沒效果
                    }
                    final Modifier modifier = mod.toModifier(Map.of(), "符文 " + rune.name());
                    list.add(effect == 1 ? modifier
                            : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                }
            }
        }
        return list;
    }

    /**
     * 2026-10-27 照 PoE2 0.5（昇華・聖賢智慧：Gain the benefits of Bonded modifiers on Runes and Idols）：嵌著的符文的羈絆詞綴給的修飾器
     * （都不是本地的，直接加在角色身上；已乘上傳奇的符文效果增加）。沒有聖賢智慧的人不會呼叫這個。
     */
    public static List<Modifier> bondedModifiers(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty() || base == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double effect = effect(repository, data);
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.bondedOn(base)) {
                for (final AffixMod mod : line.mods()) {
                    final Modifier modifier = mod.toModifier(Map.of(), "羈絆：" + rune.name());
                    list.add(effect == 1 ? modifier
                            : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                }
            }
        }
        return list;
    }

    /** 2026-10-26：嵌在防具上、改那件防具自己數值的符文修飾器（護甲／閃避／能量護盾的增加；已乘上傳奇的符文效果增加）。 */
    static double armourLocalSum(final ItemRepository repository, final ItemBase base, final ItemData data, final String stat,
                                 final tw.exilecore.stats.ModType type) {
        if (data.runes().isEmpty() || base == null || base.isWeapon()) {
            return 0;
        }
        final double effect = effect(repository, data); // 2026-10-27：跟其他符文加成一樣算上 local.rune_effect 詞綴；這次之前只算傳奇的 rune_effect
        double total = 0;
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.armour()) {
                for (final AffixMod mod : line.mods()) {
                    if (stat.equals(mod.stat()) && mod.type() == type && ItemStats.isArmourLocal(base, mod)) {
                        total += mod.toModifier(Map.of(), "").value() * effect;
                    }
                }
            }
        }
        return total;
    }

    /** 「增加 x% 護甲／閃避／能量護盾」（沒有標籤的 INCREASED）：嵌在防具上是本地的。 */
    private static boolean isDefenceIncrease(final AffixMod mod) {
        return mod.type() == tw.exilecore.stats.ModType.INCREASED && mod.tags().isEmpty()
                && (tw.exilecore.stats.StatKeys.ARMOUR.equals(mod.stat()) || tw.exilecore.stats.StatKeys.EVASION.equals(mod.stat())
                || tw.exilecore.stats.StatKeys.ES_MAX.equals(mod.stat()));
    }
}
