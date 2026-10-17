package shopify.reconciliation

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.fail

/**
 * Golden-snapshot helpers for the characterization tests that run this component's script services
 * end to end through a real Moqui {@code ec.service.sync()} (so the service XML contract, including
 * the out-parameter filter that drops undeclared result keys, is part of what is pinned).
 *
 * <p>A snapshot is the service result, the {@code ec.message} errors, every argument the script
 * passed across the Shopify boundary (captured by the metaClass mocks in each test), and the exact
 * bytes of every file the run left in its output folder. A missing golden is written and the test
 * FAILS, so a first run can never pass on its own say-so; rerun against unchanged code to confirm.</p>
 */
class ShopifyScriptCharacterizationSupport {

    static final String GOLDEN_DIR = "src/test/resources/characterization"

    static void assertMatchesGolden(String name, Object snapshot) {
        String actual = JsonOutput.prettyPrint(JsonOutput.toJson(snapshot)) + "\n"
        Path golden = componentRoot().resolve(GOLDEN_DIR).resolve("${name}.json")
        if (!Files.exists(golden)) {
            Files.createDirectories(golden.parent)
            Files.write(golden, actual.getBytes(StandardCharsets.UTF_8))
            fail("Golden ${golden} did not exist and was written from this run; rerun to verify it.")
        }
        assertEquals(new String(Files.readAllBytes(golden), StandardCharsets.UTF_8), actual,
                "characterization drift against ${golden}")
    }

    /** Every regular file under the folder, by path relative to it, as exact text. */
    static Map<String, String> folderContents(File folder) {
        Map<String, String> contents = new TreeMap<>()
        if (folder == null || !folder.exists()) return contents
        Path root = folder.toPath()
        folder.eachFileRecurse { File file ->
            if (file.isFile()) contents.put(root.relativize(file.toPath()).toString().replace(File.separator, "/"), file.getText("UTF-8"))
        }
        return contents
    }

    static void resetFolder(File folder) {
        if (folder.exists()) folder.deleteDir()
        folder.mkdirs()
    }

    /** Replaces the per-run timestamp DataManagerSupport.formatRunTimestamp puts in a default location. */
    static Object stripRunTimestamps(Object value) {
        if (value instanceof String) return ((String) value).replaceAll(/(?<!\d)20\d{6}-\d{9}(?!\d)/, "<RUN_TIMESTAMP>")
        if (value instanceof Map) {
            Map copy = new LinkedHashMap()
            ((Map) value).each { k, v -> copy.put(k, stripRunTimestamps(v)) }
            return copy
        }
        if (value instanceof List) return ((List) value).collect { stripRunTimestamps(it) }
        return value
    }

    static Path componentRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("src/main/groovy/shopify/reconciliation"))) return dir
            Path nested = dir.resolve("runtime/component/shopify-darpan")
            if (Files.exists(nested.resolve("src/main/groovy/shopify/reconciliation"))) return nested
            dir = dir.parent
        }
        throw new IllegalStateException("Could not locate the shopify-darpan component from user.dir")
    }
}
