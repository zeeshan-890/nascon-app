package com.nascon.league.data.store

import com.nascon.league.data.api.dto.ProductDto

/**
 * Pure: aligns cart lines with the latest catalogue. Price changes update the line;
 * stock below the quantity clamps it; missing or sold-out variants block checkout.
 */
fun reconcile(lines: List<CartLine>, products: List<ProductDto>): List<CartLine> {
    val variants = products.flatMap { p -> p.variants.map { it.variantId to it } }.toMap()
    return lines.map { line ->
        val variant = variants[line.variantId]
        when {
            variant == null -> line.copy(problem = CartProblem.NoLongerAvailable)
            variant.stock <= 0 -> line.copy(
                unitPriceMinor = variant.priceMinor,
                problem = CartProblem.SoldOut,
            )
            else -> {
                val priceChanged = variant.priceMinor != line.unitPriceMinor
                val clamped = variant.stock < line.quantity
                line.copy(
                    unitPriceMinor = variant.priceMinor,
                    quantity = if (clamped) variant.stock else line.quantity,
                    problem = when {
                        clamped -> CartProblem.OnlyLeft(variant.stock)
                        priceChanged -> CartProblem.PriceUpdated
                        else -> null
                    },
                )
            }
        }
    }
}
