package tw.exilecore.skill;

import tw.exilecore.ailment.AilmentType;
import tw.exilecore.core.config.YamlNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 光環的設定（skills.yml 裡 mechanic: aura 的 aura 區塊）。純資料，不依賴 Bukkit。
 * <p>
 * 光環放一次就開著（再放一次關掉），開著的時候保留一部分最大魔力（魔力池變小，回復速度不變），
 * 效果一直加在自己身上，範圍內的隊友與自己的召喚物也有（2026-10：只給自己的，召喚物也沒有）。捷（Herald）是「擊殺時觸發一個隱藏技能」的光環。
 *
 * @param reserve     保留最大魔力的百分比
 * @param radius      隊友在幾格內有效
 * @param allies      隊友也有效
 * @param effects     光環的效果（隨技能等級變）
 * @param onKill      擊殺時從屍體的位置放的隱藏技能代號（捷；null = 沒有）
 * @param onKillChance 擊殺時觸發的機率（百分比）
 * @param enemy       2026-10：範圍內的敵人受到的傷害增加（威壓那種）；null = 沒有
 * @param perMinion   2026-10（照 PoE2）：精魂召喚「每一隻」保留多少精魂（隨技能等級，1 級 → 40 級；2026-10-28 照 PoE2（poe2db）讀到 40 級；這次之前只讀到 20 級）；null = 不是照隻數算
 * @param freeMinions 前幾隻不用精魂（骸骨兵：2）
 * @param condition   2026-10（照 PoE2）：捷的觸發條件（要用攻擊打死、屍體身上要有哪種異常、要過量擊殺多少）；沒寫 = 殺死就觸發
 * @param lifeCost    2026-10（照 PoE2 的 Berserk）：開著的代價——每秒失去生命；null = 沒有
 * @param fury        2026-10（照 PoE2 的 Savage Fury）：攻擊累積獸性，滿了爆發成一段時間的狂暴；null = 沒有
 */
