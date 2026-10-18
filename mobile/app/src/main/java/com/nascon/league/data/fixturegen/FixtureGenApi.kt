package com.nascon.league.data.fixturegen

import com.nascon.league.di.AppContainer
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** The backend's pure, unauthenticated generator. NOTE: the path is under audit/v1, not api/v1. */
interface FixtureGenApi {
    @POST("audit/v1/fixtures")
    suspend fun generate(@Body body: JsonObject): JsonObject
}

/** Contract addition (see mobile/contract_additions/fixturegen.md): persists a generated schedule into a league. */
interface FixtureGenSaveApi {
    @POST("api/v1/leagues/{id}/fixtures/generate")
    suspend fun save(@Path("id") leagueId: String, @Body body: JsonObject): JsonObject
}

fun AppContainer.fixtureGenApi(): FixtureGenApi =
    service(FixtureGenApi::class.java) { hn -> FixtureGenFakeHolder.getApi(hn) }

fun AppContainer.fixtureGenSaveApi(): FixtureGenSaveApi =
    service(FixtureGenSaveApi::class.java) { hn -> FixtureGenFakeHolder.getSave(hn) }

/** Spec name used in the brief (`saveApi()`); same as [fixtureGenSaveApi]. */
fun AppContainer.saveApi(): FixtureGenSaveApi = fixtureGenSaveApi()
