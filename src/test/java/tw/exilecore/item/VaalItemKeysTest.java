package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.unique.UniqueDerived;
import tw.exilecore.item.unique.VaalKeys;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Recently;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

/**
 * 2026-11-07 照站長「瓦爾傳奇照原作」：物品本地、珠寶、符文、戒指左右手、固定等級賦予技能這幾個新屬性鍵的純規則。
 */
class VaalItemKeysTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
    }

    private ItemData unique(final String id) {
        return generator.generateUnique(repository.unique(id), 80, new Random(7));
    }

    /** 這件傳奇的瓦爾詞綴裡，有這個屬性鍵的那一條。 */
    private RolledAffix vaal(final String unique, final String stat) {
        for (final String id : repository.vaalMods(unique)) {
            final Affix affix = repository.affix(id);
            if (affix != null && affix.mods().stream().anyMatch(mod -> stat.equals(mod.stat()))) {
                return new RolledAffix(id, 1, Map.of()); // 這幾條都是固定值
            }
        }
        throw new AssertionError(unique + " 沒有 " + stat + " 的瓦爾詞綴");
    }

    private static double flat(final List<Modifier> mods, final String stat) {
        return mods.stream().filter(mod -> stat.equals(mod.stat()) && mod.type() == ModType.FLAT).mapToDouble(Modifier::value).sum();
    }

    @Test
    void quecholliIsWeaponLocalNoCritBonusAndFixedCrit() {
        final ItemData maul = unique("quecholli_maul");
        final List<Modifier> mods = ItemStats.modifiers(repository, maul);
        assertEquals(1, flat(mods, StatKeys.WEAPON_NO_CRIT_BONUS));
        assertEquals(100, flat(mods, StatKeys.WEAPON_CRIT_OVERRIDE));
        assertEquals(100, flat(mods, StatKeys.WEAPON_CRIT), 1e-9, "武器面板的暴擊率就是 100%");
        assertEquals(0, flat(mods, VaalKeys.LOCAL_NO_CRIT_BONUS), "本地的不進屬性表");

        final StatSheet sheet = new StatSheet();
        sheet.addAll(mods);
        sheet.add(Modifier.flat(StatKeys.CRIT_MULTI, 200, "基礎"));
        sheet.add(Modifier.increased(StatKeys.CRIT_CHANCE, -0.5, "減少暴擊率"));
        assertEquals(1.0, DamageCalculator.critMultiplier(sheet, Set.of(Tags.ATTACK), null), 1e-9, "這把武器的攻擊沒有暴擊加成");
        assertTrue(DamageCalculator.critMultiplier(sheet, Set.of(Tags.SPELL), null) > 1.5, "法術照常");
        final SkillProfile attack = new SkillProfile("x", Set.of(Tags.ATTACK), 1.0, null, null, Map.of(DamageType.PHYSICAL, 1.0));
        assertEquals(1.0, DamageCalculator.critChance(sheet, attack), 1e-9, "暴擊率固定 100%，不吃減少");
    }

    @Test
    void snakepitKeepsOnlyTheModsOfItsHand() {
        assertEquals("左手戒指", Recently.describe(Recently.condition("x@ring_left")));
        assertNotNull(Recently.condition("skill.chains@ring_right"));
        final List<Modifier> mods = ItemStats.modifiers(repository, unique("snakepit_ring"));
        final List<Modifier> left = UniqueDerived.forRingSlot(mods, 0);
        final List<Modifier> right = UniqueDerived.forRingSlot(mods, 1);
        final List<Modifier> third = UniqueDerived.forRingSlot(mods, -1);
        assertEquals(1, flat(left, VaalKeys.FLAG_NO_CHAIN));
        assertEquals(1, flat(left, StatKeys.SKILL_FORK));
        assertEquals(0, flat(left, VaalKeys.FLAG_NO_FORK));
        assertEquals(1, flat(right, VaalKeys.FLAG_NO_FORK));
        assertEquals(0, flat(right, VaalKeys.FLAG_NO_CHAIN));
        assertEquals(0, flat(third, VaalKeys.FLAG_NO_CHAIN) + flat(third, VaalKeys.FLAG_NO_FORK), "第三個戒指欄兩邊都不算");
        assertEquals(1, flat(third, VaalKeys.FLAG_NO_PIERCE), "沒有左右手條件的照常");
        assertTrue(left.stream().noneMatch(mod -> Recently.hasCondition(mod.stat())));
    }

    @Test
    void covenantGrantsFixedLevelTen() {
        final Map<String, Integer> granted = UniqueDerived.grantedSkillLevels(ItemStats.modifiers(repository, unique("covenant_robe")));
        assertEquals(10, granted.get("vital_residue"));
        assertEquals(0, UniqueDerived.fixedGrantLevel(1), "值 1 照物品等級");
        assertEquals(12, UniqueDerived.fixedGrantLevel(12));
    }

    @Test
    void splendourOnlyTakesSoulCoresAndCountsExtraSlots() {
        final ItemData chest = unique("empress_splendour");
        final ItemBase base = repository.base(chest.base());
        final Runes.Rune iron = repository.runes().stream().filter(rune -> !Runes.isSoulCore(rune.id()) && !rune.soulCore()).findFirst().orElseThrow();
        assertNotNull(Runes.socketProblem(repository, base, chest, iron), "一般符文不能嵌");
        final Runes.Rune thesis = repository.rune("thesis_jiquani"); // 頭盔、手套、鞋子各一條，沒有胸甲的
        assertTrue(thesis.on(base).isEmpty());
        final String picked = UniquePicks.picks(chest, UniquePicks.CORE_SLOT, 1).getFirst();
        assertNotEquals("chest", picked);
        assertEquals(thesis.onSlot(picked), Runes.linesOn(repository, base, chest, thesis), "抽到的那個部位的加成");
        assertEquals(thesis.onSlot(picked).isEmpty(), Runes.socketProblem(repository, base, chest, thesis) != null);

        // 瓦爾詞綴：也照鞋子、頭盔、手套——三條都有，不重複
        final ItemData vaal = chest.copy();
        vaal.affixes().add(vaal(chest.unique(), VaalKeys.LOCAL_CORE_AS_FEET));
        vaal.affixes().add(vaal(chest.unique(), VaalKeys.LOCAL_CORE_AS_HEAD));
        vaal.affixes().add(vaal(chest.unique(), VaalKeys.LOCAL_CORE_AS_LEGS));
        assertTrue(Runes.coreExtraSlots(repository, vaal).containsAll(List.of("head", "legs", "feet")));
        // 2026-11-08 照 poe2db：也照盾（這次之前漏了這條瓦爾詞綴）
        final ItemData shield = chest.copy();
        shield.affixes().add(vaal(chest.unique(), VaalKeys.LOCAL_CORE_AS_SHIELD));
        assertTrue(Runes.coreExtraSlots(repository, shield).contains("shield"));
        assertEquals(3, Runes.linesOn(repository, base, vaal, thesis).size());
        assertNull(Runes.socketProblem(repository, base, vaal, thesis));
        vaal.addRune("thesis_jiquani");
        final List<Modifier> mods = Runes.modifiers(repository, base, vaal);
        assertEquals(1, flat(mods, VaalKeys.MANA_PER_3_HELMET_ARMOUR), "頭盔那條");
        assertEquals(1, flat(mods, VaalKeys.FLAG_MOVEMENT_TO_ES_RECHARGE), "鞋子那條");
        assertEquals(2000, flat(mods, VaalKeys.ES_RECHARGE_ON_MANA_SPENT), "手套那條");

        // 共通的 armour（特卡蒂：+13% 混沌抗性）不會因為「也照手套」多算一次
        final Runes.Rune tacati = repository.rune("core_tacati");
        // 2026-11-07 修正：原本那條隨機部位可能抽到武器（特卡蒂的武器那條會多一條），跟沒有瓦爾詞綴的比（頭、手、腳都是共通的 armour，不多算）
        assertEquals(Runes.linesOn(repository, base, chest, tacati).size(), Runes.linesOn(repository, base, vaal, tacati).size());
    }

    @Test
    void keystonePicksKeepVaalDifferentFromOriginal() {
        UniquePicks.setKeystones(new UniquePicks.Pool(List.of("k1", "k2", "k3", "k4", "k5"), id -> id), id -> List.of());
        final ItemData jewel = unique("flesh_crucible_jewel");
        final Modifier original = Modifier.flat(VaalKeys.JEWEL_ALLOCATE_KEYSTONES, 1, "a");
        final Modifier vaal = Modifier.flat(VaalKeys.JEWEL_RANDOM_KEYSTONE, 1, "b");
        final List<String> own = UniquePicks.keystonePicks(jewel, List.of(original));
        final List<String> both = UniquePicks.keystonePicks(jewel, List.of(original, vaal));
        final List<String> onlyVaal = UniquePicks.keystonePicks(jewel, List.of(vaal));
        assertEquals(1, own.size());
        assertEquals(2, both.size());
        assertEquals(own.getFirst(), both.getFirst());
        assertNotEquals(both.get(0), both.get(1), "瓦爾那條跟原本那條不同");
        assertEquals(List.of(both.get(1)), onlyVaal, "原本那條被換掉了，瓦爾的照樣跟它不同");
        assertEquals(own, UniquePicks.keystonePicks(jewel, ItemStats.modifiers(repository, jewel)), "物品上的那條");
        UniquePicks.setKeystones(null, null);
    }

    @Test
    void crucibleLessBecomesMoreOfTheRolledKind() {
        final List<Modifier> mods = new ArrayList<>(List.of(Modifier.flat(VaalKeys.JEWEL_CRUCIBLE_LESS, 15, "s"),
                Modifier.flat(VaalKeys.JEWEL_CRUCIBLE_LESS_KIND, 2, "s"), Modifier.flat(StatKeys.LIFE_MAX, 5, "t")));
        ItemStats.crucibleLess(mods);
        assertEquals(4, mods.size());
        for (final String stat : List.of(StatKeys.ARMOUR, StatKeys.EVASION, StatKeys.ES_MAX)) {
            assertTrue(mods.stream().anyMatch(mod -> stat.equals(mod.stat()) && mod.type() == ModType.MORE && Math.abs(mod.value() + 0.15) < 1e-9), stat);
        }
        final List<Modifier> jewel = ItemStats.modifiers(repository, unique("flesh_crucible_jewel"));
        assertFalse(jewel.stream().anyMatch(mod -> mod.stat().startsWith("jewel.crucible_less")), "物品上的換掉了");
        assertTrue(jewel.stream().anyMatch(mod -> mod.type() == ModType.MORE && mod.value() < 0));
    }
}
