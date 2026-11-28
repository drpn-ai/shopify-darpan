package shopify.reconciliation.automation

/**
 * DAR-BE-050. Assembles bulk-operation JSONL lines into one record per ORDER LINE UNIT.
 *
 * Bulk JSONL is FLAT: an Order arrives as its own line with no __parentId, and each LineItem
 * arrives as its own line whose __parentId points at that Order. The ORDERS extractor treats every
 * line as an order, which is why connection fields are rejected for that source; for this source
 * the child lines ARE the records (ShopifyGraphqlQueryBuilder's allowsBulkConnections opt-in).
 *
 * Filtering is not done here (DAR-BE-318): the zero-quantity and orphan drops below are the shape of the grain and stay
 * (counted in requestMetadata); every user rule is Spark Keep's, in darpan core.
 *
 * Pure: no transport, no ExecutionContext. Takes the already-parsed lines from
 * ShopifyBulkOperationClient.eachJsonlRecord, which needs no change - it is grain-agnostic.
 */
class ShopifyOrderLineUnitSupport {

    // Unit states (spec 2026-09-30 D1). Per line, with q = quantity, c = currentQuantity and
    // u = unfulfilledQuantity: the first u units are OPEN, the next (c - u) FULFILLED, the last
    // (q - c) REMOVED. OPEN is exact - returns do not touch unfulfilledQuantity. REMOVED is
    // cancellation PLUS refund, because a return zeroes currentQuantity too; so FULFILLED means
    // "shipped and still held", never "ever fulfilled", and no run keys on it.
    static final String STATE_OPEN = "OPEN"
    static final String STATE_FULFILLED = "FULFILLED"
    static final String STATE_REMOVED = "REMOVED"
    // The line arrived without currentQuantity or unfulfilledQuantity. Not null: a composite key on
    // stateOrdinal refuses a blank field and fails the whole run, and an INCLUDE_IN rule on a
    // state still drops these units by value. Counted, never guessed.
    static final String STATE_UNKNOWN = "UNKNOWN"

    /**
     * The units as a list, for a caller that holds its lines already (tests, small reads); see {@link #streamUnits}.
     *
     * @return [units: List&lt;Map&gt;, orphanLineItemCount: int, droppedZeroQuantity: int,
     *          unknownStateLineCount: int, clampedStateLineCount: int]
     */
    static Map assembleUnits(List jsonlRecords) {
        List<Map> units = []
        Map counts = streamUnits({ Closure visit -> (jsonlRecords ?: []).each { visit.call(it) } }, { Map unit -> units.add(unit) })
        return [units: units] + counts
    }

