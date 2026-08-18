package shopify.reconciliation.automation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Per-REFUND extraction for returns reconciliation (DAR-BE-018; 2026-08-17 grain-alignment plan,
 * Task 1). One record per refund: {refundId, returnId, orderId, createdAt}, windowed on the
 * refund's OWN createdAt. See ShopifyReturnRefsSupport.toRecords for the refund->return
 * association rationale (order-level pairing; Shopify's queried response carries no direct link).
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
    void emitsOneRecordPerRefundWithBareOrderAndReturnIds() {
        // NOTE: ShopifyGraphqlTransport.execute() returns [ok, data, cost, extensions, statusCode,
        // retryable] on success — "data" is top-level, there is no "body" wrapper (see parseResponse
        // in ShopifyGraphqlTransport.groovy, and the same [ok:true, data:...] shape used by every
        // sibling cursor consumer: ShopifyExchangeSweepSupport, ShopifyExchangeStateLookupSupport).
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("7025799037059",
                            ["gid://shopify/Refund/5001"],
                            ["gid://shopify/Return/9001"])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount)
        Map record = (Map) ((List) result.records)[0]
        assertEquals("7025799037059", record.orderId)
        assertEquals("5001", record.refundId)
        // Unambiguous pairing: the order carries exactly one in-window return.
        assertEquals("9001", record.returnId)
        assertEquals("2026-05-01T11:00:00Z", record.createdAt)
    }

    @Test
    void twoRefundsAndOneReturnProduceTwoRecordsEachWithItsOwnRefundIdAndCreatedAt() {
        Map node = [
                id              : "gid://shopify/Order/2020",
                legacyResourceId: "2020",
                name            : "#2020",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [
                        [id: "gid://shopify/Refund/201", createdAt: "2026-05-01T09:00:00Z"],
                        [id: "gid://shopify/Refund/202", createdAt: "2026-05-01T10:00:00Z"],
                ],
                returns         : [nodes: [[id: "gid://shopify/Return/301", status: "CLOSED", createdAt: "2026-05-01T09:05:00Z"]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(2, result.recordCount)
        List records = (List) result.records
        Map first = (Map) records.find { ((Map) it).refundId == "201" }
        Map second = (Map) records.find { ((Map) it).refundId == "202" }
        assertTrue(first != null && second != null, "both refunds must produce their own record: ${records}")
        assertEquals("2026-05-01T09:00:00Z", first.createdAt)
        assertEquals("2026-05-01T10:00:00Z", second.createdAt)
        // Single return on the order => unambiguous pairing applies to every refund on it.
        assertEquals("301", first.returnId)
        assertEquals("301", second.returnId)
    }

    @Test
    void aRefundWithAnAssociatedReturnCarriesThatReturnIdAndOneWithoutCarriesNull() {
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [
                            [cursor: "c1", node: orderNode("4001", ["gid://shopify/Refund/401"], ["gid://shopify/Return/501"])],
                            [cursor: "c2", node: orderNode("4002", ["gid://shopify/Refund/402"], [])],
                    ],
                    pageInfo: [hasNextPage: false, endCursor: "c2"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(2, result.recordCount)
        List records = (List) result.records
        Map withReturn = (Map) records.find { ((Map) it).refundId == "401" }
        Map withoutReturn = (Map) records.find { ((Map) it).refundId == "402" }
        assertEquals("501", withReturn.returnId)
        assertNull(withoutReturn.returnId, "a refund with no associated return must carry a null returnId: ${withoutReturn}")
    }

    @Test
    void anOldOrderWithARecentRefundIsEmitted() {
        // The real-world common case: the order can be months old, but a refund minted THIS window
        // must still be picked up. Windowing is on the refund's own createdAt, never the order's.
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

        assertEquals(1, result.recordCount)
        Map record = (Map) ((List) result.records)[0]
        assertEquals("81", record.refundId)
        assertEquals("2026-05-01T12:00:00Z", record.createdAt)
    }

    @Test
    void aRecentOrderWithAnOutOfWindowRefundIsNotEmitted() {
        // C1: the order surfaces in the Shopify net because SOMETHING on it changed recently (an
        // updated_at bump), but the refund itself predates the window. Only the event's OWN
        // createdAt may decide inclusion.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("777",
                            ["gid://shopify/Refund/71"], [],
                            "2026-01-01T00:00:00Z",   // order createdAt: irrelevant to windowing
                            "2026-04-15T00:00:00Z",   // refund createdAt: BEFORE the window
                            "2026-05-01T10:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(0, result.recordCount, "an out-of-window refund must not appear: ${result.records}")
    }

    @Test
    void anOrderWhoseReturnsHaveNoRefundEmitsNoRecordAndIncrementsAWarningCount() {
        // The deliberate narrowing this plan accepts: a refund-driven extract cannot emit a return
        // awaiting its refund. It must be counted and surfaced as a warning, never dropped silently.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("9999", [], ["gid://shopify/Return/8801"])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        int warningsBefore = 0
        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(0, result.recordCount, "a return with no refund must emit no record: ${result.records}")
        List warnings = (List) result.warnings
        assertEquals(warningsBefore + 1, warnings.size(), "exactly one warning must be added for the unrefunded return: ${warnings}")
        assertTrue(warnings.any { it.toString().contains("9999") && it.toString().contains("refund") },
                "the warning must name the order and mention the missing refund: ${warnings}")
    }

    @Test
    void anOrderWithNeitherRefundsNorReturnsInWindowEmitsNoRecordAndNoWarning() {
        // Unlike the order-per-record model this replaces, an empty order is no longer emitted as
        // "evidence" for a reverse pass — the target end-state is a plain flat join, which needs no
        // such evidence record. No return exists here either, so this is not the no-refund narrowing:
        // there is nothing to warn about.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("333", [], [])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(0, result.recordCount)
        assertEquals([], result.warnings)
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
        List refundIds = ((List) result.records).collect { ((Map) it).refundId }
        assertEquals(["1", "2"] as Set, refundIds as Set)
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

        assertEquals(1, result.recordCount)
        assertEquals("91", ((Map) ((List) result.records)[0]).refundId)
    }

    @Test
    void refundOnlyOrderProducesARecordAndReturnOnlyOrderProducesOnlyAWarning() {
        // C1: live-captured fixture (gorjana-sandbox.myshopify.com, Admin API 2026-01, 2026-08-13).
        // #GORTEST27948 is a refund-only order — no Return object attached — which a bare
        // `-return_status:no_return` trim silently drops (probed: 25 orders back, all with
        // returns >= 1, zero refund-only). The OR-widened trim this class builds must let it survive
        // extraction end to end, now as a refund record with a null returnId.
        // #GORTEST27950 is return-only: under the refund-driven grain it can no longer be
        // represented as a Shopify record at all — it must surface as the no-refund-yet warning.
        Map fixture = loadFixture()
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: fixture.data]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-08-12T00:00:00Z", "2026-08-13T00:00:00Z", [:], executor)

        List records = (List) result.records
        assertEquals(1, records.size(), "only the refund-only order should produce a record: ${records}")
        Map refundOnly = (Map) records[0]
        assertEquals("1005422084140", refundOnly.refundId)
        assertEquals("6942591025196", refundOnly.orderId)
        assertNull(refundOnly.returnId, "a refund-only order has no return to pair with: ${refundOnly}")

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("6942747197484") && it.toString().contains("refund") },
                "the return-only order must surface as a no-refund-yet warning, not a silently dropped record: ${warnings}")
    }

    @Test
    void buildsTheWidenedLowerBoundOnlySearchQueryWithUpdatedAtSortKey() {
        // Live-verified 2026-08-13 (HTTP 200): the exact search text and sort key C1 requires. No
        // upper bound — later, unrelated order activity must never hide an in-window event. The
        // floor itself is windowStart minus the default 3h lookback (Important #3, fix-wave-C
        // re-review): OMS lags Shopify by ~38min (RQ-23), so the net must reach back far enough that
        // a Shopify event just before windowStart is still fetched for the OMS-side forward match.
        Map captured = [:]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            captured.doc = doc
            captured.vars = vars
            return [ok: true, data: [orders: [edges: [], pageInfo: [hasNextPage: false, endCursor: null]]]]
        }

        ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        String query = captured.vars.query as String
        assertEquals("updated_at:>='2026-04-30T21:00:00Z' AND ((-return_status:no_return) OR " +
                "(financial_status:refunded) OR (financial_status:partially_refunded))", query)
        assertFalse(query.contains("updated_at:<"), "the net must carry no upper bound: ${query}")
        assertTrue((captured.doc as String).contains("sortKey: UPDATED_AT"),
                "the rendered document must sort on UPDATED_AT: ${captured.doc}")
    }

    @Test
    void lookbackHoursOptionOverridesTheDefaultAndMovesTheNetFloor() {
        Map captured = [:]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            captured.vars = vars
            return [ok: true, data: [orders: [edges: [], pageInfo: [hasNextPage: false, endCursor: null]]]]
        }

        ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [lookbackHours: 1], executor)

        String query = captured.vars.query as String
        assertTrue(query.startsWith("updated_at:>='2026-04-30T23:00:00Z'"),
                "a caller-supplied lookbackHours must move the net floor: ${query}")
    }

    @Test
    void includesARefundCreatedWithinTheDefaultLookbackBeforeWindowStart() {
        // Important #3: OMS lags Shopify by ~38min (RQ-23) -- widen the fetch/emit floor by the
        // default 3h lookback so a refund minted just before windowStart is still available for the
        // OMS-side forward match once the (later-arriving) OMS return shows up just inside the window.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("1111",
                            ["gid://shopify/Refund/111"], [],
                            "2026-01-01T00:00:00Z",
                            "2026-04-30T22:00:00Z",   // 2h before windowStart -- inside the 3h lookback
                            "2026-05-01T10:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "a refund within the lookback before windowStart must be included: ${result.records}")
        assertEquals("111", ((Map) ((List) result.records)[0]).refundId)
    }

    @Test
    void excludesARefundCreatedBeforeTheLookbackFloor() {
        // The lookback is bounded -- an event further back than lookbackHours must still be
        // excluded, exactly like the pre-fix window boundary was.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("1112",
                            ["gid://shopify/Refund/112"], [],
                            "2026-01-01T00:00:00Z",
                            "2026-04-30T20:00:00Z",   // 4h before windowStart -- outside the 3h lookback
                            "2026-05-01T10:30:00Z")]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(0, result.recordCount, "a refund before the lookback floor must still be excluded: ${result.records}")
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
