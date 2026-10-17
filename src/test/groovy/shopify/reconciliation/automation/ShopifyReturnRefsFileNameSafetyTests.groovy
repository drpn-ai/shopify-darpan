package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * I3 (DAR-BE-018 fix wave A): extractShopifyReturnRefs.groovy DEFINED a `safeFileName` closure and
 * never called it — an operator-supplied `fileName` went straight into
 * DataManagerSupport.childLocation (plain string concatenation) and moveIntoLocation
 * (REPLACE_EXISTING, no traversal guard of its own). The fix wired safeFileName into the call site:
 *   String outputFileName = ShopifyExtractSupport.safeFileName(fileName ?: "...", "shopify-return-refs.json")
 * The closure has since moved to ShopifyExtractSupport.safeFileName, shared with
 * extractShopifyOrders and extractShopifyOrderLineUnits, so this test now exercises it directly.
 * ShopifyExtractScriptCharacterizationTests pins the call-site wiring by running the service.
 */
class ShopifyReturnRefsFileNameSafetyTests {

    private static String safeFileName(Object rawName, String fallback) {
        return ShopifyExtractSupport.safeFileName(rawName, fallback)
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
