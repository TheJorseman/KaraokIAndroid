package com.karaokei.core.ai.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

/**
 * Rewrites an ONNX model's FLOAT16 initializers to FLOAT32.
 *
 * Why: on ARM64 devices the ORT CPU EP produces corrupted inference
 * output for graphs that carry FP16 initializers (measured with
 * HTDemucs FT: the separated vocals were uncorrelated with the correct
 * x86 output and whisper hallucinated on them), while the same model,
 * input and ORT version produce correct output on x86_64. The graph
 * only feeds those FP16 initializers into `Cast(to=FLOAT)` nodes, so
 * widening them to FLOAT32 at load time is semantically identical
 * (host outputs correlate 1.000000 before/after) while bypassing the
 * broken device-side FP16 path.
 *
 * Implementation: a two-pass, offset-based protobuf rewrite that never
 * holds the model in memory. Pass 1 walks the message structure and
 * records byte ranges plus the rewritten sizes; pass 2 streams the
 * output, copying untouched ranges and converting fp16 raw_data in
 * small chunks. Peak memory is one chunk buffer, so the 165 MB
 * HTDemucs model converts inside the app's 192 MB heap limit.
 */
object OnnxFp32Upcast {

    private const val GRAPH_FIELD = 7 // ModelProto.graph
    private const val INITIALIZER_FIELD = 5 // GraphProto.initializer
    private const val TENSOR_DATA_TYPE = 2 // TensorProto.data_type
    private const val TENSOR_RAW_DATA = 9 // TensorProto.raw_data
    private const val TENSOR_DATA_LOCATION = 12 // TensorProto.data_location
    private const val DATA_TYPE_FLOAT16 = 10
    private const val DATA_TYPE_FLOAT = 1

    private const val CHUNK_CONVERT_BYTES = 4 shl 20 // 2 MiB of fp16 input per step

    /** Byte range `[start, end)` of a field on the source file. */
    private class FieldRange(val start: Long, val end: Long)

    /** A parsed initializer: the tensor field's range plus its edit points. */
    private class TensorInfo(
        val field: FieldRange,
        val payloadStart: Long,
        val dataType: Int,
        val dataTypeValuePos: Long,
        val dataTypeValueLen: Int,
        val rawField: FieldRange?, // whole raw_data field (tag..end)
        val rawPayloadStart: Long,
        val rawPayloadLen: Long,
        val externalData: Boolean,
    )

    /** True when [file] contains at least one in-file FLOAT16 initializer. */
    fun containsFp16Initializers(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        walkModel(raf).tensors.any {
            it.dataType == DATA_TYPE_FLOAT16 && !it.externalData && it.rawField != null
        }
    }

