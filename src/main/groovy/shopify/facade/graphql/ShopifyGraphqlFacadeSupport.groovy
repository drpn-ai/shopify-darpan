package shopify.facade.graphql

import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifyGraphqlTransport

import static darpan.common.ValueSupport.normalize

class ShopifyGraphqlFacadeSupport {
    static Map<String, Object> buildGraphqlQuery(def ec, Object serviceContext) {
        try {
            return ShopifyGraphqlQueryBuilder.buildQuery([
                    sourceDefinitionId : serviceContext?.sourceDefinitionId,
                    apiVersion         : serviceContext?.apiVersion,
                    selectedFieldPaths : serviceContext?.selectedFieldPaths,
                    filters            : serviceContext?.filters,
                    pageSize           : serviceContext?.pageSize,
                    afterCursor        : serviceContext?.afterCursor,
                    reverse            : serviceContext?.reverse,
                    connectionPageSizes: serviceContext?.connectionPageSizes,
            ])
        } catch (Exception e) {
            ec.message.addError(e.message)
        }
        return null
    }

    static Map<String, Object> executeGraphql(def ec, Object serviceContext) {
        String configId = normalize(serviceContext?.shopifyAuthConfigId)
        String queryValue = normalize(serviceContext?.queryDocument)
        Map variablesValue = serviceContext?.variables instanceof Map ? (Map) serviceContext.variables : [:]

        if (!configId) ec.message.addError("Shopify auth config ID is required.")
        if (!queryValue) ec.message.addError("Shopify GraphQL query document is required.")

        def authConfig = null
        if (!ec.message.hasError()) {
            authConfig = ShopifyAuthConfigSupport.findAuthConfig(ec, configId)
            ShopifyAuthConfigSupport.requireTenantAuthConfigAccess(ec, authConfig, configId)
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
            Map<String, Object> graphqlResult = ShopifyGraphqlTransport.execute(authConfigMap, queryValue, variablesValue, [
                    connectTimeoutMillis: serviceContext?.connectTimeoutMillis,
                    readTimeoutMillis   : serviceContext?.readTimeoutMillis,
                    maxAttempts         : serviceContext?.maxAttempts,
            ])
            if (graphqlResult.ok == false) {
                (graphqlResult.errors ?: []).each { String error -> ec.message.addError(error) }
            }
            return graphqlResult
        }
        return null
    }
}
