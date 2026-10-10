package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.Catalyst;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星魄、催化劑、星籤（用 jar 裡的 currencies.yml 與基底資料）。 */
class CraftingTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        generator = new ItemGenerator(items);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    @Test
    void shippedFileHasTheThreeFamilies() {
        int essences = 0;
        int catalysts = 0;
        int omens = 0;
        for (final CurrencyType type : currencies.all()) {
            switch (type.operation()) {
                case ESSENCE -> {
                    essences++;
                    assertNotNull(items.affix(type.affix()), type.id() + " 的詞綴要存在：" + type.affix());
                }
                case CATALYST -> {
                    catalysts++;
                    assertNotNull(type.catalyst());
                }
                case OMEN -> {
                    omens++;
                    assertNotNull(type.omen());
                }
                default -> {
                }
            }
            assertEquals(0, type.operation() == CurrencyOperation.ESSENCE || type.operation() == CurrencyOperation.CATALYST
                    || type.operation() == CurrencyOperation.OMEN ? type.dropWeight() : 0, "機制的通貨怪物不掉");
        }
        assertEquals(12, essences);
        assertEquals(Catalyst.values().length, catalysts, "每一種催化劑一顆");
        assertEquals(6, omens);
    }

    @Test
    void essenceUpgradesAndGuaranteesTheAffix() {
        final CurrencyType body = currencies.get("essence_body");
        final ItemData plain = generator.generate(items.base("plate_body"), Rarity.NORMAL, 60, new SplittableRandom(1));
        assertNull(Crafting.problem(body, plain, generator));
        assertTrue(body.canApply(plain, generator));
        Crafting.applyEssence(body, plain, generator, new SplittableRandom(2));
        assertEquals(Rarity.MAGIC, plain.rarity());
        assertEquals(1, plain.affixes().size());
        assertEquals("life_flat", plain.affixes().getFirst().id());
        final Affix affix = items.affix("life_flat");
        final int worst = affix.tiersFor(60).stream().mapToInt(tier -> tier.tier()).max().orElseThrow();
        assertNotEquals(worst, plain.affixes().getFirst().tier(), "不會是最差的那階");

        // 魔法 → 稀有：原本的留著，再加星魄那條（同群組的換掉）
        final CurrencyType flame = currencies.get("essence_flame");
        plain.affixes().add(new RolledAffix("resist_fire", 6, Map.of("value", 6)));
        Crafting.applyEssence(flame, plain, generator, new SplittableRandom(3));
        assertEquals(Rarity.RARE, plain.rarity());
        assertEquals(2, plain.affixes().size(), "舊的火抗換成星魄的");
        assertNotEquals(6, plain.affixes().stream().filter(a -> a.id().equals("resist_fire")).findFirst().orElseThrow().tier(), "新的階級");
        assertNotNull(plain.name(), "稀有有名字");

        // 不能用：詞綴不會出現在這種基底上、稀有物品
        final CurrencyType battle = currencies.get("essence_battle");
        assertNotNull(Crafting.problem(battle, generator.generate(items.base("plate_body"), Rarity.NORMAL, 60, new SplittableRandom(4)), generator),
                "攻速不會出現在胸甲上");
        assertFalse(battle.canApply(plain, generator), "稀有物品不能用星魄");
    }

    @Test
    void essenceWorksOnAMagicItemWhosePrefixIsFull() {
        // 2026-10：魔法物品前綴滿了也能用加前綴的星魄——先升成稀有（前綴三條的空間），原本的留著、再加星魄那條
        final CurrencyType body = currencies.get("essence_body");
        final Affix life = items.affix("life_flat");
        final tw.exilecore.item.ItemBase plate = items.base("plate_body");
        Affix otherPrefix = null;
        Affix suffix = null;
        for (final Affix affix : items.affixes()) {
            if (!affix.canSpawnOn(plate) || affix.tiersFor(60).isEmpty() || affix.group().equals(life.group())) {
                continue;
            }
            if (otherPrefix == null && affix.type() == AffixType.PREFIX) {
                otherPrefix = affix;
            } else if (suffix == null && affix.type() == AffixType.SUFFIX) {
                suffix = affix;
            }
        }
        assertNotNull(otherPrefix);
        assertNotNull(suffix);
        assertEquals(AffixType.PREFIX, life.type(), "生命星魄加的是前綴");
        final ItemData magic = new ItemData("plate_body", Rarity.MAGIC, 60);
        magic.setIdentified(true);
        magic.affixes().add(new RolledAffix(otherPrefix.id(), otherPrefix.tiersFor(60).getFirst().tier(), Map.of()));
        magic.affixes().add(new RolledAffix(suffix.id(), suffix.tiersFor(60).getFirst().tier(), Map.of()));
        assertFalse(generator.canAddAffix(magic, AffixType.PREFIX), "魔法物品的前綴滿了");
        assertNull(Crafting.problem(body, magic, generator), "照樣能用");
        assertTrue(body.canApply(magic, generator));
        Crafting.applyEssence(body, magic, generator, new SplittableRandom(5));
        assertEquals(Rarity.RARE, magic.rarity(), "變成稀有");
        assertEquals(3, magic.affixes().size(), "原本兩條留著，多一條星魄的");
        assertTrue(magic.affixes().stream().anyMatch(a -> a.id().equals("life_flat")));
    }

    @Test
    void catalystScalesOneCategory() {
        final CurrencyType resist = currencies.get("catalyst_resist");
        final ItemData ring = generator.generate(items.base("iron_ring"), Rarity.RARE, 60, new SplittableRandom(8));
        ring.setIdentified(true);
        ring.affixes().clear();
        ring.affixes().add(new RolledAffix("resist_fire", 2, Map.of("value", 40)));
        ring.affixes().add(new RolledAffix("life_flat", 3, Map.of("value", 60)));
        assertTrue(resist.canApply(ring, generator));
        Crafting.applyCatalyst(resist, ring);
        Crafting.applyCatalyst(resist, ring);
        assertEquals("resist", ring.catalyst());
        assertEquals(10, ring.quality());
        double fire = 0;
        double life = 0;
        for (final Modifier mod : ItemStats.modifiers(items, ring)) {
            if (mod.stat().equals(StatKeys.RESIST_FIRE)) {
                fire += mod.value();
            } else if (mod.stat().equals(StatKeys.LIFE_MAX)) {
                life += mod.value();
            }
        }
        assertEquals(44, fire, 1e-9, "抗性 ×1.1");
        assertEquals(60, life, 1e-9, "生命不受抗性催化劑影響");
        // 換種類：品質重來
        Crafting.applyCatalyst(currencies.get("catalyst_life"), ring);
        assertEquals("life", ring.catalyst());
        assertEquals(5, ring.quality());
        // 到 20 就不能再打同一種
        ring.setQuality(20);
        assertNotNull(Crafting.problem(currencies.get("catalyst_life"), ring, generator));
        assertNull(Crafting.problem(currencies.get("catalyst_attack"), ring, generator), "換種類可以");
        // 不是飾品不能打
        assertNotNull(Crafting.problem(resist, new ItemData("plate_body", Rarity.RARE, 60), generator));
    }

    @Test
    void omensChangeTheNextCurrency() {
        final CurrencyType sinistral = currencies.get("omen_sinistral");
        final CurrencyType zenith = currencies.firstWith(CurrencyOperation.ADD_AFFIX_RARE).orElseThrow();
        final ItemData body = new ItemData("plate_body", Rarity.RARE, 60);
        body.setIdentified(true);
        body.setName("測試");
        body.affixes().add(new RolledAffix("life_flat", 3, Map.of("value", 60)));
        assertTrue(sinistral.canApply(body, generator));
        Crafting.attachOmen(sinistral, body);
        assertEquals("omen_sinistral", body.omen());
        assertTrue(Crafting.triggers(sinistral, zenith));
        assertNull(Crafting.omenProblem(sinistral, zenith, body, generator));
        assertTrue(Crafting.applyWithOmen(sinistral, zenith, body, generator, new SplittableRandom(5)));
        assertEquals(2, body.affixes().size());
        for (final RolledAffix rolled : body.affixes()) {
            assertEquals(AffixType.PREFIX, items.affix(rolled.id()).type(), "加前綴籤：加的一定是前綴");
        }

        // 削弱：移除最差的那條
        final CurrencyType whittling = currencies.get("omen_whittling");
        final CurrencyType annul = currencies.firstWith(CurrencyOperation.REMOVE_AFFIX).orElseThrow();
        final ItemData ring = new ItemData("iron_ring", Rarity.RARE, 60);
        ring.setIdentified(true);
        ring.setName("測試");
        ring.affixes().add(new RolledAffix("resist_fire", 1, Map.of("value", 45)));
        ring.affixes().add(new RolledAffix("resist_cold", 6, Map.of("value", 7)));
        assertTrue(Crafting.applyWithOmen(whittling, annul, ring, generator, new SplittableRandom(6)));
        assertEquals(1, ring.affixes().size());
        assertEquals("resist_fire", ring.affixes().getFirst().id(), "T6 的冰抗被拿掉，T1 的火抗留著");

        // 保後綴籤：亂流石不動後綴——但只有後綴的物品沒有前綴可以拿
        final CurrencyType guard = currencies.get("omen_dextral_guard");
        final CurrencyType turmoil = currencies.firstWith(CurrencyOperation.REMOVE_AND_ADD).orElseThrow();
        assertNotNull(Crafting.omenProblem(guard, turmoil, ring, generator), "只有後綴（火抗）：沒有前綴可以移除");
        ring.affixes().add(new RolledAffix("life_flat", 3, Map.of("value", 60)));
        assertNull(Crafting.omenProblem(guard, turmoil, ring, generator));
        assertTrue(Crafting.applyWithOmen(guard, turmoil, ring, generator, new SplittableRandom(7)));
        assertTrue(ring.affixes().stream().anyMatch(a -> a.id().equals("resist_fire")), "後綴（火抗）留著");
        assertFalse(ring.affixes().stream().anyMatch(a -> a.id().equals("life_flat")), "前綴（生命）被拿掉");
        assertEquals(2, ring.affixes().size(), "拿掉一條再加一條");
    }

    @Test
    void instilNeedsAnIdentifiedAmuletAndCostsByDistance() {
        final CurrencyType ire = currencies.get("distilled_ire");
        assertEquals(CurrencyOperation.INSTIL, ire.operation());
        final ItemData ring = generator.generate(items.base("iron_ring"), Rarity.MAGIC, 60, new SplittableRandom(11));
        assertNotNull(Crafting.problem(ire, ring, generator), "只能注入項鍊");
        assertFalse(ire.canApply(ring, generator));
        final String amuletBase = items.bases().stream().filter(base -> base.slot() == tw.exilecore.item.ItemSlot.AMULET).findFirst().orElseThrow().id();
        final ItemData amulet = generator.generate(items.base(amuletBase), Rarity.RARE, 60, new SplittableRandom(12));
        amulet.setIdentified(false);
        assertNotNull(Crafting.problem(ire, amulet, generator), "要先鑑定");
        amulet.setIdentified(true);
        assertNull(Crafting.problem(ire, amulet, generator));
        assertTrue(ire.canApply(amulet, generator));
        // 離中心越遠越高階
        assertEquals(1, Crafting.instilTier(3, -5));
        assertEquals(1, Crafting.instilTier(7, 0));
        assertEquals(2, Crafting.instilTier(8, 2));
        assertEquals(2, Crafting.instilTier(-11, 11));
        assertEquals(3, Crafting.instilTier(0, 15));
        assertEquals(4, Crafting.instilTier(16, -3));
        assertEquals(3, Crafting.INSTIL_COST);
        // 注入：項鍊的修飾器多了那個核心的（透過 ItemStats 的查詢）
        Crafting.instil(amulet, "n_test");
        assertEquals("n_test", amulet.instilled());
        final int before = ItemStats.modifiers(items, amulet).size();
        ItemStats.setInstils(id -> id.equals("n_test")
                ? new ItemStats.Instil("測試核心", List.of(Modifier.increased(StatKeys.LIFE_MAX, 0.1, "注入 測試核心")), List.of("增加 10% 最大生命"))
                : null);
        try {
            final List<Modifier> mods = ItemStats.modifiers(items, amulet);
            assertEquals(before + 1, mods.size());
            assertTrue(mods.stream().anyMatch(mod -> mod.source().equals("注入 測試核心") && mod.stat().equals(StatKeys.LIFE_MAX)));
            assertEquals("測試核心", ItemStats.instilOf(amulet).name());
            Crafting.instil(amulet, "gone");
            assertNull(ItemStats.instilOf(amulet), "樹改版後找不到的核心：沒有效果");
            assertEquals(before, ItemStats.modifiers(items, amulet).size());
            assertEquals("gone", amulet.copy().instilled(), "複製也帶著注入");
        } finally {
            ItemStats.setInstils(null);
        }
    }

    @Test
    void omenNeedsRoomForTheForcedType() {
        final CurrencyType dextral = currencies.get("omen_dextral");
        final CurrencyType lustre = currencies.firstWith(CurrencyOperation.ADD_AFFIX_MAGIC).orElseThrow();
        final ItemData ring = new ItemData("iron_ring", Rarity.MAGIC, 60);
        ring.setIdentified(true);
        ring.affixes().add(new RolledAffix("resist_fire", 2, Map.of("value", 40)));
        assertNotNull(Crafting.omenProblem(dextral, lustre, ring, generator), "魔法物品只有一個後綴位，已經有火抗了");
        final List<RolledAffix> before = List.copyOf(ring.affixes());
        assertEquals(before, ring.affixes(), "沒動");
    }
}
