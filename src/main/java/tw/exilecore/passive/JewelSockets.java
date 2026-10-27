package tw.exilecore.passive;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemStats;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 天賦樹的珠寶孔（PoE2 的珠寶插槽）：把背包裡的珠寶放進配了的珠寶孔、拿出來，以及把放在沒配置孔位裡的珠寶還給玩家。
 * 珠寶的詞綴跟天賦一樣直接加在角色身上（{@link #modifiers}），沒有需求。珠寶存在 {@link PlayerState#jewels()}，
 * 跟著角色存檔（character_inventory.jewels）。
 */
public final class JewelSockets {

    /**
     * 2026-10-27 照 PoE2 昇華・晶化護符（Crystalline Phylactery：Can Socket a non-Unique Basic Jewel into the Phylactery、
     * 100% increased Effect of bonuses gained from Socketed Jewel）：骸主的命匣是昇華天賦上的一個珠寶孔，存在 {@link PlayerState#jewels()}
     * 的這個鍵（不是天賦樹的節點）。只能放非傳奇的基礎珠寶（不是遺時、永恆珠寶），效果增加 100%。在昇華天賦（/ascend tree）放進去、拿出來。
     */
    public static final String PHYLACTERY = "asc_phylactery";

    private JewelSockets() {
    }

    /** 2026-10-27：這顆能不能放進命匣；可以回傳 null，不行回傳原因。 */
    public static String phylacteryProblem(final ExileCore plugin, final ItemStack stack) {
        final String problem = problem(plugin, stack);
        if (problem != null) {
            return problem;
        }
        final ItemData data = plugin.items().read(stack).orElseThrow();
        final ItemBase base = plugin.items().repository().base(data.base());
        if (data.rarity() == tw.exilecore.item.Rarity.UNIQUE) {
            return "命匣只能放非傳奇的珠寶";
        }
        if (base.isRadiusJewel() || TimelessJewels.isTimeless(base)) {
            return "命匣只能放基礎珠寶（赤星晶、翠星晶、蒼星晶、白星晶）";
        }
        return null;
    }

    /** 2026-10-27：把一顆珠寶放進命匣（呼叫端負責把珠寶從背包拿掉，成功才拿）。 */
    public static PassiveState.Result socketPhylactery(final ExileCore plugin, final Player player, final ItemStack stack) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return PassiveState.Result.fail("先建立角色（/char）");
        }
        if (tw.exilecore.ascendancy.AscBuild.ascFlat(plugin, state, tw.exilecore.ascendancy.AscKeys.PHYLACTERY) <= 0) {
            return PassiveState.Result.fail("要先配昇華天賦「晶化護符」");
        }
        if (state.jewel(PHYLACTERY) != null) {
            return PassiveState.Result.fail("命匣裡已經有珠寶了，先拿出來");
        }
        final String problem = phylacteryProblem(plugin, stack);
        if (problem != null) {
            return PassiveState.Result.fail(problem);
        }
        final ItemStack one = stack.clone();
        one.setAmount(1);
        state.setJewel(PHYLACTERY, one);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    /** 2026-10-27：把命匣裡的珠寶拿回背包（退掉晶化護符之後也拿得回來）。 */
    public static PassiveState.Result unsocketPhylactery(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        final ItemStack jewel = state.jewel(PHYLACTERY);
        if (jewel == null) {
            return PassiveState.Result.fail("命匣是空的");
        }
        if (plugin.loot() != null && !plugin.loot().fits(player, state, jewel)) {
            return PassiveState.Result.fail("背包與隨身背包都滿了，先清出一格");
        }
        state.setJewel(PHYLACTERY, null);
        giveBack(plugin, player, state, jewel);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    /** 這顆東西能不能放進珠寶孔；可以回傳 null，不行回傳原因（給玩家看）。 */
    public static String problem(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "這不是珠寶";
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return "這不是珠寶";
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        if (base == null || !base.isJewel()) {
            return "這不是珠寶（珠寶是赤星晶、翠星晶、蒼星晶、白星晶）";
        }
        if (!data.get().identified()) {
            return "要先鑑定（找鑑定師或用鑑定卷軸）";
        }
        return null;
    }

    /** 是不是我們的珠寶（鑑定與否不管）。 */
    public static boolean isJewel(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty()) {
            return false;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        return base != null && base.isJewel();
    }

    /**
     * 把一顆珠寶放進孔位。呼叫端負責把珠寶從背包拿掉（成功才拿）。
     *
     * @return 成功，或失敗的原因
     */
    public static PassiveState.Result socket(final ExileCore plugin, final Player player, final PassiveNode node, final ItemStack stack) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return PassiveState.Result.fail("先建立角色（/char）");
        }
        if (!node.isSocket()) {
            return PassiveState.Result.fail("這裡不是珠寶孔");
        }
        if (!state.passives().contains(node.id())) {
            return PassiveState.Result.fail("要先配這個珠寶孔（左鍵，1 點）");
        }
        if (state.jewel(node.id()) != null) {
            return PassiveState.Result.fail("這個孔已經有珠寶了，先點它拿出來");
        }
        final String problem = problem(plugin, stack);
        if (problem != null) {
            return PassiveState.Result.fail(problem);
        }
        // 2026-10：傳奇珠寶一次只能配一顆；2026-10-18：改成「每一種傳奇珠寶各一顆」（不同的傳奇珠寶可以一起配）
        final String unique = uniqueOf(plugin, stack);
        if (unique != null) {
            for (final ItemStack placed : state.jewels().values()) {
                if (unique.equals(uniqueOf(plugin, placed))) {
                    return PassiveState.Result.fail("同一種傳奇珠寶只能配一顆（天賦樹上已經有一顆了）");
                }
            }
        }
        // 2026-10-26 照 PoE2：永恆珠寶（英雄悲劇、不朽恨意）同時只能配一顆（Limited to: 1 Historic）
        if (isTimeless(plugin, stack)) {
            for (final ItemStack placed : state.jewels().values()) {
                if (isTimeless(plugin, placed)) {
                    return PassiveState.Result.fail("永恆珠寶同時只能配一顆（天賦樹上已經有一顆了）");
                }
            }
        }
        final ItemStack one = stack.clone();
        one.setAmount(1);
        state.setJewel(node.id(), one);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    /** 2026-10-26：是不是永恆珠寶。 */
    private static boolean isTimeless(final ExileCore plugin, final ItemStack stack) {
        return stack != null && plugin.items().read(stack)
                .map(data -> TimelessJewels.isTimeless(plugin.items().repository().base(data.base()))).orElse(false);
    }

    /** 這顆珠寶是哪一種傳奇（傳奇的代號）；不是傳奇是 null。 */
    private static String uniqueOf(final ExileCore plugin, final ItemStack stack) {
        return stack == null ? null : plugin.items().read(stack)
                .filter(data -> data.rarity() == tw.exilecore.item.Rarity.UNIQUE)
                .map(data -> data.unique() == null || data.unique().isBlank() ? "?" : data.unique()).orElse(null);
    }

    /** 把孔位裡的珠寶拿回背包（背包滿就放隨身背包，都滿就不拿）。 */
    public static PassiveState.Result unsocket(final ExileCore plugin, final Player player, final PassiveNode node) {
        final PlayerState state = plugin.players().get(player);
        final ItemStack jewel = state.jewel(node.id());
        if (jewel == null) {
            return PassiveState.Result.fail("這個孔是空的");
        }
        if (plugin.loot() != null && !plugin.loot().fits(player, state, jewel)) {
            return PassiveState.Result.fail("背包與隨身背包都滿了，先清出一格");
        }
        state.setJewel(node.id(), null);
        giveBack(plugin, player, state, jewel);
        plugin.players().sheet(player);
        return PassiveState.Result.success();
    }

    /**
     * 把放在「沒配置、不存在或已經不是珠寶孔」的節點裡的珠寶還給玩家（退點、匯入規劃、樹改版、換職業、登入時檢查）。
     *
     * @param reason 給玩家看的原因（null 不提示）
     * @return 還了幾顆
     */
    public static int reclaim(final ExileCore plugin, final Player player, final PlayerState state, final PassiveTree tree, final String reason) {
        if (state.jewels().isEmpty() || tree == null) {
            return 0; // 樹沒載入（資料檔壞了）：珠寶先留在原位，不要亂還
        }
        final List<String> orphaned = new ArrayList<>();
        for (final String nodeId : state.jewels().keySet()) {
            if (PHYLACTERY.equals(nodeId)) {
                continue; // 2026-10-27：命匣不是天賦樹的孔（昇華天賦上的，拿回來在 /ascend tree）
            }
            final PassiveNode node = tree.node(nodeId);
            if (node == null || !node.isSocket() || !state.passives().contains(nodeId)) {
                orphaned.add(nodeId);
            }
        }
        for (final String nodeId : orphaned) {
            final ItemStack jewel = state.jewels().remove(nodeId);
            if (jewel != null) {
                giveBack(plugin, player, state, jewel);
            }
        }
        if (!orphaned.isEmpty()) {
            state.markDirty();
            if (reason != null) {
                player.sendMessage(plugin.text().prefixed("<yellow>" + reason + "：" + orphaned.size() + " 顆珠寶已拿回背包"));
            }
        }
        return orphaned.size();
    }

    /** 先放背包、再放隨身背包、再放倉庫，都滿了掉在腳下（跟掉落一樣）。 */
    public static void giveBack(final ExileCore plugin, final Player player, final PlayerState state, final ItemStack stack) {
        ItemStack rest = stack;
        if (plugin.loot() != null) {
            rest = plugin.loot().stow(player, state, stack);
        } else {
            final Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            rest = leftover.isEmpty() ? null : leftover.values().iterator().next();
        }
        if (rest != null && !rest.isEmpty() && plugin.stash() != null && plugin.stash().deposit(player, rest)) {
            player.sendMessage(plugin.text().prefixed("<yellow>背包、隨身背包都滿了，珠寶放進倉庫"));
            rest = null; // 2026-10-27 照站長：背包、隨身背包滿了先放倉庫
        }
        if (rest != null && !rest.isEmpty()) {
            // 2026-10-18：改成只有自己撿得到、不會消失的個人掉落（原本公開掉落 5 分鐘就沒了）
            plugin.personalDrops().dropForever(player, rest);
            player.sendMessage(plugin.text().prefixed("<red>背包滿了，珠寶掉在你腳下（只有你撿得到，不會消失）"));
        }
    }

    /** 放在有配置的珠寶孔裡的珠寶給角色的修飾器（未鑑定的珠寶什麼都沒有，但放不進去）。 */
    public static List<Modifier> modifiers(final ExileCore plugin, final PlayerState state, final PassiveTree tree) {
        if (state.jewels().isEmpty() || tree == null || plugin.items() == null) {
            return List.of();
        }
        // 2026-10-26（昇華・巴雅地脈）：非傳奇的遺時珠寶半徑增加 40%
        // 2026-10-27 照 PoE2（昇華・晶化護符）：命匣（昇華天賦上的珠寶孔）裡的非傳奇基礎珠寶，效果增加 100%；
        //   這次之前沒有命匣的孔：天賦樹上第一顆非傳奇的基礎珠寶效果加倍
        return modifiers(plugin.items().repository(), jewelData(plugin, state), state.passives(), tree,
                tw.exilecore.ascendancy.AscBuild.jewelRadiusScale(plugin, state, null),
                tw.exilecore.ascendancy.AscBuild.ascFlat(plugin, state, tw.exilecore.ascendancy.AscKeys.PHYLACTERY) > 0);
    }

    /** 孔位節點代號 → 嵌著的珠寶的物品資料（讀不出來的跳過）。 */
    public static Map<String, tw.exilecore.item.ItemData> jewelData(final ExileCore plugin, final PlayerState state) {
        final Map<String, tw.exilecore.item.ItemData> data = new java.util.LinkedHashMap<>();
        if (plugin.items() == null) {
            return data;
        }
        for (final Map.Entry<String, ItemStack> entry : state.jewels().entrySet()) {
            final tw.exilecore.item.ItemData read = cachedRead(plugin, entry.getValue());
            if (read != null) {
                data.put(entry.getKey(), read);
            }
        }
        return data;
    }

    /**
     * 2026-10-27 效能：解好的珠寶（同一個物品、內容的雜湊一樣、資料庫沒重載就不再解一次）。屬性表每重算一次要讀兩遍全部珠寶，
     * 一顆整份讀約 60 微秒（ItemReadCostTest）；這次之前每次都重解。拿到的資料只能讀，不能改。
     */
    private record CachedJewel(int hash, tw.exilecore.item.ItemRepository repository, tw.exilecore.item.ItemData data) {
    }

    private static final Map<ItemStack, CachedJewel> JEWEL_CACHE = new java.util.IdentityHashMap<>();
    /** 快取最多幾顆（超過全部清掉重來；珠寶換掉之後舊的物品就不會再被問到）。 */
    static final int JEWEL_CACHE_MAX = 2048;

    private static tw.exilecore.item.ItemData cachedRead(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final int hash = stack.hashCode();
        final tw.exilecore.item.ItemRepository repository = plugin.items().repository();
        synchronized (JEWEL_CACHE) {
            final CachedJewel cached = JEWEL_CACHE.get(stack);
            if (cached != null && cached.hash() == hash && cached.repository() == repository) {
                return cached.data();
            }
        }
        final tw.exilecore.item.ItemData read = plugin.items().read(stack).orElse(null);
        synchronized (JEWEL_CACHE) {
            if (JEWEL_CACHE.size() >= JEWEL_CACHE_MAX) {
                JEWEL_CACHE.clear();
            }
            JEWEL_CACHE.put(stack, new CachedJewel(hash, repository, read));
        }
        return read;
    }

    /**
     * 同上，珠寶直接給物品資料（2026-10：網頁計算器的虛擬角色，沒有真的物品）。純函式。
     *
     * @param jewels   孔位節點代號 → 嵌著的珠寶
     * @param passives 已配置的天賦（孔位要配了才算）
     */
    public static List<Modifier> modifiers(final tw.exilecore.item.ItemRepository repository, final Map<String, tw.exilecore.item.ItemData> jewels,
                                           final java.util.Set<String> passives, final PassiveTree tree) {
        return modifiers(repository, jewels, passives, tree, 1.0, false);
    }

    /**
     * 同上；nonUniqueRadiusScale：非傳奇半徑珠寶的半徑倍率（2026-10-26 昇華・巴雅地脈）；
     * phylactery：命匣（jewels 的 {@link #PHYLACTERY}）裡的非傳奇基礎珠寶生效、效果增加 100%（2026-10-27 照 PoE2 昇華・晶化護符；
     * 這次之前是天賦樹上第一顆非傳奇的基礎珠寶效果加倍）。沒有晶化護符時命匣裡的珠寶沒有效果。
     */
    public static List<Modifier> modifiers(final tw.exilecore.item.ItemRepository repository, final Map<String, tw.exilecore.item.ItemData> jewels,
                                           final java.util.Set<String> passives, final PassiveTree tree, final double nonUniqueRadiusScale,
                                           final boolean phylactery) {
        if (jewels == null || jewels.isEmpty() || tree == null || repository == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        final tw.exilecore.item.ItemData held = phylactery ? jewels.get(PHYLACTERY) : null;
        final ItemBase heldBase = held == null ? null : repository.base(held.base());
        if (heldBase != null && heldBase.isJewel() && !heldBase.isRadiusJewel() && !TimelessJewels.isTimeless(heldBase)
                && held.rarity() != tw.exilecore.item.Rarity.UNIQUE) {
            for (final Modifier mod : tw.exilecore.item.ItemStats.modifiers(repository, held)) {
                list.add(new Modifier(mod.stat(), mod.type(), mod.value() * PHYLACTERY_EFFECT, mod.tags(), "命匣"));
            }
        }
        boolean uniqueSeen = false;
        // 2026-10-26：永恆珠寶征服的節點（半徑珠寶的「範圍內天賦效果增加」放大的是征服後的效果）
        final Map<String, PassiveNode> conquered = TimelessJewels.conquered(tree, TimelessJewels.active(repository, jewels, passives, tree), passives);
        for (final Map.Entry<String, tw.exilecore.item.ItemData> entry : jewels.entrySet()) {
            final PassiveNode node = tree.node(entry.getKey());
            final tw.exilecore.item.ItemData data = entry.getValue();
            if (data == null || node == null || !node.isSocket() || !passives.contains(entry.getKey())) {
                continue;
            }
            if (TimelessJewels.isTimeless(repository.base(data.base()))) {
                continue; // 2026-10-26：永恆珠寶沒有自己的屬性，效果是征服節點（TimelessJewels.treeModifiers），也不佔傳奇珠寶的名額
            }
            if (data.rarity() == tw.exilecore.item.Rarity.UNIQUE) {
                if (uniqueSeen) {
                    continue; // 以前放進去的第二顆傳奇珠寶不算
                }
                uniqueSeen = true;
            }
            List<Modifier> mods = tw.exilecore.item.ItemStats.modifiers(repository, data);
            final ItemBase base = repository.base(data.base());
            if (base != null && base.isRadiusJewel()) {
                if (nonUniqueRadiusScale != 1.0 && data.rarity() != tw.exilecore.item.Rarity.UNIQUE) {
                    mods = new ArrayList<>(mods);
                    mods.add(Modifier.flat(tw.exilecore.stats.StatKeys.JEWEL_LOCAL_RADIUS, radiusOf(mods) * (nonUniqueRadiusScale - 1), "巴雅地脈"));
                }
                list.addAll(radiusModifiers(tree, node, passives, mods, conquered));
            } else {
                list.addAll(mods);
            }
        }
        return list;
    }

    /** 2026-10-27：命匣裡的珠寶效果倍率（增加 100%）。 */
    public static final double PHYLACTERY_EFFECT = 2.0;

    /**
     * 半徑珠寶的基本半徑（PoE2 單位，跟 {@link PassiveTree#distance} 一樣）。2026-10-26 照 PoE2：小 1000、「中」+150、「大」+300
     * （JewelRadiusImplicit／Medium／Large）；這次之前是格子：基本 2 格、+1／+2 格。
     */
    public static final double BASE_RADIUS = 1000;
    /** 「中」「大」的半徑（PoE2 單位）。 */
    public static final double MEDIUM_RADIUS = 1150;
    public static final double LARGE_RADIUS = 1300;
    /**
     * 2026-10-26 照 PoE2：「範圍內的核心天賦也各給」的屬性前綴（範圍內每個已配置的核心各給一次；{@code radius:} 是小天賦）。
     * 2026-10-21 起「也各給」最多算 15 個小天賦的上限拿掉了（原作沒有）。
     */
    public static final String RADIUS_NOTABLE_PREFIX = "radius_notable:";

    /** 珠寶孔周圍半徑內的節點（2026-10-26：照 PoE2 原作座標的距離 ≤ 半徑；這次之前是格子距離 ≤ 半徑 + 0.5）。 */
    public static List<PassiveNode> nodesInRadius(final PassiveTree tree, final PassiveNode socket, final double radius) {
        final List<PassiveNode> list = new ArrayList<>();
        for (final PassiveNode node : tree.nodes()) {
            if (node == socket) {
                continue;
            }
            if (tree.distance(socket, node) <= radius + 1e-6) {
                list.add(node);
            }
        }
        return list;
    }

    /** 這顆半徑珠寶的半徑（PoE2 單位：基本 1000 + 「中」150／「大」300）。 */
    public static double radiusOf(final List<Modifier> mods) {
        double radius = BASE_RADIUS;
        for (final Modifier mod : mods) {
            if (tw.exilecore.stats.StatKeys.JEWEL_LOCAL_RADIUS.equals(mod.stat())) {
                radius += mod.value();
            }
        }
        return radius;
    }

    /** 半徑的名稱（PoE2：小、中、大、非常大）。 */
    public static String radiusName(final double radius) {
        if (radius >= TimelessJewels.RADIUS) {
            return "非常大";
        }
        return radius >= LARGE_RADIUS ? "大" : radius >= MEDIUM_RADIUS ? "中" : "小";
    }

    public static List<Modifier> radiusModifiers(final PassiveTree tree, final PassiveNode socket, final java.util.Set<String> allocated,
                                                 final List<Modifier> mods) {
        return radiusModifiers(tree, socket, allocated, mods, Map.of());
    }

    /**
     * 半徑珠寶（2026-10，PoE2 的遺時珠寶）：{@code radius:屬性} 的詞綴，範圍內每個已配置的小天賦都多給一次；
     * {@code radius_notable:屬性}（2026-10-26 照 PoE2）範圍內每個已配置的核心天賦多給一次；
     * 「範圍內小天賦／核心效果增加 x%」把範圍內已配置的小天賦／核心的修飾器再加 x%。其他詞綴照一般珠寶。
     *
     * @param conquered 永恆珠寶征服的節點（效果照征服後的放大）
     */
    public static List<Modifier> radiusModifiers(final PassiveTree tree, final PassiveNode socket, final java.util.Set<String> allocated,
                                                 final List<Modifier> mods, final Map<String, PassiveNode> conquered) {
        final List<Modifier> list = new ArrayList<>();
        double smallEffect = 0;
        double notableEffect = 0;
        for (final Modifier mod : mods) {
            if (tw.exilecore.stats.StatKeys.JEWEL_LOCAL_SMALL_EFFECT.equals(mod.stat())) {
                smallEffect += mod.value();
            } else if (tw.exilecore.stats.StatKeys.JEWEL_LOCAL_NOTABLE_EFFECT.equals(mod.stat())) {
                notableEffect += mod.value();
            }
        }
        int smalls = 0;
        int notables = 0;
        final List<PassiveNode> nearby = new ArrayList<>();
        for (final PassiveNode raw : nodesInRadius(tree, socket, radiusOf(mods))) {
            if (!allocated.contains(raw.id())) {
                continue;
            }
            final PassiveNode node = conquered == null ? raw : conquered.getOrDefault(raw.id(), raw);
            if (node.type() == PassiveNode.Type.SMALL) {
                smalls++;
            } else if (node.type() == PassiveNode.Type.NOTABLE) {
                notables++;
            }
            nearby.add(node);
        }
        for (final Modifier mod : mods) {
            if (mod.stat().startsWith(RADIUS_NOTABLE_PREFIX)) {
                if (notables > 0) {
                    list.add(new Modifier(mod.stat().substring(RADIUS_NOTABLE_PREFIX.length()), mod.type(), mod.value() * notables, mod.tags(),
                            mod.source()));
                }
            } else if (mod.stat().startsWith(tw.exilecore.stats.StatKeys.RADIUS_PREFIX)) {
                if (smalls > 0) {
                    list.add(new Modifier(mod.stat().substring(tw.exilecore.stats.StatKeys.RADIUS_PREFIX.length()), mod.type(),
                            mod.value() * smalls, mod.tags(), mod.source()));
                }
            } else if (!mod.stat().startsWith("jewel.local.")) {
                list.add(mod);
            }
        }
        for (final PassiveNode node : nearby) {
            final double scale = node.type() == PassiveNode.Type.SMALL ? smallEffect : node.type() == PassiveNode.Type.NOTABLE ? notableEffect : 0;
            if (scale <= 0) {
                continue;
            }
            for (final Modifier mod : node.modifiers()) {
                // 2026-10：「+x 任一屬性」記下是哪一點（屬性表那邊照那一點選的屬性換成力量／敏捷／智慧，以前加到沒人讀的 attr.choice）
                final String stat = tw.exilecore.stats.StatKeys.ATTR_CHOICE.equals(mod.stat())
                        ? tw.exilecore.stats.StatKeys.ATTR_CHOICE + "@" + node.id() : mod.stat();
                list.add(new Modifier(stat, mod.type(), mod.value() * scale, mod.tags(), "半徑珠寶：" + node.name()));
            }
        }
        return list;
    }

    /** 顯示用：孔位裡那顆珠寶的名字（帶稀有度顏色）與詞綴行；孔位空的回傳空清單。 */
    public static List<String> describe(final ExileCore plugin, final ItemStack jewel) {
        final List<String> lines = new ArrayList<>();
        if (jewel == null) {
            return lines;
        }
        final Optional<ItemData> data = plugin.items().read(jewel);
        if (data.isEmpty()) {
            return lines;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        lines.add("<" + data.get().rarity().color() + ">" + ItemRenderer.escape(ItemStats.displayName(plugin.items().repository(), data.get()))
                + (base != null && base.jewelColor() != null ? " <" + base.jewelColor() + ">◆" : ""));
        for (final ItemStats.AffixLine line : ItemStats.affixLines(plugin.items().repository(), data.get())) {
            lines.add("<white>" + line.ranged() + " <dark_gray>(T" + line.tier() + ")"); // ranged 已經是 MiniMessage（跳脫過），不能再跳脫一次
        }
        return lines;
    }

    /** 孔位裡珠寶的顏色（#rrggbb）；空的回傳 null。 */
    public static String color(final ExileCore plugin, final ItemStack jewel) {
        if (jewel == null) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(jewel);
        if (data.isEmpty()) {
            return null;
        }
        final ItemBase base = plugin.items().repository().base(data.get().base());
        return base == null ? null : base.jewelColor();
    }
}
