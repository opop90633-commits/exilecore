package tw.exilecore.module;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模組註冊表：記住註冊順序，依序啟用、反序停用，並記錄哪些模組真的啟用成功。
 * <p>
 * 這個類別刻意不依賴任何 Bukkit／Paper 類別，所以可以直接寫單元測試。
 */
public final class ModuleRegistry {

    private final Logger log;
    private final List<Module> registered = new ArrayList<>();
    private final Set<String> enabled = new LinkedHashSet<>();

    public ModuleRegistry(final Logger log) {
        this.log = log;
    }

    /** 註冊一個模組。同一個代號註冊兩次會直接丟出例外，避免設定錯誤被吞掉。 */
    public ModuleRegistry register(final Module module) {
        for (final Module existing : registered) {
            if (existing.id().equals(module.id())) {
                throw new IllegalArgumentException("模組代號重複：" + module.id());
            }
        }
        registered.add(module);
        return this;
    }

    /** 依註冊順序啟用所有模組；失敗的模組會記錄錯誤並跳過，不影響其他模組。 */
    public void enableAll() {
        for (final Module module : registered) {
            final long start = System.nanoTime();
            try {
                module.enable();
                enabled.add(module.id());
                log.info("模組 {} 已啟用（{} 毫秒）", module.id(), (System.nanoTime() - start) / 1_000_000);
            } catch (final Exception e) {
                log.error("模組 {} 啟用失敗，已跳過", module.id(), e);
            }
        }
    }

    /** 依註冊順序的反序停用所有已啟用的模組。 */
    public void disableAll() {
        final List<Module> reversed = new ArrayList<>(registered);
        Collections.reverse(reversed);
        for (final Module module : reversed) {
            if (!enabled.contains(module.id())) {
                continue;
            }
            try {
                module.disable();
            } catch (final RuntimeException e) {
                log.error("模組 {} 停用時發生錯誤", module.id(), e);
            }
            enabled.remove(module.id());
        }
    }

    /** 通知所有已啟用的模組設定檔重載了。 */
    public void reloadAll() {
        for (final Module module : registered) {
            if (!enabled.contains(module.id())) {
                continue;
            }
            try {
                module.reload();
            } catch (final Exception e) {
                log.error("模組 {} 重載失敗", module.id(), e);
            }
        }
    }

    /** 已啟用的模組代號，依啟用順序。 */
    public List<String> enabledIds() {
        return List.copyOf(enabled);
    }

    /** 已註冊的模組數量。 */
    public int size() {
        return registered.size();
    }
}
