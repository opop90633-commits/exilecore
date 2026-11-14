package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-14 照站長「副作用能處理嗎」：綁定記在面板的那一格上（技能寶石本身、每一孔的輔助各自一位），
 * 同一種寶石有綁、沒綁也分得出來；存檔、取出成寶石再放回去都留著。
 */
class BoundGemLedgerTest {

    private static SkillLoadout.Entry roundTrip(final SkillLoadout.Entry entry) {
        return GemData.decode(GemData.skill(entry).encode()).orElseThrow().entry();
    }

    @Test
    void eachSocketRemembersItsOwnBinding() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 20, 3, List.of("a", "b", "c"))
                .withSupportBound(1, true);
        final SkillLoadout.Entry back = roundTrip(entry);
        assertFalse(back.supportBound(0));
        assertTrue(back.supportBound(1));
        assertFalse(back.supportBound(2));
        assertFalse(back.bound());
        assertTrue(back.anyBound(), "裡面有綁定的輔助：整顆取出來要綁定");
        assertEquals("b", back.support(1), "代號是乾淨的");
    }

    @Test
    void bindingSurvivesResonanceCorruptionAndQuality() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 20, 2, List.of("a", "b"))
                .withResonant(0, true).withSupportBound(0, true).withBound(true).withCorrupted(true).withQuality(13).withLevel(21);
        final SkillLoadout.Entry back = roundTrip(entry);
        assertTrue(back.bound());
        assertTrue(back.supportBound(0));
        assertTrue(back.resonant(0));
        assertTrue(back.corrupted());
        assertEquals(13, back.quality());
        assertEquals(21, back.level());
    }

    @Test
    void replacingOrEmptyingASocketDropsItsBinding() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 20, 2, List.of("a", "b")).withSupportBound(0, true);
        assertFalse(entry.withSupport(0, "").supportBound(0), "拿掉了");
        assertFalse(entry.withSupport(0, "c").supportBound(0), "換成別顆（另外標）");
        assertTrue(entry.withSupportBound(1, true).supportBound(1));
        assertTrue(entry.withSockets(3).supportBound(0), "開孔不影響");
        assertTrue(entry.withResonant(0, true).supportBound(0), "共鳴石不影響");
    }

    @Test
    void spellsInSocketsKeepTheirBinding() {
        final SkillLoadout.Entry spell = new SkillLoadout.Entry("spark", 18, 2, List.of()).withBound(true);
        final SkillLoadout.Entry host = new SkillLoadout.Entry("cast_on_crit", 20, 3, List.of("", "", "")).withSpell(1, spell);
        assertTrue(host.spellAt(1).bound());
        assertTrue(host.anyBound());
        assertTrue(roundTrip(host).spellAt(1).bound());
    }

    /** 2026-11-14 照站長「照原作改」：晶蝕石用過的是二次腐化（存檔、取出、放進孔裡都記得），不算綁定。 */
    @Test
    void twiceCorruptedIsRemembered() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 21, 2, List.of("a", "")).withCorrupted(true).withTwiceCorrupted(true);
        final SkillLoadout.Entry back = roundTrip(entry);
        assertTrue(back.twiceCorrupted());
        assertTrue(back.corrupted());
        assertFalse(back.anyBound(), "二次腐化不是綁定");
        assertTrue(back.withLevel(20).withSupportBound(0, true).twiceCorrupted());
        final SkillLoadout.Entry host = new SkillLoadout.Entry("cast_on_crit", 20, 3, List.of("", "", "")).withSpell(1, entry);
        assertTrue(roundTrip(host).spellAt(1).twiceCorrupted());
        assertFalse(new SkillLoadout.Entry("fireball", 20).twiceCorrupted());
    }
}
