package tw.exilecore.skill;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import tw.exilecore.ExileCore;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 召喚物不推擠玩家、玩家也撞不到召喚物（2026-10）。
 * <p>
 * 伺服器這邊 {@code setCollidable(false)} 只管伺服器算的推擠；玩家自己被推開是客戶端算的，客戶端只看計分板隊伍的
 * 「碰撞規則」。所以把召喚物放進一個碰撞規則 NEVER 的隊伍——每一張玩家看得到的計分板（主計分板、側欄那張個人的）都要有。
 * 召喚物之間同一隊：互相不會打（本來就不會）。
 */
public final class MinionCollision {

    /** 隊伍名稱。 */
    public static final String TEAM = "ec_minions";
    /** 現在在場的召喚物（UUID 字串，計分板的 entry）。 */
    private static final Set<String> ENTRIES = ConcurrentHashMap.newKeySet();

    private MinionCollision() {
    }

    /** 這張計分板準備好隊伍（新的個人計分板建立時呼叫），把現在的召喚物都放進去。 */
    public static void prepare(final Scoreboard board) {
        final Team team = team(board);
        for (final String entry : ENTRIES) {
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
        }
    }

    static void add(final ExileCore plugin, final UUID minion) {
        final String entry = minion.toString();
        ENTRIES.add(entry);
        for (final Scoreboard board : boards(plugin)) {
            team(board).addEntry(entry);
        }
    }

    static void remove(final ExileCore plugin, final UUID minion) {
        final String entry = minion.toString();
        if (!ENTRIES.remove(entry)) {
            return;
        }
        for (final Scoreboard board : boards(plugin)) {
            final Team team = board.getTeam(TEAM);
            if (team != null && team.hasEntry(entry)) {
                team.removeEntry(entry);
            }
        }
    }

    private static Team team(final Scoreboard board) {
        Team team = board.getTeam(TEAM);
        if (team == null) {
            team = board.registerNewTeam(TEAM);
        }
        if (team.getOption(Team.Option.COLLISION_RULE) != Team.OptionStatus.NEVER) {
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        return team;
    }

    /** 主計分板＋線上玩家正在看的計分板（不重複）。 */
    private static Set<Scoreboard> boards(final ExileCore plugin) {
        final Map<Scoreboard, Boolean> boards = new IdentityHashMap<>();
        boards.put(plugin.getServer().getScoreboardManager().getMainScoreboard(), true);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            boards.put(player.getScoreboard(), true);
        }
        return boards.keySet();
    }
}
