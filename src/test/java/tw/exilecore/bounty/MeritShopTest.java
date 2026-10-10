package tw.exilecore.bounty;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;
import tw.exilecore.zone.ZoneConfig;

import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 功勳兌換的紀錄、日期與週、回響的倍率。 */
class MeritShopTest {

    static BountyConfig.Shop shop() {
        final BountyConfig config = new BountyConfig();
        config.load(YamlNode.load("bounties.yml", new StringReader("""
                shop:
                  stash_pages: [200, 300]
                  flask: 10
                  fragment: { price: 150, weekly: 2 }
                  titles:
                    exile: { name: 流放者, price: 50, color: "#b0b0b0" }
                    hunter: { name: "星痕<獵人>", price: 300, color: "#7fd4ff" }
                """)));
        return config.shop();
    }

    @Test
    void stashPagesGetPricierThenRunOut() {
        final BountyConfig.Shop shop = shop();
        final Map<String, String> progress = new HashMap<>();
        assertEquals(0, MeritShop.extraStashPages(progress));
        assertEquals(200, MeritShop.nextStashPrice(shop, progress));
        progress.put(MeritShop.STASH, "1");
        assertEquals(300, MeritShop.nextStashPrice(shop, progress));
        progress.put(MeritShop.STASH, "2");
        assertEquals(-1, MeritShop.nextStashPrice(shop, progress), "換完了");
        progress.put(MeritShop.STASH, "壞掉");
        assertEquals(0, MeritShop.extraStashPages(progress));
        progress.put(MeritShop.STASH, "99");
        assertEquals(BountyConfig.MAX_STASH_PAGES, MeritShop.extraStashPages(progress), "最多 8 頁");
    }

    @Test
    void fragmentsCountPerWeek() {
        final Map<String, String> progress = new HashMap<>();
        assertEquals(0, MeritShop.fragmentsThisWeek(progress, "2026-W40"));
        progress.put(MeritShop.FRAGMENTS, MeritShop.fragmentValue("2026-W40", 2));
        assertEquals(2, MeritShop.fragmentsThisWeek(progress, "2026-W40"));
        assertEquals(0, MeritShop.fragmentsThisWeek(progress, "2026-W41"), "新的一週重新算");
    }

    @Test
    void titlesAreOwnedThenWorn() {
        final BountyConfig.Shop shop = shop();
        final Map<String, String> progress = new HashMap<>();
        assertTrue(MeritShop.ownedTitles(progress).isEmpty());
        assertNull(MeritShop.equipped(shop, progress));
        progress.put(MeritShop.TITLE, "hunter");
        assertNull(MeritShop.equipped(shop, progress), "沒買過的戴不上");
        final Set<String> owned = new LinkedHashSet<>(List.of("exile", "hunter"));
        progress.put(MeritShop.TITLES, MeritShop.titlesValue(owned));
        assertEquals(owned, MeritShop.ownedTitles(progress));
        assertEquals("hunter", MeritShop.equipped(shop, progress).id());
        assertEquals("<#7fd4ff>「星痕\\<獵人>」</#7fd4ff>", MeritShop.tag(MeritShop.equipped(shop, progress)), "名字裡的 < 跳脫掉");
        progress.put(MeritShop.TITLE, "removed");
        progress.put(MeritShop.TITLES, "exile,removed");
        assertNull(MeritShop.equipped(shop, progress), "設定檔已經拿掉的稱號不顯示");
    }

    @Test
    void ownedTitlesNeverOutgrowTheColumn() {
        final Set<String> owned = new LinkedHashSet<>();
        for (int i = 0; i < 40; i++) {
            owned.add("old_title_" + i); // 設定檔改過很多次：買過的舊稱號一大堆
        }
        owned.add("hunter");
        final String value = MeritShop.titlesValue(owned, Set.of("hunter", "exile"));
        assertTrue(value.length() <= 250, value.length() + " 字");
        assertTrue(value.startsWith("hunter"), "設定檔裡還有的排前面、一定留著");
        assertTrue(MeritShop.ownedTitles(Map.of(MeritShop.TITLES, value)).contains("old_title_0"), "放得下的舊稱號也留著");
        assertEquals("exile,hunter", MeritShop.titlesValue(new LinkedHashSet<>(List.of("exile", "hunter")), Set.of("exile", "hunter")));
    }

