package shopify.graphql

import darpan.common.ValueSupport
import groovy.json.JsonSlurper

import java.net.HttpURLConnection

class ShopifyBulkOperationClient {
    static final int DEFAULT_MAX_POLL_ATTEMPTS = 60
    static final int DEFAULT_POLL_INTERVAL_MILLIS = 5000
    static final int DEFAULT_START_RETRY_ATTEMPTS = 10
    static final int DEFAULT_START_RETRY_DELAY_MILLIS = 120000

    private static final Set<String> FAILED_STATUSES = ["FAILED", "CANCELED", "CANCELLED", "EXPIRED"] as Set

    private static final String RUN_QUERY_MUTATION = '''mutation DarpanBulkOperationRunQuery($query: String!, $groupObjects: Boolean!) {
  bulkOperationRunQuery(query: $query, groupObjects: $groupObjects) {
    bulkOperation {
      id
      status
    }
    userErrors {
      field
      message
    }
  }
}'''

    private static final String STATUS_BY_ID_QUERY = '''query DarpanBulkOperationStatus($id: ID!) {
  bulkOperation(id: $id) {
    id
    status
    errorCode
    createdAt
    completedAt
    objectCount
    fileSize
    url
    partialDataUrl
  }
}'''

    private static final String CURRENT_STATUS_QUERY = '''query DarpanCurrentBulkOperationStatus {
  currentBulkOperation {
    id
    status
    errorCode
    createdAt
    completedAt
    objectCount
    fileSize
    url
    partialDataUrl
  }
}'''

    static Map<String, Object> runQuery(Map authConfig, String bulkQueryDocument, Map options = [:]) {
        String normalizedQuery = ValueSupport.normalize(bulkQueryDocument)
        if (!normalizedQuery) return safeFailure("Shopify bulk operation query document is required.")

        Map<String, Object> runOptions = options ?: [:]
        int startRetryAttempts = Math.max(0, normalizeInt(runOptions.startRetryAttempts, DEFAULT_START_RETRY_ATTEMPTS))
        int startRetryDelayMillis = Math.max(0, normalizeInt(runOptions.startRetryDelayMillis, DEFAULT_START_RETRY_DELAY_MILLIS))
        List<Map<String, Object>> startRetryHistory = []

        Map<String, Object> startResult = null
        for (int startAttempt = 0; startAttempt <= startRetryAttempts; startAttempt++) {
            startResult = startQuery(authConfig, normalizedQuery, runOptions)
            if (startResult.ok != false) break
            if (!isConcurrentBulkOperationFailure(startResult)) return startResult

            List<String> startErrors = sanitizeErrorList(startResult.errors ?: startResult.userErrors)
            if (startAttempt >= startRetryAttempts) {
                return safeFailure("Shopify bulk operation could not start because another bulk operation was still running after ${startRetryAttempts} retry attempt(s).",
                        [
                                startRetryCount  : startRetryHistory.size(),
                                startRetryHistory: startRetryHistory,
                                lastStartErrors  : startErrors,
                        ].findAll { entry -> entry.value != null } as Map<String, Object>)
            }

            startRetryHistory.add([
                    attempt: startAttempt + 1,
                    errors : startErrors,
            ].findAll { entry -> entry.value != null } as Map<String, Object>)

            if (startRetryDelayMillis > 0) {
                try {
                    Thread.sleep(startRetryDelayMillis as long)
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt()
                    return safeFailure("Shopify bulk operation start retry was interrupted.",
                            [startRetryCount: startRetryHistory.size(), startRetryHistory: startRetryHistory])
                }
            }
        }
        if (startResult.ok == false) return startResult

        Map<String, Object> operation = startResult.bulkOperation instanceof Map ?
                (Map<String, Object>) startResult.bulkOperation : [:]
        String operationId = ValueSupport.normalize(operation.id)
        if (!operationId) return safeFailure("Shopify bulk operation did not return an operation id.")

        int maxPollAttempts = Math.max(1, normalizeInt(options?.maxPollAttempts, DEFAULT_MAX_POLL_ATTEMPTS))
        int pollIntervalMillis = Math.max(0, normalizeInt(options?.pollIntervalMillis, DEFAULT_POLL_INTERVAL_MILLIS))
        List<Map<String, Object>> statusHistory = [safeOperationMetadata(operation)]

        for (int pollAttempt = 0; pollAttempt <= maxPollAttempts; pollAttempt++) {
            String status = ValueSupport.normalize(operation.status)?.toUpperCase()
            if (status == "COMPLETED") {
                return completeOperation(operation, statusHistory, runOptions) + retryMetadata(startRetryHistory)
            }
            if (FAILED_STATUSES.contains(status)) {
                return safeFailure("Shopify bulk operation ${operationId} ended with status ${status}.",
                        [bulkOperation: safeOperationMetadata(operation), pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory] + retryMetadata(startRetryHistory))
            }
            if (pollAttempt >= maxPollAttempts) {
                return safeFailure("Shopify bulk operation ${operationId} did not complete after ${maxPollAttempts} poll attempt(s).",
                        [bulkOperation: safeOperationMetadata(operation), pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory] + retryMetadata(startRetryHistory))
            }
            if (pollIntervalMillis > 0) {
                try {
                    Thread.sleep(pollIntervalMillis as long)
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt()
                    return safeFailure("Shopify bulk operation polling was interrupted.",
                            [bulkOperation: safeOperationMetadata(operation), pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory] + retryMetadata(startRetryHistory))
                }
            }

            Map<String, Object> statusResult = pollOperation(authConfig, operationId, runOptions)
            if (statusResult.ok == false) return statusResult
            operation = statusResult.bulkOperation instanceof Map ? (Map<String, Object>) statusResult.bulkOperation : [:]
            if (!ValueSupport.normalize(operation.id)) operation.id = operationId
            statusHistory.add(safeOperationMetadata(operation))
        }

        return safeFailure("Shopify bulk operation ${operationId} did not complete.",
                [bulkOperation: safeOperationMetadata(operation), pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory] + retryMetadata(startRetryHistory))
    }

