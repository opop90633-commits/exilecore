package tw.exilecore.character;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一個在線玩家的即時狀態：目前使用的角色、生命／魔力／護盾的目前值、屬性表、攻擊計時、天賦配點、飾品、天賦樹上的珠寶。
 * <p>
 * 登入到資料庫載入完成之間（以及還沒建角色時）{@link #character()} 是 null：
 * 這時用「無角色」的基礎值（等級 1、屬性 20/20/20），也不會存檔。
 */
public final class PlayerState {

    /**
     * 角色面板的裝備欄順序：戒指、戒指、項鍊、腰帶，加上武器、副手、頭盔、胸甲、褲子、鞋子（見 {@link Gear}）。
     * 存檔的格式補空位讀得進舊資料（舊角色後面六格是空的，第一次載入時把手上、身上的搬進來）。
     */
    public static final ItemSlot[] JEWELLERY_SLOTS = {ItemSlot.RING, ItemSlot.RING, ItemSlot.AMULET, ItemSlot.BELT,
            ItemSlot.MAIN_HAND, ItemSlot.OFF_HAND, ItemSlot.HEAD, ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET};
    /** 戰鬥區域裡快捷列第 3–8 格換成技能圖示時，原本那幾格的東西先收在這裡（跟裝備欄一起存）。 */
    public static final int HOTBAR_STASH = JEWELLERY_SLOTS.length;
    /** 護符欄（2026-10）：角色面板裡的虛擬欄位，放在快捷列那 6 格後面（舊存檔讀進來是空的）。 */
    public static final int CHARM_SLOT = HOTBAR_STASH + 6;
    /** 護符欄最多幾格（開幾格看基本格數加「+x 護符欄位」）。 */
    public static final int CHARM_SLOTS = 3;
    /**
     * 第三個戒指欄（2026-10-26 照 PoE2 昇華・展露之指「+1 戒指欄位」；這次之前沒有這一格，平衡等效成戒指的詞綴效果增加 50%）：
     * 跟護符欄一樣是接在後面的虛擬欄位（舊存檔讀進來是空的，前面的位置都沒動）。有 {@link #RING_EXTRA_STAT} 才開。
     */
    public static final int RING3_SLOT = CHARM_SLOT + CHARM_SLOTS;
    /** 存檔裡這一串的總長度：裝備欄 + 收起來的快捷列 6 格 + 護符欄 + 第三個戒指欄。 */
    public static final int STORED_SLOTS = JEWELLERY_SLOTS.length + 6 + CHARM_SLOTS + 1;
    /** 「+1 戒指欄位」（2026-10-26，展露之指）：大於 0 時開第三個戒指欄。 */
    public static final String RING_EXTRA_STAT = "slot.ring_extra";
    /**
     * 角色面板上全部的裝備欄（2026-10-26）：{@link #JEWELLERY_SLOTS} 的 10 格，接著第三個戒指欄。
     * 裝備組、網頁的 gear 陣列、虛擬角色都照這個順序（第 {@link #RING3} 個是第三個戒指；在 jewellery() 的位置見 {@link #gearIndex}）。
     */
    public static final ItemSlot[] GEAR_SLOTS = {ItemSlot.RING, ItemSlot.RING, ItemSlot.AMULET, ItemSlot.BELT,
            ItemSlot.MAIN_HAND, ItemSlot.OFF_HAND, ItemSlot.HEAD, ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET, ItemSlot.RING};
    /** 第三個戒指在 {@link #GEAR_SLOTS} 的順序。 */
    public static final int RING3 = JEWELLERY_SLOTS.length;

    /** {@link #GEAR_SLOTS} 第 i 個在 jewellery() 的位置（前 10 個一樣，第三個戒指是 {@link #RING3_SLOT}）。 */
    public static int gearIndex(final int i) {
        return i < JEWELLERY_SLOTS.length ? i : RING3_SLOT;
    }
    /**
     * 生命偷取每秒最多回最大生命的幾成：2026-10-27 照 PoE2 改成 20%（一份最多 10%、1 秒回完，見 {@link Leech}）；這次之前 10%。
     */
    public static final double LEECH_RATE = Leech.MAX_RATE;

    private final UUID uuid;
    private CharacterRecord character;
    private final Set<String> passives = new HashSet<>();
    private final Map<String, String> progress = new HashMap<>();
    private SkillLoadout skills = new SkillLoadout();
    private final ItemStack[] jewellery = new ItemStack[STORED_SLOTS];
    /** 放在天賦樹珠寶孔裡的珠寶：節點代號 → 珠寶（只有配了的珠寶孔才會有東西，見 passive 模組的 JewelSockets）。 */
    private final Map<String, ItemStack> jewels = new LinkedHashMap<>();
    /** 裝備組（2026-10，月卡福利）：沒在用的組放的裝備（不生效）；正在用的那一組就是 {@link #jewellery}，這裡那一格是空的。 */
    public static final int GEAR_SETS = 5;
    private final ItemStack[][] gearSets = new ItemStack[GEAR_SETS][GEAR_SLOTS.length];
    /** 天賦組（2026-10，月卡福利）：沒在用的組放在珠寶孔裡的珠寶（節點代號 → 珠寶；不生效）。正在用的那一組在 {@link #jewels}。 */
    @SuppressWarnings("unchecked")
    private final Map<String, ItemStack>[] treeJewels = new Map[GEAR_SETS];
    {
        for (int i = 0; i < GEAR_SETS; i++) {
            treeJewels[i] = new LinkedHashMap<>();
        }
    }
    private Bag bag = new Bag();
    private boolean loaded;
    /** 帳號的金幣（PoE2：同帳號的角色共用；存在 accounts.gold，跟著角色存檔一起寫）。 */
    private long gold;
    private double life;
    private double mana;
    private double energyShield;
    /** 還沒回復的生命偷取（2026-10-27 照 PoE2：一份一份的，見 {@link Leech}；這次之前一個池子每秒 10%）。 */
    private final Leech leech = new Leech();
    /** 還沒回復的魔力偷取。 */
    private final Leech manaLeech = new Leech();
    /** 2026-10-26 照 PoE2：受到的傷害之後 4 秒內回來的回復（Recoup；這次之前生命的存進偷取）。 */
    private final Recoup recoup = new Recoup();
    private final StatSheet sheet = new StatSheet();
    private boolean dirty = true;
    /** 很久以前（不能用 Long.MIN_VALUE：「現在 − 它」會溢位成負數）。 */
    private long lastDamageTick = -1_000_000L;
    private long nextAttackTick;
    private boolean debug;
    private Set<String> weaponTags = Set.of();
    private List<String> inactiveEquipment = List.of();
    private long sessionStartMillis = System.currentTimeMillis();
    private String notice;
    private long noticeExpiresTick;
    /** 守護（2026-10，PoE2）：護符觸發時拿到的一層額外血，效果期間先替你吃掉傷害，到期就消失。 */
    private double guard;
    private long guardExpiresTick;
    /** 入場保護的落點（傳送進地圖、傳送到檢查點之後還沒移動）；null 表示沒有保護。見 {@link EntryGrace}。 */
    private Location safeSpot;

    public PlayerState(final UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    /** 目前使用的角色；還沒載入或還沒建角色時為 null。 */
    public CharacterRecord character() {
        return character;
    }

    public boolean hasCharacter() {
        return character != null;
    }

    /** 換角色（或第一次載入）。呼叫端負責背包與飾品。 */
    void setCharacter(final CharacterRecord character, final Set<String> allocated, final Map<String, String> loadedProgress,
                      final SkillLoadout loadedSkills) {
        this.character = character;
        this.passives.clear();
        if (allocated != null) {
            this.passives.addAll(allocated);
        }
        this.progress.clear();
        if (loadedProgress != null) {
            this.progress.putAll(loadedProgress);
        }
        this.skills = loadedSkills == null ? new SkillLoadout() : loadedSkills.copy();
        this.jewels.clear(); // 呼叫端接著放進讀出來的珠寶
        for (final ItemStack[] set : gearSets) {
            java.util.Arrays.fill(set, null); // 呼叫端接著放進讀出來的裝備組
        }
        for (final Map<String, ItemStack> set : treeJewels) {
            set.clear();
        }
        this.bag = new Bag(); // 呼叫端接著放進讀出來的隨身背包
        this.sessionStartMillis = System.currentTimeMillis();
        this.leech.clear();
        this.manaLeech.clear();
        this.dirty = true;
    }

    /** 帳號資料是否已經從資料庫載入（載入前不存檔，避免用空資料蓋掉存檔）。 */
    public boolean isLoaded() {
        return loaded;
    }

    void setLoaded(final boolean loaded) {
        this.loaded = loaded;
    }

    public int level() {
        return character == null ? 1 : character.level();
    }

    public CharacterClass characterClass() {
        return character == null ? null : character.characterClass();
    }

    /** 技能面板（技能與按鍵）。 */
    /** 換成另一組技能（2026-10 技能組）。 */
    public void setSkills(final SkillLoadout loadout) {
        this.skills = loadout == null ? new SkillLoadout() : loadout;
        dirty = true;
    }

    public SkillLoadout skills() {
        return skills;
    }

    /** 已配置的天賦節點（不含起點）。 */
    public Set<String> passives() {
        return passives;
    }

    /** 角色進度（檢查點、Boss 首殺…）；改動請走 CharacterService.setProgress 才會存進資料庫。 */
    public Map<String, String> progress() {
        return progress;
    }

    /** 飾品欄位的物品（可能為 null）。 */
    public ItemStack jewellery(final int index) {
        return jewellery[index];
    }

    public ItemStack[] jewellery() {
        return jewellery;
    }

    public void setJewellery(final int index, final ItemStack stack) {
        jewellery[index] = stack == null || stack.isEmpty() ? null : stack;
        dirty = true;
    }

    /** 第 set 組放著（沒在用）的裝備，照 {@link #GEAR_SLOTS} 的順序（2026-10-26 多了第三個戒指）；正在用的組是空的（裝在 jewellery 裡）。 */
    public ItemStack[] gearSet(final int set) {
        return gearSets[set];
    }

    /** 正在用第幾組（從 0 起；存在角色進度 gearset.active）。 */
    public int activeGearSet() {
        final String value = progress.get(GEAR_SET_KEY);
        try {
            return value == null ? 0 : Math.max(0, Math.min(GEAR_SETS - 1, Integer.parseInt(value)));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public static final String GEAR_SET_KEY = "gearset.active";
    public static final String TREE_SET_KEY = "treeset.active";
    public static final String SKILL_SET_KEY = "skillset.active";

    /** 正在用第幾組技能（從 0 起）。 */
    public int activeSkillSet() {
        final String value = progress.get(SKILL_SET_KEY);
        try {
            return value == null ? 0 : Math.max(0, Math.min(GEAR_SETS - 1, Integer.parseInt(value)));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 第 set 組天賦（沒在用）放在珠寶孔裡的珠寶。 */
    public Map<String, ItemStack> treeJewels(final int set) {
        return treeJewels[set];
    }

    /** 正在用第幾組天賦（從 0 起）。 */
    public int activeTreeSet() {
        final String value = progress.get(TREE_SET_KEY);
        try {
            return value == null ? 0 : Math.max(0, Math.min(GEAR_SETS - 1, Integer.parseInt(value)));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 換成第 set 組：現在裝著的收進原本那一組，那一組的換上來（裝備沒有消失，只是不生效）。 */
    public void switchGearSet(final int from, final int to) {
        for (int i = 0; i < GEAR_SLOTS.length; i++) {
            final int at = gearIndex(i);
            gearSets[from][i] = jewellery[at];
            jewellery[at] = gearSets[to][i];
            gearSets[to][i] = null;
        }
        dirty = true;
    }

    /** 天賦樹珠寶孔裡的珠寶（節點代號 → 珠寶）。改動請走 JewelSockets（它會檢查孔位有沒有配、把拿出來的還給玩家）。 */
    public Map<String, ItemStack> jewels() {
        return jewels;
    }

    /** 這個珠寶孔裡的珠寶；沒有為 null。 */
    public ItemStack jewel(final String nodeId) {
        return jewels.get(nodeId);
    }

    /** 放珠寶進孔位（null 拿掉）。 */
    public void setJewel(final String nodeId, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            jewels.remove(nodeId);
        } else {
            jewels.put(nodeId, stack);
        }
        dirty = true;
    }

    /** 隨身背包（物品 2 頁＋通貨袋）；沒有角色時是一個不會存檔的空背包。 */
    public Bag bag() {
        return bag;
    }

    void setBag(final Bag bag) {
        this.bag = bag == null ? new Bag() : bag;
    }

    /** 帳號的金幣（所有角色共用）。 */
    public long gold() {
        return gold;
    }

    /** 改金幣請走 GoldService（加減、提示）；這裡只設值（不會小於 0）。 */
    public void setGold(final long gold) {
        this.gold = Math.max(0, gold);
    }

    /** 這次登入到現在的秒數（存檔時加進遊玩時間）。 */
    public long sessionSeconds() {
        return Math.max(0, (System.currentTimeMillis() - sessionStartMillis) / 1000);
    }

    void resetSession() {
        sessionStartMillis = System.currentTimeMillis();
    }

    public StatSheet sheet() {
        return sheet;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    void markClean() {
        this.dirty = false;
    }

    public double life() {
        return life;
    }

    public double maxLife() {
        return maxLife(sheet);
    }

    /** 某張屬性表的最大生命（2026-10：角色面板拿「沒有增益」的表來比，看增益有沒有生效）。 */
    public static double maxLife(final StatSheet sheet) {
        if (StatKeys.flag(sheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            return 1;
        }
        // 煉獄使者的貝達特（PoE2）：每一個保留 25% 生命
        final int beidat = (StatKeys.flag(sheet, StatKeys.BEIDAT_HAND) ? 1 : 0) + (StatKeys.flag(sheet, StatKeys.BEIDAT_GAZE) ? 1 : 0)
                + (StatKeys.flag(sheet, StatKeys.BEIDAT_WILL) ? 1 : 0);
        // 2026-10：光環改保留精魂，生命不再被保留
        return Math.max(1, sheet.get(StatKeys.LIFE_MAX) * (1 - 0.25 * beidat));
    }

    public double mana() {
        return mana + manaOverflow;
    }

    /** 2026-10-26 照 PoE2（心靈獻祭）：溢出最大魔力的那部分（最多最大魔力的 50%；先扣這部分，一般回復補不回來）。 */
    private double manaOverflow;

    public double manaOverflow() {
        return manaOverflow;
    }

    /** 2026-10-26 照 PoE2 的溢出：回復 amount 魔力，可以溢出到最大魔力的 {@value #OVERFLOW_CAP} 倍。 */
    public void recoverManaOverflowing(final double amount) {
        if (amount <= 0) {
            return;
        }
        final double max = maxMana();
        final double room = Math.max(0, max - mana);
        mana += Math.min(room, amount);
        final double rest = amount - Math.min(room, amount);
        manaOverflow = Math.max(0, Math.min(max * (OVERFLOW_CAP - 1), manaOverflow + rest));
    }

    /** 溢出的上限（PoE2：最大值的 1.5 倍）。 */
    public static final double OVERFLOW_CAP = 1.5;

    /** 最大魔力：屬性表的最大魔力扣掉光環保留的部分（保留只縮小魔力池，不影響回復速度：照 PoE2 的精神值精神，比較好用）。 */
    public double maxMana() {
        return maxMana(sheet);
    }

    /** 某張屬性表的最大魔力。 */
    public static double maxMana(final StatSheet sheet) {
        if (StatKeys.flag(sheet, StatKeys.FLAG_BLOOD_MAGIC)) {
            return 0; // 血魔法：沒有魔力
        }
        final double mana = Math.max(0, sheet.get(StatKeys.MANA_MAX)); // 2026-10：光環改保留精魂，魔力只給技能用
        // 靈能電池：能量護盾全部變成最大魔力（2026-10-28 照 PoE2 吉爾菲的聖殿：「最大能量護盾無法被轉換」的不換）
        return StatKeys.flag(sheet, StatKeys.FLAG_ELDRITCH_BATTERY) && esEqualToStr(sheet) <= 0
                ? mana + Math.max(0, sheet.get(StatKeys.ES_MAX)) : mana;
    }

    /** 被光環保留的最大魔力比例：2026-10 改成精魂之後一律 0（留著給舊的呼叫）。 */
    public double reservedFraction() {
        return 0;
    }

    /** 最大精魂（2026-10）。 */
    public double maxSpirit() {
        return Math.max(0, sheet.get(StatKeys.SPIRIT_MAX));
    }

    /** 光環保留的精魂。 */
    public double reservedSpirit() {
        return Math.max(0, sheet.get(StatKeys.SPIRIT_RESERVED));
    }

    public double energyShield() {
        return energyShield;
    }

    public double maxEnergyShield() {
        return maxEnergyShield(sheet);
    }

    /** 某張屬性表的最大能量護盾。 */
    public static double maxEnergyShield(final StatSheet sheet) {
        // 2026-10-28 照 PoE2（吉爾菲的聖殿）：最大能量護盾等於力量的 x%（其他的護盾加成都不算），而且不能被轉換（靈能電池不換）
        final double fromStr = esEqualToStr(sheet);
        if (fromStr > 0) {
            return Math.floor(Math.max(0, sheet.get(StatKeys.STR)) * fromStr / 100.0);
        }
        if (StatKeys.flag(sheet, StatKeys.FLAG_ELDRITCH_BATTERY)) {
            return 0;
        }
        return Math.max(0, sheet.get(StatKeys.ES_MAX));
    }

    /** 2026-10-28：「最大能量護盾等於力量的 x%」的 x（沒有回傳 0）。 */
    static double esEqualToStr(final StatSheet sheet) {
        return sheet.flat(tw.exilecore.item.unique.UniqueStats.ES_EQUAL_TO_STR, java.util.Set.of());
    }

    public void setLife(final double value) {
        this.life = Math.max(0, Math.min(maxLife(), value));
    }

    public void setMana(final double value) {
        // 2026-10-26：有溢出的魔力時先扣溢出的部分（setMana 不會增加溢出）
        final double max = maxMana();
        manaOverflow = Math.max(0, Math.min(manaOverflow, value - max));
        this.mana = Math.max(0, Math.min(max, value - manaOverflow));
    }

    public void setEnergyShield(final double value) {
        this.energyShield = Math.max(0, Math.min(maxEnergyShield(), value));
    }

    /** 全部回滿（登入、重生）。 */
    public void restoreAll() {
        life = maxLife();
        mana = maxMana();
        energyShield = maxEnergyShield();
        leech.clear();
        manaLeech.clear();
        recoup.clear();
    }

    /** 2026-10-26：還沒回來的回復（生命、魔力、能量護盾）。 */
    public Recoup recoup() {
        return recoup;
    }

    /**
     * 不是擊中來的「存進偷取慢慢回」（昇華、傳奇的補償）：每秒回最大生命的 10%，沒有一份的上限。
     * 擊中的偷取用 {@link #addHitLeech}（2026-10-27 分開；這次之前都用這個）。
     */
    public void addLeech(final double amount) {
        if (amount > 0 && !isDead()) {
            leech.addSlow(amount, maxLife());
        }
    }

    /** 2026-10-27 照 PoE2：擊中偷到的生命：一份最多最大生命的 10%，1 秒內回完（{@link Leech}）。 */
    public void addHitLeech(final double amount) {
        if (amount > 0 && !isDead()) {
            leech.addHit(amount, maxLife());
        }
    }

    /**
     * 回復一段時間的生命偷取；生命滿了剩下的偷取就結束（PoE 規則）。
     *
     * @return 實際回了多少
     */
    public double recoverLeech(final double seconds) {
        return recoverLeech(seconds, 1.0);
    }

    /** rateScale：最大偷取速度的倍率（「增加 x% 最大偷取速度」）。 */
    public double recoverLeech(final double seconds, final double rateScale) {
        final double amount = leech.tick(seconds, maxLife(), rateScale); // 2026-10-27 照 PoE2（Leech）
        if (amount <= 0) {
            return 0;
        }
        final double before = life;
        setLife(life + amount);
        if (life >= maxLife() - 1e-6) {
            leech.clear();
        }
        return life - before;
    }

    /** 不是擊中來的魔力「慢慢回」：每秒回最大魔力的 10%（擊中的用 {@link #addHitManaLeech}）。 */
    public void addManaLeech(final double amount) {
        if (amount > 0 && !isDead()) {
            manaLeech.addSlow(amount, maxMana());
        }
    }

    /** 2026-10-27 照 PoE2：擊中偷到的魔力：一份最多最大魔力的 10%，1 秒內回完。 */
    public void addHitManaLeech(final double amount) {
        if (amount > 0 && !isDead()) {
            manaLeech.addHit(amount, maxMana());
        }
    }

    /** 回復一段時間的魔力偷取（跟生命偷取一樣：每秒最多回最大魔力的 20%，魔力滿了剩下的就結束）；回傳實際回了多少。 */
    public double recoverManaLeech(final double seconds) {
        return recoverManaLeech(seconds, 1.0);
    }

    public double recoverManaLeech(final double seconds, final double rateScale) {
        final double amount = manaLeech.tick(seconds, maxMana(), rateScale);
        if (amount <= 0) {
            return 0;
        }
        final double before = mana;
        setMana(mana + amount);
        if (mana >= maxMana() - 1e-6) {
            manaLeech.clear();
        }
        return mana - before;
    }

    /** 還沒回復的魔力偷取（測試與除錯看）。 */
    public double manaLeechPool() {
        return manaLeech.pending();
    }

    /** 2026-10-26：還沒回復的生命偷取（天賦樹的「偷取中」條件）。 */
    public double leechPool() {
        return leech.pending();
    }

    /** 重算後把目前值夾在新上限內。 */
    public void clampToMax() {
        life = Math.min(life, maxLife());
        mana = Math.min(mana, maxMana());
        manaOverflow = Math.max(0, Math.min(manaOverflow, maxMana() * (OVERFLOW_CAP - 1)));
        energyShield = Math.min(energyShield, maxEnergyShield());
    }

    public boolean isDead() {
        return life <= 0;
    }

    /**
     * 2026-10：「最近…」的事件（PoE2 的 recently = 4 秒內）：事件名 → 上一次發生的 tick。
     * 裝備、珠寶的條件詞綴（屬性代號後面接 {@code @事件} 或 {@code @no事件}，例如 {@code evasion@nohit}、{@code damage@projhit}）看這個。
     * 事件名見 {@link tw.exilecore.stats.Recently}。
     */
    private final Map<String, Long> recent = new HashMap<>();
    /** 上一次算屬性表時各條件的結果（條件 → 成立與否）；空的表示屬性表裡沒有條件詞綴。 */
    private Map<String, Boolean> conditionsEvaluated = Map.of();

    /** 記下事件剛發生（「最近…」的條件）。 */
    public void markRecent(final String event, final long tick) {
        recent.put(event, tick);
    }

    /** 這個事件最近（4 秒內）發生過嗎。 */
    public boolean recently(final String event, final long currentTick) {
        final Long tick = recent.get(event);
        return tick != null && currentTick - tick <= tw.exilecore.stats.StatKeys.RECENTLY_TICKS;
    }

    /** 2026-10-26：這個事件上一次發生的 tick（沒發生過是很久以前；天賦樹的「引導中」看這個）。 */
    public long recentTick(final String event) {
        return recent.getOrDefault(event, -1_000_000L);
    }

    /** 上一次被擊中的 tick（舊介面：等同事件 hit）。 */
    public long lastHitTick() {
        return recent.getOrDefault(tw.exilecore.stats.Recently.HIT, -1_000_000L);
    }

    public void setLastHitTick(final long tick) {
        markRecent(tw.exilecore.stats.Recently.HIT, tick);
    }

    /** 屬性表裡有沒有條件詞綴。 */
    public boolean hitConditions() {
        return !conditionsEvaluated.isEmpty();
    }

    public Map<String, Boolean> conditionsEvaluated() {
        return conditionsEvaluated;
    }

    public void setConditionsEvaluated(final Map<String, Boolean> evaluated) {
        this.conditionsEvaluated = evaluated.isEmpty() ? Map.of() : Map.copyOf(evaluated);
    }

    public long lastDamageTick() {
        return lastDamageTick;
    }

    /** 荒蕪（2026-10，賽勒斯的「黑水」）：到這個 tick 之前生命回復、偷取、護盾回充全部停掉（藥劑照喝）。不存檔。 */
    private long ruinUntilTick = -1;

    public boolean ruined(final long currentTick) {
        return currentTick < ruinUntilTick;
    }

    public long ruinUntilTick() {
        return ruinUntilTick;
    }

    /** 踩到荒蕪：禁療到這個 tick（只會往後延，不會縮短）。 */
    public void ruin(final long untilTick) {
        this.ruinUntilTick = Math.max(this.ruinUntilTick, untilTick);
    }

    public void clearRuin() {
        this.ruinUntilTick = -1;
    }

    public void setLastDamageTick(final long tick) {
        this.lastDamageTick = tick;
    }

    public long nextAttackTick() {
        return nextAttackTick;
    }

    public void setNextAttackTick(final long tick) {
        this.nextAttackTick = tick;
    }

    public boolean debug() {
        return debug;
    }

    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    /** 目前主手武器的種類標籤（徒手為空），重算時更新。 */
    public Set<String> weaponTags() {
        return weaponTags;
    }

    void setWeaponTags(final Set<String> tags) {
        this.weaponTags = Set.copyOf(tags);
    }

    /** 2026-10-27 照 PoE2 雙持（巨人之血）：副手武器出手要用的東西（沒有雙持是 null），重算時更新。 */
    private DualWield.OffHand offHandAttack;
    /** 下一次攻擊輪到副手。 */
    private boolean offHandNext;

    public DualWield.OffHand offHandAttack() {
        return offHandAttack;
    }

    void setOffHandAttack(final DualWield.OffHand offHand) {
        this.offHandAttack = offHand;
    }

    /** 雙持輪流出手：回傳這一下是不是副手（每呼叫一次換一手）。 */
    public boolean nextHandIsOff() {
        final boolean off = offHandNext;
        offHandNext = !offHandNext;
        return off;
    }

    /** 2026-10-27：下一下是不是副手（只看，不換手）；真的出手（付了消耗）才 {@link #flipHand()}。 */
    public boolean peekHandIsOff() {
        return offHandNext;
    }

    /**
     * 2026-10-27：真的出手了，換另一手。這次之前一按就換手（冷卻中、沒魔力、太遠、按住蓄力都會換），
     * 失敗一次就能一直用比較強的那一手。
     */
    public void flipHand() {
        offHandNext = !offHandNext;
    }

    /** 需求不符、沒有生效的裝備說明（重算時更新）。 */
    public List<String> inactiveEquipment() {
        return inactiveEquipment;
    }

    void setInactiveEquipment(final List<String> lines) {
        this.inactiveEquipment = List.copyOf(lines);
    }

    /** 動作列上短暫顯示的提示（撿到金幣之類），到期自動消失。 */
    public void notice(final String miniMessage, final long expiresTick) {
        this.notice = miniMessage;
        this.noticeExpiresTick = expiresTick;
    }

    public String notice(final long currentTick) {
        if (notice != null && currentTick > noticeExpiresTick) {
            notice = null;
        }
        return notice;
    }

    /** 給一層守護：amount 點、到 expiresTick 為止（已經有的話取比較多的那個、比較晚的到期）。 */
    public void addGuard(final double amount, final long expiresTick) {
        if (amount <= 0) {
            return;
        }
        this.guard = Math.max(guard(expiresTick - 1), amount);
        this.guardExpiresTick = Math.max(guardExpiresTick, expiresTick);
    }

    /** 現在還剩多少守護（到期是 0）。 */
    public double guard(final long currentTick) {
        if (guard > 0 && currentTick > guardExpiresTick) {
            guard = 0;
        }
        return guard;
    }

    /** 守護吃掉傷害：回傳吃掉多少。 */
    public double absorbGuard(final double damage, final long currentTick) {
        final double available = guard(currentTick);
        if (available <= 0 || damage <= 0) {
            return 0;
        }
        final double absorbed = Math.min(available, damage);
        guard = available - absorbed;
        return absorbed;
    }

    /** 入場保護中：傳送進來還沒移動，不會受傷、怪物不會盯上。 */
    public boolean isSafe() {
        return safeSpot != null;
    }

    /** 入場保護的落點（沒有保護時 null）。 */
    public Location safeSpot() {
        return safeSpot;
    }

    /** 給或收掉入場保護（null 收掉）。請走 {@link EntryGrace}，它會順便處理提示。 */
    public void setSafeSpot(final Location spot) {
        this.safeSpot = spot == null ? null : spot.clone();
    }

    /** 屬性值的簡寫。 */
    public double strength() {
        return sheet.get(StatKeys.STR);
    }

    public double dexterity() {
        return sheet.get(StatKeys.DEX);
    }

    public double intelligence() {
        return sheet.get(StatKeys.INT);
    }
}
