package com.sergey.animevault.data.online

import org.junit.Test

class OnlineProviderRegistryTest {
    @Test fun uniqueDescriptorsPass() {
        validateProviderDescriptors(
            listOf(
                OnlineProviderDescriptor("a", "A", ""),
                OnlineProviderDescriptor("b", "B", ""),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateIdsFailFast() {
        validateProviderDescriptors(
            listOf(
                OnlineProviderDescriptor("same", "A", ""),
                OnlineProviderDescriptor("same", "B", ""),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankIdsFailFast() {
        validateProviderDescriptors(listOf(OnlineProviderDescriptor("", "Bad", "")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMinimumSearchLengthFailsFast() {
        validateProviderDescriptors(
            listOf(OnlineProviderDescriptor("bad", "Bad", "", minimumSearchLength = 0)),
        )
    }

}
