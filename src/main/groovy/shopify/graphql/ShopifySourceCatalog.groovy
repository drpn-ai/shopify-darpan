package shopify.graphql

import static darpan.common.ValueSupport.normalize

class ShopifySourceCatalog {
    static final String SHOPIFY_ORDERS = "SHOPIFY_ORDERS"
    static final String SHOPIFY_ORDER_RETURN_REFS = "SHOPIFY_ORDER_RETURN_REFS"
    static final List<String> SUPPORTED_API_VERSIONS = [
        "2025-07",
        "2025-10",
        "2026-01",
        "2026-04",
        "unstable",
    ].asImmutable()

    private static final Map<String, Object> ORDER_SOURCE = [
        sourceDefinitionId       : SHOPIFY_ORDERS,
        label                    : "Shopify Orders",
        description              : "Shopify Admin GraphQL orders connection for order automation extraction.",
        requiredEndpointSystemEnumId: "SHOPIFY",
        queryRoot                : "orders",
        graphqlType              : "Order",
        defaultSortKey           : "UPDATED_AT",
        paginationStrategy       : "CURSOR",
        defaultPageSize          : 100,
        maxPageSize              : 250,
        supportedApiVersions     : SUPPORTED_API_VERSIONS,
        defaultSelectedFieldPaths: [
            "id",
            "legacyResourceId",
            "name",
            "createdAt",
            "updatedAt",
        ],
        // Exact field set the Bulk Operations extraction selects (extractShopifyOrders). The JSONL
        // record shape is a downstream contract for reconciliation schemas and $.records[*] rules —
        // do not drop or rename entries without a migration plan.
        defaultBulkSelectedFieldPaths: [
            "id",
            "legacyResourceId",
            "name",
            "createdAt",
            "updatedAt",
            "processedAt",
            "email",
            "cancelledAt",
            "totalPrice",
            "displayFinancialStatus",
            "displayFulfillmentStatus",
            "currencyCode",
            "currentTotalPriceSet.shopMoney.amount",
            "currentTotalPriceSet.shopMoney.currencyCode",
            "currentTotalTaxSet.shopMoney.amount",
            "currentTotalTaxSet.shopMoney.currencyCode",
            "totalPriceSet.shopMoney.amount",
            "totalPriceSet.shopMoney.currencyCode",
            "subtotalPriceSet.shopMoney.amount",
            "subtotalPriceSet.shopMoney.currencyCode",
        ],
        supportedFilters         : [
            updatedAtFrom : [queryName: "updated_at", comparator: ">=", type: "datetime", sortKey: "UPDATED_AT"],
            updatedAtTo   : [queryName: "updated_at", comparator: "<", type: "datetime", sortKey: "UPDATED_AT"],
            createdAtFrom : [queryName: "created_at", comparator: ">=", type: "datetime", sortKey: "CREATED_AT"],
            createdAtTo   : [queryName: "created_at", comparator: "<", type: "datetime", sortKey: "CREATED_AT"],
            processedAtFrom: [queryName: "processed_at", comparator: ">=", type: "datetime", sortKey: "PROCESSED_AT"],
            processedAtTo : [queryName: "processed_at", comparator: "<", type: "datetime", sortKey: "PROCESSED_AT"],
            status        : [queryName: "status", comparator: "", type: "keyword"],
        ],
        fields                   : [
            [fieldPath: "id", label: "Order ID", type: "ID", selectionPath: "id", required: true],
            [fieldPath: "legacyResourceId", label: "Legacy Order ID", type: "UnsignedInt64", selectionPath: "legacyResourceId", required: true],
            [fieldPath: "name", label: "Order Name", type: "String", selectionPath: "name"],
            [fieldPath: "createdAt", label: "Created At", type: "DateTime", selectionPath: "createdAt"],
            [fieldPath: "updatedAt", label: "Updated At", type: "DateTime", selectionPath: "updatedAt"],
            [fieldPath: "processedAt", label: "Processed At", type: "DateTime", selectionPath: "processedAt"],
            [fieldPath: "cancelledAt", label: "Cancelled At", type: "DateTime", selectionPath: "cancelledAt"],
            [fieldPath: "email", label: "Email", type: "String", selectionPath: "email"],
            [fieldPath: "totalPrice", label: "Total Price", type: "Money", selectionPath: "totalPrice"],
            [fieldPath: "displayFinancialStatus", label: "Financial Status", type: "String", selectionPath: "displayFinancialStatus"],
            [fieldPath: "displayFulfillmentStatus", label: "Fulfillment Status", type: "String", selectionPath: "displayFulfillmentStatus"],
            [fieldPath: "currencyCode", label: "Currency Code", type: "CurrencyCode", selectionPath: "currencyCode"],
            [fieldPath: "totalPriceSet.shopMoney.amount", label: "Total Price Amount", type: "Decimal", selectionPath: "totalPriceSet.shopMoney.amount"],
            [fieldPath: "totalPriceSet.shopMoney.currencyCode", label: "Total Price Currency", type: "CurrencyCode", selectionPath: "totalPriceSet.shopMoney.currencyCode"],
            [fieldPath: "currentTotalPriceSet.shopMoney.amount", label: "Current Total Price Amount", type: "Decimal", selectionPath: "currentTotalPriceSet.shopMoney.amount"],
            [fieldPath: "currentTotalPriceSet.shopMoney.currencyCode", label: "Current Total Price Currency", type: "CurrencyCode", selectionPath: "currentTotalPriceSet.shopMoney.currencyCode"],
            [fieldPath: "currentTotalTaxSet.shopMoney.amount", label: "Current Total Tax Amount", type: "Decimal", selectionPath: "currentTotalTaxSet.shopMoney.amount"],
            [fieldPath: "currentTotalTaxSet.shopMoney.currencyCode", label: "Current Total Tax Currency", type: "CurrencyCode", selectionPath: "currentTotalTaxSet.shopMoney.currencyCode"],
            [fieldPath: "subtotalPriceSet.shopMoney.amount", label: "Subtotal Amount", type: "Decimal", selectionPath: "subtotalPriceSet.shopMoney.amount"],
            [fieldPath: "subtotalPriceSet.shopMoney.currencyCode", label: "Subtotal Currency", type: "CurrencyCode", selectionPath: "subtotalPriceSet.shopMoney.currencyCode"],
            [fieldPath: "customer.id", label: "Customer ID", type: "ID", selectionPath: "customer.id"],
            [fieldPath: "customer.email", label: "Customer Email", type: "String", selectionPath: "customer.email"],
            [fieldPath: "shippingAddress.city", label: "Shipping City", type: "String", selectionPath: "shippingAddress.city"],
            [fieldPath: "shippingAddress.provinceCode", label: "Shipping Province Code", type: "String", selectionPath: "shippingAddress.provinceCode"],
            [fieldPath: "shippingAddress.zip", label: "Shipping Zip", type: "String", selectionPath: "shippingAddress.zip"],
            [fieldPath: "lineItems.id", label: "Line Item ID", type: "ID", selectionPath: "lineItems.edges.node.id", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.name", label: "Line Item Name", type: "String", selectionPath: "lineItems.edges.node.name", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.quantity", label: "Line Item Quantity", type: "Integer", selectionPath: "lineItems.edges.node.quantity", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.sku", label: "Line Item SKU", type: "String", selectionPath: "lineItems.edges.node.sku", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.variant.id", label: "Variant ID", type: "ID", selectionPath: "lineItems.edges.node.variant.id", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.variant.title", label: "Variant Title", type: "String", selectionPath: "lineItems.edges.node.variant.title", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
        ],
    ].asImmutable()

    /**
     * Per-order refund ids and return ids for returns reconciliation (DAR-BE-018, design §7).
     *
     * A SEPARATE source rather than extra fields on ORDER_SOURCE, because ORDER_SOURCE's
     * defaultBulkSelectedFieldPaths is a declared downstream contract for reconciliation schemas and
     * $.records[*] rules; widening it would need a migration plan. Nothing here touches that set.
     *
     * BULK IS NOT AVAILABLE for this source. Both refunds and returns are connections, and
     * ShopifyGraphqlQueryBuilder.buildBulkQuery rejects connection-bearing fields outright because
     * bulk JSONL emits their children as separate __parentId lines that no parser here re-nests.
     * Extraction uses the cursor path (buildQuery + ShopifyGraphqlTransport); see
     * ShopifyReturnRefsSupport.
     *
     * Shape live-probed against gorjana-sandbox.myshopify.com on Admin GraphQL API 2026-01
     * (2026-08-13, DAR-BE-018 Task 1): Order.refunds is NON_NULL -> LIST (plain list, `first` arg
     * only); Order.returns is NON_NULL -> ReturnConnection. See the field-level comments below.
     *
     * REVISION 2026-08-18: also selects Return.refunds (nested under returns.nodes, id-only) —
     * confirmed present across every dated supported API version via shopify.dev's schema reference,
     * not live-probed like the shape above. Used solely to test whether a given return already has a
     * refund; see ShopifyReturnRefsSupport.toRecords for why that determines whether the return gets
     * its own output row.
     */
    private static final Map<String, Object> ORDER_RETURN_REFS_SOURCE = [
        sourceDefinitionId       : SHOPIFY_ORDER_RETURN_REFS,
        label                    : "Shopify Order Return References",
        description              : "Per-order Shopify refund ids and return ids for returns reconciliation.",
        requiredEndpointSystemEnumId: "SHOPIFY_RETURN_REFS",
        queryRoot                : "orders",
        graphqlType              : "Order",
        defaultSortKey           : "CREATED_AT",
        paginationStrategy       : "CURSOR",
        defaultPageSize          : 100,
        maxPageSize              : 250,
        supportedApiVersions     : SUPPORTED_API_VERSIONS,
        defaultSelectedFieldPaths: [
            "id",
            "legacyResourceId",
            "name",
            "createdAt",
            "refunds.id",
            "refunds.createdAt",
            "returns.id",
            "returns.createdAt",
            "returns.refunds",
        ],
        // Bulk is unsupported for this source (see class doc above). This stays an empty list
        // rather than an absent key: copySource() below unconditionally does
        // `new ArrayList(source.defaultBulkSelectedFieldPaths as List)` for every registered
        // source, and `new ArrayList(null)` throws NullPointerException. An empty list is Groovy-falsy,
        // so buildBulkQuery's `source.defaultBulkSelectedFieldPaths ?: source.defaultSelectedFieldPaths`
        // still falls through to defaultSelectedFieldPaths, whose refunds.id/returns.id entries carry
        // connectionRoot and trigger the bulk builder's rejection — same effective behavior, no NPE.
        defaultBulkSelectedFieldPaths: [],
        supportedFilters         : [
            createdAtFrom : [queryName: "created_at", comparator: ">=", type: "datetime", sortKey: "CREATED_AT"],
            createdAtTo   : [queryName: "created_at", comparator: "<", type: "datetime", sortKey: "CREATED_AT"],
            updatedAtFrom : [queryName: "updated_at", comparator: ">=", type: "datetime", sortKey: "UPDATED_AT"],
            updatedAtTo   : [queryName: "updated_at", comparator: "<", type: "datetime", sortKey: "UPDATED_AT"],
        ],
        fields                   : [
            [fieldPath: "id", label: "Order ID", type: "ID", selectionPath: "id", required: true],
            [fieldPath: "legacyResourceId", label: "Legacy Order ID", type: "UnsignedInt64", selectionPath: "legacyResourceId", required: true],
            [fieldPath: "name", label: "Order Name", type: "String", selectionPath: "name"],
            [fieldPath: "createdAt", label: "Created At", type: "DateTime", selectionPath: "createdAt"],
            // LIVE-PROBED 2026-08-13 on API 2026-01: Order.refunds is NON_NULL -> LIST, taking only
            // a `first` arg. It is a PLAIN LIST, not a connection — there is no edges/node (and no
            // nodes) wrapper, so the selectionPath must NOT contain them. Order.returns below IS a
            // ReturnConnection. The two are asymmetric; do not "tidy" them into the same shape.
            // connectionRoot is still set on refunds so buildBulkQuery keeps rejecting this source.
            [fieldPath: "refunds.id", label: "Refund ID", type: "ID", selectionPath: "refunds.id",
             connectionRoot: "refunds", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "refunds.createdAt", label: "Refund Created At", type: "DateTime", selectionPath: "refunds.createdAt",
             connectionRoot: "refunds", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            // returns IS a connection (ReturnConnection). Live-probed shape uses nodes{}, matching
            // ShopifyExchangeStateLookupSupport's existing `returns(first: 10) { nodes { ... } }`.
            [fieldPath: "returns.id", label: "Return ID", type: "ID", selectionPath: "returns.nodes.id",
             connectionRoot: "returns", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "returns.status", label: "Return Status", type: "String", selectionPath: "returns.nodes.status",
             connectionRoot: "returns", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "returns.createdAt", label: "Return Created At", type: "DateTime", selectionPath: "returns.nodes.createdAt",
             connectionRoot: "returns", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            // REVISION 2026-08-18 (returns-refund-grain-alignment plan narrowing): Return.refunds
            // confirmed present — non-null RefundConnection, no required args — on every DATED API
            // version this catalog supports (2025-07, 2025-10, 2026-01, 2026-04; checked directly
            // against shopify.dev's schema reference for each, not assumed). Selected ONLY to test
            // emptiness (does this return have at least one refund) — see
            // ShopifyReturnRefsSupport.toRecords for why that question matters and why `status` could
            // not answer it. `.id` is the sole leaf, mirroring the other id-only selections above; no
            // other Refund field is needed here.
            //   connectionRoot deliberately REUSES "refunds" (the same $refundsFirst variable as
            //   Order.refunds above) rather than getting a page size of its own:
            //   ShopifyGraphqlQueryBuilder's connection-page-size variables are keyed purely by the
            //   literal (leaf) GraphQL field name shared across the WHOLE rendered document, not by
            //   tree position, so this nested "refunds" occurrence and the top-level one cannot carry
            //   independently-valued `first` arguments without either extending that shared mechanism
            //   (used by every other source too) or embedding literal GraphQL argument text into what
            //   every other selectionPath in this catalog treats as a plain dotted identifier path —
            //   neither is done here for one field. The shared value (default 50, clamped to 100) is
            //   still narrow and fully correct for an existence check, since only emptiness is ever
            //   read downstream, never the count.
            [fieldPath: "returns.refunds", label: "Return Has Refund", type: "ID", selectionPath: "returns.nodes.refunds.id",
             connectionRoot: "refunds", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
        ],
    ].asImmutable()

    private static final Map<String, Map<String, Object>> SOURCES_BY_ID = [
        (SHOPIFY_ORDERS)            : ORDER_SOURCE,
        (SHOPIFY_ORDER_RETURN_REFS): ORDER_RETURN_REFS_SOURCE,
    ].asImmutable()

    static List<Map<String, Object>> listSources(Object apiVersion = null) {
        String normalizedApiVersion = normalizeApiVersion(apiVersion)
        return SOURCES_BY_ID.values()
            .findAll { Map<String, Object> source -> !normalizedApiVersion || supportsApiVersion(source, normalizedApiVersion) }
            .collect { Map<String, Object> source -> copySource(source) }
            .sort { left, right -> (left.label ?: left.sourceDefinitionId) <=> (right.label ?: right.sourceDefinitionId) }
    }

    static Map<String, Object> getSource(Object sourceDefinitionId, Object apiVersion = null) {
        String sourceId = normalizeSourceDefinitionId(sourceDefinitionId)
        Map<String, Object> source = SOURCES_BY_ID[sourceId]
        if (!source) return null
        String normalizedApiVersion = normalizeApiVersion(apiVersion)
        if (normalizedApiVersion && !supportsApiVersion(source, normalizedApiVersion)) return null
        return copySource(source)
    }

    static Map<String, Object> requireSource(Object sourceDefinitionId, Object apiVersion = null) {
        Map<String, Object> source = getSource(sourceDefinitionId, apiVersion)
        if (!source) {
            String versionText = normalizeApiVersion(apiVersion)
            throw new IllegalArgumentException(versionText ?
                    "Shopify source ${sourceDefinitionId} is not available for API version ${versionText}." :
                    "Shopify source ${sourceDefinitionId} was not found.")
        }
        return source
    }

    static Map<String, Map<String, Object>> fieldsByPath(Map<String, Object> source) {
        return ((List<Map<String, Object>>) (source.fields ?: []))
            .collectEntries { Map<String, Object> field -> [(field.fieldPath.toString()): field] }
    }

    static List<String> normalizeFieldPaths(Collection fieldPaths) {
        return (fieldPaths ?: [])
            .collect { Object fieldPath -> normalize(fieldPath) }
            .findAll { String fieldPath -> fieldPath }
            .unique()
    }

    static String normalizeSourceDefinitionId(Object sourceDefinitionId) {
        return normalize(sourceDefinitionId)?.toUpperCase()
    }

    static String normalizeApiVersion(Object apiVersion) {
        return normalize(apiVersion)?.toLowerCase()
    }

    static boolean supportsApiVersion(Map<String, Object> source, String apiVersion) {
        if (!apiVersion) return true
        return ((List<String>) (source.supportedApiVersions ?: [])).contains(apiVersion)
    }

    private static Map<String, Object> copySource(Map<String, Object> source) {
        return [
            sourceDefinitionId       : source.sourceDefinitionId,
            label                    : source.label,
            description              : source.description,
            requiredEndpointSystemEnumId: source.requiredEndpointSystemEnumId,
            queryRoot                : source.queryRoot,
            graphqlType              : source.graphqlType,
            defaultSortKey           : source.defaultSortKey,
            paginationStrategy       : source.paginationStrategy,
            defaultPageSize          : source.defaultPageSize,
            maxPageSize              : source.maxPageSize,
            supportedApiVersions     : new ArrayList(source.supportedApiVersions as List),
            defaultSelectedFieldPaths: new ArrayList(source.defaultSelectedFieldPaths as List),
            defaultBulkSelectedFieldPaths: new ArrayList(source.defaultBulkSelectedFieldPaths as List),
            supportedFilters         : (source.supportedFilters as Map).collectEntries { key, value -> [(key): new LinkedHashMap(value as Map)] },
            fields                   : ((List<Map<String, Object>>) source.fields).collect { Map<String, Object> field -> new LinkedHashMap(field) },
        ]
    }
}
