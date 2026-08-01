package shopify.facade.settings

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Connection diagnostics for Shopify must diagnose each layer independently: a rejected token, a
 * dead API version and a missing read_orders scope are three different operator actions, so they
 * must never collapse into one another. No network — the transport's httpExecutor seam is injected.
 */
class ShopifyConnectionProbeTests {

    private static Map<String, Object> config(Map overrides = [:]) {
        return ([
                shopApiUrl   : "https://example.myshopify.com/admin/api",
                apiVersion   : "2026-04",
                accessToken  : "shpat_secret_token",
                canReadOrders: "Y",
        ] + overrides) as Map<String, Object>
    }

    private static Map<String, Object> ok(Map data) {
        return [statusCode: 200, body: JsonOutput.toJson([data: data])]
    }

    private static Map<String, Object> status(int statusCode) {
        return [statusCode: statusCode, body: ""]
    }

    private static Map<String, Object> graphqlError(String message, String code = null) {
        Map error = [message: message]
        if (code) error.put("extensions", [code: code])
        return [statusCode: 200, body: JsonOutput.toJson([errors: [error]])]
    }

    private static Map<String, Object> byQuery(Closure<Map<String, Object>> responder) {
        return [httpExecutor: { Map request -> responder.call(((Map) request.body).query as String) }]
    }

    private static Map<String, Object> checkFor(Map<String, Object> result, String key) {
        Map<String, Object> row = ((List<Map<String, Object>>) result.checks).find { it.key == key }
        assertNotNull(row, "expected a '${key}' check row, got ${((List) result.checks)*.key}")
        return row
    }

    private static void assertStatus(Map<String, Object> result, String key, String expected) {
        assertEquals(expected, checkFor(result, key).status,
                "check '${key}' detail=${checkFor(result, key).detail}")
    }

