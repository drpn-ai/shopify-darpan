# Shopify GraphQL Catalog And Query Builder

The `shopify-darpan` component builds Shopify Admin GraphQL extraction queries from structured source definitions. Setup flows should choose a source, fields, filters, and pagination settings. They should not expose a raw GraphQL editor.

## Reference Docs

This implementation follows Shopify's current Admin GraphQL documentation:

- [GraphQL Admin API reference](https://shopify.dev/docs/api/admin-graphql/latest): endpoint format, access-token header, and QueryRoot model.
- [Admin GraphQL `orders` query](https://shopify.dev/docs/api/admin-graphql/latest/queries/orders): `orders` connection arguments, cursor pagination, `query` filters, and `sortKey`.
- [Bulk operation queries](https://shopify.dev/docs/api/usage/bulk-operations/queries): `bulkOperationRunQuery`, operation polling, JSONL result downloads, and bulk-operation limits.
- [Shopify API limits](https://shopify.dev/docs/api/usage/limits): GraphQL cost metadata under `extensions.cost` and retry-aware throttling behavior.

## Source Catalog

`SHOPIFY_ORDERS` is the first configured source definition. It defines:

- required permission flag: `canReadOrders`
- query root: `orders`
- default sort key: `UPDATED_AT`
- pagination strategy: cursor pagination
- supported filters: `updatedAtFrom`, `updatedAtTo`, `createdAtFrom`, `createdAtTo`, `processedAtFrom`, `processedAtTo`, and `status`
- selectable fields for order headers, money sets, customer identity, shipping address, and line items

The catalog is exposed through:

- `facade.ShopifyFacadeServices.list#ShopifySourceDefinitions`
- `facade.ShopifyFacadeServices.get#ShopifySourceDefinition`

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

The transport:

- builds `/admin/api/{apiVersion}/graphql.json`
- sends `Content-Type: application/json`
- sends `X-Shopify-Access-Token`
- parses `data`, `errors`, and `extensions.cost`
- retries HTTP 429 and 5xx responses within the configured attempt count
- returns safe errors without exposing the stored access token

## Bulk Operations Extraction

`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders` is the automation-facing order extractor. It builds a Bulk Operations query for the normalized `created_at` window, starts the operation with `bulkOperationRunQuery`, polls Shopify until the operation completes or fails, downloads the completed JSONL result, and writes two outputs under the reconciliation run data-manager folder:

- a raw `.jsonl` sidecar copied from Shopify's bulk result
- the existing `.json` reconciliation input wrapper with `metadata` and normalized `records`

Shopify allows only one Bulk Operation to run for a shop/app at a time. When the start mutation returns a safe error indicating another bulk operation is already running, the extractor retries the start request every 2 minutes up to 10 times before failing the run. Tests can override those defaults with `bulkStartRetryAttempts` and `bulkStartRetryDelayMillis`.

The JSON wrapper remains the source file consumed by Darpan reconciliation, so saved runs and automation rules can continue to use `$.records[*].id`. The raw JSONL sidecar is retained for audit/debugging. Metadata records the bulk operation id, status, object count, file size, poll count, start retry count, search query, and raw sidecar location, but it does not persist Shopify's temporary result URL or any access token.

## Automation Integration

The source catalog and query builder are the Shopify side of the API date-range automation contract. `SHOPIFY_ORDERS` requires a Shopify auth config with `canReadOrders=true`, and selected field paths must be validated against the catalog before any GraphQL document is built.

The core automation executor in `darpan` expects API sources to provide an extraction service through `ReconciliationAutomationSource.safeMetadataJson.extractServiceName`. Shopify auth source options set that to `reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders`, so automation and manual API-backed saved runs use the same Bulk Operations extraction path.

Automation-facing Shopify output should return:

- `dataAvailable`
- `fileLocation` or `dataManagerPath`
- `fileName`
- `fileTypeEnumId`
- `schemaFileName`
- `recordCount`

The output and all request metadata must stay secret-safe. Do not include the stored access token or authorization headers in logs, facade responses, generated source files, or execution metadata.