    static Map<String, Object> parseJsonlRecords(String jsonlText) {
        List<Map<String, Object>> records = []
        List<String> errors = []
        int lineCount = 0
        JsonSlurper slurper = new JsonSlurper()

        (jsonlText ?: "").eachLine { String line ->
            lineCount++
            String trimmed = line?.trim()
            if (!trimmed) return
            try {
                Object parsed = slurper.parseText(trimmed)
                if (parsed instanceof Map) {
                    records.add((Map<String, Object>) parsed)
                } else {
                    errors.add("Shopify bulk operation JSONL line ${lineCount} was not a JSON object.")
                }
            } catch (Exception ignored) {
                errors.add("Shopify bulk operation JSONL line ${lineCount} was not valid JSON.")
            }
        }

        return [
                records  : records,
                lineCount: lineCount,
                errors   : errors,
        ]
    }

    static String escapeGraphqlString(String value) {
        return (value ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
    }

    private static Map<String, Object> startQuery(Map authConfig, String bulkQueryDocument, Map options) {
        // Audit H6.5 — bulkOperationRunQuery is a NON-idempotent mutation. If the first attempt
        // reaches Shopify and the response or socket drops, Shopify accepts the start; a retry then
        // races with the concurrent-bulk-operation check ('another bulk operation is running').
        // Hard-cap mutation retries to 1 attempt here, then rely on the caller's outer concurrent-
        // retry loop in runBulkQueryWithConcurrentRetry to back off and re-poll, which IS safe.
        Map<String, Object> mutationOptions = new LinkedHashMap<String, Object>(options ?: [:])
        mutationOptions.maxAttempts = 1
        Map<String, Object> graphqlResult = ShopifyGraphqlTransport.execute(authConfig, RUN_QUERY_MUTATION, [
                query       : bulkQueryDocument,
                groupObjects: false,
        ], mutationOptions)
        if (graphqlResult.ok == false) return graphqlResult

        Map data = graphqlResult.data instanceof Map ? (Map) graphqlResult.data : [:]
        Map payload = data.bulkOperationRunQuery instanceof Map ? (Map) data.bulkOperationRunQuery : [:]
        List<String> userErrors = normalizeUserErrors(payload.userErrors)
        if (userErrors) {
            return safeFailure("Shopify bulk operation could not start: ${userErrors.join('; ')}.",
                    [userErrors: userErrors, retryable: isConcurrentBulkOperationMessage(userErrors)])
        }

        Map operation = payload.bulkOperation instanceof Map ? (Map) payload.bulkOperation : [:]
        if (!ValueSupport.normalize(operation.id)) {
            return safeFailure("Shopify bulk operation could not start.")
        }

        return [
                ok           : true,
                bulkOperation: safeOperationMetadata(operation),
                cost         : graphqlResult.cost,
                retryable    : false,
        ].findAll { entry -> entry.value != null } as Map<String, Object>
    }

    private static Map<String, Object> pollOperation(Map authConfig, String operationId, Map options) {
        boolean useIdQuery = supportsBulkOperationById(authConfig?.apiVersion)
        String queryDocument = useIdQuery ? STATUS_BY_ID_QUERY : CURRENT_STATUS_QUERY
        Map variables = useIdQuery ? [id: operationId] : [:]
        Map<String, Object> graphqlResult = ShopifyGraphqlTransport.execute(authConfig, queryDocument, variables, options ?: [:])
        if (graphqlResult.ok == false) return graphqlResult

        Map data = graphqlResult.data instanceof Map ? (Map) graphqlResult.data : [:]
        Map operation = useIdQuery ?
                (data.bulkOperation instanceof Map ? (Map) data.bulkOperation : [:]) :
                (data.currentBulkOperation instanceof Map ? (Map) data.currentBulkOperation : [:])
        if (!operation) return safeFailure("Shopify bulk operation status was not available.")

        return [
                ok           : true,
                bulkOperation: operation,
                cost         : graphqlResult.cost,
                retryable    : false,
        ].findAll { entry -> entry.value != null } as Map<String, Object>
    }

    private static Map<String, Object> completeOperation(Map<String, Object> operation, List<Map<String, Object>> statusHistory, Map options) {
        String downloadUrl = ValueSupport.normalize(operation.url)
        Map<String, Object> operationMetadata = safeOperationMetadata(operation)
        Map<String, Object> baseResult = [
                ok           : true,
                bulkOperation: operationMetadata,
                pollCount    : Math.max(0, statusHistory.size() - 1),
                statusHistory: statusHistory,
                records      : [],
                jsonlText    : "",
                jsonlLineCount: 0,
        ] as Map<String, Object>
        if (!downloadUrl) return baseResult

        Map<String, Object> downloadResult = downloadText(downloadUrl, options ?: [:])
        if (downloadResult.ok == false) {
            return safeFailure(((List) downloadResult.errors)?.join("; ") ?: "Shopify bulk operation result could not be downloaded.",
                    [bulkOperation: operationMetadata, pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory])
        }

        String jsonlText = downloadResult.body?.toString() ?: ""
        Map<String, Object> parsed = parseJsonlRecords(jsonlText)
        List<String> parseErrors = (List<String>) (parsed.errors ?: [])
        if (parseErrors) {
            return safeFailure("Shopify bulk operation result could not be parsed: ${parseErrors.join('; ')}.",
                    [bulkOperation: operationMetadata, pollCount: Math.max(0, statusHistory.size() - 1), statusHistory: statusHistory])
        }

        return baseResult + [
                records       : parsed.records ?: [],
                jsonlText     : jsonlText,
                jsonlLineCount: parsed.lineCount ?: 0,
        ]
    }

    private static Map<String, Object> downloadText(String url, Map options) {
        Closure downloadExecutor = (Closure) (options.downloadExecutor ?: { Map<String, Object> request -> executeDownloadRequest(request) })
        Map<String, Object> request = [
                method              : "GET",
                url                 : url,
                headers             : [:],
                connectTimeoutMillis: normalizeInt(options.connectTimeoutMillis, ShopifyGraphqlTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS),
                readTimeoutMillis   : normalizeInt(options.readTimeoutMillis, ShopifyGraphqlTransport.DEFAULT_READ_TIMEOUT_MILLIS),
        ]
        Map<String, Object> response
        try {
            response = (Map<String, Object>) downloadExecutor.call(request)
        } catch (Exception ignored) {
            return safeFailure("Shopify bulk operation result download failed before a valid response was received.")
        }

        int statusCode = (response?.statusCode ?: response?.status ?: 0) as int
        if (statusCode < 200 || statusCode >= 300) {
            return [
                    ok        : false,
                    errors    : ["Shopify bulk operation result download failed with HTTP ${statusCode}."],
                    statusCode: statusCode,
                    retryable : statusCode == 429 || statusCode >= 500,
            ]
        }

        return [
                ok        : true,
                body      : response?.body?.toString() ?: "",
                statusCode: statusCode,
                retryable : false,
        ]
    }

    private static Map<String, Object> executeDownloadRequest(Map<String, Object> request) {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url.toString()).openConnection()
        connection.requestMethod = "GET"
        connection.connectTimeout = (request.connectTimeoutMillis ?: ShopifyGraphqlTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS) as int
        connection.readTimeout = (request.readTimeoutMillis ?: ShopifyGraphqlTransport.DEFAULT_READ_TIMEOUT_MILLIS) as int

        int statusCode = connection.responseCode
        InputStream input = statusCode >= 400 ? connection.errorStream : connection.inputStream
        String responseBody = input != null ? input.getText("UTF-8") : ""
        return [
                statusCode: statusCode,
                body      : responseBody,
        ]
    }

