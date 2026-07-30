package shopify.reconciliation.lookup

import shopify.graphql.ShopifyGraphqlTransport

/**
 * Exchange-state point lookup for the exchange pair verify stage: per Shopify order, the returns
 * that contain exchange line items plus current totals. Query shape live-verified 2026-07-30
 * (spec G4): the only exchange surface on supported API versions is returns→exchangeLineItems;
 * ExchangeLineItem.lineItems is a plain list on 2024-10 but may be a connection on later versions,
 * so a list-shape GraphQL rejection triggers one retry with the connection-shaped query.
 */
class ShopifyExchangeStateLookupSupport {
    static final int MAX_IDS_PER_CALL = 50
    static final String SHAPE_LIST = "list"
    static final String SHAPE_CONNECTION = "connection"

    private static String query(boolean connectionShape) {
        String lineItemsSelection = connectionShape
                ? "lineItems(first: 20) { nodes { id sku quantity } }"
                : "lineItems { id sku quantity }"
        return """query orderExchangeState(\$ids: [ID!]!) {
  nodes(ids: \$ids) {
    id
    ... on Order {
      legacyResourceId
      name
      returnStatus
      currentTotalPriceSet { shopMoney { amount currencyCode } }
      returns(first: 10) {
        nodes {
          id
          status
          createdAt
          exchangeLineItems(first: 20) { nodes { id quantity ${lineItemsSelection} } }
        }
      }
    }
  }
}"""
    }

    static Map<String, Object> lookupExchangeState(Map authConfig, Collection orderIds,
                                                   Map options = [:], Closure executor = null) {
        Closure exec = executor ?: { Map cfg, String queryDocument, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, queryDocument, variables, opts)
        }
        Map transportOptions = [
                connectTimeoutMillis: options?.connectTimeoutMillis,
                readTimeoutMillis   : options?.readTimeoutMillis,
                maxAttempts         : options?.maxAttempts,
        ]
        List<String> gids = (orderIds ?: []).collect { toOrderGid(it) }.findAll { it }.unique()
        if (!gids) return [ok: false, statesByOrderId: [:], errors: ["No order ids provided for exchange-state lookup."], lineItemsShape: null]

        Map<String, Object> statesByOrderId = [:]
        boolean connectionShape = false
        String shapeUsed = null
        for (int offset = 0; offset < gids.size(); offset += MAX_IDS_PER_CALL) {
            List<String> batch = gids.subList(offset, Math.min(offset + MAX_IDS_PER_CALL, gids.size()))
            Map result = (Map) exec(authConfig, query(connectionShape), [ids: batch], transportOptions)
            if (result?.ok != true && !connectionShape && isLineItemsShapeError(result)) {
                connectionShape = true
                result = (Map) exec(authConfig, query(true), [ids: batch], transportOptions)
            }
            if (result?.ok != true) {
                return [ok: false, statesByOrderId: [:],
                        errors: ((result?.errors ?: ["Shopify exchange-state lookup failed."]) as List), lineItemsShape: null]
            }
            shapeUsed = connectionShape ? SHAPE_CONNECTION : SHAPE_LIST
            Object nodes = result.data instanceof Map ? ((Map) result.data).get('nodes') : null
            (nodes instanceof List ? (List) nodes : []).each { Object node ->
                if (!(node instanceof Map)) return
                Map orderNode = (Map) node
                String legacyId = orderNode.get('legacyResourceId')?.toString()
                        ?: legacyIdFromGid(orderNode.get('id')?.toString())
                if (legacyId) statesByOrderId.put(legacyId, parseOrderState(orderNode))
            }
        }
        return [ok: true, statesByOrderId: statesByOrderId, errors: [], lineItemsShape: shapeUsed]
    }

    protected static boolean isLineItemsShapeError(Map result) {
        List graphqlErrors = (result?.graphqlErrors ?: []) as List
        return graphqlErrors.any { it?.toString()?.toLowerCase()?.contains("lineitems") }
    }

    protected static String toOrderGid(Object rawId) {
        String id = rawId?.toString()?.trim()
        if (!id) return null
        return id.startsWith(ShopifyOrderLookupSupport.ORDER_GID_PREFIX) ? id
                : "${ShopifyOrderLookupSupport.ORDER_GID_PREFIX}${id}".toString()
    }

    protected static String legacyIdFromGid(String gid) {
        if (!gid) return null
        int slash = gid.lastIndexOf('/')
        return slash >= 0 ? gid.substring(slash + 1) : gid
    }

    protected static Map<String, Object> parseOrderState(Map orderNode) {
        Map money = (Map) (((Map) (orderNode.get('currentTotalPriceSet') ?: [:])).get('shopMoney') ?: [:])
        BigDecimal amount = null
        try { if (money.get('amount') != null) amount = new BigDecimal(money.get('amount').toString()) } catch (Exception ignored) { }
        List returnsNodes = (List) (((Map) (orderNode.get('returns') ?: [:])).get('nodes') ?: [])
        List exchanges = []
        returnsNodes.each { Object rawReturn ->
            if (!(rawReturn instanceof Map)) return
            Map returnNode = (Map) rawReturn
            List exchangeLineItems = (List) (((Map) (returnNode.get('exchangeLineItems') ?: [:])).get('nodes') ?: [])
            List parsedItems = exchangeLineItems.findAll { it instanceof Map }.collect { Object rawItem ->
                Map item = (Map) rawItem
                [id: item.get('id'), quantity: item.get('quantity'), lineItems: normalizeLineItems(item.get('lineItems'))]
            }
            if (parsedItems) {
                exchanges.add([returnId: returnNode.get('id'), status: returnNode.get('status'),
                        createdAt: returnNode.get('createdAt'), exchangeLineItems: parsedItems])
            }
        }
        return [returnStatus: orderNode.get('returnStatus'), currentTotalAmount: amount,
                currentTotalCurrency: money.get('currencyCode'), exchanges: exchanges]
    }

    /** Accepts both response shapes: plain list (2024-10) and connection {nodes:[...]} (later versions). */
    protected static List normalizeLineItems(Object rawLineItems) {
        List items = rawLineItems instanceof Map ? (List) (((Map) rawLineItems).get('nodes') ?: [])
                : (rawLineItems instanceof List ? (List) rawLineItems : [])
        return items.findAll { it instanceof Map }.collect { Object raw ->
            Map lineItem = (Map) raw
            [id: lineItem.get('id'), sku: lineItem.get('sku'), quantity: lineItem.get('quantity')]
        }
    }
}
