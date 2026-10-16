package org.parallelnest.app

import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.AppInfo
import org.parallelnest.app.ui.AppCatalog

class AppCatalogTest {
    private val apps=listOf(AppInfo("org.example.zeta","Zeta",emptyList()),AppInfo("com.whatsapp","WhatsApp",emptyList()),AppInfo("org.example.alpha","Alpha",emptyList()))
    @Test fun searchIsCaseInsensitiveAndSorted(){assertEquals(listOf("Alpha","Zeta"),AppCatalog.filter(apps," ORG.EXAMPLE ").map{it.label})}
    @Test fun hotAppsOnlyIncludeEntriesActuallyDiscovered(){assertEquals(listOf("com.whatsapp"),AppCatalog.hot(apps).map{it.packageName});assertTrue(AppCatalog.hot(apps.filter{it.packageName!="com.whatsapp"}).isEmpty())}
    @Test fun multiSelectionIsUniqueAndImmediatelyCounts(){var selected=AppCatalog.toggle(emptyList(),"org.example.alpha");selected=AppCatalog.toggle(selected,"org.example.zeta");assertEquals(2,selected.size);assertEquals(listOf("org.example.zeta"),AppCatalog.toggle(selected,"org.example.alpha"))}
    @Test fun rediscoveryRemovesUnavailableAndUninstalledSelections() {
        val discovered=apps.map { it.copy(supported=it.packageName=="org.example.alpha") }
        assertEquals(listOf("org.example.alpha"),AppCatalog.availableSelection(listOf("com.whatsapp","org.example.removed","org.example.alpha","org.example.alpha"),discovered))
        assertTrue(AppCatalog.availableSelection(listOf("com.whatsapp"),discovered).isEmpty())
    }
}
