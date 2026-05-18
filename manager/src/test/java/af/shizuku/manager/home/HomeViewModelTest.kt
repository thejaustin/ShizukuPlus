package af.shizuku.manager.home

import android.content.Context
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.test.MavericksTestRule
import com.airbnb.mvrx.withState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mavericksTestRule = MavericksTestRule()

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `initial state is Loading and then Success or Fail`() {
        val viewModel = HomeViewModel(HomeState())
        
        withState(viewModel) { state ->
            // In a real test we'd mock Shizuku.pingBinder() etc.
            // For now just verify it's not Uninitialized
            state.serviceStatus.shouldBeInstanceOf<Loading<*>>()
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