    @Test
    void healthyConnectionPassesEveryCheck() {
        List<String> queries = []
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            queries.add(query)
            return query.contains("orders(")
                    ? ok([orders: [edges: [[node: [id: "gid://shopify/Order/1"]]]]])
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        })

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "apiVersion", "PASS")
        assertStatus(result, "ordersRead", "PASS")
        assertEquals("example.myshopify.com", checkFor(result, "reachable").detail)

        // Two separate calls, and the orders probe asks for exactly one order.
        assertEquals(2, queries.size())
        assertTrue(queries[1].contains("orders(first: 1)"), queries[1])
    }

    @Test
    void undecryptableTokenFailsBeforeAnyNetworkCall() {
        boolean called = false
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(
                config([accessToken: null, credentialError: "The stored access token could not be decrypted."]),
                [httpExecutor: { Map request -> called = true; return ok([:]) }])

        assertFalse(called, "a credential that cannot be read must not produce an HTTP call")
        assertStatus(result, "credential", "FAIL")
        // Everything downstream is untested, not broken.
        assertStatus(result, "reachable", "SKIP")
        assertStatus(result, "apiVersion", "SKIP")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void missingTokenIsACredentialFailureNotAConnectivityOne() {
        boolean called = false
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config([accessToken: "  "]),
                [httpExecutor: { Map request -> called = true; return ok([:]) }])

        assertFalse(called)
        assertStatus(result, "credential", "FAIL")
    }

    @Test
    void rejectedTokenFailsReachabilityAndSkipsTheScopeCheck() {
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(),
                byQuery { String query -> status(401) })

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "FAIL")
        // Never attempted, so it must not read as a scope problem.
        assertStatus(result, "apiVersion", "SKIP")
        assertStatus(result, "ordersRead", "SKIP")
        assertTrue((checkFor(result, "reachable").detail as String).contains("401"))
    }

    @Test
    void unservedApiVersionKeepsReachabilityPassing() {
        // The version is baked into the endpoint path, so an unserved version 404s on a live host.
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(),
                byQuery { String query -> status(404) })

        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "apiVersion", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
        assertTrue((checkFor(result, "apiVersion").detail as String).contains("2026-04"))
    }

    @Test
    void missingOrdersScopeIsDistinctFromABadToken() {
        // The case a single combined query would blur: the token is fine, the scope is not.
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            return query.contains("orders(")
                    ? graphqlError("Access denied for orders field. Required access: `read_orders` access scope.",
                            "ACCESS_DENIED")
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        })

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "apiVersion", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("read_orders"))
    }

    @Test
    void configNotEnabledForOrdersSkipsRatherThanFails() {
        List<String> queries = []
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config([canReadOrders: "N"]),
                byQuery { String query ->
                    queries.add(query)
                    return ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
                })

        assertStatus(result, "ordersRead", "SKIP")
        assertEquals(1, queries.size(), "a config not enabled for orders must not issue the orders query")
    }

    @Test
    void unreachableHostReportsNoStatusCode() {
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(),
                [httpExecutor: { Map request -> throw new java.net.UnknownHostException("nope.myshopify.com") }])

        assertStatus(result, "reachable", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void noCheckDetailEverEchoesTheStoredToken() {
        String secret = "shpat_super_secret_value"
        List<Map<String, Object>> results = [
                ShopifyConnectionProbe.probeAuthConfig(config([accessToken: secret]), byQuery { status(401) }),
                ShopifyConnectionProbe.probeAuthConfig(config([accessToken: secret]), byQuery { status(500) }),
                ShopifyConnectionProbe.probeAuthConfig(config([accessToken: secret]), byQuery { String query ->
                    query.contains("orders(")
                            ? graphqlError("Access denied. Token ${secret} lacks scope.".toString())
                            : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
                }),
        ]

        results.each { Map<String, Object> result ->
            String rendered = ((List<Map<String, Object>>) result.checks)*.detail.join(" | ")
            assertFalse(rendered.contains(secret), "a check detail leaked the access token: ${rendered}")
        }
    }
    @Test
    void stagesRunOneAtATimeAndChainViaNextStage() {
        // Each stage returns only its own rows, so a caller can render them as the server finishes.
        List<String> queries = []
        Map<String, Object> options = byQuery { String query ->
            queries.add(query)
            return query.contains("orders(")
                    ? ok([orders: [edges: [[node: [id: "gid://shopify/Order/1"]]]]])
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        }

        Map<String, Object> one = ShopifyConnectionProbe.probeStage(config(), options,
                darpan.facade.settings.SourceConnectionDiagnosticsSupport.STAGE_FIRST)
        assertEquals(["credential"], ((List<Map>) one.checks)*.key)
        assertEquals("connect", one.nextStage)
        assertEquals(0, queries.size(), "the credential stage must not touch the network")

        Map<String, Object> two = ShopifyConnectionProbe.probeStage(config(), options, (String) one.nextStage)
        assertEquals(["reachable", "apiVersion"], ((List<Map>) two.checks)*.key)
        assertEquals("orders", two.nextStage)
        assertEquals(1, queries.size())

        Map<String, Object> three = ShopifyConnectionProbe.probeStage(config(), options, (String) two.nextStage)
        assertEquals(["ordersRead"], ((List<Map>) three.checks)*.key)
        assertNull(three.nextStage, "the last stage must end the walk")
        assertEquals(2, queries.size())
    }

    @Test
    void aTerminalFailureEndsTheWalkImmediately() {
        // An unreadable credential makes every later stage meaningless, so stage one returns the
        // skip rows itself and reports no next stage — the UI never calls again.
        Map<String, Object> result = ShopifyConnectionProbe.probeStage(
                config([accessToken: null, credentialError: "The stored access token could not be decrypted."]),
                [:], darpan.facade.settings.SourceConnectionDiagnosticsSupport.STAGE_FIRST)

        assertNull(result.nextStage)
        assertEquals(["credential", "reachable", "apiVersion", "ordersRead"], ((List<Map>) result.checks)*.key)
        assertStatus(result, "credential", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void walkingTheStagesMatchesRunningThemAllAtOnce() {
        // The staged path and the one-shot path must not drift apart.
        Closure<Map<String, Object>> responder = { String query ->
            query.contains("orders(")
                    ? ok([orders: [edges: [[node: [id: "gid://shopify/Order/1"]]]]])
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        }

        Map<String, Object> oneShot = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery(responder))

        List<Map> walked = []
        String stage = darpan.facade.settings.SourceConnectionDiagnosticsSupport.STAGE_FIRST
        while (stage) {
            Map<String, Object> step = ShopifyConnectionProbe.probeStage(config(), byQuery(responder), stage)
            walked.addAll((List<Map>) step.checks)
            stage = (String) step.nextStage
        }

        assertEquals(((List<Map>) oneShot.checks)*.key, walked*.key)
        assertEquals(((List<Map>) oneShot.checks)*.status, walked*.status)
    }
}
