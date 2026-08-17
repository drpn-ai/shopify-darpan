package shopify.facade.settings

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import shopify.facade.graphql.ShopifyGraphqlFacadeSupport
import shopify.graphql.ShopifySourceCatalog

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyEndpointGateTests {
    private ExecutionContext ec
    private static final String CONFIG_ID = "gate-shopify"
    private static final String TENANT = "GATE_TENANT"
    // Only used by executeShopifyGraphqlRefusesWhenShopifyEndpointDisabled below: the interactive
    // branch it exercises (ShopifyGraphqlFacadeSupport.executeGraphql passes no companyUserGroupId)
    // resolves the active tenant from a REAL logged-in user's memberships, unlike rejects() above,
    // which stays on the automation branch and never needs a session at all.
    private static final String GRAPHQL_TEST_USER_ID = "GATE_SHOPIFY_GRAPHQL_USER"
    private static final String GRAPHQL_TEST_USERNAME = "gate.shopify.graphql"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-endpoint-gate")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // FK prerequisites: ShopifyAuthConfig.companyUserGroupId -> moqui.security.UserGroup, and
        // SourceConfigEndpointAccess declares type="one" relationships to UserGroup and TWICE to
        // moqui.basic.Enumeration (configTypeEnumId, systemEnumId) — real FK constraints exist.
        // systemEnumId="SHOPIFY_RETURN_REFS" is already covered by the DarpanSystemSourceSeedData.xml
        // load above, but configTypeEnumId (SCFG_SHOPIFY_AUTH, normally seeded by
        // darpan/data/SecuritySeedData.xml) and the tenant's UserGroup row are not auto-loaded by this
        // isolated test DB, so both are hand-seeded here — matching the convention in
        // SourceEndpointAccessSupportTests.setup() (darpan) and OmsEndpointGateTests.setup()
        // (darpan-hotwax).
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT, description: "Gate seam smoke-test tenant"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, enumTypeId: "DarpanSharedConfigType"]).createOrUpdate()

        ec.entity.makeValue("darpan.shopify.ShopifyAuthConfig")
                .setAll([shopifyAuthConfigId: CONFIG_ID, description: "Gate",
                         companyUserGroupId : TENANT,
                         shopApiUrl         : "https://gate.myshopify.com/admin/api",
                         apiVersion         : "2026-01", isActive: "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH,
                         configId          : CONFIG_ID, systemEnumId: "SHOPIFY_RETURN_REFS",
                         companyUserGroupId: TENANT, isEnabled: "N"]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    // Automation-branch shape (opts.companyUserGroupId present), matching OmsEndpointGateTests'
    // convention: it resolves against an EXPLICIT trusted tenant via SharedConfigAccessSupport's
    // canTenantUseConfig, so this needs no session login / active-tenant preference at all — unlike
    // the interactive branch, which depends on a real logged-in ec.user.
    private boolean rejects(String requiredEndpoint) {
        ec.message.clearErrors()
        ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, CONFIG_ID,
                [requiredEndpointSystemEnumId: requiredEndpoint, companyUserGroupId: TENANT])
        boolean hadError = ec.message.hasError()
        ec.message.clearErrors()
        return hadError
    }

    @Test
    void disabledReturnRefsIsRefusedEvenThoughOrdersIsEnabled() {
        assertTrue(rejects("SHOPIFY_RETURN_REFS"))
        assertFalse(rejects("SHOPIFY"))
    }

    // Task 14: the connection probe must report per-endpoint state (from
    // SourceEndpointAccessSupport.listEndpointsForConfig) rather than the retired canReadOrders
    // boolean. CONFIG_ID here carries no accessToken, so the credential stage fails before any
    // network call — but the endpoints list is resolved in probeConnection() before the credential
    // check runs, so it is present on the result regardless of credential state. ShopifyConnectionProbe
    // is in this same package (shopify.facade.settings), so no import is needed to reach it.
    @Test
    void probeNamesTheEnabledEndpointsRatherThanASingleFlag() {
        Map<String, Object> result = ShopifyConnectionProbe.probeConnection(ec, CONFIG_ID)

        List<Map<String, Object>> endpoints = result.endpoints as List<Map<String, Object>>
        assertNotNull(endpoints, "The probe must report per-endpoint state, not one canReadOrders boolean")
        assertTrue(endpoints.find { it.systemEnumId == "SHOPIFY" }.isEnabled as boolean)
        assertFalse(endpoints.find { it.systemEnumId == "SHOPIFY_RETURN_REFS" }.isEnabled as boolean)
    }

    // Fix round 1: a Groovy-level unit test on ShopifyConnectionProbe cannot see the service-dispatch
    // out-parameter drop — probeConnection()'s return value already carries `endpoints` regardless of
    // whether the Moqui service declares it as an out-parameter (Moqui's service engine only copies
    // DECLARED out-parameters from ec.context into the result map it hands back to a service caller).
    // This test goes through ec.service.sync().name(...).call() — real service dispatch — so it is the
    // one that would have failed before probe#ShopifyAuthConnection declared `endpoints` in its
    // out-parameters. Naming convention ("facade.ShopifyFacadeServices.probe#ShopifyAuthConnection")
    // matches every other ec.service.sync() call in this component's tests (see
    // ShopifyAuthConfigFacadeSmokeTests for "facade.ShopifyFacadeServices.list#ShopifyAuthConfigs" etc).
    @Test
    void probeServiceDispatchIncludesEndpointsNotJustTheSupportMethod() {
        Map<String, Object> result = ec.service.sync()
                .name("facade.ShopifyFacadeServices.probe#ShopifyAuthConnection")
                .parameters([shopifyAuthConfigId: CONFIG_ID])
                .call() as Map<String, Object>

        List<Map<String, Object>> endpoints = result.endpoints as List<Map<String, Object>>
        assertNotNull(endpoints,
                "probe#ShopifyAuthConnection's out-parameters must declare endpoints, or the Moqui " +
                "service engine drops it even though ShopifyConnectionProbe.probeConnection computed it")
        assertTrue(endpoints.find { it.systemEnumId == "SHOPIFY" }.isEnabled as boolean)
        assertFalse(endpoints.find { it.systemEnumId == "SHOPIFY_RETURN_REFS" }.isEnabled as boolean)
    }

    // Fix round 1: probe#ShopifyAuthConnection's out-parameters were only HALF the drop. The real
    // remote-facing gateway an operator/UI actually calls is
    // facade.SettingsFacadeServices.test#SourceConnection (darpan) — its Groovy support method,
    // SourceConnectionDiagnosticsSupport.testSourceConnection, calls the connector's probe service via
    // ec.service.sync() and then explicitly REBUILDS its own return map from that result. Before this
    // fix round it never read `endpoints` off the inner result at all, so `endpoints` would have been
    // dropped a second time here even with the previous test passing. This test goes through the actual
    // production call path (test#SourceConnection, not probe#ShopifyAuthConnection directly) against
    // the real SHOPIFY connector row from SourceSystemConnectorSeedData.xml (loaded in setup()).
    //
    // A separate KREWE-tenant config is used (rather than CONFIG_ID/GATE_TENANT above) because
    // test#SourceConnection enforces TenantAccessSupport.requireActiveTenantWriteAccess, and
    // ReconciliationSmokeTestSupport.seedCompanyScope is the canned fixture that already sets up a
    // real logged-in user with write access to the "KREWE" tenant (same helper
    // SourceConnectionDiagnosticsFacadeSmokeTests and ShopifyAuthConfigFacadeSmokeTests use).
    @Test
    void endpointsReachesTheRealGatewayServiceNotJustTheConnectorProbe() {
        String kreweConfigId = "gate-shopify-krewe"
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("darpan.shopify.ShopifyAuthConfig")
                .setAll([shopifyAuthConfigId: kreweConfigId, description: "Gate (KREWE gateway check)",
                         companyUserGroupId : "KREWE",
                         shopApiUrl         : "https://gate-krewe.myshopify.com/admin/api",
                         apiVersion         : "2026-01", isActive: "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH,
                         configId          : kreweConfigId, systemEnumId: "SHOPIFY_RETURN_REFS",
                         companyUserGroupId: "KREWE", isEnabled: "N"]).createOrUpdate()

        Map<String, Object> result = ec.service.sync()
                .name("facade.SettingsFacadeServices.test#SourceConnection")
                .parameters([systemEnumId: "SHOPIFY", configId: kreweConfigId])
                .call() as Map<String, Object>

        List<Map<String, Object>> endpoints = result.endpoints as List<Map<String, Object>>
        assertNotNull(endpoints,
                "test#SourceConnection must forward endpoints from the connector probe result — " +
                "SourceConnectionDiagnosticsSupport.testSourceConnection must not silently drop it " +
                "while rebuilding its own return map, and the service's out-parameters must declare it")
        assertTrue(endpoints.find { it.systemEnumId == "SHOPIFY" }.isEnabled as boolean)
        assertFalse(endpoints.find { it.systemEnumId == "SHOPIFY_RETURN_REFS" }.isEnabled as boolean)
    }

    @Test
    void catalogEntriesDeclareDistinctEndpoints() {
        // The latent bug: both sources used to name the same flag, so enabling orders permitted
        // return-refs. They must now be independently gated.
        String orders = ShopifySourceCatalog.getSource(ShopifySourceCatalog.SHOPIFY_ORDERS)
                .requiredEndpointSystemEnumId
        String returnRefs = ShopifySourceCatalog.getSource(ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS)
                .requiredEndpointSystemEnumId
        assertEquals("SHOPIFY", orders)
        assertEquals("SHOPIFY_RETURN_REFS", returnRefs)
        assertNotEquals(orders, returnRefs)
    }

    // --- Task 16 regression test: THE call site that matters most.
    //
    // ShopifyGraphqlFacadeSupport.executeGraphql (the registered SHOPIFY connector remoteSendServiceName
    // / execute#ShopifyGraphql) called requireUsableAuthConfig(ec, configId, [:]) — before Task 6,
    // requireUsableAuthConfig defaulted requiredPermissionFlag to "canReadOrders" and enforced it even
    // with an empty opts map; Task 6 removed that default and nothing replaced it at this call site, so
    // an empty opts enforced NOTHING. This is red before Task 16 wires "SHOPIFY" into that call.
    //
    // CONFIG_ID carries no accessToken in this fixture, so ShopifyGraphqlTransport.execute fails fast
    // on "Shopify access token is not configured." WITHOUT any network call whenever the gate does not
    // block first — that keeps the red run deterministic and network-free, and is also why the
    // assertion below checks for the SPECIFIC "not enabled for SHOPIFY" gate message (and a null
    // result, proving the transport was never reached) rather than merely ec.message.hasError(), which
    // the missing-token failure would satisfy on its own and mask the real regression.
    @Test
    void executeShopifyGraphqlRefusesWhenShopifyEndpointDisabled() {
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH,
                         configId          : CONFIG_ID, systemEnumId: "SHOPIFY",
                         companyUserGroupId: TENANT, isEnabled: "N"]).createOrUpdate()
        try {
            // ec.user.setPreference requires a REAL logged-in user (initMoqui's fallback login is
            // anonymous, which setPreference explicitly rejects: "no user logged in"). setup() above
            // never logs one in because rejects() never needed one. Seed the minimum: a company-typed
            // TENANT group (setup()'s UserGroup row carries no groupTypeEnumId, so it would not be
            // resolvable as an "available tenant"), a user, and that user's membership in TENANT.
            ec.entity.makeValue("moqui.basic.EnumerationType")
                    .setAll([enumTypeId: "UserGroupType", description: "User Group Type"]).createOrUpdate()
            ec.entity.makeValue("moqui.basic.Enumeration")
                    .setAll([enumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
                             enumTypeId: "UserGroupType", description: "Darpan company groups"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: TENANT, description: "Gate seam smoke-test tenant",
                             groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserAccount")
                    .setAll([userId: GRAPHQL_TEST_USER_ID, username: GRAPHQL_TEST_USERNAME,
                             userFullName: "Gate Shopify GraphQL Test User", currentPassword: "",
                             disabled: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember")
                    .setAll([userGroupId: TENANT, userId: GRAPHQL_TEST_USER_ID,
                             fromDate: Timestamp.valueOf("2026-05-01 00:00:00")]).createOrUpdate()

            // internalLoginUser takes a USERNAME, not a userId.
            assertTrue(ec.user.internalLoginUser(GRAPHQL_TEST_USERNAME),
                    "test setup: could not log in ${GRAPHQL_TEST_USERNAME}")
            // Clear errors AFTER login, not before — login itself can leave a stray message.
            ec.message.clearErrors()
            ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)
            ec.message.clearErrors()
            Map<String, Object> result = ShopifyGraphqlFacadeSupport.executeGraphql(ec, [
                    shopifyAuthConfigId: CONFIG_ID,
                    queryDocument      : "{ shop { name } }",
            ])
            List<String> errors = (ec.message.getErrors() ?: []) as List<String>
            assertTrue(errors.any { it.contains("not enabled for SHOPIFY") },
                    "execute#ShopifyGraphql must refuse a config whose SHOPIFY endpoint is disabled: ${errors}")
            assertNull(result,
                    "a refused config must never reach ShopifyGraphqlTransport — a missing-token " +
                    "failure would still return a non-null (ok:false) result")
        } finally {
            ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                    .condition([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH,
                                configId          : CONFIG_ID, systemEnumId: "SHOPIFY",
                                companyUserGroupId: TENANT])
                    .disableAuthz().useCache(false).deleteAll()
            ec.message.clearErrors()
        }
    }
}
