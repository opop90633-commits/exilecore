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
     * 一個技能：代號、等級、輔助孔數、掛的輔助寶石（長度固定 {@link #MAX_SOCKETS}，空孔是空字串）、
     * 每一孔的輔助有沒有共鳴（2026-10-18：用過共鳴石的專屬輔助不佔專屬名額；長度也固定 {@link #MAX_SOCKETS}）。
     * 存檔裡共鳴的寶石代號後面接一個 {@value #RESONANT_SUFFIX}（例如 3:rift_echo~,,pierce）；建構式會把它剝掉放進 resonant，
     * {@link #support(int)} 永遠回傳乾淨的代號，舊資料沒有 ~ 就全是 false。
     * <p>
     * 2026-10-22：英靈圖騰與主要寶石（星咒圖騰、暴擊引星）把技能寶石放在自己的第 1 孔（{@link #inner}），
     * 第 2～5 孔的輔助都套在那顆技能上。存檔裡第 1 孔寫成 {@value #INNER_PREFIX}代號/等級/孔數（例如 5:@fireball/18/4,added_fire,,）；
     * 舊版的宿主寶石第 1 孔放的是輔助，載入後由 SkillService.migrateRemoved 往後挪一孔。
     *
     * @param inner 放在第 1 孔的技能寶石（代號、等級、它自己的孔數；輔助一律空的）；沒有或不是宿主寶石為 null
     * @param corrupted 2026-10-23：被蝕變石腐化過（照 PoE2：之後不能再升級、開孔、腐化；輔助照樣能換）。
     *                  存檔文字最前面寫 {@value #CORRUPTED_PREFIX}（例如 !5:fireball,,）
     */
    public record Entry(String skillId, int level, int sockets, List<String> supports, List<Boolean> resonant, Entry inner,
                        boolean corrupted, int quality, boolean granted) {

        /**
         * 2026-10-26：存檔文字裡「武器賦予的技能」的字首（PoE2 的法杖、長杖、權杖：Grants Skill）。
         * 賦予的技能不是寶石：不能升級、取出成寶石、腐化、加品質；等級照武器的物品等級，可以掛輔助、開孔。
         */
        public static final String GRANTED_PREFIX = "^";

        /** 2026-10-23：存檔文字裡「已腐化」的字首。 */
        public static final String CORRUPTED_PREFIX = "!";
        /** 2026-10-23：存檔文字裡「品質」的字首（例如 q15.5:fireball,,）；寶石放在宿主第 1 孔時寫成 /q15。 */
        public static final String QUALITY_PREFIX = "q";
        /** 2026-10-23：技能寶石的品質上限（琢星稜鏡最多到 20；腐化可以到 23）。 */
        public static final int QUALITY_MAX = 20;
        public static final int QUALITY_CORRUPTED_MAX = 23;

        /** 2026-10-18：存檔文字裡標「這顆輔助已共鳴」的字尾。 */
        public static final String RESONANT_SUFFIX = "~";
        /** 2026-10-22：存檔文字裡第 1 孔「放著技能寶石」的字首。 */
        public static final String INNER_PREFIX = "@";
        /** 放技能寶石的那一孔（宿主寶石的第 1 孔）。 */
        public static final int INNER_SOCKET = 0;

        public Entry {
            if (skillId == null || skillId.isBlank()) {
                throw new IllegalArgumentException("技能代號不能是空的");
            }
            level = Math.max(1, Math.min(GEM_CAP, level));
            sockets = Math.max(BASE_SOCKETS, Math.min(MAX_SOCKETS, sockets));
            // 2026-10-27 照 PoE2 0.5：武器賦予的技能的品質就是武器的品質（不套寶石的上限，特殊詞綴超過 20% 也照算）
            quality = granted ? Math.max(0, quality) : Math.max(0, Math.min(QUALITY_CORRUPTED_MAX, quality));
            final String[] filled = new String[MAX_SOCKETS];
            final Boolean[] marks = new Boolean[MAX_SOCKETS];
            Arrays.fill(filled, "");
            Arrays.fill(marks, Boolean.FALSE);
            if (supports != null) {
                for (int i = 0; i < Math.min(sockets, supports.size()); i++) {
                    String id = supports.get(i) == null ? "" : supports.get(i).trim();
                    if (i == INNER_SOCKET && id.startsWith(INNER_PREFIX)) { // 2026-10-22：第 1 孔放著技能寶石
                        if (inner == null) {
                            inner = decodeInner(id.substring(INNER_PREFIX.length()));
                        }
                        continue;
                    }
                    if (id.startsWith(INNER_PREFIX)) {
                        // 2026-10-26 照 PoE2 Meta Gem（「Triggers all Socketed Spells」）：主要寶石第 2～5 孔也可以放技能寶石，
                        //   原樣存成 @代號/等級/孔數（壞掉的當空孔）；這次之前只有第 1 孔能放
                        final Entry spell = decodeInner(id.substring(INNER_PREFIX.length()));
                        filled[i] = spell == null ? "" : INNER_PREFIX + innerText(spell);
                        continue;
                    }
                    if (id.endsWith(RESONANT_SUFFIX)) { // 2026-10-18：存檔裡的 ~ 剝掉，記成共鳴
                        id = id.substring(0, id.length() - RESONANT_SUFFIX.length()).trim();
                        marks[i] = Boolean.TRUE;
                    }
                    filled[i] = id;
                    if (resonant != null && i < resonant.size() && Boolean.TRUE.equals(resonant.get(i))) {
                        marks[i] = Boolean.TRUE;
                    }
                    if (filled[i].isEmpty()) {
                        marks[i] = Boolean.FALSE; // 空孔不會共鳴
                    }
                }
            }
            if (inner != null) {
                filled[INNER_SOCKET] = ""; // 放著技能寶石的那一孔不會同時有輔助
                marks[INNER_SOCKET] = Boolean.FALSE;
                if (!inner.supports().stream().allMatch(String::isEmpty) || inner.inner() != null) {
                    inner = new Entry(inner.skillId(), inner.level(), inner.sockets(), List.of(), null, null, inner.corrupted(), inner.quality()); // 裡面的技能自己沒有輔助
                }
            }
            supports = List.of(filled);
            resonant = List.of(marks);
        }

        /** 2026-10-26：不是武器賦予的技能（一般的技能寶石）。 */
        public Entry(final String skillId, final int level, final int sockets, final List<String> supports, final List<Boolean> resonant,
                     final Entry inner, final boolean corrupted, final int quality) {
            this(skillId, level, sockets, supports, resonant, inner, corrupted, quality, false);
        }

        /** 2026-10-26：標成武器賦予的技能（或拿掉）。 */
        public Entry withGranted(final boolean value) {
            return new Entry(skillId, level, sockets, supports, resonant, inner, corrupted, quality, value);
        }

        /** 2026-10-23：沒寫腐化、品質的建構式（沒腐化、品質 0）。 */
        public Entry(final String skillId, final int level, final int sockets, final List<String> supports, final List<Boolean> resonant,
                     final Entry inner) {
            this(skillId, level, sockets, supports, resonant, inner, false, 0);
        }

        /** 2026-10-23：沒寫品質的建構式（品質 0）。 */
        public Entry(final String skillId, final int level, final int sockets, final List<String> supports, final List<Boolean> resonant,
                     final Entry inner, final boolean corrupted) {
            this(skillId, level, sockets, supports, resonant, inner, corrupted, 0);
        }

        /** 2026-10-23：同一顆寶石標成已腐化（或拿掉）。 */
        public Entry withCorrupted(final boolean value) {
            return new Entry(skillId, level, sockets, supports, resonant, inner, value, quality, granted);
        }

        /** 2026-10-23：換掉品質（0～23）。 */
        public Entry withQuality(final int value) {
            return new Entry(skillId, level, sockets, supports, resonant, inner, corrupted, value, granted);
        }

        /** 2026-10-22：舊的五個欄位的建構式（第 1 孔沒有技能寶石；supports 裡寫 @ 開頭的照樣認得）。 */
        public Entry(final String skillId, final int level, final int sockets, final List<String> supports, final List<Boolean> resonant) {
            this(skillId, level, sockets, supports, resonant, null);
        }

        /** 沒有共鳴資訊的建構式（代號裡可以帶 ~）。 */
        public Entry(final String skillId, final int level, final int sockets, final List<String> supports) {
            this(skillId, level, sockets, supports, null, null);
        }

        /** 存檔裡「@代號/等級/孔數」後面那段；壞掉的回傳 null。 */
        private static Entry decodeInner(final String text) {
            final String[] parts = text.split("/", -1);
            if (parts.length < 1 || parts[0].isBlank()) {
                return null;
            }
            try {
                final int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                final int sockets = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : BASE_SOCKETS;
                boolean corrupted = false;
                int quality = 0;
                for (int i = 3; i < parts.length; i++) { // 2026-10-23：/! 已腐化、/q15 品質
                    final String part = parts[i].trim();
                    if (CORRUPTED_PREFIX.equals(part)) {
                        corrupted = true;
                    } else if (part.startsWith(QUALITY_PREFIX)) {
                        quality = Integer.parseInt(part.substring(QUALITY_PREFIX.length()));
                    }
                }
                return new Entry(parts[0].trim(), level, sockets, List.of(), null, null, corrupted, quality);
            } catch (final NumberFormatException e) {
                return null;
            }
        }

        /** 2026-10-26：放在孔裡的技能寶石寫成存檔文字（@ 後面那段：代號/等級/孔數[/!][/q品質]）。 */
        static String innerText(final Entry spell) {
            final StringBuilder text = new StringBuilder();
            text.append(spell.skillId()).append('/').append(spell.level()).append('/').append(spell.sockets());
            if (spell.corrupted()) {
                text.append('/').append(CORRUPTED_PREFIX); // 2026-10-23：放進去的腐化寶石拿出來還是腐化的
            }
            if (spell.quality() > 0) {
                text.append('/').append(QUALITY_PREFIX).append(spell.quality()); // 2026-10-23：品質也跟著
            }
            return text.toString();
        }

        /** 2026-10-26：第 index 孔放著技能寶石（第 1 孔的 {@link #inner}，或主要寶石第 2～5 孔的 @…）。 */
        public boolean spellSocket(final int index) {
            if (index == INNER_SOCKET) {
                return inner != null;
            }
            return index > 0 && index < MAX_SOCKETS && supports.get(index).startsWith(INNER_PREFIX);
        }

        /** 2026-10-26：第 index 孔裡的技能寶石；不是放技能的孔回傳 null。 */
        public Entry spellAt(final int index) {
            if (index == INNER_SOCKET) {
                return inner;
            }
            return spellSocket(index) ? decodeInner(supports.get(index).substring(INNER_PREFIX.length())) : null;
        }

        /** 2026-10-26 照 PoE2：孔裡放著的所有技能寶石（照孔的順序；第 1 孔的在最前面）。 */
        public List<Entry> spells() {
            final List<Entry> list = new ArrayList<>();
            for (int i = 0; i < sockets; i++) {
                final Entry spell = spellAt(i);
                if (spell != null) {
                    list.add(spell);
                }
            }
            return list;
        }

        /** 2026-10-26：第 index 孔放（或拿掉，null）技能寶石；第 1 孔就是 {@link #withInner}。 */
        public Entry withSpell(final int index, final Entry spell) {
            if (index == INNER_SOCKET) {
                return withInner(spell);
            }
            final Entry clean = spell == null ? null
                    : new Entry(spell.skillId(), spell.level(), spell.sockets(), List.of(), null, null, spell.corrupted(), spell.quality());
            return withSupport(index, clean == null ? "" : INNER_PREFIX + innerText(clean), false);
        }

        /** 2026-10-22：換掉第 1 孔的技能寶石（null = 拿掉）。 */
        public Entry withInner(final Entry newInner) {
            return new Entry(skillId, level, sockets, supports, resonant, newInner, corrupted, quality, granted);
        }

        /** 2026-10-22：第 1 孔放著技能寶石時，第 1 孔不算輔助孔：第一個能放輔助的孔。 */
        public int firstSupportSocket() {
            return inner != null ? INNER_SOCKET + 1 : 0;
        }

        /** 新做出來的技能：2 個空的輔助孔。 */
        public Entry(final String skillId, final int level) {
            this(skillId, level, BASE_SOCKETS, List.of());
        }

        /** 第 index 個輔助孔掛的輔助寶石；空的或超出範圍回傳空字串。 */
        public String support(final int index) {
            return index >= 0 && index < MAX_SOCKETS ? supports.get(index) : "";
        }

        /** 2026-10-18：第 index 個輔助孔的輔助有沒有共鳴（不佔專屬名額）；空孔或超出範圍回傳 false。 */
        public boolean resonant(final int index) {
            return index >= 0 && index < MAX_SOCKETS && resonant.get(index);
        }

        public Entry withLevel(final int newLevel) {
            return new Entry(skillId, newLevel, sockets, supports, resonant, inner, corrupted, quality, granted);
        }

        public Entry withSockets(final int newSockets) {
            return new Entry(skillId, level, newSockets, supports, resonant, inner, corrupted, quality, granted);
        }

        /** 換掉一個輔助孔的內容（null 或空字串 = 拿掉）；沒有共鳴。 */
        public Entry withSupport(final int index, final String supportId) {
            return withSupport(index, supportId, false);
        }

        /** 2026-10-18：換掉一個輔助孔的內容（null 或空字串 = 拿掉），並記這顆輔助有沒有共鳴。 */
        public Entry withSupport(final int index, final String supportId, final boolean isResonant) {
            if (index < 0 || index >= sockets) {
                throw new IllegalArgumentException("輔助孔超出範圍：" + index);
            }
            final List<String> list = new ArrayList<>(supports);
            final List<Boolean> marks = new ArrayList<>(resonant);
            list.set(index, supportId == null ? "" : supportId);
            marks.set(index, isResonant);
            return new Entry(skillId, level, sockets, list, marks, inner, corrupted, quality, granted);
        }

        /** 2026-10-18：只改一孔的共鳴旗標（孔裡要有輔助）。 */
        public Entry withResonant(final int index, final boolean isResonant) {
            return withSupport(index, support(index), isResonant);
        }

        /** 第一個空的輔助孔（放著技能寶石的第 1 孔不算）；沒有回傳 -1。 */
        public int freeSocket() {
            for (int i = firstSupportSocket(); i < sockets; i++) {
                if (supports.get(i).isEmpty()) {
                    return i;
                }
            }
            return -1;
        }

        /** 掛著的輔助寶石（依孔的順序，空孔略過；2026-10-26：放著技能寶石的孔也略過）。 */
        public List<String> supportIds() {
            final List<String> list = new ArrayList<>();
            for (int i = 0; i < sockets; i++) {
                if (!supports.get(i).isEmpty() && !supports.get(i).startsWith(INNER_PREFIX)) {
                    list.add(supports.get(i));
                }
            }
            return list;
        }

        public boolean hasSupport(final String supportId) {
            return supportIds().contains(supportId);
        }

        /** 存檔用的文字：「孔數:輔助1,輔助2,…」，空孔留空，共鳴的代號後面接 ~，例如 3:faster_attacks,,pierce~。 */
        public String encodeSupports() {
            final StringBuilder text = new StringBuilder();
            if (granted) {
                text.append(GRANTED_PREFIX); // 2026-10-26
            }
            if (corrupted) {
                text.append(CORRUPTED_PREFIX); // 2026-10-23
            }
            if (quality > 0 && !granted) { // 2026-10-27：賦予的技能的品質照武器（每次重算同步），不存檔
                text.append(QUALITY_PREFIX).append(quality).append('.'); // 2026-10-23：品質
            }
            text.append(sockets).append(':');
            for (int i = 0; i < sockets; i++) {
                if (i > 0) {
                    text.append(',');
                }
                if (i == INNER_SOCKET && inner != null) { // 2026-10-22：第 1 孔的技能寶石
                    text.append(INNER_PREFIX).append(innerText(inner));
                    continue;
                }
                text.append(supports.get(i));
                if (resonant.get(i) && !supports.get(i).isEmpty()) {
                    text.append(RESONANT_SUFFIX); // 2026-10-18：共鳴旗標
                }
            }
            return text.toString();
        }

        /** 讀存檔；壞掉的文字當成 2 個空孔。 */
        public static Entry decode(final String skillId, final int level, final String text) {
            if (text == null || text.isBlank()) {
                return new Entry(skillId, level);
            }
            if (text.startsWith(GRANTED_PREFIX)) { // 2026-10-26：武器賦予的技能
                return decode(skillId, level, text.substring(GRANTED_PREFIX.length())).withGranted(true);
            }
            if (text.startsWith(CORRUPTED_PREFIX)) { // 2026-10-23：已腐化
                return decode(skillId, level, text.substring(CORRUPTED_PREFIX.length())).withCorrupted(true);
            }
            if (text.startsWith(QUALITY_PREFIX)) { // 2026-10-23：品質（q15.孔數:…）
                final int dot = text.indexOf('.');
                if (dot > 0) {
                    int quality = 0;
                    try {
                        quality = Integer.parseInt(text.substring(QUALITY_PREFIX.length(), dot));
                    } catch (final NumberFormatException ignored) {
                        // 壞掉的品質當 0
                    }
                    return decode(skillId, level, text.substring(dot + 1)).withQuality(quality);
                }
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
    /**
     * 2026-10-26：身上的武器現在賦予哪些技能（技能代號 → 等級；不存檔，換裝備時由屬性表重算）。
     * 面板上標成賦予的技能不在這裡面就是「沒拿著那把武器」：留著輔助與按鍵，但不能用。
     */
    private java.util.Map<String, Integer> grantedNow = java.util.Map.of();
    /**
     * 2026-10-26 照 PoE2：屬性需求不足的技能寶石（技能代號 → 原因；不存檔，屬性表算完時重算，見 tw.exilecore.skill.GemAttributes）。
     * 跟沒拿著武器的賦予技能一樣不能用（光環、常駐關掉），但按鍵還是指著它（按下去提示缺什麼，不會變成普攻）。
     */
    private java.util.Map<String, String> unmetNow = java.util.Map.of();

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

    /** 2026-10-22：放在某個宿主寶石孔裡的技能（2026-10-26：第 2～5 孔的也算）；那個宿主在面板上的位置，沒有回傳 -1。 */
    public int findInner(final String skillId) {
        for (int i = 0; i < MAX_SKILLS; i++) {
            if (skills[i] == null) {
                continue;
            }
            for (final Entry spell : skills[i].spells()) { // 2026-10-26：主要寶石第 2～5 孔的技能寶石也算
                if (spell.skillId().equals(skillId)) {
                    return i;
                }
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

    /** 按鍵格子指向的技能；普攻回傳 null（2026-10-26：武器賦予、但現在沒拿著那把武器的技能也當普攻）。 */
    public Entry boundSkill(final int slot) {
        final Entry entry = skill(bound(slot));
        return entry == null || dormant(entry) && !unmet(entry) ? null : entry;
    }

    /** 2026-10-26：武器賦予的技能、但現在身上沒有賦予它的武器（不能用）；2026-10-26 也包含屬性需求不足的技能寶石（{@link #unmet}）。 */
    public boolean dormant(final Entry entry) {
        return entry != null && (entry.granted() && !grantedNow.containsKey(entry.skillId()) || unmet(entry));
    }

    /** 2026-10-26 照 PoE2：屬性需求不足的技能寶石（不能用）。 */
    public boolean unmet(final Entry entry) {
        return entry != null && !entry.granted() && unmetNow.containsKey(entry.skillId());
    }

    /** 2026-10-26：屬性需求不足的原因（例如「力量不足（需要 52，你有 40）」）；夠的話回傳 null。 */
    public String unmetReason(final String skillId) {
        return unmetNow.get(skillId);
    }

    /**
     * 2026-10-26：照屬性表重算的「屬性需求不足」（技能代號 → 原因）。
     *
     * @return 這次新變成不足的技能代號（提醒玩家用）
     */
    public java.util.List<String> syncUnmet(final java.util.Map<String, String> unmet) {
        final java.util.Map<String, String> next = unmet == null ? java.util.Map.of() : java.util.Map.copyOf(unmet);
        final java.util.List<String> added = new ArrayList<>();
        for (final String id : next.keySet()) {
            if (!unmetNow.containsKey(id)) {
                added.add(id);
            }
        }
        unmetNow = next;
        return added;
    }

    /** 2026-10-26：身上的武器現在賦予的技能（技能代號 → 等級，唯讀）。 */
    public java.util.Map<String, Integer> grantedNow() {
        return grantedNow;
    }

    /**
     * 2026-10-26：照身上的武器同步賦予的技能（PoE2：法杖、長杖、權杖的 Grants Skill）。
     * 面板上已經有的（標成賦予的）改成武器給的等級；沒有的放進第一個空格（輔助孔 2 個）。
     * 已經有同一個技能的寶石就不另外放（用寶石那個）。拿掉武器的不刪：輔助、按鍵都留著，只是不能用（{@link #dormant}）。
     *
     * @return 面板有沒有變（要存檔）
     */
    public boolean syncGranted(final java.util.Map<String, Integer> desired) {
        return syncGranted(desired, java.util.Map.of());
    }

    /**
     * 2026-10-27 照 PoE2 0.5（poe2wiki Quality）：法杖、長杖、權杖的品質不加武器自己的數值，改成加在賦予的技能上
     * （每 1% 品質 = 賦予的技能 +1% 品質，效果照 gem_quality.yml／通用規則，跟技能寶石一樣）。
     * quality：技能代號 → 武器的品質（沒寫的 0）。品質不存檔，每次重算照身上的武器同步。這次之前賦予的技能沒有品質。
     */
    public boolean syncGranted(final java.util.Map<String, Integer> desired, final java.util.Map<String, Integer> quality) {
        grantedNow = desired == null ? java.util.Map.of() : java.util.Map.copyOf(desired);
        boolean changed = false;
        for (final java.util.Map.Entry<String, Integer> grant : grantedNow.entrySet()) {
            final int weaponQuality = quality == null ? 0 : Math.max(0, quality.getOrDefault(grant.getKey(), 0));
            final int position = find(grant.getKey());
            if (position >= 0) {
                final Entry entry = skills[position];
                if (entry.granted() && (entry.level() != grant.getValue() || entry.quality() != weaponQuality)) {
                    skills[position] = entry.withLevel(grant.getValue()).withQuality(weaponQuality);
                    changed = true;
                }
                continue;
            }
            if (add(new Entry(grant.getKey(), grant.getValue()).withGranted(true).withQuality(weaponQuality)) >= 0) {
                changed = true;
            }
        }
        return changed;
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
            if (position >= 0 && position < MAX_SKILLS && skills[position] != null && !dormant(skills[position]) && seen.add(position)) {
                list.add(skills[position]);
            }
        }
        for (int position = 0; position < MAX_SKILLS; position++) {
            if (skills[position] != null && !dormant(skills[position]) && seen.add(position)) {
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
        copy.grantedNow = grantedNow;
        return copy;
    }

    /** 清空（管理指令用）。 */
    public void clear() {
        Arrays.fill(skills, null);
        Arrays.fill(binds, -1);
    }
}