    /**
     * DAR-OPS-029 S42a: the units, each handed to {@code emit} as it is made, so neither the lines nor the units of a long
     * window are ever held together (the month OOM the orders extract had). {@code eachLine} replays every JSONL line
     * and is called twice: the first pass keeps each order's few fields a unit carries, the second makes the units, line
     * items in file order, so the output is the one a single in-memory pass made.
     *
     * Two passes rather than one. JSONL does put a parent before its children, but relying on that would turn a
     * truncated or reordered download into silently missing units instead of a reported orphan count - and a unit
     * missing from the Shopify side is reported by the anti-join as a real difference, so a parse bug would masquerade
     * as a finding.
     *
     * @return [orphanLineItemCount: int, droppedZeroQuantity: int, unknownStateLineCount: int, clampedStateLineCount: int]
     */
    static Map streamUnits(Closure eachLine, Closure emit) {
        Map<String, Map> ordersByGid = [:]
        eachLine.call { Object raw ->
            if (!(raw instanceof Map)) return
            Map record = (Map) raw
            if (normalize(record.get("__parentId"))) return
            String gid = normalize(record.get("id"))
            if (gid) ordersByGid.put(gid, [id: record.get("id"), legacyResourceId: record.get("legacyResourceId"),
                                           name: record.get("name"), returnStatus: record.get("returnStatus"),
                                           sourceName: record.get("sourceName")])
        }

        int orphanLineItemCount = 0
        int droppedZeroQuantity = 0
        int unknownStateLineCount = 0
        int clampedStateLineCount = 0
        Map<String, Integer> ordinalByLine = [:]
        Map<String, Integer> ordinalByLineState = [:]

        eachLine.call { Object raw ->
            if (!(raw instanceof Map)) return
            Map lineItem = (Map) raw
            String parentId = normalize(lineItem.get("__parentId"))
            if (!parentId) return
            Map order = ordersByGid.get(parentId)
            if (order == null) {
                orphanLineItemCount++
                return
            }

            String shopifyOrderId = normalize(order.get("legacyResourceId")) ?: legacyId(order.get("id"))
            String shopifyLineId = legacyId(lineItem.get("id"))
            if (!shopifyOrderId || !shopifyLineId) {
                orphanLineItemCount++
                return
            }

            int quantity = toNonNegativeInt(lineItem.get("quantity"))
            if (quantity <= 0) {
                droppedZeroQuantity++
                return
            }

            Integer currentQuantity = toNullableInt(lineItem.get("currentQuantity"))
            Integer unfulfilledQuantity = toNullableInt(lineItem.get("unfulfilledQuantity"))
            boolean stateKnown = currentQuantity != null && unfulfilledQuantity != null
            int openCount = 0
            int heldCount = 0
            if (stateKnown) {
                // Clamped so the three bands always sum to quantity. Shopify should never send
                // u > c or c > q; if it does, the unit count stays right and the line is counted.
                heldCount = Math.min(Math.max(0, currentQuantity), quantity)
                openCount = Math.min(Math.max(0, unfulfilledQuantity), heldCount)
                if (heldCount != currentQuantity || openCount != unfulfilledQuantity) clampedStateLineCount++
            } else {
                unknownStateLineCount++
            }

            String lineKey = shopifyOrderId + "/" + shopifyLineId
            for (int copy = 0; copy < quantity; copy++) {
                int ordinal = (ordinalByLine.get(lineKey) ?: 0) + 1
                ordinalByLine.put(lineKey, ordinal)
                String unitState = !stateKnown ? STATE_UNKNOWN :
                        copy < openCount ? STATE_OPEN :
                                copy < heldCount ? STATE_FULFILLED : STATE_REMOVED
                String stateKey = lineKey + "/" + unitState
                int stateOrdinal = (ordinalByLineState.get(stateKey) ?: 0) + 1
                ordinalByLineState.put(stateKey, stateOrdinal)
                // The first six keys are DAR-BE-050's record, in its order; the state fields are
                // appended after them so that pair's output is unchanged field for field.
                emit.call([
                        shopifyOrderId   : shopifyOrderId,
                        shopifyLineId    : shopifyLineId,
                        unitOrdinal      : ordinal,
                        shopifyOrderName : normalize(order.get("name")),
                        sku              : normalize(lineItem.get("sku")),
                        lineItemName     : normalize(lineItem.get("name")),
                        unitState        : unitState,
                        stateOrdinal     : stateOrdinal,
                        orderReturnStatus: normalize(order.get("returnStatus")),
                        orderSourceName  : normalize(order.get("sourceName")),
                ])
            }
        }
        return [orphanLineItemCount  : orphanLineItemCount,
                droppedZeroQuantity  : droppedZeroQuantity,
                unknownStateLineCount: unknownStateLineCount,
                clampedStateLineCount: clampedStateLineCount]
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

    /** null when the field is absent or unparseable - the caller must not guess a state from it. */
    private static Integer toNullableInt(Object value) {
        if (value == null) return null
        if (value instanceof Number) return ((Number) value).intValue()
        try {
            return Integer.parseInt(value.toString().trim())
        } catch (NumberFormatException ignored) {
            return null
        }
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
