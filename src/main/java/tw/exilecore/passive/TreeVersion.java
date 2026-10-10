package tw.exilecore.passive;

/**
 * 天賦樹改版（tree.json 的 version）：角色記著自己的配點是照哪一版配的，跟現在不一樣就全部退點。純邏輯。
 */
public final class TreeVersion {

    private TreeVersion() {
    }

    /**
     * 這個角色的配點是舊版樹的嗎。
     *
     * @param current  現在的版本
     * @param recorded 角色記的版本（沒記過 = null：那時候還沒有版本這回事，是第 1 版配的）
     */
    public static boolean outdated(final int current, final String recorded) {
        if (recorded == null || recorded.isBlank()) {
            return current != 1;
        }
        return !recorded.trim().equals(String.valueOf(current));
    }
}
