# Notice Tracker PRD

## Product
A personal Android utility built on the existing KnowYourCase eCourts lookup/parsing setup. The user scans the QR code on a court notice; the app extracts the CNR, saves an entry immediately, fetches the case data, and tracks service against the next hearing date.

## Core goal
The app should answer:
- What notices do I have?
- Which are not served?
- Which process server is assigned?
- Which next dates are close enough to need action?

## Required scan flow
1. Tap **Scan Notice**.
2. Scan the eCourts QR code.
3. Extract and validate the 16-character CNR.
4. Create a local notice entry immediately.
5. Fetch case data using the same eCourts/WebView/CAPTCHA pipeline as KnowYourCase.
6. Use the existing backend parser when necessary.
7. Update the local notice with case details.
8. Calculate reminders from the next hearing date.

A failed or slow lookup must not lose the scanned notice.

## Backend
For this build use the previous Render backend:

`https://knc-backend.onrender.com`

Do not use Blitz for Notice Tracker until Blitz is confirmed working.

## Case data to retain
Where available:
- CNR
- case number
- case type
- case title
- court name
- judge
- petitioner/appellant/complainant names
- respondent/accused/defendant names
- petitioner-side advocate
- respondent-side advocate
- next hearing date
- stage/status
- filing/registration fields
- Acts/Sections
- hearing history and other structured fields if returned by the shared parser

Party addresses are not required.

## Notice data
Notice-specific data is stored separately from case data.

Each notice must retain:
- notice ID
- CNR/case link
- scanned date/time
- assigned process server
- service status
- next hearing date
- fetch state/error
- last update time

## Process server assignment
The user must be able to assign a process server by name to each notice.

Process-server assignment is independent of case fetching and must never be overwritten by a refresh.

## Service status
There are only two service results:
- **Served**
- **Not Served**

Do not ask for a non-service reason.

Default after scanning is **Not Served**.

Marking **Served** stops pending reminders for that notice. Marking **Not Served** restores/reschedules reminders based on the next hearing date.

## Dashboard
Show an actionable list ordered around the next hearing date.

At minimum show:
- total Not Served
- unassigned process-server count
- Not Served notices due within 7 days
- case number/CNR
- case title
- next hearing
- Served/Not Served
- assigned process server

Urgency:
- next date within 2 days: critical
- next date within 7 days: urgent
- otherwise normal
- served notices are visually completed

## Notice detail
Show:
- case title
- CNR
- case number
- court
- parties
- advocates
- next hearing
- process server
- Served / Not Served
- case fetch error/state if relevant

Actions:
- Assign Process Server
- Mark Served
- Mark Not Served
- Refresh Case Details

## Duplicate scan handling
If the CNR already exists, offer:
- Open existing notice
- Add another notice

The second option is necessary because the same case can produce multiple notices.

## Reminders
Schedule local reminders for Not Served notices at:
- 10 days before hearing
- 7 days before
- 3 days before
- 1 day before
- hearing morning

Reminder text should identify the case and state that the notice is not served. If no process server is assigned, say so.

## Refresh behavior
Refreshing case details may update:
- next hearing
- case stage/status
- court/judge
- parties/advocates
- other parser fields

Refreshing must never overwrite:
- process server
- Served / Not Served status
- scan timestamp

If the next hearing changes, reminder scheduling must be recalculated.

## Offline behavior
Already-saved notices must remain viewable and editable offline.

## Storage
Use a local Room database.

## Android architecture
- Kotlin
- existing KnowYourCase eCourts lookup/CAPTCHA approach
- Room
- WorkManager
- QR scanner
- Retrofit/OkHttp
- local notifications

## MVP acceptance criteria
A build is acceptable when:
1. A real eCourts QR can be scanned.
2. A notice entry is saved immediately.
3. The CNR is detected.
4. Case details can be fetched through the existing KnowYourCase flow.
5. Case number, title, parties, advocates, court and next hearing populate when available.
6. A process server can be assigned.
7. Service can be toggled only between Served and Not Served.
8. No non-service reason is requested.
9. Hearing reminders are scheduled for Not Served notices.
10. Marking Served cancels reminders.
11. Existing records work offline.
12. The build uses the old Render backend.

## Future improvements
After the MVP is stable:
- rapid batch scan mode
- process-server workload view
- calendar view
- changed-next-date history
- morning summary
- search and advanced filters
- notice timeline
- JSON/CSV backup/export
- optional app lock
