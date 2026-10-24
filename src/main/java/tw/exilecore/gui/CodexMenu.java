package tw.exilecore.gui;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.item.Veiled;
import tw.exilecore.skill.GemData;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 圖鑑（/codex，或選單的「圖鑑」，2026-10）：只能看、不能拿。四個分頁：
 * <ul>
 *   <li>傳奇圖鑑：每件傳奇的樣子（數值是隨機擲的一個例子，括號是範圍）、誰會掉、最低物品等級；可以照部位篩選。</li>
 *   <li>技能寶石：每個技能的說明；有角色的話照你目前的屬性算（可以切換 Lv.1／Lv.20）。</li>
 *   <li>輔助寶石：效果、可以輔助哪些技能、階級、怎麼拿到。</li>
 *   <li>詞綴一覽：先選一種基底，列出它會出的前綴、後綴，每一階的數值範圍與需要的物品等級。</li>
 * </ul>
 * 預覽的東西不寫物品資料（拿不出去也不能用）；點擊一律取消（{@link Menu} 預設）。
 */
public final class CodexMenu extends Menu {

    private static final int PER_PAGE = CONTENT_END - CONTENT_START;
    private static final int SLOT_OPTION = 47;
    private static final int SLOT_INFO = 46;
    /** 2026-10：來源分類（傳奇：一般／追逐級；技能、輔助：切割／專屬）。 */
    private static final int SLOT_SOURCE = 51;
    private static final String[] UNIQUE_GRADES = {"全部", "傳奇", "追逐級"};
    private static final String[] GEM_SOURCES = {"全部", "切割", "專屬"};
    /** 傳奇的等級分類：0 全部、1 一般傳奇、2 追逐級。 */
    private int uniqueGrade;
    /** 技能的來源：0 全部、1 未切割寶石雕得出來、2 專屬掉落。 */
    private int skillSource;
    /** 輔助的來源：0 全部、1 未切割寶石切得出來、2 專屬掉落。 */
    private int supportSource;

    private static boolean uniqueGradeMatches(final UniqueItem unique, final int grade) {
        return grade == 0 || (grade == 2) == unique.chase();
    }

    private static boolean skillSourceMatches(final SkillDefinition definition, final int source) {
        return source == 0 || (source == 1) == definition.engravable();
    }

    private static boolean supportSourceMatches(final SupportDefinition support, final int source) {
        return source == 0 || (source == 1) == support.cuttable();
    }

    enum Tab {
        UNIQUES("傳奇圖鑑", Material.GOLDEN_HELMET, "每件傳奇的樣子、誰會掉"),
        SKILLS("技能寶石", Material.ENCHANTED_BOOK, "每個技能在做什麼、數值"),
        SUPPORTS("輔助寶石", Material.AMETHYST_SHARD, "輔助的效果、可以掛在哪些技能"),
        AFFIXES("詞綴一覽", Material.WRITABLE_BOOK, "每個部位會出哪些前綴、後綴");

        final String title;
        final Material icon;
        final String about;

        Tab(final String title, final Material icon, final String about) {
            this.title = title;
            this.icon = icon;
            this.about = about;
        }
    }

    /** 傳奇的部位篩選：null = 全部。 */
    private static final List<ItemSlot> SLOT_FILTERS = java.util.Arrays.asList(null, ItemSlot.MAIN_HAND, ItemSlot.OFF_HAND, ItemSlot.HEAD,
            ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET, ItemSlot.RING, ItemSlot.AMULET, ItemSlot.BELT, ItemSlot.JEWEL,
            ItemSlot.CHARM); // 2026-10-24：＋護符

    private Tab tab;
    private int page;
    private int slotFilter;
    private int gemLevel = 1;
    /** 技能的分類（2026-10）：SKILL_FILTERS 的第幾個。 */
    private int skillFilter;
    /** 輔助的分類（2026-10）：0 全部、1～3 第幾階、4 專屬掉落。 */
    private int supportFilter;
    private static final String[][] SKILL_FILTERS = {
            {"", "全部"},
            {tw.exilecore.stats.Tags.ATTACK, "攻擊"},
            {tw.exilecore.stats.Tags.SPELL, "法術"},
            {tw.exilecore.stats.Tags.MELEE, "近戰"},
            {tw.exilecore.stats.Tags.PROJECTILE, "投射物"},
            {tw.exilecore.stats.Tags.AREA, "範圍"},
            {tw.exilecore.stats.Tags.MINION, "召喚"},
            {tw.exilecore.stats.Tags.AURA + "," + tw.exilecore.stats.Tags.HERALD + "," + tw.exilecore.stats.Tags.WARCRY, "光環・戰吼"},
    };
    private static final String[] SUPPORT_FILTERS = {"全部", "第 1 階", "第 2 階", "第 3 階"};
    /** 輔助照能掛的技能標籤分類（2026-10）：標籤、名字；"*" = 什麼技能都能掛的。 */
    private static final String[][] SUPPORT_KINDS = {
            {"", "全部"},
            {tw.exilecore.stats.Tags.ATTACK, "攻擊"},
            {tw.exilecore.stats.Tags.MELEE, "近戰"},
            {tw.exilecore.stats.Tags.PROJECTILE, "投射物"},
            {tw.exilecore.stats.Tags.SPELL, "法術"},
            {tw.exilecore.stats.Tags.AREA, "範圍"},
            {tw.exilecore.stats.Tags.MINION, "召喚"},
            {"warcry,aura,herald,persistent,mark,totem,trap,bell,duration,cooldown,chain,corpse,movement,grenade", "戰吼・圖騰・其他"},
            {"*", "通用（什麼技能都能掛）"},
    };
    /** 輔助的標籤分類：SUPPORT_KINDS 的第幾個。 */
    private int supportKind;

