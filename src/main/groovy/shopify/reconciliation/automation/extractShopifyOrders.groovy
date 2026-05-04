import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

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
Closure<String> buildQueryDocument = {
    return """query DarpanShopifyOrdersByDateWindow(\$search: String, \$after: String) {
  orders(first: 100, after: \$after, query: \$search) {
    edges {
      cursor
      node {
        ${shopifyOrderSelection.call()}
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}"""
}
Closure<Map<String, Object>> executeGraphql = { def config, String queryDocument, Map<String, Object> variables ->
    Map<String, Object> authConfigMap = [
            shopApiUrl : config.shopApiUrl,
            apiVersion : config.apiVersion,
            accessToken: config.accessToken,
    ]
    return ShopifyGraphqlTransport.execute(authConfigMap, queryDocument, variables, [
            connectTimeoutMillis: connectTimeoutMillis,
            readTimeoutMillis   : readTimeoutMillis,
            maxAttempts         : maxAttempts,
    ])
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
String queryDocument = buildQueryDocument.call()
String searchQuery = "created_at:>=${windowStartText} created_at:<${windowEndText}"
Integer maxPageCount = Math.max(1, FacadeSupport.normalizeInt(maxPages, 20))

List<Map<String, Object>> records = []
List<String> searchQueries = []
String afterCursor = null
boolean hasMorePages = false
int pageCount = 0
while (pageCount < maxPageCount) {
    pageCount++
    searchQueries.add(searchQuery)
    Map<String, Object> graphqlResult = executeGraphql.call(authConfig, queryDocument, [
            search: searchQuery,
            after : afterCursor,
    ])
    if (graphqlResult.ok == false) {
        outputErrors.addAll(((List) (graphqlResult.errors ?: ["Shopify GraphQL request failed."]))
                .collect { Object error -> normalize(error) }
                .findAll { String error -> error })
        break
    }

    Map data = graphqlResult.data instanceof Map ? (Map) graphqlResult.data : [:]
    Map orders = data.orders instanceof Map ? (Map) data.orders : [:]
    List edges = orders.edges instanceof Collection ? (List) orders.edges : []
    edges.each { Object edge ->
        Object node = edge instanceof Map ? ((Map) edge).node : null
        if (node instanceof Map) records.add(normalizeShopifyOrderRecord.call((Map<String, Object>) node))
    }

    Map pageInfo = orders.pageInfo instanceof Map ? (Map) orders.pageInfo : [:]
    hasMorePages = pageInfo.hasNextPage == true
    if (!hasMorePages) break
    afterCursor = normalize(pageInfo.endCursor)
    if (!afterCursor) break
}

if (hasMorePages && pageCount >= maxPageCount) {
    outputErrors.add("Shopify API returned more than ${maxPageCount} pages in the automation window. Use a smaller window.")
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
fileName = outputFileName
fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
fileTypeEnumId = "DftJson"
recordCount = records.size()
dataAvailable = records.size() > 0
requestMetadata = [
        sourceType            : "SHOPIFY_GRAPHQL_ORDERS",
        extractionMode        : "DATE_FILTER",
        shopifyAuthConfigId   : configIdValue,
        automationId          : normalize(automationId),
        fileSide              : normalize(fileSide),
        systemEnumId          : normalize(systemEnumId),
        sourceTypeEnumId      : normalize(sourceTypeEnumId),
        sourceTimeZone        : sourceWindow.timeZone,
        calendarDateNormalized: sourceWindow.calendarDateNormalized,
        filterFields          : ["created_at"],
        searchQueries         : searchQueries.unique(),
        windowStartUtc        : windowStartText,
        windowEndUtc          : windowEndText,
        pageCount             : pageCount,
        extractedRecordCount  : records.size(),
].findAll { it.value != null } as Map<String, Object>
DataManagerSupport.writeText(ec, fileLocation as String, JsonOutput.toJson([
        metadata: requestMetadata,
        records : records,
]))
warnings = outputWarnings
errors = []
