 # Action Scheduler SDK (Kotlin Multiplatform)
 
 This repo contains:
 
 - **SDK**: [`action_scheduler`](./action_scheduler)
 - **Sample UI (Compose Multiplatform)**: [`composeApp`](./composeApp)
 - **iOS host app (SwiftUI entry point)**: [`iosApp`](./iosApp)
 
 The SDK lets you register recurring / one-time **actions** (your business logic) and have them executed via **OS-backed background scheduling** on Android and iOS.
 
 ---
 
 ## Setup (use the SDK in your app)
 
 ### 1) Add dependency
 
 In this repo, the sample app uses:
 
 ```kotlin
 dependencies {
     implementation(project(":action_scheduler"))
 }
 ```
 
 If you publish this module, consumers would instead use your Maven coordinate (not included in this sample repo).
 
 ### 2) Initialize on app startup
 
 You must initialize the runtime **early** (before any background worker dispatch happens), then register action handlers.
 
 #### Android
 
 Initialize in your `Application.onCreate()` (see [`SampleActionSchedulerApp`](./composeApp/src/androidMain/kotlin/com/pv_libs/sampleactionscheduler/SampleActionSchedulerApp.kt)):
 
 ```kotlin
 val scheduler = ActionSchedulerKit.initialize(
     ActionSchedulerConfig(
         platformContext = this, // Application context
     )
 )
 
 scheduler.registerHandler("YOUR_ACTION_TYPE") { invocation ->
     // Do your work
     ActionHandlerResult.Success
 }
 
 scheduler.setNotificationHandler { notification ->
     // Show a local notification (your implementation)
 }
 ```
 
 Required permissions (sample) live in [`composeApp/src/androidMain/AndroidManifest.xml`](./composeApp/src/androidMain/AndroidManifest.xml):
 
 - **`android.permission.POST_NOTIFICATIONS`** (Android 13+)
 - **`android.permission.INTERNET`** / **`android.permission.ACCESS_NETWORK_STATE`** (only needed if your action does network)
 
 #### iOS
 
 Initialize from Swift early (see [`iosApp/iosApp/iOSApp.swift`](./iosApp/iosApp/iOSApp.swift)):
 
 ```swift
 SampleIosBootstrapKt.initializeSampleActionSchedulerIos()
 ```
 
 Under the hood (see [`SampleIosBootstrap.kt`](./composeApp/src/iosMain/kotlin/com/pv_libs/sampleactionscheduler/SampleIosBootstrap.kt)) the SDK is initialized as:
 
 ```kotlin
 ActionSchedulerKit.initialize(
     ActionSchedulerConfig(
         iosTaskIds = setOf("com.pv_libs.action_scheduler.runner"),
     )
 )
 ```
 
 Also update your iOS `Info.plist` (see sample [`iosApp/iosApp/Info.plist`](./iosApp/iosApp/Info.plist)):
 
 - **`BGTaskSchedulerPermittedIdentifiers`** includes `kmp_chain_executor_task`
 - **`UIBackgroundModes`** includes `processing`
 
 ---
 
 ## Features available
 
 - **Recurrence rules** via `RecurrenceRule`:
   - `OneTime(at)`
   - `Daily(hour, minute)`
   - `Weekly(dayOfWeekIso, hour, minute)`
   - `Monthly(dayOfMonth, hour, minute)`
 - **Timezone-aware scheduling** via `ActionSpec.timezoneId`.
 - **OS-backed execution** through `kmpworkmanager` (Android WorkManager, iOS BGTaskScheduler).
 - **Optional pre-action notifications** via `notificationOffsetMinutes`.
 - **Retries with exponential backoff** when your handler returns retryable failure.
 - **Persistence + observability**:
   - `getRegisteredActions(): Flow<List<ActionSpec>>`
   - `getExecutionLogs(): Flow<List<ExecutionLog>>`
 
 ---
 
 ## Quick start: register an action
 
 Create an `ActionSpec` and call `registerAction`:
 
 ```kotlin
 val spec = ActionSpec(
     actionId = "pay_rent",
     actionType = "PAYMENT_REMINDER",
     payloadJson = "{\"amount\":12000}",
     recurrence = RecurrenceRule.Monthly(dayOfMonth = 1, hour = 9, minute = 0),
     notificationOffsetMinutes = 24 * 60,
     notificationTitle = "Rent due tomorrow",
     notificationDescription = "Open the app to pay rent",
     constraints = ActionConstraints(requiresNetwork = false),
 )
 
 val result = scheduler.registerAction(spec)
 ```
 
 Then register a handler for the `actionType`:
 
 ```kotlin
 scheduler.registerHandler("PAYMENT_REMINDER") { invocation ->
     // Use invocation.payloadJson, invocation.scheduledAt, etc.
     ActionHandlerResult.Success
 }
 ```
 
 ---
 
 ## Internal workings (how it works)
 
 This SDK is implemented as a **single-runner architecture**:
 
 - For every registered `ActionSpec`, the SDK persists the **next due execution(s)** as rows in a Room-backed database.
 - A single OS task (ID: `com.pv_libs.action_scheduler.runner`) is always kept scheduled for the **nearest pending execution**.
 - After each dispatch (success/failure/retry), the SDK reconciles again and re-schedules the runner to point at the next nearest pending execution.
 
 ### Architecture (high level)
 
 ```mermaid
 flowchart LR
     App[Host App] --> Kit[ActionSchedulerKit.initialize]
     Kit --> Scheduler[DefaultActionScheduler]
 
     Scheduler --> Store[(Room DB)]
     Scheduler --> Engine[SchedulerEngine]
     Scheduler --> Handlers[Action handlers map]
     Scheduler --> Notif[NotificationHandler]
 
     Engine --> KmpWM[kmpworkmanager]
     KmpWM --> WM[Android WorkManager]
     KmpWM --> BG[iOS BGTaskScheduler]
 
     WM --> Worker[Dispatch worker]
     BG --> Worker
     Worker --> Kit
     Kit --> Scheduler
 ```
 
 ### Persistence model (practical)
 
 Internally we persist:
 
 - **Schedules** (from `ActionSpec`)
 - **Executions** (one row per planned run, retry attempt, and pre-notification)
 
 Execution logs are trimmed to `ActionSchedulerConfig.maxExecutionLogs`.
 
 ### Register flow
 
 When you call `registerAction(spec)`:
 
 - **Validate** the schedule.
 - **Upsert** the schedule in the DB.
 - Compute the **next occurrence** (calendar-aware; one-shot scheduling).
 - Insert a **PENDING** execution row for the next action time.
 - If `notificationOffsetMinutes` is set, insert another **PENDING** execution with ID suffix `-reminder`.
 - Reconcile and schedule the single runner to the nearest pending execution.
 
 ### Execution / dispatch flow
 
 ```mermaid
 sequenceDiagram
     participant OS as OS Scheduler
     participant W as kmpworkmanager Worker
     participant Kit as ActionSchedulerKit
     participant S as DefaultActionScheduler
     participant DB as Room DB
     participant H as ActionHandler
     participant N as NotificationHandler
 
     OS->>W: Run task (payload = executionId)
     W->>Kit: dispatchFromWorker(executionId)
     Kit->>S: dispatch(executionId)
     S->>DB: load execution + schedule
 
     alt reminder execution ("-reminder")
         S->>N: onNotify(ActionNotification)
         S->>DB: mark SUCCESS
     else normal action execution
         S->>H: onExecute(ActionInvocation)
         alt handler success
             S->>DB: mark SUCCESS
             S->>DB: plan next recurrence (insert next PENDING)
         else handler failure
             alt retryable + attempts left
                 S->>DB: mark FAILED (original)
                 S->>DB: insert retry execution ("-retryN")
             else not retryable / exhausted
                 S->>DB: mark FAILED
                 S->>DB: plan next recurrence
             end
         end
     end
 
     S->>S: reconcileSingleRunner()
 ```
 
 ### Retries
 
 - Max attempts: **3**.
 - Backoff is exponential: `backoffDelayMs * 2^attempt` (see `ActionConstraints.backoffDelayMs`).
 - Each retry is a *new execution row* with ID suffix `-retryN`.
 
 ### Idempotency / duplicate triggers
 
 Dispatch is designed to be safe if the OS delivers a task more than once:
 
 - If an execution is missing, we record `RunStatus.NOT_FOUND` and return `success = true` to the worker.
 - If an execution is already processed (`status != PENDING`), dispatch returns `success = true`.
 
 (Your handlers should still be designed to be idempotent for best safety.)
 
 ---
 
 ## Sample app
 
 The sample (`composeApp`) demonstrates:
 
 - Creating an action via UI (`HomeScreen`)
 - Registering a handler (`registerSampleActionHandlers`)
 - Scheduling daily/weekly/monthly/one-time reminders
 - Optional pre-action reminders (notification offset)
 - Viewing execution logs + statuses in real time via `Flow`
 
 Key files:
 
 - **Android init**: [`SampleActionSchedulerApp.kt`](./composeApp/src/androidMain/kotlin/com/pv_libs/sampleactionscheduler/SampleActionSchedulerApp.kt)
 - **iOS init**: [`SampleIosBootstrap.kt`](./composeApp/src/iosMain/kotlin/com/pv_libs/sampleactionscheduler/SampleIosBootstrap.kt)
 - **UI**: [`HomeScreen.kt`](./composeApp/src/commonMain/kotlin/com/pv_libs/sampleactionscheduler/HomeScreen.kt)
 
 ---
 
 ## Notes / limitations
 
 - **iOS scheduling is best-effort**. BGTaskScheduler execution timing is opportunistic and may be delayed.
 - If the user **force-quits** the app, iOS may stop delivering background tasks until the app is opened again.
 
