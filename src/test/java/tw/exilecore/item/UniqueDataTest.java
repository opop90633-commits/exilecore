package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SpecialEffectLines;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/uniques.yml：每件傳奇都讀得到、屬性與標籤遊戲真的會讀、掉落來源存在，特殊效果真的在物品上。 */
class UniqueDataTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    /** StatKeys 裡所有的屬性鍵（加上 damage.gain.類型）。 */
    static Set<String> stats;
    /** Tags 裡所有的標籤。 */
    static Set<String> tags;
    /** 地圖事件的來源（map_events.yml）。 */
    static final Set<String> EVENT_SOURCES = Set.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium", "abyss"); // 2026-10-18：＋深淵裂縫
    /** 其他專屬的來源（2026-10）：劫盜的大金庫、開服活動的世界 Boss（world_events.yml 直接列傳奇代號）。 */
    static final Set<String> OTHER_SOURCES = Set.of("heist", "world_boss", "abyss_whale");
    /** 地圖代號（具名 Boss 的來源）。 */
    static Set<String> maps;
    /** 引擎用空的情境查的屬性（寫標籤也不會生效）。 */
    static final Set<String> UNTAGGED = Set.of(StatKeys.LIFE_ON_KILL, StatKeys.MANA_ON_KILL, StatKeys.ES_ON_KILL,
            StatKeys.ONSLAUGHT_ON_KILL, StatKeys.KILL_EXPLODE, StatKeys.DAMAGE_FROM_MANA, StatKeys.LIFE_RECOUP,
            StatKeys.RESIST_MAX, StatKeys.AVOID_FREEZE);
    static final Set<String> WEAPON_TAGS = Set.of(Tags.SWORD, Tags.AXE, Tags.MACE, Tags.DAGGER, Tags.CLAW, Tags.WAND, Tags.STAFF,
            Tags.BOW, Tags.CROSSBOW, Tags.QUARTERSTAFF, Tags.SPEAR, Tags.ONE_HAND, Tags.TWO_HAND);

    /** 原作就不到三條詞綴的傳奇（2026-10-26 天樞墜、巨像之心；2026-10-27 映鏡指環、孀雨粗弓、濁源指環）：最少幾條。 */
    static final Map<String, Integer> FEW_LINES = Map.of("celestial_pivot", 2, "colossus_heart", 2, "mirror_touch_ring", 1,
            "widowsong_bow", 1, "tainted_origin_ring", 2);

    /** 2026-10-28 照 PoE2：禁止掉落（poe2db「禁止掉落」：Infernoclasp、Thunderstep、Forbidden Gaze）。 */
    static final Set<String> DISABLED = Set.of("flameclasp_belt", "thunderdash_boots", "forbidden_tiara");

    @BeforeAll
    static void load() throws IOException, IllegalAccessException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        stats = constants(StatKeys.class);
        stats.remove(StatKeys.DAMAGE_GAIN);
        for (final DamageType type : DamageType.values()) {
            if (type != DamageType.PHYSICAL) {
                stats.add(StatKeys.damageGain(type));
                stats.add(StatKeys.resistMax(type)); // 2026-10：某一種元素的抗性上限
            }
            stats.add(StatKeys.damageExtra(type)); // 2026-10：「獲得 x% 傷害為額外…傷害」
        }
        stats.addAll(LocalMods.KNOWN); // 2026-10：傳奇武器也可以有本地詞綴（裂穹的 local.crit_base）
        // 2026-10：戰星（球）的上限、機率（charge.max.球…，照 charges.yml）
        for (final String charge : tw.exilecore.charge.ChargeConfig.parse(YamlNode.load(DATA.resolve(tw.exilecore.charge.ChargeConfig.FILE))).keySet()) {
            stats.add(StatKeys.chargeChance(charge));
            stats.add(StatKeys.chargeOnHit(charge));
            stats.add(StatKeys.chargeMax(charge));
            stats.add(tw.exilecore.charge.ChargeService.LOSE_AT_MAX + charge); // 2026-10-24：赤芒導衣
            stats.add(tw.exilecore.flask.CharmService.GRANT + charge);         // 2026-10-24：岳息、雷吼護符
        }
        // 2026-10 新的引擎屬性鍵（引擎照字串組出來讀的）：自己身上某種異常的持續時間（AilmentService）、傷害轉換、
        // 臨在的抗性降低（Presence）、護符欄位（CharmService）、揭露詞綴的幅度（ItemStats）
        for (final String ailment : List.of("ignite", "chill", "freeze", "shock", "bleed", "poison")) {
            stats.add(StatKeys.SELF_AILMENT_DURATION + "." + ailment);
            stats.add("flag.immune." + ailment); // 2026-10-28：「不會被…」的傳奇是免疫（AilmentService.avoided 讀 flag.immune.＋異常）
        }
        for (final DamageType type : DamageType.values()) {
            stats.add(StatKeys.damageConvert(type));
            if (type != DamageType.PHYSICAL) {
                stats.add(StatKeys.PRESENCE_RESIST + type.tag());
            }
        }
        stats.add(tw.exilecore.flask.CharmService.STAT_SLOTS);
        stats.add(ItemStats.UNVEILED_MAGNITUDE);
        stats.addAll(tw.exilecore.passive.TimelessJewels.STATS); // 2026-10-26：永恆珠寶的種子、征服者、族（TimelessJewels 讀）
        // 2026-10：對身上有某種異常的敵人（damage.vs_chilled…，CombatListener.ailmentWord）
        for (final String ailment : List.of("chilled", "frozen", "ignited", "shocked", "poisoned", "bleeding")) {
            stats.add(StatKeys.DAMAGE_VS_AILMENT + ailment);
        }
        // 2026-10-26 照 PoE2 0.5 傳奇重做：傳奇的新機制屬性鍵（UniqueStats）與賦予的技能（grant_skill.技能）
        stats.addAll(tw.exilecore.item.unique.UniqueStats.all());
        // 2026-10-27 照 PoE2：藥劑生命／魔力回復速率（Flask Life/Mana Recovery rate；FlaskService.flaskRate 讀）
        stats.add(tw.exilecore.stats.KeystoneKeys.FLASK_LIFE_RATE);
        stats.add(tw.exilecore.stats.TreeEffectKeys.FLASK_MANA_RATE);
        for (final String skill : List.of("decompose", "withering_presence", "lightning_bolt", "spark", "blink",
                "rite_of_restoration")) { // 2026-10-27：＋雷頌墜、縛地長杖、絲沙背心；2026-10-28：＋綺夢
            stats.add(tw.exilecore.item.unique.UniqueStats.GRANT_SKILL + skill);
        }
        tags = constants(Tags.class);
        // 2026-10-28：敵人狀態的條件標籤（vs_immobilised、vs_cursed…；TargetConditions 照目標的狀態加進擊中的情境，帶這些標籤的修飾器才算）
        tags.addAll(tw.exilecore.stats.TreeConditions.ENEMY);
        // 2026-10-27（王衛全鎧）：只對某一種球（charge.duration 寫 endurance 標籤，ChargeType.durationTicksFor）
        tags.addAll(tw.exilecore.charge.ChargeConfig.parse(YamlNode.load(DATA.resolve(tw.exilecore.charge.ChargeConfig.FILE))).keySet());
        tags.add(tw.exilecore.item.unique.UniqueStats.SKILL_TAG_PREFIX + "temporal_curse"); // 2026-10-26：只對某個技能（SkillBuild 的 skill:技能 標籤）
        tags.add("curse"); // 2026-10：詛咒技能的標籤（SkillDefinition 給範圍印記加的，Tags 沒有常數）
        tags.addAll(tw.exilecore.stats.TreeConditions.ENEMY); // 2026-10-28（元素之章）：對敵人狀態的條件標籤（擊中時 TargetConditions 加進情境）
        final YamlNode mapsYml = YamlNode.load(DATA.resolve("maps.yml"));
        maps = new HashSet<>(mapsYml.node("maps").keys());
        maps.addAll(mapsYml.node("finals").keys()); // 其他終局 Boss 各自的傳奇寫終局的代號
        maps.add(mapsYml.node("final").string("id", "sky_crucible")); // 極境的傳奇寫 uber_終局代號（含主要的終局）
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

    @Test
    void everyUniqueLoadsWithValidStatsTagsAndSources() {
        assertTrue(repository.warnings().isEmpty(), String.join("\n", repository.warnings()));
        // 2026-10 PoE2 改版：舊的傳奇退役（retired，不算在 uniques() 裡），換成照 PoE2 的一批，加上劫盜、世界 Boss 的專屬
        // 2026-10-26 照 PoE2 0.5：＋30 件原作有、我們以前沒有的傳奇（淵握墜退役換成淵握戒，件數不變）
        assertEquals(127 + 21 + 5 + 2 + 6 + 2 + 30 + 91 + 1 + 9 + 21 - 3 + 1, repository.uniques().size(), // 2026-10-29：＋百目冠（Crown of Eyes） // 2026-10-28 照站長刪掉焰扣束帶、奔雷皮靴、禁視鏈冠（－3）；2026-10-27：＋91 件照 PoE2 0.5 的第二批（84 件一般、7 件追逐級）；2026-10-28：＋黯炎；2026-10-28：＋9 件照 PoE2（綺夢、阿茲里的輕視、謎容、神聖烈焰、元素之章、貝雷克的兩枚、薛朗的背包、吉爾菲的聖殿）
                // 2026-10-18：＋七隻事件巔峰 Boss 各 3 件、深淵裂縫事件 5 件、淵鯨再 2 件；2026-10-24：＋6 件戰星傳奇；2026-10-26：＋永恆珠寶 2 件（英雄悲劇、不朽恨意）
                "（2026-10-27 第二批 ＋91）終局 2 + 追逐級 13 + 其他終局 3 + 具名 Boss 2 + 共通 41（2026-10：＋德魯伊的獸魂符 4 件；2026-10-15 ＋6 件、＋3 件圖騰；2026-10-17 ＋棘壘） + 地圖事件 30 + 極境 4 + 世界 Boss 2 + 劫盜 14 + 巔峰 11（2026-10-15 ＋1） + 四個終局共通 1（蛛巢壁壘） + 星骸之海的淵鯨 3 + 只能賭的 1（2026-10-17 踏骸之履）");
        final Set<String> names = new HashSet<>();
        for (final UniqueItem unique : repository.uniques()) {
            final String where = unique.id();
            assertTrue(names.add(unique.name()), where + " 名字重複");
            assertNotNull(repository.base(unique.base()), where);
            assertFalse(unique.flavour().isEmpty(), where + " 要有世界觀文字");
            // 2026-10-26 照 PoE2 0.5：天樞墜（Astramentis）、巨像之心（Kaom's Heart）原作就只有兩條
            // 2026-10-27 照 PoE2 0.5：映鏡指環（Kalandra's Touch）、孀雨粗弓（Widowhail）原作只有一條，濁源指環（Original Sin）兩條
            final int least = FEW_LINES.getOrDefault(where, 3);
            assertTrue(unique.affixIds().size() + repository.uniqueVeiled(where).size() >= least, where + " 至少三條詞綴（隱匿傳奇的隱匿詞綴也算）");
            // 2026-10-28 照 PoE2：poe2db「禁止掉落」的三件（焰扣束帶、奔雷皮靴、禁視鏈冠）sources 是空的
            assertEquals(DISABLED.contains(where), unique.sources().isEmpty(), where + " 要有掉落來源（禁止掉落的才是空的）");
            for (final String source : unique.sources()) {
                assertTrue(Set.of("final", "map_boss", "chance", "gamble", "chase").contains(source) || maps.contains(source)
                                || EVENT_SOURCES.contains(source) || OTHER_SOURCES.contains(source)
                                || source.startsWith("uber_") && maps.contains(source.substring(5)),
                        where + " 的來源 " + source + " 不存在");
            }
            for (final String affixId : unique.affixIds()) {
                final Affix affix = repository.affix(affixId);
                assertNotNull(affix, affixId);
                for (final AffixMod mod : affix.mods()) {
                    if (tw.exilecore.stats.Recently.hasCondition(mod.stat())) {
                        assertNotNull(tw.exilecore.stats.Recently.condition(mod.stat()), affixId + "：不認得的條件 " + mod.stat());
                    }
                    assertTrue(stats.contains(tw.exilecore.stats.Recently.stat(mod.stat())), affixId + "：不認得的屬性 " + mod.stat());
                    for (final String tag : mod.tags()) {
                        assertTrue(tags.contains(tag), affixId + "：不認得的標籤 " + tag);
                    }
                    if (SpecialEffectLines.STATS.contains(mod.stat()) || mod.stat().startsWith("flag.")) {
                        assertEquals(ModType.FLAT, mod.type(), affixId + "：特殊效果要用 FLAT");
                    }
                    if (UNTAGGED.contains(mod.stat())) {
                        assertTrue(mod.tags().isEmpty(), affixId + "：" + mod.stat() + " 引擎不看標籤，寫了也沒用");
                    }
                    if (mod.stat().equals(StatKeys.SKILL_LEVEL)) {
                        for (final String tag : mod.tags()) {
                            assertFalse(WEAPON_TAGS.contains(tag), affixId + "：技能等級不看武器標籤");
                        }
                    }
                    if (mod.variable() != null) {
                        assertTrue(affix.tiers().getFirst().vars().containsKey(mod.variable()), affixId + " 缺少變數 " + mod.variable());
                        // 小數擲骰寫 {value/100}（顯示跟效果一樣）
                        // 2026-10-26：{名字:甲|乙} 是選項（永恆珠寶的征服者，Affix.choices）
                        assertTrue(affix.text().contains("{" + mod.variable() + "}") || affix.text().contains("{" + mod.variable() + "/")
                                        || affix.text().contains("{" + mod.variable() + ":"),
                                affixId + " 的文字沒有 {" + mod.variable() + "}");
                    }
                }
            }
        }
    }

    @Test
    void sourcesSplitIntoBossOnlyAndFindableUniques() {
        // 2026-10 PoE2 改版：天穹之心、星爐、守陵者的誓約、觀星者的星盤…退役，換成新的一批
        final Set<String> finals = ids(repository.uniquesFor("final", 84));
        // 2026-10-15：＋蛛巢壁壘（四個終局都掉）；2026-10-26：＋英雄悲劇（照 PoE2 是終局級 Boss 奧爾羅斯掉的永恆珠寶）
        // 2026-10-26：英雄悲劇改成固定 1%（drop_chance），不在 final 的權重池裡，改由 fixedChanceUniques 擲
        // 2026-10-28 照 PoE2：＋原作限定 Boss 掉、我們沒有那個內容的（試煉大師的憎鑄腿鎧、阿茲里寶庫的穿雲箭袋與阿茲里的輕視、
        //   灰燼仲裁者的神聖烈焰）；天樞墜、隕砧、蛛巢壁壘原作沒有限定，另外也是全域掉落
        assertEquals(Set.of("celestial_pivot", "meteor_anvil", "spider_bulwark", "hateforge_greaves", "tailwind_quiver",
                "sacred_flame", "atziris_contempt"), finals);
        assertEquals(Set.of("heroic_tragedy"), ids(repository.fixedChanceUniques("final", 84)));
        assertEquals(Set.of("undying_hate", "masters_reach"), ids(repository.fixedChanceUniques("abyss_lich", 84))); // 2026-10-28：＋主宰之觸（同一隻 Boss）
        assertFalse(ids(repository.uniquesFor("abyss_lich", 84)).contains("undying_hate"));
        assertEquals(0.01, repository.dropChance("heroic_tragedy"), 1e-12);
        assertEquals(0.01, repository.dropChance("undying_hate"), 1e-12);
        assertEquals(0, repository.dropChance("celestial_pivot"), 1e-12);
        assertEquals(Set.of("gravewarden_maul"), ids(repository.uniquesFor("royal_tomb", 84)));
        assertEquals(Set.of("astrolabe_wand"), ids(repository.uniquesFor("observatory", 84)));
        // 新武器（弩、長棍、矛）都有自己的傳奇，地圖 Boss 會掉、僥倖石做得出來
        // 2026-10-26 照 PoE2 0.5 傳奇重做：弩、長棍、矛的一般掉落傳奇換到原作的基底（這次之前 xbow_26、qstaff_25、spear_25）
        for (final String base : List.of("xbow_22", "qstaff_22", "spear_21")) {
            final ItemData normal = generator.generate(repository.base(base), Rarity.NORMAL, 70, new Random(1));
            assertFalse(CurrencyOperation.chanceUniques(normal, generator).isEmpty(), base + " 要有能用僥倖石做的傳奇");
        }
        // 物品等級 68 才有的：低一點的地圖 Boss 不會掉
        assertFalse(ids(repository.uniquesFor("map_boss", 67)).contains("prism_wall"));
        assertTrue(ids(repository.uniquesFor("map_boss", 68)).contains("prism_wall"));
        // 練功用的兩件：劇情就賭得到，地圖 Boss 不掉
        assertTrue(ids(repository.uniquesFor("gamble", 5)).containsAll(Set.of("roamers_boots", "goldleaf_hood")));
        assertFalse(ids(repository.uniquesFor("map_boss", 84)).contains("roamers_boots"));
        // 2026-10-28 照 PoE2：限定掉落（Boss、地圖事件、極境、劫盜、世界 Boss 專屬）的做不出來、賭不到；
        // 原作沒有掉落限制的（以前是內容專屬的）也是全域掉落，原本的內容照樣會掉。這次之前內容專屬的一律做不出來、賭不到
        for (final UniqueItem unique : repository.uniques()) {
            if (!UniqueRarity.global(unique)) {
                assertFalse(unique.droppedBy("chance") || unique.droppedBy("gamble") || unique.droppedBy("map_boss"), unique.id());
            }
        }
        for (final String id : List.of("hateforge_greaves", "tailwind_quiver", "pragmatist_armour", "wisdom_action_wraps", "collapsing_horizon",
                "sacred_flame", "atziris_contempt", "votive_flame", "voidgaze_helm", "heroic_tragedy", "undying_hate")) {
            assertFalse(UniqueRarity.global(repository.unique(id)), id + "：原作限定 Boss 掉");
        }
        for (final String id : List.of("sanctum_helm", "keymasters_ring", "phaseboots", "outlaws_vest", "dirge_staff", "somnambulist",
                "celestial_pivot", "rime_halo", "gravewarden_maul", "blinkstep_shroud", "winterbound_ring", "worldsplitter")) {
            final UniqueItem unique = repository.unique(id);
            assertTrue(unique.droppedBy("map_boss") && unique.droppedBy("gamble") && UniqueRarity.global(unique), id + "：原作沒有掉落限制");
            assertTrue(unique.sources().size() > 3, id + "：原本的內容照樣會掉");
        }
        // 每種地圖事件各五件（2026-10 擴充兩次，各多兩件；2026-10-18 深淵裂縫也五件）；每個極境各一件，而且物品等級 80 起
        for (final String event : EVENT_SOURCES) {
            assertEquals(5, repository.uniquesFor(event, 84).size(), event + " 的專屬傳奇");
            assertTrue(repository.uniquesFor(event, 59).isEmpty(), event + "：物品等級 60 起");
        }
        for (final String finalId : List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel")) {
            assertEquals(1, repository.uniquesFor("uber_" + finalId, 91).size(), finalId + " 的極境專屬傳奇");
            assertTrue(repository.uniquesFor("uber_" + finalId, 79).isEmpty(), finalId + "：極境的傳奇物品等級 80 起");
        }
        // 2026-10：巔峰 Boss（覺醒之主・虛星）自己的十件，物品等級 85 起；做不出來、賭不到、一般地圖 Boss 不掉
        final List<UniqueItem> awakener = repository.uniquesFor("awakener", 92);
        assertEquals(12, awakener.size(), "虛星的專屬傳奇（2026-10-15：＋神諭之矛；2026-10-28：＋無與倫比，原作灰燼仲裁者掉）");
        assertTrue(repository.uniquesFor("awakener", 84).isEmpty(), "虛星的傳奇物品等級 85 起");
        for (final UniqueItem unique : awakener) {
            // 2026-10-28 照 PoE2：瞬閃之影（Hyrri's Ire）、神諭之矛（The Ordained）原作沒有掉落限制，另外也是全域掉落
            if (Set.of("blinkstep_shroud", "oracle_spear").contains(unique.id())) {
                assertTrue(UniqueRarity.global(unique), unique.id());
            } else {
                assertEquals(Set.of("awakener"), Set.copyOf(unique.sources()), unique.id());
            }
        }
        // 2026-10：星骸之海的淵鯨（比照巔峰）三件，物品等級 85 起，只有淵鯨會掉
        assertEquals(Set.of("abyss_echo", "tidewhisper_bow", "tide_devourer", "undertow_coil", "whalesong_pearl"), ids(repository.uniquesFor("abyss_whale", 92))); // 2026-10-18：＋暗流束帶、鯨歌珠
        assertTrue(repository.uniquesFor("abyss_whale", 84).isEmpty());
    }

    private static Set<String> ids(final List<UniqueItem> uniques) {
        return uniques.stream().map(UniqueItem::id).collect(Collectors.toSet());
    }

    private static StatSheet sheetOf(final ItemData data) {
        return new StatSheet().addAll(ItemStats.modifiers(repository, data));
    }

    private static double total(final ItemData data, final String stat, final Set<String> context) {
        return sheetOf(data).flat(stat, context);
    }

    /** 第 1 版的傳奇（這次改版之前掉的）：照舊的定義擺詞綴，沒有版本。 */
    private static ItemData legacy(final String unique, final int... values) {
        final ItemData old = generator.generateUnique(repository.unique(unique), 70, new Random(1));
        old.affixes().clear();
        for (int i = 0; i < values.length; i++) {
            old.affixes().add(new RolledAffix(UniqueItem.AFFIX_PREFIX + unique + "." + (i + 1), 1, Map.of("value", values[i])));
        }
        old.setUniqueVersion(1);
        return old;
    }

    private static int value(final ItemData data, final int index) {
        return data.affixes().get(index - 1).vars().get("value");
    }

    @Test
    void legacyMapsCoverEveryVersionedUnique() {
        for (final UniqueItem unique : repository.uniques()) {
            for (final Map.Entry<Integer, List<Integer>> entry : unique.legacy().entrySet()) {
                assertEquals(unique.affixIds().size(), entry.getValue().size(), unique.id());
                final java.util.Set<Integer> used = new HashSet<>();
                for (final int oldIndex : entry.getValue()) {
                    assertTrue(oldIndex >= 0, unique.id());
                    assertTrue(oldIndex == 0 || used.add(oldIndex), unique.id() + "：舊的第 " + oldIndex + " 條對到兩條新的");
                }
            }
            assertEquals(unique.version() > 1 || unique.legacy().isEmpty(), true, unique.id() + "：第 1 版不該有 legacy");
        }
    }

    @Test
    void uniqueRevisionChangesOnlyWhenTheDefinitionsChange(@org.junit.jupiter.api.io.TempDir final Path temp) throws IOException {
        final ItemRepository again = new ItemRepository();
        again.load(DATA);
        assertEquals(repository.uniqueRevision(), again.uniqueRevision(), "同樣的資料檔：指紋一樣（物品不用再檢查）");
        // 複製一份資料檔，把潮痕的生命範圍改掉
        for (final String folder : List.of("bases", "affixes")) {
            java.nio.file.Files.createDirectories(temp.resolve(folder));
            try (var files = java.nio.file.Files.list(DATA.resolve(folder))) {
                for (final Path file : files.toList()) {
                    java.nio.file.Files.copy(file, temp.resolve(folder).resolve(file.getFileName()));
                }
            }
        }
        final String uniques = java.nio.file.Files.readString(DATA.resolve("uniques.yml"));
        final String changed = uniques.replaceFirst("value: \\[40, 60\\]", "value: [45, 65]");
        assertFalse(uniques.equals(changed));
        java.nio.file.Files.writeString(temp.resolve("uniques.yml"), changed);
        final ItemRepository edited = new ItemRepository();
        edited.load(temp);
        assertFalse(repository.uniqueRevision() == edited.uniqueRevision(), "範圍改了：指紋不一樣");
    }

    /** 測試用：用 JSON 複製一件物品資料（跟存進物品的格式一樣）。 */
    private static final class ItemDataCopy {
        static ItemData of(final ItemData data) {
            final com.google.gson.Gson gson = new com.google.gson.Gson();
            return gson.fromJson(gson.toJson(data), ItemData.class);
        }
    }

    @Test
    void chaseUniquesAreRareAndNotCraftable() {
        int chase = 0;
        for (final UniqueItem unique : repository.uniques()) {
            if (!unique.chase()) {
                // 劫盜的隱匿傳奇（2026-10）有 70–90 的，其他 100
                // 2026-10-28 照 PoE2：全域掉落的照稀有度（常見 100…很稀有 8），但都比追逐級（≤ 10）的池子另外算；限定掉落的照舊 100
                final UniqueRarity rarity = repository.uniqueRarity(unique.id());
                if (rarity != null) {
                    assertEquals(rarity.dropWeight(), unique.weight(), unique.id() + "：沒寫 weight 照稀有度");
                } else {
                    assertTrue(unique.weight() >= (unique.droppedBy("heist") ? 70 : 100), unique.id() + "：一般傳奇權重 100");
                }
                continue;
            }
            chase++;
            assertTrue(unique.weight() <= 10, unique.id() + "：追逐級權重要壓低");
            assertTrue(unique.dropLevel() >= 75, unique.id() + "：追逐級要 T11 以上才掉");
            assertTrue(unique.droppedBy("chase"), unique.id() + "：從追逐級的擲骰掉（所有地圖 Boss 固定機率）");
            for (final String other : new String[] {"map_boss", "final", "uber", "shrine", "strongbox", "breach", "exile", "ritual", "delirium"}) {
                assertFalse(unique.droppedBy(other), unique.id() + "：追逐級只在 chase 池，不在 " + other + "（不然機率會被別的擲骰拉高）");
            }
            assertFalse(unique.droppedBy("chance") || unique.droppedBy("gamble"), unique.id() + "：追逐級不能做、不能賭");
            // 2026-10-26 照 PoE2 0.5 的 Kaom's Heart：巨像之心只剩兩條（+1500 生命、沒有精魂），其他的追逐級至少三條
            // 2026-10-27：映鏡指環、濁源指環照原作只有一、兩條（FEW_LINES）
            assertTrue(unique.affixIds().size() >= FEW_LINES.getOrDefault(unique.id(), 3), unique.id() + "：追逐級至少三條");
        }
        assertEquals(20, chase, "二十件追逐級傳奇（2026-10-15 加了九件；2026-10-27 照 PoE2 第二批 ＋7：映鏡指環、濁源指環、逆漏法袍、巧匠束帶、雷頌墜、冷蔑金冠、冷蔑步靴）");
        // 共通池（map_boss）任何等級都沒有追逐級：追逐級只從 chase 池、照固定機率掉
        for (final int level : new int[] {70, 75, 90}) {
            for (final UniqueItem unique : repository.uniquesFor("map_boss", level)) {
                assertFalse(unique.chase(), unique.id());
            }
        }
        assertEquals(20, repository.uniquesFor("chase", 75).size()); // 2026-10-27：13 → 20
        assertEquals(0, repository.uniquesFor("chase", 74).size());
        // 2026-10-15：退役的傳奇定義從 uniques.yml 刪掉了
        assertNull(repository.unique("star_chaser"));
        assertFalse(repository.retiredUnique("star_chaser"));
    }

    /** 只看基底的物理傷害：沒鑑定的物品不算詞綴，品質 0。 */
    private static double[] basePhysical(final UniqueItem unique, final int itemLevel) {
        final ItemData data = generator.generateUnique(unique, itemLevel, new Random(5));
        data.setIdentified(false);
        return ItemStats.snapshot(repository, repository.base(data.base()), data).damage().get(DamageType.PHYSICAL);
    }

    private static double curve(final int level) {
        return 1 + repository.scaling().damagePerLevel() * (level - 1);
    }

    @Test
    void uniqueWeaponBaseDamageGrowsWithItemLevelFromItsTier() {
        // 裂穹：stat_base 是鑄紋弓（bow_08，39 級那一階，固定數值）。物品等級比那一階高，基底傷害照稀有武器同一條曲線往上長
        // （2026-10-26 照 PoE2 0.5 傳奇重做換了那一階；這次之前是霜晶弓 72 級那一階、49–82）
        final UniqueItem bow = repository.unique("horizon_cleaver");
        final ItemBase bowTier = repository.base("bow_08");
        final int tierLevel = bowTier.requirements().level();
        final int[] tierDamage = bowTier.damage().get(DamageType.PHYSICAL);
        assertEquals(tierDamage[0], basePhysical(bow, tierLevel)[0]);
        assertEquals(tierDamage[1], basePhysical(bow, tierLevel)[1]);
        assertEquals(tierDamage[0], basePhysical(bow, 20)[0], "不會比它那一階低（低物品等級的舊傳奇數值不變）");
        assertEquals(tierDamage[1], basePhysical(bow, 20)[1]);
        for (final int level : new int[] {60, 75, 84, 91}) {
            final double factor = curve(level) / curve(tierLevel);
            assertEquals(Math.round(tierDamage[0] * factor), basePhysical(bow, level)[0], "物品等級 " + level);
            assertEquals(Math.round(tierDamage[1] * factor), basePhysical(bow, level)[1], "物品等級 " + level);
        }

        // 寫 base_level 的基底（劈世巨鎚：45 級那一階）也一樣，從 base_level 算起
        final UniqueItem maul = repository.unique("worldsplitter");
        final ItemBase maulBase = repository.base(maul.base());
        assertTrue(maulBase.baseLevel() > 0 && !maulBase.fixed());
        final int[] written = maulBase.damage().get(DamageType.PHYSICAL);
        assertEquals(written[1], basePhysical(maul, maulBase.baseLevel())[1]);
        // 2026-10-26 照 PoE2 0.5 傳奇重做：基底換成戰痕巨錘（45 級那一階；這次之前 79 級那一階、這裡寫物品等級 60）
        assertEquals(written[1], basePhysical(maul, maulBase.baseLevel() - 10)[1]);
        assertEquals(Math.round(written[1] * curve(91) / curve(maulBase.baseLevel())), basePhysical(maul, 91)[1]);

        // 需求照舊固定在那一階（屬性需求不跟著長）；詞綴的附加傷害也不跟著長
        final ItemData tier = generator.generateUnique(bow, tierLevel, new Random(5));
        final ItemData high = generator.generateUnique(bow, 91, new Random(5));
        final ItemBase bowBase = repository.base(tier.base());
        assertEquals(ItemStats.requirements(repository, bowBase, tier).dex(), ItemStats.requirements(repository, bowBase, high).dex());
        // 2026-10-27 照站長比照 poe2db 調強：第 1 條是增加物理，附加傷害變成第 2 條（這次之前是第 1 條）
        assertEquals(tier.affixes().get(1).vars(), high.affixes().get(1).vars(), "同一個種子擲出一樣的附加傷害");

        // 不是武器的傳奇不變（虛空冠冕的能量護盾）；稀有武器照原本的算法
        final UniqueItem diadem = repository.unique("hollow_diadem");
        final ItemData lowHelm = generator.generateUnique(diadem, 75, new Random(5));
        final ItemData highHelm = generator.generateUnique(diadem, 91, new Random(5));
        lowHelm.setIdentified(false);
        highHelm.setIdentified(false);
        final ItemBase helm = repository.base(lowHelm.base());
        assertEquals(ItemStats.snapshot(repository, helm, lowHelm).energyShield(), ItemStats.snapshot(repository, helm, highHelm).energyShield());
        final ItemBase rareBow = repository.base("bow_23");
        final ItemData rare = generator.generate(rareBow, Rarity.NORMAL, 84, new Random(5));
        // 2026-10-26：照 PoE2 分階的武器到 base_level 就是原值，物品等級再高也不往上長
        assertEquals(Math.round(rareBow.damage().get(DamageType.PHYSICAL)[1] * curve(Math.min(84, rareBow.baseLevel())) / curve(rareBow.baseLevel())),
                ItemStats.snapshot(repository, rareBow, rare).damage().get(DamageType.PHYSICAL)[1]);
    }

    @Test
    void horizonCleaverFollowsItsOriginalAndOldOnesKeepTheirRolls() {
        // 2026-10-26 照 PoE2 0.5 傳奇重做：裂穹照原作（附加物理、暴擊、偷取、分裂、分裂後穿透）；基底那一階是鑄紋弓（bow_08，39 級）。
        // 2026-10-27 照站長比照 poe2db 的頂級弓調強（第 5 版）：偷取魔力換成增加 150–190% 物理（放第 1 條），附加物理 40–48 到 65–72 → 75–85 到 130–150；
        // 這次之前是第 4 版（附加物理在第 1 條、沒有增加物理、第 4 條偷取魔力）
        final UniqueItem bow = repository.unique("horizon_cleaver");
        assertEquals(5, bow.version());
        assertEquals(6, bow.affixIds().size());
        final ItemBase tierBase = repository.base("bow_08");
        final int tierLevel = tierBase.requirements().level();
        final int[] written = tierBase.damage().get(DamageType.PHYSICAL);
        final ItemData fresh = generator.generateUnique(bow, 84, new Random(11));
        final ItemBase base = repository.base(fresh.base());
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, fresh);
        final int increased = fresh.affixes().get(0).vars().get("value");
        assertTrue(increased >= 150 && increased <= 190);
        assertEquals(increased / 100.0, snapshot.local().physical(), 1e-9, "增加物理是本地的");
        // 面板 = (基底 × 物品等級的倍率 + 附加) × (1 + 增加物理)
        final Map<String, Integer> added = fresh.affixes().get(1).vars();
        final double factor = curve(84) / curve(tierLevel);
        final double more = 1 + increased / 100.0;
        final double[] physical = snapshot.damage().get(DamageType.PHYSICAL);
        assertEquals(Math.round((written[0] * factor + added.get("min")) * more), physical[0], 1);
        assertEquals(Math.round((written[1] * factor + added.get("max")) * more), physical[1], 1);
        // 追逐級的強度：84 級無品質的物理 DPS 在原作頂級稀有弓（六條好詞綴約 530、全頂約 626）上下，不再只有 150 左右
        final double dps = (physical[0] + physical[1]) / 2.0 * snapshot.aps();
        assertTrue(dps >= 480 && dps <= 650, "物理 DPS " + dps);

        // 第 4 版（附加物理、暴擊、偷取生命、偷取魔力、分裂、分裂後穿透）：附加物理照比例換到新範圍，其他對得上的留著，
        // 偷取魔力拿掉，增加物理是新的（重擲）
        final String prefix = UniqueItem.AFFIX_PREFIX + bow.id() + ".";
        final ItemData v4 = generator.generateUnique(bow, 84, new Random(14));
        v4.affixes().clear();
        final RolledAffix oldAdded = new RolledAffix(prefix + "1", 1, Map.of("min", 44, "max", 72));
        oldAdded.record(1, Map.of("min", new int[] {40, 48}, "max", new int[] {65, 72}), "unique", "prefix");
        v4.affixes().add(oldAdded);
        v4.affixes().add(new RolledAffix(prefix + "2", 1, Map.of("value", 500)));
        v4.affixes().add(new RolledAffix(prefix + "3", 1, Map.of("value", 6)));
        v4.affixes().add(new RolledAffix(prefix + "4", 1, Map.of("value", 5)));
        v4.affixes().add(new RolledAffix(prefix + "5", 1, Map.of()));
        v4.affixes().add(new RolledAffix(prefix + "6", 1, Map.of()));
        v4.setUniqueVersion(4);
        assertTrue(UniqueUpgrade.outdated(repository, v4));
        assertTrue(UniqueUpgrade.apply(repository, v4));
        assertEquals(6, v4.affixes().size());
        assertEquals(5, v4.uniqueVersion());
        final int rerolled = v4.affixes().get(0).vars().get("value");
        assertTrue(rerolled >= 150 && rerolled <= 190, "新的增加物理");
        assertEquals(80, v4.affixes().get(1).vars().get("min"), "舊範圍的正中間 → 新範圍的正中間");
        assertEquals(150, v4.affixes().get(1).vars().get("max"), "舊範圍的最高 → 新範圍的最高");
        assertEquals(Map.of("value", 500), v4.affixes().get(2).vars());
        assertEquals(Map.of("value", 6), v4.affixes().get(3).vars());
        assertFalse(UniqueUpgrade.outdated(repository, v4));

        // 第 3 版（七條，第 1 條就是增加 150–190% 物理）：增加物理接回第 1 條，附加物理、暴擊、偷取生命、分裂接上；
        // 偷取魔力、穿透 5 個拿掉，分裂後穿透是新的
        final ItemData v3 = generator.generateUnique(bow, 75, new Random(12));
        v3.affixes().clear();
        v3.affixes().add(new RolledAffix(prefix + "1", 1, Map.of("value", 170)));
        v3.affixes().add(new RolledAffix(prefix + "2", 1, Map.of("min", 80, "max", 140)));
        v3.affixes().add(new RolledAffix(prefix + "3", 1, Map.of("value", 450)));
        v3.affixes().add(new RolledAffix(prefix + "4", 1, Map.of("value", 7)));
        v3.affixes().add(new RolledAffix(prefix + "5", 1, Map.of("value", 5)));
        v3.affixes().add(new RolledAffix(prefix + "6", 1, Map.of()));
        v3.affixes().add(new RolledAffix(prefix + "7", 1, Map.of()));
        v3.setUniqueVersion(3);
        assertTrue(UniqueUpgrade.apply(repository, v3));
        assertEquals(6, v3.affixes().size());
        assertEquals(Map.of("value", 170), v3.affixes().get(0).vars());
        assertEquals(Map.of("min", 80, "max", 140), v3.affixes().get(1).vars());
        assertEquals(Map.of("value", 450), v3.affixes().get(2).vars());
        assertEquals(Map.of("value", 7), v3.affixes().get(3).vars());
        assertFalse(UniqueUpgrade.outdated(repository, v3));

        // 第 2 版（六條，附加物理在第 1 條）也一樣接得上
        final ItemData v2 = generator.generateUnique(bow, 75, new Random(13));
        v2.affixes().clear();
        v2.affixes().add(new RolledAffix(prefix + "1", 1, Map.of("min", 76, "max", 131)));
        v2.affixes().add(new RolledAffix(prefix + "2", 1, Map.of("value", 600)));
        v2.affixes().add(new RolledAffix(prefix + "3", 1, Map.of("value", 8)));
        v2.affixes().add(new RolledAffix(prefix + "4", 1, Map.of("value", 4)));
        v2.affixes().add(new RolledAffix(prefix + "5", 1, Map.of()));
        v2.affixes().add(new RolledAffix(prefix + "6", 1, Map.of()));
        v2.setUniqueVersion(2);
        assertTrue(UniqueUpgrade.apply(repository, v2));
        assertEquals(Map.of("min", 76, "max", 131), v2.affixes().get(1).vars(), "第 2 版的第 1 條");
        assertEquals(Map.of("value", 600), v2.affixes().get(2).vars(), "第 2 版的第 2 條");
        assertEquals(Map.of("value", 8), v2.affixes().get(3).vars(), "第 2 版的第 3 條");
        assertFalse(UniqueUpgrade.outdated(repository, v2));
    }

    @Test
    void chaseWeaponsSitAroundTopRaresOfTheirKind() {
        // 2026-10-27 照站長比照 poe2db：弒神之矛數值不變（84 級無品質物理 DPS 約 590–772，原作頂級稀有矛六條好詞綴約 660、全頂約 786）
        final UniqueItem spear = repository.unique("godslayer_spear");
        final ItemData rolled = generator.generateUnique(spear, 84, new Random(21));
        final ItemStats.Snapshot spearStats = ItemStats.snapshot(repository, repository.base(rolled.base()), rolled);
        final double[] spearPhysical = spearStats.damage().get(DamageType.PHYSICAL);
        final double spearDps = (spearPhysical[0] + spearPhysical[1]) / 2.0 * spearStats.aps();
        assertTrue(spearDps >= 560 && spearDps <= 800, "弒神之矛物理 DPS " + spearDps);

        // 萬象之杖：+5 全部法術技能等級、增加 200–240% 法術傷害、35–45% 施法速度（這次之前 +3、80–120%、15–25%）
        final UniqueItem staff = repository.unique("omniscience_staff");
        assertEquals(2, staff.version());
        final ItemData rolledStaff = generator.generateUnique(staff, 84, new Random(22));
        assertEquals(5, total(rolledStaff, StatKeys.SKILL_LEVEL, Set.of(Tags.SPELL)), 1e-9);
        final int spell = rolledStaff.affixes().get(1).vars().get("value");
        final int cast = rolledStaff.affixes().get(2).vars().get("value");
        assertTrue(spell >= 200 && spell <= 240 && cast >= 35 && cast <= 45, spell + "% / " + cast + "%");
    }
}
