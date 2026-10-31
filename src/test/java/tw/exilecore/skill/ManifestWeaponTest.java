package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照 PoE2 Manifest Weapon：主手要近戰武鬥武器；照武器類型多一招（RePoE skills.json）。 */
class ManifestWeaponTest {

    @Test
    void formFollowsTheMainHandWeapon() {
        assertEquals(MinionAbilities.ManifestForm.MACE, MinionAbilities.manifestForm(Set.of("weapon", "two_hand", "mace", "melee")));
        assertEquals(MinionAbilities.ManifestForm.QUARTERSTAFF, MinionAbilities.manifestForm(Set.of("weapon", "two_hand", "quarterstaff", "melee")));
        assertEquals(MinionAbilities.ManifestForm.SPEAR, MinionAbilities.manifestForm(Set.of("weapon", "one_hand", "spear", "melee")));
        assertEquals(MinionAbilities.ManifestForm.TALISMAN, MinionAbilities.manifestForm(Set.of("weapon", "two_hand", "talisman", "melee")));
        for (final String strike : new String[] {"sword", "axe", "claw", "dagger", "flail"}) {
            assertEquals(MinionAbilities.ManifestForm.STRIKE, MinionAbilities.manifestForm(Set.of("weapon", strike, "melee")), strike);
        }
        assertNull(MinionAbilities.manifestForm(Set.of("weapon", "two_hand", "staff", "ranged", "caster")), "施法長杖不是近戰武鬥武器");
        assertNull(MinionAbilities.manifestForm(Set.of("weapon", "one_hand", "wand", "ranged", "caster")));
        assertNull(MinionAbilities.manifestForm(Set.of("weapon", "one_hand", "sceptre", "caster", "melee")), "權杖不是武鬥武器");
        assertNull(MinionAbilities.manifestForm(Set.of("weapon", "two_hand", "bow", "ranged")), "弓不是近戰");
        assertNull(MinionAbilities.manifestForm(Set.of()), "空手");
    }

    @Test
    void extraAttacksMatchRefData() {
        assertEquals(1.8, MinionAbilities.MANIFEST_SLAM_DAMAGE, 1e-9);
        assertEquals(6, MinionAbilities.MANIFEST_SLAM_COOLDOWN, 1e-9);
        assertEquals(0.9, MinionAbilities.MANIFEST_SWEEP_DAMAGE, 1e-9);
        assertEquals(1.8, MinionAbilities.MANIFEST_LUNGE_DAMAGE, 1e-9);
        assertEquals(3, MinionAbilities.MANIFEST_LUNGE_COOLDOWN, 1e-9);
        assertEquals(1.6, MinionAbilities.MANIFEST_RAKE_DAMAGE, 1e-9);
        assertTrue(MinionAbilities.MANIFEST_LUNGE_MIN < MinionAbilities.MANIFEST_LUNGE_MAX);
    }
}