public record AuraSpec(double reserve, double radius, boolean allies, List<ScaledMod> effects, String onKill, double onKillChance,
                       Enemy enemy, Scaled perMinion, int freeMinions,
                       KillCondition condition, LifeCost lifeCost, Fury fury) {

    /**
     * 捷的觸發條件（2026-10，照 PoE2）。
     *
     * @param attackHit 最後一擊要是你自己的攻擊擊中（法術、持續傷害、召喚物、捷自己炸死的都不算）
     * @param ailment   死的時候身上要有這種異常（感電、凍結、流血、中毒…）；null = 不看
     * @param overkill  過量擊殺：最後一擊多打出來的傷害至少是敵人最大生命的這個百分比（一定要是你自己的攻擊擊中）；0 = 不看
     */
    public record KillCondition(boolean attackHit, AilmentType ailment, double overkill) {

        /** 殺死就觸發（沒寫條件）。 */
        public static final KillCondition ALWAYS = new KillCondition(false, null, 0);

        public boolean always() {
            return !attackHit && ailment == null && overkill <= 0;
        }

        /** 要不要看最後一擊（攻擊擊中、過量擊殺都要）。 */
        public boolean needsOwnAttack() {
            return attackHit || overkill > 0;
        }

        /**
         * 這次擊殺算不算。
         *
         * @param ownAttack       最後一擊是這個玩家自己的攻擊擊中，而且不是捷的效果打的
         * @param ailments        死的時候身上的異常
         * @param overkillPercent 最後一擊多打出來的傷害是敵人最大生命的幾 %（不是自己的擊中 = 0）
         */
        public boolean met(final boolean ownAttack, final Set<AilmentType> ailments, final double overkillPercent) {
            if (needsOwnAttack() && !ownAttack) {
                return false;
            }
            if (overkill > 0 && overkillPercent + 1e-9 < overkill) {
                return false;
            }
            return ailment == null || ailments.contains(ailment);
        }

        /** 說明用：「用攻擊擊中殺死感電中的敵人」。 */
        public String describe() {
            if (always()) {
                return "殺死敵人";
            }
            final String target = ailment == null ? "敵人" : ailmentWord(ailment) + "的敵人";
            final String how = needsOwnAttack() ? "用攻擊擊中殺死" : "殺死";
            final String over = overkill > 0 ? String.format(Locale.ROOT, "，而且過量擊殺（多打出牠最大生命 %.0f%% 以上的傷害）", overkill) : "";
            return how + target + over;
        }

        private static String ailmentWord(final AilmentType type) {
            return switch (type) {
                case IGNITE -> "點燃中";
                case CHILL -> "冰緩中";
                case FREEZE -> "凍結中";
                case SHOCK -> "感電中";
                case BLEED -> "流血中";
                case POISON -> "中毒中";
                case STUN -> "暈眩中";
                case ELECTROCUTE -> "被電擊";
            };
        }

        static KillCondition parse(final YamlNode node) {
            AilmentType ailment = null;
            if (node.has("ailment")) {
                final String word = node.string("ailment", "").trim().toLowerCase(Locale.ROOT);
                ailment = switch (word) {
                    case "ignite", "ignited" -> AilmentType.IGNITE;
                    case "chill", "chilled" -> AilmentType.CHILL;
                    case "freeze", "frozen" -> AilmentType.FREEZE;
                    case "shock", "shocked" -> AilmentType.SHOCK;
                    case "bleed", "bleeding" -> AilmentType.BLEED;
                    case "poison", "poisoned" -> AilmentType.POISON;
                    case "stun", "stunned" -> AilmentType.STUN;
                    case "electrocute", "electrocuted" -> AilmentType.ELECTROCUTE;
                    default -> throw new IllegalArgumentException(node.path()
                            + ".ailment：要寫 ignite、chill、freeze、shock、bleed、poison、stun、electrocute 其中一個，卻是 " + word);
                };
            }
            final KillCondition condition = new KillCondition(node.bool("attack", false), ailment,
                    check(node.path(), "overkill", node.decimal("overkill", 0), 0, 1000));
            return condition.always() ? ALWAYS : condition;
        }
    }

    /**
     * 開著的代價（2026-10，照 PoE2 的 Berserk）：每秒失去生命（直接扣生命，不經過能量護盾與抗性；扣不死，最低留 1 點；營地不扣）。
     * 生命被扣到只剩 1 點時效果暫停，回到最大生命的 {@value #RESUME_PERCENT}% 以上再繼續。
     *
     * @param percent 每秒失去最大生命的百分比
     * @param perRage 每 1 點怒氣每秒失去最大生命的百分比——只在怒氣「沒有在消退」的時候扣
     */
    public record LifeCost(double percent, double perRage) {

        /** 暫停之後生命回到最大生命的幾 % 才繼續。 */
        public static final double RESUME_PERCENT = 10;

        /** 每秒失去最大生命的幾 %。 */
        public double percentPerSecond(final int rage, final boolean rageDecaying) {
            return percent + (rageDecaying ? 0 : perRage * Math.max(0, rage));
        }

        static LifeCost parse(final YamlNode node) {
            final LifeCost cost = new LifeCost(check(node.path(), "percent", node.decimal("percent", 0), 0, 50),
                    check(node.path(), "per_rage", node.decimal("per_rage", 0), 0, 5));
            if (cost.percent <= 0 && cost.perRage <= 0) {
                throw new IllegalArgumentException(node.path() + "：percent、per_rage 至少要寫一個大於 0 的");
            }
            return cost;
        }
    }

    /**
     * 獸性（2026-10，照 PoE2 的 Savage Fury）：攻擊擊中敵人累積（一次技能只算 1 點，最多 max 點）；
     * 滿了就爆發成狂暴，持續「點數 × secondsPer」秒：得到 effects（隨技能等級）與疾速，每秒失去最大生命 lifeLoss%。
     * form：要在獸形（熊、狼人、飛龍）才會爆發，狂暴期間一直維持獸形；狂暴中不會再累積。
     *
     * @param max        最多幾點
     * @param secondsPer 每一點換幾秒的狂暴
     * @param lifeLoss   狂暴中每秒失去最大生命的百分比（扣不死，最低留 1 點——扣到 1 點狂暴就結束）
     * @param onslaught  狂暴中有沒有疾速
     * @param form       要不要獸形
     * @param effects    狂暴中的效果
     */
    public record Fury(int max, double secondsPer, double lifeLoss, boolean onslaught, boolean form, List<ScaledMod> effects) {

        public Fury {
            effects = List.copyOf(effects);
        }

        /** 這麼多點可以狂暴幾 tick。 */
        public int ticks(final int points) {
            return (int) Math.round(Math.max(0, Math.min(max, points)) * secondsPer * 20);
        }

        static Fury parse(final YamlNode node) {
            final Fury fury = new Fury((int) check(node.path(), "max", node.integer("max", 20), 1, 100),
                    check(node.path(), "seconds_per", node.decimal("seconds_per", 0.5), 0.05, 10),
                    check(node.path(), "life_loss", node.decimal("life_loss", 0), 0, 50),
                    node.bool("onslaught", false), node.bool("form", false), ScaledMod.parseList(node, "effects"));
            if (fury.effects.isEmpty() && !fury.onslaught) {
                throw new IllegalArgumentException(node.path() + "：狂暴至少要有 effects 或 onslaught");
            }
            return fury;
        }
    }

    /**
     * 對敵人的光環（2026-10）：範圍內的敵人受到的傷害增加 taken%（隨等級），在範圍內待滿 rampSeconds 秒提升到 rampTaken%。
     * tag：只有帶這個標籤的擊中才算（physical = 物理：攻擊與物理技能）；null = 全部。
     */
    public record Enemy(Scaled taken, Scaled rampTaken, double rampSeconds, String tag, Scaled weaknessEvery,
                        Scaled witheredEvery, List<ScaledMod> effects) {

        public Enemy {
            effects = effects == null ? List.of() : List.copyOf(effects);
        }

        public Enemy(final Scaled taken, final Scaled rampTaken, final double rampSeconds, final String tag) {
            this(taken, rampTaken, rampSeconds, tag, null);
        }

        public Enemy(final Scaled taken, final Scaled rampTaken, final double rampSeconds, final String tag, final Scaled weaknessEvery) {
            this(taken, rampTaken, rampSeconds, tag, weaknessEvery, null, List.of());
        }
    }

    public AuraSpec {
        effects = List.copyOf(effects);
        condition = condition == null ? KillCondition.ALWAYS : condition;
    }

    /** 沒有觸發條件、代價、獸性的光環（2026-10 以前的寫法）。 */
    public AuraSpec(final double reserve, final double radius, final boolean allies, final List<ScaledMod> effects, final String onKill,
                    final double onKillChance, final Enemy enemy, final Scaled perMinion, final int freeMinions) {
        this(reserve, radius, allies, effects, onKill, onKillChance, enemy, perMinion, freeMinions, null, null, null);
    }

    /** 精魂是照隻數算的召喚（2026-10）。 */
    public boolean perMinionReserve() {
        return perMinion != null;
    }

    /** 一般的光環（2026-10-26：焚心業火拿掉，焚燒光環 burn 跟著拿掉；這次之前這裡是「沒有焚燒的光環」）。 */
    public AuraSpec(final double reserve, final double radius, final boolean allies, final List<ScaledMod> effects, final String onKill,
                    final double onKillChance) {
        this(reserve, radius, allies, effects, onKill, onKillChance, null, null, 0);
    }

    /** 是不是「捷」：擊殺時觸發效果的光環。 */
    public boolean herald() {
        return onKill != null;
    }

    /** skills.yml 的 aura 區塊；沒有回傳 null。 */
    static AuraSpec parse(final YamlNode skill) {
        if (!skill.has("aura")) {
            return null;
        }
        final YamlNode node = skill.node("aura");
        final String where = node.path();
        final List<ScaledMod> effects = ScaledMod.parseList(node, "effects");
        String onKill = null;
        double chance = 100;
        KillCondition condition = KillCondition.ALWAYS;
        if (node.has("on_kill")) {
            final YamlNode sub = node.node("on_kill");
            onKill = sub.requireString("skill").trim().toLowerCase(Locale.ROOT);
            chance = check(sub.path(), "chance", sub.decimal("chance", 100), 1, 100);
            condition = KillCondition.parse(sub);
        }
        final LifeCost lifeCost = node.has("life_cost") ? LifeCost.parse(node.node("life_cost")) : null;
        final Fury fury = node.has("fury") ? Fury.parse(node.node("fury")) : null;
        if (node.has("burn")) { // 2026-10-26：焚燒光環（焚心業火）拿掉了
            throw new IllegalArgumentException(where + ".burn：焚燒光環已經拿掉了");
        }
        Enemy enemy = null;
        if (node.has("enemy")) {
            final YamlNode e = node.node("enemy");
            final Scaled taken = Scaled.parse(e.path() + ".taken", e.raw("taken"), 0);
            enemy = new Enemy(taken, e.has("ramp_taken") ? Scaled.parse(e.path() + ".ramp_taken", e.raw("ramp_taken"), 0) : taken,
                    check(e.path(), "ramp", e.decimal("ramp", 0), 0, 60), e.has("tag") ? e.string("tag", "").trim().toLowerCase(Locale.ROOT) : null,
                    // 2026-10-26：每幾秒給範圍內的敵人 1 層暴擊弱點（PoE2 的惡意 Malice）
                    e.has("crit_weakness_every") ? Scaled.parse(e.path() + ".crit_weakness_every", e.raw("crit_weakness_every"), 0) : null,
                    // 2026-10-26（照 PoE2 的凋息 Withering Presence）：每幾秒給範圍內的敵人 1 層凋零
                    e.has("withered_every") ? Scaled.parse(e.path() + ".withered_every", e.raw("withered_every"), 0) : null,
                    // 2026-10-26（照 PoE2 的鎮懾 Overwhelming Presence）：疊在範圍內敵人身上的修飾器（暈眩門檻、異常門檻降低）
                    ScaledMod.parseList(e, "effects"));
        }
        if (effects.isEmpty() && onKill == null && enemy == null && fury == null && !skill.has("summon") && !skill.has("meta")
                && !skill.has("level_modifiers")) { // 2026-10-26：雷霆共鳴、折光之鏡、鴉群密謀的效果寫在程式裡（GrantedAuras），逐級數值在 level_modifiers // 2026-10：夥伴（aura ＋ summon）、主要寶石（只保留精魂）可以沒有效果
            throw new IllegalArgumentException(where + "：光環至少要有 effects、enemy、on_kill 或 fury");
        }
        final Scaled perMinion = node.has("per_minion") ? Scaled.parse(where + ".per_minion", node.raw("per_minion"), 0) : null;
        if (perMinion != null && (!skill.has("summon") || perMinion.at(1) <= 0)) {
            throw new IllegalArgumentException(where + ".per_minion：只有精魂召喚（有 summon）能寫，而且要大於 0");
        }
        final int free = (int) check(where, "free", node.integer("free", 0), 0, 20);
        return new AuraSpec(check(where, "reserve", node.decimal("reserve", perMinion != null ? 0 : 35), 0, 100),
                check(where, "radius", node.decimal("radius", 8), 2, 24),
                node.bool("allies", true), effects, onKill, chance, enemy, perMinion, free, condition, lifeCost, fury);
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
