package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterConfig;
import tw.exilecore.character.StarterKits;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 建立角色的第二步：選新手武器。職業起始屬性夠用的武器都能選，職業推薦的排在第一列（標 ★）。
 * 選完跳出輸入框打名稱。建立時一起送職業的防具，單手武器再送一面盾（{@link StarterKits}）。
 */
public final class WeaponSelectMenu extends Menu {

    /** 每列最多幾把（第 2 到第 8 欄）。 */
    private static final int PER_ROW = 7;
    private static final int SLOT_INFO = 4;

    /** 武器標籤在說明裡的名稱。 */
    private static final Map<String, String> TAG_NAMES = Map.ofEntries(
            Map.entry("melee", "近戰武器"), Map.entry("ranged", "遠程武器"), Map.entry("bow", "弓"),
            Map.entry("wand", "法杖"), Map.entry("staff", "長杖"), Map.entry("sword", "劍"), Map.entry("axe", "斧"),
            Map.entry("mace", "錘"), Map.entry("dagger", "匕首"), Map.entry("claw", "爪"),
            Map.entry("crossbow", "弩"), Map.entry("quarterstaff", "長棍"), Map.entry("spear", "矛"),
            Map.entry("talisman", "獸魂符"), Map.entry("one_hand", "單手武器"), Map.entry("two_hand", "雙手武器"));

    private final int characterSlot;
    private final CharacterClass characterClass;
    private final List<StarterKits.Option> options;
    private final Map<Integer, StarterKits.Option> positions = new HashMap<>();
    private final int backSlot;

    public WeaponSelectMenu(final ExileCore plugin, final Player player, final int characterSlot,
                            final CharacterClass characterClass, final List<StarterKits.Option> options) {
        super(plugin, player, rows(options), "<dark_gray>選擇新手武器（" + characterClass.displayName() + "）");
        this.characterSlot = characterSlot;
        this.characterClass = characterClass;
        this.options = List.copyOf(options);
        final int rows = rows(options);
        this.backSlot = rows * 9 - 1;
        layout(rows);
    }

    /** 標題一列、推薦的武器、其他武器（各自從新的一列開始）、底下一列按鈕；太多時不分組，最多 6 列。 */
    private static int rows(final List<StarterKits.Option> options) {
        final int recommended = (int) options.stream().filter(StarterKits.Option::recommended).count();
        final int others = options.size() - recommended;
        final int grouped = 2 + ceil(recommended) + ceil(others);
        return Math.min(6, grouped <= 6 ? Math.max(3, grouped) : 2 + Math.max(1, ceil(options.size())));
    }

    private static int ceil(final int count) {
        return (count + PER_ROW - 1) / PER_ROW;
    }

    private void layout(final int rows) {
        final List<StarterKits.Option> recommended = new ArrayList<>();
        final List<StarterKits.Option> others = new ArrayList<>();
        for (final StarterKits.Option option : options) {
            (option.recommended() ? recommended : others).add(option);
        }
        final boolean grouped = 2 + ceil(recommended.size()) + ceil(others.size()) <= 6;
        int row = 1;
        if (grouped) {
            row = place(recommended, row, rows);
            place(others, row, rows);
        } else {
            place(options, row, rows);
        }
    }

    /** 從第 row 列開始，每列最多 7 把、置中排好；回傳下一個空的列。 */
    private int place(final List<StarterKits.Option> list, final int firstRow, final int rows) {
        int row = firstRow;
        for (int start = 0; start < list.size() && row < rows - 1; start += PER_ROW, row++) {
            final int count = Math.min(PER_ROW, list.size() - start);
            final int column = (9 - count) / 2;
            for (int i = 0; i < count; i++) {
                positions.put(row * 9 + column + i, list.get(start + i));
            }
        }
        return row;
    }

