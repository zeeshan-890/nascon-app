package com.nascon.league.data.cart

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "guest_cart_item", primaryKeys = ["token", "variantId"])
data class GuestCartItemEntity(
    val token: String,
    val variantId: String,
    val name: String,
    val priceMinor: Long,
    val quantity: Int,
)

@Dao
interface GuestCartDao {
    @Query("SELECT * FROM guest_cart_item WHERE token = :token ORDER BY name")
    fun observe(token: String): Flow<List<GuestCartItemEntity>>

    @Query("SELECT * FROM guest_cart_item WHERE token = :token")
    suspend fun items(token: String): List<GuestCartItemEntity>

    @Query("SELECT * FROM guest_cart_item WHERE token = :token AND variantId = :variantId")
    suspend fun find(token: String, variantId: String): GuestCartItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: GuestCartItemEntity)

    @Query("DELETE FROM guest_cart_item WHERE token = :token AND variantId = :variantId")
    suspend fun delete(token: String, variantId: String)

    @Query("DELETE FROM guest_cart_item WHERE token = :token")
    suspend fun clear(token: String)
}
