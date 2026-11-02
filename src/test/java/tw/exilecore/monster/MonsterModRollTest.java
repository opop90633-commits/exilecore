package tw.exilecore.monster;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 照 PoE2 0.5 補怪物詞綴：抽詞綴的上下限、星圖天賦的加成、Boss 多抽詞綴的排除清單（純函式），
 * 以及 monsters.yml 的新詞綴都讀得到、monster.* 的特殊行為都有實作（資料）。
 */
class MonsterModRollTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MonsterConfig config;

    /** 這次新增的詞綴（PoE2 原名寫在 monsters.yml）。 */
    static final List<String> NEW_MODS = List.of("unstunnable", "periodic_enrage", "enraged", "invulnerability_aura",
            "empowered_invulnerability_aura", "fire_death_blast", "cold_death_blast", "lightning_death_blast", "burning_ground",
            "chilled_ground", "shocked_ground", "elemental_hazards", "volatile_crystals", "volatile_plants", "empowering_volatile_plants",
            "volatile_crag", "empowering_volatile_crag", "minion_shield", "empowered_reviver", "corrupted_culling", "corrupted_gigantic",
            "corrupted_raise");

    @BeforeAll
    static void load() throws IOException {
        config = new MonsterConfig();
        config.load(DATA);
    }

    // ---- 純函式：詞綴數 ----

    @Test
    void rareRangeFollowsAtlasPassives() {
        assertArrayEquals(new int[] {2, 4}, MonsterService.rareRange(2, 4, 0, 0), "沒有天賦：monsters.yml 的 [2, 4]");
        assertArrayEquals(new int[] {2, 5}, MonsterService.rareRange(2, 4, 1, 0), "Adaptive Biology：上限 +1");
        assertArrayEquals(new int[] {2, 6}, MonsterService.rareRange(2, 4, 2, 0), "再加 Mutating Monsters：上限 +2");
        assertArrayEquals(new int[] {3, 4}, MonsterService.rareRange(2, 4, 0, 3), "No Simple Battles：至少 3 條");
        assertArrayEquals(new int[] {3, 6}, MonsterService.rareRange(2, 4, 2, 3), "三個都點");
        assertArrayEquals(new int[] {5, 5}, MonsterService.rareRange(2, 4, 0, 5), "下限比上限高：上限跟著拉上去");
        assertArrayEquals(new int[] {2, 4}, MonsterService.rareRange(2, 4, -3, 0), "負的上限加成不算");
    }

    @Test
    void rollCountStaysInRangeAndMoreMeansMore() {
        final Random random = new Random(42);
        final Map<Integer, Integer> seen = new HashMap<>();
        double plain = 0;
        double boosted = 0;
        final int rolls = 20000;
        for (int i = 0; i < rolls; i++) {
            final int count = MonsterService.rollCount(2, 6, 0, random);
            assertTrue(count >= 2 && count <= 6, "超出範圍：" + count);
            seen.merge(count, 1, Integer::sum);
            plain += count;
            final int more = MonsterService.rollCount(2, 6, MonsterService.combinedMore(0, 0.2), random);
            assertTrue(more >= 2 && more <= 6, "超出範圍：" + more);
            boosted += more;
        }
        assertEquals(5, seen.size(), "沒有加成：2～6 每一種都抽得到");
        assertTrue(boosted / rolls > plain / rolls + 0.1, "天賦兩個 +10%：平均要比沒有加成多（" + boosted / rolls + " vs " + plain / rolls + "）");
        assertEquals(4, MonsterService.rollCount(4, 4, 5, random), "上下限一樣：固定");
        for (int i = 0; i < 100; i++) {
            assertEquals(6, MonsterService.rollCount(2, 6, 1.0, random), "機率加到 100%：一定滿");
        }
        assertEquals(0.21, MonsterService.combinedMore(0.1, 0.1), 1e-9, "換界石的更多跟天賦的增加相乘");
        assertEquals(0.0, MonsterService.combinedMore(-1, 0), 1e-9, "負的不算");
    }

    @Test
    void pickRespectsLevelSkillsAndExisting() {
        final Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            final List<String> picked = MonsterService.pick(config.mods(), 6, 60, random, mod -> true, List.of("swift"));
            assertEquals(picked.size(), new HashSet<>(picked).size(), "不重複");
            assertFalse(picked.contains("swift"), "已經有的不抽");
            int skills = 0;
            for (final String id : picked) {
                final MonsterConfig.MonsterMod mod = config.mod(id);
                assertTrue(mod.minLevel() <= 60, id + "：等級不到");
                assertTrue(mod.minLevel() < MonsterService.HIDDEN_LEVEL, id + "：隱藏的不會抽到");
                skills += mod.hasSkills() ? 1 : 0;
            }
            assertTrue(skills <= MonsterService.MAX_SKILL_MODS, "帶招式的最多兩條");
        }
        final List<String> low = MonsterService.pick(config.mods(), 50, 1, random, mod -> true, List.of());
        for (final String id : low) {
            assertEquals(1, config.mod(id).minLevel(), id + "：1 級只抽得到 min_level 1 的");
        }
    }

    // ---- 純函式：Boss 的排除清單 ----

    @Test
    void bossExtraModsSkipThingsThatBreakBosses() {
        for (final String id : List.of("slammer", "blinker", "shroud_walker", "elemental_hazards", "fire_unleasher", "summoner", "commander",
                "proximal", "reviver", "empowered_reviver", "minion_shield", "invulnerability_aura", "empowered_invulnerability_aura",
                "corpse_exploder", "resistant", "pinnacle_resist", "warlord_plate", "empowered_minion", "corrupted_raise",
                "corrupted_gigantic", "corrupted_culling")) {
            assertNotNull(config.mod(id), id);
            assertFalse(MonsterService.bossSafe(config.mod(id)), id + "：Boss 不能多抽這條");
        }
        for (final String id : List.of("swift", "sturdy", "fiery", "armoured", "unstunnable", "enraged", "periodic_enrage", "temporal_bubble",
                "fire_death_blast", "burning_ground", "volatile_plants", "volatile_crag", "volatile_crystals", "regenerating", "haste_aura")) {
            assertTrue(MonsterService.bossSafe(config.mod(id)), id + "：Boss 可以多抽這條");
        }
        final Random random = new Random(3);
        final List<String> fixed = List.of("armoured", "pinnacle_resist");
        for (int i = 0; i < 300; i++) {
            final List<String> extra = MonsterService.pick(config.mods(), 2, 84, random, MonsterService::bossSafe, fixed);
            assertEquals(2, extra.size(), "Boss 多兩條");
            for (final String id : extra) {
                assertTrue(MonsterService.bossSafe(config.mod(id)), id);
                assertFalse(fixed.contains(id), id + "：跟 maps.yml 寫的重複");
            }
        }
    }

    // ---- 資料：新詞綴都讀得到、monster.* 都有實作 ----

    @Test
    void newModsLoadWithChineseNames() {
        final Set<String> names = new HashSet<>();
        for (final MonsterConfig.MonsterMod mod : config.mods()) {
            if (!NEW_MODS.contains(mod.id())) {
                names.add(mod.name());
            }
        }
        for (final String id : NEW_MODS) {
            final MonsterConfig.MonsterMod mod = config.mod(id);
            assertNotNull(mod, "monsters.yml 沒有 " + id);
            assertTrue(mod.name().endsWith("的"), id + "：中文名要「…的」結尾：" + mod.name());
            assertTrue(names.add(mod.name()), id + "：名字跟別條重複：" + mod.name());
            assertFalse(mod.mods().isEmpty() && mod.aura() == null && !mod.hasSkills(), id + "：要有效果");
        }
        for (final MonsterConfig.MonsterMod mod : config.mods()) {
            if (mod.id().startsWith(MonsterService.CORRUPTED_PREFIX)) {
                assertTrue(mod.minLevel() >= MonsterService.HIDDEN_LEVEL, mod.id() + "：腐化詞綴只有腐化區會加，不能自然抽到");
            }
        }
        assertTrue(config.mods().stream().filter(mod -> mod.id().startsWith(MonsterService.CORRUPTED_PREFIX)).count() >= 3, "腐化詞綴");
    }

    @Test
    void poe2ValuesOnExistingMods() {
        assertEquals(5, config.mod("warlord_aura").aura().radius(), 1e-9, "Extra Physical Damage Aura：5 公尺");
        assertEquals(40, firstValue(config.mod("warlord_aura").aura().stats(), "damage"), 1e-9, "物理傷害增加 40%");
        assertEquals(35, firstValue(config.mod("determination_aura").aura().stats(), "resist.fire"), 1e-9, "Elemental Resistance Aura 35%");
        assertEquals(20, firstValue(config.mod("shield_aura").aura().stats(), StatKeys.MONSTER_SHIELD), 1e-9, "Energy Shield Aura 20%");
        assertEquals(3.6, firstValue(config.mod("hindering").mods(), StatKeys.MONSTER_HINDER_AURA), 1e-9, "Hinder Aura 3.6 公尺");
        assertEquals(3.2, firstValue(config.mod("temporal_bubble").mods(), StatKeys.MONSTER_TEMPORAL_BUBBLE), 1e-9, "Temporal Bubble 3.2 公尺");
        assertEquals(1000, firstValue(config.mod("shattering").mods(), StatKeys.ARMOUR_BREAK), 1e-9, "Breaks Armour 1000%");
        assertEquals(25, firstValue(config.mod("shielded").mods(), StatKeys.MONSTER_SHIELD), 1e-9, "Extra Energy Shield 25%");
        assertEquals(-33, firstValue(config.mod("enraged").mods(), StatKeys.DAMAGE_TAKEN), 1e-9, "Enraged：受到的傷害少 33%");
        assertEquals(50, firstValue(config.mod("minion_shield").mods(), StatKeys.MONSTER_MINION_SHIELD), 1e-9, "50% 先由小怪扛");
        // 2026-11-02 照 PoE2 的怪物技能資料（RePoE skills.json、keywords.json）
        assertEquals(164, firstValue(config.mod("volatile_plants").mods(), StatKeys.MONSTER_VOLATILE_PLANTS), 1e-9, "爆花 12–18 ÷ 9.16");
        assertEquals(202, firstValue(config.mod("empowering_volatile_plants").mods(), StatKeys.MONSTER_VOLATILE_PLANTS), 1e-9, "強化爆花 15–22");
        assertEquals(20, firstValue(config.mod("corrupted_gigantic").mods(), "life.max"), 1e-9, "Gigantic：生命更多 20%");
        assertEquals(20, firstValue(config.mod("corrupted_gigantic").mods(), "damage"), 1e-9, "Gigantic：傷害更多 20%");
        assertEquals(3.82, MonsterMechanics.deathBlastDamage(tw.exilecore.stats.DamageType.FIRE), 0.01, "死亡爆炸 28–42");
        assertEquals(3.77, MonsterMechanics.deathBlastDamage(tw.exilecore.stats.DamageType.LIGHTNING), 0.01, "死亡爆炸（雷）17–52");
        assertEquals(2.57, MonsterMechanics.CRYSTAL_DAMAGE, 0.01, "晶體 19–28");
        assertEquals(2.57, MonsterMechanics.CRAG_DAMAGE, 0.01, "爆岩 19–28");
        assertEquals(1.80, MonsterMechanics.groundNovaDamage(tw.exilecore.ailment.AilmentType.IGNITE), 0.01, "焦土新星 13–20");
    }

    @Test
    void everyMonsterStatIsImplemented() throws Exception {
        // StatKeys 的常數名 → 值
        final Map<String, String> constants = new HashMap<>();
        for (final Field field : StatKeys.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put((String) field.get(null), field.getName());
            }
        }
        final Set<String> used = new HashSet<>();
        for (final MonsterConfig.MonsterMod mod : config.mods()) {
            final List<AffixMod> all = new ArrayList<>(mod.mods());
            if (mod.aura() != null) {
                all.addAll(mod.aura().stats());
            }
            for (final AffixMod stat : all) {
                if (stat.stat().startsWith(StatKeys.MONSTER_PREFIX)) {
                    assertTrue(StatKeys.MONSTER_KEYS.contains(stat.stat()), mod.id() + "：不認得的特殊行為 " + stat.stat());
                    used.add(stat.stat());
                }
            }
        }
        assertTrue(used.size() >= 30, "monster.* 的特殊行為：" + used.size());
        // 實作：常數要在 StatKeys 以外的程式（monster 套件、戰鬥）裡用到
        final StringBuilder sources = new StringBuilder();
        for (final Path dir : List.of(Path.of("src/main/java/tw/exilecore/monster"), Path.of("src/main/java/tw/exilecore/combat"))) {
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    sources.append(Files.readString(file));
                }
            }
        }
        for (final String key : StatKeys.MONSTER_KEYS) {
            final String name = constants.get(key);
            assertNotNull(name, key + "：StatKeys 沒有這個常數");
            assertTrue(sources.indexOf("StatKeys." + name) >= 0, key + "（" + name + "）：monster／combat 的程式沒用到，沒有實作");
        }
    }

    private static double firstValue(final List<AffixMod> mods, final String stat) {
        for (final AffixMod mod : mods) {
            if (mod.stat().equals(stat)) {
                return mod.constant();
            }
        }
        throw new AssertionError("沒有 " + stat);
    }
}