    /**
     * Copies [input] to [output], widening every FLOAT16 initializer to
     * FLOAT32. Returns the number of converted initializers. The
     * output is written atomically (temp file + rename).
     */
    fun upcast(input: File, output: File): Int {
        val tmp = File(output.parentFile, output.name + ".part")
        try {
            val converted = RandomAccessFile(input, "r").use { raf ->
                val plan = walkModel(raf)
                tmp.outputStream().use { fos ->
                    val ch = Channels.newChannel(fos)
                    writeUpcast(raf, ch, plan)
                }
            }
            if (output.exists()) output.delete()
            if (!tmp.renameTo(output)) {
                throw IllegalStateException("failed to move upcast model into place")
            }
            return converted
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // ------------------------------------------------------------------
    // Pass 1: structure walk (offsets only, no payload copies).
    // ------------------------------------------------------------------

    private class Plan(
        val graph: FieldRange, // the ModelProto.graph field (tag..end)
        val graphPayload: FieldRange, // its length-delimited payload
        val tensors: List<TensorInfo>, // initializer fields in order
    )

    private fun walkModel(raf: RandomAccessFile): Plan {
        var graph: FieldRange? = null
        var graphPayload: FieldRange? = null
        forEachField(raf, 0, raf.length()) { f ->
            if (f.tag == GRAPH_FIELD && f.wire == 2) {
                graph = FieldRange(f.start, f.end)
                graphPayload = FieldRange(f.payloadStart, f.end)
            }
        }
        val g = graph ?: throw IllegalStateException("onnx model has no graph")
        val gp = graphPayload!!
        val tensors = ArrayList<TensorInfo>()
        forEachField(raf, gp.start, gp.end) { f ->
            if (f.tag == INITIALIZER_FIELD && f.wire == 2) {
                tensors.add(parseTensor(raf, f))
            }
        }
        return Plan(g, gp, tensors)
    }

    private class VisitorField(
        val tag: Int,
        val wire: Int,
        val start: Long,
        val end: Long,
        val payloadStart: Long,
        val payloadLen: Long,
    )

    private fun parseTensor(raf: RandomAccessFile, field: VisitorField): TensorInfo {
        var dataType = 0
        var dataTypeValuePos = 0L
        var dataTypeValueLen = 0
        var rawField: FieldRange? = null
        var rawPayloadStart = 0L
        var rawPayloadLen = 0L
        var external = false
        forEachField(raf, field.payloadStart, field.end) { tf ->
            when {
                tf.tag == TENSOR_DATA_TYPE && tf.wire == 0 -> {
                    dataType = readVarintValue(raf, tf.payloadStart, tf.payloadLen).toInt()
                    dataTypeValuePos = tf.payloadStart
                    dataTypeValueLen = tf.payloadLen.toInt()
                }
                tf.tag == TENSOR_RAW_DATA && tf.wire == 2 -> {
                    rawField = FieldRange(tf.start, tf.end)
                    rawPayloadStart = tf.payloadStart
                    rawPayloadLen = tf.payloadLen
                }
                tf.tag == TENSOR_DATA_LOCATION && tf.wire == 0 ->
                    external = readVarintValue(raf, tf.payloadStart, tf.payloadLen) == 2L
            }
        }
        return TensorInfo(
            field = FieldRange(field.start, field.end),
            payloadStart = field.payloadStart,
            dataType = dataType,
            dataTypeValuePos = dataTypeValuePos,
            dataTypeValueLen = dataTypeValueLen,
            rawField = rawField,
            rawPayloadStart = rawPayloadStart,
            rawPayloadLen = rawPayloadLen,
            externalData = external,
        )
    }

    private inline fun forEachField(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        visit: (VisitorField) -> Unit,
    ) {
        var pos = start
        raf.seek(pos)
        while (pos < end) {
            val fieldStart = pos
            // read tag+wire varint
            raf.seek(pos)
            var key = 0L
            var shift = 0
            var headerLen = 0
            while (true) {
                val b = raf.read()
                if (b < 0) throw IllegalStateException("truncated protobuf")
                headerLen++
                key = key or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            pos += headerLen
            val tag = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when (wire) {
                0 -> {
                    // varint value
                    var len = 0
                    while (true) {
                        val b = raf.read()
                        len++
                        if (b < 0) throw IllegalStateException("truncated varint")
                        if (b and 0x80 == 0) break
                    }
                    pos += len
                    visit(VisitorField(tag, wire, fieldStart, pos, pos - len, len.toLong()))
                }
                1 -> {
                    pos += 8
                    visit(VisitorField(tag, wire, fieldStart, pos, pos - 8, 8))
                }
                2 -> {
                    raf.seek(pos)
                    var len = 0L
                    var s = 0
                    while (true) {
                        val b = raf.read()
                        s++
                        if (b < 0) throw IllegalStateException("truncated length")
                        len = len or ((b and 0x7F).toLong() shl ((s - 1) * 7))
                        if (b and 0x80 == 0) break
                    }
                    pos += s
                    visit(VisitorField(tag, wire, fieldStart, pos + len, pos, len))
                    pos += len
                }
                5 -> {
                    pos += 4
                    visit(VisitorField(tag, wire, fieldStart, pos, pos - 4, 4))
                }
                else -> throw IllegalStateException("unsupported wire type $wire")
            }
            raf.seek(pos)
        }
    }

    private fun readVarintValue(raf: RandomAccessFile, pos: Long, len: Long): Long {
        raf.seek(pos)
        var result = 0L
        var shift = 0
        for (i in 0 until len) {
            val b = raf.read()
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
        }
        return result
    }

    // ------------------------------------------------------------------
    // Pass 2: streaming write.
    // ------------------------------------------------------------------

    private fun writeUpcast(raf: RandomAccessFile, out: WritableByteChannel, plan: Plan): Int {
        val needsGraphRewrite = plan.tensors.any {
            it.dataType == DATA_TYPE_FLOAT16 && !it.externalData && it.rawField != null
        }
        if (!needsGraphRewrite) {
            copyRange(raf, out, 0, raf.length())
            return 0
        }

        // Compute the new graph payload size first (needed for the
        // enclosing length prefix).
        var newGraphPayloadSize = 0L
        forEachField(raf, plan.graphPayload.start, plan.graphPayload.end) { f ->
            if (f.tag == INITIALIZER_FIELD && f.wire == 2) {
                val t = plan.tensors.first { it.field.start == f.start }
                newGraphPayloadSize += rewrittenTensorFieldSize(t)
            } else {
                newGraphPayloadSize += (f.end - f.start)
            }
        }
        val newGraphFieldSize = varintSize(GRAPH_FIELD.toLong() shl 3 or 2L) +
            varintSize(newGraphPayloadSize) + newGraphPayloadSize

        // ModelProto: copy every field, substituting the graph field.
        var converted = 0
        forEachField(raf, 0, raf.length()) { f ->
            if (f.tag == GRAPH_FIELD && f.wire == 2) {
                writeVarint(out, GRAPH_FIELD.toLong() shl 3 or 2L)
                writeVarint(out, newGraphPayloadSize)
                forEachField(raf, plan.graphPayload.start, plan.graphPayload.end) { gf ->
                    if (gf.tag == INITIALIZER_FIELD && gf.wire == 2) {
                        val t = plan.tensors.first { it.field.start == gf.start }
                        if (t.dataType == DATA_TYPE_FLOAT16 && !t.externalData && t.rawField != null) {
                            writeConvertedTensor(raf, out, t)
                            converted++
                        } else {
                            copyRange(raf, out, gf.start, gf.end)
                        }
                    } else {
                        copyRange(raf, out, gf.start, gf.end)
                    }
                }
            } else {
                copyRange(raf, out, f.start, f.end)
            }
        }
        return converted
    }

    /**
     * Size of the rewritten initializer's payload. Every inner field
     * keeps its size except data_type (varint 10 -> 1, same width for
     * this model but computed) and raw_data (payload doubles AND its
     * length varint may grow — the classic off-by-one that desynced
     * the first implementation).
     */
    private fun newTensorPayloadSize(t: TensorInfo): Long {
        val oldRawFieldSize = t.rawField!!.end - t.rawField.start
        val oldTypeFieldSize = 1L + t.dataTypeValueLen // tag varint + value varint
        val newRawFieldSize = 1L + varintSize(t.rawPayloadLen * 2) + t.rawPayloadLen * 2
        val newTypeFieldSize = 1L + varintSize(DATA_TYPE_FLOAT.toLong())
        val oldPayload = t.field.end - t.payloadStart
        return oldPayload - oldRawFieldSize - oldTypeFieldSize + newRawFieldSize + newTypeFieldSize
    }

    private fun rewrittenTensorFieldSize(t: TensorInfo): Long {
        if (t.dataType != DATA_TYPE_FLOAT16 || t.externalData || t.rawField == null) {
            return t.field.end - t.field.start
        }
        val newPayload = newTensorPayloadSize(t)
        return varintSize((INITIALIZER_FIELD.toLong() shl 3) or 2L) + varintSize(newPayload) + newPayload
    }

    /** Stream one initializer field, widening its fp16 raw_data to fp32. */
    private fun writeConvertedTensor(
        raf: RandomAccessFile,
        out: WritableByteChannel,
        t: TensorInfo,
    ) {
        val newPayload = newTensorPayloadSize(t)
        writeVarint(out, (INITIALIZER_FIELD.toLong() shl 3) or 2L)
        writeVarint(out, newPayload)
        forEachField(raf, t.payloadStart, t.field.end) { tf ->
            when {
                tf.tag == TENSOR_DATA_TYPE && tf.wire == 0 -> {
                    writeVarint(out, (TENSOR_DATA_TYPE.toLong() shl 3) or 0L)
                    writeVarint(out, DATA_TYPE_FLOAT.toLong())
                }
                tf.tag == TENSOR_RAW_DATA && tf.wire == 2 -> {
                    require(t.rawPayloadLen % 2 == 0L) { "odd fp16 raw_data length" }
                    writeVarint(out, (TENSOR_RAW_DATA.toLong() shl 3) or 2L)
                    writeVarint(out, t.rawPayloadLen * 2)
                    convertStream(raf, out, t.rawPayloadStart, t.rawPayloadLen)
                }
                else -> copyRange(raf, out, tf.start, tf.end)
            }
        }
    }

    /** Convert fp16 LE bytes to fp32 LE bytes in bounded chunks. */
    private fun convertStream(
        raf: RandomAccessFile,
        out: WritableByteChannel,
        start: Long,
        len: Long,
    ) {
        val inBuf = ByteArray(CHUNK_CONVERT_BYTES)
        val outBuf = ByteBuffer.allocate(CHUNK_CONVERT_BYTES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = len
        var pos = start
        while (remaining > 0) {
            val step = minOf(remaining, CHUNK_CONVERT_BYTES.toLong()).toInt() and 1.inv() // even
            raf.seek(pos)
            raf.readFully(inBuf, 0, step)
            outBuf.clear()
            var i = 0
            while (i < step) {
                val half = (inBuf[i].toInt() and 0xFF) or (inBuf[i + 1].toInt() shl 8)
                outBuf.putInt(halfToFloatBits(half))
                i += 2
            }
            outBuf.flip()
            while (outBuf.hasRemaining()) out.write(outBuf)
            pos += step
            remaining -= step
        }
    }

    private fun copyRange(raf: RandomAccessFile, out: WritableByteChannel, start: Long, end: Long) {
        val buf = ByteBuffer.allocate(minOf(CHUNK_CONVERT_BYTES, (end - start).toInt().coerceAtLeast(1)))
        var pos = start
        raf.seek(pos)
        while (pos < end) {
            buf.clear()
            val step = minOf(buf.capacity().toLong(), end - pos).toInt()
            buf.limit(step)
            raf.readFully(buf.array(), 0, step)
            buf.position(step)
            buf.flip()
            while (buf.hasRemaining()) out.write(buf)
            pos += step
        }
    }

    private fun writeVarint(out: WritableByteChannel, value: Long) {
        val buf = ByteArray(10)
        var i = 0
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buf[i++] = v.toByte()
                break
            }
            buf[i++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out.write(ByteBuffer.wrap(buf, 0, i))
    }

    private fun varintSize(value: Long): Int {
        var v = value
        var n = 1
        while (true) {
            if (v and 0x7FL.inv() == 0L) return n
            n++
            v = v ushr 7
        }
    }

    /** IEEE 754 binary16 -> binary32 bit pattern. */
    internal fun halfToFloatBits(h: Int): Int {
        val sign = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1F
        val frac = h and 0x3FF
        val s = sign shl 31
        return when {
            exp == 0 -> if (frac == 0) {
                s // signed zero
            } else {
                // subnormal half -> normal float
                var e = 127 - 15 + 1
                var f = frac
                while (f and 0x400 == 0) {
                    f = f shl 1
                    e--
                }
                f = f and 0x3FF
                s or (e shl 23) or (f shl 13)
            }
            exp == 0x1F -> s or (0xFF shl 23) or (frac shl 13) // inf / nan
            else -> s or ((exp - 15 + 127) shl 23) or (frac shl 13)
        }
    }
}
