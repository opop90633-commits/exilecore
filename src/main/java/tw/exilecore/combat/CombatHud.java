package tw.exilecore.combat;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.stats.DamageType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 戰鬥數據顯示在畫面右邊的側欄（2026-10）：打出的每秒傷害與類型、受到的每秒傷害與類型、受到的最大一擊（{@link CombatMeter}）。
 * 打怪或被打時出現在側欄最上面，停手 5 秒收起來；選單的「設定」可以關（{@link PlayerSettings#COMBAT_METER}）。
 */
public final class CombatHud {

    /** 多久更新一次側欄（tick）。 */
    public static final long PERIOD = 10;

    private final ExileCore plugin;
    private final Map<UUID, CombatMeter> meters = new HashMap<>();
    /** 側欄上正顯示著戰鬥數據的人。 */
    private final Set<UUID> shown = new HashSet<>();
    /** 上一段戰鬥的統計（/dps；停手之後還查得到，下線清掉）。 */
    private final Map<UUID, CombatMeter.Fight> lastFights = new HashMap<>();

    public CombatHud(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private CombatMeter meter(final UUID player) {
        return meters.computeIfAbsent(player, key -> new CombatMeter());
    }

    /** 自己（或自己的召喚物、圖騰、陷阱）打中一下：各類型多少。 */
    public void dealt(final Player player, final Map<DamageType, Double> parts) {
        meter(player.getUniqueId()).dealt(now(), parts);
    }

    /** 同上，記在 skill 這個技能底下（技能的名字；2026-10 技能明細）。 */
    public void dealt(final Player player, final String skill, final Map<DamageType, Double> parts) {
        meter(player.getUniqueId()).dealt(now(), skill, parts);
    }

    /** 正在戰鬥中嗎（戰鬥數據這一段還沒結束；2026-10-20 快捷欄的戰吼提醒）。 */
    public boolean fighting(final UUID player) {
        final CombatMeter meter = meters.get(player);
        return meter != null && meter.active(now());
    }

    /** /dps：正在打的這一段（沒在打就是上一段）每個技能打了多少；都沒有回傳 null。 */
    public CombatMeter.Fight fight(final Player player) {
        final CombatMeter meter = meters.get(player.getUniqueId());
        if (meter != null && meter.fight().total() > 0) {
            return meter.fight();
        }
        return lastFights.get(player.getUniqueId());
    }

    /** /dps */
    public static com.mojang.brigadier.tree.LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> command(final ExileCore plugin) {
        return io.papermc.paper.command.brigadier.Commands.literal("dps")
                .executes(context -> {
                    final org.bukkit.command.CommandSender sender = context.getSource().getSender();
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(plugin.text().prefixed("<red>只有玩家能用"));
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    }
                    final CombatHud hud = plugin.combat() == null ? null : plugin.combat().hud();
                    final CombatMeter.Fight fight = hud == null ? null : hud.fight(player);
                    final List<String> lines = fight == null ? List.of() : CombatMeter.report(fight);
                    if (lines.isEmpty()) {
                        player.sendMessage(plugin.text().prefixed("<gray>還沒有戰鬥紀錄：去打一下怪再來看（停手 5 秒算一段結束）"));
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    }
                    for (final String line : lines) {
                        player.sendMessage(plugin.text().parse(line));
                    }
                    if (!PlayerSettings.on(plugin.players().get(player), PlayerSettings.COMBAT_SKILLS)) {
                        player.sendMessage(plugin.text().parse("<dark_gray>想在打的時候即時看：選單 → 設定 → 戰鬥數據：技能明細"));
                    }
                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /** 自己造成的一跳持續傷害（點燃、流血、中毒）：算進那種傷害類型，側欄另外列在「持續」。 */
    public void dealtDot(final UUID player, final AilmentType kind, final DamageType type, final double amount) {
        if (player != null && amount > 0 && plugin.getServer().getPlayer(player) != null) {
            meter(player).dealtDot(now(), kind, type, amount);
        }
    }

    /** 受到一下：actual 是真正扣掉的護盾＋生命＋魔力，parts 決定各類型的比例。 */
    public void taken(final Player player, final Map<DamageType, Double> parts, final double actual) {
        meter(player.getUniqueId()).taken(now(), parts, actual);
    }

    /** 受到一跳持續傷害（不算最大一擊）。 */
    public void takenDot(final Player player, final AilmentType kind, final DamageType type, final double actual) {
        meter(player.getUniqueId()).takenDot(now(), kind, type, actual);
    }

    /** 每 {@link #PERIOD} tick：在戰鬥中的人更新側欄，停手的收起來。 */
    public void tick() {
        // 2026-10：下線的人的上一段統計清掉（以前只有還在打的人下線才清，打完再下線的一直留著）
        lastFights.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        if (meters.isEmpty() && shown.isEmpty()) {
            return;
        }
        final long now = now();
        final tw.exilecore.gui.Sidebar sidebar = plugin.sidebar();
        for (final Iterator<Map.Entry<UUID, CombatMeter>> it = meters.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, CombatMeter> entry = it.next();
            final UUID uuid = entry.getKey();
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                it.remove();
                shown.remove(uuid);
                lastFights.remove(uuid);
                continue;
            }
            final boolean active = entry.getValue().active(now);
            if (!active) {
                final CombatMeter.Fight ended = entry.getValue().fight();
                if (ended.total() > 0) {
                    lastFights.put(uuid, ended); // 這一段結束了：留著給 /dps 查
                }
                it.remove();
            }
            if (!active || !PlayerSettings.enabled(plugin.players().get(player), PlayerSettings.COMBAT_METER)) {
                if (shown.remove(uuid) && sidebar != null) {
                    sidebar.combat(player, List.of());
                }
                continue;
            }
            if (sidebar != null) {
                sidebar.combat(player, CombatMeter.lines(entry.getValue().snapshot(now),
                        PlayerSettings.on(plugin.players().get(player), PlayerSettings.COMBAT_SKILLS)));
                shown.add(uuid);
            }
        }
        // 換了角色、資料清掉了的：側欄上還留著的收掉
        for (final Iterator<UUID> it = shown.iterator(); it.hasNext(); ) {
            final UUID uuid = it.next();
            if (!meters.containsKey(uuid)) {
                it.remove();
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && sidebar != null) {
                    sidebar.combat(player, List.of());
                }
            }
        }
    }

    /** 模組停用：全部收掉。 */
    public void clearAll() {
        final tw.exilecore.gui.Sidebar sidebar = plugin.sidebar();
        for (final UUID uuid : shown) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && sidebar != null) {
                sidebar.combat(player, List.of());
            }
        }
        shown.clear();
        meters.clear();
        lastFights.clear();
    }
}
