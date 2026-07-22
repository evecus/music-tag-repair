package com.musictagrepair.data

import java.util.zip.Inflater

/**
 * QRC 歌词解密（非标准 3DES-ECB + zlib inflate）
 *
 * 由 /tmp/any-listen/.../tx/qrcDecode.ts 1:1 移植。
 *
 * **重要**：QRC 使用非标准 DES 变体，其中 S2[23]=15、S4[53]=10 两处与标准 DES 不同（标准为 14、1）。
 * 不可改为标准值，否则解密结果错误。
 *
 * 数据流程：hex 字符串 -> 字节 -> 3DES 解密 -> zlib inflate -> UTF-8 文本。
 */
object TxQrcDecoder {

    private const val DES_ENCRYPT = 1
    private const val DES_DECRYPT = 0

    private val sbox: Array<IntArray> = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13,
            6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10,
            4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8,
            15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12,
            11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10,
            13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2,
            8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12,
            3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9,
            12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val key_rnd_shift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val key_perm_c = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
    private val key_perm_d = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    private val key_compression = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44,
        32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    // QRC 固定密钥：!@#)(*$%123ZXC!@!@#)(NHL
    private val QRC_KEY = byteArrayOf(33, 64, 35, 41, 40, 42, 36, 37, 49, 50, 51, 90, 88, 67, 33, 64, 33, 64, 35, 41, 40, 78, 72, 76)

    // 全部以无符号 32 位处理
    private fun bitnum(a: ByteArray, b: Int, c: Int): Int {
        val byteIndex = (b / 32) * 4 + 3 - (b % 32) / 8
        if (byteIndex < 0 || byteIndex >= a.size) return 0
        val v = a[byteIndex].toInt() and 0xFF
        return ((v ushr (7 - (b % 8))) and 1) shl c
    }

