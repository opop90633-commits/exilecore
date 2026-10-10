package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能面板：技能、等級、按鍵。 */
class SkillLoadoutTest {

    @Test
    void putFindAndLevels() {
        final SkillLoadout loadout = new SkillLoadout();
        assertTrue(loadout.isEmpty());
        final int fireball = loadout.put("fireball", 3);
        assertEquals(0, fireball);
        assertEquals(1, loadout.put("arc", 1));
        assertEquals(fireball, loadout.put("fireball", 7), "已經有的技能只改等級");
        assertEquals(7, loadout.skill(fireball).level());
        assertEquals(2, loadout.size());
        assertEquals(-1, loadout.find("spark"));
        assertEquals(20, new SkillLoadout.Entry("x", 99).level(), "等級夾在 1–20");
        assertEquals(1, new SkillLoadout.Entry("x", 0).level());
        assertThrows(IllegalArgumentException.class, () -> new SkillLoadout.Entry(" ", 1));

        for (int i = 0; i < SkillLoadout.MAX_SKILLS - 2; i++) {
            assertTrue(loadout.put("skill" + i, 1) >= 0);
        }
        assertEquals(-1, loadout.put("one_too_many", 1), "最多 9 個");
    }

    @Test
    void bindsFollowRemovals() {
        final SkillLoadout loadout = new SkillLoadout();
        final int fireball = loadout.put("fireball", 1);
        final int arc = loadout.put("arc", 1);
        final int rightA = SkillLoadout.slot(0, SkillLoadout.KEY_RIGHT);
        final int sneakLeftB = SkillLoadout.slot(1, SkillLoadout.KEY_SNEAK_LEFT);
        assertEquals(1, rightA);
        assertEquals(6, sneakLeftB);
        assertEquals(-1, loadout.bound(rightA), "沒綁的按鍵是普攻");
        assertTrue(loadout.bind(rightA, fireball));
        assertTrue(loadout.bind(sneakLeftB, fireball));
        assertTrue(loadout.bind(SkillLoadout.slot(0, SkillLoadout.KEY_LEFT), arc));
        assertFalse(loadout.bind(rightA, 5), "空的位置不能綁");
        assertFalse(loadout.bind(99, fireball));
        assertEquals("fireball", loadout.boundSkill(sneakLeftB).skillId());

        assertTrue(loadout.remove(fireball));
        assertEquals(-1, loadout.bound(rightA), "移除技能後按鍵回到普攻");
        assertEquals(-1, loadout.bound(sneakLeftB));
        assertEquals(arc, loadout.bound(SkillLoadout.slot(0, SkillLoadout.KEY_LEFT)));
        assertFalse(loadout.remove(fireball));
        assertTrue(loadout.bind(SkillLoadout.slot(0, SkillLoadout.KEY_LEFT), -1));
        assertNull(loadout.boundSkill(SkillLoadout.slot(0, SkillLoadout.KEY_LEFT)));
    }

    @Test
    void copyIsIndependent() {
        final SkillLoadout loadout = new SkillLoadout();
        final int arc = loadout.put("arc", 2);
        loadout.bind(SkillLoadout.slot(0, SkillLoadout.KEY_RIGHT), arc);
        final SkillLoadout copy = loadout.copy();
        loadout.put("arc", 9);
        loadout.bind(SkillLoadout.slot(0, SkillLoadout.KEY_RIGHT), -1);
        assertEquals(2, copy.skill(arc).level());
        assertEquals(arc, copy.bound(SkillLoadout.slot(0, SkillLoadout.KEY_RIGHT)));
        loadout.clear();
        assertTrue(loadout.isEmpty());
        assertEquals(1, copy.size());
    }

    @Test
    void entriesCarrySocketsAndSupports() {
        final SkillLoadout.Entry fresh = new SkillLoadout.Entry("fireball", 5);
        assertEquals(SkillLoadout.BASE_SOCKETS, fresh.sockets(), "新技能 2 個輔助孔");
        assertEquals(SkillLoadout.MAX_SOCKETS, fresh.supports().size());
        assertEquals(0, fresh.freeSocket());
        assertTrue(fresh.supportIds().isEmpty());

        final SkillLoadout.Entry socketed = fresh.withSupport(1, "pierce").withSockets(3).withSupport(2, "chain");
        assertEquals(List.of("pierce", "chain"), socketed.supportIds());
        assertEquals(0, socketed.freeSocket());
        assertTrue(socketed.hasSupport("chain"));
        assertEquals(5, socketed.level(), "換輔助不動等級");
        assertEquals(6, socketed.withLevel(6).level());
        assertThrows(IllegalArgumentException.class, () -> fresh.withSupport(2, "x"), "還沒開的孔不能掛");

        // 存檔文字：孔數:輔助,輔助,…（空孔留空）
        assertEquals("3:,pierce,chain", socketed.encodeSupports());
        assertEquals(socketed, SkillLoadout.Entry.decode("fireball", 5, "3:,pierce,chain"));
        assertEquals(fresh, SkillLoadout.Entry.decode("fireball", 5, null), "舊存檔（沒有這一欄）");
        assertEquals(fresh, SkillLoadout.Entry.decode("fireball", 5, "壞掉的:"), "壞掉的文字當成 2 個空孔");
        assertEquals(SkillLoadout.MAX_SOCKETS, SkillLoadout.Entry.decode("x", 1, "9:a,b,c,d,e,f").sockets(), "孔數夾在 2–5");
        assertEquals(List.of("a", "b"), SkillLoadout.Entry.decode("x", 1, "2:a,b,c").supportIds(), "超過孔數的輔助不算");
    }

    @Test
    void addRefusesDuplicatesAndFindsSupportOwners() {
        final SkillLoadout loadout = new SkillLoadout();
        final int arc = loadout.add(new SkillLoadout.Entry("arc", 3).withSupport(0, "chain"));
        assertEquals(0, arc);
        assertEquals(-2, loadout.add(new SkillLoadout.Entry("arc", 9)), "同一個技能不能放兩個");
        assertEquals(arc, loadout.supportUsedBy("chain"));
        assertEquals(-1, loadout.supportUsedBy("pierce"));
        // put 只改等級，不動輔助孔
        loadout.put("arc", 7);
        assertEquals(7, loadout.skill(arc).level());
        assertEquals(List.of("chain"), loadout.skill(arc).supportIds());
        for (int i = 1; i < SkillLoadout.MAX_SKILLS; i++) {
            assertTrue(loadout.add(new SkillLoadout.Entry("skill" + i, 1)) >= 0);
        }
        assertEquals(-1, loadout.add(new SkillLoadout.Entry("more", 1)), "面板滿了");
    }
}
