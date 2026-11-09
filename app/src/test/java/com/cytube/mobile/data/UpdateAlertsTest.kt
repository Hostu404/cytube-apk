package com.cytube.mobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class UpdateAlertsTest {
    private val installed = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()

    private fun release(name: String, published: String, draft: Boolean = false) =
        JSONObject().put("name", name).put("tag_name", "nightly").put("published_at", published).put("draft", draft)

    @Test fun aNightlyBuildWatchesTheNightly() {
        assertTrue(AppAlerts.isNightly("classless nightly 6 Nov"))
        assertFalse(AppAlerts.isNightly("classless"))
    }

    @Test fun aReleaseOutSinceThisInstallIsAnUpdate() {
        val alert = AppAlerts.updateFrom(release("Nightly 7 Nov 2026", "2026-11-07T03:10:00Z"), installed, nightly = true)!!
        assertEquals("New nightly: 7 Nov 2026", alert.text)
        assertEquals(AppAlerts.NIGHTLY_PAGE, alert.link)
        assertTrue(alert.update)
        val proper = AppAlerts.updateFrom(release("v1.9.0", "2026-11-06T10:00:00Z"), installed, nightly = false)!!
        assertEquals("New version: v1.9.0", proper.text)
        assertEquals(AppAlerts.LATEST_PAGE, proper.link)
    }

    @Test fun notOneThatCameOutBeforeItOrAHalfMadeOne() {
        assertNull(AppAlerts.updateFrom(release("Nightly 4 Nov 2026", "2026-11-04T03:10:00Z"), installed, nightly = true))
        assertNull(AppAlerts.updateFrom(release("v1.9.0", "2026-11-06T10:00:00Z", draft = true), installed, nightly = false))
        assertNull(AppAlerts.updateFrom(JSONObject().put("name", "v2"), installed, nightly = false))
    }

    private val today = LocalDate.of(2026, 11, 6)

    @Test fun newsForThisVersionUpToItsLastDay() {
        val json = JSONObject("""{"alerts": [
            {"id": "maint", "text": "Server maintenance tonight", "link": "https://cytu.be/status", "until": "2026-11-06"},
            {"id": "old", "text": "Yesterday's news", "until": "2026-11-05"},
            {"id": "upgrade", "text": "Please update", "maxVersionCode": 22},
            {"id": "future", "text": "For newer versions", "minVersionCode": 24},
            {"id": "", "text": "No id"},
            {"id": "blank"}
        ]}""")
        val news = AppAlerts.newsFrom(json, today, versionCode = 23)
        assertEquals(listOf("news:maint"), news.map { it.id })
        assertEquals("https://cytu.be/status", news[0].link)
        assertEquals(LocalDate.of(2026, 11, 6), news[0].until)
    }

    @Test fun onlyWebLinksPlainShortTextAndAFew() {
        val json = JSONObject().put("alerts", org.json.JSONArray().apply {
            put(JSONObject().put("id", "a").put("text", "x".repeat(500)).put("link", "javascript:alert(1)"))
            repeat(5) { put(JSONObject().put("id", "n$it").put("text", "News $it")) }
        })
        val news = AppAlerts.newsFrom(json, today, versionCode = 23)
        assertEquals(AppAlerts.MAX_NEWS, news.size)
        assertNull("only http(s) links", news[0].link)
        assertEquals(AppAlerts.MAX_TEXT, news[0].text.length)
    }

    @Test fun aNullIsNothingNotTheWordNull() {
        // A release with no title: its tag instead.
        val untitled = JSONObject().put("name", JSONObject.NULL).put("tag_name", "v1.9.0").put("published_at", "2026-11-06T10:00:00Z")
        assertEquals("New version: v1.9.0", AppAlerts.updateFrom(untitled, installed, nightly = false)!!.text)
        val news = AppAlerts.newsFrom(JSONObject("""{"alerts": [
            {"id": "a", "text": "No last day", "until": null, "link": null},
            {"id": "b", "text": null}
        ]}"""), today, versionCode = 23)
        assertEquals(listOf("news:a"), news.map { it.id })
        assertNull(news[0].until)
        assertNull(news[0].link)
    }

    // The home page keys its list on the id: one listed twice can't be drawn,
    // and would crash it at every launch while the alert is kept.
    @Test fun eachIdOnceTheFirstWins() {
        val news = AppAlerts.newsFrom(JSONObject("""{"alerts": [
            {"id": "maint", "text": "Maintenance tonight"},
            {"id": "maint", "text": "Maintenance tonight (corrected)"},
            {"id": "other", "text": "Something else"},
            {"id": " maint ", "text": "Same id with spaces round it"}
        ]}"""), today, versionCode = 23)
        assertEquals(listOf("news:maint", "news:other"), news.map { it.id })
        assertEquals("Maintenance tonight", news[0].text)
    }

    @Test fun aRepeatedIdDoesNotUseUpTheRoomForNews() {
        val json = JSONObject().put("alerts", org.json.JSONArray().apply {
            repeat(4) { put(JSONObject().put("id", "same").put("text", "Same $it")) }
            put(JSONObject().put("id", "next").put("text", "Next"))
        })
        assertEquals(listOf("news:same", "news:next"), AppAlerts.newsFrom(json, today, versionCode = 23).map { it.id })
    }

    @Test fun aKeptListWithAnIdTwiceReadsBackWithItOnce() {
        val saved = """[
            {"id": "news:maint", "text": "One", "update": false},
            {"id": "news:maint", "text": "Two", "update": false},
            {"id": "update:2026-11-07T03:10:00Z", "text": "New nightly", "update": true}
        ]"""
        val alerts = AppAlerts.decode(saved)
        assertEquals(listOf("news:maint", "update:2026-11-07T03:10:00Z"), alerts.map { it.id })
        assertEquals("One", alerts[0].text)
    }

    @Test fun keptAndReadBack() {
        val alerts = listOf(
            AppAlert("update:2026-11-07T03:10:00Z", "New nightly: 7 Nov 2026", AppAlerts.NIGHTLY_PAGE, update = true),
            AppAlert("news:maint", "Maintenance", null, until = LocalDate.of(2026, 11, 8))
        )
        assertEquals(alerts, AppAlerts.decode(AppAlerts.encode(alerts)))
        assertEquals(emptyList<AppAlert>(), AppAlerts.decode(null))
        assertEquals(emptyList<AppAlert>(), AppAlerts.decode("not json"))
    }
}
