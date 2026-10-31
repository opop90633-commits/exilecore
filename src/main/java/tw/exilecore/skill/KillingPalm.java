package tw.exilecore.skill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-10-28 照 PoE2 0.5 Killing Palm（斷魂掌；RePoE KillingPalmPlayer、poe2db）；這次之前只是原地打一下、撲殺時給銳星：
 * <ul>
 *   <li>衝向一個敵人打一掌：目標在 {@link #DASH_RANGE} 格內、打不到就先衝過去（原作 base_melee_dash_range 65 = 6.5 公尺）。</li>
 *   <li>打中的目標被撲殺 → 往範圍內其他可以撲殺的敵人再衝過去打一掌（隨機挑一隻，原作 0.4.0c 起是隨機不是最近），
 *       那一掌又撲殺就繼續，直到附近沒有可以撲殺的敵人。每一掌傷害一樣（原作只有一組數值）。
 *       每一掌都是一次攻擊：隔這個技能的攻擊時間（吃攻擊速度，{@link #chainStepTicks}）；連衝期間不能做別的事。
 *       2026-10-28 照 PoE2 拿掉上限（這次之前隔 0.2 秒、最多 40 掌）：每一掌都要撲殺一隻還沒打過的敵人（死掉），
 *       同一隻不會打兩次，所以最多就是附近的敵人數，不會無限連。</li>
 *   <li>這一掌打死的每個敵人給銳星：一般、魔法 1 顆，稀有 2 顆，傳奇 3 顆（charge.event.kill.power，ChargeService 照稀有度多給）。</li>
 *   <li>技能面板上有斷魂掌時，身邊可以撲殺的敵人會發光（外框；只有你自己看得到，要裝 PacketEvents，見 {@link tw.exilecore.hook.CullGlow}；
 *       沒裝的話照舊頭上冒紅點）。OriginalSkills 每 {@link #HIGHLIGHT_TICKS} tick 更新一次。</li>
 *   <li>品質：撲殺時 {@link #RECOVERY_SECONDS} 秒內回復最大魔力（進階奇術：最大生命），照 PoE2 慢慢回（這次之前一次回）。</li>
 *   <li>原作 Killing Palm 沒有 Totemable、Triggerable（poe2db 類型：Attack, Melee, Area, Physical, MeleeSingleTarget, GeneratesCharges, Palm），
 *       不能交給圖騰、也不能被觸發；我們這裡也放不進圖騰（英靈圖騰只用錘子技能、星咒圖騰只放法術）、觸發只放隱藏技能。
 *       複擊（裂虛回響）的那一下算玩家自己再打一次：照樣衝過去、撲殺就連衝（SkillService.repeatStrike → {@code palmAgain}）。</li>
 * </ul>
 * 撲殺是 CombatListener 判的（門檻照稀有度），撲殺到的那一下記在這裡（{@link #noteCull}），施放的地方同一個 tick 查。
 */
public final class KillingPalm {

    static final String ID = "soul_sever_palm";
    /** 衝刺的距離（格）：原作 base_melee_dash_range 65（0.1 公尺一單位）。找目標、連擊找下一隻、標記都用這個距離。 */
    static final double DASH_RANGE = tw.exilecore.core.Units.metres(6.5); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 品質的回復分幾秒回完（原作「在 4 秒內恢復」）。 */
    public static final double RECOVERY_SECONDS = 4;
    /** 品質的回復每幾 tick 回一次。 */
    public static final int RECOVERY_PERIOD_TICKS = 5;
    /** 品質的回復分幾次回（4 秒 ÷ 0.25 秒 = 16 次）。 */
    public static final int RECOVERY_PULSES = (int) Math.round(RECOVERY_SECONDS * 20 / RECOVERY_PERIOD_TICKS);
    /** 標記多久畫一次（tick）。 */
    static final int HIGHLIGHT_TICKS = 5;

    /** 玩家 → {被撲殺的敵人, tick}（最近一次撲殺）。 */
    private static final Map<UUID, Cull> LAST_CULL = new HashMap<>();

    private record Cull(UUID target, long tick) {
    }

    private KillingPalm() {
    }

    static boolean is(final SkillBuild build) {
        return build != null && ID.equals(build.definition().id());
    }

    /**
     * 連衝下一掌隔幾 tick：每一掌是一次攻擊，照這個技能的攻擊時間（{@link SkillBuild#intervalTicks}，已經吃攻擊速度），最少 1 tick。
     * 2026-10-28 照 PoE2；這次之前固定 4 tick（0.2 秒）。
     */
    static long chainStepTicks(final double attackIntervalTicks) {
        if (!(attackIntervalTicks > 0) || Double.isInfinite(attackIntervalTicks)) {
            return 1;
        }
        return Math.max(1, Math.round(attackIntervalTicks));
    }

    /** 品質的回復每一次回多少（總量平均分成 {@link #RECOVERY_PULSES} 次）。 */
    public static double perPulse(final double total) {
        return total > 0 ? total / RECOVERY_PULSES : 0;
    }

    /** CombatListener：玩家撲殺了一隻敵人（任何技能都記；斷魂掌只查自己剛打的那一下）。 */
    public static void noteCull(final UUID attacker, final UUID target, final long tick) {
        if (attacker == null || target == null) {
            return;
        }
        if (LAST_CULL.size() > 512) {
            LAST_CULL.values().removeIf(cull -> cull.tick() < tick - 20);
        }
        LAST_CULL.put(attacker, new Cull(target, tick));
    }

    /** 這個 tick 這個玩家是不是撲殺了這隻敵人。 */
    static boolean culled(final UUID attacker, final UUID target, final long tick) {
        final Cull cull = attacker == null ? null : LAST_CULL.get(attacker);
        return cull != null && cull.tick() == tick && cull.target().equals(target);
    }

    /** 現在的生命是不是已經在撲殺門檻以下（打中就會被撲殺）：門檻 0 的（不是怪物）不算。 */
    static boolean cullable(final double health, final double maxHealth, final double threshold) {
        return threshold > 0 && maxHealth > 0 && health > 0 && health <= maxHealth * threshold;
    }

    /** 這一掌打死一隻這種稀有度的敵人給幾顆銳星（說明、測試用；實際由 ChargeService 的擊殺事件給）。 */
    static int powerCharges(final tw.exilecore.monster.MonsterRarity rarity) {
        if (rarity == null) {
            return 1;
        }
        return switch (rarity) {
            case NORMAL, MAGIC -> 1;
            case RARE -> 2;
            case UNIQUE -> 3;
        };
    }

    /** 連擊的下一隻：隨機排好順序（呼叫的人照順序找第一個衝得過去的）。 */
    static <T> void shuffle(final List<T> candidates, final RandomGenerator random) {
        for (int i = candidates.size() - 1; i > 0; i--) {
            final int j = random.nextInt(i + 1);
            final T swap = candidates.get(i);
            candidates.set(i, candidates.get(j));
            candidates.set(j, swap);
        }
    }
}
