import shopify.reconciliation.lookup.ShopifyLookupScriptSupport
import shopify.reconciliation.lookup.ShopifyOrderLookupSupport

ok = false
foundIds = []
missingIds = []
errors = []

Map<String, Object> resolved = ShopifyLookupScriptSupport.resolveConnection(ec, shopifyAuthConfigId, companyUserGroupId)
if (resolved.failed) {
    errors = resolved.errors
    return
}

Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds((Map) resolved.connection, (List<Object>) orderIds,
        ShopifyLookupScriptSupport.transportOptions(context))

ok = result.ok
foundIds = result.foundIds
missingIds = result.missingIds
errors = result.errors
