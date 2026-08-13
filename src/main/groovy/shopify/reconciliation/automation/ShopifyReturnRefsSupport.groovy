package shopify.reconciliation.automation

import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifyGraphqlTransport
import shopify.graphql.ShopifySourceCatalog

import java.util.regex.Matcher
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * Per-order Shopify refund ids and return ids for returns reconciliation (DAR-BE-018, design §7).
 *
 * CURSOR PATH, NOT BULK. refunds and returns are connections; ShopifyGraphqlQueryBuilder
 * .buildBulkQuery rejects connection-bearing fields because bulk JSONL emits their children as
 * separate __parentId lines and nothing here re-nests them. Cursor pagination returns naturally
 * nested objects, which is exactly the per-order id-set shape the match rule wants.
 *
 * Ids are emitted BARE (GID tail). The OMS side stores a bare numeric Shopify reference, and the
 * SHOPIFY_GID_TAIL normalizer's type segment is a wildcard, so both sides land in the same space
 * regardless of whether the id names a Refund or a Return (design §2).
 */
class ShopifyReturnRefsSupport {

    static final int DEFAULT_CONNECTION_PAGE_SIZE = 50
    static final int MAX_PAGE_COUNT = 20000

    private static final Pattern GID_TAIL = Pattern.compile('gid://shopify/[^/]+/(\\d+)(?:\\?.*)?$')
    private static final Pattern TRAILING_DIGITS = Pattern.compile('(\\d+)$')

    /** Mirrors CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL so both sides agree. */
    static String bareId(Object rawId) {
        String value = normalize(rawId)
        if (!value) return null
        Matcher gidMatcher = GID_TAIL.matcher(value)
        if (gidMatcher.find()) return gidMatcher.group(1)
        Matcher digitsMatcher = TRAILING_DIGITS.matcher(value)
        if (digitsMatcher.find()) return digitsMatcher.group(1)
        return value
    }

    static Map<String, Object> extractReturnRefs(Map authConfig, Object windowStart, Object windowEnd,
                                                 Map options = [:], Closure executor = null) {
        List<String> errors = []
        List<String> warnings = []
        List<Map<String, Object>> records = []

        Closure exec = executor ?: { Map cfg, String queryDocument, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, queryDocument, variables, opts)
        }

        Map<String, Object> built
        try {
            built = ShopifyGraphqlQueryBuilder.buildQuery([
                    sourceDefinitionId  : ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                    operationName       : "DarpanShopifyReturnRefsByDateWindow",
                    filters             : [createdAtFrom: normalize(windowStart), createdAtTo: normalize(windowEnd)],
                    pageSize            : options?.pageSize,
                    connectionPageSizes : [
                            refundsFirst: normalizeInt(options?.connectionPageSize, DEFAULT_CONNECTION_PAGE_SIZE),
                            returnsFirst: normalizeInt(options?.connectionPageSize, DEFAULT_CONNECTION_PAGE_SIZE),
                    ],
            ])
        } catch (Exception e) {
            return [records: [], recordCount: 0, dataAvailable: false, requestMetadata: [:],
                    warnings: warnings, errors: [normalize(e.message) ?: "Shopify return-refs query could not be built."]]
        }

        String queryDocument = built.queryDocument as String
        // buildQuery already resolves first/after/query/reverse/<root>First into one variables map
        // consistent with the rendered document's declared variables ($reverse, $refundsFirst and
        // $returnsFirst are all non-null!). Each page must start from a copy of it and only touch
        // "after" — rebuilding a fresh {first, after} map from scratch would silently drop $query
        // (the window filter itself), $reverse, $refundsFirst and $returnsFirst, and Shopify would
        // reject every request at GraphQL variable-validation time, before the search query is even
        // evaluated.
        Map<String, Object> baseVariables = (Map<String, Object>) built.variables
        int connectionFirst = normalizeInt(options?.connectionPageSize, DEFAULT_CONNECTION_PAGE_SIZE)
        String cursor = null
        int pageCount = 0
        boolean hasNextPage = true

