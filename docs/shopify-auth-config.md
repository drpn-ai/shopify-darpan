# Shopify Auth Config

The `shopify-darpan` component owns Shopify Admin API configuration for Darpan integrations. Shopify uses one Admin GraphQL endpoint per shop and API version, so the component stores the shop/API URL and API version directly on the auth config instead of maintaining separate endpoint records.

## Services

The facade services live in `facade.ShopifyFacadeServices`:

- `list#ShopifyAuthConfigs` returns configs the active tenant owns or has been shared, with pagination, secret indicators, `companyLabel`, and `isShared`.
- `get#ShopifyAuthConfig` returns one owned or shared config with the token redacted.
- `save#ShopifyAuthConfig` creates or updates one config (owner or shared peer).
- `delete#ShopifyAuthConfig` deletes one config (owner only; see Access Rules).
- `probe#ShopifyAuthConnection` runs connection diagnostics (internal; see below).

Catalog, query-builder, and transport services are described in `shopify/graphql-query-builder.md`.

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
- `canReadOrders` (legacy; see Endpoint enablement)

List, get, and save responses never return `accessToken`. They return `hasAccessToken` so callers can show whether a token is already stored.

### Shop API URL rules

`shopApiUrl` must be an absolute `https` URL whose host ends with `.myshopify.com` — custom domains and plain `http` are rejected on save with the canonical outbound-policy reason. Loopback, private (RFC1918), link-local, CGNAT, and cloud-metadata targets are always rejected, and trailing slashes are trimmed. The same outbound policy is re-checked at request time in the GraphQL transport (and, with no host allow-list, on the off-domain bulk-result download URL), so a `shopApiUrl` mutated out-of-band — direct DB edit or data import — is still blocked before any request is sent.

`timeZone` is an IANA timezone ID such as `America/Chicago`. Darpan stores it as the Shopify source timezone for source metadata and direct source-level extraction. Saved-run and automation reconciliation windows are owned by the Darpan tenant or automation window timezone first, then sent to Shopify as UTC GraphQL filters. When omitted, the config defaults to `UTC`.

## Access Rules

Configs are owned by a tenant through `companyUserGroupId`. Reads return records the active tenant owns plus records shared to it through `ConfigTenantAccess` (config type `SCFG_SHOPIFY_AUTH`). Writes require active-tenant edit access, so view-only tenant users can list and get configs but cannot create or update them.

- A shared peer may read and edit every field of a shared config. Sharing widens access to the same encrypted credential; it is not a copy.
- Delete is owner-only. A peer gets a "shared from another tenant" error. The owner cannot delete a config that still has active grants; revoke them first.
- A caller with no standing gets the same "was not found" message whether the config is missing or belongs to another tenant.
- Sharing is managed by the darpan component: `facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess`, `revoke#ConfigTenantAccess`, and `list#ConfigTenantAccess`.

Lookups resolve the row without the active-company entity filter and then apply the owner-or-peer check (`ShopifyAuthConfigSupport.findAuthConfig` / `requireTenantAuthConfigAccess`). Keep both steps; either alone is wrong.

When saving an existing config, a blank `accessToken` preserves the stored token. New configs require an access token because they are not usable without one.

## Endpoint enablement

One config can serve three endpoints: `SHOPIFY` (orders), `SHOPIFY_ORDER_LINE_UNITS`, and `SHOPIFY_RETURN_REFS`. Each is enabled or disabled per config through `facade.SourceEndpointFacadeServices.list#SourceConfigEndpoints` and `store#SourceConfigEndpointAccess` (owner only). An absent `SourceConfigEndpointAccess` row means enabled.

`ShopifyAuthConfigSupport.requireUsableAuthConfig` is the single usability gate: the config exists, the tenant may use it, `isActive` is `Y`, and the requested endpoint is enabled. Each extractor names its own endpoint; `execute#ShopifyGraphql` and every lookup require `SHOPIFY`.

`canReadOrders` no longer gates anything. The one-time `migrate#SourceConfigEndpointAccess` upgrade service turned `canReadOrders='N'` into disabled endpoint rows. The column is still stored and returned.

Enabling an endpoint does not add API scopes to the token. The returns endpoint and the exchange lookups need `read_returns`.

## Connection probe

`facade.ShopifyFacadeServices.probe#ShopifyAuthConnection` is registered as `healthCheckServiceName` on the Shopify connector rows and is called only through `facade.SettingsFacadeServices.test#SourceConnection`, which owns the permission gate. It is read-only and never retries (5s connect, 10s read).

It runs three stages and returns check rows, `nextStage`, and the config's per-endpoint enablement:

1. `credential` — the config loads and the stored token decrypts. A decrypt failure is reported as a credential problem, not a connectivity one.
2. `connect` — `{ shop { name myshopifyDomain } }` proves shop, token, and API version together.
3. `orders` — reads one order. An access-denied answer names `read_orders`, and also `read_returns` when the returns endpoint is enabled.

A rejected credential is a check row, not a service error; the probe never writes to `ec.message`.
