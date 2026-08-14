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
