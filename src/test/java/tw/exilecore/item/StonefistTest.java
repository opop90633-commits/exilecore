package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Recently;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 0.5 的石拳之道（Way of the Stonefist）：腿甲（原作的手套）穿上時整件換成磐石之拳
 * （data/stonefist.yml、{@link Stonefist}），以及 poe2db「磐石之拳 Uniques」的 37 件手套傳奇。
 */
class StonefistTest {

    /** poe2db 的「磐石之拳 Uniques」37 件 → 我們的傳奇代號（16 件以前就有，21 件 2026-10-28 補上）。 */
    static final Map<String, String> STONEFIST_UNIQUES = Map.ofEntries(
            Map.entry("Aerisvane's Wings", "windvane_gauntlets"), Map.entry("Atziri's Acuity", "empress_insight"),
            Map.entry("Aurseize", "goldsnatch_greaves"), Map.entry("Blueflame Bracers", "azureflame_cuffs"),
            Map.entry("Candlemaker", "wickmaker"), Map.entry("Death Articulated", "bonejoint_gauntlets"),
            Map.entry("Deathblow", "fugitive_greaves"), Map.entry("Demon Stitcher", "seamwright"),
            Map.entry("Doedre's Tenure", "hexwife_wraps"), Map.entry("Dreadfist", "fearfist"),
            Map.entry("Empire's Grasp", "rampart_greaves"), Map.entry("Essentia Sanguis", "blood_drinker"),
            Map.entry("Facebreaker", "toothcollector"), Map.entry("Gravebind", "tombgrip"),
            Map.entry("Grip of Winter", "wintergrasp_wraps"), Map.entry("Hand of Wisdom and Action", "wisdom_action_wraps"),
            Map.entry("Hateforge", "spiteforge_greaves"), Map.entry("Horror's Flight", "terror_glide"),
            Map.entry("Idle Hands", "restless_hands"), Map.entry("Jarngreipr", "irongrip_gauntlets"), Map.entry("Killjoy", "spoilsport"),
            Map.entry("Kitoko's Current", "kido_current"), Map.entry("Leopold's Applause", "ovation_gloves"),
            Map.entry("Lochtonial Caress", "mindtouch"), Map.entry("Maligaro's Virtuosity", "marg_bloodgloves"),
            Map.entry("Nightscale", "duskscale_gloves"), Map.entry("Northpaw", "northclaw_bracers"),
            Map.entry("Painter's Servant", "crimson_brush"), Map.entry("Plaguefinger", "festerfinger"),
            Map.entry("Shackles of the Wretched", "misery_shackles"), Map.entry("Sine Aequo", "peerless"),
            Map.entry("Snakebite", "viperfang_bracers"), Map.entry("The Master's Reach", "binder_grasp"),
            Map.entry("The Prisoner's Manacles", "captive_manacles"), Map.entry("Thunderfist", "stormfist"),
            Map.entry("Treefingers", "treefinger_mitts"), Map.entry("Valako's Vice", "stormvice_gauntlets"));

    /** 2026-10-28 補上的 21 件。 */
    static final Set<String> ADDED = Set.of("empress_insight", "wickmaker", "seamwright", "fearfist", "blood_drinker", "toothcollector",
            "tombgrip", "terror_glide", "restless_hands", "irongrip_gauntlets", "spoilsport", "kido_current", "mindtouch", "marg_bloodgloves",
            "crimson_brush", "festerfinger", "misery_shackles", "peerless", "captive_manacles", "stormfist", "binder_grasp");

    /** 腿甲擲得到、但原作沒有磐石之拳對應的詞綴（照原本的生效）：我們自己的揭露詞綴。 */
    static final Set<String> KEPT = Set.of("unveiled_d_armour_attack", "unveiled_d_armour_spell", "unveiled_d_armour_minion",
            "unveiled_d_armour_thorns", "unveiled_d_curse_area", "unveiled_d_bleed_chance", "unveiled_d_poison_chance",
            "unveiled_d_ailment_effect", "unveiled_d_gloves_extra_frenzy");

    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException, IllegalAccessException {
        UniqueDataTest.load(); // 屬性鍵、標籤的清單（資料檢查用）
        repository = UniqueDataTest.repository;
    }

