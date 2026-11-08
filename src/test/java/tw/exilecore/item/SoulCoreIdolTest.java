package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-04 照站長「地圖絕境塞核心（跟符文同名但要有區分）」「這些（魔偶）放地圖內軍團掉落」：
 * 魂核符文（15 顆）、魔偶符文（31 顆）讀得起來，嵌在哪裡有效果照原作（軍用武器、某個部位、權杖），僅限 1、需求轉換、魂核效果增加。
 */
class SoulCoreIdolTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    private static ItemBase base(final Predicate<ItemBase> filter) {
        return repository.bases().stream().filter(filter).filter(base -> !repository.retiredBase(base.id())).findFirst().orElseThrow();
    }

    @Test
    void countsAndNames() {
        final long cores = repository.runes().stream().filter(rune -> rune.id().startsWith("core_")).count();
        final long idols = repository.runes().stream().filter(rune -> rune.id().startsWith("idol_")).count();
        assertEquals(15 + 13, cores, "poe2db 混沌試煉物品的 15 顆魂核＋吉卡尼的技能等級魂核 13 顆（2026-11-08）");
        assertEquals(31, idols, "poe2db Idol 的 31 顆魔偶");
        for (final Runes.Rune rune : repository.runes()) {
            if (rune.id().startsWith("core_")) {
                assertTrue(rune.name().startsWith("魂核符文・"), rune.id() + "：名字要跟一般符文分得出來");
                // 2026-11-08：吉卡尼的技能等級魂核原作寫「Weapon」（法杖類也算），不限軍用武器
                assertTrue(rune.martialOnly() || rune.id().startsWith("core_jiquani_"), rune.id() + "：魂核只能嵌軍用武器");
            }
            if (rune.id().startsWith("idol_")) {
                assertTrue(rune.name().startsWith("魔偶符文・"), rune.id());
                // 2026-11-05 照 poe2db：蛇、猿、梟、貓、狼、野豬、熊、牛不限顆數，其他 23 顆一個角色僅限 1 顆；這次之前 31 顆全部僅限 1 顆
                final boolean unlimited = java.util.Set.of("idol_snake", "idol_primate", "idol_owl", "idol_cat", "idol_wolf",
                        "idol_boar", "idol_bear", "idol_ox").contains(rune.id());
                assertEquals(unlimited ? 0 : 1, rune.limit(), rune.id() + "：魔偶的僅限顆數照原作");
                assertTrue(rune.slots().containsKey("sceptre"), rune.id() + "：每顆魔偶都有權杖那條");
            }
        }
    }

    @Test
    void whereTheyWork() {
        final ItemBase sword = base(b -> b.isWeapon() && b.tags().contains("melee") && !b.tags().contains(Runes.CASTER_TAG));
        final ItemBase wand = base(b -> b.isWeapon() && b.tags().contains(Runes.CASTER_TAG) && !b.tags().contains("sceptre"));
        final ItemBase sceptre = base(b -> b.isWeapon() && b.tags().contains("sceptre"));
        final ItemBase helm = base(b -> b.slot() == ItemSlot.HEAD && b.armour() + b.evasion() + b.energyShield() > 0);
        final ItemBase boots = base(b -> b.slot() == ItemSlot.FEET && b.armour() + b.evasion() + b.energyShield() > 0);
        final ItemBase chest = base(b -> b.slot() == ItemSlot.CHEST && b.armour() + b.evasion() + b.energyShield() > 0);
        final Runes.Rune opiloti = repository.rune("core_opiloti");
        assertFalse(opiloti.on(sword).isEmpty(), "軍用武器有加成");
        assertTrue(Runes.uselessOn(wand, opiloti), "魂核嵌法杖沒效果");
        assertFalse(opiloti.on(helm).isEmpty(), "歐派理堤：頭盔那條");
        assertTrue(Runes.uselessOn(boots, opiloti), "歐派理堤嵌鞋子沒效果");
        assertFalse(repository.rune("core_tacati").on(boots).isEmpty(), "特卡蒂：護甲（全部防具）都有");
        final Runes.Rune sirrius = repository.rune("idol_sirrius");
        assertFalse(sirrius.on(sceptre).isEmpty(), "魔偶：權杖那條");
        assertTrue(Runes.uselessOn(sword, sirrius), "席里斯沒有武器那條");
        assertTrue(Runes.uselessOn(chest, sirrius), "席里斯只有手套");
        assertFalse(repository.rune("idol_sycophant").on(wand).isEmpty(), "奉承：軍用武器、法杖或長杖");
        assertFalse(repository.rune("idol_maxarius").on(chest).isEmpty());
    }

    @Test
    void requirementConversionAndCoreEffect() {
        final ItemBase chest = repository.base("body_str_dex_27");
        final ItemData data = new ItemData(chest.id(), Rarity.NORMAL, 80);
        data.setIdentified(true);
        final ItemBase.Requirements plain = ItemStats.requirements(repository, chest, data);
        assertTrue(plain.dex() > 0 && plain.str() > 0);
        data.addRune("core_atmohua");
        data.addRune("core_atmohua");
        final ItemBase.Requirements converted = ItemStats.requirements(repository, chest, data);
        // 兩顆各 40%：敏捷需求 80% 搬到力量
        assertEquals(Math.round(plain.dex() * 0.2), converted.dex(), 1);
        assertEquals(plain.str() + plain.dex() - converted.dex(), converted.str(), 1);
        assertTrue(Runes.modifiers(repository, chest, data).stream().noneMatch(mod -> mod.stat().startsWith(Runes.REQ_CONVERT)), "需求轉換不進屬性表");
    }

    @Test
    void limitedOnePerCharacter() {
        final ItemData first = new ItemData("helm_str_dex_18", Rarity.NORMAL, 80);
        first.addRune("core_zalatl");
        final ItemData second = new ItemData("helm_str_dex_18", Rarity.NORMAL, 80);
        second.addRune("core_zalatl");
        final Map<String, Integer> seen = new HashMap<>();
        assertTrue(Runes.limitedDuplicates(repository, first, seen).isEmpty(), "第一顆算");
        assertEquals(java.util.Set.of("符文 " + repository.rune("core_zalatl").name()), Runes.limitedDuplicates(repository, second, seen), "第二顆不算");
        final ItemData tacati = new ItemData("helm_str_dex_18", Rarity.NORMAL, 80);
        tacati.addRune("core_tacati");
        assertTrue(Runes.limitedDuplicates(repository, tacati, seen).isEmpty(), "沒有僅限的照算");
    }

    @Test
    void soulCoreCurrenciesExist() throws IOException {
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(Path.of("src/main/resources/data"));
        for (final Runes.Rune rune : repository.runes()) {
            if (rune.soulCore()) {
                final tw.exilecore.currency.CurrencyType type = currencies.get("rune_" + rune.id());
                assertNotNull(type, rune.id() + "：currencies.yml 要有 rune_" + rune.id());
                assertEquals(0, type.dropWeight(), rune.id() + "：不走掉落表（只有絕境、軍團給）");
            }
        }
    }
}
