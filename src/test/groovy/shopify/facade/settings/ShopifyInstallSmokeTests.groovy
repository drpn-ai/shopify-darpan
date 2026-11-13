package shopify.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-275 — the install round trip: begin#ShopifyInstall, then the public callback. What matters is
 * what the callback refuses (forged signature, another store, replay, missing scopes) and that an
 * install never touches a connection made by pasting a token.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyInstallSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "KREWE"
    private static final String VIEWER = "GORJANA"
    private static final String SHOP = "acme-test.myshopify.com"
    private static final String APP_ID = "SHOPIFY_APP_TEST"
    private static final String SECRET = "test-client-secret"
    private static final String SCOPES = "read_orders,read_returns,read_all_orders"
    private static final String APP_BASE = "https://app.example.com"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-install-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
        [TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID].each {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: it, description: it,
                    groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
        grant(OWNER, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(VIEWER, "Viewer tenant")
        grant(VIEWER, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        ec.entity.makeValue("darpan.shopify.ShopifyApp").setAll([
                shopifyAppId: APP_ID, distribution: "CUSTOM", shopDomain: SHOP, clientId: "test-client-id",
                clientSecret: SECRET, scopes: SCOPES, isActive: "Y"]).createOrUpdate()
        ec.entity.makeValue("darpan.shopify.ShopifyAuthConfig").setAll([
                shopifyAuthConfigId: "PASTED_TOKEN_CONFIG", companyUserGroupId: OWNER, shopApiUrl: "https://${SHOP}".toString(),
                apiVersion: "2026-01", accessToken: "shpat_pasted", isActive: "Y"]).createOrUpdate()
        System.setProperty(TenantNotificationSupport.APP_BASE_URL_PROPERTY, APP_BASE)
        System.setProperty(ShopifyInstallSupport.REDIRECT_URI_PROPERTY, "https://api.example.com/apps/shopify/installCallback")
    }

    @AfterAll
    void cleanup() {
        System.clearProperty(TenantNotificationSupport.APP_BASE_URL_PROPERTY)
        System.clearProperty(ShopifyInstallSupport.REDIRECT_URI_PROPERTY)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void asOwner() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        ShopifyOAuthClient.setPostHook { String url, Map form ->
            [status: 200, body: [access_token: "shpat_new_${form.code}".toString(), scope: SCOPES, expires_in: 3600,
                                 refresh_token: "shprt_new", refresh_token_expires_in: 7776000]]
        }
    }

    @AfterEach
    void resetHook() { ShopifyOAuthClient.resetPostHook() }

    @Test
    void installStoresItsOwnConnectionAndLeavesThePastedOneAlone() {
        String state = beginState("acme-test")
        String redirect = ShopifyInstallSupport.handleCallback(ec, signed([code: "c1", shop: SHOP, state: state]))
        assertEquals("${APP_BASE}/settings?shopify=connected".toString(), redirect)

        def installed = installedRow()
        assertNotNull(installed)
        assertEquals(OWNER, installed.companyUserGroupId)
        assertEquals(TEST_USER_ID, installed.createdByUserId)
        assertEquals("shpat_new_c1", installed.accessToken)
        assertEquals("shprt_new", installed.refreshToken)
        assertNotNull(installed.accessTokenExpiresDate)
        assertEquals("N", installed.canReadOrders, "a new connection is enabled for nothing until someone switches a run")

        def pasted = ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", "PASTED_TOKEN_CONFIG").useCache(false).one()
        assertEquals("shpat_pasted", pasted.accessToken)
        assertNull(pasted.shopifyAppId)

        // A re-install updates the same row instead of adding a second connection.
        String again = beginState(SHOP)
        ShopifyInstallSupport.handleCallback(ec, signed([code: "c2", shop: SHOP, state: again]))
        List rows = ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAppId", APP_ID).useCache(false).list()
        assertEquals(1, rows.size())
        assertEquals("shpat_new_c2", rows.first().accessToken)
    }

    @Test
    void aForgedSignatureIsRefusedAndDoesNotBurnTheState() {
        String state = beginState(SHOP)
        Map forged = signed([code: "c3", shop: SHOP, state: state])
        forged.hmac = "0" * 64
        assertTrue(ShopifyInstallSupport.handleCallback(ec, forged).endsWith("reason=bad_signature"))
        // The real callback still works: someone without the secret cannot cancel a pending install.
        assertTrue(ShopifyInstallSupport.handleCallback(ec, signed([code: "c3", shop: SHOP, state: state])).endsWith("shopify=connected"))
    }

    @Test
    void aReplayedCallbackIsRefused() {
        String state = beginState(SHOP)
        Map params = signed([code: "c4", shop: SHOP, state: state])
        assertTrue(ShopifyInstallSupport.handleCallback(ec, params).endsWith("shopify=connected"))
        assertTrue(ShopifyInstallSupport.handleCallback(ec, params).endsWith("reason=already_used"))
    }

    @Test
    void aCodeForAnotherStoreIsRefused() {
        String state = beginState(SHOP)
        String redirect = ShopifyInstallSupport.handleCallback(ec, signed([code: "c5", shop: "other-store.myshopify.com", state: state]))
        assertTrue(redirect.endsWith("reason=shop_mismatch"))
    }

    @Test
    void missingScopesAreRefusedAndNothingIsStored() {
        ShopifyOAuthClient.setPostHook { String url, Map form ->
            [status: 200, body: [access_token: "shpat_short", scope: "read_orders", expires_in: 3600,
                                 refresh_token: "shprt_short", refresh_token_expires_in: 7776000]]
        }
        String state = beginState(SHOP)
        assertTrue(ShopifyInstallSupport.handleCallback(ec, signed([code: "c6", shop: SHOP, state: state])).endsWith("reason=missing_scopes"))
        assertFalse(ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("accessToken", "shpat_short").useCache(false).list() as boolean)
    }

    @Test
    void unknownStoreAndViewOnlyTenantCannotStartAnInstall() {
        Map<String, Object> unknown = beginInstall("not-set-up.myshopify.com")
        assertFalse(unknown.ok as boolean)
        assertTrue((unknown.errors as List).join(" ").contains("No Darpan Shopify app"))

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, VIEWER)
        Map<String, Object> viewOnly = beginInstall(SHOP)
        assertFalse(viewOnly.ok as boolean)
        assertNull(viewOnly.authorizeUrl)
    }

    @Test
    void theAuthorizeUrlNeverCarriesTheSecret() {
        Map<String, Object> result = beginInstall(SHOP)
        assertTrue(result.ok as boolean, "${result.errors}")
        assertTrue((result.authorizeUrl as String).startsWith("https://${SHOP}/admin/oauth/authorize?"))
        assertFalse(result.toString().contains(SECRET))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, Object> beginInstall(String shopDomain) {
        return (Map<String, Object>) ec.service.sync().name("facade.ShopifyFacadeServices.begin#ShopifyInstall")
                .parameters([shopDomain: shopDomain]).disableAuthz().call()
    }

    private String beginState(String shopDomain) {
        ec.message.clearErrors()
        Map<String, Object> result = beginInstall(shopDomain)
        assertTrue(result.ok as boolean, "${result.errors}")
        String url = result.authorizeUrl as String
        return URLDecoder.decode((url =~ /state=([^&]+)/)[0][1] as String, "UTF-8")
    }

    private static Map<String, Object> signed(Map<String, Object> params) {
        Map<String, Object> withTime = params + [timestamp: "1763030000"]
        String message = withTime.keySet().sort().collect { "${it}=${withTime[it]}" }.join("&")
        return withTime + [hmac: ShopifyInstallSupport.hmacHex(SECRET, message)]
    }

    private def installedRow() {
        return ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAppId", APP_ID).useCache(false).one()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId: tenantId, description: label, groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE,
        ]).createOrUpdate()
    }

    private void grant(String tenantId, String permissionGroupId) {
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: TEST_USER_ID, permissionUserGroupId: permissionGroupId, fromDate: TEST_FROM_DATE,
        ]).createOrUpdate()
    }
}
