package shopify.privacy

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.facade.settings.ShopifyInstallSupport
import shopify.facade.settings.ShopifyTokenSupport

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-273 — the receiver for Shopify's webhooks to Darpan's own apps: app/uninstalled and the three mandatory
 * privacy topics. Public by necessity (POST /apps/shopify/webhook, no session); Shopify's signature is the only thing
 * it trusts.
 *
 * <h3>Signature</h3>
 * X-Shopify-Hmac-Sha256 is base64(HMAC-SHA256(client secret, raw body)). The secret is the one of the Darpan app the
 * store has installed: the CUSTOM app registered for X-Shopify-Shop-Domain, or the PUBLIC app. Each candidate is tried
 * with a constant-time compare; no match is a 401 and nothing runs. The shop in the header is not trusted until a
 * signature made with that store's app's secret matches.
 *
 * <h3>Answers</h3>
 * 200 once handled (or for a topic Darpan ignores), 401 for a bad or missing signature, 400 for a body that is not
 * JSON, 500 when handling failed so Shopify retries. Every action is idempotent, so a retried or duplicated delivery
 * (same X-Shopify-Webhook-Id) does no harm.
 */
class ShopifyWebhookSupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyWebhookSupport)

    static final String TOPIC_UNINSTALLED = "app/uninstalled"
    static final String TOPIC_DATA_REQUEST = "customers/data_request"
    static final String TOPIC_CUSTOMER_REDACT = "customers/redact"
    static final String TOPIC_SHOP_REDACT = "shop/redact"

    /** {@code [status, message]}. headers: topic, hmac, shopDomain, webhookId (already read from the request). */
    static Map<String, Object> handle(def ec, Map<String, String> headers, String body) {
        String shop = normalize(headers?.shopDomain)?.toLowerCase(Locale.ROOT)
        String topic = normalize(headers?.topic)
        String webhookId = normalize(headers?.webhookId)
        if (!ShopifyInstallSupport.isValidShopDomain(shop) || body == null) return [status: 401, message: "unauthorized"]

        def app = verifiedApp(ec, shop, normalize(headers?.hmac), body)
        if (app == null) {
            logger.warn("Shopify webhook {} for {} refused: signature did not match any Darpan app for the store", topic, shop)
            return [status: 401, message: "unauthorized"]
        }

        Map payload
        try {
            Object parsed = new JsonSlurper().parseText(body)
            payload = parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            payload = null
        }
        if (payload == null) return [status: 400, message: "bad request"]
        // The header is not signed; the body is. A PUBLIC app's one secret signs every store's webhooks, so a body
        // captured from one store could be re-sent with another store in the header. Act only when the signed body
        // names the same store.
        String signedShop = normalize(payload.shop_domain ?: payload.myshopify_domain)?.toLowerCase(Locale.ROOT)
        if (signedShop != shop) {
            logger.warn("Shopify webhook {} refused: the signed body names a different store than the header", topic)
            return [status: 400, message: "bad request"]
        }

        // The topic header is not signed either: a signed customers/data_request body re-sent as shop/redact must not
        // erase anything. Each topic acts only on a body of its own shape.
        if (!shapeMatches(topic, payload)) {
            logger.warn("Shopify webhook {} for {} refused: the signed body is not a {} payload", topic, shop, topic)
            return [status: 400, message: "bad request"]
        }

        try {
            switch (topic) {
                case TOPIC_UNINSTALLED:
                    // An app an operator has switched off acts on nothing but the privacy topics.
                    if (normalize(app.isActive) == "N") break
                    int cleared = clearAppConnections(ec, shop, normalize(app.shopifyAppId))
                    logger.info("Shopify app {} uninstalled from {} (webhook {}): {} connection(s) cleared", app.shopifyAppId, shop, webhookId, cleared)
                    break
                case TOPIC_DATA_REQUEST:
                    Map report = (Map) call(ec, "report#CustomerData", [shopDomain: shop, orderIds: payload.orders_requested]).report
                    Map tenants = (Map) (report?.tenants ?: [:])
                    // Shopify expects the store to receive this data from the app; the operator sends it. Log counts only.
                    logger.warn("Shopify customer data request for {} (webhook {}): records held per tenant {} — an operator must send them to the store",
                            shop, webhookId, tenants.collectEntries { k, v -> [k, (v as List).size()] })
                    break
                case TOPIC_CUSTOMER_REDACT:
                    Map redacted = (Map) call(ec, "redact#Customer", [shopDomain: shop, orderIds: payload.orders_to_redact]).result
                    logger.info("Shopify customer redact for {} (webhook {}): {}", shop, webhookId, redacted?.deletedByTenant)
                    break
                case TOPIC_SHOP_REDACT:
                    // Only tenants connected through THIS app: a pasted-token connection to the same store is not the
                    // app's to break (the store uninstalled the app, not Darpan).
                    Map result = (Map) call(ec, "redact#Shop", [shopDomain: shop, shopifyAppId: normalize(app.shopifyAppId)]).result
                    logger.info("Shopify shop redact for {} (webhook {}): cleared {}, deleted {}, needs review {}", shop, webhookId,
                            result?.connectionsCleared, result?.deletedByTenant, result?.needsReview)
                    break
                default:
                    logger.info("Shopify webhook topic {} for {} ignored", topic, shop)
            }
        } catch (Throwable t) {
            logger.error("Shopify webhook {} for {} failed: {}", topic, shop, t.class.simpleName)
            return [status: 500, message: "error"]
        }
        return [status: 200, message: "ok"]
    }

    /** Shopify's payload for each topic, so an unsigned topic header cannot turn one request into another. */
    static boolean shapeMatches(String topic, Map payload) {
        switch (topic) {
            case TOPIC_UNINSTALLED: return payload.myshopify_domain != null && payload.id != null
            case TOPIC_DATA_REQUEST: return payload.data_request instanceof Map && payload.customer instanceof Map
            case TOPIC_CUSTOMER_REDACT: return payload.customer instanceof Map && payload.orders_to_redact != null && payload.data_request == null
            case TOPIC_SHOP_REDACT: return payload.shop_id != null && payload.customer == null && payload.data_request == null
            default: return true // ignored topics act on nothing
        }
    }

    /** The Darpan app whose secret signed this body, or null. Only apps that can be installed on this store are tried. */
    static def verifiedApp(def ec, String shop, String givenHmac, String body) {
        if (!givenHmac) return null
        List apps = (TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.APP_ENTITY,
                        "webhook signature check: platform-owned Darpan apps that can be installed on this store")
                ?.useCache(false)?.list() ?: []) as List
        // Inactive apps are tried too: shop/redact arrives 48 hours after an uninstall, often after an operator has
        // switched the app off, and the erase is still owed.
        List candidates = apps.findAll {
            String distribution = normalize(it.distribution)
            distribution == ShopifyInstallSupport.DISTRIBUTION_PUBLIC ||
                    (distribution == ShopifyInstallSupport.DISTRIBUTION_CUSTOM && normalize(it.shopDomain)?.toLowerCase(Locale.ROOT) == shop)
        }
        byte[] given = givenHmac.getBytes(StandardCharsets.US_ASCII)
        return candidates.find { def app ->
            String secret = normalize(app.clientSecret)
            secret && MessageDigest.isEqual(signature(secret, body).getBytes(StandardCharsets.US_ASCII), given)
        }
    }

    static String signature(String secret, String body) {
        Mac mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.encoder.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))
    }

    /**
     * app/uninstalled: the store's connections made by THIS app lose their tokens and go inactive, so nothing keeps
     * calling Shopify with a dead token. Pasted-token connections and other apps' connections are not touched.
     * Kept (not deleted): the store may install again, and shop/redact 48 hours later erases what must go.
     */
    static int clearAppConnections(def ec, String shop, String appId) {
        List configs = (TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                        "app/uninstalled: connections made by the signing app; the store and app are verified by the signature")
                ?.condition("shopifyAppId", appId)?.useCache(false)?.list() ?: []) as List
        List thisStore = configs.findAll { ShopifyTokenSupport.shopDomainOf(it.shopApiUrl) == shop }
        thisStore.each { def config ->
            config.setAll([accessToken: null, refreshToken: null, accessTokenExpiresDate: null, refreshTokenExpiresDate: null, isActive: "N"])
            ShopifyInstallSupport.withAuthzLifted(ec) { -> config.update() }
        }
        return thisStore.size()
    }

    /** Runs after the signature check, with no session: the same one authz lift the callback and refresh use. */
    private static Map call(def ec, String verbNoun, Map params) {
        Map out = (Map) ShopifyInstallSupport.withAuthzLifted(ec) { ->
            ec.service.sync().name("privacy.ShopifyPrivacyRequestServices.${verbNoun}").parameters(params).call()
        }
        if (ec.message.hasError()) throw new IllegalStateException(ec.message.errorsString)
        return out ?: [:]
    }
}
