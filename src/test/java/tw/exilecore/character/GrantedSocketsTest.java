package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2：武器、傳奇、昇華賦予的技能的輔助孔照技能等級自動開（Lv.11／15／20 開第 3／4／5 孔）。 */
class GrantedSocketsTest {

    @Test
    void socketCountFollowsLevelThresholds() {
        assertEquals(2, SkillLoadout.grantedSockets(1));
        assertEquals(2, SkillLoadout.grantedSockets(10));
        assertEquals(3, SkillLoadout.grantedSockets(11));
        assertEquals(3, SkillLoadout.grantedSockets(14));
        assertEquals(4, SkillLoadout.grantedSockets(15));
        assertEquals(4, SkillLoadout.grantedSockets(19));
        assertEquals(5, SkillLoadout.grantedSockets(20));
        assertEquals(5, SkillLoadout.grantedSockets(21));
        assertEquals(11, SkillLoadout.grantedSocketLevel(3));
        assertEquals(15, SkillLoadout.grantedSocketLevel(4));
        assertEquals(20, SkillLoadout.grantedSocketLevel(5));
    }

    @Test
    void grantedEntriesIgnoreStoredSocketsButGemsKeepThem() {
        final SkillLoadout.Entry granted = new SkillLoadout.Entry("firebolt", 16).withGranted(true);
        assertEquals(4, granted.sockets());
        assertEquals(4, granted.withSockets(5).sockets(), "賦予的技能不能另外開孔");
        assertEquals(2, granted.withLevel(3).sockets());
        assertEquals(5, granted.withLevel(20).sockets());
        final SkillLoadout.Entry gem = new SkillLoadout.Entry("firebolt", 16);
        assertEquals(2, gem.sockets(), "技能寶石照舊從 2 孔開始");
        assertEquals(5, gem.withSockets(5).sockets());
    }

    @Test
    void syncGrantedGrowsAndShrinksSocketsAndReturnsOverflow() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.syncGranted(Map.of("firebolt", 20));
        final int position = loadout.find("firebolt");
        assertEquals(5, loadout.skill(position).sockets(), "Lv.20 直接 5 孔");
        loadout.set(position, loadout.skill(position).withSupport(0, "a").withSupport(2, "c", true).withSupport(3, "d").withSupport(4, "e"));
        assertTrue(loadout.drainOverflow().isEmpty());
        // 換成等級低的武器：剩 3 孔，第 4、5 孔的輔助拿出來
        assertTrue(loadout.syncGranted(Map.of("firebolt", 12)));
        final SkillLoadout.Entry shrunk = loadout.skill(position);
        assertEquals(3, shrunk.sockets());
        assertEquals("a", shrunk.support(0));
        assertEquals("c", shrunk.support(2));
        assertTrue(shrunk.resonant(2));
        final List<SkillLoadout.Overflow> overflow = loadout.drainOverflow();
        assertEquals(List.of("d", "e"), overflow.stream().map(SkillLoadout.Overflow::support).toList());
        assertTrue(loadout.drainOverflow().isEmpty(), "拿過就清空");
        // 再換回高等級：孔變多，沒有東西要還
        assertTrue(loadout.syncGranted(Map.of("firebolt", 15)));
        assertEquals(4, loadout.skill(position).sockets());
        assertTrue(loadout.drainOverflow().isEmpty());
        // 拿掉武器：孔數不動
        loadout.syncGranted(Map.of());
        assertEquals(4, loadout.skill(position).sockets());
    }

    @Test
    void shrinkReturnsResonanceAndSpells() {
        final SkillLoadout.Entry spell = new SkillLoadout.Entry("fireball", 7);
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("firebolt", 20).withGranted(true)
                .withSupport(3, "x", true).withSpell(4, spell);
        final List<SkillLoadout.Overflow> overflow = SkillLoadout.overflow(entry, 3);
        assertEquals(2, overflow.size());
        assertEquals("x", overflow.get(0).support());
        assertTrue(overflow.get(0).resonant());
        assertNull(overflow.get(1).support());
        assertEquals("fireball", overflow.get(1).spell().skillId());
        assertEquals(7, overflow.get(1).spell().level());
        assertTrue(SkillLoadout.overflow(entry, 5).isEmpty());
    }

    @Test
    void encodingMarksLevelSocketsAndOldSavesKeepPaidSockets() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("firebolt", 12).withGranted(true).withSupport(1, "b");
        final String text = entry.encodeSupports();
        assertTrue(text.startsWith(SkillLoadout.Entry.GRANTED_PREFIX + SkillLoadout.Entry.LEVEL_SOCKETS_MARK), text);
        final SkillLoadout.Entry decoded = SkillLoadout.Entry.decode("firebolt", 12, text);
        assertTrue(decoded.granted());
        assertFalse(decoded.legacySockets());
        assertEquals(3, decoded.sockets());
        assertEquals("b", decoded.support(1));
        // 這次之前的存檔：Lv.12 付費開到 5 孔
        final SkillLoadout.Entry legacy = SkillLoadout.Entry.decode("firebolt", 12, "^5:a,b,c,d,e");
        assertTrue(legacy.granted());
        assertTrue(legacy.legacySockets(), "舊存檔的孔是付費開的");
        assertEquals(5, legacy.sockets(), "退還前孔、輔助原樣留著");
        assertEquals("e", legacy.support(4));
        assertEquals(legacy, SkillLoadout.Entry.decode("firebolt", 12, legacy.encodeSupports()), "退還前存檔照舊");
        // 舊的同步等級時孔數不動（等 migrateRemoved 退還）
        final SkillLoadout loadout = new SkillLoadout();
        loadout.set(0, legacy);
        loadout.syncGranted(Map.of("firebolt", 3));
        assertEquals(5, loadout.skill(0).sockets());
        assertTrue(loadout.drainOverflow().isEmpty());
        // 退還時：多出來的拿出來、改成照等級
        final SkillLoadout.Entry old = loadout.skill(0);
        assertEquals(List.of("c", "d", "e"), SkillLoadout.overflow(old, SkillLoadout.grantedSockets(old.level())).stream()
                .map(SkillLoadout.Overflow::support).toList());
        final SkillLoadout.Entry migrated = old.withLegacySockets(false);
        assertFalse(migrated.legacySockets());
        assertEquals(2, migrated.sockets());
        assertEquals("a", migrated.support(0));
        assertEquals("b", migrated.support(1));
        // 不是賦予的不會是舊存檔
        assertFalse(new SkillLoadout.Entry("firebolt", 3).withLegacySockets(true).legacySockets());
    }
}
