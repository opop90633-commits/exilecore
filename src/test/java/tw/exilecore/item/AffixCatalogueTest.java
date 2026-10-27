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
    static final Set<String> MAP_STATS = Set.of("map.quantity", "map.rarity", "map.pack_size", "map.magic_packs", "map.rare_packs",
            // 2026-10-26（PoE2 換界石）
            "map.waystones", "map.rare_mod_chance", "map.curse_zone.enfeeble", "map.curse_zone.temporal_chains",
            "map.curse_zone.elemental_weakness", "map.ground.fire", "map.ground.chill", "map.ground.shock", "map.ground.patches",
            "map.ground.radius");

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
                stats.add(StatKeys.resistMax(type)); // 2026-10：某一種元素的抗性上限（PoE2 珠寶、詞綴）
            }
            stats.add(StatKeys.damageExtra(type)); // 2026-10：「獲得 x% 傷害為額外…傷害」
        }
        for (final String charge : ChargeConfig.parse(YamlNode.load(DATA.resolve(ChargeConfig.FILE))).keySet()) {
            stats.add(StatKeys.chargeChance(charge));
            stats.add(StatKeys.chargeOnHit(charge));
            stats.add(StatKeys.chargeMax(charge));
            // 2026-10：揭露詞綴的戰星屬性——獲得某種戰星時多一顆（charge.extra.代號）、某個事件時獲得（charge.event.事件.代號）
            stats.add(tw.exilecore.charge.ChargeService.EXTRA + "." + charge);
            for (final String event : tw.exilecore.charge.ChargeText.EVENTS.keySet()) {
                stats.add("charge.event." + event + "." + charge);
            }
        }
        // 跟戰星的獲得有關、不分種類的（charge.extra、charge.periodic、charge.event_cooldown.事件）
        stats.add(tw.exilecore.charge.ChargeService.EXTRA);
        stats.add("charge.periodic");
        for (final String event : tw.exilecore.charge.ChargeText.EVENTS.keySet()) {
            stats.add("charge.event_cooldown." + event);
        }
        stats.addAll(LocalMods.KNOWN);
        // 2026-10 新的引擎屬性鍵（引擎照字串組出來讀的）：自己身上某種異常的持續時間（AilmentService）、傷害轉換、
        // 臨在的抗性降低（Presence）、護符欄位（CharmService）、揭露詞綴的幅度（ItemStats）
        for (final String ailment : List.of("ignite", "chill", "freeze", "shock", "bleed", "poison")) {
            stats.add(StatKeys.SELF_AILMENT_DURATION + "." + ailment);
        }
        for (final DamageType type : DamageType.values()) {
            stats.add(StatKeys.damageConvert(type));
            if (type != DamageType.PHYSICAL) {
                stats.add(StatKeys.PRESENCE_RESIST + type.tag());
            }
        }
        stats.add(tw.exilecore.flask.CharmService.STAT_SLOTS);
        stats.add(ItemStats.UNVEILED_MAGNITUDE);
        // 2026-10-27 照 PoE2：藥劑生命／魔力回復速率（Flask Life/Mana Recovery rate；FlaskService.flaskRate 讀）
        stats.add(tw.exilecore.stats.KeystoneKeys.FLASK_LIFE_RATE);
        stats.add(tw.exilecore.stats.TreeEffectKeys.FLASK_MANA_RATE);
        // 2026-10：對身上有某種異常的敵人（damage.vs_chilled…，CombatListener.ailmentWord）
        for (final String ailment : List.of("chilled", "frozen", "ignited", "shocked", "poisoned", "bleeding")) {
            stats.add(StatKeys.DAMAGE_VS_AILMENT + ailment);
        }
        tags = constants(Tags.class);
        tags.add("curse"); // 2026-10：詛咒技能的標籤（SkillDefinition 給範圍印記加的，Tags 沒有常數）
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
        // 停用的地圖詞綴（2026-10：換成 PoE2 的換界石詞綴池）spawn_tags 是 [retired]，看代號
        return affix.spawnTags().contains("map")
                || affix.spawnTags().contains(ItemRepository.RETIRED_TAG) && affix.id().startsWith("map_");
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
                if (stat.startsWith(StatKeys.RADIUS_PREFIX)) {
                    stat = stat.substring(StatKeys.RADIUS_PREFIX.length()); // 2026-10：半徑珠寶「範圍內的小天賦也各給」
                } else if (stat.startsWith(tw.exilecore.passive.JewelSockets.RADIUS_NOTABLE_PREFIX)) {
                    // 2026-10-26：「範圍內的核心天賦也各給」（照 PoE2 分小天賦／核心天賦）
                    stat = stat.substring(tw.exilecore.passive.JewelSockets.RADIUS_NOTABLE_PREFIX.length());
                }
                if (tw.exilecore.stats.Recently.hasCondition(stat)) {
                    // 2026-10：「最近…」的條件詞綴（damage@projhit 這種）：條件要是引擎認得的
                    assertNotNull(tw.exilecore.stats.Recently.condition(stat), where + "：不認得的條件 " + stat);
                    stat = tw.exilecore.stats.Recently.stat(stat);
                }
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
                        || stat.equals(StatKeys.SKILL_LEVEL) || stat.startsWith("thorns.") && !stat.equals(StatKeys.THORNS_DAMAGE)
                        // 荊棘傷害、偷取的量、最大偷取速度是 INCREASED（StatKeys.THORNS_DAMAGE／LEECH_EFFECT／LEECH_RATE），其他是百分比點數
                        || stat.startsWith("leech.") && !stat.equals(StatKeys.LEECH_EFFECT) && !stat.equals(StatKeys.LEECH_RATE)
                        && !stat.equals(StatKeys.LEECH_MANA_EFFECT)) {
                    assertEquals(ModType.FLAT, mod.type(), where + "：" + stat + " 是百分比點數／固定值，要用 FLAT");
                }
                if (mod.variable() != null && !fixedValue(affix, mod.variable())) {
                    // 每一階都是固定值的（腐化「這把武器沒有物理傷害」= -100%）文字可以不寫數字
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
            final boolean retired = affix.spawnTags().contains(ItemRepository.RETIRED_TAG);
            for (final AffixTier tier : affix.tiers()) {
                // 停用的詞綴（spawn_tags: [retired]）不會再擲出，權重可以是 0
                assertTrue(retired ? tier.weight() >= 0 : tier.weight() > 0, where + " 的權重");
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
        // 2026-10 PoE2 改版：舊基底退役（劍拿掉、法杖 → wand_xx…），詞綴照 PoE2 的詞綴池與部位
        final ItemBase mace = repository.base("mace1_26");
        final ItemBase wand = repository.base("wand_14");
        final ItemBase staff = repository.base("staff_17");
        final ItemBase bow = repository.base("bow_26");
        final ItemBase plateBody = repository.base("plate_body");
        final ItemBase silkBody = repository.base("silk_body");
        final ItemBase ring = repository.base("jring_16");
        final ItemBase amulet = repository.base("jamulet_11");
        final ItemBase belt = repository.base("jbelt_07");
        final ItemBase towerShield = repository.base("tshield_24");
        final ItemBase spiritShield = repository.base("focus_23");
        final ItemBase leggings = repository.bases().stream().filter(base -> base.hasTag("leggings")).findFirst().orElseThrow();

        // 武器本身：攻擊武器才有，施法武器、防具不會有
        for (final String id : List.of("phys_inc", "local_phys_accuracy", "added_phys_local", "local_crit_base", "local_crit_bonus")) {
            assertTrue(repository.affix(id).canSpawnOn(mace), id);
            assertTrue(repository.affix(id).canSpawnOn(bow), id);
            assertFalse(repository.affix(id).canSpawnOn(wand), id + " 不出在法杖");
            assertFalse(repository.affix(id).canSpawnOn(plateBody), id + " 不出在防具");
        }
        assertTrue(repository.affix("local_attack_speed").canSpawnOn(mace));
        assertTrue(repository.affix("local_attack_speed_bow").canSpawnOn(bow));
        assertEquals(repository.affix("local_attack_speed").group(), repository.affix("local_attack_speed_bow").group());
        // 本地與全域的攻速、暴擊率出在不同部位：一件裝備只會有一種
        for (final ItemBase base : repository.bases()) {
            assertFalse(repository.affix("attack_speed").canSpawnOn(base) && repository.affix("local_attack_speed").canSpawnOn(base), base.id());
            assertFalse(repository.affix("crit_chance").canSpawnOn(base) && repository.affix("local_crit_base").canSpawnOn(base), base.id());
        }
        // 混合防禦與荊棘：防具前綴
        assertTrue(repository.affix("armour_life").canSpawnOn(plateBody));
        assertFalse(repository.affix("armour_life").canSpawnOn(silkBody));
        assertTrue(repository.affix("es_life").canSpawnOn(silkBody));
        assertTrue(repository.affix("thorns").canSpawnOn(towerShield));
        assertFalse(repository.affix("thorns").canSpawnOn(spiritShield), "荊棘是力量系的");
        // 技能等級：法術的出在施法武器與項鍊，不出在近戰武器
        assertTrue(repository.affix("skill_level_spell").canSpawnOn(wand));
        assertTrue(repository.affix("skill_level_spell_staff").canSpawnOn(staff));
        assertFalse(repository.affix("skill_level_spell").canSpawnOn(mace));
        assertFalse(repository.affix("skill_level_spell_staff").canSpawnOn(mace));
        assertTrue(repository.affix("skill_level_spell_amulet_amulet").canSpawnOn(amulet));
        assertTrue(repository.affix("skill_level_melee_amulet_1h").canSpawnOn(mace));
        assertTrue(repository.affix("skill_level_projectile").canSpawnOn(bow));
        assertEquals(4, repository.affix("skill_level_spell").tiers().getFirst().vars().get("value")[1], "法杖最多 +4");
        assertEquals(6, repository.affix("skill_level_spell_staff").tiers().getFirst().vars().get("value")[1], "長杖最多 +6");
        assertEquals(3, repository.affix("skill_level_spell_amulet_amulet").tiers().getFirst().vars().get("value")[1], "項鍊最多 +3");
        // 腰帶的藥劑；偷取出在戒指與褲子
        assertTrue(repository.affix("flask_life_recovery").canSpawnOn(belt));
        assertFalse(repository.affix("flask_life_recovery").canSpawnOn(ring));
        assertTrue(repository.affix("leech_life_ring").canSpawnOn(ring));
        assertTrue(repository.affix("leech_life").canSpawnOn(leggings));
        assertFalse(repository.affix("leech_life").canSpawnOn(wand), "偷取看攻擊的物理傷害：施法武器不出");
        assertFalse(repository.affix("leech_life_local").canSpawnOn(wand), "偷取看攻擊的物理傷害：施法武器不出");
        // 「額外火焰傷害」：PoE2 只出在法杖、長杖
        assertTrue(repository.affix("extra_fire").canSpawnOn(wand));
        assertTrue(repository.affix("extra_fire_staff").canSpawnOn(staff));
        assertFalse(repository.affix("extra_fire").canSpawnOn(amulet));
        // 舊的（球上限、物理附加為元素）停用：哪裡都不出
        for (final String retired : List.of("frenzy_charge_max", "gain_fire", "local_phys", "jewel_bleed_mag")) {
            for (final ItemBase base : repository.bases()) {
                assertFalse(repository.affix(retired).canSpawnOn(base), retired + " 停用了，卻出在 " + base.id());
            }
        }
        // 珠寶：照 PoE2 的顏色（紅＝力量、綠＝敏捷、藍＝智慧）
        assertTrue(repository.affix("j2_base_chance_to_bleed").canSpawnOn(repository.base("ruby_jewel")));
        assertTrue(repository.affix("j2_base_chance_to_bleed").canSpawnOn(repository.base("diamond_jewel")), "白星晶每一種都出");
        assertFalse(repository.affix("j2_base_chance_to_bleed").canSpawnOn(repository.base("sapphire_jewel")));
        assertFalse(repository.affix("j2_base_chance_to_bleed").canSpawnOn(mace));
        // 地圖的詞綴（2026-10 照 PoE2 的換界石詞綴池；自己設計的停用）
        assertTrue(repository.affix("map_monster_armour_break").canSpawnOn(repository.base("map_wreck_bay")));
        assertFalse(repository.affix("map_monster_armour_break").canSpawnOn(mace));
        assertFalse(repository.affix("map_monster_skill_speed").canSpawnOn(repository.base("map_wreck_bay")), "PoE2 沒有：停用");
    }

    @Test
    void spellCritAffixesOnlyHelpSpellsAndOnlyRollOnCasterWeapons() {
        // 2026-10 PoE2 改版：法杖、長杖、法器各一條（同一個群組），暴擊傷害是「增加 x% 暴擊傷害加成」（crit.bonus）
        final ItemBase wand = repository.base("wand_14");
        final ItemBase staff = repository.base("staff_17");
        final ItemBase focus = repository.base("focus_23");
        for (final String id : List.of("spell_crit_chance", "spell_crit_bonus")) {
            final Affix oneHand = repository.affix(id + "_wand");
            final Affix twoHand = repository.affix(id + "_staff");
            final Affix offHand = repository.affix(id);
            assertTrue(oneHand.canSpawnOn(wand), oneHand.id());
            assertFalse(oneHand.canSpawnOn(staff), oneHand.id() + "：長杖用雙手的版本");
            assertTrue(twoHand.canSpawnOn(staff), twoHand.id());
            assertFalse(twoHand.canSpawnOn(wand), twoHand.id());
            assertTrue(offHand.canSpawnOn(focus), offHand.id() + "：法器");
            for (final String other : List.of("mace1_26", "bow_26", "qstaff_28", "sceptre_16", "jring_16", "jamulet_11", "silk_body")) {
                for (final Affix affix : List.of(oneHand, twoHand, offHand)) {
                    assertFalse(affix.canSpawnOn(repository.base(other)), affix.id() + " 不出在 " + other);
                }
            }
            assertEquals(oneHand.group(), twoHand.group(), "一手、雙手同一個群組");
            assertEquals(oneHand.group(), offHand.group());
            assertTrue(twoHand.tiers().getFirst().vars().get("value")[1] > oneHand.tiers().getFirst().vars().get("value")[1],
                    id + "：雙手的數值比較高");
        }
        assertTrue(repository.affix("j2_spell_critical_strike_chance").canSpawnOn(repository.base("sapphire_jewel")));
        assertFalse(repository.affix("j2_spell_critical_strike_chance").canSpawnOn(repository.base("emerald_jewel")));
        // 法術專用的比通用的高
        assertTrue(repository.affix("spell_crit_chance_wand").tiers().getFirst().vars().get("value")[0]
                > repository.affix("crit_chance").tiers().getFirst().vars().get("value")[1]);
        assertTrue(repository.affix("spell_crit_bonus_staff").tiers().getFirst().vars().get("value")[0]
                > repository.affix("crit_bonus").tiers().getFirst().vars().get("value")[1]);
        // 只對法術有效：攻擊拿不到
        final Map<String, Integer> rolled = Map.of("value", 60);
        final StatSheet sheet = new StatSheet()
                .add(repository.affix("spell_crit_chance_wand").mods().getFirst().toModifier(rolled, "法杖"))
                .add(repository.affix("spell_crit_bonus_wand").mods().getFirst().toModifier(Map.of("value", 40), "法杖"));
        assertEquals(0.6, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.SPELL, Tags.FIRE)), 1e-9);
        assertEquals(0, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.ATTACK, Tags.MELEE)), 1e-9, "攻擊不吃法術暴擊率");
        assertEquals(0.4, sheet.increased(StatKeys.CRIT_BONUS, Set.of(Tags.SPELL)), 1e-9);
        assertEquals(0, sheet.increased(StatKeys.CRIT_BONUS, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals("增加 60% 法術暴擊率", repository.affix("spell_crit_chance_wand").render(rolled));
        // 舊的（一手、雙手、項鍊的法術暴擊）停用
        for (final String retired : List.of("spell_crit_chance_2h", "spell_crit_multi", "spell_crit_multi_2h")) {
            assertTrue(repository.affix(retired).spawnTags().contains(ItemRepository.RETIRED_TAG), retired);
        }
    }

    @Test
    void amuletsAndJewelsSplitCritIntoAttackAndSpell() {
        // 2026-10 PoE2 改版：項鍊只出通用的暴擊（crit_chance_amulet、crit_bonus_amulet）；攻擊專用的出在箭袋、法術專用的出在施法武器；
        // 舊的項鍊、珠寶攻擊／法術暴擊停用（舊物品照常生效）
        final ItemBase amulet = repository.base("jamulet_11");
        for (final String id : List.of("amulet_spell_crit_chance", "amulet_spell_crit_multi", "amulet_attack_crit_chance",
                "amulet_attack_crit_multi", "jewel_attack_crit_chance", "jewel_attack_crit_multi", "jewel_spell_crit_chance",
                "jewel_spell_crit_multi")) {
            final Affix affix = repository.affix(id);
            assertNotNull(affix, id);
            for (final ItemBase base : repository.bases()) {
                assertFalse(affix.canSpawnOn(base), id + " 停用了，卻出在 " + base.id());
            }
        }
        assertTrue(repository.affix("crit_chance_amulet").canSpawnOn(amulet));
        assertTrue(repository.affix("crit_bonus_amulet").canSpawnOn(amulet));
        assertFalse(repository.affix("crit_chance_amulet").canSpawnOn(repository.base("jring_16")));
        final ItemBase quiver = repository.bases().stream().filter(ItemBase::isQuiver).findFirst().orElseThrow();
        for (final String id : List.of("attack_crit_chance", "attack_crit_bonus")) {
            final Affix affix = repository.affix(id);
            assertTrue(affix.canSpawnOn(quiver), id + " 出在箭袋");
            for (final String other : List.of("jamulet_11", "jring_16", "wand_14", "staff_17", "silk_body", "emerald_jewel", "sapphire_jewel")) {
                assertFalse(affix.canSpawnOn(repository.base(other)), id + " 不出在 " + other);
            }
            assertEquals(AffixType.SUFFIX, affix.type(), id);
            assertTrue(affix.mods().getFirst().tags().contains(Tags.ATTACK), id);
        }
        // 跟通用的不同群組（同一件可以都有）
        assertFalse(repository.affix("attack_crit_chance").group().equals(repository.affix("crit_chance_amulet").group()));
        // 攻擊的只給攻擊、法術的只給法術
        final StatSheet sheet = new StatSheet()
                .add(repository.affix("attack_crit_chance").mods().getFirst().toModifier(Map.of("value", 50), "箭袋"))
                .add(repository.affix("attack_crit_bonus").mods().getFirst().toModifier(Map.of("value", 40), "箭袋"))
                .add(repository.affix("spell_crit_chance_wand").mods().getFirst().toModifier(Map.of("value", 30), "法杖"))
                .add(repository.affix("spell_crit_bonus_wand").mods().getFirst().toModifier(Map.of("value", 20), "法杖"));
        assertEquals(0.5, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.ATTACK, Tags.MELEE)), 1e-9);
        assertEquals(0.3, sheet.increased(StatKeys.CRIT_CHANCE, Set.of(Tags.SPELL, Tags.FIRE)), 1e-9);
        assertEquals(0.4, sheet.increased(StatKeys.CRIT_BONUS, Set.of(Tags.ATTACK, Tags.PROJECTILE)), 1e-9);
        assertEquals(0.2, sheet.increased(StatKeys.CRIT_BONUS, Set.of(Tags.SPELL)), 1e-9);
        assertEquals("增加 50% 攻擊暴擊率", repository.affix("attack_crit_chance").render(Map.of("value", 50)));
        assertEquals("增加 40% 攻擊暴擊傷害加成", repository.affix("attack_crit_bonus").render(Map.of("value", 40)));
        // 珠寶：翠星晶有攻擊的、蒼星晶有法術的
        for (final String id : List.of("j2_attack_critical_strike_chance", "j2_attack_critical_strike_multiplier")) {
            assertTrue(repository.affix(id).canSpawnOn(repository.base("emerald_jewel")), id);
            assertFalse(repository.affix(id).canSpawnOn(repository.base("sapphire_jewel")), id);
            assertFalse(repository.affix(id).canSpawnOn(amulet), id);
            assertTrue(repository.affix(id).mods().getFirst().tags().contains(Tags.ATTACK), id);
        }
        for (final String id : List.of("j2_spell_critical_strike_chance", "j2_spell_crit_multiplier")) {
            assertTrue(repository.affix(id).canSpawnOn(repository.base("sapphire_jewel")), id);
            assertFalse(repository.affix(id).canSpawnOn(repository.base("emerald_jewel")), id);
            assertTrue(repository.affix(id).mods().getFirst().tags().contains(Tags.SPELL), id);
        }
        // 催化劑（2026-10）：暴擊歸暴擊催化劑，攻擊、法術催化劑只加傷害
        final Modifier spellCrit = repository.affix("spell_crit_chance_wand").mods().getFirst().toModifier(Map.of("value", 30), "法杖");
        final Modifier attackCrit = repository.affix("attack_crit_chance").mods().getFirst().toModifier(Map.of("value", 30), "箭袋");
        final Modifier anyCrit = repository.affix("crit_chance_amulet").mods().getFirst().toModifier(Map.of("value", 30), "項鍊");
        for (final Modifier crit : List.of(spellCrit, attackCrit, anyCrit)) {
            assertTrue(Catalyst.CRIT.matches(crit));
            assertFalse(Catalyst.ATTACK.matches(crit), "攻擊催化劑不加暴擊");
            assertFalse(Catalyst.CASTER.matches(crit), "法術催化劑不加暴擊");
        }
        // 2026-10-16：飾品的暴傷詞綴是「增加 x% 暴擊傷害加成」（crit.bonus），也要吃暴擊催化劑
        final Modifier critBonus = repository.affix("crit_bonus_amulet").mods().getFirst().toModifier(Map.of("value", 30), "項鍊");
        assertTrue(Catalyst.CRIT.matches(critBonus), "暴擊催化劑要加暴擊傷害加成");
        assertFalse(Catalyst.ELEMENTAL.matches(critBonus));
    }

    @Test
    void fractionalRollsShowAsDecimalsAndFeedTheEngineTheSameNumber() {
        // 2026-10 PoE2 改版：偷取照 PoE2 寫成萬分比（{value/100}）
        final Affix leech = repository.affix("leech_life");
        final Map<String, Integer> rolled = Map.of("value", 950);
        assertEquals("攻擊造成的物理傷害有 9.5% 偷取為生命", leech.render(rolled));
        final Modifier modifier = leech.mods().getFirst().toModifier(rolled, "測試");
        assertEquals(StatKeys.LEECH_LIFE, modifier.stat());
        assertEquals(9.5, modifier.value(), 1e-9);
        assertEquals(Set.of(Tags.ATTACK), modifier.tags());
        final String ranged = Affix.substituteWithRanges(leech.text(), rolled, leech.ranges(1));
        assertTrue(ranged.contains("9.5") && ranged.contains("(9–9.9)"), ranged);
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
        // 2026-10 PoE2 改版：移動速度換成 PoE2 的六階（代號 1–6，T1 35% 要物品等級 82）；舊物品身上的代號找不到時照存的數值顯示
        final Affix movement = repository.affix("movement_speed");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), movement.tiers().stream().map(AffixTier::tier).toList());
        assertEquals(35, movement.tier(1).vars().get("value")[0]);
        assertEquals(82, movement.tier(1).ilvl());
        assertTrue(movement.ranges(-1).isEmpty(), "舊的代號 -1 已經沒有：不接範圍");
        assertEquals(1, movement.rank(-1), "找不到的代號至少排第 1");
        // 停用的舊詞綴照舊保留補上去的階級（舊物品照讀）
        // rarity_inc 換成 PoE2 的三階（不再是補階的那條）
        for (final String id : List.of("block_chance", "quantity_inc", "avoid_ignite", "avoid_freeze", "avoid_shock",
                "avoid_bleed", "avoid_stun", "avoid_electrocute", "avoid_poison", "stun_threshold")) {
            final Affix affix = repository.affix(id);
            assertEquals(5, affix.tiers().size(), id + " 補到 5 階");
            assertEquals(-1, affix.tiers().getFirst().tier(), id);
        }
        assertEquals(2, repository.affix("minion_count").tiers().size(), "召喚物數量多一階（+2，很少見）");
        for (final Affix affix : repository.affixes()) {
            if (affix.id().startsWith("unveiled_")) {
                continue; // 揭露詞綴（affixes/unveiled.yml）照 PoE2 的褻瀆詞綴只有兩階
            }
            if (affix.spawnTags().contains("corruption")) {
                continue; // 2026-10-23：珠寶的腐化附魔（affixes/corrupted.yml）只有一階、只靠蝕變石加上
            }
            if (affix.spawnTags().contains("ruby") || affix.spawnTags().contains("emerald") || affix.spawnTags().contains("sapphire")
                    || affix.spawnTags().contains("jewel")) {
                if (affix.spawnTags().contains(ItemRepository.RETIRED_TAG)) {
                    assertEquals(3, affix.tiers().size(), affix.id() + "：退役的舊珠寶詞綴還是三階（舊物品照讀）");
                    continue;
                }
                // 2026-10-27 照 PoE2：珠寶詞綴只有一個範圍（等級 1，代號 3）；這次之前三階（代號 0／1／2，最強要物品等級 75）
                assertEquals(1, affix.tiers().size(), affix.id() + "：珠寶詞綴一個範圍");
                assertEquals(3, affix.tiers().getFirst().tier(), affix.id());
                assertEquals(1, affix.tiers().getFirst().ilvl(), affix.id() + "：等級 1");
            }
        }
    }

    @Test
    void adminForcedAffixesReplaceTheirGroupUpgradeRarityAndRespectSlots() {
        // 2026-10 PoE2 改版：劍退役，用單手錘；停用的詞綴（retired）不能再加
        final ItemGenerator generator = new ItemGenerator(repository);
        final Random random = new Random(3);
        final ItemData mace = generator.generate(repository.base("mace1_26"), Rarity.NORMAL, 84, random);
        assertTrue(mace.affixes().isEmpty());
        assertEquals(null, generator.forceAffix(mace, "phys_inc", null, random));
        assertEquals(Rarity.MAGIC, mace.rarity(), "普通的變魔法");
        assertEquals(repository.affix("phys_inc").tiers().getFirst().tier(), mace.affixes().getFirst().tier(), "沒寫階級 = 最強的一階");
        assertEquals(null, generator.forceAffix(mace, "local_attack_speed", 3, random));
        assertEquals(Rarity.MAGIC, mace.rarity(), "魔法還放得下一條後綴");
        assertEquals(null, generator.forceAffix(mace, "added_phys_local", null, random));
        assertEquals(Rarity.RARE, mace.rarity(), "第二條前綴：變稀有");
        assertNotNull(mace.name(), "變稀有時取名字");
        assertEquals(null, generator.forceAffix(mace, "phys_inc", 2, random), "同群組（包括自己）的換掉");
        assertEquals(3, mace.affixes().size());
        assertEquals(2, mace.affixes().stream().filter(rolled -> rolled.id().equals("phys_inc")).findFirst().orElseThrow().tier());
        assertEquals(null, generator.forceAffix(mace, "added_cold_local", null, random));
        assertTrue(generator.forceAffix(mace, "added_fire_local", null, random).contains("前綴已經滿了（3/3）"));
        assertTrue(generator.forceAffix(mace, "skill_level_spell", null, random).contains("不會出在"), "錘不出法術技能等級");
        assertTrue(generator.forceAffix(mace, "local_phys", null, random).contains("retired"), "停用的詞綴不能加");
        assertTrue(generator.forceAffix(mace, "leech_life_local", 9, random).contains("沒有階級代號 9"));
        assertTrue(generator.forceAffix(mace, "nope", null, random).contains("沒有這條詞綴"));
        final ItemData jewel = generator.generate(repository.base("ruby_jewel"), Rarity.MAGIC, 84, random);
        jewel.affixes().clear();
        assertEquals(null, generator.forceAffix(jewel, "j2_attack_damage", 3, random)); // 2026-10-27：珠寶詞綴只剩代號 3（這次之前 0）
        assertEquals(null, generator.forceAffix(jewel, "j2_fire_damage_percentage", null, random));
        assertTrue(generator.forceAffix(jewel, "j2_elemental_damage_percent", null, random).contains("前綴已經滿了（2/2）"), "珠寶每種最多 2 條");
        assertTrue(ItemStats.modifiers(repository, mace).stream().anyMatch(modifier -> modifier.tags().contains(Tags.COLD)), "附加冰冷傷害");
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
        // 2026-10 PoE2 改版：停用的換成 PoE2 的（life_on_kill → life_per_kill、local_phys → phys_inc…）
        for (final String id : List.of("leech_life", "life_per_kill", "thorns", "phys_inc", "armour_life", "es_recharge",
                "stun_threshold_flat", "ignite_chance", "flask_life_recovery")) {
            assertTrue(seen.contains(id), "3000 件稀有裡應該看得到 " + id);
        }
    }

    /** 這個變數在每一階都只有一個值（min = max）。 */
    private static boolean fixedValue(final Affix affix, final String variable) {
        for (final AffixTier tier : affix.tiers()) {
            final int[] range = tier.vars().get(variable);
            if (range == null || range[0] != range[1]) {
                return false;
            }
        }
        return !affix.tiers().isEmpty();
    }
}
