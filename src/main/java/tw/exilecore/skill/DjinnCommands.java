package tw.exilecore.skill;

/**
 * 2026-10-29 照 PoE2（poe2db Minion Commands：「You activate a Command skill, and one of your Minions carries out the Command」）：
 * 巨靈（克拉里、納維拉、魯兹安）的指令是巨靈放的，傷害照巨靈（召喚物）的數值算——召喚物傷害、召喚物暴擊這些吃得到，你自己的法術傷害、
 * 法術暴擊吃不到。做法：指令（和它帶出來的分身爆炸、熔岩浪、甲蟲…）打中時，SkillDamage 改用那隻巨靈當攻擊者，
 * CombatListener 用巨靈的屬性表配上指令自己的技能描述結算（傷害仍算你的：掉落、經驗、傷害數字）。
 * 施法時間、範圍、冷卻、魔力消耗照舊是你放指令的那一下（你按的指令）。巨靈沒叫出來（或不在同一個世界）就照舊算你的。
 * 這次之前整個指令都當成你自己放的法術。
 */
public final class DjinnCommands {

    private DjinnCommands() {
    }

    /** 這一下（技能描述代號）是哪一位巨靈放的；不是巨靈的指令回傳 null。 */
    public static String djinnFor(final String profileId) {
        if (profileId == null) {
            return null;
        }
        final int dot = profileId.indexOf('.');
        String id = dot < 0 ? profileId : profileId.substring(0, dot);
        if ("asc_corpse_beetle".equals(id)) {
            return "kelari_djinn"; // 克拉里的惡言生出來的屍甲蟲
        }
        if (id.startsWith("asc_")) {
            id = id.substring(4);
        }
        if (id.endsWith("_djinn")) {
            return null; // 巨靈本身
        }
        if (id.startsWith("kelari_")) {
            return "kelari_djinn";
        }
        if (id.startsWith("navira_")) {
            return "navira_djinn";
        }
        if (id.startsWith("ruzhan_")) {
            return "ruzhan_djinn";
        }
        return null;
    }
}
