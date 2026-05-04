import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import shopify.facade.settings.ShopifyAuthConfigSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

List<Map<String, Object>> rows = []
if (activeTenantUserGroupId) {
    (ec.entity.find(ShopifyAuthConfigSupport.ENTITY_NAME)
        .condition("companyUserGroupId", activeTenantUserGroupId)
        .useCache(false)
        .orderBy("description,shopifyAuthConfigId")
        .list() ?: []).each { cfg ->
        rows.add(ShopifyAuthConfigSupport.safeConfig(ec, cfg))
    }
}

String search = FacadeSupport.normalize(query)?.toLowerCase()
List<Map<String, Object>> filteredRows = rows.findAll { row ->
    ShopifyAuthConfigSupport.matchesSearch(row, search)
}

Map<String, Object> pageResult = ShopifyAuthConfigSupport.paginate(filteredRows, page, size)
shopifyAuthConfigs = pageResult.rows
pagination = pageResult.pagination

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
