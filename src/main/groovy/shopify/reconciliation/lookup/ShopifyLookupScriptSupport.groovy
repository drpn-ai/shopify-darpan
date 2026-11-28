package shopify.reconciliation.lookup

import darpan.common.ValueSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

/**
 * The config step the lookup script services share (lookupShopifyOrderIds,
 * lookupShopifyRefundOrReturnIds, lookupShopifyExchangeSweep).
 *
 * <p>A lookup that cannot resolve its config returns with its empty defaults and ok=false - it never
 * classifies anything. The script keeps that early return itself, because a Moqui script service sets
 * its out-parameters as script bindings.</p>
 */
class ShopifyLookupScriptSupport {

    /**
     * Requires the config id and resolves the tenant's auth config for the SHOPIFY endpoint.
     *
     * @return [failed: true, errors] when the id is blank or ec.message has an error after
     *         resolution (errors are then the ec.message errors, possibly empty); otherwise
     *         [failed: false, connection] with the transport fields of the config
     */
    static Map<String, Object> resolveConnection(def ec, Object shopifyAuthConfigId, Object companyUserGroupId) {
        String configIdValue = ValueSupport.normalize(shopifyAuthConfigId)
        String companyUserGroupIdValue = ValueSupport.normalize(companyUserGroupId)
        if (!configIdValue) return [failed: true, errors: ["Shopify auth config ID is required."]]

        def authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
                companyUserGroupId          : companyUserGroupIdValue,
                requiredEndpointSystemEnumId: "SHOPIFY",
        ])
        if (ec.message.hasError()) return [failed: true, errors: (ec.message.getErrors() ?: []) as List<String>]
        return [failed: false, connection: ShopifyAuthConfigSupport.transportConfig(authConfig)]
    }

    /** The transport knobs every lookup service declares, read off its parameters (or a lookup's options). */
    static Map<String, Object> transportOptions(Map params) {
        return [
                connectTimeoutMillis: params?.connectTimeoutMillis,
                readTimeoutMillis   : params?.readTimeoutMillis,
                maxAttempts         : params?.maxAttempts,
        ]
    }

    /** The lookups' default executor: (config, query, variables, options) to the Shopify GraphQL transport. Tests pass their own. */
    static Closure graphqlExecutor(Closure override) {
        return override ?: { Map cfg, String queryDocument, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, queryDocument, variables, opts)
        }
    }
}
