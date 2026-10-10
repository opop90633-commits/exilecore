package tw.exilecore.charge;

import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一種球（data/charges.yml）：什麼時候有機會獲得、最多幾顆、撐多久、每一顆給什麼屬性。
 * <pre>
 * power:
 *   name: 銳星
 *   symbol: 暴            # 動作列上的代表字（沒寫用名字第一個字）
 *   color: "#ff8fe8"
 *   trigger: crit         # crit = 你暴擊時、kill = 你擊殺時、hit = 你被敵人打中時
 *   max: 3                # 基礎最多幾顆（詞綴可以加：charge.max.power）
 *   duration: 10          # 每顆撐幾秒
 *   per_charge:           # 每一顆給的屬性（引擎乘上顆數）
 *     - { stat: crit.chance, type: INCREASED, value: 40 }
 * </pre>
 *
 * @param id              代號（屬性鍵 charge.max.代號、charge.chance.代號 用它）
 * @param trigger         觸發時機
 * @param baseMax         基礎最大顆數
 * @param durationSeconds 每顆的持續時間（秒）
 * @param perCharge       每一顆給的修飾器範本（數值是一顆的量）
 */
public record ChargeType(String id, String name, String symbol, String color, Trigger trigger, int baseMax, double durationSeconds,
                         List<AffixMod> perCharge) {

    /** 什麼時候擲骰獲得一顆。 */
    public enum Trigger {
        /** 玩家自己的攻擊或法術暴擊時。 */
        CRIT,
        /** 玩家擊殺怪物時。 */
        KILL,
        /** 玩家被敵人的攻擊打中時（沒閃掉、沒擋掉；持續傷害不算）。 */
        HIT;

        static Trigger parse(final String text, final String where) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + "：trigger 只能是 crit、kill、hit，卻是「" + text + "」");
            }
        }
    }

    public ChargeType {
        perCharge = List.copyOf(perCharge);
    }

    public static ChargeType parse(final String id, final YamlNode node) {
        final String name = node.requireString("name");
        final String symbol = node.string("symbol", name.substring(0, 1));
        final String color = node.string("color", "#ffffff");
        final Trigger trigger = Trigger.parse(node.requireString("trigger"), node.path() + ".trigger");
        final int max = node.integer("max", 3);
        if (max < 1) {
            throw new IllegalArgumentException(node.path() + ".max：至少 1，卻是 " + max);
        }
        final double duration = node.decimal("duration", 10);
        if (duration <= 0) {
            throw new IllegalArgumentException(node.path() + ".duration：要大於 0 秒，卻是 " + duration);
        }
        final List<AffixMod> perCharge = new ArrayList<>();
        for (final YamlNode modNode : node.list("per_charge")) {
            final AffixMod mod = AffixMod.parse(modNode);
            if (mod.variable() != null) {
                throw new IllegalArgumentException(modNode.path() + "：每顆球的數值直接寫數字（不能用 {變數}）");
            }
            final String problem = StatLines.problem(mod.toModifier(Map.of(), id));
            if (problem != null) {
                throw new IllegalArgumentException(modNode.path() + "：" + problem);
            }
            perCharge.add(mod);
        }
        if (perCharge.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：per_charge 至少要有一條（這種球要給什麼？）");
        }
        return new ChargeType(id, name, symbol, color, trigger, max, duration, perCharge);
    }

    /** 每一顆給的修飾器（一顆的量）。 */
    public List<Modifier> perChargeModifiers() {
        final List<Modifier> list = new ArrayList<>();
        for (final AffixMod mod : perCharge) {
            list.add(mod.toModifier(Map.of(), name));
        }
        return list;
    }

    /** {@code count} 顆給屬性表的修飾器：每一條的數值乘上顆數（增加、更多、固定值都是線性疊）。 */
    public List<Modifier> modifiers(final int count) {
        final List<Modifier> list = new ArrayList<>();
        if (count <= 0) {
            return list;
        }
        for (final Modifier one : perChargeModifiers()) {
            list.add(new Modifier(one.stat(), one.type(), one.value() * count, one.tags(), name + " ×" + count));
        }
        return list;
    }

    /** 這張屬性表這種球最多幾顆：基礎（max）＋ {@code charge.max.代號}（不會小於 0）。 */
    public int maxFor(final StatSheet sheet) {
        return Math.max(0, (int) Math.floor(baseMax + sheet.flat(StatKeys.chargeMax(id), Set.of())));
    }

    /** 這張屬性表這種球每顆撐幾 tick：基礎秒數 × 20 ×（1 + 增加球的持續時間），最短是基礎的 10%。 */
    public long durationTicksFor(final StatSheet sheet) {
        return Math.round(durationSeconds * 20 * Math.max(0.1, 1 + sheet.increased(StatKeys.CHARGE_DURATION, Set.of())));
    }

    /** 這張屬性表每次觸發獲得一顆的機率（0–1）：{@code charge.chance.代號} 是百分比點數。 */
    public double chanceFor(final StatSheet sheet) {
        return Math.max(0, Math.min(1, sheet.flat(StatKeys.chargeChance(id), Set.of()) / 100.0));
    }

    /** 一顆的效果說明（玩家看的）：「增加 40% 暴擊率」。 */
    public List<String> describe() {
        return StatLines.describeAll(perChargeModifiers());
    }

    /** 觸發時機的說明。 */
    public String triggerText() {
        return switch (trigger) {
            case CRIT -> "暴擊時";
            case KILL -> "擊殺時";
            case HIT -> "被敵人擊中時";
        };
    }
}
