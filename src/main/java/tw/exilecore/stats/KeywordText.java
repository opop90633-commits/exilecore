package tw.exilecore.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-27 照站長：關鍵字詞彙表。昇華天賦、天賦樹的說明裡只寫了關鍵字（「撲殺」「毀滅打擊」…）的，
 * 在說明下面自動補一行「關鍵字：說明」，每個關鍵字只補一次；說明裡已經寫了「關鍵字：」的不再補（不重複）。
 * 數值照本專案實際的效果（CombatListener、AscendancyEffects、TreeMechanics…）。純資料。
 */
public final class KeywordText {

    /** 關鍵字 → 說明（照這個順序補）。 */
    public static final Map<String, String> TERMS = new LinkedHashMap<>();

    static {
        // CombatListener.cullThreshold
        TERMS.put("撲殺", "擊中後敵人剩下的生命低於門檻就直接倒下：普通 35%、魔法 20%、稀有 10%、傳奇與 Boss 5%");
        TERMS.put("毀滅打擊", "擊中滿血的敵人時，先扣掉牠 5～30% 的生命再算這一下的傷害（Boss 不算）");
        TERMS.put("粉碎打擊", "蓄勢待暈的敵人被你擊中，直接重度暈眩");
        TERMS.put("必然暴擊", "沒擲中暴擊就重擲到暴擊為止，每重擲一次暴擊傷害加成更少 30%");
        TERMS.put("紛亂", "混沌傷害每 2 秒輪流也算進凍結累積、點燃或感電機率");
        TERMS.put("時間之沙", "技能速度增加 60% 在 10 秒內降到 1%，再升回 60%；範圍效果反過來從 1% 升到 60%");
        // AscendancyEffects：每層 1% 移動速度、2% 攻擊與施法速度、10% 閃避，最多 10 層
        TERMS.put("順風", "每層增加 1% 移動速度、2% 攻擊與施法速度、10% 閃避值（最多 10 層）");
        // TreeNewMechanicKeys：增加 15% 施法速度、魔力回復更多 20%
        TERMS.put("秘術增強", "增加 15% 施法速度，魔力再生速度更多 20%"); // 2026-10-27 照 PoE2 more Mana Regeneration Rate 用語；這次之前「魔力回復更多」
        // AilmentService.pinBuildup、ailments.yml pin
        TERMS.put("釘身", "投射物攻擊的物理傷害會累積釘身，累積滿了敵人被釘在原地 3 秒不能移動");
        TERMS.put("堤防光環", "圖騰周圍的你與隊友獲得加成，依圖騰種類不同：攻擊與施法速度、範圍、額外火焰傷害、榮耀產生、持續傷害，或對被禁錮的敵人更多傷害");
        // AscendancyEffects.reveal／openWeakness
        TERMS.put("揭露弱點", "在敵人的生命標出三段隨機的區段（共 45%），生命落在區段裡時敵人有開放弱點");
        TERMS.put("曝露", "敵人對應元素的抗性 -20%");
        // TreeMechanics：易爆
        TERMS.put("易爆", "最後一次獲得後 4 秒爆炸，每層對你造成 100 物理傷害；之後 10 秒獲得易爆之力（每層 1% 傷害額外獲得為混沌傷害）");
        // AilmentState：加劇的流血照移動中算（ailments.yml moving_multiplier 2）
        TERMS.put("加劇", "加劇的流血不管目標有沒有在動，都照移動中的兩倍傷害算");
        // AscendancyEffects：適應（最多 3 種；減少多少寫在天賦上：反應性生長每個更少 10%）
        TERMS.put("適應", "被擊中時適應那一下最高的元素傷害類型（最多同時 3 種），之後受到的該種元素傷害減少");
        // AscGranted：惡魔變身
        TERMS.put("惡魔烈焰", "惡魔變身期間每秒獲得 1 層（最多 10 層），每層法術傷害更多 3%，但每層每秒失去生命");
        // skills.yml 遁入裂痕
        TERMS.put("夏烏拉之炎", "遁入裂痕顯現的殘骸，走過去撿起：紫色獲得額外混沌傷害、藍色偷取魔力、紅色偷取生命");
        // KeystoneKeys.lucky
        TERMS.put("不幸", "擲兩次，取比較差的結果");
        TERMS.put("幸運", "擲兩次，取比較好的結果");
    }

    private KeywordText() {
    }

    /**
     * 說明裡出現的關鍵字各補一行「關鍵字：說明」。已經有一行寫了「關鍵字：」（自己解釋過了）、或說明本身已經寫在裡面的不補。
     *
     * @param shown 玩家已經看得到的行（效果、補充說明）
     */
    public static List<String> explain(final Collection<String> shown) {
        final List<String> out = new ArrayList<>();
        if (shown == null || shown.isEmpty()) {
            return out;
        }
        for (final Map.Entry<String, String> term : TERMS.entrySet()) {
            boolean mentioned = false;
            boolean explained = false;
            for (final String line : shown) {
                if (line == null) {
                    continue;
                }
                if (line.contains(term.getKey())) {
                    mentioned = true;
                }
                if (line.contains(term.getKey() + "：") || line.contains(term.getValue())) {
                    explained = true;
                }
            }
            if (mentioned && !explained) {
                out.add(term.getKey() + "：" + term.getValue());
            }
        }
        return out;
    }

    /** 去掉完全相同的行（留第一次出現的；空行照留）。 */
    public static List<String> distinct(final List<String> lines) {
        final List<String> out = new ArrayList<>();
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            if (line.isBlank() || seen.add(line.strip())) {
                out.add(line);
            }
        }
        return out;
    }

    /** 長的說明照字數折行（選單的說明一行太長會很寬）；width 是一行最多幾個字。 */
    public static List<String> wrap(final String text, final int width) {
        final List<String> out = new ArrayList<>();
        String rest = text == null ? "" : text;
        while (rest.length() > width) {
            int cut = -1;
            // 盡量在標點後面斷
            for (int i = width; i >= width / 2; i--) {
                final char c = rest.charAt(i - 1);
                if (c == '，' || c == '、' || c == '；' || c == '：' || c == '。' || c == '）' || c == ' ') {
                    cut = i;
                    break;
                }
            }
            if (cut < 0) {
                cut = width;
            }
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }
}
