package af.shizuku.manager.admin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The Device Owner guard (#688) depends on this seeing Secure Folder and the other profile kinds. */
class OwnerActivationUsersTest {
    @Test
    fun onlyTheMainUserMeansNothingElse() {
        val out = "Users:\n\tUserInfo{0:Austin Folsom:4c13} running\n"
        assertEquals(emptyList<String>(), OwnerActivation.parseOtherUsers(out, 0))
    }

    @Test
    fun secureFolderAndOtherProfilesAreListed() {
        val out =
            "Users:\n" +
                "\tUserInfo{0:Owner:c13} running\n" +
                "\tUserInfo{95:Dual Messenger:1030} running\n" +
                "\tUserInfo{150:Secure Folder:1010} running\n" +
                "\tUserInfo{11:Private space:4000} running\n"
        assertEquals(
            listOf("Dual Messenger (95)", "Secure Folder (150)", "Private space (11)"),
            OwnerActivation.parseOtherUsers(out, 0),
        )
    }

    @Test
    fun unreadableOutputIsUnknownNotEmpty() {
        assertNull(OwnerActivation.parseOtherUsers("Error: Can't find service", 0))
    }
}
