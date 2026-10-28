package tw.exilecore.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-28 換職業（職業轉職卷、管理員的 /ec setclass）要清掉什麼。純邏輯，不依賴 Bukkit；真的寫入在
 * {@link CharacterService#changeClass}。
 * <p>
 * 規則（照 PoE2 的道理：職業決定天賦樹起點、起始屬性與能走的昇華職業）：
 * <ul>
 *   <li>天賦樹全部退回（起點換了，舊的配點接不上），點數一點不少；屬性點的選擇（attr.*）一起清掉。</li>
 *   <li>天賦組（月卡）存著的配點都是舊職業的起點：全部清掉，孔裡的珠寶還給玩家。</li>
 *   <li>昇華職業退掉、昇華天賦清掉；完成過的試煉（trial.*）留著，所以昇華點全部拿回來，照正常流程重選新職業的昇華職業。
 *       命匣裡的珠寶、符文子午線紋的符文（舊昇華天賦才有的）還給玩家。</li>
 *   <li>等級、經驗、裝備、技能、通貨、倉庫、任務都不動；新手裝備與起始技能不會再送。</li>
 * </ul>
 * 這次之前只有管理員的 /ec setclass 換得了，而且天賦組、命匣、符文子午線沒有清（換完會留著舊職業的東西）。
 */
public final class ClassChange {

    /** 天賦組的配點（TreeSetMenu：treeset.組.段）；treeset.active 是現在用哪一組，不清。 */
    static final String TREE_SET_PREFIX = "treeset.";
    static final String TREE_SET_ACTIVE = "treeset.active";
    /** 屬性點的選擇（天賦樹上的屬性節點選了力量、敏捷還是智慧）。 */
    static final String ATTRIBUTE_PREFIX = "attr.";
    /** 這幾個進度屬於昇華職業（Ascension.CHOSEN、Ascension.NODES、AscBuild.MERIDIANS；寫死字串免得純邏輯依賴昇華模組）。 */
    static final String ASC_CHOSEN = "asc";
    static final String ASC_NODES = "asc.nodes";
    static final String ASC_MERIDIANS = "asc.meridians";

    private ClassChange() {
    }

    /** 能換成哪些職業：除了現在這個以外的全部（照天賦樹上的順序）。 */
    public static List<CharacterClass> choices(final CharacterClass current) {
        final List<CharacterClass> list = new ArrayList<>();
        for (final CharacterClass value : CharacterClass.values()) {
            if (value != current) {
                list.add(value);
            }
        }
        return list;
    }

    /** 不能換的原因；可以換回傳 null。 */
    public static String problem(final CharacterClass current, final CharacterClass target) {
        if (current == null) {
            return "還沒有選角色";
        }
        if (target == null) {
            return "沒有這個職業";
        }
        if (current == target) {
            return "已經是" + target.displayName() + "了";
        }
        return null;
    }

    /**
     * 要從角色進度刪掉的鍵（照進度裡現有的；順序固定，方便寫資料庫與測試）。
     * 完成過的試煉、昇華試煉的週次數、其他系統的進度都不碰。
     */
    public static List<String> progressToRemove(final Map<String, String> progress) {
        final List<String> keys = new ArrayList<>();
        if (progress == null) {
            return keys;
        }
        for (final String key : List.of(ASC_CHOSEN, ASC_NODES, ASC_MERIDIANS)) {
            if (progress.containsKey(key)) {
                keys.add(key);
            }
        }
        final List<String> rest = new ArrayList<>();
        for (final String key : progress.keySet()) {
            if (key.startsWith(ATTRIBUTE_PREFIX) || key.startsWith(TREE_SET_PREFIX) && !key.equals(TREE_SET_ACTIVE)) {
                rest.add(key);
            }
        }
        rest.sort(null);
        keys.addAll(rest);
        return keys;
    }

    /** 符文子午線紋著的符文（詞綴代號，空的孔略過）：換職業時還給玩家。 */
    public static List<String> meridianRunes(final Map<String, String> progress) {
        final List<String> runes = new ArrayList<>();
        final String value = progress == null ? null : progress.get(ASC_MERIDIANS);
        if (value == null || value.isBlank()) {
            return runes;
        }
        for (final String part : value.split("\\|", -1)) {
            if (!part.isBlank()) {
                runes.add(part.trim());
            }
        }
        return runes;
    }

    /** 確認畫面上寫給玩家看的規則（照 {@link CharacterService#changeClass} 真的做的事）。 */
    public static List<String> rules(final CharacterClass target) {
        final List<String> lines = new ArrayList<>();
        lines.add("<gray>天賦樹的起點換到" + target.displayName() + "：<white>天賦點全部退回</white>（可以重配）");
        lines.add("<gray>天賦樹孔裡的珠寶、天賦組存著的配點與珠寶：珠寶還你，配點清掉");
        lines.add("<gray>昇華職業退掉、昇華點全部還你：照平常的方式重選" + target.displayName() + "的昇華職業");
        lines.add("<gray>命匣裡的珠寶、符文子午線紋的符文也還你");
        lines.add("<gray>起始屬性換成" + target.displayName() + "的（力量 " + target.strength() + "、敏捷 " + target.dexterity()
                + "、智慧 " + target.intelligence() + "）");
        lines.add("<green>等級、經驗、裝備、技能、通貨、倉庫、任務都不變");
        lines.add("<dark_gray>不會再送新手裝備與起始技能");
        return lines;
    }
}
