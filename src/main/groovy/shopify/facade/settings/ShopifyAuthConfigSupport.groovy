package shopify.facade.settings

import darpan.common.ValueSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.SharedConfigGrantSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceEndpointAccessSupport

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

    // DAR-BE-005 seam C: a bare ec.entity.find here is silently filtered by
    // DARPAN_ACTIVE_COMPANY_SCOPE (SecuritySeedData.xml DARPAN_SCOPE_SHOPIFY_AUTH), so a
    // foreign-owned row (shared or not) would be invisible before any access check runs. That
    // silence is exactly what let saveAuthConfig's null-existingConfig branch reassign a foreign
    // row's ownership to the caller's own tenant (a live cross-tenant credential hijack).
    // findGlobalUnscoped bypasses that filter; every caller below gates the returned record with
    // requireTenantAuthConfigAccess / SharedConfigAccessSupport.canActiveTenantUseConfig before
    // using it — the read and the gate are two different steps, and skipping either reopens the
    // hole. Do not revert this to a bare ec.entity.find.
    private static final String FIND_AUTH_CONFIG_REASON =
            "findAuthConfig existence check; every caller gates the result with " +
            "requireTenantAuthConfigAccess / SharedConfigAccessSupport.canActiveTenantUseConfig " +
            "before use (DAR-BE-005)"

    static def findAuthConfig(def ec, Object shopifyAuthConfigId) {
        String configId = normalize(shopifyAuthConfigId)
        if (!configId) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, ENTITY_NAME, FIND_AUTH_CONFIG_REASON)
                .condition("shopifyAuthConfigId", configId)
                .useCache(false)
                .one()
    }

    /**
     * DAR-BE-005 read/edit standing: owner OR shared peer, both allowed — a member tenant may see
     * and edit every field of a config shared with it (decision 3). Used by get and save, NOT by
     * delete (delete stays owner-only; see deleteAuthConfig's own three-way check).
     *
     * <p>Collapsed per the oracle-closing pattern Task 6 established for the reference validators:
     * a caller with no legitimate standing (not owner, not a peer) gets the SAME "was not found"
     * text whether the config is missing or foreign, instead of the old two distinguishable
     * messages that let any authenticated caller probe arbitrary ids for existence.</p>
     */
    static void requireTenantAuthConfigAccess(def ec, def config, String configId) {
        if (config == null || !SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, config)) {
            ec.message.addError("Shopify auth config '${configId}' was not found.")
        }
    }

    /**
     * Resolve a Shopify auth config and enforce the shared usability contract in one place:
     * record exists, the caller's tenant may use it, isActive=Y, and the source permission flag
     * is enabled. Errors go to ec.message; the found record (or null) is returned either way.
     *
     * opts.companyUserGroupId — trusted automation owner tenant; checked instead of the user's
     *     active tenant (automation executions run without a user session tenant).
     * opts.requiredEndpointSystemEnumId — endpoint the caller is about to use, e.g. SHOPIFY_RETURN_REFS
     *     (matching ShopifySourceCatalog's requiredEndpointSystemEnumId for that source). Checked via
     *     SourceEndpointAccessSupport.isEndpointEnabled. Absent/blank means no endpoint check — the
     *     compiler cannot catch a caller naming the WRONG endpoint, which is why each seam has its own
     *     gate test.
     * opts.disableAuthz — accepted for source-compatibility with existing automation callers, but
     *     has no effect: the read now always goes through TenantScopedFinder.findGlobalUnscoped
     *     (DAR-BE-005 B3), which disables authz unconditionally. A caller may still pass it.
     */
    static def requireUsableAuthConfig(def ec, Object shopifyAuthConfigId, Map opts = [:]) {
        String configId = normalize(shopifyAuthConfigId)
        if (!configId) {
            ec.message.addError("Shopify auth config ID is required.")
            return null
        }

        // DAR-BE-005 B3 — was the last bare ec.entity.find in this file (findAuthConfig above was
        // already fixed). Same seam C hazard: a bare find is authz-enabled and silently filtered by
        // DARPAN_ACTIVE_COMPANY_SCOPE, so a peer's shared config (or the GraphQL path calling this
        // with configId only) would be invisible before either gate below ever runs.
        // findGlobalUnscoped bypasses that filter; both branches below already gate the result
        // correctly (automation branch via canTenantUseConfig, interactive branch via
        // requireTenantAuthConfigAccess) — only the read needed to change.
        def config = TenantScopedFinder.findGlobalUnscoped(ec, ENTITY_NAME,
                        "requireUsableAuthConfig existence check; canTenantUseConfig / " +
                        "requireTenantAuthConfigAccess gates owner-or-shared access immediately " +
                        "after (DAR-BE-005)")
                .condition("shopifyAuthConfigId", configId)
                .useCache(false)
                .one()

        String trustedCompanyUserGroupId = normalize(opts?.companyUserGroupId)
        if (trustedCompanyUserGroupId) {
            // DAR-BE-005 B1 — was strict owner-only (a straight companyUserGroupId != trustedTenant
            // comparison), so a peer tenant could see and select a shared config from the settings
            // list (Task 7) and then have every automation run against it fail here. Widened to the
            // same owner-or-shared decision the interactive branch below already uses, via the
            // explicit-tenant overload (the automation tenant is server-derived from the
            // already-gated automation record, not a session's active tenant, so it cannot call
            // canActiveTenantUseConfig). Collapsed onto requireTenantAuthConfigAccess's identical
            // "was not found" text — a caller with no standing must not be able to tell a missing
            // config from a foreign, un-shared one.
            if (config == null || !SharedConfigAccessSupport.canTenantUseConfig(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, config, trustedCompanyUserGroupId)) {
                ec.message.addError("Shopify auth config '${configId}' was not found.")
            }
        } else {
            requireTenantAuthConfigAccess(ec, config, configId)
        }

        if (config != null && !ec.message.hasError()) {
            if ((readValue(config, "isActive") ?: "Y").toString().equalsIgnoreCase("N")) {
                ec.message.addError("Shopify auth config ${configId} is inactive.")
            }
            // Per-endpoint enablement replaces the single canReadOrders flag. Both Shopify sources
            // used to name canReadOrders, so enabling orders silently permitted return-refs; they are
            // now independently gated.
            String requiredEndpoint = normalize(opts?.requiredEndpointSystemEnumId)
            if (requiredEndpoint && !SourceEndpointAccessSupport.isEndpointEnabled(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, configId, requiredEndpoint)) {
                ec.message.addError("Shopify auth config ${configId} is not enabled for ${requiredEndpoint}.")
            }
        }
        return config
    }

    static Map<String, Object> listAuthConfigs(def ec, Object query, Object pageIndex, Object pageSize) {
        int page = Math.max(0, ValueSupport.normalizeInt(pageIndex, 0))
        int size = Math.max(1, Math.min(200, ValueSupport.normalizeInt(pageSize, 20)))
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

        // DAR-BE-005: owned rows plus rows shared to this tenant. listAccessibleConfigRows has no
        // server-side search/pagination (it returns every accessible row, owned and shared alike),
        // so both are applied here in Groovy — the same shape listOmsRestSourceConfigs.groovy uses.
        List<Map<String, Object>> rows = []
        if (activeTenantUserGroupId) {
            List configs = SharedConfigAccessSupport.listAccessibleConfigRows(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH)
            rows = configs.collect { cfg ->
                safeConfig(ec, cfg) + [isShared: readValue(cfg, "companyUserGroupId") != activeTenantUserGroupId]
            }
        }

        String search = normalize(query)?.toLowerCase()
        List<Map<String, Object>> filtered = search ? rows.findAll { Map<String, Object> row ->
            [row.shopifyAuthConfigId, row.description, row.shopApiUrl, row.apiVersion, row.timeZone].any {
                it?.toString()?.toLowerCase()?.contains(search)
            }
        } : rows

        int totalCount = filtered.size()
        return withEnvelope(ec, [
                shopifyAuthConfigs: PaginationSupport.pageRows(filtered, page, size),
                pagination        : PaginationSupport.pagination(page, size, totalCount),
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
            if (!ec.message.hasError()) {
                String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
                output.shopifyAuthConfig = safeConfig(ec, config) +
                        [isShared: readValue(config, "companyUserGroupId") != activeTenantUserGroupId]
            }
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
        if (shopApiUrlValue) {
            String shopApiUrlError = describeShopApiUrlError(shopApiUrlValue)
            if (shopApiUrlError) ec.message.addError("Shop/API URL: ${shopApiUrlError}".toString())
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
                String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
                output.savedShopifyAuthConfig = safeConfig(ec, storeMap) +
                        [isShared: normalize(readValue(storeMap, "companyUserGroupId")) != activeTenantUserGroupId]
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
            boolean isOwner = config != null && TenantAccessSupport.canAccessTenantRecord(ec, config)

            // Delete stays owner-only (decision 3's asymmetry): a peer may edit every field of a
            // shared config but must not delete the row its peers depend on — shopifyAuthConfigId
            // has no DB FK for ConfigTenantAccess to cascade through. A peer who wants out uses
            // revoke#ConfigTenantAccess instead.
            if (config != null && !isOwner && SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, config)) {
                ec.message.addError("Shopify auth config '${configId}' is shared from another " +
                        "tenant. Stop sharing it instead of deleting it.")
            } else if (!isOwner) {
                // DAR-BE-005 oracle collapse: a nonexistent config and one this tenant has no
                // standing on (not owner, not a peer) must be indistinguishable to the caller —
                // the pre-Task-7 behavior threw a distinguishing "not available in your active
                // tenant" message for the foreign-owned case here.
                //
                // Task 13 invariant: this "not found" path (config == null included) sets
                // ec.message.hasError(), which gates the SourceConfigEndpointAccess cleanup below
                // the same as it gates the entity delete — so a config already gone (deleted
                // out-of-band, bypassing this method) never has its access rows swept here. That is
                // safe today only because SourceConfigEndpointAccess is new in this plan (no
                // pre-existing orphans can exist) and both real delete paths route through this
                // method. An out-of-band delete of the config row (e.g. direct DB deletion) would
                // still orphan its access rows.
                ec.message.addError("Shopify auth config '${configId}' was not found.")
            } else if (SharedConfigGrantSupport.hasActiveGrants(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, configId)) {
                // Task 9: even the owner cannot delete a config that still has active grants.
                // configId is polymorphic with no DB FK, so a delete cascades nothing and every
                // peer tenant's automation would break at run time with a confusing "not found"
                // instead of failing loudly here. This branch is only reachable when isOwner is
                // true — it runs strictly AFTER the standing check above.
                ec.message.addError("Shopify auth config '${configId}' is shared with other tenants. " +
                        "Stop sharing it with every tenant before deleting it.")
            }
        }

        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(
                    ec,
                    "Your active tenant only has view access for Shopify auth settings."
            )
        }

        if (!ec.message.hasError()) {
            // SourceConfigEndpointAccess.configId is polymorphic, so nothing cascades. Delete explicitly, or a
            // later config reusing this id silently inherits this one's disabled endpoints.
            ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                    .condition("configTypeEnumId", SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH)
                    .condition("configId", configId)
                    .deleteAll()

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
