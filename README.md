# MovementID

An Android app for identifying watch movements from a photo and keeping a local collection of
what you've identified.

Point it at a movement, get a structured identification back, correct anything the AI got wrong,
and save it. Everything stays on the device.

---

## Flow

1. **Splash** — black screen, movement logo, app name.
2. **Home** — your saved collection, newest first, with search across caliber, brand, title and
   notes. This is the landing screen; identifying is an action you take from it.
3. **Identify** — camera, or pick existing photos from your gallery. Uses Android's Photo
   Picker, so no storage permission is needed and only the photos you choose are exposed.
   **Take as many shots as you like before identifying** — they're staged in a strip at the
   bottom and sent together.
4. **Review** — the answer appears *before* anything is saved, with a **Cancel** button while the
   scan runs. Every field is editable here. Save, retry, discard — or push back (below).
5. **Details** — the full record, all fields still editable, plus lookup buttons and provenance.

## Backup

Everything you identify — entries, notes, corrections and photos — is backed up to your own
cloud storage, so a broken, lost or replaced phone doesn't mean redoing the work.

**Automatic is the point.** Backups run after you save a movement (batched, five minutes later,
so a burst of edits makes one upload) and daily as a safety net, on Wi-Fi by default since
archives with photos run to tens of megabytes. It's scheduled through WorkManager, so it
survives the app being closed and retries when a connection fails.

**Koofr over WebDAV.** 10 GB free, EU-hosted, and it speaks plain WebDAV — a URL, a username and
an app password, with no OAuth, no browser round-trip and no refresh tokens to expire. Create an
app password at app.koofr.net under *Preferences → Password* (the account password is rejected)
and make the target folder before first use. Nextcloud, pCloud or any WebDAV server works
equally well, and Dropbox or Google Drive linked into Koofr appear as pass-through folders.

**The archive** is one self-contained ZIP: `manifest.json`, `movements.json`, and `photos/`.
The same file is what gets uploaded, what Export writes, and what Restore reads — so a backup
from either destination restores from the other. Photo paths are stored as bare names, since
absolute paths from the old phone mean nothing on the new one. The manifest carries a format
version, and a restore refuses an archive from a newer app rather than silently dropping fields.

**API keys are never included.** They live in the device keystore; a backup carrying one would
put a usable key in cloud storage. Re-entering it takes seconds.

**Restore replaces**, it doesn't merge — for a single-user collection there's no meaningful
conflict to resolve, and merging would duplicate everything on each restore. It always confirms
first.

**Export/Restore to a file** is also available, and is genuinely useful for moving to a new
phone or keeping a copy elsewhere. It is *not* a substitute for the automatic backup: a manual
export only helps if you made it before the phone broke, which is precisely when nobody thinks
to make one. The UI says so rather than presenting the two as equal choices.

Server-side retention keeps the newest few archives and deletes the rest.

## Share from Gallery or Google Photos

MovementID registers as an image share target, so **Share → MovementID** works from Gallery,
Google Photos, a browser, a messaging app — anywhere with a photo. The app opens straight on the
Identify screen with the image already staged, skipping Home and the picker.

Sharing several images at once works too; they're treated as multiple shots of the same
movement. A share replaces anything previously staged rather than adding to it, since a new
share is a new subject.

The camera stays off when you arrive with photos already in hand — you get the staged photos
with an Identify button, and the camera only starts if you press Camera. Holding the camera open
for a capture nobody asked for wastes battery and lights the privacy indicator for no reason.

## Multiple photos per movement

All staged photos go to the model in one request, and they're kept with the saved entry as a
thumbnail strip you can add to later.

This matters because one case-back photo often isn't enough. A caliber number readable on the
dial side settles what the back leaves ambiguous, and the same ebauche appears in different
watches with different branding stamped on it — so a second example can be the thing that
identifies the first. The prompt tells the model the images are all one movement and asks it to
say which image told it what.

## Reading the markings first

The caliber number stamped on a movement is the strongest evidence there is, and the commonest
failure was a model reasoning past it — reading a layout as "looks like a 7750" when "2824-2" was
engraved on the bridge. Two changes target that directly.

