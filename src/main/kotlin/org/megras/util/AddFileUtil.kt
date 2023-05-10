package org.megras.util

import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffmpeg.*
import com.github.kokorin.jaffree.ffprobe.FFprobe
import de.javagl.obj.ObjReader
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.pdfbox.pdmodel.PDDocument
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectDescriptor
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.id.IdUtil
import org.megras.id.ObjectId
import org.megras.segmentation.SegmentationBounds
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min


object AddFileUtil {

    fun addFile(objectStore: FileSystemObjectStore, quads: MutableQuadSet, file: PseudoFile): ObjectId {

        //store raw
        val descriptor = objectStore.store(file)
        val oid = IdUtil.generateId(file)

        quads.add(Quad(oid, MeGraS.RAW_ID.uri, StringValue(descriptor.id.id)))
        quads.add(Quad(oid, MeGraS.RAW_MIME_TYPE.uri, StringValue(descriptor.mimeType.mimeString)))
        quads.add(Quad(oid, MeGraS.MEDIA_TYPE.uri, StringValue(MediaType.mimeTypeMap[descriptor.mimeType]!!.name)))
        quads.add(Quad(oid, MeGraS.FILE_NAME.uri, StringValue(file.name)))

        //generate and store canonical
        val canonical = generateCanonicalRepresentation(objectStore, descriptor)
        quads.add(Quad(oid, MeGraS.CANONICAL_ID.uri, StringValue(canonical.id.id)))
        quads.add(Quad(oid, MeGraS.CANONICAL_MIME_TYPE.uri, StringValue(canonical.mimeType.mimeString)))

        return oid
    }


    private fun generateCanonicalRepresentation(
        objectStore: FileSystemObjectStore,
        rawDescriptor: StoredObjectDescriptor
    ): StoredObjectDescriptor {

        return when (rawDescriptor.mimeType) {

            MimeType.PNG -> {
                val imageStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val image = ImageIO.read(imageStream)

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    SegmentationBounds(0, image.width, 0, image.height)
                )
                objectStore.store(imageStream, descriptor)

                //return
                descriptor
            }
            MimeType.JPEG_I,
            MimeType.BMP,
            MimeType.GIF,
            MimeType.SVG,
            MimeType.TIFF -> {

                try {
                    val buffered = ImageIO.read(objectStore.get(rawDescriptor.id)!!.inputStream())

                    val rgbaImage = BufferedImage(buffered.width, buffered.height, BufferedImage.TYPE_INT_ARGB)
                    val g = rgbaImage.createGraphics()
                    g.drawImage(buffered, 0, 0, null)
                    g.dispose()

                    val outStream = ByteArrayOutputStream()
                    ImageIO.write(rgbaImage, "PNG", outStream)

                    val buf = outStream.toByteArray()
                    val inStream = ByteArrayInputStream(buf)
                    val id = objectStore.idFromStream(inStream)

                    inStream.reset()

                    val descriptor = StoredObjectDescriptor(
                        id,
                        MimeType.PNG,
                        buf.size.toLong(),
                        SegmentationBounds(0, buffered.width, 0, buffered.height)
                    )
                    objectStore.store(inStream, descriptor)

                    //return
                    descriptor

                } catch (e: Exception) {
                    //TODO log
                    rawDescriptor
                }
            }

            MimeType.AAC,
            MimeType.OGG_A,
            MimeType.MPEG_A,
            MimeType.MP4_A,
            MimeType.ADP,
            MimeType.AIF,
            MimeType.AU,
            MimeType.MIDI,
            MimeType.WAV,
            MimeType.WAX,
            MimeType.WMA,
            MimeType.MKV,
            MimeType.WEBM,
            MimeType.MOV,
            MimeType.MP4,
            MimeType.AVI,
            MimeType.OGG -> {
                try {
                    val videoStream = objectStore.get(rawDescriptor.id)!!.byteChannel()
                    val outStream = SeekableInMemoryByteChannel()
                    val durationMillis = AtomicLong()

                    val probe = FFprobe.atPath().setShowStreams(true).setInput(videoStream).execute().streams
                    val videoProbe = probe.first { s -> s.codecType == StreamType.VIDEO }

                    FFmpeg.atPath()
                        .addInput(ChannelInput.fromChannel(videoStream))
                        .setProgressListener { progress -> durationMillis.set(progress.timeMillis) }
                        .addArguments("-c:v", "libvpx-vp9")
                        .addArguments("-c:a", "libvorbis")
                        .setOverwriteOutput(true)
                        .addOutput(ChannelOutput.toChannel("", outStream).setFormat("webm"))
                        .execute()

                    val buf = outStream.array()
                    val inStream = ByteArrayInputStream(buf)
                    val id = objectStore.idFromStream(inStream)

                    inStream.reset()

                    val descriptor = StoredObjectDescriptor(
                        id,
                        MimeType.WEBM,
                        buf.size.toLong(),
                        SegmentationBounds(0, videoProbe.width, 0, videoProbe.height, 0, durationMillis.get() / 1000)
                    )
                    objectStore.store(inStream, descriptor)

                    //return
                    descriptor

                } catch (e: Exception) {
                    //TODO log
                    rawDescriptor
                }
            }

            MimeType.CSS,
            MimeType.CSV,
            MimeType.HTML,
            MimeType.JS,
            MimeType.JSON,
            MimeType.YAML,
            MimeType.TEXT -> {
                val textStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val buffer = textStream.readBytes()

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    SegmentationBounds(0, buffer.size)
                )
                objectStore.store(textStream, descriptor)

                //return
                descriptor
            }

            MimeType.PDF -> {
                val pdfStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val pdf = PDDocument.load(pdfStream)
                val page = pdf.getPage(0)

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    SegmentationBounds(0, ptToMm(page.mediaBox.width), 0, ptToMm(page.mediaBox.height), 0, pdf.numberOfPages)
                )
                objectStore.store(pdfStream, descriptor)

                //return
                descriptor
            }
            MimeType.OBJ -> {
                val objStream = objectStore.get(rawDescriptor.id)!!.inputStream()
                val obj = ObjReader.read(objStream)

                val b = floatArrayOf(
                    Float.MAX_VALUE, Float.MIN_VALUE,
                    Float.MAX_VALUE, Float.MIN_VALUE,
                    Float.MAX_VALUE, Float.MIN_VALUE
                )

                for (v in 0 until obj.numVertices) {
                    val vertex = obj.getVertex(v)

                    b[0] = min(vertex.x, b[0])
                    b[1] = max(vertex.x, b[1])
                    b[2] = min(vertex.y, b[2])
                    b[3] = max(vertex.y, b[3])
                    b[4] = min(vertex.z, b[4])
                    b[5] = max(vertex.z, b[5])
                }

                val descriptor = StoredObjectDescriptor(
                    rawDescriptor.id,
                    rawDescriptor.mimeType,
                    rawDescriptor.length,
                    SegmentationBounds(
                        b[0].toDouble(), b[1].toDouble(),
                        b[2].toDouble(), b[3].toDouble(),
                        b[4].toDouble(), b[5].toDouble()
                    )
                )
                objectStore.store(objStream, descriptor)

                //return
                descriptor
            }

            MimeType.OCTET_STREAM -> rawDescriptor
        }
    }

    private fun ptToMm(pt: Float): Float {
        return pt * 25.4f / 72;
    }
}