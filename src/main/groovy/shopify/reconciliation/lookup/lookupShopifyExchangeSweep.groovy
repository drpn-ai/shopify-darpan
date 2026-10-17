import shopify.reconciliation.lookup.ShopifyExchangeSweepSupport
import shopify.reconciliation.lookup.ShopifyLookupScriptSupport

ok = false
exchanges = []
truncated = false
errors = []

Map<String, Object> resolved = ShopifyLookupScriptSupport.resolveConnection(ec, shopifyAuthConfigId, companyUserGroupId)
if (resolved.failed) {
    errors = resolved.errors
    return
}

Map<String, Object> result = ShopifyExchangeSweepSupport.sweepExchanges((Map) resolved.connection,
        ((Number) windowStartMillis).longValue(), ((Number) windowEndMillis).longValue(),
        ShopifyLookupScriptSupport.transportOptions(context))

ok = result.ok
exchanges = result.exchanges
truncated = result.truncated
errors = result.errors
