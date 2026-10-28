package tw.exilecore.skill;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 寶石（PoE2 的未切割寶石）：物品的建立與辨識、右鍵雕琢、技能面板上的放入／取出／掛輔助／開孔、怪物掉落。
 * <ul>
 *   <li>未切割技能寶石（Lv.N）：雕成一個 Lv.N 的技能寶石，或把面板上的技能直接升到 Lv.N（2026-10-27 照 PoE2；這次之前一顆升一級）</li>
 *   <li>未切割輔助寶石（第 T 階）：切成一個第 T 階以下的輔助寶石</li>
 *   <li>技能寶石：放進技能面板就學會；取出來會帶著等級、輔助孔與掛的輔助寶石</li>
 *   <li>輔助寶石：掛在技能的輔助孔上；同一種可以掛在不同技能上（各用一顆），同一個技能不能掛兩顆一樣的
 *       （supports.yml 的 once_per_character: true 時照 PoE2：同一種一個角色只能用一次）</li>
 * </ul>
 */
public final class GemService {

    /** 一次面板操作的結果：錯誤訊息（null = 成功）與要還給玩家的東西（換下來的寶石）。 */
    public record Change(String error, ItemStack returned) {
        static Change fail(final String error) {
            return new Change(error, null);
        }

        static Change ok(final ItemStack returned) {
            return new Change(null, returned);
        }

        public boolean failed() {
            return error != null;
        }
    }

    private final ExileCore plugin;
    private final SkillService skills;
    private final NamespacedKey key;
    private final boolean resourcePack;

    GemService(final ExileCore plugin, final SkillService skills) {
        this.plugin = plugin;
        this.skills = skills;
        this.key = new NamespacedKey(plugin, "gem");
        this.resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
    }

    private SkillConfig config() {
        return skills.config();
    }

    // ---- 物品 ----

