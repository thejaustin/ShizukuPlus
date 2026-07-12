## Note on RemoteDbSyncWorkerTest.kt

I was attempting to add a test for `RemoteDbSyncWorker` that handles a `catch` block path when `fetch` throws an exception, expecting it to return `Result.retry()`.

While adding tests based on standard `WorkManager` testing requires JUnit and MockK (rather than Kotest directly using JUnit Platform due to Android dependency limitations), enabling JUnit mixed with Kotest broke existing project tests (specifically `HomeViewModelTest.kt`) via build configuration issues (`kotestJunit5` plugin configuration vs standard `useJUnitPlatform()`).

Removing `useJUnitPlatform()` from `manager/build.gradle` allows the new test to run successfully but causes Kotest tests to be ignored, failing code review.

**To resolve later:**
Rewrite `RemoteDbSyncWorkerTest.kt` completely in Kotest syntax, avoiding vanilla JUnit testing constructs, or adapt the gradle file configurations to allow mixed test execution properly. This is tracked here so a subsequent agent can pick it up.
