package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.List;
import java.util.Locale;

/**
 * 光環的設定（skills.yml 裡 mechanic: aura 的 aura 區塊）。純資料，不依賴 Bukkit。
 * <p>
 * 光環放一次就開著（再放一次關掉），開著的時候保留一部分最大魔力（魔力池變小，回復速度不變），
 * 效果一直加在自己身上，範圍內的隊友與自己的召喚物也有。捷（Herald）是「擊殺時觸發一個隱藏技能」的光環。
 *
 * @param reserve     保留最大魔力的百分比
 * @param radius      隊友在幾格內有效
 * @param allies      隊友也有效
 * @param effects     光環的效果（隨技能等級變）
 * @param onKill      擊殺時從屍體的位置放的隱藏技能代號（捷；null = 沒有）
 * @param onKillChance 擊殺時觸發的機率（百分比）
 * @param burn        焚燒光環（2026-10，焚心業火）：開著的時候每半秒用技能自己的 damage 打一下周圍 radius 內的敵人
 * @param selfBurn    焚燒光環：自己每秒受到最大生命（＋能量護盾）這個百分比的火焰傷害（火焰抗性減免；在營地不燒）
 * @param enemy       2026-10：範圍內的敵人受到的傷害增加（威壓那種）；null = 沒有
 * @param perMinion   2026-10（照 PoE2）：精魂召喚「每一隻」保留多少精魂（隨技能等級，1 級 → 20 級）；null = 不是照隻數算
 * @param freeMinions 前幾隻不用精魂（骸骨兵：2）
 */
public record AuraSpec(double reserve, double radius, boolean allies, List<ScaledMod> effects, String onKill, double onKillChance,
                       boolean burn, double selfBurn, Enemy enemy, Scaled perMinion, int freeMinions) {

    /**
     * 對敵人的光環（2026-10）：範圍內的敵人受到的傷害增加 taken%（隨等級），在範圍內待滿 rampSeconds 秒提升到 rampTaken%。
     * tag：只有帶這個標籤的擊中才算（physical = 物理：攻擊與物理技能）；null = 全部。
     */
    public record Enemy(Scaled taken, Scaled rampTaken, double rampSeconds, String tag) {
    }

    public AuraSpec {
        effects = List.copyOf(effects);
    }

    public AuraSpec(final double reserve, final double radius, final boolean allies, final List<ScaledMod> effects, final String onKill,
                    final double onKillChance, final boolean burn, final double selfBurn) {
        this(reserve, radius, allies, effects, onKill, onKillChance, burn, selfBurn, null, null, 0);
    }

    /** 精魂是照隻數算的召喚（2026-10）。 */
    public boolean perMinionReserve() {
        return perMinion != null;
    }

    /** 沒有焚燒的光環。 */
    public AuraSpec(final double reserve, final double radius, final boolean allies, final List<ScaledMod> effects, final String onKill,
                    final double onKillChance) {
        this(reserve, radius, allies, effects, onKill, onKillChance, false, 0);
    }

    /** 焚燒光環：每半秒打周圍的敵人（技能要寫 damage、damage_types）。 */
    public boolean burns() {
        return burn;
    }

    /** 常駐技能（2026-10）：焚燒的（焚心業火）不算光環——不保留魔力、不吃光環的加成，腳下也不畫光環的法陣。 */
    public boolean persistent() {
        return burn;
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
        if (node.has("on_kill")) {
            final YamlNode sub = node.node("on_kill");
            onKill = sub.requireString("skill").trim().toLowerCase(Locale.ROOT);
            chance = check(sub.path(), "chance", sub.decimal("chance", 100), 1, 100);
        }
        final boolean burn = node.has("burn");
        double selfBurn = 0;
        if (burn) {
            selfBurn = check(node.node("burn").path(), "self", node.node("burn").decimal("self", 0), 0, 100);
        }
        Enemy enemy = null;
        if (node.has("enemy")) {
            final YamlNode e = node.node("enemy");
            final Scaled taken = Scaled.parse(e.path() + ".taken", e.raw("taken"), 0);
            enemy = new Enemy(taken, e.has("ramp_taken") ? Scaled.parse(e.path() + ".ramp_taken", e.raw("ramp_taken"), 0) : taken,
                    check(e.path(), "ramp", e.decimal("ramp", 0), 0, 60), e.has("tag") ? e.string("tag", "").trim().toLowerCase(Locale.ROOT) : null);
        }
        if (effects.isEmpty() && onKill == null && !burn && enemy == null && !skill.has("summon")) { // 2026-10：夥伴（aura ＋ summon）可以沒有效果
            throw new IllegalArgumentException(where + "：光環至少要有 effects、enemy、on_kill 或 burn");
        }
        final Scaled perMinion = node.has("per_minion") ? Scaled.parse(where + ".per_minion", node.raw("per_minion"), 0) : null;
        if (perMinion != null && (!skill.has("summon") || perMinion.at(1) <= 0)) {
            throw new IllegalArgumentException(where + ".per_minion：只有精魂召喚（有 summon）能寫，而且要大於 0");
        }
        final int free = (int) check(where, "free", node.integer("free", 0), 0, 20);
        return new AuraSpec(burn ? 0 : check(where, "reserve", node.decimal("reserve", perMinion != null ? 0 : 35), 0, 100),
                check(where, "radius", node.decimal("radius", 8), 2, 24),
                node.bool("allies", !burn), effects, onKill, chance, burn, selfBurn, enemy, perMinion, free);
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
