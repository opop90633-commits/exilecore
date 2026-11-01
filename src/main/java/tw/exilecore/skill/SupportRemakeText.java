package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportRemakeKeys;

/** 2026-11-01：照原作重做的輔助寶石的修飾器說明（{@link ModifierText} 呼叫；鍵見 {@link SupportRemakeKeys}）。 */
final class SupportRemakeText {

    private SupportRemakeText() {
    }

    private static String n(final double value) {
        return ModifierText.number(value);
    }

    /** 這條的說明；不是這裡的鍵回傳 null。value：FLAT 是原本的數字，MORE／INCREASED 是小數（percent 是換成 % 的絕對值）。 */
    static String describe(final Modifier modifier, final double value, final String percent) {
        final String stat = modifier.stat();
        if ("proc.use".equals(stat) && modifier.tags().contains(SupportRemakeKeys.TREMOR_PROC)) {
            return "被輔助的猛擊 " + n(SupportRemakeKeys.TREMOR_ONE) + "% 機率多造成 1 次餘震、" + n(SupportRemakeKeys.TREMOR_TWO)
                    + "% 機率多 2 次、" + n(SupportRemakeKeys.TREMOR_THREE) + "% 機率多 3 次（觸發「" + SkillConfig.nameOf(SupportRemakeKeys.TREMOR_PROC) + "」）";
        }
        return switch (stat) {
            case SupportRemakeKeys.FORKED_DAMAGE -> "分叉出來的投射物傷害" + (value < 0 ? "更少 " : "更多 ") + percent + "%";
            case SupportRemakeKeys.ACCURACY_NEARBY -> "對 " + n(SupportRemakeKeys.OVERREACH_METRES) + " 格內的目標命中"
                    + (value < 0 ? "更少 " : "更多 ") + percent + "%";
            case SupportRemakeKeys.FULL_BREAK_ON_HEAVY_STUN -> "造成重暈時讓敵人完全破甲";
            case SupportRemakeKeys.AFTERSHOCK_PER_QUARTER -> "有餘震機率的話，攻擊時間每 0.25 秒餘震機率增加 " + n(value) + "%";
            case SupportRemakeKeys.RUTHLESS -> "每第 " + SupportRemakeKeys.RUTHLESS_EVERY + " 次使用是重擊，那一次暈眩傷害更多 " + n(value) + "%";
            case SupportRemakeKeys.MINIONS_GIGANTIC -> "召喚物變成巨型（體型大 20%）";
            case tw.exilecore.stats.KeystoneKeys.ECHO_AREA -> "回響的那一次範圍" + (value < 0 ? "減少 " : "增加 ") + percent + "%";
            default -> null;
        };
    }
}
