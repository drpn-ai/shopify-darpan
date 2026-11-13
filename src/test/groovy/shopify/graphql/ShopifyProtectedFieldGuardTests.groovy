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
    void everyBulkJobIsCheckedAndOnlyOneOperationGoesOut() {
        // Two aliased bulk jobs: the second must not ride past the first's clean query.
        String twoJobs = 'mutation { a: bulkOperationRunQuery(query: "{ orders { edges { node { id } } } }") { bulkOperation { id } } ' +
                'b: bulkOperationRunQuery(query: "{ orders { edges { node { email } } } }") { bulkOperation { id } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(twoJobs)?.contains("orders.edges.node.email"))
        assertTrue(ShopifyProtectedFieldGuard.refusal('query a { shop { id } } query b { orders(first: 1) { edges { node { email } } } }')
                ?.contains("2 operations"))
    }

    @Test
    void darpanSendsNoMutationButTheBulkRead() {
        // A webhook would carry full order payloads, customer included, to someone else's URL.
        assertTrue(ShopifyProtectedFieldGuard.refusal('mutation { webhookSubscriptionCreate(topic: ORDERS_CREATE, webhookSubscription: {callbackUrl: "https://x.example"}) { userErrors { message } } }')
                ?.contains("webhookSubscriptionCreate"))
        assertTrue(ShopifyProtectedFieldGuard.refusal('mutation { bulkOperationRunMutation(mutation: "mutation { x }", stagedUploadPath: "p") { bulkOperation { id } } }')
                ?.contains("bulkOperationRunMutation"))
        assertTrue(ShopifyProtectedFieldGuard.refusal('subscription { x }') != null)
        // A bulk job's inner document must itself be a query.
        String nested = 'mutation { bulkOperationRunQuery(query: "mutation { bulkOperationRunQuery(query: \\"{ shop { id } }\\") { bulkOperation { id } } }") { bulkOperation { id } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(nested)?.contains("only reads"))
    }

    @Test
    void otherRoadsToAPersonAreClosed() {
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ customerByIdentifier(identifier: {id: "gid://shopify/Customer/1"}) { id note } }') != null)
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ customerSegmentMembers(first: 1, query: "") { edges { node { id lastOrderId } } } }') != null)
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ productVariants(first: 1) { edges { node { displayName } } } }') != null)
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ orders(first: 1) { edges { node { transactions { paymentDetails { ... on CardPaymentDetails { company } } } } } } }')
                ?.contains("CardPaymentDetails"))
        // A connection of customers read by id only is fine.
        assertNull(ShopifyProtectedFieldGuard.refusal('{ customers(first: 5) { pageInfo { hasNextPage } edges { cursor node { id } } } }'))
    }

    @Test
    void aSearchCannotTestWhetherAPersonExists() {
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ customers(first: 1, query: "email:someone@example.com") { edges { node { id } } } }')?.contains("email:someone@example.com"))
        assertTrue(ShopifyProtectedFieldGuard.refusal('{ orders(first: 1, query: "someone@example.com") { edges { node { id } } } }') != null, "a bare word matches names and emails")
        assertTrue(ShopifyProtectedFieldGuard.refusal('query($q: String) { orders(first: 1, query: $q) { edges { node { id } } } }', [q: "shipping_city:Boston"]) != null)
        assertTrue(ShopifyProtectedFieldGuard.refusal('mutation($query: String!) { bulkOperationRunQuery(query: $query) { bulkOperation { id } } }',
                [query: '{ orders(query: "-first_name:x") { edges { node { id } } } }']) != null, "the bulk job's own searches are checked too")
        // The searches Darpan writes keep working.
        ["updated_at:>='2026-11-01T00:00:00Z' AND -return_status:no_return",
         "subject_type:Order AND (action:return_created OR action:refund_created) created_at:>='2026-11-01T00:00:00Z'",
         "(id:18959057551491 OR id:18959060828291)", "created_at:<1970-01-02", "status:any", ""].each { String q ->
            assertNull(ShopifyProtectedFieldGuard.refusal('query($q: String) { orders(first: 1, query: $q) { edges { node { id } } } }', [q: q]), q)
        }
    }

    @Test
    void aFragmentAtTheRootCannotSmuggleAMutationOrABulkJob() {
        String smuggled = 'mutation { bulkOperationRunQuery(query: "{ shop { id } }") { bulkOperation { id } } ' +
                '... on Mutation { webhookSubscriptionCreate(topic: ORDERS_CREATE, webhookSubscription: {callbackUrl: "https://x.example"}) { userErrors { message } } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(smuggled)?.contains("webhookSubscriptionCreate"), "a root fragment is still the root")
        String fragmentBulk = 'mutation { ... on Mutation { bulkOperationRunQuery(query: "{ orders { edges { node { email } } } }") { bulkOperation { id } } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(fragmentBulk)?.contains("orders.edges.node.email"), "a bulk job inside a fragment is still checked")
    }

    @Test
    void aVariablesDefaultIsCheckedWhenNoValueIsSent() {
        String withDefault = 'query($q: String = "email:someone@example.com") { customers(first: 1, query: $q) { edges { node { id } } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(withDefault, [:]) != null, "Shopify would search by the default")
        assertNull(ShopifyProtectedFieldGuard.refusal(withDefault, [q: "updated_at:>='2026-11-01'"]), "a sent value replaces the default")
        assertTrue(ShopifyProtectedFieldGuard.refusal('query($q: String = 5) { orders(first: 1, query: $q) { edges { node { id } } } }', [:])?.contains("not a plain string"))
        assertTrue(ShopifyProtectedFieldGuard.refusal('query($q: String) { orders(first: 1, query: $q) { edges { node { id } } } }', [q: ["email:x"]])?.contains("not a plain string"))
        String bulkDefault = 'mutation($query: String! = "{ orders { edges { node { phone } } } }") { bulkOperationRunQuery(query: $query) { bulkOperation { id } } }'
        assertTrue(ShopifyProtectedFieldGuard.refusal(bulkDefault, [:])?.contains("orders.edges.node.phone"), "a bulk job's default query is checked")
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
