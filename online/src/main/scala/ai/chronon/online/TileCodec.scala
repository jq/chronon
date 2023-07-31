package ai.chronon.online

import ai.chronon.aggregator.row.RowAggregator
import ai.chronon.api.{BooleanType, DataType, GroupBy, StructType}
import org.apache.avro.generic.GenericData
import ai.chronon.api.Extensions.{AggregationOps, MetadataOps}

import scala.collection.JavaConverters._

object TileCodec {
  def buildRowAggregator(groupBy: GroupBy, inputSchema: Seq[(String, DataType)]): RowAggregator = {
    // a set of Chronon groupBy aggregations needs to be flatted out to get the
    // full cross-product of all the feature column aggregations to be computed
    val unpackedAggs = groupBy.aggregations.asScala.flatMap(_.unpack)
    new RowAggregator(inputSchema, unpackedAggs)
  }
}

/**
  * TileCodec is a helper class that allows for the creation of pre-aggregated tiles of feature values.
  * These pre-aggregated tiles can be used in the serving layer to compute the final feature values along
  * with batch pre-aggregates produced by GroupByUploads.
  * The pre-aggregated tiles are serialized as Avro and indicate whether the tile is complete or not (partial aggregates)
  */
class TileCodec(rowAggregator: RowAggregator, groupBy: GroupBy) {
  val windowedIrSchema: StructType = StructType.from("WindowedIr", rowAggregator.irSchema)
  val fields: Array[(String, DataType)] = Array(
    "collapsedIr" -> windowedIrSchema,
    "isComplete" -> BooleanType
  )

  val tileChrononSchema: StructType =
    StructType.from(s"${groupBy.metaData.cleanName}_TILE_IR", fields)
  val tileAvroSchema: String = AvroConversions.fromChrononSchema(tileChrononSchema).toString()
  val tileAvroCodec: AvroCodec = AvroCodec.of(tileAvroSchema)
  private val irToBytesFn = AvroConversions.encodeBytes(tileChrononSchema, null)

  def makeTileIr(ir: Array[Any], isComplete: Boolean): Array[Byte] = {
    val normalizedIR = rowAggregator.normalize(ir)
    val tileIr: Array[Any] = Array(normalizedIR, Boolean.box(isComplete))
    irToBytesFn(tileIr)
  }

  def decodeTileIr(tileIr: Array[Byte]): (Array[Any], Boolean) = {
    val decodedTileIr = tileAvroCodec.decode(tileIr)
    val collapsedIr = decodedTileIr
      .get("collapsedIr")
      .asInstanceOf[GenericData.Record]

    val ir = AvroConversions
      .toChrononRow(collapsedIr, windowedIrSchema)
      .asInstanceOf[Array[Any]]
    val denormalizedIr = rowAggregator.denormalize(ir)
    val isComplete = decodedTileIr.get("isComplete").asInstanceOf[Boolean]
    (denormalizedIr, isComplete)
  }
}