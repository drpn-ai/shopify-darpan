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

    private static Map<String, Object> authConfig() {
        return [
            shopApiUrl : "https://example.myshopify.com/admin/api",
            apiVersion : "2026-04",
            accessToken: "shpat_secret_token",
        ]
    }
}
