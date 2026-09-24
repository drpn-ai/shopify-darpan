package shopify.reconciliation.automation

/**
 * DAR-BE-050. Assembles bulk-operation JSONL lines into one record per ORDER LINE UNIT.
 *
 * Bulk JSONL is FLAT: an Order arrives as its own line with no __parentId, and each LineItem
 * arrives as its own line whose __parentId points at that Order. The ORDERS extractor treats every
 * line as an order, which is why connection fields are rejected for that source; for this source
 * the child lines ARE the records (ShopifyGraphqlQueryBuilder's allowsBulkConnections opt-in).
 *
 * Pure: no transport, no ExecutionContext. Takes the already-parsed lines from
 * ShopifyBulkOperationClient.parseJsonlRecords, which needs no change - it is grain-agnostic.
 */
class ShopifyOrderLineUnitSupport {

    /**
     * @return [units: List&lt;Map&gt;, orphanLineItemCount: int, droppedZeroQuantity: int]
     */
    static Map assembleUnits(List jsonlRecords) {
        Map<String, Map> ordersByGid = [:]
        List<Map> lineItems = []

        // Two passes rather than one. JSONL does put a parent before its children, but relying on
        // that would turn a truncated or reordered download into silently missing units instead of
        // a reported orphan count - and a unit missing from the Shopify side is reported by the
        // anti-join as a real difference, so a parse bug would masquerade as a finding.
        for (Object raw : (jsonlRecords ?: [])) {
            if (!(raw instanceof Map)) continue
            Map record = (Map) raw
            String parentId = normalize(record.get("__parentId"))
            if (parentId) {
                lineItems.add(record)
            } else {
                String gid = normalize(record.get("id"))
                if (gid) ordersByGid.put(gid, record)
            }
        }

        List<Map> units = []
        int orphanLineItemCount = 0
        int droppedZeroQuantity = 0
        Map<String, Integer> ordinalByLine = [:]

        for (Map lineItem : lineItems) {
            Map order = ordersByGid.get(normalize(lineItem.get("__parentId")))
            if (order == null) {
                orphanLineItemCount++
                continue
            }

            String shopifyOrderId = normalize(order.get("legacyResourceId")) ?: legacyId(order.get("id"))
            String shopifyLineId = legacyId(lineItem.get("id"))
            if (!shopifyOrderId || !shopifyLineId) {
                orphanLineItemCount++
                continue
            }

            int quantity = toNonNegativeInt(lineItem.get("quantity"))
            if (quantity <= 0) {
                droppedZeroQuantity++
                continue
            }

            String lineKey = shopifyOrderId + "/" + shopifyLineId
            for (int copy = 0; copy < quantity; copy++) {
                int ordinal = (ordinalByLine.get(lineKey) ?: 0) + 1
                ordinalByLine.put(lineKey, ordinal)
                units.add([
                        shopifyOrderId  : shopifyOrderId,
                        shopifyLineId   : shopifyLineId,
                        unitOrdinal     : ordinal,
                        shopifyOrderName: normalize(order.get("name")),
                        sku             : normalize(lineItem.get("sku")),
                        lineItemName    : normalize(lineItem.get("name")),
                ])
            }
        }
        return [units              : units,
                orphanLineItemCount: orphanLineItemCount,
                droppedZeroQuantity: droppedZeroQuantity]
    }

    /** gid://shopify/LineItem/15210699161731 -> 15210699161731; already-numeric passes through. */
    private static String legacyId(Object gid) {
        String text = normalize(gid)
        if (!text) return null
        int slash = text.lastIndexOf("/")
        return slash >= 0 ? text.substring(slash + 1) : text
    }

    private static String normalize(Object value) {
        String text = value?.toString()?.trim()
        return text ? text : null
    }

    private static int toNonNegativeInt(Object value) {
        if (value instanceof Number) return Math.max(0, ((Number) value).intValue())
        try {
            return Math.max(0, Integer.parseInt(value?.toString()?.trim() ?: "0"))
        } catch (NumberFormatException ignored) {
            return 0
        }
    }
}
