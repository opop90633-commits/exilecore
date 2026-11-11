package tw.exilecore.passive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.stats.Modifier;

/** 2026-11-11 照站長「不祥珠寶孔原作不能放傳奇珠寶」：低語之門的孔裡的傳奇珠寶不算，基礎稀有珠寶照算。 */
class VoicesSocketTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        tree = PassiveTree.load(DATA.resolve("passives").resolve("tree.json"));
    }

    @Test
    void uniqueJewelInVoiceSocketDoesNothing() {
        final String host = tree.ordered().stream().filter(PassiveNode::isSocket).map(PassiveNode::id).findFirst().orElseThrow();
        final ItemData voices = generator.generateUnique(repository.unique("voices_jewel"), 80, new Random(1));
        final ItemData rare = generator.generate(repository.base("ruby_jewel"), Rarity.RARE, 80, new Random(3));
        final ItemData unique = repository.uniques().stream()
                .filter(u -> !u.chase() && repository.base(u.base()) != null && repository.base(u.base()).isJewel()
                        && !repository.base(u.base()).isRadiusJewel() && !u.id().equals("voices_jewel"))
                .map(u -> generator.generateUnique(u, 80, new Random(4)))
                .filter(data -> !tw.exilecore.item.ItemStats.modifiers(repository, data).isEmpty())
                .findFirst().orElseThrow();
        final Map<String, ItemData> jewels = new LinkedHashMap<>();
        jewels.put(host, voices);
        jewels.put(JewelSockets.voiceKey(host, 1), rare);
        final int withRare = JewelSockets.modifiers(repository, jewels, Set.of(host), tree).size();
        assertTrue(withRare > JewelSockets.modifiers(repository, Map.of(host, voices), Set.of(host), tree).size(), "稀有珠寶照算");
        jewels.put(JewelSockets.voiceKey(host, 2), unique);
        final List<Modifier> mods = JewelSockets.modifiers(repository, jewels, Set.of(host), tree);
        assertEquals(withRare, mods.size(), "傳奇珠寶不算：" + unique.unique());
    }
}
