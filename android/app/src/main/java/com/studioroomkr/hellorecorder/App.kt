package com.studioroomkr.hellorecorder

import android.app.Application

/**
 * 앱 전역 초기화 지점.
 * 가장 먼저 온디바이스 크래시 로거를 설치해, 어느 화면/서비스에서든 미처리 예외를 기록한다.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Pro 상태를 프로세스 시작 시 복원한다. Activity 없이 서비스만 먼저 뜨는 경로
        // (부팅 리시버·위젯)에서도 AudioEngine 의 Pro 게이트(강조·Silero)가 올바르게 동작하도록.
        Pro.init(this)
    }
}
