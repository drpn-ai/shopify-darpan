package shopify.graphql

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-260: the ways a query could still reach customer email, phone, name or address, and the reads that must keep working. */
class ShopifyProtectedFieldGuardTests {

    @Test
    void darpansOwnReadsStillPass() {
        // The catalog's default bulk read, an order's number (`name`), the customer's id, and the connection probe.
        String bulk = ShopifyGraphqlQueryBuilder.buildBulkQuery([sourceDefinitionId: "SHOPIFY_ORDERS"]).queryDocument as String
        assertNull(ShopifyProtectedFieldGuard.refusal(bulk))
        assertNull(ShopifyProtectedFieldGuard.refusal('{ orders(first: 1) { edges { node { id name customer { id legacyResourceId } } } } }'))
        assertNull(ShopifyProtectedFieldGuard.refusal('{ shop { name myshopifyDomain } }'))
        assertNull(ShopifyProtectedFieldGuard.refusal('query orderIdLookup($ids: [ID!]!) { nodes(ids: $ids) { id } }'))
    }

    @Test
    void anAliasCannotHideAProtectedField() {
        String refusal = ShopifyProtectedFieldGuard.refusal('{ orders(first: 1) { edges { node { id contact: email } } } }')
        assertTrue(refusal?.contains("orders.edges.node.email"), refusal)
    }

    @Test
    void aCustomerIsReadByIdOnlyHoweverItIsReached() {
        // Directly, and through nodes(ids) with an inline fragment, where the field has no `customer` parent.
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ orders(first: 1) { edges { node { customer { id displayName } } } } }')?.contains("customer.displayName"))
        String viaNodes = ShopifyProtectedFieldGuard.refusal('query($ids: [ID!]!) { nodes(ids: $ids) { ... on Customer { id displayName } } }')
        assertTrue(viaNodes?.contains("nodes.displayName"), viaNodes)
    }

    @Test
    void aFragmentCannotCarryAnAddressOrAContactType() {
        String spread = ShopifyProtectedFieldGuard.refusal('''
            fragment ship on Order { shippingAddress { city } }
            { orders(first: 1) { edges { node { id ...ship } } } }''')
        assertTrue(spread?.contains("orders.edges.node.shippingAddress"), spread)
        String typed = ShopifyProtectedFieldGuard.refusal('query($ids: [ID!]!) { nodes(ids: $ids) { ... on MailingAddress { city } } }')
        assertTrue(typed?.contains("MailingAddress"), typed)
    }

    @Test
    void theBulkJobsInnerQueryIsCheckedWhereverItIsWritten() {
        String mutation = 'mutation run($query: String!) { bulkOperationRunQuery(query: $query) { bulkOperation { id } userErrors { message } } }'
        assertNull(ShopifyProtectedFieldGuard.refusal(mutation, [query: '{ orders { edges { node { id name } } } }']))
        assertTrue(ShopifyProtectedFieldGuard.refusal(mutation, [query: '{ orders { edges { node { id phone } } } }'])?.contains("orders.edges.node.phone"))
        assertFalse(ShopifyProtectedFieldGuard.refusal(mutation, [:]) == null, "a bulk run with no inner query cannot be checked, so it is refused")
        String inline = 'mutation { bulkOperationRunQuery(query: "{ customers { edges { node { email } } } }") { bulkOperation { id } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(inline)?.contains("customers.edges.node.email"))
    }

    @Test
    void anUnreadableQueryIsRefusedAndNothingIsSent() {
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ orders { edges { node { id ')?.startsWith("Darpan could not check"))
        boolean sent = false
        Map result = ShopifyGraphqlTransport.execute([shopApiUrl: "https://acme.myshopify.com/admin/api/2025-07/graphql.json", accessToken: "t"],
                '{ orders(first: 1) { edges { node { email } } } }', [:], [httpExecutor: { Map r -> sent = true; [:] }])
        assertFalse(sent, "a refused query never reaches Shopify")
        assertEquals(false, result.ok)
        assertEquals(false, result.retryable)
    }
}