    @Override
    protected void draw() {
        final int size = getInventory().getSize();
        for (int i = 0; i < size; i++) {
            set(i, filler());
        }
        set(SLOT_INFO, infoIcon());
        for (final Map.Entry<Integer, StarterKits.Option> entry : positions.entrySet()) {
            set(entry.getKey(), weaponIcon(entry.getValue()));
        }
        set(backSlot, icon(Material.ARROW, "<white>返回", "<gray>重選職業"));
    }

    private ItemStack infoIcon() {
        final ItemRepository items = plugin.items().repository();
        final CharacterConfig config = plugin.characters().config();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>選一把武器，建立角色時一起拿到：");
        lore.add("<white>・武器 <gray>直接裝在角色面板上");
        final List<String> armour = new ArrayList<>();
        for (final ItemBase base : StarterKits.armour(items, config, characterClass)) {
            armour.add(base.name());
        }
        if (!armour.isEmpty()) {
            lore.add("<white>・" + ItemRenderer.escape(String.join("、", armour)) + " <gray>直接穿上");
        }
        final String shield = config.starter(characterClass).shield();
        final ItemBase shieldBase = shield == null ? null : items.base(shield);
        if (shieldBase != null && StarterKits.usable(items, shieldBase, characterClass)) {
            lore.add("<white>・" + ItemRenderer.escape(shieldBase.name()) + " <gray>選單手武器才送（副手）");
        }
        lore.add("<gray>全部是普通物品、物品等級 1");
        final String skills = skillRules(config);
        if (!skills.isEmpty()) {
            lore.add("");
            lore.add("<gray>起始技能跟著武器：<white>" + skills);
        }
        lore.add("");
        lore.add("<dark_gray>起始屬性（力量 " + characterClass.strength() + "、敏捷 " + characterClass.dexterity()
                + "、智慧 " + characterClass.intelligence() + "）夠用的武器都能選");
        lore.add("<dark_gray>★ 是這個職業推薦的；之後想換武器、換技能都可以");
        return icon(Material.CHEST, "<#f5b942>新手裝備", lore);
    }

    /** 「弓 → 分裂箭、法杖 → 熾星彈…」。 */
    private String skillRules(final CharacterConfig config) {
        if (plugin.skills() == null) {
            return "";
        }
        final Map<String, String> parts = new LinkedHashMap<>();
        for (final CharacterConfig.StarterSkillRule rule : config.starterSkills()) {
            if (rule.classId() != null && !rule.classId().equals(characterClass.id())) {
                continue; // 別的職業才有的規則（例如混沌術士拿法杖送混沌彈）
            }
            final SkillDefinition skill = plugin.skills().config().skill(rule.skill());
            if (skill != null) {
                parts.putIfAbsent(rule.weaponTag(), TAG_NAMES.getOrDefault(rule.weaponTag(), rule.weaponTag()) + " → " + skill.name());
            }
        }
        return ItemRenderer.escape(String.join("、", parts.values()));
    }

