# Personal webpage presentation — 16 October 2026

- Lower times now appear lower in the chart, both in the initial SVG and JavaScript live repaint. Point labels and the best-time guide follow the same geometry. Empty pages retain a chart container so the chart can appear as results arrive, and a correction below two completed runs clears it.
- The current report logo is published as an optional embedded PNG and displayed above the webpage header. The image is scaled to at most 256 pixels, bounded, and read from the existing device-local report logo. Missing/unreadable images do not block results. Both foreground publishing and background retry include it. Adding/removing branding republishes the current session when publishing is enabled; it never enables publishing automatically. Previously published sessions retain their own snapshot.
- The logo field is optional and backward compatible with existing publishers. The personal view accepts only bounded base64 PNG data; arbitrary image URLs/HTML are not rendered. Live polling adds, changes or removes the image without reloading.
- Ongoing sessions show a highlighted green, softly blinking **Pågår** badge in the app dashboard/history and personal webpages. The label remains readable throughout the pulse. Closing switches the webpage badge to static **Avslutad** on its next poll. Browser reduced-motion preferences suppress the animation.

Validation includes publisher logo add/remove, image scaling/missing/corrupt-file behavior, wire compatibility, SVG point ordering, branding rendering and closed/open status. A separate localhost preview verified the initial graph/logo/badge, then a live fourth run, logo removal and closure without a page reload. The local tablet emulator was updated using its existing debug signing key, preserving application data.

The running publishing server must load the updated server build to serve these changes. The isolated validation server used port 8081; it did not replace or clear the existing port-8080 server's in-memory published sessions.

## Finish-line QR on the time card

When a server address is configured, the viewed skier's personal QR code appears directly beside the time/comparisons, including when publishing is off. In that case the card says **Publicering av**, and the enlarged view explains that new results are not being published. Tapping enlarges it and offers copy-link. It uses the same session/skier token as the pairing screen and follows the viewed skier independently of the next starter; older runs keep the same personal session link. The compact layout also retains the code alongside the skier identity. No runs or publishing settings are changed by opening it.

The local emulator's current `10.0.2.2` server address is emulator-only. A phone needs a reachable LAN/public address configured under publishing. Validation decoded the rendered compact/enlarged QR codes for Alma and Elias and compared them with their exact session/skier links, including older-run browsing. The app build and existing unit suite pass; the local tablet emulator is updated. A separate ignored build directory avoided concurrent Gradle output-file locks.

## Latest personal best

The latest completed-time card turns green and displays **★ Bästa åket hittills** when the skier's actual latest run equals their best completed time in the displayed session. Exact millisecond ties count; another skier's time never affects it. A subsequent slower run, DNF or incomplete run removes the celebration. The first completed run counts as the best so far. The highlight follows corrections and live polling without a page reload, with matching dark-theme colors. No cross-course or cross-session ranking is introduced.

Server tests cover the first run, improvement, exact tie, a one-millisecond slower run, DNF, incomplete results, skier isolation and initial HTML/API rendering. Browser validation checks both the green/star state and removal after a live slower result. Empty corrected result sets also clear stale latest/best times and comparison text.

## Follow the latest chart runs

Personal charts scroll to the newest end on page load and whenever a new result for that skier arrives. Ordinary five-second refreshes retain the manually chosen scroll position; results for another skier do not move it. A personal latest-recorded sequence includes DNF/incomplete arrivals even though those do not add a completed-time graph point. Corrections without a new result retain the browsing position.

Browser validation used 30 completed runs: initial scroll was at the right edge, manual scrolling remained at the left edge through another skier's result, and a new own run returned to the new right edge. Repeated the manual-scroll/new-result check with a DNF, which also returned to the latest end. The running local webserver was restarted with the updated build and existing published snapshots restored.

The diagram also marks every best-time point with a gold ring and a star beside its run number, including exact ties. A short legend and point title identify the meaning. Both server rendering and live repaint calculate it from the skier's completed times, with brighter gold in dark mode. Extra chart-edge padding keeps the last point's star visible. Server tests verify two tied best runs receive markers while a slower run does not.
