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

    @Override
    public void enable() throws Exception {
        database.connect(plugin.settings().storage());
    }

    @Override
    public void reload() throws Exception {
        // 設定可能改了資料庫種類或位址，重載時重新連線
        database.connect(plugin.settings().storage());
    }

    @Override
    public void disable() {
        database.close();
    }
}
