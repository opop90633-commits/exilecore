package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 光環腳下的法陣（2026-10）：一張主法陣（外圈符文刻紋、內圈幾何線、柔和的光暈）＋每個光環一顆元素符文繞著外圈轉。
 * 都是白色的圖，遊戲裡用 custom_model_data 的顏色 0 著色（跟招式預警的 mark 一樣）。
 * <ul>
 *   <li>{@code exilecore:mark/aura_sigil}：主法陣，平面在正中間（跟 mark/disc 同一個模型）；</li>
 *   <li>{@code exilecore:mark/rune_元素}：符文，平面在模型 +X 那一側（離中心 7 個單位），
 *       實體只要「繞 Y 軸轉」符文就沿著圓弧繞圈（客戶端補間是轉角度，不會走直線切過去）。</li>
 * </ul>
 */
final class AuraSigils {

    /** 符文的種類（skill/SkillVisuals.auraRune 照光環的外觀挑一個）。 */
    static final List<String> RUNES = List.of("cold", "fire", "lightning", "armour", "evasion", "es", "haste", "purity", "life",
            "mana", "precision", "ash", "thunder", "ice", "star");

    static final int SIGIL_SIZE = 256;
    static final int RUNE_SIZE = 64;

    private AuraSigils() {
    }

    static List<String> names() {
        final java.util.List<String> names = new java.util.ArrayList<>();
        names.add("aura_sigil");
        names.add("ring_thin");
        for (final String rune : RUNES) {
            names.add("rune_" + rune);
        }
        return names;
    }

    static BufferedImage image(final String name) {
        if (name.equals("ring_thin")) {
            return thinRing();
        }
        return name.equals("aura_sigil") ? sigil() : rune(name.substring("rune_".length()));
    }

    /** 常駐地面圈（裂隙、祭壇、怪物光環、焚燒範圍）用的細圈：256 像素、線寬 3 像素，圈再大也只是一條細線。 */
    static BufferedImage thinRing() {
        final int n = 256;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = smooth(image);
        final double c = n / 2.0;
        final double r = c - 3;
        g.setPaint(white(225));
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
        g.setPaint(white(70));
        g.setStroke(new BasicStroke(1.5f));
        final double inner = r - 5;
        g.draw(new Ellipse2D.Double(c - inner, c - inner, inner * 2, inner * 2)); // 內側一條淡的，看起來有一點光暈
        g.dispose();
        return image;
    }

    /** 符文的模型：一小片水平平面，在模型 +X 那一側（中心 x = 15，離模型中心 7 個單位），寬 3 個單位。 */
    static JsonObject runePlane() {
        final JsonObject root = new JsonObject();
        root.addProperty("ambientocclusion", false);
        final JsonObject textures = new JsonObject();
        textures.addProperty("particle", "#t");
        root.add("textures", textures);
        final JsonObject face = new JsonObject();
        face.add("uv", numbers(0, 0, 16, 16));
        face.addProperty("texture", "#t");
        face.addProperty("tintindex", 0);
        final JsonObject faces = new JsonObject();
        faces.add("up", face);
        final JsonObject element = new JsonObject();
        element.add("from", numbers(13.5, 8, 6.5));
        element.add("to", numbers(16.5, 8, 9.5));
        element.addProperty("shade", false);
        element.add("faces", faces);
        final JsonArray elements = new JsonArray();
        elements.add(element);
        root.add("elements", elements);
        return root;
    }

    private static JsonArray numbers(final double... values) {
        final JsonArray array = new JsonArray();
        for (final double value : values) {
            array.add(value);
        }
        return array;
    }

