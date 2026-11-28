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
import static org.junit.jupiter.api.Assertions.assertFalse
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
    /** OC204: peers that read the store only through A's shared connection (C granted, D granted then revoked). */
    private static final String C = "PRIV_C"
    private static final String D = "PRIV_D"
    private static final String SHOP = "priv-store.myshopify.com"
    private static final String OTHER_SHOP = "priv-other.myshopify.com"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-privacy-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void seed() {
        ["darpan.audit.AuditFindingRecord", "darpan.audit.AuditFinding"].each {
            ec.entity.find(it).condition("companyUserGroupId", "in", [A, B, C, D]).deleteAll()
        }
        ec.entity.find("darpan.auth.ConfigTenantAccess").condition("tenantUserGroupId", "in", [C, D]).deleteAll()
        ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("companyUserGroupId", "in", [A, B]).deleteAll()
        ["darpan.idindex.RecordIdIndex", "darpan.idindex.RecordIdIndexCursor", "darpan.idindex.RecordIdIndexDrop", "darpan.dataset.DatasetRead",
         "darpan.dataset.Dataset"].each {
            ec.entity.find(it).condition("companyUserGroupId", "in", [A, B, C, D]).deleteAll()
        }
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
        assertEquals([(A): 3L, (B): 0L], result.deletedByTenant, "A connects only this store: its Shopify records go; B nothing it can prove")
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
    void recordsThatSayTheirConnectionAreErasedExactlyEvenWithASecondStore() {
        // DAR-BE-284: B connects two stores. Records read through this store's connection go; the other store's stay.
        finding(B, "F4", [["SHOPIFY", "1004", null]], "${B}_CFG".toString())
        finding(B, "F5", [["SHOPIFY", "5005", null]], "${B}_CFG2".toString())
        Map result = (Map) call("redact#Shop", [shopDomain: SHOP]).result
        assertEquals(1L, ((Map) result.deletedByTenant)[B], "only the record read through this store's connection")
        assertEquals(["#1002", "1001", "1003", "5005"].sort(), shopifyRecordIds(B), "legacy records kept for review, the other store's record kept")
        assertEquals([B], result.needsReview)
    }

    @Test
    void shopRedactAlsoDropsTheStoreConnectionsKeptCopiesAndIdIndexRows() {
        // OC92: kept copies and the ID index hold the store's order ids, names and amounts and no retention clears them.
        keptCopy(A, "PRIV_DS_A", "${A}_CFG".toString())
        keptCopy(B, "PRIV_DS_B", "${B}_CFG".toString())
        keptCopy(B, "PRIV_DS_B2", "${B}_CFG2".toString())
        Map result = (Map) call("redact#Shop", [shopDomain: SHOP]).result
        assertEquals([(A): 1, (B): 1], result.keptCopiesDropped)
        assertFalse(keptDataLeft(A, "PRIV_DS_A", "${A}_CFG".toString()), "A's copy of this store is gone")
        assertFalse(keptDataLeft(B, "PRIV_DS_B", "${B}_CFG".toString()), "B's copy of this store is gone")
        assertTrue(keptDataLeft(B, "PRIV_DS_B2", "${B}_CFG2".toString()), "B's other store keeps its copy and index")
    }

    /**
     * OC204 (Session 010's security re-verify): A shares its connection to the store with C, and once shared it with D (revoked).
     * Both read the store through it, so their finding records and kept data carry A's config id. shop/redact erases them as
     * it erases A's: a former peer's data is still the store's data.
     */
    @Test
    void shopRedactErasesWhatPeersReadThroughASharedConnectionRevokedGrantsIncluded() {
        String shared = "${A}_CFG".toString()
        peer(C, shared, null)
        peer(D, shared, java.sql.Timestamp.valueOf("2026-06-01 00:00:00"))
        [C, D].each { String t ->
            finding(t, "F9", [["SHOPIFY", "9001", null]], shared)
            keptCopy(t, "PRIV_DS_${t}".toString(), shared)
        }
        Map result = (Map) call("redact#Shop", [shopDomain: SHOP]).result
        [C, D].each { String t ->
            assertEquals([], shopifyRecordIds(t), "${t}'s records read through A's shared connection are erased")
            assertFalse(keptDataLeft(t, "PRIV_DS_${t}".toString(), shared), "${t}'s kept copy and index rows are erased")
        }
        assertNull(ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", shared).useCache(false).one().accessToken)
        assertTrue(((Map) result.deletedByTenant).keySet().containsAll([C, D]))
    }

    /** OC204: customers/redact and the data request reach a peer that read the store through a shared connection. */
    @Test
    void customerRedactAndDataRequestReachAPeerOfASharedConnection() {
        String shared = "${A}_CFG".toString()
        peer(C, shared, null)
        finding(C, "F9", [["SHOPIFY", "9001", null]], shared)
        Map report = (Map) call("report#CustomerData", [shopDomain: SHOP, orderIds: ["9001"]]).report
        assertEquals(["9001"], ((List<Map>) ((Map) report.tenants)[C])*.recordId, "the peer's record is reported")
        Map result = (Map) call("redact#Customer", [shopDomain: SHOP, orderIds: [9001]]).result
        assertEquals(1L, ((Map) result.deletedByTenant)[C])
        assertEquals([], shopifyRecordIds(C))
    }

    /**
     * OC232 (Session 010 round 2): the store's kept copies and ID index hold the customer's orders (ids, amounts, the customer's
     * id; no name, email or address since DAR-BE-260), so customers/redact drops every tenant's kept data read through the
     * store as shop/redact does; a copy is a cache, rebuilt from Shopify, which has redacted the customer itself. A redact
     * naming no order drops nothing, and another store's copy stays.
     */
    @Test
    void customerRedactDropsTheStoresKeptCopiesAndIdIndexRows() {
        keptCopy(A, "PRIV_DS_A", "${A}_CFG".toString())
        keptCopy(B, "PRIV_DS_B", "${B}_CFG".toString())
        keptCopy(B, "PRIV_DS_B2", "${B}_CFG2".toString())
        Map none = (Map) call("redact#Customer", [shopDomain: SHOP, orderIds: []]).result
        assertEquals([(A): 0, (B): 0], none.keptCopiesDropped, "a redact naming no order drops nothing")
        assertTrue(keptDataLeft(A, "PRIV_DS_A", "${A}_CFG".toString()))
        Map result = (Map) call("redact#Customer", [shopDomain: SHOP, orderIds: [1001]]).result
        assertEquals([(A): 1, (B): 1], result.keptCopiesDropped)
        assertFalse(keptDataLeft(A, "PRIV_DS_A", "${A}_CFG".toString()), "A's copy of this store is gone")
        assertFalse(keptDataLeft(B, "PRIV_DS_B", "${B}_CFG".toString()), "B's copy of this store is gone")
        assertTrue(keptDataLeft(B, "PRIV_DS_B2", "${B}_CFG2".toString()), "B's other store keeps its copy and index")
    }

    /**
     * Security review of OC232: config ids are per-entity sequences, so the tenant's NetSuite connection can share the Shopify
     * connection's id. shop/redact erases the store's Shopify kept data and never the NetSuite copy under the same id.
     */
    @Test
    void aRedactNeverDropsAnotherSystemsCopyUnderTheSameConnectionId() {
        keptCopy(A, "PRIV_DS_A", "${A}_CFG".toString())
        keptCopy(A, "PRIV_DS_A_NS", "${A}_CFG".toString(), "NETSUITE_SUITEQL")
        Map result = (Map) call("redact#Shop", [shopDomain: SHOP]).result
        assertEquals(1, ((Map) result.keptCopiesDropped)[A])
        assertFalse(ec.entity.find("darpan.dataset.Dataset").condition("datasetId", "PRIV_DS_A").useCache(false).count() > 0)
        assertTrue(ec.entity.find("darpan.dataset.Dataset").condition("datasetId", "PRIV_DS_A_NS").useCache(false).count() > 0, "the NetSuite copy stays")
        assertTrue(darpan.dataset.DatasetRefreshSupport.datasetDir(ec, A, "PRIV_DS_A_NS").exists(), "and its folder")
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

    private void finding(String t, String id, List<List<String>> recs, String sourceConfigId = null) {
        String f = "${t}_${id}".toString()
        java.sql.Date today = new java.sql.Date(System.currentTimeMillis())
        upsert("darpan.audit.AuditFinding", [findingId: f, companyUserGroupId: t, statementId: "${t}_R1".toString(), checkId: "${t}_CHK".toString(),
                recordKey: f, period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "missing", firstFoundDate: today, lastSeenDate: today])
        recs.eachWithIndex { List<String> r, int i ->
            upsert("darpan.audit.AuditFindingRecord", [findingId: f, recordSeqId: String.format("%02d", i + 1), companyUserGroupId: t,
                    systemEnumId: r[0], recordType: "order", recordId: r[1], internalId: r[2], sourceConfigId: sourceConfigId,
                    recordData: r[0] == "SHOPIFY" ? "{\"email\":\"shopper@example.com\"}" : "{}"])
        }
    }

    /** A peer tenant granted A's connection (thru = when the grant was revoked; null: still active). */
    private void peer(String t, String configId, java.sql.Timestamp thru) {
        // ConfigTenantAccess.configTypeEnumId has a DB FK to Enumeration (SecuritySeedData in prod); seeded by hand here
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType", description: "Darpan shared config types"])
        upsert("moqui.basic.Enumeration", [enumId: "SCFG_SHOPIFY_AUTH", description: "Shopify auth config", enumTypeId: "DarpanSharedConfigType"])
        upsert("moqui.security.UserGroup", [userGroupId: t, description: t, groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        upsert("darpan.audit.AuditStatement", [statementId: "${t}_R1".toString(), companyUserGroupId: t, auditId: "${t}_MC".toString(),
                statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is in both."])
        upsert("darpan.audit.AuditCheck", [checkId: "${t}_CHK".toString(), companyUserGroupId: t, checkCode: "orders", label: "Orders"])
        upsert("darpan.auth.ConfigTenantAccess", [configTypeEnumId: "SCFG_SHOPIFY_AUTH", configId: configId, tenantUserGroupId: t,
                fromDate: java.sql.Timestamp.valueOf("2026-05-01 00:00:00"), thruDate: thru])
    }

    private void keptCopy(String t, String datasetId, String connection, String system = "SHOPIFY") {
        java.sql.Timestamp now = ec.user.nowTimestamp
        upsert("darpan.dataset.Dataset", [datasetId: datasetId, companyUserGroupId: t, systemEnumId: system, sourceConfigId: connection,
                optionsHash: "h-${datasetId}".toString(), statusId: "DST_ACTIVE", readRuleEnumId: "DRR_CHANGED_SINCE", idField: "id",
                stampField: "updatedAt", windowField: "createdAt"])
        upsert("darpan.dataset.DatasetRead", [datasetId: datasetId, readSeqId: "000001", companyUserGroupId: t, readKindEnumId: "DRK_FULL",
                statusId: "DRS_DONE", readAt: now])
        upsert("darpan.idindex.RecordIdIndexCursor", [companyUserGroupId: t, datasetId: datasetId, systemEnumId: system,
                connectionId: connection, lastIndexedAt: now])
        upsert("darpan.idindex.RecordIdIndex", [rowKey: "rk-${datasetId}".toString(), companyUserGroupId: t, idNorm: "1001", idRaw: "1001",
                systemCode: system, systemEnumId: system, connectionId: connection, recordKind: "ORDER", recordKey: "1001",
                fieldPath: "id", role: "OWN", lastSeenAt: now])
        File dir = darpan.dataset.DatasetRefreshSupport.datasetDir(ec, t, datasetId)
        dir.mkdirs()
        new File(dir, "current.json").text = '{"version":"v1"}'
    }

    private boolean keptDataLeft(String t, String datasetId, String connection) {
        return ec.entity.find("darpan.dataset.Dataset").condition([companyUserGroupId: t, datasetId: datasetId]).useCache(false).count() > 0 ||
                ec.entity.find("darpan.dataset.DatasetRead").condition([companyUserGroupId: t, datasetId: datasetId]).useCache(false).count() > 0 ||
                ec.entity.find("darpan.idindex.RecordIdIndex").condition([companyUserGroupId: t, connectionId: connection]).useCache(false).count() > 0 ||
                ec.entity.find("darpan.idindex.RecordIdIndexCursor").condition([companyUserGroupId: t, connectionId: connection]).useCache(false).count() > 0 ||
                darpan.dataset.DatasetRefreshSupport.datasetDir(ec, t, datasetId).exists()
    }

    private List records(String t) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("companyUserGroupId", t).useCache(false).list()
    }

    private List<String> shopifyRecordIds(String t) {
        return records(t).findAll { it.systemEnumId == "SHOPIFY" }.collect { it.recordId as String }.sort()
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
