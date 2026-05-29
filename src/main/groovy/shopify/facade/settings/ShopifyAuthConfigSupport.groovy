package shopify.facade.settings

import darpan.common.ValueSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import org.moqui.entity.EntityCondition

import java.net.URI

class ShopifyAuthConfigSupport {
    static final String ENTITY_NAME = "darpan.shopify.ShopifyAuthConfig"
    static final String DEFAULT_ACTIVE_FLAG = "Y"
    static final String DEFAULT_TIME_ZONE = TenantAccessSupport.DEFAULT_TIME_ZONE

    static String normalize(Object value) {
        return ValueSupport.normalize(value)
    }

    static String normalizeShopApiUrl(Object value) {
        String raw = normalize(value)
        if (!raw) return raw
        return raw.endsWith("/") ? raw.replaceAll(/\/+$/, "") : raw
    }

    static String normalizeApiVersion(Object value) {
        return normalize(value)?.toLowerCase()
    }

    static String normalizeTimeZone(Object value) {
        return normalize(value) ?: DEFAULT_TIME_ZONE
    }

    static String normalizeIndicator(Object value, boolean defaultValue) {
        return ValueSupport.normalizeBool(value, defaultValue) ? "Y" : "N"
    }

    // Allow-list for tenant-supplied shopApiUrl. SHOP_API_URL_HOST_SUFFIXES is checked by
    // OutboundHttpPolicy.validate, which also forces https and rejects loopback / RFC1918 / link-local
    // / cloud-metadata. Audit H5.2 / H6.4 (SSRF + access-token exfil to attacker host).
    private static final List<String> SHOP_API_URL_HOST_SUFFIXES = [".myshopify.com"]

    static boolean isValidShopApiUrl(String shopApiUrl) {
        if (!shopApiUrl) return false
        return darpan.facade.common.OutboundHttpPolicy.validate(shopApiUrl, SHOP_API_URL_HOST_SUFFIXES).ok
    }

    /** Returns null if the URL is acceptable, else a human-readable error suitable for ec.message.addError. */
    static String describeShopApiUrlError(String shopApiUrl) {
        if (!shopApiUrl) return "Shop API URL is required."
        def result = darpan.facade.common.OutboundHttpPolicy.validate(shopApiUrl, SHOP_API_URL_HOST_SUFFIXES)
        return result.ok ? null : result.error
    }

    static boolean isValidApiVersion(String apiVersion) {
        return apiVersion == "unstable" || apiVersion ==~ /\d{4}-\d{2}/
    }

