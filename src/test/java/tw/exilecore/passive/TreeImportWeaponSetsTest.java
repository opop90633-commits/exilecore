package tw.exilecore.passive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-09 照站長「匯入改成兩組一起帶回遊戲」：代碼「*」後面與網址計算器那段的武器組都讀得出來。 */
class TreeImportWeaponSetsTest {

    @Test
    void readsWeaponSetsFromCodeAndUrl() throws Exception {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final List<PassiveNode> order = tree.ordered();
        int a = -1;
        int b = -1;
        for (int i = 0; i < order.size() && b < 0; i++) {
            if (!order.get(i).isStart() && !order.get(i).isSocket()) {
                if (a < 0) {
                    a = i;
                } else {
                    b = i;
                }
            }
        }
        final String pack = Base64.getUrlEncoder().withoutPadding().encodeToString(("[2,[" + a + "],[" + b + "]]").getBytes(StandardCharsets.UTF_8));
        final TreeActions.WsImport fromCode = TreeActions.wsImport(tree, "EC1.2.witch.AAAA*" + pack);
        assertEquals(2, fromCode.active());
        assertEquals(java.util.Set.of(order.get(a).id()), fromCode.one());
        assertEquals(java.util.Set.of(order.get(b).id()), fromCode.two());
        assertEquals("EC1.2.witch.AAAA", TreeActions.treeCodeOnly("EC1.2.witch.AAAA*" + pack));

        final String calc = Base64.getUrlEncoder().withoutPadding().encodeToString(("[0,\"\",[],[],[],\"\",0,[1,[" + b + "],[]]]")
                .getBytes(StandardCharsets.UTF_8));
        final String url = "https://x/tree#EC1.2.witch.AAAA~!" + calc;
        final TreeActions.WsImport fromUrl = TreeActions.wsImport(tree, url);
        assertEquals(1, fromUrl.active());
        assertEquals(java.util.Set.of(order.get(b).id()), fromUrl.one());
        assertEquals("EC1.2.witch.AAAA", TreeActions.treeCodeOnly(url));
        assertNull(TreeActions.wsImport(tree, "EC1.2.witch.AAAA"), "舊代碼沒有武器組");
    }
}
