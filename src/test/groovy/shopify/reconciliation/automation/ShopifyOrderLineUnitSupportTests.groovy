package shopify.reconciliation.automation

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.KeepRules
import darpan.reconciliation.source.SourceFilterSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-050 Task 7. Bulk JSONL is FLAT: an Order arrives as its own line with no __parentId, and
 * each LineItem arrives as its own line whose __parentId points at that Order.
 */
class ShopifyOrderLineUnitSupportTests {

    private static Map orderLine(String id, String legacyId, String name) {
        return [id: "gid://shopify/Order/${id}".toString(), legacyResourceId: legacyId,
                name: name, createdAt: "2026-08-19T22:27:12Z"]
    }

    private static Map lineItemLine(String lineId, String parentOrderId, int quantity, String sku) {
        return [id: "gid://shopify/LineItem/${lineId}".toString(), quantity: quantity, sku: sku,
                name: "Test Product", __parentId: "gid://shopify/Order/${parentOrderId}".toString()]
    }

    @Test
    void aQuantityThreeLineBecomesThreeUnits() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337"),
                lineItemLine("15210699161731", "6678687481987", 3, "SKU-1")])

        List units = (List) result.units
        assertEquals(3, units.size())
        assertEquals([1, 2, 3], units.collect { ((Map) it).unitOrdinal })
        assertEquals("6678687481987", ((Map) units[0]).shopifyOrderId)
        assertEquals("15210699161731", ((Map) units[0]).shopifyLineId)
        assertEquals("SKU-1", ((Map) units[0]).sku)
    }

    @Test
    void idsAreTheNumericLegacyForm() {
        // OMS stores the NUMERIC Shopify ids. A gid on either side keys against nothing and the
        // whole window reports as 100% different - the loudest possible version of a silent bug.
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337"),
                lineItemLine("15210699161731", "6678687481987", 1, "SKU-1")])

        Map unit = (Map) ((List) result.units)[0]
        assertTrue(!(unit.shopifyOrderId as String).contains("gid://"))
        assertTrue(!(unit.shopifyLineId as String).contains("gid://"))
    }

    @Test
    void eachLineRestartsAtOrdinalOne() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337"),
                lineItemLine("15210699161731", "6678687481987", 2, "SKU-1"),
                lineItemLine("15210699194499", "6678687481987", 1, "SKU-2")])

        List units = (List) result.units
        assertEquals(3, units.size())
        assertEquals([1, 2, 1], units.collect { ((Map) it).unitOrdinal })
    }

    @Test
    void aLineItemWithNoParentOrderIsCountedNotSilentlyDropped() {
        // JSONL puts the parent before its children, but a truncated download or a future second
        // nesting level breaks that. An orphan must be loud: silently dropping it removes units
        // from the Shopify side, which the anti-join then reports as missing in SHOPIFY - a
        // real-looking finding manufactured by a parse bug.
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                lineItemLine("15210699161731", "6678687481987", 1, "SKU-1")])

        assertTrue(((List) result.units).isEmpty())
        assertEquals(1, result.orphanLineItemCount)
    }

    @Test
    void childLinesArrivingBeforeTheirParentStillResolve() {
        // Two passes, not one: relying on parent-before-child ordering would turn a reordered or
        // truncated download into silently missing units instead of a reported orphan count.
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                lineItemLine("15210699161731", "6678687481987", 1, "SKU-1"),
                orderLine("6678687481987", "6678687481987", "#GOR196507337")])

        assertEquals(1, ((List) result.units).size())
        assertEquals(0, result.orphanLineItemCount)
    }

    @Test
    void aZeroQuantityLineEmitsNothingAndIsCounted() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337"),
                lineItemLine("15210699161731", "6678687481987", 0, "SKU-1")])

        assertTrue(((List) result.units).isEmpty())
        assertEquals(1, result.droppedZeroQuantity)
    }

    @Test
    void anOrderWithNoLinesContributesNoUnits() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337")])

        assertTrue(((List) result.units).isEmpty())
        assertEquals(0, result.orphanLineItemCount)
    }

    @Test
    void severalOrdersInOnePageKeepTheirOwnLines() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                orderLine("6678687481987", "6678687481987", "#GOR196507337"),
                lineItemLine("15210699161731", "6678687481987", 1, "SKU-1"),
                orderLine("6678687481988", "6678687481988", "#GOR196507338"),
                lineItemLine("15210699194499", "6678687481988", 2, "SKU-2")])

        List units = (List) result.units
        assertEquals(3, units.size())
        assertEquals(["6678687481987", "6678687481988", "6678687481988"],
                units.collect { ((Map) it).shopifyOrderId })
        assertEquals([1, 1, 2], units.collect { ((Map) it).unitOrdinal })
    }

    @Test
    void anOrderMissingLegacyResourceIdFallsBackToItsGidTail() {
        Map order = orderLine("6678687481987", null, "#GOR196507337")
        order.remove("legacyResourceId")

        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                order, lineItemLine("15210699161731", "6678687481987", 1, "SKU-1")])

        assertEquals("6678687481987", ((Map) ((List) result.units)[0]).shopifyOrderId)
    }

    // ---- Tri-system unit state (spec 2026-09-30 D1, D2) -------------------------------------
    // Per-line values below are from a live gorjana pull (API 2024-10), customer data stripped.

    private static Map stateOrder(String legacyId, String name, String returnStatus, String sourceName = "web") {
        return [id: "gid://shopify/Order/${legacyId}".toString(), legacyResourceId: legacyId,
                name: name, createdAt: "2026-09-05T07:59:18Z", returnStatus: returnStatus,
                sourceName: sourceName]
    }

    private static Map stateLine(String lineId, String parentOrderId, String sku,
                                 Integer quantity, Integer currentQuantity, Integer unfulfilledQuantity) {
        Map line = [id: "gid://shopify/LineItem/${lineId}".toString(), quantity: quantity, sku: sku,
                    name: "Test Product", __parentId: "gid://shopify/Order/${parentOrderId}".toString()]
        if (currentQuantity != null) line.currentQuantity = currentQuantity
        if (unfulfilledQuantity != null) line.unfulfilledQuantity = unfulfilledQuantity
        return line
    }

    /** One order of each real shape: fulfilled, cancelled before fulfilment, returned, open. */
    private static List liveShapedJsonl() {
        return [
                stateOrder("7157143306371", "#GOR197201510", "NO_RETURN"),
                stateLine("16445243916419", "7157143306371", "214-3008-G", 1, 1, 0),
                stateOrder("7158980083843", "#GOR197205176", "NO_RETURN"),
                stateLine("16448517505155", "7158980083843", "2511-3018-02-G", 1, 0, 0),
                stateOrder("7163614199939", "#GOR197215502", "RETURNED"),
                stateLine("16457549676675", "7163614199939", "188-008-S", 1, 0, 0),
                stateOrder("7160857329795", "#GOR197209182", "NO_RETURN"),
                stateLine("16451984851075", "7160857329795", "202-025-308-G", 2, 2, 2),
        ]
    }

    private static Map unitsOf(List units, String shopifyLineId) {
        List matching = units.findAll { ((Map) it).shopifyLineId == shopifyLineId }
        return [states  : matching.collect { ((Map) it).unitState },
                stateOrd: matching.collect { ((Map) it).stateOrdinal },
                unitOrd : matching.collect { ((Map) it).unitOrdinal }]
    }

    @Test
    void eachRealLineShapeGetsTheStateTheSpecDerives() {
        List units = (List) ShopifyOrderLineUnitSupport.assembleUnits(liveShapedJsonl()).units

        assertEquals(["FULFILLED"], unitsOf(units, "16445243916419").states, "q1 c1 u0")
        assertEquals(["REMOVED"], unitsOf(units, "16448517505155").states, "cancelled before fulfilment, q1 c0 u0")
        // A RETURN also zeroes currentQuantity, which is why FULFILLED is never "ever fulfilled"
        // (D1): this line was shipped, and it is REMOVED.
        assertEquals(["REMOVED"], unitsOf(units, "16457549676675").states, "returned, q1 c0 u0")
        assertEquals(["OPEN", "OPEN"], unitsOf(units, "16451984851075").states, "q2 c2 u2")
    }

    @Test
    void aMixedLineOrdersOpenThenFulfilledThenRemovedAndNumbersEachStateFromOne() {
        List units = (List) ShopifyOrderLineUnitSupport.assembleUnits([
                stateOrder("7160857329795", "#GOR197209182", "NO_RETURN"),
                stateLine("16451984851075", "7160857329795", "SKU-1", 6, 4, 1)]).units

        Map line = unitsOf(units, "16451984851075")
        assertEquals(["OPEN", "FULFILLED", "FULFILLED", "FULFILLED", "REMOVED", "REMOVED"], line.states)
        assertEquals([1, 1, 2, 3, 1, 2], line.stateOrd)
        // unitOrdinal is DAR-BE-050's key and must not move.
        assertEquals([1, 2, 3, 4, 5, 6], line.unitOrd)
    }

    @Test
    void theExistingUnitFieldsAreUnchangedAndTheNewOnesAreAppended() {
        Map unit = (Map) ((List) ShopifyOrderLineUnitSupport.assembleUnits([
                stateOrder("7163614199939", "#GOR197215502", "RETURNED", "pos"),
                stateLine("16457549676675", "7163614199939", "188-008-S", 1, 0, 0)]).units)[0]

        assertEquals(["shopifyOrderId", "shopifyLineId", "unitOrdinal", "shopifyOrderName", "sku",
                      "lineItemName", "unitState", "stateOrdinal", "orderReturnStatus", "orderSourceName"],
                unit.keySet() as List)
        assertEquals("RETURNED", unit.orderReturnStatus)
        // POS refunds carry no Return object, so returnStatus stays NO_RETURN on a POS return; the
        // A-cancel run needs the channel to tell those apart (first live run, 2026-09-30).
        assertEquals("pos", unit.orderSourceName)
    }

    @Test
    void missingQuantityFieldsAreUnknownAndCountedNeverGuessed() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                stateOrder("7157143306371", "#GOR197201510", null),
                stateLine("16445243916419", "7157143306371", "SKU-1", 2, null, null),
                stateLine("16445243916420", "7157143306371", "SKU-2", 1, 1, null)])

        List units = (List) result.units
        assertEquals(3, units.size(), "the unit is still emitted: DAR-BE-050's presence pair needs it")
        assertEquals(["UNKNOWN", "UNKNOWN"], unitsOf(units, "16445243916419").states)
        assertEquals([1, 2], unitsOf(units, "16445243916419").stateOrd)
        assertEquals(["UNKNOWN"], unitsOf(units, "16445243916420").states)
        assertEquals(2, result.unknownStateLineCount)
        assertNull(((Map) units[0]).orderReturnStatus)
    }

    @Test
    void inconsistentQuantitiesAreClampedAndCounted() {
        Map result = ShopifyOrderLineUnitSupport.assembleUnits([
                stateOrder("7157143306371", "#GOR197201510", "NO_RETURN"),
                // currentQuantity above quantity, unfulfilledQuantity above currentQuantity.
                stateLine("16445243916419", "7157143306371", "SKU-1", 2, 5, 9)])

        assertEquals(["OPEN", "OPEN"], unitsOf((List) result.units, "16445243916419").states)
        assertEquals(1, result.clampedStateLineCount)
    }

    // ---- Keep parity (DAR-BE-318 Task 22) -----------------------------------------------------
    // The extract no longer filters. Spark Keep (darpan core) sets aside by the same rules; Spark is not on this repo's test
    // classpath, so parity is pinned as: the unfiltered units are exactly what the old read-time filter was handed, the old
    // filter's outcome on them is pinned, and the same rules translate to valid Keep rules.

    private static final List<Map> KEEP_FILTERS = [
            [sequenceNum: 1, fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "OPEN,FULFILLED"],
            [sequenceNum: 2, fieldExpression: "orderReturnStatus", operator: "EXCLUDE_IN", filterValues: "RETURNED"],
            [sequenceNum: 3, fieldExpression: "orderSourceName", operator: "EXCLUDE_IN", filterValues: "pos"],
    ]

    @Test
    void theUnfilteredUnitsAreExactlyWhatTheReadTimeFilterWasHandedAndItsOutcomeIsPinned() {
        List jsonl = liveShapedJsonl() + [
                stateOrder("7170000000001", "#GOR1", "NO_RETURN", "pos"),
                stateLine("17000000000001", "7170000000001", "SKU-P", 2, 2, 1),
                stateOrder("7170000000002", "#GOR2", null),
                stateLine("17000000000002", "7170000000002", "SKU-N", 1, 1, 1),
                stateLine("17000000000003", "7170000000002", "SKU-Z", 0, 0, 0),   // zero quantity: grain shape
                stateLine("17000000000004", "7179999999999", "SKU-O", 1, 1, 1)]   // orphan: grain shape
        Map assembled = ShopifyOrderLineUnitSupport.assembleUnits(jsonl)
        List<Map> units = (List<Map>) assembled.units
        // The grain's own drops stay at flattening, counted; they are not user rules and Keep never sees them.
        assertEquals(1, assembled.droppedZeroQuantity)
        assertEquals(1, assembled.orphanLineItemCount)
        assertEquals(8, units.size())
        assertFalse(units.any { it.shopifyLineId in ["17000000000003", "17000000000004"] })

        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(KEEP_FILTERS)
        // applyToRecords does not mutate its input: what Keep is handed is what the old filter was handed.
        String before = groovy.json.JsonOutput.toJson(units)
        Map old = SourceFilterSupport.applyToRecords(units, parsed)
        assertEquals(before, groovy.json.JsonOutput.toJson(units))
        ExcludedRecordsSidecar.discard((Map) old.excludedCollector)

        List<String> keptKeys = ((List<Map>) old.records).collect { "${it.shopifyLineId}/${it.unitOrdinal}".toString() }
        // 16445243916419 FULFILLED (kept), 16448517505155 REMOVED (set aside), 16457549676675 REMOVED + RETURNED (set aside),
        // 16451984851075 two OPEN (kept), 17000000000001 pos (both set aside), 17000000000002 OPEN, absent returnStatus (kept: EXCLUDE_IN).
        assertEquals(["16445243916419/1", "16451984851075/1", "16451984851075/2", "17000000000002/1"], keptKeys)
        assertEquals([[1, 2, 0], [2, 0, 0], [3, 2, 0]], ((List<Map>) old.configuredExclusions).collect {
            [it.sequenceNum, it.excludedCount, it.fieldAbsentCount]
        })

        List<Map> keepRules = SourceFilterSupport.toKeepRules(parsed)
        KeepRules.validate(keepRules)
        assertEquals(["A:1", "A:2", "A:3"], keepRules*.id)
    }

    /**
     * DAR-OPS-029 S42a (I2 conservation, no OOM). Streamed, the units are the ones the list path makes, in the same order,
     * and a line that arrives before its order or far from it still becomes its units (the file is read twice).
     */
    @Test
    void streamedUnitsMatchTheListPathEvenWhenLinesArriveAwayFromTheirOrder() {
        List lines = [
                [id: "gid://shopify/LineItem/12", __parentId: "gid://shopify/Order/2", quantity: 1, sku: "B"],   // before its order
                [id: "gid://shopify/Order/1", legacyResourceId: "1", name: "#1", returnStatus: "NO_RETURN", sourceName: "web"],
                [id: "gid://shopify/LineItem/11", __parentId: "gid://shopify/Order/1", quantity: 2, sku: "A",
                 currentQuantity: 2, unfulfilledQuantity: 1],
                [id: "gid://shopify/Order/2", legacyResourceId: "2", name: "#2"],
                [id: "gid://shopify/LineItem/13", __parentId: "gid://shopify/Order/1", quantity: 0, sku: "Z"],  // zero: dropped
                [id: "gid://shopify/LineItem/14", __parentId: "gid://shopify/Order/9", quantity: 1, sku: "Q"],  // no order: orphan
        ]
        List<Map> streamed = []
        Map counts = ShopifyOrderLineUnitSupport.streamUnits({ Closure visit -> lines.each { visit.call(it) } }, { Map u -> streamed.add(u) })

        // Literal expectations, not the list path (which now calls streamUnits): the unit a line before its order makes.
        assertEquals([shopifyOrderId: "2", shopifyLineId: "12", unitOrdinal: 1, shopifyOrderName: "#2", sku: "B", lineItemName: null,
                      unitState: "UNKNOWN", stateOrdinal: 1, orderReturnStatus: null, orderSourceName: null], streamed[0])
        assertEquals(["web", "web"], streamed.findAll { it.shopifyOrderId == "1" }*.orderSourceName)
        assertEquals(["2/12/1", "1/11/1", "1/11/2"], streamed.collect { "${it.shopifyOrderId}/${it.shopifyLineId}/${it.unitOrdinal}".toString() })
        assertEquals(["OPEN", "FULFILLED"], streamed.findAll { it.shopifyLineId == "11" }*.unitState)
        assertEquals(1, counts.orphanLineItemCount)
        assertEquals(1, counts.droppedZeroQuantity)
        // Conservation: every line is its quantity in units, or counted.
        assertEquals(3, streamed.size())
    }
}
