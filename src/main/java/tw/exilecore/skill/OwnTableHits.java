package tw.exilecore.skill;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2：技能另外一段有「自己的基礎傷害表」（PoB2 的另一組 statSet，傷害種類跟主段不一樣），
 * 例如滾雷珠用掉火灌注時的火焰爆炸、星火連珠用掉電灌注時的閃電光束。這一段的基礎傷害、暴擊率、自己的修飾器照存數值的隱藏技能，
 * 輔助寶石、天賦這些「這個技能的加成」照原技能那一下（原技能自己寫在 skills.yml 的修飾器、逐級修飾器不帶過來，
 * 那些是主段的數值）。
 * <p>
 * 跟 {@link SkillStages}（同一張表乘倍數）不同：那個只能放大主段的傷害，換不了傷害種類。
 */
final class OwnTableHits {

    private OwnTableHits() {
    }

    /**
     * 純邏輯：own（隱藏技能自己的那一下）疊上 parent（原技能這一下）的加成。parent 裡來源是原技能名稱的修飾器（skills.yml 自己寫的、
     * 逐級的）、stat 在 drop 裡的（灌注的記號）不帶；parent 比原技能定義多出來的標籤（圖騰、陷阱…）帶過來。
     */
    static SkillProfile compose(final SkillProfile own, final SkillProfile parent, final String parentName, final Set<String> parentTags,
                                final Set<String> drop) {
        final List<Modifier> local = new ArrayList<>(own.local());
        for (final Modifier modifier : parent.local()) {
            if ((parentName != null && parentName.equals(modifier.source())) || drop.contains(modifier.stat())) {
                continue;
            }
            local.add(modifier);
        }
        final Set<String> tags = new HashSet<>(own.tags());
        for (final String tag : parent.tags()) {
            if (parentTags == null || !parentTags.contains(tag)) {
                tags.add(tag);
            }
        }
        return new SkillProfile(own.id(), tags, own.effectiveness(), own.spellDamage(), own.spellDamageMax(), own.conversion(), local);
    }

    /**
     * hiddenId 這個隱藏技能在 parent 這一級的那一下（{@link #compose}）；parentProfile 是原技能實際打出去的那一下（含這一發才有的加成）。
     * 找不到隱藏技能回傳 null。
     */
    static SkillProfile profile(final ExileCore plugin, final Player owner, final SkillBuild parent, final SkillProfile parentProfile,
                                final String hiddenId, final Set<String> drop) {
        if (plugin == null || owner == null || parent == null || plugin.skills() == null || plugin.players() == null) {
            return null;
        }
        final SkillDefinition hidden = plugin.skills().config().skill(hiddenId);
        if (hidden == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(owner);
        final SkillBuild data = SkillBuild.of(plugin.skills().config(), hidden, Math.max(1, parent.level()), state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(owner));
        return compose(data.profile(), parentProfile == null ? parent.profile() : parentProfile, parent.definition().name(),
                parent.definition().tags(), drop);
    }
}
