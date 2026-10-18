package com.nascon.league.data.platformadmin

import com.nascon.league.di.AppContainer

fun AppContainer.platformAdminApi(): PlatformAdminApi =
    service(PlatformAdminApi::class.java) { hn -> PlatformAdminFakeHolder.get(hn) }

/** Live mode talks to the real server's `/health` and `/api/v1/ping`; demo mode answers `{status: ok}`. */
fun AppContainer.healthApi(): HealthApi =
    service(HealthApi::class.java) { hn -> HealthFakeHolder.get(hn) }
