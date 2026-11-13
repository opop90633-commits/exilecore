package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長「（斬獲＋儀典獻祭）同一條只算一次」：同一條詞綴不管哪個來源都是同一個增益，剩比較久的留著。 */
class StolenBuffOnceTest {

    @Test
    void sameModSharesOneBuffId() {
        assertEquals(SkillService.stolenBuffId("regenerating"), SkillService.stolenBuffId("regenerating"));
        assertFalse(SkillService.stolenBuffId("regenerating").equals(SkillService.stolenBuffId("shielded")));
    }

    @Test
    void longerOneStays() {
        assertTrue(SkillService.replacesStolen(0, 400), "沒有就給");
        assertTrue(SkillService.replacesStolen(300, 1200), "斬獲剩 15 秒、獻祭給 60 秒：換成獻祭的");
        assertFalse(SkillService.replacesStolen(1200, 400), "斬獲剩 60 秒、獻祭只給 20 秒：留著斬獲的，不疊第二份");
        assertFalse(SkillService.replacesStolen(400, 400));
    }
}
