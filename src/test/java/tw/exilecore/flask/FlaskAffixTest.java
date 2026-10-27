package tw.exilecore.flask;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：藥劑、護符是物品引擎的基底，擲 PoE2 的藥劑詞綴（affixes/flasks.yml）。 */
class FlaskAffixTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static FlaskConfig config;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        config = new FlaskConfig();
        config.load(DATA);
    }

    @Test
    void everyFlaskHasAnItemBaseButStaysOutOfGearLists() {
        for (final FlaskBase flask : config.bases()) {
            final ItemBase base = repository.base(flask.id());
            assertNotNull(base, flask.id() + " 要有物品引擎的基底");
            assertTrue(base.isFlask() && base.isFlaskLike() && !base.isWearable(), flask.id());
            assertTrue(base.tags().contains("flask") && base.tags().contains(flask.kind() == FlaskKind.LIFE ? "life_flask" : "mana_flask"),
                    flask.id() + " 的標籤 " + base.tags());
            assertEquals(flask.level(), base.requirements().level(), flask.id());
        }
        for (final ItemBase base : repository.bases()) {
            assertFalse(base.isFlaskLike(), base.id() + "：藥劑、護符不該出現在裝備基底的清單裡（圖鑑、商人、新手裝）");
        }
        for (final ItemBase base : repository.basesDroppableAt(100)) {
            assertFalse(base.isFlaskLike(), base.id() + "：藥劑另外掉，不走裝備掉落");
        }
        assertFalse(repository.flaskLikeBases().isEmpty());
    }

    @Test
    void flaskAffixesSpawnOnlyOnFlasksAndCharmAffixesOnlyOnCharms() {
        int flaskAffixes = 0;
        int charmAffixes = 0;
        final Set<String> shared = Set.of(StatKeys.FLASK_LOCAL_CHARGES_GAINED, StatKeys.FLASK_LOCAL_CHARGES_USED, StatKeys.FLASK_LOCAL_MAX_CHARGES,
                StatKeys.FLASK_LOCAL_CHARGE_ON_KILL, StatKeys.FLASK_LOCAL_CHARGES_PER_MINUTE); // 充能類：藥劑、護符都出（PoE2）
        for (final Affix affix : repository.affixes()) {
            if (affix.id().startsWith(tw.exilecore.item.UniqueItem.AFFIX_PREFIX)) {
                continue; // 2026-10-24：傳奇護符自己的詞綴（只出在那件傳奇上，不會隨機出）
            }
            final boolean flaskStat = affix.mods().stream().anyMatch(mod -> mod.stat().startsWith("flask.local.") && !shared.contains(mod.stat()));
            final boolean charmStat = affix.mods().stream().anyMatch(mod -> mod.stat().startsWith("charm.local."));
            final boolean sharedStat = affix.mods().stream().anyMatch(mod -> shared.contains(mod.stat()));
            if (!flaskStat && !charmStat && !sharedStat) {
                for (final ItemBase base : repository.flaskLikeBases()) {
                    assertFalse(affix.canSpawnOn(base), affix.id() + " 是裝備的詞綴，卻會出在 " + base.id() + " 上");
                }
                continue;
            }
            for (final ItemBase base : repository.bases()) {
                assertFalse(affix.canSpawnOn(base), affix.id() + " 是藥劑／護符的詞綴，卻會出在裝備 " + base.id() + " 上");
            }
            boolean onFlask = false;
            boolean onCharm = false;
            for (final ItemBase base : repository.flaskLikeBases()) {
                if (!affix.canSpawnOn(base)) {
                    continue;
                }
                onFlask |= base.isFlask();
                onCharm |= base.isCharm();
                if (flaskStat) {
                    assertTrue(base.isFlask(), affix.id() + " 是藥劑的詞綴，卻出在護符 " + base.id());
                }
                if (charmStat) {
                    assertTrue(base.isCharm(), affix.id() + " 是護符的詞綴，卻出在藥劑 " + base.id());
                }
            }
            if (sharedStat) {
                assertTrue(onFlask && onCharm, affix.id() + " 充能類的詞綴藥劑、護符都要出");
            }
            if (flaskStat || sharedStat) {
                flaskAffixes++;
            }
            if (charmStat || sharedStat) {
                charmAffixes++;
            }
        }
        assertTrue(flaskAffixes >= 10, "藥劑詞綴 " + flaskAffixes);
        assertTrue(charmAffixes >= 4, "護符詞綴 " + charmAffixes);
    }

    @Test
    void magicFlaskGetsAtMostOnePrefixAndOneSuffix() {
        final ItemGenerator generator = new ItemGenerator(repository);
        final Random random = new Random(7);
        final ItemBase base = repository.base(config.bases().iterator().next().id());
        for (int i = 0; i < 200; i++) {
            final ItemData data = generator.generate(base, Rarity.MAGIC, 80, random);
            assertEquals(Rarity.MAGIC, data.rarity());
            assertFalse(data.affixes().isEmpty(), "魔法藥劑至少一條詞綴");
            int prefixes = 0;
            int suffixes = 0;
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                assertNotNull(affix, rolled.id());
                assertTrue(affix.canSpawnOn(base), rolled.id() + " 不該出在藥劑上");
                if (affix.type() == AffixType.PREFIX) {
                    prefixes++;
                } else {
                    suffixes++;
                }
            }
            assertTrue(prefixes <= 1 && suffixes <= 1, "藥劑最多一前綴一後綴：" + data.affixes());
            assertEquals(1, generator.maxAffixes(data, AffixType.PREFIX));
            assertEquals(1, generator.maxAffixes(data, AffixType.SUFFIX));
            final FlaskMods mods = FlaskMods.of(repository, data);
            assertFalse(mods.isNone(), "有詞綴就該算出數值：" + data.affixes());
        }
    }

    @Test
    void modsMultiplyBaseNumbers() {
        final FlaskMods mods = FlaskMods.of(List.of(
                new Modifier(StatKeys.FLASK_LOCAL_RECOVERY, ModType.INCREASED, 0.5, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_RECOVERY_LOW, ModType.INCREASED, 0.2, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_SPEED, ModType.INCREASED, 0.25, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_MAX_CHARGES, ModType.INCREASED, 0.3, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_CHARGES_USED, ModType.INCREASED, -0.2, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_CHARGES_GAINED, ModType.INCREASED, 0.4, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_INSTANT, ModType.FLAT, 25, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_CHARGE_ON_KILL, ModType.FLAT, 20, Set.of(), "t"),
                new Modifier(StatKeys.CHARM_LOCAL_DURATION, ModType.INCREASED, 0.5, Set.of(), "t"),
                new Modifier(StatKeys.CHARM_LOCAL_GUARD, ModType.FLAT, 300, Set.of(), "t")));
        assertEquals(150, mods.recoveryAmount(100, false), 1e-9);
        assertEquals(180, mods.recoveryAmount(100, true), 1e-9);
        assertEquals(8, mods.recoveryTime(10), 1e-9);
        assertEquals(78, mods.maxCharges(60));
        assertEquals(8, mods.perUse(10));
        assertEquals(7, mods.gained(5));
        assertEquals(0.25, mods.instant(), 1e-9);
        assertEquals(0.2, mods.chargeOnKill(), 1e-9);
        assertEquals(1.5, mods.duration(), 1e-9);
        assertEquals(300, mods.guard(), 1e-9);
        assertTrue(FlaskMods.of(List.of()).isNone());
        // 2026-10-26 照 PoE2：低生命／低魔力的前綴是「更多」回復（MORE），跟回復量增加相乘
        final FlaskMods more = FlaskMods.of(List.of(
                new Modifier(StatKeys.FLASK_LOCAL_RECOVERY, ModType.INCREASED, 0.5, Set.of(), "t"),
                new Modifier(StatKeys.FLASK_LOCAL_RECOVERY_LOW, ModType.MORE, 1.0, Set.of(), "t")));
        assertEquals(150, more.recoveryAmount(100, false), 1e-9);
        assertEquals(300, more.recoveryAmount(100, true), 1e-9, "150 × 更多 100%");
        assertEquals(1, FlaskMods.NONE.perUse(1));
    }

    @Test
    void instantFlaskRecoversEverythingAtOnce() {
        final Affix affix = repository.affix("flask_instant_full");
        assertNotNull(affix);
        final ItemData data = new ItemData(config.bases().iterator().next().id(), Rarity.MAGIC, 80);
        data.affixes().add(new RolledAffix(affix.id(), affix.tiers().getLast().tier(), affix.tiers().getLast().roll(new Random(1))));
        final FlaskMods mods = FlaskMods.of(repository, data);
        assertEquals(1, mods.instant(), 1e-9);
        assertTrue(mods.recovery() < 1, "立即回復的藥劑回復量比較少：" + mods.recovery());
    }
}
