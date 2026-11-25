package shopify.reconciliation.automation

import org.junit.jupiter.api.Test
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifySourceCatalog

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-368: an orders bulk read's lines put back under their order, one order held at a time. */
class ShopifyOrderLinesNestingTests {

    private static List<Map> nest(List<Map> lines) {
        List<Map> out = []
        ShopifyOrderLinesNesting nesting = new ShopifyOrderLinesNesting({ Map order -> out.add(order) })
        lines.each { nesting.add(it as Map<String, Object>) }
        nesting.finish()
        out.add([orphanLines: nesting.orphanLines])
        return out
    }

    @Test
    void eachOrderCarriesItsOwnLinesAndAnOrderWithNoneCarriesAnEmptyList() {
        List<Map> out = nest([
                [id: "gid://shopify/Order/1", name: "#1"],
                [id: "gid://shopify/LineItem/11", sku: "A", quantity: 1, __parentId: "gid://shopify/Order/1"],
                [id: "gid://shopify/Order/2", name: "#2"],
                [id: "gid://shopify/Order/3", name: "#3"],
                [id: "gid://shopify/LineItem/31", sku: "C", quantity: 3, currentQuantity: 3, unfulfilledQuantity: 1,
                 __parentId: "gid://shopify/Order/3"],
        ])
        assertEquals(["#1", "#2", "#3"], out.take(3)*.name)
        assertEquals(["11"], out[0].lineItems*.id)
        assertEquals([], out[1].lineItems)
        assertEquals([id: "31", sku: "C", name: null, quantity: 3, currentQuantity: 3, unfulfilledQuantity: 1], out[2].lineItems[0])
        assertEquals(0, out[3].orphanLines)
    }

    @Test
    void aLineAwayFromItsOrderIsCountedAndNeverPutOnAnother() {
        List<Map> out = nest([
                [id: "gid://shopify/LineItem/9", sku: "X", __parentId: "gid://shopify/Order/1"],
                [id: "gid://shopify/Order/1", name: "#1"],
                [id: "gid://shopify/Order/2", name: "#2"],
                [id: "gid://shopify/LineItem/12", sku: "Y", __parentId: "gid://shopify/Order/1"],
        ])
        assertEquals([], out[0].lineItems)
        assertEquals([], out[1].lineItems)
        assertEquals(2, out[2].orphanLines)
    }

    @Test
    void onlyTheOrdersWithLinesSourceSelectsLinesAndThePickerNeverListsIt() {
        String withLines = ShopifyGraphqlQueryBuilder.buildBulkQuery([sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDERS_WITH_LINES,
                filters: [createdAtFrom: "2026-11-01T00:00:00Z", createdAtTo: "2026-11-02T00:00:00Z"]]).queryDocument
        ["lineItems", "sku", "unfulfilledQuantity", "currentQuantity", "displayFulfillmentStatus"].each {
            assertTrue(withLines.contains(it), "$it in $withLines")
        }
        assertThrows(IllegalArgumentException) {
            ShopifyGraphqlQueryBuilder.buildBulkQuery([sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDERS,
                    selectedFieldPaths: ["id", "lineItems.sku"]])
        }
        assertNull(ShopifySourceCatalog.listSources().find { it.sourceDefinitionId == ShopifySourceCatalog.SHOPIFY_ORDERS_WITH_LINES })
    }
}
