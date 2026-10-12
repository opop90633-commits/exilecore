package tw.exilecore.character;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 角色的技能面板（PoE2 的做法：寶石不插裝備）：最多 27 個技能（三頁，一頁 9 個），各有等級與輔助孔；
 * 10 個按鍵格（左鍵、右鍵、潛行＋左鍵、潛行＋右鍵、數字鍵 3–8）各指向其中一個技能，沒指向的格子是普攻。
 * 2026-10：拿掉 F 鍵切換的 B 組，B 組的四格（以前的格子 4–7）剛好變成數字鍵 3–6，舊存檔不用轉換。
 * <p>
 * 純資料，不依賴 Bukkit；跟角色存檔一起存（character_skills、character_skill_binds）。
 */
public final class SkillLoadout {

    /** 一頁幾個技能。 */
    public static final int PAGE_SIZE = 9;
    /** 幾頁。 */
    public static final int PAGES = 3; // 2026-10：2 → 3（18 → 27 格）
    /** 最多幾個技能。 */
    public static final int MAX_SKILLS = PAGE_SIZE * PAGES;
    /** 滑鼠的按鍵：左鍵、右鍵、潛行＋左鍵、潛行＋右鍵。 */
    public static final int KEYS = 4;
    /** 數字鍵格子：3–8（快捷列的第 3–8 格；1、2 是藥劑，9 是手上的武器）。 */
    public static final int HOTBAR_KEYS = 6;
    /** 第一個數字鍵（鍵盤上的 3）。 */
    public static final int FIRST_HOTBAR_KEY = 3;
    public static final int BIND_SLOTS = KEYS + HOTBAR_KEYS;
    /** 技能等級上限。 */
    public static final int MAX_LEVEL = 20;
    /** 2026-10：技能寶石最高 Lv.21（只有 Lv.21 的未切割技能寶石做得到：劫盜大金庫、金庫之主掉）。 */
    public static final int GEM_CAP = 21;

    public static final int KEY_LEFT = 0;
    public static final int KEY_RIGHT = 1;
    public static final int KEY_SNEAK_LEFT = 2;
    public static final int KEY_SNEAK_RIGHT = 3;

    /** 技能一開始有幾個輔助孔（PoE2：2 個）。 */
    public static final int BASE_SOCKETS = 2;
    /** 輔助孔上限（PoE2：用珠寶匠寶珠開到 5 個）。 */
    public static final int MAX_SOCKETS = 5;

    /**
     * 一個技能：代號、等級、輔助孔數、掛的輔助寶石（長度固定 {@link #MAX_SOCKETS}，空孔是空字串）。
     */
    public record Entry(String skillId, int level, int sockets, List<String> supports) {
        public Entry {
            if (skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("技能代號不能是空的");
            }
            level = Math.max(1, Math.min(GEM_CAP, level));
            sockets = Math.max(BASE_SOCKETS, Math.min(MAX_SOCKETS, sockets));
            final String[] filled = new String[MAX_SOCKETS];
            Arrays.fill(filled, "");
            if (supports != null) {
                for (int i = 0; i < Math.min(sockets, supports.size()); i++) {
                    final String id = supports.get(i);
                    filled[i] = id == null ? "" : id.trim();
                }
            }
            supports = List.of(filled);
        }

        /** 新做出來的技能：2 個空的輔助孔。 */
        public Entry(final String skillId, final int level) {
            this(skillId, level, BASE_SOCKETS, List.of());
        }

        /** 第 index 個輔助孔掛的輔助寶石；空的或超出範圍回傳空字串。 */
        public String support(final int index) {
            return index >= 0 && index < MAX_SOCKETS ? supports.get(index) : "";
        }

        public Entry withLevel(final int newLevel) {
            return new Entry(skillId, newLevel, sockets, supports);
        }

        public Entry withSockets(final int newSockets) {
            return new Entry(skillId, level, newSockets, supports);
        }

        /** 換掉一個輔助孔的內容（null 或空字串 = 拿掉）。 */
        public Entry withSupport(final int index, final String supportId) {
            if (index < 0 || index >= sockets) {
                throw new IllegalArgumentException("輔助孔超出範圍：" + index);
            }
            final List<String> list = new ArrayList<>(supports);
            list.set(index, supportId == null ? "" : supportId);
            return new Entry(skillId, level, sockets, list);
        }

