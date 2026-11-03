package tw.exilecore.loot;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 掉落過濾器（/filter）。2026-10 重新設計成統一版面：最上面四個分頁（總覽、裝備、寶石與藥劑、通貨），
 * 每一頁的每一排最左邊是這一排的標籤，右邊是可以點的項目；最下面一排是返回、說明、關閉。
 * <p>
 * 每個項目名字前面寫「✔ 會撿」或「✘ 隱藏」（套用資源包的人看到綠框、紅框），說明寫點了會變成什麼；
 * 每點一下馬上生效，關閉時存檔。
 */
public final class LootFilterMenu extends Menu {

    /** 分頁。 */
    private enum Tab {
        OVERVIEW("總覽", Material.BOOK, "預設組合、目前會藏什麼、全部顯示"),
        EQUIPMENT("裝備", Material.IRON_CHESTPLATE, "依稀有度、好詞綴、等級差、同類武器、同系防具、地圖、傳奇分組、基底清單"),
        GEMS("寶石與藥劑", Material.AMETHYST_SHARD, "未切割技能寶石、輔助寶石、藥劑"),
        CURRENCY("通貨", Material.SUNFLOWER, "照價值分三組，每一種都可以單獨切換");

        private final String title;
        private final Material icon;
        private final String about;

        Tab(final String title, final Material icon, final String about) {
            this.title = title;
            this.icon = icon;
            this.about = about;
        }
    }

    // ---- 總覽 ----
    private static final int ROW_PRESETS = 1;
    private static final int ROW_SUMMARY = 2;
    private static final int ROW_TOOLS = 3;
    private static final int SLOT_SHOW_ALL = slot(ROW_TOOLS, 1);
    private static final int SLOT_STATS = slot(ROW_TOOLS, 2);
    private static final int SLOT_SOUNDS = slot(ROW_TOOLS, 3);
    // ---- 裝備（2026-11-03 照站長重排：稀有度與好詞綴一排、進階規則一排、傳奇分組一排、基底一排）----
    private static final Map<Integer, Rarity> RARITY_SLOTS = Map.of(slot(1, 1), Rarity.NORMAL, slot(1, 2), Rarity.MAGIC, slot(1, 3), Rarity.RARE);
    private static final int SLOT_KEEP_AFFIX_TIER = slot(1, 5);
    /** 好詞綴也只撿同類（2026-10）。 */
    private static final int SLOT_KEEP_AFFIX_LIKE = slot(1, 6);
    private static final int SLOT_LEVEL_GAP = slot(2, 1);
    private static final int SLOT_WEAPONS_LIKE_MINE = slot(2, 2);
    private static final int SLOT_ARMOUR_LIKE_MINE = slot(2, 3);
    private static final int SLOT_MAPS = slot(2, 5);
    /** 傳奇分組（2026-11-03）：六格，照 UniqueGroup 的順序。 */
    private static final int SLOT_UNIQUE_GROUPS = slot(3, 1);
    /** 基底清單（2026-11-03：取代只撿的基底、珠寶、遺時星晶、劫盜特殊基底）。 */
    private static final int SLOT_BASES = slot(4, 1);
    /** 基底清單的捷徑：直接打開某一類。 */
    private static final int SLOT_BASES_JEWELS = slot(4, 3);
    private static final int SLOT_BASES_MAPS = slot(4, 4);
    private static final int SLOT_BASES_HIDDEN = slot(4, 5);
    // ---- 寶石與藥劑 ----
    private static final int SLOT_SKILL_GEMS = slot(1, 1);
    private static final int SLOT_SKILL_GEM_LEVEL = slot(1, 2);
    private static final int SLOT_SUPPORT_GEMS = slot(2, 1);
    private static final int SLOT_SUPPORT_TIER = slot(2, 2);
    private static final int SLOT_FLASKS = slot(3, 1);
    /** 護符（2026-10）。 */
    private static final int SLOT_CHARMS = slot(3, 2);
    // ---- 通貨：三組，標籤在最左邊（右鍵整組切換），後面 8 格是通貨；一組超過 8 種就往下多佔一排（2026-10）；
    //      四排放不下時一組接著一組排、標籤跟在上一組後面（2026-10-23） ----
    // 2026-10-30 照站長「通貨分區要更明確」：符文另外照階級分四組（以前符文沒有參考價值，連完美符文都被歸到低價通貨）；
    //   沒有參考價值的其他通貨（星諭牌堆）放中價
    private static final int GROUP_COUNT = 7;
    private static final int GROUP_SIZE = 8;
    /** 通貨可以用的排（第 1～4 排；第 5 排是下面的按鈕）。 */
    private static final int FIRST_GROUP_ROW = 1;
    private static final int LAST_GROUP_ROW = 4;
    private static final String[] GROUP_NAMES = {"低價通貨", "中價通貨", "高價通貨", "次級符文", "符文", "高階符文", "完美符文"};
    private static final String[] GROUP_HINTS = {"微光石這一類，掉很多、不值錢", "改造會用到的", "少見、值錢的（通常都想撿）",
            "最低階的符文", "一般的符文", "高階的符文", "最高階的符文（很少掉）"};
    /** 符文的第一組（次級符文）。 */
    private static final int RUNE_GROUP = 3;
    /** 參考價值低於這個是低價、低於這個是中價（照 economy.yml 的 values，金幣）。 */
    private static final long[] GROUP_BOUNDS = {100, 1500};

    private final LootService service;
    private final int[] tabSlots = tabSlots(Tab.values().length);
    private final Map<Integer, String> currencyAt = new HashMap<>();
    private final Map<Integer, String> presetAt = new HashMap<>();
    /** 每一組的通貨代號（整組切換用）。 */
    private final List<List<String>> groups = new ArrayList<>();
    /** 每一組的標籤在第幾排（這次畫面算出來的）。 */
    /** 每一組的標籤在哪一格（2026-10-23：以前是 groupRows，標籤一定在每排的第一格）。 */
    private final int[] groupLabels = new int[GROUP_COUNT];
    /** 2026-10-29：通貨分頁每一頁的組標籤格 → 第幾組（同一組換頁後在新的一頁再標一次）。 */
    private final Map<Integer, Integer> labelGroup = new HashMap<>();
    /** 2026-10-29：通貨分頁現在第幾頁、共幾頁（排不下時換頁，這次之前多的種類直接不顯示）。 */
    private int currencyPage;
    private int currencyPages = 1;
    /** 打開時的角色（換了角色就不能再改，免得把上一個角色的設定寫到新角色上）。 */
    private final long characterId;
    private LootFilter filter;
    private boolean changed;
    private Tab tab = Tab.OVERVIEW;