    private static Map<String, Object> safeOperationMetadata(Map operation) {
        return [
                id         : ValueSupport.normalize(operation?.id),
                status     : ValueSupport.normalize(operation?.status),
                errorCode  : ValueSupport.normalize(operation?.errorCode),
                createdAt  : ValueSupport.normalize(operation?.createdAt),
                completedAt: ValueSupport.normalize(operation?.completedAt),
                objectCount: ValueSupport.normalize(operation?.objectCount),
                fileSize   : ValueSupport.normalize(operation?.fileSize),
        ].findAll { entry -> entry.value != null } as Map<String, Object>
    }

    private static List<String> normalizeUserErrors(Object rawUserErrors) {
        if (!(rawUserErrors instanceof Collection)) return []
        return ((Collection) rawUserErrors).collect { Object rawError ->
            if (rawError instanceof Map) {
                String field = rawError.field instanceof Collection ?
                        ((Collection) rawError.field).collect { Object item -> ValueSupport.normalize(item) }.findAll { it }.join(".") :
                        ValueSupport.normalize(rawError.field)
                String message = ValueSupport.normalize(rawError.message)
                return field && message ? "${field}: ${message}" : message ?: field
            }
            return ValueSupport.normalize(rawError)
        }.findAll { String error -> error } as List<String>
    }

