package tw.exilecore.festival;

import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.sponsor.SponsorConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/festival.yml（2026-10 萬聖節「萬燈夜」）。 */
class FestivalConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    private static long at(final int month, final int day, final int hour, final int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
    }

    @Test
    void shippedFestivalLoads() throws IOException {
        final FestivalConfig config = new FestivalConfig();
        config.load(DATA);
        assertTrue(config.enabled());
        assertEquals("萬燈夜", config.name());
        // 期間：到 10/31，兌換多開 7 天
        assertFalse(config.running(at(10, 15, 23, 59)));
        assertTrue(config.running(at(10, 16, 0, 0)));
        assertTrue(config.running(at(10, 31, 23, 59)));
        assertFalse(config.running(at(11, 1, 0, 0)));
        assertTrue(config.shopOpen(at(11, 7, 23, 59)));
        assertFalse(config.shopOpen(at(11, 8, 0, 0)));
        assertEquals("20261031", config.day(at(10, 31, 23, 0)));
        // 南瓜王固定三個時段
        assertEquals(List.of(13, 20, 23), List.copyOf(config.boss().hours()));
        assertEquals(13, config.lanterns().count());

        // 素材、時裝、招式都存在
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        final SponsorConfig sponsor = new SponsorConfig();
        sponsor.load(DATA);
        final SkillConfig skills = new SkillConfig();
        skills.load(DATA);
        assertEquals(List.of(), config.validate(id -> currencies.get(id) != null, id -> sponsor.cosmetics().containsKey(id),
                id -> skills.bossSkills().skill(id) != null));

        // 萬燈夜的時裝全部是限定（只有活動拿得到），每一件都在兌換商店裡
        final Set<String> inShop = new HashSet<>();
        for (final FestivalConfig.ShopItem item : config.shop()) {
            if (item.kind() == FestivalConfig.Kind.COSMETIC) {
                inShop.add(item.cosmetic());
            }
        }
        int limited = 0;
        for (final SponsorConfig.Cosmetic cosmetic : sponsor.cosmetics().values()) {
            if ("萬燈夜".equals(cosmetic.limited())) {
                limited++;
                assertTrue(inShop.contains(cosmetic.id()), cosmetic.id() + " 要放進萬燈夜的兌換");
                assertEquals(cosmetic.id(), cosmetic.art(), "萬燈夜的時裝代號跟模型同名");
            }
        }
        assertEquals(16, limited);
        assertEquals(inShop.size(), limited);
        // 挑戰的稱號、兌換的稱號都有加成
        assertTrue(config.titles().containsKey(config.challenges().title()));
        for (final FestivalConfig.Title title : config.titles().values()) {
            assertFalse(title.stats().isEmpty(), title.id());
        }
    }

    @Test
    void lanternsSitOnTheRingInsideTheArena() throws IOException {
        final FestivalConfig config = new FestivalConfig();
        config.load(DATA);
        final List<int[]> spots = FestivalArenaBuilder.lanternSpots(config);
        assertEquals(config.lanterns().count(), spots.size());
        final Set<Long> unique = new HashSet<>();
        for (final int[] spot : spots) {
            final double dx = spot[0] - config.arena().x();
            final double dz = spot[2] - config.arena().z();
            final double d = Math.sqrt(dx * dx + dz * dz);
            assertEquals(config.lanterns().ring(), d, 1.0);
            assertTrue(d < config.arena().radius() - 2, "燈要在牆內");
            assertTrue(unique.add(((long) spot[0] << 32) ^ (spot[2] & 0xffffffffL)), "燈不能重疊");
            // 燈之間至少隔 6 格，站在一盞旁邊不會同時算到另一盞
            for (final int[] other : spots) {
                if (other != spot) {
                    assertTrue(Math.hypot(other[0] - spot[0], other[2] - spot[2]) >= 6);
                }
            }
        }
        assertEquals("north", FestivalArenaBuilder.facing(0, -5));
        assertEquals("east", FestivalArenaBuilder.facing(5, 1));
    }
}
