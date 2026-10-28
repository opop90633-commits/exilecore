package tw.exilecore.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

/**
 * 玩家的召喚物在傷害管道裡怎麼算（技能模組實作，{@link CombatModule#setMinions} 接上；技能模組沒啟用就沒有召喚物）：
 * 召喚物打怪物用主人的技能描述與牠自己的屬性表，傷害算主人的（掉落、經驗、傷害數字）；
 * 怪物打召喚物用召喚物的屬性表；玩家、召喚物之間互不傷害（競技場裡不同隊的除外）。
 */
public interface MinionHooks {

    /** 召喚物打人要用的東西。 */
    record Attack(Player owner, StatSheet sheet, SkillProfile profile) {
    }

    /** 這個實體是不是某個玩家的召喚物。 */
    boolean isMinion(Entity entity);

    /** 這隻召喚物打人：主人（在線）、牠的屬性表、技能描述；不是召喚物或主人不在回傳 null。 */
    Attack attack(LivingEntity minion);

    /** 這隻召喚物的主人（在線）；不是召喚物或主人不在回傳 null（競技場判斷敵我用）。 */
    Player owner(LivingEntity minion);

    /** 這隻召喚物的屬性表（被打時的防禦）；不是召喚物回傳 null。 */
    StatSheet sheet(LivingEntity minion);

    /** 我們的生命值對原版血量的比例（跟怪物一樣：生命超過 1024 時原版血量只當比例）。 */
    double healthScale(LivingEntity minion);

    /** 回復召喚物的生命（我們的單位；擊中回復生命輔助）。 */
    void heal(LivingEntity minion, double amount);

    /** 2026-10-26 照 PoE2：召喚物目前的能量護盾（我們的單位；沒有回傳 0）。擊中先扣護盾再扣生命。 */
    default double energyShield(final LivingEntity minion) {
        return 0;
    }

    /** 2026-10-26：召喚物被擊中了（esDamage = 護盾吸收的部分）：扣護盾、重新算回充的 4 秒，冰甲反擊 attacker。 */
    default void damaged(final LivingEntity minion, final double esDamage, final LivingEntity attacker) {
    }

    /** 2026-10-28：召喚物被擊中，這一下總共多少傷害（骸骨祭的護盾：第一次被打超過最大生命 25% 時護盾消失）。 */
    default void hitTaken(final LivingEntity minion, final double total) {
    }
}
