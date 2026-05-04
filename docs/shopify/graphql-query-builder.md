# Shopify GraphQL Catalog And Query Builder

The `shopify-darpan` component builds Shopify Admin GraphQL extraction queries from structured source definitions. Setup flows should choose a source, fields, filters, and pagination settings. They should not expose a raw GraphQL editor.

## Reference Docs

This implementation follows Shopify's current Admin GraphQL documentation:

- [GraphQL Admin API reference](https://shopify.dev/docs/api/admin-graphql/latest): endpoint format, access-token header, and QueryRoot model.
- [Admin GraphQL `orders` query](https://shopify.dev/docs/api/admin-graphql/latest/queries/orders): `orders` connection arguments, cursor pagination, `query` filters, and `sortKey`.
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
updated_at:>=2026-04-01T00:00:00Z updated_at:<2026-04-02T00:00:00Z
```

Shopify search syntax accepts UTC ISO-8601 date-time values directly, so the builder renders normalized UTC instants without wrapping quotes.

Order status can be combined with the same date filters:

```text
created_at:>=2026-05-01T04:00:00Z created_at:<2026-05-02T04:00:00Z status:closed
```

When a range filter maps to a known order sort key, the generated query uses the matching `sortKey` (`CREATED_AT`, `PROCESSED_AT`, or `UPDATED_AT`) to keep Shopify range queries aligned with the searched field.

Order records include both `id` and `legacyResourceId`. Darpan preserves the GraphQL GID as `shopifyGid` and normalizes the extracted `id` field to `legacyResourceId` for API Order Sync so Shopify rows compare against HotWax order `externalId` values.

API Order Sync extracts Shopify and HotWax independently from the same normalized date window before compare. Shopify uses the GraphQL `orders` search query with `created_at` bounds, while HotWax uses OMS `order_date` (`orderDate_from` and `orderDate_thru`) after tenant-timezone normalization.

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

## Automation Integration

The source catalog and query builder are the Shopify side of the API date-range automation contract. `SHOPIFY_ORDERS` requires a Shopify auth config with `canReadOrders=true`, and selected field paths must be validated against the catalog before any GraphQL document is built.

The core automation executor in `darpan` expects API sources to provide an extraction service through `ReconciliationAutomationSource.safeMetadataJson.extractServiceName`. This component currently provides the auth config, source catalog, query builder, and GraphQL transport. A Shopify orders extractor still needs to plug those pieces into the `extractServiceName` contract before a Shopify auth config can be selected as an automation source and produce a data-manager source file.

Automation-facing Shopify output should return:

- `dataAvailable`
- `fileLocation` or `dataManagerPath`
- `fileName`
- `fileTypeEnumId`
- `schemaFileName`
- `recordCount`

The output and all request metadata must stay secret-safe. Do not include the stored access token or authorization headers in logs, facade responses, generated source files, or execution metadata.
