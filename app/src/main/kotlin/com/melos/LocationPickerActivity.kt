package com.melos

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

/**
 * Map-based location picker. User taps the map to place a marker,
 * then clicks "保存此位置" and enters a name. The saved location
 * is persisted and returned to the caller.
 */
class LocationPickerActivity : AppCompatActivity() {

    companion object {
        // GCJ-02 coordinates for quick-nav chips (user-calibrated positions)
        private val SIPING_STADIUM = doubleArrayOf(31.28015301850486, 121.50378979363322)
        private val JIADING_STADIUM = doubleArrayOf(31.28831747052701, 121.21599811379792)
    }

    private var pickedLat = 0.0
    private var pickedLng = 0.0
    private var hasPick = false
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MelosConfig.appContext = applicationContext
        setContentView(R.layout.activity_location_picker)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        val tvCoord = findViewById<TextView>(R.id.tvCoord)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        // JS interface: map tap reports coordinates back to Kotlin
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onMapTap(lat: Double, lng: Double) {
                runOnUiThread {
                    pickedLat = lat
                    pickedLng = lng
                    hasPick = true
                    tvCoord.text = String.format("%.6f, %.6f", lat, lng)
                    btnSave.visibility = MaterialButton.VISIBLE
                }
            }
        }, "Android")

        // Default center: Tongji Siping area
        val html = buildPickerHtml(31.2800, 121.5000)
        webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null)

        btnSave.setOnClickListener {
            if (!hasPick) return@setOnClickListener
            showSaveDialog()
        }

        // Quick-nav chips
        findViewById<Chip>(R.id.chip_siping).setOnClickListener {
            flyTo(SIPING_STADIUM[0], SIPING_STADIUM[1])
        }
        findViewById<Chip>(R.id.chip_jiading).setOnClickListener {
            flyTo(JIADING_STADIUM[0], JIADING_STADIUM[1])
        }
    }

    private fun flyTo(lat: Double, lng: Double) {
        webView.evaluateJavascript("flyTo($lat, $lng);", null)
    }

    private fun showSaveDialog() {
        val input = EditText(this).apply {
            hint = "例如：体育馆"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("为位置命名")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton

                val loc = MelosConfig.saveLocation(name, pickedLat, pickedLng)
                setResult(RESULT_OK, Intent().apply {
                    putExtra("id", loc.id)
                    putExtra("name", loc.name)
                    putExtra("lat", loc.lat)
                    putExtra("lng", loc.lng)
                })
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildPickerHtml(centerLat: Double, centerLng: Double): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html,body,#map{height:100%;margin:0;padding:0;}
  .crosshair{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);z-index:1000;pointer-events:none;}
  .crosshair::before,.crosshair::after{content:'';position:absolute;background:rgba(25,118,210,0.7);}
  .crosshair::before{width:2px;height:32px;left:50%;top:50%;transform:translate(-50%,-50%);}
  .crosshair::after{width:32px;height:2px;left:50%;top:50%;transform:translate(-50%,-50%);}
</style>
</head>
<body>
<div id="map"></div>
<div class="crosshair"></div>
<script>
var center = [$centerLat, $centerLng];
var map = L.map('map').setView(center, 16);
L.tileLayer('https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {maxZoom:18}).addTo(map);

var marker = null;

map.on('click', function(e) {
  var lat = e.latlng.lat;
  var lng = e.latlng.lng;
  if (!marker) {
    marker = L.marker([lat, lng], {draggable: true}).addTo(map);
    marker.on('dragend', function() {
      var p = marker.getLatLng();
      Android.onMapTap(p.lat, p.lng);
    });
  } else {
    marker.setLatLng([lat, lng]);
  }
  Android.onMapTap(lat, lng);
});

function flyTo(lat, lng) {
  map.flyTo([lat, lng], 17, {duration: 1.0});
  if (!marker) {
    marker = L.marker([lat, lng], {draggable: true}).addTo(map);
    marker.on('dragend', function() {
      var p = marker.getLatLng();
      Android.onMapTap(p.lat, p.lng);
    });
  } else {
    marker.setLatLng([lat, lng]);
  }
  Android.onMapTap(lat, lng);
}
</script>
</body>
</html>
""".trimIndent()
}
