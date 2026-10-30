package tw.exilecore.skill;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 切輔助寶石（右鍵未切割輔助寶石，或在隨身背包的通貨頁 Shift＋右鍵）。
 * 2026-10：分兩步——先選技能面板上的一個技能，再從「能掛在這個技能上的輔助」裡挑一個（掛不上的不列）；
 * 切好直接掛到那個技能的空孔（沒有空孔就放進背包）。也可以不選技能、看全部輔助。
 * 最上面一排：0～3 階級分頁、4 用的那一顆、5 選的技能（點一下重選）、7 效果分類（左鍵下一類、右鍵上一類）。
 */
final class SupportCutMenu extends Menu {

    private static final int SLOT_GEM = 4;
    private static final int SLOT_SKILL = 5;
    private static final int SLOT_CATEGORY = 7;
    /** 搜尋（2026-10）。 */
    private static final int SLOT_SEARCH = 8;
    private String query = "";
    private static final int SLOT_ALL = 40;
    private static final int LIST_START = CONTENT_START;
    private static final int LIST_SLOTS = CONTENT_END - CONTENT_START;
    /** 還在選技能。 */
    private static final int PICKING = -2;
    /** 不選技能，列出全部。 */
    private static final int ANY = -1;

    private final SkillService service;
    private final GemService.UncutSource source;
    private final int tier;
    /** 選的技能在面板上的位置（PICKING／ANY／0～26）。 */
    private int position;
    private final List<SupportDefinition> all = new ArrayList<>();
    private final List<SupportDefinition> shown = new ArrayList<>();
    private int page;
    /** 0 = 全部、1～3 = 第幾階。 */
    private int tierFilter;
    private static final int[] TIER_SLOTS = {0, 1, 2, 3};

    /** 效果分類：名稱、圖示、屬性前綴（輔助的修飾器有任一個屬性以這些開頭就算這一類）。 */
    private static final String[][] CATEGORIES = {
            {"全部", "NETHER_STAR", ""},
            {"傷害", "IRON_SWORD", "damage,penetration,armour.break,exposure,channel.more,onkill.damage,accuracy"},
            {"暴擊", "SPYGLASS", "crit."},
            {"速度・時間", "SUGAR", "attack.speed,cast.speed,skill.cooldown,skill.duration,movement.speed,onkill.speed"},
            {"範圍・投射物", "ARROW", "skill.area,skill.projectile,skill.chains,skill.pierce,skill.fork,skill.strike_targets"},
            {"異常・暈眩", "FERMENTED_SPIDER_EYE", "ignite.,bleed.,poison.,shock.,chill.,freeze.,electrocute.,ailment.,flag.no_elemental_ailments,pin.,daze.,stun."},
            {"生存・回復", "GOLDEN_APPLE", "leech.,life.,resist.,totem.life,mana."},
            {"觸發・特殊", "REDSTONE", "trigger.,flag.,warcry.,totem.,rage.,channel.stages"},
    };
    private int category;

    SupportCutMenu(final ExileCore plugin, final Player player, final SkillService service, final GemService.UncutSource source, final int tier) {
        super(plugin, player, 6, "<dark_gray>切輔助寶石（第 " + tier + " 階）");
        this.service = service;
        this.source = source;
        this.tier = tier;
        this.position = plugin.players().get(player).skills().isEmpty() ? ANY : PICKING;
    }

