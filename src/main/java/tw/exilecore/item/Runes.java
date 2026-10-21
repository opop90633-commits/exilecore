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
 * 符文孔：雙手武器 2、單手武器 1、胸甲 2、頭盔／褲子／鞋子／副手 1、飾品與地圖、珠寶沒有。
 */
public final class Runes {

    public static final String FILE = "runes.yml";

    /** 一條加成：說明文字與修飾器。 */
    public record Line(String text, AffixMod mod) {
    }

    /** 一種符文。 */
    public record Rune(String id, String name, List<Line> weapon, List<Line> armour) {
        public Rune {
            weapon = List.copyOf(weapon);
            armour = List.copyOf(armour);
        }

        /** 嵌在這種基底上的加成。 */
        public List<Line> on(final ItemBase base) {
            return base.isWeapon() ? weapon : armour;
        }
    }

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

    /** 這件物品有幾個符文孔：傳奇寫了 rune_sockets 照傳奇（2026-10），不然照基底；再加蝕變石多開的（2026-10-21，可以超過上限）。 */
    public static int slots(final ItemRepository repository, final ItemBase base, final ItemData data) {
        final int extra = data == null ? 0 : data.corruptSockets();
        if (repository != null && data != null && data.unique() != null) {
            final int own = repository.uniqueRuneSockets(data.unique());
            if (own >= 0) {
                return own + extra;
            }
        }
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
            map.put(id, new Rune(id, node.requireString("name"), lines(node, "weapon"), lines(node, "armour")));
        }
        return map;
    }

    private static List<Line> lines(final YamlNode node, final String key) {
        final List<Line> list = new ArrayList<>();
        for (final YamlNode child : node.list(key)) {
            list.add(new Line(child.requireString("text"), AffixMod.parse(child)));
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "." + key + "：至少要一條（{ text, stat, type, value }）");
        }
        return list;
    }

    /** 嵌在武器上、改武器自己數值的符文修飾器（2026-10-17，LocalMods 用；已乘上傳奇的符文效果增加）。 */
    static List<Modifier> weaponLocal(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty() || base == null || !base.isWeapon()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double effect = 1 + (data.unique() == null ? 0 : repository.uniqueRuneEffect(data.unique()));
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.weapon()) {
                if (LocalMods.isWeaponLocal(line.mod())) {
                    final Modifier modifier = line.mod().toModifier(Map.of(), "符文 " + rune.name());
                    list.add(effect == 1 ? modifier
                            : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
                }
            }
        }
        return list;
    }

    /** 嵌著的符文給的修飾器（武器本地的不算，見 {@link #weaponLocal}）。 */
    static List<Modifier> modifiers(final ItemRepository repository, final ItemBase base, final ItemData data) {
        if (data.runes().isEmpty()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final double effect = 1 + (data.unique() == null ? 0 : repository.uniqueRuneEffect(data.unique())); // 傳奇的符文效果增加
        for (final String id : data.runes()) {
            final Rune rune = repository.rune(id);
            if (rune == null) {
                continue;
            }
            for (final Line line : rune.on(base)) {
                if (base.isWeapon() && LocalMods.isWeaponLocal(line.mod())) {
                    continue; // 2026-10-17：武器本地的（增加 x% 物理傷害、攻擊速度）算進武器面板（LocalMods）
                }
                final Modifier modifier = line.mod().toModifier(Map.of(), "符文 " + rune.name());
                list.add(effect == 1 ? modifier
                        : new Modifier(modifier.stat(), modifier.type(), modifier.value() * effect, modifier.tags(), modifier.source()));
            }
        }
        return list;
    }
}
