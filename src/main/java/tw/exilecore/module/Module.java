package tw.exilecore.module;

/**
 * ExileCore 的一個功能模組（stats、item、currency …）。
 * <p>
 * 模組之間不直接呼叫彼此的內部類別，只透過服務介面與自訂事件溝通；
 * {@link ModuleRegistry} 依註冊順序啟用、反序停用。
 */
public interface Module {

    /** 模組代號，小寫英文，例如 {@code core}、{@code stats}。 */
    String id();

    /**
     * 啟用模組。丟出例外代表這個模組啟用失敗，
     * 註冊表會記錄錯誤並繼續啟用其他模組。
     */
    void enable() throws Exception;

    /** 停用模組；伺服器關閉或插件重載時呼叫，不應丟出例外。 */
    void disable();

    /** 設定檔重載後呼叫；預設什麼都不做。 */
    default void reload() throws Exception {
    }
}
