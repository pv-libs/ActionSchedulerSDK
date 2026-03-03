# Action Scheduler SDK (Kotlin Multiplatform)

This repository contains:

- **SDK module**: [`action_scheduler`](./action_scheduler)
- **Sample app (Compose Multiplatform)**: [`composeApp`](./composeApp)
- **iOS host app (SwiftUI)**: [`iosApp`](./iosApp)

The SDK lets app developers register recurring or one-time **actions** and execute them with OS-backed background scheduling on Android and iOS.

---

## What the SDK currently provides

- KMP-first scheduling API (`commonMain`).
- Recurrence support:
  - `OneTime(at)`
  - `Daily(hour, minute)`
  - `Weekly(dayOfWeekIso, hour, minute, skipWeeks)`
  - `Monthly(dayOfMonth, hour, minute)`
- Action execution via `ActionHandlerFactory`.
- Optional pre-action reminders (`notificationOffsetMinutes`).
- Retry support for retryable failures (max 3 retries, exponential backoff).
- Persistent schedule + execution history (Room + bundled SQLite).
- Reactive observability with `Flow`:
  - `getRegisteredActions()`
  - `getExecutionLogs()`

---

## Public API (current)

```kotlin
interface ActionScheduler {
    suspend fun registerAction(spec: ActionSpec): RegistrationResult
    suspend fun cancelAction(actionId: String)

    fun getRegisteredActions(): Flow<List<ActionSpec>>
    fun getExecutionLogs(): Flow<List<ExecutionLog>>

    fun setNotificationHandler(handler: NotificationHandler?)
}

fun interface ActionHandler {
    suspend fun onExecute(invocation: ActionInvocation): ActionHandlerResult
}

fun interface ActionHandlerFactory {
    fun create(actionType: String): ActionHandler?
}
```

> Note: there is no `registerHandler(...)` API on `ActionScheduler`. Handlers are resolved through `ActionHandlerFactory`, passed at initialization.

---

## Setup

### 1) Add dependency

Inside this repository, the sample app depends on the local project module:

```kotlin
dependencies {
    implementation(project(":action_scheduler"))
}
```

If you publish the SDK, consumers can use your Maven coordinates instead.

### 2) Initialize early on app startup

Initialize `ActionSchedulerKit` as early as possible so worker dispatch can resolve handlers.

#### Android

Initialize from `Application.onCreate()`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val notificationHandler: NotificationHandler = { notification ->
            // show local notification
        }

        val scheduler = ActionSchedulerKit.initialize(
            actionHandlerFactory = SampleActionHandlerFactory(notificationHandler),
            config = ActionSchedulerConfig(
                platformContext = this, // required on Android
            ),
        )

        scheduler.setNotificationHandler(notificationHandler)
    }
}
```

Sample Android permissions are in
[`composeApp/src/androidMain/AndroidManifest.xml`](./composeApp/src/androidMain/AndroidManifest.xml):

- `android.permission.POST_NOTIFICATIONS` (Android 13+)
- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`

#### iOS

Initialize from Swift startup (sample):

```swift
SampleIosBootstrapKt.initializeSampleActionSchedulerIos()
```

Current sample bootstrap configures task ID:

- `com.pv_libs.sampleactionscheduler.action_executor`

Ensure `Info.plist` includes:

- `BGTaskSchedulerPermittedIdentifiers` with `com.pv_libs.sampleactionscheduler.action_executor`
- `UIBackgroundModes` with at least `processing` (sample includes `processing` and `fetch`)

---

## Registering actions

### ActionSpec fields

```kotlin
data class ActionSpec(
    val actionId: String,
    val actionType: String,
    val payloadJson: String,
    val recurrence: RecurrenceRule,
    val timezoneId: String,
    val notificationOffsetMinutes: Int? = null,
    val notificationTitle: String? = null,
    val notificationDescription: String? = null,
    val enabled: Boolean = true,
    val constraints: ActionConstraints = ActionConstraints(),
)
```

### Example: monthly reminder

```kotlin
val spec = ActionSpec(
    actionId = "pay_rent",
    actionType = "PAYMENT_REMINDER",
    payloadJson = "{\"amount\":12000}",
    recurrence = RecurrenceRule.Monthly(dayOfMonth = 1, hour = 9, minute = 0),
    notificationOffsetMinutes = 24 * 60,
    notificationTitle = "Rent due tomorrow",
    notificationDescription = "Open the app to pay rent",
    constraints = ActionConstraints(requiresNetwork = true),
)

val result = scheduler.registerAction(spec)
```

