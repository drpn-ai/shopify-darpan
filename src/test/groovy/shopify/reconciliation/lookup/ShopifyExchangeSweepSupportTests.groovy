package shopify.reconciliation.lookup

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * Shopify exchange sweep (product contract 2026-07-31): enumerate exchanges CREATED IN SHOPIFY
 * within a return-date window, so the verify stage can confirm each was imported into OMS.
 * Sweeps orders(query: "updated_at:>=<windowStart> -return_status:no_return") — updated_at has no
 * upper bound because later activity on an order must not hide an in-window return — then filters
 * client-side to returns whose createdAt falls inside [windowStart, windowEnd) and that carry at
 * least one exchange line item.
 */
class ShopifyExchangeSweepSupportTests {
    static final long WINDOW_START = 1785196800000L   // 2026-07-28T00:00:00Z
    static final long WINDOW_END = 1785283200000L     // 2026-07-29T00:00:00Z

    private static Map orderNode(String legacyId, List returnsNodes, String cursor) {
        [cursor: cursor, node: [id: "gid://shopify/Order/${legacyId}".toString(), legacyResourceId: legacyId,
                name: "#T${legacyId}".toString(), returns: [nodes: returnsNodes]]]
    }

    private static Map exchangeReturn(String returnId, String createdAt) {
        [id: "gid://shopify/Return/${returnId}".toString(), name: "R-${returnId}".toString(), status: "CLOSED",
         createdAt: createdAt, exchangeLineItems: [nodes: [[id: "gid://shopify/ExchangeLineItem/1"]]]]
    }

    private static Map plainReturn(String returnId, String createdAt) {
        [id: "gid://shopify/Return/${returnId}".toString(), name: "R-${returnId}".toString(), status: "CLOSED",
         createdAt: createdAt, exchangeLineItems: [nodes: []]]
    }

    private static Map page(List edges, boolean hasNext) {
        [ok: true, data: [orders: [edges: edges, pageInfo: [hasNextPage: hasNext, endCursor: edges ? edges.last().cursor : null]]]]
    }

    @Test
    void collectsInWindowExchangesAndFiltersReturnDateClientSide() {
        List queries = []
        def executor = { Map cfg, String query, Map vars, Map opts ->
            queries.add([query: query, vars: vars])
            page([
                orderNode("111", [exchangeReturn("r1", "2026-07-28T10:00:00Z")], "c1"),          // in window -> kept
                orderNode("222", [exchangeReturn("r2", "2026-07-30T10:00:00Z")], "c2"),          // after window -> dropped
                orderNode("333", [plainReturn("r3", "2026-07-28T11:00:00Z")], "c3"),             // no exchange -> dropped
                orderNode("444", [exchangeReturn("r4", "2026-07-27T23:00:00Z")], "c4"),          // before window -> dropped
            ], false)
        }

        Map result = ShopifyExchangeSweepSupport.sweepExchanges([shopApiUrl: "https://x.myshopify.com",
                apiVersion: "2025-07", accessToken: "t"], WINDOW_START, WINDOW_END, [:], executor)

        assertTrue(result.ok as boolean, result.errors.toString())
        List exchanges = (List) result.exchanges
        assertEquals(1, exchanges.size())
        assertEquals("111", exchanges[0].externalId)
        assertEquals("gid://shopify/Return/r1", exchanges[0].returnId)
        assertTrue((exchanges[0].returnCreatedAtMillis as long) >= WINDOW_START)
        String searchQuery = queries[0].vars.query
        assertTrue(searchQuery.contains("updated_at:>="), searchQuery)
        assertTrue(searchQuery.contains("-return_status:no_return"), searchQuery)
        assertFalse(searchQuery.contains("updated_at:<"), "no upper bound on updated_at: " + searchQuery)
    }

