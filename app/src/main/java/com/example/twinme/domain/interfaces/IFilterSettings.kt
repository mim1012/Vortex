package com.example.twinme.domain.interfaces

enum class ConditionMode {
    CONDITION_1_2,  // 금액 기반 (키워드 있으면 키워드도 적용)
    CONDITION_3      // 인천공항 출발 + 금액
}

interface IFilterSettings {
    val conditionMode: ConditionMode
    val minAmount: Int  // 조건1: 금액만 체크하는 최소 금액
    val keywordMinAmount: Int  // 조건2: 키워드 + 금액 체크하는 최소 금액
    val airportMinAmount: Int  // 조건3: 인천공항 출발 전용 최소 금액
    val keywords: List<String>
    val allowHourlyReservation: Boolean  // 1시간 예약 허용 여부
    fun shouldAcceptByAmount(amount: Int): Boolean
    fun shouldAcceptByKeyword(origin: String, destination: String, amount: Int): Boolean
    fun validateSettings(): Boolean
}
