package shopify.graphql

import darpan.common.ValueSupport
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.HttpURLConnection
import java.nio.file.Files

class ShopifyBulkOperationClient {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyBulkOperationClient.class)
    static final int DEFAULT_MAX_POLL_ATTEMPTS = 60
    static final int DEFAULT_POLL_INTERVAL_MILLIS = 5000
    static final int DEFAULT_START_RETRY_ATTEMPTS = 10
    static final int DEFAULT_START_RETRY_DELAY_MILLIS = 120000
    static final int DEFAULT_DOWNLOAD_MAX_ATTEMPTS = 3
    static final int DEFAULT_DOWNLOAD_RETRY_DELAY_MILLIS = 1000

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

    /**
     * DAR-BE-162: reads a downloaded bulk result one line at a time and hands each JSON object to visit, so a month of
     * orders is never in heap at once (holding the whole result as text and then as maps OOMed a 2 GB JVM on ~125k
     * orders). Answers [lineCount, recordCount, errors]. With no visit it only checks the file, which completeOperation
     * does before answering, so a caller never reads a file with a bad line in it.
     */
    static Map<String, Object> eachJsonlRecord(File jsonlFile, Closure visit = null) {
        List<String> errors = []
        int lineCount = 0
        int recordCount = 0
        JsonSlurper slurper = new JsonSlurper()
        if (jsonlFile?.exists()) {
            jsonlFile.withReader("UTF-8") { Reader reader ->
                String line
                while ((line = reader.readLine()) != null) {
                    lineCount++
                    String trimmed = line.trim()
                    if (!trimmed) continue
                    Object parsed
                    try {
                        parsed = slurper.parseText(trimmed)
                    } catch (Exception ignored) {
                        errors.add("Shopify bulk operation JSONL line ${lineCount} was not valid JSON.".toString())
                        continue
                    }
                    if (!(parsed instanceof Map)) {
                        errors.add("Shopify bulk operation JSONL line ${lineCount} was not a JSON object.".toString())
                        continue
                    }
                    recordCount++
                    if (visit != null) visit.call((Map<String, Object>) parsed)
                }
            }
        }
        return [lineCount: lineCount, recordCount: recordCount, errors: errors]
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
        // Hard-cap mutation retries to 1 attempt here, then rely on the outer concurrent-start retry
        // loop in runQuery to back off and re-issue the start, which IS safe.
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
        Map<String, Object> failureContext = [
                bulkOperation: operationMetadata,
                pollCount    : Math.max(0, statusHistory.size() - 1),
                statusHistory: statusHistory,
        ] as Map<String, Object>
        Long expectedCount = parseObjectCount(operation.objectCount)
        // jsonlFile is the downloaded result (null when there was nothing to download). The caller owns it: read it with
        // eachJsonlRecord, then move it into place or delete it.
        Map<String, Object> baseResult = [
                ok            : true,
                bulkOperation : operationMetadata,
                pollCount     : Math.max(0, statusHistory.size() - 1),
                statusHistory : statusHistory,
                jsonlFile     : null,
                jsonlLineCount: 0,
        ] as Map<String, Object>
        if (!downloadUrl) {
            // A COMPLETED operation that reports objects but no result URL would otherwise look like
            // a legitimately empty extraction and silently drop every record.
            if (expectedCount != null && expectedCount > 0L) {
                return safeFailure("Shopify bulk operation completed with ${expectedCount} object(s) but returned no result download URL.", failureContext)
            }
            return baseResult
        }

        File jsonlFile = newResultFile(options)
        boolean handedOver = false
        try {
            Map<String, Object> downloadResult = downloadWithRetry(downloadUrl, jsonlFile, options ?: [:])
            if (downloadResult.ok == false) {
                Map<String, Object> downloadFailureContext = new LinkedHashMap<String, Object>(failureContext)
                if (downloadResult.statusCode != null) downloadFailureContext.statusCode = downloadResult.statusCode
                if (downloadResult.retryable != null) downloadFailureContext.retryable = downloadResult.retryable
                if (downloadResult.downloadAttemptCount != null) downloadFailureContext.downloadAttemptCount = downloadResult.downloadAttemptCount
                return safeFailure(((List) downloadResult.errors)?.join("; ") ?: "Shopify bulk operation result could not be downloaded.", downloadFailureContext)
            }

            Map<String, Object> checked = eachJsonlRecord(jsonlFile)
            List<String> parseErrors = (List<String>) (checked.errors ?: [])
            if (parseErrors) {
                return safeFailure("Shopify bulk operation result could not be parsed: ${parseErrors.join('; ')}.", failureContext)
            }

            int parsedCount = (checked.recordCount ?: 0) as int
            // The client always starts the bulk query with groupObjects: false, so objectCount is exactly
            // one JSONL object per line; a download truncated at a newline boundary parses cleanly and
            // would otherwise feed reconciliation a silently incomplete record set.
            if (expectedCount != null && expectedCount != (parsedCount as long)) {
                return safeFailure("Shopify bulk operation result was incomplete: expected ${expectedCount} record(s) but parsed ${parsedCount}.",
                        (failureContext + [retryable: true]) as Map<String, Object>)
            }

            handedOver = true
            return baseResult + [
                    jsonlFile     : jsonlFile,
                    jsonlLineCount: checked.lineCount ?: 0,
            ]
        } finally {
            if (!handedOver) jsonlFile.delete()
        }
    }

    /**
     * DAR-BE-162: the file a result downloads into — in options.downloadDirectory when given (the extract's output
     * directory, so the raw JSONL moves into place by rename), else the system temp directory. Owner-only on POSIX:
     * it holds customer orders.
     */
    private static File newResultFile(Map options) {
        File directory = options?.downloadDirectory instanceof File ? (File) options.downloadDirectory : null
        return directory != null
                ? Files.createTempFile(directory.toPath(), "shopify-bulk-", ".jsonl.partial").toFile()
                : Files.createTempFile("shopify-bulk-", ".jsonl.partial").toFile()
    }

    private static Long parseObjectCount(Object rawValue) {
        String text = ValueSupport.normalize(rawValue)
        if (!text?.isLong()) return null
        return text.toLong()
    }

    private static Map<String, Object> downloadWithRetry(String url, File target, Map options) {
        int maxAttempts = Math.max(1, normalizeInt(options.downloadMaxAttempts, DEFAULT_DOWNLOAD_MAX_ATTEMPTS))
        long retryDelayMillis = Math.max(0L, normalizeInt(options.downloadRetryDelayMillis, DEFAULT_DOWNLOAD_RETRY_DELAY_MILLIS) as long)
        Map<String, Object> result = null
        // The result download is an idempotent GET (unlike the H6.5-capped start mutation), so
        // transient 429/5xx failures retry instead of hard-failing a completed long-running operation.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = download(url, target, options)
            if (result.ok != false || result.retryable != true || attempt >= maxAttempts) {
                if (result.ok == false) result.downloadAttemptCount = attempt
                return result
            }
            if (retryDelayMillis > 0L) {
                try {
                    Thread.sleep(retryDelayMillis)
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt()
                    return safeFailure("Shopify bulk operation result download retry was interrupted.", [downloadAttemptCount: attempt] as Map<String, Object>)
                }
            }
        }
        return result ?: safeFailure("Shopify bulk operation result download did not return a response.")
    }

    /** One GET of the result into target, overwriting what an earlier attempt left there. */
    private static Map<String, Object> download(String url, File target, Map options) {
        Closure downloadExecutor = (Closure) (options.downloadExecutor ?: { Map<String, Object> request -> executeDownloadRequest(request) })
        Map<String, Object> request = [
                method              : "GET",
                url                 : url,
                headers             : [:],
                connectTimeoutMillis: normalizeInt(options.connectTimeoutMillis, ShopifyGraphqlTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS),
                readTimeoutMillis   : normalizeInt(options.readTimeoutMillis, ShopifyGraphqlTransport.DEFAULT_READ_TIMEOUT_MILLIS),
                targetFile          : target,
        ]
        Map<String, Object> response
        try {
            response = (Map<String, Object>) downloadExecutor.call(request)
        } catch (ShopifyGraphqlTransport.OutboundPolicyBlockedException policyBlocked) {
            return safeFailure(policyBlocked.message)
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

        // The real executor has streamed the body into targetFile; an injected one (tests) answers with it instead.
        if (response?.body != null) target.setText(response.body.toString(), "UTF-8")
        return [
                ok        : true,
                statusCode: statusCode,
                retryable : false,
        ]
    }

    private static Map<String, Object> executeDownloadRequest(Map<String, Object> request) {
        // Audit #15 parity: the bulk-result URL comes from the GraphQL response body and is served
        // off-domain (a signed storage URL), so re-validate it with no host allow-list — https is
        // still forced and loopback/link-local/RFC1918/cloud-metadata targets are blocked.
        def __urlCheck = darpan.facade.common.OutboundHttpPolicy.validate(request.url?.toString(), null)
        if (!__urlCheck.ok) throw new ShopifyGraphqlTransport.OutboundPolicyBlockedException("Shopify bulk result URL blocked by outbound policy: ${__urlCheck.error}")
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url.toString()).openConnection()
        connection.requestMethod = "GET"
        // The signed result URL is a direct download; never auto-follow a redirect past the policy check.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = (request.connectTimeoutMillis ?: ShopifyGraphqlTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS) as int
        connection.readTimeout = (request.readTimeoutMillis ?: ShopifyGraphqlTransport.DEFAULT_READ_TIMEOUT_MILLIS) as int

        long started = System.currentTimeMillis()
        Integer statusCode = null
        File target = (File) request.targetFile
        try {
            statusCode = connection.responseCode
            if (statusCode >= 200 && statusCode < 300) {
                // DAR-BE-162: straight to disk, never into a String. A FileOutputStream truncates the file in place,
                // so it keeps the owner-only permissions newResultFile gave it.
                connection.inputStream.withCloseable { InputStream input ->
                    new FileOutputStream(target).withCloseable { OutputStream output -> input.transferTo(output) }
                }
            }
            return [statusCode: statusCode]
        } finally {
            // DAR-BE-129. The bulk result download is a Shopify call too; counted beside the GraphQL ones.
            logger.info("shopify bulk download status={} ms={} bytes={}", statusCode, System.currentTimeMillis() - started,
                    target?.length())
        }
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
