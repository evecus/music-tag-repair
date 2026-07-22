package com.musictagrepair.data

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 API 加密工具 (eapi / weapi)
 *
 * 与 Dart/Rust 版等价，使用 JDK 内置的 javax.crypto + 手动 NoPadding RSA。
 */
object NeteaseCrypto {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val EAPI_KEY = "e82ckenh8dichen8"

    // 网易云官方 RSA 公钥（PEM）
    private const val PUBLIC_KEY_PEM =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"

    // 懒加载解析得到的 RSA 公钥 modulus 和 exponent
    private val rsaModulus: BigInteger by lazy { parseRsaPublicKey().first }
    private val rsaExponent: BigInteger by lazy { parseRsaPublicKey().second }

    private val RANDOM = SecureRandom()
    private const val RANDOM_CHARS =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.length)])
        }
        return sb.toString()
    }

    private fun aesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * 从 PEM 中解析出 RSA 公钥（modulus + exponent）
     */
    private fun parseRsaPublicKey(): Pair<BigInteger, BigInteger> {
        val der = java.util.Base64.getDecoder().decode(PUBLIC_KEY_PEM)
        val spec = X509EncodedKeySpec(der)
        val key = KeyFactory.getInstance("RSA").generatePublic(spec) as java.security.interfaces.RSAPublicKey
        return key.modulus to key.publicExponent
    }

    /**
     * 无填充 RSA 加密：直接 modpow(data, e, n)
     * 网易云使用的就是这种非标准方式
     */
    private fun rsaEncryptNoPadding(data: ByteArray): ByteArray {
        // 网易云将 secretKey 反转后，作为 BigInteger(1, reversed) 进行 modpow
        val dataBigInt = BigInteger(1, data)
        val encrypted = dataBigInt.modPow(rsaExponent, rsaModulus)
        // 转换为 256 字节的 hex 字符串
        return encrypted.toString(16).padStart(256, '0').hexToByteArray()
    }

    private fun String.hexToByteArray(): ByteArray {
        val len = this.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /**
     * eapi 加密
     */
    fun eapi(url: String, data: Map<String, Any?>): Map<String, String> {
        val text = JsonUtils.encodeMap(data)
        val message = "nobody${url}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val plain = "$url-36cd479b6b5-$text-36cd479b6b5-$digest"

        val encrypted = aesEcbEncrypt(
            plain.toByteArray(Charsets.UTF_8),
            EAPI_KEY.toByteArray(Charsets.UTF_8),
        )
        return mapOf("params" to encrypted.toHex().uppercase())
    }

    /**
     * weapi 加密
     */
    fun weapi(data: Map<String, Any?>): Map<String, String> {
        val text = JsonUtils.encodeMap(data)
        val secretKey = randomString(16)

        val first = aesCbcEncrypt(
            text.toByteArray(Charsets.UTF_8),
            PRESET_KEY.toByteArray(Charsets.UTF_8),
            IV.toByteArray(Charsets.UTF_8),
        )
        val params = aesCbcEncrypt(
            first,
            secretKey.toByteArray(Charsets.UTF_8),
            IV.toByteArray(Charsets.UTF_8),
        ).toHex()

        val reversed = secretKey.reversed().toByteArray()
        val encSecKey = rsaEncryptNoPadding(reversed)

        return mapOf("params" to params, "encSecKey" to encSecKey.toHex())
    }
}
