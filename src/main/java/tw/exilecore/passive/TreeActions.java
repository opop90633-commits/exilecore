package tw.exilecore.passive;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 天賦樹的對話框：天賦總覽（所有配點加起來）、網頁規劃器的連結、匯入規劃代碼（預覽要花幾點、多少金幣再確認）、說明。
 */
public final class TreeActions {

    private TreeActions() {
    }

    /** 天賦總覽：點數、加起來的能力，以及網頁規劃器、匯入、說明的按鈕。 */
    public static void summary(final ExileCore plugin, final Player player, final PassiveTree tree, final Runnable back) {
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色：/char"));
            return;
        }
        final List<PassiveNode> owned = inTreeOrder(tree, state.passives());
        final List<String> body = new ArrayList<>();
        body.add("<gray>" + character.characterClass().displayName() + " Lv." + character.level() + "　已配置 <white>" + owned.size()
                + "</white> 點，還能配 <white>" + plugin.players().availablePoints(state) + "</white> 點");
        body.add("");
        final List<String> totals = PassiveSummary.sum(owned);
        if (totals.isEmpty()) {
            body.add("<dark_gray>還沒有配任何天賦");
        } else {
            body.add("<#f5b942>天賦加起來：");
            for (final String line : totals) {
                body.add("<white>" + ItemRenderer.escape(line));
            }
        }
        // 珠寶孔裡的珠寶（孔位有配的）
        final List<String> jewels = new ArrayList<>();
        for (final PassiveNode node : owned) {
            final org.bukkit.inventory.ItemStack jewel = node.isSocket() ? state.jewel(node.id()) : null;
            if (jewel != null) {
                jewels.addAll(JewelSockets.describe(plugin, jewel));
            }
        }
        if (!jewels.isEmpty()) {
            body.add("");
            body.add("<#c77dff>珠寶：");
            body.addAll(jewels);
        }
        final List<Dialogs.Button> buttons = new ArrayList<>();
        final String url = plannerUrl(plugin, tree, player);
        if (url != null) {
            buttons.add(Dialogs.Button.link("<#77ccff>網頁規劃器", "<gray>在瀏覽器打開整棵樹（帶著你現在的配點）", url));
        }
        buttons.add(Dialogs.Button.run("<#f5b942>匯入規劃代碼", () -> promptImport(plugin, player, tree, back)));
        // 2026-10 月卡福利：天賦樹存好幾組
        buttons.add(Dialogs.Button.run("<#bcd4ff>天賦組（月卡）",
                () -> new tw.exilecore.sponsor.TreeSetMenu(plugin, player, tree, back).open()));
        if (plugin.ascendancy() != null) {
            buttons.add(Dialogs.Button.run("<#c77dff>昇華（試煉、昇華天賦）", () -> plugin.ascendancy().open(player))); // 2026-10：選單拿掉昇華，從這裡進
        }
        buttons.add(Dialogs.Button.run("<white>怎麼配點", () -> help(plugin, player, back)));
        buttons.add(Dialogs.Button.run("<white>回到天賦樹", back));
        plugin.dialogs().notice(player, "<dark_gray>天賦總覽", body, buttons, 2);
    }

    private static void help(final ExileCore plugin, final Player player, final Runnable back) {
        plugin.dialogs().notice(player, "<dark_gray>怎麼配點", List.of(
                "<gray>相鄰（上下左右）的節點才相連，要從職業起點一路連過去",
                "<gray>左鍵配一點；點遠處還沒連到的節點，會先用<#77aaff>藍色</#77aaff>畫出最短路徑、",
                "<gray>列出整條路加起來的能力，再點一次就整條配上",
                "<gray>右鍵退點：花金幣，未滿 20 級免費（不能把路切斷，先退最外面的）",
                "<gray>點空白的地方：視野移過去；「縮小地圖」：整棵樹分區看",
                "<#c77dff>珠寶孔<gray>：配了之後左鍵選一顆背包裡的珠寶放進去，詞綴直接加在身上；再左鍵拿回來",
                "",
                "<#f5b942>金色<gray>＝核心　<#ff55ff>紫色<gray>＝基石　<#c77dff>◇<gray>＝珠寶孔　<#55ffff>青色<gray>＝搜尋結果",
                "<green>綠<gray>／金＝已配置　<yellow>黃<gray>／白＝能配　<dark_gray>灰＝還連不到",
                "",
                "<gray>網頁規劃器：在瀏覽器規劃整棵樹，按「複製代碼」，回來按「匯入規劃代碼」貼上"),
                List.of(Dialogs.Button.run("<white>回到天賦樹", back)), 1);
    }

    /** 規劃器網址（帶著現在的配點）；網頁伺服器沒開時為 null。 */
    public static String plannerUrl(final ExileCore plugin, final PassiveTree tree, final Player player) {
        if (plugin.web() == null || !plugin.web().available(player)) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            return plugin.web().publicUrl() + "/tree";
        }
        return plugin.web().publicUrl() + "/tree#" + TreeCode.encode(tree, character.characterClass().id(), state.passives(),
                chosenAttributes(state, tree));
    }

    /** 已配的屬性點現在各是哪個屬性（2026-10：帶進規劃器的網址，規劃器照這個上色）。 */
    static java.util.Map<String, String> chosenAttributes(final PlayerState state, final PassiveTree tree) {
        final java.util.Map<String, String> chosen = new java.util.LinkedHashMap<>();
        for (final String id : state.passives()) {
            final PassiveNode node = tree.node(id);
            if (node != null && node.attributeChoice() > 0) {
                chosen.put(id, tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), id));
            }
        }
        return chosen;
    }

    /** 規劃裡指定了屬性、而且跟現在不一樣的屬性點（只看套用後有配的）。 */
    static java.util.Map<String, String> attributeChanges(final PlayerState state, final PassiveTree tree, final Set<String> result,
                                                          final java.util.Map<String, String> wanted) {
        final java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();
        for (final java.util.Map.Entry<String, String> entry : wanted.entrySet()) {
            final PassiveNode node = tree.node(entry.getKey());
            if (node == null || node.attributeChoice() <= 0 || !result.contains(entry.getKey())) {
                continue;
            }
            // 還沒配過的屬性點現在算職業的主屬性；規劃要的不一樣才算要改
            if (!entry.getValue().equals(tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), entry.getKey()))
                    || !state.progress().containsKey("attr." + entry.getKey())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        return changes;
    }

    /** 把屬性點改成規劃指定的屬性（存進角色進度），屬性表重算；回傳改了幾個。 */
    private static int applyAttributes(final ExileCore plugin, final Player player, final java.util.Map<String, String> changes) {
        if (changes.isEmpty() || plugin.characters() == null) {
            return 0;
        }
        for (final java.util.Map.Entry<String, String> entry : changes.entrySet()) {
            plugin.characters().setProgress(player, "attr." + entry.getKey(), entry.getValue());
        }
        final PlayerState state = plugin.players().get(player);
        state.markDirty();
        plugin.players().sheet(player);
        return changes.size();
    }

    /** /tree web：聊天欄給一個連結。 */
    public static void sendWebLink(final ExileCore plugin, final Player player, final PassiveTree tree) {
        final String url = plannerUrl(plugin, tree, player);
        if (url == null) {
            player.sendMessage(plugin.text().prefixed(plugin.web() != null && plugin.web().available()
                    ? "<red>伺服器還沒設定網頁規劃器的網址（config.yml 的 web.public-url），請跟管理員說"
                    : "<red>網頁規劃器沒有開（config.yml 的 web.enabled）"));
            return;
        }
        player.sendMessage(plugin.text().prefixed("<click:open_url:'" + url.replace("'", "%27") + "'><hover:show_text:'<gray>"
                + ItemRenderer.escape(url) + "'><#77ccff><u>[打開網頁天賦規劃器]</u></hover></click> <gray>規劃好按「複製代碼」，回來打 "
                + "<white>/tree import</white> 貼上"));
    }

    /** 匯入：跳出輸入框貼代碼。 */
    public static void promptImport(final ExileCore plugin, final Player player, final PassiveTree tree, final Runnable back) {
        plugin.dialogs().text(player, "<dark_gray>匯入天賦規劃",
                List.of("<gray>貼上網頁規劃器的代碼（或整個網址）", "<dark_gray>下一步會先列出要配哪些、退哪些，確認了才套用"),
                "代碼", "", 400, code -> preview(plugin, player, tree, code, back), back);
    }

    /** 解析代碼、算好要配哪些退哪些，給玩家確認。 */
    public static void preview(final ExileCore plugin, final Player player, final PassiveTree tree, final String code, final Runnable back) {
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        final PassiveState passive = new PassiveState(plugin, player, tree);
        final PassiveNode start = passive.start();
        if (character == null || start == null) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色：/char"));
            return;
        }
        final TreePlan.Plan plan;
        final java.util.Map<String, String> wantedAttributes;
        try {
            final TreeCode.Decoded decoded = TreeCode.decode(tree, code);
            wantedAttributes = decoded.attributes();
            if (!decoded.classId().equals(character.characterClass().id())) {
                final String planClass = classNames(decoded.classId());
                fail(plugin, player, "這是" + planClass + "的規劃，你的角色是" + character.characterClass().displayName(), back);
                return;
            }
            plan = TreePlan.compute(tree, start, Set.copyOf(state.passives()), decoded.nodes(),
                    plugin.players().availablePoints(state));
        } catch (final TreeCode.InvalidCode | TreePlan.InvalidPlan e) {
            fail(plugin, player, e.getMessage(), back);
            return;
        }
        // 2026-10：規劃裡選好的屬性點（力量／敏捷／智慧）
        final java.util.Map<String, String> attributeChanges = attributeChanges(state, tree, plan.result(), wantedAttributes);
        if (!plan.changes() && !attributeChanges.isEmpty()) {
            // 配點一樣，只有屬性點選的屬性不一樣：直接改（切換屬性本來就不花錢）
            final int changed = applyAttributes(plugin, player, attributeChanges);
            plugin.characters().save(player);
            player.sendMessage(plugin.text().prefixed("<green>配點跟規劃一樣；" + changed + " 個屬性點照規劃改好了（力量／敏捷／智慧）"
                    + (plan.later().isEmpty() ? "" : "<gray>（還有 " + plan.later().size() + " 點等升級）")));
            back.run();
            return;
        }
        if (!plan.changes()) {
            fail(plugin, player, plan.later().isEmpty() ? "你的配點已經跟規劃一樣了"
                    : "點數不夠：規劃還有 " + plan.later().size() + " 點沒配，升級後再匯入一次", back);
            return;
        }
        final long cost = passive.planCost(plan);
        final int free = passive.planFreeRefunds(plan);
        final List<String> body = new ArrayList<>();
        body.add("<gray>新配 <white>" + plan.added().size() + "</white> 點" + names(plan.added()));
        if (!plan.removed().isEmpty()) {
            body.add("<gray>退掉 <white>" + plan.removed().size() + "</white> 點" + names(plan.removed()));
            body.add(cost > 0 ? String.format(Locale.ROOT, "<#f5b942>退點要 %,d 金幣<gray>（你有 %,d）%s", cost, state.gold(),
                    free > 0 ? "，另外用掉 " + free + " 次免費退點" : "")
                    : free > 0 ? "<gray>用掉 " + free + " 次免費退點" : "<gray>退點免費（未滿 " + plugin.players().config().respecFreeUntilLevel() + " 級）");
        }
        if (!plan.later().isEmpty()) {
            body.add("<dark_gray>規劃還有 " + plan.later().size() + " 點這次點數不夠，升級後再匯入一次會接著配");
        }
        if (!attributeChanges.isEmpty()) {
            body.add("<gray>屬性點：<white>" + attributeChanges.size() + "</white> 個照規劃選的屬性（力量／敏捷／智慧）");
        }
        int jewelsBack = 0;
        for (final PassiveNode node : plan.removed()) {
            if (node.isSocket() && state.jewel(node.id()) != null) {
                jewelsBack++;
            }
        }
        if (jewelsBack > 0) {
            body.add("<#c77dff>退掉的珠寶孔裡有 " + jewelsBack + " 顆珠寶，會拿回背包（背包滿了會掉在腳下）");
        }
        final List<String> totals = PassiveSummary.sum(inTreeOrder(tree, plan.result()));
        if (!totals.isEmpty()) {
            body.add("");
            body.add("<#f5b942>套用後天賦加起來：");
            for (int i = 0; i < Math.min(14, totals.size()); i++) {
                body.add("<white>" + ItemRenderer.escape(totals.get(i)));
            }
            if (totals.size() > 14) {
                body.add("<dark_gray>…還有 " + (totals.size() - 14) + " 項");
            }
        }
        plugin.dialogs().confirm(player, "<dark_gray>套用天賦規劃？", body, "套用", () -> {
            final PassiveState now = new PassiveState(plugin, player, tree);
            // 看這個畫面的時候升級了、配了點：費用或要退的可能不一樣，重新算一次給玩家看
            if (!plugin.players().get(player).passives().equals(plan.base()) || now.planCost(plan) != cost
                    || now.planFreeRefunds(plan) != free) {
                player.sendMessage(plugin.text().prefixed("<yellow>你的配點或退點費用剛剛變了，重新算一次"));
                preview(plugin, player, tree, code, back);
                return;
            }
            final PassiveState.Result result = now.applyPlan(plan);
            if (!result.ok()) {
                fail(plugin, player, result.message(), back);
                return;
            }
            applyAttributes(plugin, player, attributeChanges(plugin.players().get(player), tree, plan.result(), wantedAttributes));
            plugin.characters().save(player);
            player.sendMessage(plugin.text().prefixed("<green>天賦規劃已套用：新配 " + plan.added().size() + " 點"
                    + (plan.removed().isEmpty() ? "" : "、退掉 " + plan.removed().size() + " 點")
                    + (plan.later().isEmpty() ? "" : "<gray>（還有 " + plan.later().size() + " 點等升級）")));
            back.run();
        }, back);
    }

    /** 照樹檔的順序列出（加總的行才會跟網頁規劃器排得一樣）。 */
    static List<PassiveNode> inTreeOrder(final PassiveTree tree, final Set<String> ids) {
        final List<PassiveNode> nodes = new ArrayList<>();
        for (final PassiveNode node : tree.ordered()) {
            if (ids.contains(node.id())) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static void fail(final ExileCore plugin, final Player player, final String message, final Runnable back) {
        plugin.dialogs().notice(player, "<dark_gray>沒辦法匯入", List.of("<red>" + ItemRenderer.escape(message)),
                List.of(Dialogs.Button.run("<white>回到天賦樹", back)), 1);
    }

    /** 列出核心、基石的名字（最多 4 個）。 */
    private static String names(final List<PassiveNode> nodes) {
        final List<String> named = new ArrayList<>();
        for (final PassiveNode node : nodes) {
            if (node.type() == PassiveNode.Type.NOTABLE || node.type() == PassiveNode.Type.KEYSTONE) {
                named.add(ItemRenderer.escape(node.name()));
            }
        }
        if (named.isEmpty()) {
            return "";
        }
        return "<dark_gray>（" + String.join("、", named.subList(0, Math.min(4, named.size())))
                + (named.size() > 4 ? " 等 " + named.size() + " 個" : "") + "）";
    }

    private static String classNames(final String classId) {
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            if (type.id().equals(classId)) {
                return type.displayName();
            }
        }
        return classId;
    }
}
