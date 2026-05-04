import darpan.facade.common.FacadeSupport
import shopify.graphql.ShopifySourceCatalog

String sourceId = ShopifySourceCatalog.normalizeSourceDefinitionId(sourceDefinitionId)
if (!sourceId) {
    ec.message.addError("Shopify source definition ID is required.")
}

if (!ec.message.hasError()) {
    sourceDefinition = ShopifySourceCatalog.getSource(sourceId, apiVersion)
    if (sourceDefinition == null) {
        String apiVersionValue = ShopifySourceCatalog.normalizeApiVersion(apiVersion)
        ec.message.addError(apiVersionValue ?
            "Shopify source ${sourceId} is not available for API version ${apiVersionValue}." :
            "Shopify source ${sourceId} was not found.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
