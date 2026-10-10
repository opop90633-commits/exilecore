package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.charge.ChargeConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapStats;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全部詞綴（裝備、珠寶、地圖）的資料審核：屬性鍵是引擎真的會讀的、標籤存在、變數對得上、會出在該出的基底上；
 * 加上這一批新的機制：小數擲骰（{value/10}）、武器本身的詞綴（local.）、補階後舊代號不變。
 */
class AffixCatalogueTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    /** 引擎認得的屬性鍵：StatKeys 的常數＋damage.gain.類型＋charge.chance／charge.on_hit／charge.max.球＋local.。 */
    static Set<String> stats;
    static Set<String> tags;
    /** 引擎用空的情境查的屬性：寫標籤也不會生效（召喚物的例外：帶 minion 的給召喚物）。 */
    static final Set<String> UNTAGGED = Set.of(StatKeys.LIFE_ON_KILL, StatKeys.MANA_ON_KILL, StatKeys.ES_ON_KILL,
            StatKeys.ES_RECHARGE, StatKeys.ES_DELAY, StatKeys.THORNS_MIN, StatKeys.THORNS_MAX, StatKeys.CHARGE_DURATION,
            StatKeys.FLASK_LIFE_RECOVERY, StatKeys.FLASK_MANA_RECOVERY, StatKeys.AILMENT_THRESHOLD, StatKeys.SELF_AILMENT_DURATION,
            StatKeys.DROP_QUANTITY, StatKeys.DROP_RARITY, StatKeys.RESIST_MAX);
    /** 地圖本身的屬性（MapStats 讀的）。 */
    static final Set<String> MAP_STATS = Set.of("map.quantity", "map.rarity", "map.pack_size", "map.magic_packs", "map.rare_packs");

    @BeforeAll
    static void load() throws IOException, IllegalAccessException {
        repository = new ItemRepository();
        repository.load(DATA);
        stats = constants(StatKeys.class);
        stats.remove(StatKeys.DAMAGE_GAIN);
        stats.remove(StatKeys.CHARGE_MAX);
        stats.remove(StatKeys.CHARGE_CHANCE);
        for (final DamageType type : DamageType.values()) {
            if (type != DamageType.PHYSICAL) {
                stats.add(StatKeys.damageGain(type));
            }
        }
        for (final String charge : ChargeConfig.parse(YamlNode.load(DATA.resolve(ChargeConfig.FILE))).keySet()) {
            stats.add(StatKeys.chargeChance(charge));
            stats.add(StatKeys.chargeOnHit(charge));
            stats.add(StatKeys.chargeMax(charge));
        }
        stats.addAll(LocalMods.KNOWN);
        tags = constants(Tags.class);
    }

    private static Set<String> constants(final Class<?> type) throws IllegalAccessException {
        final Set<String> values = new HashSet<>();
        for (final Field field : type.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                values.add((String) field.get(null));
            }
        }
        return values;
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("測試", new java.io.StringReader(text));
    }

    private static boolean isMapAffix(final Affix affix) {
        return affix.spawnTags().contains("map");
    }

    @Test
    void everyAffixUsesStatsAndTagsTheEngineReads() {
        int checked = 0;
        for (final Affix affix : repository.affixes()) {
            if (affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
                continue; // 傳奇的詞綴另外審（UniqueDataTest）
            }
            final String where = affix.id();
            for (final AffixMod mod : affix.mods()) {
                String stat = mod.stat();
                if (isMapAffix(affix)) {
                    if (MAP_STATS.contains(stat)) {
                        continue;
                    }
                    boolean prefixed = false;
                    for (final String prefix : List.of(MapStats.MONSTER_PREFIX, MapStats.BOSS_PREFIX, MapStats.PLAYER_PREFIX)) {
                        if (stat.startsWith(prefix)) {
                            stat = stat.substring(prefix.length());
                            prefixed = true;
                        }
                    }
                    assertTrue(prefixed, where + "：地圖詞綴的屬性要寫 monster:／boss:／player: 或 map.，卻是 " + mod.stat());
                }
                assertTrue(stats.contains(stat), where + "：不認得的屬性 " + mod.stat());
                for (final String tag : mod.tags()) {
                    assertTrue(tags.contains(tag), where + "：不認得的標籤 " + tag);
                }
                if (UNTAGGED.contains(stat)) {
                    assertTrue(mod.tags().isEmpty(), where + "：" + stat + " 引擎不看標籤，寫了也沒用");
                }
                if (stat.equals(StatKeys.LIFE_REGEN_PERCENT) || stat.equals(StatKeys.MOVEMENT_SPEED)) {
                    assertTrue(mod.tags().isEmpty() || mod.tags().equals(Set.of(Tags.MINION)), where + "：" + stat + " 只認沒有標籤（自己）或 minion（召喚物）");
                }
                if (stat.startsWith(StatKeys.DAMAGE_GAIN) || stat.startsWith(StatKeys.CHARGE_CHANCE) || stat.startsWith(StatKeys.CHARGE_MAX)
                        || stat.equals(StatKeys.SKILL_LEVEL) || stat.startsWith("leech.") || stat.startsWith("thorns.")) {
                    assertEquals(ModType.FLAT, mod.type(), where + "：" + stat + " 是百分比點數／固定值，要用 FLAT");
                }
                if (mod.variable() != null) {
                    assertTrue(affix.text().contains("{" + mod.variable() + "}") || affix.text().contains("{" + mod.variable() + "/"),
                            where + " 的文字沒有 {" + mod.variable() + "}");
                    if (mod.divisor() > 1) {
                        assertTrue(affix.text().contains("{" + mod.variable() + "/" + mod.divisor() + "}"),
                                where + "：修飾器除以 " + mod.divisor() + "，文字也要寫 {" + mod.variable() + "/" + mod.divisor() + "}（顯示跟效果一樣）");
                    }
                }
            }
            assertFalse(affix.names().isEmpty(), where + " 沒有名稱");
            assertTrue(affix.names().size() >= affix.tiers().size(), where + "：每個階級都要有自己的名稱（" + affix.names().size() + " 個名稱、"
                    + affix.tiers().size() + " 階）");
            for (final AffixTier tier : affix.tiers()) {
                assertTrue(tier.weight() > 0, where + " 的權重");
                for (final int[] range : tier.vars().values()) {
                    assertTrue(range[0] <= range[1], where + " T" + tier.tier() + " 的範圍下限大於上限");
                }
            }
            checked++;
        }
        assertTrue(checked > 200, "審了 " + checked + " 條");
    }

    @Test
    void strongerTiersNeedHigherItemLevelsAndRollHigherNumbers() {
        for (final Affix affix : repository.affixes()) {
            if (affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
                continue;
            }
            for (int i = 1; i < affix.tiers().size(); i++) {
                final AffixTier stronger = affix.tiers().get(i - 1);
                final AffixTier weaker = affix.tiers().get(i);
                assertTrue(stronger.ilvl() >= weaker.ilvl(), affix.id() + "：T" + stronger.tier() + " 的物品等級要 ≥ T" + weaker.tier());
                for (final Map.Entry<String, int[]> entry : stronger.vars().entrySet()) {
                    final int[] lower = weaker.vars().get(entry.getKey());
                    // 負數範圍（地圖的「玩家 -x% 抗性」）越小越強：比絕對值
                    assertTrue(Math.abs(entry.getValue()[1]) >= Math.abs(lower[1]),
                            affix.id() + "：T" + stronger.tier() + " 的 " + entry.getKey() + " 上限要 ≥ 下一階");
                }
            }
        }
    }

    @Test
    void newAffixesSpawnWhereTheyShould() {
        final ItemBase sword = repository.base("short_sword");
        final ItemBase wand = repository.base("wand");
        final ItemBase staff = repository.base("staff");
        final ItemBase bow = repository.base("bow");
        final ItemBase plateBody = repository.base("plate_body");
        final ItemBase silkBody = repository.base("silk_body");
        final ItemBase ring = repository.base("iron_ring");
        final ItemBase amulet = repository.base("amber_amulet");
        final ItemBase belt = repository.base("leather_belt");
        final ItemBase towerShield = repository.base("tower_shield");
        final ItemBase spiritShield = repository.base("spirit_shield");
        final ItemBase boots = repository.base("leather_boots");

        // 武器本身：攻擊武器才有，施法武器、防具不會有
        for (final String id : List.of("local_phys", "local_phys_accuracy", "local_attack_speed", "local_crit")) {
            assertTrue(repository.affix(id).canSpawnOn(sword), id);
            assertTrue(repository.affix(id).canSpawnOn(bow), id);
            assertFalse(repository.affix(id).canSpawnOn(wand), id + " 不出在法杖");
            assertFalse(repository.affix(id).canSpawnOn(plateBody), id + " 不出在防具");
        }
        // 本地與全域同群組：一把武器只會有一種
        assertEquals(repository.affix("attack_speed").group(), repository.affix("local_attack_speed").group());
        assertEquals(repository.affix("crit_chance").group(), repository.affix("local_crit").group());
        // 混合防禦與荊棘：防具前綴
        assertTrue(repository.affix("armour_life").canSpawnOn(plateBody));
        assertFalse(repository.affix("armour_life").canSpawnOn(silkBody));
        assertTrue(repository.affix("es_life").canSpawnOn(silkBody));
        assertTrue(repository.affix("thorns").canSpawnOn(towerShield));
        assertFalse(repository.affix("thorns").canSpawnOn(spiritShield), "荊棘是力量系的");
        // 技能等級：法術的出在施法武器與項鍊，不出在劍
        assertTrue(repository.affix("skill_level_spell").canSpawnOn(staff));
        assertFalse(repository.affix("skill_level_spell").canSpawnOn(sword));
        assertTrue(repository.affix("skill_level_spell_amulet").canSpawnOn(amulet));
        assertTrue(repository.affix("skill_level_melee").canSpawnOn(sword));
        assertTrue(repository.affix("skill_level_projectile").canSpawnOn(bow));
        assertEquals(3, repository.affix("skill_level_spell").tiers().getFirst().vars().get("value")[1], "武器最多 +3");
        assertEquals(2, repository.affix("skill_level_spell_amulet").tiers().getFirst().vars().get("value")[1], "項鍊最多 +2");
        // 腰帶的藥劑、續戰、球
        assertTrue(repository.affix("flask_life_recovery").canSpawnOn(belt));
        assertFalse(repository.affix("flask_life_recovery").canSpawnOn(ring));
        assertTrue(repository.affix("leech_life").canSpawnOn(ring));
        assertFalse(repository.affix("leech_life").canSpawnOn(wand), "偷取看攻擊的物理傷害：施法武器不出");
        assertTrue(repository.affix("frenzy_charge_max").canSpawnOn(boots));
        assertTrue(repository.affix("gain_fire").canSpawnOn(amulet));
        assertFalse(repository.affix("gain_fire").canSpawnOn(staff));
        // 珠寶的新家族
        assertTrue(repository.affix("jewel_bleed_mag").canSpawnOn(repository.base("ruby_jewel")));
        assertTrue(repository.affix("jewel_ailment_threshold").canSpawnOn(repository.base("sapphire_jewel")), "jewel 標籤：每一種都出");
        assertFalse(repository.affix("jewel_bleed_mag").canSpawnOn(sword));
        // 地圖的詞綴（2026-10 照 PoE2 的換界石詞綴池；自己設計的停用）
        assertTrue(repository.affix("map_monster_armour_break").canSpawnOn(repository.base("map_wreck_bay")));
        assertFalse(repository.affix("map_monster_armour_break").canSpawnOn(sword));
        assertFalse(repository.affix("map_monster_skill_speed").canSpawnOn(repository.base("map_wreck_bay")), "PoE2 沒有：停用");
    }

    @Test
    void spellCritAffixesOnlyHelpSpellsAndOnlyRollOnCasterWeapons() {
        final ItemBase wand = repository.base("wand");
        final ItemBase sceptre = repository.base("sceptre");
        final ItemBase staff = repository.base("staff");
        for (final String id : List.of("spell_crit_chance", "spell_crit_multi")) {
            assertTrue(repository.affix(id).canSpawnOn(wand), id);
            assertTrue(repository.affix(id).canSpawnOn(sceptre), id);
            assertFalse(repository.affix(id).canSpawnOn(staff), id + "：長杖用雙手的版本");
            assertTrue(repository.affix(id + "_2h").canSpawnOn(staff), id + "_2h");
            for (final String other : List.of("short_sword", "bow", "quarterstaff", "iron_ring", "jade_amulet", "silk_body")) {
                assertFalse(repository.affix(id).canSpawnOn(repository.base(other)), id + " 不出在 " + other);
                assertFalse(repository.affix(id + "_2h").canSpawnOn(repository.base(other)), id + "_2h 不出在 " + other);
            }
            assertEquals(repository.affix(id).group(), repository.affix(id + "_2h").group(), "一手、雙手同一個群組");
            final int oneHand = repository.affix(id).tiers().getFirst().vars().get("value")[1];
            final int twoHand = repository.affix(id + "_2h").tiers().getFirst().vars().get("value")[1];
            assertTrue(twoHand > oneHand, id + "：雙手的數值比較高");
            assertEquals(78, repository.affix(id).tiers().getFirst().ilvl(), id + "：最高階要物品等級 78");
        }
        assertTrue(repository.affix("jewel_spell_crit_chance").canSpawnOn(repository.base("sapphire_jewel")));
        assertFalse(repository.affix("jewel_spell_crit_chance").canSpawnOn(repository.base("emerald_jewel")));
        // 法術專用的比通用的高
        assertTrue(repository.affix("spell_crit_chance").tiers().getFirst().vars().get("value")[0]
                > repository.affix("crit_chance").tiers().getFirst().vars().get("value")[1]);
        assertTrue(repository.affix("spell_crit_multi").tiers().getFirst().vars().get("value")[0]
                > repository.affix("crit_multi").tiers().getFirst().vars().get("value")[1]);
        // 只對法術有效：攻擊拿不到
        final Map<String, Integer> rolled = Map.of("value", 60);
        final StatSheet sheet = new StatSheet()
                .add(repository.affix("spell_crit_chance").mods().getFirst().toModifier(rolled, "法杖"))
                .add(repository.affix("spell_crit_multi").mods().getFirst().toModifier(Map.of("value", 40), "法杖"));
        assertEquals(0.6, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.SPELL, Tags.FIRE)), 1e-9);
        assertEquals(0, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.ATTACK, Tags.MELEE)), 1e-9, "攻擊不吃法術暴擊率");
        assertEquals(40, sheet.flat(StatKeys.CRIT_MULTI, Set.of(Tags.SPELL)), 1e-9);
        assertEquals(0, sheet.flat(StatKeys.CRIT_MULTI, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals("增加 60% 法術暴擊率", repository.affix("spell_crit_chance").render(rolled));
    }

    @Test
    void amuletsAndJewelsSplitCritIntoAttackAndSpell() {
        final ItemBase amulet = repository.base("jade_amulet");
        final List<String> amuletOnly = List.of("amulet_spell_crit_chance", "amulet_spell_crit_multi",
                "amulet_attack_crit_chance", "amulet_attack_crit_multi");
        for (final String id : amuletOnly) {
            final Affix affix = repository.affix(id);
            assertNotNull(affix, id);
            assertTrue(affix.canSpawnOn(amulet), id + " 出在項鍊");
            assertTrue(affix.canSpawnOn(repository.base("amber_amulet")), id);
            for (final String other : List.of("iron_ring", "wand", "staff", "short_sword", "bow", "silk_body", "emerald_jewel", "sapphire_jewel")) {
                assertFalse(affix.canSpawnOn(repository.base(other)), id + " 不出在 " + other);
            }
            assertEquals(AffixType.SUFFIX, affix.type(), id);
            assertEquals(78, affix.tiers().getFirst().ilvl(), id + "：最高階要物品等級 78");
        }
        // 通用的照留；法術的跟法杖同一群組，攻擊的是新的群組；跟通用的不同群組（同一條可以都有）
        assertTrue(repository.affix("crit_chance").canSpawnOn(amulet));
        assertTrue(repository.affix("crit_multi").canSpawnOn(amulet));
        assertEquals("spell_crit_chance", repository.affix("amulet_spell_crit_chance").group());
        assertEquals("spell_crit_multi", repository.affix("amulet_spell_crit_multi").group());
        assertEquals("attack_crit_chance", repository.affix("amulet_attack_crit_chance").group());
        assertEquals("attack_crit_multi", repository.affix("amulet_attack_crit_multi").group());
        // 數值：比通用的高、比法杖（一手施法武器）的低
        final int general = repository.affix("crit_chance").tiers().getFirst().vars().get("value")[1];
        final int wand = repository.affix("spell_crit_chance").tiers().getFirst().vars().get("value")[1];
        for (final String id : List.of("amulet_spell_crit_chance", "amulet_attack_crit_chance")) {
            final int[] top = repository.affix(id).tiers().getFirst().vars().get("value");
            assertTrue(top[0] > general && top[1] < wand, id + "：" + top[0] + "–" + top[1]);
        }
        final int generalMulti = repository.affix("crit_multi").tiers().getFirst().vars().get("value")[1];
        final int wandMulti = repository.affix("spell_crit_multi").tiers().getFirst().vars().get("value")[1];
        for (final String id : List.of("amulet_spell_crit_multi", "amulet_attack_crit_multi")) {
            final int[] top = repository.affix(id).tiers().getFirst().vars().get("value");
            assertTrue(top[0] > generalMulti && top[1] < wandMulti, id + "：" + top[0] + "–" + top[1]);
        }
        // 攻擊的只給攻擊、法術的只給法術
        final StatSheet sheet = new StatSheet()
                .add(repository.affix("amulet_attack_crit_chance").mods().getFirst().toModifier(Map.of("value", 50), "項鍊"))
                .add(repository.affix("amulet_attack_crit_multi").mods().getFirst().toModifier(Map.of("value", 40), "項鍊"))
                .add(repository.affix("amulet_spell_crit_chance").mods().getFirst().toModifier(Map.of("value", 30), "項鍊"))
                .add(repository.affix("amulet_spell_crit_multi").mods().getFirst().toModifier(Map.of("value", 20), "項鍊"));
        assertEquals(0.5, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.ATTACK, Tags.MELEE)), 1e-9);
        assertEquals(0.3, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.SPELL, Tags.FIRE)), 1e-9);
        assertEquals(40, sheet.flat(StatKeys.CRIT_MULTI, Set.of(Tags.ATTACK, Tags.PROJECTILE)), 1e-9);
        assertEquals(20, sheet.flat(StatKeys.CRIT_MULTI, Set.of(Tags.SPELL)), 1e-9);
        assertEquals("增加 50% 攻擊暴擊率", repository.affix("amulet_attack_crit_chance").render(Map.of("value", 50)));
        assertEquals("+40% 攻擊暴擊傷害", repository.affix("amulet_attack_crit_multi").render(Map.of("value", 40)));
        // 珠寶：翠星晶有攻擊的、蒼星晶有法術的
        for (final String id : List.of("jewel_attack_crit_chance", "jewel_attack_crit_multi")) {
            assertTrue(repository.affix(id).canSpawnOn(repository.base("emerald_jewel")), id);
            assertFalse(repository.affix(id).canSpawnOn(repository.base("sapphire_jewel")), id);
            assertFalse(repository.affix(id).canSpawnOn(amulet), id);
            assertTrue(repository.affix(id).mods().getFirst().tags().contains(Tags.ATTACK), id);
        }
        // 催化劑：攻擊的加通用與攻擊的暴擊，法術的加法術的暴擊，不會兩邊都加
        final Modifier spellCrit = repository.affix("amulet_spell_crit_chance").mods().getFirst().toModifier(Map.of("value", 30), "項鍊");
        final Modifier attackCrit = repository.affix("amulet_attack_crit_chance").mods().getFirst().toModifier(Map.of("value", 30), "項鍊");
        final Modifier anyCrit = repository.affix("crit_chance").mods().getFirst().toModifier(Map.of("value", 30), "項鍊");
        assertFalse(Catalyst.ATTACK.matches(spellCrit), "攻擊催化劑不加法術暴擊");
        assertTrue(Catalyst.CASTER.matches(spellCrit));
        assertTrue(Catalyst.ATTACK.matches(attackCrit));
        assertFalse(Catalyst.CASTER.matches(attackCrit));
        assertTrue(Catalyst.ATTACK.matches(anyCrit), "通用的暴擊照舊算攻擊");
        assertFalse(Catalyst.CASTER.matches(anyCrit));
    }

    @Test
    void fractionalRollsShowAsDecimalsAndFeedTheEngineTheSameNumber() {
        final Affix leech = repository.affix("leech_life");
        final Map<String, Integer> rolled = Map.of("value", 12);
        assertEquals("攻擊造成的物理傷害有 1.2% 偷取為生命", leech.render(rolled));
        final Modifier modifier = leech.mods().getFirst().toModifier(rolled, "測試");
        assertEquals(StatKeys.LEECH_LIFE, modifier.stat());
        assertEquals(1.2, modifier.value(), 1e-9);
        assertEquals(Set.of(Tags.ATTACK), modifier.tags());
        final String ranged = Affix.substituteWithRanges(leech.text(), rolled, leech.ranges(1));
        assertTrue(ranged.contains("1.2") && ranged.contains("1.1") && ranged.contains("1.3"), ranged);
        assertEquals("每秒回復 0.8% 最大生命", repository.affix("life_regen_pct").render(Map.of("value", 8)));
        assertEquals(0.8, repository.affix("life_regen_pct").mods().getFirst().toModifier(Map.of("value", 8), "測試").value(), 1e-9);
        assertEquals("擊殺時回復 2% 最大生命", repository.affix("life_on_kill").render(Map.of("value", 20)), "整數不留 .0");
        // 取負的：「減少 x%」的進引擎是負的
        final Modifier cost = repository.affix("skill_cost").mods().getFirst().toModifier(Map.of("value", 10), "測試");
        assertEquals(-0.10, cost.value(), 1e-9);
        final Modifier delay = repository.affix("es_delay").mods().getFirst().toModifier(Map.of("value", 30), "測試");
        assertEquals(StatKeys.ES_DELAY, delay.stat());
        assertEquals(-0.30, delay.value(), 1e-9);
        assertEquals("0.05", Affix.number(5, 100));
        assertEquals("1.5", Affix.number(15, 10));
        assertEquals("7", Affix.number(7, 1));
    }

    @Test
    void affixModValuesParseVariablesDivisorsAndSigns() {
        final AffixMod tenth = AffixMod.parse(yaml("{ stat: leech.life, type: FLAT, value: \"{value/10}\" }"));
        assertEquals("value", tenth.variable());
        assertEquals(10, tenth.divisor());
        assertFalse(tenth.negate());
        final AffixMod negativeHundredth = AffixMod.parse(yaml("{ stat: skill.cost, type: INCREASED, value: \"-{x/100}\" }"));
        assertTrue(negativeHundredth.negate());
        assertEquals(100, negativeHundredth.divisor());
        assertEquals(-0.0005, negativeHundredth.toModifier(Map.of("x", 5), "t").value(), 1e-12, "-5 ÷ 100 = -0.05 點，INCREASED 再 ÷ 100");
        assertThrows(IllegalArgumentException.class, () -> AffixMod.parse(yaml("{ stat: a, type: FLAT, value: \"{value/7}\" }")));
        assertThrows(IllegalArgumentException.class, () -> AffixMod.parse(yaml("{ stat: a, type: FLAT, value: \"value\" }")));
        assertEquals(LocalMods.PHYSICAL, AffixMod.parse(yaml("{ stat: local.physical, type: INCREASED }")).stat());
        assertTrue(AffixMod.parse(yaml("{ stat: local.physical, type: INCREASED }")).isLocal());
    }

    /** 一件 1 級的稀有（固定詞綴照常擲），詞綴先清空。 */
    private static ItemData blank(final String baseId) {
        final ItemData data = new ItemGenerator(repository).generate(repository.base(baseId), Rarity.RARE, 1, new Random(7));
        data.affixes().clear();
        return data;
    }

    @Test
    void localWeaponModsChangeTheWeaponItselfNotTheCharacterSheet() {
        final ItemBase sword = repository.base("short_sword");
        final ItemData plain = blank("short_sword");
        final ItemStats.Snapshot before = ItemStats.snapshot(repository, sword, plain);
        assertTrue(before.local().isEmpty());

        final ItemData boosted = blank("short_sword");
        boosted.affixes().add(new RolledAffix("local_phys", 1, new LinkedHashMap<>(Map.of("value", 50))));
        boosted.affixes().add(new RolledAffix("local_attack_speed", 1, new LinkedHashMap<>(Map.of("value", 20))));
        final ItemStats.Snapshot after = ItemStats.snapshot(repository, sword, boosted);
        assertEquals(0.5, after.local().physical(), 1e-9);
        final double[] basePhys = before.damage().get(DamageType.PHYSICAL);
        final double[] boostedPhys = after.damage().get(DamageType.PHYSICAL);
        assertEquals(Math.round(sword.damage().get(DamageType.PHYSICAL)[1] * 1.5), boostedPhys[1], 1e-9, "武器的物理傷害 × 1.5");
        assertTrue(boostedPhys[1] > basePhys[1]);
        assertEquals(ItemStats.roundTo(sword.aps() * 1.2, 100), after.aps(), 1e-9, "攻擊速度 × 1.2（兩位小數）");
        assertEquals(before.crit(), after.crit(), 1e-9, "沒有暴擊的本地詞綴就不變");

        // 角色的屬性表：武器的基礎傷害變大，但 local.* 本身不進屬性表
        final List<Modifier> modifiers = ItemStats.modifiers(repository, boosted);
        assertTrue(modifiers.stream().noneMatch(modifier -> LocalMods.isLocal(modifier.stat())), "本地詞綴不進屬性表");
        final double weaponMax = modifiers.stream().filter(modifier -> modifier.stat().equals(StatKeys.WEAPON_MAX)
                && modifier.tags().contains(Tags.PHYSICAL)).mapToDouble(Modifier::value).sum();
        assertEquals(boostedPhys[1], weaponMax, 1e-9);

        // 本地暴擊：武器的基礎暴擊率 × 1.3
        final ItemData critical = blank("short_sword");
        critical.affixes().add(new RolledAffix("local_crit", 1, new LinkedHashMap<>(Map.of("value", 30))));
        assertEquals(ItemStats.roundTo(sword.crit() * 1.3, 100), ItemStats.snapshot(repository, sword, critical).crit(), 1e-9);

        // 沒鑑定的不算；不是武器的不算
        boosted.setIdentified(false);
        assertTrue(ItemStats.snapshot(repository, sword, boosted).local().isEmpty(), "未鑑定的詞綴不生效");
        final ItemData armour = blank("plate_body");
        armour.affixes().add(new RolledAffix("local_phys", 1, new LinkedHashMap<>(Map.of("value", 50))));
        assertTrue(LocalMods.of(repository, repository.base("plate_body"), armour).isEmpty());
    }

    @Test
    void localStatsAreValidatedWhenTheDataLoads(@TempDir final Path temp) throws IOException {
        Files.createDirectories(temp.resolve("bases"));
        Files.createDirectories(temp.resolve("affixes"));
        Files.copy(DATA.resolve("bases/weapons.yml"), temp.resolve("bases/weapons.yml"));
        Files.copy(DATA.resolve("bases/armour.yml"), temp.resolve("bases/armour.yml"));
        final String good = """
                ok_local:
                  type: prefix
                  spawn_tags: [weapon]
                  names: [好的]
                  text: "增加 {value}% 武器物理傷害"
                  tiers:
                    - { tier: 1, ilvl: 1, weight: 100, value: [10, 20] }
                  mods:
                    - { stat: local.physical, type: INCREASED, value: "{value}" }
                """;
        Files.writeString(temp.resolve("affixes/test.yml"), good);
        final ItemRepository loaded = new ItemRepository();
        loaded.load(temp);
        assertNotNull(loaded.affix("ok_local"));

        // 出在防具上：本地詞綴不會生效，載入就擋下
        Files.writeString(temp.resolve("affixes/test.yml"), good.replace("spawn_tags: [weapon]", "spawn_tags: [weapon, armour]"));
        final IllegalArgumentException onArmour = assertThrows(IllegalArgumentException.class, () -> new ItemRepository().load(temp));
        assertTrue(onArmour.getMessage().contains("local."), onArmour.getMessage());
        // 不認得的本地屬性
        Files.writeString(temp.resolve("affixes/test.yml"), good.replace("local.physical", "local.fire"));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepository().load(temp));
        // 本地屬性只能是 INCREASED
        Files.writeString(temp.resolve("affixes/test.yml"), good.replace("type: INCREASED", "type: MORE"));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepository().load(temp));
        // 文字用了不支援的除數
        Files.writeString(temp.resolve("affixes/test.yml"), good.replace("增加 {value}%", "增加 {value/3}%"));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepository().load(temp));
    }

    @Test
    void deepenedAffixesKeepTheirOldTierCodesAndAddStrongerOnes() {
        final Affix movement = repository.affix("movement_speed");
        assertEquals(List.of(-1, 0, 1, 2, 3), movement.tiers().stream().map(AffixTier::tier).toList());
        assertEquals(25, movement.tier(1).vars().get("value")[0], "舊的代號 1 範圍不變（舊物品照讀）");
        assertEquals(30, movement.tier(1).vars().get("value")[1]);
        assertEquals(3, movement.rank(1), "舊的 T1 現在排第 3");
        assertEquals("之奔雷", movement.nameFor(1), "名稱跟著排名往後移，舊物品的名字不變");
        for (final String id : List.of("block_chance", "quantity_inc", "rarity_inc", "avoid_ignite", "avoid_freeze", "avoid_shock",
                "avoid_bleed", "avoid_stun", "avoid_electrocute", "avoid_poison", "stun_threshold")) {
            final Affix affix = repository.affix(id);
            assertEquals(5, affix.tiers().size(), id + " 補到 5 階");
            assertEquals(-1, affix.tiers().getFirst().tier(), id);
        }
        assertEquals(2, repository.affix("minion_count").tiers().size(), "召喚物數量多一階（+2，很少見）");
        for (final Affix affix : repository.affixes()) {
            if (affix.spawnTags().contains("ruby") || affix.spawnTags().contains("emerald") || affix.spawnTags().contains("sapphire")
                    || affix.spawnTags().contains("jewel")) {
                assertEquals(3, affix.tiers().size(), affix.id() + "：珠寶詞綴三階");
                assertEquals(0, affix.tiers().getFirst().tier(), affix.id());
                assertEquals(75, affix.tiers().getFirst().ilvl(), affix.id() + "：最強一階要物品等級 75");
            }
        }
    }

    @Test
    void adminForcedAffixesReplaceTheirGroupUpgradeRarityAndRespectSlots() {
        final ItemGenerator generator = new ItemGenerator(repository);
        final Random random = new Random(3);
        final ItemData sword = generator.generate(repository.base("short_sword"), Rarity.NORMAL, 84, random);
        assertTrue(sword.affixes().isEmpty());
        assertEquals(null, generator.forceAffix(sword, "local_phys", null, random));
        assertEquals(Rarity.MAGIC, sword.rarity(), "普通的變魔法");
        assertEquals(1, sword.affixes().getFirst().tier(), "沒寫階級 = 最強的一階");
        assertEquals(null, generator.forceAffix(sword, "local_attack_speed", 3, random));
        assertEquals(Rarity.MAGIC, sword.rarity(), "魔法還放得下一條後綴");
        assertEquals(null, generator.forceAffix(sword, "added_phys", null, random));
        assertEquals(Rarity.RARE, sword.rarity(), "第二條前綴：變稀有");
        assertNotNull(sword.name(), "變稀有時取名字");
        assertEquals(null, generator.forceAffix(sword, "local_phys_accuracy", null, random), "同群組（local_phys）的換掉");
        assertEquals(3, sword.affixes().size());
        assertTrue(sword.affixes().stream().noneMatch(rolled -> rolled.id().equals("local_phys")));
        assertEquals(null, generator.forceAffix(sword, "gain_cold", null, random));
        assertTrue(generator.forceAffix(sword, "phys_inc", null, random).contains("前綴已經滿了（3/3）"));
        assertTrue(generator.forceAffix(sword, "skill_level_spell", null, random).contains("不會出在"), "劍不出法術技能等級");
        assertTrue(generator.forceAffix(sword, "leech_life", 9, random).contains("沒有階級代號 9"));
        assertTrue(generator.forceAffix(sword, "nope", null, random).contains("沒有這條詞綴"));
        final ItemData jewel = generator.generate(repository.base("ruby_jewel"), Rarity.MAGIC, 84, random);
        jewel.affixes().clear();
        assertEquals(null, generator.forceAffix(jewel, "jewel_life_inc", 0, random));
        assertEquals(null, generator.forceAffix(jewel, "jewel_phys_inc", null, random));
        assertTrue(generator.forceAffix(jewel, "jewel_fire_inc", null, random).contains("前綴已經滿了（2/2）"), "珠寶每種最多 2 條");
        assertTrue(ItemStats.modifiers(repository, sword).stream().anyMatch(modifier -> modifier.stat().equals(StatKeys.damageGain(DamageType.COLD))));
    }

    @Test
    void manyGeneratedItemsRenderEveryAffixWithoutLeftoverBraces() {
        final ItemGenerator generator = new ItemGenerator(repository);
        final Random random = new Random(20261004);
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            final ItemBase base = repository.pickDroppable(84, random);
            if (base == null || base.isMap()) {
                continue;
            }
            final ItemData data = generator.generate(base, Rarity.RARE, 84, random);
            for (final RolledAffix rolled : data.affixes()) {
                final Affix affix = repository.affix(rolled.id());
                assertNotNull(affix, rolled.id());
                assertTrue(affix.canSpawnOn(base), rolled.id() + " 出在不該出的 " + base.id());
                final String text = affix.render(rolled.vars());
                assertFalse(text.contains("{") || text.contains("}"), rolled.id() + "：" + text);
                seen.add(rolled.id());
            }
            ItemStats.modifiers(repository, data); // 不丟例外（變數都在）
            ItemStats.snapshot(repository, base, data);
        }
        for (final String id : List.of("leech_life", "life_on_kill", "thorns", "local_phys", "armour_life", "skill_area", "es_recharge",
                "ailment_threshold", "ignite_magnitude", "flask_life_recovery")) {
            assertTrue(seen.contains(id), "3000 件稀有裡應該看得到 " + id);
        }
    }
}
