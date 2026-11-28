import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifyCallTally
import shopify.graphql.ShopifySourceCatalog
// Moqui extract scripts carry no package declaration, so an unimported class reference compiles
// fine (Groovy treats it as a dynamic property) and fails only at RUNTIME. Import every support class.
import shopify.reconciliation.automation.ShopifyExtractSupport
import shopify.reconciliation.automation.ShopifyOrderPaymentSignal
import shopify.reconciliation.automation.ShopifyOrderLinesNesting

import java.sql.Timestamp

List<String> outputWarnings = []
// DAR-BE-320: count every call this extract makes to Shopify (requestMetadata.shopifyCalls).
ShopifyCallTally.start()

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Closure<Map<String, Object>> normalizeShopifyOrderRecord = { Map<String, Object> record ->
    Map<String, Object> normalizedRecord = new LinkedHashMap<>(record ?: [:])
    String gid = normalize(normalizedRecord.id)
    String legacyId = normalize(normalizedRecord.legacyResourceId)
    if (!legacyId && gid) {
        def matcher = gid =~ /(\d+)$/
        if (matcher.find()) legacyId = matcher.group(1)
    }
    if (gid) normalizedRecord.shopifyGid = gid
    if (legacyId) {
        normalizedRecord.legacyResourceId = legacyId
        normalizedRecord.id = legacyId
    }
    // DAR-BE-064: derived before the source filters run, so a filter and the presence OPP rule can both read it.
    normalizedRecord.hasPaymentTransaction = ShopifyOrderPaymentSignal.hasPaymentTransaction(normalizedRecord.transactions)
    // DAR-BE-153: the transaction ids HotWax keeps as OPP manualRefNum; compared against HotWax paymentRefNums.
    normalizedRecord.paymentTransactionIds = ShopifyOrderPaymentSignal.paymentTransactionIds(normalizedRecord.transactions)
    // DAR-BE-153: every successful transaction id, so each HotWax OPP manualRefNum can be found among them.
    normalizedRecord.transactionIds = ShopifyOrderPaymentSignal.transactionIds(normalizedRecord.transactions)
    // DAR-BE-125 (R20): Y only when Shopify fulfilled the whole order; compared against HotWax isCompleted.
    normalizedRecord.isFulfilled = normalize(normalizedRecord.displayFulfillmentStatus) == "FULFILLED" ? "Y" : "N"
    // DAR-BE-251: units ordered, compared against HotWax itemQuantity (the sum of its item rows' quantities).
    if (normalizedRecord.subtotalLineItemsQuantity != null) normalizedRecord.itemQuantity = normalize(normalizedRecord.subtotalLineItemsQuantity)
    return normalizedRecord
}
Map<String, Object> resolved = ShopifyExtractSupport.resolveConfigAndWindow(ec, shopifyAuthConfigId, companyUserGroupId,
        windowStart, windowEnd, "SHOPIFY")
List<String> outputErrors = (List<String>) resolved.errors
String configIdValue = (String) resolved.configIdValue
def authConfig = resolved.authConfig

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

Map<String, Object> window = ShopifyExtractSupport.resolveSourceWindow(ec, authConfig,
        (Timestamp) resolved.windowStartValue, (Timestamp) resolved.windowEndValue, preserveWindowInstants)
