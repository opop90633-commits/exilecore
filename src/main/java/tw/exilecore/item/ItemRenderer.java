package tw.exilecore.item;

import com.google.common.collect.HashMultimap;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.map.MapStats;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 ItemData 畫成玩家看到的物品：材質、名稱、描述、旗標。
 * 所有原版屬性行都隱藏、原版屬性全部清空（傷害由我們的引擎算），物品不可破壞、不可堆疊。
 */
public final class ItemRenderer {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * 外觀的版本：畫法改了（描述的格式、武器的元件）就加一，舊的物品在角色載入、拿在手上時重畫（ItemService.refresh）。
     * 2 = 詞綴顯示排名、地圖名稱帶階級、武器的攻擊距離元件（按住左鍵連續施放）。
     * 3 = 詞綴的數值後面接著範圍（「+15(10–19) 最大生命」）。
     * 4 = 附加傷害分成「攻擊附加」與「法術附加」。
     * 5 = 傳奇的專屬外觀等後來的外觀調整。
     * 6 = 武器本身的詞綴（本地加成）：武器數值改過的畫成藍色、暴擊率顯示兩位小數。
     */
    public static final int RENDER_VERSION = 33; // 33：說明長行自動換行（core.text.TextWrap，2026-10-26）；32：腰帶的簡圖照固定詞綴分（2026-10-24）；31：藥劑、護符的詞綴標階級 (T1…)（2026-10-24）；30：普通、魔法、稀有裝備在格子裡畫簡圖（plain:種類，2026-10-24）；29：遺時珠寶說明寫「也各給」最多算 15 個小天賦（2026-10-21 晚）；28：傳奇以外的裝備不用畫的圖、改回原版（2026-10-21 晚）；27：一般裝備、地圖、藥劑、護符、珠寶的圖（base:基底代號，2026-10-21）；26：飾品上的偷取吃攻擊、生命催化劑（2026-10-19）；25：其他標籤也照 PoE2 的資料對過（93 條）；24：攻擊、法術標籤照 PoE2 的資料對過一輪（50 條），遺時珠寶的詞綴也有標籤；23：箭袋「弓技能傷害」的標籤照 PoE2（只有傷害）；22：詞綴後來多的變數補上（AffixUpgrade），舊物品重畫一次把補好的寫回去；21：暴擊率那一行漏了數字與「每秒攻擊」、傳奇武器的基底傷害照物品等級；20：催化劑品質只寫「攻擊詞綴」、固定詞綴有標籤；19：靈盾改名法器、不能格擋；18：基礎暴擊率改過也變色；17：權杖顯示精魂；16：符文孔；15：催化劑的說明改字；14：說明排版（分隔線、前後綴顏色）；13：鎖定的小鎖頭；8：武器上的物理增加與攻擊附加改成本地；9：魔法、稀有裝備的外框標記；10：傳奇也有外框；11：飾品品質（催化劑）加成後的數值；12：47 件傳奇補上專屬圖
    /** 物品上記的外觀版本（PDC）。 */
    public static final NamespacedKey RENDER_KEY = new NamespacedKey("exilecore", "render");

    /**
     * 武器的攻擊距離（原版元件）：最近 64 格，客戶端找準星目標時跳過 64 格內的實體，準星落在後面的方塊上，
     * 按住左鍵就會一直「挖」（每 tick 揮手）＝按住左鍵連續施放（skill/HoldToCast）。創造模式照原版的距離（蓋東西用）。
     * 原版的攻擊本來就全部取消（傷害由技能算），目標由伺服器沿視線找。
     */
    private static final class WeaponRange {
        /** 第一次畫武器時才建（要伺服器在跑；ItemRenderer.escape 這種純文字的用法不會碰到）。 */
        static final AttackRange VALUE = AttackRange.attackRange().minReach(64).maxReach(64)
                .minCreativeReach(0).maxCreativeReach(5).hitboxMargin(0).mobFactor(1).build();

        private WeaponRange() {
        }
    }

    /** 追逐級傳奇在物品上的標記（極稀有：掉出來全服廣播）。 */
    public static final String CHASE_LABEL = "<#ffb347>✦ 追逐級傳奇<dark_gray>（極稀有）";

    private final ItemRepository repository;
    private final boolean resourcePackEnabled;
    /** 星籤的通貨定義（顯示名稱與說明）；通貨模組接上，沒有就只印代號。 */
    private java.util.function.Function<String, tw.exilecore.currency.CurrencyType> omens;

    public ItemRenderer(final ItemRepository repository, final boolean resourcePackEnabled) {
        this.repository = repository;
        this.resourcePackEnabled = resourcePackEnabled;
    }

