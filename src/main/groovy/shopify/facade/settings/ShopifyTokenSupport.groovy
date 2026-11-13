package shopify.facade.settings

import darpan.facade.common.TenantScopedFinder
import org.moqui.Moqui
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-275 — a current access token for a connection made by a Darpan app install (expiring offline
 * tokens: 1-hour access token, single-use 90-day refresh token). A pasted token (no shopifyAppId) is
 * returned as stored, unchanged.
 *
 * <h3>Why refresh runs under a row lock in its own transaction</h3>
 * A refresh token works once: refreshing returns a new one and the old one dies. One tenant can run
 * up to five jobs at once, so two threads seeing an about-to-expire token would both refresh, and the
 * loser would store a dead refresh token and cut the store off. The refresh locks the config row
 * (SELECT ... FOR UPDATE) in a new transaction, re-reads it, and refreshes only if it is still due;
 * the waiting thread then finds the fresh token and uses it. The new transaction commits the new
 * tokens even if the caller's transaction later rolls back, since Shopify has already retired the
 * old refresh token.
 *
 * <p>Callers reach this only after the config was authorized for them (requireUsableAuthConfig or the
 * extract/lookup resolvers), so the read here is by id, unscoped.</p>
 */
class ShopifyTokenSupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyTokenSupport)

    /** Refresh this long before expiry, so a request never leaves with a token about to die. */
    static final long REFRESH_MARGIN_MILLIS = 5 * 60_000L
    /**
     * Must outlast a wait for the lock (another thread's refresh: up to 30 s of HTTP) plus this thread's own
     * refresh (up to 30 s). If it timed out after Shopify issued new tokens, the rollback would lose the only
     * live refresh token.
     */
    static final int REFRESH_TX_TIMEOUT_SECONDS = 180

    static boolean isAppIssued(def config) {
        return config != null && normalize(config.shopifyAppId) != null && config.accessTokenExpiresDate != null
    }

    static boolean needsRefresh(def config, Timestamp now) {
        if (!isAppIssued(config)) return false
        return ((Timestamp) config.accessTokenExpiresDate).time - REFRESH_MARGIN_MILLIS <= now.time
    }

    /** The token to send for this config: as stored for a pasted token, refreshed first when due for an app-issued one. */
    static String accessTokenFor(def ec, def config) {
        if (!isAppIssued(config)) return normalize(config?.accessToken)
        return currentAccessToken(ec, normalize(config.shopifyAuthConfigId))
    }

    /**
     * Re-reads the config (the caller's copy may be an hour old in a long bulk read) and refreshes when
     * due. Returns null when there is no usable token; the transport then reports it as not configured.
     */
    static String currentAccessToken(def ec, String configId) {
        def context = ec ?: Moqui.getExecutionContext()
        if (context == null || !configId) return null
        def config = findConfig(context, configId, false)
        if (config == null) return null
        if (!needsRefresh(config, context.user.nowTimestamp)) return normalize(config.accessToken)
        return (String) context.transaction.runRequireNew(REFRESH_TX_TIMEOUT_SECONDS,
                "Shopify token refresh failed", { -> refreshLocked(context, configId) })
    }

    private static String refreshLocked(def ec, String configId) {
        def locked = findConfig(ec, configId, true)
        if (locked == null) return null
        Timestamp now = ec.user.nowTimestamp
        // Another thread refreshed while this one waited for the lock: use its token.
        if (!needsRefresh(locked, now)) return normalize(locked.accessToken)

        def app = ShopifyInstallSupport.findApp(ec, locked.shopifyAppId)
        String shop = shopDomainOf(locked.shopApiUrl)
        String refreshToken = normalize(locked.refreshToken)
        if (app == null || !refreshToken || !shop) {
            logger.warn("Shopify config {} cannot be refreshed (app, refresh token or store missing)", configId)
            return stillValid(locked, now)
        }
        Map<String, Object> tokens = ShopifyOAuthClient.refresh(ec, shop, normalize(app.clientId), normalize(app.clientSecret), refreshToken)
        if (tokens.ok == true) {
            locked.set("accessToken", tokens.accessToken)
            locked.set("refreshToken", tokens.refreshToken)
            locked.set("accessTokenExpiresDate", tokens.accessTokenExpiresDate)
            locked.set("refreshTokenExpiresDate", tokens.refreshTokenExpiresDate)
            if (tokens.grantedScopes) locked.set("grantedScopes", tokens.grantedScopes)
            locked.update()
            return (String) tokens.accessToken
        }
        if (tokens.invalidGrant == true) {
            // Shopify refused the refresh token itself: the store has to install the app again. Clear
            // both so nothing keeps sending them; the connection reads as "no token" until then.
            logger.warn("Shopify refused the refresh token for config {}; the store must install the app again", configId)
            locked.set("accessToken", null)
            locked.set("refreshToken", null)
            locked.update()
            return null
        }
        // A timeout or 5xx: keep the tokens and use the current one while it still works; the next
        // request tries the refresh again.
        logger.warn("Shopify token refresh for config {} failed ({}); will retry", configId, tokens.reason)
        return stillValid(locked, now)
    }

    private static String stillValid(def config, Timestamp now) {
        Timestamp expires = (Timestamp) config.accessTokenExpiresDate
        return expires != null && expires.after(now) ? normalize(config.accessToken) : null
    }

    private static def findConfig(def ec, String configId, boolean forUpdate) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                "token refresh for a config the caller already resolved through its tenant/shared-access check")
                ?.condition("shopifyAuthConfigId", configId)
                ?.useCache(false)
        if (forUpdate) find = find?.forUpdate(true)
        return find?.one()
    }

    static String shopDomainOf(Object shopApiUrl) {
        String host = normalize(shopApiUrl)?.toLowerCase(Locale.ROOT)?.replaceFirst(/^https?:\/\//, "")
        if (!host) return null
        int slash = host.indexOf("/")
        if (slash >= 0) host = host.substring(0, slash)
        return ShopifyInstallSupport.isValidShopDomain(host) ? host : null
    }
}
