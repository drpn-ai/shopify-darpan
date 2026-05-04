package shopify.graphql

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ShopifySourceCatalogAndQueryBuilderTests {
    @Test
    void catalogExposesShopifyOrdersSourceAndSelectableFields() {
        Map<String, Object> source = ShopifySourceCatalog.requireSource("SHOPIFY_ORDERS", "2026-04")

        assertEquals("SHOPIFY_ORDERS", source.sourceDefinitionId)
        assertEquals("canReadOrders", source.requiredPermissionFlag)
        assertEquals("orders", source.queryRoot)
        assertEquals("UPDATED_AT", source.defaultSortKey)
        assertEquals("CURSOR", source.paginationStrategy)
        assertTrue(((List<String>) source.supportedApiVersions).contains("2026-04"))

        List<String> fieldPaths = ((List<Map<String, Object>>) source.fields).collect { Map<String, Object> field -> field.fieldPath }
        assertTrue(fieldPaths.contains("id"))
        assertTrue(fieldPaths.contains("legacyResourceId"))
        assertTrue(fieldPaths.contains("updatedAt"))
        assertTrue(fieldPaths.contains("totalPriceSet.shopMoney.amount"))
        assertTrue(fieldPaths.contains("lineItems.sku"))
    }

    @Test
    void queryBuilderProducesPaginatedOrdersQueryWithDateFilters() {
        Map<String, Object> result = ShopifyGraphqlQueryBuilder.buildQuery([
            sourceDefinitionId : "SHOPIFY_ORDERS",
            apiVersion         : "2026-04",
            selectedFieldPaths : [
                "name",
                "updatedAt",
                "totalPriceSet.shopMoney.amount",
                "lineItems.sku",
            ],
            filters            : [
                updatedAtFrom: "2026-04-01T00:00:00Z",
                updatedAtTo  : "2026-04-02T00:00:00Z",
            ],
            pageSize           : 500,
            afterCursor        : "cursor-123",
            connectionPageSizes: [
                lineItems: 75,
            ],
        ])

        assertEquals("SHOPIFY_ORDERS", result.sourceDefinitionId)
        assertEquals("ShopifyOrders", result.operationName)
        assertEquals(250, result.variables.first)
        assertEquals("cursor-123", result.variables.after)
        assertEquals("updated_at:>=2026-04-01T00:00:00Z updated_at:<2026-04-02T00:00:00Z", result.variables.query)
        assertEquals(75, result.variables.lineItemsFirst)
        assertFalse((Boolean) result.variables.reverse)
        assertTrue(((List<String>) result.selectedFieldPaths).contains("id"))
        assertTrue(((List<String>) result.selectedFieldPaths).contains("legacyResourceId"))

        String queryDocument = result.queryDocument as String
        assertTrue(queryDocument.contains('query ShopifyOrders($first: Int!, $after: String, $query: String, $reverse: Boolean!, $lineItemsFirst: Int!)'))
        assertTrue(queryDocument.contains('orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT, reverse: $reverse)'))
        assertTrue(queryDocument.contains('lineItems(first: $lineItemsFirst)'))
        assertTrue(queryDocument.contains("pageInfo"))
        assertTrue(queryDocument.contains("endCursor"))
    }

    @Test
    void queryBuilderNormalizesDateFilterOffsetsToUtcInstants() {
        Map<String, Object> result = ShopifyGraphqlQueryBuilder.buildQuery([
            sourceDefinitionId: "SHOPIFY_ORDERS",
            filters           : [
                createdAtFrom: "2026-05-01T00:00:00-04:00",
                createdAtTo  : "2026-05-02T00:00:00-04:00",
            ],
        ])

        assertEquals("created_at:>=2026-05-01T04:00:00Z created_at:<2026-05-02T04:00:00Z", result.variables.query)
    }

    @Test
    void queryBuilderCombinesDateAndOrderStatusFilters() {
        Map<String, Object> result = ShopifyGraphqlQueryBuilder.buildQuery([
            sourceDefinitionId: "SHOPIFY_ORDERS",
            filters           : [
                createdAtFrom: "2026-05-01T04:00:00Z",
                createdAtTo  : "2026-05-02T04:00:00Z",
                status       : "closed",
            ],
        ])

        assertEquals("created_at:>=2026-05-01T04:00:00Z created_at:<2026-05-02T04:00:00Z status:closed", result.variables.query)
        assertEquals("CREATED_AT", result.sortKey)
    }

    @Test
    void queryBuilderRejectsFieldsOutsideTheConfiguredSourceDefinition() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: "SHOPIFY_ORDERS",
                apiVersion        : "2026-04",
                selectedFieldPaths: ["id", "rawGraphql"],
            ])
        }

        assertTrue(error.message.contains("Unsupported Shopify field path"))
        assertTrue(error.message.contains("rawGraphql"))
    }

    @Test
    void queryBuilderRejectsUnsupportedFilters() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: "SHOPIFY_ORDERS",
                apiVersion        : "2026-04",
                selectedFieldPaths: ["id"],
                filters           : [customWhere: "updated_at:>2026-04-01"],
            ])
        }

        assertTrue(error.message.contains("Unsupported Shopify filter 'customWhere'"))
    }
}
