package shopify.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-275 — registering Darpan's Shopify apps: super admin only, the secret never comes back, one active app per store. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyAppAdminServicesSmokeTests {
    private static final String SECRET = "admin-test-secret"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-app-admin-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void clear() { ec.message.clearErrors() }

    @Test
    void aTenantUserCannotRegisterOrListApps() {
        setSuperAdmin(false)
        Map out = call("save#ShopifyApp", [distribution: "CUSTOM", shopDomain: "plain-user", clientId: "c", clientSecret: "s", scopes: "read_orders"])
        assertTrue(ec.message.hasError())
        assertNull(out.shopifyApp)
        ec.message.clearErrors()
        assertNull(call("list#ShopifyApps", [:]).shopifyApps)
    }

    @Test
    void theSecretIsStoredButNeverReturned() {
        setSuperAdmin(true)
        Map saved = call("save#ShopifyApp", [distribution: "custom", shopDomain: "https://Secret-Store.myshopify.com/admin",
                                             clientId: "cid-1", clientSecret: SECRET, scopes: "read_orders, read_returns"])
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        Map app = (Map) saved.shopifyApp
        assertEquals("secret-store.myshopify.com", app.shopDomain)
        assertEquals("CUSTOM", app.distribution)
        assertEquals("read_orders,read_returns", app.scopes)
        assertTrue(app.hasClientSecret as boolean)
        assertFalse(saved.toString().contains(SECRET))
        assertFalse(call("list#ShopifyApps", [:]).toString().contains(SECRET))

        // An update without a secret keeps the stored one.
        call("save#ShopifyApp", [shopifyAppId: app.shopifyAppId, distribution: "CUSTOM", shopDomain: "secret-store", description: "renamed"])
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        def row = ec.entity.find("darpan.shopify.ShopifyApp").condition("shopifyAppId", app.shopifyAppId).disableAuthz().useCache(false).one()
        assertEquals(SECRET, row.clientSecret)
        assertEquals("renamed", row.description)
    }

    @Test
    void aSecondActiveAppForTheSameStoreAndACustomAppWithoutAStoreAreRefused() {
        setSuperAdmin(true)
        call("save#ShopifyApp", [distribution: "CUSTOM", shopDomain: "dup-store", clientId: "a", clientSecret: "s", scopes: "read_orders"])
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        call("save#ShopifyApp", [distribution: "CUSTOM", shopDomain: "dup-store", clientId: "b", clientSecret: "s", scopes: "read_orders"])
        assertTrue(ec.message.errors.join(" ").contains("already set up for dup-store.myshopify.com"))

        ec.message.clearErrors()
        call("save#ShopifyApp", [distribution: "CUSTOM", clientId: "c", clientSecret: "s", scopes: "read_orders"])
        assertTrue(ec.message.errors.join(" ").contains("needs the store"))

        ec.message.clearErrors()
        call("save#ShopifyApp", [distribution: "CUSTOM", shopDomain: "no-secret", clientId: "c", scopes: "read_orders"])
        assertTrue(ec.message.errors.join(" ").contains("client secret"))
    }

    private Map call(String verbNoun, Map params) {
        return (Map) ec.service.sync().name("admin.ShopifyAppAdminServices.${verbNoun}").parameters(params).disableAuthz().call()
    }

    private void setSuperAdmin(boolean on) {
        String userId = ec.user.userId
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find("moqui.security.UserGroupMember").condition("userGroupId", "DARPAN_SUPER_ADMIN")
                    .condition("userId", userId).deleteAll()
            if (on) {
                ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "DARPAN_SUPER_ADMIN",
                        description: "Darpan super admin"]).createOrUpdate()
                ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_SUPER_ADMIN",
                        userId: userId, fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).createOrUpdate()
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
