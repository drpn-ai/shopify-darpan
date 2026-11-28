package shopify.facade.graphql

import darpan.agent.AgentQuerySupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

/**
 * DAR-CLI-003. A tenant user's agent sends one fresh, read-only GraphQL query through a Shopify connection the tenant
 * already stores (Darpan holds the access token), and reads a trimmed copy of the Shopify Admin schema to work out what
 * to ask. Gated as ShopifyGraphqlFacadeSupport.executeGraphql gates (usable config, SHOPIFY endpoint enabled); the
 * document is checked, sent once with connect/read timeouts below the 60 s wall clock, logged and stripped of the
 * connection's secrets by {@link AgentQuerySupport#run}.
 */
class ShopifyAgentQuerySupport {

    static final String SYSTEM_ENUM_ID = "SHOPIFY"
    static final int CONNECT_TIMEOUT_MILLIS = 15000
    static final int READ_TIMEOUT_MILLIS = 45000

    /** Test seam, passed to the transport's own httpExecutor option: request map -> [statusCode, body] (null = real HTTP). */
    static Closure httpExecutorOverride = null

    /**
     * The AgentQuerySupport.graphqlQuery answer, or null with an error on ec when the caller may not read through this config.
     * S99-073: the query, schema, cap and unwrap flow is darpan's (AgentQuerySupport); the Shopify gate, transport and secrets are here.
     */
    static Map runAgentQuery(def ec, Map context) {
        String configId = context.get("shopifyAuthConfigId")?.toString()?.trim()
        String document = context.get("document")?.toString()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: document ?: ""]
        if (!TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read Shopify.")) {
            return null
        }
        Map variables = context.get("variables") instanceof Map ? new LinkedHashMap((Map) context.get("variables")) : null
        return AgentQuerySupport.graphqlQuery(ec, meta, document, variables, { Map m -> gateConfig(ec, configId, m) })
    }

    /** The trimmed schema for the config (AgentQuerySupport.graphqlSchema). Null with an error on ec when refused. */
    static Map getSchema(def ec, Map context) {
        String configId = context.get("shopifyAuthConfigId")?.toString()?.trim()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: "introspection (schema)"]
        return AgentQuerySupport.graphqlSchema(ec, meta, "SHOPIFY", { Map m -> gateConfig(ec, configId, m) })
    }

    // ---- shared plumbing ------------------------------------------------------------------------------------------

    /**
     * [send, stamp], [refused: the logged REFUSED answer], or null with an error on ec. meta.secrets is set
     * before anything that can fail with a message.
     */
    private static Map gateConfig(def ec, String configId, Map meta) {
        def config = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId, [requiredEndpointSystemEnumId: SYSTEM_ENUM_ID])
        if (config == null || ec.message.hasError()) return null
        // One current token per agent call (refreshed first if due), so secretsOf can redact exactly it.
        Map transportConfig = [shopApiUrl: config.shopApiUrl, apiVersion: config.apiVersion,
                               accessToken: shopify.facade.settings.ShopifyTokenSupport.accessTokenFor(ec, config)]
        meta.secrets = secretsOf(transportConfig)
        // Final review R25: fresh queries and schema reads run only on the active tenant's own connection.
        String notOwned = AgentQuerySupport.requireOwnedConnection(ec, config.companyUserGroupId)
        if (notOwned) return [refused: AgentQuerySupport.refuse(ec, meta, notOwned)]
        return [send: { String document, Map variables -> singleAnswer(transportConfig, document, variables) },
                stamp: config.lastUpdatedStamp?.toString() ?: ""]
    }

    /** One call, no retry (maxAttempts 1) so a retry cannot pass the 60 s wall clock. Capped by AgentQuerySupport. */
    private static Map singleAnswer(Map transportConfig, String document, Map variables) {
        Map options = [connectTimeoutMillis: CONNECT_TIMEOUT_MILLIS, readTimeoutMillis: READ_TIMEOUT_MILLIS, maxAttempts: 1]
        if (httpExecutorOverride != null) options.httpExecutor = httpExecutorOverride
        Map result = ShopifyGraphqlTransport.execute(transportConfig, document, variables ?: [:], options)
        if (result.ok == false) throw new IllegalStateException(((result.errors ?: ["Shopify did not answer."]) as List).join(" "))
        return result.data instanceof Map ? (Map) result.data : [:]
    }

    /** Everything about the connection that must never reach the agent or the log. */
    private static List<String> secretsOf(Map config) {
        List<String> out = []
        String token = config.accessToken?.toString()?.trim()
        if (token) out.add(token)
        String url = config.shopApiUrl?.toString()?.trim()
        if (url) {
            out.add(url)
            out.add(url.replaceAll('/+$', ''))
            try {
                URI uri = URI.create(url)
                if (uri.host) out.add(uri.host)
                if (uri.host?.endsWith(".myshopify.com")) out.add(uri.host.substring(0, uri.host.length() - ".myshopify.com".length()))
                if (uri.userInfo) out.add(uri.userInfo)
            } catch (Exception ignored) { }
        }
        return out.findAll { it }.unique()
    }
}
