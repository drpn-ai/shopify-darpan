# Shopify Auth Config

The `shopify-darpan` component owns Shopify Admin API configuration for Darpan integrations. Shopify uses one Admin GraphQL endpoint per shop and API version, so the component stores the shop/API URL and API version directly on the auth config instead of maintaining separate endpoint records.

## Services

The facade services live in `facade.ShopifyFacadeServices`:

- `list#ShopifyAuthConfigs` returns active-tenant configs with pagination and secret indicators.
- `get#ShopifyAuthConfig` returns one active-tenant config with the token redacted.
- `save#ShopifyAuthConfig` creates or updates one active-tenant config.

## Config Contract

`darpan.shopify.ShopifyAuthConfig` stores:

- `shopifyAuthConfigId`
- `description`
- `companyUserGroupId`
- `createdByUserId`
- `shopApiUrl`
- `apiVersion`
- `timeZone`
- encrypted `accessToken`
- `isActive`
- `canReadOrders`

List, get, and save responses never return `accessToken`. They return `hasAccessToken` so callers can show whether a token is already stored.

`timeZone` is an IANA timezone ID such as `America/Chicago`. Darpan stores it as the Shopify source timezone for source metadata and direct source-level extraction. Saved-run and automation reconciliation windows are owned by the Darpan tenant or automation window timezone first, then sent to Shopify as UTC GraphQL filters. When omitted, the config defaults to `UTC`.

## Access Rules

Configs are tenant scoped through `companyUserGroupId`. Reads only return records for the active tenant. Writes require active-tenant edit access, so view-only tenant users can list and get configs but cannot create or update them.

When saving an existing config, a blank `accessToken` preserves the stored token. New configs require an access token because they are not usable without one.
