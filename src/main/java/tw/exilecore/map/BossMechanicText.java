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
        return phaseNotes(phase, PhaseMechanic.explicit(phase), newSkills); // 2026-11-02：照這個階段自己寫的（mechanic 或 shield）
    }

    /**
     * 2026-11-02 照站長：鎖血機制放第一（結晶、站位圈、倒數大招、元素弱點）；這次之前只有 shield 的結晶。
     *
     * @param mechanic 這個階段配到的鎖血機制（null = 不鎖）
     */
    static List<String> phaseNotes(final MapConfig.Phase phase, final PhaseMechanic.Spec mechanic, final List<String> newSkills) {
        final List<String> notes = new ArrayList<>();
        final String lock = mechanicNote(mechanic);
        if (lock != null) {
            notes.add(lock);
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

    /** 2026-11-02：鎖血機制的一句話（要做什麼）；不鎖 null。 */
    static String mechanicNote(final PhaseMechanic.Spec mechanic) {
        if (mechanic == null) {
            return null;
        }
        return switch (mechanic.kind()) {
            case CRYSTAL -> String.format(Locale.ROOT, "⛨ 護盾：%d 秒內打掉 %d 顆「%s」", Math.round(mechanic.crystal().duration()),
                    mechanic.crystal().count(), mechanic.crystal().name());
            case ZONES -> String.format(Locale.ROOT, "◎ 站位圈：%d 秒內站滿全部光圈（一人一個；站在圈裡會越來越脆弱）",
                    Math.round(mechanic.zones().duration()));
            case COUNTDOWN -> String.format(Locale.ROOT, "⚠ 倒數大招：%d 秒後「%s」，躲進綠色安全區", Math.round(mechanic.countdown().delay()),
                    mechanic.countdown().name());
            case ELEMENT -> String.format(Locale.ROOT, "✦ 元素弱點：只吃一種元素傷害（看血條），%d 秒內打掉一段血",
                    Math.round(mechanic.element().duration()));
            case NONE -> null;
        };
    }

    /**
     * 2026-11-02 照站長（C 組）：鎖血機制有多少時間（秒；模組側邊的機制清單倒數用）；不鎖、沒寫時間 0。
     */
    static double mechanicSeconds(final PhaseMechanic.Spec mechanic) {
        if (mechanic == null) {
            return 0;
        }
        return switch (mechanic.kind()) {
            case CRYSTAL -> mechanic.crystal() == null ? 0 : mechanic.crystal().duration();
            case ZONES -> mechanic.zones() == null ? 0 : mechanic.zones().duration();
            case COUNTDOWN -> mechanic.countdown() == null ? 0 : mechanic.countdown().delay();
            case ELEMENT -> mechanic.element() == null ? 0 : mechanic.element().duration();
            case NONE -> 0;
        };
    }

    /** 2026-11-02：開打總覽裡每個階段後面的標記。 */
    static String mechanicTag(final PhaseMechanic.Spec mechanic) {
        if (mechanic == null) {
            return "";
        }
        return switch (mechanic.kind()) {
            case CRYSTAL -> "（⛨ 護盾）";
            case ZONES -> "（◎ 站位圈）";
            case COUNTDOWN -> "（⚠ 倒數大招）";
            case ELEMENT -> "（✦ 元素弱點）";
            case NONE -> "";
        };
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
        final List<PhaseMechanic.Spec> mechanics = new ArrayList<>();
        for (final MapConfig.Phase phase : boss.phases()) {
            mechanics.add(PhaseMechanic.explicit(phase));
        }
        return overview(boss, mechanics, hazardNames);
    }

    /** 2026-11-02：每個階段標出配到的鎖血機制（mechanics 跟 phases 一樣長；這次之前只標有沒有護盾）。 */
    static String overview(final MapConfig.Boss boss, final List<PhaseMechanic.Spec> mechanics, final List<String> hazardNames) {
        final List<String> parts = new ArrayList<>();
        final List<MapConfig.Phase> phases = boss.phases();
        for (int i = 0; i < phases.size(); i++) {
            final MapConfig.Phase phase = phases.get(i);
            parts.add(String.format(Locale.ROOT, "血量 %d%% 進第 %d 階段「%s」%s", Math.round(phase.at() * 100), i + 1, phase.name(),
                    mechanicTag(i < mechanics.size() ? mechanics.get(i) : null)));
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

    /** 2026-11-02：站位圈、元素弱點沒完成：Boss 回血。 */
    static String[] lockFailed(final PhaseMechanic.Spec mechanic) {
        final String why = switch (mechanic.kind()) {
            case ZONES -> "光圈沒有全部充滿";
            case ELEMENT -> "沒有及時打掉弱點那一段血";
            default -> "沒有及時完成";
        };
        return new String[] {"<red><bold>Boss 回血了", "<white>" + why + "：回復 " + Math.round(mechanic.heal()) + "% 生命"};
    }

    /** 2026-11-02：倒數大招放完，Boss 破盾、虛弱。 */
    static String[] weakened(final PhaseMechanic.Countdown countdown) {
        if (countdown.weak() <= 0 || countdown.weakTaken() <= 0) {
            return new String[] {"<gold><bold>💥 Boss 破盾", "<white>撐過去了，繼續打！"}; // 2026-11-02 照站長：不再虛弱
        }
        return new String[] {"<gold><bold>💥 Boss 虛弱了", String.format(Locale.ROOT, "<white>%s 秒內受到的傷害更多 %d%%，快打！",
                countdown.weak() == Math.rint(countdown.weak()) ? String.valueOf((long) countdown.weak()) : String.valueOf(countdown.weak()),
                Math.round(countdown.weakTaken() * 100))};
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
