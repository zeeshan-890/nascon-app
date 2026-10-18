package com.nascon.league.ui.navigation

/** Route constants for the feature screens added from the second design batch. Central so features can link to each other. */
object NewRoutes {
    // Match operations (organizer / super admin)
    const val MATCH_ADMIN = "matchadmin/{matchId}"
    const val MATCH_RESCHEDULE = "matchadmin/{matchId}/reschedule"
    const val MATCH_PINS = "matchadmin/{matchId}/pins"

    // Roster extras
    const val ROSTER_ADD = "roster/{clubId}/add"
    const val MY_STATS = "mystats"

    // Club orders (club manager / super admin)
    const val CLUB_ORDERS = "cluborders/{clubId}"

    // Club management (club manager / super admin)
    const val CLUB_EDIT = "clubadmin/{clubId}/edit"
    const val CLUB_STAFF = "clubadmin/{clubId}/staff"
    const val CLUB_PRODUCTS = "clubadmin/{clubId}/products"
    const val PRODUCT_EDIT = "clubadmin/{clubId}/products/{productId}"
    const val PRODUCT_STOCK = "clubadmin/{clubId}/products/{productId}/stock"

    // League management (organizer / super admin)
    const val LEAGUE_ADMIN_LIST = "leagueadmin"
    const val LEAGUE_ADMIN_DETAIL = "leagueadmin/{leagueId}"
    const val LEAGUE_FORM = "leagueadmin/form?leagueId={leagueId}"
    const val SEASONS = "leagueadmin/{leagueId}/seasons"
    const val SEASON_FORM = "leagueadmin/{leagueId}/seasons/form"
    const val DIVISIONS = "leagueadmin/{leagueId}/divisions"
    const val TEAMS = "leagueadmin/{leagueId}/teams"
    const val PROMOTION = "leagueadmin/{leagueId}/promotion"
    const val STANDINGS_RULES = "leagueadmin/{leagueId}/standingsrules"

    // Fixture generator
    const val GEN_FORMAT = "fixturegen/{leagueId}"
    const val GEN_ROUND_ROBIN = "fixturegen/{leagueId}/roundrobin"
    const val GEN_SWISS = "fixturegen/{leagueId}/swiss"
    const val GEN_SINGLE = "fixturegen/{leagueId}/singleelim"
    const val GEN_DOUBLE = "fixturegen/{leagueId}/doubleelim"
    const val GEN_HYBRID = "fixturegen/{leagueId}/hybrid"
    const val GEN_PREVIEW = "fixturegen/{leagueId}/preview"
    const val GEN_BRACKET_SINGLE = "fixturegen/{leagueId}/bracket/single"
    const val GEN_BRACKET_DOUBLE = "fixturegen/{leagueId}/bracket/double"
    const val GEN_SWISS_ROUNDS = "fixturegen/{leagueId}/swiss/rounds"

    // Platform admin (super admin). The admin home itself is Destinations.ADMIN.
    const val ADMIN_USERS = "admin/users"
    const val ADMIN_USER_DETAIL = "admin/users/{userId}"
    const val ADMIN_USER_NEW = "admin/users/new"
    const val ADMIN_USER_ROLE = "admin/users/{userId}/role"
    const val ADMIN_CLUBS = "admin/clubs"
    const val ADMIN_CLUB_NEW = "admin/clubs/new"
    const val ADMIN_SPORTS = "admin/sports"
    const val ADMIN_SPORT_CONFIG = "admin/sports/{sportId}"
    const val ADMIN_SCORING_EVENT = "admin/sports/{sportId}/event?key={key}"
    const val ADMIN_ACCESS = "admin/access"
    const val ADMIN_AUDIT = "admin/audit"
    const val ADMIN_AUDIT_DETAIL = "admin/audit/{entryId}"
    const val ADMIN_AUDIT_FILTER = "admin/audit/filter"
    const val ADMIN_STATUS = "admin/status"

    fun matchAdmin(id: String) = "matchadmin/$id"
    fun matchReschedule(id: String) = "matchadmin/$id/reschedule"
    fun matchPins(id: String) = "matchadmin/$id/pins"
    fun rosterAdd(clubId: String) = "roster/$clubId/add"
    fun clubOrders(clubId: String) = "cluborders/$clubId"
    fun clubEdit(clubId: String) = "clubadmin/$clubId/edit"
    fun clubStaff(clubId: String) = "clubadmin/$clubId/staff"
    fun clubProducts(clubId: String) = "clubadmin/$clubId/products"
    fun productEdit(clubId: String, productId: String) = "clubadmin/$clubId/products/$productId"
    fun productStock(clubId: String, productId: String) = "clubadmin/$clubId/products/$productId/stock"
    fun leagueAdminDetail(id: String) = "leagueadmin/$id"
    fun leagueForm(leagueId: String? = null) = if (leagueId == null) "leagueadmin/form" else "leagueadmin/form?leagueId=$leagueId"
    fun seasons(id: String) = "leagueadmin/$id/seasons"
    fun seasonForm(id: String) = "leagueadmin/$id/seasons/form"
    fun divisions(id: String) = "leagueadmin/$id/divisions"
    fun teams(id: String) = "leagueadmin/$id/teams"
    fun promotion(id: String) = "leagueadmin/$id/promotion"
    fun standingsRules(id: String) = "leagueadmin/$id/standingsrules"
    fun genFormat(id: String) = "fixturegen/$id"
    fun gen(id: String, suffix: String) = "fixturegen/$id/$suffix"
    fun adminUser(id: String) = "admin/users/$id"
    fun adminUserRole(id: String) = "admin/users/$id/role"
    fun adminSport(id: String) = "admin/sports/$id"
    fun adminScoringEvent(sportId: String, key: String?) =
        if (key == null) "admin/sports/$sportId/event" else "admin/sports/$sportId/event?key=$key"
    fun adminAuditDetail(id: String) = "admin/audit/$id"
}
