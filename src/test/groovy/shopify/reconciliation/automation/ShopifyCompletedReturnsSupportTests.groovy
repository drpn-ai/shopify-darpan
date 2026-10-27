package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-137. One row per order with a return COMPLETED in the window: CLOSED, closedAt inside [start, end), and
 * (refunded) carrying a refund or (exchanged) carrying exchange items.
 */
class ShopifyCompletedReturnsSupportTests {
    static final long WINDOW_START = 1792454400000L   // 2026-10-20T00:00:00Z
    static final long WINDOW_END = 1792540800000L     // 2026-10-21T00:00:00Z
    static final Map CONFIG = [shopApiUrl: "https://x.myshopify.com", apiVersion: "2025-07", accessToken: "t"]

    private static Map order(String legacyId, String name, List returns) {
        [legacyResourceId: legacyId, name: name, returns: [nodes: returns]]
    }

    private static Map ret(String id, String status, String closedAt, boolean refunded, boolean exchanged) {
        [id       : "gid://shopify/Return/${id}".toString(), status: status, closedAt: closedAt,
         refunds  : [nodes: refunded ? [[id: "gid://shopify/Refund/9"]] : []],
         exchangeLineItems: [nodes: exchanged ? [[id: "gid://shopify/ExchangeLineItem/9"]] : []]]
    }

    private static Map page(List nodes, boolean hasNext, String cursor = null) {
        [ok: true, data: [orders: [nodes: nodes, pageInfo: [hasNextPage: hasNext, endCursor: cursor]]]]
    }

    private static final List ORDERS = [
            order("1", "#DK1", [ret("11", "CLOSED", "2026-10-20T10:00:00Z", true, false)]),   // refunded, in window
            order("2", "#DK2", [ret("21", "CLOSED", "2026-10-20T11:00:00Z", false, true)]),   // exchange, no refund
            order("3", "#DK3", [ret("31", "OPEN", null, true, false)]),                        // not completed
            order("4", "#DK4", [ret("41", "CLOSED", "2026-10-21T00:00:00Z", true, false)]),   // closed at window end: out
            order("5", "#DK5", [ret("51", "CLOSED", "2026-10-19T23:59:59Z", true, true)]),    // closed before the window
            order("6", "DK6", [ret("61", "CLOSED", "2026-10-20T01:00:00Z", true, true),
                               ret("62", "CLOSED", "2026-10-20T02:00:00Z", true, false)]),     // both kinds, two returns
    ]