    private static boolean supportKindMatches(final SupportDefinition support, final int kind) {
        if (kind == 0) {
            return true;
        }
        final String tags = SUPPORT_KINDS[kind][0];
        if ("*".equals(tags)) {
            return support.requires().isEmpty();
        }
        for (final String tag : tags.split(",")) {
            if (support.requires().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean skillMatches(final SkillDefinition definition, final int filter) {
        if (filter == 0) {
            return true;
        }
        for (final String tag : SKILL_FILTERS[filter][0].split(",")) {
            if (definition.tags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportMatches(final SupportDefinition support, final int filter) {
        return switch (filter) {
            case 0 -> true;
            default -> support.tier() == filter; // 2026-10：專屬掉落搬到「來源」分類
        };
    }

    /** 分類的按鈕：每一類有幾個、現在選哪個。 */
    private ItemStack filterButton(final Material material, final String title, final String[] names, final int[] counts, final int chosen) {
        final List<String> lore = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            lore.add((i == chosen ? "<#f5b942>▶ " : "<gray>　") + names[i] + " <dark_gray>(" + counts[i] + ")");
        }
        lore.add("");
        lore.add("<yellow>左鍵：下一個　右鍵：上一個");
        return icon(material, "<white>" + title + "：<#f5b942>" + names[chosen], lore);
    }
    /** 詞綴一覽選到的基底；null = 還在選基底。 */
    private String base;
    /** 詞綴一覽的搜尋（2026-10）：null = 沒在搜尋。 */
    private String search;
    /** 2026-10：傳奇、技能、輔助分頁的搜尋（名稱、說明、詞綴／效果的文字）。 */
    private String textQuery = "";
    private static final int SLOT_TEXT_SEARCH = 52;
    /** 上一次畫的時候總共幾頁（下一頁按鈕用）。 */
    private int pages = 1;
    /** 這一頁每一格對應的東西（基底代號），點了用。 */
    private final java.util.Map<Integer, String> targets = new java.util.HashMap<>();

    public CodexMenu(final ExileCore plugin, final Player player) {
        this(plugin, player, Tab.UNIQUES);
    }

    CodexMenu(final ExileCore plugin, final Player player, final Tab tab) {
        super(plugin, player, 6, "<dark_gray>圖鑑");
        this.tab = tab;
    }

    /** /codex：打開圖鑑。 */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("codex")
                .executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player) {
                        open(plugin, player);
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("關鍵字", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            if (context.getSource().getExecutor() instanceof Player player) {
                                openSearch(plugin, player, com.mojang.brigadier.arguments.StringArgumentType.getString(context, "關鍵字"));
                            } else {
                                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    /** 這個分頁符合搜尋的有幾個。 */
    private int matchCount(final Tab which) {
        return switch (which) {
            case UNIQUES -> uniqueEntries().size();
            case SKILLS -> skillEntries().size();
            case SUPPORTS -> supportEntries().size();
            case AFFIXES -> 0;
        };
    }

    /** /codex 關鍵字（2026-10）：直接搜尋傳奇、技能、輔助，打開第一個找得到的分頁。 */
    public static void openSearch(final ExileCore plugin, final Player player, final String query) {
        if (plugin.items() == null) {
            player.sendMessage(plugin.text().prefixed("<red>物品模組沒有啟用（看主控台的錯誤訊息）"));
            return;
        }
        final CodexMenu menu = new CodexMenu(plugin, player);
        menu.textQuery = query.trim();
        for (final Tab each : List.of(Tab.UNIQUES, Tab.SKILLS, Tab.SUPPORTS)) {
            if (menu.matchCount(each) > 0) {
                menu.tab = each;
                break;
            }
        }
        menu.open();
    }

    /** 物品模組沒開就打不開（傳奇、詞綴都在那裡）。 */
    public static void open(final ExileCore plugin, final Player player) {
        if (plugin.items() == null) {
            player.sendMessage(plugin.text().prefixed("<red>物品模組沒有啟用（看主控台的錯誤訊息）"));
            return;
        }
        new CodexMenu(plugin, player).open();
    }

    // ---- 畫 ----

    @Override
    protected void draw() {
        frame();
        targets.clear();
        final int[] tabs = tabSlots(Tab.values().length);
        for (final Tab each : Tab.values()) {
            final List<String> about = new ArrayList<>(List.of("<gray>" + each.about));
            if (!textQuery.isBlank() && each != Tab.AFFIXES) {
                about.add("<#55ffff>搜尋「" + ItemRenderer.escape(textQuery) + "」：" + matchCount(each) + " 個");
            }
            set(tabs[each.ordinal()], tab(each.icon, each.title, each == tab, about));
        }
        final List<ItemStack> entries = switch (tab) {
            case UNIQUES -> uniqueEntries();
            case SKILLS -> skillEntries();
            case SUPPORTS -> supportEntries();
            case AFFIXES -> search != null ? searchEntries() : base == null ? baseEntries() : affixEntries();
        };
        pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        for (int i = 0; i < PER_PAGE; i++) {
            final int index = page * PER_PAGE + i;
            if (index < entries.size()) {
                set(CONTENT_START + i, entries.get(index));
            }
        }
        if (entries.isEmpty()) {
            set(22, icon(Material.BARRIER, "<gray>這裡沒有東西", "<dark_gray>換一個篩選看看"));
        }
        if (page > 0) {
            set(SLOT_PREV, pageButton(false, page, pages));
        }
        if (page < pages - 1) {
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        drawOptions();
        if (tab != Tab.AFFIXES) {
            set(SLOT_TEXT_SEARCH, searchButton(textQuery, tab == Tab.UNIQUES ? "傳奇（名稱、詞綴、掉落來源：Boss、地圖、事件）" : tab == Tab.SKILLS ? "技能（名稱、說明、效果）" : "輔助（名稱、說明、效果）"));
        }
        set(SLOT_HELP, helpButton(List.of(
                "這裡只能看，東西拿不出來",
                "傳奇：數值是隨機擲的一個例子，括號裡是範圍；",
                "隱匿詞綴能揭露的清單先收著：右鍵（或對著它按 Q）展開、再按一次收起來",
                "技能：有角色的話照你目前的屬性與武器算",
                "詞綴：先點一種基底，再看它會出哪些詞綴；",
                "T1 最強，物品等級夠高才擲得到高階",
                "",
                "傳奇、技能、輔助：右下的望遠鏡搜尋名稱與說明（換分頁會留著）",
                "",
                "<dark_gray>/codex 也可以打開；/codex 關鍵字 直接搜尋")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private void drawOptions() {
        switch (tab) {
            case UNIQUES -> {
                final ItemSlot filter = SLOT_FILTERS.get(slotFilter);
                final List<String> lore = new ArrayList<>();
                for (int i = 0; i < SLOT_FILTERS.size(); i++) {
                    lore.add((i == slotFilter ? "<#f5b942>▶ " : "<gray>　") + slotName(SLOT_FILTERS.get(i)));
                }
                lore.add("");
                lore.add("<yellow>左鍵：下一個　右鍵：上一個");
                set(SLOT_OPTION, icon(Material.HOPPER, "<white>部位：<#f5b942>" + slotName(filter), lore));
                final int[] gradeCounts = new int[UNIQUE_GRADES.length];
                for (final UniqueItem unique : plugin.items().repository().uniques()) {
                    final ItemBase uniqueBase = plugin.items().repository().base(unique.base());
                    if (uniqueBase == null || filter != null && uniqueBase.slot() != filter) {
                        continue;
                    }
                    for (int i = 0; i < UNIQUE_GRADES.length; i++) {
                        if (uniqueGradeMatches(unique, i)) {
                            gradeCounts[i]++;
                        }
                    }
                }
                set(SLOT_SOURCE, filterButton(Material.GOLD_INGOT, "分類", UNIQUE_GRADES, gradeCounts, uniqueGrade));
            }
            case SKILLS -> {
                set(SLOT_OPTION, icon(Material.EXPERIENCE_BOTTLE, "<white>寶石等級：<#f5b942>Lv." + gemLevel,
                        "<gray>看 Lv.1、20、30、40 的數值",
                        "<dark_gray>（30、40 級＝20 級寶石再加 +10、+20 技能等級，裝備的「+x 技能等級」能做到，最高 40）",
                        "", "<yellow>左鍵：下一個　右鍵：上一個"));
                if (plugin.skills() != null) {
                    final String[] names = new String[SKILL_FILTERS.length];
                    final int[] counts = new int[SKILL_FILTERS.length];
                    for (int i = 0; i < SKILL_FILTERS.length; i++) {
                        names[i] = SKILL_FILTERS[i][1];
                        for (final SkillDefinition definition : plugin.skills().config().visible()) {
                            if (skillMatches(definition, i) && skillSourceMatches(definition, skillSource)) {
                                counts[i]++;
                            }
                        }
                    }
                    set(SLOT_INFO, filterButton(Material.HOPPER, "分類", names, counts, skillFilter));
                    final int[] sourceCounts = new int[GEM_SOURCES.length];
                    for (final SkillDefinition definition : plugin.skills().config().visible()) {
                        for (int i = 0; i < GEM_SOURCES.length; i++) {
                            if (skillMatches(definition, skillFilter) && skillSourceMatches(definition, i)) {
                                sourceCounts[i]++;
                            }
                        }
                    }
                    set(SLOT_SOURCE, filterButton(Material.SHEARS, "來源（切割：未切割技能寶石雕得出來）", GEM_SOURCES, sourceCounts, skillSource));
                }
            }
            case SUPPORTS -> {
                if (plugin.skills() != null) {
                    final int[] counts = new int[SUPPORT_FILTERS.length];
                    for (int i = 0; i < SUPPORT_FILTERS.length; i++) {
                        for (final SupportDefinition support : plugin.skills().config().supports()) {
                            if (supportMatches(support, i) && supportSourceMatches(support, supportSource) && supportKindMatches(support, supportKind)) {
                                counts[i]++;
                            }
                        }
                    }
                    set(SLOT_OPTION, filterButton(Material.HOPPER, "階級", SUPPORT_FILTERS, counts, supportFilter));
                    // 2026-10：照能掛的技能標籤分類
                    final String[] kindNames = new String[SUPPORT_KINDS.length];
                    final int[] kindCounts = new int[SUPPORT_KINDS.length];
                    for (int i = 0; i < SUPPORT_KINDS.length; i++) {
                        kindNames[i] = SUPPORT_KINDS[i][1];
                        for (final SupportDefinition support : plugin.skills().config().supports()) {
                            if (supportMatches(support, supportFilter) && supportSourceMatches(support, supportSource) && supportKindMatches(support, i)) {
                                kindCounts[i]++;
                            }
                        }
                    }
                    set(SLOT_INFO, filterButton(Material.NAME_TAG, "分類（能掛的技能）", kindNames, kindCounts, supportKind));
                    final int[] sourceCounts = new int[GEM_SOURCES.length];
                    for (final SupportDefinition support : plugin.skills().config().supports()) {
                        for (int i = 0; i < GEM_SOURCES.length; i++) {
                            if (supportMatches(support, supportFilter) && supportKindMatches(support, supportKind) && supportSourceMatches(support, i)) {
                                sourceCounts[i]++;
                            }
                        }
                    }
                    set(SLOT_SOURCE, filterButton(Material.SHEARS, "來源（切割：未切割輔助寶石切得出來）", GEM_SOURCES, sourceCounts, supportSource));
                }
            }
            case AFFIXES -> {
                if (base == null) {
                    // 2026-10：搜尋詞綴
                    set(SLOT_OPTION, icon(Material.SPYGLASS, "<white>搜尋詞綴" + (search == null ? "" : "：<#55ffff>" + ItemRenderer.escape(search)),
                            "<gray>輸入關鍵字（例如「生命」、「暴擊」、「火焰抗性」），列出：",
                            "<gray>・符合的詞綴，和哪些裝備基底擲得到",
                            "<gray>・詞綴裡有這段字的傳奇裝備",
                            "", "<yellow>左鍵：輸入關鍵字" + (search == null ? "" : "　右鍵：清掉搜尋")));
                }
                if (base != null) {
                    final ItemBase chosen = plugin.items().repository().base(base);
                    set(SLOT_BACK, backButton("基底列表"));
                    if (chosen != null) {
                        set(SLOT_INFO, glowing(icon(material(chosen.iconMaterial(), Material.IRON_SWORD), "<white>" + ItemRenderer.escape(chosen.name()),
                                "<gray>" + slotName(chosen.slot()) + "・" + tagText(chosen))));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static String slotName(final ItemSlot slot) {
        if (slot == null) {
            return "全部";
        }
        return switch (slot) {
            case MAIN_HAND -> "武器";
            case OFF_HAND -> "副手（盾、箭袋）";
            case HEAD -> "頭盔";
            case CHEST -> "胸甲";
            case LEGS -> "護腿";
            case FEET -> "靴子";
            default -> slot.displayName();
        };
    }

    // ---- 傳奇 ----

    /** 技能搜尋用的文字：名稱、說明、效果。 */
    public static String[] skillSearchText(final SkillDefinition definition) {
        final List<String> texts = new ArrayList<>(List.of(definition.name(), definition.description() == null ? "" : definition.description()));
        texts.addAll(skillEffectTexts(definition));
        return texts.toArray(String[]::new);
    }

    /**
     * 技能的效果文字（2026-10：圖鑑搜尋也找得到）：技能自己的修飾器，加上光環、狂怒、戰吼（自己與召喚物）、印記／詛咒給的效果
     * （照 20 級寫；搜尋只看字，數字不重要）。
     */
    public static List<String> skillEffectTexts(final SkillDefinition definition) {
        final List<String> texts = new ArrayList<>(tw.exilecore.skill.ModifierText.describeAll(definition.modifiers()));
        final List<tw.exilecore.skill.ScaledMod> scaled = new ArrayList<>();
        if (definition.aura() != null) {
            scaled.addAll(definition.aura().effects());
            if (definition.aura().fury() != null) {
                scaled.addAll(definition.aura().fury().effects());
            }
        }
        if (definition.warcry() != null) {
            scaled.addAll(definition.warcry().buff());
            scaled.addAll(definition.warcry().minionBuff());
        }
        if (definition.params() != null && definition.params().mark() != null) {
            scaled.addAll(definition.params().mark().effects());
        }
        for (final tw.exilecore.skill.ScaledMod mod : scaled) {
            try {
                texts.add(mod.describe(SkillLoadout.MAX_LEVEL, 1.0));
            } catch (final RuntimeException ignored) {
                // 寫不出說明的略過（只影響搜尋）
            }
        }
        return texts;
    }

    /** 輔助搜尋用的文字：名稱、說明、效果（2026-10：可以搜「封印」「連鎖」這種只寫在說明裡的字）。 */
    public static String[] supportSearchText(final SupportDefinition support) {
        final List<String> texts = new ArrayList<>(List.of(support.name(), support.description() == null ? "" : support.description()));
        texts.addAll(tw.exilecore.skill.ModifierText.describeAll(support.modifiers()));
        return texts.toArray(String[]::new);
    }

    /** 傳奇的說明裡，隱匿詞綴能揭露的清單超過幾條就先收起來（2026-10：全列會長到超出畫面）；右鍵（或 Q）展開。 */
    static final int VEILED_PREVIEW = 4;
    /** 這一頁：格子 → 傳奇（右鍵展開／收起能揭露的詞綴）。 */
    private final java.util.Map<Integer, UniqueItem> uniqueSlots = new java.util.HashMap<>();
    /** 展開中的傳奇（代號）：說明換成能揭露的詞綴的完整清單；null = 都收著。 */
    private String expandedUnique;

    private List<ItemStack> uniqueEntries() {
        uniqueSlots.clear();
        final ItemRepository repository = plugin.items().repository();
        final ItemSlot filter = SLOT_FILTERS.get(slotFilter);
        final List<UniqueItem> list = new ArrayList<>();
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase uniqueBase = repository.base(unique.base());
            if (uniqueBase == null || filter != null && uniqueBase.slot() != filter || !uniqueGradeMatches(unique, uniqueGrade)) {
                continue;
            }
            if (!textQuery.isBlank()) {
                final List<String> texts = new ArrayList<>(List.of(unique.name(), uniqueBase.name()));
                for (final String affixId : unique.affixIds()) {
                    final tw.exilecore.item.Affix affix = repository.affix(affixId);
                    if (affix != null) {
                        texts.add(affix.text());
                    }
                }
                texts.addAll(unique.flavour());
                texts.addAll(sourceTexts(unique)); // 2026-10-16：也能搜掉落來源（萬界、Boss 的名字、祭壇、追逐級…）
                if (!matches(textQuery, texts.toArray(String[]::new))) {
                    continue;
                }
            }
            list.add(unique);
        }
        list.sort(Comparator.<UniqueItem>comparingInt(unique -> unique.chase() ? 1 : 0)
                .thenComparingInt(unique -> repository.base(unique.base()).slot().ordinal())
                .thenComparingInt(UniqueItem::dropLevel));
        final List<ItemStack> stacks = new ArrayList<>();
        final int from = Math.max(0, page * PER_PAGE);
        for (int i = 0; i < list.size(); i++) {
            // 只做這一頁的（其他頁放一個空的位置，數量對就好）
            final boolean shown = i >= from && i < from + PER_PAGE;
            if (shown) {
                uniqueSlots.put(CONTENT_START + i - from, list.get(i));
            }
            stacks.add(shown ? uniqueStack(list.get(i)) : null);
        }
        return stacks;
    }

    /** 這件傳奇的隱匿詞綴能揭露的清單有沒有長到要收起來。 */
    private boolean veiledFolded(final UniqueItem unique) {
        final ItemBase uniqueBase = plugin.items().repository().base(unique.base());
        final List<AffixType> veiled = plugin.items().repository().uniqueVeiled(unique.id());
        if (uniqueBase == null || veiled.isEmpty()) {
            return false;
        }
        for (final AffixType type : new AffixType[] {AffixType.PREFIX, AffixType.SUFFIX}) {
            if (veiled.contains(type) && unveiledPool(uniqueBase, type).size() > VEILED_PREVIEW) {
                return true;
            }
        }
        return false;
    }

    /** 展開的樣子：只留名字，說明換成隱匿詞綴能揭露的完整清單（本來的詞綴先不顯示，才放得進畫面）。 */
    private ItemStack expandedStack(final UniqueItem unique, final ItemStack stack) {
        final ItemBase uniqueBase = plugin.items().repository().base(unique.base());
        final List<AffixType> veiled = plugin.items().repository().uniqueVeiled(unique.id());
        final List<String> lines = new ArrayList<>();
        lines.add("<#f5b942>━━ 隱匿詞綴能揭露的 ━━");
        lines.add("<gray>帶去營地找盜賊首領，每一條三選一");
        for (final AffixType type : new AffixType[] {AffixType.PREFIX, AffixType.SUFFIX}) {
            final long count = veiled.stream().filter(type::equals).count();
            if (count == 0) {
                continue;
            }
            final List<Affix> options = unveiledPool(uniqueBase, type);
            lines.add("");
            lines.add("<#c48bff>隱匿" + (type == AffixType.PREFIX ? "前綴" : "後綴") + (count > 1 ? " ×" + count : "") + "<gray>（" + options.size() + " 種）");
            for (final Affix option : options) {
                lines.add("<#c48bff>　· " + option.rangeText(option.tiers().getFirst().tier()));
            }
        }
        lines.add("");
        lines.add("<yellow>右鍵（或 Q）：收起來，看回這件傳奇");
        stack.editMeta(meta -> meta.lore(List.of()));
        return highlight(appendLore(stack, lines));
    }

    private ItemStack uniqueStack(final UniqueItem unique) {
        final int level = Math.max(1, unique.dropLevel());
        final ItemData data;
        try {
            data = plugin.items().generator().generateUnique(unique, level, new Random(unique.id().hashCode()));
        } catch (final RuntimeException e) {
            return icon(Material.BARRIER, "<red>" + ItemRenderer.escape(unique.name()), "<gray>做不出預覽：" + ItemRenderer.escape(e.getMessage()));
        }
        data.setIdentified(true);
        final ItemStack stack = plugin.items().renderer().create(data); // 只畫外觀，不寫物品資料
        if (unique.id().equals(expandedUnique) && veiledFolded(unique)) {
            return expandedStack(unique, stack);
        }
        final List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<#f5b942>━━ 圖鑑 ━━");
        if (unique.chase()) {
            lines.add("<#ff5555>✦ 追逐級：極稀有，全服廣播");
            lines.add("<gray>哪裡會掉：");
            lines.add("<white>　· 所有地圖 Boss（一般、具名、終局、極境）都有很小的機率");
            lines.add("<white>　· 怪物等級 75 以上的一般怪物（機率更小，約百萬分之一）");
            lines.add("<white>　· 僥倖石用在它的基底上（物品等級 75 以上），也有同樣極小的機率");
        }
        final List<String> sources = new ArrayList<>();
        for (final String source : unique.sources()) {
            final String text = sourceName(source);
            if (text != null && !sources.contains(text)) {
                sources.add(text);
            }
        }
        if (!sources.isEmpty()) {
            lines.add("<gray>哪裡會掉：");
            for (final String source : sources) {
                lines.add("<white>　· " + source);
            }
        }
        final ItemBase uniqueBase = plugin.items().repository().base(unique.base());
        // 2026-10：同一種（前綴／後綴）有好幾條時只列一次清單，寫「×2」（以前每一條都印一次一樣的清單）
        final List<AffixType> veiled = plugin.items().repository().uniqueVeiled(unique.id());
        for (final AffixType type : new AffixType[] {AffixType.PREFIX, AffixType.SUFFIX}) {
            final long count = veiled.stream().filter(type::equals).count();
            if (count == 0) {
                continue;
            }
            final String kind = type == AffixType.PREFIX ? "前綴" : "後綴";
            final List<Affix> options = uniqueBase == null ? List.of() : unveiledPool(uniqueBase, type);
            lines.add("<#c48bff>隱匿" + kind + (count > 1 ? " ×" + count : "") + "<gray>：帶去營地找盜賊首領，"
                    + (count > 1 ? "每一條各自" : "") + "從下面 " + options.size() + " 種裡三選一揭露");
            // 2026-10：清單長的先收起來（全列會長到超出畫面、上面的詞綴被擠出去）；右鍵（或 Q）展開
            if (options.size() > VEILED_PREVIEW) {
                lines.add("<yellow>　右鍵（或 Q）：展開這 " + options.size() + " 種");
            } else {
                for (final Affix option : options) {
                    lines.add("<#c48bff>　· " + option.rangeText(option.tiers().getFirst().tier()));
                }
            }
        }
        lines.add("<gray>物品等級 <white>" + level + "</white> 以上才會掉");
        lines.add("<dark_gray>預覽：數值是隨機擲的一個例子，括號裡是範圍");
        lines.add("<dark_gray>（基底的傷害、護甲照物品等級 " + level + " 算，越高越多）");
        return appendLore(stack, lines);
    }

    /** 搜尋用（2026-10-16）：這件傳奇的掉落來源——來源說明、地圖名字、那張圖 Boss 的名字；追逐級寫「追逐級」。 */
    private List<String> sourceTexts(final UniqueItem unique) {
        final List<String> texts = new ArrayList<>();
        if (unique.chase()) {
            texts.add("追逐級");
        }
        for (final String source : unique.sources()) {
            final String text = sourceName(source);
            if (text != null) {
                texts.add(text);
            }
            final String key = source.toLowerCase(Locale.ROOT);
            final String mapId = key.startsWith("uber_") ? key.substring("uber_".length()) : key;
            if (plugin.maps() != null) {
                final var map = plugin.maps().config().find(mapId);
                if (map != null && map.boss() != null && map.boss().name() != null) {
                    texts.add(map.boss().name());
                }
            }
        }
        return texts;
    }

    /** 掉落來源的說明；不用列的（chase：上面另外寫）回傳 null。 */
    private String sourceName(final String source) {
        return sourceLabel(plugin, source);
    }

    /** 掉落來源的說明（網頁圖鑑也用，2026-10-22）；不用列的（chase）回傳 null。 */
    public static String sourceLabel(final ExileCore plugin, final String source) {
        final String key = source.toLowerCase(Locale.ROOT);
        switch (key) {
            case "gamble":
                return "賭博（商人的未鑑定裝備）";
            case "chance":
                return "僥倖石（把普通裝備變成傳奇）";
            case "map_boss":
                return "任何地圖的 Boss";
            case "heist":
                return "劫盜的大金庫";
            case "final":
                return "終局 Boss";
            case "uber":
                return "極境 Boss";
            case "shrine":
                return "地圖事件：神龕";
            case "strongbox":
                return "地圖事件：寶箱";
            case "breach":
                return "地圖事件：裂隙";
            case "exile":
                return "地圖事件：流寇";
            case "abyss": // 2026-10-18
                return "地圖事件：深淵裂縫";
            case "safe":
                return "地圖事件：保險箱";
            case "ritual":
                return "祭壇";
            case "delirium":
                return "幻霧";
            case "chase":
                return null;
            default:
                break;
        }
        if (key.startsWith("uber_")) {
            return mapName(plugin, key.substring("uber_".length())) + "（極境）專屬";
        }
        return mapName(plugin, key) + "的 Boss";
    }

    private static String mapName(final ExileCore plugin, final String id) {
        if (plugin.maps() != null) {
            final var map = plugin.maps().config().find(id);
            if (map != null) {
                return map.name();
            }
        }
        return id;
    }

    // ---- 技能寶石 ----

    private List<ItemStack> skillEntries() {
        if (plugin.skills() == null) {
            return List.of(icon(Material.BARRIER, "<red>技能模組沒有啟用"));
        }
        final List<SkillDefinition> list = new ArrayList<>();
        for (final SkillDefinition definition : plugin.skills().config().visible()) {
            if (skillMatches(definition, skillFilter) && skillSourceMatches(definition, skillSource) && matches(textQuery, skillSearchText(definition))) {
                list.add(definition);
            }
        }
        list.sort(Comparator.comparingInt(SkillDefinition::tier));
        final PlayerState state = plugin.players().get(player);
        final boolean personal = state != null && state.isLoaded() && state.hasCharacter();
        final List<ItemStack> stacks = new ArrayList<>();
        final int from = page * PER_PAGE;
        for (int i = 0; i < list.size(); i++) {
            stacks.add(i >= from && i < from + PER_PAGE ? skillStack(list.get(i), personal) : null);
        }
        return stacks;
    }

    /** 換成技能、輔助自己的圖示（名稱、說明照寶石的；不認得的物品名稱就照舊）。 */
    private static ItemStack iconOf(final ItemStack gem, final String icon) {
        final Material material = icon == null ? null : Material.matchMaterial(icon);
        if (material == null || !material.isItem() || material.isAir()) {
            return gem;
        }
        final ItemStack stack = gem.withType(material);
        stack.resetData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA); // 回到那個物品原本的樣子
        stack.resetData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL);
        stack.setAmount(1);
        return stack;
    }

    private ItemStack skillStack(final SkillDefinition definition, final boolean personal) {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry(definition.id(), Math.min(SkillLoadout.MAX_LEVEL, gemLevel));
        // 2026-10：寶石在資源包裡都是同一顆球，圖鑑改用技能自己的圖示（skills.yml 的 icon）比較好認
        final ItemStack stack = tw.exilecore.skill.SkillArt.decorate(
                iconOf(stripData(plugin.skills().gems().create(GemData.skill(entry), 1)), definition.icon()), definition);
        final List<String> lines = new ArrayList<>();
        if (personal) {
            try {
                lines.addAll(gemLevel > SkillLoadout.MAX_LEVEL
                        ? plugin.skills().describeExtra(player, definition, gemLevel - SkillLoadout.MAX_LEVEL) // 2026-10：Lv.30、Lv.40
                        : plugin.skills().describe(player, definition, entry));
            } catch (final RuntimeException e) {
                lines.clear();
            }
        }
        if (lines.isEmpty()) {
            lines.add(tw.exilecore.skill.SkillService.headlineChips(definition));
            if (!definition.description().isBlank()) {
                lines.add("<gray>" + ItemRenderer.escape(definition.description()));
            }
            if (!definition.weapons().isEmpty()) {
                lines.add("<gray>需要" + definition.weaponText());
            }
        }
        lines.add("");
        lines.add("<#f5b942>━━ 圖鑑 ━━");
        if (!definition.engravable()) {
            lines.add("<#d4a017>專屬掉落：" + (definition.source() == null ? "特殊來源" : ItemRenderer.escape(sourceName(definition.source())))
                    + "<gray>，未切割技能寶石雕不出來");
        } else {
            lines.add("<gray>第 <white>" + definition.tier() + "</white> 階：未切割技能寶石等級 ≥ " + definition.tier() + " 才雕得出來");
        }
        lines.add(personal ? "<dark_gray>數值照你目前的屬性、武器算（Lv." + gemLevel + "、沒有輔助" + (gemLevel > SkillLoadout.MAX_LEVEL ? "；另外加你裝備的 +等級" : "") + "）"
                : "<dark_gray>選一個角色之後，這裡會照你的屬性算出數值");
        stack.editMeta(meta -> {
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (final String line : lines) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
        });
        return stack;
    }

    // ---- 輔助寶石 ----

    private List<ItemStack> supportEntries() {
        if (plugin.skills() == null) {
            return List.of(icon(Material.BARRIER, "<red>技能模組沒有啟用"));
        }
        final List<SupportDefinition> list = new ArrayList<>();
        for (final SupportDefinition support : plugin.skills().config().supports()) {
            if (supportMatches(support, supportFilter) && supportKindMatches(support, supportKind) && supportSourceMatches(support, supportSource)
                    && matches(textQuery, supportSearchText(support))) {
                list.add(support);
            }
        }
        list.sort(Comparator.<SupportDefinition>comparingInt(support -> support.cuttable() ? 0 : 1).thenComparingInt(SupportDefinition::tier));
        final List<ItemStack> stacks = new ArrayList<>();
        for (final SupportDefinition support : list) {
            final ItemStack stack = tw.exilecore.skill.SkillArt.decorateSupport( // 2026-10：輔助的分類圖示
                    iconOf(stripData(plugin.skills().gems().create(GemData.support(support.id()), 1)), support.icon()), support.id());
            final List<String> lines = new ArrayList<>();
            lines.add("");
            lines.add("<#f5b942>━━ 圖鑑 ━━");
            lines.add(support.cuttable()
                    ? "<gray>未切割輔助寶石（第 " + support.tier() + " 階以上）切得出來"
                    : "<#ff9f43>專屬掉落：" + ItemRenderer.escape(sourceName(support.source())));
            stacks.add(appendLore(stack, lines));
        }
        return stacks;
    }

    // ---- 詞綴 ----

    /** 這個基底會出的詞綴（一般掉落、通貨擲得到的；傳奇專屬、揭露、停用的不算）：前綴在前、後綴在後。 */
    private List<Affix> pool(final ItemBase itemBase) {
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : plugin.items().repository().affixes()) {
            if (!normalAffix(affix) || !affix.canSpawnOn(itemBase)) {
                continue;
            }
            list.add(affix);
        }
        list.sort(Comparator.comparingInt(affix -> affix.type() == AffixType.PREFIX ? 0 : 1));
        return list;
    }

    /** 這個基底能揭露出的詞綴（隱匿詞綴找盜賊首領三選一、或揭露石；一般掉落擲不到）：前綴在前、後綴在後。 */
    private List<Affix> unveiledPool(final ItemBase itemBase, final AffixType only) {
        return unveiledOptions(plugin, itemBase, only);
    }

    /** 這個基底能揭露出的詞綴（網頁圖鑑也用，2026-10-22）。 */
    public static List<Affix> unveiledOptions(final ExileCore plugin, final ItemBase itemBase, final AffixType only) {
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : plugin.items().repository().affixes()) {
            if (unveiledAffix(affix) && affix.canSpawnOn(itemBase) && (only == null || affix.type() == only)) {
                list.add(affix);
            }
        }
        list.sort(Comparator.comparingInt(affix -> affix.type() == AffixType.PREFIX ? 0 : 1));
        return list;
    }

    /** 一般掉落、通貨擲得到的詞綴（傳奇專屬、隱匿、揭露、腐化、停用的不算）。網頁圖鑑也用。 */
    public static boolean normalAffix(final Affix affix) {
        return !affix.id().startsWith(UniqueItem.AFFIX_PREFIX) && !Veiled.isUnveiled(affix.id()) && !Veiled.isVeiled(affix.id())
                && !affix.spawnTags().contains(ItemRepository.RETIRED_TAG) && !affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG)
                && !affix.tiers().isEmpty();
    }

    /** 蝕變石的腐化詞綴（2026-10-21，affixes/corrupted.yml）。網頁圖鑑也用。 */
    public static boolean corruptionAffix(final Affix affix) {
        return affix.spawnTags().contains(ItemRepository.CORRUPTION_TAG) && !affix.spawnTags().contains(ItemRepository.RETIRED_TAG)
                && !affix.tiers().isEmpty();
    }

    /** 這個基底腐化時可能加上的腐化詞綴。 */
    private List<Affix> corruptionPool(final ItemBase itemBase) {
        final List<Affix> list = new ArrayList<>();
        for (final Affix affix : plugin.items().repository().affixes()) {
            if (corruptionAffix(affix) && tw.exilecore.item.ItemGenerator.corruptionFits(affix, itemBase)) {
                list.add(affix);
            }
        }
        return list;
    }

    /** 揭露才拿得到的詞綴（隱匿詞綴找盜賊首領）。網頁圖鑑也用。 */
    public static boolean unveiledAffix(final Affix affix) {
        return Veiled.isUnveiled(affix.id()) && !affix.tiers().isEmpty() && !affix.spawnTags().contains(ItemRepository.RETIRED_TAG);
    }

    /** 圖鑑列的基底：裝備、珠寶，加上藥劑與護符（2026-10）；地圖不列。照部位、需要等級排。網頁圖鑑也用。 */
    public static List<ItemBase> codexBases(final ItemRepository repository) {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase each : repository.bases()) {
            if (!each.isMap()) {
                list.add(each);
            }
        }
        list.addAll(repository.flaskLikeBases());
        list.sort(Comparator.<ItemBase>comparingInt(each -> each.slot().ordinal()).thenComparingInt(each -> each.requirements().level()));
        return list;
    }

    private List<ItemBase> bases() {
        return codexBases(plugin.items().repository());
    }

    private List<ItemStack> baseEntries() {
        final List<ItemStack> stacks = new ArrayList<>();
        final List<ItemBase> list = bases();
        for (int i = 0; i < list.size(); i++) {
            final ItemBase each = list.get(i);
            int prefixes = 0;
            int suffixes = 0;
            for (final Affix affix : pool(each)) {
                if (affix.type() == AffixType.PREFIX) {
                    prefixes++;
                } else {
                    suffixes++;
                }
            }
            final int unveiled = unveiledPool(each, null).size();
            final int slot = CONTENT_START + i - page * PER_PAGE;
            if (slot >= CONTENT_START && slot < CONTENT_END) {
                targets.put(slot, each.id());
            }
            stacks.add(icon(material(each.iconMaterial(), Material.IRON_SWORD), "<white>" + ItemRenderer.escape(each.name()),
                    "<gray>" + slotName(each.slot()) + "・" + tagText(each),
                    "<#8888ff>前綴 " + prefixes + " 種<gray>・<#ffd27f>後綴 " + suffixes + " 種"
                            + (unveiled > 0 ? "<gray>・<#c48bff>揭露 " + unveiled + " 種" : ""),
                    "", "<yellow>左鍵：看會出哪些詞綴"));
        }
        return stacks;
    }

    /** 基底的種類（看得懂的標籤）。 */
    private static String tagText(final ItemBase each) {
        final List<String> parts = new ArrayList<>();
        if (each.hasTag("life_flask")) {
            parts.add("生命藥劑");
        } else if (each.hasTag("mana_flask")) {
            parts.add("魔力藥劑");
        }
        if (each.hasTag("two_hand")) {
            parts.add("雙手");
        } else if (each.hasTag("one_hand")) {
            parts.add("單手");
        }
        if (each.hasTag("str_armour")) {
            parts.add("護甲");
        }
        if (each.hasTag("dex_armour")) {
            parts.add("閃避");
        }
        if (each.hasTag("int_armour")) {
            parts.add("能量護盾");
        }
        if (each.hasTag("caster")) {
            parts.add("法術");
        }
        return parts.isEmpty() ? "需要等級 " + each.requirements().level() : String.join("／", parts) + "・需要等級 " + each.requirements().level();
    }

    private List<ItemStack> affixEntries() {
        final ItemBase chosen = plugin.items().repository().base(base);
        if (chosen == null) {
            base = null;
            return baseEntries();
        }
        final List<ItemStack> stacks = new ArrayList<>();
        final List<Affix> all = new ArrayList<>(pool(chosen));
        all.addAll(unveiledPool(chosen, null));
        all.addAll(corruptionPool(chosen)); // 2026-10-21：蝕變石的腐化詞綴
        for (final Affix affix : all) {
            final boolean prefix = affix.type() == AffixType.PREFIX;
            final boolean unveiled = Veiled.isUnveiled(affix.id());
            final List<AffixTier> tiers = affix.tiers();
            final List<String> lore = new ArrayList<>();
            lore.add(kindLabel(affix));
            if (corruptionAffix(affix)) {
                lore.add("<gray>一般掉落、通貨擲不到：用<white>蝕變石</white>腐化時約 25% 加上一條");
                lore.add("<gray>（不能再開符文孔的裝備約 50%）；一件只會有一條，不佔前綴、後綴的位子");
            }
            if (unveiled) {
                lore.add("<gray>一般掉落、通貨擲不到：裝備上的<white>隱匿" + (prefix ? "前綴" : "後綴") + "</white>帶去營地找盜賊首領，");
                lore.add("<gray>三個選項選一個（隱匿詞綴從劫盜來，揭露石也能加一條）");
            }
            int lowest = Integer.MAX_VALUE;
            for (final AffixTier tier : tiers) {
                lowest = Math.min(lowest, tier.ilvl());
            }
            lore.add("<gray>物品等級 <white>" + lowest + "</white> 以上開始出現");
            lore.add("");
            for (int i = 0; i < tiers.size(); i++) {
                final AffixTier tier = tiers.get(i);
                final String name = affix.nameFor(tier.tier());
                lore.add("<#f5b942>T" + (i + 1) + " <dark_gray>ilvl " + tier.ilvl() + (name.isBlank() ? "" : "・" + ItemRenderer.escape(name))
                        + " <white>" + affix.rangeText(tier.tier()));
            }
            stacks.add(icon(kindIcon(affix), kindColor(affix) + affix.rangeText(tiers.getFirst().tier()), lore));
        }
        return stacks;
    }

    /** 詞綴種類的標題（腐化、揭露、前綴、後綴）。 */
    private static String kindLabel(final Affix affix) {
        final boolean prefix = affix.type() == AffixType.PREFIX;
        if (corruptionAffix(affix)) {
            return "<#d20000>腐化詞綴（蝕變石）";
        }
        return Veiled.isUnveiled(affix.id()) ? "<#c48bff>揭露" + (prefix ? "前綴" : "後綴") : prefix ? "<#8888ff>前綴" : "<#ffd27f>後綴";
    }

    private static String kindColor(final Affix affix) {
        return corruptionAffix(affix) ? "<#d20000>" : Veiled.isUnveiled(affix.id()) ? "<#c48bff>"
                : affix.type() == AffixType.PREFIX ? "<#8888ff>" : "<#ffd27f>";
    }

    private static Material kindIcon(final Affix affix) {
        return corruptionAffix(affix) ? Material.REDSTONE : Veiled.isUnveiled(affix.id()) ? Material.ECHO_SHARD
                : affix.type() == AffixType.PREFIX ? Material.MAP : Material.PAPER;
    }

    /** 搜尋詞綴的結果：符合的詞綴（每一種一格，寫哪些基底擲得到），再來是詞綴裡有這段字的傳奇。 */
    private List<ItemStack> searchEntries() {
        final String key = search.toLowerCase(java.util.Locale.ROOT);
        uniqueSlots.clear(); // 2026-10：搜尋結果裡的傳奇也能右鍵（或 Q）展開隱匿詞綴的清單
        final java.util.Map<String, Affix> matched = new java.util.LinkedHashMap<>();
        final java.util.Map<String, List<ItemBase>> where = new java.util.HashMap<>();
        for (final ItemBase each : bases()) {
            final List<Affix> all = new ArrayList<>(pool(each));
            all.addAll(unveiledPool(each, null));
            all.addAll(corruptionPool(each)); // 2026-10-21
            for (final Affix affix : all) {
                if (!matches(affix, key)) {
                    continue;
                }
                matched.putIfAbsent(affix.id(), affix);
                where.computeIfAbsent(affix.id(), id -> new ArrayList<>()).add(each);
            }
        }
        final List<ItemStack> stacks = new ArrayList<>();
        for (final Affix affix : matched.values()) {
            final boolean prefix = affix.type() == AffixType.PREFIX;
            final boolean unveiled = Veiled.isUnveiled(affix.id());
            final List<AffixTier> tiers = affix.tiers();
            final List<String> lore = new ArrayList<>();
            lore.add(kindLabel(affix));
            lore.add("<gray>最高 T1：<white>" + affix.rangeText(tiers.getFirst().tier()));
            lore.add("<gray>最低：<white>" + affix.rangeText(tiers.getLast().tier()));
            lore.add("");
            // 擲得到的基底：照部位整理
            final java.util.Map<String, List<String>> bySlot = new java.util.LinkedHashMap<>();
            for (final ItemBase each : where.getOrDefault(affix.id(), List.of())) {
                bySlot.computeIfAbsent(slotName(each.slot()), name -> new ArrayList<>()).add(each.name());
            }
            lore.add("<gray>擲得到的裝備（" + where.getOrDefault(affix.id(), List.of()).size() + " 種基底）：");
            int lines = 0;
            for (final java.util.Map.Entry<String, List<String>> entry : bySlot.entrySet()) {
                if (lines++ >= 10) {
                    lore.add("<dark_gray>　…還有 " + (bySlot.size() - 10) + " 個部位");
                    break;
                }
                final List<String> names = entry.getValue();
                lore.add("<white>　" + entry.getKey() + "<gray>：" + ItemRenderer.escape(String.join("、", names.subList(0, Math.min(4, names.size()))))
                        + (names.size() > 4 ? "<dark_gray> 等 " + names.size() + " 種" : ""));
            }
            stacks.add(icon(kindIcon(affix), kindColor(affix) + affix.rangeText(tiers.getFirst().tier()), lore));
        }
        // 詞綴裡有這段字的傳奇
        final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer plain =
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            // 展開中的那一件：說明換成揭露清單了，要用收起來的樣子比對（不然一展開就從結果裡消失）
            final boolean expanded = unique.id().equals(expandedUnique);
            final String keep = expandedUnique;
            if (expanded) {
                expandedUnique = null;
            }
            final ItemStack stack = uniqueStack(unique);
            expandedUnique = keep;
            if (stack == null || stack.getItemMeta() == null || stack.getItemMeta().lore() == null) {
                continue;
            }
            boolean hit = false;
            for (final net.kyori.adventure.text.Component line : stack.getItemMeta().lore()) {
                final String text = plain.serialize(line);
                if (text.contains("━━ 圖鑑 ━━")) {
                    break; // 下面是圖鑑自己的說明（哪裡會掉…），不算
                }
                if (text.toLowerCase(java.util.Locale.ROOT).contains(key)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                final int slot = CONTENT_START + stacks.size() - page * PER_PAGE;
                if (slot >= CONTENT_START && slot < CONTENT_END) {
                    uniqueSlots.put(slot, unique);
                }
                stacks.add(expanded ? uniqueStack(unique) : stack);
            }
        }
        return stacks;
    }

    private static boolean matches(final Affix affix, final String key) {
        for (final AffixTier tier : affix.tiers()) {
            if (affix.rangeText(tier.tier()).toLowerCase(java.util.Locale.ROOT).contains(key)
                    || affix.nameFor(tier.tier()).toLowerCase(java.util.Locale.ROOT).contains(key)) {
                return true;
            }
        }
        return false;
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (click == ClickType.DOUBLE_CLICK) {
            return;
        }
        final int[] tabs = tabSlots(Tab.values().length);
        for (final Tab each : Tab.values()) {
            if (tabs[each.ordinal()] == slot && each != tab) {
                tab = each;
                page = 0;
                base = null;
                // 2026-10：換分頁搜尋留著（傳奇、技能、輔助一起找；分頁上寫各有幾個）
                redraw();
                return;
            }
        }
        if (slot == SLOT_PREV && page > 0) {
            page--;
            redraw();
        } else if (slot == SLOT_NEXT && page < pages - 1) {
            page++;
            redraw();
        } else if (slot == SLOT_CLOSE_BAR) {
            goBack();
        } else if (slot == SLOT_TEXT_SEARCH && tab != Tab.AFFIXES) {
            searchClick(click, "", textQuery, text -> {
                textQuery = text;
                page = 0;
            });
        } else if (slot == SLOT_OPTION && tab == Tab.AFFIXES && base == null) {
            if (click.isRightClick() && search != null) {
                search = null;
                page = 0;
                redraw();
                return;
            }
            final CodexMenu self = this;
            plugin.dialogs().text(player, "<dark_gray>搜尋詞綴", List.of("<gray>詞綴的說明含這段文字的都會列出來",
                            "<dark_gray>例如「生命」、「暴擊」、「火焰抗性」、「移動速度」"), "關鍵字", search == null ? "" : search, 30,
                    text -> {
                        self.search = text.isBlank() ? null : text.trim();
                        self.page = 0;
                        self.open();
                    }, self::open);
        } else if (slot == SLOT_BACK && tab == Tab.AFFIXES && base != null) {
            base = null;
            page = 0;
            redraw();
        } else if (slot == SLOT_SOURCE && tab != Tab.AFFIXES) {
            final int step = click.isRightClick() ? 2 : 1; // 三個選項：右鍵 = 往回一個
            switch (tab) {
                case UNIQUES -> uniqueGrade = (uniqueGrade + step) % UNIQUE_GRADES.length;
                case SKILLS -> skillSource = (skillSource + step) % GEM_SOURCES.length;
                case SUPPORTS -> supportSource = (supportSource + step) % GEM_SOURCES.length;
                default -> {
                }
            }
            page = 0;
            redraw();
        } else if (slot == SLOT_OPTION && tab == Tab.UNIQUES) {
            final int size = SLOT_FILTERS.size();
            slotFilter = (slotFilter + (click.isRightClick() ? size - 1 : 1)) % size;
            page = 0;
            redraw();
        } else if (slot == SLOT_INFO && tab == Tab.SKILLS) {
            skillFilter = (skillFilter + (click.isRightClick() ? SKILL_FILTERS.length - 1 : 1)) % SKILL_FILTERS.length;
            page = 0;
            redraw();
        } else if (slot == SLOT_INFO && tab == Tab.SUPPORTS) {
            supportKind = (supportKind + (click.isRightClick() ? SUPPORT_KINDS.length - 1 : 1)) % SUPPORT_KINDS.length;
            page = 0;
            redraw();
        } else if (slot == SLOT_OPTION && tab == Tab.SUPPORTS) {
            supportFilter = (supportFilter + (click.isRightClick() ? SUPPORT_FILTERS.length - 1 : 1)) % SUPPORT_FILTERS.length;
            page = 0;
            redraw();
        } else if (slot == SLOT_OPTION && tab == Tab.SKILLS) {
            final int[] levels = {1, SkillLoadout.MAX_LEVEL, 30, 40}; // 2026-10：多了 30、40
            int index = 0;
            for (int i = 0; i < levels.length; i++) {
                if (levels[i] == gemLevel) {
                    index = i;
                }
            }
            gemLevel = levels[(index + (click.isRightClick() ? levels.length - 1 : 1)) % levels.length];
            redraw();
        } else if ((tab == Tab.UNIQUES || tab == Tab.AFFIXES && search != null)
                && (click.isRightClick() || click == ClickType.DROP || click == ClickType.CONTROL_DROP) && uniqueSlots.containsKey(slot)) {
            // 2026-10：隱匿詞綴的清單預設收著，右鍵（或對著它按 Q）展開、再按一次收起來
            final UniqueItem unique = uniqueSlots.get(slot);
            if (unique.id().equals(expandedUnique)) {
                expandedUnique = null;
            } else if (veiledFolded(unique)) {
                expandedUnique = unique.id();
            } else {
                return;
            }
            redraw();
        } else if (tab == Tab.AFFIXES && base == null && targets.containsKey(slot)) {
            base = targets.get(slot);
            page = 0;
            redraw();
        }
    }

    /** 預覽用：拿掉物品上的插件資料（拿不出去，就算拿出去也只是一個沒有用的圖示）。 */
    private static ItemStack stripData(final ItemStack stack) {
        stack.editMeta(meta -> {
            final var container = meta.getPersistentDataContainer();
            for (final NamespacedKey key : new ArrayList<>(container.getKeys())) {
                container.remove(key);
            }
        });
        return stack;
    }
}
