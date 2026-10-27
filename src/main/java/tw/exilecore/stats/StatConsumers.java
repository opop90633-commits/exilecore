package tw.exilecore.stats;

import java.util.Map;

/**
 * 2026-10-27：天賦樹（passives/tree.json）與昇華（ascendancy.yml）用到的屬性鍵「有沒有程式在讀」的登記表
 * （站長：「天賦點好像還有蠻多沒有生效的東西」——說明沒標「尚未生效」、卻沒有任何程式讀那個鍵，等於假生效）。
 * <p>
 * 測試 {@code StatConsumersTest} 掃 src/main/java：每個鍵要有人讀——字串直接寫在程式裡（宣告常數的那一行不算）、
 * 常數在宣告以外的地方用到、或是由某個前綴組出來（{@code StatKeys.damageExtra(type)} 這種；前綴的常數或字串有人用）。
 * 只出現在說明文字的地方（{@code *Text.java}、StatLines、SpecialEffectLines）不算讀。
 * 讀不到的鍵要嘛接上消費端，要嘛寫進下面的表（寫清楚為什麼不用讀），不然測試失敗。
 * 測試也看種類對不對：鍵只被 {@code flat(...)} 讀、樹上卻寫 INCREASED 這種（2026-10-27 抓到「對低血敵人的擊中傷害」12 個節點沒效果）。
 */
public final class StatConsumers {

    private StatConsumers() {
    }

    /**
     * 本來就成立、不用程式讀的效果（照原作寫在樹上；我們的遊戲裡這件事本來就是這樣）→ 原因。
     * 只有在「效果真的已經有了」的時候才能寫進來。
     */
    public static final Map<String, String> INHERENT = Map.of(
            // 2026-10-27：「被格擋的擊中擊退更少」換成最近格擋過時增加暈眩門檻（真的有作用的效果），從這裡拿掉
            TreeLastKeys.FLAG_OFFERING_UNDAMAGEABLE, "祭品（祭柱）是不能被打的展示方塊（Warcries.raiseSpike），一直不會受到傷害");

    /**
     * 只有一段的前綴（太籠統，測試不會自己認）組出來的鍵 → 讀的地方。測試會確認前綴的常數或字串真的有人用。
     * 兩段以上的前綴（damage.extra.、flag.immune.、resist.max.）測試自己認得。
     */
    public static final Map<String, String> PREFIXES = Map.of(
            "grant_skill.", "AscBuild.grantedSkills、UniqueDerived：賦予技能（後面接技能代號）",
            "supgem.", "AttributeScalingKeys.SUPPORT_GATE：插了幾顆某色輔助寶石才生效（後面接條件與真正的屬性鍵）",
            "surge.", "SkillResources.SURGE_PREFIX：消耗戰星時的元素湧動層數（後面接元素）");

    /** 不是照屬性鍵讀，而是照配了哪個節點讀的 → 讀的地方。 */
    public static final Map<String, String> BY_NODE = Map.of(
            "asc.travellers_wisdom", "PlayerStateService、PassiveMenu 看有沒有配 travellers_wisdom 節點（多一點天賦點）");

    /**
     * 種類不照一般規則讀的（例如 FLAT 的值換算成別的東西、或是整張表直接加總）→ 讀的地方。
     * 測試看種類時跳過這些鍵。
     */
    public static final Map<String, String> ANY_TYPE = Map.of();
}
