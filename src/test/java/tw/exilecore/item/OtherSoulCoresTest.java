package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「一起補」：poe2db 有名字的魂核 19 顆（吉卡尼以外）。 */
class OtherSoulCoresTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    private static ItemBase base(final Predicate<ItemBase> filter) {
        return repository.bases().stream().filter(filter).filter(b -> !repository.retiredBase(b.id())).findFirst().orElseThrow();
    }

    private static List<Modifier> socketed(final ItemBase base, final String rune) {
        final ItemData data = new ItemData(base.id(), Rarity.NORMAL, 80);
        data.setIdentified(true);
        data.addRune(rune);
        return Runes.modifiers(repository, base, data);
    }

    @Test
    void allNineteenExistWithLevel50() {
        final List<Runes.Rune> named = repository.runes().stream()
                .filter(rune -> Runes.possessiveCore(rune.id()) && !rune.id().startsWith("core_jiquani_")).toList();
        assertEquals(19, named.size());
        for (final Runes.Rune rune : named) {
            assertEquals(50, rune.level(), rune.id());
            assertTrue(rune.soulCore(), rune.id());
        }
        assertFalse(Runes.possessiveCore("core_tacati"), "一般的魂核不算有名字的");
    }

    @Test
    void tzamotoGivesRageOnHelmetOnly() {
        final ItemBase helm = base(b -> b.slot() == ItemSlot.HEAD && b.armour() + b.evasion() + b.energyShield() > 0);
        final ItemBase boots = base(b -> b.slot() == ItemSlot.FEET && b.armour() + b.evasion() + b.energyShield() > 0);
        assertTrue(socketed(helm, "core_tzamoto_ferocity").stream().anyMatch(m -> m.stat().equals("rage.max") && m.value() == 4));
        assertTrue(Runes.uselessOn(boots, repository.rune("core_tzamoto_ferocity")), "鞋子嵌了沒效果、不給嵌");
    }

    @Test
    void cholotlOnlyOnBows() {
        final ItemBase bow = base(b -> b.isWeapon() && b.tags().contains("bow"));
        final ItemBase sword = base(b -> b.isWeapon() && b.tags().contains("melee") && !b.tags().contains(Runes.CASTER_TAG));
        final Runes.Rune cholotl = repository.rune("core_cholotl_war");
        assertTrue(socketed(bow, cholotl.id()).stream().anyMatch(m -> m.stat().equals("skill.chain_terrain") && m.value() == 20));
        assertTrue(Runes.uselessOn(sword, cholotl), "不是弓的武器沒效果");
        // 一般的軍用武器魂核嵌在弓上照樣有武器那條（弓沒寫 slots.bow 的照 weapon）
        assertFalse(repository.rune("core_opiloti").on(bow).isEmpty());
    }

    @Test
    void atziriCountsCorruptedItems() {
        final ItemBase helm = base(b -> b.slot() == ItemSlot.HEAD && b.armour() + b.evasion() + b.energyShield() > 0);
        final List<Modifier> mods = socketed(helm, "core_atziri_devotion");
        assertTrue(mods.stream().anyMatch(m -> m.stat().startsWith(Runes.PER_CORRUPTED_ITEM)));
        final List<Modifier> three = Runes.perCorruptedItem(mods, 3);
        assertEquals(1, three.size());
        assertEquals(StatKeys.SPIRIT_MAX, three.getFirst().stat());
        assertEquals(ModType.INCREASED, three.getFirst().type());
        assertEquals(0.03, three.getFirst().value(), 1e-9, "3 件腐化裝備 → 增加 3% 精魂（INCREASED 存成小數）");
        assertTrue(Runes.perCorruptedItem(mods, 0).isEmpty());
        assertTrue(tw.exilecore.currency.VaalCrafting.socketBound("core_atziri_vitality"), "阿茲里的嵌上去拿不下來");
        assertFalse(tw.exilecore.currency.VaalCrafting.socketBound("core_tacati"));
    }

    @Test
    void tacatiLowersChaosResistOfCursedEnemies() {
        final StatSheet defender = new StatSheet();
        defender.setBase(StatKeys.RESIST_MAX, 75);
        final StatSheet attacker = new StatSheet();
        attacker.add(new Modifier(DamageCalculator.ENEMY_RESIST + "chaos", ModType.FLAT, -5, Set.of("vs_cursed"), "測試"));
        final DamagePacket hit = new DamagePacket().set(DamageType.CHAOS, 100);
        assertEquals(100, DamageCalculator.mitigate(hit, defender, attacker, Set.of()).get(DamageType.CHAOS), 1e-6, "沒詛咒不算");
        assertEquals(105, DamageCalculator.mitigate(hit, defender, attacker, Set.of("vs_cursed")).get(DamageType.CHAOS), 1e-6, "詛咒的敵人 -5% 混沌抗性");
    }

    @Test
    void armourAppliesToOneElement() {
        final StatSheet defender = new StatSheet();
        defender.setBase(StatKeys.RESIST_MAX, 75);
        defender.add(Modifier.flat(StatKeys.ARMOUR, 5000, "測試"));
        defender.add(new Modifier(StatKeys.ARMOUR_ELEMENTAL, ModType.FLAT, 40, Set.of("cold"), "哈尤西"));
        final double cold = DamageCalculator.mitigate(new DamagePacket().set(DamageType.COLD, 100), defender, null, Set.of()).get(DamageType.COLD);
        final double fire = DamageCalculator.mitigate(new DamagePacket().set(DamageType.FIRE, 100), defender, null, Set.of()).get(DamageType.FIRE);
        assertTrue(cold < 100, "冰冷吃護甲");
        assertEquals(100, fire, 1e-6, "火焰不吃");
    }
}
