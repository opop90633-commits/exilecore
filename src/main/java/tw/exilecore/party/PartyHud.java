package tw.exilecore.party;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.character.PlayerState;
import tw.exilecore.web.HudFont;
import tw.exilecore.web.PartyFont;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 畫面左邊的隊伍框（2026-10-18，像 PoE）：每個隊友一格——頭像、名字（隊長前面金色的 ★）、等級，
 * 血條（護盾是血條上面薄薄的一層）、魔力條；死了名字變紅、離線變灰、範圍外（2026-10-26：不分經驗、掉落）名字變灰、寫「遠」。
 * 自己不列（自己的看動作列）。
 * <p>
 * 接在動作列最後面（淨寬 0，不會把動作列推歪），由資源包的文字著色器搬到畫面左上角——原理見 {@link PartyFont}。
 * 沒套資源包的人不畫；選單的「設定」可以關（{@link PlayerSettings#PARTY_FRAMES}）。
 */
public final class PartyHud {

    /** 最多畫幾個隊友（著色器的隊員編號只有 4 位；隊伍上限 12 人 → 隊友 11 個）。 */
    public static final int MAX_SHOWN = 11;

    private static final Key HUD_FONT = Key.key(HudFont.FONT);
    private static final Key PARTY_FONT = Key.key(PartyFont.FONT);

    /** 框裡的位置（格）：頭像 2..20；名字從 24 開始；血條、魔力條 23..107（底框 22..108）。 */
    private static final double TEXT_X = 24;
    private static final double BAR_X = 23;
    private static final double BAR_W = 84;
    private static final double RIGHT = BAR_X + BAR_W;

    private final ExileCore plugin;
    /** 皮膚（textures 屬性）：上線時記下來，離線的隊友也畫得出頭像。 */
    private final Map<UUID, PlayerHeadObjectContents.ProfileProperty> skins = new HashMap<>();

    public PartyHud(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** viewer 的隊伍框（接在動作列後面）；沒有隊伍、關掉了、資源包還沒好回傳 null。 */
    public Component render(final Player viewer, final PlayerState viewerState) {
        final PartyService service = plugin.party();
        if (service == null || !HudFont.ready() || !PlayerSettings.enabled(viewerState, PlayerSettings.PARTY_FRAMES)) {
            return null;
        }
        final PartyRoster.Party party = service.party(viewer.getUniqueId());
        if (party == null || party.size() < 2) {
            return null;
        }
        final Frames out = new Frames();
        int index = 0;
        for (final UUID member : party.members()) {
            if (member.equals(viewer.getUniqueId())) {
                continue;
            }
            if (index >= MAX_SHOWN) {
                break;
            }
            frame(out, index++, member, member.equals(party.leader()), service, viewer);
        }
        return index == 0 ? null : out.build();
    }

    private void frame(final Frames out, final int slot, final UUID id, final boolean leader, final PartyService service, final Player viewer) {
        final Player player = plugin.getServer().getPlayer(id);
        final PlayerState state = player == null || plugin.players() == null ? null : plugin.players().all().get(id);
        final boolean offline = player == null || !player.isOnline() || state == null;
        final boolean dead = !offline && (player.isDead() || state.isDead());
        // 2026-10-26：範圍外（不在 nearby_radius 內、在城鎮、不同世界…）的隊友不分經驗、掉落：名字變灰、右邊寫「遠」
        final boolean far = !offline && !dead
                && !(service.nearby(player, viewer.getLocation()) && service.nearby(viewer, player.getLocation()));

        // 頭像
        out.head(slot, id, offline ? PartyFont.HEAD_DIM : PartyFont.HEAD, skin(id, player));

        // 底框，再疊血條、護盾、魔力
        out.bar(slot, PartyFont.FRAME, BAR_X - 1, BAR_W + 2);
        if (offline) {
            out.bar(slot, PartyFont.LIFE_BG, BAR_X, BAR_W);
            out.bar(slot, PartyFont.MANA_BG, BAR_X, BAR_W);
        } else {
            final double life = fill(state.life(), state.maxLife());
            out.bar(slot, PartyFont.LIFE, BAR_X, life);
            out.bar(slot, PartyFont.LIFE_BG, BAR_X + life, BAR_W - life);
            if (state.maxEnergyShield() > 0) {
                out.bar(slot, PartyFont.ES, BAR_X, fill(state.energyShield(), state.maxEnergyShield()));
            }
            final double mana = fill(state.mana(), state.maxMana());
            out.bar(slot, PartyFont.MANA, BAR_X, mana);
            out.bar(slot, PartyFont.MANA_BG, BAR_X + mana, BAR_W - mana);
        }

        // 右邊：等級（死了寫陣亡、離線寫離線）
        final String tag;
        final int tagKind;
        if (offline) {
            tag = "離線";
            tagKind = PartyFont.TEXT_DIM;
        } else if (dead) {
            tag = "陣亡";
            tagKind = PartyFont.TEXT_RED;
        } else if (far) {
            tag = "遠" + (state.hasCharacter() ? " Lv" + state.level() : "");
            tagKind = PartyFont.TEXT_DIM;
        } else {
            tag = state.hasCharacter() ? "Lv" + state.level() : "";
            tagKind = PartyFont.TEXT_GRAY;
        }
        final double tagWidth = Frames.width(tag);
        out.text(slot, tagKind, RIGHT - tagWidth, tag);

        // 左邊：★ 名字（太長截掉）
        double x = TEXT_X;
        if (leader) {
            x += out.text(slot, PartyFont.TEXT_GOLD, x, "★") + 2;
        }
        final double room = RIGHT - tagWidth - 4 - x;
        final String name = fit(service.name(id), room);
        out.text(slot, offline || far ? PartyFont.TEXT_DIM : dead ? PartyFont.TEXT_RED : PartyFont.TEXT, x, name);
    }

    /** 0..BAR_W，以半格為單位。 */
    private static double fill(final double value, final double max) {
        if (max <= 0 || value <= 0) {
            return 0;
        }
        return Math.round(Math.min(1.0, value / max) * BAR_W * 2) / 2.0;
    }

    /** 名字放不下就截掉、補「..」。 */
    private static String fit(final String name, final double room) {
        if (Frames.width(name) <= room) {
            return name;
        }
        final double dots = Frames.width("..");
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            final int cp = name.codePointAt(i);
            final String next = out + new String(Character.toChars(cp));
            if (Frames.width(next) + dots > room) {
                break;
            }
            out.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return out + "..";
    }

    /** 皮膚：在線的從玩家資料拿（換皮膚也跟著換），離線的用記下來的。 */
    private PlayerHeadObjectContents.ProfileProperty skin(final UUID id, final Player player) {
        if (player != null && player.isOnline()) {
            for (final com.destroystokyo.paper.profile.ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    final PlayerHeadObjectContents.ProfileProperty skin = property.getSignature() == null
                            ? PlayerHeadObjectContents.property(property.getName(), property.getValue())
                            : PlayerHeadObjectContents.property(property.getName(), property.getValue(), property.getSignature());
                    skins.put(id, skin);
                    return skin;
                }
            }
        }
        return skins.get(id);
    }

    /**
     * 組字：每個字一個子元件，顏色＝座標（見 {@link PartyFont}）。最前面推到畫面右邊很遠，最後推回來（淨寬 0）。
     */
    private static final class Frames {
        private final TextComponent.Builder root = Component.text().shadowColor(ShadowColor.none());
        /** 這段在原版裡總共往右走了多少（推回來用）。 */
        private double advance;

        Frames() {
            root.append(shift(PartyFont.PRE_SHIFT));
        }

        /** 原版字型的寬度（含字距；空白 4）。 */
        static double width(final String text) {
            return HudFont.estimateVanilla(text);
        }

        private static Component shift(final int px) {
            return Component.text(HudFont.shift(px)).font(HUD_FONT);
        }

        /** 顏色：R = x（半格）、G = 寬（半格）、B = 隊員編號 << 4 | 種類。 */
        private static TextColor code(final int slot, final int kind, final double x, final double w) {
            final int r = (int) Math.max(0, Math.min(255, Math.round(x * 2)));
            final int g = (int) Math.max(0, Math.min(255, Math.round(w * 2)));
            return TextColor.color(r, g, (slot & 15) << 4 | (kind & 15));
        }

        void head(final int slot, final UUID id, final int kind, final PlayerHeadObjectContents.ProfileProperty skin) {
            final PlayerHeadObjectContents.Builder head = ObjectContents.playerHead().id(id).hat(true);
            if (skin != null) {
                head.profileProperty(skin);
            }
            root.append(Component.object(head.build()).color(code(slot, kind, 0, 0)));
            advance += 8;
        }

        void bar(final int slot, final int kind, final double x, final double w) {
            if (w <= 0) {
                return;
            }
            root.append(Component.text(String.valueOf(PartyFont.SOLID), code(slot, kind, x, w)).font(PARTY_FONT));
            advance += PartyFont.SOLID_ADVANCE;
        }

        /** 一段字（每個字先畫一次陰影）；回傳寬度。 */
        double text(final int slot, final int kind, final double x, final String text) {
            double at = x;
            for (int i = 0; i < text.length(); ) {
                final int cp = text.codePointAt(i);
                final String glyph = new String(Character.toChars(cp));
                i += Character.charCount(cp);
                final double step = width(glyph);
                if (cp != ' ' && step > 1) {
                    final double w = step - 1; // 原版的字距是 1
                    root.append(Component.text(glyph, code(slot, PartyFont.SHADOW, at + 1, w)));
                    root.append(Component.text(glyph, code(slot, kind, at, w)));
                    advance += 2 * step;
                }
                at += step;
            }
            return at - x;
        }

        Component build() {
            root.append(shift(-(PartyFont.PRE_SHIFT + (int) Math.round(advance))));
            return root.build();
        }
    }
}
