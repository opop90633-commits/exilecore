package tw.exilecore.core;

import tw.exilecore.ExileCore;
import tw.exilecore.core.db.Database;
import tw.exilecore.module.Module;

/**
 * core 模組：設定、資料庫連線池、排程等共用基礎。
 * 階段 0 只管資料庫；之後的模組都排在它後面註冊。
 */
public final class CoreModule implements Module {

    private final ExileCore plugin;
    private final Database database;

    public CoreModule(final ExileCore plugin, final Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public String id() {
        return "core";
    }

    private tw.exilecore.core.config.Settings.Storage connected;

    @Override
    public void enable() throws Exception {
        database.connect(plugin.settings().storage());
        connected = plugin.settings().storage();
        // 2026-10-17：排著的帳號轉移（ec transfer）在其他模組讀資料之前做
        tw.exilecore.ops.AccountTransfer.applyPending(plugin, database);
    }

    @Override
    public void reload() {
        // 重載時不重新連線：記憶體裡的角色、倉庫、市集都是這個資料庫的，中途換資料庫會對不上（還在排隊的存檔也可能亂掉）
        if (!plugin.settings().storage().equals(connected)) {
            plugin.getSLF4JLogger().warn("config.yml 的 storage 改了：要重新啟動伺服器才會換資料庫（/ec reload 不會換）");
        }
    }

    @Override
    public void disable() {
        database.close();
    }
}
