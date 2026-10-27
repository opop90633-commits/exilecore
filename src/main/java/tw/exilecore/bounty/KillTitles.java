package tw.exilecore.bounty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 擊殺稱號該不該發（純邏輯，不依賴 Bukkit）。
 * <p>
 * 2026-10-26：「達成目標沒有發放」——以前只在 Boss 倒下那一刻試一次，發失敗（帳號的稱號清單超過 250 字記不下）就一直不發；
 * 現在打倒時、登入／換角色、打開稱號選單、設定重讀都用這裡算「次數已經到了、還沒擁有」的全部補發（≥ 目標就算，不是剛好等於）。
 */
public final class KillTitles {

    private KillTitles() {
    }

    /** 2026-10-27：管理員拿掉的稱號記在帳號進度（這次之前沒記，登入／開選單／重讀設定又補發回來）。 */
    public static final String REVOKED = "title.revoked.";

    /** 這個稱號是不是管理員拿掉的（補發的路線都要跳過；管理員再發一次才解除）。 */
    public static boolean revoked(final Map<String, String> progress, final String id) {
        return progress.containsKey(REVOKED + id);
    }

    /** 帳號進度裡這隻 Boss 打倒幾次（沒有、壞掉的算 0）。 */
    public static long kills(final Map<String, String> progress, final String boss) {
        final String value = progress.get(BountyService.BOSS_KILLS + boss);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 次數到了（≥ 目標）、帳號還沒擁有、也不是管理員拿掉的擊殺稱號（照設定檔的順序）。 */
    public static List<BountyConfig.BossTitle> due(final Collection<BountyConfig.BossTitle> defs, final Map<String, String> progress,
                                                   final Set<String> owned) {
        final List<BountyConfig.BossTitle> list = new ArrayList<>();
        for (final BountyConfig.BossTitle def : defs) {
            if (!owned.contains(def.title().id()) && !revoked(progress, def.title().id()) && kills(progress, def.boss()) >= def.kills()) {
                list.add(def);
            }
        }
        return list;
    }
}
