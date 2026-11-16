package af.shizuku.manager.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** How state changes reach listeners: none skipped by a failing one, none left on a stale state. */
class StateDeliveryTest :
    FunSpec({
        test("a listener that throws doesn't stop the ones after it") {
            var current = "RUNNING"
            val heard = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val listeners = listOf<(String) -> Unit>({ error("boom") }, { heard += it })
            val delivery = StateDelivery(listeners, { current }) { _, s -> errors += s }

            delivery.deliver(current)

            heard shouldBe listOf("RUNNING")
            errors shouldBe listOf("RUNNING")
        }

        test("a delivery posted out of order still ends on the current state") {
            // Thread A wrote STARTING, then thread B wrote RUNNING, but B's post ran first.
            var current = "RUNNING"
            val heard = mutableListOf<String>()
            val delivery = StateDelivery(listOf<(String) -> Unit>({ heard += it }), { current }) { _, _ -> }

            delivery.deliver("RUNNING")
            delivery.deliver("STARTING")

            heard.last() shouldBe "RUNNING"
            heard shouldBe listOf("RUNNING", "STARTING", "RUNNING")
        }

        test("changes in order are heard once each, in order") {
            var current = "STOPPED"
            val heard = mutableListOf<String>()
            val delivery = StateDelivery(listOf<(String) -> Unit>({ heard += it }), { current }) { _, _ -> }

            // CRASHED's post runs after STARTING was already written: both are still heard.
            current = "STARTING"
            delivery.deliver("CRASHED")
            delivery.deliver("STARTING")

            heard shouldBe listOf("CRASHED", "STARTING")
        }

        test("a listener that moves the state on leaves everyone on the newer state") {
            var current = "STARTING"
            val first = mutableListOf<String>()
            val second = mutableListOf<String>()
            lateinit var delivery: StateDelivery<String>
            val listeners =
                listOf<(String) -> Unit>(
                    {
                        first += it
                        if (it == "STARTING") {
                            current = "RUNNING"
                            delivery.deliver("RUNNING")
                        }
                    },
                    { second += it },
                )
            delivery = StateDelivery(listeners, { current }) { _, _ -> }

            delivery.deliver("STARTING")

            first shouldBe listOf("STARTING", "RUNNING")
            second shouldBe listOf("RUNNING")
        }
    })
