package tw.exilecore.character;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 玩家狀態的管理：重算屬性表、經驗與升級、把我們的生命／魔力／護盾投影到原版的血條／經驗條／吸收血條。
 * 角色的載入與存檔在 {@link CharacterService}。
 */
public final class PlayerStateService {

    /** 原版最大血量固定 40（20 顆心），血條只是比例顯示。 */
    public static final double VANILLA_MAX_HEALTH = 40.0;
    /** 護盾受傷後幾 tick 才開始回充（基礎值；詞綴可以縮短，見 {@link Formulas#esRechargeDelayTicks}）。 */
    public static final long ES_RECHARGE_DELAY_TICKS = Formulas.ES_RECHARGE_DELAY_TICKS;

    private final ExileCore plugin;
    private final CharacterConfig config;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final NamespacedKey movementKey;
    private final EntryGrace grace;
    /** 戰鬥資源血條（2026-10）：怒氣、連擊、戰星、灌注從動作列搬到畫面上方的一欄。 */
    private final ResourceBar resourceBar;
    /** 2026-10-18：收掉所有人的戰鬥資源血條（插件停用時）。 */
    void hideResourceBars() {
        resourceBar.hideAll();
    }

    /**
     * 2026-10-27：下線、換角色時清掉跟著「這一次上線的這個角色」的暫存：條件取樣（變身中、事件計數）與被怪物破掉的護甲。
     * 這次之前都沒清：重新登入時拿舊的「變身中」取樣當作剛變回人形（變身時間算到下線那麼久，白拿變回人形的增益），
     * 事件計數與破甲也帶到下一個角色。
     */
    public void forgetTransient(final UUID player) {
        conditionTracker.forget(player);
        tw.exilecore.combat.PlayerArmourBreak.clear(player);
    }

    /** 撿到東西的角落提示（2026-10）。 */
    private final LootFeed lootFeed = new LootFeed();
    /** 2026-10-18：畫面左上角的隊伍框（接在動作列後面）。 */
    private final tw.exilecore.party.PartyHud partyHud;
    /** 以前的常駐奔跑的移動速度加成（拿掉了；身上還有就清掉）。 */
    private final NamespacedKey oldRunKey;