**Higher resolution.** Photos now go up at 2560px on the long edge (was 1536) and higher JPEG
quality. Engraving is often under a millimetre tall; at the old size it could blur into noise
before the model saw it, and JPEG artefacts land exactly on fine engraved lines. It costs a
larger upload and more image tokens per request, which is the right trade here.

**Transcribe before identifying.** The response schema now starts with `visibleMarkings`: every
legible caliber number, brand, jewel count and stamp, exactly as written. Models generate JSON
fields in order, so putting this first makes the model commit to what's written before it names
a caliber — the effect of a separate transcription step without spending a second request of
your quota. The prompt then tells it a legible caliber number outranks its visual impression.

The review sheet shows what was read. If nothing was legible it says so, in red, because an
identification from layout alone deserves more suspicion. And if it read a caliber number but
answered something else, that contradiction is flagged prominently.

The contradiction check is deliberately conservative. Jewel counts, "SWISS", adjustment marks,
serial numbers and partly-legible text are ignored, and "Cal. 2824" matches "ETA 2824-2". It was
tested against 14 cases before being ported, including the false-positive traps.

Markings are saved with each entry and editable, like everything else.

## Viewing photos full size

Tap any photo — on a saved movement or before identifying — to open it full screen. Pinch or
double-tap to zoom (double-tap zooms towards the point you tapped), drag to pan, swipe between
photos when not zoomed. Zooms to 8×, which is enough to read sub-millimetre engraving.

Two details that make the zoom actually useful rather than just magnified blur:

- **Photos are stored at full resolution.** Only the copy sent to the AI is downscaled; the
  saved original is untouched. The camera now captures in CameraX's maximum-quality mode at the
  sensor's highest resolution, rather than the default latency-optimised mode.
- **The viewer decodes at up to 4096px.** Coil normally decodes to the view's size, so zooming
  would just enlarge a screen-sized bitmap. Capped at 4096 rather than the full original, since a
  large sensor's image as a bitmap can exceed 100 MB and crash lower-memory phones.

Zooming *before* identifying is worth the habit: if you can't read the engraving, the model
can't either, and a retake costs less than a wasted request.

## Staying within the free quota

Gemini's free tier limits requests *and* tokens, per model, reset daily at midnight Pacific time
(09:00 in Central Europe). Photos are the expensive part — image tokens scale with resolution —
so the app manages both automatically.

**Resolution adapts.** Scans go at 2048px, which reads most engraving at roughly two-thirds the
token cost of full 2560px. When a scan reads *no* markings, the review sheet offers **Try again
sharper**, re-sending the same photos at 2560px. Extra tokens are spent only on the scans that
need them — deliberately a button rather than automatic, since silently escalating would double
the cost of exactly the scans that already failed, and a closer photo is often the better fix.
"Always use maximum resolution" in Settings overrides this, off by default.

**Quota runs out → another model answers.** Quotas are per model, so one running dry says
nothing about the rest. When a model reports quota exhausted, the scan moves to the next model
with room — your selected models first, then the others — and the review sheet notes which
model stepped in. Fallbacks run at standard resolution so they don't drain the next model as
fast. Exhausted models are remembered for a few hours, so later scans skip them rather than
rediscovering it. Only when every model is dry does the scan fail, and it says when limits reset.

## Pushing back when it's wrong

**Not right? Tell it why** on the review sheet re-runs identification with your correction as a
constraint: *"It's an Omega, the case back is marked 1012."*

This is the highest-value input available to the model, because you can see what the photo
can't — the case, the dial side, the watch's provenance. The prompt states your correction
outranks what the model thinks it sees, tells it not to repeat the rejected answer, and tells it
to lower confidence rather than invent a replacement if your correction leaves it unsure.

Note this doesn't change the model itself, for you or anyone else — it's better context in the
prompt, which is the right fix here anyway, since these are knowledge gaps rather than reasoning
failures.

## What it records

Brand, movement family, caliber, type (automatic / manual / quartz), size, beat rate, power
reserve, jewel count, **lift angle**, **years produced**, the model's reasoning, and your own
notes. Plus which model answered, how long it took, and where the lift angle came from.

