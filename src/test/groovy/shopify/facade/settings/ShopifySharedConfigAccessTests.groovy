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
 * DAR-BE-005 Task 7 — Seam A wired into shopify-darpan's own facade services (list/get/save/delete)
 * for darpan.shopify.ShopifyAuthConfig honouring cross-tenant ConfigTenantAccess grants.
 *
 * <p>See OmsSharedConfigAccessTests (darpan-hotwax, same task) for why this uses a real Moqui
 * ExecutionContext via ReconciliationSmokeTestSupport rather than darpan's
 * SharedConfigAccessSupportTests stubs: those stubs model ec.user/ec.entity/ec.message only, with
 * no ec.service, so they cannot exercise real facade service dispatch — only a static support
 * method called directly (SharedConfigPropagationTests' own pattern for ReconciliationSavedRunSupport).
 * The properties here are facade-service behavior (real dispatch, real XML out-parameter shape, real
 * entity persistence), so this class mirrors ShopifyAuthConfigFacadeSmokeTests' existing pattern.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifySharedConfigAccessTests {
    private static final String GRANT_ENTITY_NAME = "darpan.auth.ConfigTenantAccess"
    private static final String CONFIG_TYPE = "SCFG_SHOPIFY_AUTH"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "SHOPIFY_SHARE_OWNER"
    private static final String MEMBER = "SHOPIFY_SHARE_MEMBER"
    private static final String STRANGER = "SHOPIFY_SHARE_STRANGER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    // Distinct config ids per test so tests remain order-independent under
    // @TestInstance(PER_CLASS) — the delete test consumes its row.
    private static final String LIST_CONFIG_ID = "OWNER_LIST_SHARED_SHOPIFY"
    private static final String GET_CONFIG_ID = "OWNER_GET_SHARED_SHOPIFY"
    private static final String SAVE_CONFIG_ID = "OWNER_SAVE_SHARED_SHOPIFY"
    private static final String DELETE_CONFIG_ID = "OWNER_DELETE_SHARED_SHOPIFY"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-auth-shared-config-access")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedPermissionGroup(TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, "Can manage tenant-scoped Darpan settings")
        seedTenant(OWNER, "Share Owner")
        seedTenant(MEMBER, "Share Member")
        seedTenant(STRANGER, "Share Stranger")
        seedConfigTypeEnumeration()

        seedShopifyFixture(LIST_CONFIG_ID, "List fixture")
        seedShopifyFixture(GET_CONFIG_ID, "Get fixture")
        seedShopifyFixture(SAVE_CONFIG_ID, "Save fixture")
        seedShopifyFixture(DELETE_CONFIG_ID, "Delete fixture")

        seedGrant(LIST_CONFIG_ID)
        seedGrant(GET_CONFIG_ID)
        seedGrant(SAVE_CONFIG_ID)
        seedGrant(DELETE_CONFIG_ID)
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

    @Test
    void listFlagsASharedConfigAsSharedForTheMemberButNotForTheOwner() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> memberResult = listFacade()
        assertTrue((Boolean) memberResult.ok, memberResult.errors?.toString())
        Map<String, Object> memberRow = (memberResult.shopifyAuthConfigs as List).find {
            it.shopifyAuthConfigId == LIST_CONFIG_ID
        }
        assertNotNull(memberRow, "a member tenant must see a config shared with it in its own list")
        assertTrue(memberRow.isShared as boolean, "a config owned by another tenant must be flagged isShared")

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = listFacade()
        Map<String, Object> ownerRow = (ownerResult.shopifyAuthConfigs as List).find {
            it.shopifyAuthConfigId == LIST_CONFIG_ID
        }
        assertNotNull(ownerRow)
        assertFalse(ownerRow.isShared as boolean, "the owner's own config must never be flagged isShared")
    }

    @Test
    void getAllowsAMemberTenantToReadASharedConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> result = getFacade(GET_CONFIG_ID)
        assertTrue((Boolean) result.ok, result.errors?.toString())
        Map<String, Object> config = (Map<String, Object>) result.shopifyAuthConfig
        assertEquals(GET_CONFIG_ID, config.shopifyAuthConfigId)
        assertTrue(config.isShared as boolean)
    }

    /**
     * DAR-BE-005 Task 7 review finding: getAuthConfig is the highest-risk surface per the brief
     * (caller-supplied id), but no test proved a genuine STRANGER — owns nothing, holds no grant —
     * gets identical text for a real foreign config as for a nonexistent one. Uses the SAME literal
     * configId for both calls (sequenced: first while the id does not exist, then again once a
     * real row exists under that exact id) — the "was not found" message echoes the caller-supplied
     * id, so two different ids would trivially produce different text regardless of whether the
     * collapse holds. This is the exact leak class Task 6 accidentally reopened one task before this.
     */
    @Test
    void getAuthConfigGivesAStrangerTheIdenticalMessageForARealAndANonexistentConfigId() {
        String targetConfigId = "SHOPIFY_STRANGER_GET_TARGET"
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> missingResult = getFacade(targetConfigId)
        assertFalse((Boolean) missingResult.ok)

        ec.message.clearErrors()
        seedShopifyFixture(targetConfigId, "Stranger get target")
        Map<String, Object> foreignResult = getFacade(targetConfigId)
        assertFalse((Boolean) foreignResult.ok)

        assertEquals(missingResult.errors, foreignResult.errors,
                "a stranger (no ownership, no grant) must get byte-identical text whether the " +
                "config id is real-but-foreign or does not exist at all — divergence here is a " +
                "cross-tenant existence oracle")
    }

    @Test
    void saveAcceptsAMemberEditOfEveryFieldAndNeverTransfersOwnership() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = saveFacade([
            shopifyAuthConfigId: SAVE_CONFIG_ID,
            description        : "Edited by member",
            shopApiUrl         : "https://owner-shared-save.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            canReadOrders      : true,
        ])
        assertTrue((Boolean) result.ok, result.errors?.toString())

        def stored = findOne(SAVE_CONFIG_ID)
        assertEquals("Edited by member", stored.description,
                "a member tenant must be able to edit every field of a shared config")
        assertEquals("https://owner-shared-save.myshopify.com/admin/api", stored.shopApiUrl)
        assertEquals("Y", stored.canReadOrders)
        assertEquals(OWNER, stored.companyUserGroupId,
                "a member's save must never transfer ownership of the shared row")
        Map<String, Object> saved = (Map<String, Object>) result.savedShopifyAuthConfig
        assertTrue(saved.isShared as boolean, "the save response must also flag the row isShared for the peer")
    }

    /**
     * DAR-BE-005 Task 9: a config with an active grant cannot be deleted by ANYONE, including its
     * owner — configId is polymorphic with no DB FK, so a delete would cascade nothing and leave
     * the peer's automation failing at run time with a confusing "not found" instead of failing
     * loudly here. This complements (does not replace) the peer-cannot-delete rule Task 7 already
     * proved: a peer never deletes; an owner deletes only once the group is empty.
     */
    @Test
    void deleteRejectsAMemberTenantAndTheOwnerWhileAGrantRemainsActive() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> blockedResult = deleteFacade(DELETE_CONFIG_ID)
        assertFalse((Boolean) blockedResult.ok)
        assertTrue((blockedResult.errors ?: []).join(" ").contains("Stop sharing it instead of deleting it"),
                "a peer must be told to stop sharing instead of delete: ${blockedResult.errors}")
        assertNotNull(findOne(DELETE_CONFIG_ID), "the shared row must survive a peer's delete attempt")

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = deleteFacade(DELETE_CONFIG_ID)
        assertFalse((Boolean) ownerResult.ok,
                "the owner must not be able to delete a config that still has an active grant")
        assertTrue((ownerResult.errors ?: []).join(" ").contains(
                "Stop sharing it with every tenant before deleting it"),
                "the owner-cannot-delete-while-shared guard must fire: ${ownerResult.errors}")
        assertNotNull(findOne(DELETE_CONFIG_ID),
                "the config must survive even the owner's delete attempt while a grant is active")
    }

    /**
     * DAR-BE-005 Task 9, the other half: once every grant on a config is revoked, the owner may
     * delete it normally — the guard is about live dependents, not a permanent lock.
     */
    @Test
    void deleteAcceptsTheOwnerOnceEveryGrantIsRevoked() {
        String configId = "OWNER_DELETE_AFTER_REVOKE_SHOPIFY"
        seedShopifyFixture(configId, "Delete-after-revoke fixture")
        seedGrant(configId)

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> blockedResult = deleteFacade(configId)
        assertFalse((Boolean) blockedResult.ok,
                "the owner must not be able to delete a config that still has an active grant")
        assertNotNull(findOne(configId), "a blocked delete must not remove the row")

        ec.message.clearErrors()
        revokeGrant(configId, Timestamp.valueOf("2026-05-02 00:00:00"))

        Map<String, Object> ownerResult = deleteFacade(configId)
        assertTrue((Boolean) ownerResult.ok, ownerResult.errors?.toString())
        assertEquals(true, ownerResult.deleted)
        assertNull(findOne(configId), "once every grant is revoked the owner may delete normally")
    }

    /**
     * DAR-BE-005 Task 7 review finding: the pre-existing coverage of deleteAuthConfig's no-standing
     * branch used a stranger against a NONEXISTENT id only, or a read-only OWNER (not a stranger).
     * Neither proves a genuine stranger gets identical text for a real foreign config as for a
     * nonexistent one. Same SAME-literal-id sequencing as getAuthConfig's stranger test above, for
     * the same reason (the message echoes the caller-supplied id).
     */
    @Test
    void deleteGivesAStrangerTheIdenticalMessageForARealAndANonexistentConfigId() {
        String targetConfigId = "SHOPIFY_STRANGER_DELETE_TARGET"
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> missingResult = deleteFacade(targetConfigId)
        assertFalse((Boolean) missingResult.ok)

        ec.message.clearErrors()
        seedShopifyFixture(targetConfigId, "Stranger delete target")
        Map<String, Object> foreignResult = deleteFacade(targetConfigId)
        assertFalse((Boolean) foreignResult.ok)
        assertNotNull(findOne(targetConfigId),
                "a stranger's delete attempt on a real foreign config must not delete it")

        assertEquals(missingResult.errors, foreignResult.errors,
                "a stranger (no ownership, no grant) must get byte-identical text whether the " +
                "config id is real-but-foreign or does not exist at all — divergence here is a " +
                "cross-tenant existence oracle")
    }

    private Map<String, Object> listFacade() {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.ShopifyFacadeServices.list#ShopifyAuthConfigs")
            .parameters([pageIndex: 0, pageSize: 20, query: ""])
            .disableAuthz()
            .call()
    }

    private Map<String, Object> getFacade(String configId) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.ShopifyFacadeServices.get#ShopifyAuthConfig")
            .parameters([shopifyAuthConfigId: configId])
            .disableAuthz()
            .call()
    }

    private Map<String, Object> saveFacade(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.ShopifyFacadeServices.save#ShopifyAuthConfig")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private Map<String, Object> deleteFacade(String configId) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.ShopifyFacadeServices.delete#ShopifyAuthConfig")
            .parameters([shopifyAuthConfigId: configId])
            .disableAuthz()
            .call()
    }

    private def findOne(String configId) {
        return ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
            .condition([shopifyAuthConfigId: configId])
            .disableAuthz()
            .useCache(false)
            .one()
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
            userGroupId    : permissionGroupId,
            description    : description,
            groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

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
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "replaceShopifySharedConfigTenantPermission",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", tenantId)
                .condition("userId", TEST_USER_ID)
                .disableAuthz()
                .useCache(false)
                .list()
                .each { it.delete() }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
        ], [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
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
            accessToken        : "shared-shopify-token",
            isActive           : "Y",
            canReadOrders      : "N",
        ])
    }

    /** ConfigTenantAccess.configTypeEnumId has a DB FK to moqui.basic.Enumeration. Production loads
     *  this row from darpan/data/SecuritySeedData.xml; this isolated test DB does not auto-load seed
     *  data (the existing smoke tests hand-seed their own permission-group rows for the same reason),
     *  so it is hand-seeded here too rather than pulling in the whole seed file. */
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

    private void seedGrant(String configId) {
        upsertEntityValue(GRANT_ENTITY_NAME, [
            configTypeEnumId : CONFIG_TYPE,
            configId         : configId,
            tenantUserGroupId: MEMBER,
            fromDate         : TEST_FROM_DATE,
        ], [
            configTypeEnumId : CONFIG_TYPE,
            configId         : configId,
            tenantUserGroupId: MEMBER,
            fromDate         : TEST_FROM_DATE,
            thruDate         : null,
            grantedByUserId  : TEST_USER_ID,
        ])
    }

    /** Soft-revokes the MEMBER grant seeded by {@link #seedGrant} — sets thruDate rather than
     *  deleting the row, matching production revoke semantics (SharedConfigGrantSupport.revokeAccess). */
    private void revokeGrant(String configId, Timestamp thruDate) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "revokeShopifySharedConfigAccess",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.service.sync()
                .name("update#${GRANT_ENTITY_NAME}")
                .parameters([
                    configTypeEnumId : CONFIG_TYPE,
                    configId         : configId,
                    tenantUserGroupId: MEMBER,
                    fromDate         : TEST_FROM_DATE,
                    thruDate         : thruDate,
                ])
                .disableAuthz()
                .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "seedShopifySharedConfigAccess",
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
