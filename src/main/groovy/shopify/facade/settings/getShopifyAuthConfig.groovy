import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport

String configId = FacadeSupport.normalize(shopifyAuthConfigId)
if (!configId) {
    ec.message.addError("Shopify auth config ID is required.")
}

if (!ec.message.hasError()) {
    def config = ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
        .condition("shopifyAuthConfigId", configId)
        .useCache(false)
        .one()
    TenantAccessSupport.requireTenantRecordAccess(
        ec,
        config,
        "Shopify auth config '${configId}' was not found.",
        "Shopify auth config '${configId}' is not available in your active tenant."
    )
    if (!ec.message.hasError()) {
        shopifyAuthConfig = ShopifyAuthConfigSupport.safeConfig(ec, config)
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
