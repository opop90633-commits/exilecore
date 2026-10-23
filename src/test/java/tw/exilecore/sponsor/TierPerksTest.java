package tw.exilecore.sponsor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：累積贊助每一階的主打（sponsor.yml 的 tiers）。 */
class TierPerksTest {

    @Test
    void shippedTiersHavePerks() throws Exception {
        final SponsorConfig config = new SponsorConfig();
        config.load(Path.of("src/main/resources/data"));
        final List<SponsorConfig.Tier> tiers = config.tiers();
        assertEquals(6, tiers.size());
        for (final SponsorConfig.Tier tier : tiers) {
            assertTrue(tier.perks().any(), tier.id() + " 要有主打");
            for (final String id : tier.perks().cosmetics()) {
                assertTrue(config.cosmetics().containsKey(id), tier.id() + " 的時裝 " + id);
            }
        }
        assertTrue(tiers.get(0).perks().loginMessage() && tiers.get(0).perks().levelFireworks());
        assertEquals(1, tiers.get(1).perks().freeBoosts());
        assertEquals("week", tiers.get(1).perks().freePer());
        assertEquals("day", tiers.get(4).perks().freePer());
        assertTrue(tiers.get(5).perks().allCosmetics() && tiers.get(5).perks().lifetimeCard());
        assertFalse(tiers.get(0).perks().allCosmetics());
        final SponsorConfig.Perks top = tiers.get(5).perks();
        assertTrue(top.joinAnnounce() && top.customNameColor() && top.entryAura());
        assertEquals("★", top.badge());
        assertEquals(1, top.monthlyRenameCards());
        assertEquals(2, top.guildStashPages());
        assertEquals(4, top.raftSize());
        assertEquals(10, top.bedStamina());
        assertEquals(5, top.boostMinutes());
        assertEquals("", tiers.get(0).perks().badge());
    }

    @Test
    void periodKeysRollOver() {
        final LocalDate friday = LocalDate.of(2026, 10, 23);
        assertEquals("2026W43", SponsorConfig.Perks.periodKey("week", friday));
        assertEquals("2026W44", SponsorConfig.Perks.periodKey("week", friday.plusDays(3)));
        assertEquals("2026-10", SponsorConfig.Perks.periodKey("month", friday));
        assertEquals("2026-11", SponsorConfig.Perks.periodKey("month", friday.plusDays(9)));
        assertEquals(String.valueOf(friday.toEpochDay()), SponsorConfig.Perks.periodKey("day", friday));
    }
}
