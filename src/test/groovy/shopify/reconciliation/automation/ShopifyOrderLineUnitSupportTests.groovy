package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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
}
