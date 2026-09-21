package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonParserTest {

    @Test
    void parsesManifestShapedJson() {
        Object parsed = MiniJsonParser.parse(
                "{\"schemaVersion\":1,\"items\":[{\"path\":\"F1/a.m4s\",\"size\":42}],\"optional\":null}");

        Map<?, ?> root = (Map<?, ?>) parsed;
        assertEquals(1L, root.get("schemaVersion"));
        assertEquals(null, root.get("optional"));

        List<?> items = (List<?>) root.get("items");
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("F1/a.m4s", item.get("path"));
        assertEquals(42L, item.get("size"));
    }

    @Test
    void rejectsDuplicateObjectKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonParser.parse("{\"a\":1,\"a\":2}"));
    }
}
