package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-17 標籤整理：詞綴標籤顯示。 */
class AffixChipsTagTest {

    private static Set<AffixChips.Chip> chips(final String stat, final String... tags) {
        final Set<AffixChips.Chip> chips = EnumSet.noneOf(AffixChips.Chip.class);
        AffixChips.add(chips, stat, Set.of(tags));
        return chips;
    }

    @Test
    void skillTagsShow() {
        assertTrue(chips("damage", "totem").contains(AffixChips.Chip.TOTEM));
        assertTrue(chips("skill.cooldown", "warcry").contains(AffixChips.Chip.WARCRY));
        assertTrue(chips("skill.level", "attack", "melee").contains(AffixChips.Chip.MELEE));
        assertFalse(chips("local.unveiled_magnitude").contains(AffixChips.Chip.ATTACK), "揭露幅度不是武器屬性");
        assertTrue(chips("local.attack_speed").contains(AffixChips.Chip.ATTACK));
        assertTrue(chips("leech.life_spell").contains(AffixChips.Chip.CASTER));
        assertTrue(chips("penetration", "fire").contains(AffixChips.Chip.DAMAGE));
        assertTrue(chips("flask.local.minion_heal").contains(AffixChips.Chip.MINION));
        assertTrue(chips("damage.vs_cursed").contains(AffixChips.Chip.CURSE));
    }
}
