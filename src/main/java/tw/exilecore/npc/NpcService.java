package tw.exilecore.npc;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.map.MapDeviceMenu;
import tw.exilecore.market.MarketModule;
import tw.exilecore.vendor.VendorMenu;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.salvage.SalvageMenu;
import tw.exilecore.zone.WaypointMenu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 營地 NPC：用原版的「假人」（Mannequin）當身體，不會動、不會受傷、怪物不會打它，
 * 實體的 PDC 記著它是哪一種 NPC，所以跟著區塊存檔，重開伺服器還在。
 * <p>
 * 鑑定師照 PoE2 的兜帽者：右鍵打開選單，一鍵把背包裡所有未鑑定的物品免費鑑定；
 * 也可以免費領鑑定卷軸（補滿到一疊）帶去野外用。
 */
public final class NpcService {

    /** 同一個玩家兩次點 NPC 至少隔幾 tick（避免連點洗訊息）。 */
    private static final long INTERACT_COOLDOWN_TICKS = 10;
    /** 鑑定完在聊天列出名字的稀有／傳奇物品上限。 */
    private static final int LIST_LIMIT = 6;

    /** 一次鑑定的結果。 */
    public record IdentifyResult(int magic, int rare, int unique, List<String> highlights) {
        public int total() {
            return magic + rare + unique;
        }
    }

    private final ExileCore plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey storyKey;
    private final Map<UUID, Long> lastInteract = new HashMap<>();

