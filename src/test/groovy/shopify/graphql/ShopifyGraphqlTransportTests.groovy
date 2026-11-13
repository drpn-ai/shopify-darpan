package shopify.graphql

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ShopifyGraphqlTransportTests {
    @Test
    void transportBuildsAdminEndpointAndReturnsCostMetadata() {
        Map<String, Object> capturedRequest = [:]
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { shop { name } }", [:], [
            httpExecutor: { Map<String, Object> request ->
                capturedRequest = request
                return [
                    statusCode: 200,
                    body      : JsonOutput.toJson([
                        data      : [shop: [name: "Test Shop"]],
                        extensions: [
                            cost: [
                                requestedQueryCost: 12,
                                actualQueryCost   : 4,
                                throttleStatus    : [
                                    maximumAvailable  : 1000,
                                    currentlyAvailable: 996,
                                    restoreRate       : 50,
                                ],
                            ],
                        ],
                    ]),
                ]
            },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals("https://example.myshopify.com/admin/api/2026-04/graphql.json", capturedRequest.url)
        assertEquals("shpat_secret_token", ((Map<String, String>) capturedRequest.headers)["X-Shopify-Access-Token"])
        assertEquals("Test Shop", result.data.shop.name)
        assertEquals(12, result.cost.requestedQueryCost)
        assertFalse(result.toString().contains("shpat_secret_token"))
    }

    @Test
    void transportRetriesRetryableHttpFailuresAndReturnsSuccessfulResponse() {
        int callCount = 0
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { shop { name } }", [:], [
            maxAttempts: 2,
            httpExecutor: { Map<String, Object> request ->
                callCount++
                if (callCount == 1) return [statusCode: 500, body: "temporary failure"]
                return [
                    statusCode: 200,
                    body      : JsonOutput.toJson([data: [shop: [name: "Recovered Shop"]]]),
                ]
            },
        ])

        assertEquals(2, callCount)
        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals("Recovered Shop", result.data.shop.name)
    }

    @Test
    void transportReturnsSafeGraphqlErrorsWithoutLeakingToken() {
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { orders(first: 1) { edges { node { id } } } }", [:], [
            httpExecutor: { Map<String, Object> request ->
                return [
                    statusCode: 200,
                    body      : JsonOutput.toJson([
                        errors    : [[message: "Access denied for orders field."]],
                        extensions: [cost: [requestedQueryCost: 2, actualQueryCost: 2]],
                    ]),
                ]
            },
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List<String>) result.errors).first().contains("Shopify GraphQL error: Access denied for orders field."))
        assertEquals(2, result.cost.requestedQueryCost)
        assertFalse(result.toString().contains("shpat_secret_token"))
    }

    @Test
    void transportReturnsSafeErrorsForInvalidJsonResponses() {
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { shop { name } }", [:], [
            httpExecutor: { Map<String, Object> request -> [statusCode: 200, body: "not-json"] },
        ])

        assertFalse((Boolean) result.ok)
        assertEquals(["Shopify GraphQL response was not valid JSON."], result.errors)
    }

    @Test
    void transportNeverRetriesMutationDocuments() {
        int callCount = 0
        // The bulk run is the one mutation Darpan sends (DAR-BE-260 refuses any other before it is sent).
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(),
                'mutation DarpanBulkRun($query: String!) { bulkOperationRunQuery(query: $query) { bulkOperation { id } userErrors { message } } }',
                [query: '{ orders { edges { node { id } } } }'], [
            maxAttempts : 2,
            httpExecutor: { Map<String, Object> request ->
                callCount++
                return [statusCode: 500, body: "temporary failure"]
            },
        ])

        assertEquals(1, callCount)
        assertFalse((Boolean) result.ok)
        assertEquals(false, result.retryable)
    }

    @Test
    void transportRetriesThrottledGraphqlErrorsWithExplicitDelay() {
        int callCount = 0
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { shop { name } }", [:], [
            maxAttempts     : 2,
            retryDelayMillis: 1,
            httpExecutor    : { Map<String, Object> request ->
                callCount++
                if (callCount == 1) {
                    return [
                        statusCode: 200,
                        body      : JsonOutput.toJson([
                            errors    : [[message: "Throttled", extensions: [code: "THROTTLED"]]],
                            extensions: [cost: [requestedQueryCost: 100, throttleStatus: [currentlyAvailable: 0, restoreRate: 50]]],
                        ]),
                    ]
                }
                return [statusCode: 200, body: JsonOutput.toJson([data: [shop: [name: "Recovered Shop"]]])]
            },
        ])

        assertEquals(2, callCount)
        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals("Recovered Shop", result.data.shop.name)
    }

    @Test
    void transportDoesNotRetryOutboundPolicyBlockedRequests() {
        int callCount = 0
        Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig(), "query ShopifyOrders { shop { name } }", [:], [
            maxAttempts : 3,
            httpExecutor: { Map<String, Object> request ->
                callCount++
                throw new ShopifyGraphqlTransport.OutboundPolicyBlockedException("Shopify endpoint URL blocked by outbound policy: URL must use https.")
            },
        ])

        assertEquals(1, callCount)
        assertFalse((Boolean) result.ok)
        assertEquals(false, result.retryable)
        assertTrue(((List<String>) result.errors).first().contains("blocked by outbound policy"))
    }

    @Test
    void throttleDelayIsDerivedFromThrottleStatusCostMetadata() {
        long delay = ShopifyGraphqlTransport.throttleDelayMillis([
            cost: [requestedQueryCost: 150, throttleStatus: [currentlyAvailable: 50, restoreRate: 50]],
        ] as Map<String, Object>)

        assertEquals(2000L, delay)
        assertEquals(ShopifyGraphqlTransport.DEFAULT_THROTTLE_DELAY_MILLIS, ShopifyGraphqlTransport.throttleDelayMillis([:] as Map<String, Object>))
    }

    private static Map<String, Object> authConfig() {
        return [
            shopApiUrl : "https://example.myshopify.com/admin/api",
            apiVersion : "2026-04",
            accessToken: "shpat_secret_token",
        ]
    }

    /** DAR-BE-262 follow-up: a loopback listener counting connections, and a guarded client whose lookup answers loopback. */
    private static Map loopbackListener() {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getByAddress([127, 0, 0, 1] as byte[]))
        server.setSoTimeout(200)
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false)
        Thread acceptor = Thread.start {
            while (!stop.get()) {
                try { Socket sock = server.accept(); accepted.incrementAndGet(); sock.close() } catch (SocketTimeoutException ignored) { } catch (IOException ignored) { break }
            }
        }
        def lookup = { String host -> [InetAddress.getByAddress(host, [127, 0, 0, 1] as byte[])] as InetAddress[] } as java.util.function.Function<String, InetAddress[]>
        org.eclipse.jetty.client.HttpClient guarded = darpan.facade.common.OutboundHttpClient.newClient(
                new darpan.facade.common.OutboundHttpClient.VettedAddressResolver(lookup))
        return [server: server, accepted: accepted, stop: stop, acceptor: acceptor, guarded: guarded]
    }

    private static void closeListener(Map l) {
        (l.stop as java.util.concurrent.atomic.AtomicBoolean).set(true)
        (l.guarded as org.eclipse.jetty.client.HttpClient).stop()
        (l.server as ServerSocket).close()
        (l.acceptor as Thread).join(2000)
    }

    @Test
    void "DAR-BE-262: a shop host that resolves to an internal address at the connect gets no socket and the attempt fails retryably"() {
        Map l = loopbackListener()
        try {
            int port = (l.server as ServerSocket).localPort
            Map result = ShopifyGraphqlTransport.execute([shopApiUrl: "https://rebind-test.myshopify.com:${port}".toString(), apiVersion: "2025-01",
                    accessToken: "shpat_test"], "query { shop { name } }", [:],
                    [maxAttempts: 2, retryDelayMillis: 1, httpExecutor: { Map<String, Object> request ->
                        ShopifyGraphqlTransport.executeHttpRequest(request, (org.eclipse.jetty.client.HttpClient) l.guarded) }])
            assertFalse(result.ok as boolean)
            assertEquals(["Shopify GraphQL request failed before a valid response was received."], result.errors)
            Thread.sleep(300)
            assertEquals(0, (l.accepted as java.util.concurrent.atomic.AtomicInteger).get(), "no connection reached the loopback listener")
        } finally {
            closeListener(l)
        }
    }

    @Test
    void "DAR-BE-262: a host off the myshopify allow-list is still refused before any request, and not retried"() {
        Map result = ShopifyGraphqlTransport.execute([shopApiUrl: "https://evil.example.com", apiVersion: "2025-01", accessToken: "shpat_test"],
                "query { shop { name } }", [:], [maxAttempts: 3])
        assertFalse(result.ok as boolean)
        assertFalse(result.retryable as boolean)
        assertTrue((result.errors as List)[0].toString().contains("blocked by outbound policy"), result.errors.toString())
    }
}
