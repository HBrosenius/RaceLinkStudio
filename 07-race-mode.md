# Race mode

Choose **Passåtgärder → Starta tävling**, enter a name/date, choose the format, define classes (one per line), and assign every participating skier to a class. Starting a race saves the previous session in history. Training remains available through **Starta träningspass**.

## Formats and agreed rules

- **Två åk · totaltid:** lowest sum of two completed runs wins.
- **Differensslalom:** smallest absolute difference between two completed runs wins; either run may be faster.
- Rankings are separate for each class. Two completed runs are required for a placing. DNF or a missing run gives no final placing.
- Exact ties share a place, using competition ranking (1, 1, 3). There is no additional tie-breaker.
- Stored milliseconds determine scores and places; race times display three decimal places.

## Recording and correcting

Select **Registrera åk 1** or **Registrera åk 2** before recording incoming timing results. Results are assigned by skier identity, so the start order may differ between rounds. The simulator shows the run it will fill: it uses the selected round if that skier's slot is empty, then the other empty slot, without changing the global round selection. Two simulated results per skier therefore fill both race runs without a manual round switch. Simulation is disabled once both slots are filled, or while that skier has an unresolved extra result. Real timer integration remains a separate task.

A further result for an occupied skier/round slot never overwrites that slot. Under **Hantera registrerade resultat**, assign an extra result to an empty slot or explicitly exclude it. To replace a result, first remove the existing assignment, then assign the replacement and exclude the old extra. Original results remain stored. Unresolved extras block closing the race or starting another session.

Results assigned through timing review require an explicit race-round assignment. DNF can be applied to a recorded time and restored without losing that time. A DNF received without a finish time cannot be restored as completed. Skier corrections cannot create two results in the same skier/round slot.

Classes and participation can be edited while the race is open; skiers with results cannot be removed. The format locks after the first recorded result. Closing makes the race read-only; **Historik** retains its classes, rules, results and assignments.

## Reports and backup

**Exportera alla klasser** creates a frozen PDF or CSV result list, including participants with missing runs or DNF. Class filters affect browsing; this export includes every class. General reports also show race scores and retain original class places when selecting individual skiers. Race reports do not calculate training trends.

Backup version 7 includes race configuration, assignments and excluded extra results for current and archived sessions. Earlier backup versions remain readable. Training sessions retain their existing behavior.

## Validation

Debug build and 92 unit tests pass, covering both scoring rules, class isolation, ties, DNF restoration, replay/extra-result handling, conflicting corrections, reviewed-result assignment, exports, old backups and race backup/archive round trips. Emulator rehearsal covers class setup, both formats, reversed second-round order, DNF, extra-result exclusion, closing, archived reopening, PDF preview and CSV saving/read-back. The final APK retained the race after an update and restart. Test display settings were reset and the test emulator stopped; physical tablet update is pending reconnection.
