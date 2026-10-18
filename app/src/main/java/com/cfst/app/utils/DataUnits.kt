package com.cfst.app.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object DataUnits {
    fun formatBytes(bytes:Long):String {
        require(bytes>=0)
        val divisor:Long; val suffix:String
        when {
            bytes<1000 -> return "$bytes B"
            bytes<1000000 -> { divisor=1000; suffix="KB" }
            bytes<1000000000 -> { divisor=1000000; suffix="MB" }
            else -> { divisor=1000000000; suffix="GB" }
        }
        return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor),2,RoundingMode.HALF_UP).toPlainString()+" $suffix"
    }
    fun formatSpeed(bytesPerSecond:Double):String = if (!bytesPerSecond.isFinite() || bytesPerSecond<0) "未知" else String.format(Locale.ROOT,"%.2f MB/s",bytesPerSecond/1000000)
    fun budgetEditor(bytes:Long):String = BigDecimal.valueOf(bytes).divide(BigDecimal(1000000),2,RoundingMode.HALF_UP).toPlainString()
    fun parseBudgetMb(text:String):Long {
        require(text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "MB 最多两位小数" }
        val value = try { BigDecimal(text).multiply(BigDecimal(1000000)).longValueExact() } catch (_:ArithmeticException) { throw IllegalArgumentException("预算溢出") }
        require(value in 1..1073741824L) { "预算应为1..1,073,741,824 bytes" }
        return value
    }
}
