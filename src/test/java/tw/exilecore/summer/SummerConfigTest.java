package tw.exilecore.summer;

import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.sponsor.SponsorConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/summer_festival.yml（2026-11-03 夏日活動「星潮祭」）。照萬燈夜的 FestivalConfigTest。 */
class SummerConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    private static long at(final int month, final int day, final int hour, final int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
    }

    @Test
    void shippedSummerLoads() throws IOException {
        final SummerConfig config = new SummerConfig();
        config.load(DATA);
        assertTrue(config.enabled());
        assertEquals("星潮祭", config.name());
        assertEquals("#2ec4ff", config.color());
        // 期間：11/3 ～ 11/25（2026-11-03 站長拉長成三週、提早到 11/3），兌換多開一週到 12/2
        assertFalse(config.running(at(11, 2, 23, 59)));
        assertTrue(config.running(at(11, 3, 0, 0)));
        assertTrue(config.running(at(11, 25, 23, 59)));
        assertFalse(config.running(at(11, 26, 0, 0)));
        assertTrue(config.shopOpen(at(12, 2, 23, 59)));
        assertFalse(config.shopOpen(at(12, 3, 0, 0)));
        assertEquals("20261125", config.day(at(11, 25, 23, 0)));
        // 跟萬燈夜（z 400）分開的場地
        assertEquals(-400, config.arena().z());
        // 海妖固定三個時段、大亂鬥每個整點 9～23
        // 2026-11-03 照站長：整點、錯開——不定時活動在偶數整點，星潮祭用奇數整點，海妖跟大亂鬥也不撞
        // 2026-11-12 照站長：開天者結束後，原本開天者的整點穿插一些星潮祭（海妖多 10、16，大亂鬥多 14、20）
        assertEquals(List.of(10, 13, 16, 19, 23), List.copyOf(config.boss().hours()));
        assertEquals(List.of(9, 11, 14, 15, 17, 20, 21), List.copyOf(config.melee().hours()));
        for (final int hour : config.melee().hours()) {
            // 2026-11-12：開天者結束了，偶數整點也能用；海妖跟大亂鬥照樣不撞（這次之前要求奇數整點）
            assertTrue(!config.boss().hours().contains(hour), "大亂鬥 " + hour + " 點");
        }
        assertEquals(0, config.meleeMinute());
        assertEquals(0, config.bossMinute());
        assertEquals(88, config.boss().level());
        assertEquals(86.79, config.boss().life(), 1e-9); // 2026-11-03 站長：比照巔峰
        assertEquals(List.of("swift", "pinnacle_resist", "unstoppable"), config.boss().mods()); // 2026-11-03：比照巔峰＋免疫所有控場
        // 2026-11-03 站長「招式要更多」：舊 5 招＋新 12 招；大招（潮影、漲潮、海嘯）在後面的階段
        assertEquals(17, config.boss().skills().size());
        assertEquals(List.of(0.66, 0.33), config.boss().phases());
        assertEquals(0, config.boss().phaseAt(1.0));
        assertEquals(0, config.boss().phaseAt(0.67));
        assertEquals(1, config.boss().phaseAt(0.66));
        assertEquals(1, config.boss().phaseAt(0.5));
        assertEquals(2, config.boss().phaseAt(0.33));
        assertEquals(2, config.boss().phaseAt(0.0));
        final java.util.Map<String, Integer> phases = new java.util.HashMap<>();
        for (final tw.exilecore.skill.BossSkillUse use : config.boss().skills()) {
            phases.put(use.id(), use.fromPhase());
        }
        assertEquals(1, phases.get("tide_echoes"));
        assertEquals(1, phases.get("tide_rising_sea"));
        assertEquals(2, phases.get("tide_tsunami"));
        assertEquals(0, phases.get("tide_wave"));
        assertEquals(0, phases.get("tide_drowned_call"));
        // 新 12 招都是 tide_ 開頭、都在 boss_skills.yml 裡（validate 另外驗）
        for (final String id : List.of("tide_drowned_call", "tide_coral_volley", "tide_water_jet", "tide_bubble_orbs", "tide_tsunami", "tide_spray",
                "tide_undertow_mark", "tide_ram", "tide_wave_nova", "tide_rising_sea", "tide_echoes", "tide_storm_zone")) {
            assertTrue(phases.containsKey(id), id);
        }
        assertArrayEquals3(config.melee().topPearls());
        assertEquals(6, config.dig().spotsPerDay());
        assertEquals(8, config.bottle().letters().size());
        assertTrue(config.bottle().mapExtra() > 0);

        // 素材、時裝、招式都存在
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        final SponsorConfig sponsor = new SponsorConfig();
        sponsor.load(DATA);
        final SkillConfig skills = new SkillConfig();
        skills.load(DATA);
        assertEquals(List.of(), config.validate(id -> currencies.get(id) != null, id -> sponsor.cosmetics().containsKey(id),
                id -> skills.bossSkills().skill(id) != null));
        assertNotNull(currencies.get(SummerConfig.SHELL));
        assertNotNull(currencies.get(SummerConfig.PEARL));

        // 星潮祭的時裝全部是限定（只有活動拿得到），每一件都在兌換商店裡；代號照站長給的
        final Set<String> inShop = new HashSet<>();
        for (final SummerConfig.ShopItem item : config.shop()) {
            if (item.kind() == SummerConfig.Kind.COSMETIC) {
                inShop.add(item.cosmetic());
            }
        }
        assertEquals(Set.of("sf_kraken_wings", "sf_pearl_crown", "sf_tide_trident", "sf_pet_kraken", "sf_shell_shield", "sf_pet_jellyfish",
                // 2026-11-03 照站長：限定粒子特效
                "sf_tide_spiral", "sf_pearl_orbit", "sf_splash_trail", "sf_bubble_halo", "sf_glow_aura",
                // 2026-11-03 照站長：限定動作「衝浪」、限定 3D 戰甲「珊瑚海妖鎧」
                "sf_emote_surf", "sf_armor_coral"), inShop);
        int limited = 0;
        for (final SponsorConfig.Cosmetic cosmetic : sponsor.cosmetics().values()) {
            if ("星潮祭".equals(cosmetic.limited())) {
                limited++;
                assertTrue(inShop.contains(cosmetic.id()), cosmetic.id() + " 要放進星潮祭的兌換");
            }
        }
        assertEquals(inShop.size(), limited, "星潮祭限定的時裝要跟兌換商店一樣多");
        // 2026-11-03 照站長：不換通貨（這次之前照萬燈夜有四樣）；2026-11-05 照站長：只換 Boss 的入場券（SummerTicketsUniquesTest）
        for (final SummerConfig.ShopItem item : config.shop()) {
            if (item.kind() == SummerConfig.Kind.CURRENCY) {
                assertTrue((item.currency().startsWith(tw.exilecore.currency.CurrencyGroups.TICKET_PREFIX)
                        || item.currency().startsWith(tw.exilecore.currency.CurrencyGroups.FRAGMENT_PREFIX) || tw.exilecore.currency.CurrencyGroups.BOSS_KEYS.contains(item.currency())
                        || "fog_splinter".equals(item.currency())), item.id() + "：只換開 Boss 用的");
            }
        }
        // 挑戰的稱號、兌換的稱號都有加成
        assertEquals("summer_surfer", config.challenges().title());
        assertTrue(config.titles().containsKey(config.challenges().title()));
        assertTrue(config.titles().containsKey("summer_hunter"));
        for (final SummerConfig.Title title : config.titles().values()) {
            assertFalse(title.stats().isEmpty(), title.id());
        }
        assertEquals(5, config.challenges().krakenCount());
        assertEquals(7, config.challenges().digDays());
        assertEquals(10, config.challenges().meleeCount());
        assertEquals(30, config.challenges().bottleCount());
        // 資料檔要列在 index.txt（啟動時才會複製到插件資料夾）
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/summer_festival.yml"), "index.txt 要列 summer_festival.yml");
    }

    private static void assertArrayEquals3(final int[] tops) {
        assertEquals(3, tops.length);
        assertEquals(3, tops[0]);
        assertEquals(2, tops[1]);
        assertEquals(1, tops[2]);
    }

    @Test
    void beachLayoutLeavesRoomToDig() throws IOException {
        final SummerConfig config = new SummerConfig();
        config.load(DATA);
        // 中央的競技沙地不是海；北邊離岸遠的地方是海、2 格深；南邊是沙灘
        assertFalse(SummerArenaBuilder.sea(0, 0));
        assertFalse(SummerArenaBuilder.sea(0, -10));
        assertTrue(SummerArenaBuilder.sea(0, -30));
        assertTrue(SummerArenaBuilder.deep(0, -30));
        assertFalse(SummerArenaBuilder.deep(0, -16));
        assertFalse(SummerArenaBuilder.sea(0, 20));
        assertTrue(SummerArenaBuilder.boardwalk(0, 20));
        assertFalse(SummerArenaBuilder.diggable(config, 0, 20), "棧道不能挖");
        assertFalse(SummerArenaBuilder.diggable(config, 0, 0), "中央沙地不能挖");
        assertTrue(SummerArenaBuilder.diggable(config, 5, 20));
        // 每個人每天的藏寶點：數量對、彼此隔開、都在可以挖的沙子上；同一個種子永遠一樣、不同種子不一樣
        final List<int[]> spots = SummerArenaBuilder.digSpots(config, 12345L);
        assertEquals(config.dig().spotsPerDay(), spots.size());
        for (final int[] spot : spots) {
            assertTrue(SummerArenaBuilder.diggable(config, spot[0], spot[1]));
            for (final int[] other : spots) {
                if (other != spot) {
                    assertTrue(Math.hypot(other[0] - spot[0], other[1] - spot[1]) >= 5, "藏寶點至少隔 5 格");
                }
            }
        }
        final List<int[]> again = SummerArenaBuilder.digSpots(config, 12345L);
        for (int i = 0; i < spots.size(); i++) {
            assertEquals(spots.get(i)[0], again.get(i)[0]);
            assertEquals(spots.get(i)[1], again.get(i)[1]);
        }
        final List<int[]> other = SummerArenaBuilder.digSpots(config, 67890L);
        boolean differs = false;
        for (int i = 0; i < spots.size(); i++) {
            differs |= spots.get(i)[0] != other.get(i)[0] || spots.get(i)[1] != other.get(i)[1];
        }
        assertTrue(differs, "不同的人（種子）藏寶點不一樣");
        // 西瓜落在陸地上
        final java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 50; i++) {
            final int[] spot = SummerArenaBuilder.randomLand(config, random);
            assertNotNull(spot);
            assertFalse(SummerArenaBuilder.sea(spot[0], spot[1]));
        }
    }
}
