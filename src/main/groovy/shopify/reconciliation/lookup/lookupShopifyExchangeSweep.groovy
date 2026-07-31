import darpan.common.ValueSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.reconciliation.lookup.ShopifyExchangeSweepSupport

String configIdValue = ValueSupport.normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = ValueSupport.normalize(companyUserGroupId)

ok = false
exchanges = []
truncated = false
errors = []

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

Map<String, Object> result = ShopifyExchangeSweepSupport.sweepExchanges([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], ((Number) windowStartMillis).longValue(), ((Number) windowEndMillis).longValue(), [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
])

ok = result.ok
exchanges = result.exchanges
truncated = result.truncated
errors = result.errors