Timestamp sourceWindowStart = (Timestamp) window.start
Timestamp sourceWindowEnd = (Timestamp) window.end
// DAR-BE-156: a read by key (an audit re-checking its older open findings) searches those order ids, not the window.
List<String> idKeys = (lookupKeys instanceof List) ? ((List) lookupKeys).collect { normalize(it) }.findAll { it }.unique() as List<String> : []
if (idKeys && !(normalize(lookupKeyField) in [null, "id", "legacyResourceId"])) {
    errors = ["Shopify orders can be read by order id only, not by ${lookupKeyField}.".toString()]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
// DAR-BE-368: a read asked for its lines (withLineItems) selects them and puts each back under its order.
boolean readLines = withLineItems == true
String ordersSource = readLines ? ShopifySourceCatalog.SHOPIFY_ORDERS_WITH_LINES : ShopifySourceCatalog.SHOPIFY_ORDERS
List<Map<String, Object>> builtBulkQueries = []
try {
    builtBulkQueries = idKeys ?
            idKeys.collate(ShopifyExtractSupport.LOOKUP_IDS_PER_BULK).collect { List<String> chunk ->
                ShopifyExtractSupport.buildIdsBulkQuery(ordersSource, "DarpanShopifyOrdersByIds", chunk)
            } :
            [ShopifyExtractSupport.buildOrdersWindowBulkQuery(ordersSource,
                    "DarpanShopifyOrdersByDateWindow", (String) window.startText, (String) window.endText,
                    normalize(windowFieldName))]
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
String searchQuery = idKeys ? "id IN (${idKeys.size()} ids)".toString() : (String) builtBulkQueries[0].searchQuery
String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
File outputDirectory = ShopifyExtractSupport.outputDirectory(ec, outputBaseLocation)

// DAR-BE-162: each bulk result downloads to a file and is read one order at a time: normalized and written straight to
// the extract. DAR-BE-318: no filter runs here. The extract holds every order; Spark Keep sets aside what the tenant's
// rules say (this service no longer declares sourceFilters, so the Keep gate places the side in Spark).
// Holding the result as text and then as maps OOMed a 2 GB JVM on a month.
Map<String, Integer> paymentSummary = [paymentTransactionYCount: 0, paymentTransactionNCount: 0]
ShopifyExtractSupport.ExtractFileWriter extractWriter = new ShopifyExtractSupport.ExtractFileWriter(outputDirectory)
Map<String, Object> bulkOperationResult = null
int extractedRecordCount = 0
Closure<Void> writeOrder = { Map<String, Object> record ->
    extractedRecordCount++
    Map<String, Object> order = normalizeShopifyOrderRecord.call(record)
    ShopifyOrderPaymentSignal.tally(paymentSummary, order)
    extractWriter.add(order)
}
int orphanLines = 0
try {
    for (Map<String, Object> builtBulkQuery : builtBulkQueries) {
        // A read by key runs one bulk query per chunk; only the last chunk's raw JSONL is kept beside the extract.
        ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
        bulkOperationResult = ShopifyExtractSupport.runBulkQuery(authConfig, (String) builtBulkQuery.queryDocument,
                context, outputDirectory)
        if (bulkOperationResult.ok == false) {
            outputErrors.addAll(ShopifyExtractSupport.bulkErrors(bulkOperationResult))
            break
        }
        if (readLines) {
            ShopifyOrderLinesNesting nesting = new ShopifyOrderLinesNesting(writeOrder)
            ShopifyBulkOperationClient.eachJsonlRecord((File) bulkOperationResult.jsonlFile) { Map<String, Object> record -> nesting.add(record) }
            nesting.finish()
            orphanLines += nesting.orphanLines
        } else {
            ShopifyBulkOperationClient.eachJsonlRecord((File) bulkOperationResult.jsonlFile, writeOrder)
        }
    }
    if (outputErrors) {
        errors = outputErrors
        warnings = outputWarnings
        dataAvailable = false
        recordCount = extractWriter.recordCount
        return
    }

    String outputFileName = ShopifyExtractSupport.safeFileName(
            fileName ?: "shopify-orders-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
            "shopify-orders.json"
    )
    Map<String, String> rawJsonl = ShopifyExtractSupport.moveRawJsonl(ec, bulkOperationResult, outputBaseLocation,
            outputFileName, "shopify-orders.jsonl")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    fileTypeEnumId = "DftJson"
    // DAR-BE-064 canary: the presence OPP rule (<=) cannot see a missing signal, so report it here.
    String paymentCanary = ShopifyOrderPaymentSignal.canaryWarning(paymentSummary)
    if (paymentCanary) outputWarnings.add(paymentCanary)
    if (orphanLines) outputWarnings.add("${orphanLines} Shopify order line(s) arrived away from their order and were left off it.".toString())
    recordCount = extractWriter.recordCount
    dataAvailable = extractWriter.recordCount > 0
    // DAR-OPS-029 S42a: the field the search actually matched, so the metadata says what was read.
    String filterField = idKeys ? "id" : (normalize(windowFieldName) == "updatedAt" ? "updated_at" : "created_at")
    Map<String, Object> metadata = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", configIdValue,
            context, window, searchQuery, bulkOperationResult, rawJsonl, filterField)
    metadata.putAll([
            extractedRecordCount    : extractedRecordCount,
            keptRecordCount         : extractWriter.recordCount,
            paymentTransactionYCount: paymentSummary.paymentTransactionYCount,
            paymentTransactionNCount: paymentSummary.paymentTransactionNCount,
            withLineItems           : readLines ? true : null,
            orphanLines             : readLines ? orphanLines : null,
    ])
    metadata.shopifyCalls = ShopifyCallTally.stop()
    requestMetadata = metadata.findAll { it.value != null } as Map<String, Object>
    extractWriter.finish(ec, fileLocation as String, requestMetadata)
    warnings = outputWarnings
    errors = []
} finally {
    extractWriter.abort()
    ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
}
