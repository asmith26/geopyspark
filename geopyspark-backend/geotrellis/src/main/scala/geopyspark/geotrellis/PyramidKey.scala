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


case class PyramidKey(zoom: Int, key: SpatialKey)

object PyramidKey {
  implicit val spatialComponent = {
    Component[PyramidKey, SpatialKey](
      k => k.key,
      (k, sk) => PyramidKey(k.zoom, sk)
    )
  }

  implicit object AvroRecordCodec extends AvroRecordCodec[PyramidKey] {
    def schema = SchemaBuilder
      .record("PyramidKey").namespace("geotrellis.spark")
      .fields()
      .name("col").`type`().intType().noDefault()
      .name("row").`type`().intType().noDefault()
      .name("zoom").`type`().intType().noDefault()
      .endRecord()

    def encode(key: PyramidKey, rec: GenericRecord) = {
      rec.put("col", key.key.col)
      rec.put("row", key.key.row)
      rec.put("zoom", key.zoom)
    }

    def decode(rec: GenericRecord): PyramidKey = {
      val sk = SpatialKey(rec[Int]("col"), rec[Int]("row"))
      PyramidKey(rec[Int]("zoom"), sk)
    }
  }

  implicit object JsonFormat extends RootJsonFormat[PyramidKey] {
    def write(key: PyramidKey) =
      JsObject(
        "col" -> JsNumber(key.key.col),
        "row" -> JsNumber(key.key.row),
        "zoom" -> JsNumber(key.zoom)
      )

    def read(value: JsValue): PyramidKey =
      value.asJsObject.getFields("col", "row", "zoom") match {
        case Seq(JsNumber(col), JsNumber(row), JsNumber(zoom)) =>
          PyramidKey(zoom.toInt, SpatialKey(col.toInt, row.toInt))
        case _ =>
          throw new DeserializationException("PyramidKey expected")
      }
  }

  implicit object Boundable extends Boundable[PyramidKey] {
    def minBound(a: PyramidKey, b: PyramidKey) = {
      require(a.zoom == b.zoom)
      val min = implicitly[Boundable[SpatialKey]].minBound(a.key, b.key)
      PyramidKey(a.zoom, min)
    }

    def maxBound(a: PyramidKey, b: PyramidKey) = {
      require(a.zoom == b.zoom)
      val max = implicitly[Boundable[SpatialKey]].maxBound(a.key, b.key)
      PyramidKey(a.zoom, max)
    }
  }
}
