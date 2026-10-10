package tw.exilecore.currency;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** currency 模組：載入通貨定義、拖曳使用。 */
public final class CurrencyModule implements Module {

    private final ExileCore plugin;
    private final CurrencyRepository repository = new CurrencyRepository();
    private CurrencyService service;

    public CurrencyModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "currency";
    }

    @Override
    public void enable() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
        final boolean resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
        service = new CurrencyService(plugin, plugin.getSLF4JLogger(), repository, plugin.items(), resourcePack);
        service.setUsageLog((characterId, playerName, itemUuid, currencyId, before, after) -> {
            // 每用掉一顆（僥倖石碎掉也算）記一筆：經濟報表的「做裝備、地圖」消耗
            plugin.economy().ledger().consumed(currencyId, tw.exilecore.economy.EconomyLedger.CRAFT, 1);
            plugin.database().async(connection -> {
                plugin.characters().repository().logCurrency(connection, characterId, playerName, itemUuid, currencyId, before, after);
                return null;
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getSLF4JLogger().warn("通貨紀錄寫入失敗：{}", error.getMessage());
                }
            });
        });
        plugin.getServer().getPluginManager().registerEvents(new CurrencyListener(plugin, service), plugin);
        plugin.items().renderer().setOmens(repository::get); // 裝備說明上的「星籤：×」要知道名稱
        plugin.getSLF4JLogger().info("通貨：{} 種", repository.size());
        warnOverfull();
    }

    @Override
    public void reload() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
        warnOverfull();
    }

    /** 同一組的通貨太多，通貨袋、倉庫的通貨頁放不下：主控台說一聲。 */
    private void warnOverfull() {
        for (final String problem : CurrencyGroups.overfull(repository.all())) {
            plugin.getSLF4JLogger().warn("currencies.yml：{}，多的在通貨袋、倉庫的通貨頁看不到", problem);
        }
    }

    @Override
    public void disable() {
    }

    public CurrencyService service() {
        return service;
    }
}
