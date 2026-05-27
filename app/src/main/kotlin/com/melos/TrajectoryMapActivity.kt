package com.melos

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.melos.trajectory.GeoUtils
import com.melos.trajectory.RealTrackLoader
import com.melos.trajectory.TrackProfile
import com.melos.trajectory.TrajectoryGenerator
import com.melos.trajectory.TrajectoryPoint
import org.json.JSONArray

class TrajectoryMapActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trajectory)

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // Load real track or fall back to mathematical model
        val realTrackData = RealTrackLoader.load()
        val generator: TrajectoryGenerator
        val trackName: String

        if (realTrackData != null) {
            val track = realTrackData.trackProfile
            trackName = track.name
            generator = TrajectoryGenerator(
                trackProfile = track,
                meanSpeedMps = 2.5,
                speedVariation = 0.10,
                wanderMeters = 2.0,
                realSpeedAltitudeProfile = realTrackData.speedAltitudeProfile,
            )
        } else {
            trackName = "Mathematical 400m (no real data)"
            generator = TrajectoryGenerator(
                trackProfile = buildMathTrack(),
                meanSpeedMps = 2.5,
                speedVariation = 0.15,
                wanderMeters = 2.0,
            )
        }

        // Simulate 3 laps
        val points = simulateTrajectory(generator, 3)

        // Show track info
        val info = findViewById<TextView>(R.id.tvInfo)
        info.text = "$trackName · ${points.size} pts · ${"%.1f".format(points.last().elapsedDistanceMeters)}m"

        // Build JSON for the map (convert WGS-84 → GCJ-02 for Gaode tiles)
        val ptsJson = JSONArray().apply {
            for (p in points) {
                val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(p.position.lat, p.position.lng)
                put(JSONArray().apply {
                    put(gcjLat)
                    put(gcjLng)
                    put("%.1f".format(p.speedMps))
                    put("%.1f".format(p.accuracyMeters))
                })
            }
        }

        webView.loadDataWithBaseURL("https://unpkg.com", buildHtml(ptsJson.toString()), "text/html", "UTF-8", null)
    }

    private fun simulateTrajectory(generator: TrajectoryGenerator, laps: Int): List<TrajectoryPoint> {
        val perimeter = realTrackData?.trackProfile?.perimeterMeters ?: 400.0
        val totalTime = (perimeter * laps) / 2.5  // ~2.5 m/s average
        val points = mutableListOf<TrajectoryPoint>()
        var t = 0.0
        while (t < totalTime) {
            points.add(generator.nextPoint(t))
            t += 1.0  // 1s intervals
        }
        return points
    }

    private fun buildHtml(pointsJson: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html,body,#map{height:100%;margin:0;padding:0;}
  .label-s { background:#4CAF50; color:#fff; border-radius:50%; width:22px; height:22px; line-height:22px; text-align:center; font-size:11px; font-weight:bold; }
  .label-e { background:#F44336; color:#fff; border-radius:50%; width:22px; height:22px; line-height:22px; text-align:center; font-size:11px; font-weight:bold; }
</style>
</head>
<body>
<div id="map"></div>
<script>
var pts = $pointsJson;
if(!pts.length) { document.body.innerHTML='<h3 style="text-align:center;padding:40px">没有轨迹数据</h3>'; }
else {
  var center = [pts[0][0], pts[0][1]];
  var map = L.map('map').setView(center, 17);
  L.tileLayer('https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {maxZoom:18}).addTo(map);

  var latlngs = pts.map(function(p){ return [p[0], p[1]]; });

  // Colored by lap: split every ~400 points (roughly 1 lap at 1pt/s, ~400s/lap)
  var lapLen = 400;
  var colors = ['#1976D2','#E91E63','#4CAF50','#FF9800','#9C27B0'];
  for(var lap=0; lap*lapLen < latlngs.length; lap++) {
    var start = lap * lapLen;
    var end = Math.min((lap+1)*lapLen + 1, latlngs.length);
    if(start >= latlngs.length) break;
    var seg = latlngs.slice(start, end);
    if(seg.length > 1) {
      L.polyline(seg, {color: colors[lap % colors.length], weight: 3, opacity: 0.8}).addTo(map);
    }
    // Lap start marker
    var icon = L.divIcon({className:'', html:'<div class="label-s">L'+(lap+1)+'</div>', iconSize:[22,22], iconAnchor:[11,11]});
    L.marker(seg[0], {icon: icon}).addTo(map).bindPopup('第'+(lap+1)+'圈 起点');
  }

  // End marker
  var endIcon = L.divIcon({className:'', html:'<div class="label-e">E</div>', iconSize:[22,22], iconAnchor:[11,11]});
  L.marker(latlngs[latlngs.length-1], {icon: endIcon}).addTo(map).bindPopup('终点');

  map.fitBounds(L.latLngBounds(latlngs).pad(0.2));
}
</script>
</body>
</html>
""".trimIndent()

    // Simple math track fallback (same as in MelosHookEntry)
    private fun buildMathTrack(): com.melos.trajectory.TrackProfile {
        val center = com.melos.trajectory.LatLng(31.29217, 121.21242)
        val halfStraight = 84.39 / 2.0
        val radius = 36.5
        val arcSteps = 8
        fun local(eastM: Double, northM: Double) = com.melos.trajectory.GeoUtils.offsetMeters(center, eastM, northM)
        val pts = ArrayList<com.melos.trajectory.LatLng>()
        pts.add(local(-radius, -halfStraight))
        pts.add(local(-radius, +halfStraight))
        for (i in 1 until arcSteps) {
            val phi = Math.toRadians(180.0 - 180.0 * i / arcSteps)
            pts.add(local(radius * Math.cos(phi), halfStraight + radius * Math.sin(phi)))
        }
        pts.add(local(+radius, +halfStraight))
        pts.add(local(+radius, -halfStraight))
        for (i in 1 until arcSteps) {
            val phi = Math.toRadians(-180.0 * i / arcSteps)
            pts.add(local(radius * Math.cos(phi), -halfStraight + radius * Math.sin(phi)))
        }
        return com.melos.trajectory.TrackProfile("Math 400m", pts)
    }

    // Store for perimeter access in simulateTrajectory
    private val realTrackData = RealTrackLoader.load()

    override fun onBackPressed() {
        val webView = findViewById<WebView>(R.id.webView)
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
