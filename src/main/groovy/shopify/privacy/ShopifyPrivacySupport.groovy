package shopify.privacy

import darpan.common.DarpanEntityConstants
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shopify.facade.settings.ShopifyInstallSupport
import shopify.facade.settings.ShopifyTokenSupport

import java.sql.Timestamp

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
 * systems' data and are not touched. Kept copies and ID-index rows (darpan's dataset store) are kept past any retention, so
 * both redacts drop the store's (OC92, OC232), and so does deleting the connection (ShopifyAuthConfigSupport.deleteAuthConfig),
 * since a redact finds a store only through its connections.</p>
 *
 * <p><strong>Tenants.</strong> The store is found through ShopifyAuthConfig.shopApiUrl, across every tenant that
 * connects it and every tenant ever granted one of those connections (OC204: a peer reads through the owner's config id);
 * each tenant is handled separately and every query carries that tenant's companyUserGroupId
 * explicitly (findGlobalUnscoped skips the active-tenant filter). An unknown store changes nothing.</p>
 */
class ShopifyPrivacySupport {
    private static final Logger logger = LoggerFactory.getLogger(ShopifyPrivacySupport)

    static final String RECORD_ENTITY = "darpan.audit.AuditFindingRecord"
    static final String SHOPIFY_SYSTEM = "SHOPIFY"

    static final String CONFIG_TYPE = SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH

    /** The store's connections (name.myshopify.com; through the signing app when one is named): configId -> owning tenant. */
    private static Map<String, String> storeConnections(def ec, Object rawShopDomain, String appId = null) {
        String shop = normalize(rawShopDomain)?.toLowerCase(Locale.ROOT)
        if (!ShopifyInstallSupport.isValidShopDomain(shop)) return [:]
        def find = TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                "privacy request: every connection to this store (through the signing app when one is named); each tenant is then handled with its own condition")
                ?.selectField("shopifyAuthConfigId")?.selectField("companyUserGroupId")?.selectField("shopApiUrl")?.useCache(false)
        if (appId) find = find?.condition("shopifyAppId", appId)
        Map<String, String> out = [:]
        ((find?.list() ?: []) as List).findAll { ShopifyTokenSupport.shopDomainOf(it.shopApiUrl) == shop }.each {
            String id = normalize(it.shopifyAuthConfigId), owner = normalize(it.companyUserGroupId)
            if (id && owner) out[id] = owner
        }
        return out
    }

    /**
     * OC204: every tenant that read the store through these connections: their owners, and every tenant ever granted one
     * (ConfigTenantAccess), a revoked grant too, since a former peer's records and kept copies are still the store's data.
     */
    static Set<String> tenantsOf(def ec, Map<String, String> connections) {
        Set<String> tenants = new LinkedHashSet<String>(connections.values())
        if (connections) {
            ((TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.CONFIG_TENANT_ACCESS,
                    "privacy request: every tenant ever granted the store's connections, revoked grants included")
                    ?.condition("configTypeEnumId", CONFIG_TYPE)?.condition("configId", EntityCondition.IN, connections.keySet())
                    ?.useCache(false)?.list() ?: []) as List).each { tenants.add(normalize(it.tenantUserGroupId)) }
        }
        tenants.remove(null)
        return tenants
    }

    /**
     * OC92/OC232: one tenant's kept copies and ID-index rows read through these connections (drop#ConnectionKeptData, which
     * conditions on the tenant explicitly; authz is lifted so an owner's connection delete reaches a peer's rows). A copy
     * mid-refresh refuses with an error on ec.message: the caller fails and is retried. Returns how many copies went.
     */
    static int dropKeptData(def ec, String tenantId, Collection<String> configIds) {
        if (!tenantId || !configIds) return 0
        Map dropped = (Map) ShopifyInstallSupport.withAuthzLifted(ec) { ->
            ec.service.sync().name("dataset.DatasetServices.drop#ConnectionKeptData")
                    .parameters([companyUserGroupId: tenantId, sourceConfigIds: configIds as List]).call()
        }
        return ec.message.hasError() ? 0 : ((dropped?.datasetsDropped ?: 0) as int)
    }

    /**
     * OC232 (DAR-BE-420, Shopify): a deleted connection's kept data, in its owner and in every tenant ever granted it. shop/redact
     * finds a store only through its connections, so once the connection is gone it could not erase them. Stops at the first
     * refusal (left on ec.message).
     */
    static Map<String, Integer> dropConnectionKeptData(def ec, String configId, String ownerTenantId) {
        Map<String, Integer> dropped = [:]
        for (String tenantId : tenantsOf(ec, [(configId): ownerTenantId])) {
            dropped[tenantId] = dropKeptData(ec, tenantId, [configId])
            if (ec.message.hasError()) break
        }
        return dropped
    }

    /** The tenants holding the store's data: owners and peers of its connections (OC204). */
    private static Set<String> tenantsForShop(def ec, Object rawShopDomain, String appId = null) {
        return tenantsOf(ec, storeConnections(ec, rawShopDomain, appId))
    }

    /** OC204: whether this tenant still reads another Shopify connection through a live grant (one not to this store). */
    private static boolean liveGrantElsewhere(def ec, String tenantId, Collection<String> storeConfigIds) {
        Timestamp now = ec.user.nowTimestamp
        return ((TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.CONFIG_TENANT_ACCESS,
                "privacy shop redact: this tenant's other live shared Shopify connections, explicit tenantUserGroupId condition")
                ?.condition("configTypeEnumId", CONFIG_TYPE)?.condition("tenantUserGroupId", tenantId)?.useCache(false)?.list() ?: []) as List)
                .any { !storeConfigIds.contains(normalize(it.configId)) && (it.thruDate == null || ((Timestamp) it.thruDate).after(now)) }
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

    /**
     * customers/redact: delete the Shopify records for these orders, per tenant. OC232: the store's kept copies and ID index hold
     * the customer's orders too (ids, amounts, the customer's id; no name, email or address since DAR-BE-260), and a copy is a
     * file a record cannot be cut from, so every kept copy read through the store goes, as on shop/redact. A copy is a cache:
     * the next read rebuilds it from Shopify, which has redacted the customer itself.
     */
    static Map<String, Object> redactCustomer(def ec, Object shopDomain, Object orderIds) {
        Set<String> forms = orderIdForms(orderIds)
        Map<String, Long> deleted = [:]
        Map<String, Integer> keptDropped = [:]
        Map<String, String> connections = storeConnections(ec, shopDomain)
        tenantsOf(ec, connections).each { String tenantId ->
            deleted[tenantId] = forms ? (recordFind(ec, tenantId, forms, "privacy customer redact: explicit companyUserGroupId condition applied")
                    ?.deleteAll() ?: 0L) as Long : 0L
            keptDropped[tenantId] = forms ? dropKeptData(ec, tenantId, connections.keySet()) : 0
            if (ec.message.hasError()) throw new IllegalStateException(ec.message.errorsString)
        }
        if (!deleted) logger.info("Shopify customer redact for an unknown store; nothing held")
        else logger.info("Shopify customer redact deleted Shopify finding records per tenant: {}", deleted)
        return [shopDomain: normalize(shopDomain), deletedByTenant: deleted, keptCopiesDropped: keptDropped]
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
        Map<String, Object> out = [shopDomain: shop, connectionsCleared: 0, deletedByTenant: [:], keptCopiesDropped: [:], needsReview: []]
        Map<String, String> connections = storeConnections(ec, shop, appId)
        Set<String> tenants = tenantsOf(ec, connections)
        if (!tenants) { logger.info("Shopify shop redact for an unknown store; nothing held"); return out }
        // DAR-BE-284: records and kept data that say which connection read them go exactly when it was this store's. OC204: for
        // every tenant that read through it, the owner and every peer (a peer's records carry the owner's config id).
        List<String> storeConfigIds = connections.keySet() as List<String>
        tenants.each { String tenantId ->
            List configs = ((TenantScopedFinder.findGlobalUnscoped(ec, ShopifyInstallSupport.CONFIG_ENTITY,
                            "privacy shop redact: this tenant's connections, explicit companyUserGroupId condition applied")
                    ?.condition("companyUserGroupId", tenantId)?.useCache(false)?.list() ?: []) as List)
            // With an app: only that app's connections to the store are cleared; anything else still live for the tenant
            // (a pasted token to the same store, or another store) means its findings may still be in use.
            List thisStore = configs.findAll { connections.containsKey(normalize(it.shopifyAuthConfigId)) }
            boolean otherStoreActive = configs.any { !(it in thisStore) && normalize(it.isActive) != "N" } ||
                    liveGrantElsewhere(ec, tenantId, storeConfigIds)
            thisStore.each { def config ->
                config.setAll([accessToken: null, refreshToken: null, accessTokenExpiresDate: null, refreshTokenExpiresDate: null, isActive: "N"])
                ShopifyInstallSupport.withAuthzLifted(ec) { -> config.update() }
                out.connectionsCleared = (out.connectionsCleared as int) + 1
            }
            // OC92: the store's kept copies and the ID index rows its connections wrote go too; no retention clears them.
            // A copy mid-refresh refuses, so the request fails here and Shopify retries it.
            if (storeConfigIds) {
                (out.keptCopiesDropped as Map)[tenantId] = dropKeptData(ec, tenantId, storeConfigIds)
                if (ec.message.hasError()) throw new IllegalStateException(ec.message.errorsString)
            }
            long deleted = storeConfigIds ? ((recordFind(ec, tenantId, null,
                    "privacy shop redact: the store's connections, explicit companyUserGroupId condition applied")
                    ?.condition("sourceConfigId", org.moqui.entity.EntityCondition.IN, storeConfigIds)?.deleteAll() ?: 0L) as Long) : 0L
            // Records written before 2026-11-13 do not say: delete them only when the tenant has no other live
            // connection that could have read them; otherwise keep them for a person to review.
            def legacy = recordFind(ec, tenantId, null, "privacy shop redact: legacy records, explicit companyUserGroupId condition applied")
                    ?.condition("sourceConfigId", null)
            if (otherStoreActive) {
                if (legacy?.count()) {
                    (out.needsReview as List).add(tenantId)
                    logger.warn("Shopify shop redact: tenant {} still has another live Shopify connection; its Shopify finding records from before connections were recorded were kept for review", tenantId)
                }
            } else {
                deleted += (legacy?.deleteAll() ?: 0L) as Long
            }
            (out.deletedByTenant as Map)[tenantId] = deleted
        }
        return out
    }
}
