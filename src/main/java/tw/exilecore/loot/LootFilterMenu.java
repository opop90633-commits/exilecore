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
        EQUIPMENT("裝備", Material.IRON_CHESTPLATE, "依稀有度、等級差、同類武器、同系防具、詞綴階級、地圖"),
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
    // ---- 裝備 ----
    private static final Map<Integer, Rarity> RARITY_SLOTS = Map.of(slot(1, 1), Rarity.NORMAL, slot(1, 2), Rarity.MAGIC,
            slot(1, 3), Rarity.RARE, slot(1, 4), Rarity.UNIQUE);
    private static final int SLOT_LEVEL_GAP = slot(2, 1);
    private static final int SLOT_WEAPONS_LIKE_MINE = slot(2, 2);
    private static final int SLOT_ARMOUR_LIKE_MINE = slot(2, 3);
    private static final int SLOT_KEEP_AFFIX_TIER = slot(2, 4);
    /** 好詞綴也只撿同類（2026-10）。 */
    private static final int SLOT_KEEP_AFFIX_LIKE = slot(2, 5);
    private static final int SLOT_MAPS = slot(3, 1);
    /** 珠寶依稀有度（2026-10）。 */
    private static final int SLOT_JEWELS = slot(3, 3);
    /** 遺時星晶（2026-10）：依稀有度一格，後面四格是四種撿不撿。 */
    private static final int SLOT_TIME_LOST = slot(3, 4);
    private static final int SLOT_TIME_LOST_KINDS = slot(3, 5);
    /** 只撿的基底（2026-10）。 */
    private static final int SLOT_KEEP_BASES = slot(4, 1);
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
    private static final int GROUP_COUNT = 3;
    private static final int GROUP_SIZE = 8;
    /** 通貨可以用的排（第 1～4 排；第 5 排是下面的按鈕）。 */
    private static final int FIRST_GROUP_ROW = 1;
    private static final int LAST_GROUP_ROW = 4;
    private static final String[] GROUP_NAMES = {"低價通貨", "中價通貨", "高價通貨"};
    private static final String[] GROUP_HINTS = {"微光石這一類，掉很多、不值錢", "改造會用到的", "少見、值錢的（通常都想撿）"};
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
                "追逐級、頂級專屬傳奇永遠會撿（傳奇地圖、珠寶也是）；怪物的金幣直接加進角色",
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
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE, Rarity.UNIQUE}) {
            if (filter.hidesEquipment(rarity)) {
                rarities.add(rarity == Rarity.UNIQUE ? "傳奇（追逐級、頂級專屬、珠寶、地圖照撿）" : rarity.displayName());
            }
        }
        if (!rarities.isEmpty()) {
            lines.add(String.join("、", rarities) + "裝備");
        }
        if (!filter.keepBases().isEmpty()) {
            lines.add("只撿 " + filter.keepBases().size() + " 種基底：有挑的那幾類裡，其他基底的普通、魔法、稀有裝備"); // 2026-10：以前總覽沒列
        }
        if (filter.hidesCharms()) {
            lines.add("護符");
        }
        final LootFilter.Rules rules = filter.rules();
        if (filter.jewelRarity() > 0) {
            lines.add("珠寶：" + LootFilter.jewelRarityName(filter.jewelRarity()));
        }
        if (filter.filtersTimeLost()) {
            final List<String> kinds = new ArrayList<>();
            for (final LootFilter.TimeLost kind : LootFilter.TimeLost.values()) {
                if (filter.hidesTimeLost(kind)) {
                    kinds.add(kind.displayName());
                }
            }
            lines.add("遺時星晶：" + (filter.timeLostRarity() > 0 ? LootFilter.timeLostRarityName(filter.timeLostRarity()) : "")
                    + (filter.timeLostRarity() > 0 && !kinds.isEmpty() ? "；" : "") + (kinds.isEmpty() ? "" : "不撿" + String.join("、", kinds)));
        }
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
            lines.add(rules.mapRarity() >= 2 ? "普通（白）與魔法（藍）地圖" : "普通（白）地圖");
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
            if (filter.hidesEquipment(Rarity.UNIQUE)) {
                lore.add("<gray>你另外隱藏了傳奇，套用後會保留"); // 2026-10-22：傳奇隱藏換組合會帶過去
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
        set(slot(1, 0), rowLabel(Material.IRON_CHESTPLATE, "依稀有度", "整個稀有度的裝備撿或不撿", "追逐級、頂級專屬傳奇永遠會撿"));
        for (final Map.Entry<Integer, Rarity> entry : RARITY_SLOTS.entrySet()) {
            final Rarity rarity = entry.getValue();
            final boolean hidden = filter.hidesEquipment(rarity);
            final Material material = switch (rarity) {
                case NORMAL -> Material.IRON_SWORD;
                case MAGIC -> Material.DIAMOND_SWORD;
                case UNIQUE -> Material.NETHERITE_SWORD;
                default -> Material.GOLDEN_SWORD;
            };
            set(entry.getKey(), toggle(material, "<" + rarity.color() + ">" + rarity.displayName() + "裝備", hidden,
                    service.goldEstimate(player, DropTable.Kind.EQUIPMENT, rarity, 0),
                    rarity == Rarity.RARE ? "<dark_gray>稀有掉出來未鑑定，隱藏前想清楚"
                            : rarity == Rarity.UNIQUE ? "<#ffb347>追逐級、頂級專屬（巔峰、極境、覺醒者、世界 Boss 的）不管這格，永遠會撿\n<dark_gray>這格是另外記的：換預設組合也會保留" : null));
        }
        set(slot(2, 0), rowLabel(Material.COMPARATOR, "進階規則", "照你的等級、手上的武器、身上的防具藏東西",
                "優先順序：傳奇只看稀有度（追逐級、頂級專屬一定撿）→ 等級差、不同類、不在只撿的基底裡一定不撿", "→ 剩下的看稀有度；被稀有度藏起來的，有好詞綴就撿回來"));
        final LootFilter.Rules rules = filter.rules();
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
        final int keep = rules.keepAffixTier();
        set(SLOT_KEEP_AFFIX_TIER, option(Material.ENCHANTED_BOOK, "保留好詞綴的裝備", keep == 0 ? "關" : "有 T" + keep + " 以上就撿", keep > 0,
                List.of("<gray>裝備身上只要有一條詞綴是 T1～T" + (keep == 0 ? "幾" : keep) + "（T1 最好），",
                        "<gray>被<white>稀有度</white><gray>藏起來的照樣撿（等級差、不同類、不在只撿的基底裡的不救）",
                        "<dark_gray>未鑑定的也算；珠寶也算（被珠寶的稀有度藏起來的照樣撿）；地圖、遺時星晶不管",
                        "<dark_gray>只有一兩階的詞綴（例如 +1 技能等級）不算",
                        keep == 0 ? "<dark_gray>例：開 T2、隱藏魔法裝備 → 有 T1、T2 詞綴的魔法裝備還是會撿" : ""),
                options(LootFilter.AFFIX_TIERS, keep, value -> value == 0 ? "關" : "T" + value + " 以上")));
        final boolean keepLike = filter.keepAffixLikeMine();
        set(SLOT_KEEP_AFFIX_LIKE, option(Material.WRITABLE_BOOK, "好詞綴也只撿同類", keepLike ? "開" : "關", keepLike,
                List.of("<gray>開了之後，「保留好詞綴的裝備」只保留跟你同類的：",
                        "<gray>武器跟手上同種類、防具與盾跟身上同系",
                        keep == 0 ? "<dark_gray>（「保留好詞綴的裝備」關著，這條沒差）" : "<dark_gray>不同類的照稀有度的規則藏起來"),
                List.of("<yellow>左鍵：" + (keepLike ? "關" : "開"))));
        set(slot(3, 0), rowLabel(Material.FILLED_MAP, "地圖與珠寶", "本來永遠會撿；太多的時候可以照稀有度不撿"));
        final int mapRarity = rules.mapRarity();
        set(SLOT_MAPS, option(Material.FILLED_MAP, "地圖", LootFilter.mapRarityName(mapRarity), mapRarity > 0,
                List.of("<gray>依地圖的稀有度：<white>普通（白）<gray>、<#8888ff>魔法（藍）<gray>、<#ffff77>稀有（黃）",
                        "<dark_gray>隱藏的地圖一樣換成金幣；傳奇地圖永遠會撿"),
                options(LootFilter.MAP_RARITIES, mapRarity, LootFilter::mapRarityName)));
        final int jewels = filter.jewelRarity();
        set(SLOT_JEWELS, option(Material.AMETHYST_CLUSTER, "珠寶", LootFilter.jewelRarityName(jewels), jewels > 0,
                List.of("<gray>依珠寶的稀有度：<white>普通<gray>、<#8888ff>魔法<gray>、<#ffff77>稀有",
                        "<dark_gray>隱藏的珠寶一樣換成金幣；傳奇珠寶永遠會撿"),
                options(LootFilter.JEWEL_RARITIES, jewels, LootFilter::jewelRarityName)));
        // 2026-10：遺時星晶（範圍珠寶，只在劫盜掉）另外設定：依稀有度，再加四種各自撿不撿
        final int timeLost = filter.timeLostRarity();
        set(SLOT_TIME_LOST, option(Material.ECHO_SHARD, "遺時星晶", LootFilter.timeLostRarityName(timeLost), timeLost > 0,
                List.of("<gray>遺時星晶（範圍珠寶，只在劫盜掉）依稀有度：<#8888ff>魔法<gray>、<#ffff77>稀有",
                        "<gray>跟左邊「珠寶」分開算：珠寶全部不撿，遺時星晶照這裡",
                        "<dark_gray>隱藏的一樣換成金幣；右邊四格可以挑哪幾種要撿"),
                options(LootFilter.TIME_LOST_RARITIES, timeLost, LootFilter::timeLostRarityName)));
        for (final LootFilter.TimeLost kind : LootFilter.TimeLost.values()) {
            final boolean hidden = filter.hidesTimeLost(kind);
            final Material material = switch (kind) {
                case RUBY -> Material.REDSTONE;
                case EMERALD -> Material.EMERALD;
                case SAPPHIRE -> Material.LAPIS_LAZULI;
                case DIAMOND -> Material.DIAMOND;
            };
            final ItemStack kindIcon = icon(material, (hidden ? "<red>✘ " : "<green>✔ ") + kind.displayName() + "：<white>" + (hidden ? "不撿" : "撿"),
                    hidden ? "<gray>這一種掉了換成金幣" : "<gray>這一種照左邊「遺時星晶」的稀有度設定撿",
                    "", "<yellow>左鍵：" + (hidden ? "改成撿" : "改成不撿"));
            set(SLOT_TIME_LOST_KINDS + kind.ordinal(), hidden ? offMark(kindIcon) : kindIcon);
        }
        set(slot(4, 0), rowLabel(Material.EMERALD, "只撿的基底", "有挑的話：裝備只撿這些基底", "稀有度照第一排；可以一直加"));
        final List<String> keptLore = new ArrayList<>();
        keptLore.add("<gray>挑了之後，<white>有挑的那幾類</white><gray>（武器／防具／盾與箭袋／飾品）");
        keptLore.add("<gray>普通、魔法、稀有裝備只撿這些基底；沒挑的類別照原本的規則");
        keptLore.add("<gray>要撿哪幾種稀有度照第一排（三格都開＝三種都撿）");
        keptLore.add("<dark_gray>例：只挑了兩種弓 → 其他武器不撿，防具、飾品照常");
        keptLore.add("<dark_gray>有挑的類別裡不在清單的一律不撿（好詞綴也不救）；等級差、同類同系照樣看");
        keptLore.add("<dark_gray>傳奇、珠寶、地圖不受影響；全部拿掉就恢復原本的規則");
        keptLore.add("");
        int listed = 0;
        for (final String id : filter.keepBases()) {
            if (listed++ >= 8) {
                keptLore.add("<dark_gray>…還有 " + (filter.keepBases().size() - 8) + " 種");
                break;
            }
            final tw.exilecore.item.ItemBase base = plugin.items().repository().base(id);
            keptLore.add("<green>✔ <white>" + ItemRenderer.escape(base == null ? id : base.name()));
        }
        if (filter.keepBases().isEmpty()) {
            keptLore.add("<dark_gray>還沒有（照上面的規則撿）");
        }
        keptLore.add("");
        keptLore.add("<yellow>左鍵：挑選基底");
        final ItemStack keepIcon = icon(Material.EMERALD, (filter.keepBases().isEmpty() ? "<white>" : "<green>")
                + "只撿的基底（" + filter.keepBases().size() + " 種）", keptLore);
        set(SLOT_KEEP_BASES, filter.keepBases().isEmpty() ? keepIcon : highlight(keepIcon));
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
            final long value = value(type);
            final int group = value < GROUP_BOUNDS[0] ? 0 : value < GROUP_BOUNDS[1] ? 1 : 2;
            byGroup.get(group).add(type);
        }
        // 2026-10：低價通貨有 10 種（微光石、光澤石、磨刀石、護甲碎片＋六種符文），以前只排得下 8 格，最後兩種不見了。
        // 2026-10-23：每組各自從新的一排開始排不下時（星火石不見了），改成一組接著一組排（標籤也佔一格），四排 36 格都用上
        final int[][] layout = layout(byGroup.stream().mapToInt(List::size).toArray());
        for (int group = 0; group < GROUP_COUNT; group++) {
            final List<CurrencyType> types = byGroup.get(group);
            types.sort((a, b) -> Long.compare(value(a), value(b)));
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
            lore.add("<dark_gray>照參考價值分（" + (group == 0 ? "低於 " + GROUP_BOUNDS[0] : group == 1 ? GROUP_BOUNDS[0] + "–" + (GROUP_BOUNDS[1] - 1)
                    : GROUP_BOUNDS[1] + " 以上") + " 金幣）");
            lore.add(state);
            lore.add("");
            if (types.size() > layout[group].length - 1) {
                lore.add("<red>還有 " + (types.size() - layout[group].length + 1) + " 種排不下（整組切換照樣會切到）");
            }
            lore.add("<yellow>右鍵：這一組" + (shown == 0 ? "全部改成會撿" : "全部隱藏"));   // 有會撿的就全部隱藏（跟 click 一樣）
            groupLabels[group] = gridSlot(layout[group][0]);
            set(groupLabels[group], icon(group == 0 ? Material.GLOWSTONE_DUST : group == 1 ? Material.GOLD_NUGGET : Material.NETHER_STAR,
                    "<#f5b942>【" + GROUP_NAMES[group] + "】", lore));
            for (int i = 0; i < Math.min(layout[group].length - 1, types.size()); i++) {
                final CurrencyType type = types.get(i);
                final int slot = gridSlot(layout[group][i + 1]);
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
                        // 只撿的基底、珠寶、遺時星晶、音效、護符、好詞綴也只撿同類的設定留著（2026-10：以前護符與好詞綴同類會被悄悄重設）
                        apply(LootFilter.of(preset).withKeepBases(filter.keepBases()).withJewelRarity(filter.jewelRarity()).withTimeLost(filter)
                                .withDropSounds(filter.dropSounds()).withExtras(filter));
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
                } else if (slot == SLOT_JEWELS) {
                    next.cycleJewelRarity(backwards);
                } else if (slot == SLOT_TIME_LOST) {
                    next.cycleTimeLostRarity(backwards);
                } else if (slot >= SLOT_TIME_LOST_KINDS && slot < SLOT_TIME_LOST_KINDS + LootFilter.TimeLost.values().length) {
                    next.toggleTimeLost(LootFilter.TimeLost.values()[slot - SLOT_TIME_LOST_KINDS]);
                } else if (slot == SLOT_KEEP_BASES) {
                    if (changed && !stale()) {
                        plugin.characters().save(player);
                        changed = false;
                    }
                    new KeepBasesMenu(plugin, player, service, () -> new LootFilterMenu(plugin, player, service).openOn("EQUIPMENT")).open();
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

    private int groupOf(final int slot) {
        for (int i = 0; i < GROUP_COUNT; i++) {
            if (groupLabels[i] == slot) {
                return i;
            }
        }
        return -1;
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
