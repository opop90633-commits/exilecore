package tw.exilecore.balance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ec balance 的文字：聊天用的摘要（MiniMessage）、一個等級的明細、寫進檔案的完整報表（純文字）。純函式，不依賴 Bukkit。
 * 從資料來的文字（技能、輔助、詞綴、Boss 名稱）放進 MiniMessage 前都先跳脫。
 */
public final class BalanceReport {

    private BalanceReport() {
    }

    /** 聊天摘要：每 5 級一行，後面接區域 Boss。 */
    public static List<String> summary(final BalanceModel model, final List<BalanceModel.Row> rows, final List<BalanceModel.BossRow> bosses) {
        final List<String> lines = new ArrayList<>();
        lines.add("<#f5b942>平衡報表<gray>（中等裝備的參考角色 vs 同等級普通怪，單體）");
        lines.add("<dark_gray>等級｜怪物生命｜攻擊：換算秒數（目標）、平均幾下｜法術是攻擊的幾 %｜被打一下扣幾 % 生命（目標）");
        for (final BalanceModel.Row row : rows) {
            if (row.level() != 1 && row.level() % 5 != 0) {
                continue;
            }
            lines.add(String.format(Locale.ROOT,
                    "<white>Lv%2d<gray>｜%s｜%s%.2f 秒<gray>（%.2f）、%s 下｜%s%.0f%%<gray>｜%s%.1f%%<gray>（%.1f%%）",
                    row.level(), number(row.monsterLife()),
                    color(row.attackSeconds() / row.targetSeconds()), row.attackSeconds(), row.targetSeconds(), swings(row.attack().swingsToKill()),
                    color(model.config().spellVsAttack() / Math.max(0.01, row.spellRatio())), row.spellRatio() * 100,
                    color(row.hitPercent() / row.targetHitPercent()), row.hitPercent(), row.targetHitPercent()));
        }
        if (!bosses.isEmpty()) {
            lines.add("<dark_gray>Boss｜生命｜攻擊站著打幾秒（目標）｜法術幾秒");
            for (final BalanceModel.BossRow boss : bosses) {
                lines.add(String.format(Locale.ROOT, "<white>%s <gray>Lv%d｜%s｜%s%.0f 秒<gray>（%.0f）｜%.0f 秒",
                        escape(boss.spec().name()), boss.spec().level(), number(boss.life()),
                        color(boss.attackSeconds() / boss.targetSeconds()), boss.attackSeconds(), boss.targetSeconds(), boss.spellSeconds()));
            }
        }
        lines.add("<dark_gray>換算秒數 = 生命 ÷ 每秒傷害；平均幾下是擲骰模擬（一下打不死也要揮完整下）。");
        lines.add("<dark_gray>綠色 = 離目標 20% 以內，黃色 = 20–50%，紅色 = 差更多。/ec balance \\<等級> 看明細");
        return lines;
    }

