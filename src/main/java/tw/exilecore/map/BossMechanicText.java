package tw.exilecore.map;

import tw.exilecore.item.AffixMod;
import tw.exilecore.skill.ModifierText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Boss 機制的說明文字（2026-10，終局與試煉 Boss 的特殊機制要在畫面中間與聊天室說清楚）：純邏輯，回傳 MiniMessage 字串，不依賴 Bukkit。
 * <ul>
 *   <li>開打：聊天室一段機制總覽（幾 % 血換到哪個階段、護盾、狂暴、場地危害）。</li>
 *   <li>換階段：畫面中間大字「第 N 階段・名稱」，副標題是最要緊的一件事（護盾優先），聊天室列出這個階段的全部變化。</li>
 *   <li>護盾破了、來不及打破 Boss 回血、狂暴：畫面中間大字＋副標題（聊天室原本就有）。</li>
 * </ul>
 */
final class BossMechanicText {

    private BossMechanicText() {
    }

    /** 換階段的大字。 */
    static String phaseTitle(final int number, final MapConfig.Phase phase) {
        return "<red><bold>第 " + number + " 階段・" + esc(phase.name());
    }

    /**
     * 這個階段要注意的事，一件一句（護盾放第一）。
     *
     * @param newSkills 這個階段才開始用的招式名稱
     */
    static List<String> phaseNotes(final MapConfig.Phase phase, final List<String> newSkills) {
        final List<String> notes = new ArrayList<>();
        final MapConfig.Shield shield = phase.shield();
        if (shield != null) {
            notes.add(String.format(Locale.ROOT, "⛨ 護盾：%d 秒內打掉 %d 顆「%s」", Math.round(shield.duration()), shield.count(), shield.name()));
        }
        if (phase.summonType() != null && phase.summonCount() > 0) {
            notes.add("叫出 " + phase.summonCount() + " 隻小怪");
        }
        if (!newSkills.isEmpty()) {
            notes.add("新招式：" + String.join("、", newSkills));
        }
        final List<String> stats = new ArrayList<>();
        for (final AffixMod stat : phase.stats()) {
            stats.add(ModifierText.describe(stat.toModifier(Map.of(), "階段")));
        }
        if (phase.speed() > 0) {
            stats.add("動作變快");
        }
        if (!stats.isEmpty()) {
            notes.add("Boss " + String.join("、", stats));
        }
        if (notes.isEmpty()) {
            notes.add("Boss 變得更強");
        }
        return notes;
    }

    /** 換階段的副標題：最要緊的那一件。 */
    static String phaseSubtitle(final List<String> notes) {
        return "<white>" + esc(notes.getFirst());
    }

    /** 換階段的聊天室說明：全部。 */
    static String phaseChat(final int number, final MapConfig.Phase phase, final List<String> notes) {
        return "<red>▶ 第 " + number + " 階段「" + esc(phase.name()) + "」<gray>：" + esc(String.join("；", notes));
    }

    /**
     * 開打時的機制總覽（聊天室一行）；沒有階段、狂暴、場地危害就是 null。
     *
     * @param hazardNames 場地危害的招式名稱
     */
    static String overview(final MapConfig.Boss boss, final List<String> hazardNames) {
        final List<String> parts = new ArrayList<>();
        final List<MapConfig.Phase> phases = boss.phases();
        for (int i = 0; i < phases.size(); i++) {
            final MapConfig.Phase phase = phases.get(i);
            parts.add(String.format(Locale.ROOT, "血量 %d%% 進第 %d 階段「%s」%s", Math.round(phase.at() * 100), i + 1, phase.name(),
                    phase.shield() != null ? "（⛨ 護盾）" : ""));
        }
        final MapConfig.Enrage enrage = boss.enrage();
        if (enrage != null) {
            final long seconds = Math.round(enrage.after());
            parts.add(String.format(Locale.ROOT, "開打 %d:%02d 後狂暴%s", seconds / 60, seconds % 60,
                    enrage.damage() > 0 ? "（傷害更多 " + Math.round(enrage.damage()) + "%）" : ""));
        }
        if (!hazardNames.isEmpty()) {
            parts.add("場地危害：" + String.join("、", hazardNames));
        }
        return parts.isEmpty() ? null : "<#f5b942>【機制】<gray>" + esc(String.join("；", parts));
    }

    /** 護盾張開（從換階段以外的地方）：大字與副標題。 */
    static String[] shieldUp(final MapConfig.Shield shield, final int crystals) {
        return new String[] {"<#ffd27f><bold>⛨ 護盾",
                String.format(Locale.ROOT, "<white>%d 秒內打掉 %d 顆「%s」", Math.round(shield.duration()), crystals, esc(shield.name()))};
    }

    /** 護盾破了。 */
    static String[] shieldBroken() {
        return new String[] {"<green><bold>⛨ 護盾破了", "<white>Boss 又打得動了"};
    }

    /** 沒有及時打破護盾：Boss 回血。 */
    static String[] shieldFailed(final MapConfig.Shield shield) {
        return new String[] {"<red><bold>Boss 回血了", "<white>沒有及時打掉結晶：回復 " + Math.round(shield.heal()) + "% 生命"};
    }

    /** 狂暴。 */
    static String[] enraged(final MapConfig.Enrage enrage) {
        return new String[] {"<dark_red><bold>☠ 狂暴",
                "<white>" + (enrage.damage() > 0 ? "傷害更多 " + Math.round(enrage.damage()) + "%、" : "") + (enrage.speed() > 0 ? "動作更快、" : "")
                        + "快點打倒牠"};
    }

    /** 跳脫 MiniMessage 的標記（資料檔的名字裡可能有 &lt;）。 */
    private static String esc(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
