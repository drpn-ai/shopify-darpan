package shopify.graphql

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ShopifyBulkOperationClientTests {
    @Test
    void bulkOperationQueryStartsPollsDownloadsAndParsesJsonl() {
        List<Map<String, Object>> graphqlRequests = []
        List<Map<String, Object>> downloadRequests = []

        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id legacyResourceId name } } } }", [
                pollIntervalMillis: 0,
                maxPollAttempts   : 2,
                httpExecutor      : { Map<String, Object> request ->
                    graphqlRequests.add(request)
                    Map body = (Map) request.body
                    String query = body.query as String
                    if (query.contains("bulkOperationRunQuery")) {
                        assertEquals("{ orders { edges { node { id legacyResourceId name } } } }", ((Map) body.variables).query)
                        return jsonResponse([
                                data: [
                                        bulkOperationRunQuery: [
                                                bulkOperation: [
                                                        id    : "gid://shopify/BulkOperation/1",
                                                        status: "CREATED",
                                                ],
                                                userErrors   : [],
                                        ],
                                ],
                        ])
                    }
                    if (query.contains('bulkOperation(id: $id)')) {
                        assertEquals("gid://shopify/BulkOperation/1", ((Map) body.variables).id)
                        return jsonResponse([
                                data: [
                                        bulkOperation: [
                                                id         : "gid://shopify/BulkOperation/1",
                                                status     : "COMPLETED",
                                                objectCount: "2",
                                                fileSize   : "156",
                                                url        : "https://bulk.example.test/orders.jsonl",
                                        ],
                                ],
                        ])
                    }
                    return jsonResponse([errors: [[message: "unexpected query"]]])
                },
                downloadExecutor  : { Map<String, Object> request ->
                    downloadRequests.add(request)
                    return [
                            statusCode: 200,
                            body      : '{"id":"gid://shopify/Order/1001","legacyResourceId":"1001","name":"#1001"}\n{"id":"gid://shopify/Order/1002","legacyResourceId":"1002","name":"#1002"}\n',
                    ]
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals("gid://shopify/BulkOperation/1", result.bulkOperation.id)
        assertEquals("COMPLETED", result.bulkOperation.status)
        assertEquals(2, ((List) result.records).size())
        assertEquals("1001", ((List<Map>) result.records).first().legacyResourceId)
        assertEquals(2, graphqlRequests.size())
        assertEquals(1, downloadRequests.size())
        assertFalse(result.toString().contains("shpat_secret_token"))
        assertFalse(result.toString().contains("orders.jsonl"))
    }

    @Test
    void bulkOperationQueryReturnsSafeUserErrors() {
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                httpExecutor: { Map<String, Object> request ->
                    return jsonResponse([
                            data: [
                                    bulkOperationRunQuery: [
                                            bulkOperation: null,
                                            userErrors   : [[field: ["query"], message: "Bulk queries cannot use that field."]],
                                    ],
                            ],
                    ])
                },
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("Bulk queries cannot use that field.") })
        assertFalse(result.toString().contains("shpat_secret_token"))
    }

    @Test
    void bulkOperationQueryRetriesWhenAnotherBulkOperationIsRunning() {
        int startCalls = 0
        int statusCalls = 0

        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                startRetryAttempts   : 2,
                startRetryDelayMillis: 0,
                pollIntervalMillis   : 0,
                maxPollAttempts      : 1,
                httpExecutor         : { Map<String, Object> request ->
                    String query = ((Map) request.body).query as String
                    if (query.contains("bulkOperationRunQuery")) {
                        startCalls++
                        if (startCalls <= 2) {
                            return jsonResponse([
                                    data: [
                                            bulkOperationRunQuery: [
                                                    bulkOperation: null,
                                                    userErrors   : [[message: "A bulk operation for this app and shop is already in progress."]],
                                            ],
                                    ],
                            ])
                        }
                        return jsonResponse([
                                data: [
                                        bulkOperationRunQuery: [
                                                bulkOperation: [
                                                        id    : "gid://shopify/BulkOperation/retry",
                                                        status: "CREATED",
                                                ],
                                                userErrors   : [],
                                        ],
                                ],
                        ])
                    }
                    statusCalls++
                    return jsonResponse([
                            data: [
                                    bulkOperation: [
                                            id    : "gid://shopify/BulkOperation/retry",
                                            status: "COMPLETED",
                                    ],
                            ],
                    ])
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(3, startCalls)
        assertEquals(1, statusCalls)
        assertEquals(2, result.startRetryCount)
        assertEquals(2, ((List) result.startRetryHistory).size())
        assertEquals("COMPLETED", result.bulkOperation.status)
        assertFalse(result.toString().contains("shpat_secret_token"))
    }

    @Test
    void bulkOperationQueryFailsAfterConcurrentStartRetriesAreExhausted() {
        int startCalls = 0

        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                startRetryAttempts   : 2,
                startRetryDelayMillis: 0,
                httpExecutor         : { Map<String, Object> request ->
                    startCalls++
                    return jsonResponse([
                            data: [
                                    bulkOperationRunQuery: [
                                            bulkOperation: null,
                                            userErrors   : [[message: "Another bulk operation is already running for this shop."]],
                                    ],
                            ],
                    ])
                },
        ])

        assertFalse((Boolean) result.ok)
        assertEquals(3, startCalls)
        assertEquals(2, result.startRetryCount)
        assertEquals(2, ((List) result.startRetryHistory).size())
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("after 2 retry attempt") })
        assertFalse(result.toString().contains("shpat_secret_token"))
    }

    @Test
    void bulkOperationQueryReportsTimeoutWithoutDownloadUrl() {
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis: 0,
                maxPollAttempts   : 1,
                httpExecutor      : { Map<String, Object> request ->
                    String query = ((Map) request.body).query as String
                    if (query.contains("bulkOperationRunQuery")) {
                        return jsonResponse([
                                data: [
                                        bulkOperationRunQuery: [
                                                bulkOperation: [
                                                        id    : "gid://shopify/BulkOperation/1",
                                                        status: "CREATED",
                                                ],
                                                userErrors   : [],
                                        ],
                                ],
                        ])
                    }
                    return jsonResponse([
                            data: [
                                    bulkOperation: [
                                            id    : "gid://shopify/BulkOperation/1",
                                            status: "RUNNING",
                                    ],
                            ],
                    ])
                },
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("did not complete") })
    }

    private static Map<String, Object> authConfig() {
        return [
                shopApiUrl : "https://example.myshopify.com/admin/api",
                apiVersion : "2026-04",
                accessToken: "shpat_secret_token",
        ]
    }

    private static Map<String, Object> jsonResponse(Map body) {
        return [
                statusCode: 200,
                body      : JsonOutput.toJson(body),
        ]
    }
}
