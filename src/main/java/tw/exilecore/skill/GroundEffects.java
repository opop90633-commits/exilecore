package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.List;

/**
 * 2026-10-26 照 PoE2：不造成傷害的地面效果與觸發效果。地面效果（mechanic: zone）的技能修飾器寫了 {@value #FLAG}，
 * 每一波就不打傷害，改成直接給範圍內的敵人：
 * <ul>
 *   <li>{@value #CHILL}：冰緩（%，冰緩地面：Frost Nexus、Spreading Frost）；</li>
 *   <li>{@value #SHOCK}：感電（受到的傷害增加 %，感電地面：Storm Lance 的帶電地）；</li>
 *   <li>{@value #DAZE}：眩暈幾秒（Break Posture）；</li>
 *   <li>{@value #WITHERED}：凋零幾層（Withering Touch）；</li>
 *   <li>{@value #SECONDS}：上面這些持續幾秒（沒寫 = 這一灘的間隔再多 0.25 秒：站在裡面就一直有）。</li>
 * </ul>
 * 原作這些都不擲機率、不算擊中（不會觸發擊中效果、不吃傷害加成）。
 */
final class GroundEffects {

    static final String FLAG = "ground.effect";
    static final String CHILL = "ground.chill";
    static final String SHOCK = "ground.shock";
    static final String DAZE = "ground.daze";
    static final String WITHERED = "ground.withered";
    static final String SECONDS = "ground.seconds";

    private GroundEffects() {
    }

    /** 這個技能（一灘地面）是不是只給效果、不打傷害。 */
    static boolean is(final SkillProfile profile) {
        return profile != null && sum(profile, FLAG) > 0;
    }

    static double sum(final SkillProfile profile, final String stat) {
        double total = 0;
        for (final Modifier modifier : profile.local()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.value();
            }
        }
        return total;
    }

    /** 給一個敵人這一波的效果。intervalTicks：這一灘多久一波（效果至少撐到下一波）。 */
    static void apply(final ExileCore plugin, final Player owner, final LivingEntity target, final SkillProfile profile, final long intervalTicks) {
        if (target instanceof Player) {
            return; // PvP 的地面效果照舊不處理（原本也只對怪物）
        }
        final double seconds = sum(profile, SECONDS);
        final long ticks = seconds > 0 ? Math.round(seconds * 20) : intervalTicks + 5;
        final double chill = sum(profile, CHILL) / 100.0;
        final double shock = sum(profile, SHOCK) / 100.0;
        if ((chill > 0 || shock > 0) && plugin.ailments() != null) {
            plugin.ailments().groundStatusOnMonster(target, chill, shock, ticks);
        }
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final double daze = sum(profile, DAZE);
        if (daze > 0) {
            // 跟擊中造成的眩暈一樣（HitEffects，PoE2 Dazed：受到的暈眩累積更多 50%），只是秒數照這裡寫的
            // 2026-10-27 照 PoE2；這次之前還是舊的「更少 15% 傷害、暈眩門檻減少 30%」（擊中的 10-26 已經改了，這裡漏改）
            plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("daze", owner.getUniqueId(), now + Math.round(daze * 20), 0,
                    false, null, List.of(Modifier.more(tw.exilecore.ailment.AilmentKeys.STUN_BUILDUP_TAKEN, HitEffects.DAZE_STUN_BUILDUP, "眩暈")), 0));
        }
        final int withered = (int) Math.round(sum(profile, WITHERED));
        if (withered > 0) {
            HitEffects.addWithered(plugin.combat().debuffs(), target.getUniqueId(), owner.getUniqueId(), now, withered,
                    seconds > 0 ? ticks : HitEffects.WITHERED_TICKS, owner == null ? 1.0 // 2026-10-26：凋零效果
                            : Math.max(0, 1 + plugin.players().sheet(owner).increased(tw.exilecore.stats.TreeEffectKeys.WITHERED_EFFECT, java.util.Set.of())));
        }
    }
}
