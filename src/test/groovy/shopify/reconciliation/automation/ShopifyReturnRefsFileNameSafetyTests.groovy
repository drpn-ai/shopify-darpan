package shopify.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * I3 (DAR-BE-018 fix wave A): extractShopifyReturnRefs.groovy DEFINED a `safeFileName` closure and
 * never called it — an operator-supplied `fileName` went straight into
 * DataManagerSupport.childLocation (plain string concatenation) and moveIntoLocation
 * (REPLACE_EXISTING, no traversal guard of its own). The fix wires safeFileName into the call site
 * (see extractShopifyReturnRefs.groovy) so it now reads:
 *   String outputFileName = safeFileName(fileName ?: "...", "shopify-return-refs.json")
 * where safeFileName is:
 *   { Object rawName, String fallback ->
 *       String safeName = DataManagerSupport.safeToken(rawName, fallback)
 *       return safeName.toLowerCase(Locale.ROOT).endsWith(".json") ? safeName : "${safeName}.json"
 *   }
 * — identical, word for word, to extractShopifyOrders.groovy's proven safeFileName closure.
 *
 * There is no unit-test harness anywhere in this repo for the top-level Moqui script services
 * themselves (extractShopifyOrders.groovy has none either): they need a live `ec` binding plus
 * ShopifyAuthConfigSupport/entity lookups just to start executing, which nothing in this test suite
 * fakes. This test instead proves the sanitization PRIMITIVE the fixed call site now goes through —
 * DataManagerSupport.safeToken, reimplemented here exactly as the closure wraps it — actually
 * neutralizes a traversal-style name. The call-site wiring itself is verified by reading
 * extractShopifyReturnRefs.groovy, not by execution.
 */
class ShopifyReturnRefsFileNameSafetyTests {

    private static String safeFileName(Object rawName, String fallback) {
        String safeName = DataManagerSupport.safeToken(rawName, fallback)
        return safeName.toLowerCase(Locale.ROOT).endsWith(".json") ? safeName : "${safeName}.json"
    }

    @Test
    void neutralizesAPosixStyleTraversalFileNameAndKeepsTheJsonSuffixContract() {
        String sanitized = safeFileName("../../../etc/passwd", "shopify-return-refs.json")

        assertFalse(sanitized.contains(".."), "path traversal segments must not survive: ${sanitized}")
        assertFalse(sanitized.contains("/"), "no path separators must survive: ${sanitized}")
        assertEquals("passwd.json", sanitized)
    }

    @Test
    void neutralizesAWindowsStyleTraversalFileName() {
        String sanitized = safeFileName("..\\..\\Windows\\win.ini", "shopify-return-refs.json")

        assertFalse(sanitized.contains(".."), "path traversal segments must not survive: ${sanitized}")
        assertFalse(sanitized.contains("\\"), "no path separators must survive: ${sanitized}")
    }

    @Test
    void keepsAnAlreadySafeFileNameUnchangedAndDoesNotDoubleTheJsonSuffix() {
        assertEquals("my-run.json", safeFileName("my-run.json", "shopify-return-refs.json"))
    }

    @Test
    void fallsBackWhenTheNameIsBlank() {
        assertEquals("shopify-return-refs.json", safeFileName("   ", "shopify-return-refs.json"))
        assertEquals("shopify-return-refs.json", safeFileName(null, "shopify-return-refs.json"))
    }
}
