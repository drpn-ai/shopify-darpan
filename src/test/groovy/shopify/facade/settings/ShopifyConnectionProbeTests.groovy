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
                shopApiUrl : "https://example.myshopify.com/admin/api",
                apiVersion : "2026-04",
                accessToken: "shpat_secret_token",
                // Per-endpoint state, not the retired canReadOrders boolean. SHOPIFY enabled by
                // default matches every existing scenario below; tests that care about a different
                // shape (nothing enabled, or SHOPIFY_RETURN_REFS also enabled) override this key.
                endpoints  : [[systemEnumId: "SHOPIFY", endpointLabel: "Orders", isEnabled: true]],
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

    private static boolean isBulkStart(String query) {
        return query.contains("bulkOperationRunQuery")
    }

    private static Map<String, Object> bulkStarted() {
        return ok([bulkOperationRunQuery: [bulkOperation: [id: "gid://shopify/BulkOperation/1", status: "CREATED"],
                                           userErrors   : []]])
    }

    /** Shop, one order, and a bulk start that Shopify accepts. */
    private static Map<String, Object> healthy(String query) {
        if (isBulkStart(query)) return bulkStarted()
        return query.contains("orders(")
                ? ok([orders: [edges: [[node: [id: "gid://shopify/Order/1"]]]]])
                : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
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
            return healthy(query)
        })

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "apiVersion", "PASS")
        assertStatus(result, "ordersRead", "PASS")
        assertStatus(result, "bulkRead", "PASS")
        assertEquals("example.myshopify.com", checkFor(result, "reachable").detail)

        // Three separate calls; the orders probe asks for exactly one order, the bulk start for none.
        assertEquals(3, queries.size())
        assertTrue(queries[1].contains("orders(first: 1)"), queries[1])
        assertTrue(isBulkStart(queries[2]), queries[2])
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
        assertStatus(result, "bulkRead", "SKIP")
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
    void configWithNoEndpointsEnabledSkipsRatherThanFails() {
        // Endpoint-level replacement for the retired "canReadOrders: N" case: no catalog endpoint on
        // this config is enabled, so the connect stage must skip rather than run the orders query.
        List<String> queries = []
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(
                config([endpoints: [[systemEnumId: "SHOPIFY", endpointLabel: "Orders", isEnabled: false]]]),
                byQuery { String query ->
                    queries.add(query)
                    return ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
                })

        assertStatus(result, "ordersRead", "SKIP")
        assertEquals(1, queries.size(), "a config with no endpoints enabled must not issue the orders query")
    }

    @Test
    void probeReportsPerEndpointStateAlongsideChecks() {
        // The core contract change: callers read enablement from `endpoints`, not a canReadOrders flag.
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config([endpoints: [
                [systemEnumId: "SHOPIFY", endpointLabel: "Orders", isEnabled: true],
                [systemEnumId: "SHOPIFY_RETURN_REFS", endpointLabel: "Return references", isEnabled: false],
        ]]), byQuery { String query ->
            return query.contains("orders(")
                    ? ok([orders: [edges: [[node: [id: "gid://shopify/Order/1"]]]]])
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        })

        List<Map<String, Object>> endpoints = result.endpoints as List<Map<String, Object>>
        assertNotNull(endpoints, "The probe must report per-endpoint state, not one canReadOrders boolean")
        assertTrue(endpoints.find { it.systemEnumId == "SHOPIFY" }.isEnabled as boolean)
        assertFalse(endpoints.find { it.systemEnumId == "SHOPIFY_RETURN_REFS" }.isEnabled as boolean)
    }

    @Test
    void missingScopeNamesReadReturnsWhenReturnRefsIsAlsoEnabled() {
        // The risk this task closes: the migration can enable SHOPIFY_RETURN_REFS on a token that
        // never carried read_returns. The probe can only exercise the orders scope directly, but once
        // it knows return-refs is also enabled it must name that as a likely second cause rather than
        // reporting only the scope this one query happened to exercise.
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(
                config([endpoints: [
                        [systemEnumId: "SHOPIFY", endpointLabel: "Orders", isEnabled: true],
                        [systemEnumId: "SHOPIFY_RETURN_REFS", endpointLabel: "Return references", isEnabled: true],
                ]]),
                byQuery { String query ->
                    return query.contains("orders(")
                            ? graphqlError("Access denied for orders field. Required access: `read_orders` access scope.",
                                    "ACCESS_DENIED")
                            : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
                })

        assertStatus(result, "ordersRead", "FAIL")
        String detail = checkFor(result, "ordersRead").detail as String
        assertTrue(detail.contains("read_orders"), detail)
        assertTrue(detail.contains("read_returns"), detail)
        assertTrue(detail.contains("SHOPIFY_RETURN_REFS"), detail)
    }

    @Test
    void missingScopeStaysOrdersOnlyWhenReturnRefsIsNotEnabled() {
        // Same ACCESS_DENIED, but SHOPIFY_RETURN_REFS is not enabled on this config — the detail must
        // not speculate about a scope this config was never going to need.
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            return query.contains("orders(")
                    ? graphqlError("Access denied for orders field. Required access: `read_orders` access scope.",
                            "ACCESS_DENIED")
                    : ok([shop: [name: "Example", myshopifyDomain: "example.myshopify.com"]])
        })

        String detail = checkFor(result, "ordersRead").detail as String
        assertTrue(detail.contains("read_orders"), detail)
        assertFalse(detail.contains("read_returns"), detail)
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
                ShopifyConnectionProbe.probeAuthConfig(config([accessToken: secret]), byQuery { String query ->
                    isBulkStart(query)
                            ? graphqlError("Access denied for bulkOperationRunQuery field. Token ${secret}.".toString())
                            : healthy(query)
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
            return healthy(query)
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
        assertEquals("bulk", three.nextStage)
        assertEquals(2, queries.size())

        Map<String, Object> four = ShopifyConnectionProbe.probeStage(config(), options, (String) three.nextStage)
        assertEquals(["bulkRead"], ((List<Map>) four.checks)*.key)
        assertNull(four.nextStage, "the last stage must end the walk")
        assertEquals(3, queries.size())
    }

    @Test
    void aTerminalFailureEndsTheWalkImmediately() {
        // An unreadable credential makes every later stage meaningless, so stage one returns the
        // skip rows itself and reports no next stage — the UI never calls again.
        Map<String, Object> result = ShopifyConnectionProbe.probeStage(
                config([accessToken: null, credentialError: "The stored access token could not be decrypted."]),
                [:], darpan.facade.settings.SourceConnectionDiagnosticsSupport.STAGE_FIRST)

        assertNull(result.nextStage)
        assertEquals(["credential", "reachable", "apiVersion", "ordersRead", "bulkRead"], ((List<Map>) result.checks)*.key)
        assertStatus(result, "credential", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void walkingTheStagesMatchesRunningThemAllAtOnce() {
        // The staged path and the one-shot path must not drift apart.
        Closure<Map<String, Object>> responder = { String query -> healthy(query) }

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

    // DAR-BE-243: New Era's Partner-app token read one order fine and every run still failed, because
    // Shopify refused it bulk reads. The check now starts one empty bulk read after the orders row.

    @Test
    void aTokenRefusedBulkReadsFailsThatRowAndNamesTheSwitch() {
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            isBulkStart(query)
                    ? graphqlError("Access denied for bulkOperationRunQuery field. Required access: Only accessible by supported access tokens.",
                            "ACCESS_DENIED")
                    : healthy(query)
        })

        assertStatus(result, "ordersRead", "PASS")
        assertStatus(result, "bulkRead", "FAIL")
        String detail = checkFor(result, "bulkRead").detail as String
        assertTrue(detail.contains("Allow bulk queries"), detail)
        assertFalse(detail.contains("read_orders"), "orders read fine, so this is not a scope problem: ${detail}")
    }

    @Test
    void aBulkReadAlreadyRunningStillProvesAccess() {
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            isBulkStart(query)
                    ? ok([bulkOperationRunQuery: [bulkOperation: null, userErrors: [
                            [field: null, message: "A bulk query operation for this app and shop is already in progress: gid://shopify/BulkOperation/9."]]]])
                    : healthy(query)
        })

        assertStatus(result, "bulkRead", "PASS")
    }

    @Test
    void aBulkStartWithAnotherUserErrorFailsWithoutEchoingIt() {
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            isBulkStart(query)
                    ? ok([bulkOperationRunQuery: [bulkOperation: null, userErrors: [[field: ["query"], message: "Invalid bulk query: upstream text"]]]])
                    : healthy(query)
        })

        assertStatus(result, "bulkRead", "FAIL")
        assertFalse((checkFor(result, "bulkRead").detail as String).contains("upstream text"))
    }

    @Test
    void theBulkStartAsksForNothing() {
        // The export Shopify starts must be empty: a filter no order can match, sent as a variable.
        List<Map> bodies = []
        ShopifyConnectionProbe.probeAuthConfig(config(), [httpExecutor: { Map request ->
            Map body = (Map) request.body
            bodies.add(body)
            return healthy(body.query as String)
        }])

        Map bulkBody = bodies.find { isBulkStart(it.query as String) }
        assertNotNull(bulkBody)
        assertEquals(ShopifyConnectionProbe.BULK_PROBE_QUERY, ((Map) bulkBody.variables).query)
        assertTrue((((Map) bulkBody.variables).query as String).contains("created_at:<1970-01-02"))
    }

    @Test
    void aFailedOrdersReadSkipsTheBulkStart() {
        List<String> queries = []
        Map<String, Object> result = ShopifyConnectionProbe.probeAuthConfig(config(), byQuery { String query ->
            queries.add(query)
            return query.contains("orders(")
                    ? graphqlError("Access denied for orders field. Required access: `read_orders` access scope.", "ACCESS_DENIED")
                    : healthy(query)
        })

        assertStatus(result, "ordersRead", "FAIL")
        assertStatus(result, "bulkRead", "SKIP")
        assertFalse(queries.any { isBulkStart(it) }, "no bulk start after the orders read failed")
    }
}
