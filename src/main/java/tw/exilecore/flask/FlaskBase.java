package tw.exilecore.flask;

import tw.exilecore.core.config.YamlNode;


/**
 * 藥劑基底（data/flasks.yml 的 bases）。純資料，不依賴 Bukkit。
 *
 * @param level    需求等級（也是從幾級的怪物開始掉）
 * @param recovery 一次回復多少（在 duration 秒內平均回完）
 * @param duration 回復時間（秒）
 * @param charges  充能上限
 * @param perUse   每喝一次用掉幾點充能
 */
public record FlaskBase(String id, String name, FlaskKind kind, String model, int level, double recovery, double duration,
                        int charges, int perUse) {

    /** 喝一次最多幾次（充能滿的時候）。 */
    public int uses() {
        return perUse <= 0 ? 0 : charges / perUse;
    }

    static FlaskBase parse(final String id, final YamlNode node) {
        final String where = node.path();
        final FlaskKind kind = FlaskKind.parse(where + ".kind", node.requireString("kind"));
        final int level = node.integer("level", 1);
        final double recovery = node.decimal("recovery", 0);
        final double duration = node.decimal("duration", 3);
        final int charges = node.integer("charges", 60);
        final int perUse = node.integer("per_use", 10);
        if (level < 1 || recovery <= 0 || duration < 0.1 || duration > 30 || charges < 1 || perUse < 1 || perUse > charges) {
            throw new IllegalArgumentException(where + "：level ≥ 1、recovery > 0、duration 0.1 到 30 秒、1 ≤ per_use ≤ charges");
        }
        return new FlaskBase(id, node.requireString("name"), kind, node.string("model", null), level, recovery, duration,
                charges, perUse);
    }
}