        while (hasNextPage) {
            if (pageCount++ >= MAX_PAGE_COUNT) {
                warnings.add("Return-refs extraction stopped at the ${MAX_PAGE_COUNT}-page ceiling; the window may be incomplete.".toString())
                break
            }
            Map<String, Object> variables = new LinkedHashMap<>(baseVariables)
            if (cursor) variables.put("after", cursor)

            Map response = (Map) exec(authConfig, queryDocument, variables, options ?: [:])
            if (response?.ok != true) {
                errors.addAll(((List) (response?.errors ?: ["Shopify return-refs request failed."]))
                        .collect { normalize(it) }.findAll { it })
                break
            }

            Map ordersConnection = (Map) walk(response, ["data", "orders"])
            if (ordersConnection == null) {
                errors.add("Shopify return-refs response had no orders connection.")
                break
            }

            List edges = (ordersConnection.get("edges") instanceof List) ? (List) ordersConnection.get("edges") : []
            edges.each { Object rawEdge ->
                Map node = (Map) ((Map) rawEdge)?.get("node")
                if (node == null) return
                records.add(toRecord(node, connectionFirst, warnings))
            }

            Map pageInfo = (Map) ordersConnection.get("pageInfo")
            hasNextPage = pageInfo?.get("hasNextPage") == true
            cursor = normalize(pageInfo?.get("endCursor"))
            if (hasNextPage && !cursor) {
                warnings.add("Shopify reported another page but returned no cursor; extraction stopped early.")
                break
            }
        }

        if (errors) {
            return [records: [], recordCount: 0, dataAvailable: false, requestMetadata: [:],
                    warnings: warnings, errors: errors]
        }

        return [
                records        : records,
                recordCount    : records.size(),
                dataAvailable  : !records.isEmpty(),
                requestMetadata: [filters: [
                        serverReportedOrderCount: records.size(),
                        refundIdCount           : records.sum { ((List) it.refundIds).size() } ?: 0,
                        returnIdCount           : records.sum { ((List) it.returnIds).size() } ?: 0,
                ]],
                warnings       : warnings,
                errors         : [],
        ]
    }

    /**
     * createdAt is REQUIRED downstream, not decorative: ReturnPresenceVerificationSupport's reverse
     * pass uses it for the grace check. If it is absent, parseMillis returns 0, every Shopify order
     * reads as old, the grace never fires, and young refunds are reported missing instead of
     * pending. Keep it selected in the catalog and emitted here.
     */
    private static Map<String, Object> toRecord(Map node, int connectionFirst, List<String> warnings) {
        String orderId = bareId(node.get("legacyResourceId") ?: node.get("id"))
        return [
                orderId   : orderId,
                orderName : normalize(node.get("name")),
                createdAt : normalize(node.get("createdAt")),
                refundIds : collectIds(node.get("refunds"), orderId, "refunds", connectionFirst, warnings),
                returnIds : collectIds(node.get("returns"), orderId, "returns", connectionFirst, warnings),
        ]
    }

    /**
     * Handles BOTH live shapes, which are asymmetric (probed 2026-08-13, API 2026-01):
     *   refunds -> a plain List of objects, no wrapper and NO pageInfo
     *   returns -> a ReturnConnection: {nodes: [...], pageInfo: {...}}
     * The edges/node branch is kept defensively so an API-version difference degrades to a parsed
     * set rather than a silent empty one.
     *
     * TRUNCATION is the real hazard here: an understated id set manufactures false
     * missing-in-Shopify diffs, which is precisely what this extractor exists to prevent. The two
     * shapes need different detection:
     *   - connection: pageInfo.hasNextPage is authoritative.
     *   - plain list: there is NO signal at all, so saturation (size >= requested first) is the
     *     only proxy. It over-warns on an order holding exactly `first` items; that false positive
     *     is far cheaper than a silently truncated set.
     */
    private static List<String> collectIds(Object rawConnection, String orderId, String label,
                                           int requestedFirst, List<String> warnings) {
        if (rawConnection == null) return []
        List rawNodes = []
        if (rawConnection instanceof List) {
            rawNodes = (List) rawConnection
            if (requestedFirst > 0 && rawNodes.size() >= requestedFirst) {
                warnings.add("Order ${orderId} returned ${rawNodes.size()} ${label}, the maximum requested — the list may be truncated and diffs for it may be wrong.".toString())
            }
        } else if (rawConnection instanceof Map) {
            Map connection = (Map) rawConnection
            if (connection.get("nodes") instanceof List) {
                rawNodes = (List) connection.get("nodes")
            } else if (connection.get("edges") instanceof List) {
                rawNodes = ((List) connection.get("edges")).collect { ((Map) it)?.get("node") }
            }
            if (((Map) (connection.get("pageInfo") ?: [:])).get("hasNextPage") == true) {
                warnings.add("Order ${orderId} has more ${label} than one page; the id set is incomplete and diffs for it may be wrong.".toString())
            }
        }
        return rawNodes.collect { Object rawNode ->
            bareId(((Map) rawNode)?.get("id"))
        }.findAll { it } as List<String>
    }

    private static Object walk(Object root, List<String> path) {
        Object current = root
        for (String segment : path) {
            if (!(current instanceof Map)) return null
            current = ((Map) current).get(segment)
        }
        return current
    }
}