    private static boolean isConcurrentBulkOperationFailure(Map<String, Object> result) {
        if (result?.ok != false) return false
        return isConcurrentBulkOperationMessage(result.errors) || isConcurrentBulkOperationMessage(result.userErrors) ||
                isConcurrentBulkOperationMessage(result.graphqlErrors)
    }

    private static boolean isConcurrentBulkOperationMessage(Object rawMessages) {
        if (!(rawMessages instanceof Collection)) return false
        return ((Collection) rawMessages).any { Object rawMessage ->
            String message = ValueSupport.normalize(rawMessage)?.toLowerCase()
            message?.contains("bulk operation") && (
                    message.contains("already in progress") ||
                            message.contains("already running") ||
                            message.contains("operation is running") ||
                            message.contains("another bulk operation")
            )
        }
    }

    private static List<String> sanitizeErrorList(Object rawErrors) {
        if (!(rawErrors instanceof Collection)) return []
        return ((Collection) rawErrors)
                .collect { Object error -> ValueSupport.normalize(error instanceof Map ? error.message : error) }
                .findAll { String error -> error } as List<String>
    }

    private static Map<String, Object> retryMetadata(List<Map<String, Object>> startRetryHistory) {
        if (!startRetryHistory) return [:]
        return [
                startRetryCount  : startRetryHistory.size(),
                startRetryHistory: startRetryHistory,
        ] as Map<String, Object>
    }

    private static boolean supportsBulkOperationById(Object apiVersion) {
        String version = ValueSupport.normalize(apiVersion)?.toLowerCase()
        if (!version || version == "unstable") return true
        def matcher = version =~ /^(\d{4})-(\d{2})$/
        if (!matcher.matches()) return true
        int year = matcher[0][1] as int
        int month = matcher[0][2] as int
        return year > 2026 || (year == 2026 && month >= 1)
    }

    private static Map<String, Object> safeFailure(String message, Map<String, Object> extra = [:]) {
        return ([
                ok       : false,
                errors   : [message],
                retryable: false,
        ] + (extra ?: [:])) as Map<String, Object>
    }

    private static int normalizeInt(Object value, int defaultValue) {
        Integer normalized = ValueSupport.normalizeInt(value, defaultValue)
        return normalized == null ? defaultValue : normalized
    }
}