    public NpcService(final ExileCore plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "npc");
        this.storyKey = new NamespacedKey(plugin, "npc_story");
    }

    // ---- 生成與查詢 ----

    /** 在指定位置生成一個 NPC（朝向跟著 location 的 yaw）。 */
    public Mannequin spawn(final Location location, final NpcType type) {
        return location.getWorld().spawn(location, Mannequin.class, npc -> {
            npc.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
            dress(npc, type);
            npc.setImmovable(true);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setPersistent(true);
            npc.setRemoveWhenFarAway(false);
            ensureSkin(npc);
        });
    }

    /** 營地 NPC 的名字與說明；只是擺設的（{@link NpcType#decorative}）沒有名字。 */
    public void dress(final Entity entity, final NpcType type) {
        if (!(entity instanceof Mannequin mannequin) || type == NpcType.STORY) {
            return;
        }
        if (type.decorative()) {
            mannequin.customName(null);
            mannequin.setCustomNameVisible(false);
            mannequin.setDescription(null);
            return;
        }
        mannequin.customName(plugin.text().parse("<#f5b942>" + type.displayName()));
        mannequin.setCustomNameVisible(true);
        mannequin.setDescription(plugin.text().parse("<gray>" + type.hint()));
    }

    /** 在指定位置放一個劇情人物（quests.yml 的 npcs）。 */
    public Mannequin spawnStory(final Location location, final tw.exilecore.quest.QuestConfig.Npc definition) {
        return location.getWorld().spawn(location, Mannequin.class, npc -> {
            npc.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, NpcType.STORY.id());
            npc.getPersistentDataContainer().set(storyKey, PersistentDataType.STRING, definition.id());
            npc.setCustomNameVisible(true);
            npc.setImmovable(true);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setPersistent(true);
            npc.setRemoveWhenFarAway(false);
            dressStory(npc, definition);
            ensureSkin(npc);
        });
    }

    /** 劇情人物的名字、頭銜、手上拿的東西跟上設定檔。 */
    public void dressStory(final Entity entity, final tw.exilecore.quest.QuestConfig.Npc definition) {
        if (definition.name().isBlank()) {
            // 只是擺設的劇情人物（quests.yml 沒寫名字）：沒有名字、沒有說明
            entity.customName(null);
            entity.setCustomNameVisible(false);
            if (entity instanceof Mannequin mannequin) {
                mannequin.setDescription(null);
            }
        } else {
            entity.customName(plugin.text().parse("<#f5b942>" + ItemRenderer.escape(definition.name())));
            entity.setCustomNameVisible(true);
        }
        if (entity instanceof Mannequin mannequin && !definition.name().isBlank()) {
            mannequin.setDescription(plugin.text().parse("<gray>" + (definition.title().isBlank() ? ""
                    : ItemRenderer.escape(definition.title()) + "・") + NpcType.STORY.hint()));
            final org.bukkit.Material hold = definition.hold() == null ? null
                    : org.bukkit.Material.matchMaterial(definition.hold());
            final org.bukkit.inventory.EntityEquipment equipment = mannequin.getEquipment();
            if (equipment == null) {
                return;
            }
            final ItemStack held = equipment.getItemInMainHand();
            if (hold == null || !hold.isItem()) {
                if (!held.isEmpty()) {
                    equipment.setItemInMainHand(null);
                }
            } else if (held.getType() != hold) {
                equipment.setItemInMainHand(ItemStack.of(hold));
            }
        }
    }

    // ---- 外觀 ----

    /** 盜賊首領的皮膚（玩家名稱）。 */
    private static final String HEIST_SKIN = "Penguin0c0";
    /** 地圖裝置的皮膚（玩家名稱，2026-10）。 */
    private static final String MAP_DEVICE_SKIN = "UTro_";
    /** 商人的皮膚（玩家名稱，2026-10）。 */
    private static final String VENDOR_SKIN = "ss9319622";

    /** 原版內建的 9 種預設皮膚（textures/entity/player/{wide,slim}/名字.png）。 */
    private static final String[] DEFAULT_SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

    /**
     * 還是預設樣子（沒設過皮膚）的 NPC 隨機套一個原版內建的皮膚（2026-10，之後再換成專屬的）。
     * 用實體的 UUID 決定，同一個 NPC 每次都一樣；皮膚跟著實體存檔。
     */
    public void ensureSkin(final Entity entity) {
        if (!(entity instanceof Mannequin mannequin) || !isNpc(entity)) {
            return;
        }
        final io.papermc.paper.datacomponent.item.ResolvableProfile current = mannequin.getProfile();
        // 2026-10：盜賊首領用玩家 Penguin0c0、地圖裝置用 UTro_、商人用 ss9319622 的皮膚（照名字讓客戶端去抓；換成別的名字改這裡）
        final NpcType npcType = type(entity).orElse(null);
        final String named = npcType == NpcType.HEIST ? HEIST_SKIN : npcType == NpcType.MAP_DEVICE ? MAP_DEVICE_SKIN
                : npcType == NpcType.VENDOR ? VENDOR_SKIN : null;
        if (named != null) {
            if (current == null || !named.equals(current.name())) {
                mannequin.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile().name(named).build());
            }
            return;
        }
        final boolean unset = current == null || current.equals(Mannequin.defaultProfile())
                || current.name() == null && current.properties().isEmpty() && (current.skinPatch() == null || current.skinPatch().isEmpty());
        if (!unset) {
            return; // 已經有皮膚（自己設過的不動）
        }
        final long seed = entity.getUniqueId().getMostSignificantBits() ^ entity.getUniqueId().getLeastSignificantBits();
        final java.util.Random random = new java.util.Random(seed);
        final String skin = DEFAULT_SKINS[random.nextInt(DEFAULT_SKINS.length)];
        final boolean slim = random.nextBoolean();
        final net.kyori.adventure.key.Key body = net.kyori.adventure.key.Key.key("minecraft",
                "entity/player/" + (slim ? "slim/" : "wide/") + skin);
        mannequin.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                .skinPatch(patch -> patch.body(body).model(slim ? org.bukkit.profile.PlayerTextures.SkinModel.SLIM
                        : org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC))
                .build());
    }

    public boolean isNpc(final Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(typeKey);
    }

    /** 劇情人物的代號（不是劇情人物回傳 null）。 */
    public String storyId(final Entity entity) {
        return entity == null ? null : entity.getPersistentDataContainer().get(storyKey, PersistentDataType.STRING);
    }

    /** 任務用的 NPC 代號：劇情人物的代號、營地 NPC 的種類代號；不是 NPC 回傳 null。 */
    public String questId(final Entity entity) {
        final NpcType type = type(entity).orElse(null);
        if (type == null) {
            return null;
        }
        return type == NpcType.STORY ? storyId(entity) : type.id();
    }

    public Optional<NpcType> type(final Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(NpcType.parse(entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING)));
    }

    /** 玩家附近最近的 NPC。 */
    public Optional<Entity> nearest(final Player player, final double radius) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!isNpc(entity)) {
                continue;
            }
            final double distance = entity.getLocation().distance(player.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 目前載入中的所有 NPC（沒載入的區塊裡的看不到）。 */
    public List<Entity> loaded() {
        final List<Entity> list = new ArrayList<>();
        for (final World world : plugin.getServer().getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                if (isNpc(entity)) {
                    list.add(entity);
                }
            }
        }
        return list;
    }

    // ---- 互動 ----

    /** 玩家右鍵 NPC。 */
    public void interact(final Player player, final NpcType type, final Entity entity) {
        final long now = plugin.getServer().getCurrentTick();
        final Long last = lastInteract.get(player.getUniqueId());
        if (last != null && now - last < INTERACT_COOLDOWN_TICKS) {
            return;
        }
        lastInteract.put(player.getUniqueId(), now);
        if (type.decorative()) {
            dress(entity, type); // 只是擺設：舊的名字拿掉，右鍵沒有反應
            return;
        }
        if (type == NpcType.STORY) {
            final tw.exilecore.quest.QuestConfig.Npc story = plugin.quests() == null || storyId(entity) == null ? null
                    : plugin.quests().config().npc(storyId(entity));
            if (story != null && story.name().isBlank()) {
                return; // 只是擺設的劇情人物
            }
            // 劇情人物：任務的對話（任務模組沒啟用時只說一句）；有功能的（軍需官的委託板）沒有任務的事就直接打開
            final String id = storyId(entity);
            if (plugin.quests() != null && id != null) {
                final Runnable function = storyFunction(player, id);
                if (!plugin.quests().talk(player, id, function) && function != null) {
                    function.run();
                }
            } else {
                player.sendMessage(plugin.text().prefixed("<gray>……（任務模組沒有啟用）"));
            }
            return;
        }
        // 舊版放的 NPC：名字與說明跟上新版
        dress(entity, type);
        // 有任務的事（回報、接任務）先跳對話視窗；對話視窗上有按鈕可以打開原本的功能
        if (plugin.quests() != null && plugin.quests().talk(player, type.id(), () -> open(player, type))) {
            return;
        }
        open(player, type);
    }

    /** 劇情人物的功能（quests.yml 的 opens）；沒有、或那個模組沒啟用回傳 null。 */
    private Runnable storyFunction(final Player player, final String id) {
        final tw.exilecore.quest.QuestConfig.Npc definition = plugin.quests().config().npc(id);
        if (definition == null || definition.opens() == null) {
            return null;
        }
        return switch (definition.opens()) {
            case "bounty" -> plugin.bounties() == null ? null : () -> plugin.bounties().open(player);
            case "trial" -> plugin.ascendancy() == null ? null : () -> plugin.ascendancy().open(player);
            default -> null;
        };
    }

    /** 打開營地 NPC 的功能。 */
    private void open(final Player player, final NpcType type) {
        switch (type) {
            case IDENTIFIER -> new IdentifierMenu(plugin, player).open();
            case STASH -> {
                if (plugin.stash() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>倉庫模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    plugin.stash().open(player, true);
                }
            }
            case SALVAGE -> {
                if (!plugin.players().get(player).hasCharacter()) {
                    player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
                } else if (plugin.salvage() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>拆解模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    new SalvageMenu(plugin, player).open();
                }
            }
            case WAYPOINT -> {
                if (plugin.zones() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    new WaypointMenu(plugin, player).open();
                }
            }
            case MAP_DEVICE -> {
                if (!plugin.players().get(player).hasCharacter()) {
                    player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
                } else if (plugin.maps() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>地圖模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    new MapDeviceMenu(plugin, player, plugin.maps()).open();
                }
            }
            case VENDOR -> {
                if (!plugin.players().get(player).hasCharacter()) {
                    player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
                } else if (plugin.vendor() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>商人模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    new VendorMenu(plugin, player, plugin.vendor()).open();
                }
            }
            case MARKET -> MarketModule.open(plugin, player);
            case HEIST -> {
                if (!plugin.players().get(player).hasCharacter()) {
                    player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
                } else if (plugin.heists() == null) {
                    player.sendMessage(plugin.text().prefixed("<red>劫盜模組沒有啟用（看主控台的錯誤訊息）"));
                } else {
                    new tw.exilecore.heist.HeistMenu(plugin, player).open();
                }
            }
            case STORY -> {
            }
        }
    }

    public void forget(final UUID uuid) {
        lastInteract.remove(uuid);
    }

    /** 鑑定師選單的「鑑定所有物品」。 */
    public void identify(final Player player) {
        final IdentifyResult result = identifyAll(player);
        if (result.total() == 0) {
            player.sendMessage(plugin.text().prefixed("<#f5b942>鑑定師：<gray>你背包裡沒有需要鑑定的東西。"));
            return;
        }
        final List<String> parts = new ArrayList<>();
        if (result.unique() > 0) {
            parts.add("傳奇 " + result.unique());
        }
        if (result.rare() > 0) {
            parts.add("稀有 " + result.rare());
        }
        if (result.magic() > 0) {
            parts.add("魔法 " + result.magic());
        }
        player.sendMessage(plugin.text().prefixed("<#f5b942>鑑定師：<gray>鑑定好了 <white>" + result.total()
                + "</white> 件（" + String.join("、", parts) + "）。"));
        for (final String line : result.highlights()) {
            player.sendMessage(plugin.text().parse("  " + line));
        }
        final int hidden = result.rare() + result.unique() - result.highlights().size();
        if (hidden > 0) {
            player.sendMessage(plugin.text().parse("  <dark_gray>……還有 " + hidden + " 件"));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
    }

    /** 背包的格數：快捷列 9 格＋主背包 27 格（不含身上穿的、副手與飾品欄位）。 */
    public static final int BACKPACK_SLOTS = 36;

    /** 鑑定背包（快捷列＋主背包）裡所有未鑑定的物品；身上穿的與飾品欄位不動。 */
    public IdentifyResult identifyAll(final Player player) {
        int magic = 0;
        int rare = 0;
        int unique = 0;
        final List<String> highlights = new ArrayList<>();
        final List<ItemData> identified = new ArrayList<>();

        final PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < BACKPACK_SLOTS; i++) {
            final ItemStack stack = inventory.getItem(i);
            final Optional<ItemData> data = identifyStack(stack);
            if (data.isPresent()) {
                inventory.setItem(i, stack);
                identified.add(data.get());
            }
        }

        for (final ItemData data : identified) {
            switch (data.rarity()) {
                case UNIQUE -> unique++;
                case RARE -> rare++;
                default -> magic++;
            }
            if ((data.rarity() == Rarity.RARE || data.rarity() == Rarity.UNIQUE) && highlights.size() < LIST_LIMIT) {
                highlights.add("<" + data.rarity().color() + ">◆ "
                        + ItemRenderer.escape(ItemStats.displayName(plugin.items().repository(), data))
                        + " <dark_gray>" + data.rarity().displayName() + "・物品等級 " + data.ilvl());
            }
        }
        if (!identified.isEmpty()) {
            plugin.players().markDirty(player);
        }
        return new IdentifyResult(magic, rare, unique, highlights);
    }

    /** 背包（快捷列＋主背包）裡有幾件未鑑定。 */
    public int countUnidentified(final Player player) {
        int count = 0;
        final PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < BACKPACK_SLOTS; i++) {
            if (isUnidentified(inventory.getItem(i))) {
                count++;
            }
        }
        return count;
    }

    private boolean isUnidentified(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return plugin.items().read(stack).map(data -> !data.identified()).orElse(false);
    }

    // ---- 鑑定卷軸 ----

    /** 鑑定卷軸的定義（currencies.yml 裡第一種 IDENTIFY 通貨）。 */
    public Optional<CurrencyType> scrollType() {
        return plugin.currencies().repository().firstWith(CurrencyOperation.IDENTIFY);
    }

    /** 背包裡有幾張鑑定卷軸。 */
    public int countScrolls(final Player player, final CurrencyType scroll) {
        int count = 0;
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Optional<CurrencyType> type = plugin.currencies().read(stack);
            if (type.isPresent() && type.get().id().equals(scroll.id())) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** 免費領鑑定卷軸：補滿到一疊（stack 上限）。 */
    public void claimScrolls(final Player player) {
        final Optional<CurrencyType> scroll = scrollType();
        if (scroll.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>currencies.yml 裡沒有鑑定卷軸（operation: IDENTIFY）"));
            return;
        }
        final int have = countScrolls(player, scroll.get());
        final int want = scroll.get().stack() - have;
        if (want <= 0) {
            player.sendActionBar(plugin.text().parse("<gray>你的" + scroll.get().name() + "已經滿了（" + have + " 張）"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        final Map<Integer, ItemStack> leftover = player.getInventory().addItem(plugin.currencies().create(scroll.get(), want));
        int notGiven = 0;
        for (final ItemStack rest : leftover.values()) {
            notGiven += rest.getAmount();
        }
        final int given = want - notGiven;
        plugin.economy().ledger().produced(scroll.get().id(), tw.exilecore.economy.EconomyLedger.NPC, given);
        if (given <= 0) {
            player.sendActionBar(plugin.text().parse("<red>背包滿了，放不下" + scroll.get().name()));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        player.sendActionBar(plugin.text().parse("<green>領了 " + given + " 張" + scroll.get().name()
                + (notGiven > 0 ? "<gray>（背包滿了，少給 " + notGiven + " 張）" : "")));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
    }

    /** 未鑑定的 ExileCore 裝備就鑑定並重繪；回傳鑑定後的資料（不是或已鑑定回傳空）。 */
    private Optional<ItemData> identifyStack(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || data.get().identified()) {
            return Optional.empty();
        }
        data.get().setIdentified(true);
        plugin.items().update(stack, data.get());
        return data;
    }
}
