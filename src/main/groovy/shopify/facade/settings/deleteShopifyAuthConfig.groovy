import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport

String configId = ShopifyAuthConfigSupport.normalize(shopifyAuthConfigId)
deleted = false

if (!configId) {
    ec.message.addError("Shopify auth config ID is required.")
}

def config = null
if (!ec.message.hasError()) {
    config = ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
        .condition("shopifyAuthConfigId", configId)
        .useCache(false)
        .one()
    TenantAccessSupport.requireTenantRecordAccess(
        ec,
        config,
        "Shopify auth config '${configId}' was not found.",
        "Shopify auth config '${configId}' is not available in your active tenant."
    )
}

if (!ec.message.hasError()) {
    TenantAccessSupport.requireActiveTenantWriteAccess(
        ec,
        "Your active tenant only has view access for Shopify auth settings."
    )
}

if (!ec.message.hasError()) {
    ec.service.sync()
        .name("delete#${ShopifyAuthConfigSupport.ENTITY_NAME}")
        .parameters([shopifyAuthConfigId: configId])
        .disableAuthz()
        .call()
    deleted = true
    deletedShopifyAuthConfigId = configId
    ec.message.addMessage("Deleted Shopify auth config ${configId}.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
