package shopify.graphql

import darpan.facade.common.OutboundHttpClient
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
        // DAR-BE-260: every Shopify call passes here, so this is the one place customer email, phone, name and
        // address are kept out — catalog extracts, lookups, free-form queries, agent queries and automations alike.
        String protectedFieldRefusal = ShopifyProtectedFieldGuard.refusal(normalizedQuery, variables ?: [:])
        if (protectedFieldRefusal) return safeFailure(protectedFieldRefusal, false)
        String accessToken = resolveAccessToken(authConfig)
        if (!accessToken) return safeFailure("Shopify access token is not configured.", false)

        Map<String, Object> request
        try {
            request = buildRequest(authConfig + [accessToken: accessToken], normalizedQuery, variables ?: [:], options)
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
                countCall(lastResult)
            } catch (OutboundPolicyBlockedException policyBlocked) {
                logger.warn("Shopify GraphQL request blocked by outbound policy (attempt ${attempt}/${maxAttempts}): ${policyBlocked.message}")
                lastResult = safeFailure(policyBlocked.message, false)
            } catch (Exception e) {
                logger.warn("Shopify GraphQL request attempt ${attempt}/${maxAttempts} failed: ${e.class.simpleName}: ${e.message}")
                lastResult = safeFailure("Shopify GraphQL request failed before a valid response was received.", attempt < maxAttempts)
                // The vetted client refuses a host at its DNS step (OutboundHttpPolicy.resolveVetted) before sending anything:
                // the outbound policy blocking too, so not a call to Shopify.
                if (!(e instanceof UnknownHostException || e.cause instanceof UnknownHostException)) countCall(lastResult)
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

    /**
     * DAR-BE-275: a connection made by a Darpan app install carries an accessTokenSupplier (its token
     * expires hourly and is refreshed under a lock); a pasted token is used as given.
     */
    static String resolveAccessToken(Map authConfig) {
        Object supplier = authConfig?.accessTokenSupplier
        if (supplier instanceof Closure) {
            try {
                return normalize(((Closure) supplier).call())
            } catch (Exception e) {
                logger.warn("Shopify access token could not be refreshed: ${e.class.simpleName}")
                return null
            }
        }
        return normalize(authConfig?.accessToken)
    }

    /** DAR-BE-320: one request was sent to Shopify (not one our outbound policy blocked); tally it with its cost and any throttle. */
    private static void countCall(Map<String, Object> result) {
        ShopifyCallTally.add("graphql")
        Map cost = result?.cost instanceof Map ? (Map) result.cost : null
        Object requested = cost?.get('requestedQueryCost')
        if (requested instanceof Number) ShopifyCallTally.add("costPoints", ((Number) requested).longValue())
        if (result?.throttled || (result?.statusCode as Integer) == 429) ShopifyCallTally.add("throttled")
    }

    private static boolean isMutationDocument(String queryDocument) {
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

    private static Map<String, Object> buildRequest(Map authConfig, String queryDocument, Map variables = [:], Map options = [:]) {
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

    private static String buildAdminGraphqlEndpoint(Object shopApiUrl, Object apiVersion) {
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
            String refusal = refusalMessage(statusCode, bodyText, headerValue(response?.headers, "Content-Type"),
                    headerValue(response?.headers, "X-Request-Id"))
            logger.warn(refusal)
            return [
                ok        : false,
                errors    : [refusal],
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

    // DAR-BE-262 follow-up: sent through darpan's OutboundHttpClient, whose DNS step is the same policy's check, so a host
    // that passes validate and then resolves to an internal address when connecting is refused at the connect (an
    // IOException, retried like any failed attempt). No redirects, no cookies. The connect timeout picks the shared
    // guarded client for that timeout; the read timeout is applied per wait for bytes, as HttpURLConnection's was.
    // {@code client} replaces the shared guarded client (tests pass one with their own DNS step).
    static Map<String, Object> executeHttpRequest(Map<String, Object> request, org.eclipse.jetty.client.HttpClient client = null) {
        def __urlCheck = darpan.facade.common.OutboundHttpPolicy.validate(request.url?.toString(), OUTBOUND_HOST_SUFFIXES)
        if (!__urlCheck.ok) throw new OutboundPolicyBlockedException("Shopify endpoint URL blocked by outbound policy: ${__urlCheck.error}")
        long connectTimeout = (request.connectTimeoutMillis ?: DEFAULT_CONNECT_TIMEOUT_MILLIS) as long
        long readTimeout = (request.readTimeoutMillis ?: DEFAULT_READ_TIMEOUT_MILLIS) as long
        org.eclipse.jetty.client.HttpClient http = client ?: OutboundHttpClient.shared(connectTimeout)

        long started = System.currentTimeMillis()
        Integer statusCode = null
        try {
            String json = JsonOutput.toJson(request.body ?: [:])
            return OutboundHttpClient.stream(http, "POST", request.url.toString(), (Map) request.headers, json, readTimeout) { Map answer ->
                statusCode = answer.statusCode as Integer
                // A GraphQL answer is bounded by Shopify's query cost; read whole, as before (error bodies too).
                return [
                    statusCode: statusCode,
                    headers   : answer.headers,
                    body      : ((InputStream) answer.body).getText("UTF-8"),
                ] as Map<String, Object>
            }
        } finally {
            // DAR-BE-129. One line per Shopify request, so a run's calls can be counted by system and operation:
            // op is the document's operation name, or its first selected field when the document is anonymous.
            def op = ((request.body as Map)?.query?.toString() ?: "") =~ /^\s*(?:(?:query|mutation)\s*(\w*)[^{]*)?\{\s*(\w+)/
            logger.info("shopify gql call op={} status={} ms={}", op.find() ? (op.group(1) ?: op.group(2)) : null,
                    statusCode, System.currentTimeMillis() - started)
        }
    }

    /**
     * DAR-BE-326: a refused call names Shopify's own reason (its top-level {@code errors}: a string, or a list of
     * strings or {message} maps) and request id, so a 401/403 can be told apart without a deploy. A body that is not
     * Shopify's JSON (a proxy or CDN page) is never echoed; the message says what kind of answer it was.
     */
    private static String refusalMessage(int statusCode, String bodyText, String contentType, String requestId) {
        String head = "Shopify GraphQL request failed with HTTP ${statusCode}".toString()
        String idPart = requestId ? " (Shopify request id ${clip(requestId, 80)})".toString() : ""
        Object parsed = null
        try { parsed = bodyText ? new JsonSlurper().parseText(bodyText) : null } catch (Exception ignored) { }
        if (parsed instanceof Map) {
            Object errors = ((Map) parsed).get("errors")
            List<String> reasons = (errors instanceof List ? (List) errors : [errors])
                    .collect { Object e -> normalize(e instanceof Map ? ((Map) e).get("message") : e) }
                    .findAll { String m -> m }
            String reason = clip(reasons.join("; "), 300)
            return reason ? "${head}: ${reason}${idPart}".toString() : "${head}.${idPart}".toString()
        }
        if (bodyText) {
            String kind = normalize(contentType?.split(";")?.first()) ?: "no content type"
            return "${head}; the answer was not Shopify's JSON (${kind}), so something between Darpan and Shopify may have refused it.${idPart}".toString()
        }
        return "${head}.${idPart}".toString()
    }

    private static String headerValue(Object headers, String name) {
        if (!(headers instanceof Map)) return null
        Map.Entry entry = ((Map) headers).find { k, v -> k?.toString()?.equalsIgnoreCase(name) } as Map.Entry
        return normalize(entry?.value)
    }

    /** One line, control characters dropped, at most {@code max} characters. */
    private static String clip(String text, int max) {
        String flat = (text ?: "").replaceAll(/[\p{Cntrl}\s]+/, " ").trim()
        return flat.length() <= max ? flat : flat.substring(0, max) + "…"
    }

    private static Map<String, Object> safeFailure(String message, boolean retryable) {
        return [
            ok       : false,
            errors   : [message],
            retryable: retryable,
        ]
    }
}
