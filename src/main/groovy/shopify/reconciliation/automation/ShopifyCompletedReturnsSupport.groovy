package shopify.reconciliation.automation

import shopify.graphql.ShopifyGraphqlTransport

import java.time.Instant

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-137. One record per Shopify order with a return COMPLETED (status CLOSED, closedAt inside the window), for a
 * tenant whose returns all happen in Shopify and only need to be reflected in NetSuite (Dolls Kill): a completed return
 * that was refunded must have a refunded RMA, and a completed exchange must have its exchange sales order.
 *
 * <p>Selected by the SHOPIFY_RETURN_REFS extract option {@code completedReturns}: "refunded" keeps orders with a
 * completed return that has a refund, "exchanged" orders with a completed return that has exchange items. An exchange
 * with no refund is therefore only ever held to the exchange check.</p>
 *
 * <p>Same net as ShopifyExchangeSweepSupport: orders updated since the window start that have a return, no upper bound
 * (a later, unrelated edit must not hide an earlier completion); the window itself is applied to each return's own
 * closedAt here.</p>
 */
class ShopifyCompletedReturnsSupport {

    static final String REFUNDED = "refunded"
    static final String EXCHANGED = "exchanged"
    static final int PAGE_SIZE = 100
    static final int RETURNS_PER_ORDER = 20
    static final int MAX_PAGES = 400
    private static final String CLOSED = "CLOSED"

    private static final String QUERY = '''query DarpanShopifyCompletedReturns($first: Int!, $after: String, $query: String, $returnsFirst: Int!) {
  orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT) {
    pageInfo { hasNextPage endCursor }
    nodes {
      legacyResourceId
      name
      returns(first: $returnsFirst) {
        nodes {
          id
          status
          closedAt
          refunds(first: 1) { nodes { id } }
          exchangeLineItems(first: 1) { nodes { id } }
        }
      }
    }
  }
}'''

    static Map<String, Object> extract(Map authConfig, String completedReturns, long windowStartMillis, long windowEndMillis,
                                       Map options = [:], Closure executor = null) {
        String kind = normalize(completedReturns)?.toLowerCase()
        if (!(kind in [REFUNDED, EXCHANGED])) {
            return [records: [], errors: ["completedReturns is '${REFUNDED}' or '${EXCHANGED}', got: ${completedReturns}".toString()],
                    warnings: [], requestMetadata: [:]]
        }
        Closure exec = executor ?: { Map cfg, String document, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, document, variables, opts)
        }
        Map transportOptions = [connectTimeoutMillis: options?.connectTimeoutMillis, readTimeoutMillis: options?.readTimeoutMillis,
                                maxAttempts         : options?.maxAttempts]
        String search = "updated_at:>='${Instant.ofEpochMilli(windowStartMillis)}' AND -return_status:no_return".toString()

        List<Map<String, Object>> records = []
        List<String> warnings = []
        String after = null
        int pages = 0
        while (true) {
            if (pages++ >= MAX_PAGES) {
                warnings.add("Stopped after ${MAX_PAGES} pages of orders; completed returns past that are not read.".toString())
                break
            }
            Map result = (Map) exec(authConfig, QUERY,
                    [first: PAGE_SIZE, after: after, query: search, returnsFirst: RETURNS_PER_ORDER], transportOptions)
            if (result?.ok != true) {
                return [records: [], warnings: warnings, requestMetadata: [:],
                        errors : ((result?.errors ?: ["Shopify completed-returns read failed."]) as List).collect { it.toString() }]
            }
            Map orders = (Map) (((Map) (result.data ?: [:])).get("orders") ?: [:])
            ((List) (orders.get("nodes") ?: [])).each { Object node ->
                Map record = toRecord((Map) node, kind, windowStartMillis, windowEndMillis, warnings)
                if (record) records.add(record)
            }
            Map pageInfo = (Map) (orders.get("pageInfo") ?: [:])
            after = pageInfo.get("hasNextPage") == true ? normalize(pageInfo.get("endCursor")) : null
            if (!after) break
        }
        return [records: records, errors: [], warnings: warnings,
                requestMetadata: [completedReturns: kind, ordersPages: pages, search: search]]
    }

    /** The order's record when it has a matching return completed in [start, end); null otherwise. */
    static Map<String, Object> toRecord(Map order, String kind, long windowStartMillis, long windowEndMillis, List<String> warnings) {
        String orderId = normalize(order?.get("legacyResourceId"))
        if (!orderId) return null
        List returns = (List) (((Map) (order.get("returns") ?: [:])).get("nodes") ?: [])
        if (returns.size() >= RETURNS_PER_ORDER) {
            warnings.add("Order ${orderId} has ${returns.size()} returns, the most read; later ones are not checked.".toString())
        }
        List<Map> matched = returns.findAll { Object raw ->
            Map ret = (Map) raw
            Long closedAt = millis(ret.get("closedAt"))
            boolean completed = normalize(ret.get("status")) == CLOSED && closedAt != null &&
                    closedAt >= windowStartMillis && closedAt < windowEndMillis
            completed && hasNode(ret.get(kind == REFUNDED ? "refunds" : "exchangeLineItems"))
        } as List<Map>
        if (!matched) return null
        return [orderId    : orderId,
                orderName  : normalize(order.get("name"))?.replaceFirst(/^#/, ""),
                returnIds  : matched.collect { bareId(it.get("id")) }.join(","),
                returnCount: matched.size(),
                closedAt   : matched.collect { normalize(it.get("closedAt")) }.max()] as Map<String, Object>
    }

    private static boolean hasNode(Object connection) {
        return connection instanceof Map && ((List) (((Map) connection).get("nodes") ?: [])).size() > 0
    }

    private static Long millis(Object iso) {
        String text = normalize(iso)
        if (!text) return null
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (Exception ignored) {
            return null
        }
    }

    private static String bareId(Object gid) {
        String text = normalize(gid)
        return text?.substring(text.lastIndexOf("/") + 1)
    }
}
