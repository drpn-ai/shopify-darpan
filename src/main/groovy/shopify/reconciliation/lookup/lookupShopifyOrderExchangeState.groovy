import shopify.reconciliation.lookup.ShopifyExchangeStateLookupSupport
import shopify.reconciliation.lookup.ShopifyLookupScriptSupport

ok = false
statesByOrderId = [:]
errors = []
lineItemsShape = null

Map<String, Object> resolved = ShopifyLookupScriptSupport.resolveConnection(ec, shopifyAuthConfigId, companyUserGroupId)
if (resolved.failed) {
    errors = resolved.errors
    return
}

Map<String, Object> result = ShopifyExchangeStateLookupSupport.lookupExchangeState((Map) resolved.connection,
        (List) orderIds, ShopifyLookupScriptSupport.transportOptions(context))

ok = result.ok
statesByOrderId = result.statesByOrderId
errors = result.errors
lineItemsShape = result.lineItemsShape