    private ItemStack weaponIcon(final StarterKits.Option option) {
        final ItemBase base = option.base();
        final List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + (base.hasTag("two_hand") ? "雙手武器" : "單手武器") + "・"
                + (base.hasTag("ranged") ? "遠程" : "近戰"));
        for (final Map.Entry<DamageType, int[]> entry : base.damage().entrySet()) {
            lore.add("<gray>" + entry.getKey().displayName() + "傷害：<white>" + entry.getValue()[0] + " - " + entry.getValue()[1]);
        }
        lore.add(String.format(Locale.ROOT, "<gray>暴擊率：<white>%.1f%%  <gray>每秒攻擊：<white>%.2f", base.crit(), base.aps()));
        lore.add(requirementLine(plugin.items().repository(), base));
        if (base.implicit() != null) {
            lore.add("<#a0a0ff>" + ItemRenderer.escape(rangeText(base)) + " <dark_gray>(固定)"
                    + tw.exilecore.item.AffixChips.miniMessage(tw.exilecore.item.AffixChips.of(base.implicit().mods())));
        }
        final String hint = base.basicAttackHint();
        if (hint != null) {
            lore.add("<gray>" + hint);
        }
        lore.add("<dark_gray>────────────");
        final String skill = starterSkillName(base);
        if (skill != null) {
            lore.add("<gray>起始技能：<#e0c080>" + ItemRenderer.escape(skill));
        }
        final ItemBase shield = StarterKits.shield(plugin.items().repository(), plugin.characters().config(), characterClass, base);
        if (shield != null) {
            lore.add("<gray>副手：<white>" + ItemRenderer.escape(shield.name()) + " <gray>（一起送）");
        } else if (base.hasTag("two_hand")) {
            lore.add("<gray>雙手武器，不送盾");
        }
        if (option.recommended()) {
            lore.add("<#f5b942>★ " + characterClass.displayName() + "推薦");
        }
        lore.add("");
        lore.add("<yellow>左鍵：選這把，接著輸入角色名稱");
        final ItemStack stack = icon(material(base.material(), Material.IRON_SWORD),
                "<white>" + ItemRenderer.escape(base.name()) + (option.recommended() ? " <#f5b942>★" : ""), lore);
        if (base.model() != null && plugin.getConfig().getBoolean("resource-pack.enabled", false)) {
            final NamespacedKey model = NamespacedKey.fromString(base.model());
            if (model != null) {
                stack.editMeta(meta -> meta.setItemModel(model));
            }
        }
        return option.recommended() ? glowing(stack) : stack;
    }

    private static String requirementLine(final ItemRepository repository, final ItemBase base) {
        // 2026-10-26：顯示新手武器那個物品等級的需求（合併的基底照同一類最低那一階的 PoE2 需求）；這次之前直接顯示基底寫的
        final ItemBase.Requirements req = tw.exilecore.item.ItemStats.requirements(repository, base, StarterKits.ITEM_LEVEL);
        final List<String> parts = new ArrayList<>();
        if (req.str() > 0) {
            parts.add("力量 <white>" + req.str());
        }
        if (req.dex() > 0) {
            parts.add("敏捷 <white>" + req.dex());
        }
        if (req.intelligence() > 0) {
            parts.add("智慧 <white>" + req.intelligence());
        }
        return parts.isEmpty() ? "<gray>需求：無" : "<gray>需求：" + String.join("<gray>，", parts);
    }

    /** 固定詞綴寫成範圍：「增加 8–12% 物理傷害」（實際數值拿到時才擲）。 */
    private static String rangeText(final ItemBase base) {
        String text = base.implicit().text();
        for (final Map.Entry<String, int[]> entry : base.implicit().vars().entrySet()) {
            final int lo = Math.min(entry.getValue()[0], entry.getValue()[1]);
            final int hi = Math.max(entry.getValue()[0], entry.getValue()[1]);
            text = text.replace("{" + entry.getKey() + "}", lo == hi ? String.valueOf(lo) : lo + "–" + hi);
        }
        return text;
    }

    /** 拿這把武器送的起始技能名稱（技能模組沒啟用時 null）。 */
    private String starterSkillName(final ItemBase base) {
        if (plugin.skills() == null) {
            return null;
        }
        final String id = StarterKits.skill(plugin.characters().config(), characterClass, base);
        SkillDefinition definition = id == null ? null : plugin.skills().config().skill(id);
        if (definition == null || definition.hidden()) {
            definition = plugin.skills().config().starter(characterClass.id());
        }
        return definition == null ? null : definition.name();
    }

    @Override
    protected void onClick(final int clicked, final ClickType click, final InventoryClickEvent event) {
        if (clicked == backSlot) {
            plugin.characterMenus().openClassSelect(player, characterSlot);
            return;
        }
        final StarterKits.Option option = positions.get(clicked);
        if (option != null && click.isLeftClick()) {
            plugin.characterMenus().promptName(player, characterSlot, characterClass, option.base().id());
        }
    }
}
