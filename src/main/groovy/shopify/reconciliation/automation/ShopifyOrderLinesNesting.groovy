package shopify.reconciliation.automation

import darpan.common.ValueSupport

/**
 * DAR-BE-368 (Aditi: "include the line item details, expecially the SKU"). Puts an orders bulk read's line items back
 * under their order, one order at a time, so an audit finding about the order can show its lines.
 *
 * Bulk JSONL is flat: an order arrives as its own line, then each of its line items as a line whose __parentId is the
 * order's gid (ShopifyOrderLineUnitSupport reads the same lines as units). This holds only the order being read: a new
 * order line hands the one before it on, with its lines, so a month of orders never sits in the heap (the OOM in
 * shopify-orders-extract-oom-month-window). A line whose order is not the one being read is not guessed onto another:
 * it is counted (orphanLines) and left out.
 *
 * Pure: no transport, no ExecutionContext.
 */
class ShopifyOrderLinesNesting {

    static final String LINE_ITEMS_FIELD = "lineItems"

    private final Closure<Void> emit
    private Map<String, Object> current
    private String currentGid
    int orphanLines = 0

    /** {@code emit} receives each order, its lineItems attached, as soon as the next order (or the end) arrives. */
    ShopifyOrderLinesNesting(Closure<Void> emit) {
        this.emit = emit
    }

    void add(Map<String, Object> record) {
        String parent = ValueSupport.normalize(record?.get("__parentId"))
        if (!parent) {
            flush()
            current = new LinkedHashMap<String, Object>(record)
            currentGid = ValueSupport.normalize(record.get("id"))
            current.put(LINE_ITEMS_FIELD, [])
            return
        }
        if (current == null || parent != currentGid) {
            orphanLines++
            return
        }
        ((List) current.get(LINE_ITEMS_FIELD)).add(line(record))
    }

    /** Hands on the last order. Call once, after the last line. */
    void finish() {
        flush()
    }

    private void flush() {
        if (current != null) emit.call(current)
        current = null
        currentGid = null
    }

    /** A line as an audit reads it: its numeric id (HotWax keeps it as the item's externalId), SKU, name, quantities. */
    static Map<String, Object> line(Map<String, Object> record) {
        String gid = ValueSupport.normalize(record.get("id"))
        def matcher = gid ? (gid =~ /(\d+)$/) : null
        return [id                 : matcher?.find() ? matcher.group(1) : gid,
                sku                : ValueSupport.normalize(record.get("sku")),
                name               : ValueSupport.normalize(record.get("name")),
                quantity           : record.get("quantity"),
                currentQuantity    : record.get("currentQuantity"),
                unfulfilledQuantity: record.get("unfulfilledQuantity")] as Map<String, Object>
    }
}
