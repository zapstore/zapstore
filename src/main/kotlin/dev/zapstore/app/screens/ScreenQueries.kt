package dev.zapstore.app.screens

import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Stored kind 0s for [pubkeys], fetched from relays under [AppConfig.profileQuery] when allowed. */
internal fun Iolite.observeProfiles(pubkeys: Set<String>): Flow<Map<String, ProfileRecord>> =
    if (pubkeys.isEmpty()) flowOf(emptyMap()) else query(Query.profiles(pubkeys), AppConfig.profileQuery).map { it.items }

/** Profiles for the authors of whatever list of apps [this] emits. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<AppRecord>>.authorProfiles(iolite: Iolite): Flow<Map<String, ProfileRecord>> =
    map { apps -> apps.mapNotNull(AppRecord::authorPubkey).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeProfiles)

/** Listings for [appIds], keyed by app ID; apps missing from the local catalog are absent. */
internal fun Iolite.observeAppsById(appIds: Collection<String>): Flow<Map<String, AppRecord>> =
    if (appIds.isEmpty()) flowOf(emptyMap()) else observeApps(AppFilter(appIds = appIds)).map { it.associateBy(AppRecord::appId) }
