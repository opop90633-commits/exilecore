package tw.exilecore.combat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 死亡回顧（PoE2 的死亡畫面）：記住玩家最近挨的幾下——誰、哪一招、什麼類型、多少、扣完剩多少——死掉時印出最後 {@value #SHOW} 下、
 * 這段時間各類型承受多少與被擋掉幾成、哪個抗性沒滿。玩家知道自己為什麼死，調數值的人也有依據。
 * 持續傷害（點燃）同一個來源 1 秒內合併成一筆，才不會把大招擠出去。{@code /deaths} 再看一次上一次的。
 */
public final class DeathRecap implements Listener {

    /** 記幾下、印幾下。 */
    static final int KEEP = 12;
    static final int SHOW = 5;
    /** 持續傷害同一個來源在這麼多 tick 內合併成一筆。 */
    static final int MERGE_TICKS = 20;
    private static final String RULE = "━━━━━━━━━━━━━━━━━━━━━━━━";

    /**
     * 挨的一下（減免後實際扣的）。
     *
     * @param tick      哪個 tick
     * @param source    誰打的（怪物名稱、「環境」、「點燃」）
     * @param skill     哪一招（普攻、招式名稱、掉落…）
     * @param dealt     減免後各類型多少
     * @param outgoing  減免前各類型多少（算擋掉幾成）
     * @param es        護盾吸收的
     * @param life      扣到生命的
     * @param lifeAfter 扣完剩多少生命
     * @param maxLife   當時的最大生命
     * @param dot       是不是持續傷害（會合併）
     */
    public record Hit(long tick, String source, String skill, Map<DamageType, Double> dealt, Map<DamageType, Double> outgoing,
                      double es, double life, double lifeAfter, double maxLife, boolean dot) {

        public Hit {
            dealt = Map.copyOf(dealt);
            outgoing = Map.copyOf(outgoing);
        }

        double total() {
            return es + life;
        }

        /** 跟下一筆同來源的持續傷害合併。 */
        Hit merge(final Hit next) {
            final Map<DamageType, Double> sumDealt = new EnumMap<>(dealt);
            next.dealt.forEach((type, amount) -> sumDealt.merge(type, amount, Double::sum));
            final Map<DamageType, Double> sumOut = new EnumMap<>(outgoing);
            next.outgoing.forEach((type, amount) -> sumOut.merge(type, amount, Double::sum));
            return new Hit(next.tick, source, skill, sumDealt, sumOut, es + next.es, life + next.life, next.lifeAfter, next.maxLife, true);
        }
    }

    private final ExileCore plugin;
    private final Map<UUID, Deque<Hit>> hits = new HashMap<>();
    /** 上一次死亡的回顧（/deaths）。 */
    private final Map<UUID, List<String>> lastRecap = new HashMap<>();

    public DeathRecap(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 記一下。 */
    public void record(final Player player, final Hit hit) {
        if (hit.total() <= 0) {
            return;
        }
        final Deque<Hit> deque = hits.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>());
        final Hit last = deque.peekLast();
        if (hit.dot() && last != null && last.dot() && last.source().equals(hit.source()) && last.skill().equals(hit.skill())
                && hit.tick() - last.tick() <= MERGE_TICKS) {
            deque.pollLast();
            deque.addLast(last.merge(hit));
        } else {
            deque.addLast(hit);
        }
        while (deque.size() > KEEP) {
            deque.pollFirst();
        }
    }

    /** 上一次死亡的回顧；沒有就是空的。 */
    public List<String> last(final Player player) {
        return lastRecap.getOrDefault(player.getUniqueId(), List.of());
    }

    /** 整理成給聊天欄的行（MiniMessage）。沒有紀錄回傳空的。 */
    List<String> recap(final Player player, final long now) {
        final Deque<Hit> deque = hits.get(player.getUniqueId());
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        final List<Hit> all = new ArrayList<>(deque);
        final List<Hit> shown = all.subList(Math.max(0, all.size() - SHOW), all.size());
        final Hit fatal = all.get(all.size() - 1);
        final List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>━━━ <red><bold>☠ 死亡回顧</bold></red> <dark_gray>" + RULE);
        lines.add(String.format(Locale.ROOT, "<red>✖ 穿心 <white>%s</white><dark_gray>・<white>%s</white>　%s<dark_gray>（最大生命 %,d 的 %d%%）",
                fatal.source(), fatal.skill(), typeText(fatal.dealt()), Math.round(fatal.maxLife()), Math.round(fatal.total() / Math.max(1, fatal.maxLife()) * 100)));
        final double span = (shown.get(shown.size() - 1).tick() - shown.get(0).tick()) / 20.0;
        double sum = 0;
        for (final Hit hit : shown) {
            sum += hit.total();
        }
        lines.add(String.format(Locale.ROOT, "<gray>▼ 最後 %d 下（%.1f 秒內，共 <white>%,d</white>＝最大生命的 %d%%）",
                shown.size(), span, Math.round(sum), Math.round(sum / Math.max(1, fatal.maxLife()) * 100)));
        for (final Hit hit : shown) {
            final double ago = (now - hit.tick()) / 20.0;
            // 2026-10 美化：一行一下，前面一條剩下的生命條，再來是誰、什麼招、扣多少
            lines.add(String.format(Locale.ROOT, " %s <dark_gray>-%.1fs <gray>%s<dark_gray>・%s <red>-%,d</red> <dark_gray>%s%s",
                    lifeBar(hit.lifeAfter(), hit.maxLife()), ago, hit.source(), hit.skill(), Math.round(hit.total()), typeText(hit.dealt()),
                    hit.es() > 0 ? String.format(Locale.ROOT, "<dark_gray>（護盾吸收 %,d）", Math.round(hit.es())) : ""));
        }
        // 各類型合計與擋掉幾成
        final Map<DamageType, Double> dealt = new EnumMap<>(DamageType.class);
        final Map<DamageType, Double> outgoing = new EnumMap<>(DamageType.class);
        for (final Hit hit : shown) {
            hit.dealt().forEach((type, amount) -> dealt.merge(type, amount, Double::sum));
            hit.outgoing().forEach((type, amount) -> outgoing.merge(type, amount, Double::sum));
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String> parts = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        for (final Map.Entry<DamageType, Double> entry : dealt.entrySet()) {
            final DamageType type = entry.getKey();
            if (entry.getValue() <= 0.5) {
                continue;
            }
            final double before = outgoing.getOrDefault(type, 0.0);
            final double blocked = before > 0 ? Math.max(0, 1 - entry.getValue() / before) : 0;
            final String how = type == DamageType.PHYSICAL ? "護甲擋掉" : "抗性擋掉";
            parts.add(String.format(Locale.ROOT, "<%s>%s %,d</%s><dark_gray>（%s %d%%）", DamageNumbers.color(type), type.displayName(),
                    Math.round(entry.getValue()), DamageNumbers.color(type), how, Math.round(blocked * 100)));
            if (type != DamageType.PHYSICAL) {
                final double resist = sheet.get(type.mitigationStat());
                final double max = sheet.get(StatKeys.RESIST_MAX);
                if (resist < max - 0.5 && entry.getValue() >= fatal.maxLife() * 0.1) {
                    // 補滿抗性這段會少掉幾成：現在剩 (1 − r)，補滿剩 (1 − max)
                    final double keep = Math.max(0, 1 - Math.max(-60, Math.min(max, resist)) / 100.0);
                    final double capped = Math.max(0, 1 - max / 100.0);
                    final double less = keep > 0 ? 1 - capped / keep : 0;
                    hints.add(String.format(Locale.ROOT, "<yellow>✦ 提示：%s抗性 %d%%，沒滿（上限 %d%%）——補滿的話這段的%s傷害少 %d%%",
                            type.displayName(), Math.round(resist), Math.round(max), type.displayName(), Math.round(less * 100)));
                }
            }
        }
        if (!parts.isEmpty()) {
            lines.add("<gray>⚔ 承受：" + String.join("<gray>、", parts));
        }
        lines.addAll(hints);
        if (hints.isEmpty() && dealt.getOrDefault(DamageType.PHYSICAL, 0.0) > sum * 0.6 && fatal.total() >= fatal.maxLife() * 0.4) {
            lines.add("<yellow>✦ 提示：這一下太大，護甲擋不住大招——看到地上變色先走開。");
        }
        lines.add("<dark_gray>/deaths 可以再看一次");
        return lines;
    }

    /** 剩下的生命：10 格的小條（綠 → 黃 → 紅），最後一格是 0 就是死了。 */
    private static String lifeBar(final double life, final double max) {
        final double ratio = max <= 0 ? 0 : Math.max(0, Math.min(1, life / max));
        final int filled = (int) Math.ceil(ratio * 10 - 1e-9);
        final String color = ratio > 0.5 ? "#55dd55" : ratio > 0.25 ? "#ffd24a" : "#ff5555";
        return "<" + color + ">" + "▮".repeat(filled) + "</" + color + "><dark_gray>" + "▮".repeat(10 - filled) + "</dark_gray>";
    }

    /** 各類型的文字：<color>類型 數字</color>。 */
    private static String typeText(final Map<DamageType, Double> dealt) {
        final List<String> parts = new ArrayList<>();
        for (final DamageType type : DamageType.values()) {
            final double amount = dealt.getOrDefault(type, 0.0);
            if (amount >= 0.5) {
                parts.add(String.format(Locale.ROOT, "<%s>%s %,d</%s>", DamageNumbers.color(type), type.displayName(), Math.round(amount), DamageNumbers.color(type)));
            }
        }
        return parts.isEmpty() ? "<gray>0" : String.join(" ", parts);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final List<String> lines = recap(player, plugin.getServer().getCurrentTick());
        final Deque<Hit> deque = hits.remove(player.getUniqueId());
        try {
            tw.exilecore.balance.BalanceRecords.get(plugin).death(player, deque == null ? List.of() : new ArrayList<>(deque)); // 2026-10-25：平衡報表的死亡紀錄
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("死亡紀錄寫不進去", e);
        }
        if (lines.isEmpty()) {
            return;
        }
        lastRecap.put(player.getUniqueId(), lines);
        for (final String line : lines) {
            player.sendMessage(plugin.text().parse(line));
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        hits.remove(event.getPlayer().getUniqueId());
        lastRecap.remove(event.getPlayer().getUniqueId());
    }

    /** 2026-10-30：最近受到的幾下（不用死也能看）：時間、誰（身上的詛咒、印記）、什麼招、扣多少、擋掉多少前的傷害。 */
    public List<String> recent(final Player player, final long now) {
        final Deque<Hit> deque = hits.get(player.getUniqueId());
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>━━━ <#9fe8c8><bold>最近受到的傷害</bold></#9fe8c8> <dark_gray>" + RULE);
        for (final Hit hit : deque) {
            lines.add(String.format(Locale.ROOT, " <dark_gray>-%.1fs <gray>%s<dark_gray>・%s <red>-%,d</red> <dark_gray>%s%s",
                    (now - hit.tick()) / 20.0, hit.source(), hit.skill(), Math.round(hit.total()), typeText(hit.dealt()),
                    hit.es() > 0 ? String.format(Locale.ROOT, "<dark_gray>（護盾吸收 %,d）", Math.round(hit.es())) : ""));
        }
        return lines;
    }

    /** 2026-10-30：/hits：最近受到的幾下是誰打的（打你的怪身上有詛咒會寫「咒·xx」）。 */
    public static LiteralCommandNode<CommandSourceStack> hitsCommand(final ExileCore plugin) {
        return Commands.literal("hits")
                .executes(context -> {
                    if (!(context.getSource().getExecutor() instanceof Player player)) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                        return Command.SINGLE_SUCCESS;
                    }
                    final List<String> lines = plugin.combat() == null ? List.of()
                            : plugin.combat().recap().recent(player, plugin.getServer().getCurrentTick());
                    if (lines.isEmpty()) {
                        player.sendMessage(plugin.text().prefixed("<gray>最近沒有受到傷害"));
                        return Command.SINGLE_SUCCESS;
                    }
                    for (final String line : lines) {
                        player.sendMessage(plugin.text().parse(line));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /** /deaths：再看一次上一次的死亡回顧。 */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("deaths")
                .executes(context -> {
                    if (!(context.getSource().getExecutor() instanceof Player player)) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                        return Command.SINGLE_SUCCESS;
                    }
                    final List<String> lines = plugin.combat() == null ? List.of() : plugin.combat().recap().last(player);
                    if (lines.isEmpty()) {
                        player.sendMessage(plugin.text().prefixed("<gray>這次登入還沒死過（死掉時會印出最後幾下是誰打的）"));
                        return Command.SINGLE_SUCCESS;
                    }
                    for (final String line : lines) {
                        player.sendMessage(plugin.text().parse(line));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /** 怪物名稱加上等級與稀有度（回顧裡看得出來是誰）。 */
    static String describe(final String name, final int level, final String rarity) {
        return ItemRenderer.escape(name) + "<dark_gray>（Lv." + level + (rarity == null || rarity.isEmpty() ? "" : " " + rarity) + "）<gray>";
    }
}