        /** 第一個空的輔助孔；沒有回傳 -1。 */
        public int freeSocket() {
            for (int i = 0; i < sockets; i++) {
                if (supports.get(i).isEmpty()) {
                    return i;
                }
            }
            return -1;
        }

        /** 掛著的輔助寶石（依孔的順序，空孔略過）。 */
        public List<String> supportIds() {
            final List<String> list = new ArrayList<>();
            for (int i = 0; i < sockets; i++) {
                if (!supports.get(i).isEmpty()) {
                    list.add(supports.get(i));
                }
            }
            return list;
        }

        public boolean hasSupport(final String supportId) {
            return supportIds().contains(supportId);
        }

        /** 存檔用的文字：「孔數:輔助1,輔助2,…」，空孔留空，例如 3:faster_attacks,,pierce。 */
        public String encodeSupports() {
            return sockets + ":" + String.join(",", supports.subList(0, sockets));
        }

        /** 讀存檔；壞掉的文字當成 2 個空孔。 */
        public static Entry decode(final String skillId, final int level, final String text) {
            if (text == null || text.isBlank()) {
                return new Entry(skillId, level);
            }
            final int colon = text.indexOf(':');
            int sockets = BASE_SOCKETS;
            String list = text;
            if (colon >= 0) {
                try {
                    sockets = Integer.parseInt(text.substring(0, colon).trim());
                } catch (final NumberFormatException e) {
                    sockets = BASE_SOCKETS;
                }
                list = text.substring(colon + 1);
            }
            return new Entry(skillId, level, sockets, Arrays.asList(list.split(",", -1)));
        }
    }

    private final Entry[] skills = new Entry[MAX_SKILLS];
    private final int[] binds = new int[BIND_SLOTS];

    public SkillLoadout() {
        Arrays.fill(binds, -1);
    }

    /** 數字鍵（鍵盤上的 3–8）的按鍵格子；不是技能鍵回傳 -1。 */
    public static int hotbarBind(final int number) {
        return number >= FIRST_HOTBAR_KEY && number < FIRST_HOTBAR_KEY + HOTBAR_KEYS ? KEYS + number - FIRST_HOTBAR_KEY : -1;
    }

    /** 快捷列格子（0 起算）對應的按鍵格子；不是技能鍵回傳 -1。 */
    public static int bindForHotbarSlot(final int hotbarIndex) {
        return hotbarBind(hotbarIndex + 1);
    }

    /** 按鍵格子的名字：「左鍵」「潛行＋右鍵」「數字鍵 3」。 */
    public static String keyName(final int bindSlot) {
        return switch (bindSlot) {
            case KEY_LEFT -> "左鍵";
            case KEY_RIGHT -> "右鍵";
            case KEY_SNEAK_LEFT -> "潛行＋左鍵";
            case KEY_SNEAK_RIGHT -> "潛行＋右鍵";
            default -> bindSlot >= KEYS && bindSlot < BIND_SLOTS ? "數字鍵 " + (FIRST_HOTBAR_KEY + bindSlot - KEYS) : "?";
        };
    }

    /** 短的名字（狀態列用）：「左」「蹲右」「3」。 */
    public static String shortKeyName(final int bindSlot) {
        return switch (bindSlot) {
            case KEY_LEFT -> "左";
            case KEY_RIGHT -> "右";
            case KEY_SNEAK_LEFT -> "蹲左";
            case KEY_SNEAK_RIGHT -> "蹲右";
            default -> String.valueOf(FIRST_HOTBAR_KEY + bindSlot - KEYS);
        };
    }

    /** 是不是滑鼠的按鍵（左、右，含潛行）。 */
    public static boolean mouseKey(final int bindSlot) {
        return bindSlot >= 0 && bindSlot < KEYS;
    }

    public Entry skill(final int position) {
        return position >= 0 && position < MAX_SKILLS ? skills[position] : null;
    }

    /** 技能在面板上的位置；沒有回傳 -1。 */
    public int find(final String skillId) {
        for (int i = 0; i < MAX_SKILLS; i++) {
            if (skills[i] != null && skills[i].skillId().equals(skillId)) {
                return i;
            }
        }
        return -1;
    }

