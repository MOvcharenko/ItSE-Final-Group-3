# How to run the app

Commands for running and testing on your own machine. Run everything from `Project/scheduling`.


You need JDK 21 or newer (`java -version`). 

## Start the app

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

It's ready when the log says `Started SchedulingApplication`. To stop it, press `Ctrl+C` in that terminal.

Check that it's running, from a second terminal:

```powershell
curl.exe http://localhost:8080/actuator/health
```

You should see `"status":"UP"`.

- **To use the web UI,** open `http://localhost:8080/` in a browser and sign in as Maya Brooks, the Scheduler. The app starts empty: add subjects and rooms under **Catalog**, then a term and its sections under **Timetable**, and publish the term so that the other accounts can see it.
- **The database is in memory.** Restarting the app deletes all data, and old tokens stop working, so get a new one.

## Sign in

These test accounts only exist in the `dev` and `test` profiles:

| `userId` | Account | Role |
|---|---|---|
| `U01` | Maya Brooks | Scheduler (CS, MATH) |
| `U02` | L. Bianchi | Instructor |
| `U03` | Jordan Ellis | Student |

Get a token. Change `U01` to sign in as someone else:

```powershell
$base = "http://localhost:8080"
$token = (Invoke-RestMethod -Method Post -Uri "$base/dev/token" -ContentType "application/json" -Body '{"userId":"U01"}').accessToken
$auth = @{ Authorization = "Bearer $token" }
```

```sh
BASE=http://localhost:8080
TOKEN=$(curl -s -X POST $BASE/dev/token -H 'Content-Type: application/json' -d '{"userId":"U01"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
```

## Try the API

These use `$base`, `$auth` and `$token` from above, so run them in the same terminal.

Add a room, then list the rooms:

```powershell
Invoke-RestMethod -Method Post -Uri "$base/api/rooms" -Headers $auth -ContentType "application/json" -Body '{"name":"Founders 110","building":"Founders","capacity":60,"roomType":"LECTURE_HALL"}'
Invoke-RestMethod -Uri "$base/api/rooms" -Headers $auth
```

```sh
curl -X POST $BASE/api/rooms -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Founders 110","building":"Founders","capacity":60,"roomType":"LECTURE_HALL"}'
curl $BASE/api/rooms -H "Authorization: Bearer $TOKEN"
```

Download a CSV template, then upload your filled-in file:

```powershell
curl.exe "$base/api/import/templates/rooms" -H "Authorization: Bearer $token" -o rooms.csv
curl.exe -X POST "$base/api/import/rooms" -H "Authorization: Bearer $token" -F "file=@rooms.csv"
```

The import types are `rooms`, `equipment`, `subject-list` and `ready-made-schedule`. A ready-made schedule needs its rooms, subjects and term to exist first, so import or create those before it.

The full list of endpoints is in [README.md](README.md).

## Look at the database

While the app is running, open `http://localhost:8080/h2-console` and log in with:

| Field | Value |
|---|---|
| JDBC URL | `jdbc:h2:mem:scheduling;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` |
| User name | `sa` |
| Password | leave empty |

## Run the tests

| What | PowerShell |
|---|---|
| Everything (run this before you push) | `.\mvnw.cmd verify` |
| Unit tests only | `.\mvnw.cmd test` |
| One unit test class | `.\mvnw.cmd test '-Dtest=CatalogServiceImplTest'` |
| One integration test class | `.\mvnw.cmd verify '-Dit.test=NoticeApiIT' '-Dtest=NoSuchTest' '-Dsurefire.failIfNoSpecifiedTests=false'` |
| One integration test method | Same as above, with `'-Dit.test=NoticeApiIT#slowSis'` |

On Git Bash, macOS and Linux, use `./mvnw` and leave out the single quotes.

Test reports are in `target/surefire-reports` (unit) and `target/failsafe-reports` (integration).

## If something goes wrong

**`Port 8080 was already in use`:** another program has the port. See which one before you stop anything, because it may be something you need:

```powershell
Get-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess
```

Or leave it running and start the app on another port, then use that port in `$base`:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev' '-Dspring-boot.run.arguments=--server.port=8081'
```

**Every URL gives 404, even `/actuator/health`:** that's probably a different program on that port. Our app answers 401 for anything without a token, except the web UI's files and the health check.

**`http://localhost:8080/` gives a 401:** the app was started before the web UI was added. Restart it.

