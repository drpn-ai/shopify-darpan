# Shopify GraphQL Catalog And Query Builder

The `shopify-darpan` component builds Shopify Admin GraphQL extraction queries from structured source definitions. Setup flows should choose a source, fields, filters, and pagination settings. They should not expose a raw GraphQL editor.

## Reference Docs

This implementation follows Shopify's current Admin GraphQL documentation:

- [GraphQL Admin API reference](https://shopify.dev/docs/api/admin-graphql/latest): endpoint format, access-token header, and QueryRoot model.
- [Admin GraphQL `orders` query](https://shopify.dev/docs/api/admin-graphql/latest/queries/orders): `orders` connection arguments, cursor pagination, `query` filters, and `sortKey`.
- [Bulk operation queries](https://shopify.dev/docs/api/usage/bulk-operations/queries): `bulkOperationRunQuery`, operation polling, JSONL result downloads, and bulk-operation limits.
- [Shopify API limits](https://shopify.dev/docs/api/usage/limits): GraphQL cost metadata under `extensions.cost` and retry-aware throttling behavior.

## Source Catalog

`ShopifySourceCatalog` defines three sources. Each names the endpoint (`requiredEndpointSystemEnumId`) a config must have enabled before the source can run (see `shopify-auth-config.md`, "Endpoint enablement").

| Source | Endpoint | Record grain | Extraction path |
|---|---|---|---|
| `SHOPIFY_ORDERS` | `SHOPIFY` | one order | Bulk Operations |
| `SHOPIFY_ORDER_LINE_UNITS` | `SHOPIFY_ORDER_LINE_UNITS` | one order-line unit | Bulk Operations, with connections allowed |
| `SHOPIFY_ORDER_RETURN_REFS` | `SHOPIFY_RETURN_REFS` | one refund or return event | cursor pagination |

All three use the `orders` query root and cursor pagination for the interactive builder (default page size 100, max 250).

`SHOPIFY_ORDERS`:

- default sort key: `UPDATED_AT`
- supported filters: `updatedAtFrom`, `updatedAtTo`, `createdAtFrom`, `createdAtTo`, `processedAtFrom`, `processedAtTo`, and `status`
- selectable fields for order headers, money sets, customer identity, shipping address, transactions, and line items

`SHOPIFY_ORDER_LINE_UNITS` reuses the order field definitions plus `returnStatus`, `sourceName`, `lineItems.currentQuantity`, and `lineItems.unfulfilledQuantity`. Its supported API versions add `2024-10` to the shared list (`2025-07`, `2025-10`, `2026-01`, `2026-04`, `unstable`).

`SHOPIFY_ORDER_RETURN_REFS` selects order ids, `refunds`, and `returns` (with each return's nested `refunds`, used only to test whether the return has been refunded). It filters on `created_at` or `updated_at`.

The catalog is exposed through:

- `facade.ShopifyFacadeServices.list#ShopifySourceDefinitions`
- `facade.ShopifyFacadeServices.get#ShopifySourceDefinition`

Both catalog services are internal-only (`allow-remote="false"`) until a setup-flow UI consumer is wired; flip them back to remote together with that flow.

Each source also owns `defaultBulkSelectedFieldPaths`, the exact field set its Bulk Operations extraction selects. The JSONL record shape is a downstream contract for reconciliation schemas and `$.records[*]` rules, so those entries must not be dropped or renamed without a migration plan. For line units, `legacyResourceId` is load-bearing: OMS stores the numeric Shopify order id.

### `allowsBulkConnections`

Bulk JSONL returns the children of a nested connection (for example `lineItems`) as separate lines carrying `__parentId`. An order-grain parser would misread them as orders, so `buildBulkQuery` rejects connection-bearing fields by default.

`allowsBulkConnections` is a per-SOURCE opt-out, set only on `SHOPIFY_ORDER_LINE_UNITS` (DAR-BE-050). For that source the child lines are the records. The flag lives on the source, not on the field: the `lineItems.*` field definitions are shared with `SHOPIFY_ORDERS`, which must keep rejecting them. `copySource` carries the flag through `requireSource`.

## Query Builder

`facade.ShopifyFacadeServices.build#ShopifyGraphqlQuery` accepts a source ID, selected field paths, filters, page size, cursor, direction, and nested connection page sizes. It validates all selected fields against the configured source definition before generating a query.

For `SHOPIFY_ORDERS`, date filters are normalized to UTC ISO-8601 instants and translated into Shopify Admin `orders` search syntax:

```text
updated_at:>='2026-04-01T00:00:00Z' updated_at:<'2026-04-02T00:00:00Z'
```

Shopify search syntax accepts UTC ISO-8601 date-time values directly. The builder renders normalized UTC instants as quoted search values so the generated query string is stable when embedded in GraphQL variables or Bulk Operations documents.

Order status can be combined with the same date filters:

```text
created_at:>='2026-05-01T04:00:00Z' created_at:<'2026-05-02T04:00:00Z' status:closed
```

When a range filter maps to a known order sort key, the generated query uses the matching `sortKey` (`CREATED_AT`, `PROCESSED_AT`, or `UPDATED_AT`) to keep Shopify range queries aligned with the searched field.

Order records include both `id` and `legacyResourceId`. Darpan preserves the GraphQL GID as `shopifyGid` and normalizes the extracted `id` field to `legacyResourceId` for API Order Sync so Shopify rows compare against HotWax order `externalId` values.

API Order Sync extracts Shopify and HotWax independently from the same tenant-normalized date window before compare. Shopify runs the `orders` search through Bulk Operations with quoted `created_at` UTC bounds and `sortKey: CREATED_AT`, while HotWax uses OMS `order_date` (`orderDate_from` and `orderDate_thru`) epoch milliseconds for the same tenant-derived instants.

The generated query uses variables for cursor pagination:

```graphql
query ShopifyOrders($first: Int!, $after: String, $query: String, $reverse: Boolean!) {
  orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT, reverse: $reverse) {
    edges {
      cursor
      node {
        id
        name
        updatedAt
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
```

## Transport

`facade.ShopifyFacadeServices.execute#ShopifyGraphql` is internal-only. It executes generated GraphQL documents through a tenant-owned Shopify auth config, using the configured shop/API URL, API version, and encrypted access token.

The raw `queryDocument` service parameter allows literal GraphQL/search characters such as `<` and `>` because generated date-window queries embed Shopify search bounds inside the GraphQL document.

The transport:

- builds `/admin/api/{apiVersion}/graphql.json`
- sends `Content-Type: application/json`
- sends `X-Shopify-Access-Token`
- parses `data`, `errors`, and `extensions.cost`
- retries HTTP 429 and 5xx responses within the configured attempt count
- retries HTTP-200 GraphQL errors whose `extensions.code` is `THROTTLED` or `MAX_COST_EXCEEDED` within the same attempt count, pausing between attempts using a wait derived from `extensions.cost.throttleStatus` (default 1s, capped at 10s) unless the caller passes an explicit `retryDelayMillis`
- never retries mutation documents — GraphQL mutations are non-idempotent and per the spec must begin with the `mutation` keyword, so the transport hard-caps them to a single attempt
- re-validates the resolved endpoint against the central outbound URL policy (https-only, host must end in `.myshopify.com`, loopback/link-local/RFC1918/cloud-metadata targets blocked) immediately before each network request; tests that inject `httpExecutor` bypass the network path and this check
- returns safe errors without exposing the stored access token

## Bulk Operations Extraction

`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders` is the automation-facing order extractor. It builds a Bulk Operations query for the normalized `created_at` window, starts the operation with `bulkOperationRunQuery`, polls Shopify until the operation completes or fails, downloads the completed JSONL result, and writes two outputs under the reconciliation run data-manager folder:

- a raw `.jsonl` sidecar copied from Shopify's bulk result
- the existing `.json` reconciliation input wrapper with `metadata` and normalized `records`

The bulk query document is generated by `ShopifyGraphqlQueryBuilder.buildBulkQuery` from the shared source catalog (`defaultBulkSelectedFieldPaths`), so the extraction selection cannot drift from the catalog contract. Bulk documents carry no GraphQL variables, pagination, or `pageInfo` artifacts (Bulk Operations reject variables and stream the complete result). Connection-bearing fields such as `lineItems.*` are rejected on the orders bulk query; only a source with `allowsBulkConnections` (line units, below) may select them.

Shopify allows only one Bulk Operation to run for a shop/app at a time. When the start mutation returns a safe error indicating another bulk operation is already running, the extractor retries the start request every 2 minutes up to 10 times before failing the run. Tests can override those defaults with `bulkStartRetryAttempts` and `bulkStartRetryDelayMillis`.

Because `bulkOperationRunQuery` is a non-idempotent mutation, the transport-level attempt count is hard-capped to 1 for the start request; recovery from transient failures relies on the 2-minute concurrent-start retry loop above, not transport retries.

The completed-result download is an idempotent GET, so transient 429/5xx download failures retry (3 attempts by default, overridable with `downloadMaxAttempts`/`downloadRetryDelayMillis`). The download URL Shopify returns is served off-domain (a signed storage URL) and is re-validated against the outbound URL policy with no host allow-list before connecting — https is forced and loopback/link-local/RFC1918/cloud-metadata targets are blocked — and redirects are never auto-followed. After parsing, the record count is reconciled against the operation's reported `objectCount`; a completed operation that reports objects with no result URL, or a download that parses to fewer records than Shopify produced, fails the extraction instead of silently feeding reconciliation incomplete data.

The JSON wrapper remains the source file consumed by Darpan reconciliation, so saved runs and automation rules can continue to use `$.records[*].id`. The raw JSONL sidecar is retained for audit/debugging. Metadata records the bulk operation id, status, object count, file size, poll count, start retry count, search query, and raw sidecar location, but it does not persist Shopify's temporary result URL or any access token.

## Order-Line Unit Extraction

`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderLineUnits` (DAR-BE-050) writes one record per order-line UNIT for the Shopify-to-OMS unit presence pair. It shares submit, poll, download, and file writing with `extract#ShopifyOrders` (both implement `extract#ShopifyBulkWindowExtract`); only record assembly differs.

- It runs the `SHOPIFY_ORDER_LINE_UNITS` source, whose `allowsBulkConnections` lets `lineItems` into the bulk query. `ShopifyOrderLineUnitSupport` re-nests the `__parentId` child lines under their orders.
- A line of quantity N becomes N records with a `unitOrdinal`. The compare key is `(shopifyOrderId, shopifyLineId, unitOrdinal)`, matching `extract#HotWaxOmsOrderLineUnits`. Units, not lines, because OMS explodes a line into single-unit items and the compare is a set anti-join: at line grain a short import still matches.
- Each unit carries `unitState` (`OPEN`, `FULFILLED`, `REMOVED`, or `UNKNOWN` when Shopify sent no quantities), derived from `currentQuantity` and `unfulfilledQuantity`, plus the order-wide `orderReturnStatus` and `orderSourceName`.
- It windows on `created_at`, matching the OMS side's `orderDate`. Windowing one side differently produces structural false positives.
- `recordCount` counts units, not orders. `requestMetadata` always carries `orphanLineItemCount` and `droppedZeroQuantity`; an orphan also raises a run warning.
- `sourceFilters` (`EXCLUDE_IN` / `INCLUDE_IN`) run against assembled units after assembly.

The bulk `orders { lineItems }` read is used rather than `fulfillmentOrders` because some production tokens can read the first and not the second.

## Return-Refs Extraction

`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs` (DAR-BE-018) writes one record per refund or return event for the returns pair. It uses CURSOR pagination through `ShopifyGraphqlTransport`, not Bulk Operations, because refunds and returns are connections on the order.

- Each record is `{refundOrReturnId, refundOrReturnType, orderId, createdAt}` plus order-level fields (`orderCancelledAt`, `refundLineEverFulfilled`, `orderReturnStatus`). Ids are bare (GID tail stripped) so they join against the OMS `externalId`. `refundOrReturnType` is `REFUND` or `RETURN`.
- The Shopify search is a lower-bound-only net on `updated_at` (window start minus `lookbackHours`, default 3). The window is then applied client-side to each event's own `createdAt`, because an order can be much older than a refund on it.
- A return that has a refund emits no `RETURN` row; its refund row is the OMS counterpart. A `CLOSED` return with no refund emits no row either, counted in `requestMetadata.filters.closedUnrefundedReturnsSuppressed`.
- `connectionPageSize` (default 50) is clamped to the catalog maximum of 100. If an order has more refunds or returns than one page, a warning names the order rather than truncating silently.
- `sourceFilters` run against event records. The useful field is `orderReturnStatus` (Shopify `Order.returnStatus`). It is order-wide, so excluding a value also drops that order's refund rows.

## Verification and Exchange Lookups

The saved-run verification pass and the exchange checks call point lookups through `nodes(ids:)`, which reads the primary datastore rather than the search index that backs `orders(query:)`. All four lookups resolve the config through the `SHOPIFY` endpoint gate. A failed lookup returns `ok=false` and never classifies an id as missing.

| Service | Registered on | Purpose |
|---|---|---|
| `lookup#ShopifyOrderIds` | `SHOPIFY` connector `lookupServiceName` | Confirm or refute "missing in Shopify" order differences |
| `lookup#ShopifyRefundOrReturnIds` | `SHOPIFY_RETURN_REFS` connector `lookupServiceName` | Same for the returns pair; each bare id is checked as both a Refund and a Return GID; returns `unresolvedIds` |
| `lookup#ShopifyOrderExchangeState` | `SHOPIFY` connector `exchangeStateLookupServiceName` | Per order, the returns holding exchange lines plus current totals; needs `read_returns` |
| `lookup#ShopifyExchangeSweep` | `SHOPIFY` connector `exchangeSweepServiceName` | Exchanges whose return date falls in the run window; needs `read_returns` |

## Automation Integration

The source catalog and query builder are the Shopify side of the API date-range automation contract. Each source requires a Shopify auth config with its endpoint enabled (`SHOPIFY`, `SHOPIFY_ORDER_LINE_UNITS`, or `SHOPIFY_RETURN_REFS`), and selected field paths must be validated against the catalog before any GraphQL document is built.

The core automation executor in `darpan` resolves each API source's extraction service from its `SourceSystemConnector` row (seeded in `darpan/data/SourceSystemConnectorSeedData.xml`): `SHOPIFY` dispatches to `extract#ShopifyOrders`, `SHOPIFY_ORDER_LINE_UNITS` to `extract#ShopifyOrderLineUnits`, and `SHOPIFY_RETURN_REFS` to `extract#ShopifyOrderReturnRefs`. A legacy `extractServiceName` in `ReconciliationAutomationSource.safeMetadataJson` still takes precedence. Either way the name must be on the connector allow-list. Automation and manual API-backed saved runs use the same extraction path.

Every Shopify extract implements `extract#ShopifyExtractOutputs` and returns:

- `dataAvailable`
- `fileLocation`
- `fileName`
- `fileTypeEnumId`
- `recordCount`
- `requestMetadata`
- `warnings`
- `errors`

A script service returns only declared out-parameters, so a result key added to an extract must be declared on that interface.

The core automation executor derives `dataManagerPath` by mirroring `fileLocation`, and resolves `schemaFileName` from the automation source record's `safeMetadataJson` when the extraction service does not return one — the Shopify services return neither.

The output and all request metadata must stay secret-safe. Do not include the stored access token or authorization headers in logs, facade responses, generated source files, or execution metadata.
