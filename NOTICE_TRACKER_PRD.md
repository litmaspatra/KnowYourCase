# Notice Tracker PRD

**Product:** Notice Tracker  
**Platform:** Native Android  
**Current product direction:** Process-server-first court notice allotment, receipt, reporting and case-detail tracking  
**Last updated:** 28 September 2026

---

## 1. Product summary

Notice Tracker is a native Android utility for a court process desk. It is built on the existing KnowYourCase eCourts lookup/parsing stack, but its primary workflow is notice allotment and receipt rather than general case lookup.

The app must let the user:

- create and manage a roster of process servers;
- open a process server and rapidly scan many notices being handed to that person;
- save every scan immediately, even if the network/backend is unavailable;
- queue case-detail lookups without blocking scanning;
- receive returned notices through a separate scanning screen;
- mark returned notices Served or Unserved;
- accept a returned notice even when it was never allotted through the app;
- see Allotted, Received and Pending counts for each process server;
- tap those counts to open detailed reports;
- retain essential completed-record information while minimizing local storage.

The app is a local-first operational register. Case fetching is supportive and must never block the core notice workflow.

---

## 2. Core user questions

The app should answer these quickly:

- Which notices were allotted to each process server?
- How many notices has each process server received?
- How many are still pending?
- Which notices were Served and which were Unserved?
- When was a notice allotted?
- When was it received back?
- What case does the notice belong to?
- Which notices are still waiting for case details to be fetched?

---

## 3. Primary navigation

The native Android app uses five bottom-navigation destinations:

1. **Desk**
2. **Allot**
3. **Receive**
4. **Notices**
5. **Settings**

**Receive must remain the third tab.**

### 3.1 Desk

The Desk is a quick operational overview. It should show relevant counts and shortcuts into Allot, Receive and Notices.

### 3.2 Allot

The Allot tab is process-server-first. The user must add process servers before normal allotment work.

The page shows the process-server roster. Tapping a process server opens that person's allotment screen.

### 3.3 Receive

The Receive tab is a separate workflow for notices physically returned by process servers. It must not reuse the allotment scanner state.

### 3.4 Notices

The Notices tab is the full register and supports Pending, Completed and All views.

### 3.5 Settings

Settings contains connection/backend controls, reminder schedule, visible fields, appearance, export and other desk preferences.

---

## 4. Process-server setup

The user must be able to:

- add a process server by name;
- prevent duplicate names in the active roster;
- remove a process server from the active roster;
- retain historical notice assignments even when a process server is removed from the active roster.

Removing a process server must never erase or rewrite old notice records.

---

## 5. Allotment workflow

### 5.1 Entering a process server

From **Allot**, the user taps a process server. The process-server screen must show:

- process-server name;
- Scan & allot action;
- Allotted count;
- Pending count;
- relevant current pending notices.

### 5.2 Batch scanning

The app must support rapid real-world batch work.

Expected flow:

1. Open a process server.
2. Tap **Scan & allot notice**.
3. Scan a valid eCourts QR/CNR.
4. Save the notice immediately.
5. Automatically assign it to the selected process server.
6. Store the **Allotted on** date/time.
7. Set service status to **Pending**.
8. Set case fetch state to **Queued** unless details are already available.
9. Return/reopen the scanner immediately for the next notice.
10. Continue scanning as many notices as needed.
11. User exits batch scanning manually when finished.

Case fetching must not delay or block subsequent QR scans.

### 5.3 Duplicate allotment handling

Before writing an allotment, the app must check whether the CNR already exists.

If the notice is already allotted and still Pending:

- do not reassign it;
- do not create another duplicate record;
- show **Notice already allotted**;
- show the process server to whom it is currently allotted;
- allow the user to open the existing record.

If the notice is already completed:

- show that it has already been received/completed;
- do not re-allot it silently.

Duplicate checks must happen before modifying process-server assignment.

---

## 6. Receive workflow

The Receive tab is used only for returned notices.

Expected flow:

1. Open **Receive**.
2. Tap **Scan received notice**.
3. Scan the returned QR/CNR.
4. Find the existing notice if present.
5. Ask whether service result is **Served** or **Unserved**.
6. Store the **Received on** date/time.
7. Mark the notice completed.
8. Cancel future pending reminders.
9. Update process-server reports.

### 6.1 Returned notice that was never allotted

If the scanned CNR does not already exist:

- still accept the notice;
- create a new record;
- leave process server unassigned unless the user later assigns one;
- record Received on date/time;
- record Served or Unserved status;
- queue case-detail fetching if needed;
- include it in **Received without prior allotment** reporting.