### Example: weekly vs bi-weekly

```kotlin
// Every Monday at 09:00
RecurrenceRule.Weekly(dayOfWeekIso = 1, hour = 9, minute = 0, skipWeeks = 0)

// Every 2 weeks on Monday at 09:00
RecurrenceRule.Weekly(dayOfWeekIso = 1, hour = 9, minute = 0, skipWeeks = 1)
```

### Cancel an action

```kotlin
scheduler.cancelAction("pay_rent")
```

---

## Action handlers

Handlers are resolved by `actionType` through your `ActionHandlerFactory`.

```kotlin
class MyActionHandlerFactory : ActionHandlerFactory {
    override fun create(actionType: String): ActionHandler? {
        return when (actionType) {
            "PAYMENT_REMINDER" -> ActionHandler { invocation ->
                // business logic
                ActionHandlerResult.Success
            }
            else -> null
        }
    }
}
```

If no handler is returned for an action type, execution is logged as `HANDLER_NOT_FOUND`.

---

## Internal behavior (how scheduling works)

The SDK uses a **single-runner** approach:

1. `registerAction(...)` validates + upserts `ActionSpec`.
2. It computes the next due execution and stores it as `PENDING`.
3. If reminder offset exists, it also stores a `-reminder` execution.
4. It always schedules a single OS worker for the nearest pending execution.
5. After each dispatch, it reconciles and schedules the next nearest one.

Current runner task ID in SDK internals:

- `com.pv_libs.sampleactionscheduler.action_executor`

### Retry model

- Max retries: **3**.
- Backoff: `max(backoffDelayMs, 5000) * 2^attempt`.
- Each retry is persisted as a separate execution row (`-retryN` suffix).

### Validation rules

`registerAction` rejects invalid specs (`REJECTED_INVALID_PARAMS`) when:

- `actionId` or `actionType` is blank
- `notificationOffsetMinutes` is negative
- time/day fields are out of supported ranges
- one-time schedule is in the past

### Persistence details

- Uses Room entities for schedules + executions.
- Execution rows are trimmed to `maxExecutionLogs` (minimum enforced retention: 20).
- Current DB setup uses `fallbackToDestructiveMigration(true)`.

---

## Observability

Use flows for real-time state:

```kotlin
val actionsFlow: Flow<List<ActionSpec>> = scheduler.getRegisteredActions()
val logsFlow: Flow<List<ExecutionLog>> = scheduler.getExecutionLogs()
```

`RunStatus` enum currently includes:

- `SUCCESS`, `FAILED`, `NOT_FOUND`, `DEDUPE_SKIPPED`, `NOTIFICATION_SENT`,
  `HANDLER_NOT_FOUND`, `RUNNING`, `PENDING`

---

## Sample app guide

The sample demonstrates:

- App startup initialization on Android and iOS
- Factory-based handler registration with 2 action types (`CUSTOM_REMINDER`, `DIGI_GOLD`)
- Daily/weekly/bi-weekly/monthly/one-time scheduling from UI
- Optional reminder offset notifications
- Real-time list of registered actions and execution logs

Key files:

- Android startup: [`SampleActionSchedulerApp.kt`](./composeApp/src/androidMain/kotlin/com/pv_libs/sampleactionscheduler/SampleActionSchedulerApp.kt)
- iOS startup: [`SampleIosBootstrap.kt`](./composeApp/src/iosMain/kotlin/com/pv_libs/sampleactionscheduler/SampleIosBootstrap.kt)
- Handler factory: [`SampleActionHandlerFactory.kt`](./composeApp/src/commonMain/kotlin/com/pv_libs/sampleactionscheduler/SampleActionHandlerFactory.kt)
- Scheduler UI: [`HomeScreen.kt`](./composeApp/src/commonMain/kotlin/com/pv_libs/sampleactionscheduler/HomeScreen.kt)

---

## Build / run

From repository root:

```bash
./gradlew :action_scheduler:assemble
./gradlew :composeApp:assembleDebug
```

Current tests in `action_scheduler` are template sample tests; comprehensive SDK behavior tests are not added yet.

---

## Notes and limitations

- iOS background scheduling is best-effort (BGTaskScheduler timing is not exact).
- If app is force-quit on iOS, background execution may stop until relaunch.
- There is no separate `updateAction(...)` API yet; re-register with same `actionId` to upsert.
- This repository currently wires the SDK as a local module (no published artifact in repo).
