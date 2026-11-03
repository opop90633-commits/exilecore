package tw.exilecore.passive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.clientmod.UiFeed;
import tw.exilecore.item.ItemRenderer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2026-11-02 照站長（D 組）：天賦樹的原生畫面（模組 {@code PassiveTreeScreen}）的伺服器這一邊。這次之前只有箱子選單 {@link PassiveMenu}
 * （9×5 的視野、要一直捲、路徑預覽要點兩次）；沒裝模組的人照舊用它。
 * <ul>
 *   <li>靜態資料（{@link #staticJson}）：節點（代號、PoE2 座標、種類、名稱、說明、關鍵字說明、屬性點）、連線、職業變體、職業起點；
 *       整棵樹 4000 多個節點，壓縮後一百多 KB，模組照雜湊快取，樹改版（重新載入 tree.json）雜湊才變。</li>
 *   <li>狀態：已配置、現在能配、被解鎖條件擋住、藏起來（別的昇華看不到的觀星專屬節點）、退了會斷線的、剩幾點、金幣與退點的花費、
 *       屬性點選的屬性、永恆珠寶征服的節點與孔裡的珠寶（換掉名稱與說明）。</li>
 *   <li>動作：alloc（一條路徑，從已配置的往外依序；伺服器一個一個檢查能不能配、點數夠不夠才配）、refund（退一點，照舊花金幣）、
 *       use（配了的屬性點換屬性、珠寶孔放／拿珠寶）。規則全部照 {@link PassiveState}、{@link PassiveMenu}。</li>
 * </ul>
 * 這個遊戲沒有武器組天賦點（武器大師給的是 +10 天賦點，算在剩幾點裡），所以畫面上不寫武器組點數。
 */
public final class TreeFeed implements UiFeed.Source {

    public static final String KIND = "passive";
    /** 一條路徑最多幾個節點（防亂送）。 */
    static final int MAX_PATH = 300;

    private final ExileCore plugin;
    /** 靜態資料的快取：哪一棵樹（物件本身）→ JSON。 */
    private PassiveTree cachedTree;
    private byte[] cachedJson;
    /** 這次開著畫面配過點：關掉時存檔（跟箱子選單一樣）。 */
    private final Set<java.util.UUID> changed = new HashSet<>();

    public TreeFeed(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String flag() {
        return UiFeed.FLAG_TREE;
    }

    private PassiveTree tree() {
        return plugin.passives() == null ? null : plugin.passives().tree();
    }

    @Override
    public String problem(final Player player) {
        if (tree() == null) {
            return "天賦樹沒有載入（看主控台的錯誤）";
        }
        return plugin.players().get(player).hasCharacter() ? null : "先建立角色：/char";
    }

    @Override
    public byte[] staticJson(final Player player) {
        final PassiveTree tree = tree();
        if (tree == null) {
            return null;
        }
        synchronized (this) {
            if (tree != cachedTree) {
                cachedJson = staticJson(tree).toString().getBytes(StandardCharsets.UTF_8);
                cachedTree = tree;
            }
            return cachedJson;
        }
    }

    /** 節點種類的代號：s 小點、n 核心、k 基石、o 起點、j 珠寶孔。 */
    static String kindCode(final PassiveNode.Type type) {
        return switch (type) {
            case SMALL -> "s";
            case NOTABLE -> "n";
            case KEYSTONE -> "k";
            case START -> "o";
            case SOCKET -> "j";
        };
    }

    /** 純函式：整棵樹的靜態資料（跟玩家無關）。 */
    public static JsonObject staticJson(final PassiveTree tree) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("kind", KIND);
        root.addProperty("tree", tree.version());
        root.addProperty("unit", tree.unit());
        final JsonObject starts = new JsonObject();
        tree.starts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> starts.addProperty(e.getKey(), e.getValue()));
        root.add("starts", starts);
        final JsonArray nodes = new JsonArray();
        final Map<String, Integer> index = new HashMap<>();
        final JsonObject variants = new JsonObject();
        for (final PassiveNode node : tree.ordered()) {
            index.put(node.id(), index.size());
            final JsonObject object = new JsonObject();
            object.addProperty("i", node.id());
            final double[] position = tree.position(node);
            object.addProperty("x", Math.round(position[0]));
            object.addProperty("y", Math.round(position[1]));
            object.addProperty("k", kindCode(node.type()));
            object.addProperty("n", plain(node.name().isEmpty() ? node.type().displayName() : node.name()));
            object.add("d", plainStrings(node.description()));
            final List<String> explained = tw.exilecore.stats.KeywordText.explain(node.description());
            if (!explained.isEmpty()) {
                object.add("e", plainStrings(explained));
            }
            if (node.attributeChoice() > 0) {
                object.addProperty("a", node.attributeChoice());
            }
            if (tree.unseen(node)) {
                object.addProperty("u", 1); // 2026-11-02 照站長：觀星「不見之途」專屬（模組用深紫加星光外框畫）；這次之前沒有這個旗標
            }
            nodes.add(object);
            if (!node.variants().isEmpty()) {
                final JsonObject byClass = new JsonObject();
                node.variants().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    final JsonObject variant = new JsonObject();
                    variant.addProperty("n", plain(entry.getValue().name()));
                    variant.add("d", plainStrings(entry.getValue().description()));
                    byClass.add(entry.getKey(), variant);
                });
                variants.add(node.id(), byClass);
            }
        }
        root.add("nodes", nodes);
        final JsonArray links = new JsonArray();
        for (final PassiveTree.Link link : tree.links()) {
            links.add(index.get(link.a().id()));
            links.add(index.get(link.b().id()));
        }
        root.add("links", links);
        root.add("var", variants);
        // 2026-11-02 照站長：虛實交織的半徑（原作單位，跟 x／y 同一套；模組畫錨點基石的範圍圈）。跳點能不能配照舊由伺服器算（avail）
        root.addProperty("jr", PassiveTree.ENTWINED_RADIUS);
        return root;
    }

    /** 靜態資料的字：模組照簡單的 MiniMessage 讀，原本的 < 要寫成 \<。 */
    static String plain(final String text) {
        return tw.exilecore.item.ItemRenderer.escape(text);
    }

    private static JsonArray plainStrings(final java.util.Collection<String> lines) {
        final JsonArray array = new JsonArray();
        lines.forEach(line -> array.add(plain(line)));
        return array;
    }

    private static JsonArray strings(final java.util.Collection<String> lines) {
        final JsonArray array = new JsonArray();
        lines.forEach(array::add);
        return array;
    }

    private static JsonArray ids(final java.util.Collection<String> ids) {
        final JsonArray array = new JsonArray();
        ids.stream().sorted().forEach(array::add);
        return array;
    }

    /**
     * 純函式：現在能配的節點（跟 {@link PassiveTree#canAllocate} 一樣的規則，只是不用每個節點都重算一次連通）。
     */
    static Set<String> available(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> rules) {
        final Set<String> out = new LinkedHashSet<>();
        if (start == null) {
            return out;
        }
        final List<PassiveNode> seeds = tree.seeds(start, rules);
        final Set<String> from = new HashSet<>(allocated);
        for (final PassiveNode seed : seeds) {
            from.add(seed.id());
        }
        final boolean entwined = rules.contains(PassiveTree.RULE_ENTWINED);
        final Set<String> reach = entwined ? tree.reachable(allocated, start, rules) : Set.of();
        final java.util.Collection<PassiveNode> candidates;
        if (entwined) {
            candidates = tree.nodes();
        } else {
            final Set<PassiveNode> near = new LinkedHashSet<>();
            for (final String id : from) {
                final PassiveNode node = tree.node(id);
                if (node != null) {
                    near.addAll(tree.neighbours(node));
                }
            }
            candidates = near;
        }
        for (final PassiveNode node : candidates) {
            if (node.isStart() || allocated.contains(node.id()) || tree.locked(allocated, node, rules)) {
                continue;
            }
            boolean ok = false;
            for (final PassiveNode neighbour : tree.neighbours(node)) {
                if (from.contains(neighbour.id())) {
                    ok = true;
                    break;
                }
            }
            if (ok || entwined && tree.entwined(reach, node, rules)) {
                out.add(node.id());
            }
        }
        return out;
    }

    /**
     * 純函式：檢查模組送來的路徑（依序配上去每一步都要能配），回傳要配的節點；不行回傳問題（第二個值）。
     */
    static Object[] checkPath(final PassiveTree tree, final PassiveNode start, final Set<String> allocated, final Set<String> rules,
                              final List<String> ids, final String ascendancy) {
        if (ids.isEmpty() || ids.size() > MAX_PATH) {
            return new Object[] {null, "走不到那裡"};
        }
        final Set<String> simulated = new HashSet<>(allocated);
        final List<PassiveNode> path = new ArrayList<>();
        for (final String id : ids) {
            final PassiveNode node = tree.node(id);
            if (node == null || tree.hidden(node, ascendancy)) {
                return new Object[] {null, "樹改版了，請重新打開天賦樹"};
            }
            if (!tree.canAllocate(simulated, start, node, rules)) {
                final PassiveTree.Unlock unlock = tree.unlock(node);
                if (unlock != null && tree.locked(simulated, node, rules)) {
                    return new Object[] {null, unlock.stat() != null ? "只有觀星配了昇華天賦「不見之途」才能配" : "要先配旁邊說明寫的核心才能配"};
                }
                return new Object[] {null, simulated.contains(id) ? "已經配過了" : "路徑接不上（配點可能剛變了），請再點一次"};
            }
            simulated.add(id);
            path.add(node);
        }
        return new Object[] {path, null};
    }

    @Override
    public JsonObject state(final Player player, final JsonObject args) {
        final PassiveTree tree = tree();
        final PlayerState state = plugin.players().get(player);
        final PassiveState passive = new PassiveState(plugin, player, tree);
        final PassiveNode start = passive.start();
        final Set<String> rules = PassiveState.rules(plugin, state);
        final String ascendancy = PassiveState.ascendancyId(plugin, state);
        final Set<String> allocated = state.passives();
        final JsonObject root = new JsonObject();
        root.addProperty("start", start == null ? "" : start.id());
        final tw.exilecore.character.CharacterClass type = state.characterClass();
        root.addProperty("class", type == null ? "" : type.id().toLowerCase(Locale.ROOT));
        final JsonArray seeds = new JsonArray();
        if (start != null) {
            for (final PassiveNode seed : tree.seeds(start, rules)) {
                seeds.add(seed.id());
            }
        }
        root.add("seeds", seeds);
        root.add("owned", ids(allocated));
        root.add("avail", ids(available(tree, start, allocated, rules)));
        final Set<String> locked = new HashSet<>();
        final Set<String> hidden = new HashSet<>();
        for (final PassiveNode node : tree.nodes()) {
            if (tree.unlock(node) == null) {
                continue;
            }
            if (tree.hidden(node, ascendancy)) {
                hidden.add(node.id());
            } else if (tree.locked(allocated, node, rules)) {
                locked.add(node.id());
            }
        }
        // 2026-11-02 照站長：虛實交織的錨點（連得到的已配基石）；模組畫範圍圈，avail 裡沒相連的就是跳點
        final JsonArray anchors = new JsonArray();
        if (start != null) {
            tree.entwinedAnchors(allocated, start, rules).forEach(node -> anchors.add(node.id()));
        }
        root.add("anchors", anchors);
        root.add("locked", ids(locked));
        root.add("hidden", ids(hidden));
        final Set<String> fixed = new HashSet<>();
        if (start != null) {
            for (final String id : allocated) {
                final PassiveNode node = tree.node(id);
                if (node != null && !tree.canRefund(allocated, start, node, rules)) {
                    fixed.add(id);
                }
            }
        }
        root.add("fixed", ids(fixed));
        final int points = plugin.players().availablePoints(state);
        root.addProperty("points", points);
        final CharacterRecord character = state.character();
        final long gold = character == null ? 0 : state.gold();
        final int freeRefunds = character == null ? 0 : character.refundPoints();
        final long refundCost = passive.refundCost();
        // 右鍵退點那一行（每個節點都一樣，模組照這個寫在說明最後）
        final String refund;
        if (freeRefunds > 0) {
            refund = "<yellow>右鍵退點（免費退點次數 " + freeRefunds + "）";
        } else if (refundCost <= 0) {
            refund = "<yellow>右鍵退點（免費）";
        } else {
            refund = String.format(Locale.ROOT, gold >= refundCost ? "<yellow>右鍵退點（%,d 金幣，你有 %,d）" : "<red>右鍵退點要 %,d 金幣，你有 %,d",
                    refundCost, gold);
        }
        root.addProperty("refund", refund);
        // 2026-11-02 照站長：右鍵退點先確認（寫要花多少、你有多少）；0 = 免費（免費退點次數、未滿等級、月卡），模組就不用確認
        root.addProperty("rcost", refundCost);
        root.addProperty("gold", gold);
        root.add("info", strings(info(state, character, points, gold, freeRefunds, refundCost, tree)));
        // 屬性點選了什麼、征服、孔裡的珠寶
        final JsonObject use = new JsonObject(); // 配了的節點左鍵有作用的（屬性點換屬性、珠寶孔）：說明多一行
        final JsonObject attrs = new JsonObject(); // 2026-11-02 照站長：配了的屬性點選了哪個（模組照這個上色）；這次之前只寫在說明裡
        final boolean wisdom = wisdom(state);
        for (final String id : allocated) {
            final PassiveNode node = tree.node(id);
            if (node != null && node.attributeChoice() > 0) {
                final String chosen = tw.exilecore.character.PlayerStateService.chosenAttribute(state, type, id);
                attrs.addProperty(id, chosen == null ? "str" : chosen);
                use.addProperty(id, "<#ffd96a>目前：" + attributeName(chosen) + (wisdom
                        ? " <yellow>（左鍵切換：力量 → 敏捷 → 智慧 → 傷害 → 防禦 → 消耗效率）" : " <yellow>（左鍵切換：力量 → 敏捷 → 智慧）"));
            }
        }
        root.add("use", use);
        root.add("attrs", attrs);
        root.add("sets", sets(state.activeTreeSet(), card(player))); // 2026-11-02 照站長：右上的組別鈕（月卡）
        final JsonObject over = new JsonObject();
        final Map<String, PassiveNode> conquered = TimelessJewels.conquered(plugin, state, tree);
        for (final Map.Entry<String, PassiveNode> entry : conquered.entrySet()) {
            final PassiveNode original = tree.node(entry.getKey());
            final JsonObject object = new JsonObject();
            object.addProperty("n", entry.getValue().name());
            final List<String> lines = new ArrayList<>();
            for (final String line : entry.getValue().description()) {
                lines.add("<white>" + ItemRenderer.escape(line));
            }
            lines.add("<#c48bff>被恆時珠寶征服" + (original == null || original.name().equals(entry.getValue().name()) ? ""
                    : "（原本是「" + ItemRenderer.escape(original.forClass(type == null ? null : type.id()).name()) + "」）"));
            object.add("d", strings(lines));
            over.add(entry.getKey(), object);
        }
        final JsonArray jewels = new JsonArray();
        for (final String id : allocated) {
            final PassiveNode node = tree.node(id);
            if (node == null || !node.isSocket()) {
                continue;
            }
            final ItemStack jewel = state.jewel(id);
            final JsonObject object = new JsonObject();
            final List<String> lines = new ArrayList<>();
            if (jewel != null) {
                jewels.add(id);
                final List<String> described = JewelSockets.describe(plugin, jewel);
                object.addProperty("n", described.isEmpty() ? node.name() : described.getFirst());
                lines.addAll(described.subList(Math.min(1, described.size()), described.size()));
                lines.add("<yellow>左鍵：把珠寶拿回背包");
            } else {
                object.addProperty("n", "◇ " + node.name() + "（空的）");
                for (final String line : node.description()) {
                    lines.add("<white>" + ItemRenderer.escape(line));
                }
                lines.add("<yellow>左鍵：選一顆背包裡的珠寶放進去");
            }
            object.add("d", strings(lines));
            over.add(id, object);
        }
        root.add("over", over);
        root.add("jewels", jewels);
        // 2026-11-02 照站長：範圍珠寶、永恆珠寶的範圍（孔代號 → 半徑，跟節點座標同單位；模組畫圈）；這次之前沒畫
        final JsonObject radii = new JsonObject();
        final Map<String, tw.exilecore.item.ItemData> socketed = JewelSockets.jewelData(plugin, state);
        final double scale = tw.exilecore.ascendancy.AscBuild.jewelRadiusScale(plugin, state, null);
        for (final Map.Entry<String, tw.exilecore.item.ItemData> entry : socketed.entrySet()) {
            final PassiveNode node = tree.node(entry.getKey());
            final tw.exilecore.item.ItemBase base = entry.getValue() == null ? null : plugin.items().repository().base(entry.getValue().base());
            if (node == null || base == null || !allocated.contains(entry.getKey())) {
                continue;
            }
            if (TimelessJewels.isTimeless(base)) {
                radii.addProperty(entry.getKey(), TimelessJewels.RADIUS);
            } else if (base.isRadiusJewel()) {
                double radius = JewelSockets.radiusOf(tw.exilecore.item.ItemStats.modifiers(plugin.items().repository(), entry.getValue()));
                if (entry.getValue().rarity() != tw.exilecore.item.Rarity.UNIQUE) {
                    radius *= scale; // 巴揚地脈：非傳奇的範圍珠寶半徑變大
                }
                radii.addProperty(entry.getKey(), Math.round(radius));
            }
        }
        root.add("jrad", radii);
        // 2026-11-02 照站長：有空的珠寶孔時，把背包、隨身背包裡的珠寶列給模組（在天賦樹上直接選、直接放）；
        //   這次之前點空的孔一律開箱子選單
        if (hasEmptySocket(tree, state, allocated)) {
            final JsonArray bag = new JsonArray();
            for (final JewelPickMenu.Candidate candidate : JewelPickMenu.candidates(plugin, player)) {
                final JsonObject object = new JsonObject();
                object.addProperty("k", candidate.key());
                final List<String> described = JewelSockets.describe(plugin, candidate.stack());
                object.addProperty("n", described.isEmpty() ? "珠寶" : described.getFirst());
                final List<String> lines = new ArrayList<>(described.subList(Math.min(1, described.size()), described.size()));
                lines.add(candidate.page() < 0 ? "<dark_gray>在背包" : "<dark_gray>在隨身背包第 " + (candidate.page() + 1) + " 頁");
                final String problem = JewelSockets.problem(plugin, candidate.stack());
                if (problem != null) {
                    object.addProperty("p", "不能放：" + ItemRenderer.escape(problem));
                }
                object.add("d", strings(lines));
                bag.add(object);
            }
            root.add("bag", bag);
        }
        if (args != null && args.has("q") && args.get("q").isJsonPrimitive()) {
            root.addProperty("search", args.get("q").getAsString());
            args.remove("q"); // 只在打開時帶一次
        }
        return root;
    }

    private List<String> info(final PlayerState state, final CharacterRecord character, final int points, final long gold, final int freeRefunds,
                              final long refundCost, final PassiveTree tree) {
        final List<String> info = new ArrayList<>();
        info.add("<gray>可用 <white>" + points + "</white>　已配置 <white>" + state.passives().size());
        info.add(String.format(Locale.ROOT, "<gray>金幣 <#f5b942>%,d", gold));
        if (freeRefunds > 0) {
            info.add("<gray>免費退點次數 <white>" + freeRefunds);
        } else if (refundCost <= 0) {
            info.add("<gray>退點免費（未滿 " + plugin.players().config().respecFreeUntilLevel() + " 級）");
        } else {
            info.add(String.format(Locale.ROOT, "<gray>退一點 <#f5b942>%,d 金幣", refundCost));
        }
        info.add(character == null ? "<red>還沒有角色" : "<gray>" + character.characterClass().displayName() + " Lv." + character.level()
                + "　<dark_gray>每升一級 +1 點");
        int sockets = 0;
        for (final String id : state.passives()) {
            final PassiveNode node = tree.node(id);
            if (node != null && node.isSocket()) {
                sockets++;
            }
        }
        if (sockets > 0 || !state.jewels().isEmpty()) {
            info.add("<gray>珠寶孔 <white>" + sockets + "</white>　放了珠寶 <#c77dff>" + state.jewels().size());
        }
        return info;
    }

    /** 一條路最多加總幾行（跟箱子選單一樣：多的寫「…還有 N 項」）。 */
    static final int SUMMARY_LINES = 10;

    /**
     * 純函式：路徑預覽「整條路加起來的能力」（跟 {@link PassiveMenu} 的說明同一段：{@link PassiveSummary#sum}、最多 10 行）。
     * 只有一個節點時回傳空的（說明本身就寫了）。
     */
    static List<String> summaryLines(final PassiveTree tree, final List<String> ids) {
        final List<PassiveNode> path = new ArrayList<>();
        for (final String id : ids) {
            final PassiveNode node = tree.node(id);
            if (node != null) {
                path.add(node);
            }
        }
        final List<String> lines = new ArrayList<>();
        final List<String> total = PassiveSummary.sum(path);
        if (path.size() <= 1 || total.isEmpty()) {
            return lines;
        }
        lines.add("<#77aaff>整條路加起來：");
        for (int i = 0; i < Math.min(SUMMARY_LINES, total.size()); i++) {
            lines.add("<white>　" + ItemRenderer.escape(total.get(i)));
        }
        if (total.size() > SUMMARY_LINES) {
            lines.add("<dark_gray>　…還有 " + (total.size() - SUMMARY_LINES) + " 項");
        }
        return lines;
    }

    @Override
    public List<String> summary(final Player player, final List<String> path) {
        final PassiveTree tree = tree();
        return tree == null ? List.of() : summaryLines(tree, path);
    }

    @Override
    public String signature(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        return state.passives().hashCode() + ":" + plugin.players().availablePoints(state) + ":" + state.gold() + ":" + state.jewels().size()
                + ":" + (character == null ? 0 : character.refundPoints() + "/" + character.level()) + ":" + PassiveState.ascendancyId(plugin, state)
                + ":" + PassiveState.rules(plugin, state).hashCode() + ":" + attrSignature(state) + ":" + jewelSignature(player) + ":" + state.activeTreeSet() + ":" + card(player);
    }

    /** 配了、但還沒放珠寶的珠寶孔有沒有。 */
    private static boolean hasEmptySocket(final PassiveTree tree, final PlayerState state, final java.util.Collection<String> allocated) {
        for (final String id : allocated) {
            final PassiveNode node = tree.node(id);
            if (node != null && node.isSocket() && state.jewel(id) == null) {
                return true;
            }
        }
        return false;
    }

    /** 2026-11-02：背包裡的珠寶換了（撿到、丟掉、放進孔）也要重送（列給模組選的那一串）。 */
    private int jewelSignature(final Player player) {
        int hash = 0;
        for (final JewelPickMenu.Candidate candidate : JewelPickMenu.candidates(plugin, player)) {
            hash = hash * 31 + candidate.key().hashCode() * 17 + candidate.stack().hashCode();
        }
        return hash;
    }

    /** 2026-11-02：組別鈕的資料：幾組、用哪一組、有沒有月卡（模組畫右上的「1 2 3…」）。 */
    public static JsonObject sets(final int active, final boolean card) {
        final JsonObject sets = new JsonObject();
        sets.addProperty("n", PlayerState.GEAR_SETS);
        sets.addProperty("a", active);
        sets.addProperty("card", card);
        return sets;
    }

    private boolean card(final Player player) {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    private static int attrSignature(final PlayerState state) {
        int hash = 0;
        for (final Map.Entry<String, String> entry : state.progress().entrySet()) {
            if (entry.getKey().startsWith("attr.")) {
                hash += entry.getKey().hashCode() * 31 + String.valueOf(entry.getValue()).hashCode();
            }
        }
        return hash;
    }

    @Override
    public String action(final Player player, final String action, final JsonObject message) {
        final PassiveTree tree = tree();
        final PlayerState state = plugin.players().get(player);
        final PassiveState passive = new PassiveState(plugin, player, tree);
        final PassiveNode start = passive.start();
        final Set<String> rules = PassiveState.rules(plugin, state);
        final String ascendancy = PassiveState.ascendancyId(plugin, state);
        switch (action) {
            case "set" -> {
                // 2026-11-02 照站長：原生天賦樹右上的組別鈕（跟箱子選單的天賦組同一套規則：沒有月卡只能切回第 1 組）
                final int set = message.has("set") && message.get("set").isJsonPrimitive() ? message.get("set").getAsInt() : -1;
                if (set == state.activeTreeSet()) {
                    return null;
                }
                // 2026-11-03 照站長：先跳確認框，按「套用」才換（這次之前按了就換）；套用後天賦樹重送
                final java.util.UUID id = player.getUniqueId();
                final String problem = new tw.exilecore.sponsor.TreeSetMenu(plugin, player, tree, () -> { })
                        .askSwitch(set, () -> changed.add(id), () -> { });
                if (problem != null) {
                    return fail(player, problem);
                }
                return null;
            }
            case "alloc" -> {
                final List<String> ids = new ArrayList<>();
                if (message.has("path") && message.get("path").isJsonArray()) {
                    for (final JsonElement element : message.getAsJsonArray("path")) {
                        if (element.isJsonPrimitive()) {
                            ids.add(element.getAsString());
                        }
                    }
                }
                if (start == null) {
                    return fail(player, "你還沒有角色");
                }
                final Object[] checked = checkPath(tree, start, state.passives(), rules, ids, ascendancy);
                if (checked[1] != null) {
                    return fail(player, (String) checked[1]);
                }
                @SuppressWarnings("unchecked") final List<PassiveNode> path = (List<PassiveNode>) checked[0];
                final PassiveState.Result result = path.size() == 1 ? passive.allocate(path.getFirst()) : passive.allocatePath(path);
                if (!result.ok()) {
                    return fail(player, result.message());
                }
                changed.add(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                return "<green>配了 " + path.size() + " 點：" + ItemRenderer.escape(path.getLast().forClass(classId(state)).name());
            }
            case "refund" -> {
                final PassiveNode node = node(tree, message, ascendancy);
                if (node == null) {
                    return fail(player, "找不到這個節點");
                }
                final PassiveState.Result result = passive.refund(node);
                if (!result.ok()) {
                    return fail(player, result.message());
                }
                changed.add(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.7f);
                return "<yellow>退了 " + ItemRenderer.escape(node.forClass(classId(state)).name());
            }
            case "use" -> {
                final PassiveNode node = node(tree, message, ascendancy);
                if (node == null || !state.passives().contains(node.id())) {
                    return fail(player, "這個節點沒有配置");
                }
                if (PassiveMenu.isAttributeChoice(node)) {
                    // 屬性點：跟箱子選單一樣左鍵切換（不花錢）
                    final String now = tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), node.id());
                    final String next = nextAttribute(now, wisdom(state));
                    plugin.characters().setProgress(player, "attr." + node.id(), next);
                    state.markDirty();
                    plugin.players().sheet(player);
                    changed.add(player.getUniqueId());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                    return tw.exilecore.character.PlayerStateService.WISDOM_CHOICES.contains(next)
                            ? "<#ffd96a>這個屬性點改成增加 " + node.attributeChoice() + "% " + attributeName(next)
                            : "<#ffd96a>這個屬性點改成 +" + node.attributeChoice() + " " + attributeName(next);
                }
                if (node.isSocket()) {
                    if (state.jewel(node.id()) == null && message.has("from") && message.get("from").isJsonPrimitive()) {
                        // 2026-11-02 照站長：在原生天賦樹上直接選的那一顆（"inv:格" 或 "bag:頁:格"）
                        final String from = message.get("from").getAsString();
                        final JewelPickMenu.Candidate candidate = JewelPickMenu.candidates(plugin, player).stream()
                                .filter(c -> c.key().equals(from)).findFirst().orElse(null);
                        if (candidate == null) {
                            return fail(player, "那顆珠寶已經不在原來的位置了，重新選一次");
                        }
                        final String problem = JewelPickMenu.socketFrom(plugin, player, node, candidate);
                        if (problem != null) {
                            return fail(player, problem);
                        }
                        changed.add(player.getUniqueId());
                        return "<green>珠寶放進去了，詞綴已加在角色身上";
                    }
                    if (state.jewel(node.id()) == null) {
                        new JewelPickMenu(plugin, player, tree, node, Set.of()).open(); // 選珠寶照舊用箱子選單，選完回原生畫面
                        if (plugin.clientMod() != null && plugin.clientMod().ui() != null) {
                            plugin.clientMod().ui().closed(player);
                        }
                        return null;
                    }
                    final PassiveState.Result result = JewelSockets.unsocket(plugin, player, node);
                    if (!result.ok()) {
                        return fail(player, result.message());
                    }
                    changed.add(player.getUniqueId());
                    return "<yellow>珠寶拿回背包了";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static PassiveNode node(final PassiveTree tree, final JsonObject message, final String ascendancy) {
        if (!message.has("id") || !message.get("id").isJsonPrimitive()) {
            return null;
        }
        final PassiveNode node = tree.node(message.get("id").getAsString());
        return node == null || tree.hidden(node, ascendancy) ? null : node;
    }

    private static String classId(final PlayerState state) {
        return state.characterClass() == null ? null : state.characterClass().id();
    }

    private String fail(final Player player, final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        return "<red>" + ItemRenderer.escape(message);
    }

    /** 純函式：屬性點的下一個屬性（跟箱子選單一樣：力量 → 敏捷 → 智慧（→ 傷害 → 防禦 → 消耗效率））。 */
    static String nextAttribute(final String now, final boolean wisdom) {
        return switch (now) {
            case "str" -> "dex";
            case "dex" -> "int";
            case "int" -> wisdom ? "dmg" : "str";
            case "dmg" -> "def";
            case "def" -> "cost";
            default -> "str";
        };
    }

    private static boolean wisdom(final PlayerState state) {
        final String nodes = state.progress().get(tw.exilecore.ascendancy.Ascension.NODES);
        return nodes != null && java.util.Arrays.stream(nodes.split(",")).anyMatch(id -> "travellers_wisdom".equals(id.trim()));
    }

    static String attributeName(final String key) {
        return switch (key) {
            case "dex" -> "敏捷";
            case "int" -> "智慧";
            case "dmg" -> "傷害";
            case "def" -> "護甲、閃避值與能量護盾";
            case "cost" -> "消耗效率";
            default -> "力量";
        };
    }

    /** 畫面關了：這次有配點就存檔（跟箱子選單關掉時一樣）。 */
    public void saveIfChanged(final Player player) {
        if (changed.remove(player.getUniqueId()) && plugin.characters() != null) {
            plugin.characters().save(player);
        }
    }

    @Override
    public void closed(final Player player) {
        saveIfChanged(player);
    }

    /**
     * 有裝模組（招呼寫了 treeui）的玩家：打開原生畫面，回傳 true；不然回傳 false，呼叫的地方照舊開 {@link PassiveMenu}。
     *
     * @param query 搜尋字（/tree find；null = 不搜尋）
     */
    public static boolean openNative(final ExileCore plugin, final Player player, final String query) {
        final var mod = plugin.clientMod();
        if (mod == null || mod.ui() == null) {
            return false;
        }
        JsonObject args = null;
        if (query != null && !query.isBlank()) {
            args = new JsonObject();
            args.addProperty("q", query);
        }
        return mod.ui().open(player, KIND, args);
    }

    @Override
    public void chest(final Player player) {
        saveIfChanged(player);
        new PassiveMenu(plugin, player, tree(), Set.of(), null).open();
    }
}
