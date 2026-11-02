package tw.exilecore.client.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 照站長：原生天賦樹的虛實交織跳點（路徑從跳點接過去）與「不見之途」專屬節點（"u"、深紫加星光外框）。
 * 樹跟插件 EntwinedRulesTest 的一樣：S－a－K－b－c－d－j2－j1，j1 在 K 的範圍內，U 是觀星專屬。
 */
class TreeJumpTest {

    static final String TREE = """
            {"v":1,"kind":"passive","unit":160,"jr":1150,"starts":{"witch":"S"},"nodes":[
             {"i":"S","x":0,"y":0,"k":"o"},{"i":"a","x":160,"y":0},{"i":"K","x":320,"y":0,"k":"k"},{"i":"b","x":480,"y":0},
             {"i":"c","x":2400,"y":0},{"i":"d","x":2400,"y":1600},{"i":"j2","x":320,"y":1600},{"i":"j1","x":320,"y":800},
             {"i":"U","x":200,"y":300,"k":"n","u":1}],
             "links":[0,1, 1,2, 2,3, 3,4, 4,5, 5,6, 6,7, 1,8]}""";

    static TreeModel model(final String state) {
        final TreeModel.Data data = TreeModel.parseData("passive", 1, TREE);
        return new TreeModel(data, TreeModel.parseState(JsonParser.parseString(state).getAsJsonObject(), data));
    }

    static List<String> ids(final TreeModel model, final List<Integer> path) {
        final List<String> out = new ArrayList<>();
        path.forEach(i -> out.add(model.data.ids[i]));
        return out;
    }

    @Test
    void pathStartsAtJump() {
        final TreeModel withJump = model("""
                {"start":"S","owned":["a","K"],"avail":["b","j1"],"anchors":["K"],"locked":["U"],"points":9}""");
        final int j2 = withJump.data.indexOf("j2");
        assertEquals(List.of("j1", "j2"), ids(withJump, withJump.path(j2, false)), "從跳點 j1 接過去，不繞 b－c－d");
        assertEquals(List.of("j1"), ids(withJump, withJump.path(withJump.data.indexOf("j1"), false)));
        assertTrue(withJump.jump(withJump.data.indexOf("j1")));
        assertFalse(withJump.jump(withJump.data.indexOf("b")), "跟 K 相連的不是跳點");
        assertTrue(withJump.state.anchors.get(withJump.data.indexOf("K")));
        assertEquals(1150, withJump.data.jumpRadius);
        final TreeModel without = model("""
                {"start":"S","owned":["a","K"],"avail":["b"],"locked":["U"],"points":9}""");
        assertEquals(List.of("b", "c", "d", "j2"), ids(without, without.path(j2, false)));
        assertFalse(without.jump(without.data.indexOf("j1")));
    }

    @Test
    void unseenNodesGetTheirOwnColours() {
        final TreeModel locked = model("""
                {"start":"S","owned":["a"],"avail":["K"],"locked":["U"],"points":9}""");
        final int u = locked.data.indexOf("U");
        assertTrue(locked.data.unseen[u]);
        assertFalse(locked.data.unseen[locked.data.indexOf("a")]);
        assertEquals(TreePainter.UNSEEN_DEEP, TreePainter.nodeColor(locked, u, locked.stateOf(u, null)));
        assertEquals(List.of(), locked.path(u, false), "被擋住的走不到");
        assertTrue(TreePainter.tooltip(locked, u, new TreePainter.View()).stream().anyMatch(l -> l.contains("不見之途專屬")));
        final TreeModel open = model("""
                {"start":"S","owned":["a"],"avail":["K","U"],"points":9}""");
        assertEquals(TreePainter.UNSEEN_LIT, TreePainter.nodeColor(open, u, open.stateOf(u, null)));
        final TreeModel owned = model("""
                {"start":"S","owned":["a","U"],"avail":["K"],"points":9}""");
        assertEquals(TreePainter.GOLD, TreePainter.nodeColor(owned, u, owned.stateOf(u, null)), "已配照舊金色（外框留著）");
        // 跟其他顏色都分得出來
        for (final int other : new int[] {TreePainter.GOLD, TreePainter.BRIGHT, TreePainter.DIM, TreePainter.BLUE, TreePainter.SOCKET,
                TreePainter.BLOCKED, TreePainter.ORANGE}) {
            assertFalse(other == TreePainter.UNSEEN_DEEP || other == TreePainter.UNSEEN_LIT);
        }
    }
}
