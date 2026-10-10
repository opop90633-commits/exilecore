package tw.exilecore.bounty;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.event.EventConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 稱號的永久加成：功勳兌換與每月活動的稱號都有、寫法遊戲會讀、說明文字、加起來的總和、寫錯會被擋下來。 */
class TitleBonusTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static BountyConfig bounties;
    static EventConfig events;

    @BeforeAll
    static void load() throws IOException {
        bounties = new BountyConfig();
        bounties.load(DATA);
        events = new EventConfig();
        events.load(DATA);
    }

    @Test
    void everyTitleHasASmallPermanentBonus() {
        assertEquals(6, bounties.shop().titles().size());
        for (final BountyConfig.Title title : bounties.shop().titles().values()) {
            assertFalse(title.stats().isEmpty(), title.id() + "：功勳兌換的稱號都有加成");
            for (final Modifier stat : title.stats()) {
                assertNull(StatLines.problem(stat), title.id() + "：" + StatLines.problem(stat));
                assertEquals("稱號「" + title.name() + "」", stat.source());
            }
        }
        int themed = 0;
        for (final EventConfig.Theme theme : events.themes()) {
            if (theme.title() == null) {
                continue;
            }
            themed++;
            assertFalse(theme.title().stats().isEmpty(), theme.id() + "：限定稱號也有加成");
            for (final Modifier stat : theme.title().stats()) {
                assertNull(StatLines.problem(stat), theme.id() + "：" + StatLines.problem(stat));
            }
        }
        assertEquals(12, themed, "十二個月各一個限定稱號");
        // 說明文字（選單上看到的）
        assertEquals("+10 最大生命", MeritShop.bonusText(bounties.shop().titles().get("exile")));
        assertEquals("+3% 所有元素抗性", MeritShop.bonusText(bounties.shop().titles().get("echo")));
        assertEquals("增加 3% 物品掉落稀有度", MeritShop.bonusText(bounties.shop().titles().get("hunter")));
        assertEquals("增加 4% 傷害", MeritShop.bonusText(bounties.shop().titles().get("starlord")));
        assertNull(MeritShop.bonusText(new BountyConfig.Title("x", "x", "#ffffff", 0)), "沒有加成的稱號");
        final EventConfig.Theme iron = events.theme("iron_month");
        assertEquals(List.of("增加 4% 物理傷害"), StatLines.describeAll(iron.title().stats()));
        assertEquals(List.of("10% 機率避免流血"), StatLines.describeAll(events.theme("blood_month").title().stats()));
    }

    @Test
    void ownedTitlesStackIntoOneTotal() {
        // 全部都拿到時：同一種屬性合成一條
        final List<Modifier> all = new ArrayList<>();
        for (final BountyConfig.Title title : bounties.shop().titles().values()) {
            all.addAll(title.stats());
        }
        for (final EventConfig.Theme theme : events.themes()) {
            if (theme.title() != null) {
                all.addAll(theme.title().stats());
            }
        }
        final List<Modifier> total = StatLines.merge(all, "稱號加成");
        assertTrue(total.size() < all.size(), "移動速度、傷害、抗性會合併");
        final StatSheet sheet = new StatSheet().addAll(all);
        assertEquals(0.05, sheet.increased(StatKeys.MOVEMENT_SPEED, Set.of()), 1e-9, "天穹行者 3% ＋ 追風者 2%");
        assertEquals(0.07, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9, "星骸之主 4% ＋ 督軍剋星 3%");
        assertEquals(0.11, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.PHYSICAL)), 1e-9, "物理再加破甲者 4%");
        assertEquals(9, sheet.flat(StatKeys.RESIST_FIRE, Set.of()), 1e-9, "回響征服者 3 ＋ 餘燼守望者 6");
        assertEquals(0.03, sheet.increased(StatKeys.DROP_RARITY, Set.of()), 1e-9);
        // 加起來還是小的：跟幾個天賦點差不多
        assertTrue(sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL)) <= 0.1);
        assertTrue(sheet.increased(StatKeys.LIFE_MAX, Set.of()) <= 0.03);
        // merge：同一種寫法、同一組標籤才合併
        final List<Modifier> merged = StatLines.merge(List.of(
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.03, Set.of(), "a"),
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.04, Set.of(), "b"),
                new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.04, Set.of(Tags.PHYSICAL), "c"),
                new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.1, Set.of(), "d")), "總和");
        assertEquals(3, merged.size());
        assertEquals(0.07, merged.getFirst().value(), 1e-9);
        assertEquals("總和", merged.getFirst().source());
    }

    @Test
    void badTitleStatsAreRejected() throws IOException {
        final BountyConfig bad = new BountyConfig();
        bad.load(YamlNode.load("bounties.yml", new StringReader("""
                shop:
                  titles:
                    one: { name: 一, price: 10, color: "#ffffff", stats: [ { stat: resist.fire, type: INCREASED, value: 5 } ] }
                    two: { name: 二, price: 10, color: "#ffffff", stats: [ { stat: holy.power, value: 5 } ] }
                """)));
        final String problems = String.join("\n", bad.validate(List.of(), List.of(), Set.of()));
        assertTrue(problems.contains("shop.titles.one.stats：resist.fire 只能用 FLAT"), problems);
        assertTrue(problems.contains("shop.titles.two.stats：不支援的屬性 holy.power"), problems);
        assertThrows(IllegalArgumentException.class, () -> new BountyConfig().load(YamlNode.load("b.yml", new StringReader("""
                shop:
                  titles:
                    one: { name: 一, price: 10, stats: [ { stat: life.max } ] }
                """))), "缺少 value");
        // 每月活動的限定稱號
        final Path dir = Files.createTempDirectory("events");
        Files.writeString(dir.resolve("events.yml"), """
                themes:
                  only:
                    name: 測試
                    challenges:
                      - { id: kill, kind: kill, text: "擊殺 {count} 隻", count: 10, merits: 5 }
                    title: { name: 測, color: "#ffffff", stats: [ { stat: movement.speed, value: 5 } ] }
                """);
        final EventConfig fresh = new EventConfig();
        fresh.load(dir);
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        final String eventProblems = String.join("\n", fresh.validate(monsters.mods().stream().map(MonsterConfig.MonsterMod::id).toList(),
                List.of("shrine")));
        assertTrue(eventProblems.contains("only.title.stats：movement.speed 只能用 INCREASED"), eventProblems);
    }
}
