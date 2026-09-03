package shopify.graphql

import org.junit.jupiter.api.Test

import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ShopifySourceCatalogAndQueryBuilderTests {
    @Test
    void catalogExposesShopifyOrdersSourceAndSelectableFields() {
        Map<String, Object> source = ShopifySourceCatalog.requireSource("SHOPIFY_ORDERS", "2026-04")

        assertEquals("SHOPIFY_ORDERS", source.sourceDefinitionId)
        assertEquals("SHOPIFY", source.requiredEndpointSystemEnumId)
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
        assertEquals("updated_at:>='2026-04-01T00:00:00Z' updated_at:<'2026-04-02T00:00:00Z'", result.variables.query)
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

        assertEquals("created_at:>='2026-05-01T04:00:00Z' created_at:<'2026-05-02T04:00:00Z'", result.variables.query)
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

        assertEquals("created_at:>='2026-05-01T04:00:00Z' created_at:<'2026-05-02T04:00:00Z' status:closed", result.variables.query)
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
    void bulkQueryBuilderReproducesLegacyExtractionDocument() {
        Map<String, Object> result = ShopifyGraphqlQueryBuilder.buildBulkQuery([
            sourceDefinitionId: "SHOPIFY_ORDERS",
            operationName     : "DarpanShopifyOrdersByDateWindow",
            filters           : [
                createdAtFrom: "2026-04-01T00:00:00Z",
                createdAtTo  : "2026-04-02T00:00:00Z",
            ],
        ])

        assertEquals("created_at:>='2026-04-01T00:00:00Z' created_at:<'2026-04-02T00:00:00Z'", result.searchQuery)
        assertEquals("CREATED_AT", result.sortKey)

        // The exact field set the pre-unification extraction selected; the JSONL record shape is a
        // downstream contract for reconciliation schemas and $.records[*] rules.
        Set<String> legacyBulkSelection = [
            "id", "legacyResourceId", "name", "createdAt", "updatedAt", "processedAt", "email",
            "cancelledAt", "totalPrice", "displayFinancialStatus", "displayFulfillmentStatus", "currencyCode",
            "currentTotalPriceSet.shopMoney.amount", "currentTotalPriceSet.shopMoney.currencyCode",
            "currentTotalTaxSet.shopMoney.amount", "currentTotalTaxSet.shopMoney.currencyCode",
            "totalPriceSet.shopMoney.amount", "totalPriceSet.shopMoney.currencyCode",
            "subtotalPriceSet.shopMoney.amount", "subtotalPriceSet.shopMoney.currencyCode",
        ] as Set
        assertEquals(legacyBulkSelection, ((List<String>) result.selectedFieldPaths) as Set)

        String queryDocument = result.queryDocument as String
        assertTrue(queryDocument.contains("query DarpanShopifyOrdersByDateWindow {"))
        assertTrue(queryDocument.contains("orders(query: \"created_at:>='2026-04-01T00:00:00Z' created_at:<'2026-04-02T00:00:00Z'\", sortKey: CREATED_AT)"))
        assertTrue(queryDocument.contains("legacyResourceId"))
        assertTrue(queryDocument.contains("cancelledAt"))
        assertTrue(queryDocument.contains("currentTotalTaxSet {"))
        assertTrue(queryDocument.contains("subtotalPriceSet {"))
        assertTrue(queryDocument.contains("shopMoney {"))
        // bulkOperationRunQuery rejects variables; bulk documents carry no pagination artifacts
        assertFalse(queryDocument.contains('$'))
        assertFalse(queryDocument.contains("pageInfo"))
        assertFalse(queryDocument.contains("cursor"))
    }

    @Test
    void bulkQueryBuilderRejectsConnectionFields() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            ShopifyGraphqlQueryBuilder.buildBulkQuery([
                sourceDefinitionId: "SHOPIFY_ORDERS",
                selectedFieldPaths: ["id", "lineItems.sku"],
            ])
        }

        assertTrue(error.message.contains("does not support connection field"))
        assertTrue(error.message.contains("lineItems.sku"))
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

    @Test
    void returnRefsSourceIsRegisteredWithRefundAndReturnIdFields() {
        Map<String, Object> source = ShopifySourceCatalog.getSource(ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS)

        assertNotNull(source, "returns/refunds source must be registered")
        assertEquals("orders", source.queryRoot)
        List<String> paths = ((List) source.fields).collect { ((Map) it).fieldPath as String }
        assertTrue(paths.contains("refunds.id"), "refund ids are the match spine: ${paths}")
        assertTrue(paths.contains("returns.id"), "return ids are the forward backup: ${paths}")
    }

    @Test
    void returnRefsSourceIsRejectedByTheBulkBuilder() {
        // refunds/returns are connections, and bulk JSONL emits connection children as separate
        // __parentId lines that nothing in this codebase re-nests. The cursor path is mandatory.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, {
            ShopifyGraphqlQueryBuilder.buildBulkQuery([
                    sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
            ])
        })
        assertTrue(thrown.message.contains("connection field"),
                "expected the connection-field rejection, got: ${thrown.message}")
    }

    @Test
    void returnRefsCursorQuerySelectsBothIdSetsUnderTheOrder() {
        Map<String, Object> built = ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                operationName     : "DarpanReturnRefs",
                filters           : [createdAtFrom: "2026-05-01T00:00:00Z", createdAtTo: "2026-05-02T00:00:00Z"],
        ])

        String document = built.queryDocument as String
        assertTrue(document.contains("refunds"), "query must select refunds: ${document}")
        assertTrue(document.contains("returns"), "query must select returns: ${document}")
        assertTrue(document.contains("legacyResourceId"), "order identity must be selectable bare")
    }

    @Test
    void returnRefsQuerySelectsTheNestedRefundsConnectionAsNodesNotAsAPlainId() {
        // Regression for a LIVE failure: Return.refunds is a RefundConnection, unlike the order-level
        // Order.refunds (a plain List) it sits alongside in this same source — see the asymmetry
        // ShopifySourceCatalog documents on both fields. Selecting `id` directly off a connection,
        // skipping `.nodes`, is invalid GraphQL: Shopify's own validator rejects the WHOLE query
        // before any data comes back, with "Field 'id' doesn't exist on type 'RefundConnection'" —
        // exactly the error a real reconciliation run hit against a real store. A fixture-only test
        // cannot catch this class of bug: a hand-shaped JSON response only ever gets validated against
        // itself, never against what Shopify's schema actually accepts — which is exactly how the
        // original selectionPath bug shipped with every test green. This asserts on the RENDERED
        // query document instead, the same kind of check that would have failed before this fix.
        Map<String, Object> built = ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                operationName     : "DarpanReturnRefs",
                filters           : [createdAtFrom: "2026-05-01T00:00:00Z", createdAtTo: "2026-05-02T00:00:00Z"],
        ])
        String document = built.queryDocument as String

        // Positive: the nested refunds connection under returns must render as `refunds(first: ...) {
        // nodes { id } }`. Whitespace/indentation is deliberately not pinned down here.
        Pattern nestedRefundsConnection = Pattern.compile('refunds\\(first: [^)]*\\)\\s*\\{\\s*nodes\\s*\\{\\s*id\\s*\\}\\s*\\}')
        assertTrue(nestedRefundsConnection.matcher(document).find(),
                "the returns.refunds selection must be a connection selecting nodes { id }: ${document}")

        // Negative: no `refunds(first: ...) { id ...` may appear anywhere — that is precisely the
        // malformed, pre-fix shape (id selected directly off a connection, no nodes/edges wrapper) that
        // produced the live "Field 'id' doesn't exist on type 'RefundConnection'" error. This also
        // guards the sibling, correctly-plain-list Order.refunds field: its children are createdAt
        // then id (alphabetical), so it never opens with `{ id` either — this pattern only ever
        // matches a regression back to the un-nested selectionPath.
        Pattern refundsSelectingIdDirectly = Pattern.compile('refunds\\(first: [^)]*\\)\\s*\\{\\s*id\\b')
        assertFalse(refundsSelectingIdDirectly.matcher(document).find(),
                "no refunds selection may select id directly off a connection without .nodes first: ${document}")
    }

    @Test
    void returnRefsQuerySelectsTheOrderLevelReturnStatusAndThePerReturnStatusDiscriminator() {
        // TWO STATUS SELECTIONS, DIFFERENT JOBS, and the difference is the whole reason this test
        // reads the way it does.
        //
        // Order.returnStatus (order level) is the OPERATOR-FACING one: it backs the rules-board pill,
        // so its values must be words an operator can see. That is why DAR-BE-026 withdrew the
        // per-return status from the record shape hours earlier on 2026-09-01 — Return.status spells
        // the in-progress state OPEN, while Shopify admin and the return_status: search filter both
        // say IN_PROGRESS, so a rule typed as IN_PROGRESS matched nothing.
        //
        // Return.status (inside the returns connection) is RESTORED HERE by DAR-BE-027 for a job that
        // withdrawal never touched: an INTERNAL discriminator nobody types, selected exactly like
        // returns.refunds beside it. A CLOSED return carrying no refund never synced to OMS at all, so
        // toRecords suppresses its row. It is never emitted as a record key and never offered as a
        // pill — assert that in ShopifyReturnRefsSupportTests, not here.
        //
        // The 2026-08-18 rejection of returns.status as the refunded/unrefunded discriminator is a
        // THIRD question and still stands: Return.refunds remains that discriminator.
        //
        // Asserted on the RENDERED DOCUMENT, not on a response fixture. A fixture is hand-shaped and
        // only ever validated against itself, so it cannot catch a field that fails to render or
        // renders in an invalid position — which is exactly how the returns.nodes.refunds selection
        // bug reached a live store with every test green.
        Map<String, Object> built = ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                operationName     : "DarpanReturnRefs",
                filters           : [createdAtFrom: "2026-05-01T00:00:00Z", createdAtTo: "2026-05-02T00:00:00Z"],
        ])
        String document = built.queryDocument as String

        int returnsAt = document.indexOf("returns(")
        assertTrue(returnsAt >= 0, "the returns connection must still be selected: ${document}")
        // returnStatus must render at ORDER level — before the returns connection opens, not inside it.
        int orderStatusAt = document.indexOf("returnStatus")
        assertTrue(orderStatusAt >= 0, "Order.returnStatus must render: ${document}")
        assertTrue(orderStatusAt < returnsAt,
                "returnStatus must render on the Order, not inside the returns selection: ${document}")
        // The per-return leaf renders INSIDE the returns selection. Searching from returnsAt is safe
        // precisely because the assertion above already proved the order-level returnStatus renders
        // before it, so this cannot be that same field matching on its trailing substring.
        assertTrue(document.indexOf("status", returnsAt) >= 0,
                "Return.status must render inside the returns selection: ${document}")

        // Catalog-level contract, checked alongside the document so a future edit cannot quietly drop
        // the path while some unrelated `returnStatus` keeps the document assertion green.
        Map<String, Object> source = ShopifySourceCatalog.getSource(ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS)
        List paths = (List) source.defaultSelectedFieldPaths
        assertTrue(paths.contains("returnStatus"),
                "returnStatus must be a default-selected path: ${paths}")
        assertTrue(paths.contains("returns.status"),
                "returns.status must be selected again as the closed-unrefunded discriminator (DAR-BE-027): ${paths}")
        assertTrue(paths.contains("returns.refunds"),
                "the refunded-return discriminator is unaffected by either change: ${paths}")
    }

    @Test
    void orderSourceBulkContractIsUnchanged() {
        // Ratchet: DAR-BE-018 must not perturb the declared bulk field set that existing
        // reconciliation schemas and $.records[*] rules depend on.
        Map<String, Object> orderSource = ShopifySourceCatalog.getSource(ShopifySourceCatalog.SHOPIFY_ORDERS)
        List<String> bulkPaths = (List<String>) orderSource.defaultBulkSelectedFieldPaths
        assertFalse(bulkPaths.any { it.startsWith("refunds") || it.startsWith("returns") },
                "returns/refunds must not leak into the orders bulk contract: ${bulkPaths}")
    }

    // ------------------------------------------------ DAR-BE-037 by-id fetch for the returns pair

    @Test
    void buildNodesQueryRendersTheSameOrderSelectionUnderANodesRoot() {
        // The returns extract stops discovering orders through orders(query:) — live-probed at 33.4%
        // coverage — and instead resolves order ids from return-dated events. Those ids are fetched
        // with nodes(ids:), which must carry the SAME Order selection the net used, rendered from the
        // same catalog, or the two paths would drift into different record shapes.
        Map<String, Object> net = ShopifyGraphqlQueryBuilder.buildQuery([
                sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                filters           : [updatedAtFrom: "2026-08-16T14:00:00Z"],
        ])
        Map<String, Object> byId = ShopifyGraphqlQueryBuilder.buildNodesQuery([
                sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
        ])
        String doc = byId.queryDocument as String

        assertTrue(doc.contains("nodes(ids: \$ids)"), "must fetch by id, not by search: ${doc}")
        assertTrue(doc.contains("... on Order"), "nodes() is polymorphic and needs the Order fragment: ${doc}")
        assertTrue(doc.contains("\$ids: [ID!]!"), "ids must be a required non-null ID list: ${doc}")
        assertFalse(doc.contains("\$query"), "a by-id fetch must not carry a search argument: ${doc}")

        // Same leaves as the net's document — proves both come from one catalog rather than a
        // hand-copied selection that will rot.
        ["refunds", "returns", "cancelledAt", "returnStatus"].each { String leaf ->
            assertTrue(doc.contains(leaf), "by-id document lost ${leaf}: ${doc}")
            assertTrue((net.queryDocument as String).contains(leaf), "net document lost ${leaf}")
        }
        // Connection page sizes stay variables so the caller keeps the clamping it already relies on.
        assertTrue(((Map) byId.variables).containsKey("returnsFirst"), "variables: ${byId.variables}")
        assertTrue(((Map) byId.variables).containsKey("refundsFirst"), "variables: ${byId.variables}")
    }
}
