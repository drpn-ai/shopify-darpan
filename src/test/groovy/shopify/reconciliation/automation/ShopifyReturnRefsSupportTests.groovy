package shopify.reconciliation.automation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Per-EVENT extraction for returns reconciliation (DAR-BE-018; 2026-08-17 grain-alignment plan,
 * Task 1, REVISION 2026-08-18). One record per EVENT — a refund OR a return — each windowed on
 * that event's OWN createdAt: {eventId, eventType: "REFUND"|"RETURN", orderId, createdAt}.
 *
 * This shape replaced an intermediate one-record-per-REFUND shape (with a nullable returnId) once
 * the product owner confirmed OMS `externalId` is usually a Shopify refund id but sometimes a
 * return id instead — a refund-only Shopify side had no fallback for that minority and would
 * silently report those OMS rows missing-in-Shopify. Emitting both refunds and returns as their
 * own same-shaped rows removes the need for any precedence rule: eventId is the single join key,
 * whichever kind of id it is. See ShopifyReturnRefsSupport.toRecords for the full history.
 *
 * REFUNDED-RETURN NARROWING (2026-08-18): a refunded return is represented by its REFUND row alone
 * — the return itself does not also get a RETURN row, since OMS gets exactly one counterpart for it
 * (keyed by the refund id). The discriminator is Return.refunds (does this return have at least one
 * refund), NOT returns.nodes.status — status was investigated and rejected first (Shopify's own
 * returnClose mutation doc confirms a return can reach CLOSED with zero refunds, and nothing
 * prevents a refund existing while a return stays OPEN). See ShopifyReturnRefsSupport.toRecords for
 * the full evidence.
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
    void anOrderWithTwoRefundsAndOneReturnProducesThreeRecordsTwoRefundOneReturn() {
        // The defining case for the per-event shape: every refund AND every return on the order
        // gets its own row. eventType and eventId (that specific event's own id) must be correct on
        // each, and createdAt must be that event's own date, not the order's or any other event's.
        // The return's own `refunds` is explicitly empty — it is UNREFUNDED, distinct from either of
        // the two order-level refunds (201, 202), so the narrowing does not suppress its row.
        Map node = [
                id              : "gid://shopify/Order/2020",
                legacyResourceId: "2020",
                name            : "#2020",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [
                        [id: "gid://shopify/Refund/201", createdAt: "2026-05-01T09:00:00Z"],
                        [id: "gid://shopify/Refund/202", createdAt: "2026-05-01T10:00:00Z"],
                ],
                returns         : [nodes: [[id: "gid://shopify/Return/301", status: "CLOSED", createdAt: "2026-05-01T09:05:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(3, result.recordCount)
        List records = (List) result.records
        Map refund201 = (Map) records.find { ((Map) it).eventId == "201" }
        Map refund202 = (Map) records.find { ((Map) it).eventId == "202" }
        Map return301 = (Map) records.find { ((Map) it).eventId == "301" }
        assertTrue(refund201 != null && refund202 != null && return301 != null,
                "two REFUND records and one RETURN record are expected: ${records}")

        assertEquals("REFUND", refund201.eventType)
        assertEquals("2026-05-01T09:00:00Z", refund201.createdAt)
        assertEquals("2020", refund201.orderId)

        assertEquals("REFUND", refund202.eventType)
        assertEquals("2026-05-01T10:00:00Z", refund202.createdAt)

        assertEquals("RETURN", return301.eventType)
        assertEquals("2026-05-01T09:05:00Z", return301.createdAt)
        assertEquals("2020", return301.orderId)

        // No record ever carries a refundId or returnId field any more — eventId/eventType replace
        // both entirely.
        records.each { Object raw ->
            Map record = (Map) raw
            assertFalse(record.containsKey("refundId"), "refundId must not appear on any record: ${record}")
            assertFalse(record.containsKey("returnId"), "returnId must not appear on any record: ${record}")
        }
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
        assertEquals("81", record.eventId)
        assertEquals("REFUND", record.eventType)
        assertEquals("2026-05-01T12:00:00Z", record.createdAt)
    }

    @Test
    void aRecentOrderWithAnOutOfWindowReturnIsNotEmitted() {
        // The mirror of the refund case above, on the RETURN side: the order surfaces in the
        // Shopify net because something on it changed recently, but the return itself predates the
        // window. Only the event's OWN createdAt may decide inclusion — this applies to returns
        // exactly as it does to refunds now that both are windowed and emitted the same way.
        Map node = [
                id              : "gid://shopify/Order/779",
                legacyResourceId: "779",
                name            : "#779",
                createdAt       : "2026-01-01T00:00:00Z",   // order createdAt: irrelevant to windowing
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/791", status: "CLOSED",
                                             createdAt: "2026-04-15T00:00:00Z"]]],   // BEFORE the window
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(0, result.recordCount, "an out-of-window return must not appear: ${result.records}")
    }

    @Test
    void aRecentOrderWithAnOutOfWindowRefundIsNotEmitted() {
        // C1: the refund-side counterpart of the return case above.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("777",
                            ["gid://shopify/Refund/71"], [],
                            "2026-01-01T00:00:00Z",
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
    void aReturnWithNoRefundEmitsItsOwnReturnRow() {
        // This is the case that closes the false-positive gap this revision exists for: previously
        // (the per-refund shape) a return with no refund yet emitted NOTHING and only a warning. An
        // OMS row whose externalId happened to be this return's id would then have no Shopify row to
        // join against at all and would misreport as missing-in-Shopify. Now it gets a genuine RETURN
        // row, no warning needed, nothing dropped.
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: orderNode("9999", [], ["gid://shopify/Return/8801"])]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "a return with no refund must still emit its own row: ${result.records}")
        Map record = (Map) ((List) result.records)[0]
        assertEquals("8801", record.eventId)
        assertEquals("RETURN", record.eventType)
        assertEquals("9999", record.orderId)
        // No narrowing warning any more — nothing was dropped, so there is nothing to disclose.
        assertEquals([], result.warnings)
    }

    @Test
    void aRefundedReturnEmitsNoReturnRowOnlyItsRefundsRow() {
        // The narrowing itself: a return with at least one refund (per Return.refunds) is skipped —
        // it is represented by its refund's own REFUND row alone, matching the OMS contract (one
        // counterpart per return, keyed by the refund id). The refund 911 is deliberately present
        // BOTH as an order-level refund (its own row) AND nested under the return's own `refunds`
        // (the existence signal) — exactly how live Shopify data would actually look for a return
        // that has since been refunded.
        Map node = [
                id              : "gid://shopify/Order/9001",
                legacyResourceId: "9001",
                name            : "#9001",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [[id: "gid://shopify/Refund/911", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [[id: "gid://shopify/Return/912", status: "CLOSED", createdAt: "2026-05-01T08:30:00Z",
                                             refunds: [[id: "gid://shopify/Refund/911"]]]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "a refunded return must emit only its refund's row: ${result.records}")
        Map record = (Map) ((List) result.records)[0]
        assertEquals("911", record.eventId)
        assertEquals("REFUND", record.eventType)
        assertTrue(((List) result.records).every { ((Map) it).eventId != "912" },
                "the refunded return itself must not also appear as a RETURN row: ${result.records}")
    }

    @Test
    void anUnrefundedReturnWithAnEmptyRefundsConnectionEmitsAReturnRow() {
        // The other half of the narrowing: Return.refunds explicitly present but EMPTY (not merely
        // absent from the payload) must still be read as "no refund" and emit a RETURN row.
        Map node = [
                id              : "gid://shopify/Order/9002",
                legacyResourceId: "9002",
                name            : "#9002",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/922", status: "OPEN", createdAt: "2026-05-01T08:30:00Z",
                                             refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "an unrefunded return must still emit its own row: ${result.records}")
        Map record = (Map) ((List) result.records)[0]
        assertEquals("922", record.eventId)
        assertEquals("RETURN", record.eventType)
    }

    @Test
    void anOrderWithOneRefundedReturnAndOneUnrefundedReturnEmitsExactlyTwoRows() {
        // The mixed case: one return already has a refund (911, skipped — represented by its refund's
        // row), the other does not (932, gets its own RETURN row). Exactly one REFUND row and one
        // RETURN row, no more.
        Map node = [
                id              : "gid://shopify/Order/9003",
                legacyResourceId: "9003",
                name            : "#9003",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [[id: "gid://shopify/Refund/931", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/930", status: "CLOSED", createdAt: "2026-05-01T08:20:00Z",
                         refunds: [[id: "gid://shopify/Refund/931"]]],
                        [id: "gid://shopify/Return/932", status: "OPEN", createdAt: "2026-05-01T08:40:00Z",
                         refunds: []],
                ]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(2, result.recordCount, "exactly one REFUND row and one RETURN row are expected: ${result.records}")
        List records = (List) result.records
        assertTrue(records.any { ((Map) it).eventId == "931" && ((Map) it).eventType == "REFUND" })
        assertTrue(records.any { ((Map) it).eventId == "932" && ((Map) it).eventType == "RETURN" })
        assertTrue(records.every { ((Map) it).eventId != "930" }, "the refunded return (930) must not also appear: ${records}")
    }

    @Test
    void statusDoesNotAffectTheOutcomeAClosedReturnWithNoRefundsStillEmitsAReturnRow() {
        // Investigated 2026-08-18: a narrowing using returns.nodes.status as the discriminator was
        // considered and REJECTED on direct evidence (see ShopifyReturnRefsSupport.toRecords doc):
        // Shopify's own returnClose mutation doc confirms a return can reach CLOSED with zero refunds
        // ("simply when it has been marked as returned in the system"), and nothing prevents a refund
        // existing while a return stays OPEN. Return.refunds — not status — is the actual gate. This
        // test locks that in: a CLOSED return with an explicitly empty refunds connection must still
        // emit its RETURN row, proving CLOSED is not read as "has a refund".
        Map node = [
                id              : "gid://shopify/Order/9004",
                legacyResourceId: "9004",
                name            : "#9004",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/942", status: "CLOSED", createdAt: "2026-05-01T08:30:00Z",
                                             refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount,
                "a CLOSED return with no refunds must still emit its RETURN row — status is not the gate: ${result.records}")
        Map record = (Map) ((List) result.records)[0]
        assertEquals("942", record.eventId)
        assertEquals("RETURN", record.eventType)
    }

    @Test
    void anOrderWithNeitherRefundsNorReturnsInWindowEmitsNoRecordAndNoWarning() {
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
        List eventIds = ((List) result.records).collect { ((Map) it).eventId }
        assertEquals(["1", "2"] as Set, eventIds as Set)
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
        assertEquals("91", ((Map) ((List) result.records)[0]).eventId)
    }

    @Test
    void refundOnlyAndReturnOnlyOrdersBothProduceRecordsUnderTheEventShape() {
        // C1: live-captured fixture (gorjana-sandbox.myshopify.com, Admin API 2026-01, 2026-08-13).
        // #GORTEST27948 is a refund-only order — no Return object attached — which a bare
        // `-return_status:no_return` trim silently drops (probed: 25 orders back, all with
        // returns >= 1, zero refund-only). The OR-widened trim this class builds must let it survive
        // extraction end to end, as a REFUND event row.
        // #GORTEST27950 is return-only. Under the earlier per-refund shape this produced no record at
        // all, only a warning — exactly the gap this revision closes: it now produces its own RETURN
        // event row directly. Its return's `refunds: []` is a 2026-08-18 SYNTHESIZED addition (the
        // original probe never selected Return.refunds — see the fixture's own `_returnRefundsField`
        // note), consistent with this order's live-captured, unchanged order-level `refunds: []`.
        Map fixture = loadFixture()
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: fixture.data]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-08-12T00:00:00Z", "2026-08-13T00:00:00Z", [:], executor)

        List records = (List) result.records
        assertEquals(2, records.size(), "both the refund-only and the return-only order must each produce one record: ${records}")

        Map refundEvent = (Map) records.find { ((Map) it).orderId == "6942591025196" }
        assertTrue(refundEvent != null, "the refund-only order must produce a REFUND event: ${records}")
        assertEquals("1005422084140", refundEvent.eventId)
        assertEquals("REFUND", refundEvent.eventType)

        Map returnEvent = (Map) records.find { ((Map) it).orderId == "6942747197484" }
        assertTrue(returnEvent != null, "the return-only order must now produce a RETURN event, not just a warning: ${records}")
        assertEquals("44800213036", returnEvent.eventId)
        assertEquals("RETURN", returnEvent.eventType)
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
        assertEquals("111", ((Map) ((List) result.records)[0]).eventId)
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
