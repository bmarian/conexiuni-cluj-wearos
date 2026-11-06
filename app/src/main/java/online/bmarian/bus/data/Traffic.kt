package online.bmarian.bus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class TrafficStretch(val path: String, val band: Int)

@Serializable
data class Traffic(val stretches: List<TrafficStretch> = emptyList())

// Port of publishTraffic.
object LiveTraffic {
    private val streams = HashMap<Any, Map<String, Traffic>>()
    private val merged = MutableStateFlow<Map<String, Traffic>>(emptyMap())
    val byTrip: StateFlow<Map<String, Traffic>> = merged

    @Synchronized
    fun publish(stream: Any, traffic: Map<String, Traffic>?) {
        if (traffic == null) streams.remove(stream) else streams[stream] = traffic
        merged.value = streams.values.fold(emptyMap()) { all, one -> all + one }
    }
}
