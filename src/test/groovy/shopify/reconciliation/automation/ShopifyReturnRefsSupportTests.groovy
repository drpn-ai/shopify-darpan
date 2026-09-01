package shopify.reconciliation.automation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Per-EVENT extraction for returns reconciliation (DAR-BE-018; 2026-08-17 grain-alignment plan,
 * Task 1, REVISION 2026-08-18). One record per EVENT — a refund OR a return — each windowed on
 * that event's OWN createdAt: {refundOrReturnId, refundOrReturnType: "REFUND"|"RETURN", orderId, createdAt}.
 *
 * This shape replaced an intermediate one-record-per-REFUND shape (with a nullable returnId) once
 * the product owner confirmed OMS `externalId` is usually a Shopify refund id but sometimes a
 * return id instead — a refund-only Shopify side had no fallback for that minority and would
 * silently report those OMS rows missing-in-Shopify. Emitting both refunds and returns as their
 * own same-shaped rows removes the need for any precedence rule: refundOrReturnId is the single join key,
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
 * CLOSED-UNREFUNDED SUPPRESSION (2026-09-01, DAR-BE-027): the mirror of the narrowing above — a
 * return that FINISHED and never got a refund is dropped too. OMS books a return keyed on the refund,
 * so an unrefunded one has no counterpart and never had one. That is why the paragraph above is not
 * contradicted by it: status still does not decide "is this refunded" (Return.refunds does), it
 * decides "is this finished", and the two rules answer different questions. Only CLOSED qualifies —
 * OPEN, REQUESTED, DECLINED and CANCELED all still emit, each pinned by its own test below.
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
        // gets its own row. refundOrReturnType and refundOrReturnId (that specific event's own id) must be correct on
        // each, and createdAt must be that event's own date, not the order's or any other event's.
        // The return's own `refunds` is explicitly empty — it is UNREFUNDED, distinct from either of
        // the two order-level refunds (201, 202), so the narrowing does not suppress its row.
        // Status is OPEN and that is now load-bearing rather than arbitrary: it was CLOSED until
        // 2026-09-01, chosen when status meant nothing here, and DAR-BE-027 gave it meaning. This
        // test is about the per-event GRAIN, so its return must be one that survives both narrowings.
        Map node = [
                id              : "gid://shopify/Order/2020",
                legacyResourceId: "2020",
                name            : "#2020",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [
                        [id: "gid://shopify/Refund/201", createdAt: "2026-05-01T09:00:00Z"],
                        [id: "gid://shopify/Refund/202", createdAt: "2026-05-01T10:00:00Z"],
                ],
                returns         : [nodes: [[id: "gid://shopify/Return/301", status: "OPEN", createdAt: "2026-05-01T09:05:00Z", refunds: []]]],
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
        Map refund201 = (Map) records.find { ((Map) it).refundOrReturnId == "201" }
        Map refund202 = (Map) records.find { ((Map) it).refundOrReturnId == "202" }
        Map return301 = (Map) records.find { ((Map) it).refundOrReturnId == "301" }
        assertTrue(refund201 != null && refund202 != null && return301 != null,
                "two REFUND records and one RETURN record are expected: ${records}")

        assertEquals("REFUND", refund201.refundOrReturnType)
        assertEquals("2026-05-01T09:00:00Z", refund201.createdAt)
        assertEquals("2020", refund201.orderId)

        assertEquals("REFUND", refund202.refundOrReturnType)
        assertEquals("2026-05-01T10:00:00Z", refund202.createdAt)

        assertEquals("RETURN", return301.refundOrReturnType)
        assertEquals("2026-05-01T09:05:00Z", return301.createdAt)
        assertEquals("2020", return301.orderId)

        // No record ever carries a refundId or returnId field any more — refundOrReturnId/refundOrReturnType replace
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
        assertEquals("81", record.refundOrReturnId)
        assertEquals("REFUND", record.refundOrReturnType)
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
        assertEquals("8801", record.refundOrReturnId)
        assertEquals("RETURN", record.refundOrReturnType)
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
        assertEquals("911", record.refundOrReturnId)
        assertEquals("REFUND", record.refundOrReturnType)
        assertTrue(((List) result.records).every { ((Map) it).refundOrReturnId != "912" },
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
        assertEquals("922", record.refundOrReturnId)
        assertEquals("RETURN", record.refundOrReturnType)
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
        assertTrue(records.any { ((Map) it).refundOrReturnId == "931" && ((Map) it).refundOrReturnType == "REFUND" })
        assertTrue(records.any { ((Map) it).refundOrReturnId == "932" && ((Map) it).refundOrReturnType == "RETURN" })
        assertTrue(records.every { ((Map) it).refundOrReturnId != "930" }, "the refunded return (930) must not also appear: ${records}")
    }

    @Test
    void aClosedReturnWithNoRefundIsSuppressedBecauseItNeverSyncedToOms() {
        // DAR-BE-027, 2026-09-01. THIS TEST IS THE INVERSE OF THE ONE IT REPLACED, on purpose — read
        // this before "restoring" the old behaviour.
        //
        // What it used to assert, and why that was right at the time: a 2026-08-18 narrowing had
        // considered returns.nodes.status as a REFUNDED/UNREFUNDED discriminator and rejected it, on
        // Shopify's own returnClose doc ("either when a refund has been made and items restocked, or
        // simply when it has been marked as returned in the system"). CLOSED does not imply refunded,
        // so reading it as "has a refund" would have dropped an exchange-resolved return that OMS was
        // believed to expect. Return.refunds is still that discriminator and is untouched here.
        //
        // What changed is the OMS-side fact, not the Shopify-side one. OMS books a return keyed on the
        // REFUND. A closed return carrying no refund therefore has no OMS counterpart and never did —
        // there is nothing for it to be missing from. Originating evidence is a live RAILS row,
        // return 35442393257 on order 7245977551017: the app that store uses creates the Shopify
        // Return and its refund as separate, unlinked objects, so the return reaches this extractor
        // with an empty refunds connection and is reported missing-in-OMS on every single run. A
        // permanent structural false positive, not a timing gap.
        //
        // Deliberately still reported (see the two tests below): OPEN, DECLINED and CANCELED returns.
        // Scope is CLOSED only, so a second class of false positive would stay VISIBLE rather than be
        // silently explained away by a rule that had quietly grown.
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

        assertEquals(0, result.recordCount,
                "a CLOSED return carrying no refund has no OMS counterpart and must not be emitted: ${result.records}")
    }

    @Test
    void aDeclinedOrCanceledReturnWithNoRefundStillEmitsItsRow() {
        // The scope boundary, pinned so it cannot drift by accident. DECLINED and CANCELED returns
        // arguably never sync to OMS either — by the same argument that suppresses CLOSED — but that
        // is inference, and no such row has been observed. Aditi scoped this to CLOSED only
        // (2026-09-01) so those two stay reported: a false positive you can see is a bug report, a
        // false positive a rule silently ate is not. Widening is a decision, not a tidy-up.
        Map node = [
                id              : "gid://shopify/Order/9005",
                legacyResourceId: "9005",
                name            : "#9005",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/951", status: "DECLINED", createdAt: "2026-05-01T08:30:00Z", refunds: []],
                        [id: "gid://shopify/Return/952", status: "CANCELED", createdAt: "2026-05-01T08:40:00Z", refunds: []],
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

        assertEquals(2, result.recordCount,
                "only CLOSED is suppressed; DECLINED and CANCELED must stay visible: ${result.records}")
        List records = (List) result.records
        assertTrue(records.any { ((Map) it).refundOrReturnId == "951" })
        assertTrue(records.any { ((Map) it).refundOrReturnId == "952" })
    }

    @Test
    void theClosedUnrefundedSuppressionIsCountedInTheRequestMetadata() {
        // Not silent. The 2026-08-18 refunded-return narrowing suppresses rows with no count anywhere,
        // which makes "the rule fired" and "there was nothing to fire on" the same observation from
        // outside. This one reports what it dropped, so a returns-count fall after deploy can be
        // attributed instead of investigated.
        Map node = [
                id              : "gid://shopify/Order/9006",
                legacyResourceId: "9006",
                name            : "#9006",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/961", status: "CLOSED", createdAt: "2026-05-01T08:30:00Z", refunds: []],
                        [id: "gid://shopify/Return/962", status: "CLOSED", createdAt: "2026-05-01T08:40:00Z", refunds: []],
                        [id: "gid://shopify/Return/963", status: "OPEN", createdAt: "2026-05-01T08:50:00Z", refunds: []],
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

        assertEquals(1, result.recordCount, "only the OPEN return survives: ${result.records}")
        Map metadataFilters = (Map) ((Map) result.requestMetadata).get("filters")
        assertEquals(2, metadataFilters.get("closedUnrefundedReturnsSuppressed"),
                "the suppression must report its own count: ${metadataFilters}")
    }

    @Test
    void theSuppressionCountIsAlwaysPresentEvenWhenNothingWasSuppressed() {
        // Same reasoning as configuredExclusions' zero-count entries just below: an ABSENT key reads
        // as "this build does not have the rule", which is exactly the ambiguity that made the
        // withdrawn IN_PROGRESS pill undiagnosable. Zero must be stated, not implied.
        Map node = [
                id              : "gid://shopify/Order/9007",
                legacyResourceId: "9007",
                name            : "#9007",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/971", status: "OPEN",
                                             createdAt: "2026-05-01T08:30:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map metadataFilters = (Map) ((Map) result.requestMetadata).get("filters")
        assertEquals(0, metadataFilters.get("closedUnrefundedReturnsSuppressed"),
                "a run that suppressed nothing must still say so: ${metadataFilters}")
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
        List refundOrReturnIds = ((List) result.records).collect { ((Map) it).refundOrReturnId }
        assertEquals(["1", "2"] as Set, refundOrReturnIds as Set)
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
        assertEquals("91", ((Map) ((List) result.records)[0]).refundOrReturnId)
    }

    @Test
    void refundOnlyOrderSurvivesTheWidenedTrimWhileTheCapturedClosedUnrefundedReturnIsSuppressed() {
        // C1: live-captured fixture (gorjana-sandbox.myshopify.com, Admin API 2026-01, 2026-08-13).
        // #GORTEST27948 is a refund-only order — no Return object attached — which a bare
        // `-return_status:no_return` trim silently drops (probed: 25 orders back, all with
        // returns >= 1, zero refund-only). The OR-widened trim this class builds must let it survive
        // extraction end to end, as a REFUND event row. That half is unchanged and is still the point.
        //
        // THE OTHER HALF INVERTED ON 2026-09-01 (DAR-BE-027), and this fixture is the best evidence
        // in the suite for why, BECAUSE IT IS REAL: #GORTEST27950 is return-only, and its return's
        // status — CLOSED — is live-captured, not invented. Its `refunds: []` is a 2026-08-18
        // synthesized addition (the original probe never selected Return.refunds; see the fixture's
        // own `_returnRefundsField` note) consistent with the order's live, unchanged `refunds: []`.
        // So a closed return carrying no refund is not a RAILS peculiarity — gorjana produced one on
        // the day this fixture was captured. Under DAR-BE-018 it emitted a RETURN row; OMS books a
        // return keyed on the REFUND, so that row had no counterpart to match and reported
        // missing-in-OMS forever. It is now suppressed, and the fixture is deliberately left
        // BYTE-FOR-BYTE UNCHANGED — editing captured data to make an assertion pass would destroy the
        // only live evidence this test carries.
        Map fixture = loadFixture()
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: fixture.data]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-08-12T00:00:00Z", "2026-08-13T00:00:00Z", [:], executor)

        List records = (List) result.records
        assertEquals(1, records.size(), "only the refund-only order still produces a row: ${records}")

        Map refundEvent = (Map) records.find { ((Map) it).orderId == "6942591025196" }
        assertTrue(refundEvent != null, "the refund-only order must produce a REFUND event: ${records}")
        assertEquals("1005422084140", refundEvent.refundOrReturnId)
        assertEquals("REFUND", refundEvent.refundOrReturnType)

        assertTrue(records.every { ((Map) it).orderId != "6942747197484" },
                "the captured CLOSED unrefunded return has no OMS counterpart and must not be emitted: ${records}")
        Map metadataFilters = (Map) ((Map) result.requestMetadata).filters
        assertEquals(1, metadataFilters.get("closedUnrefundedReturnsSuppressed"),
                "and the run must say so rather than just showing one fewer row: ${metadataFilters}")

        // This fixture was captured 2026-08-13, before Order.returnStatus was selected, so its nodes
        // carry no returnStatus at all. That is exactly the "extract predates the field" case the key
        // must stay PRESENT for: absent key and null value are different answers, and a stored
        // artifact from before the field must not read as "Shopify said nothing".
        assertTrue(refundEvent.containsKey("orderReturnStatus"), "key must be present: ${refundEvent}")
        assertNull(refundEvent.orderReturnStatus)
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
        assertEquals("111", ((Map) ((List) result.records)[0]).refundOrReturnId)
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

    @Test
    void neitherRetiredPerReturnStatusKeyIsEmittedAnyMore() {
        // WITHDRAWN 2026-09-01 (DAR-BE-026). This endpoint carried a per-return status key twice —
        // first `returnStatus` (2026-08-27, named after Shopify's ORDER-level field but holding
        // Return.status), then `returnWorkflowStatus` (renamed hours later to stop that collision).
        // Both are gone. The board now offers ONE status field, orderReturnStatus, holding Shopify's
        // own Order.returnStatus — the value operators actually see and search on.
        //
        // Asserting both old keys are ABSENT is the load-bearing half: a stale rule naming either one
        // must not keep silently matching nothing, and firstMatchingRule keeps any record whose field
        // is absent, so a lingering key would be a permanent silent no-op.
        Map node = [
                id              : "gid://shopify/Order/7005",
                legacyResourceId: "7005",
                name            : "#7005",
                createdAt       : "2026-05-01T08:00:00Z",
                returnStatus    : "IN_PROGRESS",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/705", status: "OPEN",
                                             createdAt: "2026-05-01T09:30:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        Map returnRow = (Map) ((List) result.records).find { ((Map) it).refundOrReturnId == "705" }
        assertNotNull(returnRow, "the return row is expected: ${result.records}")
        assertEquals("IN_PROGRESS", returnRow.orderReturnStatus)
        assertFalse(returnRow.containsKey("returnWorkflowStatus"),
                "the 2026-08-27 key must be gone: ${returnRow}")
        assertFalse(returnRow.containsKey("returnStatus"),
                "the original colliding key must stay gone too: ${returnRow}")
    }

    @Test
    void aReturnWhoseStatusShopifyOmittedStillEmitsItsRowWithANullStatus() {
        // Fail toward EMITTING, matching this class's established bias everywhere else (see inWindow's
        // unparseable-createdAt handling): a missing status must never suppress a row. A null
        // orderReturnStatus is also what makes such a row un-excludable by any status rule, since
        // firstMatchingRule skips a blank candidate — the safe direction.
        Map node = [
                id              : "gid://shopify/Order/7002",
                legacyResourceId: "7002",
                name            : "#7002",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/703",
                                             createdAt: "2026-05-01T09:30:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "a status-less return must still emit its row: ${result.records}")
        Map record = (Map) ((List) result.records)[0]
        assertEquals("703", record.refundOrReturnId)
        assertNull(record.orderReturnStatus)
    }

    @Test
    void anOrderReturnStatusExclusionDropsEveryRowOfAMatchingOrderIncludingItsRefunds() {
        // The feature, restated for the order-level field (DAR-BE-026): an operator excludes
        // IN_PROGRESS and every row belonging to an order whose return has not finished is dropped.
        //
        // This is a DELIBERATE widening over the per-return field it replaces. orderReturnStatus is an
        // order-wide aggregate, so it lands on refund rows too — which is the point: a refund row
        // carries no per-return status, so under the old field it was unreachable by any rule. The
        // trade is real and was accepted knowingly: a refund on an in-progress order is dropped along
        // with the return, so if OMS holds that refund it will now report missing-in-Shopify.
        //
        // Both nodes are the live shapes probed on gorjana 2026-09-01: an IN_PROGRESS order carrying a
        // refund plus an unrefunded OPEN return, and a RETURNED order that must survive untouched.
        Map inProgress = [
                id              : "gid://shopify/Order/8001",
                legacyResourceId: "8001",
                name            : "#8001",
                createdAt       : "2026-05-01T08:00:00Z",
                returnStatus    : "IN_PROGRESS",
                refunds         : [[id: "gid://shopify/Refund/801", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/802", status: "OPEN", createdAt: "2026-05-01T09:10:00Z", refunds: []],
                ]],
        ]
        Map finished = [
                id              : "gid://shopify/Order/8010",
                legacyResourceId: "8010",
                name            : "#8010",
                createdAt       : "2026-05-01T08:00:00Z",
                returnStatus    : "RETURNED",
                refunds         : [[id: "gid://shopify/Refund/811", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/812", status: "CLOSED", createdAt: "2026-05-01T09:10:00Z", refunds: []],
                ]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: inProgress], [cursor: "c2", node: finished]],
                    pageInfo: [hasNextPage: false, endCursor: "c2"],
            ]]]
        }
        // fieldExpression is the BARE record field, not the stored JSONPath: callers reduce it via
        // SourceFilterSupport.toRecordFieldRules before dispatch (AutomationRuntimeSupport,
        // ReconciliationSavedRunSupport). The getter must never reduce it a second time.
        List<Map<String, Object>> filters = [[sequenceNum: 1, fieldExpression: "orderReturnStatus",
                                              operator: "EXCLUDE_IN", filterValues: "IN_PROGRESS"]]

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [sourceFilters: filters], executor)

        List records = (List) result.records
        // TWO RULES ARE RUNNING HERE and the counts are what tell them apart — read this before
        // "fixing" the number below back to 2. The control order 8010 is RETURNED with a CLOSED,
        // unrefunded return, which is a realistic Shopify shape and also exactly the one DAR-BE-027
        // suppresses, so its return row (812) is gone for a reason that has nothing to do with the
        // exclusion under test. The exclusion's own excludedCount still says 2, proving it took the
        // in-progress order's two rows and nothing else.
        assertEquals(1, result.recordCount, "the RETURNED order's refund is the only survivor: ${records}")
        assertTrue(records.any { ((Map) it).refundOrReturnId == "811" }, "its refund must survive: ${records}")
        assertTrue(records.every { ((Map) it).refundOrReturnId != "801" },
                "the in-progress order's REFUND row must be excluded too — the widening this field exists for: ${records}")
        assertTrue(records.every { ((Map) it).refundOrReturnId != "802" },
                "the in-progress order's return must be excluded: ${records}")

        Map metadataFilters = (Map) ((Map) result.requestMetadata).filters
        List configured = (List) metadataFilters.configuredExclusions
        assertEquals(1, configured.size())
        assertEquals(2, ((Map) configured[0]).excludedCount,
                "both rows of the in-progress order count against the RULE, and only those: ${configured}")
        assertEquals(1, metadataFilters.get("closedUnrefundedReturnsSuppressed"),
                "812 was dropped by the closed-unrefunded rule, not by the exclusion: ${metadataFilters}")
    }

    @Test
    void aRuleThatMatchesNothingStillAppearsInTheMetadataWithAZeroCount() {
        // A missing entry would read as "the rule was never applied", which is exactly the silent
        // no-op this feature exists to remove. Mirrors OmsReturnsSourceSupport.buildMetadata.
        Map node = [
                id              : "gid://shopify/Order/8002",
                legacyResourceId: "8002",
                name            : "#8002",
                createdAt       : "2026-05-01T08:00:00Z",
                returnStatus    : "RETURNED",
                // The refund is what makes this order emit a row at all since DAR-BE-027: its CLOSED
                // unrefunded return is suppressed. Left CLOSED deliberately rather than flipped to
                // OPEN — a RETURNED order whose return is still OPEN is not a shape Shopify produces,
                // and this test has no reason to invent one.
                refunds         : [[id: "gid://shopify/Refund/804", createdAt: "2026-05-01T09:20:00Z"]],
                returns         : [nodes: [[id: "gid://shopify/Return/805", status: "CLOSED",
                                             createdAt: "2026-05-01T09:30:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }
        List<Map<String, Object>> filters = [[sequenceNum: 1, fieldExpression: "orderReturnStatus",
                                              operator: "EXCLUDE_IN", filterValues: "IN_PROGRESS"]]

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [sourceFilters: filters], executor)

        assertEquals(1, result.recordCount)
        List configured = (List) ((Map) ((Map) result.requestMetadata).filters).configuredExclusions
        assertEquals(1, configured.size(), "a rule that matched nothing must still be reported: ${configured}")
        assertEquals(0, ((Map) configured[0]).excludedCount)
    }

    @Test
    void noConfiguredRulesLeavesConfiguredExclusionsAbsentEntirely() {
        Map node = [
                id              : "gid://shopify/Order/8003",
                legacyResourceId: "8003",
                name            : "#8003",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [],
                returns         : [nodes: [[id: "gid://shopify/Return/806", status: "OPEN",
                                             createdAt: "2026-05-01T09:30:00Z", refunds: []]]],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: node]],
                    pageInfo: [hasNextPage: false, endCursor: "c1"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        assertEquals(1, result.recordCount, "nothing is excluded when no rules are configured")
        Map filters = (Map) ((Map) result.requestMetadata).filters
        assertFalse(filters.containsKey("configuredExclusions"),
                "no rules configured means the key is absent, not an empty list: ${filters}")
    }

    @Test
    void aMalformedRuleFailsBeforeAnyRequestIsMade() {
        // Parsed pre-flight so a bad rule is one clean error, not N identical mid-window failures on
        // fetch-pool threads. The executor asserts it was never called.
        List<String> executorCalls = []
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            executorCalls.add("called")
            return [ok: true, data: [orders: [edges: [], pageInfo: [hasNextPage: false]]]]
        }
        // EXCLUDE_IN is the only supported operator; anything else must be rejected, not ignored.
        List<Map<String, Object>> filters = [[sequenceNum: 1, fieldExpression: "orderReturnStatus",
                                              operator: "INCLUDE_ONLY", filterValues: "IN_PROGRESS"]]

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [sourceFilters: filters], executor)

        assertTrue(executorCalls.isEmpty(), "no Shopify request may be made when a rule is malformed")
        assertFalse(((List) result.errors).isEmpty(), "a malformed rule must surface as an error")
        assertEquals(0, result.recordCount)
        assertFalse((Boolean) result.dataAvailable)
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
                // OPEN, not CLOSED (changed 2026-09-01, DAR-BE-027): this helper backs tests about
                // ids, windows and grain, none of which want their return suppressed. CLOSED here was
                // an arbitrary default from when status was read by nothing.
                returns         : [nodes: returnGids.collect { [id: it, status: "OPEN", createdAt: returnsCreatedAt] }],
        ]
    }

    @Test
    void everyEventRowCarriesTheOrderCancellationMarkerAndTheKeyIsAlwaysPresent() {
        // The cancellation-refund suppression reads this off the diff row's own embedded data, so it
        // must land on BOTH row kinds (refund and return) and the key must be present even when the
        // order is not cancelled — see ShopifyReturnRefsSupport.toRecords for why absent and null
        // cannot be allowed to mean the same thing.
        Map cancelled = [
                id              : "gid://shopify/Order/5001",
                legacyResourceId: "5001",
                name            : "#5001",
                createdAt       : "2026-05-01T08:00:00Z",
                cancelledAt     : "2026-05-03T11:22:33Z",
                refunds         : [[id: "gid://shopify/Refund/501", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [[id: "gid://shopify/Return/601", status: "OPEN", createdAt: "2026-05-01T09:05:00Z", refunds: []]]],
        ]
        Map live = [
                id              : "gid://shopify/Order/5002",
                legacyResourceId: "5002",
                name            : "#5002",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [[id: "gid://shopify/Refund/502", createdAt: "2026-05-01T09:30:00Z"]],
                returns         : [nodes: []],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: cancelled], [cursor: "c2", node: live]],
                    pageInfo: [hasNextPage: false, endCursor: "c2"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        List records = (List) result.records
        Map refund501 = (Map) records.find { ((Map) it).refundOrReturnId == "501" }
        Map return601 = (Map) records.find { ((Map) it).refundOrReturnId == "601" }
        Map refund502 = (Map) records.find { ((Map) it).refundOrReturnId == "502" }
        assertTrue(refund501 != null && return601 != null && refund502 != null,
                "all three events are expected: ${records}")

        // Both row kinds on the cancelled order carry the order's cancellation timestamp.
        assertEquals("2026-05-03T11:22:33Z", refund501.orderCancelledAt)
        assertEquals("2026-05-03T11:22:33Z", return601.orderCancelledAt)

        // Not cancelled -> key PRESENT, value null. Absent would mean "extract predates the field".
        assertTrue(refund502.containsKey("orderCancelledAt"),
                "the key must be present even when the order is not cancelled: ${refund502}")
        assertNull(refund502.orderCancelledAt)

        records.each { Object raw ->
            assertTrue(((Map) raw).containsKey("orderCancelledAt"),
                    "every event row must carry the key: ${raw}")
        }
    }

    @Test
    void everyEventRowCarriesTheOrderLevelReturnStatusAndTheKeyIsAlwaysPresent() {
        // Order.returnStatus is the OrderReturnStatus aggregate an operator actually sees (Shopify
        // admin's "Return in progress", the return_status: search filter), and since 2026-09-01 it is
        // the ONLY status field this endpoint emits. It must land on BOTH row kinds: a refund row has
        // no per-return status of its own, so this is what makes it reachable by a rule at all.
        //
        // Shape below is order #GOR196958636 as live-probed on gorjana 2026-09-01: IN_PROGRESS at
        // the order level, carrying one CLOSED return that HAS a refund (suppressed as a RETURN row
        // by the refunded-return narrowing) and one OPEN return that does not (emitted).
        Map inProgress = [
                id              : "gid://shopify/Order/7001",
                legacyResourceId: "7001",
                name            : "#GOR196958636",
                createdAt       : "2026-05-01T08:00:00Z",
                returnStatus    : "IN_PROGRESS",
                refunds         : [[id: "gid://shopify/Refund/701", createdAt: "2026-05-01T09:00:00Z"]],
                returns         : [nodes: [
                        [id: "gid://shopify/Return/801", status: "CLOSED", createdAt: "2026-05-01T09:05:00Z",
                         refunds: [nodes: [[id: "gid://shopify/Refund/701"]]]],
                        [id: "gid://shopify/Return/802", status: "OPEN", createdAt: "2026-05-01T09:06:00Z",
                         refunds: [nodes: []]],
                ]],
        ]
        // An order Shopify reports no returnStatus for at all: the key must still be present, so a
        // consumer can tell "this extract predates the field" from "Shopify said nothing".
        Map silent = [
                id              : "gid://shopify/Order/7002",
                legacyResourceId: "7002",
                name            : "#7002",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [[id: "gid://shopify/Refund/702", createdAt: "2026-05-01T09:30:00Z"]],
                returns         : [nodes: []],
        ]
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [
                    edges   : [[cursor: "c1", node: inProgress], [cursor: "c2", node: silent]],
                    pageInfo: [hasNextPage: false, endCursor: "c2"],
            ]]]
        }

        Map result = ShopifyReturnRefsSupport.extractReturnRefs(authConfig(),
                "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", [:], executor)

        List records = (List) result.records
        Map refund701 = (Map) records.find { ((Map) it).refundOrReturnId == "701" }
        Map return802 = (Map) records.find { ((Map) it).refundOrReturnId == "802" }
        Map refund702 = (Map) records.find { ((Map) it).refundOrReturnId == "702" }
        assertTrue(refund701 != null && return802 != null && refund702 != null,
                "refund, surviving return and the silent order's refund are all expected: ${records}")

        // The REFUND row had no status field at all before this change; it must carry the order-level
        // value so "exclude IN_PROGRESS" reaches it.
        assertEquals("IN_PROGRESS", refund701.orderReturnStatus)
        assertEquals("IN_PROGRESS", return802.orderReturnStatus)

        // Shopify reported nothing -> key PRESENT, value null.
        assertTrue(refund702.containsKey("orderReturnStatus"),
                "the key must be present even when Shopify reports no return status: ${refund702}")
        assertNull(refund702.orderReturnStatus)

        records.each { Object raw ->
            assertTrue(((Map) raw).containsKey("orderReturnStatus"),
                    "every event row must carry the key: ${raw}")
        }
    }

    // --- CANCELLED-ITEM DETECTION (2026-08-21): a refunded line that never shipped cannot have been
    // returned. Measured 22/25 on unmatched refunds and 0/8 on matched ones.

    private static Map orderWithFulfilment(String refundId, String refundedLineId, List fulfilledLineIds,
                                           Map extras = [:]) {
        Map node = [
                id              : "gid://shopify/Order/9100",
                legacyResourceId: "9100",
                name            : "#9100",
                createdAt       : "2026-05-01T08:00:00Z",
                refunds         : [[id: "gid://shopify/Refund/${refundId}".toString(), createdAt: "2026-05-01T09:00:00Z",
                                    refundLineItems: [nodes: [[lineItem: [id: "gid://shopify/LineItem/${refundedLineId}".toString()]]]]]],
                returns         : [nodes: []],
                fulfillments    : [[fulfillmentLineItems: [nodes: fulfilledLineIds.collect {
                                        [quantity: 1, lineItem: [id: "gid://shopify/LineItem/${it}".toString()]] }]]],
        ]
        node.putAll(extras)
        return node
    }

    private Map extractOne(Map node, Map options = [:]) {
        Closure executor = { Map cfg, String doc, Map vars, Map opts ->
            return [ok: true, data: [orders: [edges: [[cursor: "c1", node: node]],
                                              pageInfo: [hasNextPage: false, endCursor: "c1"]]]]
        }
        return ShopifyReturnRefsSupport.extractReturnRefs(authConfig(), "2026-05-01T00:00:00Z",
                "2026-05-02T00:00:00Z", options, executor)
    }

    @Test
    void aRefundWhoseLineWasShippedIsMarkedFulfilled() {
        Map result = extractOne(orderWithFulfilment("701", "5001", ["5001"]))

        Map record = (Map) ((List) result.records).find { ((Map) it).refundOrReturnId == "701" }
        assertEquals(Boolean.TRUE, record.refundLineEverFulfilled)
    }

    @Test
    void aRefundWhoseLineNeverShippedIsMarkedUnfulfilled() {
        // The ORDER has a fulfillment — just not of this line. That is the live shape: every one of
        // these orders reads displayFulfillmentStatus FULFILLED because the OTHER lines shipped.
        Map result = extractOne(orderWithFulfilment("702", "5002", ["5999"]))

        Map record = (Map) ((List) result.records).find { ((Map) it).refundOrReturnId == "702" }
        assertEquals(Boolean.FALSE, record.refundLineEverFulfilled)
    }

    @Test
    void aSaturatedFulfilmentListYieldsUnknownRatherThanUnfulfilled() {
        // Truncation is fatal to this answer: a missed fulfillment page makes a shipped line look
        // unshipped, which would suppress a real difference. Unknown must not collapse into false.
        List many = (1..2).collect { "l${it}".toString() }
        Map node = orderWithFulfilment("703", "5003", many)
        Map result = extractOne(node, [connectionPageSize: 2])

        Map record = (Map) ((List) result.records).find { ((Map) it).refundOrReturnId == "703" }
        assertNull(record.refundLineEverFulfilled)
        assertTrue(((List) result.warnings).any { (it as String).contains("truncated") },
                "a saturated fulfillment list must say so: ${result.warnings}")
    }

    @Test
    void aRefundWithNoLineItemsYieldsUnknown() {
        Map node = orderWithFulfilment("704", "5004", ["5004"])
        ((Map) ((List) node.refunds).first()).put("refundLineItems", [nodes: []])
        Map result = extractOne(node)

        Map record = (Map) ((List) result.records).find { ((Map) it).refundOrReturnId == "704" }
        assertNull(record.refundLineEverFulfilled)
    }

    @Test
    void returnRowsDoNotCarryTheFulfilmentVerdict() {
        // The verdict is about a REFUND's lines. A RETURN row has none, and carrying a null column
        // would invite a consumer to read it as "not fulfilled".
        Map node = orderWithFulfilment("705", "5005", ["5005"])
        node.put("returns", [nodes: [[id: "gid://shopify/Return/805", createdAt: "2026-05-01T09:05:00Z", refunds: []]]])
        Map result = extractOne(node)

        Map ret = (Map) ((List) result.records).find { ((Map) it).refundOrReturnType == "RETURN" }
        assertNotNull(ret)
        assertFalse(ret.containsKey("refundLineEverFulfilled"))
    }
}