    private static Map run(String kind) {
        List calls = []
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, kind, WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> calls.add(vars); page(ORDERS, false) })
        return result + [calls: calls]
    }

    @Test
    void refundedKeepsOrdersWhoseCompletedReturnHasARefund() {
        Map result = run("refunded")
        assertEquals([], result.errors)
        List records = (List) result.records
        assertEquals(["1", "6"], records*.orderId)
        assertEquals(["DK1", "DK6"], records*.orderName, "a leading # is dropped so the name matches NetSuite's DK<name>")
        assertEquals(["11", "61,62"], records*.returnIds)
        assertEquals([1, 2], records*.returnCount)
        assertEquals("2026-10-20T02:00:00Z", records[1].closedAt, "the latest completion")
    }

    @Test
    void exchangedKeepsOrdersWhoseCompletedReturnHasExchangeItems() {
        List records = (List) run("exchanged").records
        assertEquals(["2", "6"], records*.orderId)
        assertEquals(["21", "61"], records*.returnIds, "an exchange with no refund is held to this check only")
    }

    @Test
    void theNetReadsReturnsUpdatedSinceTheWindowStartWithNoUpperBound() {
        Map vars = (Map) ((List) run("refunded").calls)[0]
        assertEquals("updated_at:>='2026-10-20T00:00:00Z' AND -return_status:no_return", vars.query)
        assertEquals(ShopifyCompletedReturnsSupport.RETURNS_PER_ORDER, vars.returnsFirst)
    }

    @Test
    void pagesUntilShopifySaysThereIsNoNextPage() {
        List cursors = []
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "refunded", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts ->
                    cursors.add(vars.after)
                    vars.after ? page([ORDERS[5]], false) : page([ORDERS[0]], true, "c1")
                })
        assertEquals([null, "c1"], cursors)
        assertEquals(["1", "6"], ((List) result.records)*.orderId)
    }

    @Test
    void aFailedReadReturnsItsErrorsAndNoRecords() {
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "exchanged", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> [ok: false, errors: ["ACCESS_DENIED: read_returns"]] })
        assertEquals([], result.records)
        assertEquals(["ACCESS_DENIED: read_returns"], result.errors)
    }

    @Test
    void anUnknownKindIsRefusedInWords() {
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "both", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> fail("must not read Shopify") })
        assertEquals(["completedReturns is 'refunded', 'exchanged' or 'orderRefunded', got: both"], result.errors)
    }

    // DAR-CLIENT-005. RAILS's returns app (Loop) closes the Shopify Return and refunds the ORDER, never linking the
    // refund to the Return (133 of 133 Loop returns, USA, Oct 20-26). orderRefunded counts such a return as refunded.
    private static Map loopOrder(String legacyId, String name, String returnCreatedAt, String closedAt, List refundTimes,
                                 boolean linked = false) {
        [legacyResourceId: legacyId, name: name,
         refunds         : refundTimes.collect { [createdAt: it] },
         returns         : [nodes: [[id      : "gid://shopify/Return/${legacyId}1".toString(), status: "CLOSED",
                                     createdAt: returnCreatedAt, closedAt: closedAt,
                                     refunds : [nodes: linked ? [[id: "gid://shopify/Refund/9"]] : []],
                                     exchangeLineItems: [nodes: []]]]]]
    }

    private static final List LOOP_ORDERS = [
            loopOrder("21", "#1001", "2026-10-15T08:00:00Z", "2026-10-20T09:00:00Z", ["2026-10-20T09:00:00Z"]),  // Loop: refund on the order
            loopOrder("22", "#1002", "2026-10-19T08:00:00Z", "2026-10-20T09:00:00Z", [], true),                   // POS: linked refund
            loopOrder("23", "#1003", "2026-10-18T08:00:00Z", "2026-10-20T09:00:00Z", ["2026-10-05T22:00:00Z"]),  // refund before the return
            loopOrder("24", "#1004", "2026-10-18T08:00:00Z", "2026-10-20T09:00:00Z", []),                         // no refund at all
            loopOrder("25", "#1005", "2026-10-18T08:00:00Z", "2026-10-21T09:00:00Z", ["2026-10-21T09:00:00Z"]),  // closed after the window
    ]

    private static Map runOrderRefunded(List orders) {
        List calls = []
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "orderRefunded", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> calls.add([query: query, vars: vars]); page(orders, false) })
        return result + [calls: calls]
    }

    @Test
    void orderRefundedCountsAReturnWhoseOrderWasRefundedOnOrAfterTheReturnWasMade() {
        Map result = runOrderRefunded(LOOP_ORDERS)
        assertEquals([], result.errors)
        assertEquals(["21", "22"], ((List) result.records)*.orderId,
                "a refund on the order (Loop) or a refund linked to the return (POS); not one older than the return, not none")
        assertEquals(["1001", "1002"], ((List) result.records)*.orderName)
        assertEquals("orderRefunded", result.requestMetadata.completedReturns)
    }

    @Test
    void orderRefundedAsksForTheOrdersRefundsAndTheReturnsCreationTime() {
        Map call = (Map) ((List) runOrderRefunded(LOOP_ORDERS).calls)[0]
        assertTrue(((String) call.query).contains("refunds(first: \$orderRefundsFirst) { createdAt }"), call.query as String)
        assertTrue(((String) call.query).contains("createdAt"), call.query as String)
        assertEquals(ShopifyCompletedReturnsSupport.ORDER_REFUNDS_PER_ORDER, ((Map) call.vars).orderRefundsFirst)
    }

    @Test
    void refundedAndExchangedStillSendTheirOwnDocumentUnchanged() {
        List queries = []
        ["refunded", "exchanged"].each { String kind ->
            ShopifyCompletedReturnsSupport.extract(CONFIG, kind, WINDOW_START, WINDOW_END, [:],
                    { Map cfg, String query, Map vars, Map opts -> queries.add(query); assertFalse(vars.containsKey("orderRefundsFirst")); page([], false) })
        }
        queries.each { String q -> assertFalse(q.contains("orderRefundsFirst"), q) }
        assertEquals([], ShopifyCompletedReturnsSupport.extract(CONFIG, "refunded", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> page([LOOP_ORDERS[0]], false) }).records,
                "refunded keeps its meaning: a Loop return with no linked refund is not refunded there")
    }

    @Test
    void anOrderAtTheReturnsLimitIsWarnedAbout() {
        List returns = (1..ShopifyCompletedReturnsSupport.RETURNS_PER_ORDER).collect {
            ret("x${it}", "CLOSED", "2026-10-20T03:00:00Z", true, false)
        }
        List<String> warnings = []
        Map record = ShopifyCompletedReturnsSupport.toRecord(order("7", "#DK7", returns), "refunded",
                WINDOW_START, WINDOW_END, warnings)
        assertEquals(ShopifyCompletedReturnsSupport.RETURNS_PER_ORDER, record.returnCount)
        assertEquals(1, warnings.size())
        assertTrue(warnings[0].contains("Order 7"), warnings[0])
    }

    @Test
    void theOriginalUnfulfilledQuantityLeavesOutTheLinesAnExchangeAdded() {
        // The exchange added line L3; it is unfulfilled until the exchange ships, which is not the original order.
        Map order = [legacyResourceId: "8", name: "DK8",
                     lineItems: [nodes: [[id: "L1", unfulfilledQuantity: 0], [id: "L2", unfulfilledQuantity: 2],
                                         [id: "L3", unfulfilledQuantity: 1]]],
                     returns  : [nodes: [[id: "gid://shopify/Return/81", status: "CLOSED", closedAt: "2026-10-20T05:00:00Z",
                                          refunds: [nodes: []],
                                          exchangeLineItems: [nodes: [[id: "E1", lineItems: [[id: "L3"]]]]]]]]]
        Map record = ShopifyCompletedReturnsSupport.toRecord(order, "exchanged", WINDOW_START, WINDOW_END, [])
        assertEquals(2, record.originalUnfulfilledQuantity, "L2 still waits to ship; L3 is the exchange's own line")

        order.lineItems = [nodes: [[id: "L1", unfulfilledQuantity: 0], [id: "L3", unfulfilledQuantity: 1]]]
        assertEquals(0, ShopifyCompletedReturnsSupport.toRecord(order, "exchanged", WINDOW_START, WINDOW_END, []).originalUnfulfilledQuantity)
    }

    @Test
    void aPageOverShopifysCostLimitIsAskedAgainWithFewerOrders() {
        List sizes = []
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "refunded", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts ->
                    sizes.add(vars.first)
                    vars.first > 12 ? [ok: false, errors: ["Shopify GraphQL error: Query cost is 4302, which exceeds the single query max cost limit (1000)."]]
                            : page([ORDERS[0]], false)
                })
        assertEquals([], result.errors)
        assertEquals([50, 25, 12], sizes)
        assertEquals(["1"], ((List) result.records)*.orderId)
        assertEquals(12, result.requestMetadata.ordersPageSize)
    }

    @Test
    void aCostRefusalAtOneOrderPerPageIsAFailure() {
        Map result = ShopifyCompletedReturnsSupport.extract(CONFIG, "refunded", WINDOW_START, WINDOW_END, [:],
                { Map cfg, String query, Map vars, Map opts -> [ok: false, errors: ["exceeds the single query max cost limit (1000)"]] })
        assertEquals([], result.records)
        assertEquals(["exceeds the single query max cost limit (1000)"], result.errors)
    }
}
