package tw.exilecore.passive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.UniquePicks;
import tw.exilecore.item.unique.VaalKeys;
import tw.exilecore.stats.Modifier;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：熔肉之晶的隨機基石、蝕光之心的「已腐化稀有珠寶效果增加」放在真的天賦樹上。 */
class VaalJewelKeysTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static PassiveTree tree;
    static List<String> sockets;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        tree = PassiveTree.load(DATA.resolve("passives").resolve("tree.json"));
        sockets = tree.ordered().stream().filter(PassiveNode::isSocket).map(PassiveNode::id).limit(2).toList();
        final List<String> keystones = tree.ordered().stream().filter(node -> node.type() == PassiveNode.Type.KEYSTONE).map(PassiveNode::id).toList();
        assertEquals(33, keystones.size(), "原作 [1,33]");
        UniquePicks.setKeystones(new UniquePicks.Pool(keystones, id -> tree.node(id).name()), id -> tree.node(id).description());
    }

    @Test
    void fleshCrucibleAllocatesItsKeystone() {
        final ItemData jewel = generator.generateUnique(repository.unique("flesh_crucible_jewel"), 80, new Random(5));
        final String picked = UniquePicks.keystonePicks(jewel, tw.exilecore.item.ItemStats.modifiers(repository, jewel)).getFirst();
        final PassiveNode keystone = tree.node(picked);
        final Set<String> passives = new HashSet<>(List.of(sockets.getFirst()));
        final List<Modifier> mods = JewelSockets.modifiers(repository, Map.of(sockets.getFirst(), jewel), passives, tree);
        for (final Modifier expected : keystone.modifiers()) {
            assertTrue(mods.stream().anyMatch(mod -> mod.stat().equals(expected.stat()) && mod.source().contains(keystone.name())), expected.stat());
        }
        passives.add(picked);
        assertFalse(JewelSockets.modifiers(repository, Map.of(sockets.getFirst(), jewel), passives, tree).stream()
                .anyMatch(mod -> mod.source().contains("熔肉之晶")), "天賦樹上已經配了不重複給");
    }

    @Test
    void corruptedRareJewelSocketsAreAmplified() {
        final ItemData adorned = generator.generateUnique(repository.unique("vitality_spirit"), 80, new Random(5));
        final String vaal = repository.vaalMods("vitality_spirit").stream().filter(id -> {
            final Affix affix = repository.affix(id);
            return affix.mods().stream().anyMatch(mod -> VaalKeys.JEWEL_CORRUPTED_RARE_EFFECT.equals(mod.stat()));
        }).findFirst().orElseThrow();
        adorned.affixes().add(new RolledAffix(vaal, 1, Map.of("value", 50)));
        final ItemData rare = generator.generate(repository.base("diamond_jewel"), Rarity.RARE, 80, new Random(9));
        rare.setCorrupted(true);
        final Set<String> passives = new HashSet<>(sockets);
        assertEquals(0.5, JewelSockets.corruptedJewelEffect(repository, Map.of(sockets.get(0), adorned), passives, tree,
                VaalKeys.JEWEL_CORRUPTED_RARE_EFFECT), 1e-9);
        final Map<String, ItemData> alone = Map.of(sockets.get(1), rare);
        final Map<String, ItemData> both = new LinkedHashMap<>(alone);
        both.put(sockets.get(0), adorned);
        final double plain = sum(JewelSockets.modifiers(repository, alone, passives, tree), rare);
        final double amplified = sum(JewelSockets.modifiers(repository, both, passives, tree), rare);
        assertTrue(plain > 0);
        assertEquals(plain * 1.5, amplified, 1e-6, "已腐化稀有珠寶的效果增加 50%");
    }

    /** 稀有珠寶那幾條（來源不是傳奇的）的數值加總。 */
    private static double sum(final List<Modifier> mods, final ItemData rare) {
        final Set<String> own = new HashSet<>();
        for (final Modifier mod : tw.exilecore.item.ItemStats.modifiers(repository, rare)) {
            own.add(mod.source());
        }
        return mods.stream().filter(mod -> own.contains(mod.source()) && !mod.stat().startsWith("flag.")).mapToDouble(mod -> Math.abs(mod.value())).sum();
    }
}
