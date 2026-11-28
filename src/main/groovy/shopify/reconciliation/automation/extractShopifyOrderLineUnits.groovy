// DAR-BE-050. One record per ORDER LINE UNIT, not per order. Structurally this is
// extractShopifyOrders with the record-assembly step replaced: submitting, polling, downloading
// and writing the bulk operation are shared with it through ShopifyExtractSupport.
import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifyCallTally
import shopify.graphql.ShopifySourceCatalog
// Required, not optional: Moqui extract scripts carry no package declaration, so an unimported
// class reference compiles fine (Groovy treats it as a dynamic property) and fails only at RUNTIME.
// A green build proves nothing about it. extractShopifyReturnRefs imports its support class for
// the same reason.
import shopify.reconciliation.automation.ShopifyExtractSupport
import shopify.reconciliation.automation.ShopifyOrderLineUnitSupport

import java.sql.Timestamp

List<String> outputWarnings = []
// DAR-BE-320: count every call this extract makes to Shopify (requestMetadata.shopifyCalls).
ShopifyCallTally.start()

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Map<String, Object> resolved = ShopifyExtractSupport.resolveConfigAndWindow(ec, shopifyAuthConfigId, companyUserGroupId,
        windowStart, windowEnd, "SHOPIFY_ORDER_LINE_UNITS")
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
Map<String, Object> builtBulkQuery
try {
    builtBulkQuery = ShopifyExtractSupport.buildCreatedAtBulkQuery(ShopifySourceCatalog.SHOPIFY_ORDER_LINE_UNITS,
            "DarpanShopifyOrderLineUnitsByDateWindow", (String) window.startText, (String) window.endText)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
// DAR-BE-318: no source filters are read or applied here. The extract writes every unit and Spark Keep (darpan core) sets aside
// what a run's rules reject, so this service declares no sourceFilters parameter (the Keep gate reads that declaration).
String searchQuery = (String) builtBulkQuery.searchQuery
String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
File outputDirectory = ShopifyExtractSupport.outputDirectory(ec, outputBaseLocation)
Map<String, Object> bulkOperationResult = ShopifyExtractSupport.runBulkQuery(authConfig,
        (String) builtBulkQuery.queryDocument, context, outputDirectory)

Map assembledUnits = [orphanLineItemCount: 0, droppedZeroQuantity: 0, unknownStateLineCount: 0, clampedStateLineCount: 0]
ShopifyExtractSupport.ExtractFileWriter extractWriter = new ShopifyExtractSupport.ExtractFileWriter(outputDirectory)
try {
    if (bulkOperationResult.ok == false) {
        outputErrors.addAll(ShopifyExtractSupport.bulkErrors(bulkOperationResult))
    } else {
        // extractShopifyOrders maps each JSONL line straight to a record, because every line there IS
        // an order. Here the LineItem child lines are the records, so the flat JSONL is assembled into
        // one entry per UNIT. A child line need not sit next to its order, so the file is read twice
        // (orders, then lines) and each unit goes straight to the extract file: neither the lines nor
        // the units of a long window are held (DAR-OPS-029 S42a; the month OOM in DAR-BE-162).
        File jsonlFile = (File) bulkOperationResult.jsonlFile
        assembledUnits = ShopifyOrderLineUnitSupport.streamUnits(
                { Closure visit -> ShopifyBulkOperationClient.eachJsonlRecord(jsonlFile, visit) },
                { Map unit -> extractWriter.add(unit) })
    }
    if (outputErrors) {
        errors = outputErrors
        warnings = outputWarnings
        dataAvailable = false
        recordCount = extractWriter.recordCount
        return
    }

    String outputFileName = ShopifyExtractSupport.safeFileName(
            fileName ?: "shopify-order-line-units-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
            "shopify-order-line-units.json"
    )
    Map<String, String> rawJsonl = ShopifyExtractSupport.moveRawJsonl(ec, bulkOperationResult, outputBaseLocation,
            outputFileName, "shopify-order-line-units.jsonl")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    fileTypeEnumId = "DftJson"
    recordCount = extractWriter.recordCount
    dataAvailable = extractWriter.recordCount > 0
    Map<String, Object> metadata = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDER_LINE_UNITS",
            configIdValue, context, window, searchQuery, bulkOperationResult, rawJsonl)
    metadata.putAll([
            extractedRecordCount : extractWriter.recordCount,
            // Reported ALWAYS, not only when non-zero: a unit dropped here vanishes from the Shopify
            // side, and the anti-join then reports it as a genuine difference. An operator reading a
            // run must be able to tell a real finding from a parse loss without opening the JSONL.
            orphanLineItemCount  : assembledUnits.orphanLineItemCount,
            droppedZeroQuantity  : assembledUnits.droppedZeroQuantity,
            // Lines with no currentQuantity / unfulfilledQuantity: their units carry unitState UNKNOWN
            // and an include rule on a state drops them. Always present for the same reason as above.
            unknownStateLineCount: assembledUnits.unknownStateLineCount,
            clampedStateLineCount: assembledUnits.clampedStateLineCount,
    ])
    metadata.shopifyCalls = ShopifyCallTally.stop()
    requestMetadata = metadata.findAll { it.value != null } as Map<String, Object>
    int orphanLineItemCount = (assembledUnits.orphanLineItemCount ?: 0) as int
    if (orphanLineItemCount > 0) {
        outputWarnings.add("${orphanLineItemCount} line item(s) had no parent order in the bulk JSONL and were not compared.".toString())
    }
    int unknownStateLineCount = (assembledUnits.unknownStateLineCount ?: 0) as int
    if (unknownStateLineCount > 0) {
        outputWarnings.add("${unknownStateLineCount} line item(s) had no current or unfulfilled quantity; their units carry unitState UNKNOWN.".toString())
    }
    extractWriter.finish(ec, fileLocation as String, requestMetadata)
    warnings = outputWarnings
    errors = []
} finally {
    extractWriter.abort()
    ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
}
