package com.fraudcontrols.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

data class Money(
    val amount: BigDecimal,
    val currency: Currency,
) {
    init {
        require(amount.scale() <= 2) { "money amount must use cents or larger units" }
    }

    fun isPositive(): Boolean = amount > BigDecimal.ZERO

    companion object {
        fun usd(amount: String): Money = Money(BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY), Currency.getInstance("USD"))
    }
}
