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
        assertEquals(SkillLoadout.GEM_CAP, new SkillLoadout.Entry("x", 99).level(), "等級夾在 1–21（Lv.21 的未切割寶石）");
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
        final int rightA = (0 * SkillLoadout.KEYS + SkillLoadout.KEY_RIGHT);
        final int sneakLeftB = (1 * SkillLoadout.KEYS + SkillLoadout.KEY_SNEAK_LEFT);
        assertEquals(1, rightA);
        assertEquals(6, sneakLeftB);
        assertEquals(-1, loadout.bound(rightA), "沒綁的按鍵是普攻");
        assertTrue(loadout.bind(rightA, fireball));
        assertTrue(loadout.bind(sneakLeftB, fireball));
        assertTrue(loadout.bind((0 * SkillLoadout.KEYS + SkillLoadout.KEY_LEFT), arc));
        assertFalse(loadout.bind(rightA, 5), "空的位置不能綁");
        assertFalse(loadout.bind(99, fireball));
        assertEquals("fireball", loadout.boundSkill(sneakLeftB).skillId());

        assertTrue(loadout.remove(fireball));
        assertEquals(-1, loadout.bound(rightA), "移除技能後按鍵回到普攻");
        assertEquals(-1, loadout.bound(sneakLeftB));
        assertEquals(arc, loadout.bound((0 * SkillLoadout.KEYS + SkillLoadout.KEY_LEFT)));
        assertFalse(loadout.remove(fireball));
        assertTrue(loadout.bind((0 * SkillLoadout.KEYS + SkillLoadout.KEY_LEFT), -1));
        assertNull(loadout.boundSkill((0 * SkillLoadout.KEYS + SkillLoadout.KEY_LEFT)));
    }

    @Test
    void copyIsIndependent() {
        final SkillLoadout loadout = new SkillLoadout();
        final int arc = loadout.put("arc", 2);
        loadout.bind((0 * SkillLoadout.KEYS + SkillLoadout.KEY_RIGHT), arc);
        final SkillLoadout copy = loadout.copy();
        loadout.put("arc", 9);
        loadout.bind((0 * SkillLoadout.KEYS + SkillLoadout.KEY_RIGHT), -1);
        assertEquals(2, copy.skill(arc).level());
        assertEquals(arc, copy.bound((0 * SkillLoadout.KEYS + SkillLoadout.KEY_RIGHT)));
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

    /** 2026-10-23：腐化過的技能寶石存檔前面寫 !，讀回來還是腐化的；放在宿主第 1 孔的也一樣。 */
    @Test
    void corruptedSurvivesEncoding() {
        final SkillLoadout.Entry plain = new SkillLoadout.Entry("fireball", 18, 3, List.of("added_fire", "", "pierce"));
        assertFalse(plain.corrupted());
        assertFalse(plain.encodeSupports().startsWith(SkillLoadout.Entry.CORRUPTED_PREFIX));
        final SkillLoadout.Entry corrupted = plain.withCorrupted(true);
        assertTrue(corrupted.encodeSupports().startsWith(SkillLoadout.Entry.CORRUPTED_PREFIX));
        final SkillLoadout.Entry back = SkillLoadout.Entry.decode("fireball", 18, corrupted.encodeSupports());
        assertTrue(back.corrupted());
        assertEquals(corrupted, back);
        assertTrue(back.withLevel(19).corrupted(), "升級、開孔、換輔助都留著腐化");
        assertTrue(back.withSockets(4).corrupted());
        assertTrue(back.withSupport(1, "faster_casting").corrupted());
        // 宿主第 1 孔的腐化寶石
        final SkillLoadout.Entry host = new SkillLoadout.Entry("totem_casting", 20, 5, List.of()).withInner(new SkillLoadout.Entry("snap", 21, 2, List.of()).withCorrupted(true));
        final SkillLoadout.Entry hostBack = SkillLoadout.Entry.decode("totem_casting", 20, host.encodeSupports());
        assertTrue(hostBack.inner().corrupted());
        assertFalse(hostBack.corrupted());
    }

    @Test
    void qualitySurvivesEncoding() {
        final SkillLoadout.Entry plain = new SkillLoadout.Entry("fireball", 18, 3, List.of("added_fire", "", "pierce"));
        assertEquals(0, plain.quality());
        final SkillLoadout.Entry q = plain.withQuality(15);
        assertEquals(q, SkillLoadout.Entry.decode("fireball", 18, q.encodeSupports()));
        final SkillLoadout.Entry both = q.withCorrupted(true).withQuality(23);
        final SkillLoadout.Entry back = SkillLoadout.Entry.decode("fireball", 18, both.encodeSupports());
        assertEquals(23, back.quality());
        assertTrue(back.corrupted());
        assertEquals(both, back);
        assertEquals(23, plain.withQuality(99).quality(), "最多 23%");
        assertEquals(0, plain.withQuality(-5).quality());
        assertEquals(15, q.withLevel(19).withSockets(4).withSupport(1, "faster_casting").withCorrupted(true).quality(), "其他改動留著品質");
        final SkillLoadout.Entry host = new SkillLoadout.Entry("totem_casting", 20, 5, List.of())
                .withInner(new SkillLoadout.Entry("snap", 21, 2, List.of()).withQuality(10).withCorrupted(true));
        final SkillLoadout.Entry hostBack = SkillLoadout.Entry.decode("totem_casting", 20, host.encodeSupports());
        assertEquals(10, hostBack.inner().quality());
        assertTrue(hostBack.inner().corrupted());
        assertEquals(0, hostBack.quality());
        // 舊資料（沒有品質）照樣讀得出來
        assertEquals(0, SkillLoadout.Entry.decode("fireball", 18, plain.encodeSupports()).quality());
    }
}
