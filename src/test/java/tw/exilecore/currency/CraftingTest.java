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
                    // 2026-10：affix 可以寫好幾個（逗號分開，照基底挑出得來的那個），每一個都要存在
                    for (final String id : type.affix().split(",")) {
                        assertNotNull(items.affix(id.trim()), type.id() + " 的詞綴要存在：" + id.trim());
                    }
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
        // 2026-10-26 照 PoE2 0.5：19 種精髓各分次級、一般、高階（57），15 種有完美、狂亂星魄 1、我們自己的移速與能盾 2 = 75；這次之前 16
        assertEquals(75, essences); // 2026-10：＋能盾星魄；2026-10-15：＋炎擊、霜擊、雷擊星魄
        assertEquals(Catalyst.values().length, catalysts, "每一種催化劑一顆（13 種 PoE2 的＋4 種舊的）");
        assertEquals(26, omens); // 2026-10-26 照 PoE2 每種通貨分開、補上改造裝備用的星籤；這次之前 6
    }

    @Test
    void essenceUpgradesAndGuaranteesTheAffix() {
        // 2026-10-26 照 PoE2 0.5：精髓只把魔法變稀有（普通物品不能用），給的是固定的那一階；這次之前普通→魔法也行、階級照物品等級抽
        final CurrencyType body = currencies.get("essence_body");
        final ItemData normal = generator.generate(items.base("plate_body"), Rarity.NORMAL, 60, new SplittableRandom(1));
        assertNotNull(Crafting.problem(body, normal, generator), "普通物品不能用星魄");
        assertFalse(body.canApply(normal, generator));
        final ItemData plain = generator.generate(items.base("plate_body"), Rarity.NORMAL, 60, new SplittableRandom(1));
        plain.setRarity(Rarity.MAGIC);
        plain.affixes().clear();
        assertNull(Crafting.problem(body, plain, generator));
        assertTrue(body.canApply(plain, generator));
        Crafting.applyEssence(body, plain, generator, new SplittableRandom(2));
        assertEquals(Rarity.RARE, plain.rarity());
        assertEquals(1, plain.affixes().size());
        // 2026-10 PoE2 改版：生命詞綴照部位拆開，胸甲上是同群組的 life_flat_body
        assertEquals("life_flat_body", plain.affixes().getFirst().id());
        final Affix affix = items.affix("life_flat_body");
        assertEquals(Crafting.fixedTier(affix, body.essenceLevel()).tier(), plain.affixes().getFirst().tier(), "一般階：需求等級 46 以下最好的那階");
        plain.setRarity(Rarity.MAGIC);
        plain.setName(null);

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
    void alacrityEssenceGivesCastSpeedOnWandsAndStaves() {
        // 2026-10-26 照 PoE2：速度星魄（Haste）只給攻擊武器的攻擊速度，法杖、長杖的施法速度改成施速星魄（Alacrity）；
        //   這次之前（2026-10-24）速度星魄在法杖、長杖給施放速度
        final CurrencyType battle = currencies.get("essence_battle");
        final CurrencyType alacrity = currencies.get("essence_alacrity");
        final ItemData wand = generator.generate(items.base("wand_01"), Rarity.MAGIC, 60, new SplittableRandom(5));
        wand.affixes().clear();
        assertNotNull(Crafting.problem(battle, wand, generator), "速度星魄不能用在法杖上");
        assertNull(Crafting.problem(alacrity, wand, generator));
        Crafting.applyEssence(alacrity, wand, generator, new SplittableRandom(6));
        assertTrue(wand.affixes().stream().anyMatch(a -> a.id().equals("cast_speed_wand")), "法杖拿到施放速度：" + wand.affixes());
        final ItemData staff = generator.generate(items.base("staff_01"), Rarity.MAGIC, 60, new SplittableRandom(7));
        staff.affixes().clear();
        Crafting.applyEssence(alacrity, staff, generator, new SplittableRandom(8));
        assertTrue(staff.affixes().stream().anyMatch(a -> a.id().equals("cast_speed_staff")), "長杖拿到施放速度：" + staff.affixes());
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
        assertTrue(magic.affixes().stream().anyMatch(a -> items.affix(a.id()).group().equals(life.group())), "胸甲上是同群組的生命詞綴");
    }

    /** 2026-10-27 照 PoE2：品質通貨普通 +5、魔法 +2、稀有／傳奇 +1（這次之前一律 +5）。 */
    @Test
    void qualityCurrencyDependsOnRarity() {
        final ItemData normal = generator.generate(items.base("plate_helmet"), Rarity.NORMAL, 60, new SplittableRandom(3));
        final ItemData magic = generator.generate(items.base("plate_helmet"), Rarity.MAGIC, 60, new SplittableRandom(4));
        final ItemData rare = generator.generate(items.base("plate_helmet"), Rarity.RARE, 60, new SplittableRandom(5));
        for (final ItemData data : List.of(normal, magic, rare)) {
            data.setIdentified(true);
            data.setQuality(0);
            CurrencyOperation.QUALITY_ARMOUR.apply(data, generator, new SplittableRandom(1));
        }
        assertEquals(5, normal.quality());
        assertEquals(2, magic.quality());
        assertEquals(1, rare.quality());
        rare.setQuality(19);
        CurrencyOperation.QUALITY_ARMOUR.apply(rare, generator, new SplittableRandom(1));
        assertEquals(20, rare.quality());
        normal.setQuality(18);
        CurrencyOperation.QUALITY_ARMOUR.apply(normal, generator, new SplittableRandom(1));
        assertEquals(20, normal.quality(), "上限 20");
    }

    /** 2026-10-27 照 PoE2：前綴、後綴都有空位時從兩邊合起來的權重池抽（這次之前先 50/50 選邊）。 */
    @Test
    void prefixOrSuffixFollowsTheCombinedWeights() {
        final tw.exilecore.item.ItemBase base = items.base("body_str_25");
        final ItemData probe = generator.generate(base, Rarity.NORMAL, 80, new SplittableRandom(1));
        probe.setRarity(Rarity.MAGIC);
        double prefixWeight = 0;
        double suffixWeight = 0;
        for (final tw.exilecore.item.AffixType type : tw.exilecore.item.AffixType.values()) {
            for (final tw.exilecore.item.Affix affix : generator.eligible(probe, type)) {
                for (final tw.exilecore.item.AffixTier tier : ItemGenerator.rollableTiers(affix, 80)) {
                    if (type == tw.exilecore.item.AffixType.PREFIX) {
                        prefixWeight += tier.weight();
                    } else {
                        suffixWeight += tier.weight();
                    }
                }
            }
        }
        final double expected = prefixWeight / (prefixWeight + suffixWeight);
        int prefixes = 0;
        final int runs = 3000;
        final SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < runs; i++) {
            final ItemData data = generator.generate(base, Rarity.NORMAL, 80, random);
            generator.upgradeToMagic(data, random);
            if (items.affix(data.affixes().getFirst().id()).type() == tw.exilecore.item.AffixType.PREFIX) {
                prefixes++;
            }
        }
        assertEquals(expected, prefixes / (double) runs, 0.04, "前綴的比例照權重（" + expected + "）");
    }

    /** 2026-10-27 照 PoE2：星火石用在珠寶上也是 4 條（這次之前 3 條）。 */
    @Test
    void alchemyGivesJewelsFourModifiers() {
        for (int seed = 0; seed < 20; seed++) {
            final ItemData jewel = generator.generate(items.base("ruby_jewel"), Rarity.NORMAL, 70, new SplittableRandom(seed));
            CurrencyOperation.UPGRADE_TO_RARE.apply(jewel, generator, new SplittableRandom(seed));
            assertEquals(4, jewel.affixes().size(), "seed " + seed);
        }
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
        // 2026-10-27 照 PoE2：稀有物品每顆催化劑 +1%（這次之前 +5%，兩顆是 10）
        assertEquals(2, ring.quality());
        double fire = 0;
        double life = 0;
        for (final Modifier mod : ItemStats.modifiers(items, ring)) {
            if (mod.stat().equals(StatKeys.RESIST_FIRE)) {
                fire += mod.value();
            } else if (mod.stat().equals(StatKeys.LIFE_MAX)) {
                life += mod.value();
            }
        }
        assertEquals(40.8, fire, 1e-9, "抗性 ×1.02");
        assertEquals(60, life, 1e-9, "生命不受抗性催化劑影響");
        // 換種類：品質重來
        Crafting.applyCatalyst(currencies.get("catalyst_life"), ring);
        assertEquals("life", ring.catalyst());
        assertEquals(1, ring.quality()); // 2026-10-27：稀有物品從 1 開始（這次之前 5）
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
    void forcePrefixAndSuffixOmensIgnoreTurmoil() {
        // 2026-10-20：加前綴／加後綴籤不再被亂流石觸發（配亂流石等於只重洗那一型，比 PoE2 強）
        final CurrencyType turmoil = currencies.firstWith(CurrencyOperation.REMOVE_AND_ADD).orElseThrow();
        final CurrencyType zenith = currencies.firstWith(CurrencyOperation.ADD_AFFIX_RARE).orElseThrow();
        for (final String id : List.of("omen_sinistral", "omen_dextral")) {
            assertFalse(Crafting.triggers(currencies.get(id), turmoil), id + " 不該被亂流石觸發");
            assertTrue(Crafting.triggers(currencies.get(id), zenith), id + " 還是被至臻石觸發");
        }
    }

    /** 程式本身還支援（設定檔加回 REMOVE_AND_ADD 時照這樣做）。 */
    @Test
    void forcePrefixWithTurmoilOnAFullJewelSwapsAPrefix() {
        final CurrencyType sinistral = currencies.get("omen_sinistral");
        final CurrencyType turmoil = currencies.firstWith(CurrencyOperation.REMOVE_AND_ADD).orElseThrow();
        for (int seed = 0; seed < 30; seed++) {
            final ItemData jewel = new ItemData("time_lost_ruby", Rarity.RARE, 80);
            jewel.setIdentified(true);
            jewel.setName("測試");
            final SplittableRandom random = new SplittableRandom(seed);
            while (generator.addAffix(jewel, null, random)) {
                // 加到滿
            }
            final int prefixes = generator.countOf(jewel, AffixType.PREFIX);
            final int suffixes = generator.countOf(jewel, AffixType.SUFFIX);
            assertEquals(generator.maxAffixes(jewel, AffixType.PREFIX), prefixes, "前綴滿了");
            final java.util.Set<String> suffixIds = new java.util.HashSet<>();
            for (final RolledAffix rolled : jewel.affixes()) {
                if (items.affix(rolled.id()).type() == AffixType.SUFFIX) {
                    suffixIds.add(rolled.id());
                }
            }
            assertNull(Crafting.omenProblem(sinistral, turmoil, jewel, generator));
            assertTrue(Crafting.applyWithOmen(sinistral, turmoil, jewel, generator, new SplittableRandom(seed + 100)));
            assertEquals(prefixes, generator.countOf(jewel, AffixType.PREFIX), "前綴換一條，數量不變");
            assertEquals(suffixes, generator.countOf(jewel, AffixType.SUFFIX), "後綴不動");
            for (final RolledAffix rolled : jewel.affixes()) {
                if (items.affix(rolled.id()).type() == AffixType.SUFFIX) {
                    assertTrue(suffixIds.contains(rolled.id()), "後綴一條都沒換：" + rolled.id());
                }
            }
        }
    }

    @Test
    void instilNeedsAnIdentifiedAmuletAndFollowsTheRecipe() {
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
        // 2026-10-27 照 PoE2：每個核心固定三種情緒的配方（data/instil.yml）；這次之前看離天賦樹中心多遠、3 顆同一種（instilTier）
        assertEquals(3, Crafting.INSTIL_COST);
        assertEquals(java.util.List.of("distilled_suffering", "distilled_greed", "distilled_despair"),
                InstilRecipes.recipe("p42103"), "Enduring Deflection（poe2db 的 Anoint 表）");
        assertEquals(java.util.List.of("distilled_envy", "distilled_suffering", "distilled_suffering"), InstilRecipes.recipe("p336"));
        assertEquals(java.util.Map.of("distilled_envy", 1, "distilled_suffering", 2), InstilRecipes.cost("p336"));
        assertTrue(InstilRecipes.size() > 800, "幾乎每個核心都有配方：" + InstilRecipes.size());
        assertNull(InstilRecipes.recipe("p2254"), "PoE2 沒有配方的核心（Pure Energy）不能注入");
        final java.util.Map<String, Integer> bag = new java.util.HashMap<>(java.util.Map.of("distilled_envy", 1, "distilled_suffering", 1));
        assertNotNull(Crafting.instilProblem("p336", id -> bag.getOrDefault(id, 0), id -> id), "苦難只有 1 顆");
        bag.put("distilled_suffering", 2);
        assertNull(Crafting.instilProblem("p336", id -> bag.getOrDefault(id, 0), id -> id));
        assertNotNull(Crafting.instilProblem("p2254", id -> 99, id -> id), "沒有配方");
        // 塗油限定核心（天賦樹上沒有）照 anoint_only.yml 的配方，強效情緒是真的通貨
        assertEquals(java.util.List.of("distilled_ferocity", "distilled_despair", "distilled_isolation"), InstilRecipes.recipe("p39568"));
        for (final String potent : java.util.List.of("distilled_melancholy", "distilled_ferocity", "distilled_contempt")) {
            assertEquals(CurrencyOperation.INSTIL, currencies.get(potent).operation(), potent);
        }
        // 舊的四種情緒代號不變，配方裡用得到（手上的不會變成沒用）
        for (final String old : java.util.List.of("distilled_ire", "distilled_guilt", "distilled_greed", "distilled_paranoia")) {
            assertNotNull(currencies.get(old), old);
            assertTrue(InstilRecipes.all().values().stream().anyMatch(r -> r.contains(old)), old);
        }
        // 掉落等級：恐懼 69、苦難 73、孤寂 77（poe2db）；等級不夠的權重是 0
        final java.util.List<String> ten = java.util.List.of("distilled_ire", "distilled_fear", "distilled_isolation");
        assertEquals(java.util.List.of(5, 0, 0), Crafting.emotionWeightsAt(ten, java.util.List.of(5, 3, 1), 65, currencies::get));
        assertEquals(java.util.List.of(5, 3, 0), Crafting.emotionWeightsAt(ten, java.util.List.of(5, 3, 1), 70, currencies::get));
        assertEquals(java.util.List.of(5, 3, 1), Crafting.emotionWeightsAt(ten, java.util.List.of(5, 3, 1), 80, currencies::get));
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

    @Test
    void mirrorCopiesOnceAndTheCopyIsSealed() {
        final CurrencyType mirror = currencies.firstWith(CurrencyOperation.MIRROR).orElseThrow();
        assertEquals(0, mirror.dropWeight(), "映星鏡不走掉落表（另外擲，跟追逐級一樣的機率）");
        final ItemData ring = new ItemData("iron_ring", Rarity.RARE, 60);
        ring.setIdentified(true);
        ring.setLocked(true);
        ring.setOmen("omen_dextral");
        ring.affixes().add(new RolledAffix("resist_fire", 2, Map.of("value", 40)));
        assertTrue(mirror.canApply(ring, generator));
        assertNull(Crafting.problem(mirror, ring, generator));

        final ItemData copy = Crafting.mirror(ring);
        assertNotEquals(ring.uuid(), copy.uuid(), "複製品是另一件物品（不會被 /ec dupes 當成複製）");
        assertTrue(copy.mirrored());
        assertFalse(ring.mirrored(), "原本那件不變");
        assertFalse(copy.locked(), "鎖定不跟過去");
        assertNull(copy.omen(), "掛著的星籤不跟過去");
        assertEquals("omen_dextral", ring.omen());
        assertEquals(1, copy.affixes().size());
        assertEquals("resist_fire", copy.affixes().get(0).id());
        assertEquals(ring.affixes().get(0).vars(), copy.affixes().get(0).vars());
        assertTrue(copy.copy().mirrored(), "複製品的標記跟著物品走");

        // 複製品：不能再複製、任何通貨都不能再用
        assertFalse(mirror.canApply(copy, generator));
        assertNotNull(Crafting.problem(mirror, copy, generator));
        for (final CurrencyType type : currencies.all()) {
            assertFalse(type.canApply(copy, generator), type.id() + " 不能用在鏡像複製品上");
        }

        // 2026-10-26 照 PoE2：傳奇可以複製（這次之前不行）
        final ItemData unique = new ItemData("iron_ring", Rarity.UNIQUE, 60);
        unique.setIdentified(true);
        assertTrue(mirror.canApply(unique, generator));
        // 不能複製的：地圖、未鑑定、已腐化、新手裝備、藥劑
        final ItemData unidentified = new ItemData("iron_ring", Rarity.RARE, 60);
        unidentified.setIdentified(false);
        assertFalse(mirror.canApply(unidentified, generator));
        assertNotNull(Crafting.problem(mirror, unidentified, generator));
        final ItemData corrupted = new ItemData("iron_ring", Rarity.RARE, 60);
        corrupted.setCorrupted(true);
        assertFalse(mirror.canApply(corrupted, generator));
        final ItemData starter = new ItemData("iron_ring", Rarity.NORMAL, 1);
        starter.markStarter();
        assertFalse(mirror.canApply(starter, generator));
        for (final var base : items.bases()) {
            if (base.isMap() || base.isFlaskLike()) {
                final ItemData other = new ItemData(base.id(), Rarity.NORMAL, 60);
                assertFalse(mirror.canApply(other, generator), base.id() + " 不能複製");
                assertNotNull(Crafting.problem(mirror, other, generator));
            }
        }
        // 普通、魔法的裝備跟珠寶可以
        assertTrue(mirror.canApply(new ItemData("plate_body", Rarity.NORMAL, 60), generator));
    }

    /** 2026-10-27 照站長：去裂星籤移除 T 數字最大（最差）的那條，不是需求等級最低的（階數少的 T1 需求等級可能很低）。 */
    @Test
    void whittlingRemovesTheWorstTierNotTheLowestLevel() {
        final Affix single = items.affix("j2_area_of_effect"); // 只有一階：T1、需求等級 1
        final Affix many = items.affix("resist_fire");
        assertEquals(1, single.tiers().size());
        final int sixth = many.tiers().get(5).tier();
        assertEquals(6, many.rank(sixth));
        final ItemData item = new ItemData("plate_body", Rarity.RARE, 80);
        item.affixes().add(new RolledAffix(single.id(), single.tiers().getFirst().tier(), Map.of("value", 5)));
        item.affixes().add(new RolledAffix(many.id(), sixth, Map.of("value", 10)));
        final java.util.List<RolledAffix> worst = Crafting.lowestAffixes(item, items);
        assertEquals(1, worst.size());
        assertEquals("resist_fire", worst.getFirst().id(), "T6 先拿掉，T1 留著");
    }
}