    public Optional<GemData> read(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return GemData.decode(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    public boolean isGem(final ItemStack stack) {
        return read(stack).isPresent();
    }

    /**
     * 舊的寶石物品照現在的定義重畫名稱與說明（2026-10 技能、輔助換了名字）；名稱一樣就不動。
     *
     * @return 有沒有改
     */
    @SuppressWarnings("deprecation")
    public boolean refresh(final ItemStack stack) {
        // 2026-10-18：遷移時會拆下輔助的寶石這裡不動（這條路徑沒有擁有者，拆下來的輔助只能丟掉）：留給知道擁有者的路徑遷移
        if (read(stack).map(this::detachesSupports).orElse(false)) {
            return false;
        }
        final List<ItemStack> extra = migrateRemoved(stack);
        if (extra != null) {
            if (!extra.isEmpty()) {
                plugin.getSLF4JLogger().warn("寶石補正：拿掉的技能寶石換成未切割寶石，上面掛的輔助 {} 沒有地方放（請手動補給）", describe(extra));
            }
            return true;
        }
        final Optional<GemData> data = read(stack);
        if (data.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        final ItemStack fresh = create(data.get(), stack.getAmount());
        // 名稱或描述的文字改過才重畫（技能寶石的描述裡有掛著的輔助名稱，輔助改名時技能寶石的名字沒變）
        // 2026-10：未切割寶石有了專屬貼圖（每一級一張）：舊的沒有貼圖標記的也要重畫
        final boolean sameModel = java.util.Objects.equals(fresh.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA),
                stack.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA));
        final boolean sameType = fresh.getType() == stack.getType();
        if (sameType && ItemRenderer.sameText(fresh.getItemMeta(), stack.getItemMeta()) && sameModel) {
            return false;
        }
        if (!sameType) {
            // 2026-10：技能、輔助換了原版物品（箭矢會被原版弓當成彈藥、望遠鏡右鍵會拉近）：舊的寶石換成新的物品
            stack.setType(fresh.getType());
        }
        stack.setItemMeta(fresh.getItemMeta());
        final io.papermc.paper.datacomponent.item.CustomModelData model = fresh.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model != null) {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA, model);
        }
        return true;
    }

    /**
     * 2026-10-26：拿掉的技能寶石原數退還投入的通貨：開過的輔助孔（第 3 孔小鑿星石、第 4 孔鑿星石、第 5 孔大鑿星石）
     * 與品質（每 5% 一個琢星稜鏡）。沒有通貨系統時回傳空的。
     */
    public List<ItemStack> investedRefund(final SkillLoadout.Entry entry) {
        final List<ItemStack> refund = new ArrayList<>();
        if (entry == null || plugin.currencies() == null) {
            return refund;
        }
        // 2026-10-28 照 PoE2：賦予的技能的孔照等級自動開（不是買的，不退）；只有這次之前付費開的（legacySockets）照開過的退
        final int paid = entry.granted() && !entry.legacySockets() ? SkillLoadout.BASE_SOCKETS : entry.sockets();
        for (int socket = SkillLoadout.BASE_SOCKETS + 1; socket <= paid; socket++) {
            for (final tw.exilecore.currency.CurrencyType type : plugin.currencies().repository().all()) {
                if (type.operation() == tw.exilecore.currency.CurrencyOperation.SKILL_SOCKET && type.sockets() == socket) {
                    refund.add(plugin.currencies().create(type, 1));
                    break;
                }
            }
        }
        // 2026-10-27：賦予的技能的品質是武器給的，不退琢星稜鏡
        final int prisms = entry.granted() ? 0 : (entry.quality() + QUALITY_STEP - 1) / QUALITY_STEP;
        if (prisms > 0) {
            plugin.currencies().repository().firstWith(tw.exilecore.currency.CurrencyOperation.SKILL_QUALITY)
                    .ifPresent(type -> refund.add(plugin.currencies().create(type, prisms)));
        }
        return refund;
    }

    /**
     * 2026-10-26：拿掉的宿主寶石（引星…）第 1 孔裡的技能寶石還給玩家：還在的原樣（等級、品質、孔），併入別的技能的換成那個，
     * 也被拿掉的換成同等級的未切割技能寶石。
     */
    public ItemStack innerRefund(final SkillLoadout.Entry inner) {
        final SkillLoadout.Entry merged = config().merge(inner);
        final SkillLoadout.Entry entry = merged != null && config().skill(merged.skillId()) != null ? merged : inner;
        final SkillDefinition definition = config().skill(entry.skillId());
        // 2026-10-28 照 PoE2：武器、昇華賦予的技能沒有寶石，換成同等級的未切割技能寶石
        return definition != null && !definition.grantedOnly() ? create(GemData.skill(entry), 1) : create(GemData.uncutSkill(inner.level()), 1);
    }

    /** 2026-10-28：這顆技能寶石（物品或面板上不是賦予的）是只有武器、昇華賦予的技能（照 PoE2 沒有寶石，要換成未切割技能寶石）。 */
    boolean grantedOnlyGem(final SkillLoadout.Entry entry) {
        return grantedOnlyGem(config(), entry);
    }

    /** 同上（測試用）：面板上標成賦予的那一格不算（那是武器、天賦給的，不是寶石）。 */
    static boolean grantedOnlyGem(final SkillConfig config, final SkillLoadout.Entry entry) {
        final SkillDefinition definition = entry == null || entry.granted() ? null : config.skill(entry.skillId());
        return definition != null && definition.grantedOnly();
    }

    /**
     * 2026-10-28：只有武器、昇華賦予的技能的寶石（以前管理指令、雕琢烈陽珠拿到的）退還：同等級的未切割技能寶石、
     * 掛著的輔助、開過的孔與品質、孔裡放的技能寶石。
     */
    public List<ItemStack> grantedOnlyRefund(final SkillLoadout.Entry entry) {
        final List<ItemStack> refund = new ArrayList<>();
        refund.add(create(GemData.uncutSkill(entry.level()), 1));
        for (final String support : entry.supportIds()) {
            refund.add(supportOrUncut(support));
        }
        refund.addAll(investedRefund(entry));
        for (final SkillLoadout.Entry spell : entry.spells()) {
            refund.add(innerRefund(spell));
            if (grantedOnlyGem(spell)) {
                refund.addAll(investedRefund(spell)); // 換成未切割寶石的：開過的孔、品質另外退
            }
        }
        return refund;
    }

    /** 2026-10-28：宿主寶石孔裡放著只有武器、昇華賦予的技能的拿出來（退還放進 refund）；沒有就原樣回傳。 */
    SkillLoadout.Entry withoutGrantedOnlySpells(final SkillLoadout.Entry host, final List<ItemStack> refund, final List<String> names) {
        SkillLoadout.Entry entry = host;
        for (int i = 0; i < entry.sockets(); i++) {
            final SkillLoadout.Entry spell = entry.spellAt(i);
            final SkillDefinition definition = spell == null ? null : config().skill(spell.skillId());
            if (definition != null && grantedOnlyGem(spell)) {
                refund.add(innerRefund(spell));
                refund.addAll(investedRefund(spell));
                if (names != null) {
                    names.add(definition.name() + " Lv." + spell.level());
                }
                entry = entry.withSpell(i, null);
            }
        }
        return entry;
    }

    /** 2026-10-18：migrateRemoved 會不會拆下輔助（技能被拿掉且掛著輔助，或掛著被拿掉的輔助）。 */
    private boolean detachesSupports(final GemData data) {
        if (data.kind() != GemData.Kind.SKILL || data.entry() == null) {
            return false;
        }
        if (config().skill(data.id()) == null && config().mergedInto(data.id()) == null) { // 2026-10-26：併入別的技能的輔助保留
            return !data.entry().supportIds().isEmpty() || !data.entry().spells().isEmpty(); // 2026-10-26：拿掉的引星裡放著技能寶石也要拆
        }
        // 2026-10-26：併入同系列最高階的輔助原孔換過去，不拆；只有同一個技能已經掛著那顆（重複）才要拆
        final List<GemData> duplicate = new ArrayList<>();
        config().mergeSupports(data.entry(), duplicate);
        if (!duplicate.isEmpty()) {
            return true;
        }
        for (int i = 0; i < data.entry().sockets(); i++) {
            final String support = data.entry().support(i);
            if (!support.isEmpty() && !data.entry().spellSocket(i) && config().support(support) == null && config().supportMergedInto(support) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10 PoE2 改版拿掉的技能、輔助寶石：原地換成未切割寶石（技能 → 同等級的未切割技能寶石；輔助 → 同階的未切割輔助寶石）。
     * 技能寶石上掛著的輔助（從面板取下來的寶石）拆下來放在回傳的清單裡：還在的輔助原樣、拿掉的換成未切割輔助寶石。
     * 2026-10-26：併入別的技能的（skills.yml 的 merged）換成併入的技能寶石，等級、品質、孔、輔助都保留（不拆）。
     *
     * @return null = 不用換；否則是要另外給玩家的東西（可能是空的）
     */
    @SuppressWarnings("deprecation")
    public List<ItemStack> migrateRemoved(final ItemStack stack) {
        final Optional<GemData> read = read(stack);
        if (read.isEmpty()) {
            return null;
        }
        final GemData data = read.get();
        final List<ItemStack> extra = new ArrayList<>();
        GemData replacement = null;
        int amount = 1;
        if (data.kind() == GemData.Kind.SKILL && data.entry() != null) {
            // 2026-10-26：掛著的低階 I／II 輔助先換成同系列最高階（supports.yml 的 merged；重複的拆下來）
            final List<GemData> detached = new ArrayList<>();
            final SkillLoadout.Entry current = config().mergeSupports(data.entry(), detached);
            for (final GemData gem : detached) {
                extra.add(create(gem, 1));
            }
            // 2026-10-26：併入別的技能（skills.yml 的 merged）：直接換成那個技能，等級、品質、孔、輔助保留（拿掉的輔助照下面另外換）
            final SkillLoadout.Entry mergedSkill = config().merge(current);
            final SkillLoadout.Entry merged = mergedSkill == null && current != data.entry() ? current : mergedSkill;
            if (grantedOnlyGem(data.entry())) {
                // 2026-10-28 照 PoE2：武器、昇華賦予的技能沒有寶石（以前管理指令給的、雕出來的烈陽珠）：換成同等級的未切割技能寶石，其他原數退還
                final List<ItemStack> refund = grantedOnlyRefund(data.entry());
                replacement = GemData.uncutSkill(data.entry().level());
                extra.clear(); // 上面拆下來的重複輔助在 refund 裡照原本的代號退
                extra.addAll(refund.subList(1, refund.size()));
            } else if (merged != null && config().skill(merged.skillId()) != null) {
                SkillLoadout.Entry entry = merged;
                for (int i = 0; i < entry.sockets(); i++) {
                    final String support = entry.support(i);
                    if (!support.isEmpty() && !entry.spellSocket(i) && config().support(support) == null) { // 2026-10-26：放技能寶石的孔不是輔助
                        extra.add(supportOrUncut(support));
                        entry = entry.withSupport(i, null);
                    }
                }
                replacement = GemData.skill(entry);
            } else if (config().skill(data.id()) == null) {
                replacement = data.removedSkillReplacement(config()); // 同等級的未切割技能寶石
                extra.clear(); // 2026-10-26：上面重複拆下來的那顆下面照原本的代號再退一次，避免多給
                for (final String support : data.entry().supportIds()) {
                    extra.add(supportOrUncut(support));
                }
                extra.addAll(investedRefund(data.entry())); // 2026-10-26：開過的孔、品質原數退還
                for (final SkillLoadout.Entry spell : data.entry().spells()) {
                    extra.add(innerRefund(spell)); // 2026-10-26：拿掉的引星孔裡的技能寶石還給玩家（第 2～5 孔的也還）
                }
            } else {
                SkillLoadout.Entry entry = withoutGrantedOnlySpells(data.entry(), extra, null); // 2026-10-28：孔裡放著賦予的技能的拿出來
                for (int i = 0; i < entry.sockets(); i++) {
                    final String support = entry.support(i);
                    if (!support.isEmpty() && !entry.spellSocket(i) && config().support(support) == null) { // 2026-10-26：放技能寶石的孔不是輔助
                        extra.add(supportOrUncut(support));
                        entry = entry.withSupport(i, null);
                    }
                }
                if (entry != data.entry()) {
                    replacement = GemData.skill(entry);
                }
            }
        } else if (data.kind() == GemData.Kind.SUPPORT && config().supportMergedInto(data.id()) != null) {
            // 2026-10-26：低階 I／II 輔助換成同系列最高階那顆（共鳴保留、整疊一起換）
            replacement = GemData.support(config().supportMergedInto(data.id()), data.resonant());
            amount = stack.getAmount();
        } else if (data.kind() == GemData.Kind.SUPPORT && config().support(data.id()) == null) {
            replacement = GemData.uncutSupport(config().retiredSupportTier(data.id()));
            amount = stack.getAmount();
        }
        if (replacement == null) {
            return null;
        }
        final ItemStack fresh = create(replacement, amount);
        stack.setType(fresh.getType());
        stack.setAmount(fresh.getAmount());
        stack.setItemMeta(fresh.getItemMeta());
        final io.papermc.paper.datacomponent.item.CustomModelData model = fresh.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model != null) {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA, model);
        } else {
            stack.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
        }
        return extra;
    }

    /**
     * 一組陣列（倉庫的頁、隨身背包的頁、離線的背包）裡拿掉的技能、輔助寶石原地換成未切割寶石；
     * 技能寶石上拆下來的輔助放進同一組陣列的空格。放不下的回傳（呼叫端給玩家或記在主控台）。
     */
    public List<ItemStack> migrateArrays(final List<ItemStack[]> arrays) {
        final List<ItemStack> extras = new ArrayList<>();
        for (final ItemStack[] array : arrays) {
            for (final ItemStack stack : array) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                final List<ItemStack> extra = migrateRemoved(stack);
                if (extra != null) {
                    extras.addAll(extra);
                }
            }
        }
        final List<ItemStack> leftover = new ArrayList<>();
        for (final ItemStack extra : extras) {
            boolean placed = false;
            for (final ItemStack[] array : arrays) {
                for (int i = 0; i < array.length && !placed; i++) {
                    if (array[i] == null || array[i].isEmpty()) {
                        array[i] = extra;
                        placed = true;
                    }
                }
                if (placed) {
                    break;
                }
            }
            if (!placed) {
                leftover.add(extra);
            }
        }
        return leftover;
    }

    /** 主控台用：一串寶石的代號。 */
    public String describe(final List<ItemStack> stacks) {
        final List<String> ids = new ArrayList<>();
        for (final ItemStack stack : stacks) {
            read(stack).ifPresent(data -> ids.add(data.kind().key() + ":" + (data.id() == null ? data.level() : data.id())));
        }
        return String.join(",", ids);
    }

    /** 還在的輔助做成輔助寶石，拿掉的（2026-10 PoE2 改版）換成同階的未切割輔助寶石。 */
    public ItemStack supportOrUncut(final String supportId) {
        if (config().support(supportId) != null) {
            return create(GemData.support(supportId), 1);
        }
        if (config().supportMergedInto(supportId) != null) { // 2026-10-26：低階 I／II 換成同系列最高階
            return create(GemData.support(config().supportMergedInto(supportId)), 1);
        }
        return create(GemData.uncutSupport(config().retiredSupportTier(supportId)), 1);
    }

    /** 一次放進背包（放不下的掉在腳邊）。 */
    public void giveAll(final Player player, final List<ItemStack> stacks) {
        for (final ItemStack stack : stacks) {
            give(player, stack);
        }
    }

    /** 做出寶石物品（技能寶石一次一顆）。 */
    public ItemStack create(final GemData data, final int amount) {
        final ItemStack stack = ItemStack.of(material(data.kind()), Math.max(1, Math.min(data.maxStack(), amount)));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line(name(data)));
            meta.lore(tw.exilecore.core.text.TextWrap.lines(lore(data))); // 2026-10-26：長的說明自動換行（這次之前一行到底）
            meta.setMaxStackSize(data.maxStack());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            meta.setEnchantmentGlintOverride(data.kind() == GemData.Kind.SKILL || data.kind() == GemData.Kind.SUPPORT);
            if (resourcePack) {
                final NamespacedKey model = NamespacedKey.fromString("exilecore:gem/" + data.kind().key());
                if (model != null) {
                    meta.setItemModel(model);
                }
            } else if (data.kind() == GemData.Kind.SUPPORT) {
                // 2026-10-26：沒有資源包時輔助寶石看起來是對應顏色的染料（紅／綠／淡藍；白色的照舊惡魂之淚），只換外觀、不換物品
                final String dye = GemAttributes.dyeModel(SupportColours.colour(data.id()));
                if (dye != null) {
                    meta.setItemModel(NamespacedKey.minecraft(dye));
                }
            }
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, data.encode());
            final CurrencyType currency = currencyOf(data);
            if (currency != null) {
                // 未切割寶石算通貨（2026-10）：掛上通貨的標記，通貨袋、倉庫的通貨頁、通貨交易所都認得
                meta.getPersistentDataContainer().set(plugin.currencies().key(), PersistentDataType.STRING, currency.id());
            }
        });
        final CurrencyType currency = currencyOf(data);
        if (currency != null) {
            // 未切割寶石的專屬貼圖（web/currency_art.txt 的 uncut_skill_等級、uncut_support_階級；左上角寫等級）
            tw.exilecore.currency.CurrencyService.decorate(stack, currency);
        } else if (data.kind() == GemData.Kind.SKILL || data.kind() == GemData.Kind.SUPPORT) {
            // 技能寶石、輔助寶石的貼圖（web/currency_art.txt 的 gem_skill、gem_support）
            // 技能寶石有自己技能的圖示就用那張（web/skill_art.txt）
            final String skillArt = data.kind() == GemData.Kind.SKILL ? SkillArt.artOf(data.id()) : SkillArt.supportArtOf(data.id()); // 2026-10：輔助也有自己的圖
            final String art = skillArt != null ? skillArt : data.kind() == GemData.Kind.SKILL ? "gem_skill" : "gem_support";
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                            .addString(tw.exilecore.currency.CurrencyArt.modelKey(art)).build());
        }
        ItemRenderer.nativeText(stack); // 2026-10 效能：說明存成原版的文字元件
        return stack;
    }

    /** 這顆寶石當通貨是哪一種（未切割寶石、currencies.yml 也有那一種才算；其他回傳 null）。 */
    private CurrencyType currencyOf(final GemData data) {
        final String id = data.currencyId();
        return id == null || plugin.currencies() == null ? null : plugin.currencies().repository().get(id);
    }

    private static Material material(final GemData.Kind kind) {
        return switch (kind) {
            case UNCUT_SKILL -> Material.PRISMARINE_SHARD;
            case UNCUT_SUPPORT -> Material.QUARTZ;
            case SKILL -> Material.HEART_OF_THE_SEA;
            case SUPPORT -> Material.GHAST_TEAR;
        };
    }

    private String name(final GemData data) {
        return switch (data.kind()) {
            case UNCUT_SKILL -> "<#6fe3d0>未切割技能寶石 <gray>Lv." + data.level();
            case UNCUT_SUPPORT -> "<#9fc8ff>未切割輔助寶石 <gray>第 " + data.level() + " 階";
            case SKILL -> {
                final SkillDefinition definition = config().skill(data.id());
                // 2026-10-26 照 PoE2：名字照寶石的屬性上色（紅＝力量、綠＝敏捷、藍＝智慧、混色漸層）並標出屬性；這次之前一律金色
                yield definition == null ? "<red>不認得的技能：" + ItemRenderer.escape(data.id())
                        : (definition.engravable() ? "" : "<#d4a017>✦異質 ")
                        + GemAttributes.colourSkill(definition, "技能寶石：" + ItemRenderer.escape(definition.name()))
                        + " " + GemAttributes.skillBadge(definition) + " <gray>Lv." + data.level();
            }
            case SUPPORT -> {
                final SupportDefinition support = config().support(data.id());
                // 2026-10-26 照 PoE2：名字照輔助寶石的顏色（紅 §c 力量、綠 §a 敏捷、藍 §9 智慧、白色無屬性）並標出屬性；
                //   這次之前一般的淡藍、專屬的橘色（專屬的改成前面一個橘色 ✦）
                yield support == null ? "<red>不認得的輔助：" + ItemRenderer.escape(data.id())
                        : (support.cuttable() ? "" : "<#ff9f43>✦ ") + GemAttributes.supportTag(support.id()) + "輔助寶石：" + ItemRenderer.escape(support.name())
                        + " " + GemAttributes.supportBadge(support.id())
                        + (data.resonant() ? "<#c48bff>（共鳴）" : ""); // 2026-10-18：用過共鳴石的
            }
        };
    }

    private List<String> lore(final GemData data) {
        final List<String> lines = new ArrayList<>();
        switch (data.kind()) {
            case UNCUT_SKILL -> {
                lines.add("<gray>右鍵：做成一個 <white>Lv." + data.level() + "</white> 的技能寶石，");
                lines.add("<gray>或把技能面板上的技能直接升到 Lv." + data.level()); // 2026-10-27 照 PoE2；這次之前「升一級（最高到 Lv.N）」
                lines.add("<gray>能雕的技能：<white>" + config().engravable(data.level()).size() + "</white> 種");
                lines.add("<dark_gray>也可以拿在游標上點技能面板（/skills）裡的技能來升級");
                lines.add("<dark_gray><i>還沒刻上紋路的星骸，記得的東西還很模糊。");
            }
            case UNCUT_SUPPORT -> {
                lines.add("<gray>右鍵：切成一個 <white>第 " + data.level() + " 階以下</white> 的輔助寶石");
                lines.add("<gray>能切的輔助：<white>" + config().supportsUpTo(data.level()).size() + "</white> 種");
                lines.add("<dark_gray><i>一小塊碎星，只夠刻一道紋。");
            }
            case SKILL -> skillLore(data.entry(), lines);
            case SUPPORT -> supportLore(config().support(data.id()), data.resonant(), lines);
        }
        if (data.entry() != null && data.entry().quality() > 0) {
            final String text = SkillService.qualityText(config().skill(data.entry().skillId()), data.entry().quality()); // 2026-10-23
            if (text != null) {
                lines.add("<#8888ff>" + ItemRenderer.escape(text));
                // 2026-10-28：額外的品質效果（昇華「進階奇術」才有）也寫出來
                final String advanced = SkillService.advancedQualityText(config().skill(data.entry().skillId()), data.entry().quality());
                if (advanced != null) {
                    lines.add("<dark_gray>額外的品質效果（進階奇術）：" + ItemRenderer.escape(advanced));
                }
            }
        }
        if (data.entry() != null && data.entry().corrupted()) {
            lines.add("<#d20000>已腐化 <dark_gray>（不能再升級、開孔、加品質；輔助照樣能換）"); // 2026-10-23
        }
        lines.add("");
        if (currencyOf(data) != null) {
            lines.add("<dark_gray>撿到會收進通貨袋（/bag）；也能放倉庫的通貨頁、在通貨交易所買賣");
            lines.add("<dark_gray>未切割寶石（通貨）・堆疊上限 " + data.maxStack());
        } else {
            lines.add("<dark_gray>寶石・堆疊上限 " + data.maxStack());
        }
        return lines;
    }

    private void skillLore(final SkillLoadout.Entry entry, final List<String> lines) {
        final SkillDefinition definition = config().skill(entry.skillId());
        if (definition == null) {
            lines.add("<gray>skills.yml 裡沒有這個技能（被刪掉或改名了）");
            return;
        }
        lines.add(GemAttributes.skillBadge(definition) + " <gray>技能寶石"); // 2026-10-26 照 PoE2：第一行寫寶石的屬性（顏色）
        lines.add(SkillService.headlineChips(definition));
        if (!definition.engravable()) {
            lines.add("<#d4a017>異質品質：只有" + definition.sourceText() + "會掉，未切割技能寶石雕不出來");
        }
        if (!definition.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(definition.description()));
        }
        if (!definition.weapons().isEmpty()) {
            lines.add("<gray>需要" + definition.weaponText());
        }
        lines.add("<gray>需要角色等級 <white>" + config().requiredLevel(entry.level()));
        lines.addAll(GemAttributes.lines(config(), entry, null)); // 2026-10-26 照 PoE2：屬性需求（含插著的輔助寶石加的）
        lines.add("");
        lines.add("<gray>輔助孔：<white>" + entry.sockets() + "</white>／" + SkillLoadout.MAX_SOCKETS);
        final boolean host = SupportDefinition.hostsSkill(definition); // 2026-10-22：第 1 孔放技能寶石
        for (int i = 0; i < entry.sockets(); i++) {
            final String id = entry.support(i);
            if (host && i == SkillLoadout.Entry.INNER_SOCKET) {
                lines.add(entry.inner() == null ? "<dark_gray> ◇ 放技能寶石（空；每一孔都能放）"
                        : "<#ffd27f> ◆ " + ItemRenderer.escape(skillName(entry.inner())) + " <gray>Lv." + entry.inner().level() + "（放在裡面的技能）");
            } else if (entry.spellSocket(i)) { // 2026-10-26：宿主寶石其他孔的技能寶石
                final SkillLoadout.Entry spell = entry.spellAt(i);
                lines.add("<#ffd27f> ◆ " + ItemRenderer.escape(skillName(spell)) + " <gray>Lv." + spell.level() + "（放在裡面的技能）");
            } else if (id.isEmpty()) {
                lines.add("<dark_gray> ◇ 空");
            } else {
                final SupportDefinition support = config().support(id);
                lines.add(GemAttributes.supportTag(id) + " ◆ " + ItemRenderer.escape(support == null ? id : support.name()) // 2026-10-26：照顏色
                        + " " + GemAttributes.supportBadge(id)
                        + (entry.resonant(i) ? "<#c48bff>（共鳴）" : "")); // 2026-10-18
            }
        }
        lines.add("");
        lines.add("<yellow>右鍵：放進技能面板");
    }

    private void supportLore(final SupportDefinition support, final boolean resonant, final List<String> lines) {
        if (support == null) {
            lines.add("<gray>supports.yml 裡沒有這個輔助（被刪掉或改名了）");
            return;
        }
        lines.add(GemAttributes.supportLine(support.id())); // 2026-10-26 照 PoE2：第一行寫輔助寶石的顏色與加的屬性需求
        if (support.lineage()) {
            lines.add("<#ff9f43>✦ 血脈輔助寶石<dark_gray>（未切割輔助寶石切不出來）");
            lines.add("<#ff9f43>血脈輔助寶石<dark_gray>（不同的血脈輔助可以掛在同一個技能上；不能用共鳴石）"); // 2026-10-28 照 PoE2；這次之前「一個技能只能掛一顆血脈輔助寶石」
        } else if (!support.cuttable()) {
            lines.add("<#ff9f43>✦ 專屬掉落的輔助寶石<dark_gray>（未切割輔助寶石切不出來）");
            if (resonant) {
                lines.add("<#c48bff>共鳴：不佔一個技能的專屬輔助名額"); // 2026-10-18：用過共鳴石
            } else {
                lines.add("<#ff9f43>一個技能只能掛一顆沒有共鳴的專屬掉落輔助寶石<dark_gray>（用共鳴石就不佔名額）");
            }
        }
        if (!support.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(support.descriptionText()));
        }
        lines.addAll(effectLines(support));
        lines.add("");
        lines.add("<gray>可以輔助：<white>" + support.supportsText(config().all()));
        lines.add("<gray>第 " + support.tier() + " 階・" + (config().oncePerCharacter(support.id())
                ? "同一種輔助一個角色只能用一次" : "不同技能可以各掛一顆（一顆只能掛一個技能）"));
        lines.add("<yellow>打開 /skills，拿在游標上點技能掛上去");
    }

    /** 輔助寶石的效果（每條修飾器一行，加上魔力倍率與備註）。 */
    public static List<String> effectLines(final SupportDefinition support) {
        final List<String> lines = new ArrayList<>();
        boolean procs = false;
        for (final Modifier modifier : support.modifiers()) {
            lines.add("<#8fd3ff>" + ItemRenderer.escape(ModifierText.describe(modifier)));
            if (ProcText.isProc(modifier)) {
                // 2026-10：觸發的專屬技能實際做什麼（範圍、傷害、持續時間…）
                procs = true;
                for (final String detail : ProcText.details(modifier.tags().iterator().next())) {
                    lines.add("<gray>　" + ItemRenderer.escape(detail));
                }
            }
        }
        if (Math.abs(support.manaMultiplier() - 1.0) > 1e-9) {
            lines.add(String.format(Locale.ROOT, "<gray>魔力消耗 <white>×%s", ModifierText.number(support.manaMultiplier())));
        }
        if (support.modifiers().stream().anyMatch(m -> m.stat().startsWith("trigger."))) {
            lines.add("<#f5b942>被輔助的法術要綁在上方按鍵格才會被觸發（不用按）");
        }
        if (support.spiritSupport()) {
            lines.add("<#f5b942>要保留精魂的輔助：一個角色只能掛一顆");
        }
        for (final String note : support.notes()) {
            lines.add("<dark_gray>" + ItemRenderer.escape(note));
        }
        if (procs) {
            lines.add("<dark_gray>" + ItemRenderer.escape(ProcText.RULES));
        }
        return lines;
    }

    /** 一顆切好的輔助寶石（終局、極境的專屬掉落）；supports.yml 沒有這個代號回傳 null。 */
    public ItemStack createSupport(final String supportId) {
        return config().support(supportId) == null ? null : create(GemData.support(supportId), 1);
    }

    /**
     * 怪物掉落的一顆未切割寶石（等級或階級依怪物等級擲）。技能寶石擲到 skills.yml 的 uncut_level_scale 有寫的等級時照那個機率留下，
     * 沒留下回傳 null（這一顆不掉；掉落那邊碰到 null 會跳過）。
     */
    public ItemStack createDrop(final boolean skill, final int monsterLevel, final RandomGenerator random) {
        if (!skill) {
            return create(GemData.uncutSupport(config().rollSupportTier(monsterLevel, random)), 1);
        }
        final int level = config().rollUncutLevel(monsterLevel, random);
        return config().keepsUncut(level, random) ? create(GemData.uncutSkill(level), 1) : null;
    }

    // ---- 右鍵 ----

    /**
     * 右鍵手上的寶石。回傳 true 表示是寶石（施放與原版的右鍵都不要做）。
     * 副手的右鍵只擋掉，不做事（主手、副手的事件會各來一次）。
     */
    public boolean use(final Player player, final ItemStack item, final EquipmentSlot hand) {
        final GemData data = read(item).orElse(null);
        if (data == null) {
            return false;
        }
        if (hand != EquipmentSlot.HAND) {
            return true;
        }
        final int held = player.getInventory().getHeldItemSlot();
        switch (data.kind()) {
            case UNCUT_SKILL -> new EngraveMenu(plugin, player, skills, UncutSource.hand(held), data.level()).open();
            case UNCUT_SUPPORT -> new SupportCutMenu(plugin, player, skills, UncutSource.hand(held), data.level()).open();
            case SKILL -> {
                final String error = addFromHand(player, held, data);
                if (error != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(error)));
                    sound(player, false);
                }
            }
            case SUPPORT -> {
                new SkillMenu(plugin, player, skills).open();
                player.sendMessage(plugin.text().prefixed("<gray>拿起輔助寶石，點一個技能就能掛上去"));
            }
        }
        return true;
    }

    /** 右鍵手上的技能寶石：放進面板第一個空格。 */
    private String addFromHand(final Player player, final int heldSlot, final GemData data) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return "先選一個角色";
        }
        final Change change = place(player, -1, data);
        if (change.failed()) {
            return change.error();
        }
        consume(player, heldSlot);
        final SkillDefinition definition = config().skill(data.id());
        player.sendMessage(plugin.text().prefixed("<green>技能面板加入 " + ItemRenderer.escape(definition == null ? data.id() : definition.name())
                + " Lv." + data.level() + "，<white>/skills</white> 綁到按鍵上"));
        sound(player, true);
        plugin.characters().save(player);
        return null;
    }

    // ---- 雕琢（選單用） ----

    /**
     * 雕琢、切割、升級用的那顆未切割寶石在哪裡：快捷列的一格（右鍵手上的），或通貨袋（2026-10 未切割寶石算通貨，
     * 撿到會收進通貨袋；在隨身背包的通貨頁 Shift＋右鍵直接用）。
     *
     * @param slot       快捷列的格子（通貨袋是 -1）
     * @param currencyId 通貨袋裡的那一種（快捷列是 null）
     */
    record UncutSource(int slot, String currencyId) {
        static UncutSource hand(final int slot) {
            return new UncutSource(slot, null);
        }

        static UncutSource pouch(final String currencyId) {
            return new UncutSource(-1, currencyId);
        }

        /** 2026-10-23：倉庫的通貨頁（升級時背包、通貨袋都沒有才用）。 */
        static UncutSource stash(final String currencyId) {
            return new UncutSource(-2, currencyId);
        }

        /** 存在通貨袋或倉庫（不是背包裡的一疊）。 */
        boolean fromPouch() {
            return currencyId != null;
        }

        boolean fromStash() {
            return currencyId != null && slot == -2;
        }
    }

    /** 從通貨袋直接雕琢或切割（隨身背包的通貨頁 Shift＋右鍵）：打開對應的選單；不是未切割寶石回傳 false。 */
    public boolean openFromPouch(final Player player, final String currencyId) {
        final GemData data = GemData.fromCurrencyId(currencyId).orElse(null);
        if (data == null || !plugin.players().get(player).hasCharacter()) {
            return false;
        }
        final UncutSource source = UncutSource.pouch(currencyId);
        if (held(player, source, data.kind()) == null) {
            return false;
        }
        if (data.kind() == GemData.Kind.UNCUT_SKILL) {
            new EngraveMenu(plugin, player, skills, source, data.level()).open();
        } else {
            new SupportCutMenu(plugin, player, skills, source, data.level()).open();
        }
        return true;
    }

    /** 來源上的未切割寶石（種類不對、用完了、不見了回傳 null）。 */
    GemData held(final Player player, final UncutSource source, final GemData.Kind kind) {
        final GemData data;
        if (source.fromPouch()) {
            data = count(player, source) > 0 ? GemData.fromCurrencyId(source.currencyId()).orElse(null) : null;
        } else {
            data = read(player.getInventory().getItem(source.slot())).orElse(null);
        }
        return data != null && data.kind() == kind ? data : null;
    }

    /** 來源上還有幾顆。 */
    int count(final Player player, final UncutSource source) {
        if (source.fromStash()) {
            final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
            return stash == null ? 0 : stash.currency(source.currencyId());
        }
        if (source.fromPouch()) {
            final PlayerState state = plugin.players().get(player);
            return state.bag() != null && state.bag().usable() ? state.bag().pouch().amount(source.currencyId()) : 0;
        }
        final ItemStack stack = player.getInventory().getItem(source.slot());
        return stack == null || stack.isEmpty() || !isGem(stack) ? 0 : stack.getAmount();
    }

    /** 選單上顯示的那一疊（用完了、不見了回傳 null）。 */
    ItemStack display(final Player player, final UncutSource source) {
        if (source.fromPouch()) {
            final int count = count(player, source);
            final GemData data = GemData.fromCurrencyId(source.currencyId()).orElse(null);
            return count <= 0 || data == null ? null : create(data, Math.min(count, data.maxStack()));
        }
        final ItemStack stack = player.getInventory().getItem(source.slot());
        return stack == null || stack.isEmpty() ? null : stack;
    }

    /** 從來源扣一顆，記進經濟流水帳。 */
    private void take(final Player player, final UncutSource source, final GemData uncut) {
        if (source.fromStash()) {
            final tw.exilecore.stash.Stash stash = plugin.stash().stash(player).orElseThrow();
            stash.setCurrency(source.currencyId(), stash.currency(source.currencyId()) - 1);
        } else if (source.fromPouch()) {
            plugin.players().get(player).bag().pouch().withdraw(source.currencyId(), 1);
        } else {
            consume(player, source.slot());
        }
        used(uncut);
    }

    /** 用掉一顆未切割寶石：記進經濟流水帳的消耗（2026-10 未切割寶石算通貨，跟其他通貨一樣記）。 */
    void used(final GemData uncut) {
        final CurrencyType currency = currencyOf(uncut);
        if (currency != null) {
            plugin.economy().ledger().consumed(currency.id(), tw.exilecore.economy.EconomyLedger.SKILL, 1);
        }
    }

    private static String gone(final UncutSource source, final String what) {
        return source.fromStash() ? "倉庫裡的" + what + "用完了"
                : source.fromPouch() ? "通貨袋裡的" + what + "用完了" : "手上的" + what + "不見了";
    }

    /** 用一顆未切割技能寶石雕一個技能：放進面板（有空格、面板上沒有這個技能），不然變成寶石放進背包。 */
    String engrave(final Player player, final UncutSource source, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final GemData uncut = held(player, source, GemData.Kind.UNCUT_SKILL);
        if (!state.hasCharacter()) {
            return "先選一個角色";
        }
        if (uncut == null) {
            return gone(source, "未切割技能寶石");
        }
        final SkillDefinition definition = config().skill(skillId);
        if (definition == null || definition.hidden()) {
            return "沒有這個技能：" + skillId;
        }
        if (!definition.engravable()) {
            return definition.name() + "雕不出來（只有" + definition.sourceText() + "會掉）";
        }
        if (definition.tier() > uncut.level()) {
            return "要 Lv." + definition.tier() + " 以上的未切割技能寶石才能雕出" + definition.name();
        }
        take(player, source, uncut);
        final SkillLoadout.Entry entry = new SkillLoadout.Entry(definition.id(), uncut.level());
        // 同系列（異質寶石）已經在面板上：放進背包（2026-10）
        final boolean sibling = config().familyConflict(state.skills(), entry.skillId(), -1) >= 0;
        // 2026-10-28 照 PoE2：同一個技能可以放好幾顆，面板上有也照樣放進空格；只有一個角色一顆的常駐、宿主放進背包
        //   （這次之前面板上有這個技能就放進背包）
        final boolean single = GemCopies.placeProblem(config(), state.skills(), entry, -1) != null;
        final int position = sibling ? -3 : single ? -2 : state.skills().add(entry);
        if (position >= 0) {
            player.sendMessage(plugin.text().prefixed("<green>做出 " + ItemRenderer.escape(definition.name()) + " Lv." + entry.level()
                    + "，已經放進技能面板（<white>/skills</white> 綁到按鍵上）"));
        } else {
            give(player, create(GemData.skill(entry), 1));
            player.sendMessage(plugin.text().prefixed("<green>做出技能寶石：" + ItemRenderer.escape(definition.name()) + " Lv." + entry.level()
                    + (position == -2 ? "<gray>（面板上已經有這個技能，常駐、宿主技能一個角色只能放一顆，寶石放進背包）"
                    : position == -3 ? "<gray>（面板上有它的異質寶石，同系列只能放一個，寶石放進背包）" : "<gray>（技能面板滿了，寶石放進背包）")));
        }
        plugin.characters().save(player); // 寶石扣掉以後才存，當機時才不會兩邊都在
        sound(player, true);
        return null;
    }

    /** 用一顆未切割技能寶石把面板上的技能升到那顆寶石的等級（2026-10-27 照 PoE2；這次之前一顆升一級）。 */
    String levelUpFrom(final Player player, final UncutSource source, final int position) {
        final GemData uncut = held(player, source, GemData.Kind.UNCUT_SKILL);
        if (uncut == null) {
            return gone(source, "未切割技能寶石");
        }
        final String error = levelUp(player, position, uncut.level());
        if (error == null) {
            take(player, source, uncut);
            sound(player, true);
            plugin.characters().save(player); // 寶石扣掉以後才存，當機時才不會兩邊都在
        }
        return error;
    }

    /**
     * 用一顆 Lv.uncutLevel 的未切割技能寶石把面板上的技能直接升到 Lv.uncutLevel（2026-10-27 照 PoE2 0.5：未切割寶石的等級要比技能高，
     * 角色等級要夠新的等級；這次之前一顆升一級）。不扣寶石、不存檔，由呼叫端扣掉寶石之後再存。
     */
    String levelUp(final Player player, final int position, final int uncutLevel) {
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout.Entry entry = state.skills().skill(position);
        if (entry == null) {
            return "那一格沒有技能";
        }
        final String problem = levelUpProblem(state, entry, uncutLevel);
        if (problem != null) {
            return problem;
        }
        final int next = levelUpTarget(entry, uncutLevel); // 2026-10-27 照 PoE2：直接升到未切割寶石的等級（這次之前 entry.level() + 1）
        state.skills().set(position, entry.withLevel(next));
        final SkillDefinition definition = config().skill(entry.skillId());
        final int usable = usableByCharacter(state.level(), next);
        player.sendMessage(plugin.text().prefixed("<green>" + ItemRenderer.escape(definition == null ? entry.skillId() : definition.name())
                + " 升到 Lv." + next + (usable < next
                ? "<yellow>（角色等級 " + state.level() + "：先自動以 Lv." + usable + " 使用，升級後自動恢復）" : "")));
        return null;
    }

    /** 不能升級的原因；可以升回傳 null。 */
    String levelUpProblem(final PlayerState state, final SkillLoadout.Entry entry, final int uncutLevel) {
        if (entry.granted()) {
            return GRANTED_LEVEL; // 2026-10-26
        }
        if (entry.corrupted()) {
            return "已腐化的技能寶石不能再升級"; // 2026-10-23
        }
        if (entry.level() >= SkillLoadout.GEM_CAP) {
            return "已經是最高等級（Lv." + SkillLoadout.GEM_CAP + "）";
        }
        // 2026-10-27 照 PoE2 0.5：未切割技能寶石直接把技能升到它的等級（要比技能現在的等級高）；這次之前一顆升一級、Lv.20 之後要 Lv.21 的
        if (entry.level() >= SkillLoadout.MAX_LEVEL && uncutLevel < SkillLoadout.GEM_CAP) {
            return "Lv." + SkillLoadout.MAX_LEVEL + " 之後要 Lv." + SkillLoadout.GEM_CAP + " 的未切割技能寶石（劫盜大金庫、金庫之主）";
        }
        final int next = levelUpTarget(entry, uncutLevel);
        if (next <= entry.level()) {
            return "要 Lv." + (entry.level() + 1) + " 以上的未切割技能寶石才能升級（會直接升到未切割寶石的等級）";
        }
        // 2026-10-28 照站長：不看角色等級，直接升到未切割寶石的等級；用的時候照角色等級、屬性自動降到能用的等級（SkillBuild）
        return null;
    }

    /** 2026-10-28：這個角色等級用 Lv.gemLevel 的寶石實際能用幾級（跟 SkillBuild 一樣：Lv.21 跟 Lv.20 同樣門檻）。 */
    int usableByCharacter(final int characterLevel, final int gemLevel) {
        final int usable = gemLevel > SkillLoadout.MAX_LEVEL && characterLevel >= config().requiredLevel(SkillLoadout.MAX_LEVEL)
                ? Math.min(gemLevel, SkillLoadout.GEM_CAP) : config().maxLevelFor(characterLevel);
        return Math.max(1, Math.min(gemLevel, usable));
    }

    /** 2026-10-27 照 PoE2：用 Lv.uncutLevel 的未切割技能寶石升級後的等級（＝未切割寶石的等級，最高 Lv.21）。 */
    static int levelUpTarget(final SkillLoadout.Entry entry, final int uncutLevel) {
        return Math.min(SkillLoadout.GEM_CAP, uncutLevel);
    }

    /**
     * 技能面板的「一鍵升級」：從背包、通貨袋或倉庫找一顆比技能高、角色用得了的未切割技能寶石。
     * 2026-10-27 照 PoE2（直接升到未切割寶石的等級）：挑角色用得了的最高等級那顆（一顆就升到能用的最高；這次之前一顆升一級，挑夠用、等級最低的）。
     * 找不到回傳 null。
     */
    UncutSource bestForLevelUp(final Player player, final SkillLoadout.Entry entry) {
        final PlayerState state = plugin.players().get(player);
        if (entry == null || levelUpProblem(state, entry, Math.min(SkillLoadout.GEM_CAP, entry.level() + 1)) != null) {
            return null; // 已經最高等級、角色等級不夠：用什麼寶石都一樣不行
        }
        final int need = entry.level() + 1;
        int cap = need;
        for (int level = SkillLoadout.GEM_CAP; level >= need; level--) {
            if (levelUpProblem(state, entry, level) == null) {
                cap = level; // 角色等級用得了的最高等級
                break;
            }
        }
        UncutSource best = null;
        int bestLevel = need - 1;
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            final GemData data = read(inventory.getItem(slot)).orElse(null);
            if (data != null && data.kind() == GemData.Kind.UNCUT_SKILL && data.level() > bestLevel && data.level() <= cap) {
                best = UncutSource.hand(slot);
                bestLevel = data.level();
            }
        }
        if (state.bag() != null && state.bag().usable()) {
            for (int level = cap; level > bestLevel; level--) { // 2026-10：21 級的也找得到
                final String id = GemData.uncutSkill(level).currencyId();
                if (state.bag().pouch().amount(id) > 0) {
                    best = UncutSource.pouch(id);
                    bestLevel = level;
                    break;
                }
            }
        }
        // 2026-10-23：倉庫的通貨頁也找（Lv.21 的常常存在倉庫裡）；背包、通貨袋有同樣等級的先用那邊的
        final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
        if (stash != null) {
            for (int level = cap; level > bestLevel; level--) {
                final String id = GemData.uncutSkill(level).currencyId();
                if (stash.currency(id) > 0) {
                    best = UncutSource.stash(id);
                    bestLevel = level;
                    break;
                }
            }
        }
        return best;
    }

    /** 2026-10-27：那個來源的未切割技能寶石是幾級（讀不到回傳 0）。 */
    int uncutLevel(final Player player, final UncutSource source) {
        if (source == null) {
            return 0;
        }
        final GemData data = source.fromPouch() ? GemData.fromCurrencyId(source.currencyId()).orElse(null)
                : read(player.getInventory().getItem(source.slot())).orElse(null);
        return data == null ? 0 : data.level();
    }

    /** 那個來源的寶石叫什麼（「通貨袋裡的未切割技能寶石 Lv.5」）。 */
    String describe(final Player player, final UncutSource source) {
        final GemData data = source.fromPouch() ? GemData.fromCurrencyId(source.currencyId()).orElse(null)
                : read(player.getInventory().getItem(source.slot())).orElse(null);
        final String name = data == null ? "未切割技能寶石" : (data.kind() == GemData.Kind.UNCUT_SUPPORT ? "未切割輔助寶石 第 " + data.level() + " 階"
                : "未切割技能寶石 Lv." + data.level());
        return (source.fromStash() ? "倉庫裡的" : source.fromPouch() ? "通貨袋裡的" : "背包裡的") + name;
    }

    /** 用一顆未切割輔助寶石切一個輔助寶石（放進背包）。 */
    String cutSupport(final Player player, final UncutSource source, final String supportId) {
        return cutSupport(player, source, supportId, -1);
    }

    /**
     * 2026-10：切好直接掛到技能面板 position 那個技能的空孔（position &lt; 0、沒有空孔或掛不上：放進背包）。
     */
    String cutSupport(final Player player, final UncutSource source, final String supportId, final int position) {
        final GemData uncut = held(player, source, GemData.Kind.UNCUT_SUPPORT);
        if (uncut == null) {
            return gone(source, "未切割輔助寶石");
        }
        final SupportDefinition support = config().support(supportId);
        if (support == null) {
            return "沒有這個輔助：" + supportId;
        }
        if (!support.cuttable()) {
            return support.name() + "是專屬掉落的輔助寶石，切不出來";
        }
        if (support.tier() > uncut.level()) {
            return "要第 " + support.tier() + " 階的未切割輔助寶石才能切出" + support.name();
        }
        take(player, source, uncut);
        final SkillLoadout.Entry target = position >= 0 ? plugin.players().get(player).skills().skill(position) : null;
        Change socketed = null;
        if (target != null && target.freeSocket() >= 0 && !target.hasSupport(support.id())) {
            socketed = socket(player, position, -1, support.id());
        }
        if (socketed != null && !socketed.failed()) {
            if (socketed.returned() != null) {
                give(player, socketed.returned());
            }
            plugin.players().markDirty(player);
            plugin.characters().save(player);
            player.sendMessage(plugin.text().prefixed("<green>切出輔助寶石並掛上：" + ItemRenderer.escape(support.name())
                    + " <gray>→ " + ItemRenderer.escape(skillName(target))));
        } else {
            give(player, create(GemData.support(support.id()), 1));
            player.sendMessage(plugin.text().prefixed("<green>切出輔助寶石：" + ItemRenderer.escape(support.name())
                    + (socketed != null ? "<gray>（沒掛上：" + ItemRenderer.escape(socketed.error()) + "，放進背包）"
                    : target != null ? "<gray>（" + ItemRenderer.escape(skillName(target)) + (target.hasSupport(support.id()) ? "已經掛著" : "沒有空孔") + "，放進背包）"
                    : "<gray>（打開 /skills，拿著它點技能掛上去）")));
            if (source.fromPouch()) {
                plugin.characters().save(player);
            }
        }
        sound(player, true);
        return null;
    }

    // ---- 技能面板 ----

    /**
     * 把技能寶石放進面板：position = -1 放第一個空格；指定的格子有技能就換下來（換下來的變成寶石回傳）。
     * 開了「同一種輔助一個角色只能用一次」（supports.yml）時，寶石上掛的輔助已經掛在別的技能上就不能放。
     */
    Change place(final Player player, final int position, final GemData gem) {
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry entry = gem.entry();
        if (gem.kind() != GemData.Kind.SKILL || entry == null) {
            return Change.fail("這不是技能寶石");
        }
        final SkillDefinition definition = config().skill(entry.skillId());
        if (definition == null || definition.hidden()) {
            return Change.fail("不認得的技能：" + entry.skillId());
        }
        if (definition.grantedOnly()) { // 2026-10-28 照 PoE2：賦予的技能沒有寶石（舊的寶石重新整理背包時會換成未切割技能寶石）
            return Change.fail(definition.name() + "是" + definition.sourceText() + "的技能（不是寶石）：裝備賦予它的武器或點那個昇華天賦就會出現在面板上");
        }
        final int target = position >= 0 ? position : firstEmpty(loadout);
        if (target < 0) {
            return Change.fail("技能面板滿了（最多 " + SkillLoadout.MAX_SKILLS + " 個），先取出一個");
        }
        // 2026-10-28 照 PoE2：同一個技能可以放好幾顆（面板上、主要寶石孔裡都可以），各自的輔助；只擋一個角色一顆的常駐、宿主。
        //   這次之前面板上、宿主孔裡已經有就不能放
        final String copies = GemCopies.placeProblem(config(), loadout, entry, target);
        if (copies != null) {
            return Change.fail(copies);
        }
        final int sibling = config().familyConflict(loadout, entry.skillId(), target);
        if (sibling >= 0) {
            return Change.fail("面板上已經有" + skillName(loadout.skill(sibling)) + "（異質寶石跟原本的技能只能放一個）");
        }
        if (entry.inner() != null && !SupportDefinition.hostsSkill(definition) && !SocketedWarcry.hosts(definition)) { // 2026-10-27：裂甲熊咆也可以
            return Change.fail("這顆寶石裡放著技能，但" + definition.name() + "不是英靈圖騰、星咒圖騰或暴擊引星"); // 不該發生
        }
        // 2026-10-28 照 PoE2：宿主孔裡放著的技能面板上也可以再有一顆（自己按的那顆用自己的輔助）；
        //   這次之前（2026-10-23）宿主裡放著的技能面板上不能再有一顆
        {
            for (final String supportId : entry.supportIds()) {
                final int used = config().oncePerCharacter(supportId) ? loadout.supportUsedBy(supportId) : -1;
                if (used >= 0 && used != target) {
                    return Change.fail(supportName(supportId) + "已經掛在" + skillName(loadout.skill(used)) + "上（這種輔助一個角色只能掛一顆），先拿下來");
                }
            }
        }
        final SkillLoadout.Entry old = loadout.skill(target);
        if (old != null && old.granted()) {
            return Change.fail("這一格是武器賦予的" + skillName(old) + "（不是寶石），放到別格，或先把它拿掉"); // 2026-10-26
        }
        if (old != null && !old.skillId().equals(entry.skillId())) {
            loadout.remove(target); // 換成別的技能：原本綁的按鍵改回普攻
        }
        loadout.set(target, entry);
        return Change.ok(old == null ? null : create(GemData.skill(old), 1));
    }

    /** 從面板取出技能，變成技能寶石（帶著等級、輔助孔與輔助寶石）。 */
    Change takeOut(final Player player, final int position) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("那一格沒有技能");
        }
        loadout.remove(position);
        if (entry.granted()) {
            // 2026-10-26：武器賦予的技能不是寶石：拿掉就好，掛著的輔助還給玩家（拿著那把武器時會再放回面板）
            final List<ItemStack> supports = new java.util.ArrayList<>();
            for (int i = 0; i < entry.sockets(); i++) {
                if (!entry.support(i).isEmpty() && !entry.spellSocket(i)) {
                    supports.add(create(GemData.support(entry.support(i), entry.resonant(i)), 1));
                }
            }
            for (final SkillLoadout.Entry spell : entry.spells()) {
                supports.add(create(GemData.skill(spell), 1)); // 2026-10-26：咒擲孔裡的技能寶石也還（這次之前跟著不見）
            }
            supports.addAll(investedRefund(entry)); // 2026-10-28：這次之前付費開的孔還沒退的，一起退（照等級開的不退）
            giveAll(player, supports);
            return Change.ok(null);
        }
        return Change.ok(create(GemData.skill(entry), 1));
    }

    /**
     * 2026-10-28 照 PoE2：賦予的技能不能用鑿星石開孔的說明（孔照技能等級自動開）。
     * 這次之前賦予的技能固定 2 孔、可以用鑿星石開；付費開過的孔在 SkillService.migrateRemoved 原數退還鑿星石。
     */
    static final String GRANTED_SOCKETS = "武器、昇華賦予的技能的輔助孔照等級自動開（Lv." + SkillLoadout.grantedSocketLevel(3) + " 第 3 孔、Lv."
            + SkillLoadout.grantedSocketLevel(4) + " 第 4 孔、Lv." + SkillLoadout.grantedSocketLevel(5) + " 第 5 孔），不能用鑿星石開";

    /** 2026-10-28：賦予的技能下一個孔幾級開（說明用）；已經 5 孔回傳上限的說明。 */
    static String grantedSocketHint(final SkillLoadout.Entry entry) {
        return entry.sockets() >= SkillLoadout.MAX_SOCKETS ? "最多 " + SkillLoadout.MAX_SOCKETS + " 個"
                : "賦予的技能 Lv." + SkillLoadout.grantedSocketLevel(entry.sockets() + 1) + " 開第 " + (entry.sockets() + 1) + " 孔";
    }

    /** 2026-10-28：還沒開的孔的說明（賦予的技能照等級，寶石用鑿星石）。 */
    static String lockedSocket(final SkillLoadout.Entry entry, final int index) {
        if (entry.granted() && index >= 0 && index < SkillLoadout.MAX_SOCKETS) {
            return "這個孔還沒開（賦予的技能 Lv." + SkillLoadout.grantedSocketLevel(index + 1) + " 才開第 " + (index + 1) + " 孔）";
        }
        return "這個孔還沒開（用鑿星石開孔）";
    }

    /**
     * 2026-10-28 照 PoE2：賦予的技能等級變低、孔變少時，多出來那幾孔的寶石還給玩家（輔助照原樣含共鳴；拿掉的輔助換未切割；
     * 放著的技能寶石照 {@link #innerRefund}）。沒有就不做事。
     */
    public void returnOverflow(final Player player, final List<SkillLoadout.Overflow> overflow) {
        if (player == null || overflow.isEmpty()) {
            return;
        }
        final List<ItemStack> give = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (final SkillLoadout.Overflow item : overflow) {
            give.add(overflowItem(item));
            names.add(item.spell() != null ? skillName(item.spell()) : supportName(item.support()));
        }
        giveAll(player, give);
        player.sendMessage(plugin.text().prefixed("<yellow>賦予的技能等級變低，輔助孔變少：<white>" + ItemRenderer.escape(String.join("、", names))
                + "<yellow> 放回背包"));
    }

    /** 2026-10-28：孔變少時拿出來的一樣東西做成物品。 */
    ItemStack overflowItem(final SkillLoadout.Overflow item) {
        if (item.spell() != null) {
            return innerRefund(item.spell());
        }
        return config().support(item.support()) != null ? create(GemData.support(item.support(), item.resonant()), 1) : supportOrUncut(item.support());
    }

    /** 2026-10-26：武器賦予的技能不能用未切割寶石升級的說明。 */
    static final String GRANTED_LEVEL = "武器賦予的技能：等級照武器的物品等級，不能用未切割寶石升級";

    /**
     * 2026-10-22：把技能寶石放進宿主寶石（英靈圖騰、星咒圖騰、引星）的第 1 孔。
     * 寶石自己掛的輔助拆下來放進背包（裡面的技能沒有自己的輔助：宿主其他孔的輔助就是它的）；
     * 原本放著的技能換下來回傳；宿主孔裡跟新技能不合的輔助也拆下來放進背包。
     */
    Change placeInner(final Player player, final int position, final GemData gem) {
        return placeSpell(player, position, SkillLoadout.Entry.INNER_SOCKET, gem);
    }

    /**
     * 2026-10-26 照 PoE2 Meta Gem（「Triggers all Socketed Spells」）：常駐的主要寶石（引星、咒擲）每一孔都能放法術的技能寶石。
     * 2026-10-27 照 PoE2（Spell Totem「uses socketed Spells」、Ancestral Warrior Totem「Place one or more Skill Gems」）：
     * 星咒圖騰、英靈圖騰也可以（圖騰／英靈照孔的順序輪流用）；這次之前它們只有第 1 孔能放。
     */
    boolean multiSpell(final SkillDefinition host) {
        return SupportDefinition.hostsSkill(host);
    }

    /**
     * 2026-10-26：把技能寶石放進宿主寶石的第 index 孔（其他孔裡不能有輔助；2026-10-27 起每種宿主寶石都可以）。
     * 原本放著的技能換下來回傳。
     */
    Change placeSpell(final Player player, final int position, final int index, final GemData gem) {
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry host = loadout.skill(position);
        final SkillDefinition hostDefinition = host == null ? null : config().skill(host.skillId());
        final boolean warcryHost = SocketedWarcry.hosts(hostDefinition); // 2026-10-27 照 PoE2 Ferocious Roar：裂甲熊咆的第 1 孔放一顆人形戰吼
        if (hostDefinition == null || !SupportDefinition.hostsSkill(hostDefinition) && !warcryHost) {
            return Change.fail("只有英靈圖騰、星咒圖騰、引星這類主要寶石（與裂甲熊咆的第 1 孔）能放技能寶石");
        }
        if (warcryHost && index != SkillLoadout.Entry.INNER_SOCKET) {
            return Change.fail(hostDefinition.name() + "只有第 1 孔能放人形戰吼（只能放一顆）");
        }
        if (warcryHost && host.inner() == null && !host.support(SkillLoadout.Entry.INNER_SOCKET).isEmpty()) {
            return Change.fail("第 1 孔掛著輔助寶石，先拿下來");
        }
        if (index != SkillLoadout.Entry.INNER_SOCKET) {
            if (index < 0 || index >= host.sockets()) {
                return Change.fail(lockedSocket(host, index));
            }
            if (!host.support(index).isEmpty() && !host.spellSocket(index)) {
                return Change.fail("這一孔掛著輔助寶石，先拿下來");
            }
        }
        final SkillLoadout.Entry entry = gem.entry();
        if (gem.kind() != GemData.Kind.SKILL || entry == null) {
            return Change.fail("這不是技能寶石");
        }
        final SkillDefinition definition = config().skill(entry.skillId());
        if (definition == null || definition.hidden()) {
            return Change.fail("不認得的技能：" + entry.skillId());
        }
        final String why = innerProblem(hostDefinition, definition);
        if (why != null) {
            return Change.fail(definition.name() + "不能放進" + hostDefinition.name() + "（" + why + "）");
        }
        // 2026-10-28 照 PoE2：面板上有一顆自己按的、別的主要寶石裡有、同一個主要寶石別孔有都可以再放（每一顆各自觸發）；
        //   只擋一個角色一顆的常駐、宿主。這次之前面板上或任何宿主孔裡已經有就不能放
        final String copies = GemCopies.socketProblem(config(), loadout, entry, position, index);
        if (copies != null) {
            return Change.fail(copies);
        }
        final SkillLoadout.Entry replaced = host.spellAt(index);
        if (!entry.spells().isEmpty()) {
            return Change.fail("這顆寶石裡還放著別的技能，先拿出來");
        }
        final List<ItemStack> extra = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < entry.sockets(); i++) {
            if (!entry.support(i).isEmpty()) { // 寶石自己的輔助拆下來
                extra.add(create(GemData.support(entry.support(i), entry.resonant(i)), 1));
                names.add(supportName(entry.support(i)));
            }
        }
        SkillLoadout.Entry updated = host.withSpell(index, new SkillLoadout.Entry(entry.skillId(), entry.level(), entry.sockets(), List.of())
                .withCorrupted(entry.corrupted()).withQuality(entry.quality())); // 2026-10-23：腐化、品質放進去也留著
        for (int i = SkillLoadout.Entry.INNER_SOCKET + 1; !warcryHost && index == SkillLoadout.Entry.INNER_SOCKET && i < updated.sockets(); i++) {
            final SupportDefinition support = config().support(updated.support(i));
            if (support != null && support.hostRefusal(hostDefinition, definition) != null) { // 跟新技能不合的輔助拆下來
                extra.add(create(GemData.support(updated.support(i), updated.resonant(i)), 1));
                names.add(support.name() + "（不能輔助" + definition.name() + "）");
                updated = updated.withSupport(i, "", false);
            }
        }
        loadout.set(position, updated);
        if (!extra.isEmpty()) {
            giveAll(player, extra);
            player.sendMessage(plugin.text().prefixed("<gray>拆下來放進背包：" + ItemRenderer.escape(String.join("、", names))
                    + "（放進" + ItemRenderer.escape(hostDefinition.name()) + "裡的技能用的是它其他孔的輔助）"));
        }
        return Change.ok(replaced == null ? null : create(GemData.skill(replaced), 1));
    }

    /** 2026-10-22：把宿主寶石第 1 孔的技能寶石拿出來（帶著它自己的孔數，沒有輔助）。 */
    Change takeInner(final Player player, final int position) {
        return takeSpell(player, position, SkillLoadout.Entry.INNER_SOCKET);
    }

    /** 2026-10-26：把宿主寶石第 index 孔的技能寶石拿出來。 */
    Change takeSpell(final Player player, final int position, final int index) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry host = loadout.skill(position);
        final SkillLoadout.Entry spell = host == null ? null : host.spellAt(index);
        if (spell == null) {
            return Change.fail("這一孔是空的");
        }
        loadout.set(position, host.withSpell(index, null));
        return Change.ok(create(GemData.skill(spell), 1));
    }

    /** 2026-10-22：這個技能能不能放進這種宿主寶石的孔；可以回傳 null，不行回傳原因。 */
    String innerProblem(final SkillDefinition host, final SkillDefinition inner) {
        if (inner.grantedOnly()) {
            return MetaGems.GRANTED_ONLY; // 2026-10-28 照 PoE2：武器、昇華賦予的技能不是寶石，哪一種宿主寶石都不能放
        }
        if (SkillExecutor.hasFlag(host, tw.exilecore.stats.StatKeys.FLAG_TOTEM_LINKED)) {
            return SkillService.linkedProblem(inner);
        }
        if (SocketedWarcry.hosts(host)) {
            return SocketedWarcry.problem(inner); // 2026-10-27 照 PoE2 Ferocious Roar：只能放人形戰吼
        }
        if (DeadeyeMetas.hosts(host)) {
            return DeadeyeMetas.problem(host, inner); // 2026-10-28 照 PoE2：預告射擊放印記、幻影銳眼放遠程投射物攻擊
        }
        final MetaSpec spec = config().meta(host.id());
        return spec == null ? "不是宿主寶石" : MetaGems.problem(spec, inner);
    }

    /** 把輔助寶石掛到技能的輔助孔（index = -1 掛第一個空孔）；孔裡原本有的換下來回傳。 */
    Change socket(final Player player, final int position, final int index, final String supportId) {
        return socket(player, position, index, supportId, false);
    }

    /** 2026-10-18：帶著寶石物品的共鳴旗標掛上去（共鳴的專屬輔助不佔專屬名額；拔下來時旗標會還給寶石）。 */
    Change socket(final Player player, final int position, final int index, final String supportId, final boolean resonant) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("先選一個技能");
        }
        final SkillDefinition definition = config().skill(entry.skillId());
        final SupportDefinition support = config().support(supportId);
        if (definition == null || support == null) {
            return Change.fail("不認得的技能或輔助");
        }
        final boolean host = SupportDefinition.hostsSkill(definition); // 2026-10-22：宿主寶石：第 1 孔放技能，輔助照裡面的技能驗
        if (host) {
            final String why = support.hostRefusal(definition, config().skill(entry.spells().isEmpty() ? "" : entry.spells().getFirst().skillId())); // 2026-10-27：孔裡第一個技能
            if (why != null) {
                return Change.fail(why);
            }
        } else if (!support.supports(definition)) {
            return Change.fail(support.name() + "不能輔助" + definition.name() + "（" + support.refusal(definition) + "）");
        }
        if (host && index == SkillLoadout.Entry.INNER_SOCKET) {
            return Change.fail(definition.name() + "的第 1 孔只放技能寶石，輔助請放其他孔");
        }
        if (index >= 0 && entry.spellSocket(index)) {
            return Change.fail("這一孔放著技能寶石，先拿出來"); // 2026-10-26
        }
        final int socket = index >= 0 ? index : entry.freeSocket();
        if (socket < 0) {
            return Change.fail(definition.name() + "沒有空的輔助孔了（" + (entry.granted() ? grantedSocketHint(entry)
                    : "用鑿星石開孔，最多 " + SkillLoadout.MAX_SOCKETS + " 個") + "）");
        }
        if (socket >= entry.sockets()) {
            return Change.fail(lockedSocket(entry, socket));
        }
        if (entry.hasSupport(supportId) && !supportId.equals(entry.support(socket))) {
            return Change.fail(definition.name() + "已經掛著" + support.name() + "了（同一個技能不能掛兩顆一樣的）");
        }
        final String exclusive = SupportDefinition.exclusiveConflict(entry, socket, support, resonant, config()::support); // 2026-10-18：看共鳴
        if (exclusive != null) {
            return Change.fail(exclusive);
        }
        if (config().oncePerCharacter(supportId)) {
            final int used = loadout.supportUsedBy(supportId);
            if (used >= 0 && used != position) {
                return Change.fail(support.name() + "已經掛在" + skillName(loadout.skill(used)) + "上（這種輔助一個角色只能掛一顆）");
            }
        }
        final String old = entry.support(socket);
        final boolean oldResonant = entry.resonant(socket);
        loadout.set(position, entry.withSupport(socket, supportId, resonant && !support.cuttable() && !support.lineage())); // 2026-10-27：血脈輔助不留共鳴
        return Change.ok(old.isEmpty() ? null : create(GemData.support(old, oldResonant), 1)); // 2026-10-18：換下來的保留共鳴
    }

    /**
     * 2026-10-18：用共鳴石把技能上第 index 孔的專屬輔助標成共鳴（之後不佔專屬名額）。
     * 孔要掛著沒共鳴的專屬輔助才行；通貨由技能面板扣。
     */
    Change resonate(final Player player, final int position, final int index, final CurrencyType orb) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("那一格沒有技能");
        }
        if (orb.operation() != CurrencyOperation.RESONANCE) {
            return Change.fail(orb.name() + "不能用在輔助上");
        }
        if (index < 0 || index >= entry.sockets() || entry.support(index).isEmpty() || entry.spellSocket(index)) {
            return Change.fail(orb.name() + "要點一顆已經掛上去的專屬輔助寶石");
        }
        final SupportDefinition support = config().support(entry.support(index));
        if (support == null || support.cuttable() || support.lineage()) { // 2026-10-27 照站長：血脈輔助不能共鳴
            return Change.fail(orb.name() + "只能用在專屬掉落的輔助寶石上（" + (support == null ? entry.support(index) : support.name()) + "不是）");
        }
        if (entry.resonant(index)) {
            return Change.fail(support.name() + "已經共鳴了");
        }
        loadout.set(position, entry.withResonant(index, true));
        plugin.economy().ledger().consumed(orb.id(), tw.exilecore.economy.EconomyLedger.CRAFT, 1); // 技能面板會把游標上的那顆用掉
        return Change.ok(null);
    }

    /** 把輔助寶石從技能上拿下來。 */
    Change unsocket(final Player player, final int position, final int index) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null || index < 0 || index >= entry.sockets() || entry.support(index).isEmpty()) {
            return Change.fail("這個孔是空的");
        }
        if (entry.spellSocket(index)) {
            return takeSpell(player, position, index); // 2026-10-26：宿主寶石其他孔的技能寶石
        }
        final String old = entry.support(index);
        final boolean oldResonant = entry.resonant(index);
        loadout.set(position, entry.withSupport(index, "", false));
        return Change.ok(create(GemData.support(old, oldResonant), 1)); // 2026-10-18：共鳴旗標還給寶石
    }

    /** 用鑿星石幫技能開一個輔助孔（小的開第 3 孔、普通的開第 4 孔、大的開第 5 孔）。 */
    Change addSocket(final Player player, final int position, final CurrencyType orb) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("那一格沒有技能");
        }
        if (orb.operation() != CurrencyOperation.SKILL_SOCKET) {
            return Change.fail(orb.name() + "不能用在技能上");
        }
        if (entry.granted()) {
            return Change.fail(GRANTED_SOCKETS); // 2026-10-28 照 PoE2：賦予的技能的孔照等級自動開
        }
        if (entry.corrupted()) {
            return Change.fail("已腐化的技能寶石不能再開孔"); // 2026-10-23
        }
        if (entry.sockets() >= SkillLoadout.MAX_SOCKETS) {
            return Change.fail("已經有 " + SkillLoadout.MAX_SOCKETS + " 個輔助孔了");
        }
        if (entry.sockets() != orb.sockets() - 1) {
            return Change.fail(orb.name() + "是開第 " + orb.sockets() + " 個孔用的（這個技能現在有 " + entry.sockets() + " 個孔，要用"
                    + socketOrbName(entry.sockets() + 1) + "）");
        }
        loadout.set(position, entry.withSockets(orb.sockets()));
        plugin.economy().ledger().consumed(orb.id(), tw.exilecore.economy.EconomyLedger.SKILL, 1); // 技能面板會把游標上的那顆用掉
        return Change.ok(null);
    }

    /**
     * 2026-10-23：蝕變石腐化面板上的技能寶石（照 PoE2，各 25%）：沒變化、等級 +1 或 −1、輔助孔 +1 或 −1、
     * 品質 −7%～+3%（最多 23%）。腐化後不能再升級、開孔、腐化；輔助照樣能換。
     * 少一孔時那一孔的輔助寶石還給玩家。
     */
    Change corrupt(final Player player, final int position, final CurrencyType orb) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("那一格沒有技能");
        }
        if (orb.operation() != CurrencyOperation.CORRUPT) {
            return Change.fail(orb.name() + "不能用在技能上");
        }
        if (entry.granted()) {
            return Change.fail("武器賦予的技能不是寶石，不能腐化"); // 2026-10-26
        }
        if (entry.corrupted()) {
            return Change.fail("這顆技能寶石已經腐化過了");
        }
        final java.util.random.RandomGenerator random = plugin.items().random();
        SkillLoadout.Entry updated = entry.withCorrupted(true);
        ItemStack returned = null;
        final String result;
        switch (random.nextInt(4)) {
            case 1 -> {
                final int next = Math.max(1, Math.min(SkillLoadout.GEM_CAP, entry.level() + (random.nextBoolean() ? 1 : -1)));
                updated = updated.withLevel(next);
                result = next == entry.level() ? "沒有變化" : next > entry.level() ? "等級 +1（Lv." + next + "）" : "等級 −1（Lv." + next + "）";
            }
            case 2 -> {
                final boolean add = random.nextBoolean();
                final int next = Math.max(SkillLoadout.BASE_SOCKETS, Math.min(SkillLoadout.MAX_SOCKETS, entry.sockets() + (add ? 1 : -1)));
                if (next < entry.sockets()) {
                    final int last = entry.sockets() - 1;
                    if (entry.spellSocket(last)) { // 2026-10-26：第 2～5 孔的技能寶石也一樣還
                        returned = create(GemData.skill(entry.spellAt(last)), 1);
                        updated = updated.withSpell(last, null);
                    } else if (!entry.support(last).isEmpty()) {
                        returned = create(GemData.support(entry.support(last), entry.resonant(last)), 1);
                        updated = updated.withSupport(last, "", false);
                    }
                }
                updated = updated.withSockets(next);
                result = next == entry.sockets() ? "沒有變化" : next > entry.sockets() ? "輔助孔 +1（" + next + " 孔）" : "輔助孔 −1（" + next + " 孔）";
            }
            case 3 -> {
                // 2026-10-23（PoE2）：品質變動 −7%～+3%（最多 23%）
                final int next = Math.max(0, Math.min(SkillLoadout.Entry.QUALITY_CORRUPTED_MAX, entry.quality() - 7 + random.nextInt(11)));
                updated = updated.withQuality(next);
                result = next == entry.quality() ? "沒有變化" : "品質 " + (next > entry.quality() ? "+" : "−") + Math.abs(next - entry.quality())
                        + "%（" + next + "%）";
            }
            default -> result = "沒有變化";
        }
        loadout.set(position, updated);
        plugin.economy().ledger().consumed(orb.id(), tw.exilecore.economy.EconomyLedger.SKILL, 1);
        player.sendMessage(plugin.text().prefixed("<#d20000>腐化 " + ItemRenderer.escape(skillName(entry)) + "：<white>" + result
                + (returned != null ? "<gray>（那一孔的寶石還給你）" : "")));
        return Change.ok(returned);
    }

    /** 2026-10-23：琢星稜鏡：技能寶石品質 +5%（最多 20%；腐化的不能用）。 */
    Change addQuality(final Player player, final int position, final CurrencyType prism) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null) {
            return Change.fail("那一格沒有技能");
        }
        final String problem = qualityProblem(entry);
        if (problem != null) {
            return Change.fail(problem);
        }
        final int next = Math.min(SkillLoadout.Entry.QUALITY_MAX, entry.quality() + QUALITY_STEP);
        loadout.set(position, entry.withQuality(next));
        plugin.economy().ledger().consumed(prism.id(), tw.exilecore.economy.EconomyLedger.SKILL, 1);
        final String text = SkillService.qualityText(config().skill(entry.skillId()), next);
        player.sendMessage(plugin.text().prefixed("<#8888ff>" + ItemRenderer.escape(skillName(entry)) + " 品質 " + next + "%"
                + (text == null ? "" : "<gray>（" + ItemRenderer.escape(text) + "）")));
        return Change.ok(null);
    }

    /** 琢星稜鏡一次加多少品質。 */
    static final int QUALITY_STEP = 5;

    /** 不能加品質的原因；可以回傳 null。 */
    String qualityProblem(final SkillLoadout.Entry entry) {
        if (entry.granted()) {
            return "武器賦予的技能不能加品質"; // 2026-10-26
        }
        if (entry.corrupted()) {
            return "已腐化的技能寶石不能再加品質";
        }
        if (entry.quality() >= SkillLoadout.Entry.QUALITY_MAX) {
            return "品質已經 " + SkillLoadout.Entry.QUALITY_MAX + "% 了";
        }
        return null;
    }

    /** 開第 n 個孔的通貨名稱（說明用）。 */
    String socketOrbName(final int sockets) {
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (type.operation() == CurrencyOperation.SKILL_SOCKET && type.sockets() == sockets) {
                return type.name();
            }
        }
        return "鑿星石";
    }

    private static int firstEmpty(final SkillLoadout loadout) {
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            if (loadout.skill(i) == null) {
                return i;
            }
        }
        return -1;
    }

    String skillName(final SkillLoadout.Entry entry) {
        if (entry == null) {
            return "別的技能";
        }
        final SkillDefinition definition = config().skill(entry.skillId());
        return definition == null ? entry.skillId() : definition.name();
    }

    String supportName(final String supportId) {
        final SupportDefinition support = config().support(supportId);
        return support == null ? supportId : support.name();
    }

    // ---- 工具 ----

    /** 快捷列某一格的東西少一個。 */
    private static void consume(final Player player, final int slot) {
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        player.getInventory().setItem(slot, stack.getAmount() <= 1 ? null : stack.asQuantity(stack.getAmount() - 1));
    }

    /** 放進背包；放不下的掉在腳邊（只有自己看得到、撿得起來）。 */
    void give(final Player player, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return; // 2026-10-26：拿掉武器賦予的技能沒有寶石可以還
        }
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (final ItemStack rest : leftover.values()) {
            plugin.personalDrops().dropForever(player, rest);
            player.sendMessage(plugin.text().prefixed("<yellow>背包滿了，" + ItemRenderer.escape(plainName(rest)) + "掉在腳邊"));
        }
    }

    private String plainName(final ItemStack stack) {
        return read(stack).map(data -> switch (data.kind()) {
            case UNCUT_SKILL -> "未切割技能寶石";
            case UNCUT_SUPPORT -> "未切割輔助寶石";
            case SKILL -> "技能寶石";
            case SUPPORT -> "輔助寶石";
        }).orElse("東西");
    }

    static void sound(final Player player, final boolean success) {
        if (success) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f, 0.6f);
        }
    }
}
