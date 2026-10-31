package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照站長：脫下法杖、長杖、權杖時，面板上賦予的技能馬上拿掉，綁的鍵回到普攻。 */
class GrantedRemovalTest {

    @Test
    void unequippingRemovesTheGrantedSkill() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.syncGranted(Map.of("flame_star_shot", 10), Map.of(), true);
        final int position = loadout.find("flame_star_shot");
        assertTrue(position >= 0, "拿著武器：放進面板");
        loadout.bind(SkillLoadout.KEYS, position);
        assertEquals(position, loadout.bound(SkillLoadout.KEYS));

        loadout.syncGranted(Map.of(), Map.of(), false);
        assertTrue(loadout.find("flame_star_shot") >= 0, "角色還沒載入好：先不拿");

        assertTrue(loadout.syncGranted(Map.of(), Map.of(), true));
        assertEquals(-1, loadout.find("flame_star_shot"), "脫下武器：馬上拿掉");
        assertEquals(-1, loadout.bound(SkillLoadout.KEYS), "綁的鍵回到普攻");
        assertTrue(loadout.drainOverflow().isEmpty(), "沒掛輔助：沒東西要還");
    }
}
