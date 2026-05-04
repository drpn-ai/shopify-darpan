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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyAuthConfigFacadeSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-auth-config-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedPermissionGroup(TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, "Can manage tenant-scoped Darpan settings")
        seedPermissionGroup(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, "Can view tenant-scoped Darpan data but cannot mutate it")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedTenant(GORJANA, "Gorjana")
        seedShopifyFixtures()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
    }

    @Test
    void listsOnlyReturnRowsForTheActiveTenant() {
        assertTenantVisibleRows(KREWE, ["KREWE_SHOPIFY"])
        assertTenantVisibleRows(GORJANA, ["GORJANA_SHOPIFY"])
    }

    @Test
    void listAndGetResponsesOnlyExposeSecretIndicators() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> listResult = listFacade()
        List<Map<String, Object>> configs = (List<Map<String, Object>>) (listResult.shopifyAuthConfigs ?: [])
        assertEquals(["KREWE_SHOPIFY"], configs.collect { Map<String, Object> row -> row.shopifyAuthConfigId })
        assertTrue(configs.first().hasAccessToken as boolean)
        assertEquals("America/Chicago", configs.first().timeZone)
        assertNoRawCredentialFields(configs)

        ec.message.clearErrors()
        Map<String, Object> getResult = getFacade("KREWE_SHOPIFY")
        assertTrue((Boolean) getResult.ok, getResult.errors?.toString())
        Map<String, Object> config = (Map<String, Object>) getResult.shopifyAuthConfig
        assertTrue(config.hasAccessToken as boolean)
        assertEquals("America/Chicago", config.timeZone)
        assertNoRawCredentialFields(getResult.shopifyAuthConfig)
    }

    @Test
    void viewOnlyTenantCanReadButCannotCreateOrUpdateConfigs() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> listResult = listFacade()
        assertTrue((Boolean) listResult.ok, listResult.errors?.toString())

        ec.message.clearErrors()
        Map<String, Object> createResult = saveFacade([
            shopifyAuthConfigId: "KREWE_VIEW_ONLY_CREATE",
            description        : "Blocked view-only create",
            shopApiUrl         : "https://blocked.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            accessToken        : "blocked-token",
            canReadOrders      : true,
        ])
        assertFalse((Boolean) createResult.ok)
        assertTrue((createResult.errors ?: []).join(" ").contains("view access"))
        assertNull(findOne([shopifyAuthConfigId: "KREWE_VIEW_ONLY_CREATE"]))

        ec.message.clearErrors()
        Map<String, Object> updateResult = saveFacade([
            shopifyAuthConfigId: "KREWE_SHOPIFY",
            description        : "Blocked view-only update",
            shopApiUrl         : "https://blocked-update.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            canReadOrders      : false,
        ])
        assertFalse((Boolean) updateResult.ok)
        assertTrue((updateResult.errors ?: []).join(" ").contains("view access"))
        assertEquals("Krewe Shopify", findOne([shopifyAuthConfigId: "KREWE_SHOPIFY"]).description)
    }

    @Test
    void activeTenantCannotUpdateAnotherTenantConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> result = saveFacade([
            shopifyAuthConfigId: "KREWE_SHOPIFY",
            description        : "Cross tenant edit",
            shopApiUrl         : "https://cross-tenant.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            canReadOrders      : true,
        ])

        assertFalse((Boolean) result.ok)
        assertTrue((result.errors ?: []).join(" ").contains("not available in your active tenant"))
        assertEquals("Krewe Shopify", findOne([shopifyAuthConfigId: "KREWE_SHOPIFY"]).description)
    }

    @Test
    void savingWithoutReplacementTokenPreservesStoredToken() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> result = saveFacade([
            shopifyAuthConfigId: "GORJANA_SHOPIFY",
            description        : "Gorjana Shopify Updated",
            shopApiUrl         : "https://gorjana-updated.myshopify.com/admin/api/",
            apiVersion         : "2025-10",
            accessToken        : "",
            isActive           : true,
            canReadOrders      : false,
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        def saved = findOne([shopifyAuthConfigId: "GORJANA_SHOPIFY"])
        assertEquals("gorjana-shopify-token", saved.accessToken)
        assertEquals("https://gorjana-updated.myshopify.com/admin/api", saved.shopApiUrl)
        assertEquals("America/Los_Angeles", saved.timeZone)
        assertEquals("N", saved.canReadOrders)
        assertTrue(((Map<String, Object>) result.savedShopifyAuthConfig).hasAccessToken as boolean)
    }

    @Test
    void tenantAdminCanPersistOrderReadPermissionCheckbox() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> createResult = saveFacade([
            shopifyAuthConfigId: "GORJANA_CREATED_SHOPIFY",
            description        : "Created Shopify",
            shopApiUrl         : "https://created.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            timeZone           : "America/New_York",
            accessToken        : "created-shopify-token",
            isActive           : true,
            canReadOrders      : true,
        ])

        assertTrue((Boolean) createResult.ok, createResult.errors?.toString())
        assertTenantOwnership([shopifyAuthConfigId: "GORJANA_CREATED_SHOPIFY"])
        assertEquals("Y", findOne([shopifyAuthConfigId: "GORJANA_CREATED_SHOPIFY"]).canReadOrders)
        assertEquals("America/New_York", findOne([shopifyAuthConfigId: "GORJANA_CREATED_SHOPIFY"]).timeZone)
        assertTrue(((Map<String, Object>) createResult.savedShopifyAuthConfig).canReadOrders as boolean)
    }

    @Test
    void saveRejectsInvalidTimezone() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> result = saveFacade([
            shopifyAuthConfigId: "GORJANA_BAD_TZ",
            description        : "Bad timezone",
            shopApiUrl         : "https://bad-tz.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            timeZone           : "Central",
            accessToken        : "bad-tz-token",
            canReadOrders      : true,
        ])

        assertFalse((Boolean) result.ok)
        assertTrue((result.errors ?: []).join(" ").contains("Shopify timezone is invalid"))
        assertNull(findOne([shopifyAuthConfigId: "GORJANA_BAD_TZ"]))
    }

    @Test
    void tenantAdminCanDeleteOwnConfigButViewOnlyTenantCannotDelete() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> createResult = saveFacade([
            shopifyAuthConfigId: "GORJANA_DELETE_SHOPIFY",
            description        : "Delete Shopify",
            shopApiUrl         : "https://delete.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            accessToken        : "delete-shopify-token",
            isActive           : true,
            canReadOrders      : true,
        ])
        assertTrue((Boolean) createResult.ok, createResult.errors?.toString())
        assertTenantOwnership([shopifyAuthConfigId: "GORJANA_DELETE_SHOPIFY"])

        ec.message.clearErrors()
        Map<String, Object> deleteResult = deleteFacade("GORJANA_DELETE_SHOPIFY")
        assertTrue((Boolean) deleteResult.ok, deleteResult.errors?.toString())
        assertEquals(true, deleteResult.deleted)
        assertEquals("GORJANA_DELETE_SHOPIFY", deleteResult.deletedShopifyAuthConfigId)
        assertNull(findOne([shopifyAuthConfigId: "GORJANA_DELETE_SHOPIFY"]))

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map<String, Object> blockedResult = deleteFacade("KREWE_SHOPIFY")
        assertFalse((Boolean) blockedResult.ok)
        assertTrue((blockedResult.errors ?: []).join(" ").contains("view access"))
        assertEquals("Krewe Shopify", findOne([shopifyAuthConfigId: "KREWE_SHOPIFY"]).description)
    }

    private void assertTenantVisibleRows(String tenantId, List<String> expectedIds) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenantId)
        ec.message.clearErrors()

        Map<String, Object> result = listFacade()
        assertTrue((Boolean) result.ok, result.errors?.toString())
        List<Map<String, Object>> configs = (List<Map<String, Object>>) (result.shopifyAuthConfigs ?: [])
        List<String> visibleIds = configs.collect { Map<String, Object> row -> row.shopifyAuthConfigId }
        assertTrue(visibleIds.containsAll(expectedIds), "Expected ${expectedIds} in ${visibleIds}")
        assertTrue(configs.every { Map<String, Object> row -> row.companyUserGroupId == tenantId })
    }

    private Map<String, Object> listFacade() {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.ShopifyFacadeServices.list#ShopifyAuthConfigs")
            .parameters([
                pageIndex: 0,
                pageSize : 20,
                query    : "",
            ])
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

    private void assertTenantOwnership(Map<String, Object> pkFields) {
        def record = findOne(pkFields)
        assertEquals(GORJANA, record.companyUserGroupId)
        assertEquals(TEST_USER_ID, record.createdByUserId)
    }

    private def findOne(Map<String, Object> pkFields) {
        return ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
            .condition(pkFields)
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
            "replaceShopifyTenantPermission",
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

    private void seedShopifyFixtures() {
        upsertEntityValue(ShopifyAuthConfigSupport.ENTITY_NAME, [shopifyAuthConfigId: "KREWE_SHOPIFY"], [
            shopifyAuthConfigId: "KREWE_SHOPIFY",
            description        : "Krewe Shopify",
            companyUserGroupId : KREWE,
            createdByUserId    : TEST_USER_ID,
            shopApiUrl         : "https://krewe.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            timeZone           : "America/Chicago",
            accessToken        : "krewe-shopify-token",
            isActive           : "Y",
            canReadOrders      : "Y",
        ])
        upsertEntityValue(ShopifyAuthConfigSupport.ENTITY_NAME, [shopifyAuthConfigId: "GORJANA_SHOPIFY"], [
            shopifyAuthConfigId: "GORJANA_SHOPIFY",
            description        : "Gorjana Shopify",
            companyUserGroupId : GORJANA,
            createdByUserId    : TEST_USER_ID,
            shopApiUrl         : "https://gorjana.myshopify.com/admin/api",
            apiVersion         : "2025-10",
            timeZone           : "America/Los_Angeles",
            accessToken        : "gorjana-shopify-token",
            isActive           : "Y",
            canReadOrders      : "Y",
        ])
    }

    private static void assertNoRawCredentialFields(Object payload) {
        if (payload instanceof Map) {
            Map map = (Map) payload
            ["accessToken", "password", "privateKey", "apiToken", "privateKeyPem"].each { String fieldName ->
                assertFalse(map.containsKey(fieldName), "Response must not expose ${fieldName}: ${map}")
            }
            map.values().each { Object value -> assertNoRawCredentialFields(value) }
        } else if (payload instanceof Iterable) {
            ((Iterable) payload).each { Object value -> assertNoRawCredentialFields(value) }
        }
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "seedShopifyAuthConfig",
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
