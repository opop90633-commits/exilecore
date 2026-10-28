package tw.exilecore.skill;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 雕琢（右鍵未切割技能寶石，或在隨身背包的通貨頁 Shift＋右鍵）：統一版面。
 * <ul>
 *   <li>最上面一排中間：用的是哪一顆（還有幾顆）。</li>
 *   <li>中間三排：能雕的新技能（這顆雕得出來的排前面，灰色的要更高等級的寶石；多了翻頁）。</li>
 *   <li>第五排：技能面板上的技能，點一下直接升到未切割寶石的等級（2026-10-27 照 PoE2；這次之前升一級）。</li>
 *   <li>最下面一排：返回（從隨身背包來的）、翻頁、說明、關閉。</li>
 * </ul>
 * 每次點都重新確認那顆寶石還在（手上那一格，或通貨袋裡那一種）。
 */
final class EngraveMenu extends Menu {

    private static final int SLOT_GEM = 4;
    private static final int NEW_START = CONTENT_START;
    private static final int NEW_SLOTS = 27;
    private static final int UPGRADE_START = 36;

    private final SkillService service;
    private final GemService.UncutSource source;
    private final int level;
    /** 全部的技能：雕得出來的在前（各自照檔案順序）。 */
    private final List<SkillDefinition> all = new ArrayList<>();
    /** 這一頁顯示的。 */
    private final List<SkillDefinition> shown = new ArrayList<>();
    /** 第五排的格子 → 技能面板的位置（面板有三頁 27 格，這裡只排有技能的，最多 9 個）。 */
    private final List<Integer> upgradePositions = new ArrayList<>();
    private int page;
    /**
     * 最上面一排的分類（2026-10）：格子 → {分類代號, 名稱, 圖示}；第 0 格「全部」。
     * 2026-10 改準一點（{@link #inCategory}）：以前只看標籤，拿弓選「投射物」看不到萬箭齊落、召霆矢、雷樁矢、瘴域
     * （落在地上的，沒有投射物標籤），選「攻擊」看不到霜鏃、連珠（弓的增益，算法術）。
     */
    private static final int[] FILTER_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8};
    private static final String[][] FILTERS = {
            {"", "全部", "NETHER_STAR"},
            {"attack", "攻擊・武器技能", "IRON_SWORD"},
            {"spell", "法術（不限武器）", "BLAZE_ROD"},
            {"melee", "近戰", "IRON_AXE"},
            {"ranged", "投射物・弓弩", "ARROW"},
            {"area", "範圍", "FIRE_CHARGE"},
            {"minion", "召喚", "ZOMBIE_HEAD"},
            {"buff", "光環・戰吼・增益", "BEACON"},
    };
    /** 遠程武器（弓、弩的技能都算「投射物・弓弩」）。 */
    private static final java.util.Set<String> RANGED_WEAPONS = java.util.Set.of(tw.exilecore.stats.Tags.BOW, tw.exilecore.stats.Tags.CROSSBOW);
    /** 近戰武器（錘、長棍、獸魂符、「近戰武器」的技能都算「近戰」；矛會投擲，照標籤）。 */
    private static final java.util.Set<String> MELEE_WEAPONS = java.util.Set.of("mace", tw.exilecore.stats.Tags.QUARTERSTAFF, "talisman",
            tw.exilecore.stats.Tags.MELEE);

    /** 技能在不在這個分類裡（分類代號見 {@link #FILTERS}）。 */
    static boolean inCategory(final String category, final SkillDefinition definition) {
        final java.util.Set<String> tags = definition.tags();
        final java.util.Set<String> weapons = definition.weapons();
        return switch (category) {
            case "" -> true;
            // 有攻擊標籤的，加上要拿特定武器才能用的（弓的霜鏃、連珠這種增益）
            case "attack" -> tags.contains(tw.exilecore.stats.Tags.ATTACK) || !weapons.isEmpty();
            // 不限武器的法術（要拿武器的法術放在「攻擊・武器技能」）
            case "spell" -> tags.contains(tw.exilecore.stats.Tags.SPELL) && weapons.isEmpty();
            case "melee" -> tags.contains(tw.exilecore.stats.Tags.MELEE) || !java.util.Collections.disjoint(weapons, MELEE_WEAPONS);
            case "ranged" -> tags.contains(tw.exilecore.stats.Tags.PROJECTILE) || !java.util.Collections.disjoint(weapons, RANGED_WEAPONS);
            case "area" -> tags.contains(tw.exilecore.stats.Tags.AREA);
            case "minion" -> tags.contains(tw.exilecore.stats.Tags.MINION);
            case "buff" -> tags.contains(tw.exilecore.stats.Tags.AURA) || tags.contains(tw.exilecore.stats.Tags.HERALD)
                    || tags.contains(tw.exilecore.stats.Tags.WARCRY) || definition.mechanic() == Mechanic.GUARD;
            default -> tags.contains(category);
        };
    }
    /** 現在選的分類（FILTERS 的第幾個）。 */
    private int filter;

    /** 2026-10：只看手上武器能用的（預設開）。 */
    private static final int SLOT_WEAPON = 47;
    /** 2026-10：武器種類（左鍵下一種、右鍵上一種）：手上武器能用的、全部、共用（不限武器）、各種武器。 */
    private static final String[][] WEAPONS = {
            {"hand", "手上武器能用的", "IRON_SWORD"},
            {"all", "全部", "NETHER_STAR"},
            {"shared", "共用（不限武器）", "BOOK"},
            {"bow", "弓", "BOW"},
            {"crossbow", "弩", "CROSSBOW"},
            {"mace", "錘", "MACE"},
            {"quarterstaff", "長棍", "STICK"},
            {"spear", "矛", "TRIDENT"},
            {"talisman", "獸魂符", "BONE"},
    };
    private int weapon;
    /** 排序用：共用的排最前面，再照這個順序的武器。 */
    private static final List<String> WEAPON_ORDER = List.of("bow", "crossbow", "mace", "quarterstaff", "spear", "talisman");

    private static int weaponRank(final SkillDefinition definition) {
        if (definition.weapons().isEmpty()) {
            return 0;
        }
        int best = WEAPON_ORDER.size() + 1;
        for (final String w : definition.weapons()) {
            final int i = WEAPON_ORDER.indexOf(w);
            if (i >= 0) {
                best = Math.min(best, i + 1);
            }
        }
        return best;
    }

    private static final java.util.Comparator<SkillDefinition> ORDER = java.util.Comparator.comparingInt(EngraveMenu::weaponRank)
            .thenComparingInt(SkillDefinition::tier).thenComparing(SkillDefinition::id);
    /** 2026-10：傷害類型（左鍵下一種、右鍵上一種）。 */
    private static final int SLOT_ELEMENT = 51;
    /** 搜尋（2026-10）：技能名稱與說明。 */
    private static final int SLOT_SEARCH = 52;
    private String query = "";
    private static final String[][] ELEMENTS = {
            {"", "全部傷害類型", "NETHER_STAR"},
            {tw.exilecore.stats.Tags.PHYSICAL, "物理", "IRON_INGOT"},
            {tw.exilecore.stats.Tags.FIRE, "火焰", "BLAZE_POWDER"},
            {tw.exilecore.stats.Tags.COLD, "冰冷", "SNOWBALL"},
            {tw.exilecore.stats.Tags.LIGHTNING, "閃電", "GLOWSTONE_DUST"},
            {tw.exilecore.stats.Tags.CHAOS, "混沌", "FERMENTED_SPIDER_EYE"},
    };
    private int element;

    private boolean matches(final SkillDefinition definition) {
        switch (WEAPONS[weapon][0]) {
            case "hand" -> {
                if (!definition.usableWith(handTags)) {
                    return false;
                }
            }
            case "all" -> {
            }
            case "shared" -> {
                if (!definition.weapons().isEmpty()) {
                    return false;
                }
            }
            default -> {
                if (!definition.weapons().contains(WEAPONS[weapon][0])) {
                    return false;
                }
            }
        }
        if (element > 0 && !definition.tags().contains(ELEMENTS[element][0])) {
            return false;
        }
        if (!Menu.matches(query, tw.exilecore.gui.CodexMenu.skillSearchText(definition))) {
            return false;
        }
        return inCategory(FILTERS[filter][0], definition);
    }

    /** 這次畫面用的手上武器標籤（{@link #handTags()}）。 */
    private java.util.Set<String> handTags = java.util.Set.of();

    /**
     * 手上武器的標籤（「手上武器能用的」篩選用）。2026-10：以前只看角色生效中的武器——弓的需求（等級、敏捷）還不夠時
     * 弓沒生效、標籤是空的，只剩共用跟近戰的技能，弓的技能全部不見。現在沒生效時照主手那把的種類篩。
     */
    private java.util.Set<String> handTags() {
        final PlayerState state = plugin.players().get(player);
        if (!state.weaponTags().isEmpty()) {
            return state.weaponTags();
        }
        final tw.exilecore.item.ItemBase base = mainHandBase();
        return base != null && base.isWeapon() ? base.tags() : java.util.Set.of();
    }

    /** 主手那把的基底（沒拿、不是本服的物品回傳 null）。 */
    private tw.exilecore.item.ItemBase mainHandBase() {
        final ItemStack stack = tw.exilecore.character.Gear.mainHand(plugin.players().get(player));
        return stack == null || stack.isEmpty() ? null
                : plugin.items().read(stack).map(data -> plugin.items().repository().base(data.base())).orElse(null);
    }

    EngraveMenu(final ExileCore plugin, final Player player, final SkillService service, final GemService.UncutSource source, final int level) {
        super(plugin, player, 6, "<dark_gray>雕琢技能寶石 Lv." + level);
        this.service = service;
        this.source = source;
        this.level = level;
    }

    private int pageCount() {
        return Math.max(1, (all.size() + NEW_SLOTS - 1) / NEW_SLOTS);
    }

    @Override
    protected void draw() {
        frame();
        handTags = handTags();
        all.clear();
        for (final SkillDefinition definition : service.config().visible()) {
            if (definition.engravable() && definition.tier() <= level && matches(definition)) {
                all.add(definition);
            }
        }
        all.sort(ORDER); // 2026-10：共用的在前，再照武器、階級排
        final int engravable = all.size();
        final List<SkillDefinition> later = new ArrayList<>();
        for (final SkillDefinition definition : service.config().visible()) {
            if (definition.engravable() && definition.tier() > level && matches(definition)) {
                later.add(definition);
            }
        }
        later.sort(ORDER);
        all.addAll(later);
        page = Math.max(0, Math.min(page, pageCount() - 1));

        final ItemStack held = service.gems().display(player, source);
        final int count = service.gems().count(player, source);
        set(SLOT_GEM, held == null ? icon(Material.BARRIER, "<red>" + (source.fromPouch() ? "通貨袋裡的這種寶石用完了" : "手上的寶石不見了"))
                : appendLore(held.asOne(), "", "<gray>" + (source.fromPouch() ? "通貨袋裡" : "手上") + "還有 <white>" + count + "</white> 顆",
                        "<gray>這顆雕得出 <white>" + engravable + "</white> 種技能",
                        "<gray>中間三排：雕新技能　第五排：升級面板上的技能"));

        for (int i = 0; i < FILTERS.length; i++) {
            int total = 0;
            for (final SkillDefinition definition : service.config().visible()) {
                final int keep = filter;
                filter = i;
                if (definition.engravable() && matches(definition)) {
                    total++;
                }
                filter = keep;
            }
            final ItemStack tab = icon(material(FILTERS[i][2], Material.BOOK), (i == filter ? "<#f5b942>▶ " : "<white>") + FILTERS[i][1],
                    "<gray>" + total + " 種技能", i == filter ? "<green>顯示中" : "<yellow>點一下：只看" + FILTERS[i][1]);
            set(FILTER_SLOTS[i], i == filter ? highlight(tab) : tab);
        }
        shown.clear();
        for (int i = page * NEW_SLOTS; i < all.size() && shown.size() < NEW_SLOTS; i++) {
            shown.add(all.get(i));
        }
        final PlayerState state = plugin.players().get(player);
        for (int i = 0; i < shown.size(); i++) {
            set(NEW_START + i, newSkillIcon(state, shown.get(i)));
        }
        final SkillLoadout loadout = state.skills();
        upgradePositions.clear();
        for (int position = 0; position < SkillLoadout.MAX_SKILLS && upgradePositions.size() < SkillLoadout.PAGE_SIZE; position++) {
            if (loadout.skill(position) != null) {
                upgradePositions.add(position);
            }
        }
        for (int i = 0; i < SkillLoadout.PAGE_SIZE; i++) {
            set(UPGRADE_START + i, upgradeIcon(state, i < upgradePositions.size() ? loadout.skill(upgradePositions.get(i)) : null));
        }

        if (source.fromPouch()) {
            set(SLOT_BACK, backButton("隨身背包"));
        }
        final java.util.Set<String> weaponTags = state.weaponTags();
        final List<String> weaponLore = new ArrayList<>();
        weaponLore.add("<gray>手上：<white>" + weaponName(weaponTags));
        if (weaponTags.isEmpty() && !handTags.isEmpty()) {
            weaponLore.add("<red>這把的需求還不夠，現在沒生效<gray>（照它的種類篩選）");
        }
        weaponLore.add("");
        for (int w = 0; w < WEAPONS.length; w++) {
            weaponLore.add((w == weapon ? "<#f5b942>▶ " : "<gray>  ") + WEAPONS[w][1]);
        }
        weaponLore.add("");
        weaponLore.add("<dark_gray>不限武器的（法術、光環這類）放在「共用」；排序也是共用的在前");
        weaponLore.add("<yellow>左鍵：下一種　右鍵：上一種");
        final ItemStack weaponTab = icon(material(WEAPONS[weapon][2], Material.IRON_SWORD), "<white>武器：<#f5b942>" + WEAPONS[weapon][1], weaponLore);
        set(SLOT_WEAPON, weapon != 1 ? highlight(weaponTab) : weaponTab);
        final List<String> elementLore = new ArrayList<>();
        for (int e = 0; e < ELEMENTS.length; e++) {
            elementLore.add((e == element ? "<#f5b942>▶ " : "<gray>  ") + ELEMENTS[e][1]);
        }
        elementLore.add("");
        elementLore.add("<yellow>左鍵：下一種　右鍵：上一種");
        final ItemStack elementTab = icon(material(ELEMENTS[element][2], Material.BOOK), "<white>傷害類型：<#f5b942>" + ELEMENTS[element][1], elementLore);
        set(SLOT_ELEMENT, element == 0 ? elementTab : highlight(elementTab));
        set(SLOT_SEARCH, searchButton(query, "技能"));
        if (pageCount() > 1) {
            set(SLOT_PREV, page > 0 ? pageButton(false, page, pageCount()) : bar());
            set(SLOT_NEXT, page < pageCount() - 1 ? pageButton(true, page, pageCount()) : bar());
        }
        set(SLOT_HELP, helpButton(List.of(
                "用一顆未切割技能寶石 Lv." + level + "：",
                "<white>雕新技能</white>：中間三排點一下，做成 Lv." + level + " 的技能寶石",
                "　（面板有空格就直接放進去，不然放進背包）",
                "<white>升級</white>：第五排是技能面板上的技能，點一下直接升到 Lv." + level,
                "　（技能要比 Lv." + level + " 低；角色等級也要夠）",
                "",
                "<dark_gray>技能面板（/skills）裡也可以直接升級")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack newSkillIcon(final PlayerState state, final SkillDefinition definition) {
        if (definition.tier() > level) {
            return icon(Material.GRAY_DYE, "<gray>" + ItemRenderer.escape(definition.name()),
                    "<red>要 Lv." + definition.tier() + " 以上的未切割技能寶石才能雕");
        }
        final List<String> lore = new ArrayList<>(service.describe(player, definition, new SkillLoadout.Entry(definition.id(), level)));
        lore.add("");
        // 2026-10-28 照 PoE2：同一個技能可以放好幾顆，只有一個角色一顆的常駐、宿主會放進背包（這次之前面板上有就放進背包）
        lore.add(GemCopies.placeProblem(service.config(), state.skills(), new SkillLoadout.Entry(definition.id(), level), -1) != null
                ? "<gray>面板上已經有這個技能（常駐、宿主一個角色一顆）：做好的寶石會放進背包"
                : state.skills().find(definition.id()) >= 0 || state.skills().findInner(definition.id()) >= 0
                ? "<gray>已經有一顆了，做好的這顆也直接放進技能面板（同一個技能可以放好幾顆，各自掛輔助）"
                : "<gray>做好直接放進技能面板（面板滿了就放進背包）");
        lore.add("<yellow>點一下：做成 Lv." + level + " 的技能寶石");
        return SkillArt.decorate(icon(material(definition.icon(), Material.BOOK),
                "<#e0c080>雕出：" + ItemRenderer.escape(definition.name()) + " <gray>Lv." + level, lore), definition);
    }

    private ItemStack upgradeIcon(final PlayerState state, final SkillLoadout.Entry entry) {
        if (entry == null) {
            return uiIcon("slot_skill", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>技能面板的空格", "<dark_gray>（升級用：面板上的技能會列在這一排）");
        }
        final SkillDefinition definition = service.config().skill(entry.skillId());
        final String name = definition == null ? entry.skillId() : definition.name();
        final String problem = service.gems().levelUpProblem(state, entry, level);
        final List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>技能面板上的技能");
        if (problem == null) {
            lore.add("<gray>Lv." + entry.level() + " → <white>Lv." + GemService.levelUpTarget(entry, level)); // 2026-10-27 照 PoE2：直接升到未切割寶石的等級
            lore.add("");
            lore.add("<yellow>點一下：升到 Lv." + GemService.levelUpTarget(entry, level) + "（用掉一顆）");
        } else {
            lore.add("<red>" + ItemRenderer.escape(problem));
        }
        final ItemStack stack = icon(definition == null ? Material.BARRIER : material(definition.icon(), Material.BOOK),
                (problem == null ? "<#6fe3d0>⬆ 升級：" : "<gray>") + ItemRenderer.escape(name) + " <gray>Lv." + entry.level(), lore);
        SkillArt.decorate(stack, definition);
        return problem == null ? targetHighlight(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK && source.fromPouch()) {
            backToBag();
            return;
        }
        if (slot == SLOT_SEARCH) {
            searchClick(click, "技能", query, text -> {
                query = text;
                page = 0;
            });
            return;
        }
        if (slot == SLOT_WEAPON) {
            weapon = (weapon + (click.isRightClick() ? WEAPONS.length - 1 : 1)) % WEAPONS.length;
            page = 0;
            redraw();
            return;
        }
        if (slot == SLOT_ELEMENT) {
            element = (element + (click.isRightClick() ? ELEMENTS.length - 1 : 1)) % ELEMENTS.length;
            page = 0;
            redraw();
            return;
        }
        for (int i = 0; i < FILTER_SLOTS.length; i++) {
            if (slot == FILTER_SLOTS[i]) {
                if (filter != i) {
                    filter = i;
                    page = 0;
                    redraw();
                }
                return;
            }
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            final int target = slot == SLOT_PREV ? page - 1 : page + 1;
            if (target >= 0 && target < pageCount()) {
                page = target;
                redraw();
            }
            return;
        }
        String error = null;
        boolean acted = false;
        if (slot >= NEW_START && slot < NEW_START + shown.size()) {
            final SkillDefinition definition = shown.get(slot - NEW_START);
            if (definition.tier() > level) {
                return;
            }
            if (!acceptAction(click)) {
                return;
            }
            error = service.gems().engrave(player, source, definition.id());
            acted = true;
        } else if (slot >= UPGRADE_START && slot < UPGRADE_START + SkillLoadout.PAGE_SIZE) {
            final int index = slot - UPGRADE_START;
            final int position = index < upgradePositions.size() ? upgradePositions.get(index) : -1;
            if (position < 0 || plugin.players().get(player).skills().skill(position) == null || !acceptAction(click)) {
                return;
            }
            error = service.gems().levelUpFrom(player, source, position);
            acted = true;
        }
        if (!acted) {
            return;
        }
        if (error != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(error)));
            GemService.sound(player, false);
        }
        // 寶石用完了：從隨身背包來的回隨身背包，手上的關掉；還有就重畫（可以連續升級）
        if (service.gems().held(player, source, GemData.Kind.UNCUT_SKILL) == null) {
            if (source.fromPouch()) {
                backToBag();
            } else {
                player.closeInventory();
            }
        } else {
            redraw();
        }
    }

    private void backToBag() {
        if (plugin.loot() != null) {
            plugin.loot().openBagCurrency(player, tw.exilecore.currency.CurrencyGroups.Group.GEM);
        } else {
            player.closeInventory();
        }
    }

    /** 主手武器的名稱（角色裝備的那把）。 */
    private String weaponName(final java.util.Set<String> tags) {
        final tw.exilecore.item.ItemBase base = mainHandBase();
        return base == null ? (tags.isEmpty() ? "徒手" : "武器") : ItemRenderer.escape(base.name());
    }
}
