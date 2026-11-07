package tw.exilecore.loot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次 3：掉落過濾器、換金幣、通貨袋。 */
class LootTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static LootConfig config;
    static DropTable drops;

    @BeforeAll
    static void load() throws IOException {
        config = new LootConfig();
        config.load(DATA);
        drops = new DropTable();
        drops.load(DATA);
    }

    @Test
    void configLoads() {
        assertTrue(config.autoPickup());
        assertEquals(List.of("loose", "standard", "strict"), config.presets().stream().map(LootConfig.Preset::id).toList());
        assertEquals("standard", config.defaultPresetId());
        final LootConfig.Preset strict = config.preset("strict");
        assertEquals(Set.of(Rarity.NORMAL, Rarity.MAGIC), strict.hideEquipment());
        assertEquals(LootFilter.FlaskRule.BETTER, strict.flasks());
    }

    @Test
    void brokenReloadKeepsOldSettings(@TempDir final Path dir) throws IOException {
        final LootConfig live = new LootConfig();
        live.load(DATA);
        Files.writeString(dir.resolve(LootConfig.FILE),
                "filter:\n  default: standrad\n  presets:\n    standard: { name: 標準, hide_equipment: [normal] }\n");
        assertThrows(IllegalArgumentException.class, () -> live.load(dir), "拼錯的預設組合要報錯");
        assertEquals("standard", live.defaultPresetId(), "寫錯時還是用原本的設定");
        assertEquals(3, live.presets().size());
        assertEquals("p=standard;v=2", live.defaultFilter().encode());
        Files.writeString(dir.resolve(LootConfig.FILE), "filter:\n  presets:\n    strict: { flasks: sometimes }\n");
        assertThrows(IllegalArgumentException.class, () -> live.load(dir));
        assertEquals(3, live.presets().size(), "後面的組合寫錯也不會換掉一半");
    }

    @Test
    void presetCurrenciesExist() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        for (final String id : config.mentionedCurrencies()) {
            assertNotNull(currencies.get(id), "loot.yml 寫到的通貨要存在：" + id);
        }
    }

    @Test
    void defaultFilterHidesNormalAndLowCurrency() {
        final LootFilter filter = config.defaultFilter();
        assertEquals("standard", filter.preset());
        assertTrue(filter.hides(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false));
        assertFalse(filter.hides(DropTable.Kind.EQUIPMENT, Rarity.MAGIC, null, false));
        assertFalse(filter.hides(DropTable.Kind.EQUIPMENT, Rarity.UNIQUE, null, false));
        assertTrue(filter.hides(DropTable.Kind.CURRENCY, null, "whetstone", false));
        assertFalse(filter.hides(DropTable.Kind.CURRENCY, null, "glimmer_orb", false));
        assertFalse(filter.hides(DropTable.Kind.SKILL_GEM, null, null, false));
        assertTrue(filter.hides(DropTable.Kind.FLASK, null, null, false), "標準：藥劑只撿比身上好的");
        assertFalse(filter.hides(DropTable.Kind.FLASK, null, null, true));
    }

    @Test
    void presetFiltersFollowConfigAndCustomKeepsItems() {
        assertEquals("p=standard;v=2", config.defaultFilter().encode()); // 2026-11-03：存檔格式 v=2
        assertEquals(config.defaultFilter(), LootFilter.decode("p=standard", config));
        assertEquals(config.defaultFilter(), LootFilter.decode(null, config), "沒設定過用預設");
        assertEquals(config.defaultFilter(), LootFilter.decode("p=gone", config), "組合被刪掉用預設");
        assertEquals(config.defaultFilter(), LootFilter.decode("亂寫", config));

        final LootFilter custom = config.defaultFilter();
        custom.toggleEquipment(Rarity.MAGIC);
        custom.toggleCurrency("glimmer_orb");
        custom.toggleCurrency("whetstone"); // 標準本來隱藏，改成顯示
        custom.toggleSupportGems();
        custom.cycleFlasks(); // better → none
        assertEquals(LootFilter.CUSTOM, custom.preset());
        final String text = custom.encode();
        assertEquals("p=custom;v=2;eq=normal,magic;cur=armour_scrap,glimmer_orb;sg=0;ug=1;fl=none;wl=0;al=0;lg=20;gl=0;gt=0;mr=0", text,
                "標準有等級差 20 的進階規則，自訂時一起記下來");
        final LootFilter back = LootFilter.decode(text, config);
        assertEquals(custom, back);
        assertTrue(back.hides(DropTable.Kind.SUPPORT_GEM, null, null, false));
        assertTrue(back.hides(DropTable.Kind.FLASK, null, null, true), "none：好的藥劑也隱藏");
        assertFalse(back.hidesCurrency("whetstone"));
        assertTrue(back.hiddenUniques().isEmpty());
        assertTrue(text.length() < 1024, "要放得進資料庫欄位");
    }

    /** 2026-11-03 照站長：傳奇照來源分組（追逐級、一般、事件、巔峰、終局・極境、劫盜）。 */
    @Test
    void uniqueGroupsFollowSources() {
        final Set<String> finals = Set.of("sky_crucible", "frost_throne", "bone_throne", "ember_citadel");
        final Set<String> pinnacles = Set.of("awakener", "shrine_warden", "strongbox_mimic", "breach_lord", "exile_king", "ritual_priest",
                "delirium_mirror", "abyss_lich");
        final java.util.function.Function<Set<String>, LootFilter.UniqueGroup> of = sources -> LootFilter.UniqueGroup.of(false, sources, finals, pinnacles);
        assertEquals(LootFilter.UniqueGroup.CHASE, LootFilter.UniqueGroup.of(true, Set.of("map_boss"), finals, pinnacles), "追逐級先看");
        assertEquals(LootFilter.UniqueGroup.COMMON, of.apply(Set.of("map_boss", "chance", "gamble")));
        assertEquals(LootFilter.UniqueGroup.COMMON, of.apply(Set.of("royal_tomb")), "某張地圖的 Boss");
        assertEquals(LootFilter.UniqueGroup.COMMON, of.apply(Set.of()), "禁止掉落的當一般");
        assertEquals(LootFilter.UniqueGroup.EVENT, of.apply(Set.of("shrine")));
        assertEquals(LootFilter.UniqueGroup.EVENT, of.apply(Set.of("delirium", "gamble")), "事件的優先於一般");
        assertEquals(LootFilter.UniqueGroup.PINNACLE, of.apply(Set.of("shrine_warden")));
        assertEquals(LootFilter.UniqueGroup.PINNACLE, of.apply(Set.of("awakener")));
        assertEquals(LootFilter.UniqueGroup.PINNACLE, of.apply(Set.of("abyss_whale")), "淵鯨也算巔峰");
        // 2026-11-06 照站長：三隻災厄級 Boss 的專屬傳奇也算巔峰專屬（這次之前被當成一般傳奇，關掉一般傳奇就一起不撿）
        for (final String boss : new String[]{"fog_trial", "ultimatum", "legion", "delve_boss"}) { // 2026-11-07：＋深坑首領
            assertEquals(LootFilter.UniqueGroup.PINNACLE, of.apply(Set.of(boss)), boss);
        }
        assertEquals(LootFilter.UniqueGroup.PINNACLE, of.apply(Set.of("world_boss")));
        assertEquals(LootFilter.UniqueGroup.ENDGAME, of.apply(Set.of("final")));
        assertEquals(LootFilter.UniqueGroup.ENDGAME, of.apply(Set.of("frost_throne")));
        assertEquals(LootFilter.UniqueGroup.ENDGAME, of.apply(Set.of("uber_frost_throne")));
        assertEquals(LootFilter.UniqueGroup.HEIST, of.apply(Set.of("heist")));
        assertEquals(LootFilter.UniqueGroup.ENDGAME, LootFilter.UniqueGroup.of(false, Set.of("final"), null, null), "沒給地圖代號也認得 final、uber_");
    }

    /** 2026-11-03：傳奇六組各自撿不撿；追逐級永遠撿；傳奇不看稀有度、基底、等級差；另外記，換預設組合也留著。 */
    @Test
    void uniqueGroupsCanBeHiddenButChaseAlwaysKept() {
        final LootFilter filter = config.defaultFilter();
        final LootFilter.Viewer viewer = new LootFilter.Viewer(70, Set.of(), Set.of());
        assertFalse(filter.hides(unique(LootFilter.UniqueGroup.COMMON, "armour", "body"), viewer), "預設傳奇會撿");
        final String presetBefore = filter.preset();
        filter.toggleUniques(LootFilter.UniqueGroup.COMMON);
        filter.toggleUniques(LootFilter.UniqueGroup.CHASE);
        assertTrue(filter.hidesUniques(LootFilter.UniqueGroup.COMMON));
        assertFalse(filter.hidesUniques(LootFilter.UniqueGroup.CHASE), "追逐級點了沒反應");
        assertEquals(presetBefore, filter.preset(), "傳奇分組是另外記的：預設組合不會變成自訂");
        assertTrue(filter.hides(unique(LootFilter.UniqueGroup.COMMON, "armour", "body"), viewer), "一般傳奇換成金幣");
        assertFalse(filter.hides(unique(LootFilter.UniqueGroup.CHASE, "armour", "body"), viewer), "追逐級永遠撿");
        assertFalse(filter.hides(unique(LootFilter.UniqueGroup.PINNACLE, "armour", "body"), viewer), "沒勾的組照撿");
        assertFalse(filter.hides(equipment(Rarity.UNIQUE, 68, "armour", "body", LootFilter.ALWAYS_KEEP_TAG), viewer), "舊記號當追逐級");
        assertTrue(filter.hides(equipment(Rarity.UNIQUE, 68, "armour", "body"), viewer), "不知道分組的當一般傳奇");
        // 傳奇不看基底、等級差、同類同系
        filter.setBaseRule("body_a", LootFilter.BaseRule.HIDE);
        filter.toggleWeaponsLikeMine();
        final LootFilter.Viewer archer = new LootFilter.Viewer(90, Set.of("weapon", "two_hand", "bow", "ranged"), Set.of());
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.UNIQUE, null, false, 10, Set.of("weapon", "one_hand", "sword"),
                0, 0, 0, "body_a", LootFilter.UniqueGroup.PINNACLE), archer), "傳奇只看分組");
        final LootFilter back = LootFilter.decode(filter.encode(), config);
        assertTrue(back.hidesUniques(LootFilter.UniqueGroup.COMMON), "存檔讀回來還是隱藏");
        assertTrue(filter.encode().contains(";hu=common"), filter.encode());
        // 換成預設組合：傳奇分組、基底帶過去，只記代號的存檔也記得
        final LootFilter preset = LootFilter.of(config.preset("strict")).withExtras(filter);
        assertTrue(preset.hidesUniques(LootFilter.UniqueGroup.COMMON));
        assertEquals(LootFilter.BaseRule.HIDE, preset.baseRule("body_a"));
        assertTrue(LootFilter.decode(preset.encode(), config).hidesUniques(LootFilter.UniqueGroup.COMMON), preset.encode());
        // 舊存檔的「傳奇隱藏」（eq 裡的 unique、hu=1）：換算成一般、事件、劫盜不撿（巔峰、終局以前就是永遠撿的）
        final LootFilter old = LootFilter.decode("p=standard;hu=1", config);
        assertTrue(old.needsMigration());
        old.migrate(List.of());
        assertFalse(old.needsMigration());
        assertEquals(Set.of(LootFilter.UniqueGroup.COMMON, LootFilter.UniqueGroup.EVENT, LootFilter.UniqueGroup.HEIST), old.hiddenUniques());
        final LootFilter oldCustom = LootFilter.decode("p=custom;eq=normal,unique;cur=;sg=0;ug=0;fl=all", config);
        oldCustom.migrate(List.of());
        assertTrue(oldCustom.hidesUniques(LootFilter.UniqueGroup.COMMON));
        assertTrue(oldCustom.hidesEquipment(Rarity.NORMAL));
        assertFalse(oldCustom.hidesEquipment(Rarity.UNIQUE), "傳奇不在稀有度那一組裡了");
        assertFalse(LootFilter.decode(oldCustom.encode(), config).needsMigration(), "存回去就是新格式");
    }

    private static LootFilter.Candidate unique(final LootFilter.UniqueGroup group, final String... tags) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.UNIQUE, null, false, 68, Set.of(tags), 0, 0, 0, null, group);
    }

    @Test
    void advancedRulesJudgeByWeaponArmourLevelGemAndMap() {
        final LootConfig.Preset strict = config.preset("strict");
        assertTrue(strict.rules().weaponsLikeMine());
        assertEquals(10, strict.rules().levelGap());
        assertEquals(10, strict.rules().minSkillGemLevel());
        assertEquals(2, strict.rules().minSupportTier());
        assertEquals(0, strict.rules().mapRarity());
        final LootFilter filter = LootFilter.of(strict);
        final LootFilter.Viewer archer = new LootFilter.Viewer(70, Set.of("weapon", "two_hand", "bow", "ranged"), Set.of("dex_armour"));
        // 稀有的弓撿；稀有的劍（跟手上不同類）不撿；盾（雙手武器）不撿；傳奇什麼都撿
        assertFalse(filter.hides(equipment(Rarity.RARE, 68, "weapon", "two_hand", "bow", "ranged"), archer));
        assertTrue(filter.hides(equipment(Rarity.RARE, 68, "weapon", "one_hand", "sword", "melee"), archer));
        assertTrue(filter.hides(equipment(Rarity.RARE, 68, "shield", "str_armour"), archer));
        assertFalse(filter.hides(equipment(Rarity.UNIQUE, 68, "weapon", "one_hand", "sword", "melee"), archer));
        // 沒拿武器：同類規則不管
        assertFalse(filter.hides(equipment(Rarity.RARE, 68, "weapon", "one_hand", "sword", "melee"), new LootFilter.Viewer(70, Set.of(), Set.of())));
        // 等級差 10：70 級的人，物品等級 59 以下的稀有不撿、60 撿；珠寶與地圖不看等級差
        assertTrue(filter.hides(equipment(Rarity.RARE, 59, "armour", "body", "dex_armour"), archer));
        assertFalse(filter.hides(equipment(Rarity.RARE, 60, "armour", "body", "dex_armour"), archer));
        assertTrue(filter.hides(equipment(Rarity.MAGIC, 20, "jewel", "ruby"), archer), "2026-11-03：珠寶併進基底，照稀有度（嚴格藏魔法）；這次之前珠寶永遠撿");
        assertFalse(filter.hides(equipment(Rarity.RARE, 20, "jewel", "ruby"), archer), "珠寶不看等級差");
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false, 61, Set.of("map", "map_t1"), 1, 0), archer),
                "地圖本來不藏");
        // 同系防具：嚴格沒開；自己開了之後智慧系的頭盔不撿、混系（含敏捷）撿
        final LootFilter mine = filter.copy();
        mine.toggleArmourLikeMine();
        assertTrue(mine.hides(equipment(Rarity.RARE, 70, "armour", "helmet", "int_armour"), archer));
        assertFalse(mine.hides(equipment(Rarity.RARE, 70, "armour", "helmet", "dex_armour", "int_armour"), archer));
        assertFalse(mine.hides(equipment(Rarity.RARE, 70, "armour", "helmet", "int_armour"), new LootFilter.Viewer(70, Set.of(), Set.of())), "沒穿防具不管");
        // 寶石：技能寶石 10 級以上才撿、輔助第 2 階以上才撿；不知道等級（0）就撿
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.SKILL_GEM, null, null, false, 0, Set.of(), 0, 4), archer));
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.SKILL_GEM, null, null, false, 0, Set.of(), 0, 10), archer));
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.SKILL_GEM, null, null, false, 0, Set.of(), 0, 0), archer));
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.SUPPORT_GEM, null, null, false, 0, Set.of(), 0, 1), archer));
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.SUPPORT_GEM, null, null, false, 0, Set.of(), 0, 2), archer));
        // 地圖依稀有度：開「隱藏普通（白）地圖」→ 白圖不撿、藍圖黃圖撿；再一次「只撿稀有」→ 藍圖也不撿；階級不管
        final LootFilter maps = filter.copy();
        maps.cycleMapRarity(false);
        assertEquals(1, maps.rules().mapRarity());
        assertTrue(maps.hides(map(Rarity.NORMAL, 16), archer), "白圖（就算是高階）不撿");
        assertFalse(maps.hides(map(Rarity.MAGIC, 1), archer));
        assertFalse(maps.hides(map(Rarity.RARE, 1), archer));
        assertFalse(maps.hides(map(Rarity.UNIQUE, 1), archer), "傳奇地圖永遠撿");
        maps.cycleMapRarity(false);
        assertEquals(2, maps.rules().mapRarity());
        assertTrue(maps.hides(map(Rarity.MAGIC, 16), archer));
        assertFalse(maps.hides(map(Rarity.RARE, 3), archer));
        assertTrue(maps.encode().endsWith(";mr=2"), maps.encode());
        assertEquals(maps, LootFilter.decode(maps.encode(), config));
        maps.cycleMapRarity(false);
        assertEquals(3, maps.rules().mapRarity(), "2026-10-24：全部隱藏");
        assertTrue(maps.hides(map(Rarity.RARE, 16), archer), "黃圖也不撿");
        assertFalse(maps.hides(map(Rarity.UNIQUE, 1), archer), "傳奇地圖照撿");
        assertEquals("全部隱藏", LootFilter.mapRarityName(3));
        maps.cycleMapRarity(false);
        assertEquals(0, maps.rules().mapRarity(), "繞一圈回到全部顯示");
        maps.cycleMapRarity(true);
        assertEquals(3, maps.rules().mapRarity(), "右鍵倒著走");
        // 舊存檔的地圖階級（mt=）已經不用：讀進來是關
        assertEquals(0, LootFilter.decode("p=custom;eq=;cur=;sg=0;ug=0;fl=all;wl=0;al=0;lg=0;gl=0;gt=0;mt=5", config).rules().mapRarity());
        assertEquals("全部顯示", LootFilter.mapRarityName(0));
        assertEquals(1, LootConfig.mapRarity("t", "normal"));
        assertEquals(2, LootConfig.mapRarity("t", "MAGIC"));
        assertEquals(0, LootConfig.mapRarity("t", "none"));
        assertEquals(3, LootConfig.mapRarity("t", "all"));
        assertThrows(IllegalArgumentException.class, () -> LootConfig.mapRarity("t", "rare"));
        // 存檔來回
        final String text = mine.encode();
        assertTrue(text.contains(";wl=1;al=1;lg=10;gl=10;gt=2;mr=0"), text);
        assertEquals(mine, LootFilter.decode(text, config));
        assertEquals(5, mine.activeRuleCount(), "嚴格的 4 項加上自己開的同系防具");
        // 全部顯示
        mine.showEverything();
        assertEquals(0, mine.activeRuleCount());
        assertFalse(mine.hides(equipment(Rarity.NORMAL, 1, "weapon", "one_hand", "sword", "melee"), archer));
        assertEquals("p=custom;v=2;eq=;cur=;sg=0;ug=0;fl=all", mine.encode(), "沒有規則就不多記");
        // 全部顯示也清掉基底、傳奇分組（2026-11-03）
        final LootFilter extra = LootFilter.decode("p=custom;v=2;eq=;cur=;sg=0;ug=0;fl=all;xb=some_base;ab=other_base;hu=common", config);
        assertEquals(2, extra.baseRules().size());
        assertEquals(1, extra.hiddenUniques().size());
        extra.showEverything();
        assertTrue(extra.baseRules().isEmpty());
        assertTrue(extra.hiddenUniques().isEmpty());
        // 2026-10：稀有掉落音效預設開；關掉的存檔來回、換預設組合都留著
        assertTrue(LootFilter.decode("p=standard", config).dropSounds());
        final LootFilter quiet = LootFilter.decode("p=standard;ds=0", config);
        assertFalse(quiet.dropSounds());
        assertEquals(quiet, LootFilter.decode(quiet.encode(), config));
        assertFalse(quiet.copy().dropSounds());
        // 舊存檔（沒有規則欄位）讀起來規則全關
        assertEquals(LootFilter.Rules.NONE, LootFilter.decode("p=custom;eq=normal;cur=;sg=0;ug=0;fl=all", config).rules());
        // 選項表
        assertEquals(10, LootFilter.next(LootFilter.LEVEL_GAPS, 0, false));
        assertEquals(30, LootFilter.next(LootFilter.LEVEL_GAPS, 0, true));
        assertEquals(0, LootFilter.next(LootFilter.LEVEL_GAPS, 30, false));
        assertEquals(0, LootFilter.next(LootFilter.LEVEL_GAPS, 7, false), "不在表裡從頭");
    }

    /** 保留有 T 幾以上詞綴的裝備（2026-10）：被稀有度、等級差、不同類藏起來的，有夠好的詞綴照樣撿。 */
    @Test
    void goodAffixTiersOnlyRescueRarityHiding() {
        final LootFilter filter = LootFilter.of(config.preset("strict")); // 藏普通、魔法；等級差 10；只撿同類武器
        final LootFilter.Viewer archer = new LootFilter.Viewer(70, Set.of("weapon", "two_hand", "bow", "ranged"), Set.of("dex_armour"));
        final LootFilter.Candidate magicT1 = equipment(Rarity.MAGIC, 70, 1, "armour", "helmet", "dex_armour");
        final LootFilter.Candidate magicT3 = equipment(Rarity.MAGIC, 70, 3, "armour", "helmet", "dex_armour");
        final LootFilter.Candidate swordT2 = equipment(Rarity.RARE, 70, 2, "weapon", "one_hand", "sword", "melee");
        final LootFilter.Candidate oldT1 = equipment(Rarity.RARE, 40, 1, "armour", "body", "dex_armour");
        assertTrue(filter.hides(magicT1, archer), "沒開：魔法照藏");
        final LootFilter keep = filter.copy();
        keep.cycleKeepAffixTier(false);
        assertEquals(1, keep.rules().keepAffixTier());
        assertEquals(LootFilter.CUSTOM, keep.preset(), "自己改過就是自訂");
        assertFalse(keep.hides(magicT1, archer), "有 T1：魔法也撿");
        assertTrue(keep.hides(magicT3, archer), "只有 T3：照藏");
        assertTrue(keep.hides(oldT1, archer), "2026-10 優先順序：等級差一定不撿，T1 也不救");
        assertTrue(keep.hides(swordT2, archer), "不同類的劍只有 T2：照藏");
        keep.cycleKeepAffixTier(false);
        keep.cycleKeepAffixTier(false);
        assertEquals(3, keep.rules().keepAffixTier());
        assertTrue(keep.hides(swordT2, archer), "不同類一定不撿，開到 T3 也不救");
        assertFalse(keep.hides(magicT3, archer));
        assertTrue(keep.hides(equipment(Rarity.NORMAL, 70, 0, "armour", "helmet", "dex_armour"), archer), "沒有詞綴的普通照藏");
        assertTrue(keep.encode().endsWith(";kt=3"), keep.encode());
        assertEquals(keep, LootFilter.decode(keep.encode(), config));
        assertEquals(5, keep.activeRuleCount(), "嚴格的 4 項加上保留詞綴階級");
        keep.cycleKeepAffixTier(true);
        assertEquals(2, keep.rules().keepAffixTier(), "右鍵倒著走");
        // 其他規則改了不會把它清掉
        keep.toggleArmourLikeMine();
        keep.cycleLevelGap(false);
        assertEquals(2, keep.rules().keepAffixTier());
        assertEquals(0, LootFilter.next(LootFilter.AFFIX_TIERS, 5, false), "T5 再下一個是關");
        assertEquals(0, LootFilter.decode("p=custom;eq=;cur=;sg=0;ug=0;fl=all;wl=0;al=0;lg=0;gl=0;gt=0;mr=0", config).rules().keepAffixTier(),
                "舊存檔沒有 kt：關");
    }

    /** 2026-11-03：舊存檔（只撿的基底、珠寶、遺時星晶、劫盜特殊基底）換算成基底規則；新角色劫盜特殊項鍊預設必撿。 */
    @Test
    void oldSavesMigrateIntoBaseRules() {
        final List<LootFilter.BaseInfo> all = List.of(
                new LootFilter.BaseInfo("long_bow", Set.of("weapon", "two_hand", "bow", "ranged"), false),
                new LootFilter.BaseInfo("short_bow", Set.of("weapon", "two_hand", "bow", "ranged"), false),
                new LootFilter.BaseInfo("iron_sword", Set.of("weapon", "one_hand", "sword", "melee"), false),
                new LootFilter.BaseInfo("leather_hood", Set.of("armour", "helmet", "dex_armour"), false),
                new LootFilter.BaseInfo("iron_ring", Set.of("jewellery", "ring"), false),
                new LootFilter.BaseInfo("heist_amulet", Set.of("jewellery", "amulet"), true),
                new LootFilter.BaseInfo("ruby_jewel", Set.of("jewel", "ruby"), false),
                new LootFilter.BaseInfo("diamond_jewel", Set.of("jewel", "ruby", "emerald", "sapphire"), false),
                new LootFilter.BaseInfo("tl_ruby", Set.of("jewel", "radius", "r_ruby"), false),
                new LootFilter.BaseInfo("tl_diamond", Set.of("jewel", "radius", "r_ruby", "r_emerald", "r_sapphire"), false),
                new LootFilter.BaseInfo("map_a", Set.of("map", "map_t1"), false));
        assertEquals(LootFilter.TimeLost.DIAMOND, LootFilter.TimeLost.of(Set.of("jewel", "radius", "r_ruby", "r_emerald", "r_sapphire")));
        assertNull(LootFilter.TimeLost.of(Set.of("jewel", "ruby")));
        // 只撿的基底（白名單）：有挑的那一類（武器）其他基底隱藏；防具、飾品、珠寶、地圖不動
        final LootFilter kept = LootFilter.decode("p=custom;eq=;cur=;sg=0;ug=0;fl=all;kb=long_bow", config);
        assertTrue(kept.needsMigration());
        kept.migrate(all);
        assertFalse(kept.needsMigration());
        assertEquals(LootFilter.BaseRule.SHOW, kept.baseRule("long_bow"));
        assertEquals(LootFilter.BaseRule.HIDE, kept.baseRule("short_bow"), "同一類沒挑的隱藏");
        assertEquals(LootFilter.BaseRule.HIDE, kept.baseRule("iron_sword"));
        assertEquals(LootFilter.BaseRule.SHOW, kept.baseRule("leather_hood"), "沒挑的類別不動");
        assertEquals(LootFilter.BaseRule.SHOW, kept.baseRule("iron_ring"));
        assertEquals(LootFilter.BaseRule.SHOW, kept.baseRule("ruby_jewel"));
        assertEquals(LootFilter.BaseRule.ALWAYS, kept.baseRule("heist_amulet"), "劫盜特殊基底沒寫 = 一定撿 → 必撿");
        assertEquals(LootFilter.BaseRule.SHOW, kept.baseRule("map_a"));
        assertTrue(kept.encode().contains(";xb=iron_sword,short_bow;ab=heist_amulet"), kept.encode());
        assertFalse(LootFilter.decode(kept.encode(), config).needsMigration());
        assertEquals(kept, LootFilter.decode(kept.encode(), config));
        // 珠寶全部不撿 + 遺時星晶全部不撿 + 劫盜特殊基底不撿
        final LootFilter jewels = LootFilter.decode("p=standard;jr=3;tr=2;hb=hide", config);
        jewels.migrate(all);
        assertEquals("standard", jewels.preset());
        assertEquals(LootFilter.BaseRule.HIDE, jewels.baseRule("ruby_jewel"));
        assertEquals(LootFilter.BaseRule.HIDE, jewels.baseRule("diamond_jewel"));
        assertEquals(LootFilter.BaseRule.HIDE, jewels.baseRule("tl_ruby"));
        assertEquals(LootFilter.BaseRule.HIDE, jewels.baseRule("tl_diamond"));
        assertEquals(LootFilter.BaseRule.HIDE, jewels.baseRule("heist_amulet"));
        assertEquals(LootFilter.BaseRule.SHOW, jewels.baseRule("long_bow"));
        // 只撿稀有珠寶（沒有對應的：顯示、照稀有度）；不撿赤的遺時星晶；劫盜特殊基底照一般規則
        final LootFilter some = LootFilter.decode("p=standard;jr=2;th=ruby;hb=normal", config);
        some.migrate(all);
        assertEquals(LootFilter.BaseRule.SHOW, some.baseRule("ruby_jewel"));
        assertEquals(LootFilter.BaseRule.HIDE, some.baseRule("tl_ruby"));
        assertEquals(LootFilter.BaseRule.SHOW, some.baseRule("tl_diamond"));
        assertEquals(LootFilter.BaseRule.SHOW, some.baseRule("heist_amulet"));
        // 新角色（沒存過）：劫盜特殊項鍊預設必撿，其他顯示
        final LootFilter fresh = LootFilter.decode(null, config);
        assertTrue(fresh.needsMigration());
        fresh.migrate(all);
        assertEquals(LootFilter.BaseRule.ALWAYS, fresh.baseRule("heist_amulet"));
        assertEquals(1, fresh.baseRules().size());
        assertEquals("standard", fresh.preset());
        // 新格式不會再換算；新格式裡已經設定的基底不會被舊鍵蓋掉
        final LootFilter mixed = LootFilter.decode("p=standard;v=2;xb=long_bow;kb=long_bow", config);
        assertFalse(mixed.needsMigration());
        mixed.migrate(all);
        assertEquals(LootFilter.BaseRule.HIDE, mixed.baseRule("long_bow"));
        assertEquals(LootFilter.BaseRule.SHOW, mixed.baseRule("heist_amulet"), "新格式：沒設定就是顯示");
        // 換預設組合時還沒換算的部分也帶過去
        final LootFilter pending = LootFilter.decode("p=standard;kb=long_bow", config);
        final LootFilter switched = LootFilter.of(config.preset("strict")).withExtras(pending);
        assertTrue(switched.needsMigration());
        switched.migrate(all);
        assertEquals(LootFilter.BaseRule.HIDE, switched.baseRule("short_bow"));
    }

    /** 2026-11-03 照站長：隱藏的基底絕對不撿（T1 也不撿）、必撿的不看其他規則、顯示的照其他規則；權杖與錘分開；護符與好詞綴同類換預設組合也留著。 */
    @Test
    void baseRulesComeFirstAndExtrasSurvivePresets() {
        final LootFilter filter = new LootFilter();
        filter.cycleKeepAffixTier(false); // T1
        filter.toggleEquipment(Rarity.RARE);
        filter.setBaseRule("short_bow", LootFilter.BaseRule.HIDE);
        filter.setBaseRule("gold_ring", LootFilter.BaseRule.ALWAYS);
        final LootFilter.Viewer nobody = LootFilter.Viewer.NONE;
        final String[] bow = {"weapon", "two_hand", "bow", "ranged"};
        assertTrue(filter.hides(ranked(Rarity.RARE, "short_bow", 1, bow), nobody), "隱藏的基底：有 T1 也不撿");
        assertTrue(filter.hides(ranked(Rarity.NORMAL, "short_bow", 0, bow), nobody));
        assertTrue(filter.hides(ranked(Rarity.RARE, "long_bow", 3, bow), nobody), "顯示的基底：照稀有度（稀有藏、只有 T3 不救）");
        assertFalse(filter.hides(ranked(Rarity.RARE, "long_bow", 1, bow), nobody), "顯示的基底：有 T1 救回來");
        assertFalse(filter.hides(ranked(Rarity.NORMAL, "long_bow", 0, bow), nobody), "普通沒藏：撿");
        assertTrue(filter.hides(ranked(Rarity.RARE, "gold_ring", 5, "jewellery", "ring"), nobody), "必撿：門檻 T1、只有 T5 不撿");
        assertTrue(filter.hides(ranked(Rarity.NORMAL, "gold_ring", 0, "jewellery", "ring"), nobody), "必撿：門檻開著、沒有詞綴不撿");
        assertFalse(filter.hides(ranked(Rarity.RARE, "gold_ring", 1, "jewellery", "ring"), nobody), "必撿：有 T1 一律撿（稀有藏了也撿）");
        final LootFilter noTier = filter.copy();
        noTier.cycleKeepAffixTier(true); // T1 → 關
        assertFalse(noTier.hides(ranked(Rarity.RARE, "gold_ring", 5, "jewellery", "ring"), nobody), "必撿：門檻關就全部撿");
        assertFalse(noTier.hides(ranked(Rarity.NORMAL, "gold_ring", 0, "jewellery", "ring"), nobody));
        filter.setBaseRule("map_c", LootFilter.BaseRule.ALWAYS);
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false, 70, Set.of("map", "map_t5"), 5, 0, 0, "map_c"),
                nobody), "必撿的地圖不看詞綴");
        // 必撿也不看等級差、同類同系；隱藏的基底跟珠寶、地圖一樣適用
        filter.toggleWeaponsLikeMine();
        filter.cycleLevelGap(false);
        filter.setBaseRule("iron_sword", LootFilter.BaseRule.ALWAYS);
        filter.setBaseRule("ruby_jewel", LootFilter.BaseRule.HIDE);
        filter.setBaseRule("map_a", LootFilter.BaseRule.HIDE);
        final LootFilter.Viewer archer = new LootFilter.Viewer(90, Set.of("weapon", "two_hand", "bow", "ranged"), Set.of());
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false, 10, Set.of("weapon", "one_hand", "sword"),
                0, 0, 1, "iron_sword"), archer), "必撿：有 T1 的話不同類、等級差太多也撿");
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false, 10, Set.of("weapon", "one_hand", "sword"),
                0, 0, 2, "iron_sword"), archer), "必撿：只有 T2 不撿");
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, null, false, 10, Set.of("weapon", "one_hand", "sword"),
                0, 0, 0, "other_sword"), archer), "顯示：不同類不撿");
        assertTrue(filter.hides(ranked(Rarity.RARE, "ruby_jewel", 1, "jewel", "ruby"), nobody), "隱藏的珠寶：T1 也不撿");
        assertFalse(filter.hides(ranked(Rarity.MAGIC, "emerald_jewel", 1, "jewel", "emerald"), nobody), "顯示的珠寶：照稀有度（魔法沒藏）");
        assertTrue(filter.hides(ranked(Rarity.RARE, "emerald_jewel", 3, "jewel", "emerald"), nobody), "顯示的珠寶：稀有藏了、T3 不救");
        assertFalse(filter.hides(ranked(Rarity.MAGIC, "emerald_jewel", 1, "jewel", "emerald"), archer), "珠寶不看等級差、同類");
        assertTrue(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.RARE, null, false, 70, Set.of("map", "map_t5"), 5, 0, 0, "map_a"),
                nobody), "隱藏的地圖整張不撿");
        assertFalse(filter.hides(new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, Rarity.RARE, null, false, 70, Set.of("map", "map_t5"), 5, 0, 0, "map_b"),
                nobody), "顯示的地圖照地圖的稀有度規則（關著）");
        // 基底是另外記的：改了不會變成自訂；存檔來回；全部清掉
        final LootFilter std = LootFilter.decode("p=standard;v=2", config);
        std.toggleBaseHidden("short_bow");
        std.toggleBaseAlways("gold_ring");
        assertEquals("standard", std.preset());
        assertEquals(LootFilter.BaseRule.HIDE, std.baseRule("short_bow"));
        assertEquals(LootFilter.BaseRule.ALWAYS, std.baseRule("gold_ring"));
        std.toggleBaseAlways("short_bow");
        assertEquals(LootFilter.BaseRule.ALWAYS, std.baseRule("short_bow"), "隱藏的右鍵變必撿");
        std.toggleBaseHidden("short_bow");
        assertEquals(LootFilter.BaseRule.HIDE, std.baseRule("short_bow"), "必撿的左鍵變隱藏");
        std.toggleBaseHidden("short_bow");
        assertEquals(LootFilter.BaseRule.SHOW, std.baseRule("short_bow"));
        assertEquals(Set.of(), std.hiddenBases());
        assertEquals(Set.of("gold_ring", "jamulet_absent"), std.alwaysBases()); // 2026-11-06：空無墜飾預設必撿
        assertEquals("p=standard;v=2;ab=gold_ring", std.encode());
        assertEquals(std, LootFilter.decode(std.encode(), config));
        std.clearBaseRules();
        assertTrue(std.baseRules().isEmpty());
        assertEquals("jewel", LootFilter.gearCategory(Set.of("jewel", "radius")));
        assertEquals("map", LootFilter.gearCategory(Set.of("map")));
        assertEquals("offhand", LootFilter.gearCategory(Set.of("quiver")));

        // 權杖也有錘的標籤：拿權杖不撿單手錘、拿單手錘不撿權杖
        final LootFilter.Viewer sceptre = new LootFilter.Viewer(70, Set.of("weapon", "one_hand", "mace", "sceptre", "melee"), Set.of());
        final LootFilter.Viewer mace = new LootFilter.Viewer(70, Set.of("weapon", "one_hand", "mace", "melee", "melee_1h"), Set.of());
        final Set<String> maceTags = Set.of("weapon", "one_hand", "mace", "melee", "melee_1h");
        final Set<String> sceptreTags = Set.of("weapon", "one_hand", "mace", "sceptre", "melee");
        assertTrue(LootFilter.unlikeMine(maceTags, sceptre, true, false));
        assertTrue(LootFilter.unlikeMine(sceptreTags, mace, true, false));
        assertFalse(LootFilter.unlikeMine(sceptreTags, sceptre, true, false));
        assertFalse(LootFilter.unlikeMine(maceTags, mace, true, false));

        // 護符、好詞綴也只撿同類：不會變成自訂，存檔來回、換預設組合都留著
        final LootFilter standard = LootFilter.decode("p=standard", config);
        standard.toggleCharms();
        standard.toggleKeepAffixLikeMine();
        assertEquals("standard", standard.preset());
        final LootFilter again = LootFilter.decode(standard.encode(), config);
        assertTrue(again.hidesCharms());
        assertTrue(again.keepAffixLikeMine());
        final LootFilter strict = LootFilter.of(config.preset("strict")).withExtras(again);
        assertTrue(strict.hidesCharms());
        assertTrue(strict.keepAffixLikeMine());
        final LootFilter custom = LootFilter.decode("p=custom;eq=;cur=;sg=0;ch=1;kl=1;ug=0;fl=all", config);
        assertTrue(custom.hidesCharms(), "舊的自訂存檔照樣讀得到");
        assertEquals(custom, LootFilter.decode(custom.encode(), config));
        assertTrue(custom.needsMigration(), "舊格式讀進來要換算");
        assertFalse(LootFilter.decode(custom.encode(), config).needsMigration(), "存回去就是新格式");
    }

    private static LootFilter.Candidate ranked(final Rarity rarity, final String baseId, final int bestRank, final String... tags) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, rarity, null, false, 70, Set.of(tags), 0, 0, bestRank, baseId);
    }

    private static LootFilter.Candidate base(final Rarity rarity, final String baseId, final String... tags) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, rarity, null, false, 70, Set.of(tags), 0, 0, 0, baseId);
    }

    private static LootFilter.Candidate equipment(final Rarity rarity, final int itemLevel, final int bestRank, final String... tags) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, rarity, null, false, itemLevel, Set.of(tags), 0, 0, bestRank);
    }

    private static LootFilter.Candidate equipment(final Rarity rarity, final int itemLevel, final String... tags) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, rarity, null, false, itemLevel, Set.of(tags), 0, 0);
    }

    /** 一張地圖的掉落（階級 tier，物品等級照 T1 = 61 大概算）。 */
    private static LootFilter.Candidate map(final Rarity rarity, final int tier) {
        return new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, rarity, null, false, 60 + tier, Set.of("map", "map_t" + Math.min(5, tier)), tier, 0);
    }

    @Test
    void filteredItemsTurnIntoGold() {
        assertEquals(4.0, drops.averageGold(1), 1e-9);
        assertEquals(14.0, drops.averageGold(10), 1e-9);
        final double gold = drops.averageGold(10);
        assertEquals(1, config.goldValue(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, 0, gold), "14 × 0.075（2026-10-24 全部減半）");
        assertEquals(2, config.goldValue(DropTable.Kind.EQUIPMENT, Rarity.MAGIC, 0, gold), "14 × 0.15");
        assertEquals(1, config.goldValue(DropTable.Kind.CURRENCY, null, 1500, gold), "礪鋒石：14 × 150/1500");
        assertEquals(2, config.goldValue(DropTable.Kind.CURRENCY, null, 1000, gold), "至臻石：14 × 150/1000");
        assertEquals(70, config.goldValue(DropTable.Kind.CURRENCY, null, 15, gold), "天啟石：倍率上限 5");
        assertEquals(1, config.goldValue(DropTable.Kind.CURRENCY, null, 3000, 1.0), "至少 1");
        assertEquals(2, config.goldValue(DropTable.Kind.FLASK, null, 0, gold), "14 × 0.15");
    }

    @Test
    void notableDrops() {
        assertTrue(config.notable(DropTable.Kind.EQUIPMENT, Rarity.RARE, 0));
        assertFalse(config.notable(DropTable.Kind.EQUIPMENT, Rarity.MAGIC, 0));
        assertTrue(config.notable(DropTable.Kind.CURRENCY, null, 1000, "zenith_orb"), "至臻石");
        assertTrue(config.notable(DropTable.Kind.CURRENCY, null, 570, "bounty_orb"), "豐饒石");
        // 2026-10 礪鋒石、甲冑殘片的權重減半成 750（比至臻石低）：靠 quiet_currencies 不提示
        assertFalse(config.notable(DropTable.Kind.CURRENCY, null, 750, "whetstone"), "礪鋒石");
        assertFalse(config.notable(DropTable.Kind.CURRENCY, null, 750, "armour_scrap"), "甲冑殘片");
        assertFalse(config.notable(DropTable.Kind.CURRENCY, null, 1500, "glimmer_orb"), "微光石");
        assertFalse(config.notable(DropTable.Kind.CURRENCY, null, 1250, "lustre_orb"), "添輝石");
        assertTrue(config.notable(DropTable.Kind.CURRENCY, null, 750, null), "沒給代號只看權重");
        assertTrue(config.mentionedCurrencies().contains("lustre_orb"), "quiet_currencies 也要檢查代號拼對了沒");
        assertTrue(config.notable(DropTable.Kind.SKILL_GEM, null, 0));
        assertFalse(config.notable(DropTable.Kind.FLASK, null, 0));
    }

    @Test
    void unlikeMineIsSharedByTheFilterAndTheGambleTab() {
        final LootFilter.Viewer bowman = new LootFilter.Viewer(40, Set.of("weapon", "two_hand", "bow", "ranged"), Set.of("dex_armour"));
        assertFalse(LootFilter.unlikeMine(Set.of("weapon", "two_hand", "bow", "ranged"), bowman, true, true), "弓對弓");
        assertTrue(LootFilter.unlikeMine(Set.of("weapon", "one_hand", "sword", "melee"), bowman, true, true), "劍跟弓不同類");
        assertTrue(LootFilter.unlikeMine(Set.of("shield", "armour", "str_armour"), bowman, true, false), "雙手武器的人不列盾");
        assertTrue(LootFilter.unlikeMine(Set.of("armour", "str_armour"), bowman, false, true), "力量系防具跟敏捷系不同系");
        assertFalse(LootFilter.unlikeMine(Set.of("armour", "dex_armour"), bowman, true, true));
        assertFalse(LootFilter.unlikeMine(Set.of("ring", "jewellery"), bowman, true, true), "飾品不分類");
        // 箭袋只有拿弓的人能裝；獸魂符是自己一類的雙手武器
        assertFalse(LootFilter.unlikeMine(Set.of("quiver"), bowman, true, true), "拿弓的人撿箭袋");
        final LootFilter.Viewer druid = new LootFilter.Viewer(50, Set.of("weapon", "two_hand", "talisman", "melee", "melee_2h"), Set.of());
        assertTrue(LootFilter.unlikeMine(Set.of("quiver"), druid, true, true), "沒拿弓的人不撿箭袋");
        assertFalse(LootFilter.unlikeMine(Set.of("quiver"), druid, false, true), "沒開「同類的武器」就不管");
        assertFalse(LootFilter.unlikeMine(Set.of("weapon", "two_hand", "talisman", "melee", "melee_2h"), druid, true, true), "獸魂符對獸魂符");
        assertTrue(LootFilter.unlikeMine(Set.of("weapon", "two_hand", "mace", "melee", "melee_2h"), druid, true, true), "拿獸魂符的人不撿巨錘");
        assertTrue(LootFilter.unlikeMine(Set.of("weapon", "two_hand", "talisman", "melee", "melee_2h"), bowman, true, true), "拿弓的人不撿獸魂符");
        assertTrue(LootFilter.unlikeMine(Set.of("shield", "int_armour", "int_only", "focus"), druid, true, false), "雙手的獸魂符用不到法器");
        final LootFilter.Viewer crossbow = new LootFilter.Viewer(50, Set.of("weapon", "two_hand", "crossbow", "ranged"), Set.of());
        assertTrue(LootFilter.unlikeMine(Set.of("quiver"), crossbow, true, true), "弩不能裝箭袋");
        assertFalse(LootFilter.unlikeMine(Set.of("quiver"), new LootFilter.Viewer(50, Set.of(), Set.of()), true, true), "沒拿武器：不管");
        // 2026-10：同種類但單手／雙手不同也算不同類（說明寫「拿雙手劍就只撿雙手劍」）
        final LootFilter.Viewer bigMace = new LootFilter.Viewer(50, Set.of("weapon", "two_hand", "mace", "melee", "melee_2h"), Set.of());
        assertTrue(LootFilter.unlikeMine(Set.of("weapon", "one_hand", "mace", "melee"), bigMace, true, true), "拿雙手錘不撿單手錘");
        assertFalse(LootFilter.unlikeMine(Set.of("weapon", "two_hand", "mace", "melee", "melee_2h"), bigMace, true, true), "雙手錘對雙手錘");
        assertFalse(LootFilter.unlikeMine(Set.of("weapon", "one_hand", "sword", "melee"), bowman, false, true), "不看武器就不藏");
        final LootFilter.Viewer bare = new LootFilter.Viewer(40, Set.of(), Set.of());
        assertFalse(LootFilter.unlikeMine(Set.of("weapon", "one_hand", "sword", "melee"), bare, true, true), "沒拿武器全列");
        assertFalse(LootFilter.unlikeMine(Set.of("armour", "str_armour"), bare, true, true), "沒穿防具全列");
        assertEquals("弓", LootFilter.weaponWord("bow"));
        assertEquals("長棍", LootFilter.weaponWord("quarterstaff"));
        assertEquals("智慧系", LootFilter.familyWord("int_armour"));
        assertEquals("odd", LootFilter.weaponWord("odd"), "不認得的照標籤");
    }

    @Test
    void currencyPouch() {
        final CurrencyPouch pouch = new CurrencyPouch();
        assertEquals(30, pouch.deposit("whetstone", 30));
        assertEquals(CurrencyPouch.CAP - 30, pouch.deposit("whetstone", 99_999), "超過上限的不收");
        assertEquals(0, pouch.deposit("whetstone", 1));
        assertEquals(12, pouch.deposit("glimmer_orb", 12));
        assertEquals(10, pouch.withdraw("glimmer_orb", 10));
        assertEquals(2, pouch.withdraw("glimmer_orb", 10), "只拿得到剩下的");
        assertEquals(0, pouch.amount("glimmer_orb"));
        assertEquals(1, pouch.kinds(), "拿光的通貨不留空的一項");
        pouch.deposit("annul_orb", 3);
        assertEquals("annul_orb=3,whetstone=" + CurrencyPouch.CAP, pouch.encode(), "照代號排序");
        final CurrencyPouch back = CurrencyPouch.decode(pouch.encode() + ",壞掉,x=abc,=5");
        assertEquals(pouch.all(), back.all(), "寫壞的項目略過");
        assertEquals(0, CurrencyPouch.decode(null).kinds());
        // 2026-10：上限可以靠贊助加大，讀檔時不裁掉超過的，只是不能再存入
        final CurrencyPouch big = CurrencyPouch.decode("zenith_orb=999999");
        assertEquals(999_999, big.amount("zenith_orb"), "讀進來不裁掉");
        assertEquals(0, big.deposit("zenith_orb", 1), "已經超過上限就不再收");
    }

}
