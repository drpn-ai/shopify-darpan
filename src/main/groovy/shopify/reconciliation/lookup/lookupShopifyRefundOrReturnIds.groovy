import shopify.reconciliation.lookup.ShopifyLookupScriptSupport
import shopify.reconciliation.lookup.ShopifyRefundOrReturnLookupSupport

ok = false
foundIds = []
missingIds = []
unresolvedIds = []
errors = []

Map<String, Object> resolved = ShopifyLookupScriptSupport.resolveConnection(ec, shopifyAuthConfigId, companyUserGroupId)
if (resolved.failed) {
    errors = resolved.errors
    return
}

Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds((Map) resolved.connection,
        (List<Object>) refundOrReturnIds, ShopifyLookupScriptSupport.transportOptions(context))

ok = result.ok
foundIds = result.foundIds
missingIds = result.missingIds
unresolvedIds = result.unresolvedIds ?: []
errors = result.errors
