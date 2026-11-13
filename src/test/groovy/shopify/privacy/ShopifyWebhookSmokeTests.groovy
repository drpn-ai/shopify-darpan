package shopify.privacy

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-273 — the webhook receiver, run as it runs in production: no user, authz on. What could bite: a forged or
 * borrowed signature getting through, an uninstall clearing connections it does not own, and the signature check
 * failing on a non-ASCII body (Moqui hands the body over as text).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyWebhookSmokeTests {
    private static final String T = "HOOK_T"
    private static final String SHOP = "hook-store.myshopify.com"
    private static final String SECRET = "hook-store-secret"
    private static final String OTHER_SECRET = "other-store-secret"
    private ExecutionContext ec
    private String username

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-webhook-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        username = ec.user.username
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void seed() {
        if (ec.user.userId == null) ec.user.internalLoginUser(username)
        ec.artifactExecution.disableAuthz()
        ["darpan.audit.AuditFindingRecord", "darpan.audit.AuditFinding"].each { ec.entity.find(it).condition("companyUserGroupId", T).deleteAll() }
        upsert("moqui.security.UserGroup", [userGroupId: T, description: T, groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("darpan.shopify.ShopifyApp", [shopifyAppId: "HOOK_APP", distribution: "CUSTOM", shopDomain: SHOP, tenantUserGroupId: T,
                clientId: "cid", clientSecret: SECRET, scopes: "read_orders", isActive: "Y"])
        upsert("darpan.shopify.ShopifyApp", [shopifyAppId: "HOOK_OTHER_APP", distribution: "CUSTOM", shopDomain: "hook-other.myshopify.com",
                tenantUserGroupId: T, clientId: "cid2", clientSecret: OTHER_SECRET, scopes: "read_orders", isActive: "Y"])
        upsert("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "HOOK_APP_CFG", companyUserGroupId: T, shopApiUrl: "https://${SHOP}".toString(),
                apiVersion: "2026-04", accessToken: "shpat_app", refreshToken: "shprt_app", shopifyAppId: "HOOK_APP",
                accessTokenExpiresDate: new java.sql.Timestamp(System.currentTimeMillis() + 3_600_000L), isActive: "Y"])
        upsert("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "HOOK_PASTED_CFG", companyUserGroupId: T, shopApiUrl: "https://${SHOP}".toString(),
                apiVersion: "2026-04", accessToken: "shpat_pasted", isActive: "Y"])
        upsert("darpan.audit.Audit", [auditId: "${T}_MC".toString(), companyUserGroupId: T, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        upsert("darpan.audit.AuditStatement", [statementId: "${T}_R1".toString(), companyUserGroupId: T, auditId: "${T}_MC".toString(),
                statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is in both."])
        upsert("darpan.audit.AuditCheck", [checkId: "${T}_CHK".toString(), companyUserGroupId: T, checkCode: "orders", label: "Orders"])
        java.sql.Date today = new java.sql.Date(System.currentTimeMillis())
        ["2001", "2002"].each { String id ->
            String f = "${T}_F${id}".toString()
            upsert("darpan.audit.AuditFinding", [findingId: f, companyUserGroupId: T, statementId: "${T}_R1".toString(), checkId: "${T}_CHK".toString(),
                    recordKey: f, period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "missing", firstFoundDate: today, lastSeenDate: today])
            upsert("darpan.audit.AuditFindingRecord", [findingId: f, recordSeqId: "01", companyUserGroupId: T, systemEnumId: "SHOPIFY",
                    recordType: "order", recordId: id, recordData: "{}"])
        }
        // As production: no user, authz on.
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()
    }

    @Test
    void aForgedOrBorrowedSignatureIsRefusedAndNothingRuns() {
        String body = JsonOutput.toJson([shop_domain: SHOP, customer: [id: 9], orders_to_redact: [2001]])
        assertEquals(401, post("customers/redact", body, "bm90IGEgc2lnbmF0dXJl").status)
        // Signed with ANOTHER store's Darpan app: a real secret, but not this store's.
        assertEquals(401, post("customers/redact", body, ShopifyWebhookSupport.signature(OTHER_SECRET, body)).status)
        assertEquals(401, post("customers/redact", body, null).status)
        assertEquals(["2001", "2002"], shopifyRecordIds())
    }

    @Test
    void aSignedBodyResentWithAnotherStoreInTheHeaderIsRefused() {
        // Review finding: the header is not signed. A body naming another store, correctly signed, sent with this store
        // in the header must not act on this store.
        String body = JsonOutput.toJson([shop_id: 1, shop_domain: "someone-else.myshopify.com"])
        assertEquals(400, post("shop/redact", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        assertEquals(["2001", "2002"], shopifyRecordIds())
        assertEquals("shpat_app", config("HOOK_APP_CFG").accessToken)
    }

    @Test
    void aSignedBodyResentUnderAnotherTopicIsRefused() {
        // Review finding: the topic header is not signed. A genuine data request re-sent as shop/redact erases nothing.
        String body = JsonOutput.toJson([shop_domain: SHOP, customer: [id: 9], orders_requested: [2001], data_request: [id: 5]])
        assertEquals(400, post("shop/redact", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        assertEquals(400, post("customers/redact", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        assertEquals(["2001", "2002"], shopifyRecordIds())
        assertEquals("shpat_app", config("HOOK_APP_CFG").accessToken)
    }

    @Test
    void anAppSwitchedOffStillReceivesPrivacyRequestsButNotUninstalls() {
        // Review finding: shop/redact comes 48 h after uninstall, often after the app is switched off; it is still owed.
        ec.user.internalLoginUser(username); ec.artifactExecution.disableAuthz()
        def app = ec.entity.find("darpan.shopify.ShopifyApp").condition("shopifyAppId", "HOOK_APP").useCache(false).one()
        app.set("isActive", "N"); app.update()
        ec.user.logoutUser(); ec.artifactExecution.enableAuthz()
        try {
            String redact = JsonOutput.toJson([shop_domain: SHOP, customer: [id: 9], orders_to_redact: [2002]])
            assertEquals(200, post("customers/redact", redact, ShopifyWebhookSupport.signature(SECRET, redact)).status)
            assertEquals(["2001"], shopifyRecordIds())
            String uninstall = JsonOutput.toJson([id: 1, myshopify_domain: SHOP])
            assertEquals(200, post("app/uninstalled", uninstall, ShopifyWebhookSupport.signature(SECRET, uninstall)).status)
            assertEquals("shpat_app", config("HOOK_APP_CFG").accessToken, "a switched-off app's uninstall acts on nothing")
        } finally {
            ec.user.internalLoginUser(username); ec.artifactExecution.disableAuthz()
            app.set("isActive", "Y"); app.update()
        }
    }

    @Test
    void shopRedactLeavesAPastedConnectionToTheSameStoreAlone() {
        // Review finding: the store uninstalled the app, not Darpan. The tenant also connects the store with a pasted
        // token, so the app's connection is cleared but the pasted one and the findings stay (flagged for review).
        String body = JsonOutput.toJson([shop_id: 1, shop_domain: SHOP])
        assertEquals(200, post("shop/redact", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        assertNull(config("HOOK_APP_CFG").accessToken)
        assertEquals("shpat_pasted", config("HOOK_PASTED_CFG").accessToken)
        assertEquals(["2001", "2002"], shopifyRecordIds())
    }

    @Test
    void uninstallClearsOnlyThisAppsConnectionToTheStore() {
        String body = JsonOutput.toJson([id: 1, domain: SHOP, myshopify_domain: SHOP])
        assertEquals(200, post("app/uninstalled", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        def app = config("HOOK_APP_CFG")
        assertNull(app.accessToken)
        assertNull(app.refreshToken)
        assertEquals("N", app.isActive)
        assertEquals("shpat_pasted", config("HOOK_PASTED_CFG").accessToken, "a pasted-token connection is not the app's to clear")
    }

    @Test
    void customerRedactWithANonAsciiBodyIsVerifiedAndDeletesThatOrder() {
        String body = JsonOutput.toJson([shop_domain: SHOP, customer: [id: 9, email: "zoë@example.com"], orders_to_redact: [2001]])
        body = groovy.json.StringEscapeUtils.unescapeJava(body) // send the ë as a real character, as Shopify does
        assertEquals(200, post("customers/redact", body, ShopifyWebhookSupport.signature(SECRET, body)).status)
        assertEquals(["2002"], shopifyRecordIds())
    }

    @Test
    void shopRedactDataRequestAndOtherTopicsAnswer200() {
        String shopBody = JsonOutput.toJson([shop_id: 1, shop_domain: SHOP])
        String requestBody = JsonOutput.toJson([shop_domain: SHOP, customer: [id: 9], orders_requested: [2002], data_request: [id: 5]])
        assertEquals(200, post("customers/data_request", requestBody, ShopifyWebhookSupport.signature(SECRET, requestBody)).status)
        assertEquals(200, post("orders/create", shopBody, ShopifyWebhookSupport.signature(SECRET, shopBody)).status, "ignored topic")
        assertEquals(["2001", "2002"], shopifyRecordIds(), "neither deletes anything")
        assertEquals(400, post("shop/redact", "not json", ShopifyWebhookSupport.signature(SECRET, "not json")).status)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map post(String topic, String body, String hmac) {
        return ShopifyWebhookSupport.handle(ec, [topic: topic, hmac: hmac, shopDomain: SHOP, webhookId: "wh-1"], body)
    }

    private def config(String id) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { return ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", id).useCache(false).one() }
        finally { if (!was) ec.artifactExecution.enableAuthz() }
    }

    private List<String> shopifyRecordIds() {
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            return ec.entity.find("darpan.audit.AuditFindingRecord").condition("companyUserGroupId", T).condition("systemEnumId", "SHOPIFY")
                    .useCache(false).list().collect { it.recordId as String }.sort()
        } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
