package com.melos.collector

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.melos.R

class MapActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val pointsJson = intent.getStringExtra("points") ?: "[]"
        val venue = intent.getStringExtra("venue") ?: ""
        val mode = intent.getStringExtra("mode") ?: "point"

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        val html = if (mode == "trajectory") buildTrajectoryHtml(pointsJson, venue) else buildPointHtml(pointsJson, venue)
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val webView = findViewById<WebView>(R.id.webView)
        if (webView.canGoBack()) webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun buildPointHtml(pointsJson: String, venue: String): String {
        val escapedVenue = venue.replace("'", "\\'")
        return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0;padding:0;}
.point-label{background:#1976D2;color:#fff;border-radius:50%;width:24px;height:24px;line-height:24px;text-align:center;font-size:12px;font-weight:bold;}</style>
</head><body><div id="map"></div><script>
var pts=$pointsJson;
if(!pts.length){document.body.innerHTML='<h3 style="text-align:center;padding:40px">没有采集点</h3>';}
else{var latSum=0,lngSum=0;pts.forEach(function(p){latSum+=p.lat;lngSum+=p.lng;});
var center=[latSum/pts.length,lngSum/pts.length];
var map=L.map('map').setView(center,17);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'OpenStreetMap'}).addTo(map);
var latlngs=[];pts.forEach(function(p,i){var icon=L.divIcon({className:'',html:'<div class="point-label">'+(i+1)+'</div>',iconSize:[24,24],iconAnchor:[12,12]});
L.marker([p.lat,p.lng],{icon:icon}).addTo(map).bindPopup('<b>$escapedVenue 点'+(i+1)+'</b><br>'+p.lat.toFixed(6)+', '+p.lng.toFixed(6)+'<br>精度: '+p.acc.toFixed(1)+'m<br>WiFi: '+p.wifi+' AP | 基站: '+p.cell+' 小区');
latlngs.push([p.lat,p.lng]);});
if(latlngs.length>1){L.polyline(latlngs,{color:'#1976D2',weight:3,dashArray:'8,6'}).addTo(map);}
map.fitBounds(L.featureGroup(pts.map(function(p){return L.marker([p.lat,p.lng]);})).getBounds().pad(0.3));}
</script></body></html>""".trimIndent()
    }

    private fun buildTrajectoryHtml(pointsJson: String, venue: String): String {
        val escapedVenue = venue.replace("'", "\\'")
        return """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0;padding:0;}
.start-marker{background:#4CAF50;color:#fff;border-radius:50%;width:20px;height:20px;line-height:20px;text-align:center;font-size:11px;font-weight:bold;}
.end-marker{background:#F44336;color:#fff;border-radius:50%;width:20px;height:20px;line-height:20px;text-align:center;font-size:11px;font-weight:bold;}</style>
</head><body><div id="map"></div><script>
var pts=$pointsJson;
if(!pts.length){document.body.innerHTML='<h3 style="text-align:center;padding:40px">没有数据</h3>';}
else{var latSum=0,lngSum=0;pts.forEach(function(p){latSum+=p.lat;lngSum+=p.lng;});
var center=[latSum/pts.length,lngSum/pts.length];
var map=L.map('map').setView(center,17);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'OpenStreetMap'}).addTo(map);
var latlngs=pts.map(function(p){return[p.lat,p.lng];});
L.polyline(latlngs,{color:'#1976D2',weight:3,opacity:0.8}).addTo(map);
var si=L.divIcon({className:'',html:'<div class="start-marker">S</div>',iconSize:[20,20],iconAnchor:[10,10]});
L.marker(latlngs[0],{icon:si}).addTo(map).bindPopup('<b>起点</b><br>'+pts[0].lat.toFixed(6)+', '+pts[0].lng.toFixed(6));
if(pts.length>1){var ei=L.divIcon({className:'',html:'<div class="end-marker">E</div>',iconSize:[20,20],iconAnchor:[10,10]});
L.marker(latlngs[latlngs.length-1],{icon:ei}).addTo(map).bindPopup('<b>终点</b><br>'+pts[pts.length-1].lat.toFixed(6)+', '+pts[pts.length-1].lng.toFixed(6));}
if(pts.length>2){for(var i=1;i<pts.length-1;i++){L.circleMarker(latlngs[i],{radius:2,color:'#1976D2',fillColor:'#1976D2',fillOpacity:0.5,weight:0}).addTo(map);}}
map.fitBounds(L.featureGroup(pts.map(function(p){return L.marker([p.lat,p.lng]);})).getBounds().pad(0.3));}
</script></body></html>""".trimIndent()
    }
}
