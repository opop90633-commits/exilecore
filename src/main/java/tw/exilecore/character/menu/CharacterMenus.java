package tw.exilecore.character.menu;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.StarterKits;
import tw.exilecore.passive.PassiveMenu;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色相關視窗的入口：角色選擇、職業選擇、角色面板、天賦樹（含搜尋）。
 */
public final class CharacterMenus {

    private final ExileCore plugin;

    public CharacterMenus(final ExileCore plugin) {
        this.plugin = plugin;
    }

    public void openSelect(final Player player) {
        new CharacterSelectMenu(plugin, player).open();
    }

    public void openClassSelect(final Player player, final int slot) {
        new ClassSelectMenu(plugin, player, slot).open();
    }

    /** 選完職業後：選新手武器（沒有能選的武器就直接輸入名稱，送職業預設的武器）。 */
    public void openWeaponSelect(final Player player, final int slot, final CharacterClass characterClass) {
        final List<StarterKits.Option> options = StarterKits.weapons(plugin.items().repository(),
                plugin.characters().config(), characterClass);
        if (options.isEmpty()) {
            promptName(player, slot, characterClass, null);
            return;
        }
        new WeaponSelectMenu(plugin, player, slot, characterClass, options).open();
    }

    public void openPanel(final Player player) {
        new CharacterPanelMenu(plugin, player).open();
    }

    public void openTree(final Player player) {
        final PassiveTree tree = treeOrNull(player);
        if (tree != null) {
            new PassiveMenu(plugin, player, tree, Set.of(), null).open();
        }
    }

    /** 搜尋天賦：高亮符合的節點並把視野移到第一個。 */
    public void openTreeSearch(final Player player, final String text) {
        final PassiveTree tree = treeOrNull(player);
        if (tree == null) {
            return;
        }
        final List<PassiveNode> matches = tree.search(text);
        if (matches.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>沒有名稱或說明含「" + escape(text) + "」的天賦"));
            new PassiveMenu(plugin, player, tree, Set.of(), null).open();
            return;
        }
        final Set<String> ids = new HashSet<>();
        for (final PassiveNode node : matches) {
            ids.add(node.id());
        }
        player.sendMessage(plugin.text().prefixed("<gray>找到 <white>" + matches.size() + "</white> 個含「" + escape(text)
                + "」的天賦，已用<#55ffff>青色</#55ffff>標出；用箭頭捲動可以看到其他的"));
        new PassiveMenu(plugin, player, tree, ids, matches.get(0)).open();
    }

    /** 選單裡的搜尋按鈕：跳出輸入框打關鍵字。 */
    public void promptTreeSearch(final Player player) {
        plugin.dialogs().text(player, "<dark_gray>搜尋天賦", List.of("<gray>名稱或說明含這段文字的天賦會用<#55ffff>青色</#55ffff>標出",
                        "<dark_gray>例如「生命」、「暴擊」、「火焰」、「斧」"),
                "關鍵字", "", 30, text -> openTreeSearch(player, text), () -> openTree(player));
    }

    private PassiveTree treeOrNull(final Player player) {
        final PassiveTree tree = plugin.passives().tree();
        if (tree == null) {
            player.sendMessage(plugin.text().prefixed("<red>天賦樹沒有載入（看主控台的錯誤）"));
            return null;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色：/char"));
            return null;
        }
        return tree;
    }

    /**
     * 選完職業與新手武器後：跳出輸入框打名稱。取消就回到選武器（沒有武器可選時回角色選擇）。
     *
     * @param weaponBaseId 選的新手武器基底；null = 用第一個能選的
     */
    public void promptName(final Player player, final int slot, final CharacterClass characterClass, final String weaponBaseId) {
        promptName(player, slot, characterClass, weaponBaseId, null, "");
    }

    private void promptName(final Player player, final int slot, final CharacterClass characterClass, final String weaponBaseId,
                            final String problem, final String typed) {
        final List<String> body = new java.util.ArrayList<>();
        body.add("<gray>" + characterClass.displayName() + "　" + plugin.characters().config().nameMin() + " 到 "
                + plugin.characters().config().nameMax() + " 個字，中英文、數字、底線；全服不能重複（大家看到、私訊用的就是這個名字）");
        if (problem != null) {
            body.add("<red>" + escape(problem) + "，請再輸入一次");
        }
        plugin.dialogs().text(player, "<dark_gray>新角色的名稱", body, "名稱", typed,
                Math.max(plugin.characters().config().nameMax(), 1), text -> {
                    final String error = plugin.characters().validateName(text);
                    if (error != null) {
                        promptName(player, slot, characterClass, weaponBaseId, error, text);
                        return;
                    }
                    plugin.characters().create(player, slot, characterClass, weaponBaseId, text, failure -> {
                        if (tw.exilecore.character.CharacterService.NAME_TAKEN.equals(failure)) {
                            promptName(player, slot, characterClass, weaponBaseId, failure, text); // 換一個名字
                        } else {
                            player.sendMessage(plugin.text().prefixed("<red>" + failure));
                        }
                    });
                }, () -> {
                    if (weaponBaseId == null) {
                        openSelect(player);
                    } else {
                        openWeaponSelect(player, slot, characterClass);
                    }
                });
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
