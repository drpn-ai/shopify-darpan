package shopify.graphql

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

class ShopifyGraphqlTransport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyGraphqlTransport)

    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 30000
    static final int DEFAULT_READ_TIMEOUT_MILLIS = 60000
    static final int DEFAULT_MAX_ATTEMPTS = 2
    static final long DEFAULT_THROTTLE_DELAY_MILLIS = 1000L
    static final long MAX_THROTTLE_DELAY_MILLIS = 10000L

    /** Marker for requests rejected by the outbound URL policy — never retryable. */
    static class OutboundPolicyBlockedException extends IllegalStateException {
        OutboundPolicyBlockedException(String message) { super(message) }
    }

    static Map<String, Object> execute(Map authConfig, String queryDocument, Map variables = [:], Map options = [:]) {
        String normalizedQuery = normalize(queryDocument)
        if (!normalizedQuery) return safeFailure("Shopify GraphQL query document is required.", false)
        String accessToken = normalize(authConfig?.accessToken)
        if (!accessToken) return safeFailure("Shopify access token is not configured.", false)

        Map<String, Object> request
        try {
            request = buildRequest(authConfig, normalizedQuery, variables ?: [:], options)
        } catch (Exception e) {
            return safeFailure(e.message ?: "Shopify GraphQL request could not be built.", false)
        }
        Closure httpExecutor = (Closure) (options.httpExecutor ?: { Map<String, Object> requestMap -> executeHttpRequest(requestMap) })
        // Audit H6.5 parity: GraphQL mutations are non-idempotent and per the spec must begin with the
        // 'mutation' keyword (shorthand documents are always queries), so never re-send them.
        int maxAttempts = isMutationDocument(normalizedQuery) ? 1 : Math.max(1, normalizeInt(options.maxAttempts, DEFAULT_MAX_ATTEMPTS))
        long retryDelayMillis = Math.max(0L, (normalizeInt(options.retryDelayMillis, 0) ?: 0) as long)

        Map<String, Object> lastResult = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> response = (Map<String, Object>) httpExecutor.call(request)
                lastResult = parseResponse(response, attempt, maxAttempts)
            } catch (OutboundPolicyBlockedException policyBlocked) {
                logger.warn("Shopify GraphQL request blocked by outbound policy (attempt ${attempt}/${maxAttempts}): ${policyBlocked.message}")
                lastResult = safeFailure(policyBlocked.message, false)
            } catch (Exception e) {
                logger.warn("Shopify GraphQL request attempt ${attempt}/${maxAttempts} failed: ${e.class.simpleName}: ${e.message}")
                lastResult = safeFailure("Shopify GraphQL request failed before a valid response was received.", attempt < maxAttempts)
            }

            if (lastResult.ok || !lastResult.retryable || attempt >= maxAttempts) return lastResult
            long delayMillis = retryDelayMillis
            // Audit H6.6 follow-up: a THROTTLED/429 retry with no pause just re-spends the same cost
            // budget. Derive the wait from extensions.cost.throttleStatus when present, else 1s.
            if (delayMillis <= 0L && (lastResult.throttled || (lastResult.statusCode as Integer) == 429)) {
                delayMillis = throttleDelayMillis(lastResult)
            }
            if (delayMillis > 0L) Thread.sleep(delayMillis)
        }
        return lastResult ?: safeFailure("Shopify GraphQL request did not return a response.", false)
    }

    static boolean isMutationDocument(String queryDocument) {
        String stripped = (queryDocument ?: "").replaceAll(/(?m)^\s*#[^\n]*$/, "").trim()
        return stripped.toLowerCase(Locale.ROOT) ==~ /(?s)mutation\b.*/
    }

    static long throttleDelayMillis(Map<String, Object> result) {
        try {
            Map cost = result?.cost instanceof Map ? (Map) result.cost : null
            Map throttleStatus = cost?.get('throttleStatus') instanceof Map ? (Map) cost.get('throttleStatus') : null
            BigDecimal requested = cost?.get('requestedQueryCost') != null ? new BigDecimal(cost.get('requestedQueryCost').toString()) : null
            BigDecimal available = throttleStatus?.get('currentlyAvailable') != null ? new BigDecimal(throttleStatus.get('currentlyAvailable').toString()) : null
            BigDecimal restoreRate = throttleStatus?.get('restoreRate') != null ? new BigDecimal(throttleStatus.get('restoreRate').toString()) : null
            if (requested == null || available == null || restoreRate == null || restoreRate <= 0G) return DEFAULT_THROTTLE_DELAY_MILLIS
            BigDecimal missing = requested.subtract(available)
            if (missing <= 0G) return DEFAULT_THROTTLE_DELAY_MILLIS
            long millis = missing.divide(restoreRate, 3, java.math.RoundingMode.CEILING).multiply(1000G).longValue()
            return Math.min(MAX_THROTTLE_DELAY_MILLIS, Math.max(DEFAULT_THROTTLE_DELAY_MILLIS, millis))
        } catch (Exception ignored) {
            return DEFAULT_THROTTLE_DELAY_MILLIS
        }
    }

    static Map<String, Object> buildRequest(Map authConfig, String queryDocument, Map variables = [:], Map options = [:]) {
        String endpointUrl = buildAdminGraphqlEndpoint(authConfig?.shopApiUrl, authConfig?.apiVersion)
        Integer connectTimeoutMillis = normalizeInt(options.connectTimeoutMillis, DEFAULT_CONNECT_TIMEOUT_MILLIS)
        Integer readTimeoutMillis = normalizeInt(options.readTimeoutMillis, DEFAULT_READ_TIMEOUT_MILLIS)
        return [
            method              : "POST",
            url                 : endpointUrl,
            headers             : [
                "Content-Type"          : "application/json",
                "X-Shopify-Access-Token": normalize(authConfig?.accessToken),
            ],
            body                : [
                query    : queryDocument,
                variables: variables ?: [:],
            ],
            connectTimeoutMillis: connectTimeoutMillis,
            readTimeoutMillis   : readTimeoutMillis,
        ]
    }

    static String buildAdminGraphqlEndpoint(Object shopApiUrl, Object apiVersion) {
        String baseUrl = normalize(shopApiUrl)
        String version = normalize(apiVersion)
        if (!baseUrl) throw new IllegalArgumentException("Shop/API URL is required.")
        if (!version) throw new IllegalArgumentException("Shopify API version is required.")

        String withoutTrailingSlash = baseUrl.replaceAll(/\/+$/, "")
        if (withoutTrailingSlash.endsWith("/graphql.json")) return withoutTrailingSlash

        int adminApiIndex = withoutTrailingSlash.indexOf("/admin/api")
        if (adminApiIndex >= 0) {
            withoutTrailingSlash = withoutTrailingSlash.substring(0, adminApiIndex + "/admin/api".length())
            return "${withoutTrailingSlash}/${version}/graphql.json"
        }
        return "${withoutTrailingSlash}/admin/api/${version}/graphql.json"
    }

    static Map<String, Object> parseResponse(Map<String, Object> response, int attempt = 1, int maxAttempts = 1) {
        int statusCode = (response?.statusCode ?: response?.status ?: 0) as int
        String bodyText = response?.body?.toString() ?: ""
        boolean retryableStatus = statusCode == 429 || statusCode >= 500

        if (statusCode < 200 || statusCode >= 300) {
            return [
                ok        : false,
                errors    : ["Shopify GraphQL request failed with HTTP ${statusCode}."],
                statusCode: statusCode,
                retryable : retryableStatus && attempt < maxAttempts,
            ]
        }

        Map parsedBody
        try {
            parsedBody = (Map) new JsonSlurper().parseText(bodyText)
        } catch (Exception ignored) {
            return [
                ok        : false,
                errors    : ["Shopify GraphQL response was not valid JSON."],
                statusCode: statusCode,
                retryable : false,
            ]
        }

        List<String> graphqlErrors = ((List) (parsedBody.errors ?: []))
            .collect { Object error -> normalize(error instanceof Map ? error.message : error) }
            .findAll { String message -> message }

        // Audit H6.6 — Shopify GraphQL returns HTTP 200 with errors[].extensions.code == 'THROTTLED'
        // (or 'MAX_COST_EXCEEDED') as a soft rate-limit signal, not a permanent failure. Previously
        // every GraphQL error was treated as retryable=false, so the caller surfaced 'throttled' as
        // a user error. Now we detect throttle codes and mark the call retryable until maxAttempts.
        if (graphqlErrors) {
            List<Map> errorObjs = ((List) parsedBody.errors).findAll { it instanceof Map } as List<Map>
            boolean throttled = errorObjs.any { Map err ->
                String code = err?.extensions instanceof Map ? normalize((err.extensions as Map).code) : null
                return code in ["THROTTLED", "MAX_COST_EXCEEDED"]
            }
            return [
                ok            : false,
                errors        : graphqlErrors.collect { String message -> "Shopify GraphQL error: ${message}" },
                graphqlErrors : graphqlErrors,
                cost          : parsedBody.extensions?.cost,
                extensions    : parsedBody.extensions,
                statusCode    : statusCode,
                retryable     : throttled && attempt < maxAttempts,
                throttled     : throttled,
            ]
        }

        return [
            ok        : true,
            data      : parsedBody.data,
            cost      : parsedBody.extensions?.cost,
            extensions: parsedBody.extensions,
            statusCode: statusCode,
            retryable : false,
        ]
    }

    // Audit 2026-06-11 #15: re-validate the resolved Shopify endpoint against the outbound policy at
    // request time (not only at config-save time), so a shopApiUrl mutated out-of-band cannot make the
    // server fetch loopback / link-local / RFC1918 / cloud-metadata targets (SSRF). Same suffix the
    // save path enforces. Runs only on the real network path; tests inject their own httpExecutor.
    private static final List<String> OUTBOUND_HOST_SUFFIXES = [".myshopify.com"]

    private static Map<String, Object> executeHttpRequest(Map<String, Object> request) {
        def __urlCheck = darpan.facade.common.OutboundHttpPolicy.validate(request.url?.toString(), OUTBOUND_HOST_SUFFIXES)
        if (!__urlCheck.ok) throw new OutboundPolicyBlockedException("Shopify endpoint URL blocked by outbound policy: ${__urlCheck.error}")
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url.toString()).openConnection()
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = (request.connectTimeoutMillis ?: DEFAULT_CONNECT_TIMEOUT_MILLIS) as int
        connection.readTimeout = (request.readTimeoutMillis ?: DEFAULT_READ_TIMEOUT_MILLIS) as int
        ((Map<String, String>) request.headers).each { String key, String value -> connection.setRequestProperty(key, value) }

        byte[] bodyBytes = JsonOutput.toJson(request.body ?: [:]).getBytes(StandardCharsets.UTF_8)
        connection.outputStream.withCloseable { OutputStream output -> output.write(bodyBytes) }

        int statusCode = connection.responseCode
        InputStream input = statusCode >= 400 ? connection.errorStream : connection.inputStream
        String responseBody = input != null ? input.getText("UTF-8") : ""
        return [
            statusCode: statusCode,
            body      : responseBody,
        ]
    }

    private static Map<String, Object> safeFailure(String message, boolean retryable) {
        return [
            ok       : false,
            errors   : [message],
            retryable: retryable,
        ]
    }
}
