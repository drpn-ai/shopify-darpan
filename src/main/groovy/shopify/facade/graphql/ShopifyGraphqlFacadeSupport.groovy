package shopify.facade.graphql

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifyGraphqlTransport

import static darpan.common.ValueSupport.normalize

class ShopifyGraphqlFacadeSupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyGraphqlFacadeSupport)

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
            logger.warn("Shopify GraphQL query build failed", e)
            ec.message.addError(e.message ?: "Shopify GraphQL query could not be built.")
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
            // The SHOPIFY endpoint is enforced here too: this service is the registered
            // remoteSendServiceName for the SHOPIFY connector row (bulk orders extraction) and must
            // match that path's contract. Regression note (Task 16): this call used to pass a bare
            // [:], which enforced nothing once requireUsableAuthConfig's canReadOrders default was
            // removed — see ShopifyEndpointGateTests.executeShopifyGraphqlRefusesWhenShopifyEndpointDisabled.
            authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                    [requiredEndpointSystemEnumId: "SHOPIFY"])
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
                    retryDelayMillis    : serviceContext?.retryDelayMillis,
            ])
            if (graphqlResult.ok == false) {
                (graphqlResult.errors ?: []).each { String error -> ec.message.addError(error) }
            }
            return graphqlResult
        }
        return null
    }
}
