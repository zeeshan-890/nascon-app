package com.nascon.league.data.realtime

class SubscriptionRegistry {
    private val counts = LinkedHashMap<String, Int>()

    /** Returns true if this is the first subscriber for the topic. */
    @Synchronized
    fun add(topic: String): Boolean {
        val n = (counts[topic] ?: 0) + 1
        counts[topic] = n
        return n == 1
    }

    /** Returns true if the topic has no subscribers left. */
    @Synchronized
    fun remove(topic: String): Boolean {
        val n = counts[topic] ?: return false
        return if (n <= 1) { counts.remove(topic); true } else { counts[topic] = n - 1; false }
    }

    @Synchronized
    fun activeTopics(): List<String> = counts.keys.toList()

    @Synchronized
    fun isEmpty(): Boolean = counts.isEmpty()

    @Synchronized
    fun has(topic: String): Boolean = counts.containsKey(topic)
}
