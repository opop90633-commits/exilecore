package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeActions;
import tw.exilecore.passive.TreeCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 月卡福利（2026-10）：天賦樹、技能按鍵存好幾組，一鍵換回來。每個角色各自 {@value #SLOTS} 組，存在帳號進度裡。
 * <ul>
 *   <li>天賦樹：存成規劃代碼；套用時跟「匯入規劃代碼」一樣（先給你看要配、要退哪些，退點照常花金幣）。</li>
 *   <li>技能按鍵：存每個按鍵綁的技能；套用時技能面板上有的技能照存的綁回去（寶石、輔助不動）。</li>
 * </ul>
 * 月卡過期後存的組還在，但要有月卡才能存、才能套用。
 */
public final class PresetMenu extends Menu {

    public enum Kind { TREE, KEYS, GEAR }

    static final int SLOTS = 5;
    private static final int FIRST = 11;
    private static final int SLOT_BACK = 18;
    private static final String KEY = "sp.preset.";

    private final Kind kind;
    private final PassiveTree tree;
    private final Runnable back;
    private final Map<Integer, Integer> presets = new HashMap<>();

    public PresetMenu(final ExileCore plugin, final Player player, final Kind kind, final PassiveTree tree, final Runnable back) {
        super(plugin, player, 3, "<dark_gray>" + label(kind) + "存檔（月卡）");
        this.kind = kind;
        this.tree = tree;
        this.back = back;
    }

    private static String label(final Kind kind) {
        return switch (kind) {
            case TREE -> "天賦樹";
            case KEYS -> "技能按鍵";
            case GEAR -> "裝備";
        };
    }

    private String key(final int index) {
        final CharacterRecord character = plugin.players().get(player).character();
        return KEY + kind.name().toLowerCase(java.util.Locale.ROOT) + "." + (character == null ? 0 : character.id()) + "." + index;
    }

    private String saved(final int index) {
        return plugin.bounties() == null ? null : plugin.bounties().progressValue(player, key(index));
    }

    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    @Override
    protected void draw() {
        presets.clear();
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final boolean card = card();
        set(4, icon(Material.CLOCK, SponsorService.CARD_COLOR + label(kind) + "存檔",
                switch (kind) {
                    case TREE -> "<gray>把現在的配點存起來，之後一鍵換回來（退點照常花金幣）";
                    case KEYS -> "<gray>存每個按鍵綁的技能；套用時面板上有的技能照存的綁回去";
                    case GEAR -> "<gray>存身上每一格裝的是哪一件；套用時從背包找出那幾件換上（換下來的放回背包）";
                },
                "<gray>每個角色各自 " + SLOTS + " 組",
                card ? "<green>月卡：可以使用" : "<red>要有月卡才能存、才能套用（存過的會留著）"));
        for (int i = 0; i < SLOTS; i++) {
            final String value = saved(i);
            final List<String> lore = new ArrayList<>();
            if (value == null || value.isEmpty()) {
                lore.add("<dark_gray>空的");
            } else {
                lore.addAll(describe(value));
            }
            lore.add("");
            if (value != null && !value.isEmpty()) {
                lore.add("<yellow>左鍵：套用");
            }
            lore.add("<yellow>Shift＋左鍵：把現在的" + (kind == Kind.TREE ? "配點" : kind == Kind.KEYS ? "按鍵" : "裝備") + "存到這一組");
            if (value != null && !value.isEmpty()) {
                lore.add("<yellow>Shift＋右鍵：清掉");
            }
            set(FIRST + i, icon(value == null || value.isEmpty() ? Material.PAPER : Material.WRITABLE_BOOK,
                    "<white>第 " + (i + 1) + " 組", lore));
            presets.put(FIRST + i, i);
        }
        set(SLOT_BACK, backButton(kind == Kind.TREE ? "天賦樹" : kind == Kind.KEYS ? "技能面板" : "角色"));
        set(26, closeButton());
    }

    private List<String> describe(final String value) {
        final List<String> lines = new ArrayList<>();
        if (kind == Kind.GEAR) {
            final String[] entries = value.split(";", -1);
            for (int i = 0; i < Math.min(entries.length, PlayerState.JEWELLERY_SLOTS.length); i++) {
                final int eq = entries[i].indexOf('=');
                if (eq > 0) {
                    lines.add("<gray>" + PlayerState.JEWELLERY_SLOTS[i].displayName() + "：<white>" + ItemRenderer.escape(entries[i].substring(eq + 1)));
                }
            }
            if (lines.isEmpty()) {
                lines.add("<gray>什麼都沒裝");
            }
            return lines;
        }
        if (kind == Kind.TREE) {
            try {
                final TreeCode.Decoded decoded = TreeCode.decode(tree, value);
                lines.add("<gray>配了 <white>" + decoded.nodes().size() + "</white> 點");
            } catch (final TreeCode.InvalidCode e) {
                lines.add("<red>存的代碼讀不出來（天賦樹改過？）");
            }
            return lines;
        }
        final String[] ids = value.split(",", -1);
        for (int slot = 0; slot < Math.min(ids.length, SkillLoadout.BIND_SLOTS); slot++) {
            if (!ids[slot].isEmpty() && !ids[slot].equals("-")) {
                final String name = plugin.skills() == null || plugin.skills().config().skill(ids[slot]) == null ? ids[slot]
                        : plugin.skills().config().skill(ids[slot]).name();
                lines.add("<gray>" + tw.exilecore.character.HotbarLayout.of(plugin.players().get(player)).shortKeyName(slot) + "：<white>" + ItemRenderer.escape(name));
            }
        }
        if (lines.isEmpty()) {
            lines.add("<gray>全部是普攻");
        }
        return lines;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            back.run();
            return;
        }
        if (slot == 26) {
            goBack();
            return;
        }
        final Integer index = presets.get(slot);
        if (index == null) {
            return;
        }
        if (!card()) {
            fail("要有月卡才能使用存檔");
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        if (character == null || plugin.bounties() == null) {
            fail("先建立角色");
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            write(index, null, "清掉第 " + (index + 1) + " 組");
            return;
        }
        if (click == ClickType.SHIFT_LEFT) {
            final String value;
            if (kind == Kind.GEAR) {
                final List<String> entries = new ArrayList<>();
                for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
                    final org.bukkit.inventory.ItemStack worn = state.jewellery(i);
                    final java.util.Optional<tw.exilecore.item.ItemData> data = worn == null || worn.isEmpty() ? java.util.Optional.empty() : plugin.items().read(worn);
                    entries.add(data.isEmpty() ? "-" : data.get().uuid() + "=" + data.get().name().replace(";", " ").replace("=", " "));
                }
                value = String.join(";", entries);
            } else if (kind == Kind.TREE) {
                value = TreeCode.encode(tree, character.characterClass().id(), state.passives());
            } else {
                final SkillLoadout loadout = state.skills();
                final List<String> ids = new ArrayList<>();
                for (int bind = 0; bind < SkillLoadout.BIND_SLOTS; bind++) {
                    final SkillLoadout.Entry entry = loadout.boundSkill(bind);
                    ids.add(entry == null ? "-" : entry.skillId());
                }
                value = String.join(",", ids);
            }
            write(index, value, "存到第 " + (index + 1) + " 組");
            return;
        }
        if (click != ClickType.LEFT) {
            return;
        }
        final String value = saved(index);
        if (value == null || value.isEmpty()) {
            fail("這一組是空的：Shift＋左鍵存");
            return;
        }
        if (kind == Kind.GEAR) {
            applyGear(state, index, value);
            return;
        }
        if (kind == Kind.TREE) {
            // 跟「匯入規劃代碼」一樣：先列出要配、要退的，確認後才套用
            TreeActions.preview(plugin, player, tree, value, () -> new PresetMenu(plugin, player, kind, tree, back).open());
            return;
        }
        final SkillLoadout loadout = state.skills();
        final String[] ids = value.split(",", -1);
        final List<String> missing = new ArrayList<>();
        for (int bind = 0; bind < SkillLoadout.BIND_SLOTS; bind++) {
            final String id = bind < ids.length ? ids[bind] : "-";
            if (id.isEmpty() || id.equals("-")) {
                loadout.bind(bind, -1);
                continue;
            }
            final int position = loadout.find(id);
            if (position < 0) {
                loadout.bind(bind, -1);
                missing.add(plugin.skills() == null || plugin.skills().config().skill(id) == null ? id : plugin.skills().config().skill(id).name());
            } else {
                loadout.bind(bind, position);
            }
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<green>技能按鍵：套用第 " + (index + 1) + " 組"
                + (missing.isEmpty() ? "" : "<yellow>（面板上沒有：" + ItemRenderer.escape(String.join("、", missing)) + "，改成普攻）")));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
        redraw();
    }

    /** 套用裝備存檔：每一格要的那件在背包裡就換上（戒指兩格互換也算）；不在背包的列出來。 */
    private void applyGear(final PlayerState state, final int index, final String value) {
        final String[] entries = value.split(";", -1);
        final List<String> missing = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int changed = 0;
        for (int i = 0; i < Math.min(entries.length, PlayerState.JEWELLERY_SLOTS.length); i++) {
            final int eq = entries[i].indexOf('=');
            if (eq <= 0) {
                continue; // 存的時候這一格是空的：不動
            }
            final String uuid = entries[i].substring(0, eq);
            final String name = entries[i].substring(eq + 1);
            if (uuid.equals(uuidOf(state.jewellery(i)))) {
                continue;
            }
            // 戒指放在另一格：兩格互換
            if ((i == 0 || i == 1) && uuid.equals(uuidOf(state.jewellery(1 - i)))) {
                final ItemStack other = state.jewellery(1 - i);
                state.setJewellery(1 - i, state.jewellery(i));
                state.setJewellery(i, other);
                plugin.characters().gear().sync(player);
                plugin.players().sheet(player);
                changed++;
                continue;
            }
            int found = -1;
            for (int slot = 0; slot < 36 && found < 0; slot++) {
                if (uuid.equals(uuidOf(player.getInventory().getItem(slot)))) {
                    found = slot;
                }
            }
            if (found < 0) {
                missing.add(name);
                continue;
            }
            final String problem = plugin.characters().gear().equipAt(player, found, i);
            if (problem != null) {
                problems.add(name + "：" + problem);
            } else {
                changed++;
            }
        }
        player.sendMessage(plugin.text().prefixed("<green>裝備：套用第 " + (index + 1) + " 組（換了 " + changed + " 件）"
                + (missing.isEmpty() ? "" : "<yellow>　背包裡找不到：" + ItemRenderer.escape(String.join("、", missing)))
                + (problems.isEmpty() ? "" : "<red>　" + ItemRenderer.escape(String.join("、", problems)))));
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1.1f);
        redraw();
    }

    private String uuidOf(final org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return plugin.items().read(stack).map(tw.exilecore.item.ItemData::uuid).orElse(null);
    }

    private void write(final int index, final String value, final String what) {
        final Map<String, String> values = new HashMap<>();
        values.put(key(index), value);
        if (!plugin.bounties().commitAccount(player, values, label(kind) + "存檔：" + what)) {
            fail("帳號資料載入中，請稍等");
            return;
        }
        player.sendMessage(plugin.text().prefixed("<green>" + label(kind) + "：" + what));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.3f);
        redraw();
    }

    private void fail(final String message) {
        player.sendMessage(plugin.text().prefixed("<red>" + message));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
    }
}
