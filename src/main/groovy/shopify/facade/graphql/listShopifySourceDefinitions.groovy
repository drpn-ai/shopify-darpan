import darpan.facade.common.FacadeSupport
import shopify.graphql.ShopifySourceCatalog

try {
    sourceDefinitions = ShopifySourceCatalog.listSources(apiVersion)
} catch (Exception e) {
    ec.message.addError(e.message)
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
