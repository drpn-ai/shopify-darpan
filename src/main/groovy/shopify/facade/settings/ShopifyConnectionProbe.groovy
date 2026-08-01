package shopify.facade.settings

import darpan.facade.settings.SourceConnectionDiagnosticsSupport as Checks
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.graphql.ShopifyGraphqlTransport

import static darpan.common.ValueSupport.normalize

/**
 * Connection diagnostics for a saved Shopify auth config.
 *
 * Calls ShopifyGraphqlTransport directly rather than the execute#ShopifyGraphql service. The
 * security-relevant protections live in the transport (the .myshopify.com outbound allow-list and
 * endpoint construction), so they are inherited either way; the service additionally writes to
 * ec.message and enforces canReadOrders before any call, both of which would defeat a layered
 * diagnostic — a scope problem must be reportable as one row while the rest still pass.
 *
 * Nothing here writes to ec.message: a rejected credential is a diagnostic RESULT, not a service
 * error. Every outcome comes back as a check row.
 */
class ShopifyConnectionProbe {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyConnectionProbe.class)

    /** Cheapest authenticated read that proves shop + token + API version at once. */
    static final String SHOP_QUERY = "{ shop { name myshopifyDomain } }"
    /** One order only — honors the standing one-order-per-query rule for live Shopify probing. */
    static final String ORDERS_QUERY = "{ orders(first: 1) { edges { node { id } } } }"

    // An operator is watching a popup: fail fast, and never retry. A probe that silently retries
    // reports a flaky connection as healthy.
    static final int CONNECT_TIMEOUT_MILLIS = 5000
    static final int READ_TIMEOUT_MILLIS = 10000
    static final int MAX_ATTEMPTS = 1

    /**
     * Entity-facing entry point: resolve the stored config, then delegate. The accessToken read is
     * isolated here because that is where a bad entity_ds_crypt_pass throws — before any network
     * call — and reporting that as a connectivity problem is the misdiagnosis this whole ordering
     * exists to prevent.
     */
    static Map<String, Object> probeConnection(def ec, Object rawConfigId, Map options = [:]) {
        String configId = normalize(rawConfigId)

        def config = ShopifyAuthConfigSupport.findAuthConfig(ec, configId)
        if (config == null) {
            return [checks: [Checks.check("credential", "Configuration found", Checks.STATUS_FAIL,
                    "The configuration could not be loaded.")]]
        }

        String accessToken = null
        String credentialError = null
        try {
            accessToken = normalize(config.accessToken)
        } catch (Throwable t) {
            logger.warn("Shopify probe could not read the stored access token for ${configId}: ${t.class.simpleName}")
            credentialError = "The stored access token could not be decrypted."
        }

        return probeAuthConfig([
                shopApiUrl     : normalize(config.shopApiUrl),
                apiVersion     : normalize(config.apiVersion),
                accessToken    : accessToken,
                canReadOrders  : config.canReadOrders,
                credentialError: credentialError,
        ], options)
    }

    /**
     * Pure probe over a resolved config map — no entity access, so tests drive it with an injected
     * httpExecutor exactly like the lookup supports do.
     */
    static Map<String, Object> probeAuthConfig(Map<String, Object> config, Map options = [:]) {
        List<Map<String, Object>> checks = []

        String accessToken = normalize(config?.accessToken)
        String credentialFailure = normalize(config?.credentialError)
        if (!credentialFailure && !accessToken) {
            credentialFailure = "No access token is stored for this configuration."
        }

        if (credentialFailure) {
            checks.add(Checks.check("credential", "Credential readable", Checks.STATUS_FAIL, credentialFailure))
            checks.add(Checks.check("reachable", "Shop reachable", Checks.STATUS_SKIP, "Not attempted."))
            checks.add(Checks.check("apiVersion", "API version supported", Checks.STATUS_SKIP, "Not attempted."))
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_SKIP, "Not attempted."))
            return [checks: checks]
        }
        checks.add(Checks.check("credential", "Credential readable", Checks.STATUS_PASS))

        String shopApiUrl = normalize(config?.shopApiUrl)
        String apiVersion = normalize(config?.apiVersion)
        Map<String, Object> authConfig = [shopApiUrl: shopApiUrl, apiVersion: apiVersion, accessToken: accessToken]

        // 2 + 3. One call, two rows: "can we talk to the shop with this token" and "is this API
        //        version still served". The remedies differ, so the outcomes are reported apart.
        long shopStartedAt = System.currentTimeMillis()
        Map<String, Object> shopResult = execute(authConfig, SHOP_QUERY, options)
        long shopMillis = System.currentTimeMillis() - shopStartedAt
        int shopStatus = statusCodeOf(shopResult)

        if (shopResult.ok) {
            checks.add(Checks.check("reachable", "Shop reachable", Checks.STATUS_PASS,
                    normalize(shopResult.data?.shop?.myshopifyDomain) ?: shopApiUrl, shopMillis))
            checks.add(Checks.check("apiVersion", "API version supported", Checks.STATUS_PASS, apiVersion))
        } else if (shopStatus == 404) {
            // The version is baked into the endpoint path, so a served host with an unserved version
            // answers 404. The host clearly answered, hence reachable PASS.
            checks.add(Checks.check("reachable", "Shop reachable", Checks.STATUS_PASS, shopApiUrl, shopMillis))
            checks.add(Checks.check("apiVersion", "API version supported", Checks.STATUS_FAIL,
                    "The shop did not serve API version ${apiVersion}. Check the version and the shop URL.".toString()))
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_SKIP, "Not attempted."))
            return [checks: checks]
        } else {
            checks.add(Checks.check("reachable", "Shop reachable", Checks.STATUS_FAIL,
                    describeTransportFailure(shopStatus), shopMillis))
            checks.add(Checks.check("apiVersion", "API version supported", Checks.STATUS_SKIP, "Not attempted."))
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_SKIP, "Not attempted."))
            return [checks: checks]
        }

        // 4. The scope reconciliation actually needs. Kept as a separate call because Shopify reports
        //    a missing scope as a per-field ACCESS_DENIED alongside partial data — folded into the
        //    shop query it would be indistinguishable from a bad token.
        if (isDisabled(config?.canReadOrders)) {
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_SKIP,
                    "This configuration is not enabled for order reads."))
            return [checks: checks]
        }

        long ordersStartedAt = System.currentTimeMillis()
        Map<String, Object> ordersResult = execute(authConfig, ORDERS_QUERY, options)
        long ordersMillis = System.currentTimeMillis() - ordersStartedAt

        if (ordersResult.ok) {
            int returned = ((List) (ordersResult.data?.orders?.edges ?: [])).size()
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_PASS,
                    returned == 1 ? "1 order returned" : "no orders in range", ordersMillis))
        } else if (indicatesMissingScope(ordersResult)) {
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_FAIL,
                    "The token is missing the read_orders scope.", ordersMillis))
        } else {
            checks.add(Checks.check("ordersRead", "Orders readable", Checks.STATUS_FAIL,
                    describeTransportFailure(statusCodeOf(ordersResult)), ordersMillis))
        }

        return [checks: checks]
    }

    private static Map<String, Object> execute(Map<String, Object> authConfig, String query, Map options) {
        Map<String, Object> transportOptions = [
                connectTimeoutMillis: CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis   : READ_TIMEOUT_MILLIS,
                maxAttempts         : MAX_ATTEMPTS,
        ]
        // Tests inject their own executor; the real path falls through to the transport's own
        // client, which enforces the outbound host policy.
        if (options?.httpExecutor != null) transportOptions.put("httpExecutor", options.httpExecutor)
        try {
            return ShopifyGraphqlTransport.execute(authConfig, query, [:], transportOptions)
        } catch (Throwable t) {
            logger.warn("Shopify probe transport call failed: ${t.class.simpleName}")
            return [ok: false, statusCode: 0, errors: []] as Map<String, Object>
        }
    }

    private static int statusCodeOf(Map<String, Object> result) {
        Object raw = result?.get("statusCode")
        return raw instanceof Number ? ((Number) raw).intValue() : 0
    }

    /**
     * Fixed vocabulary keyed on status. Upstream error text is deliberately not echoed — a Shopify
     * error payload can carry request context that should not be rendered into a browser.
     */
    private static String describeTransportFailure(int statusCode) {
        if (statusCode == 401 || statusCode == 403) return "The access token was rejected (HTTP ${statusCode}).".toString()
        if (statusCode == 429) return "Shopify rate-limited the request (HTTP 429)."
        if (statusCode >= 500) return "Shopify returned a server error (HTTP ${statusCode}).".toString()
        if (statusCode > 0) return "The request failed with HTTP ${statusCode}.".toString()
        return "The shop could not be reached."
    }

    /** Shopify signals a missing scope as an ACCESS_DENIED GraphQL error on the requested field. */
    private static boolean indicatesMissingScope(Map<String, Object> result) {
        List errors = (List) (result?.get("graphqlErrors") ?: [])
        return errors.any { Object message ->
            String text = normalize(message)?.toLowerCase()
            return text != null && (text.contains("access denied") || text.contains("required access"))
        }
    }

    private static boolean isDisabled(Object indicator) {
        return normalize(indicator)?.equalsIgnoreCase("N")
    }
}
