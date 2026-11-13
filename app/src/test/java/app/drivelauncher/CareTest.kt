package app.drivelauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backup / restore, update answers and break-reminder wording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CareTest {

    @Test fun backupRoundTrip() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.ss("pages_glass", """[{"layout":"aurora"}]""")
        Prefs.breakMin = 90
        Prefs.autoUpdate = false
        Prefs.sl("odoTest", 123_456L)
        File(app.filesDir, "trips").mkdirs()
        File(app.filesDir, "trips/1700000000000.csv").writeText("13.7,100.5,42")
        File(app.filesDir, "fuel.json").writeText("[]")
        val out = ByteArrayOutputStream()
        Backup.write(app, out)

        Prefs.ss("pages_glass", "changed")
        Prefs.breakMin = 0
        Prefs.autoUpdate = true
        File(app.filesDir, "fuel.json").writeText("[1]")
        File(app.filesDir, "later.txt").writeText("not in the backup")

        val what = Backup.restore(app, ByteArrayInputStream(out.toByteArray()))
        assertTrue(what.isNotEmpty())
        assertEquals("""[{"layout":"aurora"}]""", Prefs.gs("pages_glass"))
        assertEquals(90, Prefs.breakMin)
        assertFalse(Prefs.autoUpdate)
        assertEquals(123_456L, Prefs.gl("odoTest", 0L))
        assertEquals("13.7,100.5,42", File(app.filesDir, "trips/1700000000000.csv").readText())
        assertEquals("[]", File(app.filesDir, "fuel.json").readText())
        assertFalse(File(app.filesDir, "later.txt").exists())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }

    @Test fun restoreRefusesOtherFiles() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.ss("keepMe", "yes")
        try {
            Backup.restore(app, ByteArrayInputStream(zip("photo.jpg" to "x")))
            fail("should refuse")
        } catch (_: IOException) {}
        assertEquals("yes", Prefs.gs("keepMe"))
    }

    @Test fun restoreStaysInsideTheAppFolder() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.init(app)
        val js = """{"kind":"drive-launcher-backup","version":"x","time":0,"prefs":[]}"""
        Backup.restore(app, ByteArrayInputStream(zip("backup.json" to js, "files/../../escape.txt" to "bad", "files/ok.txt" to "good")))
        assertEquals("good", File(app.filesDir, "ok.txt").readText())
        assertFalse(File(app.filesDir.parentFile, "escape.txt").exists())
        assertFalse(File(app.filesDir.parentFile?.parentFile, "escape.txt").exists())
    }

    @Test fun updateAnswer() {
        Updater.parse(JSONObject("""{"tag_name":"build-99999","name":"Drive Launcher 0.6.99999",
            "body":"New premium car\n\n- Driving look","assets":[{"name":"other.txt","browser_download_url":"https://x/o"},
            {"name":"DriveLauncher.apk","browser_download_url":"https://x/DriveLauncher.apk"}]}"""))
        assertTrue(Updater.available)
        assertEquals("0.6.99999", Updater.latestName)
        assertEquals("New premium car", Updater.highlights())
        Updater.parse(JSONObject("""{"tag_name":"build-1","name":"Drive Launcher 0.6.1","assets":[]}"""))
        assertFalse(Updater.available)
    }

    @Test fun crashReportAndLoopGuard() {
        val r = Crash.report("main", IllegalStateException("boom"), 0L)
        assertTrue(r.startsWith("=== Crash "))
        assertTrue(r.contains("IllegalStateException: boom"))
        assertTrue(Crash.shouldRestart(listOf(1L)))
        assertTrue(Crash.shouldRestart(listOf(1L, 2L)))
        assertFalse(Crash.shouldRestart(listOf(1L, 2L, 3L)))
    }

    @Test fun breakWords() {
        assertEquals("2 hours", BreakReminder.spoken(120))
        assertEquals("2 hours", BreakReminder.spoken(123))
        assertEquals("1 hour and 30 minutes", BreakReminder.spoken(90))
        assertEquals("3 hours and 5 minutes", BreakReminder.spoken(186))
        assertEquals("45 minutes", BreakReminder.spoken(45))
    }
}
