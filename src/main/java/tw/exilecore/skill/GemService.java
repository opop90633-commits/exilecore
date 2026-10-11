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
 *   <li>未切割技能寶石（Lv.N）：雕成一個 Lv.N 的技能寶石，或把面板上的技能升一級（最高到 Lv.N）</li>
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
    public boolean refresh(final ItemStack stack) {
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
        if (fresh.getType() != stack.getType() || ItemRenderer.sameText(fresh.getItemMeta(), stack.getItemMeta()) && sameModel) {
            return false;
        }
        stack.setItemMeta(fresh.getItemMeta());
        final io.papermc.paper.datacomponent.item.CustomModelData model = fresh.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
        if (model != null) {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA, model);
        }
        return true;
    }

    /**
     * 2026-10 PoE2 改版拿掉的技能、輔助寶石：原地換成未切割寶石（技能 → 同等級的未切割技能寶石；輔助 → 同階的未切割輔助寶石）。
     * 技能寶石上掛著的輔助（從面板取下來的寶石）拆下來放在回傳的清單裡：還在的輔助原樣、拿掉的換成未切割輔助寶石。
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
            if (config().skill(data.id()) == null) {
                replacement = GemData.uncutSkill(data.level());
                for (final String support : data.entry().supportIds()) {
                    extra.add(supportOrUncut(support));
                }
            } else {
                SkillLoadout.Entry entry = data.entry();
                for (int i = 0; i < entry.sockets(); i++) {
                    final String support = entry.support(i);
                    if (!support.isEmpty() && config().support(support) == null) {
                        extra.add(supportOrUncut(support));
                        entry = entry.withSupport(i, null);
                    }
                }
                if (entry != data.entry()) {
                    replacement = GemData.skill(entry);
                }
            }
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
            final List<Component> lore = new ArrayList<>();
            for (final String line : lore(data)) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
            meta.setMaxStackSize(data.maxStack());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            meta.setEnchantmentGlintOverride(data.kind() == GemData.Kind.SKILL || data.kind() == GemData.Kind.SUPPORT);
            if (resourcePack) {
                final NamespacedKey model = NamespacedKey.fromString("exilecore:gem/" + data.kind().key());
                if (model != null) {
                    meta.setItemModel(model);
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
                yield definition == null ? "<red>不認得的技能：" + ItemRenderer.escape(data.id())
                        : (definition.engravable() ? "<#e0c080>技能寶石：" : "<#d4a017>異質技能寶石：") + ItemRenderer.escape(definition.name())
                        + " <gray>Lv." + data.level();
            }
            case SUPPORT -> {
                final SupportDefinition support = config().support(data.id());
                yield support == null ? "<red>不認得的輔助：" + ItemRenderer.escape(data.id())
                        : (support.cuttable() ? "<#8fd3ff>" : "<#ff9f43>") + "輔助寶石：" + ItemRenderer.escape(support.name());
            }
        };
    }

    private List<String> lore(final GemData data) {
        final List<String> lines = new ArrayList<>();
        switch (data.kind()) {
            case UNCUT_SKILL -> {
                lines.add("<gray>右鍵：做成一個 <white>Lv." + data.level() + "</white> 的技能寶石，");
                lines.add("<gray>或把技能面板上的技能升一級（最高到 Lv." + data.level() + "）");
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
            case SUPPORT -> supportLore(config().support(data.id()), lines);
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
        lines.add(SkillService.headlineChips(definition));
        if (!definition.engravable()) {
            lines.add("<#d4a017>異質品質：只有劫盜的大金庫會掉，未切割技能寶石雕不出來");
        }
        if (!definition.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(definition.description()));
        }
        if (!definition.weapons().isEmpty()) {
            lines.add("<gray>需要" + definition.weaponText());
        }
        lines.add("<gray>需要角色等級 <white>" + config().requiredLevel(entry.level()));
        lines.add("");
        lines.add("<gray>輔助孔：<white>" + entry.sockets() + "</white>／" + SkillLoadout.MAX_SOCKETS);
        for (int i = 0; i < entry.sockets(); i++) {
            final String id = entry.support(i);
            if (id.isEmpty()) {
                lines.add("<dark_gray> ◇ 空");
            } else {
                final SupportDefinition support = config().support(id);
                lines.add("<#8fd3ff> ◆ " + ItemRenderer.escape(support == null ? id : support.name()));
            }
        }
        lines.add("");
        lines.add("<yellow>右鍵：放進技能面板");
    }

    private void supportLore(final SupportDefinition support, final List<String> lines) {
        if (support == null) {
            lines.add("<gray>supports.yml 裡沒有這個輔助（被刪掉或改名了）");
            return;
        }
        if (!support.cuttable()) {
            lines.add("<#ff9f43>✦ 專屬掉落的輔助寶石<dark_gray>（未切割輔助寶石切不出來）");
            lines.add("<#ff9f43>一個技能只能掛一顆專屬掉落的輔助寶石");
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
        for (final Modifier modifier : support.modifiers()) {
            lines.add("<#8fd3ff>" + ItemRenderer.escape(ModifierText.describe(modifier)));
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

        boolean fromPouch() {
            return currencyId != null;
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
        if (source.fromPouch()) {
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
        return source.fromPouch() ? "通貨袋裡的" + what + "用完了" : "手上的" + what + "不見了";
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
            return definition.name() + "雕不出來（只有劫盜的大金庫會掉）";
        }
        if (definition.tier() > uncut.level()) {
            return "要 Lv." + definition.tier() + " 以上的未切割技能寶石才能雕出" + definition.name();
        }
        take(player, source, uncut);
        final SkillLoadout.Entry entry = new SkillLoadout.Entry(definition.id(), uncut.level());
        // 同系列（異質寶石）已經在面板上：放進背包（2026-10）
        final boolean sibling = config().familyConflict(state.skills(), entry.skillId(), -1) >= 0;
        final int position = sibling ? -3 : state.skills().add(entry);
        if (position >= 0) {
            player.sendMessage(plugin.text().prefixed("<green>做出 " + ItemRenderer.escape(definition.name()) + " Lv." + entry.level()
                    + "，已經放進技能面板（<white>/skills</white> 綁到按鍵上）"));
        } else {
            give(player, create(GemData.skill(entry), 1));
            player.sendMessage(plugin.text().prefixed("<green>做出技能寶石：" + ItemRenderer.escape(definition.name()) + " Lv." + entry.level()
                    + (position == -2 ? "<gray>（面板上已經有這個技能，寶石放進背包）"
                    : position == -3 ? "<gray>（面板上有它的異質寶石，同系列只能放一個，寶石放進背包）" : "<gray>（技能面板滿了，寶石放進背包）")));
        }
        plugin.characters().save(player); // 寶石扣掉以後才存，當機時才不會兩邊都在
        sound(player, true);
        return null;
    }

    /** 用一顆未切割技能寶石把面板上的技能升一級。 */
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
     * 用一顆 Lv.uncutLevel 的未切割技能寶石把面板上的技能升一級（一顆升一級，寶石等級要夠）。
     * 不扣寶石、不存檔，由呼叫端扣掉寶石之後再存。
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
        final int next = entry.level() + 1;
        state.skills().set(position, entry.withLevel(next));
        final SkillDefinition definition = config().skill(entry.skillId());
        player.sendMessage(plugin.text().prefixed("<green>" + ItemRenderer.escape(definition == null ? entry.skillId() : definition.name())
                + " 升到 Lv." + next));
        return null;
    }

    /** 不能升級的原因；可以升回傳 null。 */
    String levelUpProblem(final PlayerState state, final SkillLoadout.Entry entry, final int uncutLevel) {
        if (entry.level() >= SkillLoadout.GEM_CAP) {
            return "已經是最高等級（Lv." + SkillLoadout.GEM_CAP + "）";
        }
        if (entry.level() >= SkillLoadout.MAX_LEVEL && uncutLevel < SkillLoadout.GEM_CAP) {
            return "Lv." + SkillLoadout.MAX_LEVEL + " 之後要 Lv." + SkillLoadout.GEM_CAP + " 的未切割技能寶石（劫盜大金庫、金庫之主）";
        }
        final int next = entry.level() + 1;
        if (next > uncutLevel) {
            return "要 Lv." + next + " 以上的未切割技能寶石才能升到 Lv." + next;
        }
        final int required = config().requiredLevel(next);
        if (state.level() < required) {
            return "角色等級要 " + required + " 才能升到 Lv." + next;
        }
        return null;
    }

    /**
     * 技能面板的「一鍵升級」：從背包或通貨袋找一顆夠用、等級最低的未切割技能寶石（不浪費高等級的）。
     * 找不到回傳 null。
     */
    UncutSource bestForLevelUp(final Player player, final SkillLoadout.Entry entry) {
        final PlayerState state = plugin.players().get(player);
        if (entry == null || levelUpProblem(state, entry, SkillLoadout.GEM_CAP) != null) {
            return null; // 已經最高等級、角色等級不夠：用什麼寶石都一樣不行
        }
        final int need = entry.level() + 1;
        UncutSource best = null;
        int bestLevel = Integer.MAX_VALUE;
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            final GemData data = read(inventory.getItem(slot)).orElse(null);
            if (data != null && data.kind() == GemData.Kind.UNCUT_SKILL && data.level() >= need && data.level() < bestLevel) {
                best = UncutSource.hand(slot);
                bestLevel = data.level();
            }
        }
        if (state.bag() != null && state.bag().usable()) {
            for (int level = need; level < bestLevel && level <= SkillLoadout.MAX_LEVEL; level++) {
                final String id = GemData.uncutSkill(level).currencyId();
                if (state.bag().pouch().amount(id) > 0) {
                    best = UncutSource.pouch(id);
                    bestLevel = level;
                    break;
                }
            }
        }
        return best;
    }

    /** 那個來源的寶石叫什麼（「通貨袋裡的未切割技能寶石 Lv.5」）。 */
    String describe(final Player player, final UncutSource source) {
        final GemData data = source.fromPouch() ? GemData.fromCurrencyId(source.currencyId()).orElse(null)
                : read(player.getInventory().getItem(source.slot())).orElse(null);
        final String name = data == null ? "未切割技能寶石" : (data.kind() == GemData.Kind.UNCUT_SUPPORT ? "未切割輔助寶石 第 " + data.level() + " 階"
                : "未切割技能寶石 Lv." + data.level());
        return (source.fromPouch() ? "通貨袋裡的" : "背包裡的") + name;
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
        final int target = position >= 0 ? position : firstEmpty(loadout);
        if (target < 0) {
            return Change.fail("技能面板滿了（最多 " + SkillLoadout.MAX_SKILLS + " 個），先取出一個");
        }
        final int existing = loadout.find(entry.skillId());
        if (existing >= 0 && existing != target) {
            return Change.fail("面板上已經有" + definition.name() + "了");
        }
        final int sibling = config().familyConflict(loadout, entry.skillId(), target);
        if (sibling >= 0) {
            return Change.fail("面板上已經有" + skillName(loadout.skill(sibling)) + "（異質寶石跟原本的技能只能放一個）");
        }
        {
            for (final String supportId : entry.supportIds()) {
                final int used = config().oncePerCharacter(supportId) ? loadout.supportUsedBy(supportId) : -1;
                if (used >= 0 && used != target) {
                    return Change.fail(supportName(supportId) + "已經掛在" + skillName(loadout.skill(used)) + "上（這種輔助一個角色只能掛一顆），先拿下來");
                }
            }
        }
        final SkillLoadout.Entry old = loadout.skill(target);
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
        return Change.ok(create(GemData.skill(entry), 1));
    }

    /** 把輔助寶石掛到技能的輔助孔（index = -1 掛第一個空孔）；孔裡原本有的換下來回傳。 */
    Change socket(final Player player, final int position, final int index, final String supportId) {
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
        if (!support.supports(definition)) {
            return Change.fail(support.name() + "不能輔助" + definition.name() + "（" + support.refusal(definition) + "）");
        }
        final int socket = index >= 0 ? index : entry.freeSocket();
        if (socket < 0) {
            return Change.fail(definition.name() + "沒有空的輔助孔了（用鑿星石開孔，最多 " + SkillLoadout.MAX_SOCKETS + " 個）");
        }
        if (socket >= entry.sockets()) {
            return Change.fail("這個孔還沒開（用鑿星石開孔）");
        }
        if (entry.hasSupport(supportId) && !supportId.equals(entry.support(socket))) {
            return Change.fail(definition.name() + "已經掛著" + support.name() + "了（同一個技能不能掛兩顆一樣的）");
        }
        final String exclusive = SupportDefinition.exclusiveConflict(entry, socket, support, config()::support);
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
        loadout.set(position, entry.withSupport(socket, supportId));
        return Change.ok(old.isEmpty() ? null : create(GemData.support(old), 1));
    }

    /** 把輔助寶石從技能上拿下來。 */
    Change unsocket(final Player player, final int position, final int index) {
        final SkillLoadout loadout = plugin.players().get(player).skills();
        final SkillLoadout.Entry entry = loadout.skill(position);
        if (entry == null || index < 0 || index >= entry.sockets() || entry.support(index).isEmpty()) {
            return Change.fail("這個孔是空的");
        }
        final String old = entry.support(index);
        loadout.set(position, entry.withSupport(index, ""));
        return Change.ok(create(GemData.support(old), 1));
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

    private String skillName(final SkillLoadout.Entry entry) {
        if (entry == null) {
            return "別的技能";
        }
        final SkillDefinition definition = config().skill(entry.skillId());
        return definition == null ? entry.skillId() : definition.name();
    }

    private String supportName(final String supportId) {
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
