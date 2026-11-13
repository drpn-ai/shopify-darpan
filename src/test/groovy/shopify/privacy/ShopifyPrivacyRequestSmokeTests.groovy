package shopify.privacy

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-272 — the three privacy requests. What could bite: deleting another tenant's rows (every query is tenant-pinned
 * by hand, the active-tenant filter is skipped), deleting records Darpan read from HotWax/NetSuite, and a shop redact
 * wiping a second store's audit data when the tenant connects two stores.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyPrivacyRequestSmokeTests {
    private static final String A = "PRIV_A"
    private static final String B = "PRIV_B"
    private static final String SHOP = "priv-store.myshopify.com"
    private static final String OTHER_SHOP = "priv-other.myshopify.com"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-privacy-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void seed() {
        ["darpan.audit.AuditFindingRecord", "darpan.audit.AuditFinding"].each {
            ec.entity.find(it).condition("companyUserGroupId", "in", [A, B]).deleteAll()
        }
        ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("companyUserGroupId", "in", [A, B]).deleteAll()
        [A, B].each { String t ->
            upsert("moqui.security.UserGroup", [userGroupId: t, description: t, groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            upsert("darpan.audit.AuditStatement", [statementId: "${t}_R1".toString(), companyUserGroupId: t, auditId: "${t}_MC".toString(),
                    statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is in both."])
            upsert("darpan.audit.AuditCheck", [checkId: "${t}_CHK".toString(), companyUserGroupId: t, checkCode: "orders", label: "Orders"])
            upsert("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "${t}_CFG".toString(), companyUserGroupId: t,
                    shopApiUrl: "https://${SHOP}".toString(), apiVersion: "2026-04", accessToken: "shpat_${t}".toString(), isActive: "Y"])
            // Order 1001 (stored as its numeric id) and 1002 (stored as a global id), each with a HotWax record beside it.
            finding(t, "F1", [["SHOPIFY", "1001", null], ["OMS", "HC1001", "1001"]])
            finding(t, "F2", [["SHOPIFY", "#1002", "gid://shopify/Order/1002"], ["NETSUITE", "SO1002", null]])
            finding(t, "F3", [["SHOPIFY", "1003", null]])
        }
        // Tenant B also connects a second store.
        upsert("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "${B}_CFG2".toString(), companyUserGroupId: B,
                shopApiUrl: "https://${OTHER_SHOP}/admin".toString(), apiVersion: "2026-04", accessToken: "shpat_B2", isActive: "Y"])
    }

    @Test
    void customerRedactDeletesOnlyThoseOrdersShopifyRecordsInEachTenant() {
        Map result = (Map) call("redact#Customer", [shopDomain: SHOP, orderIds: [1001, "gid://shopify/Order/1002"]]).result
        assertEquals([(A): 2L, (B): 2L], result.deletedByTenant)
        [A, B].each { String t ->
            assertEquals(["1003"], shopifyRecordIds(t), "only order 1003's Shopify record is left in ${t}")
            assertEquals(["HC1001", "SO1002"], records(t).findAll { it.systemEnumId != "SHOPIFY" }*.recordId.sort(),
                    "records read from HotWax and NetSuite stay")
        }
    }

    @Test
    void dataRequestReportsTheRecordsWithTheirData() {
        Map report = (Map) call("report#CustomerData", [shopDomain: SHOP, orderIds: ["1002"]]).report
        Map tenants = (Map) report.tenants
        assertEquals([A, B] as Set, tenants.keySet())
        assertEquals(["#1002"], ((List<Map>) tenants[A])*.recordId)
        assertEquals("{\"email\":\"shopper@example.com\"}", ((List<Map>) tenants[A])[0].recordData)
    }

    @Test
    void anUnknownStoreChangesNothing() {
        Map result = (Map) call("redact#Customer", [shopDomain: "nobody.myshopify.com", orderIds: [1001]]).result
        assertEquals([:], result.deletedByTenant)
        Map shop = (Map) call("redact#Shop", [shopDomain: "nobody.myshopify.com"]).result
        assertEquals(0, shop.connectionsCleared)
        [A, B].each { assertEquals(["1001", "#1002", "1003"].sort(), shopifyRecordIds(it)) }
    }

    @Test
    void shopRedactClearsTheStoreButKeepsATenantsOtherStoreData() {
        Map result = (Map) call("redact#Shop", [shopDomain: SHOP]).result
        assertEquals(2, result.connectionsCleared)
        assertEquals([(A): 3L], result.deletedByTenant, "A connects only this store: its Shopify records go")
        assertEquals([B], result.needsReview, "B connects another store: kept, flagged for a person")
        assertEquals([], shopifyRecordIds(A))
        assertEquals(["#1002", "1001", "1003"].sort(), shopifyRecordIds(B))
        assertEquals(1, records(A).findAll { it.systemEnumId == "OMS" }.size())
        [A, B].each { String t ->
            def cfg = ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", "${t}_CFG".toString()).useCache(false).one()
            assertNull(cfg.accessToken)
            assertEquals("N", cfg.isActive)
        }
        def other = ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", "${B}_CFG2".toString()).useCache(false).one()
        assertEquals("shpat_B2", other.accessToken, "the other store's connection is untouched")
    }

    @Test
    void idFormsCoverNumericAndGlobalIds() {
        assertEquals(["1001", "gid://shopify/Order/1001"] as Set, ShopifyPrivacySupport.orderIdForms(["gid://shopify/Order/1001"]))
        assertTrue(ShopifyPrivacySupport.orderIdForms(null).isEmpty())
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map call(String verbNoun, Map params) {
        Map out = (Map) ec.service.sync().name("privacy.ShopifyPrivacyRequestServices.${verbNoun}").parameters(params).disableAuthz().call()
        assertNotNull(out, "${ec.message.errors}")
        return out
    }

    private void finding(String t, String id, List<List<String>> recs) {
        String f = "${t}_${id}".toString()
        java.sql.Date today = new java.sql.Date(System.currentTimeMillis())
        upsert("darpan.audit.AuditFinding", [findingId: f, companyUserGroupId: t, statementId: "${t}_R1".toString(), checkId: "${t}_CHK".toString(),
                recordKey: f, period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "missing", firstFoundDate: today, lastSeenDate: today])
        recs.eachWithIndex { List<String> r, int i ->
            upsert("darpan.audit.AuditFindingRecord", [findingId: f, recordSeqId: String.format("%02d", i + 1), companyUserGroupId: t,
                    systemEnumId: r[0], recordType: "order", recordId: r[1], internalId: r[2],
                    recordData: r[0] == "SHOPIFY" ? "{\"email\":\"shopper@example.com\"}" : "{}"])
        }
    }

    private List records(String t) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("companyUserGroupId", t).useCache(false).list()
    }

    private List<String> shopifyRecordIds(String t) {
        return records(t).findAll { it.systemEnumId == "SHOPIFY" }.collect { it.recordId as String }.sort()
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
