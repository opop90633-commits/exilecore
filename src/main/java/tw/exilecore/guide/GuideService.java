package tw.exilecore.guide;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.quest.QuestLog;
import tw.exilecore.zone.ZoneService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 新手引導：側欄最上面寫現在這一步（{@link Guide}），每 2 秒看一次有沒有完成；完成一步聊天欄打勾、提示下一步。
 * 進度記在角色進度（{@value #PROGRESS_DONE}）；帳號上已經有 {@value Guide#VETERAN_LEVEL} 級以上的角色就不顯示（分身不用再看）。
 */
public final class GuideService {

    /** 角色進度：寫了就是不再顯示（做完、/guide off）。 */
    public static final String PROGRESS_DONE = "guide.done";
    /** 角色進度：打開過選單（蹲下＋F）。 */
    public static final String PROGRESS_MENU = "guide.menu";
    /** 角色進度前綴：做過的事（{@link #milestone}）：guide.m.currency、guide.m.salvage。 */
    public static final String PROGRESS_MILESTONE = "guide.m.";
    /** 角色進度前綴：第一次遇到的提示已經說過（{@link Hints}）。 */
    public static final String PROGRESS_HINT = "hint.";
    /** 2026-10-30：做過的事（{@link #milestone}）：翻滾、嵌符文、打開掉落過濾。 */
    public static final String DODGE = "dodge";
    public static final String RUNE = "rune";
    public static final String FILTER = "filter";
    private static final int HOTBAR_SLOTS = 9;

    private final ExileCore plugin;
    /** 上次看到的那一步（判斷有沒有前進）。 */
    private final Map<UUID, Integer> shown = new HashMap<>();

    public GuideService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 每 40 tick。 */
    public void tick() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                update(player, false);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的新手引導出錯", player.getName(), e);
            }
        }
    }

    /** 這個玩家現在的狀態。 */
    Guide.Snapshot snapshot(final Player player, final PlayerState state) {
        boolean questStarted = false;
        for (final String key : state.progress().keySet()) {
            if (key.startsWith(QuestLog.PREFIX)) {
                questStarted = true;
                break;
            }
        }
        boolean skillBound = false;
        for (int slot = 0; slot < tw.exilecore.character.SkillLoadout.BIND_SLOTS && !skillBound; slot++) {
            skillBound = state.skills().boundSkill(slot) != null;
        }
        boolean flasks = false;
        if (plugin.flasks() != null) {
            // 2026-10-18：藥劑欄位置可以改（設定 → 按鍵），掃整排快捷列、只看該玩家的藥劑欄（原本只看第 1、2 格）
            for (int slot = 0; slot < HOTBAR_SLOTS && !flasks; slot++) {
                flasks = plugin.flasks().kindAt(player, slot) != null
                        && plugin.flasks().read(player.getInventory().getItem(slot)).isPresent();
            }
        }
        final Map<String, String> progress = state.progress();
        // ---- 第二階段（2026-10-20）----
        int storyStarted = -1;
        int storyDone = -1;
        for (int i = 0; i < Guide.MAIN_QUESTS.size(); i++) {
            final QuestLog.State quest = QuestLog.parse(progress.get(QuestLog.key(Guide.MAIN_QUESTS.get(i))));
            if (quest.started()) {
                storyStarted = i;
            }
            if (quest.phase() == QuestLog.Phase.DONE) {
                storyDone = i;
            }
        }
        boolean supportSocketed = false;
        for (int position = 0; position < SkillLoadout.MAX_SKILLS && !supportSocketed; position++) {
            final SkillLoadout.Entry entry = state.skills().skill(position);
            supportSocketed = entry != null && !entry.supportIds().isEmpty();
        }
        boolean stashUsed = false;
        if (plugin.stash() != null) {
            final tw.exilecore.stash.Stash stash = plugin.stash().stash(player).orElse(null);
            if (stash != null) {
                stashUsed = !stash.currencies().isEmpty() || stash.mapCount() > 0;
                for (int page = 0; page < stash.pageCount() && !stashUsed; page++) {
                    stashUsed = stash.itemCount(page) > 0;
                }
            }
        }
        int flaskLevel = 0;
        if (plugin.flasks() != null) {
            for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
                final tw.exilecore.flask.FlaskService.FlaskData data = plugin.flasks().read(player.getInventory().getItem(slot)).orElse(null);
                final tw.exilecore.flask.FlaskBase base = data == null ? null : plugin.flasks().config().base(data.base());
                if (base != null) {
                    flaskLevel = Math.max(flaskLevel, base.level());
                }
            }
        }
        boolean trialDone = false;
        int mapsDone = 0;
        for (final String key : progress.keySet()) {
            if (key.startsWith(tw.exilecore.ascendancy.Ascension.TRIAL_PREFIX)) {
                trialDone = true;
            } else if (key.startsWith("atlas.")) {
                mapsDone++; // 跟任務的 maps 目標同一個算法（QuestLog.mapsCompleted）
            }
        }
        // 2026-10-30 照站長（已經點過星圖的人沒觸發）：星圖天賦改成帳號共用之後存在帳號上，不在角色進度裡；這次之前只看角色進度
        String atlas = progress.get(tw.exilecore.map.AtlasTree.PROGRESS_KEY);
        if ((atlas == null || atlas.isBlank()) && plugin.maps() != null) {
            final java.util.Map<String, String> account = plugin.maps().atlasData(player);
            atlas = account == null ? null : account.get(tw.exilecore.map.AtlasTree.PROGRESS_KEY);
        }
        final java.util.Set<String> milestones = new java.util.HashSet<>();
        for (final String key : progress.keySet()) {
            if (key.startsWith(PROGRESS_MILESTONE)) {
                milestones.add(key.substring(PROGRESS_MILESTONE.length()));
            }
        }
        boolean charm = false;
        for (int i = 0; i < 3 && !charm; i++) {
            charm = tw.exilecore.flask.CharmService.charmAt(state, i) != null;
        }
        return new Guide.Snapshot(state.hasCharacter(), progress.containsKey(PROGRESS_MENU), questStarted, skillBound, flasks,
                progress.containsKey(ZoneService.PROGRESS_LAST), !state.passives().isEmpty(), state.level(),
                storyStarted, storyDone, state.skills().size(), supportSocketed, progress.containsKey(PROGRESS_MILESTONE + "currency"),
                stashUsed, progress.containsKey(PROGRESS_MILESTONE + "salvage"), flaskLevel,
                plugin.bounties() != null && plugin.bounties().boardSeen(player), trialDone,
                progress.containsKey(tw.exilecore.ascendancy.Ascension.CHOSEN), atlas != null && !atlas.isBlank(), mapsDone, milestones, charm);
    }

    /** 做過某件事（用通貨、拆解）：別的模組叫，記在角色進度；沒角色不記。 */
    public void milestone(final Player player, final String id) {
        final PlayerState state = plugin.players().get(player);
        if (state.hasCharacter() && !state.progress().containsKey(PROGRESS_MILESTONE + id)) {
            plugin.characters().setProgress(player, PROGRESS_MILESTONE + id, "1");
        }
    }

    // ---- 第一次遇到就提示（2026-10-20）----

    /**
     * 第一次遇到某樣東西的提示：聊天欄一行，每個角色每種只說一次（角色進度 hint.代號）。
     * 分身（帳號上有老角色）跟關掉引導的人不提示。
     */
    public void hint(final Player player, final String id) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || !wants(player, state)) {
            return;
        }
        final String message = Hints.MESSAGES.get(id);
        if (message == null || state.progress().containsKey(PROGRESS_HINT + id)) {
            return;
        }
        plugin.characters().setProgress(player, PROGRESS_HINT + id, "1");
        player.sendMessage(plugin.text().prefixed("<#9fe8c8>提示　<gray>" + message));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
    }

    /** 死亡：第一次死的提示（GuideModule 的事件）。 */
    public void onDeath(final Player player) {
        hint(player, Hints.DEATH);
    }

    /** 每 2 秒：看背包、通貨袋裡有沒有第一次出現的東西；等級到 10、20 也提示。 */
    private void scanHints(final Player player, final PlayerState state) {
        if (state.level() >= 10) {
            hint(player, Hints.LEVEL10);
        }
        if (state.level() >= 20) {
            hint(player, Hints.LEVEL20);
        }
        if (state.level() >= 40) {
            hint(player, Hints.LEVEL40); // 2026-10-30
        }
        final java.util.Set<String> found = new java.util.HashSet<>();
        for (final org.bukkit.inventory.ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final tw.exilecore.currency.CurrencyType currency = plugin.currencies() == null ? null : plugin.currencies().read(stack).orElse(null);
            if (currency != null) {
                found.add(Hints.forCurrency(currency));
                continue;
            }
            if (plugin.flasks() != null && plugin.flasks().charms() != null && plugin.flasks().charms().isCharm(stack)) {
                found.add(Hints.CHARM); // 2026-10-30
                continue;
            }
            if (plugin.flasks() != null && plugin.flasks().isFlask(stack)) {
                found.add(Hints.FLASK);
                continue;
            }
            final tw.exilecore.item.ItemData data = plugin.items() == null ? null : plugin.items().read(stack).orElse(null);
            final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
            if (base == null) {
                continue;
            }
            if (base.isMap()) {
                found.add(Hints.MAP);
            } else if (base.isJewel()) {
                found.add(Hints.JEWEL);
            } else if (!data.identified()) {
                found.add(Hints.UNIDENTIFIED);
            }
        }
        if (plugin.currencies() != null) {
            for (final String id : state.bag().pouch().all().keySet()) {
                final tw.exilecore.currency.CurrencyType currency = plugin.currencies().repository().get(id);
                if (currency != null) {
                    found.add(Hints.forCurrency(currency));
                }
            }
        }
        found.remove(null);
        for (final String id : found) {
            hint(player, id);
        }
    }

    /** 打開了主選單（新手教學的「打開選單」那一步）。 */
    public void noteMenu(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (state.hasCharacter() && !state.progress().containsKey(PROGRESS_MENU) && !state.progress().containsKey(PROGRESS_DONE)) {
            plugin.characters().setProgress(player, PROGRESS_MENU, "1");
        }
    }

    /** 這個玩家要不要看引導：有角色、還沒做完、帳號上沒有老角色。 */
    private boolean wants(final Player player, final PlayerState state) {
        if (!state.isLoaded()) {
            return false;
        }
        if (state.progress().containsKey(PROGRESS_DONE)) {
            return false;
        }
        if (state.hasCharacter()) {
            for (final CharacterRecord other : plugin.characters().roster(player)) {
                if (other.id() != state.character().id() && other.level() >= Guide.VETERAN_LEVEL) {
                    return false; // 分身：帳號上已經有打過一輪的角色
                }
            }
        }
        return true;
    }

    /**
     * 重算這一步並更新側欄；前進了就在聊天欄打勾。
     *
     * @param force 一定重畫（登入、切角色）
     */
    public void update(final Player player, final boolean force) {
        final PlayerState state = plugin.players().get(player);
        if (!wants(player, state)) {
            if (shown.remove(player.getUniqueId()) != null || force) {
                plugin.sidebar().guide(player, List.of());
            }
            return;
        }
        if (state.hasCharacter()) {
            scanHints(player, state); // 2026-10-20：第一次遇到就提示
        }
        final Guide.Snapshot snapshot = snapshot(player, state);
        final int current = Guide.current(snapshot);
        final Integer before = shown.put(player.getUniqueId(), current);
        if (before != null && current > before && current <= Guide.STEPS.size()) {
            final Guide.Step done = Guide.STEPS.get(current - 1);
            player.sendMessage(plugin.text().prefixed("<green>✔ 完成：" + done.title()));
            if (current < Guide.STEPS.size()) {
                player.sendMessage(plugin.text().prefixed(Guide.hintLine(Guide.STEPS.get(current))));
                title(player, "<#9fe8c8>下一步：" + Guide.STEPS.get(current).title(), "<gray>" + Guide.STEPS.get(current).hint());
            } else if (Guide.allDone(snapshot)) {
                player.sendMessage(plugin.text().prefixed("<gray>新手教學完成！接下來照側欄的任務走；/guide 可以再看一次"));
                title(player, "<green>新手教學完成！", "<gray>照側欄的任務繼續冒險");
            } else {
                player.sendMessage(plugin.text().prefixed("<gray>這一階段的教學做完了，主線再往前走會有下一步；/guide 可以看全部"));
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        } else if (before == null && current < Guide.STEPS.size()) {
            // 剛上線（或剛打開教學）：在聊天欄說一次現在這一步，畫面中間也提示一下
            player.sendMessage(plugin.text().prefixed("<#9fe8c8>新手教學 " + (current + 1) + "／" + Guide.STEPS.size() + "　"
                    + Guide.hintLine(Guide.STEPS.get(current))));
            if (current == 0) {
                title(player, "<#9fe8c8>歡迎來到星骸", "<gray>跟著右邊側欄的「新手教學」一步一步來");
            }
        }
        if (current >= Guide.STEPS.size()) {
            // 2026-10-20：第二階段跟主線連動——剩下的步驟都還沒解鎖時先收起來，等主線往前走再出現；真的全做完才記 done
            if (state.hasCharacter() && Guide.allDone(snapshot)) {
                plugin.characters().setProgress(player, PROGRESS_DONE, "1");
            }
            plugin.sidebar().guide(player, List.of());
            shown.remove(player.getUniqueId());
            return;
        }
        plugin.sidebar().guide(player, Guide.sidebar(current));
    }

    private void title(final Player player, final String main, final String sub) {
        tw.exilecore.gui.Titles.show(player, plugin.text().parse(main), plugin.text().parse(sub), // 2026-10-26：空的那段不送
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3500),
                        java.time.Duration.ofMillis(600)));
    }

    public void forget(final Player player) {
        shown.remove(player.getUniqueId());
    }

    /** /guide、/guide off、/guide on */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("guide")
                .executes(context -> run(plugin, context.getSource().getExecutor() instanceof Player player ? player : null, null))
                .then(Commands.argument("action", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (final String option : List.of("off", "on")) {
                                if (option.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(option);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> run(plugin, context.getSource().getExecutor() instanceof Player player ? player : null,
                                StringArgumentType.getString(context, "action"))))
                .build();
    }

    private static int run(final ExileCore plugin, final Player player, final String action) {
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        final GuideService service = plugin.guide();
        if (service == null) {
            player.sendMessage(plugin.text().prefixed("<red>新手引導沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final PlayerState state = plugin.players().get(player);
        if ("off".equalsIgnoreCase(action)) {
            if (state.hasCharacter()) {
                plugin.characters().setProgress(player, PROGRESS_DONE, "1");
            }
            service.update(player, true);
            player.sendMessage(plugin.text().prefixed("<gray>新手引導關掉了（/guide on 再打開）"));
            return Command.SINGLE_SUCCESS;
        }
        if ("on".equalsIgnoreCase(action)) {
            if (state.hasCharacter()) {
                plugin.characters().removeProgress(player, PROGRESS_DONE);
            }
            service.forget(player);
            service.update(player, true);
            player.sendMessage(plugin.text().prefixed("<gray>新手引導打開了"));
            return Command.SINGLE_SUCCESS;
        }
        final Guide.Snapshot snapshot = service.snapshot(player, state);
        for (final String line : Guide.chat(snapshot, Guide.current(snapshot))) {
            player.sendMessage(plugin.text().parse(line));
        }
        if (state.progress().containsKey(PROGRESS_DONE)) {
            player.sendMessage(plugin.text().parse("<dark_gray>  （側欄的引導已經關掉；/guide on 打開）"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
