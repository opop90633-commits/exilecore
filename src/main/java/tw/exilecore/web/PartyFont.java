package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 畫面左邊的隊伍框（2026-10-18，{@link tw.exilecore.party.PartyHud}）：資源包的部分。
 * <p>
 * 原版的動作列、Boss 血條都是置中的，沒有辦法靠字型把東西放到畫面左邊（畫面多寬客戶端才知道）。做法是改原版的文字著色器
 * （{@code core/text.vsh}，GUI 裡的字都走它）：
 * <ul>
 *   <li>隊伍框的每個字、每段血條都是動作列裡的一個字，先用位移字元推到畫面右邊很遠的地方（{@value #PRE_SHIFT} 格，
 *       正常的字不會在那裡），著色器只動「在動作列那一行、又在畫面右邊 2000 格以外」的字；</li>
 *   <li>字的顏色不是拿來上色的，是座標：R = 框裡的 x（半格）、G = 寬（半格）、B = 第幾個隊員（高 4 位）＋哪一種東西（低 4 位，
 *       決定 y、高度、真正的顏色，見 {@link #KINDS}）；</li>
 *   <li>著色器照這些把字的四個角搬到畫面左上角（離左邊 {@value #LEFT} 格、上面 {@value #TOP} 格，每個隊員往下 {@value #STEP} 格），
 *       拉成要的大小：血條是一個實心的字拉長的，頭像是原版的玩家頭（文字的 object 元件）放大；</li>
 *   <li>四個角哪個是哪個：字型貼圖（256×256）的 UV 原版內縮了 0.01 個像素，左／上的角小數是 .01、右／下是 .99；
 *       玩家頭用的是 64×64 的皮膚貼圖，看 UV 是 8／16（頭）或 40／48（帽子）。</li>
 * </ul>
 * 名字用原版字型（任何字都畫得出來）；寬度照 {@link HudFont#estimateVanilla} 算，差一點只是那個字偏一點。
 * 著色器的寫法 26.2（{@code #moj_import}）跟 26.3（{@code #include}、layout）不一樣，用 pack.mcmeta 的 overlays 分版本放；
 * 其他版本沒有著色器，隊伍框就一直待在畫面外面，不會亂畫。
 */
public final class PartyFont {

    /** 隊伍框用的字型：只有一個實心的字（血條、底框）。 */
    public static final String FONT = "exilecore:party";
    public static final char SOLID = '';
    /** 實心字的寬度（8 格寬的點陣 → 前進 9）。 */
    public static final int SOLID_ADVANCE = 9;
    /** 先往右推多遠（著色器只動畫面右邊 2000 格以外的字）。 */
    public static final int PRE_SHIFT = 8000;
    /** 框離畫面左邊、上面幾格；每個隊員往下幾格（跟著色器裡的一樣）。 */
    public static final int LEFT = 4;
    public static final int TOP = 24; // 2026-10-23：4 → 24（最上面的全服加成血條（標題＋條）會蓋到第一個隊員）
    public static final int STEP = 26;

    // ---- 種類（顏色的 B 低 4 位）：y、高度、顏色寫在著色器的 KINDS ----
    public static final int HEAD = 0;
    public static final int HEAD_DIM = 1;
    public static final int SHADOW = 2;
    public static final int TEXT = 3;
    public static final int TEXT_GOLD = 4;
    public static final int TEXT_GRAY = 5;
    public static final int TEXT_DIM = 6;
    public static final int TEXT_RED = 7;
    public static final int FRAME = 8;
    public static final int LIFE_BG = 9;
    public static final int LIFE = 10;
    public static final int ES = 11;
    public static final int MANA_BG = 12;
    public static final int MANA = 13;

    /** 每一種的 y、高度（框裡，格）與顏色（r, g, b, a）。順序跟上面的編號一樣。 */
    private static final String KINDS = """
                vec2(2.0, 18.0), vec2(2.0, 18.0), vec2(3.0, 8.0), vec2(2.0, 8.0), vec2(2.0, 8.0), vec2(2.0, 8.0), vec2(2.0, 8.0), vec2(2.0, 8.0),
                vec2(11.0, 11.0), vec2(12.0, 5.0), vec2(12.0, 5.0), vec2(12.0, 2.0), vec2(18.0, 3.0), vec2(18.0, 3.0), vec2(0.0, 0.0), vec2(0.0, 0.0)""";
    private static final String TINTS = """
                vec4(1.0, 1.0, 1.0, 1.0), vec4(0.42, 0.42, 0.42, 1.0), vec4(0.0, 0.0, 0.0, 0.85), vec4(1.0, 1.0, 1.0, 1.0),
                vec4(1.0, 0.82, 0.36, 1.0), vec4(0.70, 0.70, 0.70, 1.0), vec4(0.48, 0.48, 0.48, 1.0), vec4(1.0, 0.38, 0.38, 1.0),
                vec4(0.0, 0.0, 0.0, 0.72), vec4(0.26, 0.05, 0.06, 0.92), vec4(0.80, 0.12, 0.15, 1.0), vec4(0.66, 0.90, 1.0, 1.0),
                vec4(0.05, 0.09, 0.24, 0.92), vec4(0.22, 0.45, 1.0, 1.0), vec4(0.0), vec4(0.0)""";

    /** 搬字的那段（26.2、26.3 共用；Position、Color、UV0、ProjMat、Sampler0 要先宣告好）。 */
    private static final String MOVE = """
            // ExileCore 隊伍框（2026-10-18）：見 tw.exilecore.web.PartyFont
            const vec2 EC_PARTY_KINDS[16] = vec2[16](
            %s
            );
            const vec4 EC_PARTY_TINTS[16] = vec4[16](
            %s
            );

            bool ecPartyMove(inout vec3 position, inout vec4 color) {
                float guiW = 2.0 / ProjMat[0][0];
                float guiH = -2.0 / ProjMat[1][1];
                float lineTop = guiH - 72.0;
                if (guiW <= 0.0 || guiH <= 0.0 || Position.x < guiW + 2000.0 || Position.y < lineTop - 4.0 || Position.y > lineTop + 13.0) {
                    return false;
                }
                ivec3 c = ivec3(round(Color.rgb * 255.0));
                int kind = c.b & 15;
                int member = c.b >> 4;
                float x = float(c.r) * 0.5;
                float w = float(c.g) * 0.5;
                vec2 t = UV0 * vec2(textureSize(Sampler0, 0));
                bool right;
                bool bottom;
                if (kind <= %d) {
                    if (textureSize(Sampler0, 0) != ivec2(64, 64) || c.r != 0 || c.g != 0) {
                        return false;
                    }
                    // 玩家頭：皮膚貼圖的臉 8..16、帽子 40..48
                    x = 2.0;
                    w = 18.0;
                    right = mod(floor(t.x + 0.5), 32.0) > 12.0;
                    bottom = t.y > 12.0;
                } else {
                    // 字型貼圖：原版把 UV 往內縮 0.01 像素
                    right = fract(t.x) > 0.5;
                    bottom = fract(t.y) > 0.5;
                }
                vec2 row = EC_PARTY_KINDS[kind];
                vec4 tint = EC_PARTY_TINTS[kind];
                float x0 = %d.0 + x;
                float y0 = %d.0 + float(member) * %d.0 + row.x;
                position.x = right ? x0 + w : x0;
                position.y = bottom ? y0 + row.y : y0;
                color = vec4(tint.rgb, tint.a * Color.a);
                return true;
            }
            """.formatted(KINDS, TINTS, HEAD_DIM, LEFT, TOP, STEP);

    /** 26.2（資源包格式 88～96）：原版 core/text.vsh 加上搬字。 */
    static final String VSH_88 = """
            #version 330

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            #moj_import <minecraft:fog.glsl>
            #moj_import <minecraft:sample_lightmap.glsl>
            #endif

            #moj_import <minecraft:dynamictransforms.glsl>
            #moj_import <minecraft:projection.glsl>

            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            in ivec2 UV2;
            #endif

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            uniform sampler2D Sampler2;
            out float sphericalVertexDistance;
            out float cylindricalVertexDistance;
            #endif

            #ifdef IS_GUI
            uniform sampler2D Sampler0;
            #endif

            out vec4 vertexColor;
            out vec2 texCoord0;

            #ifdef IS_GUI
            %s
            #endif

            void main() {
                vec3 position = Position;
                vec4 color = Color;
            #ifdef IS_GUI
                ecPartyMove(position, color);
            #endif
                gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                sphericalVertexDistance = fog_spherical_distance(Position);
                cylindricalVertexDistance = fog_cylindrical_distance(Position);
                vertexColor = Color * sample_lightmap(Sampler2, UV2);
            #else
                vertexColor = color;
            #endif
                texCoord0 = UV0;
            }
            """.formatted(MOVE);

    /** 26.3（資源包格式 97）：#include、layout(location)。 */
    static final String VSH_97 = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            #include <minecraft:fog.glsl>
            #include <minecraft:sample_lightmap.glsl>
            #endif

            #include <minecraft:dynamictransforms.glsl>
            #include <minecraft:projection.glsl>

            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            layout(location = 3) in ivec2 UV2;
            #endif

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
            uniform sampler2D Sampler2;
            layout(location = 0) out float sphericalVertexDistance;
            layout(location = 1) out float cylindricalVertexDistance;
            #endif

            #ifdef IS_GUI
            uniform sampler2D Sampler0;
            #endif

            layout(location = 2) out vec4 vertexColor;
            layout(location = 3) out vec2 texCoord0;

            #ifdef IS_GUI
            %s
            #endif

            void main() {
                vec3 position = Position;
                vec4 color = Color;
            #ifdef IS_GUI
                ecPartyMove(position, color);
            #endif
                gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

            #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                sphericalVertexDistance = fog_spherical_distance(Position);
                cylindricalVertexDistance = fog_cylindrical_distance(Position);
                vertexColor = Color * sample_lightmap(Sampler2, UV2);
            #else
                vertexColor = color;
            #endif
                texCoord0 = UV0;
            }
            """.formatted(MOVE);

    /** overlays：資料夾、適用的資源包格式（含）。 */
    private record Overlay(String directory, int min, int max, String vsh) {
    }

    private static final Overlay[] OVERLAYS = {
            new Overlay("exilecore_party_88", 88, 96, VSH_88),
            new Overlay("exilecore_party_97", 97, 97, VSH_97),
    };

    private PartyFont() {
    }

    /** pack.mcmeta 的 overlays.entries 要加的。 */
    static JsonArray overlayEntries() {
        final JsonArray entries = new JsonArray();
        for (final Overlay overlay : OVERLAYS) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("directory", overlay.directory());
            entry.addProperty("min_format", overlay.min());
            entry.addProperty("max_format", overlay.max());
            entries.add(entry);
        }
        return entries;
    }

    /** 字型（實心的字）與各版本的著色器。 */
    static void build(final Map<String, byte[]> files) throws IOException {
        final BufferedImage solid = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                solid.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        files.put("assets/exilecore/textures/font/party_solid.png", png(solid));
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", "exilecore:font/party_solid.png");
        bitmap.addProperty("ascent", 7);
        bitmap.addProperty("height", 8);
        final JsonArray chars = new JsonArray();
        chars.add(String.valueOf(SOLID));
        bitmap.add("chars", chars);
        final JsonArray providers = new JsonArray();
        providers.add(bitmap);
        final JsonObject root = new JsonObject();
        root.add("providers", providers);
        files.put("assets/exilecore/font/party.json", root.toString().getBytes(StandardCharsets.UTF_8));
        for (final Overlay overlay : OVERLAYS) {
            files.put(overlay.directory() + "/assets/minecraft/shaders/core/text.vsh", overlay.vsh().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
