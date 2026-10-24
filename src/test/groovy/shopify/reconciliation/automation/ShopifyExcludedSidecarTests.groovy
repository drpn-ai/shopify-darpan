package shopify.reconciliation.automation

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-127: the spill holds customer records, so a Shopify extract deletes it on every path. */
class ShopifyExcludedSidecarTests {

    private static final Map RULE = [sequenceNum: 1, fieldExpression: "displayFinancialStatus"]

    @Test
    void writingTheSidecarDeletesTheSpill() {
        Map collector = ExcludedRecordsSidecar.newCollector()
        ExcludedRecordsSidecar.collect(collector, [orderId: "1001"], RULE)
        File spill = (File) collector.spill
        assertTrue(spill.exists())
        List<String> warnings = []

        ShopifyExtractSupport.writeExcludedSidecar(null, collector, "dbresource://darpan/runs/1", "shopify-orders.json", warnings)

        assertFalse(spill.exists(), "the spill is gone once the sidecar is written")
        assertEquals([], warnings)
    }

    @Test
    void nothingExcludedWritesNothingAndWarnsOfNothing() {
        List<String> warnings = []
        ShopifyExtractSupport.writeExcludedSidecar(null, ExcludedRecordsSidecar.newCollector(), "dbresource://darpan/runs/1",
                "shopify-orders.json", warnings)
        assertEquals([], warnings)
    }
}
