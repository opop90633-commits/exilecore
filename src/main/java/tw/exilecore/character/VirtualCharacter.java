package tw.exilecore.character;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 網頁計算器（2026-10，像 PoB）的虛擬角色：不在線、不存檔，只拿來算屬性表與每秒傷害。
 * 跟真的角色用同一個 {@link PlayerState}（職業、等級、天賦、進度、技能面板），算法才會一模一樣。
 * 純資料，不依賴伺服器。
 */
public final class VirtualCharacter {

    /** 虛擬角色的角色編號（不會跟資料庫裡的撞到）。 */
    public static final long VIRTUAL_ID = -1;
    /** 虛擬角色最高幾級（2026-10：等級上限 120；計算器再照伺服器的 level_cap 夾）。 */
    public static final int MAX_LEVEL = 200;

    private VirtualCharacter() {
    }

    /**
     * 做一個虛擬角色的狀態。
     *
     * @param characterClass 職業
     * @param level          等級（夾在 1–MAX_LEVEL）
     * @param passives       已配置的天賦節點（不含起點）
     * @param progress       角色進度（昇華、屬性點的選擇、任務、Boss 首殺…；只拿來算加成）
     * @param skills         技能面板（技能、等級、輔助、按鍵）
     */
    public static PlayerState state(final CharacterClass characterClass, final int level, final Set<String> passives,
                                    final Map<String, String> progress, final SkillLoadout skills) {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        final CharacterRecord record = new CharacterRecord(VIRTUAL_ID, new UUID(0, 0), 0, "計算器", characterClass);
        record.setLevel(Math.max(1, Math.min(MAX_LEVEL, level)));
        state.setCharacter(record, passives, progress, skills);
        state.setLoaded(true);
        return state;
    }
}