    public int size() {
        int count = 0;
        for (final Entry entry : skills) {
            if (entry != null) {
                count++;
            }
        }
        return count;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 加入技能（已經有就改等級）。
     *
     * @return 技能的位置；面板滿了回傳 -1
     */
    public int put(final String skillId, final int level) {
        final int existing = find(skillId);
        if (existing >= 0) {
            skills[existing] = skills[existing].withLevel(level);
            return existing;
        }
        return add(new Entry(skillId, level));
    }

    /**
     * 放進第一個空格（技能寶石放進面板）。
     *
     * @return 位置；面板滿了回傳 -1，已經有同一個技能回傳 -2
     */
    public int add(final Entry entry) {
        if (find(entry.skillId()) >= 0) {
            return -2;
        }
        for (int i = 0; i < MAX_SKILLS; i++) {
            if (skills[i] == null) {
                skills[i] = entry;
                return i;
            }
        }
        return -1;
    }

    /** 哪個技能掛著這個輔助寶石（PoE2：同一個輔助一個角色只能用一次）；沒有回傳 -1。 */
    public int supportUsedBy(final String supportId) {
        for (int i = 0; i < MAX_SKILLS; i++) {
            if (skills[i] != null && skills[i].hasSupport(supportId)) {
                return i;
            }
        }
        return -1;
    }

    /** 放在指定位置（載入存檔、換技能的等級與輔助）；會蓋掉原本的，按鍵保留（放 null 會清掉指向它的按鍵）。 */
    public void set(final int position, final Entry entry) {
        if (position < 0 || position >= MAX_SKILLS) {
            throw new IllegalArgumentException("技能位置超出範圍：" + position);
        }
        skills[position] = entry;
        if (entry == null) {
            unbindPosition(position);
        }
    }

    /** 移除技能，指向它的按鍵回到普攻。 */
    public boolean remove(final int position) {
        if (skill(position) == null) {
            return false;
        }
        skills[position] = null;
        unbindPosition(position);
        return true;
    }

    private void unbindPosition(final int position) {
        for (int i = 0; i < BIND_SLOTS; i++) {
            if (binds[i] == position) {
                binds[i] = -1;
            }
        }
    }

    /** 按鍵格子指向的技能位置；-1 = 普攻。 */
    public int bound(final int slot) {
        return slot >= 0 && slot < BIND_SLOTS ? binds[slot] : -1;
    }

    /** 按鍵格子指向的技能；普攻回傳 null。 */
    public Entry boundSkill(final int slot) {
        return skill(bound(slot));
    }

    /** 把技能綁到按鍵格子；position = -1 表示改回普攻。位置上沒有技能時不做事並回傳 false。 */
    public boolean bind(final int slot, final int position) {
        if (slot < 0 || slot >= BIND_SLOTS) {
            return false;
        }
        if (position == -1) {
            binds[slot] = -1;
            return true;
        }
        if (skill(position) == null) {
            return false;
        }
        binds[slot] = position;
        return true;
    }

    /**
     * 綁在按鍵格的技能，照按鍵的順序（2026-10）：數字鍵 3→8，再來左鍵、右鍵、潛行＋左鍵、潛行＋右鍵；
     * 後面接沒綁鍵的（照面板位置）。光環、精魂召喚照這個順序開、分精魂（前面的先拿）。
     */
    public List<Entry> entriesInKeyOrder() {
        final List<Entry> list = new ArrayList<>();
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        final int[] order = new int[BIND_SLOTS];
        int n = 0;
        for (int slot = KEYS; slot < BIND_SLOTS; slot++) {
            order[n++] = slot;
        }
        for (int slot = 0; slot < KEYS; slot++) {
            order[n++] = slot;
        }
        for (final int slot : order) {
            final int position = binds[slot];
            if (position >= 0 && position < MAX_SKILLS && skills[position] != null && seen.add(position)) {
                list.add(skills[position]);
            }
        }
        for (int position = 0; position < MAX_SKILLS; position++) {
            if (skills[position] != null && seen.add(position)) {
                list.add(skills[position]);
            }
        }
        return list;
    }

    /** 所有技能（依位置，沒有的略過）。 */
    public List<Entry> entries() {
        final List<Entry> list = new ArrayList<>();
        for (final Entry entry : skills) {
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    /** 深拷貝（存檔時在主執行緒拍快照）。 */
    public SkillLoadout copy() {
        final SkillLoadout copy = new SkillLoadout();
        System.arraycopy(skills, 0, copy.skills, 0, MAX_SKILLS);
        System.arraycopy(binds, 0, copy.binds, 0, BIND_SLOTS);
        return copy;
    }

    /** 清空（管理指令用）。 */
    public void clear() {
        Arrays.fill(skills, null);
        Arrays.fill(binds, -1);
    }
}
