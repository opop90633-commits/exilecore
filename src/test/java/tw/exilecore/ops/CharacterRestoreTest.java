package tw.exilecore.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-31：還原單一隻角色——從備份檔裡挑出那隻角色的列。 */
class CharacterRestoreTest {

    private static Path backup(final String... lines) throws IOException {
        final Path file = Files.createTempFile("db-", ".json.gz");
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            writer.write("{\"format\":\"exilecore-backup\",\"version\":1,\"created\":1790000000000}\n");
            for (final String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        return file;
    }

    private static final String CHARACTERS = "{\"table\":\"characters\",\"columns\":[\"id\",\"account_uuid\",\"slot\",\"name\",\"class_id\",\"char_level\",\"deleted_at\"]}";

    @Test
    void picksOnlyThatCharactersRows() throws IOException {
        final Path file = backup(
                "{\"table\":\"schema_version\",\"columns\":[\"version\"]}",
                "{\"row\":[30]}",
                CHARACTERS,
                "{\"row\":[7,\"00000000-0000-0000-0000-000000000001\",0,\"Alice\",\"ranger\",42,null]}",
                "{\"row\":[8,\"00000000-0000-0000-0000-000000000001\",1,\"Bob\",\"monk\",10,null]}",
                "{\"table\":\"character_inventory\",\"columns\":[\"character_id\",\"contents\"]}",
                "{\"row\":[7,{\"b64\":\"AAE=\"}]}",
                "{\"row\":[8,{\"b64\":\"AAI=\"}]}",
                "{\"table\":\"progress\",\"columns\":[\"character_id\",\"progress_key\",\"progress_value\"]}",
                "{\"row\":[7,\"act\",\"3\"]}",
                "{\"row\":[7,\"asc\",\"stararrow\"]}",
                "{\"row\":[8,\"act\",\"1\"]}",
                "{\"table\":\"stash_pages\",\"columns\":[\"account_uuid\",\"page\"]}",
                "{\"row\":[\"00000000-0000-0000-0000-000000000001\",0]}");
        final CharacterRestore.Snapshot alice = CharacterRestore.read(file, "alice");
        assertEquals(7, alice.id());
        assertEquals("Alice", alice.name());
        assertEquals(42, alice.level());
        assertFalse(alice.deleted());
        assertEquals(1, alice.rows().get("characters").size());
        assertEquals(1, alice.rows().get("character_inventory").size());
        assertEquals(2, alice.rows().get("progress").size());
        assertFalse(alice.rows().containsKey("stash_pages"), "帳號共用的不動");
        assertEquals(8, CharacterRestore.read(file, "#8").id(), "#編號");
        assertEquals(8, CharacterRestore.read(file, "8").id(), "純數字也是編號");
        assertThrows(IOException.class, () -> CharacterRestore.read(file, "Carol"));
    }

    @Test
    void sameNameTwicePrefersTheLivingOneOrAsksForTheNumber() throws IOException {
        final Path file = backup(CHARACTERS,
                "{\"row\":[3,\"00000000-0000-0000-0000-000000000002\",0,\"Dup\",\"witch\",5,{\"ts\":1789000000000}]}",
                "{\"row\":[4,\"00000000-0000-0000-0000-000000000002\",1,\"Dup\",\"witch\",9,null]}");
        final CharacterRestore.Snapshot dup = CharacterRestore.read(file, "Dup");
        assertEquals(4, dup.id(), "同名的挑沒刪掉的那隻");
        assertTrue(CharacterRestore.read(file, "#3").deleted());
        final Path both = backup(CHARACTERS,
                "{\"row\":[5,\"00000000-0000-0000-0000-000000000003\",0,\"Twin\",\"monk\",5,null]}",
                "{\"row\":[6,\"00000000-0000-0000-0000-000000000004\",0,\"Twin\",\"monk\",9,null]}");
        final IOException error = assertThrows(IOException.class, () -> CharacterRestore.read(both, "Twin"));
        assertTrue(error.getMessage().contains("#5") && error.getMessage().contains("#6"), error.getMessage());
    }
}
