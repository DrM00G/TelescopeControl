package com.example.telescopecontrol

import com.example.telescopecontrol.diagnostics.EngineeringSessionLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class EngineeringSessionLoggerTest {
    private fun snapshot(logger: EngineeringSessionLogger): String {
        val done = CountDownLatch(1)
        var result: Result<String>? = null
        logger.snapshot { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    @Test fun sessionIsPersistentAndOrderedAcrossReopen() {
        val dir = Files.createTempDirectory("engineering-test").toFile()
        val logger = EngineeringSessionLogger(dir, elapsedMillis = { 123L }, wallMillis = { 1_700_000_000_000L })
        logger.startSession("fake usb", 2)
        logger.record("WIRE_TX", 3, "bytes=50 01")
        logger.record("WIRE_RX", 3, "bytes=23")
        logger.endSession(4, "test")
        val text = snapshot(logger)
        assertTrue(text.contains("elapsed_ms=123 seq=1 gen=2 category=USB"))
        assertTrue(text.contains("seq=2 gen=3 category=WIRE_TX bytes=50 01"))
        assertTrue(text.indexOf("WIRE_TX") < text.indexOf("WIRE_RX"))
        assertTrue(text.contains("SESSION_END test"))
        logger.close()
        val reopened = EngineeringSessionLogger(dir)
        assertEquals(text, snapshot(reopened))
        reopened.close()
    }

    @Test fun retentionNeverDeletesCurrentSessionWhenTimestampsTie() {
        val dir = Files.createTempDirectory("engineering-ties").toFile()
        val fixedTime = 1_700_000_000_000L
        repeat(10) {
            File(dir, "session-old-$it.log").apply { writeText("old"); setLastModified(Long.MAX_VALUE) }
        }
        val logger = EngineeringSessionLogger(dir, wallMillis = { fixedTime })
        logger.startSession("current", 1)
        logger.record("WIRE_RX", 1, "bytes=23")
        logger.endSession(1, "done")
        assertTrue(snapshot(logger).contains("WIRE_RX bytes=23"))
        assertEquals(10, dir.listFiles()!!.count { it.name.endsWith(".log") })
        logger.close()
    }

    @Test fun snapshotsCaptureOnlyRecordsBeforeEachRequest() {
        val logger = EngineeringSessionLogger(Files.createTempDirectory("engineering-snapshot").toFile())
        logger.startSession("fake", 0)
        logger.record("INTENT", 1, "first")
        val done = CountDownLatch(1)
        var first = ""
        logger.snapshot { first = it.getOrThrow(); done.countDown() }
        logger.record("INTENT", 2, "second")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(first.contains("first")); assertFalse(first.contains("second"))
        assertTrue(snapshot(logger).contains("second"))
        logger.close()
    }

    @Test fun sessionSizeIsBoundedAndTruncationIsVisible() {
        val dir = Files.createTempDirectory("engineering-size").toFile()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val logger = EngineeringSessionLogger(dir, onError = { errors.add(it) }, maxSessionBytes = 1024)
        logger.startSession("fake", 0)
        repeat(20) { logger.record("WIRE_RX", 1, "x".repeat(200)) }
        val text = snapshot(logger)
        assertTrue(text.contains("LOG_TRUNCATED"))
        assertTrue(text.toByteArray(Charsets.UTF_8).size < 1200)
        assertTrue(errors.any { it.contains("size limit") })
        logger.close()
    }

    @Test fun reportsSnapshotFailureForMissingLog() {
        val logger = EngineeringSessionLogger(Files.createTempDirectory("engineering-empty").toFile())
        val done = CountDownLatch(1)
        var failed = false
        logger.snapshot { failed = it.isFailure; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failed)
        logger.close()
    }

    @Test fun retainsOnlyTenSessions() {
        val dir = Files.createTempDirectory("engineering-retention").toFile()
        val logger = EngineeringSessionLogger(dir)
        repeat(12) {
            logger.startSession("fake $it", it.toLong())
            logger.endSession(it.toLong(), "done")
        }
        snapshot(logger)
        assertEquals(10, dir.listFiles()!!.count { it.name.endsWith(".log") })
        logger.close()
    }
}
