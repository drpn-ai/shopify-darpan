package shopify.reconciliation.lookup

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 / DAR-BE-036. A Moqui script service returns only its DECLARED out-parameters, so a key
 * the support class adds to its result never reaches ec.service callers unless the service XML
 * declares it and the script assigns it. ShopifyRefundOrReturnLookupSupport has returned
 * unresolvedIds since DAR-BE-036, and the service declared neither — so MissingDiffVerificationSupport
 * never saw it and counted every unchecked id as confirmed missing. Unit tests on the support class
 * stayed green throughout; only the service boundary shows the drop.
 */
class ShopifyLookupServiceContractTests {

    @Test
    void theRefundOrReturnLookupServiceDeclaresUnresolvedIds() {
        def services = new XmlSlurper().parse(componentFile("service/reconciliation/ShopifyOrderExtractionServices.xml").toFile())
        def service = services.service.find { it.@verb == "lookup" && it.@noun == "ShopifyRefundOrReturnIds" }
        assertTrue(service.size() == 1, "lookup#ShopifyRefundOrReturnIds not found")
        List<String> outs = service.'out-parameters'.parameter.collect { it.@name.text() }
        assertTrue(outs.contains("unresolvedIds"), "unresolvedIds must be a declared out-parameter: ${outs}")
    }

    @Test
    void theRefundOrReturnLookupScriptPassesUnresolvedIdsThrough() {
        // Code lines only — a comment mentioning the assignment must not satisfy this.
        List<String> code = componentFile("src/main/groovy/shopify/reconciliation/lookup/lookupShopifyRefundOrReturnIds.groovy").toFile()
                .readLines().collect { it.trim() }.findAll { it && !it.startsWith("//") && !it.startsWith("*") }
        assertTrue(code.any { it ==~ /unresolvedIds\s*=\s*result\.unresolvedIds.*/ },
                "the script must assign unresolvedIds from the support result")
    }

    private static Path componentFile(String relative) {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            Path candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return candidate
            Path nested = dir.resolve("runtime/component/shopify-darpan").resolve(relative)
            if (Files.exists(nested)) return nested
            dir = dir.parent
        }
        throw new IllegalStateException("Could not locate ${relative} from user.dir")
    }
}
