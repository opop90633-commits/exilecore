package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.character.StatsReport;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.UniqueArt;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 網頁角色頁（2026-10，像 poe.ninja）：每個角色最後的樣子存成 JSON（character_profiles），網頁 /builds 照這個畫。
 * <ul>
 *   <li>快照：在線的角色每 5 分鐘一次、下線時一次、/char profile 時一次（主執行緒做 JSON，資料庫在背景寫）。</li>
 *   <li>內容：職業、等級、昇華、天賦（規劃器的代碼）、天賦樹珠寶、十個裝備欄（名稱與說明文字照遊戲裡的樣子）、技能與輔助、屬性。</li>
 *   <li>預設公開；玩家 /char profile off 關掉（角色進度的 {@value #HIDDEN_KEY}），關掉的網頁上看不到。刪掉的角色也看不到。</li>
 *   <li>2026-10：不公開也能自己看：遊戲裡給的連結帶一把鑰匙（{@code #名字~鑰匙}，伺服器密鑰對角色編號的 HMAC），有鑰匙就看得到。</li>
 * </ul>
 */
public final class Profiles implements Listener {

    public static final String HIDDEN_KEY = "profile.hidden";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long LIST_CACHE_MILLIS = 30_000;

    private final ExileCore plugin;
    private BukkitTask task;
    private volatile byte[] listCache;
    private volatile long listCachedAt;

    Profiles(final ExileCore plugin) {
        this.plugin = plugin;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("web.profiles", this::snapshotSlice, 20L * 60, 20L); // 2026-10-20：攤開做，5 分鐘一輪（以前一次全做，100 人時卡一下）
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        snapshot(event.getPlayer());
    }

    /** 關服：在線的人都做一次（資料庫還在的時候）。 */
    void snapshotOnline() {
        snapshotAll();
    }

    /** 每秒一小批，300 秒一輪：每個在線的人一輪快照一次。 */
    private final tw.exilecore.core.Staggered rounds = new tw.exilecore.core.Staggered(300);

    private void snapshotSlice() {
        final List<java.util.UUID> online = new ArrayList<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        for (final java.util.UUID id : rounds.next(online)) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.isOnline()) {
                snapshot(player);
            }
        }
    }

    private void snapshotAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            snapshot(player);
        }
    }

    /** 公開或不公開（玩家指令）；回傳現在是不是公開的。 */
    public boolean setPublic(final Player player, final boolean visible) {
        if (visible) {
            plugin.characters().removeProgress(player, HIDDEN_KEY);
        } else {
            plugin.characters().setProgress(player, HIDDEN_KEY, "1");
        }
        snapshot(player);
        return visible;
    }

    public boolean isPublic(final Player player) {
        return !plugin.players().get(player).progress().containsKey(HIDDEN_KEY);
    }

    /** 做一次快照（主執行緒）。 */
    public void snapshot(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return;
        }
        final CharacterRecord character = state.character();
        final JsonObject json;
        try {
            json = build(player, state, character);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("角色頁：{} 的快照做不出來：{}", character.name(), e.toString());
            return;
        }
        final long id = character.id();
        final String account = player.getUniqueId().toString();
        final String name = character.name();
        final String classId = character.characterClass().id();
        final int level = state.level();
        final String ascendancy = json.has("ascendancy") ? json.getAsJsonObject("ascendancy").get("name").getAsString() : null;
        final int hidden = state.progress().containsKey(HIDDEN_KEY) ? 1 : 0;
        final String data = GSON.toJson(json);
        plugin.database().async(connection -> {
            final Timestamp now = Timestamp.from(Instant.now());
            try (PreparedStatement update = connection.prepareStatement("UPDATE character_profiles SET account_uuid = ?, name = ?, class_id = ?, "
                    + "level = ?, ascendancy = ?, hidden = ?, data = ?, updated_at = ? WHERE character_id = ?")) {
                update.setString(1, account);
                update.setString(2, name);
                update.setString(3, classId);
                update.setInt(4, level);
                update.setString(5, ascendancy);
                update.setInt(6, hidden);
                update.setString(7, data);
                update.setTimestamp(8, now);
                update.setLong(9, id);
                if (update.executeUpdate() > 0) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO character_profiles (character_id, account_uuid, name, "
                    + "class_id, level, ascendancy, hidden, data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, id);
                insert.setString(2, account);
                insert.setString(3, name);
                insert.setString(4, classId);
                insert.setInt(5, level);
                insert.setString(6, ascendancy);
                insert.setInt(7, hidden);
                insert.setString(8, data);
                insert.setTimestamp(9, now);
                insert.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("角色頁：{} 的快照寫不進資料庫：{}", name, error.toString());
            } else {
                listCache = null;
            }
        });
    }

    // ---- 做 JSON ----

    private JsonObject build(final Player player, final PlayerState state, final CharacterRecord character) {
        final JsonObject root = new JsonObject();
        root.addProperty("name", character.name());
        root.addProperty("level", state.level());
        root.addProperty("classId", character.characterClass().id());
        root.addProperty("className", character.characterClass().displayName());
        root.addProperty("classColor", color(character.characterClass().color()));
        root.addProperty("updated", System.currentTimeMillis());
        // 昇華
        if (plugin.ascendancy() != null) {
            final tw.exilecore.ascendancy.Ascension ascension = plugin.ascendancy().ascension(state);
            if (ascension.chosen() != null) {
                final JsonObject asc = new JsonObject();
                asc.addProperty("name", ascension.chosen().name());
                asc.addProperty("color", color(ascension.chosen().color()));
                final JsonArray nodes = new JsonArray();
                for (final var node : ascension.allocated()) {
                    final JsonObject n = new JsonObject();
                    n.addProperty("name", node.name());
                    final JsonArray lines = new JsonArray();
                    for (final String line : tw.exilecore.ascendancy.StatLines.describeAll(node.stats())) {
                        lines.add(line);
                    }
                    if (node.note() != null && !node.note().isBlank()) {
                        lines.add(node.note());
                    }
                    n.add("lines", lines);
                    nodes.add(n);
                }
                asc.add("nodes", nodes);
                root.add("ascendancy", asc);
            }
        }
        // 天賦
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        if (tree != null) {
            root.addProperty("treeCode", TreeCode.encode(tree, character.characterClass().id(), state.passives()));
            root.addProperty("passiveCount", state.passives().size());
        }
        // 裝備
        final JsonArray gear = new JsonArray();
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            final JsonObject slot = new JsonObject();
            final ItemSlot type = PlayerState.JEWELLERY_SLOTS[i];
            slot.addProperty("slot", type.name().toLowerCase(Locale.ROOT) + (type == ItemSlot.RING ? (i == 0 ? "1" : "2") : ""));
            slot.addProperty("label", type.displayName());
            final ItemStack stack = state.jewellery(i);
            if (stack != null && !stack.isEmpty()) {
                slot.add("item", item(stack, type));
            }
            gear.add(slot);
        }
        root.add("gear", gear);
        // 天賦樹珠寶
        final JsonArray jewels = new JsonArray();
        for (final Map.Entry<String, ItemStack> entry : state.jewels().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                final JsonObject jewel = item(entry.getValue(), null);
                jewel.addProperty("node", entry.getKey());
                jewels.add(jewel);
            }
        }
        root.add("jewels", jewels);
        // 2026-10：護符（開著的每一格，空的也列）與藥劑（快捷欄的生命、魔力藥劑欄）
        final tw.exilecore.flask.FlaskService flaskService = plugin.flasks();
        final JsonArray charms = new JsonArray();
        final JsonArray flasks = new JsonArray();
        if (flaskService != null) {
            try {
                if (flaskService.charms() != null) {
                    final int open = flaskService.charms().activeCount(player);
                    for (int i = 0; i < open; i++) {
                        final JsonObject slot = new JsonObject();
                        slot.addProperty("label", "護符 " + (i + 1));
                        final ItemStack stack = tw.exilecore.flask.CharmService.charmAt(state, i);
                        if (stack != null && !stack.isEmpty()) {
                            slot.add("item", item(stack, null, "charm"));
                        }
                        charms.add(slot);
                    }
                }
                for (final int hotbar : flaskService.slots(player)) {
                    final JsonObject slot = new JsonObject();
                    final tw.exilecore.flask.FlaskKind kind = flaskService.kindAt(player, hotbar);
                    slot.addProperty("label", kind == null ? "藥劑" : kind.displayName() + "藥劑");
                    final ItemStack stack = player.getInventory().getItem(hotbar);
                    if (stack != null && !stack.isEmpty() && flaskService.kindOf(stack) != null) {
                        slot.add("item", item(stack, null, "flask"));
                    }
                    flasks.add(slot);
                }
            } catch (final RuntimeException ignored) {
                // 讀不到就不列（角色頁其他部分照常）
            }
        }
        root.add("charms", charms);
        root.add("flasks", flasks);
        // 技能
        final JsonArray skills = new JsonArray();
        final SkillLoadout loadout = state.skills();
        if (loadout != null && plugin.skills() != null) {
            for (final SkillLoadout.Entry entry : loadout.entries()) {
                if (entry == null) {
                    continue;
                }
                final var definition = plugin.skills().config().skill(entry.skillId());
                if (definition == null) {
                    continue;
                }
                final JsonObject skill = new JsonObject();
                skill.addProperty("name", definition.name());
                skill.addProperty("level", entry.level());
                skill.addProperty("sockets", entry.sockets());
                if (entry.inner() != null) { // 2026-10-22：放在英靈圖騰、星咒圖騰、暴擊引星第 1 孔裡的技能
                    final var inner = plugin.skills().config().skill(entry.inner().skillId());
                    skill.addProperty("inner", (inner == null ? entry.inner().skillId() : inner.name()) + " Lv." + entry.inner().level());
                }
                final JsonArray supports = new JsonArray();
                for (final String supportId : entry.supportIds()) {
                    final var support = plugin.skills().config().support(supportId);
                    if (support != null) {
                        supports.add(support.name());
                    }
                }
                skill.add("supports", supports);
                // 2026-10：滑鼠移上去看得到說明（跟遊戲裡技能面板、寶石上寫的一樣）
                try {
                    final JsonArray lines = new JsonArray();
                    for (final String line : plugin.skills().describe(player, definition, entry)) {
                        final JsonArray spans = spans(plugin.text().parse(line));
                        lines.add(spans);
                    }
                    skill.add("lines", lines);
                    final JsonArray supportInfo = new JsonArray();
                    for (final String supportId : entry.supportIds()) {
                        final var support = plugin.skills().config().support(supportId);
                        if (support == null) {
                            continue;
                        }
                        final JsonObject info = new JsonObject();
                        info.addProperty("name", support.name());
                        final JsonArray supportLines = new JsonArray();
                        supportLines.add(spans(plugin.text().parse("<gray>" + tw.exilecore.item.ItemRenderer.escape(support.descriptionText()))));
                        // 2026-10：用技能那套說明（認得 flag.、proc. 這些輔助專用的屬性，不會秀出英文代號）
                        for (final String line : tw.exilecore.skill.ModifierText.describeAll(support.allModifiers())) {
                            supportLines.add(spans(plugin.text().parse("<#b4bcff>" + line)));
                        }
                        info.add("lines", supportLines);
                        supportInfo.add(info);
                    }
                    skill.add("supportInfo", supportInfo);
                } catch (final RuntimeException ignored) {
                    // 說明算不出來就只列名字
                }
                skills.add(skill);
            }
        }
        root.add("skills", skills);
        // 2026-10：每個技能估計的每秒傷害（管理員的統計頁用；跟 /stats 的「✦ 技能 每秒」一樣算法：沒算怪物減免、持續傷害、戰吼與怒氣這些打起來才有的加成）
        final JsonArray dps = new JsonArray();
        if (plugin.skills() != null) {
            try {
                for (final String[] row : plugin.skills().skillDps(player)) {
                    final JsonObject d = new JsonObject();
                    d.addProperty("name", row[1]);
                    d.addProperty("dps", Long.parseLong(row[2]));
                    if (row.length > 3 && Long.parseLong(row[3]) > Long.parseLong(row[2])) {
                        d.addProperty("full", Long.parseLong(row[3])); // 2026-10：戰吼、戰旗、詛咒、印記全開
                    }
                    dps.add(d);
                }
            } catch (final RuntimeException ignored) {
                // 算不出來就不寫
            }
        }
        root.add("dps", dps);
        // 2026-10：打訓練假人的實測（估計值沒算戰吼、怒氣、持續傷害，跟實際差很多）
        final tw.exilecore.training.DummyRecord measured = tw.exilecore.training.DummyRecord.decode(
                state.progress().get(tw.exilecore.training.DummyRecord.KEY));
        if (measured != null) {
            final JsonObject m = new JsonObject();
            m.addProperty("dps", measured.dps());
            m.addProperty("seconds", measured.seconds());
            m.addProperty("dummy", measured.dummy());
            m.addProperty("dot", measured.dotPercent());
            m.addProperty("at", measured.at());
            root.add("measured", m);
        }
        // 屬性
        final JsonArray stats = new JsonArray();
        for (final Map.Entry<String, List<String>> section : StatsReport.sections(plugin, player).entrySet()) {
            final JsonObject block = new JsonObject();
            block.addProperty("title", section.getKey());
            final JsonArray lines = new JsonArray();
            for (final String line : section.getValue()) {
                final JsonArray spans = spans(plugin.text().parse(line));
                if (!spans.isEmpty()) {
                    lines.add(spans);
                }
            }
            block.add("lines", lines);
            stats.add(block);
        }
        root.add("stats", stats);
        // 2026-10：網頁計算器（/tree 的「載入角色」）要的原始資料
        try {
            root.add("calc", calcJson(player, state, character));
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("角色頁：{} 的計算器資料做不出來：{}", character.name(), e.toString());
        }
        return root;
    }

    /**
     * 網頁計算器的原始資料（2026-10）：天賦代碼（含屬性點）、昇華、算加成用的進度、十個裝備欄與珠寶的物品資料、技能面板與按鍵、
     * 要看玩家本人才算得出來的永久加成（稱號、公會、贊助、分身獎勵）。格式就是 {@link BuildCalc} 收的。
     * 物品的 UUID 拿掉（網頁用不到）。
     */
    private JsonObject calcJson(final Player player, final PlayerState state, final CharacterRecord character) {
        final JsonObject calc = new JsonObject();
        calc.addProperty("level", state.level());
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        final Map<String, String> attributes = new java.util.LinkedHashMap<>();
        final JsonObject progress = new JsonObject();
        for (final Map.Entry<String, String> entry : state.progress().entrySet()) {
            if (entry.getKey().startsWith("attr.")) {
                attributes.put(entry.getKey().substring("attr.".length()), entry.getValue());
            }
            if (BuildCalc.allowedProgress(entry.getKey())) {
                progress.addProperty(entry.getKey(), entry.getValue());
            }
        }
        if (tree != null) {
            calc.addProperty("tree", TreeCode.encode(tree, character.characterClass().id(), state.passives(), attributes));
        }
        final String asc = state.progress().get(tw.exilecore.ascendancy.Ascension.CHOSEN);
        if (asc != null && !asc.isBlank()) {
            calc.addProperty("asc", asc);
            final JsonArray ascNodes = new JsonArray();
            final String nodes = state.progress().get(tw.exilecore.ascendancy.Ascension.NODES);
            if (nodes != null) {
                for (final String id : nodes.split(",")) {
                    if (!id.isBlank()) {
                        ascNodes.add(id.trim());
                    }
                }
            }
            calc.add("ascNodes", ascNodes);
        }
        calc.add("progress", progress);
        final JsonArray gear = new JsonArray();
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            gear.add(itemData(state.jewellery(i)));
        }
        calc.add("gear", gear);
        final JsonObject jewels = new JsonObject();
        for (final Map.Entry<String, ItemStack> entry : state.jewels().entrySet()) {
            final JsonElement data = itemData(entry.getValue());
            if (!data.isJsonNull()) {
                jewels.add(entry.getKey(), data);
            }
        }
        calc.add("jewels", jewels);
        final JsonArray skills = new JsonArray();
        final JsonArray binds = new JsonArray();
        final SkillLoadout loadout = state.skills();
        final Map<Integer, Integer> index = new java.util.HashMap<>();
        if (loadout != null) {
            for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
                final SkillLoadout.Entry entry = loadout.skill(position);
                if (entry == null) {
                    continue;
                }
                index.put(position, skills.size());
                final JsonObject skill = new JsonObject();
                skill.addProperty("id", entry.skillId());
                skill.addProperty("level", entry.level());
                skill.addProperty("sockets", entry.sockets());
                final JsonArray supports = new JsonArray();
                for (int i = 0; i < entry.sockets(); i++) {
                    // 2026-10-18：共鳴的寶石要帶 ~，BuildCalc 才會算成共鳴（跟存檔格式一樣）
                    // 2026-10-22：宿主第 1 孔裡的技能寫成 @代號/等級/孔數（跟存檔格式一樣，Entry 認得）
                    if (i == SkillLoadout.Entry.INNER_SOCKET && entry.inner() != null) {
                        supports.add(SkillLoadout.Entry.INNER_PREFIX + entry.inner().skillId() + "/" + entry.inner().level() + "/" + entry.inner().sockets());
                        continue;
                    }
                    supports.add(entry.support(i) + (entry.resonant(i) ? SkillLoadout.Entry.RESONANT_SUFFIX : ""));
                }
                skill.add("supports", supports);
                skills.add(skill);
            }
            for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
                binds.add(index.getOrDefault(loadout.bound(slot), -1));
            }
        }
        calc.add("skills", skills);
        calc.add("binds", binds);
        final JsonArray extra = new JsonArray();
        for (final tw.exilecore.stats.Modifier modifier : plugin.players().persistentExtras(player)) {
            extra.add(BuildCalc.modifierJson(modifier));
        }
        calc.add("extra", extra);
        return calc;
    }

    /** 一件物品的資料（JSON，拿掉 UUID）；不是我們的物品回傳 JsonNull。 */
    private JsonElement itemData(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || plugin.items() == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        if (data == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        final JsonObject json = com.google.gson.JsonParser.parseString(plugin.items().toJson(data)).getAsJsonObject();
        json.remove("uuid");
        return json;
    }

    /** 一件物品：名稱、說明文字（照遊戲裡的樣子，含顏色）、稀有度、圖示。 */
    private JsonObject item(final ItemStack stack, final ItemSlot slot) {
        return item(stack, slot, "jewel");
    }

    /** @param other 不是裝備欄位的東西算哪一種（jewel 珠寶、charm 護符、flask 藥劑；決定網頁上的圖示） */
    private JsonObject item(final ItemStack stack, final ItemSlot slot, final String other) {
        final JsonObject object = new JsonObject();
        final ItemMeta meta = stack.getItemMeta();
        final Component name = meta != null && meta.displayName() != null ? meta.displayName() : Component.translatable(stack.translationKey());
        object.add("name", spans(name));
        final JsonArray lore = new JsonArray();
        if (meta != null && meta.lore() != null) {
            for (final Component line : meta.lore()) {
                final JsonArray spans = spans(line);
                // 操作提示（右鍵、Shift…）網頁上用不到
                final String text = plain(spans);
                if (text.contains("左鍵") || text.contains("右鍵") || text.contains("Shift")) {
                    continue;
                }
                lore.add(spans);
            }
        }
        object.add("lore", lore);
        final ItemData data = plugin.items().read(stack).orElse(null);
        object.addProperty("rarity", data == null ? "normal" : data.rarity().name().toLowerCase(Locale.ROOT));
        if (data != null && data.unique() != null && UniqueArt.has(data.unique())) {
            object.addProperty("icon", "u:" + data.unique());
        }
        if (slot != null) {
            object.addProperty("kind", slot.name().toLowerCase(Locale.ROOT));
        } else {
            object.addProperty("kind", other);
        }
        return object;
    }

    /** 文字元件攤平成 [[文字, 顏色], …]；自訂字型的圖示字略過。 */
    static JsonArray spans(final Component component) {
        final JsonArray out = new JsonArray();
        walk(component, null, false, out);
        return out;
    }

    private static void walk(final Component component, final TextColor inherited, final boolean boldInherited, final JsonArray out) {
        final TextColor color = component.color() != null ? component.color() : inherited;
        final TextDecoration.State boldState = component.decoration(TextDecoration.BOLD);
        final boolean bold = boldState == TextDecoration.State.TRUE || boldState == TextDecoration.State.NOT_SET && boldInherited;
        final Key font = component.font();
        final boolean iconFont = font != null && !"minecraft:default".equals(font.asString()) && !"minecraft:uniform".equals(font.asString());
        String text = null;
        if (component instanceof TextComponent textComponent) {
            text = textComponent.content();
        } else if (component instanceof TranslatableComponent translatable) {
            text = translatable.fallback() != null ? translatable.fallback() : translatable.key();
        }
        if (text != null && !text.isEmpty() && !iconFont) {
            final JsonArray span = new JsonArray();
            span.add(text);
            span.add(color == null ? "" : color.asHexString());
            if (bold) {
                span.add(1);
            }
            out.add(span);
        }
        for (final Component child : component.children()) {
            walk(child, color, bold, out);
        }
    }

    private static String plain(final JsonArray spans) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            out.append(spans.get(i).getAsJsonArray().get(0).getAsString());
        }
        return out.toString();
    }

    private static String color(final String miniMessage) {
        final String text = miniMessage == null ? "" : miniMessage.replace("<", "").replace(">", "").trim();
        return text.matches("#[0-9a-fA-F]{6}") ? text.toLowerCase(Locale.ROOT) : "#f5b942";
    }

    // ---- 網頁 ----

    /** 公開角色的清單（30 秒快取）。 */
    byte[] listJson() throws Exception {
        final byte[] cached = listCache;
        if (cached != null && System.currentTimeMillis() - listCachedAt < LIST_CACHE_MILLIS) {
            return cached;
        }
        final byte[] bytes = listJson(false);
        listCache = bytes;
        listCachedAt = System.currentTimeMillis();
        return bytes;
    }

    /** 角色清單；all = 管理員看（不公開的也列，標 hidden；不快取）。 */
    public byte[] listJson(final boolean all) throws Exception {
        final CompletableFuture<byte[]> future = plugin.database().async(connection -> {
            final JsonArray rows = new JsonArray();
            try (PreparedStatement select = connection.prepareStatement("SELECT p.name, p.class_id, p.level, p.ascendancy, p.updated_at, p.hidden "
                    + "FROM character_profiles p JOIN characters c ON c.id = p.character_id WHERE " + (all ? "" : "p.hidden = 0 AND ")
                    + "c.deleted_at IS NULL ORDER BY p.level DESC, p.updated_at DESC LIMIT 2000");
                 ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    final JsonObject row = new JsonObject();
                    row.addProperty("name", result.getString("name"));
                    row.addProperty("classId", result.getString("class_id"));
                    row.addProperty("level", result.getInt("level"));
                    row.addProperty("ascendancy", result.getString("ascendancy"));
                    final Timestamp updated = result.getTimestamp("updated_at");
                    row.addProperty("updated", updated == null ? 0 : updated.getTime());
                    if (all && result.getInt("hidden") != 0) {
                        row.addProperty("hidden", true);
                    }
                    rows.add(row);
                }
            }
            final JsonObject root = new JsonObject();
            final JsonArray classes = new JsonArray();
            for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
                final JsonObject c = new JsonObject();
                c.addProperty("id", type.id());
                c.addProperty("name", type.displayName());
                c.addProperty("color", color(type.color()));
                classes.add(c);
            }
            root.add("classes", classes);
            root.add("characters", rows);
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    /** 一個角色的快照（照名稱，不分大小寫）；沒有或不公開 null。 */
    byte[] characterJson(final String name, final String key) throws Exception {
        return characterJson(name, key, false);
    }

    /** admin = 管理員看（不公開的也看得到，標 private）。 */
    public byte[] characterJson(final String name, final String key, final boolean admin) throws Exception {
        if (name == null || name.isBlank() || name.length() > 32) {
            return null;
        }
        final CompletableFuture<byte[]> future = plugin.database().async(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT p.data, p.hidden, p.character_id FROM character_profiles p "
                    + "JOIN characters c ON c.id = p.character_id WHERE LOWER(p.name) = ? AND c.deleted_at IS NULL "
                    + "ORDER BY p.hidden ASC, p.updated_at DESC")) {
                select.setString(1, name.trim().toLowerCase(Locale.ROOT));
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        final boolean hidden = result.getInt(2) != 0;
                        // 2026-10-18：鑰匙用固定時間比對（MessageDigest.isEqual）
                        final boolean owner = admin || key != null && !key.isBlank() && java.security.MessageDigest.isEqual(
                                key.getBytes(StandardCharsets.UTF_8), key(result.getLong(3)).getBytes(StandardCharsets.UTF_8));
                        if (hidden && !owner) { // 不公開、又不是本人或管理員：跳過
                            continue;
                        }
                        if (!hidden) {
                            return result.getString(1).getBytes(StandardCharsets.UTF_8);
                        }
                        // 不公開、本人帶著鑰匙：標一下，網頁上寫「只有你看得到」
                        final JsonObject json = GSON.fromJson(result.getString(1), JsonObject.class);
                        json.addProperty("private", true);
                        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
                    }
                    return null;
                }
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    /**
     * 管理員的統計頁（2026-10）：每個角色（含不公開的）一行精簡資料——等級、職業、昇華、每個技能的估計每秒傷害、
     * 技能與輔助、穿著的傳奇（裝備與珠寶）。統計在網頁上算（可以篩等級、職業）。
     */
    public byte[] metaJson() throws Exception {
        final CompletableFuture<byte[]> future = plugin.database().async(connection -> {
            final JsonArray rows = new JsonArray();
            try (PreparedStatement select = connection.prepareStatement("SELECT p.data, p.hidden, p.updated_at FROM character_profiles p "
                    + "JOIN characters c ON c.id = p.character_id WHERE c.deleted_at IS NULL ORDER BY p.level DESC LIMIT 5000");
                 ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    final JsonObject data;
                    try {
                        data = GSON.fromJson(result.getString(1), JsonObject.class);
                    } catch (final RuntimeException e) {
                        continue;
                    }
                    final JsonObject row = new JsonObject();
                    for (final String key : new String[] {"name", "level", "classId", "className", "classColor", "dps", "measured"}) {
                        if (data.has(key)) {
                            row.add(key, data.get(key));
                        }
                    }
                    if (data.has("ascendancy")) {
                        row.addProperty("ascendancy", data.getAsJsonObject("ascendancy").get("name").getAsString());
                    }
                    final JsonArray skills = new JsonArray();
                    if (data.has("skills")) {
                        for (final var each : data.getAsJsonArray("skills")) {
                            final JsonObject skill = each.getAsJsonObject();
                            final JsonObject slim = new JsonObject();
                            slim.add("name", skill.get("name"));
                            slim.add("supports", skill.has("supports") ? skill.get("supports") : new JsonArray());
                            skills.add(slim);
                        }
                    }
                    row.add("skills", skills);
                    final JsonArray uniques = new JsonArray();
                    final List<JsonObject> items = new ArrayList<>();
                    if (data.has("gear")) {
                        for (final var each : data.getAsJsonArray("gear")) {
                            final JsonObject slot = each.getAsJsonObject();
                            if (slot.has("item")) {
                                items.add(slot.getAsJsonObject("item"));
                            }
                        }
                    }
                    if (data.has("jewels")) {
                        for (final var each : data.getAsJsonArray("jewels")) {
                            items.add(each.getAsJsonObject());
                        }
                    }
                    for (final JsonObject item : items) {
                        if (item.has("rarity") && "unique".equals(item.get("rarity").getAsString()) && item.has("name")) {
                            uniques.add(plain(item.getAsJsonArray("name")));
                        }
                    }
                    row.add("uniques", uniques);
                    row.addProperty("hidden", result.getInt(2) != 0);
                    final Timestamp updated = result.getTimestamp(3);
                    row.addProperty("updated", updated == null ? 0 : updated.getTime());
                    rows.add(row);
                }
            }
            final JsonObject root = new JsonObject();
            root.add("characters", rows);
            return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        });
        return future.get(15, TimeUnit.SECONDS);
    }

    /** 傳奇的專屬圖示（PNG）；沒有 null。 */
    static byte[] uniqueIcon(final String id) {
        final UniqueArt.Sprite sprite = id == null ? null : UniqueArt.sprites().get(id);
        if (sprite == null) {
            return null;
        }
        try {
            return UiPack.png(UiPack.spriteImage(sprite));
        } catch (final java.io.IOException e) {
            return null;
        }
    }

    /** 在聊天欄送出自己的角色頁連結（順便做一次快照，網頁上就是最新的）。 */
    public void sendLink(final Player player) {
        snapshot(player);
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        final String url = privateUrl(state.character().name(), state.character().id());
        final WebModule web = plugin.web();
        final String list = web == null ? null : web.publicUrl() + "/builds";
        if (url == null) {
            player.sendMessage(plugin.text().prefixed("<red>網頁沒有開（config.yml 的 web.enabled）"));
            return;
        }
        player.sendMessage(plugin.text().prefixed((isPublic(player) ? "" : "<gray>（不公開：別人看不到，這個連結只有你打得開，別分享出去）</gray> ")
                + "<click:open_url:'" + url.replace("'", "%27") + "'><hover:show_text:'<gray>" + tw.exilecore.item.ItemRenderer.escape(url)
                + "'><#77ccff><u>[我的角色頁]</u></hover></click>　<click:open_url:'" + list.replace("'", "%27")
                + "'><hover:show_text:'<gray>" + tw.exilecore.item.ItemRenderer.escape(list) + "'><#77ccff><u>[所有角色]</u></hover></click>"));
    }

    /** 本人用的網址（帶鑰匙：不公開也打得開）。 */
    public String privateUrl(final String characterName, final long characterId) {
        final String base = url(characterName);
        return base == null ? null : base + "~" + key(characterId);
    }

    /** 角色的鑰匙：伺服器密鑰（plugins/ExileCore/profile-secret.txt，第一次用自己產生）對角色編號的 HMAC，取前 22 字。 */
    String key(final long characterId) {
        try {
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret(), "HmacSHA256"));
            final byte[] hash = mac.doFinal(("profile:" + characterId).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22);
        } catch (final java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private volatile byte[] secret;

    private synchronized byte[] secret() {
        if (secret != null) {
            return secret;
        }
        final java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("profile-secret.txt");
        try {
            if (java.nio.file.Files.exists(file)) {
                final String text = java.nio.file.Files.readString(file).trim();
                if (text.length() >= 32) {
                    secret = text.getBytes(StandardCharsets.UTF_8);
                    return secret;
                }
            }
            final byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            final String text = java.util.HexFormat.of().formatHex(random);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, text + "\n");
            secret = text.getBytes(StandardCharsets.UTF_8);
            return secret;
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("角色頁的密鑰讀寫不了：" + file, e);
        }
    }

    /** 網頁上「我的角色頁」的網址。 */
    public String url(final String characterName) {
        final WebModule web = plugin.web();
        if (web == null) {
            return null;
        }
        return web.publicUrl() + "/builds#" + java.net.URLEncoder.encode(characterName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
