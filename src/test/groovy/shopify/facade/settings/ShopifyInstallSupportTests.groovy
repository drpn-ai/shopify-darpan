package shopify.facade.settings

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-275 — the pure parts of the install: store domain, HMAC, scopes, token response. No Moqui. */
class ShopifyInstallSupportTests {

    // shopify.dev's published example (secret "hush").
    private static final Map<String, Object> DOC_PARAMS = [
            code     : "0907a61c0c8d55e99db179b68161bc00",
            hmac     : "700e2dadb827fcc8609e9d5ce208b2e9cdaab9df07390d2cbca10d7c328fc4bf",
            shop     : "some-shop.myshopify.com",
            state    : "0.6784241404160823",
            timestamp: "1337178173",
    ]

    @Test
    void hmacMatchesShopifysPublishedExampleAndAnyChangeBreaksIt() {
        assertTrue(ShopifyInstallSupport.verifyHmac(DOC_PARAMS, "hush"))
        assertTrue(ShopifyInstallSupport.verifyHmac(DOC_PARAMS.collectEntries { k, v -> [k, [v] as String[]] }, "hush"),
                "servlet parameter maps hold String[]")
        assertFalse(ShopifyInstallSupport.verifyHmac(DOC_PARAMS, "wrong-secret"))
        assertFalse(ShopifyInstallSupport.verifyHmac(DOC_PARAMS + [shop: "other-shop.myshopify.com"], "hush"),
                "a changed shop must fail, or a code could be stored against another store")
        assertFalse(ShopifyInstallSupport.verifyHmac(DOC_PARAMS + [extra: "x"], "hush"))
        assertFalse(ShopifyInstallSupport.verifyHmac(DOC_PARAMS - [hmac: DOC_PARAMS.hmac], "hush"))
        assertFalse(ShopifyInstallSupport.verifyHmac(DOC_PARAMS, null))
    }

    @Test
    void shopDomainIsStrictOnTheCallbackAndForgivingOnInput() {
        assertTrue(ShopifyInstallSupport.isValidShopDomain("acme-1.myshopify.com"))
        ["acme.myshopify.com.evil.io", "evil.io/acme.myshopify.com", "-acme.myshopify.com", "acme.shopify.com",
         "https://acme.myshopify.com", "acme.myshopify.com/", ""].each {
            assertFalse(ShopifyInstallSupport.isValidShopDomain(it), it)
        }
        assertEquals("acme.myshopify.com", ShopifyInstallSupport.normalizeShopDomain("acme"))
        assertEquals("acme.myshopify.com", ShopifyInstallSupport.normalizeShopDomain(" https://ACME.myshopify.com/admin/orders "))
        assertNull(ShopifyInstallSupport.normalizeShopDomain("acme.com"))
        assertNull(ShopifyInstallSupport.normalizeShopDomain("acme.myshopify.com.evil.io"))
    }

    @Test
    void writeScopeCoversItsReadScope() {
        assertTrue(ShopifyInstallSupport.scopesCover("read_orders,read_returns,read_all_orders", "read_orders, read_all_orders"))
        assertTrue(ShopifyInstallSupport.scopesCover("write_orders,read_all_orders", "read_orders,read_all_orders"))
        assertFalse(ShopifyInstallSupport.scopesCover("read_orders", "read_orders,read_all_orders"))
        assertFalse(ShopifyInstallSupport.scopesCover(null, "read_orders"))
    }

    @Test
    void authorizeUrlGoesToTheStoreWithEveryParameterEncoded() {
        String url = ShopifyInstallSupport.buildAuthorizeUrl("acme.myshopify.com", "cid", "read_orders,read_returns",
                "https://api.example.com/apps/shopify/installCallback", "st&te")
        assertTrue(url.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
        assertTrue(url.contains("client_id=cid"))
        assertTrue(url.contains("scope=read_orders%2Cread_returns"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.example.com%2Fapps%2Fshopify%2FinstallCallback"))
        assertTrue(url.contains("state=st%26te"))
    }

    @Test
    void tokenResponseNeedsAnExpiringTokenAndFlagsARefusedRefreshToken() {
        Timestamp now = Timestamp.valueOf("2026-11-13 12:00:00")
        Map ok = ShopifyOAuthClient.readTokenResponse([status: 200, body: [
                access_token: "shpat_x", scope: "read_orders", expires_in: 3600,
                refresh_token: "shprt_y", refresh_token_expires_in: 7776000]], now)
        assertTrue(ok.ok as boolean)
        assertEquals(Timestamp.valueOf("2026-11-13 13:00:00"), ok.accessTokenExpiresDate)
        assertEquals(Timestamp.valueOf("2027-02-11 12:00:00"), ok.refreshTokenExpiresDate)

        Map nonExpiring = ShopifyOAuthClient.readTokenResponse([status: 200, body: [access_token: "shpat_x", scope: "read_orders"]], now)
        assertFalse(nonExpiring.ok as boolean)
        assertEquals("NOT_EXPIRING", nonExpiring.reason)

        Map refused = ShopifyOAuthClient.readTokenResponse([status: 401, body: [error: "invalid_request"]], now)
        assertFalse(refused.ok as boolean)
        assertTrue(refused.invalidGrant as boolean)

        Map outage = ShopifyOAuthClient.readTokenResponse([status: 503, body: [:]], now)
        assertFalse(outage.invalidGrant as boolean, "a Shopify outage must not throw a store's tokens away")
    }
}