    @Test
    void thirtySevenGloveUniquesAllHaveStonefistVersions() {
        assertEquals(37, STONEFIST_UNIQUES.size());
        assertEquals(37, Set.copyOf(STONEFIST_UNIQUES.values()).size());
        final Stonefist.Data data = repository.stonefist();
        for (final String id : STONEFIST_UNIQUES.values()) {
            final UniqueItem unique = repository.unique(id);
            assertNotNull(unique, id);
            assertEquals(ItemSlot.LEGS, repository.base(unique.base()).slot(), id + "：PoE2 的手套在我們這裡是腿甲");
            assertTrue(data.uniques().containsKey(id), id + " 要有磐石之拳版本");
        }
        assertEquals(Set.copyOf(STONEFIST_UNIQUES.values()), data.uniques().keySet(), "stonefist.yml 只放這 37 件");
        for (final String id : ADDED) {
            assertTrue(STONEFIST_UNIQUES.containsValue(id), id);
        }
        assertEquals("雷霆之拳", repository.unique("stormfist").name());
        assertEquals("支配之觸", repository.unique("binder_grasp").name());
    }

    @Test
    void newUniquesDropWhereTheyDropInRef() {
        assertEquals(Set.of("abyss_lich"), repository.unique("binder_grasp").sources(), "骸王的容器");
        assertEquals(Set.of("awakener"), repository.unique("peerless").sources(), "灰燼仲裁者（巔峰）");
        assertEquals(Set.of("map_boss"), repository.unique("terror_glide").sources(), "渡鴉欺詐者（Boss 限定，做不出來）");
        for (final String id : ADDED) {
            if (!Set.of("binder_grasp", "peerless", "terror_glide").contains(id)) {
                assertEquals(Set.of("map_boss", "chance", "gamble"), repository.unique(id).sources(), id + "：原作是全域掉落");
            }
        }
    }

    @Test
    void stonefistDataPointsAtRealAffixesUniquesAndStats() {
        final Stonefist.Data data = repository.stonefist();
        assertFalse(data.affixes().isEmpty());
        for (final Map.Entry<String, Affix> entry : data.affixes().entrySet()) {
            final Affix original = repository.affix(entry.getKey());
            assertNotNull(original, "stonefist.yml 的 " + entry.getKey() + " 不是我們的詞綴");
            for (final AffixTier tier : entry.getValue().tiers()) {
                assertFalse(original.ranges(tier.tier()).isEmpty(), entry.getKey() + " 沒有階級 " + tier.tier());
            }
            for (final AffixTier tier : original.tiers()) {
                assertFalse(entry.getValue().ranges(tier.tier()).isEmpty(), entry.getKey() + " 的階級 " + tier.tier() + " 沒有對應");
            }
            checkMods(entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<String, List<Stonefist.Line>> entry : data.uniques().entrySet()) {
            final UniqueItem unique = repository.unique(entry.getKey());
            assertNotNull(unique, entry.getKey());
            final Set<Integer> used = new HashSet<>();
            for (final Stonefist.Line line : entry.getValue()) {
                assertTrue(line.from() >= 0 && line.from() <= unique.affixIds().size(), entry.getKey() + " 的 from " + line.from());
                assertTrue(line.from() == 0 || used.add(line.from()), entry.getKey() + " 的 from " + line.from() + " 重複");
                checkMods(entry.getKey(), line.affix());
            }
        }
    }

    private static void checkMods(final String where, final Affix affix) {
        for (final AffixMod mod : affix.mods()) {
            final String stat = Recently.stat(mod.stat());
            if (Recently.hasCondition(mod.stat())) {
                assertNotNull(Recently.condition(mod.stat()), where + "：不認得的條件 " + mod.stat());
            }
            assertTrue(UniqueDataTest.stats.contains(stat) || Stonefist.PER_LEVEL.contains(stat), where + "：不認得的屬性 " + mod.stat());
            for (final String tag : mod.tags()) {
                assertTrue(UniqueDataTest.tags.contains(tag), where + "：不認得的標籤 " + tag);
            }
            if (mod.variable() != null) {
                assertTrue(affix.text().contains("{" + mod.variable() + "}") || affix.text().contains("{" + mod.variable() + "/")
                        || mod.variable().equals("level"), where + " 的文字沒有 {" + mod.variable() + "}");
                for (final AffixTier tier : affix.tiers()) {
                    assertTrue(tier.vars().containsKey(mod.variable()), where + " 缺少變數 " + mod.variable());
                }
            }
        }
    }

    @Test
    void everyExplicitAffixALegCanRollHasAStonefistVersion() {
        final Set<String> missing = new java.util.TreeSet<>();
        for (final ItemBase base : repository.bases()) {
            if (base.slot() != ItemSlot.LEGS) {
                continue;
            }
            for (final Affix affix : repository.affixes()) {
                if (!affix.canSpawnOn(base) || affix.spawnTags().contains(ItemRepository.RETIRED_TAG)
                        || affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG) || affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
                    continue;
                }
                if (!repository.stonefist().affixes().containsKey(affix.id()) && !KEPT.contains(affix.id())) {
                    missing.add(affix.id());
                }
            }
        }
        assertEquals(Set.of(), missing, "腿甲擲得到的詞綴要有磐石之拳版本（或寫進 KEPT）");
    }

