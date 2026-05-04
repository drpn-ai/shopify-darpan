package shopify.graphql

import darpan.facade.common.FacadeSupport

class ShopifySourceCatalog {
    static final String SHOPIFY_ORDERS = "SHOPIFY_ORDERS"
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
        requiredPermissionFlag   : "canReadOrders",
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
            [fieldPath: "email", label: "Email", type: "String", selectionPath: "email"],
            [fieldPath: "displayFinancialStatus", label: "Financial Status", type: "String", selectionPath: "displayFinancialStatus"],
            [fieldPath: "displayFulfillmentStatus", label: "Fulfillment Status", type: "String", selectionPath: "displayFulfillmentStatus"],
            [fieldPath: "currencyCode", label: "Currency Code", type: "CurrencyCode", selectionPath: "currencyCode"],
            [fieldPath: "totalPriceSet.shopMoney.amount", label: "Total Price Amount", type: "Decimal", selectionPath: "totalPriceSet.shopMoney.amount"],
            [fieldPath: "totalPriceSet.shopMoney.currencyCode", label: "Total Price Currency", type: "CurrencyCode", selectionPath: "totalPriceSet.shopMoney.currencyCode"],
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

    private static final Map<String, Map<String, Object>> SOURCES_BY_ID = [
        (SHOPIFY_ORDERS): ORDER_SOURCE,
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

    static List<String> validateSelectedFields(Map<String, Object> source, Collection selectedFieldPaths) {
        Map<String, Map<String, Object>> fieldsByPath = fieldsByPath(source)
        return normalizeFieldPaths(selectedFieldPaths).findAll { String fieldPath -> !fieldsByPath.containsKey(fieldPath) }
    }

    static List<String> normalizeFieldPaths(Collection fieldPaths) {
        return (fieldPaths ?: [])
            .collect { Object fieldPath -> FacadeSupport.normalize(fieldPath) }
            .findAll { String fieldPath -> fieldPath }
            .unique()
    }

    static String normalizeSourceDefinitionId(Object sourceDefinitionId) {
        return FacadeSupport.normalize(sourceDefinitionId)?.toUpperCase()
    }

    static String normalizeApiVersion(Object apiVersion) {
        return FacadeSupport.normalize(apiVersion)?.toLowerCase()
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
            requiredPermissionFlag   : source.requiredPermissionFlag,
            queryRoot                : source.queryRoot,
            graphqlType              : source.graphqlType,
            defaultSortKey           : source.defaultSortKey,
            paginationStrategy       : source.paginationStrategy,
            defaultPageSize          : source.defaultPageSize,
            maxPageSize              : source.maxPageSize,
            supportedApiVersions     : new ArrayList(source.supportedApiVersions as List),
            defaultSelectedFieldPaths: new ArrayList(source.defaultSelectedFieldPaths as List),
            supportedFilters         : (source.supportedFilters as Map).collectEntries { key, value -> [(key): new LinkedHashMap(value as Map)] },
            fields                   : ((List<Map<String, Object>>) source.fields).collect { Map<String, Object> field -> new LinkedHashMap(field) },
        ]
    }
}
