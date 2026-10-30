package shopify.graphql

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
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
        List<Map> records = readRecords(result)
        assertEquals(2, records.size())
        assertEquals("1001", records.first().legacyResourceId)
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

    @Test
    void bulkOperationFailsWhenCompletedReportsObjectsButNoDownloadUrl() {
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis: 0,
                maxPollAttempts   : 1,
                httpExecutor      : completedOperationExecutor([id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: "5"]),
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("no result download URL") })
    }

    @Test
    void bulkOperationSucceedsWhenCompletedWithZeroObjectsAndNoUrl() {
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis: 0,
                maxPollAttempts   : 1,
                httpExecutor      : completedOperationExecutor([id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: "0"]),
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertNull(result.jsonlFile, "nothing to download, nothing to read")
    }

    @Test
    void bulkOperationFailsWhenDownloadParsesFewerRecordsThanObjectCount() {
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis      : 0,
                maxPollAttempts         : 1,
                downloadRetryDelayMillis: 0,
                httpExecutor            : completedOperationExecutor([
                        id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: "3",
                        url: "https://bulk.example.test/orders.jsonl",
                ]),
                downloadExecutor        : { Map<String, Object> request ->
                    return [statusCode: 200, body: '{"id":"1001"}\n{"id":"1002"}\n']
                },
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("expected 3 record(s) but parsed 2") })
        assertEquals(true, result.retryable)
        assertFalse(result.toString().contains("orders.jsonl"))
    }

    @Test
    void bulkOperationRetriesTransientDownloadFailures() {
        int downloadCalls = 0
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis      : 0,
                maxPollAttempts         : 1,
                downloadRetryDelayMillis: 0,
                httpExecutor            : completedOperationExecutor([
                        id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: "1",
                        url: "https://bulk.example.test/orders.jsonl",
                ]),
                downloadExecutor        : { Map<String, Object> request ->
                    downloadCalls++
                    if (downloadCalls == 1) return [statusCode: 500, body: "temporary failure"]
                    return [statusCode: 200, body: '{"id":"1001"}\n']
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(2, downloadCalls)
        assertEquals(1, readRecords(result).size(), "the retry overwrote the failed attempt, it did not append to it")
    }

    @Test
    void bulkOperationDoesNotRetryClientErrorDownloads() {
        int downloadCalls = 0
        Map<String, Object> result = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                pollIntervalMillis      : 0,
                maxPollAttempts         : 1,
                downloadRetryDelayMillis: 0,
                httpExecutor            : completedOperationExecutor([
                        id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: "1",
                        url: "https://bulk.example.test/orders.jsonl",
                ]),
                downloadExecutor        : { Map<String, Object> request ->
                    downloadCalls++
                    return [statusCode: 404, body: "gone"]
                },
        ])

        assertFalse((Boolean) result.ok)
        assertEquals(1, downloadCalls)
        assertEquals(404, result.statusCode)
        assertEquals(1, result.downloadAttemptCount)
        assertFalse(result.toString().contains("orders.jsonl"))
    }

    @Test
    void bulkOperationDownloadsIntoTheGivenDirectoryAndRemovesTheFileWhenTheResultIsRejected() {
        // DAR-BE-162: the result is a file in the extract's output folder (moved into place by rename), never a String.
        File directory = Files.createTempDirectory("bulk-download-").toFile()
        try {
            Map<String, Object> kept = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                    pollIntervalMillis: 0,
                    maxPollAttempts   : 1,
                    downloadDirectory : directory,
                    httpExecutor      : completedOperationExecutor([id: "gid://shopify/BulkOperation/1", status: "COMPLETED",
                                                                    objectCount: "2", url: "https://bulk.example.test/orders.jsonl"]),
                    downloadExecutor  : { Map<String, Object> request -> [statusCode: 200, body: '{"id":"1001"}\n\n{"id":"1002"}\n'] },
            ])
            assertTrue((Boolean) kept.ok, kept.errors?.toString())
            assertEquals(directory, ((File) kept.jsonlFile).parentFile)
            assertEquals(3, kept.jsonlLineCount, "blank lines are counted as lines, not as records")
            assertFalse(kept.containsKey("records"))
            assertFalse(kept.containsKey("jsonlText"))
            assertEquals(["1001", "1002"], readRecords(kept)*.id)

            Map<String, Object> rejected = ShopifyBulkOperationClient.runQuery(authConfig(), "{ orders { edges { node { id } } } }", [
                    pollIntervalMillis: 0,
                    maxPollAttempts   : 1,
                    downloadDirectory : directory,
                    httpExecutor      : completedOperationExecutor([id: "gid://shopify/BulkOperation/2", status: "COMPLETED",
                                                                    objectCount: "2", url: "https://bulk.example.test/orders.jsonl"]),
                    downloadExecutor  : { Map<String, Object> request -> [statusCode: 200, body: '{"id":"1001"}\nnot json\n'] },
            ])
            assertFalse((Boolean) rejected.ok)
            assertTrue(((List<String>) rejected.errors).any { String error -> error.contains("line 2 was not valid JSON") })
            assertEquals([], directory.list() as List, "a rejected result leaves no customer data behind")
        } finally {
            directory.deleteDir()
        }
    }

    @Test
    void eachJsonlRecordHandsOverObjectsOneAtATimeAndReportsBadLines() {
        File file = Files.createTempFile("bulk-lines-", ".jsonl").toFile()
        try {
            file.setText('{"id":"1"}\n[1,2]\n\n{"id":"2"}\n{oops\n', "UTF-8")
            List<String> seen = []
            Map<String, Object> read = ShopifyBulkOperationClient.eachJsonlRecord(file) { Map record -> seen.add(record.id as String) }
            assertEquals(["1", "2"], seen)
            assertEquals(5, read.lineCount)
            assertEquals(2, read.recordCount)
            assertEquals(["Shopify bulk operation JSONL line 2 was not a JSON object.",
                          "Shopify bulk operation JSONL line 5 was not valid JSON."], read.errors)
        } finally {
            file.delete()
        }
    }

    /** The downloaded result's records, read the way an extract reads them; the file is then deleted. */
    private static List<Map> readRecords(Map<String, Object> result) {
        File file = (File) result.jsonlFile
        try {
            List<Map> records = []
            ShopifyBulkOperationClient.eachJsonlRecord(file) { Map record -> records.add(record) }
            return records
        } finally {
            file?.delete()
        }
    }

    /** Start succeeds, then every status poll reports the supplied completed operation. */
    private static Closure completedOperationExecutor(Map operation) {
        return { Map<String, Object> request ->
            String query = ((Map) request.body).query as String
            if (query.contains("bulkOperationRunQuery")) {
                return jsonResponse([
                        data: [
                                bulkOperationRunQuery: [
                                        bulkOperation: [id: operation.id, status: "CREATED"],
                                        userErrors   : [],
                                ],
                        ],
                ])
            }
            return jsonResponse([data: [bulkOperation: operation]])
        }
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
