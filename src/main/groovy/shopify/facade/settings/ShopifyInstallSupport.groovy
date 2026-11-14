package shopify.facade.settings

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.graphql.ShopifySourceCatalog

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-275 — installing a Darpan-owned Shopify app on a store (authorization code grant), so a store
 * connects without anyone pasting a token another app issued.
 *
 * <p>Flow: an authenticated tenant user calls begin#ShopifyInstall with the store's domain; Darpan
 * mints a single-use state bound to that tenant, user, app and store and returns Shopify's authorize
 * URL. The merchant approves in Shopify, and Shopify redirects the browser to the public callback
 * ({@link #CALLBACK_PATH}) with code, hmac, shop, state and timestamp.</p>
 *
 * <h3>Why the callback trusts nothing but the state and the HMAC</h3>
 * The callback carries no Darpan session. The state row says which tenant the store is for; the HMAC
 * (keyed by the app's client secret) proves Shopify sent the parameters. The HMAC is checked BEFORE the
 * state is consumed, so someone without the secret cannot burn a pending install. The shop in the
 * callback must equal the shop the state was minted for, so a code for one store is never stored as
 * another.
 *
 * <p>Each install creates (or, on a re-install of the same app on the same store for the same tenant,
 * updates) its OWN ShopifyAuthConfig row. Rows made by pasting a token are never touched, and the new
 * row starts with no endpoint enabled, so no run uses it until someone switches one over.</p>
 */
class ShopifyInstallSupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyInstallSupport)

    static final String CALLBACK_PATH = "/apps/shopify/installCallback"
    static final String REDIRECT_URI_ENV = "DARPAN_SHOPIFY_REDIRECT_URI"
    static final String REDIRECT_URI_PROPERTY = "darpan.shopify.redirectUri"

    static final String APP_ENTITY = "darpan.shopify.ShopifyApp"
    static final String STATE_ENTITY = "darpan.shopify.ShopifyInstallState"
    static final String CONFIG_ENTITY = "darpan.shopify.ShopifyAuthConfig"

    static final String DISTRIBUTION_CUSTOM = "CUSTOM"
    static final String DISTRIBUTION_PUBLIC = "PUBLIC"

    /** Long enough to approve in Shopify, short enough that a leaked state is worthless. */
    static final int STATE_TTL_MINUTES = 10

    /** shopify.dev: anchored at both ends, so a suffixed host (x.myshopify.com.evil.io) fails. */
    static final Pattern SHOP_DOMAIN = ~/^[a-zA-Z0-9][a-zA-Z0-9\-]*\.myshopify\.com$/

    private static final SecureRandom RANDOM = new SecureRandom()

    // ---- store domain --------------------------------------------------------------------------

    /** What a person types (handle, domain or admin URL) as name.myshopify.com, or null if it is not one. */
    static String normalizeShopDomain(Object raw) {
        String value = normalize(raw)?.toLowerCase(Locale.ROOT)
        if (!value) return null
        value = value.replaceFirst(/^https?:\/\//, "")
        int slash = value.indexOf("/")
        if (slash >= 0) value = value.substring(0, slash)
        if (!value.contains(".")) value = value + ".myshopify.com"
        return isValidShopDomain(value) ? value : null
    }

    /** Strict: exactly what Shopify sends, no normalising. Used on the callback's shop parameter. */
    static boolean isValidShopDomain(Object raw) {
        String value = raw?.toString()
        return value != null && SHOP_DOMAIN.matcher(value).matches()
    }

    // ---- apps ----------------------------------------------------------------------------------

    /**
     * The active CUSTOM app made for this store AND this tenant, or null. ShopifyApp is platform-owned, so it is
     * read unscoped with an explicit tenant condition. The PUBLIC app is never installed from here: a public
     * install starts in Shopify and needs its own way to tie the store to a tenant (DAR-BE-197 Phase 2).
     */
    static def findAppForShop(def ec, String shopDomain, String tenantId) {
        if (!shopDomain || !tenantId) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, APP_ENTITY,
                        "platform-owned Darpan Shopify app chosen by store domain, pinned to the requesting tenant")
                ?.condition("distribution", DISTRIBUTION_CUSTOM)
                ?.condition("shopDomain", shopDomain)
                ?.condition("tenantUserGroupId", tenantId)
                ?.condition("isActive", "Y")
                ?.useCache(false)?.list()?.find { true }
    }

    static def findApp(def ec, Object rawAppId) {
        String appId = normalize(rawAppId)
        if (!appId) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, APP_ENTITY,
                        "platform-owned Darpan Shopify app read by id; holds no tenant data")
                ?.condition("shopifyAppId", appId)
                ?.useCache(false)?.one()
    }

    // ---- begin ---------------------------------------------------------------------------------

    /** The callback URL registered in the app. Explicit setting first, else the app base URL. */
    static String resolveRedirectUri(def ec) {
        String explicit = System.getenv(REDIRECT_URI_ENV)?.trim() ?:
                ec?.resource?.properties?.get(REDIRECT_URI_PROPERTY)?.toString()?.trim() ?:
                System.getProperty(REDIRECT_URI_PROPERTY)?.trim()
        if (explicit) return explicit
        String appBaseUrl = TenantNotificationSupport.resolveAppBaseUrl(ec)
        return appBaseUrl ? "${appBaseUrl}${CALLBACK_PATH}".toString() : null
    }

    /**
     * Starts an install for the active tenant. Adds an error to ec and returns null when it cannot;
     * otherwise returns the Shopify authorize URL. Called only from begin#ShopifyInstall, after the
     * tenant write-access check — that is where the tenant binding comes from.
     */
    static String beginInstall(def ec, String tenantId, String userId, Object rawShopDomain) {
        String shopDomain = normalizeShopDomain(rawShopDomain)
        if (!shopDomain) {
            ec.message.addError("Enter the store's address, such as your-store.myshopify.com.")
            return null
        }
        def app = findAppForShop(ec, shopDomain, tenantId)
        if (app == null || !normalize(app.clientId) || !normalize(app.clientSecret)) {
            ec.message.addError("No Darpan Shopify app is set up for ${shopDomain} in this tenant.")
            return null
        }
        String redirectUri = resolveRedirectUri(ec)
        if (!redirectUri) {
            ec.message.addError("Shopify cannot be connected until an app base URL or ${REDIRECT_URI_PROPERTY} is configured.")
            return null
        }
        String stateToken = mintState(ec, tenantId, userId, normalize(app.shopifyAppId), shopDomain)
        return buildAuthorizeUrl(shopDomain, normalize(app.clientId), normalize(app.scopes), redirectUri, stateToken)
    }

    static String mintState(def ec, String tenantId, String userId, String appId, String shopDomain) {
        byte[] entropy = new byte[32]
        RANDOM.nextBytes(entropy)
        String stateToken = Base64.urlEncoder.withoutPadding().encodeToString(entropy)
        Timestamp now = ec.user.nowTimestamp
        def row = ec.entity.makeValue(STATE_ENTITY)
        row.set("stateToken", stateToken)
        row.set("companyUserGroupId", tenantId)
        row.set("initiatedByUserId", userId)
        row.set("shopifyAppId", appId)
        row.set("shopDomain", shopDomain)
        row.set("createdDate", now)
        row.set("expiresDate", new Timestamp(now.time + STATE_TTL_MINUTES * 60_000L))
        row.create()
        return stateToken
    }

    static String buildAuthorizeUrl(String shopDomain, String clientId, String scopes, String redirectUri, String stateToken) {
        Map<String, String> params = [client_id: clientId, scope: scopes, redirect_uri: redirectUri, state: stateToken]
        return "https://${shopDomain}/admin/oauth/authorize?${formEncode(params)}".toString()
    }

    // ---- state ---------------------------------------------------------------------------------

    /** Reads a state without consuming it. {@code [ok, reason, companyUserGroupId, initiatedByUserId, shopifyAppId, shopDomain]} */
    static Map<String, Object> peekState(def ec, Object rawStateToken) {
        String stateToken = normalize(rawStateToken)
        if (!stateToken) return [ok: false, reason: "MISSING_STATE"]
        def row = TenantScopedFinder.findGlobalUnscoped(ec, STATE_ENTITY,
                        "install state read keyed by an unguessable single-use token — the token IS the authorization")
                ?.condition("stateToken", stateToken)
                ?.useCache(false)?.one()
        if (row == null) return [ok: false, reason: "UNKNOWN_STATE"]
        if (row.consumedDate != null) return [ok: false, reason: "STATE_ALREADY_USED"]
        if (row.expiresDate != null && ((Timestamp) row.expiresDate).before((Timestamp) ec.user.nowTimestamp)) {
            return [ok: false, reason: "EXPIRED_STATE"]
        }
        return [ok                : true,
                companyUserGroupId: normalize(row.companyUserGroupId),
                initiatedByUserId : normalize(row.initiatedByUserId),
                shopifyAppId      : normalize(row.shopifyAppId),
                shopDomain        : normalize(row.shopDomain)]
    }

    /** Atomic single-use claim (WHERE consumedDate IS NULL): of two racing callbacks, only one wins. */
    static boolean consumeState(def ec, String stateToken) {
        long claimed = TenantScopedFinder.findGlobalUnscoped(ec, STATE_ENTITY,
                        "atomic single-use install state claim — conditional update where consumedDate is null")
                ?.condition("stateToken", stateToken)
                ?.condition("consumedDate", null)
                ?.updateAll([consumedDate: ec.user.nowTimestamp]) ?: 0
        return claimed > 0
    }

    // ---- HMAC ----------------------------------------------------------------------------------

    /**
     * shopify.dev: drop hmac, sort the rest by name, join as key=value with &, HMAC-SHA256 hex with the
     * client secret, compare in constant time. {@code signature} (an app-proxy parameter) is dropped too.
     */
    static boolean verifyHmac(Map params, String clientSecret) {
        String given = firstValue(params?.get("hmac"))
        if (!given || !clientSecret) return false
        String message = (params.keySet() as List<String>)
                .findAll { it != "hmac" && it != "signature" }
                .sort()
                .collect { String key -> "${key}=${firstValue(params.get(key)) ?: ''}" }
                .join("&")
        String expected = hmacHex(clientSecret, message)
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))
    }

    static String hmacHex(String secret, String message) {
        Mac mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /** A request parameter as one string: servlet maps hold String[]; tests pass plain strings. */
    static String firstValue(Object value) {
        if (value instanceof String[]) return ((String[]) value).length ? ((String[]) value)[0] : null
        if (value instanceof Collection) return ((Collection) value) ? ((Collection) value).first()?.toString() : null
        return value?.toString()
    }

    // ---- scopes --------------------------------------------------------------------------------

    /** True when the granted list covers every required scope; a write_x grant implies read_x. */
    static boolean scopesCover(Object granted, Object required) {
        Set<String> have = splitScopes(granted) as Set<String>
        return splitScopes(required).every { String scope ->
            have.contains(scope) || (scope.startsWith("read_") && have.contains("write_" + scope.substring(5)))
        }
    }

    static List<String> splitScopes(Object raw) {
        return (normalize(raw) ?: "").split(",").collect { it.trim() }.findAll { it } as List<String>
    }

    // ---- store the connection ------------------------------------------------------------------

    /** The newest dated API version Darpan supports (never "unstable"). */
    static String defaultApiVersion() {
        return ShopifySourceCatalog.SUPPORTED_API_VERSIONS.findAll { it ==~ /\d{4}-\d{2}/ }.max()
    }

    /**
     * Creates the install's own ShopifyAuthConfig row, or updates it on a re-install of the same app on
     * the same store for the same tenant. Never touches a row without this app id (a pasted token).
     */
    static String storeInstall(def ec, Map<String, Object> state, Map<String, Object> tokens) {
        String tenantId = (String) state.companyUserGroupId
        String appId = (String) state.shopifyAppId
        String shopApiUrl = "https://${state.shopDomain}".toString()
        Timestamp now = ec.user.nowTimestamp
        def existing = TenantScopedFinder.findGlobalUnscoped(ec, CONFIG_ENTITY,
                        "re-install lookup pinned to the redeemed state's tenant — explicit companyUserGroupId condition applied")
                ?.condition("companyUserGroupId", tenantId)
                ?.condition("shopifyAppId", appId)
                ?.condition("shopApiUrl", shopApiUrl)
                ?.useCache(false)?.one()
        Map<String, Object> tokenFields = [
                accessToken            : tokens.accessToken,
                refreshToken           : tokens.refreshToken,
                accessTokenExpiresDate : tokens.accessTokenExpiresDate,
                refreshTokenExpiresDate: tokens.refreshTokenExpiresDate,
                grantedScopes          : tokens.grantedScopes,
                isActive               : "Y",
        ]
        if (existing != null) {
            tokenFields.each { key, value -> existing.set(key, value) }
            existing.update()
            return normalize(existing.shopifyAuthConfigId)
        }
        def created = ec.entity.makeValue(CONFIG_ENTITY)
        ([
                description       : "Darpan app install: ${state.shopDomain}".toString(),
                companyUserGroupId: tenantId,
                createdByUserId   : state.initiatedByUserId,
                shopApiUrl        : shopApiUrl,
                apiVersion        : defaultApiVersion(),
                timeZone          : "UTC",
                shopifyAppId      : appId,
                installedDate     : now,
        ] + tokenFields).each { key, value -> created.set(key, value) }
        created.set("shopifyAuthConfigId", TenantScopedFinder.mintId(ec, CONFIG_ENTITY, "shopifyAuthConfigId"))
        created.create()
        return normalize(created.shopifyAuthConfigId)
    }

    // ---- callback ------------------------------------------------------------------------------

    /**
     * The whole callback as one testable unit. Always returns where to send the browser (null only when
     * no app base URL is configured); failures come back as short slugs, never Shopify's own text, so
     * nothing from the query string is reflected into the SPA. Never logs the code or a token.
     */
    static String handleCallback(def ec, Map params) {
        String appBaseUrl = TenantNotificationSupport.resolveAppBaseUrl(ec)
        if (!appBaseUrl) return null

        String stateToken = firstValue(params?.get("state"))
        Map<String, Object> state = peekState(ec, stateToken)
        if (state.ok != true) return failureUrl(appBaseUrl, stateReasonSlug((String) state.reason))

        String shop = firstValue(params.get("shop"))
        if (!isValidShopDomain(shop) || shop.toLowerCase(Locale.ROOT) != state.shopDomain) {
            return failureUrl(appBaseUrl, "shop_mismatch")
        }

        def app = findApp(ec, state.shopifyAppId)
        String clientSecret = normalize(app?.clientSecret)
        if (app == null || normalize(app.isActive) == "N" || !clientSecret) return failureUrl(appBaseUrl, "not_configured")
        // Re-checked here, not only at begin: the app may have been reassigned while the install was pending.
        if (normalize(app.distribution) != DISTRIBUTION_CUSTOM || normalize(app.tenantUserGroupId) != state.companyUserGroupId) {
            return failureUrl(appBaseUrl, "not_configured")
        }

        if (!verifyHmac(params, clientSecret)) {
            logger.warn("Shopify install callback for tenant {} failed the HMAC check", state.companyUserGroupId)
            return failureUrl(appBaseUrl, "bad_signature")
        }
        // Consumed only now: before the HMAC check, anyone holding the state could burn it.
        if (!consumeState(ec, stateToken)) return failureUrl(appBaseUrl, "already_used")

        String code = firstValue(params.get("code"))
        if (!code) return failureUrl(appBaseUrl, "missing_code")

        Map<String, Object> tokens = ShopifyOAuthClient.exchangeCode(ec, shop, normalize(app.clientId), clientSecret, code)
        if (tokens.ok != true) {
            logger.warn("Shopify install code exchange failed for tenant {}: {}", state.companyUserGroupId, tokens.reason)
            return failureUrl(appBaseUrl, "exchange_failed")
        }
        if (!scopesCover(tokens.grantedScopes, app.scopes)) {
            logger.warn("Shopify install for tenant {} was granted {} but the app needs {}",
                    state.companyUserGroupId, tokens.grantedScopes, app.scopes)
            return failureUrl(appBaseUrl, "missing_scopes")
        }
        // The callback has no Darpan session, so no artifact authz flows down to the entity write. Everything that
        // authorises it has been checked above (single-use state bound to this tenant and store, Shopify's HMAC, the
        // app's tenant, the granted scopes), and storeInstall writes only that tenant's row. Found by the live install
        // test (2026-11-13): ArtifactAuthorizationException on create of ShopifyAuthConfig.
        try {
            withAuthzLifted(ec) { -> storeInstall(ec, state, tokens) }
        } catch (Throwable t) {
            logger.error("Shopify install could not be stored for tenant {}: {}", state.companyUserGroupId, t.class.simpleName)
            return failureUrl(appBaseUrl, "store_failed")
        }
        return "${appBaseUrl}/settings?shopify=connected".toString()
    }

    /**
     * DAR-BE-275: the one place this component lifts artifact authz (DisableAuthzRatchetTest baselines it). Used only
     * for writes to a ShopifyAuthConfig row whose tenant was already established without a session: the install
     * callback (state + HMAC) and a token refresh (the caller resolved the config through its tenant/shared-access
     * check; a scheduled run has no user). Restores the previous state afterwards.
     */
    static Object withAuthzLifted(def ec, Closure work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private static String stateReasonSlug(String reason) {
        switch (reason) {
            case "EXPIRED_STATE": return "expired"
            case "STATE_ALREADY_USED": return "already_used"
            default: return "bad_state"
        }
    }

    private static String failureUrl(String appBaseUrl, String slug) {
        return "${appBaseUrl}/settings?shopify=error&reason=${URLEncoder.encode(slug, StandardCharsets.UTF_8.name())}".toString()
    }

    static String formEncode(Map<String, ?> params) {
        return params.findAll { it.value != null }.collect { key, value ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value.toString(), StandardCharsets.UTF_8.name())}"
        }.join("&")
    }
}
