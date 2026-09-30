package shopify.graphql

import static darpan.common.ValueSupport.normalize

class ShopifySourceCatalog {
    static final String SHOPIFY_ORDERS = "SHOPIFY_ORDERS"
    static final String SHOPIFY_ORDER_RETURN_REFS = "SHOPIFY_ORDER_RETURN_REFS"
    static final String SHOPIFY_ORDER_LINE_UNITS = "SHOPIFY_ORDER_LINE_UNITS"
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
        nodeType                 : "Order",
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
        nodeType                 : "Order",
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
            // Order-level cancellation marker (2026-08-20). A plain scalar on the Order node this
            // source ALREADY fetches, so selecting it costs no extra request and no extra rate-limit
            // points — unlike the OMS point lookup it replaces, which was one HTTP call per chunk of
            // candidate orders. ShopifyReturnRefsSupport.toRecords stamps it onto every event row as
            // orderCancelledAt; ReturnPresenceVerificationSupport reads it from there.
            "cancelledAt",
            // ORDER-LEVEL RETURN STATUS (2026-09-01, DAR-BE-026). Like cancelledAt above, a plain
            // scalar on the Order node this source ALREADY fetches, so it costs no extra request and
            // no extra rate-limit points. It is the OrderReturnStatus aggregate operators see and
            // search on (return_status:in_progress); returns.status below is a different enum.
            "returnStatus",
            "refunds.id",
            "refunds.createdAt",
            "returns.id",
            "returns.createdAt",
            // CLOSED-UNREFUNDED SUPPRESSION (2026-09-01, DAR-BE-027). Return.status, restored as an
            // INTERNAL discriminator only — never a record key, never a rules-board pill. That is the
            // distinction the DAR-BE-026 withdrawal hours earlier turned on: its values are unusable
            // for an operator TYPING a rule (it spells the in-progress state OPEN, a word no Shopify
            // surface shows), which says nothing about code reading it. See ShopifyReturnRefsSupport
            // .toRecords for why CLOSED + no refund means "never synced to OMS".
            "returns.status",
            "returns.refunds",
            // CANCELLED-ITEM DETECTION (2026-08-21). Which lines a refund touched, and which lines the
            // order ever shipped: a refunded line that appears in NO fulfillment was never shipped, and
            // an item that never shipped cannot have been returned. Measured over 25 unmatched vs 8
            // matched refunds: 22/25 vs 0/8 -- perfect separation on the matched side, where
            // restockType scored 1/8 false positives.
            "refunds.lineItemId",
            "fulfillments.lineItemId",
            "fulfillments.lineItemQuantity",
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
            // Nullable DateTime on Order — null means "not cancelled". Present on every dated API
            // version this catalog supports; it is already selected by the SHOPIFY_ORDERS source
            // above, so it carries no new document-validation risk of the kind that broke
            // returns.nodes.refunds (see that field's comment below).
            [fieldPath: "cancelledAt", label: "Order Cancelled At", type: "DateTime", selectionPath: "cancelledAt"],
            // Order.returnStatus — non-null OrderReturnStatus enum, no args. Live-probed on gorjana
            // 2026-09-01 (API 2024-10, HTTP 200) alongside returns.nodes.status in one document, so
            // the two coexisting in this selection is verified, not assumed.
            [fieldPath: "returnStatus", label: "Order Return Status", type: "String", selectionPath: "returnStatus"],
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
            //
            // LIVE-FAILURE FIX (same day, 2026-08-18): the selectionPath below first shipped as
            // "returns.nodes.refunds.id" — missing the `.nodes` a RefundConnection requires before its
            // leaf field, exactly the mistake this same comment warns against for Order.refunds above
            // (a plain LIST, no `.nodes`) versus Order.returns (a connection, WITH `.nodes`). A real
            // reconciliation run against a live store hit that bug immediately: Shopify's GraphQL
            // validator rejected the whole query with "Field 'id' doesn't exist on type
            // 'RefundConnection'" (RefundConnection has no `id` field of its own — only `.nodes[].id`
            // or `.edges[].node.id` do). Corrected to "returns.nodes.refunds.nodes.id". See
            // ShopifyReturnRefsSupportTests / ShopifySourceCatalogAndQueryBuilderTests for the
            // regression coverage this gap in testing left: the fixture-based test only validated a
            // hand-shaped JSON response against itself and could never have caught a malformed
            // selection — a query-DOCUMENT assertion (returnRefsQuerySelectsTheNestedRefundsConnectionAsNodesNotAsAPlainId)
            // was added specifically because it would have failed before this fix.
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
            // refunds is a plain LIST already carrying refundsFirst; refundLineItems inside it is a
            // CONNECTION and needs its own page-size variable, which is what connectionRoot supplies
            // here -- renderFieldName adds (first: $x) to any field whose name has a matching variable.
            [fieldPath: "refunds.lineItemId", label: "Refunded Line Item ID", type: "ID",
             selectionPath: "refunds.refundLineItems.nodes.lineItem.id",
             connectionRoot: "refundLineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 250],
            // Two descriptors for one nested path ON PURPOSE: connectionRoot is one-per-descriptor, and
            // BOTH fulfillments and fulfillmentLineItems need a first: arg. Declaring each against a
            // different leaf creates both variables; the builder merges the paths into one subtree.
            // Do not "tidy" these into a single entry -- the inner connection would lose its argument.
            [fieldPath: "fulfillments.lineItemId", label: "Fulfilled Line Item ID", type: "ID",
             selectionPath: "fulfillments.fulfillmentLineItems.nodes.lineItem.id",
             connectionRoot: "fulfillments", connectionDefaultPageSize: 50, connectionMaxPageSize: 250],
            [fieldPath: "fulfillments.lineItemQuantity", label: "Fulfilled Quantity", type: "Int",
             selectionPath: "fulfillments.fulfillmentLineItems.nodes.quantity",
             connectionRoot: "fulfillmentLineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 250],
            // Return.status — a ReturnStatus enum (REQUESTED, OPEN, CLOSED, DECLINED, CANCELED), no
            // args. Live-probed on gorjana 2026-09-01 (API 2024-10, HTTP 200) in one document
            // alongside the order-level returnStatus above, so the two coexisting is verified.
            //
            // SCOPE OF THE 2026-08-18 REJECTION, restated because this field keeps attracting it: that
            // rejection is about `status` as the REFUNDED/UNREFUNDED discriminator, and it still
            // stands — Return.refunds below answers that, because a return can reach CLOSED with zero
            // refunds. DAR-BE-027 uses status for a DIFFERENT question, and reaches the opposite
            // conclusion from the same Shopify fact: precisely because CLOSED does not imply a refund,
            // a CLOSED return whose refunds connection is empty is one OMS never booked. Both fields
            // are read, neither substitutes for the other; do not "tidy" either away.
            [fieldPath: "returns.status", label: "Return Status", type: "String", selectionPath: "returns.nodes.status",
             connectionRoot: "returns", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "returns.refunds", label: "Return Has Refund", type: "ID", selectionPath: "returns.nodes.refunds.nodes.id",
             connectionRoot: "refunds", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
        ],
    ].asImmutable()

    /**
     * DAR-BE-050. One record per ORDER LINE UNIT, for the Shopify -> OMS unit presence pair.
     *
     * Shares ORDER_SOURCE's field definitions - the lineItems.* paths already exist there for the
     * cursor path - plus three state scalars of its own (see fields below), and otherwise differs
     * in exactly two ways.
     *
     * allowsBulkConnections: this source's extractor EXPECTS __parentId child lines, because they
     * ARE its records. Measured against gorjana production 2026-09-02: bulk `orders { lineItems }`
     * is READABLE on the live token while fulfillmentOrders is DENIED, so this is the sweep that
     * works today rather than the one the vendor's docs point at.
     *
     * supportedApiVersions: its own list, including 2024-10. gorjana production runs 2024-10 while
     * the shared SUPPORTED_API_VERSIONS starts at 2025-07, so a source restricted to the shared
     * list could not run on the one tenant this feature was built for. The shared constant is
     * deliberately NOT widened - it is a contract the other sources rely on - and this divergence
     * is narrow, intentional, and confined to the source that needs it.
     *
     * DECLARATION ORDER IS LOAD-BEARING: this must stay ABOVE SOURCES_BY_ID. Static fields
     * initialise top-down, so a source declared below the registry is still null when the registry
     * captures it, and every lookup then fails with "not available for API version" - an error
     * that points at the version list rather than at the ordering.
     */
    private static final Map<String, Object> ORDER_LINE_UNITS_SOURCE = [
        sourceDefinitionId          : SHOPIFY_ORDER_LINE_UNITS,
        label                       : "Shopify Order Line Units",
        description                 : "One record per Shopify order line UNIT for order-line presence reconciliation.",
        requiredEndpointSystemEnumId: "SHOPIFY_ORDER_LINE_UNITS",
        queryRoot                   : "orders",
        nodeType                    : "Order",
        graphqlType                 : "Order",
        allowsBulkConnections       : true,
        defaultSortKey              : "CREATED_AT",
        paginationStrategy          : "CURSOR",
        defaultPageSize             : 100,
        maxPageSize                 : 250,
        supportedApiVersions        : (["2024-10"] + SUPPORTED_API_VERSIONS).unique().asImmutable(),
        defaultSelectedFieldPaths   : [
            "id",
            "legacyResourceId",
            "name",
            "createdAt",
        ].asImmutable(),
        // The JSONL record shape is a downstream contract for the unit compare key.
        // legacyResourceId is load-bearing rather than informational: OMS stores the NUMERIC
        // Shopify order id, so dropping it makes every record key on a gid and the whole window
        // read as 100% different.
        defaultBulkSelectedFieldPaths: [
            "id",
            "legacyResourceId",
            "name",
            "createdAt",
            "lineItems.id",
            "lineItems.quantity",
            "lineItems.sku",
            "lineItems.name",
            // Tri-system unit state (spec 2026-09-30 D1). All three are plain scalars, so they are
            // bulk-legal, and all three were live-probed on gorjana prod (API 2024-10).
            // ShopifyOrderLineUnitSupport derives each unit's OPEN / FULFILLED / REMOVED state from
            // the two quantities and stamps returnStatus onto every unit as orderReturnStatus.
            "returnStatus",
            "lineItems.currentQuantity",
            "lineItems.unfulfilledQuantity",
            // The channel, stamped onto every unit as orderSourceName. POS refunds create no Return
            // object, so a POS return reads returnStatus NO_RETURN; A-cancel excludes pos by this.
            "sourceName",
        ].asImmutable(),
        supportedFilters            : ORDER_SOURCE.supportedFilters,
        // ORDER_SOURCE's definitions PLUS the three state scalars. They are appended here and not
        // added to ORDER_SOURCE, so the ORDERS field catalog (what the field picker offers) does
        // not change.
        fields                      : (ORDER_SOURCE.fields + [
            [fieldPath: "returnStatus", label: "Order Return Status", type: "String", selectionPath: "returnStatus"],
            [fieldPath: "sourceName", label: "Order Source Name", type: "String", selectionPath: "sourceName"],
            [fieldPath: "lineItems.currentQuantity", label: "Line Item Current Quantity", type: "Integer", selectionPath: "lineItems.edges.node.currentQuantity", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
            [fieldPath: "lineItems.unfulfilledQuantity", label: "Line Item Unfulfilled Quantity", type: "Integer", selectionPath: "lineItems.edges.node.unfulfilledQuantity", connectionRoot: "lineItems", connectionDefaultPageSize: 50, connectionMaxPageSize: 100],
        ]).asImmutable(),
    ].asImmutable()

    private static final Map<String, Map<String, Object>> SOURCES_BY_ID = [
        (SHOPIFY_ORDERS)            : ORDER_SOURCE,
        (SHOPIFY_ORDER_RETURN_REFS): ORDER_RETURN_REFS_SOURCE,
        (SHOPIFY_ORDER_LINE_UNITS): ORDER_LINE_UNITS_SOURCE,
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
            nodeType                 : source.nodeType,
            graphqlType              : source.graphqlType,
            defaultSortKey           : source.defaultSortKey,
            paginationStrategy       : source.paginationStrategy,
            defaultPageSize          : source.defaultPageSize,
            maxPageSize              : source.maxPageSize,
            // DAR-BE-050: this whitelist is why a flag must be COPIED as well as declared.
            // buildBulkQuery reads the source through requireSource -> copySource, so a key added
            // to a source map but not here is silently stripped, and the source is then rejected
            // by the very guard it opts out of - with an error naming lineItems rather than the
            // omission that actually caused it.
            allowsBulkConnections    : source.allowsBulkConnections == true,
            supportedApiVersions     : new ArrayList(source.supportedApiVersions as List),
            defaultSelectedFieldPaths: new ArrayList(source.defaultSelectedFieldPaths as List),
            defaultBulkSelectedFieldPaths: new ArrayList(source.defaultBulkSelectedFieldPaths as List),
            supportedFilters         : (source.supportedFilters as Map).collectEntries { key, value -> [(key): new LinkedHashMap(value as Map)] },
            fields                   : ((List<Map<String, Object>>) source.fields).collect { Map<String, Object> field -> new LinkedHashMap(field) },
        ]
    }
}
