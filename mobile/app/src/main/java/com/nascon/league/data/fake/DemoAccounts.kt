package com.nascon.league.data.fake

data class DemoAccount(val roleLabel: String, val email: String, val password: String)

object DemoAccounts {
    const val PASSWORD = "Nascon@26"

    val all: List<DemoAccount> = listOf(
        DemoAccount("Super Admin", "admin@nascon.test", PASSWORD),
        DemoAccount("League Organizer", "organizer@nascon.test", PASSWORD),
        DemoAccount("Club Manager", "manager@nascon.test", PASSWORD),
        DemoAccount("Player", "player@nascon.test", PASSWORD),
        DemoAccount("Viewer", "viewer@nascon.test", PASSWORD),
    )
}