    /** 一個等級的明細（聊天）。 */
    public static List<String> detail(final BalanceModel.Row row) {
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "<#f5b942>Lv%d 平衡明細", row.level()));
        lines.add(String.format(Locale.ROOT, "<gray>同等級普通怪：生命 <white>%s<gray>，打一下平均 <white>%.1f<gray>（扣參考角色 %.1f%%，目標 %.1f%%）",
                number(row.monsterLife()), row.monsterHit(), row.hitPercent(), row.targetHitPercent()));
        side(lines, "攻擊", row.attack(), row.monsterLife(), row.targetSeconds());
        side(lines, "法術", row.spell(), row.monsterLife(), row.spellTargetSeconds());
        lines.add(String.format(Locale.ROOT, "<gray>照目標：怪物生命應該是 <white>%s<gray>，打一下應該是 <white>%.1f",
                number(row.suggestedLife()), row.suggestedHit()));
        return lines;
    }

    private static void side(final List<String> lines, final String label, final BalanceModel.Side side,
                             final double monsterLife, final double target) {
        lines.add(String.format(Locale.ROOT, "<white>%s：%s Lv%d<gray>（輔助：%s）", label, escape(side.skill()),
                side.gemLevel(), side.supports().isEmpty() ? "無" : escape(String.join("、", side.supports()))));
        final double seconds = monsterLife / Math.max(1e-9, side.dps());
        lines.add(String.format(Locale.ROOT,
                "<gray>　每下 <white>%.1f<gray> × 每秒 <white>%.2f<gray> 次 × 命中 <white>%.0f%%<gray>，暴擊 %.1f%%（×%.2f）→ <white>%s/秒",
                side.perHit(), side.perSecond(), side.hitChance() * 100, side.critChance() * 100, side.critMultiplier(),
                number(side.dps())));
        lines.add(String.format(Locale.ROOT, "<gray>　換算 %s%.2f 秒<gray>（目標 %.2f）；實際平均 <white>%s<gray> 下打死，<white>%s<gray> 秒",
                color(seconds / target), seconds, target, swings(side.swingsToKill()), seconds(side.actualSeconds())));
        lines.add("<dark_gray>　裝備：" + (side.gear().isEmpty() ? "無" : escape(String.join("、", side.gear())))
                + String.format(Locale.ROOT, "；最大生命 %.0f", side.life()));
    }

    /** 完整報表（寫進檔案）：每一級的數字、區域 Boss，最後是可以直接貼進設定檔的建議值。 */
    public static List<String> full(final BalanceModel model, final List<BalanceModel.Row> rows, final List<BalanceModel.BossRow> bosses) {
        final List<String> lines = new ArrayList<>();
        lines.add("ExileCore 平衡報表（/ec balance 產生）");
        lines.add("參考角色與目標：data/balance.yml；怪物：data/monsters.yml；法術成長：data/skills.yml；Boss：data/zones.yml");
        lines.add("換算秒數 = 生命 ÷ 每秒傷害（打過頭的傷害也算）；幾下 = 擲骰模擬平均幾下打死；實際秒數 = 幾下 × 一下的時間");
        lines.add("");
        lines.add("等級  怪物生命  攻擊DPS  換算秒數  目標秒數  攻擊幾下  實際秒數  法術DPS  法術秒數  法術幾下  法術/攻擊  怪物一下  扣生命%  目標%  參考生命  建議生命  建議一下");
        for (final BalanceModel.Row row : rows) {
            lines.add(String.format(Locale.ROOT,
                    "%4d  %8.1f  %7.1f  %8.2f  %8.2f  %8s  %8s  %7.1f  %8.2f  %8s  %8.0f%%  %8.1f  %6.1f%%  %4.1f%%  %8.0f  %8.1f  %8.1f",
                    row.level(), row.monsterLife(), row.attack().dps(), row.attackSeconds(), row.targetSeconds(),
                    swings(row.attack().swingsToKill()), seconds(row.attack().actualSeconds()), row.spell().dps(), row.spellSeconds(),
                    swings(row.spell().swingsToKill()), row.spellRatio() * 100, row.monsterHit(), row.hitPercent(),
                    row.targetHitPercent(), row.attack().life(), row.suggestedLife(), row.suggestedHit()));
        }
        if (!bosses.isEmpty()) {
            lines.add("");
            lines.add("Boss（站著打、魔力無限；實際要閃技能，大約再久一半；目標秒數：回響 ×1.3、極境 ×2、世界 Boss ×3、統帥 ×1.5）");
            lines.add("區域        Boss          等級        生命  攻擊秒數  目標秒數  法術秒數  life倍率  建議倍率");
            for (final BalanceModel.BossRow boss : bosses) {
                lines.add(String.format(Locale.ROOT, "%-10s  %-12s  %4d  %10.0f  %8.1f  %8.1f  %8.1f  %8.2f  %8.2f",
                        boss.spec().zone(), boss.spec().name(), boss.spec().level(), boss.life(), boss.attackSeconds(),
                        boss.targetSeconds(), boss.spellSeconds(), boss.spec().fileValue(), boss.suggestedLifeMultiplier()));
            }
        }
        final BalanceModel.Suggestions suggestions = model.suggest(rows);
        lines.add("");
        lines.add("# 建議的 monsters.yml（照 balance.yml 的目標、對每一級做最小平方；最高等級以上照原本的比例延伸）");
        lines.add("life:");
        for (final Map.Entry<Integer, Double> entry : suggestions.life().entrySet()) {
            lines.add(String.format(Locale.ROOT, "  %d: %d", entry.getKey(), Math.round(entry.getValue())));
        }
        lines.add("damage:");
        for (final Map.Entry<Integer, double[]> entry : suggestions.damage().entrySet()) {
            lines.add(String.format(Locale.ROOT, "  %d: [%d, %d]", entry.getKey(), Math.max(1, Math.round(entry.getValue()[0])),
                    Math.max(1, Math.round(entry.getValue()[1]))));
        }
        lines.add("");
        lines.add("# 建議的 skills.yml（法術單體是攻擊的 balance.yml spell_vs_attack 倍）");
        final StringBuilder growth = new StringBuilder("spell_damage_growth: { ");
        boolean first = true;
        for (final Map.Entry<Integer, Double> entry : suggestions.spellGrowth().entrySet()) {
            if (!first) {
                growth.append(", ");
            }
            growth.append(entry.getKey()).append(": ").append(String.format(Locale.ROOT, "%.2f", entry.getValue()));
            first = false;
        }
        lines.add(growth.append(" }").toString());
        if (!bosses.isEmpty()) {
            lines.add("");
            lines.add("# 建議的 boss.life（照 balance.yml 的 boss_seconds；地圖 Boss 寫在 maps.yml）");
            for (final BalanceModel.BossRow boss : bosses) {
                final String zone = boss.spec().zone();
                final String where = zone.startsWith("map:") ? "maps.yml " + zone.substring(4)
                        : zone.startsWith("trial:") ? "ascendancy.yml 試煉 " + zone.substring(6)
                        : zone.startsWith("heist:") ? "heist.yml " + zone.substring(6)
                        : zone.startsWith("echo:") ? "bounties.yml 回響（weekly.life，照 " + zone.substring(5) + "）"
                        : zone.startsWith("uber:") ? "maps.yml 極境 " + zone.substring(5) + "（uber.life）"
                        : zone.startsWith("world:") ? "world_events.yml " + zone.substring(6)
                        : "zones.yml " + zone;
                lines.add(String.format(Locale.ROOT, "#   %s 的 boss.life: %.1f", where, boss.suggestedLifeMultiplier()));
            }
        }
        return lines;
    }

    /** 離目標的比例 → 顏色：±20% 內綠色，50% 內黃色，其他紅色。 */
    private static String color(final double ratio) {
        final double off = Math.abs(Math.log(Math.max(1e-9, ratio)));
        if (off <= Math.log(1.2)) {
            return "<green>";
        }
        return off <= Math.log(1.5) ? "<yellow>" : "<red>";
    }

    /** 平均幾下（打不死是 ∞）。 */
    private static String swings(final double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "∞";
    }

    private static String seconds(final double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "∞";
    }

    /** 放進 MiniMessage 前跳脫標籤字元（跟 ItemRenderer.escape 一樣，但不用載入 Paper 的類別）。 */
    private static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static String number(final double value) {
        return value >= 100 ? String.format(Locale.ROOT, "%,d", Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }
}
