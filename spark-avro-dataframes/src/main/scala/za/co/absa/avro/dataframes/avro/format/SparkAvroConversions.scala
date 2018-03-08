package za.co.absa.avro.dataframes.avro.format

import org.apache.avro.SchemaBuilder
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.DateType
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.types.BinaryType
import org.apache.spark.sql.types.DecimalType
import org.apache.spark.sql.types.BooleanType
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.ByteType
import org.apache.spark.sql.types.FloatType
import org.apache.spark.sql.types.ShortType
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.Row
import java.nio.ByteBuffer
import java.util.HashMap
import com.databricks.spark.avro.SchemaConverters
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.sql.types.ArrayType
import org.apache.avro.generic.GenericData.Record
import java.sql.Timestamp
import java.sql.Date
import org.apache.avro.Schema
import org.apache.spark.sql.types.MapType
import com.databricks.spark.avro.DatabricksAdapter
import com.databricks.spark.avro.SchemaConverters.SchemaType
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.EncoderFactory
import org.apache.avro.generic.GenericDatumWriter
import java.io.ByteArrayOutputStream
import org.apache.avro.generic.IndexedRecord
import scalaz.std.effect.writer
import za.co.absa.avro.dataframes.avro.write.AvroWriterHolder
import scala.collection._

object SparkAvroConversions {

  private case class ConverterKey(sparkSchema: DataType, recordName: String, recordNamespace: String) 
  private val converterCache = new mutable.HashMap[ConverterKey, Any => Any]()
  
  private def avroWriterHolder = new AvroWriterHolder()
  
  def toByteArray(record: IndexedRecord, schema: Schema): Array[Byte] = {    
    val outStream = new ByteArrayOutputStream()
    val encoder = avroWriterHolder.getEncoder(outStream)
    try {                 
      avroWriterHolder.getWriter(schema).write(record, encoder)
      encoder.flush()
      outStream.flush()
      outStream.toByteArray()
    } finally {      
      outStream.close()
    }    
  }  
  
  def toAvroSchema(
      structType: StructType,
      schemaName: String,
      schemaNamespace: String): Schema = {
    val build = SchemaBuilder.record(schemaName).namespace(schemaNamespace)    
    DatabricksAdapter.convertStructToAvro(structType, build, schemaNamespace)
  }
  
  def rowToBinaryAvro(row: Row, sparkSchema: StructType, avroSchema: Schema) = {
    val record = rowToGenericRecord(row, sparkSchema, avroSchema)    
    toByteArray(record, avroSchema)
  }

  private def rowToGenericRecord(row: Row, sparkSchema: StructType, avroSchema: Schema) = {
    //val converter = SparkAvroConversions.createConverterToAvro(sparkSchema, avroSchema.getName, avroSchema.getNamespace)
    val converter = getConverter(sparkSchema, avroSchema.getName, avroSchema.getNamespace)
    converter(row).asInstanceOf[GenericRecord]
  }
  
  private def getConverter(dataType: DataType, name: String, namespace: String): Any => Any = {
    converterCache.getOrElseUpdate(new ConverterKey(dataType, name, namespace), SparkAvroConversions.createConverterToAvro(dataType, name, namespace)) 
  }
  
  /**
   * Translates an Avro Schema into a Spark's StructType.
   * 
   * Relies on Databricks Spark-Avro library to do the job.
   */
  def toSqlType(schema: Schema): StructType = {
    DatabricksAdapter.toSqlType(schema).dataType.asInstanceOf[StructType]
  }   
  
  // copied from Databricks as this is a very convenient method which is encapsulated by one of their classes
  private def createConverterToAvro(
    dataType:        DataType,
    structName:      String,
    recordNamespace: String): (Any) => Any = {
    dataType match {
      case BinaryType => (item: Any) => item match {
        case null               => null
        case bytes: Array[Byte] => ByteBuffer.wrap(bytes)
      }
      case ByteType | ShortType | IntegerType | LongType |
        FloatType | DoubleType | StringType | BooleanType => identity
      case _: DecimalType => (item: Any) => if (item == null) null else item.toString
      case TimestampType => (item: Any) =>
        if (item == null) null else item.asInstanceOf[Timestamp].getTime
      case DateType => (item: Any) =>
        if (item == null) null else item.asInstanceOf[Date].getTime
      case ArrayType(elementType, _) =>
        val elementConverter = createConverterToAvro(
          elementType,
          structName,
          DatabricksAdapter.getNewRecordNamespace(elementType, recordNamespace, structName))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val sourceArray = item.asInstanceOf[Seq[Any]]
            val sourceArraySize = sourceArray.size
            val targetArray = new Array[Any](sourceArraySize)
            var idx = 0
            while (idx < sourceArraySize) {
              targetArray(idx) = elementConverter(sourceArray(idx))
              idx += 1
            }
            targetArray
          }
        }
      case MapType(StringType, valueType, _) =>
        val valueConverter = createConverterToAvro(
          valueType,
          structName,
          DatabricksAdapter.getNewRecordNamespace(valueType, recordNamespace, structName))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val javaMap = new HashMap[String, Any]()
            item.asInstanceOf[Map[String, Any]].foreach {
              case (key, value) =>
                javaMap.put(key, valueConverter(value))
            }
            javaMap
          }
        }
      case structType: StructType =>
        val builder = SchemaBuilder.record(structName).namespace(recordNamespace)
        val schema: Schema = SchemaConverters.convertStructToAvro(
          structType, builder, recordNamespace)
        val fieldConverters = structType.fields.map(field =>
          createConverterToAvro(
            field.dataType,
            field.name,
            DatabricksAdapter.getNewRecordNamespace(field.dataType, recordNamespace, field.name)))
        (item: Any) => {
          if (item == null) {
            null
          } else {
            val record = new Record(schema)
            val convertersIterator = fieldConverters.iterator
            val fieldNamesIterator = dataType.asInstanceOf[StructType].fieldNames.iterator
            val rowIterator = item.asInstanceOf[Row].toSeq.iterator

            while (convertersIterator.hasNext) {
              val converter = convertersIterator.next() 
              record.put(fieldNamesIterator.next(), converter(rowIterator.next()))
            }
            record
          }
        }
    }
  }  
}