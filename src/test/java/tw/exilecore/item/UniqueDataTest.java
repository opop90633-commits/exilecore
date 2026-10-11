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
    static final Set<String> EVENT_SOURCES = Set.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium");
    /** 其他專屬的來源（2026-10）：劫盜的大金庫、開服活動的世界 Boss（world_events.yml 直接列傳奇代號）。 */
    static final Set<String> OTHER_SOURCES = Set.of("heist", "world_boss");
    /** 地圖代號（具名 Boss 的來源）。 */
    static Set<String> maps;
    /** 引擎用空的情境查的屬性（寫標籤也不會生效）。 */
    static final Set<String> UNTAGGED = Set.of(StatKeys.LIFE_ON_KILL, StatKeys.MANA_ON_KILL, StatKeys.ES_ON_KILL,
            StatKeys.ONSLAUGHT_ON_KILL, StatKeys.KILL_EXPLODE, StatKeys.DAMAGE_FROM_MANA, StatKeys.LIFE_RECOUP,
            StatKeys.RESIST_MAX, StatKeys.AVOID_FREEZE);
    static final Set<String> WEAPON_TAGS = Set.of(Tags.SWORD, Tags.AXE, Tags.MACE, Tags.DAGGER, Tags.CLAW, Tags.WAND, Tags.STAFF,
            Tags.BOW, Tags.CROSSBOW, Tags.QUARTERSTAFF, Tags.SPEAR, Tags.ONE_HAND, Tags.TWO_HAND);

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
        }
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
        // 2026-10：對身上有某種異常的敵人（damage.vs_chilled…，CombatListener.ailmentWord）
        for (final String ailment : List.of("chilled", "frozen", "ignited", "shocked", "poisoned", "bleeding")) {
            stats.add(StatKeys.DAMAGE_VS_AILMENT + ailment);
        }
        tags = constants(Tags.class);
        tags.add("curse"); // 2026-10：詛咒技能的標籤（SkillDefinition 給範圍印記加的，Tags 沒有常數）
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
        assertEquals(88, repository.uniques().size(),
                "終局 2 + 追逐級 4 + 其他終局 3 + 具名 Boss 2 + 共通 27 + 地圖事件 30 + 極境 4 + 世界 Boss 2 + 劫盜 14");
        final Set<String> names = new HashSet<>();
        for (final UniqueItem unique : repository.uniques()) {
            final String where = unique.id();
            assertTrue(names.add(unique.name()), where + " 名字重複");
            assertNotNull(repository.base(unique.base()), where);
            assertFalse(unique.flavour().isEmpty(), where + " 要有世界觀文字");
            assertTrue(unique.affixIds().size() + repository.uniqueVeiled(where).size() >= 3, where + " 至少三條詞綴（隱匿傳奇的隱匿詞綴也算）");
            assertFalse(unique.sources().isEmpty(), where + " 要有掉落來源");
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
                        assertTrue(affix.text().contains("{" + mod.variable() + "}") || affix.text().contains("{" + mod.variable() + "/"),
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
        assertEquals(Set.of("celestial_pivot", "meteor_anvil"), finals);
        assertEquals(Set.of("gravewarden_maul"), ids(repository.uniquesFor("royal_tomb", 84)));
        assertEquals(Set.of("astrolabe_wand"), ids(repository.uniquesFor("observatory", 84)));
        // 新武器（弩、長棍、矛）都有自己的傳奇，地圖 Boss 會掉、僥倖石做得出來
        for (final String base : List.of("xbow_26", "qstaff_25", "spear_25")) {
            final ItemData normal = generator.generate(repository.base(base), Rarity.NORMAL, 70, new Random(1));
            assertFalse(CurrencyOperation.chanceUniques(normal, generator).isEmpty(), base + " 要有能用僥倖石做的傳奇");
        }
        // 物品等級 68 才有的：低一點的地圖 Boss 不會掉
        assertFalse(ids(repository.uniquesFor("map_boss", 67)).contains("prism_wall"));
        assertTrue(ids(repository.uniquesFor("map_boss", 68)).contains("prism_wall"));
        // 練功用的兩件：劇情就賭得到，地圖 Boss 不掉
        assertTrue(ids(repository.uniquesFor("gamble", 5)).containsAll(Set.of("roamers_boots", "goldleaf_hood")));
        assertFalse(ids(repository.uniquesFor("map_boss", 84)).contains("roamers_boots"));
        // Boss 專屬、地圖事件專屬、極境專屬、劫盜、世界 Boss 的做不出來、賭不到
        for (final UniqueItem unique : repository.uniques()) {
            final boolean exclusive = unique.droppedBy("final") || unique.droppedBy("royal_tomb") || unique.droppedBy("observatory")
                    || unique.sources().stream().anyMatch(source -> EVENT_SOURCES.contains(source) || OTHER_SOURCES.contains(source)
                    || source.startsWith("uber_"));
            if (exclusive) {
                assertFalse(unique.droppedBy("chance") || unique.droppedBy("gamble") || unique.droppedBy("map_boss"), unique.id());
            }
        }
        // 每種地圖事件各五件（2026-10 擴充兩次，各多兩件）；每個極境各一件，而且物品等級 80 起
        for (final String event : EVENT_SOURCES) {
            assertEquals(5, repository.uniquesFor(event, 84).size(), event + " 的專屬傳奇");
            assertTrue(repository.uniquesFor(event, 59).isEmpty(), event + "：物品等級 60 起");
        }
        for (final String finalId : List.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel")) {
            assertEquals(1, repository.uniquesFor("uber_" + finalId, 91).size(), finalId + " 的極境專屬傳奇");
            assertTrue(repository.uniquesFor("uber_" + finalId, 79).isEmpty(), finalId + "：極境的傳奇物品等級 80 起");
        }
    }

    private static Set<String> ids(final List<UniqueItem> uniques) {
        return uniques.stream().map(UniqueItem::id).collect(Collectors.toSet());
    }

    @Test
    void specialEffectsEndUpOnTheRolledItem() {
        final ItemData heart = generator.generateUnique(repository.unique("sky_heart"), 84, new Random(2));
        assertEquals(6, heart.affixes().size());
        assertEquals(1, total(heart, StatKeys.SKILL_LEVEL, Set.of()), 1e-9, "+1 全部技能等級");

        final ItemData staff = generator.generateUnique(repository.unique("city_burner"), 84, new Random(3));
        assertEquals(2, total(staff, StatKeys.SKILL_LEVEL, Set.of(Tags.SPELL, Tags.FIRE)), 1e-9);
        final double explode = total(staff, StatKeys.KILL_EXPLODE, Set.of());
        assertTrue(explode >= 5 && explode <= 8, "爆炸 " + explode);

        final ItemData crossbow = generator.generateUnique(repository.unique("formation_breaker"), 84, new Random(4));
        assertEquals(1, total(crossbow, StatKeys.SKILL_PROJECTILES, Set.of(Tags.CROSSBOW)), 1e-9, "用弩多一支");
        assertEquals(0, sheetOf(crossbow).flat(StatKeys.SKILL_PROJECTILES, Set.of(Tags.BOW)), 1e-9, "換成弓就沒有");

        final ItemData shell = generator.generateUnique(repository.unique("hollow_shell"), 84, new Random(5));
        assertTrue(StatKeys.flag(sheetOf(shell), StatKeys.FLAG_CHAOS_IMMUNE), "空殼給混沌免疫");

        final ItemData boots = generator.generateUnique(repository.unique("frostbite"), 84, new Random(6));
        assertTrue(StatKeys.flag(sheetOf(boots), StatKeys.FLAG_FREEZE_IMMUNE), "無法被凍結：用開關，不是避免（避免有 75% 上限）");
        assertEquals(0, sheetOf(boots).get(StatKeys.AVOID_FREEZE), 1e-9);
        assertTrue(SpecialEffectLines.of(sheetOf(boots)).contains("無法被凍結"), "角色面板看得到");

        // 角色面板的「特殊效果」看得到
        final List<String> lines = SpecialEffectLines.of(sheetOf(staff));
        assertTrue(lines.contains("+2 火焰法術技能等級"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("你殺死的敵人爆炸")), lines.toString());
    }

    private static StatSheet sheetOf(final ItemData data) {
        return new StatSheet().addAll(ItemStats.modifiers(repository, data));
    }

    private static double total(final ItemData data, final String stat, final Set<String> context) {
        return sheetOf(data).flat(stat, context);
    }

    @Test
    void recoupRollsInsideItsRange() {
        final ItemData vow = generator.generateUnique(repository.unique("iron_vow"), 70, new Random(8));
        final Map<String, Double> recoup = new java.util.HashMap<>();
        for (final Modifier modifier : ItemStats.modifiers(repository, vow)) {
            if (modifier.stat().equals(StatKeys.LIFE_RECOUP)) {
                recoup.put(modifier.stat(), modifier.value());
            }
        }
        final double value = recoup.getOrDefault(StatKeys.LIFE_RECOUP, 0.0);
        assertTrue(value >= 12 && value <= 18, "回復 " + value);
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
    void oldUniquesAreUpgradedToTheCurrentDefinitionKeepingRollsThatStillFit() {
        // 舊版的潮痕：三條詞綴（生命 50、冰冷抗性 25、物品數量 10）；新版五條，legacy { 1: [1, 2, 0, 0, 3] }
        final ItemData old = legacy("tide_scar", 50, 25, 10);
        final String uuid = old.uuid();
        assertEquals(1, old.uniqueVersion());
        assertTrue(UniqueUpgrade.outdated(repository, old));
        final ItemData copy = ItemDataCopy.of(old);

        assertTrue(UniqueUpgrade.apply(repository, old));
        assertEquals(5, old.affixes().size());
        assertEquals(50, value(old, 1), "生命 50 還在新範圍 [40, 60] 裡：留著");
        assertTrue(value(old, 2) >= 30 && value(old, 2) <= 40, "冰冷抗性 25 不在 [30, 40]：重擲 " + value(old, 2));
        assertTrue(value(old, 3) >= 3 && value(old, 3) <= 5, "新的擊殺回復生命 [3, 5]：擲 " + value(old, 3));
        assertTrue(value(old, 4) >= 3 && value(old, 4) <= 5);
        assertEquals(10, value(old, 5), "物品數量 10 從舊的第 3 條搬到新的第 5 條，還在 [8, 12] 裡：留著");
        assertEquals(uuid, old.uuid(), "還是同一件");
        assertEquals(70, old.ilvl());
        assertEquals(2, old.uniqueVersion(), "補完記下版本");
        assertFalse(UniqueUpgrade.outdated(repository, old), "補完就是新版");
        assertFalse(UniqueUpgrade.apply(repository, old), "新版的不再動");

        // 同一件（同一個 UUID）每次補出來都一樣
        UniqueUpgrade.apply(repository, copy);
        for (int i = 0; i < old.affixes().size(); i++) {
            assertEquals(old.affixes().get(i).vars(), copy.affixes().get(i).vars());
        }
    }

    @Test
    void reorderedUniquesKeepTheRightRollsThroughTheLegacyMap() {
        // 舊版的守陵者的誓約：近戰物理 55、力量 40、攻速 12、護甲 220；新版插了攻擊附加與雙倍傷害進去
        final ItemData oath = legacy("tomb_oath", 55, 40, 12, 220);
        assertTrue(UniqueUpgrade.apply(repository, oath));
        assertEquals(6, oath.affixes().size());
        assertTrue(value(oath, 1) >= 150 && value(oath, 1) <= 190, "近戰物理 55 不在新範圍：重擲");
        final Map<String, Integer> added = oath.affixes().get(1).vars();
        assertTrue(added.get("min") >= 14 && added.get("min") <= 18 && added.get("max") >= 30 && added.get("max") <= 38, "新的攻擊附加 " + added);
        assertEquals(40, value(oath, 3), "力量 40 從舊的第 2 條搬到新的第 3 條");
        assertEquals(12, value(oath, 4), "攻速 12 從舊的第 3 條搬到新的第 4 條");
        assertTrue(value(oath, 5) >= 10 && value(oath, 5) <= 15, "新的雙倍傷害");
        assertEquals(220, value(oath, 6), "護甲 220 從舊的第 4 條搬到新的第 6 條");

        // 舊版的觀星者的星盤：法術傷害 50、魔力 55、施法速度 14、閃電抗性 25 → 新版 [0, 1, 3, 0, 2, 0]
        final ItemData chart = legacy("star_chart", 50, 55, 14, 25);
        assertTrue(UniqueUpgrade.apply(repository, chart));
        assertEquals("unique.star_chart.1", chart.affixes().get(0).id(), "+1 法術技能等級（固定值，沒有變數）");
        assertTrue(value(chart, 2) >= 60 && value(chart, 2) <= 80, "法術傷害 50 不在 [60, 80]：重擲");
        assertEquals(14, value(chart, 3), "施法速度 14 從舊的第 3 條搬到新的第 3 條");
        assertEquals(55, value(chart, 5), "魔力 55 從舊的第 2 條搬到新的第 5 條");

        // 舊版的天穹之心：全部往後挪一條
        final ItemData heart = legacy("sky_heart", 30, 28, 80, 10, 20);
        assertTrue(UniqueUpgrade.apply(repository, heart));
        assertEquals("unique.sky_heart.1", heart.affixes().get(0).id());
        assertEquals(30, value(heart, 2), "全部屬性 30");
        assertEquals(28, value(heart, 3), "傷害 28");
        assertEquals(80, value(heart, 4), "生命 80");
        assertEquals(10, value(heart, 5), "全部元素抗性 10");
        assertEquals(20, value(heart, 6), "稀有度 20");

        // 剛掉的新版傳奇不動
        final ItemData fresh = generator.generateUnique(repository.unique("iron_vow"), 70, new Random(3));
        assertEquals(2, fresh.uniqueVersion());
        assertFalse(UniqueUpgrade.outdated(repository, fresh));
        // 新的傳奇是第 1 版
        assertEquals(1, generator.generateUnique(repository.unique("hawk_eye"), 70, new Random(3)).uniqueVersion());
        // 不是傳奇、或傳奇被拿掉了：不動
        final ItemData rare = generator.generate(repository.base("coral_ring"), Rarity.RARE, 70, new Random(4));
        assertFalse(UniqueUpgrade.apply(repository, rare));
        fresh.setUnique("removed_unique");
        assertFalse(UniqueUpgrade.apply(repository, fresh));
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
        // 這次改版的 7 件是第 2 版
        for (final String id : List.of("sky_heart", "tomb_oath", "star_chart", "tide_scar", "ashen_mantle", "frostbite", "iron_vow")) {
            assertEquals(2, repository.unique(id).version(), id);
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
                assertTrue(unique.weight() >= (unique.droppedBy("heist") ? 70 : 100), unique.id() + "：一般傳奇權重 100");
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
            // 2026-10：照 PoE2 同部位的傳奇重做，巨像之心只剩三條（大量生命、沒有精魂）
            assertTrue(unique.affixIds().size() >= 3, unique.id() + "：追逐級至少三條");
        }
        assertEquals(4, chase, "四件追逐級傳奇");
        // 共通池（map_boss）任何等級都沒有追逐級：追逐級只從 chase 池、照固定機率掉
        for (final int level : new int[] {70, 75, 90}) {
            for (final UniqueItem unique : repository.uniquesFor("map_boss", level)) {
                assertFalse(unique.chase(), unique.id());
            }
        }
        assertEquals(4, repository.uniquesFor("chase", 75).size());
        assertEquals(0, repository.uniquesFor("chase", 74).size());
        // 逐星者的「偷詞綴」是 steal.mods（秒數）；2026-10 PoE2 改版退役了，定義留著讓舊物品照常生效
        final UniqueItem chaser = repository.unique("star_chaser");
        assertTrue(repository.retiredUnique("star_chaser"));
        assertFalse(chaser.chase(), "退役的不算追逐級（不會掉）");
        boolean steal = false;
        for (final String affixId : chaser.affixIds()) {
            for (final AffixMod mod : repository.affix(affixId).mods()) {
                steal |= mod.stat().equals(StatKeys.STEAL_MODS);
            }
        }
        assertTrue(steal, "逐星者要有 steal.mods");
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
        // 裂穹：stat_base 是霜晶弓（72 級那一階，固定 34–57）。物品等級比那一階高，基底傷害照稀有武器同一條曲線往上長
        final UniqueItem bow = repository.unique("horizon_cleaver");
        assertEquals(34, basePhysical(bow, 72)[0]);
        assertEquals(57, basePhysical(bow, 72)[1]);
        assertEquals(34, basePhysical(bow, 60)[0], "不會比它那一階低（低物品等級的舊傳奇數值不變）");
        assertEquals(57, basePhysical(bow, 60)[1]);
        for (final int level : new int[] {75, 84, 91}) {
            final double factor = curve(level) / curve(72);
            assertEquals(Math.round(34 * factor), basePhysical(bow, level)[0], "物品等級 " + level);
            assertEquals(Math.round(57 * factor), basePhysical(bow, level)[1], "物品等級 " + level);
        }
        assertEquals(38, basePhysical(bow, 84)[0], "物品等級 84：×1.12");
        assertEquals(64, basePhysical(bow, 84)[1]);

        // 寫 base_level 的基底（劈世巨鎚：79 級那一階）也一樣，從 base_level 算起
        final UniqueItem maul = repository.unique("worldsplitter");
        final ItemBase maulBase = repository.base(maul.base());
        assertTrue(maulBase.baseLevel() > 0 && !maulBase.fixed());
        final int[] written = maulBase.damage().get(DamageType.PHYSICAL);
        assertEquals(written[1], basePhysical(maul, maulBase.baseLevel())[1]);
        assertEquals(written[1], basePhysical(maul, 60)[1]);
        assertEquals(Math.round(written[1] * curve(91) / curve(maulBase.baseLevel())), basePhysical(maul, 91)[1]);

        // 需求照舊固定在那一階（屬性需求不跟著長）；詞綴的附加傷害也不跟著長
        final ItemData tier = generator.generateUnique(bow, 72, new Random(5));
        final ItemData high = generator.generateUnique(bow, 91, new Random(5));
        final ItemBase bowBase = repository.base(tier.base());
        assertEquals(ItemStats.requirements(repository, bowBase, tier).dex(), ItemStats.requirements(repository, bowBase, high).dex());
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
        assertEquals(Math.round(rareBow.damage().get(DamageType.PHYSICAL)[1] * curve(84) / curve(rareBow.baseLevel())),
                ItemStats.snapshot(repository, rareBow, rare).damage().get(DamageType.PHYSICAL)[1]);
    }

    @Test
    void horizonCleaverHasLocalPhysicalAndOldOnesKeepTheirRolls() {
        final UniqueItem bow = repository.unique("horizon_cleaver");
        assertEquals(3, bow.version());
        assertEquals(7, bow.affixIds().size());
        final ItemData fresh = generator.generateUnique(bow, 84, new Random(11));
        final int increased = value(fresh, 1);
        assertTrue(increased >= 150 && increased <= 190, "增加物理傷害 " + increased);
        final ItemBase base = repository.base(fresh.base());
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, fresh);
        assertEquals(increased / 100.0, snapshot.local().physical(), 1e-9, "武器上的增加物理是本地的");
        // 面板 =（基底 × 物品等級的倍率 + 附加）×（1 + 增加）
        final Map<String, Integer> added = fresh.affixes().get(1).vars();
        final double factor = curve(84) / curve(72);
        assertEquals(Math.round((34 * factor + added.get("min")) * (1 + increased / 100.0)), snapshot.damage().get(DamageType.PHYSICAL)[0]);
        assertEquals(Math.round((57 * factor + added.get("max")) * (1 + increased / 100.0)), snapshot.damage().get(DamageType.PHYSICAL)[1]);
        assertTrue(snapshot.damage().get(DamageType.PHYSICAL)[0] >= 200, "面板要跟同階不錯的稀有弓差不多：" + snapshot.damage().get(DamageType.PHYSICAL)[0]);

        // 第 2 版的裂穹（六條，沒有增加物理）：登入時補上新的第 1 條，原本六條的數值原封不動往後挪一條
        final ItemData old = generator.generateUnique(bow, 75, new Random(12));
        final List<RolledAffix> six = new java.util.ArrayList<>(old.affixes().subList(1, 7));
        old.affixes().clear();
        for (int i = 0; i < six.size(); i++) {
            old.affixes().add(new RolledAffix(UniqueItem.AFFIX_PREFIX + bow.id() + "." + (i + 1), 1, six.get(i).vars()));
        }
        old.setUniqueVersion(2);
        assertTrue(UniqueUpgrade.outdated(repository, old));
        assertTrue(UniqueUpgrade.apply(repository, old));
        assertEquals(7, old.affixes().size());
        assertEquals(3, old.uniqueVersion());
        assertTrue(value(old, 1) >= 150 && value(old, 1) <= 190, "新的增加物理 " + value(old, 1));
        for (int i = 0; i < six.size(); i++) {
            assertEquals(six.get(i).vars(), old.affixes().get(i + 1).vars(), "原本的第 " + (i + 1) + " 條");
        }
        assertFalse(UniqueUpgrade.outdated(repository, old));
    }
}
