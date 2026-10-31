package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照站長：網頁的更新公告（/news）。純邏輯＋檔案，不依賴 Bukkit。
 * <ul>
 *   <li>插件裡附一份（web/news.json）：每次改版把這次的改動整理進去，伺服器啟動時自動併進來；</li>
 *   <li>伺服器上存一份（plugins/ExileCore/news.json）：管理網頁可以新增、修改、刪除；</li>
 *   <li>併的規則：插件附的公告照代號比對——伺服器上沒有的新增；有、但管理員沒改過的換成插件的新版；
 *       管理員改過的不動；管理員刪掉的不會再加回來。</li>
 * </ul>
 */
public final class NewsStore {

    /** 一篇公告（body：Discord 風格的文字，## 標題、- 項目、**粗體**、-# 小字）。 */
    public record Post(String id, String date, String title, String body, boolean bundled, boolean edited) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final int TITLE_MAX = 80;
    static final int BODY_MAX = 20_000;

    private final Path file;
    private final List<Post> posts = new ArrayList<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    private volatile byte[] publicJson = "{\"posts\":[]}".getBytes(StandardCharsets.UTF_8);

    public NewsStore(final Path file) {
        this.file = file;
    }

    /** 讀伺服器上的那份，併進插件附的那份（bundled 是 web/news.json 的內容；null = 沒有），寫回去。 */
    public synchronized void load(final String bundled) throws IOException {
        posts.clear();
        deleted.clear();
        if (Files.exists(file)) {
            final JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (final JsonElement e : array(root, "posts")) {
                final JsonObject o = e.getAsJsonObject();
                posts.add(new Post(text(o, "id"), text(o, "date"), text(o, "title"), text(o, "body"),
                        o.has("bundled") && o.get("bundled").getAsBoolean(), o.has("edited") && o.get("edited").getAsBoolean()));
            }
            for (final JsonElement e : array(root, "deleted")) {
                deleted.add(e.getAsString());
            }
        }
        if (bundled != null && !bundled.isBlank()) {
            merge(JsonParser.parseString(bundled).getAsJsonObject());
        }
        sort();
        save();
    }

    /** 併進插件附的公告（規則見類別說明）。 */
    synchronized void merge(final JsonObject bundled) {
        for (final JsonElement e : array(bundled, "posts")) {
            final JsonObject o = e.getAsJsonObject();
            final Post post = new Post(text(o, "id"), text(o, "date"), text(o, "title"), text(o, "body"), true, false);
            if (post.id().isBlank() || deleted.contains(post.id())) {
                continue;
            }
            final int index = indexOf(post.id());
            if (index < 0) {
                posts.add(post);
            } else if (!posts.get(index).edited()) {
                posts.set(index, post);
            }
        }
        sort();
    }

    private int indexOf(final String id) {
        for (int i = 0; i < posts.size(); i++) {
            if (posts.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void sort() {
        posts.sort(Comparator.comparing(Post::date).reversed().thenComparing(Post::id, Comparator.reverseOrder()));
        final JsonObject root = new JsonObject();
        final JsonArray list = new JsonArray();
        for (final Post post : posts) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", post.id());
            o.addProperty("date", post.date());
            o.addProperty("title", post.title());
            o.addProperty("body", post.body());
            list.add(o);
        }
        root.add("posts", list);
        publicJson = root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 網頁 /news/data.json 的內容。 */
    public byte[] publicJson() {
        return publicJson;
    }

    public synchronized List<Post> posts() {
        return List.copyOf(posts);
    }

    /** 新增或修改（id 是 null 或空的＝新增）；回傳存好的那篇。 */
    public synchronized Post save(final String id, final String date, final String title, final String body) throws IOException {
        final String cleanTitle = title == null ? "" : title.strip();
        final String cleanBody = body == null ? "" : body.replace("\r\n", "\n").strip();
        final String cleanDate = date == null ? "" : date.strip();
        if (cleanTitle.isEmpty()) {
            throw new IllegalArgumentException("標題不能是空的");
        }
        if (cleanTitle.length() > TITLE_MAX) {
            throw new IllegalArgumentException("標題最多 " + TITLE_MAX + " 個字");
        }
        if (cleanBody.length() > BODY_MAX) {
            throw new IllegalArgumentException("內容最多 " + BODY_MAX + " 個字");
        }
        if (!cleanDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("日期要寫成 2026-11-01 這種格式");
        }
        final boolean create = id == null || id.isBlank();
        final int index = create ? -1 : indexOf(id);
        if (!create && index < 0) {
            throw new IllegalArgumentException("找不到這篇公告（可能被別人刪掉了），重新整理再試");
        }
        final Post post = create ? new Post("admin-" + UUID.randomUUID().toString().substring(0, 8), cleanDate, cleanTitle, cleanBody, false, true)
                : new Post(id, cleanDate, cleanTitle, cleanBody, posts.get(index).bundled(), true);
        if (create) {
            posts.add(post);
        } else {
            posts.set(index, post);
        }
        sort();
        save();
        return post;
    }

    /** 刪掉（插件附的那幾篇記下來，之後不會再加回來）。 */
    public synchronized boolean delete(final String id) throws IOException {
        final int index = id == null ? -1 : indexOf(id);
        if (index < 0) {
            return false;
        }
        final Post removed = posts.remove(index);
        if (removed.bundled()) {
            deleted.add(removed.id());
        }
        sort();
        save();
        return true;
    }

    private void save() throws IOException {
        final JsonObject root = new JsonObject();
        final JsonArray list = new JsonArray();
        for (final Post post : posts) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", post.id());
            o.addProperty("date", post.date());
            o.addProperty("title", post.title());
            o.addProperty("body", post.body());
            o.addProperty("bundled", post.bundled());
            o.addProperty("edited", post.edited());
            list.add(o);
        }
        root.add("posts", list);
        final JsonArray gone = new JsonArray();
        deleted.forEach(gone::add);
        root.add("deleted", gone);
        Files.createDirectories(file.toAbsolutePath().getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonArray array(final JsonObject root, final String key) {
        return root.has(key) && root.get(key).isJsonArray() ? root.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
