package com.dshmobile.shell

import org.junit.Assert.*
import org.junit.Test

class WebEngineSupportTest {
    @Test fun readsEngineVersionRegardlessOfWrapperVersion() {
        assertEquals(124, WebEngineSupport.chromiumMajor("Mozilla/5.0 Version/4.0 Chrome/124.0.0.0 VendorBrowser/12.1"))
        assertEquals(113, WebEngineSupport.chromiumMajor("Mozilla/5.0 Chromium/113.0.0.0 VendorBrowser/4.0"))
        assertEquals(99, WebEngineSupport.chromiumMajor("Mozilla/5.0 Chrome/99.0.0.0 VendorBrowser/130.0"))
        assertNull(WebEngineSupport.chromiumMajor("Mozilla/5.0 AppleWebKit/605.1 Version/18.0 Safari/605.1"))
        assertNull(WebEngineSupport.chromiumMajor("Chrome/999999999999999999999"))
    }
}
