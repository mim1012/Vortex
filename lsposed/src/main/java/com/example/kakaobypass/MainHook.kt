package com.example.kakaobypass

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.kakao.taxi.driver") {
            return
        }

        val classLoader = lpparam.classLoader

        try {
            // 대상 클래스 및 메소드 (정적 분석으로 파악)
            val refreshLimiterClass = XposedHelpers.findClass("B7.e", classLoader)

            // l() 메소드를 후킹하여 항상 true를 반환하도록 함
            XposedHelpers.findAndHookMethod(refreshLimiterClass, "l", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 원본 메소드 실행 전에 결과값을 true로 설정
                    param.result = true
                }
            })
        } catch (e: Throwable) {
            // 에러 처리
        }
    }
}
