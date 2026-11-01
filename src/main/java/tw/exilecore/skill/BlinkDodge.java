package tw.exilecore.skill;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 Blink（skillTypes DodgeReplacement：「While active, replaces your dodge roll」）：掠閃綁在按鍵格上（保留得到精魂）就開著，
 * 按翻滾鍵時改放掠閃——往翻滾的方向（按著的方向鍵，沒按就往面向）閃過去；冷卻中、放不出去（前方沒空間…）就照常翻滾。
 * 掠閃自己的按鍵不放（只用來綁上去保留精魂）。這次之前掠閃是獨立按鍵、往面向閃，翻滾不受影響。
 */
final class BlinkDodge {

    /** 掠閃的技能代號。 */
    static final String BLINK = "blink";

    /** 正在從翻滾鍵放掠閃的玩家（這段時間按鍵的檢查放行）。 */
    private static final Set<UUID> CASTING = new HashSet<>();

    private BlinkDodge() {
    }

    /** 這個技能的按鍵本身不能放（掠閃：要從翻滾鍵放）。 */
    static boolean blocksKey(final String skillId, final UUID player) {
        return BLINK.equals(skillId) && !CASTING.contains(player);
    }

    /** 純邏輯：綁鍵格裡第一個綁著 skillId 的格子；沒有回傳 -1。 */
    static int boundKey(final java.util.function.IntFunction<String> boundSkill, final String skillId) {
        for (int key = 0; key < SkillLoadout.BIND_SLOTS; key++) {
            if (skillId.equals(boundSkill.apply(key))) {
                return key;
            }
        }
        return -1;
    }

    /**
     * 翻滾鍵按下時（DodgeRoll 呼叫）：掠閃開著、冷卻好了就放，回傳 true（不翻滾）；沒開、冷卻中或放不出去回傳 false（照常翻滾）。
     *
     * @param direction 翻滾的方向（水平）
     */
    static boolean tryCast(final tw.exilecore.ExileCore plugin, final SkillService service, final Player player, final Vector direction) {
        if (!service.eligible(player) || service.inTown(player)) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        final int key = boundKey(slot -> {
            final SkillLoadout.Entry entry = state.skills().boundSkill(slot);
            return entry == null ? null : entry.skillId();
        }, BLINK);
        if (key < 0 || !service.auras().isActive(player.getUniqueId(), BLINK)) {
            return false;
        }
        final SkillRuntime runtime = service.runtime(player);
        final long now = plugin.getServer().getCurrentTick();
        if (runtime.cooldownLeft(BLINK, now) > 0) {
            return false;
        }
        CASTING.add(player.getUniqueId());
        service.executor().dashFacing(player.getUniqueId(), direction);
        try {
            service.trigger(player, key, null);
        } finally {
            CASTING.remove(player.getUniqueId());
            service.executor().dashFacing(player.getUniqueId(), null);
        }
        return runtime.cooldownLeft(BLINK, plugin.getServer().getCurrentTick()) > 0;
    }
}
