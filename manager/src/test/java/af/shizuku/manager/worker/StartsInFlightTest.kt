package af.shizuku.manager.worker

import af.shizuku.manager.adb.StartsInFlight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The quick-settings tile's supervision, in virtual time. */
class StartsInFlightTest :
    FunSpec({

        val grace = 25_000L

        test("settles after the grace when nothing ever starts") {
            runTest {
                val starts = StartsInFlight()
                val stalled = async { starts.awaitStalled(MutableStateFlow(true), grace) }

                advanceTimeBy(grace - 1)
                runCurrent()
                stalled.isCompleted shouldBe false

                advanceTimeBy(1)
                runCurrent()
                stalled.await() shouldBe 0L
            }
        }

        test("a start that begins and ends between two looks restarts the grace") {
            runTest {
                val starts = StartsInFlight()
                val stalled = async { starts.awaitStalled(MutableStateFlow(true), grace) }
                runCurrent()

                advanceTimeBy(20_000)
                starts.begin()
                starts.end()
                runCurrent()

                advanceTimeBy(grace - 1)
                runCurrent()
                stalled.isCompleted shouldBe false

                advanceTimeBy(1)
                runCurrent()
                stalled.await() shouldBe 2L
                currentTime shouldBe 20_000L + grace
            }
        }

        test("never settles while a start is in flight, however long, and settles a grace after it ends") {
            runTest {
                val starts = StartsInFlight()
                starts.begin()
                val stalled = async { starts.awaitStalled(MutableStateFlow(true), grace) }

                advanceTimeBy(10 * grace)
                runCurrent()
                stalled.isCompleted shouldBe false

                starts.end()
                runCurrent()
                advanceTimeBy(grace)
                runCurrent()
                stalled.await() shouldBe 2L
            }
        }

        test("stops without settling once the state leaves STARTING, even mid-grace") {
            runTest {
                val starts = StartsInFlight()
                val starting = MutableStateFlow(true)
                val stalled = async { starts.awaitStalled(starting, grace) }
                runCurrent()

                advanceTimeBy(grace / 2)
                starting.value = false
                runCurrent()
                stalled.await() shouldBe null
            }
        }

        test("track counts a block that throws and still ends it") {
            val starts = StartsInFlight()
            runCatching { starts.track { error("boom") } }
            starts.state.value.running shouldBe 0
            starts.state.value.generation shouldBe 2L
        }

        test("a start that began after the grace ran out stops the settling, even if it already ended") {
            val starts = StartsInFlight()
            val idleAt = starts.state.value.generation
            var settled = 0

            starts.begin()
            starts.settleIfIdleSince(idleAt) { settled++ } shouldBe false
            starts.end()
            starts.settleIfIdleSince(idleAt) { settled++ } shouldBe false
            settled shouldBe 0

            starts.settleIfIdleSince(starts.state.value.generation) { settled++ } shouldBe true
            settled shouldBe 1
        }

        test("a start beginning on another thread while settling waits until the transition is made") {
            val starts = StartsInFlight()
            val settling = CountDownLatch(1)
            var runningWhenSettled = -1
            lateinit var starter: Thread

            starts.settleIfIdleSince(starts.state.value.generation) {
                starter =
                    thread {
                        settling.countDown()
                        starts.begin()
                    }
                settling.await(5, TimeUnit.SECONDS) shouldBe true
                // Give the other thread every chance to get past begin() if it could.
                Thread.sleep(100)
                runningWhenSettled = starts.state.value.running
            } shouldBe true
            starter.join(5_000)

            runningWhenSettled shouldBe 0
            starts.state.value.running shouldBe 1
        }

        test("settleWhenStalled settles once after the grace, and not at all if STARTING ends first") {
            runTest {
                val starts = StartsInFlight()
                var settled = 0
                val done = async { starts.settleWhenStalled(MutableStateFlow(true), grace) { settled++ } }
                advanceTimeBy(grace)
                runCurrent()
                done.await() shouldBe true
                settled shouldBe 1

                val starting = MutableStateFlow(true)
                val abandoned = async { starts.settleWhenStalled(starting, grace) { settled++ } }
                runCurrent()
                starting.value = false
                runCurrent()
                abandoned.await() shouldBe false
                settled shouldBe 1
            }
        }
    })
