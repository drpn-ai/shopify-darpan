package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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
    void warnsWhenTheReturnsConnectionIsTruncated() {
        // Silent truncation would read as "this order has only these returns" and manufacture
        // false missing-in-Shopify diffs. It must be loud. returns IS a connection, so it carries
        // pageInfo and truncation is directly detectable.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            Map node = orderNode("444", [], ["gid://shopify/Return/1"])
            ((Map) node.returns).put("pageInfo", [hasNextPage: true])
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("444") },
                "a truncated connection must name the order: ${warnings}")
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

    private static Map authConfig() {
        return [shopApiUrl: "https://example.myshopify.com", apiVersion: "2025-07", accessToken: "t"]
    }

    private static Map orderNode(String legacyId, List refundGids, List returnGids) {
        return [
                id              : "gid://shopify/Order/${legacyId}".toString(),
                legacyResourceId: legacyId,
                name            : "#${legacyId}".toString(),
                createdAt       : "2026-05-01T10:00:00Z",
                // LIVE-PROBED 2026-08-13 on API 2026-01 — the two fields are ASYMMETRIC:
                //   refunds : NON_NULL -> LIST  → a bare List of objects. No edges, no nodes, NO pageInfo.
                //   returns : NON_NULL -> ReturnConnection → { nodes: [...], pageInfo: {...} }
                // Do not make these match each other. Real captured response:
                // src/test/resources/fixtures/shopify-order-return-refs-response.json
                refunds         : refundGids.collect { [id: it, createdAt: "2026-05-01T11:00:00Z"] },
                returns         : [nodes: returnGids.collect { [id: it, status: "CLOSED", createdAt: "2026-05-01T10:30:00Z"] },
                                   pageInfo: [hasNextPage: false]],
        ]
    }
}