The Receive workflow must never reject a returned notice merely because it was not previously scanned in Allot.

### 6.2 Already received notice

If a completed notice is scanned again in Receive:

- show **Already received**;
- show its previous received date when available;
- do not create another record.

---

## 7. Service status model

There are exactly three workflow states:

- **Pending** — allotted/open work; no final service result yet.
- **Served** — completed successfully.
- **Unserved** — completed unsuccessfully / party not served.

Served and Unserved are both completed outcomes.

Do not ask for a non-service reason unless a future requirement explicitly adds one.

Only Pending notices receive reminders.

---

## 8. Process-server reports

The Receive tab must provide a process-server report.

For each process server show three clickable metrics:

- **Allotted**
- **Received**
- **Pending**

Tapping a metric opens only the matching notices for that process server.

Each report row should show at minimum:

- case title, or case number/CNR when title is unavailable;
- process-server name;
- Allotted on date/time;
- Received on date/time;
- current status: Pending / Served / Unserved.

The global Receive page should also show total Allotted, Received and Pending counts.

A separate **Received without prior allotment** section should appear when such records exist.

---

## 9. Case-detail fetching

Case-detail fetching is asynchronous and secondary to scanning.

### 9.1 Backend

Default backend:

`https://knc-backend.onrender.com`

The existing KnowYourCase eCourts/WebView/CAPTCHA flow remains the primary lookup mechanism, with the existing parser/backend used where required.

### 9.2 Fetch states

A notice may have these fetch states:

- **Queued**
- **Fetching**
- **Retry required**
- **Ready**
- an internal restart/priority state when required by implementation

### 9.3 Strict serialized queue

Case-detail fetching must be strictly FIFO/priority serialized:

- only **one notice may be Fetching at any time**;
- all additional scanned notices remain Queued;
- batch scanning must never launch parallel eCourts lookup activities;
- tab/screen switching must never start another lookup while one is active;
- multiple `pumpQueue`/resume/render events must not race into multiple fetch launches;
- there must be one effective queue-controller lock/active-lookup guard.

This is a stability requirement, not just a performance preference.

### 9.4 Interrupted fetch recovery

If the app is killed, restarted or a fetch is interrupted:

- stale Fetching/restarting records must recover safely into the queue;
- no notice may be permanently stuck as Fetching because the previous lookup activity disappeared.

### 9.5 Refresh behavior

Tapping **Refresh details** must actively restart case fetching.

Required behavior:

- clear the previous fetch error;
- put that notice at the front/high priority of the queue;
- if another notice is currently fetching, refresh waits for that single active fetch to finish;
- if the same notice is currently fetching, record a restart request and actually restart it after the active attempt closes;
- do not leave the notice indefinitely showing Queued after the user explicitly requested Refresh.

Refreshing case details may update case-derived fields, but must never overwrite notice workflow fields such as process server, allotment date, received date or service status.

---

## 10. Case data while active

While a notice is Pending or otherwise active, retain useful fetched case data where available:

- CNR;
- case number;
- case type;
- case title;
- court name;
- judge;
- petitioner/appellant/complainant;
- respondent/accused/defendant;
- advocates;
- next hearing date;
- case stage/status;
- other useful parser fields where already supported.

Party addresses are not required.

---

## 11. Completed-record compaction

Once a notice becomes Served or Unserved, the app should minimize storage usage.

### 11.1 Required retained structured fields

Completed notices must continue to retain enough structured data for reports and identification:

- notice ID;
- CNR;
- case number where available;
- case title;
- process-server name;
- Allotted on timestamp;
- Received on timestamp;
- Served/Unserved status;
- scan/update timestamps needed by the register.

### 11.2 Archived text snapshot

Before clearing bulky/secondary fetched fields, generate a compact plain-text/Markdown snapshot containing useful case details, for example:

```markdown
# Case summary
CNR: ...
Case: ...
Court: ...
Petitioner: ...
Respondent: ...
Next hearing: ...
Stage: ...
Process server: ...
Allotted: ...
Received: ...
Status: Served
```

After compaction, redundant detailed fields may be cleared if their useful information is preserved in the archive or essential structured columns.

The goal is to prevent completed records from accumulating unnecessary storage while keeping them readable and reportable.

---

## 12. Reminders

Reminders apply only to **Pending** notices.

The schedule is user-configurable from Settings. Supported choices currently include:

