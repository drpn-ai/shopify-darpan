package shopify.facade.settings

import darpan.facade.common.OutboundHttpPolicy
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-275 — the two calls to Shopify's token endpoint (https://{shop}/admin/oauth/access_token):
 * exchanging an install's code, and refreshing an expiring offline token. Both ask for expiring
 * tokens: a 1-hour access token plus a single-use 90-day refresh token.
 *
 * <p>Never puts the code, a token or the client secret in a log line or a returned reason: an HTTP
 * client error can echo the request, so failures are reported by exception class or HTTP status only.</p>
 */
class ShopifyOAuthClient {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyOAuthClient)

    private static final List<String> OUTBOUND_HOST_SUFFIXES = [".myshopify.com"]

    /** Test seam: replaces the HTTP POST. Called with (url, formParams) and returns [status, body]. */
    private static Closure postHook = null

    static void setPostHook(Closure hook) { postHook = hook }

    static void resetPostHook() { postHook = null }

    static Map<String, Object> exchangeCode(def ec, String shopDomain, String clientId, String clientSecret, String code) {
        return requestTokens(ec, shopDomain, [client_id: clientId, client_secret: clientSecret, code: code, expiring: "1"])
    }

    static Map<String, Object> refresh(def ec, String shopDomain, String clientId, String clientSecret, String refreshToken) {
        return requestTokens(ec, shopDomain, [client_id    : clientId, client_secret: clientSecret,
                                              grant_type   : "refresh_token", refresh_token: refreshToken])
    }

    /**
     * {@code [ok, accessToken, refreshToken, grantedScopes, accessTokenExpiresDate, refreshTokenExpiresDate]}
     * or {@code [ok: false, reason, invalidGrant]}. invalidGrant means Shopify refused the refresh token
     * itself (401/400 invalid_request), so the store has to be installed again.
     */
    static Map<String, Object> requestTokens(def ec, String shopDomain, Map<String, String> form) {
        if (!ShopifyInstallSupport.isValidShopDomain(shopDomain)) return [ok: false, reason: "INVALID_SHOP"]
        if (!form.client_id || !form.client_secret) return [ok: false, reason: "APP_NOT_CONFIGURED"]
        String url = "https://${shopDomain}/admin/oauth/access_token".toString()
        Map<String, Object> response
        try {
            response = postHook != null ? (Map<String, Object>) postHook.call(url, form) : postForm(url, form)
        } catch (Throwable t) {
            logger.warn("Shopify token request to {} failed: {}", shopDomain, t.class.simpleName)
            return [ok: false, reason: "REQUEST_FAILED"]
        }
        return readTokenResponse(response, ec.user.nowTimestamp)
    }

    /** Pure, so the field rules are unit-testable. */
    static Map<String, Object> readTokenResponse(Map<String, Object> response, Timestamp now) {
        int status = (response?.status ?: 0) as int
        Map body = response?.body instanceof Map ? (Map) response.body : [:]
        if (status < 200 || status >= 300) {
            boolean invalidGrant = (status == 401 || status == 400) &&
                    (normalize(body.error) in ["invalid_request", "invalid_grant", "invalid_token"])
            return [ok: false, reason: "HTTP_${status}".toString(), invalidGrant: invalidGrant]
        }
        String accessToken = normalize(body.access_token)
        if (!accessToken) return [ok: false, reason: "NO_ACCESS_TOKEN"]
        String refreshToken = normalize(body.refresh_token)
        // Asked for expiring=1: a response without a refresh token or an expiry is not what was ordered,
        // and storing it would leave a token Darpan thinks never expires.
        if (!refreshToken || body.expires_in == null) return [ok: false, reason: "NOT_EXPIRING"]
        return [
                ok                     : true,
                accessToken            : accessToken,
                refreshToken           : refreshToken,
                grantedScopes          : normalize(body.scope),
                accessTokenExpiresDate : plusSeconds(now, body.expires_in),
                refreshTokenExpiresDate: body.refresh_token_expires_in != null ? plusSeconds(now, body.refresh_token_expires_in) : null,
        ]
    }

    private static Timestamp plusSeconds(Timestamp now, Object seconds) {
        return new Timestamp(now.time + (new BigDecimal(seconds.toString()).longValue() * 1000L))
    }

    private static Map<String, Object> postForm(String url, Map<String, String> form) {
        def check = OutboundHttpPolicy.validate(url, OUTBOUND_HOST_SUFFIXES)
        if (!check.ok) throw new IllegalStateException("Shopify token endpoint blocked by outbound policy")
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ShopifyInstallSupport.formEncode(form)))
                .build()
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Object parsed = null
        try {
            parsed = new JsonSlurper().parseText(response.body() ?: "{}")
        } catch (Exception ignored) {
            parsed = [:]
        }
        return [status: response.statusCode(), body: parsed instanceof Map ? parsed : [:]]
    }
}
