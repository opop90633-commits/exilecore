package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

/**
 * 蓄力放開（2026-10，照 PoE2 的 Snipe、Perfect Strike、Flameblast）：按住按鍵蓄力，放開才出手。skills.yml 的 charge 區塊。
 * <ul>
 *   <li>完美時機（perfect_at &gt; 0）：蓄到「這一招攻擊／施法時間的 perfect_at%」那一刻會叮一聲，前後 {@link #WINDOW_BEFORE}～{@link #WINDOW_AFTER}
 *       tick 內放開是完美時機：更多 perfect_more% 傷害，並觸發這個技能寫的 proc.perfect（放開的時候）／proc.perfect_hit（打中的時候）</li>
 *   <li>疊層（stages &gt; 0）：每 stage_time 秒（吃施法／攻擊速度）加一層，最多 stages 層；每多一層更多 stage_more% 傷害、增加 stage_area% 範圍</li>
 * </ul>
 * 滑鼠鍵按住蓄力、放開出手；數字鍵按一下開始、再按一下放出。蓄太久（完美時機之後 {@link #OVERHOLD} tick、疊滿之後同樣）自動放出。
 *
 * @param perfectAt   完美時機在攻擊／施法時間的幾 %（0 = 沒有完美時機）
 * @param perfectMore 完美時機更多幾 % 傷害
 * @param stages      最多幾層（0 = 不疊層）
 * @param stageTime   每層幾秒（基礎；攻擊／施法速度越快越短）
 * @param stageMore   每多一層更多幾 % 傷害
 * @param stageArea   每多一層增加幾 % 範圍
 */
public record ChargeSpec(double perfectAt, double perfectMore, int stages, double stageTime, double stageMore, double stageArea) {

    /** 完美時機那一刻之前幾 tick 放開也算（網路延遲、按鍵的反應）。 */
    public static final int WINDOW_BEFORE = 2;
    /** 完美時機那一刻之後幾 tick 內放開算（PoE2 基礎 0.3 秒）。 */
    public static final int WINDOW_AFTER = 6;
    /** 過了完美時機（或疊滿）再多按住幾 tick 就自動放出。 */
    public static final int OVERHOLD = 40;

    public boolean perfect() {
        return perfectAt > 0;
    }

    /** 完美時機在第幾 tick（照這一招的攻擊／施法間隔）。 */
    public long perfectTick(final double intervalTicks) {
        return Math.max(6, Math.min(80, Math.round(intervalTicks * perfectAt / 100.0)));
    }

    /** 蓄了 held tick 是不是完美時機。 */
    public boolean isPerfect(final long held, final double intervalTicks) {
        if (!perfect()) {
            return false;
        }
        final long at = perfectTick(intervalTicks);
        return held >= at - WINDOW_BEFORE && held <= at + WINDOW_AFTER;
    }

    /** 每層幾 tick（照這一招的速度：速度越快越短）。 */
    public double stageTicks(final double speedFactor) {
        return Math.max(2, stageTime * 20 / Math.max(0.1, speedFactor));
    }

    /** 蓄了 held tick 有幾層（至少 1 層）。 */
    public int stagesAt(final long held, final double speedFactor) {
        if (stages <= 0) {
            return 1;
        }
        return (int) Math.max(1, Math.min(stages, 1 + Math.floor(held / stageTicks(speedFactor))));
    }

    /** skills.yml 的 charge 區塊；沒寫回傳 null。 */
    static ChargeSpec parse(final YamlNode skill) {
        if (!skill.has("charge")) {
            return null;
        }
        final YamlNode node = skill.node("charge");
        final double perfectAt = node.decimal("perfect_at", 0);
        final double perfectMore = node.decimal("perfect_more", 0);
        final int stages = node.integer("stages", 0);
        final double stageTime = node.decimal("stage_time", 0.25);
        final double stageMore = node.decimal("stage_more", 0);
        final double stageArea = node.decimal("stage_area", 0);
        if (perfectAt < 0 || perfectAt > 400 || stages < 0 || stages > 20 || stageTime <= 0 || stageTime > 3 || perfectMore < 0) {
            throw new IllegalArgumentException(node.path() + "：perfect_at 0–400、stages 0–20、stage_time 0–3 秒");
        }
        if (perfectAt <= 0 && stages <= 0) {
            throw new IllegalArgumentException(node.path() + "：要寫 perfect_at（完美時機）或 stages（疊層）");
        }
        return new ChargeSpec(perfectAt, perfectMore, stages, stageTime, stageMore, stageArea);
    }
}
