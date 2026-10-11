package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.gui.Menu;

/** 建立角色的第一步：選職業。選完接著選新手武器（{@link WeaponSelectMenu}），再跳出輸入框打名稱。 */
public final class ClassSelectMenu extends Menu {

    /** 中間那一列，照天賦樹上的起點順序（力量 → 敏捷 → 智慧）。 */
    private static final int[] POSITIONS = {9, 10, 11, 12, 14, 15, 16, 17}; // 2026-10：八個職業（中間空一格）
    private static final int SLOT_BACK = 26;

    private final int slot;

    public ClassSelectMenu(final ExileCore plugin, final Player player, final int slot) {
        super(plugin, player, 3, "<dark_gray>選擇職業（角色格 " + slot + "）");
        this.slot = slot;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        set(4, icon(Material.PAPER, "<#f5b942>職業只決定起點",
                "<gray>八個職業沒有專屬技能，差別是起始屬性、",
                "<gray>天賦樹上的起點與昇華職業",
                "<gray>起始技能跟著選的新手武器走",
                "<gray>之後想走別的路線，穿過去就好",
                "",
                "<gray>對著職業按<yellow>右鍵<gray>：先看它的昇華職業與昇華天賦"));
        final CharacterClass[] classes = CharacterClass.values();
        for (int i = 0; i < classes.length && i < POSITIONS.length; i++) {
            final CharacterClass characterClass = classes[i];
            // 2026-10：這個職業能走的昇華職業寫出來，右鍵看完整的昇華天賦（還沒建立角色也能看）
            final java.util.List<String> ascendancies = plugin.ascendancy() == null ? java.util.List.of()
                    : plugin.ascendancy().ascendancyNames(characterClass.id());
            final java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add("<gray>力量 <white>" + characterClass.strength() + "<gray>　敏捷 <white>" + characterClass.dexterity()
                    + "<gray>　智慧 <white>" + characterClass.intelligence());
            lore.add("<gray>" + characterClass.description());
            lore.add(recommended(characterClass));
            if (!ascendancies.isEmpty()) {
                lore.add("<gray>昇華職業：" + String.join("<gray>、", ascendancies));
            }
            lore.add("");
            lore.add("<yellow>左鍵：選這個職業，接著選新手武器");
            if (!ascendancies.isEmpty()) {
                lore.add("<yellow>右鍵：預覽昇華職業與昇華天賦");
            }
            set(POSITIONS[i], icon(material(characterClass.icon(), Material.IRON_SWORD),
                    characterClass.color() + characterClass.displayName(), lore));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>返回"));
    }

    /** 「推薦武器：弩」（照 character.yml 的 recommended；物品模組沒啟用時用代號）。 */
    private String recommended(final CharacterClass characterClass) {
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (final String id : plugin.characters().config().starter(characterClass).recommended()) {
            final tw.exilecore.item.ItemBase base = plugin.items() == null ? null : plugin.items().repository().base(id);
            names.add(base == null ? id : base.name());
        }
        return names.isEmpty() ? "" : "<gray>推薦武器：<white>" + tw.exilecore.item.ItemRenderer.escape(String.join("、", names));
    }

    @Override
    protected void onClick(final int clicked, final ClickType click, final InventoryClickEvent event) {
        if (clicked == SLOT_BACK) {
            plugin.characterMenus().openSelect(player);
            return;
        }
        final CharacterClass[] classes = CharacterClass.values();
        for (int i = 0; i < classes.length && i < POSITIONS.length; i++) {
            if (POSITIONS[i] == clicked && click.isLeftClick()) {
                plugin.characterMenus().openWeaponSelect(player, slot, classes[i]);
                return;
            }
            if (POSITIONS[i] == clicked && click.isRightClick() && plugin.ascendancy() != null
                    && !plugin.ascendancy().ascendancyNames(classes[i].id()).isEmpty()) {
                final int characterSlot = slot;
                plugin.ascendancy().openClassPreview(player, classes[i].id(), () -> new ClassSelectMenu(plugin, player, characterSlot).open());
                return;
            }
        }
    }
}
