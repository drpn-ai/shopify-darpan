import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

String configId = FacadeSupport.normalize(shopifyAuthConfigId)
String queryValue = FacadeSupport.normalize(queryDocument)
Map variablesValue = variables instanceof Map ? (Map) variables : [:]

if (!configId) ec.message.addError("Shopify auth config ID is required.")
if (!queryValue) ec.message.addError("Shopify GraphQL query document is required.")

def authConfig = null
if (!ec.message.hasError()) {
    authConfig = ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
        .condition("shopifyAuthConfigId", configId)
        .useCache(false)
        .one()
    TenantAccessSupport.requireTenantRecordAccess(
        ec,
        authConfig,
        "Shopify auth config '${configId}' was not found.",
        "Shopify auth config '${configId}' is not available in your active tenant."
    )
    if (authConfig && (authConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
        ec.message.addError("Shopify auth config ${configId} is inactive.")
    }
}

if (!ec.message.hasError()) {
    Map<String, Object> authConfigMap = [
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
    ]
    graphqlResult = ShopifyGraphqlTransport.execute(authConfigMap, queryValue, variablesValue, [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
    ])
    if (!graphqlResult.ok) {
        (graphqlResult.errors ?: []).each { String error -> ec.message.addError(error) }
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
