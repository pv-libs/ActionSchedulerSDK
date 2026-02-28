# **Mobile TLM Assignment**

### 🧠 Overview

Design and build an Action Scheduler SDK that can be integrated into any mobile
app.
The goal is to enable developers to schedule and run tasks locally on the device,
using flexible recurrence rules. The SDK determines when to run a task, while the
app developer decides what the task actually does.

### 💡 Practical Use Cases

- Scheduling regular bill payment reminders (e.g., every 1st of the month)
- A weekly or bi-weekly nudge to check balance for mindful spending
- A reminder to pay a friend on a certain date
- Auto-save a fixed amount daily to DigiGold
- Auto-recharge on 1st of every month

### ⚙ What the SDK Should Do

- Allow developers to register actions with a schedule, such as:
    - “Every day at 9 AMˮ
    - “Every Mondayˮ
    - “On the 1st of every monthˮ
- Ensure actions run reliably even if:
    - The app restarts
    - The device goes offline or idle
- Maintain a log of failed task executions. Failure may be due to varied reasonslike device switched-off etc
- Keep track of past runs (time, status, duration)
    - Provide a simple API to query recent executions, both successful ones orfailed ones
- Offer a notification flag option:
    - Developers can tie an action with a predefined notification
    - Notification is triggered a set time (e.g., 24 hours) before the actual actionruns
    - Example: Reminder notification for AutoPay
    

### 🎯 Expectations

- SDK must be designed as a KMP cross-platform framework usable across Android and iOS.
- A sample app (Android or iOS) must be included to demonstrate SDK integration.
    - Should include at least two example scheduled actions.
- Include a short design document (2-3 pages) explaining:
    - The architecture and design rationale
    - How persistence, scheduling, and observability are handled
    - Trade-offs / assumptions made
    - Future scope or enhancements

### 📦 Deliverables

- 🧰 Code repository with:
    - SDK implementation
    - Sample app
- 🧾 Design document with:
    - Diagrams
    - Architectural reasoning
