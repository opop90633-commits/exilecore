package tw.exilecore.stats;

/**
 * 修飾器的三種型態，結算順序固定：
 * <pre>(基礎 + Σ FLAT) × (1 + Σ INCREASED) × Π (1 + MORE)</pre>
 */
public enum ModType {
    /** 附加固定值（+45 最大生命、附加 5 到 12 火焰傷害）。 */
    FLAT,
    /** 增加／減少：同屬性的全部相加後只乘一次（增加 20% 物理傷害）。 */
    INCREASED,
    /** 更多／更少：每一個各自相乘（輔助寶石的 39% 更多）。 */
    MORE
}