**`Set spring.security.oauth2.resourceserver.jwt.issuer-uri`:** you started the app without the `dev` profile. Add it as shown in [Start the app](#start-the-app).

**401 on an API call:** the token is missing, or the app has restarted since you got it, or it's over 8 hours old. Get a new one.

**403 on an API call:** this account's role isn't allowed to do that. Try another account from the table.

**A `-D` option fails with an "unknown lifecycle phase" error in PowerShell:** PowerShell split it in two. Put single quotes around it, as in the commands above.


##Start Plaything

1. Start the app
Open a terminal in Project/scheduling, run this and leave it open:


.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev' '-Dspring-boot.run.arguments=--server.port=8081'
Wait for Started SchedulingApplication.

2. Add a helper
Paste this into a second terminal. It lets you send commands as any user: U01 is the scheduler, U02 the instructor and U03 the student.


$base = "http://localhost:8081"
function api($method, $path, $user, $body) {
  $token = (Invoke-RestMethod -Method Post -Uri "$base/dev/token" -ContentType "application/json" -Body "{`"userId`":`"$user`"}").accessToken
  $req = @{ Method = $method; Uri = "$base$path"; Headers = @{ Authorization = "Bearer $token" } }
  if ($body) { $req.ContentType = "application/json"; $req.Body = $body }
  try { Invoke-RestMethod @req } catch {
    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
    "REFUSED ($([int]$_.Exception.Response.StatusCode)): " + $reader.ReadToEnd()
  }
}
3. Build a timetable as the scheduler
This creates a room, a course and a term, then places a Monday 09:00 class:


$room = api POST /api/rooms U01 '{"name":"Founders 110","building":"Founders","capacity":60,"roomType":"LECTURE_HALL"}'
$subject = api POST /api/subjects U01 '{"courseCode":"MATH 210","title":"Calculus","creditHours":3,"defaultRoomType":"LECTURE_HALL","department":"MATH"}'
$term = api POST /api/terms U01 '{"name":"Fall 2030","startDate":"2030-09-02","endDate":"2030-12-20"}'
api POST "/api/terms/$($term.id)/sections" U01 "{`"subjectId`":`"$($subject.id)`",`"roomId`":`"$($room.id)`",`"instructorIds`":[`"U02`"],`"meetings`":[{`"pattern`":`"WEEKLY`",`"day`":`"MONDAY`",`"start`":`"09:00`",`"end`":`"09:50`"}]}"
4. Publish it and see the student's view
The first calendar command shows nothing, because students can't see Drafts. After publishing, the class appears:


(api GET "/api/calendar?scope=WEEK&date=2030-09-02" U03).events
api POST "/api/terms/$($term.id)/publish" U01
(api GET "/api/calendar?scope=WEEK&date=2030-09-02" U03).events | Select-Object courseCode, date, start, end, roomName | Format-Table
5. Try to book the room during the class
The approval is refused with a 409: "Founders 110 has a class scheduled…"


$clash = api POST /api/bookings U02 "{`"resourceType`":`"ROOM`",`"resourceId`":`"$($room.id)`",`"start`":`"2030-09-02T09:00:00`",`"end`":`"2030-09-02T09:30:00`",`"purpose`":`"Review session`"}"
api POST "/api/bookings/$($clash.id)/approve" U01
6. Book a free time and check the notice
This one shows APPROVED, and the instructor gets a notice:


$free = api POST /api/bookings U02 "{`"resourceType`":`"ROOM`",`"resourceId`":`"$($room.id)`",`"start`":`"2030-09-02T14:00:00`",`"end`":`"2030-09-02T15:00:00`",`"purpose`":`"Review session`"}"
(api POST "/api/bookings/$($free.id)/approve" U01).status
(api GET /api/notices U02) | Select-Object event, message | Format-List
7. Try a few more things

# A student isn't allowed to add a room: REFUSED (403)
api POST /api/rooms U03 '{"name":"Nope","building":"X","capacity":1,"roomType":"LAB"}'

# Room search: nothing during the class, and Founders 110 on Tuesday
(api GET "/api/search/rooms?start=2030-09-02T09:00:00&end=2030-09-02T09:30:00" U02).name
(api GET "/api/search/rooms?start=2030-09-03T09:00:00&end=2030-09-03T09:30:00" U02).name

# The instructor's calendar as .ics text
api GET "/api/calendar.ics?date=2030-09-02" U02
Good to know
Starting over: press Ctrl+C in the first terminal and start the app again, which wipes all data. Repeating step 3 without restarting gets refused as duplicates, and you'd also need to redo step 2 if you opened a new terminal.
Strange characters: the notice shows something like 14:00a??15:00 instead of a dash. That looks like Windows PowerShell misreading the character rather than an app bug, since curl showed the dash correctly earlier.
Ideas to try: book outside the term's dates, or use your own room names and times. You can also change U02 to U03 in a booking, or put the end time before the start time, to see how the app refuses it.
Conflicts: two classes in the same room at the same time are both saved, because a Draft may hold conflicts, but they are flagged, and publishing then asks for confirmation first. There is no Conflicts screen in the browser yet, so list them with `(api GET "/api/terms/$($term.id)/conflicts" U01) | Select-Object type, severity, resolutionState, explanation | Format-List`. Bookings are the exception for now: approving or cancelling one doesn't update the list until a section changes or you re-scan with `api POST "/api/terms/$($term.id)/rescan" U01`.

Check it's running: open http://localhost:8081/actuator/health in your browser. It should show "status":"UP".

See it in the browser: open http://localhost:8081/ and pick an account. Everything you built above appears there, and the Scheduler can also add to it. For this data, choose Fall 2030 if it isn't already selected. The database viewer is at http://localhost:8081/h2-console.