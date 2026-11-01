package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportSystemKeys;

/** 2026-11-01：照原作換成真正系統的輔助寶石的修飾器說明（{@link ModifierText} 呼叫；鍵見 {@link SupportSystemKeys}）。 */
final class SupportSystemText {

    private SupportSystemText() {
    }

    /** 這條的說明；不是這裡的鍵回傳 null。 */
    static String describe(final Modifier modifier) {
        return switch (modifier.stat()) {
            case SupportSystemKeys.CRATER_FISSURES -> "落地時從落點往外裂出 " + ModifierText.number(modifier.value()) + " 道裂隙";
            case SupportSystemKeys.HINDER_DOT -> "被輔助的技能造成的混沌持續傷害也會阻礙敵人（移動速度減少 30%，每一跳刷新 "
                    + ModifierText.number(SupportSystemKeys.HINDER_DOT_TICKS / 20.0) + " 秒）";
            default -> null;
        };
    }
}