    LootFilterMenu(final ExileCore plugin, final Player player, final LootService service) {
        super(plugin, player, 6, "<dark_gray>掉落過濾");
        this.service = service;
        this.filter = service.filter(player);
        final var character = plugin.players().get(player).character();
        this.characterId = character == null ? -1 : character.id();
        if (plugin.guide() != null) {
            plugin.guide().milestone(player, tw.exilecore.guide.GuideService.FILTER); // 2026-10-30 新手教學
        }
    }

    /** 打開並停在某個分頁（從「一定要撿的基底」回來時用）。 */
    void openOn(final String tabName) {
        try {
            tab = Tab.valueOf(tabName);
        } catch (final IllegalArgumentException e) {
            tab = Tab.OVERVIEW;
        }
        open();
    }

    private boolean stale() {
        final var character = plugin.players().get(player).character();
        return character == null || character.id() != characterId;
    }

    @Override
    protected void onClose() {
        if (changed && !stale()) {
            plugin.characters().save(player);
        }
    }

    @Override
    protected void draw() {
        frame();
        for (int i = 0; i < Tab.values().length; i++) {
            final Tab each = Tab.values()[i];
            set(tabSlots[i], tab(each.icon, each.title, each == tab, List.of("<gray>" + each.about)));
        }
        switch (tab) {
            case OVERVIEW -> drawOverview();
            case EQUIPMENT -> drawEquipment();
            case GEMS -> drawGemsAndFlasks();
            case CURRENCY -> drawCurrencies();
        }
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_HELP, helpButton(List.of(
                "你殺的怪物掉的東西：",
                "<green>✔ 會撿<gray>：直接收進背包（通貨進通貨袋）",
                "<red>✘ 隱藏<gray>：不會掉，自動換成一點金幣",
                "裝備的判斷順序：傳奇照分組（追逐級永遠撿）→ 隱藏的基底絕對不撿、必撿的有好詞綴門檻以上就撿",
                "→ 地圖看稀有度 → 等級差、不同類 → 稀有度（有好詞綴的救回來）；怪物的金幣直接加進角色",
                "",
                "上面的分頁切換要看的東西；點一下馬上生效",
                "有好幾個選項的：左鍵下一個、右鍵上一個",
                "<dark_gray>每個角色各自設定，關閉時自動存檔")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    // ---- 總覽 ----

    private void drawOverview() {
        set(slot(ROW_PRESETS, 0), rowLabel(Material.KNOWLEDGE_BOOK, "預設組合", "點一下套用，之後還可以在其他分頁逐項改",
                "改過之後變成「自訂」"));
        drawPresets();
        final String presetName = LootFilter.CUSTOM.equals(filter.preset()) ? "自訂"
                : service.config().preset(filter.preset()) == null ? filter.preset() : service.config().preset(filter.preset()).name();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>目前：<white>" + ItemRenderer.escape(presetName));
        lore.add("");
        final List<String> summary = summary();
        if (summary.isEmpty()) {
            lore.add("<green>什麼都撿（沒有隱藏任何東西）");
        } else {
            lore.add("<white>現在會隱藏：");
            for (final String line : summary) {
                lore.add("<red>　✘ <gray>" + line);
            }
        }
        if (filter.rules().keepAffixTier() > 0) {
            lore.add("<green>　✔ <gray>有 T" + filter.rules().keepAffixTier() + " 以上詞綴的裝備照樣撿<dark_gray>（只救被稀有度藏起來的）");
            if (filter.keepAffixLikeMine()) {
                lore.add("<dark_gray>　　好詞綴也只撿跟你同類的武器、同系的防具");
            }
        }
        if (!filter.alwaysBases().isEmpty()) {
            lore.add("<green>　✔ <gray>必撿的基底 " + filter.alwaysBases().size() + " 種" + (filter.rules().keepAffixTier() > 0
                    ? "<dark_gray>（有 T" + filter.rules().keepAffixTier() + " 以上詞綴的一律撿）" : "<dark_gray>（全部撿）")); // 2026-11-03
        }
        set(slot(ROW_SUMMARY, 0), icon(Material.WRITABLE_BOOK, "<#f5b942>【目前的設定】", lore));
        set(slot(ROW_SUMMARY, 1), icon(Material.PAPER, "<white>目前：" + ItemRenderer.escape(presetName),
                summary.isEmpty() ? "<green>什麼都撿" : "<gray>隱藏 " + summary.size() + " 類（滑鼠移到左邊的書看全部）"));
        set(slot(ROW_TOOLS, 0), rowLabel(Material.COMPARATOR, "其他"));
        set(SLOT_SHOW_ALL, icon(Material.LIME_DYE, "<green>全部顯示", "<gray>清掉所有隱藏與規則，什麼都撿", "", "<yellow>Shift＋左鍵：確定"));
        final long[] stats = service.filterStats(player);
        final long[] today = service.todayStats(player);
        set(SLOT_STATS, icon(Material.CLOCK, "<white>過濾了多少",
                String.format(Locale.ROOT, "<gray>今天：過濾掉 <white>%,d<gray> 件，換了 <#f5b942>%,d<gray> 金幣", today[0], today[1]),
                String.format(Locale.ROOT, "<gray>這次登入：<white>%,d<gray> 件、<#f5b942>%,d<gray> 金幣", stats[0], stats[1]),
                "<dark_gray>今天的每個角色各自算，過了午夜歸零"));
        final boolean sounds = filter.dropSounds();
        set(SLOT_SOUNDS, icon(sounds ? Material.JUKEBOX : Material.NOTE_BLOCK, "<white>稀有掉落音效：" + (sounds ? "<green>開" : "<red>關"),
                "<gray>掉到這些東西時響不同的音效：",
                "<#ffb347>　追逐級傳奇、映星鏡、共鳴石<gray>（最響）",
                "<#ff7a5a>　終局、極境、巔峰、金庫之主掉的傳奇與專屬寶石",
                "<#af6025>　傳奇、專屬寶石",
                "<#9fd8ff>　Boss 入場材料、劫盜藍圖、大鑿星石、Lv.21 技能寶石",
                "<dark_gray>關掉也照樣撿，只是不響（一般撿東西的聲音還在）",
                "", "<yellow>左鍵：" + (sounds ? "關掉" : "打開")));
    }

    /** 目前的設定用一句一句講。 */
    private List<String> summary() {
        final List<String> lines = new ArrayList<>();
        final List<String> rarities = new ArrayList<>();
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            if (filter.hidesEquipment(rarity)) {
                rarities.add(rarity.displayName());
            }
        }
        if (!rarities.isEmpty()) {
            lines.add(String.join("、", rarities) + "裝備（珠寶也算）");
        }
        final List<String> groups = new ArrayList<>();
        for (final LootFilter.UniqueGroup group : LootFilter.UniqueGroup.values()) {
            if (filter.hidesUniques(group)) {
                groups.add(group.displayName());
            }
        }
        if (!groups.isEmpty()) {
            lines.add("傳奇：" + String.join("、", groups) + "（追逐級永遠撿）"); // 2026-11-03
        }
        if (!filter.hiddenBases().isEmpty()) {
            lines.add("隱藏的基底 " + filter.hiddenBases().size() + " 種（絕對不撿，有好詞綴也不撿）"); // 2026-11-03
        }
        if (filter.hidesCharms()) {
            lines.add("護符");
        }
        final LootFilter.Rules rules = filter.rules();
        if (rules.levelGap() > 0) {
            lines.add("物品等級比你低超過 " + rules.levelGap() + " 級的裝備");
        }
        if (rules.weaponsLikeMine()) {
            lines.add("跟手上不同類的武器" + (weaponClassName() == null ? "（現在沒拿武器：不管）" : "（手上：" + weaponClassName() + "）"));
        }
        if (rules.armourLikeMine()) {
            lines.add("跟身上不同系的防具" + (armourFamilyNames().isEmpty() ? "（現在沒穿防具：不管）" : "（身上：" + String.join("、", armourFamilyNames()) + "）"));
        }
        if (rules.mapRarity() > 0) {
            lines.add(rules.mapRarity() >= 3 ? "全部地圖（傳奇地圖照撿）" : rules.mapRarity() >= 2 ? "普通（白）與魔法（藍）地圖" : "普通（白）地圖");
        }
        if (filter.hidesSkillGems()) {
            lines.add("未切割技能寶石");
        } else if (rules.minSkillGemLevel() > 0) {
            lines.add("低於 " + rules.minSkillGemLevel() + " 級的未切割技能寶石");
        }
        if (filter.hidesSupportGems()) {
            lines.add("未切割輔助寶石");
        } else if (rules.minSupportTier() > 0) {
            lines.add("低於第 " + rules.minSupportTier() + " 階的未切割輔助寶石");
        }
        if (filter.flasks() == LootFilter.FlaskRule.NONE) {
            lines.add("藥劑");
        } else if (filter.flasks() == LootFilter.FlaskRule.BETTER) {
            lines.add("沒有比藥劑欄裡好的藥劑");
        }
        final List<String> currencies = new ArrayList<>();
        for (final String id : filter.hiddenCurrencies()) {
            final CurrencyType type = plugin.currencies().repository().get(id);
            currencies.add(type == null ? id : type.name());
        }
        if (!currencies.isEmpty()) {
            lines.add("通貨：" + String.join("、", currencies));
        }
        return lines;
    }

    private void drawPresets() {
        presetAt.clear();
        int column = 1;
        for (final LootConfig.Preset preset : service.config().presets()) {
            if (column > 8) {
                break;
            }
            final boolean current = preset.id().equals(filter.preset());
            final List<String> lore = new ArrayList<>();
            if (!preset.description().isBlank()) {
                lore.add("<gray>" + ItemRenderer.escape(preset.description()));
            }
            // 這一組到底會藏什麼：用設定本身講，不只靠一句描述
            final LootFilter sample = LootFilter.of(preset);
            final List<String> hides = new ArrayList<>();
            for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
                if (sample.hidesEquipment(rarity)) {
                    hides.add(rarity.displayName() + "裝備");
                }
            }
            if (!sample.hiddenCurrencies().isEmpty()) {
                hides.add(sample.hiddenCurrencies().size() + " 種通貨");
            }
            if (sample.hidesSkillGems()) {
                hides.add("技能寶石");
            }
            if (sample.hidesSupportGems()) {
                hides.add("輔助寶石");
            }
            if (sample.flasks() != LootFilter.FlaskRule.ALL) {
                hides.add("藥劑：" + sample.flasks().displayName());
            }
            if (sample.activeRuleCount() > 0) {
                hides.add("進階規則 " + sample.activeRuleCount() + " 項");
            }
            lore.add(hides.isEmpty() ? "<dark_gray>什麼都不藏" : "<dark_gray>隱藏：" + String.join("、", hides));
            if (!filter.hiddenUniques().isEmpty() || !filter.baseRules().isEmpty()) {
                lore.add("<gray>你另外設定的傳奇分組、基底，套用後會保留"); // 2026-11-03
            }
            lore.add("");
            lore.add(current ? "<green>目前使用中" : "<yellow>左鍵：套用");
            final ItemStack icon = icon(presetMaterial(column - 1), (current ? "<#f5b942>▶ " : "<white>") + ItemRenderer.escape(preset.name()), lore);
            final int slot = slot(ROW_PRESETS, column);
            set(slot, current ? highlight(icon) : icon); // 目前的組合：金框（沒資源包：發光）
            presetAt.put(slot, preset.id());
            column++;
        }
    }

