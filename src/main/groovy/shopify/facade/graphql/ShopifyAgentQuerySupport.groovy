package shopify.facade.graphql

import darpan.agent.AgentQuerySupport
import darpan.agent.GraphqlSchemaTrim
import darpan.datasource.GraphQlDocumentCheck
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

import java.nio.charset.StandardCharsets

/**
 * DAR-CLI-003. A tenant user's agent sends one fresh, read-only GraphQL query through a Shopify connection the tenant
 * already stores (Darpan holds the access token), and reads a trimmed copy of the Shopify Admin schema to work out what
 * to ask. Gated as ShopifyGraphqlFacadeSupport.executeGraphql gates (usable config, SHOPIFY endpoint enabled); the
 * document is checked, sent once with connect/read timeouts below the 60 s wall clock, logged and stripped of the
 * connection's secrets by {@link AgentQuerySupport#run}.
 */
class ShopifyAgentQuerySupport {

    static final String SYSTEM_ENUM_ID = "SHOPIFY"
    static final String CACHE_NAME = "darpan.agent.schema"
    static final long SCHEMA_TTL_MILLIS = 24L * 60 * 60 * 1000
    static final int CONNECT_TIMEOUT_MILLIS = 15000
    static final int READ_TIMEOUT_MILLIS = 45000

    /** Test seam, passed to the transport's own httpExecutor option: request map -> [statusCode, body] (null = real HTTP). */
    static Closure httpExecutorOverride = null

    /** The AgentQuerySupport.run answer, or null with an error on ec when the caller may not read through this config. */
    static Map runAgentQuery(def ec, Map context) {
        String configId = context.get("shopifyAuthConfigId")?.toString()?.trim()
        String document = context.get("document")?.toString()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: document ?: ""]
        if (!TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read Shopify.")) {
            return null
        }
        Map variables = context.get("variables") instanceof Map ? new LinkedHashMap((Map) context.get("variables")) : null
        Map gate = gateConfig(ec, configId, meta)
        if (gate == null) return null
        // The document check is last so a refusal is logged with the secrets already set on meta.
        String problem = AgentQuerySupport.checkGraphqlQuery(document)
        if (problem) return AgentQuerySupport.refuse(ec, meta, problem)

        final Map transportConfig = (Map) gate.transportConfig
        final String doc = document
        Map answer = AgentQuerySupport.run(ec, meta, {
            Map data = singleAnswer(transportConfig, doc, variables)
            return [rows: [data], hasMore: false, offset: 0] as Map
        } as Closure<Map>)
        return unwrap(answer)
    }

    /** The trimmed schema for the config: from the 24 h cache, else one introspection. Null with an error on ec when refused. */
    static Map getSchema(def ec, Map context) {
        String configId = context.get("shopifyAuthConfigId")?.toString()?.trim()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: "introspection (schema)"]
        Map gate = gateConfig(ec, configId, meta)
        if (gate == null) return null

        // The gate ran above, so a cache hit is never served to a caller who failed it. The config's last-updated stamp
        // is in the key: an edited connection (new shop, new version) does not serve the old schema.
        def cache = ec.cache.getCache(CACHE_NAME)
        String key = "SHOPIFY:${configId}:${gate.stamp}".toString()
        Map cached = (Map) cache.get(key)
        if (cached != null && System.currentTimeMillis() - (cached.at as long) < SCHEMA_TTL_MILLIS) {
            return unwrap([rows: [copyOf((Map) cached.schema)], rowCount: 1, hasMore: false, truncated: false, durationMs: 0L])
        }
        // Introspection is allowed only here: the document check tells the only caller that wants it.
        List errors = (List) GraphQlDocumentCheck.check(GraphqlSchemaTrim.INTROSPECTION_QUERY, true).errors
        if (errors) return AgentQuerySupport.refuse(ec, meta, errors.join(" "))

        final Map transportConfig = (Map) gate.transportConfig
        Map answer = AgentQuerySupport.run(ec, meta, {
            Map data = singleAnswer(transportConfig, GraphqlSchemaTrim.INTROSPECTION_QUERY, null)
            return [rows: [GraphqlSchemaTrim.trim(data)], hasMore: false, offset: 0] as Map
        } as Closure<Map>)
        Map out = unwrap(answer)
        if (out.rows) cache.put(key, [at: System.currentTimeMillis(), schema: copyOf((Map) ((List) out.rows)[0])])
        return out
    }

    // ---- shared plumbing ------------------------------------------------------------------------------------------

    /** [transportConfig, stamp] or null with an error on ec. meta.secrets is set before anything that can fail with a message. */
    private static Map gateConfig(def ec, String configId, Map meta) {
        def config = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId, [requiredEndpointSystemEnumId: SYSTEM_ENUM_ID])
        if (config == null || ec.message.hasError()) return null
        Map transportConfig = [shopApiUrl: config.shopApiUrl, apiVersion: config.apiVersion, accessToken: config.accessToken]
        meta.secrets = secretsOf(transportConfig)
        return [transportConfig: transportConfig, stamp: config.lastUpdatedStamp?.toString() ?: ""]
    }

    /** One call, no retry (maxAttempts 1) so a retry cannot pass the 60 s wall clock. */
    private static Map singleAnswer(Map transportConfig, String document, Map variables) {
        Map options = [connectTimeoutMillis: CONNECT_TIMEOUT_MILLIS, readTimeoutMillis: READ_TIMEOUT_MILLIS, maxAttempts: 1]
        if (httpExecutorOverride != null) options.httpExecutor = httpExecutorOverride
        Map result = ShopifyGraphqlTransport.execute(transportConfig, document, variables ?: [:], options)
        if (result.ok == false) throw new IllegalStateException(((result.errors ?: ["Shopify did not answer."]) as List).join(" "))
        Map data = result.data instanceof Map ? (Map) result.data : [:]
        // A single 5 MB body cap; a JSON document cannot be cut partway, so an over-size answer is refused with advice.
        if (JsonOutput.toJson(data).getBytes(StandardCharsets.UTF_8).length > AgentQuerySupport.MAX_BODY_BYTES - 64) {
            throw new AgentQuerySupport.AgentQueryRefused("The answer is larger than 5 MB; ask for fewer fields or a smaller 'first'.")
        }
        return data
    }

    /** rows:[payload] becomes data/schema; rowCount is the edge count of the one top-level connection, else null. */
    private static Map unwrap(Map answer) {
        if (answer.error) return answer
        Map payload = ((List) answer.rows)[0] as Map
        Map out = new LinkedHashMap(answer)
        out.schema = payload
        out.data = payload
        out.rowCount = edgeCount(payload)
        return out
    }

    static Integer edgeCount(Map data) {
        if (data == null || data.size() != 1) return null
        Object only = data.values().first()
        if (only instanceof Map && ((Map) only).edges instanceof List) return ((List) ((Map) only).edges).size()
        return null
    }

    private static Map copyOf(Map m) { (Map) new JsonSlurper().parseText(JsonOutput.toJson(m)) }

    /** Everything about the connection that must never reach the agent or the log. */
    static List<String> secretsOf(Map config) {
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
