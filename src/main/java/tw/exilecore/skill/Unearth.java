package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Corpses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 2026-10-27 照 PoE2 0.5 Unearth（掘骸）：骨刺打完之後，錐形範圍內的屍體每具變成一隻骨構體（skills.yml 的 unearth_construct，
 * 15 秒、同時最多 8～20 隻隨寶石等級；等級、輔助照掘骸，召喚物的輔助套在骨構體上）。
 * 這次之前拆成兩顆：骨棘陣只有骨刺、掘骸用身邊一具屍體叫一隻（2026-10-27 併成一顆，舊的掘骸寶石換成這顆，見 skills.yml 的 merged）。
 * 原作「大屍體叫好幾隻」沒做（一具一隻）；只有玩家本人放的才叫（圖騰放的只有骨刺）。
 */
final class Unearth {

    static final String ID = "bone_spikes";
    static final String CONSTRUCT = "unearth_construct";

    private final ExileCore plugin;

    Unearth(final ExileCore plugin) {
        this.plugin = plugin;
    }

    static boolean is(final SkillBuild build) {
        return build != null && ID.equals(build.definition().id());
    }

    /** 骨刺打完之後：範圍內的屍體變骨構體。 */
    void raise(final Origin origin, final SkillBuild build) {
        final Player player = origin.owner();
        if (player == null || plugin.skills() == null || plugin.combat() == null || plugin.combat().corpses() == null) {
            return;
        }
        final SkillDefinition construct = plugin.skills().config().skill(CONSTRUCT);
        if (construct == null || construct.summon() == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        // 2026-10-28 照 PoE2 Unearth 的品質：骨構體持續時間 +(0—5) 秒、移動速度增加 (0—50)%（掘骸的品質帶到骨構體上）
        final SkillBuild minions = SkillBuild.of(plugin.skills().config(), construct, build.gemLevel(), state.level(), false,
                build.supports(), state.weaponTags(), plugin.players().sheet(player), java.util.Set.of(), build.qualityModifiers());
        final Location feet = origin.feet();
        final long now = plugin.getServer().getCurrentTick();
        final Predicate<Location> inCone = SkillTargets.coneShape(origin, build.length(), build.angle());
        final List<Corpses.Corpse> found = new ArrayList<>();
        for (final Corpses.Corpse corpse : plugin.combat().corpses().near(feet.getWorld().getUID(), feet.getX(), feet.getZ(), build.length() + 1, now)) {
            if (inCone.test(new Location(feet.getWorld(), corpse.x(), corpse.y(), corpse.z()))) {
                found.add(corpse);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        found.sort(Comparator.comparingDouble(c -> (c.x() - feet.getX()) * (c.x() - feet.getX()) + (c.z() - feet.getZ()) * (c.z() - feet.getZ())));
        final int limit = Math.max(1, minions.minions());
        final List<Location> points = new ArrayList<>();
        for (final Corpses.Corpse corpse : found) {
            if (points.size() >= limit) {
                break;
            }
            if (!tw.exilecore.stats.TreeEffectKeys.corpseSaved(plugin.players().sheet(player), plugin.random())) { // 照 PoE2 天賦樹：x% 機率不毀掉屍體
                plugin.combat().corpses().remove(corpse);
            }
            points.add(new Location(feet.getWorld(), corpse.x(), corpse.y(), corpse.z()));
        }
        plugin.skills().minions().summonAt(player, minions, points);
    }
}
