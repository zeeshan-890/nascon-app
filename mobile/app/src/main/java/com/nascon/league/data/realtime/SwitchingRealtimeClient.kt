package com.nascon.league.data.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SwitchingRealtimeClient(
    private val useFake: () -> Boolean,
    private val real: RealtimeClient,
    private val fake: RealtimeClient,
) : RealtimeClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val state: StateFlow<RealtimeState> =
        combine(real.state, fake.state) { r, f -> if (useFake()) f else r }
            .stateIn(scope, SharingStarted.Eagerly, if (useFake()) fake.state.value else real.state.value)

    override fun subscribe(topic: String): Flow<RealtimeMessage> =
        if (useFake()) fake.subscribe(topic) else real.subscribe(topic)
}
