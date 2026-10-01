import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.reconciliation.automation.ShopifyOrderPaymentSignal
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifySourceCatalog

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

List<String> outputErrors = []
List<String> outputWarnings = []

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Closure<Timestamp> toTimestamp = { Object rawValue, String label ->
    if (rawValue == null) {
        outputErrors.add("${label} is required.")
        return null
    }
    if (rawValue instanceof Timestamp) return (Timestamp) rawValue
    if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
    if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
    if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
    if (rawValue instanceof OffsetDateTime) return Timestamp.from(((OffsetDateTime) rawValue).toInstant())
    if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)
    if (rawValue instanceof LocalDate) return Timestamp.from(((LocalDate) rawValue).atStartOfDay().toInstant(ZoneOffset.UTC))

    String text = normalize(rawValue)
    if (!text) {
        outputErrors.add("${label} is required.")
        return null
    }
    if (text ==~ /-?\d+/) return new Timestamp(Long.parseLong(text))

    List<Closure<Timestamp>> parsers = [
            { String value -> Timestamp.from(Instant.parse(value)) },
            { String value -> Timestamp.from(OffsetDateTime.parse(value).toInstant()) },
            { String value -> Timestamp.from(ZonedDateTime.parse(value).toInstant()) },
            { String value -> Timestamp.valueOf(value) },
            { String value -> Timestamp.valueOf(LocalDateTime.parse(value)) },
            { String value -> Timestamp.from(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)) },
    ]
    for (Closure<Timestamp> parser : parsers) {
        try {
            return parser.call(text)
        } catch (Exception ignored) {
        }
    }

    outputErrors.add("${label} must be a Timestamp, Date, ISO-8601 value, SQL timestamp text, or epoch milliseconds.")
    return null
}
Closure<String> formatWindow = { Timestamp timestamp -> timestamp?.toInstant()?.toString() }
Closure<String> safeFileName = { Object rawName, String fallback ->
    String safeName = DataManagerSupport.safeToken(rawName, fallback)
    return safeName.toLowerCase(Locale.ROOT).endsWith(".json") ? safeName : "${safeName}.json"
}
Closure<String> safeJsonlFileName = { Object rawName, String fallback ->
    String safeName = DataManagerSupport.safeToken(rawName, fallback)
    if (safeName.toLowerCase(Locale.ROOT).endsWith(".jsonl")) return safeName
    return safeName.replaceFirst(/(?i)\.json$/, "") + ".jsonl"
}
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
    return normalizedRecord
}
String configIdValue = normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = normalize(companyUserGroupId)
Timestamp windowStartValue = toTimestamp(windowStart, "windowStart")
Timestamp windowEndValue = toTimestamp(windowEnd, "windowEnd")
if (!configIdValue) outputErrors.add("Shopify auth config ID is required.")

def authConfig = null
if (!outputErrors) {
    authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
            disableAuthz                : true,
            companyUserGroupId          : companyUserGroupIdValue,
            requiredEndpointSystemEnumId: "SHOPIFY",
    ])
    if (ec.message.hasError()) outputErrors.addAll((ec.message.getErrors() ?: []) as List<String>)
}

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

