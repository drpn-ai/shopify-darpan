package shopify.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 B1 — the fifth seam Tasks 1-13 missed: RUNTIME CREDENTIAL *USE*. Task 7's
 * {@code ShopifySharedConfigAccessTests} proved a peer tenant can SEE and EDIT a Shopify auth config
 * shared with it via the settings facade. It never proved a peer tenant's AUTOMATION run could
 * actually use it — {@code ShopifyAuthConfigSupport.requireUsableAuthConfig}'s automation branch
 * ({@code opts.companyUserGroupId} present) stayed strict owner-only, so a config visible and
 * editable to a peer still failed every reconciliation run against it. Every automation caller in
 * this component funnels through this one method (extractShopifyOrders, the exchange-state/sweep/
 * order-id lookups, the GraphQL facade), so proving the method directly proves the fix for all of
 * them without needing to fake Shopify's Bulk Operations GraphQL protocol end to end.
 *
 * <p>Uses a real Moqui {@link ExecutionContext} via {@link ReconciliationSmokeTestSupport}, matching
 * {@code ShopifySharedConfigAccessTests}' own pattern and for the same reason (no stub {@code ec} has
 * a usable {@code ec.entity}/{@code ec.message} pair against a real persisted fixture).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifySharedConfigRuntimeAccessTests {
    private static final String GRANT_ENTITY_NAME = "darpan.auth.ConfigTenantAccess"
    private static final String CONFIG_TYPE = "SCFG_SHOPIFY_AUTH"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "SHOPIFY_RUNTIME_OWNER"
    private static final String MEMBER = "SHOPIFY_RUNTIME_MEMBER"
    private static final String STRANGER = "SHOPIFY_RUNTIME_STRANGER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-auth-shared-config-runtime-access")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(OWNER, "Runtime Owner")
        seedTenant(MEMBER, "Runtime Member")
        seedTenant(STRANGER, "Runtime Stranger")
        seedConfigTypeEnumeration()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
    }

    // --- automation path (explicit companyUserGroupId) -------------------

    @Test
    void automationPathAllowsAPeerTenantWithAnActiveGrant() {
        String configId = "RUNTIME_AUTOMATION_MEMBER_SHOPIFY"
        seedShopifyFixture(configId, "Automation member fixture")
        seedGrant(configId, MEMBER)

        def resolved = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                [disableAuthz: true, companyUserGroupId: MEMBER])
        assertFalse(ec.message.hasError(), ec.message.getErrors()?.toString())
        assertNotNull(resolved,
                "the whole point of this feature: a peer tenant's automation run against a shared " +
                "Shopify config must actually resolve the config, not just see it in the settings list")
    }

    @Test
    void automationPathDeniesAStrangerWithTheSameTextForARealAndNonexistentConfig() {
        String configId = "RUNTIME_AUTOMATION_STRANGER_TARGET_SHOPIFY"

        def missing = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                [disableAuthz: true, companyUserGroupId: STRANGER])
        assertNull(missing)
        List<String> missingErrors = (ec.message.getErrors() ?: []) as List<String>
        assertFalse(missingErrors.isEmpty())

        ec.message.clearErrors()
        seedShopifyFixture(configId, "Automation stranger target")
        def foreign = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                [disableAuthz: true, companyUserGroupId: STRANGER])
        List<String> foreignErrors = (ec.message.getErrors() ?: []) as List<String>
        assertFalse(foreignErrors.isEmpty())

        // requireUsableAuthConfig still returns the resolved record even when it adds an error (the
        // caller checks ec.message.hasError(), not nullness) — the security-relevant assertion is
        // that the ERROR TEXT collapses, not that the return value is null.
        assertEquals(missingErrors, foreignErrors,
                "a stranger automation tenant (no ownership, no grant) must get byte-identical text " +
                "whether the config id is real-but-foreign or does not exist at all — divergence here " +
                "is a cross-tenant existence oracle")
        assertTrue(foreignErrors.join(" ").contains("was not found"),
                "denial text must collapse to the plain not-found message, never a distinguishable " +
                "'not available in this automation tenant': ${foreignErrors}")
    }

    @Test
    void automationPathZeroGrantsPreservesOwnerAcceptAndForeignReject() {
        String configId = "RUNTIME_AUTOMATION_ZERO_GRANT_SHOPIFY"
        seedShopifyFixture(configId, "Zero grant automation fixture")
        // Deliberately no seedGrant call — proves behavior is unchanged from pre-DAR-BE-005 with an
        // empty ConfigTenantAccess table.

        def ownerResolved = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                [disableAuthz: true, companyUserGroupId: OWNER])
        assertFalse(ec.message.hasError(), ec.message.getErrors()?.toString())
        assertNotNull(ownerResolved)

        ec.message.clearErrors()
        ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId,
                [disableAuthz: true, companyUserGroupId: STRANGER])
        assertTrue(ec.message.hasError(),
                "with zero grants a foreign automation tenant must still be rejected exactly as before")
    }

    // --- non-hollow sanity: the interactive branch (already Task-7-correct) --

    @Test
    void interactivePathAlreadyAllowsAPeerTenantWithAnActiveGrant() {
        String configId = "RUNTIME_INTERACTIVE_MEMBER_SHOPIFY"
        seedShopifyFixture(configId, "Interactive member fixture")
        seedGrant(configId, MEMBER)

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        def resolved = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configId, [disableAuthz: true])
        assertFalse(ec.message.hasError(), ec.message.getErrors()?.toString())
        assertNotNull(resolved)
    }

    // --- helpers -----------------------------------------------------------

    private void seedTenant(String tenantId, String label) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId    : tenantId,
                description    : label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntityValue("moqui.security.UserGroupMember", [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ], [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ])
    }

    private void seedShopifyFixture(String configId, String description) {
        upsertEntityValue(ShopifyAuthConfigSupport.ENTITY_NAME, [shopifyAuthConfigId: configId], [
                shopifyAuthConfigId: configId,
                description        : description,
                companyUserGroupId : OWNER,
                createdByUserId    : TEST_USER_ID,
                shopApiUrl         : "https://${configId.toLowerCase()}.myshopify.com/admin/api".toString(),
                apiVersion         : "2025-10",
                timeZone           : "America/Chicago",
                accessToken        : "runtime-shared-shopify-token",
                isActive           : "Y",
                canReadOrders      : "Y",
        ])
    }

    /** ConfigTenantAccess.configTypeEnumId has a DB FK to moqui.basic.Enumeration. Production loads
     *  this row from darpan/data/SecuritySeedData.xml; this isolated test DB does not auto-load seed
     *  data, so it is hand-seeded here too (matches ShopifySharedConfigAccessTests' own
     *  seedConfigTypeEnumeration). */
    private void seedConfigTypeEnumeration() {
        upsertEntityValue("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"], [
                enumTypeId : "DarpanSharedConfigType",
                description: "Darpan API source config types that support cross-tenant sharing",
        ])
        upsertEntityValue("moqui.basic.Enumeration", [enumId: CONFIG_TYPE], [
                enumId     : CONFIG_TYPE,
                description: "Shopify auth config (darpan.shopify.ShopifyAuthConfig)",
                enumTypeId : "DarpanSharedConfigType",
        ])
    }

    private void seedGrant(String configId, String tenantUserGroupId) {
        upsertEntityValue(GRANT_ENTITY_NAME, [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
        ], [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
                fromDate         : TEST_FROM_DATE,
                thruDate         : null,
                grantedByUserId  : TEST_USER_ID,
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedShopifySharedConfigRuntimeAccess",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing != null) return

            ec.service.sync()
                    .name("store#${entityName}")
                    .parameters(fields)
                    .disableAuthz()
                    .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
