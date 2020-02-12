/*
 * Copyright 2020 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.example.exporttiles

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.esri.arcgisruntime.concurrent.Job
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.data.TileCache
import com.esri.arcgisruntime.geometry.Envelope
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.layers.ArcGISTiledLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheJob
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheParameters
import com.esri.arcgisruntime.tasks.tilecache.ExportTileCacheTask
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.dialog_layout.*
import java.io.File
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity() {

  private val TAG: String = MainActivity::class.java.simpleName
  private var exportTileCacheJob: ExportTileCacheJob? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // create an ArcGISTiledLayer to use as the basemap
    val tiledLayer = ArcGISTiledLayer(getString(R.string.world_street_map))
    val map = ArcGISMap().apply {
      basemap = Basemap(tiledLayer)
      minScale = 10000000.0
    }

    // create a graphic and graphics overlay to show a red box around the tiles to be downloaded
    val downloadArea = Graphic()
    downloadArea.symbol = SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.RED, 2f)
    val graphicsOverlay = GraphicsOverlay()
    graphicsOverlay.graphics.add(downloadArea)

    mapView.apply {
      // set the map to the map view
      this.map = map
      setViewpoint(Viewpoint(51.5, 0.0, 10000000.0))

      // add the graphics overlay to the map view
      graphicsOverlays.add(graphicsOverlay)

      // update the box whenever the viewpoint changes
      addViewpointChangedListener {
        if (mapView.map.loadStatus == LoadStatus.LOADED) {
          // upper left corner of the downloaded tile cache area
          val minScreenPoint: android.graphics.Point = android.graphics.Point(150, 175)
          // lower right corner of the downloaded tile cache area
          val maxScreenPoint: android.graphics.Point = android.graphics.Point(
            mapView.width - 150,
            mapView.height - 250
          )
          // convert screen points to map points
          val minPoint: Point = mapView.screenToLocation(minScreenPoint)
          val maxPoint: Point = mapView.screenToLocation(maxScreenPoint)
          // use the points to define and return an envelope
          downloadArea.geometry = Envelope(minPoint, maxPoint)
        }
      }
    }

    // create up a temporary directory in the app's cache for saving exported tiles
    val exportTilesDirectory = File(cacheDir, getString(R.string.tile_cache_folder))

    // when the button is clicked, export the tiles to a temporary file
    exportTilesButton.setOnClickListener {
      val exportTileCacheTask = ExportTileCacheTask(tiledLayer.uri)
      val parametersFuture: ListenableFuture<ExportTileCacheParameters> =
        exportTileCacheTask.createDefaultExportTileCacheParametersAsync(
          downloadArea.geometry,
          mapView.mapScale,
          tiledLayer.maxScale
        )

      parametersFuture.addDoneListener {

        try {
          val parameters: ExportTileCacheParameters = parametersFuture.get()
          // export tiles to temporary cache on device
          exportTileCacheJob =
            exportTileCacheTask.exportTileCache(
              parameters,
              exportTilesDirectory.path + "file.tpk"
            ).apply {
              // start the export tile cache job
              start()
              val dialog = createProgressDialog(this)
              dialog.show()
              // show progress of the export tile cache job on the progress bar
              addProgressChangedListener{dialog.progressBar.progress = progress}
              // when the job has completed, close the dialog and show the job result in the map preview
              addJobDoneListener {
                dialog.dismiss()
                if (status == Job.Status.SUCCEEDED) {
                  showMapPreview(result)
                } else {
                  ("Job did not succeed: " + error.additionalMessage).also {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    Log.e(TAG, error.additionalMessage)
                  }
                }
              }
            }

        } catch (e: InterruptedException) {
          Toast.makeText(this, "TileCacheParameters interrupted: " + e.message, Toast.LENGTH_LONG).show()
          Log.e(TAG, "TileCacheParameters interrupted: " + e.message)
        } catch (e: ExecutionException) {
          Toast.makeText(this, "Error generating parameters: " + e.message, Toast.LENGTH_LONG).show()
          Log.e(TAG, "Error generating parameters: " + e.message)
        }
      }
    }
  }

  /**
   * Show tile cache preview window including containing the exported tiles.
   *
   * @param result takes the TileCache from the ExportTileCacheJob
   */
  private fun showMapPreview(result: TileCache) {
    val newTiledLayer = ArcGISTiledLayer(result)
    val previewMap = ArcGISMap(Basemap(newTiledLayer))

    // set up the preview map view
    previewMapView.apply {
      map = previewMap
      setViewpoint(mapView.getCurrentViewpoint(Viewpoint.Type.CENTER_AND_SCALE))
      visibility = View.VISIBLE
    }
    // control UI visibility
    closeButton.visibility = View.VISIBLE
    dimBackground.visibility = View.VISIBLE
    preview_text_view.visibility = View.VISIBLE
    exportTilesButton.visibility = View.GONE
  }

  /**
   * Create a progress dialog box for tracking the export tile cache job.
   *
   * @param exportTileCacheJob the export tile cache job progress to be tracked
   * @return an AlertDialog set with the dialog layout view
   */
  private fun createProgressDialog(exportTileCacheJob: ExportTileCacheJob): AlertDialog {
    val builder = AlertDialog.Builder(this@MainActivity).apply {
      setTitle("Exporting tiles...")
      // provide a cancel button on the dialog
      setNeutralButton("Cancel") { _, _ ->
        exportTileCacheJob.cancel()
      }
      setView(R.layout.dialog_layout)
    }

    return builder.create()
  }

  /**
   * Clear the preview window.
   */
  fun clearPreview(view: View) {
    previewMapView.getChildAt(0).visibility = View.INVISIBLE
    mapView.bringToFront()

    exportTilesButton.visibility = View.VISIBLE
    mapView.visibility = View.VISIBLE
  }

  /**
   * Recursively deletes all files in the given directory.
   *
   * @param dir to delete
   */
  private fun deleteDirectory(dir: File?): Boolean {
    return if (dir != null && dir.isDirectory) {
      val children = dir.list()
      for (child in children) {
        val success = deleteDirectory(File(dir, child))
        if (!success) {
          return false
        }
      }
      dir.delete()
    } else if (dir != null && dir.isFile) {
      dir.delete()
    } else {
      false
    }
  }

  override fun onResume() {
    super.onResume()
    mapView.resume()
    previewMapView.resume()
  }

  override fun onPause() {
    mapView.pause()
    previewMapView.pause()
    // delete app cache when the app loses focus
    deleteDirectory(cacheDir)
    super.onPause()
  }

  override fun onDestroy() {
    mapView.dispose()
    previewMapView.dispose()
    super.onDestroy()
  }
}