package shopify.reconciliation.automation

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport

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
     * @return [units: List&lt;Map&gt;, orphanLineItemCount: int, droppedZeroQuantity: int,
     *          unknownStateLineCount: int, clampedStateLineCount: int]
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
        int unknownStateLineCount = 0
        int clampedStateLineCount = 0
        Map<String, Integer> ordinalByLine = [:]
        Map<String, Integer> ordinalByLineState = [:]

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
                units.add([
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
        return [units                : units,
                orphanLineItemCount  : orphanLineItemCount,
                droppedZeroQuantity  : droppedZeroQuantity,
                unknownStateLineCount: unknownStateLineCount,
                clampedStateLineCount: clampedStateLineCount]
    }

    /**
     * Parsed ONCE, before the bulk operation is submitted, so a malformed rule fails pre-flight
     * rather than after a multi-minute bulk run. Throws IllegalArgumentException on a bad rule.
     *
     * fieldExpression arrives ALREADY reduced to a bare top-level record field: the dispatchers
     * (AutomationRuntimeSupport, ReconciliationSavedRunSupport) run stored JSONPath expressions
     * through SourceFilterSupport.toRecordFieldRules. Do NOT reduce it again here.
     */
    static List<Map<String, Object>> parseSourceFilters(Object sourceFilters) {
        return SourceFilterSupport.parseRules(sourceFilters)
    }

    /**
     * Applies configured source filters to each UNIT row - so a rule may name a unit field
     * (unitState) as well as an order field stamped on every unit (orderReturnStatus). Same verdict
     * handling and per-rule reporting as ShopifyReturnRefsSupport and the OMS getters.
     *
     * @return [units: kept units (the SAME list when there are no rules),
     *          configuredExclusions: one entry per rule, or null when no rules are configured]
     */
    static Map applySourceFilters(List units, List<Map<String, Object>> parsedFilters) {
        // No rules: hand back the input untouched, so an unconfigured connector's output is
        // byte-identical to the build before filters existed.
        if (!parsedFilters) return [units: units, configuredExclusions: null]

        List<Map> kept = []
        // DAR-UI-044: the dropped units, whole, for the conclude pass.
        Map excludedCollector = ExcludedRecordsSidecar.newCollector()
        Map<String, Integer> exclusionCounts = [:]
        Map<String, Integer> fieldAbsentCounts = [:]
        for (Object raw : (units ?: [])) {
            Map unit = (Map) raw
            Map<String, Object> verdict = SourceFilterSupport.evaluate(unit, parsedFilters)
            if (verdict == null) {
                kept.add(unit)
                continue
            }
            ExcludedRecordsSidecar.collect(excludedCollector, unit, (Map) verdict.get("rule"))
            String key = String.valueOf(((Map) verdict.get("rule")).get("sequenceNum"))
            // Two buckets, one rejection. orderReturnStatus is stamped on every unit, so a
            // FIELD_ABSENT here means Shopify returned no returnStatus for the order - a different
            // fault from a value the rule does not allow.
            Map<String, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason")
                    ? fieldAbsentCounts
                    : exclusionCounts
            bucket.put(key, (bucket.get(key) ?: 0) + 1)
        }
        // EVERY configured rule appears, including one that matched nothing (excludedCount 0) - a
        // missing entry would read as "not applied".
        List<Map<String, Object>> configuredExclusions = parsedFilters.collect { Map<String, Object> rule ->
            String key = String.valueOf(rule.get("sequenceNum"))
            return [
                    sequenceNum     : rule.get("sequenceNum"),
                    fieldExpression : rule.get("fieldExpression"),
                    operator        : rule.get("operator"),
                    values          : new ArrayList<String>((List) rule.get("values")),
                    excludedCount   : exclusionCounts.get(key) ?: 0,
                    // Present on every rule including EXCLUDE_IN ones, where it is structurally
                    // zero: a reader should not need to know which keys apply to which mode.
                    fieldAbsentCount: fieldAbsentCounts.get(key) ?: 0,
            ] as Map<String, Object>
        }
        return [units: kept, configuredExclusions: configuredExclusions, excludedCollector: excludedCollector]
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
