import darpan.facade.common.FacadeSupport
import shopify.graphql.ShopifyGraphqlQueryBuilder

try {
    builtQuery = ShopifyGraphqlQueryBuilder.buildQuery([
        sourceDefinitionId  : sourceDefinitionId,
        apiVersion          : apiVersion,
        selectedFieldPaths  : selectedFieldPaths,
        filters             : filters,
        pageSize            : pageSize,
        afterCursor         : afterCursor,
        reverse             : reverse,
        connectionPageSizes : connectionPageSizes,
    ])
} catch (Exception e) {
    ec.message.addError(e.message)
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
