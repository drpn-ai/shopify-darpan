package shopify.facade.settings

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import shopify.graphql.ShopifySourceCatalog

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyEndpointGateTests {
    private ExecutionContext ec
    private static final String CONFIG_ID = "gate-shopify"
    private static final String TENANT = "GATE_TENANT"

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
}
