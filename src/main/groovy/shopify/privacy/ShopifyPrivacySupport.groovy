package shopify.privacy

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.facade.settings.ShopifyInstallSupport
import shopify.facade.settings.ShopifyTokenSupport

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-272 — what Darpan does for Shopify's three privacy requests (customers/data_request, customers/redact,
 * shop/redact). Internal only: DAR-BE-273's HMAC-checked webhook receiver calls these; nothing here is reachable
 * from outside.
 *
 * <p><strong>What is in scope.</strong> Raw extracts are deleted after 7 days (DAR-BE-271) and result files after 15,
 * both inside Shopify's 30-day deadline, so only database rows need action: the audit finding records Darpan read from
 * Shopify (AuditFindingRecord with systemEnumId SHOPIFY; recordData holds the full source record, and findings from
 * before DAR-BE-260 may hold email and address). Records read from HotWax or NetSuite are the merchant's other
 * systems' data and are not touched.</p>
 *
 * <p><strong>Tenants.</strong> The store is found through ShopifyAuthConfig.shopApiUrl, across every tenant that
 * connects it; each tenant is handled separately and every query carries that tenant's companyUserGroupId
 * explicitly (findGlobalUnscoped skips the active-tenant filter). An unknown store changes nothing.</p>
 */
class ShopifyPrivacySupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyPrivacySupport)

    static final String RECORD_ENTITY = "darpan.audit.AuditFindingRecord"
    static final String SHOPIFY_SYSTEM = "SHOPIFY"

    /** The tenants whose Shopify connections point at this store (name.myshopify.com). */
    static Set<String> tenantsForShop(def ec, Object rawShopDomain) {
        String shop = normalize(rawShopDomain)?.toLowerCase(Locale.ROOT)
        if (!ShopifyInstallSupport.isValidShopDomain(shop)) return [] as Set<String>
        List configs = (TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                        "privacy request: find every tenant connecting this store; each is then handled with its own tenant condition")
                ?.selectField("companyUserGroupId")?.selectField("shopApiUrl")
                ?.useCache(false)?.list() ?: []) as List
        return configs.findAll { ShopifyTokenSupport.shopDomainOf(it.shopApiUrl) == shop }
                .collect { normalize(it.companyUserGroupId) }.findAll { it } as Set<String>
    }

    /** The tenants connected to this store through one Darpan app (shop/redact from that app's webhook). */
    static Set<String> tenantsForShopViaApp(def ec, String shop, String appId) {
        if (!ShopifyInstallSupport.isValidShopDomain(shop) || !appId) return [] as Set<String>
        List configs = (TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                        "privacy shop redact: tenants connected through the signing app; each then handled with its own tenant condition")
                ?.condition("shopifyAppId", appId)?.useCache(false)?.list() ?: []) as List
        return configs.findAll { ShopifyTokenSupport.shopDomainOf(it.shopApiUrl) == shop }
                .collect { normalize(it.companyUserGroupId) }.findAll { it } as Set<String>
    }

    /** An order id as Darpan may have stored it: the numeric id and its GraphQL global id. */
    static Set<String> orderIdForms(Object rawIds) {
        Collection ids = rawIds instanceof Collection ? (Collection) rawIds : (rawIds != null ? [rawIds] : [])
        Set<String> out = [] as Set<String>
        ids.each { Object raw ->
            String id = normalize(raw)
            if (!id) return
            String numeric = id.startsWith("gid://shopify/Order/") ? id.substring("gid://shopify/Order/".length()) : id
            out.add(numeric)
            out.add("gid://shopify/Order/" + numeric)
        }
        return out
    }

    private static def recordFind(def ec, String tenantId, Set<String> idForms, String why) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, RECORD_ENTITY, why)
                ?.condition("companyUserGroupId", tenantId)
                ?.condition("systemEnumId", SHOPIFY_SYSTEM)
                ?.useCache(false)
        if (idForms != null) {
            find = find?.condition(ec.entity.conditionFactory.makeCondition([
                    ec.entity.conditionFactory.makeCondition("recordId", org.moqui.entity.EntityCondition.IN, idForms),
                    ec.entity.conditionFactory.makeCondition("internalId", org.moqui.entity.EntityCondition.IN, idForms)],
                    org.moqui.entity.EntityCondition.OR))
        }
        return find
    }

    /** customers/data_request: the Shopify records Darpan holds for these orders, per tenant. */
    static Map<String, Object> reportCustomerData(def ec, Object shopDomain, Object orderIds) {
        Set<String> forms = orderIdForms(orderIds)
        Map<String, List<Map>> byTenant = [:]
        tenantsForShop(ec, shopDomain).each { String tenantId ->
            if (!forms) { byTenant[tenantId] = []; return }
            byTenant[tenantId] = ((recordFind(ec, tenantId, forms, "privacy data request: explicit companyUserGroupId condition applied")
                    ?.list() ?: []) as List).collect { [findingId: it.findingId, recordType: it.recordType, recordId: it.recordId,
                                                      internalId: it.internalId, recordStatus: it.recordStatus, recordData: it.recordData] }
        }
        if (!byTenant) logger.info("Shopify data request for an unknown store; nothing held")
        return [shopDomain: normalize(shopDomain), tenants: byTenant]
    }

    /** customers/redact: delete the Shopify records for these orders, per tenant. */
    static Map<String, Object> redactCustomer(def ec, Object shopDomain, Object orderIds) {
        Set<String> forms = orderIdForms(orderIds)
        Map<String, Long> deleted = [:]
        tenantsForShop(ec, shopDomain).each { String tenantId ->
            deleted[tenantId] = forms ? (recordFind(ec, tenantId, forms, "privacy customer redact: explicit companyUserGroupId condition applied")
                    ?.deleteAll() ?: 0L) as Long : 0L
        }
        if (!deleted) logger.info("Shopify customer redact for an unknown store; nothing held")
        else logger.info("Shopify customer redact deleted Shopify finding records per tenant: {}", deleted)
        return [shopDomain: normalize(shopDomain), deletedByTenant: deleted]
    }

    /**
     * shop/redact (48 hours after uninstall): clear the store's tokens on every connection to it, and delete the tenant's
     * Shopify finding records. Findings do not say which store they came from, so when the tenant still connects
     * ANOTHER Shopify store the records are kept and the tenant is listed for a person to review rather than deleting
     * the other store's audit data.
     */
    static Map<String, Object> redactShop(def ec, Object rawShopDomain, Object rawAppId = null) {
        String shop = normalize(rawShopDomain)?.toLowerCase(Locale.ROOT)
        String appId = normalize(rawAppId)
        Map<String, Object> out = [shopDomain: shop, connectionsCleared: 0, deletedByTenant: [:], needsReview: []]
        Set<String> tenants = appId ? tenantsForShopViaApp(ec, shop, appId) : tenantsForShop(ec, shop)
        if (!tenants) { logger.info("Shopify shop redact for an unknown store; nothing held"); return out }
        tenants.each { String tenantId ->
            List configs = ((TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                            "privacy shop redact: this tenant's connections, explicit companyUserGroupId condition applied")
                    ?.condition("companyUserGroupId", tenantId)?.useCache(false)?.list() ?: []) as List)
            // With an app: only that app's connections to the store are cleared; anything else still live for the tenant
            // (a pasted token to the same store, or another store) means its findings may still be in use.
            List thisStore = configs.findAll { ShopifyTokenSupport.shopDomainOf(it.shopApiUrl) == shop && (!appId || normalize(it.shopifyAppId) == appId) }
            boolean otherStoreActive = configs.any { !(it in thisStore) && normalize(it.isActive) != "N" }
            thisStore.each { def config ->
                config.setAll([accessToken: null, refreshToken: null, accessTokenExpiresDate: null, refreshTokenExpiresDate: null, isActive: "N"])
                ShopifyInstallSupport.withAuthzLifted(ec) { -> config.update() }
                out.connectionsCleared = (out.connectionsCleared as int) + 1
            }
            if (otherStoreActive) {
                (out.needsReview as List).add(tenantId)
                logger.warn("Shopify shop redact: tenant {} still connects another Shopify store; its Shopify finding records were kept for review", tenantId)
            } else {
                (out.deletedByTenant as Map)[tenantId] = (recordFind(ec, tenantId, null,
                        "privacy shop redact: explicit companyUserGroupId condition applied")?.deleteAll() ?: 0L) as Long
            }
        }
        return out
    }
}