    private fun bitnum_intr(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitnum_intl(a: Int, b: Int, c: Int): Int {
        // (((a << b) & 0x80000000) >>> c) 全部按无符号处理
        val shifted = (a shl b)
        return ((shifted and 0x80000000.toInt()) ushr c) and 0xFFFFFFFF.toInt()
    }

    private fun sbox_bit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    private fun initial_permutation(input: ByteArray): IntArray {
        var s0 = 0
        s0 = s0 or bitnum(input, 57, 31)
        s0 = s0 or bitnum(input, 49, 30)
        s0 = s0 or bitnum(input, 41, 29)
        s0 = s0 or bitnum(input, 33, 28)
        s0 = s0 or bitnum(input, 25, 27)
        s0 = s0 or bitnum(input, 17, 26)
        s0 = s0 or bitnum(input, 9, 25)
        s0 = s0 or bitnum(input, 1, 24)
        s0 = s0 or bitnum(input, 59, 23)
        s0 = s0 or bitnum(input, 51, 22)
        s0 = s0 or bitnum(input, 43, 21)
        s0 = s0 or bitnum(input, 35, 20)
        s0 = s0 or bitnum(input, 27, 19)
        s0 = s0 or bitnum(input, 19, 18)
        s0 = s0 or bitnum(input, 11, 17)
        s0 = s0 or bitnum(input, 3, 16)
        s0 = s0 or bitnum(input, 61, 15)
        s0 = s0 or bitnum(input, 53, 14)
        s0 = s0 or bitnum(input, 45, 13)
        s0 = s0 or bitnum(input, 37, 12)
        s0 = s0 or bitnum(input, 29, 11)
        s0 = s0 or bitnum(input, 21, 10)
        s0 = s0 or bitnum(input, 13, 9)
        s0 = s0 or bitnum(input, 5, 8)
        s0 = s0 or bitnum(input, 63, 7)
        s0 = s0 or bitnum(input, 55, 6)
        s0 = s0 or bitnum(input, 47, 5)
        s0 = s0 or bitnum(input, 39, 4)
        s0 = s0 or bitnum(input, 31, 3)
        s0 = s0 or bitnum(input, 23, 2)
        s0 = s0 or bitnum(input, 15, 1)
        s0 = s0 or bitnum(input, 7, 0)

        var s1 = 0
        s1 = s1 or bitnum(input, 56, 31)
        s1 = s1 or bitnum(input, 48, 30)
        s1 = s1 or bitnum(input, 40, 29)
        s1 = s1 or bitnum(input, 32, 28)
        s1 = s1 or bitnum(input, 24, 27)
        s1 = s1 or bitnum(input, 16, 26)
        s1 = s1 or bitnum(input, 8, 25)
        s1 = s1 or bitnum(input, 0, 24)
        s1 = s1 or bitnum(input, 58, 23)
        s1 = s1 or bitnum(input, 50, 22)
        s1 = s1 or bitnum(input, 42, 21)
        s1 = s1 or bitnum(input, 34, 20)
        s1 = s1 or bitnum(input, 26, 19)
        s1 = s1 or bitnum(input, 18, 18)
        s1 = s1 or bitnum(input, 10, 17)
        s1 = s1 or bitnum(input, 2, 16)
        s1 = s1 or bitnum(input, 60, 15)
        s1 = s1 or bitnum(input, 52, 14)
        s1 = s1 or bitnum(input, 44, 13)
        s1 = s1 or bitnum(input, 36, 12)
        s1 = s1 or bitnum(input, 28, 11)
        s1 = s1 or bitnum(input, 20, 10)
        s1 = s1 or bitnum(input, 12, 9)
        s1 = s1 or bitnum(input, 4, 8)
        s1 = s1 or bitnum(input, 62, 7)
        s1 = s1 or bitnum(input, 54, 6)
        s1 = s1 or bitnum(input, 46, 5)
        s1 = s1 or bitnum(input, 38, 4)
        s1 = s1 or bitnum(input, 30, 3)
        s1 = s1 or bitnum(input, 22, 2)
        s1 = s1 or bitnum(input, 14, 1)
        s1 = s1 or bitnum(input, 6, 0)

        return intArrayOf(s0, s1)
    }

    private fun inverse_permutation(s0: Int, s1: Int, out: ByteArray) {
        out[3] = (
            bitnum_intr(s1, 7, 7) or
                bitnum_intr(s0, 7, 6) or
                bitnum_intr(s1, 15, 5) or
                bitnum_intr(s0, 15, 4) or
                bitnum_intr(s1, 23, 3) or
                bitnum_intr(s0, 23, 2) or
                bitnum_intr(s1, 31, 1) or
                bitnum_intr(s0, 31, 0)
            ).toByte()
        out[2] = (
            bitnum_intr(s1, 6, 7) or
                bitnum_intr(s0, 6, 6) or
                bitnum_intr(s1, 14, 5) or
                bitnum_intr(s0, 14, 4) or
                bitnum_intr(s1, 22, 3) or
                bitnum_intr(s0, 22, 2) or
                bitnum_intr(s1, 30, 1) or
                bitnum_intr(s0, 30, 0)
            ).toByte()
        out[1] = (
            bitnum_intr(s1, 5, 7) or
                bitnum_intr(s0, 5, 6) or
                bitnum_intr(s1, 13, 5) or
                bitnum_intr(s0, 13, 4) or
                bitnum_intr(s1, 21, 3) or
                bitnum_intr(s0, 21, 2) or
                bitnum_intr(s1, 29, 1) or
                bitnum_intr(s0, 29, 0)
            ).toByte()
        out[0] = (
            bitnum_intr(s1, 4, 7) or
                bitnum_intr(s0, 4, 6) or
                bitnum_intr(s1, 12, 5) or
                bitnum_intr(s0, 12, 4) or
                bitnum_intr(s1, 20, 3) or
                bitnum_intr(s0, 20, 2) or
                bitnum_intr(s1, 28, 1) or
                bitnum_intr(s0, 28, 0)
            ).toByte()
        out[7] = (
            bitnum_intr(s1, 3, 7) or
                bitnum_intr(s0, 3, 6) or
                bitnum_intr(s1, 11, 5) or
                bitnum_intr(s0, 11, 4) or
                bitnum_intr(s1, 19, 3) or
                bitnum_intr(s0, 19, 2) or
                bitnum_intr(s1, 27, 1) or
                bitnum_intr(s0, 27, 0)
            ).toByte()
        out[6] = (
            bitnum_intr(s1, 2, 7) or
                bitnum_intr(s0, 2, 6) or
                bitnum_intr(s1, 10, 5) or
                bitnum_intr(s0, 10, 4) or
                bitnum_intr(s1, 18, 3) or
                bitnum_intr(s0, 18, 2) or
                bitnum_intr(s1, 26, 1) or
                bitnum_intr(s0, 26, 0)
            ).toByte()
        out[5] = (
            bitnum_intr(s1, 1, 7) or
                bitnum_intr(s0, 1, 6) or
                bitnum_intr(s1, 9, 5) or
                bitnum_intr(s0, 9, 4) or
                bitnum_intr(s1, 17, 3) or
                bitnum_intr(s0, 17, 2) or
                bitnum_intr(s1, 25, 1) or
                bitnum_intr(s0, 25, 0)
            ).toByte()
        out[4] = (
            bitnum_intr(s1, 0, 7) or
                bitnum_intr(s0, 0, 6) or
                bitnum_intr(s1, 8, 5) or
                bitnum_intr(s0, 8, 4) or
                bitnum_intr(s1, 16, 3) or
                bitnum_intr(s0, 16, 2) or
                bitnum_intr(s1, 24, 1) or
                bitnum_intr(s0, 24, 0)
            ).toByte()
    }

    private fun des_f(state: Int, key: ByteArray): Int {
        val t1 = (
            bitnum_intl(state, 31, 0) or
                ((state and 0xF0000000.toInt()) ushr 1) or
                bitnum_intl(state, 4, 5) or
                bitnum_intl(state, 3, 6) or
                ((state and 0x0F000000) ushr 3) or
                bitnum_intl(state, 8, 11) or
                bitnum_intl(state, 7, 12) or
                ((state and 0x00F00000) ushr 5) or
                bitnum_intl(state, 12, 17) or
                bitnum_intl(state, 11, 18) or
                ((state and 0x000F0000) ushr 7) or
                bitnum_intl(state, 16, 23)
            )
        val t2 = (
            bitnum_intl(state, 15, 0) or
                (((state and 0x0000F000) shl 15) and 0xFFFFFFFF.toInt()) or
                bitnum_intl(state, 20, 5) or
                bitnum_intl(state, 19, 6) or
                (((state and 0x00000F00) shl 13) and 0xFFFFFFFF.toInt()) or
                bitnum_intl(state, 24, 11) or
                bitnum_intl(state, 23, 12) or
                (((state and 0x000000F0) shl 11) and 0xFFFFFFFF.toInt()) or
                bitnum_intl(state, 28, 17) or
                bitnum_intl(state, 27, 18) or
                (((state and 0x0000000F) shl 9) and 0xFFFFFFFF.toInt()) or
                bitnum_intl(state, 0, 23)
            )

        val lrgstate = intArrayOf(
            (t1 ushr 24) and 0xFF,
            (t1 ushr 16) and 0xFF,
            (t1 ushr 8) and 0xFF,
            (t2 ushr 24) and 0xFF,
            (t2 ushr 16) and 0xFF,
            (t2 ushr 8) and 0xFF,
        )
        for (i in 0 until 6) lrgstate[i] = lrgstate[i] xor (key[i].toInt() and 0xFF)

        val s = (
            (sbox[0][sbox_bit(lrgstate[0] ushr 2)] shl 28) or
                (sbox[1][sbox_bit(((lrgstate[0] and 0x03) shl 4) or (lrgstate[1] ushr 4))] shl 24) or
                (sbox[2][sbox_bit(((lrgstate[1] and 0x0F) shl 2) or (lrgstate[2] ushr 6))] shl 20) or
                (sbox[3][sbox_bit(lrgstate[2] and 0x3F)] shl 16) or
                (sbox[4][sbox_bit(lrgstate[3] ushr 2)] shl 12) or
                (sbox[5][sbox_bit(((lrgstate[3] and 0x03) shl 4) or (lrgstate[4] ushr 4))] shl 8) or
                (sbox[6][sbox_bit(((lrgstate[4] and 0x0F) shl 2) or (lrgstate[5] ushr 6))] shl 4) or
                sbox[7][sbox_bit(lrgstate[5] and 0x3F)]
            ) and 0xFFFFFFFF.toInt()

        return (
            bitnum_intl(s, 15, 0) or
                bitnum_intl(s, 6, 1) or
                bitnum_intl(s, 19, 2) or
                bitnum_intl(s, 20, 3) or
                bitnum_intl(s, 28, 4) or
                bitnum_intl(s, 11, 5) or
                bitnum_intl(s, 27, 6) or
                bitnum_intl(s, 16, 7) or
                bitnum_intl(s, 0, 8) or
                bitnum_intl(s, 14, 9) or
                bitnum_intl(s, 22, 10) or
                bitnum_intl(s, 25, 11) or
                bitnum_intl(s, 4, 12) or
                bitnum_intl(s, 17, 13) or
                bitnum_intl(s, 30, 14) or
                bitnum_intl(s, 9, 15) or
                bitnum_intl(s, 1, 16) or
                bitnum_intl(s, 7, 17) or
                bitnum_intl(s, 23, 18) or
                bitnum_intl(s, 13, 19) or
                bitnum_intl(s, 31, 20) or
                bitnum_intl(s, 26, 21) or
                bitnum_intl(s, 2, 22) or
                bitnum_intl(s, 8, 23) or
                bitnum_intl(s, 18, 24) or
                bitnum_intl(s, 12, 25) or
                bitnum_intl(s, 29, 26) or
                bitnum_intl(s, 5, 27) or
                bitnum_intl(s, 21, 28) or
                bitnum_intl(s, 10, 29) or
                bitnum_intl(s, 3, 30) or
                bitnum_intl(s, 24, 31)
            )
    }

    private fun des_crypt(input: ByteArray, schedule: Array<ByteArray>, output: ByteArray) {
        val ip = initial_permutation(input)
        var s0 = ip[0]
        var s1 = ip[1]
        for (i in 0 until 15) {
            val prev = s1
            s1 = (des_f(s1, schedule[i]) xor s0) and 0xFFFFFFFF.toInt()
            s0 = prev
        }
        s0 = (des_f(s1, schedule[15]) xor s0) and 0xFFFFFFFF.toInt()
        inverse_permutation(s0, s1, output)
    }

    private fun key_schedule(key: ByteArray, mode: Int): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitnum(key, key_perm_c[i], 31 - i)
            d = d or bitnum(key, key_perm_d[i], 31 - i)
        }
        for (i in 0 until 16) {
            val shift = key_rnd_shift[i]
            c = (((c shl shift) or (c ushr (28 - shift))) and 0xFFFFFFF0.toInt())
            d = (((d shl shift) or (d ushr (28 - shift))) and 0xFFFFFFF0.toInt())
            val togen = if (mode == DES_DECRYPT) 15 - i else i
            for (j in 0 until 24) {
                schedule[togen][j / 8] = (schedule[togen][j / 8].toInt() or bitnum_intr(c, key_compression[j], 7 - (j % 8))).toByte()
            }
            for (j in 24 until 48) {
                schedule[togen][j / 8] = (schedule[togen][j / 8].toInt() or bitnum_intr(d, key_compression[j] - 27, 7 - (j % 8))).toByte()
            }
        }
        return schedule
    }

    private fun tripledes_key_setup(key: ByteArray, mode: Int): Array<Array<ByteArray>> {
        return if (mode == DES_ENCRYPT) {
            arrayOf(
                key_schedule(key.copyOfRange(0, 8), DES_ENCRYPT),
                key_schedule(key.copyOfRange(8, 16), DES_DECRYPT),
                key_schedule(key.copyOfRange(16, 24), DES_ENCRYPT),
            )
        } else {
            arrayOf(
                key_schedule(key.copyOfRange(16, 24), DES_DECRYPT),
                key_schedule(key.copyOfRange(8, 16), DES_ENCRYPT),
                key_schedule(key.copyOfRange(0, 8), DES_DECRYPT),
            )
        }
    }

    private fun tripledes_crypt(input: ByteArray, schedule: Array<Array<ByteArray>>, output: ByteArray) {
        val buf = ByteArray(8)
        des_crypt(input, schedule[0], buf)
        des_crypt(buf, schedule[1], output)
        des_crypt(output, schedule[2], buf)
        System.arraycopy(buf, 0, output, 0, 8)
    }

    /**
     * 解密 QQ 音乐 QRC 歌词。
     * @param hexData 服务端返回的十六进制字符串
     * @return 解密并解压后的歌词文本（解析失败时返回空字符串）
     */
    fun decodeQrc(hexData: String): String {
        if (hexData.isEmpty() || hexData.length % 2 != 0) return ""
        val encrypted = hexToBytes(hexData) ?: return ""
        if (encrypted.isEmpty()) return ""

        val schedule = tripledes_key_setup(QRC_KEY, DES_DECRYPT)

        val block = ByteArray(8)
        var i = 0
        while (i + 8 <= encrypted.size) {
            val slice = encrypted.copyOfRange(i, i + 8)
            tripledes_crypt(slice, schedule, block)
            System.arraycopy(block, 0, encrypted, i, 8)
            i += 8
        }

        return runCatching {
            // Z_SYNC_FLUSH 等价：用 Inflater 配 nowrapped=true，且将数据当作 zlib stream（含 zlib 头）
            // 简化策略：先用 nowrapped=false 尝试，失败后用 nowrapped=true 再试
            val inflated = inflate(encrypted, false) ?: inflate(encrypted, true) ?: return ""
            String(inflated, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun inflate(data: ByteArray, nowrapped: Boolean): ByteArray? = runCatching {
        val inflater = Inflater(nowrapped)
        inflater.setInput(data)
        val output = ByteArray(16384)
        val out = java.io.ByteArrayOutputStream()
        while (!inflater.finished()) {
            val n = inflater.inflate(output)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(output, 0, n)
            }
        }
        inflater.end()
        out.toByteArray()
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray? = runCatching {
        val len = hex.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        out
    }.getOrNull()
}
