package shopify.reconciliation.lookup

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ShopifyExchangeStateLookupSupportTests {
    private static Map order(String legacyId, List returnsNodes) {
        [id: "gid://shopify/Order/${legacyId}".toString(), legacyResourceId: legacyId, name: "#T${legacyId}".toString(),
         returnStatus: "RETURNED",
         currentTotalPriceSet: [shopMoney: [amount: "185.71", currencyCode: "USD"]],
         returns: [nodes: returnsNodes]]
    }

    @Test
    void parsesExchangeStateAndFiltersReturnsWithoutExchanges() {
        Map exchangeReturn = [id: "gid://shopify/Return/25734480003", status: "CLOSED", createdAt: "2026-07-23T17:07:40Z",
                exchangeLineItems: [nodes: [[id: "gid://shopify/ExchangeLineItem/3914137731", quantity: 1,
                        lineItems: [[id: "gid://shopify/LineItem/15760850976899", sku: "2511-200G-186-G", quantity: 1]]]]]]
        Map plainReturn = [id: "gid://shopify/Return/9", status: "CLOSED", createdAt: "2026-07-01T00:00:00Z",
                exchangeLineItems: [nodes: []]]
        def executor = { Map cfg, String query, Map vars, Map opts ->
            assertTrue(query.contains("exchangeLineItems"))
            [ok: true, data: [nodes: [order("6941645013123", [exchangeReturn, plainReturn])]]]
        }

        Map result = ShopifyExchangeStateLookupSupport.lookupExchangeState([shopApiUrl: "https://x.myshopify.com",
                apiVersion: "2025-07", accessToken: "t"], ["6941645013123"], [:], executor)

        assertTrue(result.ok as boolean, result.errors.toString())
        Map state = (Map) result.statesByOrderId["6941645013123"]
        assertEquals("RETURNED", state.returnStatus)
        assertEquals(new BigDecimal("185.71"), state.currentTotalAmount)
        assertEquals(1, ((List) state.exchanges).size())
        Map exchange = (Map) ((List) state.exchanges).first()
        assertEquals("CLOSED", exchange.status)
        assertEquals("2511-200G-186-G", ((List) ((List) exchange.exchangeLineItems).first().lineItems).first().sku)
    }

    @Test
    void normalizesConnectionShapedLineItems() {
        Map connReturn = [id: "gid://shopify/Return/1", status: "OPEN", createdAt: "2026-07-23T00:00:00Z",
                exchangeLineItems: [nodes: [[id: "gid://shopify/ExchangeLineItem/1", quantity: 2,
                        lineItems: [nodes: [[id: "gid://shopify/LineItem/7", sku: "SKU-7", quantity: 2]]]]]]]
        def executor = { Map cfg, String q, Map v, Map o -> [ok: true, data: [nodes: [order("42", [connReturn])]]] }

        Map result = ShopifyExchangeStateLookupSupport.lookupExchangeState([:], ["42"], [:], executor)

        Map state = (Map) result.statesByOrderId["42"]
        assertEquals("SKU-7", ((List) ((List) ((List) state.exchanges).first().exchangeLineItems).first().lineItems).first().sku)
    }

    @Test
    void retriesWithConnectionQueryWhenListShapeIsRejected() {
        List queries = []
        def executor = { Map cfg, String query, Map vars, Map opts ->
            queries.add(query)
            if (queries.size() == 1) return [ok: false, graphqlErrors: ["Field 'lineItems' is missing required arguments: first"], errors: ["Shopify GraphQL error: ..."]]
            [ok: true, data: [nodes: [order("42", [])]]]
        }

        Map result = ShopifyExchangeStateLookupSupport.lookupExchangeState([:], ["42"], [:], executor)

        assertTrue(result.ok as boolean)
        assertEquals("connection", result.lineItemsShape)
        assertTrue(queries[1].contains("lineItems(first:"))
    }

    @Test
    void transportFailureIsConservative() {
        def executor = { Map cfg, String q, Map v, Map o -> [ok: false, errors: ["Shopify GraphQL request failed with HTTP 500."], graphqlErrors: []] }
        Map result = ShopifyExchangeStateLookupSupport.lookupExchangeState([:], ["42"], [:], executor)
        assertFalse(result.ok as boolean)
        assertTrue(result.statesByOrderId.isEmpty())
    }

    @Test
    void batchesAboveFiftyIdsIntoMultipleCalls() {
        List callSizes = []
        def executor = { Map cfg, String q, Map vars, Map o ->
            callSizes.add(((List) vars.ids).size())
            [ok: true, data: [nodes: ((List) vars.ids).collect { String gid -> order(gid.tokenize("/").last(), []) }]]
        }
        Map result = ShopifyExchangeStateLookupSupport.lookupExchangeState([:], (1..120).collect { "$it".toString() }, [:], executor)
        assertTrue(result.ok as boolean)
        assertEquals([50, 50, 20], callSizes)
        assertEquals(120, result.statesByOrderId.size())
    }
}