// DAR-BE-063: tenant source filters run CLIENT-side per order (Shopify search syntax knows nothing of
// them). Parsed BEFORE the bulk operation is submitted, so a malformed rule fails in seconds.
List<Map<String, Object>> parsedSourceFilters
try {
    parsedSourceFilters = SourceFilterSupport.parseRules(sourceFilters)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Configured exclusion rules are invalid."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

String sourceTimeZone = normalize(authConfig?.timeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
boolean preserveWindowInstantsValue = ValueSupport.normalizeBool(preserveWindowInstants, false)
Map<String, Object> sourceWindow = preserveWindowInstantsValue ?
        ReconciliationApiWindowSupport.preserveExactWindow(windowStartValue, windowEndValue, sourceTimeZone) :
        ReconciliationApiWindowSupport.normalizeCalendarWindow(windowStartValue, windowEndValue, sourceTimeZone)
Timestamp sourceWindowStart = (Timestamp) sourceWindow.windowStartDate
Timestamp sourceWindowEnd = (Timestamp) sourceWindow.windowEndDate
String windowStartText = formatWindow(sourceWindowStart)
String windowEndText = formatWindow(sourceWindowEnd)
// The field selection and search syntax come from the shared source catalog / query builder so the
// bulk extraction cannot drift from the catalog contract. apiVersion is intentionally not passed:
// extraction must keep working for configs saved with versions outside the catalog's setup list.
Map<String, Object> builtBulkQuery
try {
    builtBulkQuery = ShopifyGraphqlQueryBuilder.buildBulkQuery([
            sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDERS,
            operationName     : "DarpanShopifyOrdersByDateWindow",
            filters           : [createdAtFrom: windowStartText, createdAtTo: windowEndText],
    ])
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
String searchQuery = (String) builtBulkQuery.searchQuery
String bulkQueryDocument = (String) builtBulkQuery.queryDocument
Integer bulkMaxPollAttempts = Math.max(1, ValueSupport.normalizeInt(maxBulkPollAttempts, ShopifyBulkOperationClient.DEFAULT_MAX_POLL_ATTEMPTS))
Integer bulkPollDelayMillis = Math.max(0, ValueSupport.normalizeInt(bulkPollIntervalMillis, ShopifyBulkOperationClient.DEFAULT_POLL_INTERVAL_MILLIS))
Integer bulkStartRetryAttemptCount = Math.max(0, ValueSupport.normalizeInt(bulkStartRetryAttempts, ShopifyBulkOperationClient.DEFAULT_START_RETRY_ATTEMPTS))
Integer bulkStartRetryDelayMillisValue = Math.max(0, ValueSupport.normalizeInt(bulkStartRetryDelayMillis, ShopifyBulkOperationClient.DEFAULT_START_RETRY_DELAY_MILLIS))

Map<String, Object> bulkOperationResult = ShopifyBulkOperationClient.runQuery([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], bulkQueryDocument, [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
        maxPollAttempts     : bulkMaxPollAttempts,
        pollIntervalMillis  : bulkPollDelayMillis,
        startRetryAttempts  : bulkStartRetryAttemptCount,
        startRetryDelayMillis: bulkStartRetryDelayMillisValue,
])

List<Map<String, Object>> records = []
if (bulkOperationResult.ok == false) {
    outputErrors.addAll(((List) (bulkOperationResult.errors ?: ["Shopify bulk operation request failed."]))
            .collect { Object error -> normalize(error) }
            .findAll { String error -> error })
} else {
    records = ((List) (bulkOperationResult.records ?: []))
            .findAll { Object record -> record instanceof Map }
            .collect { Object record -> normalizeShopifyOrderRecord.call((Map<String, Object>) record) } as List<Map<String, Object>>
}
Map filteredOrders = SourceFilterSupport.applyToRecords(records, parsedSourceFilters)
int extractedRecordCount = records.size()
records = (List<Map<String, Object>>) filteredOrders.records
if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = records.size()
    return
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = normalize(outputLocation) ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec,
        automationExecutionId ?: automationId ?: configIdValue,
        timestamp
)
String outputFileName = safeFileName(
        fileName ?: "shopify-orders-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
        "shopify-orders.json"
)
String rawJsonlText = bulkOperationResult?.jsonlText?.toString() ?: ""
String rawJsonlLocation = null
String rawJsonlFileName = null
if (rawJsonlText) {
    rawJsonlFileName = safeJsonlFileName(outputFileName, "shopify-orders.jsonl")
    rawJsonlLocation = DataManagerSupport.childLocation(outputBaseLocation, rawJsonlFileName)
    DataManagerSupport.writeText(ec, rawJsonlLocation, rawJsonlText)
}
fileName = outputFileName
fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
fileTypeEnumId = "DftJson"
// DAR-UI-044 / DAR-BE-063: what the source filters dropped, beside the extract, for the conclude pass.
Map excludedCollector = (Map) filteredOrders.excludedCollector
if (excludedCollector && ((excludedCollector.total ?: 0) as int) > 0) {
    try {
        DataManagerSupport.writeText(ec, DataManagerSupport.childLocation(outputBaseLocation,
                ExcludedRecordsSidecar.fileNameFor(outputFileName)), ExcludedRecordsSidecar.toJson(excludedCollector, outputFileName))
    } catch (Exception sidecarError) {
        outputWarnings.add("Excluded-records sidecar not written: ${sidecarError.message}".toString())
    }
}
// DAR-BE-064 canary: the presence OPP rule (<=) cannot see a missing signal, so report it here.
Map paymentSummary = ShopifyOrderPaymentSignal.summarize(records)
String paymentCanary = ShopifyOrderPaymentSignal.canaryWarning(paymentSummary)
if (paymentCanary) outputWarnings.add(paymentCanary)
recordCount = records.size()
dataAvailable = records.size() > 0
requestMetadata = [
        sourceType            : "SHOPIFY_GRAPHQL_ORDERS",
        extractionMode        : "BULK_OPERATION_DATE_FILTER",
        graphqlExecutionMode  : "BULK_OPERATION",
        shopifyAuthConfigId   : configIdValue,
        automationId          : normalize(automationId),
        fileSide              : normalize(fileSide),
        systemEnumId          : normalize(systemEnumId),
        sourceTypeEnumId      : normalize(sourceTypeEnumId),
        sourceTimeZone        : sourceWindow.timeZone,
        calendarDateNormalized: sourceWindow.calendarDateNormalized,
        exactWindowPreserved  : sourceWindow.exactWindowPreserved,
        filterFields          : ["created_at"],
        searchQueries         : [searchQuery],
        windowStartUtc        : windowStartText,
        windowEndUtc          : windowEndText,
        bulkOperation         : bulkOperationResult.bulkOperation,
        bulkPollCount         : bulkOperationResult.pollCount,
        bulkStatusHistory     : bulkOperationResult.statusHistory,
        bulkStartRetryCount   : bulkOperationResult.startRetryCount,
        bulkStartRetryHistory : bulkOperationResult.startRetryHistory,
        bulkJsonlLineCount    : bulkOperationResult.jsonlLineCount,
        rawJsonlFileName      : rawJsonlFileName,
        rawJsonlLocation      : rawJsonlLocation,
        extractedRecordCount  : extractedRecordCount,
        keptRecordCount       : records.size(),
        paymentTransactionYCount: paymentSummary.paymentTransactionYCount,
        paymentTransactionNCount: paymentSummary.paymentTransactionNCount,
        // Absent (not an empty block) when no rules are configured, matching the line-units extract.
        filters               : filteredOrders.configuredExclusions != null ?
                [configuredExclusions: filteredOrders.configuredExclusions] : null,
].findAll { it.value != null } as Map<String, Object>
DataManagerSupport.writeText(ec, fileLocation as String, JsonOutput.toJson([
        metadata: requestMetadata,
        records : records,
]))
warnings = outputWarnings
errors = []
