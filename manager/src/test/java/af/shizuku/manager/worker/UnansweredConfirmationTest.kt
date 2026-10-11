package af.shizuku.manager.worker

import af.shizuku.manager.starter.UnansweredConfirmation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** StarterActivity's question before an ADB start once a dialog has gone unanswered. */
class UnansweredConfirmationTest :
    FunSpec({

        test("an unconfirmed ADB start asks while the marker is set, and only then") {
            val confirmation = UnansweredConfirmation()
            confirmation.mayStart(adb = true, unanswered = true) shouldBe false
            confirmation.mayStart(adb = true, unanswered = false) shouldBe true
        }

        test("root and system starts never ask: they raise no adbd dialog") {
            UnansweredConfirmation().mayStart(adb = false, unanswered = true) shouldBe true
        }

        test("a Home tap, an answered question or a retry each counts as the confirmation, for later starts too") {
            val confirmation = UnansweredConfirmation()
            confirmation.mayStart(adb = true, unanswered = true) shouldBe false
            confirmation.confirm()
            confirmation.mayStart(adb = true, unanswered = true) shouldBe true
            confirmation.mayStart(adb = true, unanswered = true) shouldBe true
        }

    })
