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
        assertEquals("p=standard", live.defaultFilter().encode());
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
        assertEquals("p=standard", config.defaultFilter().encode());
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
        custom.toggleEquipment(Rarity.UNIQUE); // 傳奇不能隱藏
        assertEquals(LootFilter.CUSTOM, custom.preset());
        final String text = custom.encode();
        assertEquals("p=custom;eq=normal,magic;cur=armour_scrap,glimmer_orb;sg=0;ug=1;fl=none;wl=0;al=0;lg=20;gl=0;gt=0;mr=0", text,
                "標準有等級差 20 的進階規則，自訂時一起記下來");
        final LootFilter back = LootFilter.decode(text, config);
        assertEquals(custom, back);
        assertTrue(back.hides(DropTable.Kind.SUPPORT_GEM, null, null, false));
        assertTrue(back.hides(DropTable.Kind.FLASK, null, null, true), "none：好的藥劑也隱藏");
        assertFalse(back.hidesCurrency("whetstone"));
        assertFalse(back.hidesEquipment(Rarity.UNIQUE));
        assertTrue(text.length() < 1024, "要放得進資料庫欄位");
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
        assertFalse(filter.hides(equipment(Rarity.MAGIC, 20, "jewel", "ruby"), archer), "珠寶永遠撿");
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
        assertEquals(0, maps.rules().mapRarity(), "繞一圈回到全部顯示");
        maps.cycleMapRarity(true);
        assertEquals(2, maps.rules().mapRarity(), "右鍵倒著走");
        // 舊存檔的地圖階級（mt=）已經不用：讀進來是關
        assertEquals(0, LootFilter.decode("p=custom;eq=;cur=;sg=0;ug=0;fl=all;wl=0;al=0;lg=0;gl=0;gt=0;mt=5", config).rules().mapRarity());
        assertEquals("全部顯示", LootFilter.mapRarityName(0));
        assertEquals(1, LootConfig.mapRarity("t", "normal"));
        assertEquals(2, LootConfig.mapRarity("t", "MAGIC"));
        assertEquals(0, LootConfig.mapRarity("t", "none"));
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
        assertEquals("p=custom;eq=;cur=;sg=0;ug=0;fl=all", mine.encode(), "沒有規則就不多記");
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
    void goodAffixTiersAreKeptEvenWhenOtherRulesHide() {
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
        assertFalse(keep.hides(oldT1, archer), "等級差要藏的，有 T1 也撿");
        assertTrue(keep.hides(swordT2, archer), "不同類的劍只有 T2：照藏");
        keep.cycleKeepAffixTier(false);
        keep.cycleKeepAffixTier(false);
        assertEquals(3, keep.rules().keepAffixTier());
        assertFalse(keep.hides(swordT2, archer), "開到 T3：T2 的劍也撿");
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

    /** 遺時星晶（2026-10）：自己的過濾設定（種類、稀有度），跟一般珠寶分開；存檔讀得回來。 */
    @Test
    void timeLostJewelsHaveTheirOwnFilter() {
        final LootFilter.Viewer viewer = new LootFilter.Viewer(80, Set.of(), Set.of());
        final LootFilter.Candidate ruby = equipment(Rarity.MAGIC, 70, "jewel", "radius", "r_ruby");
        final LootFilter.Candidate rareRuby = equipment(Rarity.RARE, 70, "jewel", "radius", "r_ruby");
        final LootFilter.Candidate diamond = equipment(Rarity.RARE, 75, "jewel", "radius", "r_ruby", "r_emerald", "r_sapphire");
        final LootFilter.Candidate plain = equipment(Rarity.MAGIC, 70, "jewel", "ruby");
        assertEquals(LootFilter.TimeLost.RUBY, LootFilter.TimeLost.of(Set.of("jewel", "radius", "r_ruby")));
        assertEquals(LootFilter.TimeLost.EMERALD, LootFilter.TimeLost.of(Set.of("jewel", "radius", "r_emerald")));
        assertEquals(LootFilter.TimeLost.SAPPHIRE, LootFilter.TimeLost.of(Set.of("jewel", "radius", "r_sapphire")));
        assertEquals(LootFilter.TimeLost.DIAMOND, LootFilter.TimeLost.of(Set.of("jewel", "radius", "r_ruby", "r_emerald", "r_sapphire")));
        assertNull(LootFilter.TimeLost.of(Set.of("jewel", "ruby")));

        final LootFilter filter = config.defaultFilter().copy();
        assertFalse(filter.hides(ruby, viewer));
        assertFalse(filter.hides(diamond, viewer));
        // 一般珠寶全部不撿：遺時星晶不受影響
        filter.withJewelRarity(3);
        assertTrue(filter.hides(plain, viewer));
        assertFalse(filter.hides(ruby, viewer), "遺時星晶不看一般珠寶的設定");
        // 只撿稀有的
        filter.cycleTimeLostRarity(false);
        assertEquals(1, filter.timeLostRarity());
        assertTrue(filter.hides(ruby, viewer));
        assertFalse(filter.hides(rareRuby, viewer));
        assertFalse(filter.hides(diamond, viewer));
        // 不撿赤的：稀有的赤也不撿，白的照撿
        filter.toggleTimeLost(LootFilter.TimeLost.RUBY);
        assertTrue(filter.hides(rareRuby, viewer));
        assertFalse(filter.hides(diamond, viewer));
        assertFalse(filter.hides(equipment(Rarity.UNIQUE, 70, "jewel", "radius", "r_ruby"), viewer), "傳奇永遠撿");
        // 存檔
        final LootFilter back = LootFilter.decode(filter.encode(), config);
        assertEquals(filter, back);
        assertEquals(1, back.timeLostRarity());
        assertTrue(back.hidesTimeLost(LootFilter.TimeLost.RUBY));
        assertFalse(back.hidesTimeLost(LootFilter.TimeLost.DIAMOND));
        // 全部不撿
        filter.cycleTimeLostRarity(false);
        assertTrue(filter.hides(diamond, viewer));
        assertEquals(config.defaultFilter(), LootFilter.decode("p=standard", config), "沒設定的照舊");
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
        assertEquals(2, config.goldValue(DropTable.Kind.EQUIPMENT, Rarity.NORMAL, 0, gold), "14 × 0.15");
        assertEquals(4, config.goldValue(DropTable.Kind.EQUIPMENT, Rarity.MAGIC, 0, gold), "14 × 0.3");
        assertEquals(3, config.goldValue(DropTable.Kind.CURRENCY, null, 1500, gold), "礪鋒石：14 × 300/1500");
        assertEquals(4, config.goldValue(DropTable.Kind.CURRENCY, null, 1000, gold), "至臻石：14 × 300/1000");
        assertEquals(70, config.goldValue(DropTable.Kind.CURRENCY, null, 15, gold), "天啟石：倍率上限 5");
        assertEquals(1, config.goldValue(DropTable.Kind.CURRENCY, null, 3000, 1.0), "至少 1");
        assertEquals(4, config.goldValue(DropTable.Kind.FLASK, null, 0, gold), "14 × 0.3");
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
