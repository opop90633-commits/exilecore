package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 星幣券（2026-10）：把星幣換成一張可以交易、上架的物品，拿到的人右鍵就換回星幣。
 * <p>
 * 防複製：每張券一個代號，開券時先寫進資料表 star_vouchers 才給物品；兌換時用
 * {@code UPDATE … WHERE id = ? AND redeemed_at IS NULL} 標記用掉——同一個代號只有第一張換得到，
 * 複製出來的券、已經用過的券都換不了（拿走、記在主控台）。開券、兌換都會記帳號的星幣紀錄。
 */
final class StarVouchers implements Listener {

    /** 一張最少、最多幾星幣。 */
    static final int MIN = 10;
    static final int MAX = 100_000;

    private final ExileCore plugin;
    private final SponsorService service;
    private final NamespacedKey idKey;
    private final NamespacedKey amountKey;
    /** 正在處理的玩家（資料庫還沒回來前不能再點）。 */
    private final Set<UUID> busy = new HashSet<>();

    StarVouchers(final ExileCore plugin, final SponsorService service) {
        this.plugin = plugin;
        this.service = service;
        this.idKey = new NamespacedKey(plugin, "star_voucher");
        this.amountKey = new NamespacedKey(plugin, "star_voucher_amount");
    }

    /** 這張是星幣券嗎（回傳代號；不是 null）。 */
    String voucherId(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    private long voucherAmount(final ItemStack stack) {
        final Long amount = stack.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.LONG);
        return amount == null ? 0 : amount;
    }

    /** 星幣券的圖（currency_art.txt）。 */
    public static final String VOUCHER_ART = "star_voucher";

