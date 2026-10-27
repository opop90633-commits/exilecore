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

/**
 * 2026-10：遺時珠寶（半徑珠寶，PoE2 的 Time-Lost 珠寶）。
 * 2026-10-26 照 PoE2：半徑用原作座標的距離（小 1000、中 1150、大 1300），詞綴分「範圍內的小天賦也各給」與「範圍內的核心天賦也各給」，
 * 拿掉最多算 15 個小天賦的上限。這次之前的測試是格子半徑（2 格、+1、+2）與上限 15。
 */
class RadiusJewelTest {

    /**
     * 一個珠寶孔在原作座標 (0,0)：a、b 在 500 遠（小）、c 在 1100（中才到）、核心 n 在約 849、far 在 1250（大才到）、
     * 核心 n2 在 1400（只有永恆珠寶的 1500 到）、基石 k 在 1450。
     */
    static PassiveTree tree() {
        final String json = """
                {"version": 1, "starts": {"warrior": "s"}, "nodes": [
                  {"id": "s", "type": "start", "x": -9, "y": 0, "px": -3000, "py": 0},
                  {"id": "sock", "type": "socket", "x": 0, "y": 0, "px": 0, "py": 0},
                  {"id": "a", "type": "small", "x": 1, "y": 0, "px": 500, "py": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "b", "type": "small", "x": -1, "y": 0, "px": -500, "py": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "c", "type": "small", "x": 0, "y": 2, "px": 0, "py": 1100, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "n", "type": "notable", "x": 1, "y": 1, "px": 600, "py": 600, "stats": [{"stat": "damage", "type": "INCREASED", "value": 0.2}]},
                  {"id": "far", "type": "small", "x": 3, "y": 0, "px": 1250, "py": 0, "stats": [{"stat": "life.max", "type": "FLAT", "value": 10}]},
                  {"id": "n2", "type": "notable", "x": 0, "y": -3, "px": 0, "py": -1400, "stats": [{"stat": "armour", "type": "INCREASED", "value": 0.3}]},
                  {"id": "k", "type": "keystone", "x": -3, "y": -1, "px": -1450, "py": 0, "stats": [{"stat": "flag.iron_reflexes", "type": "FLAT", "value": 1}]},
                  {"id": "attr", "type": "small", "x": 2, "y": 2, "px": 300, "py": 300, "stats": [{"stat": "attr.choice", "type": "FLAT", "value": 5}]},
                  {"id": "s2", "type": "small", "x": -2, "y": 0, "px": -5000, "py": 0}
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
        // 配了 a、b、n、far：半徑 1000 內的小天賦是 a、b（c 在 1100 外；far 在 1250 外；n 是核心不算小天賦）
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, socket, Set.of("a", "b", "n", "far"), jewel);
        assertEquals(2, out.size());
        final Modifier str = out.stream().filter(m -> m.stat().equals("attr.str")).findFirst().orElseThrow();
        assertEquals(4, str.value(), 1e-9, "兩個小天賦各給 +2 力量");
        assertTrue(out.stream().anyMatch(m -> m.stat().equals("evasion") && m.value() == 0.1), "一般詞綴照給");
        // 沒配任何範圍內的小天賦：radius 的那條不給
        assertFalse(JewelSockets.radiusModifiers(tree, socket, Set.of("far"), jewel).stream().anyMatch(m -> m.stat().equals("attr.str")));
    }

    /** 2026-10-26 照 PoE2：「範圍內的核心天賦也各給」只算範圍內已配置的核心。 */
    @Test
    void notableRadiusAffixCountsAllocatedNotables() {
        final PassiveTree tree = tree();
        final List<Modifier> jewel = List.of(
                new Modifier(JewelSockets.RADIUS_NOTABLE_PREFIX + "attack.speed", ModType.INCREASED, 0.02, Set.of(), "珠寶"),
                new Modifier(StatKeys.JEWEL_LOCAL_RADIUS, ModType.FLAT, 300, Set.of(), "珠寶"));
        // 半徑 1300：核心 n（849）在、n2（1400）不在；小天賦 a、b 不算
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, tree.node("sock"), Set.of("a", "b", "n", "n2"), jewel);
        assertEquals(0.02, out.stream().filter(m -> m.stat().equals("attack.speed")).mapToDouble(Modifier::value).sum(), 1e-9);
        assertTrue(JewelSockets.radiusModifiers(tree, tree.node("sock"), Set.of("a", "b"), jewel).isEmpty(), "沒有核心就不給");
    }

    /** 2026-10-26 照 PoE2：「也各給」沒有上限（這次之前最多算 15 個小天賦）。 */
    @Test
    void radiusGrantHasNoCap() {
        final StringBuilder json = new StringBuilder("{\"version\": 1, \"starts\": {\"warrior\": \"s\"}, \"nodes\": ["
                + "{\"id\": \"s\", \"type\": \"start\", \"x\": -9, \"y\": -9, \"px\": -9000, \"py\": -9000},"
                + "{\"id\": \"sock\", \"type\": \"socket\", \"x\": 0, \"y\": 0, \"px\": 0, \"py\": 0}");
        final java.util.Set<String> allocated = new java.util.HashSet<>();
        int inside = 0;
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                json.append(",{\"id\": \"n").append(x).append('_').append(y).append("\", \"type\": \"small\", \"x\": ").append(x)
                        .append(", \"y\": ").append(y).append(", \"px\": ").append(x * 200).append(", \"py\": ").append(y * 200).append('}');
                allocated.add("n" + x + "_" + y);
                if (Math.hypot(x * 200, y * 200) <= 1000) {
                    inside++;
                }
            }
        }
        json.append("]}");
        final PassiveTree tree = PassiveTree.load(new StringReader(json.toString()));
        final List<Modifier> jewel = List.of(new Modifier(StatKeys.RADIUS_PREFIX + "crit.bonus", ModType.INCREASED, 0.1, Set.of(), "珠寶"));
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, tree.node("sock"), allocated, jewel);
        final Modifier crit = out.stream().filter(m -> m.stat().equals("crit.bonus")).findFirst().orElseThrow();
        assertTrue(inside > 15, "範圍內超過舊的上限 15 個");
        assertEquals(0.1 * inside, crit.value(), 1e-9, "範圍內 " + inside + " 個小天賦全部都算");
    }

    @Test
    void biggerRadiusReachesFurtherAndEffectScalesNodes() {
        final PassiveTree tree = tree();
        final PassiveNode socket = tree.node("sock");
        final List<Modifier> big = List.of(
                new Modifier(StatKeys.JEWEL_LOCAL_RADIUS, ModType.FLAT, 300, Set.of(), "珠寶"),
                new Modifier(StatKeys.RADIUS_PREFIX + "attr.str", ModType.FLAT, 2, Set.of(), "珠寶"),
                new Modifier(StatKeys.JEWEL_LOCAL_SMALL_EFFECT, ModType.INCREASED, 0.2, Set.of(), "珠寶"),
                new Modifier(StatKeys.JEWEL_LOCAL_NOTABLE_EFFECT, ModType.INCREASED, 0.25, Set.of(), "珠寶"));
        final List<Modifier> out = JewelSockets.radiusModifiers(tree, socket, Set.of("a", "b", "c", "n", "far"), big);
        final Modifier str = out.stream().filter(m -> m.stat().equals("attr.str")).findFirst().orElseThrow();
        assertEquals(8, str.value(), 1e-9, "半徑大（1300）：a、b、c、far 四個小天賦");
        // 小天賦效果 +20%：每個小天賦的 +10 生命多給 2；核心 +25%：增加 20% 傷害多給 5%
        final double life = out.stream().filter(m -> m.stat().equals("life.max")).mapToDouble(Modifier::value).sum();
        assertEquals(8, life, 1e-9);
        final double damage = out.stream().filter(m -> m.stat().equals("damage")).mapToDouble(Modifier::value).sum();
        assertEquals(0.05, damage, 1e-9);
        assertFalse(out.stream().anyMatch(m -> m.stat().startsWith("jewel.local.")), "珠寶自己的數值不進屬性表");
        assertEquals(1300, JewelSockets.radiusOf(big), 1e-9);
        // 「中」（+150）：c（1100）到了、far（1250）沒到
        final List<Modifier> medium = List.of(new Modifier(StatKeys.JEWEL_LOCAL_RADIUS, ModType.FLAT, 150, Set.of(), "珠寶"),
                new Modifier(StatKeys.RADIUS_PREFIX + "attr.str", ModType.FLAT, 2, Set.of(), "珠寶"));
        assertEquals(6, JewelSockets.radiusModifiers(tree, socket, Set.of("a", "b", "c", "far"), medium).stream()
                .filter(m -> m.stat().equals("attr.str")).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals("小", JewelSockets.radiusName(1000));
        assertEquals("中", JewelSockets.radiusName(1150));
        assertEquals("大", JewelSockets.radiusName(1300));
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
                if (!affix.canSpawnOn(base) || affix.spawnTags().contains("corruption")) {
                    continue; // 2026-10-23：腐化附魔（affixes/corrupted.yml）只靠蝕變石加上，不是半徑珠寶的詞綴池
                }
                assertTrue(affix.id().startsWith("jr_") || affix.id().startsWith("unveiled_jr_"), affix.id() + " 不是半徑珠寶的詞綴，卻出在 " + base.id());
                assertTrue(affix.mods().stream().allMatch(mod -> mod.stat().startsWith(StatKeys.RADIUS_PREFIX)
                                || mod.stat().startsWith(JewelSockets.RADIUS_NOTABLE_PREFIX) || mod.stat().startsWith("jewel.local.")),
                        affix.id() + "：半徑珠寶的詞綴要寫 radius:、radius_notable: 或 jewel.local.");
                // 2026-10-26：文字跟屬性前綴要對得上（小天賦／核心天賦）
                final boolean notable = affix.mods().stream().anyMatch(mod -> mod.stat().startsWith(JewelSockets.RADIUS_NOTABLE_PREFIX));
                if (notable) {
                    assertTrue(affix.text().startsWith("範圍內的核心天賦也各給"), affix.id());
                } else if (affix.mods().stream().anyMatch(mod -> mod.stat().startsWith(StatKeys.RADIUS_PREFIX))) {
                    assertTrue(affix.text().startsWith("範圍內的小天賦也各給"), affix.id());
                }
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
        // 2026-10-26 照 PoE2：攻速、施法速度、暴擊、格擋、移動速度…這些是核心型；命中、護甲、元素傷害這些是小天賦型
        for (final String id : List.of("jr_attack_speed", "jr_cast_speed", "jr_critical_chance", "jr_block", "jr_movement_speed", "jr_maximum_rage")) {
            assertTrue(repository.affix(id).mods().stream().allMatch(mod -> mod.stat().startsWith(JewelSockets.RADIUS_NOTABLE_PREFIX)), id);
        }
        for (final String id : List.of("jr_accuracy", "jr_armour", "jr_elemental_damage", "jr_evasion")) {
            assertTrue(repository.affix(id).mods().stream().allMatch(mod -> mod.stat().startsWith(StatKeys.RADIUS_PREFIX)), id);
        }
        // 半徑升級：中 +150、大 +300（原作單位）
        assertEquals(150, repository.affix("jr_medium_size").mods().getFirst().toModifier(java.util.Map.of("value", 1), "").value(), 1e-9);
        assertEquals(300, repository.affix("jr_large_size").mods().getFirst().toModifier(java.util.Map.of("value", 2), "").value(), 1e-9);
    }

    /** 2026-10-26：PoE2 的樹上，珠寶孔周圍 1000 內有一二十個小天賦、幾個核心（照原作距離算）。 */
    @Test
    void realTreeSocketsHavePoe2SizedRadius() throws Exception {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        int sockets = 0;
        int smalls = 0;
        int notables = 0;
        for (final PassiveNode node : tree.nodes()) {
            if (!node.isSocket()) {
                continue;
            }
            sockets++;
            for (final PassiveNode near : JewelSockets.nodesInRadius(tree, node, JewelSockets.BASE_RADIUS)) {
                if (near.type() == PassiveNode.Type.SMALL) {
                    smalls++;
                } else if (near.type() == PassiveNode.Type.NOTABLE) {
                    notables++;
                }
            }
        }
        assertTrue(sockets > 0);
        assertTrue(smalls / (double) sockets > 8 && smalls / (double) sockets < 40, "平均小天賦 " + smalls / (double) sockets);
        assertTrue(notables / (double) sockets >= 2 && notables / (double) sockets < 15, "平均核心 " + notables / (double) sockets);
    }
}
