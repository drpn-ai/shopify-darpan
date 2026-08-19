import darpan.common.ValueSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.reconciliation.lookup.ShopifyRefundOrReturnLookupSupport

String configIdValue = ValueSupport.normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = ValueSupport.normalize(companyUserGroupId)

ok = false
foundIds = []
missingIds = []
errors = []

if (!configIdValue) {
    errors = ["Shopify auth config ID is required."]
    return
}

def authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
        disableAuthz                : true,
        companyUserGroupId          : companyUserGroupIdValue,
        requiredEndpointSystemEnumId: "SHOPIFY",
])
if (ec.message.hasError()) {
    errors = (ec.message.getErrors() ?: []) as List<String>
    return
}

Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], (List<Object>) refundOrReturnIds, [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
])

ok = result.ok
foundIds = result.foundIds
missingIds = result.missingIds
errors = result.errors
