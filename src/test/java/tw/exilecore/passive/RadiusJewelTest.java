package tw.exilecore.passive;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：遺時珠寶（半徑珠寶，PoE2 的 Time-Lost 珠寶）。 */
class RadiusJewelTest {

    /** 一個珠寶孔在 (0,0)，周圍一圈小天賦、一個核心、一個遠一點的小天賦。 */
    static PassiveTree tree() {
        final String json = """
                {"version": 1, "starts": {"warrior": "s"}, "nodes": [
                  {"id": "s", "type": "start", "x": -3, "y": 0},
                  {"id": "sock", "type": "socket", "x": 0, "y": 0},
                  {"id": "a", "type": "small", "x": 1, "y": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "b", "type": "small", "x": -1, "y": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "c", "type": "small", "x": 0, "y": 2, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "n", "type": "notable", "x": 1, "y": 1, "stats": [{"stat": "damage", "type": "INCREASED", "value": 0.2}]},
                  {"id": "far", "type": "small", "x": 3, "y": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "s2", "type": "small", "x": -2, "y": 0}
                ]}
                """;
        return PassiveTree.load(new StringReader(json));
    }

    @Test
    void radiusAffixGrantsOncePerAllocatedSmallPassiveInRadius() {
        final PassiveTree tree = tree();
        final PassiveNode socket = tree.node("sock");
        final List<Modifier> jewel = List.of(
                new Modifier(StatKeys.RADIUS_PREFIX + "attr.str", ModType.FLAT, 2, Set.of(), "珠寶"),
                new Modifier("evasion", ModType.INCREASED, 0.1, Set.of(), "珠寶"));
        // 配了 a、b、n、far：半徑 2 格內的小天賦是 a、b（c 在 (0,2) 也算、但沒配；far 在 3 格外；n 是核心不算小天賦）
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, socket, Set.of("a", "b", "n", "far"), jewel);
        assertEquals(2, out.size());
        final Modifier str = out.stream().filter(m -> m.stat().equals("attr.str")).findFirst().orElseThrow();
        assertEquals(4, str.value(), 1e-9, "兩個小天賦各給 +2 力量");
        assertTrue(out.stream().anyMatch(m -> m.stat().equals("evasion") && m.value() == 0.1), "一般詞綴照給");
        // 沒配任何範圍內的小天賦：radius 的那條不給
        assertFalse(JewelSockets.radiusModifiers(tree, socket, Set.of("far"), jewel).stream().anyMatch(m -> m.stat().equals("attr.str")));
    }

    @Test
    void biggerRadiusReachesFurtherAndEffectScalesNodes() {
        final PassiveTree tree = tree();
        final PassiveNode socket = tree.node("sock");
        final List<Modifier> big = List.of(
                new Modifier(StatKeys.JEWEL_LOCAL_RADIUS, ModType.FLAT, 1, Set.of(), "珠寶"),
                new Modifier(StatKeys.RADIUS_PREFIX + "attr.str", ModType.FLAT, 2, Set.of(), "珠寶"),
                new Modifier(StatKeys.JEWEL_LOCAL_SMALL_EFFECT, ModType.INCREASED, 0.2, Set.of(), "珠寶"),
                new Modifier(StatKeys.JEWEL_LOCAL_NOTABLE_EFFECT, ModType.INCREASED, 0.25, Set.of(), "珠寶"));
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, socket, Set.of("a", "b", "c", "n", "far"), big);
        final Modifier str = out.stream().filter(m -> m.stat().equals("attr.str")).findFirst().orElseThrow();
        assertEquals(8, str.value(), 1e-9, "半徑 3：a、b、c、far 四個小天賦");
        // 小天賦效果 +20%：每個小天賦的 +10 生命多給 2；核心 +25%：增加 20% 傷害多給 5%
        final double life = out.stream().filter(m -> m.stat().equals("life.max")).mapToDouble(Modifier::value).sum();
        assertEquals(8, life, 1e-9);
        final double damage = out.stream().filter(m -> m.stat().equals("damage")).mapToDouble(Modifier::value).sum();
        assertEquals(0.05, damage, 1e-9);
        assertFalse(out.stream().anyMatch(m -> m.stat().startsWith("jewel.local.")), "珠寶自己的數值不進屬性表");
        assertEquals(3, JewelSockets.radiusOf(big), 1e-9);
    }

    @Test
    void timeLostBasesOnlyRollRadiusAffixes() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        int radiusBases = 0;
        for (final ItemBase base : repository.bases()) {
            if (!base.isRadiusJewel()) {
                continue;
            }
            radiusBases++;
            assertTrue(base.isJewel() && !base.isWearable());
            for (final Affix affix : repository.affixes()) {
                if (!affix.canSpawnOn(base)) {
                    continue;
                }
                assertTrue(affix.id().startsWith("jr_") || affix.id().startsWith("unveiled_jr_"), affix.id() + " 不是半徑珠寶的詞綴，卻出在 " + base.id());
                assertTrue(affix.mods().stream().allMatch(mod -> mod.stat().startsWith(StatKeys.RADIUS_PREFIX) || mod.stat().startsWith("jewel.local.")),
                        affix.id() + "：半徑珠寶的詞綴要寫 radius: 或 jewel.local.");
            }
        }
        assertEquals(4, radiusBases, "遺時赤星晶、翠星晶、蒼星晶、白星晶");
        for (final ItemBase base : repository.bases()) {
            if (base.isJewel() && !base.isRadiusJewel()) {
                for (final Affix affix : repository.affixes()) {
                    assertFalse(affix.id().startsWith("jr_") && affix.canSpawnOn(base), affix.id() + " 是半徑珠寶的詞綴，卻出在一般珠寶 " + base.id());
                }
            }
        }
    }
}
