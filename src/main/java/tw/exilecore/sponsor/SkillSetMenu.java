package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.Sound;
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
 * 技能組（2026-10，月卡福利）：每個角色 {@value PlayerState#GEAR_SETS} 組技能面板（技能、等級、輔助、按鍵），
 * 切換時現在的面板收回原本那一組（技能和掛著的輔助都還在，只是不生效），換成那一組的。只有正在用的那一組有效果。
 * 沒有月卡也能切回第 1 組。
 * <p>
 * 沒在用的組存在角色進度 skillset.組.段（一段最多 {@value #CHUNK} 字）。
 */
public final class SkillSetMenu extends Menu {

    private static final int FIRST = 11;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;
    private static final int CHUNK = 200;
    private static final int MAX_CHUNKS = 16;
    private static final char ENTRY = '\u001E';
    private static final char FIELD = '\u001F';

    private final Runnable back;

    public SkillSetMenu(final ExileCore plugin, final Player player, final Runnable back) {
        super(plugin, player, 3, "<dark_gray>技能組（月卡）");
        this.back = back;
    }

    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardDaysLeft(player) > 0;
    }

    private static String chunkKey(final int set, final int chunk) {
        return "skillset." + set + "." + chunk;
    }

    /** 面板 → 文字：每個技能「位置、代號、等級、輔助」，最後一段是按鍵。 */
    static String encode(final SkillLoadout loadout) {
        final StringBuilder out = new StringBuilder();
        for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
            final SkillLoadout.Entry entry = loadout.skill(position);
            if (entry == null) {
                continue;
            }
            out.append(position).append(FIELD).append(entry.skillId()).append(FIELD).append(entry.level()).append(FIELD)
                    .append(entry.encodeSupports()).append(ENTRY);
        }
        out.append('B');
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            out.append(FIELD).append(loadout.bound(slot));
        }
        return out.toString();
    }

    static SkillLoadout decode(final String text) {
        final SkillLoadout loadout = new SkillLoadout();
        if (text == null || text.isEmpty()) {
            return loadout;
        }
        for (final String part : text.split(String.valueOf(ENTRY), -1)) {
            if (part.isEmpty()) {
                continue;
            }
            final String[] fields = part.split(String.valueOf(FIELD), -1);
            if (part.charAt(0) == 'B') {
                for (int slot = 0; slot < SkillLoadout.BIND_SLOTS && slot + 1 < fields.length; slot++) {
                    try {
                        loadout.bind(slot, Integer.parseInt(fields[slot + 1]));
                    } catch (final NumberFormatException ignored) {
                        // 壞掉的按鍵：普攻
                    }
                }
                continue;
            }
            if (fields.length < 4) {
                continue;
            }
            try {
                loadout.set(Integer.parseInt(fields[0]), SkillLoadout.Entry.decode(fields[1], Integer.parseInt(fields[2]), fields[3]));
            } catch (final RuntimeException ignored) {
                // 壞掉的一個技能：跳過
            }
        }
        return loadout;
    }

    private String stored(final PlayerState state, final int set) {
        final StringBuilder code = new StringBuilder();
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            final String part = state.progress().get(chunkKey(set, chunk));
            if (part == null) {
                break;
            }
            code.append(part);
        }
        return code.isEmpty() ? null : code.toString();
    }

    private boolean store(final PlayerState state, final int set, final String code) {
        if (code != null && code.length() > CHUNK * MAX_CHUNKS) {
            return false;
        }
        int chunk = 0;
        if (code != null) {
            for (int from = 0; from < code.length(); from += CHUNK, chunk++) {
                plugin.characters().setProgress(player, chunkKey(set, chunk), code.substring(from, Math.min(code.length(), from + CHUNK)));
            }
        }
        for (; chunk < MAX_CHUNKS; chunk++) {
            if (state.progress().containsKey(chunkKey(set, chunk))) {
                plugin.characters().removeProgress(player, chunkKey(set, chunk));
            }
        }
        return true;
    }

    private String skillName(final String id) {
        return plugin.skills() == null || plugin.skills().config().skill(id) == null ? id : plugin.skills().config().skill(id).name();
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final int active = state.activeSkillSet();
        final boolean card = card();
        set(4, icon(Material.CLOCK, SponsorService.CARD_COLOR + "技能組",
                "<gray>每一組有自己的技能面板：技能、等級、輔助寶石、按鍵",
                "<gray>只有正在用的那一組有效果；其他組的技能和輔助還在，只是不生效",
                "<gray>在技能面板放寶石、升級、綁按鍵，改的都是正在用的那一組",
                card ? "<green>月卡：可以隨時切換" : "<red>沒有月卡：只能切回第 1 組"));
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final boolean current = set == active;
            final SkillLoadout loadout = current ? state.skills() : decode(stored(state, set));
            final List<String> lore = new ArrayList<>();
            lore.add(current ? "<green>使用中" : "<gray>放著（不生效）");
            final List<SkillLoadout.Entry> entries = loadout.entries();
            int shown = 0;
            for (final SkillLoadout.Entry entry : entries) {
                if (shown++ >= 8) {
                    lore.add("<dark_gray>…還有 " + (entries.size() - 8) + " 個");
                    break;
                }
                lore.add("<white>" + ItemRenderer.escape(skillName(entry.skillId())) + " <gray>Lv." + entry.level()
                        + (entry.supportIds().isEmpty() ? "" : " <dark_gray>＋" + entry.supportIds().size() + " 輔助"));
            }
            if (entries.isEmpty()) {
                lore.add("<dark_gray>空的");
            }
            lore.add("");
            if (!current) {
                lore.add(card || set == 0 ? "<yellow>左鍵：換成這一組" : "<dark_gray>要有月卡才能換成這一組");
            }
            final ItemStack icon = icon(current ? Material.ENCHANTED_BOOK : entries.isEmpty() ? Material.BOOK : Material.WRITABLE_BOOK,
                    (current ? "<green>" : "<white>") + "第 " + (set + 1) + " 組", lore);
            set(FIRST + set, current ? highlight(icon) : icon);
        }
        set(SLOT_BACK, backButton("技能面板"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            back.run();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        final int set = slot - FIRST;
        if (set < 0 || set >= PlayerState.GEAR_SETS || click != ClickType.LEFT) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        final int active = state.activeSkillSet();
        if (set == active) {
            return;
        }
        if (!card() && set != 0) {
            player.sendMessage(plugin.text().prefixed("<red>要有月卡才能換成第 " + (set + 1) + " 組（沒有月卡可以切回第 1 組）"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
            return;
        }
        final SkillLoadout next = decode(stored(state, set));
        if (!store(state, active, encode(state.skills()))) {
            player.sendMessage(plugin.text().prefixed("<red>這一組的技能太多，存不下"));
            return;
        }
        store(state, set, null);
        state.setSkills(next);
        if (plugin.skills() != null) {
            plugin.skills().migrateRemoved(player, state); // 2026-10 PoE2 改版拿掉的技能、輔助換成未切割寶石
        }
        plugin.characters().setProgress(player, PlayerState.SKILL_SET_KEY, String.valueOf(set));
        plugin.players().markDirty(player);
        plugin.players().sheet(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<green>換成第 " + (set + 1) + " 組技能"));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
        redraw();
    }
}