Every one of these is editable. The AI is wrong often enough that your correction needs to be
able to become the record, not sit in a comment beside a wrong value.

---

## AI providers

**Google Gemini** is the default and the one that works. Free key, no card, from
[aistudio.google.com/apikey](https://aistudio.google.com/apikey).

Models selectable in Settings, newest first:

- `gemini-3-flash-preview` *(default — best results in practice)*
- `gemini-3.8-flash`
- `gemini-3.7-flash`
- `gemini-3.6-flash`
- `gemini-3.5-flash`

Select **more than one** and each is asked independently, with answers shown as tabs. They're
deliberately not merged into one averaged result: where models agree is a better signal than any
single model's confidence score, and averaging would hide exactly the disagreement worth seeing.

There's also a free-text **Other model ids** field, so a newly released model works the day it
ships without an app update.

**OpenAI** is available as a second provider (key from
[platform.openai.com/api-keys](https://platform.openai.com/api-keys)) for comparison. It's paid,
with billing separate from any ChatGPT subscription — new accounts have no credits until you
prepay, so it fails at the billing check until you do.

### Quota matters more than you'd think

Each selected model costs one request per scan. Four models with search verification on was
eight requests for a single photo, which exhausts Gemini's free daily quota quickly. If you see
quota errors, reduce the number of selected models first.

---

## Checking the answer

Three independent routes, in order of how much you should trust them.

### 1. Lift angle — WatchGuy's list *(automatic)*

[watchguy.co.uk/cgi-bin/lift_angles](https://watchguy.co.uk/cgi-bin/lift_angles) is a maintained
table of per-caliber lift angles. The app fetches it once, caches it on disk, and refreshes
weekly. No key, no quota.

Lift angle is exactly what a language model guesses badly — a per-caliber constant with no visual
cue, where 52° and 53° are tempting defaults that are wrong often enough to skew every amplitude
reading on a timegrapher. So the table wins where they disagree, and the model's figure is shown
alongside rather than silently replaced.

Matching **requires the brand to agree**. Caliber numbers collide constantly between makers —
Omega, A. Schild and others all have a "1012" — so a caliber-only match isn't evidence. Outcomes:

| Situation | What you see |
|---|---|
| Model gave nothing, table has it | *From WatchGuy's list (ETA 2824-2)* |
| Both agree | *Confirmed by WatchGuy's list* |
| They disagree | *WatchGuy lists 50° … the model said 53°. Using the list.* |
| Brand known, not in table | *Not in WatchGuy's list for this brand* — model's figure kept |
| Brand unknown, several makers list that caliber | *Ambiguous* — names them, model's figure kept |

### 2. Google and Ranfft lookups *(manual, free)*

Every result has **Search Google**, **Images** and **Ranfft** buttons that open in your browser.
No API, no key, no quota, nothing to break.

[Ranfft DB](https://ranfft.org) is the community successor to Roland Ranfft's archive (~17,700
calibers), the best free source for per-caliber specs and reference photos. The button runs a
Google `site:ranfft.org` search rather than their own search box — their caliber URLs are slugged
with an internal id (`/caliber/49-AHO-126`) that can't be derived from a caliber name, and their
search is a JS form with a CSRF token, so a constructed query URL would be guesswork that breaks
silently.

Google Images is often the fastest check of all: comparing your photo against known images of a
caliber plays to what you can judge better than the model can.

### 3. Ask the model to search *(optional, costs quota)*

Off by default. When on, the model searches the web after identifying and corrects its own
figures. Gemini uses its built-in search grounding; OpenAI uses the `web_search` tool on the
Responses API, which returns proper source citations.

To protect quota this runs on **one result per scan**, not all of them. Any other answer can be
checked on demand with the **Ask Gemini** / **Ask OpenAI** button on that result.

The status line under each result always says what actually happened — *Corrected against 4
sources*, *Confirmed against 4 sources*, *No web results found*, *Web check unavailable* (with
the reason), or *Web check off*. A check that quietly does nothing is worse than one that says
why it couldn't.

> **Note on Google Custom Search:** an earlier version used the Custom Search JSON API with a
> Programmable Search Engine. That's been removed — the API is closed to new customers, and new
> projects get `PERMISSION_DENIED: This project does not have the access to Custom Search JSON
> API`. If you set up a `cx` for an older build, it's no longer used.

---

## Expectations

Identifying a caliber from a photo is hard even for an experienced watchmaker. Many movements are
visually near-identical, unbranded clones are everywhere, and a model will produce a confident,
plausible, wrong answer without hesitation.

Treat every result as a starting point for your own research. That's why the confidence value is
shown, why every field is editable, why lift angle is cross-checked against a real table, and why
the lookup buttons are one tap away.

---

## Build

1. Open this folder in **Android Studio**.
2. Let Gradle sync — Kotlin 2.0.21, AGP 8.5.2, KSP for Room.
3. Run on a device with a camera, Android 8.0 (API 26) or newer.
4. Open **Settings** (gear icon on Home), paste your Gemini key, pick models, **Save**.

**Check the build actually reached the phone.** The bottom of Settings shows the version, e.g.
`MovementID 2.0 (build 11)`. If a build fails, Android Studio will happily run the previously
installed APK and none of your changes will appear.

If the Kotlin daemon fails to start (`terminated unexpectedly … error code: 0`), that's memory:
`gradle.properties` sets both `org.gradle.jvmargs` and `kotlin.daemon.jvmargs` (a separate JVM
with its own heap). Run `gradlew --stop` first so a stale daemon isn't reused. There's also a
commented-out `kotlin.compiler.execution.strategy=in-process` line as a last resort if antivirus
or a locked-down Windows image blocks the daemon's local socket.

The database uses destructive migration, so entries saved by an older build are cleared whenever
the schema changes.

---

## Artwork

The splash logo and launcher icon are original vector line-art of a movement seen from the back,
following the anatomy of a classic Swiss layout: barrel bridge at upper left carrying the ratchet
wheel, crown wheel above it, train bridge on the right with the escape wheel visible at its edge,
and the balance under its cock with regulator and timing screws. Bridges are bevelled and
Geneva-striped; the exposed mainplate between them carries perlage; jewels are set into the
bridges and the screws are slotted.

Drawn this way deliberately. A field of big exposed gears is a clockwork cliché — on a real
wristwatch the going train is hidden beneath the bridges, and what you actually see is striped,
bevelled plates, circular graining, jewels, screws and the balance.

Nothing is hand-drawn: tooth profiles, the hairspring spiral, the Geneva striping (clipped to
each bridge outline), the perlage (laid out across the plate and masked where bridges cover it),
and the bridge bevels are all computed geometry, generated by script and rendered for review.

The launcher icon is the same movement reduced to what survives at 48dp — perlage, one bridge,
the ratchet wheel, the balance and its cock. Jewels, screws and the second bridge are dropped
because they turn to noise at that size. It sits inside the adaptive-icon safe zone, so no
launcher mask can clip it.

---

## Project layout

```
app/src/main/java/com/movementid/app/
  data/            Room entity, DAO, database
  network/
    AiProvider.kt            Provider registry (Gemini, OpenAI)
    GeminiClient.kt          Identification + Google Search grounding
    OpenAiClient.kt          Identification (Chat Completions) + web_search (Responses API)
    LiftAngleTable.kt        WatchGuy table: fetch, cache, brand-safe matching
    CaliberSearch.kt         Google / Images / Ranfft lookup URLs
    ImageEncoder.kt          Downscales photos before upload
    MovementIdentification.kt Shared prompt, result model, SearchStatus
  repository/      Parallel per-model runs, verification, lift-angle check, persistence
  settings/        Encrypted keys, model selection, toggles
  ui/screens/      Splash, Home, Scan, Detail, Settings
  MainActivity.kt  Navigation (Splash → Home)
  MainViewModel.kt Search, cancellable scan state, gallery import
app/src/main/res/drawable/
  ic_movement_logo.xml       Splash logo
  ic_launcher_foreground.xml Launcher icon foreground
```

## Privacy

Your API keys, photos and saved movements never leave the device, except for the direct HTTPS
call to whichever AI provider you select, and the weekly fetch of the public WatchGuy lift angle
page. No analytics, no backend, no account.
