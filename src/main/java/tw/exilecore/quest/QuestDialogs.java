package tw.exilecore.quest;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.npc.NpcType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 跟 NPC 說話的對話視窗：一段對話可能有好幾頁——剛剛說完的那一步的台詞（任務更新或完成）、要選的獎勵、可以接的任務，
 * 按「繼續」一頁一頁看下去。營地 NPC 每一頁都有一個按鈕可以直接打開它的功能。
 */
final class QuestDialogs {

    private final ExileCore plugin;
    private final QuestService service;

    QuestDialogs(final ExileCore plugin, final QuestService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 劇情人物沒有任務的事：隨機一句閒聊。 */
    void idle(final Player player, final String npc) {
        final QuestConfig.Npc story = service.config().npc(npc);
        final List<String> body = new ArrayList<>();
        if (story != null && !story.idle().isEmpty()) {
            body.add(story.idle().get(ThreadLocalRandom.current().nextInt(story.idle().size())));
        } else {
            body.add("<gray>……");
        }
        final List<Dialogs.Button> buttons = List.of(Dialogs.Button.run("<white>任務日誌",
                () -> new QuestMenu(plugin, player, service).open()));
        plugin.dialogs().notice(player, title(npc), body, buttons, 1);
    }

    /**
     * 一段對話的下一頁：剛剛說完的台詞（spoken，已經算進進度了）→ 要選的獎勵 → 可以接的任務；
     * 都沒有了就結束（營地 NPC 不會自己打開功能，按鈕在每一頁上）。
     */
    void converse(final Player player, final String npc, final List<QuestLog.Spoke> spoken, final Runnable function) {
        if (!player.isConnected()) {
            return;
        }
        if (!spoken.isEmpty()) {
            showSpoken(player, npc, spoken.getFirst(), List.copyOf(spoken.subList(1, spoken.size())), function);
            return;
        }
        final QuestLog log = service.log(player);
        final QuestConfig.Quest waiting = QuestService.waitingChoice(log, npc);
        if (waiting != null) {
            showChoice(player, npc, waiting, function);
            return;
        }
        final List<QuestConfig.Quest> offers = log.offers(npc);
        if (!offers.isEmpty()) {
            showOffer(player, npc, offers, 0, function);
        }
    }

    private boolean more(final Player player, final String npc, final List<QuestLog.Spoke> rest) {
        if (!rest.isEmpty()) {
            return true;
        }
        final QuestLog log = service.log(player);
        return QuestService.waitingChoice(log, npc) != null || !log.offers(npc).isEmpty();
    }

    /** 剛剛說完的那一步：NPC 的台詞＋任務更新（下一步）或任務完成（獎勵）。 */
    private void showSpoken(final Player player, final String npc, final QuestLog.Spoke spoke, final List<QuestLog.Spoke> rest,
                            final Runnable function) {
        final QuestConfig.Quest quest = spoke.quest();
        final List<String> body = new ArrayList<>(quest.stage(spoke.stage()).lines());
        final QuestLog.State state = service.log(player).state(quest);
        body.add("");
        if (state.phase() == QuestLog.Phase.DONE) {
            body.add("<#f5b942>任務完成：" + quest.kind().color() + QuestText.escape(quest.name()));
            final List<String> rewards = QuestRewards.describe(quest, state.choice());
            if (!rewards.isEmpty()) {
                body.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", rewards)));
            }
        } else if (state.phase() == QuestLog.Phase.ACTIVE) {
            body.add("<gray>任務更新：" + quest.kind().color() + QuestText.escape(quest.name()));
            body.add("<gray>下一步：<white>" + QuestText.escape(quest.stage(state.stage()).text()));
        }
        final List<Dialogs.Button> buttons = new ArrayList<>();
        if (more(player, npc, rest)) {
            buttons.add(Dialogs.Button.run("<green>繼續", () -> converse(player, npc, rest, function)));
        }
        addFunction(buttons, npc, function);
        plugin.dialogs().notice(player, title(npc), body, buttons, Math.max(1, buttons.size()));
    }

    /** 最後一步要選獎勵：台詞＋固定的獎勵＋每個選項一個按鈕。 */
    private void showChoice(final Player player, final String npc, final QuestConfig.Quest quest, final Runnable function) {
        final List<String> body = new ArrayList<>(quest.stages().getLast().lines());
        body.add("");
        body.add("<#f5b942>完成任務：" + quest.kind().color() + QuestText.escape(quest.name()));
        final List<String> fixed = new ArrayList<>();
        if (quest.passivePoints() > 0) {
            fixed.add("天賦點 +" + quest.passivePoints());
        }
        for (final QuestConfig.Bonus bonus : quest.bonuses()) {
            fixed.add(QuestRewards.describe(bonus));
        }
        if (!fixed.isEmpty()) {
            body.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", fixed)));
        }
        body.add("<gray>再選一個（選了就不能換）：");
        final List<Dialogs.Button> buttons = new ArrayList<>();
        for (final QuestConfig.Choice choice : quest.choices()) {
            buttons.add(Dialogs.Button.run("<white>" + QuestText.escape(choice.label()), () -> {
                if (service.choose(player, quest, npc, choice.id())) {
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                    converse(player, npc, List.of(), function);
                } else {
                    player.sendMessage(plugin.text().prefixed("<red>這個任務現在不能在這裡完成"));
                }
            }));
        }
        addFunction(buttons, npc, function);
        plugin.dialogs().notice(player, title(npc), body, buttons, 1);
    }

    /** 可以接的任務：台詞、任務說明、第一步、獎勵；「接下任務」「下一個」。 */
    private void showOffer(final Player player, final String npc, final List<QuestConfig.Quest> offers, final int index,
                           final Runnable function) {
        final QuestConfig.Quest quest = offers.get(index);
        final List<String> body = new ArrayList<>(quest.offer());
        if (!body.isEmpty()) {
            body.add("");
        }
        body.add("<gray>" + quest.kind().displayName() + "任務：" + quest.kind().color() + QuestText.escape(quest.name()));
        if (!quest.summary().isBlank()) {
            body.add("<dark_gray>" + QuestText.escape(quest.summary()));
        }
        body.add("<gray>第一步：<white>" + QuestText.escape(quest.stage(0).text()));
        final List<String> rewards = QuestRewards.describe(quest, null);
        if (!rewards.isEmpty()) {
            body.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", rewards)));
        }
        final List<Dialogs.Button> buttons = new ArrayList<>();
        buttons.add(Dialogs.Button.run("<green>接下任務", () -> {
            if (service.accept(player, quest)) {
                converse(player, npc, List.of(), function);
            } else {
                player.sendMessage(plugin.text().prefixed("<red>這個任務現在不能接了"));
            }
        }));
        if (index + 1 < offers.size()) {
            buttons.add(Dialogs.Button.run("<white>下一個（還有 " + (offers.size() - index - 1) + " 個）",
                    () -> showOffer(player, npc, offers, index + 1, function)));
        }
        addFunction(buttons, npc, function);
        plugin.dialogs().notice(player, title(npc), body, buttons, Math.min(2, buttons.size()));
    }

    /** 營地 NPC（與有功能的劇情人物，例如軍需官的委託板）的功能按鈕。 */
    private void addFunction(final List<Dialogs.Button> buttons, final String npc, final Runnable function) {
        if (function == null) {
            return;
        }
        final QuestConfig.Npc story = service.config().npc(npc);
        final NpcType type = NpcType.parse(npc);
        final String label = story != null && story.opens() != null ? QuestConfig.FUNCTIONS.getOrDefault(story.opens(), "打開")
                : type == null ? "打開" : type.action();
        buttons.add(Dialogs.Button.run("<#f5b942>" + label, function));
    }

    /** 視窗標題：名字＋頭銜。 */
    private String title(final String npc) {
        final String title = service.speakerTitle(npc);
        return "<#f5b942>" + QuestText.escape(service.speakerName(npc)) + (title.isBlank() ? "" : " <gray>" + QuestText.escape(title));
    }

    /** 任務日誌右鍵：重看一個任務的說明與每一步。 */
    void recap(final Player player, final QuestConfig.Quest quest) {
        final QuestLog log = service.log(player);
        final QuestLog.State state = log.state(quest);
        final List<String> body = new ArrayList<>();
        if (!quest.summary().isBlank()) {
            body.add("<gray>" + QuestText.escape(quest.summary()));
            body.add("");
        }
        for (int i = 0; i < quest.stages().size(); i++) {
            final boolean done = state.phase() == QuestLog.Phase.DONE || (state.phase() == QuestLog.Phase.ACTIVE && i < state.stage());
            final boolean current = state.phase() == QuestLog.Phase.ACTIVE && i == state.stage();
            final String progress = current ? QuestText.progress(log, quest) : "";
            body.add((done ? "<green>✔ " : current ? "<yellow>▶ " : "<dark_gray>・ ") + QuestText.escape(quest.stage(i).text())
                    + (progress.isEmpty() ? "" : " <white>" + progress));
        }
        final List<String> rewards = QuestRewards.describe(quest, state.choice());
        if (!rewards.isEmpty()) {
            body.add("");
            body.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", rewards)));
        }
        if (state.phase() == QuestLog.Phase.LOCKED || state.phase() == QuestLog.Phase.AVAILABLE) {
            final String reason = log.lockReason(quest);
            body.add("");
            body.add(reason == null ? "<gray>去找" + QuestText.escape(service.speakerName(quest.giver())) + "接這個任務"
                    : "<dark_gray>" + QuestText.escape(reason));
        }
        final List<Dialogs.Button> buttons = List.of(Dialogs.Button.run("<white>回任務日誌",
                () -> new QuestMenu(plugin, player, service).open()));
        plugin.dialogs().notice(player, quest.kind().color() + QuestText.escape(quest.name()), body, buttons, 1);
    }
}
