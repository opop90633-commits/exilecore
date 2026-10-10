package tw.exilecore.bounty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/bounties.yml：讀得進來、每一種委託的說明放得進任務追蹤、區域與詞綴對得上；寫錯的檔會被擋下來。 */
class BountyConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static BountyConfig config;
    static List<String> bossZones;
    static List<String> mods;

    @BeforeAll
    static void load() throws IOException {
        config = new BountyConfig();
        config.load(DATA);
        final ZoneConfig zones = new ZoneConfig();
        zones.load(DATA);
        bossZones = new ArrayList<>();
        for (final ZoneConfig.ZoneDefinition zone : zones.zones()) {
            if (zone.boss() != null) {
                bossZones.add(zone.id());
            }
        }
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        mods = new ArrayList<>();
        for (final MonsterConfig.MonsterMod mod : monsters.mods()) {
            mods.add(mod.id());
        }
    }

    static List<String> validate(final BountyConfig bounties) {
        return bounties.validate(bossZones, mods, MapConfig.THEMES);
    }

    @Test
    void shippedFileIsValid() {
        assertEquals(List.of(), validate(config));
        assertEquals(3, config.dailyCount());
        assertEquals(6, config.templates().size());
        assertEquals(10, config.levelGap());
        assertTrue(config.weekly().enabled());
        assertEquals(new java.util.TreeSet<>(bossZones), new java.util.TreeSet<>(config.weekly().themes().keySet()), "每個有 Boss 的區域都有回響的版面");
        assertTrue(config.shop().enabled());
        assertEquals(4, config.shop().stashPages().size());
        assertEquals(List.of(300L, 500L, 800L), config.shop().characterSlots(), "功勳換角色格：三個");
        assertEquals(300, MeritShop.nextCharacterSlotPrice(config.shop(), java.util.Map.of()));
        assertEquals(800, MeritShop.nextCharacterSlotPrice(config.shop(), java.util.Map.of(MeritShop.CHARACTER_SLOTS, "2")));
        assertEquals(-1, MeritShop.nextCharacterSlotPrice(config.shop(), java.util.Map.of(MeritShop.CHARACTER_SLOTS, "3")), "買完了");
        assertEquals(0, MeritShop.extraCharacterSlots(java.util.Map.of(MeritShop.CHARACTER_SLOTS, "x")), "寫壞當 0");
        assertEquals(6, config.shop().titles().size());
        // 說明：數量換進去
        assertEquals("擊殺 150 隻怪物", config.template("slayer").describe());
        assertEquals("撿回 12 片星骸碎屑", config.template("shards").describe());
        assertEquals(0.15, config.template("slayer").experience(), 1e-9, "experience 寫百分比");
        assertTrue(config.template("cartographer").needsMaps());
        assertFalse(config.template("warden").needsMaps());
    }

    @Test
    void experienceRewardStopsGrowingAfterTheCap() throws IOException {
        assertEquals(60, config.experienceLevelCap());
        final java.util.function.IntToLongFunction need = level -> level >= 100 ? 0 : level * 1000L;
        assertEquals(7_500, config.rewardExperience(0.15, 50, need), "60 級以前：自己這一級的 15%");
        assertEquals(9_000, config.rewardExperience(0.15, 80, need), "超過 60 級照 60 級算");
        assertEquals(0, config.rewardExperience(0.15, 100, need), "滿級沒有");
        assertEquals(0, config.rewardExperience(0, 50, need));
        // 實際的經驗表：99 級完成 15% 的委託，拿到的是 60 級的 15%
        final tw.exilecore.character.CharacterConfig character = new tw.exilecore.character.CharacterConfig();
        character.load(DATA);
        assertEquals(Math.round(0.15 * 14_441_758L), config.rewardExperience(0.15, 99, character::experienceToNext));
        // 0 = 不限；沒寫 = 60
        final BountyConfig unlimited = parse("""
                settings: { experience_level_cap: 0 }
                daily:
                  count: 1
                  templates:
                    slayer: { text: "擊殺 {count} 隻", kill: { count: 3 }, merits: 10, experience: 10, weight: 5 }
                """);
        assertEquals(12_000, unlimited.rewardExperience(0.15, 80, need));
        assertEquals(60, parse("daily: { count: 1 }").experienceLevelCap());
    }

    private static BountyConfig parse(final String yaml) {
        final BountyConfig parsed = new BountyConfig();
        parsed.load(YamlNode.load("bounties.yml", new StringReader(yaml)));
        return parsed;
    }

    @Test
    void rewardsAreModest() {
        // 一天全部做完大約 50～70 功勳；每週回響不超過一天的三倍
        long most = 0;
        long least = Long.MAX_VALUE;
        for (final BountyConfig.Template template : config.templates().values()) {
            most = Math.max(most, template.merits());
            least = Math.min(least, template.merits());
        }
        assertTrue(least * config.dailyCount() >= 40 && most * config.dailyCount() <= 80, least + "–" + most);
        assertTrue(config.weekly().merits() <= least * config.dailyCount() * 3);
        // 倉庫頁越來越貴
        final List<Long> pages = config.shop().stashPages();
        for (int i = 1; i < pages.size(); i++) {
            assertTrue(pages.get(i) > pages.get(i - 1));
        }
    }

    @Test
    void badFilesAreRejected() {
        final BountyConfig bad = new BountyConfig();
        bad.load(YamlNode.load("bounties.yml", new StringReader("""
                daily:
                  count: 3
                  templates:
                    maps_only: { text: "完成 {count} 張地圖", maps: { count: 0 }, merits: 5, weight: 0 }
                    too_long: { text: 這一行的說明實在是寫得太長太長了放不進任務追蹤, kill: { count: 5 }, experience: 500 }
                    shards: { text: "撿 {count} 個", collect: { count: 3, item: " ", chance: 2 } }
                weekly:
                  rooms: 3
                  life: 0
                  mods: [nope]
                  themes: { nowhere: lava, coast: beach }
                  phase: { at: 1.5 }
                shop:
                  stash_pages: [100, -1]
                  titles:
                    a: { name: 這個稱號的名字太長了啦, color: red }
                """)));
        final String all = String.join("\n", validate(bad));
        assertTrue(all.contains("maps_only：count 至少 1"), all);
        assertTrue(all.contains("maps_only.weight：至少 1"), all);
        assertTrue(all.contains("too_long.text"), all);
        assertTrue(all.contains("too_long.experience"), all);
        assertTrue(all.contains("shards.collect.chance"), all);
        assertTrue(all.contains("shards.collect.item"), all);
        assertTrue(all.contains("不用跑地圖的委託只有 2 種，少於每天 3 個"), all);
        assertTrue(all.contains("weekly.rooms"), all);
        assertTrue(all.contains("life、damage、scale 要是正的"), all);
        assertTrue(all.contains("沒有怪物詞綴 nope"), all);
        assertTrue(all.contains("weekly.themes.nowhere：沒有這個有 Boss 的區域"), all);
        assertTrue(all.contains("不認得的主題 lava"), all);
        assertTrue(all.contains("weekly.phase.at"), all);
        assertTrue(all.contains("價格不能是負的"), all);
        assertTrue(all.contains("titles.a.name"), all);
        assertTrue(all.contains("titles.a.color"), all);
        // 一個委託兩個目標、沒有目標、代號寫錯
        assertThrows(IllegalArgumentException.class, () -> new BountyConfig().load(YamlNode.load("bounties.yml", new StringReader("""
                daily:
                  templates:
                    two: { text: 兩個, kill: { count: 1 }, boss: { count: 1 } }
                """))));
        assertThrows(IllegalArgumentException.class, () -> new BountyConfig().load(YamlNode.load("bounties.yml", new StringReader("""
                daily:
                  templates:
                    none: { text: 沒有 }
                """))));
        assertThrows(IllegalArgumentException.class, () -> new BountyConfig().load(YamlNode.load("bounties.yml", new StringReader("""
                daily:
                  templates:
                    Bad-Id: { text: 一, kill: { count: 1 } }
                """))));
        assertThrows(IllegalArgumentException.class, () -> new BountyConfig().load(YamlNode.load("bounties.yml", new StringReader("""
                daily:
                  templates:
                    rarity: { text: 一, kill: { count: 1, rarity: unique } }
                """))));
    }

    @Test
    void missingFileMeansNothing() throws IOException {
        final BountyConfig empty = new BountyConfig();
        empty.load(Path.of("build/no-such-folder"));
        assertTrue(empty.templates().isEmpty());
        assertFalse(empty.weekly().enabled());
        assertFalse(empty.shop().enabled());
        assertEquals(0, empty.dailyCount());
        assertEquals(List.of(), validate(empty), "沒有檔案不算錯：就是沒有委託");
    }
}
