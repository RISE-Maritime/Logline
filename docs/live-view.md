# The Live view

The instrument page: the chart, the layers it can draw, and what to do about a sea with no
network.

- [The chart, and its layers](#the-chart-and-its-layers)
- [Offline maps](#offline-maps)

---

The **Live** tab shows what is actually going on the bus, as opposed to how fast it is going: the last
GNSS fix on an OpenStreetMap background with the recent track drawn over it, and a sparkline per
subject for the last couple of minutes.

Under the position sits a one-line summary — fix kind, satellites, cellular SINR, charge — so the
question "is this run healthy" does not cost a scroll into three groups. The chips below it filter the
plots to one group when tapped. And a **Basic / All** control decides how much is plotted: Basic is
the eight subjects an operator watches (the fix, speed, course, true heading, horizontal accuracy, fix
quality, air pressure, charge) and All is every published series, with the number being held back
stated beside the control. Nothing is switched off by choosing Basic — every subject still publishes
and is still recorded.

It shows **only what was published** — it deliberately does not read the sensors itself. A second
location stream would double GNSS power draw and could disagree with the bus, which is the one thing
this view exists to rule out. With publishing stopped it shows whatever the last run left behind and
says so.

Some details that are load-bearing rather than cosmetic:

- **Vector subjects are plotted as magnitude.** Three overlaid axes are unreadable at card size, and
  magnitude is what answers "is this sensor sane". The full vector still goes on the wire untouched.
- **An absent bearing reads as "no bearing", not north.** The wire carries `0.0` when the fix has no
  bearing, which on a stationary phone is almost every fix; the view keeps the raw value so the map
  never draws a heading arrow that was never measured.
- **Sparklines are stride-sampled, not averaged**, when a window is wider than the canvas. Averaging
  smooths away exactly the spikes that make a sensor look wrong.
- **The map needs tiles.** osmdroid caches them under `filesDir`, so an area you have already looked at
  keeps rendering with no network; somewhere new with no signal renders the track on a blank grid.

The window is bounded in *samples*, not seconds (8192 per subject, ~2.7 minutes at 50 Hz), because
`Maximum` rate is a legal setting and a seconds-based window would not be bounded at 442 Hz.

Nothing about this touches the publish path beyond one lock and two array writes: the collectors append
to a ring, and the screen pulls a snapshot at 5 Hz. Measured with the view open, the IMU subjects still
publish at 55.3 Hz and 0.29% of frames were janky.

## The chart, and its layers

The live view opens on a 400dp chart with an **Expand** control that gives it the screen — reading a
chart and reading numbers are different jobs and neither wants half a display. The layer button offers:

| Layer | Source | Notes |
| --- | --- | --- |
| **Map** | OpenStreetMap standard | The default. |
| **Satellite** | Esri World Imagery | Global, no key. osmdroid's own `USGS_SAT` is the United States only and draws nothing over Sweden. |
| **Sea marks** | OpenSeaMap | An *overlay*, not a base layer — buoys, lights and seamarks drawn over whichever of the above is showing. |

**Attribution is drawn, and until now it was not.** `CopyrightOverlay` has to be added explicitly;
osmdroid does not draw the notice on its own, and this map never added one. It reads the current
source's notice, so it follows the layer — `© OpenStreetMap contributors` or `Esri, Maxar, Earthstar
Geographics`. Both licences require it.

> Esri's World Imagery is used without a key, as most open-source apps do, with the attribution their
> terms ask for. Esri's terms nominally expect an ArcGIS account for use in an application, so treat
> this as a pragmatic default rather than a settled licence — it is one constant in `TrackMap` to
> change if RISE would rather point at Lantmäteriet or its own imagery.

Note the Esri URL is `/tile/{z}/{y}/{x}` — **row before column**, unlike the `{z}/{x}/{y}` that
`XYTileSource` builds — which is why it is a custom source. Swap them and every tile still loads, from
the wrong place.

## Offline maps

The live view's map draws from OpenStreetMap over the network, which at sea is a blank grid. **Settings
→ Offline map** imports a tile archive — `.mbtiles`, `.gemf`, `.zip` or `.sqlite` — and the map draws
from it wherever it covers, with online tiles filling in the rest. **Offline tiles only** turns the
network off for the map entirely: out of coverage the downloader otherwise queues every tile the
archive does not cover and waits for each to time out.

**The app cannot fetch an area for you, and that is not an omission.** OpenStreetMap's tile usage
policy forbids bulk downloading, and osmdroid enforces it in code: `TileSourceFactory.MAPNIK` carries
`FLAG_NO_BULK`, so every `CacheManager` constructor throws `TileSourcePolicyException` for OSM tiles.
Prepare an archive ashore instead — MOBAC, QGIS or `tilemaker` all produce one — from a source that
permits it or from your own tile server.

An archive is also the better artefact than a warmed cache. osmdroid's tile cache is an LRU it trims at
600 MB, so tiles browsed into it can evaporate; an archive is a file and stays until it is removed. It
lives in app-private storage, is excluded from Android's backups like the recordings are, and competes
with them for the same volume — a 400 MB archive is 400 MB fewer of recording, which the main screen's
capacity line will show.

---

[← Back to the README](../README.md)
