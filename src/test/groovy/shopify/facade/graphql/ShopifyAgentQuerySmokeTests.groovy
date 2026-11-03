package shopify.facade.graphql

import darpan.agent.AgentQueryLane
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
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
import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003 Task 7: fresh GraphQL and the trimmed schema through the tenant's own Shopify connection. No network: the transport's httpExecutor is a seam. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyAgentQuerySmokeTests {
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String TENANT_A = "KREWE"
    private static final String TENANT_B = "GORJANA"
    private static final String TENANT_VIEW = "DOLLSKILL"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final String SHOP = "secret-shop-name"
    private static final String HOST = SHOP + ".myshopify.com"
    private static final String TOKEN = "shpat_" + ("z" * 30)
    private static final String LEAK2 = "query Leak2 { orders(first: 1) { edges { node { id } } } }"
    private static final String ORDERS = "query Data { orders(first: 2) { edges { node { id } } } }"

    private static final Map INTROSPECTION = [__schema: [
            queryType: [name: "Query"],
            types    : [
                    [kind: "OBJECT", name: "Query", description: "Root", fields: [
                            [name: "orders", description: "Orders", type: [kind: "OBJECT", name: "OrderConnection", ofType: null],
                             args: [[name: "filter", description: "Filter", defaultValue: null,
                                     type: [kind: "NON_NULL", name: null, ofType: [kind: "INPUT_OBJECT", name: "OrderFilter", ofType: null]]]]]]],
                    [kind: "OBJECT", name: "OrderConnection", description: null, fields: [
                            [name: "edges", description: null, args: [],
                             type: [kind: "NON_NULL", name: null, ofType: [kind: "LIST", name: null, ofType: [kind: "OBJECT", name: "Order", ofType: null]]]]]],
                    [kind: "OBJECT", name: "Order", description: "An order", fields: [
                            [name: "status", description: "Status", args: [], type: [kind: "ENUM", name: "OrderStatus", ofType: null]]]],
                    [kind: "INPUT_OBJECT", name: "OrderFilter", description: "Filter", inputFields: [
                            [name: "id", description: "Id", defaultValue: null, type: [kind: "SCALAR", name: "String", ofType: null]]]],
                    [kind: "ENUM", name: "OrderStatus", description: "Statuses", enumValues: [[name: "OPEN", description: "Open"], [name: "DONE", description: null]]],
                    [kind: "SCALAR", name: "String", description: null],
                    [kind: "OBJECT", name: "Mutation", description: "Writes", fields: [
                            [name: "cancelOrder", description: null, args: [], type: [kind: "OBJECT", name: "CancelPayload", ofType: null]]]],
                    [kind: "OBJECT", name: "CancelPayload", description: null, fields: []],
            ]]]

    private ExecutionContext ec
    private final List<Map> sent = []
    private Closure answer

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-agent-query-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                description: "View only"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "DarpanSharedConfigType",
                description: "Darpan API source config types"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "SCFG_SHOPIFY_AUTH", enumTypeId: "DarpanSharedConfigType",
                description: "Shopify"]).createOrUpdate()
        seedTenant(TENANT_A, "Krewe", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_B, "Gorjana", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_VIEW, "Dolls Kill", TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedConfig("SHOP_A", TENANT_A)
        seedConfig("SHOP_B", TENANT_B)
        seedConfig("SHOP_V", TENANT_VIEW)
        seedConfig("SHOP_E", TENANT_A)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        sent.clear()
        ec.cache.getCache(ShopifyAgentQuerySupport.CACHE_NAME).clear()
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([data: [orders: [edges: [[node: [id: 1]], [node: [id: 2]]]]]])] }
        AgentQueryLane.reset()
        ShopifyAgentQuerySupport.httpExecutorOverride = { Map req ->
            sent << req
            return (Map) answer.call(req)
        }
    }

    @AfterEach
    void restore() { ShopifyAgentQuerySupport.httpExecutorOverride = null }

    @Test
    void "returns data for a query"() {
        int before = logRows(ORDERS, "OK").size()
        Map r = run(TENANT_A, "SHOP_A", ORDERS)
        assertTrue(r.ok as boolean, "${r.errors}")
        assertEquals(2, ((r.data as Map).orders as Map).edges.size())
        assertEquals(2, r.rowCount)
        assertFalse(r.truncated as boolean)
        assertEquals(1, sent.size())
        assertEquals("https://${HOST}/admin/api/2026-01/graphql.json".toString(), sent[0].url)
        assertEquals(before + 1, logRows(ORDERS, "OK").size())
    }

    @Test
    void "a mutation never reaches Shopify"() {
        String mutation = "mutation M { orderCancel(orderId: \"1\") { job { id } } }"
        int before = logRows(mutation, "REFUSED").size()
        Map r = run(TENANT_A, "SHOP_A", mutation)
        assertFalse(r.ok as boolean)
        assertEquals("REFUSED", r.code)
        assertTrue(sent.isEmpty())
        assertEquals(before + 1, logRows(mutation, "REFUSED").size())
    }

    @Test
    void "timeouts are under 60 seconds and there is a single attempt"() {
        run(TENANT_A, "SHOP_A", ORDERS)
        assertEquals(15000, sent[0].connectTimeoutMillis)
        assertEquals(45000, sent[0].readTimeoutMillis)
        answer = { Map req -> [statusCode: 429, body: "{}"] }
        assertFalse(run(TENANT_A, "SHOP_A", ORDERS).ok as boolean)
        assertEquals(2, sent.size(), "a 429 must not be retried")
    }

    @Test
    void "introspection through run is refused, through get schema is allowed"() {
        Map r = run(TENANT_A, "SHOP_A", "query I { __schema { types { name } } }")
        assertFalse(r.ok as boolean)
        assertTrue(sent.isEmpty())
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION])] }
        Map s = schema(TENANT_A, "SHOP_A")
        assertTrue(s.ok as boolean, "${s.errors}")
        assertEquals(1, sent.size())
        assertTrue((sent[0].body.query as String).contains("__schema"))
    }

    @Test
    void "schema is trimmed, cached, returned as a copy, and an edited config misses the cache"() {
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION])] }
        Map s = schema(TENANT_A, "SHOP_E")
        Map trimmed = s.schema as Map
        assertEquals("Query", trimmed.queryType)
        assertEquals(["Query", "OrderConnection", "Order", "OrderFilter", "OrderStatus", "String"] as Set,
                (trimmed.types as List).collect { it.name } as Set)
        assertEquals(1, sent.size())
        Map again = schema(TENANT_A, "SHOP_E")
        assertEquals(trimmed, again.schema)
        assertNotSame(trimmed, again.schema)
        (again.schema as Map).put("tampered", true)
        assertNull((schema(TENANT_A, "SHOP_E").schema as Map).get("tampered"))
        assertEquals(1, sent.size(), "later calls come from the cache")
        // Editing the connection (a new last-updated stamp) must not serve the old schema.
        def cfg = ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", "SHOP_E").disableAuthz().forUpdate(true).one()
        cfg.set("apiVersion", "2026-04")
        cfg.set("lastUpdatedStamp", new Timestamp(System.currentTimeMillis() + 5000))
        cfg.update()
        assertTrue(schema(TENANT_A, "SHOP_E").ok as boolean)
        assertEquals(2, sent.size(), "an edited connection re-reads the schema")
    }

    @Test
    void "another tenant's config is refused"() {
        Map r = run(TENANT_A, "SHOP_B", ORDERS)
        assertFalse(r.ok as boolean, "tenant A reached tenant B's connection")
        assertTrue(sent.isEmpty())
        assertFalse(schema(TENANT_A, "SHOP_B").ok as boolean)
        assertTrue(sent.isEmpty())
        assertTrue(run(TENANT_B, "SHOP_B", ORDERS).ok as boolean)
        assertEquals(1, sent.size())
    }

    @Test
    void "view-only refused for run, allowed for get schema"() {
        Map r = run(TENANT_VIEW, "SHOP_V", ORDERS)
        assertFalse(r.ok as boolean)
        assertTrue(sent.isEmpty())
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION])] }
        Map s = schema(TENANT_VIEW, "SHOP_V")
        assertTrue(s.ok as boolean, "${s.errors}")
        assertNotNull(s.schema)
    }

    @Test
    void "an error carrying the shop domain and token comes back stripped"() {
        answer = { Map req -> throw new IllegalStateException("connect to https://${HOST}/admin failed, token ${TOKEN}".toString()) }
        int before = logRows(LEAK2, "ERROR").size()
        Map r = run(TENANT_A, "SHOP_A", "query Leak { orders(first: 1) { edges { node { id } } } }")
        // The transport turns an exception into a generic failure; make the leak come through a GraphQL error message too.
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([errors: [[message: "bad field on ${HOST} with ${TOKEN}".toString()]]])] }
        Map r2 = run(TENANT_A, "SHOP_A", LEAK2)
        [r, r2].each { Map x ->
            assertEquals("ERROR", x.code)
            String text = "${x.error} ${x.errors}"
            assertFalse(text.contains(HOST), text)
            assertFalse(text.contains(SHOP), text)
            assertFalse(text.contains(TOKEN), text)
        }
        assertTrue("${r2.errors}".contains("[hidden]"), "${r2.errors}")
        assertEquals(before + 1, logRows(LEAK2, "ERROR").size())
    }

    @Test
    void "an answer over 5 MB is refused with advice, not cut"() {
        String big = "x" * (5 * 1024 * 1024 + 100)
        answer = { Map req -> [statusCode: 200, body: JsonOutput.toJson([data: [shop: [name: big]]])] }
        Map r = run(TENANT_A, "SHOP_A", "query Big { shop { name } }")
        assertFalse(r.ok as boolean)
        assertEquals("REFUSED", r.code)
        assertTrue("${r.errors}".contains("5 MB"), "${r.errors}")
        assertFalse(r.truncated as boolean)
    }

    @Test
    void "row count is only for one top-level connection"() {
        assertNull(ShopifyAgentQuerySupport.edgeCount([a: 1, b: 2]))
        assertEquals(1, ShopifyAgentQuerySupport.edgeCount([orders: [edges: [[node: [:]]]]]))
    }

    private Map run(String tenant, String configId, String document) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.ShopifyFacadeServices.run#ShopifyGraphqlAgentQuery")
                .parameters([shopifyAuthConfigId: configId, document: document]).disableAuthz().call()
    }

    private Map schema(String tenant, String configId) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.ShopifyFacadeServices.get#ShopifyGraphqlSchema")
                .parameters([shopifyAuthConfigId: configId]).disableAuthz().call()
    }

    private List logRows(String queryText, String outcome) {
        return ec.entity.find("darpan.agent.AgentQueryLog").condition("queryText", queryText).condition("outcome", outcome)
                .disableAuthz().useCache(false).list() as List
    }

    private void seedConfig(String id, String tenant) {
        ec.entity.makeValue("darpan.shopify.ShopifyAuthConfig").setAll([shopifyAuthConfigId: id, description: id,
                companyUserGroupId: tenant, shopApiUrl: "https://${HOST}/admin/api".toString(), apiVersion: "2026-01",
                accessToken: TOKEN, isActive: "Y", canReadOrders: "Y"]).createOrUpdate()
    }

    private void seedTenant(String tenantId, String label, String permissionGroupId) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenantId, userId: USER, fromDate: FROM]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: USER, permissionUserGroupId: permissionGroupId, fromDate: FROM]).createOrUpdate()
    }
}
