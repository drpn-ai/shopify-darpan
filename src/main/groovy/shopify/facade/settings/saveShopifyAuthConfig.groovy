import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport

String configId = ShopifyAuthConfigSupport.normalize(shopifyAuthConfigId)
String descriptionValue = ShopifyAuthConfigSupport.normalize(description)
String shopApiUrlValue = ShopifyAuthConfigSupport.normalizeShopApiUrl(shopApiUrl)
String apiVersionValue = ShopifyAuthConfigSupport.normalizeApiVersion(apiVersion)
String requestedTimeZoneValue = ShopifyAuthConfigSupport.normalize(timeZone)
String accessTokenValue = ShopifyAuthConfigSupport.normalize(accessToken)
String isActiveValue = ShopifyAuthConfigSupport.normalizeIndicator(isActive, true)
String canReadOrdersValue = ShopifyAuthConfigSupport.normalizeIndicator(canReadOrders, false)

if (!configId) ec.message.addError("Shopify auth config ID is required.")
if (!shopApiUrlValue) ec.message.addError("Shop/API URL is required.")
if (shopApiUrlValue && !ShopifyAuthConfigSupport.isValidShopApiUrl(shopApiUrlValue)) {
    ec.message.addError("Shop/API URL must be an absolute http or https URL.")
}
if (!apiVersionValue) ec.message.addError("API version is required.")
if (apiVersionValue && !ShopifyAuthConfigSupport.isValidApiVersion(apiVersionValue)) {
    ec.message.addError("API version must use YYYY-MM format or unstable.")
}

def existingConfig = null
if (!ec.message.hasError()) {
    existingConfig = ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
        .condition("shopifyAuthConfigId", configId)
        .useCache(false)
        .one()
    if (existingConfig) {
        TenantAccessSupport.requireTenantRecordAccess(
            ec,
            existingConfig,
            "Shopify auth config '${configId}' was not found.",
            "Shopify auth config '${configId}' is not available in your active tenant."
        )
    }
}

if (!ec.message.hasError()) {
    TenantAccessSupport.requireActiveTenantWriteAccess(
        ec,
        "Your active tenant only has view access for Shopify auth settings."
    )
}

String timeZoneValue = ShopifyAuthConfigSupport.normalizeTimeZone(requestedTimeZoneValue ?: existingConfig?.timeZone)
String timeZoneValidationError = ShopifyAuthConfigSupport.validateTimeZone(timeZoneValue)
if (timeZoneValidationError) ec.message.addError(timeZoneValidationError.replace("Timezone", "Shopify timezone"))

if (!ec.message.hasError() && existingConfig == null && !accessTokenValue) {
    ec.message.addError("Access token is required for new Shopify auth configs.")
}

if (!ec.message.hasError()) {
    Map<String, Object> storeMap = ShopifyAuthConfigSupport.buildStoreMap(existingConfig, [
        shopifyAuthConfigId: configId,
        description        : descriptionValue,
        shopApiUrl         : shopApiUrlValue,
        apiVersion         : apiVersionValue,
        timeZone           : timeZoneValue,
        accessToken        : accessTokenValue,
        isActive           : isActiveValue,
        canReadOrders      : canReadOrdersValue,
    ])

    if (existingConfig == null || !existingConfig.companyUserGroupId) {
        TenantAccessSupport.assignTenantOwnershipOnCreate(storeMap, ec)
    }

    if (!ec.message.hasError()) {
        ec.service.sync().name("store#${ShopifyAuthConfigSupport.ENTITY_NAME}").parameters(storeMap).call()
        savedShopifyAuthConfig = ShopifyAuthConfigSupport.safeConfig(ec, storeMap)
        if (!ec.message.hasError()) {
            ec.message.addMessage("Saved Shopify auth config ${configId}.")
        }
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
