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
 * <p>Each row also says how much of the ORDER'S OWN lines Shopify has not fulfilled (originalUnfulfilledQuantity), leaving
 * out the lines an exchange added: an exchange reaches NetSuite only once its original order is fulfilled, so that is
 * the first thing to look at when an exchange is missing there. Order-level fulfillment status cannot answer it, since
 * the exchange's own new lines keep the order "partially fulfilled" until they ship.</p>
 *
 * <p>Same net as ShopifyExchangeSweepSupport: orders updated since the window start that have a return, no upper bound
 * (a later, unrelated edit must not hide an earlier completion); the window itself is applied to each return's own
 * closedAt here.</p>
 */
class ShopifyCompletedReturnsSupport {

    static final String REFUNDED = "refunded"
    static final String EXCHANGED = "exchanged"
    static final int PAGE_SIZE = 50
    static final int RETURNS_PER_ORDER = 20
    static final int LINES_PER_ORDER = 100
    static final int EXCHANGE_ITEMS_PER_RETURN = 20
    static final int MAX_PAGES = 400
    private static final String CLOSED = "CLOSED"

    private static final String QUERY = '''query DarpanShopifyCompletedReturns($first: Int!, $after: String, $query: String, $returnsFirst: Int!, $linesFirst: Int!, $exchangeItemsFirst: Int!) {
  orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT) {
    pageInfo { hasNextPage endCursor }
    nodes {
      legacyResourceId
      name
      lineItems(first: $linesFirst) { nodes { id unfulfilledQuantity } }
      returns(first: $returnsFirst) {
        nodes {
          id
          status
          closedAt
          refunds(first: 1) { nodes { id } }
          exchangeLineItems(first: $exchangeItemsFirst) { nodes { id lineItems { id } } }
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
        int pageSize = PAGE_SIZE
        while (true) {
            if (pages++ >= MAX_PAGES) {
                warnings.add("Stopped after ${MAX_PAGES} pages of orders; completed returns past that are not read.".toString())
                break
            }
            Map result = (Map) exec(authConfig, QUERY,
                    [first: pageSize, after: after, query: search, returnsFirst: RETURNS_PER_ORDER,
                     linesFirst: LINES_PER_ORDER, exchangeItemsFirst: EXCHANGE_ITEMS_PER_RETURN], transportOptions)
            // Each order asks for its lines, returns and exchange items, so a page can cost more than Shopify allows
            // in one query. That refusal is not a failure: the same page is asked again with half as many orders.
            if (result?.ok != true && pageSize > 1 && overCostLimit(result)) {
                pageSize = Math.max(1, (int) (pageSize / 2))
                pages--
                continue
            }
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
                requestMetadata: [completedReturns: kind, ordersPages: pages, ordersPageSize: pageSize, search: search]]
    }

    /** Shopify's single-query cost refusal: "Query cost is N, which exceeds the single query max cost limit (M)." */
    static boolean overCostLimit(Map result) {
        List<String> messages = ((List) (result?.graphqlErrors ?: result?.errors ?: [])).collect { it.toString().toLowerCase() }
        return messages.any { it.contains("max cost") || it.contains("max_cost_exceeded") }
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
        return [orderId                    : orderId,
                orderName                  : normalize(order.get("name"))?.replaceFirst(/^#/, ""),
                returnIds                  : matched.collect { bareId(it.get("id")) }.join(","),
                returnCount                : matched.size(),
                closedAt                   : matched.collect { normalize(it.get("closedAt")) }.max(),
                originalUnfulfilledQuantity: originalUnfulfilledQuantity(order, returns, orderId, warnings)] as Map<String, Object>
    }

    /** Unfulfilled quantity on the order's lines, leaving out every line an exchange (of any return) added. */
    static int originalUnfulfilledQuantity(Map order, List returns, String orderId, List<String> warnings) {
        Set<String> exchangeLineIds = [] as Set
        returns.each { Object ret ->
            List items = (List) (((Map) (((Map) ret).get("exchangeLineItems") ?: [:])).get("nodes") ?: [])
            if (items.size() >= EXCHANGE_ITEMS_PER_RETURN) {
                warnings.add("Order ${orderId} has a return with ${items.size()} exchange items, the most read.".toString())
            }
            items.each { Object item -> ((List) (((Map) item).get("lineItems") ?: [])).each { exchangeLineIds.add(normalize(((Map) it).get("id"))) } }
        }
        List lines = (List) (((Map) (order.get("lineItems") ?: [:])).get("nodes") ?: [])
        if (lines.size() >= LINES_PER_ORDER) {
            warnings.add("Order ${orderId} has ${lines.size()} lines, the most read; its unfulfilled quantity may be short.".toString())
        }
        return (int) lines.findAll { !exchangeLineIds.contains(normalize(((Map) it).get("id"))) }
                .sum(0) { Object line -> (((Map) line).get("unfulfilledQuantity") ?: 0) as int }
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
