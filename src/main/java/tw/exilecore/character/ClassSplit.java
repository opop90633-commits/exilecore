package tw.exilecore.character;

import java.util.Map;

/**
 * 混沌術士分成兩個職業（照 PoE2）：原本的混沌術士角色選一次要當元素法師（元素法術）還是留在混沌術士（混沌與血），選了不能改。
 * 純邏輯，不依賴 Bukkit；對話框與寫入在 {@link CharacterService#offerWitchSplit}。
 * <p>
 * 進度 {@link #PROGRESS}：選了什麼（sorceress／witch）。新建的混沌術士一開始就寫好（她知道自己選的是什麼）。
 */
public final class ClassSplit {

    /** 角色進度：混沌術士選過了（值是選的職業代號）。 */
    public static final String PROGRESS = "class.split";

    private ClassSplit() {
    }

    /** 這個角色還要選：混沌術士、還沒選過。 */
    public static boolean pending(final CharacterClass characterClass, final Map<String, String> progress) {
        return characterClass == CharacterClass.WITCH && progress != null && !progress.containsKey(PROGRESS);
    }

    /** 能選的兩個職業。 */
    public static boolean allowed(final CharacterClass target) {
        return target == CharacterClass.SORCERESS || target == CharacterClass.WITCH;
    }

    /**
     * 選完以後原本的昇華職業還能不能留：昇華職業屬於選的職業才留（星火術士、虛衣祭司跟著元素法術給了元素法師）。
     *
     * @param ascendancyClass 原本選的昇華職業是哪個職業的（沒選、或設定檔裡沒有了 = null）
     * @return true = 要退掉（清掉昇華職業與配點，昇華點還回去，可以重選）
     */
    public static boolean dropsAscendancy(final String ascendancyClass, final CharacterClass target) {
        return ascendancyClass != null && !ascendancyClass.equals(target.id());
    }
}
