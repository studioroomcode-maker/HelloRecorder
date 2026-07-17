package com.studioroomkr.hellorecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SttModel.sha256 이 파일 내용의 SHA-256 을 올바로 계산하는지 검증.
 * 이 해시가 모델 무결성 검증의 근거이므로, 계산 자체가 틀리면 검증이 통째로 무의미해진다.
 */
@RunWith(AndroidJUnit4::class)
class SttModelHashTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tmp(name: String) = File(ctx.cacheDir, name).also { it.delete() }

    @Test
    fun 빈_파일의_SHA256() {
        val f = tmp("empty.bin").apply { writeBytes(ByteArray(0)) }
        // 알려진 상수: 빈 입력의 SHA-256
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SttModel.sha256(f)
        )
        f.delete()
    }

    @Test
    fun 알려진_바이트열의_SHA256() {
        val f = tmp("abc.bin").apply { writeBytes("abc".toByteArray(Charsets.US_ASCII)) }
        // "abc" 의 SHA-256(널리 알려진 테스트 벡터)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SttModel.sha256(f)
        )
        f.delete()
    }

    @Test
    fun 한_바이트만_달라도_해시가_바뀐다() {
        val a = tmp("a.bin").apply { writeBytes(ByteArray(4096) { 0 }) }
        val b = tmp("b.bin").apply {
            writeBytes(ByteArray(4096) { 0 }.also { it[4095] = 1 })
        }
        // 잘린/손상 파일이 통과하지 못하는 근거: 내용이 조금이라도 다르면 해시가 갈린다.
        assertNotEquals(SttModel.sha256(a), SttModel.sha256(b))
        a.delete(); b.delete()
    }

    @Test
    fun 버퍼경계_넘는_크기도_정확하다() {
        // sha256 이 64KB 버퍼로 나눠 읽으므로, 그 경계를 넘는 크기에서 이어붙이기가 맞는지 확인.
        // 같은 내용을 한 번에 vs 두 청크로 넣어도 결과가 같아야 한다(스트리밍 정확성).
        val big = tmp("big.bin").apply {
            outputStream().use { os ->
                val chunk = ByteArray(100_000) { (it % 251).toByte() }
                os.write(chunk)
            }
        }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(100_000) { (it % 251).toByte() })
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, SttModel.sha256(big))
        big.delete()
    }
}