    /** 接上星籤的查詢（代號 → 通貨定義）。 */
    /** 藥劑、護符畫完詞綴之後的補畫（FlaskModule 接上：名稱前面的藥劑說明、充能條、藥水顏色）。 */
    private java.util.function.BiConsumer<ItemStack, ItemData> flaskDecorator;

    public void setFlaskDecorator(final java.util.function.BiConsumer<ItemStack, ItemData> decorator) {
        this.flaskDecorator = decorator;
    }

    public void setOmens(final java.util.function.Function<String, tw.exilecore.currency.CurrencyType> omens) {
        this.omens = omens;
    }

    /** 建立一個全新的 ItemStack（不含 PDC，由 ItemService 補上）。 */
    public ItemStack create(final ItemData data) {
        final ItemBase base = repository.requireBase(data.base());
        Material material = Material.matchMaterial(base.material());
        if (material == null) {
            material = Material.STICK;
        }
        final ItemStack stack = ItemStack.of(material);
        render(stack, data);
        return stack;
    }

    /**
     * 物品上的名稱、說明跟照目前資料畫出來的不一樣（2026-10-16：說明沒跟著資料更新的保險——
     * 曾經有說明寫 T1、實際是 T3 的情況）。基底不見了回傳 false（不動）。
     */
    public boolean outdated(final ItemStack stack, final ItemData data) {
        final ItemBase base = repository.base(data.base());
        if (base == null || !stack.hasItemMeta()) {
            return base != null;
        }
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, data);
        final List<Component> lore = tw.exilecore.core.text.TextWrap.lore(buildLore(base, data, snapshot)); // 2026-10-26：長行自動換行
        final Component name = line("<" + data.rarity().color() + ">" + escape(ItemStats.displayName(repository, data)));
        final org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        return !lore.equals(meta.lore()) || !name.equals(meta.displayName());
    }

    /** 依資料重繪既有物品的外觀。 */
    public void render(final ItemStack stack, final ItemData data) {
        final ItemBase base = repository.requireBase(data.base());
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, data);
        final List<Component> lore = tw.exilecore.core.text.TextWrap.lore(buildLore(base, data, snapshot)); // 2026-10-26：長行自動換行
        final Component name = line("<" + data.rarity().color() + ">" + escape(ItemStats.displayName(repository, data)));

        // 原版屬性（劍的攻擊力、盔甲的護甲值）全部清空：傷害與防禦都由我們的引擎算
        stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        if (base.isWeapon()) {
            stack.setData(DataComponentTypes.ATTACK_RANGE, WeaponRange.VALUE);
        } else {
            stack.unsetData(DataComponentTypes.ATTACK_RANGE);
        }
        if (base.isShield()) {
            // 盾不能舉起來：格擋是被動機率（PoE），舉盾也會打斷按住右鍵的連續施放
            stack.unsetData(DataComponentTypes.BLOCKS_ATTACKS);
        }
        // 2026-10：鎖定的裝備在格子裡右上角多一個小鎖頭（custom_model_data 的 flag 0；資源包的原版物品定義照這個畫）
        final boolean lockFlag = data.locked();
        if (data.unique() != null && UniqueArt.has(data.unique())) {
            // 傳奇的專屬外觀：資源包用 custom_model_data 的字串選圖；沒套資源包的看到原本的物品，所以不用管 resource-pack.enabled
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(UniqueArt.modelKey(data.unique()))
                    .addFlag(lockFlag).build());
        } else if (data.rarity() != Rarity.UNIQUE && PlainArt.category(base) != null) {
            // 2026-10-24：普通、魔法、稀有的裝備在格子裡畫簡圖（照種類一張，跟傳奇的細緻圖分開）；字串 1 是外框的稀有度。
            // 只畫在格子裡：拿在手上、穿在身上照原版
            final CustomModelData.Builder model = CustomModelData.customModelData().addString(PlainArt.modelKey(PlainArt.category(base)));
            if (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE) {
                model.addString(data.rarity() == Rarity.MAGIC ? "rarity:magic" : "rarity:rare");
            }
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, model.addFlag(lockFlag).build());
        } else if (BaseArt.has(data.base()) && (data.rarity() == Rarity.UNIQUE || !base.isWearable())) {
            // 2026-10-21：一般裝備、地圖、藥劑、護符、珠寶的圖（web/base_art.txt）：字串 0 是 base:基底代號，字串 1 是外框的稀有度
            // 2026-10-21 晚：裝備（武器、防具、飾品）只有傳奇用畫的圖；普通、魔法、稀有照原版的樣子（加稀有度外框，走下面）
            final CustomModelData.Builder model = CustomModelData.customModelData().addString(BaseArt.modelKey(data.base()));
            if (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE || data.rarity() == Rarity.UNIQUE) {
                model.addString(data.rarity() == Rarity.MAGIC ? "rarity:magic" : data.rarity() == Rarity.RARE ? "rarity:rare" : "rarity:unique");
            }
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, model.addFlag(lockFlag).build());
        } else if (data.rarity() == Rarity.MAGIC || data.rarity() == Rarity.RARE || data.rarity() == Rarity.UNIQUE) {
            // 2026-10：魔法、稀有、傳奇裝備在格子裡有淺淺的同色漸層外框（資源包的原版物品定義照這個字串挑；沒套資源包的看到原本的樣子）。
            // 有專屬外觀的傳奇走上面那條（unique:代號），外框畫在專屬外觀的挑選裡
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                    .addString(data.rarity() == Rarity.MAGIC ? "rarity:magic" : data.rarity() == Rarity.RARE ? "rarity:rare" : "rarity:unique")
                    .addFlag(lockFlag).build());
        } else if (lockFlag) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFlag(true).build());
        } else {
            stack.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
        }
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(RENDER_KEY, PersistentDataType.INTEGER, RENDER_VERSION);
            if (!base.isFlaskLike()) {
                meta.setUnbreakable(true); // 藥劑、護符的充能畫在耐久條上
            }
            meta.setMaxStackSize(1);
            meta.setAttributeModifiers(HashMultimap.create());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM);
            meta.setEnchantmentGlintOverride(data.rarity() == Rarity.UNIQUE);
            if (base.model() != null) {
                // 自己的模型要資源包；原版的模型（minecraft:，例如長棍用竹子、矛用鐵矛的樣子）客戶端本來就有
                final NamespacedKey model = NamespacedKey.fromString(base.model());
                if (model != null && (resourcePackEnabled || NamespacedKey.MINECRAFT.equals(model.getNamespace()))) {
                    meta.setItemModel(model);
                }
            }
        });
        if (base.isFlaskLike() && flaskDecorator != null) {
            flaskDecorator.accept(stack, data); // 2026-10：藥劑、護符自己的說明、充能、顏色
        }
        nativeText(stack);
    }

    /**
     * 2026-10 效能：名稱與說明換成原版自己的文字元件。
     * <p>
     * 用 API 設定的說明（meta.lore）存在物品上是「包著 Adventure 的元件」，原版每 tick 比對背包有沒有變的時候
     * （AbstractContainerMenu.broadcastChanges → ItemLore.equals）每一行都要重新轉換一次，背包裡東西一多就很重
     * （spark：佔伺服器主執行緒 8%）。存成位元組再讀回來就是原版的元件，比對很便宜；只在物品做好、重畫的時候做一次。
     */
    public static void nativeText(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        try {
            // 2026-10-22 效能：以前整件物品（連詞綴資料）序列化成壓縮的位元組再讀回來（spark：掉落時做物品 0.8%）。
            // 現在只把名稱、說明放到一張空白的紙上轉換；一模一樣的文字（通貨、同一種東西）直接用上次轉好的
            final List<Object> key = new ArrayList<>(3);
            key.add(stack.lore());
            key.add(stack.getData(DataComponentTypes.CUSTOM_NAME));
            key.add(stack.getData(DataComponentTypes.ITEM_NAME));
            ItemStack decoded;
            synchronized (NATIVE) {
                decoded = NATIVE.get(key);
            }
            if (decoded == null) {
                final ItemStack carrier = new ItemStack(Material.PAPER);
                carrier.copyDataFrom(stack, ItemRenderer::isText);
                decoded = ItemStack.deserializeBytes(carrier.serializeAsBytes());
                synchronized (NATIVE) {
                    NATIVE.put(key, decoded);
                }
            }
            stack.copyDataFrom(decoded, ItemRenderer::isText);
        } catch (final RuntimeException | LinkageError ignored) {
            // 轉不了就照舊（只是比較慢；單元測試沒有伺服器）
        }
    }

    private static boolean isText(final io.papermc.paper.datacomponent.DataComponentType type) {
        return type == DataComponentTypes.LORE || type == DataComponentTypes.CUSTOM_NAME || type == DataComponentTypes.ITEM_NAME;
    }

    /** 轉好的原版文字（名稱＋說明 → 只帶這三樣的紙），最近用的 1024 種。 */
    private static final Map<List<Object>, ItemStack> NATIVE = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<List<Object>, ItemStack> eldest) {
            return size() > 1024;
        }
    };

    /** 藥劑、護符：只畫詞綴那一段（前面的說明由 FlaskService 補）。 */
    private List<Component> buildFlaskLore(final ItemData data) {
        final List<Component> lore = new ArrayList<>();
        if (!data.identified()) {
            lore.add(line("<#9a9ad0>未鑑定"));
            return lore;
        }
        if (data.quality() > 0) {
            // 2026-10-23：蝕變石給的品質（PoE2：藥劑增加回復量、護符增加持續時間）
            final ItemBase base = repository.base(data.base());
            lore.add(line("<gray>品質：<#8888ff>+" + data.quality() + "% <dark_gray>("
                    + (base != null && base.isCharm() ? "持續時間" : "回復量") + " +" + data.quality() + "%)"));
        }
        if (!data.affixes().isEmpty()) {
            lore.add(line(SEPARATOR));
            final List<ItemStats.AffixLine> affixLines = ItemStats.affixLines(repository, data);
            lore.add(line(affixSlots(data, affixLines)));
            for (final ItemStats.AffixLine affixLine : affixLines) {
                // 2026-10-24：藥劑、護符的詞綴也標階級 (T1…)，跟裝備一樣；破裂的標起來
                final boolean fractured = ItemStats.AffixLine.FRACTURED.equals(affixLine.mark());
                lore.add(line(affixTag(affixLine.type()) + "<" + (fractured ? "#c9a66b" : affixLine.type() == AffixType.PREFIX ? PREFIX_COLOR : SUFFIX_COLOR) + ">"
                        + affixLine.ranged() + " <dark_gray>(T" + affixLine.tier() + ")" + (fractured ? " <#c9a66b>✦破裂" : "")
                        + AffixChips.miniMessage(affixLine.chips())));
            }
        }
        if (data.corrupted()) {
            lore.add(line("<#d20000>已腐化 <dark_gray>（不能再用通貨改造）")); // 2026-10-23
        }
        return lore;
    }

    private List<Component> buildLore(final ItemBase base, final ItemData data, final ItemStats.Snapshot snapshot) {
        if (base.isFlaskLike()) {
            return buildFlaskLore(data);
        }
        final List<Component> lore = new ArrayList<>();
        final String color = data.rarity().color();

        final UniqueItem uniqueDefinition = data.unique() == null ? null : repository.unique(data.unique());
        if (uniqueDefinition != null && uniqueDefinition.chase()) {
            lore.add(line(CHASE_LABEL + " <dark_gray>" + escape(base.name())));
        } else if (data.rarity() == Rarity.RARE || data.rarity() == Rarity.UNIQUE || !data.identified()) {
            lore.add(line("<" + color + ">" + escape(base.name()) + " <dark_gray>" + data.rarity().displayName()));
        } else {
            lore.add(line("<dark_gray>" + data.rarity().displayName() + escape(slotSuffix(base))));
        }
        if (data.quality() > 0) {
            final Catalyst catalyst = base.isJewellery() ? Catalyst.parse(data.catalyst()) : null;
            // 2026-10-27 照 PoE2 0.5：法杖、長杖、權杖的品質不加武器數值（權杖的精魂也不加），加在賦予的技能上；這次之前沒寫
            final boolean grants = repository.grantedSkill(data.base()) != null;
            lore.add(line("<gray>品質：<#8888ff>+" + data.quality() + "%" + (base.isMap() ? " <dark_gray>(物品數量)"
                    : catalyst != null ? " <dark_gray>(" + catalyst.description() + " +" + data.quality() + "%)"
                    : grants ? " <dark_gray>(品質加在賦予的技能上)" : "")));
        }
        if (base.isMap()) {
            return buildMapLore(base, data, lore);
        }

        // 基底數值
        if (base.isWeapon()) {
            // 武器本身的詞綴（本地加成）改過的數字畫成藍色（跟品質加成同一個藍）
            final LocalMods local = snapshot.local();
            for (final Map.Entry<DamageType, double[]> entry : snapshot.damage().entrySet()) {
                final boolean boosted = local.hasAdded(entry.getKey()) || entry.getKey() == DamageType.PHYSICAL && local.physical() != 0;
                lore.add(line("<gray>" + entry.getKey().displayName() + "傷害：" + (boosted ? AUGMENTED : "<white>")
                        + (int) entry.getValue()[0] + " - " + (int) entry.getValue()[1]));
            }
            // 2026-10：「+x% 暴擊率」（基礎暴擊）也變色
            lore.add(line("<gray>暴擊率：" + (local.crit() != 0 || Math.abs(snapshot.crit() - base.crit()) > 1e-6 ? AUGMENTED : "<white>")
                    + decimals(snapshot.crit()) + "%  <gray>每秒攻擊："
                    + (local.attackSpeed() != 0 ? AUGMENTED : "<white>") + String.format(Locale.ROOT, "%.2f", snapshot.aps())));
            final double spirit = ItemStats.sceptreSpirit(base, ItemStats.modifiers(repository, data));
            if (spirit > 0) {
                // 2026-10：權杖給的精魂（基礎 100 ＋ 權杖自己的「增加 x% 精魂」）
                lore.add(line("<gray>精魂：" + (spirit != ItemStats.SCEPTRE_SPIRIT ? AUGMENTED : "<white>") + (int) Math.round(spirit)));
            }
            final String hint = base.basicAttackHint();
            if (hint != null) {
                lore.add(line("<gray>" + hint));
            }
        }
        if (snapshot.armour() > 0) {
            lore.add(line("<gray>護甲：<white>" + (int) snapshot.armour()));
        }
        if (snapshot.evasion() > 0) {
            lore.add(line("<gray>閃避：<white>" + (int) snapshot.evasion()));
        }
        if (snapshot.energyShield() > 0) {
            lore.add(line("<gray>能量護盾：<white>" + (int) snapshot.energyShield()));
        }
        if (snapshot.block() > 0) {
            // 2026-10-23：含盾上本地的「增加 x% 格擋率」：被改過的照武器數值一樣用藍色
            lore.add(line("<gray>格擋率：" + (Math.abs(snapshot.block() - base.block()) > 1e-6 ? AUGMENTED : "<white>") + (int) snapshot.block() + "%"));
        }

        // 需求（珠寶沒有需求：放進天賦樹就生效）；前面有基底數值就隔一條線（2026-10 美化）
        if (!base.isJewel()) {
            if (base.isWeapon() || snapshot.armour() > 0 || snapshot.evasion() > 0 || snapshot.energyShield() > 0 || snapshot.block() > 0) {
                lore.add(line(SEPARATOR));
            }
            final ItemBase.Requirements req = snapshot.requirements();
            final StringBuilder requirement = new StringBuilder("<gray>需求：等級 <white>" + req.level());
            if (req.str() > 0) {
                requirement.append("<gray>，力量 <white>").append(req.str());
            }
            if (req.dex() > 0) {
                requirement.append("<gray>，敏捷 <white>").append(req.dex());
            }
            if (req.intelligence() > 0) {
                requirement.append("<gray>，智慧 <white>").append(req.intelligence());
            }
            lore.add(line(requirement.toString()));
        }

        // 插槽先不顯示：階段 3 走 PoE2 的做法，寶石不插裝備；插槽日後給符文用

        // 固定詞綴與詞綴
        final boolean hasImplicit = base.implicit() != null;
        final boolean hasAffixes = !data.affixes().isEmpty();
        if (hasImplicit || hasAffixes || data.instilled() != null) {
            lore.add(line(SEPARATOR));
        }
        if (hasImplicit) {
            final Implicit implicit = ItemStats.implicit(repository, base, data);
            lore.add(line("<#a0a0ff>" + implicit.renderWithRanges(data.implicitVars()) + " <dark_gray>(固定)"
                    + AffixChips.miniMessage(AffixChips.of(implicit.mods())))); // 2026-10：固定詞綴也有標籤
        }
        if (data.instilled() != null) {
            final ItemStats.Instil instil = ItemStats.instilOf(data);
            if (instil == null) {
                lore.add(line("<#c77dff>注入：<dark_gray>已失效（天賦樹改版，這個核心不在了）"));
            } else {
                lore.add(line("<#c77dff>注入：" + escape(instil.name()) + " <dark_gray>(蒸餾情緒)"));
                for (final String text : instil.lines()) {
                    lore.add(line("<#c77dff>　" + escape(text)));
                }
            }
        }
        if (hasAffixes) {
            if (hasImplicit || data.instilled() != null) {
                lore.add(line(SEPARATOR));
            }
            if (!data.identified()) {
                lore.add(line("<#ff5555>未鑑定 <dark_gray>— 找鑑定師（NPC）或用鑑定卷軸"));
            } else {
                final boolean unique = data.rarity() == Rarity.UNIQUE;
                final List<ItemStats.AffixLine> affixLines = new ArrayList<>(ItemStats.affixLines(repository, data));
                // 2026-10：前綴排前面、後綴排後面（同一種排在一起比較好看）
                affixLines.sort(java.util.Comparator.comparingInt(each -> each.type() == AffixType.PREFIX ? 0 : 1));
                // 2026-10-21：蝕變石的腐化詞綴排最前面、紅色，不算前綴、後綴的位子
                final List<ItemStats.AffixLine> enchants = new ArrayList<>();
                affixLines.removeIf(each -> {
                    if (ItemStats.AffixLine.ENCHANT.equals(each.mark())) {
                        enchants.add(each);
                        return true;
                    }
                    return false;
                });
                for (final ItemStats.AffixLine enchant : enchants) {
                    lore.add(line("<#d20000>腐化：" + enchant.ranged() + AffixChips.miniMessage(enchant.chips())));
                }
                if (!unique) {
                    // 前綴、後綴各用了幾個位子（改造時一看就知道還能加哪種）
                    lore.add(line(affixSlots(data, affixLines)));
                }
                for (final ItemStats.AffixLine affixLine : affixLines) {
                    // 被催化劑加強的詞綴：數值已經是加成後的，用亮藍色、後面標「催化」
                    // 被「增加 x% 已揭露詞綴的幅度」加強的揭露詞綴（2026-10）：數值已經是加成後的，用紫色、後面標「揭露 +x%」
                    final boolean fractured = ItemStats.AffixLine.FRACTURED.equals(affixLine.mark()); // 2026-10-21：定星石
                    final String catalysedMark = (affixLine.catalysed() ? " <#7fd4ff>✦催化" : "")
                            + (affixLine.unveiled() ? " <#d69bff>✦揭露 +" + affixLine.unveiledBoost() + "%" : "")
                            + (fractured ? " <#c9a66b>✦破裂" : "");
                    final String boostColor = affixLine.unveiled() ? "#d69bff" : affixLine.catalysed() ? "#7fd4ff" : fractured ? "#c9a66b" : null;
                    final String chips = AffixChips.miniMessage(affixLine.chips()); // 2026-10：詞綴標籤（傷害、火焰、攻擊…）
                    lore.add(line(unique ? "<" + (boostColor != null ? boostColor : "#8888ff") + ">" + affixLine.ranged() + catalysedMark + chips
                            : affixTag(affixLine.type()) + "<" + (boostColor != null ? boostColor
                                    : affixLine.type() == AffixType.PREFIX ? PREFIX_COLOR : SUFFIX_COLOR) + ">" + affixLine.ranged()
                                    + " <dark_gray>(T" + affixLine.tier() + ")" + catalysedMark + chips));
                }
            }
        }
        // 符文孔（2026-10）：嵌著的符文與它給的加成，空孔
        final int runeSlots = Runes.slots(repository, base, data);
        if (runeSlots > 0 && (!data.runes().isEmpty() || data.identified())) {
            lore.add(line(SEPARATOR));
            for (final String id : data.runes()) {
                final Runes.Rune rune = repository.rune(id);
                if (rune == null) {
                    continue;
                }
                for (final Runes.Line runeLine : rune.on(base)) {
                    lore.add(line("<#7fe0c8>◈ " + escape(runeLine.text()) + " <dark_gray>(" + escape(rune.name()) + ")"));
                }
                for (final Runes.Line bonded : rune.bondedOn(base)) {
                    // 2026-10-27 照 PoE2：羈絆詞綴（只有昇華・聖賢智慧才有效果，灰字）
                    lore.add(line("<dark_gray>  羈絆：" + escape(bonded.text())));
                }
            }
            final double runeEffect = repository.uniqueRuneEffect(data.unique());
            if (runeEffect > 0) {
                lore.add(line("<#7fe0c8>嵌在這件上的符文效果增加 " + Math.round(runeEffect * 100) + "%"));
            }
            final int empty = runeSlots - data.runes().size();
            if (empty > 0) {
                lore.add(line("<dark_gray>◇ 空的符文孔 ×" + empty + "（把符文用在這件上嵌進去）"));
            }
        }
        final UniqueItem unique = repository.unique(data.unique());
        if (unique != null && !unique.flavour().isEmpty()) {
            lore.add(line(SEPARATOR));
            for (final String flavour : unique.flavour()) {
                lore.add(line("<#af6025><i>" + escape(flavour)));
            }
        }
        if (data.omen() != null) {
            final tw.exilecore.currency.CurrencyType omen = omens == null ? null : omens.apply(data.omen());
            lore.add(line("<#c48bff>星籤：" + (omen == null ? escape(data.omen()) : escape(omen.name()) + " <dark_gray>— " + escape(omen.description()))));
        }
        if (base.isJewel()) {
            lore.add(line(SEPARATOR));
            lore.add(line("<gray>放進天賦樹的<" + base.jewelColor() + ">珠寶孔</" + base.jewelColor() + ">（/tree，配了孔位再點它）"));
            if (base.isRadiusJewel()) {
                // 2026-10：遺時珠寶：詞綴作用在珠寶孔周圍已配置的天賦上。2026-10-26 照 PoE2：半徑用原作單位（小 1000、中 1150、大 1300），
                // 「也各給」分小天賦與核心天賦、沒有上限（這次之前是幾格、最多算 15 個小天賦）
                final double radius = tw.exilecore.passive.JewelSockets.radiusOf(ItemStats.modifiers(repository, data));
                lore.add(line("<#ffd27f>遺時珠寶<gray>：半徑 <white>" + tw.exilecore.passive.JewelSockets.radiusName(radius) + "（" + (int) radius
                        + "）</white>，範圍內已配置的每個小天賦／核心天賦各多給一次「也各給」那幾條"));
            }
            if (tw.exilecore.passive.TimelessJewels.isTimeless(base)) {
                // 2026-10-26 照 PoE2：永恆珠寶征服範圍內的天賦
                for (final String text : tw.exilecore.passive.TimelessJewels.explain(tw.exilecore.passive.TimelessJewels.read(repository, null, data))) {
                    lore.add(line("<#ffd27f>" + escape(text)));
                }
            }
            if (!data.identified()) {
                lore.add(line("<dark_gray>要先鑑定才能放"));
            }
        }
        lore.add(line(SEPARATOR));
        lore.add(line("<dark_gray>物品等級 " + data.ilvl() + (data.starter() ? "・新手裝備（拆解沒有東西）" : "")));
        if (data.corrupted()) {
            lore.add(line("<#d20000>已腐化 <dark_gray>（不能再用通貨改造；空的符文孔還能嵌符文）"));
        }
        if (data.fractured() != null) {
            lore.add(line("<#c9a66b>✦破裂 <dark_gray>（那一條詞綴通貨動不到，會一直留著）"));
        }
        if (data.mirrored()) {
            lore.add(line("<#9fd8ff>鏡像複製品 <dark_gray>（不能再改造、不能再複製）"));
        }
        if (data.sanctified()) {
            lore.add(line("<#ffe08a>已聖化 <dark_gray>（不能再用通貨改造）")); // 2026-10-26 照 PoE2
        }
        if (data.foresight() != null) {
            lore.add(line("<#e0c070>預見鎖 <dark_gray>（拿通貨點它先看結果，再點一次才用）")); // 2026-10-26 照 PoE2 的 Hinekora's Lock
        }
        if (data.locked()) {
            lore.add(line("<#ffd27f>🔒 已鎖定 <dark_gray>（不會被拆解、上架、交易；/lock 解除）"));
        }
        return lore;
    }

    /** 區塊之間的分隔線（2026-10 美化：粗一點、長度一樣）。 */
    static final String SEPARATOR = "<dark_gray>━━━━━━━━━━━━━━";
    /** 前綴、後綴的數值顏色（2026-10：一眼分得出哪條是前綴、哪條是後綴）。 */
    static final String PREFIX_COLOR = "#b4bcff";
    static final String SUFFIX_COLOR = "#ffd9a0";

    /** 被武器本身詞綴（或品質）加成過的數字的顏色。 */
    static final String AUGMENTED = "<#8888ff>";

    /** 最多兩位小數、至少一位（5.0、6.5、6.75）。 */
    static String decimals(final double value) {
        final String text = String.format(Locale.ROOT, "%.2f", value);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    /** 每條詞綴前面的小標：前綴「前」、後綴「後」。 */
    static String affixTag(final AffixType type) {
        return type == AffixType.PREFIX ? "<#7f88d8>◆ " : "<#d0a06a>◇ ";
    }

    /** 「前綴 2/3・後綴 1/3」：各用了幾個位子（珠寶每種最多 2）。 */
    private String affixSlots(final ItemData data, final List<ItemStats.AffixLine> affixLines) {
        int prefixes = 0;
        for (final ItemStats.AffixLine affixLine : affixLines) {
            if (affixLine.type() == AffixType.PREFIX) {
                prefixes++;
            }
        }
        final ItemBase base = repository.base(data.base());
        final boolean jewel = base != null && base.isJewel();
        final int maxPrefixes = jewel ? Math.min(data.rarity().maxPrefixes(), ItemBase.JEWEL_AFFIXES_PER_TYPE)
                : data.rarity().maxPrefixes() <= 0 ? 0 : Math.max(0, data.rarity().maxPrefixes() + ItemGenerator.allowedDelta(base, AffixType.PREFIX)); // 2026-10-26：基底的前後綴上限
        final int maxSuffixes = jewel ? Math.min(data.rarity().maxSuffixes(), ItemBase.JEWEL_AFFIXES_PER_TYPE)
                : data.rarity().maxSuffixes() <= 0 ? 0 : Math.max(0, data.rarity().maxSuffixes() + ItemGenerator.allowedDelta(base, AffixType.SUFFIX));
        return "<#9a9ad0>前綴 " + prefixes + "/" + maxPrefixes + "<dark_gray>・<#d0a06a>後綴 " + (affixLines.size() - prefixes) + "/" + maxSuffixes;
    }

    /** 地圖：階級、怪物等級、物品數量與稀有度加成（含階級本身的）、詞綴（不分前後綴的階級）、怎麼用。 */
    private List<Component> buildMapLore(final ItemBase base, final ItemData data, final List<Component> lore) {
        final MapStats.Tiers tiers = MapStats.tiers();
        final MapStats stats = MapStats.of(repository, data, tiers);
        lore.add(line("<gray>地圖階級：<white>T" + stats.tier() + "<dark_gray>／T" + tiers.max() + "　<gray>怪物等級：<white>" + stats.monsterLevel()));
        lore.add(line(String.format(Locale.ROOT, "<gray>物品數量 <white>+%d%%　<gray>物品稀有度 <white>+%d%%%s",
                Math.round(stats.quantity()), Math.round(stats.rarity()),
                stats.packSize() != 0 ? "　<gray>怪物群大小 <white>+" + Math.round(stats.packSize()) + "%" : "")));
        if (stats.tier() > 1 && (tiers.quantity(stats.tier()) > 0 || tiers.rarity(stats.tier()) > 0)) { // 2026-10-27：照 PoE2 階級加成是 0 時不寫
            lore.add(line(String.format(Locale.ROOT, "<dark_gray>（含階級加成：物品數量 +%d%%、稀有度 +%d%%）",
                    Math.round(tiers.quantity(stats.tier())), Math.round(tiers.rarity(stats.tier())))));
        }
        if (!data.affixes().isEmpty()) {
            lore.add(line(SEPARATOR));
            final String color = data.rarity().color();
            final List<ItemStats.AffixLine> affixLines = ItemStats.affixLines(repository, data);
            lore.add(line(affixSlots(data, affixLines)));
            for (final ItemStats.AffixLine affixLine : affixLines) {
                lore.add(line(affixTag(affixLine.type()) + "<" + color + ">" + affixLine.ranged() + AffixChips.miniMessage(affixLine.chips())));
            }
        }
        lore.add(line(SEPARATOR));
        lore.add(line("<gray><i>大塊星骸記得它落下前照過的地方。"));
        final String craft = mapCraftHint(data);
        if (craft != null) {
            lore.add(line(craft));
        }
        lore.add(line("<dark_gray>拿到營地的地圖裝置（NPC）右鍵，打開傳送門"));
        if (data.corrupted()) {
            lore.add(line("<#d20000>已腐化 <dark_gray>（不能再用通貨改造）")); // 2026-10-23
        }
        return lore;
    }

    /**
     * 地圖還能怎麼洗（PoE2：沒洗過的地圖等於白白少拿東西）：照稀有度說下一步用哪種通貨，
     * 品質沒滿說星盤刻刀。全部洗滿回傳 null。
     */
    public String mapCraftHint(final ItemData data) {
        if (data.corrupted()) {
            return null; // 2026-10-23：腐化的地圖不能再改造
        }
        int prefixes = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix != null && affix.type() == AffixType.PREFIX) {
                prefixes++;
            }
        }
        final int suffixes = data.affixes().size() - prefixes;
        final Rarity rarity = data.rarity();
        final boolean full = prefixes >= rarity.maxPrefixes() && suffixes >= rarity.maxSuffixes();
        final List<String> steps = new ArrayList<>();
        switch (rarity) {
            case NORMAL -> steps.add("微光石→魔法（+1 詞綴）、星火石→稀有（4 條）");
            case MAGIC -> steps.add(full ? "豐饒石→稀有（再 +1 詞綴）" : "添輝石再加 1 條、豐饒石→稀有");
            case RARE -> {
                if (!full) {
                    steps.add("至臻石再加 1 條（還有 " + (rarity.maxPrefixes() - prefixes + rarity.maxSuffixes() - suffixes) + " 個空位）");
                }
            }
            default -> {
            }
        }
        if (data.quality() < 20) {
            steps.add("星盤刻刀品質 +5（每 1% = +1% 物品數量，上限 20）");
        }
        if (steps.isEmpty()) {
            return null;
        }
        return "<#f5b942>還能洗：<gray>" + String.join("；", steps);
    }

    private static String slotSuffix(final ItemBase base) {
        return "・" + base.slot().displayName();
    }

    /** MiniMessage → Component，並關掉原版描述預設的斜體。 */
    public static Component line(final String miniMessage) {
        return MINI.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    /** 把資料裡的文字放進 MiniMessage 前先跳脫標籤字元。 */
    public static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * 兩個物品的名稱與描述看起來是不是一樣（只比文字，不比顏色、樣式的寫法：存檔讀回來的元件結構可能不一樣）。
     * 通貨、寶石用它判斷要不要照現在的定義重畫（名稱或說明改過，例如 2026-10 兩次改名）。
     */
    public static boolean sameText(final org.bukkit.inventory.meta.ItemMeta a, final org.bukkit.inventory.meta.ItemMeta b) {
        if (a == null || b == null) {
            return a == b;
        }
        return plain(a.displayName()).equals(plain(b.displayName())) && plainLines(a.lore()).equals(plainLines(b.lore()));
    }

    private static String plain(final Component component) {
        return component == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> plainLines(final List<Component> lines) {
        final List<String> out = new ArrayList<>();
        if (lines != null) {
            for (final Component line : lines) {
                out.add(plain(line));
            }
        }
        return out;
    }
}
