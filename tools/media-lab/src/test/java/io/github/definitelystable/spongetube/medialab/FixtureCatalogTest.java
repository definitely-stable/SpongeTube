package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureCatalogTest {

    @TempDir
    Path temp;

    @Test
    void catalogsOnlyKnownFixtureFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("media"));
        Path fixture = Files.createDirectory(root.resolve("F0"));
        Files.write(fixture.resolve("sample.bin"), new byte[] {1, 2, 3});
        Files.writeString(root.resolve("manifest.json"), "{}");

        FixtureCatalog catalog = FixtureCatalog.load(root);

        FixtureResource resource = catalog.findRawPath("/fixtures/F0/sample.bin");
        assertNotNull(resource);
        assertEquals("F0", resource.fixtureId());
        assertEquals("sample.bin", resource.resourceId());
        assertEquals(3, resource.length());

        assertNull(catalog.findRawPath("/manifest.json"));
        assertNull(catalog.findRawPath("/fixtures/F0/missing.bin"));
        assertNull(catalog.findRawPath("/fixtures/F0/%2e%2e/secret"));
        assertNull(catalog.findRawPath("/fixtures/F0/..\\secret"));
    }

    @Test
    void rejectsInvalidFixtureNames() throws Exception {
        Path root = Files.createDirectory(temp.resolve("media"));
        Path fixture = Files.createDirectory(root.resolve("bad fixture"));
        Files.write(fixture.resolve("sample.bin"), new byte[] {1});

        assertThrows(IOException.class, () -> FixtureCatalog.load(root));
    }

    @Test
    void rejectsSymlinksInsideFixtureTreeOnSupportedPlatforms() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name").toLowerCase().contains("win"),
                "symlink creation may require elevated privileges on Windows");

        Path root = Files.createDirectory(temp.resolve("media"));
        Path fixture = Files.createDirectory(root.resolve("F0"));
        Path target = Files.write(temp.resolve("target.bin"), new byte[] {1});
        Files.createSymbolicLink(fixture.resolve("linked.bin"), target);

        assertThrows(IOException.class, () -> FixtureCatalog.load(root));
    }
}
