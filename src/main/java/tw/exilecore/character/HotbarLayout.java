package tw.exilecore.character;

import java.util.Map;

/**
 * 快捷列的排法（2026-10，玩家自己選；設定 →「按鍵」）。第 9 格一律是手上的武器。
 * <ul>
 *   <li>{@link #CLASSIC}（沒選過）：藥劑欄照 flasks.yml 的 slots（1、2），3–8 放技能。</li>
 *   <li>自訂：生命、魔力藥劑各放 1–8 的任一格（兩格不同），剩下 6 格由左到右是技能鍵。</li>
 * </ul>
 * 技能鍵的綁定照「第幾個技能鍵」存（按鍵格子 {@link SkillLoadout#KEYS}+0…+5），換排法不用重綁：
 * 第 1 個技能鍵是剩下 6 格裡最左邊那一格。
 */
public final class HotbarLayout {

    /** 角色進度裡的鍵：值「生命格,魔力格」（0 起算，例如 {@code 4,5}）；舊的 {@code late} = 4,5；沒有 = 經典。 */
    public static final String PROGRESS = "hotbar.layout";
    /** 2026-10 第一版只有「5、6」一種選擇時存的值。 */
    static final String LATE_VALUE = "late";
    /** 藥劑與技能能放的格子（第 9 格是武器）。 */
    public static final int SLOTS = 8;

    public static final HotbarLayout CLASSIC = new HotbarLayout(new int[] {2, 3, 4, 5, 6, 7}, null);
    private static final HotbarLayout[][] CUSTOM = new HotbarLayout[SLOTS][SLOTS];

    static {
        for (int life = 0; life < SLOTS; life++) {
            for (int mana = 0; mana < SLOTS; mana++) {
                if (life == mana) {
                    continue;
                }
                final int[] skills = new int[SLOTS - 2];
                int n = 0;
                for (int slot = 0; slot < SLOTS; slot++) {
                    if (slot != life && slot != mana) {
                        skills[n++] = slot;
                    }
                }
                CUSTOM[life][mana] = new HotbarLayout(skills, new int[] {life, mana});
            }
        }
    }

    /** 第 i 個技能鍵在快捷列的哪一格（0 起算）。 */
    private final int[] skillSlots;
    /** 藥劑欄（生命、魔力）；null = 照 flasks.yml。 */
    private final int[] flaskSlots;

    private HotbarLayout(final int[] skillSlots, final int[] flaskSlots) {
        this.skillSlots = skillSlots;
        this.flaskSlots = flaskSlots;
    }

    /** 生命藥劑放 life、魔力藥劑放 mana（0–7，不能同一格）；不合法回傳 null。 */
    public static HotbarLayout custom(final int life, final int mana) {
        return life < 0 || mana < 0 || life >= SLOTS || mana >= SLOTS || life == mana ? null : CUSTOM[life][mana];
    }

    /** 這個角色選的排法。 */
    public static HotbarLayout of(final PlayerState state) {
        return state == null ? CLASSIC : of(state.progress());
    }

    public static HotbarLayout of(final Map<String, String> progress) {
        return progress == null ? CLASSIC : parse(progress.get(PROGRESS));
    }

    static HotbarLayout parse(final String value) {
        if (value == null) {
            return CLASSIC;
        }
        if (LATE_VALUE.equals(value)) {
            return CUSTOM[4][5];
        }
        final String[] parts = value.split(",");
        if (parts.length != 2) {
            return CLASSIC;
        }
        try {
            final HotbarLayout layout = custom(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            return layout == null ? CLASSIC : layout;
        } catch (final NumberFormatException e) {
            return CLASSIC;
        }
    }

    /** 存進角色進度的值（經典回傳 null = 刪掉那筆）。 */
    public String encode() {
        return flaskSlots == null ? null : flaskSlots[0] + "," + flaskSlots[1];
    }

    /** 是不是自己選過的排法（不是照 flasks.yml）。 */
    public boolean custom() {
        return flaskSlots != null;
    }

    /** 技能鍵有幾個。 */
    public int skillKeys() {
        return skillSlots.length;
    }

    /** 第 index 個技能鍵在快捷列的哪一格。 */
    public int skillSlot(final int index) {
        return skillSlots[index];
    }

    /** 快捷列這一格是第幾個技能鍵（不是技能鍵回傳 -1）。 */
    public int skillIndex(final int hotbarSlot) {
        for (int i = 0; i < skillSlots.length; i++) {
            if (skillSlots[i] == hotbarSlot) {
                return i;
            }
        }
        return -1;
    }

    public boolean isSkillSlot(final int hotbarSlot) {
        return skillIndex(hotbarSlot) >= 0;
    }

    /** 快捷列這一格對應的按鍵格子（不是技能鍵回傳 -1）。 */
    public int bindForSlot(final int hotbarSlot) {
        final int index = skillIndex(hotbarSlot);
        return index < 0 ? -1 : SkillLoadout.KEYS + index;
    }

    /** 按鍵格子在快捷列的哪一格（滑鼠鍵回傳 -1）。 */
    public int slotForBind(final int bindSlot) {
        final int index = bindSlot - SkillLoadout.KEYS;
        return index >= 0 && index < skillSlots.length ? skillSlots[index] : -1;
    }

    /**
     * 藥劑欄：which 0 = 生命、1 = 魔力；經典排法照 flasks.yml（configured）。
     */
    public int flaskSlot(final int which, final int[] configured) {
        return flaskSlots != null ? flaskSlots[which] : configured[which];
    }

    /** 兩格藥劑欄（生命、魔力）。 */
    public int[] flaskSlots(final int[] configured) {
        return flaskSlots != null ? flaskSlots.clone() : configured.clone();
    }

    /** 這一格是第幾個藥劑欄（0 生命、1 魔力；不是回傳 -1）。 */
    public int flaskIndex(final int hotbarSlot, final int[] configured) {
        final int[] slots = flaskSlots != null ? flaskSlots : configured;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == hotbarSlot) {
                return i;
            }
        }
        return -1;
    }

    /** 按鍵格子的名字：「左鍵」「潛行＋右鍵」「數字鍵 1」。 */
    public String keyName(final int bindSlot) {
        final int slot = slotForBind(bindSlot);
        return slot >= 0 ? "數字鍵 " + (slot + 1) : SkillLoadout.keyName(bindSlot);
    }

    /** 短的名字（狀態列用）：「左」「蹲右」「1」。 */
    public String shortKeyName(final int bindSlot) {
        final int slot = slotForBind(bindSlot);
        return slot >= 0 ? String.valueOf(slot + 1) : SkillLoadout.shortKeyName(bindSlot);
    }

    /** 技能鍵的說法：連續的寫「3–8」，不連續的寫「1、2、3、4、7、8」。 */
    public String skillKeysText() {
        boolean contiguous = true;
        for (int i = 1; i < skillSlots.length; i++) {
            contiguous &= skillSlots[i] == skillSlots[i - 1] + 1;
        }
        if (contiguous) {
            return (skillSlots[0] + 1) + "–" + (skillSlots[skillSlots.length - 1] + 1);
        }
        final StringBuilder out = new StringBuilder();
        for (final int slot : skillSlots) {
            if (!out.isEmpty()) {
                out.append("、");
            }
            out.append(slot + 1);
        }
        return out.toString();
    }

    /** 藥劑鍵的說法：「1、2」或「5、6」。 */
    public String flaskKeysText(final int[] configured) {
        final int[] slots = flaskSlots(configured);
        return (slots[0] + 1) + "、" + (slots[1] + 1);
    }
}
