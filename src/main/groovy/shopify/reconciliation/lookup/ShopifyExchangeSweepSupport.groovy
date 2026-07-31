package shopify.reconciliation.lookup

import shopify.graphql.ShopifyGraphqlTransport

import java.time.Instant

/**
 * Exchange sweep for the exchange presence check (product contract 2026-07-31): enumerate the
 * exchanges CREATED IN SHOPIFY whose return date falls inside the run window, so the verify stage
 * can confirm each was imported into OMS as an exchange order.
 *
 * The search windows on updated_at with NO upper bound — any return activity bumps the order's
 * updated_at, but later, unrelated activity must not hide an in-window return — and the return
 * date itself is filtered client-side from Return.createdAt. `-return_status:no_return` trims the
 * candidate set to return-active orders (negation syntax live-verified 2026-07-30).
 */
class ShopifyExchangeSweepSupport {
    static final int PAGE_SIZE = 100
    static final int DEFAULT_MAX_PAGES = 40

    private static final String SWEEP_QUERY = '''query exchangeSweep($first: Int!, $after: String, $query: String) {
  orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT) {
    edges {
      cursor
      node {
        id
        legacyResourceId
        name
        returns(first: 10) {
          nodes {
            id
            name
            status
            createdAt
            exchangeLineItems(first: 1) { nodes { id } }
          }
        }
      }
    }
    pageInfo { hasNextPage endCursor }
  }
}'''

    static Map<String, Object> sweepExchanges(Map authConfig, long windowStartMillis, long windowEndMillis,
                                              Map options = [:], Closure executor = null) {
        Closure exec = executor ?: { Map cfg, String queryDocument, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, queryDocument, variables, opts)
        }
        Map transportOptions = [
                connectTimeoutMillis: options?.connectTimeoutMillis,
                readTimeoutMillis   : options?.readTimeoutMillis,
                maxAttempts         : options?.maxAttempts,
        ]
        int maxPages = (options?.maxPages instanceof Number) ? ((Number) options.maxPages).intValue() : DEFAULT_MAX_PAGES
        String searchQuery = "updated_at:>='${Instant.ofEpochMilli(windowStartMillis)}' -return_status:no_return".toString()

        List<Map<String, Object>> exchanges = []
        String after = null
        boolean truncated = false
        for (int pageIndex = 0; ; pageIndex++) {
            if (pageIndex >= maxPages) { truncated = true; break }
            Map result = (Map) exec(authConfig, SWEEP_QUERY, [first: PAGE_SIZE, after: after, query: searchQuery], transportOptions)
            if (result?.ok != true) {
                return [ok: false, exchanges: [], truncated: false,
                        errors: ((result?.errors ?: ["Shopify exchange sweep failed."]) as List)]
            }
            Map ordersConnection = (Map) ((result.data instanceof Map ? ((Map) result.data).get('orders') : null) ?: [:])
            List edges = (List) (ordersConnection.get('edges') ?: [])
            for (Object rawEdge : edges) {
                if (!(rawEdge instanceof Map)) continue
                Map node = (Map) (((Map) rawEdge).get('node') ?: [:])
                Map inWindowExchange = firstInWindowExchangeReturn(node, windowStartMillis, windowEndMillis)
                if (inWindowExchange == null) continue
                String legacyId = node.get('legacyResourceId')?.toString()
                if (!legacyId) continue
                exchanges.add([
                        externalId          : legacyId,
                        orderName           : node.get('name'),
                        returnId            : inWindowExchange.get('id'),
                        returnName          : inWindowExchange.get('name'),
                        returnStatus        : inWindowExchange.get('status'),
                        returnCreatedAtMillis: parseIsoMillis(inWindowExchange.get('createdAt')),
                        // ALL exchange returns on the order (any date): the verify stage compares
                        // this against OMS's exchange-order count so a missing repeat exchange
                        // cannot hide behind an earlier imported one.
                        exchangeReturnCount : countExchangeReturns(node),
                ])
            }
            Map pageInfo = (Map) (ordersConnection.get('pageInfo') ?: [:])
            if (pageInfo.get('hasNextPage') != true) break
            after = pageInfo.get('endCursor')?.toString()
            if (!after) break
        }
        return [ok: true, exchanges: exchanges, truncated: truncated, errors: []]
    }

    /** Earliest return with ≥1 exchange line item whose createdAt is inside [start, end) — presence
     *  semantics need one hit per order, not one row per return. */
    protected static Map firstInWindowExchangeReturn(Map orderNode, long windowStartMillis, long windowEndMillis) {
        List returnsNodes = (List) (((Map) (orderNode.get('returns') ?: [:])).get('nodes') ?: [])
        for (Object rawReturn : returnsNodes) {
            if (!(rawReturn instanceof Map)) continue
            Map returnNode = (Map) rawReturn
            List exchangeItems = (List) (((Map) (returnNode.get('exchangeLineItems') ?: [:])).get('nodes') ?: [])
            if (!exchangeItems) continue
            if (isCanceledReturn(returnNode)) continue
            Long createdAt = parseIsoMillis(returnNode.get('createdAt'))
            if (createdAt == null || createdAt < windowStartMillis || createdAt >= windowEndMillis) continue
            return returnNode
        }
        return null
    }

    protected static int countExchangeReturns(Map orderNode) {
        List returnsNodes = (List) (((Map) (orderNode.get('returns') ?: [:])).get('nodes') ?: [])
        int count = 0
        for (Object rawReturn : returnsNodes) {
            if (!(rawReturn instanceof Map)) continue
            List exchangeItems = (List) (((Map) (((Map) rawReturn).get('exchangeLineItems') ?: [:])).get('nodes') ?: [])
            if (exchangeItems && !isCanceledReturn((Map) rawReturn)) count++
        }
        return count
    }

    /** Canceled exchange returns never produce an OMS exchange order (user-verified 2026-07-31:
     *  M761755/M760737 were the live -1 orders; the flagged "missing" ones were canceled seconds).
     *  Excluded from both window inclusion and the expected count, per the forum contract's
     *  "non-cancelled" scope. */
    protected static boolean isCanceledReturn(Map returnNode) {
        return "CANCELED".equalsIgnoreCase(returnNode.get('status')?.toString()?.trim() ?: "")
    }

    protected static Long parseIsoMillis(Object rawValue) {
        String text = rawValue?.toString()?.trim()
        if (!text) return null
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (Exception ignored) {
            return null
        }
    }
}
