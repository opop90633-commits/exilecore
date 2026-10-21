package tw.exilecore.map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.training.DummyRecord;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ec bossdps（管理員，2026-10-21）：全服的傷害分佈，拿來定終局、極境、巔峰的血量。只有統計，沒有玩家名字。
 * <ul>
 *   <li>打訓練假人的紀錄：每個角色一筆（資料庫裡所有角色＋線上的人最新的），換成打 Boss 的每秒傷害（dummies.yml 的 boss_factor）。</li>
 *   <li>重開伺服器之後的 Boss 戰（終局、極境、巔峰、金庫之主）：每一場每個人實際打 Boss 的每秒傷害（照整場的秒數平均，
 *       含換階段、護盾）、遞減後的，照種類與階級分組。</li>
 * </ul>
 */
public final class BossDpsCommand {

    /** 分佈的區間（打 Boss 每秒）。 */
    static final double[] EDGES = {30_000, 100_000, 300_000, 1_000_000, 3_000_000, 10_000_000};

    private BossDpsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("bossdps").executes(context -> {
            run(plugin, context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        });
    }

    private record Row(long characterId, int level, DummyRecord record) {
    }

    private static void run(final ExileCore plugin, final CommandSender sender) {
        if (plugin.training() == null || plugin.maps() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>訓練假人或地圖模組沒開"));
            return;
        }
        // 線上的人用記憶體裡最新的（還沒存檔的紀錄也算）
        final Map<Long, Row> online = new HashMap<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            if (state != null && state.hasCharacter()) {
                final DummyRecord record = DummyRecord.decode(state.progress().get(DummyRecord.KEY));
                if (record != null) {
                    online.put(state.character().id(), new Row(state.character().id(), state.character().level(), record));
                }
            }
        }
        sender.sendMessage(plugin.text().prefixed("<gray>讀取所有角色的假人紀錄…"));
        plugin.database().async(connection -> {
            final List<Row> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT p.character_id, p.progress_value, c.char_level FROM progress p "
                    + "JOIN characters c ON c.id = p.character_id WHERE p.progress_key = ? AND c.deleted_at IS NULL")) {
                select.setString(1, DummyRecord.KEY);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        final DummyRecord record = DummyRecord.decode(result.getString("progress_value"));
                        if (record != null) {
                            rows.add(new Row(result.getLong("character_id"), result.getInt("char_level"), record));
                        }
                    }
                }
            }
            return rows;
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                sender.sendMessage(plugin.text().prefixed("<red>讀不到資料庫：" + error.getMessage()));
                return;
            }
            final Map<Long, Row> all = new LinkedHashMap<>();
            for (final Row row : rows) {
                all.put(row.characterId(), row);
            }
            all.putAll(online);
            report(plugin, sender, new ArrayList<>(all.values()));
        }));
    }

    private static void report(final ExileCore plugin, final CommandSender sender, final List<Row> rows) {
        final long week = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        final List<Double> boss = new ArrayList<>();
        final List<Double> recent = new ArrayList<>();
        final List<Double> dummy = new ArrayList<>();
        for (final Row row : rows) {
            final double value = plugin.training().bossDps(row.record());
            boss.add(value);
            dummy.add((double) row.record().dps());
            if (row.record().at() >= week) {
                recent.add(value);
            }
        }
        sender.sendMessage(plugin.text().parse("<gold>── 打訓練假人的紀錄（" + rows.size() + " 個角色；近 7 天打過的 " + recent.size() + " 個）"));
        if (!boss.isEmpty()) {
            sender.sendMessage(plugin.text().parse("<gray>打假人：" + summary(dummy)));
            sender.sendMessage(plugin.text().parse("<gray>換成打 Boss：" + summary(boss)));
            sender.sendMessage(plugin.text().parse("<gray>分佈（打 Boss 每秒）：" + buckets(boss)));
            if (!recent.isEmpty()) {
                sender.sendMessage(plugin.text().parse("<gray>近 7 天打過的（打 Boss）：" + summary(recent)));
            }
        }
        final List<MapService.BossFight> fights = plugin.maps().recentFights();
        sender.sendMessage(plugin.text().parse("<gold>── 重開伺服器之後的 Boss 戰（" + fights.size() + " 場；實際打 Boss 每秒，照整場秒數平均）"));
        if (fights.isEmpty()) {
            sender.sendMessage(plugin.text().parse("<dark_gray>還沒有人打倒終局、極境、巔峰或金庫之主（主控台的「Boss 戰紀錄」那幾行是重開之前的）"));
            return;
        }
        final Map<String, List<MapService.BossFight>> groups = new java.util.TreeMap<>();
        for (final MapService.BossFight fight : fights) {
            groups.computeIfAbsent(fight.kind() + " T" + String.format(Locale.ROOT, "%02d", fight.tier()), k -> new ArrayList<>()).add(fight);
        }
        for (final Map.Entry<String, List<MapService.BossFight>> group : groups.entrySet()) {
            final List<Double> seconds = new ArrayList<>();
            final List<Double> raw = new ArrayList<>();
            final List<Double> capped = new ArrayList<>();
            int solo = 0;
            for (final MapService.BossFight fight : group.getValue()) {
                seconds.add(fight.seconds());
                solo += fight.perPlayer().size() == 1 ? 1 : 0;
                for (final double[] one : fight.perPlayer()) {
                    raw.add(one[0]);
                    capped.add(one[1]);
                }
            }
            sender.sendMessage(plugin.text().parse("<white>" + group.getKey().replace(" T0", " T") + "<gray>：" + group.getValue().size() + " 場（單人 "
                    + solo + "）・時間中位 <white>" + Math.round(percentile(seconds, 0.5)) + " 秒</white>・每人每秒 " + summary(raw)
                    + (percentile(capped, 0.9) < percentile(raw, 0.9) - 1 ? "<dark_gray>（遞減後前 10% " + wan(percentile(capped, 0.9)) + "）" : "")));
        }
    }

    /** 中位數、前 25%、前 10%、最高。 */
    static String summary(final List<Double> values) {
        return "中位 <white>" + wan(percentile(values, 0.5)) + "</white>・前 25% <white>" + wan(percentile(values, 0.75))
                + "</white>・前 10% <white>" + wan(percentile(values, 0.9)) + "</white>・最高 <white>" + wan(percentile(values, 1.0)) + "</white>";
    }

    /** 各區間有幾個。 */
    static String buckets(final List<Double> values) {
        final int[] counts = new int[EDGES.length + 1];
        for (final double value : values) {
            int i = 0;
            while (i < EDGES.length && value >= EDGES[i]) {
                i++;
            }
            counts[i]++;
        }
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            final String label = i == 0 ? "<" + wan(EDGES[0]) : i == EDGES.length ? "≥" + wan(EDGES[i - 1]) : wan(EDGES[i - 1]) + "–" + wan(EDGES[i]);
            text.append(i == 0 ? "" : "｜").append(label).append(" <white>").append(counts[i]).append("</white>");
        }
        return text.toString();
    }

    /** 第 p 分位（0–1，最近的那一個；1 = 最高）。 */
    static double percentile(final List<Double> values, final double p) {
        if (values.isEmpty()) {
            return 0;
        }
        final List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        final int index = (int) Math.min(sorted.size() - 1, Math.max(0, Math.ceil(p * sorted.size()) - 1));
        return sorted.get(index);
    }

    /** 12.3 萬、4,500。 */
    static String wan(final double value) {
        if (value >= 100_000_000) {
            return String.format(Locale.ROOT, "%.2f 億", value / 100_000_000);
        }
        if (value >= 10_000) {
            return String.format(Locale.ROOT, value >= 1_000_000 ? "%.0f 萬" : "%.1f 萬", value / 10_000);
        }
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }
}
