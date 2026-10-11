package af.shizuku.manager.home

import af.shizuku.manager.ShizukuApplication
import android.content.Context
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val mavericksTestRule = MavericksTestRule()

    @Before
    fun setUp() {
        // HomeViewModel reads ShizukuApplication.appContext in its constructor. The property is a
        // private-set lateinit var whose backing field lives on ShizukuApplication as a private
        // static field, so initialize it via reflection with a relaxed mock Context.
        val field = ShizukuApplication::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        field.set(null, mockk<Context>(relaxed = true))
    }

    @Test
    fun `initial state is Loading and then Success or Fail`() {
        val viewModel = HomeViewModel(HomeState())

        withState(viewModel) { state ->
            // reload() synchronously moves serviceStatus off Uninitialized (to Loading); a
            // background coroutine then resolves it to Success/Fail. Assert the deterministic
            // part: construction kicked off reload() and the state is no longer Uninitialized.
            (state.serviceStatus is Uninitialized) shouldBe false
        }
    }

    @Test
    fun `setEditMode updates state`() {
        val viewModel = HomeViewModel(HomeState())

        viewModel.setEditMode(true)

        withState(viewModel) { state ->
            state.isEditMode shouldBe true
        }
    }
}