    private static Graphics2D smooth(final BufferedImage image) {
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static Color white(final int alpha) {
        return new Color(255, 255, 255, Math.max(0, Math.min(255, alpha)));
    }

    // ---- 主法陣 ----

    static BufferedImage sigil() {
        final int n = SIGIL_SIZE;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = smooth(image);
        final double c = n / 2.0;
        final double r = c - 1;

        // 柔和的光暈：中間幾乎透明，往外圈慢慢亮，外圈外面淡出
        g.setPaint(new RadialGradientPaint((float) c, (float) c, (float) r,
                new float[] {0f, 0.55f, 0.86f, 0.93f, 1f},
                new Color[] {white(0), white(10), white(46), white(30), white(0)}));
        g.fill(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));

        // 外圈：兩條線夾著一圈符文刻紋（符文會繞著這一圈轉）
        final double outer = r * 0.97;
        final double band = r * 0.875;
        final double inner = r * 0.78;
        circle(g, c, outer, n * 0.012f, 235);
        circle(g, c, inner, n * 0.009f, 210);
        // 刻紋：短線、點、小三角交錯（每 6 度一個），符文所在的位置（每 15 度）留空比較乾淨就不特別挖
        for (int i = 0; i < 60; i++) {
            final double a = Math.PI * 2 * i / 60;
            final double x = c + Math.cos(a) * band;
            final double y = c + Math.sin(a) * band;
            switch (i % 3) {
                case 0 -> {
                    g.setPaint(white(200));
                    g.setStroke(new BasicStroke(n * 0.008f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    final double l = (outer - inner) * 0.28;
                    g.draw(new Line2D.Double(c + Math.cos(a) * (band - l), c + Math.sin(a) * (band - l),
                            c + Math.cos(a) * (band + l), c + Math.sin(a) * (band + l)));
                }
                case 1 -> {
                    g.setPaint(white(170));
                    final double d = n * 0.011;
                    g.fill(new Ellipse2D.Double(x - d, y - d, d * 2, d * 2));
                }
                default -> {
                    g.setPaint(white(185));
                    final double s = n * 0.016;
                    final Path2D tri = new Path2D.Double();
                    tri.moveTo(0, -s);
                    tri.lineTo(s * 0.87, s * 0.5);
                    tri.lineTo(-s * 0.87, s * 0.5);
                    tri.closePath();
                    final AffineTransform at = AffineTransform.getTranslateInstance(x, y);
                    at.rotate(a + Math.PI / 2);
                    g.fill(at.createTransformedShape(tri));
                }
            }
        }

        // 內圈：兩個交錯的三角形（六芒星）＋一個小圓＋中心的菱形
        g.setStroke(new BasicStroke(n * 0.007f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(white(170));
        for (int k = 0; k < 2; k++) {
            final Path2D star = new Path2D.Double();
            for (int i = 0; i < 3; i++) {
                final double a = -Math.PI / 2 + Math.PI * 2 * i / 3 + k * Math.PI / 3;
                final double x = c + Math.cos(a) * inner * 0.96;
                final double y = c + Math.sin(a) * inner * 0.96;
                if (i == 0) {
                    star.moveTo(x, y);
                } else {
                    star.lineTo(x, y);
                }
            }
            star.closePath();
            g.draw(star);
        }
        circle(g, c, inner * 0.48, n * 0.007f, 190);
        // 內小圓上 6 個小弧（像轉盤的齒）
        g.setStroke(new BasicStroke(n * 0.012f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setPaint(white(150));
        final double ir = inner * 0.40;
        for (int i = 0; i < 6; i++) {
            g.draw(new Arc2D.Double(c - ir, c - ir, ir * 2, ir * 2, i * 60 + 10, 30, Arc2D.OPEN));
        }
        g.setPaint(white(200));
        final double d = inner * 0.14;
        final Path2D diamond = new Path2D.Double();
        diamond.moveTo(c, c - d);
        diamond.lineTo(c + d * 0.6, c);
        diamond.lineTo(c, c + d);
        diamond.lineTo(c - d * 0.6, c);
        diamond.closePath();
        g.fill(diamond);
        g.dispose();
        return image;
    }

    private static void circle(final Graphics2D g, final double c, final double radius, final float stroke, final int alpha) {
        g.setPaint(white(alpha));
        g.setStroke(new BasicStroke(stroke));
        g.draw(new Ellipse2D.Double(c - radius, c - radius, radius * 2, radius * 2));
    }

    // ---- 符文 ----

    static BufferedImage rune(final String kind) {
        final int n = RUNE_SIZE;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = smooth(image);
        final double c = n / 2.0;
        // 底：一顆柔和的光點＋細外框（讓符文在地上看得清楚）
        g.setPaint(new RadialGradientPaint((float) c, (float) c, (float) (c - 1),
                new float[] {0f, 0.7f, 1f}, new Color[] {white(70), white(40), white(0)}));
        g.fill(new Ellipse2D.Double(1, 1, n - 2, n - 2));
        g.setPaint(white(220));
        g.setStroke(new BasicStroke(n * 0.035f));
        g.draw(new Ellipse2D.Double(n * 0.08, n * 0.08, n * 0.84, n * 0.84));
        final boolean herald = kind.equals("ash") || kind.equals("thunder") || kind.equals("ice");
        if (herald) {
            g.draw(new Ellipse2D.Double(n * 0.16, n * 0.16, n * 0.68, n * 0.68)); // 使者：雙外框
        }
        g.setPaint(white(255));
        final double s = herald ? n * 0.26 : n * 0.30; // 圖案的半徑
        final float line = n * 0.06f;
        g.setStroke(new BasicStroke(line, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "cold", "ice" -> snowflake(g, c, s, line);
            case "fire", "ash" -> g.fill(flame(c, s));
            case "lightning", "thunder" -> g.fill(bolt(c, s));
            case "armour" -> g.fill(shield(c, s));
            case "evasion" -> swirl(g, c, s);
            case "es" -> crystal(g, c, s);
            case "haste" -> chevrons(g, c, s);
            case "purity", "star" -> g.fill(star(c, s, kind.equals("purity") ? 4 : 5));
            case "life" -> g.fill(heart(c, s));
            case "mana" -> g.fill(drop(c, s));
            case "precision" -> crosshair(g, c, s);
            default -> g.fill(star(c, s, 5));
        }
        g.dispose();
        return image;
    }

    private static void snowflake(final Graphics2D g, final double c, final double s, final float line) {
        for (int i = 0; i < 6; i++) {
            final double a = Math.PI * 2 * i / 6 - Math.PI / 2;
            final double x = c + Math.cos(a) * s;
            final double y = c + Math.sin(a) * s;
            g.draw(new Line2D.Double(c, c, x, y));
            final double mx = c + Math.cos(a) * s * 0.6;
            final double my = c + Math.sin(a) * s * 0.6;
            for (final int side : new int[] {-1, 1}) {
                final double b = a + side * Math.PI / 4;
                g.draw(new Line2D.Double(mx, my, mx + Math.cos(b) * s * 0.3, my + Math.sin(b) * s * 0.3));
            }
        }
    }

    private static Shape flame(final double c, final double s) {
        // 三道火舌：中間最高，兩側矮一點，底部圓（跟魔力的水滴分得開）
        final GeneralPath p = new GeneralPath();
        p.moveTo(c - s * 0.15, c - s * 1.05);
        p.curveTo(c + s * 0.35, c - s * 0.55, c + s * 0.25, c - s * 0.35, c + s * 0.3, c - s * 0.15);
        p.curveTo(c + s * 0.45, c - s * 0.35, c + s * 0.55, c - s * 0.55, c + s * 0.6, c - s * 0.7);
        p.curveTo(c + s * 0.95, c - s * 0.2, c + s * 0.9, c + s * 0.35, c + s * 0.65, c + s * 0.7);
        p.curveTo(c + s * 0.4, c + s * 1.0, c - s * 0.4, c + s * 1.0, c - s * 0.65, c + s * 0.7);
        p.curveTo(c - s * 0.9, c + s * 0.35, c - s * 0.85, c - s * 0.05, c - s * 0.6, c - s * 0.4);
        p.curveTo(c - s * 0.55, c - s * 0.15, c - s * 0.45, c - s * 0.05, c - s * 0.35, c);
        p.curveTo(c - s * 0.45, c - s * 0.45, c - s * 0.35, c - s * 0.75, c - s * 0.15, c - s * 1.05);
        p.closePath();
        return p;
    }

    private static Shape bolt(final double c, final double s) {
        final Path2D p = new Path2D.Double();
        p.moveTo(c + s * 0.25, c - s);
        p.lineTo(c - s * 0.55, c + s * 0.1);
        p.lineTo(c - s * 0.02, c + s * 0.1);
        p.lineTo(c - s * 0.3, c + s);
        p.lineTo(c + s * 0.6, c - s * 0.2);
        p.lineTo(c + s * 0.05, c - s * 0.2);
        p.closePath();
        return p;
    }

    private static Shape shield(final double c, final double s) {
        final GeneralPath p = new GeneralPath();
        p.moveTo(c, c - s);
        p.lineTo(c + s * 0.8, c - s * 0.7);
        p.curveTo(c + s * 0.8, c + s * 0.2, c + s * 0.4, c + s * 0.75, c, c + s);
        p.curveTo(c - s * 0.4, c + s * 0.75, c - s * 0.8, c + s * 0.2, c - s * 0.8, c - s * 0.7);
        p.closePath();
        return p;
    }

    private static void swirl(final Graphics2D g, final double c, final double s) {
        for (int i = 0; i < 3; i++) {
            final double y = c - s * 0.6 + i * s * 0.6;
            final double w = s * (1.6 - i * 0.25);
            final GeneralPath p = new GeneralPath();
            p.moveTo(c - w / 2, y);
            p.curveTo(c - w / 6, y - s * 0.25, c + w / 6, y + s * 0.25, c + w / 2, y);
            g.draw(p);
        }
    }

    private static void crystal(final Graphics2D g, final double c, final double s) {
        final Path2D p = new Path2D.Double();
        p.moveTo(c, c - s);
        p.lineTo(c + s * 0.6, c - s * 0.3);
        p.lineTo(c, c + s);
        p.lineTo(c - s * 0.6, c - s * 0.3);
        p.closePath();
        g.draw(p);
        g.draw(new Line2D.Double(c - s * 0.6, c - s * 0.3, c + s * 0.6, c - s * 0.3));
        g.draw(new Line2D.Double(c, c - s, c, c + s));
    }

    private static void chevrons(final Graphics2D g, final double c, final double s) {
        for (int i = 0; i < 2; i++) {
            final double x = c - s * 0.45 + i * s * 0.6;
            final Path2D p = new Path2D.Double();
            p.moveTo(x - s * 0.25, c - s * 0.7);
            p.lineTo(x + s * 0.35, c);
            p.lineTo(x - s * 0.25, c + s * 0.7);
            g.draw(p);
        }
    }

    private static Shape star(final double c, final double s, final int points) {
        final Path2D p = new Path2D.Double();
        for (int i = 0; i < points * 2; i++) {
            final double a = -Math.PI / 2 + Math.PI * i / points;
            final double r = i % 2 == 0 ? s : s * (points == 4 ? 0.32 : 0.42);
            if (i == 0) {
                p.moveTo(c + Math.cos(a) * r, c + Math.sin(a) * r);
            } else {
                p.lineTo(c + Math.cos(a) * r, c + Math.sin(a) * r);
            }
        }
        p.closePath();
        return p;
    }

    private static Shape heart(final double c, final double s) {
        final GeneralPath p = new GeneralPath();
        p.moveTo(c, c + s * 0.9);
        p.curveTo(c - s * 1.2, c + s * 0.05, c - s * 0.75, c - s * 1.0, c, c - s * 0.4);
        p.curveTo(c + s * 0.75, c - s * 1.0, c + s * 1.2, c + s * 0.05, c, c + s * 0.9);
        p.closePath();
        return p;
    }

    private static Shape drop(final double c, final double s) {
        final GeneralPath p = new GeneralPath();
        p.moveTo(c, c - s);
        p.curveTo(c + s * 0.3, c - s * 0.4, c + s * 0.75, c, c + s * 0.7, c + s * 0.4);
        p.curveTo(c + s * 0.6, c + s * 1.0, c - s * 0.6, c + s * 1.0, c - s * 0.7, c + s * 0.4);
        p.curveTo(c - s * 0.75, c, c - s * 0.3, c - s * 0.4, c, c - s);
        p.closePath();
        return p;
    }

    private static void crosshair(final Graphics2D g, final double c, final double s) {
        g.draw(new Ellipse2D.Double(c - s * 0.6, c - s * 0.6, s * 1.2, s * 1.2));
        g.draw(new Line2D.Double(c, c - s, c, c - s * 0.3));
        g.draw(new Line2D.Double(c, c + s * 0.3, c, c + s));
        g.draw(new Line2D.Double(c - s, c, c - s * 0.3, c));
        g.draw(new Line2D.Double(c + s * 0.3, c, c + s, c));
    }
}
