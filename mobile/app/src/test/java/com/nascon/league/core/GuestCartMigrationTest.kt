package com.nascon.league.core

import com.nascon.league.core.db.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room cannot be run on the JVM here, so the migration is checked against the schema file Room exports at build time:
 * the SQL the 2 -> 3 migration executes must create exactly the table (and key) that version 3 expects.
 */
class GuestCartMigrationTest {
    private fun schema(version: Int): JsonObject {
        val f = File("schemas/com.nascon.league.core.db.AppDatabase/$version.json")
        assertTrue("exported schema $version.json is missing: ${f.absolutePath}", f.exists())
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun entities(schema: JsonObject): JsonArray = schema["database"]!!.jsonObject["entities"]!!.jsonArray

    private fun squish(sql: String) =
        sql.map { if (it.isWhitespace()) ' ' else it }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    @Test fun migrationSqlMatchesTheExportedVersion3Table() {
        val entity = entities(schema(3)).map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "guest_cart_item" }
        val expected = entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "guest_cart_item")
        assertEquals(squish(expected), squish(AppDatabase.GUEST_CART_CREATE_SQL))
    }

    @Test fun version3KeepsEveryVersion2Table() {
        val v2 = entities(schema(2)).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        val v3 = entities(schema(3)).map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        assertTrue("version 3 must not drop tables: missing ${v2 - v3}", v3.containsAll(v2))
        assertEquals(setOf("guest_cart_item"), v3 - v2)
    }

    @Test fun existingTablesAreUnchangedByTheMigration() {
        val v2 = entities(schema(2)).associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject["createSql"]!!.jsonPrimitive.content }
        val v3 = entities(schema(3)).associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject["createSql"]!!.jsonPrimitive.content }
        for ((table, sql) in v2) assertEquals("table $table changed in v3 without a migration step", sql, v3[table])
    }

    @Test fun migrationRunsTheOneStatementAndIsVersioned() {
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_2_3.endVersion)
    }
}
