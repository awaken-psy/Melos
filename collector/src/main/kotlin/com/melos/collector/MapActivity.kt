package com.melos.collector

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MapActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val pointsJson = intent.getStringExtra("points") ?: "[]"
        val venue = intent.getStringExtra("venue") ?: ""

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        val html = buildMapHtml(pointsJson, venue)
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null)
    }

    private fun buildMapHtml(pointsJson: String, venue: String): String {
        val escapedVenue = venue.replace("'", "\\'")
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html,body,#map{height:100%;margin:0;padding:0;}
  .point-label {
    background:#1976D2;color:#fff;border-radius:50%;
    width:24px;height:24px;line-height:24px;
    text-align:center;font-size:12px;font-weight:bold;
  }
</style>
</head>
<body>
<div id="map"></div>
<script>
var pts = $pointsJson;
if(pts.length === 0) { document.body.innerHTML='<h3 style="text-align:center;padding:40px">没有采集点</h3>'; }
else {
  var latSum=0, lngSum=0;
  pts.forEach(function(p){ latSum+=p.lat; lngSum+=p.lng; });
  var center = [latSum/pts.length, lngSum/pts.length];

  var map = L.map('map').setView(center, 17);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap'
  }).addTo(map);

  var latlngs = [];
  pts.forEach(function(p, i) {
    var icon = L.divIcon({className:'', html:'<div class="point-label">'+(i+1)+'</div>', iconSize:[24,24], iconAnchor:[12,12]});
    var marker = L.marker([p.lat, p.lng], {icon: icon}).addTo(map);
    var popup = '<b>' + '$escapedVenue' + ' 点' + (i+1) + '</b><br>'
      + p.lat.toFixed(6) + ', ' + p.lng.toFixed(6) + '<br>'
      + '精度: ' + p.acc.toFixed(1) + 'm<br>'
      + 'WiFi: ' + p.wifi + ' AP | 基站: ' + p.cell + ' 小区';
    marker.bindPopup(popup);
    latlngs.push([p.lat, p.lng]);
  });

  if(latlngs.length > 1) {
    L.polyline(latlngs, {color:'#1976D2', weight:3, dashArray:'8,6'}).addTo(map);
  }

  var group = L.featureGroup(pts.map(function(p,i){
    return L.marker([p.lat, p.lng]);
  }));
  map.fitBounds(group.getBounds().pad(0.3));
}
</script>
</body>
</html>
""".trimIndent()
    }

    override fun onBackPressed() {
        val webView = findViewById<WebView>(R.id.webView)
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
