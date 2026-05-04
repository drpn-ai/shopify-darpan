package shopify.facade.settings

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport

import java.net.URI

class ShopifyAuthConfigSupport {
    static final String ENTITY_NAME = "darpan.shopify.ShopifyAuthConfig"
    static final String DEFAULT_ACTIVE_FLAG = "Y"
    static final String DEFAULT_ORDER_READ_FLAG = "N"
    static final String DEFAULT_TIME_ZONE = TenantAccessSupport.DEFAULT_TIME_ZONE

    static String normalize(Object value) {
        return FacadeSupport.normalize(value)
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

    static String validateTimeZone(String timeZone) {
        return TenantAccessSupport.validateTimeZone(timeZone)
    }

    static String normalizeIndicator(Object value, boolean defaultValue) {
        return FacadeSupport.normalizeBool(value, defaultValue) ? "Y" : "N"
    }

    static boolean isValidShopApiUrl(String shopApiUrl) {
        if (!shopApiUrl) return false
        try {
            URI uri = new URI(shopApiUrl)
            return uri.scheme in ["http", "https"] && !!uri.host
        } catch (Exception ignored) {
            return false
        }
    }

    static boolean isValidApiVersion(String apiVersion) {
        return apiVersion == "unstable" || apiVersion ==~ /\d{4}-\d{2}/
    }

    static Map<String, Object> safeConfig(def ec, def config) {
        if (config == null) return null
        String companyUserGroupId = readString(config, "companyUserGroupId")
        return [
            shopifyAuthConfigId: readString(config, "shopifyAuthConfigId"),
            description        : readString(config, "description"),
            companyUserGroupId : companyUserGroupId,
            companyLabel       : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, companyUserGroupId),
            createdByUserId    : readString(config, "createdByUserId"),
            shopApiUrl         : readString(config, "shopApiUrl"),
            apiVersion         : readString(config, "apiVersion"),
            timeZone           : normalizeTimeZone(readValue(config, "timeZone")),
            isActive           : readString(config, "isActive") ?: DEFAULT_ACTIVE_FLAG,
            canReadOrders      : FacadeSupport.normalizeBool(readString(config, "canReadOrders"), false),
            hasAccessToken     : !!normalize(readValue(config, "accessToken")),
        ]
    }

    static Map<String, Object> paginate(List<Map<String, Object>> rows, int pageIndex, int pageSize) {
        int totalCount = rows.size()
        int fromIndex = Math.min(pageIndex * pageSize, totalCount)
        int toIndex = Math.min(fromIndex + pageSize, totalCount)
        return [
            rows      : rows.subList(fromIndex, toIndex),
            pagination: [
                pageIndex : pageIndex,
                pageSize  : pageSize,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) pageSize) as int),
            ],
        ]
    }

    static boolean matchesSearch(Map<String, Object> row, String searchLower) {
        if (!searchLower) return true
        return [
            row.shopifyAuthConfigId,
            row.description,
            row.shopApiUrl,
            row.apiVersion,
            row.timeZone,
        ].any { value -> value?.toString()?.toLowerCase()?.contains(searchLower) }
    }

    static Map<String, Object> buildStoreMap(def existingConfig, Map<String, Object> values) {
        Map<String, Object> storeMap = [
            shopifyAuthConfigId: values.shopifyAuthConfigId,
            description        : values.description,
            companyUserGroupId : existingConfig?.companyUserGroupId,
            createdByUserId    : existingConfig?.createdByUserId,
            shopApiUrl         : values.shopApiUrl,
            apiVersion         : values.apiVersion,
            timeZone           : values.timeZone,
            accessToken        : values.accessToken ?: existingConfig?.accessToken,
            isActive           : values.isActive,
            canReadOrders      : values.canReadOrders,
        ]
        return storeMap
    }

    private static Object readValue(def record, String fieldName) {
        if (record == null) return null
        if (record instanceof Map) return record[fieldName]
        if (record.metaClass.respondsTo(record, "get", String)) return record.get(fieldName)
        return record."${fieldName}"
    }

    private static String readString(def record, String fieldName) {
        return normalize(readValue(record, fieldName))
    }
}
