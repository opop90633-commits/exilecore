package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5 補上的德魯伊輔助寶石（supports.yml）的機制：
 * <ul>
 *   <li>行嵐（Advancing Storm）、掠嵐（Morgana's Tempest）：風暴技能從施放者腳下出發，往準心指的地方移動，到了就停在那裡。</li>
 *   <li>蟄甲（Helbrym's Hide）：被輔助的技能讓你變身時，獲得赫布林的沉著幾秒：這段期間受到的傷害先記下來，
 *       效果結束時獲得記下的傷害 x% 的守護（4 秒）與 y% 的怒氣；結束前變成別的形態就直接沒了。</li>
 * </ul>
 * 屬性鍵放在這裡（不動 StatKeys）。
 */
public final class DruidSupports {

    /** FLAT：風暴從施放者腳下出發、往準心指的地方移動；值是 PoE2 的移動速度（25 = 每秒 2.5 公尺 = 3.75 格；2026-10-29 照站長換算）。 */
    public static final String STORM_ADVANCE = "storm.advance";
    /** MORE／INCREASED：風暴的移動速度（PoE2 Morgana's Tempest：更多 60%）。 */
    public static final String STORM_TRAVEL_SPEED = "storm.travel_speed";
    /** FLAT：被輔助的技能讓你變身時，獲得赫布林的沉著幾秒（PoE2 Helbrym's Hide：2 秒）。 */
    public static final String HELBRYM_SECONDS = "helbrym.seconds";
    /** FLAT：沉著結束時，獲得記下的傷害幾 % 的守護（PoE2：50%）。 */
    public static final String HELBRYM_GUARD = "helbrym.guard";
    /** FLAT：沉著結束時，獲得記下的傷害幾 % 的怒氣（PoE2：3%）。 */
    public static final String HELBRYM_RAGE = "helbrym.rage";
    /** 沉著給的守護留多久（tick；PoE2 support hide of helbrym guard duration 4000 ms）。 */
    static final int GUARD_TICKS = 80;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Composure> composures = new HashMap<>();

    /** 一段赫布林的沉著：哪個形態、什麼時候結束、記了多少傷害、結束時換成幾 % 守護與怒氣。 */
    private static final class Composure {
        final String form;
        final long untilTick;
        final double guardPercent;
        final double ragePercent;
        double stored;

        Composure(final String form, final long untilTick, final double guardPercent, final double ragePercent) {
            this.form = form;
            this.untilTick = untilTick;
            this.guardPercent = guardPercent;
            this.ragePercent = ragePercent;
        }
    }

