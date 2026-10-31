package tw.exilecore.passive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2026-10-31 照站長：劍、斧、匕首的天賦從天賦樹上拿掉（錘保留）；說明也不再提這三種武器。
 * 這次之前（2026-10-28）是保留節點、標「目前無效」。
 */
class RetiredWeaponNodesTest {

    @Test
    void swordAxeDaggerNodesAreGone() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        assertNull(tree.node("p9762"), "劍傷害");
        assertNull(tree.node("p62439"), "狂怒掠奪者（斧）");
        assertNull(tree.node("p56366"), "寂靜小刀（匕首）");
        assertNotNull(tree.node("p722"), "匕首群的入口那一點還在");
        for (final PassiveNode node : tree.nodes()) {
            final String text = node.name() + String.join("", node.description());
            assertFalse(text.contains("劍") || text.contains("斧") || text.contains("匕首"), node.id() + "：" + text);
            for (final tw.exilecore.stats.Modifier modifier : node.modifiers()) {
                assertFalse(modifier.tags().stream().anyMatch(Set.of("sword", "axe", "dagger")::contains), node.id());
            }
        }
    }
}
