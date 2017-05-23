package geopyspark.geotrellis

import geopyspark.geotrellis.GeoTrellisUtils._

import geotrellis.proj4._
import geotrellis.raster._
import geotrellis.raster.rasterize._
import geotrellis.raster.render._
import geotrellis.raster.resample.ResampleMethod
import geotrellis.spark._
import geotrellis.spark.costdistance.IterativeCostDistance
import geotrellis.spark.io._
import geotrellis.spark.io.avro._
import geotrellis.spark.io.json._
import geotrellis.spark.mapalgebra.local._
import geotrellis.spark.mapalgebra.focal._
import geotrellis.spark.mask.Mask
import geotrellis.spark.pyramid._
import geotrellis.spark.reproject._
import geotrellis.spark.tiling._
import geotrellis.util._
import geotrellis.vector._
import geotrellis.vector.io.wkt.WKT

import spray.json._
import spray.json.DefaultJsonProtocol._
import spire.syntax.cfor._

import org.apache.spark._
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.rdd._
import org.apache.spark.SparkContext._

import java.util.ArrayList
import scala.reflect._
import scala.collection.JavaConverters._

import geotrellis.spark.io.avro._
import org.apache.avro._
import org.apache.avro.generic._


class PyramidRasterRDD(
  val rdd: RDD[(PyramidKey, MultibandTile)] with Metadata[TileLayerMetadata[PyramidKey]]
) extends TiledRasterRDD[PyramidKey] {

  def lookup(
    zoom: Int,
    col: Int,
    row: Int
  ): (java.util.ArrayList[Array[Byte]], String) = {
    val tiles = rdd.lookup(PyramidKey(zoom, SpatialKey(col, row)))
    PythonTranslator.toPython(tiles)
  }

  def reproject(
    layout: Either[LayoutScheme, LayoutDefinition],
    crs: CRS,
    options: Reproject.Options
  ): TiledRasterRDD[SpatialKey] = {
    val (zoom, reprojected) = TileRDDReproject(rdd, crs, layout, options)
    PyramidRasterRDD(reprojected)
  }

  def tileToLayout(
    layoutDefinition: LayoutDefinition,
    resampleMethod: String
  ): TiledRasterRDD[PyramidKey] = {
    val method: ResampleMethod = TileRDD.getResampleMethod(resampleMethod)
    val mapKeyTransform =
      MapKeyTransform(
        layoutDefinition.extent,
        layoutDefinition.layoutCols,
        layoutDefinition.layoutRows)

    val crs = rdd.metadata.crs
    val projectedRDD = rdd.map{ x => (ProjectedExtent(mapKeyTransform(x._1), crs), x._2) }
    val retiledLayerMetadata = rdd.metadata.copy(
      layout = layoutDefinition,
      bounds = KeyBounds(mapKeyTransform(rdd.metadata.extent))
    )

    val tileLayer =
      MultibandTileLayerRDD(projectedRDD.tileToLayout(retiledLayerMetadata, method), retiledLayerMetadata)

    PyramidRasterRDD(tileLayer)
  }

  def pyramid(
    startZoom: Int,
    endZoom: Int,
    resampleMethod: String
  ): Array[TiledRasterRDD[PyramidKey]] = {
    require(! rdd.metadata.bounds.isEmpty, "Can not pyramid an empty RDD")

    val method: ResampleMethod = TileRDD.getResampleMethod(resampleMethod)
    val scheme = ZoomedLayoutScheme(rdd.metadata.crs, rdd.metadata.tileRows)
    val part = rdd.partitioner.getOrElse(new HashPartitioner(rdd.partitions.length))

    val leveledList =
      Pyramid.levelStream(
        rdd,
        scheme,
        startZoom,
        endZoom,
        Pyramid.Options(resampleMethod=method, partitioner=part)
      )

    leveledList.map{ x => PyramidRasterRDD(Some(x._1), x._2) }.toArray
  }

  def focal(
    operation: String,
    neighborhood: String,
    param1: Double,
    param2: Double,
    param3: Double
  ): TiledRasterRDD[PyramidKey] = {
    val singleTileLayerRDD: TileLayerRDD[SpatialKey] = TileLayerRDD(
      rdd.map({ case (k, v) => (k, v.band(0)) }),
      rdd.metadata
    )

    val _neighborhood = getNeighborhood(operation, neighborhood, param1, param2, param3)
    val cellSize = rdd.metadata.layout.cellSize
    val op: ((Tile, Option[GridBounds]) => Tile) = getOperation(operation, _neighborhood, cellSize, param1)

    val result: TileLayerRDD[SpatialKey] = FocalOperation(singleTileLayerRDD, _neighborhood)(op)

    val multibandRDD: MultibandTileLayerRDD[SpatialKey] =
      MultibandTileLayerRDD(result.map{ x => (x._1, MultibandTile(x._2)) }, result.metadata)

    PyramidRasterRDD(None, multibandRDD)
  }

  def mask(geometries: Seq[MultiPolygon]): TiledRasterRDD[PyramidKey] = {
    val options = Mask.Options.DEFAULT
    val singleBand = ContextRDD(
      rdd.map({ case (k, v) => (k, v.band(0)) }),
      rdd.metadata
    )
    val result = Mask(singleBand, geometries, options)
    val multiBand = MultibandTileLayerRDD(
      result.map({ case (k, v) => (k, MultibandTile(v)) }),
      result.metadata
    )
    PyramidRasterRDD(zoomLevel, multiBand)
  }

  def stitch: (Array[Byte], String) = {
    val contextRDD = ContextRDD(
      rdd.map({ case (k, v) => (k, v.band(0)) }),
      rdd.metadata
    )

    PythonTranslator.toPython(contextRDD.stitch.tile)
  }

  def costDistance(
    sc: SparkContext,
    geometries: Seq[Geometry],
    maxDistance: Double
  ): TiledRasterRDD[PyramidKey] = {
    val singleTileLayer = TileLayerRDD(
      rdd.map({ case (k, v) => (k, v.band(0)) }),
      rdd.metadata
    )

    implicit def conversion(k: SpaceTimeKey): SpatialKey =
      k.spatialKey

    implicit val _sc = sc

    val result: TileLayerRDD[SpatialKey] =
      IterativeCostDistance(singleTileLayer, geometries, maxDistance)

    val multibandRDD: MultibandTileLayerRDD[SpatialKey] =
      MultibandTileLayerRDD(result.map{ x => (x._1, MultibandTile(x._2)) }, result.metadata)

    PyramidRasterRDD(None, multibandRDD)
  }

  def reclassify(reclassifiedRDD: RDD[(SpatialKey, MultibandTile)]): TiledRasterRDD[PyramidKey] =
    PyramidRasterRDD(zoomLevel, MultibandTileLayerRDD(reclassifiedRDD, rdd.metadata))

  def reclassifyDouble(reclassifiedRDD: RDD[(SpatialKey, MultibandTile)]): TiledRasterRDD[PyramidKey] =
    PyramidRasterRDD(zoomLevel, MultibandTileLayerRDD(reclassifiedRDD, rdd.metadata))

  def withRDD(result: RDD[(SpatialKey, MultibandTile)]): TiledRasterRDD[PyramidKey] =
    PyramidRasterRDD(zoomLevel, MultibandTileLayerRDD(result, rdd.metadata))

  def toInt(converted: RDD[(SpatialKey, MultibandTile)]): TiledRasterRDD[PyramidKey] =
    PyramidRasterRDD(zoomLevel, MultibandTileLayerRDD(converted, rdd.metadata))

  def toDouble(converted: RDD[(SpatialKey, MultibandTile)]): TiledRasterRDD[PyramidKey] =
    PyramidRasterRDD(zoomLevel, MultibandTileLayerRDD(converted, rdd.metadata))
}
