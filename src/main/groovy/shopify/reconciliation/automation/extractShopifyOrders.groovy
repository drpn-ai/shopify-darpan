import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

List<String> outputErrors = []
List<String> outputWarnings = []

Closure<String> normalize = { Object value -> FacadeSupport.normalize(value) }
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
Closure<String> renderSearchDateTime = { Object value ->
    String text = normalize(value)
    return "'${text.replace("'", "\\'")}'"
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
    return normalizedRecord
}
Closure<String> shopifyOrderSelection = {
    return """id
      legacyResourceId
      name
      createdAt
      updatedAt
      processedAt
      email
      cancelledAt
      totalPrice
      displayFinancialStatus
      displayFulfillmentStatus
      currencyCode
      currentTotalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      currentTotalTaxSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      totalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      subtotalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }"""
}
Closure<String> buildBulkQueryDocument = { String searchQuery ->
    return """query DarpanShopifyOrdersByDateWindow {
  orders(query: "${ShopifyBulkOperationClient.escapeGraphqlString(searchQuery)}", sortKey: CREATED_AT) {
    edges {
      node {
        ${shopifyOrderSelection.call()}
      }
    }
  }
}"""
}
Closure<Map<String, Object>> authConfigForTransport = { def config ->
    return [
            shopApiUrl : config.shopApiUrl,
            apiVersion : config.apiVersion,
            accessToken: config.accessToken,
    ]
}

String configIdValue = normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = normalize(companyUserGroupId)
Timestamp windowStartValue = toTimestamp(windowStart, "windowStart")
Timestamp windowEndValue = toTimestamp(windowEnd, "windowEnd")
if (!configIdValue) outputErrors.add("Shopify auth config ID is required.")

def authConfig = null
if (!outputErrors) {
    authConfig = ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
            .condition("shopifyAuthConfigId", configIdValue)
            .disableAuthz()
            .useCache(false)
            .one()
    if (companyUserGroupIdValue) {
        if (!authConfig) {
            ec.message.addError("Shopify auth config '${configIdValue}' was not found.")
        } else if (normalize(authConfig.companyUserGroupId) != companyUserGroupIdValue) {
            ec.message.addError("Shopify auth config '${configIdValue}' is not available in this automation tenant.")
        }
    } else {
        TenantAccessSupport.requireTenantRecordAccess(
                ec,
                authConfig,
                "Shopify auth config '${configIdValue}' was not found.",
                "Shopify auth config '${configIdValue}' is not available in your active tenant."
        )
    }
    if (authConfig && (authConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
        ec.message.addError("Shopify auth config ${configIdValue} is inactive.")
    }
    if (authConfig && (authConfig.canReadOrders ?: "N").toString().equalsIgnoreCase("N")) {
        ec.message.addError("Shopify auth config ${configIdValue} is not enabled for order reads.")
    }
    if (ec.message.hasError()) outputErrors.addAll((ec.message.getErrors() ?: []) as List<String>)
}

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

String sourceTimeZone = normalize(authConfig?.timeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
Map<String, Object> sourceWindow = ReconciliationApiWindowSupport.normalizeCalendarWindow(
        windowStartValue,
        windowEndValue,
        sourceTimeZone
)
Timestamp sourceWindowStart = (Timestamp) sourceWindow.windowStartDate
Timestamp sourceWindowEnd = (Timestamp) sourceWindow.windowEndDate
String windowStartText = formatWindow(sourceWindowStart)
String windowEndText = formatWindow(sourceWindowEnd)
String searchQuery = "created_at:>=${renderSearchDateTime.call(windowStartText)} created_at:<${renderSearchDateTime.call(windowEndText)}"
String bulkQueryDocument = buildBulkQueryDocument.call(searchQuery)
Integer bulkMaxPollAttempts = Math.max(1, FacadeSupport.normalizeInt(maxBulkPollAttempts, ShopifyBulkOperationClient.DEFAULT_MAX_POLL_ATTEMPTS))
Integer bulkPollDelayMillis = Math.max(0, FacadeSupport.normalizeInt(bulkPollIntervalMillis, ShopifyBulkOperationClient.DEFAULT_POLL_INTERVAL_MILLIS))
Integer bulkStartRetryAttemptCount = Math.max(0, FacadeSupport.normalizeInt(bulkStartRetryAttempts, ShopifyBulkOperationClient.DEFAULT_START_RETRY_ATTEMPTS))
Integer bulkStartRetryDelayMillisValue = Math.max(0, FacadeSupport.normalizeInt(bulkStartRetryDelayMillis, ShopifyBulkOperationClient.DEFAULT_START_RETRY_DELAY_MILLIS))

Map<String, Object> bulkOperationResult = ShopifyBulkOperationClient.runQuery(authConfigForTransport.call(authConfig), bulkQueryDocument, [
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
        extractedRecordCount  : records.size(),
].findAll { it.value != null } as Map<String, Object>
DataManagerSupport.writeText(ec, fileLocation as String, JsonOutput.toJson([
        metadata: requestMetadata,
        records : records,
]))
warnings = outputWarnings
errors = []
