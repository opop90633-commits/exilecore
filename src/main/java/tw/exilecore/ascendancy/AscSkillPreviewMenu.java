package tw.exilecore.ascendancy;

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
import tw.exilecore.skill.SkillDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27 照站長「昇華提供的技能希望能直接在昇華那邊預覽技能效果」：昇華天賦 Shift＋點擊打開，
 * 列出這個天賦賦予的技能，說明跟技能寶石、圖鑑同一套（SkillService.describe：標籤、說明、傷害、施法時間、冷卻、消耗…）。
 * 有角色照角色目前的屬性與能用的技能等級算；沒有角色（建立角色時的預覽）照 20 級的基礎說明。
 */
final class AscSkillPreviewMenu extends Menu {

    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final int[] SLOTS = {13, 11, 15, 10, 16, 12, 14};

    private final AscendancyConfig.Node node;
    private final List<SkillDefinition> skills;
    private final Runnable back;

    AscSkillPreviewMenu(final ExileCore plugin, final Player player, final AscendancyConfig.Node node, final List<SkillDefinition> skills,
                        final Runnable back) {
        super(plugin, player, 3, "<dark_gray>昇華技能：" + AscendancyService.escape(node.name()));
        this.node = node;
        this.skills = List.copyOf(skills);
        this.back = back;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        set(SLOT_BACK, backButton("昇華天賦"));
        set(SLOT_CLOSE, closeButton());
        final List<Integer> slots = new ArrayList<>();
        for (final int slot : SLOTS) {
            slots.add(slot);
        }
        slots.subList(0, Math.min(slots.size(), skills.size())).sort(Integer::compare);
        for (int i = 0; i < skills.size() && i < slots.size(); i++) {
            set(slots.get(i), stack(skills.get(i)));
        }
    }

    private ItemStack stack(final SkillDefinition definition) {
        final PlayerState state = plugin.players() == null ? null : plugin.players().get(player);
        final boolean personal = state != null && state.hasCharacter() && plugin.skills() != null;
        final int level = personal ? plugin.skills().grantedLevel(player) : SkillLoadout.MAX_LEVEL;
        final List<String> lines = new ArrayList<>();
        if (personal) {
            try {
                lines.addAll(plugin.skills().describe(player, definition, new SkillLoadout.Entry(definition.id(), level)));
            } catch (final RuntimeException e) {
                lines.clear();
            }
        }
        if (lines.isEmpty()) {
            lines.add(tw.exilecore.skill.SkillService.headlineChips(definition));
            if (definition.description() != null && !definition.description().isBlank()) {
                lines.add("<gray>" + ItemRenderer.escape(definition.description()));
            }
        }
        lines.add("");
        lines.add("<#e7c56b>由昇華天賦「" + AscendancyService.escape(node.name()) + "」賦予<gray>：配了就有，沒有寶石");
        lines.add(personal ? "<dark_gray>數值照你目前的屬性、武器算（Lv." + level + "、沒有輔助）"
                : "<dark_gray>選一個角色之後，這裡會照你的屬性算出數值");
        return Menu.icon(Menu.material(definition.icon(), Material.AMETHYST_SHARD), "<#e7c56b>" + ItemRenderer.escape(definition.name()), lines);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (click == ClickType.DOUBLE_CLICK) {
            return;
        }
        if (slot == SLOT_BACK && back != null) {
            back.run();
        } else if (slot == SLOT_CLOSE) {
            goBack();
        }
    }
}
