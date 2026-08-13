package shopify.reconciliation.automation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Per-order refund/return id extraction for returns reconciliation (DAR-BE-018, design §7).
 *
 * Cursor path, not bulk: refunds and returns are connections, and buildBulkQuery rejects those.
 */
class ShopifyReturnRefsSupportTests {

    @Test
    void stripsGidPrefixesFromBothRefundAndReturnIds() {
        assertEquals("1234567890", ShopifyReturnRefsSupport.bareId("gid://shopify/Refund/1234567890"))
        assertEquals("9876543210", ShopifyReturnRefsSupport.bareId("gid://shopify/Return/9876543210"))
        assertEquals("7025799037059", ShopifyReturnRefsSupport.bareId("7025799037059"))
    }

    @Test
    void emitsBothIdSetsPerOrder() {
        // NOTE: ShopifyGraphqlTransport.execute() returns [ok, data, cost, extensions, statusCode,
        // retryable] on success — "data" is top-level, there is no "body" wrapper (see parseResponse
        // in ShopifyGraphqlTransport.groovy, and the same [ok:true, data:...] shape used by every
        // sibling cursor consumer: ShopifyExchangeSweepSupport, ShopifyExchangeStateLookupSupport).
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("7025799037059",
                            ["gid://shopify/Refund/5001"],
                            ["gid://shopify/Return/9001", "gid://shopify/Return/9002"])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount)
        Map record = (Map) ((List) result.records)[0]
        assertEquals("7025799037059", record.orderId)
        assertEquals(["5001"], record.refundIds)
        assertEquals(["9001", "9002"], record.returnIds)
    }

    @Test
    void followsCursorPaginationUntilHasNextPageIsFalse() {
        int calls = 0
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            calls++
            if (calls == 1) {
                return [ok: true, data: [orders: [
                        edges   : [[cursor: "c1", node: orderNode("111", ["gid://shopify/Refund/1"], [])]],
                        pageInfo: [hasNextPage: true, endCursor: "c1"],
                ]]]
            }
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c2", node: orderNode("222", ["gid://shopify/Refund/2"], [])]],
                    pageInfo: [hasNextPage: false, endCursor: "c2"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(2, calls)
        assertEquals(2, result.recordCount)
    }

    @Test
    void emitsOrdersWithNoRefundsOrReturnsAsEmptySetsNotOmitted() {
        // An order with neither is still evidence for the reverse pass: it proves Shopify has
        // nothing to match, so an OMS return pointing at it is genuinely missing-in-Shopify.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("333", [], [])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map record = (Map) ((List) result.records)[0]
        assertEquals([], record.refundIds)
        assertEquals([], record.returnIds)
    }

    @Test
    void warnsWhenTheReturnsConnectionSaturatesItsFirstArgumentWithoutHandInjectedPageInfo() {
        // I2: live traffic NEVER carries pageInfo on the nested returns connection (renderQueryDocument
        // only emits pageInfo for the ROOT orders connection — verified against the live fixture, which
        // has "returns": {"nodes": [...]} with no pageInfo key at all). Detection must therefore fire
        // from the saturation heuristic alone; orderNode()'s returns shape below carries no pageInfo,
        // by construction, so this cannot be passing by accident on hand-injected pageInfo.
        List returnGids = ["gid://shopify/Return/1", "gid://shopify/Return/2"]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("444", [], returnGids)]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [connectionPageSize: 2], executor)

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("444") && it.toString().contains("returns") },
                "a saturated returns connection must warn even with no pageInfo present: ${warnings}")
    }

    @Test
    void warnsWhenTheRefundsListSaturatesItsFirstArgument() {
        // refunds is a PLAIN LIST (live-probed): it has NO pageInfo, so truncation at `first: N`
        // is otherwise UNDETECTABLE and would silently understate an order's refund set — which
        // manufactures false missing-in-Shopify diffs, the exact failure this extractor exists to
        // avoid. The only available signal is saturation: returned size >= requested first.
        // That over-warns on an order with exactly N refunds; a spurious warning is far cheaper
        // than a silently truncated id set.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("555",
                            ["gid://shopify/Refund/1", "gid://shopify/Refund/2"], [])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [connectionPageSize: 2], executor)

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("555") && it.toString().contains("refunds") },
                "a saturated refunds list must name the order and the field: ${warnings}")
    }

    @Test
    void saturationThresholdRespectsTheConnectionMaxPageSizeClamp() {
        // I4: ShopifyGraphqlQueryBuilder clamps refundsFirst/returnsFirst to the catalog's
        // connectionMaxPageSize (100) regardless of a wider requested connectionPageSize. If
        // detection read the RAW option (200 here) instead of the value the query actually used, it
        // would only warn at >=200 while Shopify itself truncates at 100 — switching off exactly
        // when truncation begins. 100 returned items must trip the warning even though 200 was asked
        // for.
        List returnGids = (1..100).collect { "gid://shopify/Return/${it}".toString() }
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("666", [], returnGids)]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [connectionPageSize: 200], executor)

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("666") && it.toString().contains("returns") },
                "the clamped 100 must trip saturation detection even though 200 was requested: ${warnings}")
    }

    @Test
    void excludesARefundCreatedBeforeTheWindowEvenWhenTheOrderWasUpdatedInWindow() {
        // C1: the order surfaces in the Shopify net because SOMETHING on it changed recently (an
        // updated_at bump), but the refund itself predates the window. Only the event's OWN
        // createdAt may decide inclusion — this is the exact case the original order-level window
        // could never get right.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("777",
                            ["gid://shopify/Refund/71"], [],
                            "2026-01-01T00:00:00Z",   // order createdAt: irrelevant to the new semantics
                            "2026-04-15T00:00:00Z",   // refund createdAt: BEFORE the window
                            "2026-05-01T10:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map record = (Map) ((List) result.records)[0]
        assertEquals([], record.refundIds, "an out-of-window refund must not appear: ${record}")
        assertEquals([:], record.refundsCreatedAt)
    }

    @Test
    void includesARefundCreatedInWindowEvenWhenTheOrderWasCreatedLongBefore() {
        // The inverse of the above, and the real-world common case: the order can be months old,
        // but a refund minted THIS window must still be picked up.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("888",
                            ["gid://shopify/Refund/81"], [],
                            "2025-01-01T00:00:00Z",   // order createdAt: months before the window
                            "2026-05-01T12:00:00Z",   // refund createdAt: INSIDE the window
                            "2026-05-01T10:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map record = (Map) ((List) result.records)[0]
        assertEquals(["81"], record.refundIds)
        assertEquals("2026-05-01T12:00:00Z", ((Map) record.refundsCreatedAt).get("81"))
    }

    @Test
    void windowIsHalfOpenIncludingStartButExcludingEnd() {
        Map node = [
                id              : "gid://shopify/Order/999",
                legacyResourceId: "999",
                name            : "#999",
                createdAt       : "2026-05-01T00:00:00Z",
                refunds         : [
                        [id: "gid://shopify/Refund/91", createdAt: "2026-05-01T00:00:00Z"], // == windowStart: included
                        [id: "gid://shopify/Refund/92", createdAt: "2026-05-02T00:00:00Z"], // == windowEnd: excluded
                ],
                returns         : [nodes: []],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map record = (Map) ((List) result.records)[0]
        assertEquals(["91"], record.refundIds)
    }

    @Test
    void emitsParallelCreatedAtMapsForRefundsAndReturnsKeyedByBareId() {
        // I1 shape: refundsCreatedAt/returnsCreatedAt are parallel to refundIds/returnIds, keyed by
        // the SAME bare id. This is the exact shape the other repo's reverse-grace fix consumes.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("1010",
                            ["gid://shopify/Refund/11"], ["gid://shopify/Return/21"],
                            "2026-05-01T09:00:00Z", "2026-05-01T09:15:00Z", "2026-05-01T09:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map record = (Map) ((List) result.records)[0]
        assertEquals(["11"], record.refundIds)
        assertEquals(["21"], record.returnIds)
        assertEquals("2026-05-01T09:15:00Z", ((Map) record.refundsCreatedAt).get("11"))
        assertEquals("2026-05-01T09:30:00Z", ((Map) record.returnsCreatedAt).get("21"))
    }

    @Test
    void refundOnlyOrderSurvivesTheTrimAndAppearsInOutput() {
        // C1: live-captured fixture (gorjana-sandbox.myshopify.com, Admin API 2026-01, 2026-08-13).
        // #GORTEST27948 is a refund-only order — no Return object attached — which a bare
        // `-return_status:no_return` trim silently drops (probed: 25 orders back, all with
        // returns >= 1, zero refund-only). The OR-widened trim this class builds must let it survive
        // extraction end to end.
        Map fixture = loadFixture()
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: fixture.data]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-08-12T00:00:00Z", "2026-08-13T00:00:00Z", [:], executor)

        List records = (List) result.records
        Map refundOnly = (Map) records.find { ((Map) it).orderName == "#GORTEST27948" }
        assertTrue(refundOnly != null, "the refund-only order must survive the trim: ${records}")
        assertEquals(["1005422084140"], refundOnly.refundIds)
        assertEquals([], refundOnly.returnIds)

        Map returnOnly = (Map) records.find { ((Map) it).orderName == "#GORTEST27950" }
        assertTrue(returnOnly != null, "the return-only order must also survive: ${records}")
        assertEquals([], returnOnly.refundIds)
        assertEquals(["44800213036"], returnOnly.returnIds)
    }

    @Test
    void buildsTheWidenedLowerBoundOnlySearchQueryWithUpdatedAtSortKey() {
        // Live-verified 2026-08-13 (HTTP 200): the exact search text and sort key C1 requires. No
        // upper bound — later, unrelated order activity must never hide an in-window event.
        Map captured = [:]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            captured.doc = doc
            captured.vars = vars
            return [ok: true, data: [orders: [edges: [], pageInfo: [hasNextPage: false, endCursor: null]]]]
        }

        ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        String query = captured.vars.query as String
        assertEquals("updated_at:>='2026-05-01T00:00:00Z' AND ((-return_status:no_return) OR " +
                "(financial_status:refunded) OR (financial_status:partially_refunded))", query)
        assertFalse(query.contains("updated_at:<"), "the net must carry no upper bound: ${query}")
        assertTrue((captured.doc as String).contains("sortKey: UPDATED_AT"),
                "the rendered document must sort on UPDATED_AT: ${captured.doc}")
    }

    private static Map authConfig() {
        return [shopApiUrl: "https://example.myshopify.com", apiVersion: "2025-07", accessToken: "t"]
    }

    private static Map loadFixture() {
        InputStream stream = ShopifyReturnRefsSupportTests.class.getResourceAsStream(
                "/fixtures/shopify-order-return-refs-response.json")
        return (Map) new JsonSlurper().parseText(stream.getText("UTF-8"))
    }

    private static Map orderNode(String legacyId, List refundGids, List returnGids) {
        return orderNode(legacyId, refundGids, returnGids,
                "2026-05-01T10:00:00Z", "2026-05-01T11:00:00Z", "2026-05-01T10:30:00Z")
    }

    private static Map orderNode(String legacyId, List refundGids, List returnGids,
                                 String orderCreatedAt, String refundsCreatedAt, String returnsCreatedAt) {
        return [
                id              : "gid://shopify/Order/${legacyId}".toString(),
                legacyResourceId: legacyId,
                name            : "#${legacyId}".toString(),
                createdAt       : orderCreatedAt,
                // LIVE-PROBED 2026-08-13 on API 2026-01 — the two fields are ASYMMETRIC:
                //   refunds : NON_NULL -> LIST  → a bare List of objects. No edges, no nodes, NO pageInfo.
                //   returns : NON_NULL -> ReturnConnection → { nodes: [...] }. pageInfo is never
                //             actually rendered for a nested connection (see
                //             ShopifyReturnRefsSupport.collectEvents) so it is deliberately absent here.
                // Do not make these match each other. Real captured response:
                // src/test/resources/fixtures/shopify-order-return-refs-response.json
                refunds         : refundGids.collect { [id: it, createdAt: refundsCreatedAt] },
                returns         : [nodes: returnGids.collect { [id: it, status: "CLOSED", createdAt: returnsCreatedAt] }],
        ]
    }
}