    DruidSupports(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 這幾個屬性的說明（ModifierText 用）；不是的回傳 null。 */
    static String text(final Modifier modifier) {
        final double value = modifier.value();
        return switch (modifier.stat()) {
            case STORM_ADVANCE -> "風暴從你腳下出發，往準心指的地方移動（每秒 " + number(tw.exilecore.core.Units.metres(value / 10.0)) + " 格），到了就停在那裡"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
            case STORM_TRAVEL_SPEED -> modifier.type() == tw.exilecore.stats.ModType.MORE
                    ? number(Math.abs(value * 100)) + "% " + (value < 0 ? "更少" : "更多") + "風暴移動速度"
                    : (value < 0 ? "減少 " : "增加 ") + number(Math.abs(value * 100)) + "% 風暴移動速度";
            case tw.exilecore.stats.KeystoneKeys.SKILL_LIMIT -> "同時存在的上限 " + (value < 0 ? "" : "+") + number(value);
            case HELBRYM_SECONDS -> "讓你變身時獲得赫布林的沉著 " + number(value) + " 秒：期間受到的傷害記下來（結束前變成別的形態就沒了）";
            case HELBRYM_GUARD -> "沉著結束時，獲得記下的傷害 " + number(value) + "% 的守護（" + number(GUARD_TICKS / 20.0) + " 秒）";
            case HELBRYM_RAGE -> "沉著結束時，獲得記下的傷害 " + number(value) + "% 的怒氣";
            default -> null;
        };
    }

    private static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0);
    }

    // ---- 行嵐、掠嵐 ----

    /** 風暴每 tick 移動幾格（沒掛行嵐、掠嵐，或不是風暴技能就是 0）。 */
    static double stormPerTick(final SkillDefinition definition, final StatSheet effective, final Set<String> tags) {
        if (definition == null || effective == null || !definition.tags().contains(tw.exilecore.stats.KeystoneKeys.STORM)) {
            return 0;
        }
        final double speed = effective.flat(STORM_ADVANCE, tags);
        if (speed <= 0) {
            return 0;
        }
        final double scaled = speed * Math.max(0, 1 + effective.increased(STORM_TRAVEL_SPEED, tags)) * effective.more(STORM_TRAVEL_SPEED, tags);
        return tw.exilecore.core.Units.metres(scaled / 10.0) / 20.0; // PoE2 的 25 = 每秒 2.5 公尺 = 3.75 格 // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（這次之前一格當一公尺）
    }

    /** 剛放下的風暴（地面效果）改成從 from 出發往 to 移動。 */
    static void advanceStorm(final ExileCore plugin, final SkillZones zones, final Origin origin, final SkillBuild build, final Location to) {
        if (origin.owner() == null || plugin.players() == null) {
            return;
        }
        final double perTick = stormPerTick(build.definition(), build.profile().effective(plugin.players().sheet(origin.owner())),
                build.profile().tags());
        if (perTick > 0) {
            zones.drift(origin.owner(), build.definition().id(), origin.feet(), to, perTick);
        }
    }

    // ---- 蟄甲 ----

    /**
     * 放完會變身的技能（SkillService.shapeshift）：form 是變成的形態，changed 是這一下真的換了形態（本來不是這個形態）。
     * 換成別的形態時，原本的沉著直接沒了（照 PoE2）；被輔助的技能讓你變身才給新的沉著。
     */
    void shapeshifted(final Player player, final SkillDefinition definition, final String form, final boolean changed) {
        final UUID id = player.getUniqueId();
        final Composure current = composures.get(id);
        if (current != null && !current.form.equals(form)) {
            composures.remove(id); // 結束前變成別的形態：沒有效果
        }
        if (!changed) {
            return;
        }
        double seconds = 0;
        double guard = 0;
        double rage = 0;
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(definition.id());
        if (position < 0) {
            return;
        }
        for (final SupportDefinition support : service.supportsOf(state.skills().skill(position))) {
            for (final Modifier modifier : support.modifiers()) {
                switch (modifier.stat()) {
                    case HELBRYM_SECONDS -> seconds += modifier.value();
                    case HELBRYM_GUARD -> guard += modifier.value();
                    case HELBRYM_RAGE -> rage += modifier.value();
                    default -> {
                    }
                }
            }
        }
        if (seconds <= 0) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final int ticks = (int) Math.max(1, Math.round(seconds * 20));
        final Composure composure = new Composure(form, now + ticks, guard, rage);
        composures.put(id, composure);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(id, composure), ticks);
    }

    /** 沉著期間受到的傷害（CombatListener：真的扣了之後才記）。 */
    public void damaged(final Player player, final double amount) {
        final Composure composure = composures.get(player.getUniqueId());
        if (composure != null && amount > 0 && plugin.getServer().getCurrentTick() <= composure.untilTick) {
            composure.stored += amount;
        }
    }

    private void expire(final UUID id, final Composure composure) {
        if (composures.get(id) != composure) {
            return; // 中途換了形態，或有新的一段
        }
        composures.remove(id);
        final Player player = plugin.getServer().getPlayer(id);
        if (player == null || !player.isOnline() || composure.stored <= 0) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final double guard = composure.stored * composure.guardPercent / 100.0;
        if (guard > 0) {
            plugin.players().get(player).addGuard(guard, now + GUARD_TICKS);
        }
        final double rage = composure.stored * composure.ragePercent / 100.0;
        if (rage > 0) {
            service.resources().gainRage(player, rage);
        }
    }

    /** 測試用：記了多少傷害（沒有沉著是 -1）。 */
    double stored(final UUID id) {
        final Composure composure = composures.get(id);
        return composure == null ? -1 : composure.stored;
    }
}