    /** 其他模組給的修飾器（地圖詞綴：在地圖裡的玩家）。 */
    private final List<Function<Player, List<Modifier>>> modifierSources = new ArrayList<>();
    /** 其中哪些是看場合的（{@link #addSituationalSource}：地圖詞綴）。 */
    private final java.util.Set<Function<Player, List<Modifier>>> situationalSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** 其中哪些是暫時的增益（{@link #addBuffSource}）。 */
    private final java.util.Set<Function<Player, List<Modifier>>> buffSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final List<java.util.function.BiFunction<Player, PlayerState, List<Modifier>>> stateModifierSources = new ArrayList<>();
    private final List<java.util.function.ToIntFunction<PlayerState>> pointSources = new ArrayList<>();

    /** 註冊一個額外修飾器的來源（重算屬性表時呼叫）。 */
    public void addModifierSource(final Function<Player, List<Modifier>> source) {
        modifierSources.add(source);
    }

    /**
     * 註冊一個「暫時的增益」來源（光環、戰吼、球、疾速、圖騰、魔像、護符……）：跟 {@link #addModifierSource} 一樣進屬性表，
     * 另外角色面板會拿「沒有這些增益」的表（{@link #baselineSheet}）來比，列出增益讓哪些數值變了（2026-10）。
     */
    public void addBuffSource(final Function<Player, List<Modifier>> source) {
        modifierSources.add(source);
        buffSources.add(source);
    }

    /**
     * 註冊一個看場合的來源（地圖詞綴：在地圖裡才有）：跟 {@link #addModifierSource} 一樣進屬性表，
     * 只是網頁計算器的快照（{@link #persistentExtras}）不記它（2026-10）。
     */
    public void addSituationalSource(final Function<Player, List<Modifier>> source) {
        modifierSources.add(source);
        situationalSources.add(source);
    }

    /**
     * 註冊一個要看角色狀態（進度）的修飾器來源，例如任務的永久獎勵。
     * 網頁計算器的虛擬角色也會呼叫（player 是 null）：要看玩家本人的部分自己略過。
     * 拿到的是正在重算的那個狀態：不要在裡面呼叫 {@link #get}（新玩家的狀態還沒放進去）。
     */
    public void addStateModifierSource(final java.util.function.BiFunction<Player, PlayerState, List<Modifier>> source) {
        stateModifierSources.add(source);
    }

    /** 註冊一個額外天賦點的來源（例如任務獎勵的天賦點；從進度算出來，不另外存）。 */
    public void addPointSource(final java.util.function.ToIntFunction<PlayerState> source) {
        pointSources.add(source);
    }

    public PlayerStateService(final ExileCore plugin, final CharacterConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.movementKey = new NamespacedKey(plugin, "movement_speed");
        this.oldRunKey = new NamespacedKey(plugin, "always_run");
        this.grace = new EntryGrace(plugin, this);
        this.resourceBar = new ResourceBar(plugin);
        this.partyHud = new tw.exilecore.party.PartyHud(plugin);
        this.conditionTracker = new ConditionTracker(plugin);
    }

    /** 2026-10-26：天賦樹條件（靜止、被包圍、低血…）的狀態；變身、夥伴系統在這裡接上條件。 */
    private final ConditionTracker conditionTracker;

    public ConditionTracker conditions() {
        return conditionTracker;
    }

    public CharacterConfig config() {
        return config;
    }

    /** 入場保護（傳送進來還沒移動前無敵）。 */
    public EntryGrace grace() {
        return grace;
    }

    public PlayerState get(final Player player) {
        return states.computeIfAbsent(player.getUniqueId(), uuid -> {
            final PlayerState state = new PlayerState(uuid);
            rebuild(player, state);
            state.restoreAll();
            return state;
        });
    }

    /** 2026-10-22：這個玩家開了「隱藏怪物對話」（Boss 的台詞不送給他）。 */
    /** 2026-10-25：開了「過濾系統訊息」（PlayerSettings.HIDE_SYSTEM）。 */
    public boolean hidesSystem(final Player player) {
        return PlayerSettings.on(states.get(player.getUniqueId()), PlayerSettings.HIDE_SYSTEM);
    }

    public boolean hidesMonsterTalk(final Player player) {
        return PlayerSettings.on(states.get(player.getUniqueId()), PlayerSettings.HIDE_MONSTER_TALK);
    }

    public Optional<PlayerState> find(final UUID uuid) {
        return Optional.ofNullable(states.get(uuid));
    }

    public void remove(final Player player) {
        states.remove(player.getUniqueId());
        forgetTransient(player.getUniqueId());
        parsedGear.remove(player.getUniqueId());
        comparerSignature.remove(player.getUniqueId());
        conditionHold.forget(player.getUniqueId());
        resourceBar.hide(player);
        lootFeed.clear(player.getUniqueId());
    }

    /** 撿到東西的角落提示（2026-10）：掉落模組加進來，動作列畫在快捷欄右邊。 */
    public LootFeed lootFeed() {
        return lootFeed;
    }

    /** 屬性表有變動（換裝、升級、配點）時呼叫；實際重算延後到下次需要時。 */
    public void markDirty(final Player player) {
        find(player.getUniqueId()).ifPresent(PlayerState::markDirty);
    }

    /**
     * 2026-10-27：正在重算屬性表的玩家。重算途中又來要同一個人的屬性表（例如昇華「不潔之力」看隊友的屬性表、
     * 隊友那邊又回頭看這個人）就直接給目前的那張，不再重算——這次之前會無限遞迴，StackOverflowError。
     */
    private final java.util.Set<java.util.UUID> rebuilding = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 取得最新的屬性表（髒了就重算；這個人正在重算的話給目前的那張）。 */
    public StatSheet sheet(final Player player) {
        final PlayerState state = get(player);
        if (state.isDirty() && !rebuilding.contains(player.getUniqueId())) {
            rebuild(player, state);
        }
        return state.sheet();
    }

    /**
     * 重算屬性表（設計文件「修飾器來源與快取」）：
     * 職業與等級基礎值 → 天賦（含珠寶）與其他來源（地圖詞綴、任務、昇華）→ 裝備修飾器（需求不符的裝備不算）→ 屬性值帶來的加成 → 基石的特殊效果。
     */
    /** 權杖給的精魂（PoE2：每種權杖都是 100）。 */
    public static final double SCEPTRE_SPIRIT = 100;
    /** 打倒劇情 Boss 拿到的精魂（2026-10，照 PoE2 的 30／30／40）：區域代號 → 精魂。 */
    public static final java.util.Map<String, Double> QUEST_SPIRIT = java.util.Map.of("forest", 30.0, "swamp", 30.0, "mountain", 40.0);

    /** 這個角色從劇情 Boss 拿到的精魂。 */
    public static double questSpirit(final PlayerState state) {
        double total = 0;
        for (final java.util.Map.Entry<String, Double> entry : QUEST_SPIRIT.entrySet()) {
            if (state.progress().containsKey("boss." + entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public void rebuild(final Player player, final PlayerState state) {
        // 2026-10-27 效能：整張屬性表重算計時（/ec perf 的 stats.rebuild；單次太慢會寫【卡頓】警告）
        final long start = System.nanoTime();
        final boolean outer = rebuilding.add(player.getUniqueId()); // 2026-10-27：防止重算途中遞迴回來
        try {
            build(player, state, state.sheet(), true, null);
        } finally {
            if (outer) {
                rebuilding.remove(player.getUniqueId());
            }
            if (plugin.getServer().isPrimaryThread()) { // Perf 只在主執行緒用
                plugin.perf().record("stats.rebuild", System.nanoTime() - start);
            }
        }
    }

    /**
     * 網頁計算器（2026-10）的虛擬角色要的東西：不在線、沒有真的物品，裝備與珠寶直接給物品資料。
     *
     * @param gear   照 {@link PlayerState#GEAR_SLOTS} 的順序（武器、防具、飾品、第三個戒指；空的是 null）
     * @param jewels 天賦樹珠寶孔：節點代號 → 珠寶
     * @param extra  要看玩家本人才算得出來的永久加成（稱號、公會、贊助、分身獎勵），快照時記下來的
     */
    public record VirtualInputs(List<ItemData> gear, Map<String, ItemData> jewels, List<Modifier> extra) {
        public VirtualInputs {
            gear = gear == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(gear));
            jewels = jewels == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(jewels));
            extra = extra == null ? List.of() : List.copyOf(extra);
        }

        /** 同樣的裝備與珠寶，多加幾條修飾器（光環的效果）。 */
        public VirtualInputs plus(final List<Modifier> more) {
            final List<Modifier> all = new ArrayList<>(extra);
            all.addAll(more);
            return new VirtualInputs(gear, jewels, all);
        }
    }

    /** 虛擬角色算出來的屬性表，與需求不符、不生效的裝備（「頭盔：力量不足（需要 40，你有 32）」）。 */
    public record VirtualSheet(StatSheet sheet, List<String> inactive) {
    }

    /**
     * 虛擬角色的屬性表（2026-10，網頁計算器）：跟玩家身上那張同一套算法，只是不算暫時增益（光環另外算，見
     * {@link tw.exilecore.skill.SkillService#virtualAuras}）與有條件的詞綴（「最近…」），也不碰任何玩家的狀態。
     * 會把武器的標籤寫進 state（技能看得到用什麼武器）。
     */
    public VirtualSheet virtualSheet(final PlayerState state, final VirtualInputs inputs) {
        final StatSheet sheet = new StatSheet();
        final List<String> inactive = new ArrayList<>();
        build(null, state, sheet, false, inputs, inactive);
        return new VirtualSheet(sheet, List.copyOf(inactive));
    }

    /**
     * 要看玩家本人的永久加成（稱號、公會、贊助、分身獎勵…）：網頁計算器的快照記下來，虛擬角色照樣加上。
     * 不含暫時的增益、地圖詞綴這種看場合的，也不含天賦、裝備、任務、昇華天賦（那些虛擬角色自己算）。
     */
    public List<Modifier> persistentExtras(final Player player) {
        final List<Modifier> list = new ArrayList<>();
        for (final Function<Player, List<Modifier>> source : modifierSources) {
            if (!buffSources.contains(source) && !situationalSources.contains(source)) {
                list.addAll(source.apply(player));
            }
        }
        if (plugin.ascendancy() != null) {
            list.addAll(plugin.ascendancy().legacyModifiers(player, get(player)));
        }
        return list;
    }

    /**
     * 沒有暫時增益的屬性表（2026-10，角色面板比對用）：不算 {@link #addBuffSource} 的來源，也不算有條件的詞綴
     * （「最近被擊中」「藥劑效果中」這種，條件成立才有的也算增益）。每次呼叫都新算一張，不影響玩家身上的狀態。
     */
    public StatSheet baselineSheet(final Player player) {
        final PlayerState state = get(player);
        if (state.isDirty()) {
            rebuild(player, state);
        }
        final StatSheet baseline = new StatSheet();
        final long start = System.nanoTime();
        build(player, state, baseline, false, null);
        if (plugin.getServer().isPrimaryThread()) {
            plugin.perf().record("stats.baseline", System.nanoTime() - start); // 2026-10-27：整張重算，計時給 /ec perf 看
        }
        return baseline;
    }

    /**
     * @param live    true＝玩家身上那張表（全部算、更新狀態）；false＝比對用的表（不算暫時增益、不改任何狀態）
     * @param virtual 不是 null＝網頁計算器的虛擬角色（player 是 null、live 是 false）：裝備與珠寶從這裡拿，要看玩家的來源用快照的
     */
    private void build(final Player player, final PlayerState state, final StatSheet sheet, final boolean live, final VirtualInputs virtual) {
        build(player, state, sheet, live, virtual, null);
    }

    /** 2026-10-26 照 PoE2：徒手的最大物理傷害（戰士 8、傭兵與德魯伊 6、其他 5）。 */
    static double unarmedMax(final CharacterClass characterClass) {
        if (characterClass == null) {
            return 5;
        }
        return switch (characterClass) {
            case MARAUDER -> 8;
            case MERCENARY, DRUID -> 6;
            default -> 5;
        };
    }

    /** @param inactiveSink 不是 null：需求不符的裝備寫進這裡（虛擬角色用） */
    private void build(final Player player, final PlayerState state, final StatSheet sheet, final boolean live, final VirtualInputs virtual,
                       final List<String> inactiveSink) {
        sheet.clearModifiers();
        final int level = state.level();
        final CharacterClass characterClass = state.characterClass();

        // 基礎值：職業起始屬性（沒角色時 20/20/20）
        sheet.setBase(StatKeys.STR, characterClass == null ? 20 : characterClass.strength());
        sheet.setBase(StatKeys.DEX, characterClass == null ? 20 : characterClass.dexterity());
        sheet.setBase(StatKeys.INT, characterClass == null ? 20 : characterClass.intelligence());
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        sheet.setBase(StatKeys.CRIT_MULTI, 200); // 2026-10：PoE2 暴擊傷害加成 +100%
        sheet.setBase(StatKeys.LIFE_REGEN, 0);

        // 天賦（含天賦樹珠寶孔裡的珠寶）：屬性點也算進裝備需求
        final PassiveTree tree = plugin.passives().tree();
        // 2026-10-26 照 PoE2：永恆珠寶征服的節點換成征服後的效果（TimelessJewels）；這次之前直接用 tree.modifiers
        final List<Modifier> passiveModifiers = attributeChoices(state, characterClass,
                tree == null ? List.of() : tw.exilecore.passive.TimelessJewels.treeModifiers(plugin.items() == null ? null : plugin.items().repository(),
                        virtual != null ? virtual.jewels() : tw.exilecore.passive.JewelSockets.jewelData(plugin, state), tree, state.passives(),
                        characterClass == null ? null : characterClass.id()), tree);
        passiveModifiers.addAll(virtual != null
                ? tw.exilecore.passive.JewelSockets.modifiers(plugin.items() == null ? null : plugin.items().repository(), virtual.jewels(), state.passives(), tree)
                : tw.exilecore.passive.JewelSockets.modifiers(plugin, state, tree));
        // 2026-10：半徑珠寶放大的「+x 任一屬性」：照那一點選的屬性換成力量／敏捷／智慧
        // 2026-10-26 照 PoE2 旅者的智慧：選了傷害／防禦／消耗效率的換成那些（choiceModifiers）
        final List<Modifier> amplified = new ArrayList<>();
        passiveModifiers.removeIf(mod -> {
            if (!mod.stat().startsWith(StatKeys.ATTR_CHOICE + "@")) {
                return false;
            }
            amplified.addAll(choiceModifiers(chosenAttribute(state, characterClass, mod.stat().substring(StatKeys.ATTR_CHOICE.length() + 1)),
                    mod.value(), mod.tags(), mod.source()));
            return true;
        });
        passiveModifiers.addAll(amplified);
        // 2026-10：「最近…」的條件詞綴（珠寶、裝備）：條件成立才算；記下算過哪些條件，變了就重算（tick）
        final Map<String, Boolean> conditions = new java.util.HashMap<>();
        final long now = plugin.getServer().getCurrentTick();
        final List<Modifier> passiveResolved = new ArrayList<>();
        for (final Modifier modifier : passiveModifiers) {
            final Modifier resolved = live ? resolveCondition(player, state, modifier, now, conditions) : withoutCondition(modifier);
            if (resolved != null) {
                passiveResolved.add(resolved);
            }
        }
        sheet.addAll(passiveResolved);
        // 其他來源（地圖詞綴、任務獎勵、昇華天賦、分身獎勵）：給的屬性點也算進裝備需求，所以兩輪都加
        // 2026-10-27：暫時增益的來源（addBuffSource）延後到裝備第一輪之後才呼叫——它們讀 state.sheet()（就是這張正在算的表）
        //   看昇華天賦、裝備的旗標（意志之力、靈能導體、衝刺突擊、最近消耗過銳星、夏烏拉之炎的效果、裝備給的不潔之力…）；
        //   這次之前排在昇華（state 來源）與裝備之前，看到的表只有天賦樹，那幾個昇華效果完全沒生效
        final List<Modifier> extra = new ArrayList<>();
        final List<Function<Player, List<Modifier>>> deferredBuffs = new ArrayList<>();
        if (virtual != null) {
            extra.addAll(virtual.extra()); // 虛擬角色：要看玩家的來源用快照記下來的
        } else {
            for (final Function<Player, List<Modifier>> source : modifierSources) {
                if (buffSources.contains(source)) {
                    if (live) {
                        deferredBuffs.add(source);
                    }
                } else {
                    extra.addAll(source.apply(player));
                }
            }
        }
        for (final java.util.function.BiFunction<Player, PlayerState, List<Modifier>> source : stateModifierSources) {
            extra.addAll(source.apply(player, state));
        }
        sheet.addAll(extra);

        // 第一輪：所有裝備的修飾器，用來算屬性值
        // 2026-10-26（昇華・展露之指）：有「+1 戒指欄位」才算第三個戒指欄（只看裝備以外的來源，不會繞回來）
        final boolean thirdRing = ringExtra(passiveResolved) + ringExtra(extra) > 0;
        final List<EquippedItem> equipped = virtual != null ? equippedItems(state, virtual.gear(), thirdRing) : equippedItems(player, state, thirdRing);
        for (final EquippedItem item : equipped) {
            sheet.addAll(item.modifiers());
        }
        // 2026-10-27：暫時增益在這裡才算（表上已經有天賦樹、昇華、其他來源、裝備）；給的屬性值照樣算進裝備需求（第二輪也照樣加）
        final List<Modifier> buffModifiers = new ArrayList<>(collectBuffs(deferredBuffs, player));
        if (virtual != null) {
            // 2026-10-27：虛擬角色（網頁規劃器）不呼叫暫時增益的來源；昇華增益裡只看屬性表的（凱莎的平衡、不潔之力、自然秩序）照樣算，這次之前漏掉
            buffModifiers.addAll(tw.exilecore.ascendancy.AscendancyEffects.virtualBuffs(sheet));
        }
        sheet.addAll(buffModifiers);
        extra.addAll(buffModifiers);
        final double str = sheet.get(StatKeys.STR);
        final double dex = sheet.get(StatKeys.DEX);
        final double intelligence = sheet.get(StatKeys.INT);
        final double requirementScale = tw.exilecore.stats.TreeEffectKeys.requirementScale(sheet); // 2026-10-26 照 PoE2 天賦樹：減少屬性需求

        // 第二輪：剔除需求不符的裝備
        sheet.clearModifiers();
        sheet.addAll(passiveResolved);
        boolean hasWeapon = false;
        Set<String> weaponTags = Set.of();
        final List<String> inactive = new ArrayList<>();
        final List<EquippedItem> active = new ArrayList<>();
        // 2026-10-26（計數之戒，PoE2 Thief's Torment）：戴著「不能使用其他戒指」的戒指時，另一隻戒指不生效
        final EquippedItem soloRing = equipped.stream().filter(item -> item.base().slot() == tw.exilecore.item.ItemSlot.RING
                && tw.exilecore.item.unique.UniqueDerived.onlyRing(item.modifiers())).findFirst().orElse(null);
        // 2026-10-26（昇華・嵌晶）：減少能力值需求、應變能力（最高的屬性滿足需求）
        final double[] reqAttr = tw.exilecore.ascendancy.AscBuild.requirementAttributes(extra, str, dex, intelligence);
        // 2026-10-26 照 PoE2 基石：巨人之血（武鬥武器屬性需求三倍）、靈能魔符（項鍊的加成改給召喚物）
        final boolean giantsBlood = StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_GIANTS_BLOOD);
        final boolean necroTalisman = StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_NECRO_TALISMAN);
        // 2026-10-27 照 PoE2 基石：巨人之血（雙手斧錘劍單手持用，副手可以配盾、法器或再一把雙手斧錘劍雙持）、荒野領主（副手的權杖）——天賦換了也要重看副手
        final java.util.function.Function<EquippedItem, String> ownProblem = item -> {
            final ItemBase.Requirements req = scaledRequirements(item.requirements(), requirementScale);
            final double reqScale = giantsBlood && item.base().isWeapon() && tw.exilecore.stats.KeystoneKeys.martial(item.base().tags()) ? 3 : 1;
            // 2026-10-27 照 PoE2（昇華・石拳之道）：腿甲不需要能力值
            final double[] attrs = tw.exilecore.ascendancy.AscBuild.ignoresRequirements(extra, item.base().slot())
                    ? tw.exilecore.ascendancy.AscBuild.NO_REQUIREMENT : reqAttr;
            final String why = soloRing != null && item != soloRing && item.base().slot() == tw.exilecore.item.ItemSlot.RING
                    ? "不能跟另一隻戒指一起用" : requirementProblem(reqScale == 1 ? req
                    : new ItemBase.Requirements(req.level(), req.str() * 3, req.dex() * 3, req.intelligence() * 3), level, attrs[0], attrs[1], attrs[2]);
            return why != null ? why : tw.exilecore.ascendancy.AscBuild.itemProblem(extra, item.base().slot(), item.data()); // 2026-10-26（昇華・鐵匠傑作）
        };
        // 2026-10-27：副手看的是「生效的」主手：主手需求不符時當作沒拿（副手的雙手武器、權杖跟著不生效）；
        //   這次之前看裝著的主手，主手不生效時副手的雙手武器還是照算全域詞綴
        final EquippedItem mainItem = equipped.stream().filter(item -> !item.offHandWeapon() && item.base().slot() == ItemSlot.MAIN_HAND)
                .findFirst().orElse(null);
        final ItemBase mainBase = mainItem == null || ownProblem.apply(mainItem) != null ? null : mainItem.base();
        for (final EquippedItem item : equipped) {
            String reason = ownProblem.apply(item);
            if (reason == null && (item.offHandWeapon() || item.base().slot() == ItemSlot.OFF_HAND)) {
                reason = offHandProblem(state, mainBase, item, sheet); // 2026-10-27
            }
            if (reason != null) {
                inactive.add(item.base().name() + "：" + reason);
                continue;
            }
            active.add(item);
            final boolean sceptre = item.base().tags().contains("sceptre");
            double localSpirit = 0;
            for (final Modifier raw : item.modifiers()) {
                // 2026-10：有條件的詞綴（evasion@nohit、damage@projhit 這種）：條件成立才算，代號拿掉條件
                final Modifier modifier = live ? resolveCondition(player, state, raw, now, conditions) : withoutCondition(raw);
                if (modifier == null) {
                    continue;
                }
                if (necroTalisman && item.base().slot() == tw.exilecore.item.ItemSlot.AMULET && !modifier.stat().startsWith("flag.")) {
                    final Set<String> minionTags = new java.util.HashSet<>(modifier.tags());
                    minionTags.add(Tags.MINION);
                    sheet.add(modifier.withTags(minionTags)); // 靈能魔符：項鍊的加成給召喚物（不給你）
                    continue;
                }
                if (sceptre && StatKeys.SPIRIT_MAX.equals(modifier.stat()) && modifier.type() == ModType.INCREASED) {
                    localSpirit += modifier.value(); // 2026-10：權杖的「增加 x% 精魂」是本地的，只放大權杖自己的 100（PoE2）
                    continue;
                }
                sheet.add(modifier);
            }
            if (sceptre) {
                sheet.add(Modifier.flat(StatKeys.SPIRIT_MAX, SCEPTRE_SPIRIT * Math.max(0, 1 + localSpirit), "權杖")); // 2026-10：照 PoE2，權杖給 100 精魂
            }
            if (item.base().isWeapon() && !item.offHandWeapon()) { // 2026-10-27：副手的權杖不算拿著的武器（攻擊用主手）
                hasWeapon = true;
                weaponTags = item.base().tags();
            }
        }
        // 2026-10-27（傳奇）：映鏡指環（另一枚戒指再算一次）、巧匠束帶（左右手戒指的加成增加）
        sheet.addAll(tw.exilecore.item.unique.UniqueDerived.rings(sheet, active.stream()
                .filter(item -> item.base().slot() == ItemSlot.RING).map(EquippedItem::modifiers).toList()));
        sheet.addAll(extra);
        // 2026-10-26 照 PoE2 基石：空掌之術——兩手都空著時當作拿長棍攻擊（長棍的技能能用、吃長棍的修飾器）
        final boolean hollowPalm = !hasWeapon && StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_HOLLOW_PALM)
                && active.stream().noneMatch(item -> item.offHandWeapon() // 2026-10-27：副手拿的雙手武器、權杖也不是空手
                        || item.base().slot() == tw.exilecore.item.ItemSlot.OFF_HAND);
        if (hollowPalm) {
            weaponTags = Set.of("weapon", Tags.TWO_HAND, Tags.QUARTERSTAFF, Tags.MELEE);
        }
        final List<tw.exilecore.stats.KeystoneKeys.Piece> armourPieces = new ArrayList<>();
        for (final EquippedItem item : active) {
            if (!item.base().isWeapon() && item.base().slot() != null) {
                armourPieces.add(new tw.exilecore.stats.KeystoneKeys.Piece(item.base().slot().name(), itemFlat(item, StatKeys.ARMOUR),
                        itemFlat(item, StatKeys.EVASION), itemFlat(item, StatKeys.ES_MAX)));
            }
        }
        // 2026-10-27 照 PoE2：身體護甲、盾牌的移動懲罰（純力量身體 5%、力量混合 4%、其他 3%；力量盾 3%、混合 1.5%），氣勢（無視防具的移動懲罰）不算；
        // 這次之前防具沒有移動懲罰，氣勢平衡等效成增加 3% 移動速度
        if (!StatKeys.flag(sheet, tw.exilecore.stats.KeystoneOriginKeys.IGNORE_ARMOUR_MOVE_PENALTY)) {
            double movePenalty = 0;
            for (final EquippedItem item : active) {
                if (!item.offHandWeapon() && item.base().slot() != null) {
                    movePenalty += tw.exilecore.stats.KeystoneOriginKeys.armourMovePenalty(item.base().slot().name(), item.base().tags());
                }
            }
            if (movePenalty > 0) {
                sheet.add(Modifier.more(StatKeys.MOVEMENT_SPEED, -movePenalty, "防具的移動懲罰"));
            }
        }
        if (live || virtual != null) {
            state.setOffHandAttack(offHandAttack(active)); // 2026-10-27 照 PoE2 雙持（巨人之血）：副手武器輪流出手
        }
        if (live) {
            state.setWeaponTags(weaponTags);
            state.setConditionsEvaluated(conditions);
            // 2026-10-26：法杖、長杖、權杖賦予的技能（PoE2 Grants Skill）放進技能面板；需求不符的武器不給
            // 2026-10-26（昇華）：昇華天賦賦予的技能（grant_skill.代號，等級照角色等級）
            final Map<String, Integer> granted = new java.util.LinkedHashMap<>(grantedSkills(active));
            tw.exilecore.ascendancy.AscBuild.grantedSkills(plugin, extra, level, granted);
            state.skills().syncGranted(granted, grantedQuality(active)); // 面板跟著角色一起存檔（定期、登出）；2026-10-27：＋武器的品質
        } else if (virtual != null) {
            state.setWeaponTags(weaponTags); // 虛擬角色：技能要知道拿什麼武器
        }
        if (inactiveSink != null) {
            inactiveSink.addAll(inactive);
        }
        ascendancyEquipment(sheet, state, active, tree);
        // 2026-10-26（昇華）：添煤工、烈焰鑄造、引我行恩典之途、神聖流向、聖賢智慧、嵌晶的寶石顏色…（AscBuild；展露之指改成第三個戒指欄）
        tw.exilecore.ascendancy.AscBuild.equipment(plugin, sheet, state, active.stream()
                .map(item -> new tw.exilecore.ascendancy.AscBuild.Gear(item.offHandWeapon() ? ItemSlot.OFF_HAND : item.base().slot(), item.base(), item.data(), item.modifiers())).toList());
        // 2026-10-26（傳奇）：每顆銳星的抗性、每 100 生命的精魂效率、拿著法器的格擋、邪力（tw.exilecore.item.unique.UniqueDerived）
        sheet.addAll(tw.exilecore.item.unique.UniqueDerived.modifiers(sheet,
                player == null || plugin.combat() == null || plugin.combat().charges() == null ? 0 : plugin.combat().charges().count(player, "power"),
                active.stream().anyMatch(item -> item.base().tags().contains("focus"))));
        // 2026-10-27（傳奇第二批）：生命／魔力換護盾、每顆戰星、照屬性點數、魔力換荊棘、格擋率換火焰
        sheet.addAll(tw.exilecore.item.unique.UniqueDerived.second(sheet, id -> player == null || plugin.combat() == null
                || plugin.combat().charges() == null ? 0 : plugin.combat().charges().count(player, id)));
        // 與死共舞（2026-10 PoE2 基石）：主手單手武器（魔杖、權杖除外）、副手空著：技能速度更多 25%
        if (StatKeys.flag(sheet, StatKeys.FLAG_DANCE_WITH_DEATH) && weaponTags.contains("one_hand")
                && !weaponTags.contains("wand") && !weaponTags.contains("sceptre")
                && equipped.stream().noneMatch(item -> item.base().slot() == tw.exilecore.item.ItemSlot.OFF_HAND)) {
            sheet.add(Modifier.more(StatKeys.ATTACK_SPEED, 0.25, "單刃疾舞"));
            sheet.add(Modifier.more(StatKeys.CAST_SPEED, 0.25, "單刃疾舞"));
        }
        if (hollowPalm) {
            // 空掌之術（PoE2）：徒手的基礎物理傷害換成照等級算的（約同等級的白色長棍），每 75 點防具物品閃避更多 1% 攻擊速度、
            // 每 10 點防具物品能量護盾 +0.1% 暴擊率
            double itemEvasion = 0;
            double itemEs = 0;
            for (final tw.exilecore.stats.KeystoneKeys.Piece piece : armourPieces) {
                if (!"OFF_HAND".equals(piece.slot())) {
                    itemEvasion += Math.max(0, piece.evasion());
                    itemEs += Math.max(0, piece.energyShield());
                }
            }
            sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, Math.round(1 + 0.9 * level), Set.of(Tags.PHYSICAL), "空掌之術"));
            sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, Math.round(2 + 1.7 * level), Set.of(Tags.PHYSICAL), "空掌之術"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_APS, 1.4, "空掌之術"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_CRIT, 10 + Math.floor(itemEs / 10) * 0.1, "空掌之術"));
            if (itemEvasion >= 75) {
                sheet.add(Modifier.more(StatKeys.ATTACK_SPEED, Math.floor(itemEvasion / 75) / 100.0, "空掌之術"));
            }
        } else if (!hasWeapon) {
            // 徒手：2-6 物理，每秒 1.2 次，暴擊 5%
            // 2026-10-26 照 PoE2：徒手 1.65 次／秒（攻擊時間 0.606 秒），傷害照職業：戰士 2–8、傭兵與德魯伊 2–6、其他 2–5
            sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 2, Set.of(Tags.PHYSICAL), "徒手"));
            sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, unarmedMax(characterClass), Set.of(Tags.PHYSICAL), "徒手"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_APS, 1.65, "徒手"));
            sheet.add(Modifier.flat(StatKeys.WEAPON_CRIT, 5, "徒手"));
        }

        // 等級與屬性值的基礎
        final double finalStr = sheet.get(StatKeys.STR);
        final double finalDex = sheet.get(StatKeys.DEX);
        final double finalInt = sheet.get(StatKeys.INT);
        // 2026-10-26 照 PoE2：永恆珠寶（英雄悲劇）的卡爾葛基石——沒有某個屬性的固有加成，改成每 2 點給增加 1% 別的防禦
        final boolean altStr = StatKeys.flag(sheet, tw.exilecore.passive.TimelessJewels.FLAG_ALT_STR);
        final boolean altDex = StatKeys.flag(sheet, tw.exilecore.passive.TimelessJewels.FLAG_ALT_DEX);
        final boolean altInt = StatKeys.flag(sheet, tw.exilecore.passive.TimelessJewels.FLAG_ALT_INT);
        sheet.setBase(StatKeys.LIFE_MAX, Formulas.baseLife(level) + (altStr ? 0 : Formulas.lifeFromStrength(finalStr)));
        sheet.setBase(StatKeys.MANA_MAX, Formulas.baseMana(level) + (altInt ? 0 : Formulas.manaFromIntelligence(finalInt)));
        sheet.setBase(StatKeys.SPIRIT_MAX, questSpirit(state)); // 2026-10：精魂的基礎＝打倒過的劇情 Boss
        sheet.setBase(StatKeys.EVASION, Formulas.baseEvasion(level));
        sheet.setBase(tw.exilecore.stats.DamageCalculator.FLAG_PLAYER_SIDE, 1); // 2026-10-27 照 PoE2：被怪物打用怪物的命中公式
        sheet.setBase(StatKeys.ACCURACY, Formulas.baseAccuracy(level) + (altDex ? 0 : Formulas.accuracyFromDexterity(finalDex)));
        sheet.add(Modifier.increased(StatKeys.EVASION, altDex ? 0 : Formulas.evasionIncreaseFromDexterity(finalDex), "敏捷"));
        sheet.add(Modifier.increased(StatKeys.ES_MAX, altInt ? 0 : Formulas.esIncreaseFromIntelligence(finalInt), "智慧"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, altStr ? 0 : Formulas.meleePhysicalIncreaseFromStrength(finalStr),
                Set.of(Tags.MELEE, Tags.PHYSICAL), "力量"));
        if (altStr) {
            sheet.add(Modifier.increased(StatKeys.ES_MAX, Math.floor(finalStr / 2) / 100.0, "黑暗血鐮訓練"));
        }
        if (altDex) {
            sheet.add(Modifier.increased(StatKeys.ARMOUR, Math.floor(finalDex / 2) / 100.0, "循環教義"));
        }
        if (altInt) {
            sheet.add(Modifier.increased(StatKeys.EVASION, Math.floor(finalInt / 2) / 100.0, "騎士信條"));
        }
        // 魔力回復：每秒回 4% 最大魔力（PoE2 的基礎值；再吃「增加 x% 魔力回復」）
        sheet.setBase(StatKeys.MANA_REGEN, sheet.get(StatKeys.MANA_MAX) * 0.04);
        // 2026-10-26（昇華）：強化效果（固有加成加倍）、無魂型態（魔力再生）、火祭協議（最大魔力換成煉獄之炎）
        tw.exilecore.ascendancy.AscBuild.afterBase(sheet, finalStr, finalDex, finalInt, altStr, altDex, altInt);

        // 元素抗性的懲罰（2026-10，PoE 的做法）：等級 12、24、36、48、60 與第一次完成第四試煉各 -10%，共 -60%
        final int penalty = resistPenalty(state);
        if (penalty > 0) {
            for (final String resist : new String[] {StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING}) {
                sheet.add(Modifier.flat(resist, -penalty, "抗性懲罰"));
            }
        }

        // 基石「鐵壁反射」：閃避全部轉為護甲（算完閃避後再轉）
        if (StatKeys.flag(sheet, StatKeys.FLAG_IRON_REFLEXES)) {
            final double evasion = sheet.get(StatKeys.EVASION);
            sheet.add(Modifier.flat(StatKeys.ARMOUR, evasion, "化閃為甲"));
            sheet.add(Modifier.more(StatKeys.EVASION, -1.0, "化閃為甲"));
        }

        // 煉獄使者（PoE2 昇華）：照最大生命換成護盾／魔力／精魂（各保留 25% 生命，見 PlayerState.maxLife）
        final double lifeForBeidat = sheet.get(StatKeys.LIFE_MAX);
        if (StatKeys.flag(sheet, StatKeys.BEIDAT_HAND)) {
            sheet.add(Modifier.flat(StatKeys.ES_MAX, Math.floor(lifeForBeidat / 8), "魔契之手"));
        }
        if (StatKeys.flag(sheet, StatKeys.BEIDAT_GAZE)) {
            sheet.add(Modifier.flat(StatKeys.MANA_MAX, Math.floor(lifeForBeidat / 6), "魔契之眼"));
        }
        if (StatKeys.flag(sheet, StatKeys.BEIDAT_WILL)) {
            sheet.add(Modifier.flat(StatKeys.SPIRIT_MAX, Math.floor(lifeForBeidat / 25), "魔契之志"));
        }

        // 2026-10-26 照 PoE2（永恆珠寶的忠誠獻祭）：不死召喚物強度換成更多傷害（盾牌防禦、生命轉護盾在 TreeEffectKeys）
        tw.exilecore.passive.ConquestEffects.derive(sheet);
        // 2026-10-26 照 PoE2 天賦樹：輔助寶石顏色的門檻、照裝備數值換算、每 x 點屬性（AttributeScalingKeys）
        tw.exilecore.stats.AttributeScalingKeys.applyDerived(sheet, scalingGear(active, armourPieces), supportCounts(state));
        tw.exilecore.stats.TreeEffectKeys.applyDerived(sheet); // 2026-10-26 照 PoE2 天賦樹：每點敏捷的暈眩門檻、等同智慧的命中、生命轉換成護盾
        // 2026-10-26 照 PoE2 天賦樹（一次性的特殊效果）：閃避→護甲、力量→命中、頭盔護盾、鞋子／頭盔門檻、巨人之血…（KeystoneKeys）
        final boolean spellsCostLife = StatKeys.flag(sheet, StatKeys.FLAG_BLOOD_MAGIC) || StatKeys.flag(sheet, StatKeys.FLAG_SANGUIMANCY)
                || sheet.flat(tw.exilecore.stats.TreeKeys.LIFE_COST_PERCENT, Set.of(Tags.SPELL)) > 0;
        tw.exilecore.stats.KeystoneKeys.applyDerived(sheet, armourPieces, altStr ? 0 : Formulas.lifeFromStrength(finalStr), spellsCostLife);
        tw.exilecore.stats.AttributeScalingKeys.applyLate(sheet); // 2026-10-26 照 PoE2 天賦樹：每 400 命中的攻擊速度（命中都加完之後）
        // 荒野領主（PoE2）：持用獸魂符時可以再裝一把權杖——2026-10-27 照原作，副手真的裝權杖（精魂、詞綴、賦予的技能照權杖算）；
        // 這次之前副手放不了權杖，平衡等效直接給 100 精魂
        // 巨人之血：2026-10-27 起副手可以配盾、法器，或再拿一把雙手斧、錘、劍雙持（DualWield，照原作）；
        // 這次之前沒有雙持，副手空著時給「攻擊傷害更多 25%」當平衡等效（拿掉了）
        tw.exilecore.stats.TreeKeys.applyEsThresholds(sheet); // 2026-10-26 照 PoE2 天賦樹：等同最大護盾 x% 的暈眩／異常／凍結門檻
        // 2026-10-27：夥伴傷害的增加也給你（天賦樹）——只看屬性表，比對用的表、網頁的虛擬角色也算；這次之前是暫時增益（網頁沒算）
        sheet.addAll(tw.exilecore.skill.TreeMechanics.companionDamageModifiers(sheet));
        if (!live) {
            return; // 比對用的表：到這裡就算完了，下面都是更新玩家狀態
        }
        // 2026-10-26：有「對某種敵人」（vs_ 標籤）的修飾器，擊中時才去看目標的狀態
        if (player != null) {
            conditionTracker.setTargetTags(player.getUniqueId(), passiveResolved.stream().anyMatch(tw.exilecore.stats.TreeConditions::hasEnemyCondition)
                    || active.stream().anyMatch(item -> item.modifiers().stream().anyMatch(tw.exilecore.stats.TreeConditions::hasEnemyCondition)));
        }
        state.clampToMax();
        state.markClean();
        reportUnmetGems(player, state, sheet); // 2026-10-26 照 PoE2：技能寶石的屬性需求（tw.exilecore.skill.GemAttributes）
        syncVanillaAttributes(player, sheet);
        reportInactiveEquipment(player, state, inactive);
        if (plugin.items() != null && plugin.items().comparer() != null) {
            // 身上的裝備可能換了：背包裡的「比身上的 ×」重算。
            // 2026-10-23：buff、充能一變也會重算屬性表；裝備沒換的話最多 2 秒才重比一次（背包撿到的新東西照樣會比到）
            // 2026-10-27 效能：每 2 秒那次只在背包內容變了才真的重比（ItemComparer 看背包的指紋）；這次之前每 2 秒整個背包重新解析一次
            final long tick = plugin.getServer().getCurrentTick();
            final int signature = gearSignature(state);
            final long[] last = comparerSignature.get(player.getUniqueId());
            if (last == null || last[0] != signature) {
                comparerSignature.put(player.getUniqueId(), new long[] {signature, tick});
                plugin.items().comparer().schedule(player);
            } else if (tick - last[1] >= COMPARER_INTERVAL) {
                last[1] = tick;
                plugin.items().comparer().scheduleIfChanged(player);
            }
        }
    }

    /**
     * 2026-10-27 照 PoE2 雙持（巨人之血）：生效的副手武器（權杖不能攻擊，不算）——主手、副手武器本身的數值，副手出手時換過去。
     * 沒有雙持回傳 null。
     */
    private DualWield.OffHand offHandAttack(final List<EquippedItem> active) {
        final EquippedItem main = active.stream().filter(item -> !item.offHandWeapon() && item.base().isWeapon()).findFirst().orElse(null);
        final EquippedItem off = active.stream().filter(item -> item.offHandWeapon() && !item.base().tags().contains("sceptre"))
                .findFirst().orElse(null);
        if (main == null || off == null || plugin.items() == null) {
            return null;
        }
        final List<Modifier> offWeapon = ItemStats.modifiers(plugin.items().repository(), off.data()).stream()
                .filter(DualWield::weaponLocal).toList();
        return new DualWield.OffHand(off.base().tags(), main.base().tags(), DualWield.swap(main.modifiers(), offWeapon));
    }

    /**
     * 2026-10-27：副手跟主手搭不搭（天賦換了、裝備存檔換上的也要擋）：雙手武器配盾、法器要巨人之血（雙手斧錘劍）或昇華・力量之器（長杖配法器）；
     * 副手的權杖要點了荒野領主、主手是獸魂符、不是傳奇。
     */
    private String offHandProblem(final PlayerState state, final ItemBase main, final EquippedItem off, final StatSheet sheet) {
        if (off.offHandWeapon() && !off.base().tags().contains("sceptre")) {
            // 2026-10-27 照 PoE2 巨人之血：兩手各拿一把雙手斧、錘、劍（雙持）
            return Gear.giantOffHand(main, off.base(), sheet) ? null : "副手的雙手武器要點了巨人之血、主手也是雙手斧、錘、劍";
        }
        if (off.offHandWeapon()) {
            return Gear.sceptreOffHand(main, off.base(), sheet) && !Gear.uniqueSceptre(off.data()) ? null : "副手的權杖要持用獸魂符並點了荒野領主（非傳奇）";
        }
        if (main == null || !off.base().isShield() || !main.hasTag(Tags.TWO_HAND)) {
            return null;
        }
        if (StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_GIANTS_BLOOD) && tw.exilecore.stats.KeystoneKeys.giantWeapon(main.tags())
                || tw.exilecore.ascendancy.AscBuild.staffFocus(plugin, state, main, off.base())) {
            return null;
        }
        return Gear.pairProblem(main, off.base());
    }

    /**
     * 2026-10-26：生效的武器賦予的技能（技能代號 → 等級）。等級照武器的物品等級：寶石需求等級（skills.yml 的 level_requirements）
     * 不超過物品等級的最高那一級（PoE2：物品等級越高，賦予的技能等級越高）。
     */
    /** 2026-10-27 照 PoE2：每把矛都賦予的擲矛（skills.yml 的代號）。 */
    static final String SPEAR_THROW = "spear_throw";

    private Map<String, Integer> grantedSkills(final List<EquippedItem> active) {
        if (plugin.items() == null || plugin.skills() == null) {
            return Map.of();
        }
        final Map<String, Integer> granted = new java.util.LinkedHashMap<>();
        for (final EquippedItem item : active) {
            // 2026-10-26（傳奇：司祭法衣、踏骸之履）：武器以外的裝備也可以賦予技能（grant_skill.代號）
            for (final String own : tw.exilecore.item.unique.UniqueDerived.grantedSkills(item.modifiers())) {
                if (plugin.skills().config().skill(own) != null) {
                    granted.merge(own, plugin.skills().config().maxLevelFor(Math.max(1, item.data().ilvl())), Math::max);
                }
            }
            if (!item.base().isWeapon() && !item.base().tags().contains("sceptre")) {
                continue;
            }
            // 2026-10-27 照 PoE2：每把矛都賦予擲矛（Spear Throw；品質照常加在矛上，不算 grants）；這次之前拿矛沒有擲矛
            if (item.base().tags().contains(tw.exilecore.stats.Tags.SPEAR) && plugin.skills().config().skill(SPEAR_THROW) != null) {
                granted.merge(SPEAR_THROW, plugin.skills().config().maxLevelFor(Math.max(1, item.data().ilvl())), Math::max);
            }
            final String skill = plugin.items().repository().grantedSkill(item.data().base());
            if (skill == null || plugin.skills().config().skill(skill) == null) {
                continue;
            }
            final int level = plugin.skills().config().maxLevelFor(Math.max(1, item.data().ilvl()));
            granted.merge(skill, level, Math::max);
        }
        return granted;
    }

    /**
     * 2026-10-27 照 PoE2 0.5（poe2wiki Quality）：法杖、長杖、權杖的品質不加武器自己的數值，加在賦予的技能上
     * （技能代號 → 武器的品質；同一個技能有好幾把取最高）。這次之前賦予的技能沒有品質。
     */
    private Map<String, Integer> grantedQuality(final List<EquippedItem> active) {
        if (plugin.items() == null) {
            return Map.of();
        }
        final Map<String, Integer> quality = new java.util.HashMap<>();
        for (final EquippedItem item : active) {
            if (item.data().quality() <= 0 || !item.base().isWeapon() && !item.base().tags().contains("sceptre")) {
                continue;
            }
            final String skill = plugin.items().repository().grantedSkill(item.data().base());
            if (skill != null) {
                quality.merge(skill, item.data().quality(), Math::max);
            }
        }
        return quality;
    }

    /** 2026-10-27 效能：條件造成的重算每人最多每秒一次。 */
    private final ConditionHold conditionHold = new ConditionHold();

    /** 2026-10-23：裝備沒換時，裝備比較最多這麼多 tick 重算一次。 */
    static final long COMPARER_INTERVAL = 40;
    /** 玩家 → {上次比較時的裝備雜湊, 那時的 tick}。 */
    private final Map<java.util.UUID, long[]> comparerSignature = new java.util.HashMap<>();

    /**
     * 2026-10-27：依序呼叫暫時增益的來源（照註冊的順序）。呼叫時屬性表上已經有天賦樹、昇華、其他來源與裝備（第一輪），
     * 所以來源可以讀 state.sheet() 看那些旗標。
     */
    static List<Modifier> collectBuffs(final List<Function<Player, List<Modifier>>> sources, final Player player) {
        if (sources.isEmpty()) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        for (final Function<Player, List<Modifier>> source : sources) {
            out.addAll(source.apply(player));
        }
        return out;
    }

    /** 「+1 戒指欄位」加起來（2026-10-26）。 */
    private static double ringExtra(final List<Modifier> modifiers) {
        double total = 0;
        for (final Modifier mod : modifiers) {
            if (mod.type() == ModType.FLAT && PlayerState.RING_EXTRA_STAT.equals(mod.stat())) {
                total += mod.value();
            }
        }
        return total;
    }

    /** 身上裝備的雜湊（哪一件、內容）。 */
    private static int gearSignature(final PlayerState state) {
        int hash = 1;
        for (int i = 0; i < PlayerState.GEAR_SLOTS.length; i++) {
            final ItemStack stack = state.jewellery(PlayerState.gearIndex(i));
            hash = 31 * hash + (stack == null ? 0 : System.identityHashCode(stack) * 17 + stack.hashCode());
        }
        return hash;
    }

    /** 元素抗性懲罰的等級（每到一個 -{@value #RESIST_PENALTY_STEP}%）。 */
    public static final int[] RESIST_PENALTY_LEVELS = {12, 24, 36, 48, 60};
    public static final int RESIST_PENALTY_STEP = 10;

    /** 第四試煉的代號（ascendancy.yml 的第四個試煉；昇華模組沒啟用時是 star）。 */
    public String fourthTrial() {
        if (plugin.ascendancy() != null) {
            int index = 0;
            for (final var trial : plugin.ascendancy().config().trials()) {
                if (index++ == 3) {
                    return trial.id();
                }
            }
        }
        return "star";
    }

    /** 這個角色現在的元素抗性懲罰（正數，例如 30 = -30%）：照等級與有沒有完成過第四試煉（以前完成的也算）。 */
    public int resistPenalty(final PlayerState state) {
        int penalty = 0;
        for (final int at : RESIST_PENALTY_LEVELS) {
            if (state.level() >= at) {
                penalty += RESIST_PENALTY_STEP;
            }
        }
        if (state.progress().containsKey(tw.exilecore.ascendancy.Ascension.trialKey(fourthTrial()))) {
            penalty += RESIST_PENALTY_STEP;
        }
        return penalty;
    }

    /**
     * 能不能穿上這件（2026-10）：還沒鑑定的、等級或屬性需求不夠的穿不上去。
     * 用現在的屬性比（換下來的那件給的屬性還算在內；換完不夠的話那件照舊標成沒生效）。
     *
     * @return 穿不上的原因；可以穿回傳 null（不是我們的物品也回傳 null，由呼叫的人判斷）
     */
    public String equipProblem(final Player player, final ItemStack stack) {
        if (stack == null || stack.isEmpty() || plugin.items() == null) {
            return null;
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        final ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        if (base == null) {
            return null;
        }
        final StatSheet sheet = sheet(player);
        // 2026-10-26（昇華・嵌晶、鐵匠傑作）
        final List<Modifier> asc = plugin.ascendancy() == null || !get(player).hasCharacter() ? List.of()
                : plugin.ascendancy().ascension(get(player)).modifiers();
        final double[] attr = tw.exilecore.ascendancy.AscBuild.requirementAttributes(asc, sheet.get(StatKeys.STR), sheet.get(StatKeys.DEX), sheet.get(StatKeys.INT));
        final String own = data.identified() ? tw.exilecore.ascendancy.AscBuild.itemProblem(asc, base.slot(), data) : null;
        if (own != null) {
            return "穿不上去：" + own;
        }
        final double[] attrs = tw.exilecore.ascendancy.AscBuild.ignoresRequirements(asc, base.slot())
                ? tw.exilecore.ascendancy.AscBuild.NO_REQUIREMENT : attr; // 2026-10-27（昇華・石拳之道）
        return equipProblem(data, scaledRequirements(ItemStats.requirements(plugin.items().repository(), base, data),
                        tw.exilecore.stats.TreeEffectKeys.requirementScale(sheet)), get(player).level(), attrs[0], attrs[1], attrs[2]);
    }

    /** 同上，純邏輯。 */
    static String equipProblem(final ItemData data, final ItemBase.Requirements req, final int level,
                               final double str, final double dex, final double intelligence) {
        if (!data.identified()) {
            return "還沒鑑定，穿不上去：先找營地的鑑定師（免費）或用鑑定卷軸";
        }
        final String reason = requirementProblem(req, level, str, dex, intelligence);
        return reason == null ? null : "穿不上去：" + reason;
    }

    /** 2026-10-26 照 PoE2 天賦樹：「裝備的屬性需求減少 x%」（等級需求不變）。 */
    static ItemBase.Requirements scaledRequirements(final ItemBase.Requirements req, final double scale) {
        if (scale == 1.0) {
            return req;
        }
        return new ItemBase.Requirements(req.level(), (int) Math.round(req.str() * scale), (int) Math.round(req.dex() * scale),
                (int) Math.round(req.intelligence() * scale));
    }

    /** 需求不符的原因；符合回傳 null。 */
    private static String requirementProblem(final ItemBase.Requirements req, final int level,
                                             final double str, final double dex, final double intelligence) {
        if (level < req.level()) {
            return "等級不足（需要 " + req.level() + "，你是 " + level + "）";
        }
        if (str < req.str()) {
            return "力量不足（需要 " + req.str() + "，你有 " + Math.round(str) + "）";
        }
        if (dex < req.dex()) {
            return "敏捷不足（需要 " + req.dex() + "，你有 " + Math.round(dex) + "）";
        }
        if (intelligence < req.intelligence()) {
            return "智慧不足（需要 " + req.intelligence() + "，你有 " + Math.round(intelligence) + "）";
        }
        return null;
    }

    /** 比對用的表：有條件的詞綴一律不算（條件成立才有的效果也當成增益）。 */
    private static Modifier withoutCondition(final Modifier modifier) {
        return tw.exilecore.stats.Recently.hasCondition(modifier.stat()) ? null : modifier;
    }

    /** 需求不符的裝備有變動時提醒玩家（只提醒新出現的那幾件）。 */
    /** 2026-10-26 照 PoE2：重算屬性需求不足的技能寶石（不能用、光環關掉），新變成不足的提醒一次。 */
    private void reportUnmetGems(final Player player, final PlayerState state, final StatSheet sheet) {
        if (plugin.skills() == null) {
            return;
        }
        final Map<String, String> unmet = tw.exilecore.skill.GemAttributes.unmet(plugin.skills().config(), state.skills(), sheet);
        final List<String> added = state.skills().syncUnmet(unmet);
        if (!state.isLoaded() || player == null) {
            return;
        }
        for (final String id : added) {
            final tw.exilecore.skill.SkillDefinition definition = plugin.skills().config().skill(id);
            player.sendMessage(plugin.text().prefixed("<red>" + escape(definition == null ? id : definition.name()) + "：" + escape(unmet.get(id))
                    + "，這顆技能寶石不能使用"));
        }
    }

    private void reportInactiveEquipment(final Player player, final PlayerState state, final List<String> inactive) {
        final List<String> previous = state.inactiveEquipment();
        state.setInactiveEquipment(inactive);
        if (!state.isLoaded()) {
            return; // 角色還沒從資料庫載入，屬性還是佔位值，先不提醒
        }
        for (final String line : inactive) {
            if (!previous.contains(line)) {
                player.sendMessage(plugin.text().prefixed("<red>" + escape(line) + "，這件裝備不生效"));
            }
        }
    }

    /**
     * 條件詞綴：條件成立回傳拿掉條件的修飾器，不成立回傳 null；沒有條件原樣回傳。不認得的條件當成不成立（詞綴寫錯不會偷偷生效）。
     * 算過的條件記進 conditions（tick 用來看有沒有變）。
     */
    private Modifier resolveCondition(final Player player, final PlayerState state, final Modifier modifier, final long now,
                                      final Map<String, Boolean> conditions) {
        return resolved(modifier, condition -> conditions.computeIfAbsent(condition.key(), key -> evaluate(player, state, condition, now)));
    }

    /**
     * 條件詞綴的判斷（純邏輯，2026-10-26 抽出來給測試用）：沒有條件原樣回傳；條件不認得或不成立回傳 null；成立回傳拿掉條件的修飾器。
     */
    public static Modifier resolved(final Modifier modifier, final java.util.function.Predicate<tw.exilecore.stats.Recently.Condition> holds) {
        if (!tw.exilecore.stats.Recently.hasCondition(modifier.stat())) {
            return modifier;
        }
        final tw.exilecore.stats.Recently.Condition condition = tw.exilecore.stats.Recently.condition(modifier.stat());
        if (condition == null || !holds.test(condition)) {
            return null;
        }
        return new Modifier(tw.exilecore.stats.Recently.stat(modifier.stat()), modifier.type(), modifier.value(), modifier.tags(), modifier.source());
    }

    /** 這個條件現在成不成立。 */
    private boolean evaluate(final Player player, final PlayerState state, final tw.exilecore.stats.Recently.Condition condition, final long now) {
        final boolean happened = switch (condition.event()) {
            case tw.exilecore.stats.Recently.LIFE_FLASK -> plugin.flasks() != null && plugin.flasks().recovering(player, tw.exilecore.flask.FlaskKind.LIFE);
            case tw.exilecore.stats.Recently.MANA_FLASK -> plugin.flasks() != null && plugin.flasks().recovering(player, tw.exilecore.flask.FlaskKind.MANA);
            default -> conditionTracker.test(player, state, condition.event(), now); // 2026-10-26：天賦樹的狀態條件（也包含「最近…」）
        };
        return condition.negated() != happened;
    }

    /** 上一次算屬性表時的條件結果，現在有沒有哪個變了。 */
    private boolean conditionsChanged(final Player player, final PlayerState state, final long now) {
        for (final Map.Entry<String, Boolean> entry : state.conditionsEvaluated().entrySet()) {
            final tw.exilecore.stats.Recently.Condition condition = tw.exilecore.stats.Recently.condition("x" + entry.getKey());
            if (condition != null && evaluate(player, state, condition, now) != entry.getValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 我們有算的裝備欄位：全部在角色面板上（武器、副手、四件防具、戒指×2、項鍊、腰帶；2026-10-26 展露之指的第三個戒指），
     * 手上拿的、身上穿的只是顯示。
     */
    private List<EquippedItem> equippedItems(final Player player, final PlayerState state, final boolean thirdRing) {
        final List<EquippedItem> list = new ArrayList<>();
        final ParsedGear[] cache = player == null ? null : parsedGear.computeIfAbsent(player.getUniqueId(),
                uuid -> new ParsedGear[PlayerState.GEAR_SLOTS.length]);
        for (int i = 0; i < PlayerState.GEAR_SLOTS.length; i++) {
            final ItemStack stack = i == PlayerState.RING3 && !thirdRing ? null : state.jewellery(PlayerState.gearIndex(i));
            if (stack == null || stack.isEmpty() || plugin.items() == null) {
                if (cache != null) {
                    cache[i] = null;
                }
                continue;
            }
            // 2026-10-23：同一件（同一個物品、內容的雜湊一樣、資料庫沒重載）就不再解一次物品資料（buff、充能一變就重算屬性表）
            final int hash = stack.hashCode();
            final tw.exilecore.item.ItemRepository repository = plugin.items().repository();
            ParsedGear parsed = cache == null ? null : cache[i];
            if (parsed == null || parsed.stack() != stack || parsed.hash() != hash || parsed.repository() != repository) {
                final List<EquippedItem> one = new ArrayList<>(1);
                collect(one, state, stack, PlayerState.GEAR_SLOTS[i], false);
                parsed = new ParsedGear(stack, hash, repository, one.isEmpty() ? null : one.getFirst());
                if (cache != null) {
                    cache[i] = parsed;
                }
            }
            if (parsed.item() != null) {
                list.add(withInstil(state, parsed.item()));
            }
        }
        return list;
    }

    /** 2026-10-23：解好的裝備（每個玩家每一格一份）。 */
    private record ParsedGear(ItemStack stack, int hash, tw.exilecore.item.ItemRepository repository, EquippedItem item) {
    }

    private final Map<java.util.UUID, ParsedGear[]> parsedGear = new java.util.HashMap<>();

    /** 天賦樹上已經點了注入的核心：拿掉那一份（每次都照現在的天賦算）。 */
    private EquippedItem withInstil(final PlayerState state, final EquippedItem item) {
        if (!instilAllocated(state, item.data())) {
            return item;
        }
        final List<Modifier> modifiers = new ArrayList<>(item.modifiers());
        for (final Modifier mod : ItemStats.instilOf(item.data()).modifiers()) {
            modifiers.remove(mod);
        }
        return new EquippedItem(item.base(), item.data(), modifiers, item.requirements(), item.offHandWeapon());
    }

    /** 虛擬角色的裝備（物品資料，照 {@link PlayerState#GEAR_SLOTS} 的順序；第 11 個是第三個戒指，有「+1 戒指欄位」才算）。 */
    private List<EquippedItem> equippedItems(final PlayerState state, final List<ItemData> gear, final boolean thirdRing) {
        final List<EquippedItem> list = new ArrayList<>();
        if (plugin.items() == null) {
            return list;
        }
        for (int i = 0; i < PlayerState.GEAR_SLOTS.length && i < gear.size(); i++) {
            if (gear.get(i) != null && (i != PlayerState.RING3 || thirdRing)) {
                collect(list, state, gear.get(i), PlayerState.GEAR_SLOTS[i]);
            }
        }
        return list;
    }

    private void collect(final List<EquippedItem> list, final PlayerState state, final ItemStack stack, final ItemSlot slot, final boolean instil) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        plugin.items().read(stack).ifPresent(data -> collect(list, state, data, slot, instil));
    }

    private void collect(final List<EquippedItem> list, final PlayerState state, final ItemData item, final ItemSlot slot) {
        collect(list, state, item, slot, true);
    }

    private void collect(final List<EquippedItem> list, final PlayerState state, final ItemData item, final ItemSlot slot, final boolean instil) {
        final Optional<ItemData> data = Optional.of(item);
        final ItemBase base = plugin.items().repository().base(data.get().base());
        // 2026-10-27 照 PoE2 荒野領主：副手的權杖（能不能生效在算屬性表時看：要點了荒野領主、主手是獸魂符、不是傳奇）
        // 2026-10-27 照 PoE2 巨人之血：副手的雙手斧、錘、劍（雙持；能不能生效同樣在算屬性表時看）
        final boolean offHandSceptre = base != null && slot == ItemSlot.OFF_HAND && base.slot() == ItemSlot.MAIN_HAND
                && (base.tags().contains("sceptre") || tw.exilecore.stats.KeystoneKeys.giantWeapon(base.tags()));
        if (base == null || base.slot() != slot && !offHandSceptre) {
            // 放錯欄位（例如把頭盔拿在手上）不算數
            return;
        }
        List<Modifier> modifiers = ItemStats.modifiers(plugin.items().repository(), data.get());
        if (instil && instilAllocated(state, data.get())) {
            // 2026-10（PoE2）：蒸餾是「配置這個核心」，天賦樹上已經點了就不再多給一份
            final ItemStats.Instil instilled = ItemStats.instilOf(data.get());
            modifiers = new ArrayList<>(modifiers);
            for (final Modifier mod : instilled.modifiers()) {
                modifiers.remove(mod);
            }
        }
        if (offHandSceptre) {
            // 副手的權杖只給精魂、詞綴與賦予的技能；武器本身的傷害、攻速、暴擊（本地）不算（攻擊用主手的獸魂符）
            // 2026-10-27 雙持（巨人之血）：副手武器本身的數值只在副手出手時算（DualWield，算屬性表時另外取）
            modifiers = modifiers.stream().filter(mod -> !DualWield.weaponLocal(mod)).toList();
        }
        list.add(new EquippedItem(base, data.get(), modifiers,
                ItemStats.requirements(plugin.items().repository(), base, data.get()), offHandSceptre));
    }

    /** 這件裝備注入的天賦核心，這個角色在天賦樹上已經配置了（注入的效果不重複算）。 */
    public static boolean instilAllocated(final PlayerState state, final ItemData data) {
        return data.instilled() != null && state.passives().contains(data.instilled()) && ItemStats.instilOf(data) != null;
    }

    /** offHandWeapon：2026-10-27 照 PoE2 荒野領主，裝在副手的權杖；巨人之血，裝在副手的雙手斧、錘、劍（主手武器的基底，放在副手）。 */
    private record EquippedItem(ItemBase base, ItemData data, List<Modifier> modifiers, ItemBase.Requirements requirements, boolean offHandWeapon) {
        EquippedItem(final ItemBase base, final ItemData data, final List<Modifier> modifiers, final ItemBase.Requirements requirements) {
            this(base, data, modifiers, requirements, false);
        }
    }

    /**
     * 身上一件生效的裝備（2026-10：技能的「裝備加成」用）。
     *
     * @param name        顯示名稱（傳奇是傳奇的名字）
     * @param color       稀有度顏色（MiniMessage）
     * @param slot        部位名稱
     * @param modifiers   這件的詞綴（有條件的拿掉條件，放進 conditional）
     * @param conditional 有條件才生效的那幾條（例如「最近沒被擊中時」）
     */
    public record GearPiece(String name, String color, String slot, List<Modifier> modifiers, Set<Modifier> conditional) {
    }

    /** 身上需求都符合（真的有在算）的裝備與詞綴。 */
    public List<GearPiece> activeGear(final Player player) {
        final PlayerState state = get(player);
        final StatSheet sheet = sheet(player);
        final double str = sheet.get(StatKeys.STR);
        final double dex = sheet.get(StatKeys.DEX);
        final double intelligence = sheet.get(StatKeys.INT);
        final List<GearPiece> list = new ArrayList<>();
        final List<Modifier> asc = plugin.ascendancy() == null || !state.hasCharacter() ? List.of() : plugin.ascendancy().ascension(state).modifiers();
        final double[] attr = tw.exilecore.ascendancy.AscBuild.requirementAttributes(asc, str, dex, intelligence); // 2026-10-26（昇華・嵌晶）
        for (final EquippedItem item : equippedItems(player, state, Gear.ringSlotOpen(sheet))) { // 2026-10-26：第三個戒指欄
            final double[] attrs = tw.exilecore.ascendancy.AscBuild.ignoresRequirements(asc, item.base().slot())
                    ? tw.exilecore.ascendancy.AscBuild.NO_REQUIREMENT : attr; // 2026-10-27（昇華・石拳之道）
            if (requirementProblem(scaledRequirements(item.requirements(), tw.exilecore.stats.TreeEffectKeys.requirementScale(sheet)),
                    state.level(), attrs[0], attrs[1], attrs[2]) != null
                    || tw.exilecore.ascendancy.AscBuild.itemProblem(asc, item.base().slot(), item.data()) != null) {
                continue;
            }
            final List<Modifier> mods = new ArrayList<>();
            final Set<Modifier> conditional = new java.util.HashSet<>();
            for (final Modifier raw : item.modifiers()) {
                if (tw.exilecore.stats.Recently.hasCondition(raw.stat())) {
                    final Modifier stripped = new Modifier(tw.exilecore.stats.Recently.stat(raw.stat()), raw.type(), raw.value(), raw.tags(), raw.source());
                    mods.add(stripped);
                    conditional.add(stripped);
                } else {
                    mods.add(raw);
                }
            }
            list.add(new GearPiece(ItemStats.displayName(plugin.items().repository(), item.data()), item.data().rarity().color(),
                    item.base().slot().displayName(), mods, conditional));
        }
        return list;
    }

    /** 把會影響原版行為的數值同步過去：血量上限固定、攻擊冷卻拉到無感、移動速度。 */
    private void syncVanillaAttributes(final Player player, final StatSheet sheet) {
        final AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
        if (health != null && health.getBaseValue() != VANILLA_MAX_HEALTH) {
            health.setBaseValue(VANILLA_MAX_HEALTH);
        }
        final AttributeInstance attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null && attackSpeed.getBaseValue() < 100) {
            attackSpeed.setBaseValue(100);
        }
        final AttributeInstance movement = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            // 2026-10-26：更多／更少的移動速度（引導、舉盾的減速）也算進去；這次之前只看增加，那些減速沒有作用
            final double increased = Math.max(0, 1 + sheet.increased(StatKeys.MOVEMENT_SPEED, Set.of()))
                    * sheet.more(StatKeys.MOVEMENT_SPEED, Set.of()) - 1;
            // 2026-10-27 效能：數值沒變就不動（每次拿掉再加會讓原版重算屬性、送封包）；這次之前每次重算屬性表都拿掉再加
            final AttributeModifier current = movement.getModifier(movementKey);
            final boolean same = current == null ? increased == 0
                    : current.getAmount() == increased && current.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            if (!same) {
                movement.removeModifier(movementKey);
                if (increased != 0) {
                    movement.addModifier(new AttributeModifier(movementKey, increased,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1));
                }
            }
        }
        if (movement != null && movement.getModifier(oldRunKey) != null) {
            // 以前的常駐奔跑（沒按衝刺也是衝刺的速度，2026-10 拿掉、改回原版：要按衝刺）留下的加成；暫時的修飾器不會存檔，
            // 只有插件熱更新時線上的人身上還會有
            movement.removeModifier(oldRunKey);
        }
    }

    // ---- 經驗與等級 ----

    /** 擊殺一隻怪物給的經驗：怪物等級表 × 稀有度倍率 × 等級差懲罰。 */
    public long experienceForKill(final int playerLevel, final int monsterLevel, final MonsterRarity rarity) {
        return Math.round(experienceForKillExact(playerLevel, monsterLevel, rarity));
    }

    /** 同上，不四捨五入（組隊分經驗時先分再取整）。 */
    public double experienceForKillExact(final int playerLevel, final int monsterLevel, final MonsterRarity rarity) {
        final double base = plugin.monsters().config().experienceAt(monsterLevel);
        final double multiplier = plugin.monsters().config().rarity(rarity).experienceMultiplier();
        return base * multiplier * config.levelPenalty(playerLevel, monsterLevel);
    }

    /** 給經驗並處理升級；沒角色或滿級時什麼都不做。回傳升了幾級。 */
    public int awardExperience(final Player player, final long amount) {
        final PlayerState state = get(player);
        final CharacterRecord character = state.character();
        if (character == null || amount <= 0 || character.level() >= config.levelCap()) {
            return 0;
        }
        long experience = character.experience() + amount;
        int level = character.level();
        int gained = 0;
        while (level < config.levelCap()) {
            final long need = config.experienceToNext(level);
            if (need <= 0 || experience < need) {
                break;
            }
            experience -= need;
            level++;
            gained++;
        }
        if (level >= config.levelCap()) {
            experience = 0;
        }
        character.setExperience(experience);
        if (gained > 0) {
            character.setLevel(level);
            state.markDirty();
            rebuild(player, state);
            announceLevelUp(player, state, gained);
            if (level >= config.levelCap() && plugin.discord() != null) {
                // 2026-10：滿級同步到 Discord
                plugin.discord().achievement(tw.exilecore.item.ItemRenderer.escape(character.name()) + "（" + tw.exilecore.item.ItemRenderer.escape(player.getName())
                        + "）升到了 " + config.levelCap() + " 等！");
            }
            for (final int at : RESIST_PENALTY_LEVELS) {
                if (level - gained < at && level >= at) {
                    player.sendMessage(plugin.text().prefixed("<#ff7a5a>到了等級 " + at + "：火焰、冰冷、閃電抗性 -" + RESIST_PENALTY_STEP
                            + "%<gray>（抗性懲罰，現在共 <white>-" + resistPenalty(state) + "%</white>）"));
                }
            }
            updateListName(player);
            if (plugin.quests() != null) {
                plugin.quests().refresh(player); // 任務：到達等級、等級夠了可以接的任務
            }
            if (plugin.ascendancy() != null) {
                plugin.ascendancy().onLevelUp(player, level - gained, level); // 分身獎勵多一階時說一聲
            }
        }
        return gained;
    }

    /**
     * 別人看到的名字是角色名稱（PoE 的做法）：
     * <ul>
     *   <li>TAB 列表：稱號、角色名稱、等級與職業（職業的顏色；昇華了寫昇華職業），例如「「星痕獵人」 暗影刺客 Lv.60 弓箭手」；
     *       還沒選角色的寫「選角中」與玩家 ID。</li>
     *   <li>聊天、加入離開的訊息（顯示名稱）：角色名稱；滑過去看等級、職業與玩家 ID，按一下私訊。</li>
     *   <li>私訊、組隊、交易用的名字表（聊天模組）。</li>
     * </ul>
     * 登入、換角色、升級、改名、昇華、換稱號時更新。
     */
    public void updateListName(final Player player) {
        final PlayerState state = get(player);
        final CharacterRecord character = state.isLoaded() ? state.character() : null;
        if (plugin.chat() != null) {
            plugin.chat().refresh(player, character == null ? null : character.name());
        }
        if (character == null) {
            player.playerListName(plugin.text().parse("<dark_gray>[選角中]</dark_gray> <gray>"
                    + tw.exilecore.item.ItemRenderer.escape(player.getName())));
            player.displayName(null);
            return;
        }
        // 稱號（功勳兌換的，帳號共用）
        final String title = plugin.bounties() == null ? "" : plugin.bounties().titleTag(player);
        // 2026-10：TAB「Lv.等級 │ 職業 │ 【稱號】名字」三欄對齊（每個人都要照最長的那個補空白，所以整張一起重排）
        refreshTabList();
        // 聊天的名字：【稱號】角色名稱（中間不空格；稱號沒有滑過去的說明，名字有）
        final net.kyori.adventure.text.Component nameText = nameComponent(player);
        // 2026-10：公會標籤放最前面「[標籤]【稱號】角色名稱」
        final String guildTag = plugin.guilds() == null ? "" : plugin.guilds().tag(player);
        player.displayName(title.isEmpty() && guildTag.isEmpty() ? nameText : net.kyori.adventure.text.Component.text()
                .append(plugin.text().parse(guildTag + title)).append(nameText.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE)).build());
        if (plugin.chat() != null) {
            plugin.chat().refreshNameplate(player); // 2026-10：頭上的名牌
        }
    }

    /** TAB 上次設的內容（沒變就不重送）。 */
    private final Map<UUID, String> tabShown = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * TAB 整張重排（2026-10）：三欄「Lv.等級」「職業」「【稱號】名字」，前兩欄照線上最寬的那個補空白對齊；
     * 等級高的排前面。原版字型沒有等寬的空白：用一般空白（4）和粗體空白（5）湊出要補的寬度。
     */
    public void refreshTabList() {
        final List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        final List<String[]> rows = new ArrayList<>();
        final List<Player> owners = new ArrayList<>();
        double classColumn = 0;
        double levelColumn = 0;
        final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer plain =
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (final Player each : online) {
            final PlayerState state = get(each);
            final CharacterRecord character = state != null && state.isLoaded() ? state.character() : null;
            if (character == null) {
                continue;
            }
            final String title = plugin.bounties() == null ? "" : plugin.bounties().titleTag(each);
            // 2026-10：「【稱號】名字」同一欄（【對齊），名字緊接在】後面；沒稱號的名字從同一欄開始
            // 2026-10：有公會的人名字後面接公會標籤（放後面：前面幾欄的對齊不用跟著動）
            final String guildTag = plugin.guilds() == null ? "" : plugin.guilds().tag(each);
            final String first = styledName(each, tw.exilecore.item.ItemRenderer.escape(character.name()))
                    + (guildTag.isEmpty() ? "" : " " + guildTag);
            final String level = "Lv." + character.level();
            final String classText = classText(state, character);
            final double classWidth = tw.exilecore.web.HudFont.estimateVanilla(plain.serialize(plugin.text().parse(classText)));
            final double levelWidth = tw.exilecore.web.HudFont.estimateVanilla(level);
            classColumn = Math.max(classColumn, classWidth);
            levelColumn = Math.max(levelColumn, levelWidth);
            rows.add(new String[] {first, level, classText, String.valueOf(classWidth), String.valueOf(levelWidth), title});
            owners.add(each);
            // 2026-10：管理員永遠置頂；2026-10-23：再來是贊助的人（累積贊助的階級高的在前），最後一般玩家；同一群裡照等級高的排前面
            final int sponsorRank = plugin.sponsors() == null ? 0 : plugin.sponsors().tierRank(each);
            final int order = (each.hasPermission("exilecore.admin") ? 100_000 : 0) + sponsorRank * 1_000 + character.level();
            if (each.getPlayerListOrder() != order) {
                each.setPlayerListOrder(order);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            final String[] row = rows.get(i);
            // 2026-10：「Lv.等級 職業 【稱號】名字」：長度固定的放前面，最長的名字放最後（不用為了名字留一大段空白）
            final String text = "<#f5b942>" + row[1] + "</#f5b942>" + pad(levelColumn - Double.parseDouble(row[4]) + TAB_GAP)
                    + row[2] + "<reset>" + pad(classColumn - Double.parseDouble(row[3]) + TAB_GAP)
                    + row[5] + "<reset>" + row[0]; // 稱號的【對齊在同一欄，名字直接接在】後面
            final Player each = owners.get(i);
            if (!text.equals(tabShown.get(each.getUniqueId()))) {
                tabShown.put(each.getUniqueId(), text);
                each.playerListName(plugin.text().parse(text));
            }
        }
        tabShown.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    /** 欄與欄之間至少空幾格（GUI 像素；≥ 12 才湊得出任何寬度）。 */
    private static final int TAB_GAP = 12;

    /** 補 width 像素的空白：一般空白 4、粗體空白 5，湊最接近的。 */
    private static String pad(final double width) {
        final int target = (int) Math.round(width);
        int bold = 0;
        int plainSpaces = 0;
        for (int b = 0; b <= 3; b++) {
            final int rest = target - 5 * b;
            if (rest >= 0 && rest % 4 == 0) {
                bold = b;
                plainSpaces = rest / 4;
                break;
            }
            if (b == 3) {
                plainSpaces = Math.max(0, Math.round(target / 4f));
            }
        }
        return " ".repeat(plainSpaces) + (bold > 0 ? "<bold>" + " ".repeat(bold) + "</bold>" : "");
    }

    /** 名字（已跳脫的 MiniMessage）套上累積贊助的名字顏色（沒有就是白色）。 */
    private String styledName(final Player player, final String escapedName) {
        // 2026-10 月卡福利：有月卡的人名字前面一個小月亮（TAB、聊天）
        final String moon = plugin.sponsors() != null && plugin.sponsors().cardActive(player) ? "<#bcd4ff>☾</#bcd4ff>" : "";
        final String badge = plugin.sponsors() == null ? "" : plugin.sponsors().nameBadge(player); // 2026-10-23 晚：最高階的 ★
        final String style = plugin.sponsors() == null ? null : plugin.sponsors().nameColor(player);
        if (style == null) {
            return badge + moon + "<white>" + escapedName + "</white>";
        }
        final String tag = style.startsWith("gradient:") ? "gradient" : style;
        return badge + moon + "<" + style + ">" + escapedName + "</" + tag + ">";
    }

    /**
     * 純文字的完整名字（2026-10，Discord 同步聊天用）：「【稱號】角色名稱《職業》Lv.等級」，不寫玩家 ID；
     * 還沒選角色回傳 null。
     */
    public String plainFullName(final Player player) {
        final PlayerState state = find(player.getUniqueId()).orElse(null); // 2026-10-18：只查不建（下線時 Discord 也會問，不能把狀態建回來）
        final CharacterRecord character = state != null && state.isLoaded() ? state.character() : null;
        if (character == null) {
            return null;
        }
        final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer plain =
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        final String title = plugin.bounties() == null ? "" : plain.serialize(plugin.text().parse(plugin.bounties().titleTag(player))).trim();
        final String classText = plain.serialize(plugin.text().parse(classText(state, character))).trim();
        return title + character.name() + "《" + classText + "》Lv." + character.level();
    }

    /** 職業那一段（昇華了就寫昇華職業，PoE 的做法：鐵衛、星矢…），MiniMessage。 */
    private String classText(final PlayerState state, final CharacterRecord character) {
        final String ascendancy = plugin.ascendancy() == null ? null : plugin.ascendancy().ascendancyName(state);
        final CharacterClass characterClass = character.characterClass();
        return ascendancy != null ? ascendancy : characterClass.color() + characterClass.displayName();
    }

    /**
     * 別人看到的這個人的名字：角色名稱，滑過去看等級、職業與玩家 ID，按一下私訊（聊天、死亡訊息共用）。
     * 還沒選角色回傳 null。
     */
    public net.kyori.adventure.text.Component nameComponent(final Player player) {
        final PlayerState state = find(player.getUniqueId()).orElse(null); // 2026-10-18：只查不建（死亡訊息等可能在下線前後問）
        final CharacterRecord character = state != null && state.isLoaded() ? state.character() : null;
        if (character == null) {
            return null;
        }
        final String name = tw.exilecore.item.ItemRenderer.escape(character.name());
        final net.kyori.adventure.text.Component hover = plugin.text().parse("<white>" + name + "\n<#f5b942>Lv." + character.level()
                + "</#f5b942> " + classText(state, character) + "\n<gray>玩家 ID：" + tw.exilecore.item.ItemRenderer.escape(player.getName())
                + "\n<dark_gray>按一下私訊");
        // 2026-10：聊天、死亡訊息裡的名字一律粗體（沒有贊助顏色的白色名字混在白色的訊息裡看不出來；贊助的顏色照舊）
        return plugin.text().parse("<b>" + styledName(player, name) + "</b>")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/w " + character.name() + " "));
    }

    private void announceLevelUp(final Player player, final PlayerState state, final int gained) {
        final int points = availablePoints(state);
        player.showTitle(Title.title(
                plugin.text().parse("<#f5b942>升級！"),
                plugin.text().parse("<white>等級 " + state.level() + "　<gray>可用天賦點 <white>" + points + "　<gray>輸入 <white>/tree</white> 配點"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        player.sendMessage(plugin.text().prefixed("<green>升到 " + state.level() + " 級" + (gained > 1 ? "（連升 " + gained + " 級）" : "")
                + "<gray>，可用天賦點 <white>" + points));
        if (plugin.sponsors() != null) {
            plugin.sponsors().celebrateLevel(player); // 2026-10-23：累積贊助的主打：升級放煙火
        }
    }

    /** 死亡懲罰（不在地圖裡）。 */
    public long applyDeathPenalty(final Player player) {
        return applyDeathPenalty(player, false);
    }

    /**
     * 死亡懲罰：扣目前等級所需經驗的一定比例，不會降級。回傳扣掉的經驗。
     * 劇情區域照 character.yml 的 death_penalty（預設 0），地圖裡照 map_death_penalty（預設 10%，同 PoE2）。
     */
    public long applyDeathPenalty(final Player player, final boolean inMap) {
        final PlayerState state = get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            return 0;
        }
        character.setDeaths(character.deaths() + 1);
        // 2026-10：統一——death_penalty_from_level（60）等以上在哪裡死都扣 map_death_penalty（10%：劇情、地圖、劫盜、世界 Boss…），
        // 以下都不扣（競技場本來就不扣，在呼叫的地方擋掉）
        final double ratio = deathPenaltyRatio(player, character.level());
        final long need = config.experienceToNext(character.level());
        if (ratio <= 0 || need <= 0) {
            return 0;
        }
        final long loss = Math.min(character.experience(), Math.round(need * ratio));
        character.setExperience(character.experience() - loss);
        return loss;
    }

    /** 這個人死亡扣的比例（現在的等級）：map_death_penalty，累積贊助有減免的照那一階（2026-10）。 */
    public double deathPenaltyRatio(final Player player) {
        final CharacterRecord character = get(player).character();
        return deathPenaltyRatio(player, character == null ? 1 : character.level());
    }

    /**
     * 這個人在這個等級死亡扣的比例（2026-10）：照等級（100 級起 20%、110 級起 30%…，character.yml 的 death_penalty_tiers），
     * 累積贊助的減免是固定值（贊助把 10% 減成 6% = 少 4%：20% 變 16%、30% 變 26%），未滿 death_penalty_from_level 不扣。
     */
    public double deathPenaltyRatio(final Player player, final int level) {
        final double base = config.deathPenaltyAt(level);
        final double normal = config.mapDeathPenalty();
        if (base <= 0 || plugin.sponsors() == null) {
            return base;
        }
        final double reduction = Math.max(0, normal - plugin.sponsors().deathPenalty(player, normal));
        return Math.max(0, base - reduction);
    }

    /** 管理指令：直接設定等級（經驗歸零）。 */
    public void setLevel(final Player player, final int level) {
        final PlayerState state = get(player);
        final CharacterRecord character = state.character();
        if (character != null) {
            character.setLevel(Math.max(1, Math.min(config.levelCap(), level)));
            character.setExperience(0);
        }
        state.markDirty();
        rebuild(player, state);
        state.restoreAll();
        display(player, state);
        updateListName(player);
    }

    /** 可用的天賦點 = 升級給的 + 額外點數 − 已配置。 */
    public int availablePoints(final PlayerState state) {
        final CharacterRecord character = state.character();
        if (character == null) {
            return 0;
        }
        return config.pointsFromLevel(character.level()) + character.bonusPoints() + extraPoints(state) - state.passives().size();
    }

    /** 從其他來源（任務）來的天賦點。 */
    public int extraPoints(final PlayerState state) {
        int total = 0;
        for (final java.util.function.ToIntFunction<PlayerState> source : pointSources) {
            total += source.applyAsInt(state);
        }
        return total;
    }

    /** 目前等級的經驗進度（0 到 1）；滿級為 1。 */
    public double experienceProgress(final PlayerState state) {
        final CharacterRecord character = state.character();
        if (character == null) {
            return 0;
        }
        final long need = config.experienceToNext(character.level());
        if (need <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, character.experience() / (double) need));
    }

    // ---- 每 tick 的回復與顯示 ----

    /** 每秒回復與護盾回充；由排程每 10 tick 呼叫一次。 */
    public void tick(final Player player, final PlayerState state, final long currentTick, final int ticksSinceLast) {
        conditionTracker.sample(player, state, currentTick); // 2026-10-26：天賦樹條件（移動、生命比例…）
        // 2026-10：「最近…」的條件變了（剛被打、剛翻滾、4 秒過了……）：重算屬性表
        // 2026-10-27 效能：條件造成的重算每人每秒最多一次（ConditionHold）；計時 stats.conditions，重算的原因分開記 stats.rebuild.cond／dirty
        boolean conditionFlip = false;
        if (state.hitConditions() && !state.isDirty() && conditionHold.ready(player.getUniqueId(), currentTick)) {
            final long start = System.nanoTime();
            conditionFlip = conditionsChanged(player, state, currentTick);
            plugin.perf().record("stats.conditions", System.nanoTime() - start);
            if (conditionFlip) {
                conditionHold.flipped(player.getUniqueId(), currentTick);
                state.markDirty();
            }
        }
        if (state.isDirty()) {
            final long start = System.nanoTime();
            rebuild(player, state);
            plugin.perf().record(conditionFlip ? "stats.rebuild.cond" : "stats.rebuild.dirty", System.nanoTime() - start);
        }
        if (state.isDead() || player.isDead()) {
            return;
        }
        final double seconds = ticksSinceLast / 20.0;
        final StatSheet sheet = state.sheet();
        // 每秒回復：固定值 + 最大生命的 x%（生機光環）
        // 野林堅持（儀祭者，PoE2）：每少 5% 生命，生命回復速度增加 x%
        final double perMissing = sheet.flat(StatKeys.LIFE_RECOVERY_PER_MISSING, Set.of());
        final double missingFactor = perMissing <= 0 || state.maxLife() <= 0 ? 1.0
                : 1 + perMissing / 100.0 * Math.floor((1 - state.life() / state.maxLife()) * 100 / 5);
        // 2026-10：荒蕪（賽勒斯的黑水）——禁療：生命回復、偷取、護盾回充全部停掉；藥劑照喝（那是唯一的活路）
        final boolean ruined = state.ruined(currentTick);
        if (ruined) {
            state.notice("<#b06cff>☠ 荒蕪：無法回復生命（離開黑色地面）", currentTick + 15);
        }
        // 2026-10-26 照 PoE2 天賦樹：「增加 x% 生命回復速度」——生命再生、偷取、回復都快 x%
        // 2026-10-27：魔力回復速度（mana.recovery_rate）同理：魔力再生、偷取、回復都快 x%；魔力再生速度（mana.regen）只加再生。更多／更少也算
        final double lifeRate = tw.exilecore.stats.TreeEffectKeys.recoveryRate(sheet, tw.exilecore.stats.TreeEffectKeys.LIFE_RECOVERY_RATE);
        final double manaRate = tw.exilecore.stats.TreeEffectKeys.recoveryRate(sheet, tw.exilecore.stats.TreeEffectKeys.MANA_RECOVERY_RATE);
        final double regen = ruined ? 0
                : (sheet.get(StatKeys.LIFE_REGEN) + state.maxLife() * sheet.get(StatKeys.LIFE_REGEN_PERCENT) / 100.0
                        + tw.exilecore.passive.ConquestEffects.bloodRegen(player.getUniqueId(), sheet, currentTick)) // 2026-10-26：鮮血獻祭
                        * seconds * missingFactor * lifeRate;
        final double overflow = Math.max(0, state.life() + regen - state.maxLife());
        // 2026-10-26 照 PoE2 基石：創傷信仰——能量護盾不回充、不能用再生回復
        final boolean scarred = StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_SCARRED_FAITH);
        if (scarred && StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.REGEN_TO_ES)) {
            // 再生改成回護盾的那部分沒了
        } else if (StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.REGEN_TO_ES)) {
            state.setEnergyShield(state.energyShield() + regen); // 2026-10-26（空殼護符）：生命回復改成回復能量護盾
        } else {
            state.setLife(state.life() + regen);
        }
        final boolean zealot = StatKeys.flag(sheet, StatKeys.FLAG_ZEALOTS_OATH);
        final boolean youth = StatKeys.flag(sheet, StatKeys.FLAG_ETERNAL_YOUTH);
        if (zealot && !scarred && overflow > 0 && state.maxEnergyShield() > 0) {
            state.setEnergyShield(state.energyShield() + Math.min(overflow, Math.max(0, regen))); // 狂熱誓言：溢出的生命回復變成護盾
        }
        final double leechRate = sheet.apply(StatKeys.LEECH_RATE, 1.0, java.util.Set.of()); // 最大偷取速度（2026-10）
        if (!ruined) {
            state.recoverLeech(seconds * lifeRate, leechRate); // 2026-10-27：回復速度讓每份偷取回得更快（上限也一起）；這次之前只放大每秒上限
        }
        // 2026-10-26 照 PoE2：回復（Recoup）4 秒內平均回來；禁療中生命、護盾那份不回（魔力照回）
        final double[] recouped = state.recoup().tick(ticksSinceLast);
        if (!ruined) {
            state.setLife(state.life() + recouped[tw.exilecore.character.Recoup.LIFE]);
            // 2026-10-27：創傷信仰回復的護盾（ES_CAPPED）不超過護甲；一般的護盾回復不夾（這次之前有創傷信仰時整份都夾在護甲）
            state.setEnergyShield(tw.exilecore.character.Recoup.cappedEs(state.energyShield(), recouped[tw.exilecore.character.Recoup.ES],
                    recouped[tw.exilecore.character.Recoup.ES_CAPPED], sheet.get(StatKeys.ARMOUR)));
        }
        state.setMana(state.mana() + recouped[tw.exilecore.character.Recoup.MANA]);
        if (StatKeys.flag(sheet, tw.exilecore.passive.ConquestEffects.MIND)) {
            state.recoverManaOverflowing(tw.exilecore.stats.TreeEffectKeys.manaRegen(sheet) * seconds); // 2026-10-26 照 PoE2 心靈獻祭：回復的魔力溢出最大魔力
        } else {
            state.setMana(state.mana() + tw.exilecore.stats.TreeEffectKeys.manaRegen(sheet) * seconds);
        }
        if (!ruined) {
            state.recoverManaLeech(seconds * manaRate, leechRate); // 2026-10-18：荒蕪禁療中魔力偷取也停（以前照回）；2026-10-27 照 PoE2 吃魔力回復速度
        }
        if (youth && !ruined && currentTick - state.lastDamageTick() >= esRechargeDelay(sheet)) {
            // 永恆青春：生命像護盾一樣回充（護盾不回充）
            state.setLife(state.life() + Formulas.esRechargePerSecond(state.maxLife(), esRechargeSpeed(sheet)) * seconds);
        }
        if (!zealot && !youth && !scarred && !ruined && state.maxEnergyShield() > 0 && currentTick - state.lastDamageTick() >= esRechargeDelay(sheet)
                && !tw.exilecore.skill.TreeLeftovers.esRechargeBlocked(plugin, player, currentTick)) { // 2026-10-27 照 PoE2 天賦樹：被詛咒的不能回充（PvP）
            // 延遲 4 秒過後每秒回充 12.5%（回充速度、延遲都吃詞綴）
            state.setEnergyShield(state.energyShield() + Formulas.esRechargePerSecond(state.maxEnergyShield(), esRechargeSpeed(sheet)) * seconds);
        }
        grace.tick(player, state); // 入場保護：走離落點就解除
        display(player, state);
    }

    /** 從這件裝備的修飾器裡加總某個屬性的固定值（基底數值，例如胸甲的護甲）。 */
    private static double itemFlat(final EquippedItem item, final String stat) {
        double total = 0;
        for (final Modifier mod : item.modifiers()) {
            if (stat.equals(mod.stat()) && mod.type() == ModType.FLAT && mod.tags().isEmpty()) {
                total += mod.value();
            }
        }
        return total;
    }

    /**
     * 2026-10-26 照 PoE2 天賦樹：「增加 x% 裝備的盾的護甲、閃避、護盾」「增加 x% 胸甲的護甲」「增加 x% 法器的護盾」
     * （照基底數值加，跟裝備本地的增加相加）、「增加 x% 箭袋的加成」（箭袋上每條詞綴放大）。
     */
    private static void treeEquipment(final StatSheet sheet, final EquippedItem item) {
        final ItemBase base = item.base();
        final Set<String> none = Set.of();
        if (base.isShield() && !base.tags().contains("focus")) {
            final double inc = sheet.increased(tw.exilecore.stats.TreeEffectKeys.SHIELD_DEFENCES, none);
            if (inc != 0) {
                sheet.add(Modifier.flat(StatKeys.ARMOUR, base.armour() * inc, "盾的防禦"));
                sheet.add(Modifier.flat(StatKeys.EVASION, base.evasion() * inc, "盾的防禦"));
                sheet.add(Modifier.flat(StatKeys.ES_MAX, base.energyShield() * inc, "盾的防禦"));
            }
        }
        if (base.slot() == tw.exilecore.item.ItemSlot.CHEST) {
            final double inc = sheet.increased(tw.exilecore.stats.TreeEffectKeys.BODY_ARMOUR, none);
            if (inc != 0) {
                sheet.add(Modifier.flat(StatKeys.ARMOUR, base.armour() * inc, "胸甲的護甲"));
            }
        }
        if (base.tags().contains("focus")) {
            final double inc = sheet.increased(tw.exilecore.stats.TreeEffectKeys.FOCUS_ES, none);
            if (inc != 0) {
                sheet.add(Modifier.flat(StatKeys.ES_MAX, base.energyShield() * inc, "法器的護盾"));
            }
        }
        if (base.tags().contains("quiver")) {
            final double inc = sheet.increased(tw.exilecore.stats.TreeEffectKeys.QUIVER_EFFECT, none);
            if (inc != 0) {
                sheet.addAll(tw.exilecore.stats.TreeEffectKeys.amplify(item.modifiers(), inc, "箭袋效果"));
            }
        }
    }

    /** 2026-10-26（AttributeScalingKeys）：胸甲的基底閃避、護盾，頭盔、護腿（PoE2 的手套）、鞋子的力量需求總和。 */
    private static tw.exilecore.stats.AttributeScalingKeys.Gear scalingGear(final List<EquippedItem> active,
                                                                           final List<tw.exilecore.stats.KeystoneKeys.Piece> pieces) {
        double evasion = 0;
        double es = 0;
        double str = 0;
        for (final EquippedItem item : active) {
            final tw.exilecore.item.ItemSlot slot = item.base().slot();
            if (slot == tw.exilecore.item.ItemSlot.CHEST) {
                evasion = item.base().evasion();
                es = item.base().energyShield();
            } else if ((slot == tw.exilecore.item.ItemSlot.HEAD || slot == tw.exilecore.item.ItemSlot.LEGS || slot == tw.exilecore.item.ItemSlot.FEET)
                    && item.requirements() != null) {
                str += item.requirements().str();
            }
        }
        return new tw.exilecore.stats.AttributeScalingKeys.Gear(pieces, evasion, es, str);
    }

    /** 2026-10-26：技能面板上插著的輔助寶石顏色（SupportColours）。 */
    private static tw.exilecore.stats.AttributeScalingKeys.Supports supportCounts(final PlayerState state) {
        final tw.exilecore.skill.SupportColours.Counts counts = tw.exilecore.skill.SupportColours.count(state.skills());
        return new tw.exilecore.stats.AttributeScalingKeys.Supports(counts.red(), counts.green(), counts.blue(), counts.total());
    }

    /** PoE2 昇華裡跟裝備、天賦小點有關的效果（旗標來自昇華，在裝備之後才知道）。 */
    private static void ascendancyEquipment(final StatSheet sheet, final PlayerState state, final List<EquippedItem> active, final PassiveTree tree) {
        // 2026-10-26 照 PoE2 天賦：身體護甲的本地效果、「護甲等同鞋子、手套、頭盔力量需求的 x%」
        final double fromStr = sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.ARMOUR_FROM_STR_REQUIREMENT, java.util.Set.of());
        final double bodyThorns = sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.BODY_ARMOUR_THORNS, java.util.Set.of());
        final double bodyEs = sheet.increased(tw.exilecore.stats.TreeNewMechanicKeys.BODY_ES, java.util.Set.of());
        final double bodyEvasion = sheet.increased(tw.exilecore.stats.TreeNewMechanicKeys.BODY_EVASION, java.util.Set.of());
        for (final EquippedItem item : active) {
            final tw.exilecore.item.ItemSlot slot = item.base().slot();
            if (fromStr > 0 && item.requirements() != null && (slot == tw.exilecore.item.ItemSlot.HEAD || slot == tw.exilecore.item.ItemSlot.LEGS
                    || slot == tw.exilecore.item.ItemSlot.FEET)) {
                sheet.add(Modifier.flat(StatKeys.ARMOUR, item.requirements().str() * fromStr / 100.0, "力量需求的護甲"));
            }
            if (slot == tw.exilecore.item.ItemSlot.CHEST) {
                if (bodyThorns > 0) {
                    final double thorns = itemFlat(item, StatKeys.ARMOUR) * bodyThorns / 100.0;
                    sheet.add(Modifier.flat(StatKeys.THORNS_MIN, thorns, "身體護甲的荊棘"));
                    sheet.add(Modifier.flat(StatKeys.THORNS_MAX, thorns, "身體護甲的荊棘"));
                }
                // 「增加 x% 身體護甲的能量護盾／閃避」跟全域的增加相加（固定值先除掉全域的倍率）
                if (bodyEs != 0) {
                    sheet.add(Modifier.flat(StatKeys.ES_MAX, itemFlat(item, StatKeys.ES_MAX) * bodyEs
                            / Math.max(0.1, 1 + sheet.increased(StatKeys.ES_MAX, java.util.Set.of())), "身體護甲"));
                }
                if (bodyEvasion != 0) {
                    sheet.add(Modifier.flat(StatKeys.EVASION, itemFlat(item, StatKeys.EVASION) * bodyEvasion
                            / Math.max(0.1, 1 + sheet.increased(StatKeys.EVASION, java.util.Set.of())), "身體護甲"));
                }
            }
            treeEquipment(sheet, item);
            if (slot == tw.exilecore.item.ItemSlot.CHEST && StatKeys.flag(sheet, StatKeys.FLAG_STONE_SKIN)) {
                sheet.add(Modifier.flat(StatKeys.ARMOUR, itemFlat(item, StatKeys.ARMOUR) * 0.5, "岩膚"));
            }
            if (StatKeys.flag(sheet, StatKeys.FLAG_STALKING_PANTHER)) {
                final double evasion = itemFlat(item, StatKeys.EVASION);
                if (slot == tw.exilecore.item.ItemSlot.HEAD || slot == tw.exilecore.item.ItemSlot.LEGS || slot == tw.exilecore.item.ItemSlot.FEET) {
                    sheet.add(Modifier.flat(StatKeys.EVASION, evasion, "林豹"));
                } else if (slot == tw.exilecore.item.ItemSlot.CHEST) {
                    sheet.add(Modifier.flat(StatKeys.EVASION, -evasion * 0.5, "林豹"));
                }
            }
            if (slot == tw.exilecore.item.ItemSlot.CHEST && StatKeys.flag(sheet, StatKeys.FLAG_CRIMSON_POWER)) {
                sheet.add(Modifier.flat(StatKeys.LIFE_MAX, itemFlat(item, StatKeys.ES_MAX), "赤力"));
            }
            if (slot == tw.exilecore.item.ItemSlot.OFF_HAND && StatKeys.flag(sheet, StatKeys.FLAG_RENLY) && item.base().block() > 0) {
                // 2026-10-23：盾上寫的格擋率含本地的「增加 x% 格擋率」：基礎改成 35 時一樣照比例放大
                final double shieldBlock = itemFlat(item, StatKeys.BLOCK_CHANCE);
                final double scale = shieldBlock > 0 ? shieldBlock / item.base().block() : 1;
                sheet.add(Modifier.flat(StatKeys.BLOCK_CHANCE, 35 * scale - (shieldBlock > 0 ? shieldBlock : item.base().block()), "盾術精修"));
            }
            if ((slot == tw.exilecore.item.ItemSlot.RING || slot == tw.exilecore.item.ItemSlot.AMULET) && StatKeys.flag(sheet, StatKeys.FLAG_MYSTIC_ATTUNEMENT)) {
                for (final Modifier mod : item.modifiers()) {
                    if (mod.type() != ModType.MORE && !mod.stat().startsWith("flag.")) {
                        sheet.add(new Modifier(mod.stat(), mod.type(), mod.value() * 0.25, mod.tags(), "祭器共鳴"));
                    }
                }
            }
        }
        // 巨人之形：天賦樹小點的效果增加 50%
        if (tree != null && StatKeys.flag(sheet, StatKeys.FLAG_HULKING_FORM)) {
            for (final String id : state.passives()) {
                final tw.exilecore.passive.PassiveNode node = tree.node(id);
                if (node == null || node.type() != tw.exilecore.passive.PassiveNode.Type.SMALL) {
                    continue;
                }
                for (final Modifier raw : node.modifiers()) {
                    for (final Modifier mod : StatKeys.ATTR_CHOICE.equals(raw.stat())
                            ? choiceModifiers(chosenAttribute(state, state.characterClass(), id), raw.value(), raw.tags(), raw.source())
                            : List.of(raw)) {
                        if (mod.type() != ModType.MORE && !mod.stat().startsWith("flag.")) {
                            sheet.add(new Modifier(mod.stat(), mod.type(), mod.value() * 0.5, mod.tags(), "巨像化"));
                        }
                    }
                }
            }
        }
    }

    /**
     * 天賦樹的屬性點（2026-10 PoE2）：「+5 任一屬性」照玩家選的換成力量／敏捷／智慧（角色進度 attr.<節點>）；
     * 沒選過的用職業的主屬性。
     */
    public static List<Modifier> attributeChoices(final PlayerState state, final tw.exilecore.character.CharacterClass characterClass,
                                                  final List<Modifier> mods, final PassiveTree tree) {
        if (tree == null) {
            return new ArrayList<>(mods);
        }
        final List<Modifier> out = new ArrayList<>();
        for (final Modifier mod : mods) {
            if (!StatKeys.ATTR_CHOICE.equals(mod.stat())) {
                out.add(mod);
            }
        }
        for (final String id : state.passives()) {
            final tw.exilecore.passive.PassiveNode node = tree.node(id);
            if (node == null) {
                continue;
            }
            for (final Modifier mod : node.modifiers()) {
                if (StatKeys.ATTR_CHOICE.equals(mod.stat())) {
                    out.addAll(choiceModifiers(chosenAttribute(state, characterClass, id), mod.value(), Set.of(), node.name()));
                }
            }
        }
        return out;
    }

    /** 2026-10-26 照 PoE2 旅者的智慧（開路者）：屬性點可以改給傷害（dmg）、防禦（def）、消耗效率（cost）。 */
    public static final List<String> WISDOM_CHOICES = List.of("dmg", "def", "cost");

    /**
     * 屬性點選的效果：力量／敏捷／智慧給 +x 點；旅者的智慧的傷害／防禦／消耗效率給增加 x%（+5 屬性 → 增加 5%，
     * 珠寶放大、巨像化照比例）。這次之前只有三種屬性。
     */
    public static List<Modifier> choiceModifiers(final String choice, final double value, final Set<String> tags, final String source) {
        final double percent = value / 100.0;
        return switch (choice) {
            case "dmg" -> List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, percent, tags, source));
            case "def" -> List.of(new Modifier(StatKeys.ARMOUR, ModType.INCREASED, percent, tags, source),
                    new Modifier(StatKeys.EVASION, ModType.INCREASED, percent, tags, source),
                    new Modifier(StatKeys.ES_MAX, ModType.INCREASED, percent, tags, source));
            // 2026-10-27 照 PoE2：消耗效率（消耗 ÷ (1 + 效率)，CostKeys）；這次之前近似成 skill.cost 減少 x%
            case "cost" -> List.of(new Modifier(tw.exilecore.stats.CostKeys.COST_EFFICIENCY, ModType.INCREASED, percent, tags, source));
            default -> List.of(new Modifier(attributeStat(choice), ModType.FLAT, value, tags, source));
        };
    }

    /** 這個屬性點選了哪個屬性（str／dex／int；旅者的智慧另外有 dmg／def／cost，沒有那個昇華天賦時當沒選）。 */
    public static String chosenAttribute(final PlayerState state, final tw.exilecore.character.CharacterClass characterClass, final String nodeId) {
        final String chosen = state.progress().get("attr." + nodeId);
        if ("str".equals(chosen) || "dex".equals(chosen) || "int".equals(chosen)) {
            return chosen;
        }
        if (chosen != null && WISDOM_CHOICES.contains(chosen) && wisdom(state)) {
            return chosen;
        }
        if (characterClass == null) {
            return "str";
        }
        final int s = characterClass.strength();
        final int d = characterClass.dexterity();
        final int i = characterClass.intelligence();
        return s >= d && s >= i ? "str" : d >= i ? "dex" : "int";
    }

    /** 有沒有配旅者的智慧（看角色進度裡配好的昇華天賦；不看屬性表，重算屬性表時也能用）。 */
    private static boolean wisdom(final PlayerState state) {
        final String nodes = state.progress().get("asc.nodes"); // Ascension.NODES：逗號分隔的昇華天賦代號
        return nodes != null && java.util.Arrays.stream(nodes.split(",")).anyMatch(id -> "travellers_wisdom".equals(id.trim()));
    }

    public static String attributeStat(final String key) {
        return switch (key) {
            case "dex" -> StatKeys.DEX;
            case "int" -> StatKeys.INT;
            default -> StatKeys.STR;
        };
    }

    /** 能量護盾的回充速度倍率（1 = 基礎；增加、更多都算）。 */
    public static double esRechargeSpeed(final StatSheet sheet) {
        return Math.max(0, sheet.apply(StatKeys.ES_RECHARGE, 1.0, Set.of()));
    }

    /** 被打之後要等幾 tick 才開始回充（基礎 40，「延遲縮短」最多縮到兩成）。 */
    public static long esRechargeDelay(final StatSheet sheet) {
        return Formulas.esRechargeDelayTicks(sheet.apply(StatKeys.ES_DELAY, 1.0, Set.of()));
    }

    /** 把生命／魔力／護盾投影到原版顯示。 */
    public void display(final Player player, final PlayerState state) {
        if (player.isDead()) {
            return;
        }
        final double ratio = state.life() / state.maxLife();
        final double vanillaHealth = state.life() <= 0 ? 0 : Math.max(1.0, Math.round(ratio * VANILLA_MAX_HEALTH));
        // 剛上線（還沒選角、屬性還沒同步）時原版血量上限可能還是 20：先補成 40，再照實際上限夾住，不然 setHealth 會丟例外
        final AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getBaseValue() != VANILLA_MAX_HEALTH) {
            maxHealth.setBaseValue(VANILLA_MAX_HEALTH);
        }
        final double cap = maxHealth == null ? VANILLA_MAX_HEALTH : Math.min(VANILLA_MAX_HEALTH, maxHealth.getValue());
        if (vanillaHealth > 0 && Math.abs(player.getHealth() - Math.min(cap, vanillaHealth)) >= 0.5) {
            player.setHealth(Math.min(cap, vanillaHealth));
        }
        final double maxEs = state.maxEnergyShield();
        final double absorption = maxEs <= 0 ? 0 : Math.round(20.0 * state.energyShield() / maxEs);
        if (Math.abs(player.getAbsorptionAmount() - absorption) >= 0.5) {
            player.setAbsorptionAmount(absorption);
        }
        final double maxMana = state.maxMana();
        player.setLevel((int) Math.round(state.mana()));
        player.setExp(maxMana <= 0 ? 0f : (float) Math.max(0, Math.min(0.999, state.mana() / maxMana)));
        player.sendActionBar(actionBar(player, state));
        resourceBar.update(player, state);
    }

    /**
     * 2026-10-23：隊伍框（接在動作列後面的那段）；沒有隊伍、沒套資源包、關掉了是 null。
     * 別的動作列提示（{@link tw.exilecore.gui.ActionBar#send}）也接上，提示出現時隊伍框才不會不見。
     */
    public Component partyFrames(final Player player) {
        if (plugin.web() == null || !plugin.web().hasPack(player)) {
            return null;
        }
        final PlayerState state = states.get(player.getUniqueId());
        return state == null ? null : partyHud.render(player, state);
    }

    private Component actionBar(final Player player, final PlayerState state) {
        // 2026-10-22 效能（spark：動作列 1.9%，大半是 MiniMessage 解析）：生命、護盾、魔力、等級這段數字幾乎每次都不一樣，
        // 快取一直落空——改成直接組元件（不經過 MiniMessage、不用 String.format）；後面那段（異常、光環、通知…）才解析，而且很少變
        final net.kyori.adventure.text.TextComponent.Builder stats = Component.text();
        final StringBuilder statsPlain = new StringBuilder();
        stat(stats, statsPlain, "", LIFE_COLOR, "❤ " + grouped(Math.round(state.life())) + " / " + grouped(Math.round(state.maxLife())));
        if (state.maxEnergyShield() > 0) {
            stat(stats, statsPlain, "  ", ES_COLOR, "⛨ " + grouped(Math.round(state.energyShield())) + " / " + grouped(Math.round(state.maxEnergyShield())));
        }
        stat(stats, statsPlain, "  ", MANA_COLOR, "◆ " + grouped(Math.round(state.mana())) + " / " + grouped(Math.round(state.maxMana())));
        if (state.hasCharacter()) {
            stat(stats, statsPlain, "  ", LEVEL_COLOR, "Lv." + state.level() + " ");
            final String progress;
            if (state.level() >= config.levelCap()) {
                progress = "滿級";
            } else {
                final long tenths = Math.round(experienceProgress(state) * 1000);
                progress = tenths / 10 + "." + tenths % 10 + "%";
            }
            stats.append(Component.text(progress, net.kyori.adventure.text.format.NamedTextColor.GRAY));
            statsPlain.append(progress);
        }
        // 後面那段接在數字後面：以前整行一起解析時沒寫顏色的字會沿用前面的顏色（有角色是灰色，沒有是魔力的藍色）
        final StringBuilder text = new StringBuilder(state.hasCharacter() ? "<gray>" : "<#5599ff>");
        if (plugin.ailments() != null) {
            final String ailments = plugin.ailments().statusText(player);
            if (!ailments.isEmpty()) {
                text.append("  ").append(ailments); // 點燃、冰緩、凍結、感電
            }
        }
        // 2026-10：開著的光環（快捷欄左邊）、召喚物數量（快捷欄右邊）；沒套資源包的人照舊寫在這一行
        final List<String> auraNames = plugin.skills() == null ? List.of() : plugin.skills().auraNames(player);
        final List<tw.exilecore.skill.MinionService.HudCount> pets = plugin.skills() == null ? List.of() : plugin.skills().minionCounts(player);
        // 2026-10-20：增益倒數（技能格上方、快捷欄左邊）
        final tw.exilecore.skill.SkillService.HudBuffs hudBuffs = plugin.skills() == null
                ? tw.exilecore.skill.SkillService.HudBuffs.EMPTY
                : plugin.skills().hudBuffs(player, plugin.combat() != null && plugin.combat().hud() != null
                        && plugin.combat().hud().fighting(player.getUniqueId()));
        final boolean sides = (!auraNames.isEmpty() || !pets.isEmpty() || !hudBuffs.isEmpty()) && tw.exilecore.web.HudFont.ready()
                && plugin.web() != null && plugin.web().hasPack(player);
        if (!sides) {
            final String inline = HotbarSides.inline(auraNames, pets, hudBuffs);
            if (!inline.isEmpty()) {
                text.append("  ").append(inline);
            }
        }
        // 2026-10：怒氣、連擊、灌注、獸性與戰星搬到畫面上方的戰鬥資源血條（ResourceBar），動作列不再寫
        if (state.isSafe()) {
            text.append("  ").append(EntryGrace.HUD); // 入場保護
        }
        final String notice = state.notice(plugin.getServer().getCurrentTick());
        if (notice != null) {
            text.append("  ").append(notice);
        }
        final String mainPlain = statsPlain + cachedPlain(player, text.toString());
        if (sides) {
            final String side = HotbarSides.render(auraNames, pets, hudBuffs, mainPlain);
            if (side != null) {
                text.append(side);
            } else {
                text.append("  ").append(HotbarSides.inline(auraNames, pets, hudBuffs)); // 有字沒收進字型（新加的技能名稱）：照舊
            }
        }
        // 2026-10：撿到東西的角落提示（快捷欄右邊往上疊；2026-10-23：有召喚物時放在召喚物那一欄的右邊）
        if (tw.exilecore.web.LootHud.ready() && plugin.web() != null && plugin.web().hasPack(player)) {
            final long now = plugin.getServer().getCurrentTick();
            final List<LootFeed.Entry> loot = lootFeed.active(player.getUniqueId(), now);
            if (!loot.isEmpty()) {
                text.append(HotbarSides.renderLoot(loot, HotbarSides.lootStart(pets, sides), mainPlain, now));
            }
        }
        final Component main = stats.append(cachedParse(player, text.toString())).build();
        // 2026-10-22：畫面左上角的隊伍框（PartyHud，2026-10-18 做好了但一直沒接上動作列）：接在最後面，淨寬 0
        if (plugin.web() != null && plugin.web().hasPack(player)) {
            final Component frames = partyHud.render(player, state);
            if (frames != null) {
                return Component.text().append(main).append(frames).build();
            }
        }
        return main;
    }

    private static final net.kyori.adventure.text.format.TextColor LIFE_COLOR = net.kyori.adventure.text.format.TextColor.color(0xff5555);
    private static final net.kyori.adventure.text.format.TextColor ES_COLOR = net.kyori.adventure.text.format.TextColor.color(0x77ccff);
    private static final net.kyori.adventure.text.format.TextColor MANA_COLOR = net.kyori.adventure.text.format.TextColor.color(0x5599ff);
    private static final net.kyori.adventure.text.format.TextColor LEVEL_COLOR = net.kyori.adventure.text.format.TextColor.color(0xf5b942);

    /** 動作列的一段數字（前面的空白沒有顏色，跟以前的 MiniMessage 一樣）。 */
    private static void stat(final net.kyori.adventure.text.TextComponent.Builder out, final StringBuilder plain, final String gap,
                             final net.kyori.adventure.text.format.TextColor color, final String body) {
        if (!gap.isEmpty()) {
            out.append(Component.text(gap));
            plain.append(gap);
        }
        out.append(Component.text(body, color));
        plain.append(body);
    }

    /** 千分位（跟 String.format 的 %,d 一樣，但不用 Formatter；spark 0.2%）。 */
    static String grouped(final long value) {
        final String digits = Long.toString(Math.abs(value));
        final StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        if (value < 0) {
            out.append('-');
        }
        final int first = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
        out.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return out.toString();
    }

    /** 2026-10 效能（spark：動作列每 tick 解析 MiniMessage 兩次）：字一樣就沿用上一次的結果。 */
    private record Parsed(String text, Object value) {
    }

    private final Map<UUID, Parsed> lastPlain = new java.util.HashMap<>();
    private final Map<UUID, Parsed> lastBar = new java.util.HashMap<>();

    private String cachedPlain(final Player player, final String text) {
        final Parsed cached = lastPlain.get(player.getUniqueId());
        if (cached != null && cached.text().equals(text)) {
            return (String) cached.value();
        }
        final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(plugin.text().parse(text));
        trimCaches();
        lastPlain.put(player.getUniqueId(), new Parsed(text, plain));
        return plain;
    }

    private Component cachedParse(final Player player, final String text) {
        final Parsed cached = lastBar.get(player.getUniqueId());
        if (cached != null && cached.text().equals(text)) {
            return (Component) cached.value();
        }
        final Component parsed = plugin.text().parse(text);
        trimCaches();
        lastBar.put(player.getUniqueId(), new Parsed(text, parsed));
        return parsed;
    }

    /** 下線的人留下的快取清掉（人數多出不少時才掃）。 */
    private void trimCaches() {
        if (lastBar.size() + lastPlain.size() > 2 * plugin.getServer().getOnlinePlayers().size() + 64) {
            lastBar.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
            lastPlain.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        }
    }

    /** 全部在線玩家的狀態。 */
    public Map<UUID, PlayerState> all() {
        return states;
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
