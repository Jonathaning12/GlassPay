package com.glasspay.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    @Test
    fun groupedMoneyUsesThousandsSeparators() {
        assertEquals("$3,450.00", formatGroupedMoney(3450.0))
        assertEquals("$1,234,567.80", formatGroupedMoney(1234567.8))
        assertEquals("$0.00", formatGroupedMoney(0.0))
    }

    @Test
    fun signedMoneyShowsPlusAndMinus() {
        assertEquals("+$2,140.00", formatGroupedMoney(2140.0, signed = true))
        assertEquals("−$50.00", formatGroupedMoney(-50.0, signed = true))
        assertEquals("$0.00", formatGroupedMoney(0.0, signed = true))
    }

    @Test
    fun changePercentAgainstThePreviousPeriod() {
        assertEquals(18, netChangePercent(2140.0, 1813.56))
        assertEquals(-50, netChangePercent(50.0, 100.0))
    }

    @Test
    fun noChangePercentWithoutAComparableBase() {
        assertNull(netChangePercent(100.0, 0.0))
        assertNull(netChangePercent(100.0, -40.0))
    }

    @Test
    fun axisMaxIsARoundNumberAboveTheData() {
        assertEquals(2000.0, niceAxisMax(1210.0), 0.0)
        assertEquals(5000.0, niceAxisMax(3450.0), 0.0)
        assertEquals(250.0, niceAxisMax(240.0), 0.0)
        assertEquals(100.0, niceAxisMax(0.0), 0.0)
    }

    @Test
    fun axisLabelsAreShort() {
        assertEquals("0", axisLabel(0.0))
        assertEquals("750", axisLabel(750.0))
        assertEquals("1k", axisLabel(1000.0))
        assertEquals("2.5k", axisLabel(2500.0))
    }

    @Test
    fun defaultSelectionIsTheLastBucketWithActivity() {
        val buckets = listOf(
            StatsBucket("L", 350.0, 0.0),
            StatsBucket("M", 0.0, 200.0),
            StatsBucket("M", 0.0, 0.0),
        )
        assertEquals(1, defaultSelectedBucket(buckets))
        assertEquals(0, defaultSelectedBucket(listOf(StatsBucket("L", 0.0, 0.0))))
    }

    @Test
    fun periodsMapToTheServerValues() {
        assertEquals(listOf("week", "month", "year"), StatsPeriod.entries.map { it.apiValue })
    }
}
