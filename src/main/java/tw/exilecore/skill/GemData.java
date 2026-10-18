package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyType;

import java.util.Locale;
import java.util.Optional;

/**
 * 寶石物品的資料（存在物品的 PDC，一行文字）。純資料，不依賴 Bukkit。
 * <ul>
 *   <li>未切割技能寶石：等級 1–20，右鍵雕成那個等級的技能寶石，或把面板上的技能升一級（最高到它的等級）</li>
 *   <li>未切割輔助寶石：階級 1–3，右鍵切成一個階級不超過它的輔助寶石</li>
 *   <li>技能寶石：技能、等級、輔助孔、掛的輔助寶石（從面板取出來的樣子，放回去原樣還原）</li>
 *   <li>輔助寶石：輔助的代號</li>
 * </ul>
 *
 * @param id    技能或輔助的代號（未切割的為 null）
 * @param level 等級（未切割輔助寶石是階級；輔助寶石是 0）
 * @param entry 技能寶石的內容（其他種類為 null）
 * @param resonant 2026-10-18：輔助寶石有沒有用過共鳴石（共鳴的專屬輔助不佔一個技能的專屬名額；其他種類為 false）
 */
public record GemData(Kind kind, String id, int level, SkillLoadout.Entry entry, boolean resonant) {

    /** 2026-10-18：舊的四個欄位的建構式（沒有共鳴）。 */
    public GemData(final Kind kind, final String id, final int level, final SkillLoadout.Entry entry) {
        this(kind, id, level, entry, false);
    }

    /** 2026-10-18：輔助寶石第四欄寫這個字＝已共鳴。 */
    static final String RESONANT_FLAG = "resonant";

    /** 寶石的種類與存檔用的代號。 */
    public enum Kind {
        UNCUT_SKILL("uncut_skill"),
        UNCUT_SUPPORT("uncut_support"),
        SKILL("skill"),
        SUPPORT("support");

        private final String key;

        Kind(final String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Kind fromKey(final String key) {
            for (final Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public static GemData uncutSkill(final int level) {
        return new GemData(Kind.UNCUT_SKILL, null, clamp(level, 1, SkillLoadout.GEM_CAP), null);
    }

    public static GemData uncutSupport(final int tier) {
        return new GemData(Kind.UNCUT_SUPPORT, null, clamp(tier, 1, SupportDefinition.MAX_TIER), null);
    }

    public static GemData skill(final SkillLoadout.Entry entry) {
        return new GemData(Kind.SKILL, entry.skillId(), entry.level(), entry);
    }

    public static GemData support(final String supportId) {
        return new GemData(Kind.SUPPORT, supportId, 0, null);
    }

    /** 2026-10-18：輔助寶石，可以帶共鳴旗標。 */
    public static GemData support(final String supportId, final boolean resonant) {
        return new GemData(Kind.SUPPORT, supportId, 0, null, resonant);
    }

    /** 2026-10-18：同一顆寶石標成共鳴。 */
    public GemData withResonant(final boolean value) {
        return new GemData(kind, id, level, entry, value);
    }

    /** 一行文字：種類;代號;等級;輔助孔（技能寶石才有；輔助寶石這一欄寫 resonant＝已共鳴）。 */
    public String encode() {
        return kind.key + ";" + (id == null ? "" : id) + ";" + level + ";"
                + (entry != null ? entry.encodeSupports() : kind == Kind.SUPPORT && resonant ? RESONANT_FLAG : "");
    }

    /** 讀回來；格式不對回傳空的。 */
    public static Optional<GemData> decode(final String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        final String[] parts = text.split(";", -1);
        if (parts.length < 3) {
            return Optional.empty();
        }
        final Kind kind = Kind.fromKey(parts[0].trim().toLowerCase(Locale.ROOT));
        if (kind == null) {
            return Optional.empty();
        }
        final int level;
        try {
            level = Integer.parseInt(parts[2].trim());
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
        final String id = parts[1].trim();
        return switch (kind) {
            case UNCUT_SKILL -> Optional.of(uncutSkill(level));
            case UNCUT_SUPPORT -> Optional.of(uncutSupport(level));
            case SUPPORT -> id.isEmpty() ? Optional.empty()
                    : Optional.of(support(id, parts.length > 3 && RESONANT_FLAG.equals(parts[3].trim()))); // 2026-10-18：共鳴旗標
            case SKILL -> id.isEmpty() ? Optional.empty()
                    : Optional.of(skill(SkillLoadout.Entry.decode(id, level, parts.length > 3 ? parts[3] : null)));
        };
    }

    /**
     * 未切割寶石當通貨時的代號（2026-10：uncut_skill_等級、uncut_support_階級；currencies.yml 有同名的通貨）。
     * 技能寶石、輔助寶石不是通貨，回傳 null。
     */
    public String currencyId() {
        return switch (kind) {
            case UNCUT_SKILL -> CurrencyType.UNCUT_SKILL_PREFIX + level;
            case UNCUT_SUPPORT -> CurrencyType.UNCUT_SUPPORT_PREFIX + level;
            case SKILL, SUPPORT -> null;
        };
    }

    /** 通貨代號是哪一顆未切割寶石（{@link #currencyId} 反過來）；不是或等級超出範圍回傳空的。 */
    public static Optional<GemData> fromCurrencyId(final String currencyId) {
        if (currencyId == null) {
            return Optional.empty();
        }
        final boolean skill = currencyId.startsWith(CurrencyType.UNCUT_SKILL_PREFIX);
        if (!skill && !currencyId.startsWith(CurrencyType.UNCUT_SUPPORT_PREFIX)) {
            return Optional.empty();
        }
        final String number = currencyId.substring((skill ? CurrencyType.UNCUT_SKILL_PREFIX : CurrencyType.UNCUT_SUPPORT_PREFIX).length());
        final int level;
        try {
            level = Integer.parseInt(number);
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
        if (level < 1 || level > (skill ? SkillLoadout.GEM_CAP : SupportDefinition.MAX_TIER) || !number.equals(Integer.toString(level))) {
            return Optional.empty();
        }
        return Optional.of(skill ? uncutSkill(level) : uncutSupport(level));
    }

    /** 物品的堆疊上限。 */
    public int maxStack() {
        return switch (kind) {
            case UNCUT_SKILL, UNCUT_SUPPORT -> CurrencyType.UNCUT_STACK; // 當通貨時通貨袋拿出來的一疊也是這麼多
            case SUPPORT -> 10;
            case SKILL -> 1;
        };
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
