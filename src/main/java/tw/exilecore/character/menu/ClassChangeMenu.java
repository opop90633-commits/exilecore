package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.ClassChange;
import tw.exilecore.character.ClassScroll;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-28 職業轉職卷的選單：中間一列是其他七個職業（起始屬性、說明、昇華職業），點一個進確認畫面（寫清楚會變什麼），
 * 按「確定轉職」才換（{@link tw.exilecore.character.CharacterService#changeClass}），換成功才用掉一張轉職卷。
 */
public final class ClassChangeMenu extends Menu {

    private static final int[] POSITIONS = {10, 11, 12, 13, 14, 15, 16};
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_TARGET = 13;
    private static final int SLOT_CANCEL = 15;
    private static final int SLOT_CLOSE = 22;
    /** 剛進確認畫面多久內不接受「確定」（選職業連點兩下不會直接換掉）。 */
    private static final long CONFIRM_GRACE_MILLIS = 600;

    private CharacterClass pending;
    private long pendingSince;

    private ClassChangeMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 3, "<dark_gray>職業轉職卷：選新的職業");
    }

    /** 主手拿著職業轉職卷右鍵：打開選單（回傳有沒有處理）。 */
    public static boolean tryOpen(final ExileCore plugin, final Player player, final ItemStack item, final EquipmentSlot hand) {
        if (hand != EquipmentSlot.HAND || !ClassScroll.is(item)) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        if (state == null || !state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return true;
        }
        new ClassChangeMenu(plugin, player).open();
        return true;
    }

    private CharacterClass current() {
        final PlayerState state = plugin.players().get(player);
        return state == null || state.character() == null ? null : state.character().characterClass();
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final CharacterClass current = current();
        if (current == null) {
            return;
        }
        if (pending != null) {
            drawConfirm(current, pending);
            return;
        }
        set(SLOT_INFO, icon(Material.PAPER, "<#e8c26a>" + ClassScroll.NAME,
                "<gray>現在的職業：" + current.color() + current.displayName(),
                "<gray>點一個職業看會變什麼，確認後才換",
                "<gray>天賦點、昇華點全部退回；等級、裝備、技能、通貨都不變",
                "<dark_gray>換成功才會用掉一張轉職卷"));
        final List<CharacterClass> choices = ClassChange.choices(current);
        for (int i = 0; i < choices.size() && i < POSITIONS.length; i++) {
            final CharacterClass target = choices.get(i);
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>力量 <white>" + target.strength() + "<gray>　敏捷 <white>" + target.dexterity()
                    + "<gray>　智慧 <white>" + target.intelligence());
            lore.add("<gray>" + ItemRenderer.escape(target.description()));
            final List<String> ascendancies = ascendancies(target);
            if (!ascendancies.isEmpty()) {
                lore.add("<gray>昇華職業：" + ItemRenderer.escape(String.join("、", ascendancies)));
            }
            lore.add("");
            lore.add("<yellow>點一下：換成這個職業（會先確認）");
            set(POSITIONS[i], icon(material(target.icon(), Material.IRON_SWORD), target.color() + target.displayName(), lore));
        }
        set(SLOT_CLOSE, icon(Material.BARRIER, "<white>關閉"));
    }

    private void drawConfirm(final CharacterClass current, final CharacterClass target) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + current.color() + current.displayName() + " <gray>→ " + target.color() + target.displayName());
        lore.add("");
        lore.addAll(ClassChange.rules(target));
        final String ascendancy = plugin.ascendancy() == null ? null : plugin.ascendancy().ascendancyName(plugin.players().get(player));
        if (ascendancy != null) {
            lore.add("<yellow>你的昇華職業 " + ascendancy + " <yellow>會退掉"); // colored() 已經是 MiniMessage
        }
        set(SLOT_TARGET, glowing(icon(material(target.icon(), Material.IRON_SWORD), target.color() + "轉職成" + target.displayName(), lore)));
        set(SLOT_CONFIRM, icon(Material.LIME_STAINED_GLASS_PANE, "<green>確定轉職",
                "<gray>用掉一張" + ClassScroll.NAME + "，換成" + target.displayName()));
        set(SLOT_CANCEL, icon(Material.RED_STAINED_GLASS_PANE, "<red>取消", "<gray>回到職業列表"));
    }

    private List<String> ascendancies(final CharacterClass target) {
        return plugin.ascendancy() == null ? List.of() : plugin.ascendancy().ascendancyNames(target.id());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (!acceptAction(click)) {
            return;
        }
        final CharacterClass current = current();
        if (current == null) {
            player.closeInventory();
            return;
        }
        if (pending != null) {
            if (slot == SLOT_CANCEL) {
                pending = null;
                redraw();
            } else if (slot == SLOT_CONFIRM && System.currentTimeMillis() - pendingSince >= CONFIRM_GRACE_MILLIS) {
                apply(pending);
            }
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        final List<CharacterClass> choices = ClassChange.choices(current);
        for (int i = 0; i < choices.size() && i < POSITIONS.length; i++) {
            if (POSITIONS[i] == slot) {
                pending = choices.get(i);
                pendingSince = System.currentTimeMillis();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                redraw();
                return;
            }
        }
    }

    /** 確定：轉職卷還在才換；換成功才用掉一張。 */
    private void apply(final CharacterClass target) {
        player.closeInventory();
        if (ClassScroll.find(player) < 0) {
            fail("背包裡沒有" + ClassScroll.NAME + "了");
            return;
        }
        final String problem = plugin.characters().changeClass(player, target, false);
        if (problem != null) {
            fail(problem);
            return;
        }
        ClassScroll.consume(player); // 同一個 tick、剛找過：一定拿得到
        plugin.characters().save(player);
    }

    private void fail(final String message) {
        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(message)));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
    }
}
