package tw.exilecore.passive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 站長：劍、斧、匕首、爪的天賦保留，但標「目前無效」。 */
class RetiredWeaponNodesTest {

    @Test
    void retiredWeaponNodesAreMarked() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final PassiveNode sword = tree.node("p9762"); // 劍傷害
        assertTrue(sword.description().stream().anyMatch(line -> line.startsWith(PassiveTree.RETIRED_PREFIX + "劍")), sword.description().toString());
        final PassiveNode shock = tree.node("p4"); // 感電機率
        assertFalse(shock.description().stream().anyMatch(line -> line.startsWith(PassiveTree.RETIRED_PREFIX)));
    }
}
