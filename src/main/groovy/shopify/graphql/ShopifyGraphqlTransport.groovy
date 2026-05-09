package shopify.graphql

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

class ShopifyGraphqlTransport {
    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 30000
    static final int DEFAULT_READ_TIMEOUT_MILLIS = 60000
    static final int DEFAULT_MAX_ATTEMPTS = 2

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
        int maxAttempts = Math.max(1, normalizeInt(options.maxAttempts, DEFAULT_MAX_ATTEMPTS))
        long retryDelayMillis = Math.max(0L, (normalizeInt(options.retryDelayMillis, 0) ?: 0) as long)

        Map<String, Object> lastResult = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> response = (Map<String, Object>) httpExecutor.call(request)
                lastResult = parseResponse(response, attempt, maxAttempts)
            } catch (Exception ignored) {
                lastResult = safeFailure("Shopify GraphQL request failed before a valid response was received.", attempt < maxAttempts)
            }

            if (lastResult.ok || !lastResult.retryable || attempt >= maxAttempts) return lastResult
            if (retryDelayMillis > 0L) Thread.sleep(retryDelayMillis)
        }
        return lastResult ?: safeFailure("Shopify GraphQL request did not return a response.", false)
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

        if (graphqlErrors) {
            return [
                ok            : false,
                errors        : graphqlErrors.collect { String message -> "Shopify GraphQL error: ${message}" },
                graphqlErrors : graphqlErrors,
                cost          : parsedBody.extensions?.cost,
                extensions    : parsedBody.extensions,
                statusCode    : statusCode,
                retryable     : false,
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

    private static Map<String, Object> executeHttpRequest(Map<String, Object> request) {
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
