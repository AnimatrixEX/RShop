package com.rshop.domain.model

data class CatalogFilter(
    val query: String = "",
    val genre: String? = null,
    val platform: String? = null,
    /** Only games of this source (its id); null for all sources. */
    val sourceId: String? = null,
    val sort: SortOrder = SortOrder.Title,
)

enum class SortOrder { Title, Popular, RecentlyAdded, RecentlyUpdated, Size }