- 14 days before;
- 10 days before;
- 7 days before;
- 5 days before;
- 3 days before;
- 2 days before;
- 1 day before;
- hearing morning.

Default schedule:

- 10 days;
- 7 days;
- 3 days;
- 1 day;
- hearing morning.

When a notice becomes Served or Unserved, future reminders must be cancelled.

When a next hearing date changes after refresh, reminders must be recalculated.

Changing reminder settings must clear stale old reminder jobs before rescheduling.

---

## 13. Notice register

The Notices tab supports:

- Pending;
- Completed;
- All.

Completed means both Served and Unserved.

Each notice should expose:

- case title / case number / CNR;
- process server;
- next hearing when relevant;
- service status;
- fetch state;
- Refresh details;
- delete action;
- relevant completion/reopen actions.

Wrong entries must be deletable with confirmation.

---

## 14. Offline behavior

The app is local-first.

- QR scans and allotments must save immediately to Room.
- Existing notices and reports remain available offline.
- Lack of backend/network only affects case-detail fetching.
- Queued records remain queued until fetching can proceed.
- A failed lookup must never delete or roll back the notice itself.

---

## 15. Stability and concurrency requirements

The app must remain stable during real desk usage, including:

- rapidly scanning many notices in succession;
- assigning notices across multiple process servers;
- switching tabs repeatedly while fetches are queued/running;
- refreshing a notice while another notice is fetching;
- background/foreground transitions;
- scanner cancellation/back navigation;
- backend offline/online transitions.

### Mandatory rules

1. Scanner state and fetch state must be independent.
2. A QR scan must complete its local database write before the same QR can be accepted again.
3. Duplicate CNR handling must be deterministic.
4. Only one eCourts lookup activity can be active at a time.
5. Tab rendering must not launch duplicate fetch activities.
6. ActivityResult launchers must never be launched concurrently for the same lookup flow.
7. Crashes must not cause a scanned notice to disappear.

---

## 16. Storage and architecture

### Local storage

- Room database for notices and report data.
- SharedPreferences for lightweight app preferences such as process-server roster, appearance and reminder choices.
- Completed records compacted to essential structured fields plus text/Markdown archive.

### Android stack

- Kotlin;
- native Android UI / Material components;
- CameraX + ML Kit barcode scanning;
- existing KnowYourCase eCourts WebView/CAPTCHA lookup;
- Retrofit/OkHttp/backend parser;
- Room;
- WorkManager;
- local notifications.

The app must remain a true native Android APK. Apper is only a UI/navigation design reference and must not be a runtime dependency.

---

## 17. Export

Support local export of notice data as:

- CSV;
- JSON.

Exports should include report-relevant fields such as:

- CNR;
- case number;
- case title;
- process server;
- allotted date/time;
- received date/time;
- status;
- fetch state;
- other enabled visible fields where available.

---

## 18. Acceptance criteria

A build is acceptable when all of the following are true:

1. Process servers can be added before allotment.
2. A process server can be opened and notices scanned directly into that person's allotment.
3. Ten or more notices can be scanned rapidly without waiting for case fetching.
4. Every valid scan is saved immediately and queued.
5. A notice already allotted cannot be silently allotted again.
6. Duplicate allot scanning clearly reports the existing process server.
7. Receive is the third bottom-navigation tab.
8. Returned notices can be scanned and marked Served or Unserved.
9. A never-before-allotted returned notice can still be received and recorded.
10. Process-server reports show Allotted, Received and Pending counts.
11. Tapping a count opens the corresponding detailed notice list.
12. Report rows show case title/CNR, allot date, receive date and status.
13. Only one notice can be Fetching at a time.
14. Remaining scans stay Queued while one fetch is active.
15. Switching tabs while fetching does not crash the app or start another lookup.
16. Refresh actively restarts/prioritizes fetching instead of remaining stuck Queued.
17. Interrupted fetching recovers after app restart.
18. Completed notices cancel reminders.
19. Completed notices are compacted while preserving report-critical data and a readable archive.
20. Existing records remain viewable offline.
21. Wrong notice entries can be deleted with confirmation.
22. The default backend remains the existing Render backend.
23. UI audit, Android lint and debug compilation pass before APK delivery.

---

## 19. Future improvements

After the above workflow is stable:

- search within process-server reports;
- date-range report filters;
- printable process-server handing-over/receipt report;
- daily/weekly process-desk summary;
- optional automatic backup;
- calendar view;
- notice timeline/history;
- changed-hearing-date history;
- optional app lock;
- optional sync across devices.
