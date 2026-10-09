package com.rshop.domain.model

data class CatalogFilter(
    val query: String = "",
    val genre: String? = null,
    val platform: String? = null,
    /** Only games of this source (its id); null for all sources. */
    val sourceId: String? = null,
    val sort: SortOrder = SortOrder.Title,
    /** Leave out games that are already installed. */
    val hideInstalled: Boolean = false,
    /** Leave out demos, betas and prototypes. */
    val hideExtras: Boolean = false,
)

enum class SortOrder { Title, Popular, RecentlyAdded, RecentlyUpdated, Size }