    private static double sum(final List<Modifier> mods, final String stat, final ModType type) {
        double total = 0;
        for (final Modifier mod : mods) {
            if (mod.stat().equals(stat) && mod.type() == type) {
                total += mod.value();
            }
        }
        return total;
    }

    @Test
    void rareLegsBecomeFistsOfStone() {
        final ItemBase base = repository.base("legs_str_16");
        final ItemData data = new ItemData(base.id(), Rarity.RARE, 84);
        data.setIdentified(true);
        final Affix life = repository.affix("life_flat");
        final int[] lifeRange = life.ranges(1).get("value");
        data.affixes().add(new RolledAffix("life_flat", 1, Map.of("value", lifeRange[1]))); // 最高
        data.affixes().add(new RolledAffix("armour_inc", 1, Map.of("value", repository.affix("armour_inc").ranges(1).get("value")[0]))); // 最低
        data.affixes().add(new RolledAffix("attack_speed", 1, Map.of("value", repository.affix("attack_speed").ranges(1).get("value")[1])));

        final List<Modifier> before = ItemStats.modifiers(repository, data);
        assertTrue(sum(before, StatKeys.ARMOUR, ModType.FLAT) > 0, "原本有基底護甲");
        assertTrue(sum(before, StatKeys.LIFE_MAX, ModType.FLAT) > 0);

        final List<Modifier> after = Stonefist.modifiers(repository, base, data, 50);
        assertEquals(0, sum(after, StatKeys.ARMOUR, ModType.FLAT), 1e-9, "基底護甲換掉了");
        assertEquals(0, sum(after, StatKeys.LIFE_MAX, ModType.FLAT), 1e-9, "+最大生命換成低血時受到的傷害更少");
        assertEquals(3 * 50, sum(after, StatKeys.EVASION, ModType.FLAT), 1e-9, "每一玩家等級 +3 閃避值");
        assertEquals(50, sum(after, StatKeys.ES_MAX, ModType.FLAT), 1e-9, "每一玩家等級 +1 最大能量護盾");
        assertEquals(-13, sum(after, "debuff.taken.fire@lowlife", ModType.FLAT), 1e-9, "T1 +生命 → 低血時受到的傷害更少 13%");
        assertEquals(0.19, sum(after, StatKeys.EVASION, ModType.MORE), 1e-9, "T1 增加護甲（最低）→ 更多 19%（19–20 的最低）");
        assertEquals(0.19, sum(after, StatKeys.ES_MAX, ModType.MORE), 1e-9);
        assertEquals(30, sum(after, StatKeys.ONSLAUGHT_ON_HIT, ModType.FLAT), 1e-9, "T1 攻擊速度（最高）→ 30% 機率疾速");
        assertEquals(0, sum(after, StatKeys.ATTACK_SPEED, ModType.INCREASED), 1e-9);

        final List<String> lines = Stonefist.lines(repository, data, 50);
        assertTrue(lines.size() >= 6, lines.toString());
        for (final String line : lines) {
            assertFalse(line.contains("{"), line);
        }
        assertEquals(1, Stonefist.requirements(ItemStats.requirements(repository, base, data)).level(), "磐石之拳需求等級 1");
    }

