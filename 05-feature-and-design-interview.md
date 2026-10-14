# Feature and design interview

Status: feature and design interview complete, 14 October 2026. Confirmed answers are incorporated into the planning baseline. A first simulator demo now implements a subset; see IMPLEMENTATION.md for current scope.

## People and purpose

- One coach at the finish operates the Android tablet. The skier may also look at it.
- The primary audience includes young children.
- Focus on the same skier's repeated runs: trends, improvements, and clearly identified fastest and slowest runs.
- Comparing skiers by absolute time is not the primary goal.
- Comparing improvement between skiers across sessions is a possible later feature requiring further definition and consideration of course/condition changes; it is not an MVP commitment.

## Confirmed screen behavior

- Latest finish, athlete name and personal comparison deserve prominent space.
- Primary comparison: difference from that skier's previous run.
- While reviewing a selected child, stay on that child when another skier finishes. Show a prominent new-finish notification without interrupting the review.
- Use neutral wording and colors for slower runs, such as “0.42 s longer.” Clearly label fastest and slowest runs.
- Visual direction: modern coaching dashboard with clean panels, results and comparisons.
- Run history: a large trend chart with a compact results table.
- Coaching context: quick tags such as technique focus, mistake or changed conditions, plus an optional free-text note.
- Appearance: both light and dark themes with a quick switch.
- Multiple skiers can be on course simultaneously. Split timing is not used and is outside the initial UI scope.
- Children enter their bib number at the start. The roster maps that number to a name for personal feedback. Automatic association requires verified hardware bib data; uncertain or missing identity still needs review.
- When gates or training exercises change, keep comparing runs within the session and mark the change on the chart. These comparisons describe observed times, not proof of improvement under equivalent conditions.
- Save a reusable team roster with regular bib numbers; select today's participants from that roster.
- Correct a wrong bib association by tapping the run and reassigning it to another child, retaining correction history.
- Initial live viewing is on the tablet only. Other-coach and family phone viewers are deferred.
- Show today's runs and allow earlier sessions to be opened separately; no cross-session overview required initially.
- Incomplete attempts remain visible but are excluded from time comparisons. Do not invent a completed duration for an incomplete attempt.
- New-finish notifications include athlete name, time and a waiting-results count, with an optional short sound. They do not replace the selected child's view.
- Chart presentation is switchable on the fly while reviewing results: actual run times or improvement relative to the first completed run. Working interpretation: a control in the current results view, not a global settings-only choice; it does not alter stored timing evidence or the primary previous-run comparison.
- Blue accent color for controls, selections and chart lines; faster/slower feedback remains neutral.
- Initial interface language: Swedish.
- The coach's tablet will be beside the timer at the finish. Direct BLE is the initial topology to validate; the optional Pi/distant-backhaul topology is deferred.
- Target screen size is approximately 10 inches; exact Android tablet model/version remains undecided.
- Opening an active session shows the latest finisher's personal dashboard. Subsequent finishes do not interrupt an existing review. The no-finishes-yet state remains to be designed.
- After an incomplete attempt, compare the next completed run with the most recent completed run and clearly identify that reference run.
- Initial optional coaching tags: “Teknikfokus”, “Bra känsla”, “Misstag”, “Ändrade förhållanden”, “Sen”. The coach can add and edit tags. Tags remain distinct from attempt status.
- Initial exports include a shareable individual-child summary, a whole-session report and CSV export. Exporting does not imply automatic sending.
- Individual summaries support image and PDF export.
- The whole-session report has a section for each child with their chart, runs and personal changes, without rankings.
- Renaming a coaching tag updates its displayed wording on past and future runs. Use stable tag identities so renaming does not break run associations. Previously exported files are not automatically rewritten.

- Shared summaries exclude coaching tags and notes by default; the coach can choose to include them during export.
- Initial chart mode is actual run times, clearly labeled so lower means faster. The in-view toggle remains available.
- Support a team name and optional logo in the app and exported reports.

## Interview outcome

The feature and visual direction is settled for planning. Exact tablet model/version and hardware capabilities remain technical validation items rather than unanswered visual preferences.

## Details to resolve in later rounds

- First-run and no-results empty states, tied fastest/slowest labels, and notification navigation are design details to demonstrate in the next prototype.
- Exact RL mode and verification of hardware bib delivery; missing/incorrect bib handling.
- Exact tablet model/version, outdoor readability and backup destination.
- Cross-session improvement comparisons are deferred; earlier sessions open separately.
