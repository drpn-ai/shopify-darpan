package shopify.graphql

import darpan.facade.common.FacadeSupport

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ShopifyGraphqlQueryBuilder {
    static final String DEFAULT_SOURCE_DEFINITION_ID = ShopifySourceCatalog.SHOPIFY_ORDERS

    static Map<String, Object> buildQuery(Map<String, Object> requirements) {
        String sourceDefinitionId = ShopifySourceCatalog.normalizeSourceDefinitionId(requirements?.sourceDefinitionId) ?: DEFAULT_SOURCE_DEFINITION_ID
        String apiVersion = ShopifySourceCatalog.normalizeApiVersion(requirements?.apiVersion)
        Map<String, Object> source = ShopifySourceCatalog.requireSource(sourceDefinitionId, apiVersion)

        List<String> selectedFieldPaths = resolveSelectedFieldPaths(source, requirements?.selectedFieldPaths as Collection)
        List<String> invalidFields = ShopifySourceCatalog.validateSelectedFields(source, selectedFieldPaths)
        if (invalidFields) {
            throw new IllegalArgumentException("Unsupported Shopify field path(s) for ${source.sourceDefinitionId}: ${invalidFields.join(', ')}.")
        }

        Map<String, Object> filters = normalizeFilters(source, requirements?.filters as Map)
        Integer pageSize = clampPageSize(requirements?.pageSize, source.defaultPageSize as Integer, source.maxPageSize as Integer)
        String afterCursor = FacadeSupport.normalize(requirements?.afterCursor)
        Boolean reverse = FacadeSupport.normalizeBool(requirements?.reverse, false)
        Map<String, Object> connectionPageSizes = resolveConnectionPageSizes(source, selectedFieldPaths, requirements?.connectionPageSizes as Map)
        String sortKey = resolveSortKey(source, filters, requirements?.sortKey)

        Map<String, Map> selectionTree = buildSelectionTree(source, selectedFieldPaths)
        Map<String, Object> variables = [
            first  : pageSize,
            after  : afterCursor,
            query  : buildSearchQuery(source, filters),
            reverse: reverse,
        ]
        connectionPageSizes.each { String key, Object value -> variables[key] = value }

        String operationName = operationNameFor(source.sourceDefinitionId as String)
        String queryDocument = renderQueryDocument(source, selectionTree, operationName, connectionPageSizes.keySet() as Set<String>, sortKey)

        return [
            sourceDefinitionId: source.sourceDefinitionId,
            operationName     : operationName,
            queryDocument     : queryDocument,
            variables         : variables,
            sortKey           : sortKey,
            selectedFieldPaths: selectedFieldPaths,
            filters           : filters,
            outputShape       : [
                rootPath      : "data.${source.queryRoot}",
                edgePath      : "data.${source.queryRoot}.edges",
                nodePath      : "data.${source.queryRoot}.edges.node",
                cursorPath    : "data.${source.queryRoot}.edges.cursor",
                pageInfoPath  : "data.${source.queryRoot}.pageInfo",
                paginationType: source.paginationStrategy,
            ],
        ]
    }

    static List<String> resolveSelectedFieldPaths(Map<String, Object> source, Collection requestedFieldPaths) {
        List<String> requested = ShopifySourceCatalog.normalizeFieldPaths(requestedFieldPaths)
        if (!requested) requested = new ArrayList(source.defaultSelectedFieldPaths as List)

        List<String> required = ((List<Map<String, Object>>) (source.fields ?: []))
            .findAll { Map<String, Object> field -> field.required == true }
            .collect { Map<String, Object> field -> field.fieldPath.toString() }

        return (required + requested).findAll { it }.unique()
    }

    static Map<String, Object> normalizeFilters(Map<String, Object> source, Map rawFilters) {
        Map<String, Map<String, Object>> supportedFilters = (Map<String, Map<String, Object>>) (source.supportedFilters ?: [:])
        Map<String, Object> filters = [:]
        (rawFilters ?: [:]).each { Object key, Object value ->
            String filterKey = FacadeSupport.normalize(key)
            String filterValue = FacadeSupport.normalize(value)
            if (!filterKey || !filterValue) return
            if (!supportedFilters.containsKey(filterKey)) {
                throw new IllegalArgumentException("Unsupported Shopify filter '${filterKey}' for ${source.sourceDefinitionId}.")
            }
            Map<String, Object> filterDefinition = supportedFilters[filterKey]
            filterValue = normalizeFilterValue(filterKey, filterValue, filterDefinition)
            if (filterValue.contains('"') || filterValue.contains("\n") || filterValue.contains("\r")) {
                throw new IllegalArgumentException("Shopify filter '${filterKey}' contains unsupported characters.")
            }
            filters[filterKey] = filterValue
        }
        return filters
    }

    static String normalizeFilterValue(String filterKey, String filterValue, Map<String, Object> filterDefinition) {
        if (filterDefinition?.type == "datetime") return normalizeDateTimeFilterValue(filterKey, filterValue)
        return filterValue
    }

    static String buildSearchQuery(Map<String, Object> source, Map<String, Object> filters) {
        Map<String, Map<String, Object>> supportedFilters = (Map<String, Map<String, Object>>) (source.supportedFilters ?: [:])
        List<String> terms = []
        filters.each { String filterKey, Object value ->
            Map<String, Object> filterDefinition = supportedFilters[filterKey]
            terms.add("${filterDefinition.queryName}:${filterDefinition.comparator}${renderSearchValue(filterDefinition, value)}")
        }
        return terms.join(" ")
    }

    private static String normalizeDateTimeFilterValue(String filterKey, String rawValue) {
        if (rawValue.contains("'") || rawValue.contains('"') || rawValue.contains("\n") || rawValue.contains("\r")) {
            throw new IllegalArgumentException("Shopify filter '${filterKey}' contains unsupported characters.")
        }

        List<Closure<Instant>> parsers = [
            { String text -> Instant.parse(text) },
            { String text -> OffsetDateTime.parse(text).toInstant() },
            { String text -> ZonedDateTime.parse(text).toInstant() },
            { String text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) },
        ]
        for (Closure<Instant> parser : parsers) {
            try {
                return parser(rawValue).toString()
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Shopify filter '${filterKey}' must be an ISO-8601 date-time.")
    }

    private static String renderSearchValue(Map<String, Object> filterDefinition, Object rawValue) {
        String value = rawValue?.toString() ?: ""
        return value
    }

    private static String resolveSortKey(Map<String, Object> source, Map<String, Object> filters, Object rawSortKey) {
        String requestedSortKey = FacadeSupport.normalize(rawSortKey)?.toUpperCase()
        if (requestedSortKey) return requestedSortKey

        Map<String, Map<String, Object>> supportedFilters = (Map<String, Map<String, Object>>) (source.supportedFilters ?: [:])
        for (String filterKey : filters.keySet()) {
            String filterSortKey = FacadeSupport.normalize(supportedFilters[filterKey]?.sortKey)?.toUpperCase()
            if (filterSortKey) return filterSortKey
        }
        return FacadeSupport.normalize(source.defaultSortKey)?.toUpperCase() ?: "UPDATED_AT"
    }

    private static Integer clampPageSize(Object rawPageSize, Integer defaultPageSize, Integer maxPageSize) {
        Integer pageSize = FacadeSupport.normalizeInt(rawPageSize, defaultPageSize ?: 100)
        return Math.max(1, Math.min(maxPageSize ?: 250, pageSize))
    }

    private static Map<String, Object> resolveConnectionPageSizes(Map<String, Object> source, List<String> selectedFieldPaths, Map rawConnectionPageSizes) {
        Map<String, Map<String, Object>> fieldsByPath = ShopifySourceCatalog.fieldsByPath(source)
        Map<String, Object> pageSizes = [:]
        selectedFieldPaths.each { String fieldPath ->
            Map<String, Object> field = fieldsByPath[fieldPath]
            String connectionRoot = FacadeSupport.normalize(field?.connectionRoot)
            if (!connectionRoot) return

            String variableName = "${connectionRoot}First"
            Integer defaultPageSize = (field.connectionDefaultPageSize ?: 50) as Integer
            Integer maxPageSize = (field.connectionMaxPageSize ?: 100) as Integer
            pageSizes[variableName] = clampPageSize(rawConnectionPageSizes?.get(variableName) ?: rawConnectionPageSizes?.get(connectionRoot), defaultPageSize, maxPageSize)
        }
        return pageSizes
    }

    private static Map<String, Map> buildSelectionTree(Map<String, Object> source, List<String> selectedFieldPaths) {
        Map<String, Map<String, Object>> fieldsByPath = ShopifySourceCatalog.fieldsByPath(source)
        Map<String, Map> tree = [:]
        selectedFieldPaths.each { String fieldPath ->
            String selectionPath = fieldsByPath[fieldPath]?.selectionPath
            if (!selectionPath) return
            addSelectionPath(tree, selectionPath.tokenize("."))
        }
        return tree
    }

    private static void addSelectionPath(Map<String, Map> tree, List<String> segments) {
        if (!segments) return
        String segment = segments.first()
        Map<String, Map> childTree = (Map<String, Map>) tree.computeIfAbsent(segment) { [:] }
        addSelectionPath(childTree, segments.tail())
    }

    private static String renderQueryDocument(Map<String, Object> source, Map<String, Map> selectionTree, String operationName,
            Set<String> connectionPageSizeVariables, String sortKey) {
        List<String> variableDefinitions = [
            "\$first: Int!",
            "\$after: String",
            "\$query: String",
            "\$reverse: Boolean!",
        ]
        connectionPageSizeVariables.sort().each { String variableName ->
            variableDefinitions.add("\$${variableName}: Int!")
        }

        String rootArgs = "first: \$first, after: \$after, query: \$query, sortKey: ${sortKey}, reverse: \$reverse"
        String nodeSelections = renderSelectionTree(selectionTree, 5, connectionPageSizeVariables)
        return """query ${operationName}(${variableDefinitions.join(', ')}) {
  ${source.queryRoot}(${rootArgs}) {
    edges {
      cursor
      node {
${nodeSelections}
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}"""
    }

    private static String renderSelectionTree(Map<String, Map> tree, int indentLevel, Set<String> connectionPageSizeVariables) {
        String indent = "  " * indentLevel
        return tree.keySet().sort().collect { String fieldName ->
            Map<String, Map> childTree = tree[fieldName]
            String renderedFieldName = renderFieldName(fieldName, connectionPageSizeVariables)
            if (!childTree) return "${indent}${renderedFieldName}"
            return "${indent}${renderedFieldName} {\n${renderSelectionTree(childTree, indentLevel + 1, connectionPageSizeVariables)}\n${indent}}"
        }.join("\n")
    }

    private static String renderFieldName(String fieldName, Set<String> connectionPageSizeVariables) {
        String variableName = "${fieldName}First"
        return connectionPageSizeVariables.contains(variableName) ? "${fieldName}(first: \$${variableName})" : fieldName
    }

    private static String operationNameFor(String sourceDefinitionId) {
        return sourceDefinitionId.toLowerCase().split("_").collect { String part -> part.capitalize() }.join("")
    }
}
