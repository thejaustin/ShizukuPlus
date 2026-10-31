package af.shizuku.manager.utils

import af.shizuku.manager.utils.ShizukuIssues.AlertLevel
import af.shizuku.manager.utils.ShizukuIssues.Route
import af.shizuku.manager.utils.ShizukuIssues.Severity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Where a problem shows, from whether the Watchdog notification is up and "Problem alerts". */
class ShizukuIssuesRouteTest :
    FunSpec({
        val inOngoing = Route(inOngoing = true, shared = false)
        val both = Route(inOngoing = true, shared = true)
        val shared = Route(inOngoing = false, shared = true)
        val nowhere = Route(inOngoing = false, shared = false)

        test("with the Watchdog notification up, every problem shows in it") {
            for (level in AlertLevel.entries) {
                ShizukuIssues.route(true, level, Severity.PROBLEM) shouldBe inOngoing
            }
            ShizukuIssues.route(true, AlertLevel.OFF, Severity.URGENT) shouldBe inOngoing
        }

        test("an urgent problem also alerts on its own unless alerts are off") {
            ShizukuIssues.route(true, AlertLevel.URGENT, Severity.URGENT) shouldBe both
            ShizukuIssues.route(true, AlertLevel.ALL, Severity.URGENT) shouldBe both
        }

        test("without it, the setting alone decides") {
            ShizukuIssues.route(false, AlertLevel.OFF, Severity.URGENT) shouldBe nowhere
            ShizukuIssues.route(false, AlertLevel.OFF, Severity.PROBLEM) shouldBe nowhere
            ShizukuIssues.route(false, AlertLevel.URGENT, Severity.URGENT) shouldBe shared
            ShizukuIssues.route(false, AlertLevel.URGENT, Severity.PROBLEM) shouldBe nowhere
            ShizukuIssues.route(false, AlertLevel.ALL, Severity.PROBLEM) shouldBe shared
        }

        test("stored values map to levels, unknown ones to the default") {
            AlertLevel.of("off") shouldBe AlertLevel.OFF
            AlertLevel.of("stopped") shouldBe AlertLevel.URGENT
            AlertLevel.of("all") shouldBe AlertLevel.ALL
            AlertLevel.of(null) shouldBe AlertLevel.URGENT
            AlertLevel.of("bogus") shouldBe AlertLevel.URGENT
        }
    })