    @Test
    void paginatesWithCursorUntilExhausted() {
        List afters = []
        int call = 0
        def executor = { Map cfg, String query, Map vars, Map opts ->
            afters.add(vars.after)
            call++
            if (call == 1) return page([orderNode("111", [exchangeReturn("r1", "2026-07-28T10:00:00Z")], "cA")], true)
            return page([orderNode("555", [exchangeReturn("r5", "2026-07-28T12:00:00Z")], "cB")], false)
        }

        Map result = ShopifyExchangeSweepSupport.sweepExchanges([:], WINDOW_START, WINDOW_END, [:], executor)

        assertEquals([null, "cA"], afters)
        assertEquals(["111", "555"], ((List) result.exchanges).collect { it.externalId })
    }

    @Test
    void canceledExchangeReturnsNeitherIncludeNorCount() {
        // User-verified 2026-07-31: canceled exchange returns never produce an OMS exchange order.
        // A canceled in-window return must not seed inclusion, and canceled returns must not
        // inflate the expected count on orders included via a live return.
        Map canceledReturn = [id: "gid://shopify/Return/rc", name: "R-rc", status: "CANCELED",
                createdAt: "2026-07-28T09:00:00Z", exchangeLineItems: [nodes: [[id: "gid://shopify/ExchangeLineItem/9"]]]]
        def executor = { Map cfg, String q, Map v, Map o ->
            page([
                orderNode("111", [canceledReturn], "c1"),                                          // only canceled -> excluded entirely
                orderNode("222", [exchangeReturn("r2", "2026-07-28T10:00:00Z"), canceledReturn], "c2"), // live + canceled -> count 1
            ], false)
        }
        Map result = ShopifyExchangeSweepSupport.sweepExchanges([:], WINDOW_START, WINDOW_END, [:], executor)
        List exchanges = (List) result.exchanges
        assertEquals(1, exchanges.size())
        assertEquals("222", exchanges[0].externalId)
        assertEquals(1, exchanges[0].exchangeReturnCount)
    }

    @Test
    void transportFailureIsConservative() {
        def executor = { Map cfg, String q, Map v, Map o -> [ok: false, errors: ["HTTP 500"], graphqlErrors: []] }
        Map result = ShopifyExchangeSweepSupport.sweepExchanges([:], WINDOW_START, WINDOW_END, [:], executor)
        assertFalse(result.ok as boolean)
        assertTrue(((List) result.exchanges).isEmpty())
    }

    @Test
    void pageCapMarksTruncationInsteadOfLoopingForever() {
        def executor = { Map cfg, String q, Map vars, Map o ->
            page([orderNode("${vars.after ?: 'x'}9".toString(), [exchangeReturn("r", "2026-07-28T10:00:00Z")], "c${vars.after ?: 0}".toString())], true)
        }
        Map result = ShopifyExchangeSweepSupport.sweepExchanges([:], WINDOW_START, WINDOW_END, [maxPages: 3], executor)
        assertTrue(result.ok as boolean)
        assertTrue(result.truncated as boolean)
        assertEquals(3, ((List) result.exchanges).size())
    }

    @Test
    void oneEntryPerOrderCarryingTheTotalExchangeReturnCount() {
        // Per-exchange presence: an order with an old imported exchange and a new missing one must
        // not read as present. The entry counts ALL exchange returns on the order (in-window or
        // not) so the verify stage can compare against OMS's exchange-order count.
        def executor = { Map cfg, String q, Map v, Map o ->
            page([orderNode("111", [exchangeReturn("r0", "2026-07-01T10:00:00Z"),               // old exchange
                                    exchangeReturn("r1", "2026-07-28T10:00:00Z"),               // in window
                                    exchangeReturn("r2", "2026-07-28T12:00:00Z")], "c1")], false)
        }
        Map result = ShopifyExchangeSweepSupport.sweepExchanges([:], WINDOW_START, WINDOW_END, [:], executor)
        List exchanges = (List) result.exchanges
        assertEquals(1, exchanges.size())
        assertEquals("111", exchanges[0].externalId)
        assertEquals(3, exchanges[0].exchangeReturnCount)
    }
}
