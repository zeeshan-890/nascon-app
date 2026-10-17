package com.nascon.league.data.store

import com.nascon.league.data.api.dto.ProductDto
import java.util.Locale

sealed interface StockText {
    data object InStock : StockText
    data class OnlyLeft(val n: Int) : StockText
    data object SoldOut : StockText
}

fun deriveCategories(products: List<ProductDto>): List<String> =
    products.map { it.category }.filter { it.isNotBlank() }.distinct()

fun capitalizeLabel(s: String): String =
    s.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

fun minPrice(product: ProductDto): Long? = product.variants.minOfOrNull { it.priceMinor }

fun isSoldOut(product: ProductDto): Boolean = product.variants.none { it.stock > 0 }

fun stockText(stock: Int): StockText = when {
    stock <= 0 -> StockText.SoldOut
    stock <= 5 -> StockText.OnlyLeft(stock)
    else -> StockText.InStock
}

fun variantLabel(size: String?, color: String?): String {
    val parts = listOfNotNull(size?.trim()?.takeIf { it.isNotEmpty() }, color?.trim()?.takeIf { it.isNotEmpty() })
    return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
}

fun maxQuantityFor(stock: Int): Int = minOf(10, stock).coerceAtLeast(0)

/** Namespace form of the same helpers. */
object StoreLogic {
    fun deriveCategories(products: List<ProductDto>): List<String> =
        com.nascon.league.data.store.deriveCategories(products)

    fun capitalizeLabel(s: String): String = com.nascon.league.data.store.capitalizeLabel(s)
    fun minPrice(product: ProductDto): Long? = com.nascon.league.data.store.minPrice(product)
    fun isSoldOut(product: ProductDto): Boolean = com.nascon.league.data.store.isSoldOut(product)
    fun stockText(stock: Int): StockText = com.nascon.league.data.store.stockText(stock)
    fun variantLabel(size: String?, color: String?): String =
        com.nascon.league.data.store.variantLabel(size, color)

    fun maxQuantityFor(stock: Int): Int = com.nascon.league.data.store.maxQuantityFor(stock)
}