    static boolean inCategory(final SupportDefinition support, final int category) {
        if (category <= 0) {
            return true;
        }
        final String[] prefixes = CATEGORIES[category][2].split(",");
        for (final tw.exilecore.stats.Modifier modifier : support.modifiers()) {
            for (final String prefix : prefixes) {
                if (modifier.stat().startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private SkillLoadout loadout() {
        return plugin.players().get(player).skills();
    }

    /** 選的技能（沒選或已經不在面板上回傳 null）。 */
    private SkillDefinition chosen() {
        if (position < 0) {
            return null;
        }
        final SkillLoadout.Entry entry = loadout().skill(position);
        return entry == null ? null : service.config().skill(entry.skillId());
    }

    private boolean listed(final SupportDefinition support) {
        if (!support.cuttable() || (tierFilter != 0 && support.tier() != tierFilter) || !inCategory(support, category)
                || !matches(query, tw.exilecore.gui.CodexMenu.supportSearchText(support))) {
            return false;
        }
        final SkillDefinition skill = chosen();
        return skill == null || fits(loadout().skill(position), skill, support);
    }

    /**
     * 2026-10-30：這顆輔助掛不掛得上這個技能，跟技能面板掛上去時一樣驗（GemService.socket）：宿主寶石（引星、星咒圖騰、幻影銳眼…）
     * 照孔裡的技能驗（hostRefusal）。這次之前一律照技能自己的標籤，宿主寶石列出來的跟真的掛得上的不一樣。
     */
    private boolean fits(final SkillLoadout.Entry entry, final SkillDefinition skill, final SupportDefinition support) {
        if (SupportDefinition.hostsSkill(skill)) {
            return entry != null && support.hostRefusal(skill, service.innerSkill(entry)) == null;
        }
        return support.supports(skill);
    }

    private int pageCount() {
        return Math.max(1, (all.size() + LIST_SLOTS - 1) / LIST_SLOTS);
    }

    @Override
    protected void draw() {
        frame();
        if (position >= 0 && chosen() == null) {
            position = PICKING; // 技能被拿下來了
        }
        final ItemStack held = service.gems().display(player, source);
        set(SLOT_GEM, held == null ? icon(Material.BARRIER, "<red>" + (source.fromPouch() ? "通貨袋裡的這種寶石用完了" : "手上的寶石不見了"))
                : appendLore(held.asOne(), "", "<gray>" + (source.fromPouch() ? "通貨袋裡" : "手上") + "還有 <white>"
                        + service.gems().count(player, source) + "</white> 顆"));
        if (source.fromPouch()) {
            set(SLOT_BACK, backButton("隨身背包"));
        }
        set(SLOT_CLOSE_BAR, closeButton());
        if (position == PICKING) {
            drawSkills();
            return;
        }
        all.clear();
        for (final SupportDefinition support : service.config().supports()) {
            if (support.tier() <= tier && listed(support)) {
                all.add(support);
            }
        }
        final int cuttable = all.size();
        for (final SupportDefinition support : service.config().supports()) {
            if (support.tier() > tier && listed(support)) {
                all.add(support);
            }
        }
        page = Math.max(0, Math.min(page, pageCount() - 1));
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            final String name = i == 0 ? "全部階級" : "第 " + i + " 階";
            final ItemStack tab = icon(i == 0 ? Material.NETHER_STAR : i == 1 ? Material.LIGHT_BLUE_DYE : i == 2 ? Material.CYAN_DYE : Material.BLUE_DYE,
                    (i == tierFilter ? "<#f5b942>▶ " : "<white>") + name, i > tier ? "<gray>要第 " + i + " 階的寶石才切得出來" : "",
                    i == tierFilter ? "<green>顯示中" : "<yellow>點一下：只看" + name);
            if (i > 0) {
                tab.setAmount(i);
            }
            set(TIER_SLOTS[i], i == tierFilter ? highlight(tab) : tab);
        }
        set(SLOT_SKILL, chosenIcon(cuttable));
        set(SLOT_CATEGORY, categoryButton());
        set(SLOT_SEARCH, searchButton(query, "輔助"));
        shown.clear();
        for (int i = page * LIST_SLOTS; i < all.size() && shown.size() < LIST_SLOTS; i++) {
            shown.add(all.get(i));
        }
        for (int i = 0; i < shown.size(); i++) {
            set(LIST_START + i, supportIcon(shown.get(i)));
        }
        if (shown.isEmpty()) {
            set(LIST_START + 13, icon(Material.BARRIER, "<gray>沒有符合的輔助", "<dark_gray>換一個階級、分類或搜尋看看"));
        }
        if (pageCount() > 1) {
            set(SLOT_PREV, page > 0 ? pageButton(false, page, pageCount()) : bar());
            set(SLOT_NEXT, page < pageCount() - 1 ? pageButton(true, page, pageCount()) : bar());
        }
        set(SLOT_HELP, helpButton(List.of(
                "只列出能掛在選的技能上的輔助（掛不上的不列）",
                "點一個輔助：切出來直接掛到那個技能的空孔",
                "（沒有空孔就放進背包）",
                "灰色的要更高階的未切割輔助寶石",
                "上面的分類：左鍵下一類、右鍵上一類")));
    }

    /** 第一步：列出技能面板（三頁 27 格照面板的位置排）。 */
    private void drawSkills() {
        final SkillLoadout loadout = loadout();
        for (int p = 0; p < SkillLoadout.MAX_SKILLS; p++) {
            final SkillLoadout.Entry entry = loadout.skill(p);
            final SkillDefinition definition = entry == null ? null : service.config().skill(entry.skillId());
            if (definition == null) {
                set(LIST_START + p, uiIcon("slot_skill", Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>技能面板的空格"));
                continue;
            }
            int fits = 0;
            for (final SupportDefinition support : service.config().supports()) {
                if (support.cuttable() && support.tier() <= tier && fits(entry, definition, support)) {
                    fits++;
                }
            }
            int free = 0;
            for (int i = 0; i < entry.sockets(); i++) {
                if (entry.support(i).isEmpty()) {
                    free++;
                }
            }
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>Lv." + entry.level() + "　輔助孔 <white>" + (entry.sockets() - free) + "/" + entry.sockets() + "</white>"
                    + (free > 0 ? "<green>（空 " + free + " 孔）" : "<red>（沒有空孔）"));
            for (final String id : entry.supportIds()) {
                final SupportDefinition support = service.config().support(id);
                lore.add("<dark_gray> - " + ItemRenderer.escape(support == null ? id : support.name()));
            }
            lore.add("");
            lore.add("<gray>這顆寶石能切給它的輔助：<white>" + fits + "</white> 種");
            lore.add("<yellow>點一下：選這個技能");
            final ItemStack stack = SkillArt.decorate(icon(material(definition.icon(), Material.BOOK),
                    "<#e0c080>" + ItemRenderer.escape(definition.name()), lore), definition);
            set(LIST_START + p, stack);
        }
        set(SLOT_ALL, icon(Material.BOOKSHELF, "<white>不選技能：看全部輔助", "<gray>切好的寶石放進背包", "<yellow>點一下"));
        set(SLOT_HELP, helpButton(List.of(
                "第一步：選技能面板上的一個技能",
                "第二步：只列出能掛在這個技能上的輔助",
                "切好直接掛上去（沒有空孔就放進背包）")));
    }

    private ItemStack chosenIcon(final int cuttable) {
        final SkillDefinition skill = chosen();
        if (skill == null) {
            return icon(Material.BOOKSHELF, "<white>全部輔助（沒選技能）", "<gray>這顆切得出 <white>" + cuttable + "</white> 種",
                    "<gray>切好的寶石放進背包", "", "<yellow>點一下：改選技能");
        }
        final SkillLoadout.Entry entry = loadout().skill(position);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>能掛、這顆切得出的：<white>" + cuttable + "</white> 種");
        lore.add(entry.freeSocket() >= 0 ? "<green>有空孔：切好直接掛上" : "<red>沒有空孔：切好放進背包");
        for (final String id : entry.supportIds()) {
            final SupportDefinition support = service.config().support(id);
            lore.add("<dark_gray> - " + ItemRenderer.escape(support == null ? id : support.name()));
        }
        lore.add("");
        lore.add("<yellow>點一下：改選技能");
        return highlight(SkillArt.decorate(icon(material(skill.icon(), Material.BOOK), "<#e0c080>技能：" + ItemRenderer.escape(skill.name()), lore), skill));
    }

    private ItemStack categoryButton() {
        final List<String> lore = new ArrayList<>();
        for (int c = 0; c < CATEGORIES.length; c++) {
            lore.add((c == category ? "<#f5b942>▶ " : "<gray>  ") + CATEGORIES[c][0]);
        }
        lore.add("");
        lore.add("<yellow>左鍵：下一類　右鍵：上一類");
        final ItemStack stack = icon(material(CATEGORIES[category][1], Material.BOOK), "<white>分類：<#f5b942>" + CATEGORIES[category][0], lore);
        return category == 0 ? stack : highlight(stack);
    }

    private ItemStack supportIcon(final SupportDefinition support) {
        if (support.tier() > tier) {
            return icon(Material.GRAY_DYE, "<gray>" + ItemRenderer.escape(support.name()) + " <dark_gray>第 " + support.tier() + " 階",
                    "<red>要第 " + support.tier() + " 階的未切割輔助寶石才能切");
        }
        final List<String> lore = new ArrayList<>();
        if (!support.description().isBlank()) {
            lore.add("<gray>" + ItemRenderer.escape(support.descriptionText()));
        }
        lore.addAll(GemService.effectLines(support));
        final SkillLoadout loadout = loadout();
        final SkillLoadout.Entry entry = position >= 0 ? loadout.skill(position) : null;
        if (entry == null) {
            lore.add("");
            lore.add("<gray>可以輔助：<white>" + support.supportsText(service.config().all()));
        } else if (entry.hasSupport(support.id())) {
            lore.add("");
            lore.add("<gray>這個技能已經掛著（切出來會放進背包）");
        }
        if (loadout.supportUsedBy(support.id()) >= 0 && service.config().oncePerCharacter(support.id())) {
            lore.add("<gray>你的技能已經掛著這個輔助（同一種只能用一次）");
        }
        lore.add("<yellow>點一下：切成這個輔助寶石" + (entry != null && entry.freeSocket() >= 0 && !entry.hasSupport(support.id()) ? "並掛上" : ""));
        return SkillArt.decorateSupport(icon(material(support.icon(), Material.GHAST_TEAR),
                "<#8fd3ff>" + ItemRenderer.escape(support.name()) + " <dark_gray>第 " + support.tier() + " 階", lore), support.id());
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
        if (position == PICKING) {
            if (slot == SLOT_ALL) {
                position = ANY;
                page = 0;
                redraw();
                return;
            }
            final int p = slot - LIST_START;
            if (p >= 0 && p < SkillLoadout.MAX_SKILLS && loadout().skill(p) != null) {
                position = p;
                page = 0;
                redraw();
            }
            return;
        }
        if (slot == SLOT_SEARCH) {
            searchClick(click, "輔助", query, text -> {
                query = text;
                page = 0;
            });
            return;
        }
        if (slot == SLOT_SKILL) {
            position = PICKING;
            redraw();
            return;
        }
        if (slot == SLOT_CATEGORY) {
            category = (category + (click.isRightClick() ? CATEGORIES.length - 1 : 1)) % CATEGORIES.length;
            page = 0;
            redraw();
            return;
        }
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            if (slot == TIER_SLOTS[i]) {
                if (tierFilter != i) {
                    tierFilter = i;
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
        if (slot < LIST_START || slot >= LIST_START + shown.size()) {
            return;
        }
        final SupportDefinition support = shown.get(slot - LIST_START);
        if (support.tier() > tier || !acceptAction(click)) {
            return;
        }
        final String error = service.gems().cutSupport(player, source, support.id(), position);
        if (error != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(error)));
            GemService.sound(player, false);
        }
        if (service.gems().held(player, source, GemData.Kind.UNCUT_SUPPORT) == null) {
            if (source.fromPouch()) {
                backToBag();
            } else {
                player.closeInventory();
            }
        } else {
            redraw(); // 還有就留著可以再切
        }
    }

    private void backToBag() {
        if (plugin.loot() != null) {
            plugin.loot().openBagCurrency(player, tw.exilecore.currency.CurrencyGroups.Group.GEM);
        } else {
            player.closeInventory();
        }
    }
}
