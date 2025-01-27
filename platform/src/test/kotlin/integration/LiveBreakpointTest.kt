package integration

import spp.protocol.SourceMarkerServices
import spp.protocol.SourceMarkerServices.Provide
import spp.protocol.instrument.LiveInstrumentBatch
import spp.protocol.instrument.LiveInstrumentEvent
import spp.protocol.instrument.LiveInstrumentEventType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.breakpoint.event.LiveBreakpointHit
import spp.protocol.service.live.LiveInstrumentService
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.serviceproxy.ServiceProxyBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.service.error.LiveInstrumentException
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class LiveBreakpointTest : PlatformIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveBreakpointTest::class.java)

    @Test
    fun verifyLiveVariables() {
        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = UUID.randomUUID().toString()

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_ADDED -> {
                    log.info("Got added")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotAdded = true
                }
                LiveInstrumentEventType.BREAKPOINT_APPLIED -> {
                    log.info("Got applied")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotApplied = true
                }
                LiveInstrumentEventType.BREAKPOINT_REMOVED -> {
                    log.info("Got removed")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("breakpointId"))
                    }
                    gotRemoved = true
                }
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("breakpointId"))
                    }
                    gotHit = true

                    val bpHit = Json.decodeValue(liveEvent.data, LiveBreakpointHit::class.java)
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(10, topFrame.variables.size)

                        //byte
                        assertEquals(-2, topFrame.variables.find { it.name == "b" }!!.value)
                        assertEquals("java.lang.Byte", topFrame.variables.find { it.name == "b" }!!.liveClazz)

                        //char
                        assertEquals("h", topFrame.variables.find { it.name == "c" }!!.value)
                        assertEquals("java.lang.Character", topFrame.variables.find { it.name == "c" }!!.liveClazz)

                        //string
                        assertEquals("hi", topFrame.variables.find { it.name == "s" }!!.value)
                        assertEquals("java.lang.String", topFrame.variables.find { it.name == "s" }!!.liveClazz)

                        //double
                        assertEquals(0.23, topFrame.variables.find { it.name == "d" }!!.value)
                        assertEquals("java.lang.Double", topFrame.variables.find { it.name == "d" }!!.liveClazz)

                        //bool
                        assertEquals(true, topFrame.variables.find { it.name == "bool" }!!.value)
                        assertEquals("java.lang.Boolean", topFrame.variables.find { it.name == "bool" }!!.liveClazz)

                        //long
                        assertEquals(Long.MAX_VALUE, topFrame.variables.find { it.name == "max" }!!.value)
                        assertEquals("java.lang.Long", topFrame.variables.find { it.name == "max" }!!.liveClazz)

                        //short
                        assertEquals(Short.MIN_VALUE.toInt(), topFrame.variables.find { it.name == "sh" }!!.value)
                        assertEquals("java.lang.Short", topFrame.variables.find { it.name == "sh" }!!.liveClazz)

                        //float
                        assertEquals(1.0, topFrame.variables.find { it.name == "f" }!!.value)
                        assertEquals("java.lang.Float", topFrame.variables.find { it.name == "f" }!!.liveClazz)

                        //integer
                        assertEquals(1, topFrame.variables.find { it.name == "i" }!!.value)
                        assertEquals("java.lang.Integer", topFrame.variables.find { it.name == "i" }!!.liveClazz)
                    }
                }
                else -> testContext.failNow("Got event: " + it.body())
            }

            if (gotAdded && gotHit && gotRemoved) {
                consumer.unregister {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause());
                return@completionHandler
            }

            val instrumentService = ServiceProxyBuilder(vertx)
                .setToken(SYSTEM_JWT_TOKEN)
                .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
                .build(LiveInstrumentService::class.java)
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    id = instrumentId,
                    location = LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 25),
                )
            ) {
                if (it.failed()) {
                    testContext.failNow(it.cause())
                }
            }
        }

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                consumer.unregister()
                log.info("Got added: $gotAdded")
                log.info("Got applied: $gotApplied")
                log.info("Got hit: $gotHit")
                log.info("Got removed: $gotRemoved")
                throw testContext.causeOfFailure()
            }
        } else {
            consumer.unregister()
            log.info("Got added: $gotAdded")
            log.info("Got applied: $gotApplied")
            log.info("Got hit: $gotHit")
            log.info("Got removed: $gotRemoved")
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun addHitRemove() {
        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = UUID.randomUUID().toString()

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.endHandler {
            log.info("Stopped listening at: {}", "local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        }.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_ADDED -> {
                    log.info("Got added")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotAdded = true
                }
                LiveInstrumentEventType.BREAKPOINT_APPLIED -> {
                    log.info("Got applied")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotApplied = true
                }
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("breakpointId"))
                    }
                    gotHit = true
                }
                LiveInstrumentEventType.BREAKPOINT_REMOVED -> {
                    log.info("Got removed")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("breakpointId"))
                    }
                    gotRemoved = true
                }
                else -> testContext.failNow("Got event: " + it.body())
            }

            if (gotAdded && gotHit && gotRemoved) {
                consumer.unregister {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }
        }.completionHandler {
            log.info("Started listening at: {}", "local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
            if (it.failed()) {
                testContext.failNow(it.cause());
                return@completionHandler
            }

            val instrumentService = ServiceProxyBuilder(vertx)
                .setToken(SYSTEM_JWT_TOKEN)
                .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
                .build(LiveInstrumentService::class.java)
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    id = instrumentId,
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    condition = "2==2"
                )
            ) {
                if (it.failed()) {
                    testContext.failNow(it.cause())
                }
            }
        }

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                consumer.unregister()
                log.info("Got added: $gotAdded")
                log.info("Got applied: $gotApplied")
                log.info("Got hit: $gotHit")
                log.info("Got removed: $gotRemoved")
                throw testContext.causeOfFailure()
            }
        } else {
            consumer.unregister()
            log.info("Got added: $gotAdded")
            log.info("Got applied: $gotApplied")
            log.info("Got hit: $gotHit")
            log.info("Got removed: $gotRemoved")
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun removeById() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2"
            )
        ) {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstrument(originalId) {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(originalId, it.result()!!.id!!)
                        }
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun removeByLocation() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2"
            )
        ) {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("spp.example.webapp.model.User", 42)
                ) {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(1, it.result().size)
                            assertEquals(originalId, it.result()!![0].id!!)
                            testContext.completeNow()
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun removeMultipleByLocation() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstruments(
            LiveInstrumentBatch(
                listOf(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.model.User", 42),
                        condition = "1==2"
                    ),
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.model.User", 42),
                        condition = "1==3"
                    )
                )
            )
        ) {
            if (it.succeeded()) {
                testContext.verify { assertEquals(2, it.result().size) }
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("spp.example.webapp.model.User", 42)
                ) {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(2, it.result().size)
                            testContext.completeNow()
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun addBreakpointWithInvalidCondition() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1===2",
                applyImmediately = true
            )
        ) {
            if (it.failed()) {
                if (it.cause().cause is LiveInstrumentException) {
                    testContext.verify {
                        assertEquals(
                            "Expression [1===2] @1: EL1042E: Problem parsing right operand",
                            it.cause().cause!!.message
                        )
                    }
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause().cause ?: it.cause())
                }
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @RepeatedTest(2) //ensures can try again (in case things have changed on probe side)
    fun applyImmediatelyWithInvalidClass() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("bad.Clazz", 48),
                applyImmediately = true
            )
        ) {
            if (it.failed()) {
                testContext.verify {
                    assertNotNull(it.cause().cause)
                    assertTrue(it.cause().cause is LiveInstrumentException)
                    val ex = it.cause().cause as LiveInstrumentException
                    assertEquals(LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, ex.errorType)
                    assertEquals("bad.Clazz", it.cause().cause!!.message)
                }
                testContext.completeNow()
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