    @Test
    void daysAndWeeks() {
        assertEquals("2026-10-03", BountyClock.day(LocalDate.of(2026, 10, 3)));
        assertEquals("2026-W40", BountyClock.week(LocalDate.of(2026, 10, 3)));
        assertEquals("2026-W41", BountyClock.week(LocalDate.of(2026, 10, 5)), "星期一換週");
        assertEquals("2026-W53", BountyClock.week(LocalDate.of(2027, 1, 1)), "跨年照 ISO 週");
        final ZoneId taipei = ZoneId.of("Asia/Taipei");
        final ZonedDateTime saturdayNight = ZonedDateTime.of(LocalDateTime.of(2026, 10, 3, 22, 30), taipei);
        assertEquals(Duration.ofMinutes(90), BountyClock.untilNextDay(saturdayNight));
        assertEquals(Duration.ofHours(25).plusMinutes(30), BountyClock.untilNextWeek(saturdayNight));
        final ZonedDateTime monday = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 0, 0), taipei);
        assertEquals(Duration.ofDays(7), BountyClock.untilNextWeek(monday), "星期一 0 點：下一次是下週一");
        assertEquals("1 小時 30 分", BountyClock.describe(Duration.ofMinutes(90)));
        assertEquals("1 天 1 小時", BountyClock.describe(Duration.ofHours(25).plusMinutes(30)));
        assertEquals("1 分", BountyClock.describe(Duration.ofSeconds(5)));
        assertEquals("2 天", BountyClock.describe(Duration.ofDays(2)));
    }

    @Test
    void echoesAreStrongerZoneBosses() {
        final BountyConfig config = new BountyConfig();
        config.load(YamlNode.load("bounties.yml", new StringReader("""
                weekly:
                  merits: 120
                  prefix: 回響・
                  life: 2.5
                  damage: 1.2
                  scale: 1.1
                  mods: [deadly, sturdy]
                  lore: 記得
                  themes: { coast: beach }
                  phase: { at: 0.5, name: 共鳴, summon: 3, speed: 0.2 }
                """)));
        final ZoneConfig.BossDefinition boss = new ZoneConfig.BossDefinition("溺潮之母", "ExileCore_TideMother", "DROWNED", 4.8, 1.3, 1.8,
                List.of("sturdy", "frosty"), "「誰也別想走」", "沉回海底");
        final ZoneConfig.ZoneDefinition coast = new ZoneConfig.ZoneDefinition("coast", "潮濕海岸", false, 1, 12,
                List.of(new ZoneConfig.SpawnEntry("mythic:ExileCore_Tidecaller", 8), new ZoneConfig.SpawnEntry("ZOMBIE", 40)), boss, null, null);
        final MapConfig.MapDef map = Echoes.map(config.weekly(), coast, 1.5, 1.2, 30);
        assertEquals("echo_coast", map.id());
        assertEquals("回響：潮濕海岸", map.name());
        assertEquals("beach", map.theme());
        assertEquals(30, map.level());
        assertEquals("記得", map.lore());
        assertEquals(coast.monsters(), map.monsters(), "路上是那個區域的怪");
        final MapConfig.Boss echo = map.boss();
        assertEquals("回響・溺潮之母", echo.name());
        assertEquals("ExileCore_TideMother", echo.mythic());
        assertTrue(echo.named());
        // 地圖 Boss 生出來時會除掉生物本身的倍率（1.5），所以這裡先乘上：最後是區域 Boss 的 2.5 倍
        assertEquals(4.8 * 1.5 * 2.5, echo.life(), 1e-9);
        assertEquals(1.3 * 1.2 * 1.2, echo.damage(), 1e-9);
        assertEquals(1.8 * 1.1, echo.scale(), 1e-9);
        assertEquals(List.of("sturdy", "frosty", "deadly"), echo.mods(), "多加的詞綴不重複");
        assertEquals(1, echo.phases().size());
        assertEquals("ZOMBIE", echo.phases().getFirst().summonType(), "叫出那個區域的第一種原版怪物");
        assertEquals(3, echo.phases().getFirst().summonCount());
        // 沒寫主題的區域用廢墟；等級取區域最高等級與角色等級高的，最高到地圖最高階的怪物等級（84）
        assertEquals("ruins", config.weekly().theme("forest"));
        assertEquals(12, Echoes.level(12, 5, 84));
        assertEquals(40, Echoes.level(12, 40, 84));
        assertEquals(84, Echoes.level(12, 100, 84), "100 級的角色打的回響也是 84 級（怪物等級的上限）");
        assertEquals(90, Echoes.level(90, 30, 84), "區域本身的等級不會被壓低");
        // 每日委託比等級：角色超過 84 級時當作 84 級（打 T16 地圖的怪也算）
        assertEquals(84, Echoes.comparedLevel(100, 84));
        assertEquals(70, Echoes.comparedLevel(70, 84));
    }
}