    static Map<String, Object> safeConfig(def ec, def config, String companyLabel = null) {
        if (config == null) return null
        String companyUserGroupId = readString(config, "companyUserGroupId")
        return [
            shopifyAuthConfigId: readString(config, "shopifyAuthConfigId"),
            description        : readString(config, "description"),
            companyUserGroupId : companyUserGroupId,
            companyLabel       : companyLabel ?: TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, companyUserGroupId),
            createdByUserId    : readString(config, "createdByUserId"),
            shopApiUrl         : readString(config, "shopApiUrl"),
            apiVersion         : readString(config, "apiVersion"),
            timeZone           : normalizeTimeZone(readValue(config, "timeZone")),
            isActive           : readString(config, "isActive") ?: DEFAULT_ACTIVE_FLAG,
            canReadOrders      : ValueSupport.normalizeBool(readString(config, "canReadOrders"), false),
            hasAccessToken     : !!normalize(readValue(config, "accessToken")),
        ]
    }

    static def findAuthConfig(def ec, Object shopifyAuthConfigId) {
        String configId = normalize(shopifyAuthConfigId)
        if (!configId) return null
        return ec.entity.find(ENTITY_NAME)
                .condition("shopifyAuthConfigId", configId)
                .useCache(false)
                .one()
    }

    static void requireTenantAuthConfigAccess(def ec, def config, String configId) {
        TenantAccessSupport.requireTenantRecordAccess(
                ec,
                config,
                "Shopify auth config '${configId}' was not found.",
                "Shopify auth config '${configId}' is not available in your active tenant."
        )
    }

    static Map<String, Object> listAuthConfigs(def ec, Object query, Object pageIndex, Object pageSize) {
        int page = Math.max(0, ValueSupport.normalizeInt(pageIndex, 0))
        int size = Math.max(1, Math.min(200, ValueSupport.normalizeInt(pageSize, 20)))
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

        List<Map<String, Object>> rows = []
        int totalCount = 0
        if (activeTenantUserGroupId) {
            def finder = authConfigFinder(ec, activeTenantUserGroupId, query)
            totalCount = Math.min(Integer.MAX_VALUE, finder.count()) as int
            if (totalCount > 0) {
                String companyLabel = TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, activeTenantUserGroupId)
                (finder.offset(page, size).limit(size).list() ?: []).each { cfg ->
                    rows.add(safeConfig(ec, cfg, companyLabel))
                }
            }
        }

        return withEnvelope(ec, [
                shopifyAuthConfigs: rows,
                pagination        : pagination(page, size, totalCount),
        ])
    }

    static Map<String, Object> getAuthConfig(def ec, Object shopifyAuthConfigId) {
        String configId = normalize(shopifyAuthConfigId)
        if (!configId) {
            ec.message.addError("Shopify auth config ID is required.")
        }

        Map<String, Object> output = [:]
        if (!ec.message.hasError()) {
            def config = findAuthConfig(ec, configId)
            requireTenantAuthConfigAccess(ec, config, configId)
            if (!ec.message.hasError()) output.shopifyAuthConfig = safeConfig(ec, config)
        }
        return withEnvelope(ec, output)
    }

    static Map<String, Object> saveAuthConfig(def ec, Object serviceContext) {
        String configId = normalize(serviceContext?.shopifyAuthConfigId)
        String descriptionValue = normalize(serviceContext?.description)
        String shopApiUrlValue = normalizeShopApiUrl(serviceContext?.shopApiUrl)
        String apiVersionValue = normalizeApiVersion(serviceContext?.apiVersion)
        String requestedTimeZoneValue = normalize(serviceContext?.timeZone)
        String accessTokenValue = normalize(serviceContext?.accessToken)
        String isActiveValue = normalizeIndicator(serviceContext?.isActive, true)
        String canReadOrdersValue = normalizeIndicator(serviceContext?.canReadOrders, false)

        if (!configId) ec.message.addError("Shopify auth config ID is required.")
        if (!shopApiUrlValue) ec.message.addError("Shop/API URL is required.")
        if (shopApiUrlValue && !isValidShopApiUrl(shopApiUrlValue)) {
            ec.message.addError("Shop/API URL must be an absolute http or https URL.")
        }
        if (!apiVersionValue) ec.message.addError("API version is required.")
        if (apiVersionValue && !isValidApiVersion(apiVersionValue)) {
            ec.message.addError("API version must use YYYY-MM format or unstable.")
        }

        def existingConfig = null
        if (!ec.message.hasError()) {
            existingConfig = findAuthConfig(ec, configId)
            if (existingConfig) requireTenantAuthConfigAccess(ec, existingConfig, configId)
        }

        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(
                    ec,
                    "Your active tenant only has view access for Shopify auth settings."
            )
        }

        String timeZoneValue = normalizeTimeZone(requestedTimeZoneValue ?: existingConfig?.timeZone)
        String timeZoneValidationError = TenantAccessSupport.validateTimeZone(timeZoneValue)
        if (timeZoneValidationError) ec.message.addError(timeZoneValidationError.replace("Timezone", "Shopify timezone"))

        if (!ec.message.hasError() && existingConfig == null && !accessTokenValue) {
            ec.message.addError("Access token is required for new Shopify auth configs.")
        }

        Map<String, Object> output = [:]
        if (!ec.message.hasError()) {
            Map<String, Object> storeMap = [
                    shopifyAuthConfigId: configId,
                    description        : descriptionValue,
                    companyUserGroupId : existingConfig?.companyUserGroupId,
                    createdByUserId    : existingConfig?.createdByUserId,
                    shopApiUrl         : shopApiUrlValue,
                    apiVersion         : apiVersionValue,
                    timeZone           : timeZoneValue,
                    accessToken        : accessTokenValue ?: existingConfig?.accessToken,
                    isActive           : isActiveValue,
                    canReadOrders      : canReadOrdersValue,
            ]

            if (existingConfig == null || !existingConfig.companyUserGroupId) {
                TenantAccessSupport.assignTenantOwnershipOnCreate(storeMap, ec)
            }

            if (!ec.message.hasError()) {
                ec.service.sync().name("store#${ENTITY_NAME}").parameters(storeMap).call()
                output.savedShopifyAuthConfig = safeConfig(ec, storeMap)
                if (!ec.message.hasError()) {
                    ec.message.addMessage("Saved Shopify auth config ${configId}.")
                }
            }
        }
        return withEnvelope(ec, output)
    }

    static Map<String, Object> deleteAuthConfig(def ec, Object shopifyAuthConfigId) {
        String configId = normalize(shopifyAuthConfigId)
        boolean deleted = false

        if (!configId) {
            ec.message.addError("Shopify auth config ID is required.")
        }

        if (!ec.message.hasError()) {
            def config = findAuthConfig(ec, configId)
            requireTenantAuthConfigAccess(ec, config, configId)
        }

        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(
                    ec,
                    "Your active tenant only has view access for Shopify auth settings."
            )
        }

        if (!ec.message.hasError()) {
            ec.service.sync()
                    .name("delete#${ENTITY_NAME}")
                    .parameters([shopifyAuthConfigId: configId])
                    .disableAuthz()
                    .call()
            deleted = true
            ec.message.addMessage("Deleted Shopify auth config ${configId}.")
        }

        return withEnvelope(ec, [
                deleted                    : deleted,
                deletedShopifyAuthConfigId : deleted ? configId : null,
        ].findAll { entry -> entry.value != null } as Map<String, Object>)
    }

    private static def authConfigFinder(def ec, String companyUserGroupId, Object query) {
        def finder = ec.entity.find(ENTITY_NAME)
                .condition("companyUserGroupId", companyUserGroupId)
                .useCache(false)
                .orderBy("description,shopifyAuthConfigId")
        String search = normalize(query)
        if (search) {
            List searchConditions = ["shopifyAuthConfigId", "description", "shopApiUrl", "apiVersion", "timeZone"].collect { String fieldName ->
                ec.entity.conditionFactory.makeCondition(fieldName, EntityCondition.LIKE, "%${search}%").ignoreCase()
            }
            finder.condition(ec.entity.conditionFactory.makeCondition(searchConditions, EntityCondition.OR))
        }
        return finder
    }

    private static Map<String, Object> pagination(int pageIndex, int pageSize, int totalCount) {
        return [
            pageIndex : pageIndex,
            pageSize  : pageSize,
            totalCount: totalCount,
            pageCount : Math.max(1, Math.ceil(totalCount / (double) pageSize) as int),
        ]
    }

    private static Map<String, Object> withEnvelope(def ec, Map<String, Object> output = [:]) {
        Map envelope = FacadeSupport.envelope(ec)
        return (output ?: [:]) + [
                ok      : envelope.ok,
                messages: envelope.messages,
                errors  : envelope.errors,
        ]
    }

    private static Object readValue(def record, String fieldName) {
        return record instanceof Map ? ((Map) record)[fieldName] : null
    }

    private static String readString(def record, String fieldName) {
        return normalize(readValue(record, fieldName))
    }
}