    @Test
    void uniquesBecomeTheirStonefistVersion() {
        final ItemGenerator generator = new ItemGenerator(repository);
        final ItemData northpaw = generator.generateUnique(repository.unique("northclaw_bracers"), 70, new Random(3));
        // 第 3 行「增加 (10–15)% 暴擊傷害加成」擲到 15（最高）→ 磐石之拳「增加 (15–20)% 暴擊傷害加成」的最高
        northpaw.affixes().set(2, new RolledAffix("unique.northclaw_bracers.3", 1, Map.of("value", 15)));
        final ItemBase base = repository.base(northpaw.base());
        final List<Modifier> mods = Stonefist.modifiers(repository, base, northpaw, 40);
        assertEquals(0.20, sum(mods, StatKeys.CRIT_BONUS, ModType.INCREASED), 1e-9);
        assertEquals(0, sum(mods, tw.exilecore.item.unique.UniqueStats.CRIT_WEAPON_BASE, ModType.FLAT), 1e-9, "武器基礎暴擊 7% 換掉了");
        assertTrue(sum(mods, "crit.base_extra@unarmed", ModType.FLAT) >= 3, "換成空手近戰暴擊率 +3–6%");
        assertEquals((3 + 2) * 40, sum(mods, StatKeys.EVASION, ModType.FLAT), 1e-9, "+閃避 → 每一玩家等級 +2 閃避值（加上磐石之拳的 +3）");
        assertEquals((1 + 1) * 40, sum(mods, StatKeys.ES_MAX, ModType.FLAT), 1e-9);

        // 原作多一行的（里歐波德的掌聲：照敵人最低的元素抗性算）
        final ItemData applause = generator.generateUnique(repository.unique("ovation_gloves"), 70, new Random(5));
        final List<Modifier> applauseMods = Stonefist.modifiers(repository, repository.base(applause.base()), applause, 60);
        assertEquals(1, sum(applauseMods, tw.exilecore.item.unique.UniqueStats.LOWEST_RESIST, ModType.FLAT), 1e-9);
        assertEquals(0, sum(applauseMods, StatKeys.PENETRATION, ModType.FLAT), 1e-9, "穿透換成 +全部元素抗性");

        // 原作拿掉的行（馬雷葛蘿：暴擊率無法被重骰）
        final ItemData maligaro = generator.generateUnique(repository.unique("marg_bloodgloves"), 70, new Random(7));
        final List<Modifier> maligaroMods = Stonefist.modifiers(repository, repository.base(maligaro.base()), maligaro, 60);
        assertEquals(0, sum(maligaroMods, tw.exilecore.item.unique.UniqueStats.CRIT_NO_REROLL, ModType.FLAT), 1e-9);
        assertEquals(0, sum(maligaroMods, tw.exilecore.item.unique.UniqueStats.CRIT_BONUS_OVERRIDE, ModType.FLAT), 1e-9);
        final double override = sum(maligaroMods, tw.exilecore.item.unique.UniqueStats.CRIT_ATTACK_OVERRIDE, ModType.FLAT);
        assertTrue(override >= 25 && override <= 40, "攻擊的暴擊率為 25–40%：" + override);
    }

    @Test
    void everyNewUniqueRollsAndTransforms() {
        final ItemGenerator generator = new ItemGenerator(repository);
        for (final String id : STONEFIST_UNIQUES.values()) {
            final ItemData data = generator.generateUnique(repository.unique(id), 84, new Random(id.hashCode()));
            final ItemBase base = repository.base(data.base());
            assertFalse(ItemStats.modifiers(repository, data).isEmpty(), id);
            final List<Modifier> mods = Stonefist.modifiers(repository, base, data, 70);
            for (final Modifier mod : mods) {
                assertFalse(Stonefist.PER_LEVEL.contains(mod.stat()), id + "：每一玩家等級的要換算成閃避值、能量護盾");
                assertFalse(Double.isNaN(mod.value()), id + " " + mod);
            }
            for (final String line : Stonefist.lines(repository, data, 70)) {
                assertFalse(line.contains("{") || line.contains("}"), id + "：" + line);
            }
        }
    }

    @Test
    void rollPositionCarriesOver() {
        assertEquals(15, Stonefist.at(new int[] {10, 20}, 0.5));
        assertEquals(12, Stonefist.at(new int[] {12, 15}, 0));
        assertEquals(20, Stonefist.at(new int[] {25, 20}, 1), "寫成 (25—20) 的越後面越好");
    }
}