    // ---- 裝備 ----

    private void drawEquipment() {
        set(slot(1, 0), rowLabel(Material.IRON_CHESTPLATE, "依稀有度", "普通、魔法、稀有裝備（珠寶也算）撿或不撿", "傳奇照下面「傳奇」那一排"));
        for (final Map.Entry<Integer, Rarity> entry : RARITY_SLOTS.entrySet()) {
            final Rarity rarity = entry.getValue();
            final boolean hidden = filter.hidesEquipment(rarity);
            final Material material = switch (rarity) {
                case NORMAL -> Material.IRON_SWORD;
                case MAGIC -> Material.DIAMOND_SWORD;
                default -> Material.GOLDEN_SWORD;
            };
            set(entry.getKey(), toggle(material, "<" + rarity.color() + ">" + rarity.displayName() + "裝備", hidden,
                    service.goldEstimate(player, DropTable.Kind.EQUIPMENT, rarity, 0),
                    rarity == Rarity.RARE ? "<dark_gray>稀有掉出來未鑑定，隱藏前想清楚\n<dark_gray>藏起來的有好詞綴（右邊）會救回來" : "<dark_gray>藏起來的有好詞綴（右邊）會救回來"));
        }
        final LootFilter.Rules rules = filter.rules();
        final int keep = rules.keepAffixTier();
        set(SLOT_KEEP_AFFIX_TIER, option(Material.ENCHANTED_BOOK, "保留好詞綴的裝備", keep == 0 ? "關" : "有 T" + keep + " 以上就撿", keep > 0,
                List.of("<gray>裝備身上只要有一條詞綴是 T1～T" + (keep == 0 ? "幾" : keep) + "（T1 最好），",
                        "<gray>被<white>稀有度</white><gray>藏起來的照樣撿",
                        "<red>救不回來的：<gray>隱藏的基底、等級差、不同類不同系",
                        "<#f5b942>必撿的基底：<gray>有這個門檻以上的詞綴一律撿，沒有的不撿",
                        "<dark_gray>未鑑定的也算；珠寶也算；地圖不管",
                        "<dark_gray>只有一兩階的詞綴（例如 +1 技能等級）不算",
                        keep == 0 ? "<dark_gray>例：開 T2、隱藏魔法裝備 → 有 T1、T2 詞綴的魔法裝備還是會撿" : ""),
                options(LootFilter.AFFIX_TIERS, keep, value -> value == 0 ? "關" : "T" + value + " 以上")));
        final boolean keepLike = filter.keepAffixLikeMine();
        set(SLOT_KEEP_AFFIX_LIKE, option(Material.WRITABLE_BOOK, "好詞綴也只撿同類", keepLike ? "開" : "關", keepLike,
                List.of("<gray>開了之後，「保留好詞綴的裝備」只保留跟你同類的：",
                        "<gray>武器跟手上同種類、防具與盾跟身上同系",
                        keep == 0 ? "<dark_gray>（「保留好詞綴的裝備」關著，這條沒差）" : "<dark_gray>不同類的照稀有度的規則藏起來"),
                List.of("<yellow>左鍵：" + (keepLike ? "關" : "開"))));
        set(slot(2, 0), rowLabel(Material.COMPARATOR, "進階規則", "照你的等級、手上的武器、身上的防具藏東西",
                "這三條藏起來的，好詞綴也救不回來；傳奇、珠寶、必撿的基底不管"));
        final int gap = rules.levelGap();
        set(SLOT_LEVEL_GAP, option(Material.EXPERIENCE_BOTTLE, "等級差", gap == 0 ? "關" : "低超過 " + gap + " 級就隱藏", gap > 0,
                List.of("<gray>物品等級比你低太多的裝備（詞綴階級低、賣不掉）不撿",
                        "<gray>傳奇、珠寶、地圖不管；只看普通、魔法、稀有",
                        gap == 0 ? "<dark_gray>例：開 10 級、你 70 級 → 物品等級 59 以下的隱藏"
                                : "<dark_gray>現在你 " + plugin.players().get(player).level() + " 級 → 物品等級 "
                                + Math.max(0, plugin.players().get(player).level() - gap - 1) + " 以下的隱藏"),
                options(LootFilter.LEVEL_GAPS, gap, value -> value == 0 ? "關" : "低超過 " + value + " 級")));
        final String weapon = weaponClassName();
        set(SLOT_WEAPONS_LIKE_MINE, option(Material.BOW, "只撿跟手上同類的武器", rules.weaponsLikeMine() ? "開" : "關", rules.weaponsLikeMine(),
                List.of("<gray>拿弓就只撿弓、拿雙手劍就只撿雙手劍；雙手武器的人不撿盾",
                        "<gray>箭袋只有拿弓的人撿（弩、獸魂符、其他武器都不撿）",
                        "<gray>傳奇、珠寶不管；換了武器會自動跟著換",
                        weapon == null ? "<dark_gray>你現在沒拿武器：這條先不會藏東西" : "<dark_gray>你手上：<white>" + weapon),
                List.of("<yellow>左鍵：" + (rules.weaponsLikeMine() ? "關" : "開"))));
        final List<String> families = armourFamilyNames();
        set(SLOT_ARMOUR_LIKE_MINE, option(Material.LEATHER_CHESTPLATE, "只撿跟身上同系的防具", rules.armourLikeMine() ? "開" : "關", rules.armourLikeMine(),
                List.of("<gray>防具分力量系（護甲）、敏捷系（閃避）、智慧系（護盾）與混系",
                        "<gray>只撿跟你身上穿的同系的防具與盾；傳奇不管",
                        families.isEmpty() ? "<dark_gray>你現在沒穿防具：這條先不會藏東西" : "<dark_gray>你身上：<white>" + String.join("、", families)),
                List.of("<yellow>左鍵：" + (rules.armourLikeMine() ? "關" : "開"))));
        final int mapRarity = rules.mapRarity();
        set(SLOT_MAPS, option(Material.FILLED_MAP, "地圖", LootFilter.mapRarityName(mapRarity), mapRarity > 0,
                List.of("<gray>依地圖的稀有度：<white>普通（白）<gray>、<#8888ff>魔法（藍）<gray>、<#ffff77>稀有（黃）",
                        "<dark_gray>隱藏的地圖一樣換成金幣；傳奇地圖照傳奇分組",
                        "<dark_gray>某幾張地圖整張不撿：到基底清單的「地圖」分頁隱藏那幾種"),
                options(LootFilter.MAP_RARITIES, mapRarity, LootFilter::mapRarityName)));
        // 2026-11-03 照站長：傳奇分組，各自撿不撿（追逐級永遠撿）；這次之前傳奇只有一格、追逐級與頂級專屬永遠撿
        set(slot(3, 0), rowLabel(Material.NETHERITE_SWORD, "傳奇", "照掉落來源分六組，各自撿不撿", "追逐級永遠會撿；傳奇不看稀有度、等級差、同類同系、基底"));
        for (final LootFilter.UniqueGroup group : LootFilter.UniqueGroup.values()) {
            final boolean hidden = filter.hidesUniques(group);
            final Material material = switch (group) {
                case CHASE -> Material.NETHER_STAR;
                case COMMON -> Material.NETHERITE_SWORD;
                case EVENT -> Material.CHEST;
                case PINNACLE -> Material.DRAGON_HEAD;
                case ENDGAME -> Material.WITHER_SKELETON_SKULL;
                case HEIST -> Material.ECHO_SHARD;
            };
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + group.about());
            if (group == LootFilter.UniqueGroup.CHASE) {
                lore.add("<#ffb347>永遠會撿，不能隱藏");
                set(SLOT_UNIQUE_GROUPS + group.ordinal(), onMark(icon(material, "<green>✔ <#af6025>" + group.displayName() + "傳奇", lore)));
                continue;
            }
            lore.add(hidden ? "<red>隱藏<gray>：這一組的傳奇不會掉，換成金幣" : "<green>會撿<gray>：自動撿進背包");
            lore.add("");
            lore.add("<yellow>左鍵：" + (hidden ? "改成會撿" : "改成隱藏"));
            final ItemStack groupIcon = icon(material, (hidden ? "<red>✘ " : "<green>✔ ") + "<#af6025>" + group.displayName(), lore);
            set(SLOT_UNIQUE_GROUPS + group.ordinal(), hidden ? offMark(groupIcon) : onMark(groupIcon));
        }
        // 2026-11-03 照站長：基底清單（每一種基底顯示／隱藏／必撿）取代只撿的基底、珠寶、遺時星晶、劫盜特殊基底
        set(slot(4, 0), rowLabel(Material.EMERALD, "基底", "每一種基底各自：顯示、隱藏、必撿", "隱藏的絕對不撿（有 T1 詞綴也不撿）", "必撿的有好詞綴門檻以上就一律撿（門檻關＝全部撿）"));
        final int hiddenBases = filter.hiddenBases().size();
        final int alwaysBases = filter.alwaysBases().size();
        final List<String> basesLore = new ArrayList<>();
        basesLore.add("<gray>武器、防具、盾與箭袋、飾品、珠寶、遺時星晶、劫盜特殊項鍊、地圖，每一種基底：");
        basesLore.add("<white>顯示</white><gray>：照上面的規則（稀有度、好詞綴、等級差、同類同系）");
        basesLore.add("<red>隱藏</red><gray>：絕對不撿，有好詞綴也不撿（傳奇不受影響）");
        basesLore.add("<#f5b942>必撿</#f5b942><gray>：有「保留好詞綴」門檻以上詞綴的一律撿（不看稀有度、等級差、同類同系）");
        basesLore.add(filter.rules().keepAffixTier() > 0 ? "<dark_gray>　現在門檻 T" + filter.rules().keepAffixTier() + "：必撿的基底要有 T1～T" + filter.rules().keepAffixTier() + " 詞綴才撿"
                : "<dark_gray>　現在門檻是關：必撿的基底全部撿（地圖一律全部撿）");
        basesLore.add("");
        basesLore.add("<gray>現在：<red>隱藏 " + hiddenBases + " 種<gray>、<#f5b942>必撿 " + alwaysBases + " 種");
        basesLore.add("");
        basesLore.add("<yellow>左鍵：打開清單");
        final ItemStack basesIcon = icon(Material.EMERALD, (hiddenBases + alwaysBases == 0 ? "<white>" : "<green>") + "基底清單", basesLore);
        set(SLOT_BASES, hiddenBases + alwaysBases == 0 ? basesIcon : highlight(basesIcon));
        set(SLOT_BASES_JEWELS, icon(Material.AMETHYST_CLUSTER, "<white>珠寶、遺時星晶", "<gray>直接打開基底清單的「珠寶」分頁",
                "<dark_gray>四種珠寶、四種遺時星晶各自顯示、隱藏、必撿", "", "<yellow>左鍵：打開"));
        set(SLOT_BASES_MAPS, icon(Material.FILLED_MAP, "<white>地圖", "<gray>直接打開基底清單的「地圖」分頁",
                "<dark_gray>某幾張地圖整張不撿（不管稀有度）", "", "<yellow>左鍵：打開"));
        set(SLOT_BASES_HIDDEN, icon(Material.BARRIER, "<white>已隱藏、必撿的", "<gray>直接打開基底清單的「已隱藏」分頁",
                "", "<yellow>左鍵：打開"));
    }

    // ---- 寶石與藥劑 ----

    private void drawGemsAndFlasks() {
        final LootFilter.Rules rules = filter.rules();
        set(slot(1, 0), rowLabel(Material.PRISMARINE_SHARD, "未切割技能寶石", "整個不撿，或只撿夠高等級的"));
        set(SLOT_SKILL_GEMS, toggle(Material.PRISMARINE_SHARD, "<#7fd4ff>未切割技能寶石", filter.hidesSkillGems(),
                service.goldEstimate(player, DropTable.Kind.SKILL_GEM, null, 0), "<dark_gray>右邊那格可以只藏低等級的"));
        final int gemLevel = rules.minSkillGemLevel();
        set(SLOT_SKILL_GEM_LEVEL, option(Material.PRISMARINE_CRYSTALS, "技能寶石最低等級", gemLevel == 0 ? "不限" : gemLevel + " 級以上才撿", gemLevel > 0,
                List.of("<gray>未切割技能寶石的等級低於這個就隱藏（高等級角色不需要 1 級的寶石）",
                        filter.hidesSkillGems() ? "<dark_gray>（技能寶石整個隱藏了，這條沒差）" : "<dark_gray>寶石的等級寫在名字上"),
                options(LootFilter.GEM_LEVELS, gemLevel, value -> value == 0 ? "不限" : value + " 級以上")));
        set(slot(2, 0), rowLabel(Material.QUARTZ, "未切割輔助寶石", "整個不撿，或只撿夠高階的"));
        set(SLOT_SUPPORT_GEMS, toggle(Material.QUARTZ, "<#7fd4ff>未切割輔助寶石", filter.hidesSupportGems(),
                service.goldEstimate(player, DropTable.Kind.SUPPORT_GEM, null, 0), "<dark_gray>右邊那格可以只藏低階的"));
        final int tier = rules.minSupportTier();
        set(SLOT_SUPPORT_TIER, option(Material.QUARTZ_BLOCK, "輔助寶石最低階", tier == 0 ? "不限" : "第 " + tier + " 階以上才撿", tier > 0,
                List.of("<gray>未切割輔助寶石的階級低於這個就隱藏（第 1 階的輔助到了地圖通常不缺）",
                        filter.hidesSupportGems() ? "<dark_gray>（輔助寶石整個隱藏了，這條沒差）" : "<dark_gray>階級寫在名字上"),
                options(LootFilter.SUPPORT_TIERS, tier, value -> value == 0 ? "不限" : "第 " + value + " 階以上")));
        set(slot(3, 0), rowLabel(Material.POTION, "藥劑", "全部撿、只撿比藥劑欄好的、或全部不撿"));
        final LootFilter.FlaskRule flasks = filter.flasks();
        final List<String> flaskLore = new ArrayList<>();
        for (final LootFilter.FlaskRule rule : LootFilter.FlaskRule.values()) {
            flaskLore.add((rule == flasks ? "<green>▶ " : "<dark_gray>　 ") + rule.displayName());
        }
        flaskLore.add("");
        flaskLore.add("<gray>「好」是需求等級比藥劑欄裡同一種（生命／魔力）的高");
        flaskLore.add(String.format(Locale.ROOT, "<gray>隱藏時換成約 <#f5b942>%,d<gray> 金幣",
                service.goldEstimate(player, DropTable.Kind.FLASK, null, 0)));
        flaskLore.add("");
        flaskLore.add("<yellow>左鍵：下一個　右鍵：上一個");
        final ItemStack flaskIcon = icon(Material.POTION, (flasks == LootFilter.FlaskRule.NONE ? "<red>✘ " : "<green>✔ ")
                + "<#ff7777>藥劑：<white>" + flasks.displayName(), flaskLore);
        set(SLOT_FLASKS, flasks == LootFilter.FlaskRule.NONE ? offMark(flaskIcon) : onMark(flaskIcon));
        final boolean charms = !filter.hidesCharms();
        final ItemStack charmIcon = icon(Material.AMETHYST_SHARD, (charms ? "<green>✔ " : "<red>✘ ") + "<#c9a0ff>護符：<white>" + (charms ? "撿" : "不撿"),
                List.of("<gray>護符的掉落要不要撿（不撿換成金幣）", "", "<yellow>點一下切換"));
        set(SLOT_CHARMS, charms ? onMark(charmIcon) : offMark(charmIcon));
    }

    // ---- 通貨 ----

    private void drawCurrencies() {
        currencyAt.clear();
        groups.clear();
        final List<List<CurrencyType>> byGroup = new ArrayList<>();
        for (int i = 0; i < GROUP_COUNT; i++) {
            byGroup.add(new ArrayList<>());
            groups.add(new ArrayList<>());
        }
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (type.dropWeight() <= 0 && !tw.exilecore.heist.HeistService.CONTRACT.equals(type.id())) {
                continue; // 怪物不會掉的（鑑定卷軸、地圖機制的、未切割寶石）；劫盜合約另外掉（星圖裡的怪），也可以過濾（2026-10-16）
            }
            byGroup.get(groupOf(type)).add(type);
        }
        // 2026-10-29：排不下的換頁（每頁四排；每組從新的一排開始，標籤在第一格、通貨在後面 8 格），全部種類都看得到、點得到；
        //   這次之前四排塞不下就一組接著一組擠，再塞不下的直接不顯示（標籤寫「還有幾種排不下」）
        labelGroup.clear();
        java.util.Arrays.fill(groupLabels, -1);
        final List<int[]> rows = new ArrayList<>(); // {組, 這一排第一種的序號}
        for (int group = 0; group < GROUP_COUNT; group++) {
            byGroup.get(group).sort(group >= RUNE_GROUP ? java.util.Comparator.comparing(CurrencyType::id)
                    : (a, b) -> Long.compare(value(a), value(b)));
            final int size = byGroup.get(group).size();
            for (int start = 0; start == 0 || start < size; start += GROUP_SIZE) {
                rows.add(new int[] {group, start});
            }
        }
        final int rowsPerPage = LAST_GROUP_ROW - FIRST_GROUP_ROW + 1;
        currencyPages = Math.max(1, (rows.size() + rowsPerPage - 1) / rowsPerPage);
        currencyPage = Math.max(0, Math.min(currencyPages - 1, currencyPage));
        final List<ItemStack> labels = new ArrayList<>();
        for (int group = 0; group < GROUP_COUNT; group++) {
            final List<CurrencyType> types = byGroup.get(group);
            int shown = 0;
            int hidden = 0;
            for (final CurrencyType type : types) {
                if (filter.hidesCurrency(type.id())) {
                    hidden++;
                } else {
                    shown++;
                }
                groups.get(group).add(type.id());
            }
            final String state = types.isEmpty() ? "<dark_gray>（沒有）" : hidden == 0 ? "<green>全部會撿" : shown == 0 ? "<red>全部隱藏"
                    : "<gray>撿 " + shown + "、藏 " + hidden;
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + GROUP_HINTS[group]);
            if (group < RUNE_GROUP) {
                lore.add("<dark_gray>照參考價值分（" + (group == 0 ? "低於 " + GROUP_BOUNDS[0] : group == 1 ? GROUP_BOUNDS[0] + "–" + (GROUP_BOUNDS[1] - 1)
                        : GROUP_BOUNDS[1] + " 以上") + " 金幣）" + (group == 1 ? "<dark_gray>；沒有參考價值的也在這" : ""));
            } else {
                lore.add("<dark_gray>符文照階級分（次級 → 一般 → 高階 → 完美）");
            }
            lore.add(state);
            lore.add("");
            lore.add("<yellow>右鍵：這一組" + (shown == 0 ? "全部改成會撿" : "全部隱藏"));   // 有會撿的就全部隱藏（跟 click 一樣）
            labels.add(icon(group == 0 ? Material.GLOWSTONE_DUST : group == 1 ? Material.GOLD_NUGGET : group == 2 ? Material.NETHER_STAR
                    : group == RUNE_GROUP ? Material.CLAY_BALL : group == RUNE_GROUP + 1 ? Material.LAPIS_LAZULI
                    : group == RUNE_GROUP + 2 ? Material.AMETHYST_SHARD : Material.ECHO_SHARD,
                    "<#f5b942>【" + GROUP_NAMES[group] + "】", lore));
        }
        int lastGroup = -1;
        for (int r = 0; r < rowsPerPage; r++) {
            final int index = currencyPage * rowsPerPage + r;
            if (index >= rows.size()) {
                break;
            }
            final int group = rows.get(index)[0];
            final int start = rows.get(index)[1];
            if (group != lastGroup) {
                final int labelSlot = slot(FIRST_GROUP_ROW + r, 0);
                set(labelSlot, labels.get(group));
                labelGroup.put(labelSlot, group);
                groupLabels[group] = labelSlot;
                lastGroup = group;
            }
            final List<CurrencyType> types = byGroup.get(group);
            for (int i = start; i < Math.min(types.size(), start + GROUP_SIZE); i++) {
                final CurrencyType type = types.get(i);
                final int slot = slot(FIRST_GROUP_ROW + r, 1 + (i - start));
                final boolean isHidden = filter.hidesCurrency(type.id());
                final long gold = service.goldEstimate(player, DropTable.Kind.CURRENCY, null, type.dropWeight());
                final ItemStack icon = icon(Menu.material(type.material(), Material.EMERALD),
                        (isHidden ? "<red>✘ " : "<green>✔ ") + "<#f5b942>" + ItemRenderer.escape(type.name()),
                        isHidden ? "<red>隱藏<gray>：不會掉，換成約 <#f5b942>" + String.format(Locale.ROOT, "%,d", gold) + "<gray> 金幣"
                                : "<green>會撿<gray>：自動收進通貨袋",
                        "<dark_gray>" + ItemRenderer.escape(type.description()),
                        String.format(Locale.ROOT, "<dark_gray>參考價值 %,d 金幣・%s", value(type),
                                type.dropWeight() <= 0 ? "只在星圖裡掉" : frequency(type.dropWeight())),
                        "",
                        "<yellow>左鍵：" + (isHidden ? "改成會撿" : "改成隱藏"));
                final ItemStack decorated = tw.exilecore.currency.CurrencyService.decorate(icon, type);
                set(slot, isHidden ? offMark(decorated) : onMark(decorated));
                currencyAt.put(slot, type.id());
            }
        }
        if (currencyPages > 1) {
            set(SLOT_PREV, pageButton(false, currencyPage, currencyPages));
            set(SLOT_NEXT, pageButton(true, currencyPage, currencyPages));
        }
    }

    /**
     * 每組佔幾排：一排 8 種，最少 1 排；全部加起來不超過可以用的排數（第 1～4 排），
     * 不夠時從數量多的組先少給一排（每組至少 1 排）。
     */
    /** 四排 9 格裡的第幾格 → 選單的格子。 */
    private static int gridSlot(final int position) {
        return slot(FIRST_GROUP_ROW + position / 9, position % 9);
    }

    /**
     * 2026-10-23：通貨每一組放在哪幾格（四排 × 9 格裡的位置，0 起算）：每組的第一個是標籤，後面是通貨。
     * 每組都從新的一排開始（標籤在第一格、通貨在後面 8 格、多的換排）排得下就這樣排；
     * 排不下就一組接著一組排（標籤也佔一格），不會有通貨被擠掉（以前中間那組 9 種，星火石不見了）。
     * 還是排不下的，那組後面的就不放（標籤寫還有幾種）。純計算，測試用。
     */
    static int[][] layout(final int[] sizes) {
        final int cells = (LAST_GROUP_ROW - FIRST_GROUP_ROW + 1) * 9;
        int rowsNeeded = 0;
        for (final int size : sizes) {
            rowsNeeded += Math.max(1, (size + GROUP_SIZE - 1) / GROUP_SIZE);
        }
        final int[][] out = new int[sizes.length][];
        if (rowsNeeded <= LAST_GROUP_ROW - FIRST_GROUP_ROW + 1) {
            int row = 0;
            for (int g = 0; g < sizes.length; g++) {
                out[g] = new int[sizes[g] + 1];
                out[g][0] = row * 9;
                for (int i = 0; i < sizes[g]; i++) {
                    out[g][i + 1] = (row + i / GROUP_SIZE) * 9 + 1 + i % GROUP_SIZE;
                }
                row += Math.max(1, (sizes[g] + GROUP_SIZE - 1) / GROUP_SIZE);
            }
            return out;
        }
        int at = 0;
        for (int g = 0; g < sizes.length; g++) {
            // 後面每一組至少留一格給標籤
            final int fit = Math.max(1, Math.min(sizes[g] + 1, cells - at - (sizes.length - 1 - g)));
            out[g] = new int[fit];
            for (int i = 0; i < fit; i++) {
                out[g][i] = at + i;
            }
            at += fit;
        }
        return out;
    }

    private long value(final CurrencyType type) {
        return plugin.economy() == null ? 0 : plugin.economy().config().value(type.id());
    }

    private static String frequency(final int weight) {
        return weight >= 1000 ? "很常掉" : weight >= 150 ? "普通" : weight >= 30 ? "少見" : "很少見";
    }

    // ---- 共用的畫法 ----

    /** 一個可以切換的項目：名字前面「✔ 會撿」或「✘ 隱藏」，綠框或紅框（沒資源包：會撿的發光）。 */
    private ItemStack toggle(final Material material, final String name, final boolean hidden, final long gold, final String note) {
        final List<String> lore = new ArrayList<>();
        lore.add(hidden ? "<red>隱藏<gray>：不會掉，換成約 <#f5b942>" + String.format(Locale.ROOT, "%,d", gold) + "<gray> 金幣" : "<green>會撿<gray>：自動撿進背包");
        if (note != null) {
            lore.addAll(List.of(note.split("\n"))); // 2026-10-22：一格可以寫好幾行
        }
        lore.add("");
        lore.add("<yellow>左鍵：" + (hidden ? "改成會撿" : "改成隱藏"));
        final ItemStack icon = icon(material, (hidden ? "<red>✘ " : "<green>✔ ") + name, lore);
        return hidden ? offMark(icon) : onMark(icon);
    }

    /** 一個有好幾種值的規則：名字寫「規則：目前的值」，開著是綠框；說明下面列出所有選項（目前的打 ▶）。 */
    private ItemStack option(final Material material, final String name, final String current, final boolean active, final List<String> about,
                             final List<String> choices) {
        final List<String> lore = new ArrayList<>();
        for (final String line : about) {
            if (!line.isEmpty()) {
                lore.add(line);
            }
        }
        lore.add("");
        lore.addAll(choices);
        final ItemStack icon = icon(material, (active ? "<#f5b942>" : "<gray>") + name + "：<white>" + current, lore);
        return active ? onMark(icon) : icon;
    }

    private interface Label {
        String of(int value);
    }

    /** 選項列：每個值一行，目前的打 ▶；最後一行寫左鍵下一個、右鍵上一個。 */
    private static List<String> options(final int[] values, final int current, final Label label) {
        final List<String> lines = new ArrayList<>();
        for (final int value : values) {
            lines.add((value == current ? "<green>▶ " : "<dark_gray>　 ") + label.of(value));
        }
        lines.add("");
        lines.add("<yellow>左鍵：下一個　右鍵：上一個");
        return lines;
    }

    private static Material presetMaterial(final int index) {
        return switch (index) {
            case 0 -> Material.LIME_DYE;
            case 1 -> Material.YELLOW_DYE;
            case 2 -> Material.RED_DYE;
            default -> Material.GRAY_DYE;
        };
    }

    /** 手上武器的種類名稱（沒拿 null）。 */
    private String weaponClassName() {
        final String tag = LootFilter.primaryWeaponClass(plugin.players().get(player).weaponTags());
        return tag == null ? null : LootFilter.weaponWord(tag) + (plugin.players().get(player).weaponTags().contains("two_hand") ? "（雙手）" : "");
    }

    /** 身上防具的系的名稱。 */
    private List<String> armourFamilyNames() {
        final List<String> names = new ArrayList<>();
        final LootFilter.Viewer viewer = service.viewer(player, plugin.players().get(player));
        for (final String family : List.of("str_armour", "dex_armour", "int_armour")) {
            if (viewer.armourFamilies().contains(family)) {
                names.add(LootFilter.familyWord(family));
            }
        }
        return names;
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (stale()) {
            player.closeInventory();
            return;
        }
        if (click == ClickType.DOUBLE_CLICK) {
            return; // 2026-10：雙擊會先送兩次左鍵再送一次雙擊，多選項的格子會多跳一格
        }
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        for (int i = 0; i < tabSlots.length; i++) {
            if (tabSlots[i] == slot) {
                if (tab != Tab.values()[i]) {
                    tab = Tab.values()[i];
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                    redraw();
                }
                return;
            }
        }
        final boolean backwards = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        final LootFilter next = filter.copy();
        switch (tab) {
            case OVERVIEW -> {
                if (presetAt.containsKey(slot)) {
                    final LootConfig.Preset preset = service.config().preset(presetAt.get(slot));
                    if (preset != null) {
                        // 基底、傳奇分組、音效、護符、好詞綴也只撿同類的設定留著（2026-10：以前護符與好詞綴同類會被悄悄重設）
                        apply(LootFilter.of(preset).withDropSounds(filter.dropSounds()).withExtras(filter));
                    }
                    return;
                }
                if (slot == SLOT_SOUNDS) {
                    next.toggleDropSounds();
                    apply(next);
                    if (next.dropSounds()) {
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f); // 試聽
                    }
                    return;
                }
                if (slot != SLOT_SHOW_ALL) {
                    return;
                }
                if (click != ClickType.SHIFT_LEFT) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>要清掉所有設定：Shift＋左鍵"));
                    return;
                }
                next.showEverything();
            }
            case EQUIPMENT -> {
                final Rarity rarity = RARITY_SLOTS.get(slot);
                if (rarity != null) {
                    next.toggleEquipment(rarity);
                } else if (slot == SLOT_LEVEL_GAP) {
                    next.cycleLevelGap(backwards);
                } else if (slot == SLOT_WEAPONS_LIKE_MINE) {
                    next.toggleWeaponsLikeMine();
                } else if (slot == SLOT_ARMOUR_LIKE_MINE) {
                    next.toggleArmourLikeMine();
                } else if (slot == SLOT_KEEP_AFFIX_LIKE) {
                    next.toggleKeepAffixLikeMine();
                } else if (slot == SLOT_KEEP_AFFIX_TIER) {
                    next.cycleKeepAffixTier(backwards);
                } else if (slot == SLOT_MAPS) {
                    next.cycleMapRarity(backwards);
                } else if (slot >= SLOT_UNIQUE_GROUPS && slot < SLOT_UNIQUE_GROUPS + LootFilter.UniqueGroup.values().length) {
                    next.toggleUniques(LootFilter.UniqueGroup.values()[slot - SLOT_UNIQUE_GROUPS]); // 2026-11-03（追逐級沒反應）
                } else if (slot == SLOT_BASES || slot == SLOT_BASES_JEWELS || slot == SLOT_BASES_MAPS || slot == SLOT_BASES_HIDDEN) {
                    if (changed && !stale()) {
                        plugin.characters().save(player);
                        changed = false;
                    }
                    final String category = slot == SLOT_BASES_JEWELS ? "JEWEL" : slot == SLOT_BASES_MAPS ? "MAP" : slot == SLOT_BASES_HIDDEN ? "SET" : "ALL";
                    new BasesMenu(plugin, player, service, category, () -> new LootFilterMenu(plugin, player, service).openOn("EQUIPMENT")).open();
                    return;
                } else {
                    return;
                }
            }
            case GEMS -> {
                if (slot == SLOT_SKILL_GEMS) {
                    next.toggleSkillGems();
                } else if (slot == SLOT_SKILL_GEM_LEVEL) {
                    next.cycleSkillGemLevel(backwards);
                } else if (slot == SLOT_SUPPORT_GEMS) {
                    next.toggleSupportGems();
                } else if (slot == SLOT_SUPPORT_TIER) {
                    next.cycleSupportTier(backwards);
                } else if (slot == SLOT_CHARMS) {
                    next.toggleCharms();
                } else if (slot == SLOT_FLASKS) {
                    next.cycleFlasks();
                    if (backwards) {
                        next.cycleFlasks(); // 三種：往前兩次等於往後一次
                    }
                } else {
                    return;
                }
            }
            case CURRENCY -> {
                if ((slot == SLOT_PREV || slot == SLOT_NEXT) && currencyPages > 1) {
                    currencyPage = stepPage(currencyPage, currencyPages, slot == SLOT_NEXT, click); // 2026-10-29：通貨換頁
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                    redraw();
                    return;
                }
                if (currencyAt.containsKey(slot)) {
                    next.toggleCurrency(currencyAt.get(slot));
                } else {
                    final int group = groupOf(slot);
                    if (group < 0 || !backwards) {
                        return;
                    }
                    // 整排切換：有會撿的就全部隱藏，不然全部改成會撿
                    boolean anyShown = false;
                    for (final String id : groups.get(group)) {
                        anyShown |= !filter.hidesCurrency(id);
                    }
                    next.setCurrenciesHidden(groups.get(group), anyShown);
                }
            }
        }
        apply(next);
    }

    /** 2026-10-30：這種通貨在哪一組（符文照階級；其他照參考價值，沒有參考價值的放中價）。 */
    private int groupOf(final CurrencyType type) {
        if (type.operation() == tw.exilecore.currency.CurrencyOperation.RUNE) {
            final String id = type.id();
            return RUNE_GROUP + (id.endsWith("_lesser") ? 0 : id.endsWith("_greater") ? 2 : id.endsWith("_perfect") ? 3 : 1);
        }
        final long value = value(type);
        return value <= 0 ? 1 : value < GROUP_BOUNDS[0] ? 0 : value < GROUP_BOUNDS[1] ? 1 : 2;
    }

    private int groupOf(final int slot) {
        return labelGroup.getOrDefault(slot, -1); // 2026-10-29：分頁後同一組可能換到下一頁才標
    }

    private void apply(final LootFilter next) {
        if (next.equals(filter)) {
            return;
        }
        filter = next;
        service.setFilter(player, next);
        changed = true;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        redraw();
    }
}
