import darpan.common.ValueSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.reconciliation.lookup.ShopifyExchangeStateLookupSupport

String configIdValue = ValueSupport.normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = ValueSupport.normalize(companyUserGroupId)

ok = false
statesByOrderId = [:]
errors = []
lineItemsShape = null

if (!configIdValue) {
    errors = ["Shopify auth config ID is required."]
    return
}

def authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
        disableAuthz      : true,
        companyUserGroupId: companyUserGroupIdValue,
])
if (ec.message.hasError()) {
    errors = (ec.message.getErrors() ?: []) as List<String>
    return
}

Map<String, Object> result = ShopifyExchangeStateLookupSupport.lookupExchangeState([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], (List) orderIds, [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
])

ok = result.ok
statesByOrderId = result.statesByOrderId
errors = result.errors
lineItemsShape = result.lineItemsShape
