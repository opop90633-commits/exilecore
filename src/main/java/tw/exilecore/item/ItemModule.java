package tw.exilecore.item;

import tw.exilecore.ExileCore;
import tw.exilecore.core.DataAudit;
import tw.exilecore.core.DataFiles;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.util.random.RandomGenerator;

/** item 模組：載入基底與詞綴資料、提供 ItemService。 */
public final class ItemModule implements Module {

    private final ExileCore plugin;
    private final ItemRepository repository = new ItemRepository();
    private final RandomGenerator random;
    private ItemService service;

    public ItemModule(final ExileCore plugin, final RandomGenerator random) {
        this.plugin = plugin;
        this.random = random;
    }

    @Override
    public String id() {
        return "item";
    }

    @Override
    public void enable() throws Exception {
        // 資料檔跟著新版更新（item 是第一個讀資料檔的模組，所以在這裡做）
        final DataFiles.Report report = DataFiles.sync(plugin);
        if (report.backup() != null) {
            plugin.getSLF4JLogger().warn("資料檔已全部換成新版預設值；舊的備份在 plugins/ExileCore/{}",
                    plugin.dataFolder().relativize(report.backup()));
        }
        if (!report.added().isEmpty()) {
            plugin.getSLF4JLogger().info("新增 {} 個預設資料檔：{}", report.added().size(), String.join("、", report.added()));
        }
        if (!report.updated().isEmpty()) {
            plugin.getSLF4JLogger().info("更新 {} 個資料檔到新版：{}", report.updated().size(), String.join("、", report.updated()));
        }
        // 你改過的檔案不會自動換新（新版放在旁邊的 .new）；體檢順便列出你的檔案缺了新版的哪些段落：
        // 新功能的設定沒讀到不會報錯、只會照預設值跑，所以要提醒
        int attention = 0;
        for (final DataAudit.FileStatus status : DataAudit.audit(plugin.dataFolder(), DataFiles.indexedPaths(plugin), DataFiles.loader(plugin))) {
            if (status.needsAttention()) {
                attention++;
                plugin.getSLF4JLogger().warn("資料檔 {}", DataAudit.summary(status));
            }
        }
        if (attention > 0) {
            plugin.getSLF4JLogger().warn("{} 個資料檔你改過、新版預設值放在旁邊的 .new：/ec data 看狀態、/ec data diff <檔> 看缺的鍵、/ec data accept <檔> 換成新版（舊的留 .bak）",
                    attention);
        }
        final Path data = plugin.dataFolder().resolve("data");
        repository.load(data);
        final boolean resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
        service = new ItemService(repository, new ItemGenerator(repository), new ItemCodec(plugin),
                new ItemRenderer(repository, resourcePack), random);
        // 退役的傳奇（2026-10 PoE2 改版）換成傳奇兌換券；右鍵兌換券選一件新的傳奇
        service.setVoucherFactory(() -> {
            final tw.exilecore.currency.CurrencyService currencies = plugin.currencies();
            final tw.exilecore.currency.CurrencyType type = currencies == null ? null : currencies.repository().get(ItemService.VOUCHER_ID);
            return type == null ? null : currencies.create(type, 1);
        });
        // 我們的物品不能拿去做原版的合成、鐵砧、砂輪、釀造、熔爐（做出來的東西會沒有資料）
        plugin.getServer().getPluginManager().registerEvents(new CustomItemGuard(plugin), plugin);
        // 裝備比較：背包裡的裝備說明最後面寫「比身上的 ×」
        final ItemComparer comparer = new ItemComparer(plugin, service);
        service.setComparer(comparer);
        plugin.getServer().getPluginManager().registerEvents(comparer, plugin);
        plugin.getSLF4JLogger().info("物品資料：{} 個基底、{} 條詞綴、{} 件傳奇", repository.baseCount(), repository.affixCount(),
                repository.uniques().size());
        for (final String warning : repository.warnings()) {
            plugin.getSLF4JLogger().warn(warning);
        }
    }

    @Override
    public void reload() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
        plugin.getSLF4JLogger().info("物品資料重載：{} 個基底、{} 條詞綴", repository.baseCount(), repository.affixCount());
        for (final String warning : repository.warnings()) {
            plugin.getSLF4JLogger().warn(warning);
        }
    }

    @Override
    public void disable() {
    }

    public ItemService service() {
        return service;
    }
}