    /** 套上星幣券的專屬圖示（currency_art.txt 的 star_voucher；沒套資源包的看到原本的紙）；選單的按鈕也用。 */
    static ItemStack withArt(final ItemStack stack) {
        stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                        .addString(tw.exilecore.currency.CurrencyArt.modelKey(VOUCHER_ART)).build());
        return stack;
    }

    private ItemStack create(final String id, final long amount, final String issuer) {
        final ItemStack stack = withArt(ItemStack.of(Material.PAPER));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#f5b942>星幣券　" + SponsorService.format(amount) + " 星幣"));
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ItemRenderer.line("<gray>右鍵：換回 " + SponsorService.format(amount) + " 星幣（存進帳號）"));
            lore.add(ItemRenderer.line("<gray>可以交易、上架；每張只能兌換一次"));
            lore.add(ItemRenderer.line("<dark_gray>開券：" + ItemRenderer.escape(issuer)));
            lore.add(ItemRenderer.line("<dark_gray>券號：" + id.substring(0, 8)));
            meta.lore(lore);
            meta.setMaxStackSize(1);
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
            meta.getPersistentDataContainer().set(amountKey, PersistentDataType.LONG, amount);
        });
        return stack;
    }

    // ---- 開券 ----

    /** 把 amount 星幣換成一張券；成功 null（券在資料庫寫好之後才給）。 */
    String issue(final Player player, final long amount) {
        if (amount < MIN || amount > MAX) {
            return "一張要 " + MIN + " 到 " + SponsorService.format(MAX) + " 星幣";
        }
        if (busy.contains(player.getUniqueId())) {
            return "上一張還在處理中，請稍等";
        }
        if (!plugin.economy().wallet().fits(player, ItemStack.of(Material.PAPER))) {
            return "背包和隨身背包都滿了";
        }
        final String problem = service.spendCoins(player, amount, "開星幣券");
        if (problem != null) {
            return problem;
        }
        final String id = UUID.randomUUID().toString();
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        busy.add(uuid);
        plugin.database().async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO star_vouchers (id, amount, issuer_uuid, issuer_name, created_at, delivered, credited) VALUES (?, ?, ?, ?, ?, 0, 1)")) {
                statement.setString(1, id);
                statement.setLong(2, amount);
                statement.setString(3, uuid.toString());
                statement.setString(4, name);
                statement.setTimestamp(5, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return null;
        }).whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            busy.remove(uuid);
            final Player online = plugin.getServer().getPlayer(uuid);
            if (error != null) {
                plugin.getSLF4JLogger().error("星幣券寫不進資料庫（{}，{} 星幣），退回星幣", name, amount, error);
                if (online != null) {
                    service.addCoins(online, amount, "星幣券開券失敗退回");
                    online.sendMessage(plugin.text().prefixed("<red>星幣券開不出來（資料庫錯誤），星幣已經退回"));
                }
                return;
            }
            plugin.getSLF4JLogger().info("[贊助] {} 開了星幣券 {}（{} 星幣）", name, id, amount);
            // 交給本人：在線就馬上給；下線了等下次上線（onReady 補給）
            if (online != null) {
                claimDeliveries(online);
            }
        }));
        return null;
    }

    // ---- 兌換 ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Player player = event.getPlayer();
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final String id = voucherId(hand);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        redeem(player, hand, id);
    }

    private void redeem(final Player player, final ItemStack hand, final String id) {
        if (!service.loaded(player)) {
            player.sendMessage(plugin.text().prefixed("<red>帳號資料載入中，請稍等"));
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            return;
        }
        final long amount = voucherAmount(hand);
        // 先把券從手上拿走（處理中不能拿去交易、再點一次）；資料庫出錯再還回去
        final int slot = player.getInventory().getHeldItemSlot();
        final ItemStack taken = hand.clone();
        player.getInventory().setItem(slot, null);
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        plugin.database().async(connection -> {
            final Map<String, Object> result = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE star_vouchers SET redeemed_uuid = ?, redeemed_name = ?, redeemed_at = ?, credited = 0 WHERE id = ? AND redeemed_at IS NULL")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.setString(4, id);
                result.put("updated", statement.executeUpdate());
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT amount FROM star_vouchers WHERE id = ?")) {
                statement.setString(1, id);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    result.put("amount", rows.next() ? rows.getLong(1) : -1L);
                }
            }
            return result;
        }).whenComplete((result, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            busy.remove(uuid);
            final Player online = plugin.getServer().getPlayer(uuid);
            if (error != null) {
                plugin.getSLF4JLogger().error("星幣券 {} 兌換時資料庫錯誤（{}）", id, name, error);
                if (online != null) {
                    plugin.economy().wallet().dropFor(online, plugin.economy().wallet().give(online, taken));
                    online.sendMessage(plugin.text().prefixed("<red>兌換失敗（資料庫錯誤），券還給你了，等一下再試"));
                }
                return;
            }
            final int updated = ((Number) result.get("updated")).intValue();
            final long real = ((Number) result.get("amount")).longValue();
            if (updated != 1 || real <= 0) {
                plugin.getSLF4JLogger().warn("[贊助] {} 想兌換無效的星幣券 {}（{}；券上寫 {} 星幣）——已經用過或是複製的，收走",
                        name, id, real < 0 ? "沒有這張" : "已經兌換過", amount);
                if (online != null) {
                    online.sendMessage(plugin.text().prefixed("<red>這張星幣券已經兌換過了（或是無效的券），收走了"));
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
                }
                return;
            }
            plugin.getSLF4JLogger().info("[贊助] {} 兌換星幣券 {}（{} 星幣）", name, id, real);
            if (online != null) {
                claimCredits(online); // 星幣照資料表加（下線了等下次上線補）
            }
        }));
    }

    // ---- 下線也有效：上線時補給券、補加星幣 ----

    /** 帳號載入好（登入、換角色）：還沒交到手上的券、兌換了還沒加的星幣，補上。 */
    void onReady(final Player player) {
        claimDeliveries(player);
        claimCredits(player);
        // 2026-10：之前開的券補上專屬圖示（背包裡的）
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (voucherId(stack) != null && !stack.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)) {
                withArt(stack);
            }
        }
    }

    /** 開了但還沒交到手上的券：先在資料表標記交了（只有一次），再給物品；這時下線了就改回來，下次再給。 */
    private void claimDeliveries(final Player player) {
        final UUID uuid = player.getUniqueId();
        plugin.database().async(connection -> {
            final List<Object[]> claimed = new ArrayList<>();
            final List<Object[]> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, amount, issuer_name FROM star_vouchers WHERE issuer_uuid = ? AND delivered = 0 AND redeemed_at IS NULL")) {
                statement.setString(1, uuid.toString());
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        pending.add(new Object[] {rows.getString(1), rows.getLong(2), rows.getString(3)});
                    }
                }
            }
            for (final Object[] row : pending) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE star_vouchers SET delivered = 1 WHERE id = ? AND delivered = 0")) {
                    statement.setString(1, (String) row[0]);
                    if (statement.executeUpdate() == 1) {
                        claimed.add(row);
                    }
                }
            }
            return claimed;
        }).whenComplete((claimed, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("星幣券：補給券失敗", error);
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            final Player online = plugin.getServer().getPlayer(uuid);
            if (online == null) {
                revert(claimed, "delivered");
                return;
            }
            for (final Object[] row : claimed) {
                final ItemStack voucher = create((String) row[0], (Long) row[1], (String) row[2]);
                plugin.economy().wallet().dropFor(online, plugin.economy().wallet().give(online, voucher));
                online.sendMessage(plugin.text().prefixed("<green>拿到一張 " + SponsorService.format((Long) row[1])
                        + " 星幣的星幣券<gray>（可以交易、上架；右鍵換回星幣）"));
            }
            online.playSound(online.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
            plugin.characters().save(online);
        }));
    }

    /** 兌換了、星幣還沒加的：先標記加了（只有一次），再加星幣；這時下線了就改回來。 */
    private void claimCredits(final Player player) {
        final UUID uuid = player.getUniqueId();
        plugin.database().async(connection -> {
            final List<Object[]> claimed = new ArrayList<>();
            final List<Object[]> pending = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, amount FROM star_vouchers WHERE redeemed_uuid = ? AND credited = 0")) {
                statement.setString(1, uuid.toString());
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        pending.add(new Object[] {rows.getString(1), rows.getLong(2)});
                    }
                }
            }
            for (final Object[] row : pending) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE star_vouchers SET credited = 1 WHERE id = ? AND credited = 0")) {
                    statement.setString(1, (String) row[0]);
                    if (statement.executeUpdate() == 1) {
                        claimed.add(row);
                    }
                }
            }
            return claimed;
        }).whenComplete((claimed, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("星幣券：補加星幣失敗", error);
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            final Player online = plugin.getServer().getPlayer(uuid);
            if (online == null || !service.loaded(online)) {
                revert(claimed, "credited");
                return;
            }
            long total = 0;
            for (final Object[] row : claimed) {
                total += (Long) row[1];
            }
            service.addCoins(online, total, "兌換星幣券 " + claimed.size() + " 張");
            online.sendMessage(plugin.text().prefixed("<green>星幣券兌換成功：+" + SponsorService.format(total) + " 星幣<gray>（現在 "
                    + SponsorService.format(service.coins(online)) + "）"));
            online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            plugin.characters().save(online);
        }));
    }

    /** 標記了但人已經下線：改回 0，下次上線再補。 */
    private void revert(final List<Object[]> claimed, final String column) {
        plugin.database().async(connection -> {
            for (final Object[] row : claimed) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE star_vouchers SET " + column + " = 0 WHERE id = ?")) {
                    statement.setString(1, (String) row[0]);
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    /** 給選單說明用。 */
    static String rules() {
        return String.format(Locale.ROOT, "一張 %d–%s 星幣", MIN, SponsorService.format(MAX));
    }
}
